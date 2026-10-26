package com.mengx.server.idea;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 功能卡（新结构：核心三要素 + 动态维度，只谈「做什么」，刻意没有技术字段）。
 * 主卡字段轻量展示；设计沉淀整体存 designJson（functions/effects/approach/dimensions/
 * constraints/feasibilityNotes/keyItems）。
 * 状态机：新芽 → 已确认（=授权开工，造物线工单已开）→ 已实现（终态）；不要了直接删卡。
 */
@Entity
@Table(name = "idea_cards")
public class IdeaCardEntity {

    public static final String STATUS_SPROUT = "SPROUT";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_IMPLEMENTED = "IMPLEMENTED";

    public static final String PRD_NONE = "NONE";
    public static final String PRD_RUNNING = "RUNNING";
    public static final String PRD_DONE = "DONE";
    public static final String PRD_FAILED = "FAILED";

    /** 施工包状态：NONE 未开工；在制随工单状态流转（RUNNING/WAITING_USER/REVIEW…），终态 DONE/FAILED/CANCELLED */
    public static final String BUILD_NONE = "NONE";
    public static final String BUILD_DONE = "DONE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long sessionId;

    /** 来源客人昵称，永久署名 */
    @Column(nullable = false, length = 30)
    private String nickname;

    /** 标题：动宾短语 */
    @Column(nullable = false, length = 100)
    private String title;

    /** 一句话说明：说人话的功能描述 */
    @Column(nullable = false, length = 500)
    private String summary;

    /** 创意值：卡片装配时 AI 评 1~5 星新颖度 */
    @Column(nullable = false)
    private Integer creativity;

    @Column(nullable = false, length = 20)
    private String status;

    /** 优先级（高/中/低）：设计简报给出的投入建议 */
    @Column(length = 10)
    private String priority;

    /** 来源应用提案（idea_proposals）；许愿池等无提案来源的卡为 null */
    @Column
    private Long proposalId;

    /**
     * 设计沉淀 JSON：{functions:[], effects, approach, dimensions:[{name,content}],
     * constraints, feasibilityNotes, keyItems:[{type,content,status,conclusion}]}
     */
    @Column(columnDefinition = "text")
    private String designJson;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    /** PRD 车间状态：NONE 未生成 / RUNNING 赶工中 / DONE 已出稿 / FAILED 失败 */
    @Column(length = 10)
    private String prdStatus;

    /** PRD 车间产出：格式化官整理出的施工 PRD（markdown 文本，可直接投喂 AI 编程工具） */
    @Column(columnDefinition = "text")
    private String prdJson;

    /** 最近一次 PRD 出稿时间 */
    @Column
    private Instant prdGeneratedAt;

    /**
     * 回路 B 施工包状态（工单进度在卡片侧的镜像，与 prdStatus 平行互不影响）：
     * NONE 未开工；在制随 forge_jobs.status 流转；终态 DONE/FAILED/CANCELLED。
     */
    // 不带 columnDefinition：Hibernate 6.6 的 ddl-auto=update 会把整串 columnDefinition 当列类型比对，
    // 含 not null/default 时会对 PG 生成非法的「set data type varchar(20) not null default 'NONE'」。
    // 列默认值由建列时的一次性 DDL 保证存量数据，新插入靠 Java 字段初始化 BUILD_NONE。
    @Column(nullable = false, length = 20)
    private String buildStatus = BUILD_NONE;

    /** 工部产出的施工包（markdown 文本，DONE 后经 /build 接口下载投喂 AI 编程工具） */
    @Column(columnDefinition = "text")
    private String buildJson;

    /** 最近一次施工包出稿时间 */
    @Column
    private Instant buildGeneratedAt;

    protected IdeaCardEntity() {}

    public IdeaCardEntity(Long sessionId, String nickname, String title, String summary,
                          Integer creativity) {
        this.sessionId = sessionId;
        this.nickname = nickname;
        this.title = title;
        this.summary = summary;
        this.creativity = creativity;
        this.status = STATUS_SPROUT;
    }

    public void confirm() {
        this.status = STATUS_CONFIRMED;
        this.updatedAt = Instant.now();
    }

    public void updateStatus(String status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    /** 规格定稿时写入设计沉淀（优先级 + 设计 JSON） */
    public void applyDesign(String priority, String designJson) {
        this.priority = priority == null || priority.isBlank() ? null : priority.trim();
        this.designJson = designJson;
    }

    public void bindProposal(Long proposalId) {
        this.proposalId = proposalId;
    }

    /** PRD 车间状态流转并记录产出（DONE 时记录时间） */
    public void markPrd(String status, String json) {
        this.prdStatus = status;
        this.prdJson = json;
        if (PRD_DONE.equals(status)) {
            this.prdGeneratedAt = Instant.now();
        }
    }

    /** 回路 B 施工包状态流转并记录产出（DONE 时记录时间；与 PRD 车间平行互不影响） */
    public void markBuild(String status, String json) {
        this.buildStatus = status;
        this.buildJson = json;
        this.updatedAt = Instant.now();
        if (BUILD_DONE.equals(status)) {
            this.buildGeneratedAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public String getNickname() {
        return nickname;
    }

    public String getTitle() {
        return title;
    }

    public String getSummary() {
        return summary;
    }

    public Integer getCreativity() {
        return creativity;
    }

    public String getStatus() {
        return status;
    }

    public String getPriority() {
        return priority;
    }

    public Long getProposalId() {
        return proposalId;
    }

    public String getDesignJson() {
        return designJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getPrdStatus() {
        return prdStatus == null ? PRD_NONE : prdStatus;
    }

    public String getPrdJson() {
        return prdJson;
    }

    public Instant getPrdGeneratedAt() {
        return prdGeneratedAt;
    }

    public String getBuildStatus() {
        return buildStatus == null ? BUILD_NONE : buildStatus;
    }

    public String getBuildJson() {
        return buildJson;
    }

    public Instant getBuildGeneratedAt() {
        return buildGeneratedAt;
    }
}
