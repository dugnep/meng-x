package com.mengx.server.idea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mengx.server.idea.IdeaProposalService.Observation;
import org.junit.jupiter.api.Test;

/**
 * 机会观察链路的确定性解析单测（零依赖）：覆盖观察器输出解析（围栏剥离/三态判定/
 * PROPOSE 证据闸门）。
 */
class IdeaProposalParseTest {

    // ==================== parseObservation ====================

    @Test
    void 解析_PROPOSE_四字段齐备() {
        Observation o = IdeaProposalService.parseObservation("""
                {"verdict":"PROPOSE","suggestTitle":"股价播报早茶","userStory":"想每天早上收到持仓股的重大公告摘要","gap":"现在只能自己一只只翻公告","signalQuote":"要是每天早上能帮我把持仓的公告筛一遍就好了"}
                """);
        assertTrue(o.propose());
        assertEquals("股价播报早茶", o.suggestTitle());
        assertEquals("想每天早上收到持仓股的重大公告摘要", o.userStory());
        assertEquals("现在只能自己一只只翻公告", o.gap());
        assertEquals("要是每天早上能帮我把持仓的公告筛一遍就好了", o.signalQuote());
    }

    @Test
    void 解析_PROPOSE_缺signalQuote判无效() {
        assertNull(IdeaProposalService.parseObservation(
                "{\"verdict\":\"PROPOSE\",\"suggestTitle\":\"t\",\"userStory\":\"u\",\"gap\":\"g\"}"));
    }

    @Test
    void 解析_PROPOSE_缺userStory判无效() {
        assertNull(IdeaProposalService.parseObservation(
                "{\"verdict\":\"PROPOSE\",\"suggestTitle\":\"t\",\"gap\":\"g\",\"signalQuote\":\"s\"}"));
    }

    @Test
    void 解析_NONE_仅verdict() {
        Observation o = IdeaProposalService.parseObservation(
                "{\"verdict\":\"NONE\"}");
        assertFalse(o.propose());
        assertFalse(o.exit());
        assertNull(o.suggestTitle());
        assertNull(o.signalQuote());
    }

    @Test
    void 解析_EXIT_仅verdict() {
        Observation o = IdeaProposalService.parseObservation(
                "{\"verdict\":\"EXIT\"}");
        assertTrue(o.exit());
    }

    @Test
    void 解析_小写verdict归一化() {
        assertTrue(IdeaProposalService.parseObservation(
                "{\"verdict\":\"propose\",\"userStory\":\"u\",\"signalQuote\":\"s\"}").propose());
        assertTrue(IdeaProposalService.parseObservation(
                "{\"verdict\":\"exit\"}").exit());
    }

    @Test
    void 解析_markdown围栏剥离() {
        Observation o = IdeaProposalService.parseObservation("""
                ```json
                {"verdict":"PROPOSE","suggestTitle":"围栏里的提案","userStory":"u","gap":"g","signalQuote":"s"}
                ```
                """);
        assertTrue(o.propose());
        assertEquals("围栏里的提案", o.suggestTitle());
    }

    @Test
    void 解析_围栏外带说明文字仍可截取JSON() {
        Observation o = IdeaProposalService.parseObservation(
                "观察结论如下：{\"verdict\":\"EXIT\"} 以上。");
        assertTrue(o.exit());
    }

    @Test
    void 解析_纯文本输出判无效() {
        assertNull(IdeaProposalService.parseObservation("这轮没什么好提议的，继续聊。"));
    }

    @Test
    void 解析_缺verdict判无效() {
        assertNull(IdeaProposalService.parseObservation("{\"suggestTitle\":\"t\"}"));
    }

    @Test
    void 解析_null输入判无效() {
        assertNull(IdeaProposalService.parseObservation(null));
    }
}
