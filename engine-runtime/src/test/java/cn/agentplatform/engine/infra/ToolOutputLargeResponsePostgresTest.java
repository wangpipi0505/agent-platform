package cn.agentplatform.engine.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * PLAT-14 T14.2（分页/大响应策略）：大响应注入模型上下文前的行为定死与分页消费。
 *
 * <p>验收锚点为台账 T14.2 的大响应用例（P2-11 的 550KB 场景：约 5414 条星表条目
 * ≥550KB）。语义定死：超限不静默截断、不再整块塞给模型、也不让运行直接失败——
 * 模型可见的工具结果替换为 OUTPUT_TOO_LARGE 可读错误（code+userMessage+traceId+
 * 实际大小与上限）；契约声明了分页（contract.pagination，方案 §4.14 形状）的工具
 * 附分页元信息，模型可带页参续拉。阈值内输出与无声明工具的既有行为零变化。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class ToolOutputLargeResponsePostgresTest {
    final TracePostgresTest fixture = new TracePostgresTest();

    @BeforeAll
    static void connect() throws Exception {
        TracePostgresTest.connect();
    }

    /** 双向清理（R-19 回归补齐）：类内残留 run/model_invocation 链不得阻断共享库其他 engine-test 夹具清理。 */
    @BeforeEach
    @AfterEach
    void clean() {
        fixture.clean();
    }

    /** 工具声明：contract 即技能契约；无分页声明时传空对象。 */
    Map<String, Object> skill(JsonNode contract) {
        return Map.of(
                "code",
                "lookup",
                "name",
                "lookup",
                "skillVersionId",
                42,
                "contract",
                contract,
                "contractDigest",
                Digest.sha256("fixture-contract"),
                "definition",
                        Map.of(
                                "type",
                                "function",
                                "function",
                                Map.of("name", "lookup", "parameters", Map.of("type", "object"))));
    }

    /** 构造可通过 FrozenModels 校验的最小运行快照，并返回可驱动的 run/node 视图。 */
    AgentLoopBudgetPostgresTest.DrivenRun agentRun(JsonNode contract) {
        long r = fixture.run(), n = fixture.node(r, "READY");
        var entry = Json.read("{\"modelEntryId\":1}");
        var config =
                Json.tree(
                        Map.of(
                                "modelEntryId", 1,
                                "guard", Map.of("roles", java.util.List.of("EDITOR")),
                                "tools", java.util.List.of(skill(contract))));
        var snapshot =
                Json.tree(
                        Map.of(
                                "appCode", "test",
                                "type", "CHAT",
                                "config", config,
                                "definition",
                                        Map.of("nodes", java.util.List.of(), "edges", java.util.List.of()),
                                "dependencyVersions",
                                        Map.of(
                                                "modelRefs",
                                                java.util.List.of(
                                                        Map.of(
                                                                "modelEntryId",
                                                                1,
                                                                "entry",
                                                                entry,
                                                                "modelBindingRef",
                                                                        Map.of(
                                                                                "bindingId",
                                                                                1,
                                                                                "sha256",
                                                                                Digest.sha256(
                                                                                        Json.canonical(
                                                                                                entry))))))));
        TracePostgresTest.db.update(
                "update engine.run set snapshot=?::jsonb where id=?", Json.write(snapshot), r);
        var node = TracePostgresTest.store.claim(n, "replica");
        var run = TracePostgresTest.db.queryForMap("select * from engine.run where id=?", r);
        return new AgentLoopBudgetPostgresTest.DrivenRun(run, node, config);
    }

    /**
     * 脚本化客户端：/internal/model/measure 固定小值放行；技能执行按参数路由——
     * 带 page 参数返回分页小页，否则返回全量大响应。每轮模型请求体被捕入 bodies。
     */
    RuntimeClients scripted(
            List<String> answers, List<String> bodies, JsonNode full, JsonNode page) {
        return new RuntimeClients() {
            @Override
            public JsonNode post(String service, String path, Object body, Actor actor) {
                if (path.endsWith("/internal/model/measure"))
                    return Json.read(
                            "{\"inputTokens\":10,\"contextWindow\":100000,\"maxOutputTokens\":4096"
                                    + ",\"tokenizer\":\"fixture\",\"exact\":true}");
                var args = Json.tree(body).path("arguments");
                return Json.tree(
                        Map.of(
                                "status",
                                "SUCCEEDED",
                                "result",
                                args.path("page").asInt(0) > 0 ? page : full));
            }

            @Override
            public JsonNode chatStream(
                    Object body, Actor actor, java.util.function.Consumer<String> chunk) {
                bodies.add(Json.write(body));
                return Json.read(answers.get(bodies.size() - 1));
            }
        };
    }

    /** P2-11 锚点负载：5414 条星表条目，序列化后必须 ≥550KB；991 号条目带唯一标记。 */
    static JsonNode fullCatalog() {
        var items = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 5414; i++) {
            var item = new LinkedHashMap<String, Object>();
            item.put("id", i);
            item.put("name", String.format("satellite-%04d", i));
            item.put("constellation", i == 991 ? "LEO-ALPHA-MARKER" : "LEO-ALPHA");
            item.put("options", java.util.List.of("A", "B", "C"));
            item.put("elevationDeg", 45.2);
            item.put("visible", true);
            item.put("groundStation", "GS-" + (i % 32));
            items.add(item);
        }
        return Json.tree(Map.of("items", items, "total", 5414));
    }

    static long fullCatalogBytes(JsonNode full) {
        return Json.write(full).getBytes(StandardCharsets.UTF_8).length;
    }

    static String toolContentOf(String requestBody, String toolCallId) {
        for (JsonNode message : Json.read(requestBody).path("messages"))
            if (message.path("role").asText().equals("tool")
                    && message.path("tool_call_id").asText().equals(toolCallId))
                return message.path("content").asText();
        throw new AssertionError("请求缺少工具结果消息: " + toolCallId);
    }

    // === ① 550KB 大响应触发定死语义：可读错误，不炸、不静默截断、不整块塞给模型 =======

    @Test
    void oversizedOutputBecomesReadableErrorNeverModelBlob() {
        var driven = agentRun(Json.object());
        JsonNode full = fullCatalog();
        assertThat(fullCatalogBytes(full)).isGreaterThanOrEqualTo(550 * 1024);
        var answers =
                java.util.List.of(
                        "{\"text\":\"\",\"toolCalls\":[{\"id\":\"tc1\",\"name\":\"lookup\","
                                + "\"arguments\":{\"q\":\"sat\"}}],\"usage\":{}}",
                        "{\"text\":\"done\",\"usage\":{}}");
        var bodies = new ArrayList<String>();
        var terminal =
                new AgentDriver(
                                TracePostgresTest.store,
                                scripted(answers, bodies, full, Json.object()),
                                new ContextAssembler())
                        .drive(
                                driven.run(),
                                driven.node(),
                                driven.config(),
                                Json.read("{}"),
                                TracePostgresTest.actor,
                                false);
        // 定死语义：运行不因大响应失败，模型仍能收口作答。
        assertEquals("done", terminal.path("text").asText());
        assertThat(bodies).hasSize(2);
        String content = toolContentOf(bodies.get(1), "tc1");
        var error = Json.read(content).path("error");
        assertThat(error.path("code").asText()).isEqualTo("OUTPUT_TOO_LARGE");
        assertThat(error.path("semantic").asText()).isEqualTo("OUTPUT_TOO_LARGE");
        assertThat(error.path("userMessage").asText()).isNotBlank();
        assertThat(error.path("traceId").asText())
                .isNotBlank()
                .contains(Objects.toString(driven.run().get("public_id")));
        // 含实际大小与上限的可读信息（不静默截断的直接证据）。
        assertThat(error.path("detail").path("actualBytes").asLong())
                .isEqualTo(fullCatalogBytes(full));
        assertThat(error.path("detail").path("maxBytes").asLong()).isEqualTo(262144);
        // 大负载本体绝不进入模型上下文。
        assertThat(content).doesNotContain("LEO-ALPHA-MARKER");
        assertThat(content.length()).isLessThan(4096);
    }

    // === ② 阈值内输出零变化锚点（无声明工具不误伤） =======================================

    @Test
    void withinThresholdToolContentIsByteIdentical() {
        var driven = agentRun(Json.object());
        var small = Json.read("{\"value\":1}");
        var answers =
                java.util.List.of(
                        "{\"text\":\"\",\"toolCalls\":[{\"id\":\"tc1\",\"name\":\"lookup\","
                                + "\"arguments\":{\"q\":\"sat\"}}],\"usage\":{}}",
                        "{\"text\":\"done\",\"usage\":{}}");
        var bodies = new ArrayList<String>();
        var terminal =
                new AgentDriver(
                                TracePostgresTest.store,
                                scripted(answers, bodies, small, Json.object()),
                                new ContextAssembler())
                        .drive(
                                driven.run(),
                                driven.node(),
                                driven.config(),
                                Json.read("{}"),
                                TracePostgresTest.actor,
                                false);
        assertEquals("done", terminal.path("text").asText());
        assertThat(bodies).hasSize(2);
        // 阈值内：工具结果逐字原样进入模型上下文，与既有行为一致。
        assertEquals(small.toString(), toolContentOf(bodies.get(1), "tc1"));
    }

    // === ③ 声明分页的工具按声明处理：错误 + 分页元信息，模型带页参续拉成功 ==============

    @Test
    void declaredPaginationLetsModelRepullByPage() {
        var declaration =
                Json.read(
                        "{\"request\":{\"page\":\"page\",\"size\":\"size\"},"
                                + "\"response\":{\"itemsPath\":\"$.items\",\"totalPath\":\"$.total\"},"
                                + "\"maxPageSize\":200}");
        // 载体形状：技能契约里的 pagination 键（contract.pagination）。
        var driven = agentRun(Json.tree(Map.of("pagination", declaration)));
        JsonNode full = fullCatalog();
        var page1 =
                Json.read("{\"items\":[{\"id\":1},{\"id\":2},{\"id\":3}],\"page\":1,\"total\":5414}");
        var answers =
                java.util.List.of(
                        "{\"text\":\"\",\"toolCalls\":[{\"id\":\"tc1\",\"name\":\"lookup\","
                                + "\"arguments\":{\"q\":\"sat\"}}],\"usage\":{}}",
                        "{\"text\":\"\",\"toolCalls\":[{\"id\":\"tc2\",\"name\":\"lookup\","
                                + "\"arguments\":{\"q\":\"sat\",\"page\":1,\"size\":200}}],\"usage\":{}}",
                        "{\"text\":\"done\",\"usage\":{}}");
        var bodies = new ArrayList<String>();
        var terminal =
                new AgentDriver(
                                TracePostgresTest.store,
                                scripted(answers, bodies, full, page1),
                                new ContextAssembler())
                        .drive(
                                driven.run(),
                                driven.node(),
                                driven.config(),
                                Json.read("{}"),
                                TracePostgresTest.actor,
                                false);
        assertEquals("done", terminal.path("text").asText());
        assertThat(bodies).hasSize(3);
        // 第一轮：超限替换为错误 + 分页提示（声明参数/上限/按声明路径提取的观测值）。
        var hinted = Json.read(toolContentOf(bodies.get(1), "tc1"));
        assertThat(hinted.path("error").path("code").asText()).isEqualTo("OUTPUT_TOO_LARGE");
        assertThat(hinted.path("pagination").path("request").path("page").asText())
                .isEqualTo("page");
        assertThat(hinted.path("pagination").path("request").path("size").asText())
                .isEqualTo("size");
        assertThat(hinted.path("pagination").path("maxPageSize").asInt()).isEqualTo(200);
        assertThat(hinted.path("pagination").path("observed").path("total").asLong())
                .isEqualTo(5414);
        assertThat(hinted.path("pagination").path("hint").asText()).isNotBlank();
        // 第二轮：模型带页参续拉，小页原样进入模型上下文——大响应可分页消费。
        assertEquals(page1.toString(), toolContentOf(bodies.get(2), "tc2"));
    }

    // === ⑤ 引擎级阈值可配置：调小阈值即收紧，同一负载被拒且上报新上限 ======================

    @Test
    void configuredThresholdTakesEffectAtDriverLevel() {
        var driven = agentRun(Json.object());
        var medium = Json.tree(Map.of("blob", "x".repeat(4096)));
        var answers =
                java.util.List.of(
                        "{\"text\":\"\",\"toolCalls\":[{\"id\":\"tc1\",\"name\":\"lookup\","
                                + "\"arguments\":{\"q\":\"sat\"}}],\"usage\":{}}",
                        "{\"text\":\"done\",\"usage\":{}}");
        var bodies = new ArrayList<String>();
        var agent =
                new AgentDriver(
                        TracePostgresTest.store,
                        scripted(answers, bodies, medium, Json.object()),
                        new ContextAssembler());
        // 引擎级阈值可配置（Spring 装配走 agent.tool.output-max-bytes；这里直证驱动行为）。
        agent.outputPolicy(new ToolOutputPolicy(1024));
        var terminal =
                agent.drive(
                        driven.run(),
                        driven.node(),
                        driven.config(),
                        Json.read("{}"),
                        TracePostgresTest.actor,
                        false);
        assertEquals("done", terminal.path("text").asText());
        String content = toolContentOf(bodies.get(1), "tc1");
        assertThat(content).contains("OUTPUT_TOO_LARGE");
        assertThat(Json.read(content).path("error").path("detail").path("maxBytes").asLong())
                .isEqualTo(1024);
    }
}
