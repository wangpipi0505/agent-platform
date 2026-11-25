package cn.agentplatform.gateway.api;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.AuthorityProperties;
import cn.agentplatform.common.authority.CurrentAuthorityClient;
import cn.agentplatform.common.authority.EvaluationCandidateBinding;
import cn.agentplatform.common.authority.GatewayIngressBoundary;
import cn.agentplatform.common.authority.PlatformBuildRoutes;
import cn.agentplatform.common.authority.PlatformAccessClient;
import cn.agentplatform.common.authority.IntegrationRuntimeRoutes;
import cn.agentplatform.common.authority.IntegrationTaskRoutes;
import cn.agentplatform.common.authority.SubjectKey;
import cn.agentplatform.common.authority.PortalRuntimeRoutes;
import cn.agentplatform.common.authority.ServiceCall;
import cn.agentplatform.common.authority.ServiceRoutePolicy;
import cn.agentplatform.gateway.application.IntegrationAccess;
import cn.agentplatform.gateway.application.PublishedRunProxy;
import cn.agentplatform.gateway.application.PublishedMetadataClient;
import cn.agentplatform.gateway.application.RunStatusProxy;
import cn.agentplatform.gateway.application.RunEventsProxy;
import cn.agentplatform.gateway.domain.Routes;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.*;

import org.springframework.core.env.Environment;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** 单一入口和有界流转发；用户JWT在网关验签后换发内部票据，不透传至外部模型/工具。 */
@RestController
public class GatewayController {
    private final Environment env;
    private final IntegrationAccess integration;
    private final AuthorityProperties deployment;
    private final Optional<PublishedRunProxy> published;
    private final Optional<RunStatusProxy> status;
    private final Optional<RunEventsProxy> events;
    private final Optional<CurrentAuthorityClient> authority;
    private final ObjectProvider<PublishedMetadataClient> taskMetadata;
    private PlatformAccessClient platformAccess;
    private final ServiceRoutePolicy serviceRoutes = new ServiceRoutePolicy();

    public GatewayController(
            Environment env,
            IntegrationAccess integration,
            AuthorityProperties deployment,
            Optional<PublishedRunProxy> published) {
        this(env,integration,deployment,published,Optional.empty());
    }

    public GatewayController(Environment env,IntegrationAccess integration,AuthorityProperties deployment,
            Optional<PublishedRunProxy> published,Optional<RunStatusProxy> status) {
        this(env,integration,deployment,published,status,Optional.empty());
    }

