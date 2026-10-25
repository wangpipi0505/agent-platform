package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.common.schema.SchemaWorkBudget;
import cn.agentplatform.engine.application.*;
import cn.agentplatform.engine.domain.OutputPolicy;
import cn.agentplatform.engine.domain.RuntimeFault;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Protocol fixtures exercise the runtime boundary; they do not assert provider capability. */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class OutputPolicyPostgresTest {
    final TracePostgresTest fixture = new TracePostgresTest();
    long rid, nid;
    Map<String, Object> run, node;
    AtomicInteger calls;

    @BeforeAll
    static void connect() throws Exception {
        TracePostgresTest.connect();
    }

    @BeforeEach
    void prepare() {
        fixture.clean();
        rid = fixture.run();
        nid = fixture.node(rid, "READY");
        node = TracePostgresTest.store.claim(nid, "output-policy");
        run = TracePostgresTest.db.queryForMap("select * from engine.run where id=?", rid);
        calls = new AtomicInteger();
    }

    Map<String, Object> request(String mode, int retries) {
        return new ContextAssembler()
                .assemble(
                        Json.tree(
                                Map.of(
                                        "modelEntryId",
                                        1,
                                        "output",
                                        Map.of(
                                                "format",
                                                Map.of(
                                                        "type",
                                                        "json",
                                                        "schema",
                                                        Json.read(
                                                                "{\"type\":\"object\",\"required\":[\"answer\"],\"properties\":{\"answer\":{\"type\":\"integer\",\"minimum\":1}},\"additionalProperties\":false}")),
                                                "onInvalid",
                                                mode,
                                                "maxRetries",
                                                retries))),
                        Json.read("{\"message\":\"give answer\"}"),
                        List.of(),
                        List.of());
    }

    RuntimeClients client(String... texts) {
        return new RuntimeClients() {
            @Override
            public JsonNode post(String service, String path, Object body, Actor actor) {
                return Json.read(
                        "{\"inputTokens\":10,\"contextWindow\":10000,\"maxOutputTokens\":4096,\"tokenizer\":\"protocol-fixture\",\"exact\":true}");
            }

            @Override
            public JsonNode chatStream(
                    Object body, Actor actor, java.util.function.Consumer<String> chunks) {
                int index = calls.getAndIncrement();
                assertTrue(index < texts.length, "unexpected model call");
                assertNotNull(
                        TracePostgresTest.db.queryForObject(
                                "select final_request from engine.model_invocation where"
                                        + " node_instance_id=? order by id desc limit 1",
                                String.class,
                                nid));
                if (index > 0)
                    assertTrue(
                            TracePostgresTest.db.queryForObject(
                                            "select count(*) from engine.model_invocation where"
                                                + " node_instance_id=? and response is not null",
                                            Integer.class,
                                            nid)
                                    >= index);
                return Json.tree(
                        Map.of(
                                "text",
                                texts[index],
                                "toolCalls",
                                List.of(),
                                "usage",
                                Map.of("totalTokens", 7)));
            }
        };
    }

    JsonNode exchange(Map<String, Object> request, RuntimeClients clients) {
        return new ModelExchange(TracePostgresTest.store, clients)
                .exchange(run, node, 0, request, TracePostgresTest.actor, s -> {});
    }

    @Test
    void sharedSchemaAdmissionRejectsBeforeAnyModelInvocation() {
        try (var budget = Mockito.mockStatic(SchemaWorkBudget.class, Mockito.CALLS_REAL_METHODS)) {
            budget.when(() -> SchemaWorkBudget.inspect(Mockito.any(JsonNode.class)))
                    .thenAnswer(call -> SchemaWorkBudget.inspect(call.getArgument(0), 1));
            var error =
                    assertThrows(RuntimeFault.class, () -> exchange(request("RETRY", 1), client()));
            assertEquals("OUTPUT_POLICY_INVALID", error.semantic);
            assertTrue(error.getMessage().contains("工作量"));
            assertEquals(0, calls.get());
            assertEquals(
                    0,
                    TracePostgresTest.db.queryForObject(
                            "select count(*) from engine.model_invocation where run_id=?",
                            Integer.class,
                            rid));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"TERMINATE", "RETRY", "PASS_WITH_FLAG"})
    void tinyValidationBudgetRetainsExistingOutputStrategy(String mode) {
        var req = request(mode, 1);
        var schema = Json.tree(req.get("params")).path("output").path("format").path("schema");
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
            budget.when(() -> SchemaWorkBudget.inspect(Mockito.any(JsonNode.class)))
                    .thenReturn(limited);
            var clients = client("{\"answer\":2}", "{\"answer\":3}");
            if (mode.equals("PASS_WITH_FLAG")) {
                var answer = exchange(req, clients);
                assertFalse(answer.path("outputValidation").path("valid").asBoolean(true));
                assertTrue(answer.path("outputValidation").path("reason").asText().contains("工作量"));
                assertFalse(answer.has("structured"));
            } else {
                var error = assertThrows(RuntimeFault.class, () -> exchange(req, clients));
                assertEquals("OUTPUT_INVALID", error.semantic);
                assertTrue(error.getMessage().contains("工作量"));
            }
            int expected = mode.equals("RETRY") ? 2 : 1;
            assertEquals(expected, calls.get());
            assertEquals(
                    expected,
                    TracePostgresTest.db.queryForObject(
                            "select count(*) from engine.model_invocation where run_id=? and"
                                    + " output_validation->>'valid'='false' and usage_accounted",
                            Integer.class,
                            rid));
        }
    }

    @Test
    void missingLocalReferenceRejectsBeforeAnyModelHttpOrBudgetDebit() {
        var before =
                TracePostgresTest.db.queryForObject(
                        "select budget::text from engine.run where id=?", String.class, rid);
        var forbidden =
                new RuntimeClients() {
                    @Override
                    public JsonNode post(String service, String path, Object body, Actor actor) {
                        calls.incrementAndGet();
                        throw new AssertionError("configuration must reject before model HTTP");
                    }

                    @Override
                    public JsonNode chatStream(
                            Object body, Actor actor, java.util.function.Consumer<String> chunks) {
                        calls.incrementAndGet();
                        throw new AssertionError("configuration must reject before paid dispatch");
                    }
                };
        for (String mode : List.of("TERMINATE", "PASS_WITH_FLAG", "RETRY")) {
            var req = request(mode, 2);
            var params = Json.tree(req.get("params"));
            ((com.fasterxml.jackson.databind.node.ObjectNode) params.path("output").path("format"))
                    .set("schema", Json.read("{\"$ref\":\"#/missing\"}"));
            req.put("params", params);
            assertEquals(
                    "OUTPUT_POLICY_INVALID",
                    assertThrows(RuntimeFault.class, () -> exchange(req, forbidden)).semantic);
        }
        assertEquals(0, calls.get());
        assertEquals(
                0,
                TracePostgresTest.db.queryForObject(
                        "select count(*) from engine.model_invocation where run_id=?",
                        Integer.class,
                        rid));
        assertEquals(
                before,
                TracePostgresTest.db.queryForObject(
                        "select budget::text from engine.run where id=?", String.class, rid));
    }

    @Test
    void unexpectedValidatorFailurePreservesDiagnosticAndChargesPaidResponseOnce() {
        var req = request("RETRY", 2);
        try (var policies =
                Mockito.mockConstruction(
                        OutputPolicy.class,
                        (policy, context) -> {
                            Mockito.when(policy.json()).thenReturn(true);
                            Mockito.when(policy.onInvalid()).thenReturn("RETRY");
                            Mockito.when(policy.validate(Mockito.any()))
                                    .thenThrow(
                                            new com.networknt.schema.JsonSchemaException(
                                                    "injected validator initialization failure"));
                        })) {
            var clients = client("{}");
            for (int recovery = 0; recovery < 2; recovery++)
                assertEquals(
                        "OUTPUT_POLICY_INVALID",
                        assertThrows(RuntimeFault.class, () -> exchange(req, clients)).semantic);
        }
        assertEquals(1, calls.get());
        assertEquals(
                1,
                TracePostgresTest.db.queryForObject(
                        "select count(*) from engine.model_invocation where run_id=? and response"
                                + " is not null and usage_accounted and"
                                + " output_validation->>'semantic'='OUTPUT_POLICY_INVALID'",
                        Integer.class,
                        rid));
        var budget =
                TraceStore.json(
                        TracePostgresTest.db.queryForObject(
                                "select budget from engine.run where id=?", String.class, rid));
        assertEquals(1, budget.path("consumed").path("modelCalls").asInt());
        assertEquals(7, budget.path("consumed").path("tokens").asInt());
    }

    @Test
    void terminateRejectsSchemaViolationAndPersistsReturn() {
        var fault =
                assertThrows(
                        RuntimeFault.class,
                        () -> exchange(request("TERMINATE", 2), client("{\"answer\":0}")));
        assertEquals("OUTPUT_INVALID", fault.semantic);
        assertEquals(1, calls.get());
        assertEquals(
                1,
                TracePostgresTest.db.queryForObject(
                        "select count(*) from engine.model_invocation where run_id=? and response"
                                + " is not null",
                        Integer.class,
                        rid));
    }

    @Test
    void passWithFlagPreservesInvalidRawTextAndExplicitDiagnostic() {
        var answer = exchange(request("PASS_WITH_FLAG", 2), client("not json"));
        assertEquals("not json", answer.path("text").asText());
        assertFalse(answer.path("outputValidation").path("valid").asBoolean(true));
        assertEquals("OUTPUT_INVALID", answer.path("outputValidation").path("semantic").asText());
        assertFalse(answer.path("outputValidation").path("reason").asText().isBlank());
    }

    @Test
    void retryUsesSeparateDurableCallsAndRestartDoesNotRecharge() {
        var req = request("RETRY", 2);
        var clients = client("bad", "{\"answer\":0}", "{\"answer\":2}");
        var answer = exchange(req, clients);
        assertEquals(2, answer.path("structured").path("answer").asInt());
        assertEquals(3, calls.get());
        assertEquals(answer, exchange(req, clients));
        assertEquals(3, calls.get());
        var budget =
                TraceStore.json(
                        TracePostgresTest.db.queryForObject(
                                "select budget from engine.run where id=?", String.class, rid));
        assertEquals(3, budget.path("consumed").path("modelCalls").asInt());
        assertEquals(21, budget.path("consumed").path("tokens").asInt());
    }

    @Test
    void retryStopsAtConfiguredCap() {
        assertThrows(RuntimeFault.class, () -> exchange(request("RETRY", 1), client("bad", "bad")));
        assertEquals(2, calls.get());
    }

    @Test
    void unknownPolicyAndNegativeRetriesRejectBeforeCalling() {
        assertThrows(RuntimeException.class, () -> exchange(request("UNKNOWN", 2), client("bad")));
        assertEquals(0, calls.get());
        assertThrows(RuntimeException.class, () -> exchange(request("RETRY", -1), client("bad")));
        assertEquals(0, calls.get());
    }

    @Test
    void trailingJsonAndDuplicateKeysAreInvalid() {
        assertThrows(
                RuntimeFault.class,
                () -> exchange(request("TERMINATE", 0), client("{\"answer\":2} {}")));
    }

    JsonNode frozenConfig(String mode) {
        JsonNode config =
                Json.tree(
                        Map.of(
                                "modelEntryId",
                                1,
                                "output",
                                Json.tree(request(mode, 1).get("params")).path("output")));
        JsonNode entry = Json.read("{\"id\":1}");
        JsonNode snapshot =
                Json.tree(
                        Map.of(
                                "config",
                                config,
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
        TracePostgresTest.db.update(
                "update engine.run set snapshot=?::jsonb where id=?", Json.write(snapshot), rid);
        run = TracePostgresTest.db.queryForMap("select * from engine.run where id=?", rid);
        return config;
    }

    @Test
    void agentFinalAnswerExposesAcceptedStructuredValue() {
        var config = frozenConfig("TERMINATE");
        var result =
                new AgentDriver(
                                TracePostgresTest.store,
                                client("{\"answer\":2}"),
                                new ContextAssembler())
                        .drive(
                                run,
                                node,
                                config,
                                Json.read("{\"message\":\"question\"}"),
                                TracePostgresTest.actor,
                                false);
        assertEquals(2, result.path("structured").path("answer").asInt());
        assertTrue(result.path("outputValidation").path("valid").asBoolean());
    }

    @Test
    void flowLlmConsumesFrozenNodeOverrideAndPassFlag() {
        var config = (com.fasterxml.jackson.databind.node.ObjectNode) frozenConfig("TERMINATE");
        config.set(
                "nodeOverrides",
                Json.read("{\"n1\":{\"output\":{\"onInvalid\":\"PASS_WITH_FLAG\"}}}"));
        var snapshot =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        TraceStore.json(run.get("snapshot"));
        snapshot.set("config", config);
        TracePostgresTest.db.update(
                "update engine.run set snapshot=?::jsonb where id=?", Json.write(snapshot), rid);
        run = TracePostgresTest.db.queryForMap("select * from engine.run where id=?", rid);
        node.put("definition", Json.write(Map.of("id", "n1", "type", "LLM")));
        var agent = new AgentDriver(TracePostgresTest.store, client("bad"), new ContextAssembler());
        var result =
                new FlowDriver(TracePostgresTest.store, agent)
                        .execute(run, node, TracePostgresTest.actor);
        assertEquals("bad", result.path("text").asText());
        assertFalse(result.path("outputValidation").path("valid").asBoolean(true));
    }

    @Test
    void modelCallBudgetStopsOutputRetryBeforeOutbound() {
        TracePostgresTest.db.update(
                "update engine.run set budget='{\"limits\":{\"modelCalls\":1}}' where id=?", rid);
        var failure =
                assertThrows(
                        PlatformException.class,
                        () -> exchange(request("RETRY", 2), client("bad")));
        assertEquals("BUDGET_MODELCALLS", failure.code());
        assertEquals(1, calls.get());
        assertEquals(
                1,
                TracePostgresTest.db.queryForObject(
                        "select count(*) from engine.model_invocation where run_id=? and"
                                + " output_validation->>'valid'='false'",
                        Integer.class,
                        rid));
    }

    @Test
    void recordedResponseRemainsAuthoritativeWhenTokenBudgetFails() {
        TracePostgresTest.db.update(
                "update engine.run set budget='{\"limits\":{\"totalTokens\":6}}' where id=?", rid);
        var req = request("RETRY", 2);
        var clients = client("bad");
        assertEquals(
                "BUDGET_TOKENS",
                assertThrows(PlatformException.class, () -> exchange(req, clients)).code());
        assertEquals(
                "BUDGET_TOKENS",
                assertThrows(PlatformException.class, () -> exchange(req, clients)).code());
        assertEquals(1, calls.get());
        assertEquals(
                1,
                TracePostgresTest.db.queryForObject(
                        "select count(*) from engine.model_invocation where run_id=? and"
                                + " output_validation->>'valid'='false'",
                        Integer.class,
                        rid));
        assertEquals(
                1,
                TracePostgresTest.db.queryForObject(
                        "select count(*) from engine.model_invocation where run_id=? and response"
                                + " is not null",
                        Integer.class,
                        rid));
    }

    @Test
    void totalDurationBudgetStopsBeforeFirstOutbound() {
        TracePostgresTest.db.update(
                "update engine.run set"
                    + " budget='{\"limits\":{\"totalDurationMs\":1}}',started_at=now()-interval '1"
                    + " minute' where id=?",
                rid);
        assertEquals(
                "BUDGET_DURATION",
                assertThrows(
                                PlatformException.class,
                                () -> exchange(request("RETRY", 2), client("bad")))
                        .code());
        assertEquals(0, calls.get());
    }

    @Test
    void recoveryAfterInvalidValidationReusesFirstResponseAndResumesNextAttempt() {
        var req = request("RETRY", 1);
        var measures = new AtomicInteger();
        var underlying = client("bad");
        var interrupted =
                new RuntimeClients() {
                    @Override
                    public JsonNode post(String service, String path, Object body, Actor actor) {
                        if (measures.incrementAndGet() == 3)
                            throw new PlatformException(
                                    503,
                                    "TEST_INTERRUPTION",
                                    "protocol test interruption before retry dispatch");
                        return underlying.post(service, path, body, actor);
                    }

                    @Override
                    public JsonNode chatStream(
                            Object body, Actor actor, java.util.function.Consumer<String> chunks) {
                        return underlying.chatStream(body, actor, chunks);
                    }
                };
        assertThrows(PlatformException.class, () -> exchange(req, interrupted));
        assertEquals(1, calls.get());
        var resumed =
                new RuntimeClients() {
                    @Override
                    public JsonNode post(String service, String path, Object body, Actor actor) {
                        return underlying.post(service, path, body, actor);
                    }

                    @Override
                    public JsonNode chatStream(
                            Object body, Actor actor, java.util.function.Consumer<String> chunks) {
                        calls.incrementAndGet();
                        return Json.read(
                                "{\"text\":\"{\\\"answer\\\":3}\",\"usage\":{\"totalTokens\":7}}");
                    }
                };
        assertEquals(3, exchange(req, resumed).path("structured").path("answer").asInt());
        assertEquals(2, calls.get());
        assertEquals(
                2,
                TracePostgresTest.db.queryForObject(
                        "select count(distinct final_request->>'invocationRef') from"
                                + " engine.model_invocation where run_id=? and kind='MAIN'",
                        Integer.class,
                        rid));
        assertEquals(
                2,
                TracePostgresTest.db.queryForObject(
                        "select count(*) from engine.model_invocation where run_id=? and"
                                + " output_validation is not null and usage_accounted",
                        Integer.class,
                        rid));
    }

    @Test
    void toolCallIntermediateRoundSkipsFinalSchema() {
        var underlying = client();
        var tools =
                new RuntimeClients() {
                    @Override
                    public JsonNode post(String service, String path, Object body, Actor actor) {
                        return underlying.post(service, path, body, actor);
                    }

                    @Override
                    public JsonNode chatStream(
                            Object body, Actor actor, java.util.function.Consumer<String> chunks) {
                        calls.incrementAndGet();
                        return Json.read(
                                "{\"text\":\"working\",\"toolCalls\":[{\"id\":\"call1\",\"name\":\"lookup\",\"arguments\":{}}],\"usage\":{\"totalTokens\":1}}");
                    }
                };
        var answer = exchange(request("TERMINATE", 0), tools);
        assertEquals(1, answer.path("toolCalls").size());
        assertFalse(answer.has("outputValidation"));
        assertEquals(1, calls.get());
    }

    @Test
    void flowLegacyContractUsesOutputRetryPolicyRatherThanLateRejection() {
        frozenConfig("RETRY");
        node.put(
                "definition",
                Json.write(
                        Map.of(
                                "id",
                                "n1",
                                "type",
                                "LLM",
                                "outputContract",
                                Map.of("type", "json", "schema", Map.of("type", "integer")))));
        var agent =
                new AgentDriver(
                        TracePostgresTest.store, client("bad", "42"), new ContextAssembler());
        assertEquals(
                42,
                new FlowDriver(TracePostgresTest.store, agent)
                        .execute(run, node, TracePostgresTest.actor)
                        .asInt());
        assertEquals(2, calls.get());
    }

    @Test
    void uncertainDispatchIsNotImplicitlySentAgainByRecovery() {
        var underlying = client();
        var lost =
                new RuntimeClients() {
                    @Override
                    public JsonNode post(String service, String path, Object body, Actor actor) {
                        return underlying.post(service, path, body, actor);
                    }

                    @Override
                    public JsonNode chatStream(
                            Object body, Actor actor, java.util.function.Consumer<String> chunks) {
                        calls.incrementAndGet();
                        throw new PlatformException(
                                503, "TEST_TRANSPORT_LOST", "test protocol response lost");
                    }
                };
        var req = request("RETRY", 2);
        assertThrows(PlatformException.class, () -> exchange(req, lost));
        var failure = assertThrows(RuntimeFault.class, () -> exchange(req, lost));
        assertEquals("MODEL_RESULT_UNKNOWN", failure.semantic);
        assertEquals(1, calls.get());
    }

    @Test
    void controlledReplayTraversesEveryOutputAttemptWithoutModelHttp() {
        var req = request("RETRY", 2);
        req.put("modelBindingRef", Map.of("bindingId", 1, "sha256", "a".repeat(64)));
        var expected = exchange(req, client("bad", "{\"answer\":0}", "{\"answer\":3}"));
        long source = rid;
        String sourceId = run.get("public_id").toString();
        rid = fixture.run();
        nid = fixture.node(rid, "READY");
        node = TracePostgresTest.store.claim(nid, "output-replay");
        TracePostgresTest.db.update(
                "update engine.run set snapshot=?::jsonb where id=?",
                Json.write(Map.of("controlledReplay", Map.of("sourceRunId", sourceId))),
                rid);
        run = TracePostgresTest.db.queryForMap("select * from engine.run where id=?", rid);
        var forbidden =
                new RuntimeClients() {
                    @Override
                    public JsonNode post(String service, String path, Object body, Actor actor) {
                        throw new AssertionError("model HTTP forbidden");
                    }

                    @Override
                    public JsonNode chatStream(
                            Object body, Actor actor, java.util.function.Consumer<String> chunks) {
                        throw new AssertionError("model HTTP forbidden");
                    }
                };
        assertEquals(expected, exchange(req, forbidden));
        assertEquals(
                3,
                TracePostgresTest.db.queryForObject(
                        "select count(*) from engine.model_invocation m join"
                                + " engine.model_invocation s on s.id=m.source_invocation_id where"
                                + " m.run_id=? and s.run_id=? and"
                                + " m.output_attempt_no=s.output_attempt_no and m.is_mock and"
                                + " m.sdk_attempts=0 and m.output_validation is not null",
                        Integer.class,
                        rid,
                        source));
    }

    @Test
    void summaryDoesNotInheritFinalJsonSchemaOrRetryPolicy() {
        var config =
                Json.read(
                        "{\"modelEntryId\":1,\"output\":{\"maxTokens\":20,\"format\":{\"type\":\"json\",\"schema\":{\"type\":\"integer\"}},\"onInvalid\":\"TERMINATE\"},\"context\":{\"historyTurns\":1,\"summarization\":{\"enabled\":true}}}");
        var req =
                new ContextAssembler()
                        .assemble(
                                config,
                                Json.read("{\"message\":\"current\"}"),
                                List.of(),
                                List.of(),
                                List.of(
                                        Map.of("role", "user", "content", "old"),
                                        Map.of("role", "assistant", "content", "x".repeat(3000))));
        var client =
                new RuntimeClients() {
                    @Override
                    public JsonNode post(String service, String path, Object body, Actor actor) {
                        var view = Json.tree(body);
                        boolean summary =
                                view.path("messages")
                                        .get(0)
                                        .path("content")
                                        .asText()
                                        .startsWith("Summarize");
                        return Json.tree(
                                Map.of(
                                        "inputTokens",
                                        view.path("messages").toString().length(),
                                        "contextWindow",
                                        summary ? 10000 : 600,
                                        "maxOutputTokens",
                                        100,
                                        "tokenizer",
                                        "fixture",
                                        "exact",
                                        true));
                    }

                    @Override
                    public JsonNode chatStream(
                            Object body, Actor actor, java.util.function.Consumer<String> chunks) {
                        calls.incrementAndGet();
                        boolean summary =
                                Json.tree(body).path("invocationRef").asText().endsWith("SUMMARY");
                        if (summary) assertFalse(Json.tree(body).path("params").has("output"));
                        return Json.tree(
                                Map.of(
                                        "text",
                                        summary ? "prior fact" : "42",
                                        "usage",
                                        Map.of("totalTokens", 3)));
                    }
                };
        assertEquals(42, exchange(req, client).path("structured").asInt());
        assertEquals(2, calls.get());
        assertEquals(
                1,
                TracePostgresTest.db.queryForObject(
                        "select count(*) from engine.model_invocation where run_id=? and"
                                + " kind='SUMMARY' and output_validation is null",
                        Integer.class,
                        rid));
    }

    @Test
    void flowMappingCannotDiscardPassWithFlagDiagnostic() {
        frozenConfig("PASS_WITH_FLAG");
        node.put(
                "definition",
                Json.write(Map.of("id", "n1", "type", "LLM", "output", Map.of("data", "$.text"))));
        var agent =
                new AgentDriver(
                        TracePostgresTest.store,
                        client("invalid business data"),
                        new ContextAssembler());
        var result =
                new FlowDriver(TracePostgresTest.store, agent)
                        .execute(run, node, TracePostgresTest.actor);
        assertEquals("invalid business data", result.path("data").asText());
        assertFalse(result.path("outputValidation").path("valid").asBoolean(true));
    }
}
