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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.List;
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
    @Autowired private JdbcTemplate db;

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
            assertThatThrownBy(() -> evaluation.evaluate(Map.of("appCode", "other-app", "draftRev", 7,
                    "candidateSha256", SHA, "datasetVersion", "v1")))
                    .isInstanceOfSatisfying(PlatformException.class, error -> assertThat(error.status()).isEqualTo(403));
        }
        assertThatThrownBy(() -> evaluation.call("POST", run, body)).isInstanceOf(PlatformException.class);
        assertThat(REQUESTS).hasValue(1);
    }

    @Test
    void publicationEvidenceUsesTheDelegatedTenantAndBoundApplication() {
        var now = Instant.now();
        var user = new Actor("tenant-a", "publisher", Set.of("USER"), Map.of());
        var subject = new SubjectKey(user.tenantId(), user.principalId());
        var root = EvaluationCandidateBinding.publication("eval-app");
        var call = new ServiceCall("monitor", EvaluationCandidateBinding.EVIDENCE_ACTION, root);
        var grant = new DelegationRef("grt-publication", subject, now.plusSeconds(45));
        var operation = new OperationBinding("publication", "PORTAL", "PORTAL", now, now.plusSeconds(30),
                grant.value(), subject, root, SHA);
        var delegated = new VerifiedAuthority(user, "app", call, grant, "v1", now, now.plusSeconds(20),
                "decision-publication", operation);
        var jwt = Jwt.withTokenValue("verified-fixture").header("alg", "RS256").subject(user.principalId())
                .claim("tenant_id", user.tenantId()).claim("authority_mode", "delegated")
                .claim("roles", List.of("USER")).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        try (var frame = AuthorityContext.bind(delegated, () -> delegated)) {
            assertThat(evaluation.accepted("eval-app", 7, SHA)).containsEntry("accepted", false);
            assertThatThrownBy(() -> evaluation.accepted("other-app", 7, SHA))
                    .isInstanceOfSatisfying(PlatformException.class, error -> assertThat(error.status()).isEqualTo(403));
        } finally {
            SecurityContextHolder.clearContext();
        }
        org.mockito.Mockito.verify(db).queryForList(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("tenant-a"), org.mockito.ArgumentMatchers.eq("eval-app"),
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(SHA),
                org.mockito.ArgumentMatchers.isNull());
    }

    private static String origin() {
        return "http://127.0.0.1:" + peer.getAddress().getPort();
    }
}
