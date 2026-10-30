package cn.agentplatform.engine.infra;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import com.sun.net.httpserver.HttpServer;

import cn.agentplatform.common.ApiKeyAuthenticator;
import cn.agentplatform.common.PlatformErrors;
import cn.agentplatform.common.SecurityConfiguration;
import cn.agentplatform.common.authority.AuthorityDecoders;
import cn.agentplatform.common.authority.AuthorityProperties;
import cn.agentplatform.common.authority.CoverageGate;
import cn.agentplatform.common.authority.LoopbackAuthority;
import cn.agentplatform.common.authority.ServiceAuthorization;
import cn.agentplatform.common.authority.ServiceRoutePolicy;
import cn.agentplatform.common.authority.ServiceTrustChain;
import cn.agentplatform.common.authority.WorkloadCredentialResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EX-43 phase 2 group A: engine-runtime iam inbound chain, exercised through the real servlet stack.
 *
 * <p>The deployment is the shared loopback authority fixture whose decision endpoint is redirected to a
 * local stub, so both the 5xx and the timeout branches of {@code BoundedIamHttp} are observed as 503
 * instead of being folded into 401/403. Tickets are minted far in the future relative to the fixture
 * clock because the fixture clock is pinned at 2026-10-20 while the shared decoder validates expiry
 * against the wall clock.
 */
class IamInboundTrustChainTest {

    static final String SERVICE_ID = "engine";
    static final String TEMPLATE = "/internal/approvals";
    static final String PATH = "/internal/approvals";
    static final String ACTION = "approval.list";
    static final String KIND = "approval";

    static LoopbackAuthority fixture;
    static HttpServer decision;
    static AtomicReference<String> decisionMode;
    static AtomicInteger decisionCalls;

    @BeforeAll
    static void startFixtures() throws Exception {
        fixture = new LoopbackAuthority();
        // The fixture clock is pinned at 2026-10-20; move it onto the wall clock so one ticket is valid
        // for the shared decoder (wall clock) and for the authority client (fixture clock) at once.
        fixture.advanceClock(Duration.ofSeconds(Duration.between(fixture.clock().instant(), Instant.now()).getSeconds()));
        decisionMode = new AtomicReference<>("500");
        decisionCalls = new AtomicInteger();
        decision = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        decision.setExecutor(Executors.newCachedThreadPool());
        decision.createContext("/decision", exchange -> {
            decisionCalls.incrementAndGet();
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                String mode = decisionMode.get();
                if ("slow".equals(mode)) {
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders("slow".equals(mode) ? 200 : 500, body.length);
                exchange.getResponseBody().write(body);
            } catch (IOException closedByClientTimeout) {
                // the service already answered 503; nothing left to write
            }
        });
        decision.start();
    }

    @AfterAll
    static void stopFixtures() {
        decision.stop(0);
        fixture.close();
    }

    @BeforeEach
    void resetDecision() {
        decisionMode.set("500");
        decisionCalls.set(0);
    }

    @Test
    void anInternalHandlerIsClosedWithoutAServiceTicket() throws Exception {
        try (var app = start(Receiver.class)) {
            var anonymous = get(app, PATH, null);
            assertThat(anonymous.status()).isEqualTo(401);
            assertThat(anonymous.body()).contains("SYS-AUTH-401");
        }
    }

    @Test
    void badTicketsStayUnauthorized() throws Exception {
        try (var app = start(Receiver.class)) {
            assertThat(get(app, PATH, "header.payload.signature").status()).isEqualTo(401);

            var expired = ticket("monitor", ACTION, "delegated", KIND);
            expired.put("exp", Instant.now().minusSeconds(600).getEpochSecond());
            assertThat(get(app, PATH, fixture.sign(expired, fixture.authoritySigningKey(), "at+jwt")).status())
                    .isEqualTo(401);

            var wrongAudience = ticket("monitor", ACTION, "delegated", KIND);
            wrongAudience.put("aud", "urn:agent-platform:service:monitor");
            assertThat(get(app, PATH, fixture.sign(wrongAudience, fixture.authoritySigningKey(), "at+jwt")).status())
                    .isEqualTo(401);

            var wrongIssuer = ticket("monitor", ACTION, "delegated", KIND);
            wrongIssuer.put("iss", fixture.issuer() + "/somewhere-else");
            assertThat(get(app, PATH, fixture.sign(wrongIssuer, fixture.authoritySigningKey(), "at+jwt")).status())
                    .isEqualTo(401);
        }
    }

    @Test
    void iamHidesTheLegacyDevTokenEndpoint() throws Exception {
        try (var app = start(Receiver.class)) {
            var response = get(app, "/auth/dev-token", null);
            assertThat(response.status()).isEqualTo(404);
            assertThat(response.body()).doesNotContain("dev-token");
        }
    }

