package com.mengx.server.idea;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mengx.server.idea.agent.IdeaAgentFactory;
import com.mengx.server.law.LawAgentService;
import com.mengx.server.llm.LlmUsage;
import com.mengx.server.routing.ChannelRegistry;
import com.mengx.server.routing.RoutingService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

/**
 * 主对话代理（host，叙事名：仙官百晓仙君）服务：orchestrator 模式，仙官是唯一前台。
 * 流式作答（chunk）；回答收口即落库并立即发 done 解锁前端输入——
 * 机会观察（PROPOSE/NONE/EXIT）与功能卡装配全部移出关键路径，在旁路线程池异步执行：
 * 判定 PROPOSE 落应用提案（idea_proposals），前端经 notices 轮询展示邀请卡，用户许可后才进入发现模式；
 * 定稿为状态驱动（推演官 closingConfirmed：收口条件满足且客人点头）后异步装配功能卡，晚到靠轮询补气泡。
 * 发现模式改为同步共创：用户发言当轮由共创推演官推演（普通轮约十余秒、纯确认轮数秒），
 * 快照当轮落库并随 design 事件当轮下发（营造图面板当轮点亮），结论经 briefing 注入主对话代理。
 */
@Service
public class IdeaChatService {

    private static final Logger log = LoggerFactory.getLogger(IdeaChatService.class);

    public record InMessage(String role, String content) {}

    /** SSE 流事件类型（前后端约定，改动需同步 web/src/api.js 注释与 Chat.jsx 分支） */
    public record StreamEvent(String type, String content) {
        /** 阶段过程提示（前端以可展开面板展示） */
        public static final String THINKING = "thinking";
        /** 增量文本 */
        public static final String CHUNK = "chunk";
        /** 功能卡 DTO JSON */
        public static final String CARD = "card";
        /** 法考题 DTO JSON */
        public static final String QUESTION = "question";
        /** 设计简报存档原文（JSON 信封；协议键不可改） */
        public static final String DESIGN = "design";
        /** 法条引用徽章 JSON */
        public static final String CITATIONS = "citations";
        /** 本轮结束 */
        public static final String DONE = "done";
        /** 可读错误提示（前端附在气泡尾部） */
        public static final String ERROR = "error";
    }

    /** 会话通道：法学助手 / 主对话（host 与 ChannelRegistry.DEFAULT_CHANNEL 保持同值） */
    private static final String CHANNEL_LAW = "law";
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ROLE_CARD = "card";

    /** 单场对话卡片数上限，防灌水（设计方案 §9）；提案服务闸门共用 */
    static final int MAX_CARDS_PER_SESSION = 5;
    /** 点子收藏架注入 system prompt 的条数上限，防 prompt 过长 */
    private static final int SHELF_MAX = 15;
    /** 需求澄清推演回看的最近对话条数 */
    private static final int RECENT_WINDOW = 10;
    /** 意图路由 L2 细判携带的最近对话背景条数（约三轮，不含本轮消息） */
    private static final int ROUTE_CONTEXT_WINDOW = 6;

    private static final String NOT_CONFIGURED_MSG = "LLM 未配置，请设置 MENGX_LLM_API_KEY 或启用 local profile";

    private final ObjectProvider<StreamingChatModel> streamingModelProvider;
    private final IdeaAgentFactory ideaAgents;
    private final IdeaCardRepository cardRepository;
    private final IdeaMessageRepository messageRepository;
    private final IdeaSessionRepository sessionRepository;
    private final DiscoveryService discoveryService;
    private final IdeaProposalService proposalService;
    private final DiscoveryEpochGuard epochGuard;
    private final LawAgentService lawAgent;
    private final com.mengx.server.law.LawSearchService lawSearch;
    private final com.mengx.server.law.LawProfileService lawProfile;
    private final LawQuizFlowService lawQuizFlow;
    private final RoutingService routingService;
    private final ObjectMapper objectMapper;

