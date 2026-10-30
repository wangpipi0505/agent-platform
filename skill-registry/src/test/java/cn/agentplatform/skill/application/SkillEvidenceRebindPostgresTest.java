package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import cn.agentplatform.common.PlatformErrors;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.skill.api.SkillEvidenceController;
import cn.agentplatform.skill.infra.SkillClients;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * T10 受控证据重绑定的真实库验证。
 *
 * <p>核心不变量有三条：① 重绑定只“复制”证据行，历史行一字不改（发布版本不可变的触发器仍在）；
 * ② 只有 semantic_digest 相等才允许复制——只改 description 的草稿能复用旧证据，改 endpoint 的不能；
 * ③ 同一目标版本只能绑定一次（唯一约束 + 409），否则同一份报告会被无限复制、审计失去意义。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class SkillEvidenceRebindPostgresTest {
    /** 专用库：避免与共享 async 库的其他测试互相清理。 */
    private static final String DATABASE = "agent_platform_skill_rebind_test";
    private static final String REASON = "只调整了展示文案，端点与参数契约未变化";

    private static JdbcTemplate db;
    private static DataSourceTransactionManager manager;

    private String tenant;
    private String code;
    private SkillCatalog catalog;

    @BeforeAll
    static void migrate() {
        String password = System.getenv("AGENT_DB_PASSWORD");
        var admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:postgresql://127.0.0.1:25432/postgres", "agent_v12", password));
        if (admin.queryForObject(
                        "select count(*) from pg_database where datname=?", Integer.class, DATABASE)
                == 0) admin.execute("create database " + DATABASE);
        var ds =
                new DriverManagerDataSource(
                        "jdbc:postgresql://127.0.0.1:25432/" + DATABASE, "agent_v12", password);
        Flyway.configure()
                .dataSource(ds)
                .schemas("skill")
                .defaultSchema("skill")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        db = new JdbcTemplate(ds);
        manager = new DataSourceTransactionManager(ds);
    }

    @BeforeEach
    void fixture() {
        tenant = "rebind_" + UUID.randomUUID();
        code = "rebind_skill";
        identity(tenant, List.of("EDITOR", "ADMIN", "SERVICE"));
        catalog = new SkillCatalog(db, manager, mock(SkillClients.class), "http://unused");
        catalog.create(draft(code));
    }

    @Test
    void rebindsEvidenceForDescriptionOnlyEditAndKeepsHistory() {
        publishWithEvidence(1);
        long versionOne = versionId(1);
        var historyConnection =
                history("skill.connection_test", versionOne);
        var historyAcceptance = history("skill.acceptance", versionOne);

        var edited = new LinkedHashMap<>(SkillCatalog.object(catalog.version(code, 1).get("definition")));
        edited.put("description", "只改了展示文案，放行动作不变");
        assertEquals(2, ((Number) catalog.save(code, edited).get("versionNo")).intValue());
        var target = catalog.version(code, 2);
        long versionTwo = versionId(2);

        var result =
                catalog.rebindEvidence(
                        code, 2, Map.of("fromVersion", 1, "reason", REASON));

        assertEquals(true, result.get("rebound"));
        assertEquals(1, result.get("fromVersion"));
        assertEquals(target.get("semanticDigest"), result.get("semanticDigest"));
        assertEquals(true, result.get("connectionTest"));
        assertEquals(true, result.get("acceptance"));

        // 历史行（含 report 原文）一字未改：触发器 protected_published_contract 仍然是最后防线。
        assertEquals(historyConnection, history("skill.connection_test", versionOne));
        assertEquals(historyAcceptance, history("skill.acceptance", versionOne));

        var copiedConnection =
                db.queryForMap(
                        "select candidate_sha256,report,semantic_digest from skill.connection_test where tenant_id=? and skill_version_id=?",
                        tenant,
                        versionTwo);
        assertEquals(target.get("sha256"), copiedConnection.get("candidate_sha256"));
        assertEquals(target.get("semanticDigest"), copiedConnection.get("semantic_digest"));
        assertEquals(
                String.valueOf(1),
                db.queryForObject(
                        "select report->'reboundFrom'->>'version' from skill.connection_test where tenant_id=? and skill_version_id=?",
                        String.class,
                        tenant,
                        versionTwo));
        assertEquals(
                1,
                db.queryForObject(
                        "select count(*) from skill.acceptance where tenant_id=? and skill_version_id=? and report->'reboundFrom'->>'version'='1'",
                        Integer.class,
                        tenant,
                        versionTwo));

        var audit =
                db.queryForMap(
                        "select from_version_id,to_version_id,semantic_digest,operator,reason from skill.evidence_rebind where tenant_id=?",
                        tenant);
        assertEquals(versionOne, ((Number) audit.get("from_version_id")).longValue());
        assertEquals(versionTwo, ((Number) audit.get("to_version_id")).longValue());
        assertEquals(target.get("semanticDigest"), audit.get("semantic_digest"));
        assertEquals(REASON, audit.get("reason"));

        // 绑定后的目标草稿用复制来的证据通过发布门禁。
        assertEquals("PUBLISHED", catalog.publish(code, 2).get("status"));
    }

    @Test
    void semanticChangeRequiresRetest() {
        publishWithEvidence(1);

        var edited = new LinkedHashMap<>(SkillCatalog.object(catalog.version(code, 1).get("definition")));
        edited.put("endpoint", "https://example.com/inventory-v2");
        catalog.save(code, edited);

        var failure =
                assertThrows(
                        PlatformException.class,
                        () -> catalog.rebindEvidence(code, 2, Map.of("fromVersion", 1, "reason", REASON)));

        assertEquals(409, failure.status());
        assertEquals("SEMANTIC_CHANGE_REQUIRES_RETEST", failure.code());
        assertTrue(
                failure.getMessage().contains("下一步：对该版本重跑连接测试与选型验收"),
                failure.getMessage());
        assertEquals(
                0,
                db.queryForObject(
                        "select count(*) from skill.evidence_rebind where tenant_id=?",
                        Integer.class,
                        tenant));
    }

    @Test
    void secondRebindIsRejected() {
        publishWithEvidence(1);
        var edited = new LinkedHashMap<>(SkillCatalog.object(catalog.version(code, 1).get("definition")));
        edited.put("description", "第二次绑定的展示文案");
        catalog.save(code, edited);
        catalog.rebindEvidence(code, 2, Map.of("fromVersion", 1, "reason", REASON));

        var failure =
                assertThrows(
                        PlatformException.class,
                        () -> catalog.rebindEvidence(code, 2, Map.of("fromVersion", 1, "reason", REASON + "；本次改用不同绑定声明")));

        assertEquals(409, failure.status());
        assertEquals("EVIDENCE_ALREADY_BOUND", failure.code());
        assertTrue(failure.getMessage().contains("下一步"), failure.getMessage());
        assertEquals(
                1,
                db.queryForObject(
                        "select count(*) from skill.evidence_rebind where tenant_id=?",
                        Integer.class,
                        tenant));
    }

    @Test
    void concurrentSamePayloadReturnsOriginalReceiptWithoutDuplicatingOrMutatingHistory() throws Exception {
        publishWithEvidence(1);
        long source = versionId(1);
        var originalConnection = history("skill.connection_test", source);
        var originalAcceptance = history("skill.acceptance", source);
        var edited = new LinkedHashMap<>(SkillCatalog.object(catalog.version(code, 1).get("definition")));
        edited.put("description", "并发仅改展示文案"); catalog.save(code, edited);
        var ready = new CountDownLatch(2); var go = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Map<String,Object>> bind = () -> {
                identity(tenant, List.of("EDITOR", "ADMIN", "SERVICE")); ready.countDown();
                try {
                    if (!go.await(5, TimeUnit.SECONDS)) throw new AssertionError("并发起点未放行");
                    try { return catalog.rebindEvidence(code, 2, Map.of("fromVersion", 1, "reason", REASON)); }
                    catch (PlatformException failed) { return Map.of("code", failed.code(), "status", failed.status()); }
                } finally { SecurityContextHolder.clearContext(); }
            };
            var first = pool.submit(bind); var second = pool.submit(bind);
            assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown();
            var one = first.get(10, TimeUnit.SECONDS); var two = second.get(10, TimeUnit.SECONDS);
            assertEquals(true, one.get("rebound"), one.toString());
            assertEquals(true, two.get("rebound"), two.toString());
            assertEquals(one, two, "同请求返回首份回执，不重新生成证明");
            assertEquals(1, db.queryForObject("select count(*) from skill.evidence_rebind where tenant_id=?", Integer.class, tenant));
            assertEquals(1, db.queryForObject("select count(*) from skill.connection_test where tenant_id=? and skill_version_id=?", Integer.class, tenant, versionId(2)));
            assertEquals(1, db.queryForObject("select count(*) from skill.acceptance where tenant_id=? and skill_version_id=?", Integer.class, tenant, versionId(2)));
            assertEquals(originalConnection, history("skill.connection_test", source));
            assertEquals(originalAcceptance, history("skill.acceptance", source));
            catalog.publish(code, 2);
            assertEquals(one, catalog.rebindEvidence(code, 2, Map.of("fromVersion", 1, "reason", REASON)), "发布后的 ACK 重试只读原回执");
        } finally { go.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test
    void changedPayloadOrTargetBytesConflictWhileSamePayloadReplaysExactly() {
        publishWithEvidence(1);
        var edited = new LinkedHashMap<>(SkillCatalog.object(catalog.version(code, 1).get("definition")));
        edited.put("description", "第一份目标文案"); catalog.save(code, edited);
        var first = catalog.rebindEvidence(code, 2, Map.of("fromVersion", 1, "reason", REASON));
        assertEquals(first, assertDoesNotThrow(() -> catalog.rebindEvidence(code, 2, Map.of("fromVersion", 1, "reason", REASON))));
        var conflict = assertThrows(PlatformException.class, () -> catalog.rebindEvidence(code, 2,
                Map.of("fromVersion", 1, "reason", REASON + "；使用另一项理由")));
        assertEquals(409, conflict.status()); assertEquals("EVIDENCE_ALREADY_BOUND", conflict.code());
        assertEquals(400, assertThrows(PlatformException.class, () -> catalog.rebindEvidence(code, 2,
                Map.of("fromVersion", 1.5, "reason", REASON))).status());
        assertEquals(400, assertThrows(PlatformException.class, () -> catalog.rebindEvidence(code, 2,
                Map.of("fromVersion", 1, "reason", REASON, "requestSha256", "a".repeat(64)))).status());
        long target = versionId(2);
        var connection = history("skill.connection_test", target); var acceptance = history("skill.acceptance", target);
        edited.put("description", "后续又改展示文案"); catalog.save(code, edited);
        assertEquals("EVIDENCE_ALREADY_BOUND", assertThrows(PlatformException.class, () -> catalog.rebindEvidence(code, 2,
                Map.of("fromVersion", 1, "reason", REASON))).code());
        assertEquals(connection, history("skill.connection_test", target)); assertEquals(acceptance, history("skill.acceptance", target));
        assertEquals(1, db.queryForObject("select count(*) from skill.evidence_rebind where tenant_id=?", Integer.class, tenant));
    }

    @Test
    void sourceWithoutPassingEvidenceIsRejected() {
        publishWithEvidence(1);
        var edited = new LinkedHashMap<>(SkillCatalog.object(catalog.version(code, 1).get("definition")));
        edited.put("description", "来源版本证据已被清理的场景");
        catalog.save(code, edited);
        // 删除来源证据行模拟“来源版本只做过部分测试”；连接测试仍可复制、验收缺失时必须整体拒绝。
        db.update(
                "delete from skill.acceptance where tenant_id=? and skill_version_id=?",
                tenant,
                versionId(1));

        var failure =
                assertThrows(
                        PlatformException.class,
                        () -> catalog.rebindEvidence(code, 2, Map.of("fromVersion", 1, "reason", REASON)));

        assertEquals(409, failure.status());
        assertEquals("EVIDENCE_INCOMPLETE", failure.code());
        assertEquals(
                0,
                db.queryForObject(
                        "select count(*) from skill.connection_test where tenant_id=? and skill_version_id=?",
                        Integer.class,
                        tenant,
                        versionId(2)));
    }

    @Test
    void nonAdminIsRejectedBeforeAnyWrite() throws Exception {
        publishWithEvidence(1);
        var edited = new LinkedHashMap<>(SkillCatalog.object(catalog.version(code, 1).get("definition")));
        edited.put("description", "非管理员调用不能绑定证据");
        catalog.save(code, edited);

        identity(tenant, List.of("EDITOR"));
        MockMvc mvc =
                MockMvcBuilders.standaloneSetup(new SkillEvidenceController(catalog))
                        .setControllerAdvice(new PlatformErrors())
                        .build();
        mvc.perform(
                        post("/admin/skills/{code}/versions/{version}/rebind-evidence", code, 2)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"fromVersion\":1,\"reason\":\""
                                                + REASON
                                                + "\"}"))
                .andExpect(jsonPath("$.code").value("SYS-APP-4031"));

        assertEquals(
                0,
                db.queryForObject(
                        "select count(*) from skill.evidence_rebind where tenant_id=?",
                        Integer.class,
                        tenant));
    }

    private void publishWithEvidence(int version) {
        var candidate = catalog.version(code, version);
        evidence(candidate, (String) candidate.get("sha256"), (String) candidate.get("semanticDigest"));
        catalog.publish(code, version);
    }

    private void evidence(Map<String, Object> candidate, String sha, String digest) {
        long versionId = ((Number) candidate.get("skillVersionId")).longValue();
        db.update(
                "insert into"
                    + " skill.connection_test(tenant_id,skill_version_id,candidate_sha256,passed,report,semantic_digest)"
                    + " values(?,?,?,true,?::jsonb,?)",
                tenant,
                versionId,
                sha,
                "{\"ok\":true,\"candidateSha256\":\"" + sha + "\"}",
                digest);
        db.update(
                "insert into"
                    + " skill.acceptance(id,tenant_id,skill_version_id,candidate_sha256,passed,hit_rate,report,semantic_digest)"
                    + " values(?,?,?,?,true,0.9,?::jsonb,?)",
                "sa_" + UUID.randomUUID().toString().replace("-", ""),
                tenant,
                versionId,
                sha,
                "{\"hitRate\":0.9,\"candidateSha256\":\"" + sha + "\"}",
                digest);
    }

    private long versionId(int version) {
        return ((Number) catalog.version(code, version).get("skillVersionId")).longValue();
    }

    private List<Map<String, Object>> history(String table, long versionId) {
        return db.queryForList(
                "select id,candidate_sha256,passed,report::text as report,created_at::text as created_at"
                        + " from "
                        + table
                        + " where tenant_id=? and skill_version_id=? order by id",
                tenant,
                versionId);
    }

    static Map<String, Object> draft(String code) {
        var body = new LinkedHashMap<String, Object>();
        body.put("code", code);
        body.put("name", code);
        body.put("type", "HTTP");
        body.put("description", "查询仓库库存（真实库测试）");
        body.put("endpoint", "https://example.com/inventory");
        body.put("httpMethod", "POST");
        body.put("authScheme", "NONE");
        body.put("inputSchema", Map.of("type", "object"));
        body.put("outputSchema", Map.of("type", "object"));
        body.put(
                "execution",
                Map.of(
                        "readonly", true,
                        "sideEffect", false,
                        "idempotent", true,
                        "cancellable", false));
        body.put("guard", Map.of("attributes", Map.of("warehouse", List.of("A"))));
        body.put("timeoutMs", 30000);
        return body;
    }

    static void identity(String tenant, List<String> roles) {
        var jwt =
                Jwt.withTokenValue("t10-rebind-fixture")
                        .header("alg", "HS256")
                        .subject("t10-rebind")
                        .claim("tenant_id", tenant)
                        .claim("roles", roles)
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(120))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