    public GatewayController(Environment env,IntegrationAccess integration,AuthorityProperties deployment,
            Optional<PublishedRunProxy> published,Optional<RunStatusProxy> status,Optional<RunEventsProxy> events) {
        this(env, integration, deployment, published, status, events, Optional.empty(), null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public GatewayController(Environment env, IntegrationAccess integration, AuthorityProperties deployment,
            Optional<PublishedRunProxy> published, Optional<RunStatusProxy> status, Optional<RunEventsProxy> events,
            Optional<CurrentAuthorityClient> authority, ObjectProvider<PublishedMetadataClient> taskMetadata) {
        this.env = env;
        this.integration = integration;
        this.deployment = Objects.requireNonNull(deployment);
        this.published = Objects.requireNonNull(published);
        this.status=Objects.requireNonNull(status);
        this.events=Objects.requireNonNull(events);
        this.authority = Objects.requireNonNull(authority);
        this.taskMetadata = taskMetadata;
    }

    private String base(String service) {
        return env.getRequiredProperty("agent.services." + service);
    }

    @org.springframework.beans.factory.annotation.Autowired
    void platformAccess(PlatformAccessClient client) {
        this.platformAccess = client;
    }

    @RequestMapping({"/admin/**", "/v1/**"})
    public void proxy(HttpServletRequest req, HttpServletResponse res) throws IOException {
        if ("iam".equals(deployment.mode())) {
            if (IntegrationRuntimeRoutes.metadata(req.getMethod(), req.getRequestURI())) {
                var call = IntegrationRuntimeRoutes.call(serviceRoutes, req.getMethod(), req.getRequestURI());
                var ticket = integration.exchangeRuntime(req, call);
                forward(req, res, call.target(), ticket.value());
                return;
            }
            var integrationSource = IntegrationRuntimeRoutes.currentSource();
            String integrationApp = IntegrationTaskRoutes.application(req.getMethod(), req.getRequestURI());
            if (integrationSource != null && integrationApp != null) {
                var metadata = taskMetadata == null ? null : taskMetadata.getIfAvailable();
                if (metadata == null)
                    throw new PlatformException(503, "DEPENDENCY_UNAVAILABLE", "应用发布查询暂不可用");
                var subject = new SubjectKey(integrationSource.getClaimAsString("tenant_id"), integrationSource.getSubject());
                var version = metadata.lookupIntegration(integrationSource.getTokenValue(), subject,
                        integrationApp, req.getHeader("Origin"));
                var call = IntegrationTaskRoutes.startCall(integrationApp, version.resource());
                var ticket = integration.exchangeRuntime(req, call);
                res.setHeader("Cache-Control", "no-store");
                forward(req, res, call.target(), ticket.value());
                return;
            }
            boolean cancelIntegration = integrationSource != null
                    && IntegrationRuntimeRoutes.taskCancel(req.getMethod(), req.getRequestURI());
            if (integrationSource != null && (cancelIntegration
                    || IntegrationRuntimeRoutes.taskRead(req.getMethod(), req.getRequestURI()))) {
                var call = IntegrationRuntimeRoutes.taskCall(serviceRoutes, req.getMethod(), req.getRequestURI(),
                        integrationSource.getClaimAsString("app_code"));
                byte[] body = cancelIntegration ? integrationCancelBody(req) : null;
                var ticket = integration.exchangeRuntime(req, call);
                res.setHeader("Cache-Control", "no-store");
                forward(req, res, call.target(), ticket.value(), body);
                return;
            }
            String evaluationTemplate = EvaluationCandidateBinding.portalTemplate(req.getMethod(), req.getRequestURI());
            if (evaluationTemplate != null) {
                evaluate(req, res, evaluationTemplate);
                return;
            }
            String buildTemplate = PlatformBuildRoutes.template(req.getMethod(), req.getRequestURI());
            if (buildTemplate != null) {
                var source = GatewayIngressBoundary.claimRuntime(req);
                var call = PlatformBuildRoutes.call(serviceRoutes, req.getMethod(), buildTemplate, source.subject());
                if (EvaluationCandidateBinding.publicationTemplate(buildTemplate)) {
                    String code = req.getRequestURI().split("/")[3];
                    call = new ServiceCall(call.target(), call.action(), EvaluationCandidateBinding.publication(code));
                }
                var client = authority.orElseThrow(() -> new PlatformException(
                        503, "DEPENDENCY_UNAVAILABLE", "门户构建授权服务暂不可用"));
                String capability = PlatformBuildRoutes.requiredCapability(req.getMethod(), buildTemplate);
                if (capability != null) {
                    if (platformAccess == null) throw new PlatformException(503, "PLATFORM_ACCESS_UNAVAILABLE", "平台授权暂不可用");
                    var lookup = PlatformBuildRoutes.call(serviceRoutes, "GET", PlatformBuildRoutes.SESSION, source.subject());
                    platformAccess.require(client.exchange(source.sourceToken(), lookup), source.subject(), capability);
                }
                var ticket = client.exchange(source.sourceToken(), call);
                forward(req, res, call.target(), ticket.value());
                return;
            }
            if (PortalRuntimeRoutes.matches(req.getMethod(), req.getRequestURI())) {
                var source = GatewayIngressBoundary.claimRuntime(req);
                var call = PortalRuntimeRoutes.call(serviceRoutes, req.getMethod(), req.getRequestURI());
                if ("proxy.engine.post.TaskController.create".equals(call.action())) {
                    var metadata = taskMetadata == null ? null : taskMetadata.getIfAvailable();
                    if (metadata == null)
                        throw new PlatformException(503, "DEPENDENCY_UNAVAILABLE", "应用发布查询暂不可用");
                    var version = metadata.lookup(source.sourceToken(), source.subject(), call.resource().id());
                    call = new ServiceCall(call.target(), call.action(), version.resource());
                }
                var ticket = authority.orElseThrow(() -> new PlatformException(
                        503, "DEPENDENCY_UNAVAILABLE", "门户运行授权服务暂不可用"))
                        .exchange(source.sourceToken(), call);
                forward(req, res, call.target(), ticket.value());
                return;
            }
            if("GET".equals(req.getMethod())&&req.getRequestURI().matches("/admin/runs/r_[0-9a-f]{32}/events")){
                var source=GatewayIngressBoundary.claimEvents(req,res);
                events.orElseThrow(()->new PlatformException(503,"RUN_EVENTS_UNAVAILABLE","运行事件暂不可用")).forward(req,res,source);
                return;
            }
            if("GET".equals(req.getMethod())&&req.getRequestURI().matches("/admin/runs/r_[0-9a-f]{32}")){
                var source=GatewayIngressBoundary.claimStatus(req);
                status.orElseThrow(()->new PlatformException(503,"RUN_STATUS_UNAVAILABLE","运行状态暂不可用")).forward(req,res,source);
                return;
            }
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
        forward(req, res, service, ServiceTokens.issue(actor));
    }

    private void forward(HttpServletRequest req, HttpServletResponse res, String service, String token)
            throws IOException {
        forward(req, res, service, token, null);
    }

    private static byte[] integrationCancelBody(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readNBytes(65);
        if (body.length > 64 || (body.length != 0
                && !new String(body, java.nio.charset.StandardCharsets.UTF_8).matches("\\s*\\{\\s*}\\s*"))) {
            throw new PlatformException(400, "TASK_REQUEST_INVALID", "集成任务中断仅接受空正文或空对象");
        }
        return new byte[]{'{', '}'};
    }

    private void evaluate(HttpServletRequest req, HttpServletResponse res, String template) throws IOException {
        String source = GatewayIngressBoundary.claimEvaluation(req);
        var route = serviceRoutes.require("gateway", "monitor", req.getMethod(), template);
        if ("GET".equals(req.getMethod())) {
            var resource = EvaluationCandidateBinding.ROUTE.equals(template)
                    ? EvaluationCandidateBinding.reports()
                    : EvaluationCandidateBinding.report(req.getRequestURI().split("/")[3]);
            forward(req, res, route.target(), evaluationTicket(source,
                    new ServiceCall(route.target(), route.action(), resource)));
            return;
        }
        int limit = requestLimit(req);
        byte[] body = req.getInputStream().readNBytes(limit + 1);
        if (body.length > limit) {
            throw new PlatformException(413, "PAYLOAD_TOO_LARGE", "评测请求超过大小限制");
        }
        JsonNode json;
        try {
            json = Json.mapper().readerFor(JsonNode.class)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(body);
        } catch (IOException malformed) {
            throw new PlatformException(400, "INVALID_INPUT", "评测请求 JSON 无效");
        }
        var resource = EvaluationCandidateBinding.from(json);
        forward(req, res, route.target(), evaluationTicket(source,
                new ServiceCall(route.target(), route.action(), resource)), body);
    }

    private String evaluationTicket(String source, ServiceCall call) {
        return authority.orElseThrow(() -> new PlatformException(
                503, "DEPENDENCY_UNAVAILABLE", "门户评测授权服务暂不可用"))
                .exchange(source, call).value();
    }

    private void forward(HttpServletRequest req, HttpServletResponse res, String service, String token, byte[] capturedBody)
            throws IOException {
        String query = req.getQueryString();
        String target = base(service) + req.getRequestURI() + (query == null ? "" : "?" + query);
        // R-9③④：转发核心由 HttpURLConnection 换 java.net.http.HttpClient——前者
        // setRequestMethod("PATCH") 直接 ProtocolException（缺陷③全 500）且 DELETE 无法携带
        // 请求体（缺陷④体被丢弃）；HttpClient 支持任意方法+请求体，PATCH/DELETE 与响应如实透传。
        // 有界流转发语义保持：请求体不缓存（BoundedInputStream 沿用原 requestLimit 额度）、
        // 响应体逐块透传（SSE 同路）；连接超时 5s、响应头超时 650s（原 readTimeout 同额度）。
        String method = req.getMethod();
        boolean hasBody = !Set.of("GET", "HEAD", "OPTIONS", "TRACE").contains(method);
        HttpRequest.Builder outbound =
                HttpRequest.newBuilder(URI.create(target))
                        .header("Authorization", "Bearer " + token)
                        .header(TraceContextFilter.HEADER, TraceContextFilter.current());
        if (capturedBody != null && IntegrationRuntimeRoutes.taskCancel(method, req.getRequestURI()))
            outbound.header("Content-Type", MediaType.APPLICATION_JSON_VALUE);
        else if (req.getContentType() != null) outbound.header("Content-Type", req.getContentType());
        String cursor = req.getHeader("Last-Event-ID");
        if (cursor != null) outbound.header("Last-Event-ID", cursor);
        outbound.timeout(Duration.ofMillis(650000));
        HttpRequest request;
        if (capturedBody != null) {
            request = outbound.method(method, HttpRequest.BodyPublishers.ofByteArray(capturedBody)).build();
        } else if (hasBody) {
            InputStream body = new BoundedInputStream(req.getInputStream(), requestLimit(req));
            request = outbound.method(method, HttpRequest.BodyPublishers.ofInputStream(() -> body)).build();
        } else {
            request = outbound.method(method, HttpRequest.BodyPublishers.noBody()).build();
        }
        try {
            HttpResponse<InputStream> upstream =
                    UPSTREAM_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = upstream.statusCode();
            res.setStatus(status);
            String contentType = upstream.headers().firstValue("Content-Type").orElse(null);
            if (contentType != null) res.setContentType(contentType);
            String upstreamTrace = upstream.headers().firstValue(TraceContextFilter.HEADER).orElse(null);
            if (upstreamTrace != null) res.setHeader(TraceContextFilter.HEADER, upstreamTrace);
            for (String header : List.of("Content-Disposition", "ETag", "Retry-After")) {
                String value = upstream.headers().firstValue(header).orElse(null);
                if (value != null) res.setHeader(header, value);
            }
            if (Objects.toString(contentType, "").contains("text/event-stream")) {
                res.setHeader("Cache-Control", "no-cache");
                res.setHeader("X-Accel-Buffering", "no");
            }
            try (InputStream input = upstream.body()) {
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
        }
    }

    /** R-9③④：共享上游客户端——连接建立 5s 上限（原 connectTimeout 同额度）。 */
    private static final HttpClient UPSTREAM_CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofMillis(5000)).build();

    /** 请求体有界读（原 copyBounded 同额度与语义：超限 IOException→502，不缓存整体）。 */
    private static final class BoundedInputStream extends FilterInputStream {
        private long total;
        private final int max;

        BoundedInputStream(InputStream in, int max) {
            super(in);
            this.max = max;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0 && ++total > max) throw new IOException("请求超过安全大小");
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = in.read(buffer, offset, length);
            if (n > 0) {
                total += n;
                if (total > max) throw new IOException("请求超过安全大小");
            }
            return n;
        }
    }

    private static int requestLimit(HttpServletRequest req) {
        if ("POST".equals(req.getMethod()) && "/admin/artifacts".equals(req.getRequestURI())
                && req.getContentType() != null) {
            try {
                var type = MediaType.parseMediaType(req.getContentType());
                // 64MiB 文件另受下游校验；仅此入口预留 multipart framing，总请求仍逐字节有界。
                if ("multipart".equals(type.getType()) && "form-data".equals(type.getSubtype()))
                    return 65 * 1024 * 1024;
            } catch (IllegalArgumentException ignored) {
                // 畸形或不匹配的媒体类型沿用普通请求额度，不扩大其它入口。
            }
        }
        return 2 * 1024 * 1024;
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
