package cn.agentplatform.authority.api;

import cn.agentplatform.authority.AuthorityException;
import cn.agentplatform.authority.config.AuthorityServiceProperties;
import cn.agentplatform.authority.config.IssuerResolver;
import cn.agentplatform.authority.sign.KeyRing;
import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.authority.ResourceBinding;
import cn.agentplatform.common.authority.ServiceCall;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 票据与决策断言的唯一签发/校验点。
 * <p>票面形状与平台侧校验器逐字段对齐（{@code TrustedJwtDecoder} + {@code AuthorityResponseVerifier}）：
 * <ul>
 *   <li>票据 typ {@code at+jwt}、{@code token_use=service}、aud 是目标服务的 {@code urn:agent-platform:service:<target>}、
 *       {@code caller==client_id==sub}、{@code scope==action}、{@code authority_mode=workload}，
 *       且不含 delegated 专属的 {@code tenant_id/grant_ref/roles/attributes}；</li>
 *   <li>断言 typ {@code agent-authority-decision+jwt}、{@code token_use=current-decision}、aud 是**调用方**的 urn、
 *       带 {@code nonce/request_sha256/active/current}，平台据此确认"这条决策确实对应我这个请求"。</li>
 * </ul>
 * 时限由 {@code ticket-seconds(<=60)} / {@code assertion-seconds(<=5)} 控制，超过平台预算的票一定被拒，
 * 所以这里不提供"签更久"的开关。
 */
@Component
public final class TicketService {

    public static final String SERVICE_AUDIENCE_PREFIX = "urn:agent-platform:service:";
    public static final String ASSERTION_TYPE = "agent-authority-decision+jwt";
    public static final String TICKET_TYPE = "at+jwt";
    public static final String WORKLOAD = "workload";
    /** EX-44 S3：continuation 是"下一次观测会话的接续凭据"，不是票，也不参与服务鉴权。 */
    public static final String CONTINUATION_TYPE = "agent-portal-gateway-events-continuation+jwt";
    public static final String CONTINUATION_USE = "portal-gateway-events-continuation";
    /**
     * EX-45 S4：child 交换签给 engine 的短票。客户端 {@code verifyChildOpen} 按 typ/use 选验证分支，
     * 并把 aud 钉死成 {@code service:engine}；三个值都是协议常量，不能按调用方动态改写。
     */
    public static final String CHILD_OPEN_TYPE = "agent-portal-events-child-open+jwt";
    public static final String CHILD_OPEN_USE = "portal-events-child-open";
    public static final String CHILD_CONTINUATION_TYPE = "agent-portal-engine-events-child-continuation+jwt";
    public static final String CHILD_CONTINUATION_USE = "portal-engine-events-child-continuation";
    public static final String CHILD_RECEIPT_TYPE = "agent-portal-events-child-receipt+jwt";
    public static final String CHILD_RECEIPT_USE = "portal-events-child-receipt";

    private final KeyRing keys;
    private final IssuerResolver issuer;
    private final Clock clock;
    private final int ticketSeconds;
    private final int assertionSeconds;

    public TicketService(KeyRing keys, IssuerResolver issuer, Clock clock, AuthorityServiceProperties properties) {
        this.keys = keys;
        this.issuer = issuer;
        this.clock = clock;
        this.ticketSeconds = properties.ticketSeconds();
        this.assertionSeconds = properties.assertionSeconds();
    }

