package cn.agentplatform.engine.domain;

import java.util.*;

public final class GraphRules {
    public static final Set<String> TERMINAL =
            Set.of("SUCCEEDED", "FAILED", "SKIPPED", "ABANDONED", "CANCELLED");

    public static String join(String mode, List<String> states, boolean skipPass) {
        if (states.isEmpty()) return "READY";
        if (mode.equals("ANY") && states.contains("SUCCEEDED")) return "READY";
        if (!states.stream().allMatch(TERMINAL::contains)) return "WAIT";
        if (states.stream().allMatch("FAILED"::equals)) return "FAILED";
        if (mode.equals("ANY")) return "SKIPPED";
        if (states.contains("FAILED") || states.contains("CANCELLED")) return "FAILED";
        if (states.stream().allMatch(s -> s.equals("SKIPPED") || s.equals("ABANDONED")))
            return "SKIPPED";
        return states.stream()
                        .allMatch(s -> s.equals("SUCCEEDED") || (skipPass && s.equals("SKIPPED")))
                ? "READY"
                : "SKIPPED";
    }

    // Conservatively cancel only direct exclusive predecessors; shared consumers retain execution.
    public static boolean exclusive(String branch, String join, Map<String, List<String>> out) {
        return out.getOrDefault(branch, List.of()).stream().allMatch(join::equals);
    }

    public static void validateDag(List<String> nodes, Map<String, List<String>> out) {
        Set<String> seen = new HashSet<>(), active = new HashSet<>();
        for (String n : nodes) visit(n, out, seen, active);
    }

    private static void visit(
            String n, Map<String, List<String>> out, Set<String> s, Set<String> a) {
        if (a.contains(n)) throw new IllegalArgumentException("DAG_REQUIRED");
        if (!s.add(n)) return;
        a.add(n);
        for (String v : out.getOrDefault(n, List.of())) {
            if (!out.containsKey(v)) throw new IllegalArgumentException("UNKNOWN_NODE " + v);
            visit(v, out, s, a);
        }
        a.remove(n);
    }
}
