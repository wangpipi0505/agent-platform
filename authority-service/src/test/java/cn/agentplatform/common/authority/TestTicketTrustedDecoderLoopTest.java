package cn.agentplatform.common.authority;

import cn.agentplatform.authority.AuthorityTestSupport;
import cn.agentplatform.authority.TestJwt;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.JOSEObjectType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R-26 DoD-2 平台验签闭环（本机）：R-26 端点签出的 USER 票必须能被<b>平台生产验签组件</b>
 * {@link TrustedJwtDecoder}（platform-gateway 的 @Primary JwtDecoder，GatewayIamPortalHttpTest 同款
 * 配置形状：portal 域 issuer/jwks 指向 authority）验签通过——issuer/kid/aud 三者一致是最终判据。
 *
 * <p>负向对照：过期票拒（SEC-07 反例"过期票 401"的解码层等价）；异钥伪造票（同 kid 换私钥）拒；
 * HS256 伪造票拒。三者的部署级表现都是网关 401，本类在解码层钉住根因。
 *
 * <p>本类在 {@code common.authority} 包：与 {@code RealClientContractTest} 同一惯例（真实消费方类
 * 在 admin-common 主代码里，测试进程直接装配）。
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = cn.agentplatform.authority.AuthorityApplication.class,
        properties = {
                AuthorityTestSupport.P_ISSUER,
                AuthorityTestSupport.P_KID,
                AuthorityTestSupport.P_KEY,
                AuthorityTestSupport.P_GATEWAY,
                AuthorityTestSupport.P_APP})
class TestTicketTrustedDecoderLoopTest extends AuthorityTestSupport {

