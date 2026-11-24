package cn.agentplatform.engine.infra;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Digest;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.authority.DelegationRef;
import cn.agentplatform.common.authority.OperationBinding;
import cn.agentplatform.common.authority.ResourceBinding;
import cn.agentplatform.common.authority.ServiceCall;
import cn.agentplatform.common.authority.SubjectKey;
import cn.agentplatform.common.authority.VerifiedAuthority;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationRunAuthorityTest {
    private static final ResourceBinding CANDIDATE =
            new ResourceBinding("evaluation-candidate", "ops@7", Digest.sha256("evaluation-fixture"));

    @Test
    void monitorMayAdmitOnlyAPortalEvaluationCandidate() {
        assertThatCode(() -> RunAuthorityStore.requirePurpose(
                grant("monitor", "evaluation.run", CANDIDATE, "PORTAL"), false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> RunAuthorityStore.requirePurpose(
                grant("gateway", "evaluation.run", CANDIDATE, "PORTAL"), false))
                .isInstanceOf(PlatformException.class);
        assertThatThrownBy(() -> RunAuthorityStore.requirePurpose(
                grant("monitor", "evaluation.run", CANDIDATE, "INTEGRATION"), false))
                .isInstanceOf(PlatformException.class);
        assertThatThrownBy(() -> RunAuthorityStore.requirePurpose(
                grant("monitor", "evaluation.run", new ResourceBinding("app-version", CANDIDATE.id(),
                        CANDIDATE.versionDigest()), "PORTAL"), false))
                .isInstanceOf(PlatformException.class);
    }

    private static VerifiedAuthority grant(String caller, String action, ResourceBinding resource, String origin) {
        var actor = new Actor("tenant-a", "builder", Set.of("USER"), Map.of());
        var subject = new SubjectKey(actor.tenantId(), actor.principalId());
        var issued = Instant.now();
        var expires = issued.plusSeconds(30);
        var delegation = new DelegationRef("fixture-grant", subject, expires);
        var operation = new OperationBinding("fixture-operation", origin, origin, issued, expires,
                delegation.value(), subject, resource, resource.versionDigest());
        return new VerifiedAuthority(actor, caller, new ServiceCall("engine", action, resource), delegation,
                "v1", issued, expires, "fixture-decision", operation);
    }
}
