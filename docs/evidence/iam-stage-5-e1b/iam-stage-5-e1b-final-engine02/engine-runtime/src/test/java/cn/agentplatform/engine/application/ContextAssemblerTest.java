package cn.agentplatform.engine.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Json;

import org.junit.jupiter.api.Test;

import java.util.*;

class ContextAssemblerTest {
    @Test
    void eachRoundRetainsOriginalUserAndToolPairs() {
        var history =
                List.<Map<String, Object>>of(
                        Map.of(
                                "role",
                                "assistant",
                                "content",
                                "checking",
                                "tool_calls",
                                List.of(
                                        Map.of(
                                                "id",
                                                "call1",
                                                "type",
                                                "function",
                                                "function",
                                                Map.of("name", "lookup", "arguments", "{}")))),
                        Map.of("role", "tool", "tool_call_id", "call1", "content", "{}"));
        var result =
                new ContextAssembler()
                        .assemble(
                                Json.read("{\"modelEntryId\":1}"),
                                Json.read("{\"message\":\"original question\"}"),
                                history,
                                List.of());
        assertEquals("user", Json.tree(result.get("messages")).get(1).path("role").asText());
        assertEquals(
                "original question",
                Json.tree(result.get("messages")).get(1).path("content").asText());
    }

    @Test
    void orphanToolResultIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ContextAssembler()
                                .assemble(
                                        Json.read("{\"modelEntryId\":1}"),
                                        Json.read("{}"),
                                        List.of(
                                                Map.of(
                                                        "role",
                                                        "tool",
                                                        "tool_call_id",
                                                        "orphan",
                                                        "content",
                                                        "{}")),
                                        List.of()));
    }

    @Test
    void samplingAndOutputConfigReachFinalSnapshot() {
        var result =
                new ContextAssembler()
                        .assemble(
                                Json.read(
                                        "{\"modelEntryId\":1,\"sampling\":{\"temperature\":0.2,\"topP\":0.7},\"output\":{\"maxTokens\":123,\"format\":\"json\"},\"timeout\":{\"totalMs\":999}}"),
                                Json.read("{}"),
                                List.of(),
                                List.of());
        var params = Json.tree(result.get("params"));
        assertEquals(0.2, params.path("temperature").asDouble());
        assertEquals(123, params.path("maxOutputTokens").asInt());
        assertEquals("json", params.path("output").path("format").asText());
        assertEquals(999, params.path("timeout").path("totalMs").asInt());
    }
}
