package cn.agentplatform.common.authority;

import cn.agentplatform.authority.AuthorityApplication;
import cn.agentplatform.authority.AuthorityTestSupport;
import cn.agentplatform.authority.DelegatedTestSupport;
import cn.agentplatform.authority.decision.DecisionStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-44 切片 1d：{@code portal_gateway_events_open}（S3）的协议契约测试。
 *
 * <p>覆盖三类不变量：
 * <ol>
 *   <li>成功路径返回**断言信封**（不是票）+ 一张 continuation，且 {@code checked_until == exp}、
 *       {@code exp <= immutable.hard_deadline}；D3（S2 决策）被一次性消费，D4（EVENTS 决策）落库且
 *       记录 binding.responseId / continuation jti / hardDeadline；</li>
 *   <li>重放同一个 open 必须 403（{@code EVENTS_OPEN_REPLAY}），绝不能是 409——409 是"过期"的语义，
 *       客户端会把重放误判成"刷新后重试"；</li>
 *   <li>时间夹取上界与 immutable 整体等值：超出 {@code min(now+55s, 决策/授权到期)} 的 hardDeadline
 *       必须 403 {@code EVENTS_DEADLINE}，篡改 immutable 任一字段必须 403 {@code EVENTS_IMMUTABLE}。</li>
 * </ol>
 *
 * <p>另有一条真实客户端用例：{@link HttpCurrentAuthorityClient#beginGatewayPortalEvents} +
 * {@link HttpCurrentAuthorityClient#openGatewayPortalEvents} 会在客户端本地做几十项严格校验
 * （continuation 的 typ/aud/exp、immutable 整棵树、D3 消费后不许再开窗）。它不触发 EX-45 的
 * {@code portal_events_child_exchange}——那是拿到响应之后由 gateway 另起的一跳，S0→S3 本身可以单独证明。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = AuthorityApplication.class,
        properties = {AuthorityTestSupport.P_ISSUER, AuthorityTestSupport.P_KID, AuthorityTestSupport.P_KEY,
                AuthorityTestSupport.P_GATEWAY, DelegatedTestSupport.P_ENGINE,
                DelegatedTestSupport.P_DELEGATION_ON})
class GatewayEventsSlice1dContractTest extends GatewayEventsSlice1dSupport {

    @Autowired
    private DecisionStore decisions;

    @Test
    void theRealGatewayClientOpensTheWindowAndAcceptsTheContinuation() {
        String portal = portalToken(300, ORIGIN, DelegatedTickets.SUBJECT, "ses-real");
        try (HttpCurrentAuthorityClient client = gatewayClient()) {
            HttpCurrentAuthorityClient.GatewayEventsOpening opening =
                    client.beginGatewayPortalEvents(portal, RUN, 7);
            try (GatewayPortalEventsResponse response = client.openGatewayPortalEvents(
                    opening, RunObservationSelectors.events(RUN, DIGEST), () -> { })) {
                GatewayPortalEventsResponse.Capture capture = response.currentCapture();
                assertThat(capture.resource()).isEqualTo(RunObservationSelectors.events(RUN, DIGEST));
                assertThat(capture.initialCursor()).isEqualTo(7);
                assertThat(capture.responseId()).isNotBlank();
                assertThat(capture.remainingNanos()).isPositive();
                response.requireLive(capture);
            }
        }
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

    @Test
    void eventsOpenReturnsAnAssertionEnvelopeAndASignedContinuation() {
        Flow flow = flow("ok");
        PortalEventsBinding binding = binding(flow, 7, nowPlusSeconds(40));
        JsonNode immutable = immutable(flow, binding);
        var audit = attachAudit();

        ResponseEntity<JsonNode> response = openEvents(flow, binding, immutable, "n-s3-ok");
        JsonNode body = response.getBody();
        assertThat(response.getStatusCode()).as("S3 body: %s", body).isEqualTo(HttpStatus.OK);

        JsonNode claims = claimsOf(body);
        JsonNode s2 = s2Claims(flow);

        // 1) 响应只有 assertion：S3 签的是"这次观测的断言"，不是下一跳的票。
        assertThat(body.has("ticket")).isFalse();
        assertThat(text(claims, "profile")).isEqualTo(GATEWAY_EVENTS_PROFILE);
        assertThat(claims.get("immutable")).isEqualTo(immutable);
        // 2) 时间夹取的落点：checked_until 必须等于 exp，且 exp 不超过客户端声明的 hard_deadline。
        assertThat(epoch(claims, "checked_until")).isEqualTo(epoch(claims, "exp"));
        assertThat(epoch(claims, "exp")).isLessThanOrEqualTo(epoch(immutable, "hard_deadline"));
        assertThat(epoch(claims, "exp")).isGreaterThan(Instant.now().getEpochSecond());
        // 3) 断言必须与 retained（S2）授权逐项同源：票 jti/grant/operation/ceiling 一个都不能换。
        assertThat(text(claims, "ticket_jti")).isEqualTo(text(s2, "ticket_jti"));
        assertThat(text(claims, "subject_caller")).isEqualTo(GATEWAY);
        assertThat(text(claims, "origin_authentication_id")).isEqualTo(ORIGIN);
        assertThat(claims.get("operation")).isEqualTo(s2.get("operation"));
        assertThat(text(claims, "grant_ref")).isEqualTo(text(s2, "grant_ref"));
        assertThat(claims.get("initial_ceiling")).isEqualTo(s2.get("initial_ceiling"));
        assertThat(epoch(claims, "grant_expires_at")).isEqualTo(epoch(s2, "grant_expires_at"));
        // 4) 3.3 的禁用 claims：S3 不允许借"父响应"或"完成许可"把两段授权拼起来。
        assertThat(claims.has("parent_response_id")).isFalse();
        assertThat(claims.has("completion_permit")).isFalse();
        assertThat(claims.has("parent_response")).isFalse();

        // 5) continuation：typ/use/aud/exp 都由服务端本地 KeyRing 签，exp 必须等于 hard_deadline。
        JsonNode continuation = verified(text(claims, "continuation_ref"), CONTINUATION_TYPE);
        assertThat(text(continuation, "token_use")).isEqualTo(CONTINUATION_USE);
        assertThat(text(continuation, "aud")).isEqualTo("urn:agent-platform:service:" + GATEWAY);
        assertThat(epoch(continuation, "exp")).isEqualTo(epoch(immutable, "hard_deadline"));
        assertThat(continuation.get("immutable")).isEqualTo(immutable);
        assertThat(continuation.has("parent_response_id")).isFalse();
        assertThat(continuation.has("completion_permit")).isFalse();
        assertThat(continuation.has("service_ticket")).isFalse();

        // 6) D3 消费 + D4 落库：重放保护与 EX-45 的反查都依赖这两条决策。
        String retainedDecision = text(s2, "jti");
        String eventsDecision = text(claims, "decision_id");
        DecisionStore.Decision consumed = decisions.find(retainedDecision).orElseThrow();
        assertThat(consumed.consumedAt()).isNotNull();
        assertThat(consumed.consumedBy()).isEqualTo(GATEWAY);
        DecisionStore.Decision events = decisions.find(eventsDecision).orElseThrow();
        assertThat(events.purpose()).isEqualTo(DecisionStore.Purpose.EVENTS);
        assertThat(events.parentAdmissionId()).isEqualTo(retainedDecision);
        assertThat(events.bindingResponseId()).isEqualTo(binding.responseId());
        assertThat(events.continuationJti()).isEqualTo(text(continuation, "jti"));
        assertThat(events.hardDeadline()).isEqualTo(Instant.ofEpochSecond(epoch(immutable, "hard_deadline")));
        assertThat(hasReason(auditLines(audit), "EVENTS_OPEN_REPLAY")).isFalse();
    }

    @Test
    void replayingTheSameOpenIsForbiddenNotConflict() {
        Flow flow = flow("replay");
        PortalEventsBinding binding = binding(flow, 7, nowPlusSeconds(40));
        JsonNode immutable = immutable(flow, binding);
        assertThat(openEvents(flow, binding, immutable, "n-s3-replay").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        var audit = attachAudit();
        ResponseEntity<JsonNode> replay = openEvents(flow, binding, immutable, "n-s3-replay");

        assertThat(replay.getStatusCode()).as("replay body: %s", replay.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(replay.getBody().path("code").textValue()).isEqualTo("SYS-AUTH-403");
        assertThat(hasReason(auditLines(audit), "EVENTS_OPEN_REPLAY")).isTrue();
    }

    @Test
    void aHardDeadlineBeyondTheServerClampIsRejected() {
        Flow flow = flow("deadline");
        PortalEventsBinding binding = binding(flow, 7, nowPlusSeconds(120));
        JsonNode immutable = immutable(flow, binding);

        var audit = attachAudit();
        ResponseEntity<JsonNode> response = openEvents(flow, binding, immutable, "n-s3-deadline");

        assertThat(response.getStatusCode()).as("deadline body: %s", response.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(hasReason(auditLines(audit), "EVENTS_DEADLINE")).isTrue();
    }

    @Test
    void aTamperedImmutableIsRejectedAsImmutableMismatch() {
        Flow flow = flow("immutable");
        PortalEventsBinding binding = binding(flow, 7, nowPlusSeconds(40));
        ObjectNode immutable = (ObjectNode) immutable(flow, binding);
        // 只改一个业务字段：服务端必须按整体节点等值拒绝，而不是只比对其中几个字段。
        immutable.put("admission_nonce", "n-tampered");

        var audit = attachAudit();
        ResponseEntity<JsonNode> response = openEvents(flow, binding, immutable, "n-s3-immutable");

        assertThat(response.getStatusCode()).as("immutable body: %s", response.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(hasReason(auditLines(audit), "EVENTS_IMMUTABLE")).isTrue();
    }

    @Test
    void anUnknownDecisionIdIsRejectedAsDecisionNotBinding() {
        Flow flow = flow("unknown");
        PortalEventsBinding binding = binding(flow, 7, nowPlusSeconds(40));
        JsonNode immutable = immutable(flow, binding);

        var audit = attachAudit();
        ResponseEntity<JsonNode> response = openEventsFor("dec-unknown", binding, immutable, "n-s3-unknown");

        assertThat(response.getStatusCode()).as("unknown body: %s", response.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(hasReason(auditLines(audit), "EVENTS_DECISION")).isTrue();
    }
}
