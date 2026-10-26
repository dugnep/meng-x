package com.mengx.server.forge;

import com.mengx.server.idea.IdeaCardEntity;
import com.mengx.server.idea.IdeaCardRepository;
import com.mengx.server.idea.IdeaSessionEntity;
import com.mengx.server.idea.IdeaSessionRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 造物线业务（方案 §9 接口）：开工（建工单 + 冻结营造图快照 + FORGE 会话 + 提交首岗）、
 * 在制工单列表、工单详情。鉴权复用现有 JWT（SecurityConfig anyRequest 默认覆盖 /api/forge/**）。
 * 权限口径 v1 不设角色：看板可见即可操作（信任圈产品，§5.5）。
 * 开工入口：功能卡「确认」即授权开工，由 IdeaService.confirmCard 在事务内调用 startBuild。
 */
@Service
public class ForgeService {

    private static final Logger log = LoggerFactory.getLogger(ForgeService.class);

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** 无营造图快照的兜底信封（缺设计沉淀）：分析师按卡片标题与一句话深化 */
    private static final String EMPTY_SNAPSHOT = "{\"note\":\"无营造图快照，请以功能卡标题与一句话说明为基准深化\"}";

    /** 终审打回合法目标岗（advisor 为槽位名，执行时经注册表重新选型） */
    private static final Set<String> REJECT_TARGETS = Set.of("analyst", "advisor", "architect", "reviewer");

    /** 打回岗中文名（分身播报用） */
    private static final Map<String, String> STAGE_LABELS = Map.of(
            "analyst", "需求分析师", "advisor", "领域顾问", "architect", "架构师", "reviewer", "审校官");

    private final ForgeJobRepository jobRepository;
    private final IdeaCardRepository cardRepository;
    private final IdeaSessionRepository sessionRepository;
    private final ForgeExecutor executor;
    private final ForgeOrchestrator orchestrator;
    private final ForgeHeraldService herald;
    private final ForgeFormatterService formatter;
    private final TransactionTemplate tx;

