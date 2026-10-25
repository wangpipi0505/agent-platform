package cn.agentplatform.engine.infra;

import cn.agentplatform.common.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RuntimeClients {
    private static org.springframework.http.client.SimpleClientHttpRequestFactory requestFactory() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(120000);
        return factory;
    }

    private final RestClient http = RestClient.builder().requestFactory(requestFactory()).build();
    private final RestClient probes = probeClient();

    private static RestClient probeClient() {
        var factory = requestFactory();
        factory.setReadTimeout(5000);
        return RestClient.builder().requestFactory(factory).build();
    }

    @Value("${agent.services.app:http://127.0.0.1:18081}")
    String app;

    @Value("${agent.services.skill:http://127.0.0.1:18083}")
    String skill;

    @Value("${agent.services.model:http://127.0.0.1:18084}")
    String model;

    public JsonNode get(String service, String path, Actor actor) {
        return unwrap(
                (path.startsWith("/internal/skill-facts/") ? probes : http)
                        .get()
                        .uri(base(service) + path)
                        .header("Authorization", "Bearer " + ServiceTokens.issue(actor))
                        .retrieve()
                        .body(String.class));
    }

    public JsonNode post(String service, String path, Object body, Actor actor) {
        return unwrap(
                http.post()
                        .uri(base(service) + path)
                        .header("Authorization", "Bearer " + ServiceTokens.issue(actor))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class));
    }

    private String base(String service) {
        return switch (service) {
            case "app" -> app;
            case "skill" -> skill;
            default -> model;
        };
    }

    private JsonNode unwrap(String text) {
        JsonNode n = Json.read(text);
        if (n.has("code")) {
            if (!n.path("code").asText().equals("OK"))
                throw new PlatformException(502, "UPSTREAM_ERROR", n.path("message").asText());
            return n.path("data");
        }
        return n;
    }

    public JsonNode chatStream(
            Object body, Actor actor, java.util.function.Consumer<String> onChunk) {
        java.net.HttpURLConnection connection = null;
        try {
            connection =
                    (java.net.HttpURLConnection)
                            java.net
                                    .URI
                                    .create(model + "/internal/model/chat/stream")
                                    .toURL()
                                    .openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(120000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setRequestProperty("Authorization", "Bearer " + ServiceTokens.issue(actor));
            try (var output = connection.getOutputStream()) {
                output.write(Json.write(body).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            if (connection.getResponseCode() != 200)
                throw new PlatformException(
                        502, "MODEL_STREAM_ERROR", "模型流请求失败 HTTP " + connection.getResponseCode());
            try (var reader =
                    new java.io.BufferedReader(
                            new java.io.InputStreamReader(
                                    connection.getInputStream(),
                                    java.nio.charset.StandardCharsets.UTF_8))) {
                String event = "message", line;
                StringBuilder data = new StringBuilder();
                while ((line = reader.readLine()) != null) {
                    if (line.length() > 4194304)
                        throw new PlatformException(502, "MODEL_STREAM_LIMIT", "模型流事件超过上限");
                    if (line.startsWith("event:")) event = line.substring(6).trim();
                    else if (line.startsWith("data:")) {
                        if (!data.isEmpty()) data.append('\n');
                        data.append(line.substring(5).stripLeading());
                    } else if (line.isEmpty() && !data.isEmpty()) {
                        JsonNode value = Json.read(data.toString());
                        if (event.equals("chunk")) onChunk.accept(value.path("text").asText());
                        else if (event.equals("final")) return value;
                        else if (event.equals("error"))
                            throw new PlatformException(
                                    502,
                                    "MODEL_STREAM_ERROR",
                                    value.path("message").asText("模型流失败"));
                        data.setLength(0);
                        event = "message";
                    }
                }
            }
            throw new PlatformException(502, "MODEL_STREAM_INCOMPLETE", "模型流缺少最终完整响应");
        } catch (java.io.IOException e) {
            throw new PlatformException(502, "MODEL_STREAM_IO", "模型流连接中断：" + e.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
