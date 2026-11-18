package cn.agentplatform.engine.infra;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.ApprovalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** EX25：真实 PG 的跨 call 授予门及有限 HTTP 副作用见证；不把相同参数自动当成同一业务。 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class BusinessIdempotencyAcrossCallsTest {
    static JdbcTemplate db;
    static TraceStore store;
    String tenant;
    HttpServer business;
    final AtomicInteger sends = new AtomicInteger();
    final List<String> keys = new CopyOnWriteArrayList<>();
    static final String CONTRACT = Digest.sha256("ex25-fixed-contract");

    record Prepared(long run, long node, long call, String publicId, Actor owner, Map<String, Object> args, long version) {}
    record Ready(Prepared prepared, Map<String, Object> claim, String outboundKey) {}

    @BeforeAll static void database() {
        var ds = new DriverManagerDataSource(EngineTestDatabase.url(), EngineTestDatabase.user(), EngineTestDatabase.password());
        Flyway.configure().dataSource(ds).schemas("engine").defaultSchema("engine").locations("classpath:db/migration").load().migrate();
        db = new JdbcTemplate(ds); store = new TraceStore(db, new DataSourceTransactionManager(ds));
    }
    @BeforeEach void fixture() throws Exception {
        tenant = "ex25-" + UUID.randomUUID();
        business = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        business.createContext("/effect", exchange -> {
            exchange.getRequestBody().readAllBytes(); sends.incrementAndGet();
            keys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            byte[] bytes = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); } finally { exchange.close(); }
        });
        business.start();
        purgeActionPlans();
    }
    @AfterEach void close() { if (business != null) business.stop(0); purgeActionPlans(); }

    /**
     * 共享库卫生（R-19 合并回归补齐，照双向清理纪律）：本类 action_plan 行带 call_id FK
     * 且租户为 ex25-随机——不清理会以 FK 违例阻断 ChatTurnEventLinkageTest 等对 tool_call
     * 做无界清理的共享库夹具（BeforeEach+AfterEach 双向，含 heal 既有残留）。
     */
    private void purgeActionPlans() {
        db.update("delete from engine.action_plan where run_id in"
                + " (select id from engine.run where tenant_id like 'ex25-%')");
    }

    Prepared prepare(String scopedTenant, String intent, long version, Map<String, Object> args, boolean enabled) {
        var owner = new Actor(scopedTenant, "alice", Set.of("EDITOR"), Map.of());
        String publicId = "r_" + UUID.randomUUID().toString().replace("-", "");
        long run = db.queryForObject("insert into engine.run(public_id,app_code,debug_snapshot,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs,mode)"
                        + " values(?,'ex25','{}','DEBUG','RUNNING','alice',?,'ex25','{}','{}','{}','{}',?::jsonb,'STEP') returning id",
                Long.class, publicId, scopedTenant, Json.write(Map.of("businessIntentId", intent)));
        var approval = new LinkedHashMap<String, Object>(Map.of("mode", "PREPARE_EXECUTE", "risk", "STANDARD"));
        if (enabled) approval.put("businessIntentInput", "businessIntentId");
        db.update("update engine.run set snapshot=?::jsonb where id=?", Json.write(Map.of("config", Map.of("tools", List.of(
                Map.of("code", "write", "skillVersionId", version, "contractDigest", CONTRACT,
                        "execution", Map.of("sideEffect", true, "confirmRoles", List.of("APPROVER"), "approval", approval)))))), run);
        long node = db.queryForObject("insert into engine.node_instance(run_id,node_def_id,step_seq,node_type,status,definition)"
                + " values(?,'n',1,'CODE','READY','{\"type\":\"CODE\",\"expression\":\"1\"}') returning id", Long.class, run);
        var lease = store.claim(node, "prepare-worker");
        var calls = new ToolCalls(store);
        long call = TraceStore.number(calls.register(register(publicId, run, node, version, args, lease), owner), "callId");
        // 远端即使伪造 businessIdempotency，键仍必须取服务端冻结 run.inputs。
        calls.requestApproval(call, Json.tree(Map.of("roles", List.of("APPROVER"),
                "parametersDigest", Digest.sha256(Json.canonical(args)), "actionPlan", Map.of("summary", "明确业务写入", "target", "object-1",
                        "diff", Map.of("enabled", true), "risk", "STANDARD", "businessIdempotency", Map.of("intent", "remote-forged")))), owner);
        return new Prepared(run, node, call, publicId, owner, args, version);
    }

    JsonNode register(String id, long run, long node, long version, Map<String, Object> args, Map<String, Object> lease) {
        return Json.tree(Map.of("runId", id, "nodeInstanceId", node, "attemptId", lease.get("attemptId"), "callSeq", 1,
                "skillVersionId", version, "skillCode", "write", "arguments", args,
                "idempotencyKey", Digest.sha256(run + ":" + node + ":1:" + version), "leaseOwner", lease.get("lease_owner")));
    }

    Ready approve(Prepared p) {
        var approver = new Actor(p.owner().tenantId(), "bob", Set.of("APPROVER"), Map.of());
        var approvals = new ApprovalService(store);
        var item = approvals.list(approver).stream().filter(r -> ((Number) r.get("nodeInstanceId")).longValue() == p.node()).findFirst().orElseThrow();
        approvals.approve(p.publicId(), p.node(), Json.tree(Map.of("approved", true, "requestId", UUID.randomUUID().toString(),
                "snapshotDigest", item.get("snapshotDigest"))), approver);
        var lease = store.claim(p.node(), "execute-worker");
        var calls = new ToolCalls(store);
        calls.register(register(p.publicId(), p.run(), p.node(), p.version(), p.args(), lease), p.owner());
        var issued = Json.tree(calls.approval(p.call(), p.owner())).path("actionPlan");
        String key = issued.path("idempotencyKey").asText();
        return new Ready(p, Map.of("owner", lease.get("lease_owner"), "attemptId", lease.get("attemptId"),
                "executeKey", issued.path("executeKey").asText(key)), key);
    }

    Map<String, Object> plan(Prepared p) { return db.queryForMap("select * from engine.action_plan where call_id=?", p.call()); }
    Map<String, Object> claim(Ready r) { return new ToolCalls(store).claim(r.prepared().call(), Json.tree(r.claim()), r.prepared().owner()); }
    void effect(Ready r, String outcome) throws Exception {
        var p = r.prepared(); var calls = new ToolCalls(store);
        calls.submitted(p.call(), Json.tree(r.claim()), p.owner());
        try (var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + business.getAddress().getPort() + "/effect"))
                    .timeout(Duration.ofSeconds(2)).header("Idempotency-Key", r.outboundKey()).POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(), HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isEqualTo(200);
        }
        var fact = new LinkedHashMap<>(r.claim()); fact.put("status", outcome); fact.put("result", Map.of("ok", "SUCCEEDED".equals(outcome)));
        assertThat(calls.fact(p.call(), Json.tree(fact), p.owner()).get("accepted")).isEqualTo(true);
    }
    void code(Runnable operation, String expected) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(PlatformException.class, e -> {
            assertThat(e.status()).isEqualTo(409); assertThat(e.code()).isEqualTo(expected);
        });
    }

    @Test void expiredReprepareAcrossCallsKeepsOneBusinessEffectAndFrozenOldPlan() throws Exception {
        var first = approve(prepare(tenant, "intent-one", 42, Map.of("value", 1), true));
        var frozen = plan(first.prepared());
        db.update("update engine.action_plan set expires_at=now()-interval '1 second' where call_id=?", first.prepared().call());
        code(() -> claim(first), "ACTION_PLAN_EXPIRED");
        var second = approve(prepare(tenant, "intent-one", 42, Map.of("value", 1), true));
        assertThat(second.outboundKey()).isEqualTo(first.outboundKey());
        assertThat(plan(second.prepared()).get("execute_key")).isNotEqualTo(frozen.get("execute_key"));
        assertThat(claim(second).get("claimed")).isEqualTo(true); effect(second, "SUCCEEDED");
        db.update("update engine.action_plan set expires_at=now()-interval '1 second' where call_id=?", second.prepared().call());
        var third = approve(prepare(tenant, "intent-one", 42, Map.of("value", 1), true));
        code(() -> claim(third), "BUSINESS_INTENT_ALREADY_EXECUTED");
        assertThat(sends).hasValue(1); assertThat(keys).containsExactly(first.outboundKey());
        var unchanged = plan(first.prepared());
        for (String field : List.of("execute_key", "digest", "plan")) assertThat(unchanged.get(field).toString()).isEqualTo(frozen.get(field).toString());
    }

    @Test void sameIntentDifferentPayloadConflictsWhileExplicitOtherIntentsAndScopesStaySeparate() throws Exception {
        var original = prepare(tenant, "intent-one", 42, Map.of("value", 1), true);
        code(() -> prepare(tenant, "intent-one", 42, Map.of("value", 2), true), "BUSINESS_INTENT_PAYLOAD_CONFLICT");
        for (var p : List.of(original, prepare(tenant, "intent-two", 42, Map.of("value", 1), true),
                prepare(tenant + "-other", "intent-one", 42, Map.of("value", 1), true),
                prepare(tenant, "intent-one", 43, Map.of("value", 1), true))) {
            var ready = approve(p); assertThat(claim(ready).get("claimed")).isEqualTo(true); effect(ready, "SUCCEEDED");
        }
        assertThat(sends).hasValue(4); assertThat(new HashSet<>(keys)).hasSize(4);
    }

    @Test void concurrentCallsGrantOnceAndUnknownNeverReleasesWhileUndeclaredPlansKeepOldKey() throws Exception {
        var a = approve(prepare(tenant, "raced-intent", 42, Map.of("value", 1), true));
        var b = approve(prepare(tenant, "raced-intent", 42, Map.of("value", 1), true));
        assertThat(a.outboundKey()).isEqualTo(b.outboundKey());
        var gate = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        Ready winner;
        try {
            var outcomes = new ArrayList<Future<Ready>>();
            for (Ready r : List.of(a, b)) outcomes.add(pool.submit(() -> {
                assertThat(gate.await(5, TimeUnit.SECONDS)).isTrue();
                try { assertThat(claim(r).get("claimed")).isEqualTo(true); return r; }
                catch (PlatformException blocked) { assertThat(blocked.code()).isEqualTo("BUSINESS_INTENT_RESULT_UNKNOWN"); return null; }
            }));
            gate.countDown(); var granted = new ArrayList<Ready>();
            for (var result : outcomes) { Ready r = result.get(5, TimeUnit.SECONDS); if (r != null) granted.add(r); }
            assertThat(granted).hasSize(1); winner = granted.getFirst();
        } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
        effect(winner, "UNKNOWN");
        db.update("update engine.action_plan set expires_at=now()-interval '1 second' where call_id=?", winner.prepared().call());
        var retried = approve(prepare(tenant, "raced-intent", 42, Map.of("value", 1), true));
        code(() -> claim(retried), "BUSINESS_INTENT_RESULT_UNKNOWN"); assertThat(sends).hasValue(1);
        var old = approve(prepare(tenant, "unused-input", 42, Map.of("value", 1), false));
        var legacy = plan(old.prepared()); var core = (com.fasterxml.jackson.databind.node.ObjectNode) TraceStore.json(legacy.get("plan")).deepCopy();
        core.remove(List.of("idempotencyKey", "expiresAt"));
        assertThat(legacy.get("execute_key")).isEqualTo(Digest.sha256("EXECUTE:" + old.prepared().call() + ":" + Digest.sha256(Json.canonical(core))));
        assertThat(old.outboundKey()).isEqualTo(legacy.get("execute_key"));
    }
}
