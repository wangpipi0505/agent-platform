package cn.agentplatform.common.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.agentplatform.common.PlatformException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * EX-40/M2 的前置：授权服务的"否定回答"必须与"服务不可用"区分。
 *
 * <p>改动前 {@code BoundedIamHttp} 把所有非 200 都折成 {@code 503 IAM_AUTHORITY_UNAVAILABLE}，
 * 于是真实的 403 判权否决在调用方看起来是依赖故障——既可被重试放大，也会在错误面丢失"谁拒绝了什么"。
 * 这里钉住四件事：①400/401/403/409 按原状态码透出且码稳定；②5xx 与网络故障仍是 503；
 * ③响应体不进入错误消息（避免服务端自由文本被当成用户可见文案）；④200 的行为不变。
 */
class BoundedIamHttpErrorTest {
    private HttpServer server;
    private int status;
    private String body;
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            calls.incrementAndGet();
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private BoundedIamHttp http() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        var authority = new AuthorityProperties.Authority(
                base, base + "/jwks", base + "/token", base + "/decision",
                "skill", "env:AGENT_SECRET_IAM_SKILL_CLIENT", "RS256", "urn:agent-platform:service:skill",
                60, 5, 1_000, 2_000);
        var config = new AuthorityProperties("iam", "skill", null, authority, null, null, null, false, null);
        return new BoundedIamHttp(config, true);
    }

    private void expect(int httpStatus, int expectedStatus, String expectedCode) {
        status = httpStatus;
        body = "{\"code\":\"SECRET-INTERNAL\",\"message\":\"服务端内部细节不该外泄\"}";
        try (var http = http()) {
            assertThatThrownBy(() -> http.postJsonAuthenticated(
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/token"),
                            Map.of("operation", "client"), "Basic dGVzdDp0ZXN0"))
                    .isInstanceOfSatisfying(PlatformException.class, failure -> {
                        assertThat(failure.status()).as("status for " + httpStatus).isEqualTo(expectedStatus);
                        assertThat(failure.code()).as("code for " + httpStatus).isEqualTo(expectedCode);
                        assertThat(failure.getMessage())
                                .as("server body must not leak")
                                .doesNotContain("SECRET-INTERNAL")
                                .doesNotContain("服务端内部细节不该外泄");
                    });
        }
        assertThat(calls).hasValue(1);
    }

    @Test
    void rejectionStatusesSurfaceAsThemselves() {
        expect(400, 400, "IAM_AUTHORITY_REJECTED");
    }

    @Test
    void unauthorizedIsNotDependencyFailure() {
        expect(401, 401, "IAM_AUTHORITY_UNAUTHORIZED");
    }

    @Test
    void forbiddenIsNotDependencyFailure() {
        expect(403, 403, "IAM_AUTHORITY_FORBIDDEN");
    }

    @Test
    void conflictIsNotDependencyFailure() {
        expect(409, 409, "IAM_AUTHORITY_CONFLICT");
    }

    @Test
    void serverErrorStaysUnavailable() {
        status = 500;
        body = "{}";
        try (var http = http()) {
            assertThatThrownBy(() -> http.postJsonAuthenticated(
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/token"),
                            Map.of("operation", "client"), "Basic dGVzdDp0ZXN0"))
                    .isInstanceOfSatisfying(PlatformException.class, failure -> {
                        assertThat(failure.status()).isEqualTo(503);
                        assertThat(failure.code()).isEqualTo("IAM_AUTHORITY_UNAVAILABLE");
                    });
        }
    }

    @Test
    void networkFailureStaysUnavailable() throws Exception {
        var authority = new AuthorityProperties.Authority(
                "http://127.0.0.1:1", "http://127.0.0.1:1/jwks", "http://127.0.0.1:1/token",
                "http://127.0.0.1:1/decision", "skill", "env:AGENT_SECRET_IAM_SKILL_CLIENT", "RS256",
                "urn:agent-platform:service:skill", 60, 5, 300, 300);
        var config = new AuthorityProperties("iam", "skill", null, authority, null, null, null, false, null);
        try (var http = new BoundedIamHttp(config, true)) {
            assertThatThrownBy(() -> http.postJsonAuthenticated(
                            URI.create("http://127.0.0.1:1/token"), Map.of(), "Basic dGVzdDp0ZXN0"))
                    .isInstanceOfSatisfying(PlatformException.class,
                            failure -> assertThat(failure.code()).isEqualTo("IAM_AUTHORITY_UNAVAILABLE"));
        }
    }

    @Test
    void successPathStillReturnsBody() {
        status = 200;
        body = "{\"token\":\"abc\"}";
        try (var http = http()) {
            byte[] bytes = http.postJsonAuthenticated(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/token"),
                    Map.of("operation", "client"), "Basic dGVzdDp0ZXN0");
            assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo(body);
        }
    }
}
