package com.mengx.server.idea.orchestrator.worker;

import com.mengx.server.idea.DiscoveryService;
import com.mengx.server.idea.IdeaMessageEntity;
import com.mengx.server.idea.IdeaMessageRepository;
import com.mengx.server.idea.agent.IdeaAgentFactory;
import com.mengx.server.idea.orchestrator.ChatWorker;
import com.mengx.server.idea.orchestrator.WorkerCapability;
import com.mengx.server.idea.orchestrator.WorkerContext;
import com.mengx.server.idea.orchestrator.WorkerContext.ChatTurn;
import com.mengx.server.idea.orchestrator.WorkerResult;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 卡片装配器 Worker（异步旁路）：客人点头定稿后把访谈成果装配成功能卡 JSON。
 * 包装 CardComposerAgent.compose()，返回原始 JSON 文本作为 WorkerResult.payload。
 * 卡数上限/落库/解析等业务闸门留在编排层，本 Worker 只做模型装配。
 */
@Component
public class CardComposerWorker implements ChatWorker {

    public static final String NAME = "card-composer";
    private static final int RECENT_WINDOW = 10;

    private final IdeaAgentFactory ideaAgents;
    private final DiscoveryService discoveryService;
    private final IdeaMessageRepository messageRepository;

    public CardComposerWorker(IdeaAgentFactory ideaAgents, DiscoveryService discoveryService,
                              IdeaMessageRepository messageRepository) {
        this.ideaAgents = ideaAgents;
        this.discoveryService = discoveryService;
        this.messageRepository = messageRepository;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public WorkerCapability capability() {
        return new WorkerCapability(
                "卡片装配器",
                "发现模式定稿后装配功能卡 JSON",
                List.of());
    }

    @Override
    public WorkerResult run(WorkerContext ctx) {
        if (!ideaAgents.available()) {
            return WorkerResult.payload(null);
        }
        String state = latestDiscoveryState(ctx.sessionId());
        String briefing = (state == null || state.isBlank())
                ? "（本场无设计简报）" : discoveryService.renderForHost(state);
        String priority = state == null ? null : discoveryService.priorityOf(state);
        String dialog = buildDialog(ctx.history(), ctx.hostReply());
        String reply = ideaAgents.composer().compose(priority, briefing, dialog);
        return WorkerResult.payload(reply);
    }

    /** 最近一份需求澄清状态存档原文（沿用 IdeaChatService.latestDiscoveryState） */
    private String latestDiscoveryState(Long sessionId) {
        List<IdeaMessageEntity> list = messageRepository.findBySessionIdAndRoleOrderByIdAsc(sessionId, "counsel");
        return list.isEmpty() ? null : list.get(list.size() - 1).getContent();
    }

    /** 装配器讨论背景（最近几轮对话 + 本轮主持回复，沿用 IdeaChatService.buildDiscoveryContext） */
    private String buildDialog(List<ChatTurn> history, String hostReply) {
        StringBuilder sb = new StringBuilder();
        List<ChatTurn> window = history.size() > RECENT_WINDOW
                ? history.subList(history.size() - RECENT_WINDOW, history.size())
                : history;
        for (ChatTurn m : window) {
            if ("user".equals(m.role())) {
                sb.append("客人：").append(m.content()).append('\n');
            } else if ("assistant".equals(m.role())) {
                sb.append("主持：").append(m.content()).append('\n');
            }
        }
        if (hostReply != null && !hostReply.isBlank()) {
            sb.append("主持：").append(hostReply).append('\n');
        }
        return sb.toString().trim();
    }
}
