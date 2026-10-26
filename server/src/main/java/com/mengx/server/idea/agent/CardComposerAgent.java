package com.mengx.server.idea.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 卡片装配器（Card Composer）：客人点头定稿（推演官 closingConfirmed 状态驱动）后异步调用，
 * 把这场需求澄清访谈的成果装配成功能卡 JSON（核心三要素 + 动态维度 + 边界兜底 + 关键项留痕）。
 * 本接口只负责装配并返回原文；卡数上限、落库等业务闸门在编排层。
 */
public interface CardComposerAgent {

    @SystemMessage("""
            你是应用提案的定稿装配器。客人已点头同意定稿，\
            你的任务是把这场需求澄清访谈的成果装配成一张结构化卡片。\
            只输出一个 JSON 对象（禁止 markdown 代码块、禁止解释文字）。
            """)
    @UserMessage("""
            装配规则：
            - 字段值从【设计简报】的规格快照与关键项留痕提取，【最近访谈记录】仅作补充；\
              两边都没覆盖的细节填「待聊」，不要编造；主持人说过但客人没认可的内容同样填「待聊」。
            - functions 逐项写「功能 + 做到什么程度」；effects 是怎么算做成的达成判据；\
              approach 是产品级实现思路（不是技术设计）。
            - dimensions 誊抄快照里的动态维度（name=维度名，content=内容），「待聊」原样保留；没有时空数组。
            - constraints 记边界与兜底（出错/极端场景怎么办），不记市面替代方案。
            - feasibilityNotes 记可行性说明：客人坚持带来的风险、推演官提示的隐忧；没有写空串。
            - keyItems 誊抄简报的关键项留痕（type/content/status/conclusion 原样）；没有时空数组。
            - priority 从【优先级建议】取（只能是 高/中/低），为空填"中"。
            - creativity 是新颖度评分，1~5 的整数。

            输出严格 JSON：
            {"title":"动宾短语标题","summary":"一句话功能说明，说人话","functions":["功能 + 做到什么程度"],"effects":"怎么算做成了","approach":"产品级实现思路","dimensions":[{"name":"维度名","content":"维度内容"}],"constraints":"边界与兜底","feasibilityNotes":"可行性说明与风险","keyItems":[{"type":"诉求|疑问|问题","content":"一句话","status":"open|resolved","conclusion":"结论，open 时空串"}],"creativity":3,"priority":"高|中|低"}

            【优先级建议】：{{priority}}

            【设计简报】（内部工作文档）：
            {{briefing}}

            【最近访谈记录】（客人=用户，主持=主对话代理）：
            {{dialog}}
            """)
    String compose(@V("priority") String priority,
                   @V("briefing") String briefing,
                   @V("dialog") String dialog);
}
