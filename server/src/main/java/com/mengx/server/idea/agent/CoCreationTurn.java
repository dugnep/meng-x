package com.mengx.server.idea.agent;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.List;

/**
 * 需求预澄清员（CoCreationAgent）的单轮推演结论（新契约：需求简报 + 移交判定）：
 * 本轮聚焦点、建议问法（可附选项）、建议方案、边界判定、更新后的全量需求简报
 * 与移交信号。简报是开发线（forge）认识这个想法的唯一窗口——用户同意移交后
 * 以简报为起点创建专属子对话，由开发线多角色团队继续澄清并产出 PRD。
 * 由 DiscoveryService 原样序列化为状态信封存档（SSE design 事件直发前端需求简报面板）。
 */
public record CoCreationTurn(
        String focus,
        String question,
        @JsonDeserialize(using = StringOrListDeserializer.class) List<String> options,
        String proposal,
        Validity validity,
        Brief brief,
        Boolean handoffReady,
        Boolean handoffConfirmed,
        Boolean ack,
        Divert divert) {

    /** 暂挂判定：用户本轮明确提出与本场访谈无关的独立请求时 escape=true——转 Host 直答、访谈暂挂 */
    public record Divert(Boolean escape, String reason) {}

    /** 边界判定：用户本轮提出新主张时给出；verdict ∈ 合理/不合理；不合理必须附替代方案 */
    public record Validity(String target, String verdict, String reason, String alternative) {}

    /**
     * 需求简报：用户口径，各节未聊到的写「待澄清」。
     * openItems 是留给开发线的问题清单（移交后子对话的澄清起点）；
     * disputes 是建议过但用户未表态的默认值与被判不合理的原主张；
     * keyLog 是用户诉求/疑问/问题的原话级留痕，跨轮累积不丢弃。
     */
    public record Brief(String summary, String motivation,
                        @JsonDeserialize(using = StringOrListDeserializer.class) List<String> wants,
                        String audience, String expectations, String constraints,
                        List<OpenItem> openItems, List<Dispute> disputes, List<KeyItem> keyLog) {}

    /** 留给开发线的未决问题：question=要问什么，why=为什么必须在开发线问 */
    public record OpenItem(String question, String why) {}

    /** 待定分歧：建议过但用户未表态的默认值、被判不合理的原主张 */
    public record Dispute(String target, String alternative) {}

    /** 关键项留痕：用户诉求/疑问/问题（尽量保留原话），status ∈ resolved/open */
    public record KeyItem(String type, String content, String status, String conclusion) {}
}
