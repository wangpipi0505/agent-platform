package cn.agentplatform.engine.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.PlatformException;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * R-24（台账 §7.6b）：V901 种子 promptSource=DIRECT 与 engine 提示词装配口径对齐——
 * 锁死 promptText 判定路径（V901 四应用形状不再 409；未知值/空正文/TEMPLATE 缺正文仍 409）。
 * "播种→经真实网关发起 AGENT 任务→终态"全链由 R-24 网关端到端验收承载（evidence README）。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class DemoSeedPromptSourcePostgresTest {

    /** V901__demo_seed.sql satellite-planning-assistant 的 config 字面形状（:36/:40/:49/:55 同构）。 */
    static final String V901_DIRECT_CONFIG =
            "{\"modelBinding\":{\"providerCode\":\"demo-provider\",\"modelName\":\"demo-model\"},"
                + "\"promptSource\":\"DIRECT\","
                + "\"systemPrompt\":\"你是卫星规划助手，面向空间目标监测与地面目标遥感观测。\","
                + "\"maxTurns\":200}";

    static JsonNode config(String json) {
        return cn.agentplatform.common.Json.read(json, JsonNode.class);
    }

    @Test
    void v901DirectSeedAssemblesInlinePromptWithout409() {
        // 播种形状（V901）→ 装配不抛 409，主段=内联 systemPrompt 原文。
        String prompt = ContextAssembler.promptText(config(V901_DIRECT_CONFIG), cn.agentplatform.common.Json.tree(Map.of()));
        assertEquals("你是卫星规划助手，面向空间目标监测与地面目标遥感观测。", prompt);
    }

    @Test
    void missingPromptSourceKeepsLegacyInlineBehavior() {
        // 旧内联兼容：缺 promptSource 键 + systemPrompt → 原行为不变。
        String json = "{\"systemPrompt\":\"旧内联助手\"}";
        assertEquals("旧内联助手", ContextAssembler.promptText(config(json), cn.agentplatform.common.Json.tree(Map.of())));
    }

    @Test
    void directWithBlankBodyIs409() {
        var e = assertThrows(PlatformException.class,
                () -> ContextAssembler.promptText(
                        config("{\"promptSource\":\"DIRECT\"}"), cn.agentplatform.common.Json.tree(Map.of())));
        assertEquals(409, e.status());
        assertTrue(e.getMessage().contains("直连来源缺少内联正文"), e.getMessage());
    }

    @Test
    void templateWithoutFrozenPromptsIsStill409() {
        var e = assertThrows(PlatformException.class,
                () -> ContextAssembler.promptText(
                        config("{\"promptSource\":\"TEMPLATE\"}"), cn.agentplatform.common.Json.tree(Map.of())));
        assertEquals(409, e.status());
        assertTrue(e.getMessage().contains("模板主来源缺少冻结正文"), e.getMessage());
    }

    @Test
    void unknownPromptSourceIsStill409() {
        var e = assertThrows(PlatformException.class,
                () -> ContextAssembler.promptText(
                        config("{\"promptSource\":\"MAGIC\"}"), cn.agentplatform.common.Json.tree(Map.of())));
        assertEquals(409, e.status());
        assertTrue(e.getMessage().contains("仅 TEMPLATE/DIRECT"), e.getMessage());
    }
}
