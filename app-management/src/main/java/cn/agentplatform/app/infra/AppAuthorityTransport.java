package cn.agentplatform.app.infra;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.ServiceTokens;
import cn.agentplatform.common.TraceContextFilter;
import cn.agentplatform.common.auth.OnboardingServiceProof;
import cn.agentplatform.common.authority.AuthorityContext;
import cn.agentplatform.common.authority.CurrentAuthorityClient;
import cn.agentplatform.common.authority.ServiceCall;
import cn.agentplatform.common.authority.ServiceRoutePolicy;
import com.fasterxml.jackson.databind.DeserializationFeature;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 固定部署 origin、目录 handler 和当前同根授权；显式开发证明与正式能力互不回退。 */
@Component
public class AppAuthorityTransport {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final long MAX_NANOS = 30_000_000_000L;
    private static final Set<String> DEV_SKILL_ACTIONS = Set.of(
            "skill.onboarding-import.create", "skill.onboarding-import.read", "skill.metadata.read",
            "proxy.skill.post.OpenApiImportController.preview");
    private record Endpoint(String origin, ServiceRoutePolicy.Route route, PathPattern pattern) {}
    private record Outbound(String ticket, String proof) {}

    private final List<Endpoint> endpoints;
    private final ObjectProvider<CurrentAuthorityClient> authority;
    private final HttpClient http;

    @Value("${agent.security.mode:}")
    private String securityMode = "";

    @Value("${agent.onboarding.dev-app-key-ref:}")
    private String devAppKeyRef = "";

    public AppAuthorityTransport(
            @Value("${agent.services.skill:http://127.0.0.1:18083}") String skill,
            @Value("${agent.services.model:http://127.0.0.1:18084}") String model,
            @Value("${agent.services.engine:http://127.0.0.1:18082}") String engine,
            @Value("${agent.services.monitor:http://127.0.0.1:18085}") String monitor,
            ObjectProvider<CurrentAuthorityClient> authority) {
        var origins = Map.of(
                "skill", origin(skill), "model", origin(model),
                "engine", origin(engine), "monitor", origin(monitor));
        var registered = new ArrayList<Endpoint>();
        var parser = new PathPatternParser();
        for (var route : new ServiceRoutePolicy().routes()) {
            if ("app".equals(route.caller()) && "delegated".equals(route.mode())
                    && origins.containsKey(route.target()))
                registered.add(new Endpoint(origins.get(route.target()), route, parser.parse(route.template())));
        }
        endpoints = List.copyOf(registered);
        this.authority = authority;
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public Map<String, Object> call(String base, String path, Map<String, Object> body) {
        return call(base, path, body, System.nanoTime() + MAX_NANOS);
    }

    public Map<String, Object> call(String base, String path, Map<String, Object> body, long deadlineNanos) {
        long now = System.nanoTime();
        long budget = Math.min(MAX_NANOS, deadlineNanos - now);
        if (budget <= 0) throw timeout();
        long deadline = now + budget;
        String method = body == null ? "GET" : "POST";
        URI relative = path(path);
        Endpoint endpoint = endpoint(base, relative.getRawPath(), method);
        byte[] payload = body == null ? null : Json.write(body).getBytes(StandardCharsets.UTF_8);
        if (payload != null && payload.length > MAX_BYTES)
            throw new PlatformException(413, "DEPENDENCY_REQUEST_SIZE", "服务请求超过2MiB预算");
        Outbound outbound = authorize(endpoint, method, relative.toASCIIString(), body, deadline);
        var request = HttpRequest.newBuilder(URI.create(endpoint.origin()).resolve(relative))
                .timeout(Duration.ofNanos(remaining(deadline)))
                .header("Authorization", "Bearer " + outbound.ticket())
                .header("Accept", "application/json")
                .header(TraceContextFilter.HEADER, TraceContextFilter.current());
        if (outbound.proof() != null) request.header(OnboardingServiceProof.HEADER, outbound.proof());
        if (payload == null) request.GET();
        else request.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload));

