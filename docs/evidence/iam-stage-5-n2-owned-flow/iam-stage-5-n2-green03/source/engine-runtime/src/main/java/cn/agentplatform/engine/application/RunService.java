package cn.agentplatform.engine.application;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.domain.*;
import cn.agentplatform.engine.infra.*;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@EnableScheduling
public class RunService {
    private final TraceStore store;
    private final RuntimeClients clients;
    private final FlowDriver flow;
    private final AgentDriver agent;
    private final RunScheduler scheduler;
    private final RunAuthority runAuthority;
    private final String owner = UUID.randomUUID().toString();
    private final Set<Long> activeNodes = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public RunService(TraceStore s, RuntimeClients c, FlowDriver f, AgentDriver a) {
        this(s, c, f, a, new RunScheduler(s));
    }

    @Autowired
    public RunService(
            TraceStore s, RuntimeClients c, FlowDriver f, AgentDriver a, RunScheduler scheduler) {
        this(s,c,f,a,scheduler,null);
    }

    /** Explicit isolated protected assembly. Formal trusted Spring IAM admission is N3. */
    public RunService(TraceStore s, RuntimeClients c, FlowDriver f, AgentDriver a, RunScheduler scheduler, RunAuthority authority) {
        store = s;
        clients = c;
        flow = f;
        agent = a;
        this.scheduler = scheduler;
        this.runAuthority = authority;
        scheduler.bind(this::driveRecovered, this::renewLeases, this::resumeExternalFacts);
    }

    @PostConstruct
    public void startScheduler() {
        scheduler.start();
    }

    public RunScheduler.Admission reserveSessionTurn() {
        return scheduler.reserveInteractive();
    }

    public Map<String, Object> createReservedSessionTurn(
            String code,
            JsonNode request,
            JsonNode snapshot,
            Actor actor,
            RunScheduler.Admission ticket) {
        return createInternal(code, request, "TASK", actor, snapshot, null, false, ticket, true);
    }

    public Map<String, Object> create(String code, JsonNode req, String trigger, Actor actor) {
        return createInternal(code, req, trigger, actor, null, null);
    }

    public Map<String, Object> reproduceFrozen(String id, JsonNode req, Actor actor) {
        var original = store.run(id, actor);
        ControlledReplay.preflight(store, original, actor);
        if (original.get("controlled_draft_skill_version_id") != null)
            throw new PlatformException(
                    409, "CONTROLLED_DRAFT_RECREATE_REQUIRED", "草稿验收不能从旧合同直接复现");
        var source =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        TraceStore.json(original.get("snapshot")).deepCopy();
        source.set(
                "controlledReplay",
                Json.tree(
                        Map.of(
                                "sourceRunId",
                                id,
                                "sourceDigest",
                                req.path("sourceDigest").asText(),
                                "mode",
                                "CONTROLLED",
                                "externalCalls",
                                0)));
        var input = Json.object();
        input.put("requestId", "controlled:" + id + ":" + req.path("requestId").asText());
        input.set("inputs", TraceStore.json(original.get("inputs")));
        input.put("mode", "FULL");
        return createInternal(
                original.get("app_code").toString(), input, "DEBUG", actor, source, null, true);
    }

    public Map<String, Object> reexecuteFrozen(String id, JsonNode req, Actor actor) {
        var original = store.run(id, actor);
        if (original.get("controlled_draft_skill_version_id") != null)
            throw new PlatformException(
                    409, "CONTROLLED_DRAFT_RECREATE_REQUIRED", "草稿验收必须从当前精确合同重新创建");
        JsonNode published =
                clients.get(
                        "app", "/internal/apps/" + original.get("app_code") + "/published", actor);
        var source =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        TraceStore.json(original.get("snapshot")).deepCopy();
        source.remove("controlledReplay");
        source.set(
                "reexecution",
                Json.tree(
                        Map.of(
                                "sourceRunId",
                                id,
                                "sourceDigest",
                                req.path("sourceDigest").asText(),
                                "mode",
                                "REEXECUTE",
                                "currentServices",
                                true)));
        var input = Json.object();
        input.put("requestId", "reexecute:" + id + ":" + req.path("requestId").asText());
        input.set("inputs", TraceStore.json(original.get("inputs")));
        input.put("mode", "FULL");
        return createInternal(
                original.get("app_code").toString(), input, "DEBUG", actor, source, null);
    }

    public Map<String, Object> createSessionTurn(
            String code, JsonNode request, JsonNode trustedSnapshot, Actor actor) {
        return createInternal(code, request, "TASK", actor, trustedSnapshot, null);
    }

    private Map<String, Object> createInternal(
            String code,
            JsonNode req,
            String trigger,
            Actor actor,
            JsonNode trustedSnapshot,
            Long controlledDraftId) {
        return createInternal(code, req, trigger, actor, trustedSnapshot, controlledDraftId, false);
    }

    private Map<String, Object> createInternal(
            String code,
            JsonNode req,
            String trigger,
            Actor actor,
            JsonNode trustedSnapshot,
            Long controlledDraftId,
            boolean recordedReplay) {
        return createInternal(
                code,
                req,
                trigger,
                actor,
                trustedSnapshot,
                controlledDraftId,
                recordedReplay,
                null,
                false);
    }

