package com.mengx.server.idea.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.mengx.server.idea.IdeaChatService;
import com.mengx.server.idea.IdeaChatService.InMessage;
import com.mengx.server.idea.IdeaChatService.StreamEvent;
import com.mengx.server.idea.IdeaSessionEntity;
import java.util.List;
import reactor.core.publisher.FluxSink;

/**
 * 领域能力工具接口：Host 主 Agent 可调用的专业领域能力。
 * 每个领域（法学/股票/共创…）实现一个 DomainTool，注册到 HostWorker 的工具列表。
 * Host 通过 function calling 决定是否调用；调用后领域 Worker 流式输出直达用户，
 * 不回传 Host 二次包装（保持领域专业性，如法学法条回答不经 Host 转述）。
 */
public interface DomainTool {

    /** 工具名（Host function calling 时使用，如 law_qa） */
    String name();

    /** 该领域对应的通道名（用于分帧隔离与落库，如 "law"） */
    String channel();

    /** 工具描述（告知 Host 何时该调用，写在 tool description 中） */
    String description();

    /** 参数 JSON Schema（OpenAI function calling 格式） */
    String parametersSchema();

    /**
     * 执行领域能力，流式输出到 sink。
     * 实现方必须保证最终发出 DONE 或 ERROR 事件并 complete sink。
     *
     * @param session  当前会话
     * @param history  会话历史（Host 帧）
     * @param args     Host 传入的参数（已解析为 JsonNode）
     * @param sink     流式输出目的地（CHUNK/CITATIONS/DONE/ERROR）
     * @return 该领域输出的完整文本（供落库用）
     */
    String invoke(IdeaSessionEntity session, List<InMessage> history, JsonNode args,
                  FluxSink<StreamEvent> sink);
}

