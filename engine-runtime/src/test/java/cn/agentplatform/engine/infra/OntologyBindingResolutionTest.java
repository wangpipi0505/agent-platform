package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 发布绑定（release-binding）查询的协议边界。
 *
 * <p>与取包链路共用同一个 base-url 与同一个 {@code vault:} 密钥引用，但缓存窗口不同：绑定是"当前应当使用哪个
 * releaseId"的指针，30 秒就足以避免同一波运行重复查询，同时保证重新发布后不必等 5 分钟。负结果（404 与任何故障）
 * 一律不缓存，否则一次瞬时 5xx 会把"没有绑定"钉死在进程里。
 */
class OntologyBindingResolutionTest {
    private static final String TENANT = "tenant-a";
    private static final String APP = "app_demo";
    private static final String ENV = "production";
    private static final String RELEASE = "22222222-2222-2222-2222-222222222222";
    private static final String OTHER_RELEASE = "33333333-3333-3333-3333-333333333333";
    private static final String BINDING = "44444444-4444-4444-4444-444444444444";

    /** 可推进时钟：30 秒 TTL 的边界不靠 sleep 等待，测试必须确定性。 */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-28T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }
    }

    /** JDK 桩：记录次数、路径、原始查询串与请求头；响应码/体可逐用例替换。 */
    private static final class Stub implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger count = new AtomicInteger();
        private final List<String> paths = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final List<String> queries = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final List<Map<String, List<String>>> headers =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        private final AtomicReference<String> body = new AtomicReference<>("{}");
        private final AtomicInteger status = new AtomicInteger(200);

        private Stub() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext(
                    "/onto/",
                    exchange -> {
                        count.incrementAndGet();
                        paths.add(exchange.getRequestURI().getPath());
                        queries.add(exchange.getRequestURI().getRawQuery());
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

    private static String bindingBody(String releaseId, String bindingId, String status) {
        var data = Json.object();
        data.put("releaseId", releaseId);
        data.put("bindingId", bindingId);
        data.put("status", status);
        data.put("platformTenant", TENANT);
        data.put("appCode", APP);
        data.put("environment", ENV);
        var envelope = Json.object();
        envelope.put("code", "OK");
        envelope.put("message", "");
        envelope.set("data", data);
        return Json.write(envelope);
    }

    @Test
    void bindingQueryCarriesOnlyApiKeyAndEncodesParameters() throws Exception {
        try (var stub = new Stub()) {
            stub.body.set(bindingBody(RELEASE, BINDING, "ACTIVE"));
            var packs = OntologyContextPacks.forApiKey(stub.baseUrl(), "k-9", Clock.systemUTC());
            // appCode 里带分隔符与空格：绑定查询必须编码，不能让应用编码改写查询结构。
            var binding = packs.resolveBinding(TENANT, "app demo&x=1", ENV, actor(TENANT));

            assertEquals(RELEASE, binding.releaseId());
            assertEquals(BINDING, binding.bindingId());
            assertEquals(1, stub.count.get());
            assertEquals("/onto/internal/release-bindings", stub.paths.get(0));
            assertEquals(
                    "platformTenant=tenant-a&appCode=app+demo%26x%3D1&environment=production",
                    stub.queries.get(0));
            var sent = stub.headers.get(0);
            assertEquals("k-9", header(sent, "X-API-Key"));
            assertNull(header(sent, "Authorization"));
            assertNull(header(sent, "Cookie"));
            assertNull(header(sent, "Content-Type"));
        }
    }

    @Test
    void bindingResultIsCachedForThirtySeconds() throws Exception {
        try (var stub = new Stub()) {
            stub.body.set(bindingBody(RELEASE, BINDING, "ACTIVE"));
            var clock = new MutableClock();
            var packs = OntologyContextPacks.forApiKey(stub.baseUrl(), "k-9", clock);

            packs.resolveBinding(TENANT, APP, ENV, actor(TENANT));
            packs.resolveBinding(TENANT, APP, ENV, actor(TENANT));
            assertEquals(1, stub.count.get(), "同一 tenant|appCode|environment 只应查询一次");

            clock.advance(Duration.ofSeconds(29));
            packs.resolveBinding(TENANT, APP, ENV, actor(TENANT));
            assertEquals(1, stub.count.get(), "TTL 内必须命中缓存");

            clock.advance(Duration.ofSeconds(2));
            packs.resolveBinding(TENANT, APP, ENV, actor(TENANT));
            assertEquals(2, stub.count.get(), "超过 30 秒后必须重新解析，重新发布不能被旧指针挡住");

            packs.resolveBinding(TENANT, "app_other", ENV, actor(TENANT));
            assertEquals(3, stub.count.get(), "不同 appCode 不得互相命中");
            packs.resolveBinding(TENANT, APP, "staging", actor(TENANT));
            assertEquals(4, stub.count.get(), "不同环境不得互相命中");
        }
    }

    @Test
    void bindingNotFoundIsCodedAndNeverCached() throws Exception {
        try (var stub = new Stub()) {
            stub.status.set(404);
            stub.body.set("{\"code\":\"BINDING_NOT_FOUND\",\"message\":\"没有生效的发布绑定\"}");
            var packs = OntologyContextPacks.forApiKey(stub.baseUrl(), "k-9", Clock.systemUTC());

            var first =
                    assertThrows(
                            PlatformException.class,
                            () -> packs.resolveBinding(TENANT, APP, ENV, actor(TENANT)));
            // 404 不是故障，是"该应用当前没有生效绑定"的事实；用独立 code 让应用层决定 fail-open。
            assertEquals(404, first.status());
            assertEquals("BINDING_NOT_FOUND", first.code());

            assertThrows(
                    PlatformException.class,
                    () -> packs.resolveBinding(TENANT, APP, ENV, actor(TENANT)));
            assertEquals(2, stub.count.get(), "404 不得被缓存，否则刚发布的绑定 30 秒内不可见");
        }
    }

    @Test
    void gatewayFourOhFourWithoutBindingCodeIsUnavailable() throws Exception {
        try (var stub = new Stub()) {
            stub.status.set(404);
            // 路由错配/网关返回的 404 不是"没有绑定"：若按不存在处理，错配置会静默退化成不注入。
            stub.body.set("<html>404 Not Found</html>");
            var packs = OntologyContextPacks.forApiKey(stub.baseUrl(), "k-9", Clock.systemUTC());

            var failure =
                    assertThrows(
                            PlatformException.class,
                            () -> packs.resolveBinding(TENANT, APP, ENV, actor(TENANT)));
            assertEquals(503, failure.status());
            assertEquals("CONTEXT_PACK_UNAVAILABLE", failure.code());
            assertTrue(failure.getMessage().contains("绑定"), failure.getMessage());
        }
    }

    @Test
    void bindingServerFailureAndConnectionRefusedAreUnavailable() throws Exception {
        try (var stub = new Stub()) {
            stub.status.set(500);
            stub.body.set("{\"code\":\"INTERNAL\",\"message\":\"boom\"}");
            var packs = OntologyContextPacks.forApiKey(stub.baseUrl(), "k-9", Clock.systemUTC());

            var failure =
                    assertThrows(
                            PlatformException.class,
                            () -> packs.resolveBinding(TENANT, APP, ENV, actor(TENANT)));
            assertEquals(503, failure.status());
            assertEquals("CONTEXT_PACK_UNAVAILABLE", failure.code());
            assertTrue(failure.getMessage().contains("绑定"), failure.getMessage());

            assertThrows(
                    PlatformException.class,
                    () -> packs.resolveBinding(TENANT, APP, ENV, actor(TENANT)));
            assertEquals(2, stub.count.get(), "5xx 不得被缓存");
        }
        String deadUrl;
        try (var closure = new Stub()) {
            deadUrl = closure.baseUrl();
        }
        var refused = OntologyContextPacks.forApiKey(deadUrl, "k-9", Clock.systemUTC());
        var failure =
                assertThrows(
                        PlatformException.class,
                        () -> refused.resolveBinding(TENANT, APP, ENV, actor(TENANT)));
        assertEquals(503, failure.status());
        assertEquals("CONTEXT_PACK_UNAVAILABLE", failure.code());
        assertTrue(failure.getMessage().contains("绑定"), failure.getMessage());
    }

    @Test
    void malformedBindingPayloadIsUnavailable() throws Exception {
        try (var stub = new Stub()) {
            var packs = OntologyContextPacks.forApiKey(stub.baseUrl(), "k-9", Clock.systemUTC());

            stub.body.set(bindingBody("not-a-uuid", BINDING, "ACTIVE"));
            var malformed =
                    assertThrows(
                            PlatformException.class,
                            () -> packs.resolveBinding(TENANT, APP, ENV, actor(TENANT)));
            assertEquals(503, malformed.status());
            assertEquals("CONTEXT_PACK_UNAVAILABLE", malformed.code());

            // 非 ACTIVE 绑定不能用：宁可报不可用，也不能把已下线版本塞进运行。
            stub.body.set(bindingBody(OTHER_RELEASE, BINDING, "SUSPENDED"));
            var notActive =
                    assertThrows(
                            PlatformException.class,
                            () -> packs.resolveBinding(TENANT, APP, ENV, actor(TENANT)));
            assertEquals(503, notActive.status());

            stub.body.set("{\"code\":\"OK\",\"data\":{");
            assertThrows(
                    PlatformException.class,
                    () -> packs.resolveBinding(TENANT, APP, ENV, actor(TENANT)));
        }
    }
}
