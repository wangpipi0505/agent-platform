package cn.agentplatform.engine.infra;

import cn.agentplatform.common.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 本体上下文包客户端。取包只在运行冻结阶段发生；ContextAssembler 只读已写入 config 的文本，
 * 因此这里的任何 IO 都不会进入模型输入哈希的重算路径。
 *
 * <p>安全边界：只向本体发 {@code X-API-Key}，密钥要么来自 {@code vault:} 引用（按运行所属租户解析），
 * 要么来自测试工厂；绝不接受调用方传入的密钥，也不跟随重定向，避免把密钥带到第三方主机。
 */
@Component
public class OntologyContextPacks {
    /** body 是已剥掉响应信封的包对象；sha256 是响应体的 UTF-8 摘要（审计用，不参与模型请求）。 */
    public record Pack(JsonNode body, String sha256) {}

    /** 发布绑定解析结果：releaseId 是运行期应当使用的版本，bindingId 只用于审计关联。 */
    public record Binding(String releaseId, String bindingId) {}

    private static final int MAX_BYTES = 256 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration TTL = Duration.ofMinutes(5);
    /**
     * 绑定是"当前该用哪个版本"的指针：30 秒足以合并同一波运行的重复查询，
     * 又让重新发布不必等满取包的 5 分钟窗口。
     */
    private static final Duration BINDING_TTL = Duration.ofSeconds(30);
    private static final int CAPACITY = 64;
    private static final String UNAVAILABLE = "本体上下文暂不可用，请稍后重试";
    private static final String BINDING_UNAVAILABLE = "本体发布绑定暂不可用，请稍后重试";
    /** 404 且带此码才是"没有生效绑定"；其它 404 是路由/网关问题，不能当成没有绑定。 */
    public static final String BINDING_NOT_FOUND = "BINDING_NOT_FOUND";
    /** 绑定与包各自校验形状：坏 releaseId 或非 ACTIVE 都不能进入运行。 */
    private static final Pattern UUID_TEXT =
            Pattern.compile(
                    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Set<String> SECTIONS =
            Set.of("conceptCards", "termDictionary", "relationSummaries", "exampleQa");
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-fA-F]{64}");

    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
    private final String baseUrl;
    private final String keyRef;
    private final Clock clock;
    private final String fixedApiKey;
    /** access-order LRU；只在 synchronized 块内读写，容量上限保证进程内内存有界。 */
    private final LinkedHashMap<String, CacheEntry> cache = new LinkedHashMap<>(16, 0.75f, true);
    /** 绑定缓存独立于取包缓存：命中绑定不代表包仍在 5 分钟窗口内，反之亦然。 */
    private final LinkedHashMap<String, BindingEntry> bindings =
            new LinkedHashMap<>(16, 0.75f, true);

    private record CacheEntry(Pack pack, Instant expiresAt) {}

    private record BindingEntry(Binding binding, Instant expiresAt) {}

    public OntologyContextPacks(
            @Value("${agent.ontology.base-url:}") String baseUrl,
            @Value("${agent.ontology.api-key-ref:}") String keyRef) {
        this(baseUrl, keyRef, Clock.systemUTC(), null);
    }

