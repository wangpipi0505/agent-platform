package cn.agentplatform.engine.domain;

import java.util.*;

public class GraphRulesTest {
    @org.junit.jupiter.api.Test
    void invariants() {
        assert GraphRules.join("ANY", List.of("FAILED", "FAILED"), true).equals("FAILED");
        assert GraphRules.join("ANY", List.of("FAILED", "SKIPPED"), true).equals("SKIPPED");
        assert GraphRules.join("ANY", List.of("SUCCEEDED", "RUNNING"), true).equals("READY");
        assert GraphRules.join("ALL", List.of("SUCCEEDED", "SKIPPED"), true).equals("READY");
        assert !GraphRules.exclusive("b", "j", Map.of("b", List.of("j", "k")));
        try {
            GraphRules.validateDag(List.of("a", "b"), Map.of("a", List.of("b"), "b", List.of("a")));
            throw new AssertionError("cycle accepted");
        } catch (IllegalArgumentException expected) {
        }
        System.out.println("6 graph invariants passed");
    }
}
