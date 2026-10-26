package com.mengx.server.idea.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mengx.server.idea.DiscoveryEpochGuard;
import com.mengx.server.idea.DiscoveryService;
import com.mengx.server.idea.IdeaCardDto;
import com.mengx.server.idea.IdeaCardEntity;
import com.mengx.server.idea.IdeaCardRepository;
import com.mengx.server.idea.IdeaChatService;
import com.mengx.server.idea.IdeaChatService.InMessage;
import com.mengx.server.idea.IdeaChatService.StreamEvent;
import com.mengx.server.idea.IdeaMessageEntity;
import com.mengx.server.idea.IdeaMessageRepository;
import com.mengx.server.idea.IdeaProposalService;
import com.mengx.server.idea.IdeaSessionEntity;
import com.mengx.server.idea.IdeaSessionRepository;
import com.mengx.server.idea.orchestrator.worker.HostWorker;
import com.mengx.server.idea.orchestrator.worker.CardComposerWorker;
import com.mengx.server.llm.LlmUsage;
import com.mengx.server.routing.ChannelRegistry;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jakarta.annotation.PreDestroy;
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
 * <p>分帧隔离：每个通道（host/law/…）是独立帧，历史按 producer_channel 分表存储；
 * 切换通道时旧帧挂起压栈（FrameStackManager），切回时按通道恢复历史。
 * 通道切换由 Host LLM 调用对应领域工具时触发（onComplete 回调中 activate）。
 *
 * <p>Worker 池由 ChatWorkerRegistry 管理，功能卡装配器（CardComposerWorker）仍走 Worker 流转。
 */
