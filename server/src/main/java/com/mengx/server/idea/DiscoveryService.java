package com.mengx.server.idea;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mengx.server.idea.agent.CoCreationTurn;
import com.mengx.server.idea.agent.IdeaAgentFactory;
import com.mengx.server.llm.LlmUsage;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 需求预澄清服务（human-in-the-loop requirements discovery loop）：
 * 主对话需求澄清阶段（cocreation 领域）每轮由需求预澄清员（CoCreation）在用户发言当轮
 * 同步推演——一次调用同时维护全量需求简报（用户口径，未聊到的节标「待澄清」）
 * 与本轮议程（聚焦点、建议问法+选项、建议方案、边界判定、移交判定、关键项留痕）。
 * 推演结论随轮以 discovery 状态消息存档（推演输出原样 JSON，SSE design 事件当轮直发前端简报面板）：
 * 注入领域顾问统一经 renderForHost 渲染为文本，喂卡片装配器经 renderBriefForComposer。
 * 用户同意移交（handoffConfirmed）后由开发线（forge）子对话接管后续澄清与 PRD 产出。
 * 用户全程只感知 Host——预澄清员不发言、不露面，转述权完全归 Host。
 * 本类只做编排（回灌截取、单官调用与退避重试、存档、渲染）；prompt 与模型调用归 idea.agent 包。
 */
