package com.mengx.server.idea.orchestrator;

import java.util.List;

/**
 * Worker 能力元数据：路由匹配的辅助依据 + 可观测性展示。
 * signalTags 是能力关键词（与 ChannelSpec.signals 互补，供未来语义路由用）。
 */
public record WorkerCapability(
        String name,
        String description,
        List<String> signalTags) {

    public WorkerCapability(String name, String description) {
        this(name, description, List.of());
    }
}
