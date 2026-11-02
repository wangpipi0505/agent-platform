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
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-46 回归：读窗口的<b>登记</b>不能活成首个断言窗那么短。
 *
 * <p>为什么单独一条：S3/S5 开窗时顺手登记一条 {@code Purpose.EVENTS}/{@code CHILD_OPEN} 决策，
 * 续期（S6/S6'）只重签 5s 断言、不新登记。若登记的有效期取首次 {@code checked_until}，
 * 那么第一个断言窗一过，下一次 {@code DecisionStore.put(...)} 里的 {@code sweep()} 就会把仍在
 * 硬截止内、且刚被客户端续期的窗口当过期清掉——表现为"续期突然 403 EVENTS_CHECK_CONTINUATION"，
 * 长观测在 5s 处断流。正确口径：登记活到 {@code hard_deadline}，有效期只由续期向前推。
 *
 * <p>断言窗压到 1s（{@code agent.authority.assertion-seconds} 允许的最小值）以缩短等待；
 * 触发 sweep 的那次写入用第二条窗口，避免把"被清理"和"被容量淘汰"混在一起。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = AuthorityApplication.class,
        properties = {AuthorityTestSupport.P_ISSUER, AuthorityTestSupport.P_KID, AuthorityTestSupport.P_KEY,
                AuthorityTestSupport.P_GATEWAY, DelegatedTestSupport.P_ENGINE, DelegatedTestSupport.P_DELEGATION_ON,
                "agent.authority.assertion-seconds=1"})
@TestPropertySource(properties = "agent.authority.assertion-seconds=1")
class GatewayEventsWindowExpiryTest extends GatewayEventsSlice1fSupport {

    @Autowired
    DecisionStore decisions;

    @Test
    void theWindowRegistrationOutlivesTheFirstAssertionWindow() throws Exception {
        Parent parent = window("ex46-late-renewal");
        String decisionId = text(parent.claims(), "jti");
        long hardDeadline = epoch(parent.immutable(), "hard_deadline");
        long firstCheckedUntil = epoch(parent.claims(), "checked_until");

        // 越过首个断言窗（1s）。
        Thread.sleep(2_000L);
        assertThat(Instant.now().getEpochSecond()).isGreaterThan(firstCheckedUntil);

        // 任何一次决策写入都会跑 sweep()；用第二条窗口触发，证明"合法窗口不会被顺手清掉"。
        window("ex46-sweep-trigger");
        assertThat(decisions.find(decisionId))
                .as("首个断言窗已过，但仍在 hard_deadline 内的窗口登记必须还在")
                .isPresent();

        ResponseEntity<JsonNode> renewed = renewal(GATEWAY, GATEWAY_SECRET, GATEWAY_CHECK, GATEWAY_EVENTS_PROFILE,
                retainedCall(), Json.read(Json.write(parent.binding())), parent.continuation(), "n-s6-late");
        assertThat(renewed.getStatusCode()).as("S6 body: %s", renewed.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode claims = claimsOf(renewed.getBody());
        assertThat(text(claims, "decision_id")).isEqualTo(decisionId);
        assertThat(epoch(claims, "checked_until"))
                .as("续期必须把窗口往后推，而不是只重签同一段")
                .isGreaterThan(firstCheckedUntil)
                .isLessThanOrEqualTo(hardDeadline);

        // 根因回归：登记有效期必须等于硬截止，而不是首个断言窗。
        assertThat(decisions.find(decisionId).orElseThrow().expiresAt())
                .as("窗口登记的有效期应跟随 hard_deadline")
                .isEqualTo(Instant.ofEpochSecond(hardDeadline));
    }
}
