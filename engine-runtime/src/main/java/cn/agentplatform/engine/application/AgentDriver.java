package cn.agentplatform.engine.application;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.infra.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.StreamSupport;

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
        this.d2 = new cn.agentplatform.engine.harness.session.SessionStore(s);
    }

    /**
     * R-A7：工具调用 pre/post 拦截链，可选装配。手工构造（测试夹具）未注入时保持
     * 空链零开销；Spring 环境注入按 @Order 排序的链。
     */
    private ToolInterceptorChain interceptors = ToolInterceptorChain.EMPTY;
    // H1-5：D2 请求材料落库入口（复用 TraceStore 事务边界；每轮请求在出流前写 header+context）。
    private final cn.agentplatform.engine.harness.session.SessionStore d2;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void interceptors(ToolInterceptorChain chain) {
        this.interceptors = chain;
    }

    /**
     * PLAT-14 T14.2：工具输出注入模型上下文前的大响应策略，可选装配。手工构造（测试
     * 夹具）未注入时用引擎默认阈值（256KiB）；Spring 装配读 agent.tool.output-max-bytes。
     */
    private ToolOutputPolicy outputPolicy = ToolOutputPolicy.defaults();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void outputPolicy(ToolOutputPolicy policy) {
        this.outputPolicy = policy;
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
            // H1-5（D2，主方案 §5.1"模型可见即已记录"）：最终请求材料（含工具采纳集）先持久化
            // 成功，再发送模型请求。有会话关联的运行写入 session_event（request/header +
            // request/context）；独立运行无会话，事实仅保留运行链路（attempt.request_snapshot）。
            // 会话标识由 ChatSessions 在受理时注入快照（chatSession.sessionId）。
            var linkedSession = TraceStore.json(run.get("snapshot")).path("chatSession");
            if (linkedSession.has("sessionId")) {
                var adopted = new java.util.ArrayList<>();
                for (var t : config.path("tools"))
                    adopted.add(
                            Map.of(
                                    "toolId",
                                    t.path("name").asText(t.path("code").asText()),
                                    "versionRef",
                                    t.path("skillVersionId").asLong(0),
                                    "schemaDigest",
                                    t.path("contractDigest").asText("")));
                d2.append(
                        linkedSession.path("sessionId").asText(),
                        null,
                        cn.agentplatform.engine.harness.session.SessionEvents.REQUEST_HEADER,
                        Json.tree(Map.of("config", Json.read(Json.write(request)), "tools", adopted)),
                        null);
                d2.append(
                        linkedSession.path("sessionId").asText(),
                        null,
                        cn.agentplatform.engine.harness.session.SessionEvents.REQUEST_CONTEXT,
                        Json.tree(
                                Map.of(
                                        "modelEntryId",
                                        request.get("modelEntryId"),
                                        "modelBindingRef",
                                        String.valueOf(request.get("modelBindingRef")),
                                        "invocationRef",
                                        request.get("invocationRef"))),
                        null);
            }
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

                // H1-6 半卡③：会话审批策略重放生效——never 在服务层拦截（后注册的工具不能绕过），
                // 拒绝发生在登记之前（零出站）。策略=最后一个 approval/policy 事件。
                var policySession = TraceStore.json(run.get("snapshot")).path("chatSession");
                if (policySession.has("sessionId")
                        && approvalPolicyNever(policySession.path("sessionId").asText())
                        && !tool.path("execution").path("confirmRoles").isEmpty()) {
                    throw new cn.agentplatform.engine.domain.RuntimeFault(
                            "APPROVAL_DENIED",
                            "toolCall.policy",
                            "会话审批策略为 never：需审批工具被拒绝",
                            Map.of(
                                    "semantic",
                                    "APPROVAL_DENIED",
                                    "userMessage",
                                    "会话审批策略为 never，已拒绝需审批工具",
                                    "retryable",
                                    false));
                }

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
                } catch (cn.agentplatform.engine.domain.RuntimeFault fault) {
                    // 等待确认与结果未知是状态机信号，必须继续走各自的状态转移，不进模型反馈。
                    if (fault.semantic.equals("WAITING_APPROVAL")
                            || fault.semantic.equals("RESULT_UNKNOWN")) throw fault;
                    Map<String, Object> error = fault.structuredError();
                    boolean param = fault.semantic.equals("PARAM_ERROR");
                    // PLAT-05（方案 §4.16）：可解释拒绝（retryable=false 且带 userMessage）要以
                    // 工具结果交给模型解释，而不是让运行带着裸语义失败；模型不重试不可重试错误，
                    // 同参数重复由既有 NO_PROGRESS 指纹熔断兜底。corrections 上限约束吸收轮数。
                    boolean explainable =
                            error != null
                                    && !Objects.toString(error.get("userMessage"), "").isBlank()
                                    && !Boolean.TRUE.equals(error.get("retryable"));
                    if (corrections++ < 2 && (param || explainable)) {
                        var payload =
                                error != null
                                        ? new LinkedHashMap<String, Object>(error)
                                        : new LinkedHashMap<String, Object>();
                        payload.putIfAbsent("semantic", fault.semantic);
                        payload.putIfAbsent("message", "请修正工具参数");
                        result = Json.tree(Map.of("error", payload));
                    } else throw fault;
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
                // PLAT-14 T14.2：大响应在进入模型上下文前按引擎策略替换（超限不整块塞给
                // 模型、不静默截断）：无分页声明替换为 OUTPUT_TOO_LARGE 可读错误；声明了
                // 分页（contract.pagination）附分页元信息供模型带页参续拉。阈值内零变化。
                // 仅作用于模型可见面；调用事实（tool_call/技能侧 trace）保留原始完整结果。
                result =
                        outputPolicy.enforce(
                                tool,
                                result,
                                run.get("public_id")
                                        + ":"
                                        + nid
                                        + ":round"
                                        + round
                                        + ":"
                                        + call.path("id").asText());
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
        // INT-02：受控连接引用只从冻结快照取——模型参数、浏览器与草稿在派发时都不能自己指定
        // "连哪个端点"。引用随调用登记钉住（V226），重试与恢复不得改写。
        JsonNode frozenRefs =
                TraceStore.json(run.get("snapshot")).path("config").path("bindingRefs");
        if (frozenRefs.isArray() && !frozenRefs.isEmpty())
            req.put("bindingRefs", Json.tree(frozenRefs));
        var registration =
                store.atomic(
                        () -> {
                            var reg = new ToolCalls(store).register(Json.tree(req), actor);
                            // H1-6（contracts/h1-6-h1-8-stubs.md §1）：登记事务内追加唯一 tool/call
                            // 会话事件——重放复用首锚点（仅 REGISTERED 新登记写入；既有事件存在即跳过）。
                            var linked = TraceStore.json(run.get("snapshot")).path("chatSession");
                            if (linked.has("sessionId") && reg.get("status").equals("REGISTERED")) {
                                String sid = linked.path("sessionId").asText();
                                String callId = String.valueOf(reg.get("callId"));
                                int existing =
                                        store.db()
                                                .queryForObject(
                                                        "select count(*) from engine.session_event"
                                                            + " where session_id=? and type='tool/call'"
                                                            + " and data->>'callId'=?",
                                                        Integer.class,
                                                        sid,
                                                        callId);
                                if (existing == 0) {
                                    var reqNode = Json.tree(req);
                                    var data = Json.object();
                                    data.put("callId", callId);
                                    data.put("name", reqNode.path("skillCode").asText());
                                    data.set("args", reqNode.path("arguments"));
                                    data.put("skillVersionId", reqNode.path("skillVersionId").asLong());
                                    data.put("idempotencyKey", reqNode.path("idempotencyKey").asText());
                                    new cn.agentplatform.engine.harness.session.SessionStore(store)
                                            .append(
                                                    sid,
                                                    null,
                                                    cn.agentplatform.engine.harness.session.SessionEvents.TOOL_CALL,
                                                    data,
                                                    null);
                                }
                            }
                            return reg;
                        });
        req.put("callId", registration.get("callId"));
        if (ControlledReplay.enabled(run) && registration.get("status").equals("REGISTERED"))
            return ControlledReplay.skill(
                    store, run, node, code, version, args, seq, registration, actor, clients);
        if (registration.get("status").equals("FAILED")) {
            // PLAT-05：登记即 FAILED（既有事实/对账采纳）——统一错误对象原样携带，
            // 不再降级成裸 error_semantic；旧 {semantic,reason} 形态同样原样保留。
            var structured =
                    envelopeError(
                            (JsonNode) registration.get("result"),
                            Objects.toString(registration.get("error_semantic"), null));
            String semantic =
                    structured != null
                            ? Objects.toString(structured.get("semantic"), "")
                            : Objects.toString(
                                    registration.get("error_semantic"), "NON_RETRYABLE_FAILURE");
            throw new cn.agentplatform.engine.domain.RuntimeFault(
                    semantic.isBlank() ? "NON_RETRYABLE_FAILURE" : semantic,
                    "toolCall." + registration.get("callId"),
                    readable(structured, "既有调用已失败"),
                    structured);
        }
        if (registration.get("status").equals("SUCCEEDED"))
            return interceptors.postResult(
                    code, Json.tree(registration.get("result")), actor);

        JsonNode result;
        try {
            // pre 链在登记/认领之后、外部派发之前：策略环节（超时/审计）可抛异常
            // 短路本次调用；已登记事实由既有恢复路径对账，链不负责回滚。
            // H1-6 半卡③（服务层拦截，派发前）：会话策略 never 重放生效——即使工具在策略
            // 设置后才注册/派发也不能绕过；拒绝发生在出站之前（零外呼）。
            var policySession = TraceStore.json(run.get("snapshot")).path("chatSession");
            final long frozenVersion = version;
            var frozenSkill =
                    StreamSupport.stream(
                                    TraceStore.json(run.get("snapshot"))
                                            .path("config")
                                            .path("tools")
                                            .spliterator(),
                                    false)
                            .filter(
                                    t ->
                                            t.path("code").asText().equals(code)
                                                    && t.path("skillVersionId").asLong() == frozenVersion)
                            .findFirst()
                            .orElseGet(() -> Json.read("{}"));
            if (policySession.has("sessionId")
                    && approvalPolicyNever(policySession.path("sessionId").asText())
                    && !frozenSkill.path("execution").path("confirmRoles").isEmpty()) {
                throw new cn.agentplatform.engine.domain.RuntimeFault(
                        "APPROVAL_DENIED",
                        "toolCall.policy",
                        "会话审批策略为 never：需审批工具被拒绝",
                        Map.of(
                                "semantic",
                                "APPROVAL_DENIED",
                                "userMessage",
                                "会话审批策略为 never，已拒绝需审批工具",
                                "retryable",
                                false));
            }
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
            // H1-6 半卡③（fail-closed，主方案 §5.7）：审批通道不可用＝拒绝出站——记录后按
            // APPROVAL_UNAVAILABLE（接口稿 §2 已登记）上抛，不降级为放行。
            try {
                new ToolCalls(store)
                        .requestApproval(
                                ((Number) registration.get("callId")).longValue(),
                                result.path("confirmation"),
                                actor);
            } catch (RuntimeException unavailable) {
                throw new cn.agentplatform.engine.domain.RuntimeFault(
                        "APPROVAL_UNAVAILABLE",
                        "toolCall." + registration.get("callId"),
                        "审批通道不可用，已拒绝出站（fail-closed）: " + unavailable.getClass().getSimpleName() + ":" + unavailable.getMessage(),
                        Map.of(
                                "semantic",
                                "APPROVAL_UNAVAILABLE",
                                "userMessage",
                                "审批通道暂不可用，操作未执行",
                                "retryable",
                                false));
            }
            // H1-6 半卡③：approval/asked 会话事件关联原确认对象（actionPlanId，不另建确认对象）。
            var linkedAsk = TraceStore.json(run.get("snapshot")).path("chatSession");
            if (linkedAsk.has("sessionId")) {
                var asked = Json.object();
                asked.put("callId", String.valueOf(registration.get("callId")));
                var context = TraceStore.json(registration.get("approval_context"));
                if (context.has("actionPlanId")) asked.put("actionPlanId", context.path("actionPlanId").asLong());
                asked.set("roles", result.path("confirmation").path("roles"));
                new cn.agentplatform.engine.harness.session.SessionStore(store)
                        .append(
                                linkedAsk.path("sessionId").asText(),
                                null,
                                cn.agentplatform.engine.harness.session.SessionEvents.APPROVAL_ASKED,
                                asked,
                                null);
            }
            throw new cn.agentplatform.engine.domain.RuntimeFault(
                    "WAITING_APPROVAL", "toolCall." + registration.get("callId"), "技能需要指定角色确认");
        }
        if (result.path("status").asText().equals("UNKNOWN"))
            throw new cn.agentplatform.engine.domain.RuntimeFault(
                    "RESULT_UNKNOWN",
                    "toolCall." + registration.get("callId"),
                    "技能执行结果未知，等待对账",
                    envelopeError(result.path("result"), "RESULT_UNKNOWN"));
        if (!result.path("status").asText().equals("SUCCEEDED")) {
            // PLAT-05：执行响应 wrap 为 {status, result: envelope, errorSemantic?}。七字段错误
            // 对象在 result.error，顶层 errorSemantic 只是兼容键；这里解析并原样携带，
            // 不再丢弃成单个语义字符串（那会让 run.error/SSE/详情丢掉 userMessage 与 traceId）。
            var structured =
                    envelopeError(result.path("result"), result.path("errorSemantic").asText(null));
            String semantic =
                    structured != null
                            ? Objects.toString(structured.get("semantic"), "")
                            : result.path("errorSemantic")
                                    .asText("SKILL_" + result.path("status").asText());
            throw new cn.agentplatform.engine.domain.RuntimeFault(
                    semantic.isBlank()
                            ? "SKILL_" + result.path("status").asText()
                            : semantic,
                    "toolCall." + registration.get("callId"),
                    readable(
                            structured,
                            "技能调用失败（" + result.path("status").asText() + "）"),
                    structured);
        }
        return interceptors.postResult(
                code, result.has("result") ? result.path("result") : result.path("output"), actor);
    }

    /**
     * PLAT-05（contracts/skill-invocation.md §11.1）：解析技能 envelope 的统一错误对象。
     * 七字段（semantic/code/category/retryable/userMessage/detail/traceId）与兼容键
     * reason/message 原样保留（含显式 null，不补发明字段）；旧 {semantic,reason} 形态
     * 原样保留。envelope 没有 error 对象时回退到调用方给出的兼容语义键
     * （wrap 的 errorSemantic / 调用视图的 error_semantic）；两者皆无返回 null，
     * 由调用方用 SKILL_&lt;status&gt; 兜底。纯函数，便于直接验证解析契约。
     */
    static Map<String, Object> envelopeError(JsonNode envelope, String fallbackSemantic) {
        var error = envelope.path("error");
        if (error.isObject() && !error.isEmpty()) {
            var parsed = new LinkedHashMap<String, Object>();
            String semantic = error.path("semantic").asText("");
            parsed.put(
                    "semantic",
                    semantic.isBlank() ? Objects.toString(fallbackSemantic, "") : semantic);
            for (String field :
                    List.of(
                            "code",
                            "category",
                            "retryable",
                            "userMessage",
                            "detail",
                            "traceId",
                            "reason",
                            "message"))
                if (error.has(field))
                    parsed.put(field, Json.mapper().convertValue(error.path(field), Object.class));
            if (!Objects.toString(parsed.get("semantic"), "").isBlank()) return parsed;
        }
        if (fallbackSemantic != null && !fallbackSemantic.isBlank())
            return new LinkedHashMap<>(Map.of("semantic", fallbackSemantic));
        return null;
    }

    /** 可读原因：统一错误对象优先 userMessage，其次 reason/message；缺失时用兜底文案。 */
    private static String readable(Map<String, Object> error, String fallback) {
        if (error != null)
            for (String key : List.of("userMessage", "reason", "message")) {
                String value = Objects.toString(error.get(key), "");
                if (!value.isBlank()) return value;
            }
        return fallback;
    }

    /** H1-6 半卡③：重放会话 approval/policy 事件取生效策略（最后一个事件生效；无事件=默认 ask）。 */
    private boolean approvalPolicyNever(String sessionId) {
        for (var e :
                new cn.agentplatform.engine.harness.session.SessionStore(store)
                        .read(sessionId, 0, 0)) {
            if (cn.agentplatform.engine.harness.session.SessionEvents.APPROVAL_POLICY.equals(e.type())
                    && "never".equals(e.data().path("mode").asText())) return true;
        }
        return false;
    }

    public Map<String, Object> preview(JsonNode config, JsonNode inputs) {
        List<Object> tools = new ArrayList<>();
        config.path("tools").forEach(t -> tools.add(t.path("definition")));
        return assembler.assemble(config, inputs, List.of(), tools);
    }
}
