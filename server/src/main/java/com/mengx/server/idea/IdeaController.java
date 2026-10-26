package com.mengx.server.idea;

import com.mengx.server.auth.AuthService;
import com.mengx.server.forge.ForgeService;
import com.mengx.server.user.UserEntity;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

/** 创意收集接口（全部 JWT 鉴权）：会话、主对话访谈、功能卡确认/看板/导出（设计方案 §6 P0） */
@RestController
@RequestMapping("/api/idea")
public class IdeaController {

    public record SessionResponse(Long sessionId, String nickname) {}

    /**
     * 一轮聊天请求。
     * clientRoundId：前端生成的一轮幂等键（建议 UUID），同键重发只落库一次；greet 出场可不传。
     */
    public record ChatRequest(Long sessionId, List<IdeaChatService.InMessage> messages,
                              String mode, Boolean greet, String clientRoundId) {}

    public record UpdateStatusRequest(String status) {}

    private final IdeaService ideaService;
    private final IdeaChatService ideaChatService;
    private final IdeaPrdService prdService;
    private final IdeaThanksService thanksService;
    private final IdeaWishService wishService;
    private final IdeaProposalService proposalService;
    private final AuthService authService;
    private final ForgeService forgeService;

    public IdeaController(
            IdeaService ideaService,
            IdeaChatService ideaChatService,
            IdeaPrdService prdService,
            IdeaThanksService thanksService,
            IdeaWishService wishService,
            IdeaProposalService proposalService,
            AuthService authService,
            ForgeService forgeService) {
        this.ideaService = ideaService;
        this.ideaChatService = ideaChatService;
        this.prdService = prdService;
        this.thanksService = thanksService;
        this.wishService = wishService;
        this.proposalService = proposalService;
        this.authService = authService;
        this.forgeService = forgeService;
    }

    private static Long userId(Authentication auth) {
        return (Long) auth.getPrincipal();
    }

    /** 开一场新访谈（聊天页首次进入或点"新开一场"），昵称取自用户账号 */
    @PostMapping("/sessions")
    public ResponseEntity<?> startSession(Authentication auth) {
        Long uid = userId(auth);
        return authService
                .findById(uid)
                .<ResponseEntity<?>>map(u -> {
                    IdeaSessionEntity session = ideaService.createSession(uid, u.getNickname());
                    return ResponseEntity.ok(new SessionResponse(session.getId(), session.getNickname()));
                })
                .orElseGet(() -> ResponseEntity.status(401).body(Map.of("message", "账号不存在")));
    }

