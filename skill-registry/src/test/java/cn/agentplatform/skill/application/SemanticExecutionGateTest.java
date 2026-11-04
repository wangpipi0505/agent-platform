package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.PlatformException;
import cn.agentplatform.skill.infra.SemanticDecisionClient;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** SEM-07.1 门禁：声明即强制复核，未装配/未绑定/不可达都不得降级为放行。 */
class SemanticExecutionGateTest {
    private static final UUID RELEASE = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void undeclaredContractNeverCallsDecisionService() {
        var stub = new StubDecision(pass());
        var gate = new SemanticExecutionGate(stub);
        assertFalse(SemanticExecutionGate.declared(contract(false)));
        gate.check(contract(false));
        assertEquals(0, stub.calls);
    }

    @Test
    void holdsPassesAndSendsPinnedRelease() {
        var stub = new StubDecision(pass());
        var gate = new SemanticExecutionGate(stub);
        gate.check(contract(true));
        assertEquals(1, stub.calls);
        assertEquals(RELEASE, stub.releaseId);
        assertEquals("task_lookup", stub.request.get("opCode"));
        assertEquals(1, ((List<?>) stub.request.get("conditions")).size());
    }

    @Test
    void notHoldsBlocksDispatch() {
        var stub = new StubDecision(result(false,
                List.of(new SemanticDecisionClient.Condition("RELATION", "NOT_HOLDS", ""))));
        var failure = assertThrows(PlatformException.class,
                () -> new SemanticExecutionGate(stub).check(contract(true)));
        assertEquals(409, failure.status());
        assertEquals("SEMANTIC_CONDITION_BLOCKED", failure.code());
        assertTrue(failure.getMessage().contains("RELATION"));
    }

    @Test
    void unknownBlocksWithReason() {
        var stub = new StubDecision(result(false,
                List.of(new SemanticDecisionClient.Condition("STATE", "UNKNOWN", "缺少守卫绑定"))));
        var failure = assertThrows(PlatformException.class,
                () -> new SemanticExecutionGate(stub).check(contract(true)));
        assertEquals(409, failure.status());
        assertEquals("SEMANTIC_CONDITION_UNKNOWN", failure.code());
        assertTrue(failure.getMessage().contains("缺少守卫绑定"));
    }

    @Test
    void unreachableDependencyBecomesUnavailable() {
        var stub = new StubDecision(pass());
        stub.failure = new PlatformException(502, "DEPENDENCY_UNAVAILABLE", "连接失败");
        var failure = assertThrows(PlatformException.class,
                () -> new SemanticExecutionGate(stub).check(contract(true)));
        assertEquals(503, failure.status());
        assertEquals("SEMANTIC_SOURCE_UNAVAILABLE", failure.code());
    }

    @Test
    void missingPinnedReleaseIsUnavailableNotSilentPass() {
        var stub = new StubDecision(pass());
        Map<String, Object> contract = contract(true);
        contract.remove("importSource");
        var failure = assertThrows(PlatformException.class,
                () -> new SemanticExecutionGate(stub).check(contract));
        assertEquals(503, failure.status());
        assertEquals("SEMANTIC_CONDITION_UNAVAILABLE", failure.code());
    }

    @Test
    void mismatchedPinnedReleaseIsInvalid() {
        var stub = new StubDecision(pass());
        Map<String, Object> contract = contract(true);
        declaration(contract).put("releaseId", "99999999-9999-9999-9999-999999999999");
        var failure = assertThrows(PlatformException.class,
                () -> new SemanticExecutionGate(stub).check(contract));
        assertEquals(409, failure.status());
        assertEquals("SEMANTIC_CONDITION_INVALID", failure.code());
    }

    @Test
    void unknownDeclarationFieldIsInvalid() {
        var stub = new StubDecision(pass());
        Map<String, Object> contract = contract(true);
        declaration(contract).put("expression", "${x} > 1");
        var failure = assertThrows(PlatformException.class,
                () -> new SemanticExecutionGate(stub).check(contract));
        assertEquals(409, failure.status());
        assertEquals("SEMANTIC_CONDITION_INVALID", failure.code());
    }

