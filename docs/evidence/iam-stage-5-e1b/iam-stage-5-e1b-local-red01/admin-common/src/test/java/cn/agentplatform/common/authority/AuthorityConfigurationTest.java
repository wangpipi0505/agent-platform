package cn.agentplatform.common.authority;

import cn.agentplatform.common.SecurityConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.annotation.*;
import org.springframework.context.support.GenericApplicationContext;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class AuthorityConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(SecurityConfiguration.class)
    static class TestApp {}

    static Map<String, Object> completeIam() {
        var p = new LinkedHashMap<String, Object>();
        p.put("agent.security.mode", "iam");
        p.put("agent.security.service-id", "engine");
        p.put("agent.security.authority.client-id", "engine");
        p.put("agent.security.authority.issuer", "https://authority.invalid");
        p.put("agent.security.authority.jwks-uri", "https://authority.invalid/jwks");
        p.put("agent.security.authority.token-uri", "https://authority.invalid/token");
        p.put("agent.security.authority.decision-uri", "https://authority.invalid/decision");
        p.put("agent.security.authority.client-secret-ref", "env:AGENT_SECRET_IAM_ENGINE_CLIENT");
        p.put("agent.security.authority.allowed-algorithm", "RS256");
        p.put("agent.security.authority.audience", "urn:agent-platform:service:engine");
        return p;
    }
    record Invalid(String name, Map<String, Object> properties, String reason) {
        @Override public String toString() { return name; }
    }
    static Stream<Invalid> invalidConfigurations() {
        var cases = new ArrayList<Invalid>();
        cases.add(new Invalid("no-mode", Map.of(), "SECURITY_MODE_REQUIRED"));
        cases.add(new Invalid("unknown-mode", Map.of("agent.security.mode", "production"), "SECURITY_MODE_INVALID"));
        cases.add(new Invalid("complete-iam-still-closed", completeIam(), "IAM_TRUST_CHAIN_UNAVAILABLE"));
        for (String key : List.of("issuer", "jwks-uri", "token-uri", "decision-uri", "client-secret-ref", "client-id", "allowed-algorithm", "audience")) {
            var p = completeIam(); p.remove("agent.security.authority." + key);
            cases.add(new Invalid("missing-" + key, p, "IAM_CONFIG_REQUIRED"));
        }
        for (var entry : Map.<String, Object>of("agent.dev-auth", true, "agent.security.portal.enabled", true,
                "agent.security.authority.client-id", "app", "agent.security.service-id", "other").entrySet()) {
            var p = completeIam(); p.put(entry.getKey(), entry.getValue());
            cases.add(new Invalid(entry.getKey(), p,
                    entry.getKey().equals("agent.dev-auth") ? "IAM_DEV_AUTH_FORBIDDEN" :
                    entry.getKey().equals("agent.security.portal.enabled") ? "PORTAL_SERVICE_FORBIDDEN" :
                    entry.getKey().equals("agent.security.service-id") ? "SERVICE_ID_INVALID" : "IAM_CLIENT_SERVICE_MISMATCH"));
        }
        var badUrl = completeIam(); badUrl.put("agent.security.authority.token-uri", "http://authority.invalid/token");
        cases.add(new Invalid("insecure-iam-url", badUrl, "IAM_URI_INVALID"));
        for (var entry : Map.<String, Object>of(
                "agent.security.authority.allowed-algorithm", "HS256",
                "agent.security.authority.audience", "urn:agent-platform:service:app",
                "agent.security.authority.client-secret-ref", "env:AGENT_SECRET_IAM_APP_CLIENT",
                "agent.security.integration.enabled", true).entrySet()) {
            var p = completeIam(); p.put(entry.getKey(), entry.getValue());
            cases.add(new Invalid(entry.getKey(), p,
                    entry.getKey().endsWith("allowed-algorithm") ? "IAM_ALGORITHM_INVALID" :
                    entry.getKey().endsWith("audience") ? "IAM_AUDIENCE_INVALID" :
                    entry.getKey().endsWith("client-secret-ref") ? "IAM_SECRET_REF_INVALID" : "PORTAL_SERVICE_FORBIDDEN"));
        }
        for (var entry : Map.of("max-ticket-seconds", 61, "max-decision-seconds", 6,
                "connect-timeout-ms", 1001, "read-timeout-ms", 2001).entrySet()) {
            for (int value : List.of(0, entry.getValue())) {
                var p = completeIam(); p.put("agent.security.authority." + entry.getKey(), value);
                cases.add(new Invalid(entry.getKey() + "=" + value, p, "IAM_BUDGET_INVALID"));
            }
        }
        var userInfo = completeIam(); userInfo.put("agent.security.authority.jwks-uri", "https://user@authority.invalid/jwks");
        cases.add(new Invalid("userinfo-uri", userInfo, "IAM_URI_INVALID"));
        cases.add(new Invalid("dev-without-dev-auth", Map.of("agent.security.mode", "dev-hmac"), "DEV_AUTH_REQUIRED"));
        cases.add(new Invalid("dev-non-loopback", Map.of("agent.security.mode", "dev-hmac", "agent.dev-auth", true, "server.address", "0.0.0.0"), "LOOPBACK_BIND_REQUIRED"));
        cases.add(new Invalid("dev-engine-cannot-enable-legacy-integration", Map.of("agent.security.mode", "dev-hmac", "agent.dev-auth", true, "agent.security.allow-embed", true), "PORTAL_SERVICE_FORBIDDEN"));
        cases.add(new Invalid("test-without-profile", Map.of("agent.security.mode", "test"), "TEST_PROFILE_REQUIRED"));
        var typo = completeIam(); typo.put("agent.security.authority.decison-uri", "https://authority.invalid/typo");
        cases.add(new Invalid("unknown-property", typo, "left unbound"));
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}") @MethodSource("invalidConfigurations")
    void illegalConfigurationFailsBeforeCreatingListener(Invalid invalid) {
        var factories = new AtomicInteger();
        var app = application(invalid.properties(), factories);
        assertThatThrownBy(() -> { try (var ignored = app.run()) {} })
                .hasStackTraceContaining(invalid.reason());
        assertThat(factories).as("no web server construction or listener on rejected configuration").hasValue(0);
    }

    static Stream<String> developmentModes() { return Stream.of("dev-hmac", "test"); }

    @ParameterizedTest @MethodSource("developmentModes")
    void explicitDevelopmentModesKeepLegacyDecoder(String mode) {
        var factories = new AtomicInteger();
        try (var context = application(Map.of("agent.security.mode", mode, "agent.dev-auth", true, "stage1.test-profile", mode.equals("test")), factories).run()) {
            assertThat(context.isActive()).isTrue();
            assertThat(factories).hasValue(1);
            var decoder = context.getBean(org.springframework.security.oauth2.jwt.JwtDecoder.class);
            assertThat(decoder.decode(signed("agent-platform", "user")).getSubject()).isEqualTo("test-principal");
            assertThat(decoder.decode(signed("agent-platform-internal", "service")).getClaimAsString("token_use")).isEqualTo("service");
            assertThatThrownBy(() -> decoder.decode(signed("agent-platform-internal", "user")))
                    .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
            assertThatThrownBy(() -> decoder.decode(signed("agent-platform-embed", "integration")))
                    .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
        }
    }

    @Test void explicitGatewayDevPreservesIntegration() {
        try (var context = application(Map.of("agent.security.mode", "dev-hmac", "agent.dev-auth", true,
                "agent.security.service-id", "gateway", "agent.security.allow-embed", true), new AtomicInteger()).run()) {
            assertThat(context.getBean(org.springframework.security.oauth2.jwt.JwtDecoder.class)
                    .decode(signed("agent-platform-embed", "integration")).getClaimAsString("token_use"))
                    .isEqualTo("integration");
        }
    }

    @Test void explicitTestProfileMayUseOnlyLiteralLoopbackTrustEndpointsAndIamStaysClosed() {
        var p=completeIam();p.replaceAll((k,v)->v instanceof String s?s.replace("https://authority.invalid","http://127.0.0.1:12345"):v);
        p.put("stage1.test-profile",true);
        var factories=new AtomicInteger();
        assertThatThrownBy(()->{try(var ignored=application(p,factories).run()){}}).hasStackTraceContaining("IAM_TRUST_CHAIN_UNAVAILABLE");
        assertThat(factories).hasValue(0);
        p.put("agent.security.authority.jwks-uri","http://localhost:12345/jwks");
        assertThatThrownBy(()->{try(var ignored=application(p,factories).run()){}}).hasStackTraceContaining("IAM_URI_INVALID");
        assertThat(factories).hasValue(0);
    }

    @Test void portalCeilingsAreRequiredAndStrictlyBoundByTheDeploymentBinder() {
        var p=completeIam();p.put("agent.security.service-id","gateway");
        p.put("agent.security.authority.client-id","gateway");p.put("agent.security.authority.client-secret-ref","env:AGENT_SECRET_IAM_GATEWAY_CLIENT");
        p.put("agent.security.authority.audience","urn:agent-platform:service:gateway");
        p.put("agent.security.portal.enabled",true);p.put("agent.security.portal.issuer","https://portal.invalid");
        p.put("agent.security.portal.jwks-uri","https://portal.invalid/jwks");p.put("agent.security.portal.audience","portal-gateway");
        p.put("agent.security.portal.client-id","portal-web");p.put("agent.security.portal.role-allowlist[0]","EDITOR");
        p.put("agent.security.portal.attribute-allowlist[0]","region");
        var factories=new AtomicInteger();
        assertThatThrownBy(()->{try(var ignored=application(p,factories).run()){}}).hasStackTraceContaining("PORTAL_CONFIG_REQUIRED");
        p.put("agent.security.portal.attribute-value-allowlist.region[0]","A");p.put("agent.security.portal.scope-allowlist[0]","platform.access");
        assertThatThrownBy(()->{try(var ignored=application(p,factories).run()){}}).hasStackTraceContaining("IAM_TRUST_CHAIN_UNAVAILABLE");
        p.put("agent.security.portal.role-allowlist[0]","SERVICE");
        assertThatThrownBy(()->{try(var ignored=application(p,factories).run()){}}).hasStackTraceContaining("PORTAL_CEILING_INVALID");
        assertThat(factories).hasValue(0);
    }

    @Test void configuredDomainBusinessRolesAreAcceptedAndReservedRolesRemainForbidden() throws Exception {
        try (var issuer = new LoopbackIssuer()) {
            var c = issuer.config(); var p = c.portal();
            for (var roles : List.of(Set.of("ops"), Set.of("VIEWER"), Set.of("ops", "EDITOR"))) {
                var portal = new AuthorityProperties.Portal(true, p.issuer(), p.jwksUri(), p.audience(), p.clientId(),
                        roles, p.attributeAllowlist(), p.attributeValueAllowlist(), p.scopeAllowlist());
                assertThatCode(() -> new AuthorityProperties(c.mode(), c.serviceId(), portal, c.authority(),
                        null, null, null, false, null).validate(true)).doesNotThrowAnyException();
            }
            for (String role : List.of("SERVICE", "GATEWAY_AUTH", "", " ")) {
                var portal = new AuthorityProperties.Portal(true, p.issuer(), p.jwksUri(), p.audience(), p.clientId(),
                        Set.of(role), p.attributeAllowlist(), p.attributeValueAllowlist(), p.scopeAllowlist());
                assertThatThrownBy(() -> new AuthorityProperties(c.mode(), c.serviceId(), portal, c.authority(),
                        null, null, null, false, null).validate(true)).hasMessage("PORTAL_CEILING_INVALID");
            }
        }
    }

    private static String signed(String audience, String use) {
        try {
            var jwt = new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.HS256),
                    new com.nimbusds.jwt.JWTClaimsSet.Builder().issuer("agent-platform-v12").audience(audience)
                    .subject("test-principal").claim("tenant_id", "test-tenant").claim("token_use", use)
                    .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(30))).build());
            jwt.sign(new com.nimbusds.jose.crypto.MACSigner("stage1-test-only-012345678901234567890123456789"));
            return jwt.serialize();
        } catch (Exception ex) { throw new AssertionError(ex); }
    }

    private static SpringApplication application(Map<String, Object> properties, AtomicInteger factories) {
        var defaults = new HashMap<String, Object>();
        defaults.put("agent.security.service-id", "engine");
        defaults.put("server.address", "127.0.0.1"); defaults.put("server.port", "0");
        defaults.put("spring.config.location", "optional:classpath:/iam-stage-1-no-config/");
        defaults.put("spring.main.banner-mode", "off"); defaults.put("logging.level.root", "ERROR");
        defaults.put("spring.jmx.enabled", false);
        defaults.put("springdoc.api-docs.enabled", false);
        defaults.put("agent.security.jwt-secret", "stage1-test-only-012345678901234567890123456789");
        defaults.putAll(properties);
        var app = new SpringApplication(TestApp.class);
        if (Boolean.TRUE.equals(properties.get("stage1.test-profile"))) app.setAdditionalProfiles("test");
        app.setWebApplicationType(WebApplicationType.SERVLET);
        app.setDefaultProperties(defaults);
        app.addInitializers(context -> ((GenericApplicationContext) context).registerBean(
                "stage1WebServerFactory", TomcatServletWebServerFactory.class,
                () -> new TomcatServletWebServerFactory() {
                    @Override public WebServer getWebServer(ServletContextInitializer... initializers) {
                        factories.incrementAndGet(); return super.getWebServer(initializers);
                    }
                }));
        return app;
    }
}
