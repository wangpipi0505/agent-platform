package cn.agentplatform.model.application;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.agentplatform.common.*;
import cn.agentplatform.model.api.ModelController;
import cn.agentplatform.model.infra.ModelStore;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.*;

@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class ModelStatisticsPostgresTest {
    static JdbcTemplate db;
    String tenant;
    long provider, entry;
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
                .schemas("model")
                .defaultSchema("model")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        db = new JdbcTemplate(ds);
    }

    @BeforeEach
    void fixture() {
        tenant = "statistics_" + UUID.randomUUID();
        actor(List.of("ADMIN", "SERVICE"));
        provider =
                db.queryForObject(
                        "insert into"
                            + " model.provider(tenant_id,code,name,base_url,trust_labels,created_by)"
                            + " values(?,'p','p','http://fixture','[]','owner') returning id",
                        Long.class,
                        tenant);
        entry =
                db.queryForObject(
                        "insert into"
                            + " model.entry(tenant_id,provider_id,model_name,display_name,context_window,max_output_tokens,created_by)"
                            + " values(?,?,'m','m',100,50,'owner') returning id",
                        Long.class,
                        tenant,
                        provider);
        mvc =
                MockMvcBuilders.standaloneSetup(new ModelController(new ModelStore(db), null))
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

    void call(String run, String status, Integer tokens) {
        db.update(
                "insert into"
                    + " model.call_log(tenant_id,principal_id,run_id,invocation_ref,model_entry_id,provider_id,actual_endpoint,request_snapshot,estimated_tokens,status,total_tokens,duration_ms)"
                    + " values(?,'owner',?,?,?,?,'http://fixture','{}',100,?,?,25)",
                tenant,
                run,
                UUID.randomUUID().toString(),
                entry,
                provider,
                status,
                tokens);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void confirmedNoSendDoesNotPolluteKnownUsage() throws Exception {
        call("r_one", "SUCCEEDED", 12);
        call("r_one", "FAILED", null);
        reservation("unsent", "NO_SEND", tenant);
        db.update(
                "update model.call_log set quota_receipt=?::jsonb where tenant_id=? and"
                        + " run_id='r_one' and status='FAILED'",
                Json.write(Map.of("id", "unsent")),
                tenant);
        mvc.perform(
                        post("/internal/statistics/query")
                                .contentType("application/json")
                                .content("{\"runIds\":[\"r_one\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.byRun[0].calls").value(1))
                .andExpect(jsonPath("$.data.byRun[0].failed").value(0))
                .andExpect(jsonPath("$.data.byRun[0].usageMissingCalls").value(0))
                .andExpect(jsonPath("$.data.byRun[0].totalTokens").value(12));
    }

    void reservation(String id, String status, String ownerTenant) {
        db.update(
                "insert into model.quota_reservation(tenant_id,reservation_id,principal_id,"
                        + "invocation_ref,model_entry_id,provider_id,app_code,quota_receipt,status)"
                        + " values(?,?,'owner','same-invocation',?,?,'one','{}',?)",
                ownerTenant,
                id,
                entry,
                provider,
                status);
    }

    @Test
    void noSendEvidenceRequiresSameTenantAndReceiptIdentity() throws Exception {
        call("r_one", "SUCCEEDED", 12);
        db.update(
                "update model.call_log set quota_receipt='{\"id\":\"shared\"}',"
                        + "invocation_ref='same-invocation' where tenant_id=?",
                tenant);
        reservation("shared", "NO_SEND", "other_" + UUID.randomUUID());
        reservation("other-receipt", "NO_SEND", tenant);
        mvc.perform(
                        post("/internal/statistics/query")
                                .contentType("application/json")
                                .content("{\"runIds\":[\"r_one\",\"r_one\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.byRun[0].calls").value(1))
                .andExpect(jsonPath("$.data.byRun[0].totalTokens").value(12));
    }

    @Test
    void sentUnknownAndHistoricalOrOrphanReceiptsRemainConservative() throws Exception {
        call("r_sent", "FAILED", null);
        db.update(
                "update model.call_log set quota_receipt='{\"id\":\"sent\"}' where tenant_id=?",
                tenant);
        reservation("sent", "SENT", tenant);
        call("r_legacy", "FAILED", null);
        call("r_orphan", "FAILED", null);
        db.update(
                "update model.call_log set quota_receipt='{\"id\":\"orphan\"}' where tenant_id=?"
                    + " and run_id='r_orphan'",
                tenant);
        mvc.perform(
                        post("/internal/statistics/query")
                                .contentType("application/json")
                                .content("{\"runIds\":[\"r_sent\",\"r_legacy\",\"r_orphan\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.byRun.length()").value(3))
                .andExpect(
                        jsonPath("$.data.byRun[*].calls")
                                .value(
                                        org.hamcrest.Matchers.everyItem(
                                                org.hamcrest.Matchers.is(1))))
                .andExpect(
                        jsonPath("$.data.byRun[*].usageMissingCalls")
                                .value(
                                        org.hamcrest.Matchers.everyItem(
                                                org.hamcrest.Matchers.is(1))));
        org.assertj.core.api.Assertions.assertThat(
                        new ModelStore(db)
                                .statistics(
                                        new Actor(
                                                tenant,
                                                "admin",
                                                Set.of("ADMIN", "SERVICE"),
                                                Map.of())))
                .containsEntry("calls", 3L)
                .containsEntry("tokens", null)
                .containsEntry("usage_missing", 3L);
    }

    @Test
    void missingUsageRemainsUnknownAndDuplicateRunRefsDoNotMultiplyTokens() throws Exception {
        call("r_one", "SUCCEEDED", 15);
        call("r_one", "SUCCEEDED", null);
        call("r_other", "SUCCEEDED", 999);
        mvc.perform(
                        post("/internal/statistics/query")
                                .contentType("application/json")
                                .content(Json.write(Map.of("runIds", List.of("r_one", "r_one")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.byRun.length()").value(1))
                .andExpect(jsonPath("$.data.byRun[0].calls").value(2))
                .andExpect(jsonPath("$.data.byRun[0].knownTokens").value(15))
                .andExpect(jsonPath("$.data.byRun[0].totalTokens").doesNotExist())
                .andExpect(jsonPath("$.data.byRun[0].usageMissingCalls").value(1));
    }

    @Test
    void rejectsOrdinaryActorAndOversizedRunSet() throws Exception {
        actor(List.of("EDITOR", "SERVICE"));
        mvc.perform(
                        post("/internal/statistics/query")
                                .contentType("application/json")
                                .content("{\"runIds\":[\"r_one\"]}"))
                .andExpect(status().isForbidden());
        actor(List.of("ADMIN", "SERVICE"));
        mvc.perform(
                        post("/internal/statistics/query")
                                .contentType("application/json")
                                .content(
                                        Json.write(
                                                Map.of(
                                                        "runIds",
                                                        Collections.nCopies(10001, "r_one")))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sameRunIdFromAnotherTenantCannotContributeUsage() throws Exception {
        call("r_one", "SUCCEEDED", 15);
        String original = tenant;
        tenant = "other_" + UUID.randomUUID();
        actor(List.of("ADMIN", "SERVICE"));
        mvc.perform(
                        post("/internal/statistics/query")
                                .contentType("application/json")
                                .content("{\"runIds\":[\"r_one\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.byRun.length()").value(0));
        tenant = original;
    }
}
