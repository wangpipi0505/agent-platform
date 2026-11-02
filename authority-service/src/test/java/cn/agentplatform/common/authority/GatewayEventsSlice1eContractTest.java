package cn.agentplatform.common.authority;

import cn.agentplatform.authority.AuthorityApplication;
import cn.agentplatform.authority.AuthorityTestSupport;
import cn.agentplatform.authority.DelegatedTestSupport;
import cn.agentplatform.authority.decision.DecisionStore;
import cn.agentplatform.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-45 切片 1e：{@code portal_events_child_exchange}（S4）的协议契约测试。
 *
 * <p>这是 gateway 真正出流前的最后一段服务端授权：S3 开窗后客户端**立刻**调用
 * {@code exchangePortalEventsChild}（{@code PortalRunEventsHttp:312}），服务端必须把
 * "gateway 已经拿到的 SSE 窗口" 换成 "engine 可以读这条 run 的事件" 的一段新授权，同时保持：
 * <ol>
 *   <li>父 continuation 是唯一入口凭据：typ/use/aud/exp 由 KeyRing 验，{@code exp == immutable.hard_deadline}，
 *       且必须能反查到一条<b>未被消费</b>的 S3 决策（{@code purpose=EVENTS}）；</li>
 *   <li>parent immutable 必须与 S3 那条决策逐项同源（origin/caller/ticket_jti/grant/opening/admission/
 *       gateway_query/binding），任何一个字段被换都只能 403；</li>
 *   <li>child 自己的窗口只能更短：{@code hard_deadline <= min(parent.hard_deadline, now+55s)}，
 *       {@code child_binding} 只能换 responseId 与 hard_deadline，资源/游标/观测摘要必须与父一致；</li>
 *   <li>回显的 {@code child_association_sha256} 必须等于客户端本地
 *       {@code AuthorityProtocol.digest(association)}（canonical 排序后的 sha256），否则客户端
 *       {@code :685} 直接 403；{@code child_open} 是 authority 签的、aud=engine、exp 恰为 child 窗口的
 *       短票，engine 侧 {@code :724-730} 会逐字段复核。</li>
 * </ol>
 *
 * <p>失败语义沿用 1d：对外 code 恒 {@code SYS-AUTH-403}，reason 只进审计（{@code EVENTS_CHILD_*}）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = AuthorityApplication.class,
        properties = {AuthorityTestSupport.P_ISSUER, AuthorityTestSupport.P_KID, AuthorityTestSupport.P_KEY,
                AuthorityTestSupport.P_GATEWAY, DelegatedTestSupport.P_ENGINE,
                DelegatedTestSupport.P_DELEGATION_ON})
class GatewayEventsSlice1eContractTest extends GatewayEventsSlice1eSupport {

    @Autowired
    private DecisionStore decisions;

    /**
     * 真实客户端全链：S0→S1→S2→S3 之后由 {@code exchangePortalEventsChild} 完成 S4。
     * 客户端在本地做 association/child_open/child_association_sha256 的逐项校验，
     * 链路能返回 {@code PortalEventsChildAuthorization} 即证明"服务端签的东西客户端认"。
     */
    @Test
    void theRealGatewayClientExchangesTheChildAndGetsALiveProof() {
        String portal = portalToken(300, ORIGIN, DelegatedTickets.SUBJECT, "ses-child-" + UUID.randomUUID());
        try (HttpCurrentAuthorityClient client = gatewayClient()) {
            HttpCurrentAuthorityClient.GatewayEventsOpening opening =
                    client.beginGatewayPortalEvents(portal, RUN, 7);
            try (GatewayPortalEventsResponse response = client.openGatewayPortalEvents(
                    opening, RunObservationSelectors.events(RUN, DIGEST), () -> { })) {
                HttpCurrentAuthorityClient.PortalEventsChildAuthorization proof =
                        client.exchangePortalEventsChild(portal, response);

                // requireVerified 同时校验 proof 的剩余预算与硬窗口，它通过才代表"这段授权还活着"。
                proof.requireVerified();
                ServiceTicket ticket = proof.ticket();
                JsonNode claims = verified(ticket.value(), "at+jwt");

                assertThat(text(claims, "caller")).isEqualTo(GATEWAY);
                assertThat(text(claims, "client_id")).isEqualTo(GATEWAY);
                assertThat(text(claims, "target")).isEqualTo("engine");
                assertThat(text(claims, "scope")).isEqualTo(RunObservationSelectors.EVENTS_ACTION);
                assertThat(text(claims, "authority_mode")).isEqualTo("delegated");
                assertThat(text(claims, "aud")).isEqualTo(ENGINE_AUDIENCE);
                assertThat(text(claims.path("resource"), "kind")).isEqualTo("run-snapshot");
                assertThat(text(claims.path("resource"), "id")).isEqualTo(RUN);
                assertThat(text(claims.path("resource"), "version_digest")).isEqualTo(DIGEST);
                // 票的 exp 与 operation.expires_at 都夹到 child 窗口，票据不能活得比窗口久。
                assertThat(ticket.expiresAt()).isAfter(Instant.now());
                assertThat(ticket.operation().expiresAt()).isEqualTo(ticket.expiresAt());
            }
        }
    }

