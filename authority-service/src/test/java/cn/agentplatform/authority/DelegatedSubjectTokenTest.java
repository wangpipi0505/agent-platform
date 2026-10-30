package cn.agentplatform.authority;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.agentplatform.common.authority.DelegatedTickets;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 卡片第 1 条的第二种主体来源：{@code subject_token}（gateway 手里的 portal 用户令牌）。
 *
 * <p>为什么这条也要测：平台真实链路（{@code ServiceAuthorization}）先 {@code authorizeRetained} 把
 * portal 令牌换成决策，再 {@code forward} 换 delegated 票；只测 {@code service_ticket} 分支等于漏掉入口。
 * 令牌由本服务的 {@code KeyRing} 签（生产里就是 {@code SessionService} 签的同一形态）。
 *
 * <p>目录行取 {@code gateway -> app / proxy.app.get.AppController.list}（目录里的 delegated 行）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = AuthorityApplication.class,
        properties = {
                AuthorityTestSupport.P_ISSUER,
                AuthorityTestSupport.P_KID,
                AuthorityTestSupport.P_KEY,
                AuthorityTestSupport.P_GATEWAY,
                AuthorityTestSupport.P_APP,
                DelegatedTestSupport.P_DELEGATION_ON})
class DelegatedSubjectTokenTest extends DelegatedTestSupport {

    private static final String ACTION = "proxy.app.get.AppController.list";

    @Test
    void portalUserTokenIsExchangedForADelegatedDecision() {
        ResponseEntity<JsonNode> issued = postJson("/internal/authorize", basic(GATEWAY, GATEWAY_SECRET),
                body("authorize", call(APP, ACTION), "subj-ok", Map.of("subject_token", subjectToken(3600))));

        assertThat(issued.getStatusCode()).as("authorize body: %s", issued.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode jwks = new ObjectMapper().valueToTree(keys.jwks());
        JsonNode claims = TestJwt.verifiedClaims(issued.getBody().get("assertion").asText(),
                "agent-authority-decision+jwt", TestJwt.publicKey(jwks.get("keys").get(0)));

        assertThat(claims.get("token_use").asText()).isEqualTo("current-decision");
        assertThat(claims.get("aud").asText()).isEqualTo("urn:agent-platform:service:gateway");
        assertThat(claims.get("authority_mode").asText()).isEqualTo("delegated");
        assertThat(claims.get("target").asText()).isEqualTo(APP);
        assertThat(claims.get("action").asText()).isEqualTo(ACTION);
        assertThat(claims.get("tenant_id").asText()).isEqualTo(DelegatedTickets.TENANT_ID);
        assertThat(claims.get("sub").asText()).isEqualTo(DelegatedTickets.SUBJECT);
        assertThat(claims.get("grant_ref").asText()).startsWith("grt-");
        assertThat(claims.get("authorization_version").asText()).isNotBlank();
        // initial_ceiling 必须是 Actor 形状：客户端 actor(...) 反序列化后要能算出同一个 ceiling。
        assertThat(claims.get("initial_ceiling").get("sub").asText()).isEqualTo(DelegatedTickets.SUBJECT);
        assertThat(claims.get("operation").get("operation_id").asText()).startsWith("op-");
        assertThat(claims.get("operation").get("ceiling_sha256").asText()).matches("[a-f0-9]{64}");
        // 决策本身不再是"票据"：ticket_jti 只在 authorize_workload 的回执里出现。
        assertThat(claims.has("ticket_jti")).isFalse();
    }

    @Test
    void expiredSubjectTokenIsRefused() {
        ListAppender<ILoggingEvent> audit = attachAudit();
        ResponseEntity<JsonNode> refused = postJson("/internal/authorize", basic(GATEWAY, GATEWAY_SECRET),
                body("authorize", call(APP, ACTION), "subj-expired", Map.of("subject_token", subjectToken(-30))));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().get("code").asText()).isEqualTo("SYS-AUTH-403");
        assertThat(auditLines(audit)).anyMatch(line -> line.contains("operation=authorize"));
    }

    @Test
    void subjectTokenForAnotherAudienceIsRefused() {
        String token = sign(Map.of(
                "aud", "some-other-service",
                "sub", DelegatedTickets.SUBJECT,
                "tenant_id", DelegatedTickets.TENANT_ID));
        ResponseEntity<JsonNode> refused = postJson("/internal/authorize", basic(GATEWAY, GATEWAY_SECRET),
                body("authorize", call(APP, ACTION), "subj-aud", Map.of("subject_token", token)));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().get("code").asText()).isEqualTo("SYS-AUTH-403");
    }

    /** portal 令牌形态对齐 {@code SessionService}：iss=本服务、token_use=user、aud=portal 受众。 */
    private String subjectToken(long ttlSeconds) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("iat", now);
        overrides.put("nbf", now);
        overrides.put("exp", now + ttlSeconds);
        return sign(overrides);
    }

    private String sign(Map<String, Object> overrides) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", ISSUER);
        claims.put("aud", "agent-platform-portal");
        claims.put("token_use", "user");
        claims.put("jti", "ses-ex42");
        claims.put("client_id", "gateway");
        claims.put("scope", "platform.access");
        claims.put("roles", List.of("EDITOR"));
        claims.put("attributes", Map.of("region", List.of("A")));
        claims.putAll(DelegatedTickets.actorClaims());
        claims.putAll(overrides);
        return keys.sign("at+jwt", claims);
    }
}
