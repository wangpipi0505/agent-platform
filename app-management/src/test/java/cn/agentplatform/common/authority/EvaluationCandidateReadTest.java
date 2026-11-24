package cn.agentplatform.common.authority;

import cn.agentplatform.app.api.AppController;
import cn.agentplatform.app.application.AppService;
import cn.agentplatform.app.application.PlatformAccessService;
import cn.agentplatform.app.application.TemplateService;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Digest;
import cn.agentplatform.common.PlatformException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EvaluationCandidateReadTest {
    private static final String SHA = Digest.sha256("evaluation-candidate");
    private final AppService apps = mock(AppService.class);
    private final PlatformAccessService access = mock(PlatformAccessService.class);
    private final AppController controller = new AppController(apps, mock(TemplateService.class),
            new AuthorityProperties("iam", "app", null, null, null, null, null, false, List.of()), access);

    @Test
    void readsOnlyTheCandidateBoundToTheVerifiedUserDelegation() {
        assertThatThrownBy(() -> controller.candidate("ops", 7, SHA))
                .isInstanceOfSatisfying(PlatformException.class, error -> assertThat(error.status()).isEqualTo(403));

        var actor = new Actor("tenant", "builder", Set.of("USER"), Map.of());
        var subject = new SubjectKey(actor.tenantId(), actor.principalId());
        var resource = new ResourceBinding("evaluation-candidate", "ops@7", SHA);
        var now = Instant.now();
        var grant = new DelegationRef("fixture-grant", subject, now.plusSeconds(45));
        var operation = new OperationBinding("fixture-operation", "PORTAL", "PORTAL", now, now.plusSeconds(30),
                grant.value(), subject, resource, SHA);
        var delegated = new VerifiedAuthority(actor, "monitor", new ServiceCall("app", "app.candidate.read", resource),
                grant, "v1", now, now.plusSeconds(10), "decision", operation);
        try (var frame = AuthorityContext.bind(delegated, () -> delegated)) {
            assertThatThrownBy(() -> controller.candidate("ops", 8, SHA))
                    .isInstanceOfSatisfying(PlatformException.class, error -> assertThat(error.status()).isEqualTo(403));
            verifyNoInteractions(apps);
        }
        try (var frame = AuthorityContext.bind(delegated, () -> delegated)) {
            when(apps.frozenCandidate("ops", 7, SHA)).thenReturn(Map.of("code", "ops"));
            controller.candidate("ops", 7, SHA);
            verify(access).require(actor, PlatformAccessService.BUILD, "ops");
            verify(apps).frozenCandidate("ops", 7, SHA);
        }
    }
}
