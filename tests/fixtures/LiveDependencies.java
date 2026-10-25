import cn.agentplatform.common.Json;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.*;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 仅联合测试使用的隔离依赖；计数外部写动作，绝不用于产品降级或真实模型验收。 */
public class LiveDependencies {
    static final Set<String> written = ConcurrentHashMap.newKeySet();
    static final AtomicInteger writes = new AtomicInteger();

    static void json(HttpExchange exchange, Object value) throws java.io.IOException {
        byte[] bytes = Json.write(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    public static void main(String[] args) throws Exception {
        HttpServer model = HttpServer.create(new InetSocketAddress("127.0.0.1", 28410), 0);
        model.createContext(
                "/v1/models", e -> json(e, Map.of("data", List.of(Map.of("id", "gpt-4")))));
        model.createContext(
                "/v1/chat/completions",
                exchange -> {
                    JsonNode request =
                            Json.read(
                                    new String(
                                            exchange.getRequestBody().readNBytes(2 * 1024 * 1024),
                                            StandardCharsets.UTF_8));
                    boolean hasToolResult = false;
                    for (JsonNode m : request.path("messages"))
                        if ("tool".equals(m.path("role").asText())) hasToolResult = true;
                    boolean useTool =
                            request.path("tools").isArray()
                                    && !request.path("tools").isEmpty()
                                    && !hasToolResult;
                    String answer = "受控测试完成", finish = useTool ? "tool_calls" : "stop";
                    Map<String, Object> arguments = new LinkedHashMap<>();
                    String name = "";
                    if (useTool) {
                        JsonNode tool = request.path("tools").get(0).path("function");
                        name = tool.path("name").asText();
                        tool.path("parameters")
                                .path("properties")
                                .fields()
                                .forEachRemaining(
                                        p ->
                                                arguments.put(
                                                        p.getKey(),
                                                        switch (p.getValue()
                                                                .path("type")
                                                                .asText()) {
                                                            case "number", "integer" -> 1;
                                                            case "boolean" -> true;
                                                            case "object" -> Map.of();
                                                            case "array" -> List.of();
                                                            default -> "test-value";
                                                        }));
                    }
                    Object message =
                            useTool
                                    ? Map.of(
                                            "role",
                                            "assistant",
                                            "content",
                                            "",
                                            "tool_calls",
                                            List.of(
                                                    Map.of(
                                                            "id",
                                                            "fixture_call",
                                                            "type",
                                                            "function",
                                                            "function",
                                                            Map.of(
                                                                    "name",
                                                                    name,
                                                                    "arguments",
                                                                    Json.write(arguments)))))
                                    : Map.of("role", "assistant", "content", answer);
                    if (!request.path("stream").asBoolean()) {
                        json(
                                exchange,
                                Map.of(
                                        "id",
                                        "fixture_response",
                                        "object",
                                        "chat.completion",
                                        "created",
                                        1,
                                        "model",
                                        "gpt-4",
                                        "choices",
                                        List.of(
                                                Map.of(
                                                        "index",
                                                        0,
                                                        "message",
                                                        message,
                                                        "finish_reason",
                                                        finish)),
                                        "usage",
                                        Map.of(
                                                "prompt_tokens",
                                                8,
                                                "completion_tokens",
                                                4,
                                                "total_tokens",
                                                12)));
                        return;
                    }
                    Object delta =
                            useTool
                                    ? Map.of(
                                            "role",
                                            "assistant",
                                            "tool_calls",
                                            List.of(
                                                    Map.of(
                                                            "index",
                                                            0,
                                                            "id",
                                                            "fixture_call",
                                                            "type",
                                                            "function",
                                                            "function",
                                                            Map.of(
                                                                    "name",
                                                                    name,
                                                                    "arguments",
                                                                    Json.write(arguments)))))
                                    : Map.of("role", "assistant", "content", answer);
                    String data =
                            "data: "
                                    + Json.write(
                                            Map.of(
                                                    "id",
                                                    "fixture_stream",
                                                    "object",
                                                    "chat.completion.chunk",
                                                    "created",
                                                    1,
                                                    "model",
                                                    "gpt-4",
                                                    "choices",
                                                    List.of(
                                                            Map.of(
                                                                    "index",
                                                                    0,
                                                                    "delta",
                                                                    delta,
                                                                    "finish_reason",
                                                                    finish))))
                                    + "\n\ndata: "
                                    + Json.write(
                                            Map.of(
                                                    "id",
                                                    "fixture_stream",
                                                    "object",
                                                    "chat.completion.chunk",
                                                    "created",
                                                    1,
                                                    "model",
                                                    "gpt-4",
                                                    "choices",
                                                    List.of(),
                                                    "usage",
                                                    Map.of(
                                                            "prompt_tokens",
                                                            8,
                                                            "completion_tokens",
                                                            4,
                                                            "total_tokens",
                                                            12)))
                                    + "\n\ndata: [DONE]\n\n";
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write(data.getBytes(StandardCharsets.UTF_8));
                    exchange.close();
                });
        HttpServer skill = HttpServer.create(new InetSocketAddress("127.0.0.1", 28411), 0);
        skill.createContext(
                "/write",
                e -> {
                    String key = e.getRequestHeaders().getFirst("Idempotency-Key");
                    if (key == null || key.isBlank()) {
                        e.sendResponseHeaders(400, -1);
                        e.close();
                        return;
                    }
                    JsonNode body =
                            Json.read(
                                    new String(
                                            e.getRequestBody().readNBytes(1048576),
                                            StandardCharsets.UTF_8));
                    if (written.add(key)) writes.incrementAndGet();
                    json(e, Map.of("ok", true, "value", body.path("message").asText()));
                });
        skill.createContext("/stats", e -> json(e, Map.of("writes", writes.get())));
        model.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        skill.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        model.start();
        skill.start();
        System.out.println("TEST ONLY: model=28410 skill=28411; no production fallback");
    }
}
