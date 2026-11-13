package cn.agentplatform.engine.application;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.domain.InputDeclarations;
import cn.agentplatform.engine.domain.RunAdmissionRejected;
import cn.agentplatform.engine.infra.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ChatSessions {
    private final TraceStore store;
    private final RuntimeClients clients;
    private final RunService runs;
    // H1-2：会话事件日志写入口（复用 TraceStore 事务边界，轮次预留事务内同事务落 turn/start+user/message）。
    private final cn.agentplatform.engine.harness.session.SessionStore turnEvents;

    public ChatSessions(TraceStore store, RuntimeClients clients, RunService runs) {
        this.store = store;
        this.clients = clients;
        this.runs = runs;
        this.turnEvents = new cn.agentplatform.engine.harness.session.SessionStore(store);
    }

    public Map<String, Object> create(String code, Actor actor) {
        return create(code, null, actor);
    }

    /**
     * INT-06：会话创建时可提议 configReleaseId，引用由引擎向 app 核验后固定；
     * 未提议时保持旧行为（配置列保持 null，轮次不绑定配置版本）。
     */
    public Map<String, Object> create(String code, JsonNode body, Actor actor) {
        JsonNode snapshot = clients.get("app", "/internal/apps/" + code + "/published", actor);
        if (!snapshot.path("type").asText().equals("CHAT"))
            throw new PlatformException(409, "CHAT_REQUIRED", "只有已发布对话应用可创建会话");
        maxTurns(snapshot);
        RunService.ConfigReference config =
                runs == null ? null : runs.resolveConfig(code, body, actor);
        String id = "s_" + UUID.randomUUID().toString().replace("-", "");
        if (config == null) {
            store.db()
                    .update(
                            "insert into"
                                + " engine.chat_session(public_id,tenant_id,principal_id,app_code,snapshot)"
                                + " values(?,?,?,?,?::jsonb)",
                            id,
                            actor.tenantId(),
                            actor.principalId(),
                            code,
                            Json.write(snapshot));
        } else {
            store.db()
                    .update(
                            "insert into"
                                + " engine.chat_session(public_id,tenant_id,principal_id,app_code,snapshot,"
                                + "config_release_id,config_digest,environment_binding_revs,config_environment)"
                                + " values(?,?,?,?,?::jsonb,?,?,?::jsonb,?)",
                            id,
                            actor.tenantId(),
                            actor.principalId(),
                            code,
                            Json.write(snapshot),
                            config.releaseId(),
                            config.digest(),
                            Json.write(config.environmentBindingRevs()),
                            config.environment());
        }
        return get(code, id, actor);
    }

    public Map<String, Object> get(String code, String id, Actor actor) {
        var session = owned(code, id, actor, false);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("sessionId", id);
        view.put("appCode", code);
        view.put("versionNo", TraceStore.json(session.get("snapshot")).path("versionNo"));
        view.put("nextTurn", session.get("next_turn"));
        view.put("maxTurns", maxTurns(TraceStore.json(session.get("snapshot"))));
        view.put(
                "turns",
                store.db()
                        .queryForList(
                                "select turn_no,request_id,run_public_id,created_at from"
                                        + " engine.chat_turn where session_id=? order by turn_no",
                                id));
        if (session.get("config_release_id") != null)
            view.put("configReleaseId", session.get("config_release_id"));
        return view;
    }

    public Map<String, Object> turn(String code, String id, JsonNode req, Actor actor) {
        String requestId = req.path("requestId").asText();
        if (requestId.isBlank() || requestId.length() > 48 || !req.path("inputs").isObject())
            throw new PlatformException(400, "TURN_REQUEST_INVALID", "会话轮次必须提供requestId与inputs");
        var pinnedSession = owned(code, id, actor, false);
        if (TraceStore.json(pinnedSession.get("snapshot")).has("evaluationConversation"))
            throw new PlatformException(403,"EVALUATION_SESSION_ROUTE_REQUIRED","候选评测会话只能经受控EVALUATION入口续接");
        // INT-06：轮次只能沿用会话创建时固定的配置版本；客户端在轮次里换版本一律拒绝。
        RunService.ConfigReference pinnedConfig = pinnedConfig(pinnedSession, req);
        boolean known =
                !store.db()
                        .queryForList(
                                "select turn_no from engine.chat_turn where session_id=? and"
                                        + " request_id=?",
                                id,
                                requestId)
                        .isEmpty();
        // Keep the published version pinned, but never let an old session bypass current app
        // disablement.
        // Existing reservations are accepted work and must remain idempotently recoverable.
        if (!known) {
            requireTurnLimit(pinnedSession);
            ChatHistory.checkScope(store, id, code, actor);
            clients.get("app", "/internal/apps/" + code + "/published", actor);
        }
        RunScheduler.Admission ticket = null;
        if (!known) {
            InputDeclarations.validate(
                    TraceStore.json(pinnedSession.get("snapshot")), req.path("inputs"));
            try {
                ticket = runs.reserveSessionTurn();
            } catch (RunAdmissionRejected rejected) {
                // Another request may have accepted this exact turn since the first lookup.
                // A durable reservation is accepted work; its normal locked branch below
                // verifies input equality and can reconstruct a missing run without re-admission.
                if (store.db()
                        .queryForList(
                                "select turn_no from engine.chat_turn where session_id=? and"
                                        + " request_id=?",
                                id,
                                requestId)
                        .isEmpty()) throw rejected;
            }
        }
        try {
            java.util.function.Supplier<Map<String, Object>> reserve =
                    () ->
                            store.atomic(
                                    () -> {
                                        var session = owned(code, id, actor, true);
                                        var existing =
                                                store.db()
                                                        .queryForList(
                                                                "select * from engine.chat_turn"
                                                                        + " where session_id=? and"
                                                                        + " request_id=?",
                                                                id,
                                                                requestId);
                                        if (!existing.isEmpty()) {
                                            if (!TraceStore.json(existing.getFirst().get("inputs"))
                                                    .equals(req.path("inputs")))
                                                throw new PlatformException(
                                                        409, "REQUEST_CONFLICT", "轮次请求号已绑定其他输入");
                                            return existing.getFirst();
                                        }
                                        // 在既有会话行锁内复核固定版本的上限；已受理请求走上方重放分支，不再占用轮次。
                                        requireTurnLimit(session);
                                        var pending =
                                                store.db()
                                                        .queryForList(
                                                                "select t.run_public_id,r.status"
                                                                    + " from engine.chat_turn t"
                                                                    + " left join engine.run r on"
                                                                    + " r.public_id=t.run_public_id"
                                                                    + " where t.session_id=? order"
                                                                    + " by t.turn_no desc limit 1",
                                                                id);
                                        if (!pending.isEmpty()
                                                && (pending.getFirst().get("status") == null
                                                        || !Set.of(
                                                                        "SUCCEEDED",
                                                                        "FAILED",
                                                                        "CANCELLED")
                                                                .contains(
                                                                        pending.getFirst()
                                                                                .get("status"))))
                                            throw new PlatformException(
                                                    409, "CHAT_TURN_ACTIVE", "上一轮仍在执行或恢复");
                                        // Validate locally under the session lock.
                                        // Accepted requestIds retain their original inputs.
                                        // Schema loading cannot perform HTTP here.
                                        InputDeclarations.validate(
                                                TraceStore.json(session.get("snapshot")),
                                                req.path("inputs"));
                                        int turn = ((Number) session.get("next_turn")).intValue();
                                        store.db()
                                                .update(
                                                        "insert into"
                                                            + " engine.chat_turn(session_id,turn_no,request_id,inputs)"
                                                            + " values(?,?,?,?::jsonb)",
                                                        id,
                                                        turn,
                                                        requestId,
                                                        Json.write(req.path("inputs")));
                                        store.db()
                                                .update(
                                                        "update engine.chat_session set"
                                                                + " next_turn=next_turn+1 where"
                                                                + " public_id=?",
                                                        id);
                                        // H1-2（主方案 §5.1）：chat_turn 创建与 turn/start+user/message
                                        // 同事务——重放/恢复以事务为界，日志重建必回到原轮次。
                                        // turnEvents 复用本 reserve 的 TraceStore 事务（SessionStore(TraceStore)），
                                        // 此处已在 store.atomic 内，append 直接加入当前事务。
                                        turnEvents.append(
                                                id, null,
                                                cn.agentplatform.engine.harness.session.SessionEvents.TURN_START,
                                                Json.tree(Map.of("turn", turn)), null);
                                        turnEvents.append(
                                                id, null,
                                                cn.agentplatform.engine.harness.session.SessionEvents.USER_MESSAGE,
                                                Json.tree(Map.of("content", req.path("inputs"))), null);
                                        return store.db()
                                                .queryForMap(
                                                        "select * from engine.chat_turn where"
                                                                + " session_id=? and turn_no=?",
                                                        id,
                                                        turn);
                                    });
            // The first durable reservation is the admission point, before consuming next_turn.
            // Subsequent run preparation may perform HTTP outside this short transaction. Once
            // accepted, drain cannot reject the same ticket's completion and orphan its turn.
            var reservation = ticket == null ? reserve.get() : ticket.persist(reserve);
            if (reservation.get("run_public_id") != null)
                return runs.status(reservation.get("run_public_id").toString(), actor);
            var session = owned(code, id, actor, false);
            var source =
                    (com.fasterxml.jackson.databind.node.ObjectNode)
                            TraceStore.json(session.get("snapshot")).deepCopy();
            source.set(
                    "chatSession",
                    Json.tree(Map.of("sessionId", id, "turnNo", reservation.get("turn_no"))));
            var input = (com.fasterxml.jackson.databind.node.ObjectNode) req.deepCopy();
            input.remove("sessionId");
            input.remove("configReleaseId");
            input.put("requestId", "session:" + id + ":" + requestId);
            var result =
                    pinnedConfig == null
                            ? runs.createReservedSessionTurn(code, input, source, actor, ticket)
                            : runs.createReservedSessionTurn(
                                    code, input, source, actor, ticket, pinnedConfig);
            store.db()
                    .update(
                            "update engine.chat_turn set run_public_id=? where session_id=? and"
                                    + " turn_no=? and run_public_id is null",
                            result.get("runId"),
                            id,
                            reservation.get("turn_no"));
            return result;
        } finally {
            if (ticket != null) ticket.close();
        }
    }

    /** 仅已验内部评测主体；固定候选会话不读取published，也不走TASK/FULL受理。 */
    public Map<String,Object> evaluationTurn(JsonNode req,Actor actor) {
        if (!actor.roles().contains("SERVICE")) throw new PlatformException(403,"EVALUATION_SERVICE_REQUIRED","仅内部评测调用可受理候选轮次");
        if (runs.iamEnabled()) throw new PlatformException(403,"IAM_ADMISSION_ROUTE_DENIED","IAM候选会话受理尚未迁移");
        String code=req.path("appCode").asText(), report=req.path("evalReportId").asText(), caseId=req.path("caseId").asText();
        if (!code.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || report.isBlank() || report.length()>128 || caseId.isBlank() || caseId.length()>128
                || !req.path("turnNo").isIntegralNumber() || !req.path("turnNo").canConvertToInt() || req.path("turnNo").intValue()<1
                || !req.path("inputs").isObject() || !req.path("frozenCandidate").isObject())
            throw new PlatformException(400,"EVALUATION_TURN_INVALID","评测轮次参数无效");
        int turn=req.path("turnNo").intValue();
        var candidate=req.path("frozenCandidate");
        if (!"CHAT".equals(candidate.path("type").asText()) || candidate.has("chatSession") || candidate.has("evaluationConversation"))
            throw new PlatformException(400,"EVALUATION_CHAT_REQUIRED","候选会话仅支持显式CHAT，历史指针由服务器创建");
        Map<String,Object> content=new LinkedHashMap<>();
        for(String key:List.of("appCode","type","name","description","domain","bizCategory","tags","config","definition")) content.put(key,candidate.path(key));
        String sha=Digest.sha256(Json.canonical(content));
        if (!code.equals(candidate.path("appCode").asText()) || !sha.equals(req.path("candidateSha256").asText())
                || !sha.equals(candidate.path("candidateSha256").asText()) || !req.path("draftRev").isIntegralNumber()
                || req.path("draftRev").asLong()<1 || req.path("draftRev").asLong()!=candidate.path("draftRev").asLong()
                || req.path("datasetVersion").asText().isBlank())
            throw new PlatformException(409,"CANDIDATE_MISMATCH","评测候选身份或正文不匹配");
        if(turn>maxTurns(candidate)) throw new PlatformException(429,"CHAT_TURN_LIMIT","评测轮数超过固定候选上限");
        String id="es_"+Digest.sha256(Json.canonical(Map.of("tenant",actor.tenantId(),"report",report,"case",caseId))).substring(0,48);
        if(req.hasNonNull("conversationId") && !id.equals(req.path("conversationId").asText()))
            throw new PlatformException(409,"EVALUATION_CONVERSATION_CONFLICT","轮次不能切换会话");
        var binding=new LinkedHashMap<String,Object>();
        binding.put("schemaVersion",1); binding.put("evalReportId",report); binding.put("caseId",caseId);
        binding.put("appCode",code); binding.put("draftRev",req.path("draftRev").asLong()); binding.put("candidateSha256",sha);
        binding.put("datasetVersion",req.path("datasetVersion").asText());
        var fixed=(com.fasterxml.jackson.databind.node.ObjectNode)candidate.deepCopy();
        fixed.set("evaluationConversation",Json.tree(binding));
        var reservation=store.atomic(()->{
            store.db().update("insert into engine.chat_session(public_id,tenant_id,principal_id,app_code,snapshot) values(?,?,?,?,?::jsonb) on conflict do nothing",id,actor.tenantId(),actor.principalId(),code,Json.write(fixed));
            var session=owned(code,id,actor,true);
            // JSONB读回可能把同值LongNode归为IntNode；比较完整canonical材料，仍核全部候选与来源字段。
            if(!Json.canonical(TraceStore.json(session.get("snapshot"))).equals(Json.canonical(fixed))) throw new PlatformException(409,"EVALUATION_CONVERSATION_CONFLICT","原会话已固定其他候选或评测来源");
            var prior=store.db().queryForList("select * from engine.chat_turn where session_id=? and turn_no=?",id,turn);
            if(!prior.isEmpty()){
                if(!TraceStore.json(prior.getFirst().get("inputs")).equals(req.path("inputs")))
                    throw new PlatformException(409,"REQUEST_CONFLICT","原轮次已绑定其他输入");
                return prior.getFirst();
            }
            requireTurnLimit(session);
            if(((Number)session.get("next_turn")).intValue()!=turn) throw new PlatformException(409,"EVALUATION_TURN_ORDER","必须按明确顺序续接轮次");
            if(turn>1){
                var previous=store.db().queryForList("select r.status from engine.chat_turn t left join engine.run r on r.public_id=t.run_public_id where t.session_id=? and t.turn_no=?",id,turn-1);
                if(previous.isEmpty() || !"SUCCEEDED".equals(previous.getFirst().get("status")))
                    throw new PlatformException(409,"CHAT_TURN_ACTIVE","前轮尚未成功完成，不能发起下一轮");
                ChatHistory.checkScope(store,id,code,actor);
            }
            InputDeclarations.validate(candidate,req.path("inputs"));
            store.db().update("insert into engine.chat_turn(session_id,turn_no,request_id,inputs) values(?,?,?,?::jsonb)",id,turn,"turn:"+turn,Json.write(req.path("inputs")));
            store.db().update("update engine.chat_session set next_turn=next_turn+1 where public_id=?",id);
            return store.db().queryForMap("select * from engine.chat_turn where session_id=? and turn_no=?",id,turn);
        });
        Map<String,Object> result;
        if(reservation.get("run_public_id")!=null) result=runs.status(reservation.get("run_public_id").toString(),actor);
        else{
            var source=(com.fasterxml.jackson.databind.node.ObjectNode)fixed.deepCopy();
            source.set("chatSession",Json.tree(Map.of("sessionId",id,"turnNo",turn)));
            result=runs.createEvaluationSessionTurn(code,req,source,actor);
            store.db().update("update engine.chat_turn set run_public_id=? where session_id=? and turn_no=? and run_public_id is null",result.get("runId"),id,turn);
        }
        var response=new LinkedHashMap<String,Object>(result);
        response.putAll(binding); response.put("conversationId",id); response.put("turnNo",turn);
        return response;
    }

    /** 会话行里固定的配置版本；会话未固定时拒绝轮次临时指定，避免同一会话先后挂到两套配置。 */
    RunService.ConfigReference pinnedConfig(Map<String, Object> session, JsonNode req) {
        Object releaseId = session.get("config_release_id");
        String requested =
                req == null ? "" : req.path("configReleaseId").asText("").trim();
        if (releaseId == null) {
            if (!requested.isEmpty())
                throw new PlatformException(
                        409, "CONFIG_REV_CONFLICT", "会话创建时未固定配置版本，轮次不能指定");
            return null;
        }
        if (!requested.isEmpty() && !requested.equals(releaseId.toString()))
            throw new PlatformException(409, "CONFIG_REV_CONFLICT", "轮次配置版本与会话固定版本不一致");
        Object digest = session.get("config_digest");
        Object environment = session.get("config_environment");
        if (digest == null || environment == null)
            throw new PlatformException(409, "CONFIG_REV_CONFLICT", "会话固定配置版本缺少完整性字段");
        return new RunService.ConfigReference(
                releaseId.toString(),
                digest.toString(),
                environment.toString(),
                TraceStore.json(session.get("environment_binding_revs")));
    }

    private Map<String, Object> owned(String code, String id, Actor actor, boolean lock) {
        var rows =
                store.db()
                        .queryForList(
                                "select * from engine.chat_session where public_id=? and app_code=?"
                                        + " and tenant_id=? and principal_id=?"
                                        + (lock ? " for update" : ""),
                                id,
                                code,
                                actor.tenantId(),
                                actor.principalId());
        if (rows.isEmpty()) throw new PlatformException(404, "SESSION_NOT_FOUND", "会话不存在");
        return rows.getFirst();
    }

    /** 上限随会话创建时的发布快照固定；后续应用改稿不会改变旧会话，缺省40轮。 */
    private static int maxTurns(JsonNode snapshot) {
        var value = snapshot.path("config").path("context").path("maxTurns");
        if (value.isMissingNode()) return 40;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1 || value.intValue() > 200)
            throw new PlatformException(400, "CHAT_CONTEXT_INVALID", "会话最大轮数须为1至200的整数");
        return value.intValue();
    }

    private static void requireTurnLimit(Map<String, Object> session) {
        int limit = maxTurns(TraceStore.json(session.get("snapshot")));
        if (((Number) session.get("next_turn")).longValue() > limit)
            throw new PlatformException(429, "CHAT_TURN_LIMIT", "会话已达到固定版本的 " + limit + " 轮上限，请新建会话后继续");
    }
}
