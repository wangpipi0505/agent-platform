package cn.agentplatform.common.authority;

import cn.agentplatform.authority.DelegatedTestSupport;
import cn.agentplatform.authority.TestJwt;
import cn.agentplatform.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * EX-44 切片 1c 两个测试类共用的请求/票面构造：变体 B（{@code query_ticket}）的 S0 opening、
 * S1 retained 换票，以及"手工签一张 opening 形状的票但 jti 不在登记簿里"这种负例素材。
 *
 * <p>刻意不复用 {@code HttpCurrentAuthorityClient}：1c 只实现 S1，客户端 {@code openGatewayPortalEvents}
 * 还会继续走 S2/S3（1d），用真实客户端只能测到"最终 403"，分不清是哪一步拒绝。这里按客户端
 * {@code :622} 的逐字段请求体直连服务端，才能把 1c 的判定点固定住。
 */
abstract class GatewayEventsSlice1cSupport extends DelegatedTestSupport {

    static final String EXCHANGE = "exchange";
    static final String PORTAL_AUDIENCE = "agent-platform-portal";
    static final String RUN = "r_0123456789abcdef0123456789abcdef";
    static final String ORIGIN = "oa1:0123456789abcdef0123456789abcdef";
    /** 与 {@code RunObservationSelectors.GATEWAY_EVENTS_ACTION} 同值（package-private，生产侧只能写字面量）。 */
    static final String GATEWAY_OBSERVE = RunObservationSelectors.GATEWAY_EVENTS_ACTION;

    /** S0：{@code beginGatewayPortalEvents} 的 opening 换票（engine / metadata / selector）。 */
    protected JsonNode open(String portalToken, String nonce) {
        ResponseEntity<JsonNode> response = postJson("/oauth/token", basic(GATEWAY, GATEWAY_SECRET),
                command(EXCHANGE, metadataCall(), nonce, Map.of(
                        "subject_token", portalToken,
                        "gateway_events_opening", true)));
        org.assertj.core.api.Assertions.assertThat(response.getStatusCode())
                .as("opening body: %s", response.getBody()).isEqualTo(org.springframework.http.HttpStatus.OK);
        return response.getBody();
    }

    /** S1：客户端 {@code :622} 的请求体形状（subject_token + query_ticket，目标 gateway 观测行）。 */
    protected ResponseEntity<JsonNode> exchangeQuery(String portalToken, String queryTicket, String nonce) {
        return postJson("/oauth/token", basic(GATEWAY, GATEWAY_SECRET),
                command(EXCHANGE, retainedCall(), nonce, Map.of(
                        "subject_token", portalToken,
                        "query_ticket", queryTicket)));
    }

    protected static ServiceCall retainedCall() {
        return new ServiceCall("gateway", GATEWAY_OBSERVE, RunObservationSelectors.events(RUN, DIGEST));
    }

    /** 与 {@code beginGatewayPortalEvents:588} 同形：engine / metadata / selector 资源。 */
    protected static ServiceCall metadataCall() {
        return new ServiceCall(ENGINE, RunObservationSelectors.ACTION,
                RunObservationSelectors.metadata(
                        new SubjectKey(DelegatedTickets.TENANT_ID, DelegatedTickets.SUBJECT), RUN));
    }

    /**
     * 请求体字段集合必须与客户端 {@code command(...)} 一致；多一个或少一个都会让客户端的
     * {@code request_sha256} 对不上（测试里直接按服务端白名单拼，避免"看起来像"的 body）。
     */
    protected static String command(String operation, ServiceCall call, String nonce, Map<String, Object> extra) {
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

    /** 与会话端点签发的 portal 票同形；jti 用来区分"同一登录会话"与"另一张令牌"。 */
    protected String portalToken(long ttlSeconds, String origin, String subject, String jti) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", ISSUER);
        claims.put("aud", PORTAL_AUDIENCE);
        claims.put("token_use", "user");
        claims.put("jti", jti);
        claims.put("client_id", GATEWAY);
        claims.put("scope", "platform.access");
        claims.put("iat", now);
        claims.put("nbf", now);
        claims.put("exp", now + ttlSeconds);
        claims.put("tenant_id", DelegatedTickets.TENANT_ID);
        claims.put("sub", subject);
        claims.put("roles", java.util.List.copyOf(DelegatedTickets.ROLES));
        claims.put("attributes", Map.of("region", java.util.List.copyOf(
                DelegatedTickets.ATTRIBUTES.get("region"))));
        if (origin != null) {
            claims.put("origin_authentication_id", origin);
        }
        return keys.sign("at+jwt", claims);
    }

    protected JsonNode verified(String token, String type) {
        return TestJwt.verifiedClaims(token, type, publishedKey());
    }

    /**
     * 一张形状合法（自签、at+jwt、service、delegated、gateway caller、opening 目的）但 jti/decision_id
     * 不在登记簿里的 opening 票：用来区分"验签/形状拒绝"与"找不到 S0 决策"。
     */
    protected String orphanOpeningTicket(String jti, String decisionId, String grantRef) {
        long now = Instant.now().getEpochSecond();
        ServiceCall call = metadataCall();
        Map<String, Object> resource = Map.of("kind", call.resource().kind(), "id", call.resource().id(),
                "version_digest", call.resource().versionDigest());
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("operation_id", "op-" + decisionId);
        operation.put("source_kind", "PORTAL");
        operation.put("origin_kind", "PORTAL");
        operation.put("issued_at", now);
        operation.put("expires_at", now + 60);
        operation.put("grant_ref", grantRef);
        operation.put("tenant_id", DelegatedTickets.TENANT_ID);
        operation.put("sub", DelegatedTickets.SUBJECT);
        operation.put("resource", resource);
        operation.put("ceiling_sha256", AuthorityProtocol.ceiling(DelegatedTickets.actor()));

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", ISSUER);
        claims.put("aud", "urn:agent-platform:service:" + call.target());
        claims.put("token_use", "service");
        claims.put("iat", now);
        claims.put("nbf", now);
        claims.put("exp", now + 60);
        claims.put("jti", jti);
        claims.put("target", call.target());
        claims.put("action", call.action());
        claims.put("resource", resource);
        claims.put("scope", call.action());
        claims.put("caller", GATEWAY);
        claims.put("client_id", GATEWAY);
        claims.put("tenant_id", DelegatedTickets.TENANT_ID);
        claims.put("sub", DelegatedTickets.SUBJECT);
        claims.put("roles", java.util.List.copyOf(DelegatedTickets.ROLES));
        claims.put("attributes", Map.of("region", java.util.List.copyOf(
                DelegatedTickets.ATTRIBUTES.get("region"))));
        claims.put("authority_mode", "delegated");
        claims.put("decision_id", decisionId);
        claims.put("grant_ref", grantRef);
        claims.put("grant_expires_at", now + 60);
        claims.put("authorization_version", DelegatedTickets.AUTHORIZATION_VERSION);
        claims.put("initial_ceiling", DelegatedTickets.actorClaims());
        claims.put("operation", operation);
        claims.put("subject_caller", GATEWAY);
        claims.put("origin_authentication_id", ORIGIN);
        return keys.sign("at+jwt", claims);
    }

    /** 审计断言用：agent.authority.audit 里按 reason 找拒绝行。 */
    protected static boolean hasReason(java.util.List<String> lines, String reason) {
        return lines.stream().anyMatch(line -> line.contains("reason=" + reason));
    }
}
