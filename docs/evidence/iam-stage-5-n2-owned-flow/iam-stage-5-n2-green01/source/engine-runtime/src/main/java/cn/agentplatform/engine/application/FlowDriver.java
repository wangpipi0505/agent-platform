package cn.agentplatform.engine.application;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.domain.*;
import cn.agentplatform.engine.infra.*;
import cn.agentplatform.common.authority.HttpCurrentAuthorityClient.RetainedAuthorization;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class FlowDriver {
    private final TraceStore store;
    private final AgentDriver agent;

    public FlowDriver(TraceStore s, AgentDriver a) {
        store = s;
        agent = a;
    }

    public JsonNode scope(Map<String, Object> run) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("input", TraceStore.json(run.get("inputs")));
        scope.put("context", Map.of());
        for (var n : store.nodes(TraceStore.number(run, "id")))
            if (n.get("node_def_id") != null) {
                scope.put(
                        n.get("node_def_id").toString(),
                        Map.of(
                                "output",
                                TraceStore.json(n.get("output")),
                                "error",
                                TraceStore.json(n.get("error"))));
                if (n.get("error") != null) scope.put("error", TraceStore.json(n.get("error")));
            }
        return Json.tree(scope);
    }

    public boolean prepare(Map<String, Object> run, Map<String, Object> node) {
        if (!node.get("status").equals("READY")) return false;
        JsonNode graph = TraceStore.json(run.get("snapshot")).path("definition");
        String id = Objects.toString(node.get("node_def_id"), "");
        List<Map<String, Object>> incoming = new ArrayList<>();
        List<String> states = new ArrayList<>();
        for (JsonNode edge : graph.path("edges"))
            if (edge.path("to").asText().equals(id)) {
                var rows =
                        store.db()
                                .queryForList(
                                        "select * from engine.node_instance where run_id=? and"
                                                + " node_def_id=?",
                                        run.get("id"),
                                        edge.path("from").asText());
                if (rows.isEmpty()) throw new IllegalArgumentException("UNKNOWN_EDGE");
                var parent = rows.getFirst();
                incoming.add(parent);
                String state = parent.get("status").toString();
                JsonNode pd = TraceStore.json(parent.get("definition"));
                if (GraphRules.TERMINAL.contains(state)
                        && pd.path("type").asText().equals("CONDITION")
                        && !TraceStore.json(parent.get("output"))
                                .path("selected")
                                .asText()
                                .equals(id)) state = "SKIPPED";
                if (edge.path("when").asText().equals("ERROR")
                        || pd.path("errorPolicy").path("onFail").asText().equals("ERROR_EDGE")
                                && pd.path("errorPolicy").path("errorEdgeTo").asText().equals(id))
                    state =
                            state.equals("FAILED")
                                    ? "SUCCEEDED"
                                    : GraphRules.TERMINAL.contains(state) ? "SKIPPED" : state;
                else if (state.equals("FAILED")
                        && pd.path("errorPolicy").path("onFail").asText().equals("ERROR_EDGE"))
                    state = "SKIPPED";
                states.add(state);
            }
        String mode = "ALL";
        boolean skipPass = true;
        for (JsonNode join : graph.path("joins"))
            if (join.path("nodeId").asText().equals(id)) {
                mode = join.path("semantics").asText("ALL");
                skipPass = join.path("skipCountsAs").asText("PASS").equals("PASS");
            }
        String decision = GraphRules.join(mode, states, skipPass);
        if (decision.equals("WAIT")) return false;
        if (!decision.equals("READY")) {
            String terminal = decision;
            store.atomic(
                    () -> {
                        store.lockRun(TraceStore.number(run, "id"));
                        int count =
                                store.db()
                                        .update(
                                                "update engine.node_instance set"
                                                        + " status=?,ended_at=now() where id=? and"
                                                        + " status='READY'",
                                                terminal,
                                                node.get("id"));
                        if (count > 0)
                            store.event(
                                    TraceStore.number(run, "id"),
                                    terminal.equals("FAILED") ? "NODE_FAILED" : "NODE_SKIPPED",
                                    Map.of("nodeInstanceId", node.get("id"), "status", terminal));
                        return null;
                    });
            return false;
        }
        if (mode.equals("ANY") && !incoming.isEmpty()) {
            Map<String, List<String>> out = new HashMap<>();
            for (JsonNode e : graph.path("edges"))
                out.computeIfAbsent(e.path("from").asText(), k -> new ArrayList<>())
                        .add(e.path("to").asText());
            var winner =
                    incoming.stream()
                            .filter(n -> n.get("status").equals("SUCCEEDED"))
                            .findFirst()
                            .orElse(null);
            if (winner != null)
                store.atomic(
                        () -> {
                            store.lockRun(TraceStore.number(run, "id"));
                            // §30.4.2/R05: winner is write-once. Only exclusive direct predecessors
                            // are
                            // abandoned; a branch with any other consumer keeps its execution and
                            // output.
                            store.db()
                                    .update(
                                            "update engine.node_instance set winner_node_id=? where"
                                                    + " id=? and winner_node_id is null",
                                            winner.get("id"),
                                            node.get("id"));
                            for (var parent : incoming)
                                if (!parent.get("id").equals(winner.get("id"))
                                        && GraphRules.exclusive(
                                                parent.get("node_def_id").toString(), id, out))
                                    store.db()
                                            .update(
                                                    "update engine.node_instance set"
                                                        + " status='ABANDONED',ended_at=now() where"
                                                        + " id=? and status in ('READY','RUNNING')",
                                                    parent.get("id"));
                            return null;
                        });
        }
        return true;
    }

    public JsonNode execute(Map<String, Object> run, Map<String, Object> node, Actor actor) {
        return execute(run,node,actor,scope(run));
    }

    /** Work data carries no authority. The owned action still supplies its explicit live proof. */
    public JsonNode executeFlow(TraceStore.FlowClaim work, RunDispatch dispatch, RetainedAuthorization proof) {
        proof.requireVerified();
        if(dispatch.runId()!=TraceStore.number(work.run(),"id"))throw RunAuthorityStore.denied();
        var result=execute(work.run(),work.node(),proof.current().actor(),work.scope());
        proof.requireVerified();
        return result;
    }

    private JsonNode execute(Map<String, Object> run, Map<String, Object> node, Actor actor, JsonNode materializedScope) {
        JsonNode def = TraceStore.json(node.get("definition")),
                scope = materializedScope,
                snapshot = TraceStore.json(run.get("snapshot")),
                config = snapshot.path("config");
        JsonNode args = Values.resolve(def.path("args"), scope);
        Object controlledVersion =
                run.get("controlled_skill_test_version_id") != null
                        ? run.get("controlled_skill_test_version_id")
                        : run.get("controlled_draft_skill_version_id");
        if (controlledVersion != null
                && def.path("skillRef").path("skillVersionId").asLong()
                        == ((Number) controlledVersion).longValue())
            args = TraceStore.json(run.get("inputs"));
        JsonNode raw =
                switch (def.path("type").asText()) {
                    case "START" -> {
                        JsonNode input = TraceStore.json(run.get("inputs"));
                        // Recovery can encounter runs accepted before the admission gate. Use
                        // the same full declarations here: required is presence, so an explicit
                        // null is valid only when its declared type permits it.
                        try {
                            InputDeclarations.validate(snapshot, input);
                        } catch (PlatformException invalid) {
                            throw new RuntimeFault("PARAM_ERROR", "inputs", invalid.getMessage());
                        }
                        yield input;
                    }
                    case "END" -> {
                        var output = new LinkedHashMap<String, Object>();
                        for (JsonNode o : snapshot.path("definition").path("outputs"))
                            output.put(
                                    o.path("name").asText(), Values.resolve(o.path("from"), scope));
                        yield output.isEmpty() ? args : Json.tree(output);
                    }
                    case "CODE" ->
                            Json.tree(Values.expression(def.path("expression").asText(), scope));
                    case "CONDITION" -> {
                        String selected = null;
                        for (JsonNode branch : def.path("branches")) {
                            if (branch.has("else")) {
                                selected = branch.path("else").asText();
                                break;
                            }
                            if (Boolean.TRUE.equals(
                                    Values.expression(branch.path("when").asText(), scope))) {
                                selected = branch.path("to").asText();
                                break;
                            }
                        }
                        yield Json.tree(Map.of("selected", selected == null ? "" : selected));
                    }
                    case "SKILL", "RETRIEVE" ->
                            agent.skill(
                                    run,
                                    node,
                                    def.path("skillRef").path("code").asText(),
                                    def.path("skillRef").path("skillVersionId").asLong(),
                                    args,
                                    1,
                                    actor);
                    case "LLM" -> {
                        var modelConfig = config.deepCopy();
                        if (def.has("promptRef")) {
                            var chosen =
                                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                                            .arrayNode();
                            for (JsonNode prompt : config.path("frozenPrompts"))
                                if (prompt.path("code")
                                                .asText()
                                                .equals(
                                                        def.path("promptRef")
                                                                .path("templateCode")
                                                                .asText(
                                                                        def.path("promptRef")
                                                                                .path("code")
                                                                                .asText()))
                                        && prompt.path("version").asInt()
                                                == def.path("promptRef").path("version").asInt())
                                    chosen.add(prompt);
                            ((com.fasterxml.jackson.databind.node.ObjectNode) modelConfig)
                                    .set("frozenPrompts", chosen);
                        }
                        yield agent.drive(run, node, modelConfig, args, actor, true);
                    }
                    case "CONFIRM" -> Json.tree(Map.of("approved", true));
                    case "AGENT" -> agent.drive(run, node, config, args, actor, false);
                    default ->
                            throw new IllegalArgumentException(
                                    "NODE_HANDLER_UNAVAILABLE:" + def.path("type").asText());
                };
        boolean modelValidated =
                Set.of("LLM", "AGENT").contains(def.path("type").asText())
                        && raw.has("outputValidation");
        JsonNode outputValidation = modelValidated ? raw.path("outputValidation") : Json.object();
        if (def.path("outputContract").has("schema") && !modelValidated) {
            if (def.path("outputContract").path("type").asText().equals("json")
                    && raw.path("text").isTextual()) {
                try {
                    raw = Json.read(raw.path("text").asText());
                } catch (Exception error) {
                    throw new RuntimeFault("OUTPUT_INVALID", "output", "模型返回不是有效JSON");
                }
            }
            JsonGate.validate(
                    def.path("outputContract").path("schema"), raw, "output", "OUTPUT_INVALID");
        }
        if (raw.has("structured")) raw = raw.path("structured");
        if (def.path("output").isObject() && !def.path("output").isEmpty()) {
            var mapped = Json.object();
            var fields = def.path("output").fields();
            while (fields.hasNext()) {
                var mapping = fields.next();
                mapped.set(
                        mapping.getKey(),
                        mapping.getValue().asText().equals("$")
                                ? raw
                                : Values.path(raw, mapping.getValue().asText()));
            }
            // Failed acceptance is engine metadata and cannot be hidden by a business mapping.
            if (modelValidated && !outputValidation.path("valid").asBoolean())
                mapped.set("outputValidation", outputValidation);
            return mapped;
        }
        return raw;
    }
}
