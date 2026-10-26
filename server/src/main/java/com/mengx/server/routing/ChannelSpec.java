package com.mengx.server.routing;

import java.util.List;

/**
 * 通道注册条目：每个能力（agent/道场）在注册表里登记自己的路由元数据 + Worker 执行元数据。
 * L1 的确定性信号词与 L2 的名册提示词均由注册表条目生成——
 * 新能力上线 = 注册表加一条，路由代码零改动。
 *
 * 执行元数据（workerType/timeoutMs/needsRag/inputSchema/outputSchema）供编排器调度时使用，
 * 与路由信号解耦：路由决定"交给谁"，执行元数据决定"怎么调、等多久、要不要检索"。
 */
public record ChannelSpec(
        String id,
        String name,
        String routeDescription,
        List<String> signals,
        List<String> strongSignals,
        List<Example> examples,
        // ---- Worker 执行元数据 ----
        WorkerType workerType,
        int timeoutMs,
        boolean needsRag,
        String inputSchema,
        String outputSchema) {

    /** few-shot 示例：这句话应该路由到哪个通道 */
    public record Example(String utterance, String channel) {}
}
