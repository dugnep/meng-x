package com.mengx.server.idea.agent;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * idea 域内部 agent 的装配工厂：ChatModel 可用时懒加载同步 AiServices 代理
 * （CoCreation/OpportunityObserver/CardComposer 等）。
 * 主对话已由 HostWorker（function calling）接管，不再走 AiServices，
 * 托管对话窗口（旧 IdeaHostChatMemory）随之废弃——Host 帧上下文由
 * ContextManager.loadHostFrame 统一从服务端取数。
 * api-key 缺失时对应 Bean 不在容器中，{@link #available()} 为 false，
 * 调用方按原有路径降级（观察跳过/未配置错误事件）。
 * 后续 AgentModelCatalog 落地后，此处按 agent 名取各自模型档即可，调用方不变。
 */
@Component
public class IdeaAgentFactory {

    private final ObjectProvider<ChatModel> chatModelProvider;

    private volatile CoCreationAgent coCreator;
    private volatile OpportunityObserverAgent observer;
    private volatile CardComposerAgent composer;
    private volatile WishDistillerAgent wishDistiller;
    private volatile WishRefinerAgent wishRefiner;
    private volatile PrdFormatterAgent prdFormatter;
    private volatile ThanksAgent thanks;

    public IdeaAgentFactory(ObjectProvider<ChatModel> chatModelProvider) {
        this.chatModelProvider = chatModelProvider;
    }

    /** 同步模型是否已配置（未配置 api-key 时内部 agent 整体不可用） */
    public boolean available() {
        return chatModelProvider.getIfAvailable() != null;
    }

    /** 共创推演官：发现模式下每轮同步推演快照与议程 */
    public CoCreationAgent coCreator() {
        CoCreationAgent local = coCreator;
        if (local == null) {
            synchronized (this) {
                local = coCreator;
                if (local == null) {
                    local = AiServices.create(CoCreationAgent.class, requireModel());
                    coCreator = local;
                }
            }
        }
        return local;
    }

    /** 机会观察器：主对话代理收口后旁路判定 PROPOSE/NONE/EXIT */
    public OpportunityObserverAgent observer() {
        OpportunityObserverAgent local = observer;
        if (local == null) {
            synchronized (this) {
                local = observer;
                if (local == null) {
                    local = AiServices.create(OpportunityObserverAgent.class, requireModel());
                    observer = local;
                }
            }
        }
        return local;
    }

    /** 卡片装配器：宣布定稿后异步装配功能卡 JSON */
    public CardComposerAgent composer() {
        CardComposerAgent local = composer;
        if (local == null) {
            synchronized (this) {
                local = composer;
                if (local == null) {
                    local = AiServices.create(CardComposerAgent.class, requireModel());
                    composer = local;
                }
            }
        }
        return local;
    }

    public WishDistillerAgent wishDistiller() {
        WishDistillerAgent local = wishDistiller;
        if (local == null) {
            synchronized (this) {
                local = wishDistiller;
                if (local == null) {
                    local = AiServices.create(WishDistillerAgent.class, requireModel());
                    wishDistiller = local;
                }
            }
        }
        return local;
    }

    public WishRefinerAgent wishRefiner() {
        WishRefinerAgent local = wishRefiner;
        if (local == null) {
            synchronized (this) {
                local = wishRefiner;
                if (local == null) {
                    local = AiServices.create(WishRefinerAgent.class, requireModel());
                    wishRefiner = local;
                }
            }
        }
        return local;
    }

    public PrdFormatterAgent prdFormatter() {
        PrdFormatterAgent local = prdFormatter;
        if (local == null) {
            synchronized (this) {
                local = prdFormatter;
                if (local == null) {
                    local = AiServices.create(PrdFormatterAgent.class, requireModel());
                    prdFormatter = local;
                }
            }
        }
        return local;
    }

    public ThanksAgent thanks() {
        ThanksAgent local = thanks;
        if (local == null) {
            synchronized (this) {
                local = thanks;
                if (local == null) {
                    local = AiServices.create(ThanksAgent.class, requireModel());
                    thanks = local;
                }
            }
        }
        return local;
    }

    private ChatModel requireModel() {
        ChatModel model = chatModelProvider.getIfAvailable();
        if (model == null) {
            throw new IllegalStateException("模型未配置（ai.model.api-key 缺失），agent 无法运行");
        }
        return model;
    }
}