    private Map<String, Object> createInternal(
            String code,
            JsonNode req,
            String trigger,
            Actor actor,
            JsonNode trustedSnapshot,
            Long controlledDraftId,
            boolean recordedReplay,
            RunScheduler.Admission suppliedTicket,
            boolean acceptedReservation) {
        if (code == null || code.isBlank() || code.length() > 64 || !req.path("inputs").isObject())
            throw new PlatformException(400, "RUN_INPUT_INVALID", "请提供应用编码和对象类型inputs");
        if (req.has("versionNo") && req.path("versionNo").asInt() < 1)
            throw new PlatformException(400, "VERSION_INVALID", "版本号必须大于零");
        if (trigger.equals("DEBUG")) actor.requireRole("EDITOR");
        if (trigger.equals("EVALUATION") && req.has("evalReportId") && req.has("caseId"))
            ((com.fasterxml.jackson.databind.node.ObjectNode) req)
                    .put(
                            "requestId",
                            "evaluation:"
                                    + req.path("evalReportId").asText()
                                    + ":"
                                    + req.path("caseId").asText());
        if (req.hasNonNull("requestId")) {
            var prior =
                    store.db()
                            .queryForList(
                                    "select * from engine.run where tenant_id=? and principal_id=?"
                                            + " and request_id=?",
                                    actor.tenantId(),
                                    actor.principalId(),
                                    req.path("requestId").asText());
            if (!prior.isEmpty()) {
                var existing = prior.getFirst();
                verifyReplay(existing, code, req, trigger);
                return status(existing.get("public_id").toString(), actor);
            }
        }
        RunScheduler.Admission admission = suppliedTicket;
        if (admission == null && !acceptedReservation && !trigger.equals("EVALUATION")) {
            try {
                admission = scheduler.reserveInteractive();
            } catch (RunAdmissionRejected rejected) {
                // Same request lock as INSERT: accepted concurrent work remains readable at
                // capacity. No HTTP or semaphore wait is performed in this short transaction.
                Map<String, Object> accepted =
                        req.hasNonNull("requestId")
                                ? store.atomic(
                                        () -> {
                                            store.db()
                                                    .queryForList(
                                                            "select"
                                                                + " pg_advisory_xact_lock(hashtextextended(?,0))",
                                                            actor.tenantId()
                                                                    + ":"
                                                                    + actor.principalId()
                                                                    + ":"
                                                                    + req.path("requestId")
                                                                            .asText());
                                            var rows =
                                                    store.db()
                                                            .queryForList(
                                                                    "select * from engine.run where"
                                                                            + " tenant_id=? and"
                                                                            + " principal_id=? and"
                                                                            + " request_id=?",
                                                                    actor.tenantId(),
                                                                    actor.principalId(),
                                                                    req.path("requestId").asText());
                                            if (rows.isEmpty()) return null;
                                            verifyReplay(rows.getFirst(), code, req, trigger);
                                            return rows.getFirst();
                                        })
                                : null;
                if (accepted != null) return status(accepted.get("public_id").toString(), actor);
                throw rejected;
            }
        }
        try {
            String path =
                    trigger.equals("DEBUG")
                            ? "/internal/apps/" + code + "/snapshot?draft=true"
                            : trigger.equals("EVALUATION") && req.has("candidateSha256")
                                    ? "/internal/apps/"
                                            + code
                                            + "/candidate?draftRev="
                                            + req.path("draftRev").asLong()
                                            + "&candidateSha256="
                                            + req.path("candidateSha256").asText()
                                    : req.has("versionNo")
                                            ? "/internal/apps/"
                                                    + code
                                                    + "/versions/"
                                                    + req.path("versionNo").asInt()
                                            : "/internal/apps/" + code + "/published";
            JsonNode source =
                    trustedSnapshot != null
                            ? trustedSnapshot
                            : req.has("frozenCandidate") && trigger.equals("EVALUATION")
                                    ? verifyCandidate(req.path("frozenCandidate"), req, code)
                                    : clients.get("app", path, actor);
            final String actualTrigger =
                    trigger.equals("TASK") && source.path("type").asText().equals("CHAT")
                            ? "CHAT"
                            : trigger;
            JsonNode snapshot =
                    recordedReplay
                            ? source.deepCopy()
                            : prepareSnapshot(source, actor, controlledDraftId);
            if (recordedReplay) {
                ControlledReplay.guard(
                        snapshot.path("config").path("guard").isObject()
                                ? snapshot.path("config").path("guard")
                                : Json.object(),
                        actor);
                long modelId = snapshot.path("config").path("modelEntryId").asLong();
                if (modelId > 0) FrozenModels.reference(snapshot, modelId);
            }
            // All new run entry points share the frozen declaration gate. An invalid request
            // must fail before model binding, run/attempt creation or any paid dispatch.
            // Previously accepted requestIds above keep their original idempotent read semantics.
            InputDeclarations.validate(snapshot, req.path("inputs"));
            if (!recordedReplay) freezeModelRefs(snapshot, trigger, actor);
            if (trigger.equals("DEBUG"))
                ((com.fasterxml.jackson.databind.node.ObjectNode) snapshot)
                        .set(
                                "debugControls",
                                Json.tree(
                                        Map.of(
                                                "breakpoints",
                                                req.path("breakpoints"),
                                                "mocks",
                                                req.path("mocks"))));
            final JsonNode frozen = snapshot, graph = snapshot.path("definition");
            if (trigger.equals("EVALUATION")
                    && req.has("candidateSha256")
                    && !req.path("candidateSha256")
                            .asText()
                            .equals(snapshot.path("candidateSha256").asText()))
                throw new PlatformException(409, "CANDIDATE_MISMATCH", "候选摘要不一致");
            if (graph.path("nodes").isArray()) {
                Map<String, List<String>> out = new HashMap<>();
                List<String> ids = new ArrayList<>();
                for (JsonNode n : graph.path("nodes")) {
                    String id = n.path("id").asText();
                    if (ids.contains(id))
                        throw new PlatformException(400, "DUPLICATE_NODE", "节点编码重复");
                    ids.add(id);
                    out.put(id, new ArrayList<>());
                }
                for (JsonNode e : graph.path("edges")) {
                    if (!out.containsKey(e.path("from").asText()))
                        throw new PlatformException(400, "UNKNOWN_EDGE", "连线起点不存在");
                    out.get(e.path("from").asText()).add(e.path("to").asText());
                }
                GraphRules.validateDag(ids, out);
            }
            String id = "r_" + UUID.randomUUID().toString().replace("-", "");
            java.util.function.Supplier<Long> persist =
                    () ->
                            store.atomic(
                                    () -> {
                                        if (req.hasNonNull("requestId"))
                                            store.db()
                                                    .queryForList(
                                                            "select"
                                                                + " pg_advisory_xact_lock(hashtextextended(?,0))",
                                                            actor.tenantId()
                                                                    + ":"
                                                                    + actor.principalId()
                                                                    + ":"
                                                                    + req.path("requestId")
                                                                            .asText());
                                        if (req.hasNonNull("requestId")) {
                                            var existing =
                                                    store.db()
                                                            .queryForList(
                                                                    "select * from engine.run where"
                                                                            + " tenant_id=? and"
                                                                            + " principal_id=? and"
                                                                            + " request_id=?",
                                                                    actor.tenantId(),
                                                                    actor.principalId(),
                                                                    req.path("requestId").asText());
                                            if (!existing.isEmpty()) {
                                                verifyReplay(
                                                        existing.getFirst(), code, req, trigger);
                                                return TraceStore.number(existing.getFirst(), "id");
                                            }
                                        }
                                        Map<String, Object> guard =
                                                Map.of(
                                                        "tenantId",
                                                        actor.tenantId(),
                                                        "principalId",
                                                        actor.principalId(),
                                                        "roles",
                                                        actor.roles(),
                                                        "attributes",
                                                        actor.attributes(),
                                                        "snapshotAt",
                                                        java.time.Instant.now().toString());
                                        Object version =
                                                trigger.equals("DEBUG")
                                                                || req.has("candidateSha256")
                                                        ? null
                                                        : frozen.path("appVersionId").isNumber()
                                                                ? frozen.path("appVersionId")
                                                                        .asLong()
                                                                : frozen.path("versionId")
                                                                                .isNumber()
                                                                        ? frozen.path("versionId")
                                                                                .asLong()
                                                                        : null;
                                        if (!trigger.equals("DEBUG")
                                                && !req.has("candidateSha256")
                                                && version == null)
                                            throw new PlatformException(
                                                    409, "PUBLISHED_VERSION_REQUIRED", "请先发布应用");
                                        long rid =
                                                store.db()
                                                        .queryForObject(
                                                                "insert into"
                                                                    + " engine.run(public_id,app_code,app_version_id,debug_snapshot,eval_candidate,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs,mode,request_id)"
                                                                    + " values(?,?,?,?::jsonb,?::jsonb,?,'PENDING',?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?)"
                                                                    + " returning id",
                                                                Long.class,
                                                                id,
                                                                code,
                                                                version,
                                                                trigger.equals("DEBUG")
                                                                        ? Json.write(frozen)
                                                                        : null,
                                                                trigger.equals("EVALUATION")
                                                                                && req.has(
                                                                                        "candidateSha256")
                                                                        ? Json.write(req)
                                                                        : null,
                                                                actualTrigger,
                                                                actor.principalId(),
                                                                actor.tenantId(),
                                                                "pg:run/" + id + "/input",
                                                                Json.write(
                                                                        versionRefs(
                                                                                source, frozen,
                                                                                req)),
                                                                Json.write(guard),
                                                                Json.write(
                                                                        initialBudget(
                                                                                frozen.path(
                                                                                        "config"))),
                                                                Json.write(frozen),
                                                                Json.write(req.path("inputs")),
                                                                req.path("mode").asText("FULL"),
                                                                req.hasNonNull("requestId")
                                                                        ? req.path("requestId")
                                                                                .asText()
                                                                        : null);
                                        int seq = 0;
                                        if (controlledDraftId != null) {
                                            String skillState =
                                                    source.path("controlledSkillVersion")
                                                            .path("status")
                                                            .asText();
                                            store.db()
                                                    .update(
                                                            "update engine.run set"
                                                                + " controlled_skill_test_version_id=?,controlled_skill_test_digest=?,controlled_skill_test_status=?"
                                                                + " where id=?",
                                                            controlledDraftId,
                                                            source.path("controlledSkillDigest")
                                                                    .asText(),
                                                            skillState,
                                                            rid);
                                            if (skillState.equals("DRAFT"))
                                                store.db()
                                                        .update(
                                                                "update engine.run set"
                                                                    + " controlled_draft_skill_version_id=?,controlled_draft_skill_digest=?"
                                                                    + " where id=?",
                                                                controlledDraftId,
                                                                source.path("controlledSkillDigest")
                                                                        .asText(),
                                                                rid);
                                        }
                                        if (graph.path("nodes").isArray()
                                                && !graph.path("nodes").isEmpty()) {
                                            for (JsonNode n : graph.path("nodes"))
                                                store.db()
                                                        .update(
                                                                "insert into"
                                                                    + " engine.node_instance(run_id,node_def_id,step_seq,node_type,status,definition)"
                                                                    + " values(?,?,?,?,'READY',?::jsonb)",
                                                                rid,
                                                                n.path("id").asText(),
                                                                ++seq,
                                                                n.path("type").asText(),
                                                                Json.write(n));
                                        } else
                                            store.db()
                                                    .update(
                                                            "insert into"
                                                                + " engine.node_instance(run_id,step_seq,node_type,status,definition)"
                                                                + " values(?,1,'AGENT','READY','{\"type\":\"AGENT\"}'::jsonb)",
                                                            rid);
                                        return rid;
                                    });
            long run =
                    admission != null
                            ? acceptedReservation
                                    ? admission.completeReservation(persist)
                                    : admission.persist(persist)
                            : acceptedReservation ? persist.get() : scheduler.persistNew(persist);
            String publicId =
                    store.db()
                            .queryForObject(
                                    "select public_id from engine.run where id=?",
                                    String.class,
                                    run);
            scheduler.created(run, admission);
            return status(publicId, actor);
        } finally {
            if (admission != null) admission.close();
        }
    }

