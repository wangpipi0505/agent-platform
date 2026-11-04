package cn.agentplatform.engine.application;

import cn.agentplatform.common.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** INT-06：会话固定配置版本后，轮次不得改挂到另一套配置；未固定会话不接受轮次临时指定。 */
class ChatSessionConfigPinTest {
    private static final String DIGEST =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private final ChatSessions sessions = new ChatSessions(null, null, null);

    private static Map<String, Object> pinned(String releaseId, String digest, String environment) {
        Map<String, Object> row = new HashMap<>();
        row.put("config_release_id", releaseId);
        row.put("config_digest", digest);
        row.put("config_environment", environment);
        row.put("environment_binding_revs", "[]");
        return row;
    }

    private static PlatformException rejected(Runnable call) {
        return assertThrows(PlatformException.class, call::run);
    }

    @Test
    void legacySessionWithoutConfigurationAcceptsLegacyTurns() {
        assertNull(sessions.pinnedConfig(Map.of(), Json.read("{\"message\":\"你好\"}")));
        assertNull(sessions.pinnedConfig(Map.of(), null));
    }

    @Test
    void legacySessionRejectsATurnThatIntroducesAConfiguration() {
        var rejected =
                rejected(
                        () ->
                                sessions.pinnedConfig(
                                        Map.of(),
                                        Json.read("{\"configReleaseId\":\"cr_1\"}")));

        assertEquals(409, rejected.status());
        assertEquals("CONFIG_REV_CONFLICT", rejected.code());
    }

    @Test
    void pinnedSessionRejectsATurnPinnedToAnotherRelease() {
        var rejected =
                rejected(
                        () ->
                                sessions.pinnedConfig(
                                        pinned("cr_1", DIGEST, "production"),
                                        Json.read("{\"configReleaseId\":\"cr_2\"}")));

        assertEquals(409, rejected.status());
        assertEquals("CONFIG_REV_CONFLICT", rejected.code());
    }

    @Test
    void pinnedSessionReturnsItsFixedReferenceForEveryTurn() {
        JsonNode req = Json.read("{\"configReleaseId\":\"cr_1\"}");

        var config = sessions.pinnedConfig(pinned("cr_1", DIGEST, "production"), req);

        assertNotNull(config);
        assertEquals("cr_1", config.releaseId());
        assertEquals(DIGEST, config.digest());
        assertEquals("production", config.environment());
        assertTrue(config.environmentBindingRevs().isArray());
        assertEquals(config, sessions.pinnedConfig(pinned("cr_1", DIGEST, "production"), null));
    }

    @Test
    void pinnedSessionWithoutIntegrityFieldsCannotRun() {
        var row = pinned("cr_1", DIGEST, "production");
        row.remove("config_digest");

        var rejected =
                rejected(() -> sessions.pinnedConfig(row, Json.read("{\"message\":\"你好\"}")));

        assertEquals("CONFIG_REV_CONFLICT", rejected.code());
        assertTrue(rejected.getMessage().contains("完整性"));
    }
}
