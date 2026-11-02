package cn.agentplatform.common.authority;

import cn.agentplatform.authority.DelegatedTestSupport;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-46 切片 1f 的请求构造：engine 的 child_open（S5）与两面续期（S6 gateway / S6' child）。
 *
 * <p>为什么不直接用 {@code HttpCurrentAuthorityClient} 的
 * {@code openPortalEventsChild}：它需要先有一条真实的 S4 响应和一个带 child 证据的 retained
 * 授权，测试里还得把 engine 侧 immutable 按 {@code expectedChildImmutable} 逐字段拼出来；本切片要
 * 分别证明"immutable 与 D5 的摘要承诺同源""续期不消费决策""续期窗口不超过 hard_deadline"，
 * 直接拼请求体才能把每个拒绝点单独打出来。child 侧的 immutable 仍按客户端
 * {@code :1027-1054} 的字段顺序复刻（见 {@link #engineImmutable}），所以 receipt 里的
 * {@code child_immutable_sha256} 与客户端本地 digest 同源。
 */
abstract class GatewayEventsSlice1fSupport extends GatewayEventsSlice1eSupport {

    static final String CHILD_OPEN = "portal_events_child_open";
    static final String CHILD_CHECK = "portal_events_child_check";
    static final String GATEWAY_CHECK = "portal_gateway_events_check";
    static final String CHILD_EVENTS_PROFILE = "portal-engine-events-child-response.v1";
    static final String CHILD_CONTINUATION_TYPE = "agent-portal-engine-events-child-continuation+jwt";
    static final String CHILD_CONTINUATION_USE = "portal-engine-events-child-continuation";
    static final String RECEIPT_TYPE = "agent-portal-events-child-receipt+jwt";
    static final String RECEIPT_USE = "portal-events-child-receipt";
    static final String GATEWAY_AUDIENCE = "urn:agent-platform:service:gateway";

    /** 一条已换出的 child 授权：S4 的 body/claims/G 票/association/child_open 一次备齐。 */
    protected record Child(Flow flow, Parent parent, JsonNode body, JsonNode claims, JsonNode ticket,
            JsonNode association, String childOpen, String associationSha256, String decisionId,
            long hardDeadline) {
    }

    /** S3 开窗 → S4 换票；任何一步非 200 都直接断言失败（1f 只测 S5/S6）。 */
    protected Child child(String name) {
        Parent parent = window(name);
        ResponseEntity<JsonNode> exchanged = childExchange(parent, parent.immutable(), "n-s4-" + name);
        assertThat(exchanged.getStatusCode()).as("S4 body: %s", exchanged.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode body = exchanged.getBody();
        JsonNode claims = claimsOf(body);
        // G 票不在 body 顶层：它是 S4 断言里的 {@code ticket} claim（客户端同样从 claims 读）。
        JsonNode ticket = verified(text(claims, "ticket"), "at+jwt");
        // association 与 child_open 只签在 **票** 上（决策断言里只有 child_association_sha256），
        // 这正是 EX-45 §7 的"marker on ticket claims"：客户端也是从票里取这两样。
        JsonNode marker = ticket.path("portal_events_child");
        return new Child(parent.flow(), parent, body, claims, ticket, marker.path("association"),
                text(marker, "child_open"), text(claims, "child_association_sha256"),
                text(claims, "decision_id"), epoch(marker.path("association"), "hard_deadline"));
    }

    /**
     * engine 侧 {@code immutableEvents(initial,binding,CHILD_EVENTS_PROFILE)} + child_association/child_open_sha256
     * （客户端 {@code :742-746}）。{@code initial} 是 G 票（gateway 以 {@code Bearer <child ticket>} 打开观测），
     * 所以 opening/admission/ticket_jti 全部从 S4 的票里取，而不是由服务端重算。
     */
    protected JsonNode engineImmutable(Child child) {
        JsonNode ticket = child.ticket();
        JsonNode association = child.association();
        Actor actor = AuthorityProtocol.actor(ticket.get("initial_ceiling"));
        ServiceCall call = childCall(child.parent());
        OperationBinding operation = AuthorityProtocol.operation(ticket.get("operation"), actor, call.resource(),
                text(ticket, "grant_ref"), Clock.systemUTC());
        Map<String, Object> immutable = new LinkedHashMap<>();
        immutable.put("profile", CHILD_EVENTS_PROFILE);
        immutable.put("binding", association.get("child_binding"));
        immutable.put("consumer", "engine");
        immutable.put("caller", text(ticket, "subject_caller"));
        immutable.put("action", call.action());
        immutable.put("resource", AuthorityProtocol.resource(call.resource()));
        immutable.put("subject", AuthorityProtocol.subject(actor));
        immutable.put("source_kind", "PORTAL");
        immutable.put("origin_kind", "PORTAL");
        immutable.put("operation", Json.tree(operation));
        immutable.put("grant_ref", text(ticket, "grant_ref"));
        immutable.put("grant_expires_at", epoch(ticket, "grant_expires_at"));
        immutable.put("source_expires_at", operation.expiresAt().getEpochSecond());
        immutable.put("opening_decision_id", text(ticket, "decision_id"));
        immutable.put("opening_authorization_version", text(ticket, "authorization_version"));
        immutable.put("ticket_jti", text(ticket, "jti"));
        immutable.put("origin_authentication_id", text(ticket, "origin_authentication_id"));
        immutable.put("initial_ceiling_sha256", AuthorityProtocol.ceiling(actor));
        // admission_nonce / admission_request_sha256 是 **engine 自己那次 authorize 请求**的信封锚点
        // （客户端 {@code RetainedAuthorization} 从 authorize 的 current 决策取 nonce/request_sha256），
        // 权威侧在 child_open 里无法重算，只能接收并由回执原样带回；因此这里造一组合法形状的值。
        immutable.put("admission_nonce", "n-admit-" + child.decisionId());
        immutable.put("admission_request_sha256", AuthorityProtocol.digest(Json.write(call)));
        immutable.put("hard_deadline", child.hardDeadline());
        immutable.put("child_association", association);
        immutable.put("child_open_sha256", AuthorityProtocol.digest(child.childOpen()));
        return Json.read(Json.write(immutable));
    }

    /** S5：{@code openPortalEventsChild} 的 body（engine basic）。 */
    protected ResponseEntity<JsonNode> childOpen(Child child, JsonNode immutable, String nonce) {
        return childOpenAs(DelegatedTestSupport.ENGINE, DelegatedTestSupport.ENGINE_SECRET, child, immutable, nonce);
    }

    /** S5 换调用方（负例证明 child_open 只属于 engine，不是任意持有 G 票的服务）。 */
    protected ResponseEntity<JsonNode> childOpenAs(String clientId, String secret, Child child, JsonNode immutable,
            String nonce) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("decision_id", child.decisionId());
        extra.put("profile", CHILD_EVENTS_PROFILE);
        extra.put("binding", child.association().get("child_binding"));
        extra.put("immutable", immutable);
        extra.put("child_open", child.childOpen());
        return postJson("/internal/authorize", basic(clientId, secret),
                command(CHILD_OPEN, childCall(child.parent()), nonce, extra));
    }

    /** 续期请求体：{@code renewedEventsEvidence} 的 {@code profile/binding/continuation_ref} 三元组。 */
    protected ResponseEntity<JsonNode> renewal(String clientId, String secret, String operation, String profile,
            ServiceCall call, JsonNode binding, String continuation, String nonce) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("profile", profile);
        extra.put("binding", binding);
        extra.put("continuation_ref", continuation);
        return postJson("/internal/authorize", basic(clientId, secret), command(operation, call, nonce, extra));
    }

    /**
     * 客户端侧 gateway 的本地重建（{@code expectedChildImmutable:781-794}）：只用 association、
     * {@code child_open} 的 digest 与 receipt 里那三个 engine 字段，不看服务端的任何额外状态。
     * 它的 digest 必须等于 receipt 的 {@code child_immutable_sha256}，这正是
     * {@code acceptGatewayChildReceipt} 与 {@code PortalEventsMaterialVerifier:124} 通过的条件。
     */
    protected JsonNode expectedChildImmutable(JsonNode association, String childOpenDigest,
            JsonNode receiptClaims) {
        JsonNode engine = association.get("engine");
        Actor initial = AuthorityProtocol.actor(engine.get("initial_ceiling"));
        ResourceBinding resource = new ResourceBinding(text(engine.path("resource"), "kind"),
                text(engine.path("resource"), "id"), text(engine.path("resource"), "version_digest"));
        OperationBinding operation = AuthorityProtocol.operation(engine.get("operation"), initial, resource,
                text(engine, "grant_ref"), Clock.systemUTC());
        Map<String, Object> immutable = new LinkedHashMap<>();
        immutable.put("profile", CHILD_EVENTS_PROFILE);
        immutable.put("binding", association.get("child_binding"));
        immutable.put("consumer", "engine");
        immutable.put("caller", text(engine, "caller"));
        immutable.put("action", text(engine, "action"));
        immutable.put("resource", AuthorityProtocol.resource(resource));
        immutable.put("subject", AuthorityProtocol.subject(initial));
        immutable.put("source_kind", "PORTAL");
        immutable.put("origin_kind", "PORTAL");
        immutable.put("operation", Json.tree(operation));
        immutable.put("grant_ref", text(engine, "grant_ref"));
        immutable.put("grant_expires_at", epoch(engine, "grant_expires_at"));
        immutable.put("source_expires_at", operation.expiresAt().getEpochSecond());
        immutable.put("opening_decision_id", text(receiptClaims, "engine_opening_decision_id"));
        immutable.put("opening_authorization_version", text(engine, "authorization_version"));
        immutable.put("ticket_jti", text(engine, "jti"));
        immutable.put("origin_authentication_id", text(engine, "origin_authentication_id"));
        immutable.put("initial_ceiling_sha256", AuthorityProtocol.ceiling(initial));
        immutable.put("admission_nonce", text(receiptClaims, "engine_admission_nonce"));
        immutable.put("admission_request_sha256", text(receiptClaims, "engine_admission_request_sha256"));
        immutable.put("hard_deadline", epoch(association, "hard_deadline"));
        immutable.put("child_association", association);
        immutable.put("child_open_sha256", childOpenDigest);
        return Json.read(Json.write(immutable));
    }

    /** 用测试 KeyRing 重签一个 child continuation（改 exp 以证明续期窗口不能超过 hard_deadline）。 */
    protected String reforgedChildContinuation(String immutableSha256, long continuationJtiCounter, long exp) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", ISSUER);
        claims.put("aud", ENGINE_AUDIENCE);
        claims.put("token_use", CHILD_CONTINUATION_USE);
        claims.put("iat", now);
        claims.put("nbf", now);
        claims.put("exp", exp);
        claims.put("jti", "jti-1f-" + continuationJtiCounter);
        claims.put("continuation_jti", "cont-1f-" + continuationJtiCounter);
        claims.put("hard_deadline", exp);
        claims.put("immutable_sha256", immutableSha256);
        return keys.sign(CHILD_CONTINUATION_TYPE, claims);
    }
}
