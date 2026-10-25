package cn.agentplatform.engine.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.*;

class ContextProjectionTest {
    Map<String, Object> request() {
        return new LinkedHashMap<>(
                Map.of(
                        "messages",
                        new ArrayList<>(
                                List.of(
                                        Map.of("role", "system", "content", "governance"),
                                        Map.of("role", "user", "content", "current question"),
                                        Map.of(
                                                "role",
                                                "assistant",
                                                "content",
                                                "",
                                                "tool_calls",
                                                List.of(Map.of("id", "old"))),
                                        Map.of(
                                                "role",
                                                "tool",
                                                "tool_call_id",
                                                "old",
                                                "content",
                                                "x".repeat(3000)),
                                        Map.of("role", "assistant", "content", "latest"))),
                        "toolDefinitions",
                        List.of(),
                        "params",
                        Json.read(
                                "{\"context\":{\"historyTurns\":1,\"summarization\":{\"enabled\":true}},\"maxOutputTokens\":20}")));
    }

    @Test
    void projectsWithoutDeletingOriginalAndLogsRealMeasurementAndSummary() {
        var original = request();
        var summaries = new AtomicInteger();
        var result =
                new ContextProjection()
                        .apply(
                                original,
                                r ->
                                        Json.tree(
                                                Map.of(
                                                        "inputTokens",
                                                        Json.write(r.get("messages")).length(),
                                                        "contextWindow",
                                                        600,
                                                        "maxOutputTokens",
                                                        20,
                                                        "tokenizer",
                                                        "fixture",
                                                        "exact",
                                                        false)),
                                source -> {
                                    summaries.incrementAndGet();
                                    return "retained fact 42";
                                });
        assertEquals(1, summaries.get());
        assertTrue(Json.write(result.get("messages")).contains("retained fact 42"));
        assertTrue(Json.write(original.get("messages")).contains("x".repeat(3000)));
        assertFalse(
                Json.tree(result.get("manifest")).path("measurement").path("exact").asBoolean());
        assertFalse(Json.tree(result.get("manifest")).path("trimLog").isEmpty());
        assertEquals(
                "current question",
                Json.tree(result.get("messages"))
                        .get(Json.tree(result.get("manifest")).path("currentUserIndex").asInt())
                        .path("content")
                        .asText());
    }

    @Test
    void protectedOverflowFailsAndDoesNotInventTokenizer() {
        assertThrows(
                PlatformException.class,
                () ->
                        new ContextProjection()
                                .apply(
                                        request(),
                                        r ->
                                                Json.tree(
                                                        Map.of(
                                                                "inputTokens",
                                                                999,
                                                                "contextWindow",
                                                                100,
                                                                "maxOutputTokens",
                                                                20)),
                                        s -> "summary"));
    }
}
