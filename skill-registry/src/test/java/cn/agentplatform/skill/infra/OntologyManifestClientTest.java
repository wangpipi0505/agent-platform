package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ONB-07 客户端单测：正式来源必须与请求同址同源，未声明本体时零调用。
 *
 * <p>只锁定 AP 侧的防御边界；OR 的 READY/权威校验与真实网络留给 OR 定向测试和集成窗口。
 */
class OntologyManifestClientTest {
    private static final UUID RELEASE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String DIGEST = "a".repeat(64);
    private static final String MANIFEST = "b".repeat(64);
    private static final String EXT = "c".repeat(64);

    private static Map<String, Object> ref() {
        return Map.of(
                "spaceCode", "space-a",
                "releaseId", RELEASE.toString(),
                "version", "v3",
                "contentDigest", DIGEST);
    }

    private static Map<String, Object> operation(String code, String status) {
        return Map.of("opCode", code, "route", "/onto/r/space-a/releases/" + RELEASE + "/ops/" + code,
                "status", status);
    }

    private static Map<String, Object> extension(String profile, String status) {
        return Map.of(
                "extensionId", "22222222-2222-2222-2222-222222222222",
                "opCode", "query_task",
                "profile", profile,
                "schemaVersion", "1.0",
                "extensionSha256", EXT,
                "status", status);
    }

    private static JsonNode source() {
        var body = new LinkedHashMap<String, Object>();
        body.put("schemaVersion", "1.0");
        body.put("releaseRef", ref());
        body.put("manifestSha256", MANIFEST);
        body.put("bundleSha256", DIGEST);
        body.put("operations", List.of(operation("query_task", "ACTIVE"), operation("legacy", "DISABLED")));
        body.put("semanticExtensions", List.of(extension("read-only@1.0", "ACTIVE")));
        body.put("counts", Map.of("class", 3, "property", 12, "relation", 1, "rule", 2, "operation", 2));
        body.put("readOnly", true);
        return Json.tree(body);
    }

    /**
     * 非静态内部类（非独立类型）：继承的 {@code @Component} 会让类路径扫描把它当候选组件，
     * 而带参私有构造无法实例化，整模块 Spring 上下文连坐失败；非独立类型不会被注册为 bean。
     */
    private final class StubClients extends SkillClients {
        private final JsonNode reply;
        private final PlatformException failure;
        private String url;
        private int calls;

        private StubClients(JsonNode reply) {
            this.reply = reply;
            this.failure = null;
        }

        private StubClients(PlatformException failure) {
            this.reply = null;
            this.failure = failure;
        }

        @Override
        public JsonNode get(String url) {
            this.url = url;
            calls++;
            if (failure != null) throw failure;
            return reply;
        }
    }

    @Test
    void resolvesFormalSourceAndKeepsExactQuadruple() {
        var clients = new StubClients(source());
        var resolved = new OntologyManifestClient(clients, "http://or.local/").resolve(RELEASE, "space-a");
        assertEquals("space-a", resolved.spaceCode());
        assertEquals(RELEASE, resolved.releaseId());
        assertEquals("v3", resolved.version());
        assertEquals(DIGEST, resolved.contentDigest());
        assertEquals(MANIFEST, resolved.manifestSha256());
        assertEquals(2, resolved.operations().size());
        assertEquals("DISABLED", resolved.operations().get(1).status());
        assertEquals("read-only@1.0", resolved.extensions().getFirst().profile());
        assertEquals("http://or.local/onto/releases/" + RELEASE + "/formal-source?spaceCode=space-a", clients.url);
    }

    @Test
    void rejectsResponseForAnotherRelease() {
        var body = (com.fasterxml.jackson.databind.node.ObjectNode) source();
        ((com.fasterxml.jackson.databind.node.ObjectNode) body.path("releaseRef"))
                .put("releaseId", "99999999-9999-9999-9999-999999999999");
        var failure = assertThrows(PlatformException.class,
                () -> new OntologyManifestClient(new StubClients(body), "http://or").resolve(RELEASE, null));
        assertEquals(502, failure.status());
        assertEquals("DEPENDENCY_UNAVAILABLE", failure.code());
    }

    @Test
    void rejectsDigestThatDoesNotMatchBundleDigest() {
        var body = (com.fasterxml.jackson.databind.node.ObjectNode) source();
        ((com.fasterxml.jackson.databind.node.ObjectNode) body.path("releaseRef"))
                .put("contentDigest", "d".repeat(64));
        var failure = assertThrows(PlatformException.class,
                () -> new OntologyManifestClient(new StubClients(body), "http://or").resolve(RELEASE, null));
        assertEquals(502, failure.status());
    }

    @Test
    void rejectsInactiveSemanticExtension() {
        var body = (com.fasterxml.jackson.databind.node.ObjectNode) source();
        ((com.fasterxml.jackson.databind.node.ArrayNode) body.path("semanticExtensions"))
                .removeAll()
                .add(Json.tree(extension("read-only@1.0", "RETIRED")));
        var failure = assertThrows(PlatformException.class,
                () -> new OntologyManifestClient(new StubClients(body), "http://or").resolve(RELEASE, null));
        assertEquals(502, failure.status());
    }

    @Test
    void dependencyFailureIsReportedAsSemanticSourceUnavailable() {
        var failure = assertThrows(PlatformException.class,
                () -> new OntologyManifestClient(
                                new StubClients(new PlatformException(
                                        502, "DEPENDENCY_UNAVAILABLE", "连接失败")),
                                "http://or")
                        .resolve(RELEASE, "space-a"));
        assertEquals(503, failure.status());
        assertEquals("SEMANTIC_SOURCE_UNAVAILABLE", failure.code());
    }

    @Test
    void missingFormalSourcePassesThroughSemanticRefInvalid() {
        var failure = assertThrows(PlatformException.class,
                () -> new OntologyManifestClient(
                                new StubClients(new PlatformException(
                                        404, "SEMANTIC_REF_INVALID", "本体版本不存在")),
                                "http://or")
                        .resolve(RELEASE, "space-a"));
        assertEquals(404, failure.status());
        assertEquals("SEMANTIC_REF_INVALID", failure.code());
    }

    @Test
    void templateWithoutOntologyDeclarationMakesNoCall() {
        var clients = new StubClients(source());
        var client = new OntologyManifestClient(clients, "http://or");
        assertTrue(client.resolveIfDeclared(null).isEmpty());
        assertTrue(client.resolveIfDeclared(Map.of("kind", "QUERY")).isEmpty());
        assertEquals(0, clients.calls);
        assertNull(clients.url);
    }

    @Test
    void declarationWithInvalidReleaseIdIsRejectedBeforeNetwork() {
        var clients = new StubClients(source());
        var failure = assertThrows(PlatformException.class,
                () -> new OntologyManifestClient(clients, "http://or")
                        .resolveIfDeclared(Map.of("releaseId", "not-a-uuid")));
        assertEquals(400, failure.status());
        assertEquals("SEMANTIC_REF_INVALID", failure.code());
        assertEquals(0, clients.calls);
    }
}
