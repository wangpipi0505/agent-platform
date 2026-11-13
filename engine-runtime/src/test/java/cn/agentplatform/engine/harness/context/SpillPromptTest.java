package cn.agentplatform.engine.harness.context;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.*;
import cn.agentplatform.engine.domain.RuntimeFault;
import cn.agentplatform.engine.harness.session.SessionStore;
import cn.agentplatform.engine.infra.EngineTestDatabase;
import cn.agentplatform.engine.infra.RuntimeClients;
import cn.agentplatform.engine.infra.TraceStore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/**
 * T2-10（台账 §7.3）：spill 流式转存+PromptSection 五段白名单+输出校验策略 A15b。
 * 机制规格=主方案 §5.4（大结果外溢/提示装配）与 §6.7/接口稿 §5.5（A15b）；字段=数据库稿
 * §4.5 V231（T2-8 已交付 spill_artifact）。夹具 h10_% 前缀双向清理（BeforeEach+AfterEach）
 * 照 ChatTurnEventLinkageTest 口径；A15b 运行链清理照 TracePostgresTest 口径。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class SpillPromptTest {

    static JdbcTemplate db;
    static TraceStore store;
    static SpillStore spills;
    static SessionStore sessions;
    static PromptAssembler prompts;
    static final Actor actor = new Actor("engine-test", "alice", Set.of("EDITOR"), Map.of());

    @BeforeAll
    static void connect() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        EngineTestDatabase.url(),
                        EngineTestDatabase.user(),
                        EngineTestDatabase.password());
        db = new JdbcTemplate(ds);
        store = new TraceStore(db, new DataSourceTransactionManager(ds));
        sessions = new SessionStore(db, new DataSourceTransactionManager(ds));
        spills = new SpillStore(db, new DataSourceTransactionManager(ds));
        prompts = new PromptAssembler(sessions);
        org.flywaydb.core.Flyway.configure()
                .dataSource(ds)
                .schemas("engine")
                .defaultSchema("engine")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    /** 双向清理：防止本类残留行（chat_session/spill_artifact/运行链）阻断其他测试类清理。 */
    @BeforeEach
    @AfterEach
    void clean() {
        db.update("delete from engine.spill_artifact where session_id like 'h10_%'");
        db.execute("ALTER TABLE engine.session_event DISABLE TRIGGER session_event_append_only_guard");
        db.update("delete from engine.session_event where session_id like 'h10_%'");
        db.execute("ALTER TABLE engine.session_event ENABLE TRIGGER session_event_append_only_guard");
        db.update("delete from engine.chat_session where public_id like 'h10_%'");
        db.update("delete from engine.reproduction");
        db.update("delete from engine.model_invocation");
        db.update(
                "delete from engine.attempt_note where node_instance_id in(select n.id from"
                        + " engine.node_instance n join engine.run r on r.id=n.run_id where"
                        + " r.tenant_id='engine-test')");
        db.update(
                "delete from engine.run_event where run_id in(select id from engine.run where"
                        + " tenant_id='engine-test')");
        db.update(
                "delete from engine.tool_call where run_id in(select id from engine.run where"
                        + " tenant_id='engine-test')");
        db.update(
                "delete from engine.attempt where node_instance_id in(select n.id from"
                        + " engine.node_instance n join engine.run r on r.id=n.run_id where"
                        + " r.tenant_id='engine-test')");
        db.update(
                "delete from engine.node_instance where run_id in(select id from engine.run where"
                        + " tenant_id='engine-test')");
        db.update("delete from engine.run where tenant_id='engine-test'");
    }

    String session() {
        String id = "h10_" + UUID.randomUUID();
        db.update(
                "insert into engine.chat_session(public_id,tenant_id,principal_id,app_code,snapshot)"
                        + " values(?,'engine-test','alice','test','{}')",
                id);
        return id;
    }

    long run() {
        long id =
                db.queryForObject(
                        "insert into"
                                + " engine.run(public_id,app_code,debug_snapshot,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs,mode)"
                                + " values(?,'test','{}','DEBUG','RUNNING','alice','engine-test','pg:h10','{}','{}','{}','{}','{}','STEP')"
                                + " returning id",
                        Long.class,
                        "r_h10_" + UUID.randomUUID());
        db.update(
                "update engine.run set snapshot=?::jsonb where id=?",
                Json.write(Map.of("config", Map.of("tools", List.of()))),
                id);
        return id;
    }

    long node(long r, String state) {
        return db.queryForObject(
                "insert into"
                        + " engine.node_instance(run_id,node_def_id,step_seq,node_type,status,definition)"
                        + " values(?,'n1',1,'CODE',?,'{\"type\":\"CODE\",\"expression\":\"1\"}')"
                        + " returning id",
                Long.class,
                r,
                state);
    }

    // ------------------------------------------------------------------ spill

    /** 超阈值：sink 收到分块累计（每块<=窗口、累计和=全文字节、正文逐字节一致），spill_artifact 行落库，回填 locator 形状。 */
    @Test
    void spillOverThresholdStreamsChunksWritesRowAndBackfillsLocator() {
        String sid = session();
        var result = Json.object();
        var items = result.putArray("items");
        for (int i = 0; i < 4000; i++) items.add("item-" + i + "-" + "y".repeat(12));
        String text = Json.write(result);
        byte[] all = text.getBytes(StandardCharsets.UTF_8);
        assertTrue(all.length > SpillStore.WINDOW_BYTES, "夹具必须超过一个 64KB 窗口才能观察多块转存");
        assertTrue(all.length > 32768, "夹具必须超过测试阈值 32KB");

        var sink = new SpillStore.BufferedMemorySink();
        JsonNode backfill = spills.spill(sid, null, result, 32768, sink);

        // 分块累计：每块 <= 64KB 窗口，块和=全文字节，sink 正文与序列化字节逐字节一致
        var chunks = sink.chunkSizes();
        assertFalse(chunks.isEmpty(), "超阈值必须有分块写出");
        assertTrue(chunks.size() >= 2, "正文超过一个窗口应产生多块（实际块数=" + chunks.size() + "）");
        for (int chunk : chunks) assertTrue(chunk <= SpillStore.WINDOW_BYTES);
        assertEquals(all.length, chunks.stream().mapToInt(Integer::intValue).sum());
        assertArrayEquals(all, sink.toByteArray());

        // spill_artifact 行落库（bytes/sha256/preview/storage_ref/expires_at）
        assertEquals(
                1,
                db.queryForObject(
                        "select count(*) from engine.spill_artifact where session_id=?",
                        Integer.class,
                        sid));
        var row = db.queryForMap("select * from engine.spill_artifact where session_id=?", sid);
        String spillId = String.valueOf(row.get("spill_id"));
        String sha = String.valueOf(row.get("sha256"));
        String ref = String.valueOf(row.get("storage_ref"));
        long bytes = ((Number) row.get("bytes")).longValue();
        assertTrue(spillId.matches("^s_[a-f0-9]{32}$"), "spill_id 形状（V231 CHECK）：" + spillId);
        assertEquals(all.length, bytes);
        assertEquals(Digest.sha256(text), sha, "sha256=全文摘要");
        assertEquals("TOOL_RESULT", String.valueOf(row.get("kind")));
        assertNull(row.get("call_id"));
        assertNotNull(row.get("expires_at"));
        assertEquals(
                new String(all, 0, SpillStore.PREVIEW_BYTES, StandardCharsets.UTF_8),
                String.valueOf(row.get("preview")),
                "preview=前 2KB");
        assertTrue(ref.startsWith("mem://spill/"), "BufferedMemorySink 测试引用：" + ref);

        // 回填 locator 形状 {spilled:true, spillId, locator, preview, bytes, sha256}
        assertTrue(backfill.path("spilled").asBoolean());
        assertEquals(spillId, backfill.path("spillId").asText());
        assertEquals(ref, backfill.path("locator").asText(), "locator=sink 返回引用（storage_ref）");
        assertEquals(String.valueOf(row.get("preview")), backfill.path("preview").asText());
        assertEquals(bytes, backfill.path("bytes").asLong());
        assertEquals(sha, backfill.path("sha256").asText());
    }

    /** 阈值内零 spill（照 ToolOutputPolicy 口径）：原 result 同一实例返回、sink 零写入、不落索引行。 */
    @Test
    void spillBelowThresholdReturnsOriginalAndNeverTouchesSink() {
        String sid = session();
        var result = Json.tree(Map.of("ok", 1, "note", "small enough"));
        var sink = new SpillStore.BufferedMemorySink();
        JsonNode returned = spills.spill(sid, null, result, 65536, sink);
        assertSame(result, returned, "阈值内零变化：同一实例原样返回");
        assertFalse(sink.touched(), "阈值内 sink 零写入");
        assertEquals(
                0,
                db.queryForObject(
                        "select count(*) from engine.spill_artifact where session_id=?",
                        Integer.class,
                        sid));
    }

    // --------------------------------------------------------- PromptAssembler

    /** 五段有序拼装（与 Map 遍历序无关）+{{var}} 插值+白名单覆盖（RULES 可覆盖；平台固定段/运行时事实段拒绝）。 */
    @Test
    void promptSectionsAssembleInFixedOrderWithInterpolationAndWhitelist() {
        Map<PromptAssembler.Section, String> sections = new HashMap<>(); // 故意乱序：拼装序不受遍历序影响
        sections.put(PromptAssembler.Section.RUNTIME_FACTS, "facts for {{user}}");
        sections.put(PromptAssembler.Section.PLATFORM_FIXED, "platform fixed rules");
        sections.put(PromptAssembler.Section.SKILL_CATALOG, "skill catalog");
        sections.put(PromptAssembler.Section.TEMPLATE, "template about {{topic}}");
        sections.put(PromptAssembler.Section.RULES, "workspace rules");

        var render = prompts.render(sections, Map.of("user", "alice", "topic", "spill"), null);
        assertEquals(
                List.of(
                        PromptAssembler.Section.PLATFORM_FIXED,
                        PromptAssembler.Section.TEMPLATE,
                        PromptAssembler.Section.RULES,
                        PromptAssembler.Section.SKILL_CATALOG,
                        PromptAssembler.Section.RUNTIME_FACTS),
                render.sections(),
                "五段声明序=拼装序");
        assertEquals(
                "platform fixed rules\n\ntemplate about spill\n\nworkspace rules\n\nskill catalog\n\nfacts for alice",
                render.content());
        assertEquals(Digest.sha256(render.content()), render.sha256());

        // 白名单内覆盖：RULES 被 contextPolicy.sections 替换，其余段不动
        var policy = Json.read("{\"sections\":{\"RULES\":\"custom rules about {{topic}}\"}}");
        var overridden = prompts.render(sections, Map.of("user", "alice", "topic", "spill"), policy);
        assertEquals(
                "platform fixed rules\n\ntemplate about spill\n\ncustom rules about spill\n\nskill catalog\n\nfacts for alice",
                overridden.content());
        assertEquals(Set.of(PromptAssembler.Section.RULES), overridden.overridden());

        // 不可遮蔽段与未知段名：结构化拒绝（409 PROMPT_SECTION_OVERRIDE_DENIED）
        for (String denied :
                List.of("PLATFORM_FIXED", "RUNTIME_FACTS", "UNKNOWN_SECTION")) {
            var attempt = Json.read("{\"sections\":{\"" + denied + "\":\"hijack content\"}}");
            var error =
                    assertThrows(
                            PlatformException.class, () -> prompts.render(sections, Map.of(), attempt));
            assertEquals(409, error.status());
            assertEquals("PROMPT_SECTION_OVERRIDE_DENIED", error.code());
            assertTrue(error.getMessage().contains(denied), "拒绝原因记录被拒段：" + error.getMessage());
        }
        // 平台固定段正文在白名单覆盖后原样保留
        assertTrue(overridden.content().startsWith("platform fixed rules"));

        // 未知插值变量结构化报错（与模板渲染同口径）
        assertThrows(
                IllegalArgumentException.class,
                () -> prompts.render(Map.of(PromptAssembler.Section.RULES, "hi {{missing_var}}"), Map.of(), null));
    }

    /** 渲染产物落 system/message：变更时原位 replace（旧 prompt_render 消息被摘除），未变更不重复落。 */
    @Test
    void promptRenderLandsSystemMessageAndReplacesInPlaceOnChange() {
        String sid = session();
        long seq1 =
                prompts.publish(
                        sid,
                        Map.of(
                                PromptAssembler.Section.PLATFORM_FIXED, "v1 platform",
                                PromptAssembler.Section.RULES, "v1 rules"),
                        Map.of(),
                        null);
        var first = sessions.deriveMessages(sid, null);
        assertEquals(1, first.messages().size(), "渲染产物入模型历史投影");
        assertEquals("system", first.messages().getFirst().role());
        assertEquals(seq1, first.lastSeq());
        var content = first.messages().getFirst().content();
        assertEquals("v1 platform\n\nv1 rules", content.path("content").asText());
        assertEquals(PromptAssembler.RENDER_KIND, content.path("kind").asText());

        // 变更 → 原位 replace：投影内消息数不变（旧消息摘除），正文换新
        long seq2 =
                prompts.publish(
                        sid,
                        Map.of(
                                PromptAssembler.Section.PLATFORM_FIXED, "v1 platform",
                                PromptAssembler.Section.RULES, "v2 rules"),
                        Map.of(),
                        null);
        assertEquals(seq1 + 1, seq2);
        var after = sessions.deriveMessages(sid, null);
        assertEquals(1, after.messages().size(), "replace 后旧渲染消息不在投影中");
        assertEquals("v1 platform\n\nv2 rules", after.messages().getFirst().content().path("content").asText());
        // 同一产物重复发布：不重复落（watermark 不变）
        long again =
                prompts.publish(
                        sid,
                        Map.of(
                                PromptAssembler.Section.PLATFORM_FIXED, "v1 platform",
                                PromptAssembler.Section.RULES, "v2 rules"),
                        Map.of(),
                        null);
        assertEquals(seq2, again);
        assertEquals(seq2, sessions.watermark(sid));
    }

    // ------------------------------------------------------------------- A15b

    private static final String BAD_TEXT = "{\"name\":\"widget\"}"; // 缺 required: id
    private static final String OK_TEXT = "{\"id\":\"ok\"}";

    private static JsonNode inferencePolicy(int max, String... retryOn) {
        var policy = Json.object();
        policy.set("outputSchema", Json.read("{\"type\":\"object\",\"required\":[\"id\"]}"));
        var retry = policy.putObject("retry");
        retry.put("max", max);
        var on = retry.putArray("on");
        for (String code : retryOn) on.add(code);
        return policy;
    }

    /** 装配层翻译产物挂载后的模型请求（照 ModelExchangeTest 请求构造口径）。 */
    private static Map<String, Object> requestWith(JsonNode inferencePolicy) {
        var config = Json.read("{\"modelEntryId\":1}");
        var output = OutputValidationPolicy.translate(inferencePolicy);
        assertNotNull(output, "启用 outputSchema 后必须翻译出 output 契约");
        ((ObjectNode) config).set("output", output);
        return new ContextAssembler()
                .assemble(config, Json.read("{\"message\":\"m\"}"), List.of(), List.of());
    }

    /** 桩定模型：前 badUntil 次返回缺 required 字段的畸形响应，其后返回合法响应。 */
    private static RuntimeClients stub(AtomicInteger calls, int badUntil) {
        return new RuntimeClients() {
            @Override
            public JsonNode post(String service, String path, Object body, Actor a) {
                return Json.read(
                        "{\"inputTokens\":100,\"contextWindow\":10000,\"maxOutputTokens\":4096,\"tokenizer\":\"fixture\",\"exact\":true}");
            }

            @Override
            public JsonNode chatStream(Object body, Actor a, Consumer<String> chunks) {
                int n = calls.incrementAndGet();
                return Json.tree(
                        Map.of(
                                "text",
                                n <= badUntil ? BAD_TEXT : OK_TEXT,
                                "toolCalls",
                                List.of(),
                                "usage",
                                Map.of("totalTokens", 50)));
            }
        };
    }

    private long modelCallsConsumed(long runId) {
        return TraceStore.json(
                        db.queryForObject("select budget from engine.run where id=?", String.class, runId))
                .path("consumed")
                .path("modelCalls")
                .asLong();
    }

    private JsonNode validationOf(long runId, int attempt) {
        String raw =
                db.queryForObject(
                        "select output_validation from engine.model_invocation where run_id=? and"
                                + " kind='MAIN' and output_attempt_no=?",
                        String.class,
                        runId,
                        attempt);
        return Json.read(raw);
    }

    /** 翻译映射（接口稿 §5.5）：retry.on 含 OUTPUT_SCHEMA_INVALID=RETRY，其余 TERMINATE；存量无策略返回 null。 */
    @Test
    void translateMapsRetryOnToPolicyActionAndKeepsLegacyUntouched() {
        var retryable = OutputValidationPolicy.translate(inferencePolicy(3, "TRANSPORT", "OUTPUT_SCHEMA_INVALID"));
        assertEquals("RETRY", retryable.path("onInvalid").asText());
        assertEquals(3, retryable.path("maxRetries").asInt());
        assertEquals("OUTPUT_SCHEMA_INVALID", retryable.path("reasonCode").asText());
        assertEquals("json", retryable.path("format").path("type").asText());
        assertTrue(retryable.path("format").path("schema").path("required").isArray());

        var terminating = OutputValidationPolicy.translate(inferencePolicy(3, "TRANSPORT"));
        assertEquals("TERMINATE", terminating.path("onInvalid").asText(), "retry.on 不含 OUTPUT_SCHEMA_INVALID=终止");

        assertFalse(OutputValidationPolicy.enabled(Json.read("{}")), "无 outputSchema=未启用");
        assertNull(OutputValidationPolicy.translate(Json.read("{\"retry\":{\"max\":2}}")));
        assertEquals(
                "OUTPUT_INVALID",
                OutputValidationPolicy.reasonCode(Json.read("{\"format\":{\"type\":\"json\"}}")),
                "存量发布（无 reasonCode 标记）保持原原因码");
    }

    /** 装配层挂载：存量显式 output 契约优先（沿用原策略）；无 output 时以翻译产物挂载。 */
    @Test
    void applyFromManifestHonorsLegacyContractAndMountsTranslatedOne() {
        var withLegacy =
                Json.read(
                        "{\"model\":{\"inferencePolicy\":"
                                + Json.write(inferencePolicy(2, "OUTPUT_SCHEMA_INVALID"))
                                + "},\"output\":{\"format\":{\"type\":\"json\",\"schema\":{}},\"onInvalid\":\"PASS_WITH_FLAG\"}}");
        OutputValidationPolicy.applyFromManifest(withLegacy);
        assertEquals(
                "PASS_WITH_FLAG",
                withLegacy.path("output").path("onInvalid").asText(),
                "存量显式契约优先，沿用原 onInvalid 行为");

        var fresh =
                Json.read(
                        "{\"model\":{\"inferencePolicy\":"
                                + Json.write(inferencePolicy(2, "OUTPUT_SCHEMA_INVALID"))
                                + "}}");
        OutputValidationPolicy.applyFromManifest(fresh);
        assertEquals("RETRY", fresh.path("output").path("onInvalid").asText());
        assertEquals(2, fresh.path("output").path("maxRetries").asInt());
    }

    /** A15b RETRY 分支（成功路径）：前 2 次畸形+第 3 次合法 → 调用次数=retry.max+1、预算消耗同额、每 attempt 诊断落库。 */
    @Test
    void a15bRetryBranchRetriesWithinSameBudgetThenSucceeds() {
        var calls = new AtomicInteger();
        var clients = stub(calls, 2);
        long r = run(), n = node(r, "READY");
        var node = store.claim(n, "replica");
        var run = db.queryForMap("select * from engine.run where id=?", r);
        var answer =
                new ModelExchange(store, clients)
                        .exchange(
                                run,
                                node,
                                0,
                                requestWith(inferencePolicy(2, "OUTPUT_SCHEMA_INVALID")),
                                actor,
                                s -> {});
        assertEquals(3, calls.get(), "实际模型调用次数=retry.max+1（每次重试计入同一预算）");
        assertEquals("ok", answer.path("structured").path("id").asText());
        assertTrue(answer.path("outputValidation").path("valid").asBoolean());
        assertEquals(3, modelCallsConsumed(r), "预算消耗同额：modelCalls=实际调用次数");
        assertEquals(3, db.queryForObject("select count(*) from engine.model_invocation where run_id=? and kind='MAIN'", Integer.class, r));

        var first = validationOf(r, 0);
        assertFalse(first.path("valid").asBoolean());
        assertEquals("OUTPUT_SCHEMA_INVALID", first.path("semantic").asText(), "原因码投影");
        assertEquals("RETRY", first.path("onInvalid").asText(), "策略动作落库");
        assertTrue(first.path("reason").asText().contains("id"), "校验失败原因（networknt 本地化文案含缺失属性名）：" + first.path("reason").asText());
        var second = validationOf(r, 1);
        assertEquals("OUTPUT_SCHEMA_INVALID", second.path("semantic").asText());
        var last = validationOf(r, 2);
        assertTrue(last.path("valid").asBoolean(), "成功 attempt 诊断 valid=true");
    }

    /** A15b RETRY 分支（重试耗尽）：始终畸形 → 恰好 retry.max+1 次后终止，预算消耗同额，原因码 OUTPUT_SCHEMA_INVALID。 */
    @Test
    void a15bRetryExhaustionStopsAtRetryMaxPlusOneWithinBudget() {
        var calls = new AtomicInteger();
        var clients = stub(calls, Integer.MAX_VALUE);
        long r = run(), n = node(r, "READY");
        var node = store.claim(n, "replica");
        var run = db.queryForMap("select * from engine.run where id=?", r);
        var fault =
                assertThrows(
                        RuntimeFault.class,
                        () ->
                                new ModelExchange(store, clients)
                                        .exchange(
                                                run,
                                                node,
                                                0,
                                                requestWith(inferencePolicy(2, "OUTPUT_SCHEMA_INVALID")),
                                                actor,
                                                s -> {}));
        assertEquals("OUTPUT_SCHEMA_INVALID", fault.semantic, "终止原因码投影");
        assertEquals(3, calls.get(), "实际调用次数=retry.max+1，重试上限受预算约束");
        assertEquals(3, modelCallsConsumed(r), "预算消耗同额（每次实际调用计入同一预算）");
        for (int attempt : IntStream.rangeClosed(0, 2).toArray()) {
            var validation = validationOf(r, attempt);
            assertEquals("OUTPUT_SCHEMA_INVALID", validation.path("semantic").asText());
            assertEquals("RETRY", validation.path("onInvalid").asText());
            assertTrue(validation.path("reason").asText().contains("id"), "校验失败原因：" + validation.path("reason").asText());
        }
    }

    /** A15b TERMINATE 分支：retry.on 不含 OUTPUT_SCHEMA_INVALID → 首次失败即终止（调用次数=1）。 */
    @Test
    void a15bTerminateBranchStopsOnFirstSchemaInvalid() {
        var calls = new AtomicInteger();
        var clients = stub(calls, Integer.MAX_VALUE);
        long r = run(), n = node(r, "READY");
        var node = store.claim(n, "replica");
        var run = db.queryForMap("select * from engine.run where id=?", r);
        var fault =
                assertThrows(
                        RuntimeFault.class,
                        () ->
                                new ModelExchange(store, clients)
                                        .exchange(
                                                run,
                                                node,
                                                0,
                                                requestWith(inferencePolicy(2, "TRANSPORT")),
                                                actor,
                                                s -> {}));
        assertEquals("OUTPUT_SCHEMA_INVALID", fault.semantic);
        assertEquals(1, calls.get(), "TERMINATE：首次失败即终止，无重试");
        assertEquals(1, modelCallsConsumed(r));
        var validation = validationOf(r, 0);
        assertEquals("OUTPUT_SCHEMA_INVALID", validation.path("semantic").asText(), "原因码落库");
        assertEquals("TERMINATE", validation.path("onInvalid").asText(), "策略动作=终止");
        assertTrue(validation.path("reason").asText().contains("id"), "校验失败原因：" + validation.path("reason").asText());
        assertEquals(1, db.queryForObject("select count(*) from engine.model_invocation where run_id=? and kind='MAIN'", Integer.class, r));
    }
}
