package cn.agentplatform.common.authority;

import cn.agentplatform.authority.AuthorityApplication;
import cn.agentplatform.authority.AuthorityTestSupport;
import cn.agentplatform.authority.DelegatedTestSupport;
import cn.agentplatform.authority.TestJwt;
import cn.agentplatform.common.Json;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-43：{@code POST /oauth/token} 的 {@code operation=exchange}——gateway 用一张 portal 用户令牌
 * 直接换签给 SSE 订阅目标的 delegated 票据，不再要求先 authorize 再 forward 两跳。
 *
 * <p>缺口背景：iam 模式下 gateway 的 {@code /admin/runs/{id}/events} 订阅走
 * {@link HttpCurrentAuthorityClient#exchange} 拿票；服务端只认 {@code operation=client/forward}，
 * 于是 exchange 被 403 拒绝，SSE 合法订阅在入口即失败。
 *
 * <p>为什么用 {@code proxy.engine.get.RunController.events}：它就是 SSE 订阅真正要的那条目录行
 * （gateway → engine，delegated）。resource 用 run-observation selector（{@code run-snapshot} +
 * {@code r_<32hex>}），与平台侧 {@code RunObservationSelectors} 同形。
 *
 * <p>portal 令牌由本服务的 {@link cn.agentplatform.authority.sign.KeyRing} 直接签成
 * {@code token_use=user} 形状——这正是 EX-40 会话端点签发的票面（iss=authority、aud=portal 受众、
 * client_id=gateway、scope=platform.access），不是伪造的旁路凭据。
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
class ExchangeContractTest extends DelegatedTestSupport {

    /** 线上协议值；实现侧另有 {@code DelegatedAuthority.EXCHANGE} 常量。 */
    private static final String EXCHANGE = "exchange";
    private static final String EVENTS_ACTION = "proxy.engine.get.RunController.events";
    private static final String PORTAL_AUDIENCE = "agent-platform-portal";
    private static final String SELECTOR_ID = "r_0123456789abcdef0123456789abcdef";

    @Test
    void gatewayExchangesAPortalUserTokenForTheSseTicket() {
        ServiceCall call = eventsCall();
        String portalToken = portalToken(30);
        long portalExpiresAt = TestJwt.claims(portalToken).get("exp").asLong();
        ListAppender<ILoggingEvent> audit = attachAudit();

        ServiceTicket ticket;
        try (HttpCurrentAuthorityClient gateway = gatewayClient()) {
            ticket = gateway.exchange(portalToken, call);
        }

        JsonNode claims = TestJwt.verifiedClaims(ticket.value(), "at+jwt", publishedKey());
        assertThat(claims.get("authority_mode").asText()).isEqualTo("delegated");
        assertThat(claims.get("caller").asText()).isEqualTo(GATEWAY);
        assertThat(claims.get("client_id").asText()).isEqualTo(GATEWAY);
        assertThat(claims.get("target").asText()).isEqualTo(ENGINE);
        assertThat(claims.get("action").asText()).isEqualTo(EVENTS_ACTION);
        assertThat(claims.get("aud").asText()).isEqualTo("urn:agent-platform:service:" + ENGINE);
        assertThat(claims.get("tenant_id").asText()).isEqualTo(DelegatedTickets.TENANT_ID);
        assertThat(claims.get("sub").asText()).isEqualTo(DelegatedTickets.SUBJECT);
        assertThat(claims.get("grant_ref").asText()).isNotBlank();
        assertThat(claims.get("authorization_version").asText())
                .isEqualTo(DelegatedTickets.AUTHORIZATION_VERSION);
        assertThat(claims.get("initial_ceiling").get("sub").asText()).isEqualTo(DelegatedTickets.SUBJECT);

        JsonNode operation = claims.get("operation");
        assertThat(operation.get("source_kind").asText()).isEqualTo("PORTAL");
        assertThat(operation.get("expires_at").asLong()).isLessThanOrEqualTo(portalExpiresAt);
        // portal 令牌只活 30s、grant 默认 60s：决策到期必须由令牌 exp 封顶（EX-43 的夹取要求）。
        assertThat(operation.get("expires_at").asLong()).isEqualTo(portalExpiresAt);
        assertThat(claims.get("exp").asLong()).isLessThanOrEqualTo(operation.get("expires_at").asLong());
        assertThat(ticket.expiresAt()).isEqualTo(Instant.ofEpochSecond(operation.get("expires_at").asLong()));

        List<String> lines = auditLines(audit);
        assertThat(lines).anyMatch(line -> line.contains("operation=exchange") && line.contains("outcome=permit"));
        // 审计永不含令牌原文（JWS 段以 eyJ 开头）。
        assertThat(lines).noneMatch(line -> line.contains("eyJ"));
    }

    @Test
    void exchangeRejectsACallerThatIsNotGateway() {
        ListAppender<ILoggingEvent> audit = attachAudit();
        ResponseEntity<JsonNode> denied = postJson("/oauth/token", basic(ENGINE, ENGINE_SECRET),
                exchangeBody(eventsCall(), "ex-caller", portalToken(300)));

        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody().get("code").asText()).isEqualTo("SYS-AUTH-403");
        assertThat(auditLines(audit)).anyMatch(line -> line.contains("operation=exchange")
                && line.contains("reason=EXCHANGE_CALLER_NOT_GATEWAY"));
    }

    @Test
    void exchangeRejectsAnActionWithoutADelegatedRouteRow() {
        ServiceCall unregistered = new ServiceCall(ENGINE, "run.wake", selectorResource());
        ListAppender<ILoggingEvent> audit = attachAudit();
        ResponseEntity<JsonNode> denied = postJson("/oauth/token", basic(GATEWAY, GATEWAY_SECRET),
                exchangeBody(unregistered, "ex-route", portalToken(300)));

        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody().get("code").asText()).isEqualTo("SYS-AUTH-403");
        assertThat(auditLines(audit)).anyMatch(line -> line.contains("operation=exchange")
                && line.contains("reason=ROUTE_NOT_REGISTERED"));
    }

    @Test
    void exchangeRejectsAServiceTokenAsSubject() {
        ListAppender<ILoggingEvent> audit = attachAudit();
        ResponseEntity<JsonNode> denied = postJson("/oauth/token", basic(GATEWAY, GATEWAY_SECRET),
                exchangeBody(eventsCall(), "ex-token-use", serviceShapedToken()));

        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody().get("code").asText()).isEqualTo("SYS-AUTH-403");
        assertThat(auditLines(audit)).anyMatch(line -> line.contains("operation=exchange")
                && line.contains("reason=SUBJECT_TOKEN_USE"));
    }

    private HttpCurrentAuthorityClient gatewayClient() {
        AuthorityProperties config = new AuthorityProperties("iam", GATEWAY,
                new AuthorityProperties.Portal(
                        true,
                        ISSUER,
                        url("/.well-known/jwks.json"),
                        PORTAL_AUDIENCE,
                        GATEWAY,
                        Set.of("EDITOR"),
                        Set.of("region", "dataLabels"),
                        Map.of("region", Set.of("A"), "dataLabels", Set.of("confidential")),
                        Set.of("platform.access")),
                new AuthorityProperties.Authority(
                        ISSUER,
                        url("/.well-known/jwks.json"),
                        url("/oauth/token"),
                        url("/internal/authorize"),
                        GATEWAY,
                        GATEWAY_SECRET_REF,
                        "RS256",
                        "urn:agent-platform:service:" + GATEWAY,
                        60,
                        5,
                        1000,
                        2000),
                null, null, null, false, null);
        WorkloadCredentialResolver credentials = new WorkloadCredentialResolver(config, ref -> GATEWAY_SECRET, true);
        return new HttpCurrentAuthorityClient(config, Clock.systemUTC(), credentials, true);
    }

    private static ServiceCall eventsCall() {
        return new ServiceCall(ENGINE, EVENTS_ACTION, selectorResource());
    }

    private static ResourceBinding selectorResource() {
        return new ResourceBinding("run-snapshot", SELECTOR_ID, DIGEST);
    }

    private static String exchangeBody(ServiceCall call, String nonce, String subjectToken) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operation", EXCHANGE);
        body.put("target", call.target());
        body.put("action", call.action());
        body.put("resource", Map.of("kind", call.resource().kind(), "id", call.resource().id(),
                "version_digest", call.resource().versionDigest()));
        body.put("nonce", nonce);
        body.put("subject_token", subjectToken);
        return Json.write(body);
    }

    /** EX-40 会话签发的形状：本服务签、token_use=user、aud=portal 受众、client_id=gateway。 */
    private String portalToken(long ttlSeconds) {
        return signSubjectToken(ttlSeconds, "user");
    }

    /** 形状合法但 token_use=service：exchange 只接受"人"的令牌。 */
    private String serviceShapedToken() {
        return signSubjectToken(300, "service");
    }

    private String signSubjectToken(long ttlSeconds, String tokenUse) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", ISSUER);
        claims.put("aud", PORTAL_AUDIENCE);
        claims.put("token_use", tokenUse);
        claims.put("jti", "ses-ex43");
        claims.put("client_id", GATEWAY);
        claims.put("scope", "platform.access");
        claims.put("iat", now);
        claims.put("nbf", now);
        claims.put("exp", now + ttlSeconds);
        claims.putAll(DelegatedTickets.actorClaims());
        return keys.sign("at+jwt", claims);
    }
}
