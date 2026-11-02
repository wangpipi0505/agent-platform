package cn.agentplatform.common.authority;

import cn.agentplatform.authority.AuthorityApplication;
import cn.agentplatform.authority.AuthorityTestSupport;
import cn.agentplatform.authority.DelegatedTestSupport;
import cn.agentplatform.authority.TestJwt;
import cn.agentplatform.authority.decision.DecisionStore;
import cn.agentplatform.common.Json;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-44 切片 1b 的服务端契约：{@code operation=exchange} 的变体 A（{@code gateway_events_opening:true}）。
 *
 * <p>为什么变体 A 不能只是"普通 exchange 加个被忽略的布尔位"：客户端
 * {@code beginGatewayPortalEvents}（{@code HttpCurrentAuthorityClient:574-602}）发出的 call 是
 * {@code engine / run.observation.metadata.read / run-observation-selector-v1}，随后在 :593 用
 * {@code requirePurpose(...,"gateway","delegated",PORTAL,PORTAL)} 复核这张票的用途、在 :594 用
 * {@code origin(ticket.claims())} 复核 {@code origin_authentication_id}。所以服务端必须在签票前就把
 * "这是一张观测 opening 票"钉住：(1) 目的只能是 metadata 选择器；(2) portal 令牌必须带 origin，
 * 否则签出的票必然在客户端被 403，属于"发一张废票"。
 *
 * <p>本切片不实现 1c/1d：不新增端点，不实现 {@code portal_gateway_events_open}，不实现一次性消费，
 * 也不往 Decision 里加 {@code purpose} 等 §4.2 字段——S0 的 {@code purpose=EVENTS_OPENING} 记录
 * 需要与 S3 的消费点一起定义（§4.3），提前加字段只会制造半个不变量。
 *
 * <p>为什么正例要用真实客户端（第 1 条）：{@code beginGatewayPortalEvents} 内部有 30+ 条逐字段校验
 * （call 全等、request_sha256、operation 结构、ceiling、grant 夹取、5 秒窗），任何一条服务端不一致
 * 都会抛异常；"不抛异常"本身就是最严的契约断言（设计 §7.1）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = AuthorityApplication.class,
        properties = {
                AuthorityTestSupport.P_ISSUER,
                AuthorityTestSupport.P_KID,
                AuthorityTestSupport.P_KEY,
                AuthorityTestSupport.P_GATEWAY,
                DelegatedTestSupport.P_ENGINE,
                DelegatedTestSupport.P_DELEGATION_ON})
class GatewayEventsSlice1bContractTest extends DelegatedTestSupport {

    private static final String EXCHANGE = "exchange";
    private static final String PORTAL_AUDIENCE = "agent-platform-portal";
    private static final String ENGINE = "engine";
    /** 客户端 {@code RunObservationSelectors.metadata} 只接受 {@code r_<32hex>}。 */
    private static final String RUN = "r_0123456789abcdef0123456789abcdef";
    /** 目录里真实存在的 SSE 订阅行（gateway → engine，delegated），用来证明"过了目录行仍要被目的校验拦住"。 */
    private static final String EVENTS_ACTION = "proxy.engine.get.RunController.events";
    /** {@code "oa1:" + 32 hex}；36 字符，客户端 {@code origin()} 的上限是 128。 */
    private static final String ORIGIN = "oa1:0123456789abcdef0123456789abcdef";

    @Autowired
    private DecisionStore decisions;

