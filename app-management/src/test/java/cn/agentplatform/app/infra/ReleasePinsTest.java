package cn.agentplatform.app.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 发布绑定客户端的单元证据：不触达数据库，桩服务器用 JDK HttpServer 记录真实请求。
 *
 * <p>密钥走生产同一条 {@link SecretVault} 解析路径（临时目录+临时主密钥），因此这里同时证明
 * {@code vault:} 引用被正确解析成 {@code X-API-Key}，而不是测试替身直接塞明文。
 */
class ReleasePinsTest {
    static final Actor ACTOR = new Actor("tenant-pin", "publisher", Set.of("ADMIN"), Map.of());
    static final String SPACE = "3f8b6c1e-9d2a-4a51-8a77-1c2d3e4f5a6b";
    static final String RELEASE = "8a1f2b3c-4d5e-4f60-9a71-b2c3d4e5f607";
    static final String API_KEY = "pin-test-api-key";

    static Path secretDir;
    static String apiKeyRef;
    static String previousDir;
    static String previousKey;

    @BeforeAll
    static void secrets() throws IOException {
        previousDir = System.getProperty("agent.secret.dir");
        previousKey = System.getProperty("agent.master.key");
        secretDir = Files.createTempDirectory("agent-pin-secret");
        System.setProperty("agent.secret.dir", secretDir.toString());
        System.setProperty("agent.master.key", "0123456789abcdef0123456789abcdef");
        apiKeyRef = SecretVault.put(API_KEY, ACTOR);
    }

