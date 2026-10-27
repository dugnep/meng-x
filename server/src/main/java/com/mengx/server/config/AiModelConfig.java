package com.mengx.server.config;

import com.mengx.server.llm.ConcurrencyGuardedStreamingModel;
import com.mengx.server.llm.ZhipuUsageListener;
import dev.langchain4j.community.model.zhipu.ZhipuAiChatModel;
import dev.langchain4j.community.model.zhipu.chat.Thinking;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 智谱 GLM 模型配置（langchain4j 官方社区集成 langchain4j-community-zhipu-ai）：
 * host/agent 档走 langchain4j-open-ai 客户端 + 智谱 OpenAI 兼容端点（同一 api-key）：
 * glm-5.3-flash 为强制思考模型（thinking.type 仅 enabled 合法），思考强度经顶层参数
 * reasoning_effort 控制（low/high/max），zhipu-ai 社区模块不支持该参数故换道 open-ai 客户端
 * （host 档换道另有原因：glm-4-flash 免费档无视注入的路由指令，153 同 payload 四连逃逸定案弃用）；
 * route 档（glm-4-flash 非思考）沿用 zhipu 客户端传 thinking disabled 兼容。
 * 三档分工：
 * 流式档（StreamingChatModel）= 前台主对话 Host（路由+直答）、法学助手、共创领域顾问；
 * 同步档（ChatModel，@Primary）= 内部结构化 agent（需求澄清双官/观察器/装配器/PRD 格式化器）；
 * 路由档（ChatModel "routeChatModel"）= 意图路由等轻量二分类。
 * 重试：同步档 maxRetries=2（限流/网络故障退避重试一次）；流式档沿用官方默认
 * （仅首响应失败重试，已输出正文后断流不重试），与项目低并发退避策略一致。
 * api-key 缺失时返回 null（Bean 不注册），应用仍可启动，调用时给出明确错误。
 */
@Configuration
public class AiModelConfig {

    private static final Logger log = LoggerFactory.getLogger(AiModelConfig.class);

    @Value("${ai.model.base-url}")
    private String baseUrl;

    @Value("${ai.model.api-key:}")
    private String apiKey;

    /** 前台主对话代理/法学助手（流式对话） */
    @Value("${ai.model.host-name:glm-4-flash}")
    private String hostName;

    @Value("${ai.model.host-temperature:0.7}")
    private Double hostTemperature;

    @Value("${ai.model.host-max-tokens:2048}")
    private Integer hostMaxTokens;

    /** 思考强度（host 档 5.3-flash 强制思考，reasoning_effort 降档省 token）：low/high/max */
    @Value("${ai.model.host-reasoning-effort:}")
    private String hostReasoningEffort;

    /**
     * host 流式并发闸：流式请求自发出至流结束全程占一路；
     * 闸 15 路内直通、超出排队（多用户高峰优雅降级），留 5 路余量给路由档与流式首响重试
     */
    @Value("${ai.model.host-concurrency-permits:15}")
    private Integer hostConcurrencyPermits;

    /** 并发闸排队上限：等待超过该秒数走前端 error 兜底，避免无限挂起 */
    @Value("${ai.model.host-acquire-timeout-seconds:60}")
    private Long hostAcquireTimeoutSeconds;

    /** 内部结构化 agent（需求澄清双官/观察器/装配器/PRD 格式化器等） */
    @Value("${ai.model.agent-name:glm-4-plus}")
    private String agentName;

    @Value("${ai.model.agent-temperature:0.2}")
    private Double agentTemperature;

    @Value("${ai.model.agent-max-tokens:4096}")
    private Integer agentMaxTokens;

    /** 思考强度（GLM-5.3 系列强制思考，经 reasoning_effort 降档省 token）：low/high/max */
    @Value("${ai.model.agent-reasoning-effort:}")
    private String agentReasoningEffort;

    /** 意图路由等轻量分类（glm-4-flash 非思考快速档） */
    @Value("${ai.model.route-name:glm-4-flash}")
    private String routeName;

    @Value("${ai.model.route-temperature:0.1}")
    private Double routeTemperature;

    @Value("${ai.model.route-max-tokens:256}")
    private Integer routeMaxTokens;

    @Value("${ai.model.route-thinking:disabled}")
    private String routeThinking;

    @Value("${ai.model.timeout-seconds:60}")
    private Integer timeoutSeconds;

    private boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 按档构建思考力度参数（type 值由服务端校验：disabled/low/high/max 等） */
    private Thinking thinking(String type) {
        return Thinking.builder().type(type).build();
    }

