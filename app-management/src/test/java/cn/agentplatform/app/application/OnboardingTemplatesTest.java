package cn.agentplatform.app.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.agentplatform.app.infra.CapabilityPackageQueryRepository;
import cn.agentplatform.app.infra.ConfigWriteLog;
import cn.agentplatform.app.infra.OnboardingTemplateRepository;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.PlatformException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** ONB-01/02 服务端行为：模板目录、引用复核、幂等与预检归一。 */
class OnboardingTemplatesTest {

    private static final Actor ADMIN = new Actor("tenant-a", "admin", java.util.Set.of("ADMIN"), Map.of());
    private static final String SHA = "a".repeat(64);

    private OnboardingTemplateRepository store;
    private ConfigWriteLog writes;
    private AssistantExperiences experiences;
    private CapabilityPackageQueryRepository packages;
    private OnboardingPreflightService preflight;
    private OnboardingTemplates templates;

    @BeforeEach
    void setUp() {
        store = mock(OnboardingTemplateRepository.class);
        writes = mock(ConfigWriteLog.class);
        experiences = mock(AssistantExperiences.class);
        packages = mock(CapabilityPackageQueryRepository.class);
        preflight = mock(OnboardingPreflightService.class);
        templates = new OnboardingTemplates(store, writes, experiences, packages, preflight);
    }

    @Test
    void templateKindsOtherThanQueryAreNotOpenYet() {
        Map<String, Object> body = body("FILE_ANALYSIS");

        assertThatThrownBy(() -> templates.create(ADMIN, body))
                .isInstanceOf(PlatformException.class)
                .extracting("code").isEqualTo("INVALID_INPUT");
        verify(store, never()).create(any(), anyString(), any(), anyString(), any());
    }

    @Test
    void unknownBodyFieldsAreRejectedBeforeAnyWrite() {
        Map<String, Object> body = body("QUERY");
        body.put("arbitraryScript", "alert(1)");

        assertThatThrownBy(() -> templates.create(ADMIN, body))
                .isInstanceOf(PlatformException.class)
                .extracting("code").isEqualTo("INVALID_INPUT");
        verify(store, never()).create(any(), anyString(), any(), anyString(), any());
    }

    @Test
    void createAcceptsOnlyUnpublishedCodeShapeAndRejectsExpectedRev() {
        Map<String, Object> body = body("QUERY");
        body.put("code", "Upper-Code");

        assertThatThrownBy(() -> templates.create(ADMIN, body))
                .isInstanceOf(PlatformException.class)
                .extracting("code").isEqualTo("INVALID_INPUT");

        Map<String, Object> withRev = body("QUERY");
        withRev.put("expectedRev", 1);
        assertThatThrownBy(() -> templates.create(ADMIN, withRev))
                .isInstanceOf(PlatformException.class)
                .extracting("code").isEqualTo("INVALID_INPUT");
    }

