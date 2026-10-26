package com.mengx.server.idea.orchestrator;

/**
 * Worker 执行结果：结构化产物（功能卡 JSON 等），由编排器按类型分发。
 * 旧版的 answer/citations/clarify 分支已随路由架构下线——领域文本现在由
 * DomainTool 直达用户，Worker 只承担结构化产物装配。
 */
public record WorkerResult(Object payload) {

    /** 仅返回结构化产物 */
    public static WorkerResult payload(Object payload) {
        return new WorkerResult(payload);
    }
}
