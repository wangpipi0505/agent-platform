package cn.agentplatform.engine.application;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.domain.*;
import cn.agentplatform.engine.infra.*;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/**
 * Authorized recorded business/model responses; only lease-checked infrastructure artifact copying
 * uses HTTP.
 */
public final class ControlledReplay {
    private ControlledReplay() {}

    public static boolean enabled(Map<String, Object> run) {
        return TraceStore.json(run.get("snapshot")).path("controlledReplay").has("sourceRunId");
    }

    private static Map<String, Object> source(
            TraceStore store, Map<String, Object> run, Actor actor) {
        if (!run.get("trigger_type").equals("DEBUG"))
            throw new PlatformException(409, "REPLAY_DEBUG_ONLY", "受控复现仅允许调试运行");
        return store.run(
                TraceStore.json(run.get("snapshot"))
                        .path("controlledReplay")
                        .path("sourceRunId")
                        .asText(),
                actor);
    }

    public static void guard(JsonNode guard, Actor actor) {
        ReplayScope.permissions(guard, actor, false);
    }

    public static void preflight(TraceStore store, Map<String, Object> source, Actor actor) {
        // Validate all recorded tool scopes before any saved model answer or artifact can be
        // reused.
        // Otherwise a cached terminal model answer could expose old wider tool data without
        // invoking a
        // tool again.
        JsonNode snapshot = TraceStore.json(source.get("snapshot"));
        for (var call :
                store.db()
                        .queryForList(
                                "select * from engine.tool_call where run_id=? order by id",
                                source.get("id"))) {
            JsonNode contract = null;
            for (JsonNode candidate : snapshot.path("config").path("tools"))
                if (candidate.path("code").asText().equals(call.get("skill_code"))
                        && candidate.path("skillVersionId").asLong()
                                == TraceStore.number(call, "skill_version_id"))
                    contract = candidate;
            if (contract == null
                    || !contract.path("contractDigest")
                            .asText()
                            .equals(call.get("skill_contract_sha256")))
                throw new PlatformException(409, "REPLAY_SKILL_RECORD_MISSING", "源技能合同材料不完整");
            JsonNode secured =
                    ReplayScope.secure(
                            contract.path("guard"), actor, TraceStore.json(call.get("args")));
            String recorded =
                    TraceStore.json(call.get("result"))
                            .path("guardTrace")
                            .path("argsDigest")
                            .asText();
            if (!recorded.matches("[a-f0-9]{64}")
                    || !recorded.equals(Digest.sha256(Json.canonical(secured))))
                throw new RuntimeFault("NO_PERMISSION", "guardedArguments", "源运行技能事实授权范围与当前不一致");
        }
    }

    public static JsonNode model(
            TraceStore store,
            Map<String, Object> run,
            Map<String, Object> node,
            int round,
            int outputAttempt,
            Map<String, Object> raw,
            Actor actor) {
        var original = source(store, run, actor);
        var rows =
                store.db()
                        .queryForList(
                                "select m.* from engine.model_invocation m join"
                                    + " engine.node_instance n on n.id=m.node_instance_id where"
                                    + " m.run_id=? and n.node_def_id is not distinct from ? and"
                                    + " m.round_no=? and m.kind='MAIN' and m.output_attempt_no=?"
                                    + " and m.response is not null",
                                original.get("id"),
                                node.get("node_def_id"),
                                round,
                                outputAttempt);
        if (rows.size() != 1)
            throw new PlatformException(409, "REPLAY_MODEL_RECORD_MISSING", "缺少精确节点与轮次的模型记录");
        var saved = rows.getFirst();
        var sourceRequest = TraceStore.json(saved.get("source_request"));
        for (String field : List.of("messages", "toolDefinitions", "params", "modelBindingRef"))
            if (!Json.canonical(sourceRequest.path(field)).equals(Json.canonical(raw.get(field))))
                throw new PlatformException(
                        409, "REPLAY_MODEL_INPUT_CHANGED", "复现模型输入与保存记录不符：" + field);
        return store.atomic(
                () -> {
                    long rid = TraceStore.number(run, "id"), nid = TraceStore.number(node, "id");
                    fence(store, rid, nid, node);
                    var finalRequest =
                            (com.fasterxml.jackson.databind.node.ObjectNode)
                                    TraceStore.json(saved.get("final_request")).deepCopy();
                    finalRequest.put("runId", run.get("public_id").toString());
                    finalRequest.put("attemptId", TraceStore.number(node, "attemptId"));
                    finalRequest.put(
                            "invocationRef",
                            "replay:" + nid + ":" + round + ":output:" + outputAttempt);
                    finalRequest.set(
                            "replayProvenance",
                            Json.tree(
                                    Map.of(
                                            "sourceRunId",
                                            original.get("public_id"),
                                            "sourceInvocationId",
                                            saved.get("id"),
                                            "sourceResponseSha256",
                                            Digest.sha256(
                                                    Json.canonical(
                                                            TraceStore.json(
                                                                    saved.get("response")))))));
                    store.db()
                            .update(
                                    "insert into"
                                        + " engine.model_invocation(run_id,node_instance_id,round_no,kind,output_attempt_no,source_request,final_request,response,status,is_mock,source_invocation_id,usage_accounted)"
                                        + " values(?,?,?,'MAIN',?,?::jsonb,?::jsonb,?::jsonb,'SUCCEEDED',true,?,true)"
                                        + " on conflict(node_instance_id,round_no,kind,output_attempt_no)"
                                        + " do nothing",
                                    rid,
                                    nid,
                                    round,
                                    outputAttempt,
                                    Json.write(raw),
                                    Json.write(finalRequest),
                                    saved.get("response").toString(),
                                    saved.get("id"));
                    store.freeze(TraceStore.number(node, "attemptId"), finalRequest);
                    store.event(
                            rid,
                            "MODEL_REPLAYED",
                            Map.of(
                                    "sourceInvocationId",
                                    saved.get("id"),
                                    "nodeInstanceId",
                                    nid,
                                    "round",
                                    round,
                                    "isMock",
                                    true));
                    return TraceStore.json(saved.get("response"));
                });
    }

