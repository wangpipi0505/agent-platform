package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.api.RunController;
import cn.agentplatform.engine.application.RunService;
import cn.agentplatform.engine.application.RunScheduler;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 真实 PG run/tool_call 与 MVC 六维检索；冻结来源为夹具，不调用当前目录、模型或技能。 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class RunObservationDimensionsTest {
    static JdbcTemplate db;
    static TraceStore store;
    RunService runs;
    MockMvc mvc;
    String tenant;
    final String owner = "observation-owner";
    final String release = "33333333-3333-4333-8333-333333333333";
    final String space = "44444444-4444-4444-8444-444444444444";

    @BeforeAll static void database() {
        var ds = new DriverManagerDataSource(EngineTestDatabase.url(), EngineTestDatabase.user(), EngineTestDatabase.password());
        org.flywaydb.core.Flyway.configure().dataSource(ds).schemas("engine").defaultSchema("engine")
                .locations("classpath:db/migration").load().migrate();
        db = new JdbcTemplate(ds); store = new TraceStore(db, new DataSourceTransactionManager(ds));
    }
    @BeforeEach void setup() {
        tenant = "observe-" + UUID.randomUUID(); actor(tenant, owner);
        // 只隔离调度线程；查询、持久事实和 HTTP Controller 均使用真实实现。
        runs = new RunService(store, null, null, null, mock(RunScheduler.class));
        mvc = MockMvcBuilders.standaloneSetup(new RunController(runs, store, null, null)).setControllerAdvice(new PlatformErrors()).build();
    }
    @AfterEach void clean() {
        for (String scope : List.of(tenant, tenant + "-other")) {
            db.update("delete from engine.tool_call where run_id in(select id from engine.run where tenant_id=?)", scope);
            db.update("delete from engine.attempt where node_instance_id in(select n.id from engine.node_instance n join engine.run r on r.id=n.run_id where r.tenant_id=?)", scope);
            db.update("delete from engine.node_instance where run_id in(select id from engine.run where tenant_id=?)", scope);
            db.update("delete from engine.run where tenant_id=?", scope);
        }
        MDC.remove("traceId"); SecurityContextHolder.clearContext();
    }
    static void actor(String tenant, String principal) {
        var jwt = Jwt.withTokenValue("verified-observation").header("alg", "HS256").subject(principal)
                .claim("tenant_id", tenant).claim("roles", List.of("VIEWER"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(120)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
    Map<String, Object> source(String pkg, String route, String tool, String skill, long version) {
        return Map.of("packageId", pkg, "version", "1.0.0", "routeProfileId", route, "softwareId", "fixture-software",
                "bindings", List.of(Map.of("routeProfileId", route, "toolId", tool, "skillCode", skill, "skillVersionId", version, "selection", "ENABLED")));
    }
    long run(String scope, String principal, Object refs, Object snapshot) {
        return db.queryForObject("insert into engine.run(public_id,app_code,app_version_id,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs)"
                + " values(?,'observed_app',1,'TASK','SUCCEEDED',?,?,'pg:fixture',?::jsonb,'{}','{}',?::jsonb,?::jsonb) returning id", Long.class,
                "r_" + UUID.randomUUID().toString().replace("-", ""), principal, scope, Json.write(refs), Json.write(snapshot),
                Json.write(Map.of("packageId", "forged-input-package", "traceId", "forged-input-trace")));
    }
    void tool(long run, String skill, long version, int seq) {
        long node = db.queryForObject("insert into engine.node_instance(run_id,step_seq,node_def_id,node_type,status,definition) values(?,?,?,'SKILL','SUCCEEDED','{}') returning id", Long.class, run, seq, "node_" + seq);
        long attempt = db.queryForObject("insert into engine.attempt(node_instance_id,attempt_no,status,idempotency_key,request_snapshot_ref,request_snapshot) values(?,1,'SUCCEEDED',?,'fixture','{}') returning id", Long.class, node, Digest.sha256("attempt-" + node));
        db.update("insert into engine.tool_call(run_id,node_instance_id,call_seq,skill_version_id,args_digest,idempotency_key,status,skill_code,args,attempt_id) values(?,?,1,?,?,?,'SUCCEEDED',?,'{}',?)",
                run, node, version, "a".repeat(64), Digest.sha256("call-" + node), skill, attempt);
    }
    Map<String, Object> page(Map<String, String> filters, int status) throws Exception {
        var request = get("/admin/runs/page"); filters.forEach(request::param);
        var response = mvc.perform(request).andReturn().getResponse();
        assertEquals(status, response.getStatus(), "OBSERVATION_HTTP_STATUS");
        var envelope = Json.read(response.getContentAsString(StandardCharsets.UTF_8), Map.class);
        return (Map<String, Object>) envelope.get("data");
    }
    static List<Map<String, Object>> items(Map<String, Object> page) { return (List<Map<String, Object>>) page.get("items"); }
    static void count(Map<String, Object> page, long expected) {
        assertEquals(expected, ((Number) page.get("total")).longValue()); assertEquals(expected, items(page).size());
    }

    @Test void sixDimensionsMatchOneFrozenSourceAndAnActualExactToolCall() throws Exception {
        var a = source("package-a", "route-a", "tool-a", "skill_a", 101);
        var b = source("package-b", "route-b", "tool-b", "skill_b", 102);
        long id = run(tenant, owner, Map.of("capabilityPackages", List.of(a, b)), Map.of("contextPack", Map.of("space", space, "releaseId", release)));
        tool(id, "skill_a", 101, 1); tool(id, "skill_b", 102, 2);
        var selected = new LinkedHashMap<>(Map.of("appCode", "observed_app", "packageId", "package-a", "routeProfileId", "route-a", "toolId", "tool-a", "releaseId", release));
        count(page(selected, 200), 1);
        selected.put("routeProfileId", "route-b"); count(page(selected, 200), 0);
        selected.put("routeProfileId", "route-a"); selected.put("toolId", "tool-b"); count(page(selected, 200), 0);
        selected.put("toolId", "tool-a"); selected.put("releaseId", UUID.randomUUID().toString()); count(page(selected, 200), 0);
        db.update("delete from engine.tool_call where run_id=? and skill_code='skill_a'", id);
        selected.put("releaseId", release); count(page(selected, 200), 0);
    }
    @Test void actorTenantAndPrincipalRemainMandatoryAndInputsCannotInventDimensions() throws Exception {
        var refs = Map.of("capabilityPackages", List.of(source("private-package", "route-a", "tool-a", "skill_a", 101)));
        run(tenant, owner, refs, Map.of()); run(tenant + "-other", owner, refs, Map.of()); run(tenant, "another-owner", refs, Map.of());
        count(page(Map.of("packageId", "private-package"), 200), 1);
        page(Map.of("tenantId", tenant + "-other"), 400);
        count(page(Map.of("packageId", "forged-input-package"), 200), 0);
        count(page(Map.of("packageId", "private-package' OR true--"), 200), 0);
        page(Map.of("routeProfileId", "x".repeat(129)), 400);
    }
    @Test void historicalMissingValuesStayNullAndOnlyNewAdmissionsCaptureTheirCurrentTrace() throws Exception {
        long legacy = run(tenant, owner, Map.of(), Map.of("config", Map.of("capabilityPackages", List.of(source("not-frozen", "not-frozen", "tool-a", "skill_a", 101)))));
        var row = items(page(Map.of(), 200)).getFirst();
        assertTrue(row.get("observation") instanceof Map, "DTO 必须明确区分未记录和已冻结字段");
        var observed = (Map<String, Object>) row.get("observation");
        assertTrue(observed.containsKey("traceId")); assertNull(observed.get("traceId"));
        assertNull(observed.get("releaseId")); assertNull(observed.get("capabilityPackages"));
        count(page(Map.of("packageId", "not-frozen"), 200), 0);
        var method = RunService.class.getDeclaredMethod("versionRefs", JsonNode.class, JsonNode.class, JsonNode.class);
        method.setAccessible(true); MDC.put("traceId", "persisted-current-trace");
        var captured = (Map<String, Object>) method.invoke(runs, Json.tree(Map.of("definition", Map.of())), Json.tree(Map.of("versionNo", 1, "config", Map.of())), Json.object());
        MDC.remove("traceId"); assertEquals("persisted-current-trace", captured.get("traceId"));
        run(tenant, owner, captured, Map.of());
        count(page(Map.of("traceId", "persisted-current-trace"), 200), 1);
        var newer = (Map<String, Object>) items(page(Map.of(), 200)).getFirst().get("observation");
        assertEquals("persisted-current-trace", newer.get("traceId"));
        assertEquals("{}", db.queryForObject("select version_refs::text from engine.run where id=?", String.class, legacy));
    }
}
