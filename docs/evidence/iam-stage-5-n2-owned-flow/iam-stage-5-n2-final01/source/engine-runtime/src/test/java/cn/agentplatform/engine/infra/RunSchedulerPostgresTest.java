package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.*;
import cn.agentplatform.engine.domain.*;
import cn.agentplatform.engine.domain.RunDispatch.Queue;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class RunSchedulerPostgresTest {
    private static final String TENANT = "scheduler-test";
    private static final Actor ACTOR = new Actor(TENANT, "alice", Set.of("EDITOR"), Map.of());
    private TraceStore store;

    @BeforeAll
    static void database() throws Exception {
        TracePostgresTest.connect();
    }

    @BeforeEach
    void clear() {
        // This is the shared dedicated engine test database, never the development database.
        // Drain/clear the other fixture tenant before exercising a deliberately global queue.
        new TracePostgresTest().clean();
        store = TracePostgresTest.store;
        var db = store.db();
        db.update(
                "delete from engine.attempt_note where node_instance_id in(select n.id from"
                        + " engine.node_instance n join engine.run r on r.id=n.run_id where"
                        + " r.tenant_id=?)",
                TENANT);
        db.update(
                "delete from engine.run_event where run_id in(select id from engine.run where"
                        + " tenant_id=?)",
                TENANT);
        db.update(
                "delete from engine.attempt where node_instance_id in(select n.id from"
                        + " engine.node_instance n join engine.run r on r.id=n.run_id where"
                        + " r.tenant_id=?)",
                TENANT);
        db.update(
                "delete from engine.node_instance where run_id in(select id from engine.run where"
                        + " tenant_id=?)",
                TENANT);
        db.update("delete from engine.run where tenant_id=?", TENANT);
    }

    @AfterEach
    void cleanup() {
        clear();
    }

    private long ready() {
        var guard =
                Map.of(
                        "tenantId",
                        TENANT,
                        "principalId",
                        "alice",
                        "roles",
                        List.of("EDITOR"),
                        "attributes",
                        Map.of());
        long id =
                store.db()
                        .queryForObject(
                                "insert into"
                                    + " engine.run(public_id,app_code,debug_snapshot,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs)"
                                    + " values(?,'schedule','{}','DEBUG','RUNNING','alice',?,'pg:test','{}',?::jsonb,'{}','{}','{}')"
                                    + " returning id",
                                Long.class,
                                "r_schedule_" + UUID.randomUUID(),
                                TENANT,
                                Json.write(guard));
        store.db()
                .update(
                        "insert into"
                            + " engine.node_instance(run_id,step_seq,node_type,status,definition)"
                            + " values(?,1,'CODE','READY','{}')",
                        id);
        return id;
    }

    @Test
    void recoveryEventuallySeesReadyRunBeyondFirstHundred() throws Exception {
        Set<Long> seen = ConcurrentHashMap.newKeySet();
        long tail = 0;
        for (int i = 0; i < 150; i++) tail = ready();
        var runs =
                new RunService(store, null, null, null) {
                    @Override
                    public void drive(long id, Actor actor) {
                        seen.add(id);
                    }
                };
        for (int tick = 0; tick < 24; tick++) {
            runs.recover();
            Thread.sleep(30);
        }
        long expected = tail;
        assertTrue(
                seen.contains(expected),
                "ready run 150 must not remain behind a fixed first-100 scan");
    }

    @Test
    void repeatedRecoveryDoesNotStartDuplicateWorkersForOneBlockedRun() throws Exception {
        ready();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var starts = new AtomicInteger();
        var runs =
                new RunService(store, null, null, null) {
                    @Override
                    public void drive(long id, Actor actor) {
                        starts.incrementAndGet();
                        entered.countDown();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                };
        try {
            runs.recover();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 12; i++) runs.recover();
            Thread.sleep(100);
            assertEquals(
                    1,
                    starts.get(),
                    "node CAS cannot substitute for bounded run worker deduplication");
        } finally {
            release.countDown();
        }
    }

    private RunScheduler scheduler(int interactive, int batch, long lease, long drain) {
        return new RunScheduler(
                new RunScheduleRepository(store), interactive, batch, 20, lease, drain);
    }

    private void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "bounded wait exhausted");
    }

    private void batch(long id) {
        store.db()
                .update(
                        "update engine.run set"
                            + " trigger_type='EVALUATION',eval_candidate='{}',debug_snapshot=null"
                            + " where id=?",
                        id);
    }

    @Test
    void pendingStartAndEventAreAtomicAndWrittenOnlyOnce() {
        long id = ready();
        store.db().update("update engine.run set status='PENDING' where id=?", id);
        var repo = new RunScheduleRepository(store);
        var first = repo.claimSpecific(id, Queue.INTERACTIVE, "A", Duration.ofSeconds(2));
        assertNotNull(first);
        assertEquals(
                "RUNNING",
                store.db()
                        .queryForObject(
                                "select status from engine.run where id=?", String.class, id));
        assertEquals(
                1,
                store.db()
                        .queryForObject(
                                "select count(*) from engine.run_event where run_id=? and"
                                        + " type='RUN_STARTED'",
                                Integer.class,
                                id));
        assertTrue(repo.release(first, Duration.ZERO));
        assertNotNull(repo.claimSpecific(id, Queue.INTERACTIVE, "B", Duration.ofSeconds(2)));
        assertEquals(
                1,
                store.db()
                        .queryForObject(
                                "select count(*) from engine.run_event where run_id=? and"
                                        + " type='RUN_STARTED'",
                                Integer.class,
                                id));
        assertEquals(
                0,
                store.db()
                        .queryForObject(
                                "select count(*) from engine.attempt a join engine.node_instance n"
                                        + " on n.id=a.node_instance_id where n.run_id=?",
                                Integer.class,
                                id));
    }

    @Test
    void liveNodeLeasesAtHeadDoNotHideReadyTail() {
        for (int i = 0; i < 101; i++) {
            long id = ready();
            store.db()
                    .update(
                            "update engine.node_instance set"
                                + " status='RUNNING',lease_owner='other',lease_until=now()+interval"
                                + " '5 minutes' where run_id=?",
                            id);
        }
        long tail = ready();
        assertEquals(
                tail,
                new RunScheduleRepository(store)
                        .claimNext(Queue.INTERACTIVE, "B", Duration.ofSeconds(2))
                        .runId());
    }

    @Test
    void twoRepositoriesCannotClaimSameGeneration() throws Exception {
        Set<Long> expected = new HashSet<>();
        for (int i = 0; i < 20; i++) expected.add(ready());
        var claimed = ConcurrentHashMap.<Long>newKeySet();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < 2; i++) {
                String owner = "replica" + i;
                var repo = new RunScheduleRepository(store);
                tasks.add(
                        executor.submit(
                                () -> {
                                    try {
                                        start.await();
                                    } catch (InterruptedException e) {
                                        throw new RuntimeException(e);
                                    }
                                    RunDispatch dispatch;
                                    while ((dispatch =
                                                    repo.claimNext(
                                                            Queue.INTERACTIVE,
                                                            owner,
                                                            Duration.ofSeconds(10)))
                                            != null)
                                        assertTrue(
                                                claimed.add(dispatch.runId()),
                                                "same live scheduling row claimed twice");
                                }));
            }
            start.countDown();
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
        }
        assertEquals(expected, claimed);
    }

    @Test
    void lockedHeadIsSkippedWithoutWaitingForItsTransaction() throws Exception {
        long head = ready(), tail = ready();
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var held =
                    executor.submit(
                            () ->
                                    store.atomic(
                                            () -> {
                                                store.lockRun(head);
                                                locked.countDown();
                                                try {
                                                    release.await(4, TimeUnit.SECONDS);
                                                } catch (InterruptedException e) {
                                                    throw new RuntimeException(e);
                                                }
                                                return null;
                                            }));
            try {
                assertTrue(locked.await(2, TimeUnit.SECONDS));
                var claim =
                        executor.submit(
                                () ->
                                        new RunScheduleRepository(store)
                                                .claimNext(
                                                        Queue.INTERACTIVE,
                                                        "B",
                                                        Duration.ofSeconds(2)));
                assertEquals(tail, claim.get(2, TimeUnit.SECONDS).runId());
            } finally {
                release.countDown();
                held.get(2, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void oldGenerationCannotRenewOrReleaseSuccessor() {
        long id = ready();
        var repo = new RunScheduleRepository(store);
        var old = repo.claimSpecific(id, Queue.INTERACTIVE, "A", Duration.ofSeconds(2));
        store.db()
                .update(
                        "update engine.run set dispatch_until=now()-interval '1 second' where id=?",
                        id);
        var fresh = repo.claimSpecific(id, Queue.INTERACTIVE, "B", Duration.ofSeconds(2));
        assertEquals(old.generation() + 1, fresh.generation());
        assertFalse(repo.renew(old, Duration.ofSeconds(9)));
        assertFalse(repo.release(old, Duration.ZERO));
        assertTrue(repo.valid(fresh));
    }

    @Test
    void fullInteractivePoolDoesNotConsumeBatchSlotAndViceVersa() throws Exception {
        long interactive = ready(), evaluation = ready();
        batch(evaluation);
        var both = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try (var scheduler = scheduler(1, 1, 1000, 2000)) {
            scheduler.bind(
                    id -> {
                        both.countDown();
                        try {
                            release.await(3, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    },
                    () -> {},
                    () -> {});
            try {
                scheduler.poll();
                assertTrue(both.await(2, TimeUnit.SECONDS));
                assertEquals(2, scheduler.activeWorkers());
                assertEquals(0, scheduler.available(Queue.INTERACTIVE));
                assertEquals(0, scheduler.available(Queue.BATCH));
                for (int i = 0; i < 30; i++) scheduler.poll();
                assertEquals(2, scheduler.activeWorkers());
            } finally {
                release.countDown();
            }
            waitFor(() -> scheduler.activeWorkers() == 0);
            assertEquals(1, scheduler.available(Queue.INTERACTIVE));
            assertEquals(1, scheduler.available(Queue.BATCH));
        }
    }

    @Test
    void ticketCloseAndHandoffDoNotOverReleaseOrReleaseRunningWorker() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var scheduler = scheduler(1, 1, 1000, 2000)) {
            scheduler.bind(
                    id -> {
                        entered.countDown();
                        try {
                            release.await(3, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    },
                    () -> {},
                    () -> {});
            var ticket = scheduler.reserveInteractive();
            ticket.close();
            ticket.close();
            assertEquals(1, scheduler.available(Queue.INTERACTIVE));
            var handed = scheduler.reserveInteractive();
            long id = ready();
            try {
                scheduler.created(id, handed);
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                handed.close();
                handed.close();
                assertEquals(0, scheduler.available(Queue.INTERACTIVE));
                assertThrows(RunAdmissionRejected.class, scheduler::reserveInteractive);
            } finally {
                release.countDown();
            }
            waitFor(() -> scheduler.activeWorkers() == 0);
            assertEquals(1, scheduler.available(Queue.INTERACTIVE));
        }
    }

    @Test
    void cancellingBlockedWorkerDoesNotReleaseItsRealSlotEarly() throws Exception {
        long id = ready();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var scheduler = scheduler(1, 1, 1000, 2000)) {
            scheduler.bind(
                    rid -> {
                        entered.countDown();
                        try {
                            release.await(3, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    },
                    () -> {},
                    () -> {});
            try {
                scheduler.poll();
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                store.db().update("update engine.run set status='CANCELLED' where id=?", id);
                scheduler.poll();
                assertEquals(0, scheduler.available(Queue.INTERACTIVE));
                assertEquals(1, scheduler.activeWorkers());
            } finally {
                release.countDown();
            }
            waitFor(() -> scheduler.activeWorkers() == 0);
            assertEquals(1, scheduler.available(Queue.INTERACTIVE));
        }
    }

    @Test
    void cancelledPendingWorkNeverRunsAndRemainsDurableAfterSchedulerReplacement()
            throws Exception {
        long cancelled = ready(), pending = ready();
        batch(pending);
        store.db().update("update engine.run set status='CANCELLED' where id=?", cancelled);
        store.db().update("update engine.run set status='PENDING' where id=?", pending);
        var seen = ConcurrentHashMap.<Long>newKeySet();
        try (var scheduler = scheduler(1, 1, 1000, 1000)) {
            scheduler.bind(id -> seen.add(id), () -> {}, () -> {});
            scheduler.poll();
            waitFor(() -> scheduler.activeWorkers() == 0);
        }
        assertEquals(Set.of(pending), seen);
        assertEquals(
                0,
                store.db()
                        .queryForObject(
                                "select count(*) from engine.run_event where run_id=?",
                                Integer.class,
                                cancelled));
    }

    @Test
    void contextCloseKeepsDedicatedHeartbeatAliveThroughoutDrain() throws Exception {
        long id = ready();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var renewals = new AtomicInteger();
        var scheduler = scheduler(1, 1, 300, 2000);
        scheduler.bind(
                rid -> {
                    entered.countDown();
                    try {
                        release.await(4, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                },
                renewals::incrementAndGet,
                () -> {});
        try (var context = new org.springframework.context.support.GenericApplicationContext();
                var executor = Executors.newSingleThreadExecutor()) {
            context.registerBean(RunScheduler.class, () -> scheduler);
            context.refresh();
            scheduler.start();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var closing = executor.submit(context::close);
            try {
                Thread.sleep(450);
                assertFalse(closing.isDone(), "drain must wait for the real worker");
                assertTrue(
                        renewals.get() >= 2,
                        "ContextClosedEvent must not kill the independent renewal timer");
                assertTrue(
                        store.db()
                                .queryForObject(
                                        "select dispatch_until>now() from engine.run where id=?",
                                        Boolean.class,
                                        id));
                assertEquals(
                        503,
                        assertThrows(RunAdmissionRejected.class, scheduler::reserveInteractive)
                                .status());
                assertEquals(0, scheduler.available(Queue.INTERACTIVE));
            } finally {
                release.countDown();
                closing.get(3, TimeUnit.SECONDS);
            }
        }
        assertEquals(1, scheduler.available(Queue.INTERACTIVE));
    }

    @Test
    void drainTimeoutDoesNotInventExpiryOrReturnBlockedPermit() throws Exception {
        long id = ready();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var scheduler = scheduler(1, 1, 1000, 50);
        scheduler.bind(
                rid -> {
                    entered.countDown();
                    try {
                        release.await(3, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                },
                () -> {},
                () -> {});
        try {
            scheduler.poll();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            scheduler.close();
            assertEquals(0, scheduler.available(Queue.INTERACTIVE));
            assertTrue(
                    store.db()
                            .queryForObject(
                                    "select dispatch_until>now() from engine.run where id=?",
                                    Boolean.class,
                                    id));
            assertNull(
                    new RunScheduleRepository(store)
                            .claimSpecific(id, Queue.INTERACTIVE, "B", Duration.ofSeconds(1)));
        } finally {
            release.countDown();
        }
        waitFor(() -> scheduler.activeWorkers() == 0);
        assertEquals(1, scheduler.available(Queue.INTERACTIVE));
    }

    @Test
    void failedPreparationReleasesReservationAndCannotPersistAfterClose() {
        try (var scheduler = scheduler(1, 1, 1000, 0)) {
            var ticket = scheduler.reserveInteractive();
            try {
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                ticket.persist(
                                        () -> {
                                            throw new IllegalStateException("validation");
                                        }));
            } finally {
                ticket.close();
            }
            assertEquals(1, scheduler.available(Queue.INTERACTIVE));
            var next = scheduler.reserveInteractive();
            scheduler.close();
            try {
                assertEquals(
                        503,
                        assertThrows(
                                        RunAdmissionRejected.class,
                                        () -> next.persist(() -> fail("no write allowed")))
                                .status());
            } finally {
                next.close();
            }
            assertEquals(1, scheduler.available(Queue.INTERACTIVE));
        }
    }

    @Test
    void blockedReadyFactsDoesNotStopActiveDispatchHeartbeat() throws Exception {
        heartbeatDuringBlockedPoll(false);
    }

    @Test
    void blockedClaimUnderAdmissionGateDoesNotStopActiveDispatchHeartbeat() throws Exception {
        heartbeatDuringBlockedPoll(true);
    }

    private void heartbeatDuringBlockedPoll(boolean insideClaim) throws Exception {
        long id = ready();
        var entered = new CountDownLatch(1);
        var releaseWorker = new CountDownLatch(1);
        var blocked = new CountDownLatch(1);
        var releasePoll = new CountDownLatch(1);
        var block = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable pause =
                () -> {
                    if (block.get()) {
                        blocked.countDown();
                        try {
                            releasePoll.await(3, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                };
        var repo =
                new RunScheduleRepository(store) {
                    @Override
                    public RunDispatch claimNext(Queue queue, String owner, Duration duration) {
                        if (insideClaim) pause.run();
                        return super.claimNext(queue, owner, duration);
                    }
                };
        var scheduler = new RunScheduler(repo, 2, 1, 20, 300, 1000);
        scheduler.bind(
                rid -> {
                    entered.countDown();
                    try {
                        releaseWorker.await(4, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                },
                () -> {},
                insideClaim ? () -> {} : pause);
        try {
            scheduler.start();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            block.set(true);
            assertTrue(blocked.await(2, TimeUnit.SECONDS));
            Thread.sleep(450);
            assertTrue(
                    store.db()
                            .queryForObject(
                                    "select dispatch_until>now() from engine.run where id=?",
                                    Boolean.class,
                                    id),
                    "poll/readyFacts/gate stall must not starve an unrelated live worker renewal");
        } finally {
            block.set(false);
            releasePoll.countDown();
            releaseWorker.countDown();
            scheduler.close();
        }
    }

    @Test
    void closingChildContextDoesNotDrainParentScheduler() {
        try (var parent = new org.springframework.context.support.GenericApplicationContext()) {
            var scheduler = scheduler(1, 1, 1000, 0);
            parent.registerBean(RunScheduler.class, () -> scheduler);
            parent.refresh();
            try (var child =
                    new org.springframework.context.support.GenericApplicationContext(parent)) {
                child.refresh();
            }
            assertEquals(1, assertDoesNotThrow(() -> scheduler.persistNew(() -> 1)));
        }
    }

    @Test
    void acceptedBatchDoesNotSynchronouslyScanUnrelatedExternalFacts() throws Exception {
        long id = ready();
        batch(id);
        store.db().update("update engine.run set status='PENDING' where id=?", id);
        var entered = new CountDownLatch(1);
        try (var scheduler = scheduler(1, 1, 1000, 1000)) {
            scheduler.bind(
                    rid -> entered.countDown(),
                    () -> {},
                    () -> {
                        throw new IllegalStateException(
                                "unrelated recovery must not block accepted POST");
                    });
            assertDoesNotThrow(() -> scheduler.created(id, null));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void transferredTicketCannotStartAnotherRunOrMintAnExtraPermit() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var scheduler = scheduler(1, 1, 1000, 1000)) {
            scheduler.bind(
                    rid -> {
                        entered.countDown();
                        try {
                            release.await(3, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    },
                    () -> {},
                    () -> {});
            var ticket = scheduler.reserveInteractive();
            long first = ready(), second = ready();
            try {
                scheduler.created(first, ticket);
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class, () -> scheduler.created(second, ticket));
            } finally {
                release.countDown();
            }
            waitFor(() -> scheduler.activeWorkers() == 0);
            assertEquals(1, scheduler.available(Queue.INTERACTIVE));
        }
    }

    @Test
    void closedTicketCannotBeRevivedForAWorker() {
        try (var scheduler = scheduler(1, 1, 1000, 1000)) {
            scheduler.bind(rid -> {}, () -> {}, () -> {});
            var ticket = scheduler.reserveInteractive();
            ticket.close();
            long id = ready();
            assertThrows(IllegalStateException.class, () -> scheduler.created(id, ticket));
            assertEquals(1, scheduler.available(Queue.INTERACTIVE));
        }
    }

    @Test
    void reservationCanPersistOnlyOneAcceptedOperation() {
        try (var scheduler = scheduler(1, 1, 1000, 1000);
                var ticket = scheduler.reserveInteractive()) {
            assertEquals(1, ticket.persist(() -> 1));
            assertThrows(IllegalStateException.class, () -> ticket.persist(() -> 2));
        }
    }

    @Test
    void closeDuringPersistenceDoesNotReleaseItsSlotBeforeWriteReturns() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var scheduler = scheduler(1, 1, 1000, 1000);
                var executor = Executors.newSingleThreadExecutor()) {
            var ticket = scheduler.reserveInteractive();
            var writing =
                    executor.submit(
                            () ->
                                    ticket.persist(
                                            () -> {
                                                entered.countDown();
                                                try {
                                                    release.await(3, TimeUnit.SECONDS);
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                }
                                                return 1;
                                            }));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                ticket.close();
                assertEquals(
                        0,
                        scheduler.available(Queue.INTERACTIVE),
                        "an in-flight persistence operation still owns its real admission slot");
            } finally {
                release.countDown();
                writing.get(3, TimeUnit.SECONDS);
                ticket.close();
            }
            assertEquals(1, scheduler.available(Queue.INTERACTIVE));
        }
    }

    @Test
    void onePollHasFiniteClaimBudgetEvenWhenFastWorkLeavesRowsReady() throws Exception {
        ready();
        var claims = new AtomicInteger();
        var previous = new java.util.concurrent.atomic.AtomicReference<>(new CountDownLatch(0));
        var repo =
                new RunScheduleRepository(store) {
                    @Override
                    public RunDispatch claimNext(Queue queue, String owner, Duration duration) {
                        if (queue == Queue.INTERACTIVE) {
                            if (claims.incrementAndGet() > 10)
                                throw new IllegalStateException("single poll did not yield");
                            try {
                                assertTrue(previous.get().await(2, TimeUnit.SECONDS));
                            } catch (InterruptedException e) {
                                throw new RuntimeException(e);
                            }
                        }
                        var dispatch = super.claimNext(queue, owner, duration);
                        if (dispatch != null) previous.set(new CountDownLatch(1));
                        return dispatch;
                    }

                    @Override
                    public boolean release(RunDispatch dispatch, Duration delay) {
                        try {
                            return super.release(dispatch, Duration.ZERO);
                        } finally {
                            previous.get().countDown();
                        }
                    }
                };
        try (var scheduler = new RunScheduler(repo, 2, 1, 20, 1000, 1000)) {
            scheduler.bind(rid -> {}, () -> {}, () -> {});
            assertDoesNotThrow(scheduler::poll);
            assertTrue(claims.get() <= 2, "released slots cannot enlarge this tick's claim budget");
            waitFor(() -> scheduler.activeWorkers() == 0);
        }
    }

    @Test
    void failedStartedEventRollsBackPendingTransitionAndDispatchGeneration() {
        long id = ready();
        store.db().update("update engine.run set status='PENDING' where id=?", id);
        var db = store.db();
        db.execute(
                "create function engine.scheduler_reject_start() returns trigger language plpgsql"
                    + " as 'begin raise exception ''scheduler test injected event failure''; end'");
        try {
            db.execute(
                    "create trigger scheduler_reject_start before insert on engine.run_event for"
                            + " each row when (new.run_id="
                            + id
                            + " and new.type='RUN_STARTED') execute function"
                            + " engine.scheduler_reject_start()");
            try {
                assertThrows(
                        org.springframework.dao.DataAccessException.class,
                        () ->
                                new RunScheduleRepository(store)
                                        .claimSpecific(
                                                id, Queue.INTERACTIVE, "A", Duration.ofSeconds(1)));
            } finally {
                db.execute("drop trigger scheduler_reject_start on engine.run_event");
            }
            var row =
                    db.queryForMap(
                            "select status,event_seq,dispatch_generation,dispatch_owner from"
                                    + " engine.run where id=?",
                            id);
            assertEquals("PENDING", row.get("status"));
            assertEquals(0, ((Number) row.get("event_seq")).intValue());
            assertEquals(0, ((Number) row.get("dispatch_generation")).longValue());
            assertNull(row.get("dispatch_owner"));
        } finally {
            db.execute("drop function engine.scheduler_reject_start()");
        }
    }
}
