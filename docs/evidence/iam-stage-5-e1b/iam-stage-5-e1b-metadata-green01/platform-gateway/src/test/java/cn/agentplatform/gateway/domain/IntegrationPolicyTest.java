package cn.agentplatform.gateway.domain;

import static org.assertj.core.api.Assertions.*;

import cn.agentplatform.common.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class IntegrationPolicyTest {
    private Map<String, Object> credential() {
        return Map.of(
                "id",
                1,
                "tenantId",
                "tenant-a",
                "appCode",
                "ops",
                "bizSystemCode",
                "partner",
                "status",
                "ACTIVE",
                "secretRef",
                "vault:test",
                "allowedOrigins",
                List.of("https://partner.example"),
                "claimLimits",
                Map.of(
                        "principalIds",
                        List.of("alice"),
                        "roles",
                        List.of("operator", "ADMIN"),
                        "attributes",
                        Map.of("region", List.of("A"))));
    }

    @Test
    void clipsClaimsAndNamespacesPrincipalWithoutGrantingPlatformRoles() {
        var actor =
                IntegrationPolicy.clip(
                        Json.tree(credential()),
                        Json.tree(
                                Map.of(
                                        "userId",
                                        "alice",
                                        "roles",
                                        List.of("operator", "ADMIN", "SERVICE"),
                                        "attributes",
                                        Map.of(
                                                "region",
                                                List.of("A", "B"),
                                                "tenant_id",
                                                List.of("evil")))));
        assertThat(actor.tenantId()).isEqualTo("tenant-a");
        assertThat(actor.principalId()).isEqualTo("partner:alice");
        assertThat(actor.roles()).containsExactly("operator");
        assertThat(actor.attributes().get("region")).containsExactly("A");
        assertThat(actor.attributes()).doesNotContainKey("tenant_id");
    }

    @Test
    void rejectsUnknownPrincipalAndRevokedCredential() {
        assertThatThrownBy(
                        () ->
                                IntegrationPolicy.clip(
                                        Json.tree(credential()),
                                        Json.tree(Map.of("userId", "mallory"))))
                .isInstanceOf(PlatformException.class);
        var revoked = new HashMap<>(credential());
        revoked.put("status", "REVOKED");
        assertThatThrownBy(
                        () ->
                                IntegrationPolicy.clip(
                                        Json.tree(revoked), Json.tree(Map.of("userId", "alice"))))
                .isInstanceOf(PlatformException.class);
    }

    @Test
    void policyChangesInvalidateExistingTicketAndRoutesAreNarrow() {
        assertThat(IntegrationPolicy.allowedPath("/v1/apps/ops/input-schema", "GET", "ops"))
                .isTrue();
        assertThat(IntegrationPolicy.allowedPath("/v1/apps/other/input-schema", "GET", "ops"))
                .isFalse();
        var changed = new HashMap<>(credential());
        changed.put("allowedOrigins", List.of("https://other.example"));
        assertThat(IntegrationPolicy.digest(Json.tree(credential())))
                .isNotEqualTo(IntegrationPolicy.digest(Json.tree(changed)));
        assertThat(IntegrationPolicy.allowedPath("/v1/apps/ops/runs", "POST", "ops")).isTrue();
        assertThat(IntegrationPolicy.allowedPath("/v1/apps/other/runs", "POST", "ops")).isFalse();
        assertThat(IntegrationPolicy.allowedPath("/admin/apps/ops/runs", "POST", "ops")).isFalse();
        assertThat(IntegrationPolicy.allowedPath("/v1/runs/r_1/events", "GET", "ops")).isTrue();
        assertThat(IntegrationPolicy.allowedPath("/v1/runs/r_1/cancel", "DELETE", "ops")).isFalse();
        assertThat(
                        IntegrationPolicy.allowedPath(
                                "/v1/runs/r_1/artifacts/abc/download", "GET", "ops"))
                .isTrue();
        assertThat(IntegrationPolicy.allowedPath("/admin/artifacts/abc/download", "GET", "ops"))
                .isFalse();
    }

    @Test
    void sessionTicketsRemainBoundToTheAppAndPublicOperations() {
        assertThat(IntegrationPolicy.allowedPath("/v1/apps/ops/sessions", "POST", "ops")).isTrue();
        assertThat(IntegrationPolicy.allowedPath("/v1/apps/ops/sessions/s_1", "GET", "ops"))
                .isTrue();
        assertThat(IntegrationPolicy.allowedPath("/v1/apps/ops/sessions/s_1/turns", "POST", "ops"))
                .isTrue();
        assertThat(
                        IntegrationPolicy.allowedPath(
                                "/v1/apps/other/sessions/s_1/turns", "POST", "ops"))
                .isFalse();
        assertThat(IntegrationPolicy.allowedPath("/v1/apps/ops/sessions/s_1/turns", "GET", "ops"))
                .isFalse();
        assertThat(IntegrationPolicy.allowedPath("/admin/apps/ops/sessions", "POST", "ops"))
                .isFalse();
        assertThat(IntegrationPolicy.allowedPath("/v1/apps/ops/sessions/s_1/secret", "GET", "ops"))
                .isFalse();
    }

    @Test void sharedRulesPreserveLongPrincipalAndCanonicalLegacyDigest() {
        String user="u".repeat(90);var c=new HashMap<>(credential());
        c.put("claimLimits",Map.of("principalIds",List.of(user),"roles",List.of("operator","admin"),"attributes",Map.of("region",List.of("A"))));
        var node=Json.tree(c);var actor=IntegrationPolicy.clip(node,Json.tree(Map.of("userId",user,"roles",List.of("operator","admin"))));
        assertThat(actor.principalId()).isEqualTo("ext_"+Digest.sha256("partner\u0000"+user).substring(0,60));assertThat(actor.roles()).containsExactly("operator");
        assertThat(IntegrationPolicy.digest(node)).isEqualTo(Digest.sha256(Json.canonical(Map.of("secretRef",node.path("secretRef"),"claimLimits",node.path("claimLimits"),"allowedOrigins",node.path("allowedOrigins")))));
        assertThat(IntegrationPolicy.allowedPath("/internal/iam/integration-policy/resolve","POST","ops")).isFalse();
    }
}