    private static final String AUDIENCE = "agent-platform-portal";
    private static final String USER_BODY = "{\"tenantId\":\"demo\",\"subject\":\"tester-1\","
            + "\"roles\":[\"USER\"],\"attributes\":{\"region\":[\"A\"],\"dataLabels\":[\"public\"],"
            + "\"bizSystemCode\":\"BIZ-1\",\"userId\":\"tester-1\"},\"ttlSeconds\":600}";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        System.setProperty("spring.profiles.active", "test");
        registry.add("agent.authority.test-ticket.enabled", () -> "true");
        registry.add("agent.authority.identity.issuer", () -> ISSUER);
        registry.add("agent.authority.identity.audience", () -> AUDIENCE);
        registry.add("agent.authority.identity.client-id", () -> GATEWAY);
        registry.add("agent.authority.identity.scope", () -> "platform.access");
    }

    /** 可推进的时钟：解码器与网关生产装配一样从注入的 Clock 读"现在"，推进即过期。 */
    static final class MutableClock extends Clock {
        private volatile Instant now = Instant.now();

        void advance(Duration elapsed) {
            now = now.plus(elapsed);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();

    /**
     * 正例闭环：本服务签出的 USER 票经平台 TrustedJwtDecoder（portal 域 issuer/jwks 指向本服务）
     * 验签通过，claims 经 PortalClaimMapper 映射为平台 Actor 视图。
     */
    @Test
    void mintedUserTicketPassesThePlatformTrustedDecoder() {
        JsonNode minted = mint(USER_BODY);

        // E2 overlay 口径：portal 白名单两键（region/dataLabels），归属键不参与钉扎。
        AuthorityProperties config = e2ShapedPortalConfig();
        try (TrustedJwtDecoder decoder = new TrustedJwtDecoder(config, clock, true)) {
            Jwt jwt = decoder.decode(minted.get("accessToken").asText());

            // issuer/kid/aud 三者一致（DoD-2 判据）。
            assertThat(jwt.getClaimAsString("iss")).isEqualTo(ISSUER);
            assertThat(jwt.getHeaders().get("kid")).isEqualTo(TestJwt.header(
                    minted.get("accessToken").asText()).get("kid").asText());
            assertThat(jwt.getAudience()).isEqualTo(List.of(AUDIENCE));
            assertThat(jwt.getSubject()).isEqualTo("tester-1");
            assertThat(jwt.getClaimAsString("tenant_id")).isEqualTo("demo");
            assertThat(jwt.getClaimAsString("token_use")).isEqualTo("user");
            assertThat(jwt.getClaimAsString("client_id")).isEqualTo(GATEWAY);
            // PortalClaimMapper 映射结果：roles 保留 USER；region 取交集；dataLabels 取并集（义务累积）。
            assertThat(jwt.getClaimAsStringList("roles")).containsExactly("USER");
            @SuppressWarnings("unchecked")
            Map<String, Object> attributes = (Map<String, Object>) jwt.getClaims().get("attributes");
            assertThat(attributes.get("region")).isEqualTo(java.util.Set.of("A"));
            assertThat(attributes.get("dataLabels")).isEqualTo(java.util.Set.of("public", "internal"));
            assertThat(attributes).doesNotContainKeys("bizSystemCode", "userId");
            assertThat(jwt.getClaimAsString("scope")).isEqualTo("platform.access");
        }
    }

    /**
     * 归属键钉扎口径的反向对照：把网关白名单配成四键（含钉扎的 bizSystemCode/userId）时，
     * 测试票（签名面只有 region/dataLabels）会被 ClaimCeiling 拒——这正是本端点不把归属键签进票面的
     * 原因（userId 随 subject 变化无法与静态白名单共存）。部署侧必须用两键白名单（E2 overlay 口径）。
     */
    @Test
    void ownershipPinnedConfigRejectsTestTicketsByDesign() {
        JsonNode minted = mint(USER_BODY);
        try (TrustedJwtDecoder decoder = new TrustedJwtDecoder(
                pinnedOwnershipPortalConfig(), clock, true)) {
            assertThatThrownBy(() -> decoder.decode(minted.get("accessToken").asText()))
                    .isInstanceOf(cn.agentplatform.common.PlatformException.class)
                    .satisfies(e -> assertThat(((cn.agentplatform.common.PlatformException) e).status())
                            .isEqualTo(403));
        }
    }

    /** 过期票：ttlSeconds=1 + 时钟推进 2s → BadJwtException（部署级表现即网关 401）。 */
    @Test
    void expiredTicketIsRejectedByThePlatformDecoder() {
        JsonNode minted = mint("{\"tenantId\":\"demo\",\"subject\":\"tester-1\",\"roles\":[\"USER\"],"
                + "\"attributes\":{\"region\":\"A\",\"dataLabels\":[],\"bizSystemCode\":\"BIZ-1\","
                + "\"userId\":\"tester-1\"},\"ttlSeconds\":1}");
        clock.advance(Duration.ofSeconds(2));
        try (TrustedJwtDecoder decoder = new TrustedJwtDecoder(e2ShapedPortalConfig(), clock, true)) {
            assertThatThrownBy(() -> decoder.decode(minted.get("accessToken").asText()))
                    .isInstanceOf(BadJwtException.class);
        }
    }

    /** 异钥伪造：同 kid 同 claims 但换了一把私钥签名 → BadJwtException（伪造票 401 的根因）。 */
    @Test
    void forgedTicketSignedByAnotherKeyIsRejected() throws Exception {
        JsonNode minted = mint(USER_BODY);
        String token = minted.get("accessToken").asText();
        JsonNode claims = TestJwt.claims(token);

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, new SecureRandom());
        KeyPair attacker = generator.generateKeyPair();
        RSAKey attackerKey = new RSAKey.Builder((RSAPublicKey) attacker.getPublic())
                .privateKey(attacker.getPrivate())
                .keyID(TestJwt.header(token).get("kid").asText())
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .build();
        Map<String, Object> forgedClaims = cn.agentplatform.common.Json.mapper().convertValue(
                claims, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                });
        JWSObject forged = new JWSObject(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(new JOSEObjectType("at+jwt"))
                        .keyID(TestJwt.header(token).get("kid").asText())
                        .build(),
                new Payload(cn.agentplatform.common.Json.write(forgedClaims)));
        forged.sign(new RSASSASigner(attackerKey.toRSAPrivateKey()));

        try (TrustedJwtDecoder decoder = new TrustedJwtDecoder(e2ShapedPortalConfig(), clock, true)) {
            assertThatThrownBy(() -> decoder.decode(forged.serialize()))
                    .isInstanceOf(BadJwtException.class);
        }
    }

    /** HS256 伪造票（非 RS256 alg）：头部白名单直接拒 → BadJwtException。 */
    @Test
    void hs256ForgedTicketIsRejected() {
        Map<String, Object> claims = new java.util.LinkedHashMap<>();
        claims.put("iss", ISSUER);
        claims.put("aud", List.of(AUDIENCE));
        claims.put("token_use", "user");
        claims.put("client_id", GATEWAY);
        claims.put("sub", "attacker");
        claims.put("tenant_id", "demo");
        claims.put("roles", List.of("ADMIN"));
        claims.put("attributes", Map.of());
        claims.put("scope", "platform.access");
        claims.put("iat", clock.instant().getEpochSecond());
        claims.put("exp", clock.instant().plusSeconds(600).getEpochSecond());
        claims.put("jti", "forged-" + UUID.randomUUID());
        try {
            JWSObject hs256 = new JWSObject(
                    new JWSHeader.Builder(JWSAlgorithm.HS256).type(new JOSEObjectType("at+jwt")).build(),
                    new Payload(cn.agentplatform.common.Json.write(claims)));
            hs256.sign(new MACSigner(new byte[64]));
            try (TrustedJwtDecoder decoder = new TrustedJwtDecoder(e2ShapedPortalConfig(), clock, true)) {
                assertThatThrownBy(() -> decoder.decode(hs256.serialize()))
                        .isInstanceOf(BadJwtException.class);
            }
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode mint(String body) {
        ResponseEntity<JsonNode> response = postJson("/test/tickets", null, body);
        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /**
     * E2 overlay 同形状：portal 白名单两键（region/dataLabels）、issuer 指向 authority 的签发 issuer、
     * jwks 指向同一 authority（测试进程为真实 HTTP 端口）。
     */
    private AuthorityProperties e2ShapedPortalConfig() {
        return new AuthorityProperties("iam", "gateway",
                new AuthorityProperties.Portal(true, ISSUER, url("/.well-known/jwks.json"),
                        AUDIENCE, GATEWAY,
                        java.util.Set.of("ADMIN", "BUILDER", "OPERATOR", "USER"),
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

    /** 归属键钉扎口径：四键白名单、bizSystemCode/userId 各恰一值（与测试票面一致）。 */
    private AuthorityProperties pinnedOwnershipPortalConfig() {
        return new AuthorityProperties("iam", "gateway",
                new AuthorityProperties.Portal(true, ISSUER, url("/.well-known/jwks.json"),
                        AUDIENCE, GATEWAY,
                        java.util.Set.of("USER"),
                        java.util.Set.of("region", "dataLabels", "bizSystemCode", "userId"),
                        java.util.Map.of("region", java.util.Set.of("A", "B"),
                                "dataLabels", java.util.Set.of("public", "internal"),
                                "bizSystemCode", java.util.Set.of("BIZ-1"),
                                "userId", java.util.Set.of("tester-1")),
                        java.util.Set.of("platform.access")),
                new AuthorityProperties.Authority(ISSUER, url("/.well-known/jwks.json"),
                        url("/oauth/token"), url("/internal/authorize"), GATEWAY,
                        "env:AGENT_SECRET_IAM_GATEWAY_CLIENT", "RS256",
                        "urn:agent-platform:service:gateway", 60, 5, 1000, 2000),
                null, null, null, false, null);
    }
}
