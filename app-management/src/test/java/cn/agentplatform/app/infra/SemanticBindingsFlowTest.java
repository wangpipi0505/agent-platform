package cn.agentplatform.app.infra;

import cn.agentplatform.app.application.SemanticBindings;
import cn.agentplatform.common.Digest;
import cn.agentplatform.common.PlatformException;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 PostgreSQL 定向用例（SEM-02.2 阶段1）：语义绑定目录的 create/revise/disable、
 * expectedRev CAS、requestId 重放与修订不可变。无 AGENT_DB_PASSWORD 时整类跳过。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class SemanticBindingsFlowTest {

    static final String RUN = Long.toString(System.currentTimeMillis() % 1_000_000L, 36);
    static final String TENANT = "t-" + RUN;
    static final AtomicInteger SEQ = new AtomicInteger();

    static JdbcTemplate db;
    static SemanticBindings bindings;

    @BeforeAll
    static void schema() throws Exception {
        var ds = new DriverManagerDataSource(
                env("AGENT_DB_URL", "jdbc:postgresql://127.0.0.1:25432/agent_platform_v12"),
                env("AGENT_DB_USER", "agent_v12"), System.getenv("AGENT_DB_PASSWORD"));
        ds.setDriverClassName("org.postgresql.Driver");
        db = new JdbcTemplate(ds);
        migrate(ds);
        var txm = new DataSourceTransactionManager(ds);
        bindings = new SemanticBindings(new SemanticBindingRepository(db, txm), new ConfigWriteLog(db));
    }

    @AfterAll
    static void cleanup() {
        SecurityContextHolder.clearContext();
        if (db == null) {
            return;
        }
        db.update("delete from app.semantic_binding_revision where tenant_id=?", TENANT);
        db.update("delete from app.semantic_binding where tenant_id=?", TENANT);
        db.update("delete from app.config_write_request where tenant_id=?", TENANT);
        db.update("delete from app.application where tenant_id=?", TENANT);
    }

    @BeforeEach
    void actor() {
        authenticate(TENANT);
    }

    @Test
    void createKeepsQuadrupleAndSkillVersion() {
        String app = app("create");
        String bindingId = "orders-" + RUN;
        Map<String, Object> out = bindings.create(body(app, bindingId, req("create"), "approved-op"));

        assertEquals(bindingId, out.get("bindingId"));
        assertEquals(app, out.get("appCode"));
        assertEquals(1, ((Number) out.get("currentRev")).intValue());
        assertEquals("ACTIVE", out.get("status"));
        assertEquals(false, out.get("replayed"));

        @SuppressWarnings("unchecked")
        Map<String, Object> revision = first(out);
        assertEquals(1, ((Number) revision.get("bindingRev")).intValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> release = (Map<String, Object>) revision.get("releaseRef");
        assertEquals("space-a", release.get("spaceCode"));
        assertEquals("v3", release.get("version"));
        assertEquals(digest("bundle"), release.get("contentDigest"));
        @SuppressWarnings("unchecked")
        Map<String, Object> skill = (Map<String, Object>) revision.get("skill");
        assertEquals("orders.query", skill.get("skillCode"));
        assertEquals(7, ((Number) skill.get("skillVersion")).intValue());
        assertEquals(List.of("read-only@1.0"), revision.get("profiles"));

        assertEquals(1, bindings.list(app).size());
        assertEquals(1, ((Number) bindings.get(bindingId).get("currentRev")).intValue());
    }

    @Test
    void sameRequestIdWritesOnce() {
        String app = app("replay");
        String bindingId = "replay-" + RUN;
        Map<String, Object> body = body(app, bindingId, req("replay"), "approved-op");

        Map<String, Object> first = bindings.create(body);
        Map<String, Object> second = bindings.create(body);
        assertEquals(false, first.get("replayed"));
        assertEquals(true, second.get("replayed"));
        assertEquals(1, ((Number) db.queryForObject("""
                select count(*) from app.semantic_binding_revision where tenant_id=? and binding_id=?
                """, Integer.class, TENANT, bindingId)).intValue());
    }

    @Test
    void reviseAppendsAndOldRevisionIsUntouched() {
        String app = app("revise");
        String bindingId = "revise-" + RUN;
        Map<String, Object> created = bindings.create(body(app, bindingId, req("create"), "approved-op"));

        Map<String, Object> revise = reviseBody(1, req("revise"), "approved-op-2");
        Map<String, Object> revised = bindings.revise(bindingId, revise);

        assertEquals(2, ((Number) revised.get("currentRev")).intValue());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> revisions = (List<Map<String, Object>>) revised.get("revisions");
        assertEquals(2, revisions.size());
        assertEquals("approved-op", revisions.get(0).get("opCode"), "旧修订必须保持原样");
        assertEquals("approved-op-2", revisions.get(1).get("opCode"));
        assertEquals(first(created).get("declarationSha256"), revisions.get(0).get("declarationSha256"));

        PlatformException stale = assertThrows(PlatformException.class,
                () -> bindings.revise(bindingId, reviseBody(1, req("stale"), "approved-op-3")));
        assertEquals(409, stale.status());
        assertEquals("CONFIG_REV_CONFLICT", stale.code());
    }

    @Test
    void unknownFieldAndMalformedReferenceAreRejected() {
        String app = app("reject");
        String bindingId = "reject-" + RUN;
        Map<String, Object> unknown = body(app, bindingId, req("unknown"), "op");
        unknown.put("extra", "x");
        PlatformException field = assertThrows(PlatformException.class, () -> bindings.create(unknown));
        assertEquals(400, field.status());
        assertEquals("SEMANTIC_FIELD_OUT_OF_RANGE", field.code());

        Map<String, Object> malformed = body(app, bindingId, req("malformed"), "op");
        @SuppressWarnings("unchecked")
        Map<String, Object> release = (Map<String, Object>) malformed.get("releaseRef");
        release.put("releaseId", "not-a-uuid");
        PlatformException ref = assertThrows(PlatformException.class, () -> bindings.create(malformed));
        assertEquals(400, ref.status());
        assertEquals("SEMANTIC_FIELD_OUT_OF_RANGE", ref.code());
    }

    @Test
    void disableKeepsHistoryAndBlocksFurtherRevisions() {
        String app = app("disable");
        String bindingId = "disable-" + RUN;
        bindings.create(body(app, bindingId, req("create"), "approved-op"));

        Map<String, Object> disabled = bindings.disable(bindingId,
                Map.of("expectedRev", 1, "requestId", req("disable")));
        assertEquals("DISABLED", disabled.get("status"));

        PlatformException blocked = assertThrows(PlatformException.class,
                () -> bindings.revise(bindingId, reviseBody(1, req("blocked"), "op-2")));
        assertEquals(400, blocked.status());
        assertEquals("CONFIG_STATE_TRANSITION_INVALID", blocked.code());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> revisions = (List<Map<String, Object>>) disabled.get("revisions");
        assertEquals(1, revisions.size(), "停用不删除历史修订");
    }

    @Test
    void unknownAppIsNotFound() {
        PlatformException missing = assertThrows(PlatformException.class,
                () -> bindings.create(body("missing-" + RUN, "missing-" + RUN, req("missing"), "op")));
        assertEquals(404, missing.status());
        assertEquals("CONFIG_REF_NOT_FOUND", missing.code());
    }

    private static Map<String, Object> body(String app, String bindingId, String requestId, String opCode) {
        Map<String, Object> release = new LinkedHashMap<>();
        release.put("spaceCode", "space-a");
        release.put("releaseId", UUID.nameUUIDFromBytes(("release-" + bindingId).getBytes()).toString());
        release.put("version", "v3");
        release.put("contentDigest", digest("bundle"));

        Map<String, Object> skill = new LinkedHashMap<>();
        skill.put("skillCode", "orders.query");
        skill.put("skillVersion", 7);
        skill.put("skillSha256", digest("skill"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bindingId", bindingId);
        body.put("appCode", app);
        body.put("requestId", requestId);
        body.put("releaseRef", release);
        body.put("extensionId", "ext-" + bindingId);
        body.put("extensionDigest", digest("extension-" + opCode));
        body.put("opCode", opCode);
        body.put("skill", skill);
        body.put("profiles", List.of("read-only@1.0"));
        return body;
    }

    private static Map<String, Object> reviseBody(int expectedRev, String requestId, String opCode) {
        Map<String, Object> release = new LinkedHashMap<>();
        release.put("spaceCode", "space-a");
        release.put("releaseId", UUID.nameUUIDFromBytes(("release-revise-" + opCode).getBytes()).toString());
        release.put("version", "v4");
        release.put("contentDigest", digest("bundle-" + opCode));

        Map<String, Object> skill = new LinkedHashMap<>();
        skill.put("skillCode", "orders.query");
        skill.put("skillVersion", 8);
        skill.put("skillSha256", digest("skill-" + opCode));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expectedRev", expectedRev);
        body.put("requestId", requestId);
        body.put("releaseRef", release);
        body.put("extensionDigest", digest("extension-" + opCode));
        body.put("opCode", opCode);
        body.put("skill", skill);
        body.put("profiles", List.of("read-only@1.0"));
        return body;
    }

    private static Map<String, Object> first(Map<String, Object> view) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> revisions = (List<Map<String, Object>>) view.get("revisions");
        return revisions.get(0);
    }

    private static String app(String tag) {
        String app = "sem-" + tag + "-" + RUN;
        db.update("""
                insert into app.application(tenant_id, code, name, type, status, current_version_no, created_by)
                values (?, ?, ?, 'CHAT', 'DRAFT', 0, ?) on conflict(tenant_id, code) do nothing
                """, TENANT, app, "semantic " + tag, "admin-" + RUN);
        return app;
    }

    private static String req(String tag) {
        return "req-" + RUN + "-" + tag + "-" + SEQ.incrementAndGet();
    }

    private static String digest(String seed) {
        return Digest.sha256(seed);
    }

    private static void authenticate(String tenant) {
        var jwt = Jwt.withTokenValue("verified-semantic-admin").header("alg", "HS256")
                .subject("u-" + RUN).claim("tenant_id", tenant).claim("roles", List.of("ADMIN"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private static void migrate(DriverManagerDataSource ds) throws Exception {
        try {
            Flyway.configure().dataSource(ds).schemas("app").defaultSchema("app")
                    .locations("classpath:db/migration").load().migrate();
        } catch (FlywayException e) {
            try (var connection = ds.getConnection(); var statement = connection.createStatement()) {
                for (String table : List.of("semantic_binding_revision", "semantic_binding")) {
                    statement.executeUpdate("drop table if exists app." + table + " cascade");
                }
            }
            Flyway.configure().dataSource(ds).schemas("app").defaultSchema("app")
                    .locations("classpath:db/migration").load().migrate();
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
