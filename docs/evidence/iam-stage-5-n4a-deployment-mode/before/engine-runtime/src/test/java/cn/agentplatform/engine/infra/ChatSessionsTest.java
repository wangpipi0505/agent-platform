package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.*;

@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class ChatSessionsTest {
    final TracePostgresTest fixture = new TracePostgresTest();

    @BeforeAll
    static void connect() throws Exception {
        TracePostgresTest.connect();
    }

    @BeforeEach
    void clean() {
        TracePostgresTest.db.update("delete from engine.chat_turn");
        TracePostgresTest.db.update("delete from engine.chat_session");
        fixture.clean();
    }

    @Test
    void turnsArePinnedOwnedIdempotentAndCarryPreviousRunMessages() throws Exception {
        var entry = Json.read("{\"modelEntryId\":1}");
        var snapshot =
                Json.tree(
                        Map.of(
                                "appCode",
                                "chat",
                                "type",
                                "CHAT",
                                "versionNo",
                                1,
                                "appVersionId",
                                1,
                                "config",
                                Map.of("modelEntryId", 1),
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
        var observed = new java.util.concurrent.CopyOnWriteArrayList<JsonNode>();
        var clients =
                new RuntimeClients() {
                    @Override
                    public JsonNode get(String service, String path, Actor actor) {
                        return snapshot;
                    }

                    @Override
                    public JsonNode post(String service, String path, Object body, Actor actor) {
                        return Json.read(
                                "{\"inputTokens\":100,\"contextWindow\":10000,\"maxOutputTokens\":2048,\"tokenizer\":\"fixture\",\"exact\":false}");
                    }

                    @Override
                    public JsonNode chatStream(
                            Object body, Actor actor, java.util.function.Consumer<String> chunk) {
                        observed.add(Json.tree(body));
                        return Json.tree(
                                Map.of(
                                        "text",
                                        "remembered42",
                                        "toolCalls",
                                        List.of(),
                                        "usage",
                                        Map.of("totalTokens", 10)));
                    }
                };
        var agent = new AgentDriver(TracePostgresTest.store, clients, new ContextAssembler());
        var runs =
                SchedulerTestResources.runs(
                        TracePostgresTest.store,
                        clients,
                        new FlowDriver(TracePostgresTest.store, agent),
                        agent);
        var sessions = new ChatSessions(TracePostgresTest.store, clients, runs);
        String id = sessions.create("chat", TracePostgresTest.actor).get("sessionId").toString();
        var req = Json.read("{\"requestId\":\"first\",\"inputs\":{\"message\":\"fact42\"}}");
        var first = sessions.turn("chat", id, req, TracePostgresTest.actor);
        String runId = first.get("runId").toString();
        for (int i = 0;
                i < 200
                        && !runs.status(runId, TracePostgresTest.actor)
                                .get("status")
                                .equals("SUCCEEDED");
                i++) Thread.sleep(10);
        assertEquals("SUCCEEDED", runs.status(runId, TracePostgresTest.actor).get("status"));
        assertEquals(runId, sessions.turn("chat", id, req, TracePostgresTest.actor).get("runId"));
        var second =
                sessions.turn(
                        "chat",
                        id,
                        Json.read(
                                "{\"requestId\":\"second\",\"inputs\":{\"message\":\"what"
                                        + " fact\"}}"),
                        TracePostgresTest.actor);
        String secondId = second.get("runId").toString();
        for (int i = 0;
                i < 200
                        && !runs.status(secondId, TracePostgresTest.actor)
                                .get("status")
                                .equals("SUCCEEDED");
                i++) Thread.sleep(10);
        assertEquals(2, observed.size());
        assertTrue(observed.get(1).path("messages").toString().contains("fact42"));
        assertTrue(observed.get(1).path("messages").toString().contains("remembered42"));
        assertEquals(
                "what fact",
                observed.get(1)
                        .path("messages")
                        .get(observed.get(1).path("manifest").path("currentUserIndex").asInt())
                        .path("content")
                        .asText());
        assertThrows(
                PlatformException.class,
                () ->
                        sessions.get(
                                "chat",
                                id,
                                new Actor("engine-test", "other", Set.of("ADMIN"), Map.of())));
    }

    @Test
    void disabledApplicationCannotStartANewTurnFromAnExistingSession() {
        var disabled = new java.util.concurrent.atomic.AtomicBoolean();
        var clients =
                new RuntimeClients() {
                    @Override
                    public JsonNode get(String service, String path, Actor actor) {
                        if (disabled.get())
                            throw new PlatformException(
                                    409, "APP_DISABLED", "application disabled");
                        return Json.read(
                                "{\"appCode\":\"chat\",\"type\":\"CHAT\",\"versionNo\":1}");
                    }
                };
        var sessions = new ChatSessions(TracePostgresTest.store, clients, null);
        String id = sessions.create("chat", TracePostgresTest.actor).get("sessionId").toString();
        disabled.set(true);
        assertThrows(
                PlatformException.class,
                () ->
                        sessions.turn(
                                "chat",
                                id,
                                Json.read(
                                        "{\"requestId\":\"new\",\"inputs\":{\"message\":\"must"
                                                + " refuse\"}}"),
                                TracePostgresTest.actor));
        assertEquals(
                0,
                TracePostgresTest.db.queryForObject(
                        "select count(*) from engine.chat_turn where session_id=?",
                        Integer.class,
                        id));
    }

    @Test
    void acceptedTurnRemainsReadableWhenApplicationIsDisabled() {
        var clients =
                new RuntimeClients() {
                    @Override
                    public JsonNode get(String service, String path, Actor actor) {
                        throw new AssertionError(
                                "Existing accepted work must not be rebound or blocked by current"
                                        + " publication");
                    }
                };
        String session = "s_accepted";
        TracePostgresTest.db.update(
                "insert into"
                    + " engine.chat_session(public_id,tenant_id,principal_id,app_code,snapshot,next_turn)"
                    + " values(?,'engine-test','alice','chat','{\"type\":\"CHAT\",\"versionNo\":1}',2)",
                session);
        long r = fixture.run();
        String id =
                TracePostgresTest.db.queryForObject(
                        "select public_id from engine.run where id=?", String.class, r);
        TracePostgresTest.db.update(
                "update engine.run set app_code='chat',status='SUCCEEDED' where id=?", r);
        TracePostgresTest.db.update(
                "insert into engine.chat_turn(session_id,turn_no,request_id,inputs,run_public_id)"
                        + " values(?,1,'existing','{\"message\":\"accepted\"}',?)",
                session,
                id);
        var runs = SchedulerTestResources.runs(TracePostgresTest.store, null, null, null);
        var sessions = new ChatSessions(TracePostgresTest.store, clients, runs);
        assertEquals(
                id,
                sessions.turn(
                                "chat",
                                session,
                                Json.read(
                                        "{\"requestId\":\"existing\",\"inputs\":{\"message\":\"accepted\"}}"),
                                TracePostgresTest.actor)
                        .get("runId"));
        assertEquals(
                1,
                TracePostgresTest.db.queryForObject(
                        "select count(*) from engine.chat_turn where session_id=?",
                        Integer.class,
                        session));
    }

    @Test
    void narrowedScopeCannotForwardHistoricalAnswerOrReserveAnotherTurn() {
        String session = "s_scope_narrowed";
        TracePostgresTest.db.update(
                "insert into"
                    + " engine.chat_session(public_id,tenant_id,principal_id,app_code,snapshot,next_turn)"
                    + " values(?,'engine-test','alice','chat','{\"type\":\"CHAT\",\"versionNo\":1}',2)",
                session);
        long source = fixture.run();
        String id =
                TracePostgresTest.db.queryForObject(
                        "select public_id from engine.run where id=?", String.class, source);
        TracePostgresTest.db.update(
                "update engine.run set"
                    + " app_code='chat',status='SUCCEEDED',output='{\"text\":\"contains protected B"
                    + " data\"}',guard_ctx=?::jsonb where id=?",
                Json.write(
                        Map.of(
                                "roles",
                                List.of("EDITOR"),
                                "attributes",
                                Map.of("region", List.of("A", "B")))),
                source);
        TracePostgresTest.db.update(
                "insert into engine.chat_turn(session_id,turn_no,request_id,inputs,run_public_id)"
                        + " values(?,1,'source','{\"message\":\"query A and B\"}',?)",
                session,
                id);
        var models = new java.util.concurrent.atomic.AtomicInteger();
        var clients =
                new RuntimeClients() {
                    @Override
                    public JsonNode get(String service, String path, Actor actor) {
                        return Json.read("{\"type\":\"CHAT\",\"versionNo\":1}");
                    }

                    @Override
                    public JsonNode chatStream(
                            Object body, Actor actor, java.util.function.Consumer<String> chunk) {
                        models.incrementAndGet();
                        throw new AssertionError("history must not leave the platform");
                    }
                };
        var agent = new AgentDriver(TracePostgresTest.store, clients, new ContextAssembler());
        var runs =
                SchedulerTestResources.runs(
                        TracePostgresTest.store,
                        clients,
                        new FlowDriver(TracePostgresTest.store, agent),
                        agent);
        var sessions = new ChatSessions(TracePostgresTest.store, clients, runs);
        var narrowed =
                new Actor("engine-test", "alice", Set.of("EDITOR"), Map.of("region", Set.of("A")));
        assertDoesNotThrow(
                () ->
                        ChatHistory.checkScope(
                                TracePostgresTest.store,
                                session,
                                "chat",
                                new Actor(
                                        "engine-test",
                                        "alice",
                                        Set.of("EDITOR"),
                                        Map.of("region", new LinkedHashSet<>(List.of("B", "A"))))));
        assertDoesNotThrow(
                () ->
                        ChatHistory.checkScope(
                                TracePostgresTest.store,
                                session,
                                "chat",
                                new Actor(
                                        "engine-test",
                                        "alice",
                                        Set.of("EDITOR", "READER"),
                                        Map.of("region", Set.of("A", "B", "C")))));
        assertThrows(
                PlatformException.class,
                () ->
                        ChatHistory.checkScope(
                                TracePostgresTest.store,
                                session,
                                "chat",
                                new Actor(
                                        "engine-test",
                                        "alice",
                                        Set.of("READER"),
                                        Map.of("region", Set.of("A", "B")))));
        var current =
                Map.<String, Object>of(
                        "tenant_id",
                        "engine-test",
                        "principal_id",
                        "alice",
                        "app_code",
                        "chat",
                        "snapshot",
                        Json.tree(Map.of("chatSession", Map.of("sessionId", session, "turnNo", 2))),
                        "guard_ctx",
                        Json.tree(
                                Map.of(
                                        "roles",
                                        List.of("EDITOR"),
                                        "attributes",
                                        Map.of("region", List.of("A")))));
        assertThrows(
                PlatformException.class, () -> ChatHistory.load(TracePostgresTest.store, current));
        var error =
                assertThrows(
                        PlatformException.class,
                        () ->
                                sessions.turn(
                                        "chat",
                                        session,
                                        Json.read(
                                                "{\"requestId\":\"narrowed\",\"inputs\":{\"message\":\"continue\"}}"),
                                        narrowed));
        assertEquals("CHAT_HISTORY_SCOPE_CHANGED", error.code());
        assertEquals(0, models.get());
        assertEquals(
                1,
                TracePostgresTest.db.queryForObject(
                        "select count(*) from engine.chat_turn where session_id=?",
                        Integer.class,
                        session));
        TracePostgresTest.db.update(
                "update engine.run set status='FAILED',error='{\"message\":\"protected B"
                        + " failure\"}' where id=?",
                source);
        assertThrows(
                PlatformException.class, () -> ChatHistory.load(TracePostgresTest.store, current));
        TracePostgresTest.db.update("update engine.run set guard_ctx='{}' where id=?", source);
        assertThrows(
                PlatformException.class,
                () ->
                        ChatHistory.checkScope(
                                TracePostgresTest.store, session, "chat", TracePostgresTest.actor));
    }
}
