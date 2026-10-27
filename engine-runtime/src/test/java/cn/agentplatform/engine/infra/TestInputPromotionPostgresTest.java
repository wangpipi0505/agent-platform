package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.TestInputService;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 提升链路的跨服务一段：真实 TestInputService + 真实 TestInputClient + 真实 PostgreSQL，
 * 只在 HTTP 边界用桩替代 monitor。这样 assertion 的透传、缺省与错误码原样回传
 * 都在同一条链上被验证，且不引入 Spring 上下文（既有 TestInputHttpTest 因他人
 * 夹具的 decoder Bean 冲突无法启动，见证据 README）。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class TestInputPromotionPostgresTest {
    static final String DATABASE = "agent_platform_test_input_promotion_test";
    static HttpServer upstream;
    static JdbcTemplate db;
    static TestInputService service;
    static AtomicReference<JsonNode> received = new AtomicReference<>();
    static AtomicReference<Reply> reply = new AtomicReference<>();
    static AtomicReference<JsonNode> draft = new AtomicReference<>();
    String tenant;

    record Reply(int status, String code, String message) {}

    @BeforeAll
    static void start() throws Exception {
        var admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:postgresql://127.0.0.1:25432/postgres",
                                "agent_v12",
                                System.getenv("AGENT_DB_PASSWORD")));
        if (admin.queryForObject(
                        "select count(*) from pg_database where datname=?", Integer.class, DATABASE)
                == 0) admin.execute("create database " + DATABASE);
        var ds =
                new DriverManagerDataSource(
                        "jdbc:postgresql://127.0.0.1:25432/" + DATABASE,
                        "agent_v12",
                        System.getenv("AGENT_DB_PASSWORD"));
        Flyway.configure()
                .dataSource(ds)
                .schemas("engine")
                .defaultSchema("engine")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        db = new JdbcTemplate(ds);

        System.setProperty(
                "agent.jwt.secret", "TEST-ONLY-jwt-secret-for-promotion-chain-0123456789");
        SecurityConfiguration.bindDeployment(
                new MockEnvironment()
                        .withProperty("agent.security.mode", "dev-hmac")
                        .withProperty("agent.security.service-id", "engine")
                        .withProperty("agent.dev-auth", "true")
                        .withProperty("server.address", "127.0.0.1"));

        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext(
                "/internal/app-drafts/",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    respond(exchange, 200, ApiResponse.ok(draft.get()));
                });
        upstream.createContext(
                "/internal/datasets/promotions",
                exchange -> {
                    received.set(
                            Json.read(
                                    new String(
                                            exchange.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8)));
                    var r = reply.get();
                    if (r == null) {
                        respond(exchange, 200, ApiResponse.ok(received.get()));
                    } else {
                        respond(
                                exchange,
                                r.status(),
                                new ApiResponse<>(r.code(), r.message(), null, null));
                    }
                });
        upstream.start();
        var base = "http://127.0.0.1:" + upstream.getAddress().getPort();
        service =
                new TestInputService(
                        db, new DataSourceTransactionManager(ds), new TestInputClient(base, base));
    }

    @AfterAll
    static void stop() {
        if (upstream != null) upstream.stop(0);
    }

    @BeforeEach
    void fixture() {
        tenant = "promote_" + UUID.randomUUID();
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new JwtAuthenticationToken(
                                Jwt.withTokenValue("verified")
                                        .header("alg", "HS256")
                                        .subject("owner")
                                        .claim("tenant_id", tenant)
                                        .claim("roles", List.of("EDITOR"))
                                        .issuedAt(Instant.now())
                                        .expiresAt(Instant.now().plusSeconds(60))
                                        .build()));
        draft.set(
                Json.tree(
                        Map.of(
                                "draftId", 1,
                                "appCode", "demo",
                                "draftRev", 1,
                                "status", "DRAFT",
                                "config",
                                        Map.of(
                                                "inputSchema", Map.of("type", "object"),
                                                "inputs", List.of()),
                                "definition", Map.of())));
        received.set(null);
        reply.set(null);
    }

    @Test
    void promotionCarriesCanonicalNumericAssertionObject() {
        long id = create();
        service.promote(
                id,
                Json.tree(
                        Map.of(
                                "requestId", "numeric-1",
                                "revision", 1,
                                "datasetVersion", "numeric-v1",
                                "caseId", "numeric",
                                "expected", "40",
                                "safety", false,
                                "assertion",
                                        Map.of("mode", "NUMERIC_TOLERANCE", "tolerance", 0.05))));

        JsonNode assertion = received.get().path("cases").get(0).path("assertion");
        assertEquals("NUMERIC_TOLERANCE", assertion.path("mode").asText());
        assertEquals(0.05, assertion.path("tolerance").asDouble(), 1e-9);
    }

    @Test
    void promotionCarriesStringModeAndOmitsMissingAssertion() {
        long containsId = create();
        service.promote(
                containsId,
                Json.tree(
                        Map.of(
                                "requestId", "contains-1",
                                "revision", 1,
                                "datasetVersion", "contains-v1",
                                "caseId", "contains",
                                "expected", "ok",
                                "safety", false,
                                "assertion", "CONTAINS")));
        JsonNode carried = received.get().path("cases").get(0);
        assertTrue(carried.path("assertion").isTextual());
        assertEquals("CONTAINS", carried.path("assertion").asText());

        long absentId = create();
        service.promote(
                absentId,
                Json.tree(
                        Map.of(
                                "requestId", "absent-1",
                                "revision", 1,
                                "datasetVersion", "absent-v1",
                                "caseId", "absent",
                                "expected", "ok",
                                "safety", false)));
        // 缺失=EXACT 由 monitor 保存期解释；提升层不替调用方写默认键。
        assertFalse(received.get().path("cases").get(0).has("assertion"));
    }

    @Test
    void promotionRejectionKeepsMonitorAssertionCodeAcrossHttp() {
        long id = create();
        reply.set(
                new Reply(
                        400,
                        "EVALUATION_ASSERTION_SAFETY_CONSTRAINT",
                        "安全用例只允许严格相等或Schema校验"));
        var thrown =
                assertThrows(
                        PlatformException.class,
                        () ->
                                service.promote(
                                        id,
                                        Json.tree(
                                                Map.of(
                                                        "requestId", "reject-1",
                                                        "revision", 1,
                                                        "datasetVersion", "reject-v1",
                                                        "caseId", "reject",
                                                        "expected", "ok",
                                                        "safety", true,
                                                        "assertion", "CONTAINS"))));
        assertEquals(400, thrown.status());
        assertEquals("EVALUATION_ASSERTION_SAFETY_CONSTRAINT", thrown.code());
        assertEquals("安全用例只允许严格相等或Schema校验", thrown.getMessage());
    }

    long create() {
        var saved =
                service.create(
                        1,
                        Json.tree(Map.of("name", "named", "inputs", Map.of("q", "old"))));
        return ((Number) saved.get("id")).longValue();
    }

    static void respond(HttpExchange exchange, int status, ApiResponse<?> body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
