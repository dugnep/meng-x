package com.mengx.server.idea.orchestrator;

/**
 * 对话 Worker 契约（对标 forge 模块的 ForgeAgent）：
 * 编排归 ChatOrchestrator（代码，不是模型），模型推理归 Worker。
 * 每个 Worker 只做一件事：读 WorkerContext → 产出 WorkerResult。
 * Worker 之间禁止直接对话，不碰会话状态（状态迁移归编排器）。
 *
 * 旧版的 stream/streamMessages 流式契约已随 DomainTool 架构下线——
 * 领域流式输出现在由 DomainTool 直达用户，Worker 只承担阻塞形态的结构化产物装配
 * （当前唯一实现：CardComposerWorker 卡片装配器）。
 *
 * 新 Worker 接入两步：① 实现本接口 ② Spring 注册为 Bean（ChatWorkerRegistry 自动收集）。
 */
public interface ChatWorker {

    /** Worker 唯一标识（编排器按名取 Worker） */
    String name();

    /** 能力元数据（供可观测性展示） */
    WorkerCapability capability();

    /** 执行一次 Worker 任务：读 ctx，把产物写进 WorkerResult 返回（阻塞形态） */
    WorkerResult run(WorkerContext ctx);
}
