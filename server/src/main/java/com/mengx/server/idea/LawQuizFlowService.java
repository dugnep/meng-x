package com.mengx.server.idea;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mengx.server.law.LawAnswerRecordEntity;
import com.mengx.server.law.LawAnswerRecordRepository;
import com.mengx.server.law.LawQuestionGenService;
import com.mengx.server.law.LawProfileService;
import com.mengx.server.law.LawSearchService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.FluxSink;

/**
 * 法学刷题流（法学域内编排，不进全局路由）：出题/重发、交卷判分、学情汇报三类子意图，
 * 全部零模型意图判定（触发词/正则），命中即在法学通道内完成整轮。
 * 由 IdeaChatService 在路由判定法学通道后调用；出题官/判分/画像等能力仍在 law 包。
 */
@Service
public class LawQuizFlowService {

    private static final Logger log = LoggerFactory.getLogger(LawQuizFlowService.class);

    /** 法学通道出题意图触发词（子意图判定，零模型调用；命中即出题/续题） */
    private static final List<String> QUIZ_TRIGGERS = List.of(
            "考考我", "考我", "出题", "出几道", "出些题", "出点题", "出个题", "再出一", "再来一",
            "来几道", "来道题", "来一组", "做几道", "几道题", "刷题", "练一练", "练习一下",
            "下一题", "继续出", "继续练");

    /** 备考意图信号：命中才检索真题（日常咨询只查法条，不拿旧题干扰回答） */
    private static final List<String> EXAM_INTENT_SIGNALS = List.of(
            "真题", "试题", "备考", "刷题", "练习", "考点", "命题", "怎么考", "如何考", "考什么",
            "考试", "历年", "题型", "做题", "模考", "押题");

    /** 学情汇报意图触发词（画像 P1）：命中即纯代码聚合汇报，零模型调用 */
    private static final List<String> PROFILE_TRIGGERS = List.of(
            "学得怎么样", "学情", "我的弱点", "正确率", "练了多少", "答题情况", "掌握得如何");

    /** 一组题的题型轮换：单选→多选→不定项（贴合法考真实卷结构） */
    private static final List<String> QUIZ_TYPES = List.of("单选", "多选", "不定项");

    /** 主观题（案例分析）触发词：命中且带出题动作字才算出题意图（防「这个案例分析下」类咨询误伤） */
    private static final List<String> SUBJECTIVE_TRIGGERS = List.of(
            "主观题", "案例分析", "简答题", "简答", "论述");

    /** 主观出题动作字：与触发词组合判定（单字 contains） */
    private static final String SUBJECTIVE_ACTION_CHARS = "出来考练做试想";

    private final IdeaMessageRepository messageRepository;
    private final LawSearchService lawSearch;
    private final LawQuestionGenService lawQuestionGen;
    private final LawAnswerRecordRepository lawAnswerRecords;
    private final LawProfileService lawProfile;
    private final com.mengx.server.law.LawSubjectiveJudgeService lawSubjectiveJudge;
    private final ObjectMapper objectMapper;

    public LawQuizFlowService(
            IdeaMessageRepository messageRepository,
            LawSearchService lawSearch,
            LawQuestionGenService lawQuestionGen,
            LawAnswerRecordRepository lawAnswerRecords,
            LawProfileService lawProfile,
            com.mengx.server.law.LawSubjectiveJudgeService lawSubjectiveJudge,
            ObjectMapper objectMapper) {
        this.messageRepository = messageRepository;
        this.lawSearch = lawSearch;
        this.lawQuestionGen = lawQuestionGen;
        this.lawAnswerRecords = lawAnswerRecords;
        this.lawProfile = lawProfile;
        this.lawSubjectiveJudge = lawSubjectiveJudge;
        this.objectMapper = objectMapper;
    }

    /** 题组待答状态：all=信封全组，unanswered=按信封顺序过滤已答 */
    private record PendingQuiz(String topic, List<LawQuestionGenService.QuizQuestion> all,
                               List<LawQuestionGenService.QuizQuestion> unanswered) {}

