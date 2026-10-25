package cn.agentplatform.engine.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.api.RunController;
import cn.agentplatform.engine.infra.TraceStore;

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

@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class RunStatisticsPostgresTest {
    static JdbcTemplate db;
    static DataSourceTransactionManager manager;
    String tenant;
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
                .schemas("engine")
                .defaultSchema("engine")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        db = new JdbcTemplate(ds);
        manager = new DataSourceTransactionManager(ds);
    }

    @BeforeEach
    void fixture() {
        tenant = "statistics_" + UUID.randomUUID();
        actor(List.of("ADMIN", "SERVICE"));
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new RunController(null, new TraceStore(db, manager), null, null))
                        .setControllerAdvice(new PlatformErrors())
                        .build();
    }

    void actor(List<String> roles) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new JwtAuthenticationToken(
                                Jwt.withTokenValue("verified")
                                        .header("alg", "HS256")
                                        .subject("admin")
                                        .claim("tenant_id", tenant)
                                        .claim("roles", roles)
                                        .issuedAt(Instant.now())
                                        .expiresAt(Instant.now().plusSeconds(60))
                                        .build()));
    }

    long run(String id, String app, String biz, String trigger, String status, String day) {
        return db.queryForObject(
                "insert into"
                    + " engine.run(public_id,app_code,app_version_id,debug_snapshot,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs,started_at,ended_at)"
                    + " values(?,?,?,?::jsonb,?,?,'owner',?,'input','{}',?::jsonb,'{}','{}','{}',?::timestamptz,?::timestamptz+interval"
                    + " '200 milliseconds') returning id",
                Long.class,
                id,
                app,
                trigger.equals("DEBUG") ? null : 1L,
                trigger.equals("DEBUG") ? "{}" : null,
                trigger,
                status,
                tenant,
                Json.write(Map.of("attributes", Map.of("bizSystemCode", List.of(biz)))),
                day,
                day);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void filtersAuthoritativeDimensionsAndCountsConfirmationsWithoutMultiplyingRuns()
            throws Exception {
        long id =
                run(
                        "r_" + UUID.randomUUID(),
                        "app-a",
                        "biz-a",
                        "TASK",
                        "SUCCEEDED",
                        "2026-10-01T00:00:00Z");
        db.update(
                "insert into engine.run_event(run_id,seq,type,payload)"
                    + " values(?,1,'APPROVAL_DONE','{}'),(?,2,'APPROVAL_DONE','{}')",
                id,
                id);
        run("r_" + UUID.randomUUID(), "app-a", "biz-b", "TASK", "FAILED", "2026-10-01T00:00:00Z");
        run(
                "r_" + UUID.randomUUID(),
                "app-b",
                "biz-a",
                "DEBUG",
                "SUCCEEDED",
                "2026-10-01T00:00:00Z");
        mvc.perform(
                        get("/internal/statistics")
                                .param("from", "2026-10-01T00:00:00Z")
                                .param("to", "2026-10-02T00:00:00Z")
                                .param("appCode", "app-a")
                                .param("bizSystemCode", "biz-a")
                                .param("callKind", "FORMAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups[0].runs").value(1))
                .andExpect(jsonPath("$.data.groups[0].confirmationCount").value(2))
                .andExpect(jsonPath("$.data.groups[0].successRate").value(1.0))
                .andExpect(jsonPath("$.data.runRefs.length()").value(1));
    }

    @Test
    void requiresAdministratorEvenForServiceActor() throws Exception {
        actor(List.of("EDITOR", "SERVICE"));
        mvc.perform(get("/internal/statistics")).andExpect(status().isForbidden());
    }

    @Test
    void administratorDrilldownUsesTenantBoundaryWithoutRequiringRunOwnership() throws Exception {
        String id = "r_" + UUID.randomUUID();
        run(id, "app-a", "biz-a", "TASK", "FAILED", "2026-10-01T00:00:00Z");
        mvc.perform(get("/internal/statistics/runs/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value(id))
                .andExpect(jsonPath("$.data.inputs").doesNotExist());
        String original = tenant;
        tenant = "foreign";
        actor(List.of("ADMIN", "SERVICE"));
        mvc.perform(get("/internal/statistics/runs/" + id)).andExpect(status().isNotFound());
        tenant = original;
        actor(List.of("EDITOR", "SERVICE"));
        mvc.perform(get("/internal/statistics/runs/" + id)).andExpect(status().isForbidden());
    }

    @Test
    void tooManyRunsAreRejectedRatherThanSilentlyTruncated() throws Exception {
        String prefix = "r_" + UUID.randomUUID();
        db.update(
                "insert into"
                    + " engine.run(public_id,app_code,app_version_id,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs,started_at)"
                    + " select"
                    + " ?||g,'bulk',1,'TASK','SUCCEEDED','owner',?,'input','{}','{}','{}','{}','{}','2026-10-01T00:00:00Z'::timestamptz"
                    + " from generate_series(1,10001) g",
                prefix,
                tenant);
        mvc.perform(
                        get("/internal/statistics")
                                .param("from", "2026-10-01T00:00:00Z")
                                .param("to", "2026-10-02T00:00:00Z"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void actualSkillSegmentsAndMockStayIndependentWhenUnknownChangesTerminal() throws Exception {
        long rid =
                run(
                        "r_" + UUID.randomUUID(),
                        "app-a",
                        "biz-a",
                        "TASK",
                        "SUCCEEDED",
                        "2026-10-01T00:00:00Z");
        long nid =
                db.queryForObject(
                        "insert into"
                            + " engine.node_instance(run_id,step_seq,node_type,status,definition)"
                            + " values(?,1,'SKILL','SUCCEEDED','{}') returning id",
                        Long.class,
                        rid);
        long aid =
                db.queryForObject(
                        "insert into"
                            + " engine.attempt(node_instance_id,attempt_no,status,idempotency_key,request_snapshot_ref,request_snapshot)"
                            + " values(?,1,'SUCCEEDED',?,'ref','{}') returning id",
                        Long.class,
                        nid,
                        "a".repeat(64));
        long call =
                db.queryForObject(
                        "insert into"
                            + " engine.tool_call(run_id,node_instance_id,call_seq,skill_version_id,args_digest,idempotency_key,status,duration_ms,skill_code,args,attempt_id,is_mock)"
                            + " values(?,?,1,1,?,?,'UNKNOWN',120,'s','{}',?,false) returning id",
                        Long.class,
                        rid,
                        nid,
                        "a".repeat(64),
                        UUID.randomUUID().toString().replace("-", "") + "a".repeat(32),
                        aid);
        db.update(
                "insert into"
                    + " engine.tool_call(run_id,node_instance_id,call_seq,skill_version_id,args_digest,idempotency_key,status,duration_ms,skill_code,args,attempt_id,is_mock)"
                    + " values(?,?,2,1,?,?,'SUCCEEDED',1,'s','{}',?,true)",
                rid,
                nid,
                "a".repeat(64),
                UUID.randomUUID().toString().replace("-", "") + "b".repeat(32),
                aid);
        var query =
                get("/internal/statistics")
                        .param("from", "2026-10-01T00:00:00Z")
                        .param("to", "2026-10-02T00:00:00Z");
        mvc.perform(query)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skillGroups[0].calls").value(2))
                .andExpect(jsonPath("$.data.skillGroups[0].unknown").value(1))
                .andExpect(jsonPath("$.data.skillGroups[0].mockCalls").value(1))
                .andExpect(jsonPath("$.data.skillGroups[0].p95DurationMs").value(120));
        db.update("update engine.tool_call set status='SUCCEEDED' where id=?", call);
        mvc.perform(query)
                .andExpect(jsonPath("$.data.skillGroups[0].calls").value(2))
                .andExpect(jsonPath("$.data.skillGroups[0].unknown").value(0))
                .andExpect(jsonPath("$.data.skillGroups[0].successRate").value(1.0));
    }
}
