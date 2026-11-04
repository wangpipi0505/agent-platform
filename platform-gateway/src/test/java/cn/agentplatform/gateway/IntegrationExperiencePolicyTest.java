package cn.agentplatform.gateway;

import cn.agentplatform.gateway.domain.IntegrationPolicy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IntegrationExperiencePolicyTest {
    @Test void experienceAndSemanticReadsAreNarrowlyAllowedForTheBoundApplication() {
        assertTrue(IntegrationPolicy.allowedPath("/v1/apps/orders/experience","GET","orders"));
        assertFalse(IntegrationPolicy.allowedPath("/v1/apps/other/experience","GET","orders"));
        assertFalse(IntegrationPolicy.allowedPath("/v1/apps/orders/experience","POST","orders"));
        assertFalse(IntegrationPolicy.allowedPath("/admin/apps/orders/experiences","GET","orders"));
        assertTrue(IntegrationPolicy.allowedPath("/v1/runs/r_1/semantic-results","GET","orders"));
        assertFalse(IntegrationPolicy.allowedPath("/v1/runs/r_1/semantic-results","POST","orders"));
    }
}
