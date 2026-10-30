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

    private Map<String, Object> assertion(String clientId, AuthorityRequest request, String decisionId, long iat) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer.issuer());
        claims.put("aud", audience(clientId));
        claims.put("token_use", "current-decision");
        claims.put("iat", iat);
        claims.put("nbf", iat);
        claims.put("exp", iat + assertionSeconds);
        claims.put("jti", decisionId);
        claims.putAll(callClaims(request.call()));
        claims.put("nonce", request.nonce());
        claims.put("request_sha256", Digest.sha256(Json.canonical(request.raw())));
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
