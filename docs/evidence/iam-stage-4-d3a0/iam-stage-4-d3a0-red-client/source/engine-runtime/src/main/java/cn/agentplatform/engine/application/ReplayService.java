package cn.agentplatform.engine.application;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.infra.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Service;

import java.util.*;

/** Replay presents recorded decisions. Re-execution is a distinct, explicit new DEBUG run. */
@Service
public class ReplayService {
    private final TraceStore store;
    private final RunService runs;

    public ReplayService(TraceStore store, RunService runs) {
        this.store = store;
        this.runs = runs;
    }

    public Map<String, Object> log(String id, Actor actor) {
        var run = store.run(id, actor);
        long rid = TraceStore.number(run, "id");
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("sourceRunId", id);
        material.put("sourceStatus", run.get("status"));
        material.put("snapshot", TraceStore.json(run.get("snapshot")));
        material.put("inputs", TraceStore.json(run.get("inputs")));
        material.put("output", TraceStore.json(run.get("output")));
        material.put("versionRefs", TraceStore.json(run.get("version_refs")));
        material.put(
                "events",
                normalizeRows(
                        store.db()
                                .queryForList(
                                        "select seq,type,payload,created_at from engine.run_event"
                                            + " where run_id=? order by seq",
                                        rid)));
        material.put("nodes", normalizeRows(store.nodes(rid)));
        material.put(
                "modelInvocations",
                normalizeRows(
                        store.db()
                                .queryForList(
                                        "select * from engine.model_invocation where run_id=? order"
                                            + " by id",
                                        rid)));
        material.put(
                "toolCalls",
                normalizeRows(
                        store.db()
                                .queryForList(
                                        "select * from engine.tool_call where run_id=? order by"
                                                + " node_instance_id,call_seq",
                                        rid)));
        material.put(
                "attempts",
                normalizeRows(
                        store.db()
                                .queryForList(
                                        "select a.* from engine.attempt a join engine.node_instance"
                                            + " n on n.id=a.node_instance_id where n.run_id=? order"
                                            + " by a.id",
                                        rid)));
        material.put(
                "notes",
                normalizeRows(
                        store.db()
                                .queryForList(
                                        "select a.* from engine.attempt_note a join"
                                            + " engine.node_instance n on n.id=a.node_instance_id"
                                            + " where n.run_id=? order by a.id",
                                        rid)));
        material.put("sourceDigest", Digest.sha256(Json.canonical(material)));
        material.put("mode", "LOG");
        material.put("externalCalls", 0);
        return material;
    }

    public Map<String, Object> reproduce(String id, JsonNode req, Actor actor) {
        actor.requireRole("EDITOR");
        var material = log(id, actor);
        confirmed(material, req);
        return runs.reproduceFrozen(id, req, actor);
    }

    public Map<String, Object> recordMaterial(String id, JsonNode req, Actor actor) {
        actor.requireRole("EDITOR");
        var material = log(id, actor);
        confirmed(material, req);
        return store.atomic(
                () -> {
                    String request = req.path("requestId").asText();
                    var prior =
                            store.db()
                                    .queryForList(
                                            "select * from engine.reproduction where tenant_id=?"
                                                + " and principal_id=? and request_id=?",
                                            actor.tenantId(),
                                            actor.principalId(),
                                            request);
                    if (!prior.isEmpty()) {
                        if (!prior.getFirst()
                                .get("source_digest")
                                .equals(req.path("sourceDigest").asText()))
                            throw new PlatformException(409, "REQUEST_CONFLICT", "复现请求号已绑定其他记录");
                        return get(prior.getFirst().get("public_id").toString(), actor);
                    }
                    String replay = "rp_" + UUID.randomUUID().toString().replace("-", "");
                    var source = store.run(id, actor);
                    store.db()
                            .update(
                                    "insert into"
                                        + " engine.reproduction(public_id,source_run_id,tenant_id,principal_id,request_id,source_digest,material)"
                                        + " values(?,?,?,?,?,?,?::jsonb)",
                                    replay,
                                    source.get("id"),
                                    actor.tenantId(),
                                    actor.principalId(),
                                    request,
                                    req.path("sourceDigest").asText(),
                                    Json.write(material));
                    return get(replay, actor);
                });
    }

    public Map<String, Object> get(String id, Actor actor) {
        var rows =
                store.db()
                        .queryForList(
                                "select material from engine.reproduction where public_id=? and"
                                    + " tenant_id=? and principal_id=?",
                                id,
                                actor.tenantId(),
                                actor.principalId());
        if (rows.isEmpty()) throw new PlatformException(404, "REPRODUCTION_NOT_FOUND", "复现记录不存在");
        return Map.of(
                "reproductionId",
                id,
                "mode",
                "CONTROLLED",
                "status",
                "RECORDED",
                "externalCalls",
                0,
                "material",
                TraceStore.json(rows.getFirst().get("material")));
    }

    public Map<String, Object> reexecute(String id, JsonNode req, Actor actor) {
        actor.requireRole("EDITOR");
        var material = log(id, actor);
        confirmed(material, req);
        return runs.reexecuteFrozen(id, req, actor);
    }

    private void confirmed(Map<String, Object> material, JsonNode req) {
        if (req.path("requestId").asText().isBlank()
                || req.path("requestId").asText().length() > 48
                || !Objects.equals(material.get("sourceDigest"), req.path("sourceDigest").asText()))
            throw new PlatformException(
                    409, "REPLAY_CONFIRMATION_REQUIRED", "请确认读取到的完整源记录摘要并提供请求号");
        if (!Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(material.get("sourceStatus")))
            throw new PlatformException(409, "SOURCE_RUN_ACTIVE", "源运行尚未结束");
    }

    private List<Map<String, Object>> normalizeRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var row : rows) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            row.forEach(
                    (key, value) ->
                            normalized.put(
                                    key,
                                    value != null
                                                    && value.getClass()
                                                            .getSimpleName()
                                                            .equals("PGobject")
                                            ? TraceStore.json(value)
                                            : value));
            result.add(normalized);
        }
        return result;
    }
}
