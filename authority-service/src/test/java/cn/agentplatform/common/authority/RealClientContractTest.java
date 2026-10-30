package cn.agentplatform.common.authority;

import cn.agentplatform.authority.AuthorityApplication;
import cn.agentplatform.authority.AuthorityTestSupport;
import cn.agentplatform.authority.TestJwt;
import cn.agentplatform.common.PlatformException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 端到端消费者契约：直接用平台自己的 {@link HttpCurrentAuthorityClient} 打本服务的真实 HTTP 端口
 * （不是 mock，不是假的 authority），验证三个端点在真实客户端的严格校验下能走通。
 * <p>为什么这里用 {@code gateway -> app / integration.origin.check} 而不是卡面示例的
 * {@code skill -> engine / run.wake}：目录里 workload 行只有两行（都属 {@code gateway -> app}），
 * {@code run.wake} 只有 delegated 行，而 workload 消费者还会自己再查一次目录——用 delegated 行只会在
 * 客户端本地被拒，测不到本条链路。delegated 路由需要 M2 的 forward 才能覆盖。
 * <p>本类必须位于 {@code cn.agentplatform.common.authority} 包：{@link WorkloadCredentialResolver}
 * 供测试用的构造器是包私有的。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = AuthorityApplication.class,
        properties = {
                AuthorityTestSupport.P_ISSUER,
                AuthorityTestSupport.P_KID,
                AuthorityTestSupport.P_KEY,
                AuthorityTestSupport.P_GATEWAY,
                AuthorityTestSupport.P_APP})
class RealClientContractTest extends AuthorityTestSupport {

    @Test
    void gatewayClientObtainsATicketThatTheAppClientCanAuthorize() {
        ServiceCall call = new ServiceCall(APP, ORIGIN_ACTION,
                new ResourceBinding("integration-origin", ORIGIN_ID, DIGEST));

        ServiceTicket ticket;
        try (HttpCurrentAuthorityClient gateway = client(GATEWAY, GATEWAY_SECRET_REF, GATEWAY_SECRET)) {
            ticket = gateway.client(call);
        }

        assertThat(ticket.value()).isNotBlank();
        assertThat(ticket.expiresAt()).isAfter(Instant.now());
        JsonNode claims = TestJwt.claims(ticket.value());
        assertThat(claims.get("authority_mode").asText()).isEqualTo("workload");
        assertThat(claims.get("caller").asText()).isEqualTo(GATEWAY);
        assertThat(claims.get("scope").asText()).isEqualTo(ORIGIN_ACTION);
        assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isBetween(1L, 60L);

        WorkloadAuthority decision;
        try (HttpCurrentAuthorityClient app = client(APP, APP_SECRET_REF, APP_SECRET)) {
            decision = app.authorizeWorkload(ticket.value(), call);
        }

        assertThat(decision.caller()).isEqualTo(GATEWAY);
        assertThat(decision.call()).isEqualTo(call);
        assertThat(decision.decisionId()).isNotBlank();
        assertThat(decision.expiresAt()).isAfter(Instant.now());
    }

    /**
     * 目录拒绝面：真实客户端把非 200 折叠成 {@code BoundedIamHttp.unavailable()}（503 →
     * {@code IAM_AUTHORITY_UNAVAILABLE}）。这里断言"抛了且不是成功"；错误码保持客户端现状，
     * M2 需要把 401/403 按状态码映射出来（见证据 README 的待办）。
     */
    @Test
    void clientSurfacesDirectoryDenialAsPlatformException() {
        ServiceCall noWorkloadRoute = new ServiceCall("engine", "run.wake",
                new ResourceBinding("run", "123", DIGEST));

        try (HttpCurrentAuthorityClient gateway = client(GATEWAY, GATEWAY_SECRET_REF, GATEWAY_SECRET)) {
            assertThatThrownBy(() -> gateway.client(noWorkloadRoute))
                    .isInstanceOf(PlatformException.class)
                    .satisfies(e -> {
                        PlatformException platform = (PlatformException) e;
                        assertThat(platform.status()).isEqualTo(503);
                        assertThat(platform.code()).isEqualTo("IAM_AUTHORITY_UNAVAILABLE");
                    });
        }
    }

    private HttpCurrentAuthorityClient client(String service, String secretRef, String secret) {
        AuthorityProperties config = config(service, secretRef);
        WorkloadCredentialResolver credentials = new WorkloadCredentialResolver(config, ref -> secret, true);
        return new HttpCurrentAuthorityClient(config, Clock.systemUTC(), credentials, true);
    }

    private AuthorityProperties config(String service, String secretRef) {
        return new AuthorityProperties("iam", service, null,
                new AuthorityProperties.Authority(
                        ISSUER,
                        url("/.well-known/jwks.json"),
                        url("/oauth/token"),
                        url("/internal/authorize"),
                        service,
                        secretRef,
                        "RS256",
                        "urn:agent-platform:service:" + service,
                        60,
                        5,
                        1000,
                        2000),
                null, null, null, false, null);
    }
}
