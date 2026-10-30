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
 * T18.3 / PLAT-18：pin 结果落库证据。真实 PG 专用库 {@code agent_platform_pin_audit_v12}：
 * Fresh DB 按 105→106→107 顺序应用（该库绝不复用其它泳道已到 V107 的库，否则 V106 会触发
 * Flyway outOfOrder 校验失败）。验证四点：
 * 四态（PINNED/SKIPPED/FAILED/DISABLED）各自落库可查；旧发布记录无 pin 行时反查不报错；
 * 重新发布覆盖后反查到最新；发布响应 pin 键与 T-pin（4caf8af）行为逐键兼容。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class PublishPinAuditPostgresTest {
    static final String DB = "agent_platform_pin_audit_v12";
    static final String TENANT = "pin-audit-tests";
    static final Actor PUBLISHER = new Actor(TENANT, "publisher", Set.of("ADMIN"), Map.of());
    static final String SPACE = "3f8b6c1e-9d2a-4a51-8a77-1c2d3e4f5a6b";
    static final String RELEASE_A = "8a1f2b3c-4d5e-4f60-9a71-b2c3d4e5f607";
    static final String RELEASE_B = "c0d1e2f3-a4b5-4c76-8d98-e0f1a2b3c4d5";
    static final String API_KEY = "pin-audit-api-key";

    static JdbcTemplate db;
    static AppService apps;
    static String apiKeyRef;
    static Path secretDir;
    static String previousDir;
    static String previousKey;
    String code;

    @BeforeAll
    static void databaseAndSecrets() throws IOException {
        // 专用库只在不存在时创建：既保证 Fresh DB 顺序迁移，也绝不触碰其它泳道的库。
        var admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:postgresql://127.0.0.1:25432/postgres",
                                "agent_v12",
                                System.getenv("AGENT_DB_PASSWORD")));
        if (!Boolean.TRUE.equals(
                admin.queryForObject(
                        "select exists(select 1 from pg_database where datname=?)",
                        Boolean.class,
                        DB)))
            admin.execute("CREATE DATABASE " + DB);
        var ds =
                new DriverManagerDataSource(
                        "jdbc:postgresql://127.0.0.1:25432/" + DB,
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
        secretDir = Files.createTempDirectory("pin-audit-secret");
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
        // apps 是跨测试共享的静态实例：开关类测试改过的实例状态必须逐用例复位。
        apps.pinRecord(true);
        code = "audit_" + UUID.randomUUID().toString().replace("-", "");
        apps.create(Map.of("code", code, "name", "pin 落库测试", "type", "FLOW"));
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
                "req-" + UUID.randomUUID().toString().substring(0, 8));
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

    /** V106 表结构与历史顺序：Fresh DB 上 105→106→107 必须按序入库。 */
    @Test
    void v106LandsPublishPinTableWithStateContractAndOrderedHistory() {
        assertEquals(
                1,
                db.queryForObject(
                        "select count(*) from information_schema.tables where table_schema='app'"
                                + " and table_name='publish_pin'",
                        Integer.class));
        for (String column :
                List.of(
                        "release_id", "binding_id", "reason",
                        "last_pinned_release_id", "last_pinned_binding_id", "last_pinned_at")) {
            var nullable =
                    db.queryForMap(
                            "select is_nullable from information_schema.columns where"
                                + " table_schema='app' and table_name='publish_pin' and column_name='"
                                + column
                                + "'");
            assertEquals("YES", nullable.get("is_nullable"), "publish_pin." + column + " 必须可空");
        }
        // state 永远可判定（四态之一）、environment 有默认值：两者非空；旧发布记录以"无行"表达，
        // 而不是以空 state 表达。
        for (String column : List.of("state", "environment")) {
            var notNull =
                    db.queryForMap(
                            "select is_nullable from information_schema.columns where"
                                + " table_schema='app' and table_name='publish_pin' and column_name='"
                                + column
                                + "'");
            assertEquals("NO", notNull.get("is_nullable"), "publish_pin." + column + " 必须非空");
        }
        var history =
                db.queryForList(
                        "select version from app.flyway_schema_history where success order by"
                                + " installed_rank",
                        String.class);
        int v105 = history.indexOf("105"), v106 = history.indexOf("106"), v107 = history.indexOf("107");
        assertTrue(v105 >= 0 && v106 > v105 && v107 > v106, "必须 105→106→107 顺序入库: " + history);
    }

    @Test
    void pinnedResultIsPersistedAndQueryable() throws Exception {
        try (var stub = new Stub()) {
            apps.pins(new ReleasePins(stub.base(), apiKeyRef, true, false, "production"));
            apps.save(code, draft(Map.of("ontology", ontology(SPACE, RELEASE_A))), false);

            var result = apps.commit(code, permit());

            var status = apps.pinStatus(code);
            assertEquals("PINNED", status.get("state"));
            assertEquals(RELEASE_A, status.get("releaseId"));
            assertEquals("bind-1", status.get("bindingId"));
            assertEquals(1, status.get("versionNo"));
            assertEquals("production", status.get("environment"));
            assertNotNull(status.get("lastPinnedAt"), "PINNED 必须留下 lastPinnedAt");
            // 交付面兼容：响应 pin 键保持 T-pin 语义，不混入落库字段
            var pin = AppService.object(result.get("pin"));
            assertEquals("PINNED", pin.get("state"));
            assertEquals("bind-1", pin.get("bindingId"));
            assertEquals(true, pin.get("changed"));
            assertEquals(Set.of("state", "bindingId", "changed"), pin.keySet());
        }
    }

    @Test
    void skippedResultIsPersistedAndQueryable() throws Exception {
        try (var stub = new Stub()) {
            apps.pins(new ReleasePins(stub.base(), apiKeyRef, true, false, "production"));
            apps.save(code, draft(Map.of("changed", true)), false);

            var result = apps.commit(code, permit());

            var status = apps.pinStatus(code);
            assertEquals("SKIPPED", status.get("state"));
            assertNotNull(status.get("reason"));
            assertNull(status.get("bindingId"));
            assertNull(status.get("lastPinnedAt"), "未 PINNED 不得伪造 lastPinnedAt");
            assertEquals(0, stub.requests.size());
            assertEquals("SKIPPED", AppService.object(result.get("pin")).get("state"));
        }
    }

    @Test
    void failedResultIsPersistedButKeepsLastKnownPinnedBinding() throws Exception {
        try (var stub = new Stub()) {
            apps.pins(new ReleasePins(stub.base(), apiKeyRef, true, false, "production"));
            apps.save(code, draft(Map.of("ontology", ontology(SPACE, RELEASE_A))), false);
            // 第一次发布：PINNED，留下"远端当前指向"的已知事实。
            apps.commit(code, permit());
            assertEquals("PINNED", apps.pinStatus(code).get("state"));

            // 同应用再次发布：本体 5xx → 本次 FAILED 落库，但"远端当前指向哪个绑定"的已知事实不丢。
            long rev = ((Number) apps.get(code).get("draftRev")).longValue();
            apps.save(
                    code,
                    Map.of("draftRev", rev, "config", Map.of("ontology", ontology(SPACE, RELEASE_B))),
                    false);
            stub.status = 500;
            var result = apps.commit(code, permit());

            assertEquals("FAILED", AppService.object(result.get("pin")).get("state"));
            var status = apps.pinStatus(code);
            assertEquals("FAILED", status.get("state"));
            assertEquals(RELEASE_B, status.get("releaseId"), "FAILED 行仍记录本次声明的目标 release");
            assertEquals(RELEASE_A, status.get("lastPinnedReleaseId"), "最后已知生效绑定不因失败被抹掉");
            assertEquals("bind-1", status.get("lastPinnedBindingId"));
            assertNotNull(status.get("lastPinnedAt"));
        }
    }

    @Test
    void disabledResultIsPersistedAndQueryable() throws Exception {
        try (var stub = new Stub()) {
            apps.pins(new ReleasePins(stub.base(), apiKeyRef, false, false, "production"));
            apps.save(code, draft(Map.of("ontology", ontology(SPACE, RELEASE_A))), false);

            var result = apps.commit(code, permit());

            var status = apps.pinStatus(code);
            assertEquals("DISABLED", status.get("state"));
            assertNotNull(status.get("reason"));
            assertEquals(0, stub.requests.size());
            assertEquals("DISABLED", AppService.object(result.get("pin")).get("state"));
        }
    }

    @Test
    void republishOverwritesCurrentRowSoReverseLookupReturnsLatest() throws Exception {
        try (var stub = new Stub()) {
            stub.body = "{\"data\":{\"bindingId\":\"bind-1\",\"changed\":true}}";
            apps.pins(new ReleasePins(stub.base(), apiKeyRef, true, false, "production"));
            apps.save(code, draft(Map.of("ontology", ontology(SPACE, RELEASE_A))), false);
            apps.commit(code, permit());
            assertEquals(RELEASE_A, apps.pinStatus(code).get("releaseId"));

            stub.body = "{\"data\":{\"bindingId\":\"bind-2\",\"changed\":true}}";
            long rev = ((Number) apps.get(code).get("draftRev")).longValue();
            apps.save(
                    code,
                    Map.of("draftRev", rev, "config", Map.of("ontology", ontology(SPACE, RELEASE_B))),
                    false);
            apps.commit(code, permit());

            var status = apps.pinStatus(code);
            assertEquals("PINNED", status.get("state"));
            assertEquals(RELEASE_B, status.get("releaseId"), "反查必须拿到最新一次发布的结果");
            assertEquals("bind-2", status.get("bindingId"));
            assertEquals(2, status.get("versionNo"));
            assertEquals(
                    1,
                    db.queryForObject(
                            "select count(*) from app.publish_pin where tenant_id=? and app_code=?",
                            Integer.class,
                            TENANT,
                            code),
                    "每应用只保留一行当前 pin");
        }
    }

    @Test
    void legacyPublishWithoutPinRowIsQueriedWithoutError() throws Exception {
        try (var stub = new Stub()) {
            apps.pins(new ReleasePins(stub.base(), apiKeyRef, true, false, "production"));
            apps.save(code, draft(Map.of("ontology", ontology(SPACE, RELEASE_A))), false);
            apps.commit(code, permit());
            // 模拟 V106 之前的旧发布记录：pin 行不存在（等价于历史数据），反查必须显式为空且不报错。
            db.update("delete from app.publish_pin where tenant_id=? and app_code=?", TENANT, code);

            var status = apps.pinStatus(code);

            assertNotNull(status);
            assertTrue(status.isEmpty(), "无 pin 行的旧发布记录反查为空，不得报错或静默造数据");
        }
    }

    @Test
    void pinStatusOfUnknownAppIsExplicit404() {
        var thrown =
                assertThrows(PlatformException.class, () -> apps.pinStatus("no_such_app_x"));
        assertEquals(404, thrown.status());
        assertEquals("APP_NOT_FOUND", thrown.code());
    }

    @Test
    void recordSwitchOffStopsPersistingWithoutTouchingResponse() throws Exception {
        try (var stub = new Stub()) {
            apps.pins(new ReleasePins(stub.base(), apiKeyRef, true, false, "production"));
            apps.save(code, draft(Map.of("ontology", ontology(SPACE, RELEASE_A))), false);
            apps.pinRecord(false);

            var result = apps.commit(code, permit());

            assertEquals("PINNED", AppService.object(result.get("pin")).get("state"), "响应行为不变");
            assertEquals(
                    0,
                    db.queryForObject(
                            "select count(*) from app.publish_pin where tenant_id=? and app_code=?",
                            Integer.class,
                            TENANT,
                            code),
                    "回滚开关关闭时不得落库");
            assertTrue(apps.pinStatus(code).isEmpty());
        }
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
        evidence.put("datasetVersion", "PublishPinAuditPostgresTest-environment-fixture-v1");
        evidence.put("datasetSha256", Digest.sha256(Json.canonical(List.of(Map.of("caseId", "fixture-case", "runMode", "REAL")))));
        evidence.put("environment", "REAL"); evidence.put("qualityAccepted", true); evidence.put("status", "ACCEPTED");
        evidence.put("evidenceSha256", Digest.sha256(Json.canonical(evidence)));
        return Json.write(List.of(Map.of("group", "评测环境", "result", "PASS", "schemaVersion", 1,
                "publicationTarget", "PRODUCTION", "candidateSha256", app.get("candidateSha256"),
                "semanticDigest", app.get("semanticDigest"), "publicationEvidence", evidence)));
    }
}