    /**
     * 法学域子意图总入口：出题/重发、交卷、学情汇报任一命中即完成整轮，返回落库文本；
     * 都不是返回 null，调用方走正常法学问答。
     * <p>关流契约（强约束）：返回非 null 表示本方法已接管整轮，实现方必须保证最终发出
     * done 或 error 事件并调用 sink.complete()，否则前端会永久挂起等待；
     * 返回 null 时不得触碰 sink。新增分支务必沿用该契约。
     * <p>落库契约：返回文本即 assistant 落库内容——user 轮与 assistant 轮统一由编排器
     * 收口落库（顺序与幂等键由编排器保证），本类只落 question 信封等结构性行，不再写对话轮。
     */
    public String handle(FluxSink<IdeaChatService.StreamEvent> sink,
            IdeaSessionEntity session, List<IdeaChatService.InMessage> history) {
        String latest = latestUserText(history);
        if (latest.isBlank()) {
            return null;
        }
        PendingQuiz pending = pendingQuiz(session);
        if (isSubjectiveTrigger(latest)) {
            // 主观意图先于客观触发词判定（「考我一道案例分析」含「考我」，应出主观而非客观组）
            if (pending != null && !pending.unanswered().isEmpty()) {
                return resendQuiz(sink, session, pending);
            }
            return newSubjectiveQuiz(sink, session, history, latest, pending);
        }
        if (isQuizTrigger(latest)) {
            if (pending != null && !pending.unanswered().isEmpty()) {
                return resendQuiz(sink, session, pending);
            }
            return newQuiz(sink, session, history, latest, pending);
        }
        if (latest.contains("交卷") && pending != null && !pending.unanswered().isEmpty()) {
            return judgeBatch(sink, session, pending, latest);
        }
        // 纯答案格式交卷：有未答题组时，单字母(A/B)、题号+字母(1A)、多题答案(1B 2AD 3C)均视为交卷
        if (pending != null && !pending.unanswered().isEmpty() && isAnswerSubmission(latest)) {
            return judgeBatch(sink, session, pending, latest);
        }
        return reportProfile(sink, session, history);
    }

    /**
     * 纯答案格式检测（仅在有未答题组时调用）：
     * - 单字母：A / B / C / D（默认对应第1题）
     * - 题号+字母：1A / 2:B / 3 C
     * - 多题答案：1B 2AD 3C / A B C
     * 非答案文本（如"这题选什么"、"你好"）返回 false，交给正常法学问答。
     */
    static boolean isAnswerSubmission(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.trim();
        // 纯字母串（1~4 个 A-D）：A / AB / ABC / ABD
        if (t.matches("[A-Da-d]{1,4}")) {
            return true;
        }
        // 题号+字母格式，至少匹配一组：1A / 1:A / 1 A / 1B 2AD 3C
        if (Pattern.compile("(\\d+)\\s*[:：]?\\s*([A-Da-d]+)").matcher(t).find()) {
            return true;
        }
        // 空格分隔的纯字母序列：A B C / A B D
        if (t.matches("[A-Da-d](\\s+[A-Da-d]){0,5}")) {
            return true;
        }
        return false;
    }

    /** 备考意图信号探测（公开静态：法学问答检索分支也用）：真题检索只在备考/刷题语境下触发 */
    public static boolean isExamIntent(String text) {
        return text != null && EXAM_INTENT_SIGNALS.stream().anyMatch(text::contains);
    }

    static boolean isQuizTrigger(String text) {
        return text != null && QUIZ_TRIGGERS.stream().anyMatch(text::contains);
    }

    /** 主观出题意图：触发词 + 动作字同时命中（「来道案例分析」出题，「这个案例分析下」咨询） */
    static boolean isSubjectiveTrigger(String text) {
        if (text == null) {
            return false;
        }
        boolean hit = SUBJECTIVE_TRIGGERS.stream().anyMatch(text::contains);
        return hit && text.chars().anyMatch(c -> SUBJECTIVE_ACTION_CHARS.indexOf(c) >= 0);
    }

    /** 主观交卷作答提取：「交卷：<作答全文>」→ 作答全文（冒号/空白前缀剥净；无「交卷」前缀原样返回） */
    static String extractSubjectiveAnswer(String submission) {
        if (submission == null) {
            return "";
        }
        int idx = submission.indexOf("交卷");
        String rest = idx >= 0 ? submission.substring(idx + 2) : submission;
        return rest.replaceFirst("^[：:\\s]+", "").trim();
    }