    @Test
    void sameRequestIdReplaysPreviousResponse() {
        Map<String, Object> previous = new LinkedHashMap<>(Map.of("code", "query_lookup", "currentRev", 1));
        when(writes.replay(eq("tenant-a"), eq("onboarding-template.create"), eq("query_lookup"),
                eq("req-0001"), anyString())).thenReturn(previous);

        Map<String, Object> result = templates.create(ADMIN, body("QUERY"));

        assertThat(result).containsEntry("replayed", true);
        verify(store, never()).create(any(), anyString(), any(), anyString(), any());
        verify(writes, never()).remember(anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void createDelegatesToRepositoryInsideTheControlledSurface() {
        when(writes.replay(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(null);
        when(store.create(any(), eq("query_lookup"), any(), eq("req-0001"), any()))
                .thenReturn(Map.of("code", "query_lookup", "currentRev", 1));

        Map<String, Object> result = templates.create(ADMIN, body("QUERY"));

        assertThat(result).containsEntry("code", "query_lookup");
        verify(writes).remember(eq("tenant-a"), eq("onboarding-template.create"), eq("query_lookup"),
                eq("req-0001"), anyString(), any());
    }

    @Test
    void publishStopsWhenTheCapabilityPackageDisappeared() {
        drafts();
        when(packages.detail("tenant-a", 11L))
                .thenThrow(new PlatformException(404, "CAPABILITY_PACKAGE_NOT_FOUND", "gone"));
        when(writes.replay(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(null);

        assertThatThrownBy(() -> templates.publish(ADMIN, "query_lookup", transition(1)))
                .isInstanceOf(PlatformException.class)
                .extracting("code").isEqualTo("CONFIG_REF_NOT_FOUND");
        verify(store, never()).transition(any(), anyString(), anyInt(), anyString(), anyString(),
                anyString(), any());
    }

    @Test
    void publishStopsWhenTheCapabilityPackageDigestChanged() {
        drafts();
        when(packages.detail("tenant-a", 11L)).thenReturn(Map.of("sha256", "b".repeat(64)));
        when(writes.replay(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(null);

        assertThatThrownBy(() -> templates.publish(ADMIN, "query_lookup", transition(1)))
                .isInstanceOf(PlatformException.class)
                .extracting("code").isEqualTo("INVALID_INPUT");
    }

    @Test
    void publishStopsWhenTheIntegrationProfileIsNotApproved() {
        drafts();
        when(packages.detail("tenant-a", 11L)).thenReturn(Map.of("sha256", SHA));
        when(store.approvedIntegrationRevisionExists(ADMIN, "query_app", "crm", "prod", "L2"))
                .thenReturn(false);
        when(writes.replay(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(null);

        assertThatThrownBy(() -> templates.publish(ADMIN, "query_lookup", transition(1)))
                .isInstanceOf(PlatformException.class)
                .extracting("code").isEqualTo("CONFIG_STATE_TRANSITION_INVALID");
    }

    @Test
    void publishMovesTheCurrentDraftRevisionToPublished() {
        drafts();
        when(packages.detail("tenant-a", 11L)).thenReturn(Map.of("sha256", SHA));
        when(store.approvedIntegrationRevisionExists(ADMIN, "query_app", "crm", "prod", "L2"))
                .thenReturn(true);
        when(writes.replay(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(null);
        when(store.transition(any(), eq("query_lookup"), eq(1), eq("DRAFT"), eq("PUBLISHED"),
                eq("req-000p"), any())).thenReturn(Map.of("code", "query_lookup", "status", "PUBLISHED"));

        Map<String, Object> result = templates.publish(ADMIN, "query_lookup", transition(1));

        assertThat(result).containsEntry("status", "PUBLISHED");
        verify(experiences).requireApproved(ADMIN, "query_app", 3);
    }

    @Test
    void publishRejectsAConcurrentRevisionChange() {
        when(store.state(ADMIN, "query_lookup", 1))
                .thenReturn(new OnboardingTemplateRepository.State(2, "DRAFT", true));

        assertThatThrownBy(() -> templates.publish(ADMIN, "query_lookup", transition(1)))
                .isInstanceOf(PlatformException.class)
                .extracting("code").isEqualTo("CONFIG_REV_CONFLICT");
    }

    @Test
    void disableIsOnlyAllowedForDraftOrPublishedTemplates() {
        when(store.state(ADMIN, "query_lookup", 1))
                .thenReturn(new OnboardingTemplateRepository.State(1, "DISABLED", true));

        assertThatThrownBy(() -> templates.disable(ADMIN, "query_lookup", transition(1)))
                .isInstanceOf(PlatformException.class)
                .extracting("code").isEqualTo("CONFIG_STATE_TRANSITION_INVALID");
    }

    @Test
    void preflightReturnsTheSupportedSubsetAndAStablePlanDigest() {
        when(store.state(ADMIN, "query_lookup", 2))
                .thenReturn(new OnboardingTemplateRepository.State(2, "DRAFT", true));
        when(store.revision(ADMIN, "query_lookup", 2)).thenReturn(revision());
        when(preflight.preview(eq(ADMIN), anyString())).thenAnswer(call -> preview());

        Map<String, Object> first = templates.preflight(ADMIN, "query_lookup", 2, preflightBody());
        Map<String, Object> second = templates.preflight(ADMIN, "query_lookup", 2, preflightBody());

        assertThat(first).containsEntry("templateCode", "query_lookup")
                .containsEntry("templateVersion", 2)
                .containsEntry("zeroCreated", true)
                .containsEntry("supportedCount", 1);
        assertThat(String.valueOf(first.get("planSha256"))).matches("^[0-9a-f]{64}$");
        assertThat(first.get("planSha256")).isEqualTo(second.get("planSha256"));
    }

    @Test
    void preflightConnectionIsOptionalButChangesThePlanDigest() {
        when(store.state(ADMIN, "query_lookup", 2))
                .thenReturn(new OnboardingTemplateRepository.State(2, "DRAFT", true));
        when(store.revision(ADMIN, "query_lookup", 2)).thenReturn(revision());
        when(preflight.preview(eq(ADMIN), anyString())).thenAnswer(call -> preview());

        Map<String, Object> bare = new LinkedHashMap<>();
        bare.put("document", "openapi: 3.0.0");
        Map<String, Object> withoutConnection = templates.preflight(ADMIN, "query_lookup", 2, bare);
        Map<String, Object> withConnection = templates.preflight(ADMIN, "query_lookup", 2, preflightBody());

        assertThat(withoutConnection.get("planSha256"))
                .isNotEqualTo(withConnection.get("planSha256"));
    }

    @Test
    void preflightRefusesDisabledTemplates() {
        when(store.state(ADMIN, "query_lookup", 1))
                .thenReturn(new OnboardingTemplateRepository.State(1, "DISABLED", true));

        assertThatThrownBy(() -> templates.preflight(ADMIN, "query_lookup", 1, preflightBody()))
                .isInstanceOf(PlatformException.class)
                .extracting("code").isEqualTo("CONFIG_STATE_TRANSITION_INVALID");
    }

    @Test
    void preflightPropagatesAnUnreachableSkillService() {
        when(store.state(ADMIN, "query_lookup", 1))
                .thenReturn(new OnboardingTemplateRepository.State(1, "DRAFT", true));
        when(store.revision(ADMIN, "query_lookup", 1)).thenReturn(revision());
        when(preflight.preview(any(), anyString()))
                .thenThrow(new PlatformException(502, "DEPENDENCY_UNAVAILABLE", "不可用"));

        assertThatThrownBy(() -> templates.preflight(ADMIN, "query_lookup", 1, preflightBody()))
                .isInstanceOf(PlatformException.class)
                .extracting("code").isEqualTo("DEPENDENCY_UNAVAILABLE");
    }

    private void drafts() {
        when(store.state(ADMIN, "query_lookup", 1))
                .thenReturn(new OnboardingTemplateRepository.State(1, "DRAFT", true));
        when(store.revision(ADMIN, "query_lookup", 1)).thenReturn(revision());
        when(store.view(ADMIN, "query_lookup")).thenReturn(Optional.of(Map.of("code", "query_lookup")));
    }

    private static Map<String, Object> revision() {
        Map<String, Object> revision = new HashMap<>();
        revision.put("sourcePackageVersionId", 11L);
        revision.put("sourcePackageSha256", SHA);
        revision.put("appCode", "query_app");
        revision.put("experience", Map.of("experienceRev", 3));
        revision.put("integration", Map.of("bizSystemCode", "crm", "environment", "prod", "mode", "L2"));
        revision.put("declarationSha256", "c".repeat(64));
        return revision;
    }

    private static Map<String, Object> preview() {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("operationId", "getPet");
        operation.put("method", "GET");
        operation.put("path", "/pets/{id}");
        operation.put("supported", true);
        operation.put("requiresAuth", true);
        operation.put("reasons", List.of());
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("operations", List.of(operation));
        preview.put("warnings", List.of());
        preview.put("documentSha256", "d".repeat(64));
        preview.put("total", 1);
        preview.put("supportedCount", 1);
        preview.put("unsupported", List.of());
        preview.put("zeroCreated", true);
        return preview;
    }

    private static Map<String, Object> preflightBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("document", "openapi: 3.0.0");
        body.put("connection", Map.of("kind", "HTTP"));
        body.put("requestId", "req-00pf");
        return body;
    }

    private static Map<String, Object> transition(int expectedRev) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expectedRev", expectedRev);
        body.put("requestId", "req-000p");
        return body;
    }

    private static Map<String, Object> body(String kind) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "query_lookup");
        body.put("name", "查询助手");
        body.put("kind", kind);
        body.put("description", "把查询类应用包一层业务语言");
        body.put("sourcePackageVersionId", 11L);
        body.put("sourcePackageSha256", SHA);
        body.put("appCode", "query_app");
        body.put("experience", Map.of("appCode", "query_app", "experienceRev", 3));
        body.put("integration", Map.of("bizSystemCode", "crm", "environment", "prod", "mode", "L2"));
        body.put("requestId", "req-0001");
        return body;
    }
}
