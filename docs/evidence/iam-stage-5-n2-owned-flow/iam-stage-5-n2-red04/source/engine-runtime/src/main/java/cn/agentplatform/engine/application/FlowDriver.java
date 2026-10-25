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

    public boolean prepare(Map<String,Object> run,Map<String,Object> node) {
        var decision=GraphRules.prepare(TraceStore.json(run.get("snapshot")).path("definition"),node,store.nodes(TraceStore.number(run,"id")));
        if(decision.state().equals("WAIT"))return false;
        if(!decision.state().equals("READY")) {
            store.atomic(()->{store.lockRun(TraceStore.number(run,"id"));if(store.db().update("update engine.node_instance set status=?,ended_at=now() where id=? and status='READY'",decision.state(),node.get("id"))>0)store.event(TraceStore.number(run,"id"),decision.state().equals("FAILED")?"NODE_FAILED":"NODE_SKIPPED",Map.of("nodeInstanceId",node.get("id"),"status",decision.state()));return null;});return false;
        }
        if(decision.winner()!=null)store.atomic(()->{store.lockRun(TraceStore.number(run,"id"));store.db().update("update engine.node_instance set winner_node_id=? where id=? and winner_node_id is null",decision.winner(),node.get("id"));for(long parent:decision.abandon())store.db().update("update engine.node_instance set status='ABANDONED',ended_at=now() where id=? and status in ('READY','RUNNING')",parent);return null;});
        return true;
    }

    public JsonNode execute(Map<String, Object> run, Map<String, Object> node, Actor actor) {
        return execute(run,node,actor,scope(run));
    }

    /** Work data carries no authority. The owned action still supplies its explicit live proof. */
    public JsonNode executeFlow(TraceStore.FlowClaim work, RunDispatch dispatch, RetainedAuthorization proof) {
        proof.requireVerified();
        if(dispatch.runId()!=TraceStore.number(work.run(),"id"))throw RunAuthorityStore.denied();
        if(!Set.of("START","END","CODE","CONDITION").contains(TraceStore.json(work.node().get("definition")).path("type").asText()))throw RunAuthorityStore.denied();
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