    /**
     * 交卷作答解析，支持三种格式：
     * ① 题号+字母：「1B 2AD 3C」「1:B 2:C」→ {1:B, 2:AD, 3:C}
     * ② 纯字母串：「ABC」「ABD」→ 按序对应未答题 {1:A, 2:B, 3:C}
     * ③ 空格分隔字母：「A B C」→ 按序对应未答题 {1:A, 2:B, 3:C}
     * 键=组内题号，值=字母串大写；同号后者覆盖；无匹配返回空。
     */
    static java.util.Map<Integer, String> parseSubmission(String submission) {
        java.util.Map<Integer, String> answers = new java.util.LinkedHashMap<>();
        if (submission == null || submission.isBlank()) {
            return answers;
        }
        String t = submission.trim();
        // 格式①：题号+字母（优先，最明确）
        Matcher m = Pattern.compile("(\\d+)\\s*[:：]?\\s*([A-Da-d]+)").matcher(t);
        while (m.find()) {
            answers.put(Integer.parseInt(m.group(1)), m.group(2).toUpperCase());
        }
        if (!answers.isEmpty()) {
            return answers;
        }
        // 格式②③：纯字母，按序映射到第 1..N 题
        String letters = t.replaceAll("\\s+", "");
        if (letters.matches("[A-Da-d]{1,6}")) {
            for (int i = 0; i < letters.length(); i++) {
                answers.put(i + 1, String.valueOf(letters.charAt(i)).toUpperCase());
            }
        }
        return answers;
    }

    /** 学情汇报（画像 P1）：纯代码聚合画像 + 模板输出，零模型调用；未命中返回 null */
    private String reportProfile(FluxSink<IdeaChatService.StreamEvent> sink,
            IdeaSessionEntity session, List<IdeaChatService.InMessage> history) {
        String latest = latestUserText(history);
        if (latest.isBlank() || PROFILE_TRIGGERS.stream().noneMatch(latest::contains)) {
            return null;
        }
        log.info("学情汇报流程开讲（sessionId={}）", session.getId());
        sink.next(new IdeaChatService.StreamEvent(IdeaChatService.StreamEvent.THINKING, "翻看你的答题簿…"));
        String report = lawProfile.report(session.getUserId());
        String reply = report != null ? report
                : "答题簿还空着——先练一组（说「出几道题」便是），再唤老朽替你盘账不迟。";
        sink.next(new IdeaChatService.StreamEvent(IdeaChatService.StreamEvent.CHUNK, reply));
        sink.next(new IdeaChatService.StreamEvent(IdeaChatService.StreamEvent.DONE, null));
        sink.complete();
        return reply;
    }

    /** 当前题组状态：最近一封 question 信封 − 该用户已答记录；无信封/解析失败返回 null（answer_records 为持久真相） */
    private PendingQuiz pendingQuiz(IdeaSessionEntity session) {
        try {
            List<IdeaMessageEntity> envelopes =
                    messageRepository.findBySessionIdAndRoleOrderByIdAsc(session.getId(), "question");
            if (envelopes.isEmpty()) {
                return null;
            }
            LawQuestionGenService.QuizEnvelope env =
                    lawQuestionGen.parseEnvelope(envelopes.get(envelopes.size() - 1).getContent());
            if (env == null || env.questions() == null || env.questions().isEmpty()) {
                return null;
            }
            List<Long> ids = env.questions().stream()
                    .map(LawQuestionGenService.QuizQuestion::id).toList();
            Set<Long> answered = lawAnswerRecords
                    .findByUserIdAndQuestionIdIn(session.getUserId(), ids).stream()
                    .map(LawAnswerRecordEntity::getQuestionId)
                    .collect(java.util.stream.Collectors.toSet());
            List<LawQuestionGenService.QuizQuestion> unanswered = env.questions().stream()
                    .filter(q -> !answered.contains(q.id())).toList();
            return new PendingQuiz(env.topic(), env.questions(), unanswered);
        } catch (Exception e) {
            log.warn("题组状态解析失败（不影响问答）：{}", e.getMessage());
            return null;
        }
    }

