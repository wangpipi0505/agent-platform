package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;
import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 真实 PG 取消事务和签名 HTTP；接收器只证明取消通知/收据，不冒充真实 PROCESS 杀树。 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class EngineProcessCancellationTest {
    static JdbcTemplate db;
    static TraceStore store;
    static HttpServer peer;
    static ExecutorService executor;
    static String origin, priorSecret;
    static volatile Actor actor;
    static final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    static final AtomicReference<Throwable> peerFailure = new AtomicReference<>();
    static final AtomicBoolean fail = new AtomicBoolean();
    RuntimeClients clients;
    RunScheduler scheduler;
    RunService runs;
    record Call(long id, long run, long node, long attempt, String publicRun, String owner, long version) {}

    @BeforeAll static void database() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/agent_platform_engine_test", EngineTestDatabase.user(), EngineTestDatabase.password());
        org.flywaydb.core.Flyway.configure().dataSource(ds).schemas("engine").defaultSchema("engine").locations("classpath:db/migration").load().migrate();
        db = new JdbcTemplate(ds); store = new TraceStore(db, new DataSourceTransactionManager(ds));
        priorSecret = System.getProperty("agent.jwt.secret"); System.setProperty("agent.jwt.secret", "engine-cancel-protocol-fixture-key-32-characters");
        var env = new MockEnvironment().withProperty("agent.security.mode", "test").withProperty("agent.security.service-id", "engine")
                .withProperty("server.address", "127.0.0.1"); env.setActiveProfiles("test"); SecurityConfiguration.bindDeployment(env);
        peer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); executor = Executors.newSingleThreadExecutor(); peer.setExecutor(executor);
        peer.createContext("/internal/skill-calls/", exchange -> {
            try (exchange) {
                try {
                    assertEquals("POST", exchange.getRequestMethod());
                    var token = SignedJWT.parse(exchange.getRequestHeaders().getFirst("Authorization").substring(7));
                    assertTrue(token.verify(new MACVerifier(ServiceTokens.secretBytes())));
                    assertEquals(actor.tenantId(), token.getJWTClaimsSet().getStringClaim("tenant_id"));
                    assertEquals(actor.principalId(), token.getJWTClaimsSet().getSubject());
                    assertTrue(token.getJWTClaimsSet().getStringListClaim("roles").contains("SERVICE"));
                    String path = exchange.getRequestURI().getPath(); assertTrue(path.matches("/internal/skill-calls/[1-9][0-9]*/cancel"));
                    JsonNode body = Json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    var fields = new HashSet<String>(); body.fieldNames().forEachRemaining(fields::add);
                    assertEquals(Set.of("runId", "attemptId", "skillVersionId"), fields);
                    // 独立连接在收到 HTTP 时即读到 CANCELLED，证明网络未发生在未提交的取消事务内。
                    assertEquals("CANCELLED", db.queryForObject("select status from engine.run where tenant_id=? and public_id=?", String.class,
                            actor.tenantId(), body.path("runId").asText()));
                    var captured = Json.object(); captured.put("callId", Long.parseLong(path.split("/")[3])); captured.set("body", body); requests.add(captured);
                    byte[] bytes = Json.write(fail.get() ? Map.of("code", "FIXTURE_UNAVAILABLE")
                            : ApiResponse.ok(Map.of("localCallMatched", true, "cancelRequested", true, "reason", "CANCEL_REQUESTED"))).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(fail.get() ? 503 : 200, bytes.length); exchange.getResponseBody().write(bytes);
                } catch (Throwable error) { peerFailure.compareAndSet(null, error); exchange.sendResponseHeaders(500, -1); }
            }
        });
        peer.start(); origin = "http://127.0.0.1:" + peer.getAddress().getPort();
    }
    @AfterAll static void close() throws Exception {
        try { if (peer != null) peer.stop(0); }
        finally {
            if (executor != null) { executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)); }
            if (priorSecret == null) System.clearProperty("agent.jwt.secret"); else System.setProperty("agent.jwt.secret", priorSecret);
        }
    }
    @BeforeEach void setup() {
        actor = new Actor("engine-cancel-" + UUID.randomUUID(), "fixture-editor", Set.of("EDITOR"), Map.of());
        requests.clear(); peerFailure.set(null); fail.set(false);
        clients = new RuntimeClients(); clients.skill = origin; clients.app = origin; clients.model = origin;
        scheduler = new RunScheduler(new RunScheduleRepository(store), 1, 1, 20, 1000, 1000);
        runs = new RunService(store, clients, null, null, scheduler);
    }
    @AfterEach void cleanup() {
        try { scheduler.close(); clients.close(); }
        finally {
            db.update("delete from engine.attempt_note where node_instance_id in(select n.id from engine.node_instance n join engine.run r on r.id=n.run_id where r.tenant_id=?)", actor.tenantId());
            db.update("delete from engine.run_event where run_id in(select id from engine.run where tenant_id=?)", actor.tenantId());
            db.update("delete from engine.tool_call where run_id in(select id from engine.run where tenant_id=?)", actor.tenantId());
            db.update("delete from engine.attempt where node_instance_id in(select n.id from engine.node_instance n join engine.run r on r.id=n.run_id where r.tenant_id=?)", actor.tenantId());
            db.update("delete from engine.node_instance where run_id in(select id from engine.run where tenant_id=?)", actor.tenantId());
            db.update("delete from engine.run where tenant_id=?", actor.tenantId());
        }
        assertNull(peerFailure.get(), "协议夹具错误不能算业务红");
    }
    long run() {
        var tools = new ArrayList<Map<String, Object>>();
        for (long version : List.of(10L, 11L, 12L, 13L)) {
            var execution = Map.of("readonly", true, "sideEffect", false, "cancellable", version != 11);
            var contract = Map.of("type", version == 12 ? "HTTP" : "PROCESS", "execution", execution);
            tools.add(Map.of("code", "tool_" + version, "skillVersionId", version, "execution", execution,
                    "contract", contract, "contractDigest", Digest.sha256(Json.canonical(contract))));
        }
        String publicId = "r_cancel_" + UUID.randomUUID().toString().replace("-", "");
        return db.queryForObject("insert into engine.run(public_id,app_code,debug_snapshot,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs)"
                        + " values(?,'cancel_fixture','{}','DEBUG','RUNNING',?,?,'pg:test','{}','{}','{}',?::jsonb,'{}') returning id",
                Long.class, publicId, actor.principalId(), actor.tenantId(), Json.write(Map.of("config", Map.of("tools", tools))));
    }
    Call submitted(long run, long version, int sequence) {
        long node = db.queryForObject("insert into engine.node_instance(run_id,node_def_id,step_seq,node_type,status,definition) values(?,?,?,'CODE','READY','{}') returning id",
                Long.class, run, "n" + sequence, sequence);
        var lease = store.claim(node, "fixture-owner-" + sequence); long attempt = TraceStore.number(lease, "attemptId");
        String owner = lease.get("lease_owner").toString();
        String publicRun = db.queryForObject("select public_id from engine.run where id=?", String.class, run);
        var input = Json.tree(Map.of("runId", publicRun, "nodeInstanceId", node, "callSeq", 1, "skillVersionId", version,
                "skillCode", "tool_" + version, "attemptId", attempt, "leaseOwner", owner,
                "idempotencyKey", Digest.sha256(run + ":" + node + ":1:" + version), "arguments", Map.of()));
        var calls = new ToolCalls(store); long call = TraceStore.number(calls.register(input, actor), "callId");
        var claim = Json.tree(Map.of("owner", owner, "attemptId", attempt)); calls.claim(call, claim, actor); calls.submitted(call, claim, actor);
        return new Call(call, run, node, attempt, publicRun, owner, version);
    }
    JsonNode receipt(long run) {
        return Json.read(db.queryForObject("select payload::text from engine.run_event where run_id=? and type='PROCESS_CANCEL_RECEIPT' order by seq desc limit 1", String.class, run));
    }

    @Test void committedCancellationNotifiesOnlyTheOwnedCurrentCancellableProcessGeneration() {
        long id = run(); var target = submitted(id, 10, 1); submitted(id, 11, 2); submitted(id, 12, 3); var stale = submitted(id, 13, 4);
        db.update("update engine.node_instance set lease_until=now()-interval '1 second' where id=?", stale.node()); store.claim(stale.node(), "replacement-owner");
        var other = submitted(run(), 10, 1);
        assertEquals("CANCELLED", runs.cancel(target.publicRun(), actor).get("status"));
        assertEquals(1, requests.size(), "PROCESS_CANCEL_HTTP_REQUIRED"); assertEquals(target.id(), requests.getFirst().path("callId").asLong());
        JsonNode body = requests.getFirst().path("body"); assertEquals(target.publicRun(), body.path("runId").asText());
        assertEquals(target.attempt(), body.path("attemptId").asLong()); assertEquals(target.version(), body.path("skillVersionId").asLong());
        JsonNode receipt = receipt(id); assertTrue(receipt.path("receiptReceived").asBoolean());
        assertTrue(receipt.path("localCallMatched").asBoolean()); assertTrue(receipt.path("cancelRequested").asBoolean());
        assertFalse(receipt.path("remoteTerminationConfirmed").asBoolean());
        assertEquals("RUNNING", db.queryForObject("select status from engine.run where id=?", String.class, other.run()));
        runs.cancel(target.publicRun(), actor); assertEquals(1, requests.size());
        assertEquals(1, db.queryForObject("select count(*) from engine.run_event where run_id=? and type='RUN_FINISHED'", Integer.class, id));
    }

    @Test void failedNotificationKeepsCancelledStateAndRejectsLateCompletion() {
        var target = submitted(run(), 10, 1); fail.set(true);
        assertEquals("CANCELLED", runs.cancel(target.publicRun(), actor).get("status"));
        assertEquals(1, requests.size(), "PROCESS_CANCEL_FAILED_REQUEST_MUST_BE_ATTEMPTED");
        JsonNode receipt = receipt(target.run()); assertFalse(receipt.path("receiptReceived").asBoolean());
        assertEquals("REQUEST_FAILED", receipt.path("reason").asText()); assertFalse(receipt.path("remoteTerminationConfirmed").asBoolean());
        assertFalse(store.commit(target.run(), target.node(), target.attempt(), target.owner(), Map.of("late", true)));
        assertEquals("CANCELLED", db.queryForObject("select status from engine.run where id=?", String.class, target.run()));
    }
}