    private JsonNode verifyCandidate(JsonNode snapshot, JsonNode req, String code) {
        Map<String, Object> content = new LinkedHashMap<>();
        for (String key :
                List.of(
                        "appCode",
                        "type",
                        "name",
                        "description",
                        "domain",
                        "bizCategory",
                        "tags",
                        "config",
                        "definition")) content.put(key, snapshot.path(key));
        if (!snapshot.path("appCode").asText().equals(code)
                || snapshot.path("draftRev").asLong() != req.path("draftRev").asLong()
                || !Digest.sha256(Json.canonical(content))
                        .equals(req.path("candidateSha256").asText()))
            throw new PlatformException(409, "CANDIDATE_MISMATCH", "冻结候选内容或摘要不匹配");
        return snapshot;
    }

    /**
     * Server-built acceptance snapshot, never an application/body flag allowing arbitrary drafts.
     */
    public Map<String, Object> createDraftTest(JsonNode request, Actor actor) {
        actor.requireRole("EDITOR");
        actor.requireRole("SERVICE");
        long id = request.path("skillVersionId").asLong();
        if (id < 1 || !request.path("inputs").isObject())
            throw new PlatformException(400, "DRAFT_TEST_INVALID", "请提供精确技能版本与对象inputs");
        JsonNode version = clients.get("skill", "/internal/skill-versions/" + id, actor);
        if (version.path("skillStatus").asText().equals("DISABLED"))
            throw new PlatformException(409, "SKILL_UNAVAILABLE", "技能已停用，不能创建受控连接测试");
        if (!Set.of("DRAFT", "PUBLISHED", "ARCHIVED").contains(version.path("status").asText())
                || version.path("skillVersionId").asLong() != id)
            throw new PlatformException(
                    409, "EXACT_SKILL_VERSION_REQUIRED", "受控连接测试必须绑定精确草稿、已发布或归档版本");
        JsonNode contract = version.path("definition");
        String digest = version.path("digest").asText(version.path("sha256").asText());
        if (!contract.isObject() || !Digest.sha256(Json.canonical(contract)).equals(digest))
            throw new PlatformException(409, "DRAFT_CONTRACT_MISMATCH", "技能草稿合同摘要不一致");
        String code = version.path("code").asText();
        int number = version.path("versionNo").asInt();
        if (code.isBlank() || number < 1)
            throw new PlatformException(409, "DRAFT_METADATA_INVALID", "技能草稿缺少精确版本元数据");
        var graph = Json.object();
        graph.put("definitionVersion", "1.0");
        graph.put("trigger", "TASK");
        graph.put("entry", "start");
        graph.set("inputs", Json.tree(List.of()));
        graph.set(
                "outputs",
                Json.tree(List.of(Map.of("name", "result", "from", "${draft_skill.output}"))));
        graph.set(
                "nodes",
                Json.tree(
                        List.of(
                                Map.of("id", "start", "type", "START"),
                                Map.of(
                                        "id",
                                        "draft_skill",
                                        "type",
                                        "SKILL",
                                        "skillRef",
                                        Map.of(
                                                "code",
                                                code,
                                                "version",
                                                number,
                                                "skillVersionId",
                                                id),
                                        "args",
                                        request.path("inputs")),
                                Map.of("id", "end", "type", "END"))));
        graph.set(
                "edges",
                Json.tree(
                        List.of(
                                Map.of("from", "start", "to", "draft_skill"),
                                Map.of("from", "draft_skill", "to", "end"))));
        graph.set("joins", Json.tree(List.of()));
        graph.set(
                "executionRules",
                Json.tree(Map.of("parallel", false, "maxParallel", 1, "cyclesForbidden", true)));
        var snapshot = Json.object();
        snapshot.put("appCode", "skill_test_" + id);
        snapshot.put("type", "FLOW");
        snapshot.put("draftRev", number);
        snapshot.set(
                "config",
                Json.tree(
                        Map.of(
                                "guard",
                                version.path("guard"),
                                "skillRefs",
                                List.of(Map.of("code", code, "version", number)))));
        snapshot.set("definition", graph);
        snapshot.set("controlledSkillVersion", version);
        snapshot.set("controlledSkillContract", contract);
        snapshot.put("controlledSkillDigest", digest);
        var input = (com.fasterxml.jackson.databind.node.ObjectNode) request.deepCopy();
        input.put("mode", "FULL");
        input.put("controlledContractDigest", digest);
        return createInternal("skill_test_" + id, input, "DEBUG", actor, snapshot, id);
    }

    private void verifyReplay(Map<String, Object> run, String code, JsonNode req, String trigger) {
        boolean sameTrigger =
                run.get("trigger_type").equals(trigger)
                        || trigger.equals("TASK") && run.get("trigger_type").equals("CHAT");
        if (!run.get("app_code").equals(code)
                || !sameTrigger
                || req.has("controlledContractDigest")
                        && !req.path("controlledContractDigest")
                                .asText()
                                .equals(
                                        Objects.toString(
                                                run.get("controlled_skill_test_digest"),
                                                Objects.toString(
                                                        run.get("controlled_draft_skill_digest"),
                                                        "")))
                || !TraceStore.json(run.get("inputs")).equals(req.path("inputs"))
                || req.has("versionNo")
                        && TraceStore.json(run.get("snapshot")).path("versionNo").asInt()
                                != req.path("versionNo").asInt()
                || req.has("candidateSha256")
                        && !TraceStore.json(run.get("eval_candidate"))
                                .path("candidateSha256")
                                .asText()
                                .equals(req.path("candidateSha256").asText()))
            throw new PlatformException(409, "REQUEST_CONFLICT", "请求号已绑定其他版本或输入");
    }

    private Object versionRefs(JsonNode source, JsonNode frozen, JsonNode req) {
        Map<String, Object> refs = new LinkedHashMap<>(),
                skills = new TreeMap<>(),
                prompts = new TreeMap<>();
        for (JsonNode skill : frozen.path("config").path("tools"))
            skills.put(skill.path("code").asText(), skill.path("skillVersionId"));
        for (JsonNode prompt : frozen.path("config").path("frozenPrompts"))
            prompts.put(prompt.path("code").asText(), prompt.path("version"));
        refs.put("appVer", frozen.path("versionNo"));
        refs.put("flowDefSha", Digest.sha256(Json.canonical(source.path("definition"))));
        refs.put("promptVers", prompts);
        refs.put("skillVers", skills);
        refs.put("modelCfg", frozen.path("config").path("modelBinding"));
        refs.put("interpreterVer", "1.0");
        refs.put("evalReportId", req.path("evalReportId"));
        refs.put("ontology", List.of());
        return refs;
    }

    private Object initialBudget(JsonNode config) {
        var limits = Json.object();
        limits.put("maxSteps", 25);
        limits.put("modelCalls", 40);
        limits.put("totalTokens", 200000);
        limits.put("totalDurationMs", 600000);
        limits.put("maxDepth", 2);
        for (String name :
                List.of("maxSteps", "modelCalls", "totalTokens", "totalDurationMs", "maxDepth"))
            if (config.path("budgets").has(name)) {
                long value = config.path("budgets").path(name).asLong();
                if (value < 1) throw new PlatformException(400, "BUDGET_INVALID", "预算必须大于零");
                limits.put(name, value);
            }
        return Map.of(
                "limits",
                limits,
                "consumed",
                Map.of("steps", 0, "modelCalls", 0, "tokens", 0, "depth", 0),
                "deadline",
                java.time.Instant.now()
                        .plusMillis(limits.path("totalDurationMs").asLong())
                        .toString());
    }

