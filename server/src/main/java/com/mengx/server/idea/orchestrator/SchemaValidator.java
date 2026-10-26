package com.mengx.server.idea.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 结构化产物 Schema 校验器：集中校验卡片 JSON / 题目 JSON 等结构化输出的字段完整性，
 * 替代各编排点散落的 try-catch 与字段判空。
 *
 * 设计原则：只做"是否具备必要字段"的轻量校验，不做值范围约束（那是业务层职责）。
 * 校验失败返回 false，由调用方决定降级或重试。
 */
@Component
public class SchemaValidator {

    /** 功能卡 JSON 必要字段（核心三要素 + 标题） */
    private static final Set<String> CARD_REQUIRED = Set.of("title", "functions", "effects", "approach");

    /** 法考题 JSON 必要字段 */
    private static final Set<String> LAW_QUESTION_REQUIRED = Set.of("stem", "options", "answer");

    private final ObjectMapper objectMapper;

    public SchemaValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 校验 JSON 字符串是否符合指定 schema 类型。
     *
     * @param json       待校验 JSON 字符串
     * @param schemaType 类型：card / law_question
     * @return true=校验通过，false=JSON 非法或缺少必要字段
     */
    public boolean validate(String json, String schemaType) {
        if (json == null || json.isBlank()) {
            return false;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (Exception e) {
            return false;
        }
        if (node == null || node.isNull()) {
            return false;
        }
        Set<String> required = switch (schemaType) {
            case "card" -> CARD_REQUIRED;
            case "law_question" -> LAW_QUESTION_REQUIRED;
            default -> null;
        };
        if (required == null) {
            return true; // 未知类型放行，由调用方自行处理
        }
        for (String field : required) {
            JsonNode value = node.path(field);
            if (value.isMissingNode() || value.isNull()) {
                return false;
            }
            // 字符串/数组需非空
            if (value.isTextual() && value.asText().isBlank()) {
                return false;
            }
            if (value.isArray() && value.isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
