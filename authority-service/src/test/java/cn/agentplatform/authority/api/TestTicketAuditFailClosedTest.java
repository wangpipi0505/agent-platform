package cn.agentplatform.authority.api;

import cn.agentplatform.authority.AuthorityException;
import cn.agentplatform.authority.TestJwt;
import cn.agentplatform.authority.config.AuthorityServiceProperties;
import cn.agentplatform.authority.identity.IdentityProperties;
import cn.agentplatform.authority.sign.KeyRing;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R-26 审计 fail-closed（§9.2："审计失败即拒签"）单元测试：审计写入抛错时签发必须失败且
 * 不返回票；正常路径下审计行先于响应组装、字段与票面一致。
 * <p>本类与被测类同包，以便使用包私有的 {@code AuditWriter} 注入点；密钥用 classpath 测试 PEM，
 * 与 {@code AuthorityTestSupport} 同款。
 */
class TestTicketAuditFailClosedTest {

    private static final String ISSUER = "https://authority.test";
    private static final String AUDIENCE = "agent-platform-portal";
    private static final String CLIENT = "gateway";

    private static final String BODY = "{\"tenantId\":\"demo\",\"subject\":\"tester-1\","
            + "\"roles\":[\"USER\"],\"attributes\":{\"region\":\"A\",\"dataLabels\":[],"
            + "\"bizSystemCode\":\"BIZ-1\",\"userId\":\"tester-1\"},\"ttlSeconds\":300}";

    private static KeyRing keyRing() {
        AuthorityServiceProperties properties = new AuthorityServiceProperties(
                ISSUER, "classpath:authority-test-key.pem", "test-2026-10",
                null, null, 60, 5, 55, Map.of());
        return new KeyRing(properties, Clock.systemUTC());
    }

    private static IdentityProperties identityProfile() {
        // enabled=false：测试票端点刻意不依赖身份源（user-mgmt），只要求档案三项在场。
        return new IdentityProperties(false, null, null, "/system/apikeys/identity/self",
                "local-v12", ISSUER, AUDIENCE, CLIENT, "platform.access",
                "agent-platform", 1000, 1000, 3600, null, "USER_MGMT", null, "0", null);
    }

    private static TestTicketController controller(TestTicketController.AuditWriter writer) {
        return new TestTicketController(keyRing(), identityProfile(), Clock.systemUTC(), writer);
    }

    @Test
    void auditWriteFailureRefusesToReturnATicket() {
        List<String> captured = new ArrayList<>();
        TestTicketController controller = controller(line -> {
            captured.add(line);
            throw new IllegalStateException("audit sink down");
        });

        assertThatThrownBy(() -> controller.mint(BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(AuthorityException.class)
                .satisfies(e -> {
                    AuthorityException authority = (AuthorityException) e;
                    assertThat(authority.status()).isEqualTo(503);
                    assertThat(authority.code()).isEqualTo("TEST_TICKET_AUDIT_FAILED");
                });
        assertThat(captured).as("fail-closed：尝试写审计（已抛错）但绝不能有票离开服务").hasSize(1);
    }

    @Test
    void auditLineIsWrittenBeforeResponseAndMatchesTheTicket() {
        List<String> captured = new ArrayList<>();
        KeyRing keys = keyRing();
        TestTicketController controller = new TestTicketController(
                keys, identityProfile(), Clock.systemUTC(), captured::add);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) controller
                .mint(BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(captured).hasSize(1);
        String line = captured.get(0);
        assertThat(line).startsWith("AUDIT TEST_TICKET_MINTED");
        assertThat(line).contains("subject=tester-1");
        assertThat(line).contains("roles=[USER]");
        assertThat(line).contains("tenant=demo");
        assertThat(line).contains("jti=" + response.get("jti"));
        assertThat(line).contains("issuer=" + ISSUER);

        // 公钥从 KeyRing 自己发布的 JWKS 还原（与 HTTP 测试的 publishedKey() 同构）。
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> jwks =
                (java.util.List<Map<String, Object>>) keys.jwks().get("keys");
        JsonNode claims = TestJwt.verifiedClaims(
                (String) response.get("accessToken"), "at+jwt",
                TestJwt.publicKey(cn.agentplatform.common.Json.tree(jwks.get(0))));
        assertThat(line).contains("iat=" + claims.get("iat").asLong());
        assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isEqualTo(300L);
    }

    @Test
    void missingIdentityProfileFailsClosedWith503() {
        IdentityProperties broken = new IdentityProperties(false, null, null, "/x",
                "t", null, AUDIENCE, CLIENT, "platform.access", "agent-platform",
                1000, 1000, 3600, null, "USER_MGMT", null, "0", null);
        TestTicketController controller = new TestTicketController(keyRing(), broken,
                Clock.systemUTC(), line -> {
                });

        assertThatThrownBy(() -> controller.mint(BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(AuthorityException.class)
                .satisfies(e -> {
                    AuthorityException authority = (AuthorityException) e;
                    assertThat(authority.status()).isEqualTo(503);
                    assertThat(authority.code()).isEqualTo("TEST_TICKET_PROFILE_MISSING");
                });
    }
}