    /** 出题：检索佐证 → 出题官逐题生成逐题下发（首题约 15 秒可见，不必等全组）→ 信封落库（历史恢复以此为准）。
     *  返回落库文本（intro 或含歇笔提示），对话轮由编排器统一收口 */
    private String newQuiz(FluxSink<IdeaChatService.StreamEvent> sink, IdeaSessionEntity session,
            List<IdeaChatService.InMessage> history, String triggerText, PendingQuiz pending) {
        log.info("出题流程开讲（sessionId={}，trigger={}）", session.getId(), triggerText);
        sink.next(new IdeaChatService.StreamEvent(IdeaChatService.StreamEvent.THINKING, "出题官正在拟题，好题约需半盏茶功夫，逐题呈上…"));
        try {
            // 触发词剥净后没剩主题（如"继续出"），沿用上一组主题
            String topic = triggerText;
            for (String t : QUIZ_TRIGGERS) {
                topic = topic.replace(t, "");
            }
            if (topic.replace("帮", "").replace("我", "").replace("请", "").trim().length() < 2
                    && pending != null && pending.topic() != null) {
                topic = pending.topic();
            }
            List<LawSearchService.SearchHit> refs =
                    lawSearch.search(triggerText, lawCodeHint(history));
            // 真题参照：按主题捞相近真题交出题官揣摩风格与干扰项套路（检索失败降级为无参照，不阻断出题）
            List<com.mengx.server.law.LawQuestionEntity> examRefs = lawSearch.searchExamQuestions(
                    topic == null || topic.isBlank() ? triggerText : topic, 2);
            if (!examRefs.isEmpty()) {
                sink.next(new IdeaChatService.StreamEvent(IdeaChatService.StreamEvent.THINKING,
                        "翻出 " + examRefs.size() + " 道相近真题作参照…"));
            }
            // 学情注入（画像 P1）：有答题记录时弱点加权、已稳降频（零记录不注入，出题官凭检索自选）
            String briefing = lawProfile.quizBriefing(session.getUserId());
            if (!briefing.isBlank()) {
                sink.next(new IdeaChatService.StreamEvent(IdeaChatService.StreamEvent.THINKING, "翻看你的学情簿，照弱点下题…"));
            }
            String intro = "好，这就出题——逐题呈上，各题点选你的答案，选满后按「交卷」，老朽当场讲评。";
            sink.next(new IdeaChatService.StreamEvent(IdeaChatService.StreamEvent.CHUNK, intro));
            List<LawQuestionGenService.QuizQuestion> questions = new ArrayList<>();
            List<String> usedPoints = new ArrayList<>();
            for (int no = 1; no <= LawQuestionGenService.QUESTION_COUNT; no++) {
                String type = QUIZ_TYPES.get((no - 1) % QUIZ_TYPES.size());
                LawQuestionGenService.QuizQuestion q =
                        lawQuestionGen.generateOne(topic, refs, examRefs, no, type, usedPoints, briefing);
                if (q == null) {
                    continue; // 单题失败跳过，凑不齐也成卷
                }
                if (q.knowledgePoints() != null) {
                    usedPoints.addAll(q.knowledgePoints());
                }
                questions.add(q);
                sink.next(new IdeaChatService.StreamEvent(IdeaChatService.StreamEvent.QUESTION, toJsonValue(q)));
            }
            if (questions.isEmpty()) {
                String msg = intro + "\n出题官暂时歇笔，稍后再唤一声便是。";
                sink.next(new IdeaChatService.StreamEvent(IdeaChatService.StreamEvent.CHUNK, "\n出题官暂时歇笔，稍后再唤一声便是。"));
                sink.next(new IdeaChatService.StreamEvent(IdeaChatService.StreamEvent.DONE, null));
                sink.complete();
                return msg;
            }
            messageRepository.save(new IdeaMessageEntity(session.getId(), "question",
                    lawQuestionGen.envelopeJson(new LawQuestionGenService.QuizEnvelope(topic, questions)), "law", null));
            log.info("出题流程收口（sessionId={}，{} 题）", session.getId(), questions.size());
            sink.next(new IdeaChatService.StreamEvent(IdeaChatService.StreamEvent.DONE, null));
            sink.complete();
            return intro;
        } catch (Exception e) {
            log.warn("出题流程异常：{}", e.getMessage());
            sink.next(new IdeaChatService.StreamEvent(IdeaChatService.StreamEvent.ERROR, "出题官暂时开小差了，稍后再试试～"));
            sink.complete();
            return "";
        }
    }

