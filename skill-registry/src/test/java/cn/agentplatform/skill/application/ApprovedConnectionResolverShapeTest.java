package cn.agentplatform.skill.application;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 纯规则用例：skill 侧对 app 解析回执的形状校验（不启动 Spring、不连数据库）。
 *
 * 这些断言守的是"看不懂就别用"：远端少字段、摘要不合法、鉴权形态未开放，都必须显式失败，
 * 不能降级成"当作没有鉴权"继续调用。
 */
class ApprovedConnectionResolverShapeTest {

    static final String DIGEST = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void validReceiptIsCopiedFieldByField() {
        var facts = ApprovedConnectionResolver.shape(Json.read(payload(DIGEST, "API_KEY", "[]")), "crm-primary", 1);
        assertEquals("crm-primary", facts.get("profileId"));
        assertEquals(1, facts.get("profileRev"));
        assertEquals("HTTP_SYNC", facts.get("protocol"));
        assertEquals("API_KEY", facts.get("authKind"));
        assertEquals(List.of("customers.read"), facts.get("operationAllowlist"));
        assertEquals(DIGEST, facts.get("resolutionDigest"));
    }

    @Test
    void malformedDigestIsProtocolFailureNotSilentSuccess() {
        var error = assertThrows(PlatformException.class,
                () -> ApprovedConnectionResolver.shape(Json.read(payload("not-a-digest", "API_KEY", "[]")), "crm-primary", 1));
        assertEquals("CONNECTION_RESOLUTION_MALFORMED", error.code());
        assertEquals(502, error.status());
    }

    @Test
    void revisionMismatchIsRejected() {
        var error = assertThrows(PlatformException.class,
                () -> ApprovedConnectionResolver.shape(Json.read(payload(DIGEST, "API_KEY", "[]")), "crm-primary", 2));
        assertEquals("CONNECTION_RESOLUTION_MALFORMED", error.code());
    }

    @Test
    void frozenAuthKindsAreRejectedWithContractCode() {
        var error = assertThrows(PlatformException.class, () -> ApprovedConnectionResolver.shape(
                Json.read(payload(DIGEST, "OAUTH2_CLIENT_CREDENTIALS", "[]")), "crm-primary", 1));
        assertEquals("CONFIG_AUTH_SCHEME_UNSUPPORTED", error.code());
        assertEquals(400, error.status());
    }

    @Test
    void nullOrNonArrayCollectionsAreRejected() {
        assertEquals("CONNECTION_RESOLUTION_MALFORMED", assertThrows(PlatformException.class,
                () -> ApprovedConnectionResolver.shape(Json.read(payload(DIGEST, "NONE", "{}")), "crm-primary", 1)).code());
        assertEquals("CONNECTION_RESOLUTION_MALFORMED", assertThrows(PlatformException.class,
                () -> ApprovedConnectionResolver.shape(null, "crm-primary", 1)).code());
    }

    private static String payload(String digest, String authKind, String allowedUses) {
        return "{\"profileId\":\"crm-primary\",\"profileRev\":1,\"status\":\"APPROVED\",\"protocol\":\"HTTP_SYNC\","
                + "\"operationAllowlist\":[\"customers.read\"],\"endpointPolicy\":{},\"authScheme\":{\"kind\":\""
                + authKind + "\"},\"authKind\":\"" + authKind + "\",\"identityPolicy\":{},"
                + "\"inputContractRefs\":[],\"outputContractRefs\":[],\"allowedUses\":[\"read\"],"
                + "\"grantedUses\":" + allowedUses + ",\"bindings\":[],\"grants\":[],"
                + "\"resolutionDigest\":\"" + digest + "\",\"resolvedAt\":\"2026-11-03T12:00:00Z\"}";
    }
}
