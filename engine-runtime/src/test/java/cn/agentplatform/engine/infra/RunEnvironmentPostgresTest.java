package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;

import java.util.*;

/**
 * PLAT-20 T20.1（run 环境标注半段）真库验收：走真实 createInternal（EVALUATION 冻结候选路径），
 * 断言标注随冻结快照落库、运行详情可见、RUN_STARTED 事件载荷可见；旧记录（无标注）读取不报错。
 * environment 取值依赖进程 AGENT_DEMO_MODE，这里只断言 DEMO/REAL 二值与 demoMode 自洽；
 * datasetVersion 透传是确定性的（v1 / 缺失不落键）。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class RunEnvironmentPostgresTest {
    static JdbcTemplate db;
    static TraceStore store;
    static RunService runs;
    static final Actor actor = new Actor("engine-env-test", "alice", Set.of("EDITOR"), Map.of());

    @BeforeAll
    static void connect() throws Exception {
        String url = EngineTestDatabase.url();
        var ds =
                new DriverManagerDataSource(
                        url, EngineTestDatabase.user(), EngineTestDatabase.password());
        db = new JdbcTemplate(ds);
        store = new TraceStore(db, new DataSourceTransactionManager(ds));
        org.flywaydb.core.Flyway.configure()
                .dataSource(ds)
                .schemas("engine")
                .defaultSchema("engine")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        var clients = new RuntimeClients();
        var a = new AgentDriver(store, clients, new ContextAssembler());
        runs = SchedulerTestResources.runs(store, clients, new FlowDriver(store, a), a);
    }

    @AfterAll
    static void release() {
        SchedulerTestResources.close();
    }

    @BeforeEach
    void clean() {
        SchedulerTestResources.close();
        db.update("delete from engine.run_event where run_id in(select id from engine.run where"
                + " tenant_id='engine-env-test')");
        db.update("delete from engine.tool_call where run_id in(select id from engine.run where"
                + " tenant_id='engine-env-test')");
        db.update("delete from engine.attempt where node_instance_id in(select n.id from"
                + " engine.node_instance n join engine.run r on r.id=n.run_id where"
                + " r.tenant_id='engine-env-test')");
        db.update("delete from engine.node_instance where run_id in(select id from engine.run where"
                + " tenant_id='engine-env-test')");
        db.update("delete from engine.run where tenant_id='engine-env-test'");
        var clients = new RuntimeClients();
        var a = new AgentDriver(store, clients, new ContextAssembler());
        runs = SchedulerTestResources.runs(store, clients, new FlowDriver(store, a), a);
    }

    /** 与 monitor → /internal/evaluations/run 的真实载荷同形（含 datasetVersion 与冻结候选）。 */
    com.fasterxml.jackson.databind.node.ObjectNode evaluation(
            String caseId, boolean withDataset) {
        var graph =
                Json.read(
                        "{\"definitionVersion\":\"1.0\",\"trigger\":\"TASK\",\"entry\":\"start\",\"inputs\":[],\"outputs\":[{\"name\":\"x\",\"from\":\"${input.x}\"}],\"nodes\":[{\"id\":\"start\",\"type\":\"START\"},{\"id\":\"end\",\"type\":\"END\"}],\"edges\":[{\"from\":\"start\",\"to\":\"end\"}],\"joins\":[],\"executionRules\":{\"parallel\":true,\"maxParallel\":8,\"cyclesForbidden\":true}}");
        var content = new LinkedHashMap<String, Object>();
        content.put("appCode", "eval-app");
        content.put("type", "FLOW");
        content.put("name", "eval");
        content.put("description", "");
        content.put("domain", "");
        content.put("bizCategory", "");
        content.put("tags", List.of());
        content.put("config", Map.of());
        content.put("definition", graph);
        String hash = Digest.sha256(Json.canonical(content));
        var snapshot = (com.fasterxml.jackson.databind.node.ObjectNode) Json.tree(content);
        snapshot.put("draftRev", 1);
        snapshot.put("candidateSha256", hash);
        var req = Json.object();
        req.put("appCode", "eval-app");
        req.put("draftRev", 1);
        req.put("candidateSha256", hash);
        req.put("evalReportId", "report-env");
        req.put("caseId", caseId);
        if (withDataset) req.put("datasetVersion", "v1");
        req.set("inputs", Json.tree(Map.of("x", 1)));
        req.set("frozenCandidate", snapshot);
        return req;
    }

    private Map<String, Object> awaitRun(Map<String, Object> accepted, String expected)
            throws Exception {
        String id = accepted.get("runId").toString();
        Map<String, Object> snapshot = accepted;
        for (int i = 0; i < 200 && !snapshot.get("status").equals(expected); i++) {
            runs.recover();
            Thread.sleep(10);
            snapshot = runs.status(id, actor);
        }
        return snapshot;
    }

    private static void assertEnvConsistent(Map<String, Object> detail, JsonNode runtime) {
        String environment = (String) detail.get("environment");
        assertTrue(
                Set.of("DEMO", "REAL").contains(environment),
                "environment 只能是 DEMO/REAL，实际=" + environment);
        assertEquals(
                Boolean.parseBoolean(runtime.path("demoMode").asText()),
                detail.get("demoMode"),
                "详情 demoMode 必须与快照一致");
    }

    @Test
    void evaluationRunCarriesAnnotationDatasetAndSurface() throws Exception {
        var req = evaluation("case-env-1", true);
        var detail = awaitRun(runs.create("eval-app", req, "EVALUATION", actor), "SUCCEEDED");
        assertEquals("SUCCEEDED", detail.get("status"));

        // 详情顶层可见（演示运行借此区分；dataset 引用透传）。
        var runtime = TraceStore.json(
                db.queryForObject(
                        "select snapshot->'runtime' from engine.run where public_id=?",
                        String.class,
                        detail.get("runId")));
        assertEnvConsistent(detail, runtime);
        assertEquals("v1", detail.get("datasetVersion"), "datasetVersion 必须原样透传");
        assertEquals("v1", runtime.path("datasetVersion").asText());

        // RUN_STARTED 载荷（SSE 数据源）携带同一 environment。
        var started =
                db.queryForList(
                        "select payload::text from engine.run_event where run_id=(select id from"
                                + " engine.run where public_id=?) and type='RUN_STARTED'",
                        String.class,
                        detail.get("runId"));
        assertEquals(1, started.size(), "RUN_STARTED 必须恰好一条");
        var payload = Json.read(started.getFirst());
        assertTrue(
                Set.of("DEMO", "REAL").contains(payload.path("environment").asText()),
                "RUN_STARTED 载荷必须带 environment");
        assertEquals("v1", payload.path("datasetVersion").asText());
    }

    @Test
    void runWithoutDatasetReferenceOmitsDatasetKey() throws Exception {
        var req = evaluation("case-env-2", false);
        var detail = awaitRun(runs.create("eval-app", req, "EVALUATION", actor), "SUCCEEDED");
        assertFalse(
                detail.containsKey("datasetVersion"),
                "入参未带 dataset 引用时详情不得伪造该键");
        int withDataset =
                db.queryForObject(
                        "select count(*) from engine.run where tenant_id='engine-env-test' and"
                                + " snapshot->'runtime' ? 'datasetVersion'",
                        Integer.class);
        assertEquals(0, withDataset, "快照 runtime 也不得落 datasetVersion 键");
        var environment =
                db.queryForObject(
                        "select snapshot->'runtime'->>'environment' from engine.run where"
                                + " public_id=?",
                        String.class,
                        detail.get("runId"));
        assertTrue(Set.of("DEMO", "REAL").contains(environment));
    }

    /** 旧 run（无标注）详情读取不报错、不补键。 */
    @Test
    void legacyRunWithoutAnnotationReadsCleanly() {
        long id =
                db.queryForObject(
                        "insert into"
                            + " engine.run(public_id,app_code,debug_snapshot,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs,mode)"
                            + " values(?,'legacy-app','{}','DEBUG','RUNNING','alice','engine-env-test','pg:legacy','{}','{}','{}','{}','{}','FULL')"
                            + " returning id",
                        Long.class,
                        "r_env_" + UUID.randomUUID());
        String publicId =
                db.queryForObject("select public_id from engine.run where id=?", String.class, id);
        var detail = runs.status(publicId, actor);
        assertFalse(detail.containsKey("environment"), "旧记录不得补写 environment");
        assertFalse(detail.containsKey("demoMode"));
        assertFalse(detail.containsKey("datasetVersion"));
    }
}
