package com.mengx.server.idea;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 访谈消息：一场对话的服务端持久化（换设备/清缓存不丢聊天记录）。
 * role：user（访客发言）| assistant（主对话回复）| card（功能卡占位，content=卡片 id；
 * 卡片被丢弃后渲染时按 id 查不到即跳过）。
 *
 * producerChannel：产生该消息的通道（host/law/stock…），用于分帧隔离。
 * 同一 session 内不同通道的消息互不污染；前端历史用于路由/槽位等编排逻辑不过滤，
 * 但各通道 Agent 的 LLM 上下文只取本通道消息（含 NULL 存量兼容）。
 */
@Entity
@Table(name = "idea_messages")
public class IdeaMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long sessionId;

    @Column(nullable = false, length = 10)
    private String role;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** 产生该消息的通道（host/law/stock…）；存量数据为 NULL，过滤时兼容本通道+NULL */
    @Column(name = "producer_channel", length = 32)
    private String producerChannel;

    /** 一轮对话的幂等键（前端生成）：同一轮重发只落库一次；counsel/card 之外的半截消息为 null */
    @Column(name = "client_round_id", length = 64)
    private String clientRoundId;

    protected IdeaMessageEntity() {}

    public IdeaMessageEntity(Long sessionId, String role, String content) {
        this(sessionId, role, content, null, null);
    }

    public IdeaMessageEntity(Long sessionId, String role, String content, String clientRoundId) {
        this(sessionId, role, content, null, clientRoundId);
    }

    public IdeaMessageEntity(Long sessionId, String role, String content, String producerChannel,
                             String clientRoundId) {
        this.sessionId = sessionId;
        this.role = role;
        this.content = content;
        this.producerChannel = producerChannel;
        this.clientRoundId = clientRoundId;
    }

    public Long getId() {
        return id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getProducerChannel() {
        return producerChannel;
    }

    public String getClientRoundId() {
        return clientRoundId;
    }
}
