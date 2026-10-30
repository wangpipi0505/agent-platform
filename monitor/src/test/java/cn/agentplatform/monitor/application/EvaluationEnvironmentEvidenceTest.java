package cn.agentplatform.monitor.application;

import cn.agentplatform.common.*;
import com.sun.net.httpserver.HttpServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class EvaluationEnvironmentEvidenceTest {
    static JdbcTemplate db;
    static DataSourceTransactionManager manager;
    static final String OLD_KEY = System.getProperty("agent.jwt.secret");
    HttpServer peer;
    EvaluationService service;
    String tenant, code;
    volatile String mode = "DEMO";
    volatile boolean pending;
    final AtomicBoolean initializedBeforeSend = new AtomicBoolean(true);

    @BeforeAll static void database() {
        var ds = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/agent_platform_v12", "agent_v12", System.getenv("AGENT_DB_PASSWORD"));
        Flyway.configure().dataSource(ds).schemas("monitor").defaultSchema("monitor").locations("classpath:db/migration").load().migrate();
        db = new JdbcTemplate(ds); manager = new DataSourceTransactionManager(ds);
        System.setProperty("agent.jwt.secret", "ex17-monitor-independent-key-0123456789abcdef");
        SecurityConfiguration.bindDeployment(new org.springframework.mock.env.MockEnvironment()
                .withProperty("agent.security.mode", "dev-hmac").withProperty("agent.security.service-id", "monitor")
                .withProperty("agent.dev-auth", "true").withProperty("server.address", "127.0.0.1"));
    }

    @AfterAll static void restoreKey() {
        if (OLD_KEY == null) System.clearProperty("agent.jwt.secret"); else System.setProperty("agent.jwt.secret", OLD_KEY);
    }

    @BeforeEach void fixture() throws Exception {
        tenant = "ex17-monitor-" + UUID.randomUUID(); code = "env_" + UUID.randomUUID().toString().replace("-", "");
        var jwt = Jwt.withTokenValue("verified-fixture").header("alg", "HS256").subject("evaluator")
                .claim("tenant_id", tenant).claim("roles", List.of("EDITOR"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(120)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        peer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        peer.createContext("/", exchange -> {
            Map<String, Object> response;
            if (exchange.getRequestMethod().equals("GET")) {
                response = Map.of("appCode", code, "draftRev", 1, "candidateSha256", "a".repeat(64),
                        "semanticDigest", "b".repeat(64), "config", Map.of(), "definition", Map.of());
            } else {
                var request = Json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                var frozen = Json.read(db.queryForObject("select results::text from monitor.evaluation_report where tenant_id=? and id=?",
                        String.class, tenant, request.path("evalReportId").asText()));
                if (frozen.isEmpty() || frozen.get(0).path("sourceEvidence").path("schemaVersion").asInt() != 1)
                    initializedBeforeSend.set(false);
                var run = new LinkedHashMap<String, Object>();
                run.put("runId", "run-" + request.path("evalReportId").asText());
                run.put("status", pending ? "RUNNING" : "SUCCEEDED"); run.put("output", "ok");
                if (!"MISSING".equals(mode)) {
                    run.put("environment", mode); run.put("demoMode", "DEMO".equals(mode));
                    run.put("datasetVersion", request.path("datasetVersion").asText());
                }
                response = run;
            }
            byte[] bytes = Json.write(Map.of("data", response)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); } finally { exchange.close(); }
        });
        peer.start();
        String base = "http://127.0.0.1:" + peer.getAddress().getPort();
        service = new EvaluationService(db, manager, base, base);
    }

    @AfterEach void close() { if (peer != null) peer.stop(0); SecurityContextHolder.clearContext(); }

    private Map<String, Object> request(String dataset) {
        return Map.of("appCode", code, "draftRev", 1, "candidateSha256", "a".repeat(64), "datasetVersion", dataset,
                "environment", "REAL", "demoMode", false,
                "cases", List.of(Map.of("id", "safe", "inputs", Map.of(), "expected", "ok", "safety", true)));
    }

    @Test void trustedRuntimeAndDatasetAreFrozenWithoutTrustingCallerOrUpgradingLegacyResume() {
        String demoId = null; Object demoEvidence = null;
        for (String environment : List.of("DEMO", "REAL", "MISSING")) {
            mode = environment;
            var report = service.evaluate(request("ds-" + environment));
            assertThat(report.get("status")).isEqualTo("ACCEPTED");
            var proof = Json.tree(report.get("publicationEvidence"));
            assertThat(proof.path("environment").asText()).isEqualTo("MISSING".equals(environment) ? "UNKNOWN" : environment);
            assertThat(proof.path("qualityAccepted").asBoolean()).isTrue();
            assertThat(proof.path("datasetSha256").asText()).isEqualTo(db.queryForObject(
                    "select sha256 from monitor.dataset where tenant_id=? and app_code=? and dataset_version=?",
                    String.class, tenant, code, "ds-" + environment));
            var copy = new LinkedHashMap<>(asMap(proof)); Object digest = copy.remove("evidenceSha256");
            assertThat(Digest.sha256(Json.canonical(copy))).isEqualTo(digest);
            if ("DEMO".equals(environment)) { demoId = report.get("id").toString(); demoEvidence = report.get("publicationEvidence"); }
        }
        assertThat(initializedBeforeSend).isTrue();
        mode = "REAL";
        assertThat(service.report(demoId).get("publicationEvidence")).isEqualTo(demoEvidence);
        pending = true;
        var unfinished = service.evaluate(request("ds-old-running"));
        assertThat(unfinished.get("status")).isEqualTo("RUNNING");
        String id = unfinished.get("id").toString();
        // 只把本测试刚创建的一行还原为旧报告格式；恢复可判分，但不能把事后 REAL 标签追认成原来源。
        db.update("update monitor.evaluation_report set results='[]'::jsonb where tenant_id=? and id=?", tenant, id);
        pending = false;
        var recovered = service.resume(id);
        assertThat(recovered.get("status")).isEqualTo("ACCEPTED");
        assertThat(Json.tree(recovered.get("publicationEvidence")).path("environment").asText()).isEqualTo("UNKNOWN");
    }

    @SuppressWarnings("unchecked") private static Map<String, Object> asMap(com.fasterxml.jackson.databind.JsonNode node) {
        return Json.mapper().convertValue(node, Map.class);
    }
}
