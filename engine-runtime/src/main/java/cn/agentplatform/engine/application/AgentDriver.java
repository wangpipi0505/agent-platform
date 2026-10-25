package cn.agentplatform.engine.application;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.infra.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class AgentDriver {
    private final TraceStore store;
    private final RuntimeClients clients;
    private final ContextAssembler assembler;
    private final ChunkBus chunks;

    public AgentDriver(TraceStore s, RuntimeClients c, ContextAssembler a) {
        this(s, c, a, new ChunkBus());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentDriver(TraceStore s, RuntimeClients c, ContextAssembler a, ChunkBus chunks) {
        store = s;
        clients = c;
        assembler = a;
        this.chunks = chunks;
    }

    /**
     * R-A7：工具调用 pre/post 拦截链，可选装配。手工构造（测试夹具）未注入时保持
     * 空链零开销；Spring 环境注入按 @Order 排序的链。
     */
    private ToolInterceptorChain interceptors = ToolInterceptorChain.EMPTY;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void interceptors(ToolInterceptorChain chain) {
        this.interceptors = chain;
    }

    public JsonNode drive(
            Map<String, Object> run,
            Map<String, Object> node,
            JsonNode config,
            JsonNode inputs,
            Actor actor,
            boolean single) {
        config = effectiveConfig(config, node);
        long attempt = TraceStore.number(node, "attemptId"),
                runId = TraceStore.number(run, "id"),
                nid = TraceStore.number(node, "id");
        List<Map<String, Object>> history = new ArrayList<>();
        List<Object> tools = new ArrayList<>();
        config.path("tools").forEach(t -> tools.add(t.path("definition")));
        var prior =
                store.db()
                        .queryForList(
                                "select checkpoint from engine.attempt where node_instance_id=? and"
                                        + " id<>? and response is not null order by attempt_no desc"
                                        + " limit 1",
                                nid,
                                attempt);
        JsonNode recovered =
                prior.isEmpty()
                        ? Json.read("{}")
                        : TraceStore.json(prior.getFirst().get("checkpoint"));
        // 契约 trace-model 终止规则情形6的判定状态：无进展 = 同一工具调用指纹
        // "连续"重复的轮数，指纹变化即重置（不跨恢复记忆，与检查点恢复语义一致）。
        String lastFingerprint = null;
        int repeatedRounds = 0;
        int corrections = 0;
        int seq = 0, max = single ? 1 : config.path("maxSteps").asInt(25);
        long deadline = System.currentTimeMillis() + config.path("totalDurationMs").asLong(600000);
        for (int round = 0; round < max; round++) {
            if (System.currentTimeMillis() > deadline)
                throw new IllegalArgumentException("BUDGET_DURATION");
            if (!Boolean.TRUE.equals(
                    store.db()
                            .queryForObject(
                                    "select status='RUNNING' and lease_owner=? and"
                                        + " lease_until>now() from engine.node_instance where id=?",
                                    Boolean.class,
                                    node.get("lease_owner"),
                                    nid))) throw new IllegalArgumentException("LEASE_LOST");
            if (round > 0 && !recovered.has("round" + round)) store.consume(runId, "steps", 1, 25);
            Map<String, Object> request =
                    assembler.assemble(
                            config, inputs, history, tools, ChatHistory.load(store, run));
            request.put(
                    "modelBindingRef",
                    FrozenModels.reference(
                            TraceStore.json(run.get("snapshot")),
                            ((Number) request.get("modelEntryId")).longValue()));
            request.put("appCode", run.get("app_code"));
            request.put(
                    "callKind",
                    run.get("trigger_type").equals("EVALUATION")
                            ? "EVALUATION"
                            : run.get("trigger_type").equals("DEBUG") ? "DEBUG" : "FORMAL");
            request.put("runId", run.get("public_id"));
            request.put("attemptId", attempt);
            request.put("invocationRef", attempt + ":" + round);
            JsonNode answer =
                    new ModelExchange(store, clients)
                            .exchange(
                                    run,
                                    node,
                                    round,
                                    request,
                                    actor,
                                    text -> chunks.publish(runId, text));
            // Persist the complete provider return before examining or dispatching a tool call.
            store.response(attempt, answer);
            store.db()
                    .update(
                            "update engine.attempt set"
                                + " checkpoint=jsonb_set(checkpoint,ARRAY[?],?::jsonb,true) where"
                                + " id=?",
                            "round" + round,
                            Json.write(answer),
                            attempt);
            JsonNode calls = answer.path("toolCalls");
            if (!calls.isArray() || calls.isEmpty()) {
                var terminal =
                        (com.fasterxml.jackson.databind.node.ObjectNode)
                                Json.tree(
                                        Map.of(
                                                "text",
                                                answer.path("text").asText(),
                                                "usage",
                                                answer.path("usage"),
                                                "actualModel",
                                                answer.path("actualModel")));
                for (String field : List.of("structured", "outputValidation"))
                    if (answer.has(field)) terminal.set(field, answer.path(field));
                return terminal;
            }
            if (single) throw new IllegalArgumentException("LLM_NODE_TOOL_CALL_NOT_ALLOWED");
            // 契约 trace-model 终止规则情形6：连续 N=3 轮 (skillId+参数hash) 指纹完全重复
            // 才熔断；中途指纹变化即重置计数。此前"一次重复即熔断"与契约口径不符，
            // 会误杀模型自查、参数自纠等合理的两轮重复。指纹只取技能名+参数，
            // 不含调用 id——模型每轮生成的新 id 不应掩盖"同工具同参数"的重复事实。
            StringBuilder source = new StringBuilder();
            for (JsonNode call : calls)
                source.append(call.path("name").asText())
                        .append('\n')
                        .append(call.path("arguments").toString())
                        .append('\n');
            String fingerprint = Digest.sha256(source.toString());
            repeatedRounds = fingerprint.equals(lastFingerprint) ? repeatedRounds + 1 : 1;
            lastFingerprint = fingerprint;
            if (repeatedRounds >= 3) throw new IllegalArgumentException("NO_PROGRESS");
            List<Object> assistantCalls = new ArrayList<>();
            for (JsonNode call : calls)
                assistantCalls.add(
                        Map.of(
                                "id",
                                call.path("id").asText(),
                                "type",
                                "function",
                                "function",
                                Map.of(
                                        "name",
                                        call.path("name").asText(),
                                        "arguments",
                                        call.path("arguments").toString())));
            history.add(
                    Map.of(
                            "role",
                            "assistant",
                            "content",
                            answer.path("text").asText(),
                            "tool_calls",
                            assistantCalls));
            for (JsonNode call : calls) {
                String name = call.path("name").asText();
                JsonNode tool = null;
                for (JsonNode t : config.path("tools"))
                    if (t.path("name").asText(t.path("code").asText()).equals(name)) {
                        tool = t;
                        break;
                    }
                if (tool == null) throw new IllegalArgumentException("TOOL_NOT_DECLARED");

                JsonNode result;
                try {
                    result =
                            skill(
                                    run,
                                    node,
                                    tool.path("code").asText(name),
                                    tool.path("skillVersionId").asLong(),
                                    call.path("arguments"),
                                    ++seq,
                                    actor);
                } catch (IllegalArgumentException error) {
                    if (error.getMessage().equals("PARAM_ERROR") && corrections++ < 2)
                        result =
                                Json.tree(
                                        Map.of(
                                                "error",
                                                Map.of(
                                                        "semantic",
                                                        "PARAM_ERROR",
                                                        "message",
                                                        "请修正工具参数")));
                    else throw error;
                }
                history.add(
                        Map.of(
                                "role",
                                "tool",
                                "tool_call_id",
                                call.path("id").asText(),
                                "content",
                                ControlledReplay.enabled(run)
                                        ? ControlledReplay.modelToolContent(
                                                store,
                                                run,
                                                node,
                                                round + 1,
                                                call.path("id").asText(),
                                                result,
                                                actor)
                                        : result.toString()));
            }
        }
        throw new IllegalArgumentException("BUDGET_STEPS");
    }

    private static JsonNode effectiveConfig(JsonNode config, Map<String, Object> node) {
        var effective = (com.fasterxml.jackson.databind.node.ObjectNode) config.deepCopy();
        var definition = TraceStore.json(node.get("definition"));
        merge(
                effective,
                config.path("nodeOverrides").path(Objects.toString(node.get("node_def_id"), "")));
        merge(effective, definition.path("modelOverride"));
        // A node's explicit legacy outputContract is also frozen and must use the same policy
        // loop. Otherwise FlowDriver's later gate would undo PASS_WITH_FLAG and bypass RETRY.
        var contract = definition.path("outputContract");
        if (contract.has("schema") || contract.path("type").asText().equals("json")) {
            var output =
                    effective.path("output").isObject()
                            ? (com.fasterxml.jackson.databind.node.ObjectNode)
                                    effective.path("output").deepCopy()
                            : Json.object();
            output.set("format", contract);
            effective.set("output", output);
        }
        return effective;
    }

    private static void merge(
            com.fasterxml.jackson.databind.node.ObjectNode target, JsonNode overrides) {
        if (!overrides.isObject()) return;
        overrides
                .fields()
                .forEachRemaining(
                        field -> {
                            if (!field.getKey().equals("schema")
                                    && target.path(field.getKey()).isObject()
                                    && field.getValue().isObject())
                                merge(
                                        (com.fasterxml.jackson.databind.node.ObjectNode)
                                                target.path(field.getKey()),
                                        field.getValue());
                            else target.set(field.getKey(), field.getValue().deepCopy());
                        });
    }

    public JsonNode skill(
            Map<String, Object> run,
            Map<String, Object> node,
            String code,
            long version,
            JsonNode args,
            int seq,
            Actor actor) {
        if (version == 0) {
            JsonNode definition = clients.get("skill", "/internal/skills/" + code, actor);
            version =
                    definition
                            .path("skillVersionId")
                            .asLong(definition.path("currentVersionId").asLong());
        }
        if (version == 0) throw new IllegalArgumentException("SKILL_VERSION_NOT_RESOLVED");
        String key =
                Digest.sha256(run.get("id") + ":" + node.get("id") + ":" + seq + ":" + version);
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("runId", run.get("public_id"));
        req.put("nodeInstanceId", node.get("id"));
        req.put("attemptId", node.get("attemptId"));
        req.put("callSeq", seq);
        req.put("skillVersionId", version);
        req.put("arguments", args);
        req.put("idempotencyKey", key);
        req.put("leaseOwner", node.get("lease_owner"));
        req.put(
                "leaseUntil",
                ((java.sql.Timestamp) node.get("lease_until")).toInstant().toString());
        req.put("triggerType", run.get("trigger_type"));
        req.put("mock", Map.of()); // Authority.mock_snapshot is the sole approval for skill mocks.
        req.put("skillCode", code);
        var registration = new ToolCalls(store).register(Json.tree(req), actor);
        req.put("callId", registration.get("callId"));
        if (ControlledReplay.enabled(run) && registration.get("status").equals("REGISTERED"))
            return ControlledReplay.skill(
                    store, run, node, code, version, args, seq, registration, actor, clients);
        if (registration.get("status").equals("FAILED"))
            throw new IllegalArgumentException(
                    Objects.toString(registration.get("error_semantic"), "NON_RETRYABLE_FAILURE"));
        if (registration.get("status").equals("SUCCEEDED"))
            return interceptors.postResult(
                    code, Json.tree(registration.get("result")), actor);

        JsonNode result;
        try {
            // pre 链在登记/认领之后、外部派发之前：策略环节（超时/审计）可抛异常
            // 短路本次调用；已登记事实由既有恢复路径对账，链不负责回滚。
            interceptors.preDispatch(code, version, args, actor);
            result = clients.post("skill", "/internal/skills/" + code + "/execute", req, actor);
        } catch (org.springframework.web.client.RestClientException transport) {
            var authority =
                    new ToolCalls(store)
                            .get(((Number) registration.get("callId")).longValue(), actor);
            if (authority.get("status").equals("SUCCEEDED"))
                return Json.tree(authority.get("result"));
            if (Set.of("SUBMITTED", "EXECUTING", "UNKNOWN").contains(authority.get("status")))
                throw new cn.agentplatform.engine.domain.RuntimeFault(
                        "RESULT_UNKNOWN",
                        "toolCall." + registration.get("callId"),
                        "交付中断，等待持久事实对账");
            throw transport;
        }
        if (result.path("status").asText().equals("WAITING_APPROVAL")) {
            new ToolCalls(store)
                    .requestApproval(
                            ((Number) registration.get("callId")).longValue(),
                            result.path("confirmation"),
                            actor);
            throw new cn.agentplatform.engine.domain.RuntimeFault(
                    "WAITING_APPROVAL", "toolCall." + registration.get("callId"), "技能需要指定角色确认");
        }
        if (result.path("status").asText().equals("UNKNOWN"))
            throw new cn.agentplatform.engine.domain.RuntimeFault(
                    "RESULT_UNKNOWN", "toolCall." + registration.get("callId"), "技能执行结果未知，等待对账");
        if (!result.path("status").asText().equals("SUCCEEDED"))
            throw new IllegalArgumentException(
                    result.path("errorSemantic").asText("SKILL_" + result.path("status").asText()));
        return interceptors.postResult(
                code, result.has("result") ? result.path("result") : result.path("output"), actor);
    }

    public Map<String, Object> preview(JsonNode config, JsonNode inputs) {
        List<Object> tools = new ArrayList<>();
        config.path("tools").forEach(t -> tools.add(t.path("definition")));
        return assembler.assemble(config, inputs, List.of(), tools);
    }
}
