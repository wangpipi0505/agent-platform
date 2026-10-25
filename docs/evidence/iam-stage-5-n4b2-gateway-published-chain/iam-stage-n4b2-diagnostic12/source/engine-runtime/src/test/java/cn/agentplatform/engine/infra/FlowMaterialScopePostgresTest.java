package cn.agentplatform.engine.infra;

import static org.assertj.core.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.common.authority.HttpCurrentAuthorityClient.RetainedAuthorization;
import cn.agentplatform.engine.application.RunAuthority;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.time.*;
import java.util.*;

class FlowMaterialScopePostgresTest {
    static RunAuthorityFixture fixture() throws Exception {
        return new RunAuthorityFixture("agent_platform_iam_flow_material_v12");
    }

    static TraceStore trace(RunAuthorityFixture f) {
        return new TraceStore(f.db, new DataSourceTransactionManager(f.ds), f.iam.clock());
    }

    static RetainedAuthorization current(RunAuthorityFixture f, long id) {
        return new RunAuthority(f.store, f.engine, f.iam.clock(), Duration.ofSeconds(3))
                .currentRetained(id, RunAuthorityFixture.LOCAL);
    }

    record Inserted(long run, long node) {}

    static Inserted insert(RunAuthorityFixture f) throws Exception {
        return f.admitted(
                p ->
                        f.tx.execute(
                                s -> {
                                    long run = f.create(p);
                                    long node =
                                            trace(f).createNode(
                                                            run,
                                                            "n1",
                                                            1,
                                                            "START",
                                                            Json.read(
                                                                    "{\"id\":\"n1\",\"type\":\"START\"}"),
                                                            p);
                                    return new Inserted(run, node);
                                }));
    }

