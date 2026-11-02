package cn.agentplatform.common.authority;

import cn.agentplatform.authority.DelegatedTestSupport;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-44 切片 1d 的 S2/S3 请求构造：把客户端 {@code openGatewayPortalEvents} 在 S2（retained 票 authorize）
 * 与 S3（{@code portal_gateway_events_open}）两个请求体按字段复刻出来。
 *
 * <p>为什么仍然不复用 {@code HttpCurrentAuthorityClient}：1d 要分别证明"决策没消费时放行"与"消费后重放必须
 * 403（不是 409）"，真实客户端只能看到最终异常，分不清拒绝点；而且 1d 的 immutable 是客户端拼的 25 个字段，
 * 测试里逐字段复刻（直接调 admin-common 的 {@code AuthorityProtocol}/{@code PortalEventsBinding}）才能让
 * {@code request_sha256} 与真实调用一致，否则服务端一开始就会以摘要不符拒绝，测不到 1d 的判定逻辑。
 */
abstract class GatewayEventsSlice1dSupport extends GatewayEventsSlice1cSupport {

    static final String EVENTS_OPEN = "portal_gateway_events_open";
    static final String GATEWAY_EVENTS_PROFILE = "portal-gateway-events-response.v1";
    static final String DECISION_TYPE = "agent-authority-decision+jwt";
    static final String CONTINUATION_TYPE = "agent-portal-gateway-events-continuation+jwt";
    static final String CONTINUATION_USE = "portal-gateway-events-continuation";

    /** 一条完整走到 S2 的链路：portal 票 + S0/S1/S2 三个响应。 */
    protected record Flow(String name, String portal, JsonNode s0, JsonNode s1, JsonNode s2) {
    }

