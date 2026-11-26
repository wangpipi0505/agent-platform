package cn.agentplatform.engine.infra;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.engine.api.TaskController;
import cn.agentplatform.engine.application.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static cn.agentplatform.engine.infra.SignedEngineRequest.signedRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class ModelFailureTaskPostgresTest {
    private static final String APP = "r49chat";

    @Test
    void confirmedModel503ReachesBothTaskViews() throws Exception {
        exercise(false);
    }

    @Test
    void interruptedModelCallIsClosedByTheNextWorkerWithoutResending() throws Exception {
        exercise(true);
    }

    private void exercise(boolean interrupted) throws Exception {
        var source = new DriverManagerDataSource(EngineTestDatabase.url(),
                EngineTestDatabase.user(), EngineTestDatabase.password());
        Flyway.configure().dataSource(source).schemas("engine").defaultSchema("engine")
                .locations("classpath:db/migration").outOfOrder(true).load().migrate();
        var db = new JdbcTemplate(source);
        var calls = new AtomicInteger();
        var model = controlledModel(calls, interrupted);
        var origin = "http://127.0.0.1:" + model.getAddress().getPort();
        var actor = new Actor("tenant-a", "alice", Set.of("USER"),
                Map.of("region", Set.of("A"), "dataLabels", Set.of("initial")));
        var snapshot = publishedChat(origin);
        var iam = new LoopbackAuthority();
        iam.portalActor(actor);
        try (iam; var gateway = iam.client("gateway"); var engine = iam.client("engine");
             var decoder = new TrustedJwtDecoder(iam.config("engine"), iam.clock(), true);
             var clients = new RuntimeClients(iam.config("engine"), engine, origin, true, origin) {
                 @Override public JsonNode publishedForRuntime(String code, Actor owner) { return snapshot; }
                 @Override public JsonNode published(String code, HttpCurrentAuthorityClient.RetainedAuthorization proof) {
                     return snapshot;
                 }
             };
             var chunks = new ChunkBus()) {
            var trace = new TraceStore(db, new DataSourceTransactionManager(source), iam.clock());
            var store = new RunAuthorityStore(source, iam.clock());
            var authority = new RunAuthority(store, engine, iam.clock(), Duration.ofSeconds(3));
            var materials = new ModelInputMaterials(source, engine, iam.clock());
            var repository = new RunScheduleRepository(trace, true);
            var dispatcher = new TaskDispatcher(trace);
            long runId, taskId, generation;
            boolean recoverableBeforeGrantExpiry = false;
            Tasks tasks;
            try (var worker = new RunScheduler(repository, 1, 1, 100, 90000, 1000) {
                // Drive each generation synchronously so the test controls the interruption window.
                @Override public void created(long id, Admission ticket) { if (ticket != null) ticket.close(); }
            }) {
                var runs = new RunService(trace, clients, null, null, worker, authority, store, iam.clock(), materials, chunks);
                tasks = new Tasks(trace, new SubagentPlanning(trace), clients,
                        new TaskAgentExecution(trace, runs, new StepExecutionMapping(trace), dispatcher));
                var controller = new TaskController(tasks, null, null);
                var request = Json.tree(Map.of("mode", "AGENT", "requestId", UUID.randomUUID().toString(), "goal", "test 503"));
                var root = new ResourceBinding("app-version", APP + "@1", snapshot.path("candidateSha256").asText());
                var created = signedRequest(iam, gateway, decoder, new ServiceAuthorization("engine", new ServiceRoutePolicy(), engine),
                        controller, "POST", "/v1/apps/" + APP + "/tasks", "create",
                        new ServiceCall("engine", "proxy.engine.post.TaskController.create", root),
                        () -> controller.create(APP, request));
                taskId = tasks.resolveTaskId(created.path("taskId").asText(), actor);
                runId = db.queryForObject("select run_id from engine.task_step where task_id=? and step_id='entry'", Long.class, taskId);
                if (interrupted) {
                    assertThatThrownBy(() -> runs.drive(runId, actor)).isInstanceOfSatisfying(PlatformException.class,
                            error -> assertThat(error.code()).isEqualTo("INITIAL_STREAM_INVALID"));
                    assertThat(db.queryForObject("select status from engine.model_invocation where run_id=?", String.class, runId))
                            .isEqualTo("DISPATCHED");
                    // A replacement must be able to claim the node inside a 60-second runtime grant.
                    recoverableBeforeGrantExpiry = Boolean.TRUE.equals(db.queryForObject("""
                            select lease_until between clock_timestamp() and clock_timestamp() + interval '60 seconds'
                            from engine.node_instance where run_id=?
                            """, Boolean.class, runId));
                } else {
                    runs.drive(runId, actor);
                }
                assertThat(calls.get()).isOne();
                generation = db.queryForObject("select dispatch_generation from engine.run where id=?", Long.class, runId);
            }
            if (interrupted) {
                // Advance the lease boundary for just this run, leaving its dispatch and model facts intact.
                db.update("update engine.node_instance set lease_until=clock_timestamp()-interval '1 second' where run_id=?", runId);
                db.update("update engine.run set dispatch_next_at=clock_timestamp() where id=?", runId);
                try (var replacement = new RunScheduler(repository, 1, 1, 100, 90000, 1000)) {
                    new RunService(trace, clients, null, null, replacement, authority, store, iam.clock(), materials, chunks)
                            .drive(runId, actor);
                }
                assertThat(db.queryForObject("select dispatch_generation from engine.run where id=?", Long.class, runId))
                        .isGreaterThan(generation);
                assertThat(recoverableBeforeGrantExpiry).as("node lease fits within the runtime grant").isTrue();
            }
            dispatcher.pollEntries();
            String reason = interrupted ? "MODEL_RESULT_UNKNOWN" : "SYS-MODEL-503";
            assertThat(List.of(tasks.detail(taskId, actor), tasks.result(taskId, actor))).allSatisfy(view ->
                    assertThat(view).containsEntry("state", "FAILED").containsEntry("reason", reason).containsEntry("retryable", false));
            assertThat(db.queryForList("select status, sdk_attempts from engine.model_invocation where run_id=?", runId))
                    .singleElement().satisfies(row -> assertThat(row).containsEntry("status", interrupted ? "UNKNOWN" : "FAILED")
                            .containsEntry("sdk_attempts", 1));
            assertThat(calls.get()).isOne();
        } finally {
            model.stop(0);
        }
    }

    private static HttpServer controlledModel(AtomicInteger calls, boolean interrupted) throws java.io.IOException {
        var model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/internal/model/chat/stream", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                calls.incrementAndGet();
                // A recorded upstream 503 has a terminal frame. A lost reply has no receipt.
                String frames = interrupted ? "event: final\n" : "event: failed\ndata: {\"code\":\"SYS-MODEL-503\"}\n\n";
                var bytes = frames.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        model.start();
        return model;
    }

    private static JsonNode publishedChat(String origin) {
        var entry = Map.of("baseUrl", origin, "providerId", 1, "modelName", "fixture");
        var ref = Map.of("modelEntryId", 1, "modelBindingRef",
                Map.of("bindingId", 1, "sha256", Digest.sha256(Json.canonical(entry))), "entry", entry);
        return Json.tree(Map.of("appCode", APP, "type", "CHAT", "versionId", 1, "versionNo", 1,
                "candidateSha256", Digest.sha256(APP), "config", Map.of("modelEntryId", 1, "systemPrompt", "test"),
                "dependencyVersions", Map.of("modelRefs", List.of(ref))));
    }
}
