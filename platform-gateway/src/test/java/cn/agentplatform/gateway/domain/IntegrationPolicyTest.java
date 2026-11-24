package cn.agentplatform.gateway.domain;

import static org.assertj.core.api.Assertions.*;

import cn.agentplatform.common.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class IntegrationPolicyTest {
    @Test
    void mapsDeclaredRolesByCredentialAndDowngradesBuilder() {
        var a = new HashMap<>(credential());
        a.put("bizSystemCode", "softwareA");
        a.put("claimLimits", Map.of("principalIds", List.of("alice"),
                "roles", List.of("leader", "AGENT_BUILDER"), "attributes", Map.of("dataLabels", List.of("A"))));
        a.put("roleMapping", Map.of("leader", "AGENT_ADMIN", "AGENT_BUILDER", "AGENT_ADMIN"));
        var b = new HashMap<>(a);
        b.put("bizSystemCode", "softwareB");
        b.put("roleMapping", Map.of("leader", "AGENT_VIEWER"));
        var request = Json.tree(Map.of("userId", "alice", "roles", List.of("leader", "AGENT_BUILDER", "ADMIN"),
                "attributes", Map.of("bizSystemCode", List.of("softwareB"), "dataLabels", List.of("A", "B"))));
        var actorA = IntegrationPolicy.clip(Json.tree(a), request);
        var actorB = IntegrationPolicy.clip(Json.tree(b), request);
        assertThat(actorA.roles()).containsExactlyInAnyOrder("AGENT_ADMIN", "AGENT_VIEWER");
        assertThat(actorB.roles()).containsExactly("AGENT_VIEWER");
        assertThat(actorA.principalId()).isEqualTo("softwareA:alice");
        assertThat(actorB.principalId()).isEqualTo("softwareB:alice");
        assertThat(actorA.attributes().get("bizSystemCode")).containsExactly("softwareA");
        assertThat(actorA.attributes().get("dataLabels")).containsExactly("A");
        assertThat(IntegrationPolicy.digest(Json.tree(a))).isNotEqualTo(IntegrationPolicy.digest(Json.tree(b)));
    }

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
                                                List.of("A", "B")))));
        assertThat(actor.tenantId()).isEqualTo("tenant-a");
        assertThat(actor.principalId()).isEqualTo("partner:alice");
        assertThat(actor.roles()).containsExactly("operator");
        assertThat(actor.attributes().get("region")).containsExactly("A");
        assertThat(actor.attributes()).doesNotContainKey("tenant_id");
        assertThatThrownBy(() -> IntegrationPolicy.clip(Json.tree(credential()),
                Json.tree(Map.of("userId", "alice", "attributes", Map.of("tenant_id", List.of("evil"))))))
                .isInstanceOfSatisfying(PlatformException.class,
                        failure -> assertThat(failure.code()).isEqualTo("INTEGRATION_CLIENT_IDENTITY_REJECTED"));
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

    /** T2-15：任务域四端点白名单——逐路径断言放行与拒绝（与 authority 目录行一一对应）。 */
    @Test
    void taskDomainWhitelistsOnlyTheFourServiceEndpoints() {
        // 白名单内四端点（首期仅 AGENT——mode=PLAN 由引擎 Tasks.create 外部 caller 分支拒绝并审计）。
        assertThat(IntegrationPolicy.allowedPath("/v1/apps/ops/tasks", "POST", "ops")).isTrue();
        assertThat(IntegrationPolicy.allowedPath("/v1/apps/other/tasks", "POST", "ops")).isFalse();
        assertThat(IntegrationPolicy.allowedPath("/v1/tasks/123", "GET", "ops")).isTrue();
        assertThat(IntegrationPolicy.allowedPath("/v1/tasks/123/events", "GET", "ops")).isTrue();
        assertThat(IntegrationPolicy.allowedPath("/v1/tasks/123/result", "GET", "ops")).isTrue();
        // 白名单外：cancel/resume/execute/plans/对账/步骤调用/SSE 一律不放行。
        assertThat(IntegrationPolicy.allowedPath("/v1/tasks/123/cancel", "POST", "ops")).isFalse();
        assertThat(IntegrationPolicy.allowedPath("/v1/tasks/123/resume", "POST", "ops")).isFalse();
        assertThat(IntegrationPolicy.allowedPath("/v1/tasks/123/execute", "POST", "ops")).isFalse();
        assertThat(IntegrationPolicy.allowedPath("/v1/tasks/123/plans", "POST", "ops")).isFalse();
        assertThat(IntegrationPolicy.allowedPath("/v1/tasks/123/plans/p1/revisions/1", "GET", "ops"))
                .isFalse();
        assertThat(
                        IntegrationPolicy.allowedPath(
                                "/v1/tasks/123/plans/p1/revisions/1/confirm", "POST", "ops"))
                .isFalse();
        assertThat(IntegrationPolicy.allowedPath("/v1/tasks/123/steps/entry/calls", "GET", "ops"))
                .isFalse();
        assertThat(
                        IntegrationPolicy.allowedPath(
                                "/v1/tasks/123/steps/entry/calls/7/reconcile", "POST", "ops"))
                .isFalse();
        assertThat(IntegrationPolicy.allowedPath("/v1/tasks/123/events/stream", "GET", "ops"))
                .isFalse();
        assertThat(IntegrationPolicy.allowedPath("/v1/tasks/123/result", "POST", "ops")).isFalse();
        assertThat(IntegrationPolicy.allowedPath("/v1/tasks/123/results", "GET", "ops")).isFalse();
        assertThat(IntegrationPolicy.allowedPath("/admin/tasks/123", "GET", "ops")).isFalse();
    }
}