    /**
     * 正例：真实客户端跑完 S0。服务端必须签出客户端能整体验过的票，并把 origin 带进票面/断言。
     */
    @Test
    void beginGatewayPortalEventsCompletesTheOpeningExchange() {
        String portal = portalToken(300, ORIGIN);
        long portalExpiresAt = TestJwt.claims(portal).get("exp").asLong();

        String ticketValue;
        Instant ticketExpiresAt;
        try (HttpCurrentAuthorityClient gateway = gatewayClient()) {
            HttpCurrentAuthorityClient.GatewayEventsOpening opening =
                    gateway.beginGatewayPortalEvents(portal, RUN, 7);
            // 5 秒硬窗内取凭据；close() 之后 proof 会失效，所以值必须在 try 内取出。
            ticketValue = opening.queryAuthorization().ticket().value();
            ticketExpiresAt = opening.queryAuthorization().ticket().expiresAt();
            assertThat(opening.publicRunId()).isEqualTo(RUN);
            assertThat(opening.effectiveCursor()).isEqualTo(7);
        }

        JsonNode claims = verified(ticketValue, "at+jwt");
        assertThat(claims.get("authority_mode").asText()).isEqualTo("delegated");
        assertThat(claims.get("caller").asText()).isEqualTo(GATEWAY);
        assertThat(claims.get("client_id").asText()).isEqualTo(GATEWAY);
        assertThat(claims.get("target").asText()).isEqualTo(ENGINE);
        assertThat(claims.get("action").asText()).isEqualTo(RunObservationSelectors.ACTION);
        assertThat(claims.get("aud").asText()).isEqualTo("urn:agent-platform:service:" + ENGINE);
        assertThat(claims.get("tenant_id").asText()).isEqualTo(DelegatedTickets.TENANT_ID);
        assertThat(claims.get("sub").asText()).isEqualTo(DelegatedTickets.SUBJECT);
        // 1b 的硬要求：opening 路径也必须把会话端派生的 origin 透传到票面。
        assertThat(claims.get("origin_authentication_id").asText()).isEqualTo(ORIGIN);
        assertThat(claims.get("grant_ref").asText()).isNotBlank();
        assertThat(claims.get("authorization_version").asText())
                .isEqualTo(DelegatedTickets.AUTHORIZATION_VERSION);

        JsonNode operation = claims.get("operation");
        assertThat(operation.get("source_kind").asText()).isEqualTo("PORTAL");
        assertThat(operation.get("origin_kind").asText()).isEqualTo("PORTAL");
        assertThat(operation.get("resource").get("kind").asText())
                .isEqualTo(RunObservationSelectors.KIND);
        assertThat(operation.get("resource").get("id").asText()).isEqualTo(metadataCall().resource().id());
        // 时间夹取（§4.5）：op 到期 ≤ portal exp，票 exp ≤ op 到期。
        assertThat(operation.get("expires_at").asLong()).isLessThanOrEqualTo(portalExpiresAt);
        assertThat(claims.get("exp").asLong()).isLessThanOrEqualTo(operation.get("expires_at").asLong());
        assertThat(ticketExpiresAt)
                .isEqualTo(Instant.ofEpochSecond(operation.get("expires_at").asLong()));
    }

    /**
     * 请求形状与实参：字段名/取值必须与 {@code beginGatewayPortalEvents} 的 {@code query(...)} 完全一致，
     * 否则客户端 :472 的 {@code request_sha256} 校验会失败。响应的 {@code ticket} 与断言 claim 里的
     * {@code ticket} 必须是同一个 JWS（1a 的既有契约）。
     */
    @Test
    void openingExchangeAnswersTheExactClientCallShape() {
        ServiceCall call = metadataCall();
        String portal = portalToken(300, ORIGIN);
        ListAppender<ILoggingEvent> audit = attachAudit();

        JsonNode exchanged = exchange(call, "slice1b-shape", portal, true);

        assertThat(exchanged.size()).isEqualTo(2);
        assertThat(exchanged.has("assertion")).isTrue();
        assertThat(exchanged.has("ticket")).isTrue();
        JsonNode ticket = verified(exchanged.get("ticket").asText(), "at+jwt");
        JsonNode assertion = verified(exchanged.get("assertion").asText(), "agent-authority-decision+jwt");

        assertThat(assertion.get("ticket").asText()).isEqualTo(exchanged.get("ticket").asText());
        assertThat(assertion.get("ticket_jti").asText()).isEqualTo(ticket.get("jti").asText());
        assertThat(assertion.get("origin_authentication_id").asText()).isEqualTo(ORIGIN);
        assertThat(ticket.get("origin_authentication_id").asText()).isEqualTo(ORIGIN);
        // 断言与票描述同一次签发：decision_id 相等，operation 也相等（客户端 :435 会整棵复核 operation）。
        assertThat(assertion.get("decision_id").asText()).isEqualTo(ticket.get("decision_id").asText());
        assertThat(ticket.get("operation")).isEqualTo(assertion.get("operation"));
        // opening 走的是同一个 fromSubject 决策登记：decision 上的 origin/ticket_jti 必须可查。
        DecisionStore.Decision decision = decisions.find(assertion.get("decision_id").asText()).orElseThrow();
        assertThat(decision.originAuthenticationId()).isEqualTo(ORIGIN);
        assertThat(decision.ticketJti()).isEqualTo(ticket.get("jti").asText());

        List<String> lines = auditLines(audit);
        assertThat(lines).anyMatch(line -> line.contains("operation=exchange") && line.contains("outcome=permit"));
        assertThat(lines).noneMatch(line -> line.contains("eyJ"));
    }