        // 整个响应体接收共用剩余截止时间；慢速流不能靠不断返回小块重置总预算。
        CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(request.build(), info -> new BoundedBody());
        try {
            var response = pending.get(remaining(deadline), TimeUnit.NANOSECONDS);
            remaining(deadline);
            var result = response(response.statusCode(), response.body());
            remaining(deadline);
            return result;
        } catch (TimeoutException failure) {
            pending.cancel(true);
            throw timeout();
        } catch (InterruptedException failure) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (ExecutionException failure) {
            for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof PlatformException expected) throw expected;
                if (cause instanceof HttpTimeoutException) throw timeout();
                if (cause.getCause() == cause) break;
            }
            throw unavailable();
        } catch (PlatformException failure) {
            pending.cancel(true);
            throw failure;
        }
    }

    private Outbound authorize(
            Endpoint endpoint, String method, String pathAndQuery, Map<String, Object> body, long deadline) {
        Actor actor = Actor.current();
        remaining(deadline);
        if ("dev-hmac".equals(securityMode)) {
            if (!"skill".equals(endpoint.route().target()) || !DEV_SKILL_ACTIONS.contains(endpoint.route().action()))
                throw new PlatformException(403, "AUTHORITY_ROUTE_FORBIDDEN", "开发接入证明仅用于精确技能接入口");
            actor.requireRole("EDITOR");
            String proof = OnboardingServiceProof.issue(devAppKeyRef, actor, method, pathAndQuery, body);
            return new Outbound(ServiceTokens.issue(actor), proof);
        }
        if (!"iam".equals(securityMode))
            throw new PlatformException(503, "AUTHORITY_CONTEXT_MISSING", "必须显式配置正式或开发接入模式");
        var client = authority.getIfAvailable();
        if (client == null)
            throw new PlatformException(503, "AUTHORITY_CONTEXT_MISSING", "正式服务调用缺少当前授权通路");
        var first = AuthorityContext.current();
        if (!"app".equals(first.call().target()) || first.call().resource() == null || first.operation() == null)
            throw new PlatformException(403, "AUTHORITY_RESOURCE_MISMATCH", "应用出站缺少当前同根授权");
        var current = AuthorityContext.requireFor(actor, first.call().resource());
        remaining(deadline);
        var ticket = client.forward(current,
                new ServiceCall(endpoint.route().target(), endpoint.route().action(), current.call().resource()));
        remaining(deadline);
        if (ticket == null || ticket.value() == null || ticket.value().isBlank()
                || ticket.expiresAt() == null || !ticket.expiresAt().isAfter(Instant.now())
                || !current.operation().equals(ticket.operation()))
            throw new PlatformException(403, "AUTHORITY_RESOURCE_MISMATCH", "服务票据与当前同根操作不一致");
        return new Outbound(ticket.value(), null);
    }

    private Endpoint endpoint(String base, String rawPath, String method) {
        final String origin;
        try {
            origin = origin(base);
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new PlatformException(403, "AUTHORITY_ROUTE_FORBIDDEN", "出站服务地址不在部署目录");
        }
        PathContainer candidate = PathContainer.parsePath(rawPath);
        var matched = endpoints.stream()
                .filter(row -> row.origin().equals(origin) && row.route().method().equals(method)
                        && row.pattern().matches(candidate))
                .toList();
        if (matched.size() != 1)
            throw new PlatformException(403, "AUTHORITY_ROUTE_FORBIDDEN", "出站路径没有唯一的已登记应用能力");
        return matched.getFirst();
    }

    private static URI path(String value) {
        try {
            URI uri = URI.create(value);
            String raw = uri.getRawPath();
            if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawFragment() != null
                    || raw == null || !raw.startsWith("/") || raw.contains("//") || raw.contains("%")
                    || raw.contains(";") || raw.contains("\\") || !raw.equals(uri.normalize().getRawPath())
                    || raw.codePoints().anyMatch(code -> code > 127 || Character.isISOControl(code) || Character.isWhitespace(code)))
                throw new IllegalArgumentException();
            // query 只随已经匹配的 handler 发送，不能参与 handler/target 选择。
            return uri;
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new PlatformException(400, "AUTHORITY_ROUTE_INVALID", "出站必须使用未编码的本地登记路径");
        }
    }

    private static String origin(String value) {
        URI uri = URI.create(value);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))
                || uri.getPort() == 0 || uri.getPort() > 65535)
            throw new IllegalArgumentException("服务必须配置为固定部署origin");
        scheme = scheme.toLowerCase(Locale.ROOT);
        host = host.toLowerCase(Locale.ROOT);
        // HTTP 只接固定 loopback 字面地址，不把任意可解析主机名视为回环信任。
        boolean loopback = Set.of("127.0.0.1", "::1", "[::1]").contains(host);
        if (!"https".equals(scheme) && !("http".equals(scheme) && loopback))
            throw new IllegalArgumentException("服务origin须为HTTPS或受控loopback HTTP");
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        return scheme + "://" + host + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> response(int status, byte[] bytes) {
        if (status >= 300 && status < 400)
            throw new PlatformException(502, "DEPENDENCY_REDIRECT", "服务依赖重定向被拒绝");
        final com.fasterxml.jackson.databind.JsonNode envelope;
        try {
            envelope = Json.mapper().reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
        } catch (IOException failure) {
            throw new PlatformException(status >= 400 && status < 500 ? status : 502,
                    "DEPENDENCY_UNAVAILABLE", "服务依赖回复不是有效JSON");
        }
        if (envelope == null || !envelope.isObject())
            throw new PlatformException(status >= 400 && status < 500 ? status : 502,
                    "DEPENDENCY_UNAVAILABLE", "服务依赖回复缺少有效对象");
        if (status < 200 || status >= 300 || !"OK".equals(envelope.path("code").asText()))
            throw new PlatformException(status >= 400 ? status : 502,
                    envelope.path("code").asText("DEPENDENCY_UNAVAILABLE"),
                    envelope.path("message").asText("服务依赖拒绝当前请求"));
        if (!envelope.path("data").isObject()) throw unavailable();
        return new LinkedHashMap<>(Json.mapper().convertValue(envelope.path("data"), Map.class));
    }

    private static long remaining(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw timeout();
        return remaining;
    }

    private static PlatformException timeout() {
        return new PlatformException(504, "DEPENDENCY_TIMEOUT", "正式服务调用超过剩余时间预算");
    }

    private static PlatformException unavailable() {
        return new PlatformException(502, "DEPENDENCY_UNAVAILABLE", "服务依赖暂不可用，请保留原回执后恢复");
    }

    /** 接收每个数据块之前检查总量，避免先无界分配完整响应再检查大小。 */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer item : items) {
                if (item.remaining() > MAX_BYTES - bytes.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new PlatformException(
                            502, "DEPENDENCY_RESPONSE_SIZE", "服务依赖回复超过2MiB预算"));
                    return;
                }
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable failure) {
            body.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            body.complete(bytes.toByteArray());
        }
    }
}
