package com.mengx.server.llm;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * host 档流式模型的并发闸装饰器。
 *
 * 背景：host/route 共用的 glm-4-flash 智谱并发上限 20 路，其中流式请求自发出至流结束
 * 全程占用一路（单次 20~60 秒）；多用户同时对话时瞬时叠加，超出配额会被智谱直接 429，
 * 而 langchain4j 的 maxRetries 为立即快速重试，救不了持续满载——用户表现为空回复。
 *
 * 策略：闸门 permits 取配额减余量（路由档与重试留量）；超出的请求在此排队等待
 * （前端表现为该轮回复延迟启动），等待超时抛 IllegalStateException 走前端 error 兜底。
 * permit 在流终结（onCompleteResponse/onError）时释放；委托同步抛错时兜底释放。
 */
public class ConcurrencyGuardedStreamingModel implements StreamingChatModel {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyGuardedStreamingModel.class);

    private final StreamingChatModel delegate;
    private final Semaphore permits;
    private final long acquireTimeoutSeconds;

    public ConcurrencyGuardedStreamingModel(StreamingChatModel delegate, int permits, long acquireTimeoutSeconds) {
        this.delegate = delegate;
        this.permits = new Semaphore(permits);
        this.acquireTimeoutSeconds = acquireTimeoutSeconds;
    }

    @Override
    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        try {
            if (!permits.tryAcquire(acquireTimeoutSeconds, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "模型并发闸等待超时（" + acquireTimeoutSeconds + "s，permits=" + permits.availablePermits() + "）");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("模型并发闸等待被中断", e);
        }
        boolean delegated = false;
        try {
            // 走 delegate.chat 完整默认链（参数归一化 + listener 触发），仅包装流终结回调释放 permit
            delegate.chat(request, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                    handler.onPartialResponse(partialResponse);
                }

                @Override
                public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
                    handler.onPartialResponse(partialResponse, context);
                }

                @Override
                public void onPartialThinking(PartialThinking partialThinking) {
                    handler.onPartialThinking(partialThinking);
                }

                @Override
                public void onPartialThinking(PartialThinking partialThinking, PartialThinkingContext context) {
                    handler.onPartialThinking(partialThinking, context);
                }

                @Override
                public void onPartialToolCall(PartialToolCall partialToolCall) {
                    handler.onPartialToolCall(partialToolCall);
                }

                @Override
                public void onPartialToolCall(PartialToolCall partialToolCall, PartialToolCallContext context) {
                    handler.onPartialToolCall(partialToolCall, context);
                }

                @Override
                public void onCompleteToolCall(CompleteToolCall completeToolCall) {
                    handler.onCompleteToolCall(completeToolCall);
                }

                @Override
                public void onUnmappedRawEvent(Object rawEvent) {
                    handler.onUnmappedRawEvent(rawEvent);
                }

                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    permits.release();
                    handler.onCompleteResponse(completeResponse);
                }

                @Override
                public void onError(Throwable error) {
                    permits.release();
                    handler.onError(error);
                }
            });
            delegated = true;
        } finally {
            if (!delegated) {
                permits.release();
            }
        }
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public List<ChatModelListener> listeners() {
        return delegate.listeners();
    }

    @Override
    public ModelProvider provider() {
        return delegate.provider();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
    }
}