    /**
     * 变体 A 的目的校验：目录里有 delegated 行的 {@code proxy.engine.get.RunController.events}
     * 也不能拿 opening 票——它必须是 metadata 选择器（等价于客户端 {@code requirePurpose}）。
     */
    @Test
    void openingRejectsATicketThatIsNotTheMetadataPurpose() {
        ServiceCall eventsRow = new ServiceCall(ENGINE, EVENTS_ACTION,
                new ResourceBinding("run-snapshot", RUN, DIGEST));
        ListAppender<ILoggingEvent> audit = attachAudit();

        ResponseEntity<JsonNode> denied = exchangeRaw(eventsRow, "slice1b-purpose",
                portalToken(300, ORIGIN), true);

        assertThat(denied.getStatusCode()).as("body: %s", denied.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody().get("code").asText()).isEqualTo("SYS-AUTH-403");
        assertThat(auditLines(audit)).anyMatch(line -> line.contains("operation=exchange")
                && line.contains("reason=EVENTS_OPENING_PURPOSE"));
    }

    /**
     * 目的校验的第二段：action/target 对但 resource.kind 不是 selector（目录行只按
     * caller/target/action/mode 授权，resource 由服务端结构校验兜底）。
     */
    @Test
    void openingRejectsAResourceThatIsNotTheMetadataSelector() {
        ServiceCall wrongResource = new ServiceCall(ENGINE, RunObservationSelectors.ACTION,
                new ResourceBinding("run-snapshot", RUN, DIGEST));
        ListAppender<ILoggingEvent> audit = attachAudit();

        ResponseEntity<JsonNode> denied = exchangeRaw(wrongResource, "slice1b-resource",
                portalToken(300, ORIGIN), true);

        assertThat(denied.getStatusCode()).as("body: %s", denied.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(auditLines(audit)).anyMatch(line -> line.contains("operation=exchange")
                && line.contains("reason=EVENTS_OPENING_PURPOSE"));
    }

    /** 判别位只认布尔 true：字符串 "true" 是拼错的请求，静默忽略会把它降级成普通 exchange。 */
    @Test
    void openingRejectsANonBooleanFlag() {
        ResponseEntity<JsonNode> denied = postJson("/oauth/token", basic(GATEWAY, GATEWAY_SECRET),
                command(EXCHANGE, metadataCall(), "slice1b-flag", Map.of(
                        "subject_token", portalToken(300, ORIGIN),
                        "gateway_events_opening", "true")));

        assertThat(denied.getStatusCode()).as("body: %s", denied.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(denied.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
    }

    /**
     * origin 是 S1/S2/S3 的唯一关联键（客户端 :594 缺即 403）。会话端没写 origin 时这里拒绝，
     * 避免签出一张"服务端 200、客户端必失败"的票。
     */
    @Test
    void openingRejectsAPortalTokenWithoutOrigin() {
        ListAppender<ILoggingEvent> audit = attachAudit();

        ResponseEntity<JsonNode> denied = exchangeRaw(metadataCall(), "slice1b-origin",
                portalToken(300, null), true);

        assertThat(denied.getStatusCode()).as("body: %s", denied.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody().get("code").asText()).isEqualTo("SYS-AUTH-403");
        assertThat(auditLines(audit)).anyMatch(line -> line.contains("operation=exchange")
                && line.contains("reason=EVENTS_ORIGIN"));
    }

    /** 变体 A 只是 exchange 的判别位；出现在别的 operation 上说明请求拼错，不能悄悄忽略。 */
    @Test
    void openingIsOnlyValidOnTheExchangeOperation() {
        ServiceCall workload = new ServiceCall(APP, ORIGIN_ACTION,
                new ResourceBinding("integration-origin", ORIGIN_ID, DIGEST));

        ResponseEntity<JsonNode> denied = postJson("/oauth/token", basic(GATEWAY, GATEWAY_SECRET),
                command("client", workload, "slice1b-scope", Map.of("gateway_events_opening", true)));

        assertThat(denied.getStatusCode()).as("body: %s", denied.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(denied.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
    }

    /** 判别位取 false 时语义与普通 exchange 完全一致（客户端也从不发 false，但服务端不能因此改变形状）。 */
    @Test
    void openingFlagFalseKeepsThePlainExchange() {
        ServiceCall eventsRow = new ServiceCall(ENGINE, EVENTS_ACTION,
                new ResourceBinding("run-snapshot", RUN, DIGEST));

        JsonNode exchanged = exchange(eventsRow, "slice1b-false", portalToken(300, ORIGIN), false);

        JsonNode ticket = verified(exchanged.get("ticket").asText(), "at+jwt");
        assertThat(ticket.get("action").asText()).isEqualTo(EVENTS_ACTION);
        assertThat(ticket.get("origin_authentication_id").asText()).isEqualTo(ORIGIN);
    }

    private JsonNode exchange(ServiceCall call, String nonce, String subjectToken, boolean opening) {
        ResponseEntity<JsonNode> response = exchangeRaw(call, nonce, subjectToken, opening);
        assertThat(response.getStatusCode()).as("exchange body: %s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<JsonNode> exchangeRaw(ServiceCall call, String nonce, String subjectToken,
                                                 boolean opening) {
        return postJson("/oauth/token", basic(GATEWAY, GATEWAY_SECRET),
                command(EXCHANGE, call, nonce, Map.of(
                        "subject_token", subjectToken,
                        "gateway_events_opening", opening)));
    }

    private JsonNode verified(String token, String type) {
        return TestJwt.verifiedClaims(token, type, publishedKey());
    }

    /** 与客户端 {@code beginGatewayPortalEvents:588} 逐字段同形：engine / metadata / selector 资源。 */
    private static ServiceCall metadataCall() {
        return new ServiceCall(ENGINE, RunObservationSelectors.ACTION,
                RunObservationSelectors.metadata(
                        new SubjectKey(DelegatedTickets.TENANT_ID, DelegatedTickets.SUBJECT), RUN));
    }

    /**
     * 请求体字段集合必须与客户端 {@code query(...)} 一致（fields + operation + target/action/resource + nonce）；
     * 多一个或少一个都会让客户端 :472 的 request_sha256 对不上。
     */
    private static String command(String operation, ServiceCall call, String nonce, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operation", operation);
        body.put("target", call.target());
        body.put("action", call.action());
        body.put("resource", Map.of("kind", call.resource().kind(), "id", call.resource().id(),
                "version_digest", call.resource().versionDigest()));
        body.put("nonce", nonce);
        body.putAll(extra);
        return Json.write(body);
    }

    /** 与会话端点签发的 portal 票同形；origin 只有会话端会写，测试按需补上。 */
    private String portalToken(long ttlSeconds, String origin) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", ISSUER);
        claims.put("aud", PORTAL_AUDIENCE);
        claims.put("token_use", "user");
        claims.put("jti", "ses-slice1b");
        claims.put("client_id", GATEWAY);
        claims.put("scope", "platform.access");
        claims.put("iat", now);
        claims.put("nbf", now);
        claims.put("exp", now + ttlSeconds);
        claims.putAll(DelegatedTickets.actorClaims());
        if (origin != null) claims.put("origin_authentication_id", origin);
        return keys.sign("at+jwt", claims);
    }

    /** 与 {@code ExchangeContractTest.gatewayClient()} 同一份配置：真实客户端 + 本测试服务器的 JWKS/tokenUri。 */
    private HttpCurrentAuthorityClient gatewayClient() {
        AuthorityProperties config = new AuthorityProperties("iam", GATEWAY,
                new AuthorityProperties.Portal(
                        true,
                        ISSUER,
                        url("/.well-known/jwks.json"),
                        PORTAL_AUDIENCE,
                        GATEWAY,
                        Set.of("EDITOR"),
                        Set.of("region", "dataLabels"),
                        Map.of("region", Set.of("A"), "dataLabels", Set.of("confidential")),
                        Set.of("platform.access")),
                new AuthorityProperties.Authority(
                        ISSUER,
                        url("/.well-known/jwks.json"),
                        url("/oauth/token"),
                        url("/internal/authorize"),
                        GATEWAY,
                        GATEWAY_SECRET_REF,
                        "RS256",
                        "urn:agent-platform:service:" + GATEWAY,
                        60,
                        5,
                        1000,
                        2000),
                null, null, null, false, null);
        WorkloadCredentialResolver credentials = new WorkloadCredentialResolver(config, ref -> GATEWAY_SECRET, true);
        return new HttpCurrentAuthorityClient(config, Clock.systemUTC(), credentials, true);
    }
}
