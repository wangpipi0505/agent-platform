package cn.agentplatform.common.authority;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-44 切片 1c：{@code query_ticket}（变体 B）+ opening 决策的一次性消费。
 *
 * <p>要堵的绕过：1b 之前服务端把 {@code query_ticket} 当未知字段静默丢掉，于是持 portal 用户令牌的
 * gateway 跳过 opening，直接对目录里已有的 {@code gateway -> gateway / gateway.run.events.observe}
 * 行发一次普通 exchange，就拿到一张货真价实的 delegated 观测票——不需要先做 metadata opening，
 * 也不需要任何一次性凭据。1c 之后：这个目的**只能**由"消费一张未过期的 opening 决策"换出来。
 *
 * <p>请求体逐字段照抄客户端 {@code HttpCurrentAuthorityClient.openGatewayPortalEvents:621-622}：
 * {@code operation=exchange, target=gateway, action=gateway.run.events.observe,
 * resource=run-snapshot/r_<32hex>/<64hex>, nonce, subject_token, query_ticket}。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = cn.agentplatform.authority.AuthorityApplication.class,
        properties = {
                cn.agentplatform.authority.AuthorityTestSupport.P_ISSUER,
                cn.agentplatform.authority.AuthorityTestSupport.P_KID,
                cn.agentplatform.authority.AuthorityTestSupport.P_KEY,
                cn.agentplatform.authority.AuthorityTestSupport.P_GATEWAY,
                cn.agentplatform.authority.DelegatedTestSupport.P_ENGINE,
                cn.agentplatform.authority.DelegatedTestSupport.P_DELEGATION_ON})
class GatewayEventsSlice1cContractTest extends GatewayEventsSlice1cSupport {

    @Test
    void queryTicketExchangeMintsTheRetainedDelegation() {
        String portal = portalToken(300, ORIGIN, DelegatedTickets.SUBJECT, "ses-1c-a");
        JsonNode opening = open(portal, "1c-open-a");
        String queryTicket = opening.get("ticket").asText();
        JsonNode openingTicket = verified(queryTicket, "at+jwt");
        long portalExpiresAt = verified(portal, "at+jwt").get("exp").asLong();

        ResponseEntity<JsonNode> response = exchangeQuery(portal, queryTicket, "1c-retained-a");

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        JsonNode assertion = verified(body.get("assertion").asText(),
                "agent-authority-decision+jwt");
        JsonNode ticket = verified(body.get("ticket").asText(), "at+jwt");

        // 断言里的 ticket 必须就是随体返回的那张票（客户端先验断言、再从断言取票）。
        assertThat(assertion.get("ticket").asText()).isEqualTo(body.get("ticket").asText());
        assertThat(assertion.get("ticket_jti").asText()).isEqualTo(ticket.get("jti").asText());
        assertThat(assertion.get("origin_authentication_id").asText()).isEqualTo(ORIGIN);

        assertThat(ticket.get("token_use").asText()).isEqualTo("service");
        assertThat(ticket.get("authority_mode").asText()).isEqualTo("delegated");
        assertThat(ticket.get("caller").asText()).isEqualTo(GATEWAY);
        assertThat(ticket.get("client_id").asText()).isEqualTo(GATEWAY);
        assertThat(ticket.get("target").asText()).isEqualTo("gateway");
        assertThat(ticket.get("action").asText()).isEqualTo(GATEWAY_OBSERVE);
        assertThat(ticket.get("scope").asText()).isEqualTo(GATEWAY_OBSERVE);
        assertThat(ticket.get("aud").asText()).isEqualTo("urn:agent-platform:service:gateway");
        assertThat(ticket.get("tenant_id").asText()).isEqualTo(DelegatedTickets.TENANT_ID);
        assertThat(ticket.get("sub").asText()).isEqualTo(DelegatedTickets.SUBJECT);
        assertThat(ticket.get("origin_authentication_id").asText()).isEqualTo(ORIGIN);

        JsonNode operation = ticket.get("operation");
        assertThat(operation.get("source_kind").asText()).isEqualTo("PORTAL");
        assertThat(operation.get("origin_kind").asText()).isEqualTo("PORTAL");
        assertThat(operation.get("tenant_id").asText()).isEqualTo(DelegatedTickets.TENANT_ID);
        assertThat(operation.get("sub").asText()).isEqualTo(DelegatedTickets.SUBJECT);
        assertThat(operation.get("resource").get("kind").asText()).isEqualTo("run-snapshot");
        assertThat(operation.get("resource").get("id").asText()).isEqualTo(RUN);
        // 时间夹取：op 不晚于 portal 会话 exp；票不晚于 op；grant 与 op 同到期。
        assertThat(operation.get("expires_at").asLong()).isLessThanOrEqualTo(portalExpiresAt);
        assertThat(ticket.get("exp").asLong()).isLessThanOrEqualTo(operation.get("expires_at").asLong());
        assertThat(ticket.get("grant_expires_at").asLong()).isEqualTo(operation.get("expires_at").asLong());
        // §4.4：retained 必须是新授权，不能复用 opening 的 grant/operation。
        assertThat(ticket.get("grant_ref").asText()).isNotEqualTo(openingTicket.get("grant_ref").asText());
        assertThat(operation.get("operation_id").asText())
                .isNotEqualTo(openingTicket.get("operation").get("operation_id").asText());
    }

