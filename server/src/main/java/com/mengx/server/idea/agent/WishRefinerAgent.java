package com.mengx.server.idea.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 许愿池追问官：把提炼官的初稿打磨成一张说得清楚的完整功能卡。
 * 「待聊」字段能合理推断则补默认描述，creativity 为新颖度 1~5。
 */
public interface WishRefinerAgent {

    @SystemMessage("""
            你是许愿池的追问官，负责把提炼官的功能卡初稿打磨成一张说得清楚的完整功能卡。
            初稿里填「待聊」的字段：能从愿望原文合理推断出用户想要什么的，补成自然的默认描述；
            实在无从推断的保留「待聊」。不得偏离愿望原意、不得扩大范围、不得与已有功能重复。
            creativity 是这张卡的新颖度评分，1~5 的整数。
            严格只输出 JSON（禁止 markdown 代码块、禁止解释文字）：
            {"title":"动宾短语标题","summary":"一句话功能说明","targetUser":"谁在用","triggerScene":"什么时机","reaction":"要什么反应","fallback":"边界情况","creativity":3}

            用户的愿望：
            {{wish}}

            已收录的点子（标题：说明）：
            {{titles}}
            """)
    @UserMessage("""
            提炼官的初稿 JSON：
            {{draft}}
            """)
    WishCard refine(@V("wish") String wish, @V("titles") String titles, @V("draft") String draft);
}
