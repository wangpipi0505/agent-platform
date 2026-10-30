package cn.agentplatform.skill.infra;

import cn.agentplatform.common.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class IdentityMappingOutboundTest {
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = SkillClients.class, useDefaultFilters = false, includeFilters = {
            @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = SkillClients.class),
            @ComponentScan.Filter(type = FilterType.REGEX, pattern = "cn\\.agentplatform\\.skill\\.infra\\.IdentityMappingClient")})
    static class Wiring {
        @Bean ServiceTickets tickets() { return new ServiceTickets(ServiceTickets.LEGACY); }
        @Bean SkillAdapters adapters() { return new SkillAdapters(mock(ArtifactStore.class), mock(AlgorithmAdapter.class)); }
        @Bean HttpAsyncAdapter async() { return new HttpAsyncAdapter(null); }
    }

    @Test void syncAndAsyncResolveCurrentMappingBeforeBusinessAndNeverUseAStaleOrMissingMapping() throws Exception {
        String old = System.getProperty("agent.jwt.secret");
        var previous = SecurityContextHolder.getContext().getAuthentication();
        var actor = new Actor("ex23-skill-" + UUID.randomUUID(), "alice", Set.of("EDITOR", "SERVICE"), Map.of());
        var jwt = Jwt.withTokenValue("verified-service-fixture").header("alg", "HS256").subject("alice")
                .claim("tenant_id", actor.tenantId()).claim("roles", actor.roles()).claim("token_use", "service").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        System.setProperty("agent.jwt.secret", "ex23-skill-independent-key-0123456789abcdef");
        var env = new org.springframework.mock.env.MockEnvironment().withProperty("agent.security.mode", "dev-hmac")
                .withProperty("agent.security.service-id", "skill").withProperty("agent.dev-auth", "true").withProperty("server.address", "127.0.0.1");
        SecurityConfiguration.bindDeployment(env);
        var reads = new AtomicInteger(); var sends = new AtomicInteger(); var missing = new AtomicBoolean();
        var injected = new AtomicReference<String>();
        var peer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        peer.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            boolean lookup = exchange.getRequestURI().getPath().equals("/internal/identity-mappings/current");
            Map<String, Object> body; int status = 200;
            if (lookup) {
                reads.incrementAndGet();
                String header = exchange.getRequestHeaders().getFirst("Authorization");
                // 实际服务票据使用已登记密钥验签；头里的用户值不参与主体选择。
                var decoder = org.springframework.security.oauth2.jwt.NimbusJwtDecoder.withSecretKey(
                        new javax.crypto.spec.SecretKeySpec(ServiceTokens.secretBytes(), "HmacSHA256")).build();
                var verified = decoder.decode(header.substring(7));
                if (!actor.tenantId().equals(verified.getClaimAsString("tenant_id")) || !"alice".equals(verified.getSubject()))
                    throw new AssertionError("unexpected verified subject");
                if (missing.get()) { status = 404; body = Map.of("code", "IDENTITY_MAPPING_NOT_FOUND", "message", "主体映射不存在"); }
                else body = Map.of("code", "OK", "data", Map.of("tenantId", actor.tenantId(), "principalId", "alice",
                        "mappingKey", actor.subject().mappingKey(), "revision", 1, "subject", actor.subject()));
            } else {
                sends.incrementAndGet(); injected.set(exchange.getRequestHeaders().getFirst("X-User-Id"));
                body = Map.of("code", "OK", "data", Map.of("ok", true));
            }
            byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); } finally { exchange.close(); }
        });
        peer.start(); String base = "http://127.0.0.1:" + peer.getAddress().getPort();
        try {
            new ApplicationContextRunner().withUserConfiguration(Wiring.class).withPropertyValues("agent.services.app=" + base).run(context -> {
                var contract = new LinkedHashMap<String, Object>(Map.of("endpoint", base + "/business", "httpMethod", "POST", "authScheme", "NONE",
                        "timeoutMs", 2000, "outputSchema", Map.of("type", "object"), "outputMaxBytes", 4096,
                        "execution", Map.of("readonly", true, "sideEffect", false),
                        "identity", Map.of("mode", "PLATFORM_SUBJECT", "headers", Map.of("X-User-Id", "principalId"))));
                var adapter = context.getBean(SkillAdapters.class);
                adapter.http(contract, Map.of(), "ex23-key", 23, "r-ex23", 1);
                assertThat(reads).hasValue(1); assertThat(sends).hasValue(1); assertThat(injected.get()).isEqualTo("alice");
                context.getBean(HttpAsyncAdapter.class).request(contract, base + "/business", "GET", null, "ex23-async").retrieve().toBodilessEntity();
                assertThat(reads).hasValue(2); assertThat(sends).hasValue(2);
                missing.set(true);
                assertThatThrownBy(() -> adapter.http(contract, Map.of(), "ex23-key", 23, "r-ex23", 1))
                        .isInstanceOfSatisfying(SkillAdapters.Failure.class, e -> {
                            assertThat(e.semantic).isEqualTo("NO_PERMISSION"); assertThat(e.code).isEqualTo("IDENTITY_MAPPING_NOT_FOUND");
                            assertThat(e.error("ex23-trace").get("retryable")).isEqualTo(false);
                        });
                assertThatThrownBy(() -> context.getBean(HttpAsyncAdapter.class).request(contract, base + "/business", "GET", null, "ex23-async"))
                        .isInstanceOf(SkillAdapters.Failure.class);
                assertThat(sends).hasValue(2);
                contract.remove("identity");
                adapter.http(contract, Map.of(), "ex23-key", 23, "r-ex23", 1);
                assertThat(sends).hasValue(3); assertThat(reads).hasValue(4); assertThat(injected.get()).isNull();
            });
        } finally {
            peer.stop(0); SecurityContextHolder.getContext().setAuthentication(previous);
            if (old == null) System.clearProperty("agent.jwt.secret"); else System.setProperty("agent.jwt.secret", old);
        }
    }
}
