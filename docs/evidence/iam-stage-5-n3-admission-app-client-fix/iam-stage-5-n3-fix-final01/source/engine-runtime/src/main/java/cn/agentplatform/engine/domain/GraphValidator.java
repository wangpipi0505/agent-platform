package cn.agentplatform.engine.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

public final class GraphValidator {
    public List<Map<String, Object>> validate(JsonNode graph) {
        List<Map<String, Object>> errors = new ArrayList<>();
        Map<String, JsonNode> nodes = new LinkedHashMap<>();
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (JsonNode n : graph.path("nodes")) {
            String id = n.path("id").asText();
            if (id.isBlank() || nodes.put(id, n) != null) add(errors, "NODE_ID", id, "节点编码缺失或重复");
            out.put(id, new ArrayList<>());
        }
        if (!nodes.containsKey(graph.path("entry").asText()))
            add(errors, "ENTRY_MISSING", "", "入口不存在");
        for (JsonNode e : graph.path("edges")) {
            String from = e.path("from").asText(), to = e.path("to").asText();
            if (!nodes.containsKey(from) || !nodes.containsKey(to)) {
                add(errors, "EDGE_REFERENCE", to, "连线节点不存在");
                continue;
            }
            out.get(from).add(to);
            if (e.has("when")
                    && !e.path("when").asText().equals("ERROR")
                    && !nodes.get(from).path("type").asText().equals("CONDITION"))
                add(errors, "CONDITION_OWNER", from, "条件边必须属于条件节点");
        }
        try {
            GraphRules.validateDag(new ArrayList<>(nodes.keySet()), out);
        } catch (IllegalArgumentException e) {
            add(errors, "DAG_REQUIRED", "", e.getMessage());
        }
        for (JsonNode join : graph.path("joins"))
            if (join.path("semantics").asText().equals("ANY")
                    && !join.path("allowSharedUpstream").asBoolean())
                for (var entry : out.entrySet())
                    if (entry.getValue().contains(join.path("nodeId").asText())
                            && !GraphRules.exclusive(
                                    entry.getKey(), join.path("nodeId").asText(), out))
                        add(
                                errors,
                                "ANY_SHARED_UPSTREAM",
                                join.path("nodeId").asText(),
                                "共享上游必须显式allowSharedUpstream");
        for (var n : nodes.values()) {
            JsonNode policy = n.path("errorPolicy");
            if (policy.path("onFail").asText().equals("ERROR_EDGE")
                    && !out.getOrDefault(n.path("id").asText(), List.of())
                            .contains(policy.path("errorEdgeTo").asText()))
                add(errors, "ERROR_EDGE_REQUIRED", n.path("id").asText(), "错误边目标缺失");
        }
        Map<String, Integer> indegree = new HashMap<>();
        nodes.keySet().forEach(n -> indegree.put(n, 0));
        out.values().forEach(v -> v.forEach(n -> indegree.computeIfPresent(n, (k, i) -> i + 1)));
        Set<String> remaining = new HashSet<>(nodes.keySet());
        while (!remaining.isEmpty()) {
            List<String> batch = remaining.stream().filter(n -> indegree.get(n) == 0).toList();
            if (batch.isEmpty()) break;
            Set<String> writes = new HashSet<>();
            for (String n : batch) {
                Iterator<String> fields = nodes.get(n).path("output").fieldNames();
                while (fields.hasNext()) {
                    String field = fields.next();
                    if (!writes.add(field))
                        add(errors, "PARALLEL_OUTPUT_WRITE", n, "并行批存在同名输出 " + field);
                }
            }
            for (String n : batch) {
                remaining.remove(n);
                for (String v : out.get(n)) indegree.computeIfPresent(v, (k, i) -> i - 1);
            }
        }
        return errors;
    }

    private void add(List<Map<String, Object>> e, String code, String node, String message) {
        e.add(Map.of("code", code, "nodeId", node, "message", message, "severity", "ERROR"));
    }
}
