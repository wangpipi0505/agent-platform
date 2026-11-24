package cn.agentplatform.app.application;

import cn.agentplatform.app.api.AppController;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.authority.AuthorityProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PlatformBuildEntryTest {
    private final AppService apps = mock(AppService.class);
    private final AuthorityProperties iam =
            new AuthorityProperties("iam", "app", null, null, null, null, null, false, List.of());

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void externalAdminClaimAloneCannotEditAnApplication() {
        var jwt = Jwt.withTokenValue("verified-fixture").header("alg", "RS256")
                .subject("operator").claim("tenant_id", "tenant-a").claim("roles", List.of("ADMIN")).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        var controller = new AppController(apps, mock(TemplateService.class), iam,
                new PlatformAccessService(mock(JdbcTemplate.class)));
        assertThatThrownBy(() -> controller.metadata("app-a", Map.of("name", "New name")))
                .isInstanceOfSatisfying(PlatformException.class, error -> assertThat(error.status()).isEqualTo(403));
        verifyNoInteractions(apps);
    }
}
