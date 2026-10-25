package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** The same domain gate also validates ordinary HTTP output; no MCP SDK involved here. */
class HttpSchemaBoundaryTest {
    HttpServer server;
    ExecutorService workers;
    AtomicInteger schemaReads = new AtomicInteger(), businessCalls = new AtomicInteger();
    SkillAdapters adapters;

    @BeforeEach
    void start() throws Exception {
        workers = Executors.newVirtualThreadPerTaskExecutor();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers);
        server.createContext(
                "/result",
                exchange -> {
                    businessCalls.incrementAndGet();
                    exchange.getRequestBody().readNBytes(128 * 1024);
                    byte[] bytes = "{\"value\":7}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.createContext(
                "/schema",
                exchange -> {
                    schemaReads.incrementAndGet();
                    byte[] bytes = "{\"type\":\"object\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/schema+json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        adapters = new SkillAdapters(mock(ArtifactStore.class), mock(AlgorithmAdapter.class));
    }

    @AfterEach
    void stop() {
        server.stop(0);
        workers.shutdownNow();
    }

    String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    SkillAdapters.Raw invoke(Map<String, Object> schema) {
        return adapters.invoke(
                Map.of(
                        "type",
                        "HTTP",
                        "endpoint",
                        url("/result"),
                        "httpMethod",
                        "POST",
                        "timeoutMs",
                        2000,
                        "execution",
                        Map.of("readonly", true, "sideEffect", false),
                        "outputSchema",
                        schema,
                        "outputMaxBytes",
                        1024),
                Map.of(),
                "key",
                1,
                false,
                "r_http_schema",
                1);
    }

    void reject(Map<String, Object> schema) {
        SkillAdapters.Failure failure = null;
        try {
            invoke(schema);
        } catch (SkillAdapters.Failure actual) {
            failure = actual;
        }
        assertEquals(0, schemaReads.get(), "HTTP output gate must not fetch untrusted Schema");
        assertNotNull(failure);
        assertEquals("OUTPUT_INVALID", failure.semantic);
        assertEquals(1, businessCalls.get());
    }

    @Test
    void httpOutputDynamicRefCannotReadExternalSchema() {
        reject(Map.of("type", "object", "$dynamicRef", url("/schema")));
    }

    @Test
    void httpOutputUnknownMetaSchemaCannotReadNetwork() {
        reject(Map.of("type", "object", "$schema", url("/schema")));
    }

    @Test
    void httpOutputUnusedMissingLocalRefIsEagerlyRejected() {
        reject(
                Map.of(
                        "type",
                        "object",
                        "$defs",
                        Map.of("unused", Map.of("$ref", "#/$defs/missing"))));
    }

    @Test
    void literalDefaultRefDoesNotInvalidateHttpOutput() {
        var schema =
                Map.<String, Object>of(
                        "type",
                        "object",
                        "properties",
                        Map.of("optional", Map.of("default", Map.of("$ref", url("/schema")))));
        assertEquals(
                7, assertDoesNotThrow(() -> invoke(schema)).structured().path("value").asInt());
        assertEquals(0, schemaReads.get());
    }

    @Test
    void validLocalReferencesAndBundledMetaSchemaStillValidate() {
        assertEquals(
                7,
                invoke(
                                Map.of(
                                        "type",
                                        "object",
                                        "$schema",
                                        "https://json-schema.org/draft/2020-12/schema",
                                        "$defs",
                                        Map.of("number", Map.of("type", "integer", "minimum", 7)),
                                        "properties",
                                        Map.of("value", Map.of("$ref", "#/$defs/number"))))
                        .structured()
                        .path("value")
                        .asInt());
        assertEquals(0, schemaReads.get());
    }

    @Test
    void frozenInputLiteralReferencesAreAcceptedByCommonGate() {
        var schema =
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                                "value",
                                Map.of("type", "integer"),
                                "optional",
                                Map.of("const", Map.of("$ref", url("/schema")))));
        assertDoesNotThrow(() -> cn.agentplatform.skill.domain.SkillRules.schema(schema));
        assertDoesNotThrow(
                () ->
                        cn.agentplatform.skill.domain.SkillRules.validate(
                                schema, Map.of("value", 7)));
        assertEquals(0, schemaReads.get());
    }

    @Test
    void ordinaryHttpOutputUsesTheSameExpansionPreflight() {
        var schema = McpSchemaBoundaryTest.overBudgetSchema();
        assertThrows(
                IllegalArgumentException.class,
                () -> cn.agentplatform.skill.domain.SkillRules.schema(schema));
        reject(schema);
    }

    @Test
    void ordinaryHttpOutputRejectsUnsupportedRegexWithoutJdkFallback() {
        reject(
                Map.of(
                        "type",
                        "object",
                        "patternProperties",
                        Map.of("(?=value)value", Map.of("type", "number"))));
    }
}
