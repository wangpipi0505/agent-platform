package cn.agentplatform.skill.application;

import cn.agentplatform.common.PlatformException;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceProbeTest {

    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                });
        server.createContext(
                "/ok",
                exchange -> {
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                });
        server.createContext(
                "/fail",
                exchange -> {
                    byte[] body = "unavailable".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(503, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.createContext(
                "/method",
                exchange -> {
                    byte[] body = "method not allowed".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(405, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void reportsReachableEndpoint() {
        Map<String, Object> result = ServiceProbe.probe(base + "/ok", "POST");

        assertThat(result).containsEntry("status", "REACHABLE").containsEntry("httpStatus", 204);
        assertThat(result).containsEntry("probeMethod", "HEAD").containsEntry("declaredMethod", "POST");
        assertThat(result.get("checkedAt")).isNotNull();
        assertThat((Number) result.get("latencyMs")).isNotNull();
    }

    @Test
    void probesOriginInsteadOfBusinessEndpoint() {
        Map<String, Object> result = ServiceProbe.probeOrigin(base + "/ok", "POST");

        assertThat(result).containsEntry("status", "REACHABLE").containsEntry("httpStatus", 204);
        assertThat(result).containsEntry("probeUrl", base + "/");
    }

    @Test
    void reportsDegradedForServerError() {
        Map<String, Object> result = ServiceProbe.probe(base + "/fail", "POST");

        assertThat(result).containsEntry("status", "DEGRADED").containsEntry("httpStatus", 503);
        assertThat(result.get("reason").toString()).contains("端点可达");
    }

    @Test
    void reportsReachableWhenHeadIsNotAllowed() {
        Map<String, Object> result = ServiceProbe.probe(base + "/method", "POST");

        assertThat(result).containsEntry("status", "REACHABLE").containsEntry("httpStatus", 405);
        assertThat(result.get("reason").toString()).contains("不接受 HEAD");
    }

    @Test
    void rejectsMetadataAndNonHttpEndpoints() {
        assertThatThrownBy(() -> ServiceProbe.probe("http://169.254.169.254/latest", "GET"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("链路本地");
        assertThatThrownBy(() -> ServiceProbe.probe("http://10.0.0.1@/x", "GET"))
                .isInstanceOf(PlatformException.class);
        assertThatThrownBy(() -> ServiceProbe.probe("ftp://127.0.0.1/x", "GET"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("HTTP(S)");
    }
}
