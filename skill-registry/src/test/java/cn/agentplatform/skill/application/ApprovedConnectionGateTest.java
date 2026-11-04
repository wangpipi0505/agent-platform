package cn.agentplatform.skill.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.infra.ConnectionResolutionReceipts;
import cn.agentplatform.skill.infra.SkillClients;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.*;

/**
 * INT-02 子步3：分发前受控连接复核（纯规则，不连库、不连网）。
 *
 * 覆盖契约 §5.2 的判定面：未声明引用时零副作用、逐条解析并留调用范围回执、
 * 伪造操作/摘要/租户/鉴权形态在进入适配器之前被拒绝。
 */
class ApprovedConnectionGateTest {

    static final String DIGEST = "a".repeat(64);
    static final String TENANT = "gate-tenant";

    SkillClients clients;
    ConnectionResolutionReceipts receipts;
    ApprovedConnectionResolver resolver;

    @BeforeEach
    void setUp() {
        var jwt = Jwt.withTokenValue("verified-test")
                .header("alg", "HS256").subject("gate-tester").claim("tenant_id", TENANT)
                .claim("roles", List.of("SERVICE")).claim("attributes", Map.of())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(120)).build();
        org.springframework.security.core.context.SecurityContextHolder
                .getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        clients = mock(SkillClients.class);
        receipts = mock(ConnectionResolutionReceipts.class);
        when(receipts.record(any(), anyString(), anyMap(), any(), anyMap()))
                .thenAnswer(call -> {
                    Map<String, Object> facts = new LinkedHashMap<>(call.getArgument(2));
                    facts.put("receiptId", 11L);
                    facts.put("resolvedAt", "2026-11-03T00:00:00Z");
                    return facts;
                });
        resolver = new ApprovedConnectionResolver(clients, receipts, "http://app.test");
    }

    @AfterEach
    void clear() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    static JsonNode payload(String profileId, int rev, String digest, String authKind, List<String> allow) {
        ObjectNode node = Json.object();
        node.put("profileId", profileId);
        node.put("profileRev", rev);
        node.put("status", "APPROVED");
        node.put("protocol", "HTTP_SYNC");
        node.put("authKind", authKind);
        node.put("resolutionDigest", digest);
        node.set("operationAllowlist", Json.tree(allow));
        node.set("allowedUses", Json.tree(List.of("read", "write", "execute")));
        node.set("grantedUses", Json.tree(List.of("read", "write", "execute")));
        node.set("bindings", Json.tree(List.of()));
        node.set("grants", Json.tree(List.of(Map.of("grantee", "skill.crm-read",
                "allowedUses", List.of("read", "write", "execute")))));
        node.set("endpointPolicy", Json.object());
        node.set("authScheme", Json.tree(Map.of("kind", authKind)));
        node.set("identityPolicy", Json.tree(Map.of("mode", "SERVICE_ACCOUNT")));
        node.set("inputContractRefs", Json.tree(List.of()));
        node.set("outputContractRefs", Json.tree(List.of()));
        node.put("resolvedAt", "2026-11-03T00:00:00Z");
        return node;
    }

    static Map<String, Object> ref(String profileId, int rev, String operation) {
        return Map.of("profileId", profileId, "profileRev", rev, "operation", operation);
    }

    @Test
    void callsWithoutRefsDoNothingAtAll() {
        var out = resolver.gate(Map.of(), Map.of("callId", 7, "runId", "r_1"), "crm-read");

        assertThat((List<?>) out.get("refs")).isEmpty();
        verifyNoInteractions(clients, receipts);
    }

    @Test
    void eachRefIsResolvedAndRecordedWithCallScope() {
        when(clients.get(anyString())).thenReturn(payload("crm-prod", 2, DIGEST, "API_KEY",
                List.of("customers.read")));
        Map<String, Object> ref = new LinkedHashMap<>(ref("crm-prod", 2, "customers.read"));
        Map<String, Object> request = Map.of("callId", 42, "runId", "r_abc", "attemptId", 3,
                "bindingRefs", List.of(ref));

        var out = resolver.gate(Map.of(), request, "crm-read");

        var entry = (Map<?, ?>) ((List<?>) out.get("refs")).get(0);
        assertThat(entry.get("profileId")).isEqualTo("crm-prod");
        assertThat(entry.get("profileRev")).isEqualTo(2);
        assertThat(entry.get("operation")).isEqualTo("customers.read");
        assertThat(entry.get("authKind")).isEqualTo("API_KEY");
        assertThat(entry.get("resolutionDigest")).isEqualTo(DIGEST);
        assertThat(entry.get("receiptId")).isEqualTo(11L);

        @SuppressWarnings("unchecked")
        var scope = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(receipts).record(any(), eq("crm-read"), anyMap(), any(), scope.capture());
        assertThat(scope.getValue()).containsEntry("callId", 42)
                .containsEntry("runPublicId", "r_abc")
                .containsEntry("attemptId", 3)
                .containsEntry("operation", "customers.read");
    }

    @Test
    void operationOutsideTheAllowlistIsRejectedBeforeDispatch() {
        when(clients.get(anyString())).thenReturn(payload("crm-prod", 1, DIGEST, "NONE",
                List.of("customers.read")));
        Map<String, Object> request = Map.of("callId", 1, "runId", "r_1",
                "bindingRefs", List.of(ref("crm-prod", 1, "customers.delete")));

        assertThatThrownBy(() -> resolver.gate(Map.of(), request, "crm-read"))
                .isInstanceOfSatisfying(PlatformException.class, e -> {
                    assertThat(e.status()).isEqualTo(400);
                    assertThat(e.code()).isEqualTo("CONNECTION_OPERATION_NOT_ALLOWED");
                });
    }

    @Test
    void declaredDigestThatNoLongerMatchesTheApprovedProfileIsRejected() {
        when(clients.get(anyString())).thenReturn(payload("crm-prod", 1, DIGEST, "NONE",
                List.of("customers.read")));
        Map<String, Object> ref = new LinkedHashMap<>(ref("crm-prod", 1, "customers.read"));
        ref.put("resolutionDigest", "b".repeat(64));
        Map<String, Object> request = Map.of("callId", 1, "runId", "r_1", "bindingRefs", List.of(ref));

        assertThatThrownBy(() -> resolver.gate(Map.of(), request, "crm-read"))
                .isInstanceOfSatisfying(PlatformException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.code()).isEqualTo("CONNECTION_BINDING_CHANGED");
                });
    }

    @Test
    void aRefCannotSmuggleItsOwnTenant() {
        Map<String, Object> ref = new LinkedHashMap<>(ref("crm-prod", 1, "customers.read"));
        ref.put("tenantId", "other-tenant");
        Map<String, Object> request = Map.of("callId", 1, "runId", "r_1", "bindingRefs", List.of(ref));

        assertThatThrownBy(() -> resolver.gate(Map.of(), request, "crm-read"))
                .isInstanceOfSatisfying(PlatformException.class, e -> {
                    assertThat(e.status()).isEqualTo(400);
                    assertThat(e.code()).isEqualTo("INVALID_INPUT");
                });
        verifyNoInteractions(clients);
    }

    @Test
    void frozenButNotYetRuntimeAuthKindsAreRejectedInsteadOfTreatedAsNone() {
        when(clients.get(anyString())).thenReturn(payload("crm-prod", 1, DIGEST,
                "OAUTH2_CLIENT_CREDENTIALS", List.of("customers.read")));
        Map<String, Object> request = Map.of("callId", 1, "runId", "r_1",
                "bindingRefs", List.of(ref("crm-prod", 1, "customers.read")));

        assertThatThrownBy(() -> resolver.gate(Map.of(), request, "crm-read"))
                .isInstanceOfSatisfying(PlatformException.class, e -> {
                    assertThat(e.status()).isEqualTo(400);
                    assertThat(e.code()).isEqualTo("CONFIG_AUTH_SCHEME_UNSUPPORTED");
                });
    }

    @Test
    void aSingleCallCannotReferenceAnUnboundedNumberOfProfiles() {
        List<Map<String, Object>> refs = new ArrayList<>();
        for (int i = 0; i < ApprovedConnectionResolver.MAX_REFS + 1; i++)
            refs.add(ref("crm-" + i, 1, "customers.read"));
        Map<String, Object> request = Map.of("callId", 1, "runId", "r_1", "bindingRefs", refs);

        assertThatThrownBy(() -> resolver.gate(Map.of(), request, "crm-read"))
                .isInstanceOfSatisfying(PlatformException.class, e -> {
                    assertThat(e.status()).isEqualTo(400);
                    assertThat(e.code()).isEqualTo("INVALID_INPUT");
                });
        verifyNoInteractions(clients);
    }

    /**
     * §5.2 第④条：撤权/停用之后，继续调用、重试与观察都按"当前权限"判定——
     * 解析器不缓存，app 一旦拒绝，本次调用就在进入适配器之前失败。
     */
    @Test
    void revokedOrDisabledProfileRejectsTheNextCallAndTheRetry() {
        when(clients.get(anyString()))
                .thenThrow(new PlatformException(400, "CONFIG_RELEASE_NOT_ACTIVE",
                        "连接档案已停用: crm-prod"));
        Map<String, Object> request = Map.of("callId", 8, "runId", "r_8",
                "bindingRefs", List.of(ref("crm-prod", 1, "customers.read")));

        for (int attempt = 0; attempt < 2; attempt++)
            assertThatThrownBy(() -> resolver.gate(Map.of(), request, "crm-read"))
                    .isInstanceOfSatisfying(PlatformException.class, e -> {
                        assertThat(e.status()).isEqualTo(400);
                        assertThat(e.code()).isEqualTo("CONFIG_RELEASE_NOT_ACTIVE");
                    });

        // 拒绝发生在写回执之前：被拒的调用不该留下"已解析"的记录。
        verify(receipts, never()).record(any(), anyString(), anyMap(), any(), anyMap());
    }

    /**
     * §5.2 第①条的另一半：技能侧读到的摘要必须就是 app 当前解析的那一份，
     * 两边不一致时以拒绝收场，而不是各说各话地继续执行。
     */
    @Test
    void skillSideDigestMustEqualTheResolvedDigest() {
        when(clients.get(anyString())).thenReturn(payload("crm-prod", 1, "b".repeat(64), "API_KEY",
                List.of("customers.read")));
        Map<String, Object> ref = new LinkedHashMap<>(ref("crm-prod", 1, "customers.read"));
        ref.put("resolutionDigest", DIGEST);
        Map<String, Object> request = Map.of("callId", 9, "runId", "r_9",
                "bindingRefs", List.of(ref));

        assertThatThrownBy(() -> resolver.gate(Map.of(), request, "crm-read"))
                .isInstanceOfSatisfying(PlatformException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.code()).isEqualTo("CONNECTION_BINDING_CHANGED");
                });
    }

    @Test
    void emptyGrantsNeverAuthorizeAnApprovedProfile() {
        var facts = (ObjectNode) payload("crm-prod", 1, DIGEST, "NONE", List.of("customers.read"));
        facts.set("grants", Json.tree(List.of()));
        facts.set("grantedUses", Json.tree(List.of()));
        when(clients.get(anyString())).thenReturn(facts);
        assertDenied(Map.of("execution", Map.of("readonly", true)), ref("crm-prod", 1, "customers.read"));
    }

    @Test
    void anotherGranteeAndItsUnionOfUsesCannotAuthorizeThisSkill() {
        var facts = (ObjectNode) payload("crm-prod", 1, DIGEST, "NONE", List.of("customers.read"));
        facts.set("grants", Json.tree(List.of(Map.of("grantee", "skill.other-reader", "allowedUses", List.of("read")))));
        when(clients.get(anyString())).thenReturn(facts);
        var forged = new LinkedHashMap<>(ref("crm-prod", 1, "customers.read"));
        forged.put("grantee", "skill.other-reader");
        forged.put("use", "read");
        assertDenied(Map.of("execution", Map.of("readonly", true)), forged);
    }

    @Test
    void matchingSkillCannotBorrowAnothersWritePermission() {
        var facts = (ObjectNode) payload("crm-prod", 1, DIGEST, "NONE", List.of("customers.write"));
        facts.set("grants", Json.tree(List.of(
                Map.of("grantee", "skill.crm-read", "allowedUses", List.of("read")),
                Map.of("grantee", "skill.other-writer", "allowedUses", List.of("write")))));
        when(clients.get(anyString())).thenReturn(facts);
        assertDenied(Map.of("execution", Map.of("sideEffect", true)), ref("crm-prod", 1, "customers.write"));
    }

    @Test
    void profileAndGrantMustBothAllowTheDerivedUse() {
        var facts = (ObjectNode) payload("crm-prod", 1, DIGEST, "NONE", List.of("customers.read"));
        facts.set("allowedUses", Json.tree(List.of("write")));
        when(clients.get(anyString())).thenReturn(facts);
        assertDenied(Map.of("execution", Map.of("readonly", true)), ref("crm-prod", 1, "customers.read"));
    }

    @Test
    void matchingGrantAllowsOnlyUntilItIsRevokedForContinueRetryOrObservation() {
        var active = (ObjectNode) payload("crm-prod", 1, DIGEST, "NONE", List.of("customers.read"));
        var revoked = active.deepCopy();
        revoked.set("grants", Json.tree(List.of()));
        revoked.set("grantedUses", Json.tree(List.of()));
        when(clients.get(anyString())).thenReturn(active, revoked);
        var contract = Map.<String, Object>of("execution", Map.of("readonly", true));
        var ref = ref("crm-prod", 1, "customers.read");
        assertThat((List<?>) resolver.gate(contract, Map.of("bindingRefs", List.of(ref)), "crm-read").get("refs"))
                .hasSize(1);
        clearInvocations(receipts);
        for (int i = 0; i < 3; i++) assertDenied(contract, ref);
        verify(clients, times(4)).get(anyString());
    }

    @Test
    void serviceWideLegacyGrantDoesNotAuthorizeEverySkill() {
        var facts = (ObjectNode) payload("crm-prod", 1, DIGEST, "NONE", List.of("customers.read"));
        facts.set("grants", Json.tree(List.of(Map.of("grantee", "skill-registry", "allowedUses", List.of("read")))));
        when(clients.get(anyString())).thenReturn(facts);
        assertDenied(Map.of("execution", Map.of("readonly", true)), ref("crm-prod", 1, "customers.read"));
    }

    private void assertDenied(Map<String, Object> contract, Map<String, Object> ref) {
        assertThatThrownBy(() -> resolver.gate(contract, Map.of("bindingRefs", List.of(ref)), "crm-read"))
                .isInstanceOfSatisfying(PlatformException.class, e -> {
                    assertThat(e.status()).isEqualTo(403);
                    assertThat(e.code()).isEqualTo("CONNECTION_USE_NOT_GRANTED");
                });
        verify(receipts, never()).record(any(), anyString(), anyMap(), any(), anyMap());
    }
}
