package cn.agentplatform.app.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.app.infra.ReleasePins;
import cn.agentplatform.common.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 发布事务与本体发布绑定的集成证据：提交成功且声明了 config.ontology 时，必须在事务提交后
 * 恰好向本体服务发一次绑定请求，并把结果挂到 commit 返回体的 pin 键；任何绑定失败都不能
 * 吞掉已提交的版本（fail-open 默认），也不能让异常路径污染返回体。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class PublishPinTest {
    static final String TENANT = "application-tests";
    static final Actor PUBLISHER = new Actor(TENANT, "publisher", Set.of("ADMIN"), Map.of());
    static final String SPACE = "3f8b6c1e-9d2a-4a51-8a77-1c2d3e4f5a6b";
    static final String RELEASE_A = "8a1f2b3c-4d5e-4f60-9a71-b2c3d4e5f607";
    static final String RELEASE_B = "c0d1e2f3-a4b5-4c76-8d98-e0f1a2b3c4d5";
    static final String API_KEY = "publish-pin-api-key";

    static JdbcTemplate db;
    static AppService apps;
    static String apiKeyRef;
    static Path secretDir;
    static String previousDir;
    static String previousKey;
    String code;

    @BeforeAll
    static void databaseAndSecrets() throws IOException {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:postgresql://127.0.0.1:25432/agent_platform_v12",
                        "agent_v12",
                        System.getenv("AGENT_DB_PASSWORD"));
        Flyway.configure()
                .dataSource(ds)
                .schemas("app")
                .defaultSchema("app")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        db = new JdbcTemplate(ds);
        apps =
                new AppService(
                        db,
                        new DataSourceTransactionManager(ds),
                        "http://127.0.0.1:1",
                        "http://127.0.0.1:1",
                        "http://127.0.0.1:1",
                        "http://127.0.0.1:1");
        previousDir = System.getProperty("agent.secret.dir");
        previousKey = System.getProperty("agent.master.key");
        secretDir = Files.createTempDirectory("publish-pin-secret");
        System.setProperty("agent.secret.dir", secretDir.toString());
        System.setProperty("agent.master.key", "0123456789abcdef0123456789abcdef");
        apiKeyRef = SecretVault.put(API_KEY, PUBLISHER);
    }

    @AfterAll
    static void restoreSecrets() throws IOException {
        restore("agent.secret.dir", previousDir);
        restore("agent.master.key", previousKey);
        try (var paths = Files.walk(secretDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private static void restore(String name, String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
    }

    static void actor(String tenant) {
        var jwt =
                Jwt.withTokenValue("test-verified")
                        .header("alg", "HS256")
                        .subject("publisher")
                        .claim("tenant_id", tenant)
                        .claim("roles", List.of("EDITOR", "ADMIN"))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(120))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @BeforeEach
    void create() {
        actor(TENANT);
        code = "test_" + UUID.randomUUID().toString().replace("-", "");
        apps.create(Map.of("code", code, "name", "发布绑定测试", "type", "FLOW"));
    }

    @AfterEach
    void clear() {
        apps.pins(null);
        SecurityContextHolder.clearContext();
    }

    Map<String, Object> permit() {
        var app = apps.get(code);
        String pc = "pc_" + UUID.randomUUID();
        String sourceReport = "fixture-" + pc;
        String sha = app.get("candidateSha256").toString();
        long rev = ((Number) app.get("draftRev")).longValue();
        db.update(
                "insert into"
                    + " app.publish_report(tenant_id,id,app_draft_id,app_code,draft_rev,candidate_sha256,eval_report_id,checks,passed,expires_at)"
                    + " values(?,?,(select id from app.app_draft where tenant_id=? and"
                    + " app_code=?),?,?,?,?,?::jsonb,true,now()+interval '10 minutes')",
                TENANT,
                pc,
                TENANT,
                code,
                code,
                rev,
                sha,
                sourceReport,
                fixtureEnvironmentChecks(app, TENANT, sourceReport));
        return Map.of(
                "precheckId",
                pc,
                "draftRev",
                rev,
                "candidateSha256",
                sha,
                "idempotencyRequestNo",
                "req-1");
    }

    static Map<String, Object> ontology(String space, String release) {
        return Map.of("space", space, "releaseId", release);
    }

    static Map<String, Object> draft(Map<String, Object> config) {
        var body = new LinkedHashMap<String, Object>();
        body.put("draftRev", 1);
        body.put("config", config);
        return body;
    }

    @Test
    void declaredOntologyPinsCommittedRelease() throws Exception {
        try (var stub = new Stub()) {
            apps.pins(new ReleasePins(stub.base(), apiKeyRef, true, false, "production"));
            apps.save(code, draft(Map.of("ontology", ontology(SPACE, RELEASE_A))), false);

            var result = apps.commit(code, permit());

            assertEquals(1, result.get("versionNo"));
            assertEquals(code, result.get("appCode"));
            assertNotNull(result.get("sha256"));
            assertTrue(result.get("artifacts") instanceof Map, "发布凭据必须保持原样");
            var pin = AppService.object(result.get("pin"));
            assertEquals("PINNED", pin.get("state"));
            assertEquals("bind-1", pin.get("bindingId"));
            assertEquals(true, pin.get("changed"));
            assertEquals(1, stub.requests.size());
            var sent = stub.requests.getFirst();
            assertEquals("POST", sent.method());
            assertEquals("/onto/internal/release-bindings", sent.path());
            assertEquals(API_KEY, sent.apiKey());
            assertNull(sent.authorization());
            assertEquals(
                    Map.of(
                            "spaceId", SPACE,
                            "platformTenant", TENANT,
                            "appCode", code,
                            "environment", "production",
                            "releaseId", RELEASE_A),
                    sent.body());
        }
    }

    @Test
    void undeclaredOntologyPublishesWithoutPin() throws Exception {
        try (var stub = new Stub()) {
            apps.pins(new ReleasePins(stub.base(), apiKeyRef, true, false, "production"));
            apps.save(code, draft(Map.of("changed", true)), false);

            var result = apps.commit(code, permit());

            assertEquals(1, result.get("versionNo"));
            var pin = AppService.object(result.get("pin"));
            assertEquals("SKIPPED", pin.get("state"));
            assertNotNull(pin.get("reason"));
            assertEquals(0, stub.requests.size());
        }
    }

    @Test
    void nonUuidSpaceSkipsPinButPublishes() throws Exception {
        try (var stub = new Stub()) {
            apps.pins(new ReleasePins(stub.base(), apiKeyRef, true, false, "production"));
            apps.save(code, draft(Map.of("ontology", ontology("space-1", RELEASE_A))), false);

            var result = apps.commit(code, permit());

            assertEquals(1, result.get("versionNo"));
            var pin = AppService.object(result.get("pin"));
            assertEquals("SKIPPED", pin.get("state"));
            assertTrue(pin.get("reason").toString().contains("UUID"));
            assertEquals(0, stub.requests.size());
        }
    }

    @Test
    void remoteFailureLeavesPublishCommitted() throws Exception {
        try (var stub = new Stub()) {
            stub.status = 500;
            apps.pins(new ReleasePins(stub.base(), apiKeyRef, true, false, "production"));
            apps.save(code, draft(Map.of("ontology", ontology(SPACE, RELEASE_A))), false);

            var result = apps.commit(code, permit());

            assertEquals(1, result.get("versionNo"));
            assertEquals("FAILED", AppService.object(result.get("pin")).get("state"));
            assertEquals(1, apps.versions(code).size());
            assertEquals(1, stub.requests.size());
        }
    }

    @Test
    void failClosedTurnsResponseInto502() throws Exception {
        try (var stub = new Stub()) {
            stub.status = 500;
            apps.pins(new ReleasePins(stub.base(), apiKeyRef, true, true, "production"));
            apps.save(code, draft(Map.of("ontology", ontology(SPACE, RELEASE_A))), false);

            var thrown = assertThrows(PlatformException.class, () -> apps.commit(code, permit()));

            assertEquals(502, thrown.status());
            assertEquals("PIN_FAILED", thrown.code());
            assertFalse(thrown.getMessage().contains(API_KEY), "错误信息不得泄露密钥");
            // 绑定在事务提交之后：fail-closed 只能拒绝本次响应，版本已经落库，重放会拿到同一版本并重试绑定。
            assertEquals(1, apps.versions(code).size());
        }
    }

    @Test
    void disabledPinSwitchPublishes() throws Exception {
        try (var stub = new Stub()) {
            apps.pins(new ReleasePins(stub.base(), apiKeyRef, false, false, "production"));
            apps.save(code, draft(Map.of("ontology", ontology(SPACE, RELEASE_A))), false);

            var result = apps.commit(code, permit());

            assertEquals(1, result.get("versionNo"));
            assertEquals("DISABLED", AppService.object(result.get("pin")).get("state"));
            assertEquals(0, stub.requests.size());
        }
    }

    @Test
    void replayPinsReleasedSnapshotNotCurrentDraft() throws Exception {
        try (var stub = new Stub()) {
            apps.pins(new ReleasePins(stub.base(), apiKeyRef, true, false, "production"));
            apps.save(code, draft(Map.of("ontology", ontology(SPACE, RELEASE_A))), false);
            var first = permit();
            assertEquals(1, apps.commit(code, first).get("versionNo"));

            long currentRev = ((Number) apps.get(code).get("draftRev")).longValue();
            apps.save(
                    code,
                    Map.of("draftRev", currentRev, "config", Map.of("ontology", ontology(SPACE, RELEASE_B))),
                    false);

            // 同一请求号重放发布 v1：绑定必须描述已固化的 v1，而不是后来改成 RELEASE_B 的草稿。
            var replay = apps.commit(code, first);

            assertEquals(1, replay.get("versionNo"));
            assertEquals("PINNED", AppService.object(replay.get("pin")).get("state"));
            assertEquals(2, stub.requests.size());
            assertEquals(RELEASE_A, stub.requests.get(1).body().get("releaseId"));
            assertEquals(1, apps.versions(code).size());
        }
    }

    @Test
    void unassembledServiceReportsDisabled() {
        apps.save(code, draft(Map.of("ontology", ontology(SPACE, RELEASE_A))), false);

        var result = apps.commit(code, permit());

        assertEquals(1, result.get("versionNo"));
        assertEquals("DISABLED", AppService.object(result.get("pin")).get("state"));
    }

    /** 只记录真实到达的绑定请求；测试断言的是线上可见的事实而不是替身调用。 */
    private static final class Stub implements AutoCloseable {
        private final HttpServer server;
        final List<Sent> requests = new CopyOnWriteArrayList<>();
        volatile int status = 200;
        volatile String body = "{\"data\":{\"bindingId\":\"bind-1\",\"changed\":true}}";

        record Sent(String method, String path, String apiKey, String authorization, Map<String, Object> body) {}

        Stub() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        String base() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, Object> parsed;
                try {
                    parsed = Json.read(raw, Map.class);
                } catch (RuntimeException e) {
                    parsed = Map.of();
                }
                var headers = exchange.getRequestHeaders();
                requests.add(
                        new Sent(
                                exchange.getRequestMethod(),
                                exchange.getRequestURI().getPath(),
                                headers.getFirst("X-API-Key"),
                                headers.getFirst("Authorization"),
                                parsed));
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /** 此类只验证发布事务/生命周期：显式有限来源夹具绑定本例候选，不能当作真实监控评测证据。 */
    private String fixtureEnvironmentChecks(Map<String, Object> app, String tenantId, String reportId) {
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("schemaVersion", 1); evidence.put("scope", "ENGINE_RUNTIME_AND_DATASET_IDENTITY");
        evidence.put("tenantId", tenantId); evidence.put("appCode", app.get("code"));
        evidence.put("draftRev", app.get("draftRev")); evidence.put("candidateSha256", app.get("candidateSha256"));
        evidence.put("candidateSemanticDigest", app.get("semanticDigest")); evidence.put("evalReportId", reportId);
        evidence.put("datasetVersion", "PublishPinTest-environment-fixture-v1");
        evidence.put("datasetSha256", Digest.sha256(Json.canonical(List.of(Map.of("caseId", "fixture-case", "runMode", "REAL")))));
        evidence.put("environment", "REAL"); evidence.put("qualityAccepted", true); evidence.put("status", "ACCEPTED");
        evidence.put("evidenceSha256", Digest.sha256(Json.canonical(evidence)));
        return Json.write(List.of(Map.of("group", "评测环境", "result", "PASS", "schemaVersion", 1,
                "publicationTarget", "PRODUCTION", "candidateSha256", app.get("candidateSha256"),
                "semanticDigest", app.get("semanticDigest"), "publicationEvidence", evidence)));
    }
}
