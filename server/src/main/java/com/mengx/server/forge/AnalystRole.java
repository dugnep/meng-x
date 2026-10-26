package com.mengx.server.forge;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mengx.server.forge.agent.AnalystAgent;
import com.mengx.server.forge.agent.AnalystReply;
import com.mengx.server.forge.agent.ForgeAgentFactory;
import com.mengx.server.forge.agent.QuestionToAsk;
import com.mengx.server.idea.IdeaCardEntity;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 需求分析师岗（方案 §6.3 首岗）：把前殿共创的营造图快照深化为施工包「产品深化段」。
 * 读营造图快照 + 卡片标题/一句话；写黑板 product/openQuestions 两段；不碰技术设计（留给架构师）。
 * 输出规则：验收 String→可测条目数组、边界拆四类、人设补正反例、数据需求补来源/冷启动/成本、
 * 合规段强制非空；能给合理默认的问题不问人（终审标「仙官代拟」），真拿不定才 WAIT 挂问（一轮最多一个）。
 *
 * 角色分工：本类是工部岗位编排（黑板读写、四态裁决）；LLM 契约（prompt/JSON 绑定）
 * 在 {@link AnalystAgent}，由 {@link ForgeAgentFactory} 装配。
 */
@Component
public class AnalystRole implements ForgeAgent {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ForgeAgentFactory agents;

    public AnalystRole(ForgeAgentFactory agents) {
        this.agents = agents;
    }

    @Override
    public String name() {
        return "analyst";
    }

    @Override
    public List<String> reads() {
        // 黑板上无上游段：读营造图快照（design_snapshot）与卡片标题/一句话（信封外输入）
        return List.of();
    }

    @Override
    public List<String> owns() {
        return List.of("product", "openQuestions");
    }

    @Override
    public AgentResult run(ForgeContext ctx) {
        AnalystReply reply = agents.analyst().deepen(buildUserPrompt(ctx));
        if (reply.product() == null) {
            throw new IllegalStateException("需求分析师输出缺少 product 段");
        }
        // 写黑板（owns 段）
        ctx.board().setProduct(reply.product());
        ctx.board().setOpenQuestions(reply.openQuestions() == null ? List.of() : reply.openQuestions());

        String instruction = normalize(reply.instruction(), reply.question());
        if ("WAIT".equals(instruction)) {
            QuestionToAsk q = reply.question();
            if (q == null || isBlank(q.question())) {
                return AgentResult.cont(); // 没给问题正文就挂等会卡死工单，按完成处理
            }
            return AgentResult.wait(new PendingQuestion(
                    "q" + ctx.job().getDesignVersion(),
                    name(),
                    isBlank(q.type()) ? "产品" : q.type(),
                    q.question().trim(),
                    q.options() == null ? List.of() : q.options(),
                    isBlank(q.defaultValue()) ? null : q.defaultValue().trim(),
                    false));
        }
        if ("BLOCK".equals(instruction)) {
            return AgentResult.block(isBlank(reply.note())
                    ? "需求分析师判定该卡当前无法深化" : reply.note().trim());
        }
        return AgentResult.cont();
    }

    /** 指令归一：本岗合法态 CONTINUE/WAIT/BLOCK；REWRITE 是审校官职权不适用；未知指令按有无问题推断 */
    private static String normalize(String instruction, QuestionToAsk q) {
        String i = instruction == null ? "" : instruction.trim().toUpperCase();
        if (Set.of("CONTINUE", "WAIT", "BLOCK").contains(i)) {
            return i;
        }
        return q != null && !isBlank(q.question()) ? "WAIT" : "CONTINUE";
    }

    /** 任务输入：卡片主字段 + 冻结快照（+ 修订场景的上一版产品段 / 客人对挂起问题的回答原文） */
    private String buildUserPrompt(ForgeContext ctx) {
        IdeaCardEntity card = ctx.card();
        StringBuilder sb = new StringBuilder();
        sb.append("【功能卡】\n");
        sb.append("标题：").append(card.getTitle()).append('\n');
        sb.append("一句话：").append(card.getSummary()).append('\n');
        sb.append("\n【营造图快照】（开工时冻结版：核心三要素 + 动态维度 + 边界兜底 + 关键项留痕，")
                .append("全程以此为准，主线后续变更不追）\n")
                .append(ctx.job().getDesignSnapshot()).append('\n');
        if (ctx.board().getProduct() != null) {
            sb.append("\n【当前产品深化段】（上一版，本次在其基础上修订，不要推倒重来）\n")
                    .append(json(ctx.board().getProduct())).append('\n');
        }
        if (!isBlank(ctx.job().getErrorNote())) {
            sb.append("\n【上轮审校意见/工单备注】").append(ctx.job().getErrorNote().trim())
                    .append("\n请针对该意见修订产品深化段。\n");
        }
        if (ctx.pendingQuestion() != null && !isBlank(ctx.answer())) {
            sb.append("\n【客人回答】你此前问过：").append(ctx.pendingQuestion().question())
                    .append("\n客人答（原文）：").append(ctx.answer().trim())
                    .append("\n请结合回答修订产品深化段；该问题已定的，从 openQuestions 中移除相应条目。\n");
        }
        return sb.toString();
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