    private void freezeModelRefs(JsonNode snapshot, String trigger, Actor actor) {
        Set<Long> ids = new LinkedHashSet<>();
        JsonNode config = snapshot.path("config");
        long primary =
                config.path("modelEntryId")
                        .asLong(config.path("modelBinding").path("modelEntryId").asLong());
        if (primary > 0) ids.add(primary);
        for (JsonNode node : snapshot.path("definition").path("nodes")) {
            long id = node.path("modelOverride").path("modelEntryId").asLong();
            if (id > 0) ids.add(id);
        }
        if (ids.isEmpty()) return;
        var dependencies =
                snapshot.path("dependencyVersions").isObject()
                        ? (com.fasterxml.jackson.databind.node.ObjectNode)
                                snapshot.path("dependencyVersions").deepCopy()
                        : Json.object();
        var refs =
                dependencies.path("modelRefs").isArray()
                        ? (com.fasterxml.jackson.databind.node.ArrayNode)
                                dependencies.path("modelRefs").deepCopy()
                        : Json.mapper().createArrayNode();
        for (long id : ids) {
            boolean present = false;
            for (JsonNode item : refs) if (item.path("modelEntryId").asLong() == id) present = true;
            if (!present) {
                if (!trigger.equals("DEBUG"))
                    throw new PlatformException(
                            409, "MODEL_BINDING_REQUIRED", "已发布或评测快照缺少模型冻结引用，需重新评测发布");
                JsonNode frozen =
                        clients.post(
                                "model",
                                "/internal/model-bindings/freeze",
                                Map.of("modelEntryId", id),
                                actor);
                refs.add(
                        Json.tree(
                                Map.of(
                                        "modelEntryId",
                                        id,
                                        "modelBindingRef",
                                        Map.of(
                                                "bindingId",
                                                frozen.path("bindingId").asLong(),
                                                "sha256",
                                                frozen.path("sha256").asText()),
                                        "entry",
                                        frozen.path("entry"))));
            }
        }
        dependencies.set("modelRefs", refs);
        ((com.fasterxml.jackson.databind.node.ObjectNode) snapshot)
                .set("dependencyVersions", dependencies);
        for (long id : ids) FrozenModels.reference(snapshot, id);
    }

    private JsonNode prepareSnapshot(JsonNode original, Actor actor) {
        return prepareSnapshot(original, actor, null);
    }

    private JsonNode prepareSnapshot(JsonNode original, Actor actor, Long controlledDraftId) {
        var snapshot = (com.fasterxml.jackson.databind.node.ObjectNode) original.deepCopy();
        var config =
                snapshot.path("config").isObject()
                        ? (com.fasterxml.jackson.databind.node.ObjectNode)
                                snapshot.path("config").deepCopy()
                        : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        JsonNode guard = config.path("guard");
        for (JsonNode role : guard.path("roles")) actor.requireRole(role.asText());
        guard.path("attributes")
                .fields()
                .forEachRemaining(
                        e -> {
                            boolean match =
                                    e.getValue().isArray()
                                            && e.getValue().isEmpty()
                                            && !actor.attributes()
                                                    .getOrDefault(e.getKey(), Set.of())
                                                    .isEmpty();
                            for (JsonNode allowed : e.getValue())
                                if (actor.attributes()
                                        .getOrDefault(e.getKey(), Set.of())
                                        .contains(allowed.asText())) match = true;
                            if (!match)
                                throw new PlatformException(403, "APP_GUARD_DENIED", "应用属性授权不足");
                        });
        var tools = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        Map<String, JsonNode> pinned = new HashMap<>();
        List<JsonNode> refs = new ArrayList<>();
        config.path("skillRefs").forEach(refs::add);
        for (JsonNode n : snapshot.path("definition").path("nodes"))
            if (n.has("skillRef")) refs.add(n.path("skillRef"));
        for (JsonNode ref : refs) {
            String code = ref.path("code").asText();
            int version = ref.path("version").asInt();
            if (code.isBlank() || version < 1)
                throw new PlatformException(400, "SKILL_VERSION_REQUIRED", "技能必须指定精确版本");
            String key = code + ":" + version;
            if (pinned.containsKey(key)) continue;
            if (pinned.keySet().stream().anyMatch(existing -> existing.startsWith(code + ":")))
                throw new PlatformException(409, "SKILL_VERSION_CONFLICT", "同一词表不能绑定同一技能的多个版本");
            JsonNode pinnedDraft = snapshot.path("controlledSkillVersion");
            JsonNode skill =
                    controlledDraftId != null
                                    && pinnedDraft.path("skillVersionId").asLong()
                                            == controlledDraftId
                                    && pinnedDraft.path("code").asText().equals(code)
                                    && pinnedDraft.path("versionNo").asInt() == version
                            ? pinnedDraft
                            : clients.get(
                                    "skill",
                                    "/internal/skills/" + code + "?version=" + version,
                                    actor);
            boolean controlledDraft =
                    controlledDraftId != null
                            && skill.path("skillVersionId").asLong() == controlledDraftId
                            && skill.path("status").asText().equals("DRAFT");
            if (!controlledDraft
                    && !Set.of("PUBLISHED", "ARCHIVED").contains(skill.path("status").asText()))
                throw new PlatformException(409, "SKILL_NOT_PUBLISHED", "技能尚未发布");
            pinned.put(key, skill);
            boolean visible = true;
            for (JsonNode role : skill.path("guard").path("roles"))
                if (!actor.roles().contains(role.asText()) && !actor.roles().contains("ADMIN"))
                    visible = false;
            var attributeRules = skill.path("guard").path("attributes").fields();
            while (attributeRules.hasNext()) {
                var rule = attributeRules.next();
                boolean match =
                        rule.getValue().isArray()
                                && rule.getValue().isEmpty()
                                && !actor.attributes()
                                        .getOrDefault(rule.getKey(), Set.of())
                                        .isEmpty();
                for (JsonNode allowed : rule.getValue())
                    if (actor.attributes()
                            .getOrDefault(rule.getKey(), Set.of())
                            .contains(allowed.asText())) match = true;
                if (!match) visible = false;
            }
            if (visible)
                tools.add(
                        Json.tree(
                                Map.of(
                                        "code",
                                        code,
                                        "name",
                                        code,
                                        "skillVersionId",
                                        skill.path("skillVersionId").asLong(),
                                        "execution",
                                        skill.path("execution"),
                                        "guard",
                                        skill.path("guard"),
                                        "contract",
                                        skill.path("definition"),
                                        "inputSchema",
                                        skill.path("inputSchema"),
                                        "outputSchema",
                                        skill.path("outputSchema"),
                                        "contractDigest",
                                        skill.path("digest").asText(skill.path("sha256").asText()),
                                        "definition",
                                        Map.of(
                                                "type",
                                                "function",
                                                "function",
                                                Map.of(
                                                        "name",
                                                        code,
                                                        "description",
                                                        skill.path("description").asText(code),
                                                        "parameters",
                                                        skill.path("inputSchema"))))));
        }
        if (snapshot.path("definition").path("nodes").isArray())
            for (JsonNode n : snapshot.path("definition").path("nodes"))
                if (n.has("skillRef")) {
                    JsonNode ref = n.path("skillRef"),
                            skill =
                                    pinned.get(
                                            ref.path("code").asText()
                                                    + ":"
                                                    + ref.path("version").asInt());
                    ((com.fasterxml.jackson.databind.node.ObjectNode) ref)
                            .put("skillVersionId", skill.path("skillVersionId").asLong());
                    ((com.fasterxml.jackson.databind.node.ObjectNode) ref)
                            .set("execution", skill.path("execution"));
                }
        List<JsonNode> promptRefs = new ArrayList<>();
        config.path("promptRefs").forEach(promptRefs::add);
        for (JsonNode n : snapshot.path("definition").path("nodes"))
            if (n.has("promptRef")) promptRefs.add(n.path("promptRef"));
        var prompts = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        Set<String> promptKeys = new HashSet<>();
        for (JsonNode ref : promptRefs) {
            String code = ref.path("code").asText(ref.path("templateCode").asText());
            int version = ref.path("version").asInt();
            if (version < 1 || code.isBlank())
                throw new PlatformException(400, "PROMPT_VERSION_REQUIRED", "提示词必须绑定精确版本");
            if (promptKeys.add(code + ":" + version)) {
                JsonNode template =
                        clients.get(
                                "app",
                                "/internal/prompt-templates/" + code + "/versions/" + version,
                                actor);
                var pinnedPrompt =
                        (com.fasterxml.jackson.databind.node.ObjectNode) template.deepCopy();
                pinnedPrompt.put("code", code);
                pinnedPrompt.put("version", version);
                prompts.add(pinnedPrompt);
            }
        }
        config.set("frozenPrompts", prompts);
        config.set("tools", tools);
        snapshot.set("config", config);
        return snapshot;
    }

