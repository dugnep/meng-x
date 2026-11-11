package com.mengx.server.forge;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mengx.server.forge.agent.AdvisorReply;
import com.mengx.server.forge.agent.DefaultAdvisorAgent;
import com.mengx.server.forge.agent.ForgeAgentFactory;
import com.mengx.server.forge.agent.QuestionToAsk;
import com.mengx.server.idea.IdeaCardEntity;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 通用领域顾问兜底岗（方案 §6.3 advisor.default）：法学等已有成熟范式之外的新道场都归这里。
 * 核心职责是把新道场最容易翻车的两件事强制钉死（酸儒案例教训，§1.2）：
 * ① 语料方案不许开空头支票（三选一决策+来源具体性+冷启动+更新机制）；
 * ② 内容安全强制评估（微信生态一票否决项：定级/风险话题/拒答兜底/隐私）。
 * 顺带提炼领域要点清单，供架构师与人设岗复用。
 *
 * 角色分工：本类是工部岗位编排；LLM 契约在 {@link DefaultAdvisorAgent}。
 */
@Component
public class DefaultAdvisorRole implements AdvisorAgent {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ForgeAgentFactory agents;

    public DefaultAdvisorRole(ForgeAgentFactory agents) {
        this.agents = agents;
    }

    @Override
    public String name() {
        return "advisor.default";
    }

    @Override
    public List<String> domainSignals() {
        // 兜底顾问：信号词为空，选型永远兜到这里（方案 §6.2 注册表约定）
        return List.of();
    }

    @Override
    public List<String> reads() {
        return List.of("product");
    }

    @Override
    public List<String> owns() {
        return List.of("domainDesign");
    }

    @Override
    public AgentResult run(ForgeContext ctx) {
        AdvisorReply reply = agents.defaultAdvisor().advise(buildUserPrompt(ctx));
        if (reply.domainDesign() == null) {
            throw new IllegalStateException("通用领域顾问输出缺少 domainDesign 段");
        }
        ctx.board().setDomainDesign(reply.domainDesign());

        String instruction = normalize(reply.instruction(), reply.question());
        if ("WAIT".equals(instruction)) {
            QuestionToAsk q = reply.question();
            if (q == null || isBlank(q.question())) {
                return AgentResult.cont();
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
                    ? "通用领域顾问判定该卡当前无法深化" : reply.note().trim());
        }
        return AgentResult.cont();
    }

    /** 任务输入：卡片轻量上下文 + 产品深化段（+ 修订场景的上一版领域段 / 客人对挂起问题的回答原文） */
    private String buildUserPrompt(ForgeContext ctx) {
        IdeaCardEntity card = ctx.card();
        StringBuilder sb = new StringBuilder();
        sb.append("【功能卡】\n");
        sb.append("标题：").append(card.getTitle()).append('\n');
        sb.append("一句话：").append(or(card.getSummary())).append('\n');
        sb.append("\n【产品深化段】（需求分析师产出，你的深化基准）\n")
                .append(json(ctx.board().getProduct())).append('\n');
        if (ctx.board().getDomainDesign() != null) {
            sb.append("\n【当前领域设计段】（上一版，本次在其基础上修订，不要推倒重来）\n")
                    .append(json(ctx.board().getDomainDesign())).append('\n');
        }
        if (!isBlank(ctx.job().getErrorNote())) {
            sb.append("\n【上轮审校意见/工单备注】").append(ctx.job().getErrorNote().trim())
                    .append("\n请针对该意见修订领域设计段。\n");
        }
        if (ctx.pendingQuestion() != null && !isBlank(ctx.answer())) {
            sb.append("\n【用户回答】你此前问过：").append(ctx.pendingQuestion().question())
                    .append("\n用户答（原文）：").append(ctx.answer().trim())
                    .append("\n请结合回答修订领域设计段。\n");
        }
        return sb.toString();
    }

    private static String normalize(String instruction, QuestionToAsk q) {
        String i = instruction == null ? "" : instruction.trim().toUpperCase();
        if (Set.of("CONTINUE", "WAIT", "BLOCK").contains(i)) {
            return i;
        }
        return q != null && !isBlank(q.question()) ? "WAIT" : "CONTINUE";
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

    private static String or(String s) {
        return isBlank(s) ? "待聊" : s;
    }
}
