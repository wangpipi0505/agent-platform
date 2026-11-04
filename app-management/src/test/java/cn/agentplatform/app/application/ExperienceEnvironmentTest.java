package cn.agentplatform.app.application;

import cn.agentplatform.app.api.ExperienceController;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.PlatformException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExperienceEnvironmentTest {
    private void actor() {
        var jwt=Jwt.withTokenValue("review-test").header("alg","HS256").subject("u")
                .claim("tenant_id","t").claim("roles",List.of("USER"))
                .claim("attributes",Map.of("environment",List.of("staging"))).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void missingQueryUsesVerifiedEnvironmentRatherThanProduction() {
        actor(); var releases=mock(IntegrationConfigReleases.class);
        when(releases.experience(any(Actor.class),eq("orders"),eq("staging"))).thenReturn(Map.of());
        new ExperienceController(releases).experience("orders",null);
        verify(releases).experience(any(Actor.class),eq("orders"),eq("staging"));
    }
    @Test void requestCannotOverrideTicketEnvironment() {
        actor(); var releases=mock(IntegrationConfigReleases.class);
        var error=assertThrows(PlatformException.class,()->new ExperienceController(releases).experience("orders","production"));
        assertEquals(403,error.status()); verifyNoInteractions(releases);
    }
}
