package cn.agentplatform.monitor.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.*;

/**
 * 评测证据的两种绑定：整份候选 sha256（精确绑定）与语义摘要（只描述字段变化时的受控复用）。
 *
 * <p>不变量：
 *
 * <ul>
 *   <li>sha 匹配优先，返回 {@code matchedBy=SHA256}，行为与引入摘要前完全一致；
 *   <li>sha 不匹配但语义摘要匹配时，返回 {@code matchedBy=SEMANTIC_DIGEST}，调用方必须显式记录这条
 *       证据是复用的，不得当成"新候选已通过评测"；
 *   <li>两者都不匹配返回 {@code accepted=false}，不得回落成任何旧报告。
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class EvaluationSemanticDigestTest {
    static JdbcTemplate db;
    static EvaluationService service;
    String code;
    static final String SHA_A = "a".repeat(64);
    static final String SHA_B = "b".repeat(64);
    static final String DIGEST_OLD = "1".repeat(64);
    static final String DIGEST_NEW = "2".repeat(64);

    @BeforeAll
    static void prepare() {
        System.setProperty("agent.jwt.secret", "TEST-ONLY-monitor-digest-1234567890123456789012");
        SecurityConfiguration.bindDeployment(
                new org.springframework.mock.env.MockEnvironment()
                        .withProperty("agent.security.mode", "dev-hmac")
                        .withProperty("agent.security.service-id", "monitor")
                        .withProperty("agent.dev-auth", "true")
                        .withProperty("server.address", "127.0.0.1"));
        var ds =
                new DriverManagerDataSource(
                        "jdbc:postgresql://127.0.0.1:25432/agent_platform_v12",
                        "agent_v12",
                        System.getenv("AGENT_DB_PASSWORD"));
        Flyway.configure()
                .dataSource(ds)
                .schemas("monitor")
                .defaultSchema("monitor")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        db = new JdbcTemplate(ds);
        var tx = new DataSourceTransactionManager(ds);
        service = new EvaluationService(db, tx, "http://127.0.0.1:1", "http://127.0.0.1:1", true);
    }

    @BeforeEach
    void actor() {
        var jwt =
                Jwt.withTokenValue("digest-verified")
                        .header("alg", "HS256")
                        .subject("evaluator")
                        .claim("tenant_id", "monitor-tests")
                        .claim("roles", List.of("EDITOR"))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(120))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        code = "digest_" + UUID.randomUUID().toString().replace("-", "");
        // 用例固定了报告 id（er_rev 等），清理必须按租户整体做：只按本次随机 app_code 删，
        // 上一轮留下的同 id 行会在下一轮触发 evaluation_report_pkey 冲突。
        db.update("delete from monitor.evaluation_report where tenant_id=?", "monitor-tests");
        db.update("delete from monitor.dataset where tenant_id=?", "monitor-tests");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void report(String id, String sha, String digest, String status) {
        // evaluation_report 对 (tenant_id,app_code,dataset_version) 有外键；证据绑定测试关心的是报告本身，
        // 数据集内容用空用例与占位摘要即可（表只要求 cases 非空 jsonb 与 sha256 非空）。
        db.update(
                "insert into monitor.dataset(tenant_id,app_code,dataset_version,cases,sha256)"
                        + " values(?,?,?,'[]'::jsonb,?) on conflict do nothing",
                "monitor-tests",
                code,
                "ds-1",
                "0".repeat(64));
        db.update(
                "insert into monitor.evaluation_report(tenant_id,id,app_code,draft_rev,"
                        + "candidate_sha256,candidate_semantic_digest,dataset_version,candidate,"
                        + "status,threshold,results,created_by,completed_at)"
                        + " values(?,?,?,?,?,?,?,'{}'::jsonb,?,?,?::jsonb,?,now())",
                "monitor-tests",
                id,
                code,
                3,
                sha,
                digest,
                "ds-1",
                status,
                0.9,
                "[]",
                "evaluator");
    }

    @Test
    void exactShaMatchWinsOverSemanticDigest() {
        report("er_exact", SHA_A, DIGEST_OLD, "ACCEPTED");
        var v = service.accepted(code, 3, SHA_A, DIGEST_OLD);
        assertEquals(true, v.get("accepted"));
        assertEquals("SHA256", v.get("matchedBy"));
        assertEquals("er_exact", v.get("id"));
    }

    @Test
    void semanticDigestReusesEvidenceWhenOnlyDescriptionChanged() {
        report("er_desc", SHA_A, DIGEST_OLD, "ACCEPTED");
        // 新候选 sha 不同（描述变了），语义摘要相同。
        var v = service.accepted(code, 3, SHA_B, DIGEST_OLD);
        assertEquals(true, v.get("accepted"));
        assertEquals("SEMANTIC_DIGEST", v.get("matchedBy"));
        assertEquals("er_desc", v.get("id"));
    }

    @Test
    void semanticMismatchDoesNotFallBackToOldReport() {
        report("er_mismatch", SHA_A, DIGEST_OLD, "ACCEPTED");
        var v = service.accepted(code, 3, SHA_B, DIGEST_NEW);
        assertEquals(false, v.get("accepted"));
        assertFalse(v.containsKey("matchedBy"));
    }

    @Test
    void rejectedReportIsNotEvidenceEvenWhenDigestMatches() {
        report("er_rejected", SHA_A, DIGEST_OLD, "REJECTED");
        var v = service.accepted(code, 3, SHA_B, DIGEST_OLD);
        assertEquals(false, v.get("accepted"));
    }

    @Test
    void differentDraftRevisionNeverMatches() {
        report("er_rev", SHA_A, DIGEST_OLD, "ACCEPTED");
        var v = service.accepted(code, 4, SHA_B, DIGEST_OLD);
        assertEquals(false, v.get("accepted"));
    }

    @Test
    void digestIsOptionalForCallersThatDoNotKnowIt() {
        report("er_legacy", SHA_A, null, "ACCEPTED");
        assertEquals("SHA256", service.accepted(code, 3, SHA_A, null).get("matchedBy"));
        assertEquals(false, service.accepted(code, 3, SHA_B, null).get("accepted"));
    }

    @Test
    void candidateDigestIsValidated() {
        assertNull(EvaluationService.candidateDigest(Map.of("appCode", "x")));
        assertEquals(DIGEST_OLD, EvaluationService.candidateDigest(Map.of("semanticDigest", DIGEST_OLD)));
        var bad =
                assertThrows(
                        PlatformException.class,
                        () -> EvaluationService.candidateDigest(Map.of("semanticDigest", "not-a-digest")));
        assertEquals(400, bad.status());
    }
}
