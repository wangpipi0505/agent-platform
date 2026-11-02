package cn.agentplatform.common.authority;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * EX-45 切片 1e（S4 {@code portal_events_child_exchange}）的请求素材。
 *
 * <p>复用 1d 的 S0→S3 全链，再把 {@code HttpCurrentAuthorityClient.exchangePortalEventsChild}
 * （客户端 {@code :672-690}）的请求体原样搬到服务端：
 * {@code subject_token + parent_continuation + parent_immutable}，call 固定为
 * {@code engine / proxy.engine.get.RunController.events}。<b>不能</b>换成 gateway 目标——
 * 客户端 {@code verifyAssociation}/{@code verifiedChildReceiver} 都按 engine 观测行校验。
 *
 * <p>负例素材（过期/越界 continuation）必须用本服务 {@code KeyRing} 重签：验签由 KeyRing 完成，
 * 随便拼字符串只会在 {@code keys.verify} 处被判 403，测不到"签名合法但内容越界"的分支。
 */
abstract class GatewayEventsSlice1eSupport extends GatewayEventsSlice1dSupport {

    static final String CHILD_EXCHANGE = "portal_events_child_exchange";
    static final String CHILD_OPEN_TYPE = "agent-portal-events-child-open+jwt";
    static final String CHILD_OPEN_USE = "portal-events-child-open";
    static final String CHILD_ASSOCIATION_SCHEMA = "portal-events-child-association.v1";
    static final String ENGINE_AUDIENCE = "urn:agent-platform:service:engine";

    /** 一条已经开好窗的父观测：S3 断言 claim、continuation 原文、immutable 与 binding 都在这里。 */
    protected record Parent(Flow flow, PortalEventsBinding binding, JsonNode immutable, JsonNode claims,
                            String continuation) {
    }

    protected Parent window(String name) {
        return window(name, nowPlusSeconds(40));
    }

    protected Parent window(String name, Instant hardDeadline) {
        Flow flow = flow(name);
        PortalEventsBinding binding = binding(flow, 7, hardDeadline);
        JsonNode immutable = immutable(flow, binding);
        ResponseEntity<JsonNode> response = openEvents(flow, binding, immutable, "n-s3-" + name);
        org.assertj.core.api.Assertions.assertThat(response.getStatusCode())
                .as("S3 body: %s", response.getBody()).isEqualTo(org.springframework.http.HttpStatus.OK);
        JsonNode claims = claimsOf(response.getBody());
        return new Parent(flow, binding, immutable, claims, text(claims, "continuation_ref"));
    }

    protected ServiceCall childCall(Parent parent) {
        return new ServiceCall(ENGINE, RunObservationSelectors.EVENTS_ACTION, parent.binding().resource());
    }

    /** 正例：continuation 与 immutable 都取自 S3 响应，主体令牌仍是同一张 portal 票。 */
    protected ResponseEntity<JsonNode> childExchange(Parent parent, JsonNode immutable, String nonce) {
        return childExchange(parent, parent.continuation(), immutable, parent.flow().portal(), nonce);
    }

    protected ResponseEntity<JsonNode> childExchange(Parent parent, String continuation, JsonNode immutable,
                                                     String subjectToken, String nonce) {
        return childExchangeAs(GATEWAY, GATEWAY_SECRET, parent, continuation, immutable, subjectToken, nonce);
    }

    protected ResponseEntity<JsonNode> childExchangeAs(String clientId, String secret, Parent parent,
                                                       String continuation, JsonNode immutable, String subjectToken,
                                                       String nonce) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("subject_token", subjectToken);
        extra.put("parent_continuation", continuation);
        extra.put("parent_immutable", immutable);
        return postJson("/internal/authorize", basic(clientId, secret),
                command(CHILD_EXCHANGE, childCall(parent), nonce, extra));
    }

    /** 已经过期（exp 在 now 之前）但签名合法的 continuation。 */
    protected String expiredContinuation(Parent parent) {
        long iat = Instant.now().minusSeconds(30).getEpochSecond();
        return signContinuation(parent, iat, iat + 5, parent.immutable());
    }

    /** 把 {@code hard_deadline}/{@code exp} 改成指定值，其余字段与真实 continuation 一致。 */
    protected String reforge(Parent parent, long hardDeadline, JsonNode immutable) {
        return signContinuation(parent, Instant.now().getEpochSecond(), hardDeadline, immutable);
    }

    protected JsonNode association(JsonNode claims) {
        return claims.path("portal_events_child").path("association");
    }

    private String signContinuation(Parent parent, long iat, long exp, JsonNode immutable) {
        JsonNode original = verified(parent.continuation(), CONTINUATION_TYPE);
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", ISSUER);
        claims.put("aud", "urn:agent-platform:service:" + GATEWAY);
        claims.put("token_use", CONTINUATION_USE);
        claims.put("iat", iat);
        claims.put("nbf", iat);
        claims.put("exp", exp);
        claims.put("jti", text(original, "jti"));
        claims.put("continuation_jti", text(original, "continuation_jti"));
        claims.put("hard_deadline", exp);
        claims.put("immutable", immutable);
        return keys.sign(CONTINUATION_TYPE, claims);
    }
}