    public IdeaChatService(
            ObjectProvider<StreamingChatModel> streamingModelProvider,
            IdeaAgentFactory ideaAgents,
            IdeaCardRepository cardRepository,
            IdeaMessageRepository messageRepository,
            IdeaSessionRepository sessionRepository,
            DiscoveryService discoveryService,
            IdeaProposalService proposalService,
            DiscoveryEpochGuard epochGuard,
            LawAgentService lawAgent,
            com.mengx.server.law.LawSearchService lawSearch,
            com.mengx.server.law.LawProfileService lawProfile,
            LawQuizFlowService lawQuizFlow,
            RoutingService routingService,
            ObjectMapper objectMapper) {
        this.streamingModelProvider = streamingModelProvider;
        this.ideaAgents = ideaAgents;
        this.cardRepository = cardRepository;
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.discoveryService = discoveryService;
        this.proposalService = proposalService;
        this.epochGuard = epochGuard;
        this.lawAgent = lawAgent;
        this.lawSearch = lawSearch;
        this.lawProfile = lawProfile;
        this.lawQuizFlow = lawQuizFlow;
        this.routingService = routingService;
        this.objectMapper = objectMapper;
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

    /** 吞掉不影响主流程的副作用异常，统一日志口径 */
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


    /** 本场已定稿卡片清单：让主对话代理知道这位客人今天出过什么卡，防止同一话题反复推销、重复出卡 */
    private String buildSessionCards(IdeaSessionEntity session) {
        List<IdeaCardEntity> cards = cardRepository.findBySessionIdOrderByIdDesc(session.getId());
        if (cards.isEmpty()) {
            return "（本场还没定稿过卡，聊透一个新点子再定）";
        }
        StringBuilder sb = new StringBuilder();
        for (IdeaCardEntity card : cards) {
            sb.append("《").append(card.getTitle()).append("》（").append(statusLabel(card.getStatus())).append("）\n");
        }
        return sb.toString().trim();
    }

    /** 点子收藏架：已收录的卡片动态注入 system prompt，让主对话代理有跨会话记忆（不编造、防重复） */
    private String buildShelf() {
        List<IdeaCardEntity> cards = cardRepository.findTop15ByOrderByCreativityDescCreatedAtDesc();
        if (cards.isEmpty()) {
            return "（还没有人留下点子，说不定今天开张头一位就是你们）";
        }
        StringBuilder shelf = new StringBuilder();
        for (IdeaCardEntity card : cards) {
            shelf.append("《").append(card.getTitle()).append("》（")
                    .append(statusLabel(card.getStatus()));
            if (card.getPriority() != null) {
                shelf.append("，优先级").append(card.getPriority());
            }
            if (IdeaCardEntity.PRD_DONE.equals(card.getPrdStatus())) {
                shelf.append("，PRD已出稿");
            }
            shelf.append("，").append(card.getNickname()).append(" 提出）\n");
        }
        long total = cardRepository.count();
        if (total > SHELF_MAX) {
            shelf.append("…以及更早的 ").append(total - SHELF_MAX).append(" 张，就不多占你心神了");
        }
        return shelf.toString().trim();
    }

    private static String statusLabel(String status) {
        return switch (status) {
            case IdeaCardEntity.STATUS_CONFIRMED -> "已确认";
            case IdeaCardEntity.STATUS_IMPLEMENTED -> "已实现";
            default -> "新芽";
        };
    }

    /**
     * 流式访谈。事件类型见 {@link StreamEvent} 常量。
     * 发现模式：用户发言当轮同步共创推演（thinking 事件反馈进度，design 事件当轮下发营造图），
     * 推演结论注入主对话代理后流式作答。收口即落库+发 done 解锁前端输入，
     * 机会观察（应用提案）与功能卡装配全部移出关键路径旁路异步执行。
     * clientRoundId：前端生成的一轮幂等键，重发同一轮不重复落库；null 不做幂等。
     */
    public Flux<StreamEvent> stream(IdeaSessionEntity session, List<InMessage> history,
                                   String mode, boolean greet, String clientRoundId) {
        StreamingChatModel model = streamingModelProvider.getIfAvailable();
        if (model == null) {
            return Flux.just(new StreamEvent(StreamEvent.ERROR, NOT_CONFIGURED_MSG));
        }
        // 出场触发：固定文案零模型调用（仅告知功能存在），落库保留出场记录，通道记为 law（下轮路由粘性）
        if (greet) {
            return greetingFlux(session);
        }
        final String roundId = (clientRoundId == null || clientRoundId.isBlank())
                ? null : clientRoundId.trim();
        return Flux.<StreamEvent>create(sink -> {
            Long sessionId = session.getId();
            // 用户新一轮到达：代际 +1（供旁路机会观察防过期——旧轮的 EXIT/PROPOSE 结论不该作用于新一轮）
            int turnEpoch = epochGuard.bump(sessionId);
            log.info("主对话代理开讲（sessionId={}，mode={}，discoveryActive={}，history={} 条，epoch={}）",
                    sessionId, session.getMode(), session.isDiscoveryActive(), history.size(), turnEpoch);
            sink.next(thinking("听声辨位，辨认来意…"));
            // 阶段 1：通道裁决（路由含 L2 模型细判，在后台订阅线程执行，既覆盖等待期反馈盲区也不占 Servlet 线程）
            String latestUser = latestUserText(history);
            boolean lawMode = resolveLawMode(mode, session, latestUser, history);
            persistChannel(session, lawMode);
            // 阶段 2：法学子意图分流（出题/答题/学情）。契约：handle 返回 true 即接管整轮，必须自行 sink.complete()
            if (lawMode && lawQuizFlow.handle(sink, session, history)) {
                return;
            }
            // 阶段 3：按通道组装本轮入参（法学检索 / 设计简报 / 主对话本色）；
            // lawMessages 为 null 表示法学检索失败事件已发，直接收口。
            // 主对话通道的对话窗口直读 idea_messages，本轮 user 必须先于模型调用落库，
            // 框架随后取窗口时本轮发言才在表里；法学/刷题通道收口时自行整轮落库，不在此提前写。
            List<ChatMessage> lawMessages = null;
            String hostBriefing = null;
            if (lawMode) {
                lawMessages = buildLawMessages(sink, session, history, latestUser);
                if (lawMessages == null) {
                    return;
                }
            } else {
                persistUserTurn(session.getId(), latestUser, roundId);
                hostBriefing = session.isDiscoveryActive()
                        ? prepareDiscoveryTurn(sink, session, history) : "";
            }
            // 阶段 4：流式作答 + 按通道收口
            sink.next(thinking(lawMode ? "法学助手凝神酝酿答话…" : "仙君凝神酝酿答话…"));
            streamReply(model, sink, session, history, lawMode, roundId, turnEpoch,
                    lawMessages, hostBriefing, latestUser);
        }, FluxSink.OverflowStrategy.BUFFER)
                // BUFFER 而非 LATEST：文本 token 一个都不能丢。下游 SseEmitter.send 为同步阻塞调用，
                // 客户端读得慢时反压直接作用在模型回调线程上，缓冲不会无界增长。
                // 订阅切后台线程：Flux.create lambda 含阻塞式 LLM 调用，若在 Servlet 线程同步跑完，
                // ResponseBodyEmitter 未 initialize，所有 send 只会攒进 earlySendAttempts，initialize 后一次性 replay
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 法学助手固定开场白（零模型调用），落库并把通道粘在 law */
    private Flux<StreamEvent> greetingFlux(IdeaSessionEntity session) {
        String greeting =
                "法学助手已候命。这厢专司法条解释、概念辨析、案例分析与备考建议——"
                + "阁下有法学之惑，直接发问便是，老朽自当接得住。";
        log.info("法学助手固定开场（sessionId={}）", session.getId());
        runQuietly("开场白落库", () -> {
            saveGreeting(session.getId(), greeting);
            if (!CHANNEL_LAW.equals(session.getMode())) {
                session.setMode(CHANNEL_LAW);
                sessionRepository.save(session);
            }
        });
        return Flux.just(
                new StreamEvent(StreamEvent.CHUNK, greeting),
                new StreamEvent(StreamEvent.DONE, null));
    }

    /**
     * 通道裁决：显式 law 强制法学；空值走意图路由（带上一通道粘性）；显式 host/其他值留主对话。
     * 入参做 trim+小写归一，避免 " law"/"Law" 漏判。
     */
    private boolean resolveLawMode(String mode, IdeaSessionEntity session,
                                   String latestUser, List<InMessage> history) {
        String normalized = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        if (CHANNEL_LAW.equals(normalized)) {
            return true;
        }
        if (!normalized.isEmpty()) {
            return false;
        }
        String lastChannel = CHANNEL_LAW.equals(session.getMode())
                ? CHANNEL_LAW : ChannelRegistry.DEFAULT_CHANNEL;
        return CHANNEL_LAW.equals(
                routingService.route(latestUser, lastChannel, buildRouteContext(history)));
    }

    /** 通道变化落会话（同时作为下一轮意图路由的粘性参考） */
    private void persistChannel(IdeaSessionEntity session, boolean lawMode) {
        if (lawMode != CHANNEL_LAW.equals(session.getMode())) {
            session.setMode(lawMode ? CHANNEL_LAW : ChannelRegistry.DEFAULT_CHANNEL);
            runQuietly("通道切换保存", () -> sessionRepository.save(session));
        }
    }

    /**
     * 法学通道消息组装：三路法条检索 + 备考意图下真题召回，引用徽章先于正文下发，检索上下文注入法学人设。
     * 检索（外部 DB/HTTP）失败时发可读 error 事件并关流，返回 null（不冒泡为连接级错误）。
     */
    private List<ChatMessage> buildLawMessages(FluxSink<StreamEvent> sink, IdeaSessionEntity session,
                                               List<InMessage> history, String latestUser) {
        log.info("法学通道作答（sessionId={}）", session.getId());
        boolean examIntent = LawQuizFlowService.isExamIntent(latestUser);
        sink.next(thinking(examIntent
                ? "认出是法学之问，翻阅法典与历年真题…" : "认出是法学之问，翻阅法典…"));
        try {
            long searchStart = System.currentTimeMillis();
            List<com.mengx.server.law.LawSearchService.SearchHit> refs =
                    lawSearch.search(latestUser, lawCodeHint(history));
            List<com.mengx.server.law.LawQuestionEntity> examHits = examIntent
                    ? lawSearch.searchExamQuestions(latestUser, 3) : List.of();
            sink.next(thinking(examIntent
                    ? "检出法条 " + refs.size() + " 条、真题 " + examHits.size() + " 道"
                    : "检出法条 " + refs.size() + " 条"));
            List<com.mengx.server.law.LawSearchService.Citation> citations = new ArrayList<>();
            var fromArticles = com.mengx.server.law.LawSearchService.toCitations(refs);
            if (fromArticles != null) {
                citations.addAll(fromArticles);
            }
            var fromExams = lawSearch.examCitations(examHits);
            if (fromExams != null) {
                citations.addAll(fromExams);
            }
            if (!citations.isEmpty()) {
                sink.next(new StreamEvent(StreamEvent.CITATIONS, toJsonValue(citations)));
            }
            log.info("法学检索收口（sessionId={}，{}，法条 {} 条 + 真题 {} 条，检索总耗时 {}ms）",
                    session.getId(), examIntent ? "备考意图" : "日常咨询", refs.size(), examHits.size(),
                    System.currentTimeMillis() - searchStart);
            return lawAgent.buildMessages(session.getNickname(), history, refs, examHits,
                    lawProfile.chatHint(session.getUserId()));
        } catch (Exception e) {
            log.warn("法学检索失败（sessionId={}）：{}", session.getId(), e.getMessage());
            sink.next(new StreamEvent(StreamEvent.ERROR, "法学助手一时翻不到法典，稍后再问一声便是～"));
            sink.next(new StreamEvent(StreamEvent.DONE, null));
            sink.complete();
            return null;
        }
    }

    /**
     * 需求澄清本轮准备：共创推演官在场同步推演——回灌上一版快照 + 本轮发言，
     * 产出更新后的完整快照并当轮存档、当轮随 design 事件下发（营造图面板当轮点亮）；
     * 推演结论渲染为简报、连同共创环节风格指令一起注入主对话代理 system prompt 尾部。
     * 对话历史不归这里管——已由 LangChain4j ChatMemory 按 sessionId 托管。
     */
    private String prepareDiscoveryTurn(FluxSink<StreamEvent> sink, IdeaSessionEntity session,
                                        List<InMessage> history) {
        sink.next(thinking("共创推演中，对照快照梳理这一轮…"));
        String state = null;
        try {
            String context = buildDiscoveryContext(history, null);
            // 未消化的已许可提案四要素拼进推演背景（accept 时不推演，用户首轮开口一并消化成第一版快照）
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

    /** 共创访谈环节的风格与职责指令：随发现模式 briefing 动态注入，闲聊场景不携带（仙官人设不动） */
    private static final String DISCOVERY_STYLE = """


            【共创访谈环节】当前处于共创访谈环节，本环节彻底切换为专业顾问风格：不用"老朽"自称（直接说"我"或不自称）、不用古文雅字、不提仙侠修行意象；直接、清晰、专业，每句话都要推进认识。职责边界：接住客人本轮发言；转述设计简报中的建议问法与建议方案（用自己的口吻，不是逐字念）；简报给了选项就把选项自然说给客人挑，客人也可以不选、自己说；简报判定客人某个新要求不合理时，如实转述判定、原因与替代方案，请客人表态，不替他决定；需要澄清时可以就当前焦点追问细节；客人确认某一处后，先用一句话收格确认（"已记下"），随即在同一句回复里抛出下一个问题——确认与推进必须在同一轮完成。不许自己开拓新维度（议程归推演）、不许替客人设计方案（建议方案归推演）、不许大段复述规格快照原文。""";

    /** 流式生成：首 token 埋点、逐字 chunk、按通道收口；失败时半截回答留痕。
     * 法学通道沿用裸模型调用（messages 自带法法人设 system）；主对话通道走 HostAgent
     * （人设/设计简报归接口注入，历史与本轮发言由框架经 @MemoryId 托管读写） */
    private void streamReply(StreamingChatModel model, FluxSink<StreamEvent> sink, IdeaSessionEntity session,
                             List<InMessage> history, boolean lawMode, String roundId,
                             int turnEpoch, List<ChatMessage> lawMessages, String hostBriefing,
                             String latestUser) {
        final long llmStart = System.currentTimeMillis();
        final AtomicBoolean firstToken = new AtomicBoolean(true);
        final StringBuilder partial = new StringBuilder();
        StreamingChatResponseHandler handler = new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
                if (firstToken.compareAndSet(true, false)) {
                    log.info("LLM 首 token 延迟 {}ms（sessionId={}，{}通道）",
                            System.currentTimeMillis() - llmStart, session.getId(),
                            lawMode ? "法学" : "主对话");
                }
                partial.append(token);
                sink.next(new StreamEvent(StreamEvent.CHUNK, token));
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                String reply = completeResponse.aiMessage().text();
                if (reply == null || reply.isBlank()) {
                    handleEmptyReply(sink, session, history, lawMode, llmStart);
                    return;
                }
                if (lawMode) {
                    finishLawTurn(sink, session, history, reply, roundId, llmStart);
                } else {
                    finishHostTurn(sink, session, history, reply, roundId, turnEpoch, llmStart);
                }
            }

            @Override
            public void onError(Throwable error) {
                log.warn("主对话代理模型调用失败（sessionId={}）", session.getId(), error);
                // 已吐出的半截回答留痕（不带幂等键：允许客人原样重发，不被幂等拦截）。
                // 法学通道整轮都没落库，补 user+assistant；主对话通道本轮 user 已提前落库，只补半截 assistant
                if (partial.length() > 0) {
                    if (lawMode) {
                        runQuietly("半截回答落库",
                                () -> saveRound(session.getId(), history, partial.toString(), null, null));
                    } else {
                        runQuietly("半截回答留痕", () -> messageRepository.save(
                                new IdeaMessageEntity(session.getId(), ROLE_ASSISTANT, partial.toString())));
                    }
                }
                sink.next(new StreamEvent(StreamEvent.ERROR, "仙官暂时开小差了，稍后再试试～"));
                sink.next(new StreamEvent(StreamEvent.DONE, null));
                sink.complete();
            }
        };
        if (lawMode) {
            model.chat(lawMessages, handler);
        } else {
            ideaAgents.host().turn(
                            session.getId(), session.getNickname(), buildShelf(),
                            buildSessionCards(session), hostBriefing, latestUser)
                    .onPartialResponse(handler::onPartialResponse)
                    .onCompleteResponse(handler::onCompleteResponse)
                    .onError(handler::onError)
                    .start();
        }
    }

    /** 模型空回复：法学通道整轮尚未落库，补 user 留痕；主对话通道本轮 user 已在调用前落库 */
    private void handleEmptyReply(FluxSink<StreamEvent> sink, IdeaSessionEntity session,
                                  List<InMessage> history, boolean lawMode, long llmStart) {
        log.warn("模型空回复（sessionId={}，{}通道，耗时 {}ms）", session.getId(),
                lawMode ? "法学" : "主对话", System.currentTimeMillis() - llmStart);
        if (lawMode) {
            runQuietly("空回复用户消息留痕",
                    () -> saveRound(session.getId(), history, null, null, null));
        }
        sink.next(new StreamEvent(StreamEvent.ERROR,
                lawMode ? "法学助手一时语塞，换个问法再试一句？" : "仙官一时语塞，换个说法再问一句试试？"));
        sink.next(new StreamEvent(StreamEvent.DONE, null));
        sink.complete();
    }

    /** 法学通道收口：不出卡、不碰需求澄清循环，只持久化本轮消息（对话窗口直读业务表，无需镜像） */
    private void finishLawTurn(FluxSink<StreamEvent> sink, IdeaSessionEntity session,
                               List<InMessage> history, String reply, String roundId, long llmStart) {
        runQuietly("消息入库", () -> saveRound(session.getId(), history, reply, null, roundId));
        log.info("法学模式收口（sessionId={}，回复 {} 字，流式生成总耗时 {}ms）",
                session.getId(), reply.length(), System.currentTimeMillis() - llmStart);
        sink.next(new StreamEvent(StreamEvent.DONE, null));
        sink.complete();
    }

    /** 主对话通道收口：落库+发 done 即解锁前端输入；发现模式流转、卡片装配、机会观察全部旁路异步 */
    private void finishHostTurn(FluxSink<StreamEvent> sink, IdeaSessionEntity session,
                                List<InMessage> history, String reply, String roundId,
                                int turnEpoch, long llmStart) {
        final boolean wasDiscovery = session.isDiscoveryActive();
        // 定稿状态驱动：本轮推演官判定 closingConfirmed（收口条件满足且客人点头）即触发装配，
        // 不再检测主持人话术关键词——话术由简报指令约束，状态由推演官判定，两者解耦
        final boolean closingConfirmed = wasDiscovery
                && discoveryService.isClosingConfirmed(latestDiscoveryState(session.getId()));
        if (closingConfirmed) {
            // 客人点头定稿：退出发现模式，功能卡异步装配（card 占位消息由装配任务补插）
            runQuietly("发现模式状态保存", () -> {
                session.setDiscoveryActive(false);
                sessionRepository.save(session);
            });
            composeCardAsync(session, history, reply, sink);
        }
        // 发现模式继续：无需预取——下一轮发言进来时同步推演
        runQuietly("消息入库", () -> saveAssistantRound(session.getId(), reply, null, roundId));
        log.info("本轮收口（sessionId={}，回复 {} 字，{}，流式生成总耗时 {}ms）", session.getId(),
                reply.length(),
                wasDiscovery ? (closingConfirmed ? "客人点头定稿，异步装配功能卡" : "发现模式继续") : "常规对答",
                System.currentTimeMillis() - llmStart);
        sink.next(new StreamEvent(StreamEvent.DONE, null));
        sink.complete();
        // 旁路机会观察：定稿收口轮不观察（本轮访谈已闭环，无新提案时机）
        if (!closingConfirmed) {
            proposalService.observeAsync(session, history, reply, turnEpoch);
        }
    }

    /** 需求澄清推演官与装配器的讨论背景（最近几轮对话 + 本轮主对话代理回复；规格快照回灌由 DiscoveryService 自行截取处理） */
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

    /** 最近一份需求澄清状态存档原文（JSON 信封；注入/评估经 renderForHost 渲染）；无存档返回 null */
    private String latestDiscoveryState(Long sessionId) {
        List<IdeaMessageEntity> list = messageRepository.findBySessionIdAndRoleOrderByIdAsc(sessionId, "counsel");
        return list.isEmpty() ? null : list.get(list.size() - 1).getContent();
    }

    /** 异步装配功能卡：客人点头定稿后在装配池执行（不阻塞 done 收口），迟到卡片靠轮询补发 */
    private void composeCardAsync(IdeaSessionEntity session, List<InMessage> history,
                                  String hostReply, FluxSink<StreamEvent> sink) {
        Long sessionId = session.getId();
        try {
            composerPool.submit(() -> composeCard(sessionId, session.getNickname(), history, hostReply, sink));
        } catch (Exception e) {
            log.warn("功能卡装配任务提交失败（sessionId={}）：{}", sessionId, e.getMessage());
        }
    }

    /**
     * 装配功能卡（装配池线程）：设计简报渲染 + 优先级建议 → 装配器调用（2 秒退避重试一次）→
     * 过三道闸门后入库 + 补 card 占位消息。业务闸门拒绝（null）不重试；
     * 输出无法识别与模型失败重试一次，两次仍失败放弃（不影响对话，无卡即无占位）。
     */
    private void composeCard(Long sessionId, String nickname, List<InMessage> history,
                             String hostReply, FluxSink<StreamEvent> sink) {
        if (!ideaAgents.available()) {
            return;
        }
        try {
            String state = latestDiscoveryState(sessionId);
            String briefing = (state == null || state.isBlank())
                    ? "（本场无设计简报）" : discoveryService.renderForHost(state);
            String priority = state == null ? null : discoveryService.priorityOf(state);
            String dialog = buildDiscoveryContext(history, hostReply);
            LlmUsage.set("extractor"); // 用量观测：装配器场景（沿用提炼器口径）
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
                        reply = ideaAgents.composer().compose(priority, briefing, dialog);
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
                        return; // 业务闸门拒绝（标题空/卡数上限）：不重试
                    }
                    card.bindProposal(proposalService.acceptedProposalId(sessionId));
                    IdeaCardEntity saved = cardRepository.save(card);
                    messageRepository.save(new IdeaMessageEntity(
                            sessionId, ROLE_CARD, String.valueOf(saved.getId())));
                    log.info("功能卡装配完成《{}》（sessionId={}，cardId={}）",
                            saved.getTitle(), sessionId, saved.getId());
                    try {
                        sink.next(new StreamEvent(StreamEvent.CARD, toJson(saved)));
                    } catch (Exception ignore) {
                        // 流已关闭（前端已刷新/关页）：迟到卡片靠 notices 轮询补发
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

    /** 装配器输出无法解析（非合法功能卡 JSON）：区别于业务闸门拒绝，应退避重试 */
    private static final class UnparseableCardException extends RuntimeException {
        UnparseableCardException() {}

        UnparseableCardException(Throwable cause) {
            super(cause);
        }
    }

    /**
     * 解析装配器输出为功能卡实体（不落库，落库由调用方负责）。
     * 返回 null = 业务闸门拒绝（title 空/本场卡数上限），调用方不重试；
     * 抛 UnparseableCardException = JSON 结构非法，调用方退避重试。
     * 注意：不做"与已有卡重复"拦截——功能卡是创意存档而非已开发功能，
     * 相似点子允许重复创作（迭代、换角度深化），同场多卡并存。
     */
    private IdeaCardEntity parseCard(String reply, Long sessionId, String nickname) {
        String text = IdeaProposalService.stripFences(reply.trim());
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new UnparseableCardException();
        }
        try {
            JsonNode node = objectMapper.readTree(text.substring(start, end + 1));
            String title = truncate(node.path("title").asText("").trim(), 100);
            if (title.isEmpty()) {
                log.info("装配器输出 title 为空，放弃装配（sessionId={}）", sessionId);
                return null;
            }
            if (cardRepository.countBySessionId(sessionId) >= MAX_CARDS_PER_SESSION) {
                log.info("本场卡数已到上限（{}），不再装配（sessionId={}）",
                        MAX_CARDS_PER_SESSION, sessionId);
                return null;
            }
            String summary = node.path("summary").asText("").trim();
            IdeaCardEntity card = new IdeaCardEntity(
                    sessionId,
                    nickname,
                    title,
                    summary.isEmpty() ? "待聊" : truncate(summary, 500),
                    clamp(node.path("creativity").asInt(3)));
            // 设计沉淀（核心三要素 + 动态维度 + 边界兜底 + 关键项留痕）整体存 designJson
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
            // JSON 结构非法：交回 composeCard 退避重试（title 空/卡上限属业务判定，已在上方正常返回 null）
            log.warn("功能卡 JSON 解析失败（将重试）：{}", e.getMessage());
            throw new UnparseableCardException(e);
        }
    }

    /** 路由 L2 细判的对话背景：最近几轮（客人/回答 逐行），不含本轮消息 */
    private String buildRouteContext(List<InMessage> history) {
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, history.size() - 1 - ROUTE_CONTEXT_WINDOW);
        for (int i = from; i < history.size() - 1; i++) {
            InMessage m = history.get(i);
            if (ROLE_USER.equals(m.role())) {
                sb.append("客人：");
            } else if (ROLE_ASSISTANT.equals(m.role())) {
                sb.append("回答：");
            } else {
                continue;
            }
            sb.append(m.content() == null ? "" : m.content()).append('\n');
        }
        return sb.toString().trim();
    }

    /** 意图路由用：取 history 中最后一条 user 消息文本（无或空内容返回空串） */
    private String latestUserText(List<InMessage> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            InMessage m = history.get(i);
            if (ROLE_USER.equals(m.role())) {
                return m.content() == null ? "" : m.content();
            }
        }
        return "";
    }

    // ==================== 法学刷题流已拆至 LawQuizFlowService（idea 包，域内子意图编排） ====================

    /** 编号路指代解析：「那第20条呢」没写法名时，取最近对话中出现过的法名作检索提示 */
    private String lawCodeHint(List<InMessage> history) {
        int from = Math.max(0, history.size() - 8);
        for (int i = history.size() - 1; i >= from; i--) {
            String content = history.get(i).content();
            if (content == null) {
                continue;
            }
            String code = lawSearch.detectLawCode(content);
            if (code != null) {
                return code;
            }
        }
        return null;
    }

    /** 法学助手出场：只存开场白（触发无用户消息，saveRound 会重复存旧消息） */
    private void saveGreeting(Long sessionId, String reply) {
        if (reply == null || reply.isBlank()) {
            return;
        }
        messageRepository.save(new IdeaMessageEntity(sessionId, ROLE_ASSISTANT, reply));
    }

    /**
     * 主对话通道本轮 user 在调用模型前落库（托管窗口直读本表，必须先入库框架才看得到本轮发言）。
     * 幂等：带 clientRoundId 且本场已有同键 user 行（断线重发/续跑），不重复写；
     * null 幂等键不拦截（允许原样重发，与旧 saveRound 语义一致）。
     */
    private void persistUserTurn(Long sessionId, String userText, String clientRoundId) {
        if (userText == null || userText.isBlank()) {
            return;
        }
        if (clientRoundId != null && messageRepository.existsBySessionIdAndClientRoundIdAndRole(
                sessionId, clientRoundId, ROLE_USER)) {
            log.info("重复轮次 user 行已存在，不重复落库（sessionId={}，clientRoundId={}）",
                    sessionId, clientRoundId);
            return;
        }
        messageRepository.save(new IdeaMessageEntity(sessionId, ROLE_USER, userText, clientRoundId));
        // 新用户消息到达：静默归档被搁置的应用提案（PENDING 后已有多条新发言仍未理睬）
        proposalService.archiveSilentProposals(sessionId);
    }

    /**
     * 主对话通道收口落库：本轮 user 已由 {@link #persistUserTurn} 提前写入，这里只补
     * assistant 行（card 占位由异步装配任务按需补插）。幂等：clientRoundId 非空且本场已有
     * 同键 assistant 行（整轮已完成），整轮跳过；半截留痕不走这里（无幂等键的 assistant 单行）。
     */
    private void saveAssistantRound(Long sessionId, String assistantReply,
                                    Long cardId, String clientRoundId) {
        if (clientRoundId != null && messageRepository.existsBySessionIdAndClientRoundIdAndRole(
                sessionId, clientRoundId, ROLE_ASSISTANT)) {
            log.info("重复轮次拦截（sessionId={}，clientRoundId={}）", sessionId, clientRoundId);
            return;
        }
        List<IdeaMessageEntity> rows = new ArrayList<>(2);
        if (assistantReply != null && !assistantReply.isBlank()) {
            rows.add(new IdeaMessageEntity(sessionId, ROLE_ASSISTANT, assistantReply, clientRoundId));
        }
        if (cardId != null) {
            rows.add(new IdeaMessageEntity(sessionId, ROLE_CARD, String.valueOf(cardId), clientRoundId));
        }
        messageRepository.saveAll(rows);
    }

    /**
     * 法学通道本轮消息整轮入库（user → assistant；不出卡）。历史恢复以此为准。
     * 幂等：clientRoundId 非空且本场已存在带该键的 assistant 行（说明整轮已完成落库），整轮跳过；
     * 半截/空回复留痕传 null，不占用幂等键，客人重发不会被误挡。
     */
    private void saveRound(Long sessionId, List<InMessage> history, String assistantReply,
                           Long cardId, String clientRoundId) {
        InMessage lastUser = null;
        for (int i = history.size() - 1; i >= 0; i--) {
            if (ROLE_USER.equals(history.get(i).role())) {
                lastUser = history.get(i);
                break;
            }
        }
        if (lastUser == null) {
            return;
        }
        if (clientRoundId != null && messageRepository.existsBySessionIdAndClientRoundIdAndRole(
                sessionId, clientRoundId, ROLE_ASSISTANT)) {
            log.info("重复轮次拦截（sessionId={}，clientRoundId={}）", sessionId, clientRoundId);
            return;
        }
        List<IdeaMessageEntity> round = new ArrayList<>(3);
        round.add(new IdeaMessageEntity(sessionId, ROLE_USER, lastUser.content(), clientRoundId));
        if (assistantReply != null && !assistantReply.isBlank()) {
            round.add(new IdeaMessageEntity(sessionId, ROLE_ASSISTANT, assistantReply, clientRoundId));
        }
        if (cardId != null) {
            round.add(new IdeaMessageEntity(sessionId, ROLE_CARD, String.valueOf(cardId), clientRoundId));
        }
        messageRepository.saveAll(round);
    }

    /** 装配器输出的字符串数组字段归一（模型偶发输出成单字符串），空值填「待聊」占位 */
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

    /** 装配器输出的设计字段文本（空值填「待聊」） */
    private static String designField(JsonNode node, String name) {
        String v = node.path(name).asText("").trim();
        return v.isEmpty() ? "待聊" : truncate(v, 500);
    }

    /** 装配器输出的对象数组字段（dimensions/keyItems）原样透传；非数组返回空数组 */
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

    /** 通用 JSON 序列化（citations 等非卡片事件） */
    private String toJsonValue(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }
}
