package com.mengx.server.forge;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mengx.server.forge.agent.ArchitectAgent;
import com.mengx.server.forge.agent.ArchitectReply;
import com.mengx.server.forge.agent.ForgeAgentFactory;
import com.mengx.server.idea.IdeaCardEntity;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 工部架构师岗（方案 §6.3）：读产品段 + 领域设计段，写工程施工段（engineering 自由结构，§6.5）。
 * 施工必须贴合架构手册（全局约束唯一真源，「必须复用不得另建」）；输出到架构级——
 * 模块划分/表结构/接口草案，不写代码级细节（代码由 AI 编程工具按施工包生成）。
 * 三不原则（§5.2）：技术决策一律自决、不问用户——本岗禁止 WAIT。
 *
 * 角色分工：本类是工部岗位编排；LLM 契约在 {@link ArchitectAgent}（架构手册经 @V 注入）。
 */
@Component
public class ArchitectRole implements ForgeAgent {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ForgeAgentFactory agents;
    private final ForgeHandbookStack handbookStack;

    public ArchitectRole(ForgeAgentFactory agents, ForgeHandbookStack handbookStack) {
        this.agents = agents;
        this.handbookStack = handbookStack;
    }

    @Override
    public String name() {
        return "architect";
    }

    @Override
    public List<String> reads() {
        return List.of("product", "domainDesign", "openQuestions");
    }

    @Override
    public List<String> owns() {
        return List.of("engineering");
    }

    @Override
    public AgentResult run(ForgeContext ctx) {
        ArchitectReply reply = agents.architect().design(handbookStack.stack(), buildUserPrompt(ctx));
        if (reply.engineering() == null) {
            throw new IllegalStateException("架构师输出缺少 engineering 段");
        }
        ctx.board().setEngineering(reply.engineering());

        String instruction = reply.instruction() == null
                ? "" : reply.instruction().trim().toUpperCase();
        if ("WAIT".equals(instruction)) {
            // 三不原则：架构师不问用户。段已产出说明只是想问——忽略问题按完成推进；没段则无法施工
            return AgentResult.block("架构师试图向用户提问（技术决策应自决），本次输出作废，请重跑");
        }
        if ("BLOCK".equals(instruction)) {
            return AgentResult.block(isBlank(reply.note())
                    ? "架构师判定该卡当前无法施工" : reply.note().trim());
        }
        return AgentResult.cont();
    }

    /** 任务输入：卡片轻量上下文 + 产品段 + 领域段（+ 打回重跑的上一版工程段与审校意见） */
    private String buildUserPrompt(ForgeContext ctx) {
        IdeaCardEntity card = ctx.card();
        StringBuilder sb = new StringBuilder();
        sb.append("【功能卡】\n");
        sb.append("标题：").append(card.getTitle()).append('\n');
        sb.append("一句话：").append(or(card.getSummary())).append('\n');
        sb.append("\n【产品深化段】（需求分析师产出）\n")
                .append(json(ctx.board().getProduct())).append('\n');
        sb.append("\n【领域设计段】（领域顾问产出）\n")
                .append(json(ctx.board().getDomainDesign())).append('\n');
        if (ctx.board().getOpenQuestions() != null && !ctx.board().getOpenQuestions().isEmpty()) {
            sb.append("\n【待决问题清单】（部分为系统代拟的默认项，请给出兜底处理）\n");
            ctx.board().getOpenQuestions().forEach(q -> sb.append("- ").append(q).append('\n'));
        }
        if (ctx.board().getEngineering() != null) {
            sb.append("\n【当前工程施工段】（上一版，本次在其基础上修订，不要推倒重来）\n")
                    .append(json(ctx.board().getEngineering())).append('\n');
        }
        if (!isBlank(ctx.job().getErrorNote())) {
            sb.append("\n【上轮审校意见/工单备注】").append(ctx.job().getErrorNote().trim())
                    .append("\n请针对该意见修订工程施工段。\n");
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

    private static String or(String s) {
        return isBlank(s) ? "待聊" : s;
    }
}