    @Test
    void actuatorHealthStaysReachableInIam() throws Exception {
        try (var app = start(Receiver.class)) {
            assertThat(get(app, "/actuator/health", null).status()).isEqualTo(200);
        }
    }

    @Test
    void aCallerWithoutACatalogRowIsDeniedBeforeTheAuthorityIsCalled() throws Exception {
        try (var app = start(Receiver.class)) {
            var response = get(app, PATH, signed(ticket("gateway", ACTION, "delegated", KIND)));
            assertThat(response.status()).isEqualTo(403);
            assertThat(response.body()).contains("SYS-AUTH-403");
            assertThat(decisionCalls.get()).as("catalog denial precedes the decision call").isZero();
        }
    }

    @Test
    void aWorkloadTicketCannotRideADelegatedOnlyTemplate() throws Exception {
        try (var app = start(Receiver.class)) {
            var response = get(app, PATH, signed(ticket("monitor", ACTION, "workload", KIND)));
            assertThat(response.status()).isEqualTo(403);
            assertThat(response.body()).contains("SYS-AUTH-403");
            assertThat(decisionCalls.get()).isZero();
        }
    }

    @Test
    void anAuthorityDecisionOutageStays503() throws Exception {
        try (var app = start(Receiver.class)) {
            var response = get(app, PATH, signed(ticket("monitor", ACTION, "delegated", KIND)));
            assertThat(response.status()).isEqualTo(503);
            assertThat(response.body()).contains("IAM_AUTHORITY_UNAVAILABLE");
            assertThat(decisionCalls.get()).isEqualTo(1);
        }
    }

    @Test
    void anAuthorityTimeoutStays503() throws Exception {
        decisionMode.set("slow");
        try (var app = start(Receiver.class)) {
            var response = get(app, PATH, signed(ticket("monitor", ACTION, "delegated", KIND)));
            assertThat(response.status()).isEqualTo(503);
            assertThat(response.body()).contains("IAM_AUTHORITY_UNAVAILABLE");
            assertThat(decisionCalls.get()).isEqualTo(1);
        }
    }

    @Test
    void theGuardIsRegisteredOnTheMvcMappingAndCoveragePasses() throws Exception {
        try (var app = start(Receiver.class)) {
            var guard = app.getBean(ServiceAuthorization.class);
            var mapping = app.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
            var registered = java.util.Arrays.stream(mapping.getAdaptedInterceptors())
                    .filter(MappedInterceptor.class::isInstance)
                    .map(MappedInterceptor.class::cast)
                    .filter(interceptor -> interceptor.getIncludePathPatterns() == null
                            && interceptor.getExcludePathPatterns() == null)
                    .toList();
            assertThat(registered).anySatisfy(interceptor ->
                    assertThat(interceptor.getInterceptor()).isSameAs(guard));

            // Assembly self-check: the assembled handlers are covered by the shipped catalog, so the
            // startup gate that fails a real iam deployment passes for this wiring.
            CoverageGate.verify("iam", SERVICE_ID, new ServiceRoutePolicy(),
                    app.getBeansOfType(RequestMappingInfoHandlerMapping.class).values());
        }
    }

    @Test
    void coverageGateFailsClosedInIamAndOnlyWarnsInDevHmac() {
        var mappings = List.<RequestMappingInfoHandlerMapping>of(mappingFor("/internal/iam/uncovered"));
        assertThatThrownBy(() -> CoverageGate.verify("iam", SERVICE_ID, new ServiceRoutePolicy(), mappings))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ROUTE_COVERAGE_MISSING");
        CoverageGate.verify("dev-hmac", SERVICE_ID, new ServiceRoutePolicy(), mappings);
    }

