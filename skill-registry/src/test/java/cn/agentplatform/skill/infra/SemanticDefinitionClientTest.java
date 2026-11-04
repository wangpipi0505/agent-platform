package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SEM-06.2 客户端单测：定义投影必须与请求同址同源，且截断声明、计数与类型化引用自洽。
 * 真实 OR 链路留给集成窗口，这里不假定网络可用。
 */
class SemanticDefinitionClientTest {
    private static final UUID RELEASE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String DIGEST = "a".repeat(64);

    @Test
    void mapsProjectionAndKeepsRequestIdentity() {
        var clients = new StubClients(ok());
        var projection = new SemanticDefinitionClient(clients, "http://or.local/")
                .query(RELEASE, Map.of("kinds", List.of("objects", "actions"), "limit", 10));

        assertEquals(RELEASE.toString(), projection.releaseRef().get("releaseId"));
        assertEquals("v3", projection.releaseRef().get("version"));
        assertEquals(5, projection.itemCount());
        assertFalse(projection.emptyResult());
        assertFalse(projection.truncated());
        assertEquals(DIGEST, projection.projectionDigest());
        assertEquals(
                "http://or.local/onto/releases/" + RELEASE + "/semantic/definitions/query",
                clients.url);
        assertEquals(10, ((Map<?, ?>) clients.sent).get("limit"));
    }

    @Test
    void rejectsProjectionForAnotherRelease() {
        JsonNode body = ok();
        ((ObjectNode) body.get("releaseRef"))
                .put("releaseId", "99999999-9999-9999-9999-999999999999");
        assertEquals(
                "DEPENDENCY_UNAVAILABLE",
                assertThrows(
                                PlatformException.class,
                                () ->
                                        new SemanticDefinitionClient(new StubClients(body), "http://or")
                                                .query(RELEASE, Map.of()))
                        .code());
    }

    @Test
    void rejectsMismatchedReferenceKinds() {
        JsonNode body = ok();
        ((ObjectNode) body.get("definitions").get("relations").get(0).get("ref"))
                .put("kind", "object-type");
        assertEquals(
                "DEPENDENCY_UNAVAILABLE",
                assertThrows(
                                PlatformException.class,
                                () ->
                                        new SemanticDefinitionClient(new StubClients(body), "http://or")
                                                .query(RELEASE, Map.of()))
                        .code());
    }

    @Test
    void rejectsCountsThatDoNotMatchDefinitions() {
        JsonNode body = ok();
        ((ObjectNode) body.get("counts")).put("objects", 2);
        assertEquals(
                "DEPENDENCY_UNAVAILABLE",
                assertThrows(
                                PlatformException.class,
                                () ->
                                        new SemanticDefinitionClient(new StubClients(body), "http://or")
                                                .query(RELEASE, Map.of()))
                        .code());
    }

    @Test
    void keepsSemanticSourceUnavailable() {
        var client =
                new SemanticDefinitionClient(
                        new StubClients(new PlatformException(503, "SEMANTIC_SOURCE_UNAVAILABLE", "offline")),
                        "http://or");
        var failure = assertThrows(PlatformException.class, () -> client.query(RELEASE, Map.of()));
        assertEquals(503, failure.status());
        assertEquals("SEMANTIC_SOURCE_UNAVAILABLE", failure.code());
    }

    @Test
    void translatesOpaqueDependencyFailureToUnavailable() {
        var client =
                new SemanticDefinitionClient(
                        new StubClients(
                                new PlatformException(502, "DEPENDENCY_UNAVAILABLE", "连接失败")),
                        "http://or");
        var failure = assertThrows(PlatformException.class, () -> client.query(RELEASE, Map.of()));
        assertEquals(503, failure.status());
        assertEquals("SEMANTIC_SOURCE_UNAVAILABLE", failure.code());
    }

    @Test
    void rejectsUnknownSelectionBeforeCallingSource() {
        var clients = new StubClients(ok());
        var client = new SemanticDefinitionClient(clients, "http://or");
        var failure =
                assertThrows(
                        PlatformException.class, () -> client.query(RELEASE, Map.of("secret", true)));
        assertEquals(400, failure.status());
        assertEquals("SEMANTIC_FIELD_OUT_OF_RANGE", failure.code());
        assertNull(clients.url);
    }

    @Test
    void rejectsLimitOutsideBudget() {
        var failure =
                assertThrows(
                        PlatformException.class,
                        () ->
                                new SemanticDefinitionClient(new StubClients(ok()), "http://or")
                                        .query(RELEASE, Map.of("limit", 201)));
        assertEquals("SEMANTIC_FIELD_OUT_OF_RANGE", failure.code());
    }

    private static JsonNode ok() {
        ObjectNode body = Json.object();
        body.put("schemaVersion", "1.0");
        body.put("profile", "read-only");
        ObjectNode ref = body.putObject("releaseRef");
        ref.put("spaceCode", "space-a");
        ref.put("releaseId", RELEASE.toString());
        ref.put("version", "v3");
        ref.put("contentDigest", DIGEST);
        ObjectNode definitions = body.putObject("definitions");
        definitions.set("objects", Json.tree(List.of(definition("object-type", "Task"))));
        definitions.set("properties", Json.tree(List.of(definition("property", "title"))));
        definitions.set("relations", Json.tree(List.of(definition("relation", "owns"))));
        definitions.set("rules", Json.tree(List.of(definition("rule", "must-title"))));
        definitions.set("actions", Json.tree(List.of(definition("action", "task_query"))));
        ObjectNode counts = body.putObject("counts");
        for (String name : List.of("objects", "properties", "relations", "rules", "actions"))
            counts.put(name, 1);
        body.put("emptyResult", false);
        body.put("truncated", false);
        body.put("projectionDigest", DIGEST);
        return body;
    }

    private static Map<String, Object> definition(String kind, String name) {
        return Map.of("ref", Map.of("kind", kind, "id", kind + "-1", "name", name));
    }

    /** 非静态内部类：避免被类路径扫描当候选组件（同 OntologyManifestClientTest.StubClients）。 */
    private final class StubClients extends SkillClients {
        private final JsonNode payload;
        private final PlatformException failure;
        private String url;
        private Object sent;

        private StubClients(JsonNode payload) {
            this.payload = payload;
            this.failure = null;
        }

        private StubClients(PlatformException failure) {
            this.payload = null;
            this.failure = failure;
        }

        @Override
        public JsonNode post(String url, Object body) {
            this.url = url;
            this.sent = body;
            if (failure != null) throw failure;
            return payload;
        }
    }
}