    /** 签发票据 + 决策断言（{@code operation=client}）。 */
    public Issued ticket(String clientId, AuthorityRequest request) {
        long iat = now();
        String ticketId = newId("tkt");
        String decisionId = newId("dec");
        Map<String, Object> ticketClaims = new LinkedHashMap<>();
        ticketClaims.put("iss", issuer.issuer());
        ticketClaims.put("aud", audience(request.call().target()));
        ticketClaims.put("token_use", "service");
        ticketClaims.put("iat", iat);
        ticketClaims.put("nbf", iat);
        ticketClaims.put("exp", iat + ticketSeconds);
        ticketClaims.put("jti", ticketId);
        ticketClaims.putAll(callClaims(request.call()));
        ticketClaims.put("scope", request.call().action());
        ticketClaims.put("caller", clientId);
        ticketClaims.put("client_id", clientId);
        ticketClaims.put("sub", clientId);
        ticketClaims.put("authority_mode", WORKLOAD);
        String ticket = keys.sign(TICKET_TYPE, ticketClaims);

        Map<String, Object> assertion = assertion(clientId, request, decisionId, iat);
        // 票据本体必须作为断言 claim 回传：平台客户端取的是断言里的 ticket（HttpCurrentAuthorityClient
        // .verifiedTicket 读 envelope["ticket"]），响应体顶层的同一值只用于人工排查。
        assertion.put("ticket", ticket);
        assertion.put("ticket_jti", ticketId);
        assertion.put("subject_caller", clientId);
        assertion.put("authority_mode", WORKLOAD);
        return new Issued(keys.sign(ASSERTION_TYPE, assertion), ticket, ticketId, decisionId);
    }

    /**
     * {@code operation=authorize_workload} 的决策断言：只允许 {@code caller -> target} 这一跳，
     * 断言只描述"这次调用被许可"，不携带任何可提升权限的字段。
     */
    public Map<String, Object> workloadAssertion(String clientId, AuthorityRequest request, TicketView ticket) {
        long iat = now();
        Map<String, Object> assertion = assertion(clientId, request, newId("dec"), iat);
        assertion.put("ticket_jti", ticket.jti());
        assertion.put("subject_caller", ticket.caller());
        assertion.put("authority_mode", WORKLOAD);
        return Map.of("assertion", keys.sign(ASSERTION_TYPE, assertion));
    }

    /** 校验调用方带来的 workload 票据，并要求票面与本次请求的 target/action/resource 完全相等。 */
    public TicketView verifyWorkloadTicket(String token, AuthorityRequest request) {
        JsonNode claims = keys.verify(token, TICKET_TYPE);
        if (!"service".equals(text(claims, "token_use"))) {
            throw AuthorityException.denied("ticket.token_use != service");
        }
        if (!WORKLOAD.equals(text(claims, "authority_mode"))) {
            throw AuthorityException.denied("ticket.authority_mode != workload");
        }
        if (!issuer.issuer().equals(text(claims, "iss"))) {
            throw AuthorityException.denied("ticket.iss 与本服务不一致");
        }
        ServiceCall call = readCall(claims);
        if (!call.equals(request.call())) {
            throw AuthorityException.denied("ticket 的 target/action/resource 与请求不一致");
        }
        if (!audience(call.target()).equals(audienceOf(claims))) {
            throw AuthorityException.denied("ticket.aud 不是目标服务");
        }
        String caller = text(claims, "caller");
        if (caller == null || !caller.equals(text(claims, "sub")) || !caller.equals(text(claims, "client_id"))) {
            throw AuthorityException.denied("ticket 的 caller/sub/client_id 不自洽");
        }
        return new TicketView(text(claims, "jti"), caller, call);
    }

    public String audience(String serviceId) {
        return SERVICE_AUDIENCE_PREFIX + serviceId;
    }