    static void runningFixture(RunAuthorityFixture f, long id) {
        // Finite PG dispatch fixture only: N2 will own the actual scheduler transition and its
        // scope.
        var proof = current(f, id);
        f.tx.setIsolationLevel(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
        f.tx.execute(
                s -> {
                    var b = RunAuthorityStore.load(f.db, id, true);
                    var ms = new MaterialScopeStore(f.ds, f.iam.clock());
                    ms.requireWriter(b, proof, false);
                    var consumed = ms.currentScopes(b);
                    f.db.update(
                            "update engine.run set status='RUNNING' where id=? and"
                                    + " status='PENDING'",
                            id);
                    ms.append(b, "run-state", b.publicId(), proof, consumed);
                    return null;
                });
    }

    @Test
    void realAdmissionRetainsThreeInitialsAndAddsFourActualNullableRunMaterials() throws Exception {
        try (var f = fixture()) {
            long id = f.admitted(p -> f.tx.execute(s -> f.create(p)));
            assertThat(f.store.load(id).scopes()).hasSize(3);
            assertThat(
                            f.db.queryForList(
                                    "select kind from engine.material_scope where run_id=? order by"
                                            + " kind",
                                    String.class,
                                    id))
                    .containsExactly(
                            "run-budget",
                            "run-error",
                            "run-inputs",
                            "run-output",
                            "run-snapshot",
                            "run-state",
                            "run-version-refs");
        }
    }

    @Test
    void protectedLegacyEntryPointsDenyBeforeAnyPayloadReadOrMutation() throws Exception {
        try (var f = fixture()) {
            long id = f.admitted(p -> f.tx.execute(s -> f.create(p)));
            var b = f.store.load(id);
            var trace = trace(f);
            assertThatThrownBy(
                            () ->
                                    trace.run(
                                            b.publicId(),
                                            RunAuthorityFixture.actor(java.util.Set.of("A", "B"))))
                    .isInstanceOf(PlatformException.class);
            assertThatThrownBy(() -> trace.nodes(id)).isInstanceOf(PlatformException.class);
            assertThatThrownBy(() -> trace.events(id, 0)).isInstanceOf(PlatformException.class);
            assertThatThrownBy(() -> trace.event(id, "BAD", java.util.Map.of()))
                    .isInstanceOf(PlatformException.class);
            assertThat(
                            f.db.queryForObject(
                                    "select event_seq from engine.run where id=?",
                                    Integer.class,
                                    id))
                    .isZero();
        }
    }

    @Test
    void actualClaimCommitAndEventsAppendRevisionsWithRealParentsAndPreciseNullTimePayloads()
            throws Exception {
        try (var f = fixture()) {
            var inserted = insert(f);
            runningFixture(f, inserted.run());
            var trace = trace(f);
            var proof = current(f, inserted.run());
            var materials = new MaterialScopeStore(f.ds, f.iam.clock());
            var old =
                    materials.currentRefs(inserted.run(), proof).stream()
                            .filter(r -> r.kind().equals("node-state"))
                            .findFirst()
                            .orElseThrow();
            var claimed = trace.claim(inserted.node(), "worker", proof);
            long attempt = TraceStore.number(claimed, "attemptId");
            String owner = (String) claimed.get("lease_owner");
            assertThat(owner).startsWith("worker_");
            var refs = materials.currentRefs(inserted.run(), proof);
            assertThat(refs).filteredOn(r -> r.kind().startsWith("attempt-")).hasSize(4);
            for (String kind : List.of("attempt-request", "attempt-checkpoint", "attempt-error")) {
                var ref =
                        refs.stream().filter(r -> r.kind().equals(kind)).findFirst().orElseThrow();
                var payload = (JsonNode) materials.read(ref, proof).payload();
                assertThat(payload.isNull()).isEqualTo(kind.equals("attempt-error"));
                if (!kind.equals("attempt-error"))
                    assertThat(payload.isObject() && payload.isEmpty()).isTrue();
            }
            f.ds.payloadReads.set(0);
            assertThatThrownBy(() -> materials.read(old, proof))
                    .isInstanceOf(PlatformException.class);
            assertThat(f.ds.payloadReads.get()).isZero();
            var exact =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .enable(
                                    com.fasterxml.jackson.databind.DeserializationFeature
                                            .USE_BIG_DECIMAL_FOR_FLOATS)
                            .readTree("{\"value\":0.123456789012345678901,\"empty\":null}");
            String consumedState =
                    f.db.queryForObject(
                            "select scope_sha256 from engine.material_scope where run_id=? and"
                                    + " kind='node-state' order by material_revision desc limit 1",
                            String.class,
                            inserted.run());
            assertThat(trace.commit(inserted.run(), inserted.node(), attempt, owner, exact, proof))
                    .isTrue();
            refs = materials.currentRefs(inserted.run(), proof);
            var output =
                    refs.stream()
                            .filter(r -> r.kind().equals("node-output"))
                            .findFirst()
                            .orElseThrow();
            assertThat(
                            ((JsonNode) materials.read(output, proof).payload())
                                    .path("value")
                                    .decimalValue()
                                    .toPlainString())
                    .isEqualTo("0.123456789012345678901");
            var state =
                    refs.stream()
                            .filter(r -> r.kind().equals("node-state"))
                            .findFirst()
                            .orElseThrow();
            assertThat(
                            ((JsonNode) materials.read(state, proof).payload())
                                    .path("ended_at")
                                    .asText())
                    .endsWith("Z");
            assertThat(f.store.load(inserted.run()).scopes()).hasSize(3);
            var scope = materials.read(output, proof).scope();
            assertThat(scope.requiredDataLabels()).contains("initial", "config");
            assertThat(scope.parentScopeSha256()).contains(consumedState);
            assertThat(
                            f.db.queryForList(
                                    "select type from engine.run_event where run_id=? order by seq",
                                    String.class,
                                    inserted.run()))
                    .containsExactly("NODE_STARTED", "LOGIC_RESULT_COMMITTED", "NODE_FINISHED");
            long before =
                    f.db.queryForObject(
                            "select count(*) from engine.material_scope where run_id=?",
                            Long.class,
                            inserted.run());
            assertThat(
                            trace.commit(
                                    inserted.run(),
                                    inserted.node(),
                                    attempt,
                                    owner,
                                    Map.of("bad", true),
                                    proof))
                    .isFalse();
            assertThat(
                            f.db.queryForObject(
                                    "select count(*) from engine.material_scope where run_id=?",
                                    Long.class,
                                    inserted.run()))
                    .isEqualTo(before);
            assertThat(
                            f.db.queryForObject(
                                    "select count(*) from engine.attempt_note where attempt_id=?"
                                            + " and kind='SUPERSEDED_SUBMIT'",
                                    Integer.class,
                                    attempt))
                    .isEqualTo(1);
        }
    }

    @Test
    void directSignedLocalProofCannotBypassSuccessfulCurrentCasOrPersistedBlock() throws Exception {
        try (var f = fixture()) {
            var i = insert(f);
            var b = f.store.load(i.run());
            var ticket = f.engine.resume(b.delegation(), RunAuthorityFixture.LOCAL);
            var bypass = f.engine.authorizeRetained(ticket.value(), RunAuthorityFixture.LOCAL);
            var trace = trace(f);
            f.ds.payloadReads.set(0);
            assertThatThrownBy(() -> trace.event(i.run(), "BAD", Map.of(), bypass))
                    .isInstanceOf(PlatformException.class);
            assertThat(f.ds.payloadReads.get()).isZero();
            var proper = current(f, i.run());
            assertThat(
                            f.store.recordCheck(
                                    f.store.load(i.run()),
                                    null,
                                    "AUTHORITY_DENIED",
                                    Duration.ofSeconds(3)))
                    .isTrue();
            f.ds.payloadReads.set(0);
            assertThatThrownBy(() -> trace.event(i.run(), "BAD", Map.of(), proper))
                    .isInstanceOf(PlatformException.class);
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
    void mainCannotMutateHistoricalRunAndActorOnlyAttemptAndNodeWritesAreClosed() throws Exception {
        try (var f = fixture()) {
            var i = insert(f);
            runningFixture(f, i.run());
            var trace = trace(f);
            var proof = current(f, i.run());
            var claim = trace.claim(i.node(), "worker", proof);
            long a = TraceStore.number(claim, "attemptId");
            f.admitted(
                    p -> {
                        assertThatThrownBy(() -> trace.event(i.run(), "BAD", Map.of(), p))
                                .isInstanceOf(PlatformException.class);
                        return null;
                    });
            for (Runnable action :
                    List.<Runnable>of(
                            () -> trace.claim(i.node(), "worker"),
                            () -> trace.commit(i.run(), i.node(), a, "worker", Map.of()),
                            () -> trace.fail(i.run(), i.node(), a, "worker", Map.of()),
                            () -> trace.freeze(a, Map.of()),
                            () -> trace.response(a, Map.of()),
                            () -> trace.consume(i.run(), "steps", 1, 25),
                            () -> trace.lockRun(i.run())))
                assertThatThrownBy(action::run).isInstanceOf(PlatformException.class);
        }
    }

    @Test
    void scopeInsertFaultRollsBackActualPayloadEvenWhenOuterCallerCatches() throws Exception {
        try (var f = fixture()) {
            var i = insert(f);
            var trace = trace(f);
            var proof = current(f, i.run());
            f.db.execute(
                    "create function engine.n1_scope_fault() returns trigger language plpgsql as $$"
                        + " begin if NEW.kind='run-event' then raise exception 'N1_SCOPE_FAULT';"
                        + " end if; return NEW; end $$");
            f.db.execute(
                    "create trigger n1_scope_fault before insert on engine.material_scope for each"
                            + " row execute function engine.n1_scope_fault()");
            try {
                assertThatThrownBy(() -> trace.event(i.run(), "FAULT", Map.of(), proof))
                        .isInstanceOf(org.springframework.dao.DataAccessException.class)
                        .hasMessageContaining("N1_SCOPE_FAULT");
            } finally {
                f.db.execute("drop trigger n1_scope_fault on engine.material_scope");
                f.db.execute("drop function engine.n1_scope_fault()");
            }
            assertThat(
                            f.db.queryForObject(
                                    "select event_seq from engine.run where id=?",
                                    Integer.class,
                                    i.run()))
                    .isZero();
            assertThat(
                            f.db.queryForObject(
                                    "select count(*) from engine.run_event where run_id=?",
                                    Integer.class,
                                    i.run()))
                    .isZero();
        }
    }

    @Test
    void protectedWriteRejectsAmbientReadCommittedBeforePayload() throws Exception {
        try (var f = fixture()) {
            var i = insert(f);
            var proof = current(f, i.run());
            f.ds.payloadReads.set(0);
            assertThatThrownBy(
                            () ->
                                    f.tx.execute(
                                            s -> trace(f).event(i.run(), "BAD", Map.of(), proof)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("REPEATABLE_READ_REQUIRED");
            assertThat(f.ds.payloadReads.get()).isZero();
        }
    }

    @Test
    void normalLeaseRenewalDoesNotInvalidateUserFactScope() throws Exception {
        try (var f = fixture()) {
            var i = insert(f);
            runningFixture(f, i.run());
            var proof = current(f, i.run());
            var claim = trace(f).claim(i.node(), "worker", proof);
            assertThat(claim).containsKeys("lease_owner", "lease_until");
            var ms = new MaterialScopeStore(f.ds, f.iam.clock());
            var ref =
                    ms.currentRefs(i.run(), proof).stream()
                            .filter(r -> r.kind().equals("node-state"))
                            .findFirst()
                            .orElseThrow();
            // Same control-only extension as RunService.renewLeases; N2 owns its dispatch wiring.
            f.db.update(
                    "update engine.node_instance set lease_until=lease_until+interval '30 seconds'"
                            + " where id=?",
                    i.node());
            var state = (JsonNode) ms.read(ref, proof).payload();
            assertThat(state.has("lease_until")).isFalse();
            assertThat(state.has("lease_owner")).isFalse();
            assertThat(state.path("status").asText()).isEqualTo("RUNNING");
        }
    }

    @Test
    void explicitRetainedMaterialReadChecksProofAfterActualReadCommit() throws Exception {
        try (var f = fixture()) {
            var i = insert(f);
            var proof = current(f, i.run());
            var ms = new MaterialScopeStore(f.ds, f.iam.clock());
            var ref =
                    ms.currentRefs(i.run(), proof).stream()
                            .filter(r -> r.kind().equals("node-state"))
                            .findFirst()
                            .orElseThrow();
            var once = new java.util.concurrent.atomic.AtomicBoolean();
            f.ds.afterReadCommit =
                    () -> {
                        if (once.compareAndSet(false, true))
                            f.iam.advanceClock(Duration.ofSeconds(6));
                    };
            assertThatThrownBy(() -> ms.read(ref, proof))
                    .isInstanceOfSatisfying(
                            PlatformException.class,
                            e -> assertThat(e.code()).isEqualTo("AUTHORITY_EXPIRED"));
            assertThat(once).isTrue();
        }
    }

    @Test
    void mismatchedAttemptNodeRunOwnerIsMetadataDeniedBeforeAnyPayload() throws Exception {
        try (var f = fixture()) {
            var first = insert(f);
            var second = insert(f);
            runningFixture(f, second.run());
            var secondProof = current(f, second.run());
            var claim = trace(f).claim(second.node(), "worker", secondProof);
            var firstProof = current(f, first.run());
            f.ds.payloadReads.set(0);
            assertThatThrownBy(
                            () ->
                                    trace(f).commit(
                                                    first.run(),
                                                    second.node(),
                                                    TraceStore.number(claim, "attemptId"),
                                                    (String) claim.get("lease_owner"),
                                                    Map.of(),
                                                    firstProof))
                    .isInstanceOf(PlatformException.class);
            assertThat(f.ds.payloadReads.get()).isZero();
        }
    }

    @Test
    void forgedPublicVerifiedAuthorityCannotReadProtectedInitialPayload() throws Exception {
        try (var f = fixture()) {
            var i = insert(f);
            var ref = f.store.load(i.run()).scopes().getFirst().material();
            f.ds.payloadReads.set(0);
            f.admitted(
                    p -> {
                        var a = p.current();
                        var forged =
                                new VerifiedAuthority(
                                        a.actor(),
                                        a.caller(),
                                        a.call(),
                                        a.delegation(),
                                        a.authorizationVersion(),
                                        a.checkedAt(),
                                        a.expiresAt(),
                                        a.decisionId(),
                                        a.operation());
                        assertThatThrownBy(
                                        () ->
                                                new MaterialScopeStore(f.ds, f.iam.clock())
                                                        .read(ref, forged))
                                .isInstanceOf(PlatformException.class);
                        return null;
                    });
            assertThat(f.ds.payloadReads.get()).isZero();
        }
    }
}
