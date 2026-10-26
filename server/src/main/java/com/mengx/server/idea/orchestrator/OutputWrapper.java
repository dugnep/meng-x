package com.mengx.server.idea.orchestrator;

import org.springframework.stereotype.Component;

/**
 * 输出包装层：结构化产物的 Schema 校验入口（卡片 JSON 等），委托 {@link SchemaValidator}。
 *
 * 旧版的免责声明注入（wrap + ResponseMetadata 元信息打标）依赖路由架构的
 * domainFrame 判定，已随路由下线失效——法学免责声明现由 LawDomainTool 在
 * 回复尾部直接注入（展示与落库一致），新领域免责在各 DomainTool 内自理。
 */
@Component
public class OutputWrapper {

    private final SchemaValidator schemaValidator;

    public OutputWrapper(SchemaValidator schemaValidator) {
        this.schemaValidator = schemaValidator;
    }

    /** 校验结构化产物（卡片 JSON / 题目 JSON 等），不合法返回 false。 */
    public boolean validateSchema(String json, String schemaType) {
        return schemaValidator.validate(json, schemaType);
    }
}
