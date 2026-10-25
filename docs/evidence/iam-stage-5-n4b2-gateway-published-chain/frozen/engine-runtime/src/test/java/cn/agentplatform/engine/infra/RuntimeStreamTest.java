package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;

import org.junit.jupiter.api.Test;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

class RuntimeStreamTest {
    @Test
    void deliversProviderChunksBeforeFinalResponse() throws Exception {
        System.setProperty("agent.jwt.secret", "engine-test-only-jwt-key-at-least-32-characters");
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> fixture =
                    executor.submit(
                            () -> {
                                try (Socket socket = server.accept()) {
                                    var input = socket.getInputStream();
                                    var headers = new java.io.ByteArrayOutputStream();
                                    int state = 0;
                                    while (state != 4) {
                                        int b = input.read();
                                        if (b < 0)
                                            throw new IllegalStateException("incomplete request");
                                        headers.write(b);
                                        state =
                                                switch (state) {
                                                    case 0 -> b == '\r' ? 1 : 0;
                                                    case 1 -> b == '\n' ? 2 : 0;
                                                    case 2 -> b == '\r' ? 3 : 0;
                                                    default -> b == '\n' ? 4 : 0;
                                                };
                                    }
                                    String head = headers.toString(StandardCharsets.UTF_8);
                                    int length = 0;
                                    for (String line : head.split("\r\n"))
                                        if (line.toLowerCase(Locale.ROOT)
                                                .startsWith("content-length:"))
                                            length = Integer.parseInt(line.substring(15).trim());
                                    String body =
                                            new String(
                                                    input.readNBytes(length),
                                                    StandardCharsets.UTF_8);
                                    var out = socket.getOutputStream();
                                    out.write(
                                            "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\nevent: chunk\ndata: {\"text\":\"first\"}\n\n"
                                                    .getBytes(StandardCharsets.UTF_8));
                                    out.flush();
                                    Thread.sleep(30);
                                    out.write(
                                            "event: chunk\ndata: {\"text\":\" second\"}\n\nevent: final\ndata: {\"text\":\"first second\",\"toolCalls\":[],\"usage\":{},\"actualModel\":\"fixture\"}\n\n"
                                                    .getBytes(StandardCharsets.UTF_8));
                                    out.flush();
                                    return body;
                                }
                            });
            var clients = new RuntimeClients();
            clients.model = "http://127.0.0.1:" + server.getLocalPort();
            List<String> chunks = new ArrayList<>();
            var response =
                    clients.chatStream(
                            Map.of("messages", List.of(Map.of("role", "user", "content", "hello"))),
                            new Actor("tenant", "user", Set.of("EDITOR"), Map.of()),
                            chunks::add);
            assertEquals(List.of("first", " second"), chunks);
            assertEquals("first second", response.path("text").asText());
            assertTrue(fixture.get(5, TimeUnit.SECONDS).contains("hello"));
        }
    }
}
