package cn.agentplatform.app.application;

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
import org.springframework.test.util.ReflectionTestUtils;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class EvaluationEnvironmentGateTest {
    static JdbcTemplate db;
    static DataSourceTransactionManager manager;
    static final String OLD_KEY = System.getProperty("agent.jwt.secret");
    HttpServer peer;
    AppService apps;
    String tenant, code;
    volatile String mode = "REAL";
    volatile boolean proof = true, quality = true;

    @BeforeAll static void database() {
        var ds = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/agent_platform_v12", "agent_v12", System.getenv("AGENT_DB_PASSWORD"));
        Flyway.configure().dataSource(ds).schemas("app").defaultSchema("app").locations("classpath:db/migration").load().migrate();
        db = new JdbcTemplate(ds); manager = new DataSourceTransactionManager(ds);
        System.setProperty("agent.jwt.secret", "ex17-app-independent-key-0123456789abcdef");
        SecurityConfiguration.bindDeployment(new org.springframework.mock.env.MockEnvironment()
                .withProperty("agent.security.mode", "dev-hmac").withProperty("agent.security.service-id", "app")
                .withProperty("agent.dev-auth", "true").withProperty("server.address", "127.0.0.1"));
    }

    @AfterAll static void restoreKey() {
        if (OLD_KEY == null) System.clearProperty("agent.jwt.secret"); else System.setProperty("agent.jwt.secret", OLD_KEY);
    }

    @BeforeEach void fixture() throws Exception {
        tenant = "ex17-app-" + UUID.randomUUID();
        var jwt = Jwt.withTokenValue("verified-fixture").header("alg", "HS256").subject("publisher")
                .claim("tenant_id", tenant).claim("roles", List.of("EDITOR", "ADMIN"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(120)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        peer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        peer.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getPath();
            Map<String, Object> data;
            if (path.equals("/internal/evaluations/accepted")) {
                var query = new HashMap<String, String>();
                for (String part : exchange.getRequestURI().getRawQuery().split("&")) {
                    var pair = part.split("=", 2); query.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
                }
                var result = new LinkedHashMap<String, Object>();
                result.put("accepted", true); result.put("evalReportId", "quality-only-report");
                if (proof) {
                    var evidence = new LinkedHashMap<String, Object>();
                    evidence.put("schemaVersion", 1); evidence.put("scope", "ENGINE_RUNTIME_AND_DATASET_IDENTITY");
                    evidence.put("tenantId", tenant); evidence.put("appCode", query.get("appCode"));
                    evidence.put("draftRev", Long.parseLong(query.get("draftRev")));
                    evidence.put("candidateSha256", query.get("candidateSha256"));
                    evidence.put("candidateSemanticDigest", query.get("semanticDigest"));
                    evidence.put("evalReportId", "source-report"); evidence.put("datasetVersion", "ex17-dataset");
                    evidence.put("datasetSha256", "d".repeat(64)); evidence.put("environment", mode);
                    evidence.put("qualityAccepted", quality); evidence.put("status", quality ? "ACCEPTED" : "REJECTED");
                    evidence.put("evidenceSha256", Digest.sha256(Json.canonical(evidence)));
                    result.put("publicationEvidence", evidence);
                }
                data = result;
            } else if (path.equals("/internal/model-bindings/freeze")) {
                data = Map.of("modelEntryId", 41, "bindingId", 7, "sha256", "ex17-frozen-model", "entry", Map.of("id", 41, "modelName", "ex17-model"));
            } else if (path.startsWith("/internal/model-entries")) {
                data = Map.of("id", 41, "provider", Map.of("status", "ENABLED"));
            } else data = Map.of("valid", true);
            byte[] bytes = Json.write(Map.of("data", data)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); } finally { exchange.close(); }
        });
        peer.start();
        String base = "http://127.0.0.1:" + peer.getAddress().getPort();
        apps = new AppService(db, manager, base, base, base, base);
        createApp();
    }

    private void createApp() {
        code = "env_" + UUID.randomUUID().toString().replace("-", "");
        apps.create(Map.of("code", code, "name", "来源隔离", "type", "CHAT"));
        apps.save(code, Map.of("draftRev", 1, "config", Map.of("modelBinding", Map.of("modelEntryId", 41))), false);
    }

    @AfterEach void close() { if (peer != null) peer.stop(0); SecurityContextHolder.clearContext(); }

    @Test void productionDemoAndUnknownCannotBeWaivedOrHiddenByQualityModesOrAnotherReportsPass() {
        var app = apps.get(code);
        var waiver = apps.createWaiver(code, Map.of("draftRev", app.get("draftRev"), "candidateSha256", app.get("candidateSha256"),
                "reason", "仅用于验证普通质量豁免不能改变环境资格", "expiresAt", Instant.now().plusSeconds(600).toString()));
        for (String gate : List.of("BLOCKING", "ADVISORY", "OFF")) {
            apps.configurePublishGate(gate, true);
            for (String environment : List.of("DEMO", "UNKNOWN")) {
                mode = environment;
                assertCode(() -> apps.precheck(code), "EVALUATION_ENVIRONMENT_BLOCKED");
            }
        }
        proof = false;
        assertCode(() -> apps.precheck(code), "EVALUATION_ENVIRONMENT_BLOCKED");
        assertThat(db.queryForObject("select count(*) from app.app_version where tenant_id=? and app_code=?", Integer.class, tenant, code)).isZero();
        // 同一候选的另份质量 PASS 不能与来源报告的 REJECTED 拼接；撤销普通豁免后质量仍须独立阻断。
        apps.revokeWaiver(code, waiver.get("id").toString());
        apps.configurePublishGate("BLOCKING", true); proof = true; quality = false; mode = "REAL";
        assertCode(() -> apps.precheck(code), "EVAL_GATE_BLOCKED");
    }

    @Test void realProofAndExplicitNonproductionConfirmationAreFrozenAndOldPrechecksCannotCreateNewPublication() {
        var precheck = apps.precheck(code);
        var request = request(precheck);
        var first = apps.commit(code, request);
        var stored = Json.read(db.queryForObject("select snapshot::text from app.app_version where tenant_id=? and app_code=?", String.class, tenant, code));
        assertThat(stored.path("publicationTarget").asText()).isEqualTo("PRODUCTION");
        assertThat(stored.path("publicationEvidence").path("environment").asText()).isEqualTo("REAL");
        assertThat(stored.path("publicationEvidence").path("evalReportId").asText()).isEqualTo("source-report");
        String original = stored.toString();
        String legacy = "pc_" + UUID.randomUUID();
        db.update("insert into app.publish_report(tenant_id,id,app_draft_id,app_code,draft_rev,candidate_sha256,eval_report_id,checks,passed,expires_at,model_refs,gate_mode)"
                        + " select tenant_id,?,app_draft_id,app_code,draft_rev,candidate_sha256,eval_report_id,'[]'::jsonb,true,expires_at,model_refs,gate_mode from app.publish_report where tenant_id=? and id=?",
                legacy, tenant, precheck.get("precheckId"));
        var legacyRequest = new LinkedHashMap<>(request); legacyRequest.put("precheckId", legacy); legacyRequest.put("idempotencyRequestNo", UUID.randomUUID().toString());
        assertCode(() -> apps.commit(code, legacyRequest), "EVALUATION_ENVIRONMENT_REQUIRED");
        assertThat(apps.commit(code, request)).isEqualTo(first);
        assertThat(Json.read(db.queryForObject("select snapshot::text from app.app_version where tenant_id=? and app_code=?", String.class, tenant, code)).toString()).isEqualTo(original);

        createApp(); mode = "DEMO";
        ReflectionTestUtils.setField(apps, "publicationTarget", "NON_PRODUCTION");
        var demo = apps.precheck(code); var demoRequest = request(demo);
        assertCode(() -> apps.commit(code, demoRequest), "EVALUATION_ENVIRONMENT_CONFIRMATION_REQUIRED");
        demoRequest.put("evaluationEnvironmentConfirmation", Map.of("confirmed", true, "precheckId", demo.get("precheckId"),
                "evalReportId", demo.get("evalReportId"), "evidenceSha256", demo.get("environmentEvidenceSha256")));
        apps.commit(code, demoRequest);
        ReflectionTestUtils.setField(apps, "publicationTarget", "PRODUCTION");
        demoRequest.put("idempotencyRequestNo", UUID.randomUUID().toString());
        assertCode(() -> apps.commit(code, demoRequest), "EVALUATION_ENVIRONMENT_BLOCKED");
    }

    private Map<String, Object> request(Map<String, Object> precheck) {
        var request = new LinkedHashMap<String, Object>();
        for (String key : List.of("precheckId", "draftRev", "candidateSha256")) request.put(key, precheck.get(key));
        request.put("idempotencyRequestNo", UUID.randomUUID().toString());
        return request;
    }
    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(PlatformException.class, e -> {
            assertThat(e.status()).isEqualTo(409); assertThat(e.code()).isEqualTo(code);
        });
    }
}
