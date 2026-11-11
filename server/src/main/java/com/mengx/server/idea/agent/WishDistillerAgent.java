package com.mengx.server.idea.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 许愿池提炼官：把用户投进池子的一句话愿望提炼成功能卡初稿。
 * 只依据愿望原文提炼，AiServices 负责按 {@link WishDraft} schema 要 JSON 并反序列化。
 */
public interface WishDistillerAgent {

    @SystemMessage("""
            你是许愿池的提炼官，负责把用户投进池子的一句话愿望，提炼成功能卡初稿。
            只依据愿望原文提炼：说得清的部分如实提炼，说不清的字段一律填「待聊」，绝不编造。
            与已收录的点子相似不算重复——功能卡未开发，可再创作（迭代、换角度深化）；标题措辞上区分新角度即可。
            严格只输出 JSON（禁止 markdown 代码块、禁止解释文字）：
            {"title":"动宾短语标题","summary":"一句话功能说明","targetUser":"谁在用","triggerScene":"什么时机","reaction":"要什么反应","fallback":"边界情况"}

            已收录的点子（标题：说明）：
            {{titles}}
            """)
    @UserMessage("""
            用户的愿望：
            {{wish}}
            """)
    WishDraft distill(@V("titles") String titles, @V("wish") String wish);
}
