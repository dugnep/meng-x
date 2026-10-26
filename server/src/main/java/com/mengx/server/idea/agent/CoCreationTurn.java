package com.mengx.server.idea.agent;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.List;

/**
 * 共创推演官（CoCreationAgent）的单轮推演结论（新契约：维度由需求推演，无固定格子）：
 * 本轮聚焦点、建议问法（可附选项）、建议方案、合理性判定、待定决策、关键项留痕，
 * 以及更新后的全量快照（核心三要素 + 动态维度）与收口信号。
 * 由 DiscoveryService 原样序列化为状态信封存档（SSE design 事件直发前端营造图面板）。
 */
public record CoCreationTurn(
        String focus,
        String question,
        @JsonDeserialize(using = StringOrListDeserializer.class) List<String> options,
        String proposal,
        Validity validity,
        List<Decision> decisions,
        Boolean closing,
        Boolean closingConfirmed,
        Boolean ack,
        String priority,
        Snapshot snapshot,
        List<KeyItem> keyItems) {

    /** 合理性判定：用户本轮提出新要求时给出；verdict ∈ 合理/不合理；不合理必须附替代方案 */
    public record Validity(String target, String verdict, String reason, String alternative) {}

    /** 待定决策：被判不合理、等用户表态的要求——表态前不入快照；用户坚持时带风险入快照并移出 */
    public record Decision(String target, String alternative) {}

    /** 规格快照：核心三要素 + 动态维度（全部由当前需求推演得出，无固定清单） */
    public record Snapshot(Core core, List<Dimension> dimensions) {}

    /** 核心三要素：做什么（功能+做到什么程度）、怎么算成、产品级实现思路 */
    public record Core(@JsonDeserialize(using = StringOrListDeserializer.class) List<String> functions,
                       String effects, String approach) {}

    /** 动态维度：name=从需求推演出的维度名，content=维度内容（「待聊」= 还没聊到） */
    public record Dimension(String name, String content) {}

    /** 关键项留痕：用户的诉求/疑问/问题，跨轮累积不覆盖；status ∈ resolved/open */
    public record KeyItem(String type, String content, String status, String conclusion) {}
}
