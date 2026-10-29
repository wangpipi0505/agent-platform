package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;

/** 真实 HTTP 与 PG 调度代次；仅技能执行内容用观察器隔离，不宣称完成跨仓部署验收。 */
class CrossServiceTraceTest {
    HttpServer peer;
    ExecutorService peerWorker;
    RuntimeClients clients;
    String previousSecret;
    Actor actor;
    final List<String> headers = new CopyOnWriteArrayList<>();
    final AtomicReference<Throwable> peerFailure = new AtomicReference<>();

    @BeforeEach void setup() throws Exception {
        actor = new Actor("trace-fixture-" + UUID.randomUUID(), "trace-editor", Set.of("EDITOR"), Map.of());
        previousSecret = System.getProperty("agent.jwt.secret");
        System.setProperty("agent.jwt.secret", "cross-service-trace-fixture-key-32-characters");
        var env = new MockEnvironment().withProperty("agent.security.mode", "test")
                .withProperty("agent.security.service-id", "engine").withProperty("server.address", "127.0.0.1");
        env.setActiveProfiles("test"); SecurityConfiguration.bindDeployment(env);
        peer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        peerWorker = Executors.newSingleThreadExecutor(); peer.setExecutor(peerWorker);
        peer.createContext("/internal/", exchange -> {
            try (exchange) {
                try {
                    var token = SignedJWT.parse(exchange.getRequestHeaders().getFirst("Authorization").substring(7));
                    assertTrue(token.verify(new MACVerifier(ServiceTokens.secretBytes())));
                    assertEquals(actor.tenantId(), token.getJWTClaimsSet().getStringClaim("tenant_id"));
                    assertEquals(actor.principalId(), token.getJWTClaimsSet().getSubject());
                    assertTrue(exchange.getRequestBody().readNBytes(1025).length <= 1024);
                    headers.add(Objects.toString(exchange.getRequestHeaders().getFirst("X-Trace-Id"), "<missing>"));
                    String path = exchange.getRequestURI().getPath();
                    boolean stream = path.equals("/internal/model/chat/stream");
                    byte[] body = (stream ? "event: final\ndata: {\"answer\":\"fixture\"}\n\n"
                            : Json.write(ApiResponse.ok(path.endsWith("/cancel")
                                    ? Map.of("localCallMatched", true, "cancelRequested", true, "reason", "CANCEL_REQUESTED")
                                    : Map.of("observed", true)))).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
                    exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
                } catch (Throwable failure) {
                    peerFailure.compareAndSet(null, failure); exchange.sendResponseHeaders(500, -1);
                }
            }
        });
        peer.start(); String origin = "http://127.0.0.1:" + peer.getAddress().getPort();
        clients = new RuntimeClients(); clients.app = origin; clients.skill = origin; clients.model = origin;
    }

    @AfterEach void close() throws Exception {
        try { if (clients != null) clients.close(); if (peer != null) peer.stop(0); }
        finally {
            if (peerWorker != null) { peerWorker.shutdownNow(); assertTrue(peerWorker.awaitTermination(2, TimeUnit.SECONDS)); }
            MDC.remove("traceId");
            if (previousSecret == null) System.clearProperty("agent.jwt.secret"); else System.setProperty("agent.jwt.secret", previousSecret);
        }
        assertNull(peerFailure.get(), "协议接收夹具错误不能算 trace 业务红");
    }

