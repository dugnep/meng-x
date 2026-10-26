package com.mengx.server.idea;

import java.time.Instant;

/** 功能卡对外结构（看板/确认/导出共用）：主卡轻量字段 + designJson 设计沉淀，刻意没有技术字段 */
public record IdeaCardDto(
        Long id,
        Long sessionId,
        String nickname,
        String title,
        String summary,
        Integer creativity,
        String status,
        Instant createdAt,
        Instant updatedAt,
        String priority,
        Long proposalId,
        String designJson,
        String prdStatus,
        String prdJson,
        String buildStatus,
        long likeCount,
        boolean liked) {

    public static IdeaCardDto from(IdeaCardEntity c) {
        return from(c, 0L, false);
    }

    /** 带点赞信息组装（看板/创意榜用；聊天页等不关心点赞的场景走 from(c)） */
    public static IdeaCardDto from(IdeaCardEntity c, long likeCount, boolean liked) {
        return new IdeaCardDto(
                c.getId(),
                c.getSessionId(),
                c.getNickname(),
                c.getTitle(),
                c.getSummary(),
                c.getCreativity(),
                c.getStatus(),
                c.getCreatedAt(),
                c.getUpdatedAt(),
                c.getPriority(),
                c.getProposalId(),
                c.getDesignJson(),
                c.getPrdStatus(),
                c.getPrdJson(),
                c.getBuildStatus(),
                likeCount,
                liked);
    }
}
