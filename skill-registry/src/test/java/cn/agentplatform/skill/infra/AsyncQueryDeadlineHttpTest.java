package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** R13：直达真实查询传输与响应读取，不依赖PG绑定，验证截止取消而非socket空闲超时。 */
@Timeout(10)
class AsyncQueryDeadlineHttpTest {
    private HttpServer peer;
    private ExecutorService workers;
    private HttpAsyncAdapter adapter;
    private String base;
    private final AtomicInteger hits = new AtomicInteger();
    private final Map<String, Object> contract = Map.of("timeoutMs", 5000, "authScheme", "NONE");

    @BeforeEach
    void start() throws Exception {
        peer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newCachedThreadPool();
        peer.setExecutor(workers);
        peer.createContext("/fast", exchange -> {
            hits.incrementAndGet();
            reply(exchange, 200, "{\"status\":\"DONE\"}");
        });
        peer.createContext("/denied", exchange ->
                reply(exchange, 401, "{\"code\":\"DENIED\",\"message\":\"not allowed\"}"));
        peer.createContext("/paused", exchange -> paused(exchange, 200));
        peer.createContext("/paused-error", exchange -> paused(exchange, 401));
        peer.createContext("/paused-rejected", exchange -> paused(exchange, 409));
        peer.createContext("/headers", exchange -> {
            try {
                Thread.sleep(3000);
                reply(exchange, 200, "{\"status\":\"DONE\"}");
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        });
        peer.start();
        base = "http://127.0.0.1:" + peer.getAddress().getPort();
        adapter = new HttpAsyncAdapter(null);
        // 排除首次类装载/连接初始化的固定开销，不扩大被测期限与断言。
        try (var ignored = response("exchangeStream", "/fast", deadline(5000))) {}
        hits.set(0);
    }

    @AfterEach
    void stop() throws Exception {
        // 基线无自有传输；修复后的真实组件销毁入口负责清理其客户端。
        if (adapter != null) for (var method : HttpAsyncAdapter.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(jakarta.annotation.PreDestroy.class)) {
                method.setAccessible(true);
                method.invoke(adapter);
            }
        }
        if (peer != null) peer.stop(0);
        if (workers != null) {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void 在途成功正文读取必须在总期限中止() {
        assertBounded(() -> response("exchangeStream", "/paused", deadline(1000)));
    }

    @Test
    void 错误正文读取也必须在总期限中止() {
        assertBounded(() -> response("exchangeStream", "/paused-error", deadline(1000)));
    }

    @Test
    void 结果拒绝后的响应关闭不能继续排空慢正文() {
        assertBounded(() -> response("resultEndpointStream", "/paused-rejected", deadline(1000)));
    }

    @Test
    void 尚未收到响应头也必须按总期限取消() {
        assertBounded(() -> response("exchangeStream", "/headers", deadline(1000)));
    }

    @Test
    void 构造后过期的请求在实际发送前拒绝() throws Exception {
        var prepared = adapter.request(contract, base + "/fast", "GET", null, "deadline-test", deadline(80));
        Thread.sleep(120);
        assertThrows(SkillAdapters.Failure.class,
                () -> prepared.exchange((sent, received) -> received.getBody().readAllBytes()));
        assertEquals(0, hits.get(), "过期请求不能到达对端");
    }

    @Test
    void 及时成功与真实认证拒绝保持原语义() throws Exception {
        try (var received = response("exchangeStream", "/fast", deadline(2000))) {
            assertEquals("DONE", received.metadata().path("status").asText());
        }
        var rejected = assertThrows(SkillAdapters.Failure.class,
                () -> response("exchangeStream", "/denied", deadline(2000)));
        assertEquals("NO_PERMISSION", rejected.semantic);
        assertTrue(rejected.getMessage().contains("DENIED"));
    }

    private static long deadline(long millis) {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
    }

    private void assertBounded(ThrowingCall call) {
        long started = System.nanoTime();
        assertThrows(Exception.class, call::run);
        long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
        // T=1000；旧实现最后一字节在650ms、再等完整socket预算，实测约1672ms。
        assertTrue(elapsed < 1400, "应在1000ms总期限附近取消真实I/O，实际=" + elapsed + "ms");
    }

    private AsyncHttpResponse response(String entry, String path, long deadline) throws Exception {
        var method = HttpAsyncAdapter.class.getDeclaredMethod(
                entry, Map.class, String.class, String.class, String.class, Long.class);
        method.setAccessible(true);
        try {
            return (AsyncHttpResponse) method.invoke(adapter, contract, base + path, "deadline-test", "$.result", deadline);
        } catch (InvocationTargetException failed) {
            if (failed.getCause() instanceof Exception cause) throw cause;
            throw failed;
        }
    }

    private static void reply(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (exchange; var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private static void paused(HttpExchange exchange, int status) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, 0);
        try (exchange; var output = exchange.getResponseBody()) {
            output.write("{\"status\":\"D".getBytes(StandardCharsets.UTF_8));
            output.flush();
            Thread.sleep(650);
            output.write('O');
            output.flush();
            Thread.sleep(2000);
            output.write("NE\"}".getBytes(StandardCharsets.UTF_8));
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch (IOException disconnected) {
            // 客户端在截止时取消后，对端写入失败是预期收尾。
        }
    }

    private interface ThrowingCall { void run() throws Exception; }
}
