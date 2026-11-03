package cn.agentplatform.skill.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SEM-05.1：语义回执投影的纯规则用例（不依赖 Spring/DB/网络）。 */
class SemanticReceiptTest {

    private static final String SPACE = "customer-space";
    private static final String RELEASE = "3f9c1b1e-2a4d-4c6e-9b7a-1c2d3e4f5a6b";
    private static final String DIGEST = "b".repeat(64);

    private static Map<String, Object> contract() {
        return Map.of("code", "customer.read", "version", 3);
    }

    private static Map<String, Object> meta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("spaceCode", SPACE);
        meta.put("releaseId", RELEASE);
        meta.put("version", "v7");
        meta.put("contentDigest", DIGEST);
        return meta;
    }

    private static Map<String, Object> releaseRef() {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("spaceCode", SPACE);
        ref.put("releaseId", RELEASE);
        ref.put("version", "v7");
        ref.put("contentDigest", DIGEST);
        return ref;
    }

    private static Map<String, Object> fragment(Map<String, Object> release) {
        Map<String, Object> fragment = new LinkedHashMap<>();
        fragment.put("releaseRef", release);
        fragment.put("evidenceRefs", List.of(Map.of("evidenceKind", "artifact", "artifactId", 7L)));
        fragment.put(
                "timeliness",
                Map.of(
                        "observedAt", "2024-09-01T10:00:00Z",
                        "freshness", "UNKNOWN",
                        "unknownReason", "冻结回执未提供可确认的源数据时点"));
        return fragment;
    }

    private static SemanticReceipt.Projection project(Map<String, Object> meta, Map<String, Object> fragment) {
        return SemanticReceipt.project(
                contract(), meta, fragment, 42L, "run-9", Instant.parse("2024-09-02T00:00:00Z"));
    }

    @Test
    void projectionWithoutFactSetIsPartialAndKeepsOurOwnCallEvidence() {
        var projection = project(meta(), fragment(releaseRef()));
        assertNotNull(projection);
        assertTrue(projection.valid());
        var record = projection.record();
        assertEquals("PARTIAL", record.get("status"));
        assertEquals("1.0", record.get("schemaVersion"));
        assertEquals("read-only", record.get("profile"));
        assertTrue(String.valueOf(record.get("recordId")).startsWith("sr_"));
        assertEquals("customer.read", ((Map<?, ?>) record.get("bindingRef")).get("skillCode"));
        assertEquals(3, ((Map<?, ?>) record.get("bindingRef")).get("skillVersion"));
        var factSet = (Map<?, ?>) record.get("factSet");
        assertEquals("call:42", factSet.get("queryRef"));
        assertTrue(((List<?>) factSet.get("items")).isEmpty());
        assertEquals(64, String.valueOf(factSet.get("factSetDigest")).length());
        var errors = (List<?>) record.get("errors");
        assertEquals(1, errors.size());
        assertEquals("SEMANTIC_SOURCE_UNAVAILABLE", ((Map<?, ?>) errors.getFirst()).get("code"));
        var refs = (List<?>) record.get("evidenceRefs");
        assertEquals(2, refs.size());
        var own = (Map<?, ?>) refs.getLast();
        assertEquals("call", own.get("evidenceKind"));
        assertEquals("42", own.get("callId"));
        assertEquals("run-9", own.get("runId"));
        // 源时点未知时必须带原因，且不得凭空补 dataAsOf。
        var timeliness = (Map<?, ?>) record.get("timeliness");
        assertEquals("UNKNOWN", timeliness.get("freshness"));
        assertFalse(timeliness.containsKey("dataAsOf"));
        assertNotNull(timeliness.get("unknownReason"));
        // 落库前与本类复检口径一致（读口与联动测试复用同一套校验）。
        assertDoesNotThrow(() -> SemanticReceipt.parse(record));
    }

    @Test
    void missingMetaIsRejectedInsteadOfInventingAVersion() {
        var projection = project(Map.of(), fragment(releaseRef()));
        assertFalse(projection.valid());
        assertEquals("ERROR", projection.record().get("status"));
        assertEquals(
                "SEMANTIC_REF_INVALID",
                ((Map<?, ?>) ((List<?>) projection.record().get("errors")).getFirst()).get("code"));
        assertFalse(projection.record().containsKey("releaseRef"));
    }

    @Test
    void previewResponsesAreRejected() {
        Map<String, Object> meta = new LinkedHashMap<>(meta());
        meta.put("preview", true);
        var projection = project(meta, fragment(releaseRef()));
        assertFalse(projection.valid());
        assertEquals(
                "SEMANTIC_REF_INVALID",
                ((Map<?, ?>) ((List<?>) projection.record().get("errors")).getFirst()).get("code"));
    }

    @Test
    void digestDriftIsReportedAsDigestMismatch() {
        Map<String, Object> meta = new LinkedHashMap<>(meta());
        meta.put("contentDigest", "c".repeat(64));
        var projection = project(meta, fragment(releaseRef()));
        assertFalse(projection.valid());
        assertEquals(
                "SEMANTIC_DIGEST_MISMATCH",
                ((Map<?, ?>) ((List<?>) projection.record().get("errors")).getFirst()).get("code"));
    }

    @Test
    void unknownFreshnessMustNotCarrySourceStamp() {
        Map<String, Object> fragment = fragment(releaseRef());
        fragment.put(
                "timeliness",
                Map.of(
                        "observedAt", "2024-09-01T10:00:00Z",
                        "freshness", "UNKNOWN",
                        "dataAsOf", "2024-08-01T00:00:00Z",
                        "unknownReason", "冲突声明"));
        assertFalse(project(meta(), fragment).valid());
    }

    @Test
    void semanticPathsMustBeSemanticPointersAndUnique() {
        assertDoesNotThrow(
                () -> SemanticReceipt.validatePaths(
                        Map.of(
                                "factSetPath", "$.data.semantic.facts",
                                "evidenceRefsPath", "$.data.semantic.evidence")));
        assertThrows(
                IllegalArgumentException.class,
                () -> SemanticReceipt.validatePaths(Map.of("factSetPath", "$.data.other.facts")));
        assertThrows(
                IllegalArgumentException.class,
                () -> SemanticReceipt.validatePaths(Map.of("factSetPath", "$.meta")));
        assertThrows(
                IllegalArgumentException.class,
                () -> SemanticReceipt.validatePaths(Map.of("factSetPath", "$.data.semantic.a[*]")));
    }

    @Test
    void parseRejectsDecisionProfileAndStatusDrift() {
        var record = project(meta(), fragment(releaseRef())).record();
        var withDecision = new LinkedHashMap<>(record);
        withDecision.put("decisionRef", Map.of("decisionId", "d1"));
        assertThrows(IllegalArgumentException.class, () -> SemanticReceipt.parse(withDecision));

        var okWithoutFacts = new LinkedHashMap<>(record);
        okWithoutFacts.put("status", "OK");
        assertThrows(IllegalArgumentException.class, () -> SemanticReceipt.parse(okWithoutFacts));

        var errorWithFacts = new LinkedHashMap<>(record);
        errorWithFacts.put("status", "ERROR");
        errorWithFacts.remove("errors");
        assertThrows(IllegalArgumentException.class, () -> SemanticReceipt.parse(errorWithFacts));

        var undeclaredCode = new LinkedHashMap<>(record);
        undeclaredCode.put("errors", List.of(Map.of("code", "SOMETHING_ELSE", "message", "x")));
        assertThrows(IllegalArgumentException.class, () -> SemanticReceipt.parse(undeclaredCode));
    }
}
