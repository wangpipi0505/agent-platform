package cn.agentplatform.engine.application;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.infra.RuntimeClients;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** INT-06：配置版本只由引擎向 app 的激活投影核验；客户端提议值要么命中激活版本，要么被拒。 */
class RunConfigReferenceTest {
    private static final Actor ACTOR =
            new Actor("tenant-a", "alice", Set.of("ADMIN"), Map.of());
    private static final String DIGEST =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String EXPERIENCE =
            "/v1/apps/demo/experience?environment=production";

    private static RunService service(RuntimeClients clients) {
        return new RunService(null, clients, null, null);
    }

    private static JsonNode projection(
            String appCode,
            String releaseId,
            String digest,
            String profileId,
            long profileRev,
            String environment) {
        return Json.read(
                "{"
                        + "\"schemaVersion\":\"v1\","
                        + "\"appCode\":\""
                        + appCode
                        + "\","
                        + "\"experienceRev\":3,"
                        + "\"configReleaseRef\":{\"configReleaseId\":\""
                        + releaseId
                        + "\",\"digest\":\""
                        + digest
                        + "\"},"
                        + "\"integrationProfileRef\":{\"profileId\":\""
                        + profileId
                        + "\",\"profileRev\":"
                        + profileRev
                        + ",\"mode\":\"L2\",\"environment\":\""
                        + environment
                        + "\"}}");
    }

    private static PlatformException rejected(Runnable call) {
        return assertThrows(PlatformException.class, call::run);
    }

    @Test
    void legacyRequestWithoutReleaseKeepsOldBehaviorAndNeverCallsTheResolver() {
        var clients = mock(RuntimeClients.class);
        var service = service(clients);

        assertNull(service.resolveConfig("demo", Json.read("{\"inputs\":{}}"), ACTOR));
        assertNull(service.resolveConfig("demo", null, ACTOR));
        verifyNoInteractions(clients);
    }

    @Test
    void trustedReleaseIsResolvedFromTheActiveProjection() {
        var clients = mock(RuntimeClients.class);
        when(clients.get("app", EXPERIENCE, ACTOR))
                .thenReturn(projection("demo", "cr_1", DIGEST, "prof_1", 2, "production"));

        var config =
                service(clients)
                        .resolveConfig("demo", Json.read("{\"configReleaseId\":\"cr_1\"}"), ACTOR);

        assertNotNull(config);
        assertEquals("cr_1", config.releaseId());
        assertEquals(DIGEST, config.digest());
        assertEquals("production", config.environment());
        assertEquals("integration-profile", config.environmentBindingRevs().get(0).path("kind").asText());
        assertEquals("prof_1", config.environmentBindingRevs().get(0).path("profileId").asText());
        assertEquals(2, config.environmentBindingRevs().get(0).path("profileRev").asLong());
    }

    @Test
    void requestedReleaseMustMatchTheActiveOne() {
        var clients = mock(RuntimeClients.class);
        when(clients.get("app", EXPERIENCE, ACTOR))
                .thenReturn(projection("demo", "cr_1", DIGEST, "prof_1", 2, "production"));

        var rejected =
                rejected(
                        () ->
                                service(clients)
                                        .resolveConfig(
                                                "demo",
                                                Json.read("{\"configReleaseId\":\"cr_forged\"}"),
                                                ACTOR));

        assertEquals(409, rejected.status());
        assertEquals("CONFIG_RELEASE_NOT_ACTIVE", rejected.code());
    }

    @Test
    void projectionMustBelongToTheRequestedApplication() {
        var clients = mock(RuntimeClients.class);
        when(clients.get("app", EXPERIENCE, ACTOR))
                .thenReturn(projection("other", "cr_1", DIGEST, "prof_1", 2, "production"));

        var rejected =
                rejected(
                        () ->
                                service(clients)
                                        .resolveConfig(
                                                "demo",
                                                Json.read("{\"configReleaseId\":\"cr_1\"}"),
                                                ACTOR));

        assertEquals("CONFIG_RELEASE_NOT_ACTIVE", rejected.code());
    }

    @Test
    void releaseWithoutAProfileRevisionCannotFixEnvironmentBindings() {
        var clients = mock(RuntimeClients.class);
        when(clients.get("app", EXPERIENCE, ACTOR))
                .thenReturn(
                        Json.read(
                                "{\"appCode\":\"demo\","
                                        + "\"configReleaseRef\":{\"configReleaseId\":\"cr_1\","
                                        + "\"digest\":\""
                                        + DIGEST
                                        + "\"}}"));

        var rejected =
                rejected(
                        () ->
                                service(clients)
                                        .resolveConfig(
                                                "demo",
                                                Json.read("{\"configReleaseId\":\"cr_1\"}"),
                                                ACTOR));

        assertEquals(409, rejected.status());
        assertEquals("CONFIG_RELEASE_NOT_ACTIVE", rejected.code());
    }

    @Test
    void releaseEnvironmentMustMatchTheEngineEnvironment() {
        var clients = mock(RuntimeClients.class);
        when(clients.get("app", EXPERIENCE, ACTOR))
                .thenReturn(projection("demo", "cr_1", DIGEST, "prof_1", 2, "staging"));

        var rejected =
                rejected(
                        () ->
                                service(clients)
                                        .resolveConfig(
                                                "demo",
                                                Json.read("{\"configReleaseId\":\"cr_1\"}"),
                                                ACTOR));

        assertEquals("CONFIG_RELEASE_NOT_ACTIVE", rejected.code());
        assertTrue(rejected.getMessage().contains("环境"));
    }

    @Test
    void nonHexDigestIsNotAcceptedAsAReleaseReference() {
        var clients = mock(RuntimeClients.class);
        when(clients.get("app", EXPERIENCE, ACTOR))
                .thenReturn(projection("demo", "cr_1", "sha256:abc", "prof_1", 2, "production"));

        var rejected =
                rejected(
                        () ->
                                service(clients)
                                        .resolveConfig(
                                                "demo",
                                                Json.read("{\"configReleaseId\":\"cr_1\"}"),
                                                ACTOR));

        assertEquals("CONFIG_RELEASE_NOT_ACTIVE", rejected.code());
    }

    @Test
    void missingClientsIsAnExplainableBlock() {
        var rejected =
                rejected(
                        () ->
                                service(null)
                                        .resolveConfig(
                                                "demo",
                                                Json.read("{\"configReleaseId\":\"cr_1\"}"),
                                                ACTOR));

        assertEquals(503, rejected.status());
        assertEquals("RUN_CONFIG_UNAVAILABLE", rejected.code());
    }

    @Test
    void activationLookupFailurePropagatesAndNoRunCanStart() {
        var clients = mock(RuntimeClients.class);
        when(clients.get(eq("app"), eq(EXPERIENCE), any(Actor.class)))
                .thenThrow(new PlatformException(503, "SERVICE_UNAVAILABLE", "app 暂不可用"));

        var rejected =
                rejected(
                        () ->
                                service(clients)
                                        .resolveConfig(
                                                "demo",
                                                Json.read("{\"configReleaseId\":\"cr_1\"}"),
                                                ACTOR));

        assertEquals(503, rejected.status());
    }
}