    /**
     * EX-44 S3 的 continuation：S3 断言信封里 {@code continuation_ref} 指向的那张 JWT。
     *
     * <p>与票/断言的差别是刻意的：
     * <ul>
     *   <li>typ 是独立的 {@code agent-portal-gateway-events-continuation+jwt}，客户端按 typ 选择验证分支，</li>
     *   <li>{@code exp} 必须**等于** immutable 里的 {@code hard_deadline}（客户端会逐值比对），不能按
     *       {@code assertionSeconds} 重算——否则一次长观测在 S4 续期时会被自己的 exp 卡住；</li>
     *   <li>{@code immutable} 原样随签，供下游续期时证明"还是同一次观测"；</li>
     *   <li>不携带 {@code service_ticket}/{@code parent_response_id}/{@code completion_permit}：
     *       continuation 不能当票用，也不能借它拼接两段授权。</li>
     * </ul>
     */
    public String continuation(String clientId, String jti, JsonNode immutable, Instant hardDeadline, long iat) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer.issuer());
        claims.put("aud", audience(clientId));
        claims.put("token_use", CONTINUATION_USE);
        claims.put("iat", iat);
        claims.put("nbf", iat);
        claims.put("exp", hardDeadline.getEpochSecond());
        claims.put("jti", jti);
        claims.put("continuation_jti", jti);
        claims.put("hard_deadline", hardDeadline.getEpochSecond());
        claims.put("immutable", immutable);
        return keys.sign(CONTINUATION_TYPE, claims);
    }

    /**
     * EX-45：{@code child_open}——"engine 可以按这份 association 打开子观测"的一次性证明。
     *
     * <p>与 continuation 的区别：它是<b>给 engine 验的票</b>（aud=service:engine、use=portal-events-child-open），
     * 生命周期不得超过客户端 55s 硬窗，且 {@code exp} 必须恰为 association 的 {@code hard_deadline}
     * （engine 侧 {@code requireAssociationToken} 会做 {@code same(exp, association.hard_deadline)}）。
     * 三个绑定值（association 摘要、父子 responseId）让它无法被挪到另一次 child 授权上。
     */
    public String childOpen(String target, String associationSha256, String parentResponseId,
                            String childResponseId, Instant hardDeadline, long iat) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer.issuer());
        claims.put("aud", audience(target));
        claims.put("token_use", CHILD_OPEN_USE);
        claims.put("iat", iat);
        claims.put("nbf", iat);
        claims.put("exp", hardDeadline.getEpochSecond());
        claims.put("jti", newId("copen"));
        claims.put("association_sha256", associationSha256);
        claims.put("parent_response_id", parentResponseId);
        claims.put("child_response_id", childResponseId);
        return keys.sign(CHILD_OPEN_TYPE, claims);
    }

    /**
     * EX-46：child continuation——engine 侧 child 窗口的续期证明，{@code portal_events_child_check} 的
     * {@code continuation_ref}。
     *
     * <p>与 gateway continuation 的两处刻意差异：{@code aud} 指向 engine（续期请求由 engine 发出），
     * 且只携带 immutable 的<b>摘要</b>而不是整棵节点——客户端 CHILD 分支就是按
     * {@code same(digest(immutable), immutable_sha256)} 校验的。immutable 正文在续期断言里由服务端按
     * 决策回显，客户端再用摘要确认"还是同一次 child 打开"。
     */
    public String childContinuation(String clientId, String jti, String immutableSha256, Instant hardDeadline,
                                    long iat) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer.issuer());
        claims.put("aud", audience(clientId));
        claims.put("token_use", CHILD_CONTINUATION_USE);
        claims.put("iat", iat);
        claims.put("nbf", iat);
        claims.put("exp", hardDeadline.getEpochSecond());
        claims.put("jti", jti);
        claims.put("continuation_jti", jti);
        claims.put("hard_deadline", hardDeadline.getEpochSecond());
        claims.put("immutable_sha256", immutableSha256);
        return keys.sign(CHILD_CONTINUATION_TYPE, claims);
    }

    /**
     * EX-46：{@code child_receipt}——engine 成功打开子观测后交给 gateway 的回执（经首帧 metadata 送回）。
     *
     * <p>客户端 {@code expectedChildImmutable} 重建 immutable 时，只有
     * {@code engine_opening_decision_id / engine_admission_nonce / engine_admission_request_sha256}
     * 三个字段取自回执，其余全部取自 association——所以回执必须原样带回 engine 自己的那三个值，
     * 否则 gateway 重算出的摘要与 engine 手里那份不相等，{@code acceptChildReceipt} 会拒绝。
     * {@code child_immutable_sha256} 就是 engine immutable 的 canonical 摘要，两端口径一致。
     */
    public String childReceipt(String target, String associationSha256, String parentResponseId,
                               String childResponseId, String openingDecisionId, String admissionNonce,
                               String admissionRequestSha256, String childImmutableSha256, Instant hardDeadline,
                               long iat) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer.issuer());
        claims.put("aud", audience(target));
        claims.put("token_use", CHILD_RECEIPT_USE);
        claims.put("iat", iat);
        claims.put("nbf", iat);
        claims.put("exp", hardDeadline.getEpochSecond());
        claims.put("jti", newId("crec"));
        claims.put("association_sha256", associationSha256);
        claims.put("parent_response_id", parentResponseId);
        claims.put("child_response_id", childResponseId);
        claims.put("engine_opening_decision_id", openingDecisionId);
        claims.put("engine_admission_nonce", admissionNonce);
        claims.put("engine_admission_request_sha256", admissionRequestSha256);
        claims.put("child_immutable_sha256", childImmutableSha256);
        return keys.sign(CHILD_RECEIPT_TYPE, claims);
    }

    private Map<String, Object> assertion(String clientId, AuthorityRequest request, String decisionId, long iat) {
        return envelope(clientId, request.call(), request.nonce(), request.raw(), decisionId, iat);
    }

    /**
     * 决策断言骨架：{@code aud=调用方 urn}、{@code token_use=current-decision}、{@code jti=decision_id}、
     * {@code nonce}、{@code request_sha256} 与 {@code active/current}。
     * <p>委派链路（{@code decision.DelegatedAuthority}）复用同一骨架后再补 delegated 专属 claims：
     * 两处各写一份的话，平台客户端会在 nonce/摘要校验上分叉。
     */
    public Map<String, Object> envelope(String clientId, ServiceCall call, String nonce, JsonNode raw,
                                        String decisionId, long iat) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer.issuer());
        claims.put("aud", audience(clientId));
        claims.put("token_use", "current-decision");
        claims.put("iat", iat);
        claims.put("nbf", iat);
        claims.put("exp", iat + assertionSeconds);
        claims.put("jti", decisionId);
        claims.putAll(callClaims(call));
        claims.put("nonce", nonce);
        claims.put("request_sha256", Digest.sha256(Json.canonical(raw)));
        claims.put("client_id", clientId);
        claims.put("active", true);
        claims.put("current", true);
        return claims;
    }

    private static Map<String, Object> callClaims(ServiceCall call) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("kind", call.resource().kind());
        resource.put("id", call.resource().id());
        resource.put("version_digest", call.resource().versionDigest());
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("target", call.target());
        claims.put("action", call.action());
        claims.put("resource", resource);
        return claims;
    }

    private static ServiceCall readCall(JsonNode claims) {
        JsonNode resource = claims.get("resource");
        if (resource == null || !resource.isObject() || resource.size() != 3) {
            throw AuthorityException.denied("ticket.resource 形状非法");
        }
        try {
            return new ServiceCall(
                    requiredText(claims, "target"),
                    requiredText(claims, "action"),
                    new ResourceBinding(
                            requiredText(resource, "kind"),
                            requiredText(resource, "id"),
                            requiredText(resource, "version_digest")));
        } catch (IllegalArgumentException e) {
            throw AuthorityException.denied("ticket.resource 字段非法");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw AuthorityException.denied("ticket 缺少 " + field);
        }
        return value;
    }

    private static String audienceOf(JsonNode claims) {
        JsonNode aud = claims.get("aud");
        if (aud != null && aud.isTextual()) {
            return aud.textValue();
        }
        if (aud != null && aud.isArray() && aud.size() == 1 && aud.get(0).isTextual()) {
            return aud.get(0).textValue();
        }
        throw AuthorityException.denied("ticket.aud 形状非法");
    }

    private long now() {
        // 秒级截断：票面 iat/nbf/exp 都是 epoch 秒，客户端按截断后的时刻比对 iat。
        return clock.instant().truncatedTo(ChronoUnit.SECONDS).getEpochSecond();
    }

    private static String newId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** 签发结果：断言给调用方证明决策，票据给调用方做下一跳。 */
    public record Issued(String assertion, String ticket, String ticketId, String decisionId) {

        public Map<String, Object> asResponse() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("assertion", assertion);
            body.put("ticket", ticket);
            return body;
        }
    }

    /** 已验证票据的最小视图（不含断言，只用于决策）。 */
    public record TicketView(String jti, String caller, ServiceCall call) {
        public TicketView {
            Objects.requireNonNull(jti, "jti");
            Objects.requireNonNull(caller, "caller");
            Objects.requireNonNull(call, "call");
        }
    }
}
