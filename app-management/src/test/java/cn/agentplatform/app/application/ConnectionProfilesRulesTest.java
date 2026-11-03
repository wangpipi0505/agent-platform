package cn.agentplatform.app.application;

import cn.agentplatform.common.PlatformException;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 纯规则用例：不启动 Spring、不连数据库，只验证连接档案与环境绑定的字段契约。
 *
 * 这些断言保护的是"错的东西进不来"：未知字段、缺失协议、空操作白名单、明文凭据、非 https 地址、
 * 超预算声明都必须以确定的错误码拒绝。错误码属于 INT-02 契约的一部分，改动必须同时改文档。
 */
class ConnectionProfilesRulesTest {

    private static Map<String, Object> profile(String protocol, List<String> operations) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("protocol", protocol);
        body.put("operationAllowlist", operations);
        body.put("authScheme", Map.of("kind", "API_KEY", "headerName", "X-Api-Key"));
        body.put("identityPolicy", Map.of("mode", "PLATFORM_PRINCIPAL"));
        body.put("allowedUses", List.of("assistant-tool"));
        return body;
    }

    private static String code(Runnable action) {
        PlatformException e = assertThrows(PlatformException.class, action::run);
        return e.code();
    }

    @Test
    void declarationKeepsEmptyOptionalSectionsAndComputesStableDigest() {
        var first = ConnectionProfiles.declaration(profile("HTTP_SYNC", List.of("customers.read")));
        var second = ConnectionProfiles.declaration(profile("HTTP_SYNC", List.of("customers.read")));
        assertEquals(first.sha256(), second.sha256());
        assertEquals(List.of("customers.read"), first.operationAllowlist());
        assertEquals(Map.of(), first.endpointPolicy());
        assertEquals(List.of(), first.inputContractRefs());
        assertNotEquals(first.sha256(),
                ConnectionProfiles.declaration(profile("HTTP_SYNC", List.of("orders.read"))).sha256());
    }

    @Test
    void unknownFieldIsRejectedBeforeAnyWrite() {
        Map<String, Object> body = profile("HTTP_SYNC", List.of("customers.read"));
        body.put("endpoint", "https://crm.example.com");
        assertEquals("INVALID_INPUT",
                code(() -> ConnectionProfiles.keys(body, ConnectionProfiles.BODY_KEYS, "连接档案")));
    }

    @Test
    void protocolIsLimitedToDeclaredKinds() {
        assertEquals("INVALID_INPUT", code(() -> ConnectionProfiles.declaration(profile("GRAPHQL", List.of("q")))));
        var revision = ConnectionProfiles.declaration(profile("MCP", List.of("tools/list")));
        assertEquals("MCP", revision.protocol());
    }

    @Test
    void operationAllowlistCannotBeEmpty() {
        assertEquals("INVALID_INPUT", code(() -> ConnectionProfiles.declaration(profile("HTTP_SYNC", List.of()))));
    }

    @Test
    void plaintextSecretsAreRejectedButSecretReferenceIsAllowed() {
        Map<String, Object> body = profile("HTTP_SYNC", List.of("customers.read"));
        body.put("authScheme", Map.of("kind", "API_KEY", "headerName", "X-Api-Key", "apiToken", "sk-live-123"));
        assertEquals("CONFIG_SECRET_PLAINTEXT_REJECTED", code(() -> ConnectionProfiles.declaration(body)));

        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("environment", "production");
        binding.put("connectionProfileId", "crm-http");
        binding.put("connectionProfileRev", 1);
        binding.put("baseEndpoint", "https://crm.example.com");
        binding.put("secretRef", "vault:kv/agent/crm");
        assertNotNull(EnvironmentBindings.declaration(binding).sha256());
    }

    @Test
    void endpointMustBeHttpsOrLoopbackHttp() {
        assertEquals("INVALID_INPUT",
                code(() -> ConnectionProfiles.endpoint("http://crm.example.com", "baseEndpoint")));
        assertEquals("INVALID_INPUT",
                code(() -> ConnectionProfiles.endpoint("https://crm.example.com?token=1", "baseEndpoint")));
        assertEquals("https://crm.example.com", ConnectionProfiles.endpoint("https://crm.example.com", "baseEndpoint"));
        assertEquals("http://localhost:8080", ConnectionProfiles.endpoint("http://localhost:8080", "baseEndpoint"));
    }

    @Test
    void bindingRequiresPositiveProfileRevision() {
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("environment", "production");
        binding.put("connectionProfileId", "crm-http");
        binding.put("connectionProfileRev", 0);
        binding.put("baseEndpoint", "https://crm.example.com");
        binding.put("secretRef", "vault:kv/agent/crm");
        assertEquals("INVALID_INPUT", code(() -> EnvironmentBindings.declaration(binding)));
    }

    @Test
    void oversizedDeclarationHitsSchemaBudget() {
        // 预算检查发生在字段校验之前：超大体一律按预算拒绝，避免先解析再拒的服务端放大。
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("profileId", "crm-http");
        body.put("outputContractRefs", "x".repeat(70_000));
        assertEquals("CONFIG_SCHEMA_BUDGET_EXCEEDED",
                code(() -> ConnectionProfiles.keys(body, ConnectionProfiles.BODY_KEYS, "连接档案")));
    }

    @Test
    void expectedRevMustBeAPositiveWholeNumber() {
        assertEquals("INVALID_INPUT", code(() -> ConnectionProfiles.expectedRev(Map.of())));
        assertEquals("INVALID_INPUT", code(() -> ConnectionProfiles.expectedRev(Map.of("expectedRev", 1.5))));
        assertEquals(3, ConnectionProfiles.expectedRev(Map.of("expectedRev", 3)));
    }

    @Test
    void requestIdMustLookLikeAnIdempotencyKey() {
        assertEquals("INVALID_INPUT", code(() -> ConnectionProfiles.requestId(Map.of("requestId", "short"))));
        assertEquals("req-20261103-0001", ConnectionProfiles.requestId(Map.of("requestId", "req-20261103-0001")));
    }

    @Test
    void bearerIsDeclarableAndRuntimeSupported() {
        // §10.1：BEARER 在目录里可声明，运行期也已开放。
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("protocol", "HTTP_SYNC");
        body.put("operationAllowlist", List.of("customers.read"));
        body.put("authScheme", Map.of("kind", "BEARER", "headerName", "Authorization"));
        body.put("identityPolicy", Map.of("mode", "PLATFORM_PRINCIPAL"));
        body.put("allowedUses", List.of("read"));
        var revision = ConnectionProfiles.declaration(body);
        assertEquals("BEARER", ConnectionProfiles.runtimeAuthKind(revision.authScheme()));
    }

    @Test
    void frozenAuthKindsDeclareButNeverResolve() {
        // 可声明（目录要能记录未来形态），但 resolve 必须显式拒绝，不能静默当 NONE。
        for (String kind : List.of("OAUTH2_CLIENT_CREDENTIALS", "MTLS", "SIGNED_REQUEST")) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("protocol", "HTTP_SYNC");
            body.put("operationAllowlist", List.of("customers.read"));
            body.put("authScheme", Map.of("kind", kind));
            body.put("identityPolicy", Map.of("mode", "SERVICE_ACCOUNT"));
            body.put("allowedUses", List.of("read"));
            var revision = ConnectionProfiles.declaration(body);
            assertEquals("CONFIG_AUTH_SCHEME_UNSUPPORTED",
                    code(() -> ConnectionProfiles.runtimeAuthKind(revision.authScheme())));
        }
    }

    @Test
    void absentAuthSchemeMeansNoAuthentication() {
        assertEquals("NONE", ConnectionProfiles.runtimeAuthKind(Map.of()));
        assertEquals("NONE", ConnectionProfiles.runtimeAuthKind(null));
    }
}