    /** 主观题（案例分析）出题：独立成组一次一道 → 作答全文交卷 → 判卷官批改（见 judgeSubjective）。
     *  返回落库文本，对话轮由编排器统一收口 */
    private String newSubjectiveQuiz(FluxSink<IdeaChatService.StreamEvent> sink, IdeaSessionEntity session,
            List<IdeaChatService.InMessage> history, String triggerText, PendingQuiz pending) {
        log.info("主观题出题流程开讲（sessionId={}，trigger={}）", session.getId(), triggerText);
        sink.next(new IdeaChatService.StreamEvent(IdeaChatService.StreamEvent.THINKING, "出题官正在拟一道案例分析题，好题约需半盏茶功夫…"));
        try {
            String topic = triggerText;
            for (String t : SUBJECTIVE_TRIGGERS) {
                topic = topic.replace(t, "");
            }
            topic = topic.replaceAll("[出来考练做试想道个一帮请我]", "").trim();
            if (topic.length() < 2 && pending != null && pending.topic() != null) {
                topic = pending.topic();
            }
            List<LawSearchService.SearchHit> refs =
                    lawSearch.search(triggerText, lawCodeHint(history));
            List<com.mengx.server.law.LawQuestionEntity> examRefs = lawSearch.searchExamQuestions(
                    topic == null || topic.isBlank() ? triggerText : topic, 2);
            String briefing = lawProfile.quizBriefing(session.getUserId());
            List<String> usedPoints = pending == null ? List.of()
                    : pending.all().stream()
                            .map(LawQuestionGenService.QuizQuestion::knowledgePoints)
                            .filter(java.util.Objects::nonNull)
                            .flatMap(List::stream).distinct().toList();
            LawQuestionGenService.QuizQuestion q = lawQuestionGen.generateSubjective(
                    topic, refs, examRefs, 1, usedPoints, briefing);
            if (q == null) {
                String msg = "\n出题官暂时歇笔，稍后再唤一声便是。";
                sink.next(new IdeaChatService.StreamEvent(IdeaChatService.StreamEvent.CHUNK, msg));
                sink.next(new IdeaChatService.StreamEvent(IdeaChatService.StreamEvent.DONE, null));
                sink.complete();
                return msg;
            }
            String intro = "好，这就出一道案例分析——写完你的答案按「交卷」，老朽请判卷官当场拆解。";
            sink.next(new IdeaChatService.StreamEvent(IdeaChatService.StreamEvent.CHUNK, intro));
            sink.next(new IdeaChatService.StreamEvent(IdeaChatService.StreamEvent.QUESTION, toJsonValue(q)));
            messageRepository.save(new IdeaMessageEntity(session.getId(), "question",
                    lawQuestionGen.envelopeJson(new LawQuestionGenService.QuizEnvelope(topic, List.of(q))), "law", null));
            log.info("主观题出题流程收口（sessionId={}，questionId={}）", session.getId(), q.id());
            sink.next(new IdeaChatService.StreamEvent(IdeaChatService.StreamEvent.DONE, null));
            sink.complete();
            return intro;
        } catch (Exception e) {
            log.warn("主观题出题流程异常：{}", e.getMessage());
            sink.next(new IdeaChatService.StreamEvent(IdeaChatService.StreamEvent.ERROR, "出题官暂时开小差了，稍后再试试～"));
            sink.complete();
            return "";
        }
    }

    /** 上组未答完又要求出题：重发未答题（不重复落信封，answer_records 已答的不再下发）；返回落库文本 */
    private String resendQuiz(FluxSink<IdeaChatService.StreamEvent> sink, IdeaSessionEntity session,
            PendingQuiz pending) {
        String intro = "上一组还剩 " + pending.unanswered().size()
                + " 题未答，先答完这些——各题点选后交卷便是。";
        try {
            sink.next(new IdeaChatService.StreamEvent(IdeaChatService.StreamEvent.CHUNK, intro));
            for (LawQuestionGenService.QuizQuestion q : pending.unanswered()) {
                sink.next(new IdeaChatService.StreamEvent(IdeaChatService.StreamEvent.QUESTION, toJsonValue(q)));
            }
            log.info("重发未答题（sessionId={}，剩 {} 题）", session.getId(), pending.unanswered().size());
            sink.next(new IdeaChatService.StreamEvent(IdeaChatService.StreamEvent.DONE, null));
            sink.complete();
            return intro;
        } catch (Exception e) {
            log.warn("重发题目异常：{}", e.getMessage());
            sink.next(new IdeaChatService.StreamEvent(IdeaChatService.StreamEvent.ERROR, "题目重发失败，稍后再试试～"));
            sink.complete();
            return "";
        }
    }

