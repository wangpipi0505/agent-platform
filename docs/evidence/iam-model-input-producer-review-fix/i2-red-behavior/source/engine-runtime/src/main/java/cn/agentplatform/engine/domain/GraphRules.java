package cn.agentplatform.engine.domain;

import cn.agentplatform.common.Json;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

public final class GraphRules {
    public static final Set<String> TERMINAL =
            Set.of("SUCCEEDED", "FAILED", "SKIPPED", "ABANDONED", "CANCELLED");

    public record Preparation(String state, Long winner, List<Long> abandon) {}

    /** Original edge order and actual-SUCCEEDED ANY winner are part of the graph contract. */
    public static Preparation prepare(
            JsonNode graph, Map<String, Object> node, List<Map<String, Object>> nodes) {
        if (!"READY".equals(node.get("status"))) return new Preparation("WAIT", null, List.of());
        String id = Objects.toString(node.get("node_def_id"), "");
        var incoming = new ArrayList<Map<String, Object>>();
        var states = new ArrayList<String>();
        for (var edge : graph.path("edges"))
            if (edge.path("to").asText().equals(id)) {
                var parent =
                        nodes.stream()
                                .filter(
                                        n ->
                                                edge.path("from")
                                                        .asText()
                                                        .equals(n.get("node_def_id")))
                                .findFirst()
                                .orElseThrow(() -> new IllegalArgumentException("UNKNOWN_EDGE"));
                incoming.add(parent);
                String state = parent.get("status").toString();
                var pd = value(parent.get("definition"));
                if (TERMINAL.contains(state)
                        && pd.path("type").asText().equals("CONDITION")
                        && !value(parent.get("output")).path("selected").asText().equals(id))
                    state = "SKIPPED";
                if (edge.path("when").asText().equals("ERROR")
                        || pd.path("errorPolicy").path("onFail").asText().equals("ERROR_EDGE")
                                && pd.path("errorPolicy").path("errorEdgeTo").asText().equals(id))
                    state =
                            state.equals("FAILED")
                                    ? "SUCCEEDED"
                                    : TERMINAL.contains(state) ? "SKIPPED" : state;
                else if (state.equals("FAILED")
                        && pd.path("errorPolicy").path("onFail").asText().equals("ERROR_EDGE"))
                    state = "SKIPPED";
                states.add(state);
            }
        String mode = "ALL";
        boolean skipPass = true;
        for (var join : graph.path("joins"))
            if (join.path("nodeId").asText().equals(id)) {
                mode = join.path("semantics").asText("ALL");
                skipPass = join.path("skipCountsAs").asText("PASS").equals("PASS");
            }
        String decision = join(mode, states, skipPass);
        if (!decision.equals("READY") || !mode.equals("ANY"))
            return new Preparation(decision, null, List.of());
        var winner =
                incoming.stream()
                        .filter(n -> "SUCCEEDED".equals(n.get("status")))
                        .findFirst()
                        .orElse(null);
        if (winner == null) return new Preparation(decision, null, List.of());
        Map<String, List<String>> out = new HashMap<>();
        for (var e : graph.path("edges"))
            out.computeIfAbsent(e.path("from").asText(), k -> new ArrayList<>())
                    .add(e.path("to").asText());
        var abandon =
                incoming.stream()
                        .filter(
                                n ->
                                        !n.get("id").equals(winner.get("id"))
                                                && exclusive(
                                                        n.get("node_def_id").toString(), id, out))
                        .map(n -> ((Number) n.get("id")).longValue())
                        .toList();
        return new Preparation(decision, ((Number) winner.get("id")).longValue(), abandon);
    }

