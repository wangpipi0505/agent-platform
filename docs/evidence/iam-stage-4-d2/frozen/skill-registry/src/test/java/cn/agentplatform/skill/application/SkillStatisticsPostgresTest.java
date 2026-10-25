package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.api.SkillController;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.*;

/** Dedicated database; these fixtures never touch the running development schema. */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class SkillStatisticsPostgresTest {
    static JdbcTemplate db;
    static DataSourceTransactionManager manager;
    String tenant, code;
    long version;
    MockMvc mvc;

    @BeforeAll
    static void database() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:postgresql://127.0.0.1:25432/agent_platform_statistics_test",
                        "agent_v12",
                        System.getenv("AGENT_DB_PASSWORD"));
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
        tenant = "stats_" + UUID.randomUUID();
        code = "stats_skill";
        actor("owner", List.of("ADMIN"));
        db.update("insert into skill.skill(tenant_id,code,name) values(?,?,?)", tenant, code, code);
        version =
                db.queryForObject(
                        "insert into"
                            + " skill.skill_version(tenant_id,skill_code,version_no,contract,sha256)"
                            + " values(?,?,1,'{}',?) returning id",
                        Long.class,
                        tenant,
                        code,
                        "a".repeat(64));
        var execution = new SkillExecution(db, manager, null, null, null, "http://unused");
        mvc =
                MockMvcBuilders.standaloneSetup(new SkillController(null, execution, null))
                        .setControllerAdvice(new PlatformErrors())
                        .build();
    }

    void actor(String principal, List<String> roles) {
        var jwt =
                Jwt.withTokenValue("verified")
                        .header("alg", "HS256")
                        .subject(principal)
                        .claim("tenant_id", tenant)
                        .claim("roles", roles)
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(60))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    void fact(
            long id,
            String principal,
            String kind,
            boolean mock,
            String status,
            long ms,
            String day) {
        db.update(
                "insert into"
                    + " skill.execution_fact(tenant_id,call_id,skill_version_id,principal_id,run_id,attempt_id,execution_owner,args_digest,request_snapshot,status,result,duration_ms,is_mock,call_kind,skill_contract_sha256,created_at)"
                    + " values(?,?,?,?,?,1,'worker',?,'{}',?,?::jsonb,?,?,?,?,?::timestamptz)",
                tenant,
                id,
                version,
                principal,
                "r_" + id,
                "a".repeat(64),
                status,
                Json.write(Map.of("error", Map.of("semantic", "RETRYABLE_FAILURE"))),
                ms,
                mock,
                kind,
                "a".repeat(64),
                day);
    }

    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder query() {
        return get("/admin/skills/" + code + "/stats")
                .param("from", "2026-10-01T00:00:00Z")
                .param("to", "2026-10-03T00:00:00Z")
                .param("groupBy", "day");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void frozenContractSeparatesModesMockAndErrorSamples() throws Exception {
        fact(1, "owner", "FORMAL", false, "SUCCEEDED", 100, "2026-10-01T01:00:00Z");
        fact(2, "owner", "FORMAL", false, "FAILED", 300, "2026-10-01T02:00:00Z");
        fact(3, "owner", "DEBUG", true, "SUCCEEDED", 2, "2026-10-01T03:00:00Z");
        fact(4, "owner", "EVALUATION", false, "SUCCEEDED", 50, "2026-10-02T01:00:00Z");
        fact(5, "owner", "FORMAL", false, "SUCCEEDED", 900, "2026-10-03T00:00:00Z");
        mvc.perform(query())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total.calls").value(4))
                .andExpect(jsonPath("$.data.total.formal").value(2))
                .andExpect(jsonPath("$.data.total.debug").value(1))
                .andExpect(jsonPath("$.data.total.evaluation").value(1))
                .andExpect(jsonPath("$.data.mock.calls").value(1))
                .andExpect(jsonPath("$.data.byDay[0].successRate").value(0.5))
                .andExpect(jsonPath("$.data.failures[0].sampleRunIds[0]").value("r_2"));
    }

    @Test
    void unknownReplacementRecalculatesWithoutAddingLogicalCallOrWaitDuration() throws Exception {
        fact(1, "owner", "FORMAL", false, "UNKNOWN", 120, "2026-10-01T01:00:00Z");
        mvc.perform(query()).andExpect(jsonPath("$.data.byStatus.unknown").value(1));
        db.update(
                "update skill.execution_fact set status='SUCCEEDED',updated_at=now() where"
                        + " tenant_id=? and call_id=1",
                tenant);
        mvc.perform(query())
                .andExpect(jsonPath("$.data.total.calls").value(1))
                .andExpect(jsonPath("$.data.byStatus.unknown").value(0))
                .andExpect(jsonPath("$.data.byDay[0].p95").value(120));
    }

    @Test
    void ordinaryUserSeesOnlyOwnedFactsAndFiltersVersion() throws Exception {
        fact(1, "owner", "FORMAL", false, "FAILED", 100, "2026-10-01T01:00:00Z");
        fact(2, "other", "FORMAL", false, "FAILED", 100, "2026-10-01T01:00:00Z");
        actor("owner", List.of("EDITOR"));
        mvc.perform(query().param("version", "1"))
                .andExpect(jsonPath("$.data.total.calls").value(1));
        mvc.perform(query().param("version", "2"))
                .andExpect(jsonPath("$.data.total.calls").value(0));
    }

    @Test
    void rejectsUnboundedOrUnsupportedGrouping() throws Exception {
        mvc.perform(query().param("groupBy", "day;drop table x"))
                .andExpect(status().isBadRequest());
        mvc.perform(query().param("from", "2020-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sampleLinksUseAdministratorDrilldownAndOwnerRuntimeDetail() throws Exception {
        fact(1, "owner", "FORMAL", false, "FAILED", 10, "2026-10-01T01:00:00Z");
        mvc.perform(query())
                .andExpect(
                        jsonPath("$.data.failures[0].sampleLinks[0].href")
                                .value("/admin/statistics/runs/r_1"));
        actor("owner", List.of("EDITOR"));
        mvc.perform(query())
                .andExpect(
                        jsonPath("$.data.failures[0].sampleLinks[0].href")
                                .value("/admin/runs/r_1"));
    }

    @Test
    void tenantAggregateInternalEndpointRequiresAdministrator() throws Exception {
        actor("owner", List.of("SERVICE", "EDITOR"));
        mvc.perform(get("/internal/statistics")).andExpect(status().isForbidden());
    }

    @Test
    void internalStatisticsRequiresBoundedSkillQueryRatherThanLegacyTenantDump() throws Exception {
        mvc.perform(get("/internal/statistics")).andExpect(status().isBadRequest());
        mvc.perform(get("/internal/statistics").param("code", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total.calls").value(0));
    }
}