    /** S0 → S1 → S2；任何一步非 200 都直接断言失败（前置切片已绿，1d 不重复测它们）。 */
    protected Flow flow(String name) {
        String portal = portalToken(300, ORIGIN, DelegatedTickets.SUBJECT,
                "ses-" + name + "-" + UUID.randomUUID());
        JsonNode s0 = open(portal, "n-s0-" + name);
        ResponseEntity<JsonNode> s1 = exchangeQuery(portal, text(s0, "ticket"), "n-s1-" + name);
        assertThat(s1.getStatusCode()).as("S1 body: %s", s1.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode s2 = authorizeRetained(text(s1.getBody(), "ticket"), "n-s2-" + name);
        return new Flow(name, portal, s0, s1.getBody(), s2);
    }

    /**
     * S2：客户端 {@code authorizeRetained} 的 {@code query("authorize",call,{service_ticket})}。
     * 调用方是 gateway（basic gateway），resource 必须是 retained 观测行（gateway/observe/run-snapshot）。
     */
    protected JsonNode authorizeRetained(String retainedTicket, String nonce) {
        ResponseEntity<JsonNode> response = postJson("/internal/authorize", basic(GATEWAY, GATEWAY_SECRET),
                command("authorize", retainedCall(), nonce, Map.of("service_ticket", retainedTicket)));
        assertThat(response.getStatusCode()).as("S2 body: %s", response.getBody()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    protected JsonNode s2Claims(Flow flow) {
        return claimsOf(flow.s2());
    }

    /** 客户端 {@code:631} 的 PortalEventsBinding：sha 由 S2 operation.subject + origin + 观测 resource + cursor 重算。 */
    protected PortalEventsBinding binding(Flow flow, long cursor, Instant hardDeadline) {
        JsonNode claims = s2Claims(flow);
        Actor actor = AuthorityProtocol.actor(claims.get("initial_ceiling"));
        String sha = PortalEventsBinding.observationRequestSha256(AuthorityProtocol.subject(actor),
                text(claims, "origin_authentication_id"), retainedCall().resource(), cursor);
        return new PortalEventsBinding(UUID.randomUUID().toString(), retainedCall().resource(), cursor, sha,
                hardDeadline);
    }

    /**
     * 客户端 {@code immutableEvents(:1027)} + {@code:633} 的 gateway_query：字段、命名（camelCase 的
     * binding/operation）、两种时间编码（ISO 字符串 vs epoch 秒）全部按客户端复刻，最后做一次
     * {@code Json.read(Json.write(...))} 归一化节点宽度。
     */
    protected JsonNode immutable(Flow flow, PortalEventsBinding binding) {
        JsonNode claims = s2Claims(flow);
        Actor actor = AuthorityProtocol.actor(claims.get("initial_ceiling"));
        ServiceCall call = retainedCall();
        OperationBinding operation = AuthorityProtocol.operation(claims.get("operation"), actor, call.resource(),
                text(claims, "grant_ref"), Clock.systemUTC());
        Map<String, Object> immutable = new LinkedHashMap<>();
        immutable.put("profile", GATEWAY_EVENTS_PROFILE);
        immutable.put("binding", binding);
        immutable.put("consumer", "gateway");
        immutable.put("caller", text(claims, "subject_caller"));
        immutable.put("action", call.action());
        immutable.put("resource", AuthorityProtocol.resource(call.resource()));
        immutable.put("subject", AuthorityProtocol.subject(actor));
        immutable.put("source_kind", "PORTAL");
        immutable.put("origin_kind", "PORTAL");
        immutable.put("operation", Json.tree(operation));
        immutable.put("grant_ref", text(claims, "grant_ref"));
        immutable.put("grant_expires_at", operation.expiresAt().getEpochSecond());
        immutable.put("source_expires_at", operation.expiresAt().getEpochSecond());
        immutable.put("opening_decision_id", text(claims, "jti"));
        immutable.put("opening_authorization_version", text(claims, "authorization_version"));
        immutable.put("ticket_jti", text(claims, "ticket_jti"));
        immutable.put("origin_authentication_id", text(claims, "origin_authentication_id"));
        immutable.put("initial_ceiling_sha256", AuthorityProtocol.ceiling(actor));
        immutable.put("admission_nonce", text(claims, "nonce"));
        immutable.put("admission_request_sha256", text(claims, "request_sha256"));
        immutable.put("hard_deadline", binding.hardDeadline().getEpochSecond());
        ObjectNode node = (ObjectNode) Json.read(Json.write(immutable));
        node.set("gateway_query", gatewayQuery(flow));
        return node;
    }

    /**
     * 客户端 {@code:633-636}：四个"S0 证据"字段来自 S0 **票**（T1 jti / T1 operation 的 grant_ref+operation_id），
     * 两个请求属性来自 S0 **断言**（nonce/request_sha256）；source_token_sha256 是 portal 令牌的
     * {@code AuthorityProtocol.digest}（带 JSON 引号的 canonical 口径）。
     */
    protected JsonNode gatewayQuery(Flow flow) {
        JsonNode assertion = claimsOf(flow.s0());
        JsonNode ticket = verified(text(flow.s0(), "ticket"), "at+jwt");
        JsonNode operation = ticket.get("operation");
        Map<String, Object> gateway = new LinkedHashMap<>();
        gateway.put("ticket_jti", text(ticket, "jti"));
        gateway.put("grant_ref", operation.path("grant_ref").textValue());
        gateway.put("operation_id", operation.path("operation_id").textValue());
        gateway.put("source_token_sha256", AuthorityProtocol.digest(flow.portal()));
        gateway.put("nonce", text(assertion, "nonce"));
        gateway.put("request_sha256", text(assertion, "request_sha256"));
        return Json.read(Json.write(gateway));
    }

    /** S3：{@code decision_id = S2 断言的 jti}，binding/profile/immutable 原样进 body。 */
    protected ResponseEntity<JsonNode> openEvents(Flow flow, PortalEventsBinding binding, JsonNode immutable,
                                                  String nonce) {
        return openEventsFor(text(s2Claims(flow), "jti"), binding, immutable, nonce);
    }

    /** 决策定位可替换（未知 decision_id 负例用）。 */
    protected ResponseEntity<JsonNode> openEventsFor(String decisionId, PortalEventsBinding binding,
                                                     JsonNode immutable, String nonce) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("decision_id", decisionId);
        extra.put("binding", binding);
        extra.put("profile", GATEWAY_EVENTS_PROFILE);
        extra.put("immutable", immutable);
        return postJson("/internal/authorize", basic(GATEWAY, GATEWAY_SECRET),
                command(EVENTS_OPEN, retainedCall(), nonce, extra));
    }

    protected JsonNode claimsOf(JsonNode body) {
        return verified(text(body, "assertion"), DECISION_TYPE);
    }

    protected static Instant nowPlusSeconds(long seconds) {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(seconds);
    }

    protected static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    protected static long epoch(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null ? -1 : value.asLong(-1);
    }
}