    @Test
    void recheckPassesWhenConditionsStillHold() {
        var stub = new StubDecision(pass());
        new SemanticExecutionGate(stub).recheck(contract(true));
        assertEquals(1, stub.calls);
    }

    @Test
    void recheckBlockedRequiresReconfirmation() {
        var stub = new StubDecision(result(false,
                List.of(new SemanticDecisionClient.Condition("RELATION", "NOT_HOLDS", ""))));
        var failure = assertThrows(PlatformException.class,
                () -> new SemanticExecutionGate(stub).recheck(contract(true)));
        assertEquals(409, failure.status());
        assertEquals("SEMANTIC_CONDITION_RECONFIRM", failure.code());
    }

    @Test
    void recheckUnknownRequiresReconfirmation() {
        var stub = new StubDecision(result(false,
                List.of(new SemanticDecisionClient.Condition("STATE", "UNKNOWN", "源不可达"))));
        var failure = assertThrows(PlatformException.class,
                () -> new SemanticExecutionGate(stub).recheck(contract(true)));
        assertEquals(409, failure.status());
        assertEquals("SEMANTIC_CONDITION_RECONFIRM", failure.code());
    }

    @Test
    void recheckKeepsUnavailableAsUnavailable() {
        var stub = new StubDecision(pass());
        stub.failure = new PlatformException(502, "DEPENDENCY_UNAVAILABLE", "连接失败");
        var failure = assertThrows(PlatformException.class,
                () -> new SemanticExecutionGate(stub).recheck(contract(true)));
        assertEquals(503, failure.status());
        assertEquals("SEMANTIC_SOURCE_UNAVAILABLE", failure.code());
    }

    @Test
    void recheckUndeclaredIsNoop() {
        var stub = new StubDecision(pass());
        new SemanticExecutionGate(stub).recheck(contract(false));
        assertEquals(0, stub.calls);
    }

    // ---- fixtures ------------------------------------------------------------

    private static Map<String, Object> contract(boolean declared) {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("code", "task_lookup");
        contract.put("importSource", Map.of("releaseId", RELEASE.toString()));
        Map<String, Object> execution = new LinkedHashMap<>();
        if (declared) {
            Map<String, Object> declaration = new LinkedHashMap<>();
            declaration.put("opCode", "task_lookup");
            declaration.put("conditions", List.of(Map.of(
                    "conditionKind", "EXISTS",
                    "targetRef", Map.of("refKind", "space-object", "spaceCode", "space",
                            "objectType", "Task", "stableId", "task:1"))));
            execution.put("semanticConditions", declaration);
        }
        contract.put("execution", execution);
        return contract;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> declaration(Map<String, Object> contract) {
        return (Map<String, Object>) ((Map<String, Object>) contract.get("execution")).get("semanticConditions");
    }

    private static SemanticDecisionClient.ConditionResult pass() {
        return result(true, List.of(new SemanticDecisionClient.Condition("EXISTS", "HOLDS", "")));
    }

    private static SemanticDecisionClient.ConditionResult result(boolean allHold,
            List<SemanticDecisionClient.Condition> conditions) {
        return new SemanticDecisionClient.ConditionResult(
                Map.of("releaseId", RELEASE.toString()), allHold, new ArrayList<>(conditions));
    }

    static class StubDecision extends SemanticDecisionClient {
        private final SemanticDecisionClient.ConditionResult result;
        PlatformException failure;
        int calls;
        UUID releaseId;
        Map<String, Object> request;

        StubDecision(SemanticDecisionClient.ConditionResult result) {
            super(new cn.agentplatform.skill.infra.SkillClients(), "http://or.local");
            this.result = result;
        }

        @Override
        public ConditionResult evaluate(UUID releaseId, Map<String, Object> request) {
            calls++;
            this.releaseId = releaseId;
            this.request = request;
            if (failure != null) throw failure;
            return result;
        }
    }
}