    public static JsonNode skill(
            TraceStore store,
            Map<String, Object> run,
            Map<String, Object> node,
            String code,
            long version,
            JsonNode args,
            int seq,
            Map<String, Object> registration,
            Actor actor) {
        return skill(store, run, node, code, version, args, seq, registration, actor, null);
    }

    public static JsonNode skill(
            TraceStore store,
            Map<String, Object> run,
            Map<String, Object> node,
            String code,
            long version,
            JsonNode args,
            int seq,
            Map<String, Object> registration,
            Actor actor,
            RuntimeClients storage) {
        var original = source(store, run, actor);
        JsonNode contract = null;
        for (JsonNode skill : TraceStore.json(run.get("snapshot")).path("config").path("tools"))
            if (skill.path("code").asText().equals(code)
                    && skill.path("skillVersionId").asLong() == version) contract = skill;
        if (contract == null)
            throw new PlatformException(409, "REPLAY_SKILL_NOT_FROZEN", "技能不在保存词表");
        JsonNode secured = ReplayScope.secure(contract.path("guard"), actor, args);
        if (!contract.path("inputSchema").isObject() || !contract.path("outputSchema").isObject())
            throw new PlatformException(409, "REPLAY_SCHEMA_MISSING", "原记录缺少输入输出Schema");
        JsonGate.validate(contract.path("inputSchema"), args, "arguments", "PARAM_ERROR");
        JsonGate.validate(contract.path("inputSchema"), secured, "guardedArguments", "PARAM_ERROR");
        var rows =
                store.db()
                        .queryForList(
                                "select t.* from engine.tool_call t join engine.node_instance n on"
                                    + " n.id=t.node_instance_id where t.run_id=? and n.node_def_id"
                                    + " is not distinct from ? and t.call_seq=? and t.skill_code=?"
                                    + " and t.skill_version_id=? and t.args_digest=? and"
                                    + " t.skill_contract_sha256=? and t.status in"
                                    + " ('SUCCEEDED','FAILED')",
                                original.get("id"),
                                node.get("node_def_id"),
                                seq,
                                code,
                                version,
                                Digest.sha256(Json.canonical(args)),
                                contract.path("contractDigest").asText());
        if (rows.size() != 1)
            throw new PlatformException(409, "REPLAY_SKILL_RECORD_MISSING", "未找到参数与合同一致的技能事实");
        var saved = rows.getFirst();
        if (((Number) saved.get("readonly_retry_count")).intValue() > 0)
            throw new PlatformException(
                    409, "REPLAY_RETRY_HISTORY_UNSUPPORTED", "该源调用含多代重试，暂不支持按终态折叠复现");
        JsonNode sourceResult = TraceStore.json(saved.get("result"));
        String guardedDigest = sourceResult.path("guardTrace").path("argsDigest").asText();
        if (!guardedDigest.matches("[a-f0-9]{64}")
                || !guardedDigest.equals(Digest.sha256(Json.canonical(secured))))
            throw new RuntimeFault(
                    "NO_PERMISSION", "guardedArguments", "源事实的有效参数与当前守卫结果不一致，拒绝复用更宽范围");
        JsonNode sourceOwner = sourceResult.path("owner");
        if (!sourceOwner.path("tenantId").asText().equals(actor.tenantId())
                || !sourceOwner.path("principalId").asText().equals(actor.principalId())
                || !sourceOwner.path("runId").asText().equals(original.get("public_id"))
                || sourceOwner.path("callId").asLong() != TraceStore.number(saved, "id")
                || sourceOwner.path("nodeInstanceId").asLong()
                        != TraceStore.number(saved, "node_instance_id")
                || sourceOwner.path("skillVersionId").asLong() != version
                || !sourceOwner.path("skillCode").asText().equals(code)
                || !sourceResult.path("status").asText().equals(saved.get("status")))
            throw new PlatformException(409, "REPLAY_SOURCE_ENVELOPE_INVALID", "原事实信封归属与权威调用不一致");
        // The trusted same-contract fact already passed raw outputSchema before returnSpec
        // projection.
        // Revalidating projected scalar/null structured as the original HTTP object would reject
        // valid
        // facts.
        var result = (com.fasterxml.jackson.databind.node.ObjectNode) sourceResult.deepCopy();
        long call = ((Number) registration.get("callId")).longValue();
        result.set(
                "owner",
                Json.tree(
                        Map.of(
                                "tenantId",
                                actor.tenantId(),
                                "principalId",
                                actor.principalId(),
                                "runId",
                                run.get("public_id"),
                                "nodeInstanceId",
                                node.get("id"),
                                "attemptId",
                                node.get("attemptId"),
                                "callId",
                                call,
                                "skillVersionId",
                                version,
                                "skillCode",
                                code)));
        result.put("isMock", true);
        result.set(
                "sourceProvenance",
                Json.tree(
                        Map.of(
                                "mode",
                                "CONTROLLED",
                                "sourceRunId",
                                original.get("public_id"),
                                "sourceCallId",
                                saved.get("id"),
                                "sourceOwner",
                                sourceOwner,
                                "sourceEnvelopeSha256",
                                Digest.sha256(Json.canonical(sourceResult)))));
        result.set(
                "guardTrace",
                Json.tree(Map.of("roles", actor.roles(), "argsDigest", guardedDigest)));
        var calls = new ToolCalls(store);
        var claim =
                Json.tree(
                        Map.of(
                                "owner",
                                node.get("lease_owner"),
                                "attemptId",
                                node.get("attemptId")));
        var claimed = calls.claim(call, claim, actor);
        if (!Boolean.TRUE.equals(claimed.get("claimed"))
                && !claimed.get("status").equals("SUCCEEDED")
                && !claimed.get("status").equals("FAILED"))
            throw new PlatformException(409, "REPLAY_CLAIM_REJECTED", "复现调用租约失效");
        String sourceDigest = Digest.sha256(Json.canonical(sourceResult));
        store.atomic(
                () -> {
                    fence(store, TraceStore.number(run, "id"), TraceStore.number(node, "id"), node);
                    var current =
                            store.db()
                                    .queryForMap(
                                            "select status,claimed_by,attempt_id from"
                                                    + " engine.tool_call where id=? for update",
                                            call);
                    if (!current.get("status").equals("EXECUTING")
                            || !Objects.equals(current.get("claimed_by"), node.get("lease_owner"))
                            || TraceStore.number(current, "attempt_id")
                                    != TraceStore.number(node, "attemptId"))
                        throw new PlatformException(409, "REPLAY_CLAIM_REJECTED", "当前复现租约已失效");
                    store.db()
                            .update(
                                    "update engine.tool_call set"
                                        + " mock_source_call_id=?,mock_source_digest=? where id=?",
                                    saved.get("id"),
                                    sourceDigest,
                                    call);
                    return null;
                });
        result.set("fileRefs", Json.tree(List.of()));
        if (!sourceResult.path("fileRefs").isEmpty()) {
            if (storage == null)
                throw new PlatformException(
                        409, "REPLAY_ARTIFACT_COPY_REQUIRED", "附件必须由受控存储端口生成当前运行引用");
            // Infrastructure-only copy: source pointers and current lease are independently
            // rechecked by
            // skill.
            JsonNode copied =
                    storage.post(
                            "skill",
                            "/internal/skill-facts/" + saved.get("id") + "/reproduce-artifacts",
                            Map.of(
                                    "callId",
                                    call,
                                    "nodeInstanceId",
                                    node.get("id"),
                                    "attemptId",
                                    node.get("attemptId"),
                                    "leaseOwner",
                                    node.get("lease_owner"),
                                    "runId",
                                    run.get("public_id")),
                            actor);
            result.set(
                    "fileRefs",
                    newArtifactRefs(
                            sourceResult.path("fileRefs"),
                            copied.path("fileRefs"),
                            run.get("public_id").toString()));
        }
        var fact = (com.fasterxml.jackson.databind.node.ObjectNode) claim.deepCopy();
        fact.put("status", saved.get("status").toString());
        fact.set("result", result);
        fact.put("durationMs", 0);
        fact.put("isMock", true);
        if (saved.get("error_semantic") != null)
            fact.put("errorSemantic", saved.get("error_semantic").toString());
        if (!Boolean.TRUE.equals(calls.fact(call, fact, actor).get("accepted")))
            throw new PlatformException(409, "REPLAY_FACT_REJECTED", "复现事实租约提交失败");
        store.db()
                .update(
                        "update engine.tool_call set mock_source_call_id=?,mock_source_digest=?"
                                + " where id=?",
                        saved.get("id"),
                        Digest.sha256(Json.canonical(sourceResult)),
                        call);
        if (saved.get("status").equals("FAILED"))
            throw new IllegalArgumentException(
                    Objects.toString(saved.get("error_semantic"), "NON_RETRYABLE_FAILURE"));
        return result;
    }

