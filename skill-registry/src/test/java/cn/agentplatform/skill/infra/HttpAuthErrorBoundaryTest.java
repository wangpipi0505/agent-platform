package cn.agentplatform.skill.infra;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.TraceContextFilter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class HttpAuthErrorBoundaryTest {
    @Test
    void declaredRetryableCodesCannotOverrideHttpAuthAndUnknownEffectRemainsNonRetryable() throws Exception {
        var status = new AtomicInteger();
        var sends = new AtomicInteger();
        var receivedTrace = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/reject", exchange -> {
            sends.incrementAndGet();
            receivedTrace.set(exchange.getRequestHeaders().getFirst("X-Trace-Id"));
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = Json.write(Map.of("code", "REMOTE_DECLARED_RETRY", "message", "有限远端原因"))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
            finally { exchange.close(); }
        });
        server.start();
        String previous = MDC.get("traceId");
        try (var trace = TraceContextFilter.bind("ex22-http-boundary")) {
            var adapter = new SkillAdapters(mock(ArtifactStore.class), mock(AlgorithmAdapter.class));
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/reject";
            for (int code : List.of(401, 403, 429, 400, 503)) {
                status.set(code);
                var contract = Map.<String, Object>of("endpoint", endpoint, "httpMethod", "POST", "authScheme", "NONE",
                        "timeoutMs", 2000, "execution", Map.of("sideEffect", code == 503),
                        "errorSemantics", Map.of("retryableCodes", List.of("REMOTE_DECLARED_RETRY")));
                var failure = catchThrowableOfType(() -> adapter.http(contract, Map.of(), "ex22-key", 22, "r-ex22", 1),
                        SkillAdapters.Failure.class);
                assertThat(failure).isNotNull();
                var error = failure.error("ex22-http-boundary");
                assertThat(error.get("code")).isEqualTo("REMOTE_DECLARED_RETRY");
                assertThat(error.get("traceId")).isEqualTo("ex22-http-boundary");
                assertThat(receivedTrace.get()).isEqualTo("ex22-http-boundary");
                if (code == 401 || code == 403) {
                    assertThat(failure.semantic).isEqualTo("NO_PERMISSION");
                    assertThat(error.get("category")).isEqualTo("AUTH");
                    assertThat(error.get("retryable")).isEqualTo(false);
                } else if (code == 503) {
                    assertThat(failure.semantic).isEqualTo("RESULT_UNKNOWN");
                    assertThat(failure.unknown).isTrue();
                    assertThat(error.get("retryable")).isEqualTo(false);
                } else {
                    assertThat(failure.semantic).isEqualTo("RETRYABLE_FAILURE");
                    assertThat(error.get("retryable")).isEqualTo(true);
                }
            }
            assertThat(sends).hasValue(5);
        } finally {
            server.stop(0);
            assertThat(MDC.get("traceId")).isEqualTo(previous);
        }
    }
}
