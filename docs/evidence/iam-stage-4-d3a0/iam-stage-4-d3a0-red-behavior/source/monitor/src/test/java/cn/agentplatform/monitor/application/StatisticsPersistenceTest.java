package cn.agentplatform.monitor.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.agentplatform.common.*;
import cn.agentplatform.monitor.api.StatisticsController;

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
class StatisticsPersistenceTest {
    static JdbcTemplate db;
    static DataSourceTransactionManager manager;
    StatisticsHttpFixture upstream;
    MockMvc mvc;
    String tenant;

    @BeforeAll
    static void database() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:postgresql://127.0.0.1:25432/agent_platform_statistics_test",
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
        manager = new DataSourceTransactionManager(ds);
    }

    @BeforeEach
    void fixture() throws Exception {
        tenant = "statistics_" + UUID.randomUUID();
        actor(List.of("ADMIN"));
        upstream = new StatisticsHttpFixture();
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new StatisticsController(
                                        new StatisticsService(
                                                db, manager, upstream.url(), upstream.url())))
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

    @AfterEach
    void close() throws Exception {
        SecurityContextHolder.clearContext();
        upstream.close();
    }

    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder query() {
        return get("/admin/statistics")
                .param("from", "2026-10-01T00:00:00Z")
                .param("to", "2026-10-02T00:00:00Z")
                .param("appCode", "app-a")
                .param("bizSystemCode", "biz-a");
    }

    String rebuild(String id) {
        return Json.write(
                Map.of(
                        "requestId",
                        id,
                        "from",
                        "2026-10-01T00:00:00Z",
                        "to",
                        "2026-10-02T00:00:00Z",
                        "appCode",
                        "app-a",
                        "bizSystemCode",
                        "biz-a"));
    }

    @Test
    void combinesOnlyServerRunRefsAndPreservesMissingUsage() throws Exception {
        mvc.perform(query().param("tenantId", "forged").param("principalId", "forged"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups[0].model.knownTokens").value(21))
                .andExpect(jsonPath("$.data.groups[0].model.totalTokens").doesNotExist())
                .andExpect(jsonPath("$.data.groups[0].model.usageMissingCalls").value(1))
                .andExpect(jsonPath("$.data.consistency").value("PER_SOURCE_SNAPSHOT"));
        assertEquals(List.of("r_one"), upstream.modelRequest.get("runIds"));
        assertFalse(upstream.engineTarget.contains("forged"));
        assertTrue(upstream.engineTarget.contains("bizSystemCode=biz-a"));
    }

    @Test
    void adminOnlyAndPaginationLimitsRejectBeforeCallingSources() throws Exception {
        actor(List.of("EDITOR"));
        mvc.perform(query()).andExpect(status().isForbidden());
        assertEquals(0, upstream.engineRequests);
        actor(List.of("ADMIN"));
        mvc.perform(query().param("pageSize", "101")).andExpect(status().isBadRequest());
        assertEquals(0, upstream.engineRequests);
    }

    @Test
    void repeatedRebuildIsIdempotentAndSwitchesSnapshotOnlyAfterAllSourcesSucceed()
            throws Exception {
        String first =
                mvc.perform(
                                post("/admin/statistics/rebuild")
                                        .contentType("application/json")
                                        .content(rebuild("once")))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.snapshotId").isString())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String id = Json.read(first).path("data").path("snapshotId").asText();
        mvc.perform(
                        post("/admin/statistics/rebuild")
                                .contentType("application/json")
                                .content(rebuild("once")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.snapshotId").value(id));
        assertEquals(
                1,
                db.queryForObject(
                        "select count(*) from monitor.statistics_snapshot where tenant_id=?",
                        Integer.class,
                        tenant));
        upstream.failModel = true;
        mvc.perform(
                        post("/admin/statistics/rebuild")
                                .contentType("application/json")
                                .content(rebuild("new")))
                .andExpect(status().isBadGateway());
        assertEquals(
                id,
                db.queryForObject(
                        "select snapshot_id from monitor.statistics_active where tenant_id=?",
                        String.class,
                        tenant));
        mvc.perform(get("/admin/statistics/snapshots/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups[0].model.knownTokens").value(21));
        tenant = "other-tenant";
        actor(List.of("ADMIN"));
        mvc.perform(get("/admin/statistics/snapshots/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void rebuildRejectsClientTotalsAndReusedRequestWithDifferentFilters() throws Exception {
        var forged = Json.read(rebuild("bad"), Map.class);
        forged.put("totalTokens", 99999);
        mvc.perform(
                        post("/admin/statistics/rebuild")
                                .contentType("application/json")
                                .content(Json.write(forged)))
                .andExpect(status().isBadRequest());
        assertEquals(0, upstream.engineRequests);
        mvc.perform(
                        post("/admin/statistics/rebuild")
                                .contentType("application/json")
                                .content(rebuild("bound")))
                .andExpect(status().isOk());
        var changed = Json.read(rebuild("bound"), Map.class);
        changed.put("appCode", "app-b");
        mvc.perform(
                        post("/admin/statistics/rebuild")
                                .contentType("application/json")
                                .content(Json.write(changed)))
                .andExpect(status().isConflict());
    }
}
