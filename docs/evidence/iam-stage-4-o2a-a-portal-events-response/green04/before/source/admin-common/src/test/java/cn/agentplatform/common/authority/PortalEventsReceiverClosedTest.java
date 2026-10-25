package cn.agentplatform.common.authority;

import cn.agentplatform.common.PlatformErrors;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.assertj.core.api.Assertions.assertThat;

/** Real Servlet mapped receiver, with a sentinel handler; no engine SQL/SSE success is claimed. */
class PortalEventsReceiverClosedTest {
    static LoopbackAuthority iam;
    static final AtomicInteger handlers = new AtomicInteger();
    static final AtomicInteger frames = new AtomicInteger();

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableWebSecurity
    static class Receiver {
        @Bean(destroyMethod = "close")
        HttpCurrentAuthorityClient current() { return iam.client("engine"); }

        @Bean
        JwtDecoder decoder() { return new TrustedJwtDecoder(iam.config("engine"), iam.clock, true); }

        @Bean
        ServiceAuthorization authorization(HttpCurrentAuthorityClient current) {
            return new ServiceAuthorization("engine", new ServiceRoutePolicy(), current);
        }

        @Bean
        SecurityFilterChain security(HttpSecurity http, JwtDecoder decoder,
                ServiceAuthorization authorization) throws Exception {
            return cn.agentplatform.common.SecurityConfiguration.serviceSecurity(http, decoder, authorization);
        }

        @Bean
        org.springframework.web.servlet.handler.MappedInterceptor mapped(ServiceAuthorization authorization) {
            return authorization.mappedInterceptor();
        }

        @Bean
        EventsController controller() { return new EventsController(); }

        @Bean
        PlatformErrors errors() { return new PlatformErrors(); }

        @Bean
        FilterRegistrationBean<jakarta.servlet.Filter> frameProbe() {
            var registration = new FilterRegistrationBean<jakarta.servlet.Filter>();
            registration.setFilter((request, response, chain) -> {
                try { chain.doFilter(request, response); }
                finally {
                    if (request.getAttribute(ServiceAuthorization.class.getName() + ".frame") != null) {
                        frames.incrementAndGet();
                    }
                    if (org.assertj.core.api.Assertions.catchThrowable(AuthorityContext::current) == null) {
                        frames.incrementAndGet();
                    }
                }
            });
            registration.setOrder(Integer.MAX_VALUE);
            return registration;
        }
    }

    @RestController
    static class EventsController {
        @GetMapping({"/admin/runs/{id}/events", "/admin/debug-runs/{id}/events", "/v1/runs/{id}/events"})
        String events() {
            handlers.incrementAndGet();
            return "sentinel";
        }
    }

    @Test
    void realSignedEventsRemainClosedBeforeOnlineAuthorizeFrameAndEveryAliasHandler() throws Exception {
        handlers.set(0);
        frames.set(0);
        try (var fixture = new LoopbackAuthority()) {
            iam = fixture;
            PortalEventsAuthorityProfileTest.setup(iam);
            try (var gateway = iam.client("gateway")) {
                String ticket = gateway.exchange(iam.portal(120), PortalEventsAuthorityProfileTest.call()).value();
                var app = new SpringApplication(Receiver.class);
                app.setAdditionalProfiles("test");
                app.setDefaultProperties(Map.of("server.address", "127.0.0.1", "server.port", "0",
                        "spring.main.banner-mode", "off", "spring.config.location", "optional:classpath:/o2a-no-config/",
                        "spring.jmx.enabled", false, "springdoc.api-docs.enabled", false, "logging.level.root", "ERROR"));
                try (var server = (ServletWebServerApplicationContext) app.run();
                        var client = HttpClient.newHttpClient()) {
                    int authorityCalls = iam.authorityCalls();
                    for (String prefix : List.of("/admin/runs/", "/admin/debug-runs/", "/v1/runs/")) {
                        request(client, server, prefix + PortalEventsAuthorityProfileTest.RUN + "/events", ticket, "GET");
                    }
                    for (String method : List.of("HEAD", "POST", "PUT", "DELETE", "OPTIONS")) {
                        request(client, server, "/admin/runs/" + PortalEventsAuthorityProfileTest.RUN + "/events", ticket, method);
                    }
                    request(client, server, "/admin/runs/r_" + "b".repeat(32) + "/events", ticket, "GET");
                    request(client, server, "/admin/runs/" + PortalEventsAuthorityProfileTest.RUN, ticket, "GET");
                    request(client, server, "/not-mapped", ticket, "GET");
                    assertThat(iam.authorityCalls()).isEqualTo(authorityCalls);
                    assertThat(iam.observedOperations()).containsExactly("exchange");
                    assertThat(handlers).hasValue(0);
                    assertThat(frames).hasValue(0);
                }
            }
        }
        System.out.println("O2A_RECEIVER_SERVLET_AND_HTTP_CLOSED=true");
    }

    private static void request(HttpClient client, ServletWebServerApplicationContext server,
            String route, String ticket, String method) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                        + server.getWebServer().getPort() + route)).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + ticket)
                .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(method + " " + route).isEqualTo(403);
    }
}