    @Test void inboundCorrelationIsForwardedByEveryLegacyHttpTransportAndCleared() throws Exception {
        var filter = new TraceContextFilter();
        var expected = new ArrayList<String>();
        for (String incoming : List.of("run:opaque-A.1", "run:opaque-B.2", "unsafe trace")) {
            var request = new MockHttpServletRequest("POST", "/admin/runs"); request.addHeader("X-Trace-Id", incoming);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> {
                String trace = MDC.get("traceId"); assertNotNull(trace); assertTrue(trace.matches("[A-Za-z0-9._:-]{1,128}"));
                if (incoming.equals("unsafe trace")) assertNotEquals(incoming, trace); else assertEquals(incoming, trace);
                assertEquals(trace, ApiResponse.ok(Map.of()).traceId());
                clients.get("app", "/internal/trace/read", actor);
                clients.get("skill", "/internal/skill-facts/trace", actor);
                clients.post("skill", "/internal/trace/write", Map.of(), actor);
                assertEquals("fixture", clients.chatStream(Map.of(), actor, ignored -> fail("No chunk expected")).path("answer").asText());
                assertTrue(clients.cancelSkillCall(1, Map.of("runId", "r_fixture", "attemptId", 1, "skillVersionId", 1), actor,
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(5)).path("cancelRequested").asBoolean());
                for (int i = 0; i < 5; i++) expected.add(trace);
            });
            assertEquals(expected.getLast(), response.getHeader("X-Trace-Id")); assertNull(MDC.get("traceId"));
        }
        assertEquals(expected, headers, "CROSS_SERVICE_TRACE_HEADER_REQUIRED");
    }

    @Test @EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
    void workerUsesFrozenRunTraceAcrossRecoveryAndRestoresReusedThread() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/agent_platform_engine_test", EngineTestDatabase.user(), EngineTestDatabase.password());
        org.flywaydb.core.Flyway.configure().dataSource(ds).schemas("engine").defaultSchema("engine").locations("classpath:db/migration").load().migrate();
        var db = new JdbcTemplate(ds); var store = new TraceStore(db, new DataSourceTransactionManager(ds));
        var worker = Executors.newSingleThreadExecutor();
        var seen = new CopyOnWriteArrayList<String>();
        var scheduler = new RunScheduler(new RunScheduleRepository(store), 1, 1, 20, 10000, 1000);
        var flow = new FlowDriver(store, null) {
            @Override public boolean prepare(Map<String, Object> run, Map<String, Object> node) { return true; }
            @Override public JsonNode execute(Map<String, Object> run, Map<String, Object> node, Actor current) {
                seen.add(Objects.toString(MDC.get("traceId"), "<missing>"));
                clients.get("skill", "/internal/trace/worker", current);
                if (run.get("app_code").equals("trace_failure")) throw new IllegalArgumentException("fixture execution failure");
                return Json.tree(Map.of("ok", true));
            }
        };
        var runs = new RunService(store, clients, flow, null, scheduler);
        try {
            var ids = new ArrayList<Long>();
            for (int i = 0; i < 3; i++) {
                Object refs = i == 2 ? Map.of() : Map.of("traceId", "frozen-run-" + i);
                long id = db.queryForObject("insert into engine.run(public_id,app_code,debug_snapshot,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs)"
                                + " values(?,?,'{}','DEBUG','RUNNING',?,?,'pg:test',?::jsonb,?::jsonb,'{}','{}','{}') returning id", Long.class,
                        "r_trace_" + UUID.randomUUID().toString().replace("-", ""), i == 1 ? "trace_failure" : "trace_fixture", actor.principalId(), actor.tenantId(), Json.write(refs),
                        Json.write(Map.of("tenantId", actor.tenantId(), "principalId", actor.principalId(), "roles", actor.roles(), "attributes", actor.attributes())));
                ids.add(id);
                db.update("insert into engine.node_instance(run_id,node_def_id,step_seq,node_type,status,definition) values(?,'probe',1,'CODE','READY','{\"type\":\"CODE\"}')", id);
                final int index = i;
                assertEquals("unrelated-request", worker.submit(() -> {
                    MDC.put("traceId", "unrelated-request");
                    try {
                        if (index == 1) {
                            // 同一个真实 PG dispatch 下走恢复入口，未启动会扫描其它租户的 timer。
                            var recovered = RunService.class.getDeclaredMethod("driveRecovered", long.class); recovered.setAccessible(true);
                            scheduler.runNow(id, () -> {
                                try { recovered.invoke(runs, id); } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                            });
                        } else runs.drive(id, actor);
                        return MDC.get("traceId");
                    } finally { MDC.remove("traceId"); }
                }).get(8, TimeUnit.SECONDS));
            }
            assertEquals(List.of("SUCCEEDED", "FAILED", "SUCCEEDED"), ids.stream().map(id -> db.queryForObject("select status from engine.run where id=?", String.class, id)).toList());
            assertEquals(3, seen.size());
            assertEquals(List.of("frozen-run-0", "frozen-run-1"), seen.subList(0, 2), "FROZEN_RUN_WORKER_TRACE_REQUIRED");
            assertTrue(seen.get(2).matches("[A-Za-z0-9._:-]{1,128}")); assertNotEquals("unrelated-request", seen.get(2));
            assertEquals(seen, headers);
            assertFalse(Json.read(db.queryForObject("select version_refs::text from engine.run where id=?", String.class, ids.get(2))).has("traceId"), "历史缺失 trace 仅当次生成，不回填历史事实");
            assertNull(worker.submit(() -> MDC.get("traceId")).get(2, TimeUnit.SECONDS));
        } finally {
            worker.shutdownNow(); assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS)); scheduler.close();
            // 仅清本方法随机租户创建的行；调度器从未 poll/claimNext，不接管其它运行。
            db.update("delete from engine.attempt_note where node_instance_id in(select n.id from engine.node_instance n join engine.run r on r.id=n.run_id where r.tenant_id=?)", actor.tenantId());
            db.update("delete from engine.run_event where run_id in(select id from engine.run where tenant_id=?)", actor.tenantId());
            db.update("delete from engine.attempt where node_instance_id in(select n.id from engine.node_instance n join engine.run r on r.id=n.run_id where r.tenant_id=?)", actor.tenantId());
            db.update("delete from engine.node_instance where run_id in(select id from engine.run where tenant_id=?)", actor.tenantId());
            db.update("delete from engine.run where tenant_id=?", actor.tenantId());
        }
    }
}
