package cn.agentplatform.authority;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.agentplatform.common.authority.TrustedJwtDecoder;
import cn.agentplatform.common.authority.AuthorityProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-26 {@code POST /test/tickets} 契约测试（09 细则 v1.2 §9.2）：
 * 正例断言票面与生产用户票（{@code SessionService} portal 令牌）同 issuer/aud/client_id/scope、
 * 同 {@code KeyRing} 同 kid；反例逐条钉 400（SERVICE/未知角色/白名单外租户/7200/未知字段/归属键非单值）。
 * 开关关闭与非 test profile 的 404 分别在 {@link TestTicketDisabledTest} 与 {@link TestTicketProfileGateTest}。
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                AuthorityTestSupport.P_ISSUER,
                AuthorityTestSupport.P_KID,
                AuthorityTestSupport.P_KEY,
                AuthorityTestSupport.P_GATEWAY,
                AuthorityTestSupport.P_APP})
class TestTicketEndpointTest extends AuthorityTestSupport {

    private static final String AUDIENCE = "agent-platform-portal";
    private static final String USER_BODY = "{\"tenantId\":\"demo\",\"subject\":\"tester-1\","
            + "\"roles\":[\"USER\"],\"attributes\":{\"region\":[\"A\"],\"dataLabels\":[\"public\"],"
            + "\"bizSystemCode\":\"BIZ-1\",\"userId\":\"tester-1\"},\"ttlSeconds\":600}";

