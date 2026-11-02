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

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-46 切片 1f：{@code portal_events_child_open}（S5）与两面续期（S6 gateway / S6' child）的契约测试。
 *
 * <p>这张卡要解决的线上现象：SSE 观测流在打开约 5s 后被 abort。根因不在 55s 硬窗，而在
 * {@code AuthorityServiceProperties.assertionSeconds=5}——每条 events 决策的 {@code checked_until}
 * 只有 5s；客户端 deadline 线程在剩余 ≤3s 时调 {@code checkPortalEvents}（{@code PortalEventsResponse.tick()}），
 * 而 {@code AuthorityController} 里根本没有 {@code portal_gateway_events_check} /
 * {@code portal_events_child_check} 这两个分支，续期请求 403 后客户端记住失败并
 * {@code requireCurrent()} 直接抛，于是流在 5s 处死掉。因此 S6/S6' 的职责是：
 * <ol>
 *   <li>续期<b>只刷新 checked_until</b>，绝不再消费父决策、绝不新开决策（否则一段 60s 的流要换 12 张决策）；</li>
 *   <li>续期窗口严格不超过开窗时钉死的 {@code hard_deadline}（客户端 {@code verifyEventsContinuation}
 *       要求 {@code exp == immutable.hard_deadline}，这一条是客户端的硬约束，不是服务端口味）；</li>
 *   <li>child 续期必须能按 continuation 反查到一条 {@code purpose=CHILD_OPEN} 的窗口决策，且
 *       immutable 逐字节等于开窗那次（客户端 {@code install} 要求 same immutable）。</li>
 * </ol>
 *
 * <p>S5 另外要产出客户端 {@code openPortalEventsChild:731-757} 会验的 {@code child_receipt}：
 * 它取 association 里没有的三个 engine 值（opening decision id / admission nonce / admission request sha），
 * 让 gateway 能按 {@code expectedChildImmutable:781-794} 在本地重建 engine 的 immutable。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = AuthorityApplication.class,
        properties = {AuthorityTestSupport.P_ISSUER, AuthorityTestSupport.P_KID, AuthorityTestSupport.P_KEY,
                AuthorityTestSupport.P_GATEWAY, DelegatedTestSupport.P_ENGINE, DelegatedTestSupport.P_DELEGATION_ON})
class GatewayEventsSlice1fContractTest extends GatewayEventsSlice1fSupport {

    @Autowired
    DecisionStore decisions;

