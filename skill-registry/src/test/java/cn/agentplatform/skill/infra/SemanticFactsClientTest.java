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

    @Test
    void mapsFactsResponseAndKeepsReleaseIdentity() {
        var clients = new StubClients(factsOk());
        var facts =
                new SemanticFactsClient(clients, "http://or.local/")
                        .facts(RELEASE, "task_query", REF, Map.of("region", "north"));
        assertEquals(RELEASE.toString(), facts.releaseRef().get("releaseId"));
        assertEquals("v3", facts.releaseRef().get("version"));
        @SuppressWarnings("unchecked")
        var items = (java.util.List<Map<String, Object>>) facts.factSet().get("items");
        assertEquals(1, items.size());
        assertEquals("ABSENT", items.getFirst().get("valueState"));
        assertEquals("UNKNOWN", facts.timeliness().get("freshness"));
        assertEquals("business-record", facts.evidenceRefs().getFirst().get("evidenceKind"));
        assertEquals(
                "http://or.local/onto/releases/" + RELEASE + "/semantic/facts", clients.url);
    }

    @Test
    void rejectsFactsForAnotherRelease() {
        JsonNode body = factsOk();
        ((com.fasterxml.jackson.databind.node.ObjectNode) body.get("releaseRef"))
                .put("releaseId", "99999999-9999-9999-9999-999999999999");
        assertEquals(
                "DEPENDENCY_UNAVAILABLE",
                assertThrows(
                                PlatformException.class,
                                () ->
                                        new SemanticFactsClient(new StubClients(body), "http://or")
                                                .facts(RELEASE, "task_query", REF, Map.of()))
                        .code());
    }

    @Test
    void rejectsIncompleteTimelinessAndValueStates() {
        JsonNode noReason = factsOk();
        ((com.fasterxml.jackson.databind.node.ObjectNode) noReason.get("timeliness"))
                .remove("unknownReason");
        assertEquals(
                "DEPENDENCY_UNAVAILABLE",
                assertThrows(
                                PlatformException.class,
                                () ->
                                        new SemanticFactsClient(new StubClients(noReason), "http://or")
                                                .facts(RELEASE, "task_query", REF, Map.of()))
                        .code());

        JsonNode absentWithValue = factsOk();
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                        absentWithValue.get("factSet").get("items").get(0))
                .put("value", 1);
        assertEquals(
                "DEPENDENCY_UNAVAILABLE",
                assertThrows(
                                PlatformException.class,
                                () ->
                                        new SemanticFactsClient(
                                                        new StubClients(absentWithValue), "http://or")
                                                .facts(RELEASE, "task_query", REF, Map.of()))
                        .code());
    }

    @Test
    void rejectsMissingOperationIdentity() {
        assertEquals(
                "SEMANTIC_REF_INVALID",
                assertThrows(
                                PlatformException.class,
                                () ->
                                        new SemanticFactsClient(new StubClients(factsOk()), "http://or")
                                                .facts(RELEASE, " ", REF, Map.of()))
                        .code());
    }

    private static JsonNode factsOk() {
        return Json.tree(
                Map.of(
                        "releaseRef",
                        Map.of(
                                "spaceCode", "space-a",
                                "releaseId", RELEASE.toString(),
                                "version", "v3",
                                "contentDigest", "a".repeat(64)),
                        "objectRef", REF,
                        "source",
                        Map.of(
                                "datasourceId", "33333333-3333-3333-3333-333333333333",
                                "sourceResource", "tasks"),
                        "factSet",
                        Map.of(
                                "queryRef", "release:" + RELEASE + "/op:task_query/object:task:A-1",
                                "items",
                                java.util.List.of(Map.of(
                                        "objectRef", REF,
                                        "factKind", "attribute",
                                        "attribute", "done",
                                        "valueState", "ABSENT",
                                        "observedAt", "2026-11-03T12:00:00Z")),
                                "factSetDigest", "b".repeat(64)),
                        "timeliness",
                        Map.of(
                                "observedAt", "2026-11-03T12:00:00Z",
                                "freshness", "UNKNOWN",
                                "unknownReason", "源未提供可确认的数据时点或水位"),
                        "evidenceRefs",
                        java.util.List.of(Map.of(
                                "evidenceKind", "business-record",
                                "recordSource", "tasks",
                                "recordId", "id=1"))));
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

    /** 只替换传输层；请求 URL 与 body 仍由真实客户端拼装。非静态内部类：避免被类路径扫描当候选组件。 */
    private final class StubClients extends SkillClients {
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
