package com.mengx.server.idea.orchestrator;

import com.mengx.server.idea.CoCreationService;
import com.mengx.server.idea.DiscoveryEpochGuard;
import com.mengx.server.idea.DiscoveryService;
import com.mengx.server.idea.IdeaChatService;
import com.mengx.server.idea.IdeaChatService.InMessage;
import com.mengx.server.idea.IdeaChatService.StreamEvent;
import com.mengx.server.idea.IdeaMessageEntity;
import com.mengx.server.idea.IdeaMessageRepository;
import com.mengx.server.idea.IdeaProposalService;
import com.mengx.server.idea.IdeaSessionEntity;
import com.mengx.server.idea.IdeaSessionRepository;
import com.mengx.server.idea.orchestrator.worker.HostWorker;
import com.mengx.server.routing.ChannelRegistry;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

/**
 * 对话编排器：Host 统一入口（function calling 决策直答或调领域工具）→ 分帧隔离 → 流式输出 → 落库收口。
 *
 * <p>分帧隔离：每个通道（host/law/cocreation/…）是独立帧，历史按 producer_channel 分帧存储；
 * 切换通道时旧帧挂起压栈（FrameStackManager），切回时按通道恢复历史。
 * 通道切换由 Host LLM 调用对应领域工具时触发（onComplete 回调中 activate）。
 *
 * <p>路由提示：访谈热窗（最新推演未定稿且 30 分钟内，自愈判定、不依赖帧指针）向 Host
 * 注入指令化路由指令，由 Host function calling 执行。host 档 glm-5.3-flash 重放实测
 * 4/4 服从注入指令（glm-4-flash 免费档无视指令四连逃逸，session 153 定案弃用）。
 * 领域内推演、定稿装配全部内聚各领域服务，编排器只做通用收口。
 */
