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

    // === T18.2 运行期按发布绑定解析 releaseId =================================================
    //
    // 发布时 pin 写进了本体绑定，运行期必须按绑定取 releaseId；否则"发布了 A 版、运行时读最新版"。
    // 双端点桩把绑定查询与取包分开计数，"显式 releaseId 不走绑定"才是可断言的。

    private static final String BINDING_ID = "55555555-5555-5555-5555-555555555555";
    private static final String APP = "app_demo";

    private static String bindingEnvelope(String releaseId, String bindingId) {
        var data = Json.object();
        data.put("releaseId", releaseId);
        data.put("bindingId", bindingId);
        data.put("status", "ACTIVE");
        var envelope = Json.object();
        envelope.put("code", "OK");
        envelope.put("message", "");
        envelope.set("data", data);
        return Json.write(envelope);
    }

    private static void reply(
            com.sun.net.httpserver.HttpExchange exchange, int status, String body) {
        try {
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
        } catch (java.io.IOException e) {
            // 桩端写失败只影响该请求，不掩盖被测代码的断言。
        } finally {
            exchange.close();
        }
    }

    /** 同一个 base-url 同时提供绑定查询与取包，分别计数与记录路径。 */
    private static final class BindingStub implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger bindingCalls = new AtomicInteger();
        private final AtomicInteger packCalls = new AtomicInteger();
        private final List<String> packPaths = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final AtomicInteger bindingStatus = new AtomicInteger(200);
        private final AtomicReference<String> bindingBody =
                new AtomicReference<>(bindingEnvelope(RELEASE, BINDING_ID));

        private BindingStub() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext(
                    "/onto/",
                    exchange -> {
                        String path = exchange.getRequestURI().getPath();
                        if (path.equals("/onto/internal/release-bindings")) {
                            bindingCalls.incrementAndGet();
                            reply(exchange, bindingStatus.get(), bindingBody.get());
                            return;
                        }
                        packCalls.incrementAndGet();
                        packPaths.add(path);
                        reply(exchange, 200, packBody());
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

    private static RunService serviceWith(BindingStub stub) {
        return serviceWithBase(stub.baseUrl());
    }

    private static RunService serviceWithBase(String baseUrl) {
        var service = new RunService(null, null, null, null);
        service.contextPacks(OntologyContextPacks.forApiKey(baseUrl, "k-1", Clock.systemUTC()));
        return service;
    }

    private static JsonNode snapshotWithApp(String space, String releaseId, String appCode) {
        var config = Json.object();
        config.put("modelEntryId", 1);
        var ontology = config.putObject("ontology");
        if (space != null) ontology.put("space", space);
        if (releaseId != null) ontology.put("releaseId", releaseId);
        var snapshot = Json.object();
        if (appCode != null) snapshot.put("appCode", appCode);
        snapshot.set("config", config);
        snapshot.set("definition", Json.read("{\"nodes\":[]}"));
        return snapshot;
    }

    @Test
    void explicitReleaseIdWinsWithoutBindingCall() throws Exception {
        try (var stub = new BindingStub()) {
            var service = serviceWith(stub);
            var frozen = freeze(service, snapshotWithApp(SPACE, RELEASE, APP));

            assertEquals(0, stub.bindingCalls.get(), "显式 releaseId 必须先于绑定查询被采纳");
            assertEquals(1, stub.packCalls.get());
            assertEquals(List.of("/onto/releases/" + RELEASE + "/context-pack"), stub.packPaths);
            var audit = frozen.path("contextPack");
            assertEquals(RELEASE, audit.path("releaseId").asText());
            // 显式路径的审计形状与 T13 逐字段一致：不额外写绑定解析字段。
            assertFalse(audit.has("pinResolved"));
            assertFalse(audit.has("bindingId"));
        }
    }

    @Test
    void missingReleaseIdResolvesFromBinding() throws Exception {
        try (var stub = new BindingStub()) {
            var service = serviceWith(stub);
            var frozen = freeze(service, snapshotWithApp(SPACE, null, APP));

            assertEquals(1, stub.bindingCalls.get());
            assertEquals(List.of("/onto/releases/" + RELEASE + "/context-pack"), stub.packPaths);
            assertTrue(frozen.path("config").path("ontologyContext").asText().contains("学生"));

            var audit = frozen.path("contextPack");
            assertTrue(audit.path("pinResolved").asBoolean());
            assertEquals(BINDING_ID, audit.path("bindingId").asText());
            assertEquals(RELEASE, audit.path("releaseId").asText());
            assertEquals(SPACE, audit.path("space").asText());
            assertFalse(audit.path("sha256").asText().isBlank());
        }
    }

    @Test
    void bindingNotFoundSkipsContextButRunContinues() throws Exception {
        try (var stub = new BindingStub()) {
            stub.bindingStatus.set(404);
            stub.bindingBody.set("{\"code\":\"BINDING_NOT_FOUND\",\"message\":\"没有生效的发布绑定\"}");
            var service = serviceWith(stub);

            // 不抛异常即为"运行继续"：冻结结果仍然完整可下发。
            var frozen = freeze(service, snapshotWithApp(SPACE, null, APP));

            assertEquals(1, stub.bindingCalls.get());
            assertEquals(0, stub.packCalls.get(), "没有绑定就没有可取的包");
            assertFalse(frozen.path("config").has("ontologyContext"));
            assertTrue(frozen.path("config").path("tools").isArray());

            var audit = frozen.path("contextPack");
            assertEquals("BINDING_NOT_FOUND", audit.path("pinFailure").asText());
            assertFalse(audit.path("pinResolved").asBoolean());
            assertFalse(audit.path("reason").asText().isBlank());
            assertEquals(SPACE, audit.path("space").asText());
        }
    }

    @Test
    void bindingFailureFailsRunWithDependencyCode() throws Exception {
        try (var stub = new BindingStub()) {
            stub.bindingStatus.set(500);
            stub.bindingBody.set("{\"code\":\"INTERNAL\",\"message\":\"boom\"}");
            var service = serviceWith(stub);

            var failure = freezeFailure(service, snapshotWithApp(SPACE, null, APP));
            assertEquals(503, failure.status());
            assertEquals("CONTEXT_PACK_UNAVAILABLE", failure.code());
            assertTrue(failure.getMessage().contains("绑定"), failure.getMessage());
            assertEquals(0, stub.packCalls.get());

            // 故障不得被缓存：下一次运行必须重新解析，而不是被 30 秒窗口钉住。
            freezeFailure(service, snapshotWithApp(SPACE, null, APP));
            assertEquals(2, stub.bindingCalls.get());
        }
        String deadUrl;
        try (var closure = new BindingStub()) {
            deadUrl = closure.baseUrl();
        }
        var refused = freezeFailure(serviceWithBase(deadUrl), snapshotWithApp(SPACE, null, APP));
        assertEquals(503, refused.status());
        assertEquals("CONTEXT_PACK_UNAVAILABLE", refused.code());
        assertTrue(refused.getMessage().contains("绑定"), refused.getMessage());
    }

    @Test
    void resolutionDisabledKeepsLegacyBehaviour() throws Exception {
        try (var stub = new BindingStub()) {
            var service = serviceWith(stub);
            service.pinResolutionEnabled(false);

            // 关闭开关：缺 releaseId 时不查询绑定，退回 T13 的既有拒绝（既有用例已锁定该码）。
            var failure = freezeFailure(service, snapshotWithApp(SPACE, null, APP));
            assertEquals(400, failure.status());
            assertEquals("CONTEXT_PACK_CONFIG_INVALID", failure.code());
            assertEquals(0, stub.bindingCalls.get());

            // 显式 releaseId 与开关无关，照旧注入。
            var frozen = freeze(service, snapshotWithApp(SPACE, RELEASE, APP));
            assertEquals(1, stub.packCalls.get());
            assertEquals(0, stub.bindingCalls.get());
            assertEquals(RELEASE, frozen.path("contextPack").path("releaseId").asText());
        }
    }
}
