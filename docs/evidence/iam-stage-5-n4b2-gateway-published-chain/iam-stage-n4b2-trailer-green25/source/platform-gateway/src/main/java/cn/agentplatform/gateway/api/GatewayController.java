package cn.agentplatform.gateway.api;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.AuthorityProperties;
import cn.agentplatform.common.authority.GatewayIngressBoundary;
import cn.agentplatform.gateway.application.IntegrationAccess;
import cn.agentplatform.gateway.application.PublishedRunProxy;
import cn.agentplatform.gateway.domain.Routes;

import jakarta.servlet.http.*;

import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/** 单一入口和有界流转发；用户JWT在网关验签后换发内部票据，不透传至外部模型/工具。 */
@RestController
public class GatewayController {
    private final Environment env;
    private final IntegrationAccess integration;
    private final AuthorityProperties deployment;
    private final Optional<PublishedRunProxy> published;

    public GatewayController(
            Environment env,
            IntegrationAccess integration,
            AuthorityProperties deployment,
            Optional<PublishedRunProxy> published) {
        this.env = env;
        this.integration = integration;
        this.deployment = Objects.requireNonNull(deployment);
        this.published = Objects.requireNonNull(published);
    }

    private String base(String service) {
        return env.getRequiredProperty("agent.services." + service);
    }