    /**
     * 官方客户端在 baseUrl 后以相对路径拼接 api/paas/v4/chat/completions，
     * 故 baseUrl 只能是主机根（默认 https://open.bigmodel.cn/）。
     * 归一化：剥掉尾部斜杠与历史配置里写全的 /api/paas/v4 后缀，再补回一个尾斜杠，
     * 兼容 application-local.yml 中旧格式的完整端点 URL。
     */
    private String rootBaseUrl() {
        String url = baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        String suffix = "/api/paas/v4";
        if (url.endsWith(suffix)) {
            url = url.substring(0, url.length() - suffix.length());
        }
        return url + "/";
    }

    /**
     * 流式模型（主对话 Host 路由/直答、法学助手、共创领域顾问），外包并发闸防多用户高峰打爆智谱配额。
     * 走 langchain4j-open-ai 客户端 + 智谱 OpenAI 兼容端点（同 api-key），与 agent 档同模式：
     * host 档 glm-5.3-flash 为强制思考模型，reasoning_effort 是思考强度唯一控制途径
     * （zhipu-ai 社区模块不支持该参数）；OpenAI 客户端不发 thinking 字段，服务端默认
     * 开启思考，行为与显式 enabled 等价。流式 tool_calls 走 onCompleteToolCall、
     * 思考增量走 onPartialThinking（langchain4j-open-ai 1.18 实测支持，正文不受污染）。
     */
    @Bean
    public StreamingChatModel streamingChatModel() {
        if (!configured()) {
            log.warn("ai.model.api-key 未配置，跳过流式模型初始化（对话接口不可用）");
            return null;
        }
        log.info("初始化流式模型（主对话/法学/共创顾问，OpenAI 兼容端点）: model={}, temperature={}, reasoning_effort={}, 并发闸={}路/排队{}s",
                hostName, hostTemperature, hostReasoningEffort, hostConcurrencyPermits, hostAcquireTimeoutSeconds);
        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder = OpenAiStreamingChatModel.builder()
                .baseUrl(rootBaseUrl() + "api/paas/v4/")
                .apiKey(apiKey)
                .modelName(hostName)
                .temperature(hostTemperature)
                .maxTokens(hostMaxTokens)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .listeners(List.of(new ZhipuUsageListener(true)));
        if (hostReasoningEffort != null && !hostReasoningEffort.isBlank()) {
            builder.reasoningEffort(hostReasoningEffort);
        }
        return new ConcurrencyGuardedStreamingModel(builder.build(),
                hostConcurrencyPermits, hostAcquireTimeoutSeconds);
    }

    /**
     * 同步模型（需求澄清双官/观察器/装配器/PRD 格式化器等结构化 agent）；@Primary 供无 qualifier 注入。
     * 走 langchain4j-open-ai 客户端 + 智谱 OpenAI 兼容端点（同 api-key）：
     * 仅为传递 reasoning_effort（GLM-5.3 系列思考强度唯一控制途径，zhipu-ai 社区模块未实现该参数）；
     * OpenAI 客户端不发 thinking 字段，服务端对 5.3-flash 默认开启思考，行为与显式 enabled 等价。
     */
    @Bean
    @Primary
    public ChatModel chatModel() {
        if (!configured()) {
            log.warn("ai.model.api-key 未配置，跳过同步模型初始化");
            return null;
        }
        log.info("初始化同步模型（结构化 agent，OpenAI 兼容端点）: model={}, temperature={}, reasoning_effort={}",
                agentName, agentTemperature, agentReasoningEffort);
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(rootBaseUrl() + "api/paas/v4/")
                .apiKey(apiKey)
                .modelName(agentName)
                .temperature(agentTemperature)
                .maxTokens(agentMaxTokens)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .maxRetries(2)
                .listeners(List.of(new ZhipuUsageListener(false)));
        if (agentReasoningEffort != null && !agentReasoningEffort.isBlank()) {
            builder.reasoningEffort(agentReasoningEffort);
        }
        return builder.build();
    }

    /** 路由模型（意图路由二分类，glm-4-flash 快速档） */
    @Bean("routeChatModel")
    public ChatModel routeChatModel() {
        if (!configured()) {
            return null;
        }
        log.info("初始化路由模型（意图分类）: model={}, temperature={}, thinking={}",
                routeName, routeTemperature, routeThinking);
        return ZhipuAiChatModel.builder()
                .baseUrl(rootBaseUrl())
                .apiKey(apiKey)
                .model(routeName)
                .temperature(routeTemperature)
                .maxToken(routeMaxTokens)
                .thinking(thinking(routeThinking))
                .callTimeout(Duration.ofSeconds(timeoutSeconds))
                .connectTimeout(Duration.ofSeconds(15))
                .maxRetries(2)
                .listeners(List.of(new ZhipuUsageListener(false)))
                .build();
    }
}