    /**
     * 主对话流式访谈（SSE）。事件 type：chunk | card（content=卡片 DTO JSON）| done | error。
     * 会话必须属于当前用户。
     * Flux→SseEmitter 手动桥接：Spring MVC 内置桥（ReactiveTypeHandler）的订阅器是
     * 「单槽+每元素一圈线程调度」接力，配合阻塞式 sink.next 连发会大量积压（token 攒到收口才涌出，
     * 表现为一次性输出）；此处直接订阅 Flux，token 在生成线程上逐个 send（每次 send 后 flush）。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(Authentication auth, @RequestBody ChatRequest req, HttpServletResponse response) {
        // SSE 兜底：通知 Nginx 禁用该响应的代理缓冲（否则打字机效果变一次性输出）
        response.setHeader("X-Accel-Buffering", "no");
        // greet（法学助手出场）由后端注入触发指令，允许不带消息
        boolean greet = req != null && Boolean.TRUE.equals(req.greet());
        Flux<IdeaChatService.StreamEvent> flux;
        if (req == null || req.sessionId() == null || req.messages() == null
                || (req.messages().isEmpty() && !greet)) {
            flux = Flux.just(new IdeaChatService.StreamEvent(
                    IdeaChatService.StreamEvent.ERROR, "sessionId 和 messages 不能为空"));
        } else {
            flux = ideaService
                    .findSession(userId(auth), req.sessionId())
                    .map(session -> ideaChatService.stream(
                            session, req.messages(), req.mode(), greet, req.clientRoundId()))
                    .orElseGet(() -> Flux.just(
                            new IdeaChatService.StreamEvent(
                                    IdeaChatService.StreamEvent.ERROR, "会话不存在，请新开一场")));
        }
        SseEmitter emitter = new SseEmitter(-1L);
        flux.subscribe(
                ev -> {
                    try {
                        emitter.send(ev);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                },
                emitter::completeWithError,
                emitter::complete);
        return emitter;
    }

    /** 恢复本场对话已提炼的功能卡（刷新/回访后还原卡片气泡） */
    @GetMapping("/sessions/{id}/cards")
    public ResponseEntity<?> listSessionCards(Authentication auth, @PathVariable Long id) {
        return ideaService
                .findSession(userId(auth), id)
                .<ResponseEntity<?>>map(s -> ResponseEntity.ok(ideaService.listSessionCards(id)))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("message", "会话不存在")));
    }

    /** 我的场次列表（新→旧，带首聊预览与卡片数） */
    @GetMapping("/sessions")
    public List<IdeaService.SessionDto> listSessions(Authentication auth) {
        return ideaService.listSessions(userId(auth));
    }

    /** 恢复一场对话的消息流（与聊天页消息顺序一致，卡片占位按 id 取最新状态） */
    @GetMapping("/sessions/{id}/messages")
    public ResponseEntity<?> listMessages(Authentication auth, @PathVariable Long id) {
        return ideaService
                .listMessages(userId(auth), id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("message", "会话不存在")));
    }

    /** 营造图：最近一份设计简报存档原文（前端面板恢复用；无存档返回空对象） */
    @GetMapping("/sessions/{id}/design")
    public Map<String, String> latestDesign(Authentication auth, @PathVariable Long id) {
        return ideaService
                .latestDiscoveryState(userId(auth), id)
                .map(c -> Map.of("design", c))
                .orElseGet(() -> Map.of());
    }

    /**
     * 轮询通知：待裁决的应用提案（至多一条）+ 时间窗内晚到的功能卡。
     * 前端在对话间隙轮询（8s 间隔、30s 超时、收到提案即停），按卡片 id 去重后补气泡。
     */
    @GetMapping("/sessions/{id}/notices")
    public ResponseEntity<?> notices(Authentication auth, @PathVariable Long id) {
        return ideaService
                .findSession(userId(auth), id)
                .<ResponseEntity<?>>map(s -> ResponseEntity.ok(proposalService.notices(id)))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("message", "会话不存在")));
    }

    /**
     * 许可应用提案开班（用户点「聊聊看」）：CAS PENDING→ACCEPTED + 会话进入发现模式 +
     * 固定开场白落库 + 异步触发首轮双官推演。返回开场白文本由前端直接展示为气泡。
     */
    @PostMapping("/proposals/{id}/accept")
    public ResponseEntity<?> acceptProposal(Authentication auth, @PathVariable Long id) {
        IdeaProposalService.ProposalResult result = proposalService.accept(userId(auth), id);
        return switch (result.outcome()) {
            case IdeaProposalService.OK -> ResponseEntity.ok(Map.of("opening", result.opening()));
            case IdeaProposalService.NOT_FOUND ->
                    ResponseEntity.status(404).body(Map.of("message", "提案不存在"));
            default -> ResponseEntity.status(409).body(Map.of("message", "提案已被处理过"));
        };
    }

    /** 拒绝应用提案（用户点「暂不」）：CAS PENDING→DISMISSED */
    @PostMapping("/proposals/{id}/dismiss")
    public ResponseEntity<?> dismissProposal(Authentication auth, @PathVariable Long id) {
        return switch (proposalService.dismiss(userId(auth), id)) {
            case IdeaProposalService.OK -> ResponseEntity.ok().body(Map.of());
            case IdeaProposalService.NOT_FOUND ->
                    ResponseEntity.status(404).body(Map.of("message", "提案不存在"));
            default -> ResponseEntity.status(409).body(Map.of("message", "提案已被处理过"));
        };
    }

    /** 删除一场（连同其消息与卡片） */
    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<?> deleteSession(Authentication auth, @PathVariable Long id) {
        return ideaService.deleteSession(userId(auth), id)
                ? ResponseEntity.ok().body(Map.of())
                : ResponseEntity.status(404).body(Map.of("message", "会话不存在"));
    }

    /** 看板：全部功能卡（创意值优先），可按状态筛选，附点赞数与本人点赞态 */
    @GetMapping("/cards")
    public List<IdeaCardDto> listCards(Authentication auth, @RequestParam(required = false) String status) {
        return ideaService.listCards(userId(auth), status);
    }

    /** 我的点子墙：本人历场炼出的全部功能卡（新→旧，附成长轨迹） */
    @GetMapping("/my-cards")
    public List<IdeaCardDto> myCards(Authentication auth) {
        return ideaService.listMyCards(userId(auth));
    }

    /** 点赞（toggle）：一人一卡一赞，返回最新计数与本人点赞态 */
    @PostMapping("/cards/{id}/like")
    public ResponseEntity<?> likeCard(Authentication auth, @PathVariable Long id) {
        return ideaService
                .toggleLike(userId(auth), id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("message", "卡片不存在")));
    }

    /** 创意榜：点赞最多的点子 TOP10（平局看谁先提出） */
    @GetMapping("/leaderboard")
    public List<IdeaCardDto> leaderboard(Authentication auth) {
        return ideaService.leaderboard(userId(auth));
    }

    /** 本人确认功能卡：新芽 → 已确认（=授权开工，事务内自动创建造物线工单）（§5） */
    @PostMapping("/cards/{id}/confirm")
    public ResponseEntity<?> confirmCard(Authentication auth, @PathVariable Long id, @RequestBody Map<String, Object> body) {
        Number sessionIdNum = body == null ? null : (Number) body.get("sessionId");
        if (sessionIdNum == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "sessionId 不能为空"));
        }
        try {
            return ideaService
                    .confirmCard(userId(auth), sessionIdNum.longValue(), id)
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.status(404)
                            .body(Map.of("message", "卡片不存在或已处理过")));
        } catch (IllegalStateException e) {
            // 工单创建失败（如重复开工）：事务已回滚，卡片仍是新芽
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
        }
    }

    /** 本人丢弃功能卡（不要这个） */
    @DeleteMapping("/cards/{id}")
    public ResponseEntity<?> deleteCard(
            Authentication auth, @PathVariable Long id, @RequestParam Long sessionId) {
        return ideaService.deleteCard(userId(auth), sessionId, id)
                ? ResponseEntity.ok().body(Map.of())
                : ResponseEntity.status(404).body(Map.of("message", "卡片不存在"));
    }

    /** 状态流转：已确认→已实现（§5 状态机） */
    @PatchMapping("/cards/{id}/status")
    public ResponseEntity<?> updateCardStatus(
            Authentication auth, @PathVariable Long id, @RequestBody UpdateStatusRequest req) {
        if (req == null || req.status() == null || req.status().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "status 不能为空"));
        }
        return ideaService
                .updateCardStatus(id, req.status())
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("message", "卡片不存在或状态流转不合法")));
    }

    /** PRD 车间开工：已确认的卡一键格式化成施工 PRD（异步执行，结果看板可查） */
    @PostMapping("/cards/{id}/prd")
    public ResponseEntity<?> requestPrd(@PathVariable Long id) {
        return ideaService.requestPrd(id)
                ? ResponseEntity.accepted().body(Map.of("message", "PRD 车间已开工"))
                : ResponseEntity.status(404).body(Map.of("message", "卡片不存在或尚未确认"));
    }

    /** 下载 PRD 终稿 Markdown（可直接投喂 AI 编程工具） */
    @GetMapping("/cards/{id}/prd")
    public ResponseEntity<byte[]> downloadPrd(@PathVariable Long id) {
        return prdService.getMarkdown(id)
                .<ResponseEntity<byte[]>>map(md -> {
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(new MediaType("text", "markdown", StandardCharsets.UTF_8));
                    headers.setContentDisposition(
                            ContentDisposition.attachment().filename("prd-card-" + id + ".md").build());
                    return ResponseEntity.ok().headers(headers).body(md.getBytes(StandardCharsets.UTF_8));
                })
                .orElseGet(() -> ResponseEntity.status(404).body(null));
    }

    /** 下载施工包 Markdown（造物线 DONE 后，可直接投喂 AI 编程工具施工） */
    @GetMapping("/cards/{id}/build")
    public ResponseEntity<byte[]> downloadBuild(@PathVariable Long id) {
        return forgeService.getBuildMarkdown(id)
                .<ResponseEntity<byte[]>>map(md -> {
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(new MediaType("text", "markdown", StandardCharsets.UTF_8));
                    headers.setContentDisposition(
                            ContentDisposition.attachment().filename("build-card-" + id + ".md").build());
                    return ResponseEntity.ok().headers(headers).body(md.getBytes(StandardCharsets.UTF_8));
                })
                .orElseGet(() -> ResponseEntity.status(404).body(null));
    }

    /** 未读感谢卡（点子修成后仙君的答谢），进聊天页时拉取弹层展示 */
    @GetMapping("/thanks/unread")
    public List<IdeaThanksService.ThanksDto> listUnreadThanks(Authentication auth) {
        return thanksService.listUnread(userId(auth));
    }

    /** 感谢卡已读（关闭弹层时标记） */
    @PostMapping("/thanks/{id}/read")
    public ResponseEntity<?> readThanks(Authentication auth, @PathVariable Long id) {
        return thanksService.markRead(userId(auth), id)
                ? ResponseEntity.ok().body(Map.of())
                : ResponseEntity.status(404).body(Map.of("message", "感谢卡不存在"));
    }

    /** 许愿池：一句话愿望，精灵两官接力炼成功能卡（同步执行约 20~60 秒，前端有等待反馈） */
    @PostMapping("/wish")
    public ResponseEntity<?> wish(Authentication auth, @RequestBody Map<String, String> body) {
        String content = body == null ? null : body.get("content");
        if (content == null || content.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "愿望不能为空"));
        }
        Long uid = userId(auth);
        Optional<UserEntity> user = authService.findById(uid);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("message", "账号不存在"));
        }
        return wishService
                .cast(uid, user.get().getNickname(), content.trim())
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(409)
                        .body(Map.of("message", "精灵施法失败，或已收录这个点子，换个说法再投一次～")));
    }

    /** 导出 Markdown：cardId 为空导出全部卡片（给实现环节用） */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) Long cardId) {
        return ideaService
                .exportMarkdown(cardId)
                .map(md -> {
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(new MediaType("text", "markdown", StandardCharsets.UTF_8));
                    String filename = cardId != null ? "idea-card-" + cardId + ".md" : "idea-cards.md";
                    headers.setContentDisposition(ContentDisposition.attachment().filename(filename).build());
                    return ResponseEntity.ok().headers(headers).body(md.getBytes(StandardCharsets.UTF_8));
                })
                .orElseGet(() -> ResponseEntity.status(404).body(null));
    }
}
