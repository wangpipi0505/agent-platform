package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.EngineApplication;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real authenticated HTTP and dedicated PostgreSQL. Upstream fixture is test-only. */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
@SpringBootTest(
        classes = EngineApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TestInputHttpTest {
    static final String DATABASE = "agent_platform_test_input_test";
    static HttpServer upstream;
    static AtomicReference<Map<String, Object>> schema =
            new AtomicReference<>(Map.of("type", "object"));
    static AtomicReference<List<Map<String, Object>>> declarations =
            new AtomicReference<>(List.of());
    static AtomicBoolean drop = new AtomicBoolean();
    static AtomicReference<JsonNode> received = new AtomicReference<>();
    static JdbcTemplate db;
    @LocalServerPort int port;
    String tenant, owner = "owner";
    Set<String> roles = Set.of("EDITOR");
    HttpClient client = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) throws Exception {
        var admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:postgresql://127.0.0.1:25432/postgres",
                                "agent_v12",
                                System.getenv("AGENT_DB_PASSWORD")));
        if (admin.queryForObject(
                        "select count(*) from pg_database where datname=?", Integer.class, DATABASE)
                == 0) admin.execute("create database " + DATABASE);
        String url = "jdbc:postgresql://127.0.0.1:25432/" + DATABASE;
        db =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                url, "agent_v12", System.getenv("AGENT_DB_PASSWORD")));
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext(
                "/",
                exchange -> {
                    String token =
                            exchange.getRequestHeaders().getFirst("Authorization").substring(7);
                    var claims =
                            Json.read(
                                    new String(
                                            Base64.getUrlDecoder().decode(token.split("\\.")[1]),
                                            StandardCharsets.UTF_8));
                    assertEquals("service", claims.path("token_use").asText());
                    assertEquals("agent-platform-internal", claims.path("aud").asText());
                    JsonNode data;
                    if (exchange.getRequestURI().getPath().startsWith("/internal/app-drafts/")) {
                        if (exchange.getRequestURI().getPath().endsWith("/999")) {
                            exchange.sendResponseHeaders(404, -1);
                            exchange.close();
                            return;
                        }
                        data =
                                Json.tree(
                                        Map.of(
                                                "draftId",
                                                1,
                                                "appCode",
                                                "demo",
                                                "draftRev",
                                                1,
                                                "type",
                                                "CHAT",
                                                "status",
                                                "DRAFT",
                                                "config",
                                                Map.of(
                                                        "inputSchema",
                                                        schema.get(),
                                                        "inputs",
                                                        declarations.get()),
                                                "definition",
                                                Map.of()));
                    } else {
                        received.set(
                                Json.read(
                                        new String(
                                                exchange.getRequestBody().readAllBytes(),
                                                StandardCharsets.UTF_8)));
                        if (drop.getAndSet(false)) {
                            exchange.close();
                            return;
                        }
                        data = received.get();
                    }
                    byte[] bytes =
                            Json.write(ApiResponse.ok(data)).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        upstream.start();
        p.add("spring.datasource.url", () -> url);
        p.add("agent.services.app", () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
        p.add(
                "agent.services.monitor",
                () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
    }

    @BeforeEach
    void setup() {
        tenant = "input-" + UUID.randomUUID();
        schema.set(Map.of("type", "object"));
        declarations.set(List.of());
        drop.set(false);
        received.set(null);
    }

    @AfterAll
    static void stop() {
        if (upstream != null) upstream.stop(0);
    }

    @Test
    void chatDeclarationsValidateRequiredTypeAndChangedReuse() throws Exception {
        long id = create().path("id").asLong();
        declarations.set(List.of(Map.of("name", "message", "type", "STRING", "required", true)));
        assertEquals(
                400,
                request(
                                "POST",
                                "/admin/debug-inputs?appDraftId=1",
                                Map.of("name", "missing", "inputs", Map.of()))
                        .statusCode());
        assertEquals(
                400,
                request(
                                "POST",
                                "/admin/debug-inputs?appDraftId=1",
                                Map.of("name", "wrong type", "inputs", Map.of("message", 42)))
                        .statusCode());
        assertEquals(400, request("GET", "/admin/debug-inputs/" + id, null).statusCode());
        ok(
                "PUT",
                "/admin/debug-inputs/" + id,
                Map.of("revision", 1, "name", "corrected", "inputs", Map.of("message", "hello")));
        assertEquals(
                "hello",
                ok("GET", "/admin/debug-inputs/" + id, null)
                        .path("inputs")
                        .path("message")
                        .asText());
    }

    @Test
    void declaredSchemaConstraintsNullAndDefaultsMatchStructuredInputPayloads() throws Exception {
        declarations.set(
                List.of(
                        Map.of(
                                "name",
                                "message",
                                "type",
                                "STRING",
                                "required",
                                true,
                                "minLength",
                                3,
                                "default",
                                "hello")));
        assertEquals(
                400,
                request(
                                "POST",
                                "/admin/debug-inputs?appDraftId=1",
                                Map.of("name", "short", "inputs", Map.of("message", "x")))
                        .statusCode());
        assertEquals(
                400,
                request(
                                "POST",
                                "/admin/debug-inputs?appDraftId=1",
                                Map.of("name", "missing", "inputs", Map.of()))
                        .statusCode());
        declarations.set(
                List.of(
                        Map.of(
                                "name",
                                "payload",
                                "type",
                                "ARRAY",
                                "required",
                                true,
                                "items",
                                Map.of(
                                        "type",
                                        "object",
                                        "required",
                                        List.of("n"),
                                        "properties",
                                        Map.of("n", Map.of("type", "integer"))))));
        assertEquals(
                400,
                request(
                                "POST",
                                "/admin/debug-inputs?appDraftId=1",
                                Map.of(
                                        "name",
                                        "bad nested",
                                        "inputs",
                                        Map.of("payload", List.of(Map.of("n", "1")))))
                        .statusCode());
        assertEquals(
                400,
                request(
                                "POST",
                                "/admin/debug-inputs?appDraftId=1",
                                Map.of(
                                        "name",
                                        "string is not array",
                                        "inputs",
                                        Map.of("payload", "[]")))
                        .statusCode());
        ok(
                "POST",
                "/admin/debug-inputs?appDraftId=1",
                Map.of("name", "typed", "inputs", Map.of("payload", List.of(Map.of("n", 0)))));
        declarations.set(
                List.of(
                        Map.of("name", "nothing", "type", "NULL", "required", true),
                        Map.of("name", "flag", "type", "BOOLEAN", "required", true),
                        Map.of("name", "count", "type", "NUMBER", "required", true)));
        var values = new LinkedHashMap<String, Object>();
        values.put("nothing", null);
        values.put("flag", false);
        values.put("count", 0);
        assertEquals(
                Json.tree(values),
                ok(
                                "POST",
                                "/admin/debug-inputs?appDraftId=1",
                                Map.of("name", "null false zero", "inputs", values))
                        .path("inputs"));
    }

    HttpResponse<String> request(String method, String path, Object body) throws Exception {
        var b =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header(
                                "Authorization",
                                "Bearer "
                                        + ServiceTokens.userToken(
                                                new Actor(tenant, owner, roles, Map.of()), 120))
                        .header("Content-Type", "application/json");
        return client.send(
                b.method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(Json.write(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    JsonNode ok(String method, String path, Object body) throws Exception {
        var r = request(method, path, body);
        assertEquals(200, r.statusCode(), r.body());
        return Json.read(r.body()).path("data");
    }

    JsonNode create() throws Exception {
        return ok(
                "POST",
                "/admin/debug-inputs?appDraftId=1",
                Map.of("name", "normal", "inputs", Map.of("q", "old")));
    }

    @Test
    void persistsAndCasProtectsAgainstLostUpdates() throws Exception {
        var saved = create();
        long id = saved.path("id").asLong();
        assertEquals(1, saved.path("revision").asLong());
        assertEquals(
                "old",
                ok("GET", "/admin/debug-inputs/" + id, null).path("inputs").path("q").asText());
        var changed =
                ok(
                        "PUT",
                        "/admin/debug-inputs/" + id,
                        Map.of("revision", 1, "name", "changed", "inputs", Map.of("q", "new")));
        assertEquals(2, changed.path("revision").asLong());
        assertEquals(
                409,
                request(
                                "PUT",
                                "/admin/debug-inputs/" + id,
                                Map.of("revision", 1, "name", "stale", "inputs", Map.of()))
                        .statusCode());
        assertEquals(
                409,
                request("DELETE", "/admin/debug-inputs/" + id + "?revision=1", null).statusCode());
        ok("DELETE", "/admin/debug-inputs/" + id + "?revision=2", null);
        assertEquals(404, request("GET", "/admin/debug-inputs/" + id, null).statusCode());
    }

    @Test
    void deniesOtherOwnerTenantAndMissingEditorIncludingAdmin() throws Exception {
        long id = create().path("id").asLong();
        owner = "other";
        assertEquals(404, request("GET", "/admin/debug-inputs/" + id, null).statusCode());
        assertEquals(0, ok("GET", "/admin/debug-inputs?appDraftId=1", null).path("items").size());
        owner = "owner";
        String ownTenant = tenant;
        tenant = "other";
        assertEquals(404, request("GET", "/admin/debug-inputs/" + id, null).statusCode());
        tenant = ownTenant;
        roles = Set.of("ADMIN");
        assertEquals(403, request("GET", "/admin/debug-inputs/" + id, null).statusCode());
        roles = Set.of("VIEWER");
        assertEquals(
                403,
                request(
                                "POST",
                                "/admin/debug-inputs?appDraftId=1",
                                Map.of("name", "bad", "inputs", Map.of()))
                        .statusCode());
    }

    @Test
    void boundsInputsAndPaginationAndRevalidatesChangedSchema() throws Exception {
        assertEquals(
                404,
                request(
                                "POST",
                                "/admin/debug-inputs?appDraftId=999",
                                Map.of("name", "bad", "inputs", Map.of()))
                        .statusCode());
        assertEquals(
                400,
                request("GET", "/admin/debug-inputs?appDraftId=1&limit=101", null).statusCode());
        assertEquals(
                400,
                request(
                                "POST",
                                "/admin/debug-inputs?appDraftId=1",
                                Map.of("name", "bad", "inputs", List.of()))
                        .statusCode());
        assertEquals(
                413,
                request(
                                "POST",
                                "/admin/debug-inputs?appDraftId=1",
                                Map.of("name", "bad", "inputs", Map.of("q", "x".repeat(66000))))
                        .statusCode());
        Object nested = "leaf";
        for (int i = 0; i < 20; i++) nested = Map.of("x", nested);
        assertEquals(
                400,
                request(
                                "POST",
                                "/admin/debug-inputs?appDraftId=1",
                                Map.of("name", "bad", "inputs", nested))
                        .statusCode());
        long id = create().path("id").asLong();
        schema.set(Map.of("type", "object", "required", List.of("changed")));
        assertEquals(400, request("GET", "/admin/debug-inputs/" + id, null).statusCode());
    }

    Map<String, Object> promotion(String requestId) {
        return Map.of(
                "requestId",
                requestId,
                "revision",
                1,
                "datasetVersion",
                "safe-v1",
                "caseId",
                "safe",
                "expected",
                "human expected",
                "safety",
                true);
    }

    @Test
    void recoveryFreezesSourceAndTargetBeforeHttpAndRejectsDifferentRetry() throws Exception {
        long id = create().path("id").asLong();
        var p = promotion("retry-1");
        drop.set(true);
        var first = request("POST", "/admin/debug-inputs/" + id + "/promote", p);
        assertEquals(502, first.statusCode(), first.body());
        JsonNode original = received.get();
        assertNotNull(original);
        assertEquals("old", original.path("cases").get(0).path("inputs").path("q").asText());
        ok(
                "PUT",
                "/admin/debug-inputs/" + id,
                Map.of("revision", 1, "name", "edited", "inputs", Map.of("q", "new")));
        ok("POST", "/admin/debug-inputs/" + id + "/promote", p);
        assertEquals(original, received.get());
        var conflicting = new HashMap<>(p);
        conflicting.put("expected", "different");
        assertEquals(
                409,
                request("POST", "/admin/debug-inputs/" + id + "/promote", conflicting)
                        .statusCode());
        var missing = new HashMap<>(p);
        missing.remove("expected");
        assertEquals(
                400,
                request("POST", "/admin/debug-inputs/" + id + "/promote", missing).statusCode());
    }

    @Test
    void parallelUpdatesHaveExactlyOneWinner() throws Exception {
        long id = create().path("id").asLong();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            var jobs = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 2; i++)
                jobs.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    return request(
                                                    "PUT",
                                                    "/admin/debug-inputs/" + id,
                                                    Map.of(
                                                            "revision",
                                                            1,
                                                            "name",
                                                            "racer",
                                                            "inputs",
                                                            Map.of()))
                                            .statusCode();
                                }));
            gate.countDown();
            var statuses = new ArrayList<Integer>();
            for (var job : jobs) statuses.add(job.get());
            Collections.sort(statuses);
            assertEquals(List.of(200, 409), statuses);
        }
    }

    @Test
    void pagingIsBoundedStableAndDoesNotExposeInputsInMetadata() throws Exception {
        long first = create().path("id").asLong(), second = create().path("id").asLong();
        var page = ok("GET", "/admin/debug-inputs?appDraftId=1&limit=1", null);
        assertEquals(1, page.path("items").size());
        assertEquals(first, page.path("nextAfterId").asLong());
        assertTrue(page.path("hasMore").asBoolean());
        assertFalse(page.path("items").get(0).has("inputs"));
        var next =
                ok(
                        "GET",
                        "/admin/debug-inputs?appDraftId=1&limit=1&afterId="
                                + page.path("nextAfterId").asLong(),
                        null);
        assertEquals(second, next.path("items").get(0).path("id").asLong());
        assertFalse(next.path("hasMore").asBoolean());
    }

    @Test
    void callerCannotOverrideFrozenInputsAndStaleRevisionCannotPromote() throws Exception {
        long id = create().path("id").asLong();
        var override = new HashMap<>(promotion("override"));
        override.put("inputs", Map.of("q", "forged"));
        assertEquals(
                400,
                request("POST", "/admin/debug-inputs/" + id + "/promote", override).statusCode());
        ok(
                "PUT",
                "/admin/debug-inputs/" + id,
                Map.of("revision", 1, "name", "new", "inputs", Map.of("q", "new")));
        assertEquals(
                409,
                request("POST", "/admin/debug-inputs/" + id + "/promote", promotion("stale"))
                        .statusCode());
        assertEquals(
                0,
                db.queryForObject(
                        "select count(*) from engine.debug_input_promotion where tenant_id=?",
                        Integer.class,
                        tenant));
    }
}
