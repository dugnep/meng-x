package com.mengx.server.idea;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mengx.server.idea.agent.IdeaAgentFactory;
import com.mengx.server.idea.agent.WishCard;
import com.mengx.server.idea.agent.WishDraft;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 许愿池精灵（多 agent 协作 · 接力模式，区别于评审团的辩论与 PRD 车间的流水线）：
 * 提炼官（一句话愿望 → 四要素初稿）→ 追问官（初稿打磨成完整功能卡）。
 * 愿望炼成新芽卡的同时为用户自动开一场记录，回聊天页可继续打磨。
 * 同步执行约 20~60 秒，由前端给出等待反馈与超时。
 */
@Service
public class IdeaWishService {

    private static final Logger log = LoggerFactory.getLogger(IdeaWishService.class);

    /** 防重复比对的在册点子条数上限 */
    private static final int SHELF_MAX = 30;

    private final IdeaAgentFactory ideaAgents;
    private final ObjectMapper objectMapper;
    private final IdeaCardRepository cardRepository;
    private final IdeaSessionRepository sessionRepository;
    private final IdeaMessageRepository messageRepository;

    public IdeaWishService(
            IdeaAgentFactory ideaAgents,
            ObjectMapper objectMapper,
            IdeaCardRepository cardRepository,
            IdeaSessionRepository sessionRepository,
            IdeaMessageRepository messageRepository) {
        this.ideaAgents = ideaAgents;
        this.objectMapper = objectMapper;
        this.cardRepository = cardRepository;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
    }

    /** 投一个愿望：提炼官+追问官接力炼成功能卡（新芽），失败返回空；相似点子不拦截（卡未开发可重复创作） */
    public Optional<IdeaCardDto> cast(Long userId, String nickname, String wish) {
        List<IdeaCardEntity> existing = cardRepository
                .findAllByOrderByCreativityDescCreatedAtDesc()
                .stream().limit(SHELF_MAX).toList();
        String shelf = shelfText(existing);

        // 第一棒 · 提炼官：愿望 → 初稿（模型/解析失败按整体失败处理，不产空卡）
        WishDraft draft;
        try {
            draft = ideaAgents.wishDistiller().distill(shelf, wish);
        } catch (Exception e) {
            log.warn("许愿池提炼官调用失败：{}", e.getMessage());
            return Optional.empty();
        }
        if (draft == null || draft.title() == null || draft.title().isBlank()) {
            log.warn("许愿池提炼官未产出初稿：{}", wish);
            return Optional.empty();
        }

        // 第二棒 · 追问官：初稿 → 完整功能卡（失败退回初稿，不算整体失败）
        WishCard refined;
        try {
            refined = ideaAgents.wishRefiner().refine(wish, shelf, toJson(draft));
        } catch (Exception e) {
            log.warn("许愿池追问官调用失败，退回提炼官初稿：{}", e.getMessage());
            refined = null;
        }
        if (refined == null) {
            refined = new WishCard(
                    draft.title(), draft.summary(), draft.targetUser(),
                    draft.triggerScene(), draft.reaction(), draft.fallback(), 3);
        }

        String title = truncate(or(refined.title(), draft.title()).trim(), 100);
        if (title.isBlank()) {
            log.warn("愿望提炼标题为空，放弃定稿：{}", wish);
            return Optional.empty();
        }

        // 卡片定稿入库（新芽），并自动为这场愿望开一场记录，回聊天页可继续打磨
        IdeaSessionEntity session = sessionRepository.save(new IdeaSessionEntity(userId, nickname));
        IdeaCardEntity card = new IdeaCardEntity(
                session.getId(),
                nickname,
                title,
                orField(or(refined.summary(), draft.summary())),
                clamp(refined.creativity() == null ? 3 : refined.creativity()));
        card.applyDesign(null, wishDesignJson(refined, draft));
        card = cardRepository.save(card);
        messageRepository.saveAll(List.of(
                new IdeaMessageEntity(session.getId(), "user", wish),
                new IdeaMessageEntity(session.getId(), "assistant",
                        "许愿池的精灵把你的愿望炼成了功能卡「" + card.getTitle()
                                + "」，已收录。你可以继续说说哪里要改，或聊下一个灵感～"),
                new IdeaMessageEntity(session.getId(), "card", String.valueOf(card.getId()))));
        log.info("愿望定稿成卡成功：用户{} → 《{}》cardId={}", userId, card.getTitle(), card.getId());
        return Optional.of(IdeaCardDto.from(card));
    }

    /** 许愿池设计沉淀映射：愿望初稿四要素装入新结构（核心三要素 + 动态维度 + 边界兜底） */
    private String wishDesignJson(WishCard refined, WishDraft draft) {
        ObjectNode design = objectMapper.createObjectNode();
        design.putArray("functions").add(orField(or(refined.summary(), draft.summary())));
        design.put("effects", orField(or(refined.reaction(), draft.reaction())));
        design.put("approach", "待聊");
        ArrayNode dims = design.putArray("dimensions");
        dims.addObject().put("name", "谁在用")
                .put("content", orField(or(refined.targetUser(), draft.targetUser())));
        dims.addObject().put("name", "什么时机用")
                .put("content", orField(or(refined.triggerScene(), draft.triggerScene())));
        design.put("constraints", orField(or(refined.fallback(), draft.fallback())));
        design.put("feasibilityNotes", "");
        design.putArray("keyItems");
        return toJson(design);
    }

    private String shelfText(List<IdeaCardEntity> cards) {
        StringBuilder sb = new StringBuilder(cards.isEmpty() ? "（还没有）" : "");
        for (IdeaCardEntity card : cards) {
            sb.append("《").append(card.getTitle()).append("》：")
                    .append(card.getSummary() == null ? "" : card.getSummary()).append('\n');
        }
        return sb.toString().trim();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static String or(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String orField(String value) {
        return value == null || value.isBlank() ? "待聊" : truncate(value.trim(), 200);
    }

    private static int clamp(int value) {
        return Math.max(1, Math.min(5, value));
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