    public void drive(long rid, Actor actor) {
        if (!scheduler.owns(rid)) {
            scheduler.runNow(rid, () -> drive(rid, actor));
            return;
        }
        if (store.protectedRun(rid)) { driveOwnedFlow(rid); return; }
        var run = store.db().queryForMap("select * from engine.run where id=?", rid);
        if (!run.get("status").equals("RUNNING")) return;
        for (var pending : store.nodes(rid)) {
            if (!scheduler.mayAdvance(rid)) break;
            if (pending.get("status").equals("READY") && !flow.prepare(run, pending)) continue;
            if (!Set.of("READY", "RUNNING").contains(pending.get("status"))) continue;
            if (pending.get("status").equals("READY")
                    && run.get("trigger_type").equals("DEBUG")
                    && shouldPause(run, pending)
                    && !Boolean.TRUE.equals(pending.get("released"))) {
                pause(run, pending);
                continue;
            }
            Map<String, Object> node;
            try {
                node = store.claim(TraceStore.number(pending, "id"), owner);
            } catch (PlatformException failure) {
                if (failure.code().startsWith("BUDGET_")) {
                    stopForBudget(rid, failure.code());
                    return;
                }
                throw failure;
            }
            if (node == null) continue;
            JsonNode def = TraceStore.json(node.get("definition"));
            if (def.path("type").asText().equals("CONFIRM")
                    && !approvalGranted(rid, TraceStore.number(node, "id"))) {
                store.freeze(
                        TraceStore.number(node, "attemptId"),
                        Map.of(
                                "args",
                                Values.resolve(def.path("args"), flow.scope(run)),
                                "variables",
                                flow.scope(run),
                                "confirmRoles",
                                def.path("confirmRoles")));
                store.atomic(
                        () -> {
                            store.lockRun(rid);
                            int changed =
                                    store.db()
                                            .update(
                                                    "update engine.node_instance set"
                                                        + " status='WAITING_APPROVAL',lease_owner=null,lease_until=null"
                                                        + " where id=? and lease_owner=?",
                                                    node.get("id"),
                                                    node.get("lease_owner"));
                            if (changed > 0)
                                store.event(
                                        rid,
                                        "WAITING_APPROVAL",
                                        Map.of(
                                                "nodeInstanceId",
                                                node.get("id"),
                                                "confirmRoles",
                                                def.path("confirmRoles")));
                            return null;
                        });
                continue;
            }
            activeNodes.add(TraceStore.number(node, "id"));
            try {
                JsonNode output =
                        node.get("node_def_id") == null
                                ? agent.drive(
                                        run,
                                        node,
                                        TraceStore.json(run.get("snapshot")).path("config"),
                                        TraceStore.json(run.get("inputs")),
                                        actor,
                                        false)
                                : flow.execute(run, node, actor);
                store.commit(
                        rid,
                        TraceStore.number(node, "id"),
                        TraceStore.number(node, "attemptId"),
                        node.get("lease_owner").toString(),
                        output);
            } catch (Exception failure) {
                if (failure instanceof PlatformException platform
                        && platform.code().startsWith("BUDGET_")) {
                    stopForBudget(rid, platform.code());
                    return;
                }
                if (Objects.toString(failure.getMessage(), "").startsWith("BUDGET_")
                        || Objects.toString(failure.getMessage(), "").equals("NO_PROGRESS")) {
                    stopForBudget(rid, failure.getMessage());
                    return;
                }
                Map<String, Object> error =
                        failure instanceof RuntimeFault fault
                                ? fault.error()
                                : Map.of(
                                        "semantic",
                                        Set.of(
                                                                "RETRYABLE_FAILURE",
                                                                "NON_RETRYABLE_FAILURE",
                                                                "PARAM_ERROR",
                                                                "GUARD_DENIED",
                                                                "RUNTIME_ERROR")
                                                        .contains(
                                                                Objects.toString(
                                                                        failure.getMessage(), ""))
                                                ? failure.getMessage()
                                                : "RUNTIME_ERROR",
                                        "message",
                                        Objects.toString(
                                                failure.getMessage(),
                                                failure.getClass().getSimpleName()));
                if (error.get("semantic").equals("WAITING_APPROVAL")) {
                    // ToolCalls persisted the exact call/args/roles and WAITING_APPROVAL
                    // atomically.
                } else if (error.get("semantic").equals("RESULT_UNKNOWN")) {
                    store.atomic(
                            () -> {
                                store.lockRun(rid);
                                int changed =
                                        store.db()
                                                .update(
                                                        "update engine.node_instance set"
                                                            + " status='WAITING_EXTERNAL',error=?::jsonb"
                                                            + " where id=? and status='RUNNING' and"
                                                            + " lease_owner=? and"
                                                            + " lease_until>clock_timestamp()",
                                                        Json.write(error),
                                                        node.get("id"),
                                                        node.get("lease_owner"));
                                if (changed > 0) {
                                    store.db()
                                            .update(
                                                    "update engine.attempt set"
                                                        + " status='UNKNOWN',error=?::jsonb where"
                                                        + " id=?",
                                                    Json.write(error),
                                                    node.get("attemptId"));
                                    store.event(
                                            rid,
                                            "WAITING_EXTERNAL",
                                            Map.of(
                                                    "nodeInstanceId",
                                                    node.get("id"),
                                                    "error",
                                                    error));
                                }
                                return null;
                            });
                } else if (def.path("errorPolicy")
                        .path("onFail")
                        .asText()
                        .equals("DEFAULT_VALUE")) {
                    store.db()
                            .update(
                                    "update engine.node_instance set error=?::jsonb where id=?",
                                    Json.write(error),
                                    node.get("id"));
                    store.commit(
                            rid,
                            TraceStore.number(node, "id"),
                            TraceStore.number(node, "attemptId"),
                            node.get("lease_owner").toString(),
                            def.path("errorPolicy").path("defaultValue"));
                } else {
                    store.fail(
                            rid,
                            TraceStore.number(node, "id"),
                            TraceStore.number(node, "attemptId"),
                            node.get("lease_owner").toString(),
                            error);
                    int attemptNo =
                            store.db()
                                    .queryForObject(
                                            "select attempt_no from engine.attempt where id=?",
                                            Integer.class,
                                            node.get("attemptId"));
                    // §30.3.4: one engine retry layer, contract-gated read-only failures only.
                    // Structural faults and uncertain writes never take this retry path.
                    if (error.get("semantic").equals("RETRYABLE_FAILURE")
                            && def.path("skillRef").path("execution").path("readonly").asBoolean()
                            && store.db()
                                            .queryForObject(
                                                    "select coalesce(max(readonly_retry_count),0)"
                                                            + " from engine.tool_call where"
                                                            + " node_instance_id=?",
                                                    Integer.class,
                                                    node.get("id"))
                                    < def.path("errorPolicy").path("retries").asInt()) {
                        store.atomic(
                                () -> {
                                    int changed =
                                            store.db()
                                                    .update(
                                                            "update engine.node_instance set"
                                                                + " status='READY',lease_owner=null,lease_until=null"
                                                                + " where id=? and status='FAILED'"
                                                                + " and lease_owner=?",
                                                            node.get("id"),
                                                            node.get("lease_owner"));
                                    if (changed > 0) {
                                        store.db()
                                                .update(
                                                        "update engine.tool_call set"
                                                            + " status='REGISTERED',claimed_by=null,claimed_lease=null,readonly_retry_count=readonly_retry_count+1"
                                                            + " where node_instance_id=? and"
                                                            + " status='FAILED' and"
                                                            + " error_semantic='RETRYABLE_FAILURE'",
                                                        node.get("id"));
                                        store.event(
                                                rid,
                                                "NODE_RETRY_SCHEDULED",
                                                Map.of(
                                                        "nodeInstanceId",
                                                        node.get("id"),
                                                        "attemptNo",
                                                        attemptNo + 1));
                                    }
                                    return null;
                                });
                    }
                }
            } finally {
                activeNodes.remove(TraceStore.number(node, "id"));
            }
        }
        aggregate(rid);
    }