    @Test
    void devHmacDeploymentsDoNotAssembleTheIamChain() {
        new ApplicationContextRunner()
                .withUserConfiguration(ServiceTrustChainConfiguration.class)
                .withPropertyValues("agent.security.mode=dev-hmac")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(ServiceTrustChain.class)).isEmpty();
                    assertThat(context.getBeansOfType(ServiceAuthorization.class)).isEmpty();
                });
    }

    // --- fixtures ----------------------------------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableWebSecurity
    @EnableConfigurationProperties(AuthorityProperties.class)
    @Import({ServiceTrustChainConfiguration.class, PlatformErrors.class})
    static class Receiver {

        @Bean
        JwtDecoder decoder(AuthorityProperties deployment) {
            return AuthorityDecoders.iam(deployment);
        }

        @Bean
        Clock clock() {
            return fixture.clock();
        }

        @Bean
        WorkloadCredentialResolver credentials() {
            return fixture.clientCredentials(SERVICE_ID);
        }

        @Bean
        CorsConfigurationSource cors() {
            return request -> null;
        }

        @Bean
        SecurityFilterChain security(HttpSecurity http, JwtDecoder decoder,
                @Qualifier("cors") CorsConfigurationSource cors,
                ObjectProvider<ApiKeyAuthenticator> apiKeys, AuthorityProperties deployment) throws Exception {
            return new SecurityConfiguration().security(http, decoder, cors, apiKeys, deployment);
        }

        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    @RestController
    static class ProbeController {
        @GetMapping(TEMPLATE)
        Map<String, Object> candidate(@PathVariable String code) {
            return Map.of("code", code);
        }

        @GetMapping("/auth/dev-token")
        Map<String, String> devToken() {
            return Map.of("token", "dev-token");
        }
    }

    /**
     * The shipped app deployment as real configuration properties: only the decision endpoint is
     * redirected to the local stub and the read budget is shortened so the timeout branch is reachable.
     */
    static Map<String, Object> iamProperties() {
        return Map.ofEntries(
                Map.entry("agent.security.mode", "iam"),
                Map.entry("agent.security.service-id", SERVICE_ID),
                Map.entry("agent.security.authority.issuer", fixture.issuer()),
                Map.entry("agent.security.authority.jwks-uri", fixture.issuer() + "/jwks"),
                Map.entry("agent.security.authority.token-uri", fixture.issuer() + "/token"),
                Map.entry("agent.security.authority.decision-uri",
                        "http://127.0.0.1:" + decision.getAddress().getPort() + "/decision"),
                Map.entry("agent.security.authority.client-id", SERVICE_ID),
                Map.entry("agent.security.authority.client-secret-ref", "env:AGENT_SECRET_IAM_ENGINE_CLIENT"),
                Map.entry("agent.security.authority.allowed-algorithm", "RS256"),
                Map.entry("agent.security.authority.audience", "urn:agent-platform:service:" + SERVICE_ID),
                Map.entry("agent.security.authority.max-ticket-seconds", "60"),
                Map.entry("agent.security.authority.max-decision-seconds", "5"),
                Map.entry("agent.security.authority.connect-timeout-ms", "1000"),
                Map.entry("agent.security.authority.read-timeout-ms", "500"));
    }

    /**
     * A service ticket that is valid for both clocks in play: the fixture clock (2026-10-20, used by the
     * authority client and the trusted decoder) and the wall clock (used by the shared iam decoder).
     */
    static Map<String, Object> ticket(String caller, String action, String mode, String kind) {
        var claims = fixture.base("service", "urn:agent-platform:service:" + SERVICE_ID, 50);
        claims.put("client_id", caller);
        claims.put("caller", caller);
        claims.put("target", SERVICE_ID);
        claims.put("sub", "svc-" + caller);
        claims.put("tenant_id", "tenant-a");
        claims.put("action", action);
        claims.put("authority_mode", mode);
        claims.put("resource", Map.of("kind", kind, "id", "hello", "version_digest", "v1"));
        claims.put("grant_ref", "grant-test-1");
        claims.put("authorization_version", "v1");
        // A retained service ticket is scoped to exactly its action; the trusted decoder enforces it.
        claims.put("scope", action);
        return claims;
    }

    static String signed(Map<String, Object> claims) {
        return fixture.sign(claims, fixture.authoritySigningKey(), "at+jwt");
    }

    static RequestMappingHandlerMapping mappingFor(String template) {
        var mapping = new RequestMappingHandlerMapping();
        try {
            mapping.registerMapping(
                    RequestMappingInfo.paths(template).methods(RequestMethod.GET).build(),
                    new UncoveredHandler(), UncoveredHandler.class.getMethod("call"));
        } catch (NoSuchMethodException error) {
            throw new AssertionError(error);
        }
        return mapping;
    }

    static final class UncoveredHandler {
        public void call() {}
    }

    record Response(int status, String body) {}

    static ServletWebServerApplicationContext start(Class<?> type) {
        var defaults = new HashMap<String, Object>();
        defaults.put("server.address", "127.0.0.1");
        defaults.put("server.port", "0");
        defaults.put("spring.main.banner-mode", "off");
        defaults.put("spring.profiles.active", "test");
        defaults.putAll(iamProperties());
        defaults.put("logging.level.root", "ERROR");
        defaults.put("spring.jmx.enabled", false);
        defaults.put("springdoc.api-docs.enabled", false);
        defaults.put("spring.config.location", "optional:classpath:/ex43a-no-config/");
        defaults.put("management.endpoints.web.exposure.include", "health");
        defaults.put("spring.autoconfigure.exclude", String.join(",",
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
                "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
                "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"));
        var app = new SpringApplication(type);
        app.setDefaultProperties(defaults);
        app.setWebApplicationType(WebApplicationType.SERVLET);
        return (ServletWebServerApplicationContext) app.run();
    }

    static Response get(ServletWebServerApplicationContext app, String path, String token) throws Exception {
        var builder = HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:" + app.getWebServer().getPort() + path))
                .timeout(Duration.ofSeconds(15))
                .GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        }
    }
}