    @Test
    void theEngineOpensTheChildWindowAndGetsAChildReceipt() {
        Child child = child("s5");
        JsonNode immutable = engineImmutable(child);

        ResponseEntity<JsonNode> response = childOpen(child, immutable, "n-s5-open");

        assertThat(response.getStatusCode()).as("S5 body: %s", response.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode claims = claimsOf(response.getBody());

        // 1) 回显的就是 engine 送上来的 immutable（客户端 install 的 same-immutable 前提）。
        assertThat(text(claims, "profile")).isEqualTo(CHILD_EVENTS_PROFILE);
        assertThat(claims.get("immutable")).isEqualTo(immutable);

        // 2) child continuation：typ/use/aud 由 KeyRing 验，exp 恰为窗口 hard_deadline，且只带摘要。
        String continuation = text(claims, "continuation_ref");
        JsonNode proof = verified(continuation, CHILD_CONTINUATION_TYPE);
        assertThat(text(proof, "token_use")).isEqualTo(CHILD_CONTINUATION_USE);
        assertThat(text(proof, "aud")).isEqualTo(ENGINE_AUDIENCE);
        assertThat(epoch(proof, "exp")).isEqualTo(child.hardDeadline());
        assertThat(text(proof, "immutable_sha256")).isEqualTo(AuthorityProtocol.digest(immutable));
        // 客户端 verifyEventsContinuation 明令禁止这三个 claim 出现在 continuation 里。
        assertThat(proof.has("parent_response_id")).isFalse();
        assertThat(proof.has("completion_permit")).isFalse();
        assertThat(proof.has("service_ticket")).isFalse();

        // 3) receipt：客户端 verifyChildReceipt 要求 aud=gateway、exp=窗口、association/child 双 id 自洽。
        String receipt = text(claims, "child_receipt");
        JsonNode receiptClaims = verified(receipt, RECEIPT_TYPE);
        assertThat(text(receiptClaims, "token_use")).isEqualTo(RECEIPT_USE);
        assertThat(text(receiptClaims, "aud")).isEqualTo(GATEWAY_AUDIENCE);
        assertThat(epoch(receiptClaims, "exp")).isEqualTo(child.hardDeadline());
        assertThat(text(receiptClaims, "association_sha256"))
                .isEqualTo(AuthorityProtocol.digest(child.association()));
        assertThat(text(receiptClaims, "parent_response_id"))
                .isEqualTo(text(child.association().path("parent").path("binding"), "responseId"));
        assertThat(text(receiptClaims, "child_response_id"))
                .isEqualTo(text(child.association().path("child_binding"), "responseId"));

        // 4) 决策落点：D6 是可续期的 CHILD_OPEN 窗口，父指向 D5；D5 被消费一次，D6 不会被消费。
        DecisionStore.Decision window = decisions.find(text(claims, "decision_id")).orElseThrow();
        assertThat(window.purpose()).isEqualTo(DecisionStore.Purpose.CHILD_OPEN);
        assertThat(window.parentAdmissionId()).isEqualTo(child.decisionId());
        assertThat(window.immutable()).isEqualTo(immutable);
        assertThat(window.hardDeadline()).isEqualTo(Instant.ofEpochSecond(child.hardDeadline()));
        assertThat(window.consumedAt()).isNull();
        DecisionStore.Decision opened = decisions.find(child.decisionId()).orElseThrow();
        assertThat(opened.consumedAt()).isNotNull();
        assertThat(opened.consumedBy()).isEqualTo(DelegatedTestSupport.ENGINE);

        // 5) 窗口：checked_until == exp 且严格不超过 hard_deadline（断言窗 5s 的刷新口径）。
        assertThat(epoch(claims, "exp")).isEqualTo(epoch(claims, "checked_until"))
                .isLessThanOrEqualTo(child.hardDeadline());
        assertThat(epoch(claims, "exp")).isGreaterThan(Instant.now().getEpochSecond());
    }

    @Test
    void theGatewayRebuildsTheEngineImmutableFromTheAssociationAndTheReceipt() {
        Child child = child("s5-rebuild");
        JsonNode immutable = engineImmutable(child);

        ResponseEntity<JsonNode> response = childOpen(child, immutable, "n-s5-rebuild");
        assertThat(response.getStatusCode()).as("S5 body: %s", response.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode claims = claimsOf(response.getBody());
        JsonNode receiptClaims = verified(text(claims, "child_receipt"), RECEIPT_TYPE);

        // receipt 必须把网关无法从 association 推出的三个 engine 值带出来，否则 :790 的 same(...) 必挂。
        assertThat(text(receiptClaims, "engine_opening_decision_id")).isEqualTo(child.decisionId());
        assertThat(text(receiptClaims, "engine_admission_nonce")).isEqualTo(text(immutable, "admission_nonce"));
        assertThat(text(receiptClaims, "engine_admission_request_sha256"))
                .isEqualTo(text(immutable, "admission_request_sha256"));
        assertThat(child.association().has("admission_nonce")).isFalse();
        assertThat(child.association().has("opening_decision_id")).isFalse();

        // 关键：网关只用 association + child_open digest + receipt 的三个字段重建 immutable，
        // 其 digest 必须等于 receipt 的 child_immutable_sha256（acceptGatewayChildReceipt 的最后一步）。
        JsonNode rebuilt = expectedChildImmutable(child.association(),
                AuthorityProtocol.digest(child.childOpen()), receiptClaims);
        assertThat(AuthorityProtocol.digest(rebuilt)).isEqualTo(text(receiptClaims, "child_immutable_sha256"));
        assertThat(AuthorityProtocol.digest(rebuilt)).isEqualTo(AuthorityProtocol.digest(immutable));
    }

    @Test
    void theChildWindowRenewsWithoutConsumingTheOpenedDecision() {
        Child child = child("s6-child");
        JsonNode immutable = engineImmutable(child);
        ResponseEntity<JsonNode> opened = childOpen(child, immutable, "n-s6-child-open");
        assertThat(opened.getStatusCode()).as("S5 body: %s", opened.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode first = claimsOf(opened.getBody());
        String decisionId = text(first, "decision_id");
        String continuation = text(first, "continuation_ref");

        ResponseEntity<JsonNode> renewed = renewal(DelegatedTestSupport.ENGINE,
                DelegatedTestSupport.ENGINE_SECRET, CHILD_CHECK, CHILD_EVENTS_PROFILE, childCall(child.parent()),
                child.association().get("child_binding"), continuation, "n-s6-child-renew-1");
        assertThat(renewed.getStatusCode()).as("S6' body: %s", renewed.getBody()).isEqualTo(HttpStatus.OK);

        JsonNode claims = claimsOf(renewed.getBody());
        assertThat(text(claims, "profile")).isEqualTo(CHILD_EVENTS_PROFILE);
        assertThat(claims.get("immutable")).isEqualTo(immutable);
        assertThat(text(claims, "continuation_ref")).isEqualTo(continuation);
        assertThat(text(claims, "decision_id")).isEqualTo(decisionId);
        assertThat(epoch(claims, "exp")).isEqualTo(epoch(claims, "checked_until"))
                .isLessThanOrEqualTo(child.hardDeadline());
        assertThat(epoch(claims, "exp")).isGreaterThanOrEqualTo(Instant.now().getEpochSecond());

        // 续期不消费窗口决策，也不消费父（S4 的 D5）——否则第二次续期就会 403 REPLAY。
        ResponseEntity<JsonNode> again = renewal(DelegatedTestSupport.ENGINE,
                DelegatedTestSupport.ENGINE_SECRET, CHILD_CHECK, CHILD_EVENTS_PROFILE, childCall(child.parent()),
                child.association().get("child_binding"), continuation, "n-s6-child-renew-2");
        assertThat(again.getStatusCode()).as("S6' second body: %s", again.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(decisions.find(decisionId).orElseThrow().consumedAt()).isNull();
        assertThat(decisions.find(child.decisionId()).orElseThrow().consumedAt()).isNotNull();
    }

    @Test
    void aChildRenewalBeyondTheOpenedWindowIsRejected() {
        Child child = child("s6-child-over");
        JsonNode immutable = engineImmutable(child);
        ResponseEntity<JsonNode> opened = childOpen(child, immutable, "n-s6-over-open");
        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode first = claimsOf(opened.getBody());

        // 自签一张 exp 比窗口更长的 continuation：客户端 verifyEventsContinuation 要求 exp==hard_deadline，
        // 服务端必须同样拒绝，否则续期就会把窗口悄悄延长。
        String forged = reforgedChildContinuation(AuthorityProtocol.digest(immutable), 7,
                child.hardDeadline() + 60);
        var audit = attachAudit();
        ResponseEntity<JsonNode> response = renewal(DelegatedTestSupport.ENGINE,
                DelegatedTestSupport.ENGINE_SECRET, CHILD_CHECK, CHILD_EVENTS_PROFILE, childCall(child.parent()),
                child.association().get("child_binding"), forged, "n-s6-over");

        assertThat(response.getStatusCode()).as("renewal body: %s", response.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(hasReason(auditLines(audit), "EVENTS_CHECK_CONTINUATION")).isTrue();
        assertThat(text(first, "continuation_ref")).isNotEqualTo(forged);
    }

    @Test
    void theGatewayWindowRenewsWithoutConsumingD4Again() {
        Parent parent = window("s6-gateway");
        String decisionId = text(parent.claims(), "jti");
        // S3 消费的是 retained（S1）那条；开出来的 D4 窗口本身是可续期的，不该被续期消费。
        String retainedId = text(claimsOf(parent.flow().s2()), "jti");
        Instant retainedConsumedAt = decisions.find(retainedId).orElseThrow().consumedAt();
        assertThat(retainedConsumedAt).isNotNull();
        assertThat(decisions.find(decisionId).orElseThrow().consumedAt()).isNull();

        ResponseEntity<JsonNode> first = renewal(GATEWAY, GATEWAY_SECRET, GATEWAY_CHECK, GATEWAY_EVENTS_PROFILE,
                retainedCall(), Json.read(Json.write(parent.binding())), parent.continuation(), "n-s6-gw-1");
        assertThat(first.getStatusCode()).as("S6 body: %s", first.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode claims = claimsOf(first.getBody());
        assertThat(text(claims, "profile")).isEqualTo(GATEWAY_EVENTS_PROFILE);
        assertThat(claims.get("immutable")).isEqualTo(parent.immutable());
        assertThat(text(claims, "continuation_ref")).isEqualTo(parent.continuation());
        assertThat(text(claims, "decision_id")).isEqualTo(decisionId);
        assertThat(epoch(claims, "exp")).isEqualTo(epoch(claims, "checked_until"))
                .isLessThanOrEqualTo(epoch(parent.immutable(), "hard_deadline"));

        ResponseEntity<JsonNode> second = renewal(GATEWAY, GATEWAY_SECRET, GATEWAY_CHECK, GATEWAY_EVENTS_PROFILE,
                retainedCall(), Json.read(Json.write(parent.binding())), parent.continuation(), "n-s6-gw-2");
        assertThat(second.getStatusCode()).as("S6 second body: %s", second.getBody()).isEqualTo(HttpStatus.OK);

        // 连续续期不重复消费：retained 的消费时间戳不变，D4 仍未被消费，且没有新决策（decision_id 仍是 D4）。
        assertThat(decisions.find(retainedId).orElseThrow().consumedAt()).isEqualTo(retainedConsumedAt);
        assertThat(decisions.find(decisionId).orElseThrow().consumedAt()).isNull();
        assertThat(text(claimsOf(second.getBody()), "decision_id")).isEqualTo(decisionId);
    }

    @Test
    void replayingTheSameChildOpenIsForbiddenNotConflict() {
        Child child = child("s5-replay");
        JsonNode immutable = engineImmutable(child);
        assertThat(childOpen(child, immutable, "n-s5-replay-1").getStatusCode()).isEqualTo(HttpStatus.OK);

        var audit = attachAudit();
        ResponseEntity<JsonNode> replay = childOpen(child, immutable, "n-s5-replay-2");

        assertThat(replay.getStatusCode()).as("replay body: %s", replay.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(replay.getBody().path("code").textValue()).isEqualTo("SYS-AUTH-403");
        assertThat(hasReason(auditLines(audit), "EVENTS_CHILD_OPEN_REPLAY")).isTrue();
    }

    @Test
    void aTamperedChildAssociationIsRejected() {
        Child child = child("s5-association");
        JsonNode immutable = engineImmutable(child);
        ObjectNode tampered = (ObjectNode) immutable.deepCopy();
        ObjectNode association = (ObjectNode) child.association().deepCopy();
        association.put("source_token_sha256", "0".repeat(64));
        tampered.set("child_association", association);

        var audit = attachAudit();
        ResponseEntity<JsonNode> response = childOpen(child, tampered, "n-s5-association");

        assertThat(response.getStatusCode()).as("tampered body: %s", response.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(hasReason(auditLines(audit), "EVENTS_CHILD_OPEN_ASSOCIATION")).isTrue();
    }

    @Test
    void aChildImmutableWithADifferentDeadlineIsRejected() {
        Child child = child("s5-deadline");
        ObjectNode tampered = (ObjectNode) engineImmutable(child).deepCopy();
        tampered.put("hard_deadline", child.hardDeadline() + 60);

        var audit = attachAudit();
        ResponseEntity<JsonNode> response = childOpen(child, tampered, "n-s5-deadline");

        assertThat(response.getStatusCode()).as("deadline body: %s", response.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(hasReason(auditLines(audit), "EVENTS_CHILD_OPEN_DEADLINE")).isTrue();
    }

    @Test
    void aNonEngineChildOpenIsRejected() {
        Child child = child("s5-caller");
        JsonNode immutable = engineImmutable(child);

        var audit = attachAudit();
        ResponseEntity<JsonNode> response = childOpenAs(GATEWAY, GATEWAY_SECRET, child, immutable, "n-s5-caller");

        assertThat(response.getStatusCode()).as("caller body: %s", response.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(hasReason(auditLines(audit), "EVENTS_CHILD_OPEN_CALLER")).isTrue();
    }

    @Test
    void aRenewalWithAnUnknownContinuationIsRejected() {
        Parent parent = window("s6-unknown");
        var audit = attachAudit();

        ResponseEntity<JsonNode> response = renewal(GATEWAY, GATEWAY_SECRET, GATEWAY_CHECK, GATEWAY_EVENTS_PROFILE,
                retainedCall(), Json.read(Json.write(parent.binding())),
                "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1bmtub3duIn0.c2ln", "n-s6-unknown");

        assertThat(response.getStatusCode()).as("unknown body: %s", response.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(hasReason(auditLines(audit), "EVENTS_CHECK_CONTINUATION")).isTrue();
    }
}