@Service
public class ChatOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ChatOrchestrator.class);

    private static final String CHANNEL_HOST = "host";
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ROLE_CARD = "card";

    private static final int RECENT_WINDOW = 10;
    private static final String NOT_CONFIGURED_MSG = HostWorker.NOT_CONFIGURED_MSG;

    private final ObjectProvider<StreamingChatModel> streamingModelProvider;
    private final IdeaCardRepository cardRepository;
    private final IdeaMessageRepository messageRepository;
    private final IdeaSessionRepository sessionRepository;
    private final DiscoveryService discoveryService;
    private final IdeaProposalService proposalService;
    private final DiscoveryEpochGuard epochGuard;
    private final ObjectMapper objectMapper;
    private final ChatWorkerRegistry workerRegistry;
    private final ContextManager contextManager;
    private final OutputWrapper outputWrapper;
    private final FrameStackManager frameStackManager;
    private final HostWorker hostWorker;

    public ChatOrchestrator(
            ObjectProvider<StreamingChatModel> streamingModelProvider,
            IdeaCardRepository cardRepository,
            IdeaMessageRepository messageRepository,
            IdeaSessionRepository sessionRepository,
            DiscoveryService discoveryService,
            IdeaProposalService proposalService,
            DiscoveryEpochGuard epochGuard,
            ObjectMapper objectMapper,
            ChatWorkerRegistry workerRegistry,
            ContextManager contextManager,
            OutputWrapper outputWrapper,
            FrameStackManager frameStackManager,
            HostWorker hostWorker) {
        this.streamingModelProvider = streamingModelProvider;
        this.cardRepository = cardRepository;
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.discoveryService = discoveryService;
        this.proposalService = proposalService;
        this.epochGuard = epochGuard;
        this.objectMapper = objectMapper;
        this.workerRegistry = workerRegistry;
        this.contextManager = contextManager;
        this.outputWrapper = outputWrapper;
        this.frameStackManager = frameStackManager;
        this.hostWorker = hostWorker;
    }

    /** 功能卡装配专用池：宣布定稿后的异步装配为秒级阻塞调用 */
    private final ExecutorService composerPool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "card-composer");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    void shutdownPools() {
        composerPool.shutdownNow();
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
                        // Host 直答：落库 + 共创观察 + 卡片装配
                        finishHostTurn(sink, session, effectiveHistory, replyText, roundId, channel, turnEpoch);
                    } else {
                        // 领域工具通道（不感知具体领域，新领域零改动接入）：
                        // user 轮已由 persistUserTurn 统一落库，这里只补 assistant 行；
                        // 关流由领域工具自管（DomainTool 契约：必须自行发 done/error 并 complete）
                        runQuietly("领域消息入库",
                                () -> saveAssistantRound(sessionId, channel, replyText, null, roundId));
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

            // 统一走 Host：由 LLM function calling 决定直答或调用领域工具
            String briefing = session.isDiscoveryActive()
                    ? prepareDiscoveryTurn(sink, session, effectiveHistory) : "";
            // 用户显式选择的领域通道（前端 mode，如点「法学助手」按钮发送）作为路由强提示注入，
            // 不再静默丢弃——Host 仍可按语境否决（如闲聊寒暄），但领域问题应优先命中对应工具
            if (mode != null && !mode.isBlank() && !CHANNEL_HOST.equals(mode)) {
                briefing = (briefing == null ? "" : briefing)
                        + "\n（路由提示：用户本轮显式选择了 " + mode
                        + " 领域，若无明确冲突请优先调用该领域的工具。）";
            }
            hostWorker.stream(session, effectiveHistory, latestUser, briefing, sink, callback);
        }, FluxSink.OverflowStrategy.BUFFER)
                .subscribeOn(Schedulers.boundedElastic());
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

    private String prepareDiscoveryTurn(FluxSink<StreamEvent> sink, IdeaSessionEntity session,
                                        List<InMessage> history) {
        sink.next(thinking("共创推演中，对照快照梳理这一轮…"));
        String state = null;
        try {
            String context = buildDiscoveryContext(history, null);
            String proposalBrief = proposalService.undigestedProposalContext(session.getId());
            if (proposalBrief != null) {
                context = proposalBrief + "\n\n" + context;
            }
            state = discoveryService.composeAndArchive(session.getId(), context);
        } catch (Exception e) {
            log.warn("共创推演异常（主对话代理自行作答）：{}", e.getMessage());
        }
        if (state == null) {
            return "";
        }
        sink.next(new StreamEvent(StreamEvent.DESIGN, state));
        return DISCOVERY_STYLE + "\n" + discoveryService.renderForHost(state);
    }

    private static final String DISCOVERY_STYLE = """


            【共创访谈环节】当前处于共创访谈环节，本环节彻底切换为专业顾问风格：不用"老朽"自称（直接说"我"或不自称）、不用古文雅字、不提仙侠修行意象；直接、清晰、专业，每句话都要推进认识。职责边界：接住客人本轮发言；转述设计简报中的建议问法与建议方案（用自己的口吻，不是逐字念）；简报给了选项就把选项自然说给客人挑，客人也可以不选、自己说；简报判定客人某个新要求不合理时，如实转述判定、原因与替代方案，请客人表态，不替他决定；需要澄清时可以就当前焦点追问细节；客人确认某一处后，先用一句话收格确认（"已记下"），随即在同一句回复里抛出下一个问题——确认与推进必须在同一轮完成。不许自己开拓新维度（议程归推演）、不许替客人设计方案（建议方案归推演）、不许大段复述规格快照原文。""";

    private void finishHostTurn(FluxSink<StreamEvent> sink, IdeaSessionEntity session,
                                List<InMessage> history, String reply, String roundId, String channel,
                                int turnEpoch) {
        final boolean wasDiscovery = session.isDiscoveryActive();
        final boolean closingConfirmed = wasDiscovery
                && discoveryService.isClosingConfirmed(latestDiscoveryState(session.getId()));
        if (closingConfirmed) {
            runQuietly("发现模式状态保存", () -> {
                session.setDiscoveryActive(false);
                sessionRepository.save(session);
            });
            composeCardAsync(session, history, reply, sink);
        }
        runQuietly("消息入库", () -> saveAssistantRound(session.getId(), channel, reply, null, roundId));
        log.info("本轮收口（sessionId={}，回复 {} 字，{}）", session.getId(),
                reply == null ? 0 : reply.length(),
                wasDiscovery ? (closingConfirmed ? "客人点头定稿，异步装配功能卡" : "发现模式继续") : "常规对答");
        if (!closingConfirmed) {
            proposalService.observeAsync(session, history, reply, turnEpoch);
        }
    }

    private String buildDiscoveryContext(List<InMessage> history, String hostReply) {
        StringBuilder sb = new StringBuilder();
        List<InMessage> window = history.size() > RECENT_WINDOW
                ? history.subList(history.size() - RECENT_WINDOW, history.size())
                : history;
        for (InMessage m : window) {
            if (ROLE_USER.equals(m.role())) {
                sb.append("客人：").append(m.content()).append('\n');
            } else if (ROLE_ASSISTANT.equals(m.role())) {
                sb.append("主持：").append(m.content()).append('\n');
            }
        }
        if (hostReply != null && !hostReply.isBlank()) {
            sb.append("主持：").append(hostReply).append('\n');
        }
        return sb.toString().trim();
    }

    private String latestDiscoveryState(Long sessionId) {
        List<IdeaMessageEntity> list = messageRepository.findBySessionIdAndRoleOrderByIdAsc(sessionId, "counsel");
        return list.isEmpty() ? null : list.get(list.size() - 1).getContent();
    }

    private void composeCardAsync(IdeaSessionEntity session, List<InMessage> history,
                                  String hostReply, FluxSink<StreamEvent> sink) {
        Long sessionId = session.getId();
        try {
            composerPool.submit(() -> composeCard(sessionId, session.getNickname(), history, hostReply, sink));
        } catch (Exception e) {
            log.warn("功能卡装配任务提交失败（sessionId={}）：{}", sessionId, e.getMessage());
        }
    }

    private void composeCard(Long sessionId, String nickname, List<InMessage> history,
                             String hostReply, FluxSink<StreamEvent> sink) {
        ChatWorker composer = lookupWorker(CardComposerWorker.NAME);
        if (composer == null) {
            log.warn("卡片装配器 Worker 未就绪，跳过装配（sessionId={}）", sessionId);
            return;
        }
        // 装配器模型调用经 CardComposerWorker 流转，数据准备（简报/优先级/对话背景）由 Worker 内部构建
        WorkerContext ctx = new WorkerContext(sessionId, null, nickname, null, hostReply,
                contextManager.toChatTurns(history));
        try {
            LlmUsage.set("extractor");
            try {
                for (int attempt = 0; attempt < 2; attempt++) {
                    if (attempt > 0) {
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    String reply;
                    try {
                        WorkerResult result = composer.run(ctx);
                        reply = (String) result.payload();
                    } catch (RuntimeException e) {
                        log.warn("装配器模型调用失败（第 {} 次）：{}", attempt + 1, e.getMessage());
                        continue;
                    }
                    if (reply == null || reply.isBlank()) {
                        log.warn("装配器空输出（第 {} 次）", attempt + 1);
                        continue;
                    }
                    IdeaCardEntity card;
                    try {
                        card = parseCard(reply, sessionId, nickname);
                    } catch (UnparseableCardException e) {
                        log.warn("装配器输出无法识别（第 {} 次），退避后重试：{}",
                                attempt + 1, truncate(reply, 80));
                        continue;
                    }
                    if (card == null) {
                        return;
                    }
                    card.bindProposal(proposalService.acceptedProposalId(sessionId));
                    IdeaCardEntity saved = cardRepository.save(card);
                    messageRepository.save(new IdeaMessageEntity(
                            sessionId, ROLE_CARD, String.valueOf(saved.getId()),
                            ChannelRegistry.DEFAULT_CHANNEL, null));
                    log.info("功能卡装配完成《{}》（sessionId={}，cardId={}）",
                            saved.getTitle(), sessionId, saved.getId());
                    try {
                        sink.next(new StreamEvent(StreamEvent.CARD, toJson(saved)));
                    } catch (Exception ignore) {
                    }
                    return;
                }
                log.warn("装配器两次均失败或无法识别，本轮放弃装配（sessionId={}）", sessionId);
            } finally {
                LlmUsage.clear();
            }
        } catch (Exception e) {
            log.warn("功能卡装配异常（sessionId={}）：{}", sessionId, e.getMessage());
        }
    }

    private static final class UnparseableCardException extends RuntimeException {
        UnparseableCardException() {}

        UnparseableCardException(Throwable cause) {
            super(cause);
        }
    }

    private IdeaCardEntity parseCard(String reply, Long sessionId, String nickname) {
        String text = IdeaProposalService.stripFences(reply.trim());
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new UnparseableCardException();
        }
        String json = text.substring(start, end + 1);
        // Schema 校验：必要字段缺失直接判不可解析，触发重试
        if (!outputWrapper.validateSchema(json, "card")) {
            log.info("功能卡 Schema 校验未通过，将重试（sessionId={}）", sessionId);
            throw new UnparseableCardException();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            String title = truncate(node.path("title").asText("").trim(), 100);
            if (title.isEmpty()) {
                log.info("装配器输出 title 为空，放弃装配（sessionId={}）", sessionId);
                return null;
            }
            if (cardRepository.countBySessionId(sessionId) >= IdeaChatService.MAX_CARDS_PER_SESSION) {
                log.info("本场卡数已到上限（{}），不再装配（sessionId={}）",
                        IdeaChatService.MAX_CARDS_PER_SESSION, sessionId);
                return null;
            }
            String summary = node.path("summary").asText("").trim();
            IdeaCardEntity card = new IdeaCardEntity(
                    sessionId,
                    nickname,
                    title,
                    summary.isEmpty() ? "待聊" : truncate(summary, 500),
                    clamp(node.path("creativity").asInt(3)));
            ObjectNode design = objectMapper.createObjectNode();
            design.set("functions", textArray(node.path("functions")));
            design.put("effects", designField(node, "effects"));
            design.put("approach", designField(node, "approach"));
            design.set("dimensions", passThroughArray(node.path("dimensions")));
            design.put("constraints", designField(node, "constraints"));
            design.put("feasibilityNotes", node.path("feasibilityNotes").asText("").trim());
            design.set("keyItems", passThroughArray(node.path("keyItems")));
            card.applyDesign(truncate(node.path("priority").asText("中"), 10),
                    objectMapper.writeValueAsString(design));
            return card;
        } catch (Exception e) {
            log.warn("功能卡 JSON 解析失败（将重试）：{}", e.getMessage());
            throw new UnparseableCardException(e);
        }
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

    private void saveAssistantRound(Long sessionId, String channel, String assistantReply,
                                    Long cardId, String clientRoundId) {
        if (clientRoundId != null && messageRepository.existsBySessionIdAndClientRoundIdAndRole(
                sessionId, clientRoundId, ROLE_ASSISTANT)) {
            log.info("重复轮次拦截（sessionId={}，clientRoundId={}）", sessionId, clientRoundId);
            return;
        }
        List<IdeaMessageEntity> rows = new ArrayList<>(2);
        if (assistantReply != null && !assistantReply.isBlank()) {
            rows.add(new IdeaMessageEntity(sessionId, ROLE_ASSISTANT, assistantReply, channel, clientRoundId));
        }
        if (cardId != null) {
            rows.add(new IdeaMessageEntity(sessionId, ROLE_CARD, String.valueOf(cardId), channel, clientRoundId));
        }
        messageRepository.saveAll(rows);
    }

    private ArrayNode textArray(JsonNode node) {
        ArrayNode arr = objectMapper.createArrayNode();
        if (node.isArray()) {
            node.forEach(item -> {
                String v = item.asText("").trim();
                if (!v.isEmpty()) {
                    arr.add(truncate(v, 300));
                }
            });
        } else if (!node.isMissingNode() && !node.asText("").trim().isEmpty()) {
            arr.add(truncate(node.asText("").trim(), 300));
        }
        if (arr.isEmpty()) {
            arr.add("待聊");
        }
        return arr;
    }

    private static String designField(JsonNode node, String name) {
        String v = node.path(name).asText("").trim();
        return v.isEmpty() ? "待聊" : truncate(v, 500);
    }

    private ArrayNode passThroughArray(JsonNode node) {
        ArrayNode arr = objectMapper.createArrayNode();
        if (node.isArray()) {
            node.forEach(item -> {
                if (item.isObject()) {
                    arr.add(item);
                }
            });
        }
        return arr;
    }

    private static int clamp(int value) {
        return Math.max(1, Math.min(5, value));
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private String toJson(IdeaCardEntity card) {
        try {
            return objectMapper.writeValueAsString(IdeaCardDto.from(card));
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 从注册表取 Worker */
    private ChatWorker lookupWorker(String channel) {
        return workerRegistry.get(channel);
    }
}