    private boolean shouldPause(Map<String, Object> run, Map<String, Object> node) {
        if (run.get("mode").equals("STEP")) return true;
        if (run.get("mode").equals("RUN_TO_END")) return false;
        for (JsonNode breakpoint :
                TraceStore.json(run.get("snapshot")).path("debugControls").path("breakpoints"))
            if (breakpoint.asText().equals(Objects.toString(node.get("node_def_id"), "")))
                return true;
        return false;
    }

    private void stopForBudget(long rid, String reason) {
        store.atomic(
                () -> {
                    var run = store.lockRun(rid);
                    if (Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(run.get("status")))
                        return null;
                    store.db()
                            .update(
                                    "update engine.run set"
                                        + " status='FAILED',stop_reason=?,error=?::jsonb,ended_at=now()"
                                        + " where id=?",
                                    reason,
                                    Json.write(Map.of("semantic", reason, "message", "运行预算已耗尽")),
                                    rid);
                    store.db()
                            .update(
                                    "update engine.node_instance set"
                                        + " status='CANCELLED',ended_at=now() where run_id=? and"
                                        + " status not in"
                                        + " ('SUCCEEDED','FAILED','SKIPPED','ABANDONED','CANCELLED')",
                                    rid);
                    store.event(
                            rid, "RUN_FINISHED", Map.of("status", "FAILED", "stopReason", reason));
                    return null;
                });
    }

    private boolean approvalGranted(long run, long node) {
        return Boolean.TRUE.equals(
                store.db()
                        .queryForObject(
                                "select exists(select 1 from engine.run_event where run_id=? and"
                                    + " type='APPROVAL_DONE' and payload->>'nodeInstanceId'=? and"
                                    + " payload->>'approved'='true')",
                                Boolean.class,
                                run,
                                Long.toString(node)));
    }

    private void pause(Map<String, Object> run, Map<String, Object> pending) {
        long rid = TraceStore.number(run, "id");
        var node = store.claim(TraceStore.number(pending, "id"), owner);
        if (node == null) return;
        try {
            JsonNode definition = TraceStore.json(node.get("definition"));
            Object material = Map.of("variables", flow.scope(run), "definition", definition);
            if (Set.of("LLM", "AGENT").contains(definition.path("type").asText())) {
                JsonNode inputs =
                        node.get("node_def_id") == null
                                ? TraceStore.json(run.get("inputs"))
                                : Values.resolve(definition.path("args"), flow.scope(run));
                material =
                        agent.preview(TraceStore.json(run.get("snapshot")).path("config"), inputs);
            }
            store.freeze(TraceStore.number(node, "attemptId"), material);
            store.atomic(
                    () -> {
                        store.lockRun(rid);
                        int n =
                                store.db()
                                        .update(
                                                "update engine.node_instance set"
                                                    + " status='WAITING_STEP',lease_owner=null,lease_until=null"
                                                    + " where id=? and status='RUNNING' and"
                                                    + " lease_owner=?",
                                                node.get("id"),
                                                node.get("lease_owner"));
                        if (n > 0) {
                            int seq =
                                    store.event(
                                            rid,
                                            "WAITING_STEP",
                                            Map.of(
                                                    "nodeInstanceId",
                                                    node.get("id"),
                                                    "nodeDefId",
                                                    Objects.toString(node.get("node_def_id"), ""),
                                                    "promptRef",
                                                    "pg:attempt/" + node.get("attemptId")));
                            store.db()
                                    .update(
                                            "update engine.node_instance set pause_seq=? where"
                                                    + " id=?",
                                            seq,
                                            node.get("id"));
                        }
                        return null;
                    });
        } catch (Exception failure) {
            store.fail(
                    rid,
                    TraceStore.number(node, "id"),
                    TraceStore.number(node, "attemptId"),
                    node.get("lease_owner").toString(),
                    Map.of(
                            "semantic",
                            "PARAM_ERROR",
                            "message",
                            Objects.toString(failure.getMessage(), "预览失败")));
        }
    }

    public void aggregate(long rid) {
        if(store.protectedRun(rid))throw RunAuthorityStore.denied();
        store.atomic(
                () -> {
                    var run =
                            store.db()
                                    .queryForMap(
                                            "select * from engine.run where id=? for update", rid);
                    if (Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(run.get("status")))
                        return null;
                    // A READY row can still be blocked on a waiting predecessor. Counting such rows
                    // as executable would hide WAITING_APPROVAL/STEP and spin synchronous
                    // evaluations.
                    boolean runnable = false;
                    for (var pending : store.nodes(rid)) {
                        if (pending.get("status").equals("RUNNING")) runnable = true;
                        if (pending.get("status").equals("READY") && flow.prepare(run, pending))
                            runnable = true;
                    }
                    var nodes = store.nodes(rid);
                    boolean terminal =
                            nodes.stream()
                                    .allMatch(
                                            n ->
                                                    GraphRules.TERMINAL.contains(
                                                            n.get("status").toString()));
                    String state;
                    if (terminal)
                        state =
                                nodes.stream()
                                                .anyMatch(
                                                        n ->
                                                                n.get("status").equals("FAILED")
                                                                        && !TraceStore.json(
                                                                                        n.get(
                                                                                                "definition"))
                                                                                .path("errorPolicy")
                                                                                .path("onFail")
                                                                                .asText()
                                                                                .equals(
                                                                                        "ERROR_EDGE"))
                                        ? "FAILED"
                                        : "SUCCEEDED";
                    else if (runnable) state = "RUNNING";
                    else
                        state =
                                nodes.stream().anyMatch(n -> n.get("status").equals("WAITING_STEP"))
                                        ? "WAITING_STEP"
                                        : nodes.stream()
                                                        .anyMatch(
                                                                n ->
                                                                        n.get("status")
                                                                                .equals(
                                                                                        "WAITING_EXTERNAL"))
                                                ? "WAITING_EXTERNAL"
                                                : "WAITING_APPROVAL";
                    Object output =
                            nodes.stream()
                                    .filter(
                                            n ->
                                                    n.get("node_type").equals("END")
                                                            && n.get("output") != null)
                                    .map(n -> n.get("output"))
                                    .findFirst()
                                    .orElse(nodes.getLast().get("output"));
                    store.db()
                            .update(
                                    "update engine.run set"
                                        + " status=?,output=?::jsonb,output_ref=?,ended_at=case"
                                        + " when ? then now() else null end,stop_reason=case when ?"
                                        + " then ? else stop_reason end where id=?",
                                    state,
                                    output == null ? null : output.toString(),
                                    output == null ? null : "pg:run/" + rid + "/output",
                                    terminal,
                                    terminal,
                                    state.equals("FAILED") ? "NODE_FAILED" : "FINAL_ANSWER",
                                    rid);
                    if (terminal) store.event(rid, "RUN_FINISHED", Map.of("status", state));
                    return null;
                });
    }

