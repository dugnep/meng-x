package com.mengx.server.idea;

import com.mengx.server.idea.agent.IdeaAgentFactory;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * PRD 格式化服务（瘦身后只剩一道工序）：评审与设计已前置到主对话代理与需求澄清 agent 的共创对话里，
 * 这里只把功能卡与设计沉淀格式化成施工文档——单次调用，不新增设计、不遗漏验收标准。
 * 统一出「应用蓝图文档」（模块/数据/集成/分期）。
 * 产出为 Markdown 文本直接存 prdJson，可直接投喂 AI 编程工具。
 */
@Service
public class IdeaPrdService {

    private static final Logger log = LoggerFactory.getLogger(IdeaPrdService.class);

    /** 技术栈段真源在 classpath:forge-handbook.md 的 STACK 标记段；此常量仅为手册缺失/读取失败时的保底文本 */
    private static final String FALLBACK_STACK = """
            现有技术栈（施工必须贴合，不得另起炉灶）：
            - 前端：React 18 + Vite 6 单页应用，无路由库，手机端为主场景（视口自适应），PC 端窄栏居中
            - 后端：Java 17 + Spring Boot 3.4，Spring Data JPA（ddl-auto=update），REST 接口 JWT 鉴权，SSE 流式输出
            - 数据库：PostgreSQL + pgvector（向量检索）
            - LLM：智谱 GLM（langchain4j 官方集成，AiServices 多 agent 编排 + ChatMemory 托管上下文）
            - 已有能力体系（涉及以下能力时必须复用现有实现，不得另建）：
              手机号登录即注册、会话与消息持久化（换设备不丢）、主对话代理多 agent 访谈（意图分层路由 / 需求澄清共创 / 规格定稿）、
              功能卡看板流转与文档产出、法学助手（法条 / 真题 RAG 三路检索、法考刷题与学情画像、主观题批改）、
              每日一签、修为墙、LLM 用量埋点与 Prometheus 指标""";

    private static final String HANDBOOK_PATH = "/forge-handbook.md";
    private static final String STACK_BEGIN = "<!-- STACK:BEGIN -->";
    private static final String STACK_END = "<!-- STACK:END -->";

    private final IdeaAgentFactory ideaAgents;
    private final IdeaCardRepository cardRepository;

    /** 注入 PRD 的技术栈段：启动时从架构手册 STACK 标记段提取，提取失败保底 FALLBACK_STACK */
    private volatile String stack = FALLBACK_STACK;

    public IdeaPrdService(IdeaAgentFactory ideaAgents, IdeaCardRepository cardRepository) {
        this.ideaAgents = ideaAgents;
        this.cardRepository = cardRepository;
    }

    /** 单一真源防腐烂：技术栈以 forge-handbook.md 的 STACK 标记段为准，改手册即生效，无需改代码 */
    @PostConstruct
    void loadStackFromHandbook() {
        try (var in = getClass().getResourceAsStream(HANDBOOK_PATH)) {
            if (in == null) {
                log.warn("架构手册缺失（{}），PRD 格式化使用内置技术栈文本", HANDBOOK_PATH);
                return;
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int begin = text.indexOf(STACK_BEGIN);
            int end = text.indexOf(STACK_END);
            if (begin < 0 || end < 0 || end <= begin) {
                log.warn("架构手册未找到 STACK 标记段，PRD 格式化使用内置技术栈文本");
                return;
            }
            String extracted = text.substring(begin + STACK_BEGIN.length(), end).trim();
            if (extracted.isBlank()) {
                log.warn("架构手册 STACK 标记段为空，PRD 格式化使用内置技术栈文本");
                return;
            }
            this.stack = extracted;
        } catch (Exception e) {
            log.warn("读取架构手册失败（PRD 格式化使用内置技术栈文本）：{}", e.getMessage());
        }
    }

    /** 异步格式化（看板手动触发），不阻塞调用方 */
    public void generateAsync(Long cardId) {
        CompletableFuture.runAsync(() -> {
            try {
                generateNow(cardId);
            } catch (Exception e) {
                log.warn("PRD 格式化异步执行异常 cardId={}: {}", cardId, e.getMessage());
            }
        });
    }

    /** 同步格式化一次：功能卡 + 方案卷轴 → 施工 PRD，markdown 直存 prdJson */
    public void generateNow(Long cardId) {
        IdeaCardEntity card = cardRepository.findById(cardId).orElse(null);
        if (card == null) {
            return;
        }
        card.markPrd(IdeaCardEntity.PRD_RUNNING, null);
        cardRepository.saveAndFlush(card);
        String prdStatus;
        String prdJson = null;
        try {
            String input = buildContext(card) + "\n\n" + stack;
            String markdown = ideaAgents.prdFormatter().formatBlueprint(input);
            prdJson = truncate(markdown, 12000);
            prdStatus = IdeaCardEntity.PRD_DONE;
        } catch (Exception e) {
            log.warn("PRD 格式化失败 cardId={}: {}", cardId, e.getMessage());
            prdStatus = IdeaCardEntity.PRD_FAILED;
        }
        // agent 调用期间卡片可能已被流转或删除：重读最新行落 PRD 结果，避免旧快照覆盖状态机
        final String status = prdStatus;
        final String json = prdJson;
        cardRepository.findById(cardId).ifPresent(fresh -> {
            fresh.markPrd(status, json);
            cardRepository.save(fresh);
        });
    }

    /** 取一张卡的 PRD（markdown 文本，下载/复制用），未出稿返回空 */
    public Optional<String> getMarkdown(Long cardId) {
        return cardRepository
                .findById(cardId)
                .map(IdeaCardEntity::getPrdJson)
                .filter(Objects::nonNull)
                .filter(md -> !md.isBlank());
    }

    /** 组装格式化输入：功能卡主字段 + 优先级 + 设计沉淀（格式化以此为准） */
    private String buildContext(IdeaCardEntity card) {
        return """
                【功能卡】
                标题：%s
                一句话：%s
                创意值：%d 星
                优先级：%s
                提出人：%s

                【设计沉淀】（主对话代理与客人共创：核心三要素 + 动态维度 + 边界兜底 + 关键项留痕，格式化以此为准）
                %s
                """.formatted(
                card.getTitle(),
                card.getSummary(),
                card.getCreativity(),
                card.getPriority() == null ? "中" : card.getPriority(),
                card.getNickname(),
                card.getDesignJson() == null || card.getDesignJson().isBlank()
                        ? "（无沉淀：按一句话说明补最小可行方案，不擅自扩大范围）"
                        : card.getDesignJson());
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
