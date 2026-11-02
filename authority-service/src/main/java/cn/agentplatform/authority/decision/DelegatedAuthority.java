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
import cn.agentplatform.common.authority.OperationBinding;
import cn.agentplatform.common.authority.PortalEventsBinding;
import cn.agentplatform.common.authority.ResourceBinding;
import cn.agentplatform.common.authority.RunObservationSelectors;
import cn.agentplatform.common.authority.ServiceCall;
import cn.agentplatform.common.authority.SubjectKey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.regex.Pattern;

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
    public static final String EXCHANGE = "exchange";
    public static final String MODE = "delegated";
    /** EX-44 S3：为一次 SSE 观测开窗，产物是断言信封 + continuation，不是票。 */
    public static final String EVENTS_OPEN = "portal_gateway_events_open";
    /** portal 令牌的签发方与使用者都是 gateway：委派判权只允许它发起。 */
    public static final String PORTAL_CALLER = "gateway";

    private static final Logger audit = LoggerFactory.getLogger("agent.authority.audit");
    private static final Set<String> RESERVED_ROLES = Set.of("SERVICE", "GATEWAY_AUTH");
    private static final Set<String> ATTRIBUTE_KEYS = Set.of("region", "dataLabels", "bizSystemCode", "userId");
    /** 委派来源：本服务只从"人"（PORTAL）或"上游已委派的票据"出发，永不从 INTERNAL 自举。 */
    private static final String SOURCE_KIND = "PORTAL";
    /** S3 只接受 gateway 观测 profile；engine/child profile 是 EX-45 的另一跳，不能在这里混用。 */
    public static final String GATEWAY_EVENTS_PROFILE = "portal-gateway-events-response.v1";
    /** S0 观测窗的硬上限 55s：60s 票/授权预算里留 5s 时钟余量，客户端也按同一数值夹取。 */
    private static final long EVENTS_OPEN_MAX_SECONDS = 55L;

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
            IssuedTicket ticket = ticket(decision, clientId, request.call(), now);
            DecisionStore.Decision hop = new DecisionStore.Decision(
                    newId("dec"), clientId, request.call().target(), request.call().action(),
                    decision.resource(), decision.actor(), decision.ceiling(), decision.operation(),
                    decision.grantRef(), decision.grantExpiresAt(), decision.subjectCaller(), decision.ticketJti(),
                    decision.originAuthenticationId(), decision.authorizationVersion(),
                    earlier(decision.expiresAt(), Instant.ofEpochSecond(now).plusSeconds(delegation.decisionSeconds())),
                    // 转发只延续授权链：purpose 与 parentAdmissionId 随票透传（1d 的 S3 要顺着它反查 S0），
                    // admission nonce/request_sha256 是**入站请求**的属性，每一跳各有自己的值，不能继承。
                    decision.purpose(), null, null, null, null, null, decision.parentAdmissionId(),
                    null, null, null);
            decisions.put(hop);
            Map<String, Object> claims = authorityClaims(hop, request, now);
            claims.put("ticket", ticket.value());
            audit.info("delegation.audit endpoint=decision outcome=permit operation=forward caller={} target={}"
                            + " action={} decision_id={} next_decision_id={} grant_ref={}",
                    clientId, request.call().target(), request.call().action(), decisionId, hop.id(), grantRef);
            return Map.of("assertion", keys.sign(TicketService.ASSERTION_TYPE, claims), "ticket", ticket.value());
        } catch (AuthorityException denied) {
            deny("decision", FORWARD, clientId, request, denied);
            throw denied;
        }
    }

    /**
     * {@code operation=exchange}：gateway 用一张 portal 用户令牌直接换签给新目标（典型是 SSE 订阅目标）的
     * delegated 票据，不再要求先 {@code authorize} 再 {@code forward} 两跳（EX-43）。
     *
     * <p>与 forward 的区别在入口凭据：forward 换的是已登记决策，exchange 换的是"人"，所以这里重新走
     * {@link #fromSubject} 的主体校验，只额外要求目标落在目录已登记的 delegated 行上。grant 到期时间被夹到
     * 令牌 {@code exp}——票据不能活得比主体凭据更久，否则登录会话已失效、票据仍能继续访问。
     *
     * <p>返回体与 forward 同形（{@code assertion}+{@code ticket}，且断言 claim 里也带 {@code ticket}）：
     * 平台侧 {@code HttpCurrentAuthorityClient.query} 先验决策断言，再从断言 claim 取票，只回 ticket 会被拒。
     *
     * <p>EX-44 变体 A（{@code gateway_events_opening=true}，{@code beginGatewayPortalEvents} 的 S0）在同一入口上
     * 多加两道前置校验：目的只能是 run 观测 metadata 选择器（否则这里就会把任意 delegated 行升级成 opening 票），
     * 主体凭据必须带 {@code origin_authentication_id}（客户端 :594 对 opening 票强制读取，缺了必 403）。
     */
    public Map<String, Object> exchange(String clientId, AuthorityRequest request) {
        try {
            requireEnabled();
            if (!PORTAL_CALLER.equals(clientId)) {
                throw AuthorityException.denied("EXCHANGE_CALLER_NOT_GATEWAY");
            }
            if (request.queryTicket() != null) {
                // 变体 B：拿 S0 (opening) 票换 retained 观测票，并且消费掉那张 opening 决策。
                return exchangeQueryTicket(clientId, request);
            }
            // 变体 B 是进入 gateway 观测目的的唯一入口。没有 query_ticket 的普通 exchange 一旦落到这条
            // action 上，就是 1c 之前那个"跳过 opening 直接换观测票"的绕过路径——必须在验凭据之前就拒绝，
            // 因为目的判定只看请求本身，不依赖主体令牌是否有效（否则无效令牌会伪装成"目的无关"的 403）。
            requireNotRetainedPurpose(request.call());
            if (request.gatewayEventsOpening()) {
                requireOpeningPurpose(request.call());
            }
            if (!routes.permits(clientId, request.call().target(), request.call().action(), RouteDirectory.DELEGATED)) {
                // 换票只能落在已登记的 delegated 行：否则等于凭空给一个新目标发通行证。
                throw AuthorityException.denied("ROUTE_NOT_REGISTERED");
            }
            if (request.subjectToken() == null) {
                throw AuthorityException.denied("SUBJECT_UNAVAILABLE");
            }
            JsonNode subject = keys.verify(request.subjectToken(), TicketService.TICKET_TYPE);
            JsonNode expires = subject.get("exp");
            if (expires == null || !expires.isIntegralNumber()) {
                throw AuthorityException.denied("SUBJECT_EXPIRY");
            }
            long iat = now();
            Instant cap = Instant.ofEpochSecond(expires.longValue());
            if (!cap.isAfter(Instant.ofEpochSecond(iat))) {
                // 令牌在本秒内到期：签出来的票会立刻过期，直接按不可用处理而不是发一张废票。
                throw AuthorityException.denied("SUBJECT_EXPIRY");
            }
            if (request.gatewayEventsOpening() && originOf(subject, "EVENTS_ORIGIN") == null) {
                // origin 是 S1/S2/S3 关联同一跳登录的唯一键，且客户端对 opening 票强制读取。
                // 会话端没写（旧会话、派生失败）时在这里拒绝，而不是签一张"服务端 200、客户端必失败"的票。
                // 若 §10#2 最终确认"origin 可由 authority 从 portal jti 派生"，这个分支应改为派生而非拒绝。
                throw AuthorityException.denied("EVENTS_ORIGIN");
            }
            DecisionStore.Decision decision = fromSubject(clientId, request, cap,
                    request.gatewayEventsOpening() ? DecisionStore.Purpose.OPENING : null);
            IssuedTicket ticket = ticket(decision, clientId, request.call(), iat);
            // 先回写票 jti 再签断言：断言里的 ticket_jti 必须就是随体返回的那张票，
            // 后续 portal_gateway_events_open 靠它把"S0 换出的票"与决策对上（EX-44 §5）。
            DecisionStore.Decision recorded = decision.withTicketJti(ticket.jti());
            decisions.put(recorded);
            Map<String, Object> claims = authorityClaims(recorded, request, iat);
            claims.put("ticket", ticket.value());
            audit.info("delegation.audit endpoint=decision outcome=permit operation=exchange caller={} target={}"
                            + " action={} decision_id={} grant_ref={} grant_expires_at={}",
                    clientId, request.call().target(), request.call().action(), recorded.id(),
                    recorded.grantRef(), recorded.grantExpiresAt().getEpochSecond());
            return Map.of("assertion", keys.sign(TicketService.ASSERTION_TYPE, claims), "ticket", ticket.value());
        } catch (AuthorityException denied) {
            deny("decision", EXCHANGE, clientId, request, denied);
            throw denied;
        }
    }

    /**
     * EX-44 变体 B（S1）：用 S0 签出的 opening 票换"gateway 读 SSE 事件"的 retained 票。
     *
     * <p>安全不变量（缺一不可）：
     * <ol>
     *   <li>票必须是本服务签的、{@code token_use=service}/{@code authority_mode=delegated}/caller=gateway，
     *       且 {@code decision_id} 指向一条 {@code purpose=OPENING} 的决策——不是随便一张 delegated 票；</li>
     *   <li>绑定逐项相等：决策 id、票 jti、origin、portal 主体、portal 令牌原文摘要、opening 的 operation
     *       节点（含 grant_ref/operation_id/resource/ceiling）——换用户、换令牌、换资源都复用不了；</li>
     *   <li>那条 opening 决策必须**未消费**：用 {@link DecisionStore#consume} 的单键 CAS 占位，
     *       并发两次只有一个能签出票；</li>
     *   <li>新票必须新 mint grant_ref 与 operation_id（§4.4），exp 不越过 portal 会话 exp。</li>
     * </ol>
     * 失败一律 403（对外 code 恒 {@code SYS-AUTH-403}），reason 只进审计，见 §5。
     */
    private Map<String, Object> exchangeQueryTicket(String clientId, AuthorityRequest request) {
        requireRetainedPurpose(request.call());
        if (!routes.permits(clientId, request.call().target(), request.call().action(), RouteDirectory.DELEGATED)) {
            throw AuthorityException.denied("ROUTE_NOT_REGISTERED");
        }
        if (request.subjectToken() == null) {
            throw AuthorityException.denied("SUBJECT_UNAVAILABLE");
        }
        JsonNode subject = verifyPortalUser(request.subjectToken());
        String tenantId = required(subject, "tenant_id");
        String principalId = required(subject, "sub");
        String origin = originOf(subject, "EVENTS_ORIGIN");
        if (origin == null) {
            // 1b 已要求 S0 的 portal 令牌带 origin；S1 再验一次，否则一张旧令牌能换出一张无 origin 的观测票，
            // 客户端 :627 会立刻 403，服务端却在存储里留下一条无法归因的授权。
            throw AuthorityException.denied("EVENTS_ORIGIN");
        }
        JsonNode expires = subject.get("exp");
        if (expires == null || !expires.isIntegralNumber()) {
            throw AuthorityException.denied("SUBJECT_EXPIRY");
        }
        long iat = now();
        Instant cap = Instant.ofEpochSecond(expires.longValue());
        if (!cap.isAfter(Instant.ofEpochSecond(iat))) {
            throw AuthorityException.denied("SUBJECT_EXPIRY");
        }

        JsonNode query = verifyQueryTicket(request.queryTicket());
        String jti = text(query, "jti");
        String admissionId = text(query, "decision_id");
        String openingGrantRef = text(query, "grant_ref");
        // 决策里的 operation 是内存 Map（epoch 秒是 Long），票面经过 JSON 往返后会变成 Integer；
        // 直接 Map.equals 会因为装箱类型不同而误判绑定失败，所以两侧都归一到规范 JSON 文本再比。
        String queryOperation = Json.canonical(query.get("operation"));

        // 1) 找到 S0 决策：找不到（或已被淘汰/不是 opening）→ UNKNOWN，不与绑定失败混报。
        DecisionStore.Decision opening = decisions.findByTicketJti(jti)
                .orElseThrow(() -> AuthorityException.denied("EVENTS_TICKET_UNKNOWN"));
        if (opening.purpose() != DecisionStore.Purpose.OPENING) {
            throw AuthorityException.denied("EVENTS_TICKET_UNKNOWN");
        }

        // 2) 绑定：票面声明必须与 S0 决策逐项相等。任何一项不等都说明这张票被挪用/篡改。
        ServiceCall openingCall = callOf(query);
        boolean bound = admissionId.equals(opening.id())
                && jti.equals(opening.ticketJti())
                && openingGrantRef.equals(opening.grantRef())
                && queryOperation.equals(Json.canonical(Json.mapper().valueToTree(opening.operation())))
                && openingCall.target().equals(opening.target())
                && openingCall.action().equals(opening.action())
                && resourceOf(openingCall).equals(opening.resource())
                && origin.equals(text(query, "origin_authentication_id"))
                && Objects.equals(opening.originAuthenticationId(), origin)
                && tenantId.equals(Objects.toString(opening.actor().get("tenant_id"), null))
                && principalId.equals(Objects.toString(opening.actor().get("sub"), null))
                && Digest.sha256(Json.canonical(request.subjectToken())).equals(opening.sourceTokenSha256());
        if (!bound) {
            throw AuthorityException.denied("EVENTS_TICKET_BINDING");
        }

        // 3) 一次性：先给"重放"一个明确的 reason；真正的原子判定在下面的 consume。
        if (opening.consumedAt() != null) {
            throw AuthorityException.denied("EVENTS_TICKET_CONSUMED");
        }
        if (!opening.expiresAt().isAfter(Instant.ofEpochSecond(iat))) {
            throw AuthorityException.denied("EVENTS_TICKET_EXPIRED");
        }

        // 4) 新 mint grant/operation：绝不能复用 S0 的，否则 S1 拿到的还是同一份授权（§4.4）。
        Set<String> roles = rolesOf(subject.get("roles"));
        Map<String, Set<String>> attributes = attributesOf(subject.get("attributes"));
        Map<String, Object> actor = actor(tenantId, principalId, roles, attributes);
        String ceilingSha256 = Digest.sha256(Json.canonical(ceiling(tenantId, principalId, roles, attributes)));
        String grantRef = newId("grt");
        Instant issued = Instant.ofEpochSecond(iat);
        Instant grantExpiresAt = issued.plusSeconds(delegation.grantSeconds());
        if (cap.isBefore(grantExpiresAt)) {
            grantExpiresAt = cap;
        }
        Map<String, Object> operation = operation(newId("op"), issued, grantExpiresAt, grantRef, tenantId,
                principalId, request.call(), ceilingSha256);
        DecisionStore.Decision retained = new DecisionStore.Decision(newId("dec"), clientId,
                request.call().target(), request.call().action(), resourceOf(request.call()), actor, actor,
                operation, grantRef, grantExpiresAt, opening.subjectCaller(), null, origin, authorizationVersion(),
                issued.plusSeconds(delegation.decisionSeconds()), DecisionStore.Purpose.RETAINED,
                Digest.sha256(Json.canonical(request.subjectToken())), request.nonce(),
                Digest.sha256(Json.canonical(request.raw())), null, null, opening.id(), null, null, null);
        IssuedTicket ticket = ticket(retained, clientId, request.call(), iat);
        DecisionStore.Decision recorded = retained.withTicketJti(ticket.jti());

        // 5) 原子消费：compute 单键 CAS，输家不落任何新决策。
        if (decisions.consume(opening.id(), clientId).isEmpty()) {
            throw AuthorityException.denied("EVENTS_TICKET_CONSUMED");
        }
        decisions.put(recorded);
        Map<String, Object> claims = authorityClaims(recorded, request, iat);
        claims.put("ticket", ticket.value());
        audit.info("delegation.audit endpoint=decision outcome=permit operation=exchange variant=B caller={} target={}"
                        + " action={} decision_id={} parent_decision_id={} query_ticket_jti={} grant_ref={}"
                        + " grant_expires_at={}",
                clientId, request.call().target(), request.call().action(), recorded.id(), opening.id(), jti,
                recorded.grantRef(), recorded.grantExpiresAt().getEpochSecond());
        return Map.of("assertion", keys.sign(TicketService.ASSERTION_TYPE, claims), "ticket", ticket.value());
    }

    /**
     * EX-44 S3：{@code operation=portal_gateway_events_open}。
     *
     * <p>这一跳不发票：输入是 S2 的 RETAINED 决策 + 一次观测的 binding/immutable，输出是
     * <b>断言信封</b>（客户端按 {@code agent-authority-decision+jwt} 验签）和一张 continuation
     * （{@code agent-portal-gateway-events-continuation+jwt}）。判定顺序刻意固定，保证同一种攻击
     * 每次得到同一个 reason：
     * <ol>
     *   <li>只允许 gateway 发起，且 call 必须是 gateway observer（{@code gateway.run.events.observe}）；</li>
     *   <li>{@code decision_id} 必须指向一条 RETAINED 决策（S2 的 authorize 产物），未消费、未过期；</li>
     *   <li>经它的 {@code parentAdmissionId} 反查 S0 的 OPENING 决策：D1 上才有 S0 的
     *       {@code ticket_jti/grant_ref/operation_id/source_token_sha256/nonce/request_sha256}，
     *       即 {@code immutable.gateway_query} 要逐项对齐的权威值；</li>
     *   <li>binding 的 resource/sha 必须能由同一主体、同一 origin、同一 cursor 重算出来；</li>
     *   <li>hard_deadline 必须落在 {@code (now, min(now+55s, 授权/票据到期, S0 授权到期)]} 内——
     *       服务端只做夹取校验，签名仍用客户端送来的值，因为客户端会要求
     *       {@code continuation.exp == immutable.hard_deadline}；</li>
     *   <li>immutable 由 D3/D1 的权威值整棵重建后与请求做 <b>整节点</b> 比较（不是摘要比较），
     *       任何字段/编码/数值宽度不同都拒绝；</li>
     *   <li>最后才用 {@code compute} 单键 CAS 消费 D3：重放/并发只可能有一个赢家，输家 403
     *       {@code EVENTS_OPEN_REPLAY}，绝不返回 409（409 只表示"授权已过期"）。</li>
     * </ol>
     */
    public Map<String, Object> eventsOpen(String clientId, AuthorityRequest request) {
        requireEnabled();
        try {
            if (!PORTAL_CALLER.equals(clientId)) {
                throw AuthorityException.denied("EVENTS_CALLER");
            }
            requireRetainedPurpose(request.call());
            if (request.decisionId() == null) {
                throw AuthorityException.invalidRequest(
                        "portal_gateway_events_open 需要 decision_id", "missing decision_id");
            }
            long iat = now();
            DecisionStore.Decision retained = decisions.find(request.decisionId()).orElse(null);
            if (retained == null || retained.purpose() != DecisionStore.Purpose.RETAINED) {
                throw AuthorityException.denied("EVENTS_DECISION");
            }
            // 重放不做幂等缓存：同一 S2 决策只能开一次窗，第二次必须是 403（不是 409）。
            if (retained.consumedAt() != null) {
                throw AuthorityException.denied("EVENTS_OPEN_REPLAY");
            }
            if (!retained.expiresAt().isAfter(Instant.ofEpochSecond(iat))) {
                throw AuthorityException.expired("EVENTS_DECISION_EXPIRED");
            }
            DecisionStore.Decision admission = retained.parentAdmissionId() == null
                    ? null : decisions.find(retained.parentAdmissionId()).orElse(null);
            if (admission == null || admission.purpose() != DecisionStore.Purpose.OPENING) {
                throw AuthorityException.denied("EVENTS_DECISION");
            }
            Map<String, Object> resource = resourceClaims(request.call().resource());
            if (!retained.target().equals(request.call().target())
                    || !retained.action().equals(request.call().action())
                    || !retained.resource().equals(resource)) {
                throw AuthorityException.denied("EVENTS_BINDING");
            }
            if (!GATEWAY_EVENTS_PROFILE.equals(request.profile())) {
                throw AuthorityException.denied("EVENTS_BINDING");
            }
            AuthorityRequest.Binding binding = request.binding();
            if (!retained.resource().equals(resourceClaims(binding.resource()))) {
                throw AuthorityException.denied("EVENTS_BINDING");
            }
            String observationSha = PortalEventsBinding.observationRequestSha256(
                    subjectOf(retained), retained.originAuthenticationId(), binding.resource(), binding.effectiveCursor());
            if (!observationSha.equals(binding.observationRequestSha256())) {
                throw AuthorityException.denied("EVENTS_BINDING");
            }
            long hardDeadline = binding.hardDeadline().getEpochSecond();
            if (hardDeadline <= iat) {
                throw AuthorityException.expired("EVENTS_DEADLINE_EXPIRED");
            }
            long clamp = Math.min(iat + EVENTS_OPEN_MAX_SECONDS, Math.min(epochSeconds(retained.operation(), "expires_at"),
                    Math.min(retained.grantExpiresAt().getEpochSecond(), admission.grantExpiresAt().getEpochSecond())));
            if (hardDeadline > clamp) {
                throw AuthorityException.denied("EVENTS_DEADLINE");
            }
            String origin = text(request.immutable(), "origin_authentication_id");
            if (origin == null || !origin.equals(retained.originAuthenticationId())) {
                throw AuthorityException.denied("EVENTS_ORIGIN");
            }
            if (!expectedEventsImmutable(retained, admission, binding).equals(request.immutable())) {
                throw AuthorityException.denied("EVENTS_IMMUTABLE");
            }
            // 单键 CAS：D3 只有一个开窗名额。输家不写 D4、不签 continuation，重放确定性 403。
            if (decisions.consume(retained.id(), clientId).isEmpty()) {
                throw AuthorityException.denied("EVENTS_OPEN_REPLAY");
            }
            long checkedUntil = Math.min(iat + properties.assertionSeconds(), hardDeadline);
            String continuationJti = newId("cont");
            String continuation = tickets.continuation(
                    clientId, continuationJti, request.immutable(), binding.hardDeadline(), iat);
            DecisionStore.Decision events = new DecisionStore.Decision(
                    newId("dec"), clientId, retained.target(), retained.action(), retained.resource(),
                    retained.actor(), retained.ceiling(), retained.operation(), retained.grantRef(),
                    retained.grantExpiresAt(), retained.subjectCaller(), retained.ticketJti(),
                    retained.originAuthenticationId(), retained.authorizationVersion(),
                    Instant.ofEpochSecond(checkedUntil), DecisionStore.Purpose.EVENTS,
                    retained.sourceTokenSha256(), retained.admissionNonce(), retained.admissionRequestSha256(),
                    null, null, retained.id(), binding.responseId(), continuationJti, binding.hardDeadline());
            decisions.put(events);
            Map<String, Object> claims = new LinkedHashMap<>(
                    tickets.envelope(clientId, request.call(), request.nonce(), request.raw(), events.id(), iat));
            claims.put("authority_mode", MODE);
            claims.put("decision_id", events.id());
            claims.putAll(events.actor());
            claims.put("subject_caller", events.subjectCaller());
            claims.put("initial_ceiling", events.ceiling());
            claims.put("grant_ref", events.grantRef());
            claims.put("grant_expires_at", events.grantExpiresAt().getEpochSecond());
            claims.put("authorization_version", events.authorizationVersion());
            claims.put("operation", events.operation());
            if (events.ticketJti() != null) {
                claims.put("ticket_jti", events.ticketJti());
            }
            if (events.originAuthenticationId() != null) {
                claims.put("origin_authentication_id", events.originAuthenticationId());
            }
            claims.put("profile", GATEWAY_EVENTS_PROFILE);
            claims.put("immutable", request.immutable());
            claims.put("continuation_ref", continuation);
            // checked_until 与 exp 必须同值：客户端 eventsCurrent 会做 same(exp, checked_until)，
            // 而 exp 又被 verifySession 用来判断"断言是否已过窗"，两者分开写迟早漂移。
            claims.put("exp", checkedUntil);
            claims.put("checked_until", checkedUntil);
            audit.info("delegation.audit endpoint=decision outcome=permit operation={} caller={} target={} action={}"
                            + " decision_id={} parent_decision_id={} continuation_jti={} hard_deadline={}"
                            + " checked_until={}",
                    EVENTS_OPEN, clientId, events.target(), events.action(), events.id(), retained.id(),
                    continuationJti, hardDeadline, checkedUntil);
            return Map.of("assertion", keys.sign(TicketService.ASSERTION_TYPE, claims));
        } catch (AuthorityException denied) {
            deny("decision", EVENTS_OPEN, clientId, request, denied);
            throw denied;
        }
    }

    /**
     * 逐字段重建客户端送来的 immutable（25 字段），用于整节点比较。
     *
     * <p>三个编码陷阱必须在这里一次处理掉：epoch 秒一律 long；{@code binding} 是记录（camelCase
     * {@code versionDigest}、ISO {@code hardDeadline}）；{@code operation} 必须重新组装成
     * {@link OperationBinding} 记录再转树（camelCase），不能把存储里的 snake_case 直接回显。
     */
    private static JsonNode expectedEventsImmutable(DecisionStore.Decision retained,
                                                    DecisionStore.Decision admission,
                                                    AuthorityRequest.Binding binding) {
        SubjectKey subject = subjectOf(retained);
        Map<String, Object> operation = retained.operation();
        ResourceBinding operationResource = new ResourceBinding(
                resourceField(retained, "kind"), resourceField(retained, "id"), resourceField(retained, "version_digest"));
        OperationBinding bindingOperation = new OperationBinding(
                textClaim(operation, "operation_id"),
                textClaim(operation, "source_kind"),
                textClaim(operation, "origin_kind"),
                Instant.ofEpochSecond(epochSeconds(operation, "issued_at")),
                Instant.ofEpochSecond(epochSeconds(operation, "expires_at")),
                textClaim(operation, "grant_ref"),
                subject, operationResource, textClaim(operation, "ceiling_sha256"));
        Map<String, Object> immutable = new LinkedHashMap<>();
        immutable.put("profile", GATEWAY_EVENTS_PROFILE);
        immutable.put("binding", new PortalEventsBinding(binding.responseId(), binding.resource(),
                binding.effectiveCursor(), binding.observationRequestSha256(), binding.hardDeadline()));
        immutable.put("consumer", PORTAL_CALLER);
        immutable.put("caller", retained.subjectCaller());
        immutable.put("action", retained.action());
        immutable.put("resource", new LinkedHashMap<>(retained.resource()));
        immutable.put("subject", subject);
        immutable.put("source_kind", SOURCE_KIND);
        immutable.put("origin_kind", SOURCE_KIND);
        immutable.put("operation", Json.tree(bindingOperation));
        immutable.put("grant_ref", retained.grantRef());
        immutable.put("grant_expires_at", retained.grantExpiresAt().getEpochSecond());
        immutable.put("source_expires_at", epochSeconds(operation, "expires_at"));
        immutable.put("opening_decision_id", retained.id());
        immutable.put("opening_authorization_version", retained.authorizationVersion());
        immutable.put("ticket_jti", retained.ticketJti());
        immutable.put("origin_authentication_id", retained.originAuthenticationId());
        immutable.put("initial_ceiling_sha256", Digest.sha256(Json.canonical(ceilingMaterial(retained))));
        immutable.put("admission_nonce", retained.admissionNonce());
        immutable.put("admission_request_sha256", retained.admissionRequestSha256());
        immutable.put("hard_deadline", binding.hardDeadline().getEpochSecond());
        Map<String, Object> gatewayQuery = new LinkedHashMap<>();
        gatewayQuery.put("ticket_jti", admission.ticketJti());
        gatewayQuery.put("grant_ref", admission.grantRef());
        gatewayQuery.put("operation_id", ((Map<?, ?>) admission.operation()).get("operation_id"));
        gatewayQuery.put("source_token_sha256", admission.sourceTokenSha256());
        gatewayQuery.put("nonce", admission.admissionNonce());
        gatewayQuery.put("request_sha256", admission.admissionRequestSha256());
        ObjectNode normalized = (ObjectNode) Json.read(Json.write(immutable));
        normalized.set("gateway_query", Json.read(Json.write(gatewayQuery)));
        return normalized;
    }

    private static String resourceField(DecisionStore.Decision decision, String key) {
        Object operation = decision.operation().get("resource");
        if (!(operation instanceof Map<?, ?> resource) || !(resource.get(key) instanceof String value)) {
            throw AuthorityException.denied("EVENTS_DECISION");
        }
        return value;
    }

    private static SubjectKey subjectOf(DecisionStore.Decision decision) {
        return new SubjectKey(textClaim(decision.actor(), "tenant_id"), textClaim(decision.actor(), "sub"));
    }

    private static Map<String, Object> resourceClaims(ResourceBinding resource) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("kind", resource.kind());
        claims.put("id", resource.id());
        claims.put("version_digest", resource.versionDigest());
        return claims;
    }

    private static Map<String, Object> ceilingMaterial(DecisionStore.Decision decision) {
        Map<String, Object> owner = new LinkedHashMap<>();
        owner.put("tenantId", textClaim(decision.actor(), "tenant_id"));
        owner.put("principalId", textClaim(decision.actor(), "sub"));
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("owner", owner);
        material.put("roles", decision.actor().get("roles"));
        material.put("attributes", decision.actor().get("attributes"));
        return material;
    }

    private static long epochSeconds(Map<String, Object> claims, String field) {
        Object value = claims.get(field);
        if (!(value instanceof Number number)) {
            throw AuthorityException.denied("EVENTS_DECISION");
        }
        return number.longValue();
    }

    private static String textClaim(Map<String, Object> claims, String field) {
        Object value = claims.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw AuthorityException.denied("EVENTS_DECISION");
        }
        return text;
    }

    /**
     * 验 S0 票的形状与签名。失败统一 reason {@code EVENTS_QUERY_TICKET}：对调用方来说这就是"你给的
     * query_ticket 不是一张合法的 opening 票"，细分验签失败/类型不对只会泄露服务端内部，也无助于客户端自愈。
     */
    private JsonNode verifyQueryTicket(String queryTicket) {
        JsonNode claims;
        try {
            claims = keys.verify(queryTicket, TicketService.TICKET_TYPE);
        } catch (AuthorityException invalid) {
            // 过期/签名错/typ 错都在这里：一律按"票不可用"处理，不把 KeyRing 的内部文案透到审计语义里。
            throw AuthorityException.denied("EVENTS_QUERY_TICKET");
        }
        JsonNode operation = claims.get("operation");
        String origin = text(claims, "origin_authentication_id");
        if (!"service".equals(text(claims, "token_use"))
                || !MODE.equals(text(claims, "authority_mode"))
                || !PORTAL_CALLER.equals(text(claims, "caller"))
                || !PORTAL_CALLER.equals(text(claims, "client_id"))
                || !issuer.issuer().equals(text(claims, "iss"))
                || text(claims, "decision_id") == null
                || text(claims, "jti") == null
                || text(claims, "grant_ref") == null
                || origin == null
                || operation == null || !operation.isObject()) {
            throw AuthorityException.denied("EVENTS_QUERY_TICKET");
        }
        if (!ORIGIN.matcher(origin).matches()) {
            throw AuthorityException.denied("EVENTS_QUERY_TICKET");
        }
        try {
            // 只有 opening 票能换成 retained 票；别的 delegated 票即便签名有效也不是这一跳的凭据。
            requireOpeningPurpose(callOf(claims));
        } catch (AuthorityException invalid) {
            throw AuthorityException.denied("EVENTS_QUERY_TICKET");
        }
        return claims;
    }

    /**
     * 校验"portal 用户令牌"这个凭据类型本身：本服务签发、user 用途、portal 受众、gateway 客户端。
     * 身份字段（tenant/sub/roles）由调用方继续读；这里只负责"这张令牌能不能当主体凭据"。
     */
    private JsonNode verifyPortalUser(String token) {
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
        return claims;
    }

    /** 客户端 {@code RunObservationSelectors.GATEWAY_EVENTS_ACTION}；admin-common 里是包私有常量，只能对字面量。 */
    private static final String GATEWAY_EVENTS_ACTION = "gateway.run.events.observe";
    private static final Pattern RUN_ID = Pattern.compile("r_[0-9a-f]{32}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /**
     * 变体 B 的目的校验，等价于客户端 {@code requireGatewayPortalEventsCall} 的 target/action 分支
     * （mode/source/origin 已由票面固定），再加上 resource 形状：SSE 观测只能针对 run-snapshot 选择器。
     */
    private static boolean isRetainedPurpose(ServiceCall call) {
        return "gateway".equals(call.target())
                && GATEWAY_EVENTS_ACTION.equals(call.action())
                && "run-snapshot".equals(call.resource().kind())
                && RUN_ID.matcher(call.resource().id()).matches()
                && SHA256.matcher(call.resource().versionDigest()).matches();
    }

    private static void requireRetainedPurpose(ServiceCall call) {
        if (!isRetainedPurpose(call)) {
            throw AuthorityException.denied("EVENTS_RETAINED_PURPOSE");
        }
    }

    private static void requireNotRetainedPurpose(ServiceCall call) {
        if (isRetainedPurpose(call)) {
            throw AuthorityException.denied("EVENTS_RETAINED_PURPOSE");
        }
    }

    /** portal 用户令牌换决策：调用方必须是 gateway，令牌必须是本服务签的、给本 portal 受众的 user 令牌。 */
    private DecisionStore.Decision fromSubject(String clientId, AuthorityRequest request) {
        return fromSubject(clientId, request, null, null);
    }

    private DecisionStore.Decision fromSubject(String clientId, AuthorityRequest request, Instant cap) {
        return fromSubject(clientId, request, cap, null);
    }

    /**
     * @param cap     主体凭据的到期时间；非空时 grant 不得晚于它（exchange 用 portal 令牌的 {@code exp} 封顶）
     * @param purpose EX-44 观测链路的用途（S0 传 {@code OPENING}）；普通 authorize/exchange 传 null
     */
    private DecisionStore.Decision fromSubject(String clientId, AuthorityRequest request, Instant cap,
                                               DecisionStore.Purpose purpose) {
        if (!PORTAL_CALLER.equals(clientId)) {
            throw AuthorityException.denied("SUBJECT_CALLER_NOT_GATEWAY");
        }
        String token = request.subjectToken();
        if (token == null) {
            throw AuthorityException.denied("SUBJECT_UNAVAILABLE");
        }
        JsonNode claims = verifyPortalUser(token);
        String subjectClient = identity.clientId();
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
        if (cap != null && cap.isBefore(grantExpiresAt)) {
            // 只缩短不延长：portal 令牌比默认 grant 短命时，授权随凭据一起到期。
            grantExpiresAt = cap;
        }
        Map<String, Object> operation = operation(newId("op"), issued, grantExpiresAt, grantRef, tenantId,
                principalId, request.call(), ceilingSha256);
        DecisionStore.Decision decision = new DecisionStore.Decision(newId("dec"), clientId,
                request.call().target(), request.call().action(), resourceOf(request.call()), actor, actor,
                operation, grantRef, grantExpiresAt, Objects.requireNonNullElse(subjectClient, PORTAL_CALLER), null,
                originOf(claims, "SUBJECT_ORIGIN"), authorizationVersion(),
                issued.plusSeconds(delegation.decisionSeconds()),
                purpose,
                // 把决策钉死到"这一张"portal 令牌原文：同用户换一张令牌不能复用同一个 opening 决策。
                // 摘要口径必须与平台客户端 AuthorityProtocol.digest(String) 一致（sha256(canonical(令牌))），
                // 否则 1d 的 gateway_query.source_token_sha256 会与 D1 永远对不上（见 evidence 的根因）。
                Digest.sha256(Json.canonical(token)),
                request.nonce(), Digest.sha256(Json.canonical(request.raw())),
                null, null, null, null, null, null);
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
        // 1d 的 retained 票会把 parent_admission_id 带回来，这里原样落到决策上，S2/S3 才能反查到 S0。
        String parentAdmissionId = text(claims, "parent_admission_id");
        DecisionStore.Decision decision = new DecisionStore.Decision(newId("dec"), clientId, call.target(),
                call.action(), resourceOf(call), actor(tenantId, principalId, roles, attributes),
                toMap(ceilingNode), operation, grantRef, grantExpiresAt, upstream, text(claims, "jti"),
                originOf(claims, "TICKET_ORIGIN"), required(claims, "authorization_version"),
                earlier(issued.plusSeconds(delegation.decisionSeconds()), grantExpiresAt),
                // S2 是 retained 票的确认跳：它本身就是 S3 唯一接受的 RETAINED 决策。
                // 入站 nonce/摘要记录的是**这次 authorize 请求**，S3 的 immutable.admission_* 正是这份值。
                parentAdmissionId == null ? null : DecisionStore.Purpose.RETAINED,
                null, request.nonce(), Digest.sha256(Json.canonical(request.raw())),
                null, null, parentAdmissionId, null, null, null);
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
        if (decision.originAuthenticationId() != null) {
            // 断言也带 origin：authorizeRetained 会用它与票上的 origin 做一致性检查（client :627）。
            claims.put("origin_authentication_id", decision.originAuthenticationId());
        }
        return claims;
    }

    /**
     * delegated 服务票据：{@code caller=client_id=转发方}，但 {@code sub=主体}（与 workload 票相反），
     * 且必须带租户/角色/属性/grant/operation —— 平台侧 {@code verifiedTicket} 的 delegated 分支逐项比对。
     * {@code exp} 取 {@code min(iat+ticket-seconds, grant_expires_at)}：转发不允许延长授权。
     */
    private IssuedTicket ticket(DecisionStore.Decision decision, String clientId, ServiceCall call, long iat) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer.issuer());
        claims.put("aud", tickets.audience(call.target()));
        claims.put("token_use", "service");
        claims.put("iat", iat);
        claims.put("nbf", iat);
        claims.put("exp", Math.min(iat + properties.ticketSeconds(), decision.grantExpiresAt().getEpochSecond()));
        String jti = newId("tkt");
        claims.put("jti", jti);
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
        if (decision.originAuthenticationId() != null) {
            // 票上带 origin 是 S1 之后 :594/:627 的唯一依据：没有它，第二次 exchange 立刻 403。
            claims.put("origin_authentication_id", decision.originAuthenticationId());
        }
        if (decision.parentAdmissionId() != null) {
            // retained 票指回 S0 的 opening 决策：S3 的 eventsQuery 要靠它反查 gateway_query 的比对基准。
            claims.put("parent_admission_id", decision.parentAdmissionId());
        }
        return new IssuedTicket(keys.sign(TicketService.TICKET_TYPE, claims), jti);
    }

    private static final Pattern ORIGIN = Pattern.compile("oa1:[0-9a-f]{32}");

    /**
     * EX-44 变体 A 的目的校验，与客户端 {@code RunObservationSelectors.requirePurpose(call,"gateway",
     * "delegated",sourceKind,originKind)} 的 metadata 分支等价：caller/mode/source/origin 已由 exchange 入口
     * 固定（caller 必须是 gateway、票面 source_kind/origin_kind 恒为 PORTAL），这里只钉 target/action/kind。
     *
     * <p>为什么不能只依赖目录行：目录为 {@code (gateway,engine,run.observation.metadata.read,delegated)} 授权，
     * 但它只约束 target/action，不约束 resource 种类；不做这一步，同一 action 配一个 run-snapshot 资源、
     * 或任意其它已登记 delegated action，都能换到一张"opening 票"，而客户端会立刻以目的不符 403。
     *
     * <p>不反解 resource.id：run id 由客户端编进选择器文本（{@code Json.canonical(List.of(...))}），
     * 服务端只需要 {@code AuthorityProtocol.operation(...)} 的 {@code same(resource, 请求 resource)} 结构等值。
     */
    private static void requireOpeningPurpose(ServiceCall call) {
        if (!"engine".equals(call.target())
                || !RunObservationSelectors.ACTION.equals(call.action())
                || !RunObservationSelectors.KIND.equals(call.resource().kind())) {
            throw AuthorityException.denied("EVENTS_OPENING_PURPOSE");
        }
    }

    /**
     * EX-44：读主体凭据上的 {@code origin_authentication_id}。形状必须严格是 {@code oa1:<32hex>}；
     * 缺省返回 null（历史票/未升级的 portal 票走 currentOnly 语义），形状不对一律拒绝而不是透传。
     */
    private static String originOf(JsonNode claims, String denial) {
        JsonNode node = claims.get("origin_authentication_id");
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual() || !ORIGIN.matcher(node.textValue()).matches()) {
            throw AuthorityException.denied(denial);
        }
        return node.textValue();
    }

    /** 签出的候选票 + 它的 jti：exchange 需要把 jti 回写进决策，才能让断言引用同一张票。 */
    private record IssuedTicket(String value, String jti) {
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