    @AfterAll
    static void restoreSecrets() throws IOException {
        restore("agent.secret.dir", previousDir);
        restore("agent.master.key", previousKey);
        try (var paths = Files.walk(secretDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private static void restore(String name, String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
    }

    @Test
    void declaredOntologyPostsSingleBinding() throws Exception {
        try (var stub = new Stub()) {
            var pins = new ReleasePins(stub.base(), apiKeyRef, true, false, "production");

            var result = pins.pin(ACTOR, "app-a", candidate(Map.of("space", SPACE, "releaseId", RELEASE)));

            assertEquals("PINNED", result.get("state"));
            assertEquals("b-7", result.get("bindingId"));
            assertEquals(true, result.get("changed"));
            assertEquals(1, stub.requests.size());
            var request = stub.requests.getFirst();
            assertEquals("POST", request.method());
            assertEquals("/onto/internal/release-bindings", request.path());
            assertEquals(API_KEY, request.apiKey());
            // 只发本体专用头：Authorization 会把平台用户令牌带给另一个服务。
            assertNull(request.authorization());
            assertEquals(
                    Map.of(
                            "platformTenant", "tenant-pin",
                            "appCode", "app-a",
                            "environment", "production",
                            "releaseId", RELEASE),
                    request.body());
        }
    }

    @Test
    void missingOntologySkipsWithoutHttp() throws Exception {
        try (var stub = new Stub()) {
            var pins = new ReleasePins(stub.base(), apiKeyRef, true, false, "production");

            var result = pins.pin(ACTOR, "app-a", candidate(null));

            assertEquals("SKIPPED", result.get("state"));
            assertNotNull(result.get("reason"));
            assertEquals(0, stub.requests.size());
        }
    }

    @Test
    void emptyOntologyObjectSkipsWithoutHttp() throws Exception {
        try (var stub = new Stub()) {
            var pins = new ReleasePins(stub.base(), apiKeyRef, true, false, "production");

            var result = pins.pin(ACTOR, "app-a", candidate(Map.of()));

            assertEquals("SKIPPED", result.get("state"));
            assertEquals(0, stub.requests.size());
        }
    }

    @Test
    void nonUuidIdentifiersSkipWithReason() throws Exception {
        try (var stub = new Stub()) {
            var pins = new ReleasePins(stub.base(), apiKeyRef, true, false, "production");

            var badSpace = pins.pin(ACTOR, "app-a", candidate(Map.of("space", "space-1", "releaseId", RELEASE)));
            assertEquals("SKIPPED", badSpace.get("state"));
            assertTrue(badSpace.get("reason").toString().contains("UUID"), badSpace.get("reason").toString());

            var badRelease = pins.pin(ACTOR, "app-a", candidate(Map.of("space", SPACE, "releaseId", "release-1")));
            assertEquals("SKIPPED", badRelease.get("state"));
            assertTrue(badRelease.get("reason").toString().contains("UUID"), badRelease.get("reason").toString());

            assertEquals(0, stub.requests.size());
        }
    }

    @Test
    void remoteFailureBecomesFailed() throws Exception {
        try (var stub = new Stub()) {
            stub.status = 500;
            var pins = new ReleasePins(stub.base(), apiKeyRef, true, false, "production");

            var result = pins.pin(ACTOR, "app-a", candidate(Map.of("space", SPACE, "releaseId", RELEASE)));

            assertEquals("FAILED", result.get("state"));
            String reason = result.get("reason").toString();
            assertTrue(reason.length() <= 200, "reason 必须截断到 200 字以内: " + reason.length());
            assertEquals(1, stub.requests.size());
        }
    }

    @Test
    void failClosedRejectsWith502() throws Exception {
        try (var stub = new Stub()) {
            stub.status = 500;
            var pins = new ReleasePins(stub.base(), apiKeyRef, true, true, "production");

            var thrown =
                    assertThrows(
                            PlatformException.class,
                            () -> pins.pin(ACTOR, "app-a", candidate(Map.of("space", SPACE, "releaseId", RELEASE))));

            assertEquals(502, thrown.status());
            assertEquals("PIN_FAILED", thrown.code());
        }
    }

    @Test
    void disabledSwitchSendsNothing() throws Exception {
        try (var stub = new Stub()) {
            var pins = new ReleasePins(stub.base(), apiKeyRef, false, false, "production");

            var result = pins.pin(ACTOR, "app-a", candidate(Map.of("space", SPACE, "releaseId", RELEASE)));

            assertEquals("DISABLED", result.get("state"));
            assertEquals(0, stub.requests.size());
        }
    }

    @Test
    void missingApiKeyRefIsDisabled() throws Exception {
        try (var stub = new Stub()) {
            var pins = new ReleasePins(stub.base(), "  ", true, false, "production");

            var result = pins.pin(ACTOR, "app-a", candidate(Map.of("space", SPACE, "releaseId", RELEASE)));

            assertEquals("DISABLED", result.get("state"));
            assertEquals(0, stub.requests.size());
        }
    }

    @Test
    void missingBaseUrlIsDisabled() throws Exception {
        try (var stub = new Stub()) {
            var pins = new ReleasePins(" ", apiKeyRef, true, false, "production");

            var result = pins.pin(ACTOR, "app-a", candidate(Map.of("space", SPACE, "releaseId", RELEASE)));

            assertEquals("DISABLED", result.get("state"));
            assertEquals(0, stub.requests.size());
        }
    }

    @Test
    void redirectIsNotFollowed() throws Exception {
        try (var stub = new Stub()) {
            stub.status = 302;
            stub.location = "/elsewhere";
            var pins = new ReleasePins(stub.base(), apiKeyRef, true, false, "production");

            var result = pins.pin(ACTOR, "app-a", candidate(Map.of("space", SPACE, "releaseId", RELEASE)));

            assertEquals("FAILED", result.get("state"));
            // 302 只允许到达一次；跟随重定向会产生第二个请求并可能把密钥带到别的主机。
            assertEquals(List.of("/onto/internal/release-bindings"), stub.paths());
        }
    }

    static Map<String, Object> candidate(Object ontology) {
        var config = new LinkedHashMap<String, Object>();
        if (ontology != null) config.put("ontology", ontology);
        var candidate = new LinkedHashMap<String, Object>();
        candidate.put("appCode", "app-a");
        candidate.put("config", config);
        return candidate;
    }

    /** 记录每个真实请求的方法、路径、密钥头与解析后的 JSON 正文；测试断言只看这些事实。 */
    private static final class Stub implements AutoCloseable {
        private final HttpServer server;
        final List<Recorded> requests = new CopyOnWriteArrayList<>();
        volatile int status = 200;
        volatile String body = "{\"data\":{\"bindingId\":\"b-7\",\"changed\":true}}";
        volatile String location;

        record Recorded(
                String method,
                String path,
                String apiKey,
                String authorization,
                Map<String, Object> body) {}

        Stub() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        String base() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        List<String> paths() {
            return requests.stream().map(Recorded::path).toList();
        }

        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                String raw =
                        new String(
                                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, Object> parsed;
                try {
                    parsed = Json.read(raw, Map.class);
                } catch (RuntimeException e) {
                    parsed = Map.of();
                }
                var headers = exchange.getRequestHeaders();
                requests.add(
                        new Recorded(
                                exchange.getRequestMethod(),
                                exchange.getRequestURI().getPath(),
                                headers.getFirst("X-API-Key"),
                                headers.getFirst("Authorization"),
                                parsed));
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                if (location != null) exchange.getResponseHeaders().set("Location", location);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