@Service
public class ChatOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ChatOrchestrator.class);

    private static final String CHANNEL_HOST = "host";
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    private static final String NOT_CONFIGURED_MSG = HostWorker.NOT_CONFIGURED_MSG;

    private final ObjectProvider<StreamingChatModel> streamingModelProvider;
    private final IdeaMessageRepository messageRepository;
    private final IdeaSessionRepository sessionRepository;
    private final DiscoveryService discoveryService;
    private final IdeaProposalService proposalService;
    private final DiscoveryEpochGuard epochGuard;
    private final ContextManager contextManager;
    private final FrameStackManager frameStackManager;
    private final HostWorker hostWorker;

    public ChatOrchestrator(
            ObjectProvider<StreamingChatModel> streamingModelProvider,
            IdeaMessageRepository messageRepository,
            IdeaSessionRepository sessionRepository,
            DiscoveryService discoveryService,
            IdeaProposalService proposalService,
            DiscoveryEpochGuard epochGuard,
            ContextManager contextManager,
            FrameStackManager frameStackManager,
            HostWorker hostWorker) {
        this.streamingModelProvider = streamingModelProvider;
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.discoveryService = discoveryService;
        this.proposalService = proposalService;
        this.epochGuard = epochGuard;
        this.contextManager = contextManager;
        this.frameStackManager = frameStackManager;
        this.hostWorker = hostWorker;
    }

    private void runQuietly(String what, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("{}（不影响对话）：{}", what, e.getMessage());
        }
    }

    private static StreamEvent thinking(String text) {
        return new StreamEvent(StreamEvent.THINKING, text);
    }

    // ==================== 编排主流程 ====================
    // Host 统一入口：所有用户消息先到 Host，Host 通过 function calling 决定直答或调用领域工具。
    // 领域工具输出直达用户、不经 Host 二次包装；编排器负责帧激活与落库。

    public Flux<StreamEvent> stream(IdeaSessionEntity session, List<InMessage> history,
                                    String mode, boolean greet, String clientRoundId) {
        StreamingChatModel model = streamingModelProvider.getIfAvailable();
        if (model == null) {
            return Flux.just(new StreamEvent(StreamEvent.ERROR, NOT_CONFIGURED_MSG));
        }
        if (greet) {
            return greetingFlux(session);
        }
        final String roundId = (clientRoundId == null || clientRoundId.isBlank())
                ? null : clientRoundId.trim();
        final String latestUser = contextManager.latestUserText(history);
        return Flux.<StreamEvent>create(sink -> {
            Long sessionId = session.getId();
            int turnEpoch = epochGuard.bump(sessionId);
            // 服务端真源：Host 帧历史（全通道、40 条窗）+ 当前轮输入，前端回传的整段历史不再作为上下文
            List<InMessage> effectiveHistory = contextManager.loadHostFrame(sessionId);
            effectiveHistory.add(new InMessage(ROLE_USER, latestUser));
            log.info("主对话开讲（sessionId={}，mode={}，history={} 条，epoch={}）",
                    sessionId, mode, effectiveHistory.size(), turnEpoch);
            sink.next(thinking("听声辨位，辨认来意…"));

            HostWorker.HostCompletionCallback callback = new HostWorker.HostCompletionCallback() {
                @Override
                public void onComplete(String channel, String replyText) {
                    // 帧激活：通道切换时旧帧挂起压栈，切回已有帧则恢复
                    frameStackManager.activate(session, channel);
                    runQuietly("路由状态保存", () -> sessionRepository.save(session));
                    // 用户轮落库（带正确通道）
                    persistUserTurn(sessionId, channel, latestUser, roundId);
                    boolean hostDirect = CHANNEL_HOST.equals(channel);
                    if (hostDirect) {
                        // Host 直答：落库 + 机会观察（Host 侧旁路，只评估 host 帧流量）
                        finishHostTurn(session, replyText, roundId, channel, turnEpoch);
                    } else {
                        // 领域工具通道（不感知具体领域，新领域零改动接入）：
                        // user 轮已由 persistUserTurn 统一落库，这里只补 assistant 行；
                        // 关流由领域工具自管（DomainTool 契约：必须自行发 done/error 并 complete）
                        runQuietly("领域消息入库",
                                () -> saveAssistantRound(sessionId, channel, replyText, roundId));
                        log.info("领域收口（sessionId={}，channel={}，回复 {} 字）", sessionId, channel,
                                replyText == null ? 0 : replyText.length());
                    }
                    if (hostDirect && !sink.isCancelled()) {
                        sink.next(new StreamEvent(StreamEvent.DONE, null));
                        sink.complete();
                    }
                }

                @Override
                public void onError(Throwable error) {
                    log.warn("本轮异常（sessionId={}）", sessionId, error);
                    sink.next(new StreamEvent(StreamEvent.ERROR, "仙官暂时开小差了，稍后再试试～"));
                    sink.next(new StreamEvent(StreamEvent.DONE, null));
                    sink.complete();
                }
            };

            // 统一走 Host：由 LLM function calling 决定直答或调用领域工具。
            // 路由提示注入（V2 机制）：host 档 glm-5.3-flash 重放实测 4/4 服从注入指令；
            // 用户显式选择其他领域（如法学按钮）时不注入访谈指令——尊重用户意愿
            boolean explicitOtherDomain = mode != null && !mode.isBlank()
                    && !CHANNEL_HOST.equals(mode) && !CoCreationService.CHANNEL.equals(mode);
            boolean interviewHot = !explicitOtherDomain && discoveryService.interviewHot(sessionId);
            hostWorker.stream(session, effectiveHistory, latestUser,
                    routeHint(mode, interviewHot), sink, callback);
        }, FluxSink.OverflowStrategy.BUFFER)
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 路由提示（注入 Host 系统提示，V2 机制）：
     * ① 显式 mode（用户点领域按钮）——优先该领域；
     * ② 访谈热窗（最新推演未定稿且 30 分钟内；自愈判定，不看帧指针——逃逸翻帧、
     *    定稿滞留、显式绕行三种帧失效场景均覆盖）——指令化路由，措辞与重放
     *    验证通过的 payload 一致（glm-5.3-flash 4/4 服从）；
     * ③ 其余——自由 function calling（无提示）。
     */
    private String routeHint(String mode, boolean interviewHot) {
        StringBuilder hint = new StringBuilder();
        if (mode != null && !mode.isBlank() && !CHANNEL_HOST.equals(mode)) {
            hint.append("\n（路由提示：用户本轮显式选择了 ").append(mode)
                    .append(" 领域，若无明确冲突请优先调用该领域的工具。）");
        }
        if (interviewHot) {
            hint.append("""

                    # 本轮路由指令（最高优先级）
                    一场需求共创访谈正在进行中（规格快照尚未定稿，用户此前输入多为对访谈问题的回答），\
                    本轮输入默认也属于这场访谈：你必须调用 co_creation 工具继续访谈，不要自己作答、不要自己梳理方案。\
                    只有当本轮输入明确提出了与本次访谈无关的全新独立请求（如查法条、出题、聊别的事）时才可离开共创处理，\
                    并在处理后主动问一句是否回到方案讨论。拿不准时，一律调用 co_creation。""");
        }
        return hint.toString();
    }

    private Flux<StreamEvent> greetingFlux(IdeaSessionEntity session) {
        String greeting =
                "法学助手已候命。这厢专司法条解释、概念辨析、案例分析与备考建议——"
                        + "阁下有法学之惑，直接发问便是，老朽自当接得住。";
        log.info("法学助手固定开场（sessionId={}）", session.getId());
        runQuietly("开场白落库", () -> saveGreeting(session.getId(), greeting));
        // 开场白由 Host 说出（存默认通道），不预置法学活跃帧——首轮路由仍由 Host function calling 决定，
        // 避免「幻影 law 帧」在首轮直答时被压栈悬挂。
        return Flux.just(
                new StreamEvent(StreamEvent.CHUNK, greeting),
                new StreamEvent(StreamEvent.DONE, null));
    }

    /** Host 直答收口：落库 + 机会观察（共创推演与定稿装配已内聚 CoCreationService） */
    private void finishHostTurn(IdeaSessionEntity session, String reply, String roundId,
                                String channel, int turnEpoch) {
        runQuietly("消息入库", () -> saveAssistantRound(session.getId(), channel, reply, roundId));
        log.info("本轮收口（sessionId={}，回复 {} 字）", session.getId(),
                reply == null ? 0 : reply.length());
        // 观察器是 Host 侧旁路：只评估 host 帧流量（领域轮不进此分支，领域上下文也不进评估）
        proposalService.observeAsync(session,
                contextManager.loadChannelFrame(session.getId(), CHANNEL_HOST), reply, turnEpoch);
    }

    private void saveGreeting(Long sessionId, String reply) {
        if (reply == null || reply.isBlank()) {
            return;
        }
        messageRepository.save(new IdeaMessageEntity(sessionId, ROLE_ASSISTANT, reply,
                ChannelRegistry.DEFAULT_CHANNEL, null));
    }

    private void persistUserTurn(Long sessionId, String channel, String userText, String clientRoundId) {
        if (userText == null || userText.isBlank()) {
            return;
        }
        if (clientRoundId != null && messageRepository.existsBySessionIdAndClientRoundIdAndRole(
                sessionId, clientRoundId, ROLE_USER)) {
            log.info("重复轮次 user 行已存在，不重复落库（sessionId={}，clientRoundId={}）",
                    sessionId, clientRoundId);
            return;
        }
        messageRepository.save(new IdeaMessageEntity(sessionId, ROLE_USER, userText, channel, clientRoundId));
        proposalService.archiveSilentProposals(sessionId);
    }

    private void saveAssistantRound(Long sessionId, String channel, String assistantReply, String clientRoundId) {
        if (clientRoundId != null && messageRepository.existsBySessionIdAndClientRoundIdAndRole(
                sessionId, clientRoundId, ROLE_ASSISTANT)) {
            log.info("重复轮次拦截（sessionId={}，clientRoundId={}）", sessionId, clientRoundId);
            return;
        }
        if (assistantReply != null && !assistantReply.isBlank()) {
            messageRepository.save(new IdeaMessageEntity(
                    sessionId, ROLE_ASSISTANT, assistantReply, channel, clientRoundId));
        }
    }

}
