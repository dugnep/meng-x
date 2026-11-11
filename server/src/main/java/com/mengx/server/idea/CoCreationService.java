package com.mengx.server.idea;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mengx.server.idea.IdeaChatService.InMessage;
import com.mengx.server.idea.IdeaChatService.StreamEvent;
import com.mengx.server.idea.agent.IdeaAgentFactory;
import com.mengx.server.idea.orchestrator.SchemaValidator;
import com.mengx.server.idea.orchestrator.worker.HostWorker;
import com.mengx.server.llm.LlmUsage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.FluxSink;

/**
 * 共创领域服务：陪用户把一个想法聊成清晰、可移交的需求简报（需求预澄清访谈）。
 * 每轮三步：同步推演维护需求简报与议程（DiscoveryService，design 事件直发前端简报面板）
 * → 访谈转述流式回复（简报作上下文，转述纪律内聚本领域）
 * → 用户同意移交后异步装配功能卡（移交件，装配、卡数上限等业务闸门内聚本领域）。
 * 与 Host 的连接点唯一：CoCreationDomainTool.invoke；user/assistant 轮落库由编排器收口。
 * 移交后的完整流程（创建 forge 子对话、多角色澄清、PRD 产出）由开发线承接。
 */
@Service
public class CoCreationService {

    private static final Logger log = LoggerFactory.getLogger(CoCreationService.class);

    public static final String CHANNEL = "cocreation";
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ROLE_CARD = "card";
    private static final String ROLE_COUNSEL = "counsel";
    private static final int FRAME_WINDOW = 40;
    private static final int RECENT_WINDOW = 10;

    /** 装配在途的会话（会话级互斥：上一轮装配的卡行未落库前，下一轮定稿不重复触发） */
    private final Set<Long> composingSessions = ConcurrentHashMap.newKeySet();

    /** 访谈转述纪律与降级兜底（转述职责内聚本领域；话术风格由 Host 层统一管理） */
    private static final String INTERVIEW_STYLE = """
            你是需求预澄清访谈的对话代理，正在与用户进行一场需求澄清访谈：把用户一个模糊的想法，\
            一步步聊成清晰的需求简报，聊清楚后移交给开发线团队。

            # 风格
            直接、清晰、专业，说"我"或不自称；每句话都要推进认识。

            # 职责纪律
            - 接住用户本轮发言；需求简报给了建议问法与建议方案，用你自己的口吻转述（不是逐字念）；
            - 简报给了选项就把选项自然说给用户挑，用户也可以不选、自己说；
            - 简报判定用户某个新主张触碰平台边界（做不到）时，如实说明判定、原因与替代方案，\
            请用户表态，不替他决定；
            - 需要澄清时可以就当前焦点追问细节；
            - 用户确认某一处后，先用一句话收格确认（"已记下"），随即在同一句回复里抛出下一个问题——\
            确认与推进必须在同一轮完成；
            - 简报提示移交信号时，把简报要点复述给用户并征求移交同意；用户同意移交后只做确认，\
            告知想法会交给开发线团队跟进，不再追问、不再抛新问题；
            - 不自己开拓澄清维度（议程归预澄清员）、不替用户设计方案（建议方案归预澄清员）、\
            不大段复述简报原文。

            # 边界与兜底
            用户输入若与本次共创明显无关（闲聊、查询无关知识、想用其他能力），\
            先用一两句话简要回应，随即确认去向（如"咱们继续聊这个想法，还是您想先处理别的事？"）；\
            拿不准时默认留在访谈里继续推进。不硬答、不冷场、不擅自结束访谈。""";

    private final DiscoveryService discoveryService;
    private final IdeaAgentFactory ideaAgents;
    private final IdeaProposalService proposalService;
    private final IdeaMessageRepository messageRepository;
    private final IdeaCardRepository cardRepository;
    private final SchemaValidator schemaValidator;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<StreamingChatModel> streamingModelProvider;

