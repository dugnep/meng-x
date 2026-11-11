package com.mengx.server.forge;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mengx.server.idea.IdeaMessageEntity;
import com.mengx.server.idea.IdeaMessageRepository;
import com.mengx.server.idea.IdeaSessionEntity;
import com.mengx.server.idea.IdeaSessionRepository;
import com.mengx.server.llm.LlmUsage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 传话工序 herald（方案 §6.3/§5.2.1）：仙官分身与客人之间的唯一传话人。
 * 职责铁律——只做形态路由不做内容理解：三分类（glm-4-flash）判定客人消息去留，
 * 答案的语义消化永远归抛出岗；挂起问题按分身口吻转述落库，用户看不到星君/黑板/JSON。
 *
 * 分身人设为裁剪版（§5.1，不复用完整 HOST_PROMPT）：自称与本体一致（老朽），
 * 明确「正在营造某道场」，只答工部问题，闲聊引导回主线。
 * 分身消息一律以 HeraldEnvelope 信封落 idea_messages（role=assistant）：
 * type=notice 开场播报 / question 挂起问题（前端出选项按钮）/ reply 即时回复；
 * backlog 记 role=backlog（气泡流白名单外，不渲染，终审与审计可查）。
 */
@Service
public class ForgeHeraldService {

    private static final Logger log = LoggerFactory.getLogger(ForgeHeraldService.class);

    /** 三分类判定值（§5.2.1） */
    public static final String INTENT_ANSWER = "answer";
    public static final String INTENT_DEMAND = "demand";
    public static final String INTENT_CHAT = "chat";

