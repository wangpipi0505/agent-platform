package cn.agentplatform.engine.infra;

import cn.agentplatform.common.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
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

    private static final int MAX_BYTES = 256 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final int CAPACITY = 64;
    private static final String UNAVAILABLE = "本体上下文暂不可用，请稍后重试";
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

    private record CacheEntry(Pack pack, Instant expiresAt) {}

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
