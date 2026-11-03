package cn.agentplatform.gateway.api;

import cn.agentplatform.common.PlatformException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 门户会话失败面的映射：只有"身份源不可达"才改写成契约 §6 的阻塞码，
 * 其余失败保持原码，避免把凭据错误伪装成基础设施故障。
 */
class PortalSessionDegradeTest {

    @Test
    void authorityUnavailableBecomesTheRegisteredBlock() {
        var mapped = PortalSessionController.degrade(
                new PlatformException(503, "IAM_AUTHORITY_UNAVAILABLE", "可信授权服务暂不可用"));
        assertEquals(503, mapped.status());
        assertEquals("INTEGRATION_IDENTITY_PROVIDER_UNAVAILABLE", mapped.code());
        assertEquals("身份源暂不可用，未执行业务操作", mapped.getMessage());
    }

    @Test
    void identitySourceUnavailableBecomesTheRegisteredBlock() {
        var mapped = PortalSessionController.degrade(
                new PlatformException(503, "IAM_IDENTITY_UNAVAILABLE", "identity source failed"));
        assertEquals(503, mapped.status());
        assertEquals("INTEGRATION_IDENTITY_PROVIDER_UNAVAILABLE", mapped.code());
    }

    @Test
    void otherUnavailableCapabilitiesKeepTheirCode() {
        var original = new PlatformException(503, "PORTAL_SESSION_UNAVAILABLE", "门户会话能力尚未就绪");
        assertSame(original, PortalSessionController.degrade(original));
    }

    @Test
    void authorizationFailuresAreNotDisguisedAsTransportFailures() {
        var original = new PlatformException(403, "PORTAL_TOKEN_INVALID", "invalid");
        assertSame(original, PortalSessionController.degrade(original));
    }
}
