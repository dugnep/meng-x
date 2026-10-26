package com.mengx.server.idea;

import com.mengx.server.idea.orchestrator.ChatOrchestrator;
import java.util.List;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 主对话门面（Phase 1 瘦身）：仅承载前后端协议类型（StreamEvent/InMessage/常量），
 * 编排逻辑已搬移至 {@link ChatOrchestrator}。IdeaChatService.stream() 为纯委托。
 *
 * 保留协议类型在此而非独立文件，是因为 LawQuizFlowService、IdeaProposalService、
 * LawAgentService、IdeaController 等多处已引用 IdeaChatService.StreamEvent / InMessage，
 * 独立拆分改动面过大，Phase 2 再考虑协议类型外移。
 */
@Service
public class IdeaChatService {

    public record InMessage(String role, String content) {}

    /** SSE 流事件类型（前后端约定，改动需同步 web/src/api.js 注释与 Chat.jsx 分支） */
    public record StreamEvent(String type, String content) {
        public static final String THINKING = "thinking";
        public static final String CHUNK = "chunk";
        public static final String CARD = "card";
        public static final String QUESTION = "question";
        public static final String DESIGN = "design";
        public static final String CITATIONS = "citations";
        public static final String META = "meta";
        public static final String DONE = "done";
        public static final String ERROR = "error";
    }

    /** 单场对话卡片数上限，防灌水（设计方案 §9）；提案服务闸门共用 */
    public static final int MAX_CARDS_PER_SESSION = 5;

    private final ChatOrchestrator orchestrator;

    public IdeaChatService(ChatOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * 流式访谈。事件类型见 {@link StreamEvent} 常量。
     * clientRoundId：前端生成的一轮幂等键，重发同一轮不重复落库；null 不做幂等。
     */
    public Flux<StreamEvent> stream(IdeaSessionEntity session, List<InMessage> history,
                                   String mode, boolean greet, String clientRoundId) {
        return orchestrator.stream(session, history, mode, greet, clientRoundId);
    }
}
