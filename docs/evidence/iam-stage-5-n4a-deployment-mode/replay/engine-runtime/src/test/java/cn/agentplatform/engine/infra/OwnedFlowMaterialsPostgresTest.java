package cn.agentplatform.engine.infra;

import static cn.agentplatform.engine.infra.OwnedFlowSchedulerPostgresTest.*;

import static org.assertj.core.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.engine.application.*;
import cn.agentplatform.engine.domain.*;
import cn.agentplatform.engine.domain.RunDispatch.Queue;

import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class OwnedFlowMaterialsPostgresTest {
    @Test
    void dispatchExpiryDuringFailureScopesRollsBackBeforeActualFailureCommit() throws Exception {
        try (var f = fixture()) {
            long run = insert(f, DEFAULT);
            var t = FlowMaterialScopePostgresTest.trace(f);
            var d = dispatch(f, run);
            t.startOwnedFlow(d, current(f, run));
            var p = current(f, run);
            long node = f.db.queryForObject("select id from engine.node_instance", Long.class);
            var w = t.prepareAndClaimFlow(node, "worker", d, p);
            var before = FlowMaterialTransactionsPostgresTest.facts(f, run);
            var once = new AtomicBoolean();
            f.ds.beforeSql =
                    sql -> {
                        if (sql.startsWith("insert into engine.material_scope")
                                && once.compareAndSet(false, true))
                            f.db.update(
                                    "update engine.run set"
                                        + " dispatch_until=clock_timestamp()-interval '1 second'"
                                        + " where id=?",
                                    run);
                    };
            try {
                assertThatThrownBy(
                                () ->
                                        t.failFlow(
                                                node,
                                                TraceStore.number(w.node(), "attemptId"),
                                                w.node().get("lease_owner").toString(),
                                                Map.of("semantic", "RUNTIME_ERROR"),
                                                d,
                                                p))
                        .isInstanceOf(TraceStore.DispatchLost.class);
            } finally {
                f.ds.beforeSql = sql -> {};
            }
            assertThat(once).isTrue();
            assertThat(FlowMaterialTransactionsPostgresTest.facts(f, run)).isEqualTo(before);
        }
    }

    @BeforeAll
    static void setup() throws Exception {
        isolatedDatabase();
    }

    static final String DEFAULT =
            "{\"nodes\":[{\"id\":\"x\",\"type\":\"CODE\",\"expression\":\"new"
                + " forbidden\",\"errorPolicy\":{\"onFail\":\"DEFAULT_VALUE\",\"defaultValue\":{\"fallback\":true}}}],\"edges\":[]}";

    static RunDispatch dispatch(RunAuthorityFixture f, long run) {
        return new RunScheduleRepository(FlowMaterialScopePostgresTest.trace(f), true)
                .claimSpecific(run, Queue.INTERACTIVE, "owned-test", Duration.ofSeconds(30));
    }

    static HttpCurrentAuthorityClient.RetainedAuthorization current(
            RunAuthorityFixture f, long run) {
        return FlowMaterialScopePostgresTest.current(f, run);
    }

    static Map<String, Object> states(RunAuthorityFixture f) {
        var rows =
                f.db.queryForList(
                        "select node_def_id,status from engine.node_instance order by step_seq");
        var result = new LinkedHashMap<String, Object>();
        rows.forEach(r -> result.put(r.get("node_def_id").toString(), r.get("status")));
        return result;
    }

    static void worker(RunAuthorityFixture f, long run) throws Exception {
        try (var w = new Worker(f, true)) {
            w.scheduler.poll();
            w.await();
        }
    }

    static void fault(RunAuthorityFixture f, String kind, String when) {
        if (!Set.of(
                        "node-error",
                        "node-output",
                        "node-state",
                        "attempt-state",
                        "run-event",
                        "run-error",
                        "run-state",
                        "run-output")
                .contains(kind)) throw new AssertionError();
        f.db.execute(
                "create function engine.n2_scope_fault() returns trigger language plpgsql as $$"
                        + " begin if NEW.kind='"
                        + kind
                        + "' and ("
                        + when
                        + ") then raise exception 'N2_TRANSACTION_FAULT'; end if; return NEW; end"
                        + " $$");
        f.db.execute(
                "create trigger n2_scope_fault before insert on engine.material_scope for each row"
                        + " execute function engine.n2_scope_fault()");
    }

    static void unfault(RunAuthorityFixture f) {
        f.db.execute("drop trigger n2_scope_fault on engine.material_scope");
        f.db.execute("drop function engine.n2_scope_fault()");
    }

    @Test
    void defaultErrorOutputAttemptAndEventsCommitTogetherAndEveryActualScopeFaultRollsBack()
            throws Exception {
        try (var f = fixture()) {
            long run = insert(f, DEFAULT);
            worker(f, run);
            assertThat(states(f)).containsEntry("x", "SUCCEEDED");
            assertThat(
                            f.db.queryForObject(
                                    "select output->>'fallback' from engine.node_instance",
                                    String.class))
                    .isEqualTo("true");
            assertThat(
                            f.db.queryForObject(
                                    "select error->>'semantic' from engine.node_instance",
                                    String.class))
                    .isEqualTo("RUNTIME_ERROR");
        }
        for (String kind :
                List.of("node-error", "node-output", "node-state", "attempt-state", "run-event"))
            try (var f = fixture()) {
                long run = insert(f, DEFAULT);
                var trace = FlowMaterialScopePostgresTest.trace(f);
                var d = dispatch(f, run);
                trace.startOwnedFlow(d, current(f, run));
                var p = current(f, run);
                var work =
                        trace.prepareAndClaimFlow(
                                f.db.queryForObject(
                                        "select id from engine.node_instance", Long.class),
                                "worker",
                                d,
                                p);
                long node = TraceStore.number(work.node(), "id"),
                        attempt = TraceStore.number(work.node(), "attemptId");
                var before = FlowMaterialTransactionsPostgresTest.facts(f, run);
                fault(f, kind, "true");
                try {
                    assertThatThrownBy(
                                    () ->
                                            trace.commitDefaultFlow(
                                                    node,
                                                    attempt,
                                                    work.node().get("lease_owner").toString(),
                                                    Map.of("default", true),
                                                    Map.of("semantic", "RUNTIME_ERROR"),
                                                    d,
                                                    p))
                            .isInstanceOf(org.springframework.dao.DataAccessException.class);
                } finally {
                    unfault(f);
                }
                assertThat(FlowMaterialTransactionsPostgresTest.facts(f, run))
                        .as(kind)
                        .isEqualTo(before);
            }
    }

    @Test
    void workerCommitInfrastructureFaultNeverEntersBusinessFallbackOrFailure() throws Exception {
        try (var f = fixture()) {
            long run = insert(f, DEFAULT);
            fault(f, "node-output", "true");
            try {
                worker(f, run);
            } finally {
                unfault(f);
            }
            assertThat(states(f)).containsEntry("x", "RUNNING");
            assertThat(
                            f.db.queryForObject(
                                    "select error is null and output is null from"
                                            + " engine.node_instance",
                                    Boolean.class))
                    .isTrue();
            assertThat(f.db.queryForList("select type from engine.run_event", String.class))
                    .containsExactly("RUN_STARTED", "NODE_STARTED");
            assertThat(f.db.queryForObject("select status from engine.attempt", String.class))
                    .isEqualTo("RUNNING");
        }
    }

    @Test
    void conditionAllAnyAndErrorEdgesUseActualWinnerExclusiveAndSharedBranches() throws Exception {
        for (boolean shared : List.of(false, true))
            try (var f = fixture()) {
                String graph =
                        "{\"nodes\":[{\"id\":\"s\",\"type\":\"START\"},{\"id\":\"j\",\"type\":\"END\",\"args\":\"${s.output}\"},{\"id\":\"b\",\"type\":\"CODE\",\"expression\":\"9\"}"
                                + (shared ? ",{\"id\":\"consumer\",\"type\":\"END\"}" : "")
                                + "],\"edges\":[{\"from\":\"s\",\"to\":\"j\"},{\"from\":\"b\",\"to\":\"j\"}"
                                + (shared ? ",{\"from\":\"b\",\"to\":\"consumer\"}" : "")
                                + "],\"joins\":[{\"nodeId\":\"j\",\"semantics\":\"ANY\"}]}";
                long run = insert(f, graph);
                worker(f, run);
                assertThat(states(f)).containsEntry("b", shared ? "SUCCEEDED" : "ABANDONED");
                assertThat(
                                f.db.queryForObject(
                                        "select winner_node_id=(select id from engine.node_instance"
                                            + " where node_def_id='s') from engine.node_instance"
                                            + " where node_def_id='j'",
                                        Boolean.class))
                        .isTrue();
                assertThat(
                                f.db.queryForObject(
                                        "select count(*) from engine.material_scope where"
                                            + " kind='node-state' and scope->'parentScopeSha256' is"
                                            + " not null and material_id=(select id::text from"
                                            + " engine.node_instance where node_def_id='b')",
                                        Long.class))
                        .isGreaterThan(1);
            }
        try (var f = fixture()) {
            long run =
                    insert(
                            f,
                            "{\"nodes\":[{\"id\":\"c\",\"type\":\"CONDITION\",\"branches\":[{\"else\":\"e\"}]},{\"id\":\"skip\",\"type\":\"CODE\",\"expression\":\"1\"},{\"id\":\"e\",\"type\":\"END\"}],\"edges\":[{\"from\":\"c\",\"to\":\"skip\"},{\"from\":\"c\",\"to\":\"e\"},{\"from\":\"skip\",\"to\":\"e\"}],\"joins\":[{\"nodeId\":\"e\",\"semantics\":\"ALL\",\"skipCountsAs\":\"PASS\"}]}");
            worker(f, run);
            assertThat(states(f)).containsEntry("skip", "SKIPPED").containsEntry("e", "SUCCEEDED");
            assertThat(f.db.queryForList("select type from engine.run_event", String.class))
                    .contains("NODE_SKIPPED");
        }
        try (var f = fixture()) {
            long run =
                    insert(
                            f,
                            "{\"nodes\":[{\"id\":\"bad\",\"type\":\"CODE\",\"expression\":\"new"
                                + " x\",\"errorPolicy\":{\"onFail\":\"ERROR_EDGE\",\"errorEdgeTo\":\"e\"}},{\"id\":\"normal\",\"type\":\"END\"},{\"id\":\"e\",\"type\":\"END\",\"args\":\"${error.semantic}\"}],\"edges\":[{\"from\":\"bad\",\"to\":\"normal\"},{\"from\":\"bad\",\"to\":\"e\",\"when\":\"ERROR\"}]}");
            worker(f, run);
            assertThat(states(f))
                    .containsEntry("bad", "FAILED")
                    .containsEntry("normal", "SKIPPED")
                    .containsEntry("e", "SUCCEEDED");
            assertThat(
                            f.db.queryForObject(
                                    "select status from engine.run where id=?", String.class, run))
                    .isEqualTo("SUCCEEDED");
        }
    }

    @Test
    void budgetPrecheckCommitsPrepareWinnerWithoutAttemptOrConsumptionAndBudgetStopIsAtomic()
            throws Exception {
        for (String faultKind :
                List.of("none", "run-state", "run-error", "node-state", "run-event"))
            try (var f = fixture()) {
                long run =
                        insert(
                                f,
                                "{\"nodes\":[{\"id\":\"s\",\"type\":\"START\"},{\"id\":\"j\",\"type\":\"END\"},{\"id\":\"b\",\"type\":\"CODE\",\"expression\":\"1\"}],\"edges\":[{\"from\":\"s\",\"to\":\"j\"},{\"from\":\"b\",\"to\":\"j\"}],\"joins\":[{\"nodeId\":\"j\",\"semantics\":\"ANY\"}]}");
                var trace = FlowMaterialScopePostgresTest.trace(f);
                var d = dispatch(f, run);
                trace.startOwnedFlow(d, current(f, run));
                var p = current(f, run);
                long s =
                        f.db.queryForObject(
                                "select id from engine.node_instance where node_def_id='s'",
                                Long.class);
                var w = trace.prepareAndClaimFlow(s, "worker", d, p);
                trace.commitFlow(
                        s,
                        TraceStore.number(w.node(), "attemptId"),
                        w.node().get("lease_owner").toString(),
                        Map.of(),
                        d,
                        p);
                trace.consume(run, "steps", 24, 25, current(f, run));
                long join =
                        f.db.queryForObject(
                                "select id from engine.node_instance where node_def_id='j'",
                                Long.class);
                p = current(f, run);
                var result = trace.prepareAndClaimFlow(join, "worker", d, p);
                assertThat(result.budgetExhausted()).isEqualTo("BUDGET_STEPS");
                assertThat(states(f)).containsEntry("j", "READY").containsEntry("b", "ABANDONED");
                assertThat(
                                f.db.queryForObject(
                                        "select winner_node_id is not null from"
                                                + " engine.node_instance where id=?",
                                        Boolean.class,
                                        join))
                        .isTrue();
                assertThat(f.db.queryForObject("select count(*) from engine.attempt", Long.class))
                        .isEqualTo(1);
                assertThat(
                                f.db.queryForObject(
                                        "select budget->'consumed'->>'steps' from engine.run where"
                                                + " id=?",
                                        String.class,
                                        run))
                        .isEqualTo("25");
                var before = FlowMaterialTransactionsPostgresTest.facts(f, run);
                var proof = p;
                if (!faultKind.equals("none")) {
                    fault(f, faultKind, "true");
                    try {
                        assertThatThrownBy(
                                        () ->
                                                trace.stopFlowForBudget(
                                                        d, proof, result.budgetExhausted()))
                                .isInstanceOf(org.springframework.dao.DataAccessException.class);
                    } finally {
                        unfault(f);
                    }
                    assertThat(FlowMaterialTransactionsPostgresTest.facts(f, run))
                            .isEqualTo(before);
                } else {
                    trace.stopFlowForBudget(d, p, result.budgetExhausted());
                    assertThat(states(f)).containsEntry("j", "CANCELLED");
                    assertThat(
                                    f.db.queryForObject(
                                            "select status from engine.run where id=?",
                                            String.class,
                                            run))
                            .isEqualTo("FAILED");
                }
            }
    }

    @Test
    void nodeCasZeroDefaultHasNoErrorOrSuccessScopeButValidDispatchFailRetainsAttemptFact()
            throws Exception {
        try (var f = fixture()) {
            long run = insert(f, DEFAULT);
            var t = FlowMaterialScopePostgresTest.trace(f);
            var d = dispatch(f, run);
            t.startOwnedFlow(d, current(f, run));
            var p = current(f, run);
            long node = f.db.queryForObject("select id from engine.node_instance", Long.class);
            var w = t.prepareAndClaimFlow(node, "worker", d, p);
            long attempt = TraceStore.number(w.node(), "attemptId");
            f.db.update("update engine.node_instance set lease_owner='other' where id=?", node);
            long scopes =
                    f.db.queryForObject("select count(*) from engine.material_scope", Long.class);
            assertThat(
                            t.commitDefaultFlow(
                                    node,
                                    attempt,
                                    w.node().get("lease_owner").toString(),
                                    Map.of(),
                                    Map.of("error", true),
                                    d,
                                    p))
                    .isFalse();
            assertThat(
                            f.db.queryForObject(
                                    "select error is null and output is null from"
                                            + " engine.node_instance",
                                    Boolean.class))
                    .isTrue();
            assertThat(
                            f.db.queryForObject(
                                    "select count(*) from engine.material_scope", Long.class))
                    .isEqualTo(scopes);
            t.failFlow(
                    node,
                    attempt,
                    w.node().get("lease_owner").toString(),
                    Map.of("semantic", "RUNTIME_ERROR"),
                    d,
                    p);
            assertThat(f.db.queryForObject("select status from engine.attempt", String.class))
                    .isEqualTo("FAILED");
            assertThat(states(f)).containsEntry("x", "RUNNING");
            assertThat(f.db.queryForList("select type from engine.run_event", String.class))
                    .doesNotContain("NODE_FAILED", "NODE_FINISHED");
        }
    }

    @Test
    void oldDispatchCannotCommitOrFailForNewGenerationAndControlRenewalsPreserveDigest()
            throws Exception {
        try (var f = fixture()) {
            long run = insert(f, SIMPLE);
            var t = FlowMaterialScopePostgresTest.trace(f);
            var d = dispatch(f, run);
            t.startOwnedFlow(d, current(f, run));
            var p = current(f, run);
            long node =
                    f.db.queryForObject(
                            "select id from engine.node_instance where node_def_id='s'",
                            Long.class);
            var w = t.prepareAndClaimFlow(node, "worker", d, p);
            long attempt = TraceStore.number(w.node(), "attemptId");
            f.db.update(
                    "update engine.run set dispatch_generation=dispatch_generation+1 where id=?",
                    run);
            var before = FlowMaterialTransactionsPostgresTest.facts(f, run);
            assertThat(
                            t.commitFlow(
                                    node,
                                    attempt,
                                    w.node().get("lease_owner").toString(),
                                    Map.of(),
                                    d,
                                    p))
                    .isFalse();
            t.failFlow(node, attempt, w.node().get("lease_owner").toString(), Map.of(), d, p);
            assertThat(FlowMaterialTransactionsPostgresTest.facts(f, run)).isEqualTo(before);
            f.db.update(
                    "update engine.run set dispatch_generation=? where id=?", d.generation(), run);
            var refs = new MaterialScopeStore(f.ds, f.iam.clock()).currentRefs(run, p);
            f.db.update(
                    "update engine.node_instance set lease_until=lease_until+interval '1 second'"
                            + " where id=?",
                    node);
            assertThat(
                            t.commitFlow(
                                    node,
                                    attempt,
                                    w.node().get("lease_owner").toString(),
                                    Map.of(),
                                    d,
                                    p))
                    .isTrue();
            assertThat(refs).isNotEmpty();
        }
    }

    @Test
    void lastNodePostCommitProofExpiryLeavesGrantUsableAndAggregateOnlyNewCurrentNeverRerunsNode()
            throws Exception {
        try (var f = fixture();
                var w = new Worker(f, true)) {
            long run = insert(f, SIMPLE);
            var once = new AtomicBoolean();
            f.ds.afterMaterialCommit =
                    () -> {
                        if (Boolean.TRUE.equals(
                                        f.db.queryForObject(
                                                "select not exists(select 1 from"
                                                    + " engine.node_instance where run_id=? and"
                                                    + " status not in"
                                                    + " ('SUCCEEDED','FAILED','SKIPPED','ABANDONED','CANCELLED'))",
                                                Boolean.class,
                                                run))
                                && once.compareAndSet(false, true))
                            f.iam.advanceClock(Duration.ofSeconds(6));
                    };
            w.scheduler.poll();
            w.await();
            f.ds.afterMaterialCommit = () -> {};
            assertThat(once).isTrue();
            assertThat(f.store.load(run).blockedReason()).isNull();
            assertThat(
                            f.db.queryForObject(
                                    "select status from engine.run where id=?", String.class, run))
                    .isEqualTo("RUNNING");
            long attempts = f.db.queryForObject("select count(*) from engine.attempt", Long.class);
            int calls = f.iam.requestAttempts();
            f.db.update("update engine.run set dispatch_next_at=clock_timestamp() where id=?", run);
            w.scheduler.poll();
            w.await();
            assertThat(f.iam.requestAttempts()).isEqualTo(calls + 4);
            assertThat(f.db.queryForObject("select count(*) from engine.attempt", Long.class))
                    .isEqualTo(attempts);
            assertThat(
                            f.db.queryForObject(
                                    "select status from engine.run where id=?", String.class, run))
                    .isEqualTo("SUCCEEDED");
            assertThat(
                            f.db.queryForObject(
                                    "select count(*) from engine.run_event where"
                                            + " type='RUN_FINISHED'",
                                    Long.class))
                    .isEqualTo(1);
        }
    }

    @Test
    void executionUsesOnlyMaterializedWorkAndItsRealAttemptPreservesPreviouslyConsumedParents()
            throws Exception {
        try (var f = fixture()) {
            long run = insert(f, SIMPLE);
            var t = FlowMaterialScopePostgresTest.trace(f);
            var d = dispatch(f, run);
            t.startOwnedFlow(d, current(f, run));
            var p = current(f, run);
            long node =
                    f.db.queryForObject(
                            "select id from engine.node_instance where node_def_id='s'",
                            Long.class);
            var w = t.prepareAndClaimFlow(node, "worker", d, p);
            long attempt = TraceStore.number(w.node(), "attemptId");
            String old =
                    f.db.queryForObject(
                            "select scope_sha256 from engine.material_scope where"
                                    + " kind='node-output' and material_id=(select id::text from"
                                    + " engine.node_instance where node_def_id='e') order by"
                                    + " material_revision desc limit 1",
                            String.class);
            f.ds.beforeSql =
                    sql -> {
                        throw new AssertionError("execute must not query PG: " + sql);
                    };
            var output = new FlowDriver(t, null).executeFlow(w, d, p);
            var forgedNode = new LinkedHashMap<>(w.node());
            forgedNode.put("definition", Json.read("{\"type\":\"CONFIRM\"}"));
            assertThatThrownBy(
                            () ->
                                    new FlowDriver(t, null)
                                            .executeFlow(
                                                    new TraceStore.FlowClaim(
                                                            w.run(), forgedNode, w.scope(), null),
                                                    d,
                                                    p))
                    .isInstanceOf(PlatformException.class);
            f.ds.beforeSql = sql -> {};
            // A second actual node action replaces its output while the first action retains its
            // work.
            long other =
                    f.db.queryForObject(
                            "select id from engine.node_instance where node_def_id='e'",
                            Long.class);
            f.tx.setIsolationLevel(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            f.tx.execute(
                    status -> {
                        var b = RunAuthorityStore.load(f.db, run, true);
                        var ms = new MaterialScopeStore(f.ds, f.iam.clock());
                        ms.requireWriter(b, p, false);
                        var parents = ms.currentScopes(b);
                        f.db.update(
                                "update engine.node_instance set output='9'::jsonb where id=?",
                                other);
                        ms.append(b, "node-output", Long.toString(other), p, parents);
                        return null;
                    });
            assertThat(
                            t.commitFlow(
                                    node,
                                    attempt,
                                    w.node().get("lease_owner").toString(),
                                    output,
                                    d,
                                    p))
                    .isTrue();
            assertThat(
                            f.db.queryForObject(
                                    "with recursive lineage as (select scope from"
                                        + " engine.material_scope where kind='node-output' and"
                                        + " material_id=? order by material_revision desc limit 1),"
                                        + " ancestors(hash) as (select"
                                        + " jsonb_array_elements_text(scope->'parentScopeSha256')"
                                        + " from lineage union select"
                                        + " jsonb_array_elements_text(m.scope->'parentScopeSha256')"
                                        + " from engine.material_scope m join ancestors a on"
                                        + " m.scope_sha256=a.hash) select exists(select 1 from"
                                        + " ancestors where hash=?)",
                                    Boolean.class,
                                    Long.toString(node),
                                    old))
                    .isTrue();
        }
    }

    @Test
    void actualNodeLockOutlivingDispatchRejectsBeforePayload() throws Exception {
        try (var f = fixture();
                var executor = Executors.newSingleThreadExecutor()) {
            long run = insert(f, SIMPLE);
            var t = FlowMaterialScopePostgresTest.trace(f);
            var d =
                    new RunScheduleRepository(t, true)
                            .claimSpecific(
                                    run,
                                    Queue.INTERACTIVE,
                                    "short-node-lock",
                                    Duration.ofSeconds(1));
            var p = current(f, run);
            long node =
                    f.db.queryForObject(
                            "select id from engine.node_instance where node_def_id='s'",
                            Long.class);
            int reads = f.ds.payloadReads.get();
            var arrived = new CountDownLatch(1);
            var pid = new AtomicInteger();
            f.ds.beforeRunLock =
                    id -> {
                        pid.set(id);
                        arrived.countDown();
                    };
            try (var blocker = f.ds.getConnection()) {
                blocker.setAutoCommit(false);
                try (var lock =
                        blocker.prepareStatement(
                                "select id from engine.node_instance where id=? for update")) {
                    lock.setLong(1, node);
                    try (var rows = lock.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                    }
                    var future =
                            executor.submit(() -> catchThrowable(() -> t.startOwnedFlow(d, p)));
                    try {
                        assertThat(arrived.await(5, TimeUnit.SECONDS)).isTrue();
                        boolean waiting = false;
                        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                        do {
                            waiting =
                                    Boolean.TRUE.equals(
                                            f.db.queryForObject(
                                                    "select exists(select 1 from pg_stat_activity"
                                                        + " where pid=? and wait_event_type='Lock'"
                                                        + " and query like 'select id from"
                                                        + " engine.node_instance%')",
                                                    Boolean.class, pid.get()));
                            if (!waiting) Thread.sleep(10);
                        } while (!waiting && System.nanoTime() < until);
                        assertThat(waiting).isTrue();
                        System.out.println("N2_ACTUAL_NODE_LOCK=true");
                        Thread.sleep(1100);
                    } finally {
                        blocker.rollback();
                    }
                    assertThat(future.get(5, TimeUnit.SECONDS))
                            .isInstanceOf(TraceStore.DispatchLost.class);
                }
            }
            assertThat(f.ds.payloadReads.get())
                    .as("live dispatch is checked after the actual node lock wait")
                    .isEqualTo(reads);
            assertThat(f.db.queryForObject("select count(*) from engine.run_event", Long.class))
                    .isZero();
        }
    }

    @Test
    void callbackProofExpiryRollsBackDefaultFactsAndNeverBecomesBusinessFailure() throws Exception {
        try (var f = fixture()) {
            long run = insert(f, DEFAULT);
            var t = FlowMaterialScopePostgresTest.trace(f);
            var d = dispatch(f, run);
            t.startOwnedFlow(d, current(f, run));
            var p = current(f, run);
            long node = f.db.queryForObject("select id from engine.node_instance", Long.class);
            var w = t.prepareAndClaimFlow(node, "worker", d, p);
            var before = FlowMaterialTransactionsPostgresTest.facts(f, run);
            var once = new AtomicBoolean();
            f.ds.beforeSql =
                    sql -> {
                        if (sql.startsWith("update engine.node_instance set status='SUCCEEDED'")
                                && once.compareAndSet(false, true))
                            f.iam.advanceClock(Duration.ofSeconds(6));
                    };
            assertThatThrownBy(
                            () ->
                                    t.commitDefaultFlow(
                                            node,
                                            TraceStore.number(w.node(), "attemptId"),
                                            w.node().get("lease_owner").toString(),
                                            Map.of(),
                                            Map.of("error", true),
                                            d,
                                            p))
                    .isInstanceOfSatisfying(
                            PlatformException.class,
                            e -> assertThat(e.code()).isEqualTo("AUTHORITY_EXPIRED"));
            f.ds.beforeSql = sql -> {};
            assertThat(FlowMaterialTransactionsPostgresTest.facts(f, run)).isEqualTo(before);
            assertThat(f.store.load(run).blockedReason()).isNull();
            assertThatCode(() -> current(f, run)).doesNotThrowAnyException();
        }
    }

    @Test
    void aggregateSeesItsOwnPrepareChangesAndDistinguishesSqlNullEndFromJsonNull()
            throws Exception {
        for (boolean jsonNull : List.of(false, true))
            try (var f = fixture()) {
                long run =
                        insert(
                                f,
                                "{\"nodes\":[{\"id\":\"first\",\"type\":\"END\"},{\"id\":\"last\",\"type\":\"CODE\",\"expression\":\"9\"}],\"edges\":[]}");
                var t = FlowMaterialScopePostgresTest.trace(f);
                var d = dispatch(f, run);
                t.startOwnedFlow(d, current(f, run));
                for (long node :
                        f.db.queryForList(
                                "select id from engine.node_instance order by step_seq",
                                Long.class)) {
                    var p = current(f, run);
                    var work = t.prepareAndClaimFlow(node, "worker", d, p);
                    t.commitFlow(
                            node,
                            TraceStore.number(work.node(), "attemptId"),
                            work.node().get("lease_owner").toString(),
                            node == 1 ? null : 9,
                            d,
                            p);
                }
                var p = current(f, run);
                if (!jsonNull) {
                    f.tx.setIsolationLevel(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
                    f.tx.execute(
                            status -> {
                                var b = RunAuthorityStore.load(f.db, run, true);
                                var ms = new MaterialScopeStore(f.ds, f.iam.clock());
                                ms.requireWriter(b, p, false);
                                var parents = ms.currentScopes(b);
                                f.db.update(
                                        "update engine.node_instance set output=null where"
                                                + " node_def_id='first'");
                                ms.append(
                                        b,
                                        "node-output",
                                        f.db.queryForObject(
                                                "select id::text from engine.node_instance where"
                                                        + " node_def_id='first'",
                                                String.class),
                                        p,
                                        parents);
                                return null;
                            });
                }
                assertThat(t.aggregateFlow(d, p)).isEqualTo("SUCCEEDED");
                assertThat(
                                f.db.queryForObject(
                                        "select output::text from engine.run where id=?",
                                        String.class,
                                        run))
                        .isEqualTo(jsonNull ? "null" : "9");
                assertThat(t.aggregateFlow(d, p)).isEqualTo("SUCCEEDED");
                assertThat(
                                f.db.queryForObject(
                                        "select count(*) from engine.run_event where"
                                                + " type='RUN_FINISHED'",
                                        Long.class))
                        .isEqualTo(1);
            }
        try (var f = fixture()) {
            long run =
                    insert(
                            f,
                            "{\"nodes\":[{\"id\":\"c\",\"type\":\"CONDITION\",\"branches\":[{\"else\":\"other\"}]},{\"id\":\"a\",\"type\":\"END\"},{\"id\":\"b\",\"type\":\"END\"}],\"edges\":[{\"from\":\"c\",\"to\":\"a\"},{\"from\":\"a\",\"to\":\"b\"}]}");
            var t = FlowMaterialScopePostgresTest.trace(f);
            var d = dispatch(f, run);
            t.startOwnedFlow(d, current(f, run));
            var p = current(f, run);
            long node =
                    f.db.queryForObject(
                            "select id from engine.node_instance where node_def_id='c'",
                            Long.class);
            var work = t.prepareAndClaimFlow(node, "worker", d, p);
            t.commitFlow(
                    node,
                    TraceStore.number(work.node(), "attemptId"),
                    work.node().get("lease_owner").toString(),
                    Map.of("selected", "other"),
                    d,
                    p);
            assertThat(t.aggregateFlow(d, current(f, run))).isEqualTo("SUCCEEDED");
            assertThat(states(f)).containsEntry("a", "SKIPPED").containsEntry("b", "SKIPPED");
            assertThat(
                            f.db.queryForObject(
                                    "select count(*) from engine.run_event where"
                                            + " type='NODE_SKIPPED'",
                                    Long.class))
                    .isEqualTo(2);
        }
    }

    @Test
    void realPgSerializationFailureKeepsOriginalTypeAndRollsBackInsteadOfFallbackOr503()
            throws Exception {
        try (var f = fixture();
                var executor = Executors.newSingleThreadExecutor()) {
            long run = insert(f, SIMPLE);
            var t = FlowMaterialScopePostgresTest.trace(f);
            var d = dispatch(f, run);
            var p = current(f, run);
            var arrived = new CountDownLatch(1);
            var pid = new AtomicInteger();
            f.ds.beforeRunLock =
                    id -> {
                        pid.set(id);
                        arrived.countDown();
                    };
            try (var blocker = f.ds.getConnection()) {
                blocker.setAutoCommit(false);
                try (var lock =
                        blocker.prepareStatement(
                                "select id from engine.run where id=? for update")) {
                    lock.setLong(1, run);
                    try (var rows = lock.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                    }
                    var future =
                            executor.submit(() -> catchThrowable(() -> t.startOwnedFlow(d, p)));
                    assertThat(arrived.await(5, TimeUnit.SECONDS)).isTrue();
                    boolean waiting = false;
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    do {
                        waiting =
                                Boolean.TRUE.equals(
                                        f.db.queryForObject(
                                                "select exists(select 1 from pg_stat_activity where"
                                                        + " pid=? and wait_event_type='Lock')",
                                                Boolean.class,
                                                pid.get()));
                        if (!waiting) Thread.sleep(10);
                    } while (!waiting && System.nanoTime() < until);
                    assertThat(waiting).isTrue();
                    try (var update =
                            blocker.prepareStatement(
                                    "update engine.run set dispatch_next_at=clock_timestamp() where"
                                            + " id=?")) {
                        update.setLong(1, run);
                        update.executeUpdate();
                    }
                    blocker.commit();
                    assertThat(future.get(5, TimeUnit.SECONDS))
                            .isInstanceOf(org.springframework.dao.CannotAcquireLockException.class);
                    System.out.println("N2_ACTUAL_RR_SERIALIZATION=true");
                } finally {
                    blocker.rollback();
                }
            }
            assertThat(
                            f.db.queryForObject(
                                    "select status from engine.run where id=?", String.class, run))
                    .isEqualTo("PENDING");
            assertThat(f.db.queryForObject("select count(*) from engine.run_event", Long.class))
                    .isZero();
            assertThat(f.store.load(run).blockedReason()).isNull();
        }
    }

    @Test
    void aggregateOutputAndTerminalEventScopeFaultsRollBackEntireAggregate() throws Exception {
        for (String kind : List.of("node-state", "run-state", "run-output", "run-event"))
            try (var f = fixture()) {
                long run =
                        insert(
                                f,
                                "{\"nodes\":[{\"id\":\"c\",\"type\":\"CONDITION\"},{\"id\":\"a\",\"type\":\"END\"},{\"id\":\"b\",\"type\":\"END\"}],\"edges\":[{\"from\":\"c\",\"to\":\"a\"},{\"from\":\"a\",\"to\":\"b\"}]}");
                var t = FlowMaterialScopePostgresTest.trace(f);
                var d = dispatch(f, run);
                t.startOwnedFlow(d, current(f, run));
                var p = current(f, run);
                long node =
                        f.db.queryForObject(
                                "select id from engine.node_instance where node_def_id='c'",
                                Long.class);
                var work = t.prepareAndClaimFlow(node, "worker", d, p);
                t.commitFlow(
                        node,
                        TraceStore.number(work.node(), "attemptId"),
                        work.node().get("lease_owner").toString(),
                        Map.of("selected", "elsewhere"),
                        d,
                        p);
                var proof = current(f, run);
                var before = FlowMaterialTransactionsPostgresTest.facts(f, run);
                fault(f, kind, "true");
                try {
                    assertThatThrownBy(() -> t.aggregateFlow(d, proof))
                            .isInstanceOf(org.springframework.dao.DataAccessException.class);
                } finally {
                    unfault(f);
                }
                assertThat(FlowMaterialTransactionsPostgresTest.facts(f, run)).isEqualTo(before);
            }
    }

    @Test
    void budgetStopRejectsCallerSuppliedBudgetReasonWhenActualBudgetIsStillAvailable()
            throws Exception {
        try (var f = fixture()) {
            long run = insert(f, SIMPLE);
            var t = FlowMaterialScopePostgresTest.trace(f);
            var d = dispatch(f, run);
            t.startOwnedFlow(d, current(f, run));
            var p = current(f, run);
            var before = FlowMaterialTransactionsPostgresTest.facts(f, run);
            assertThatThrownBy(() -> t.stopFlowForBudget(d, p, "BUDGET_STEPS"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(FlowMaterialTransactionsPostgresTest.facts(f, run)).isEqualTo(before);
        }
    }
}
