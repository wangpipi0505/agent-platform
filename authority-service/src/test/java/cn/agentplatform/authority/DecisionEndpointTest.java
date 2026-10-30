package cn.agentplatform.authority;

import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 决策端点契约（真实消费者 {@code HttpCurrentAuthorityClient.authorizeWorkload}）：
 * 第二跳必须同时满足"票是本服务签的 workload 票"+"票面四项与本次请求全等"+"调用方就是 target"+
 * "目录里存在同四元组的 workload 行"，缺一条就 403；断言再把 {@code ticket_jti/subject_caller} 钉回这张票。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                AuthorityTestSupport.P_ISSUER,
                AuthorityTestSupport.P_KID,
                AuthorityTestSupport.P_KEY,
                AuthorityTestSupport.P_GATEWAY,
                AuthorityTestSupport.P_APP})
class DecisionEndpointTest extends AuthorityTestSupport {

    private static final List<String> ASSERTION_FORBIDDEN =
            List.of("tenant_id", "sub", "grant_ref", "roles", "attributes", "operation");

    @Test
    void permitsSecondHopAndBindsAssertionToTicketAndRequest() {
        String ticket = gatewayTicket("nonce-ticket-1");
        JsonNode ticketClaims = TestJwt.claims(ticket);
        String body = requestBody("authorize_workload", APP, ORIGIN_ACTION, "nonce-decision-1", ticket);

        ResponseEntity<JsonNode> response = postJson("/internal/authorize", basic(APP, APP_SECRET), body);

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode claims = TestJwt.verifiedClaims(response.getBody().get("assertion").asText(),
                "agent-authority-decision+jwt", publishedKey());

        assertThat(claims.get("iss").asText()).isEqualTo(ISSUER);
        assertThat(claims.get("token_use").asText()).isEqualTo("current-decision");
        assertThat(claims.get("aud").asText()).isEqualTo("urn:agent-platform:service:" + APP);
        assertThat(claims.get("client_id").asText()).isEqualTo(APP);
        assertThat(claims.get("nonce").asText()).isEqualTo("nonce-decision-1");
        assertThat(claims.get("active").asBoolean()).isTrue();
        assertThat(claims.get("current").asBoolean()).isTrue();
        assertThat(claims.get("ticket_jti").asText()).isEqualTo(ticketClaims.get("jti").asText());
        assertThat(claims.get("subject_caller").asText()).isEqualTo(GATEWAY);
        assertThat(claims.get("authority_mode").asText()).isEqualTo("workload");
        assertThat(claims.get("target").asText()).isEqualTo(APP);
        assertThat(claims.get("action").asText()).isEqualTo(ORIGIN_ACTION);
        assertThat(claims.get("request_sha256").asText())
                .as("断言必须绑定本次请求（含 service_ticket）的规范化摘要")
                .isEqualTo(Digest.sha256(Json.canonical(Json.read(body))));
        assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isEqualTo(5L);
        for (String forbidden : ASSERTION_FORBIDDEN) {
            assertThat(claims.has(forbidden)).as("决策断言不得携带 %s", forbidden).isFalse();
        }
    }

    @Test
    void rejectsTicketWhoseCallDoesNotMatchTheRequest() {
        String ticket = gatewayTicket("nonce-ticket-2");
        String body = requestBody("authorize_workload", APP, "integration.credential.read", "nonce-call-mismatch", ticket);

        ResponseEntity<JsonNode> response = postJson("/internal/authorize", basic(APP, APP_SECRET), body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code").asText()).isEqualTo("SYS-AUTH-403");
        assertThat(response.getBody().get("message").asText()).isEqualTo("当前授权不允许此操作");
    }

    @Test
    void rejectsClientThatIsNotTheTarget() {
        String ticket = gatewayTicket("nonce-ticket-3");
        String body = requestBody("authorize_workload", APP, ORIGIN_ACTION, "nonce-not-target", ticket);

        ResponseEntity<JsonNode> response = postJson("/internal/authorize", basic(GATEWAY, GATEWAY_SECRET), body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code").asText()).isEqualTo("SYS-AUTH-403");
    }

    @Test
    void rejectsTamperedTicket() {
        String ticket = gatewayTicket("nonce-ticket-4");
        assertThat(ticket.length()).isGreaterThan(8);
        String tampered = ticket.substring(0, ticket.length() - 4) + "AAAA";
        String body = requestBody("authorize_workload", APP, ORIGIN_ACTION, "nonce-tampered", tampered);

        ResponseEntity<JsonNode> response = postJson("/internal/authorize", basic(APP, APP_SECRET), body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code").asText()).isEqualTo("SYS-AUTH-403");
    }

    @Test
    void rejectsMissingServiceTicket() {
        ResponseEntity<JsonNode> response = postJson("/internal/authorize", basic(APP, APP_SECRET),
                requestBody("authorize_workload", APP, ORIGIN_ACTION, "nonce-no-ticket", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void rejectsOperationOutsideM1Surface() {
        String ticket = gatewayTicket("nonce-ticket-5");
        String body = requestBody("authorize", APP, ORIGIN_ACTION, "nonce-authorize", ticket);

        ResponseEntity<JsonNode> response = postJson("/internal/authorize", basic(APP, APP_SECRET), body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code").asText()).isEqualTo("SYS-AUTH-403");
    }
}