    @Test
    void theChildExchangeEchoesTheAssociationChildOpenAndAChildDecision() {
        Parent parent = window("child-shape");
        PortalEventsBinding parentBinding =
                Json.read(parent.immutable().get("binding").toString(), PortalEventsBinding.class);

        ResponseEntity<JsonNode> response = childExchange(parent, parent.immutable(), "n-s4-shape");
        assertThat(response.getStatusCode()).as("S4 body: %s", response.getBody()).isEqualTo(HttpStatus.OK);

        JsonNode body = response.getBody();
        // 与 S3 同形：响应体只回 assertion，G 票在断言 claim 里（客户端 verifiedTicket 读 envelope["ticket"]）。
        assertThat(body.has("ticket")).isFalse();
        JsonNode claims = claimsOf(body);
        JsonNode ticketClaims = verified(text(claims, "ticket"), "at+jwt");
        // 客户端在 verifiedTicket 返回的**票面** claims 上找 portal_events_child（:683），不在信封上。
        JsonNode marker = ticketClaims.path("portal_events_child");
        JsonNode association = marker.path("association");

        // 1) association 的身份与父节点回显：客户端 verifyAssociation 做的是整体节点相等。
        assertThat(text(association, "schema")).isEqualTo(CHILD_ASSOCIATION_SCHEMA);
        assertThat(association.get("parent")).isEqualTo(parent.immutable());
        assertThat(text(association, "source_token_sha256"))
                .isEqualTo(text(parent.immutable().path("gateway_query"), "source_token_sha256"));
        // 源令牌摘要：gateway_query 与 association 两处必须指向同一张 portal 票原文。
        assertThat(text(association, "source_token_sha256"))
                .isEqualTo(AuthorityProtocol.digest(parent.flow().portal()));

        // 2) child_binding 只能换 responseId 与 hard_deadline，资源/游标/观测摘要必须与父一致。
        PortalEventsBinding childBinding =
                Json.read(association.get("child_binding").toString(), PortalEventsBinding.class);
        assertThat(childBinding.resource()).isEqualTo(parentBinding.resource());
        assertThat(childBinding.effectiveCursor()).isEqualTo(parentBinding.effectiveCursor());
        assertThat(childBinding.observationRequestSha256()).isEqualTo(parentBinding.observationRequestSha256());
        assertThat(childBinding.responseId()).isNotEqualTo(parentBinding.responseId());
        assertThat(childBinding.hardDeadline().getEpochSecond()).isEqualTo(epoch(association, "hard_deadline"));
        // 子窗口不允许超过父窗口（客户端 :716 直接判越界）。
        assertThat(childBinding.hardDeadline().getEpochSecond())
                .isLessThanOrEqualTo(epoch(parent.immutable(), "hard_deadline"));

        // 3) engine 节点必须与 G 票逐项相等：客户端对 11 个字段做 same()。
        JsonNode engine = association.path("engine");
        for (String field : List.of("jti", "grant_ref", "grant_expires_at", "authorization_version", "operation",
                "initial_ceiling", "origin_authentication_id", "action", "target", "caller", "resource")) {
            assertThat(engine.get(field)).as("engine.%s", field).isEqualTo(ticketClaims.get(field));
        }
        assertThat(text(engine, "target")).isEqualTo("engine");
        assertThat(text(engine, "action")).isEqualTo(RunObservationSelectors.EVENTS_ACTION);
        assertThat(text(engine, "caller")).isEqualTo(GATEWAY);
        // 换跳必须换 grant_ref/operation_id（§4.4）：父 immutable 里的两个值都不能复用。
        assertThat(text(engine, "grant_ref")).isNotEqualTo(text(parent.immutable(), "grant_ref"));
        assertThat(text(engine.path("operation"), "operation_id"))
                .isNotEqualTo(text(parent.immutable().path("operation"), "operationId"));

        // 4) child_open：authority 签、aud=engine、token_use 专属，exp 恰为 child 窗口，
        //    并绑定 association 摘要与父子两个 responseId（engine 侧 requireAssociationToken 逐字段比）。
        JsonNode childOpen = verified(text(marker, "child_open"), CHILD_OPEN_TYPE);
        assertThat(text(childOpen, "token_use")).isEqualTo(CHILD_OPEN_USE);
        assertThat(text(childOpen, "aud")).isEqualTo(ENGINE_AUDIENCE);
        assertThat(epoch(childOpen, "exp")).isEqualTo(epoch(association, "hard_deadline"));
        assertThat(epoch(childOpen, "iat")).isLessThanOrEqualTo(Instant.now().getEpochSecond());
        assertThat(text(childOpen, "association_sha256")).isEqualTo(AuthorityProtocol.digest(association));
        assertThat(text(childOpen, "parent_response_id")).isEqualTo(parentBinding.responseId());
        assertThat(text(childOpen, "child_response_id")).isEqualTo(childBinding.responseId());

        // 5) 摘要口径：客户端就是 digest(association) 与 claim 逐字节比较。
        assertThat(text(claims, "child_association_sha256")).isEqualTo(AuthorityProtocol.digest(association));

        // 6) 决策断言不得携带客户端黑名单里的拼接字段（authorize 路径会因此 403）。
        assertThat(claims.has("parent_response_id")).isFalse();
        assertThat(claims.has("parent_response")).isFalse();
        assertThat(claims.has("completion_permit")).isFalse();
        assertThat(claims.has("child_receipt")).isFalse();

        // 7) 落库：父决策（S3）被一次性消费，子决策是 CHILD 且绑定父决策与 child responseId。
        String parentDecision = text(parent.claims(), "jti");
        String childDecision = text(claims, "decision_id");
        DecisionStore.Decision consumed = decisions.find(parentDecision).orElseThrow();
        assertThat(consumed.consumedAt()).isNotNull();
        assertThat(consumed.consumedBy()).isEqualTo(GATEWAY);
        DecisionStore.Decision child = decisions.find(childDecision).orElseThrow();
        assertThat(child.purpose().name()).isEqualTo("CHILD");
        assertThat(child.parentAdmissionId()).isEqualTo(parentDecision);
        assertThat(child.bindingResponseId()).isEqualTo(childBinding.responseId());
        assertThat(child.hardDeadline()).isEqualTo(Instant.ofEpochSecond(epoch(association, "hard_deadline")));
        assertThat(child.target()).isEqualTo("engine");
        assertThat(child.caller()).isEqualTo(GATEWAY);
        assertThat(child.consumedAt()).isNull();
    }

