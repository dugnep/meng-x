package com.mengx.server.forge;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mengx.server.forge.agent.AdvisorReply;
import com.mengx.server.forge.agent.ForgeAgentFactory;
import com.mengx.server.forge.agent.LegalAdvisorAgent;
import com.mengx.server.forge.agent.QuestionToAsk;
import com.mengx.server.idea.IdeaCardEntity;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 法学领域顾问岗（方案 §6.3 advisor.legal）：为法学相关道场补「领域设计段」。
 * 教学设计参照本应用已建成的法学道场成熟模式（题型轮换/真题参照/掌握度画像/防幻觉三防线/三路检索），
 * 这些能力可直接复用现有实现，领域段要把「复用什么、新造什么」说清楚，供架构师落工程施工包。
 * 信号词只放定位级高置信词，防酸儒类道场被内容词汇误判（§1.2 教训）。
 *
 * 角色分工：本类是工部岗位编排；LLM 契约在 {@link LegalAdvisorAgent}。
 */
@Component
public class LegalAdvisorRole implements AdvisorAgent {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 定位级信号词：只认「道场是法学域」的表述，不认内容词汇（罪/判刑之类不触发） */
    private static final List<String> SIGNALS = List.of(
            "法考", "法条", "法学", "判例", "司法考试", "律考", "法学生", "法律职业资格");

    private final ForgeAgentFactory agents;

    public LegalAdvisorRole(ForgeAgentFactory agents) {
        this.agents = agents;
    }

    @Override
    public String name() {
        return "advisor.legal";
    }

    @Override
    public List<String> domainSignals() {
        return SIGNALS;
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
        AdvisorReply reply = agents.legalAdvisor().advise(buildUserPrompt(ctx));
        if (reply.domainDesign() == null) {
            throw new IllegalStateException("法学领域顾问输出缺少 domainDesign 段");
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
                    ? "法学领域顾问判定该卡当前无法深化" : reply.note().trim());
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
