package com.mengx.server.idea;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 创意会话：一位用户的一场主对话访谈（"新开一场"即新建会话）。
 * 昵称取自用户账号，卡片署名来源于此。
 */
@Entity
@Table(name = "idea_sessions")
public class IdeaSessionEntity {

    /** 主会话：主对话访谈（历史面板/场次列表只列此类） */
    public static final String KIND_MAIN = "MAIN";
    /** 造物线会话：身外身，一工单一条，是任务面板不是聊天历史（方案 §8.2） */
    public static final String KIND_FORGE = "FORGE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    /** 客人昵称（取自用户账号，冗余存储供卡片署名） */
    @Column(nullable = false, length = 30)
    private String nickname;

    /**
     * 当前活跃帧（active frame）：host=本色陪聊（默认）/ law=法学 / cocreation=需求共创。
     * Host 路由裁决结果随消息落会话；共创访谈激活状态由「活跃帧=cocreation + 快照未定稿」推断，
     * 无独立开关字段（旧 discoveryActive/design_mode 列已随共创领域化退役，存量列停用）。
     */
    @Column(nullable = false, length = 16)
    private String mode = "host";

    /**
     * 会话帧栈（JSON 数组）：按挂起顺序排列的已休眠通道名，栈首为最近挂起的帧。
     * 切换通道时旧帧挂起压栈；切回已有帧时从栈中移除并设为活跃。
     * 配合 idea_messages.producer_channel 实现分帧隔离，每帧历史独立，切回即恢复。
     * 最多保留 {@link com.mengx.server.idea.orchestrator.FrameStackManager#MAX_FRAMES} 个挂起帧。
     */
    @Column(name = "frame_stack", columnDefinition = "text")
    private String frameStack;

    /**
     * 会话种类：MAIN=主会话 / FORGE=造物线会话（身外身）。
     * 历史面板只列 MAIN；FORGE 会话随工单 CANCELLED 归档，不手动删除。
     */
    // 不用 columnDefinition 带 default（Hibernate 6.6 update 模式会把整串当列类型，对 PG 生成非法 DDL）；
    // 存量加列的 default 由一次性 DDL 保证
    @Column(nullable = false, length = 10)
    private String kind = KIND_MAIN;

    /** FORGE 会话绑定的功能卡 id（MAIN 为 null） */
    @Column
    private Long cardId;

    /** FORGE 会话绑定的工单 id（MAIN 为 null） */
    @Column
    private Long forgeJobId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected IdeaSessionEntity() {}

    public IdeaSessionEntity(Long userId, String nickname) {
        this.userId = userId;
        this.nickname = nickname;
    }

    /** 造物线会话（身外身）：绑定工单与卡片，历史面板不列出 */
    public static IdeaSessionEntity forgeOf(Long userId, String nickname, Long cardId, Long forgeJobId) {
        IdeaSessionEntity session = new IdeaSessionEntity(userId, nickname);
        session.kind = KIND_FORGE;
        session.cardId = cardId;
        session.forgeJobId = forgeJobId;
        return session;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getNickname() {
        return nickname;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getFrameStack() {
        return frameStack;
    }

    public void setFrameStack(String frameStack) {
        this.frameStack = frameStack;
    }

    public String getKind() {
        return kind == null ? KIND_MAIN : kind;
    }

    public Long getCardId() {
        return cardId;
    }

    public Long getForgeJobId() {
        return forgeJobId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
