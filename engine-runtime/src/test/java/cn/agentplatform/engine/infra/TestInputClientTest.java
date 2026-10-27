package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 提升是用户可见失败路径：monitor 的稳定业务码（如断言契约违规）必须原样抵达调用方，
 * 只有非统一响应/服务不可用才归一到依赖错误。桩服务不启动 Spring，避免新增上下文。
 */
class TestInputClientTest {
    private static HttpServer monitor;
    private static TestInputClient client;
    private static final AtomicReference<Reply> reply = new AtomicReference<>();
    private static final Actor ACTOR =
            new Actor("tenant-client-test", "client-test", Set.of("EDITOR"), Map.of());

    record Reply(int status, String body, String contentType) {}

    @BeforeAll
    static void start() throws Exception {
        System.setProperty(
                "agent.jwt.secret", "TEST-ONLY-jwt-secret-for-test-input-client-0123456789");
        SecurityConfiguration.bindDeployment(
                new MockEnvironment()
                        .withProperty("agent.security.mode", "dev-hmac")
                        .withProperty("agent.security.service-id", "engine")
                        .withProperty("agent.dev-auth", "true")
                        .withProperty("server.address", "127.0.0.1"));
        monitor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        monitor.createContext(
                "/internal/datasets/promotions",
                exchange -> {
                    Reply r = reply.get();
                    byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", r.contentType());
                    exchange.sendResponseHeaders(r.status(), bytes.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                });
        monitor.start();
        client =
                new TestInputClient(
                        "http://127.0.0.1:1",
                        "http://127.0.0.1:" + monitor.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        if (monitor != null) monitor.stop(0);
    }

    @Test
    void propagatesMonitorAssertionBusinessCodeAndMessage() {
        String code = "EVALUATION_ASSERTION_SAFETY_CONSTRAINT";
        String message = "安全用例只允许严格相等或Schema校验";
        reply.set(
                new Reply(
                        400,
                        Json.write(new ApiResponse<>(code, message, null, null)),
                        "application/json"));
        var thrown =
                assertThrows(
                        PlatformException.class,
                        () ->
                                client.promote(
                                        Json.tree(Map.of("cases", List.of(Map.of("id", "c1")))),
                                        ACTOR));
        assertEquals(400, thrown.status());
        assertEquals(code, thrown.code());
        assertEquals(message, thrown.getMessage());
    }

    @Test
    void keepsDependencyErrorForServerFailure() {
        reply.set(
                new Reply(
                        500,
                        Json.write(
                                new ApiResponse<>(
                                        "EVALUATION_ASSERTION_INVALID", "内部细节不对外", null, null)),
                        "application/json"));
        var thrown =
                assertThrows(
                        PlatformException.class,
                        () ->
                                client.promote(
                                        Json.tree(Map.of("cases", List.of(Map.of("id", "c1")))),
                                        ACTOR));
        assertEquals(502, thrown.status());
        assertEquals("TEST_INPUT_DEPENDENCY", thrown.code());
    }

    @Test
    void fallsBackToDependencyRejectionWhenBodyIsNotApiResponse() {
        reply.set(new Reply(400, "bad gateway", "text/plain"));
        var thrown =
                assertThrows(
                        PlatformException.class,
                        () ->
                                client.promote(
                                        Json.tree(Map.of("cases", List.of(Map.of("id", "c1")))),
                                        ACTOR));
        assertEquals(400, thrown.status());
        assertEquals("TEST_INPUT_DEPENDENCY_REJECTED", thrown.code());
    }
}
