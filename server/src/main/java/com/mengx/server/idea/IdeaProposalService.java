package com.mengx.server.idea;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mengx.server.idea.agent.IdeaAgentFactory;
import com.mengx.server.llm.LlmUsage;
import com.mengx.server.routing.ChannelRegistry;
import com.mengx.server.routing.ChannelSpec;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 应用提案服务（Application Proposal Service）：需求共创的许可制入口。
 * 机会观察器（OpportunityObserverAgent）在 Host 直答每轮收口后旁路异步判定——
 * 不再压住 SSE done 事件，前端输入即时解锁；判定 PROPOSE 落提案表（idea_proposals），
 * 前端经 notices 轮询拿到邀请卡，用户点「聊聊看」（accept）后 CAS PENDING→ACCEPTED，
 * 前端随即以 mode=cocreation 自动发出首轮发言，经 Host 路由进共创领域开始访谈。
 * 提案四要素不在此刻推演——用户首轮发言时经 undigestedProposalContext 注入推演背景，
 * 由共创推演官同步消化成第一版快照（用户开口才是推演有新信息的时机）。
 * 单提议原则：新提案落库前把既有 PENDING 置 SUPERSEDED；
 * 静默归档：PENDING 提案后累计 2 条新用户消息无响应 → DISMISSED。
 * EXIT 判定（共创访谈中用户想结束访谈）应用前经代际校验，过期丢弃。
 * 本类只做编排（观察接线、提案状态机、通知组装）；prompt 与模型调用归 idea.agent 包。
 */
@Service
public class IdeaProposalService {

    private static final Logger log = LoggerFactory.getLogger(IdeaProposalService.class);

    /** 操作结果口径：OK 成功 / NOT_FOUND 不存在或非本人 / CONFLICT 已被裁决（非 PENDING） */
    public static final String OK = "OK";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String CONFLICT = "CONFLICT";

    /** 共创领域通道名（与 CoCreationService.CHANNEL 同值；自建常量避免与其循环依赖） */
    private static final String CHANNEL_COCREATION = "cocreation";

    /** 观察器回看的最近对话条数（不含本轮消息） */
    private static final int OBSERVER_DIALOG_WINDOW = 6;
    /** PENDING 提案静默归档阈值：之后累计 N 条新用户消息无响应 */
    private static final int SILENT_DISMISS_MESSAGES = 2;
    /** notices 晚到卡片的时间窗（前端按卡片 id 去重，窗口只控制返回量） */
    private static final long LATE_CARD_WINDOW_SECONDS = 300;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final IdeaAgentFactory agents;
    private final IdeaProposalRepository proposalRepository;
    private final IdeaSessionRepository sessionRepository;
    private final IdeaCardRepository cardRepository;
    private final IdeaMessageRepository messageRepository;
    private final ChannelRegistry channelRegistry;
    private final DiscoveryEpochGuard epochGuard;
    private final DiscoveryService discoveryService;

