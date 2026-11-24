package cn.agentplatform.gateway.infra;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Digest;
import cn.agentplatform.common.ApiResponse;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.authority.CoverageGate;
import cn.agentplatform.common.authority.CurrentAuthorityClient;
import cn.agentplatform.common.authority.LoopbackAuthority;
import cn.agentplatform.common.authority.MetadataSelectors;
import cn.agentplatform.common.authority.ResourceBinding;
import cn.agentplatform.common.authority.ServiceCall;
import cn.agentplatform.common.authority.ServiceRoutePolicy;
import cn.agentplatform.common.authority.ServiceTrustChain;
import cn.agentplatform.gateway.GatewayApplication;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

import java.net.URI;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-43 phase 2 group B: the gateway must run the two-chain layout against real IAM material, keep the
 * portal surface on chain 2 only, take the SSE ticket from the {@code Authorization} header only, and
 * still answer the legacy dev-token route with 404.
 *
 * <p>A real HTTP port is used instead of MockMvc on purpose: the portal ingress is a container filter
 * ordered after the security chain and the SSE claim depends on async dispatch. A MockMvc call would
 * skip exactly the ordering this test is meant to pin.
 */
class GatewayIamPortalHttpTest {

    private static LoopbackAuthority iam;
    private static ServletWebServerApplicationContext app;
    private static HttpServer engine;
    private static final String VERSION_SHA = Digest.sha256("r36-published-version");
    private static String base;

    @BeforeAll
    static void boot() throws Exception {
        iam = new LoopbackAuthority();
        iam.portalActor(new Actor("tenant-a", "alice", Set.of("USER"),
                Map.of("region", Set.of("A"), "dataLabels", Set.of("confidential"))));
        engine = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        engine.createContext("/v1/apps/ops/", GatewayIamPortalHttpTest::respondAsEngine);
        engine.createContext("/internal/apps/ops/published-metadata", GatewayIamPortalHttpTest::respondAsApp);
        engine.createContext("/internal/platform-authorization/session", GatewayIamPortalHttpTest::respondWithAccess);
        engine.createContext("/admin/platform-authorization/grants", GatewayIamPortalHttpTest::respondAsGrantService);
        engine.createContext("/admin/apps", request -> {
            try (request) {
                request.sendResponseHeaders(403, -1);
            }
        });
        engine.start();
        var deployment = properties();
        app = (ServletWebServerApplicationContext)
                new SpringApplicationBuilder(TestAuthorityClient.class, GatewayApplication.class)
                        .profiles("test")
                        // The deployment values must be visible to the early-startup bean factory
                        // post processor that validates agent.security, so they go in as the highest
                        // precedence source instead of a default property (which the file-based
                        // application.yml source would shadow).
                        .initializers(context -> context.getEnvironment()
                                .getPropertySources()
                                .addFirst(new MapPropertySource("gateway-iam-test", deployment)))
                        .run();
        base = "http://127.0.0.1:" + app.getWebServer().getPort();
    }

    @AfterAll
    static void shutdown() {
        if (app != null) app.close();
        if (engine != null) engine.stop(0);
        if (iam != null) iam.close();
    }

    /**
     * Only the fake authority client differs from production wiring: the deployment still binds the full
     * {@code agent.security} tree and the same secrets reference, so a missing or inconsistent portal
     * block would fail startup here instead of at the first portal request.
     */
    @Configuration(proxyBeanMethods = false)
    static class TestAuthorityClient {
        @Bean
        CurrentAuthorityClient gatewayAuthorityClient() {
            return iam.client("gateway");
        }

        /**
         * The fixture signs material against its own fixed instant; production wires the system clock.
         * Sharing the fixture clock keeps "now" inside the ticket's validity window instead of testing
         * expiry, which the fixture's lifecycle tests already cover.
         */
        @Bean
        Clock gatewayTestAuthorityClock() {
            return iam.clock();
        }
    }

