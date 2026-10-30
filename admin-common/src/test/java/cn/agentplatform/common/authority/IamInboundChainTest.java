package cn.agentplatform.common.authority;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.cors.CorsConfigurationSource;

import cn.agentplatform.common.ApiKeyAuthenticator;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.SecurityConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-43 §3.1/§3.6: exercises the real servlet chain. iam closes {@code /internal/**} on the
 * application chain, hides {@code /auth/dev-token} behind 404, keeps only actuator health open,
 * and never folds an authority outage (503) into 401/403.
 */
class IamInboundChainTest {
    static LoopbackIssuer issuer;

    @BeforeAll
    static void startIssuer() throws Exception {
        issuer = new LoopbackIssuer();
    }

    @AfterAll
    static void stopIssuer() {
        issuer.close();
    }

    @Test
    void iamApplicationChainAllowsHealthDeniesInternalAndHidesDevToken() throws Exception {
        try (var app = start(IamApplicationChain.class)) {
            assertThat(get(app, "/actuator/health", null).status()).isEqualTo(200);
            assertThat(get(app, "/actuator/env", null).status())
                    .as("only health actuator endpoints stay open")
                    .isEqualTo(401);

            var devToken = get(app, "/auth/dev-token", null);
            assertThat(devToken.status()).as("iam must not expose the legacy dev-token endpoint").isEqualTo(404);
            assertThat(devToken.body()).doesNotContain("dev-token");

            var anonymous = get(app, "/internal/probe", null);
            assertThat(anonymous.status()).isEqualTo(401);
            assertThat(anonymous.body()).contains("SYS-AUTH-401");

            String ticket = issuer.sign(issuer.service(Instant.now(), false));
            var authenticatedButDenied = get(app, "/internal/probe", ticket);
            assertThat(authenticatedButDenied.status())
                    .as("chain 2 must deny /internal/** even for a valid service ticket").isEqualTo(403);
            assertThat(authenticatedButDenied.body()).contains("SYS-AUTH-403");

            assertThat(get(app, "/admin/probe", ticket).status())
                    .as("a service ticket is not a portal user token").isEqualTo(403);
        }
    }

    @Test
    void expiredOrWrongAudienceTicketsAreRejectedAsUnauthorized() throws Exception {
        try (var app = start(IamApplicationChain.class)) {
            String expired = issuer.sign(issuer.service(Instant.now().minusSeconds(600), false));
            assertThat(get(app, "/internal/probe", expired).status()).isEqualTo(401);

            var claims = issuer.service(Instant.now(), false);
            claims.put("aud", List.of("urn:agent-platform:service:gateway"));
            assertThat(get(app, "/internal/probe", issuer.sign(claims)).status()).isEqualTo(401);

            var wrongIssuer = issuer.service(Instant.now(), false);
            wrongIssuer.put("iss", issuer.issuer() + "/somewhere-else");
            assertThat(get(app, "/internal/probe", issuer.sign(wrongIssuer)).status()).isEqualTo(401);
        }
    }

    @Test
    void authorityOutageStays503AndIsNotReportedAs401Or403() throws Exception {
        try (var app = start(UnavailableAuthorityChain.class)) {
            var response = get(app, "/internal/probe", "header.payload.signature");
            assertThat(response.status()).isEqualTo(503);
            assertThat(response.body()).contains("IAM_AUTHORITY_UNAVAILABLE");
        }
    }

    @Test
    void devHmacKeepsTheLegacyDevTokenAndHealthBehaviour() throws Exception {
        try (var app = start(DevHmacChain.class)) {
            assertThat(get(app, "/auth/dev-token", null).status()).isEqualTo(200);
            assertThat(get(app, "/actuator/health", null).status()).isEqualTo(200);
            assertThat(get(app, "/actuator/env", null).status()).isEqualTo(401);
        }
    }

    @Test
    void internalChainRunsBeforeTheApplicationChainRegardlessOfBeanDeclarationOrder() throws Exception {
        try (var app = start(DualChainReceiver.class)) {
            String ticket = issuer.sign(issuer.service(Instant.now(), false));
            assertThat(get(app, "/internal/probe", ticket).status())
                    .as("chain 1 authenticates /internal/** before chain 2 can deny it").isEqualTo(200);
            assertThat(get(app, "/public/ping", ticket).status())
                    .as("non-internal paths still belong to chain 2").isEqualTo(401);
        }
    }

    // --- application chain fixture -----------------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableWebSecurity
    static class IamApplicationChain {
        @Bean AuthorityProperties deployment() { return issuer.internalConfig(); }

        @Bean JwtDecoder decoder(AuthorityProperties deployment) { return AuthorityDecoders.iam(deployment); }

        @Bean CorsConfigurationSource cors() { return request -> null; }

        @Bean SecurityFilterChain security(HttpSecurity http, JwtDecoder decoder,
                @Qualifier("cors") CorsConfigurationSource cors,
                ObjectProvider<ApiKeyAuthenticator> apiKeys, AuthorityProperties deployment) throws Exception {
            return new SecurityConfiguration().security(http, decoder, cors, apiKeys, deployment);
        }

        @Bean ProbeController probeController() { return new ProbeController(); }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableWebSecurity
    static class UnavailableAuthorityChain {
        @Bean AuthorityProperties deployment() { return issuer.internalConfig(); }

        @Bean JwtDecoder decoder() {
            return token -> {
                throw new PlatformException(503, "IAM_AUTHORITY_UNAVAILABLE", "可信授权服务暂不可用");
            };
        }

        @Bean CorsConfigurationSource cors() { return request -> null; }

        @Bean SecurityFilterChain security(HttpSecurity http, JwtDecoder decoder,
                @Qualifier("cors") CorsConfigurationSource cors,
                ObjectProvider<ApiKeyAuthenticator> apiKeys, AuthorityProperties deployment) throws Exception {
            return new SecurityConfiguration().security(http, decoder, cors, apiKeys, deployment);
        }

        @Bean ProbeController probeController() { return new ProbeController(); }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableWebSecurity
    static class DevHmacChain {
        @Bean AuthorityProperties deployment() {
            return new AuthorityProperties("dev-hmac", "engine", null, null, null,
                    "stage1-test-only-012345678901234567890123456789", null, false, null);
        }

        @Bean JwtDecoder decoder() {
            var decoder = org.springframework.security.oauth2.jwt.NimbusJwtDecoder
                    .withSecretKey(new javax.crypto.spec.SecretKeySpec(
                            "stage1-test-only-012345678901234567890123456789"
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            "HmacSHA256"))
                    .macAlgorithm(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256)
                    .build();
            decoder.setJwtValidator(org.springframework.security.oauth2.jwt.JwtValidators
                    .createDefaultWithIssuer(cn.agentplatform.common.ServiceTokens.ISSUER));
            return decoder;
        }

        @Bean CorsConfigurationSource cors() { return request -> null; }

        @Bean SecurityFilterChain security(HttpSecurity http, JwtDecoder decoder,
                @Qualifier("cors") CorsConfigurationSource cors,
                ObjectProvider<ApiKeyAuthenticator> apiKeys, AuthorityProperties deployment) throws Exception {
            return new SecurityConfiguration().security(http, decoder, cors, apiKeys, deployment);
        }

        @Bean ProbeController probeController() { return new ProbeController(); }
    }

    /** Declares chain 2 first on purpose: only the Ordered wrapper can keep chain 1 ahead of it. */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableWebSecurity
    static class DualChainReceiver {
        @Bean AuthorityProperties deployment() { return issuer.internalConfig(); }

        @Bean @Qualifier("applicationDecoder") JwtDecoder applicationDecoder() {
            return token -> {
                throw new BadJwtException("APPLICATION_CHAIN_REACHED");
            };
        }

        @Bean @Qualifier("serviceDecoder") JwtDecoder serviceDecoder(AuthorityProperties deployment) {
            return AuthorityDecoders.iam(deployment);
        }

        @Bean CorsConfigurationSource cors() { return request -> null; }

        @Bean SecurityFilterChain applicationChain(HttpSecurity http,
                @Qualifier("applicationDecoder") JwtDecoder decoder,
                @Qualifier("cors") CorsConfigurationSource cors,
                ObjectProvider<ApiKeyAuthenticator> apiKeys, AuthorityProperties deployment) throws Exception {
            return new SecurityConfiguration().security(http, decoder, cors, apiKeys, deployment);
        }

        @Bean ServiceTrustChain serviceTrustChain(HttpSecurity http,
                @Qualifier("serviceDecoder") JwtDecoder decoder, ServiceAuthorization receiver) throws Exception {
            return SecurityConfiguration.serviceTrustChain(http, decoder, receiver);
        }

        @Bean ServiceAuthorization receiver() {
            return new ServiceAuthorization("app", IamTestEnvironments.routes(), noAuthorityClient());
        }

        @Bean ProbeController probeController() { return new ProbeController(); }
    }

    static CurrentAuthorityClient noAuthorityClient() {
        return new CurrentAuthorityClient() {
            @Override public ServiceTicket client(ServiceCall call) { throw new UnsupportedOperationException(); }
            @Override public ServiceTicket exchange(String token, ServiceCall call) { throw new UnsupportedOperationException(); }
            @Override public ServiceTicket resume(DelegationRef ref, ServiceCall call) { throw new UnsupportedOperationException(); }
            @Override public ServiceTicket forward(VerifiedAuthority authority, ServiceCall call) { throw new UnsupportedOperationException(); }
            @Override public VerifiedAuthority authorize(String token, ServiceCall call) { throw new UnsupportedOperationException(); }
            @Override public WorkloadAuthority authorizeWorkload(String token, ServiceCall call) { throw new UnsupportedOperationException(); }
            @Override public VerifiedAuthority approver(DelegationRef ref, SubjectKey subject, ResourceBinding resource) { throw new UnsupportedOperationException(); }
        };
    }

    @RestController
    static class ProbeController {
        @GetMapping("/actuator/health") Map<String, String> health() { return Map.of("status", "UP"); }
        @GetMapping("/auth/dev-token") Map<String, String> devToken() { return Map.of("token", "dev-token"); }
        @GetMapping("/internal/probe") Map<String, String> internal() { return Map.of("probe", "internal"); }
        @GetMapping("/admin/probe") Map<String, String> admin() { return Map.of("probe", "admin"); }
        @GetMapping("/public/ping") String ping() { return "pong"; }
    }

    // --- helpers -----------------------------------------------------------------------------------

    record Response(int status, String body) {}

    static ServletWebServerApplicationContext start(Class<?> type) {
        var defaults = new HashMap<String, Object>();
        defaults.put("server.address", "127.0.0.1");
        defaults.put("server.port", "0");
        defaults.put("spring.main.banner-mode", "off");
        defaults.put("logging.level.root", "ERROR");
        defaults.put("spring.jmx.enabled", false);
        defaults.put("springdoc.api-docs.enabled", false);
        defaults.put("spring.config.location", "optional:classpath:/ex43-no-config/");
        var app = new SpringApplication(type);
        app.setDefaultProperties(defaults);
        app.setWebApplicationType(WebApplicationType.SERVLET);
        return (ServletWebServerApplicationContext) app.run();
    }

    static Response get(ServletWebServerApplicationContext app, String path, String token) throws Exception {
        var builder = HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:" + app.getWebServer().getPort() + path))
                .timeout(Duration.ofSeconds(5))
                .GET();
        if (token != null) builder.header("Authorization", "Bearer " + token);
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        }
    }
}
