package cn.agentplatform.authority;

import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.security.PublicKey;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 票据端点契约（真实消费者 {@code HttpCurrentAuthorityClient.query("client",…)} 的形状）：
 * {@code {"assertion":…,"ticket":…}}，票据是 RS256 {@code at+jwt}，断言绑定 nonce + 请求摘要，
 * 且票面四项（target/action/resource/caller）与目录行逐字段相等——平台会拿它们做二次比对，多一个字段都是风险。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                AuthorityTestSupport.P_ISSUER,
                AuthorityTestSupport.P_KID,
                AuthorityTestSupport.P_KEY,
                AuthorityTestSupport.P_GATEWAY,
                AuthorityTestSupport.P_APP})
class TokenEndpointTest extends AuthorityTestSupport {

    private static final List<String> WORKLOAD_FORBIDDEN =
            List.of("tenant_id", "grant_ref", "roles", "attributes", "operation");

    @Test
    void issuesWorkloadTicketAndRequestBoundAssertion() {
        PublicKey key = publishedKey();
        String body = requestBody("client", APP, ORIGIN_ACTION, "nonce-client-1", null);

        ResponseEntity<JsonNode> response = postJson("/oauth/token", basic(GATEWAY, GATEWAY_SECRET), body);

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode assertion = response.getBody().get("assertion");
        JsonNode ticket = response.getBody().get("ticket");
        assertThat(assertion).isNotNull();
        assertThat(ticket).isNotNull();

        JsonNode assertionClaims = TestJwt.verifiedClaims(assertion.asText(), "agent-authority-decision+jwt", key);
        assertThat(assertionClaims.get("token_use").asText()).isEqualTo("current-decision");
        assertThat(assertionClaims.get("aud").asText()).isEqualTo("urn:agent-platform:service:" + GATEWAY);
        assertThat(assertionClaims.get("iss").asText()).isEqualTo(ISSUER);
        assertThat(assertionClaims.get("client_id").asText()).isEqualTo(GATEWAY);
        assertThat(assertionClaims.get("nonce").asText()).isEqualTo("nonce-client-1");
        assertThat(assertionClaims.get("active").asBoolean()).isTrue();
        assertThat(assertionClaims.get("current").asBoolean()).isTrue();
        assertThat(assertionClaims.get("request_sha256").asText())
                .as("断言必须绑定请求的规范化摘要")
                .isEqualTo(Digest.sha256(Json.canonical(Json.read(body))));
        assertThat(assertionClaims.get("exp").asLong() - assertionClaims.get("iat").asLong()).isEqualTo(5L);

        JsonNode claims = TestJwt.verifiedClaims(ticket.asText(), "at+jwt", key);
        assertThat(claims.get("iss").asText()).isEqualTo(ISSUER);
        assertThat(claims.get("aud").asText()).isEqualTo("urn:agent-platform:service:" + APP);
        assertThat(claims.get("token_use").asText()).isEqualTo("service");
        assertThat(claims.get("caller").asText()).isEqualTo(GATEWAY);
        assertThat(claims.get("client_id").asText()).isEqualTo(GATEWAY);
        assertThat(claims.get("sub").asText()).isEqualTo(GATEWAY);
        assertThat(claims.get("scope").asText()).isEqualTo(ORIGIN_ACTION);
        assertThat(claims.get("authority_mode").asText()).isEqualTo("workload");
        assertThat(claims.get("target").asText()).isEqualTo(APP);
        assertThat(claims.get("action").asText()).isEqualTo(ORIGIN_ACTION);
        assertThat(claims.get("jti").asText()).isNotBlank();

        JsonNode resource = claims.get("resource");
        assertThat(resource).isNotNull();
        assertThat(resource.size()).isEqualTo(3);
        assertThat(resource.get("kind").asText()).isEqualTo("integration-origin");
        assertThat(resource.get("id").asText()).isEqualTo(ORIGIN_ID);
        assertThat(resource.get("version_digest").asText()).isEqualTo(DIGEST);

        long ttl = claims.get("exp").asLong() - claims.get("iat").asLong();
        assertThat(ttl).as("平台 maxTicketSeconds=60，卡面要求 <=300").isBetween(1L, 60L);
        for (String forbidden : WORKLOAD_FORBIDDEN) {
            assertThat(claims.has(forbidden)).as("workload 票不得携带 %s", forbidden).isFalse();
        }
        assertThat(assertionClaims.get("ticket_jti").asText()).isEqualTo(claims.get("jti").asText());
    }

    @Test
    void rejectsWrongSecret() {
        ResponseEntity<JsonNode> response = postJson("/oauth/token", basic(GATEWAY, "not-the-secret"),
                requestBody("client", APP, ORIGIN_ACTION, "nonce-bad-secret", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code").asText()).isEqualTo("SYS-AUTH-401");
    }

    @Test
    void rejectsUnknownClient() {
        ResponseEntity<JsonNode> response = postJson("/oauth/token", basic("skill", GATEWAY_SECRET),
                requestBody("client", APP, ORIGIN_ACTION, "nonce-unknown", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code").asText()).isEqualTo("SYS-AUTH-401");
    }

    @Test
    void rejectsMissingAuthorization() {
        ResponseEntity<JsonNode> response = postJson("/oauth/token", null,
                requestBody("client", APP, ORIGIN_ACTION, "nonce-anon", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("message").asText()).isNotBlank();
    }

    @Test
    void rejectsOperationOutsideM1Surface() {
        ResponseEntity<JsonNode> response = postJson("/oauth/token", basic(GATEWAY, GATEWAY_SECRET),
                requestBody("forward", APP, ORIGIN_ACTION, "nonce-forward", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code").asText()).isEqualTo("SYS-AUTH-403");
        assertThat(response.getBody().get("message").asText()).isEqualTo("当前授权不允许此操作");
    }

    @Test
    void rejectsCallWithoutWorkloadRoute() {
        // run.wake 在目录里只有 delegated 行，workload 面必须拒绝。
        ResponseEntity<JsonNode> response = postJson("/oauth/token", basic(GATEWAY, GATEWAY_SECRET),
                requestBody("client", "engine", "run.wake", "nonce-no-route", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code").asText()).isEqualTo("SYS-AUTH-403");
    }

    @Test
    void rejectsMalformedRequest() {
        String body = "{\"operation\":\"client\",\"target\":\"app\",\"action\":\"integration.origin.check\","
                + "\"resource\":{\"kind\":\"integration-origin\",\"id\":\"app-a\",\"version_digest\":\"" + DIGEST + "\"}}";

        ResponseEntity<JsonNode> response = postJson("/oauth/token", basic(GATEWAY, GATEWAY_SECRET), body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void rejectsResourceWithExtraFields() {
        String body = "{\"operation\":\"client\",\"target\":\"app\",\"action\":\"integration.origin.check\","
                + "\"resource\":{\"kind\":\"integration-origin\",\"id\":\"app-a\",\"version_digest\":\"" + DIGEST
                + "\",\"extra\":\"x\"},\"nonce\":\"nonce-extra\"}";

        ResponseEntity<JsonNode> response = postJson("/oauth/token", basic(GATEWAY, GATEWAY_SECRET), body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
    }
}
