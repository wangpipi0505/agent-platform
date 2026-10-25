package cn.agentplatform.common;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.*;

public class CommonBoundaryTest {
    @Test
    void rejectsMissingAuthenticatedIdentity() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(Actor::current).isInstanceOf(PlatformException.class);
    }

    @Test
    void readsOnlyVerifiedJwtClaimsAndChecksRoles() {
        Jwt jwt =
                Jwt.withTokenValue("verified-by-decoder")
                        .header("alg", "HS256")
                        .subject("alice")
                        .claim("tenant_id", "tenant-a")
                        .claim("roles", List.of("EDITOR"))
                        .claim("attributes", Map.of("region", List.of("A")))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(60))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        try {
            Actor actor = Actor.current();
            assertThat(actor.tenantId()).isEqualTo("tenant-a");
            actor.requireRole("EDITOR");
            assertThatThrownBy(() -> actor.requireRole("APPROVER"))
                    .isInstanceOf(PlatformException.class);
            assertThat(actor.attributes().get("region")).containsExactly("A");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void usesCanonicalJsonForContentIdentity() {
        assertThat(Digest.sha256(Json.canonical(Map.of("z", 1, "a", 2))))
                .isEqualTo(Digest.sha256(Json.canonical(Map.of("a", 2, "z", 1))));
        assertThat(Json.read(Json.write(Map.of("中文", "保存"))).get("中文").asText()).isEqualTo("保存");
    }

    @Test
    void validatesInternalTicketAndPreservesActorBoundary() throws Exception {
        cn.agentplatform.common.authority.ServiceTokensDeploymentModeTest.runScenario("common-boundary");
    }

    public static void legacyTicketAssertions() {
        var names=List.of("agent.jwt.secret","agent.security.mode","spring.profiles.active");
        var before=new HashMap<String,String>();names.forEach(n->before.put(n,System.getProperty(n)));
        try {
        System.setProperty("agent.jwt.secret", "abcdefghijklmnopqrstuvwxyz0123456789-TEST-ONLY");
        System.setProperty("agent.security.mode","test");
        System.setProperty("spring.profiles.active","test");
        Actor actor =
                new Actor("tenant-a", "alice", Set.of("EDITOR"), Map.of("region", Set.of("A")));
        String ticket = ServiceTokens.issue(actor);
        JsonNode claims =
                Json.read(new String(Base64.getUrlDecoder().decode(ticket.split("\\.")[1])));
        assertThat(claims.get("tenant_id").asText()).isEqualTo("tenant-a");
        assertThat(claims.get("sub").asText()).isEqualTo("alice");
        assertThat(claims.get("aud").asText()).isEqualTo("agent-platform-internal");
        assertThat(claims.get("roles").toString())
                .doesNotContain("ADMIN")
                .contains("EDITOR", "SERVICE");
        } finally { before.forEach((k,v)->{if(v==null)System.clearProperty(k);else System.setProperty(k,v);}); }
    }
}
