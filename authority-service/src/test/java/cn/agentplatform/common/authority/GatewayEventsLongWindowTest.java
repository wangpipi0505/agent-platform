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
 * EX-46 验收：55s+ 连续出流的全周期。
 *
 * <p>生产硬窗是 55s（{@code DelegatedAuthority} 与客户端同值夹取），没法在单测里真等 55s。
 * {@code agent.authority.events-hard-window-seconds} 只夹 S0/S3 的 {@code hard_deadline} 上界，
 * 这里压到 6s、断言窗压到 1s，就能在几秒内跑完"开窗 → 反复续期跨过首个断言窗 → 到点才失效"的
 * 完整生命周期；断言窗上限与客户端夹取都仍是 5s/55s，不随这个配置变。
 *
 * <p>用户指出的缺陷是：续期只刷 {@code checked_until}，如果登记的有效期也跟着 {@code checked_until} 走，
 * 那么首次断言窗一过，下一次 {@code DecisionStore.put(...)} 里的 {@code sweep()} 就会把仍在硬窗内、
 * 且刚被续期的窗口当过期清掉——第 2 次续期就会 403 {@code EVENTS_CHECK_CONTINUATION}。
 * 所以本用例在续期途中主动开第二张窗口触发 sweep，并在每次续期后断言登记还在、有效期仍是
 * {@code hard_deadline}。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = AuthorityApplication.class,
        properties = {AuthorityTestSupport.P_ISSUER, AuthorityTestSupport.P_KID, AuthorityTestSupport.P_KEY,
                AuthorityTestSupport.P_GATEWAY, DelegatedTestSupport.P_ENGINE, DelegatedTestSupport.P_DELEGATION_ON,
                "agent.authority.assertion-seconds=1", "agent.authority.events-hard-window-seconds=6"})
@TestPropertySource(properties = {"agent.authority.assertion-seconds=1",
        "agent.authority.events-hard-window-seconds=6"})
class GatewayEventsLongWindowTest extends GatewayEventsSlice1fSupport {

    @Autowired
    DecisionStore decisions;

    @Test
    void renewalsCrossTheFirstAssertionWindowAndStopAtTheHardDeadline() throws Exception {
        Parent parent = window("ex46-long-window", nowPlusSeconds(6));
        String decisionId = text(parent.claims(), "jti");
        long hardDeadline = epoch(parent.immutable(), "hard_deadline");
        long openedAt = Instant.now().getEpochSecond();
        assertThat(hardDeadline - openedAt)
                .as("6s 硬窗下 hard_deadline 必须仍在未来 5..7s，后面的续期才真的跨过首个断言窗")
                .isBetween(5L, 7L);

        JsonNode binding = Json.read(Json.write(parent.binding()));
        long firstCheckedUntil = epoch(parent.claims(), "checked_until");
        long previous = firstCheckedUntil;

        for (int i = 1; i <= 5; i++) {
            if (i > 1) {
                Thread.sleep(1_000L);
            }
            if (i == 3) {
                // 首个断言窗（1s）早已过去。再开一张窗口会走 S3 的 put -> sweep()：
                // 若登记仍以 checked_until 为 expiresAt，仍在硬窗内的登记会在这里被清掉。
                window("ex46-long-window-sweep", nowPlusSeconds(6));
                assertThat(decisions.find(decisionId))
                        .as("hard_deadline 之前的登记不能被 sweep 当成过期清掉")
                        .isPresent();
                assertThat(decisions.find(decisionId).orElseThrow().expiresAt())
                        .as("登记有效期固定为 hard_deadline，不随每次续期改变")
                        .isEqualTo(Instant.ofEpochSecond(hardDeadline));
            }
            ResponseEntity<JsonNode> renewed = renewal(GATEWAY, GATEWAY_SECRET, GATEWAY_CHECK,
                    GATEWAY_EVENTS_PROFILE, retainedCall(), binding, parent.continuation(), "n-s6-long-" + i);
            assertThat(renewed.getStatusCode()).as("第 %d 次续期 body: %s", i, renewed.getBody())
                    .isEqualTo(HttpStatus.OK);

            JsonNode claims = claimsOf(renewed.getBody());
            assertThat(text(claims, "decision_id")).isEqualTo(decisionId);
            assertThat(text(claims, "continuation_ref")).isEqualTo(parent.continuation());
            long checkedUntil = epoch(claims, "checked_until");
            assertThat(checkedUntil)
                    .as("第 %d 次续期的 checked_until 必须单调前推且不越过 hard_deadline", i)
                    .isBetween(previous, hardDeadline);
            assertThat(epoch(claims, "exp")).isEqualTo(checkedUntil);
            previous = checkedUntil;
        }
        assertThat(previous)
                .as("跨过首个断言窗后 checked_until 必须真的前推，而不是只重签同一段")
                .isGreaterThan(firstCheckedUntil);

        // 越过 hard_deadline。continuation 的 exp 就是 hard_deadline，KeyRing.verify 对 exp<=now
        // 一律 403（见 DelegatedAuthority#verifyWindowContinuation 的注释），而不是 409 AUTHORITY_EXPIRED；
        // 客户端据此结束流。这里断言稳定的 code/message，而不是只断言 403。
        int polls = 0;
        while (Instant.now().getEpochSecond() <= hardDeadline && polls++ < 100) {
            Thread.sleep(100L);
        }
        assertThat(Instant.now().getEpochSecond()).isGreaterThan(hardDeadline);

        ResponseEntity<JsonNode> expired = renewal(GATEWAY, GATEWAY_SECRET, GATEWAY_CHECK,
                GATEWAY_EVENTS_PROFILE, retainedCall(), binding, parent.continuation(), "n-s6-long-expired");
        assertThat(expired.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(text(expired.getBody(), "code")).isEqualTo("SYS-AUTH-403");
        assertThat(text(expired.getBody(), "message")).isEqualTo("当前授权不允许此操作");
        assertThat(decisions.find(decisionId))
                .as("403 来自 continuation 到点，而不是登记被清：sweep 只在写入时发生")
                .isPresent();
    }
}