    private static JsonNode newArtifactRefs(JsonNode oldRefs, JsonNode refs, String runId) {
        if (!refs.isArray() || refs.size() != oldRefs.size())
            throw new PlatformException(502, "REPLAY_ARTIFACT_COPY_INVALID", "存储复制未返回完整的新运行附件");
        Set<String> oldIds = new HashSet<>();
        oldRefs.forEach(ref -> oldIds.add(ref.path("artifactId").asText()));
        Set<String> ids = new HashSet<>();
        var safe = Json.mapper().createArrayNode();
        for (JsonNode ref : refs) {
            String id = ref.path("artifactId").asText();
            if (!id.matches("[A-Za-z0-9_-]{1,128}")
                    || oldIds.contains(id)
                    || !ids.add(id)
                    || !ref.path("downloadUrl")
                            .asText()
                            .equals("/admin/artifacts/" + id + "/download")
                    || !ref.path("externalDownloadUrl")
                            .asText()
                            .equals("/v1/runs/" + runId + "/artifacts/" + id + "/download"))
                throw new PlatformException(
                        502, "REPLAY_ARTIFACT_COPY_INVALID", "存储复制返回了源附件或错误运行路径");
            var item = Json.object();
            for (String key :
                    List.of(
                            "artifactId",
                            "downloadUrl",
                            "externalDownloadUrl",
                            "sha256",
                            "bytes",
                            "contentType",
                            "summary",
                            "fileName",
                            "name")) if (ref.hasNonNull(key)) item.set(key, ref.path(key));
            safe.add(item);
        }
        return safe;
    }

