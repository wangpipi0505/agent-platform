package cn.agentplatform.monitor.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.monitor.MonitorApplication;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.*;
import java.net.http.*;
import java.util.*;

@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
@SpringBootTest(
        classes = MonitorApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DatasetHttpTest {
    @LocalServerPort int port;
    Actor actor;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        var admin =
                new org.springframework.jdbc.core.JdbcTemplate(
                        new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                "jdbc:postgresql://127.0.0.1:25432/postgres",
                                "agent_v12",
                                System.getenv("AGENT_DB_PASSWORD")));
        if (admin.queryForObject(
                        "select count(*) from pg_database where"
                                + " datname='agent_platform_test_input_test'",
                        Integer.class)
                == 0) admin.execute("create database agent_platform_test_input_test");
        p.add(
                "spring.datasource.url",
                () -> "jdbc:postgresql://127.0.0.1:25432/agent_platform_test_input_test");
    }

    @BeforeEach
    void setup() {
        actor = new Actor("dataset-" + UUID.randomUUID(), "owner", Set.of("EDITOR"), Map.of());
    }

    HttpResponse<String> request(String method, String path, Object body, boolean internal)
            throws Exception {
        var b =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header(
                                "Authorization",
                                "Bearer "
                                        + (internal
                                                ? ServiceTokens.issue(actor)
                                                : ServiceTokens.userToken(actor, 120)))
                        .header("Content-Type", "application/json");
        return HttpClient.newHttpClient()
                .send(
                        b.method(
                                        method,
                                        body == null
                                                ? HttpRequest.BodyPublishers.noBody()
                                                : HttpRequest.BodyPublishers.ofString(
                                                        Json.write(body)))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
    }

    Map<String, Object> promotion() {
        var inputs = Map.of("q", "source");
        return Map.of(
                "appCode",
                "demo",
                "datasetVersion",
                "v1",
                "requestId",
                "req1",
                "source",
                Map.of(
                        "inputId",
                        1,
                        "revision",
                        1,
                        "appDraftId",
                        1,
                        "inputsSha256",
                        Digest.sha256(Json.canonical(inputs))),
                "cases",
                List.of(
                        Map.of(
                                "id",
                                "safe",
                                "inputs",
                                inputs,
                                "expected",
                                "human expected",
                                "safety",
                                true)));
    }

    @Test
    void sameContentIsIdempotentButVersionAndRequestIdAreImmutable() throws Exception {
        var b = promotion();
        var first = request("POST", "/internal/datasets/promotions", b, true);
        assertEquals(200, first.statusCode(), first.body());
        assertEquals(200, request("POST", "/internal/datasets/promotions", b, true).statusCode());
        var conflict = new HashMap<>(b);
        conflict.put(
                "cases",
                List.of(
                        Map.of(
                                "id",
                                "safe",
                                "inputs",
                                Map.of("q", "source"),
                                "expected",
                                "changed",
                                "safety",
                                true)));
        assertEquals(
                409, request("POST", "/internal/datasets/promotions", conflict, true).statusCode());
        conflict = new HashMap<>(b);
        conflict.put("datasetVersion", "v2");
        assertEquals(
                409, request("POST", "/internal/datasets/promotions", conflict, true).statusCode());
        assertEquals(
                200,
                request("GET", "/admin/evaluation-datasets/demo/v1", null, false).statusCode());
    }

    @Test
    void ownershipAndExplicitEditorAreRequiredAndSourceDigestMustMatch() throws Exception {
        var b = promotion();
        assertEquals(403, request("POST", "/internal/datasets/promotions", b, false).statusCode());
        assertEquals(200, request("POST", "/internal/datasets/promotions", b, true).statusCode());
        actor = new Actor(actor.tenantId(), "other", actor.roles(), Map.of());
        assertEquals(
                404,
                request("GET", "/admin/evaluation-datasets/demo/v1", null, false).statusCode());
        assertEquals(409, request("POST", "/internal/datasets/promotions", b, true).statusCode());
        actor = new Actor(actor.tenantId(), "owner", Set.of("ADMIN"), Map.of());
        assertEquals(
                403,
                request("GET", "/admin/evaluation-datasets/demo/v1", null, false).statusCode());
        actor = new Actor("other-tenant", "owner", Set.of("EDITOR"), Map.of());
        assertEquals(
                404,
                request("GET", "/admin/evaluation-datasets/demo/v1", null, false).statusCode());
        var invalid = new HashMap<>(b);
        invalid.put(
                "source",
                Map.of(
                        "inputId",
                        1,
                        "revision",
                        1,
                        "appDraftId",
                        1,
                        "inputsSha256",
                        "0".repeat(64)));
        assertEquals(
                400, request("POST", "/internal/datasets/promotions", invalid, true).statusCode());
    }

    @Test
    void parallelRequestClaimsChooseOneVersionAndSameContentRetriesRemainStable() throws Exception {
        var b = promotion();
        var other = new HashMap<>(b);
        other.put("datasetVersion", "v2");
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new java.util.concurrent.CountDownLatch(1);
            var one =
                    pool.submit(
                            () -> {
                                gate.await();
                                return request("POST", "/internal/datasets/promotions", b, true)
                                        .statusCode();
                            });
            var two =
                    pool.submit(
                            () -> {
                                gate.await();
                                return request("POST", "/internal/datasets/promotions", other, true)
                                        .statusCode();
                            });
            gate.countDown();
            var statuses = new ArrayList<>(List.of(one.get(), two.get()));
            Collections.sort(statuses);
            assertEquals(List.of(200, 409), statuses);
        }
        var listing =
                request("GET", "/admin/evaluation-datasets?appCode=demo&limit=1", null, false);
        assertEquals(200, listing.statusCode());
        var list = Json.read(listing.body()).path("data");
        assertEquals(1, list.path("items").size());
        assertFalse(list.path("hasMore").asBoolean());
        assertFalse(list.path("items").get(0).has("cases"));
        var winner = new HashMap<>(b);
        winner.put("datasetVersion", list.path("items").get(0).path("datasetVersion").asText());
        assertEquals(
                200, request("POST", "/internal/datasets/promotions", winner, true).statusCode());
    }
}
