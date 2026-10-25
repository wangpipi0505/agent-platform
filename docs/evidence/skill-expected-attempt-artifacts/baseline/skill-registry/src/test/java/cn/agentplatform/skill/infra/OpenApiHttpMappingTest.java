package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.application.SkillCatalog;

import org.junit.jupiter.api.Test;

import java.util.*;

class OpenApiHttpMappingTest {
    static Map<String, Object> contract() {
        return new LinkedHashMap<>(
                Map.of(
                        "type",
                        "HTTP",
                        "endpoint",
                        "https://example.test/items/{id}",
                        "httpMethod",
                        "POST",
                        "timeoutMs",
                        1000,
                        "execution",
                        Map.of("sideEffect", false),
                        "httpMapping",
                        Map.of("path", List.of("id"), "query", List.of("q"), "body", "body")));
    }

    @Test
    void encodesPathAndQueryAsDataAndKeepsOnlyJsonBody() {
        var request =
                OpenApiHttpMapping.request(
                        contract(),
                        Map.of("id", "a/b?#空", "q", "a&b=c+", "body", Map.of("name", "x")));
        assertEquals(
                "https://example.test/items/a%2Fb%3F%23%E7%A9%BA?q=a%26b%3Dc%2B",
                request.endpoint());
        assertEquals(Map.of("name", "x"), request.body());
    }

    @Test
    void rejectsDotSegmentsMissingPathAndNonScalarQuery() {
        for (String id : List.of(".", "..", ""))
            assertThrows(
                    PlatformException.class,
                    () -> OpenApiHttpMapping.request(contract(), Map.of("id", id)));
        assertThrows(
                PlatformException.class, () -> OpenApiHttpMapping.request(contract(), Map.of()));
        assertThrows(
                PlatformException.class,
                () -> OpenApiHttpMapping.request(contract(), Map.of("id", "x", "q", List.of(1))));
        assertThrows(
                PlatformException.class,
                () ->
                        OpenApiHttpMapping.request(
                                contract(), Map.of("id", "x", "extra", "unmapped")));
    }

    @Test
    void catalogPreservesSourceAndMappingInActualNormalizedContract() {
        var catalog = new SkillCatalog(null, null, null, "http://unused");
        var body = contract();
        body.put("description", "读取测试");
        body.put(
                "inputSchema",
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                                "id",
                                Map.of("type", "string"),
                                "q",
                                Map.of("type", "string"),
                                "body",
                                Map.of("type", "object")),
                        "required",
                        List.of("id", "body")));
        body.put("outputSchema", Map.of("type", "object"));
        body.put(
                "execution",
                Map.of(
                        "readonly",
                        true,
                        "sideEffect",
                        false,
                        "idempotent",
                        true,
                        "cancellable",
                        false));
        body.put(
                "importSource",
                Map.of(
                        "kind",
                        "OPENAPI",
                        "operationId",
                        "read",
                        "method",
                        "POST",
                        "path",
                        "/items/{id}",
                        "documentSha256",
                        "a".repeat(64)));
        var normalized = catalog.normalize(body);
        assertEquals(body.get("httpMapping"), normalized.get("httpMapping"));
        assertEquals(body.get("importSource"), normalized.get("importSource"));
    }

    @Test
    void rejectsEditedSchemaThatNoLongerProvidesMappedPathParameters() {
        var body = contract();
        body.put("inputSchema", Map.of("type", "object", "properties", Map.of()));
        assertThrows(PlatformException.class, () -> OpenApiHttpMapping.validate(body));
    }

    @Test
    void adapterSendsMappedPathQueryAndBodyUsingActualHttpTransport() throws Exception {
        var received = new java.util.concurrent.atomic.AtomicReference<String>();
        var server =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/items/",
                exchange -> {
                    received.set(
                            exchange.getRequestURI().toASCIIString()
                                    + "|"
                                    + new String(
                                            exchange.getRequestBody().readAllBytes(),
                                            java.nio.charset.StandardCharsets.UTF_8));
                    byte[] bytes = "{}".getBytes();
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        try {
            var body = contract();
            body.put(
                    "endpoint",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/items/{id}");
            body.put("outputMaxBytes", 1024);
            body.put("outputSchema", Map.of("type", "object"));
            new SkillAdapters(mock(ArtifactStore.class), mock(AlgorithmAdapter.class))
                    .invoke(
                            body,
                            Map.of("id", "a/b", "q", "a&b", "body", Map.of("name", "x")),
                            "key",
                            1,
                            false,
                            null);
            assertEquals("/items/a%2Fb?q=a%26b|{\"name\":\"x\"}", received.get());
        } finally {
            server.stop(0);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"POST", "PATCH"})
    void jsonStringBodiesRemainJsonAndPatchActuallyReachesHttp(String method) throws Exception {
        var received = new java.util.concurrent.atomic.AtomicReference<String>();
        var server =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/items/",
                exchange -> {
                    received.set(
                            exchange.getRequestMethod()
                                    + "|"
                                    + exchange.getRequestHeaders().getFirst("Content-Type")
                                    + "|"
                                    + new String(
                                            exchange.getRequestBody().readAllBytes(),
                                            java.nio.charset.StandardCharsets.UTF_8));
                    byte[] bytes = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(bytes);
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        try {
            var body = contract();
            body.put("httpMethod", method);
            body.put(
                    "endpoint",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/items/{id}");
            body.put("outputMaxBytes", 1024);
            body.put("outputSchema", Map.of("type", "object"));
            new SkillAdapters(mock(ArtifactStore.class), mock(AlgorithmAdapter.class))
                    .invoke(body, Map.of("id", "item", "body", "hello"), "key", 1, false, null);
            assertEquals(method + "|application/json|\"hello\"", received.get());
        } finally {
            server.stop(0);
        }
    }
}
