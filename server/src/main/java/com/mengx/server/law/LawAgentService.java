package com.mengx.server.law;

import com.mengx.server.idea.IdeaChatService.InMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 法学助手 Agent（内部 agent，经主对话编排调用）：消息路由已移交 com.mengx.server.routing
 * （注册表驱动的分层路由：L1 信号短路 / L2 上下文细判 / L3 粘性兜底），
 * 本类只负责法学人设与作答消息集；回答经主对话流式通道直达用户。
 * 法条库 RAG 检索、引用溯源、出题、批改等能力长在本类上。
 */
@Service
public class LawAgentService {

    private final LawSearchService lawSearch;

    public LawAgentService(LawSearchService lawSearch) {
        this.lawSearch = lawSearch;
    }

    /** 法律顾问人设：严谨专业的法律顾问（日常法律咨询 + 法考辅导双职责；RAG 检索结果注入人设尾部） */
    private static final String LAW_PROMPT = """
            你是「点石」的法律顾问，正在为「{nickname}」解答：必须严谨、专业、条理清晰。用户可能带着日常法律困惑而来，也可能走在法考备考的路上——两样都接得住。

            【职责】日常法律咨询（法条含义、权利义务、纠纷分析、维权路径指引、合同与文书注意点）、法条与概念解释、易混淆概念辨析、案例分析；同时兼具法考辅导（考试角度点拨、常考点与命题陷阱、真题讲解、备考方法建议）。

            【答法】先给直接结论，再给依据与展开；需要分点时用中文序号「一、二、三、」自然叙述，禁止 markdown 符号与"1. 2. 3."罗列；口语化、克制篇幅，一次回复聚焦回答问题本身，结尾视情况给一句提示或追问是否需要展开。

            【边界（硬约束）】
            1. 已接入法条库（民法典、刑法、诉讼法等 18 部 + 司法解释）。回答优先依据【法条库检索结果】，引用时标注《法名》第X条；检索结果未覆盖时凭所学作答，并提醒用户核对法条原文。
            2. 已接入历年真题库（2002 年以来客观题）。【历年真题参考】给出相近真题时，自然带出「这个考点 X 年真题怎么考」，讲解其答案要点；用户点名想看某年真题时，据检索结果如实列出题干与答案要点；未给出时凭所学点拨常考角度，不编造不存在的真题。
            3. 解答以普法与学习为限：可以分析法律规定、一般处理思路与风险所在，但不代表当事人出具正式法律意见、不预测具体判决结果；涉及重大利益或真实诉讼时，建议咨询执业律师。
            4. 用户明显在聊功能创意、提想法（即使内容本身涉及法律）时，不要硬当法律问题作答——
               直接告知这个话题请回到主对话聊，主对话会接着处理；这类消息不接法条、不谈法律。
            """;

    /**
     * 法学助手作答消息集：人设 + RAG 检索上下文（法条 + 真题，注入人设尾部）+ 学情参考行（画像 P1）
     * + 对话历史。防御：空 content 消息不发给模型（智谱 1214）。
     */
    public List<ChatMessage> buildMessages(String nickname, List<InMessage> history,
            List<LawSearchService.SearchHit> refs, List<LawQuestionEntity> examQuestions,
            String profileHint) {
        StringBuilder system = new StringBuilder(
                LAW_PROMPT.replace("{nickname}", nickname == null ? "用户" : nickname));
        if (profileHint != null && !profileHint.isBlank()) {
            system.append('\n').append(profileHint);
        }
        String ragContext = LawSearchService.toContext(refs);
        if (!ragContext.isBlank()) {
            system.append('\n').append(ragContext);
        }
        String examContext = lawSearch.toExamContext(examQuestions);
        if (!examContext.isBlank()) {
            system.append('\n').append(examContext);
        }
        List<ChatMessage> messages = new ArrayList<>(history.size() + 1);
        messages.add(SystemMessage.from(system.toString()));
        for (InMessage m : history) {
            if (m == null || m.content() == null || m.content().isBlank()) {
                continue;
            }
            switch (m.role()) {
                case "user" -> messages.add(UserMessage.from(m.content()));
                case "assistant" -> messages.add(AiMessage.from(m.content()));
                default -> {}
            }
        }
        return messages;
    }
}
