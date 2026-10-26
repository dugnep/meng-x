package com.mengx.server.idea.orchestrator;

import com.mengx.server.idea.IdeaChatService.InMessage;
import com.mengx.server.idea.IdeaMessageEntity;
import com.mengx.server.idea.IdeaMessageRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 上下文管理：Host 帧上下文统一从服务端取数（真源在 idea_messages，不信前端回传），
 * 并提供 history → ChatTurn 转换、取最近用户消息等小工具。
 */
@Component
public class ContextManager {

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    private final IdeaMessageRepository messageRepository;

    public ContextManager(IdeaMessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    /**
     * Host 帧上下文（服务端真源）：最近 40 条对话轮（user/assistant，全通道、不限帧），
     * 按时间正序返回。Host 是统一入口，需要看到所有通道的走向才能做路由决策；
     * 窗口截断防止长会话 token 膨胀（对齐旧托管窗口的 40 条设计）。
     */
    public List<InMessage> loadHostFrame(Long sessionId) {
        List<IdeaMessageEntity> rows = messageRepository
                .findTop40BySessionIdAndRoleInOrderByIdDesc(sessionId, List.of(ROLE_USER, ROLE_ASSISTANT));
        Collections.reverse(rows);
        List<InMessage> frame = new ArrayList<>(rows.size());
        for (IdeaMessageEntity row : rows) {
            frame.add(new InMessage(row.getRole(), row.getContent()));
        }
        return frame;
    }

    /** 取 history 中最后一条 user 消息文本（无或空内容返回空串） */
    public String latestUserText(List<InMessage> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            InMessage m = history.get(i);
            if (ROLE_USER.equals(m.role())) {
                return m.content() == null ? "" : m.content();
            }
        }
        return "";
    }

    /** InMessage → ChatTurn 转换 */
    public List<WorkerContext.ChatTurn> toChatTurns(List<InMessage> history) {
        List<WorkerContext.ChatTurn> turns = new ArrayList<>(history.size());
        for (InMessage m : history) {
            turns.add(new WorkerContext.ChatTurn(m.role(), m.content()));
        }
        return turns;
    }
}
