package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

/**
 * SEM-03.1 客户端单测：只验证“响应必须与请求同址同源”这一防御边界。
 * 真实 OR 链路留给集成窗口，不在这里假定网络可用。
 */
class SemanticFactsClientTest {
    private static final UUID RELEASE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Map<String, Object> REF =
            Map.of(
                    "refKind", "space-object",
                    "spaceCode", "space-a",
                    "objectType", "Task",
                    "stableId", "task:A-1");

    @Test
    void mapsResolvedObjectAndKeepsRequestIdentity() {
        var clients = new StubClients(ok());
        var resolved = new SemanticFactsClient(clients, "http://or.local/").resolve(RELEASE, REF);
        assertEquals("space-object", resolved.refKind());
        assertEquals(RELEASE, resolved.releaseId());
        assertEquals("v3", resolved.version());
        assertEquals("Task", resolved.objectType());
        assertEquals("task:A-1", resolved.stableId());
        assertEquals(UUID.fromString("22222222-2222-2222-2222-222222222222"), resolved.classId());
        assertEquals("task_id", resolved.keyField());
        assertEquals("A-1", resolved.sourceKey());
        assertEquals("http://or.local/onto/releases/" + RELEASE + "/semantic/facts/resolve", clients.url);
        assertSame(REF, clients.sent);
    }

    @Test
    void rejectsResponseForAnotherRelease() {
        JsonNode body = ok();
        ((com.fasterxml.jackson.databind.node.ObjectNode) body)
                .put("releaseId", "99999999-9999-9999-9999-999999999999");
        var failure = assertThrows(PlatformException.class,
                () -> new SemanticFactsClient(new StubClients(body), "http://or").resolve(RELEASE, REF));
        assertEquals(502, failure.status());
        assertEquals("DEPENDENCY_UNAVAILABLE", failure.code());
    }

    @Test
    void rejectsResponseForAnotherSpace() {
        JsonNode body = ok();
        ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("spaceCode", "space-b");
        var failure = assertThrows(PlatformException.class,
                () -> new SemanticFactsClient(new StubClients(body), "http://or").resolve(RELEASE, REF));
        assertEquals("DEPENDENCY_UNAVAILABLE", failure.code());
    }

    @Test
    void rejectsMissingOrMalformedIdentity() {
        JsonNode missing = ok();
        ((com.fasterxml.jackson.databind.node.ObjectNode) missing).remove("stableId");
        assertEquals("DEPENDENCY_UNAVAILABLE",
                assertThrows(PlatformException.class,
                        () -> new SemanticFactsClient(new StubClients(missing), "http://or").resolve(RELEASE, REF))
                        .code());

        JsonNode badUuid = ok();
        ((com.fasterxml.jackson.databind.node.ObjectNode) badUuid).put("classId", "not-a-uuid");
        assertEquals("DEPENDENCY_UNAVAILABLE",
                assertThrows(PlatformException.class,
                        () -> new SemanticFactsClient(new StubClients(badUuid), "http://or").resolve(RELEASE, REF))
                        .code());
    }

    @Test
    void rejectsNonObjectPayload() {
        assertEquals("DEPENDENCY_UNAVAILABLE",
                assertThrows(PlatformException.class,
                        () -> new SemanticFactsClient(new StubClients(Json.tree("nope")), "http://or")
                                .resolve(RELEASE, REF))
                        .code());
    }

    private static JsonNode ok() {
        return Json.tree(Map.of(
                "refKind", "space-object",
                "spaceCode", "space-a",
                "releaseId", RELEASE.toString(),
                "version", "v3",
                "objectType", "Task",
                "stableId", "task:A-1",
                "classId", "22222222-2222-2222-2222-222222222222",
                "mappingId", "33333333-3333-3333-3333-333333333333",
                "keyField", "task_id",
                "sourceKey", "A-1"));
    }

    /** 只替换传输层；请求 URL 与 body 仍由真实客户端拼装。 */
    private static final class StubClients extends SkillClients {
        private final JsonNode reply;
        private String url;
        private Object sent;

        private StubClients(JsonNode reply) {
            this.reply = reply;
        }

        @Override
        public JsonNode post(String url, Object body) {
            this.url = url;
            this.sent = body;
            return reply;
        }
    }
}
