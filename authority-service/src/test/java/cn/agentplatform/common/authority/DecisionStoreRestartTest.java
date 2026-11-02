package cn.agentplatform.common.authority;

import cn.agentplatform.authority.AuthorityApplication;
import cn.agentplatform.authority.AuthorityException;
import cn.agentplatform.authority.AuthorityTestSupport;
import cn.agentplatform.authority.DelegatedTestSupport;
import cn.agentplatform.authority.api.AuthorityRequest;
import cn.agentplatform.authority.api.TicketService;
import cn.agentplatform.authority.config.AuthorityServiceProperties;
import cn.agentplatform.authority.config.DelegationProperties;
import cn.agentplatform.authority.config.IssuerResolver;
import cn.agentplatform.authority.decision.DecisionStore;
import cn.agentplatform.authority.decision.DelegatedAuthority;
import cn.agentplatform.authority.identity.IdentityProperties;
import cn.agentplatform.authority.route.RouteDirectory;
import cn.agentplatform.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EX-46 验收：重启后的行为——{@link DecisionStore} 是进程内账本，重启即丢登记。
 *
 * <p>这是显式债务，不是静默容忍：新进程上 continuation 仍能验签、时间也没到硬截止，但
 * {@code findByContinuationJti} 落空，续期入口据此返回 403 {@code EVENTS_CHECK_CONTINUATION}
 * （{@code SYS-AUTH-403} / "当前授权不允许此操作"），客户端会结束流，而不是拿着旧窗口继续出流。
 * 持久化口径见 {@link DecisionStore} 类注释所引的 EX-44 §6.1 与 EX-46 的 PG 迁移方案；
 * 本用例不启动第二个进程，只换一个新的 store 实例来模拟"旧账本整体丢失"。
 *
 * <p>为什么还要直接调 {@link DelegatedAuthority#eventsWindowCheck}：HTTP 层只能看到 403，
 * 这里要钉住拒绝原因是 {@code EVENTS_CHECK_CONTINUATION}（登记缺失），而不是别的 403。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = AuthorityApplication.class,
        properties = {AuthorityTestSupport.P_ISSUER, AuthorityTestSupport.P_KID, AuthorityTestSupport.P_KEY,
                AuthorityTestSupport.P_GATEWAY, DelegatedTestSupport.P_ENGINE, DelegatedTestSupport.P_DELEGATION_ON})
@TestPropertySource(properties = "agent.authority.assertion-seconds=1")
class DecisionStoreRestartTest extends GatewayEventsSlice1fSupport {

    @Autowired
    DecisionStore decisions;
    @Autowired
    TicketService tickets;
    @Autowired
    RouteDirectory routes;
    @Autowired
    DelegationProperties delegation;
    @Autowired
    AuthorityServiceProperties properties;
    @Autowired
    IdentityProperties identity;
    @Autowired
    IssuerResolver issuer;
    @Autowired
    Clock clock;

    @Test
    void aRestartedLedgerFailsClosedAndTheWindowCheckReturns403() throws Exception {
        Parent parent = window("ex46-restart");
        String decisionId = text(parent.claims(), "jti");
        long hardDeadline = epoch(parent.immutable(), "hard_deadline");
        String continuationJti = text(verified(parent.continuation(), CONTINUATION_TYPE), "continuation_jti");

        // 旧进程的账本里登记还在：续期 200。
        assertThat(decisions.find(decisionId)).isPresent();
        ResponseEntity<JsonNode> before = renewal(GATEWAY, GATEWAY_SECRET, GATEWAY_CHECK, GATEWAY_EVENTS_PROFILE,
                retainedCall(), Json.read(Json.write(parent.binding())), parent.continuation(), "n-s6-restart-before");
        assertThat(before.getStatusCode()).as("重启前续期 body: %s", before.getBody()).isEqualTo(HttpStatus.OK);

        // 重启：同一份配置、同一把密钥，只有账本是空的。
        DecisionStore restarted = new DecisionStore(delegation.capacity(), clock);
        assertThat(restarted.find(decisionId)).as("重启后按 decision id 查不到旧登记").isEmpty();
        assertThat(restarted.touchExpiry(decisionId, Instant.ofEpochSecond(hardDeadline)))
                .as("没有登记时 touchExpiry 不能凭空创建一条").isEmpty();
        assertThat(restarted.find(decisionId)).isEmpty();
        assertThat(restarted.findByContinuationJti(continuationJti))
                .as("continuation 索引同样随进程丢失").isEmpty();

        // 调用方入口：continuation 仍能验签、也还没到 hard_deadline，但新账本里没有这个窗口。
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("profile", GATEWAY_EVENTS_PROFILE);
        extra.put("binding", Json.read(Json.write(parent.binding())));
        extra.put("continuation_ref", parent.continuation());
        AuthorityRequest request = AuthorityRequest.parse(
                command(GATEWAY_CHECK, retainedCall(), "n-s6-restart-after", extra)
                        .getBytes(StandardCharsets.UTF_8));
        DelegatedAuthority afterRestart = new DelegatedAuthority(keys, tickets, routes, restarted,
                delegation, properties, identity, issuer, clock);

        assertThatThrownBy(() -> afterRestart.eventsWindowCheck(GATEWAY, request))
                .isInstanceOfSatisfying(AuthorityException.class, denied -> {
                    assertThat(denied.status()).isEqualTo(403);
                    assertThat(denied.code()).isEqualTo("SYS-AUTH-403");
                    assertThat(denied.auditReason()).isEqualTo("EVENTS_CHECK_CONTINUATION");
                    assertThat(denied.getMessage()).isEqualTo("当前授权不允许此操作");
                });
    }
}