    private static Map<String, Object> properties() {
        var values = new LinkedHashMap<String, Object>();
        values.put("server.port", "0");
        values.put("spring.flyway.enabled", "false");
        values.put("agent.dev-auth", "false");
        values.put("agent.security.mode", "iam");
        values.put("agent.security.service-id", "gateway");
        values.put("agent.security.portal.enabled", "true");
        values.put("agent.security.portal.issuer", iam.issuer() + "/portal");
        values.put("agent.security.portal.jwks-uri", iam.issuer() + "/portal-jwks");
        values.put("agent.security.portal.audience", "portal-gateway");
        values.put("agent.security.portal.client-id", "portal-web");
        values.put("agent.security.portal.role-allowlist", "ADMIN,EDITOR,USER");
        values.put("agent.services.engine", "http://127.0.0.1:" + engine.getAddress().getPort());
        values.put("agent.services.app", "http://127.0.0.1:" + engine.getAddress().getPort());
        // attribute-value-allowlist 是 Map<String,Set<String>>：没有 String→Map 的转换器，而方括号/
        // 点号记法都会被适配成小写键（datalabels），与 camelCase 白名单键集不再相等并报未绑定。
        // 因此键名只留在 application.yml（YAML 保留大小写），测试与生产一样只注入取值。
        values.put("AGENT_PORTAL_ATTRIBUTE_REGION", "A,B");
        values.put("AGENT_PORTAL_ATTRIBUTE_DATA_LABELS", "confidential");
        values.put("agent.security.portal.scope-allowlist", "platform.access");
        values.put("agent.security.authority.issuer", iam.issuer());
        values.put("agent.security.authority.jwks-uri", iam.issuer() + "/jwks");
        values.put("agent.security.authority.token-uri", iam.issuer() + "/token");
        values.put("agent.security.authority.decision-uri", iam.issuer() + "/decision");
        values.put("agent.security.authority.client-id", "gateway");
        values.put("agent.security.authority.client-secret-ref", "env:AGENT_SECRET_IAM_GATEWAY_CLIENT");
        values.put("agent.security.authority.allowed-algorithm", "RS256");
        values.put("agent.security.authority.audience", "urn:agent-platform:service:gateway");
        // The deployment reads the secret from the environment; the test profile supplies the same
        // property so the startup gate sees a present value without exporting anything.
        values.put("AGENT_SECRET_IAM_GATEWAY_CLIENT", iam.testClientSecret("gateway"));
        values.put("AGENT_JWT_SECRET", "test-only-gateway-secret-0123456789abcdef");
        values.put("AGENT_DB_PASSWORD", "test-only-db-password");
        return values;
    }

    @Test
    void internalSurfaceRunsOnTheServiceTrustChainAndPortalSurfaceOnTheGeneralChain() {
        var chains = app.getBeansOfType(SecurityFilterChain.class).values().stream().toList();
        var trust = chains.stream()
                .filter(ServiceTrustChain.class::isInstance)
                .map(ServiceTrustChain.class::cast)
                .toList();
        assertThat(trust).as("exactly one /internal chain").hasSize(1);
        var chain = trust.get(0);
        // Lower order means "consulted first"; the general chain keeps the Boot default.
        assertThat(chain.getOrder()).isLessThan(0);
        assertThat(chain.matches(request("POST", "/internal/iam/policy"))).isTrue();
        assertThat(chain.matches(request("GET", "/admin/apps/app-a/runs"))).isFalse();
        assertThat(chain.matches(request("GET", "/auth/session"))).isFalse();

        var general = chains.stream().filter(c -> !(c instanceof ServiceTrustChain)).toList();
        assertThat(general).hasSize(1);
        // 通用链来自被扫描的公共配置，它可能同样命中 /internal/**；真正保证门户票进不了链 1 的是
        // 链 1 的 order 更小（上一处断言），因此这里钉住顺序语义而不钉“通用链不命中”。
        assertThat(general.get(0).matches(request("GET", "/admin/apps/app-a/runs"))).isTrue();
        assertThat(general.get(0).matches(request("GET", "/auth/session"))).isTrue();
    }

