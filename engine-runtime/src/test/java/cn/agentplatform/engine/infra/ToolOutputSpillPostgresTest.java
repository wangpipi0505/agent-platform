package cn.agentplatform.engine.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.*;
import cn.agentplatform.engine.harness.context.PromptAssembler;
import cn.agentplatform.engine.harness.context.SpillStore;
import cn.agentplatform.engine.harness.session.SessionStore;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * R-15（spill 接入工具环）：工具输出超限分支接 SpillStore 落盘与 PromptAssembler 按引用
 * 回填原文。机制规格=主方案 §5.4（上下文回填 locator+摘要+预览）；台账卡面验收=300KB
 * 工具输出——落盘成功、响应内为引用而非全量、后续轮次 PromptAssembler 取回原文一致；
 * `grep -rl SpillStore src/main` 除自身外的接线证据=AgentDriver/ToolOutputPolicy/
 * PromptAssembler/SpillWiringConfiguration。
 *
 * <p>诚实登记的降级口径也在本类定死：无会话关联（独立运行）保持 T14.2 报错口径（信封无
 * spill 引用、零落盘；外溢组件整体未装配的行为由既有 ToolOutputLargeResponsePostgresTest
 * 覆盖）；引用取回侧的归属核验（跨会话 404）与摘要复核（损坏拒绝）一并覆盖。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class ToolOutputSpillPostgresTest {
    static final String SESSION_PREFIX = "h15_";
    final TracePostgresTest fixture = new TracePostgresTest();
    static SpillStore spills;
    static SessionStore sessions;
    static JdbcTemplate db;

    @BeforeAll
    static void connect() throws Exception {
        TracePostgresTest.connect();
        var ds =
                new DriverManagerDataSource(
                        EngineTestDatabase.url(),
                        EngineTestDatabase.user(),
                        EngineTestDatabase.password());
        db = new JdbcTemplate(ds);
        spills = new SpillStore(db, new DataSourceTransactionManager(ds));
        sessions = new SessionStore(db, new DataSourceTransactionManager(ds));
    }

    @BeforeEach
    void clean() {
        fixture.clean();
        db.update("delete from engine.spill_artifact where session_id like ?", SESSION_PREFIX + "%");
        db.execute("ALTER TABLE engine.session_event DISABLE TRIGGER session_event_append_only_guard");
        db.update("delete from engine.session_event where session_id like ?", SESSION_PREFIX + "%");
        db.execute("ALTER TABLE engine.session_event ENABLE TRIGGER session_event_append_only_guard");
        db.update("delete from engine.chat_session where public_id like ?", SESSION_PREFIX + "%");
    }

    /** 会话夹具：spill_artifact.session_id FK 必须真实存在（V231）。 */
    String session() {
        String id = SESSION_PREFIX + UUID.randomUUID().toString().replace("-", "");
        db.update(
                "insert into engine.chat_session(public_id,tenant_id,principal_id,app_code,snapshot)"
                        + " values(?,'engine-test','alice','test','{}')",
                id);
        return id;
    }

    /** 工具声明（contract 即技能契约；无分页声明）。 */
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

    /**
     * 构造可通过 FrozenModels 校验的最小运行快照（照 ToolOutputLargeResponsePostgresTest
     * 口径），并按需注入会话关联（chatSession.sessionId，H1-5 受理口径）；返回可驱动的
     * run/node/config 视图。
     */
    AgentLoopBudgetPostgresTest.DrivenRun agentRun(String sessionId) {
        long r = fixture.run(), n = fixture.node(r, "READY");
        var entry = Json.read("{\"modelEntryId\":1}");
        var config =
                Json.tree(
                        Map.of(
                                "modelEntryId", 1,
                                "guard", Map.of("roles", List.of("EDITOR")),
                                "tools", List.of(skill(Json.object()))));
        var snapshot =
                Json.tree(
                        Map.of(
                                "appCode", "test",
                                "type", "CHAT",
                                "config", config,
                                "definition",
                                Map.of("nodes", List.of(), "edges", List.of()),
                                "dependencyVersions",
                                Map.of(
                                        "modelRefs",
                                        List.of(
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
                                                                        Json.canonical(entry))))))));
        if (sessionId != null)
            ((com.fasterxml.jackson.databind.node.ObjectNode) snapshot)
                    .set("chatSession", Json.tree(Map.of("sessionId", sessionId, "turnNo", 1)));
        TracePostgresTest.db.update(
                "update engine.run set snapshot=?::jsonb where id=?", Json.write(snapshot), r);
        var node = TracePostgresTest.store.claim(n, "replica");
        var run = TracePostgresTest.db.queryForMap("select * from engine.run where id=?", r);
        return new AgentLoopBudgetPostgresTest.DrivenRun(run, node, config);
    }

    /** 脚本化客户端：技能执行恒定返回全量；每轮模型请求体被捕入 bodies。 */
    RuntimeClients scripted(List<String> answers, List<String> bodies, JsonNode full) {
        return new RuntimeClients() {
            @Override
            public JsonNode post(String service, String path, Object body, Actor actor) {
                if (path.endsWith("/internal/model/measure"))
                    return Json.read(
                            "{\"inputTokens\":10,\"contextWindow\":100000,\"maxOutputTokens\":4096"
                                    + ",\"tokenizer\":\"fixture\",\"exact\":true}");
                return Json.tree(Map.of("status", "SUCCEEDED", "result", full.deepCopy()));
            }

            @Override
            public JsonNode chatStream(
                    Object body, Actor actor, java.util.function.Consumer<String> chunk) {
                bodies.add(Json.write(body));
                return Json.read(answers.get(bodies.size() - 1));
            }
        };
    }

    static String toolContentOf(String requestBody, String toolCallId) {
        for (JsonNode message : Json.read(requestBody).path("messages"))
            if (message.path("role").asText().equals("tool")
                    && message.path("tool_call_id").asText().equals(toolCallId))
                return message.path("content").asText();
        throw new AssertionError("请求缺少工具结果消息: " + toolCallId);
    }

    /** 300KB+ 锚点负载：唯一标记便于断言"全量不进模型上下文、原文可按引用取回"。 */
    static JsonNode oversized() {
        var items = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 5500; i++)
            items.add(
                    Map.of(
                            "id",
                            i,
                            "name",
                            "satellite-%04d".formatted(i),
                            "constellation",
                            i == 991 ? "LEO-SPILL-MARKER" : "LEO-ALPHA"));
        return Json.tree(Map.of("items", items, "total", 5500));
    }

    /** AgentDriver：外溢组件已装配（SpillStore + 捕获式内存 sink 工厂）。 */
    AgentDriver wiredDriver(
            RuntimeClients clients, AtomicReference<SpillStore.BufferedMemorySink> captured) {
        var agent = new AgentDriver(TracePostgresTest.store, clients, new ContextAssembler());
        agent.spills(spills);
        agent.spillSinkFactory(
                () -> {
                    var sink = new SpillStore.BufferedMemorySink();
                    captured.set(sink);
                    return sink;
                });
        return agent;
    }

    /** 测试读取侧：locator → 捕获 sink 的全文（生产=MinIO GET；接口契约一致）。 */
    private static SpillStore.StorageSource capturedSource(
            AtomicReference<SpillStore.BufferedMemorySink> captured) {
        return locator -> captured.get().toByteArray();
    }

    List<String> twoRoundAnswers() {
        return List.of(
                "{\"text\":\"\",\"toolCalls\":[{\"id\":\"tc1\",\"name\":\"lookup\","
                        + "\"arguments\":{\"q\":\"sat\"}}],\"usage\":{}}",
                "{\"text\":\"done\",\"usage\":{}}");
    }

    // === ① 300KB 工具输出：落盘成功 + 响应内为引用而非全量 ==================================

    @Test
    void oversizedToolOutputSpillsToStoreAndModelSeesReferenceNotFullBody() {
        String sessionId = session();
        var driven = agentRun(sessionId);
        JsonNode full = oversized();
        String serialized = Json.write(full);
        long bytes = serialized.getBytes(StandardCharsets.UTF_8).length;
        assertThat(bytes).isGreaterThan(300 * 1024).as("卡面锚点：300KB 工具输出");
        var answers = twoRoundAnswers();
        var bodies = new ArrayList<String>();
        var captured = new AtomicReference<SpillStore.BufferedMemorySink>();
        var terminal =
                wiredDriver(scripted(answers, bodies, full), captured)
                        .drive(
                                driven.run(),
                                driven.node(),
                                driven.config(),
                                Json.read("{}"),
                                TracePostgresTest.actor,
                                false);
        assertEquals("done", terminal.path("text").asText(), "运行不因超限失败，模型收口作答");
        assertThat(bodies).hasSize(2);

        // 响应内为引用而非全量：错误信封 + spill 引用节点；正文与唯一标记都不在模型可见面。
        String content = toolContentOf(bodies.get(1), "tc1");
        var envelope = Json.read(content);
        assertThat(envelope.path("error").path("code").asText()).isEqualTo("OUTPUT_TOO_LARGE");
        assertThat(envelope.path("error").path("detail").path("actualBytes").asLong()).isEqualTo(bytes);
        var spill = envelope.path("spill");
        assertThat(spill.path("spilled").asBoolean()).isTrue();
        assertThat(spill.path("spillId").asText()).matches("^s_[a-f0-9]{32}$");
        assertThat(spill.path("locator").asText()).startsWith("mem://spill/");
        assertThat(spill.path("bytes").asLong()).isEqualTo(bytes);
        assertThat(spill.path("sha256").asText()).isEqualTo(Digest.sha256(serialized), "sha256=全文摘要");
        assertThat(spill.path("preview").asText())
                .isEqualTo(serialized.substring(0, SpillStore.PREVIEW_BYTES), "preview=前 2KB");
        assertThat(content).doesNotContain("LEO-SPILL-MARKER");
        assertThat(content.length()).isLessThan(4096);

        // 落盘成功：spill_artifact 行（索引）与对象存储正文（sink 全文）一致。
        assertEquals(
                1,
                db.queryForObject(
                        "select count(*) from engine.spill_artifact where session_id=?",
                        Integer.class,
                        sessionId));
        var row = db.queryForMap("select * from engine.spill_artifact where session_id=?", sessionId);
        assertEquals(spill.path("spillId").asText(), String.valueOf(row.get("spill_id")));
        assertEquals(bytes, ((Number) row.get("bytes")).longValue());
        assertEquals(spill.path("locator").asText(), String.valueOf(row.get("storage_ref")));
        assertEquals("TOOL_RESULT", String.valueOf(row.get("kind")));
        assertEquals(
                serialized,
                new String(captured.get().toByteArray(), StandardCharsets.UTF_8),
                "对象存储正文与原始工具输出逐字节一致");
    }

    // === ② 后续轮次 PromptAssembler 按引用回填原文一致 =======================================

    @Test
    void promptAssemblerBackfillsOriginalContentByReferenceInLaterRound() {
        String sessionId = session();
        var driven = agentRun(sessionId);
        JsonNode full = oversized();
        String serialized = Json.write(full);
        var answers = twoRoundAnswers();
        var bodies = new ArrayList<String>();
        var captured = new AtomicReference<SpillStore.BufferedMemorySink>();
        wiredDriver(scripted(answers, bodies, full), captured)
                .drive(
                        driven.run(),
                        driven.node(),
                        driven.config(),
                        Json.read("{}"),
                        TracePostgresTest.actor,
                        false);
        var row =
                db.queryForMap(
                        "select spill_id, storage_ref from engine.spill_artifact where session_id=?",
                        sessionId);
        String spillId = String.valueOf(row.get("spill_id"));
        String locator = String.valueOf(row.get("storage_ref"));

        // 组装时按引用回填：{{spill:...}} token 还原为外溢原文（与落盘序列化产物逐字节一致）。
        var assembler = new PromptAssembler(sessions, spills, capturedSource(captured));
        var render =
                assembler.render(
                        sessionId,
                        Map.of(
                                PromptAssembler.Section.PLATFORM_FIXED,
                                "platform rules",
                                PromptAssembler.Section.RUNTIME_FACTS,
                                "外溢原文回填：{{ spill:" + spillId + " }}"),
                        Map.of(),
                        null);
        assertEquals(
                "platform rules\n\n外溢原文回填：" + serialized,
                render.content(),
                "回填原文与落盘序列化产物逐字节一致");
        assertTrue(render.content().contains("LEO-SPILL-MARKER"), "唯一标记只在回填产物中出现");
        assertEquals(Digest.sha256(render.content()), render.sha256());

        // 直接取回入口同样一致；跨会话归属核验拒绝（404，不区分泄露存在性）。
        assertEquals(serialized, assembler.retrieveSpill(sessionId, spillId));
        String otherSession = session();
        var cross =
                assertThrows(
                        PlatformException.class,
                        () -> assembler.retrieveSpill(otherSession, spillId));
        assertEquals(404, cross.status());
        assertEquals("SPILL_REF_NOT_FOUND", cross.code());

        // 摘要复核：读取侧返回损坏正文即拒绝，不回填疑似损坏内容。
        var tampered =
                new PromptAssembler(
                        sessions, spills, loc -> "tampered".getBytes(StandardCharsets.UTF_8));
        var digest =
                assertThrows(
                        PlatformException.class, () -> tampered.retrieveSpill(sessionId, spillId));
        assertEquals(500, digest.status());
        assertEquals("SPILL_DIGEST_MISMATCH", digest.code());
        assertThat(locator).startsWith("mem://spill/");
    }

    // === ③ 降级口径定死：无会话关联保持既有报错口径（信封无引用、零落盘） =====================

    @Test
    void unlinkedRunKeepsLegacyEnvelopeWithoutSpillRow() {
        var driven = agentRun(null); // 无 chatSession 注入
        JsonNode full = oversized();
        var answers = twoRoundAnswers();
        var bodies = new ArrayList<String>();
        var captured = new AtomicReference<SpillStore.BufferedMemorySink>();
        var terminal =
                wiredDriver(scripted(answers, bodies, full), captured)
                        .drive(
                                driven.run(),
                                driven.node(),
                                driven.config(),
                                Json.read("{}"),
                                TracePostgresTest.actor,
                                false);
        assertEquals("done", terminal.path("text").asText());
        String content = toolContentOf(bodies.get(1), "tc1");
        var envelope = Json.read(content);
        assertThat(envelope.path("error").path("code").asText()).isEqualTo("OUTPUT_TOO_LARGE");
        assertThat(envelope.has("spill")).as("无会话关联：不发明外溢引用").isFalse();
        assertThat(content).doesNotContain("LEO-SPILL-MARKER");
        assertNull(captured.get(), "零落盘：sink 工厂未被调用");
        assertEquals(
                0,
                db.queryForObject(
                        "select count(*) from engine.spill_artifact where session_id like ?",
                        Integer.class,
                        SESSION_PREFIX + "%"));
    }

    // === ④ 阈值内零变化：外溢组件已装配但输出不超限（不触碰 sink、原样进入上下文） =============

    @Test
    void withinThresholdWithWiringKeepsByteIdenticalContent() {
        String sessionId = session();
        var driven = agentRun(sessionId);
        var small = Json.read("{\"value\":1,\"note\":\"small enough\"}");
        var answers = twoRoundAnswers();
        var bodies = new ArrayList<String>();
        var captured = new AtomicReference<SpillStore.BufferedMemorySink>();
        var terminal =
                wiredDriver(scripted(answers, bodies, small), captured)
                        .drive(
                                driven.run(),
                                driven.node(),
                                driven.config(),
                                Json.read("{}"),
                                TracePostgresTest.actor,
                                false);
        assertEquals("done", terminal.path("text").asText());
        assertEquals(small.toString(), toolContentOf(bodies.get(1), "tc1"), "阈值内零变化锚点");
        assertNull(captured.get(), "阈值内 sink 工厂未被调用（SpillStore 内部阈值判定短路）");
        assertEquals(
                0,
                db.queryForObject(
                        "select count(*) from engine.spill_artifact where session_id=?",
                        Integer.class,
                        sessionId));
    }
}