    public ForgeService(ForgeJobRepository jobRepository,
                        IdeaCardRepository cardRepository,
                        IdeaSessionRepository sessionRepository,
                        ForgeExecutor executor,
                        ForgeOrchestrator orchestrator,
                        ForgeHeraldService herald,
                        ForgeFormatterService formatter,
                        PlatformTransactionManager transactionManager) {
        this.jobRepository = jobRepository;
        this.cardRepository = cardRepository;
        this.sessionRepository = sessionRepository;
        this.executor = executor;
        this.orchestrator = orchestrator;
        this.herald = herald;
        this.formatter = formatter;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** 开工结果：ok 成功；conflict 区分重复开工（409）与卡不合规（404） */
    public record StartResult(boolean ok, boolean conflict, String message, Long jobId, Long sessionId) {}

    /** 在制工单列表项（任务面板/角标用） */
    public record JobDto(Long jobId, Long cardId, String cardTitle, String status, String stage,
                         int designVersion, int pendingCount, String errorNote, String updatedAt,
                         Long sessionId) {}

    /** 工单详情：黑板摘要（product/待决清单/后续段原样）+ 状态 + 挂起问题 */
    public record JobDetail(Long jobId, Long cardId, String cardTitle, String status, String stage,
                            int designVersion, String errorNote, String updatedAt, Long sessionId,
                            List<PendingQuestion> pendingQuestions, ProductSegment product,
                            List<String> openQuestions, Object domainDesign, Object engineering) {}

    /**
     * 开工（功能卡确认时由 IdeaService.confirmCard 在事务内调用）：校验已确认；一卡一单查重；
     * 冻结营造图快照；建 FORGE 会话；卡片镜像 RUNNING；事务提交后提交首岗 analyst（岗内重读工单）。
     * 首岗提交挂 afterCommit 钩子：本方法可能嵌套在 confirmCard 的外层事务里，
     * 若在 tx.execute 返回后直接提交，岗线程会读到外层尚未提交的工单行。
     */
    public StartResult startBuild(Long userId, String nickname, Long cardId) {
        StartResult result = tx.execute(status -> {
            IdeaCardEntity card = cardRepository.findById(cardId).orElse(null);
            if (card == null) {
                return new StartResult(false, false, "卡片不存在", null, null);
            }
            if (!IdeaCardEntity.STATUS_CONFIRMED.equals(card.getStatus())) {
                return new StartResult(false, false, "只有已确认的功能卡才能开工造物线", null, null);
            }
            Optional<ForgeJobEntity> existing = jobRepository.findByCardId(cardId);
            if (existing.isPresent()) {
                String st = existing.get().getStatus();
                return new StartResult(false, true,
                        "这张卡已有工单（状态 " + st + "），不能重复开工", existing.get().getId(), null);
            }
            String snapshot = card.getDesignJson() == null || card.getDesignJson().isBlank()
                    ? EMPTY_SNAPSHOT : card.getDesignJson();
            ForgeJobEntity job = new ForgeJobEntity(cardId, snapshot);
            job.markStatus(ForgeJobEntity.STATUS_RUNNING, "analyst", null);
            ForgeJobEntity saved = jobRepository.save(job);
            IdeaSessionEntity session = sessionRepository.save(
                    IdeaSessionEntity.forgeOf(userId, nickname, cardId, saved.getId()));
            // 分身开场播报（同一事务落库）：客人进造物线即知道分身在造什么、怎么配合
            herald.postOpening(session.getId(), card.getTitle());
            card.markBuild(ForgeJobEntity.STATUS_RUNNING, null);
            cardRepository.save(card);
            // 事务真正提交后再提交岗任务（runStage 重读工单，不会读到未提交行）
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.submitStage(saved.getId(), () -> orchestrator.runStage(saved));
                }
            });
            return new StartResult(true, false, "开工成功，需求分析师已进场", saved.getId(), session.getId());
        });
        if (result.ok()) {
            log.info("开工：cardId={} → 工单 {}，造物线会话 {}（用户 {}）",
                    cardId, result.jobId(), result.sessionId(), userId);
        }
        return result;
    }

    /** 当前用户在制工单（§9）：归属经 card→session→userId 链路解析；在制 = 未 DONE/CANCELLED */
    public List<JobDto> listJobs(Long userId) {
        List<IdeaSessionEntity> sessions = sessionRepository.findByUserIdAndKindOrderByCreatedAtDesc(
                userId, IdeaSessionEntity.KIND_MAIN);
        if (sessions.isEmpty()) {
            return List.of();
        }
        List<IdeaCardEntity> cards = cardRepository.findBySessionIdInOrderByCreatedAtDesc(
                sessions.stream().map(IdeaSessionEntity::getId).toList());
        if (cards.isEmpty()) {
            return List.of();
        }
        Map<Long, String> titles = cards.stream()
                .collect(Collectors.toMap(IdeaCardEntity::getId, IdeaCardEntity::getTitle));
        List<ForgeJobEntity> jobs = jobRepository.findByCardIdInOrderByUpdatedAtDesc(titles.keySet());
        if (jobs.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> jobSession = sessionRepository.findByForgeJobIdIn(
                        jobs.stream().map(ForgeJobEntity::getId).toList()).stream()
                .collect(Collectors.toMap(IdeaSessionEntity::getForgeJobId, IdeaSessionEntity::getId,
                        (a, b) -> a));
        return jobs.stream()
                .filter(j -> !ForgeJobEntity.STATUS_DONE.equals(j.getStatus())
                        && !ForgeJobEntity.STATUS_CANCELLED.equals(j.getStatus()))
                .map(j -> new JobDto(
                        j.getId(), j.getCardId(), titles.getOrDefault(j.getCardId(), "？"),
                        j.getStatus(), j.getStage(), j.getDesignVersion() == null ? 1 : j.getDesignVersion(),
                        PendingQuestion.parse(j.getPendingQuestions()).size(),
                        j.getErrorNote(), TIME_FMT.format(j.getUpdatedAt()),
                        jobSession.get(j.getId())))
                .toList();
    }

    /** 未决问题轻量轮询项（角标与任务面板用，前端 10~15s 轮询） */
    public record PendingDto(Long jobId, Long cardId, String cardTitle, int pendingCount,
                             String status, Long sessionId) {}

    /** 轻量轮询（§9 GET /api/forge/pending）：当前用户有待决问题的工单（MVP 推送替代方案） */
    public List<PendingDto> pendingList(Long userId) {
        return listJobs(userId).stream()
                .filter(j -> ForgeJobEntity.STATUS_WAITING_USER.equals(j.status())
                        && j.pendingCount() > 0)
                .map(j -> new PendingDto(j.jobId(), j.cardId(), j.cardTitle(),
                        j.pendingCount(), j.status(), j.sessionId()))
                .toList();
    }

    /** 工单详情：状态 + 挂起问题 + 黑板摘要；黑板信封损坏时摘要尽力而为（状态与挂起问题仍可见） */
    public Optional<JobDetail> jobDetail(Long jobId) {
        return jobRepository.findById(jobId).map(job -> {
            String title = cardRepository.findById(job.getCardId())
                    .map(IdeaCardEntity::getTitle).orElse("？");
            Long sessionId = sessionRepository.findByForgeJobIdIn(List.of(jobId)).stream()
                    .findFirst().map(IdeaSessionEntity::getId).orElse(null);
            ForgeBlackboard board;
            try {
                board = ForgeBlackboard.load(job);
            } catch (Exception e) {
                log.warn("工单 {} 黑板信封损坏，详情仅展示状态与挂起问题：{}", jobId, e.getMessage());
                board = null;
            }
            return new JobDetail(
                    job.getId(), job.getCardId(), title, job.getStatus(), job.getStage(),
                    job.getDesignVersion() == null ? 1 : job.getDesignVersion(),
                    job.getErrorNote(), TIME_FMT.format(job.getUpdatedAt()), sessionId,
                    PendingQuestion.parse(job.getPendingQuestions()),
                    board == null ? null : board.getProduct(),
                    board == null ? List.of() : board.getOpenQuestions(),
                    board == null ? null : board.getDomainDesign(),
                    board == null ? null : board.getEngineering());
        });
    }

    // ---- 终审与断点重试（§5.5/§9：主理人介入全部是结构化操作，不进对话） ----

    /** 终审请求：action=approve|reject|cancel；reject 必带 target（打回岗）与 reason */
    public record ReviewRequest(String action, String target, String reason) {}

    /** 终审/重试结果：status 直接映射 HTTP 码（200/400/404/409） */
    public record ReviewResult(int status, String message) {}

    /**
     * 终审三动作（§5.5）：approve 批准出稿（→ 排版官 → DONE）/ reject 打回指定岗重跑
     * / cancel 搁置谢绝（→ CANCELLED）。REVIEW 态校验 + casDispatch 防并发，
     * v1 全员可操作；播报经分身落库（会话页轮询可见）。
     */
    public ReviewResult review(Long jobId, ReviewRequest req) {
        if (req == null || req.action() == null || req.action().isBlank()) {
            return new ReviewResult(400, "终审动作不能为空");
        }
        ForgeJobEntity job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return new ReviewResult(404, "工单不存在");
        }
        if (!ForgeJobEntity.STATUS_REVIEW.equals(job.getStatus())) {
            return new ReviewResult(409, "工单当前状态是 " + job.getStatus() + "，不在终审环节");
        }
        return switch (req.action().trim().toLowerCase()) {
            case "approve" -> approve(job);
            case "reject" -> reject(job, req);
            case "cancel" -> cancel(job);
            default -> new ReviewResult(400, "终审动作只支持 approve/reject/cancel");
        };
    }

    /** 批准出稿：REVIEW → RUNNING(formatter)，事务提交后派发排版官（§7.2 主理人确认 → formatter → DONE） */
    private ReviewResult approve(ForgeJobEntity job) {
        Boolean ok = tx.execute(status -> {
            if (jobRepository.casDispatch(job.getId(), ForgeJobEntity.STATUS_REVIEW, "formatter",
                    roundsOf(job), null, Instant.now()) == 0) {
                return false;
            }
            mirrorBuild(job.getCardId(), ForgeJobEntity.STATUS_RUNNING, null);
            herald.postReply(job.getId(), "终审已过，排版官正在把终稿排成施工包，出稿后可从看板下载。");
            return true;
        });
        if (!Boolean.TRUE.equals(ok)) {
            return new ReviewResult(409, "工单已被其他操作处理");
        }
        executor.submitStage(job.getId(), () -> formatter.run(job.getId()));
        log.info("工单 {} 终审批准，排版官出稿中", job.getId());
        return new ReviewResult(200, "终审通过，排版官出稿中");
    }

    /** 打回重造：REVIEW → RUNNING(打回岗) 带原因定向重跑；rounds 归零重新走岗链（不受审校 2 轮上限约束） */
    private ReviewResult reject(ForgeJobEntity job, ReviewRequest req) {
        String target = req.target() == null ? "" : req.target().trim();
        if (!REJECT_TARGETS.contains(target)) {
            return new ReviewResult(400, "打回岗位必须是 analyst/advisor/architect/reviewer 其一");
        }
        if (req.reason() == null || req.reason().isBlank()) {
            return new ReviewResult(400, "打回必须带原因");
        }
        Boolean ok = tx.execute(status -> {
            if (jobRepository.casDispatch(job.getId(), ForgeJobEntity.STATUS_REVIEW, target,
                    0, abbreviate(req.reason()), Instant.now()) == 0) {
                return false;
            }
            mirrorBuild(job.getCardId(), ForgeJobEntity.STATUS_RUNNING, null);
            herald.postReply(job.getId(), "主理人把稿子打回给「" + STAGE_LABELS.get(target)
                    + "」重造：" + req.reason().trim() + "。工部已重新开工，有新进展老朽再禀报。");
            return true;
        });
        if (!Boolean.TRUE.equals(ok)) {
            return new ReviewResult(409, "工单已被其他操作处理");
        }
        executor.submitStage(job.getId(), () -> orchestrator.runStage(job));
        log.info("工单 {} 终审打回至 {} 岗（原因：{}）", job.getId(), target, abbreviate(req.reason()));
        return new ReviewResult(200, "已打回给「" + STAGE_LABELS.get(target) + "」重新营造");
    }

    /** 搁置谢绝：REVIEW → CANCELLED，工单随卡归档（一卡一单约束下不可再开工，v1 可接受） */
    private ReviewResult cancel(ForgeJobEntity job) {
        Boolean ok = tx.execute(status -> {
            if (jobRepository.casTransition(job.getId(), ForgeJobEntity.STATUS_REVIEW,
                    ForgeJobEntity.STATUS_CANCELLED, Instant.now()) == 0) {
                return false;
            }
            mirrorBuild(job.getCardId(), ForgeJobEntity.STATUS_CANCELLED, null);
            herald.postReply(job.getId(), "主理人搁置了这座道场的营造，待往后的机缘再议。");
            return true;
        });
        if (!Boolean.TRUE.equals(ok)) {
            return new ReviewResult(409, "工单已被其他操作处理");
        }
        log.info("工单 {} 终审搁置谢绝 → CANCELLED", job.getId());
        return new ReviewResult(200, "工单已搁置谢绝");
    }

    /** 断点重试（§9 POST /retry）：FAILED/BLOCKED → RUNNING(断点岗保留)，重新提交编排器 */
    public ReviewResult retry(Long jobId) {
        ForgeJobEntity job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return new ReviewResult(404, "工单不存在");
        }
        String st = job.getStatus();
        if (!ForgeJobEntity.STATUS_FAILED.equals(st) && !ForgeJobEntity.STATUS_BLOCKED.equals(st)) {
            return new ReviewResult(409, "工单当前状态是 " + st + "，无需重试");
        }
        Boolean ok = tx.execute(status -> {
            if (jobRepository.casDispatch(job.getId(), st, stageOf(job),
                    roundsOf(job), null, Instant.now()) == 0) {
                return false;
            }
            mirrorBuild(job.getCardId(), ForgeJobEntity.STATUS_RUNNING, null);
            return true;
        });
        if (!Boolean.TRUE.equals(ok)) {
            return new ReviewResult(409, "工单已被其他操作处理");
        }
        executor.submitStage(job.getId(), () -> orchestrator.runStage(job));
        log.info("工单 {} 从 {} 断点重试（岗 {}）", jobId, st, stageOf(job));
        return new ReviewResult(200, "已从「" + stageOf(job) + "」岗断点重试");
    }

    /** 施工包 Markdown（§9 GET /api/idea/cards/{id}/build）：DONE 后可下载，未出稿返回空 */
    public Optional<String> getBuildMarkdown(Long cardId) {
        return cardRepository.findById(cardId)
                .filter(c -> ForgeJobEntity.STATUS_DONE.equals(c.getBuildStatus()))
                .map(IdeaCardEntity::getBuildJson)
                .filter(Objects::nonNull)
                .filter(md -> !md.isBlank());
    }

    /** 卡片 buildStatus 镜像（终审动作与重试随工单状态流转） */
    private void mirrorBuild(Long cardId, String status, String json) {
        cardRepository.findById(cardId).ifPresent(c -> {
            c.markBuild(status, json);
            cardRepository.save(c);
        });
    }

    private static int roundsOf(ForgeJobEntity job) {
        return job.getRounds() == null ? 0 : job.getRounds();
    }

    private static String stageOf(ForgeJobEntity job) {
        return job.getStage() == null || job.getStage().isBlank() ? "analyst" : job.getStage();
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() > 490 ? t.substring(0, 490) : t;
    }
}
