package cn.agentplatform.authority.decision;

import cn.agentplatform.authority.AuthorityException;
import cn.agentplatform.authority.api.AuthorityRequest;
import cn.agentplatform.authority.api.TicketService;
import cn.agentplatform.authority.config.AuthorityServiceProperties;
import cn.agentplatform.authority.config.DelegationProperties;
import cn.agentplatform.authority.config.IssuerResolver;
import cn.agentplatform.authority.identity.IdentityProperties;
import cn.agentplatform.authority.route.RouteDirectory;
import cn.agentplatform.authority.sign.KeyRing;
import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.authority.ResourceBinding;
import cn.agentplatform.common.authority.ServiceCall;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 委派判权（{@code operation=authorize}）与转发（{@code operation=forward}）的唯一实现点。
 *
 * <p>两条链路的不变量：
 * <ol>
 *   <li><b>authorize</b> 只接受两种主体来源：gateway 持有的 portal 用户令牌（{@code subject_token}），
 *       或上游签发的 delegated 服务票据（{@code service_ticket}，平台真实客户端走这条）。
 *       主体身份（tenant/sub/roles/attributes）与上级上限（initial_ceiling）**原样回显**，
 *       本服务从不自行扩权；</li>
 *   <li><b>forward</b> 只允许把一张已登记、未过期的决策转给**目录里存在的 delegated 行**，
 *       且 resource 必须沿用决策内资源（换资源等于换授权对象），主体、grant、operation 一律不变——
 *       转发只能缩短或保持授权范围，不能延长 grant（票据 exp ≤ 决策 grant_expires_at）；</li>
 *   <li>两条链路都写 {@code authority.audit}：permit/deny 各一条，字段是
 *       caller/target/action/decision_id/reason，永不写令牌原文。</li>
 * </ol>
 *
 * <p>与平台客户端的对齐点：断言 claims 必须由 {@link TicketService#envelope} 生成骨架
 * （aud=调用方 urn、nonce、request_sha256、active/current），否则 {@code HttpCurrentAuthorityClient.query}
 * 会在本地拒绝；delegated 票据的 claims 必须满足 {@code verifiedTicket} + {@code AuthorityProtocol.operation}
 * （caller==client_id、scope==action、sub/tenant 是主体而不是服务、带 grant/operation/ceiling）。
 */
@Component
public final class DelegatedAuthority {

    public static final String AUTHORIZE = "authorize";
    public static final String FORWARD = "forward";
    public static final String MODE = "delegated";
    /** portal 令牌的签发方与使用者都是 gateway：委派判权只允许它发起。 */
    public static final String PORTAL_CALLER = "gateway";

    private static final Logger audit = LoggerFactory.getLogger("agent.authority.audit");
    private static final Set<String> RESERVED_ROLES = Set.of("SERVICE", "GATEWAY_AUTH");
    private static final Set<String> ATTRIBUTE_KEYS = Set.of("region", "dataLabels", "bizSystemCode", "userId");
    /** 委派来源：本服务只从"人"（PORTAL）或"上游已委派的票据"出发，永不从 INTERNAL 自举。 */
    private static final String SOURCE_KIND = "PORTAL";

    private final KeyRing keys;
    private final TicketService tickets;
    private final RouteDirectory routes;
    private final DecisionStore decisions;
    private final DelegationProperties delegation;
    private final AuthorityServiceProperties properties;
    private final IdentityProperties identity;
    private final IssuerResolver issuer;
    private final Clock clock;

    public DelegatedAuthority(KeyRing keys, TicketService tickets, RouteDirectory routes, DecisionStore decisions,
                              DelegationProperties delegation, AuthorityServiceProperties properties,
                              IdentityProperties identity, IssuerResolver issuer, Clock clock) {
        this.keys = keys;
        this.tickets = tickets;
        this.routes = routes;
        this.decisions = decisions;
        this.delegation = delegation;
        this.properties = properties;
        this.identity = identity;
        this.issuer = issuer;
        this.clock = clock;
    }

    /** {@code operation=authorize}：返回一条 {@code assertion}（决策断言），决策同时进 {@link DecisionStore}。 */
    public Map<String, Object> authorize(String clientId, AuthorityRequest request) {
        try {
            requireEnabled();
            DecisionStore.Decision decision = request.serviceTicket() != null
                    ? fromTicket(clientId, request)
                    : fromSubject(clientId, request);
            Map<String, Object> claims = authorityClaims(decision, request, now());
            audit.info("delegation.audit endpoint=decision outcome=permit operation=authorize caller={} target={}"
                            + " action={} decision_id={} subject_caller={} ticket_jti={} grant_ref={} subject={}/{}",
                    clientId, decision.target(), decision.action(), decision.id(), decision.subjectCaller(),
                    decision.ticketJti(), decision.grantRef(),
                    decision.actor().get("tenant_id"), decision.actor().get("sub"));
            return Map.of("assertion", keys.sign(TicketService.ASSERTION_TYPE, claims));
        } catch (AuthorityException denied) {
            deny("decision", AUTHORIZE, clientId, request, denied);
            throw denied;
        }
    }

    /** {@code operation=forward}：为**新目标**签一张 delegated 票据，并把这一跳登记成新决策（不延长 grant）。 */
    public Map<String, Object> forward(String clientId, AuthorityRequest request) {
        try {
            requireEnabled();
            String decisionId = required(request.decisionId(), "decision_id");
            String grantRef = required(request.grantRef(), "grant_ref");
            String operationId = required(request.operationId(), "operation_id");
            DecisionStore.Decision decision = decisions.find(decisionId)
                    .orElseThrow(() -> AuthorityException.denied("DECISION_UNKNOWN"));
            long now = now();
            if (!decision.expiresAt().isAfter(Instant.ofEpochSecond(now))) {
                throw AuthorityException.expired("DECISION_EXPIRED");
            }
            // grant 到期后即使决策还在存储里也不能再转发：票据 exp 不能早于 iat，晚于 grant 就是延长授权。
            if (!decision.grantExpiresAt().isAfter(Instant.ofEpochSecond(now))) {
                throw AuthorityException.expired("GRANT_EXPIRED");
            }
            // 只有签发这张决策的调用方可以转发它：否则任何一个服务都能顺着别人的决策往下走。
            if (!clientId.equals(decision.caller())) {
                throw AuthorityException.denied("FORWARD_CALLER");
            }
            if (!grantRef.equals(decision.grantRef())
                    || !operationId.equals(Objects.toString(decision.operation().get("operation_id"), null))) {
                throw AuthorityException.denied("FORWARD_GRANT");
            }
            // resource 就是授权对象：换资源转发等于换一个东西授权，按越权处理。
            if (!decision.resource().equals(resourceOf(request.call()))) {
                throw AuthorityException.denied("FORWARD_ESCALATION");
            }
            if (!routes.permits(clientId, request.call().target(), request.call().action(), RouteDirectory.DELEGATED)) {
                // 目录里没有 caller->新 target 的 delegated 行：跨目标转发到这里必须停住。
                throw AuthorityException.denied("FORWARD_ESCALATION");
            }
            String ticket = ticket(decision, clientId, request.call(), now);
            DecisionStore.Decision hop = new DecisionStore.Decision(
                    newId("dec"), clientId, request.call().target(), request.call().action(),
                    decision.resource(), decision.actor(), decision.ceiling(), decision.operation(),
                    decision.grantRef(), decision.grantExpiresAt(), decision.subjectCaller(), decision.ticketJti(),
                    decision.authorizationVersion(),
                    earlier(decision.expiresAt(), Instant.ofEpochSecond(now).plusSeconds(delegation.decisionSeconds())));
            decisions.put(hop);
            Map<String, Object> claims = authorityClaims(hop, request, now);
            claims.put("ticket", ticket);
            audit.info("delegation.audit endpoint=decision outcome=permit operation=forward caller={} target={}"
                            + " action={} decision_id={} next_decision_id={} grant_ref={}",
                    clientId, request.call().target(), request.call().action(), decisionId, hop.id(), grantRef);
            return Map.of("assertion", keys.sign(TicketService.ASSERTION_TYPE, claims), "ticket", ticket);
        } catch (AuthorityException denied) {
            deny("decision", FORWARD, clientId, request, denied);
            throw denied;
        }
    }

    /** portal 用户令牌换决策：调用方必须是 gateway，令牌必须是本服务签的、给本 portal 受众的 user 令牌。 */
    private DecisionStore.Decision fromSubject(String clientId, AuthorityRequest request) {
        if (!PORTAL_CALLER.equals(clientId)) {
            throw AuthorityException.denied("SUBJECT_CALLER_NOT_GATEWAY");
        }
        String token = request.subjectToken();
        if (token == null) {
            throw AuthorityException.denied("SUBJECT_UNAVAILABLE");
        }
        JsonNode claims = keys.verify(token, TicketService.TICKET_TYPE);
        if (!"user".equals(text(claims, "token_use"))) {
            throw AuthorityException.denied("SUBJECT_TOKEN_USE");
        }
        if (!issuer.issuer().equals(text(claims, "iss"))) {
            throw AuthorityException.denied("SUBJECT_ISSUER");
        }
        String audience = identity.audience();
        if (audience == null || audience.isBlank() || !audience.equals(audienceOf(claims))) {
            throw AuthorityException.denied("SUBJECT_AUDIENCE");
        }
        String subjectClient = identity.clientId();
        if (subjectClient != null && !subjectClient.isBlank() && !subjectClient.equals(text(claims, "client_id"))) {
            throw AuthorityException.denied("SUBJECT_CLIENT");
        }
        String tenantId = required(claims, "tenant_id");
        String principalId = required(claims, "sub");
        Set<String> roles = rolesOf(claims.get("roles"));
        Map<String, Set<String>> attributes = attributesOf(claims.get("attributes"));
        Map<String, Object> actor = actor(tenantId, principalId, roles, attributes);
        // 回显给客户端的 initial_ceiling 必须是 Actor 形状：客户端用 actor() 反序列化后重算
        // ceiling(Actor) 再与 operation.ceiling_sha256 比对，喂摘要材料（{owner,roles,attributes}）会被判越权。
        String ceilingSha256 = Digest.sha256(Json.canonical(ceiling(tenantId, principalId, roles, attributes)));
        String grantRef = newId("grt");
        Instant issued = Instant.ofEpochSecond(now());
        Instant grantExpiresAt = issued.plusSeconds(delegation.grantSeconds());
        Map<String, Object> operation = operation(newId("op"), issued, grantExpiresAt, grantRef, tenantId,
                principalId, request.call(), ceilingSha256);
        DecisionStore.Decision decision = new DecisionStore.Decision(newId("dec"), clientId,
                request.call().target(), request.call().action(), resourceOf(request.call()), actor, actor,
                operation, grantRef, grantExpiresAt, Objects.requireNonNullElse(subjectClient, PORTAL_CALLER), null,
                authorizationVersion(), issued.plusSeconds(delegation.decisionSeconds()));
        decisions.put(decision);
        return decision;
    }

    /**
     * 上游 delegated 服务票据换决策（平台真实客户端走这条）。票据是**上游**持有的凭据，
     * 因此这里不要求 {@code caller==target} 那样的 workload 约束，但要求调用方就是票据的接收方。
     */
    private DecisionStore.Decision fromTicket(String clientId, AuthorityRequest request) {
        JsonNode claims = keys.verify(request.serviceTicket(), TicketService.TICKET_TYPE);
        if (!"service".equals(text(claims, "token_use"))) {
            throw AuthorityException.denied("TICKET_TOKEN_USE");
        }
        if (!MODE.equals(text(claims, "authority_mode"))) {
            throw AuthorityException.denied("TICKET_MODE");
        }
        if (!issuer.issuer().equals(text(claims, "iss"))) {
            throw AuthorityException.denied("TICKET_ISSUER");
        }
        ServiceCall call = callOf(claims);
        if (!call.equals(request.call())) {
            throw AuthorityException.denied("TICKET_CALL_MISMATCH");
        }
        if (!tickets.audience(call.target()).equals(audienceOf(claims))) {
            throw AuthorityException.denied("TICKET_AUDIENCE");
        }
        if (!clientId.equals(call.target())) {
            throw AuthorityException.denied("TICKET_CONSUMER");
        }
        String upstream = required(claims, "caller");
        if (!upstream.equals(text(claims, "client_id"))) {
            throw AuthorityException.denied("TICKET_CALLER");
        }
        if (!routes.permits(upstream, call.target(), call.action(), RouteDirectory.DELEGATED)) {
            throw AuthorityException.denied("ROUTE_NOT_REGISTERED");
        }
        String tenantId = required(claims, "tenant_id");
        String principalId = required(claims, "sub");
        Set<String> roles = rolesOf(claims.get("roles"));
        Map<String, Set<String>> attributes = attributesOf(claims.get("attributes"));
        JsonNode ceilingNode = claims.get("initial_ceiling");
        if (ceilingNode == null || !ceilingNode.isObject()) {
            throw AuthorityException.denied("TICKET_CEILING");
        }
        JsonNode operationNode = claims.get("operation");
        if (operationNode == null || !operationNode.isObject()) {
            throw AuthorityException.denied("TICKET_OPERATION");
        }
        Map<String, Object> operation = toMap(operationNode);
        String grantRef = required(claims, "grant_ref");
        if (!grantRef.equals(Objects.toString(operation.get("grant_ref"), null))
                || !tenantId.equals(Objects.toString(operation.get("tenant_id"), null))
                || !principalId.equals(Objects.toString(operation.get("sub"), null))) {
            throw AuthorityException.denied("TICKET_OPERATION");
        }
        // 上限自洽：票据声明的 initial_ceiling 必须真的对应 operation.ceiling_sha256，否则一张被错误签发的
        // 上游票就能把范围更宽的 ceiling 夹带进 next hop（平台侧 verifyCeiling 会拒绝，但这里先拒更早也更好排查）。
        String expectedCeiling = Digest.sha256(Json.canonical(ceiling(tenantId, principalId, roles, attributes)));
        if (!expectedCeiling.equals(Objects.toString(operation.get("ceiling_sha256"), null))) {
            throw AuthorityException.denied("TICKET_CEILING");
        }
        JsonNode expiresNode = operationNode.get("expires_at");
        if (expiresNode == null || !expiresNode.isIntegralNumber()) {
            throw AuthorityException.denied("TICKET_OPERATION");
        }
        Instant grantExpiresAt = Instant.ofEpochSecond(expiresNode.longValue());
        Instant issued = Instant.ofEpochSecond(now());
        DecisionStore.Decision decision = new DecisionStore.Decision(newId("dec"), clientId, call.target(),
                call.action(), resourceOf(call), actor(tenantId, principalId, roles, attributes),
                toMap(ceilingNode), operation, grantRef, grantExpiresAt, upstream, text(claims, "jti"),
                required(claims, "authorization_version"),
                earlier(issued.plusSeconds(delegation.decisionSeconds()), grantExpiresAt));
        decisions.put(decision);
        return decision;
    }

    /**
     * 决策断言：骨架来自 {@link TicketService#envelope}（aud/nonce/request_sha256/active/current 全由它兜底），
     * 这里只补 delegated 专属字段。
     */
    private Map<String, Object> authorityClaims(DecisionStore.Decision decision, AuthorityRequest request, long iat) {
        Map<String, Object> claims = tickets.envelope(decision.caller(), request.call(), request.nonce(),
                request.raw(), decision.id(), iat);
        claims.put("authority_mode", MODE);
        claims.put("decision_id", decision.id());
        claims.putAll(decision.actor());
        claims.put("subject_caller", decision.subjectCaller());
        claims.put("initial_ceiling", decision.ceiling());
        claims.put("grant_ref", decision.grantRef());
        claims.put("grant_expires_at", decision.grantExpiresAt().getEpochSecond());
        claims.put("authorization_version", decision.authorizationVersion());
        claims.put("operation", decision.operation());
        if (decision.ticketJti() != null) {
            claims.put("ticket_jti", decision.ticketJti());
        }
        return claims;
    }

    /**
     * delegated 服务票据：{@code caller=client_id=转发方}，但 {@code sub=主体}（与 workload 票相反），
     * 且必须带租户/角色/属性/grant/operation —— 平台侧 {@code verifiedTicket} 的 delegated 分支逐项比对。
     * {@code exp} 取 {@code min(iat+ticket-seconds, grant_expires_at)}：转发不允许延长授权。
     */
    private String ticket(DecisionStore.Decision decision, String clientId, ServiceCall call, long iat) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer.issuer());
        claims.put("aud", tickets.audience(call.target()));
        claims.put("token_use", "service");
        claims.put("iat", iat);
        claims.put("nbf", iat);
        claims.put("exp", Math.min(iat + properties.ticketSeconds(), decision.grantExpiresAt().getEpochSecond()));
        claims.put("jti", newId("tkt"));
        claims.put("target", call.target());
        claims.put("action", call.action());
        claims.put("resource", resourceOf(call));
        claims.put("scope", call.action());
        claims.put("caller", clientId);
        claims.put("client_id", clientId);
        claims.putAll(decision.actor());
        claims.put("authority_mode", MODE);
        claims.put("decision_id", decision.id());
        claims.put("grant_ref", decision.grantRef());
        claims.put("grant_expires_at", decision.grantExpiresAt().getEpochSecond());
        claims.put("authorization_version", decision.authorizationVersion());
        claims.put("initial_ceiling", decision.ceiling());
        claims.put("operation", decision.operation());
        claims.put("subject_caller", decision.subjectCaller());
        return keys.sign(TicketService.TICKET_TYPE, claims);
    }

    private void requireEnabled() {
        if (!delegation.enabled()) {
            throw AuthorityException.denied("DELEGATION_DISABLED");
        }
    }

    private void deny(String endpoint, String operation, String clientId, AuthorityRequest request,
                      AuthorityException denied) {
        audit.info("delegation.audit endpoint={} outcome=deny operation={} caller={} target={} action={}"
                        + " decision_id={} status={} code={} reason={}",
                endpoint, operation, clientId, request.call().target(), request.call().action(),
                request.decisionId(), denied.status(), denied.code(), denied.auditReason());
    }

    private Map<String, Object> operation(String operationId, Instant issued, Instant expires, String grantRef,
                                          String tenantId, String principalId, ServiceCall call, String ceilingSha) {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("operation_id", operationId);
        operation.put("source_kind", SOURCE_KIND);
        operation.put("origin_kind", SOURCE_KIND);
        operation.put("issued_at", issued.getEpochSecond());
        operation.put("expires_at", expires.getEpochSecond());
        operation.put("grant_ref", grantRef);
        operation.put("tenant_id", tenantId);
        operation.put("sub", principalId);
        operation.put("resource", resourceOf(call));
        operation.put("ceiling_sha256", ceilingSha);
        return operation;
    }

    private Map<String, Object> actor(String tenantId, String principalId, Set<String> roles,
                                      Map<String, Set<String>> attributes) {
        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("tenant_id", tenantId);
        actor.put("sub", principalId);
        actor.put("roles", List.copyOf(roles));
        Map<String, Object> attrs = new LinkedHashMap<>();
        attributes.forEach((key, values) -> attrs.put(key, List.copyOf(values)));
        actor.put("attributes", attrs);
        return actor;
    }

    /**
     * 与平台 {@code AuthorityProtocol.ceiling(Actor)} 逐字节等价：{@code sha256(canonical({owner,roles,attributes}))}，
     * 其中 owner 是 {@code SubjectKey} 记录（camelCase 组件名）、roles/attributes 的集合元素按 canonical 升序。
     * 哈希不一致会让客户端在 {@code verifyCeiling} 处直接拒绝，所以这里不复用别的序列化路径。
     */
    private static Map<String, Object> ceiling(String tenantId, String principalId, Set<String> roles,
                                               Map<String, Set<String>> attributes) {
        Map<String, Object> owner = new LinkedHashMap<>();
        owner.put("tenantId", tenantId);
        owner.put("principalId", principalId);
        Map<String, Object> attrs = new LinkedHashMap<>();
        attributes.forEach((key, values) -> attrs.put(key, List.copyOf(values)));
        Map<String, Object> ceiling = new LinkedHashMap<>();
        ceiling.put("owner", owner);
        ceiling.put("roles", List.copyOf(roles));
        ceiling.put("attributes", attrs);
        return ceiling;
    }

    private String authorizationVersion() {
        // 平台只做"同一版本"回显比对；委派链路没有独立版本号体系，用固定标记而不是随机值，便于审计聚合。
        return "delegated-v1";
    }

    private static Map<String, Object> resourceOf(ServiceCall call) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("kind", call.resource().kind());
        resource.put("id", call.resource().id());
        resource.put("version_digest", call.resource().versionDigest());
        return resource;
    }

    private static Set<String> rolesOf(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw AuthorityException.denied("SUBJECT_ROLES");
        }
        TreeSet<String> roles = new TreeSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.textValue().isBlank() || !roles.add(item.textValue())) {
                throw AuthorityException.denied("SUBJECT_ROLES");
            }
        }
        // 保留角色永不下发给下游：平台侧 within() 见到 SERVICE/GATEWAY_AUTH 会直接判越权。
        roles.removeAll(RESERVED_ROLES);
        return roles;
    }

    private static Map<String, Set<String>> attributesOf(JsonNode node) {
        Map<String, Set<String>> attributes = new TreeMap<>();
        if (node == null || node.isNull()) {
            return attributes;
        }
        if (!node.isObject()) {
            throw AuthorityException.denied("SUBJECT_ATTRIBUTES");
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!ATTRIBUTE_KEYS.contains(field.getKey()) || !field.getValue().isArray()) {
                throw AuthorityException.denied("SUBJECT_ATTRIBUTES");
            }
            TreeSet<String> values = new TreeSet<>();
            for (JsonNode value : field.getValue()) {
                if (!value.isTextual() || value.textValue().isBlank() || !values.add(value.textValue())) {
                    throw AuthorityException.denied("SUBJECT_ATTRIBUTES");
                }
            }
            attributes.put(field.getKey(), values);
        }
        return attributes;
    }

    private static ServiceCall callOf(JsonNode claims) {
        JsonNode resource = claims.get("resource");
        if (resource == null || !resource.isObject() || resource.size() != 3) {
            throw AuthorityException.denied("TICKET_RESOURCE");
        }
        try {
            return new ServiceCall(required(claims, "target"), required(claims, "action"),
                    new ResourceBinding(required(resource, "kind"), required(resource, "id"),
                            required(resource, "version_digest")));
        } catch (IllegalArgumentException malformed) {
            throw AuthorityException.denied("TICKET_RESOURCE");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(JsonNode node) {
        return new LinkedHashMap<>(Json.mapper().convertValue(node, Map.class));
    }

    private static String audienceOf(JsonNode claims) {
        JsonNode aud = claims.get("aud");
        if (aud != null && aud.isTextual()) {
            return aud.textValue();
        }
        if (aud != null && aud.isArray() && aud.size() == 1 && aud.get(0).isTextual()) {
            return aud.get(0).textValue();
        }
        throw AuthorityException.denied("AUD");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() && !value.textValue().isBlank() ? value.textValue() : null;
    }

    private static String required(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            throw AuthorityException.denied("MISSING_" + field);
        }
        return value;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw AuthorityException.invalidRequest(field + " 缺失", "missing " + field);
        }
        return value;
    }

    private static Instant earlier(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private long now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS).getEpochSecond();
    }

    private static String newId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