    /** 交卷判分入口：组内含主观题走判卷官批改，否则客观组批量字母判分；返回落库文本 */
    private String judgeBatch(FluxSink<IdeaChatService.StreamEvent> sink, IdeaSessionEntity session,
            PendingQuiz pending, String submission) {
        if (pending.all().stream().anyMatch(q -> "主观".equals(q.type()))) {
            return judgeSubjective(sink, session, pending, submission);
        }
        List<LawQuestionGenService.QuizQuestion> all = pending.all();
        java.util.Map<Integer, String> answers = parseSubmission(submission);
        try {
            if (answers.isEmpty()) {
                String hint = "卷上还没见着答案——各题点选选项，选满后再交卷便是。";
                sink.next(new IdeaChatService.StreamEvent(IdeaChatService.StreamEvent.CHUNK, hint));
                sink.next(new IdeaChatService.StreamEvent(IdeaChatService.StreamEvent.DONE, null));
                sink.complete();
                return hint;
            }
            StringBuilder fb = new StringBuilder("收卷，老朽逐题拆解——");
            int fresh = 0;
            int hit = 0;
            for (java.util.Map.Entry<Integer, String> e : answers.entrySet()) {
                int no = e.getKey();
                // 按题号匹配（生成失败跳题后列表稀疏，no 与下标会错位，不能按下标取）
                LawQuestionGenService.QuizQuestion q = all.stream()
                        .filter(x -> x.no() != null && x.no() == no)
                        .findFirst().orElse(null);
                if (q == null) {
                    continue;
                }
                LawQuestionGenService.JudgeOutcome o =
                        lawQuestionGen.judge(session.getUserId(), session.getId(), q.id(), e.getValue());
                fb.append("\n\n第").append(no).append("题：");
                if (o.alreadyAnswered()) {
                    fb.append("此前已答过，正解 ").append(o.expected()).append("。").append(o.analysis());
                } else if (o.correct()) {
                    fresh++;
                    hit++;
                    fb.append("答").append(e.getValue()).append("，正解 ").append(o.expected())
                            .append("，过了。").append(o.analysis());
                } else {
                    fresh++;
                    fb.append("答").append(e.getValue()).append("，正解 ").append(o.expected())
                            .append("，差矣。").append(o.analysis());
                }
            }
            fb.append("\n\n");
            int left = pending.unanswered().size() - fresh;
            if (left > 0) {
                fb.append("本组还剩 ").append(left).append(" 题未交——可补交（如「交卷：2A 3C」）。");
            } else if (fresh > 0) {
                fb.append("本组 ").append(fresh).append(" 题中了 ").append(hit).append(" 题。");
            } else {
                fb.append("这一卷都是旧题重交，不另计分。");
            }
            String feedback = fb.toString();
            sink.next(new IdeaChatService.StreamEvent(IdeaChatService.StreamEvent.CHUNK, feedback));
            sink.next(new IdeaChatService.StreamEvent(IdeaChatService.StreamEvent.DONE, null));
            sink.complete();
            log.info("交卷判分（sessionId={}，交 {} 题，中 {} 题）", session.getId(), fresh, hit);
            return feedback;
        } catch (Exception e) {
            log.warn("交卷判分异常：{}", e.getMessage());
            sink.next(new IdeaChatService.StreamEvent(IdeaChatService.StreamEvent.ERROR, "讲评官暂时开小差了，稍后再试试～"));
            sink.complete();
            return "";
        }
    }

