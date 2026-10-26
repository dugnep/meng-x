package com.mengx.server.idea.orchestrator;

import java.util.List;

/**
 * Worker 执行上下文（编排器组装，Worker 只读）。
 * 旧版四层上下文（domainFrame/routeIntent/routeReason/slots/evidence）已随路由架构下线，
 * 现仅保留卡片装配器实际消费的字段。
 */
public record WorkerContext(
        Long sessionId,
        Long userId,
        String nickname,
        String userMessage,
        String hostReply,            // 后置 Worker（装配器）需要上一轮主持回复；前置 Worker 为 null
        List<ChatTurn> history) {

    /** 对话轮次（user/assistant 为主，card/counsel 等占位行不进 Worker 上下文） */
    public record ChatTurn(String role, String content) {}
}
