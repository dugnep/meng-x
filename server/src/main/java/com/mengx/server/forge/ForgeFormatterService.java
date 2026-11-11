package com.mengx.server.forge;

import com.mengx.server.forge.agent.ForgeAgentFactory;
import com.mengx.server.idea.IdeaCardRepository;
import com.mengx.server.llm.LlmUsage;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 排版官 formatter（方案 §6.3 第五岗，终审 approve 后触发）：把终稿黑板排版成
 * 施工包 Markdown——「不新增设计、不遗漏」的单工序格式化（由 IdeaPrdService 模式改造），
 * 容量放宽至 60000 字（§6.3）。产物不回黑板（黑板已完成使命），直接随 DONE 落卡片
 * buildJson（§8.2），经 GET /api/idea/cards/{id}/build 下载投喂 AI 编程工具。
 *
 * 不走 ForgeAgent 四态接口：产物是 Markdown 全文而非黑板段，指令语义固定
 * （成功 → DONE，异常 → FAILED 断点保留），由 ForgeService.review 经 executor 派发。
 * LLM 契约在 com.mengx.server.forge.agent.ForgeFormatterAgent。
 */
@Service
public class ForgeFormatterService {

    private static final Logger log = LoggerFactory.getLogger(ForgeFormatterService.class);

    /** 施工包容量上限（方案 §6.3：排版官放宽至 60000 字） */
    private static final int MAX_DOC_LENGTH = 60000;

    private final ForgeAgentFactory agents;
    private final ForgeJobRepository jobRepository;
    private final IdeaCardRepository cardRepository;
    private final ForgeHeraldService herald;
    private final TransactionTemplate tx;

    public ForgeFormatterService(ForgeAgentFactory agents,
                                 ForgeJobRepository jobRepository,
                                 IdeaCardRepository cardRepository,
                                 ForgeHeraldService herald,
                                 PlatformTransactionManager transactionManager) {
        this.agents = agents;
        this.jobRepository = jobRepository;
        this.cardRepository = cardRepository;
        this.herald = herald;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** 排版工序入口（工部线程池执行）：终稿黑板 → 施工包 Markdown → DONE + 卡片产物落库 */
    public void run(Long jobId) {
        try {
            ForgeJobEntity job = jobRepository.findById(jobId).orElse(null);
            if (job == null) {
                log.warn("工单 {} 不存在，排版任务丢弃", jobId);
                return;
            }
            if (!ForgeJobEntity.STATUS_RUNNING.equals(job.getStatus())) {
                log.info("工单 {} 已被其他触发源推进到 {}，排版任务丢弃", jobId, job.getStatus());
                return;
            }
            String title = cardRepository.findById(job.getCardId())
                    .map(c -> c.getTitle()).orElse("新应用");
            ForgeBlackboard board = ForgeBlackboard.load(job);
            long startAt = System.currentTimeMillis();
            LlmUsage.set("forge.formatter");
            String markdown;
            try {
                markdown = agents.formatter().format(buildInput(title, board));
            } finally {
                LlmUsage.clear();
            }
            String doc = truncate(markdown, MAX_DOC_LENGTH);
            Boolean done = tx.execute(status -> {
                if (jobRepository.casTransition(jobId, ForgeJobEntity.STATUS_RUNNING,
                        ForgeJobEntity.STATUS_DONE, Instant.now()) == 0) {
                    return false; // 并发兜底：已被其他触发源处理
                }
                cardRepository.findById(job.getCardId()).ifPresent(c -> {
                    c.markBuild(ForgeJobEntity.STATUS_DONE, doc);
                    cardRepository.save(c);
                });
                herald.postReply(jobId, "「" + title + "」的施工文档已出稿，可在看板下载，"
                        + "直接投喂给 AI 编程工具即可开工。");
                return true;
            });
            if (Boolean.TRUE.equals(done)) {
                log.info("工单 {} 施工包出稿（{} 字，排版耗时 {}ms）",
                        jobId, doc.length(), System.currentTimeMillis() - startAt);
            }
        } catch (Exception e) {
            log.warn("工单 {} 排版官执行异常（标 FAILED，断点保留）：{}", jobId, e.toString());
            fail(jobId, "排版官执行异常：" + e);
        }
    }

    /** 排版输入：道场名 + 终稿黑板 JSON 全文（排版只认黑板，信封外输入仅标题） */
    private String buildInput(String title, ForgeBlackboard board) {
        return "【应用名】\n" + title + "\n\n【施工包 JSON 终稿】\n" + board.toJson();
    }

    /** 排版异常兜底：FAILED 带说明 + 卡片镜像（与编排器 fail 同模式；CAS 0 行说明已被自行转移，跳过） */
    private void fail(Long jobId, String note) {
        try {
            tx.execute(status -> {
                ForgeJobEntity job = jobRepository.findById(jobId).orElse(null);
                if (job == null) {
                    return null;
                }
                if (jobRepository.casFail(jobId, abbreviate(note), Instant.now()) > 0) {
                    cardRepository.findById(job.getCardId()).ifPresent(c -> {
                        c.markBuild(ForgeJobEntity.STATUS_FAILED, null);
                        cardRepository.save(c);
                    });
                }
                return null;
            });
        } catch (Exception e) {
            log.error("工单 {} FAILED 落库失败：{}", jobId, e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 490 ? s.substring(0, 490) : s;
    }
}
