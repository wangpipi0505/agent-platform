package cn.agentplatform.app.application;

import cn.agentplatform.app.infra.ConnectionProfileRepository;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 只读健康探针（INT-02 子步3，契约 §11.4）。
 *
 * 边界：
 * 1) 只做一次 GET，不发送凭据、不带请求体，不触发任何业务写入；
 * 2) 结果独立成端点，**不进 resolve 回执**——运行期解析必须与"探针此刻是否可达"解耦，
 *    否则一次抖动就会让本可执行的调用被拒；
 * 3) 远端失败不抛异常，统一折叠成 status=FAILED + 有界原因，避免把内网拓扑与原始报错漏给调用方。
 */
@Service
public class ConnectionHealth {

    private static final int DEFAULT_TIMEOUT_MS = 2000;
    private static final int MIN_TIMEOUT_MS = 100;
    private static final int MAX_TIMEOUT_MS = 10000;

    private final ConnectionProfileRepository profiles;
    private final HttpClient client;

    public ConnectionHealth(ConnectionProfileRepository profiles) {
        this.profiles = profiles;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(DEFAULT_TIMEOUT_MS))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public Map<String, Object> probe(String profileId, int profileRev, String environment) {
        Actor actor = Actor.current();
        var head = profiles
                .profile(actor, profileId)
                .orElseThrow(() -> new PlatformException(404, "CONFIG_REF_NOT_FOUND", "连接档案不存在: " + profileId));
        String status = Objects.toString(head.get("status"), "");
        if ("DISABLED".equals(status))
            throw new PlatformException(400, "CONFIG_RELEASE_NOT_ACTIVE", "连接档案已停用，不做健康探测: " + profileId);
        if (!"APPROVED".equals(status))
            throw new PlatformException(400, "CONFIG_STATE_TRANSITION_INVALID", "只有已批准的连接档案才能做健康探测");
        int current = ((Number) head.get("currentRev")).intValue();
        if (profileRev != current)
            throw new PlatformException(400, "CONFIG_STATE_TRANSITION_INVALID",
                    "只能探测当前已批准修订：当前 " + current + "，请求 " + profileRev);

        var bindings = profiles.pinnedBindingRevisions(actor, profileId, profileRev).stream()
                .filter(b -> environment == null || environment.equals(b.get("environment")))
                .toList();
        if (bindings.isEmpty())
            throw new PlatformException(404, "CONFIG_REF_NOT_FOUND",
                    "没有可探测的已批准环境绑定: " + profileId + "@" + profileRev
                            + (environment == null ? "" : " / " + environment));

        Map<String, Object> binding = bindings.get(0);
        String base = Objects.toString(binding.get("baseEndpoint"), "");
        Map<String, Object> health = asMap(binding.get("healthPolicy"));
        String path = Objects.toString(health.get("path"), "");
        int timeout = timeoutMs(health.get("timeoutMs"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profileId", profileId);
        out.put("profileRev", profileRev);
        out.put("bindingId", binding.get("bindingId"));
        out.put("bindingRev", binding.get("bindingRev"));
        out.put("environment", binding.get("environment"));
        out.put("checkedAt", Instant.now().toString());
        out.put("timeoutMs", timeout);
        if (!path.startsWith("/")) {
            // 声明缺失不当成"健康"：直接给出不可判定，运行期不因此放行也不因此误拒。
            out.put("status", "UNKNOWN");
            out.put("detail", "环境绑定未声明 healthPolicy.path，无法探测");
            return out;
        }
        String target = trimTrailingSlash(base) + path;
        out.put("target", target);
        long started = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(target))
                    .timeout(Duration.ofMillis(timeout))
                    .GET()
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            out.put("httpStatus", response.statusCode());
            out.put("latencyMs", (System.nanoTime() - started) / 1_000_000);
            boolean ok = response.statusCode() >= 200 && response.statusCode() < 400;
            out.put("status", ok ? "OK" : "FAILED");
            if (!ok) out.put("detail", "健康端点返回 " + response.statusCode());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            out.put("status", "FAILED");
            out.put("latencyMs", (System.nanoTime() - started) / 1_000_000);
            out.put("detail", "健康探测被中断");
        } catch (Exception failed) {
            out.put("status", "FAILED");
            out.put("latencyMs", (System.nanoTime() - started) / 1_000_000);
            // 只给异常类型，不给原始消息：内网地址、代理细节不外泄。
            out.put("detail", "健康探测失败: " + failed.getClass().getSimpleName());
        }
        return out;
    }

    private static int timeoutMs(Object declared) {
        if (declared instanceof Number n) {
            int value = n.intValue();
            return Math.max(MIN_TIMEOUT_MS, Math.min(MAX_TIMEOUT_MS, value));
        }
        return DEFAULT_TIMEOUT_MS;
    }

    private static String trimTrailingSlash(String base) {
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static Map<String, Object> asMap(Object raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> m) m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    /** 供测试与调用方判断探针结果是否可用于展示（不用于放行判断）。 */
    public static boolean reachable(Map<String, Object> probe) {
        return "OK".equals(Objects.toString(probe.get("status"), ""));
    }

    static List<String> environments(Actor actor, ConnectionProfileRepository profiles, String profileId,
            int profileRev) {
        return profiles.pinnedBindingRevisions(actor, profileId, profileRev).stream()
                .map(b -> Objects.toString(b.get("environment"), ""))
                .toList();
    }

    static String canonical(Map<String, Object> probe) {
        return Json.write(probe);
    }
}