    @Test
    void replayingTheSameParentIsForbiddenNotConflict() {
        Parent parent = window("child-replay");
        assertThat(childExchange(parent, parent.immutable(), "n-s4-replay-1").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        var audit = attachAudit();
        ResponseEntity<JsonNode> replay = childExchange(parent, parent.immutable(), "n-s4-replay-2");

        assertThat(replay.getStatusCode()).as("replay body: %s", replay.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(replay.getBody().path("code").textValue()).isEqualTo("SYS-AUTH-403");
        assertThat(hasReason(auditLines(audit), "EVENTS_CHILD_REPLAY")).isTrue();
    }

    @Test
    void anExpiredParentContinuationIsRejected() {
        Parent parent = window("child-expired");
        var audit = attachAudit();

        ResponseEntity<JsonNode> response =
                childExchange(parent, expiredContinuation(parent), parent.immutable(), parent.flow().portal(),
                        "n-s4-expired");

        assertThat(response.getStatusCode()).as("expired body: %s", response.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(hasReason(auditLines(audit), "EVENTS_CHILD_CONTINUATION")).isTrue();
    }

    @Test
    void aTamperedParentImmutableIsRejectedAsImmutableMismatch() {
        Parent parent = window("child-immutable");
        ObjectNode tampered = (ObjectNode) parent.immutable().deepCopy();
        // 只改一个业务字段：continuation 里签的 immutable 与请求体整体不再相等。
        tampered.put("admission_nonce", "n-tampered");

        var audit = attachAudit();
        ResponseEntity<JsonNode> response = childExchange(parent, tampered, "n-s4-immutable");

        assertThat(response.getStatusCode()).as("tampered body: %s", response.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(hasReason(auditLines(audit), "EVENTS_CHILD_IMMUTABLE")).isTrue();
    }

    @Test
    void aChildDeadlineBeyondTheParentWindowIsRejected() {
        Parent parent = window("child-deadline");
        long parentDeadline = epoch(parent.immutable(), "hard_deadline");

        // 自签 continuation + 与之自洽的 immutable：这样"窗口超父"才会落在硬窗判定上，
        // 而不是先被 exp==hard_deadline 的一致性检查挡掉。
        ObjectNode forged = parent.immutable().deepCopy();
        forged.put("hard_deadline", parentDeadline + 60);

        var audit = attachAudit();
        ResponseEntity<JsonNode> response = childExchange(parent,
                reforge(parent, parentDeadline + 60, forged), forged,
                parent.flow().portal(), "n-s4-deadline");

        assertThat(response.getStatusCode()).as("deadline body: %s", response.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(hasReason(auditLines(audit), "EVENTS_CHILD_DEADLINE")).isTrue();
    }

    @Test
    void aNonGatewayCallerIsRejected() {
        Parent parent = window("child-caller");
        var audit = attachAudit();

        ResponseEntity<JsonNode> response = childExchangeAs(ENGINE, ENGINE_SECRET, parent, parent.continuation(),
                parent.immutable(), parent.flow().portal(), "n-s4-caller");

        assertThat(response.getStatusCode()).as("caller body: %s", response.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(hasReason(auditLines(audit), "EVENTS_CHILD_CALLER")).isTrue();
    }

    /**
     * "摘要不一致"的回归护栏：服务端同时产出 association 与摘要，客户端逐字节比 digest(association)，
     * 所以除了服务端口径写错（漏排序/用错序列化）之外造不出不一致。本用例把服务端的分段口径固定住，
     * 真实客户端用例里 {@code exchangePortalEventsChild} 的 {@code :685} 会因不一致直接 403。
     */
    @Test
    void theChildAssociationDigestIsTheCanonicalDigestOfTheEchoedAssociation() {
        Parent parent = window("child-digest");

        JsonNode claims = claimsOf(childExchange(parent, parent.immutable(), "n-s4-digest").getBody());
        // association 在 G 票上（客户端从 verifiedTicket 的票面 claims 读），摘要在信封 claims 上。
        JsonNode association = association(verified(text(claims, "ticket"), "at+jwt"));

        assertThat(text(claims, "child_association_sha256"))
                .isEqualTo(AuthorityProtocol.digest(association))
                .hasSize(64);
    }

    private HttpCurrentAuthorityClient gatewayClient() {
        AuthorityProperties config = new AuthorityProperties("iam", GATEWAY, portalConfig(),
                new AuthorityProperties.Authority(ISSUER, url("/.well-known/jwks.json"), url("/oauth/token"),
                        url("/internal/authorize"), GATEWAY, GATEWAY_SECRET_REF, "RS256",
                        "urn:agent-platform:service:" + GATEWAY, 60, 5, 1000, 2000),
                null, null, null, false, null);
        WorkloadCredentialResolver credentials = new WorkloadCredentialResolver(config, ref -> GATEWAY_SECRET, true);
        return new HttpCurrentAuthorityClient(config, Clock.systemUTC(), credentials, true);
    }

    private AuthorityProperties.Portal portalConfig() {
        return new AuthorityProperties.Portal(true, ISSUER, url("/.well-known/jwks.json"), PORTAL_AUDIENCE, GATEWAY,
                Set.of("EDITOR"), Set.of("region"), Map.of("region", Set.of("A")), Set.of("platform.access"));
    }
}