    private final ListAppender<ILoggingEvent> auditEvents = new ListAppender<>();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // @ActiveProfiles 已钉 test；system property 与 SessionEndpointTest 同款双保险。
        System.setProperty("spring.profiles.active", "test");
        registry.add("agent.authority.test-ticket.enabled", () -> "true");
        registry.add("agent.authority.identity.issuer", () -> ISSUER);
        registry.add("agent.authority.identity.audience", () -> AUDIENCE);
        registry.add("agent.authority.identity.client-id", () -> GATEWAY);
        registry.add("agent.authority.identity.scope", () -> "platform.access");
    }

    @BeforeEach
    void attachAuditCapture() {
        auditEvents.start();
        ((Logger) LoggerFactory.getLogger("agent.authority.audit")).addAppender(auditEvents);
    }

    @AfterEach
    void detachAuditCapture() {
        ((Logger) LoggerFactory.getLogger("agent.authority.audit")).detachAppender(auditEvents);
        auditEvents.list.clear();
        auditEvents.stop();
    }

    /** 正例：契约字段逐一断言（含 kid 与 service 票同源、jti 响应体与票面一致）。 */
    @Test
    void mintsUserTicketWithProductionShapedClaims() {
        ResponseEntity<JsonNode> response = postJson("/test/tickets", null, USER_BODY);

        assertThat(response.getStatusCode()).as("body: %s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(body.get("expiresIn").asInt()).isEqualTo(600);
        String accessToken = body.get("accessToken").asText();
        assertThat(accessToken).isNotBlank();

        // 同 kid：与 JWKS 里唯一的 active key 一致（生产票同款 KeyRing 签发）。
        String kid = TestJwt.header(accessToken).get("kid").asText();
        assertThat(kid).isEqualTo(jwks().get("keys").get(0).get("kid").asText());

        JsonNode claims = TestJwt.verifiedClaims(accessToken, "at+jwt", publishedKey());
        assertThat(claims.get("iss").asText()).isEqualTo(ISSUER);
        assertThat(claims.get("aud").get(0).asText()).isEqualTo(AUDIENCE);
        assertThat(claims.get("client_id").asText()).isEqualTo(GATEWAY);
        assertThat(claims.get("token_use").asText()).isEqualTo("user");
        assertThat(claims.get("tenant_id").asText()).isEqualTo("demo");
        assertThat(claims.get("sub").asText()).isEqualTo("tester-1");
        assertThat(claims.get("roles")).isEqualTo(JsonArrayOf("USER"));
        // 签名面只带 region/dataLabels（归属键钉扎口径，见 TestTicketController 类注释）：
        // 请求四键收下并校验，票面两键——与 LoopbackAuthority.portal / 网关默认白名单同形状。
        assertThat(claims.get("attributes").get("region").get(0).asText()).isEqualTo("A");
        assertThat(claims.get("attributes").get("dataLabels").get(0).asText()).isEqualTo("public");
        assertThat(claims.get("attributes")).isNotNull();
        assertThat(claims.get("attributes").size()).isEqualTo(2);
        assertThat(claims.get("scope").asText()).isEqualTo("platform.access");
        assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isEqualTo(600L);
        assertThat(claims.get("nbf").asLong()).isEqualTo(claims.get("iat").asLong());
        assertThat(claims.get("origin_authentication_id").asText()).startsWith("oa1:");
        assertThat(claims.get("jti").asText()).isEqualTo(body.get("jti").asText());
        assertThat(body.get("jti").asText()).startsWith("tt-");
    }

    /** t-* 前缀租户放行；角色重复去重保序；ttlSeconds 缺省=3600（契约上界）。 */
    @Test
    void acceptsTPrefixTenantDedupesRolesAndDefaultsTtl() {
        String body = "{\"tenantId\":\"t-qa-1\",\"subject\":\"tester-2\","
                + "\"roles\":[\"USER\",\"USER\",\"BUILDER\"],"
                + "\"attributes\":{\"region\":\"A\",\"dataLabels\":[],"
                + "\"bizSystemCode\":\"BIZ-1\",\"userId\":\"tester-2\"}}";
        ResponseEntity<JsonNode> response = postJson("/test/tickets", null, body);
        assertThat(response.getStatusCode()).as("body: %s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        JsonNode claims = TestJwt.verifiedClaims(
                response.getBody().get("accessToken").asText(), "at+jwt", publishedKey());
        assertThat(claims.get("tenant_id").asText()).isEqualTo("t-qa-1");
        assertThat(claims.get("roles")).isEqualTo(JsonArrayOf("USER", "BUILDER"));
        assertThat(response.getBody().get("expiresIn").asInt()).isEqualTo(3600);
    }

    /** ADMIN/BUILDER/OPERATOR 全部可签（测试需要钉守门行时的真实角色）。 */
    @Test
    void mintsEveryAllowedRole() {
        for (String role : List.of("ADMIN", "BUILDER", "OPERATOR")) {
            String body = "{\"tenantId\":\"demo\",\"subject\":\"r-" + role + "\",\"roles\":[\"" + role
                    + "\"],\"attributes\":{\"region\":\"A\",\"dataLabels\":[],"
                    + "\"bizSystemCode\":\"B\",\"userId\":\"r\"}}";
            ResponseEntity<JsonNode> response = postJson("/test/tickets", null, body);
            assertThat(response.getStatusCode()).as("role=" + role).isEqualTo(HttpStatus.OK);
            JsonNode claims = TestJwt.verifiedClaims(
                    response.getBody().get("accessToken").asText(), "at+jwt", publishedKey());
            assertThat(claims.get("roles")).isEqualTo(JsonArrayOf(role));
        }
    }

    // ---- 反例（§9.2）：一律 400 INVALID_REQUEST，不得给票。 ----

    @Test
    void rejectsServiceRole() {
        assertThat(postJson("/test/tickets", null, bodyWithRoles("[\"USER\",\"SERVICE\"]"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // 文案必须说明 SERVICE 走 client_credentials，避免测试误以为可以重试。
        assertThat(postJson("/test/tickets", null, bodyWithRoles("[\"SERVICE\"]")).getBody()
                .get("message").asText()).contains("client_credentials");
        assertThat(postJson("/test/tickets", null, bodyWithRoles("[\"GATEWAY_AUTH\"]"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsUnknownRole() {
        ResponseEntity<JsonNode> response = postJson("/test/tickets", null, bodyWithRoles("[\"SUDO\"]"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("message").asText()).contains("SUDO");
    }

    @Test
    void rejectsEmptyAndNonArrayRoles() {
        assertThat(postJson("/test/tickets", null, bodyWithRoles("[]"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postJson("/test/tickets", null, bodyWithRoles("\"USER\""))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsTenantOutsideWhitelist() {
        assertThat(postJson("/test/tickets", null, bodyWithTenant("prod-x"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // "t-" 裸前缀（空尾巴）不是合法测试租户。
        assertThat(postJson("/test/tickets", null, bodyWithTenant("t-"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postJson("/test/tickets", null, bodyWithTenant(""))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postJson("/test/tickets", null,
                "{\"subject\":\"s\",\"roles\":[\"USER\"],\"attributes\":{\"region\":\"A\","
                        + "\"dataLabels\":[],\"bizSystemCode\":\"B\",\"userId\":\"u\"}}")
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsTtlOverContractBudget() {
        // 契约反例：7200 一律 400。
        assertThat(postJson("/test/tickets", null, bodyWithTtl("7200"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postJson("/test/tickets", null, bodyWithTtl("0"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postJson("/test/tickets", null, bodyWithTtl("3601"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postJson("/test/tickets", null, bodyWithTtl("\"600\""))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsStrictAttributesShape() {
        // 归属键必须恰一值（平台 ClaimCeiling 钉扎口径）。
        assertThat(postJson("/test/tickets", null, bodyWithAttributes(
                "{\"region\":\"A\",\"dataLabels\":[],\"bizSystemCode\":[\"B\",\"C\"],\"userId\":\"u\"}"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // 白名单外的键（deptId 等）必须显式拒绝，不能被悄悄丢掉。
        assertThat(postJson("/test/tickets", null, bodyWithAttributes(
                "{\"region\":\"A\",\"dataLabels\":[],\"bizSystemCode\":\"B\",\"userId\":\"u\",\"deptId\":[\"1\"]}"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // 缺键同样 400：契约请求形状是四键齐全。
        assertThat(postJson("/test/tickets", null, bodyWithAttributes(
                "{\"region\":\"A\",\"dataLabels\":[],\"bizSystemCode\":\"B\"}"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postJson("/test/tickets", null, bodyWithAttributes(
                "{\"region\":123,\"dataLabels\":[],\"bizSystemCode\":\"B\",\"userId\":\"u\"}"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsUnknownFieldsBlankSubjectAndBadBody() {
        assertThat(postJson("/test/tickets", null, "{\"tenantId\":\"demo\",\"subject\":\"s\","
                + "\"roles\":[\"USER\"],\"attributes\":{\"region\":\"A\",\"dataLabels\":[],"
                + "\"bizSystemCode\":\"B\",\"userId\":\"u\"},\"userId\":\"inject\"}")
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postJson("/test/tickets", null, bodyWithSubject("   "))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postJson("/test/tickets", null, "not-json")
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postJson("/test/tickets", null, "")
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** 审计行（§9.2）：每次签发一条 AUDIT TEST_TICKET_MINTED，字段与票面一致；拒绝不写签发行。 */
    @Test
    void writesAuditLineForEveryMintAndNotForRejections() {
        int before = auditEvents.list.size();
        ResponseEntity<JsonNode> ok = postJson("/test/tickets", null, USER_BODY);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(auditEvents.list.size()).isEqualTo(before + 1);
        ILoggingEvent event = auditEvents.list.get(auditEvents.list.size() - 1);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        String line = event.getFormattedMessage();
        assertThat(line).startsWith("AUDIT TEST_TICKET_MINTED");
        assertThat(line).contains("subject=tester-1");
        assertThat(line).contains("roles=[USER]");
        assertThat(line).contains("tenant=demo");
        assertThat(line).contains("jti=" + ok.getBody().get("jti").asText());
        assertThat(line).contains("issuer=" + ISSUER);
        assertThat(line).contains("iat=");

        postJson("/test/tickets", null, bodyWithRoles("[\"SERVICE\"]"));
        assertThat(auditEvents.list.size()).as("拒签不写 TEST_TICKET_MINTED")
                .isEqualTo(before + 1);
    }

    /** 独立证据：同一张票能被平台真实验签组件通过（完整断言见 common.authority 闭环测试）。 */
    @Test
    void platformDecoderConfigurationAcceptsTheMintedTicket() {
        ResponseEntity<JsonNode> ok = postJson("/test/tickets", null, USER_BODY);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        try (TrustedJwtDecoder decoder = new TrustedJwtDecoder(
                portalConfig("ADMIN", "BUILDER", "OPERATOR", "USER"), Clock.systemUTC(), true)) {
            var jwt = decoder.decode(ok.getBody().get("accessToken").asText());
            assertThat(jwt.getClaimAsString("iss")).isEqualTo(ISSUER);
            assertThat(jwt.getAudience()).isEqualTo(List.of(AUDIENCE));
            assertThat(jwt.getSubject()).isEqualTo("tester-1");
            assertThat(jwt.getClaimAsString("tenant_id")).isEqualTo("demo");
            assertThat(jwt.getClaimAsStringList("roles")).contains("USER");
            assertThat(jwt.getClaimAsString("scope")).isEqualTo("platform.access");
        }
    }

    /**
     * 平台侧 portal 域配置：issuer 用测试签发 issuer（与票面 iss 逐字相等），jwks/token/decision 指向
     * 本测试进程的真实 HTTP 端口（issuer 与 jwks 同 authority，与 E2 部署同一形状）。
     * 属性白名单两键（region/dataLabels）= E2 overlay 口径 = 网关 application.yml 默认键集。
     */
    AuthorityProperties portalConfig(String... roles) {
        return new AuthorityProperties("iam", "gateway",
                new AuthorityProperties.Portal(true, ISSUER, url("/.well-known/jwks.json"),
                        AUDIENCE, GATEWAY,
                        roles.length == 0 ? java.util.Set.of("USER") : java.util.Set.of(roles),
                        java.util.Set.of("region", "dataLabels"),
                        java.util.Map.of("region", java.util.Set.of("A", "B"),
                                "dataLabels", java.util.Set.of("public", "internal")),
                        java.util.Set.of("platform.access")),
                new AuthorityProperties.Authority(ISSUER, url("/.well-known/jwks.json"),
                        url("/oauth/token"), url("/internal/authorize"), GATEWAY,
                        "env:AGENT_SECRET_IAM_GATEWAY_CLIENT", "RS256",
                        "urn:agent-platform:service:gateway", 60, 5, 1000, 2000),
                null, null, null, false, null);
    }

    private static com.fasterxml.jackson.databind.JsonNode JsonArrayOf(String... values) {
        return cn.agentplatform.common.Json.mapper().valueToTree(values);
    }

    private static String bodyWithRoles(String roles) {
        return "{\"tenantId\":\"demo\",\"subject\":\"tester-1\",\"roles\":" + roles
                + ",\"attributes\":{\"region\":\"A\",\"dataLabels\":[],\"bizSystemCode\":\"B\",\"userId\":\"u\"}}";
    }

    private static String bodyWithTenant(String tenantId) {
        return "{\"tenantId\":\"" + tenantId + "\",\"subject\":\"tester-1\",\"roles\":[\"USER\"],"
                + "\"attributes\":{\"region\":\"A\",\"dataLabels\":[],\"bizSystemCode\":\"B\",\"userId\":\"u\"}}";
    }

    private static String bodyWithSubject(String subject) {
        return "{\"tenantId\":\"demo\",\"subject\":\"" + subject + "\",\"roles\":[\"USER\"],"
                + "\"attributes\":{\"region\":\"A\",\"dataLabels\":[],\"bizSystemCode\":\"B\",\"userId\":\"u\"}}";
    }

    private static String bodyWithTtl(String ttl) {
        return "{\"tenantId\":\"demo\",\"subject\":\"tester-1\",\"roles\":[\"USER\"],\"ttlSeconds\":" + ttl
                + ",\"attributes\":{\"region\":\"A\",\"dataLabels\":[],\"bizSystemCode\":\"B\",\"userId\":\"u\"}}";
    }

    private static String bodyWithAttributes(String attributes) {
        return "{\"tenantId\":\"demo\",\"subject\":\"tester-1\",\"roles\":[\"USER\"],\"attributes\":"
                + attributes + "}";
    }
}
