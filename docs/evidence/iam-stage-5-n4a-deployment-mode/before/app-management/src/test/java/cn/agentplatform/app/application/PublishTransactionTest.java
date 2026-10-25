package cn.agentplatform.app.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Runs only against the explicitly provisioned independent PostgreSQL instance; never substitutes
 * an in-memory DB.
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class PublishTransactionTest {
    static JdbcTemplate db;
    static AppService apps;
    String code;

    @BeforeAll
    static void database() {
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
        actor("application-tests");
        code = "test_" + UUID.randomUUID().toString().replace("-", "");
        apps.create(Map.of("code", code, "name", "发布事务测试", "type", "FLOW"));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    Map<String, Object> permit() {
        var app = apps.get(code);
        String pc = "pc_" + UUID.randomUUID();
        String sha = app.get("candidateSha256").toString();
        long rev = ((Number) app.get("draftRev")).longValue();
        db.update(
                "insert into"
                    + " app.publish_report(tenant_id,id,app_draft_id,app_code,draft_rev,candidate_sha256,eval_report_id,checks,passed,expires_at)"
                    + " values(?,?,(select id from app.app_draft where tenant_id=? and"
                    + " app_code=?),?,?,?,'test-report','[]',true,now()+interval '10 minutes')",
                "application-tests",
                pc,
                "application-tests",
                code,
                code,
                rev,
                sha);
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

    @Test
    void candidateAndPublishedVersionUseIdenticalFrozenModels() throws Exception {
        var hits = new java.util.concurrent.atomic.AtomicInteger();
        var server =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/internal/model-bindings/freeze",
                e -> {
                    int n = hits.incrementAndGet();
                    byte[] bytes =
                            Json.write(
                                            Map.of(
                                                    "data",
                                                    Map.of(
                                                            "modelEntryId",
                                                            41,
                                                            "bindingId",
                                                            n,
                                                            "sha256",
                                                            "freeze-" + n,
                                                            "entry",
                                                            Map.of(
                                                                    "id",
                                                                    41,
                                                                    "modelName",
                                                                    "original"))))
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    e.getResponseHeaders().set("Content-Type", "application/json");
                    e.sendResponseHeaders(200, bytes.length);
                    e.getResponseBody().write(bytes);
                    e.close();
                });
        server.createContext(
                "/internal/model-bindings/validate",
                e -> {
                    byte[] bytes = "{\"data\":{\"valid\":true}}".getBytes();
                    e.getResponseHeaders().set("Content-Type", "application/json");
                    e.sendResponseHeaders(200, bytes.length);
                    e.getResponseBody().write(bytes);
                    e.close();
                });
        server.start();
        try {
            var ds = db.getDataSource();
            var instance =
                    new AppService(
                            db,
                            new DataSourceTransactionManager(ds),
                            "http://127.0.0.1:1",
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            "http://127.0.0.1:1",
                            "http://127.0.0.1:1");
            instance.save(
                    code,
                    Map.of(
                            "draftRev",
                            1,
                            "config",
                            Map.of("modelBinding", Map.of("modelEntryId", 41))),
                    false);
            var app = instance.get(code);
            long rev = ((Number) app.get("draftRev")).longValue();
            String hash = app.get("candidateSha256").toString();
            var candidate = instance.frozenCandidate(code, rev, hash);
            var second = instance.frozenCandidate(code, rev, hash);
            assertNotNull(candidate.get("dependencyVersions"));
            assertEquals(candidate.get("dependencyVersions"), second.get("dependencyVersions"));
            assertEquals(1, hits.get());
            var request = permit();
            db.update(
                    "update app.publish_report set model_refs=?::jsonb where tenant_id=? and id=?",
                    Json.write(
                            AppService.object(candidate.get("dependencyVersions"))
                                    .get("modelRefs")),
                    "application-tests",
                    request.get("precheckId"));
            db.update(
                    "update app.publish_report set model_refs='[]' where tenant_id=? and id=?",
                    "application-tests",
                    request.get("precheckId"));
            assertThrows(PlatformException.class, () -> instance.commit(code, request));
            assertTrue(instance.versions(code).isEmpty());
            db.update(
                    "update app.publish_report set model_refs=?::jsonb where tenant_id=? and id=?",
                    Json.write(
                            AppService.object(candidate.get("dependencyVersions"))
                                    .get("modelRefs")),
                    "application-tests",
                    request.get("precheckId"));
            instance.commit(code, request);
            assertEquals(
                    candidate.get("dependencyVersions"),
                    instance.version(code, 1).get("dependencyVersions"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void draftStoredSeparatelyWithRealIdentity() {
        var app = apps.get(code);
        assertTrue(((Number) app.get("id")).longValue() > 0);
        assertTrue(((Number) app.get("draftId")).longValue() > 0);
        assertEquals(
                1,
                db.queryForObject(
                        "select count(*) from app.app_draft where tenant_id=? and app_id=? and"
                                + " app_code=?",
                        Integer.class,
                        "application-tests",
                        app.get("id"),
                        code));
    }

    @Test
    void inputDefaultsNamedModelEntryIdAreNotModelBindings() {
        var inputs =
                List.of(Map.of("name", "payload", "defaultValue", Map.of("modelEntryId", 999999)));
        apps.save(code, Map.of("draftRev", 1, "config", Map.of("inputs", inputs)), false);
        var app = apps.get(code);
        var snapshot =
                apps.frozenCandidate(
                        code,
                        ((Number) app.get("draftRev")).longValue(),
                        app.get("candidateSha256").toString());
        assertEquals(
                List.of(), AppService.object(snapshot.get("dependencyVersions")).get("modelRefs"));
    }

    @Test
    void staleDraftNeverPublishes() {
        var request = permit();
        apps.save(code, Map.of("draftRev", 1, "config", Map.of("changed", true)), false);
        assertThrows(PlatformException.class, () -> apps.commit(code, request));
        assertTrue(apps.versions(code).isEmpty());
    }

    @Test
    void sameAndDifferentRequestNumbersReuseCandidate() {
        var request = permit();
        assertEquals(1, apps.commit(code, request).get("versionNo"));
        assertEquals(1, apps.commit(code, request).get("versionNo"));
        var other = new HashMap<>(request);
        other.put("idempotencyRequestNo", "req-2");
        assertEquals(1, apps.commit(code, other).get("versionNo"));
        assertEquals(1, apps.versions(code).size());
        assertEquals(
                2,
                db.queryForObject(
                        "select count(*) from app.publish_commit where tenant_id=? and app_code=?",
                        Integer.class,
                        "application-tests",
                        code));
    }

    @Test
    void tenantCannotReadOrPublishAnotherTenantDraft() {
        var request = permit();
        actor("other-tests");
        assertThrows(PlatformException.class, () -> apps.get(code));
        assertThrows(PlatformException.class, () -> apps.commit(code, request));
    }

    @Test
    void layoutOnlyEditKeepsRevisionAndDigest() {
        var old = apps.get(code);
        var now =
                apps.save(
                        code,
                        Map.of("draftRev", 1, "definition", Map.of(), "layout", Map.of("x", 123)),
                        true);
        assertEquals(old.get("draftRev"), now.get("draftRev"));
        assertEquals(old.get("candidateSha256"), now.get("candidateSha256"));
    }

    @Test
    void failedReferenceWriteRollsBackVersionAndPointer() {
        apps.save(
                code,
                Map.of(
                        "draftRev",
                        1,
                        "config",
                        Map.of(
                                "skillRefs",
                                List.of(Map.of("code", "broken", "version", "not-a-number")))),
                false);
        var request = permit();
        assertThrows(PlatformException.class, () -> apps.commit(code, request));
        assertTrue(apps.versions(code).isEmpty());
        assertNull(apps.get(code).get("currentVersionNo"));
        assertEquals(
                0,
                db.queryForObject(
                        "select count(*) from app.publish_commit where tenant_id=? and app_code=?",
                        Integer.class,
                        "application-tests",
                        code));
    }

    @Test
    void integrationSecretOnlyShownOnceAndRevocationPersists() {
        apps.commit(code, permit());
        var service = new IntegrationService(db, apps);
        var issued =
                service.issue(
                        code,
                        Map.of(
                                "bizSystemCode",
                                "partner",
                                "allowedOrigins",
                                List.of("https://partner.example"),
                                "claimLimits",
                                Map.of(
                                        "principalIds",
                                        List.of("alice"),
                                        "roles",
                                        List.of("operator"),
                                        "attributes",
                                        Map.of("region", List.of("A")))));
        assertNotNull(issued.get("secretOnce"));
        assertFalse(issued.containsKey("secretRef"));
        var publicValue = service.get(code, "partner", false);
        assertFalse(publicValue.containsKey("secretOnce"));
        assertFalse(publicValue.containsKey("secretRef"));
        var internal = service.get(code, "partner", true);
        assertEquals(
                issued.get("secretOnce"),
                SecretVault.resolve(internal.get("secretRef").toString()));
        assertEquals("REVOKED", service.revoke(code, "partner").get("status"));
    }

    @Test
    void globalCredentialLookupRequiresDedicatedGatewayIdentity() {
        apps.commit(code, permit());
        var service = new IntegrationService(db, apps);
        var issued =
                service.issue(
                        code,
                        Map.of(
                                "bizSystemCode",
                                "gateway-test",
                                "allowedOrigins",
                                List.of("https://partner.example"),
                                "claimLimits",
                                Map.of(
                                        "principalIds",
                                        List.of("alice"),
                                        "roles",
                                        List.of("operator"),
                                        "attributes",
                                        Map.of())));
        long id = ((Number) issued.get("id")).longValue();
        assertTrue(issued.get("apiKeyOnce").toString().startsWith(id + ":"));
        assertThrows(PlatformException.class, () -> service.authenticate(id));
        assertThrows(PlatformException.class, () -> service.origins("https://partner.example"));
        var jwt =
                Jwt.withTokenValue("gateway-verified-test")
                        .header("alg", "HS256")
                        .subject("_gateway")
                        .claim("tenant_id", "_gateway")
                        .claim("roles", List.of("SERVICE", "GATEWAY_AUTH"))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(120))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        assertEquals("application-tests", service.authenticate(id).get("tenantId"));
        assertEquals(true, service.origins("https://partner.example").get("allowed"));
        assertEquals(
                false,
                service.origins("https://unregistered-" + UUID.randomUUID() + ".example")
                        .get("allowed"));
        assertThrows(PlatformException.class, () -> service.get(code, "gateway-test", true));
    }

    @Test
    void explicitHistoryVersionCannotBypassDisabledApplication() {
        apps.commit(code, permit());
        var controller = new cn.agentplatform.app.api.AppController(apps, new TemplateService(db),
                new cn.agentplatform.common.authority.AuthorityProperties("test", "app", null, null, null, null, null, false, null));
        assertNotNull(controller.version(code, 1));
        apps.lifecycle(code, "disable", Map.of());
        assertThrows(PlatformException.class, () -> controller.version(code, 1));
        assertNotNull(apps.version(code, 1));
        apps.lifecycle(code, "archive", Map.of());
        assertThrows(PlatformException.class, () -> controller.version(code, 1));
    }

    @Test
    void publishedInputDeclarationsComeOnlyFromExactPublishedVersion() {
        var inputs = List.of(Map.of("name", "question", "type", "string", "required", true));
        apps.save(code, Map.of("draftRev", 1, "definition", Map.of("inputs", inputs)), true);
        apps.commit(code, permit());
        var result = apps.publishedInputs(code, null);
        assertEquals(inputs, result.get("inputs"));
        assertEquals(1, result.get("versionNo"));
        assertFalse(result.containsKey("dependencyVersions"));
        apps.save(
                code,
                Map.of(
                        "draftRev",
                        2,
                        "config",
                        Map.of("inputs", List.of(Map.of("name", "unpublished")))),
                false);
        assertEquals(inputs, apps.publishedInputs(code, null).get("inputs"));
        assertEquals(1, apps.published(code).get("versionNo"));
        apps.lifecycle(code, "rollback", Map.of("versionNo", 1));
        apps.lifecycle(code, "disable", Map.of());
        assertThrows(PlatformException.class, () -> apps.publishedInputs(code, 1));
    }

    @Test
    void concurrentDistinctRequestsPublishSingleVersion() throws Exception {
        var request = permit();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var latch = new CountDownLatch(1);
            var first =
                    executor.submit(
                            () -> {
                                actor("application-tests");
                                latch.await();
                                try {
                                    return apps.commit(code, request);
                                } finally {
                                    SecurityContextHolder.clearContext();
                                }
                            });
            var second =
                    executor.submit(
                            () -> {
                                actor("application-tests");
                                latch.await();
                                try {
                                    var b = new HashMap<>(request);
                                    b.put("idempotencyRequestNo", "req-2");
                                    return apps.commit(code, b);
                                } finally {
                                    SecurityContextHolder.clearContext();
                                }
                            });
            latch.countDown();
            assertEquals(
                    first.get(10, TimeUnit.SECONDS).get("versionNo"),
                    second.get(10, TimeUnit.SECONDS).get("versionNo"));
        }
        assertEquals(1, apps.versions(code).size());
    }
}
