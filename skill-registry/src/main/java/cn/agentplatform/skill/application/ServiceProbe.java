package cn.agentplatform.skill.application;

import cn.agentplatform.common.PlatformException;
import cn.agentplatform.skill.domain.SkillRules;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 服务接入卡片的有界可达性探测。
 *
 * <p>只发 HEAD、不跟随重定向、不读取响应体，因此不会触发业务写操作；结果是“端点可达性”，不是供应商深度健康。
 * 探测地址来自已保存的技能定义，仍按管理员输入处理：只允许 http/https，拒绝云元数据常用的链路本地地址。
 * 私网/回环地址保留，因为本地算法服务是受支持的接入形态。
 */
public final class ServiceProbe {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(4);

    private ServiceProbe() {}

    public static Map<String, Object> probe(String endpoint, String declaredMethod) {
        return probe(endpoint, declaredMethod, false);
    }

    /**
     * 管理台健康卡探测服务根地址，而不是 POST 业务端点。
     *
     * <p>Spring MVC 对未显式支持的 HEAD 可能返回 500，直接探业务端点会把健康服务误报为降级；
     * 根地址只用于判断进程/HTTP 层可达，业务可用性仍由连接测试证明。
     */
    public static Map<String, Object> probeOrigin(String endpoint, String declaredMethod) {
        return probe(endpoint, declaredMethod, true);
    }

    private static Map<String, Object> probe(
            String endpoint, String declaredMethod, boolean originOnly) {
        URI uri;
        try {
            uri = SkillRules.endpoint(endpoint);
        } catch (IllegalArgumentException e) {
            throw new PlatformException(400, "SERVICE_ENDPOINT_INVALID", "服务端点必须是 HTTP(S) 地址");
        }
        assertNotMetadata(uri);
        URI probeUri = originOnly ? origin(uri) : uri;

        long started = System.nanoTime();
        var result = new LinkedHashMap<String, Object>();
        result.put("endpoint", uri.toString());
        result.put("probeUrl", probeUri.toString());
        result.put("declaredMethod", declaredMethod == null ? "" : declaredMethod);
        result.put("probeMethod", "HEAD");
        result.put("checkedAt", Instant.now().toString());

        try {
            HttpClient client =
                    HttpClient.newBuilder()
                            .connectTimeout(CONNECT_TIMEOUT)
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build();
            HttpRequest request =
                    HttpRequest.newBuilder(probeUri)
                            .timeout(REQUEST_TIMEOUT)
                            .method("HEAD", HttpRequest.BodyPublishers.noBody())
                            .header("User-Agent", "agent-platform-service-probe")
                            .build();
            HttpResponse<Void> response =
                    client.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            result.put("httpStatus", status);
            result.put("latencyMs", elapsedMs(started));
            if (status >= 500) {
                result.put("status", "DEGRADED");
                result.put("reason", "服务返回 " + status + "，端点可达但当前不健康");
            } else if (status == 405) {
                result.put("status", "REACHABLE");
                result.put("reason", "端点可达；服务不接受 HEAD，未执行 GET/POST");
            } else if (status == 401 || status == 403) {
                result.put("status", "REACHABLE");
                result.put("reason", "端点可达；返回 " + status + "，需要鉴权");
            } else {
                result.put("status", "REACHABLE");
                result.put("reason", "端点可达（HTTP " + status + "）");
            }
        } catch (HttpTimeoutException e) {
            result.put("status", "UNREACHABLE");
            result.put("latencyMs", elapsedMs(started));
            result.put("reason", "探测超时");
        } catch (IOException e) {
            result.put("status", "UNREACHABLE");
            result.put("latencyMs", elapsedMs(started));
            result.put("reason", "无法建立连接");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.put("status", "UNREACHABLE");
            result.put("latencyMs", elapsedMs(started));
            result.put("reason", "探测被中断");
        }
        return result;
    }

    private static URI origin(URI uri) {
        try {
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), "/", null, null);
        } catch (java.net.URISyntaxException e) {
            throw new PlatformException(400, "SERVICE_ENDPOINT_INVALID", "服务端点无法解析");
        }
    }

    private static void assertNotMetadata(URI uri) {
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isLinkLocalAddress()
                        || address.isAnyLocalAddress()
                        || address.isMulticastAddress()) {
                    throw new PlatformException(
                            400, "SERVICE_ENDPOINT_BLOCKED", "服务端点不得指向链路本地或组播地址");
                }
            }
        } catch (IOException e) {
            throw new PlatformException(400, "SERVICE_ENDPOINT_INVALID", "服务端点无法解析");
        }
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