    public Map<String, Object> status(String id, Actor actor) {
        var published = store.protectedRun(id, actor) ? store.statusPublished(id, actor) : null;
        var run = published == null ? store.run(id, actor) : published.run();
        long rid = TraceStore.number(run, "id");
        var nodes = published == null ? store.nodes(rid) : published.nodes();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", id);
        result.put("appCode", run.get("app_code"));
        if (run.get("controlled_draft_skill_version_id") != null
                || run.get("controlled_skill_test_version_id") != null) {
            result.put(
                    "controlledSkillVersionId",
                    run.get("controlled_skill_test_version_id") != null
                            ? run.get("controlled_skill_test_version_id")
                            : run.get("controlled_draft_skill_version_id"));
            result.put(
                    "controlledSkillStatus",
                    run.get("controlled_skill_test_status") != null
                            ? run.get("controlled_skill_test_status")
                            : "DRAFT");
            result.put(
                    "controlledDraftSkillVersionId", run.get("controlled_draft_skill_version_id"));
            var calls =
                    store.db()
                            .queryForList(
                                    "select id from engine.tool_call where run_id=? order by id"
                                            + " desc limit 1",
                                    rid);
            result.put("controlledCallId", calls.isEmpty() ? null : calls.getFirst().get("id"));
            result.put("callId", calls.isEmpty() ? null : calls.getFirst().get("id"));
        }
        result.put("status", run.get("status"));
        result.put("eventsWatermark", run.get("event_seq"));
        result.put("inputs", TraceStore.json(run.get("inputs")));
        result.put("output", TraceStore.json(run.get("output")));
        result.put("fileRefs", published == null ? artifactRefs(rid, id) : List.of());
        result.put("error", TraceStore.json(run.get("error")));
        result.put("versionRefs", TraceStore.json(run.get("version_refs")));
        result.put("nodes", nodes.stream().map(this::nodeView).toList());
        result.put("events", published == null ? store.events(rid, 0) : published.events());
        result.put(
                "paused",
                nodes.stream()
                        .filter(n -> n.get("status").equals("WAITING_STEP"))
                        .findFirst()
                        .map(
                                n ->
                                        Map.of(
                                                "nodeInstanceId",
                                                n.get("id"),
                                                "nodeDefId",
                                                Objects.toString(n.get("node_def_id"), ""),
                                                "pauseSeq",
                                                n.get("pause_seq"),
                                                "allowedActions",
                                                List.of("NEXT_NODE", "RUN_TO_END", "CANCEL")))
                        .orElse(null));
        if (published != null) published.requireCurrent();
        return result;
    }

    private List<Map<String, Object>> artifactRefs(long run, String publicRunId) {
        Map<String, Map<String, Object>> files = new LinkedHashMap<>();
        for (var call :
                store.db()
                        .queryForList(
                                "select result from engine.tool_call where run_id=? and"
                                        + " status='SUCCEEDED' order by id",
                                run))
            for (JsonNode ref : TraceStore.json(call.get("result")).path("fileRefs")) {
                String id = ref.path("artifactId").asText(), url = ref.path("downloadUrl").asText();
                if (!id.matches("[A-Za-z0-9_-]{1,128}")
                        || !url.matches("/admin/artifacts/[A-Za-z0-9_-]{1,128}/download")) continue;
                Map<String, Object> safe = new LinkedHashMap<>();
                for (String field :
                        List.of(
                                "artifactId",
                                "downloadUrl",
                                "name",
                                "fileName",
                                "mimeType",
                                "contentType",
                                "size",
                                "sizeBytes",
                                "sha256",
                                "bytes",
                                "summary"))
                    if (ref.hasNonNull(field)) safe.put(field, ref.path(field));
                String external = ref.path("externalDownloadUrl").asText();
                if (external.equals("/v1/runs/" + publicRunId + "/artifacts/" + id + "/download"))
                    safe.put("externalDownloadUrl", external);
                files.putIfAbsent(id, safe);
            }
        return List.copyOf(files.values());
    }

    private Map<String, Object> nodeView(Map<String, Object> n) {
        Map<String, Object> v = new LinkedHashMap<>();
        for (String k :
                List.of(
                        "id",
                        "node_def_id",
                        "node_type",
                        "status",
                        "pause_seq",
                        "error",
                        "output",
                        "started_at",
                        "ended_at")) v.put(k, n.get(k));
        v.put("error", TraceStore.json(n.get("error")));
        v.put("output", TraceStore.json(n.get("output")));
        v.put("nodeInstanceId", n.get("id"));
        v.put("nodeDefId", n.get("node_def_id"));
        return v;
    }

    public Map<String, Object> resume(String id, JsonNode req, Actor actor) {
        var run = store.run(id, actor);
        long rid = TraceStore.number(run, "id");
        String action = req.path("action").asText();
        if (!Set.of("NEXT_NODE", "RUN_TO_END", "RUN_TO_BREAKPOINT").contains(action)
                || req.path("requestId").asText().isBlank())
            throw new PlatformException(400, "INVALID_RESUME", "请提供合法动作与requestId");
        return store.atomic(
                () -> {
                    store.db().queryForMap("select id from engine.run where id=? for update", rid);
                    if (!store.db()
                            .queryForList(
                                    "select id from engine.run_event where run_id=? and"
                                            + " type='RESUMED' and payload->>'requestId'=?",
                                    rid,
                                    req.path("requestId").asText())
                            .isEmpty()) return Map.of("deduped", true);
                    int n =
                            store.db()
                                    .update(
                                            "update engine.node_instance set"
                                                    + " status='READY',released=true where id=? and"
                                                    + " run_id=? and status='WAITING_STEP' and"
                                                    + " pause_seq=?",
                                            req.path("nodeInstanceId").asLong(),
                                            rid,
                                            req.path("pauseSeq").asInt());
                    if (n == 0) return Map.of("stale", true);
                    store.event(
                            rid,
                            "RESUMED",
                            Map.of(
                                    "requestId",
                                    req.path("requestId").asText(),
                                    "nodeInstanceId",
                                    req.path("nodeInstanceId").asLong(),
                                    "pauseSeq",
                                    req.path("pauseSeq").asInt(),
                                    "action",
                                    action));
                    store.db()
                            .update(
                                    "update engine.run set"
                                        + " status='RUNNING',dispatch_next_at=now(),mode=case when"
                                        + " ?='NEXT_NODE' then 'STEP' else ? end where id=?",
                                    action,
                                    action.equals("RUN_TO_END") ? "RUN_TO_END" : "FULL",
                                    rid);
                    return Map.of("status", "RUNNING");
                });
    }

    public Map<String, Object> cancel(String id, Actor actor) {
        var run = store.run(id, actor);
        long rid = TraceStore.number(run, "id");
        store.atomic(
                () -> {
                    int n =
                            store.db()
                                    .update(
                                            "update engine.run set"
                                                + " status='CANCELLED',stop_reason='USER_CANCEL',ended_at=now()"
                                                + " where id=? and status not in"
                                                + " ('SUCCEEDED','FAILED','CANCELLED')",
                                            rid);
                    if (n > 0) {
                        store.db()
                                .update(
                                        "update engine.node_instance set"
                                            + " status='CANCELLED',ended_at=now() where run_id=?"
                                            + " and status not in"
                                            + " ('SUCCEEDED','FAILED','SKIPPED','ABANDONED','CANCELLED')",
                                        rid);
                        store.db()
                                .update(
                                        "update engine.tool_call set status='ABANDONED' where"
                                            + " run_id=? and status in ('REGISTERED','EXECUTING')",
                                        rid);
                        int inFlight =
                                store.db()
                                        .queryForObject(
                                                "select count(*) from engine.tool_call where"
                                                        + " run_id=? and status='SUBMITTED'",
                                                Integer.class,
                                                rid);
                        if (inFlight > 0)
                            store.event(
                                    rid,
                                    "CANCEL_IN_FLIGHT",
                                    Map.of(
                                            "submittedCalls",
                                            inFlight,
                                            "externalEffectReverted",
                                            false));
                        store.event(rid, "RUN_FINISHED", Map.of("status", "CANCELLED"));
                    }
                    return null;
                });
        return status(id, actor);
    }

    public List<Map<String, Object>> list(Actor actor) {
        return store.db()
                .queryForList(
                        "select public_id as \"runId\",app_code as \"appCode\",trigger_type as"
                            + " \"triggerType\",status,started_at as \"startedAt\",ended_at as"
                            + " \"endedAt\",stop_reason as \"stopReason\" from engine.run where"
                            + " tenant_id=? and principal_id=? and trigger_type in ('CHAT','TASK')"
                            + " order by id desc limit 200",
                        actor.tenantId(),
                        actor.principalId());
    }

