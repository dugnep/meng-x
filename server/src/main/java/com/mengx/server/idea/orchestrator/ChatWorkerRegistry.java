package com.mengx.server.idea.orchestrator;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Worker 注册表：Spring 启动时收集所有 ChatWorker Bean，按 name() 建索引。
 * Host function calling 架构下对话轮不再走 Worker 路由，
 * 目前仅卡片装配器（CardComposerWorker）经此流转，编排器按名取用。
 *
 * 新 Worker 接入：实现 ChatWorker + 注册为 Spring Bean，此处自动收集，无需改注册表代码。
 */
@Component
public class ChatWorkerRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChatWorkerRegistry.class);

    private final Map<String, ChatWorker> workers;

    public ChatWorkerRegistry(List<ChatWorker> workerBeans) {
        this.workers = workerBeans.stream()
                .collect(Collectors.toUnmodifiableMap(ChatWorker::name, w -> w, (a, b) -> {
                    log.warn("Worker 重名「{}」，后者覆盖前者", a.name());
                    return b;
                }));
        log.info("Worker 注册表就绪：{} 个已注册 — {}", workers.size(), workers.keySet());
    }

    /** 按名取 Worker，未注册返回 null（编排器据此走兜底） */
    public ChatWorker get(String name) {
        return name == null ? null : workers.get(name);
    }

    /** 全部已注册 Worker（可观测性/健康检查用） */
    public java.util.Collection<ChatWorker> all() {
        return workers.values();
    }

    /** 指定 Worker 是否已注册 */
    public boolean isRegistered(String name) {
        return workers.containsKey(name);
    }
}
