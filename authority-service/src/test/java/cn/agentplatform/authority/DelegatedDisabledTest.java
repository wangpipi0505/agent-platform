package cn.agentplatform.authority;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.agentplatform.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回滚开关：{@code agent.authority.delegation.enabled} 默认关，关闭时 {@code authorize}/{@code forward}
 * 一律 403，且不需要（也不应该）先验票——关闭语义是"这两条链路不存在"，不是"票不合法"。
 *
 * <p>本类不覆写 {@code agent.authority.delegation.enabled}，因此断言的就是<b>生产默认值</b>；
 * 除状态码外还要求审计里写着 {@code reason=DELEGATION_DISABLED}，否则"没实现"与"关着"分不开。
 *
 * <p>properties 与其它 M1 测试一致，因此复用同一个 Spring 上下文。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = AuthorityApplication.class,
        properties = {
                AuthorityTestSupport.P_ISSUER,
                AuthorityTestSupport.P_KID,
                AuthorityTestSupport.P_KEY,
                AuthorityTestSupport.P_GATEWAY,
                AuthorityTestSupport.P_APP})
class DelegatedDisabledTest extends AuthorityTestSupport {

    @Test
    void authorizeIsForbiddenWhileDelegationStaysOff() {
        ListAppender<ILoggingEvent> audit = attachAudit();
        try {
            ResponseEntity<JsonNode> refused = postJson("/internal/authorize", basic(GATEWAY, GATEWAY_SECRET),
                    requestBody("authorize", APP, ORIGIN_ACTION, "off-authorize", "not-a-real-ticket"));

            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(refused.getBody().get("code").asText()).isEqualTo("SYS-AUTH-403");
            assertThat(auditLines(audit)).anyMatch(line -> line.contains("reason=DELEGATION_DISABLED"));
        } finally {
            detachAudit(audit);
        }
    }

    @Test
    void forwardIsForbiddenWhileDelegationStaysOff() {
        String body = Json.write(Map.of(
                "operation", "forward",
                "target", APP,
                "action", ORIGIN_ACTION,
                "resource", resource(),
                "nonce", "off-forward",
                "decision_id", "dec-never-issued",
                "grant_ref", "grt-never-issued",
                "operation_id", "op-never-issued"));

        ListAppender<ILoggingEvent> audit = attachAudit();
        try {
            // forward 走 tokenUri：关闭开关时应该在 operation 分派处就 403，而不是"票据不合法"。
            ResponseEntity<JsonNode> refused = postJson("/oauth/token", basic(GATEWAY, GATEWAY_SECRET), body);

            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(refused.getBody().get("code").asText()).isEqualTo("SYS-AUTH-403");
            assertThat(auditLines(audit)).anyMatch(line -> line.contains("reason=DELEGATION_DISABLED"));
        } finally {
            detachAudit(audit);
        }
    }

    private static ListAppender<ILoggingEvent> attachAudit() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger("agent.authority.audit")).addAppender(appender);
        return appender;
    }

    private static void detachAudit(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger("agent.authority.audit")).detachAppender(appender);
    }

    private static List<String> auditLines(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