    public static String aggregate(List<Map<String, Object>> nodes, boolean runnable) {
        if (nodes.stream().allMatch(n -> TERMINAL.contains(n.get("status").toString())))
            return nodes.stream()
                            .anyMatch(
                                    n ->
                                            "FAILED".equals(n.get("status"))
                                                    && !value(n.get("definition"))
                                                            .path("errorPolicy")
                                                            .path("onFail")
                                                            .asText()
                                                            .equals("ERROR_EDGE"))
                    ? "FAILED"
                    : "SUCCEEDED";
        if (runnable) return "RUNNING";
        if (nodes.stream().anyMatch(n -> "WAITING_STEP".equals(n.get("status"))))
            return "WAITING_STEP";
        if (nodes.stream().anyMatch(n -> "WAITING_EXTERNAL".equals(n.get("status"))))
            return "WAITING_EXTERNAL";
        return "WAITING_APPROVAL";
    }

    public static Object aggregateOutput(List<Map<String, Object>> nodes) {
        return nodes.stream()
                .filter(n -> "END".equals(n.get("node_type")) && n.get("output") != null)
                .map(n -> n.get("output"))
                .findFirst()
                .orElse(nodes.getLast().get("output"));
    }

    private static JsonNode value(Object value) {
        return value == null
                ? Json.object()
                : value instanceof JsonNode n ? n : Json.read(value.toString());
    }

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
    private static boolean dependency(JsonNode value){return value!=null&&!value.isMissingNode()&&!value.isNull()&&(!value.isContainerNode()||!value.isEmpty());}
    public static void requirePublishedFlow(JsonNode snapshot) {
        var graph = snapshot.path("definition");
        if (!"FLOW".equals(snapshot.path("type").asText())
                || !graph.isObject()
                || !graph.path("nodes").isArray()
                || graph.path("nodes").isEmpty()) throw new cn.agentplatform.common.PlatformException(403,"RUN_AUTHORITY_DENIED","运行授权或材料范围不匹配");
        if (snapshot.hasNonNull("config") && !snapshot.path("config").isObject()
                || snapshot.hasNonNull("dependencyVersions")
                        && !snapshot.path("dependencyVersions").isObject())
            throw new cn.agentplatform.common.PlatformException(403,"RUN_AUTHORITY_DENIED","运行授权或材料范围不匹配");
        for (String key :
                List.of(
                        "controlledReplay",
                        "reexecution",
                        "debugControls",
                        "controlledSkillVersion",
                        "debugSnapshot",
                        "evalCandidate",
                        "controlledDraftId"))
            if (snapshot.hasNonNull(key)) throw new cn.agentplatform.common.PlatformException(403,"RUN_AUTHORITY_DENIED","运行授权或材料范围不匹配");
        for (String key :
                List.of(
                        "skillRefs",
                        "promptRefs",
                        "modelEntryId",
                        "modelBinding",
                        "tools",
                        "frozenPrompts",
                        "dataSources"))
            if (dependency(snapshot.path("config").path(key))) throw new cn.agentplatform.common.PlatformException(403,"RUN_AUTHORITY_DENIED","运行授权或材料范围不匹配");
        // The owned worker has the same finite dependency gate. Reject its unsupported inputs
        // before prepareSnapshot can resolve any external reference or admission can write PENDING.
        for (String key : List.of("modelRefs", "skillRefs", "promptRefs", "ontology"))
            if (dependency(snapshot.path("dependencyVersions").path(key)))
                throw new cn.agentplatform.common.PlatformException(403,"RUN_AUTHORITY_DENIED","运行授权或材料范围不匹配");
        for (var node : graph.path("nodes")) {
            if (!Set.of("START", "END", "CODE", "CONDITION").contains(node.path("type").asText()))
                throw new cn.agentplatform.common.PlatformException(403,"RUN_AUTHORITY_DENIED","运行授权或材料范围不匹配");
            for (String key :
                    List.of("skillRef", "promptRef", "modelOverride", "tools", "dataSources"))
                if (dependency(node.path(key))) throw new cn.agentplatform.common.PlatformException(403,"RUN_AUTHORITY_DENIED","运行授权或材料范围不匹配");
        }
    }


}
