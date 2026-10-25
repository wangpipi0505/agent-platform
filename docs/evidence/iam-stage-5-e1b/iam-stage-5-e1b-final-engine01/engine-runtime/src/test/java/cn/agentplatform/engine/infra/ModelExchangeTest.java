package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.*;
import java.util.concurrent.atomic.*;

@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class ModelExchangeTest {
    final TracePostgresTest fixture = new TracePostgresTest();

    @BeforeAll
    static void connect() throws Exception {
        TracePostgresTest.connect();
    }

    @BeforeEach
    void clean() {
        TracePostgresTest.db.update("delete from engine.model_invocation");
        fixture.clean();
    }

    @Test
    void finalRequestPrecedesSdkAndResponseIsReusedAcrossReplicas() {
        long r = fixture.run(), n = fixture.node(r, "READY");
        var node = TracePostgresTest.store.claim(n, "replica");
        var run = TracePostgresTest.db.queryForMap("select * from engine.run where id=?", r);
        var calls = new AtomicInteger();
        var client =
                new RuntimeClients() {
                    @Override
                    public com.fasterxml.jackson.databind.JsonNode post(
                            String service, String path, Object body, Actor actor) {
                        return Json.read(
                                "{\"inputTokens\":100,\"contextWindow\":10000,\"maxOutputTokens\":4096,\"tokenizer\":\"fixture\",\"exact\":true}");
                    }

                    @Override
                    public com.fasterxml.jackson.databind.JsonNode chatStream(
                            Object body, Actor actor, java.util.function.Consumer<String> chunk) {
                        assertNotNull(
                                TracePostgresTest.db.queryForObject(
                                        "select final_request from engine.model_invocation where"
                                            + " node_instance_id=? and kind='MAIN'",
                                        String.class,
                                        n));
                        calls.incrementAndGet();
                        return Json.read("{\"text\":\"answer\",\"usage\":{\"totalTokens\":101}}");
                    }
                };
        var request =
                new ContextAssembler()
                        .assemble(
                                Json.read("{\"modelEntryId\":1}"),
                                Json.read("{\"message\":\"original\"}"),
                                List.of(),
                                List.of());
        var first =
                new ModelExchange(TracePostgresTest.store, client)
                        .exchange(run, node, 0, request, TracePostgresTest.actor, s -> {});
        var second =
                new ModelExchange(TracePostgresTest.store, client)
                        .exchange(run, node, 0, request, TracePostgresTest.actor, s -> {});
        assertEquals(first, second);
        assertEquals(1, calls.get());
        assertEquals(
                1,
                TracePostgresTest.db.queryForObject(
                        "select sdk_attempts from engine.model_invocation where node_instance_id=?",
                        Integer.class,
                        n));
        assertTrue(
                TracePostgresTest.db
                        .queryForObject(
                                "select source_request::text from engine.model_invocation where"
                                    + " node_instance_id=?",
                                String.class,
                                n)
                        .contains("original"));
    }

    @Test
    void summaryIsAnIndependentDurableBudgetedCallAndRestartReusesBoth() {
        long r = fixture.run(), n = fixture.node(r, "READY");
        var node = TracePostgresTest.store.claim(n, "replica");
        var run = TracePostgresTest.db.queryForMap("select * from engine.run where id=?", r);
        var sdk = new AtomicInteger();
        var clients =
                new RuntimeClients() {
                    @Override
                    public com.fasterxml.jackson.databind.JsonNode post(
                            String service, String path, Object body, Actor actor) {
                        var req = Json.tree(body);
                        boolean summary =
                                req.path("messages")
                                        .get(0)
                                        .path("content")
                                        .asText()
                                        .startsWith("Summarize");
                        return Json.tree(
                                Map.of(
                                        "inputTokens",
                                        req.path("messages").toString().length(),
                                        "contextWindow",
                                        summary ? 10000 : 600,
                                        "maxOutputTokens",
                                        100,
                                        "tokenizer",
                                        "fixture",
                                        "exact",
                                        false));
                    }

                    @Override
                    public com.fasterxml.jackson.databind.JsonNode chatStream(
                            Object body, Actor actor, java.util.function.Consumer<String> chunks) {
                        sdk.incrementAndGet();
                        boolean summary =
                                Json.tree(body).path("invocationRef").asText().endsWith("SUMMARY");
                        return Json.tree(
                                Map.of(
                                        "text",
                                        summary ? "retained fact 42" : "answer42",
                                        "toolCalls",
                                        List.of(),
                                        "usage",
                                        Map.of("totalTokens", summary ? 21 : 22)));
                    }
                };
        var config =
                Json.read(
                        "{\"modelEntryId\":1,\"output\":{\"maxTokens\":20},\"context\":{\"historyTurns\":1,\"summarization\":{\"enabled\":true}}}");
        var request =
                new ContextAssembler()
                        .assemble(
                                config,
                                Json.read("{\"message\":\"current\"}"),
                                List.of(),
                                List.of(),
                                List.of(
                                        Map.of("role", "user", "content", "old question"),
                                        Map.of("role", "assistant", "content", "x".repeat(3000))));
        var first =
                new ModelExchange(TracePostgresTest.store, clients)
                        .exchange(run, node, 0, request, TracePostgresTest.actor, s -> {});
        assertEquals("answer42", first.path("text").asText());
        assertEquals(2, sdk.get());
        assertEquals(
                2,
                TracePostgresTest.db.queryForObject(
                        "select count(*) from engine.model_invocation where run_id=? and"
                            + " usage_accounted=true and final_request is not null and response is"
                            + " not null",
                        Integer.class,
                        r));
        assertTrue(
                TracePostgresTest.db
                        .queryForObject(
                                "select source_request::text from engine.model_invocation where"
                                    + " run_id=? and kind='MAIN'",
                                String.class,
                                r)
                        .contains("x".repeat(3000)));
        assertTrue(
                TracePostgresTest.db
                        .queryForObject(
                                "select final_request::text from engine.model_invocation where"
                                    + " run_id=? and kind='MAIN'",
                                String.class,
                                r)
                        .contains("retained fact 42"));
        new ModelExchange(TracePostgresTest.store, clients)
                .exchange(run, node, 0, request, TracePostgresTest.actor, s -> {});
        assertEquals(2, sdk.get());
        assertEquals(
                43,
                TraceStore.json(
                                TracePostgresTest.db.queryForObject(
                                        "select budget from engine.run where id=?",
                                        String.class,
                                        r))
                        .path("consumed")
                        .path("tokens")
                        .asInt());
    }
}
