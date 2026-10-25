package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.common.schema.SchemaWorkBudget;
import cn.agentplatform.engine.application.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.Mockito;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/** Real PG admission checks; outbound transports are explicit, counted protocol fixtures. */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class RunInputValidationPostgresTest {
    private final TracePostgresTest fixture = new TracePostgresTest();
    private static final Actor ACTOR = TracePostgresTest.actor;
    private RunService runs;
    private CountingClients clients;
    private final List<String> createdSessions = new ArrayList<>();

    @BeforeAll
    static void connect() throws Exception {
        TracePostgresTest.connect();
    }

    @BeforeEach
    void clean() {
        fixture.clean();
    }

    @AfterEach
    void finishCreatedRuns() throws Exception {
        // Red assertions may expose the missing gate and create a run. Stop only this
        // fixture's runs before the next shared-test-database cleanup, never a live service.
        if (runs == null) return;
        for (var row :
                TracePostgresTest.db.queryForList(
                        "select public_id from engine.run where tenant_id='engine-test' and status"
                                + " not in ('SUCCEEDED','FAILED','CANCELLED')")) {
            String id = row.get("public_id").toString();
            for (int i = 0; i < 100 && runs.status(id, ACTOR).get("status").equals("RUNNING"); i++)
                Thread.sleep(10);
            runs.cancel(id, ACTOR);
        }
        for (String id : createdSessions) {
            TracePostgresTest.db.update("delete from engine.chat_turn where session_id=?", id);
            TracePostgresTest.db.update(
                    "delete from engine.chat_session where public_id=? and tenant_id=?",
                    id,
                    ACTOR.tenantId());
        }
    }

    static Stream<Arguments> invalidChatInputs() {
        return Stream.of("DEBUG", "EVALUATION", "TASK")
                .flatMap(
                        trigger ->
                                Stream.of("{}", "{\"message\":7}", "{\"message\":\"x\"}")
                                        .map(input -> Arguments.of(trigger, input)));
    }

    @ParameterizedTest
    @MethodSource("invalidChatInputs")
    void chatRejectsMissingWrongTypeAndConstraintBeforeFreezeOrRun(String trigger, String input) {
        setup(chat());
        assertInputRejected(
                () -> runs.create("input_validation", request(input, "bad"), trigger, ACTOR));
        assertEquals(1, clients.reads.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"message\":7}", "{\"message\":\"x\"}"})
    void flowRejectsAtAdmissionRatherThanCreatingAFailedStartNode(String input) {
        setup(flow());
        assertInputRejected(
                () ->
                        runs.create(
                                "input_validation",
                                request(input, "bad-flow"),
                                "EVALUATION",
                                ACTOR));
    }

    @Test
    void trustedSessionSnapshotUsesTheSameInputGateBeforeModelFreeze() {
        setup(chat());
        assertInputRejected(
                () ->
                        runs.createSessionTurn(
                                "input_validation",
                                request("{}", "bad-session"),
                                clients.snapshot,
                                ACTOR));
        assertEquals(0, clients.reads.get(), "the trusted session snapshot needs no app fetch");
    }

    @Test
    void explicitInputSchemaIsEnforcedAlongsideTheFieldDeclarations() {
        var source = chat();
        ((ObjectNode) source.path("config"))
                .set(
                        "inputSchema",
                        Json.read(
                                "{\"type\":\"object\",\"required\":[\"count\"],\"properties\":{\"count\":{\"type\":\"integer\",\"minimum\":1}}}"));
        setup(source);
        assertInputRejected(
                () ->
                        runs.create(
                                "input_validation",
                                request("{\"message\":\"valid\"}", "bad-explicit"),
                                "DEBUG",
                                ACTOR));
    }

    @Test
    void invalidDeclarationIsRejectedBeforeAnyModelBindingRequest() {
        var source = chat();
        ((ObjectNode) source.path("config"))
                .set("inputSchema", Json.read("{\"$ref\":\"https://example.invalid/schema\"}"));
        setup(source);
        var error =
                assertThrows(
                        PlatformException.class,
                        () ->
                                runs.create(
                                        "input_validation",
                                        request("{\"message\":\"valid\"}", "bad-schema"),
                                        "DEBUG",
                                        ACTOR));
        assertEquals(400, error.status());
        assertEquals("INVALID_INPUT_DECLARATION", error.code());
        assertNoAdmissionOrOutboundWork();
    }

    @ParameterizedTest
    @ValueSource(strings = {"graph", "instance"})
    void smallSharedBudgetRefusalPrecedesRunAndModelBinding(String boundary) {
        var source = chat();
        var schema =
                Json.read(
                        "{\"type\":\"object\",\"properties\":{\"message\":{\"type\":\"string\"}}}");
        ((ObjectNode) source.path("config")).set("inputSchema", schema);
        setup(source);
        var plan = SchemaWorkBudget.inspect(schema);
        var limited = Mockito.spy(plan);
        Mockito.doAnswer(
                        call -> {
                            plan.admitInstance(call.getArgument(0), call.getArgument(1), 1);
                            return null;
                        })
                .when(limited)
                .admitInstance(Mockito.any(JsonNode.class), Mockito.anyLong());
        try (var budget = Mockito.mockStatic(SchemaWorkBudget.class, Mockito.CALLS_REAL_METHODS)) {
            var inspection =
                    budget.when(() -> SchemaWorkBudget.inspect(Mockito.any(JsonNode.class)));
            if (boundary.equals("graph"))
                inspection.thenAnswer(call -> SchemaWorkBudget.inspect(call.getArgument(0), 1));
            else inspection.thenReturn(limited);
            var error =
                    assertThrows(
                            PlatformException.class,
                            () ->
                                    runs.create(
                                            "input_validation",
                                            request(
                                                    "{\"message\":\"valid\"}",
                                                    "budget-" + boundary),
                                            "DEBUG",
                                            ACTOR));
            assertEquals(400, error.status());
            assertEquals(
                    boundary.equals("graph") ? "INVALID_INPUT_DECLARATION" : "INVALID_INPUT",
                    error.code());
            assertTrue(error.getMessage().contains("工作量"));
            assertNoAdmissionOrOutboundWork();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEBUG", "EVALUATION", "TASK"})
    void validTypedInputsArePreservedWithoutDefaultInjection(String trigger) throws Exception {
        setup(flow());
        JsonNode inputs =
                Json.read(
                        "{\"message\":\"有效输入\",\"count\":2,\"enabled\":false,\"items\":[1,2],\"details\":{\"x\":null}}");
        var request = Json.object();
        request.put("requestId", "typed-" + trigger);
        request.set("inputs", inputs.deepCopy());
        var result = runs.create("input_validation", request, trigger, ACTOR);
        var finalState = awaitTerminal(result);
        assertEquals("SUCCEEDED", finalState.get("status"), Json.write(finalState));
        assertEquals(
                inputs,
                TraceStore.json(
                        TracePostgresTest.db.queryForObject(
                                "select inputs from engine.run where public_id=?",
                                Object.class,
                                result.get("runId"))));
        assertFalse(Json.tree(finalState.get("inputs")).has("optionalDefault"));
        assertEquals(
                inputs, request.path("inputs"), "validation must not mutate the caller's values");
        assertEquals("有效输入", Json.tree(finalState.get("output")).path("answer").asText());
        assertEquals(0, clients.freeze.get());
        assertEquals(0, clients.writes.get());
        assertEquals(0, clients.models.get());
    }

    @Test
    void declaredRequiredNullAndOtherFalsyValuesExecuteAsPresentValues() throws Exception {
        var source = flow();
        ((ObjectNode) source.path("definition"))
                .set(
                        "inputs",
                        Json.read(
                                "[{\"name\":\"message\",\"type\":\"STRING\",\"required\":true},{\"name\":\"nullValue\",\"type\":\"NULL\",\"required\":true},{\"name\":\"zero\",\"type\":\"NUMBER\",\"required\":true},{\"name\":\"disabled\",\"type\":\"BOOLEAN\",\"required\":true},{\"name\":\"items\",\"type\":\"ARRAY\",\"required\":true},{\"name\":\"object\",\"type\":\"OBJECT\",\"required\":true}]"));
        setup(source);
        var input =
                Json.read(
                        "{\"message\":\"\",\"nullValue\":null,\"zero\":0,\"disabled\":false,\"items\":[],\"object\":{}}");
        var request = Json.object();
        request.put("requestId", "required-null");
        request.set("inputs", input);
        var result = runs.create("input_validation", request, "EVALUATION", ACTOR);
        var finalState = awaitTerminal(result);
        assertEquals("SUCCEEDED", finalState.get("status"), Json.write(finalState));
        assertEquals(input, Json.tree(finalState.get("inputs")));
        // Presence is still required; accepting declared NULL must not admit an omitted field
        // or a null value for a STRING declaration.
        for (String mutation : List.of("missing-null", "null-string")) {
            var invalidInput = (ObjectNode) input.deepCopy();
            if (mutation.equals("missing-null")) invalidInput.remove("nullValue");
            else invalidInput.putNull("message");
            var invalidRequest = Json.tree(Map.of("requestId", mutation, "inputs", invalidInput));
            var error =
                    assertThrows(
                            PlatformException.class,
                            () ->
                                    runs.create(
                                            "input_validation",
                                            invalidRequest,
                                            "EVALUATION",
                                            ACTOR));
            assertEquals(400, error.status());
            assertEquals("INPUT_SCHEMA_CHANGED", error.code());
        }
        assertEquals(1, count("run"));
        assertEquals(0, clients.writes.get());
        assertEquals(0, clients.models.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "wrong-type", "short", "explicit-schema", "invalid-schema"})
    void persistedStartRevalidatesAllDeclarationsWithParamErrorSemantics(String scenario) {
        var source = flow();
        JsonNode inputs =
                Json.read(
                        switch (scenario) {
                            case "missing" -> "{}";
                            case "wrong-type" -> "{\"message\":7}";
                            case "short" -> "{\"message\":\"x\"}";
                            default -> "{\"message\":\"valid\"}";
                        });
        if (scenario.equals("explicit-schema"))
            ((ObjectNode) source.path("config"))
                    .set("inputSchema", Json.read("{\"required\":[\"count\"]}"));
        if (scenario.equals("invalid-schema"))
            ((ObjectNode) source.path("config"))
                    .set("inputSchema", Json.read("{\"$ref\":\"https://example.invalid/schema\"}"));
        setup(source);
        // Model a historical persisted READY START, bypassing new admission exactly as recovery
        // does. It still needs a complete gate before any successor or business call can run.
        long rid = fixture.run(), node = fixture.node(rid, "READY");
        TracePostgresTest.db.update(
                "update engine.run set snapshot=?::jsonb,inputs=?::jsonb,mode='FULL' where id=?",
                Json.write(source),
                Json.write(inputs),
                rid);
        TracePostgresTest.db.update(
                "update engine.node_instance set"
                    + " node_def_id='start',node_type='START',definition='{\"id\":\"start\",\"type\":\"START\"}'::jsonb"
                    + " where id=?",
                node);
        runs.drive(rid, ACTOR);
        assertEquals(
                "FAILED",
                TracePostgresTest.db.queryForObject(
                        "select status from engine.node_instance where id=?", String.class, node));
        var error =
                TraceStore.json(
                        TracePostgresTest.db.queryForObject(
                                "select error from engine.node_instance where id=?",
                                Object.class,
                                node));
        assertEquals("PARAM_ERROR", error.path("semantic").asText());
        assertEquals(
                "FAILED",
                TracePostgresTest.db.queryForObject(
                        "select status from engine.run where id=?", String.class, rid));
        assertEquals(0, clients.writes.get());
        assertEquals(0, clients.models.get());
    }

    @Test
    void taskChatSessionKeepsItsTriggerAndRunsOnceWithValidInputs() throws Exception {
        var source = chat();
        JsonNode entry = Json.tree(Map.of("modelEntryId", 7));
        source.set(
                "dependencyVersions",
                Json.tree(
                        Map.of(
                                "modelRefs",
                                List.of(
                                        Map.of(
                                                "modelEntryId",
                                                7,
                                                "entry",
                                                entry,
                                                "modelBindingRef",
                                                Map.of(
                                                        "bindingId",
                                                        9,
                                                        "sha256",
                                                        Digest.sha256(Json.canonical(entry))))))));
        setup(source);
        var result =
                runs.createSessionTurn(
                        "input_validation",
                        request("{\"message\":\"valid\"}", "session-valid"),
                        source,
                        ACTOR);
        assertEquals("SUCCEEDED", awaitTerminal(result).get("status"));
        assertEquals(
                "CHAT",
                TracePostgresTest.db.queryForObject(
                        "select trigger_type from engine.run where public_id=?",
                        String.class,
                        result.get("runId")));
        assertEquals(1, clients.models.get());
        assertEquals(0, clients.freeze.get());
        assertEquals(0, clients.reads.get());
    }

    static Stream<Arguments> invalidSessionInputs() {
        return Stream.of("{}", "{\"message\":7}", "{\"message\":\"x\"}")
                .flatMap(
                        input ->
                                Stream.of("first-attempt", "corrected-attempt")
                                        .map(correctedId -> Arguments.of(input, correctedId)));
    }

    @ParameterizedTest
    @MethodSource("invalidSessionInputs")
    void actualSessionRejectsBeforeReservationAndAllowsCorrectedFirstTurn(
            String input, String correctedId) throws Exception {
        setup(boundChat());
        var sessions = new ChatSessions(TracePostgresTest.store, clients, runs);
        String id = createSession(sessions);
        var error =
                assertThrows(
                        PlatformException.class,
                        () ->
                                sessions.turn(
                                        "input_validation",
                                        id,
                                        request(input, "first-attempt"),
                                        ACTOR));
        assertEquals(400, error.status());
        assertEquals("INPUT_SCHEMA_CHANGED", error.code());
        assertUnreserved(sessions, id);
        assertNoAdmissionOrOutboundWork();

        var corrected = request("{\"message\":\"valid corrected input\"}", correctedId);
        var result = sessions.turn("input_validation", id, corrected, ACTOR);
        assertEquals("SUCCEEDED", awaitTerminal(result).get("status"));
        assertEquals(
                1, ((List<?>) sessions.get("input_validation", id, ACTOR).get("turns")).size());
        assertEquals(
                2,
                ((Number) sessions.get("input_validation", id, ACTOR).get("nextTurn")).intValue());
        assertEquals(1, count("run"));
        assertEquals(1, clients.models.get());
        assertEquals(0, clients.freeze.get());
    }

    @Test
    void actualSessionInvalidDeclarationNeverConsumesATurnOrBlocksAnotherRequestId() {
        var source = boundChat();
        ((ObjectNode) source.path("config"))
                .set("inputSchema", Json.read("{\"$ref\":\"https://example.invalid/schema\"}"));
        setup(source);
        var sessions = new ChatSessions(TracePostgresTest.store, clients, runs);
        String id = createSession(sessions);
        for (String requestId : List.of("invalid-schema", "another-attempt")) {
            var error =
                    assertThrows(
                            PlatformException.class,
                            () ->
                                    sessions.turn(
                                            "input_validation",
                                            id,
                                            request("{\"message\":\"valid\"}", requestId),
                                            ACTOR));
            assertEquals(400, error.status());
            assertEquals("INVALID_INPUT_DECLARATION", error.code());
            assertUnreserved(sessions, id);
            assertNoAdmissionOrOutboundWork();
        }
    }

    @Test
    void actualSessionUsesPinnedDeclarationAndAcceptedReplaySurvivesCurrentAppDisablement()
            throws Exception {
        setup(boundChat());
        var sessions = new ChatSessions(TracePostgresTest.store, clients, runs);
        String id = createSession(sessions);
        // Availability uses the current app; declarations still come from the fixed session.
        ((ObjectNode) clients.snapshot.path("config"))
                .set(
                        "inputs",
                        Json.read(
                                "[{\"name\":\"newField\",\"type\":\"STRING\",\"required\":true}]"));
        var request = request("{\"message\":\"valid pinned input\"}", "accepted-turn");
        var result = sessions.turn("input_validation", id, request, ACTOR);
        assertEquals("SUCCEEDED", awaitTerminal(result).get("status"));
        int reads = clients.reads.get();
        clients.disabled = true;
        var replay = sessions.turn("input_validation", id, request, ACTOR);
        assertEquals(result.get("runId"), replay.get("runId"));
        assertEquals(
                reads,
                clients.reads.get(),
                "accepted work must not fetch the disabled current app");
        assertEquals(1, clients.models.get());
        var conflict =
                assertThrows(
                        PlatformException.class,
                        () ->
                                sessions.turn(
                                        "input_validation",
                                        id,
                                        request(
                                                "{\"message\":\"different input\"}",
                                                "accepted-turn"),
                                        ACTOR));
        assertEquals("REQUEST_CONFLICT", conflict.code());
        var disabled =
                assertThrows(
                        PlatformException.class,
                        () ->
                                sessions.turn(
                                        "input_validation",
                                        id,
                                        request("{\"message\":\"new input\"}", "new-turn"),
                                        ACTOR));
        assertEquals("APP_DISABLED", disabled.code());
        assertEquals(
                1, ((List<?>) sessions.get("input_validation", id, ACTOR).get("turns")).size());
        assertEquals(
                2,
                ((Number) sessions.get("input_validation", id, ACTOR).get("nextTurn")).intValue());
        assertEquals(1, count("run"));
        assertEquals(1, clients.models.get());
    }

    private String createSession(ChatSessions sessions) {
        String id = sessions.create("input_validation", ACTOR).get("sessionId").toString();
        createdSessions.add(id);
        return id;
    }

    private void assertUnreserved(ChatSessions sessions, String id) {
        var state = sessions.get("input_validation", id, ACTOR);
        assertEquals(
                0,
                ((List<?>) state.get("turns")).size(),
                "invalid input must not leave a null-run reservation");
        assertEquals(
                1,
                ((Number) state.get("nextTurn")).intValue(),
                "invalid input must not advance the turn number");
    }

    @Test
    void acceptedRequestIdReturnsItsOriginalRunBeforeReadingChangedDeclarations() throws Exception {
        setup(flow());
        var request = request("{\"message\":\"valid\"}", "accepted-request");
        var first = runs.create("input_validation", request, "EVALUATION", ACTOR);
        assertEquals("SUCCEEDED", awaitTerminal(first).get("status"));
        int reads = clients.reads.get();
        ((ObjectNode) clients.snapshot.path("config"))
                .set("inputSchema", Json.read("{\"required\":[\"newField\"]}"));
        var second = runs.create("input_validation", request, "EVALUATION", ACTOR);
        assertEquals(first.get("runId"), second.get("runId"));
        assertEquals(
                reads,
                clients.reads.get(),
                "accepted request replay must not fetch a new snapshot");
        assertEquals(1, count("run"));
        assertEquals(0, clients.models.get());
    }

    @Test
    void reexecutionAndControlledReproductionPreserveValidFrozenFlowInputs() throws Exception {
        setup(flow());
        var source =
                runs.create(
                        "input_validation",
                        request("{\"message\":\"frozen value\"}", "source"),
                        "EVALUATION",
                        ACTOR);
        assertEquals("SUCCEEDED", awaitTerminal(source).get("status"));
        var request = Json.tree(Map.of("requestId", "recreate", "sourceDigest", "fixture-digest"));
        var reexecuted = runs.reexecuteFrozen(source.get("runId").toString(), request, ACTOR);
        assertEquals("SUCCEEDED", awaitTerminal(reexecuted).get("status"));
        int reads = clients.reads.get();
        var reproduced = runs.reproduceFrozen(source.get("runId").toString(), request, ACTOR);
        var result = awaitTerminal(reproduced);
        assertEquals("SUCCEEDED", result.get("status"));
        assertEquals("frozen value", Json.tree(result.get("inputs")).path("message").asText());
        assertEquals(
                reads,
                clients.reads.get(),
                "recorded flow replay must not use current external services");
        assertEquals(3, count("run"));
        assertEquals(0, clients.writes.get());
        assertEquals(0, clients.models.get());
    }

    private void setup(ObjectNode source) {
        clients = new CountingClients(source);
        var agent = new AgentDriver(TracePostgresTest.store, clients, new ContextAssembler());
        runs =
                SchedulerTestResources.runs(
                        TracePostgresTest.store,
                        clients,
                        new FlowDriver(TracePostgresTest.store, agent),
                        agent);
    }

    private void assertInputRejected(org.junit.jupiter.api.function.Executable operation) {
        var error = assertThrows(PlatformException.class, operation);
        assertEquals(400, error.status());
        assertEquals("INPUT_SCHEMA_CHANGED", error.code());
        assertNoAdmissionOrOutboundWork();
    }

    private void assertNoAdmissionOrOutboundWork() {
        assertEquals(0, clients.freeze.get(), "invalid inputs must reject before model freeze");
        assertEquals(
                0, clients.writes.get(), "invalid inputs must reject before any outbound POST");
        assertEquals(0, clients.models.get(), "invalid inputs must reject before paid dispatch");
        for (String table :
                List.of("run", "node_instance", "attempt", "model_invocation", "tool_call"))
            assertEquals(0, count(table), "admission must not create engine." + table);
    }

    private int count(String table) {
        assertTrue(
                Set.of("run", "node_instance", "attempt", "model_invocation", "tool_call")
                        .contains(table));
        String query =
                switch (table) {
                    case "run" -> "select count(*) from engine.run where tenant_id='engine-test'";
                    case "attempt" ->
                            "select count(*) from engine.attempt a join engine.node_instance n on"
                                    + " n.id=a.node_instance_id join engine.run r on r.id=n.run_id"
                                    + " where r.tenant_id='engine-test'";
                    default ->
                            "select count(*) from engine."
                                    + table
                                    + " x join engine.run r on r.id=x.run_id where"
                                    + " r.tenant_id='engine-test'";
                };
        return TracePostgresTest.db.queryForObject(query, Integer.class);
    }

    private Map<String, Object> awaitTerminal(Map<String, Object> initial) throws Exception {
        String id = initial.get("runId").toString();
        for (int i = 0; i < 500; i++) {
            var status = runs.status(id, ACTOR);
            if (Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status.get("status")))
                return status;
            Thread.sleep(10);
        }
        fail("fixture run did not terminate: " + id);
        return Map.of();
    }

    private static ObjectNode request(String inputs, String requestId) {
        var request = Json.object();
        request.put("requestId", requestId);
        request.put("mode", "FULL");
        request.set("inputs", Json.read(inputs));
        return request;
    }

    private static JsonNode declarations() {
        return Json.read(
                "[{\"name\":\"message\",\"type\":\"STRING\",\"required\":true,\"minLength\":2},{\"name\":\"count\",\"type\":\"NUMBER\",\"minimum\":1},{\"name\":\"enabled\",\"type\":\"BOOLEAN\"},{\"name\":\"items\",\"type\":\"ARRAY\",\"items\":{\"type\":\"integer\"}},{\"name\":\"details\",\"type\":\"OBJECT\"},{\"name\":\"optionalDefault\",\"type\":\"STRING\",\"default\":\"UI"
                    + " only\"}]");
    }

    private static ObjectNode base(String type) {
        var source = Json.object();
        source.put("appCode", "input_validation");
        source.put("type", type);
        source.put("appVersionId", 17);
        source.put("versionNo", 1);
        source.put("draftRev", 1);
        source.set(
                "config",
                Json.tree(Map.of("guard", Map.of("roles", List.of(), "attributes", Map.of()))));
        return source;
    }

    private static ObjectNode chat() {
        var source = base("CHAT");
        var config = (ObjectNode) source.path("config");
        config.put("modelEntryId", 7);
        config.set("inputs", declarations());
        return source;
    }

    private static ObjectNode boundChat() {
        var source = chat();
        JsonNode entry = Json.tree(Map.of("modelEntryId", 7));
        source.set(
                "dependencyVersions",
                Json.tree(
                        Map.of(
                                "modelRefs",
                                List.of(
                                        Map.of(
                                                "modelEntryId",
                                                7,
                                                "entry",
                                                entry,
                                                "modelBindingRef",
                                                Map.of(
                                                        "bindingId",
                                                        9,
                                                        "sha256",
                                                        Digest.sha256(Json.canonical(entry))))))));
        return source;
    }

    private static ObjectNode flow() {
        var source = base("FLOW");
        source.set(
                "definition",
                Json.tree(
                        Map.of(
                                "definitionVersion", "1.0",
                                "trigger", "TASK",
                                "entry", "start",
                                "inputs", declarations(),
                                "outputs",
                                        List.of(
                                                Map.of(
                                                        "name",
                                                        "answer",
                                                        "from",
                                                        "${input.message}")),
                                "nodes",
                                        List.of(
                                                Map.of("id", "start", "type", "START"),
                                                Map.of("id", "end", "type", "END")),
                                "edges", List.of(Map.of("from", "start", "to", "end")),
                                "joins", List.of(),
                                "executionRules",
                                        Map.of(
                                                "parallel",
                                                false,
                                                "maxParallel",
                                                1,
                                                "cyclesForbidden",
                                                true))));
        return source;
    }

    private static final class CountingClients extends RuntimeClients {
        final ObjectNode snapshot;
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger freeze = new AtomicInteger();
        final AtomicInteger writes = new AtomicInteger();
        final AtomicInteger models = new AtomicInteger();
        boolean disabled;

        CountingClients(ObjectNode snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public JsonNode get(String service, String path, Actor actor) {
            assertEquals("app", service);
            reads.incrementAndGet();
            if (disabled)
                throw new PlatformException(409, "APP_DISABLED", "protocol fixture: app disabled");
            return snapshot.deepCopy();
        }

        @Override
        public JsonNode post(String service, String path, Object body, Actor actor) {
            writes.incrementAndGet();
            if (path.equals("/internal/model-bindings/freeze")) {
                freeze.incrementAndGet();
                throw new PlatformException(
                        503, "FREEZE_REACHED", "fixture: admission validation must precede freeze");
            }
            assertEquals("/internal/model/measure", path);
            return Json.read(
                    "{\"inputTokens\":10,\"contextWindow\":10000,\"maxOutputTokens\":4096,\"tokenizer\":\"protocol-fixture\",\"exact\":true}");
        }

        @Override
        public JsonNode chatStream(
                Object body, Actor actor, java.util.function.Consumer<String> chunks) {
            models.incrementAndGet();
            return Json.read(
                    "{\"text\":\"protocol"
                            + " fixture\",\"toolCalls\":[],\"usage\":{\"totalTokens\":3}}");
        }
    }
}
