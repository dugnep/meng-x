package com.mengx.server.law;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 法学用户画像（W3 画像 P1）：不建画像表，从 law_answer_records 实时聚合——
 * 答题记录是唯一事实源，画像是视图，永远零漂移（单用户记录量级百级，内存聚合毫秒级）。
 * 三个消费点：出题官学情注入（quizBriefing）、法学问答口吻适配（chatHint）、学情汇报（report）。
 */
@Service
public class LawProfileService {

    private static final Logger log = LoggerFactory.getLogger(LawProfileService.class);

    /** 弱点门槛：至少答过 2 题且错误率 ≥50% */
    private static final int WEAK_MIN_ATTEMPTS = 2;
    private static final double WEAK_WRONG_RATE = 0.5;
    /** 弱点清单上限 */
    private static final int WEAK_TOP_N = 5;
    /** 稳固考点门槛：至少答过 2 题且全对 */
    private static final int STABLE_MIN_ATTEMPTS = 2;

    private final LawAnswerRecordRepository recordRepository;
    private final LawQuestionRepository questionRepository;

    public LawProfileService(LawAnswerRecordRepository recordRepository,
            LawQuestionRepository questionRepository) {
        this.recordRepository = recordRepository;
        this.questionRepository = questionRepository;
    }

    /** 画像总览 */
    public record ProfileSummary(int total, int correct, int activeDays, String tier) {
        public double accuracy() {
            return total == 0 ? 0 : (double) correct / total;
        }
    }

    /** 弱点考点（按 错误数×错误率 排序） */
    public record WeakPoint(String point, int attempts, int wrong) {
        public double wrongRate() {
            return attempts == 0 ? 0 : (double) wrong / attempts;
        }
    }

    /** 按考点聚合的中间结构 */
    private record PointStat(int attempts, int wrong) {}

    // ---------- 聚合核心 ----------

    /** 逐考点统计（knowledgePoints 展开计数；题目缺失的记录按「未知考点」归桶跳过） */
    private Map<String, PointStat> statsByPoint(Long userId) {
        List<LawAnswerRecordEntity> records = recordRepository.findByUserId(userId);
        if (records.isEmpty()) {
            return Map.of();
        }
        Map<Long, LawQuestionEntity> questions = new HashMap<>();
        for (LawQuestionEntity q : questionRepository.findAllById(
                records.stream().map(LawAnswerRecordEntity::getQuestionId).toList())) {
            questions.put(q.getId(), q);
        }
        Map<String, int[]> stats = new LinkedHashMap<>();
        for (LawAnswerRecordEntity r : records) {
            LawQuestionEntity q = questions.get(r.getQuestionId());
            if (q == null || q.getKnowledgePoints() == null) {
                continue;
            }
            for (String point : q.getKnowledgePoints()) {
                int[] s = stats.computeIfAbsent(point, k -> new int[2]);
                s[0]++;
                if (!r.isCorrect()) {
                    s[1]++;
                }
            }
        }
        Map<String, PointStat> result = new LinkedHashMap<>();
        stats.forEach((k, v) -> result.put(k, new PointStat(v[0], v[1])));
        return result;
    }

    public ProfileSummary summary(Long userId) {
        List<LawAnswerRecordEntity> records = recordRepository.findByUserId(userId);
        int correct = (int) records.stream().filter(LawAnswerRecordEntity::isCorrect).count();
        Set<String> days = new HashSet<>();
        records.forEach(r -> days.add(r.getAnsweredAt()
                .atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString()));
        return new ProfileSummary(records.size(), correct, days.size(), tier(records.size(), records.isEmpty() ? 0 : (double) correct / records.size()));
    }

    /** 能力档位：题量+正确率粗分（入门/进阶/熟练） */
    private static String tier(int total, double accuracy) {
        if (total == 0) {
            return "新手";
        }
        if (total < 10 || accuracy < 0.5) {
            return "入门";
        }
        if (total < 50 || accuracy < 0.75) {
            return "进阶";
        }
        return "熟练";
    }

    public List<WeakPoint> weakPoints(Long userId) {
        return statsByPoint(userId).entrySet().stream()
                .filter(e -> e.getValue().attempts() >= WEAK_MIN_ATTEMPTS
                        && (double) e.getValue().wrong() / e.getValue().attempts() >= WEAK_WRONG_RATE)
                .sorted(Comparator.comparingDouble((Map.Entry<String, PointStat> e) ->
                                e.getValue().wrong() * ((double) e.getValue().wrong() / e.getValue().attempts()))
                        .reversed())
                .limit(WEAK_TOP_N)
                .map(e -> new WeakPoint(e.getKey(), e.getValue().attempts(), e.getValue().wrong()))
                .toList();
    }