    public static String modelToolContent(
            TraceStore store,
            Map<String, Object> run,
            Map<String, Object> node,
            int nextRound,
            String toolCallId,
            JsonNode currentResult,
            Actor actor) {
        var original = source(store, run, actor);
        var rows =
                store.db()
                        .queryForList(
                                "select m.source_request from engine.model_invocation m join"
                                    + " engine.node_instance n on n.id=m.node_instance_id where"
                                    + " m.run_id=? and n.node_def_id is not distinct from ? and"
                                    + " m.round_no=? and m.kind='MAIN' and m.output_attempt_no=0",
                                original.get("id"),
                                node.get("node_def_id"),
                                nextRound);
        if (rows.size() != 1)
            throw new PlatformException(409, "REPLAY_MODEL_MATERIAL_MISSING", "缺少下一轮保存的模型工具消息材料");
        for (JsonNode message :
                TraceStore.json(rows.getFirst().get("source_request")).path("messages"))
            if (message.path("role").asText().equals("tool")
                    && message.path("tool_call_id").asText().equals(toolCallId))
                return message.path("content").asText();
        throw new PlatformException(409, "REPLAY_MODEL_MATERIAL_MISSING", "保存材料缺少对应工具调用结果");
    }

    private static void fence(TraceStore store, long rid, long nid, Map<String, Object> node) {
        var run = store.lockRun(rid);
        var current =
                store.db()
                        .queryForMap(
                                "select lease_owner,status,lease_until>now() as live from"
                                        + " engine.node_instance where id=? for update",
                                nid);
        if (!run.get("status").equals("RUNNING")
                || !current.get("status").equals("RUNNING")
                || !Objects.equals(current.get("lease_owner"), node.get("lease_owner"))
                || !Boolean.TRUE.equals(current.get("live")))
            throw new PlatformException(409, "LEASE_LOST", "复现租约失效");
    }
}
