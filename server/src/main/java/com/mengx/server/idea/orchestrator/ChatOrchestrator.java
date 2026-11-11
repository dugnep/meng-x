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
 * <p>路由（V3 强制派发）：访谈热窗内或用户显式选择共创时，编排器不再依赖 Host FC 服从率
 * （session 165 实测短答案轮 3/6 逃逸），服务端直接派发共创推演；推演判定用户本轮
 * 与访谈无关（divert.escape）时放行 Host 直答（逃生口，判不准默认留在访谈）。
 * 领域内推演、移交装配全部内聚各领域服务，编排器只做通用收口。
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
    private final CoCreationService coCreationService;

    public ChatOrchestrator(
            ObjectProvider<StreamingChatModel> streamingModelProvider,
            IdeaMessageRepository messageRepository,
            IdeaSessionRepository sessionRepository,
            DiscoveryService discoveryService,
            IdeaProposalService proposalService,
            DiscoveryEpochGuard epochGuard,
            ContextManager contextManager,
            FrameStackManager frameStackManager,
            HostWorker hostWorker,
            CoCreationService coCreationService) {
        this.streamingModelProvider = streamingModelProvider;
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.discoveryService = discoveryService;
        this.proposalService = proposalService;
        this.epochGuard = epochGuard;
        this.contextManager = contextManager;
        this.frameStackManager = frameStackManager;
        this.hostWorker = hostWorker;
        this.coCreationService = coCreationService;
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
            // 例外（V3 强制派发）：访谈热窗内或用户显式选择共创时，不再依赖 Host FC 服从率
            // （session 165 实测短答案轮 3/6 逃逸）——服务端直接派发共创推演；
            // 推演判定用户本轮是与访谈无关的独立请求（divert.escape）时放行 Host 直答（逃生口）。
            boolean explicitOtherDomain = mode != null && !mode.isBlank()
                    && !CHANNEL_HOST.equals(mode) && !CoCreationService.CHANNEL.equals(mode);
            boolean interviewHot = !explicitOtherDomain && discoveryService.interviewHot(sessionId);
            boolean forceCoCreation = interviewHot
                    || (CoCreationService.CHANNEL.equals(mode)
                        && coCreationService.licensedToEnter(sessionId));
            if (forceCoCreation) {
                CoCreationService.PreflightResult pre =
                        coCreationService.preflight(session, effectiveHistory, latestUser, sink);
                if (!pre.ready()) {
                    return; // 模型未配置：错误事件已发、sink 已关
                }
                if (pre.state() != null && discoveryService.isDivertEscape(pre.state())) {
                    log.info("访谈暂挂（sessionId={}，用户本轮输入与访谈无关）：{}",
                            sessionId, discoveryService.divertReason(pre.state()));
                    hostWorker.stream(session, effectiveHistory, latestUser,
                            divertHint(discoveryService.divertReason(pre.state())), sink, callback);
                    return;
                }
                try {
                    String reply = coCreationService.interviewRound(
                            session, effectiveHistory, latestUser, sink, pre.state());
                    callback.onComplete(CoCreationService.CHANNEL, reply);
                } catch (Exception e) {
                    callback.onError(e);
                }
                return;
            }
            hostWorker.stream(session, effectiveHistory, latestUser,
                    routeHint(mode), sink, callback);
        }, FluxSink.OverflowStrategy.BUFFER)
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 路由提示（注入 Host 系统提示）：
     * ① 显式 mode（用户点领域按钮）——优先该领域；
     * ② 访谈热窗不再走提示注入——V3 起由编排器强制派发（{@code forceCoCreation}），
     *    逃生口为推演官 divert 判定（判不准默认留在访谈）；
     * ③ 其余——自由 function calling（无提示）。
     */
    private String routeHint(String mode) {
        StringBuilder hint = new StringBuilder();
        if (mode != null && !mode.isBlank() && !CHANNEL_HOST.equals(mode)) {
            hint.append("\n（路由提示：用户本轮显式选择了 ").append(mode)
                    .append(" 领域，若无明确冲突请优先调用该领域的工具。）");
        }
        return hint.toString();
    }

    /** divert 逃生提示：访谈暂挂原因 + 直答纪律（不回访谈、不梳理方案，回应后带一句回归话头） */
    private String divertHint(String reason) {
        return "\n（内部提示：用户本轮输入经推演判定与本场需求共创访谈无关"
                + (reason == null || reason.isBlank() ? "" : "——" + reason.trim())
                + "，访谈已暂挂：请直接正常回应这个请求，不要调用 co_creation，也不要自行梳理方案；"
                + "回应之后可自然带一句「想继续聊之前那个方案的话随时说」。）";
    }

    private Flux<StreamEvent> greetingFlux(IdeaSessionEntity session) {
        String greeting =
                "法学助手已就位：法条检索、案例解答、法考刷题与主观题批改，直接问即可。";
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
