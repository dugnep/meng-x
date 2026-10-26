package com.mengx.server.idea.orchestrator.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mengx.server.idea.IdeaChatService.InMessage;
import com.mengx.server.idea.IdeaChatService.StreamEvent;
import com.mengx.server.idea.IdeaSessionEntity;
import com.mengx.server.idea.orchestrator.DomainTool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.FluxSink;

/**
 * 主对话 Worker（百晓仙君）：统一入口，通过 function calling 决定直答或调用领域工具。
 *
 * <p>架构定位：Host 是前台唯一代理，手底下挂着各领域专业代理（法学/股票/共创…）。
 * 用户消息一律先到 Host，Host 判断自己能答就直接答；需要专业能力时调用对应 DomainTool，
 * 领域输出直达用户、不经 Host 二次包装（保持领域专业性）。
 *
 * <p>无缝切换：Host 调用领域工具时不输出过渡语，领域输出直接呈现，用户感知仍是仙君在答。
 */
@Component
public class HostWorker {

    private static final Logger log = LoggerFactory.getLogger(HostWorker.class);
    private static final String CHANNEL_HOST = "host";

    /** LLM 未配置提示（api-key 缺失时 Bean 不注册，须用 ObjectProvider 延迟获取） */
    public static final String NOT_CONFIGURED_MSG =
            "LLM 未配置，请设置 MENGX_LLM_API_KEY 或启用 local profile";

    private final ObjectProvider<StreamingChatModel> streamingModelProvider;
    private final List<DomainTool> domainTools;
    private final ObjectMapper objectMapper;

    public HostWorker(ObjectProvider<StreamingChatModel> streamingModelProvider,
                      List<DomainTool> domainTools, ObjectMapper objectMapper) {
        this.streamingModelProvider = streamingModelProvider;
        this.domainTools = domainTools;
        this.objectMapper = objectMapper;
    }

    /** Host 完成回调：告知编排器本轮通道与回复文本，由编排器负责落库 */
    public interface HostCompletionCallback {
        void onComplete(String channel, String replyText);
        void onError(Throwable error);
    }

    /**
     * 流式执行：Host 决策直答或调领域工具。
     *
     * @param session  当前会话
     * @param history  Host 帧历史
     * @param userText 用户本轮输入
     * @param briefing 额外简报（共创推演等场景注入，可空）
     * @param sink     流式输出目的地
     * @param callback 完成回调（编排器落库用）
     */
    public void stream(IdeaSessionEntity session, List<InMessage> history, String userText,
                       String briefing, FluxSink<StreamEvent> sink, HostCompletionCallback callback) {
        StreamingChatModel model = streamingModelProvider.getIfAvailable();
        if (model == null) {
            log.warn("流式模型未配置，Host 无法作答（sessionId={}）", session.getId());
            sink.next(new StreamEvent(StreamEvent.ERROR, NOT_CONFIGURED_MSG));
            sink.next(new StreamEvent(StreamEvent.DONE, null));
            sink.complete();
            return;
        }
        String systemPrompt = buildSystemPrompt(session.getNickname());
        if (briefing != null && !briefing.isBlank()) {
            systemPrompt = systemPrompt + "\n" + briefing;
        }

        List<ChatMessage> messages = buildMessages(systemPrompt, history, userText);
        List<ToolSpecification> toolSpecs = buildToolSpecifications();

        ChatRequest request = ChatRequest.builder()
                .messages(messages)
                .parameters(ChatRequestParameters.builder()
                        .toolSpecifications(toolSpecs)
                        .build())
                .build();

        StringBuilder partial = new StringBuilder();
        AtomicBoolean toolHandled = new AtomicBoolean(false);

        model.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
                partial.append(token);
                sink.next(new StreamEvent(StreamEvent.CHUNK, token));
            }

            @Override
            public void onCompleteToolCall(CompleteToolCall completeToolCall) {
                if (!toolHandled.compareAndSet(false, true)) {
                    return;
                }
                ToolExecutionRequest req = completeToolCall.toolExecutionRequest();
                String toolName = req.name();
                String argsJson = req.arguments();
                log.info("Host 调用领域工具：{}（sessionId={}）", toolName, session.getId());
                DomainTool tool = findTool(toolName);
                if (tool == null) {
                    log.warn("未找到领域工具：{}（sessionId={}）", toolName, session.getId());
                    callback.onComplete(CHANNEL_HOST, partial.toString());
                    return;
                }
                try {
                    JsonNode args = objectMapper.readTree(argsJson == null ? "{}" : argsJson);
                    String reply = tool.invoke(session, history, args, sink);
                    callback.onComplete(tool.channel(), reply);
                } catch (Exception e) {
                    log.error("领域工具执行失败：{}（sessionId={}）", toolName, session.getId(), e);
                    callback.onError(e);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                if (toolHandled.get()) {
                    return; // 已由工具接管
                }
                callback.onComplete(CHANNEL_HOST, partial.toString());
            }

            @Override
            public void onError(Throwable error) {
                log.warn("Host 模型调用失败（sessionId={}）", session.getId(), error);
                callback.onError(error);
            }
        });
    }

    private List<ToolSpecification> buildToolSpecifications() {
        List<ToolSpecification> specs = new ArrayList<>();
        for (DomainTool tool : domainTools) {
            String json = """
                    {"name":"%s","description":"%s","parameters":%s}"""
                    .formatted(tool.name(), escape(tool.description()), tool.parametersSchema());
            specs.add(ToolSpecification.fromJson(json));
        }
        return specs;
    }

    private DomainTool findTool(String name) {
        for (DomainTool tool : domainTools) {
            if (tool.name().equals(name)) {
                return tool;
            }
        }
        return null;
    }

    private List<ChatMessage> buildMessages(String systemPrompt, List<InMessage> history, String userText) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        for (InMessage m : history) {
            if ("user".equals(m.role())) {
                messages.add(new UserMessage(m.content()));
            } else if ("assistant".equals(m.role())) {
                messages.add(new dev.langchain4j.data.message.AiMessage(m.content()));
            }
        }
        // 前端回传的 history 尾部已含本轮输入（尾部 user 即 userText），不重复追加；
        // 仅当尾部不是本轮发言时兜底补上（防御 history 未带当前轮的异常调用）
        InMessage last = history.isEmpty() ? null : history.get(history.size() - 1);
        boolean alreadyIncluded = last != null && "user".equals(last.role())
                && userText != null && userText.equals(last.content());
        if (!alreadyIncluded) {
            messages.add(new UserMessage(userText));
        }
        return messages;
    }

    /** 系统提示：仙君人设（含客人昵称）+ 内部路由纪律 */
    private String buildSystemPrompt(String nickname) {
        String guest = (nickname == null || nickname.isBlank()) ? "客人" : nickname;
        return """
                # 角色
                你是仙官「百晓仙君」，自称"老朽"，上知天文下知地理，无所不知无所不能。
                你正陪「%s」说话——称呼客人时用这个昵称。
                客人有问必答，懂的直接答，不懂的也不推辞——天地间没有你接不住的话头。

                # 内部纪律（客人不可见）
                你手底下有若干垂直领域的下属代理。评估每轮问题：自己能答的直接回答；
                需要专业领域能力（如法条检索、合同审查、出题判分等）时，选择对应的下属代理调用。
                调用代理时不输出过渡语，代理的回答就是你的回答，自然呈现给客人。

                # 说话方式
                自称"老朽"，带一点点古文雅味（"便是""无妨""甚好"），偶尔带点仙气。闲聊几句话说完，不用 markdown。
                """.formatted(guest);
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
