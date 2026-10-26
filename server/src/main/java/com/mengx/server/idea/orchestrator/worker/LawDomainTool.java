package com.mengx.server.idea.orchestrator.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.mengx.server.idea.IdeaChatService.InMessage;
import com.mengx.server.idea.IdeaChatService.StreamEvent;
import com.mengx.server.idea.IdeaMessageEntity;
import com.mengx.server.idea.IdeaMessageRepository;
import com.mengx.server.idea.IdeaSessionEntity;
import com.mengx.server.idea.LawQuizFlowService;
import com.mengx.server.idea.orchestrator.DomainTool;
import com.mengx.server.law.LawAgentService;
import com.mengx.server.law.LawProfileService;
import com.mengx.server.law.LawQuestionEntity;
import com.mengx.server.law.LawSearchService;
import com.mengx.server.law.LawSearchService.Citation;
import com.mengx.server.law.LawSearchService.SearchHit;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.FluxSink;

/**
 * 法学领域工具：Host 调用后接管整轮，流式输出法条/出题/判卷结果直达用户。
 * 两条路径：法学子流程（出题/交卷/学情，LawQuizFlowService 接管）或法条 RAG 问答。
 * 输出不经 Host 二次包装，保持法学专业性。
 */
@Component
public class LawDomainTool implements DomainTool {

    private static final Logger log = LoggerFactory.getLogger(LawDomainTool.class);
    private static final String CHANNEL_LAW = "law";
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final int FRAME_WINDOW = 40;
    /** 法学免责声明（随正文流式下发并落库，替代旧 OutputWrapper 的领域框匹配注入） */
    private static final String DISCLAIMER =
            "\n\n📌 以上内容仅供学习参考，不构成法律意见。具体法律问题请咨询专业律师。";

    private final LawQuizFlowService lawQuizFlow;
    private final LawSearchService lawSearch;
    private final LawAgentService lawAgent;
    private final LawProfileService lawProfile;
    private final ObjectProvider<StreamingChatModel> streamingModelProvider;
    private final IdeaMessageRepository messageRepository;

    public LawDomainTool(LawQuizFlowService lawQuizFlow, LawSearchService lawSearch,
                         LawAgentService lawAgent, LawProfileService lawProfile,
                         ObjectProvider<StreamingChatModel> streamingModelProvider,
                         IdeaMessageRepository messageRepository) {
        this.lawQuizFlow = lawQuizFlow;
        this.lawSearch = lawSearch;
        this.lawAgent = lawAgent;
        this.lawProfile = lawProfile;
        this.streamingModelProvider = streamingModelProvider;
        this.messageRepository = messageRepository;
    }

    @Override
    public String name() {
        return "law_consult";
    }

    @Override
    public String channel() {
        return CHANNEL_LAW;
    }

    @Override
    public String description() {
        return "处理所有法律相关问题。包括：法律概念解释（如\"什么是正当防卫\"）、法条含义、合同起草与审查、"
                + "纠纷维权途径、案例分析；以及法考备考——用户说\"刷题\"\"出题\"\"考我\"\"考考我\"\"来几道题\""
                + "\"练一练\"\"练习一下\"\"我想做题\"等想要做题的请求时调用此工具生成题目；"
                + "用户输入答题选项（A/B/C/D 或 1A、1B 2C）时调用此工具判分；用户查学情时调用。"
                + "纯闲聊、技术问题、股票分析等非法律内容不应调用。";
    }

