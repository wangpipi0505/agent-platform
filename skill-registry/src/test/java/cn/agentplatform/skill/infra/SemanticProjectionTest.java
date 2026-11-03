package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SEM-05.1：适配器只在 manifest 声明语义路径时才投影，并且绝不把缺证据的响应伪装成成功。
 *
 * <p>三个用例对应三条不变量：声明且齐全 → 产出 {@code PARTIAL} 回执并钉住本次 call；
 * 声明但缺 meta → 产出 {@code valid=false} 的拒绝标记；未声明 → 完全不碰原投影。
 */
class SemanticProjectionTest {

    private static final String DIGEST = "b".repeat(64);
    private static final String RELEASE = "3f9c1b1e-2a4d-4c6e-9b7a-1c2d3e4f5a6b";

    private SkillAdapters adapters() {
        return new SkillAdapters(null, null);
    }

    private Map<String, Object> contract(boolean withSemantic, boolean withOntologyPath) {
        Map<String, Object> returnSpec = new LinkedHashMap<>();
        returnSpec.put("structuredPath", "$.data");
        if (withSemantic) {
            returnSpec.put("evidenceRefsPath", "$.data.semantic.evidence");
            returnSpec.put("timelinessPath", "$.data.semantic.quality");
        }
        if (withOntologyPath) returnSpec.put("ontologyMetaPath", "$.meta");
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("code", "customer.read");
        contract.put("version", 3);
        contract.put("timeoutMs", 30_000);
        contract.put("outputMaxBytes", 1_000_000);
        contract.put("outputSchema", Map.of("type", "object"));
        contract.put("returnSpec", returnSpec);
        return contract;
    }

    private String response(boolean withMeta) {
        var semantic = new LinkedHashMap<String, Object>();
        semantic.put(
                "releaseRef",
                Map.of(
                        "spaceCode", "customer-space",
                        "releaseId", RELEASE,
                        "version", "v7",
                        "contentDigest", DIGEST));
        semantic.put(
                "evidence", List.of(Map.of("evidenceKind", "business-record", "recordSource", "crm", "recordId", "C018")));
        semantic.put(
                "quality",
                Map.of(
                        "observedAt", "2024-09-01T10:00:00Z",
                        "freshness", "SOURCE_STAMPED",
                        "dataAsOf", "2024-08-31T00:00:00Z"));
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("code", "OK");
        envelope.put("data", Map.of("semantic", semantic, "columns", List.of(), "rows", List.of()));
        if (withMeta)
            envelope.put(
                    "meta",
                    Map.of(
                            "spaceCode", "customer-space",
                            "releaseId", RELEASE,
                            "version", "v7",
                            "contentDigest", DIGEST,
                            "preview", false));
        return com.fasterxml.jackson.databind.json.JsonMapper.builder().build().valueToTree(envelope).toString();
    }

    private SkillAdapters.Raw read(Map<String, Object> contract, String body, long callId)
            throws Exception {
        return adapters()
                .read(
                        new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),
                        contract,
                        callId,
                        "run-" + callId);
    }

    @Test
    void declaredSemanticFragmentIsProjectedAndPinnedToThisCall() throws Exception {
        var raw = read(contract(true, true), response(true), 42L);
        assertNotNull(raw.semantic());
        assertTrue(raw.semantic().valid());
        var record = raw.semantic().record();
        assertEquals("PARTIAL", record.get("status"));
        var refs = (List<?>) record.get("evidenceRefs");
        assertEquals(2, refs.size());
        var own = (Map<?, ?>) refs.getLast();
        assertEquals("42", own.get("callId"));
        assertEquals("run-42", own.get("runId"));
        var timeliness = (Map<?, ?>) record.get("timeliness");
        assertEquals("SOURCE_STAMPED", timeliness.get("freshness"));
        assertEquals("2024-08-31T00:00:00Z", timeliness.get("dataAsOf"));
    }

    @Test
    void declaredButMissingMetaYieldsARejectionInsteadOfASuccess() throws Exception {
        var raw = read(contract(true, false), response(false), 43L);
        assertNotNull(raw.semantic());
        assertFalse(raw.semantic().valid());
        var record = raw.semantic().record();
        assertEquals("ERROR", record.get("status"));
        assertFalse(record.containsKey("releaseRef"));
        assertEquals(
                "SEMANTIC_REF_INVALID",
                ((Map<?, ?>) ((List<?>) record.get("errors")).getFirst()).get("code"));
    }

    @Test
    void skillsWithoutSemanticDeclarationKeepTheOriginalProjection() throws Exception {
        var raw = read(contract(false, false), response(true), 44L);
        assertNull(raw.semantic());
        assertNotNull(raw.structured());
    }
}