    private OntologyContextPacks(
            String baseUrl, String keyRef, Clock clock, String fixedApiKey) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.keyRef = keyRef == null ? "" : keyRef.trim();
        this.clock = clock;
        this.fixedApiKey = fixedApiKey;
    }

    /** 测试工厂：直接给密钥，避开 secret_ref 解析；生产路径仍只接受引用。 */
    public static OntologyContextPacks forApiKey(String baseUrl, String apiKey, Clock clock) {
        return new OntologyContextPacks(baseUrl, "", clock, apiKey);
    }

    /**
     * 取包。成功结果按 {@code tenant|space|releaseId} 缓存 5 分钟；失败一律不缓存，
     * 使同一运行的短暂重试能再次触达本体而不是被错误钉死。
     */
    public Pack fetch(String tenantId, String space, String releaseId, Actor actor) {
        String cacheKey = tenantId + "|" + space + "|" + releaseId;
        synchronized (cache) {
            CacheEntry hit = cache.get(cacheKey);
            if (hit != null) {
                if (hit.expiresAt().isAfter(clock.instant())) return hit.pack();
                cache.remove(cacheKey);
            }
        }
        if (baseUrl.isBlank()) throw unavailable();
        String apiKey = apiKey(actor);
        String body = get(releaseId, apiKey);
        JsonNode pack = body(body);
        Pack result = new Pack(pack, Digest.sha256(body));
        synchronized (cache) {
            cache.put(cacheKey, new CacheEntry(result, clock.instant().plus(TTL)));
            while (cache.size() > CAPACITY) cache.remove(cache.entrySet().iterator().next().getKey());
        }
        return result;
    }

    /**
     * 按发布绑定解析 releaseId（{@code GET /onto/internal/release-bindings}）。
     *
     * <p>正面结果按 {@code tenant|appCode|environment} 缓存 30 秒；负结果（404 与任何故障）都不缓存，
     * 否则一次瞬时 5xx 会把"没有绑定"钉死一个缓存窗口。
     *
     * <p>404/{@code BINDING_NOT_FOUND} 是"该应用当前没有生效绑定"这一事实，不是故障：抛 404 让调用方决定
     * 是否 fail-open。其余情况（连接失败、5xx、非 404 的非 2xx、响应形状非法、非 ACTIVE 绑定）一律
     * 503/{@code CONTEXT_PACK_UNAVAILABLE}。
     */
    public Binding resolveBinding(
            String tenantId, String appCode, String environment, Actor actor) {
        String cacheKey = tenantId + "|" + appCode + "|" + environment;
        synchronized (bindings) {
            BindingEntry hit = bindings.get(cacheKey);
            if (hit != null) {
                if (hit.expiresAt().isAfter(clock.instant())) return hit.binding();
                bindings.remove(cacheKey);
            }
        }
        if (baseUrl.isBlank()) throw bindingUnavailable(null);
        Binding resolved = queryBinding(appCode, tenantId, environment, apiKey(actor));
        synchronized (bindings) {
            bindings.put(cacheKey, new BindingEntry(resolved, clock.instant().plus(BINDING_TTL)));
            while (bindings.size() > CAPACITY)
                bindings.remove(bindings.entrySet().iterator().next().getKey());
        }
        return resolved;
    }

    private Binding queryBinding(
            String appCode, String tenantId, String environment, String apiKey) {
        var request =
                HttpRequest.newBuilder(
                                URI.create(
                                        baseUrl.replaceAll("/+$", "")
                                                + "/onto/internal/release-bindings?platformTenant="
                                                + encode(tenantId)
                                                + "&appCode="
                                                + encode(appCode)
                                                + "&environment="
                                                + encode(environment)))
                        .timeout(REQUEST_TIMEOUT)
                        .header("X-API-Key", apiKey)
                        .GET()
                        .build();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw bindingUnavailable(null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw bindingUnavailable(null);
        }
        String raw;
        try (InputStream in = response.body()) {
            int status = response.statusCode();
            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw bindingUnavailable(null);
            raw = new String(bytes, StandardCharsets.UTF_8);
            if (status == 404) {
                // 只有本体自己给出 BINDING_NOT_FOUND 才算"没有绑定"；网关/错配路由的 404 必须报不可用，
                // 否则一次路由错误会被静默降级成"该应用没绑定、不注入"。
                if (BINDING_NOT_FOUND.equals(errorCode(raw)))
                    throw new PlatformException(
                            404, BINDING_NOT_FOUND, "本体没有该应用在当前环境的生效发布绑定");
                throw bindingUnavailable("HTTP 404");
            }
            if (status != 200) throw bindingUnavailable("HTTP " + status);
        } catch (IOException e) {
            throw bindingUnavailable(null);
        }
        JsonNode payload = bindingPayload(raw);
        String releaseId = payload.path("releaseId").asText("").trim();
        // 指向坏版本比"不知道指向哪"更危险：形状不合法就不允许落到运行里。
        if (!UUID_TEXT.matcher(releaseId).matches()) throw bindingUnavailable("releaseId 非法");
        String state = payload.path("status").asText("").trim();
        if (!state.isEmpty() && !"ACTIVE".equals(state)) throw bindingUnavailable("绑定状态非 ACTIVE");
        return new Binding(releaseId, payload.path("bindingId").asText(""));
    }

    /** 绑定响应同样可能是 Envelope{code,message,data}；data 缺失时按裸绑定对象解析。 */
    private JsonNode bindingPayload(String raw) {
        JsonNode root;
        try {
            root = Json.read(raw);
        } catch (RuntimeException e) {
            throw bindingUnavailable(null);
        }
        if (!root.isObject()) throw bindingUnavailable(null);
        JsonNode payload =
                root.has("code") && root.path("data").isObject() ? root.path("data") : root;
        if (!payload.isObject()) throw bindingUnavailable(null);
        return payload;
    }

    /** 只读错误码；解析不出来等同于"没有可识别的错误码"，不为此额外报错。 */
    private static String errorCode(String raw) {
        try {
            JsonNode root = Json.read(raw);
            return root.isObject() ? root.path("code").asText("") : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static PlatformException bindingUnavailable(String detail) {
        return new PlatformException(
                503,
                "CONTEXT_PACK_UNAVAILABLE",
                detail == null ? BINDING_UNAVAILABLE : BINDING_UNAVAILABLE + "（" + detail + "）");
    }

    /** 查询串参数必须编码：appCode 由应用侧提供，未编码的 &/= 能改写查询结构。 */
    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String get(String releaseId, String apiKey) {
        HttpResponse<InputStream> response;
        try {
            var request =
                    HttpRequest.newBuilder(
                                    URI.create(
                                            baseUrl.replaceAll("/+$", "")
                                                    + "/onto/releases/"
                                                    + releaseId
                                                    + "/context-pack"))
                            .timeout(REQUEST_TIMEOUT)
                            .header("X-API-Key", apiKey)
                            .GET()
                            .build();
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw unavailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
        try (InputStream in = response.body()) {
            int status = response.statusCode();
            if (status != 200) {
                if (status >= 400 && status < 500)
                    throw new PlatformException(
                            503, "CONTEXT_PACK_UNAVAILABLE", UNAVAILABLE + "（HTTP " + status + "）");
                throw unavailable();
            }
            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw unavailable();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw unavailable();
        }
    }

    /**
     * 本体返回 Envelope{code,message,data}，包正文在 data 内；也接受无信封的裸包。
     * candidateSha256 是本体的候选摘要，不是响应体摘要：存在时必须是 64 hex，缺失不阻塞（历史 release 无此字段）。
     */
    private JsonNode body(String raw) {
        JsonNode root;
        try {
            root = Json.read(raw);
        } catch (RuntimeException e) {
            throw unavailable();
        }
        if (!root.isObject()) throw unavailable();
        JsonNode pack = root;
        if (root.has("code") && root.path("data").isObject()) pack = root.path("data");
        if (!pack.isObject()) throw unavailable();
        boolean hasSection = false;
        for (String section : SECTIONS) hasSection |= pack.path(section).isArray();
        if (!hasSection) throw unavailable();
        if (pack.has("candidateSha256")) {
            JsonNode candidate = pack.path("candidateSha256");
            if (!candidate.isTextual()
                    || !SHA256_HEX.matcher(candidate.asText()).matches())
                throw unavailable();
        }
        return pack;
    }

    private String apiKey(Actor actor) {
        if (fixedApiKey != null && !fixedApiKey.isBlank()) return fixedApiKey;
        if (keyRef.isBlank()) throw unavailable();
        try {
            return SecretVault.resolve(keyRef, actor);
        } catch (PlatformException e) {
            // 密钥作用域越权是配置错误，保留 403 信号，不伪装成本体不可用。
            throw e;
        } catch (RuntimeException e) {
            throw unavailable();
        }
    }

    private static PlatformException unavailable() {
        return new PlatformException(503, "CONTEXT_PACK_UNAVAILABLE", UNAVAILABLE);
    }
}
