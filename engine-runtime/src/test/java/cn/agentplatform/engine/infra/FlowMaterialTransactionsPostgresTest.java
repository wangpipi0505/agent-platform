package cn.agentplatform.engine.infra;

import static cn.agentplatform.engine.infra.FlowMaterialScopePostgresTest.*;

import static org.assertj.core.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

class FlowMaterialTransactionsPostgresTest {
    @Test
    void durableBlockingCasCannotPassAnInFlightAuthorizedMutation() throws Exception {
        try (var f = fixture();
                var executor = Executors.newFixedThreadPool(2)) {
            var i = insert(f);
            var proof = current(f, i.run());
            var before = f.store.load(i.run());
            var payload = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var update = new CountDownLatch(1);
            var pid = new java.util.concurrent.atomic.AtomicInteger();
            var once = new java.util.concurrent.atomic.AtomicBoolean();
            f.ds.beforePayload =
                    () -> {
                        if (once.compareAndSet(false, true)) {
                            payload.countDown();
                            try {
                                if (!release.await(15, TimeUnit.SECONDS))
                                    throw new AssertionError("release timeout");
                            } catch (InterruptedException e) {
                                throw new AssertionError(e);
                            }
                        }
                    };
            var writer =
                    executor.submit(() -> trace(f).event(i.run(), "SERIALIZED", Map.of(), proof));
            Future<Boolean> blocking = null;
            try {
                assertThat(payload.await(5, TimeUnit.SECONDS)).isTrue();
                f.ds.beforeAuthorityUpdate =
                        p -> {
                            pid.set(p);
                            update.countDown();
                        };
                blocking =
                        executor.submit(
                                () ->
                                        f.store.recordCheck(
                                                before,
                                                null,
                                                "AUTHORITY_DENIED",
                                                Duration.ofSeconds(3)));
                assertThat(update.await(5, TimeUnit.SECONDS)).isTrue();
                boolean waiting = false;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                do {
                    waiting =
                            Boolean.TRUE.equals(
                                    f.db.queryForObject(
                                            "select exists(select 1 from pg_stat_activity where"
                                                + " pid=? and wait_event_type='Lock' and query like"
                                                + " 'update engine.run_authority set"
                                                + " last_decision_id=%')",
                                            Boolean.class, pid.get()));
                    if (blocking.isDone()) break;
                    if (!waiting) Thread.sleep(20);
                } while (!waiting && System.nanoTime() < deadline);
                System.out.println(
                        "N1_BLOCK_RACE_CAS_DONE="
                                + blocking.isDone()
                                + ",BLOCKED="
                                + f.store.load(i.run()).blockedReason());
                assertThat(waiting)
                        .as("blocking CAS must wait on the authorized writer's metadata lock")
                        .isTrue();
            } finally {
                release.countDown();
            }
            assertThat(writer.get(6, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(blocking.get(6, TimeUnit.SECONDS)).isTrue();
            assertThat(f.store.load(i.run()).blockedReason()).isEqualTo("AUTHORITY_DENIED");
        }
    }

    static void fault(RunAuthorityFixture f, String kind) {
        if (!Set.of(
                        "run-budget",
                        "node-output",
                        "attempt-request",
                        "attempt-error",
                        "run-event",
                        "node-error",
                        "run-error")
                .contains(kind)) throw new AssertionError();
        f.db.execute(
                "create function engine.n1_writer_fault() returns trigger language plpgsql as $$"
                        + " begin if NEW.kind='"
                        + kind
                        + "' then raise exception 'N1_WRITER_FAULT'; end if; return NEW; end $$");
        f.db.execute(
                "create trigger n1_writer_fault before insert on engine.material_scope for each row"
                        + " execute function engine.n1_writer_fault()");
    }

    static void unfault(RunAuthorityFixture f) {
        f.db.execute("drop trigger n1_writer_fault on engine.material_scope");
        f.db.execute("drop function engine.n1_writer_fault()");
    }

    static List<Map<String, Object>> facts(RunAuthorityFixture f, long id) {
        return f.db.queryForList(
                "select status,event_seq,budget::text,(select count(*) from engine.material_scope"
                    + " where run_id=r.id) as scopes,(select count(*) from engine.run_event where"
                    + " run_id=r.id) as events,(select"
                    + " jsonb_agg(jsonb_build_object('id',n.id,'status',n.status,'output',n.output,'error',n.error,'lease',n.lease_owner,'committed',n.committed_attempt_id))"
                    + " from engine.node_instance n where n.run_id=r.id) as nodes,(select"
                    + " jsonb_agg(jsonb_build_object('id',a.id,'status',a.status,'error',a.error))"
                    + " from engine.attempt a join engine.node_instance n on"
                    + " n.id=a.node_instance_id where n.run_id=r.id) as attempts from engine.run r"
                    + " where id=?",
                id);
    }

    @Test
    void everyActualWriterRollsBackPayloadScopeCasAndEventsOnScopeInsertFault() throws Exception {
        for (String action : List.of("claim", "commit", "fail", "event", "consume"))
            try (var f = fixture()) {
                var i = insert(f);
                runningFixture(f, i.run());
                var trace = trace(f);
                var proof = current(f, i.run());
                Map<String, Object> claim =
                        Set.of("commit", "fail").contains(action)
                                ? trace.claim(i.node(), "worker", proof)
                                : Map.of();
                var before = facts(f, i.run());
                fault(
                        f,
                        switch (action) {
                            case "claim" -> "attempt-request";
                            case "commit" -> "node-output";
                            case "fail" -> "attempt-error";
                            case "consume" -> "run-budget";
                            default -> "run-event";
                        });
                try {
                    Runnable write =
                            switch (action) {
                                case "claim" -> () -> trace.claim(i.node(), "worker", proof);
                                case "commit" ->
                                        () ->
                                                trace.commit(
                                                        i.run(),
                                                        i.node(),
                                                        TraceStore.number(claim, "attemptId"),
                                                        (String) claim.get("lease_owner"),
                                                        Map.of("value", 123),
                                                        proof);
                                case "fail" ->
                                        () ->
                                                trace.fail(
                                                        i.run(),
                                                        i.node(),
                                                        TraceStore.number(claim, "attemptId"),
                                                        (String) claim.get("lease_owner"),
                                                        Map.of("error", "failed"),
                                                        proof);
                                case "consume" ->
                                        () -> trace.consume(i.run(), "tokens", 2, 200000, proof);
                                default ->
                                        () ->
                                                trace.event(
                                                        i.run(),
                                                        "FAULT",
                                                        Map.of("value", 123),
                                                        proof);
                            };
                    Throwable failure = catchThrowable(write::run);
                    assertThat(failure)
                            .isInstanceOf(org.springframework.dao.DataAccessException.class)
                            .hasMessageContaining("N1_WRITER_FAULT");
                    // The caller has caught the error. The top-level owning method already rolled
                    // back everything.
                    assertThat(facts(f, i.run())).isEqualTo(before);
                } finally {
                    unfault(f);
                }
            }
    }

    @Test
    void newRunAndNewNodeInitializationCannotCommitHalfScopesWhenCallerCatches() throws Exception {
        for (String kind : List.of("run-error", "node-error"))
            try (var f = fixture()) {
                long before = f.count();
                fault(f, kind);
                try {
                    assertThatThrownBy(
                                    () ->
                                            f.admitted(
                                                    p ->
                                                            f.tx.execute(
                                                                    s -> {
                                                                        try {
                                                                            long run = f.create(p);
                                                                            trace(f).createNode(
                                                                                            run,
                                                                                            "n",
                                                                                            1,
                                                                                            "START",
                                                                                            Json
                                                                                                    .read(
                                                                                                            "{}"),
                                                                                            p);
                                                                        } catch (
                                                                                org.springframework
                                                                                                .dao
                                                                                                .DataAccessException
                                                                                        expected) {
                                                                            assertThat(expected)
                                                                                    .hasMessageContaining(
                                                                                            "N1_WRITER_FAULT");
                                                                        }
                                                                        return null;
                                                                    })))
                            .isInstanceOf(
                                    org.springframework.transaction.UnexpectedRollbackException
                                            .class);
                    assertThat(f.count()).isEqualTo(before);
                } finally {
                    unfault(f);
                }
            }
    }

    @Test
    void exactOldLocalObservationIsRejectedAfterNewCurrentCasAndUnavailableBlock()
            throws Exception {
        try (var f = fixture()) {
            var i = insert(f);
            var old = current(f, i.run());
            var fresh = current(f, i.run());
            f.ds.payloadReads.set(0);
            assertThatThrownBy(() -> trace(f).event(i.run(), "OLD", Map.of(), old))
                    .isInstanceOf(PlatformException.class);
            assertThat(f.ds.payloadReads.get()).isZero();
            assertThat(
                            f.store.recordCheck(
                                    f.store.load(i.run()),
                                    null,
                                    "AUTHORITY_UNAVAILABLE",
                                    Duration.ofSeconds(3)))
                    .isTrue();
            assertThatThrownBy(() -> trace(f).event(i.run(), "BLOCKED", Map.of(), fresh))
                    .isInstanceOf(PlatformException.class);
            assertThat(f.ds.payloadReads.get()).isZero();
        }
    }

    @Test
    void workerProofExpiryDuringActualRunLockWaitRejectsBeforePayload() throws Exception {
        try (var f = fixture();
                var executor = Executors.newSingleThreadExecutor()) {
            var i = insert(f);
            var proof = current(f, i.run());
            var pid = new java.util.concurrent.atomic.AtomicInteger();
            f.ds.payloadReads.set(0);
            try (var blocker = f.ds.getConnection()) {
                blocker.setAutoCommit(false);
                try (var lock =
                        blocker.prepareStatement(
                                "select id from engine.run where id=? for update")) {
                    lock.setLong(1, i.run());
                    try (var rows = lock.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                    }
                    var workerArrived = new CountDownLatch(1);
                    f.ds.beforeRunLock =
                            p -> {
                                pid.set(p);
                                workerArrived.countDown();
                            };
                    var future =
                            executor.submit(
                                    () ->
                                            catchThrowable(
                                                    () ->
                                                            trace(f).event(
                                                                            i.run(), "EXPIRED",
                                                                            Map.of(), proof)));
                    try {
                        assertThat(workerArrived.await(5, TimeUnit.SECONDS)).isTrue();
                        boolean waiting = false;
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                        do {
                            waiting =
                                    Boolean.TRUE.equals(
                                            f.db.queryForObject(
                                                    "select wait_event_type='Lock' and"
                                                        + " query='select id from engine.run where"
                                                        + " id=$1 for update' from pg_stat_activity"
                                                        + " where pid=?",
                                                    Boolean.class,
                                                    pid.get()));
                            if (!waiting) Thread.sleep(20);
                        } while (!waiting && System.nanoTime() < deadline);
                        assertThat(waiting).as("actual protected run lock observed").isTrue();
                        f.iam.advanceClock(Duration.ofSeconds(6));
                    } finally {
                        blocker.rollback();
                    }
                    assertThat(future.get(6, TimeUnit.SECONDS))
                            .isInstanceOfSatisfying(
                                    PlatformException.class,
                                    e -> assertThat(e.code()).isEqualTo("AUTHORITY_EXPIRED"));
                } finally {
                    blocker.rollback();
                }
            }
            assertThat(f.ds.payloadReads.get()).isZero();
            assertThat(
                            f.db.queryForObject(
                                    "select event_seq from engine.run where id=?",
                                    Integer.class,
                                    i.run()))
                    .isZero();
        }
    }

    @Test
    void expiryAfterPayloadRollsBackButExpiryAfterActualWriteCommitRetainsHistoricalFacts()
            throws Exception {
        for (boolean afterCommit : List.of(false, true))
            try (var f = fixture()) {
                var i = insert(f);
                var proof = current(f, i.run());
                var once = new java.util.concurrent.atomic.AtomicBoolean();
                Runnable expire =
                        () -> {
                            if (once.compareAndSet(false, true))
                                f.iam.advanceClock(Duration.ofSeconds(6));
                        };
                if (afterCommit) f.ds.afterMaterialCommit = expire;
                else f.ds.afterPayload = expire;
                assertThatThrownBy(
                                () ->
                                        trace(f).event(
                                                        i.run(),
                                                        "EXPIRY_FACT",
                                                        Map.of("ok", true),
                                                        proof))
                        .isInstanceOfSatisfying(
                                PlatformException.class,
                                e -> assertThat(e.code()).isEqualTo("AUTHORITY_EXPIRED"));
                assertThat(once).isTrue();
                assertThat(
                                f.db.queryForObject(
                                        "select event_seq from engine.run where id=?",
                                        Integer.class,
                                        i.run()))
                        .isEqualTo(afterCommit ? 1 : 0);
                assertThat(
                                f.db.queryForObject(
                                        "select count(*) from engine.run_event where run_id=?",
                                        Integer.class,
                                        i.run()))
                        .isEqualTo(afterCommit ? 1 : 0);
                assertThat(
                                f.db.queryForObject(
                                        "select count(*) from engine.material_scope where run_id=?"
                                                + " and kind='run-event'",
                                        Integer.class,
                                        i.run()))
                        .isEqualTo(afterCommit ? 1 : 0);
            }
    }

    @Test
    void workerRejectsAmbientRepeatableReadAndSerializableRatherThanPretendingItObservedCommit()
            throws Exception {
        try (var f = fixture()) {
            var i = insert(f);
            var proof = current(f, i.run());
            for (int isolation : new int[] {4, 8}) {
                f.tx.setIsolationLevel(isolation);
                assertThatThrownBy(
                                () ->
                                        f.tx.execute(
                                                s ->
                                                        trace(f).event(
                                                                        i.run(), "NO_JOIN",
                                                                        Map.of(), proof)))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("PROTECTED_WRITE_REQUIRES_TOP_LEVEL_TRANSACTION");
            }
        }
    }

    @Test
    void derivedOnlyLabelSurvivesLaterCurrentThatNoLongerCarriesItThroughRealParents()
            throws Exception {
        try (var f = fixture()) {
            var i = insert(f);
            f.iam.decisionMutation(
                    c -> {
                        if (c.containsKey("attributes"))
                            c.put(
                                    "attributes",
                                    Map.of(
                                            "region",
                                            Set.of("A", "B"),
                                            "dataLabels",
                                            Set.of("initial", "derivedOnly")));
                    });
            var first = current(f, i.run());
            trace(f).event(i.run(), "FIRST", Map.of(), first);
            String parent =
                    f.db.queryForObject(
                            "select scope_sha256 from engine.material_scope where run_id=? and"
                                    + " kind='run-state' order by material_revision desc limit 1",
                            String.class,
                            i.run());
            f.iam.decisionMutation(c -> {});
            var second = current(f, i.run());
            assertThat(second.current().actor().attributes().get("dataLabels"))
                    .doesNotContain("derivedOnly");
            trace(f).event(i.run(), "SECOND", Map.of(), second);
            var ms = new MaterialScopeStore(f.ds, f.iam.clock());
            var latest =
                    ms.currentRefs(i.run(), second).stream()
                            .filter(r -> r.kind().equals("run-state"))
                            .findFirst()
                            .orElseThrow();
            var scope = ms.read(latest, second).scope();
            assertThat(scope.parentScopeSha256()).contains(parent);
            assertThat(scope.requiredDataLabels()).contains("derivedOnly", "initial", "config");
            assertThat(f.store.load(i.run()).scopes())
                    .allSatisfy(
                            s -> assertThat(s.requiredDataLabels()).doesNotContain("derivedOnly"));
        }
    }

    @Test
    void failedGenerationKeepsOriginalAttemptFailureFactWithoutLogicalFailureEvent()
            throws Exception {
        try (var f = fixture()) {
            var i = insert(f);
            runningFixture(f, i.run());
            var proof = current(f, i.run());
            var trace = trace(f);
            var claim = trace.claim(i.node(), "worker", proof);
            long attempt = TraceStore.number(claim, "attemptId");
            trace.fail(
                    i.run(),
                    i.node(),
                    attempt,
                    "other-generation",
                    Map.of("failure", "late"),
                    proof);
            assertThat(
                            f.db.queryForObject(
                                    "select status from engine.node_instance where id=?",
                                    String.class,
                                    i.node()))
                    .isEqualTo("RUNNING");
            assertThat(
                            f.db.queryForObject(
                                    "select status from engine.attempt where id=?",
                                    String.class,
                                    attempt))
                    .isEqualTo("FAILED");
            assertThat(
                            f.db.queryForList(
                                    "select type from engine.run_event where run_id=?",
                                    String.class,
                                    i.run()))
                    .containsExactly("NODE_STARTED");
            var ms = new MaterialScopeStore(f.ds, f.iam.clock());
            var error =
                    ms.currentRefs(i.run(), proof).stream()
                            .filter(r -> r.kind().equals("attempt-error"))
                            .findFirst()
                            .orElseThrow();
            assertThat(
                            ((com.fasterxml.jackson.databind.JsonNode)
                                            ms.read(error, proof).payload())
                                    .path("failure")
                                    .asText())
                    .isEqualTo("late");
        }
    }

    @Test
    void migration217KeepsOriginalFormatsAppendOnlyAndActualHistory() throws Exception {
        try (var f = fixture()) {
            var i = insert(f);
            var proof = current(f, i.run());
            trace(f).event(i.run(), "REVISION", Map.of(), proof);
            assertThat(
                            f.db.queryForObject(
                                    "select max(version::int) from engine.flyway_schema_history"
                                            + " where version is not null and success",
                                    Integer.class))
                    .isEqualTo(218);
            var rows =
                    f.db.queryForList(
                            "select kind,material_version,material_revision from"
                                    + " engine.material_scope where run_id=? order by"
                                    + " material_revision",
                            i.run());
            assertThat(rows)
                    .filteredOn(r -> MaterialScopeStore.KINDS.contains(r.get("kind")))
                    .hasSize(3)
                    .allSatisfy(
                            r ->
                                    assertThat((String) r.get("material_version"))
                                            .matches("[a-f0-9]{64}"));
            assertThat(rows)
                    .filteredOn(r -> !MaterialScopeStore.KINDS.contains(r.get("kind")))
                    .allSatisfy(
                            r ->
                                    assertThat(r.get("material_version"))
                                            .isEqualTo(r.get("material_revision").toString()));
            System.out.println(
                    "N1_FINAL_HISTORY="
                            + f.db.queryForList(
                                    "select version,description,success from"
                                        + " engine.flyway_schema_history order by installed_rank"));
            System.out.println(
                    "N1_FINAL_CONSTRAINTS="
                            + f.db.queryForList(
                                    "select conname,pg_get_constraintdef(oid) as definition from"
                                            + " pg_constraint where"
                                            + " conrelid='engine.material_scope'::regclass order by"
                                            + " conname"));
            System.out.println(
                    "N1_FINAL_INDEXES="
                            + f.db.queryForList(
                                    "select indexdef from pg_indexes where schemaname='engine' and"
                                            + " tablename='material_scope' order by indexname"));
            System.out.println(
                    "N1_FINAL_TRIGGERS="
                            + f.db.queryForList(
                                    "select tgname,tgenabled from pg_trigger where tgrelid in"
                                        + " ('engine.material_scope'::regclass,'engine.run_authority'::regclass)"
                                        + " and not tgisinternal order by tgname"));
            assertThatThrownBy(
                            () ->
                                    f.db.update(
                                            "update engine.material_scope set payload_sha256=?"
                                                    + " where run_id=?",
                                            Digest.sha256("invalid"),
                                            i.run()))
                    .hasMessageContaining("MATERIAL_SCOPE_IMMUTABLE");
            assertThatThrownBy(
                            () ->
                                    f.db.update(
                                            "delete from engine.material_scope where run_id=?",
                                            i.run()))
                    .hasMessageContaining("MATERIAL_SCOPE_IMMUTABLE");
            assertThatThrownBy(
                            () ->
                                    f.db.update(
                                            "insert into"
                                                + " engine.material_scope(run_id,tenant_id,producer,kind,material_id,material_version,payload_sha256,scope_sha256,scope)"
                                                + " values(?,'tenant-a','engine','run-output','n1-invalid','01',?,?,'{}')",
                                            i.run(),
                                            Digest.sha256("payload"),
                                            Digest.sha256("invalid-scope")))
                    .hasMessageContaining("material_scope_material_version_check");
        }
    }
}
