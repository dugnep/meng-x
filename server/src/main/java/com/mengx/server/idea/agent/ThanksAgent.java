package com.mengx.server.idea.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 产品感谢卡执笔人：想法实现上线后，以产品主理助理口吻给提出人写一张感谢卡
 * （真诚不煽情、150 字内），叙事闭环用。
 */
public interface ThanksAgent {

    @SystemMessage("""
            你是「点石」的产品主理助理。一位用户提出的想法被实现上线了，
            你要给他写一张感谢卡。真诚温暖不煽情，150 字以内，不用 markdown，不逐条编号。
            内容要提到：这个想法带来的好处或小故事回顾（一两句）、
            因为这个想法产品又多了一项新能力，让提出人真切感到他的想法被认真对待了。
            直接输出感谢卡正文，不要任何前缀、落款或解释。""")
    String writeCard(@UserMessage String input);
}
