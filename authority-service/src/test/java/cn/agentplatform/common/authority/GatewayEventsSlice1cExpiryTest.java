package cn.agentplatform.common.authority;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code EVENTS_TICKET_EXPIRED} 的专项：S0 决策的有效期由 {@code delegation.decision-seconds} 控制，
 * 票的 {@code exp} 另由 {@code ticket-seconds}/portal exp 封顶。把 decision-seconds 调到 1 秒后，
 * 等 1.5 秒即得到"票仍由本服务自签且未过期、但它指向的 OPENING 决策已过期"这一组合——
 * 真实的 60s/60s 配置下两者同时到期，走不到决策过期分支。
 *
 * <p>为什么不在一张票上等 60 秒：票一过期 {@code KeyRing.verify} 就先拒了（审计 reason 是通用过期文案），
 * 根本到不了"决策过期"这条语义，测不出 1c 新增的 reason。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = cn.agentplatform.authority.AuthorityApplication.class,
        properties = {
                cn.agentplatform.authority.AuthorityTestSupport.P_ISSUER,
                cn.agentplatform.authority.AuthorityTestSupport.P_KID,
                cn.agentplatform.authority.AuthorityTestSupport.P_KEY,
                cn.agentplatform.authority.AuthorityTestSupport.P_GATEWAY,
                cn.agentplatform.authority.DelegatedTestSupport.P_ENGINE,
                cn.agentplatform.authority.DelegatedTestSupport.P_DELEGATION_ON,
                "agent.authority.delegation.decision-seconds=1"})
class GatewayEventsSlice1cExpiryTest extends GatewayEventsSlice1cSupport {

    @Test
    void queryTicketWhoseOpeningDecisionExpiredIsRejected() throws InterruptedException {
        String portal = portalToken(300, ORIGIN, DelegatedTickets.SUBJECT, "ses-1c-expired");
        JsonNode opening = open(portal, "1c-open-expired");
        String queryTicket = opening.get("ticket").asText();
        // 决策 expiresAt = issued+1s；票 exp = issued+60s，所以睡 1.5s 后票仍有效、决策已过期。
        Thread.sleep(1500);

        ListAppender<ILoggingEvent> audit = attachAudit();
        ResponseEntity<JsonNode> denied = exchangeQuery(portal, queryTicket, "1c-expired");

        assertThat(denied.getStatusCode()).as("body: %s", denied.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody().get("code").asText()).isEqualTo("SYS-AUTH-403");
        assertThat(hasReason(auditLines(audit), "EVENTS_TICKET_EXPIRED")).isTrue();
    }
}
