package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.*;

@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class ReplayBoundaryTest {
    final TracePostgresTest fixture = new TracePostgresTest();

    @BeforeAll
    static void connect() throws Exception {
        TracePostgresTest.connect();
    }

    @BeforeEach
    void clean() {
        fixture.clean();
    }

    record Replay(
            Map<String, Object> run,
            Map<String, Object> node,
            Map<String, Object> call,
            JsonNode args,
            long sourceCall,
            JsonNode envelope,
            Actor actor) {}

    Replay recorded(
            JsonNode guard, JsonNode schema, JsonNode projected, JsonNode args, Actor actor) {
        var definition =
                Json.tree(
                        Map.of(
                                "code",
                                "lookup",
                                "inputSchema",
                                Map.of("type", "object"),
                                "outputSchema",
                                schema,
                                "guard",
                                guard,
                                "returnSpec",
                                Map.of("structuredPath", "$.data.value")));
        String contract = Digest.sha256(Json.canonical(definition));
        var tool =
                Json.tree(
                        Map.of(
                                "code",
                                "lookup",
                                "name",
                                "lookup",
                                "skillVersionId",
                                42,
                                "contractDigest",
                                contract,
                                "guard",
                                guard,
                                "contract",
                                definition,
                                "execution",
                                Map.of("readonly", true),
                                "inputSchema",
                                Map.of("type", "object"),
                                "outputSchema",
                                schema,
                                "definition",
                                Map.of(
                                        "type",
                                        "function",
                                        "function",
                                        Map.of(
                                                "name",
                                                "lookup",
                                                "parameters",
                                                Map.of("type", "object")))));
        var snapshot = Json.tree(Map.of("config", Map.of("tools", List.of(tool))));
        long source = fixture.run(), sn = fixture.node(source, "READY");
        TracePostgresTest.db.update(
                "update engine.run set snapshot=?::jsonb where id=?", Json.write(snapshot), source);
        var sl = TracePostgresTest.store.claim(sn, "source");
        var sreq =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        fixture.call(source, sn, sl).register().deepCopy();
        sreq.set("arguments", args);
        var calls = new ToolCalls(TracePostgresTest.store);
        long sourceCall =
                TraceStore.number(calls.register(sreq, TracePostgresTest.actor), "callId");
        var sc = fixture.call(source, sn, sl).claim();
        calls.claim(sourceCall, sc, TracePostgresTest.actor);
        String sourceId =
                TracePostgresTest.db.queryForObject(
                        "select public_id from engine.run where id=?", String.class, source);
        var envelope = Json.object();
        envelope.put("status", "SUCCEEDED");
        envelope.set(
                "owner",
                Json.tree(
                        Map.of(
                                "tenantId",
                                "engine-test",
                                "principalId",
                                "alice",
                                "runId",
                                sourceId,
                                "nodeInstanceId",
                                sn,
                                "attemptId",
                                sl.get("attemptId"),
                                "callId",
                                sourceCall,
                                "skillVersionId",
                                42,
                                "skillCode",
                                "lookup")));
        envelope.set("structured", projected);
        envelope.set("fileRefs", Json.tree(List.of()));
        envelope.set(
                "guardTrace", Json.tree(Map.of("argsDigest", Digest.sha256(Json.canonical(args)))));
        var fact = (com.fasterxml.jackson.databind.node.ObjectNode) sc.deepCopy();
        fact.put("status", "SUCCEEDED");
        fact.set("result", envelope);
        calls.fact(sourceCall, fact, TracePostgresTest.actor);
        TracePostgresTest.db.update("update engine.run set status='SUCCEEDED' where id=?", source);
        long target = fixture.run(), tn = fixture.node(target, "READY");
        var replay = (com.fasterxml.jackson.databind.node.ObjectNode) snapshot.deepCopy();
        replay.set(
                "controlledReplay",
                Json.tree(Map.of("sourceRunId", sourceId, "mode", "CONTROLLED")));
        TracePostgresTest.db.update(
                "update engine.run set snapshot=?::jsonb where id=?", Json.write(replay), target);
        var tl = TracePostgresTest.store.claim(tn, "target");
        var treq =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        fixture.call(target, tn, tl).register().deepCopy();
        treq.set("arguments", args);
        var registered = calls.register(treq, actor);
        return new Replay(
                TracePostgresTest.db.queryForMap("select * from engine.run where id=?", target),
                tl,
                registered,
                args,
                sourceCall,
                envelope,
                actor);
    }

    JsonNode invoke(Replay r) {
        return ControlledReplay.skill(
                TracePostgresTest.store,
                r.run(),
                r.node(),
                "lookup",
                42,
                r.args(),
                1,
                r.call(),
                r.actor());
    }

    Actor actor(Set<String> regions) {
        return new Actor("engine-test", "alice", Set.of("EDITOR"), Map.of("region", regions));
    }

    @Test
    void narrowedCurrentScopeCannotReuseWiderRecordedResult() {
        var r =
                recorded(
                        Json.read(
                                "{\"roles\":[\"EDITOR\"],\"attributes\":{\"region\":[\"A\",\"B\"]}}"),
                        Json.read("{\"type\":\"object\"}"),
                        Json.read("{\"regions\":[\"A\",\"B\"]}"),
                        Json.read("{\"region\":[\"A\",\"B\"]}"),
                        actor(Set.of("A")));
        assertThrows(RuntimeException.class, () -> invoke(r));
        assertEquals(
                "REGISTERED",
                TracePostgresTest.db.queryForObject(
                        "select status from engine.tool_call where id=?",
                        String.class,
                        r.call().get("callId")));
    }

    @Test
    void emptyGuardAttributeUsesVerifiedNonemptyActorSetAndCurrentOwner() {
        var r =
                recorded(
                        Json.read("{\"roles\":[\"EDITOR\"],\"attributes\":{\"region\":[]}}"),
                        Json.read("{\"type\":\"object\"}"),
                        Json.read("{\"value\":42}"),
                        Json.read("{\"region\":[\"A\",\"B\"]}"),
                        actor(new LinkedHashSet<>(List.of("B", "A"))));
        var result = invoke(r);
        assertEquals(r.run().get("public_id"), result.path("owner").path("runId").asText());
        assertEquals(
                TraceStore.number(r.node(), "id"),
                result.path("owner").path("nodeInstanceId").asLong());
        assertEquals(
                TraceStore.number(r.call(), "callId"),
                result.path("owner").path("callId").asLong());
        assertEquals(r.sourceCall(), result.path("sourceProvenance").path("sourceCallId").asLong());
    }

    @Test
    void successfulReturnSpecScalarIsNotValidatedAgainstOriginalObjectSchema() {
        var r =
                recorded(
                        Json.read("{\"roles\":[\"EDITOR\"],\"attributes\":{}}"),
                        Json.read(
                                "{\"type\":\"object\",\"required\":[\"data\"],\"properties\":{\"data\":{\"type\":\"object\",\"required\":[\"value\"]}}}"),
                        Json.read("42"),
                        Json.read("{\"x\":1}"),
                        actor(Set.of("A")));
        assertEquals(42, invoke(r).path("structured").asInt());
    }

    @Test
    void missingGuardEvidenceCannotBeReused() {
        var r =
                recorded(
                        Json.read("{\"roles\":[\"EDITOR\"]}"),
                        Json.read("{\"type\":\"object\"}"),
                        Json.read("{}"),
                        Json.read("{\"x\":1}"),
                        actor(Set.of("A")));
        TracePostgresTest.db.update(
                "update engine.tool_call set result=result-'guardTrace' where id=?",
                r.sourceCall());
        assertThrows(RuntimeException.class, () -> invoke(r));
    }

    @Test
    void attachmentProjectionUsesStorageOnlyPortAndGetsCurrentRunOwnership() {
        var r =
                recorded(
                        Json.read("{\"roles\":[\"EDITOR\"]}"),
                        Json.read("{\"type\":\"object\",\"required\":[\"data\"]}"),
                        Json.read("null"),
                        Json.read("{\"x\":1}"),
                        actor(Set.of("A")));
        var source = (com.fasterxml.jackson.databind.node.ObjectNode) r.envelope().deepCopy();
        source.set(
                "fileRefs",
                Json.read(
                        "[{\"artifactId\":\"a_old\",\"downloadUrl\":\"/admin/artifacts/a_old/download\"}]"));
        TracePostgresTest.db.update(
                "update engine.tool_call set result=?::jsonb where id=?",
                Json.write(source),
                r.sourceCall());
        var copies = new java.util.concurrent.atomic.AtomicInteger();
        var ports =
                new RuntimeClients() {
                    @Override
                    public JsonNode get(String service, String path, Actor actor) {
                        throw new AssertionError("No GET/business/model calls");
                    }

                    @Override
                    public JsonNode post(String service, String path, Object body, Actor actor) {
                        assertEquals("skill", service);
                        assertEquals(
                                "/internal/skill-facts/" + r.sourceCall() + "/reproduce-artifacts",
                                path);
                        copies.incrementAndGet();
                        var req = Json.tree(body);
                        var authority =
                                new ToolCalls(TracePostgresTest.store)
                                        .get(req.path("callId").asLong(), actor);
                        assertEquals(
                                r.sourceCall(),
                                TraceStore.number(authority, "mock_source_call_id"));
                        assertEquals(
                                Digest.sha256(Json.canonical(source)),
                                authority.get("mock_source_digest"));
                        assertEquals(r.args(), Json.tree(authority.get("arguments")));
                        return Json.tree(
                                Map.of(
                                        "fileRefs",
                                        List.of(
                                                Map.of(
                                                        "artifactId",
                                                        "a_new",
                                                        "downloadUrl",
                                                        "/admin/artifacts/a_new/download",
                                                        "externalDownloadUrl",
                                                        "/v1/runs/"
                                                                + r.run().get("public_id")
                                                                + "/artifacts/a_new/download",
                                                        "bytes",
                                                        5000000,
                                                        "contentType",
                                                        "application/json",
                                                        "sha256",
                                                        "a".repeat(64)))));
                    }
                };
        var result =
                ControlledReplay.skill(
                        TracePostgresTest.store,
                        r.run(),
                        r.node(),
                        "lookup",
                        42,
                        r.args(),
                        1,
                        r.call(),
                        r.actor(),
                        ports);
        assertTrue(result.path("structured").isNull());
        assertEquals(1, copies.get());
        assertEquals("a_new", result.path("fileRefs").get(0).path("artifactId").asText());
        assertEquals(r.run().get("public_id"), result.path("owner").path("runId").asText());
        assertFalse(result.path("fileRefs").toString().contains("a_old"));
    }

    @Test
    void storagePortCannotReturnSourceArtifactPaths() {
        var r =
                recorded(
                        Json.read("{\"roles\":[\"EDITOR\"]}"),
                        Json.read("{\"type\":\"object\"}"),
                        Json.read("null"),
                        Json.read("{\"x\":1}"),
                        actor(Set.of("A")));
        var source = (com.fasterxml.jackson.databind.node.ObjectNode) r.envelope().deepCopy();
        source.set(
                "fileRefs",
                Json.read(
                        "[{\"artifactId\":\"a_old\",\"downloadUrl\":\"/admin/artifacts/a_old/download\"}]"));
        TracePostgresTest.db.update(
                "update engine.tool_call set result=?::jsonb where id=?",
                Json.write(source),
                r.sourceCall());
        var storage =
                new RuntimeClients() {
                    @Override
                    public JsonNode post(String service, String path, Object body, Actor actor) {
                        return Json.tree(
                                Map.of(
                                        "fileRefs",
                                        List.of(
                                                Map.of(
                                                        "artifactId",
                                                        "a_old",
                                                        "downloadUrl",
                                                        "/admin/artifacts/a_old/download",
                                                        "externalDownloadUrl",
                                                        "/v1/runs/other/artifacts/a_old/download"))));
                    }
                };
        var error =
                assertThrows(
                        PlatformException.class,
                        () ->
                                ControlledReplay.skill(
                                        TracePostgresTest.store,
                                        r.run(),
                                        r.node(),
                                        "lookup",
                                        42,
                                        r.args(),
                                        1,
                                        r.call(),
                                        r.actor(),
                                        storage));
        assertEquals("REPLAY_ARTIFACT_COPY_INVALID", error.code());
        assertNotEquals(
                "SUCCEEDED",
                TracePostgresTest.db.queryForObject(
                        "select status from engine.tool_call where id=?",
                        String.class,
                        r.call().get("callId")));
    }

    @Test
    void narrowedScopeRejectsBeforeStorageAndBeforeSavedModelAnswers() {
        var r =
                recorded(
                        Json.read(
                                "{\"roles\":[\"EDITOR\"],\"attributes\":{\"region\":[\"A\",\"B\"]}}"),
                        Json.read("{\"type\":\"object\"}"),
                        Json.read("null"),
                        Json.read("{\"region\":[\"A\",\"B\"]}"),
                        actor(Set.of("A")));
        var source = (com.fasterxml.jackson.databind.node.ObjectNode) r.envelope().deepCopy();
        source.set("fileRefs", Json.read("[{\"artifactId\":\"a_old\"}]"));
        TracePostgresTest.db.update(
                "update engine.tool_call set result=?::jsonb where id=?",
                Json.write(source),
                r.sourceCall());
        var storage =
                new RuntimeClients() {
                    @Override
                    public JsonNode post(String service, String path, Object body, Actor actor) {
                        throw new AssertionError("Scope must be checked before copying storage");
                    }
                };
        assertThrows(
                cn.agentplatform.engine.domain.RuntimeFault.class,
                () ->
                        ControlledReplay.skill(
                                TracePostgresTest.store,
                                r.run(),
                                r.node(),
                                "lookup",
                                42,
                                r.args(),
                                1,
                                r.call(),
                                r.actor(),
                                storage));
        String id =
                TraceStore.json(r.run().get("snapshot"))
                        .path("controlledReplay")
                        .path("sourceRunId")
                        .asText();
        var original = TracePostgresTest.store.run(id, r.actor());
        assertThrows(
                cn.agentplatform.engine.domain.RuntimeFault.class,
                () -> ControlledReplay.preflight(TracePostgresTest.store, original, r.actor()));
        assertEquals(
                "REGISTERED",
                TracePostgresTest.db.queryForObject(
                        "select status from engine.tool_call where id=?",
                        String.class,
                        r.call().get("callId")));
    }
}
