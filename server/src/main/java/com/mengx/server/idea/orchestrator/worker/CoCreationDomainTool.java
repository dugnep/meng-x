package com.mengx.server.idea.orchestrator.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.mengx.server.idea.CoCreationService;
import com.mengx.server.idea.IdeaChatService.InMessage;
import com.mengx.server.idea.IdeaChatService.StreamEvent;
import com.mengx.server.idea.IdeaSessionEntity;
import com.mengx.server.idea.orchestrator.DomainTool;
import java.util.List;
import org.springframework.stereotype.Component;
import reactor.core.publisher.FluxSink;

/**
 * 共创领域工具：接管整轮，推演快照 + 领域顾问流式访谈直达用户，
 * 定稿后功能卡异步装配——全部内聚 CoCreationService，不经 Host 二次包装。
 * 许可制入口（availableFor 状态硬控 + 工具描述双防线）：冷启动不可见——机会识别归
 * 观察器旁路提案、用户经邀请卡许可后才可进入；许可后首轮及访谈热窗内可见，
 * 热窗内由 ChatOrchestrator.routeHint 注入指令化路由保障延续（V2）。
 */
@Component
public class CoCreationDomainTool implements DomainTool {

    private final CoCreationService coCreation;

    public CoCreationDomainTool(CoCreationService coCreation) {
        this.coCreation = coCreation;
    }

    @Override
    public String name() {
        return "co_creation";
    }

    @Override
    public String channel() {
        return CoCreationService.CHANNEL;
    }

    @Override
    public String description() {
        return "需求共创领域：与用户一起把一个想法或模糊需求聊成清晰、可落地的功能方案"
                + "（需求澄清、方案推演、规格快照、功能卡产出）。"
                + "仅在共创访谈已发起后调用（用户经邀请卡许可进入共创模式，"
                + "或会话中已有未收尾的共创访谈需要延续）；"
                + "用户初次表达想法/需求时不要直接调用——机会识别由邀请卡机制处理，正常对话即可。"
                + "共创访谈尚未定稿时，用户的常规输入默认属于本次访谈——即使提到法律等领域词汇，"
                + "只要是在讨论方案设计（如\"要小心别违反劳动法\"），仍应调用本工具；"
                + "仅当用户明确提出其他领域的独立请求（如\"给我出几道劳动法的题\"）时，"
                + "才改调对应领域工具或直答。";
    }

    @Override
    public String parametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "idea": {
                      "type": "string",
                      "description": "用户想共创的想法或本轮访谈输入，原样传入"
                    }
                  },
                  "required": ["idea"]
                }
                """;
    }

    /** 许可制入口：访谈热窗内或存在未消化的已许可提案时可见，冷启动不可见（见 CoCreationService.licensedToEnter） */
    @Override
    public boolean availableFor(IdeaSessionEntity session) {
        return coCreation.licensedToEnter(session.getId());
    }

    @Override
    public String invoke(IdeaSessionEntity session, List<InMessage> history, JsonNode args,
                         FluxSink<StreamEvent> sink) {
        String idea = args.has("idea") ? args.get("idea").asText() : "";
        if (idea.isBlank()) {
            for (int i = history.size() - 1; i >= 0; i--) {
                if ("user".equals(history.get(i).role())) {
                    idea = history.get(i).content();
                    break;
                }
            }
        }
        return coCreation.handleInterview(session, history, idea, sink);
    }
}
