package cn.agentplatform.authority;

import cn.agentplatform.authority.AuthorityApplication;
import cn.agentplatform.common.authority.DelegatedTickets;
import cn.agentplatform.common.authority.ServiceCall;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-42 的可区分拒绝：决策过期必须是 409 {@code AUTHORITY_EXPIRED}，不能被折叠成 403。
 *
 * <p>窗口用 {@code decision-seconds=1} 压到最小：决策存活窗口只决定"还能不能再转发一次"，
 * 与 grant（{@code grant-seconds}，默认 60）分离，所以这里不缩短 grant 也能测出过期分支。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = AuthorityApplication.class,
        properties = {
                AuthorityTestSupport.P_ISSUER,
                AuthorityTestSupport.P_KID,
                AuthorityTestSupport.P_KEY,
                AuthorityTestSupport.P_GATEWAY,
                AuthorityTestSupport.P_APP,
                DelegatedTestSupport.P_ENGINE,
                DelegatedTestSupport.P_DELEGATION_ON,
                "agent.authority.delegation.decision-seconds=1"})
class DelegatedDecisionExpiryTest extends DelegatedTestSupport {

    @Test
    void expiredDecisionIsRefusedWithAuthorityExpired() throws InterruptedException {
        ServiceCall call = call(ENGINE, FIRST_ACTION);
        String bootstrap = DelegatedTickets.bootstrap(keys, call, GATEWAY);
        JsonNode decision = authorize(bootstrap, call, "expiry");

        Thread.sleep(1_500L);

        ResponseEntity<JsonNode> refused = postForward(decision, call, "expiry-after-expired");

        assertThat(refused.getStatusCode().value()).isEqualTo(409);
        assertThat(refused.getBody().get("code").asText()).isEqualTo("AUTHORITY_EXPIRED");
    }

    @Test
    void unknownDecisionIsForbiddenRatherThanExpired() {
        ServiceCall call = call(ENGINE, FIRST_ACTION);

        ResponseEntity<JsonNode> refused = postJson("/oauth/token", basic(ENGINE, ENGINE_SECRET),
                body("forward", call, "unknown-decision", java.util.Map.of(
                        "decision_id", "dec-does-not-exist",
                        "grant_ref", DelegatedTickets.GRANT_REF,
                        "operation_id", DelegatedTickets.OPERATION_ID)));

        assertThat(refused.getStatusCode().value()).isEqualTo(403);
        assertThat(refused.getBody().get("code").asText()).isEqualTo("SYS-AUTH-403");
    }
}
