package cn.agentplatform.engine.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.engine.infra.OntologyContextPacks;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 冻结段是唯一允许取包的时刻：之后 assemble 必须只读 config，不再触网。
 * 这些用例同时守住"不声明 ontology 就完全不变"的既有行为。
 */
class RunServiceContextPackTest {
    private static final String SPACE = "11111111-1111-1111-1111-111111111111";
    private static final String RELEASE = "22222222-2222-2222-2222-222222222222";

    private static final class Stub implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger count = new AtomicInteger();
        private final AtomicReference<String> body = new AtomicReference<>("{}");

        private Stub() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext(
                    "/onto/",
                    exchange -> {
                        count.incrementAndGet();
                        byte[] out = body.get().getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, out.length);
                        exchange.getResponseBody().write(out);
                        exchange.close();
                    });
            server.start();
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static Actor actor() {
        return new Actor("tenant-a", "user-1", Set.of(), Map.of());
    }

    private static String packBody() {
        var data = Json.object();
        data.put("candidateSha256", "a".repeat(64));
        data.putArray("conceptCards")
                .addObject()
                .put("name", "学生")
                .put("description", "在籍学生")
                .putArray("properties")
                .addObject()
                .put("name", "学号");
        data.putArray("termDictionary").addObject().put("term", "学号").put("definition", "唯一标识");
        data.putArray("relationSummaries").addObject().put("name", "选修");
        data.putArray("exampleQa")
                .addObject()
                .put("question", "如何创建学生？")
                .put("answer", "{\"status\":\"SUCCEEDED\"}");
        var envelope = Json.object();
        envelope.put("code", "OK");
        envelope.put("message", "");
        envelope.set("data", data);
        return Json.write(envelope);
    }

    private static JsonNode snapshot(String space, String releaseId) {
        var config = Json.object();
        config.put("modelEntryId", 1);
        var ontology = config.putObject("ontology");
        if (space != null) ontology.put("space", space);
        if (releaseId != null) ontology.put("releaseId", releaseId);
        var snapshot = Json.object();
        snapshot.set("config", config);
        snapshot.set("definition", Json.read("{\"nodes\":[]}"));
        return snapshot;
    }

    private static JsonNode freeze(RunService service, JsonNode snapshot) throws Exception {
        var method =
                RunService.class.getDeclaredMethod(
                        "prepareSnapshot", JsonNode.class, Actor.class, Long.class);
        method.setAccessible(true);
        return (JsonNode) method.invoke(service, snapshot, actor(), null);
    }

    /** 反射调用会把受检/运行时异常包成 InvocationTargetException；取回真实异常便于断言状态码。 */
    private static PlatformException freezeFailure(RunService service, JsonNode snapshot) {
        var thrown =
                assertThrows(
                        InvocationTargetException.class,
                        () -> freeze(service, snapshot));
        var cause = thrown.getCause();
        assertInstanceOf(PlatformException.class, cause);
        return (PlatformException) cause;
    }

    private static RunService serviceWith(Stub stub) {
        var service = new RunService(null, null, null, null);
        service.contextPacks(
                OntologyContextPacks.forApiKey(stub.baseUrl(), "k-1", Clock.systemUTC()));
        return service;
    }

    @Test
    void successfulPackIsFrozenAndAuditedBeforeDispatch() throws Exception {
        try (var stub = new Stub()) {
            stub.body.set(packBody());
            var service = serviceWith(stub);
            var frozen = freeze(service, snapshot(SPACE, RELEASE));
            assertEquals(1, stub.count.get());

            var config = frozen.path("config");
            String text = config.path("ontologyContext").asText();
            assertTrue(text.contains("学生"));
            assertTrue(text.contains("学号"));

            var audit = frozen.path("contextPack");
            assertEquals(SPACE, audit.path("space").asText());
            assertEquals(RELEASE, audit.path("releaseId").asText());
            assertEquals(Digest.sha256(stub.body.get()), audit.path("sha256").asText());
            assertFalse(audit.path("fetchedAt").asText().isBlank());
            assertFalse(audit.path("truncated").asBoolean());

            // 运行时渲染只读冻结配置：消息顺序不变，本体段落追加在既有段落之后。
            var request =
                    new ContextAssembler()
                            .assemble(
                                    config,
                                    Json.read("{\"message\":\"你好\"}"),
                                    List.of(),
                                    List.of());
            @SuppressWarnings("unchecked")
            var messages = (List<Map<String, Object>>) request.get("messages");
            assertEquals("system", messages.get(0).get("role"));
            String system = (String) messages.get(0).get("content");
            assertTrue(system.endsWith("\n\n[本体上下文]\n" + text));
            assertEquals("你好", messages.get(1).get("content"));
            assertEquals(1, stub.count.get(), "运行时 assemble 不得再次取包");
        }
    }

    @Test
    void runWithoutOntologyDeclarationNeverCallsOntology() throws Exception {
        try (var stub = new Stub()) {
            stub.body.set(packBody());
            var service = serviceWith(stub);
            var frozen = freeze(service, snapshot(null, null));
            assertEquals(0, stub.count.get());
            assertFalse(frozen.path("config").has("ontologyContext"));
            assertFalse(frozen.has("contextPack"));
            assertTrue(frozen.path("config").path("tools").isArray());
        }
    }

    @Test
    void declaredOntologyWithoutClientIsUnavailable() {
        var service = new RunService(null, null, null, null);
        var failure = freezeFailure(service, snapshot(SPACE, RELEASE));
        assertEquals(503, failure.status());
        assertEquals("CONTEXT_PACK_UNAVAILABLE", failure.code());
    }

    @Test
    void invalidOntologyConfigIsRejectedWithoutHttp() throws Exception {
        try (var stub = new Stub()) {
            stub.body.set(packBody());
            var service = serviceWith(stub);

            var missingSpace = freezeFailure(service, snapshot(null, RELEASE));
            assertEquals(400, missingSpace.status());
            assertEquals("CONTEXT_PACK_CONFIG_INVALID", missingSpace.code());

            var missingRelease = freezeFailure(service, snapshot(SPACE, null));
            assertEquals(400, missingRelease.status());
            assertEquals("CONTEXT_PACK_CONFIG_INVALID", missingRelease.code());

            var malformedRelease = freezeFailure(service, snapshot(SPACE, "not-a-uuid"));
            assertEquals(400, malformedRelease.status());
            assertEquals("CONTEXT_PACK_CONFIG_INVALID", malformedRelease.code());

            assertEquals(0, stub.count.get(), "配置非法时不得发出取包请求");
        }
    }
}
