package cn.agentplatform.common.authority;

import cn.agentplatform.authority.AuthorityApplication;
import cn.agentplatform.authority.AuthorityTestSupport;
import cn.agentplatform.authority.DelegatedTestSupport;
import cn.agentplatform.authority.TestJwt;
import cn.agentplatform.authority.decision.DecisionStore;
import cn.agentplatform.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-44 切片 1a 的服务端契约：gateway→gateway 观测订阅票的 jti 回写，以及
 * {@code origin_authentication_id} 从 portal 令牌 → ticket → assertion → Decision 的透传。
 *
 * <p>为什么这条链路要在 1a 就成立：客户端 {@code beginGatewayPortalEvents} 拿到 S0 票后第一件事是
 * {@code origin(ticket.claims())}（{@code HttpCurrentAuthorityClient:594}）；缺 claim 即 403，
 * 后面的 S1/S2/S3 全部不可达。origin 由会话签发端（{@code SessionService}）按 portal {@code jti}
 * 派生，authority 只做透传——派生规则唯一，票面/断言/决策三处必须是同一个值。
 *
 * <p>ticket jti 回写只写入不消费：1c 的 {@code query_ticket} 会从 Decision 读它，本切片只钉
 * "签出的票与登记的决策是同一张票"。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = AuthorityApplication.class,
        properties = {
                AuthorityTestSupport.P_ISSUER,
                AuthorityTestSupport.P_KID,
                AuthorityTestSupport.P_KEY,
                AuthorityTestSupport.P_GATEWAY,
                DelegatedTestSupport.P_ENGINE,
                DelegatedTestSupport.P_DELEGATION_ON})
class GatewayEventsSlice1aContractTest extends DelegatedTestSupport {

    private static final String ACTION = "gateway.run.events.observe";
    private static final String PORTAL_AUDIENCE = "agent-platform-portal";
    private static final String SELECTOR_ID = "r_0123456789abcdef0123456789abcdef";
    /** {@code "oa1:" + 32 hex}；36 字符，客户端 {@code origin()} 的上限是 128。 */
    private static final String ORIGIN = "oa1:0123456789abcdef0123456789abcdef";

    @Autowired
    private DecisionStore decisions;

    @Test
    void exchangeRecordsTheIssuedTicketJtiOnTheDecision() {
        JsonNode exchanged = exchange(gatewayEventsCall(), "slice1a-jti", portalToken(300, ORIGIN));
        JsonNode ticket = verified(exchanged.get("ticket").asText(), "at+jwt");
        JsonNode assertion = verified(exchanged.get("assertion").asText(), "agent-authority-decision+jwt");
        String ticketJti = ticket.get("jti").asText();
        assertThat(ticketJti).startsWith("tkt-");

        DecisionStore.Decision decision = decisions.find(assertion.path("decision_id").asText()).orElseThrow();
        assertThat(decision.ticketJti()).isEqualTo(ticketJti);
        // 断言里的 ticket_jti 与决策登记的值同源：1c 的 S3 用 immutable.gateway_query.ticket_jti 关联 S0。
        assertThat(assertion.path("ticket_jti").asText()).isEqualTo(ticketJti);
    }

    @Test
    void exchangeCarriesThePortalOriginOntoTicketAssertionAndDecision() {
        JsonNode exchanged = exchange(gatewayEventsCall(), "slice1a-origin", portalToken(300, ORIGIN));
        JsonNode ticket = verified(exchanged.get("ticket").asText(), "at+jwt");
        JsonNode assertion = verified(exchanged.get("assertion").asText(), "agent-authority-decision+jwt");

        assertThat(ticket.path("origin_authentication_id").asText()).isEqualTo(ORIGIN);
        assertThat(assertion.path("origin_authentication_id").asText()).isEqualTo(ORIGIN);
        DecisionStore.Decision decision = decisions.find(assertion.path("decision_id").asText()).orElseThrow();
        assertThat(decision.originAuthenticationId()).isEqualTo(ORIGIN);
    }

    @Test
    void authorizeCarriesTheOriginFromTheServiceTicket() {
        JsonNode exchanged = exchange(gatewayEventsCall(), "slice1a-authorize", portalToken(300, ORIGIN));
        String serviceTicket = exchanged.get("ticket").asText();

        ResponseEntity<JsonNode> response = postJson("/internal/authorize", basic(GATEWAY, GATEWAY_SECRET),
                command("authorize", gatewayEventsCall(), "slice1a-authorize-decision",
                        Map.of("service_ticket", serviceTicket)));
        assertThat(response.getStatusCode()).as("authorize body: %s", response.getBody()).isEqualTo(HttpStatus.OK);

        JsonNode assertion = verified(response.getBody().get("assertion").asText(), "agent-authority-decision+jwt");
        assertThat(assertion.path("origin_authentication_id").asText()).isEqualTo(ORIGIN);
        DecisionStore.Decision decision = decisions.find(assertion.path("decision_id").asText()).orElseThrow();
        assertThat(decision.originAuthenticationId()).isEqualTo(ORIGIN);
        // fromTicket 路径的 ticketJti 语义不变：断言描述的是"调用方出示的那张票"。
        assertThat(decision.ticketJti()).isEqualTo(verified(serviceTicket, "at+jwt").get("jti").asText());
    }

    private JsonNode exchange(ServiceCall call, String nonce, String subjectToken) {
        ResponseEntity<JsonNode> response = postJson("/oauth/token", basic(GATEWAY, GATEWAY_SECRET),
                command("exchange", call, nonce, Map.of("subject_token", subjectToken)));
        assertThat(response.getStatusCode()).as("exchange body: %s", response.getBody()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private JsonNode verified(String token, String type) {
        return TestJwt.verifiedClaims(token, type, publishedKey());
    }

    private static ServiceCall gatewayEventsCall() {
        return new ServiceCall("gateway", ACTION, selectorResource());
    }

    private static ResourceBinding selectorResource() {
        return new ResourceBinding("run-snapshot", SELECTOR_ID, DIGEST);
    }

    private static String command(String operation, ServiceCall call, String nonce, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operation", operation);
        body.put("target", call.target());
        body.put("action", call.action());
        body.put("resource", Map.of("kind", call.resource().kind(), "id", call.resource().id(),
                "version_digest", call.resource().versionDigest()));
        body.put("nonce", nonce);
        body.putAll(extra);
        return Json.write(body);
    }

    /** 与 {@code SessionEndpointTest} 签发的 portal 票同形；origin 只有会话端会写，测试按需补上。 */
    private String portalToken(long ttlSeconds, String origin) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", ISSUER);
        claims.put("aud", PORTAL_AUDIENCE);
        claims.put("token_use", "user");
        claims.put("jti", "ses-slice1a");
        claims.put("client_id", GATEWAY);
        claims.put("scope", "platform.access");
        claims.put("iat", now);
        claims.put("nbf", now);
        claims.put("exp", now + ttlSeconds);
        claims.putAll(DelegatedTickets.actorClaims());
        if (origin != null) claims.put("origin_authentication_id", origin);
        return keys.sign("at+jwt", claims);
    }
}
