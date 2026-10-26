package com.mengx.server.forge;

import com.mengx.server.idea.IdeaCardEntity;
import com.mengx.server.idea.IdeaCardRepository;
import com.mengx.server.idea.IdeaMessageEntity;
import com.mengx.server.idea.IdeaMessageRepository;
import com.mengx.server.idea.IdeaSessionEntity;
import com.mengx.server.idea.IdeaSessionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;

/**
 * 造物线聊天（方案 §9 POST /api/forge/jobs/{jobId}/chat/stream）：与主回路物理隔离，
 * 旁路主线全套——不跑路由器/法学检索/双官推演/设计简报/观察装配（客人答「可以」是确认工部问题，不是确认定稿）。
 *
 * 消息下发模型（方案 A，§9）：本接口内完成 herald 形态路由 + 分身确认「好，这就去继续营造」即关流；
 * resume 在工部线程池后台跑，新挂起问题以分身消息落库、前端轮询可见——一问一答一关流。
 * resume 防重入（§7.4）：casResume 条件 UPDATE，0 行说明已被其他触发源拿走，本方只安抚不重复推进。
 * 先落盘后交棒（§7.5）：用户消息、状态转移先落库，事务提交后才提交岗任务。
 */
@Service
public class ForgeChatService {

    private static final Logger log = LoggerFactory.getLogger(ForgeChatService.class);

    /** 造物线聊天请求：content 消息文本；questionId 为点选项按钮时携带的挂起问题标记（零歧义不走三分类） */
    public record ForgeChatRequest(String content, String questionId) {}

    /** SSE 事件（chunk|done|error，与主回路协议同构，前端渲染组件零改动） */
    public record StreamEvent(String type, String content) {}

    /** 造物线消息项（role=user 时为文本；role=assistant 时为分身信封 JSON，前端按 type 解析渲染） */
    public record MessageDto(String role, String content) {}

    private final ForgeJobRepository jobRepository;
    private final IdeaCardRepository cardRepository;
    private final IdeaSessionRepository sessionRepository;
    private final IdeaMessageRepository messageRepository;
    private final ForgeHeraldService herald;
    private final ForgeOrchestrator orchestrator;
    private final TransactionTemplate tx;

    public ForgeChatService(ForgeJobRepository jobRepository,
                            IdeaCardRepository cardRepository,
                            IdeaSessionRepository sessionRepository,
                            IdeaMessageRepository messageRepository,
                            ForgeHeraldService herald,
                            ForgeOrchestrator orchestrator,
                            PlatformTransactionManager transactionManager) {
        this.jobRepository = jobRepository;
        this.cardRepository = cardRepository;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.herald = herald;
        this.orchestrator = orchestrator;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** 造物线聊天入口：状态矩阵裁决（§5.2.1），分身回复以 chunk+done 关流（方案 A） */
    public Flux<StreamEvent> chat(Long jobId, ForgeChatRequest req) {
        if (req == null || req.content() == null || req.content().isBlank()) {
            return Flux.just(new StreamEvent("error", "消息不能为空"));
        }
        ForgeJobEntity job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return Flux.just(new StreamEvent("error", "工单不存在"));
        }
        IdeaSessionEntity session = sessionRepository
                .findByForgeJobIdIn(List.of(jobId)).stream().findFirst().orElse(null);
        if (session == null) {
            return Flux.just(new StreamEvent("error", "造物线会话不存在"));
        }
        String content = req.content().trim();
        // 用户消息先落库（先落盘后交棒，§7.5）：无论走哪个分支，客人说的话都在消息流里
        messageRepository.save(new IdeaMessageEntity(session.getId(), "user", content, "forge", null));

        String status = job.getStatus();
        if (ForgeJobEntity.STATUS_WAITING_USER.equals(status)) {
            return handleWaiting(job, req, content);
        }
        // RUNNING（不打断执行中的岗）/ REVIEW / BLOCKED / 终态：一律记 backlog + 状态说明（§5.2.1）
        herald.saveBacklog(jobId, content);
        return reply(jobId, herald.statusText(status, job.getErrorNote()));
    }

