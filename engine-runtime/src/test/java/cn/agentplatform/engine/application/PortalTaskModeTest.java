package cn.agentplatform.engine.application;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.authority.AuthorityContext;
import cn.agentplatform.common.authority.DelegationRef;
import cn.agentplatform.common.authority.OperationBinding;
import cn.agentplatform.common.authority.ResourceBinding;
import cn.agentplatform.common.authority.ServiceCall;
import cn.agentplatform.common.authority.SubjectKey;
import cn.agentplatform.common.authority.VerifiedAuthority;
import cn.agentplatform.engine.infra.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class PortalTaskModeTest {
    private static final Actor USER = new Actor("demo", "alice", Set.of("USER"),
            Map.of("bizSystemCode", Set.of("agent-platform"), "userId", Set.of("alice")));
    private static final String ACTION = "proxy.engine.post.TaskController.create";
    private final Tasks tasks = new Tasks(mock(TraceStore.class));

    @AfterEach
    void clearRequest() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void portalPlanReachesOrdinaryTaskValidation() {
        try (var ignored = bind("PORTAL", "portal-app@1")) {
            assertEquals("TASK_GOAL_REQUIRED", rejection("portal-app"));
        }
    }

    @Test
    void integrationAndUnmatchedPortalRequestsKeepTheAgentOnlyRule() {
        try (var ignored = bind("INTEGRATION", "portal-app@1")) {
            assertEquals("SERVICE_AGENT_ONLY", rejection("portal-app"));
        }
        try (var ignored = bind("PORTAL", "another-app@1")) {
            assertEquals("SERVICE_AGENT_ONLY", rejection("portal-app"));
        }
        SecurityContextHolder.clearContext();
        assertEquals("SERVICE_AGENT_ONLY", rejection("portal-app"));
    }

    private String rejection(String code) {
        var request = Json.tree(Map.of("requestId", "plan-request", "mode", "PLAN"));
        return assertThrows(PlatformException.class, () -> tasks.create(code, request, USER)).code();
    }

    private static AuthorityContext bind(String origin, String version) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                Jwt.withTokenValue("verified-test-service-ticket").header("alg", "RS256")
                        .subject(USER.principalId()).claim("tenant_id", USER.tenantId())
                        .claim("token_use", "service").claim("authority_mode", "delegated").build()));
        var subject = new SubjectKey(USER.tenantId(), USER.principalId());
        var issuedAt = Instant.now();
        var expiresAt = issuedAt.plusSeconds(30);
        var resource = new ResourceBinding("app-version", version, Digest.sha256(version));
        var delegation = new DelegationRef("task-grant", subject, expiresAt);
        var call = new ServiceCall("engine", ACTION, resource);
        var operation = new OperationBinding("task-operation", origin, origin, issuedAt,
                expiresAt, delegation.value(), subject, resource, resource.versionDigest());
        return AuthorityContext.bind(new VerifiedAuthority(USER, "gateway", call, delegation,
                "test", issuedAt, expiresAt, "task-decision", operation));
    }
}