@Service
public class DiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryService.class);

    /** 共创领域通道名（与 CoCreationService.CHANNEL 同值；自建常量避免与其循环依赖） */
    private static final String CHANNEL_COCREATION = "cocreation";

    private final IdeaAgentFactory agents;
    private final IdeaMessageRepository messageRepository;
    private final ObjectMapper counselMapper;

    public DiscoveryService(IdeaAgentFactory agents, IdeaMessageRepository messageRepository) {
        this.agents = agents;
        this.messageRepository = messageRepository;
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.counselMapper = mapper;
    }

    /**
     * 单官同步推演（普通轮约 10~15 秒、纯确认轮 3~5 秒）并立即存档，
     * 返回状态存档原文（JSON：注入领域顾问经 renderForHost，SSE design 事件直发前端）。
     */
    public String composeAndArchive(Long sessionId, String context) {
        String stored = compose(sessionId, context);
        if (stored != null) {
            archive(sessionId, stored);
        }
        return stored;
    }

    /**
     * 只推演、不落库：返回状态存档原文（推演输出原样 JSON），两次尝试均失败返回 null。
     * 上一版简报回灌预澄清员，让简报随轮累积演进；
     * 模型异常（含 1302 限流）与输出不完整（brief 缺失）均 2 秒退避后重试一次。
     */
    public String compose(Long sessionId, String context) {
        long startAt = System.currentTimeMillis();
        String draftText = recapOf(latestState(sessionId));
        LlmUsage.set("counsel"); // 用量观测：需求预澄清场景标记（finally 保证标记必清）
        CoCreationTurn turn = null;
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
                String reply = null;
                try {
                    reply = agents.coCreator().reason(draftText, context);
                    CoCreationTurn result = parseTurn(reply);
                    if (result != null && result.brief() != null) {
                        turn = result;
                        break;
                    }
                    log.warn("预澄清输出不完整（第 {} 次，brief 缺失），原始输出留痕（{} 字符）：[{}]",
                            attempt + 1, reply.length(), reply);
                } catch (Exception e) {
                    log.warn("预澄清调用失败（第 {} 次）：{}", attempt + 1, e.getMessage(), e);
                    if (reply != null) {
                        log.warn("预澄清员原始输出留痕（第 {} 次，{} 字符）：[{}]", attempt + 1, reply.length(), reply);
                    }
                }
            }
        } finally {
            LlmUsage.clear();
        }
        if (turn == null) {
            log.warn("需求简报生成失败（sessionId={}，单官两次均败，耗时 {}ms）",
                    sessionId, System.currentTimeMillis() - startAt);
            return null;
        }
        log.info("需求简报生成（sessionId={}，聚焦={}，纯确认={}，移交就绪={}，移交确认={}，待开发线问题 {} 项，耗时 {}ms）",
                sessionId, turn.focus(), Boolean.TRUE.equals(turn.ack()),
                Boolean.TRUE.equals(turn.handoffReady()), Boolean.TRUE.equals(turn.handoffConfirmed()),
                turn.brief() == null || turn.brief().openItems() == null
                        ? 0 : turn.brief().openItems().size(),
                System.currentTimeMillis() - startAt);
        // 存档推演输出原样 JSON（SSE design 事件直发前端简报面板）；注入顾问/喂装配器走渲染方法
        try {
            return counselMapper.writeValueAsString(turn);
        } catch (Exception e) {
            log.warn("状态序列化失败（本轮状态不存档）：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 预澄清员原始输出 → 单轮结论：剥 markdown 围栏后容错解析（未知字段忽略、
     * List 字段收字符串自动归一，见 {@link com.mengx.server.idea.agent.StringOrListDeserializer}）。
     * 解析失败先走一次尾部塌陷自愈（{@link #salvageTruncatedTail}：glm 生成长嵌套 JSON
     * 偶发尾部闭合括号错乱/缺失，finish=stop 但根对象未闭合，非截断）；自愈不过再向上抛，
     * 由 compose 的重试循环统一处理（退避 2 秒再试一次）。
     */
    private CoCreationTurn parseTurn(String reply) throws Exception {
        if (reply == null || reply.isBlank()) {
            throw new IllegalArgumentException("预澄清员输出为空");
        }
        String json = IdeaProposalService.stripFences(reply.trim());
        try {
            return counselMapper.readValue(json, CoCreationTurn.class);
        } catch (JsonProcessingException e) {
            CoCreationTurn salvaged = salvageTruncatedTail(json);
            if (salvaged == null) {
                throw e;
            }
            log.warn("推演输出尾部闭合塌陷，括号账本自愈成功（原 {} 字符）", json.length());
            return salvaged;
        }
    }

    /**
     * 尾部塌陷自愈：先括号账本修复成合法 JSON，再按契约重组与语义自检。
     * 契约顶层字段（keyLog）塌陷进子对象（如 brief）时上提回顶层——
     * 未知字段忽略会静默吞掉深层字段；自检不过（brief 缺失，或原文含关键项
     * 但解析结果为空，视为修复破坏了语义）返回 null，交由上层重试。
     */
    private CoCreationTurn salvageTruncatedTail(String json) {
        String balanced = repairBrackets(json);
        if (balanced == null) {
            return null;
        }
        try {
            JsonNode root = counselMapper.readTree(balanced);
            if (!(root instanceof ObjectNode obj)) {
                return null;
            }
            if (!obj.has("keyLog")) {
                JsonNode host = obj.findParent("keyLog");
                if (host instanceof ObjectNode hostObj) {
                    obj.set("keyLog", hostObj.remove("keyLog"));
                }
            }
            CoCreationTurn turn = counselMapper.treeToValue(obj, CoCreationTurn.class);
            if (turn.brief() == null) {
                return null;
            }
            boolean hadKeyLog = json.contains("\"keyLog\"");
            if (hadKeyLog && (turn.brief().keyLog() == null || turn.brief().keyLog().isEmpty())) {
                return null;
            }
            return turn;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 字符串感知的括号账本修复（保守，只救尾部）：跳过字符串字面量走查、维护 opener 栈。
     * 闭合符与栈顶不配对（如用 ] 闭合 {）→ 仅当其后只剩空白（串尾）才把该符替换为
     * 栈顶期望的闭合符；走查到结尾栈非空（纯缺失）→ 按栈序 LIFO 补齐闭合符。
     * 中段错乱（错型闭合符后还有内容）或栈空仍见闭合符 → 返回 null，交由上层重试。
     */
    private static String repairBrackets(String json) {
        StringBuilder out = new StringBuilder(json.length() + 8);
        Deque<Character> stack = new ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;
        int n = json.length();
        for (int i = 0; i < n; i++) {
            char c = json.charAt(i);
            if (inString) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
                continue;
            }
            if (c == '{' || c == '[') {
                stack.push(c);
                out.append(c);
                continue;
            }
            if (c == '}' || c == ']') {
                if (stack.isEmpty() || !matches(stack.peek(), c)) {
                    if (stack.isEmpty() || !restIsBlank(json, i + 1)) {
                        return null;
                    }
                    out.append(stack.peek() == '{' ? '}' : ']');
                    stack.pop();
                    return appendClosers(out, stack); // 其后全空白，直接丢弃并按栈补齐
                }
                stack.pop();
                out.append(c);
                continue;
            }
            out.append(c);
        }
        return appendClosers(out, stack);
    }

    private static boolean matches(char opener, char closer) {
        return (opener == '{' && closer == '}') || (opener == '[' && closer == ']');
    }

    /** [from, 末尾) 是否只剩空白 */
    private static boolean restIsBlank(String json, int from) {
        for (int i = from; i < json.length(); i++) {
            if (!Character.isWhitespace(json.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** 按栈序（LIFO）补齐全部缺失闭合符 */
    private static String appendClosers(StringBuilder out, Deque<Character> stack) {
        while (!stack.isEmpty()) {
            out.append(stack.pop() == '{' ? '}' : ']');
        }
        return out.toString();
    }

    /** 状态存档落库（失败只告警，不影响本轮回复）；role 固定 counsel，通道归共创领域 */
    public void archive(Long sessionId, String stored) {
        try {
            messageRepository.save(new IdeaMessageEntity(sessionId, "counsel", stored, CHANNEL_COCREATION, null));
        } catch (Exception e) {
            log.warn("需求简报存档失败（不影响本轮回复）：{}", e.getMessage());
        }
    }

    /** 存档 → 注入领域顾问的简报文本（简报快照 + 本轮推演结论 + 移交信号） */
    public String renderForHost(String stored) {
        CoCreationTurn turn = parse(stored);
        return turn == null ? "" : render(turn);
    }

    /** 存档 → 喂卡片装配器的需求简报文本（仅简报各节，不含本轮议程与移交指令） */
    public String renderBriefForComposer(String stored) {
        CoCreationTurn turn = parse(stored);
        if (turn == null || turn.brief() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        renderBriefInto(sb, turn.brief());
        return sb.toString().trim();
    }

    /** 存档 → 用户本轮是否已明确同意移交（移交状态驱动的唯一触发信号） */
    public boolean isHandoffConfirmed(String stored) {
        CoCreationTurn turn = parse(stored);
        return turn != null && Boolean.TRUE.equals(turn.handoffConfirmed());
    }

    /**
     * 存档 → 用户本轮是否被判为与本场访谈无关的独立请求（暂挂判定）。
     * 热窗内强制派发路径据此转 Host 直答；判不准时默认为 false（往留收）。
     */
    public boolean isDivertEscape(String stored) {
        CoCreationTurn turn = parse(stored);
        return turn != null && turn.divert() != null && Boolean.TRUE.equals(turn.divert().escape());
    }

    /** 存档 → 暂挂判定原因（注入 Host 直答提示，让 Host 知道访谈为何暂挂）；无判定返回 null */
    public String divertReason(String stored) {
        CoCreationTurn turn = parse(stored);
        return turn == null || turn.divert() == null ? null : turn.divert().reason();
    }

    /** 访谈热窗：最新推演距此刻在该时长内视为访谈仍在进行（含刚被 Host 逃逸的自愈场景） */
    private static final long INTERVIEW_HOT_WINDOW_SECONDS = 1800;

    /**
     * 访谈活跃判定：最新推演未移交确认、且距此刻在热窗内——访谈进行中，
     * 或访谈刚被 Host 直答逃逸（活跃帧已切回 host，但简报未移交）。
     * 路由强提示（拉回共创，自愈逃逸）与提案闸门（访谈期间不重复提案）共用本判定。
     */
    public boolean interviewHot(Long sessionId) {
        List<IdeaMessageEntity> rows = messageRepository.findBySessionIdAndRoleOrderByIdAsc(sessionId, "counsel");
        if (rows.isEmpty()) {
            return false;
        }
        IdeaMessageEntity latest = rows.get(rows.size() - 1);
        return !isHandoffConfirmed(latest.getContent())
                && latest.getCreatedAt().isAfter(Instant.now().minusSeconds(INTERVIEW_HOT_WINDOW_SECONDS));
    }

    /** 推演结论渲染为注入领域顾问的简报（严禁向用户暴露内部流程字眼） */
    private String render(CoCreationTurn turn) {
        StringBuilder sb = new StringBuilder("""
                【需求简报（Requirement Brief）·内部工作文档】仅供你参考吸收，以你自己的口吻与判断自然表达，\
                该采纳的采纳、该保留意见的保留意见；严禁向用户提及内部流程与工作文档：
                """);
        if (turn.brief() != null) {
            renderBriefInto(sb, turn.brief());
        }
        sb.append("\n【本轮推演结论】（这一轮只推这一处，一次只推进一步；问法仅供参考，用你自己的口吻）\n");
        sb.append("推进：").append(or(turn.focus(), "需求简报"));
        if (!isBlank(turn.question())) {
            sb.append("；建议问法：").append(turn.question());
        }
        if (turn.options() != null && !turn.options().isEmpty()) {
            sb.append("；可给用户的选项：").append(String.join(" / ", turn.options()))
                    .append("（把选项自然说给用户挑，用户也可以不选、自己说）");
        }
        if (!isBlank(turn.proposal())) {
            sb.append("\n【建议方案】当前待澄清点可转述给用户点头：").append(turn.proposal());
        }
        CoCreationTurn.Validity v = turn.validity();
        if (v != null && !isBlank(v.target())) {
            sb.append("\n【边界判定】用户提出「").append(v.target()).append("」，判定：")
                    .append(or(v.verdict(), "合理"));
            if (!isBlank(v.reason())) {
                sb.append("（").append(v.reason()).append("）");
            }
            if ("不合理".equals(v.verdict()) && !isBlank(v.alternative())) {
                sb.append("；替代方案：").append(v.alternative())
                        .append("——如实转述判定与替代方案，请用户表态，不要替他决定");
            }
        }
        if (Boolean.TRUE.equals(turn.handoffConfirmed())) {
            sb.append("\n【用户已同意移交】需求澄清已完成——只需接一句确认，告知用户这个想法会交给开发线团队跟进，")
                    .append("不要再追问、不要再抛新问题。\n");
        } else if (Boolean.TRUE.equals(turn.handoffReady())) {
            sb.append("\n【移交信号】意图已清楚——用几句话把简报要点复述给用户，然后问「接下来的细节交给开发线团队跟你聊，可以吗？」\n");
        } else if (Boolean.TRUE.equals(turn.ack())) {
            sb.append("\n【纯确认轮】用户本轮发言是纯确认，简报无新增——先用一句话收格确认，随即在同一句回复里抛出下一个问题，不要再追问已确认的内容。\n");
        }
        return sb.toString().trim();
    }

    /** 需求简报段（用户口径，各节与 PRD 章节对应）；「待澄清」= 还没聊到 */
    private void renderBriefInto(StringBuilder sb, CoCreationTurn.Brief brief) {
        sb.append("\n【需求简报】（这个想法目前聊到的样子；「待澄清」就是还没聊到的）\n");
        sb.append("- 一句话定位：").append(or(brief.summary())).append('\n');
        sb.append("- 背景与动机：").append(or(brief.motivation())).append('\n');
        sb.append("- 用户想要什么：").append(list(brief.wants())).append('\n');
        sb.append("- 使用者与场景：").append(or(brief.audience())).append('\n');
        sb.append("- 呈现与体验期望：").append(or(brief.expectations())).append('\n');
        sb.append("- 硬性要求：").append(or(brief.constraints())).append('\n');
        if (brief.openItems() != null && !brief.openItems().isEmpty()) {
            sb.append("【留给开发线的问题】（移交后开发线据此继续澄清）\n");
            for (CoCreationTurn.OpenItem o : brief.openItems()) {
                if (o == null || isBlank(o.question())) {
                    continue;
                }
                sb.append("- ").append(o.question());
                if (!isBlank(o.why())) {
                    sb.append("（为什么问：").append(o.why()).append("）");
                }
                sb.append('\n');
            }
        }
        if (brief.disputes() != null && !brief.disputes().isEmpty()) {
            sb.append("【待定分歧】（建议过但用户未表态、或被判不合理的原主张，等用户表态）\n");
            for (CoCreationTurn.Dispute d : brief.disputes()) {
                if (d == null || isBlank(d.target())) {
                    continue;
                }
                sb.append("- ").append(d.target());
                if (!isBlank(d.alternative())) {
                    sb.append("（替代方案：").append(d.alternative()).append("）");
                }
                sb.append('\n');
            }
        }
        if (brief.keyLog() != null && !brief.keyLog().isEmpty()) {
            sb.append("【关键项留痕】\n");
            for (CoCreationTurn.KeyItem k : brief.keyLog()) {
                if (k == null || isBlank(k.content())) {
                    continue;
                }
                sb.append("- [").append(or(k.type(), "诉求")).append('|')
                        .append("resolved".equals(k.status()) ? "已解决" : "待处理").append("] ")
                        .append(k.content());
                if (!isBlank(k.conclusion())) {
                    sb.append("（结论：").append(k.conclusion()).append("）");
                }
                sb.append('\n');
            }
        }
    }

    /** 存档 → 回灌预澄清员的上一版简报（仅简报各节；议程与注入说明不回灌） */
    private String recapOf(String stored) {
        CoCreationTurn turn = parse(stored);
        if (turn == null || turn.brief() == null) {
            return "（还没有需求简报——第一次推演）";
        }
        StringBuilder sb = new StringBuilder();
        renderBriefInto(sb, turn.brief());
        String recap = sb.toString().trim();
        return recap.isEmpty() ? "（还没有需求简报——第一次推演）" : recap;
    }

    /** 存档 → 推演结论；非 JSON 或解析失败返回 null */
    private CoCreationTurn parse(String stored) {
        if (stored == null || stored.isBlank() || !stored.trim().startsWith("{")) {
            return null;
        }
        try {
            return counselMapper.readValue(stored.trim(), CoCreationTurn.class);
        } catch (Exception e) {
            log.warn("需求简报存档解析失败（按无状态处理）：{}", e.getMessage());
            return null;
        }
    }

    /** 最近一份状态存档原文；无存档返回 null */
    private String latestState(Long sessionId) {
        List<IdeaMessageEntity> list = messageRepository.findBySessionIdAndRoleOrderByIdAsc(sessionId, "counsel");
        return list.isEmpty() ? null : list.get(list.size() - 1).getContent();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String or(String s) {
        return isBlank(s) ? "（待澄清）" : s;
    }

    private static String or(String s, String fallback) {
        return isBlank(s) ? fallback : s;
    }

    /** 数组项以分号拼接为一句（空数组视为待澄清） */
    private static String list(List<String> items) {
        return items == null || items.isEmpty() ? "（待澄清）" : String.join("；", items);
    }
}