    public void recover() {
        scheduler.poll();
    }

    private void driveRecovered(long id) {
        if(store.protectedRun(id)){driveOwnedFlow(id);return;}
        var run = store.db().queryForMap("select * from engine.run where id=?", id);
        JsonNode g = TraceStore.json(run.get("guard_ctx"));
        if (!g.path("tenantId").asText().equals(run.get("tenant_id"))
                || !g.path("principalId").asText().equals(run.get("principal_id"))) {
            stopForBudget(TraceStore.number(run, "id"), "GUARD_SNAPSHOT_INVALID");
            return;
        }
        Set<String> roles = new HashSet<>();
        g.path("roles").forEach(r -> roles.add(r.asText()));
        Map<String, Set<String>> attrs = new HashMap<>();
        g.path("attributes")
                .fields()
                .forEachRemaining(
                        e -> {
                            Set<String> values = new HashSet<>();
                            e.getValue().forEach(v -> values.add(v.asText()));
                            attrs.put(e.getKey(), values);
                        });
        Actor actor =
                new Actor(
                        g.path("tenantId").asText(), g.path("principalId").asText(), roles, attrs);
        drive(TraceStore.number(run, "id"), actor);
    }

    private void driveOwnedFlow(long id) {
        var dispatch=scheduler.ownedDispatch(id);
        try {
            if(runAuthority==null)throw RunAuthorityStore.denied();
            var start=runAuthority.currentRetained(id);
            var nodes=store.startOwnedFlow(dispatch,start);
            for(long pending:nodes){
                if(!scheduler.mayAdvance(id))return;
                var proof=runAuthority.currentRetained(id);
                var work=store.prepareAndClaimFlow(pending,owner,dispatch,proof);
                if(work==null)continue;
                if(work.budgetExhausted()!=null){store.stopFlowForBudget(dispatch,proof,work.budgetExhausted());return;}
                var node=work.node();long attempt=TraceStore.number(node,"attemptId");String leaseOwner=node.get("lease_owner").toString();
                JsonNode output=null;Map<String,Object> error=null;
                activeNodes.add(pending);
                try {
                    if(!scheduler.mayAdvance(id))return;
                    // Only execution faults enter business error policy. All transaction commits,
                    // proof/dispatch checks and infrastructure failures remain outside this catch.
                    try { output=flow.executeFlow(work,dispatch,proof); }
                    catch(RuntimeFault fault){error=fault.error();}
                    catch(IllegalArgumentException failure){error=Map.of("semantic","RUNTIME_ERROR","message",Objects.toString(failure.getMessage(),"执行失败"));}
                    proof.requireVerified();
                    if(error==null)store.commitFlow(pending,attempt,leaseOwner,output,dispatch,proof);
                    else {
                        var policy=TraceStore.json(node.get("definition")).path("errorPolicy");
                        if(policy.path("onFail").asText().equals("DEFAULT_VALUE"))store.commitDefaultFlow(pending,attempt,leaseOwner,policy.path("defaultValue"),error,dispatch,proof);
                        else store.failFlow(pending,attempt,leaseOwner,error,dispatch,proof);
                    }
                } finally {activeNodes.remove(pending);}
            }
            // Aggregate is a new action, with its own current obtained outside the owned RR.
            store.aggregateFlow(dispatch,runAuthority.currentRetained(id));
        } catch(RuntimeException failure) {
            scheduler.deferOwned(id,java.time.Duration.ofSeconds(3));
            throw failure;
        }
    }

    public void resumeExternalFacts() {
        // W3 (§31.2): consume durable successful/failed execution facts without repeating the
        // skill.
        for (var node :
                store.db()
                        .queryForList(
                                "select n.id,n.run_id from engine.node_instance n join engine.run r"
                                    + " on r.id=n.run_id where n.status='WAITING_EXTERNAL' and"
                                    + " r.status in ('RUNNING','WAITING_EXTERNAL') and not exists(select 1 from engine.run_authority a where a.run_id=r.id) and"
                                    + " exists(select 1 from engine.tool_call t where"
                                    + " t.node_instance_id=n.id) and not exists(select 1 from"
                                    + " engine.tool_call t where t.node_instance_id=n.id and"
                                    + " t.status not in ('SUCCEEDED','FAILED')) order by n.id limit"
                                    + " 32"))
            store.atomic(
                    () -> {
                        long rid = TraceStore.number(node, "run_id");
                        store.lockRun(rid);
                        int changed =
                                store.db()
                                        .update(
                                                "update engine.node_instance set"
                                                    + " status='READY',released=true,lease_owner=null,lease_until=null"
                                                    + " where id=? and status='WAITING_EXTERNAL'",
                                                node.get("id"));
                        if (changed > 0) {
                            store.db()
                                    .update(
                                            "update engine.run set status='RUNNING' where id=? and"
                                                    + " status='WAITING_EXTERNAL'",
                                            rid);
                            scheduler.wake(rid);
                            store.event(
                                    rid,
                                    "EXTERNAL_READY",
                                    Map.of("nodeInstanceId", node.get("id")));
                        }
                        return null;
                    });
    }

    public void renewLeases() {
        for (Long node : activeNodes) {
            try {
                store.atomic(
                        () -> {
                            // Each item is a short transaction. Busy node/tool rows are skipped,
                            // never waited on while other live workers need their leases renewed.
                            int renewed =
                                    store.db()
                                            .update(
                                                    "with renewable as (select id from"
                                                        + " engine.node_instance where id=? and"
                                                        + " status='RUNNING' and lease_owner like ?"
                                                        + " and lease_until>clock_timestamp() for update skip"
                                                        + " locked) update engine.node_instance n"
                                                        + " set lease_until=clock_timestamp()+interval '90"
                                                        + " seconds' from renewable r where"
                                                        + " n.id=r.id",
                                                    node,
                                                    owner + "_%");
                            if (renewed == 0) return null;
                            store.db()
                                    .update(
                                            "with renewable as (select t.id,n.lease_until from"
                                                + " engine.tool_call t join engine.node_instance n"
                                                + " on n.id=t.node_instance_id where n.id=? and"
                                                + " n.status='RUNNING' and n.lease_owner like ? and"
                                                + " n.lease_until>clock_timestamp() and"
                                                + " t.claimed_by=n.lease_owner and t.status in"
                                                + " ('EXECUTING','SUBMITTED') and"
                                                + " t.claimed_lease>now() for update of t skip"
                                                + " locked) update engine.tool_call t set"
                                                + " claimed_lease=r.lease_until from renewable r"
                                                + " where t.id=r.id",
                                            node,
                                            owner + "_%");
                            return null;
                        });
            } catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(RunService.class)
                        .warn("Node lease renewal failed: {}", failure.getClass().getSimpleName());
            }
        }
    }

    @Scheduled(fixedDelay = 60000)
    public void expireDebugRuns() {
        for (var candidate :
                store.db()
                        .queryForList(
                                "select distinct r.id from engine.run r join engine.node_instance n"
                                    + " on n.run_id=r.id join engine.run_event e on e.run_id=r.id"
                                    + " and e.seq=n.pause_seq where r.trigger_type='DEBUG' and"
                                    + " r.status in ('RUNNING','WAITING_STEP') and"
                                    + " n.status='WAITING_STEP' and e.created_at<now()-interval '30"
                                    + " minutes'"))
            store.atomic(
                    () -> {
                        long id = TraceStore.number(candidate, "id");
                        int n =
                                store.db()
                                        .update(
                                                "update engine.run set"
                                                    + " status='CANCELLED',stop_reason='DEBUG_TIMEOUT',ended_at=now()"
                                                    + " where id=? and status in"
                                                    + " ('RUNNING','WAITING_STEP')",
                                                id);
                        if (n > 0) {
                            store.db()
                                    .update(
                                            "update engine.node_instance set"
                                                + " status='CANCELLED',ended_at=now() where"
                                                + " run_id=? and status not in"
                                                + " ('SUCCEEDED','FAILED','SKIPPED','ABANDONED','CANCELLED')",
                                            id);
                            store.event(
                                    id,
                                    "RUN_FINISHED",
                                    Map.of("status", "CANCELLED", "stopReason", "DEBUG_TIMEOUT"));
                        }
                        return null;
                    });
    }
}
