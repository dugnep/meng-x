package com.mengx.server.idea;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mengx.server.forge.ForgeService;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 创意收集业务：会话管理、看板、状态流转、导出。
 * 所有人登录即用户（登录即注册），卡片归属校验按会话归属（谁聊的谁确认）。
 * 状态机：新芽 →（本人确认=授权开工，事务内自动建造物线工单）→ 已确认 → 已实现；不要了直接删卡。
 */
@Service
public class IdeaService {

    /** 状态机：已确认（造物线在制）→ 已实现（终态） */
    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            IdeaCardEntity.STATUS_CONFIRMED,
            Set.of(IdeaCardEntity.STATUS_IMPLEMENTED));

    private static final Map<String, String> STATUS_LABELS = Map.of(
            IdeaCardEntity.STATUS_SPROUT, "新芽",
            IdeaCardEntity.STATUS_CONFIRMED, "已确认",
            IdeaCardEntity.STATUS_IMPLEMENTED, "已实现");

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final IdeaSessionRepository sessionRepository;
    private final IdeaCardRepository cardRepository;
    private final IdeaMessageRepository messageRepository;
    private final IdeaPrdService prdService;
    private final IdeaThanksService thanksService;
    private final IdeaLikeRepository likeRepository;
    private final ForgeService forgeService;
    private final ObjectMapper objectMapper;

    public IdeaService(
            IdeaSessionRepository sessionRepository,
            IdeaCardRepository cardRepository,
            IdeaMessageRepository messageRepository,
            IdeaPrdService prdService,
            IdeaThanksService thanksService,
            IdeaLikeRepository likeRepository,
            ForgeService forgeService,
            ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.cardRepository = cardRepository;
        this.messageRepository = messageRepository;
        this.prdService = prdService;
        this.thanksService = thanksService;
        this.likeRepository = likeRepository;
        this.forgeService = forgeService;
        this.objectMapper = objectMapper;
    }

    // ---------- 会话 ----------

    /** 开一场新访谈（"新开一场"） */
    public IdeaSessionEntity createSession(Long userId, String nickname) {
        return sessionRepository.save(new IdeaSessionEntity(userId, nickname));
    }

    /** 校验会话属于当前用户（聊天/确认/丢弃的归属校验） */
    public Optional<IdeaSessionEntity> findSession(Long userId, Long sessionId) {
        return sessionRepository.findByIdAndUserId(sessionId, userId);
    }

    public List<IdeaCardDto> listSessionCards(Long sessionId) {
        return cardRepository.findBySessionIdOrderByIdDesc(sessionId).stream()
                .map(IdeaCardDto::from)
                .toList();
    }

    /** 会话列表项：首条访客发言做预览，附本场卡片数 */
    public record SessionDto(Long sessionId, String createdAt, String preview, long cardCount) {}

    /** 消息项：role=user/assistant 时为文本，role=card 时 cardId 指向最新卡片状态 */
    public record MessageDto(String role, String content, Long cardId) {}

    /** 我的场次列表（新→旧；只列主会话，FORGE 会话是任务面板不是聊天历史） */
    public List<SessionDto> listSessions(Long userId) {
        List<SessionDto> result = new ArrayList<>();
        for (IdeaSessionEntity s : sessionRepository.findByUserIdAndKindOrderByCreatedAtDesc(
                userId, IdeaSessionEntity.KIND_MAIN)) {
            String preview = messageRepository
                    .findFirstBySessionIdAndRoleOrderByIdAsc(s.getId(), "user")
                    .map(m -> abbreviate(m.getContent(), 40))
                    .orElse("还没开聊");
            result.add(new SessionDto(
                    s.getId(),
                    TIME_FMT.format(s.getCreatedAt()),
                    preview,
                    cardRepository.countBySessionId(s.getId())));
        }
        return result;
    }

    /** 恢复一场对话的消息流（user → assistant → 卡片占位，与聊天页顺序一致） */
    public Optional<List<MessageDto>> listMessages(Long userId, Long sessionId) {
        return findSession(userId, sessionId).map(s ->
                messageRepository.findBySessionIdOrderByIdAsc(sessionId).stream()
                        .filter(m -> !"counsel".equals(m.getRole())) // 设计简报是主对话代理的内部参考，不下发
                        .map(m -> "card".equals(m.getRole())
                                ? new MessageDto("card", null, parseCardId(m.getContent()))
                                : new MessageDto(m.getRole(), m.getContent(), null))
                        .toList());
    }

    /** 营造图存档：最近一份需求澄清状态原文（前端面板恢复用）；无存档返回 empty */
    public Optional<String> latestDiscoveryState(Long userId, Long sessionId) {
        return findSession(userId, sessionId).map(s -> {
            List<IdeaMessageEntity> list =
                    messageRepository.findBySessionIdAndRoleOrderByIdAsc(sessionId, "counsel");
            return list.isEmpty() ? null : list.get(list.size() - 1).getContent();
        });
    }

    /** 删除一场（连同其消息与卡片）；造物线会话随工单归档，不手动删除（防孤儿工单） */
    @Transactional
    public boolean deleteSession(Long userId, Long sessionId) {
        Optional<IdeaSessionEntity> session = findSession(userId, sessionId);
        if (session.isEmpty()) {
            return false;
        }
        if (IdeaSessionEntity.KIND_FORGE.equals(session.get().getKind())) {
            return false;
        }
        List<IdeaCardEntity> cards = cardRepository.findBySessionIdOrderByIdDesc(sessionId);
        List<Long> cardIds = cards.stream().map(IdeaCardEntity::getId).toList();
        messageRepository.deleteBySessionId(sessionId);
        cardRepository.deleteAll(cards);
        thanksService.deleteByCardIds(cardIds);
        likeRepository.deleteByCardIdIn(cardIds);
        sessionRepository.delete(session.get());
        return true;
    }

    private static String abbreviate(String s, int max) {
        String t = s.trim().replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    private static Long parseCardId(String content) {
        try {
            return Long.valueOf(content);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------- 看板 ----------

    /** 全部功能卡（创意值优先），可按状态筛选；附点赞数与本人点赞态 */
    public List<IdeaCardDto> listCards(Long userId, String status) {
        List<IdeaCardEntity> cards = (status == null || status.isBlank())
                ? cardRepository.findAllByOrderByCreativityDescCreatedAtDesc()
                : cardRepository.findByStatusOrderByCreativityDescCreatedAtDesc(status);
        return enrich(userId, cards);
    }

    /** 我的点子墙：本人历场定稿的全部功能卡（新→旧），附成长轨迹时间与点赞（只看主会话，FORGE 会话不走定稿） */
    public List<IdeaCardDto> listMyCards(Long userId) {
        List<IdeaSessionEntity> sessions = sessionRepository.findByUserIdAndKindOrderByCreatedAtDesc(
                userId, IdeaSessionEntity.KIND_MAIN);
        if (sessions.isEmpty()) {
            return List.of();
        }
        return enrich(userId, cardRepository.findBySessionIdInOrderByCreatedAtDesc(
                sessions.stream().map(IdeaSessionEntity::getId).toList()));
    }

    /** 点赞结果（toggle 后的最新状态） */
    public record LikeResult(long likeCount, boolean liked) {}

    /** 给一张卡添/撤一炷香（toggle）：一人一卡一赞 */
    @Transactional
    public Optional<LikeResult> toggleLike(Long userId, Long cardId) {
        if (!cardRepository.existsById(cardId)) {
            return Optional.empty();
        }
        boolean liked;
        if (likeRepository.existsByCardIdAndUserId(cardId, userId)) {
            likeRepository.deleteByCardIdAndUserId(cardId, userId);
            liked = false;
        } else {
            likeRepository.save(new IdeaLikeEntity(cardId, userId));
            liked = true;
        }
        return Optional.of(new LikeResult(likeRepository.countByCardId(cardId), liked));
    }

    /** 创意榜：按点赞数降序 TOP10（平局看谁先提出），只有 ≥1 赞的卡上榜 */
    public List<IdeaCardDto> leaderboard(Long userId) {
        return enrich(userId, cardRepository.findAllByOrderByCreativityDescCreatedAtDesc()).stream()
                .filter(c -> c.likeCount() > 0)
                .sorted(Comparator.comparingLong(IdeaCardDto::likeCount).reversed()
                        .thenComparing(IdeaCardDto::createdAt))
                .limit(10)
                .toList();
    }

    /** 批量组装 DTO，附点赞数与本人点赞态 */
    private List<IdeaCardDto> enrich(Long userId, List<IdeaCardEntity> cards) {
        if (cards.isEmpty()) {
            return List.of();
        }
        List<Long> ids = cards.stream().map(IdeaCardEntity::getId).toList();
        Map<Long, Long> counts = new HashMap<>();
        for (IdeaLikeEntity like : likeRepository.findByCardIdIn(ids)) {
            counts.merge(like.getCardId(), 1L, Long::sum);
        }
        Set<Long> liked = userId == null
                ? Set.of()
                : likeRepository.findByUserIdAndCardIdIn(userId, ids).stream()
                        .map(IdeaLikeEntity::getCardId).collect(Collectors.toSet());
        return cards.stream()
                .map(c -> IdeaCardDto.from(c, counts.getOrDefault(c.getId(), 0L), liked.contains(c.getId())))
                .toList();
    }

    /** 状态流转（已确认 → 已实现），非法流转返回空 */
    public Optional<IdeaCardDto> updateCardStatus(Long cardId, String status) {
        Optional<IdeaCardEntity> found = cardRepository.findById(cardId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        IdeaCardEntity card = found.get();
        Set<String> allowed = ALLOWED_TRANSITIONS.getOrDefault(card.getStatus(), Set.of());
        if (!allowed.contains(status)) {
            return Optional.empty();
        }
        card.updateStatus(status);
        IdeaCardEntity saved = cardRepository.save(card);
        // 点子修成 → 仙君给提出人递感谢卡（异步，一张卡只递一次）
        if (IdeaCardEntity.STATUS_IMPLEMENTED.equals(status)) {
            thanksService.thanksAsync(saved.getId());
        }
        return Optional.of(IdeaCardDto.from(saved));
    }

    /**
     * 本人确认功能卡 = 授权开工：新芽 → 已确认，同一事务内自动创建造物线工单。
     * 卡片必须属于本人会话；工单创建失败整体回滚（卡片保持新芽，用户可重试确认），
     * 失败原因以 IllegalStateException 抛出，由控制器转 409。
     */
    @Transactional
    public Optional<IdeaCardDto> confirmCard(Long userId, Long sessionId, Long cardId) {
        Optional<IdeaSessionEntity> session = findSession(userId, sessionId);
        if (session.isEmpty()) {
            return Optional.empty();
        }
        Optional<IdeaCardEntity> found = cardRepository.findByIdAndSessionId(cardId, sessionId);
        if (found.isEmpty() || !IdeaCardEntity.STATUS_SPROUT.equals(found.get().getStatus())) {
            return Optional.empty();
        }
        found.get().confirm();
        IdeaCardEntity saved = cardRepository.save(found.get());
        ForgeService.StartResult result =
                forgeService.startBuild(userId, session.get().getNickname(), cardId);
        if (!result.ok()) {
            throw new IllegalStateException(result.message());
        }
        return Optional.of(IdeaCardDto.from(saved));
    }

    /** 开进 PRD 车间（看板入口）：只有已确认（CONFIRMED）的卡才能排产出稿 */
    public boolean requestPrd(Long cardId) {
        boolean confirmed = cardRepository
                .findById(cardId)
                .filter(c -> IdeaCardEntity.STATUS_CONFIRMED.equals(c.getStatus()))
                .isPresent();
        if (!confirmed) {
            return false;
        }
        prdService.generateAsync(cardId);
        return true;
    }

    /** 本人丢弃功能卡（不要这个），点赞与感谢卡一并清理 */
    @Transactional
    public boolean deleteCard(Long userId, Long sessionId, Long cardId) {
        Optional<IdeaSessionEntity> session = findSession(userId, sessionId);
        if (session.isEmpty()) {
            return false;
        }
        Optional<IdeaCardEntity> found = cardRepository.findByIdAndSessionId(cardId, sessionId);
        if (found.isEmpty()) {
            return false;
        }
        cardRepository.delete(found.get());
        thanksService.deleteByCardIds(List.of(cardId));
        likeRepository.deleteByCardIdIn(List.of(cardId));
        return true;
    }

    // ---------- 导出 ----------

    /** 导出为 Markdown 清单（给实现环节用）：cardId 为空导出全部卡片 */
    public Optional<String> exportMarkdown(Long cardId) {
        List<IdeaCardEntity> cards;
        if (cardId != null) {
            Optional<IdeaCardEntity> one = cardRepository.findById(cardId);
            cards = one.map(List::of).orElseGet(List::of);
        } else {
            cards = cardRepository.findAllByOrderByCreativityDescCreatedAtDesc();
        }
        return Optional.of(renderMarkdown(cards));
    }

    private String renderMarkdown(List<IdeaCardEntity> cards) {
        StringBuilder md = new StringBuilder();
        if (cards.size() == 1) {
            md.append("# 点子铺 · 功能卡\n\n");
        } else {
            long confirmed = cards.stream()
                    .filter(c -> !IdeaCardEntity.STATUS_SPROUT.equals(c.getStatus()))
                    .count();
            md.append("# 点子铺 · 功能卡清单\n\n")
                    .append("> 导出于 ").append(DATE_FMT.format(java.time.Instant.now()))
                    .append("，共 ").append(cards.size())
                    .append(" 张，其中已确认及以上 ").append(confirmed).append(" 张\n\n");
        }
        for (IdeaCardEntity c : cards) {
            md.append("---\n\n")
                    .append("## ").append(c.getTitle()).append('\n')
                    .append("- 一句话说明：").append(c.getSummary()).append('\n')
                    .append("- 创意值：").append(stars(c.getCreativity()))
                    .append("（").append(c.getCreativity()).append("/5）\n");
            if (c.getPriority() != null) {
                md.append("- 优先级：").append(c.getPriority()).append('\n');
            }
            md.append(renderDesign(c.getDesignJson()))
                    .append("- 来源：").append(c.getNickname())
                    .append("（").append(DATE_FMT.format(c.getCreatedAt())).append("）\n")
                    .append("- 状态：").append(STATUS_LABELS.get(c.getStatus())).append('\n');
            md.append('\n');
        }
        return md.toString();
    }

    /** 设计沉淀（核心三要素 + 动态维度 + 边界兜底 + 关键项）展开为导出行，无沉淀返回空串 */
    private String renderDesign(String designJson) {
        if (designJson == null || designJson.isBlank()) {
            return "";
        }
        try {
            JsonNode d = objectMapper.readTree(designJson);
            StringBuilder sb = new StringBuilder();
            JsonNode functions = d.path("functions");
            if (functions.isArray() && !functions.isEmpty()) {
                sb.append("- 做什么：\n");
                for (JsonNode f : functions) {
                    sb.append("  - ").append(f.asText("")).append('\n');
                }
            }
            String effects = designText(d, "effects");
            if (effects != null) {
                sb.append("- 怎么算成：").append(effects).append('\n');
            }
            String approach = designText(d, "approach");
            if (approach != null) {
                sb.append("- 实现思路：").append(approach).append('\n');
            }
            JsonNode dimensions = d.path("dimensions");
            if (dimensions.isArray() && !dimensions.isEmpty()) {
                for (JsonNode dim : dimensions) {
                    String name = dim.path("name").asText("").trim();
                    if (!name.isEmpty()) {
                        sb.append("- ").append(name).append("：")
                                .append(dim.path("content").asText("待聊")).append('\n');
                    }
                }
            }
            String constraints = designText(d, "constraints");
            if (constraints != null) {
                sb.append("- 边界与兜底：").append(constraints).append('\n');
            }
            String notes = designText(d, "feasibilityNotes");
            if (notes != null) {
                sb.append("- 可行性说明：").append(notes).append('\n');
            }
            JsonNode keyItems = d.path("keyItems");
            if (keyItems.isArray() && !keyItems.isEmpty()) {
                sb.append("- 关键项留痕：\n");
                for (JsonNode k : keyItems) {
                    sb.append("  - [").append(k.path("type").asText("诉求")).append("] ")
                            .append(k.path("content").asText(""));
                    if ("resolved".equals(k.path("status").asText(""))
                            && !k.path("conclusion").asText("").isBlank()) {
                        sb.append("（结论：").append(k.path("conclusion").asText("")).append("）");
                    }
                    sb.append('\n');
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 设计字段文本，"待聊"或缺失视为没有 */
    private static String designText(JsonNode node, String field) {
        String v = node.path(field).asText("").trim();
        return v.isEmpty() || "待聊".equals(v) ? null : v;
    }

    private static String stars(int creativity) {
        return "★".repeat(creativity) + "☆".repeat(5 - creativity);
    }
}
