package com.mengx.server.routing;

/**
 * Worker 执行形态：决定编排器如何调度与回收结果。
 * SYNC_STREAM — 流式同步（主对话/法学），结果逐 token 流出，编排器转发 SSE
 * SYNC_BLOCK — 阻塞同步（共创推演/卡片装配），等完整产物返回
 * ASYNC      — 异步旁路（机会观察器），不阻塞主对话收口，结果由编排器后续消费
 */
public enum WorkerType {
    SYNC_STREAM,
    SYNC_BLOCK,
    ASYNC
}
