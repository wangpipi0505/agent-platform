package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class ControlledDraftSkillTest {
    private static final Actor editor =
            new Actor("engine-test", "alice", Set.of("EDITOR", "SERVICE"), Map.of());
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
    void controlledDraftWriteRequiresRealApprovalAndKeepsExactAuthority() throws Exception {
        var clients = new DraftFixture();
        var agent = new AgentDriver(TracePostgresTest.store, clients, new ContextAssembler());
        var runs =
                SchedulerTestResources.runs(
                        TracePostgresTest.store,
                        clients,
                        new FlowDriver(TracePostgresTest.store, agent),
                        agent);
        var result =
                runs.createDraftTest(
                        Json.tree(
                                Map.of(
                                        "skillVersionId",
                                        42,
                                        "inputs",
                                        Map.of("x", 7),
                                        "requestId",
                                        "draft-accept-1")),
                        editor);
        String id = result.get("runId").toString();
        for (int i = 0;
                i < 50 && !runs.status(id, editor).get("status").equals("WAITING_APPROVAL");
                i++) Thread.sleep(20);
        assertEquals("WAITING_APPROVAL", runs.status(id, editor).get("status"));
        assertEquals(0, clients.external.get());
        var row =
                TracePostgresTest.db.queryForMap("select * from engine.run where public_id=?", id);
        assertEquals(42, TraceStore.number(row, "controlled_draft_skill_version_id"));
        assertEquals("DEBUG", row.get("trigger_type"));
        var approver = new Actor("engine-test", "bob", Set.of("APPROVER"), Map.of());
        var approvals = new ApprovalService(TracePostgresTest.store);
        var pending = approvals.list(approver).getFirst();
        approvals.approve(
                id,
                TraceStore.number(pending, "nodeInstanceId"),
                Json.tree(
                        Map.of(
                                "requestId",
                                "accept-write",
                                "snapshotDigest",
                                pending.get("snapshotDigest"),
                                "approved",
                                true)),
                approver);
        runs.drive(TraceStore.number(row, "id"), editor);
        assertEquals("SUCCEEDED", runs.status(id, editor).get("status"));
        assertEquals(1, clients.external.get());
        assertEquals(
                1,
                TracePostgresTest.db.queryForObject(
                        "select count(*) from engine.tool_call where run_id=? and"
                                + " status='SUCCEEDED'",
                        Integer.class,
                        row.get("id")));
    }

    @Test
    void ordinaryDebugCannotEnableDraftThroughBodyFlags() {
        var clients = new DraftFixture();
        var agent = new AgentDriver(TracePostgresTest.store, clients, new ContextAssembler());
        var runs =
                SchedulerTestResources.runs(
                        TracePostgresTest.store,
                        clients,
                        new FlowDriver(TracePostgresTest.store, agent),
                        agent);
        assertThrows(
                PlatformException.class,
                () ->
                        runs.create(
                                "ordinary",
                                Json.tree(
                                        Map.of(
                                                "inputs",
                                                Map.of("x", 7),
                                                "controlledDraftSkillVersionId",
                                                42)),
                                "DEBUG",
                                editor));
        assertEquals(0, clients.external.get());
    }

    @Test
    void publishedAndArchivedExactConnectionTestsKeepNormalWriteApproval() throws Exception {
        for (String state : List.of("PUBLISHED", "ARCHIVED")) {
            var clients = new DraftFixture();
            clients.state = state;
            var agent = new AgentDriver(TracePostgresTest.store, clients, new ContextAssembler());
            var runs =
                    SchedulerTestResources.runs(
                            TracePostgresTest.store,
                            clients,
                            new FlowDriver(TracePostgresTest.store, agent),
                            agent);
            var result =
                    runs.createDraftTest(
                            Json.tree(
                                    Map.of(
                                            "skillVersionId",
                                            42,
                                            "inputs",
                                            Map.of("x", 7),
                                            "requestId",
                                            "connection-" + state)),
                            editor);
            String id = result.get("runId").toString();
            for (int i = 0;
                    i < 100 && !runs.status(id, editor).get("status").equals("WAITING_APPROVAL");
                    i++) Thread.sleep(10);
            var status = runs.status(id, editor);
            assertEquals("WAITING_APPROVAL", status.get("status"));
            assertEquals(0, clients.external.get());
            assertEquals(42, ((Number) status.get("controlledSkillVersionId")).intValue());
            assertEquals(state, status.get("controlledSkillStatus"));
            assertNotNull(status.get("callId"));
            var approver = new Actor("engine-test", "bob", Set.of("APPROVER"), Map.of());
            var approvals = new ApprovalService(TracePostgresTest.store);
            var pending =
                    approvals.list(approver).stream()
                            .filter(row -> row.get("runId").equals(id))
                            .findFirst()
                            .orElseThrow();
            approvals.approve(
                    id,
                    TraceStore.number(pending, "nodeInstanceId"),
                    Json.tree(
                            Map.of(
                                    "requestId",
                                    "approve-" + state,
                                    "snapshotDigest",
                                    pending.get("snapshotDigest"),
                                    "approved",
                                    true)),
                    approver);
            runs.drive(
                    TracePostgresTest.db.queryForObject(
                            "select id from engine.run where public_id=?", Long.class, id),
                    editor);
            assertEquals("SUCCEEDED", runs.status(id, editor).get("status"));
            assertEquals(1, clients.external.get());
        }
    }

    @Test
    void disabledExactVersionIsRejectedBeforeCreatingConnectionRun() {
        var clients = new DraftFixture();
        clients.disabled = true;
        clients.state = "PUBLISHED";
        var agent = new AgentDriver(TracePostgresTest.store, clients, new ContextAssembler());
        var runs =
                SchedulerTestResources.runs(
                        TracePostgresTest.store,
                        clients,
                        new FlowDriver(TracePostgresTest.store, agent),
                        agent);
        var error =
                assertThrows(
                        PlatformException.class,
                        () ->
                                runs.createDraftTest(
                                        Json.tree(
                                                Map.of(
                                                        "skillVersionId",
                                                        42,
                                                        "inputs",
                                                        Map.of("x", 7))),
                                        editor));
        assertEquals("SKILL_UNAVAILABLE", error.code());
        assertEquals(0, clients.external.get());
    }

    @Test
    void publishedReadonlyConnectionNeedsNoBusinessApproval() throws Exception {
        var clients = new DraftFixture();
        clients.state = "PUBLISHED";
        var execution =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        clients.definition.path("execution");
        execution.put("readonly", true);
        execution.put("sideEffect", false);
        execution.remove("confirmRoles");
        var agent = new AgentDriver(TracePostgresTest.store, clients, new ContextAssembler());
        var runs =
                SchedulerTestResources.runs(
                        TracePostgresTest.store,
                        clients,
                        new FlowDriver(TracePostgresTest.store, agent),
                        agent);
        var initial =
                runs.createDraftTest(
                        Json.tree(
                                Map.of(
                                        "skillVersionId",
                                        42,
                                        "inputs",
                                        Map.of("x", 7),
                                        "requestId",
                                        "published-readonly")),
                        editor);
        String id = initial.get("runId").toString();
        for (int i = 0; i < 100 && !runs.status(id, editor).get("status").equals("SUCCEEDED"); i++)
            Thread.sleep(10);
        assertEquals("SUCCEEDED", runs.status(id, editor).get("status"));
        assertEquals(1, clients.external.get());
        assertEquals(7, Json.tree(runs.status(id, editor).get("inputs")).path("x").asInt());
    }

    static final class DraftFixture extends RuntimeClients {
        String state = "DRAFT";
        boolean disabled;
        final AtomicInteger external = new AtomicInteger();
        final JsonNode definition =
                Json.read(
                        "{\"code\":\"draft_write\",\"name\":\"draft\",\"type\":\"HTTP\",\"endpoint\":\"http://fixture/write\",\"authRef\":\"\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"number\"}}},\"outputSchema\":{\"type\":\"object\"},\"execution\":{\"readonly\":false,\"sideEffect\":true,\"confirmRoles\":[\"APPROVER\"]},\"guard\":{\"roles\":[\"EDITOR\"],\"attributes\":{}},\"timeoutMs\":3000}");

        JsonNode metadata() {
            var n = (com.fasterxml.jackson.databind.node.ObjectNode) definition.deepCopy();
            n.put("skillVersionId", 42);
            n.put("versionNo", 1);
            n.put("status", state);
            n.put("skillStatus", disabled ? "DISABLED" : "ENABLED");
            n.put("digest", Digest.sha256(Json.canonical(definition)));
            n.set("definition", definition);
            return n;
        }

        @Override
        public JsonNode get(String service, String path, Actor actor) {
            if (service.equals("skill")) return metadata();
            return Json.tree(
                    Map.of(
                            "appCode",
                            "ordinary",
                            "type",
                            "FLOW",
                            "config",
                            Map.of(
                                    "skillRefs",
                                    List.of(Map.of("code", "draft_write", "version", 1))),
                            "definition",
                            Map.of()));
        }

        @Override
        public JsonNode post(String service, String path, Object body, Actor actor) {
            JsonNode req = Json.tree(body);
            var calls = new ToolCalls(TracePostgresTest.store);
            long call = req.path("callId").asLong();
            var authority = calls.get(call, actor);
            if (state.equals("DRAFT"))
                assertEquals(42, TraceStore.number(authority, "controlled_draft_skill_version_id"));
            else {
                assertNull(authority.get("controlled_draft_skill_version_id"));
                assertEquals(42, TraceStore.number(authority, "controlled_skill_test_version_id"));
            }
            assertEquals("DEBUG", authority.get("trigger_type"));
            if (state.equals("DRAFT"))
                assertEquals(
                        definition, Json.tree(authority.get("controlled_draft_skill_contract")));
            if (definition.path("execution").path("sideEffect").asBoolean()
                    && !Boolean.TRUE.equals(calls.approval(call, actor).get("approved")))
                return Json.tree(
                        Map.of(
                                "status",
                                "WAITING_APPROVAL",
                                "confirmation",
                                Map.of(
                                        "roles",
                                        List.of("APPROVER"),
                                        "parametersDigest",
                                        authority.get("args_digest"))));
            var claim =
                    Json.tree(
                            Map.of(
                                    "owner",
                                    req.path("leaseOwner").asText(),
                                    "attemptId",
                                    req.path("attemptId").asLong()));
            assertEquals(true, calls.claim(call, claim, actor).get("claimed"));
            calls.submitted(call, claim, actor);
            external.incrementAndGet();
            var fact = (com.fasterxml.jackson.databind.node.ObjectNode) claim.deepCopy();
            fact.put("status", "SUCCEEDED");
            fact.set("result", Json.tree(Map.of("structured", Map.of("x", 7))));
            calls.fact(call, fact, actor);
            return Json.tree(Map.of("status", "SUCCEEDED", "result", fact.path("result")));
        }
    }
}