    @Test
    void portalTicketPassesTheAdminSurface() throws Exception {
        var response = send(HttpRequest.newBuilder(URI.create(base + "/admin/apps/app-a/runs"))
                .header("Authorization", "Bearer " + iam.portal())
                .POST(HttpRequest.BodyPublishers.ofString("{}")));
        // Reaching the controller proves authentication and authorisation passed; the 503 is the
        // published-run proxy being absent from this minimal deployment, not an auth rejection.
        assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
        assertThat(response.body()).contains("PUBLISHED_RUN_UNAVAILABLE");
    }

    @Test
    void adminSurfaceWithoutTicketIs401() throws Exception {
        var response = send(HttpRequest.newBuilder(URI.create(base + "/admin/apps/app-a/runs"))
                .POST(HttpRequest.BodyPublishers.ofString("{}")));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(401);
        assertThat(response.body()).contains("SYS-AUTH-401");
    }

    @Test
    void serviceTicketForAnotherAudienceIs401() throws Exception {
        var claims = iam.base("service", "urn:agent-platform:service:app", 60);
        claims.put("sub", "app");
        var response = get("/auth/session", iam.sign(claims, iam.authoritySigningKey(), "at+jwt"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(401);
        assertThat(response.body()).contains("SYS-AUTH-401");
    }

    @Test
    void ticketWithUserUseButServiceAudienceIs401() throws Exception {
        // Mirror image of the audience case: the trust domain is chosen by iss+aud, so a user ticket
        // signed by the service authority must be refused instead of authenticating as a service.
        var claims = iam.base("user", "urn:agent-platform:service:gateway", 60);
        claims.put("sub", "alice");
        var response = get("/auth/session", iam.sign(claims, iam.authoritySigningKey(), "at+jwt"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(401);
        assertThat(response.body()).contains("SYS-AUTH-401");
    }

    @Test
    void portalSessionReturnsTheExchangedIdentity() throws Exception {
        var response = get("/auth/session", iam.portal());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        var identity = Json.read(response.body()).path("data");
        assertThat(identity.path("tenantId").asText()).isEqualTo("tenant-a");
        assertThat(identity.path("roles").get(0).asText()).isEqualTo("BUILDER");
        assertThat(identity.path("capabilities").get(0).asText()).isEqualTo("agent.build");
        assertThat(identity.path("pages").get(0).asText()).isEqualTo("/apps");
        assertThat(response.body()).doesNotContain("access_token");
    }

    private static void respondWithAccess(com.sun.net.httpserver.HttpExchange request) throws java.io.IOException {
        try (request; var authority = iam.client("app")) {
            var subject = new cn.agentplatform.common.authority.SubjectKey("tenant-a", "alice");
            var call = cn.agentplatform.common.authority.PlatformBuildRoutes.call(
                    new ServiceRoutePolicy(), "GET", "/internal/platform-authorization/session", subject);
            String bearer = request.getRequestHeaders().getFirst("Authorization");
            var actor = authority.authorize(bearer.substring("Bearer ".length()), call).actor();
            var result = Map.of("tenantId", actor.tenantId(), "principalId", actor.principalId(),
                    "roles", List.of("BUILDER"), "capabilities", List.of("agent.build"), "pages", List.of("/apps"));
            byte[] body = Json.write(ApiResponse.ok(result)).getBytes(StandardCharsets.UTF_8);
            request.getResponseHeaders().set("Content-Type", "application/json");
            request.sendResponseHeaders(200, body.length);
            request.getResponseBody().write(body);
        }
    }

    @Test
    void platformGrantWritesUseThePortalSubjectAndPreserveTheOriginalRequest() throws Exception {
        String path = "/admin/platform-authorization/grants";
        String body = "{\"subject\":\"operator\",\"role\":\"BUILDER\",\"appScope\":[\"ops\"]}";
        for (String method : List.of("PUT", "DELETE")) {
            var result = send(HttpRequest.newBuilder(URI.create(base + path))
                    .header("Authorization", "Bearer " + iam.portal())
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body)));
            assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
            assertThat(result.body()).isEqualTo(method + " tenant-a/alice " + body);
        }
    }

    private static void respondAsGrantService(com.sun.net.httpserver.HttpExchange request) throws java.io.IOException {
        try (request; var authority = iam.client("app")) {
            var subject = new cn.agentplatform.common.authority.SubjectKey("tenant-a", "alice");
            String method = request.getRequestMethod();
            var call = cn.agentplatform.common.authority.PlatformBuildRoutes.call(
                    new ServiceRoutePolicy(), method, "/admin/platform-authorization/grants", subject);
            var bearer = request.getRequestHeaders().getFirst("Authorization");
            var actor = authority.authorize(bearer.substring("Bearer ".length()), call).actor();
            String body = new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] result = (method + " " + actor.tenantId() + "/" + actor.principalId() + " " + body)
                    .getBytes(StandardCharsets.UTF_8);
            request.sendResponseHeaders(200, result.length);
            request.getResponseBody().write(result);
        }
    }

    @Test
    void portalSessionWithoutTicketIs401() throws Exception {
        var response = get("/auth/session", null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(401);
        assertThat(response.body()).contains("SYS-AUTH-401");
    }

    @Test
    void devTokenIsNotFoundInIamMode() throws Exception {
        var response = get("/auth/dev-token", null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
        assertThat(response.body()).contains("NOT_FOUND");
    }

    @Test
    void portalTicketCannotEnterTheInternalSurface() throws Exception {
        var response = send(HttpRequest.newBuilder(URI.create(base + "/internal/iam/policy"))
                .header("Authorization", "Bearer " + iam.portal())
                .POST(HttpRequest.BodyPublishers.ofString("{}")));
        // Chain 1 wraps its decoder in a service-domain check, so a browser ticket is refused there
        // rather than being authenticated and then dropped by the absent internal handler.
        assertThat(response.statusCode()).as(response.body()).isEqualTo(401);
    }

    @Test
    void queryStringTicketIsRejectedOnTheSseSurface() throws Exception {
        var response = get("/admin/runs/r_0123456789abcdef0123456789abcdef/events?access_token=leaked",
                iam.portal());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(response.body()).contains("PORTAL_TICKET_SOURCE_INVALID");
    }

    @Test
    void queryStringTicketIsRejectedEvenWithoutAnAuthorizationHeader() throws Exception {
        // 第一阶段遗留的缺口：只带 ?access_token= 的 SSE 订阅必须被拒绝，且拒绝方式固定为 400
        // （过滤器在安全链之前判定票来源，避免把"票放错位置"误报成"票无效"的 401）。
        var response = get("/admin/runs/r_0123456789abcdef0123456789abcdef/events?access_token=leaked",
                null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(response.body()).contains("PORTAL_TICKET_SOURCE_INVALID");
    }

    @Test
    void coverageGateFindsNoUncoveredInternalHandler() {
        var mappings = app.getBeansOfType(RequestMappingInfoHandlerMapping.class).values();
        var missing = CoverageGate.missingInternal("gateway", new ServiceRoutePolicy(), mappings);
        assertThat(missing).isEmpty();
        // Gateway intentionally exposes no /internal handler yet; the assertion above is therefore
        // vacuous today and becomes meaningful the moment one is added without a catalog row.
        var interceptors = app.getBeansOfType(MappedInterceptor.class).values();
        assertThat(interceptors).as("portal ingress interceptor registered once").hasSize(1);
    }

    // ---- R-34：portal ingress /v1 用户面形状 ------------------------------------------------------

    /**
     * R-34 形状卡：票源过滤器与 ingress 边界两个注册的 URL 形状同步扩 {@code /v1/apps/*}（用户面：
     * 会话/任务列表/记忆），且只扩这一个前缀——deploy/runs/sessions 非用户面不入 portal 形状。
     */
    @Test
    void v1AppsUserPlaneIsInsideBothPortalIngressRegistrationsAndNothingElse() {
        var source = (FilterRegistrationBean<?>) app.getBean("gatewayPortalTicketSource");
        var ingress = (FilterRegistrationBean<?>) app.getBean("gatewayPortalIngress");
        for (var registration : List.of(source, ingress)) {
            assertThat(registration.getUrlPatterns())
                    .contains("/admin/*", "/auth/session", "/v1/apps/*");
            assertThat(registration.getUrlPatterns())
                    .doesNotContain("/v1/deploy/*", "/v1/runs/*", "/v1/sessions/*", "/v1/tasks/*");
        }
    }

    /** R-34：票源过滤器的 portalSurface 判定扩 {@code /v1/apps/} 前缀，且仅此前缀。 */
    @Test
    void portalSurfaceShapeCoversTheV1AppsPrefixOnly() {
        assertThat(PortalTicketSourceFilter.portalSurface("/v1/apps/ops/sessions")).isTrue();
        assertThat(PortalTicketSourceFilter.portalSurface("/v1/apps/ops/memories")).isTrue();
        assertThat(PortalTicketSourceFilter.portalSurface("/v1/apps/ops")).isTrue();
        assertThat(PortalTicketSourceFilter.portalSurface("/v1/appsx/ops")).isFalse();
        assertThat(PortalTicketSourceFilter.portalSurface("/v1/deploy/resource-manifest")).isFalse();
        assertThat(PortalTicketSourceFilter.portalSurface("/v1/runs/r_1")).isFalse();
        assertThat(PortalTicketSourceFilter.portalSurface("/v1/sessions/s_1/subagents")).isFalse();
        assertThat(PortalTicketSourceFilter.portalSurface("/v1/tasks/77/plans")).isFalse();
    }

    /** R-34：查询串票据在 /v1/apps 用户面上同样以 400 拒绝（票源判定先于安全链）。 */
    @Test
    void queryStringTicketIsRejectedOnTheV1AppsUserPlane() throws Exception {
        var response = get("/v1/apps/ops/sessions?access_token=leaked", null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(response.body()).contains("PORTAL_TICKET_SOURCE_INVALID");
    }

    /** R-34 负向：非用户面前缀不因形状卡入票源判定——/v1/runs 带查询串票据走安全链语义（无票 401）。 */
    @Test
    void queryStringTicketIsNotPortalRejectedOutsideTheV1AppsUserPlane() throws Exception {
        var response = get("/v1/runs/r_0123456789abcdef0123456789abcdef?access_token=leaked", null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(401);
        assertThat(response.body()).contains("SYS-AUTH-401");
    }

    @Test
    void portalTicketOnV1AppsSessionsPreservesQueryAndExchangesForEngineTicket() throws Exception {
        var response = get("/v1/apps/ops/sessions?mine=true", iam.portal());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("tenant-a/alice GET /v1/apps/ops/sessions?mine=true ");
    }

    @Test
    void userTicketReachesAllRegisteredRuntimeCollections() throws Exception {
        for (String collection : List.of("sessions", "tasks", "memories")) {
            String path = "/v1/apps/ops/" + collection;
            var list = get(path, iam.portal());
            assertThat(list.statusCode()).as(path + ": " + list.body()).isEqualTo(200);
            assertThat(list.body()).isEqualTo("tenant-a/alice GET " + path + " ");
            var create = send(HttpRequest.newBuilder(URI.create(base + path))
                    .header("Authorization", "Bearer " + iam.portal())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"requestId\":\"r35\"}")));
            assertThat(create.statusCode()).as(path + ": " + create.body()).isEqualTo(200);
            assertThat(create.body()).isEqualTo("tenant-a/alice POST " + path + " {\"requestId\":\"r35\"}");
        }
    }

    @Test
    void userRuntimeEntryPreservesCredentialAndPathBoundaries() throws Exception {
        String path = "/v1/apps/ops/tasks";
        assertThat(get(path, null).statusCode()).isEqualTo(401);
        assertThat(get(path, iam.portal(-20)).statusCode()).isEqualTo(401);
        assertThat(get(path, "forged.token.value").statusCode()).isEqualTo(401);
        var service = iam.base("service", "urn:agent-platform:service:app", 60);
        service.put("sub", "app");
        assertThat(get(path, iam.sign(service, iam.authoritySigningKey(), "at+jwt")).statusCode())
                .isBetween(401, 403);
        for (String header : List.of("X-User-Id", "X-User-Roles", "X-User-Attributes", "X-User-Tenant")) {
            var response = send(HttpRequest.newBuilder(URI.create(base + path))
                    .header("Authorization", "Bearer " + iam.portal())
                    .header(header, "override")
                    .GET());
            assertThat(response.statusCode()).as(header + ": " + response.body()).isEqualTo(403);
        }
        for (String unavailable : List.of("/v1/apps/ops/runs", "/v1/apps/ops/tasks/detail",
                "/v1/deploy/resource-manifest", "/v1/runs/r_0123456789abcdef0123456789abcdef",
                "/v1/sessions/session-1")) {
            assertThat(get(unavailable, iam.portal()).statusCode()).as(unavailable).isEqualTo(403);
        }
        var write = send(HttpRequest.newBuilder(URI.create(base + "/admin/apps"))
                .header("Authorization", "Bearer " + iam.portal())
                .POST(HttpRequest.BodyPublishers.ofString("{}")));
        assertThat(write.statusCode()).as(write.body()).isEqualTo(403);
    }

    private static void respondAsEngine(com.sun.net.httpserver.HttpExchange request) throws java.io.IOException {
        try (request; var authority = iam.client("engine")) {
            String method = request.getRequestMethod();
            String path = request.getRequestURI().getPath();
            String template = path.replace("/ops/", "/{code}/");
            var registered = new ServiceRoutePolicy().require("gateway", "engine", method, template);
            var call = new ServiceCall(registered.target(), registered.action(),
                    "proxy.engine.post.TaskController.create".equals(registered.action())
                            ? new ResourceBinding("app-version", "ops@1", VERSION_SHA)
                            : new ResourceBinding("app-runtime", "ops", Digest.sha256("portal-runtime.v1:ops")));
            String bearer = request.getRequestHeaders().getFirst("Authorization");
            var verified = authority.authorize(bearer.substring("Bearer ".length()), call);
            var actor = verified.actor();
            String body = new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var bytes = (actor.tenantId() + "/" + actor.principalId() + " " + method + " "
                    + request.getRequestURI() + " " + body).getBytes(StandardCharsets.UTF_8);
            request.sendResponseHeaders(200, bytes.length);
            request.getResponseBody().write(bytes);
        }
    }

    private static void respondAsApp(com.sun.net.httpserver.HttpExchange request) throws java.io.IOException {
        try (request; var authority = iam.client("app")) {
            String bearer = request.getRequestHeaders().getFirst("Authorization");
            var call = new ServiceCall("app", MetadataSelectors.ACTION, MetadataSelectors.published("tenant-a", "ops"));
            authority.authorize(bearer.substring("Bearer ".length()), call);
            byte[] bytes = Json.write(ApiResponse.ok(Map.of(
                    "appCode", "ops", "versionId", 1, "versionNo", 1, "versionSha256", VERSION_SHA)))
                    .getBytes(StandardCharsets.UTF_8);
            request.getResponseHeaders().set("Content-Type", "application/json");
            request.sendResponseHeaders(200, bytes.length);
            request.getResponseBody().write(bytes);
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }

    private static HttpResponse<String> get(String path, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Accept", "application/json")
                .GET();
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return send(builder);
    }

    private static HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(
                    builder.header("Accept", "application/json").build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }
}
