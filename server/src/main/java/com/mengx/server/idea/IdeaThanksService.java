package com.mengx.server.idea;

import com.mengx.server.idea.agent.IdeaAgentFactory;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 感谢卡（叙事闭环）：点子修成（已实现）后，仙君亲手给提出人写一张感谢卡，
 * 异步生成（不阻塞状态流转接口），一张卡只递一次。
 */
@Service
public class IdeaThanksService {

    private static final Logger log = LoggerFactory.getLogger(IdeaThanksService.class);

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

    /** 感谢卡对外结构（聊天页弹层展示） */
    public record ThanksDto(Long id, String cardTitle, String content, String proposedAt) {}

    private final IdeaAgentFactory ideaAgents;
    private final IdeaCardRepository cardRepository;
    private final IdeaSessionRepository sessionRepository;
    private final IdeaThanksRepository thanksRepository;

    public IdeaThanksService(
            IdeaAgentFactory ideaAgents,
            IdeaCardRepository cardRepository,
            IdeaSessionRepository sessionRepository,
            IdeaThanksRepository thanksRepository) {
        this.ideaAgents = ideaAgents;
        this.cardRepository = cardRepository;
        this.sessionRepository = sessionRepository;
        this.thanksRepository = thanksRepository;
    }

    /** 异步生成（点子标记"已实现"后触发），失败只记日志 */
    public void thanksAsync(Long cardId) {
        CompletableFuture.runAsync(() -> {
            try {
                thanksNow(cardId);
            } catch (Exception e) {
                log.warn("感谢卡异步生成异常 cardId={}: {}", cardId, e.getMessage());
            }
        });
    }

    /** 同步生成：一张卡只递一次，提出人取自卡片归属场次 */
    void thanksNow(Long cardId) {
        IdeaCardEntity card = cardRepository.findById(cardId).orElse(null);
        if (card == null || thanksRepository.existsByCardId(cardId)) {
            return;
        }
        Long userId = sessionRepository.findById(card.getSessionId())
                .map(IdeaSessionEntity::getUserId)
                .orElse(null);
        if (userId == null) {
            return;
        }
        String content = ideaAgents.thanks().writeCard(thanksInput(card));
        if (content == null || content.isBlank()) {
            return;
        }
        thanksRepository.save(new IdeaThanksEntity(cardId, userId, content.trim()));
        log.info("感谢卡已递出 cardId={} to userId={}", cardId, userId);
    }

    private String thanksInput(IdeaCardEntity card) {
        return """
                【已修成的点子】
                标题：%s
                一句话：%s
                提出人昵称：%s
                """.formatted(
                card.getTitle(),
                card.getSummary(),
                card.getNickname());
    }

    /** 未读感谢卡（登录进聊天页时拉取） */
    public List<ThanksDto> listUnread(Long userId) {
        List<IdeaThanksEntity> thanks = thanksRepository.findByUserIdAndReadAtIsNullOrderByIdAsc(userId);
        if (thanks.isEmpty()) {
            return List.of();
        }
        Map<Long, IdeaCardEntity> cards = new HashMap<>();
        cardRepository.findAllById(thanks.stream().map(IdeaThanksEntity::getCardId).toList())
                .forEach(c -> cards.put(c.getId(), c));
        List<ThanksDto> result = new ArrayList<>(thanks.size());
        for (IdeaThanksEntity t : thanks) {
            IdeaCardEntity card = cards.get(t.getCardId());
            if (card == null) {
                continue; // 卡片已被删除，感谢卡一并作废
            }
            result.add(new ThanksDto(t.getId(), card.getTitle(), t.getContent(), DATE_FMT.format(card.getCreatedAt())));
        }
        return result;
    }

    /** 已读（关闭弹层） */
    public boolean markRead(Long userId, Long id) {
        Optional<IdeaThanksEntity> found = thanksRepository.findByIdAndUserId(id, userId);
        if (found.isEmpty()) {
            return false;
        }
        found.get().markRead();
        thanksRepository.save(found.get());
        return true;
    }

    /** 功能卡被删除时同步清理感谢卡（调用方需有事务） */
    public void deleteByCardIds(List<Long> cardIds) {
        if (cardIds == null || cardIds.isEmpty()) {
            return;
        }
        thanksRepository.deleteByCardIdIn(cardIds);
    }
}
