package cn.agentplatform.skill.infra;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.SecurityConfiguration;
import cn.agentplatform.common.authority.AuthorityContext;
import cn.agentplatform.common.authority.ServiceCall;
import cn.agentplatform.common.authority.ServiceRoutePolicy;
import cn.agentplatform.common.authority.ServiceTicket;
import cn.agentplatform.common.authority.ServiceTicketProvider;
import cn.agentplatform.common.authority.VerifiedAuthority;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G-8c：服务票据签发缝（LEGACY / AUTHORITY 两态）。
 *
 * <p>缝的价值全在"两态互不串味"：LEGACY 必须逐字等同于今天的 {@code ServiceTokens.issue}，
 * AUTHORITY 必须只认 {@link AuthorityContext} + 目录登记 + 装配好的 provider，任何一环缺失都
 * 显式失败——尤其不许"取不到授权就退回自签"。这几条断言就是那条禁令的机器版本。
 *
 * <p>父 JVM 需要 {@code ServiceTokens} 处于可签发的 test 部署模式（LEGACY 例要真出票），因此
 * {@link #deployment()} 里显式注册；AUTHORITY 例不依赖该模式。
 */
class ServiceTicketsTest {
    static final Actor ACTOR = new Actor("tenant-a", "alice", Set.of("EDITOR"), Map.of());

    /** 只够 ServiceTokens 签发 service 票据；与断言无关。 */
    static final String JWT_SECRET = "g8c-service-tickets-test-secret-0123456789abcdef";

    static final String ENGINE = "http://127.0.0.1:18082";

    /** 目录里 {@code caller=skill}、{@code target=engine} 的已登记行。 */
    static final String FACT_TEMPLATE = "/internal/tool-calls/{id}/fact";
    static final String FACT_URL = ENGINE + "/internal/tool-calls/tc-8c/fact";

    /** host:port → 服务名，等价于 application.yml 的 agent.services.*。 */
    static final Map<String, String> SERVICES =
            Map.of(
                    "app", "http://127.0.0.1:18081",
                    "engine", ENGINE,
                    "monitor", "http://127.0.0.1:18085");

    static final String MISSING_AUTHORITY_MESSAGE = "当前请求没有可验证授权，无法签发服务票据";

    @BeforeAll
    static void deployment() {
        System.setProperty("agent.jwt.secret", JWT_SECRET);
        var environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "g8c-service-tickets-test",
                                Map.of(
                                        "agent.security.mode", "test",
                                        "agent.security.service-id", "skill",
                                        "server.address", "127.0.0.1")));
        environment.setActiveProfiles("test");
        SecurityConfiguration.bindDeployment(environment);
    }

    @AfterAll
    static void restore() {
        System.clearProperty("agent.jwt.secret");
    }

    @Test
    void legacyModeUsesServiceTokens() {
        var provider = new RecordingProvider("provider-ticket");
        var tickets =
                new ServiceTickets(
                        ServiceTickets.LEGACY, provider, new ServiceRoutePolicy(), SERVICES);

        // 故意不 bind AuthorityContext：LEGACY 不读授权上下文，这本身就是"逐字一致"的一部分。
        String ticket = tickets.ticket(ACTOR, "skill", "POST", FACT_URL);

        JsonNode claims = claims(ticket);
        assertThat(claims.path("iss").asText()).isEqualTo("agent-platform-v12");
        assertThat(claims.path("sub").asText()).isEqualTo("alice");
        assertThat(claims.path("tenant_id").asText()).isEqualTo("tenant-a");
        assertThat(claims.path("aud").asText()).isEqualTo("agent-platform-internal");
        assertThat(claims.path("token_use").asText()).isEqualTo("service");
        assertThat(texts(claims.path("roles"))).contains("SERVICE");
        assertThat(provider.calls).isEmpty();
    }

    @Test
    void authorityModeIssuesThroughProvider() {
        var provider = new RecordingProvider("provider-ticket");
        var policy = new ServiceRoutePolicy();
        var tickets = new ServiceTickets(ServiceTickets.AUTHORITY, provider, policy, SERVICES);
        var authority = delegatedAuthority();

        try (var bound = AuthorityContext.bind(authority)) {
            assertThat(tickets.ticket(ACTOR, "skill", "POST", FACT_URL))
                    .isEqualTo("provider-ticket");
        }

        assertThat(provider.calls).hasSize(1);
        assertThat(provider.authority).isSameAs(authority);
        var call = provider.calls.get(0);
        // 期望值直接取自目录本身：缝不许自己编 action，也不许猜 target。
        var row =
                policy.routes().stream()
                        .filter(r -> "skill".equals(r.caller()))
                        .filter(r -> "POST".equals(r.method()))
                        .filter(r -> FACT_TEMPLATE.equals(r.template()))
                        .findFirst()
                        .orElseThrow();
        assertThat(row.target()).isEqualTo("engine");
        assertThat(call.target()).isEqualTo(row.target());
        assertThat(call.action()).isEqualTo(row.action()).isEqualTo("tool.fact.record");
        assertThat(call.resource()).isNull();
    }

    @Test
    void authorityModeWithoutAuthorityFails() {
        var provider = new RecordingProvider("provider-ticket");
        var policy = new ServiceRoutePolicy();
        var tickets = new ServiceTickets(ServiceTickets.AUTHORITY, provider, policy, SERVICES);

        var unbound = failure(() -> tickets.ticket(ACTOR, "skill", "POST", FACT_URL));
        assertThat(unbound.status()).isEqualTo(403);
        assertThat(unbound.code()).isEqualTo("AUTHORITY_CONTEXT_MISSING");
        assertThat(unbound.getMessage()).isEqualTo(MISSING_AUTHORITY_MESSAGE);

        // bind 后 close：已退休的 frame 等同"没有授权"，仍然 403——绝不回落 ServiceTokens 自签。
        var closed = AuthorityContext.bind(delegatedAuthority());
        closed.close();
        var afterClose = failure(() -> tickets.ticket(ACTOR, "skill", "POST", FACT_URL));
        assertThat(afterClose.status()).isEqualTo(403);
        assertThat(afterClose.code()).isEqualTo("AUTHORITY_CONTEXT_MISSING");

        assertThat(provider.calls).isEmpty();
    }

    @Test
    void unregisteredRouteFails() {
        var provider = new RecordingProvider("provider-ticket");
        var policy = new ServiceRoutePolicy();
        var tickets = new ServiceTickets(ServiceTickets.AUTHORITY, provider, policy, SERVICES);

        try (var bound = AuthorityContext.bind(delegatedAuthority())) {
            // 已登记的服务名 + 未登记的方法：交回 ServiceRoutePolicy 既有的 403，不另造错误码。
            var wrongMethod =
                    failure(
                            () ->
                                    tickets.ticket(
                                            ACTOR, "skill", "DELETE", ENGINE + "/internal/tool-calls/tc-8c"));
            assertThat(wrongMethod.status()).isEqualTo(403);
            assertThat(wrongMethod.code()).isEqualTo("SYS-AUTH-403");
            assertThat(wrongMethod.getMessage()).isEqualTo("服务调用能力未登记");

            // host:port 不在 agent.services.* 里：目标服务不可判定，宁可 403 也不猜。
            var unknownService =
                    failure(
                            () ->
                                    tickets.ticket(
                                            ACTOR,
                                            "skill",
                                            "POST",
                                            "http://127.0.0.1:9/internal/tool-calls/tc-8c/fact"));
            assertThat(unknownService.status()).isEqualTo(403);
            assertThat(unknownService.code()).isEqualTo("SYS-AUTH-403");
        }

        assertThat(provider.calls).isEmpty();
    }

    @Test
    void providerMissingIsAssemblyError() {
        var tickets =
                new ServiceTickets(ServiceTickets.AUTHORITY, null, new ServiceRoutePolicy(), SERVICES);

        try (var bound = AuthorityContext.bind(delegatedAuthority())) {
            var error = failure(() -> tickets.ticket(ACTOR, "skill", "POST", FACT_URL));
            // 装配错误不是鉴权失败：403 会让运维去查授权，500 才会去查容器装配。
            assertThat(error.status()).isEqualTo(500);
            assertThat(error.code()).isEqualTo("SERVICE_TICKET_PROVIDER_MISSING");
        }
    }

    /** 形状匹配按段比较：多一段的路径不是登记模板，不能前缀/包含式命中。 */
    @Test
    void templateMatchingIsSegmentedNotPrefix() {
        var provider = new RecordingProvider("provider-ticket");
        var tickets = new ServiceTickets(ServiceTickets.AUTHORITY, provider, new ServiceRoutePolicy(), SERVICES);

        try (var bound = AuthorityContext.bind(delegatedAuthority())) {
            var error =
                    failure(
                            () ->
                                    tickets.ticket(
                                            ACTOR,
                                            "skill",
                                            "GET",
                                            ENGINE + "/internal/tool-calls/tc-8c/fact/extra"));
            assertThat(error.status()).isEqualTo(403);
            assertThat(error.code()).isEqualTo("SYS-AUTH-403");
        }

        assertThat(provider.calls).isEmpty();
    }

    /** 记录"谁签的、签的什么"，并返回固定票据值。 */
    static final class RecordingProvider implements ServiceTicketProvider {
        final String value;
        final List<ServiceCall> calls = new ArrayList<>();
        VerifiedAuthority authority;

        RecordingProvider(String value) {
            this.value = value;
        }

        @Override
        public ServiceTicket issue(VerifiedAuthority authority, ServiceCall call) {
            this.authority = authority;
            calls.add(call);
            return new ServiceTicket(value, Instant.now().plusSeconds(30), null);
        }
    }

    /** 只做"当前授权"的占位对象：缝不验签，验签属于 AuthorityContext 的绑定方。 */
    static VerifiedAuthority delegatedAuthority() {
        return new VerifiedAuthority(
                ACTOR,
                "app",
                new ServiceCall("app", "skill.run", null),
                null,
                "authorization-v1",
                Instant.now(),
                Instant.now().plusSeconds(60),
                "decision-g8c",
                null);
    }

    static JsonNode claims(String ticket) {
        String[] parts = ticket.split("\\.");
        assertThat(parts).hasSize(3);
        return Json.read(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
    }

    static List<String> texts(JsonNode array) {
        var values = new ArrayList<String>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private static PlatformException failure(Call call) {
        try {
            call.run();
        } catch (PlatformException error) {
            return error;
        } catch (Exception error) {
            throw new AssertionError("expected PlatformException but got " + error, error);
        }
        throw new AssertionError("expected PlatformException but nothing was thrown");
    }

    @FunctionalInterface
    interface Call {
        void run() throws Exception;
    }
}
