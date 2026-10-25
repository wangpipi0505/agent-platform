package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.cors.CorsConfigurationSource;

/** Independent finite review probe. Uses frozen target classes; never reads business data. */
public class Stage2ReviewProbe {
    static final Instant NOW = Instant.parse("2026-10-20T00:00:00Z");
    static LoopbackIssuer issuer;
    static final AtomicInteger handlers = new AtomicInteger();
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration @EnableWebSecurity
    static class ProbeApp {
        @Bean JwtDecoder decoder() {
            return new TrustedJwtDecoder(issuer.internalConfig(), Clock.fixed(NOW, ZoneOffset.UTC), true);
        }
        @Bean SecurityFilterChain filter(HttpSecurity http, JwtDecoder decoder,
                ObjectProvider<ApiKeyAuthenticator> keys) throws Exception {
            var method = SecurityConfiguration.class.getDeclaredMethod("security", HttpSecurity.class,
                    JwtDecoder.class, CorsConfigurationSource.class, ObjectProvider.class);
            method.setAccessible(true);
            return (SecurityFilterChain) method.invoke(new SecurityConfiguration(), http, decoder,
                    (CorsConfigurationSource) request -> null, keys);
        }
        @Bean ProbeController probe() { return new ProbeController(); }
    }
    @RestController static class ProbeController {
        @GetMapping("/internal/review") Object get() { handlers.incrementAndGet(); return Map.of("ok", true); }
    }
    public static void main(String[] args) throws Exception {
        try (var local = new LoopbackIssuer()) {
            issuer = local;
            var base = local.config(); var p = base.portal();
            for (var role : List.of("EDITOR", "ops", "SERVICE")) {
                var portal = new AuthorityProperties.Portal(true, p.issuer(), p.jwksUri(), p.audience(),
                        p.clientId(), Set.of(role), p.attributeAllowlist(), p.attributeValueAllowlist(), p.scopeAllowlist());
                var config = new AuthorityProperties(base.mode(), base.serviceId(), portal, base.authority(),
                        base.integration(), base.jwtSecret(), base.issuer(), base.allowEmbed(), base.corsOrigins());
                try { config.validate(true); System.out.println("ROLE " + role + " ACCEPTED"); }
                catch (IllegalStateException e) { System.out.println("ROLE " + role + " " + e.getMessage()); }
            }
            try (var decoder = new TrustedJwtDecoder(base, Clock.fixed(NOW, ZoneOffset.UTC), true)) {
                var claims = local.portal(NOW);
                claims.put("scope", String.join(" ", Collections.nCopies(2500, "a")));
                var token = local.sign(claims);
                System.out.println("BOUNDED_SCOPE tokenChars=" + token.length() + " scopeChars=" + ((String)claims.get("scope")).length());
                try { decoder.decode(token); System.out.println("BOUNDED_SCOPE ACCEPTED"); }
                catch (Throwable e) { System.out.println("BOUNDED_SCOPE " + e.getClass().getName()); }
            }
            var app = new SpringApplication(ProbeApp.class); app.setAdditionalProfiles("test");
            app.setDefaultProperties(Map.of("server.address","127.0.0.1","server.port","0","spring.main.banner-mode","off",
                    "spring.config.location","optional:classpath:/stage2-review-no-config/","spring.jmx.enabled",false,
                    "springdoc.api-docs.enabled",false,"logging.level.root","ERROR"));
            try (var context = (ServletWebServerApplicationContext)app.run(); var client = HttpClient.newHttpClient()) {
                var uri = URI.create("http://127.0.0.1:" + context.getWebServer().getPort() + "/internal/review");
                var good = local.service(NOW, false);
                send(client, uri, "valid-internal-still-closed", local.sign(good));
                var forbidden = new LinkedHashMap<>(good);
                forbidden.put("action", "business.delete"); forbidden.put("scope", "business.delete");
                send(client, uri, "signed-unregistered-action", local.sign(forbidden));
                var narrowed = new LinkedHashMap<>(good); narrowed.put("scope", "unrelated");
                send(client, uri, "signed-insufficient-scope", local.sign(narrowed));
                send(client, uri, "wrong-signature-control", local.sign(good, local.k2, "k1", "at+jwt"));
            }
        }
    }
    static void send(HttpClient client, URI uri, String label, String token) throws Exception {
        var response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        System.out.println("HTTP " + label + " status=" + response.statusCode()
                + " code=" + Json.read(response.body()).path("code").asText() + " handlers=" + handlers.get());
    }
}
