package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

/** SEM-07.1 客户端：condition@1.0 响应必须与请求同址同源，畸形一律 502。 */
class SemanticDecisionClientTest {
    private static final UUID RELEASE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String DIGEST = "a".repeat(64);

    @Test
    void mapsConditionsAndUrl() {
        var clients = new StubClients(body("HOLDS", true, "\"conditionKind\":\"EXISTS\""));
        var result = new SemanticDecisionClient(clients, "http://or.local/").evaluate(RELEASE, Map.of());
        assertTrue(result.allHold());
        assertEquals(1, result.conditions().size());
        assertEquals("EXISTS", result.conditions().getFirst().conditionKind());
        assertNull(result.firstFailure());
        assertEquals("http://or.local/onto/releases/" + RELEASE + "/semantic/conditions/evaluate", clients.url);
    }

    @Test
    void rejectsReleaseMismatch() {
        var node = body("HOLDS", true, "\"conditionKind\":\"EXISTS\"");
        ((com.fasterxml.jackson.databind.node.ObjectNode) node.path("releaseRef"))
                .put("releaseId", "99999999-9999-9999-9999-999999999999");
        assertMalformed(node);
    }

    @Test
    void rejectsUnknownWithoutReason() {
        assertMalformed(body("UNKNOWN", false, "\"conditionKind\":\"STATE\""));
    }

    @Test
    void rejectsInconsistentAllHold() {
        assertMalformed(body("NOT_HOLDS", true, "\"conditionKind\":\"RELATION\""));
    }

    @Test
    void rejectsUnregisteredKind() {
        assertMalformed(body("HOLDS", true, "\"conditionKind\":\"SCRIPT\""));
    }

    @Test
    void rejectsIncompleteReleaseRef() {
        JsonNode node = body("HOLDS", true, "\"conditionKind\":\"EXISTS\"");
        ((com.fasterxml.jackson.databind.node.ObjectNode) node.path("releaseRef"))
                .put("contentDigest", "short");
        assertMalformed(node);
    }

    private static void assertMalformed(JsonNode node) {
        var failure = assertThrows(PlatformException.class,
                () -> new SemanticDecisionClient(new StubClients(node), "http://or").evaluate(RELEASE, Map.of()));
        assertEquals(502, failure.status());
        assertEquals("DEPENDENCY_UNAVAILABLE", failure.code());
    }

    private static JsonNode body(String result, boolean allHold, String extra) {
        String json = """
                {
                  "profile":"condition@1.0",
                  "schemaVersion":"1.0",
                  "releaseRef":{"spaceCode":"space","releaseId":"%s","version":"v3","contentDigest":"%s"},
                  "checkedAt":"2026-11-04T03:00:00Z",
                  "conditions":[{%s,"result":"%s"%s}],
                  "allHold":%s
                }
                """.formatted(
                RELEASE,
                DIGEST,
                extra,
                result,
                "UNKNOWN".equals(result) ? "" : "",
                allHold);
        return Json.read(json);
    }

    static class StubClients extends SkillClients {
        private final JsonNode data;
        String url;

        StubClients(JsonNode data) {
            this.data = data;
        }

        @Override
        public JsonNode post(String url, Object body) {
            this.url = url;
            return data;
        }
    }
}
