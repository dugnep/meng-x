package com.mengx.server.idea;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mengx.server.idea.agent.CoCreationTurn;
import com.mengx.server.idea.agent.IdeaAgentFactory;
import com.mengx.server.llm.LlmUsage;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 需求澄清服务（human-in-the-loop requirements discovery loop）：
 * 发现模式下每轮由共创推演官（CoCreation）在用户发言当轮同步推演——
 * 一次调用同时维护规格快照（核心三要素 + 动态维度，维度由需求推演、随轮累积演进）
 * 与本轮议程（聚焦点、建议问法+选项、建议方案、合理性判定、收口信号、关键项留痕）。
 * 推演结论随轮以 discovery 状态消息存档（推演输出原样 JSON，SSE design 事件当轮直发前端营造图面板）：
 * 注入主对话代理/喂卡片装配器统一经 renderForHost 渲染为文本。
 * 用户全程只感知主对话代理一人——推演官不发言、不露面，转述权完全归前台。
 * 本类只做编排（回灌截取、单官调用与退避重试、存档、渲染）；prompt 与模型调用归 idea.agent 包。
 */
@Service
public class DiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryService.class);

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
     * 返回状态存档原文（JSON：注入主对话经 renderForHost，SSE design 事件直发前端）。
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
     * 上一版快照与状态（快照 + 待定决策 + 关键项 + 优先级）回灌推演官，让规格随轮累积演进；
     * 模型异常（含 1302 限流）与输出不完整（snapshot 缺失）均 2 秒退避后重试一次。
     */
    public String compose(Long sessionId, String context) {
        long startAt = System.currentTimeMillis();
        String draftText = recapOf(latestState(sessionId));
        LlmUsage.set("counsel"); // 用量观测：共创推演场景标记（finally 保证标记必清）
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
                try {
                    String reply = agents.coCreator().reason(draftText, context);
                    CoCreationTurn result = parseTurn(reply);
                    if (result != null && result.snapshot() != null) {
                        turn = result;
                        break;
                    }
                    log.warn("共创推演输出不完整（第 {} 次，snapshot 缺失）", attempt + 1);
                } catch (Exception e) {
                    log.warn("共创推演调用失败（第 {} 次）：{}", attempt + 1, e.getMessage(), e);
                }
            }
        } finally {
            LlmUsage.clear();
        }
        if (turn == null) {
            log.warn("需求澄清状态生成失败（sessionId={}，单官两次均败，耗时 {}ms）",
                    sessionId, System.currentTimeMillis() - startAt);
            return null;
        }
        log.info("需求澄清状态生成（sessionId={}，聚焦={}，纯确认={}，收口={}，定稿确认={}，待定决策 {} 项，耗时 {}ms）",
                sessionId, turn.focus(), Boolean.TRUE.equals(turn.ack()),
                Boolean.TRUE.equals(turn.closing()), Boolean.TRUE.equals(turn.closingConfirmed()),
                turn.decisions() == null ? 0 : turn.decisions().size(),
                System.currentTimeMillis() - startAt);
        // 存档推演输出原样 JSON（SSE design 事件直发前端营造图面板）；注入主对话/喂装配器走 renderForHost
        try {
            return counselMapper.writeValueAsString(turn);
        } catch (Exception e) {
            log.warn("状态序列化失败（本轮状态不存档）：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 推演官原始输出 → 单轮结论：剥 markdown 围栏后容错解析（未知字段忽略、
     * List 字段收字符串自动归一，见 {@link com.mengx.server.idea.agent.StringOrListDeserializer}）。
     * 解析异常向上抛，由 compose 的重试循环统一处理（退避 2 秒再试一次）。
     */
    private CoCreationTurn parseTurn(String reply) throws Exception {
        if (reply == null || reply.isBlank()) {
            throw new IllegalArgumentException("推演官输出为空");
        }
        return counselMapper.readValue(IdeaProposalService.stripFences(reply.trim()), CoCreationTurn.class);
    }

    /** 状态存档落库（失败只告警，不影响本轮回复）；role 固定 counsel */
    public void archive(Long sessionId, String stored) {
        try {
            messageRepository.save(new IdeaMessageEntity(sessionId, "counsel", stored));
        } catch (Exception e) {
            log.warn("设计简报存档失败（不影响本轮回复）：{}", e.getMessage());
        }
    }

    /** 存档 → 注入主对话代理的简报文本（快照 + 待定决策 + 关键项 + 本轮推演结论） */
    public String renderForHost(String stored) {
        CoCreationTurn turn = parse(stored);
        return turn == null ? "" : render(turn);
    }

    /** 存档 → 推演官给出的优先级建议（高/中/低）；无存档或无优先级返回 null */
    public String priorityOf(String stored) {
        CoCreationTurn turn = parse(stored);
        return turn == null ? null : turn.priority();
    }

    /** 存档 → 客人本轮是否已明确点头同意定稿（收口状态驱动的唯一触发信号） */
    public boolean isClosingConfirmed(String stored) {
        CoCreationTurn turn = parse(stored);
        return turn != null && Boolean.TRUE.equals(turn.closingConfirmed());
    }

    /** 状态渲染为注入主对话代理的简报（严禁向用户暴露内部流程字眼） */
    private String render(CoCreationTurn turn) {
        StringBuilder sb = new StringBuilder("""
                【设计简报（Design Brief）·内部工作文档】仅供你参考吸收，以你自己的口吻与判断自然表达，该采纳的采纳、该保留意见的保留意见；严禁向用户提及内部流程与工作文档：
                """);
        renderSnapshotInto(sb, turn);
        sb.append("\n【本轮推演结论】（这一轮只推这一处，一次只推进一步；问法仅供参考，用你自己的口吻）\n");
        sb.append("推进：").append(or(turn.focus(), "核心三要素"));
        if (!isBlank(turn.question())) {
            sb.append("；建议问法：").append(turn.question());
        }
        if (turn.options() != null && !turn.options().isEmpty()) {
            sb.append("；可给客人的选项：").append(String.join(" / ", turn.options()))
                    .append("（把选项自然说给客人挑，客人也可以不选、自己说）");
        }
        if (!isBlank(turn.proposal())) {
            sb.append("\n【建议方案】当前维度可转述给客人点头：").append(turn.proposal());
        }
        CoCreationTurn.Validity v = turn.validity();
        if (v != null && !isBlank(v.target())) {
            sb.append("\n【合理性判定】客人提出「").append(v.target()).append("」，判定：")
                    .append(or(v.verdict(), "合理"));
            if (!isBlank(v.reason())) {
                sb.append("（").append(v.reason()).append("）");
            }
            if ("不合理".equals(v.verdict()) && !isBlank(v.alternative())) {
                sb.append("；替代方案：").append(v.alternative())
                        .append("——如实转述判定与替代方案，请客人表态，不要替他决定");
            }
        }
        if (Boolean.TRUE.equals(turn.closingConfirmed())) {
            sb.append("\n【客人已点头定稿】客人已同意定稿——只需接一句确认，并说「我把这个想法整理成一张功能卡啦」")
                    .append("（卡片系统会自动展示，你不要输出卡片内容），不要再追问、不要再抛新问题。\n");
        } else if (Boolean.TRUE.equals(turn.closing())) {
            sb.append("\n【收口信号】收口条件已满足——用几句话把核心要点复述给客人，然后问「这样定下来可以吗？还有要补的么？」\n");
        } else if (Boolean.TRUE.equals(turn.ack())) {
            sb.append("\n【纯确认轮】客人本轮发言是纯确认，快照无新增——先用一句话收格确认，随即在同一句回复里抛出下一个问题，不要再追问已确认的内容。\n");
        }
        return sb.toString().trim();
    }

    /** 快照段（核心三要素 + 动态维度 + 待定决策 + 关键项）；「待聊」= 还没聊到 */
    private void renderSnapshotInto(StringBuilder sb, CoCreationTurn turn) {
        CoCreationTurn.Snapshot snap = turn.snapshot();
        if (snap == null) {
            return;
        }
        sb.append("\n【当前规格快照】（这个应用目前画到的样子；「待聊」就是还没聊到的，客人问进展时可按图口述）\n");
        CoCreationTurn.Core core = snap.core();
        if (core != null) {
            sb.append("核心三要素：\n");
            sb.append("- 做什么：").append(list(core.functions())).append('\n');
            sb.append("- 怎么算成：").append(or(core.effects())).append('\n');
            sb.append("- 实现思路：").append(or(core.approach())).append('\n');
        }
        if (snap.dimensions() != null && !snap.dimensions().isEmpty()) {
            sb.append("动态维度：\n");
            for (CoCreationTurn.Dimension d : snap.dimensions()) {
                if (d != null && !isBlank(d.name())) {
                    sb.append("- ").append(d.name()).append("：").append(or(d.content())).append('\n');
                }
            }
        }
        if (turn.decisions() != null && !turn.decisions().isEmpty()) {
            sb.append("【待定决策】（被判不合理、等客人表态，还没画进快照）\n");
            for (CoCreationTurn.Decision d : turn.decisions()) {
                if (d != null && !isBlank(d.target())) {
                    sb.append("- ").append(d.target());
                    if (!isBlank(d.alternative())) {
                        sb.append("（替代方案：").append(d.alternative()).append("）");
                    }
                    sb.append('\n');
                }
            }
        }
        if (turn.keyItems() != null && !turn.keyItems().isEmpty()) {
            sb.append("【关键项留痕】\n");
            for (CoCreationTurn.KeyItem k : turn.keyItems()) {
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
        if (!isBlank(turn.priority())) {
            sb.append("优先级建议：").append(turn.priority()).append('\n');
        }
    }

    /** 存档 → 回灌推演官的上一版快照与状态（快照 + 待定决策 + 关键项 + 优先级；议程与注入说明不回灌） */
    private String recapOf(String stored) {
        CoCreationTurn turn = parse(stored);
        if (turn == null) {
            return "（还没有规格快照——第一次推演）";
        }
        StringBuilder sb = new StringBuilder();
        renderSnapshotInto(sb, turn);
        String recap = sb.toString().trim();
        return recap.isEmpty() ? "（还没有规格快照——第一次推演）" : recap;
    }

    /** 存档 → 推演结论；非 JSON 或解析失败返回 null */
    private CoCreationTurn parse(String stored) {
        if (stored == null || stored.isBlank() || !stored.trim().startsWith("{")) {
            return null;
        }
        try {
            return counselMapper.readValue(stored.trim(), CoCreationTurn.class);
        } catch (Exception e) {
            log.warn("设计简报存档解析失败（按无状态处理）：{}", e.getMessage());
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
        return isBlank(s) ? "（待聊）" : s;
    }

    private static String or(String s, String fallback) {
        return isBlank(s) ? fallback : s;
    }

    /** 数组项以分号拼接为一句（空数组视为待聊） */
    private static String list(List<String> items) {
        return items == null || items.isEmpty() ? "（待聊）" : String.join("；", items);
    }
}