    @Override
    public String parametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "question": {
                      "type": "string",
                      "description": "用户的法律问题或交卷内容，原样传入"
                    }
                  },
                  "required": ["question"]
                }
                """;
    }

    @Override
    public String invoke(IdeaSessionEntity session, List<InMessage> history, JsonNode args,
                         FluxSink<StreamEvent> sink) {
        String latestUser = args.has("question") ? args.get("question").asText() : "";
        if (latestUser.isBlank()) {
            latestUser = latestUserFromHistory(history);
        }
        // 法学子流程（出题/交卷/学情）：命中即接管整轮，返回值为落库文本（user/assistant 轮统一由编排器收口落库）
        String quizReply = lawQuizFlow.handle(sink, session, history);
        if (quizReply != null) {
            log.info("法学子流程接管本轮（sessionId={}）", session.getId());
            return quizReply;
        }
        return streamLawQa(session, history, latestUser, sink);
    }

    /** 法条 RAG 问答：检索 + 组装 + 流式输出，返回完整回复文本供落库 */
    private String streamLawQa(IdeaSessionEntity session, List<InMessage> history,
                               String latestUser, FluxSink<StreamEvent> sink) {
        Long sessionId = session.getId();
        StreamingChatModel model = streamingModelProvider.getIfAvailable();
        if (model == null) {
            log.warn("流式模型未配置，法学问答降级（sessionId={}）", sessionId);
            sink.next(new StreamEvent(StreamEvent.ERROR, HostWorker.NOT_CONFIGURED_MSG));
            sink.next(new StreamEvent(StreamEvent.DONE, null));
            sink.complete();
            return "";
        }
        log.info("法学通道作答（sessionId={}）", sessionId);
        // 分帧隔离：法学上下文只取 law 通道历史 + 当前用户消息
        List<InMessage> lawHistory = frameHistory(sessionId);
        lawHistory.add(new InMessage(ROLE_USER, latestUser));
        boolean examIntent = LawQuizFlowService.isExamIntent(latestUser);
        sink.next(new StreamEvent(StreamEvent.THINKING,
                examIntent ? "认出是法学之问，翻阅法典与历年真题…" : "认出是法学之问，翻阅法典…"));
        List<SearchHit> refs;
        List<LawQuestionEntity> examHits = List.of();
        try {
            refs = lawSearch.search(latestUser, lawCodeHint(lawHistory));
            if (examIntent) {
                examHits = lawSearch.searchExamQuestions(latestUser, 3);
            }
        } catch (Exception e) {
            log.warn("法学检索失败（sessionId={}）：{}", sessionId, e.getMessage());
            sink.next(new StreamEvent(StreamEvent.ERROR, "法学助手一时翻不到法典，稍后再问一声便是～"));
            sink.next(new StreamEvent(StreamEvent.DONE, null));
            sink.complete();
            return "";
        }
        sink.next(new StreamEvent(StreamEvent.THINKING,
                examIntent ? "检出法条 " + refs.size() + " 条、真题 " + examHits.size() + " 道"
                        : "检出法条 " + refs.size() + " 条"));
        // 引用下发
        List<Citation> citations = new ArrayList<>();
        var fromArticles = LawSearchService.toCitations(refs);
        if (fromArticles != null) {
            citations.addAll(fromArticles);
        }
        var fromExams = lawSearch.examCitations(examHits);
        if (fromExams != null) {
            citations.addAll(fromExams);
        }
        if (!citations.isEmpty()) {
            sink.next(new StreamEvent(StreamEvent.CITATIONS, toJson(citations)));
        }
        // 组装消息并流式调用
        List<ChatMessage> messages = lawAgent.buildMessages(
                session.getNickname(), lawHistory, refs, examHits, lawProfile.chatHint(session.getUserId()));
        CompletableFuture<String> future = new CompletableFuture<>();
        StringBuilder sb = new StringBuilder();
        model.chat(messages, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
                sb.append(token);
                sink.next(new StreamEvent(StreamEvent.CHUNK, token));
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                future.complete(sb.toString());
            }

            @Override
            public void onError(Throwable error) {
                future.completeExceptionally(error);
            }
        });
        try {
            // 法学免责声明随正文一并流式下发与落库（展示=落库一致）
            String reply = future.get(90, TimeUnit.SECONDS) + DISCLAIMER;
            sink.next(new StreamEvent(StreamEvent.CHUNK, DISCLAIMER));
            sink.next(new StreamEvent(StreamEvent.DONE, null));
            sink.complete();
            return reply;
        } catch (Exception e) {
            log.warn("法学模型调用失败（sessionId={}）", sessionId, e);
            if (sb.length() > 0) {
                sink.next(new StreamEvent(StreamEvent.ERROR, "仙官暂时开小差了，稍后再试试～"));
            }
            sink.next(new StreamEvent(StreamEvent.DONE, null));
            sink.complete();
            return sb.toString();
        }
    }

    private List<InMessage> frameHistory(Long sessionId) {
        List<IdeaMessageEntity> rows = messageRepository.findFrameMessages(
                sessionId, List.of(ROLE_USER, ROLE_ASSISTANT), List.of(CHANNEL_LAW));
        // 窗口截断：只取最近 40 条，防止长会话 token 膨胀（对齐 Host 帧窗口设计）
        int from = Math.max(0, rows.size() - FRAME_WINDOW);
        List<InMessage> result = new ArrayList<>(rows.size() - from);
        for (int i = from; i < rows.size(); i++) {
            result.add(new InMessage(rows.get(i).getRole(), rows.get(i).getContent()));
        }
        return result;
    }

    private String lawCodeHint(List<InMessage> history) {
        int from = Math.max(0, history.size() - 8);
        for (int i = history.size() - 1; i >= from; i--) {
            String content = history.get(i).content();
            if (content == null) {
                continue;
            }
            String code = lawSearch.detectLawCode(content);
            if (code != null) {
                return code;
            }
        }
        return null;
    }

    private static String latestUserFromHistory(List<InMessage> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (ROLE_USER.equals(history.get(i).role())) {
                return history.get(i).content();
            }
        }
        return "";
    }

    private static String toJson(List<Citation> citations) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < citations.size(); i++) {
            Citation c = citations.get(i);
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\"lawName\":\"").append(escape(c.lawName())).append("\"")
                    .append(",\"articleNo\":\"").append(escape(c.articleNo())).append("\"")
                    .append(",\"snippet\":\"").append(escape(c.snippet())).append("\"}");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
