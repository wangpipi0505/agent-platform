package cn.agentplatform.engine.infra;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.common.authority.HttpCurrentAuthorityClient.RetainedAuthorization;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RuntimeClients implements AutoCloseable {
    private final boolean iam;
    private final CurrentAuthorityClient authority;
    private final java.net.URI publishedOrigin;
    private final org.apache.hc.client5.http.impl.classic.CloseableHttpClient publishedHttp;
    private final java.util.concurrent.ExecutorService publishedExecutor;

    /** Explicit legacy construction for dev/test callers. Spring always uses deployment input. */
    public RuntimeClients() {
        iam = false;
        authority = null;
        publishedOrigin = null;
        publishedHttp = null;
        publishedExecutor = null;
    }

    @Autowired
    public RuntimeClients(
            AuthorityProperties properties,
            ObjectProvider<CurrentAuthorityClient> capability,
            @Value("${agent.services.app:http://127.0.0.1:18081}") String origin) {
        this(properties, capability.getIfAvailable(), origin, false);
    }

    /**
     * Explicit isolated assembly; loopback is limited to 127.0.0.1 and never inferred from a
     * ticket.
     */
    public RuntimeClients(
            AuthorityProperties properties,
            CurrentAuthorityClient capability,
            String origin,
            boolean isolatedLoopback) {
        this(properties, capability, origin, isolatedLoopback, null);
    }

    /** The supplied executor transfers ownership to this concrete client after successful assembly. */
    RuntimeClients(
            AuthorityProperties properties,
            CurrentAuthorityClient capability,
            String origin,
            boolean isolatedLoopback,
            java.util.concurrent.ExecutorService ownedExecutor) {
        java.util.Objects.requireNonNull(properties).validate(isolatedLoopback);
        if (!"engine".equals(properties.serviceId()))
            throw new IllegalArgumentException("ENGINE_TRUST_REQUIRED");
        iam = "iam".equals(properties.mode());
        authority = capability;
        app = origin;
        if (iam) {
            if (authority == null) throw new IllegalStateException("IAM_CLIENT_REQUIRED");
            var uri = java.net.URI.create(java.util.Objects.requireNonNull(origin));
            boolean isolated =
                    isolatedLoopback
                            && "http".equals(uri.getScheme())
                            && "127.0.0.1".equals(uri.getHost());
            if ((!"https".equals(uri.getScheme()) && !isolated)
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || !uri.getRawPath().isEmpty()
                    || uri.getPort() == 0
                    || uri.getPort() < -1
                    || uri.getPort() > 65535)
                throw new IllegalArgumentException("APP_ORIGIN_INVALID");
            publishedOrigin = uri;
            var manager =
                    org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
                            .create()
                            .setDefaultConnectionConfig(
                                    org.apache.hc.client5.http.config.ConnectionConfig.custom()
                                            .setConnectTimeout(
                                                    org.apache.hc.core5.util.Timeout.ofMilliseconds(
                                                            1000))
                                            .setSocketTimeout(
                                                    org.apache.hc.core5.util.Timeout.ofMilliseconds(
                                                            2000))
                                            .build())
                            .build();
            publishedHttp =
                    org.apache.hc.client5.http.impl.classic.HttpClients.custom()
                            .setConnectionManager(manager)
                            .disableAutomaticRetries()
                            .disableRedirectHandling()
                            .disableCookieManagement()
                            .disableAuthCaching()
                            .disableContentCompression()
                            .disableConnectionState()
                            .setDefaultRequestConfig(
                                    org.apache.hc.client5.http.config.RequestConfig.custom()
                                            .setConnectionRequestTimeout(
                                                    org.apache.hc.core5.util.Timeout.ofMilliseconds(
                                                            1000))
                                            .setResponseTimeout(
                                                    org.apache.hc.core5.util.Timeout.ofMilliseconds(
                                                            2000))
                                            .setAuthenticationEnabled(false)
                                            .setHardCancellationEnabled(true)
                                            .build())
                            .build();
            publishedExecutor =
                    ownedExecutor != null
                            ? ownedExecutor
                            : java.util.concurrent.Executors.newThreadPerTaskExecutor(
                                    Thread.ofVirtual().name("app-published-", 0).factory());
        } else {
            publishedOrigin = null;
            publishedHttp = null;
            publishedExecutor = null;
        }
    }

    public boolean iamEnabled() {
        return iam;
    }

    public JsonNode published(String code, RetainedAuthorization retained) {
        if (!iam || publishedHttp == null || publishedExecutor.isShutdown()) throw unavailable();
        AuthorityContext.requireBound(retained);
        MetadataSelectors.code(code);
        var current = retained.current();
        var root = current.call().resource();
        if (!"gateway".equals(current.caller())
                || !"engine".equals(current.call().target())
                || !"proxy.engine.post.RunController.run".equals(current.call().action())
                || !"PORTAL".equals(current.operation().sourceKind())
                || !"PORTAL".equals(current.operation().originKind())
                || !"app-version".equals(root.kind())
                || !root.equals(current.operation().resource())
                || !root.id().matches(java.util.regex.Pattern.quote(code) + "@[1-9][0-9]*"))
            throw RunAuthorityStore.denied();
        // Forward the bound main operation/grant. No portal source, retarget, resume or actor
        // ticket.
        var forwarded =
                authority.forward(current, new ServiceCall("app", "app.published.read", root));
        AuthorityContext.requireBound(retained);
        var request =
                new org.apache.hc.client5.http.classic.methods.HttpGet(
                        publishedOrigin.resolve("/internal/apps/" + code + "/published"));
        request.setHeader("Authorization", "Bearer " + forwarded.value());
        request.setHeader("Accept", "application/json");
        request.setHeader("Accept-Encoding", "identity");
        java.util.concurrent.Future<PublishedResponse> response = null;
        try {
            // Disable Apache's request retry/redirect/auth/compression chains per instance. This
            // constrains actual HTTP dispatch; TCP address connection attempts are not GET replays.
            response =
                    publishedExecutor.submit(
                            () ->
                                    publishedHttp.execute(
                                            request,
                                            upstream -> {
                                                try {
                                                    for (var encoding :
                                                            upstream.getHeaders("Content-Encoding"))
                                                        if (!"identity"
                                                                .equalsIgnoreCase(
                                                                        encoding.getValue()))
                                                            throw new java.io.IOException(
                                                                    "APP_CONTENT_ENCODING");
                                                    var bytes = new java.io.ByteArrayOutputStream();
                                                    if (upstream.getEntity() != null)
                                                        try (var input =
                                                                upstream.getEntity().getContent()) {
                                                            byte[] part = new byte[8192];
                                                            int count;
                                                            while ((count = input.read(part))
                                                                    != -1) {
                                                                if (count
                                                                        > 4194304 - bytes.size()) {
                                                                    // Cancel before stream/response
                                                                    // close: graceful close may
                                                                    // drain an
                                                                    // unbounded entity. Apache
                                                                    // discards the endpoint
                                                                    // immediately.
                                                                    request.cancel();
                                                                    throw new java.io.IOException(
                                                                            "APP_BODY_LIMIT");
                                                                }
                                                                bytes.write(part, 0, count);
                                                            }
                                                        }
                                                    return new PublishedResponse(
                                                            upstream.getCode(),
                                                            bytes.toByteArray());
                                                } catch (java.io.IOException
                                                        | RuntimeException failure) {
                                                    request.cancel();
                                                    throw failure;
                                                }
                                            }));
            // Socket response timeouts reset with activity. The outer future covers headers and
            // the entire body together, including a body which stalls after initial bytes.
            var result = response.get(2000, java.util.concurrent.TimeUnit.MILLISECONDS);
            AuthorityContext.requireBound(retained);
            int status = result.status();
            if (status != 200) {
                if (status >= 400 && status <= 599)
                    throw new PlatformException(status, "APP_PUBLISHED_HTTP_" + status, "应用发布读取失败");
                throw unavailable();
            }
            JsonNode envelope;
            try {
                envelope =
                        Json.read(
                                new String(result.body(), java.nio.charset.StandardCharsets.UTF_8));
            } catch (RuntimeException invalidJson) {
                throw unavailable();
            }
            if (!envelope.isObject()
                    || !"OK".equals(envelope.path("code").asText())
                    || !envelope.path("data").isObject()) throw unavailable();
            JsonNode value = envelope.path("data");
            var no = value.path("versionNo");
            var id = value.path("appVersionId");
            var alias = value.path("versionId");
            if (!value.path("appCode").isTextual()
                    || !code.equals(value.path("appCode").textValue())
                    || !no.isIntegralNumber()
                    || !no.canConvertToLong()
                    || no.longValue() < 1
                    || !id.isIntegralNumber()
                    || !id.canConvertToLong()
                    || id.longValue() < 1
                    || !alias.isIntegralNumber()
                    || !alias.canConvertToLong()
                    || alias.longValue() != id.longValue()
                    || !root.id().equals(code + "@" + no.longValue())
                    || !root.id().equals(value.path("appVersionKey").asText())
                    || !root.versionDigest().equals(value.path("candidateSha256").asText()))
                throw unavailable();
            AuthorityContext.requireBound(retained);
            return value;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException
                | java.util.concurrent.CancellationException e) {
            throw unavailable();
        } catch (PlatformException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable();
        } finally {
            request.cancel();
            if (response != null && !response.isDone()) response.cancel(true);
        }
    }

    private static PlatformException unavailable() {
        return new PlatformException(503, "APP_PUBLISHED_UNAVAILABLE", "应用发布读取暂不可用");
    }

    private record PublishedResponse(int status, byte[] body) {}

    @jakarta.annotation.PreDestroy
    @Override
    public void close() {
        if (publishedHttp != null) publishedHttp.close(org.apache.hc.core5.io.CloseMode.IMMEDIATE);
        if (publishedExecutor != null) {
            publishedExecutor.shutdownNow();
            try {
                if (!publishedExecutor.awaitTermination(
                        1000, java.util.concurrent.TimeUnit.MILLISECONDS))
                    throw new IllegalStateException("APP_CLIENT_SHUTDOWN_INCOMPLETE");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void legacy(String service) {
        base(service);
        if (iam) throw new PlatformException(403, "IAM_LEGACY_CLIENT_FORBIDDEN", "当前服务调用必须使用已核验授权");
    }

    private static org.springframework.http.client.SimpleClientHttpRequestFactory requestFactory() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(120000);
        return factory;
    }

    private final RestClient http = RestClient.builder().requestFactory(requestFactory()).build();
    private final RestClient probes = probeClient();

    private static RestClient probeClient() {
        var factory = requestFactory();
        factory.setReadTimeout(5000);
        return RestClient.builder().requestFactory(factory).build();
    }

    @Value("${agent.services.app:http://127.0.0.1:18081}")
    String app;

    @Value("${agent.services.skill:http://127.0.0.1:18083}")
    String skill;

    @Value("${agent.services.model:http://127.0.0.1:18084}")
    String model;

    public JsonNode get(String service, String path, Actor actor) {
        legacy(service);
        return unwrap(
                (path.startsWith("/internal/skill-facts/") ? probes : http)
                        .get()
                        .uri(base(service) + path)
                        .header("Authorization", "Bearer " + ServiceTokens.issue(actor))
                        .retrieve()
                        .body(String.class));
    }

    public JsonNode post(String service, String path, Object body, Actor actor) {
        legacy(service);
        return unwrap(
                http.post()
                        .uri(base(service) + path)
                        .header("Authorization", "Bearer " + ServiceTokens.issue(actor))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class));
    }

    private String base(String service) {
        if (service == null) throw RunAuthorityStore.denied();
        return switch (service) {
            case "app" -> app;
            case "skill" -> skill;
            case "model" -> model;
            default -> throw RunAuthorityStore.denied();
        };
    }

    private JsonNode unwrap(String text) {
        JsonNode n = Json.read(text);
        if (n.has("code")) {
            if (!n.path("code").asText().equals("OK"))
                throw new PlatformException(502, "UPSTREAM_ERROR", n.path("message").asText());
            return n.path("data");
        }
        return n;
    }

    public JsonNode chatStream(
            Object body, Actor actor, java.util.function.Consumer<String> onChunk) {
        legacy("model");
        java.net.HttpURLConnection connection = null;
        try {
            connection =
                    (java.net.HttpURLConnection)
                            java.net
                                    .URI
                                    .create(model + "/internal/model/chat/stream")
                                    .toURL()
                                    .openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(120000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setRequestProperty("Authorization", "Bearer " + ServiceTokens.issue(actor));
            try (var output = connection.getOutputStream()) {
                output.write(Json.write(body).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            if (connection.getResponseCode() != 200)
                throw new PlatformException(
                        502, "MODEL_STREAM_ERROR", "模型流请求失败 HTTP " + connection.getResponseCode());
            try (var reader =
                    new java.io.BufferedReader(
                            new java.io.InputStreamReader(
                                    connection.getInputStream(),
                                    java.nio.charset.StandardCharsets.UTF_8))) {
                String event = "message", line;
                StringBuilder data = new StringBuilder();
                while ((line = reader.readLine()) != null) {
                    if (line.length() > 4194304)
                        throw new PlatformException(502, "MODEL_STREAM_LIMIT", "模型流事件超过上限");
                    if (line.startsWith("event:")) event = line.substring(6).trim();
                    else if (line.startsWith("data:")) {
                        if (!data.isEmpty()) data.append('\n');
                        data.append(line.substring(5).stripLeading());
                    } else if (line.isEmpty() && !data.isEmpty()) {
                        JsonNode value = Json.read(data.toString());
                        if (event.equals("chunk")) onChunk.accept(value.path("text").asText());
                        else if (event.equals("final")) return value;
                        else if (event.equals("error"))
                            throw new PlatformException(
                                    502,
                                    "MODEL_STREAM_ERROR",
                                    value.path("message").asText("模型流失败"));
                        data.setLength(0);
                        event = "message";
                    }
                }
            }
            throw new PlatformException(502, "MODEL_STREAM_INCOMPLETE", "模型流缺少最终完整响应");
        } catch (java.io.IOException e) {
            throw new PlatformException(502, "MODEL_STREAM_IO", "模型流连接中断：" + e.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
