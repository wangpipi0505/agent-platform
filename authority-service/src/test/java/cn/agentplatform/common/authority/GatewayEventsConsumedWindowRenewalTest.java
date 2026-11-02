package cn.agentplatform.common.authority;

import cn.agentplatform.authority.AuthorityApplication;
import cn.agentplatform.authority.AuthorityTestSupport;
import cn.agentplatform.authority.DelegatedTestSupport;
import cn.agentplatform.authority.decision.DecisionStore;
import cn.agentplatform.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-46 回归：父 EVENTS 窗口被 S4 child 换票消费后，S6 的首次续期仍必须 200。
 *
 * <p>线上现象：S4 {@code portal_events_child_exchange} 会用
 * {@code DecisionStore.consume(parent.id(), clientId)} 把父窗口标记为已消费（一次性换票的
 * 单键 CAS）；随后客户端 deadline 线程的第一次 {@code portal_gateway_events_check} 走
 * {@code touchExpiry}，旧实现看到 {@code consumedAt != null} 就返回 empty，把一次合法续期
 * 变成 403 {@code EVENTS_CHECK_CONTINUATION}。这里按真实 HTTP 顺序复现：
 * 开窗（S3）→ child 换票（S4，消费父窗口）→ 立刻续期（S6）→ 再换一次票（必须仍被拒）。
 *
 * <p>“续期 ≠ 复活”：续期只推后 {@code expiresAt}，{@code consumedAt}/{@code consumedBy}
 * 原样保留，所以第二次 child 换票仍必须 403 {@code EVENTS_CHILD_REPLAY}。这条断言同时钉住
 * “touchExpiry 不能用旧快照回写”这一并发不变量：擦掉消费标记会让本用例的 replay 变 200。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = AuthorityApplication.class,
        properties = {AuthorityTestSupport.P_ISSUER, AuthorityTestSupport.P_KID, AuthorityTestSupport.P_KEY,
                AuthorityTestSupport.P_GATEWAY, DelegatedTestSupport.P_ENGINE, DelegatedTestSupport.P_DELEGATION_ON})
class GatewayEventsConsumedWindowRenewalTest extends GatewayEventsSlice1fSupport {

    @Autowired
    DecisionStore decisions;

    @Test
    void aConsumedParentWindowCanStillBeRenewedButNotReplayed() {
        Parent parent = window("ex46-consumed-renew");
        String decisionId = text(parent.claims(), "jti");

        // S4：正常 child 换票。它必须真的消费父窗口，否则本用例测不到续期分支。
        ResponseEntity<JsonNode> exchanged = childExchange(parent, parent.immutable(), "n-s4-consumed-renew");
        assertThat(exchanged.getStatusCode()).as("S4 body: %s", exchanged.getBody())
                .isEqualTo(HttpStatus.OK);
        DecisionStore.Decision consumed = decisions.find(decisionId).orElseThrow();
        assertThat(consumed.consumedAt()).as("S4 必须消费父窗口").isNotNull();
        assertThat(consumed.consumedBy()).isEqualTo(GATEWAY);

        var audit = attachAudit();
        ResponseEntity<JsonNode> renewed = renewal(GATEWAY, GATEWAY_SECRET, GATEWAY_CHECK,
                GATEWAY_EVENTS_PROFILE, retainedCall(), Json.read(Json.write(parent.binding())),
                parent.continuation(), "n-s6-consumed-renew");

        assertThat(renewed.getStatusCode())
                .as("已消费的窗口仍必须可续期（EX-46 之前这里是 403）；body: %s", renewed.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(decisions.find(decisionId).orElseThrow().consumedAt())
                .as("续期只推后 expiresAt，消费标记必须原样保留")
                .isEqualTo(consumed.consumedAt());

        // 父窗口没有复活：同一 continuation 再换一次 child 仍必须被拒（EVENTS_CHILD_REPLAY）。
        ResponseEntity<JsonNode> replay = childExchange(parent, parent.immutable(), "n-s4-consumed-replay");
        assertThat(replay.getStatusCode()).as("replay body: %s", replay.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(replay.getBody().path("code").textValue()).isEqualTo("SYS-AUTH-403");
        assertThat(hasReason(auditLines(audit), "EVENTS_CHILD_REPLAY"))
                .as("重放必须仍落在消费 CAS 上，而不是漂移成别的 403")
                .isTrue();
    }
}