    @Test
    void queryTicketIsConsumedExactlyOnce() {
        String portal = portalToken(300, ORIGIN, DelegatedTickets.SUBJECT, "ses-1c-b");
        JsonNode opening = open(portal, "1c-open-b");
        String queryTicket = opening.get("ticket").asText();

        ResponseEntity<JsonNode> first = exchangeQuery(portal, queryTicket, "1c-first-b");
        assertThat(first.getStatusCode()).as("body: %s", first.getBody()).isEqualTo(HttpStatus.OK);

        ListAppender<ILoggingEvent> audit = attachAudit();
        ResponseEntity<JsonNode> replay = exchangeQuery(portal, queryTicket, "1c-replay-b");

        assertThat(replay.getStatusCode()).as("body: %s", replay.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(replay.getBody().get("code").asText()).isEqualTo("SYS-AUTH-403");
        assertThat(hasReason(auditLines(audit), "EVENTS_TICKET_CONSUMED")).isTrue();
    }

    /**
     * 未修前的绕过路径：body 不带 {@code query_ticket}，只带 portal 令牌 + 观测 call，
     * 今天会 200 并签出一张 delegated 观测票。1c 之后这条路径必须关闭。
     */
    @Test
    void plainExchangeToTheGatewayObserverCallIsRejected() {
        String portal = portalToken(300, ORIGIN, DelegatedTickets.SUBJECT, "ses-1c-bypass");
        ListAppender<ILoggingEvent> audit = attachAudit();

        ResponseEntity<JsonNode> denied = postJson("/oauth/token", basic(GATEWAY, GATEWAY_SECRET),
                command(EXCHANGE, retainedCall(), "1c-bypass", Map.of("subject_token", portal)));

        assertThat(denied.getStatusCode()).as("body: %s", denied.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody().get("code").asText()).isEqualTo("SYS-AUTH-403");
        assertThat(hasReason(auditLines(audit), "EVENTS_RETAINED_PURPOSE")).isTrue();
    }

    @Test
    void queryTicketWithAnUnknownJtiIsRejected() {
        String portal = portalToken(300, ORIGIN, DelegatedTickets.SUBJECT, "ses-1c-unknown");
        String unknown = orphanOpeningTicket("tkt-unknown-1c", "dec-unknown-1c", "grt-unknown-1c");
        ListAppender<ILoggingEvent> audit = attachAudit();

        ResponseEntity<JsonNode> denied = exchangeQuery(portal, unknown, "1c-unknown");

        assertThat(denied.getStatusCode()).as("body: %s", denied.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody().get("code").asText()).isEqualTo("SYS-AUTH-403");
        assertThat(hasReason(auditLines(audit), "EVENTS_TICKET_UNKNOWN")).isTrue();
    }

    @Test
    void queryTicketFromAnotherPortalSubjectIsRejected() {
        String alice = portalToken(300, ORIGIN, DelegatedTickets.SUBJECT, "ses-1c-alice");
        JsonNode opening = open(alice, "1c-open-alice");
        String bob = portalToken(300, ORIGIN, "bob", "ses-1c-bob");
        ListAppender<ILoggingEvent> audit = attachAudit();

        ResponseEntity<JsonNode> denied = exchangeQuery(bob, opening.get("ticket").asText(), "1c-cross-subject");

        assertThat(denied.getStatusCode()).as("body: %s", denied.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody().get("code").asText()).isEqualTo("SYS-AUTH-403");
        assertThat(hasReason(auditLines(audit), "EVENTS_TICKET_BINDING")).isTrue();
    }

    /** 同一用户、同一 origin，但不是 S0 用的那张令牌原文：source_token_sha256 对不上。 */
    @Test
    void queryTicketFromAnotherSourceTokenIsRejected() {
        String alice = portalToken(300, ORIGIN, DelegatedTickets.SUBJECT, "ses-1c-source");
        JsonNode opening = open(alice, "1c-open-source");
        String other = portalToken(300, ORIGIN, DelegatedTickets.SUBJECT, "ses-1c-source-other");
        ListAppender<ILoggingEvent> audit = attachAudit();

        ResponseEntity<JsonNode> denied = exchangeQuery(other, opening.get("ticket").asText(), "1c-other-source");

        assertThat(denied.getStatusCode()).as("body: %s", denied.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody().get("code").asText()).isEqualTo("SYS-AUTH-403");
        assertThat(hasReason(auditLines(audit), "EVENTS_TICKET_BINDING")).isTrue();
    }

    @Test
    void queryTicketThatIsNotAnOpeningTicketIsRejected() {
        String portal = portalToken(300, ORIGIN, DelegatedTickets.SUBJECT, "ses-1c-shape");
        ListAppender<ILoggingEvent> audit = attachAudit();

        // portal 用户令牌也是本服务签的 at+jwt，但 token_use=user，不是 S0 签出的服务票。
        ResponseEntity<JsonNode> denied = exchangeQuery(portal, portal, "1c-shape");

        assertThat(denied.getStatusCode()).as("body: %s", denied.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody().get("code").asText()).isEqualTo("SYS-AUTH-403");
        assertThat(hasReason(auditLines(audit), "EVENTS_QUERY_TICKET")).isTrue();
    }

    @Test
    void queryTicketAndOpeningAreMutuallyExclusive() {
        String portal = portalToken(300, ORIGIN, DelegatedTickets.SUBJECT, "ses-1c-both");
        ResponseEntity<JsonNode> denied = postJson("/oauth/token", basic(GATEWAY, GATEWAY_SECRET),
                command(EXCHANGE, metadataCall(), "1c-both", Map.of(
                        "subject_token", portal,
                        "gateway_events_opening", true,
                        "query_ticket", "not-a-ticket")));

        assertThat(denied.getStatusCode()).as("body: %s", denied.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(denied.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
    }

    /** {@code query_ticket} 是 exchange 的判别位，出现在别的 operation 上不能被悄悄忽略。 */
    @Test
    void queryTicketIsOnlyValidOnTheExchangeOperation() {
        ResponseEntity<JsonNode> denied = postJson("/oauth/token", basic(GATEWAY, GATEWAY_SECRET),
                command("client", new ServiceCall(APP, ORIGIN_ACTION,
                        new ResourceBinding("integration-origin", ORIGIN_ID, DIGEST)),
                        "1c-op", Map.of("query_ticket", "not-a-ticket")));

        assertThat(denied.getStatusCode()).as("body: %s", denied.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(denied.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
    }

    /** 一次性消费必须是原子的：两个并发请求只能有一个拿到票。 */
    @Test
    void twoConcurrentQueryTicketExchangesMintOnlyOneTicket() throws Exception {
        String portal = portalToken(300, ORIGIN, DelegatedTickets.SUBJECT, "ses-1c-race");
        JsonNode opening = open(portal, "1c-open-race");
        String queryTicket = opening.get("ticket").asText();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<ResponseEntity<JsonNode>> attempt = () -> {
            barrier.await(10, TimeUnit.SECONDS);
            return exchangeQuery(portal, queryTicket, "1c-race");
        };
        try {
            Future<ResponseEntity<JsonNode>> first = pool.submit(attempt);
            Future<ResponseEntity<JsonNode>> second = pool.submit(attempt);
            List<ResponseEntity<JsonNode>> responses = List.of(
                    first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));

            assertThat(responses).filteredOn(r -> r.getStatusCode() == HttpStatus.OK).hasSize(1);
            assertThat(responses).filteredOn(r -> r.getStatusCode() == HttpStatus.FORBIDDEN)
                    .singleElement()
                    .satisfies(r -> assertThat(r.getBody().get("code").asText()).isEqualTo("SYS-AUTH-403"));
        } finally {
            pool.shutdownNow();
        }
    }
}
