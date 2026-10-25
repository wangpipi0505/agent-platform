package cn.agentplatform.engine.infra;

import static org.assertj.core.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.HttpCurrentAuthorityClient.RetainedAuthorization;
import cn.agentplatform.engine.application.*;
import cn.agentplatform.engine.domain.RunDispatch;
import cn.agentplatform.engine.domain.RunDispatch.Queue;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class OwnedFlowSchedulerPostgresTest {
    @Test void missingSnapshotTypeNeverStartsOwnedWorker() throws Exception { rejectedApplicationType(null, "missing"); }
    @Test void nullSnapshotTypeNeverStartsOwnedWorker() throws Exception { rejectedApplicationType(Json.read("null"), "json-null"); }
    @Test void unknownSnapshotTypeNeverStartsOwnedWorker() throws Exception { rejectedApplicationType(Json.tree("UNKNOWN"), "UNKNOWN"); }
    @Test void chatSnapshotTypeNeverStartsOwnedWorker() throws Exception { rejectedApplicationType(Json.tree("CHAT"), "CHAT"); }

    private void rejectedApplicationType(JsonNode type, String label) throws Exception {
        try(var f=fixture(); var worker=new Worker(f,true)) {
            long run;
            int reads;
            try(var ticket=worker.scheduler.reserveInteractive()) {
                run=ticket.persist(()->{try{return insert(f,SIMPLE,type);}catch(Exception e){throw new IllegalStateException(e);}});
                assertThat(f.store.load(run).root()).isEqualTo(RunAuthorityFixture.ROOT);
                assertThat(f.store.load(run).scopes()).hasSize(3);
                assertThat(f.db.queryForObject("select count(*) from engine.material_scope where run_id=?",Long.class,run)).isEqualTo(15);
                reads=f.ds.payloadReads.get();
                worker.scheduler.created(run,ticket);
            }
            worker.await();
            String status=f.db.queryForObject("select status from engine.run where id=?",String.class,run);
            long started=f.db.queryForObject("select count(*) from engine.run_event where run_id=? and type='RUN_STARTED'",Long.class,run);
            long attempts=f.db.queryForObject("select count(*) from engine.attempt",Long.class);
            long outputs=f.db.queryForObject("select count(*) from engine.node_instance where run_id=? and output is not null",Long.class,run);
            boolean runOutput=f.db.queryForObject("select output is not null from engine.run where id=?",Boolean.class,run);
            System.out.println("N2_FIX_TYPE="+label+",STATUS="+status+",STARTED="+started+",ATTEMPTS="+attempts+",EXECUTIONS="+worker.flowExecutions.get()+",NODE_OUTPUTS="+outputs+",RUN_OUTPUT="+runOutput+",AUTHORIZED_PAYLOAD_READ_DELTA="+(f.ds.payloadReads.get()-reads));
            assertThat(status).isEqualTo("PENDING");
            assertThat(started).isZero();assertThat(attempts).isZero();assertThat(worker.flowExecutions.get()).isZero();assertThat(outputs).isZero();assertThat(runOutput).isFalse();
            assertThat(f.ds.payloadReads.get()).as("gate legitimately materializes the authorized snapshot before deciding its type").isGreaterThan(reads);
        }
    }
    @BeforeAll
    static void isolatedDatabase() throws Exception {
        var target =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:postgresql://127.0.0.1:25432/agent_platform_iam_owned_flow_v12",
                                "agent_v12",
                                System.getenv("AGENT_DB_PASSWORD")));
        assertThat(target.queryForObject("select current_database()", String.class))
                .isEqualTo("agent_platform_iam_owned_flow_v12");
        System.out.println("N2_FIX_REUSED_DATABASE=agent_platform_iam_owned_flow_v12");
    }

    static RunAuthorityFixture fixture() throws Exception {
        var f = new RunAuthorityFixture("agent_platform_iam_owned_flow_v12");
        f.db.execute("truncate engine.run restart identity cascade");
        return f;
    }

    static long insert(RunAuthorityFixture f, String graph) throws Exception {
        return insert(f, graph, Json.tree("FLOW"));
    }

    static long insert(RunAuthorityFixture f, String graph, JsonNode type) throws Exception {
        var snapshot = (com.fasterxml.jackson.databind.node.ObjectNode) f.snapshot().deepCopy();
        if (type != null) snapshot.set("type", type);
        snapshot.set("definition", Json.read(graph));
        return f.admitted(
                p ->
                        f.tx.execute(
                                s -> {
                                    long run =
                                            f.store.createPublished(
                                                    p,
                                                    snapshot,
                                                    Json.read("{\"a\":7}"),
                                                    Json.read("{\"appVer\":1}"),
                                                    f.budget(),
                                                    "FULL",
                                                    UUID.randomUUID().toString(),
                                                    Set.of("config"));
                                    int seq = 0;
                                    for (var node : snapshot.path("definition").path("nodes"))
                                        FlowMaterialScopePostgresTest.trace(f)
                                                .createNode(
                                                        run,
                                                        node.path("id").asText(),
                                                        ++seq,
                                                        node.path("type").asText(),
                                                        node,
                                                        p);
                                    return run;
                                }));
    }

    static final String SIMPLE =
            "{\"nodes\":[{\"id\":\"s\",\"type\":\"START\"},{\"id\":\"e\",\"type\":\"END\",\"args\":\"${s.output}\"}],\"edges\":[{\"from\":\"s\",\"to\":\"e\"}]}";

    static final class Worker implements AutoCloseable {
        final RunScheduler scheduler;
        final RunService service;
        final TraceStore trace;
        final AtomicInteger flowExecutions = new AtomicInteger();

        Worker(RunAuthorityFixture f, boolean authority) {
            trace = FlowMaterialScopePostgresTest.trace(f);
            f.ds.beforeSql =
                    sql -> {
                        if (sql.matches(
                                "(?is)select\\s+\\*\\s+from\\s+engine\\.(run|node_instance|attempt).*"))
                            throw new AssertionError("protected worker raw payload query: " + sql);
                    };
            scheduler =
                    new RunScheduler(new RunScheduleRepository(trace, true), 1, 1, 10, 90000, 1000);
            service =
                    new RunService(
                            trace,
                            null,
                            new FlowDriver(trace, null) {
                                @Override
                                public JsonNode executeFlow(TraceStore.FlowClaim work, RunDispatch dispatch, RetainedAuthorization proof) {
                                    // Observation only: every invocation runs the original computation.
                                    flowExecutions.incrementAndGet();
                                    return super.executeFlow(work, dispatch, proof);
                                }
                            },
                            null,
                            scheduler,
                            authority
                                    ? new RunAuthority(
                                            f.store, f.engine, f.iam.clock(), Duration.ofSeconds(3))
                                    : null);
        }

        void created(RunAuthorityFixture f, String graph) throws Exception {
            try (var ticket = scheduler.reserveInteractive()) {
                long run =
                        ticket.persist(
                                () -> {
                                    try {
                                        return insert(f, graph);
                                    } catch (Exception e) {
                                        throw new IllegalStateException(e);
                                    }
                                });
                scheduler.created(run, ticket);
            }
            await();
        }

        void await() throws Exception {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (scheduler.activeWorkers() > 0 && System.nanoTime() < until) Thread.sleep(10);
            assertThat(scheduler.activeWorkers()).isZero();
            assertThat(scheduler.available(Queue.INTERACTIVE)).isEqualTo(1);
        }

        @Override
        public void close() {
            scheduler.close();
        }
    }

    @Test
    void originalReserveCreatedAndPollRunServiceFlowDriverCompleteExpiredPortalThroughDelegated()
            throws Exception {
        try (var f = fixture();
                var worker = new Worker(f, true)) {
            long run;
            try (var ticket = worker.scheduler.reserveInteractive()) {
                run =
                        ticket.persist(
                                () -> {
                                    try {
                                        return insert(f, SIMPLE);
                                    } catch (Exception e) {
                                        throw new IllegalStateException(e);
                                    }
                                });
                f.iam.advanceClock(Duration.ofSeconds(41));
                worker.scheduler.created(run, ticket);
            }
            worker.await();
            assertThat(worker.flowExecutions.get()).isEqualTo(2);
            assertThat(
                            f.db.queryForObject(
                                    "select status from engine.run where id=?", String.class, run))
                    .isEqualTo("SUCCEEDED");
            assertThat(
                            TraceStore.json(
                                    f.db.queryForObject(
                                            "select output::text from engine.run where id=?",
                                            String.class,
                                            run)))
                    .isEqualTo(Json.read("{\"a\":7}"));
            assertThat(f.db.queryForObject("select count(*) from engine.attempt", Long.class))
                    .isEqualTo(2);
            assertThat(
                            f.db.queryForList(
                                    "select type from engine.run_event order by seq", String.class))
                    .containsExactly(
                            "RUN_STARTED",
                            "NODE_STARTED",
                            "LOGIC_RESULT_COMMITTED",
                            "NODE_FINISHED",
                            "NODE_STARTED",
                            "LOGIC_RESULT_COMMITTED",
                            "NODE_FINISHED",
                            "RUN_FINISHED");
            int calls = f.iam.requestAttempts();
            worker.scheduler.poll();
            worker.await();
            assertThat(f.iam.requestAttempts()).isEqualTo(calls);
            assertThat(
                            f.db.queryForObject(
                                    "select count(*) from engine.material_scope", Long.class))
                    .isGreaterThan(30);
        }
    }

    @Test
    void current403And503MakeZeroPayloadAttemptAndStartAndBackoffBothQueueEntrypoints()
            throws Exception {
        for (boolean unavailable : List.of(false, true))
            try (var f = fixture();
                    var worker = new Worker(f, true)) {
                long run = insert(f, SIMPLE);
                int reads = f.ds.payloadReads.get();
                if (unavailable) f.iam.unavailable(true);
                else f.iam.revoked(true);
                worker.scheduler.poll();
                worker.await();
                assertThat(f.ds.payloadReads.get()).isEqualTo(reads);
                assertThat(
                                f.db.queryForObject(
                                        "select status from engine.run where id=?",
                                        String.class,
                                        run))
                        .isEqualTo("PENDING");
                assertThat(f.db.queryForObject("select count(*) from engine.run_event", Long.class))
                        .isZero();
                assertThat(f.db.queryForObject("select count(*) from engine.attempt", Long.class))
                        .isZero();
                var repo = new RunScheduleRepository(worker.trace, true);
                int calls = f.iam.requestAttempts();
                assertThat(repo.claimNext(Queue.INTERACTIVE, "no-spin", Duration.ofSeconds(30)))
                        .isNull();
                assertThat(
                                repo.claimSpecific(
                                        run, Queue.INTERACTIVE, "no-spin", Duration.ofSeconds(30)))
                        .isNull();
                worker.scheduler.poll();
                worker.await();
                assertThat(f.iam.requestAttempts()).isEqualTo(calls);
                if (unavailable) {
                    f.iam.unavailable(false);
                    f.iam.advanceClock(Duration.ofSeconds(3));
                    f.db.update(
                            "update engine.run set dispatch_next_at=clock_timestamp() where id=?",
                            run);
                    worker.scheduler.poll();
                    worker.await();
                    assertThat(
                                    f.db.queryForObject(
                                            "select status from engine.run where id=?",
                                            String.class,
                                            run))
                            .isEqualTo("SUCCEEDED");
                }
            }
    }

    @Test
    void missingAuthorityCapabilityAndUnsupportedNodesNeverUseLegacyOrAgentDriver()
            throws Exception {
        try (var f = fixture();
                var worker = new Worker(f, false)) {
            long run = insert(f, SIMPLE);
            int reads = f.ds.payloadReads.get();
            worker.scheduler.poll();
            worker.await();
            assertThat(f.ds.payloadReads.get()).isEqualTo(reads);
            assertThat(
                            f.db.queryForObject(
                                    "select status from engine.run where id=?", String.class, run))
                    .isEqualTo("PENDING");
        }
        for (String type : List.of("CONFIRM", "AGENT", "LLM", "SKILL", "RETRIEVE", "UNKNOWN"))
            try (var f = fixture();
                    var worker = new Worker(f, true)) {
                insert(f, "{\"nodes\":[{\"id\":\"x\",\"type\":\"" + type + "\"}],\"edges\":[]}");
                worker.scheduler.poll();
                worker.await();
                assertThat(f.db.queryForObject("select count(*) from engine.run_event", Long.class))
                        .isZero();
                assertThat(f.db.queryForObject("select count(*) from engine.attempt", Long.class))
                        .isZero();
            }
    }

    @Test
    void protectedClaimOnlyOwnsDispatchBeforeCurrentAndReadsNoPayload() throws Exception {
        try (var f = fixture()) {
            var i = FlowMaterialScopePostgresTest.insert(f);
            int reads = f.ds.payloadReads.get();
            assertThatCode(
                            () -> {
                                var claim =
                                        new RunScheduleRepository(
                                                        FlowMaterialScopePostgresTest.trace(f))
                                                .claimSpecific(
                                                        i.run(),
                                                        Queue.INTERACTIVE,
                                                        "n2-red",
                                                        Duration.ofSeconds(30));
                                assertThat(claim).isNotNull();
                            })
                    .as("protected queue must claim only dispatch metadata before current")
                    .doesNotThrowAnyException();
            assertThat(
                            f.db.queryForObject(
                                    "select status from engine.run where id=?",
                                    String.class,
                                    i.run()))
                    .isEqualTo("PENDING");
            assertThat(
                            f.db.queryForObject(
                                    "select count(*) from engine.run_event where run_id=?",
                                    Long.class,
                                    i.run()))
                    .isZero();
            assertThat(f.ds.payloadReads.get()).isEqualTo(reads);
        }
    }

    @Test
    void
            legacyOriginalSchedulerAndFlowRemainFunctionalButExplicitProtectedQueueRejectsMissingAuthority()
                    throws Exception {
        try (var f = fixture()) {
            var snapshot = (com.fasterxml.jackson.databind.node.ObjectNode) f.snapshot().deepCopy();
            snapshot.set("definition", Json.read(SIMPLE));
            long run =
                    f.db.queryForObject(
                            "insert into"
                                + " engine.run(public_id,app_code,app_version_id,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs)"
                                + " values('legacy-n2','app-a',12,'TASK','PENDING','alice','tenant-a','pg:legacy','{}'::jsonb,?::jsonb,?::jsonb,?::jsonb,'{\"a\":7}'::jsonb)"
                                + " returning id",
                            Long.class,
                            Json.write(
                                    Map.of(
                                            "tenantId",
                                            "tenant-a",
                                            "principalId",
                                            "alice",
                                            "roles",
                                            Set.of("EDITOR"),
                                            "attributes",
                                            Map.of())),
                            Json.write(f.budget()),
                            Json.write(snapshot));
            int seq = 0;
            for (var node : snapshot.path("definition").path("nodes"))
                f.db.update(
                        "insert into"
                            + " engine.node_instance(run_id,node_def_id,step_seq,node_type,status,definition)"
                            + " values(?,?,?,?,'READY',?::jsonb)",
                        run,
                        node.path("id").asText(),
                        ++seq,
                        node.path("type").asText(),
                        node.toString());
            var t = FlowMaterialScopePostgresTest.trace(f);
            assertThat(
                            new RunScheduleRepository(t, true)
                                    .claimNext(Queue.INTERACTIVE, "iam", Duration.ofSeconds(30)))
                    .isNull();
            var legacyRepo = new RunScheduleRepository(t);
            var first =
                    legacyRepo.claimSpecific(
                            run, Queue.INTERACTIVE, "legacy-direct", Duration.ofSeconds(30));
            assertThat(first).isNotNull();
            assertThat(legacyRepo.release(first, Duration.ofSeconds(3))).isTrue();
            var second =
                    legacyRepo.claimSpecific(
                            run, Queue.INTERACTIVE, "legacy-direct", Duration.ofSeconds(30));
            assertThat(second)
                    .as("legacy direct entry retains its original specific-claim behavior")
                    .isNotNull();
            legacyRepo.release(second, Duration.ZERO);
            try (var scheduler =
                    new RunScheduler(new RunScheduleRepository(t), 1, 1, 10, 90000, 1000)) {
                new RunService(t, null, new FlowDriver(t, null), null, scheduler);
                scheduler.poll();
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (scheduler.activeWorkers() > 0 && System.nanoTime() < until) Thread.sleep(10);
                assertThat(scheduler.activeWorkers()).isZero();
                assertThat(
                                f.db.queryForObject(
                                        "select status from engine.run where id=?",
                                        String.class,
                                        run))
                        .isEqualTo("SUCCEEDED");
                assertThat(
                                f.db.queryForObject(
                                        "select output->>'a' from engine.run where id=?",
                                        String.class,
                                        run))
                        .isEqualTo("7");
            }
        }
    }

    @Test
    void grantExpiryAndPersistentDenialAreExcludedByBothClaimPathsWithoutPayloadOrNetwork()
            throws Exception {
        for (boolean expire : List.of(false, true))
            try (var f = fixture()) {
                long run = insert(f, SIMPLE);
                if (expire) f.iam.advanceClock(Duration.ofSeconds(300));
                else
                    assertThat(
                                    f.store.recordCheck(
                                            f.store.load(run),
                                            null,
                                            "AUTHORITY_DENIED",
                                            Duration.ofSeconds(3)))
                            .isTrue();
                int reads = f.ds.payloadReads.get(), calls = f.iam.requestAttempts();
                var repo = new RunScheduleRepository(FlowMaterialScopePostgresTest.trace(f), true);
                assertThat(
                                repo.claimSpecific(
                                        run, Queue.INTERACTIVE, "excluded", Duration.ofSeconds(30)))
                        .isNull();
                assertThat(repo.claimNext(Queue.INTERACTIVE, "excluded", Duration.ofSeconds(30)))
                        .isNull();
                assertThat(f.ds.payloadReads.get()).isEqualTo(reads);
                assertThat(f.iam.requestAttempts()).isEqualTo(calls);
            }
    }
}
