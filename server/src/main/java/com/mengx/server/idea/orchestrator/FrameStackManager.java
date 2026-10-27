package com.mengx.server.idea.orchestrator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mengx.server.idea.IdeaSessionEntity;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 会话帧栈管理：多通道切换时的挂起/恢复。
 *
 * <p>每个通道（host/law/stock…）是一个独立帧，历史按 producer_channel 分帧隔离。
 * 帧栈记录已挂起的通道名，栈首为最近挂起的帧。切换通道时：
 * <ul>
 *   <li>新通道不在栈中 → 旧活跃帧挂起压栈，新通道激活（新帧）</li>
 *   <li>新通道已在栈中 → 从栈移除该通道，旧活跃帧压栈，新通道激活（恢复旧帧，历史自动恢复）</li>
 *   <li>新通道已是活跃帧 → 无操作</li>
 * </ul>
 *
 * <p>帧历史不随挂起销毁，始终按 producer_channel 存在 idea_messages 中，恢复时直接读取。
 * 旧版的帧级状态快照（domainFrame/routeIntent/routeReason）已随路由架构下线——
 * 这些字段在新架构下无生产者，快照/恢复属空转。
 */
@Component
public class FrameStackManager {

    private static final Logger log = LoggerFactory.getLogger(FrameStackManager.class);

    /** 挂起帧上限（超过则丢弃最久未激活的帧） */
    public static final int MAX_FRAMES = 8;

    private final ObjectMapper objectMapper;
    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {};

    public FrameStackManager(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 解析帧栈（空栈返回空列表，解析失败也返回空列表并告警） */
    public List<String> getStack(IdeaSessionEntity session) {
        String raw = session.getFrameStack();
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<String> stack = objectMapper.readValue(raw, LIST_TYPE);
            return stack == null ? new ArrayList<>() : new ArrayList<>(stack);
        } catch (Exception e) {
            log.warn("帧栈解析失败（sessionId={}），按空栈处理：{}", session.getId(), e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 激活指定通道，处理挂起/恢复：
     * 新通道压栈旧活跃帧并成为活跃帧；若新通道已在挂起栈中则恢复（历史按通道自动找回）。
     */
    public void activate(IdeaSessionEntity session, String newChannel) {
        if (newChannel == null || newChannel.isBlank()) {
            return;
        }
        String active = session.getMode();
        if (newChannel.equals(active)) {
            return; // 已是活跃帧
        }
        List<String> stack = getStack(session);
        boolean resumed = stack.remove(newChannel); // 若在栈中则移除（恢复）
        if (active != null && !active.isBlank()) {
            stack.add(0, active); // 旧活跃帧压栈
        }
        // 栈容量控制：超过上限丢弃最久未激活的帧
        while (stack.size() > MAX_FRAMES) {
            String dropped = stack.remove(stack.size() - 1);
            log.info("帧栈超限，丢弃最久未激活帧：{}（sessionId={}）", dropped, session.getId());
        }
        session.setMode(newChannel);
        writeStack(session, stack);
        if (resumed) {
            log.info("帧恢复：{}（sessionId={}，挂起栈={}）", newChannel, session.getId(), stack);
        } else {
            log.info("帧切换：{} → {}（sessionId={}，挂起栈={}）", active, newChannel, session.getId(), stack);
        }
    }

    /** 判断某通道是否在挂起栈中 */
    public boolean isSuspended(IdeaSessionEntity session, String channel) {
        return getStack(session).contains(channel);
    }

    private void writeStack(IdeaSessionEntity session, List<String> stack) {
        if (stack == null || stack.isEmpty()) {
            session.setFrameStack(null);
            return;
        }
        try {
            session.setFrameStack(objectMapper.writeValueAsString(stack));
        } catch (Exception e) {
            log.warn("帧栈序列化失败（sessionId={}）：{}", session.getId(), e.getMessage());
        }
    }
}
