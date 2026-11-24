package cn.agentplatform.common.authority;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Digest;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.monitor.application.EvaluationService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(EvaluationDelegationTest.Fixture.class)
@TestPropertySource(properties = "agent.security.mode=iam")
class EvaluationDelegationTest {
    private static final String SHA = Digest.sha256("evaluation-candidate");
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static HttpServer peer;

    @Autowired private EvaluationService evaluation;
    @Autowired private CurrentAuthorityClient authority;

    @DynamicPropertySource
    static void origins(DynamicPropertyRegistry properties) throws Exception {
        peer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        peer.createContext("/internal", request -> {
            try (request) {
                request.getRequestBody().readAllBytes();
                if (!"Bearer fixture-delegation".equals(request.getRequestHeaders().getFirst("Authorization"))) {
                    request.sendResponseHeaders(401, -1);
                    return;
                }
                REQUESTS.incrementAndGet();
                byte[] body = "{\"data\":{\"status\":\"received\"}}".getBytes(StandardCharsets.UTF_8);
                request.getResponseHeaders().set("Content-Type", "application/json");
                request.sendResponseHeaders(200, body.length);
                request.getResponseBody().write(body);
            }
        });
        peer.start();
        properties.add("agent.services.app", EvaluationDelegationTest::origin);
        properties.add("agent.services.engine", EvaluationDelegationTest::origin);
    }

    @AfterAll
    static void stop() {
        if (peer != null) peer.stop(0);
    }

    @Configuration(proxyBeanMethods = false)
    @Import(EvaluationService.class)
    static class Fixture {
        @Bean JdbcTemplate jdbc() { return mock(JdbcTemplate.class); }
        @Bean PlatformTransactionManager manager() { return mock(PlatformTransactionManager.class); }
        @Bean CurrentAuthorityClient authority() { return mock(CurrentAuthorityClient.class); }
    }

    @Test
    void forwardsTheVerifiedUserOnlyForTheBoundCandidateAndRegisteredPeer() {
        var now = Instant.now();
        var user = new Actor("tenant", "builder", Set.of("EDITOR"), Map.of());
        var subject = new SubjectKey(user.tenantId(), user.principalId());
        var root = new ResourceBinding("evaluation-candidate", "eval-app@7", SHA);
        var grant = new DelegationRef("grt-fixture", subject, now.plusSeconds(45));
        var operation = new OperationBinding("operation", "PORTAL", "PORTAL", now, now.plusSeconds(30),
                grant.value(), subject, root, SHA);
        var current = new VerifiedAuthority(user, "gateway",
                new ServiceCall("monitor", "proxy.monitor.post.EvaluationController.evaluate", root),
                grant, "v1", now, now.plusSeconds(20), "decision", operation);
        var downstream = new ServiceCall("engine", "evaluation.run", root);
        when(authority.forward(current, downstream))
                .thenReturn(new ServiceTicket("fixture-delegation", now.plusSeconds(15), operation));
        String run = origin() + "/internal/evaluations/run";
        var body = Map.<String, Object>of("appCode", "eval-app", "draftRev", 7, "candidateSha256", SHA);
        try (var frame = AuthorityContext.bind(current, () -> current)) {
            assertThat(evaluation.call("POST", run, body)).containsEntry("status", "received");
            var changed = Map.<String, Object>of("appCode", "other-app", "draftRev", 7, "candidateSha256", SHA);
            assertThatThrownBy(() -> evaluation.call("POST", run, changed)).isInstanceOf(PlatformException.class);
        }
        assertThatThrownBy(() -> evaluation.call("POST", run, body)).isInstanceOf(PlatformException.class);
        assertThat(REQUESTS).hasValue(1);
    }

    private static String origin() {
        return "http://127.0.0.1:" + peer.getAddress().getPort();
    }
}
