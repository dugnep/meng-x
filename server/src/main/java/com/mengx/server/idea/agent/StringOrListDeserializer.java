package com.mengx.server.idea.agent;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * List&lt;String&gt; 字段的解析容错：模型偶发把数组字段输出成单个字符串
 * （如 "tools":"待聊：需要平台销量数据"），Jackson 默认直接抛 MismatchedInputException。
 * 这里兼容三种形态：字符串 → 单元素列表（空串归空列表）、数组 → 逐项字符串透传、null → null。
 */
public class StringOrListDeserializer extends JsonDeserializer<List<String>> {

    @Override
    public List<String> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken token = p.currentToken();
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }
        if (token == JsonToken.VALUE_STRING) {
            String text = p.getText();
            return text == null || text.isBlank() ? new ArrayList<>() : new ArrayList<>(List.of(text));
        }
        if (token == JsonToken.START_ARRAY) {
            List<String> items = new ArrayList<>();
            while (p.nextToken() != JsonToken.END_ARRAY) {
                if (p.currentToken() == JsonToken.VALUE_NULL || p.currentToken().isStructStart()) {
                    continue; // 空项与嵌套结构跳过（schema 约定数组元素是字符串）
                }
                String item = p.getValueAsString();
                if (item != null && !item.isBlank()) {
                    items.add(item);
                }
            }
            return items;
        }
        // 其他形态（数字/布尔/对象）：取文本兜底为单元素
        String fallback = p.getValueAsString();
        return fallback == null || fallback.isBlank() ? new ArrayList<>() : new ArrayList<>(List.of(fallback));
    }
}
