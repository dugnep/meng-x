package com.mengx.server.idea.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 卡片装配器（Card Composer）：用户同意移交（需求预澄清员 handoffConfirmed 状态驱动）后异步调用，
 * 把这场需求预澄清访谈沉淀的【需求简报】装配成功能卡（移交件 JSON：用户口径的简报各节）。
 * 开发线（forge）将以这张卡为起点继续与用户澄清并产出 PRD。
 * 本接口只负责装配并返回原文；卡数上限、落库等业务闸门在编排层。
 */
public interface CardComposerAgent {

    @SystemMessage("""
            你是应用想法的移交装配器。用户已明确同意移交，需求预澄清阶段结束，\
            你的任务是把这场需求澄清访谈的成果装配成一张结构化卡片（移交件）。\
            只输出一个 JSON 对象（禁止 markdown 代码块、禁止解释文字）。
            """)
    @UserMessage("""
            装配规则：
            - 字段值一律从【需求简报】提取，【最近访谈记录】仅作补充；\
              两边都没覆盖的写「待澄清」，不要编造；对话代理说过但用户没认可的内容同样写「待澄清」。
            - summary 用一句话说清「替谁解决什么问题」，取简报的 summary；
            - wants 逐项保留「做什么 + 用户期望的程度」的用户口径，不加工成实现方案；
            - motivation/audience/expectations/constraints 原样誊抄简报对应节（未聊到写「待澄清」）；
            - openItems 誊抄简报的留给开发线的问题（question/why 结构原样）；没有时空数组；
            - disputes 誊抄待定分歧（target/alternative 结构原样）；没有时空数组；
            - keyLog 誊抄关键项留痕（type/content/status/conclusion 原样）；没有时空数组；
            - creativity 是新颖度评分，1~5 的整数。

            输出严格 JSON：
            {"title":"动宾短语标题","summary":"一句话：替谁解决什么问题","motivation":"背景与动机","wants":["功能 + 用户期望的程度"],"audience":"使用者与场景","expectations":"呈现与体验期望","constraints":"硬性要求","openItems":[{"question":"留给开发线的问题","why":"为什么必须问"}],"disputes":[{"target":"待定主张","alternative":"替代方案"}],"keyLog":[{"type":"诉求|疑问|问题","content":"用户原话口径","status":"open|resolved","conclusion":"结论，open 时空串"}],"creativity":3}

            【需求简报】（内部工作文档）：
            {{briefing}}

            【最近访谈记录】：
            {{dialog}}
            """)
    String compose(@V("briefing") String briefing,
                   @V("dialog") String dialog);
}
