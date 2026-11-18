package cn.agentplatform.engine.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.infra.EngineTestDatabase;
import cn.agentplatform.engine.infra.RuntimeClients;
import cn.agentplatform.engine.infra.TraceStore;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * R-19（T2-2 config_release 诚实占位改真实快照，台账 §7.6b）：任务受理在创建事务内钉住
 * 当次 config_release 真实快照——releaseId=appver-&lt;app_version 行 id&gt;、digest=发布时
 * 内容摘要 candidateSha256，来源=app-management 发布链（RuntimeClients
 * get("app","/internal/apps/{code}/published")）。验收：同一 requestId 重放读取到同一
 * 快照（重放不重复取快照、不跟随发布推进翻新）；发布新版本后新任务拿到新快照；发布
 * 不可得/无来源组装保持 T2-2 占位降级口径（unpinned- 前缀，如实登记）。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class TaskConfigReleasePinTest {

    static JdbcTemplate db;
    static TraceStore store;
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
        org.flywaydb.core.Flyway.configure()
                .dataSource(ds)
                .schemas("engine")
                .defaultSchema("engine")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    @AfterEach
    void clean() {
        db.execute("ALTER TABLE engine.session_event DISABLE TRIGGER session_event_append_only_guard");
        db.update("delete from engine.session_event where session_id like 'ti-%' or session_id like 'sa-%'");
        db.execute("ALTER TABLE engine.session_event ENABLE TRIGGER session_event_append_only_guard");
        db.update("delete from engine.subagent_catalog where parent_session_id like 'ti-%'");
        db.update("delete from engine.task_event where task_id in(select id from engine.task where tenant_id like 'h41-t-%')");
        db.update("delete from engine.task_step where task_id in(select id from engine.task where tenant_id like 'h41-t-%')");
        db.update("delete from engine.task where tenant_id like 'h41-t-%'");
        db.update("delete from engine.chat_session where public_id like 'ti-%' or public_id like 'sa-%'");
    }

    /** 发布链桩：/internal/apps/{code}/published 返回当前钉住的发布快照（可切换版本）。 */
    static RuntimeClients publishedStub(AtomicReference<JsonNode> published) {
        return new RuntimeClients() {
            @Override
            public JsonNode get(String service, String path, Actor a) {
                if (service.equals("app") && path.startsWith("/internal/apps/") && path.endsWith("/published")) {
                    JsonNode snapshot = published.get();
                    if (snapshot == null) throw new PlatformException(409, "APP_UNAVAILABLE", "应用未发布或已停用");
                    return snapshot;
                }
                throw new AssertionError("意外出站: " + service + path);
            }
        };
    }

    /** 发布快照形状（AppService.version 读回口径：appVersionId/versionId/candidateSha256/versionNo）。 */
    static JsonNode publishedSnapshot(String code, long versionRowId, int versionNo, String sha) {
        return Json.tree(
                Map.of(
                        "appCode", code,
                        "type", "AGENT",
                        "versionNo", versionNo,
                        "draftRev", versionNo,
                        "candidateSha256", sha,
                        "appVersionKey", code + "@" + versionNo,
                        "appVersionId", versionRowId,
                        "versionId", versionRowId,
                        "config", Map.of("model", Map.of("defaultBinding", Map.of("providerCode", "p", "modelName", "m"))),
                        "definition", Map.of("nodes", List.of(), "edges", List.of())));
    }

    static JsonNode taskReq(String requestId) {
        return Json.tree(Map.of("requestId", requestId, "mode", "AGENT", "goal", "差异报告", "inputs", Map.of("a", 1)));
    }

    Map<String, Object> taskRow(String requestId, String tenant) {
        return db.queryForMap(
                "select * from engine.task where tenant_id=? and request_id=?", tenant, requestId);
    }

    @Test
    void replayReadsSameSnapshotAndNewReleasePinsNewSnapshot() {
        String tenant = "h41-t-" + UUID.randomUUID().toString().substring(0, 8);
        String code = "h41-app";
        var published = new AtomicReference<JsonNode>(publishedSnapshot(code, 101L, 1, Digest.sha256("release-1-body")));
        var tasks = new Tasks(store, new SubagentPlanning(store), publishedStub(published));

        // 首受理：钉住发布链 v1 的真实快照 id 与内容摘要。
        var first = tasks.create(code, taskReq("r-pin-1"), new Actor(tenant, "alice", Set.of("EDITOR"), Map.of()));
        var row1 = taskRow("r-pin-1", tenant);
        assertEquals("appver-101", row1.get("config_release_id"), "releaseId=发布链真实快照行 id");
        assertEquals(Digest.sha256("release-1-body"), row1.get("config_digest"), "digest=发布时内容摘要");

        // 发布推进到 v2 后同 requestId 重放：读取到同一快照（重放分支先行返回，不重复取快照、
        // 不被新发布翻新——受理即钉住的时点语义）。
        published.set(publishedSnapshot(code, 102L, 2, Digest.sha256("release-2-body")));
        var replay =
                tasks.create(code, taskReq("r-pin-1"), new Actor(tenant, "alice", Set.of("EDITOR"), Map.of()));
        assertEquals(first.get("taskId"), replay.get("taskId"), "同号同参重放原任务");
        var rowAfterReplay = taskRow("r-pin-1", tenant);
        assertEquals("appver-101", rowAfterReplay.get("config_release_id"), "重放读取到同一快照");
        assertEquals(Digest.sha256("release-1-body"), rowAfterReplay.get("config_digest"));

        // 发布新版本后新任务：拿到新快照（新 releaseId+新摘要）。
        var second =
                tasks.create(code, taskReq("r-pin-2"), new Actor(tenant, "alice", Set.of("EDITOR"), Map.of()));
        assertNotEquals(first.get("taskId"), second.get("taskId"));
        var row2 = taskRow("r-pin-2", tenant);
        assertEquals("appver-102", row2.get("config_release_id"), "发布新版本后新任务拿到新快照");
        assertEquals(Digest.sha256("release-2-body"), row2.get("config_digest"));
        assertNotEquals(row1.get("config_digest"), row2.get("config_digest"));
    }

    @Test
    void unreachableOrMissingSourceKeepsPlaceholderDegrade() {
        String tenant = "h41-t-" + UUID.randomUUID().toString().substring(0, 8);
        String code = "h41-app-b";

        // 发布不可得（409 APP_UNAVAILABLE）：保持 T2-2 占位降级口径，受理不阻塞。
        var unpublished = new AtomicReference<JsonNode>(null);
        var degraded =
                new Tasks(store, new SubagentPlanning(store), publishedStub(unpublished))
                        .create(code, taskReq("r-pin-degrade"), new Actor(tenant, "alice", Set.of("EDITOR"), Map.of()));
        assertNotNull(degraded.get("taskId"));
        var row = taskRow("r-pin-degrade", tenant);
        assertTrue(
                String.valueOf(row.get("config_release_id")).startsWith("unpinned-"),
                "发布不可得=占位降级口径: " + row.get("config_release_id"));
        assertEquals(Digest.sha256("release-pin:" + tenant + ":" + code + ":v1"), row.get("config_digest"));

        // 无来源组装（测试构造，clients=null）：同一降级口径（既有行为零变化锚点）。
        new Tasks(store)
                .create(code, taskReq("r-pin-null"), new Actor(tenant, "alice", Set.of("EDITOR"), Map.of()));
        var rowNull = taskRow("r-pin-null", tenant);
        assertTrue(String.valueOf(rowNull.get("config_release_id")).startsWith("unpinned-"));

        // 快照缺 appVersionId（越形响应）：同样降级，不钉伪造身份。
        var malformed =
                new AtomicReference<>(Json.read("{\"appCode\":\"" + code + "\",\"type\":\"AGENT\"}"));
        new Tasks(store, new SubagentPlanning(store), publishedStub(malformed))
                .create(code, taskReq("r-pin-malformed"), new Actor(tenant, "alice", Set.of("EDITOR"), Map.of()));
        var rowMalformed = taskRow("r-pin-malformed", tenant);
        assertTrue(String.valueOf(rowMalformed.get("config_release_id")).startsWith("unpinned-"));
    }

    @Test
    void digestFallsBackToCanonicalSnapshotWhenCandidateShaMissing() {
        String tenant = "h41-t-" + UUID.randomUUID().toString().substring(0, 8);
        // 旧版本发布无 candidateSha256（越形）：按发布快照规范 JSON 现算（同版本确定一致）。
        var published =
                new AtomicReference<JsonNode>(
                        Json.read(
                                "{\"appCode\":\"h41-app-c\",\"type\":\"AGENT\",\"versionNo\":1,"
                                        + "\"appVersionId\":103,\"versionId\":103,\"config\":{},"
                                        + "\"definition\":{\"nodes\":[]}}"));
        var tasks = new Tasks(store, new SubagentPlanning(store), publishedStub(published));
        tasks.create("h41-app-c", taskReq("r-pin-canon-1"), new Actor(tenant, "alice", Set.of("EDITOR"), Map.of()));
        tasks.create("h41-app-c", taskReq("r-pin-canon-2"), new Actor(tenant, "alice", Set.of("EDITOR"), Map.of()));
        var expected = Digest.sha256(Json.canonical(published.get()));
        assertEquals("appver-103", taskRow("r-pin-canon-1", tenant).get("config_release_id"));
        assertEquals(expected, taskRow("r-pin-canon-1", tenant).get("config_digest"), "现算摘要=快照规范摘要");
        assertEquals(expected, taskRow("r-pin-canon-2", tenant).get("config_digest"), "同版本两任务同摘要");
    }
}
