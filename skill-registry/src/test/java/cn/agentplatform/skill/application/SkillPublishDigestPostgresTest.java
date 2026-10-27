package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import cn.agentplatform.common.PlatformException;
import cn.agentplatform.skill.domain.SkillDigest;
import cn.agentplatform.skill.infra.SkillClients;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.*;

/**
 * T10 发布门禁的真实库验证：证据必须同时对上“字节”（sha256）与“语义”（semantic_digest）。
 *
 * <p>迁移用例单独从 V314 起飞：同一租户下先造一条“迁移前”的已通过证据，再跑 V315，验证回填把
 * 旧证据的 semantic_digest 置为当时的 contract sha256——历史证据在语义上等价于“字节相同”，
 * 这也是不误伤已发布技能的唯一安全解释。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class SkillPublishDigestPostgresTest {
    /** 专用库：迁移用例需要退回 V314 重放，不能污染共享的 async 库。 */
    private static final String DATABASE = "agent_platform_skill_digest_test";

    private static JdbcTemplate db;
    private static DataSourceTransactionManager manager;
    private static final String LEGACY_TENANT = "digest_legacy";
    private static final String LEGACY_SHA = "c".repeat(64);
    private static long legacyVersionId;

    private String tenant;
    private String code;
    private SkillCatalog catalog;

    @BeforeAll
    static void migrateThroughV315() {
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
        // 迁移用例要求“先 V314、后 V315”，因此每次都重置 schema 再按目标版本起飞。
        new JdbcTemplate(ds).execute("drop schema if exists skill cascade");
        Flyway.configure()
                .dataSource(ds)
                .schemas("skill")
                .defaultSchema("skill")
                .locations("classpath:db/migration")
                .target("314")
                .load()
                .migrate();

        db = new JdbcTemplate(ds);
        db.update(
                "insert into skill.skill(tenant_id,code,name) values(?,'legacy_digest','legacy_digest')",
                LEGACY_TENANT);
        legacyVersionId =
                db.queryForObject(
                        "insert into"
                            + " skill.skill_version(tenant_id,skill_code,version_no,contract,sha256)"
                            + " values(?,'legacy_digest',1,'{\"type\":\"HTTP\"}',?) returning id",
                        Long.class,
                        LEGACY_TENANT,
                        LEGACY_SHA);
        // 迁移前写入的通过证据：V315 必须把它们回填成当时的 contract sha256。
        db.update(
                "insert into"
                    + " skill.connection_test(tenant_id,skill_version_id,candidate_sha256,passed,report)"
                    + " values(?,?,?,true,'{}'::jsonb)",
                LEGACY_TENANT,
                legacyVersionId,
                LEGACY_SHA);
        db.update(
                "insert into"
                    + " skill.acceptance(id,tenant_id,skill_version_id,candidate_sha256,passed,hit_rate,report)"
                    + " values('sa_legacy_digest_probe',?,?,?,true,0.95,'{}'::jsonb)",
                LEGACY_TENANT,
                legacyVersionId,
                LEGACY_SHA);

        Flyway.configure()
                .dataSource(ds)
                .schemas("skill")
                .defaultSchema("skill")
                .locations("classpath:db/migration")
                .load()
                .migrate();

        manager = new DataSourceTransactionManager(ds);
    }

    @BeforeEach
    void fixture() {
        tenant = "digest_" + UUID.randomUUID();
        code = "digest_skill";
        identity(tenant, List.of("EDITOR", "ADMIN", "SERVICE"));
        catalog = new SkillCatalog(db, manager, mock(SkillClients.class), "http://unused");
        catalog.create(draft(code));
    }

    @Test
    void v315BackfillsLegacyEvidenceWithContractSha() {
        assertEquals(
                LEGACY_SHA,
                db.queryForObject(
                        "select semantic_digest from skill.skill_version where id=?",
                        String.class,
                        legacyVersionId));
        assertEquals(
                LEGACY_SHA,
                db.queryForObject(
                        "select semantic_digest from skill.connection_test where skill_version_id=?",
                        String.class,
                        legacyVersionId));
        assertEquals(
                LEGACY_SHA,
                db.queryForObject(
                        "select semantic_digest from skill.acceptance where skill_version_id=?",
                        String.class,
                        legacyVersionId));
        for (String table : List.of("skill_version", "connection_test", "acceptance")) {
            assertEquals(
                    "NO",
                    db.queryForObject(
                            "select is_nullable from information_schema.columns"
                                    + " where table_schema='skill' and table_name=? and column_name='semantic_digest'",
                            String.class,
                            table),
                    table);
        }
    }

    @Test
    void descriptionOnlyEditKeepsDigestButChangesSha() {
        var before = catalog.version(code, 1);
        var edited = new LinkedHashMap<>(SkillCatalog.object(before.get("definition")));
        edited.put("description", "只改展示文案，放行的动作没有变化");

        var after = catalog.save(code, edited);

        assertNotEquals(before.get("sha256"), after.get("sha256"));
        assertEquals(before.get("semanticDigest"), after.get("semanticDigest"));
        assertEquals(1, ((Number) after.get("versionNo")).intValue());
        assertEquals(SkillDigest.VERSION, ((Number) after.get("digestVersion")).intValue());
    }

    @Test
    void staleDigestEvidenceBlocksPublish() {
        var candidate = catalog.version(code, 1);
        // 人为制造“sha 对、语义不对”的证据：模拟旧代码/人工写入了一条与当前草稿不同语义的记录。
        evidence(candidate, (String) candidate.get("sha256"), "d".repeat(64));

        var failure = assertThrows(PlatformException.class, () -> catalog.publish(code, 1));

        assertEquals(409, failure.status());
        assertEquals("CONNECTION_TEST_REQUIRED", failure.code());
        assertTrue(failure.getMessage().contains("下一步：对 v1 重跑连接测试"), failure.getMessage());
    }

    @Test
    void passingEvidenceWithMatchingDigestAllowsPublish() {
        var candidate = catalog.version(code, 1);
        evidence(candidate, (String) candidate.get("sha256"), (String) candidate.get("semanticDigest"));

        var published = catalog.publish(code, 1);

        assertEquals("PUBLISHED", published.get("status"));
        assertEquals(candidate.get("semanticDigest"), published.get("semanticDigest"));
    }

    @Test
    void digestGateCanBeDisabledForRollback() {
        // 回滚开关只放宽 digest 比对，不删列、不改历史：等价于 T10 之前“只比 sha256”的行为。
        catalog =
                new SkillCatalog(
                        db, manager, mock(SkillClients.class), "http://unused", true, false);
        var candidate = catalog.version(code, 1);
        evidence(candidate, (String) candidate.get("sha256"), "d".repeat(64));

        var published = catalog.publish(code, 1);

        assertEquals("PUBLISHED", published.get("status"));
    }

    private void evidence(Map<String, Object> candidate, String sha, String digest) {
        long versionId = ((Number) candidate.get("skillVersionId")).longValue();
        db.update(
                "insert into"
                    + " skill.connection_test(tenant_id,skill_version_id,candidate_sha256,passed,report,semantic_digest)"
                    + " values(?,?,?,true,'{}'::jsonb,?)",
                tenant,
                versionId,
                sha,
                digest);
        db.update(
                "insert into"
                    + " skill.acceptance(id,tenant_id,skill_version_id,candidate_sha256,passed,hit_rate,report,semantic_digest)"
                    + " values(?,?,?,?,true,0.9,'{}'::jsonb,?)",
                "sa_" + UUID.randomUUID().toString().replace("-", ""),
                tenant,
                versionId,
                sha,
                digest);
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
                Jwt.withTokenValue("t10-digest-fixture")
                        .header("alg", "HS256")
                        .subject("t10-digest")
                        .claim("tenant_id", tenant)
                        .claim("roles", roles)
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(120))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
