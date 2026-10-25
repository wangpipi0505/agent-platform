package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;

import java.util.*;
import java.util.concurrent.*;

/** Only the independently provisioned PostgreSQL 25432 is accepted; no H2 fallback. */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class TracePostgresTest {
    static JdbcTemplate db;
    static TraceStore store;
    static RunService runs;
    static final Actor actor = new Actor("engine-test", "alice", Set.of("EDITOR"), Map.of());

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

    @BeforeEach
    void clean() {
        SchedulerTestResources.close();
        db.update("delete from engine.reproduction");
        db.update("delete from engine.model_invocation");
        db.update(
                "delete from engine.attempt_note where node_instance_id in(select n.id from"
                        + " engine.node_instance n join engine.run r on r.id=n.run_id where"
                        + " r.tenant_id='engine-test')");
        db.update(
                "delete from engine.run_event where run_id in(select id from engine.run where"
                        + " tenant_id='engine-test')");
        db.update(
                "delete from engine.tool_call where run_id in(select id from engine.run where"
                        + " tenant_id='engine-test')");
        db.update(
                "delete from engine.attempt where node_instance_id in(select n.id from"
                        + " engine.node_instance n join engine.run r on r.id=n.run_id where"
                        + " r.tenant_id='engine-test')");
        db.update(
                "delete from engine.node_instance where run_id in(select id from engine.run where"
                        + " tenant_id='engine-test')");
        db.update("delete from engine.run where tenant_id='engine-test'");
        var clients = new RuntimeClients();
        var agent = new AgentDriver(store, clients, new ContextAssembler());
        runs = SchedulerTestResources.runs(store, clients, new FlowDriver(store, agent), agent);
    }

    long run() {
        long id =
                db.queryForObject(
                        "insert into"
                            + " engine.run(public_id,app_code,debug_snapshot,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs,mode)"
                            + " values(?,'test','{}','DEBUG','RUNNING','alice','engine-test','pg:test','{}','{}','{}','{}','{}','STEP')"
                            + " returning id",
                        Long.class,
                        "r_test_" + UUID.randomUUID());
        db.update(
                "update engine.run set snapshot=?::jsonb where id=?",
                Json.write(
                        Map.of(
                                "config",
                                Map.of(
                                        "tools",
                                        List.of(
                                                Map.of(
                                                        "code",
                                                        "lookup",
                                                        "skillVersionId",
                                                        42,
                                                        "contractDigest",
                                                        Digest.sha256("fixture-contract")))))),
                id);
        return id;
    }

    long node(long r, String state) {
        return db.queryForObject(
                "insert into"
                    + " engine.node_instance(run_id,node_def_id,step_seq,node_type,status,definition)"
                    + " values(?,'n1',1,'CODE',?,'{\"type\":\"CODE\",\"expression\":\"1\"}')"
                    + " returning id",
                Long.class,
                r,
                state);
    }

    @Test
    void triggerVersionInvariantIsEnforced() {
        assertThrows(
                Exception.class,
                () ->
                        db.update(
                                "insert into"
                                    + " engine.run(public_id,app_code,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs)"
                                    + " values('bad','test','TASK','RUNNING','alice','engine-test','x','{}','{}','{}','{}','{}')"));
    }

    @Test
    void cancelledNodeRejectsLateSuccessWithoutSuccessEvent() {
        long r = run(), n = node(r, "READY");
        var claim = store.claim(n, "replicaA");
        db.update("update engine.node_instance set status='CANCELLED' where id=?", n);
        assertFalse(
                store.commit(
                        r,
                        n,
                        TraceStore.number(claim, "attemptId"),
                        claim.get("lease_owner").toString(),
                        Map.of("ok", true)));
        assertEquals(
                0,
                db.queryForObject(
                        "select count(*) from engine.run_event where run_id=? and"
                                + " type='LOGIC_RESULT_COMMITTED'",
                        Integer.class,
                        r));
        assertEquals(
                "CANCELLED",
                db.queryForObject(
                        "select status from engine.node_instance where id=?", String.class, n));
    }

    @Test
    void leaseTakeoverRejectsOldGeneration() {
        long r = run(), n = node(r, "READY");
        var old = store.claim(n, "A");
        db.update(
                "update engine.node_instance set lease_until=now()-interval '1 second' where id=?",
                n);
        var fresh = store.claim(n, "B");
        assertNotEquals(old.get("lease_owner"), fresh.get("lease_owner"));
        assertFalse(
                store.commit(
                        r,
                        n,
                        TraceStore.number(old, "attemptId"),
                        old.get("lease_owner").toString(),
                        Map.of("old", 1)));
        assertTrue(
                store.commit(
                        r,
                        n,
                        TraceStore.number(fresh, "attemptId"),
                        fresh.get("lease_owner").toString(),
                        Map.of("new", 1)));
        assertEquals(
                1,
                db.queryForObject(
                        "select count(*) from engine.run_event where run_id=? and"
                                + " type='LOGIC_RESULT_COMMITTED'",
                        Integer.class,
                        r));
    }

    @Test
    void concurrentCompletionHasOneWinner() throws Exception {
        long r = run(), n = node(r, "READY");
        var claim = store.claim(n, "A");
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++)
                results.add(
                        pool.submit(
                                () ->
                                        store.commit(
                                                r,
                                                n,
                                                TraceStore.number(claim, "attemptId"),
                                                claim.get("lease_owner").toString(),
                                                Map.of("ok", 1))));
            int winners = 0;
            for (var f : results) if (f.get()) winners++;
            assertEquals(1, winners);
        }
        assertEquals(
                1,
                db.queryForObject(
                        "select count(*) from engine.run_event where run_id=? and"
                                + " type='LOGIC_RESULT_COMMITTED'",
                        Integer.class,
                        r));
    }

    @Test
    void resumeIsDurableAndOldRequestCannotReleaseNewPause() {
        long r = run(), n = node(r, "WAITING_STEP");
        int pause = store.atomic(() -> store.event(r, "WAITING_STEP", Map.of("nodeInstanceId", n)));
        db.update("update engine.node_instance set pause_seq=? where id=?", pause, n);
        db.update("update engine.run set status='WAITING_STEP' where id=?", r);
        String id =
                db.queryForObject("select public_id from engine.run where id=?", String.class, r);
        var req =
                Json.tree(
                        Map.of(
                                "nodeInstanceId",
                                n,
                                "pauseSeq",
                                pause,
                                "requestId",
                                "resume-1",
                                "action",
                                "NEXT_NODE"));
        assertEquals("RUNNING", runs.resume(id, req, actor).get("status"));
        db.update(
                "update engine.node_instance set status='WAITING_STEP',pause_seq=? where id=?",
                pause + 2,
                n);
        assertEquals(true, runs.resume(id, req, actor).get("deduped"));
        assertEquals(
                "WAITING_STEP",
                db.queryForObject(
                        "select status from engine.node_instance where id=?", String.class, n));
        assertEquals(
                1,
                db.queryForObject(
                        "select count(*) from engine.run_event where run_id=? and type='RESUMED'",
                        Integer.class,
                        r));
    }

    @Test
    void eventSequenceRollsBackWithState() {
        long r = run();
        assertThrows(
                IllegalStateException.class,
                () ->
                        store.atomic(
                                () -> {
                                    store.event(r, "TEST", Map.of());
                                    throw new IllegalStateException("rollback");
                                }));
        assertEquals(
                0,
                db.queryForObject("select event_seq from engine.run where id=?", Integer.class, r));
        assertTrue(store.events(r, 0).isEmpty());
    }

    @Test
    void otherPrincipalCannotReadRun() {
        long r = run();
        String id =
                db.queryForObject("select public_id from engine.run where id=?", String.class, r);
        assertThrows(
                PlatformException.class,
                () ->
                        store.run(
                                id,
                                new Actor("engine-test", "mallory", Set.of("EDITOR"), Map.of())));
    }

    @Test
    void toolRegisterRejectsForeignTenantAndBadKey() {
        long r = run(), n = node(r, "READY");
        var claim = store.claim(n, "A");
        var calls = new ToolCalls(store);
        String id =
                db.queryForObject("select public_id from engine.run where id=?", String.class, r);
        var request =
                Json.tree(
                        Map.of(
                                "runId",
                                id,
                                "nodeInstanceId",
                                n,
                                "attemptId",
                                claim.get("attemptId"),
                                "callSeq",
                                1,
                                "skillVersionId",
                                42,
                                "skillCode",
                                "lookup",
                                "arguments",
                                Map.of("x", 1),
                                "idempotencyKey",
                                "wrong",
                                "leaseOwner",
                                claim.get("lease_owner")));
        assertThrows(PlatformException.class, () -> calls.register(request, actor));
    }

    JsonNodeHelper call(long r, long n, Map<String, Object> claim) {
        String id =
                db.queryForObject("select public_id from engine.run where id=?", String.class, r);
        var req =
                Json.tree(
                        Map.of(
                                "runId",
                                id,
                                "nodeInstanceId",
                                n,
                                "attemptId",
                                claim.get("attemptId"),
                                "callSeq",
                                1,
                                "skillVersionId",
                                42,
                                "skillCode",
                                "lookup",
                                "arguments",
                                Map.of("x", 1),
                                "idempotencyKey",
                                Digest.sha256(r + ":" + n + ":1:42"),
                                "leaseOwner",
                                claim.get("lease_owner")));
        return new JsonNodeHelper(
                req,
                Json.tree(
                        Map.of(
                                "owner",
                                claim.get("lease_owner"),
                                "attemptId",
                                claim.get("attemptId"))));
    }

    record JsonNodeHelper(
            com.fasterxml.jackson.databind.JsonNode register,
            com.fasterxml.jackson.databind.JsonNode claim) {}

    @Test
    void toolLifecycleIsStableAndFactPrecedesLogicalCommit() {
        long r = run(), n = node(r, "READY");
        var node = store.claim(n, "A");
        var calls = new ToolCalls(store);
        var req = call(r, n, node);
        var first = calls.register(req.register(), actor);
        long cid = TraceStore.number(first, "callId");
        assertEquals(cid, TraceStore.number(calls.register(req.register(), actor), "callId"));
        assertEquals(true, calls.claim(cid, req.claim(), actor).get("claimed"));
        assertEquals(false, calls.claim(cid, req.claim(), actor).get("claimed"));
        calls.submitted(cid, req.claim(), actor);
        var fact = (com.fasterxml.jackson.databind.node.ObjectNode) req.claim().deepCopy();
        fact.put("status", "SUCCEEDED");
        fact.set("result", Json.tree(Map.of("value", 9)));
        assertEquals(true, calls.fact(cid, fact, actor).get("accepted"));
        assertEquals(
                "RUNNING",
                db.queryForObject(
                        "select status from engine.node_instance where id=?", String.class, n));
        assertEquals(
                0,
                db.queryForObject(
                        "select count(*) from engine.run_event where run_id=? and"
                                + " type='LOGIC_RESULT_COMMITTED'",
                        Integer.class,
                        r));
        assertEquals(9, Json.tree(calls.get(cid, actor).get("result")).path("value").asInt());
        assertEquals(false, calls.claim(cid, req.claim(), actor).get("claimed"));
    }

    @Test
    void unknownSubmittedCallCannotBeBlindlyReplayed() {
        long r = run(), n = node(r, "READY");
        var a = store.claim(n, "A");
        var calls = new ToolCalls(store);
        var req = call(r, n, a);
        long cid = TraceStore.number(calls.register(req.register(), actor), "callId");
        calls.claim(cid, req.claim(), actor);
        calls.submitted(cid, req.claim(), actor);
        db.update(
                "update engine.node_instance set lease_until=now()-interval '1 second' where id=?",
                n);
        db.update(
                "update engine.tool_call set claimed_lease=now()-interval '1 second' where id=?",
                cid);
        var b = store.claim(n, "B");
        var result =
                calls.claim(
                        cid,
                        Json.tree(
                                Map.of(
                                        "owner",
                                        b.get("lease_owner"),
                                        "attemptId",
                                        b.get("attemptId"))),
                        actor);
        assertEquals(false, result.get("claimed"));
        assertEquals("UNKNOWN", result.get("status"));
        var late = (com.fasterxml.jackson.databind.node.ObjectNode) req.claim().deepCopy();
        late.put("status", "SUCCEEDED");
        late.set("result", Json.tree(Map.of("late", true)));
        assertEquals(true, calls.fact(cid, late, actor).get("late"));
        assertEquals("UNKNOWN", calls.get(cid, actor).get("status"));
        assertEquals(
                1,
                db.queryForObject(
                        "select count(*) from engine.attempt_note where node_instance_id=? and"
                                + " kind='TOOL_EXECUTION_FACT'",
                        Integer.class,
                        n));
    }

    @Test
    void callArgumentsCannotChangeOnReplay() {
        long r = run(), n = node(r, "READY");
        var a = store.claim(n, "A");
        var calls = new ToolCalls(store);
        var req = call(r, n, a);
        calls.register(req.register(), actor);
        var changed = (com.fasterxml.jackson.databind.node.ObjectNode) req.register().deepCopy();
        changed.set("arguments", Json.tree(Map.of("x", 2)));
        assertThrows(PlatformException.class, () -> calls.register(changed, actor));
    }

    com.fasterxml.jackson.databind.node.ObjectNode evaluation(String caseId) {
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
        req.put("evalReportId", "report1");
        req.put("caseId", caseId);
        req.put("datasetVersion", "v1");
        req.set("inputs", Json.tree(Map.of("x", 1)));
        req.set("frozenCandidate", snapshot);
        return req;
    }

    @Test
    void evaluationRunsFrozenCandidateAndReusesCaseAfterRestart() throws Exception {
        var req = evaluation("case1");
        var first = runs.create("eval-app", req, "EVALUATION", actor);
        first = awaitRun(first, "SUCCEEDED");
        assertEquals("SUCCEEDED", first.get("status"));
        assertEquals(1, Json.tree(first.get("output")).path("x").asInt());
        var again = runs.create("eval-app", req, "EVALUATION", actor);
        assertEquals(first.get("runId"), again.get("runId"));
        assertEquals(
                1,
                db.queryForObject(
                        "select count(*) from engine.run where tenant_id='engine-test'",
                        Integer.class));
    }

    @Test
    void changedFrozenCandidateCannotRun() {
        var req = evaluation("case2");
        ((com.fasterxml.jackson.databind.node.ObjectNode) req.path("frozenCandidate"))
                .put("name", "tampered");
        assertThrows(
                PlatformException.class, () -> runs.create("eval-app", req, "EVALUATION", actor));
    }

    @Test
    void persistedModelResponseIsReusedAfterLeaseTakeover() {
        long r = run(), n = node(r, "READY");
        var first = store.claim(n, "A");
        var run = store.db().queryForMap("select * from engine.run where id=?", r);
        var modelEntry = Json.read("{\"modelEntryId\":1}");
        run.put(
                "snapshot",
                Json.tree(
                        Map.of(
                                "dependencyVersions",
                                Map.of(
                                        "modelRefs",
                                        List.of(
                                                Map.of(
                                                        "modelEntryId",
                                                        1,
                                                        "entry",
                                                        modelEntry,
                                                        "modelBindingRef",
                                                        Map.of(
                                                                "bindingId",
                                                                1,
                                                                "sha256",
                                                                Digest.sha256(
                                                                        Json.canonical(
                                                                                modelEntry)))))))));
        var count = new java.util.concurrent.atomic.AtomicInteger();
        var clients =
                new RuntimeClients() {
                    @Override
                    public com.fasterxml.jackson.databind.JsonNode chatStream(
                            Object body, Actor actor, java.util.function.Consumer<String> chunk) {
                        return post("model", "/internal/model/chat/stream", body, actor);
                    }

                    @Override
                    public com.fasterxml.jackson.databind.JsonNode post(
                            String service, String path, Object body, Actor actor) {
                        if (path.equals("/internal/model/measure"))
                            return Json.read(
                                    "{\"inputTokens\":100,\"contextWindow\":10000,\"maxOutputTokens\":2048,\"tokenizer\":\"fixture\",\"exact\":false}");
                        count.incrementAndGet();
                        assertNotEquals(
                                "{}",
                                db.queryForObject(
                                        "select request_snapshot::text from engine.attempt where"
                                                + " id=?",
                                        String.class,
                                        first.get("attemptId")));
                        return Json.read(
                                "{\"text\":\"real-test-provider-response\",\"toolCalls\":[],\"usage\":{},\"actualModel\":\"fixture\"}");
                    }
                };
        var driver = new AgentDriver(store, clients, new ContextAssembler());
        var config = Json.read("{\"modelEntryId\":1}");
        var result =
                driver.drive(
                        run, first, config, Json.read("{\"message\":\"hello\"}"), actor, false);
        assertEquals("real-test-provider-response", result.path("text").asText());
        db.update(
                "update engine.node_instance set lease_until=now()-interval '1 second' where id=?",
                n);
        var second = store.claim(n, "B");
        var recovered =
                driver.drive(
                        run, second, config, Json.read("{\"message\":\"hello\"}"), actor, false);
        assertEquals(result, recovered);
        assertEquals(
                1,
                count.get(),
                "Persisted response must precede tool replay and eliminate duplicate model"
                        + " request");
    }

    @Test
    void persistedToolFactIsConsumedWithoutAnotherExternalDispatch() {
        long r = run(), n = node(r, "READY");
        var first = store.claim(n, "A");
        var calls = new ToolCalls(store);
        var req = call(r, n, first);
        long cid = TraceStore.number(calls.register(req.register(), actor), "callId");
        calls.claim(cid, req.claim(), actor);
        calls.submitted(cid, req.claim(), actor);
        var fact = (com.fasterxml.jackson.databind.node.ObjectNode) req.claim().deepCopy();
        fact.put("status", "SUCCEEDED");
        fact.set("result", Json.tree(Map.of("answer", 42)));
        calls.fact(cid, fact, actor);
        db.update(
                "update engine.node_instance set lease_until=now()-interval '1 second' where id=?",
                n);
        var second = store.claim(n, "B");
        var clients =
                new RuntimeClients() {
                    @Override
                    public com.fasterxml.jackson.databind.JsonNode chatStream(
                            Object body, Actor actor, java.util.function.Consumer<String> chunk) {
                        return post("model", "/internal/model/chat/stream", body, actor);
                    }

                    @Override
                    public com.fasterxml.jackson.databind.JsonNode post(
                            String service, String path, Object body, Actor actor) {
                        throw new AssertionError(
                                "W3 must not redispatch a persisted successful tool");
                    }
                };
        var driver = new AgentDriver(store, clients, new ContextAssembler());
        var result =
                driver.skill(
                        store.db().queryForMap("select * from engine.run where id=?", r),
                        second,
                        "lookup",
                        42,
                        Json.read("{\"x\":1}"),
                        1,
                        actor);
        assertEquals(42, result.path("answer").asInt());
    }

    @Test
    void onlyTimedOutDebugPauseIsCancelled() {
        long r = run(), n = node(r, "WAITING_STEP");
        int seq = store.atomic(() -> store.event(r, "WAITING_STEP", Map.of("nodeInstanceId", n)));
        db.update("update engine.node_instance set pause_seq=? where id=?", seq, n);
        db.update("update engine.run set status='WAITING_STEP' where id=?", r);
        runs.expireDebugRuns();
        assertEquals(
                "WAITING_STEP",
                db.queryForObject("select status from engine.run where id=?", String.class, r));
        db.update(
                "update engine.run_event set created_at=now()-interval '31 minutes' where run_id=?",
                r);
        runs.expireDebugRuns();
        assertEquals(
                "CANCELLED",
                db.queryForObject("select status from engine.run where id=?", String.class, r));
        assertEquals(
                "DEBUG_TIMEOUT",
                db.queryForObject(
                        "select stop_reason from engine.run where id=?", String.class, r));
    }

    @Test
    void modelCallBudgetIsDurableAndIndependent() {
        long r = run();
        db.update(
                "update engine.run set"
                    + " budget='{\"limits\":{\"modelCalls\":1},\"consumed\":{\"modelCalls\":0}}'"
                    + " where id=?",
                r);
        store.consume(r, "modelCalls", 1, 40);
        assertThrows(PlatformException.class, () -> store.consume(r, "modelCalls", 1, 40));
        assertEquals(
                1,
                db.queryForObject(
                        "select (budget->'consumed'->>'modelCalls')::int from engine.run where"
                                + " id=?",
                        Integer.class,
                        r));
    }

    @Test
    void repeatedUnknownFactsDoNotDoubleCountExecutionDuration() {
        long r = run(), n = node(r, "READY");
        var first = store.claim(n, "A");
        var calls = new ToolCalls(store);
        var req = call(r, n, first);
        long cid = TraceStore.number(calls.register(req.register(), actor), "callId");
        calls.claim(cid, req.claim(), actor);
        calls.submitted(cid, req.claim(), actor);
        var fact = (com.fasterxml.jackson.databind.node.ObjectNode) req.claim().deepCopy();
        fact.put("status", "UNKNOWN");
        fact.put("durationMs", 10);
        calls.fact(cid, fact, actor);
        calls.fact(cid, fact, actor);
        assertEquals(
                10,
                db.queryForObject(
                        "select duration_ms from engine.tool_call where id=?", Integer.class, cid));
        fact.put("status", "SUCCEEDED");
        calls.fact(cid, fact, actor);
        assertEquals(
                10,
                db.queryForObject(
                        "select duration_ms from engine.tool_call where id=?", Integer.class, cid));
    }

    @Test
    void factCanArriveWhileLiveOwnerWaitsExternal() {
        long r = run(), n = node(r, "READY");
        var first = store.claim(n, "A");
        var calls = new ToolCalls(store);
        var req = call(r, n, first);
        long cid = TraceStore.number(calls.register(req.register(), actor), "callId");
        calls.claim(cid, req.claim(), actor);
        calls.submitted(cid, req.claim(), actor);
        db.update("update engine.node_instance set status='WAITING_EXTERNAL' where id=?", n);
        db.update("update engine.run set status='WAITING_EXTERNAL' where id=?", r);
        var fact = (com.fasterxml.jackson.databind.node.ObjectNode) req.claim().deepCopy();
        fact.put("status", "SUCCEEDED");
        fact.set("result", Json.tree(Map.of("reconciled", true)));
        assertEquals(true, calls.fact(cid, fact, actor).get("accepted"));
    }

    @Test
    void approvalRequiresDeclaredRoleAndFrozenDigestThenUsesLogicalCommit() throws Exception {
        var req = evaluation("approval-case");
        var snapshot = (com.fasterxml.jackson.databind.node.ObjectNode) req.path("frozenCandidate");
        var graph = (com.fasterxml.jackson.databind.node.ObjectNode) snapshot.path("definition");
        graph.set(
                "nodes",
                Json.read(
                        "[{\"id\":\"start\",\"type\":\"START\"},{\"id\":\"confirm\",\"type\":\"CONFIRM\",\"confirmRoles\":[\"APPROVER\"],\"args\":{\"x\":\"${input.x}\"}},{\"id\":\"end\",\"type\":\"END\"}]"));
        graph.set(
                "edges",
                Json.read(
                        "[{\"from\":\"start\",\"to\":\"confirm\"},{\"from\":\"confirm\",\"to\":\"end\"}]"));
        var content = new LinkedHashMap<String, Object>();
        for (String key :
                List.of(
                        "appCode",
                        "type",
                        "name",
                        "description",
                        "domain",
                        "bizCategory",
                        "tags",
                        "config",
                        "definition")) content.put(key, snapshot.path(key));
        String hash = Digest.sha256(Json.canonical(content));
        req.put("candidateSha256", hash);
        snapshot.put("candidateSha256", hash);
        var result = runs.create("eval-app", req, "EVALUATION", actor);
        result = awaitRun(result, "WAITING_APPROVAL");
        assertEquals("WAITING_APPROVAL", result.get("status"));
        var approvals = new ApprovalService(store);
        assertTrue(approvals.list(actor).isEmpty());
        Actor approver = new Actor("engine-test", "bob", Set.of("APPROVER"), Map.of());
        var item = approvals.list(approver).getFirst();
        assertEquals(1, Json.tree(item.get("parameterSnapshot")).path("args").path("x").asInt());
        long nid = TraceStore.number(item, "nodeInstanceId");
        String id = result.get("runId").toString();
        var decision =
                Json.tree(
                        Map.of(
                                "approved",
                                true,
                                "requestId",
                                "approve-1",
                                "snapshotDigest",
                                item.get("snapshotDigest")));
        assertThrows(PlatformException.class, () -> approvals.approve(id, nid, decision, actor));
        var wrong = (com.fasterxml.jackson.databind.node.ObjectNode) decision.deepCopy();
        wrong.put("snapshotDigest", "changed");
        assertThrows(PlatformException.class, () -> approvals.approve(id, nid, wrong, approver));
        approvals.approve(id, nid, decision, approver);
        long rid = db.queryForObject("select id from engine.run where public_id=?", Long.class, id);
        runs.drive(rid, actor);
        assertEquals("SUCCEEDED", runs.status(id, actor).get("status"));
        assertNotNull(
                db.queryForObject(
                        "select committed_attempt_id from engine.node_instance where id=?",
                        Long.class,
                        nid));
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

    @Test
    void writeApprovalIsBoundToStableCallArgumentsAcrossAttempts() {
        long r = run(), n = node(r, "READY");
        db.update(
                "update engine.run set snapshot=?::jsonb where id=?",
                Json.write(
                        Map.of(
                                "config",
                                Map.of(
                                        "tools",
                                        List.of(
                                                Map.of(
                                                        "skillVersionId",
                                                        42,
                                                        "code",
                                                        "lookup",
                                                        "contractDigest",
                                                        Digest.sha256("fixture-contract"),
                                                        "execution",
                                                        Map.of(
                                                                "confirmRoles",
                                                                List.of("APPROVER"))))))),
                r);
        var first = store.claim(n, "A");
        var calls = new ToolCalls(store);
        var req = call(r, n, first);
        long cid = TraceStore.number(calls.register(req.register(), actor), "callId");
        assertEquals(false, calls.approval(cid, actor).get("approved"));
        calls.requestApproval(
                cid,
                Json.tree(
                        Map.of(
                                "roles",
                                List.of("APPROVER"),
                                "parametersDigest",
                                Digest.sha256(Json.canonical(Map.of("x", 1))))),
                actor);
        assertEquals(
                "REGISTERED",
                db.queryForObject(
                        "select status from engine.tool_call where id=?", String.class, cid));
        assertEquals(
                "WAITING_APPROVAL",
                db.queryForObject(
                        "select status from engine.node_instance where id=?", String.class, n));
        assertEquals(
                0,
                db.queryForObject(
                        "select count(*) from engine.attempt_note where node_instance_id=? and"
                                + " kind='TOOL_EXECUTION_FACT'",
                        Integer.class,
                        n));
        var approvalService = new ApprovalService(store);
        var approver = new Actor("engine-test", "bob", Set.of("APPROVER"), Map.of());
        var pending = approvalService.list(approver).getFirst();
        String publicId =
                db.queryForObject("select public_id from engine.run where id=?", String.class, r);
        approvalService.approve(
                publicId,
                n,
                Json.tree(
                        Map.of(
                                "requestId",
                                "write-approval",
                                "approved",
                                true,
                                "snapshotDigest",
                                pending.get("snapshotDigest"))),
                approver);
        var second = store.claim(n, "B");
        var resumed = call(r, n, second);
        assertEquals(cid, TraceStore.number(calls.register(resumed.register(), actor), "callId"));
        assertEquals(true, calls.approval(cid, actor).get("approved"));
        assertEquals(42, ((Number) calls.approval(cid, actor).get("skillVersionId")).intValue());
    }

    @Test
    void adminWithoutBusinessApprovalRoleCannotReadOrApprove() {
        long r = run(), n = node(r, "READY");
        store.claim(n, "approval");
        db.update(
                "update engine.node_instance set status='WAITING_APPROVAL',definition=?::jsonb"
                        + " where id=?",
                Json.write(Map.of("type", "CONFIRM", "confirmRoles", List.of("APPROVER"))),
                n);
        var service = new ApprovalService(store);
        var admin = new Actor("engine-test", "admin-only", Set.of("ADMIN"), Map.of());
        assertTrue(service.list(admin).isEmpty());
        String id =
                db.queryForObject("select public_id from engine.run where id=?", String.class, r);
        assertThrows(
                PlatformException.class,
                () ->
                        service.approve(
                                id,
                                n,
                                Json.tree(
                                        Map.of(
                                                "approved",
                                                true,
                                                "requestId",
                                                "admin-override",
                                                "snapshotDigest",
                                                Digest.sha256("{}"))),
                                admin));
    }

    @Test
    void toolRegistrationCannotEscapeFrozenVocabulary() {
        long r = run(), n = node(r, "READY");
        db.update("update engine.run set snapshot='{}' where id=?", r);
        var lease = store.claim(n, "worker");
        assertThrows(
                PlatformException.class,
                () -> new ToolCalls(store).register(call(r, n, lease).register(), actor));
    }

    @Test
    void largeSkillResultKeepsDownloadReferencesOutsideStructuredOutput() {
        long r = run(), n = node(r, "READY");
        var lease = store.claim(n, "artifact");
        var calls = new ToolCalls(store);
        var req = call(r, n, lease);
        long id = TraceStore.number(calls.register(req.register(), actor), "callId");
        calls.claim(id, req.claim(), actor);
        var fact = (com.fasterxml.jackson.databind.node.ObjectNode) req.claim().deepCopy();
        fact.put("status", "SUCCEEDED");
        fact.set(
                "result",
                Json.read(
                        "{\"structured\":null,\"fileRefs\":[{\"artifactId\":\"a_test\",\"downloadUrl\":\"/admin/artifacts/a_test/download\",\"fileName\":\"large.json\",\"sizeBytes\":5242880,\"uri\":\"s3://secret/key\"}]}"));
        String publicRun =
                db.queryForObject("select public_id from engine.run where id=?", String.class, r);
        var ref =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        fact.path("result").path("fileRefs").get(0);
        ref.put("externalDownloadUrl", "/v1/runs/" + publicRun + "/artifacts/a_test/download");
        ref.put("bytes", 5242880);
        ref.put("summary", "Large result offloaded");
        calls.fact(id, fact, actor);
        store.commit(
                r,
                n,
                TraceStore.number(lease, "attemptId"),
                lease.get("lease_owner").toString(),
                Json.read("null"));
        String runId =
                db.queryForObject("select public_id from engine.run where id=?", String.class, r);
        var status = SchedulerTestResources.runs(store, null, null, null).status(runId, actor);
        var refs = Json.tree(status.get("fileRefs"));
        assertEquals(1, refs.size());
        assertEquals("a_test", refs.get(0).path("artifactId").asText());
        assertFalse(refs.get(0).has("uri"));
        assertEquals(
                "/v1/runs/" + publicRun + "/artifacts/a_test/download",
                refs.get(0).path("externalDownloadUrl").asText());
        assertEquals(5242880, refs.get(0).path("bytes").asInt());
        assertFalse(Json.write(refs).contains("s3://"));
        assertTrue(Json.tree(status.get("nodes")).get(0).path("output").isNull());
    }
}
