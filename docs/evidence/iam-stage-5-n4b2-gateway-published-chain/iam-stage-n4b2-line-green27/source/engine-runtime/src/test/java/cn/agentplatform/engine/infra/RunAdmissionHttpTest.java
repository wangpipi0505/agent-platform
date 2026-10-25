package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.EngineApplication;
import cn.agentplatform.engine.application.*;
import cn.agentplatform.engine.domain.RunDispatch.Queue;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real HTTP + Nimbus verification + dedicated PG. No model/skill provider is used. */
@SpringBootTest(
        classes = EngineApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class RunAdmissionHttpTest {
    static HttpServer upstream;
    static final Set<HttpServer> peers = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static final AtomicInteger reads = new AtomicInteger();
    @Autowired TraceStore store;
    @Autowired RunScheduler scheduler;
    @Autowired org.springframework.context.ApplicationContext context;
    @LocalServerPort int port;
    final HttpClient http = HttpClient.newHttpClient();
    Actor actor;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) throws Exception {
        p.add("spring.datasource.url", EngineTestDatabase::url);
        p.add("spring.datasource.username", EngineTestDatabase::user);
        p.add("spring.datasource.password", EngineTestDatabase::password);
        p.add("agent.scheduler.interactive-workers", () -> 1);
        p.add("agent.scheduler.batch-workers", () -> 1);
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext(
                "/",
                exchange -> {
                    reads.incrementAndGet();
                    JsonNode snapshot =
                            snapshot(exchange.getRequestURI().getPath().contains("/chat/"));
                    byte[] bytes =
                            Json.write(ApiResponse.ok(snapshot)).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        upstream.start();
        peers.add(upstream);
        p.add("agent.services.app", () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
    }

    static JsonNode snapshot(boolean chat) {
        return Json.tree(
                Map.of(
                        "appCode",
                        chat ? "chat" : "flow",
                        "appVersionId",
                        1,
                        "versionNo",
                        1,
                        "type",
                        chat ? "CHAT" : "FLOW",
                        "config",
                        Map.of(),
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
                                Map.of("parallel", false, "maxParallel", 1))));
    }

    @BeforeEach
    void setup() throws Exception {
        actor = new Actor("admission-" + UUID.randomUUID(), "alice", Set.of("EDITOR"), Map.of());
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (scheduler.activeWorkers() > 0 && System.nanoTime() < until) Thread.sleep(10);
        assertEquals(0, scheduler.activeWorkers());
        reads.set(0);
    }

    @AfterEach
    void cleanup() {
        store.db()
                .update(
                        "delete from engine.chat_turn where session_id in(select public_id from"
                                + " engine.chat_session where tenant_id=?)",
                        actor.tenantId());
        store.db().update("delete from engine.chat_session where tenant_id=?", actor.tenantId());
        store.db()
                .update(
                        "delete from engine.run_event where run_id in(select id from engine.run"
                                + " where tenant_id=?)",
                        actor.tenantId());
        store.db()
                .update(
                        "delete from engine.attempt_note where node_instance_id in(select n.id from"
                                + " engine.node_instance n join engine.run r on r.id=n.run_id where"
                                + " r.tenant_id=?)",
                        actor.tenantId());
        store.db()
                .update(
                        "delete from engine.attempt where node_instance_id in(select n.id from"
                                + " engine.node_instance n join engine.run r on r.id=n.run_id where"
                                + " r.tenant_id=?)",
                        actor.tenantId());
        store.db()
                .update(
                        "delete from engine.node_instance where run_id in(select id from engine.run"
                                + " where tenant_id=?)",
                        actor.tenantId());
        store.db().update("delete from engine.run where tenant_id=?", actor.tenantId());
    }

    @AfterAll
    static void stopFixture() {
        peers.forEach(peer -> peer.stop(0));
        peers.clear();
    }

    HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Authorization", "Bearer " + ServiceTokens.userToken(actor, 120))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    void fullPoolRejectsNewDebugAndFormalBeforeAnyRunOrDependencyRead() throws Exception {
        try (var held = scheduler.reserveInteractive()) {
            for (String path : List.of("/admin/debug-runs", "/v1/apps/flow/runs")) {
                var response =
                        post(path, "{\"appCode\":\"flow\",\"inputs\":{},\"requestId\":\"new\"}");
                assertEquals(429, response.statusCode());
                assertEquals("1", response.headers().firstValue("Retry-After").orElseThrow());
                var body = Json.read(response.body());
                assertEquals("ENGINE_BUSY", body.path("code").asText());
                assertEquals(1, body.path("data").path("retryAfterSeconds").asInt());
            }
            assertEquals(0, reads.get());
            assertEquals(
                    0,
                    store.db()
                            .queryForObject(
                                    "select count(*) from engine.run where tenant_id=?",
                                    Integer.class,
                                    actor.tenantId()));
        }
        assertEquals(1, scheduler.available(Queue.INTERACTIVE));
    }

    @Test
    void newSessionTurnIsRejectedBeforeReservationAndNextTurnChanges() throws Exception {
        var session = Json.read(post("/v1/apps/chat/sessions", "{}").body()).path("data");
        String id = session.path("sessionId").asText();
        assertFalse(id.isBlank());
        try (var held = scheduler.reserveInteractive()) {
            var response =
                    post(
                            "/v1/apps/chat/sessions/" + id + "/turns",
                            "{\"requestId\":\"first\",\"inputs\":{\"message\":\"hello\"}}");
            assertEquals(429, response.statusCode());
            assertEquals("1", response.headers().firstValue("Retry-After").orElseThrow());
            assertEquals(
                    0,
                    store.db()
                            .queryForObject(
                                    "select count(*) from engine.chat_turn where session_id=?",
                                    Integer.class,
                                    id));
            assertEquals(
                    1,
                    store.db()
                            .queryForObject(
                                    "select next_turn from engine.chat_session where public_id=?",
                                    Integer.class,
                                    id));
            assertEquals(
                    0,
                    store.db()
                            .queryForObject(
                                    "select count(*) from engine.run where tenant_id=?",
                                    Integer.class,
                                    actor.tenantId()));
        }
    }

    @Test
    void acceptedRequestIdRemainsReadableAtCapacityAndConflictStillConflicts() throws Exception {
        var created =
                Json.read(
                                post(
                                                "/admin/debug-runs",
                                                "{\"appCode\":\"flow\",\"inputs\":{},\"requestId\":\"accepted\"}")
                                        .body())
                        .path("data");
        String id = created.path("runId").asText();
        assertFalse(id.isBlank());
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (scheduler.activeWorkers() > 0 && System.nanoTime() < until) Thread.sleep(10);
        try (var held = scheduler.reserveInteractive()) {
            assertEquals(
                    200,
                    post(
                                    "/admin/debug-runs",
                                    "{\"appCode\":\"flow\",\"inputs\":{},\"requestId\":\"accepted\"}")
                            .statusCode());
            var duplicate =
                    Json.read(
                            post(
                                            "/admin/debug-runs",
                                            "{\"appCode\":\"flow\",\"inputs\":{},\"requestId\":\"accepted\"}")
                                    .body());
            assertEquals(id, duplicate.path("data").path("runId").asText());
            assertEquals(
                    409,
                    post(
                                    "/admin/debug-runs",
                                    "{\"appCode\":\"flow\",\"inputs\":{\"x\":1},\"requestId\":\"accepted\"}")
                            .statusCode());
            assertEquals(
                    1,
                    store.db()
                            .queryForObject(
                                    "select count(*) from engine.run where tenant_id=?",
                                    Integer.class,
                                    actor.tenantId()));
        }
    }

    @Test
    void oldAcceptedEmptySessionReservationCanRecoverAtCapacity() throws Exception {
        String id =
                Json.read(post("/v1/apps/chat/sessions", "{}").body())
                        .path("data")
                        .path("sessionId")
                        .asText();
        store.db()
                .update(
                        "insert into engine.chat_turn(session_id,turn_no,request_id,inputs)"
                                + " values(?,1,'accepted-before-crash','{}')",
                        id);
        store.db().update("update engine.chat_session set next_turn=2 where public_id=?", id);
        try (var held = scheduler.reserveInteractive()) {
            var response =
                    post(
                            "/v1/apps/chat/sessions/" + id + "/turns",
                            "{\"requestId\":\"accepted-before-crash\",\"inputs\":{}}");
            assertEquals(200, response.statusCode());
            var accepted = Json.read(response.body()).path("data");
            assertEquals("PENDING", accepted.path("status").asText());
            String run = accepted.path("runId").asText();
            assertFalse(run.isBlank());
            var repeat =
                    Json.read(
                                    post(
                                                    "/v1/apps/chat/sessions/" + id + "/turns",
                                                    "{\"requestId\":\"accepted-before-crash\",\"inputs\":{}}")
                                            .body())
                            .path("data");
            assertEquals(run, repeat.path("runId").asText());
            assertEquals(
                    1,
                    store.db()
                            .queryForObject(
                                    "select count(*) from engine.chat_turn where session_id=?",
                                    Integer.class,
                                    id));
            assertEquals(
                    run,
                    store.db()
                            .queryForObject(
                                    "select run_public_id from engine.chat_turn where session_id=?",
                                    String.class,
                                    id));
        }
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (scheduler.activeWorkers() > 0 && System.nanoTime() < until) Thread.sleep(10);
        // The production timer may claim the queued row on its next tick; wait for terminal
        // rather than deleting an accepted run while its real worker is still starting.
        while (!"SUCCEEDED"
                        .equals(
                                store.db()
                                        .queryForObject(
                                                "select status from engine.run where tenant_id=?",
                                                String.class,
                                                actor.tenantId()))
                && System.nanoTime() < until) Thread.sleep(10);
        assertEquals(
                "SUCCEEDED",
                store.db()
                        .queryForObject(
                                "select status from engine.run where tenant_id=?",
                                String.class,
                                actor.tenantId()));
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void drainingReturns503RetryAfterButAcceptedRunStillReads() throws Exception {
        var created =
                Json.read(
                                post(
                                                "/admin/debug-runs",
                                                "{\"appCode\":\"flow\",\"inputs\":{},\"requestId\":\"accepted-before-stop\"}")
                                        .body())
                        .path("data");
        assertFalse(created.path("runId").asText().isBlank());
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (scheduler.activeWorkers() > 0 && System.nanoTime() < until) Thread.sleep(10);
        scheduler.onApplicationEvent(
                new org.springframework.context.event.ContextClosedEvent(context));
        assertEquals(
                200,
                post(
                                "/admin/debug-runs",
                                "{\"appCode\":\"flow\",\"inputs\":{},\"requestId\":\"accepted-before-stop\"}")
                        .statusCode());
        var rejected =
                post(
                        "/admin/debug-runs",
                        "{\"appCode\":\"flow\",\"inputs\":{},\"requestId\":\"new-after-stop\"}");
        assertEquals(503, rejected.statusCode());
        assertEquals("1", rejected.headers().firstValue("Retry-After").orElseThrow());
        assertEquals("ENGINE_DRAINING", Json.read(rejected.body()).path("code").asText());
        assertEquals(
                1,
                store.db()
                        .queryForObject(
                                "select count(*) from engine.run where tenant_id=?",
                                Integer.class,
                                actor.tenantId()));
    }
}
