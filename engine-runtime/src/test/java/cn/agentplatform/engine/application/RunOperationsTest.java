package cn.agentplatform.engine.application;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.PlatformException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class RunOperationsTest {
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsWindowBeforeReadingTheDatabase() {
        Actor actor = new Actor("tenant-a", "admin", java.util.Set.of("ADMIN"), Map.of());
        Jwt jwt =
                Jwt.withTokenValue("test")
                        .header("alg", "none")
                        .subject(actor.principalId())
                        .claim("tenant_id", actor.tenantId())
                        .claim("roles", List.of("ADMIN"))
                        .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt, List.of(), actor.principalId()));
        var operations = new RunOperations(mock(JdbcTemplate.class));
        var error = assertThrows(PlatformException.class, () -> operations.overview(4));
        assertEquals(400, error.status());
        assertEquals("OPS_WINDOW_INVALID", error.code());
    }
}