    /** 主观题交卷批改：「交卷：<作答全文>」→ 判卷官逐采分点批改 → 讲评（得分/逐点判定/总评/参考答案）；
     *  返回落库文本 */
    private String judgeSubjective(FluxSink<IdeaChatService.StreamEvent> sink, IdeaSessionEntity session,
            PendingQuiz pending, String submission) {
        String answer = extractSubjectiveAnswer(submission);
        try {
            if (answer.isBlank()) {
                String hint = "卷上还没见着字——写上你的答案再交卷便是。";
                sink.next(new IdeaChatService.StreamEvent(IdeaChatService.StreamEvent.CHUNK, hint));
                sink.next(new IdeaChatService.StreamEvent(IdeaChatService.StreamEvent.DONE, null));
                sink.complete();
                return hint;
            }
            sink.next(new IdeaChatService.StreamEvent(IdeaChatService.StreamEvent.THINKING, "判卷官正在阅卷，约需半盏茶功夫…"));
            LawQuestionGenService.QuizQuestion q = pending.all().stream()
                    .filter(x -> "主观".equals(x.type())).findFirst().orElse(null);
            com.mengx.server.law.LawSubjectiveJudgeService.GradeOutcome o = q == null ? null
                    : lawSubjectiveJudge.grade(session.getUserId(), session.getId(), q.id(), answer);
            if (o == null) {
                String msg = "判卷官阅卷遇到波折，稍后再交一次便是。";
                sink.next(new IdeaChatService.StreamEvent(IdeaChatService.StreamEvent.CHUNK, msg));
                sink.next(new IdeaChatService.StreamEvent(IdeaChatService.StreamEvent.DONE, null));
                sink.complete();
                return msg;
            }
            StringBuilder fb = new StringBuilder("收卷，判卷官拆解如下——\n\n");
            if (o.alreadyAnswered()) {
                fb.append("此题此前已批过，上次 ").append(o.score()).append("/").append(o.total())
                        .append(" 分。");
            } else {
                boolean pass = o.total() != null && o.total() > 0
                        && o.score() != null && o.score() * 100 / o.total() >= 60;
                fb.append(o.score()).append("/").append(o.total()).append(" 分，")
                        .append(pass ? "过了。" : "未到及格线。");
                for (com.mengx.server.law.LawSubjectiveJudgeService.PointVerdict p : o.points()) {
                    fb.append("\n").append(verdictMark(p.verdict())).append(" ")
                            .append(p.point()).append("：").append(p.comment());
                }
                if (!o.summary().isBlank()) {
                    fb.append("\n\n总评：").append(o.summary());
                }
                if (!o.missing().isBlank() && !"无".equals(o.missing())) {
                    fb.append("\n遗漏要点：").append(o.missing());
                }
            }
            if (!o.referenceAnswer().isBlank()) {
                fb.append("\n\n参考答案：").append(o.referenceAnswer());
            }
            fb.append("\n\n想再练一道，说「继续出」便是。");
            String feedback = fb.toString();
            sink.next(new IdeaChatService.StreamEvent(IdeaChatService.StreamEvent.CHUNK, feedback));
            sink.next(new IdeaChatService.StreamEvent(IdeaChatService.StreamEvent.DONE, null));
            sink.complete();
            log.info("主观题交卷批改（sessionId={}，questionId={}，{}）",
                    session.getId(), q == null ? null : q.id(),
                    o.alreadyAnswered() ? "重交" : o.score() + "/" + o.total());
            return feedback;
        } catch (Exception e) {
            log.warn("主观题交卷批改异常：{}", e.getMessage());
            sink.next(new IdeaChatService.StreamEvent(IdeaChatService.StreamEvent.ERROR, "判卷官暂时开小差了，稍后再试试～"));
            sink.complete();
            return "";
        }
    }

    /** 采分点判定符号：hit=✓ / partial=△ / miss=✗ */
    private static String verdictMark(String verdict) {
        return switch (verdict == null ? "" : verdict) {
            case "hit" -> "✓";
            case "partial" -> "△";
            default -> "✗";
        };
    }

    // ---------- 本地小工具（与 IdeaChatService 各自持有，避免跨服务耦合） ----------

    /** 意图判定用：取 history 中最后一条 user 消息文本（无则空串） */
    private String latestUserText(List<IdeaChatService.InMessage> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("user".equals(history.get(i).role())) {
                return history.get(i).content();
            }
        }
        return "";
    }

    /** 编号路指代解析：「那第20条呢」没写法名时，取最近对话中出现过的法名作检索提示 */
    private String lawCodeHint(List<IdeaChatService.InMessage> history) {
        int from = Math.max(0, history.size() - 8);
        for (int i = history.size() - 1; i >= from; i--) {
            String code = lawSearch.detectLawCode(history.get(i).content());
            if (code != null) {
                return code;
            }
        }
        return null;
    }

    /** 通用 JSON 序列化（question 事件等） */
    private String toJsonValue(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "";
        }
    }
}
