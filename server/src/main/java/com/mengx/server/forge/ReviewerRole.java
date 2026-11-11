package com.mengx.server.forge;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mengx.server.forge.agent.ForgeAgentFactory;
import com.mengx.server.forge.agent.ReviewerReply;
import com.mengx.server.forge.agent.ReviewerAgent;
import com.mengx.server.idea.IdeaCardEntity;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 工部审校官岗（方案 §6.3/§6.1）：岗链终岗，通读黑板全部段做只读校对（不写任何段）。
 * 两态裁决：REWRITE 定向打回（analyst/advisor/architect 三选一，≤2 轮硬上限由编排器执行）
 * 或 CONTINUE 交主理人终审（编排器转 REVIEW 状态）。
 * 打回纪律：只对方向性/施工性硬伤打回（整岗重跑成本高），小瑕疵不放行前修；审校不问用户。
 *
 * 角色分工：本类是工部岗位编排；LLM 契约在 {@link ReviewerAgent}。
 */
@Component
public class ReviewerRole implements ForgeAgent {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 可打回的目标岗（analyst/advisor/architect 三选一；advisor 为插拔槽位名） */
    private static final Set<String> REWRITE_TARGETS = Set.of("analyst", "advisor", "architect");

    private final ForgeAgentFactory agents;

    public ReviewerRole(ForgeAgentFactory agents) {
        this.agents = agents;
    }

    @Override
    public String name() {
        return "reviewer";
    }

    @Override
    public List<String> reads() {
        return List.of("product", "domainDesign", "engineering", "openQuestions");
    }

    @Override
    public List<String> owns() {
        return List.of(); // 只读校对：唯一写者铁律下审校不写任何段
    }

    @Override
    public AgentResult run(ForgeContext ctx) {
        ReviewerReply reply = agents.reviewer().review(buildUserPrompt(ctx));
        String instruction = reply.instruction() == null
                ? "" : reply.instruction().trim().toUpperCase();
        if ("REWRITE".equals(instruction)) {
            String reason = isBlank(reply.rewriteReason())
                    ? or(reply.note(), "审校官判定存在需返工的硬伤") : reply.rewriteReason().trim();
            return AgentResult.rewrite(normalizeTarget(reply.rewriteTarget()), reason);
        }
        if ("WAIT".equals(instruction)) {
            // 审校是内部工序：WAIT 属异常输出，不静默放行也不死锁，BLOCKED 可从断点重试
            return AgentResult.block("审校官输出异常指令（WAIT：审校不问用户），本次作废，可重试");
        }
        if ("BLOCK".equals(instruction)) {
            return AgentResult.block(isBlank(reply.note())
                    ? "审校官判定施工包当前无法放行" : reply.note().trim());
        }
        // CONTINUE：note 为审校意见摘要，随 REVIEW 状态落工单备注（主理人终审参考）
        return AgentResult.cont(isBlank(reply.note()) ? null : reply.note().trim());
    }

    /** 打回目标归一：只认 analyst/advisor/architect；非法值（含空/自己/具体顾问名）兜到最近可修岗 architect */
    private static String normalizeTarget(String target) {
        String t = target == null ? "" : target.trim().toLowerCase();
        return REWRITE_TARGETS.contains(t) ? t : "architect";
    }

    /** 任务输入：卡片 + 黑板全段（审校要通读全部产物） */
    private String buildUserPrompt(ForgeContext ctx) {
        IdeaCardEntity card = ctx.card();
        StringBuilder sb = new StringBuilder();
        sb.append("【功能卡】\n");
        sb.append("标题：").append(card.getTitle()).append('\n');
        sb.append("一句话：").append(or(card.getSummary(), "")).append('\n');
        sb.append("\n【产品深化段】\n").append(json(ctx.board().getProduct())).append('\n');
        sb.append("\n【领域设计段】\n").append(json(ctx.board().getDomainDesign())).append('\n');
        sb.append("\n【工程施工段】\n").append(json(ctx.board().getEngineering())).append('\n');
        if (ctx.board().getOpenQuestions() != null && !ctx.board().getOpenQuestions().isEmpty()) {
            sb.append("\n【待决问题清单】（系统代拟的默认项，校对兜底处理）\n");
            ctx.board().getOpenQuestions().forEach(q -> sb.append("- ").append(q).append('\n'));
        }
        if (!isBlank(ctx.job().getErrorNote())) {
            sb.append("\n【工单备注】").append(ctx.job().getErrorNote().trim()).append('\n');
        }
        return sb.toString();
    }

    private static String json(Object value) {
        if (value == null) {
            return "（空）";
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String or(String s, String fallback) {
        return isBlank(s) ? fallback : s;
    }
}
