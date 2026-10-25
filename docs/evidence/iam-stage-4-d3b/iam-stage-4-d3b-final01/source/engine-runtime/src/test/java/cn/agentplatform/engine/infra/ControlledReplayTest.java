package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.*;

@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class ControlledReplayTest {
    final TracePostgresTest fixture = new TracePostgresTest();

    @BeforeAll
    static void connect() throws Exception {
        TracePostgresTest.connect();
    }

    @BeforeEach
    void clean() {
        fixture.clean();
    }

    RuntimeClients forbidden =
            new RuntimeClients() {
                @Override
                public JsonNode get(String s, String p, Actor a) {
                    throw new AssertionError("Controlled replay must not call HTTP GET " + p);
                }

                @Override
                public JsonNode post(String s, String p, Object b, Actor a) {
                    throw new AssertionError("Controlled replay must not call HTTP POST " + p);
                }

                @Override
                public JsonNode chatStream(
                        Object b, Actor a, java.util.function.Consumer<String> c) {
                    throw new AssertionError("Controlled replay must not call model");
                }
            };

    Map<String, Object> skill() {
        return Map.of(
                "code",
                "lookup",
                "name",
                "lookup",
                "skillVersionId",
                42,
                "contractDigest",
                Digest.sha256("fixture-contract"),
                "guard",
                Map.of("roles", List.of("EDITOR")),
                "execution",
                Map.of("readonly", true),
                "inputSchema",
                Map.of("type", "object"),
                "outputSchema",
                Map.of("type", "object"),
                "definition",
                Map.of(
                        "type",
                        "function",
                        "function",
                        Map.of("name", "lookup", "parameters", Map.of("type", "object"))));
    }

    long source(JsonNode snapshot, JsonNode nodeDefinition) {
        long r = fixture.run(), n = fixture.node(r, "READY");
        TracePostgresTest.db.update(
                "update engine.run set snapshot=?::jsonb where id=?", Json.write(snapshot), r);
        TracePostgresTest.db.update(
                "update engine.node_instance set node_type=?,definition=?::jsonb where id=?",
                nodeDefinition.path("type").asText(),
                Json.write(nodeDefinition),
                n);
        var lease = TracePostgresTest.store.claim(n, "source");
        var req = fixture.call(r, n, lease);
        var calls = new ToolCalls(TracePostgresTest.store);
        long call =
                TraceStore.number(
                        calls.register(req.register(), TracePostgresTest.actor), "callId");
        calls.claim(call, req.claim(), TracePostgresTest.actor);
        var fact = (com.fasterxml.jackson.databind.node.ObjectNode) req.claim().deepCopy();
        fact.put("status", "SUCCEEDED");
        String publicId =
                TracePostgresTest.db.queryForObject(
                        "select public_id from engine.run where id=?", String.class, r);
        fact.set(
                "result",
                Json.tree(
                        Map.of(
                                "status",
                                "SUCCEEDED",
                                "structured",
                                Map.of("value", 42),
                                "fileRefs",
                                List.of(),
                                "owner",
                                Map.of(
                                        "tenantId",
                                        "engine-test",
                                        "principalId",
                                        "alice",
                                        "runId",
                                        publicId,
                                        "nodeInstanceId",
                                        n,
                                        "attemptId",
                                        lease.get("attemptId"),
                                        "callId",
                                        call,
                                        "skillVersionId",
                                        42,
                                        "skillCode",
                                        "lookup"),
                                "guardTrace",
                                Map.of(
                                        "argsDigest",
                                        Digest.sha256(Json.canonical(Map.of("x", 1)))))));
        calls.fact(call, fact, TracePostgresTest.actor);
        TracePostgresTest.store.commit(
                r,
                n,
                TraceStore.number(lease, "attemptId"),
                lease.get("lease_owner").toString(),
                Map.of("value", 999));
        TracePostgresTest.db.update(
                "update engine.run set status='SUCCEEDED',output='{\"value\":999}' where id=?", r);
        return r;
    }

    RunService runs() {
        var agent = new AgentDriver(TracePostgresTest.store, forbidden, new ContextAssembler());
        return SchedulerTestResources.runs(
                TracePostgresTest.store,
                forbidden,
                new FlowDriver(TracePostgresTest.store, agent),
                agent);
    }

    Map<String, Object> replay(long source) throws Exception {
        var runs = runs();
        var service = new ReplayService(TracePostgresTest.store, runs);
        String id =
                TracePostgresTest.db.queryForObject(
                        "select public_id from engine.run where id=?", String.class, source);
        var log = service.log(id, TracePostgresTest.actor);
        var result =
                service.reproduce(
                        id,
                        Json.tree(
                                Map.of(
                                        "requestId",
                                        "controlled-one",
                                        "sourceDigest",
                                        log.get("sourceDigest"))),
                        TracePostgresTest.actor);
        String run = result.get("runId").toString();
        for (int i = 0; i < 300; i++) {
            var status = runs.status(run, TracePostgresTest.actor);
            if (Set.of("SUCCEEDED", "FAILED").contains(status.get("status"))) return status;
            Thread.sleep(10);
        }
        return runs.status(run, TracePostgresTest.actor);
    }

    @Test
    void skillAndConditionRecomputeRatherThanCopyingSourceOutput() throws Exception {
        var nodes =
                Json.read(
                        "[{\"id\":\"s\",\"type\":\"START\"},{\"id\":\"n1\",\"type\":\"SKILL\",\"args\":{\"x\":1},\"skillRef\":{\"code\":\"lookup\",\"skillVersionId\":42,\"execution\":{\"readonly\":true}}},{\"id\":\"condition\",\"type\":\"CONDITION\",\"branches\":[{\"when\":\"${n1.output.value}"
                            + " == 42\",\"to\":\"yes\"},{\"else\":\"no\"}]},{\"id\":\"yes\",\"type\":\"CODE\",\"expression\":\"${n1.output.value}"
                            + " + 1\"},{\"id\":\"no\",\"type\":\"CODE\",\"expression\":\"0\"},{\"id\":\"end\",\"type\":\"END\"}]");
        var edges =
                List.of(
                        Map.of("from", "s", "to", "n1"),
                        Map.of("from", "n1", "to", "condition"),
                        Map.of("from", "condition", "to", "yes"),
                        Map.of("from", "condition", "to", "no"),
                        Map.of("from", "yes", "to", "end"),
                        Map.of("from", "no", "to", "end"));
        var snapshot =
                Json.tree(
                        Map.of(
                                "appCode",
                                "test",
                                "type",
                                "FLOW",
                                "config",
                                Map.of(
                                        "guard",
                                        Map.of("roles", List.of("EDITOR")),
                                        "tools",
                                        List.of(skill())),
                                "definition",
                                Map.of(
                                        "nodes",
                                        nodes,
                                        "edges",
                                        edges,
                                        "outputs",
                                        List.of(
                                                Map.of(
                                                        "name",
                                                        "value",
                                                        "from",
                                                        "${yes.output}")))));
        long r = source(snapshot, nodes.get(1));
        var replay = replay(r);
        assertEquals("SUCCEEDED", replay.get("status"), Json.write(replay));
        assertEquals(43, Json.tree(replay.get("output")).path("value").asInt());
        String id = replay.get("runId").toString();
        assertEquals(
                true,
                TracePostgresTest.db.queryForObject(
                        "select t.is_mock from engine.tool_call t join engine.run r on"
                                + " r.id=t.run_id where r.public_id=?",
                        Boolean.class,
                        id));
    }

    @Test
    void agentUsesRecordedModelAndToolResponsesThroughNormalLoop() throws Exception {
        var entry = Json.read("{\"modelEntryId\":1}");
        var config =
                Json.tree(
                        Map.of(
                                "modelEntryId",
                                1,
                                "guard",
                                Map.of("roles", List.of("EDITOR")),
                                "tools",
                                List.of(skill())));
        var node = Json.read("{\"id\":\"n1\",\"type\":\"AGENT\",\"args\":{}}");
        var snapshot =
                Json.tree(
                        Map.of(
                                "appCode",
                                "test",
                                "type",
                                "CHAT",
                                "config",
                                config,
                                "definition",
                                Map.of("nodes", List.of(node), "edges", List.of()),
                                "dependencyVersions",
                                Map.of(
                                        "modelRefs",
                                        List.of(
                                                Map.of(
                                                        "modelEntryId",
                                                        1,
                                                        "entry",
                                                        entry,
                                                        "modelBindingRef",
                                                        Map.of(
                                                                "bindingId",
                                                                1,
                                                                "sha256",
                                                                Digest.sha256(
                                                                        Json.canonical(
                                                                                entry))))))));
        long r = source(snapshot, node);
        long nid =
                TracePostgresTest.db.queryForObject(
                        "select id from engine.node_instance where run_id=?", Long.class, r);
        var assembler = new ContextAssembler();
        var tools = List.of(skill().get("definition"));
        var first = assembler.assemble(config, Json.read("{}"), List.of(), tools);
        first.put("modelBindingRef", FrozenModels.reference(snapshot, 1));
        var firstAnswer =
                Json.read(
                        "{\"text\":\"\",\"toolCalls\":[{\"id\":\"tc1\",\"name\":\"lookup\",\"arguments\":{\"x\":1}}],\"usage\":{}}");
        String sourceContent =
                TracePostgresTest.db.queryForObject(
                        "select result::text from engine.tool_call where run_id=?",
                        String.class,
                        r);
        var history =
                List.<Map<String, Object>>of(
                        Map.of(
                                "role",
                                "assistant",
                                "content",
                                "",
                                "tool_calls",
                                List.of(
                                        Map.of(
                                                "id",
                                                "tc1",
                                                "type",
                                                "function",
                                                "function",
                                                Map.of(
                                                        "name",
                                                        "lookup",
                                                        "arguments",
                                                        "{\"x\":1}")))),
                        Map.of("role", "tool", "tool_call_id", "tc1", "content", sourceContent));
        var second = assembler.assemble(config, Json.read("{}"), history, tools);
        second.put("modelBindingRef", FrozenModels.reference(snapshot, 1));
        for (int round = 0; round < 2; round++) {
            var request = round == 0 ? first : second;
            var response =
                    round == 0
                            ? firstAnswer
                            : Json.read(
                                    "{\"text\":\"recomputed42\",\"toolCalls\":[],\"usage\":{}}");
            TracePostgresTest.db.update(
                    "insert into"
                        + " engine.model_invocation(run_id,node_instance_id,round_no,kind,source_request,final_request,response,status)"
                        + " values(?,?,?,'MAIN',?::jsonb,?::jsonb,?::jsonb,'SUCCEEDED')",
                    r,
                    nid,
                    round,
                    Json.write(request),
                    Json.write(request),
                    Json.write(response));
        }
        var replay = replay(r);
        assertEquals("SUCCEEDED", replay.get("status"), Json.write(replay));
        assertEquals("recomputed42", Json.tree(replay.get("output")).path("text").asText());
        var currentEnvelope =
                Json.read(
                        TracePostgresTest.db.queryForObject(
                                "select t.result::text from engine.tool_call t join engine.run r on"
                                        + " r.id=t.run_id where r.public_id=?",
                                String.class,
                                replay.get("runId")));
        assertEquals(replay.get("runId"), currentEnvelope.path("owner").path("runId").asText());
        var replayMaterial =
                Json.read(
                        TracePostgresTest.db.queryForObject(
                                "select m.source_request::text from engine.model_invocation m join"
                                        + " engine.run r on r.id=m.run_id where r.public_id=? and"
                                        + " m.round_no=1",
                                String.class,
                                replay.get("runId")));
        assertEquals(
                sourceContent,
                replayMaterial
                        .path("messages")
                        .get(replayMaterial.path("messages").size() - 1)
                        .path("content")
                        .asText());
        assertEquals(
                2,
                TracePostgresTest.db.queryForObject(
                        "select count(*) from engine.model_invocation m join engine.run r on"
                                + " r.id=m.run_id where r.public_id=? and m.is_mock=true and"
                                + " m.sdk_attempts=0",
                        Integer.class,
                        replay.get("runId")));
    }
}