    @RequestMapping({"/admin/**", "/v1/**"})
    public void proxy(HttpServletRequest req, HttpServletResponse res) throws IOException {
        if ("iam".equals(deployment.mode())) {
            var source = GatewayIngressBoundary.claim(req);
            var proxy =
                    published.orElseThrow(
                            () ->
                                    new PlatformException(
                                            503, "PUBLISHED_RUN_UNAVAILABLE", "发布运行入口尚未就绪"));
            proxy.start(req, res, source);
            return;
        }
        final String service;
        try {
            service = Routes.service(req.getRequestURI());
        } catch (IllegalArgumentException e) {
            throw new PlatformException(404, "NOT_FOUND", e.getMessage());
        }
        Actor actor = Actor.current();
        integration.check(req, actor);
        String query = req.getQueryString();
        String target = base(service) + req.getRequestURI() + (query == null ? "" : "?" + query);
        HttpURLConnection upstream =
                (HttpURLConnection) URI.create(target).toURL().openConnection();
        upstream.setConnectTimeout(5000);
        upstream.setReadTimeout(650000);
        upstream.setRequestMethod(req.getMethod());
        upstream.setRequestProperty("Authorization", "Bearer " + ServiceTokens.issue(actor));
        if (req.getContentType() != null)
            upstream.setRequestProperty("Content-Type", req.getContentType());
        String cursor = req.getHeader("Last-Event-ID");
        if (cursor != null) upstream.setRequestProperty("Last-Event-ID", cursor);
        try {
            if (Set.of("POST", "PUT", "PATCH").contains(req.getMethod())) {
                upstream.setDoOutput(true);
                upstream.setChunkedStreamingMode(8192);
                try (OutputStream out = upstream.getOutputStream()) {
                    copyBounded(req.getInputStream(), out, 2 * 1024 * 1024);
                }
            }
            int status = upstream.getResponseCode();
            res.setStatus(status);
            if (upstream.getContentType() != null) res.setContentType(upstream.getContentType());
            for (String header : List.of("Content-Disposition", "ETag", "Retry-After")) {
                String value = upstream.getHeaderField(header);
                if (value != null) res.setHeader(header, value);
            }
            if (Objects.toString(upstream.getContentType(), "").contains("text/event-stream")) {
                res.setHeader("Cache-Control", "no-cache");
                res.setHeader("X-Accel-Buffering", "no");
            }
            InputStream input =
                    status >= 400 ? upstream.getErrorStream() : upstream.getInputStream();
            if (input != null)
                try (input) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = input.read(buffer)) != -1) {
                        res.getOutputStream().write(buffer, 0, n);
                        res.flushBuffer();
                    }
                }
        } catch (IOException e) {
            if (!res.isCommitted()) {
                res.setStatus(502);
                res.setContentType("application/json;charset=UTF-8");
                res.getWriter()
                        .write(
                                Json.write(
                                        new ApiResponse<>(
                                                "DEPENDENCY_UNAVAILABLE",
                                                "服务暂不可用，请检查新工程服务是否启动",
                                                null,
                                                null)));
            }
        } finally {
            upstream.disconnect();
        }
    }

    private static void copyBounded(InputStream in, OutputStream out, int max) throws IOException {
        byte[] buffer = new byte[8192];
        int total = 0, n;
        while ((n = in.read(buffer)) != -1) {
            total += n;
            if (total > max) throw new IOException("请求超过安全大小");
            out.write(buffer, 0, n);
        }
    }

    @GetMapping("/v3/api-docs")
    public Object openapi() {
        Actor actor = Actor.current();
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(10000);
        var client = RestClient.builder().requestFactory(factory).build();
        Map<String, Object> paths = new LinkedHashMap<>(), schemas = new LinkedHashMap<>();
        List<String> unavailable = new ArrayList<>();
        for (String service : List.of("app", "engine", "skill", "model", "monitor")) {
            try {
                var doc =
                        client.get()
                                .uri(base(service) + "/v3/api-docs")
                                .header("Authorization", "Bearer " + ServiceTokens.issue(actor))
                                .retrieve()
                                .body(Map.class);
                if (doc == null) throw new IOException("空文档");
                if (doc.get("paths") instanceof Map<?, ?> map)
                    map.forEach(
                            (k, v) -> {
                                if (k.toString().startsWith("/internal")) return;
                                // 多服务controller常见list/get同名，聚合文档必须产生全局唯一operationId。
                                if (v instanceof Map<?, ?> item)
                                    item.forEach(
                                            (method, operation) -> {
                                                if (operation instanceof Map<?, ?> rawOperation
                                                        && Set.of(
                                                                        "get", "post", "put",
                                                                        "delete", "patch",
                                                                        "options", "head")
                                                                .contains(method.toString())) {
                                                    @SuppressWarnings("unchecked")
                                                    Map<String, Object> mutable =
                                                            (Map<String, Object>) rawOperation;
                                                    mutable.put(
                                                            "operationId",
                                                            service
                                                                    + "_"
                                                                    + method
                                                                    + "_"
                                                                    + k.toString()
                                                                            .replaceAll(
                                                                                    "[^A-Za-z0-9]+",
                                                                                    "_"));
                                                }
                                            });
                                paths.put(k.toString(), v);
                            });
                if (doc.get("components") instanceof Map<?, ?> c
                        && c.get("schemas") instanceof Map<?, ?> map)
                    map.forEach((k, v) -> schemas.put(k.toString(), v));
            } catch (Exception e) {
                unavailable.add(service);
            }
        }
        if (!unavailable.isEmpty())
            throw new PlatformException(
                    502,
                    "OPENAPI_DEPENDENCY_UNAVAILABLE",
                    "接口文档尚未就绪：" + String.join("、", unavailable));
        return Map.of(
                "openapi",
                "3.0.3",
                "info",
                Map.of("title", "智能体构建平台 API", "version", "v1.2"),
                "servers",
                List.of(Map.of("url", "/")),
                "paths",
                paths,
                "components",
                Map.of(
                        "schemas",
                        schemas,
                        "securitySchemes",
                        Map.of(
                                "bearerAuth",
                                Map.of("type", "http", "scheme", "bearer", "bearerFormat", "JWT"))),
                "security",
                List.of(Map.of("bearerAuth", List.of())));
    }

    @GetMapping(value = "/assistant.js", produces = "text/javascript")
    public byte[] assistant() throws IOException {
        Path root =
                Path.of(System.getenv().getOrDefault("AGENT_ROOT", System.getProperty("user.dir")))
                        .toAbsolutePath()
                        .normalize();
        Path file = root.resolve("web/assistant.js/dist/assistant.js");
        if (!Files.isRegularFile(file))
            throw new PlatformException(503, "EMBED_BUNDLE_UNAVAILABLE", "嵌入包尚未构建，请先构建新工程前端");
        return Files.readAllBytes(file);
    }
}
