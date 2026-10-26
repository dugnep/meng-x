package com.mengx.server.idea;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdeaMessageRepository extends JpaRepository<IdeaMessageEntity, Long> {

    /** 恢复一场对话（按发生顺序） */
    List<IdeaMessageEntity> findBySessionIdOrderByIdAsc(Long sessionId);

    /** 某场次某角色的全部消息（取设计简报存档 counsel 用） */
    List<IdeaMessageEntity> findBySessionIdAndRoleOrderByIdAsc(Long sessionId, String role);

    /** 某场次指定角色集合的全部消息（托管对话窗口取 user/assistant，过滤 card/counsel 行） */
    List<IdeaMessageEntity> findBySessionIdAndRoleInOrderByIdAsc(Long sessionId, Collection<String> roles);

    /** Host 帧上下文窗口：某场次最近 N 条对话轮（全通道、不限帧），倒序取再反转即时间正序 */
    List<IdeaMessageEntity> findTop40BySessionIdAndRoleInOrderByIdDesc(Long sessionId, Collection<String> roles);

    /**
     * 某场次指定角色 + 指定通道的消息（分帧隔离：Agent 只取本通道消息，不带通道标签的不进帧）。
     * 存量未打标数据（producer_channel IS NULL）须先执行迁移打标：
     * UPDATE idea_messages SET producer_channel = 'host' WHERE producer_channel IS NULL;
     */
    @Query("SELECT m FROM IdeaMessageEntity m WHERE m.sessionId = :sessionId "
            + "AND m.role IN :roles "
            + "AND m.producerChannel IN :channels "
            + "ORDER BY m.id ASC")
    List<IdeaMessageEntity> findFrameMessages(
            @Param("sessionId") Long sessionId,
            @Param("roles") Collection<String> roles,
            @Param("channels") Collection<String> channels);

    /** 某时点之后某角色新增的消息数（提案静默归档阈值判定用） */
    long countBySessionIdAndRoleAndCreatedAtAfter(Long sessionId, String role, Instant after);

    /** 会话列表预览：每场第一条访客发言 */
    Optional<IdeaMessageEntity> findFirstBySessionIdAndRoleOrderByIdAsc(Long sessionId, String role);

    /** 一轮对话是否已完整落库（以 assistant 行带同一幂等键为准）；null 幂等键不查 */
    boolean existsBySessionIdAndClientRoundIdAndRole(Long sessionId, String clientRoundId, String role);

    void deleteBySessionId(Long sessionId);
}
