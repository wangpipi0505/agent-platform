package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.*;

import com.fasterxml.jackson.databind.JsonNode;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/**
 * T19.1 准入钩子端到端（legacy 受理路径，HTTP 信封由既有 PlatformErrors→ApiResponse 组装）：
 * 三级配额超限在 engine.run 插入前以 QUOTA 拒绝（被拒运行不落库、admission 槽位照常释放），
 * 配额内受理放行（run 行以 PENDING 落库）。夹具直连 RunService（scheduler 为手工构造、不自动
 * 轮询），app 服务以本地 HttpServer 桩替代——模块内 @SpringBootTest 整容器与既有 fixture
 * Receiver 的 bean 冲突是已知挂起类问题（见 RunAdmissionHttpTest），不在此复刻。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class RunQuotaAdmissionPostgresTest {
    final TracePostgresTest fixture = new TracePostgresTest();
    HttpServer upstream;
    RunScheduler scheduler;

    @BeforeAll
    static void connect() throws Exception {
        TracePostgresTest.connect();
        // 进程级静态态（计划 §6.4-3/§7.5）：legacy 受理路径经 RuntimeClients.get → ServiceTokens.issue，
        // 需要 dev-hmac 部署模式注册；幂等注册保证单类隔离与多类同跑结果一致。
        SecurityConfiguration.bindDeployment(
                new MockEnvironment()
                        .withProperty("agent.security.mode", "dev-hmac")
                        .withProperty("agent.security.service-id", "engine")
                        .withProperty("agent.dev-auth", "true")
                        .withProperty("server.address", "127.0.0.1"));
    }

    @BeforeEach
    void clean() throws Exception {
        fixture.clean();
        var db = TracePostgresTest.db;
        db.update(
                "delete from engine.attempt_note where node_instance_id in(select n.id from"
                    + " engine.node_instance n join engine.run r on r.id=n.run_id where r.tenant_id"
                    + " like 'quota-%')");
        db.update(
                "delete from engine.run_event where run_id in(select id from engine.run where"
                    + " tenant_id like 'quota-%')");
        db.update(
                "delete from engine.tool_call where run_id in(select id from engine.run where"
                    + " tenant_id like 'quota-%')");
        db.update(
                "delete from engine.attempt where node_instance_id in(select n.id from"
                    + " engine.node_instance n join engine.run r on r.id=n.run_id where r.tenant_id"
                    + " like 'quota-%')");
        db.update(
                "delete from engine.model_invocation where run_id in(select id from engine.run"
                    + " where tenant_id like 'quota-%')");
        db.update(
                "delete from engine.node_instance where run_id in(select id from engine.run where"
                    + " tenant_id like 'quota-%')");
        db.update("delete from engine.run where tenant_id like 'quota-%'");
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext(
                "/",
                exchange -> {
                    byte[] bytes =
                            Json.write(ApiResponse.ok(snapshot())).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        upstream.start();
    }

    @AfterEach
    void stopFixture() {
        if (scheduler != null) scheduler.close();
        upstream.stop(0);
    }

    /** 已发布 FLOW 快照：主模型 modelEntryId=7，冻结引用自带 providerId（credential 级主体）。 */
    static JsonNode snapshot() {
        var entry =
                Json.tree(
                        Map.of(
                                "modelEntryId",
                                7,
                                "providerId",
                                3,
                                "modelName",
                                "model-a",
                                "baseUrl",
                                "https://model.example"));
        return Json.tree(
                Map.of(
                        "appCode",
                        "quotaapp",
                        "appVersionId",
                        1,
                        "versionNo",
                        1,
                        "type",
                        "FLOW",
                        "config",
                        Map.of("modelEntryId", 7),
                        "definition",
                        Map.of(
                                "nodes",
                                List.of(
                                        Map.of("id", "start", "type", "START"),
                                        Map.of("id", "end", "type", "END")),
                                "edges",
                                List.of(Map.of("from", "start", "to", "end")),
                                "outputs",
                                List.of(),
                                "executionRules",
                                Map.of("parallel", false, "maxParallel", 1)),
                        "dependencyVersions",
                        Map.of(
                                "modelRefs",
                                List.of(
                                        Map.of(
                                                "modelEntryId",
                                                7,
                                                "entry",
                                                entry,
                                                "modelBindingRef",
                                                Map.of(
                                                        "bindingId",
                                                        1,
                                                        "sha256",
                                                        Digest.sha256(Json.canonical(entry))))))));
    }

    RunQuota quota(boolean enabled, long window, long tenant, long app, long credential) {
        var q = new RunQuota(TracePostgresTest.db, Clock.systemUTC());
        q.quotaEnabled(enabled);
        q.windowSeconds(window);
        q.tenantTokens(tenant);
        q.appTokens(app);
        q.credentialTokens(credential);
        return q;
    }

    RunService service(RunQuota q) {
        var clients = new RuntimeClients();
        clients.app = "http://127.0.0.1:" + upstream.getAddress().getPort();
        var agent = new AgentDriver(TracePostgresTest.store, clients, new ContextAssembler());
        scheduler = new RunScheduler(TracePostgresTest.store);
        // 手工构造的 scheduler 不自动轮询；绑空 worker 使受理路径可走通，close() 在清理前排干
        // created() 认领出的在途 worker，避免清理与异步落行竞态。
        scheduler.bind(runId -> {}, () -> {}, () -> {});
        var runs =
                new RunService(
                        TracePostgresTest.store,
                        clients,
                        new FlowDriver(TracePostgresTest.store, agent),
                        agent,
                        scheduler);
        runs.runQuota(q);
        return runs;
    }

    long seedUsage(String tenant, String app, long tokens, Instant usageAt) {
        var db = TracePostgresTest.db;
        long r =
                db.queryForObject(
                        "insert into"
                            + " engine.run(public_id,app_code,app_version_id,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs,mode)"
                            + " values(?,?,1,'TASK','SUCCEEDED','alice',?,?,?::jsonb,'{}','{}','{}','{}','STEP')"
                            + " returning id",
                        Long.class,
                        "rq_" + UUID.randomUUID(),
                        app,
                        tenant,
                        "pg:quota/" + UUID.randomUUID(),
                        "{}");
        long n =
                db.queryForObject(
                        "insert into"
                            + " engine.node_instance(run_id,node_def_id,step_seq,node_type,status,definition)"
                            + " values(?,'n1',1,'AGENT','SUCCEEDED','{\"type\":\"AGENT\"}') returning id",
                        Long.class,
                        r);
        db.update(
                "insert into"
                    + " engine.model_invocation(run_id,node_instance_id,round_no,kind,source_request,response,status,usage_accounted,updated_at)"
                    + " values(?,?,'0','MAIN','{}'::jsonb,?::jsonb,'SUCCEEDED',true,?)",
                r,
                n,
                Json.write(
                        Map.of(
                                "usage",
                                Map.of("totalTokens", tokens),
                                "actualModel",
                                Map.of("providerId", 3, "modelName", "model-a"))),
                java.sql.Timestamp.from(usageAt));
        return r;
    }

    /** 计数排除计量种子 run（status=SUCCEEDED 的夹具事实行），只统计受理新插入的 run。 */
    int countRuns(String tenant, long excludeSeedRunId) {
        return TracePostgresTest
                .db
                .queryForObject(
                        "select count(*) from engine.run where tenant_id=? and id<>?",
                        Integer.class,
                        tenant,
                        excludeSeedRunId);
    }

    @Test
    void tenantOverrunRejectsBeforeRunInsertAndReleasesAdmissionSlot() {
        String tenant = "quota-http-" + UUID.randomUUID();
        long seeded = seedUsage(tenant, "quotaapp", 15000, Instant.now().minusSeconds(60));
        var actor = new Actor(tenant, "alice", Set.of("EDITOR"), Map.of());
        var q = quota(true, 3600, 10000, 20, 0);
        var runs = service(q);
        var rejected =
                assertThrows(
                        PlatformException.class,
                        () ->
                                runs.create(
                                        "quotaapp",
                                        Json.tree(
                                                Map.of(
                                                        "inputs",
                                                        Map.of(),
                                                        "requestId",
                                                        "q-" + UUID.randomUUID())),
                                        "TASK",
                                        actor));
        assertEquals("QUOTA", rejected.code());
        assertEquals(429, rejected.status());
        String message = rejected.getMessage();
        assertTrue(message.contains("租户"), () -> "userMessage 必须说明哪一级配额: " + message);
        assertTrue(message.contains("15000"), () -> "userMessage 必须含当前用量: " + message);
        assertTrue(message.contains("10000"), () -> "userMessage 必须含上限: " + message);
        assertEquals(0, countRuns(tenant, seeded), "超限拒绝不得插入 engine.run");
    }

    @Test
    void appLevelOverrunRejectsReadableBeforeRunInsert() {
        String tenant = "quota-http-" + UUID.randomUUID();
        long seeded = seedUsage(tenant, "quotaapp", 25, Instant.now().minusSeconds(60));
        var actor = new Actor(tenant, "alice", Set.of("EDITOR"), Map.of());
        var q = quota(true, 3600, 10000, 20, 0);
        var runs = service(q);
        var rejected =
                assertThrows(
                        PlatformException.class,
                        () ->
                                runs.create(
                                        "quotaapp",
                                        Json.tree(
                                                Map.of(
                                                        "inputs",
                                                        Map.of(),
                                                        "requestId",
                                                        "q-" + UUID.randomUUID())),
                                        "TASK",
                                        actor));
        assertEquals("QUOTA", rejected.code());
        String message = rejected.getMessage();
        assertTrue(message.contains("应用"), () -> "userMessage 必须说明哪一级配额: " + message);
        assertTrue(message.contains("25"), () -> "userMessage 必须含当前用量: " + message);
        assertTrue(message.contains("20"), () -> "userMessage 必须含上限: " + message);
        assertEquals(0, countRuns(tenant, seeded), "超限拒绝不得插入 engine.run");
    }

    @Test
    void withinQuotaAdmitsRunThroughSameHook() {
        String tenant = "quota-http-" + UUID.randomUUID();
        seedUsage(tenant, "quotaapp", 5, Instant.now().minusSeconds(60));
        var actor = new Actor(tenant, "alice", Set.of("EDITOR"), Map.of());
        var q = quota(true, 3600, 10000, 20, 0);
        var runs = service(q);
        var created =
                runs.create(
                        "quotaapp",
                        Json.tree(
                                Map.of(
                                        "inputs",
                                        Map.of(),
                                        "requestId",
                                        "q-" + UUID.randomUUID())),
                        "TASK",
                        actor);
        String publicId = created.get("runId").toString();
        Integer admitted =
                TracePostgresTest
                        .db
                        .queryForObject(
                                "select count(*) from engine.run where tenant_id=? and public_id=?",
                                Integer.class,
                                tenant,
                                publicId);
        assertEquals(1, admitted, "配额内受理应落库（状态可能已被受理后认领推进）");
    }
}
