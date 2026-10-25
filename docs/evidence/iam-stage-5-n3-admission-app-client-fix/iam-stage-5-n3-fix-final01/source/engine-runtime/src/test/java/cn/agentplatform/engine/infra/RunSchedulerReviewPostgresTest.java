package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.*;
import cn.agentplatform.engine.domain.*;
import cn.agentplatform.engine.domain.RunDispatch.Queue;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Review counterexamples use the real session, run, dispatch and node/tool lease paths. */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class RunSchedulerReviewPostgresTest {
    private final TracePostgresTest fixture = new TracePostgresTest();
    private TraceStore store;

    @BeforeAll
    static void connect() throws Exception {
        TracePostgresTest.connect();
    }

    @BeforeEach
    void clean() {
        TracePostgresTest.db.update("delete from engine.chat_turn");
        TracePostgresTest.db.update("delete from engine.chat_session");
        fixture.clean();
        store = TracePostgresTest.store;
    }

    @AfterEach
    void cleanup() {
        clean();
    }

    private RuntimeClients clients() {
        return new RuntimeClients() {
            @Override
            public JsonNode get(String service, String path, Actor actor) {
                return RunAdmissionHttpTest.snapshot(true);
            }

            @Override
            public JsonNode post(String service, String path, Object body, Actor actor) {
                return fail("pure START/END admission must never perform a paid call");
            }
        };
    }

    @Test
    void drainBeforeFirstSessionReservationRejectsWithoutConsumingTurn() {
        var clients = clients();
        try (var scheduler =
                new RunScheduler(new RunScheduleRepository(store), 1, 1, 20, 3000, 0)) {
            var runs =
                    new RunService(store, clients, null, null, scheduler) {
                        @Override
                        public RunScheduler.Admission reserveSessionTurn() {
                            var ticket = super.reserveSessionTurn();
                            scheduler.close();
                            return ticket;
                        }
                    };
            var sessions = new ChatSessions(store, clients, runs);
            String id =
                    sessions.create("chat", TracePostgresTest.actor).get("sessionId").toString();
            var rejected =
                    assertThrows(
                            RunAdmissionRejected.class,
                            () ->
                                    sessions.turn(
                                            "chat",
                                            id,
                                            Json.read(
                                                    "{\"requestId\":\"first\",\"inputs\":{\"message\":\"hi\"}}"),
                                            TracePostgresTest.actor));
            assertEquals(503, rejected.status());
            assertEquals(
                    0,
                    store.db()
                            .queryForObject(
                                    "select count(*) from engine.chat_turn where session_id=?",
                                    Integer.class,
                                    id));
            assertEquals(
                    1,
                    store.db()
                            .queryForObject(
                                    "select next_turn from engine.chat_session where public_id=?",
                                    Integer.class,
                                    id));
            assertEquals(
                    0,
                    store.db()
                            .queryForObject(
                                    "select count(*) from engine.run where tenant_id='engine-test'",
                                    Integer.class));
            assertEquals(1, scheduler.available(Queue.INTERACTIVE));
        }
    }

    @Test
    void acceptedSessionReservationSurvivesDrainAndReplaysSamePendingRun() {
        var clients = clients();
        try (var scheduler =
                new RunScheduler(new RunScheduleRepository(store), 1, 1, 20, 3000, 0)) {
            var runs =
                    new RunService(store, clients, null, null, scheduler) {
                        @Override
                        public Map<String, Object> createReservedSessionTurn(
                                String code,
                                JsonNode request,
                                JsonNode snapshot,
                                Actor actor,
                                RunScheduler.Admission ticket) {
                            assertEquals(
                                    1,
                                    store.db()
                                            .queryForObject(
                                                    "select count(*) from engine.chat_turn where"
                                                            + " run_public_id is null",
                                                    Integer.class));
                            scheduler.close();
                            return super.createReservedSessionTurn(
                                    code, request, snapshot, actor, ticket);
                        }
                    };
            var sessions = new ChatSessions(store, clients, runs);
            String id =
                    sessions.create("chat", TracePostgresTest.actor).get("sessionId").toString();
            var request = Json.read("{\"requestId\":\"accepted\",\"inputs\":{\"message\":\"hi\"}}");
            var result =
                    assertDoesNotThrow(
                            () -> sessions.turn("chat", id, request, TracePostgresTest.actor));
            assertEquals("PENDING", result.get("status"));
            assertEquals(
                    result.get("runId"),
                    sessions.turn("chat", id, request, TracePostgresTest.actor).get("runId"));
            assertEquals(
                    result.get("runId"),
                    store.db()
                            .queryForObject(
                                    "select run_public_id from engine.chat_turn where session_id=?",
                                    String.class,
                                    id));
            assertEquals(
                    2,
                    store.db()
                            .queryForObject(
                                    "select next_turn from engine.chat_session where public_id=?",
                                    Integer.class,
                                    id));
            assertEquals(
                    1,
                    store.db()
                            .queryForObject(
                                    "select count(*) from engine.run where tenant_id='engine-test'",
                                    Integer.class));
            assertEquals(
                    "REQUEST_CONFLICT",
                    assertThrows(
                                    PlatformException.class,
                                    () ->
                                            sessions.turn(
                                                    "chat",
                                                    id,
                                                    Json.read(
                                                            "{\"requestId\":\"accepted\",\"inputs\":{\"message\":\"different\"}}"),
                                                    TracePostgresTest.actor))
                            .code());
            assertEquals(1, scheduler.available(Queue.INTERACTIVE));
        }
    }

    @Test
    void sessionCompletionRequiresTheSameTicketsSuccessfulFirstAcceptance() {
        var clients = clients();
        try (var scheduler =
                new RunScheduler(new RunScheduleRepository(store), 1, 1, 20, 3000, 0)) {
            var runs = new RunService(store, clients, null, null, scheduler);
            try (var ticket = runs.reserveSessionTurn()) {
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                runs.createReservedSessionTurn(
                                        "chat",
                                        Json.read(
                                                "{\"requestId\":\"unaccepted\",\"inputs\":{\"message\":\"hi\"}}"),
                                        RunAdmissionHttpTest.snapshot(true),
                                        TracePostgresTest.actor,
                                        ticket));
            }
            assertEquals(
                    0,
                    store.db()
                            .queryForObject(
                                    "select count(*) from engine.run where tenant_id='engine-test'",
                                    Integer.class));
            assertEquals(1, scheduler.available(Queue.INTERACTIVE));
        }
    }

    @Test
    void acceptedPreparationRunsOutsideReservationTransactionAndSurvivesDrain() {
        var clients = clients();
        var prepared = new AtomicInteger();
        try (var scheduler =
                new RunScheduler(new RunScheduleRepository(store), 1, 1, 20, 3000, 0)) {
            var runs =
                    new RunService(store, clients, null, null, scheduler) {
                        @Override
                        public Map<String, Object> createReservedSessionTurn(
                                String code,
                                JsonNode request,
                                JsonNode snapshot,
                                Actor actor,
                                RunScheduler.Admission ticket) {
                            assertFalse(
                                    org.springframework.transaction.support
                                            .TransactionSynchronizationManager
                                            .isActualTransactionActive(),
                                    "run preparation/HTTP must occur after the reservation"
                                            + " transaction ends");
                            prepared.incrementAndGet();
                            scheduler.close();
                            return super.createReservedSessionTurn(
                                    code, request, snapshot, actor, ticket);
                        }
                    };
            var sessions = new ChatSessions(store, clients, runs);
            String id =
                    sessions.create("chat", TracePostgresTest.actor).get("sessionId").toString();
            var result =
                    sessions.turn(
                            "chat",
                            id,
                            Json.read(
                                    "{\"requestId\":\"prepared\",\"inputs\":{\"message\":\"hi\"}}"),
                            TracePostgresTest.actor);
            assertEquals("PENDING", result.get("status"));
            assertEquals(1, prepared.get());
            assertEquals(
                    0,
                    store.db()
                            .queryForObject(
                                    "select count(*) from engine.run_event e join engine.run r on"
                                            + " r.id=e.run_id where r.tenant_id='engine-test' and"
                                            + " e.type='RUN_STARTED'",
                                    Integer.class));
            assertEquals(
                    0,
                    store.db()
                            .queryForObject(
                                    "select count(*) from engine.model_invocation m join engine.run"
                                            + " r on r.id=m.run_id where r.tenant_id='engine-test'",
                                    Integer.class));
            assertEquals(
                    0,
                    store.db()
                            .queryForObject(
                                    "select count(*) from engine.tool_call t join engine.run r on"
                                            + " r.id=t.run_id where r.tenant_id='engine-test'",
                                    Integer.class));
        }
    }

    private long ready() {
        long id = fixture.run();
        fixture.node(id, "READY");
        return id;
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) throws Exception {
        var f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(target);
    }

    private Connection lock(String table, long id) throws Exception {
        var connection = Objects.requireNonNull(store.db().getDataSource()).getConnection();
        connection.setAutoCommit(false);
        try (var statement =
                connection.prepareStatement(
                        "select id from engine." + table + " where id=? for update")) {
            statement.setLong(1, id);
            statement.executeQuery().close();
        }
        return connection;
    }

    @Test
    void lockedRunCannotStarveOtherDispatchOrNodeRenewals() throws Exception {
        ready();
        ready();
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var renewals = new AtomicInteger();
        var scheduler = new RunScheduler(new RunScheduleRepository(store), 2, 1, 20, 1000, 1000);
        scheduler.bind(
                id -> {
                    entered.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                },
                renewals::incrementAndGet,
                () -> {});
        try {
            scheduler.poll();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            ConcurrentMap<Long, RunDispatch> active = field(scheduler, "active");
            var ordered = List.copyOf(active.values());
            try (var held = lock("run", ordered.getFirst().runId())) {
                scheduler.start();
                Thread.sleep(1300);
                assertTrue(
                        store.db()
                                .queryForObject(
                                        "select dispatch_until>now() from engine.run where id=?",
                                        Boolean.class,
                                        ordered.get(1).runId()),
                        "one locked run must not expire the unrelated dispatch");
                assertTrue(renewals.get() >= 2, "node renewal lane must still make progress");
                held.rollback();
            }
        } finally {
            release.countDown();
            scheduler.close();
        }
    }

    @Test
    void failedDispatchRenewalDoesNotSkipOtherItemsOrNodes() throws Exception {
        long first = ready(), second = ready();
        var renewed = ConcurrentHashMap.<Long>newKeySet();
        var renewNodes = new AtomicInteger();
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(2);
        var repo =
                new RunScheduleRepository(store) {
                    @Override
                    public boolean renew(RunDispatch dispatch, Duration lease) {
                        if (dispatch.runId() == first)
                            throw new IllegalStateException("one failed item");
                        renewed.add(dispatch.runId());
                        return super.renew(dispatch, lease);
                    }
                };
        try (var scheduler = new RunScheduler(repo, 2, 1, 20, 3000, 1000)) {
            scheduler.bind(
                    id -> {
                        entered.countDown();
                        try {
                            release.await(3, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    },
                    renewNodes::incrementAndGet,
                    () -> {});
            try {
                scheduler.poll();
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertDoesNotThrow(scheduler::heartbeat);
                assertEquals(Set.of(second), renewed);
                assertEquals(1, renewNodes.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void lockedNodeDoesNotBlockUnrelatedNodeOrCallRenewal() throws Exception {
        nodeRenewalLockIsolation(false);
    }

    @Test
    void lockedToolCallDoesNotBlockUnrelatedNodeOrCallRenewal() throws Exception {
        nodeRenewalLockIsolation(true);
    }

    private void nodeRenewalLockIsolation(boolean callLock) throws Exception {
        try (var scheduler =
                        new RunScheduler(new RunScheduleRepository(store), 2, 1, 20, 3000, 1000);
                var executor = Executors.newSingleThreadExecutor()) {
            var runs = new RunService(store, clients(), null, null, scheduler);
            Set<Long> active = field(runs, "activeNodes");
            String owner = field(runs, "owner");
            long[] nodes = new long[2], calls = new long[2];
            var tools = new ToolCalls(store);
            for (int i = 0; i < 2; i++) {
                long rid = fixture.run();
                nodes[i] = fixture.node(rid, "READY");
                var node = store.claim(nodes[i], owner);
                var request = fixture.call(rid, nodes[i], node);
                calls[i] =
                        TraceStore.number(
                                tools.register(request.register(), TracePostgresTest.actor),
                                "callId");
                assertEquals(
                        true,
                        tools.claim(calls[i], request.claim(), TracePostgresTest.actor)
                                .get("claimed"));
                if (callLock) tools.submitted(calls[i], request.claim(), TracePostgresTest.actor);
                active.add(nodes[i]);
                store.db()
                        .update(
                                "update engine.node_instance set lease_until=now()+interval '3"
                                        + " seconds' where id=?",
                                nodes[i]);
                store.db()
                        .update(
                                "update engine.tool_call set claimed_lease=now()+interval '3"
                                        + " seconds' where id=?",
                                calls[i]);
            }
            long first = active.iterator().next();
            int lockedIndex = first == nodes[0] ? 0 : 1, other = 1 - lockedIndex;
            Future<?> renewing;
            try (var held =
                    lock(
                            callLock ? "tool_call" : "node_instance",
                            callLock ? calls[lockedIndex] : nodes[lockedIndex])) {
                renewing = executor.submit(runs::renewLeases);
                try {
                    assertDoesNotThrow(
                            () -> renewing.get(800, TimeUnit.MILLISECONDS),
                            "one locked resource must not stall the whole renewal lane");
                    assertTrue(
                            store.db()
                                    .queryForObject(
                                            "select lease_until>now()+interval '60 seconds' from"
                                                    + " engine.node_instance where id=?",
                                            Boolean.class,
                                            nodes[other]));
                    assertTrue(
                            store.db()
                                    .queryForObject(
                                            "select claimed_lease>now()+interval '60 seconds' from"
                                                    + " engine.tool_call where id=?",
                                            Boolean.class,
                                            calls[other]));
                } finally {
                    held.rollback();
                }
            }
            renewing.get(2, TimeUnit.SECONDS);
            active.clear();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired-node", "foreign-node", "expired-call", "foreign-call"})
    void renewalNeverRevivesExpiredOrForeignNodeAndCallLeases(String invalid) throws Exception {
        try (var scheduler =
                new RunScheduler(new RunScheduleRepository(store), 1, 1, 20, 3000, 0)) {
            var runs = new RunService(store, clients(), null, null, scheduler);
            Set<Long> active = field(runs, "activeNodes");
            String owner = field(runs, "owner");
            long rid = fixture.run(), nid = fixture.node(rid, "READY");
            var node = store.claim(nid, owner);
            var request = fixture.call(rid, nid, node);
            var tools = new ToolCalls(store);
            long cid =
                    TraceStore.number(
                            tools.register(request.register(), TracePostgresTest.actor), "callId");
            tools.claim(cid, request.claim(), TracePostgresTest.actor);
            store.db()
                    .update(
                            "update engine.node_instance set lease_until=now()+interval '3 seconds'"
                                    + " where id=?",
                            nid);
            store.db()
                    .update(
                            "update engine.tool_call set claimed_lease=now()+interval '3 seconds'"
                                    + " where id=?",
                            cid);
            switch (invalid) {
                case "expired-node" ->
                        store.db()
                                .update(
                                        "update engine.node_instance set lease_until=now()-interval"
                                                + " '1 second' where id=?",
                                        nid);
                case "foreign-node" ->
                        store.db()
                                .update(
                                        "update engine.node_instance set"
                                                + " lease_owner='different_generation' where id=?",
                                        nid);
                case "expired-call" ->
                        store.db()
                                .update(
                                        "update engine.tool_call set claimed_lease=now()-interval"
                                                + " '1 second' where id=?",
                                        cid);
                case "foreign-call" ->
                        store.db()
                                .update(
                                        "update engine.tool_call set"
                                                + " claimed_by='different_generation' where id=?",
                                        cid);
                default -> fail(invalid);
            }
            var before =
                    store.db()
                            .queryForMap(
                                    "select n.lease_until,t.claimed_lease from engine.node_instance"
                                        + " n join engine.tool_call t on t.node_instance_id=n.id"
                                        + " where n.id=?",
                                    nid);
            active.add(nid);
            runs.renewLeases();
            var after =
                    store.db()
                            .queryForMap(
                                    "select n.lease_until,t.claimed_lease from engine.node_instance"
                                        + " n join engine.tool_call t on t.node_instance_id=n.id"
                                        + " where n.id=?",
                                    nid);
            assertEquals(
                    before.get("claimed_lease"),
                    after.get("claimed_lease"),
                    "invalid call/node authority must never be renewed");
            if (invalid.endsWith("node"))
                assertEquals(before.get("lease_until"), after.get("lease_until"));
            else
                assertTrue(
                        store.db()
                                .queryForObject(
                                        "select lease_until>now()+interval '60 seconds' from"
                                                + " engine.node_instance where id=?",
                                        Boolean.class,
                                        nid));
            active.clear();
        }
    }

    @Test
    void failedNodeRenewalRollsBackOnlyItsOwnItemAndContinues() throws Exception {
        try (var scheduler =
                new RunScheduler(new RunScheduleRepository(store), 2, 1, 20, 3000, 0)) {
            var runs = new RunService(store, clients(), null, null, scheduler);
            Set<Long> active = field(runs, "activeNodes");
            String owner = field(runs, "owner");
            for (int i = 0; i < 2; i++) {
                long nid = fixture.node(fixture.run(), "READY");
                store.claim(nid, owner);
                store.db()
                        .update(
                                "update engine.node_instance set lease_until=now()+interval '3"
                                        + " seconds' where id=?",
                                nid);
                active.add(nid);
            }
            var nodes = List.copyOf(active);
            store.db()
                    .execute(
                            "create function engine.scheduler_reject_renew() returns trigger"
                                + " language plpgsql as 'begin raise exception ''injected renewal"
                                + " failure''; end'");
            try {
                store.db()
                        .execute(
                                "create trigger scheduler_reject_renew before update on"
                                        + " engine.node_instance for each row when (new.id="
                                        + nodes.getFirst()
                                        + ") execute function engine.scheduler_reject_renew()");
                try {
                    assertDoesNotThrow(runs::renewLeases);
                    assertTrue(
                            store.db()
                                    .queryForObject(
                                            "select lease_until>now()+interval '60 seconds' from"
                                                    + " engine.node_instance where id=?",
                                            Boolean.class,
                                            nodes.get(1)));
                    assertFalse(
                            store.db()
                                    .queryForObject(
                                            "select lease_until>now()+interval '60 seconds' from"
                                                    + " engine.node_instance where id=?",
                                            Boolean.class,
                                            nodes.getFirst()));
                } finally {
                    store.db()
                            .execute("drop trigger scheduler_reject_renew on engine.node_instance");
                }
            } finally {
                store.db().execute("drop function engine.scheduler_reject_renew()");
                active.clear();
            }
        }
    }
}
