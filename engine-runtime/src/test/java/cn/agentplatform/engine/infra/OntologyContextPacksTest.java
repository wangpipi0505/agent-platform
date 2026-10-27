package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 取包链路的失败窗口与缓存边界。桩只验证平台发出的请求，不模拟本体业务规则。
 */
class OntologyContextPacksTest {
    private static final String SPACE = "11111111-1111-1111-1111-111111111111";
    private static final String RELEASE = "22222222-2222-2222-2222-222222222222";

    /** JDK 桩：记录方法、路径、请求头与次数；响应体/状态可逐用例替换。 */
    private static final class Stub implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger count = new AtomicInteger();
        private final List<String> methods = new CopyOnWriteArrayList<>();
        private final List<String> paths = new CopyOnWriteArrayList<>();
        private final List<Map<String, List<String>>> headers = new CopyOnWriteArrayList<>();
        private final AtomicReference<String> body = new AtomicReference<>("{}");
        private final AtomicInteger status = new AtomicInteger(200);

        private Stub() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext(
                    "/onto/",
                    exchange -> {
                        count.incrementAndGet();
                        methods.add(exchange.getRequestMethod());
                        paths.add(exchange.getRequestURI().getPath());
                        var copy = new LinkedHashMap<String, List<String>>();
                        exchange.getRequestHeaders()
                                .forEach((k, v) -> copy.put(k, List.copyOf(v)));
                        headers.add(copy);
                        byte[] out = body.get().getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        exchange.sendResponseHeaders(status.get(), out.length);
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

    private static String header(Map<String, List<String>> headers, String name) {
        for (var entry : headers.entrySet())
            if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue().get(0);
        return null;
    }

    private static Actor actor(String tenant) {
        return new Actor(tenant, "user-1", Set.of(), Map.of());
    }

    /** 真实信封形状：data 内四段；candidateSha256 是本体侧摘要，不是响应体摘要。 */
    private static String packBody() {
        var data = Json.object();
        data.put("candidateSha256", "a".repeat(64));
        data.putArray("conceptCards").addObject().put("name", "学生").put("description", "在籍学生");
        data.putArray("termDictionary").addObject().put("term", "学号").put("definition", "唯一标识");
        data.putArray("relationSummaries").addObject().put("name", "选修");
        data.putArray("exampleQa")
                .addObject()
                .put("question", "如何创建学生？")
                .put("answer", "{\"status\":\"SUCCEEDED\"}");
        var body = Json.object();
        body.put("code", "OK");
        body.put("message", "");
        body.set("data", data);
        return Json.write(body);
    }

    @Test
    void requestCarriesOnlyApiKeyAndReleasePath() throws Exception {
        try (var stub = new Stub()) {
            stub.body.set(packBody());
            var packs =
                    OntologyContextPacks.forApiKey(
                            stub.baseUrl(), "k-123", Clock.systemUTC());
            packs.fetch("tenant-a", SPACE, RELEASE, actor("tenant-a"));
            assertEquals(1, stub.count.get());
            assertEquals("GET", stub.methods.get(0));
            assertEquals("/onto/releases/" + RELEASE + "/context-pack", stub.paths.get(0));
            var sent = stub.headers.get(0);
            assertEquals("k-123", header(sent, "X-API-Key"));
            assertNull(header(sent, "Authorization"));
            assertNull(header(sent, "Cookie"));
            assertNull(header(sent, "Content-Type"));
        }
    }

    @Test
    void serverFailureIsUnavailableAndNeverCached() throws Exception {
        try (var stub = new Stub()) {
            stub.status.set(500);
            var packs =
                    OntologyContextPacks.forApiKey(
                            stub.baseUrl(), "k-123", Clock.systemUTC());
            var first =
                    assertThrows(
                            PlatformException.class,
                            () -> packs.fetch("tenant-a", SPACE, RELEASE, actor("tenant-a")));
            assertEquals(503, first.status());
            assertEquals("CONTEXT_PACK_UNAVAILABLE", first.code());
            assertEquals("本体上下文暂不可用，请稍后重试", first.getMessage());
            // 失败绝不能被缓存：同一 run 重试必须再次请求本体。
            assertThrows(
                    PlatformException.class,
                    () -> packs.fetch("tenant-a", SPACE, RELEASE, actor("tenant-a")));
            assertEquals(2, stub.count.get());
        }
    }

    @Test
    void cacheIsScopedByTenantSpaceAndRelease() throws Exception {
        try (var stub = new Stub()) {
            stub.body.set(packBody());
            var packs =
                    OntologyContextPacks.forApiKey(
                            stub.baseUrl(), "k-123", Clock.systemUTC());
            var first = packs.fetch("tenant-a", SPACE, RELEASE, actor("tenant-a"));
            var second = packs.fetch("tenant-a", SPACE, RELEASE, actor("tenant-a"));
            assertEquals(1, stub.count.get(), "同 tenant|space|release 只应取包一次");
            assertEquals(first.sha256(), second.sha256());
            // 相同 space/releaseId 在另一租户下必须重新取包，缓存不得跨租户遮蔽。
            packs.fetch("tenant-b", SPACE, RELEASE, actor("tenant-b"));
            assertEquals(2, stub.count.get());
            // 同租户不同 release 也不得命中。
            packs.fetch(
                    "tenant-a",
                    SPACE,
                    "33333333-3333-3333-3333-333333333333",
                    actor("tenant-a"));
            assertEquals(3, stub.count.get());
        }
    }
}
