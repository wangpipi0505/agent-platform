package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.*;
import cn.agentplatform.engine.domain.RunAdmissionRejected;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.*;

@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class RunAdmissionBoundaryPostgresTest {
    private final TracePostgresTest fixture = new TracePostgresTest();

    @BeforeAll
    static void connect() throws Exception {
        TracePostgresTest.connect();
    }

    @BeforeEach
    void clean() {
        fixture.clean();
    }

    @Test
    void controlledDraftCannotCreateRunOrCallWhenInteractivePoolIsFull() {
        var clients = new ControlledDraftSkillTest.DraftFixture();
        try (var scheduler =
                new RunScheduler(
                        new RunScheduleRepository(TracePostgresTest.store), 1, 1, 20, 1000, 1000)) {
            var agent = new AgentDriver(TracePostgresTest.store, clients, new ContextAssembler());
            var runs =
                    new RunService(
                            TracePostgresTest.store,
                            clients,
                            new FlowDriver(TracePostgresTest.store, agent),
                            agent,
                            scheduler);
            try (var held = scheduler.reserveInteractive()) {
                var actor =
                        new Actor("engine-test", "alice", Set.of("EDITOR", "SERVICE"), Map.of());
                var error =
                        assertThrows(
                                RunAdmissionRejected.class,
                                () ->
                                        runs.createDraftTest(
                                                Json.read(
                                                        "{\"skillVersionId\":42,\"inputs\":{\"x\":7},\"requestId\":\"busy-draft\"}"),
                                                actor));
                assertEquals(429, error.status());
                assertEquals(0, clients.external.get());
                assertEquals(
                        0,
                        TracePostgresTest.db.queryForObject(
                                "select count(*) from engine.run where tenant_id='engine-test'",
                                Integer.class));
                assertEquals(
                        0,
                        TracePostgresTest.db.queryForObject(
                                "select count(*) from engine.tool_call", Integer.class));
            }
        }
    }

    @Test
    void reexecuteAndControlledReproduceUseTheSamePreWriteCapacityGate() {
        long original = fixture.run();
        TracePostgresTest.db.update(
                "update engine.run set status='SUCCEEDED' where id=?", original);
        String id =
                TracePostgresTest.db.queryForObject(
                        "select public_id from engine.run where id=?", String.class, original);
        var clients =
                new RuntimeClients() {
                    @Override
                    public JsonNode get(String service, String path, Actor actor) {
                        return Json.tree(Map.of("type", "FLOW", "config", Map.of()));
                    }
                };
        try (var scheduler =
                new RunScheduler(
                        new RunScheduleRepository(TracePostgresTest.store), 1, 1, 20, 1000, 1000)) {
            var agent = new AgentDriver(TracePostgresTest.store, clients, new ContextAssembler());
            var runs =
                    new RunService(
                            TracePostgresTest.store,
                            clients,
                            new FlowDriver(TracePostgresTest.store, agent),
                            agent,
                            scheduler);
            try (var held = scheduler.reserveInteractive()) {
                JsonNode request =
                        Json.read(
                                "{\"requestId\":\"busy-replay\",\"sourceDigest\":\"recorded-source\"}");
                assertEquals(
                        429,
                        assertThrows(
                                        RunAdmissionRejected.class,
                                        () ->
                                                runs.reexecuteFrozen(
                                                        id, request, TracePostgresTest.actor))
                                .status());
                assertEquals(
                        429,
                        assertThrows(
                                        RunAdmissionRejected.class,
                                        () ->
                                                runs.reproduceFrozen(
                                                        id, request, TracePostgresTest.actor))
                                .status());
                assertEquals(
                        1,
                        TracePostgresTest.db.queryForObject(
                                "select count(*) from engine.run where tenant_id='engine-test'",
                                Integer.class));
                assertEquals(
                        0,
                        TracePostgresTest.db.queryForObject(
                                "select count(*) from engine.model_invocation", Integer.class));
            }
        }
    }
}
