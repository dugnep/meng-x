package com.mengx.server.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 通道注册表单测（零依赖）：注册表是分层路由的单一事实来源，
 * 信号词/强意图信号的增删直接影响路由行为，此处锁住关键回归点。
 */
class ChannelRegistryTest {

    private final ChannelRegistry registry = new ChannelRegistry();

    @Test
    void 默认通道注册且id为host() {
        assertEquals("host", ChannelRegistry.DEFAULT_CHANNEL);
        assertEquals("主对话", registry.get(ChannelRegistry.DEFAULT_CHANNEL).name());
    }

    @Test
    void 法学通道存在且可查() {
        ChannelSpec law = registry.get("law");
        assertEquals("法学助手", law.name());
    }

    @Test
    void 未知与null通道返回null() {
        assertNull(registry.get("medical"));
        assertNull(registry.get(null));
    }

    @Test
    void 默认通道无信号词_L1短路的前提() {
        for (ChannelSpec spec : registry.all()) {
            if (spec.id().equals(ChannelRegistry.DEFAULT_CHANNEL)) {
                assertTrue(spec.signals().isEmpty());
            }
        }
    }

    @Test
    void 法学强意图信号包含核心出题指令() {
        List<String> strong = registry.get("law").strongSignals();
        // 「继续出」曾因漏注册走进 L2 被误判 host，此处锁死
        for (String key : List.of("出题", "刷题", "继续出", "考考我", "出几道")) {
            assertTrue(strong.contains(key), "法学强意图信号缺失：" + key);
        }
    }

    @Test
    void 法学信号词命中日常法律咨询() {
        List<ChannelSpec> hits = registry.signalMatches("房东不退押金，这算侵权吗");
        assertEquals(1, hits.size());
        assertEquals("law", hits.get(0).id());
    }

    @Test
    void 闲聊无信号命中() {
        assertTrue(registry.signalMatches("今天天气不错").isEmpty());
        assertTrue(registry.signalMatches("帮我看看这个思路行不行").isEmpty());
        assertTrue(registry.signalMatches(null).isEmpty());
    }

    @Test
    void L2名册包含全部通道名() {
        String roster = registry.buildRoster();
        assertTrue(roster.contains("法学助手"));
        assertTrue(roster.contains("主对话"));
    }
}
