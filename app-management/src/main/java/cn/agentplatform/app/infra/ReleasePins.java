package cn.agentplatform.app.infra;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.SecretVault;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * 发布完成后把版本钉到本体服务的发布绑定表，结果作为 commit 返回体的 {@code pin} 键。
 *
 * <p>调用点固定在发布事务之外：本类会发 HTTP，而发布事务持应用行锁期间禁止网络调用。因此绑定失败
 * 绝不回滚已提交的发布：默认 fail-open 返回 FAILED 供调用方观测；仅当 {@code agent.pin.fail-closed=true}
 * 时抛 502 拒绝本次响应（版本已落库，重放同一请求号会再次尝试绑定）。
 *
 * <p>候选里没有 {@code config.ontology} 时应用不参与本体，记 SKIPPED 且不发请求，使未接入本体的
 * 部署无需额外配置；未配置 base-url / api-key-ref 或显式关闭时记 DISABLED，同样零网络副作用。
 *
 * <p>密钥只通过 {@link SecretVault} 引用解析（{@code vault:}/{@code env:}），既不出现在返回值也不出现在
 * 异常信息里；请求只带 {@code X-API-Key}，不带平台用户令牌 Authorization，避免把用户凭据交给另一个服务。
 */
@Component
public class ReleasePins {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_BYTES = 64 * 1024;
    private static final int MAX_REASON = 200;
    private static final String PATH = "/onto/internal/release-bindings";

    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
    private final String baseUrl;
    private final String apiKeyRef;
    private final boolean enabled;
    private final boolean failClosed;
    private final String environment;

    public ReleasePins(
            @Value("${agent.ontology.base-url:}") String baseUrl,
            @Value("${agent.ontology.api-key-ref:}") String apiKeyRef,
            @Value("${agent.pin.enabled:true}") boolean enabled,
            @Value("${agent.pin.fail-closed:false}") boolean failClosed,
            @Value("${agent.pin.environment:production}") String environment) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKeyRef = apiKeyRef == null ? "" : apiKeyRef.trim();
        this.enabled = enabled;
        this.failClosed = failClosed;
        this.environment =
                environment == null || environment.isBlank() ? "production" : environment.trim();
    }

    public Map<String, Object> pin(Actor actor, String appCode, Map<String, Object> candidate) {
        if (!enabled) return disabled("agent.pin.enabled=false");
        if (baseUrl.isEmpty()) return disabled("未配置 agent.ontology.base-url");
        if (apiKeyRef.isEmpty()) return disabled("未配置 agent.ontology.api-key-ref");
        if (actor == null) return failure("缺少租户上下文");

        var ontology = configOntology(candidate);
        if (ontology == null || ontology.isEmpty()) return skipped("未声明 config.ontology");
        String space = text(ontology, "space");
        String release = text(ontology, "releaseId");
        if (space.isEmpty() || release.isEmpty())
            return skipped("config.ontology 缺少 space/releaseId");
        if (!isUuid(space)) return skipped("config.ontology.space 不是 UUID");
        if (!isUuid(release)) return skipped("config.ontology.releaseId 不是 UUID");

        String apiKey;
        try {
            apiKey = SecretVault.resolve(apiKeyRef, actor);
        } catch (RuntimeException e) {
            return failure("解析发布绑定密钥失败: " + describe(e));
        }
        if (apiKey == null || apiKey.isBlank()) return failure("发布绑定密钥为空");

        var body =
                Map.of(
                        "spaceId", space,
                        "platformTenant", actor.tenantId(),
                        "appCode", appCode,
                        "environment", environment,
                        "releaseId", release);
        try {
            var request =
                    HttpRequest.newBuilder(
                                    URI.create(baseUrl.replaceAll("/+$", "") + PATH))
                            .timeout(REQUEST_TIMEOUT)
                            .header("Content-Type", "application/json")
                            .header("X-API-Key", apiKey)
                            .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                            .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                byte[] bytes = in.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) return failure("发布绑定响应超过 64KiB");
                if (response.statusCode() / 100 != 2)
                    return failure("本体服务返回 HTTP " + response.statusCode());
                return pinned(bytes);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure("调用本体服务被中断");
        } catch (IOException | RuntimeException e) {
            return failure("调用本体服务失败: " + describe(e));
        }
    }

    /**
     * 2xx 只代表远程已接受绑定：响应体解析失败也保持 PINNED，不把已生效的绑定说成失败。
     * 远程返回 {code,message,data} 信封时只取 data，兼容直接返回对象的实现。
     */
    private Map<String, Object> pinned(byte[] bytes) {
        var result = new LinkedHashMap<String, Object>();
        result.put("state", "PINNED");
        try {
            var root = Json.read(new String(bytes, StandardCharsets.UTF_8));
            var data = root.path("data").isObject() ? root.path("data") : root;
            if (data.hasNonNull("bindingId")) result.put("bindingId", data.get("bindingId").asText());
            if (data.has("changed")) result.put("changed", data.get("changed").asBoolean());
        } catch (RuntimeException e) {
            result.put("reason", "绑定已生效但响应体不可解析");
        }
        return result;
    }

    private Map<String, Object> skipped(String reason) {
        var result = new LinkedHashMap<String, Object>();
        result.put("state", "SKIPPED");
        result.put("reason", reason);
        return result;
    }

    private Map<String, Object> disabled(String reason) {
        var result = new LinkedHashMap<String, Object>();
        result.put("state", "DISABLED");
        result.put("reason", reason);
        return result;
    }

    private Map<String, Object> failure(String reason) {
        String trimmed = reason.length() > MAX_REASON ? reason.substring(0, MAX_REASON) : reason;
        if (failClosed) throw new PlatformException(502, "PIN_FAILED", "发布绑定失败: " + trimmed);
        var result = new LinkedHashMap<String, Object>();
        result.put("state", "FAILED");
        result.put("reason", trimmed);
        return result;
    }

    private static Map<String, Object> configOntology(Map<String, Object> candidate) {
        if (candidate == null) return null;
        if (!(candidate.get("config") instanceof Map<?, ?> config)) return null;
        if (!(config.get("ontology") instanceof Map<?, ?> ontology)) return null;
        var copy = new LinkedHashMap<String, Object>();
        ontology.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }

    private static String text(Map<String, Object> map, String field) {
        var value = map.get(field);
        return value == null ? "" : value.toString().trim();
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 只保留异常类型，不拼接可能含地址/密钥的原始消息；密钥只在请求头里使用。 */
    private static String describe(Exception e) {
        return e.getClass().getSimpleName();
    }
}
