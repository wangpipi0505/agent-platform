package cn.agentplatform.skill.infra;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.authority.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/** EX26：Spring 实际装配与有限签名 HTTP peer；不表示外部 IAM 或全服务入站已联验。 */
class ServiceTicketAuthorityWiringTest {
    private static final Actor CLAIMED_ACTOR = new Actor("forged-tenant", "mallory", Set.of("ADMIN"), Map.of());
    private static final ResourceBinding RESOURCE = new ResourceBinding("run", "r-ex26", Digest.sha256("ex26-snapshot"));
    private static final String FACT_PATH = "/internal/tool-calls/tc-ex26/fact";

    // 按实际扫描发现新增配置；首轮无配置时必须是缺 Bean 的行为失败，不能以缺类编译失败冒充实红。
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = ServiceTickets.class, useDefaultFilters = false, includeFilters = {
            @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {ServiceTickets.class, SkillClients.class}),
            @ComponentScan.Filter(type = FilterType.REGEX, pattern = "cn\\.agentplatform\\.skill\\.infra\\.ServiceTicketConfiguration")})
    static class Wiring {}

    @Test
    void actualBeansForwardSignedTicketWithTrustedResourceAndCloseTheirHttpClient() throws Exception {
        try (var iam = new LoopbackAuthority(); var gateway = iam.client("gateway");
             var engine = iam.client("engine"); var peer = new EnginePeer(engine)) {
            var capturedClient = new AtomicReference<HttpCurrentAuthorityClient>();
            var capturedAuthority = new AtomicReference<VerifiedAuthority>();
            runner(iam, peer.url()).run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(CurrentAuthorityClient.class)
                        .hasSingleBean(ServiceTicketProvider.class).hasSingleBean(ServiceTickets.class);
                assertThat(context.getBean(ServiceTicketProvider.class)).isInstanceOf(IamServiceTicketProvider.class);
                var client = context.getBean(HttpCurrentAuthorityClient.class);
                capturedClient.set(client);
                var entry = entryCall();
                var current = client.authorize(gateway.exchange(iam.portal(), entry).value(), entry);
                capturedAuthority.set(current);
                try (var scope = AuthorityContext.bind(current)) {
                    var result = context.getBean(SkillClients.class).post(peer.url() + FACT_PATH, Map.of("state", "DONE"), CLAIMED_ACTOR);
                    assertThat(result.path("accepted").asBoolean()).isTrue();
                }
                assertThat(peer.sends).hasValue(1);
                assertThat(peer.verified.get().actor()).isEqualTo(current.actor()).isNotEqualTo(CLAIMED_ACTOR);
                assertThat(peer.verified.get().call()).isEqualTo(factCall());
                assertThat(peer.verified.get().operation()).isEqualTo(current.operation());
                assertThat(iam.observedOperations()).containsExactly("exchange", "authorize", "forward", "authorize");
            });
            // 容器销毁必须关闭其真实 client，不能留下一个仍能联网签发的票据入口。
            int attempts = iam.requestAttempts();
            assertFailure(() -> capturedClient.get().forward(capturedAuthority.get(), factCall()), 503, "IAM_AUTHORITY_UNAVAILABLE");
            assertThat(iam.requestAttempts()).isEqualTo(attempts);
        }
    }

    @Test
    void missingContextUnknownRouteAndRevocationFailBeforeBusinessTransportWithoutFallback() throws Exception {
        try (var iam = new LoopbackAuthority(); var gateway = iam.client("gateway");
             var engine = iam.client("engine"); var peer = new EnginePeer(engine)) {
            runner(iam, peer.url()).run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(ServiceTicketProvider.class)
                        .hasSingleBean(CurrentAuthorityClient.class);
                var clients = context.getBean(SkillClients.class);
                var client = context.getBean(CurrentAuthorityClient.class);
                int before = iam.requestAttempts();
                assertFailure(() -> clients.post(peer.url() + FACT_PATH, Map.of(), CLAIMED_ACTOR), 403, "AUTHORITY_CONTEXT_MISSING");
                assertThat(iam.requestAttempts()).isEqualTo(before);
                var entry = entryCall();
                var current = client.authorize(gateway.exchange(iam.portal(), entry).value(), entry);
                try (var scope = AuthorityContext.bind(current)) {
                    int authorized = iam.requestAttempts();
                    assertFailure(() -> clients.post(peer.url() + FACT_PATH + "/extra", Map.of(), CLAIMED_ACTOR), 403, "SYS-AUTH-403");
                    assertThat(iam.requestAttempts()).isEqualTo(authorized);
                    iam.revoked(true);
                    assertFailure(() -> clients.post(peer.url() + FACT_PATH, Map.of(), CLAIMED_ACTOR), 403, "SYS-AUTH-403");
                    assertThat(iam.observedOperations()).containsExactly("exchange", "authorize", "forward");
                }
                assertThat(peer.sends).hasValue(0);
            });
        }
    }

    @Test
    void legacyDoesNotCreateIamBeansAndAuthorityRejectsNonIamDeployment() {
        new ApplicationContextRunner().withUserConfiguration(Wiring.class)
                .withPropertyValues("agent.skill.service-ticket-mode=LEGACY")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(ServiceTickets.class)
                        .doesNotHaveBean(CurrentAuthorityClient.class).doesNotHaveBean(ServiceTicketProvider.class));
        var legacy = new AuthorityProperties("test", "skill", null, null, null, null, null, false, null);
        new ApplicationContextRunner().withUserConfiguration(Wiring.class)
                .withPropertyValues("agent.skill.service-ticket-mode=AUTHORITY")
                .withBean(AuthorityProperties.class, () -> legacy)
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("AUTHORITY_REQUIRES_SKILL_IAM"));
    }

    private static ApplicationContextRunner runner(LoopbackAuthority iam, String engineUrl) {
        return new ApplicationContextRunner().withUserConfiguration(Wiring.class)
                .withPropertyValues("agent.skill.service-ticket-mode=AUTHORITY", "agent.services.engine=" + engineUrl,
                        "spring.profiles.active=test")
                .withBean(AuthorityProperties.class, () -> iam.config("skill"))
                .withBean(Clock.class, iam::clock)
                .withBean(WorkloadCredentialResolver.class, () -> iam.clientCredentials("skill"));
    }

    private static ServiceCall entryCall() {
        var row = new ServiceRoutePolicy().routes().stream()
                .filter(r -> "gateway".equals(r.caller()) && "skill".equals(r.target()) && "delegated".equals(r.mode()))
                .findFirst().orElseThrow();
        return new ServiceCall("skill", row.action(), RESOURCE);
    }

    private static ServiceCall factCall() {
        return new ServiceCall("engine", "tool.fact.record", RESOURCE);
    }

    private static void assertFailure(Runnable action, int status, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(PlatformException.class, failure -> {
            assertThat(failure.status()).isEqualTo(status);
            assertThat(failure.code()).isEqualTo(code);
        });
    }

    /** 收到的真实 Bearer 必须在独立 engine client 处验签/在线核权后才返回业务成功。 */
    private static final class EnginePeer implements AutoCloseable {
        private final HttpServer server;
        final AtomicInteger sends = new AtomicInteger();
        final AtomicReference<VerifiedAuthority> verified = new AtomicReference<>();

        EnginePeer(CurrentAuthorityClient engine) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                sends.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                try {
                    if (!"POST".equals(exchange.getRequestMethod()) || !FACT_PATH.equals(exchange.getRequestURI().getPath()))
                        throw new AssertionError("unexpected business route");
                    String header = exchange.getRequestHeaders().getFirst("Authorization");
                    if (header == null || !header.startsWith("Bearer ")) throw new AssertionError("missing bearer");
                    verified.set(engine.authorize(header.substring(7), factCall()));
                    byte[] response = Json.write(Map.of("code", "OK", "data", Map.of("accepted", true))).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                } catch (Throwable failure) {
                    exchange.sendResponseHeaders(403, -1);
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        @Override public void close() { server.stop(0); }
    }
}
