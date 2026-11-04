package cn.agentplatform.skill.application;

import cn.agentplatform.app.application.*;
import cn.agentplatform.app.infra.*;
import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.RunService;
import cn.agentplatform.engine.infra.RuntimeClients;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 配置引用保留、当前绑定事实和引擎会话恢复的相邻合同检查，无数据库替身作为生产实现。 */
class ApprovedConnectionConfigurationTest {
    @BeforeEach void identity() {
        var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("verified").header("alg", "HS256")
                .subject("operator").claim("tenant_id", "dispatch-test").claim("roles", List.of("SERVICE", "EDITOR"))
                .build();
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt));
    }
    @AfterEach void clear() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    Map<String, Object> ref(String binding) { return Map.of("profileId", "crm-prod", "profileRev", 1,
            "bindingId", binding, "bindingRev", 1, "operation", "customers.read"); }
    @Test void exactReleaseReferenceSurvivesParsingAndEngineSessionRestoration() throws Exception {
        var reference = new LinkedHashMap<String, Object>(ref("crm-b"));
        reference.put("environment", "production");
        var parser = IntegrationConfigReleases.class.getDeclaredMethod("refs", Object.class);
        parser.setAccessible(true);
        Object parsed = parser.invoke(null, List.of(reference));
        assertThat(Json.tree(parsed)).isEqualTo(Json.tree(List.of(reference)));
        var projection = Json.object();
        projection.put("appCode", "demo");
        projection.set("configReleaseRef", Json.tree(Map.of("configReleaseId", "release-b", "digest", "b".repeat(64))));
        projection.set("integrationProfileRef", Json.tree(Map.of("profileId", "integration-b", "profileRev", 1,
                "environment", "production")));
        projection.set("bindingRefs", Json.tree(parsed));
        var runtime = mock(RuntimeClients.class);
        when(runtime.get(eq("app"), anyString(), any())).thenReturn(projection);
        var fixed = new RunService(null, runtime, null, null).resolveConfig("demo",
                Json.tree(Map.of("configReleaseId", "release-b")), Actor.current());
        assertThat(fixed.bindingRefs()).isEqualTo(Json.tree(List.of(reference)));
        var restored = new RunService.ConfigReference(fixed.releaseId(), fixed.digest(), fixed.environment(), fixed.environmentBindingRevs());
        assertThat(restored.bindingRefs()).isEqualTo(fixed.bindingRefs());
        ((ObjectNode) projection.path("bindingRefs").get(0)).put("environment", "other");
        assertThatThrownBy(() -> new RunService(null, runtime, null, null).resolveConfig("demo",
                Json.tree(Map.of("configReleaseId", "release-b")), Actor.current())).isInstanceOf(PlatformException.class);
    }

    @Test void selectedBindingDigestIsStableWhenAnUnrelatedBindingOrGrantIsAdded() {
        var profiles = mock(ConnectionProfileRepository.class);
        var grants = mock(ConnectionGrantRepository.class);
        var revision = new LinkedHashMap<String, Object>(Json.mapper().convertValue(
                ApprovedConnectionGateTest.payload("crm-prod", 1, "b".repeat(64), "BEARER", List.of("customers.read")), Map.class));
        revision.put("authScheme", Map.of("kind", "BEARER")); revision.put("declarationSha256", "a".repeat(64));
        when(profiles.profile(any(), eq("crm-prod"))).thenReturn(Optional.of(Map.of("status", "APPROVED", "currentRev", 1,
                "revisions", List.of(revision))));
        var bindingA = Map.<String, Object>of("bindingId", "crm-a", "bindingRev", 1, "environment", "dev",
                "baseEndpoint", "http://127.0.0.1:18090", "secretRef", "env:AGENT_SECRET_A", "declarationSha256", "c".repeat(64));
        var bindingB = Map.<String, Object>of("bindingId", "crm-b", "bindingRev", 1, "environment", "dev",
                "baseEndpoint", "http://127.0.0.1:18091", "secretRef", "env:AGENT_SECRET_B", "declarationSha256", "d".repeat(64));
        var grant = Map.<String, Object>of("grantId", 1L, "grantee", "skill.crm-read", "allowedUses", List.of("read"));
        when(profiles.pinnedBindingRevisions(any(), anyString(), anyInt())).thenReturn(List.of(bindingA), List.of(bindingA, bindingB));
        when(grants.activeGrants(any(), anyString(), anyInt())).thenReturn(List.of(grant), List.of(grant,
                Map.of("grantId", 2L, "grantee", "skill.other", "allowedUses", List.of("write"))));
        var resolver = new ApprovedConnections(profiles, grants);
        var before = resolver.resolve("crm-prod", 1, "dev", "crm-a", 1, "skill.crm-read");
        var after = resolver.resolve("crm-prod", 1, "dev", "crm-a", 1, "skill.crm-read");
        assertThat(after.get("bindings")).isEqualTo(List.of(bindingA));
        assertThat(after.get("resolutionDigest")).isEqualTo(before.get("resolutionDigest"));
        when(profiles.pinnedBindingRevisions(any(), anyString(), anyInt())).thenReturn(List.of(bindingB));
        assertThatThrownBy(() -> resolver.resolve("crm-prod", 1, "dev", "crm-a", 1, "skill.crm-read"))
                .isInstanceOf(PlatformException.class);
    }

    @Test void fullExistingSkillCodeCanBeGrantedThroughTheManagementDomain() {
        var profiles = mock(ConnectionProfileRepository.class);
        var grants = mock(ConnectionGrantRepository.class);
        when(profiles.profile(any(), anyString())).thenReturn(Optional.of(Map.of("status", "APPROVED", "currentRev", 1,
                "revisions", List.of(Map.of("profileRev", 1, "allowedUses", List.of("read"))))));
        String grantee = "skill.C" + "r".repeat(61) + "_1";
        when(grants.activeGrant(any(), anyString(), anyInt(), anyString())).thenReturn(Optional.empty());
        when(grants.grant(any(), anyString(), anyInt(), eq(grantee), eq(List.of("read"))))
                .thenReturn(Map.of("grantee", grantee, "grantId", 1L));
        var out = new ConnectionGrants(profiles, grants).grant("crm-prod", 1,
                Map.of("grantee", grantee, "allowedUses", List.of("read")));
        assertThat(out.get("grantee")).isEqualTo(grantee);
    }
}