    /** 机会观察专用池：秒级阻塞判定，独立于造物线线程池 */
    private final ExecutorService observerPool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "opportunity-observer");
        t.setDaemon(true);
        return t;
    });

    public IdeaProposalService(
            IdeaAgentFactory agents,
            IdeaProposalRepository proposalRepository,
            IdeaSessionRepository sessionRepository,
            IdeaCardRepository cardRepository,
            IdeaMessageRepository messageRepository,
            ChannelRegistry channelRegistry,
            DiscoveryEpochGuard epochGuard,
            DiscoveryService discoveryService) {
        this.agents = agents;
        this.proposalRepository = proposalRepository;
        this.sessionRepository = sessionRepository;
        this.cardRepository = cardRepository;
        this.messageRepository = messageRepository;
        this.channelRegistry = channelRegistry;
        this.epochGuard = epochGuard;
        this.discoveryService = discoveryService;
    }

    @PreDestroy
    void shutdownPools() {
        observerPool.shutdownNow();
    }

    // ==================== 旁路观察 ====================

    /**
     * 旁路机会观察：Host 直答收口（done 已发、输入已解锁）后异步执行，
     * 不阻塞任何用户可感知路径。历史与回复在提交线程提取快照，任务内重读会话取最新状态。
     */
    public void observeAsync(IdeaSessionEntity session, List<IdeaChatService.InMessage> history,
                             String hostReply, int turnEpoch) {
        if (!agents.available()) {
            return;
        }
        Long sessionId = session.getId();
        Long userId = session.getUserId();
        String userMessage = latestUserText(history);
        String dialog = recentDialogText(history);
        try {
            observerPool.submit(
                    () -> runObservation(sessionId, userId, userMessage, hostReply, dialog, turnEpoch));
        } catch (Exception e) {
            log.warn("机会观察任务提交失败（不影响对话）：{}", e.getMessage());
        }
    }

    private void runObservation(Long sessionId, Long userId, String userMessage, String hostReply,
                                String dialog, int turnEpoch) {
        try {
            // 任务执行时重读会话：提交与执行之间用户可能已许可提案进入发现模式
            IdeaSessionEntity session = sessionRepository.findById(sessionId).orElse(null);
            if (session == null) {
                return;
            }
            // 共创访谈进行中 = 活跃帧为 cocreation，或最新推演未定稿且在热窗内
            // （后者覆盖访谈刚被 Host 直答逃逸、活跃帧已切回 host 的自愈场景）
            boolean discovery = CHANNEL_COCREATION.equals(session.getMode())
                    || discoveryService.interviewHot(sessionId);
            Observation obs = observeWithRetry(sessionId, discovery, userMessage, hostReply, dialog);
            if (obs == null) {
                log.warn("机会观察两次尝试均失败（sessionId={}，epoch={}）", sessionId, turnEpoch);
                return;
            }
            if (obs.exit()) {
                applyExit(session, turnEpoch);
            } else if (obs.propose()) {
                saveProposal(sessionId, userId, obs, turnEpoch);
            } else {
                log.info("机会观察判定 NONE（sessionId={}，epoch={}，discovery={}）",
                        sessionId, turnEpoch, discovery);
            }
        } catch (Exception e) {
            log.warn("机会观察执行失败（不影响对话）：{}", e.getMessage());
        }
    }

    /**
     * 观察器调用 + 解析，带 2 秒退避重试一次：模型异常（含 1302 限流）与输出无法识别
     * 两类失败均重试；两次仍失败返回 null（旁路路径，静默放弃不影响对话）。
     */
    private Observation observeWithRetry(Long sessionId, boolean discovery, String userMessage,
                                         String hostReply, String dialog) {
        LlmUsage.set("observer"); // 用量观测：机会观察场景
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                if (attempt > 0) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
                String reply;
                try {
                    reply = agents.observer().observe(
                            discovery ? "是" : "否",
                            capabilityRoster(),
                            knownList(sessionId),
                            dialog,
                            userMessage,
                            hostReply);
                } catch (RuntimeException e) {
                    log.warn("机会观察模型调用失败（第 {} 次）：{}", attempt + 1, e.getMessage());
                    continue;
                }
                if (reply == null || reply.isBlank()) {
                    log.warn("机会观察空输出（第 {} 次）", attempt + 1);
                    continue;
                }
                Observation obs = parseObservation(reply);
                if (obs != null) {
                    return obs;
                }
                log.warn("机会观察输出无法识别（第 {} 次），退避后重试：{}",
                        attempt + 1, truncate(reply, 80));
            }
        } finally {
            LlmUsage.clear();
        }
        return null;
    }

    /** EXIT 应用：仅当代际未变（用户没发新一轮）且共创帧仍活跃时切回 host 帧，防过期误关 */
    private void applyExit(IdeaSessionEntity session, int turnEpoch) {
        Long sessionId = session.getId();
        if (!epochGuard.isCurrent(sessionId, turnEpoch)) {
            log.info("机会观察 EXIT 已过期，丢弃（sessionId={}，epoch={}）", sessionId, turnEpoch);
            return;
        }
        if (CHANNEL_COCREATION.equals(session.getMode())) {
            session.setMode(ChannelRegistry.DEFAULT_CHANNEL);
            sessionRepository.save(session);
            log.info("机会观察判定 EXIT：退出共创访谈（sessionId={}，epoch={}）", sessionId, turnEpoch);
        }
    }

    /** PROPOSE 落库：过卡数上限/发现模式闸门后保存，并顶替既有 PENDING 提案。
     *  注：不做"与已有卡重复"拦截——功能卡是创意存档而非已开发功能，相似点子允许重复创作；
     *  supersedePending 的事务由 repository 方法上的 @Transactional 保障
     *  （本方法为同类内部调用，加 @Transactional 不走代理不生效） */
    private void saveProposal(Long sessionId, Long userId, Observation obs, int turnEpoch) {
        if (cardRepository.countBySessionId(sessionId) >= IdeaChatService.MAX_CARDS_PER_SESSION) {
            log.info("机会观察判 PROPOSE 但本场卡数已到上限（{}），不落提案",
                    IdeaChatService.MAX_CARDS_PER_SESSION);
            return;
        }
        String title = obs.suggestTitle() == null ? "" : obs.suggestTitle().trim();
        if (title.isEmpty()) {
            log.info("机会观察判 PROPOSE 但标题为空，不落提案（sessionId={}）", sessionId);
            return;
        }
        // 二次校验：观察期间用户可能已许可别的提案进入共创访谈
        IdeaSessionEntity fresh = sessionRepository.findById(sessionId).orElse(null);
        if (fresh == null || CHANNEL_COCREATION.equals(fresh.getMode())) {
            log.info("机会观察判 PROPOSE 但共创访谈进行中，提案丢弃（sessionId={}）", sessionId);
            return;
        }
        // 访谈热窗闸门：最新推演未定稿且在热窗内——访谈仍在进行（含刚被 Host 逃逸待拉回），不重复提案
        if (discoveryService.interviewHot(sessionId)) {
            log.info("机会观察判 PROPOSE 但共创访谈仍在进行（或刚被逃逸待拉回），提案丢弃（sessionId={}）",
                    sessionId);
            return;
        }
        IdeaProposalEntity proposal = proposalRepository.save(new IdeaProposalEntity(
                sessionId, userId,
                truncate(title, 100),
                truncate(or(obs.userStory()), 500),
                truncate(or(obs.gap()), 500),
                truncate(or(obs.signalQuote()), 500),
                turnEpoch));
        // 单提议原则：顶替既有 PENDING
        proposalRepository.supersedePending(sessionId, proposal.getId(), Instant.now());
        log.info("机会观察判定 PROPOSE：应用提案已落库（sessionId={}，proposalId={}，epoch={}，标题《{}》）",
                sessionId, proposal.getId(), turnEpoch, proposal.getSuggestTitle());
    }

    /** 观察结论：verdict ∈ PROPOSE/NONE/EXIT；PROPOSE 时四字段齐备（证据缺失判无效） */
    record Observation(String verdict, String suggestTitle, String userStory, String gap,
                       String signalQuote) {
        boolean propose() {
            return "PROPOSE".equals(verdict);
        }

        boolean exit() {
            return "EXIT".equals(verdict);
        }
    }

    /** 解析观察器输出：剥 markdown 围栏后截取 JSON；PROPOSE 缺 signalQuote/userStory 判无效 */
    static Observation parseObservation(String reply) {
        if (reply == null) {
            return null;
        }
        String text = stripFences(reply.trim());
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(text.substring(start, end + 1));
            String verdict = node.path("verdict").asText("").trim().toUpperCase(Locale.ROOT);
            if (verdict.isEmpty()) {
                return null;
            }
            if ("PROPOSE".equals(verdict)) {
                String signalQuote = node.path("signalQuote").asText("").trim();
                String userStory = node.path("userStory").asText("").trim();
                if (signalQuote.isEmpty() || userStory.isEmpty()) {
                    return null;
                }
                return new Observation(verdict,
                        node.path("suggestTitle").asText("").trim(),
                        userStory,
                        node.path("gap").asText("").trim(),
                        signalQuote);
            }
            return new Observation(verdict, null, null, null, null);
        } catch (Exception e) {
            return null;
        }
    }

    /** 剥 markdown 围栏（```json … ```）：模型偶尔裹输出，不剥会误判为无法识别 */
    public static String stripFences(String text) {
        if (text.startsWith("```")) {
            int firstBreak = text.indexOf('\n');
            int closing = text.lastIndexOf("```");
            if (firstBreak >= 0) {
                return (closing > firstBreak
                        ? text.substring(firstBreak + 1, closing)
                        : text.substring(firstBreak + 1)).trim();
            }
        }
        return text;
    }

    // ==================== 许可 / 拒绝 / 通知 ====================

    /** 许可结果（outcome 取 {@link #OK}/{@link #NOT_FOUND}/{@link #CONFLICT}） */
    public record ProposalResult(String outcome) {}

    /**
     * 许可提案：CAS PENDING→ACCEPTED，仅做状态裁决——不动会话、不落消息。
     * 首轮发言由前端以 mode=cocreation 自动发出（路由三态的最强提示），经 Host 进共创领域；
     * 不在此刻推演：提案四要素待用户首轮发言时经 {@link #undigestedProposalContext} 注入推演背景。
     */
    @Transactional
    public ProposalResult accept(Long userId, Long proposalId) {
        IdeaProposalEntity proposal = proposalRepository.findByIdAndUserId(proposalId, userId).orElse(null);
        if (proposal == null) {
            return new ProposalResult(NOT_FOUND);
        }
        if (proposalRepository.casAccept(proposalId, Instant.now()) == 0) {
            return new ProposalResult(CONFLICT);
        }
        log.info("应用提案已许可，共创首轮由前端以 cocreation 模式发起（sessionId={}，proposalId={}）",
                proposal.getSessionId(), proposalId);
        return new ProposalResult(OK);
    }

    /** 拒绝提案：CAS PENDING→DISMISSED（用户主动拒绝） */
    @Transactional
    public String dismiss(Long userId, Long proposalId) {
        if (proposalRepository.findByIdAndUserId(proposalId, userId).isEmpty()) {
            return NOT_FOUND;
        }
        return proposalRepository.casDismiss(proposalId, Instant.now()) > 0 ? OK : CONFLICT;
    }

    /** 静默归档：PENDING 提案后累计 {@value #SILENT_DISMISS_MESSAGES} 条新用户消息无响应 → DISMISSED */
    public void archiveSilentProposals(Long sessionId) {
        try {
            proposalRepository
                    .findBySessionIdAndStatusOrderByIdDesc(sessionId, IdeaProposalEntity.STATUS_PENDING)
                    .stream().findFirst().ifPresent(p -> {
                        long userAfter = messageRepository.countBySessionIdAndRoleAndCreatedAtAfter(
                                sessionId, "user", p.getCreatedAt());
                        if (userAfter >= SILENT_DISMISS_MESSAGES) {
                            proposalRepository.casDismiss(p.getId(), Instant.now());
                            log.info("应用提案静默归档（sessionId={}，proposalId={}，无响应消息 {} 条）",
                                    sessionId, p.getId(), userAfter);
                        }
                    });
        } catch (Exception e) {
            log.warn("提案静默归档检查失败（不影响对话）：{}", e.getMessage());
        }
    }

    /** 轮询通知：待裁决提案（至多一条）+ 时间窗内晚到的功能卡（前端按卡片 id 去重） */
    public record ProposalDto(Long id, String suggestTitle, String userStory, String gap,
                              String signalQuote) {}

    public record NoticesDto(List<ProposalDto> proposals, List<IdeaCardDto> cards) {}

    public NoticesDto notices(Long sessionId) {
        List<ProposalDto> proposals = proposalRepository
                .findBySessionIdAndStatusOrderByIdDesc(sessionId, IdeaProposalEntity.STATUS_PENDING)
                .stream().limit(1)
                .map(p -> new ProposalDto(p.getId(), p.getSuggestTitle(), p.getUserStory(),
                        p.getGap(), p.getSignalQuote()))
                .toList();
        Instant since = Instant.now().minusSeconds(LATE_CARD_WINDOW_SECONDS);
        List<IdeaCardDto> cards = cardRepository.findBySessionIdOrderByIdDesc(sessionId).stream()
                .filter(c -> c.getCreatedAt() != null && c.getCreatedAt().isAfter(since))
                .map(IdeaCardDto::from)
                .toList();
        return new NoticesDto(proposals, cards);
    }

    // ==================== 私有工具 ====================

    /** 本场最近一条已许可提案的 id（功能卡溯源绑定用）；无则返回 null */
    public Long acceptedProposalId(Long sessionId) {
        try {
            return proposalRepository
                    .findBySessionIdAndStatusOrderByIdDesc(sessionId, IdeaProposalEntity.STATUS_ACCEPTED)
                    .stream().findFirst().map(IdeaProposalEntity::getId).orElse(null);
        } catch (Exception e) {
            log.warn("已许可提案查询失败（卡片不绑提案来源，不影响装配）：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 未消化的已许可提案要素（共创访谈首轮推演背景注入）：
     * 最新 ACCEPTED 提案许可后尚无推演存档（counsel 行）时返回要素文本，否则 null。
     * 供用户首轮发言的同步推演携带——共创推演官把它连同用户回答一起画进第一版快照。
     */
    public String undigestedProposalContext(Long sessionId) {
        try {
            IdeaProposalEntity proposal = proposalRepository
                    .findBySessionIdAndStatusOrderByIdDesc(sessionId, IdeaProposalEntity.STATUS_ACCEPTED)
                    .stream().findFirst().orElse(null);
            if (proposal == null) {
                return null;
            }
            boolean digested = messageRepository.countBySessionIdAndRoleAndCreatedAtAfter(
                    sessionId, "counsel", proposal.getCreatedAt()) > 0;
            if (digested) {
                return null;
            }
            return "【已许可的应用提案】\n"
                    + "标题：《" + proposal.getSuggestTitle() + "》\n"
                    + "用户故事：" + proposal.getUserStory() + "\n"
                    + "能力缺口：" + proposal.getGap() + "\n"
                    + "用户原话：" + proposal.getSignalQuote();
        } catch (Exception e) {
            log.warn("未消化提案查询失败（不影响推演）：{}", e.getMessage());
            return null;
        }
    }

    /** 已实装能力清单：路由注册表各通道职责 + 基础服务（观察器 Q1 判定输入） */
    public String capabilityRoster() {
        StringBuilder sb = new StringBuilder(
                "- 主对话代理（默认通道）：日常问答与闲聊，及其余话题的接待与分发\n");
        for (ChannelSpec spec : channelRegistry.all()) {
            if (ChannelRegistry.DEFAULT_CHANNEL.equals(spec.id())) {
                continue;
            }
            sb.append("- ").append(spec.name()).append("：").append(spec.routeDescription()).append('\n');
        }
        sb.append("- 基础服务：手机号登录（登录即注册）、功能卡看板与状态流转、点赞与创意榜、每日一签\n");
        sb.append("- 文档与施工：功能卡确认后自动进入造物线，产出施工包（可下载投喂 AI 编程工具）\n");
        return sb.toString().trim();
    }

    /** 防重清单：本场已定稿卡片 + 全站热门卡片（标题：说明） */
    public String knownList(Long sessionId) {
        StringBuilder sb = new StringBuilder();
        for (IdeaCardEntity card : cardRepository.findBySessionIdOrderByIdDesc(sessionId)) {
            sb.append("《").append(card.getTitle()).append("》：")
                    .append(card.getSummary() == null ? "" : card.getSummary()).append('\n');
        }
        for (IdeaCardEntity card : cardRepository.findTop15ByOrderByCreativityDescCreatedAtDesc()) {
            sb.append("《").append(card.getTitle()).append("】：")
                    .append(card.getSummary() == null ? "" : card.getSummary()).append('\n');
        }
        return sb.isEmpty() ? "（还没有）" : sb.toString().trim();
    }

    /** 观察器回看的最近对话（不含本轮消息；客人=用户，主持=主对话代理） */
    private String recentDialogText(List<IdeaChatService.InMessage> history) {
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, history.size() - 1 - OBSERVER_DIALOG_WINDOW);
        for (int i = from; i < history.size() - 1; i++) {
            IdeaChatService.InMessage m = history.get(i);
            if ("user".equals(m.role())) {
                sb.append("客人：");
            } else if ("assistant".equals(m.role())) {
                sb.append("主持：");
            } else {
                continue;
            }
            sb.append(m.content() == null ? "" : m.content()).append('\n');
        }
        return sb.isEmpty() ? "（本场刚开始，还没有历史）" : sb.toString().trim();
    }

    private static String latestUserText(List<IdeaChatService.InMessage> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            IdeaChatService.InMessage m = history.get(i);
            if ("user".equals(m.role())) {
                return m.content() == null ? "" : m.content();
            }
        }
        return "";
    }

    private static String or(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