    /** 分身消息信封（idea_messages.content）：rendered 为可直接展示的分身全文 */
    public record HeraldEnvelope(String type, String rendered, String questionId,
                                 List<String> options, String defaultValue) {

        static HeraldEnvelope notice(String rendered) {
            return new HeraldEnvelope("notice", rendered, null, List.of(), null);
        }

        static HeraldEnvelope question(PendingQuestion q, String rendered) {
            return new HeraldEnvelope("question", rendered, q.id(),
                    q.options() == null ? List.of() : q.options(), q.defaultValue());
        }

        static HeraldEnvelope reply(String rendered) {
            return new HeraldEnvelope("reply", rendered, null, List.of(), null);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 三分类 prompt（flash 低延迟小任务，与路由 L2 同档同模式；失败兜底 answer，误分最坏多一轮引导不污染黑板） */
    private static final String CLASSIFY_PROMPT = """
            你是开发线传话官。开发线正在施工一个应用，此前有一个问题正等用户回答，用户刚发来一条消息。
            判断这条消息的意图，严格只输出 JSON（禁止 markdown 代码块、禁止解释文字）：
            {"intent":"answer"}

            intent 三选一：
            - answer：在回答/回应那个挂起问题（直接给答案、给选项内容或序号、「都行」「你定」这类表态都算）
            - demand：不是在答那个问题，而是提出了新需求、补充想法或吐槽
            - chat：无关闲聊、寒暄，或在与施工无关地聊别的
            """;

    private final ObjectProvider<ChatModel> routeModelProvider;
    private final IdeaMessageRepository messageRepository;
    private final IdeaSessionRepository sessionRepository;

    public ForgeHeraldService(
            @Qualifier("routeChatModel") ObjectProvider<ChatModel> routeModelProvider,
            IdeaMessageRepository messageRepository,
            IdeaSessionRepository sessionRepository) {
        this.routeModelProvider = routeModelProvider;
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
    }

    /**
     * 客人在挂起问题下的自由文本三分类（§5.2.1：仅此场景触发意图识别，选项按钮零歧义不走此口）。
     * 失败/超时兜底 answer——有挂起问题时大概率在答题，误分只多一轮引导、不污染黑板（§13）。
     */
    public String classify(PendingQuestion question, String message) {
        ChatModel model = routeModelProvider.getIfAvailable();
        if (model == null) {
            log.warn("传话三分类模型未配置，按 answer 兜底");
            return INTENT_ANSWER;
        }
        long startAt = System.currentTimeMillis();
        LlmUsage.set("forge.herald");
        try {
            String input = "【挂起问题】\n" + question.question() + "\n\n【用户消息】\n" + message;
            String reply = model.chat(List.of(
                    SystemMessage.from(CLASSIFY_PROMPT),
                    UserMessage.from(input))).aiMessage().text();
            String intent = parseIntent(reply);
            if (intent == null) {
                // 输出无法识别：与调用失败同口径兜底 answer（大概率在答题，误分只多一轮引导）
                log.warn("传话三分类输出无法识别（按 answer 兜底，耗时 {}ms）",
                        System.currentTimeMillis() - startAt);
                return INTENT_ANSWER;
            }
            log.info("传话三分类：{} → {}（耗时 {}ms）", abbreviate(message, 30),
                    intent, System.currentTimeMillis() - startAt);
            return intent;
        } catch (Exception e) {
            log.warn("传话三分类失败（按 answer 兜底，耗时 {}ms）：{}",
                    System.currentTimeMillis() - startAt, e.getMessage());
            return INTENT_ANSWER;
        } finally {
            LlmUsage.clear();
        }
    }

    /** 剥围栏 + 解析 intent；无法识别返回 null（由调用方兜底） */
    private static String parseIntent(String reply) {
        if (reply == null) {
            return null;
        }
        String text = reply.trim();
        if (text.startsWith("```")) {
            int lineEnd = text.indexOf('\n');
            if (lineEnd > 0) {
                text = text.substring(lineEnd + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            String intent = MAPPER.readTree(text.substring(start, end + 1)).path("intent").asText("");
            intent = intent.trim().toLowerCase(Locale.ROOT);
            return switch (intent) {
                case "answer", "demand", "chat" -> intent;
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }

    /** 开工开场播报（notice 信封，开工事务内落库） */
    public void postOpening(Long sessionId, String cardTitle) {
        saveEnvelope(sessionId, HeraldEnvelope.notice(openingText(cardTitle)));
    }

    /** 挂起问题渲染成分身口吻落库（question 信封；编排器 WAIT 落定后调用，前端轮询可见并出选项按钮） */
    public void postQuestion(Long forgeJobId, PendingQuestion q) {
        Long sessionId = sessionOf(forgeJobId);
        if (sessionId == null) {
            log.warn("工单 {} 无造物线会话，挂起问题未能转述落库", forgeJobId);
            return;
        }
        saveEnvelope(sessionId, HeraldEnvelope.question(q, questionText(q)));
    }

    /** 分身即时回复落库（reply 信封；chat/stream 输出的同一句话，刷新后可见） */
    public void postReply(Long forgeJobId, String rendered) {
        Long sessionId = sessionOf(forgeJobId);
        if (sessionId != null) {
            saveEnvelope(sessionId, HeraldEnvelope.reply(rendered));
        }
    }

    /** 记 backlog（role=backlog，气泡流白名单外；终审时一并参详，§5.2.1） */
    public void saveBacklog(Long forgeJobId, String content) {
        Long sessionId = sessionOf(forgeJobId);
        if (sessionId != null) {
            messageRepository.save(new IdeaMessageEntity(sessionId, "backlog", content, "forge", null));
            log.info("工单 {} 记入 backlog：{}", forgeJobId, abbreviate(content, 40));
        }
    }

    // ---- 分身文案模板（零模型调用，低延迟稳定） ----

    /** 开工开场播报（notice 信封，开工事务内落库） */
    public String openingText(String cardTitle) {
        return "「" + cardTitle + "」已开工，开发线团队正在施工。"
                + "需要你定夺的地方我会在这里提问；你有新想法也随时说，终审时会一并参考。";
    }

    /** 挂起问题转述：正文 + 选项行 + 默认值提示（analyst 的问题已要求口语化，此处原样转述不加戏） */
    public String questionText(PendingQuestion q) {
        StringBuilder sb = new StringBuilder("施工中有一处需要你定夺：\n").append(q.question());
        if (q.options() != null && !q.options().isEmpty()) {
            sb.append('\n');
            for (int i = 0; i < q.options().size(); i++) {
                sb.append(i + 1).append("、").append(q.options().get(i)).append("  ");
            }
        }
        if (q.defaultValue() != null && !q.defaultValue().isBlank()) {
            sb.append("\n（拿不定可先按「").append(q.defaultValue()).append("」来，终审时再改。）");
        }
        return sb.toString();
    }

    /** 答题确认（方案 A 确认即关流）：resume 已在后台跑，新挂起问题以助理消息落库、前端轮询可见 */
    public String confirmText() {
        return "好，这就继续施工。有新进展我会在这里同步，你可以先去忙别的。";
    }

    /** 新需求/吐槽：记 backlog 后引导拉回挂起问题（§5.2.1 原文口径） */
    public String redirectText() {
        return "已记下，终审时会一并参考。先把当前这个定了吧？";
    }

    /** 无关闲聊：礼貌挡回主线，引导用户而非被用户带偏（§5.2.1） */
    public String deflectText(String cardTitle) {
        return "这里专注施工「" + cardTitle + "」，不方便闲聊；想聊别的请回主对话。";
    }

    /** RUNNING 中插话：不打断执行中的岗（竞态噩梦），消息落库 + 记 backlog，当前岗跑完后统一参详 */
    public String busyText() {
        return "正在施工中，你这条我先记下了，这轮忙完统一处理。";
    }

    /** 并发已答（CAS 被其他触发源拿走）：安抚不重复推进 */
    public String racingText() {
        return "这条已经在处理了，稍待，有进展我会在这里同步。";
    }

    /** 非等待态（REVIEW/BLOCKED/终态）收到消息：backlog + 对应状态说明 */
    public String statusText(String status, String note) {
        String extra = note == null || note.isBlank() ? "" : "：" + abbreviate(note, 80);
        return switch (status == null ? "" : status) {
            case ForgeJobEntity.STATUS_REVIEW -> "文档已出稿，现在待终审定夺。你这条先记下，终审时一并参考。";
            case ForgeJobEntity.STATUS_BLOCKED -> "施工遇阻暂停" + extra + "。你这条先记下，待工单重新推进时参考。";
            case ForgeJobEntity.STATUS_DONE -> "这个应用已施工完毕、文档已出稿。你这条记下，留给二期参考。";
            case ForgeJobEntity.STATUS_FAILED -> "施工中途出了问题" + extra + "，可从看板重试。你这条先记下。";
            case ForgeJobEntity.STATUS_CANCELLED -> "这个应用的施工已取消。你这条记下，留给后续想法参考。";
            default -> "工单当前状态：" + status + "。你这条我先记下了。";
        };
    }

    /** 工单 → 造物线会话（一工单一条 FORGE 会话；找不到返回 null，调用方自行降级） */
    private Long sessionOf(Long forgeJobId) {
        return sessionRepository.findByForgeJobIdIn(List.of(forgeJobId)).stream()
                .findFirst()
                .map(IdeaSessionEntity::getId)
                .orElse(null);
    }

    /** 信封落库：序列化失败退化为 rendered 纯文本（消息可见性优先于结构化） */
    private void saveEnvelope(Long sessionId, HeraldEnvelope envelope) {
        String content;
        try {
            content = MAPPER.writeValueAsString(envelope);
        } catch (Exception e) {
            content = envelope.rendered();
        }
        messageRepository.save(new IdeaMessageEntity(sessionId, "assistant", content, "forge", null));
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }
}