    /** 稳固考点（全对）：出题降频用 */
    public List<String> stablePoints(Long userId) {
        return statsByPoint(userId).entrySet().stream()
                .filter(e -> e.getValue().attempts() >= STABLE_MIN_ATTEMPTS && e.getValue().wrong() == 0)
                .map(Map.Entry::getKey)
                .limit(5)
                .toList();
    }

    // ---------- 三个消费点 ----------

    /** 出题官学情注入（空串=零记录不注入）：弱点加权、已稳降频、难度贴档 */
    public String quizBriefing(Long userId) {
        ProfileSummary s = summary(userId);
        if (s.total() == 0) {
            return "";
        }
        List<WeakPoint> weak = weakPoints(userId);
        List<String> stable = stablePoints(userId);
        StringBuilder sb = new StringBuilder()
                .append("该生已练 ").append(s.total()).append(" 题，正确率 ")
                .append(pct(s.accuracy())).append("（").append(s.tier()).append("档）。");
        if (!weak.isEmpty()) {
            sb.append("弱点考点：");
            for (WeakPoint w : weak) {
                sb.append(w.point()).append("（").append(w.attempts()).append(" 题错 ")
                        .append(w.wrong()).append("）");
                if (!weak.get(weak.size() - 1).equals(w)) {
                    sb.append("、");
                }
            }
            sb.append("。");
        }
        if (!stable.isEmpty()) {
            sb.append("已稳考点：").append(String.join("、", stable)).append("。");
        }
        sb.append("出题倾向：优先覆盖弱点考点，已稳考点降频，难度贴合其档位。");
        return sb.toString();
    }

    /** 法学问答口吻适配（空串=零记录不注入）：对练过题的备考生与纯咨询者措辞深浅不同 */
    public String chatHint(Long userId) {
        ProfileSummary s = summary(userId);
        if (s.total() == 0) {
            return "";
        }
        List<WeakPoint> weak = weakPoints(userId);
        StringBuilder sb = new StringBuilder()
                .append("【学情参考】这位客人已练 ").append(s.total()).append(" 题、正确率 ")
                .append(pct(s.accuracy())).append("（").append(s.tier()).append("档），是备考路上的自己人——");
        if (weak.isEmpty()) {
            sb.append("讲解可以放开些，按备考的深浅来。");
        } else {
            sb.append("弱点在：");
            for (int i = 0; i < weak.size(); i++) {
                sb.append(weak.get(i).point());
                if (i < weak.size() - 1) {
                    sb.append("、");
                }
            }
            sb.append("，讲到这些考点时多铺一步。");
        }
        return sb.toString();
    }

    /** 学情汇报（纯代码模板，零模型调用）；零记录返回 null（调用方走引导话术） */
    public String report(Long userId) {
        ProfileSummary s = summary(userId);
        if (s.total() == 0) {
            return null;
        }
        List<WeakPoint> weak = weakPoints(userId);
        List<String> stable = stablePoints(userId);
        StringBuilder sb = new StringBuilder()
                .append("翻了翻你的答题簿——累计练了 ").append(s.total()).append(" 题，答对 ")
                .append(s.correct()).append(" 题（正确率 ").append(pct(s.accuracy()))
                .append("），前后练了 ").append(s.activeDays()).append(" 天，眼下算「")
                .append(s.tier()).append("」手。");
        if (!weak.isEmpty()) {
            sb.append("\n\n弱点最扎眼的是这几处：");
            for (WeakPoint w : weak) {
                sb.append("\n· ").append(w.point()).append("——").append(w.attempts())
                        .append(" 题错了 ").append(w.wrong());
            }
        } else {
            sb.append("\n\n眼下没有明显短板，考点都接得住。");
        }
        if (!stable.isEmpty()) {
            sb.append("\n\n已稳住的：").append(String.join("、", stable)).append("。");
        }
        sb.append("\n\n想补弱就说「继续出」，我会照着弱点出题；想逐条问，直接发问。");
        log.info("学情汇报（userId={}，{} 题，正确率 {}，弱点 {} 处）",
                userId, s.total(), pct(s.accuracy()), weak.size());
        return sb.toString();
    }

    private static String pct(double rate) {
        return Math.round(rate * 100) + "%";
    }
}