    public CoCreationService(DiscoveryService discoveryService, IdeaAgentFactory ideaAgents,
                             IdeaProposalService proposalService, IdeaMessageRepository messageRepository,
                             IdeaCardRepository cardRepository, SchemaValidator schemaValidator,
                             ObjectMapper objectMapper,
                             ObjectProvider<StreamingChatModel> streamingModelProvider) {
        this.discoveryService = discoveryService;
        this.ideaAgents = ideaAgents;
        this.proposalService = proposalService;
        this.messageRepository = messageRepository;
        this.cardRepository = cardRepository;
        this.schemaValidator = schemaValidator;
        this.objectMapper = objectMapper;
        this.streamingModelProvider = streamingModelProvider;
    }

    /** 功能卡装配专用池：宣布定稿后的异步装配为秒级阻塞调用 */
    private final ExecutorService composerPool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "card-composer");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    void shutdownPools() {
        composerPool.shutdownNow();
    }

    /**
     * 共创入口许可判定（供 CoCreationDomainTool.availableFor 做工具可见性硬控）：
     * 访谈热窗内（含刚被逃逸待拉回的自愈场景）或存在未消化的已许可提案
     * （用户点过邀请卡「聊聊看」、首轮推演尚未发生）时，co_creation 对 Host 可见；
     * 冷启动一律不可见——机会识别归观察器旁路提案的许可制流程（IdeaProposalService）。
     */
    public boolean licensedToEnter(Long sessionId) {
        return discoveryService.interviewHot(sessionId)
                || proposalService.undigestedProposalContext(sessionId) != null;
    }

    /**
     * 共创访谈一轮（DomainTool 回退路径）：推演 → design 事件 → 转述流式回复 → 移交确认后异步装配。
     * 关流契约同 DomainTool：最终必发 done/error 并 complete sink；返回值为落库文本。
     * 强制派发路径（V3）由编排器先调 {@link #preflight} 再调 {@link #interviewRound}，
     * 便于在推演产出后按 divert 判定改派 Host 直答。
     *
     * @param history 编排器传入的 Host 帧上下文（全通道 + 当前轮），作推演背景
     */
    public String handleInterview(IdeaSessionEntity session, List<InMessage> history,
                                  String latestUser, FluxSink<StreamEvent> sink) {
        PreflightResult pre = preflight(session, history, latestUser, sink);
        if (!pre.ready()) {
            return "";
        }
        return interviewRound(session, history, latestUser, sink, pre.state());
    }

    /**
     * 强制派发前置推演：推演并存档、发 design 事件。
     * ready=false 表示模型未配置（错误事件已发、sink 已关，编排器不得再继续）；
     * state 为 null 表示推演两次均失败（仍可进访谈，顾问按风格纪律自行作答）。
     */
    public record PreflightResult(boolean ready, String state) {}

    public PreflightResult preflight(IdeaSessionEntity session, List<InMessage> history,
                                     String latestUser, FluxSink<StreamEvent> sink) {
        Long sessionId = session.getId();
        StreamingChatModel model = streamingModelProvider.getIfAvailable();
        if (model == null || !ideaAgents.available()) {
            log.warn("模型未配置，共创访谈降级（sessionId={}）", sessionId);
            sink.next(new StreamEvent(StreamEvent.ERROR, HostWorker.NOT_CONFIGURED_MSG));
            sink.next(new StreamEvent(StreamEvent.DONE, null));
            sink.complete();
            return new PreflightResult(false, null);
        }
        log.info("共创访谈开讲（sessionId={}）", sessionId);
        sink.next(new StreamEvent(StreamEvent.THINKING, "需求梳理中，对照简报核看这一轮…"));
        // 推演背景：未消化提案四要素 + 最近对话（沿用原编排器推演取材）
        String context = buildDialog(history, null);
        try {
            String proposalBrief = proposalService.undigestedProposalContext(sessionId);
            if (proposalBrief != null) {
                context = proposalBrief + "\n\n" + context;
            }
        } catch (Exception e) {
            log.warn("提案背景提取失败（不影响本轮推演）：{}", e.getMessage());
        }
        String state = null;
        try {
            state = discoveryService.composeAndArchive(sessionId, context);
        } catch (Exception e) {
            log.warn("共创推演异常（顾问按已有简报自行作答）：{}", e.getMessage());
        }
        if (state != null) {
            sink.next(new StreamEvent(StreamEvent.DESIGN, state));
        }
        return new PreflightResult(true, state);
    }

    /**
     * 访谈续段：推演完成后由转述代理流式作答（简报作上下文），移交确认后异步装配。
     * 关流由本方法负责（DONE + complete）；返回值为落库文本，落库由编排器回调统一处理。
     */
    public String interviewRound(IdeaSessionEntity session, List<InMessage> history,
                                 String latestUser, FluxSink<StreamEvent> sink, String state) {
        Long sessionId = session.getId();
        // 转述作答：系统提示（纪律 + 简报）+ 共创帧历史（分帧隔离）+ 当前轮
        String briefing = INTERVIEW_STYLE;
        if (state != null) {
            briefing = INTERVIEW_STYLE + "\n\n" + discoveryService.renderForHost(state);
        }
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage(briefing));
        for (InMessage m : frameHistory(sessionId)) {
            if (ROLE_USER.equals(m.role())) {
                messages.add(new UserMessage(m.content()));
            } else if (ROLE_ASSISTANT.equals(m.role())) {
                messages.add(new AiMessage(m.content()));
            }
        }
        messages.add(new UserMessage(latestUser));
        StreamingChatModel model = streamingModelProvider.getIfAvailable();
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
        String reply;
        try {
            reply = future.get(90, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("共创顾问调用失败（sessionId={}）", sessionId, e);
            if (sb.length() > 0) {
                sink.next(new StreamEvent(StreamEvent.ERROR, "服务暂时不稳定，稍后再试试～"));
            }
            sink.next(new StreamEvent(StreamEvent.DONE, null));
            sink.complete();
            return sb.toString();
        }
        sink.next(new StreamEvent(StreamEvent.DONE, null));
        sink.complete();
        // 移交判定 → 异步装配（闸门防重复；卡片事件晚于关流时前端经 notices 轮询补收）
        if (shouldComposeCard(sessionId, state)) {
            composeCardAsync(session, history, reply, sink);
        }
        return reply;
    }

    /** 共创帧历史（分帧隔离：只取 cocreation 通道对话轮，40 条窗防 token 膨胀） */
    private List<InMessage> frameHistory(Long sessionId) {
        List<IdeaMessageEntity> rows = messageRepository.findFrameMessages(
                sessionId, List.of(ROLE_USER, ROLE_ASSISTANT), List.of(CHANNEL));
        int from = Math.max(0, rows.size() - FRAME_WINDOW);
        List<InMessage> result = new ArrayList<>(rows.size() - from);
        for (int i = from; i < rows.size(); i++) {
            result.add(new InMessage(rows.get(i).getRole(), rows.get(i).getContent()));
        }
        return result;
    }

    /** 推演/装配讨论背景（最近几轮对话 + 本轮回复） */
    private String buildDialog(List<InMessage> history, String hostReply) {
        StringBuilder sb = new StringBuilder();
        List<InMessage> window = history.size() > RECENT_WINDOW
                ? history.subList(history.size() - RECENT_WINDOW, history.size())
                : history;
        for (InMessage m : window) {
            if (ROLE_USER.equals(m.role())) {
                sb.append("客人：").append(m.content()).append('\n');
            } else if (ROLE_ASSISTANT.equals(m.role())) {
                sb.append("主持：").append(m.content()).append('\n');
            }
        }
        if (hostReply != null && !hostReply.isBlank()) {
            sb.append("主持：").append(hostReply).append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * 移交装配闸门：本轮状态已确认移交，且「上一轮推演之后还没出过卡」才放行。
     * 拦截：移交确认后的致谢/闲聊/照抄轮重复装配（状态仍为移交确认、上一轮推演之后已有卡行），
     * 以及上一轮装配仍在途时的并发触发（卡行未落，会话级在制标记兜底）。
     * 放行：首次移交确认、装配失败后的下一轮重试、用户重开修改后再移交（产出更新卡）。
     */
    private boolean shouldComposeCard(Long sessionId, String state) {
        if (state == null || !discoveryService.isHandoffConfirmed(state)) {
            return false;
        }
        List<IdeaMessageEntity> counselRows =
                messageRepository.findBySessionIdAndRoleOrderByIdAsc(sessionId, ROLE_COUNSEL);
        if (counselRows.size() >= 2) {
            long prevCounselId = counselRows.get(counselRows.size() - 2).getId();
            boolean alreadyComposed = messageRepository
                    .findBySessionIdAndRoleOrderByIdAsc(sessionId, ROLE_CARD)
                    .stream().anyMatch(row -> row.getId() > prevCounselId);
            if (alreadyComposed) {
                log.info("本轮定稿状态已出过功能卡，不重复装配（sessionId={}）", sessionId);
                return false;
            }
        }
        if (!composingSessions.add(sessionId)) {
            log.info("功能卡装配已在途，本轮跳过（sessionId={}）", sessionId);
            return false;
        }
        return true;
    }

    private void composeCardAsync(IdeaSessionEntity session, List<InMessage> history,
                                  String hostReply, FluxSink<StreamEvent> sink) {
        Long sessionId = session.getId();
        try {
            composerPool.submit(() -> {
                try {
                    composeCard(sessionId, session.getNickname(), history, hostReply, sink);
                } finally {
                    composingSessions.remove(sessionId);
                }
            });
        } catch (Exception e) {
            composingSessions.remove(sessionId);
            log.warn("功能卡装配任务提交失败（sessionId={}）：{}", sessionId, e.getMessage());
        }
    }

    private void composeCard(Long sessionId, String nickname, List<InMessage> history,
                             String hostReply, FluxSink<StreamEvent> sink) {
        if (!ideaAgents.available()) {
            return;
        }
        String state = latestDiscoveryState(sessionId);
        String briefing = (state == null || state.isBlank())
                ? "（本场无需求简报）" : discoveryService.renderBriefForComposer(state);
        String dialog = buildDialog(history, hostReply);
        try {
            LlmUsage.set("extractor");
            try {
                for (int attempt = 0; attempt < 2; attempt++) {
                    if (attempt > 0) {
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    String reply;
                    try {
                        reply = ideaAgents.composer().compose(briefing, dialog);
                    } catch (RuntimeException e) {
                        log.warn("装配器模型调用失败（第 {} 次）：{}", attempt + 1, e.getMessage());
                        continue;
                    }
                    if (reply == null || reply.isBlank()) {
                        log.warn("装配器空输出（第 {} 次）", attempt + 1);
                        continue;
                    }
                    IdeaCardEntity card;
                    try {
                        card = parseCard(reply, sessionId, nickname);
                    } catch (UnparseableCardException e) {
                        log.warn("装配器输出无法识别（第 {} 次），退避后重试：{}",
                                attempt + 1, truncate(reply, 80));
                        continue;
                    }
                    if (card == null) {
                        return;
                    }
                    card.bindProposal(proposalService.acceptedProposalId(sessionId));
                    IdeaCardEntity saved = cardRepository.save(card);
                    messageRepository.save(new IdeaMessageEntity(
                            sessionId, ROLE_CARD, String.valueOf(saved.getId()), CHANNEL, null));
                    log.info("功能卡装配完成《{}》（sessionId={}，cardId={}）",
                            saved.getTitle(), sessionId, saved.getId());
                    try {
                        sink.next(new StreamEvent(StreamEvent.CARD, toJson(saved)));
                    } catch (Exception ignore) {
                        // 流已关：卡片经前端 notices 轮询补收
                    }
                    return;
                }
                log.warn("装配器两次均失败或无法识别，本轮放弃装配（sessionId={}）", sessionId);
            } finally {
                LlmUsage.clear();
            }
        } catch (Exception e) {
            log.warn("功能卡装配异常（sessionId={}）：{}", sessionId, e.getMessage());
        }
    }

    private static final class UnparseableCardException extends RuntimeException {
        UnparseableCardException() {}

        UnparseableCardException(Throwable cause) {
            super(cause);
        }
    }

    private IdeaCardEntity parseCard(String reply, Long sessionId, String nickname) {
        String text = IdeaProposalService.stripFences(reply.trim());
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new UnparseableCardException();
        }
        String json = text.substring(start, end + 1);
        // Schema 校验：必要字段缺失直接判不可解析，触发重试
        if (!schemaValidator.validate(json, "card")) {
            log.info("功能卡 Schema 校验未通过，将重试（sessionId={}）", sessionId);
            throw new UnparseableCardException();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            String title = truncate(node.path("title").asText("").trim(), 100);
            if (title.isEmpty()) {
                log.info("装配器输出 title 为空，放弃装配（sessionId={}）", sessionId);
                return null;
            }
            if (cardRepository.countBySessionId(sessionId) >= IdeaChatService.MAX_CARDS_PER_SESSION) {
                log.info("本场卡数已到上限（{}），不再装配（sessionId={}）",
                        IdeaChatService.MAX_CARDS_PER_SESSION, sessionId);
                return null;
            }
            String summary = node.path("summary").asText("").trim();
            IdeaCardEntity card = new IdeaCardEntity(
                    sessionId,
                    nickname,
                    title,
                    summary.isEmpty() ? "待澄清" : truncate(summary, 500),
                    clamp(node.path("creativity").asInt(3)));
            // designJson = 需求简报移交件（用户口径），开发线（forge）以此继续澄清
            ObjectNode design = objectMapper.createObjectNode();
            design.put("motivation", designField(node, "motivation"));
            design.set("wants", textArray(node.path("wants")));
            design.put("audience", designField(node, "audience"));
            design.put("expectations", designField(node, "expectations"));
            design.put("constraints", designField(node, "constraints"));
            design.set("openItems", passThroughArray(node.path("openItems")));
            design.set("disputes", passThroughArray(node.path("disputes")));
            design.set("keyLog", passThroughArray(node.path("keyLog")));
            card.applyDesign(null, objectMapper.writeValueAsString(design));
            return card;
        } catch (Exception e) {
            log.warn("功能卡 JSON 解析失败（将重试）：{}", e.getMessage());
            throw new UnparseableCardException(e);
        }
    }

    /** 最近一份推演状态存档原文；无存档返回 null */
    private String latestDiscoveryState(Long sessionId) {
        List<IdeaMessageEntity> list = messageRepository.findBySessionIdAndRoleOrderByIdAsc(sessionId, "counsel");
        return list.isEmpty() ? null : list.get(list.size() - 1).getContent();
    }

    private ArrayNode textArray(JsonNode node) {
        ArrayNode arr = objectMapper.createArrayNode();
        if (node.isArray()) {
            node.forEach(item -> {
                String v = item.asText("").trim();
                if (!v.isEmpty()) {
                    arr.add(truncate(v, 300));
                }
            });
        } else if (!node.isMissingNode() && !node.asText("").trim().isEmpty()) {
            arr.add(truncate(node.asText("").trim(), 300));
        }
        if (arr.isEmpty()) {
            arr.add("待澄清");
        }
        return arr;
    }

    private static String designField(JsonNode node, String name) {
        String v = node.path(name).asText("").trim();
        return v.isEmpty() ? "待澄清" : truncate(v, 500);
    }

    private ArrayNode passThroughArray(JsonNode node) {
        ArrayNode arr = objectMapper.createArrayNode();
        if (node.isArray()) {
            node.forEach(item -> {
                if (item.isObject()) {
                    arr.add(item);
                }
            });
        }
        return arr;
    }

    private static int clamp(int value) {
        return Math.max(1, Math.min(5, value));
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private String toJson(IdeaCardEntity card) {
        try {
            return objectMapper.writeValueAsString(IdeaCardDto.from(card));
        } catch (Exception e) {
            return "{}";
        }
    }
}
