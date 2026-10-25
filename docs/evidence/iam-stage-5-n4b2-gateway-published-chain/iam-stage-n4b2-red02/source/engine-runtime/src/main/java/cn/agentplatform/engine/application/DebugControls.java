package cn.agentplatform.engine.application;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.infra.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class DebugControls {
    private final TraceStore store;

    public DebugControls(TraceStore store) {
        this.store = store;
    }

    public Map<String, Object> get(String id, Actor actor) {
        var run = store.run(id, actor);
        debug(run);
        return Map.of(
                "revision",
                run.get("debug_controls_revision"),
                "controls",
                TraceStore.json(run.get("snapshot")).path("debugControls"));
    }

    public Map<String, Object> update(String id, JsonNode req, Actor actor) {
        actor.requireRole("EDITOR");
        return store.atomic(
                () -> {
                    var owned = store.run(id, actor);
                    var run = store.lockRun(TraceStore.number(owned, "id"));
                    debug(run);
                    if (!req.has("expectedRevision")
                            || req.path("expectedRevision").asInt(-1)
                                    != TraceStore.number(run, "debug_controls_revision"))
                        throw new PlatformException(409, "DEBUG_CONTROLS_CONFLICT", "调试控制版本冲突");
                    if (Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(run.get("status")))
                        throw new PlatformException(409, "RUN_TERMINAL", "运行已结束");
                    Set<String> nodes = new HashSet<>();
                    for (var node : store.nodes(TraceStore.number(run, "id")))
                        nodes.add(Objects.toString(node.get("node_def_id")));
                    if (!req.path("breakpoints").isArray() || !req.path("mocks").isArray())
                        throw new PlatformException(400, "DEBUG_CONTROLS_INVALID", "断点与替身必须是数组");
                    for (JsonNode point : req.path("breakpoints"))
                        if (!nodes.contains(point.asText()))
                            throw new PlatformException(400, "BREAKPOINT_UNKNOWN", "断点节点不存在");
                    Set<String> skills = new HashSet<>();
                    for (JsonNode skill :
                            TraceStore.json(run.get("snapshot")).path("config").path("tools"))
                        skills.add(skill.path("code").asText());
                    Set<String> seen = new HashSet<>();
                    for (JsonNode mock : req.path("mocks")) {
                        String code = mock.path("skillCode").asText();
                        if (!skills.contains(code)
                                || !seen.add(code)
                                || !Set.of("FIXED", "LAST_REAL")
                                        .contains(mock.path("mode").asText())
                                || mock.path("mode").asText().equals("FIXED")
                                        && !mock.path("fixedResponse").isObject())
                            throw new PlatformException(400, "MOCK_INVALID", "替身必须绑定冻结技能且模式与结果合法");
                    }
                    var controls =
                            Json.tree(
                                    Map.of(
                                            "breakpoints",
                                            req.path("breakpoints"),
                                            "mocks",
                                            req.path("mocks")));
                    store.db()
                            .update(
                                    "update engine.run set"
                                        + " snapshot=jsonb_set(snapshot,'{debugControls}',?::jsonb,true),debug_controls_revision=debug_controls_revision+1"
                                        + " where id=?",
                                    Json.write(controls),
                                    run.get("id"));
                    store.event(
                            TraceStore.number(run, "id"),
                            "DEBUG_CONTROLS_CHANGED",
                            Map.of(
                                    "revision",
                                    req.path("expectedRevision").asInt() + 1,
                                    "controls",
                                    controls));
                    return get(id, actor);
                });
    }

    public JsonNode lastReal(
            long version,
            String code,
            String argsDigest,
            String contractDigest,
            Long sourceCallId,
            Actor actor) {
        if (contractDigest == null || !contractDigest.matches("[a-f0-9]{64}"))
            throw new PlatformException(409, "MOCK_CONTRACT_REQUIRED", "复用缺少冻结合同摘要");
        var rows =
                store.db()
                        .queryForList(
                                "select t.id,t.result,r.public_id,r.principal_id from"
                                    + " engine.tool_call t join engine.run r on r.id=t.run_id where"
                                    + " r.tenant_id=? and r.principal_id=? and t.skill_version_id=?"
                                    + " and t.skill_code=? and t.args_digest=? and"
                                    + " t.skill_contract_sha256=? and t.status='SUCCEEDED' and"
                                    + " t.is_mock=false and t.result is not null and (?::bigint is"
                                    + " null or t.id=?) order by t.id desc limit 1",
                                actor.tenantId(),
                                actor.principalId(),
                                version,
                                code,
                                argsDigest,
                                contractDigest,
                                sourceCallId,
                                sourceCallId);
        if (rows.isEmpty())
            throw new PlatformException(404, "LAST_REAL_NOT_AUTHORIZED", "没有可读取且参数合同匹配的真实结果");
        var row = rows.getFirst();
        JsonNode result = TraceStore.json(row.get("result"));
        return Json.tree(
                Map.of(
                        "skillCode",
                        code,
                        "mode",
                        "FIXED",
                        "fixedResponse",
                        result,
                        "sourceKind",
                        "LAST_REAL",
                        "sourceCallId",
                        row.get("id"),
                        "sourceRunId",
                        row.get("public_id"),
                        "sourcePrincipal",
                        row.get("principal_id"),
                        "sourceDigest",
                        Digest.sha256(Json.canonical(result))));
    }

    private void debug(Map<String, Object> run) {
        if (!run.get("trigger_type").equals("DEBUG"))
            throw new PlatformException(409, "DEBUG_ONLY", "仅调试运行允许替身和断点配置");
    }
}
