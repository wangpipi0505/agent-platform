package cn.agentplatform.common.authority;

import cn.agentplatform.authority.AuthorityApplication;
import cn.agentplatform.authority.AuthorityTestSupport;
import cn.agentplatform.authority.DelegatedTestSupport;
import cn.agentplatform.authority.TestJwt;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-42 的两跳端到端：用平台自己的 {@link HttpCurrentAuthorityClient}（不是 mock）先 {@code authorize}
 * 再 {@code forward}，最后逐字段核对下游票据的 claims。
 *
 * <p>两跳的起点是 {@link DelegatedTickets} 造的 delegated 票（M2 的输入形态），不是 portal 令牌——
 * 平台真实链路里 {@code ServiceAuthorization} 拿到的就是这张票。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = AuthorityApplication.class,
        properties = {
                AuthorityTestSupport.P_ISSUER,
                AuthorityTestSupport.P_KID,
                AuthorityTestSupport.P_KEY,
                AuthorityTestSupport.P_GATEWAY,
                AuthorityTestSupport.P_APP,
                DelegatedTestSupport.P_ENGINE,
                DelegatedTestSupport.P_DELEGATION_ON})
class DelegatedForwardContractTest extends DelegatedTestSupport {

    @Test
    void delegatedTicketIsAuthorizedThenForwardedToTheNextTarget() {
        ServiceCall call = call(ENGINE, FIRST_ACTION);
        ServiceCall next = call(SKILL, NEXT_ACTION);
        String bootstrap = DelegatedTickets.bootstrap(keys, call, GATEWAY);
        ListAppender<ILoggingEvent> audit = attachAudit();

        VerifiedAuthority authority;
        ServiceTicket forwarded;
        try (HttpCurrentAuthorityClient engine = client(ENGINE, ENGINE_SECRET_REF, ENGINE_SECRET)) {
            authority = engine.authorize(bootstrap, call);
            forwarded = engine.forward(authority, next);
        }

        assertThat(authority.decisionId()).isNotBlank();
        assertThat(authority.call()).isEqualTo(call);
        assertThat(authority.actor().principalId()).isEqualTo(DelegatedTickets.SUBJECT);
        assertThat(authority.expiresAt()).isAfter(Instant.now());
        assertThat(authority.delegation().value()).isEqualTo(DelegatedTickets.GRANT_REF);

        assertThat(forwarded.value()).isNotBlank();
        assertThat(forwarded.expiresAt()).isAfter(Instant.now());
        JsonNode claims = TestJwt.verifiedClaims(forwarded.value(), "at+jwt", publishedKey());
        assertThat(claims.get("aud").asText()).isEqualTo("urn:agent-platform:service:" + SKILL);
        assertThat(claims.get("caller").asText()).isEqualTo(ENGINE);
        assertThat(claims.get("client_id").asText()).isEqualTo(ENGINE);
        assertThat(claims.get("scope").asText()).isEqualTo(NEXT_ACTION);
        assertThat(claims.get("target").asText()).isEqualTo(SKILL);
        assertThat(claims.get("authority_mode").asText()).isEqualTo("delegated");
        assertThat(claims.get("tenant_id").asText()).isEqualTo(DelegatedTickets.TENANT_ID);
        assertThat(claims.get("sub").asText()).isEqualTo(DelegatedTickets.SUBJECT);
        assertThat(claims.get("grant_ref").asText()).isEqualTo(DelegatedTickets.GRANT_REF);
        assertThat(claims.get("authorization_version").asText())
                .isEqualTo(DelegatedTickets.AUTHORIZATION_VERSION);
        assertThat(claims.get("operation").get("operation_id").asText())
                .isEqualTo(DelegatedTickets.OPERATION_ID);
        assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isBetween(1L, 60L);
        assertThat(claims.get("exp").asLong())
                .isLessThanOrEqualTo(claims.get("grant_expires_at").asLong());

        // 审计要能复盘两跳，但绝不能落令牌原文（JWS 段以 eyJ 开头）。
        List<String> lines = auditLines(audit);
        assertThat(lines).anyMatch(line -> line.contains("operation=authorize") && line.contains("outcome=permit"));
        assertThat(lines).anyMatch(line -> line.contains("operation=forward") && line.contains("outcome=permit"));
        assertThat(lines).noneMatch(line -> line.contains("eyJ"));
    }

    @Test
    void forwardingToATargetWithoutADelegatedRowIsEscalation() {
        ServiceCall call = call(ENGINE, FIRST_ACTION);
        String bootstrap = DelegatedTickets.bootstrap(keys, call, GATEWAY);
        ListAppender<ILoggingEvent> audit = attachAudit();

        JsonNode decision = authorize(bootstrap, call, "escalate");
        ResponseEntity<JsonNode> denied = postForward(decision, call("model", FIRST_ACTION), "escalate-2");

        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody().get("code").asText()).isEqualTo("SYS-AUTH-403");
        assertThat(auditLines(audit)).anyMatch(line -> line.contains("reason=FORWARD_ESCALATION"));
    }

    @Test
    void delegatedTicketCannotBeRedeemedAsAWorkloadTicket() {
        ServiceCall call = call(ENGINE, FIRST_ACTION);
        String bootstrap = DelegatedTickets.bootstrap(keys, call, GATEWAY);
        ListAppender<ILoggingEvent> audit = attachAudit();

        ResponseEntity<JsonNode> denied = postJson("/internal/authorize", basic(ENGINE, ENGINE_SECRET),
                body("authorize_workload", call, "delegated-as-workload", Map.of("service_ticket", bootstrap)));

        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody().get("code").asText()).isEqualTo("SYS-AUTH-403");
        assertThat(auditLines(audit)).anyMatch(line -> line.contains("authority_mode != workload"));
    }

    @Test
    void authorizeRejectsAnActionThatTheRouteDirectoryDoesNotRegister() {
        ServiceCall unregistered = call(ENGINE, "run.wake");
        String bootstrap = DelegatedTickets.bootstrap(keys, unregistered, GATEWAY);
        ListAppender<ILoggingEvent> audit = attachAudit();

        ResponseEntity<JsonNode> denied = postJson("/internal/authorize", basic(ENGINE, ENGINE_SECRET),
                body("authorize", unregistered, "unregistered-route", Map.of("service_ticket", bootstrap)));

        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody().get("code").asText()).isEqualTo("SYS-AUTH-403");
        assertThat(auditLines(audit)).anyMatch(line -> line.contains("ROUTE_NOT_REGISTERED"));
    }

    private HttpCurrentAuthorityClient client(String service, String secretRef, String secret) {
        AuthorityProperties config = config(service, secretRef);
        WorkloadCredentialResolver credentials = new WorkloadCredentialResolver(config, ref -> secret, true);
        return new HttpCurrentAuthorityClient(config, Clock.systemUTC(), credentials, true);
    }

    private AuthorityProperties config(String service, String secretRef) {
        return new AuthorityProperties("iam", service, null,
                new AuthorityProperties.Authority(
                        ISSUER,
                        url("/.well-known/jwks.json"),
                        url("/oauth/token"),
                        url("/internal/authorize"),
                        service,
                        secretRef,
                        "RS256",
                        "urn:agent-platform:service:" + service,
                        60,
                        5,
                        1000,
                        2000),
                null, null, null, false, null);
    }
}