    /** WAITING_USER：选项按钮直达 or flash 三分类 → 答案交抛出岗 resume / backlog / 挡回主线 */
    private Flux<StreamEvent> handleWaiting(ForgeJobEntity job, ForgeChatRequest req, String content) {
        List<PendingQuestion> pendings = PendingQuestion.parse(job.getPendingQuestions());
        PendingQuestion q = pendings.isEmpty() ? null : pendings.get(0);
        if (q == null) {
            // 防御：WAITING_USER 却无挂起问题（数据异常），不盲 resume，按新需求记 backlog
            log.warn("工单 {} 状态 WAITING_USER 但无挂起问题，按 backlog 处理", job.getId());
            herald.saveBacklog(job.getId(), content);
            return reply(job.getId(), herald.redirectText());
        }
        boolean byButton = q.id().equals(req.questionId());
        String intent = byButton
                ? ForgeHeraldService.INTENT_ANSWER
                : herald.classify(q, content);
        if (ForgeHeraldService.INTENT_ANSWER.equals(intent)) {
            return resume(job, q, content);
        }
        if (ForgeHeraldService.INTENT_DEMAND.equals(intent)) {
            herald.saveBacklog(job.getId(), content);
            return reply(job.getId(), herald.redirectText());
        }
        return reply(job.getId(), herald.deflectText(cardTitleOf(job)));
    }

    /** 答案 resume：CAS 防重入 + 升版本 + 清挂起问题 + 卡片镜像，同一事务；提交后交棒抛出岗带答案重跑 */
    private Flux<StreamEvent> resume(ForgeJobEntity job, PendingQuestion q, String answer) {
        Long jobId = job.getId();
        Boolean resumed = tx.execute(status -> {
            if (jobRepository.casResume(jobId, Instant.now()) == 0) {
                return false; // 并发已答/已被重试拿走：安抚不重复推进（§7.4）
            }
            cardRepository.findById(job.getCardId()).ifPresent(c -> {
                c.markBuild(ForgeJobEntity.STATUS_RUNNING, null);
                cardRepository.save(c);
            });
            return true;
        });
        if (!Boolean.TRUE.equals(resumed)) {
            log.info("工单 {} resume 被其他触发源拿走，本次答案按已答处理", jobId);
            return reply(jobId, herald.racingText());
        }
        log.info("工单 {} 客人已答（问题 {}），交抛出岗 {} 带答案重跑",
                jobId, q.id(), job.getStage());
        // 先落盘后交棒：状态已原子落库，事务提交后提交岗任务（闭包带问题与答案——语义消化归抛出岗，§5.2）
        orchestrator.resumeStage(job, q, answer);
        return reply(jobId, herald.confirmText());
    }

    /** 分身回复：落库（刷新可见）+ SSE 一次 chunk + done 关流（方案 A：确认即关流） */
    private Flux<StreamEvent> reply(Long jobId, String rendered) {
        herald.postReply(jobId, rendered);
        return Flux.just(new StreamEvent("chunk", rendered), new StreamEvent("done", ""));
    }

    /** 造物线消息（§9 GET /jobs/{jobId}/messages）：role 白名单过滤，只渲染 user/assistant（§8.2） */
    public Optional<List<MessageDto>> listMessages(Long jobId) {
        if (!jobRepository.existsById(jobId)) {
            return Optional.empty();
        }
        return sessionRepository.findByForgeJobIdIn(List.of(jobId)).stream()
                .findFirst()
                .map(session -> messageRepository
                        .findBySessionIdOrderByIdAsc(session.getId()).stream()
                        .filter(m -> "user".equals(m.getRole()) || "assistant".equals(m.getRole()))
                        .map(m -> new MessageDto(m.getRole(), m.getContent()))
                        .toList());
    }

    private String cardTitleOf(ForgeJobEntity job) {
        return cardRepository.findById(job.getCardId())
                .map(IdeaCardEntity::getTitle).orElse("这座");
    }
}
