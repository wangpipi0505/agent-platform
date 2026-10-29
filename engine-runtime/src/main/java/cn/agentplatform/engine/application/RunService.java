package cn.agentplatform.engine.application;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.common.authority.HttpCurrentAuthorityClient.RetainedAuthorization;
import cn.agentplatform.engine.domain.*;
import cn.agentplatform.engine.infra.*;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
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
    private final RunAuthorityStore admissionStore;
    private final Clock clock;
    /**
     * 可选装配：未配置本体地址的环境不注入。冻结阶段是唯一取包点，之后 assemble 只读文本。
     */
    private OntologyContextPacks contextPacks;
    private boolean promptDigestCheck = true;
    /**
     * 可选装配：三级 token 配额闸门（PLAT-19 T19.1，agent.engine.quota-enabled 默认 false）。
     * 夹具未装配（null）即旁路，保持既有构造器不变。
     */
    private RunQuota runQuota;
    /**
     * 运行期发布绑定解析开关与环境。默认开启、production：关闭即退回"只有显式 releaseId 才注入"的
     * 旧行为，作为本卡唯一的运行时回滚面；环境决定查询哪份绑定。
     */
    private boolean pinResolutionEnabled = true;
    private String pinEnvironment = "production";

    private final ModelInputMaterials initialMaterials;
    private final ChunkBus initialChunks;
    private final String owner = UUID.randomUUID().toString();
    private final Set<Long> activeNodes = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.regex.Pattern UUID_PATTERN =
            java.util.regex.Pattern.compile(
                    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    /** 模板摘要形状：只有校验通过的摘要才允许进入不可变运行的引用记录。 */
    private static final java.util.regex.Pattern PROMPT_SHA_PATTERN =
            java.util.regex.Pattern.compile("[0-9a-f]{64}");

    public RunService(TraceStore s, RuntimeClients c, FlowDriver f, AgentDriver a) {
        this(s, c, f, a, new RunScheduler(s));
    }

    @Autowired
    public RunService(
            TraceStore s, RuntimeClients c, FlowDriver f, AgentDriver a, RunScheduler scheduler) {
        this(s, c, f, a, scheduler, null);
    }

    /** Explicit owned-worker assembly; legacy admission remains explicit in RuntimeClients. */
    public RunService(
            TraceStore s,
            RuntimeClients c,
            FlowDriver f,
            AgentDriver a,
            RunScheduler scheduler,
            RunAuthority authority) {
        this(s, c, f, a, scheduler, authority, null, Clock.systemUTC());
    }

    /**
     * Fixed trusted clock/store for the isolated IAM assembly; never selected from request data.
     */
    public RunService(
            TraceStore s,
            RuntimeClients c,
            FlowDriver f,
            AgentDriver a,
            RunScheduler scheduler,
            RunAuthority authority,
            RunAuthorityStore admissionStore,
            Clock clock) {
        this(s, c, f, a, scheduler, authority, admissionStore, clock, null, null);
    }

    /** Trusted finite component assembly, independent of public CHAT admission and observation. */
    public RunService(TraceStore s, RuntimeClients c, FlowDriver f, AgentDriver a,
            RunScheduler scheduler, RunAuthority authority, RunAuthorityStore admissionStore,
            Clock clock, ModelInputMaterials initialMaterials, ChunkBus initialChunks) {
        if (initialMaterials != null && (authority == null || initialChunks == null))
            throw new IllegalArgumentException("INITIAL_COMPONENT_DEPENDENCIES_REQUIRED");
        this.initialMaterials = initialMaterials;
        this.initialChunks = initialChunks;
        this.admissionStore = admissionStore;
        this.clock = Objects.requireNonNull(clock);
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

    public boolean iamEnabled() {
        return clients != null && clients.iamEnabled();
    }

    /**
     * Setter 注入保持既有构造器夹具不变（测试大量直接 new RunService(...)）。
     * required=false：未配置本体地址时不阻断启动，但声明 ontology 的运行会得到 503。
     */
    @Autowired(required = false)
    public void contextPacks(OntologyContextPacks packs) {
        this.contextPacks = packs;
    }

    /** Setter 注入保持既有构造器夹具不变（测试大量直接 new RunService(...)）。 */
    @Autowired(required = false)
    public void runQuota(RunQuota quota) {
        this.runQuota = quota;
    }

    /**
     * 摘要兜底开关由部署属性注入；字段初始 true，使大量直接 new RunService(...) 的夹具保持严格语义。
     * 仅升级过渡期允许显式关闭引擎侧校验，关闭时也不把未通过校验的摘要写进运行留痕。
     */
    @Autowired
    public void promptDigestCheck(@Value("${agent.prompt.digest-check:true}") boolean enabled) {
        this.promptDigestCheck = enabled;
    }

    /** 绑定解析开关：字段初始 true，使直接 new RunService(...) 的夹具默认走绑定解析。 */
    @Autowired
    public void pinResolutionEnabled(
            @Value("${agent.pin.resolution-enabled:true}") boolean enabled) {
        this.pinResolutionEnabled = enabled;
    }

    /** 查询哪份发布绑定；空值回落到 production，避免空属性把查询打成 environment=。 */
    @Autowired
    public void pinEnvironment(@Value("${agent.pin.environment:production}") String environment) {
        this.pinEnvironment =
                environment == null || environment.isBlank() ? "production" : environment.trim();
    }

    /**
     * PLAT-20 T20.1：运行环境标注开关，取值与 /health/data（DataHealthService）同源——
     * AGENT_DEMO_MODE=true 即 DEMO，否则 REAL；只做标注，不做任何行为分支。
     * 字段初始 false，使大量直接 new RunService(...) 的夹具保持 REAL 语义。
     */
    private boolean demoMode = false;

    @Autowired
    public void demoMode(@Value("${AGENT_DEMO_MODE:false}") String demoMode) {
        this.demoMode = Boolean.parseBoolean(demoMode == null ? "" : demoMode.trim());
    }

    /**
     * PLAT-20 T20.1：运行创建即冻结的标注——写入快照顶层 runtime 对象（与 T13 的 contextPack
     * 同层级，不另造并行结构），随 run 记录不可变。environment 缺省语义定死为 REAL：无演示
     * 标记（AGENT_DEMO_MODE 缺失/false）的环境一律标 REAL，与 /health/data 取值同源；旧运行
     * 记录不回填。dataset 引用只在入参携带非空 datasetVersion 时透传，缺失不落键、不伪造。
     */
    static void annotateRuntime(
            com.fasterxml.jackson.databind.node.ObjectNode snapshot,
            boolean demoMode,
            JsonNode req) {
        var runtime = snapshot.putObject("runtime");
        runtime.put("environment", demoMode ? "DEMO" : "REAL");
        runtime.put("demoMode", demoMode);
        String datasetVersion = req == null ? null : req.path("datasetVersion").asText("").trim();
        if (!datasetVersion.isBlank()) runtime.put("datasetVersion", datasetVersion);
    }

    /**
     * PLAT-20 T20.1：把冻结快照里的运行环境标注铺到运行详情顶层，演示运行借此区分。
     * 旧运行快照没有 runtime 对象时一个键都不铺——详情形状与历史版本逐键兼容，不补 null。
     */
    public static void surfaceRuntimeAnnotation(
            Map<String, Object> status, JsonNode snapshotJson) {
        var runtime = snapshotJson.path("runtime");
        if (!runtime.isObject()) return;
        if (runtime.hasNonNull("environment"))
            status.put("environment", runtime.path("environment").asText());
        if (runtime.hasNonNull("demoMode"))
            status.put("demoMode", runtime.path("demoMode").asBoolean());
        if (runtime.hasNonNull("datasetVersion"))
            status.put("datasetVersion", runtime.path("datasetVersion").asText());
    }

    /**
     * PLAT-14 T14.1：工具是否属于所选档案。选择只读两条声明数据——工具引用上的 profiles 数组
     * 与应用配置的 toolProfile 字符串；profile 名是自由约定的标签（任意字符串都合法），不存在
     * 按路线或软件名分支的代码路径（开发计划 §6.1 禁止路线特判）。profile 未声明（空白）→
     * 任何工具都可见，等价既有行为；未声明 profiles 的工具不属于任何档案。
     */
    static boolean toolInProfile(JsonNode ref, String profile) {
        if (profile == null || profile.isBlank()) return true;
        for (JsonNode name : ref.path("profiles"))
            if (profile.equals(name.asText())) return true;
        return false;
    }

    /**
     * PLAT-14 T14.1：档案选择留痕——写快照顶层 toolSelection（与 T13 contextPack、T20 runtime
     * 同层级，不另造并行结构），随 run 记录不可变，运行详情经 surfaceToolSelection 透出。
     * 语义定死：声明了 profile 但没有任何工具命中时放行空工具集继续运行（不拒绝启动），
     * warning 显式留痕。存量应用未使用任何档案键（toolProfile/profiles/enabled）时不落键，
     * 冻结快照与历史版本逐键一致。
     */
    static void annotateToolSelection(
            com.fasterxml.jackson.databind.node.ObjectNode snapshot,
            String profile,
            List<String> selected,
            List<Map<String, String>> excluded,
            boolean declared) {
        if (!declared) return;
        var trace = snapshot.putObject("toolSelection");
        if (profile != null && !profile.isBlank()) trace.put("profile", profile);
        var picked = trace.putArray("selected");
        selected.forEach(picked::add);
        if (!excluded.isEmpty()) {
            var skipped = trace.putArray("excluded");
            for (Map<String, String> item : excluded) skipped.add(Json.tree(item));
        }
        if (profile != null && !profile.isBlank() && selected.isEmpty())
            trace.put(
                    "warning",
                    "已声明工具档案 " + profile + " 但没有任何工具命中，本次运行以空工具集执行");
    }

    /**
     * PLAT-14 T14.1：把冻结快照里的工具档案留痕铺到运行详情顶层。旧运行快照没有
     * toolSelection 对象时一个键都不铺——详情形状与历史版本逐键兼容，不补 null。
     */
    public static void surfaceToolSelection(Map<String, Object> status, JsonNode snapshotJson) {
        var selection = snapshotJson.path("toolSelection");
        if (selection.isObject()) status.put("toolSelection", selection.deepCopy());
    }

    private static final String REPLAY_METADATA =
            "select id,public_id,app_code,trigger_type,mode,tenant_id,principal_id from engine.run"
                + " where tenant_id=? and principal_id=? and request_id=?";

    private RetainedAuthorization admissionProof(String code, Actor actor) {
        if (admissionStore == null || runAuthority == null)
            throw new PlatformException(403, "IAM_ADMISSION_UNAVAILABLE", "受理授权尚未装配");
        MetadataSelectors.code(code);
        var current = AuthorityContext.current();
        var root = current.call().resource();
        if (!"gateway".equals(current.caller())
                || !"engine".equals(current.call().target())
                || !"proxy.engine.post.RunController.run".equals(current.call().action())
                || !"PORTAL".equals(current.operation().sourceKind())
                || !"PORTAL".equals(current.operation().originKind())
                || !"app-version".equals(root.kind())
                || !root.equals(current.operation().resource())
                || !root.id().matches(java.util.regex.Pattern.quote(code) + "@[1-9][0-9]*"))
            throw RunAuthorityStore.denied();
        return AuthorityContext.requireInitialFor(actor, root);
    }

    private static void requirePublishedRequest(
            JsonNode request,
            String trigger,
            JsonNode trusted,
            Long controlled,
            boolean recorded,
            RunScheduler.Admission ticket,
            boolean reservation) {
        if (!"TASK".equals(trigger)
                || trusted != null
                || controlled != null
                || recorded
                || ticket != null
                || reservation
                || !"FULL".equals(request.path("mode").asText("FULL")))
            throw RunAuthorityStore.denied();
        for (String key :
                List.of(
                        "versionNo",
                        "candidateSha256",
                        "draftRev",
                        "frozenCandidate",
                        "breakpoints",
                        "mocks",
                        "controlledReplay",
                        "controlledContractDigest",
                        "debugControls",
                        "evalReportId",
                        "caseId",
                        "sessionId",
                        "chatSession",
                        "chatTurn",
                        "sessionHistory",
                        "history",
                        "historyRefs",
                        "materialRefs")) if (request.has(key)) throw RunAuthorityStore.denied();
        if (request.hasNonNull("requestId")
                && (!request.path("requestId").isTextual()
                        || request.path("requestId").textValue().isBlank()
                        || request.path("requestId").textValue().length() > 128))
            throw RunAuthorityStore.denied();
        if (request.path("inputs").hasNonNull("materialRefs")
                || request.path("inputs").hasNonNull("historyRefs"))
            throw RunAuthorityStore.denied();
    }

    private static boolean dependency(JsonNode value) {
        return !value.isMissingNode()
                && !value.isNull()
                && (!value.isContainerNode() || !value.isEmpty());
    }

    private void requirePublishedFlow(JsonNode snapshot) { GraphRules.requirePublishedFlow(snapshot); }

    private void requireFirstChat(JsonNode snapshot, JsonNode inputs) {
        if (initialMaterials == null || initialChunks == null) throw RunAuthorityStore.denied();
        InitialChatRules.requireSources(snapshot, inputs);
        ModelInputMaterials.initialOutputPolicy(snapshot.path("config").path("output"));
        FrozenModels.reference(snapshot, InitialChatRules.entryId(snapshot));
    }

    private void admissionTransaction(RetainedAuthorization proof) {
        AuthorityContext.requireBound(proof);
        store.db().execute("set local statement_timeout='2000ms'");
        store.db().execute("set local lock_timeout='1500ms'");
    }

    private static void requireInitialCoverage(RetainedAuthorization proof) {
        AuthorityContext.requireBound(proof);
        Actor initial = proof.requireInitialCeiling(), current = proof.current().actor();
        if (!initial.tenantId().equals(current.tenantId())
                || !initial.principalId().equals(current.principalId())
                || !current.roles().containsAll(initial.roles())) throw RunAuthorityStore.denied();
        initial.attributes()
                .forEach(
                        (key, values) -> {
                            if (!key.equals("dataLabels")
                                    && !current.attributes()
                                            .getOrDefault(key, Set.of())
                                            .containsAll(values)) throw RunAuthorityStore.denied();
                        });
    }

    private Map<String, Object> replayPublished(
            Map<String, Object> metadata,
            String code,
            JsonNode request,
            String trigger,
            Actor actor) {
        // The three requestId branches return metadata only while locked. One protected RR view
        // supplies both original replay validation and the complete response after the lock ends.
        String id = metadata.get("public_id").toString();
        if (!("TASK".equals(metadata.get("trigger_type")) || initialMaterials != null && "CHAT".equals(metadata.get("trigger_type")))
                || !"FULL".equals(metadata.get("mode"))
                || !code.equals(metadata.get("app_code"))
                || !actor.tenantId().equals(metadata.get("tenant_id"))
                || !actor.principalId().equals(metadata.get("principal_id")))
            throw RunAuthorityStore.denied();
        var view = store.statusPublished(id, actor);
        if (!metadata.get("trigger_type").equals(view.run().get("trigger_type"))
                || !"FULL".equals(view.run().get("mode"))) throw RunAuthorityStore.denied();
        verifyReplay(view.run(), code, request, trigger);
        return composeStatus(id, actor, view);
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
        // REQUIRED can join an ambient transaction. Reject it before any refresh/app I/O so
        // this admission owns the real outer commit boundary (Spring 6.2.7 TransactionTemplate).
        final boolean protectedAdmission = iamEnabled();
        if (protectedAdmission && TransactionSynchronizationManager.isActualTransactionActive())
            throw new PlatformException(403, "AUTHORITY_TRANSACTION_FORBIDDEN", "受理不能加入调用方事务");
        if (protectedAdmission)
            requirePublishedRequest(
                    req,
                    trigger,
                    trustedSnapshot,
                    controlledDraftId,
                    recordedReplay,
                    suppliedTicket,
                    acceptedReservation);
        if (code == null || code.isBlank() || code.length() > 64 || !req.path("inputs").isObject())
            throw new PlatformException(400, "RUN_INPUT_INVALID", "请提供应用编码和对象类型inputs");
        if (req.has("versionNo") && req.path("versionNo").asInt() < 1)
            throw new PlatformException(400, "VERSION_INVALID", "版本号必须大于零");
        final RetainedAuthorization opening =
                protectedAdmission ? admissionProof(code, actor) : null;
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
                                    protectedAdmission
                                            ? REPLAY_METADATA
                                            : "select * from engine.run where tenant_id=? and"
                                                  + " principal_id=? and request_id=?",
                                    actor.tenantId(),
                                    actor.principalId(),
                                    req.path("requestId").asText());
            if (!prior.isEmpty()) {
                var existing = prior.getFirst();
                if (protectedAdmission) return replayPublished(existing, code, req, trigger, actor);
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
                                            if (protectedAdmission) admissionTransaction(opening);
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
                                            if (protectedAdmission)
                                                AuthorityContext.requireBound(opening);
                                            var rows =
                                                    store.db()
                                                            .queryForList(
                                                                    protectedAdmission
                                                                            ? REPLAY_METADATA
                                                                            : "select * from"
                                                                                  + " engine.run"
                                                                                  + " where"
                                                                                  + " tenant_id=?"
                                                                                  + " and principal_id=?"
                                                                                  + " and request_id=?",
                                                                    actor.tenantId(),
                                                                    actor.principalId(),
                                                                    req.path("requestId").asText());
                                            if (rows.isEmpty()) return null;
                                            if (!protectedAdmission)
                                                verifyReplay(rows.getFirst(), code, req, trigger);
                                            return rows.getFirst();
                                        })
                                : null;
                if (accepted != null)
                    return protectedAdmission
                            ? replayPublished(accepted, code, req, trigger, actor)
                            : status(accepted.get("public_id").toString(), actor);
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
                                    : protectedAdmission
                                            ? clients.published(code, opening)
                                            : clients.get("app", path, actor);
            final boolean firstChat = protectedAdmission && "CHAT".equals(source.path("type").asText());
            if (firstChat) requireFirstChat(source, req.path("inputs"));
            else if (protectedAdmission) requirePublishedFlow(source);
            final String actualTrigger =
                    trigger.equals("TASK") && source.path("type").asText().equals("CHAT")
                            ? "CHAT"
                            : trigger;
            JsonNode snapshot =
                    recordedReplay || firstChat
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
            validateCapabilitySnapshot(snapshot);
            InputDeclarations.validate(snapshot, req.path("inputs"));
            if (!recordedReplay && !firstChat) freezeModelRefs(snapshot, trigger, actor);
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
            // PLAT-20 T20.1：环境标注必须在冻结前写入。放在这里（而非 prepareSnapshot）是为了
            // 覆盖全部创建路径——录制重放与首发 CHAT 不走 prepareSnapshot；重放产生的是新 run
            // 记录，按当前进程环境标注。引擎对 runtime 键有所有权，应用快照同名键被有意覆盖。
            if (snapshot instanceof com.fasterxml.jackson.databind.node.ObjectNode annotated)
                annotateRuntime(annotated, demoMode, req);
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
            // PLAT-19 T19.1：三级 token 配额在 engine.run 插入前、受理事务外判定（只读窗口求和），
            // 超限以 QUOTA 完整信封拒绝；protected/legacy 两个插入路径都经过此处，被拒运行不落库，
            // admission 槽位由外层 finally 释放。开关关闭（默认）或夹具未装配时旁路。
            if (runQuota != null) runQuota.requireRunAdmission(actor, code, frozen);
            final RetainedAuthorization admissionProof =
                    protectedAdmission ? admissionProof(code, actor) : null;
            if (protectedAdmission) requireInitialCoverage(admissionProof);
            final var created = new java.util.concurrent.atomic.AtomicBoolean(!protectedAdmission);
            String id =
                    protectedAdmission
                            ? null
                            : "r_" + UUID.randomUUID().toString().replace("-", "");
            java.util.function.Supplier<Long> persist =
                    () ->
                            store.atomic(
                                    () -> {
                                        if (protectedAdmission)
                                            admissionTransaction(admissionProof);
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
                                        if (protectedAdmission)
                                            AuthorityContext.requireBound(admissionProof);
                                        if (req.hasNonNull("requestId")) {
                                            var existing =
                                                    store.db()
                                                            .queryForList(
                                                                    protectedAdmission
                                                                            ? REPLAY_METADATA
                                                                            : "select * from"
                                                                                  + " engine.run"
                                                                                  + " where"
                                                                                  + " tenant_id=?"
                                                                                  + " and principal_id=?"
                                                                                  + " and request_id=?",
                                                                    actor.tenantId(),
                                                                    actor.principalId(),
                                                                    req.path("requestId").asText());
                                            if (!existing.isEmpty()) {
                                                if (!protectedAdmission)
                                                    verifyReplay(
                                                            existing.getFirst(),
                                                            code,
                                                            req,
                                                            trigger);
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
                                                protectedAdmission
                                                        ? firstChat ? admissionStore.createPublishedFirstChat(
                                                                admissionProof,
                                                                frozen,
                                                                req.path("inputs"),
                                                                Json.tree(
                                                                        versionRefs(
                                                                                source, frozen,
                                                                                req)),
                                                                Json.tree(
                                                                        initialBudget(
                                                                                frozen.path(
                                                                                        "config"))),
                                                                "FULL",
                                                                req.hasNonNull("requestId")
                                                                        ? req.path("requestId")
                                                                                .asText()
                                                                        : null,
                                                                Set.of())
                                                        : admissionStore.createPublished(
                                                                admissionProof,
                                                                frozen,
                                                                req.path("inputs"),
                                                                Json.tree(
                                                                        versionRefs(
                                                                                source, frozen,
                                                                                req)),
                                                                Json.tree(
                                                                        initialBudget(
                                                                                frozen.path(
                                                                                        "config"))),
                                                                "FULL",
                                                                req.hasNonNull("requestId")
                                                                        ? req.path("requestId")
                                                                                .asText()
                                                                        : null,
                                                                Set.of())
                                                        : store.db()
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
                                                                                        source,
                                                                                        frozen,
                                                                                        req)),
                                                                        Json.write(guard),
                                                                        Json.write(
                                                                                initialBudget(
                                                                                        frozen.path(
                                                                                                "config"))),
                                                                        Json.write(frozen),
                                                                        Json.write(
                                                                                req.path("inputs")),
                                                                        req.path("mode")
                                                                                .asText("FULL"),
                                                                        req.hasNonNull("requestId")
                                                                                ? req.path(
                                                                                                "requestId")
                                                                                        .asText()
                                                                                : null);
                                        if (protectedAdmission) {
                                            created.set(true);
                                            AuthorityContext.requireBound(admissionProof);
                                        }
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
                                                if (protectedAdmission) {
                                                    store.createNode(
                                                            rid,
                                                            n.path("id").asText(),
                                                            ++seq,
                                                            n.path("type").asText(),
                                                            n,
                                                            admissionProof);
                                                    AuthorityContext.requireBound(admissionProof);
                                                } else
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
                                        } else if (firstChat) {
                                            // The real AGENT and every node scope commit with admission.
                                            store.createNode(rid, null, 1, "AGENT", Json.tree(Map.of("type", "AGENT")), admissionProof);
                                        } else
                                            store.db()
                                                    .update(
                                                            "insert into"
                                                                + " engine.node_instance(run_id,step_seq,node_type,status,definition)"
                                                                + " values(?,1,'AGENT','READY','{\"type\":\"AGENT\"}'::jsonb)",
                                                            rid);
                                        if (protectedAdmission) {
                                            AuthorityContext.requireBound(admissionProof);
                                            requireInitialCoverage(admissionProof);
                                        }
                                        return rid;
                                    });
            long run =
                    admission != null
                            ? acceptedReservation
                                    ? admission.completeReservation(persist)
                                    : admission.persist(persist)
                            : acceptedReservation ? persist.get() : scheduler.persistNew(persist);
            // This is after the outer TransactionTemplate has committed and cleaned up. Failure
            // now preserves PENDING in PG, releases Admission in finally, and sends no stale nudge.
            if (protectedAdmission) AuthorityContext.requireBound(admissionProof);
            var responseMetadata =
                    store.db()
                            .queryForMap(
                                    "select"
                                        + " id,public_id,app_code,trigger_type,mode,tenant_id,principal_id"
                                        + " from engine.run where id=?",
                                    run);
            String publicId = responseMetadata.get("public_id").toString();
            if (protectedAdmission && !created.get())
                return replayPublished(responseMetadata, code, req, trigger, actor);
            // The committed metadata SELECT can wait beyond the proof even though commit passed.
            // Fence the actual notification after that query; durable PENDING remains recoverable.
            if (protectedAdmission) AuthorityContext.requireBound(admissionProof);
            scheduler.created(run, admission);
            if (protectedAdmission) AuthorityContext.requireBound(admissionProof);
            return protectedAdmission
                    ? composeStatus(publicId, actor, store.statusPublished(publicId, actor))
                    : status(publicId, actor);
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
                prompts = new TreeMap<>(),
                promptShas = new TreeMap<>();
        for (JsonNode skill : frozen.path("config").path("tools"))
            skills.put(skill.path("code").asText(), skill.path("skillVersionId"));
        for (JsonNode prompt : frozen.path("config").path("frozenPrompts")) {
            String code = prompt.path("code").asText();
            prompts.put(code, prompt.path("version"));
            // 只留痕校验通过的摘要：关闭兜底开关时库里的无效摘要也不会被写成运行证据。
            String sha = prompt.path("sha256").asText();
            if (PROMPT_SHA_PATTERN.matcher(sha).matches()) {
                Map<String, Object> pinned = new LinkedHashMap<>();
                pinned.put("version", prompt.path("version").asInt());
                pinned.put("sha256", sha);
                promptShas.put(code, pinned);
            }
        }
        refs.put("appVer", frozen.path("versionNo"));
        refs.put("flowDefSha", Digest.sha256(Json.canonical(source.path("definition"))));
        refs.put("promptVers", prompts);
        refs.put("promptShas", promptShas);
        refs.put("skillVers", skills);
        refs.put("modelCfg", frozen.path("config").path("modelBinding"));
        refs.put("interpreterVer", "1.0");
        refs.put("evalReportId", req.path("evalReportId"));
        refs.put("ontology", List.of());
        // 仅新运行入库时捕获本次真实请求关联；读取历史运行时不得用当前 MDC 补写旧事实。
        refs.put("traceId", TraceContextFilter.current());
        if (frozen.has("capabilityBindings")) refs.put("capabilityPackages", frozen.path("capabilityBindings").deepCopy());
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
                clock.instant().plusMillis(limits.path("totalDurationMs").asLong()).toString());
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
        CapabilitySources capability = capabilitySources(snapshot);
        if (capability != null && controlledDraftId != null) throw capabilityConflict();
        var capabilitySelections = new HashMap<String, String>();
        if (iamEnabled()) {
            // Published empty dependency containers carry no external references. Normalize only
            // these known empty fields before the existing preparation code enumerates them.
            for (var node : snapshot.path("definition").path("nodes"))
                for (String key : List.of("skillRef", "promptRef", "modelOverride"))
                    if (!dependency(node.path(key)) && node.isObject())
                        ((com.fasterxml.jackson.databind.node.ObjectNode) node).remove(key);
            if (snapshot.path("config").isObject()
                    && !dependency(snapshot.path("config").path("dataSources")))
                ((com.fasterxml.jackson.databind.node.ObjectNode) snapshot.path("config"))
                        .remove("dataSources");
        }
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
        // PLAT-14 T14.1：工具档案选择。profile 与归属都是声明式数据（config.toolProfile 与
        // 工具引用上的 profiles/enabled），本段没有任何路线/软件名分支；停用工具按 22A schema
        // tools[].enabled 语义不装载。停用优先于档案排除被记录。
        String toolProfile = config.path("toolProfile").asText("").trim();
        List<String> selectedTools = new ArrayList<>();
        List<Map<String, String>> excludedTools = new ArrayList<>();
        boolean memberDeclared = false;
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
                                    "/internal/skills/" + code + "?version=" + version + (capability == null ? "" : "&requireRegistered=true"),
                                    actor);
            boolean controlledDraft =
                    controlledDraftId != null
                            && skill.path("skillVersionId").asLong() == controlledDraftId
                            && skill.path("status").asText().equals("DRAFT");
            if (!controlledDraft
                    && !Set.of("PUBLISHED", "ARCHIVED").contains(skill.path("status").asText()))
                throw new PlatformException(409, "SKILL_NOT_PUBLISHED", "技能尚未发布");
            if (capability != null) validateCapabilitySkill(capability.versions().get(key), skill);
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
            if (capability != null)
                capabilitySelections.put(key, !visible ? "GUARD_DENIED" : !ref.path("enabled").asBoolean(true)
                        ? "DISABLED" : !toolInProfile(ref, toolProfile) ? "PROFILE_NOT_MEMBER" : "SELECTED");
            if (visible) {
                boolean enabled = ref.path("enabled").asBoolean(true);
                boolean member = toolInProfile(ref, toolProfile);
                if (ref.path("profiles").isArray() && !ref.path("profiles").isEmpty())
                    memberDeclared = true;
                if (ref.has("enabled")) memberDeclared = true;
                if (enabled && member) {
                    JsonNode entry =
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
                                                            skill.path("inputSchema")))));
                    // 档案归属声明随工具冻结（config.tools[].profiles），未声明不落键。
                    if (ref.path("profiles").isArray() && !ref.path("profiles").isEmpty())
                        ((com.fasterxml.jackson.databind.node.ObjectNode) entry)
                                .set("profiles", ref.path("profiles").deepCopy());
                    tools.add(entry);
                    selectedTools.add(code);
                } else if (!enabled) {
                    excludedTools.add(Map.of("code", code, "reason", "DISABLED"));
                } else {
                    excludedTools.add(Map.of("code", code, "reason", "PROFILE_NOT_MEMBER"));
                }
            }
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
                // 摘要缺失或形状非法的模板不得进入冻结：冻结后模板正文不可再取，留痕必须自证一致。
                if (promptDigestCheck
                        && !PROMPT_SHA_PATTERN.matcher(template.path("sha256").asText()).matches())
                    throw new PlatformException(
                            409,
                            "PROMPT_TEMPLATE_SHA_INVALID",
                            "提示词模板 " + code + " v" + version + " 摘要缺失或格式非法（需64位小写hex）");
                var pinnedPrompt =
                        (com.fasterxml.jackson.databind.node.ObjectNode) template.deepCopy();
                pinnedPrompt.put("code", code);
                pinnedPrompt.put("version", version);
                prompts.add(pinnedPrompt);
            }
        }
        config.set("frozenPrompts", prompts);
        config.set("tools", tools);
        freezeCapabilities(snapshot, config, capability, capabilitySelections);
        annotateToolSelection(
                snapshot,
                toolProfile,
                selectedTools,
                excludedTools,
                !toolProfile.isBlank() || memberDeclared || !excludedTools.isEmpty());
        applyOntologyContext(config, snapshot, actor);
        snapshot.set("config", config);
        return snapshot;
    }

    private record CapabilitySources(List<JsonNode> packages, Map<String, JsonNode> versions,
            Map<String, com.fasterxml.jackson.databind.node.ArrayNode> origins) {}

    private static PlatformException capabilityConflict() {
        return new PlatformException(409, "CAPABILITY_SNAPSHOT_CONFLICT", "能力包来源、路线或精确技能版本与运行快照不一致");
    }

    private static boolean capabilityText(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank();
    }

    private static boolean capabilityId(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToLong() && node.asLong() > 0;
    }

    private static boolean capabilityVersion(JsonNode node) {
        return capabilityId(node) && node.canConvertToInt();
    }

    /** 来源只来自 app 的受控 config；重建摘要所用的原四字段，不能信任孤立的 mappingSha256 标签。 */
    private static CapabilitySources capabilitySources(JsonNode snapshot) {
        JsonNode config = snapshot.path("config"), raw = config.path("capabilityPackages");
        if (!config.has("capabilityPackages")) return null;
        if (!raw.isArray() || raw.isEmpty()) throw capabilityConflict();
        var packages = new ArrayList<JsonNode>();
        var versions = new LinkedHashMap<String, JsonNode>();
        var origins = new LinkedHashMap<String, com.fasterxml.jackson.databind.node.ArrayNode>();
        var packageIds = new HashSet<Long>();
        var packageVersions = new HashSet<String>();
        var codeVersions = new HashMap<String, Integer>();
        for (JsonNode pkg : raw) {
            if (!pkg.isObject() || !capabilityId(pkg.path("packageVersionId")) || !packageIds.add(pkg.path("packageVersionId").asLong())) throw capabilityConflict();
            for (String key : List.of("packageId", "version", "softwareId", "environment", "routeProfileId"))
                if (!capabilityText(pkg.path(key))) throw capabilityConflict();
            if (!packageVersions.add(pkg.path("packageId").asText() + "\n" + pkg.path("version").asText())
                    || !PROMPT_SHA_PATTERN.matcher(pkg.path("sha256").asText()).matches()
                    || !PROMPT_SHA_PATTERN.matcher(pkg.path("mappingSha256").asText()).matches()
                    || !pkg.hasNonNull("environmentDeclaration") || !pkg.path("bindings").isArray() || pkg.path("bindings").isEmpty()) throw capabilityConflict();
            var mapping = Json.object();
            for (String key : List.of("environment", "environmentDeclaration", "routeProfileId", "bindings")) mapping.set(key, pkg.path(key));
            if (!pkg.path("mappingSha256").asText().equals(Digest.sha256(Json.canonical(mapping)))) throw capabilityConflict();
            var toolIds = new HashSet<String>();
            for (JsonNode binding : pkg.path("bindings")) {
                if (!binding.isObject() || !capabilityText(binding.path("toolId")) || !toolIds.add(binding.path("toolId").asText())
                        || !capabilityText(binding.path("interfaceId")) || !binding.path("routeProfileId").equals(pkg.path("routeProfileId"))
                        || !binding.path("skillCode").asText().matches("[A-Za-z][A-Za-z0-9_-]{0,63}")
                        || !capabilityVersion(binding.path("version")) || !capabilityId(binding.path("skillVersionId"))
                        || !PROMPT_SHA_PATTERN.matcher(binding.path("sha256").asText()).matches()) throw capabilityConflict();
                String code = binding.path("skillCode").asText(), key = code + ":" + binding.path("version").asInt();
                Integer priorVersion = codeVersions.putIfAbsent(code, binding.path("version").asInt());
                if (priorVersion != null && priorVersion != binding.path("version").asInt()) throw capabilityConflict();
                JsonNode previous = versions.putIfAbsent(key, binding);
                if (previous != null && (previous.path("skillVersionId").asLong() != binding.path("skillVersionId").asLong()
                        || !previous.path("sha256").equals(binding.path("sha256")))) throw capabilityConflict();
                var origin = Json.object();
                for (String field : List.of("packageVersionId", "packageId", "softwareId", "softwareVersion", "environment", "environmentDeclaration", "routeProfileId", "mappingSha256"))
                    if (pkg.has(field)) origin.set(field, pkg.path(field).deepCopy());
                origin.set("packageVersion", pkg.path("version").deepCopy()); origin.set("packageSha256", pkg.path("sha256").deepCopy());
                for (String field : List.of("toolId", "interfaceId", "skillCode", "skillVersionId")) origin.set(field, binding.path(field).deepCopy());
                origin.set("skillVersion", binding.path("version").deepCopy()); origin.set("contractDigest", binding.path("sha256").deepCopy());
                origins.computeIfAbsent(key, ignored -> Json.mapper().createArrayNode()).add(origin);
            }
            packages.add(pkg);
        }
        if (!config.path("skillRefs").isArray()) throw capabilityConflict();
        var configured = new HashSet<String>();
        var policies = new HashMap<String, String>();
        var refs = new ArrayList<JsonNode>(); config.path("skillRefs").forEach(refs::add);
        for (JsonNode ref : config.path("skillRefs")) configured.add(ref.path("code").asText() + ":" + ref.path("version").asInt());
        if (!configured.equals(versions.keySet())) throw capabilityConflict();
        for (JsonNode node : snapshot.path("definition").path("nodes")) if (node.has("skillRef")) refs.add(node.path("skillRef"));
        for (JsonNode ref : refs) {
            if (!ref.isObject() || !capabilityVersion(ref.path("version"))) throw capabilityConflict();
            String key = ref.path("code").asText() + ":" + ref.path("version").asInt();
            JsonNode expected = versions.get(key);
            if (expected == null || (ref.has("skillVersionId") && ref.path("skillVersionId").asLong() != expected.path("skillVersionId").asLong())
                    || (ref.has("enabled") && !ref.path("enabled").isBoolean()) || (ref.has("profiles") && !ref.path("profiles").isArray())) throw capabilityConflict();
            var profiles = new TreeSet<String>();
            for (JsonNode profile : ref.path("profiles")) {
                if (!capabilityText(profile)) throw capabilityConflict(); profiles.add(profile.asText());
            }
            String policy = Json.canonical(Map.of("enabled", ref.path("enabled").asBoolean(true), "profiles", profiles));
            String prior = policies.putIfAbsent(key, policy);
            if (prior != null && !prior.equals(policy)) throw capabilityConflict();
        }
        return new CapabilitySources(packages, versions, origins);
    }

    /** 仅有包的首次冻结走 requireRegistered；旧无包历史读取 ARCHIVED 等行为不全局收紧。 */
    private static void validateCapabilitySkill(JsonNode expected, JsonNode skill) {
        if (expected == null || !"PUBLISHED".equals(skill.path("status").asText())
                || !Set.of("PUBLISHED", "DRAFT").contains(skill.path("skillStatus").asText())
                || !skill.path("registered").isBoolean() || !skill.path("registered").asBoolean()
                || !expected.path("skillCode").equals(skill.path("code"))
                || !capabilityVersion(skill.path("version")) || skill.path("version").asInt() != expected.path("version").asInt()
                || !capabilityVersion(skill.path("versionNo")) || skill.path("versionNo").asInt() != expected.path("version").asInt()
                || !capabilityId(skill.path("id")) || !capabilityId(skill.path("skillVersionId"))
                || skill.path("id").asLong() != expected.path("skillVersionId").asLong()
                || skill.path("skillVersionId").asLong() != expected.path("skillVersionId").asLong()
                || !skill.path("sha256").equals(expected.path("sha256")) || !skill.path("digest").equals(expected.path("sha256"))
                || !skill.path("definition").isObject()
                || !Digest.sha256(Json.canonical(skill.path("definition"))).equals(expected.path("sha256").asText())) throw capabilityConflict();
        for (String field : List.of("guard", "execution", "inputSchema", "outputSchema"))
            if (!skill.path(field).equals(skill.path("definition").path(field))) throw capabilityConflict();
    }

    private static void freezeCapabilities(com.fasterxml.jackson.databind.node.ObjectNode snapshot,
            com.fasterxml.jackson.databind.node.ObjectNode config, CapabilitySources sources, Map<String, String> selections) {
        if (sources == null) return;
        var frozen = Json.mapper().createArrayNode();
        for (JsonNode pkg : sources.packages()) {
            var copy = (com.fasterxml.jackson.databind.node.ObjectNode) pkg.deepCopy();
            for (JsonNode binding : copy.path("bindings")) {
                String key = binding.path("skillCode").asText() + ":" + binding.path("version").asInt();
                if (!selections.containsKey(key)) throw capabilityConflict();
                ((com.fasterxml.jackson.databind.node.ObjectNode) binding).put("selection", selections.get(key));
            }
            frozen.add(copy);
        }
        for (JsonNode tool : config.path("tools")) {
            String code = tool.path("code").asText();
            String key = sources.versions().keySet().stream().filter(k -> k.startsWith(code + ":")).findFirst().orElseThrow(RunService::capabilityConflict);
            ((com.fasterxml.jackson.databind.node.ObjectNode) tool).set("capabilityOrigins", sources.origins().get(key).deepCopy());
        }
        snapshot.set("capabilityBindings", frozen);
    }

    /** 新运行与受控回放共用入库前门；回放只查原快照自身，不读当前包/技能目录、不重算历史选择。 */
    private static void validateCapabilitySnapshot(JsonNode snapshot) {
        CapabilitySources sources = capabilitySources(snapshot);
        if (sources == null) {
            if (snapshot.has("capabilityBindings")) throw capabilityConflict();
            for (JsonNode tool : snapshot.path("config").path("tools")) if (tool.has("capabilityOrigins")) throw capabilityConflict();
            return;
        }
        JsonNode frozen = snapshot.path("capabilityBindings");
        if (!frozen.isArray() || frozen.size() != sources.packages().size()) throw capabilityConflict();
        var selections = new HashMap<String, String>();
        for (int p = 0; p < frozen.size(); p++) {
            JsonNode original = sources.packages().get(p), saved = frozen.get(p);
            if (!saved.isObject() || !saved.path("bindings").isArray() || saved.path("bindings").size() != original.path("bindings").size()) throw capabilityConflict();
            var expected = (com.fasterxml.jackson.databind.node.ObjectNode) original.deepCopy();
            for (int b = 0; b < saved.path("bindings").size(); b++) {
                String state = saved.path("bindings").get(b).path("selection").asText();
                if (!Set.of("SELECTED", "GUARD_DENIED", "DISABLED", "PROFILE_NOT_MEMBER").contains(state)) throw capabilityConflict();
                var binding = (com.fasterxml.jackson.databind.node.ObjectNode) expected.path("bindings").get(b);
                binding.put("selection", state);
                String key = binding.path("skillCode").asText() + ":" + binding.path("version").asInt();
                String previous = selections.putIfAbsent(key, state);
                if (previous != null && !previous.equals(state)) throw capabilityConflict();
            }
            if (!Json.canonical(expected).equals(Json.canonical(saved))) throw capabilityConflict();
        }
        var selected = new HashSet<String>();
        for (JsonNode tool : snapshot.path("config").path("tools")) {
            String code = tool.path("code").asText();
            String key = sources.versions().keySet().stream().filter(k -> k.startsWith(code + ":")).findFirst().orElseThrow(RunService::capabilityConflict);
            JsonNode expected = sources.versions().get(key);
            if (!selected.add(key) || !"SELECTED".equals(selections.get(key)) || !capabilityId(tool.path("skillVersionId"))
                    || tool.path("skillVersionId").asLong() != expected.path("skillVersionId").asLong()
                    || !tool.path("contractDigest").equals(expected.path("sha256")) || !tool.path("contract").isObject()
                    || !Digest.sha256(Json.canonical(tool.path("contract"))).equals(expected.path("sha256").asText())
                    || !Json.canonical(tool.path("capabilityOrigins")).equals(Json.canonical(sources.origins().get(key)))) throw capabilityConflict();
            for (String field : List.of("guard", "execution", "inputSchema", "outputSchema"))
                if (!tool.path(field).equals(tool.path("contract").path(field))) throw capabilityConflict();
        }
        for (var entry : selections.entrySet())
            if ("SELECTED".equals(entry.getValue()) != selected.contains(entry.getKey())) throw capabilityConflict();
    }

    private void applyOntologyContext(
            com.fasterxml.jackson.databind.node.ObjectNode config,
            com.fasterxml.jackson.databind.node.ObjectNode snapshot,
            Actor actor) {
        JsonNode declared = config.path("ontology");
        if (declared.isMissingNode() || declared.isNull()) return;
        if (!declared.isObject())
            throw new PlatformException(400, "CONTEXT_PACK_CONFIG_INVALID", "本体上下文配置必须是对象");
        // 空占位对象视为未声明，保持既有无本体应用的冻结结果逐字节不变。
        if (declared.isEmpty()) return;
        String space = declared.path("space").asText("").trim();
        String releaseId = declared.path("releaseId").asText("").trim();
        if (!UUID_PATTERN.matcher(space).matches()
                || (!releaseId.isBlank() && !UUID_PATTERN.matcher(releaseId).matches()))
            throw new PlatformException(
                    400,
                    "CONTEXT_PACK_CONFIG_INVALID",
                    "本体上下文必须声明 space 与 releaseId，且均为 UUID");
        // 只有缺 releaseId 时才解析绑定：显式指定永远优先，且不受回滚开关影响。
        boolean resolveByBinding = releaseId.isBlank();
        String appCode = snapshot.path("appCode").asText("").trim();
        if (resolveByBinding && (!pinResolutionEnabled || appCode.isBlank()))
            throw new PlatformException(
                    400,
                    "CONTEXT_PACK_CONFIG_INVALID",
                    pinResolutionEnabled
                            ? "本体上下文缺少 releaseId，且运行快照没有 appCode，无法解析发布绑定"
                            : "本体上下文必须声明 space 与 releaseId，且均为 UUID");
        if (contextPacks == null)
            throw new PlatformException(503, "CONTEXT_PACK_UNAVAILABLE", "本体上下文暂不可用，请稍后重试");
        String bindingId = null;
        boolean pinResolved = false;
        if (resolveByBinding) {
            try {
                var binding =
                        contextPacks.resolveBinding(
                                actor.tenantId(), appCode, pinEnvironment, actor);
                releaseId = binding.releaseId();
                bindingId = binding.bindingId();
                pinResolved = true;
            } catch (PlatformException e) {
                // 只有"确实没有生效绑定"才跳过：那是应用尚未发布到本环境的事实，不该让运行失败。
                // 网关 404、5xx、连接失败、坏响应在客户端里都归为 503，照常向上抛。
                if (!OntologyContextPacks.BINDING_NOT_FOUND.equals(e.code())) throw e;
                var skipped = snapshot.putObject("contextPack");
                skipped.put("space", space);
                skipped.put("appCode", appCode);
                skipped.put("environment", pinEnvironment);
                skipped.put("pinResolved", false);
                skipped.put("pinFailure", OntologyContextPacks.BINDING_NOT_FOUND);
                skipped.put("reason", "该应用在本环境没有生效的发布绑定，按默认策略跳过本体上下文注入");
                return;
            }
        }
        // 冻结阶段唯一取包点；包文本写进 config 后，assemble 只读不触网。
        var pack = contextPacks.fetch(actor.tenantId(), space, releaseId, actor);
        var rendered = ContextPackText.renderDetailed(pack.body());
        if (!rendered.text().isBlank()) config.put("ontologyContext", rendered.text());
        // 审计只落 snapshot，不存正文；context_pack 列由统一迁移提供，写入路径不在本卡范围内。
        var audit = snapshot.putObject("contextPack");
        audit.put("space", space);
        audit.put("releaseId", releaseId);
        audit.put("sha256", pack.sha256());
        audit.put("fetchedAt", clock.instant().toString());
        audit.put("truncated", rendered.truncated());
        // 解析来的版本必须留痕来源：事后要能回答"这次运行用的是哪个绑定"。
        if (pinResolved) {
            audit.put("pinResolved", true);
            audit.put("bindingId", bindingId == null ? "" : bindingId);
        }
    }

    public void drive(long rid, Actor actor) {
        if (!scheduler.owns(rid)) {
            scheduler.runNow(rid, () -> drive(rid, actor));
            return;
        }
        if (store.protectedRun(rid)) {
            driveOwned(rid);
            return;
        }
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
                    markWaitingExternal(rid, node, error);
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
                    // PLAT-05（契约 §11.1）：显式 retryable=false 的错误不得被自动重试；
                    // 缺省键（旧形态）仍按 semantic 既有判据走。
                    if (error.get("semantic").equals("RETRYABLE_FAILURE")
                            && !explicitNonRetryable(error)
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
        if (store.protectedRun(rid)) throw RunAuthorityStore.denied();
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
        return composeStatus(id, actor, published);
    }

    public static final class ObservedStatus {
        private final Map<String,Object> value;
        private final TraceStore.ObservedView view;
        private ObservedStatus(Map<String,Object> value,TraceStore.ObservedView view){this.value=value;this.view=view;}
        public Map<String,Object> value(){return value;}
        public void requireCurrent(){view.requireCurrent();}
    }
    public ObservedStatus observeStatus(String id,Actor actor){
        var view=store.statusObserved(id,actor);
        var value=composeStatusBody(id,view.run(),view.nodes(),view.events(),true);
        view.requireCurrent();return new ObservedStatus(value,view);
    }
    private Map<String, Object> composeStatus(
            String id, Actor actor, TraceStore.PublishedView published) {
        var run=published==null?store.run(id,actor):published.run();
        long rid=TraceStore.number(run,"id");
        var value=composeStatusBody(id,run,published==null?store.nodes(rid):published.nodes(),published==null?store.events(rid,0):published.events(),published!=null);
        if(published!=null)published.requireCurrent();return value;
    }
    private Map<String,Object> composeStatusBody(String id,Map<String,Object> run,List<Map<String,Object>> nodes,List<Map<String,Object>> events,boolean protectedView){
        long rid=TraceStore.number(run,"id");
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
        result.put("fileRefs", protectedView ? List.of() : artifactRefs(rid, id));
        var error = effectiveError(run, nodes, events);
        result.put("error", error);
        result.put("errorRaw", TraceStore.json(run.get("error")));
        // 发布快照只暴露冻结状态；工具入参/出参属于运行内证词，不能越过发布边界。
        if (!protectedView) result.put("diagnostics", diagnostics(rid, run, nodes, events, error));
        result.put("versionRefs", TraceStore.json(run.get("version_refs")));
        // UIF-A5：执行详情只读画布复用需要本次运行钉定的图定义——来自运行快照
        // （创建即冻结），与节点状态表同源，不存在"画布与记录不一致"的第二事实源。
        var snapshotJson = TraceStore.json(run.get("snapshot"));
        if (!protectedView) result.put("observation", observationView(TraceStore.json(run.get("version_refs")), snapshotJson.path("contextPack")));
        if (snapshotJson.has("definition"))
            result.put("graph", snapshotJson.path("definition"));
        // PLAT-20 T20.1：environment/dataset 标注随冻结快照铺到详情顶层；旧运行无标注时不落键。
        surfaceRuntimeAnnotation(result, snapshotJson);
        // PLAT-14 T14.1：工具档案选择留痕（生效清单/所选档案/排除原因）同规则透出。
        surfaceToolSelection(result, snapshotJson);
        result.put("nodes", nodes.stream().map(this::nodeView).toList());
        result.put("events", events);
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

    // 纯函数：无数据库/时钟依赖，便于直接验证“失败运行必须有根因”的契约。
    // PLAT-05：公开给跨包测试（infra 的运行链路验收）直接断言三处错误表面同构。
    public static JsonNode effectiveError(
            Map<String, Object> run, List<Map<String, Object>> nodes, List<Map<String, Object>> events) {
        var runError = TraceStore.json(run.get("error"));
        if (!runError.isEmpty()) return runError;
        for (var node : nodes) {
            var nodeError = TraceStore.json(node.get("error"));
            if (!nodeError.isEmpty()) return nodeError;
        }
        for (var event : events) {
            String type = String.valueOf(event.get("type"));
            if (!type.equals("MODEL_DISPATCH_FAILED") && !type.equals("NODE_FAILED")) continue;
            var payload = TraceStore.json(event.get("payload"));
            var nested = payload.path("error");
            if (nested.isObject() && !nested.isEmpty()) return nested;
            if (!payload.isEmpty()) return payload;
        }
        if ("FAILED".equals(run.get("status")))
            return Json.tree(
                    Map.of(
                            "semantic",
                            "RUN_FAILED_WITHOUT_ERROR",
                            "message",
                            "运行失败，但引擎没有记录可诊断的错误详情",
                            "reason",
                            "请管理员检查节点事件与工具调用记录"));
        return Json.tree(Map.of());
    }

    private Map<String, Object> diagnostics(
            long rid,
            Map<String, Object> run,
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> events,
            JsonNode error) {
        var attempts = attempts(rid);
        var calls = toolCalls(rid);
        var result = new LinkedHashMap<String, Object>();
        result.put("error", normalizedError(run, nodes, calls, error));
        result.put("attempts", attempts);
        result.put("toolCalls", calls);
        result.put("timeline", events.stream().map(this::eventRow).toList());
        int failedCalls = (int) calls.stream().filter(c -> "FAILED".equals(c.get("status"))).count();
        int retries = calls.stream()
                .mapToInt(c -> ((Number) c.getOrDefault("retryCount", 0)).intValue())
                .sum();
        int failedAttempts =
                (int) attempts.stream().filter(a -> "FAILED".equals(a.get("status"))).count();
        result.put(
                "counts",
                Map.of(
                        "attempts", attempts.size(),
                        "failedAttempts", failedAttempts,
                        "toolCalls", calls.size(),
                        "failedToolCalls", failedCalls,
                        "toolRetries", retries,
                        "events", events.size()));
        return result;
    }

    public static Map<String, Object> normalizedError(
            Map<String, Object> run,
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> calls,
            JsonNode error) {
        // 成功运行也带 diagnostics，不能把空错误渲染成 RUN_FAILED。
        if (error.isEmpty() && !"FAILED".equals(run.get("status"))) return null;
        String semantic = error.path("semantic").asText(error.path("code").asText(""));
        String stage = errorStage(semantic);
        String message =
                error.path("message").asText(
                        error.path("reason").asText(
                                error.path("userMessage").asText(
                                        "运行失败，但没有可读的错误说明")));
        var failedCall = calls.stream()
                .filter(c -> "FAILED".equals(c.get("status")))
                .findFirst();
        if (failedCall.isPresent()) {
            var callResult = TraceStore.json(failedCall.get().get("result"));
            String reason = callResult.path("error").path("reason").asText("");
            if (reason.isBlank()) reason = callResult.path("modelSummary").asText("");
            // 引擎常把工具失败收敛成 RETRYABLE_FAILURE；工具结果里的原因才是可读根因。
            if (!reason.isBlank() && (message.isBlank() || message.equals(semantic)))
                message = reason;
            if ("执行".equals(stage)) stage = "工具调用";
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("code", semantic.isBlank() ? "RUN_FAILED" : semantic);
        result.put("semantic", semantic);
        result.put("stage", stage);
        result.put("message", message);
        // PLAT-05（契约 §11.1）：错误对象自带显式 retryable 时按声明呈现，不得由 semantic
        // 推断覆盖；旧形态无该键仍走语义推断。
        result.put(
                "retryable",
                error.path("retryable").isBoolean()
                        ? error.path("retryable").asBoolean()
                        : errorRetryable(semantic));
        result.put("hint", errorHint(semantic, stage));
        // 统一错误对象的新字段原样透传（转成普通 Java 值，与 map 其余键同型）；
        // 旧形态没有这些键时保持既有渲染，不发明字段值。
        for (String field : List.of("userMessage", "traceId", "category", "detail"))
            if (error.hasNonNull(field))
                result.put(field, Json.mapper().convertValue(error.path(field), Object.class));
        if (error.hasNonNull("code")) result.put("errorCode", error.path("code").asText());
        Object nodeId = error.path("nodeInstanceId").isNumber()
                ? error.path("nodeInstanceId").asLong()
                : nodes.stream()
                        .filter(n -> "FAILED".equals(n.get("status")))
                        .map(n -> n.get("id"))
                        .findFirst()
                        .orElse(null);
        if (nodeId != null) result.put("nodeInstanceId", nodeId);
        Object callId = error.path("callId").isNumber() ? error.path("callId").asLong()
                : calls.stream()
                        .filter(c -> "FAILED".equals(c.get("status")))
                        .map(c -> c.get("id"))
                        .findFirst()
                        .orElse(null);
        if (callId != null) result.put("toolCallId", callId);
        result.put("at", run.get("ended_at") == null ? null : run.get("ended_at"));
        result.put("path", error.path("path").asText(""));
        result.put("raw", error);
        return result;
    }

    private List<Map<String, Object>> attempts(long rid) {
        return store.db()
                .queryForList(
                        "select a.id,a.node_instance_id,a.attempt_no,a.status,a.error,"
                                + "a.started_at,a.ended_at,a.routed_model,"
                                + "greatest(0,extract(epoch from (a.ended_at-a.started_at))*1000)::bigint as duration_ms"
                                + " from engine.attempt a join engine.node_instance n"
                                + " on n.id=a.node_instance_id where n.run_id=? order by a.id",
                        rid)
                .stream()
                .map(
                        row -> {
                            Map<String, Object> value = new LinkedHashMap<>();
                            value.put("id", row.get("id"));
                            value.put("nodeInstanceId", row.get("node_instance_id"));
                            value.put("attemptNo", row.get("attempt_no"));
                            value.put("status", row.get("status"));
                            value.put("error", TraceStore.json(row.get("error")));
                            value.put("routedModel", TraceStore.json(row.get("routed_model")));
                            value.put("startedAt", row.get("started_at"));
                            value.put("endedAt", row.get("ended_at"));
                            value.put("durationMs", row.get("duration_ms"));
                            return value;
                        })
                .toList();
    }

    private List<Map<String, Object>> toolCalls(long rid) {
        return store.db()
                .queryForList(
                        "select t.id,t.node_instance_id,t.attempt_id,t.call_seq,t.skill_code,"
                                + "t.status,t.args,t.result,t.error_semantic,t.duration_ms,t.is_mock,"
                                + "t.readonly_retry_count,t.recovery_error,t.created_at"
                                + " from engine.tool_call t where t.run_id=? order by t.id",
                        rid)
                .stream()
                .map(
                        row -> {
                            Map<String, Object> value = new LinkedHashMap<>();
                            value.put("id", row.get("id"));
                            value.put("nodeInstanceId", row.get("node_instance_id"));
                            value.put("attemptId", row.get("attempt_id"));
                            value.put("callSeq", row.get("call_seq"));
                            value.put("skillCode", row.get("skill_code"));
                            value.put("status", row.get("status"));
                            value.put("args", TraceStore.json(row.get("args")));
                            value.put("result", TraceStore.json(row.get("result")));
                            value.put("errorSemantic", row.get("error_semantic"));
                            value.put("durationMs", row.get("duration_ms"));
                            value.put("isMock", row.get("is_mock"));
                            value.put("retryCount", row.get("readonly_retry_count"));
                            value.put("recoveryError", row.get("recovery_error"));
                            value.put("createdAt", row.get("created_at"));
                            return value;
                        })
                .toList();
    }

    private Map<String, Object> eventRow(Map<String, Object> event) {
        String type = String.valueOf(event.get("type"));
        var row = new LinkedHashMap<String, Object>();
        row.put("seq", event.get("seq"));
        row.put("type", type);
        row.put("label", eventLabel(type));
        row.put("at", event.get("created_at"));
        row.put("severity", type.contains("FAILED") || type.equals("RUN_FINISHED")
                && "FAILED".equals(TraceStore.json(event.get("payload")).path("status").asText())
                ? "error" : type.contains("RETRY") ? "warning" : "info");
        row.put("payload", TraceStore.json(event.get("payload")));
        return row;
    }

    private static String eventLabel(String type) {
        return switch (type) {
            case "RUN_STARTED" -> "运行开始";
            case "NODE_STARTED" -> "节点开始";
            case "MODEL_RETURN_RECORDED" -> "模型返回";
            case "MODEL_DISPATCH_FAILED" -> "模型调用失败";
            case "LOGIC_RESULT_COMMITTED" -> "节点结果提交";
            case "NODE_FINISHED" -> "节点完成";
            case "NODE_FAILED" -> "节点失败";
            case "TOOL_CALL_REGISTERED" -> "工具调用登记";
            case "TOOL_CALL_STARTED" -> "工具执行开始";
            case "TOOL_CALL_FINISHED" -> "工具执行完成";
            case "TOOL_CALL_FAILED" -> "工具执行失败";
            case "RUN_FINISHED" -> "运行结束";
            default -> type.replace('_', ' ');
        };
    }

    private static String errorStage(String semantic) {
        if (semantic.startsWith("MODEL")) return "模型调用";
        if (semantic.startsWith("TOOL")) return "工具调用";
        if (semantic.startsWith("PARAM") || semantic.startsWith("INPUT")) return "输入校验";
        if (semantic.startsWith("APPROVAL")) return "人工确认";
        if (semantic.startsWith("AUTH")) return "鉴权";
        return "执行";
    }

    private static boolean errorRetryable(String semantic) {
        if (semantic.startsWith("PARAM")
                || semantic.startsWith("INPUT")
                || semantic.startsWith("AUTH")
                || semantic.startsWith("APPROVAL")) return false;
        // 供应商返回的 4xx 是请求/凭据本身不合法，自动重试只会重复失败。
        return !(semantic.contains("_400")
                || semantic.contains("_401")
                || semantic.contains("_403")
                || semantic.contains("_422"));
    }

    /**
     * PLAT-05（契约 §11.1）：错误对象显式携带 retryable=false 时，任何自动重试判据都必须
     * 尊重声明（{@code RESULT_UNKNOWN} 恒不可重试、远端声明的确定性失败不重试）。键缺省
     * （旧形态）返回 false，保持既有"按 semantic 推断"的行为不变。
     */
    static boolean explicitNonRetryable(Map<String, Object> error) {
        Object value = error.get("retryable");
        if (value instanceof Boolean retryable) return !retryable;
        return "false".equalsIgnoreCase(Objects.toString(value, ""));
    }

    private static String errorHint(String semantic, String stage) {
        if ("工具调用".equals(stage))
            return "查看工具调用的输入、输出与错误语义；确认外部服务可用后再重试。";
        if (semantic.startsWith("MODEL"))
            return "检查模型供应商地址、凭据、模型名称与工具能力；模型错误不应重试业务写操作。";
        if (semantic.startsWith("PARAM") || semantic.startsWith("INPUT"))
            return "修正输入后重新发起；参数错误重试不会成功。";
        if (semantic.startsWith("APPROVAL"))
            return "人工确认已超时或被拒绝；请重新发起并完成确认。";
        if (semantic.startsWith("AUTH"))
            return "检查登录身份、权限和凭据；鉴权失败不应自动重试。";
        return "查看节点事件与工具调用记录，按错误阶段定位；不要盲目重试写操作。";
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
        if (n.get("started_at") instanceof java.sql.Timestamp start
                && n.get("ended_at") instanceof java.sql.Timestamp end)
            v.put("durationMs", Math.max(0, end.getTime() - start.getTime()));
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
        var targets = store.atomic(() -> {
            int n = store.db().update("update engine.run set status='CANCELLED',stop_reason='USER_CANCEL',ended_at=now()"
                    + " where id=? and status not in ('SUCCEEDED','FAILED','CANCELLED')", rid);
            var result = new ArrayList<Map<String, Object>>();
            if (n > 0) {
                // run 行锁已排除并发 claim/submitted；在改 node 状态前冻结当前代、同 owner 的活跃调用。
                var active = store.db().queryForList("select t.id,t.attempt_id,t.skill_version_id,t.skill_code,t.skill_contract_sha256"
                        + " from engine.tool_call t join engine.node_instance node on node.id=t.node_instance_id and node.run_id=t.run_id"
                        + " join engine.attempt a on a.id=t.attempt_id and a.node_instance_id=node.id"
                        + " where t.run_id=? and t.status in ('EXECUTING','SUBMITTED') and t.is_mock=false"
                        + " and node.status in ('RUNNING','WAITING_EXTERNAL') and a.status='RUNNING'"
                        + " and t.claimed_by=node.lease_owner and node.lease_until>now() and t.claimed_lease>now()"
                        + " and a.attempt_no=(select max(latest.attempt_no) from engine.attempt latest where latest.node_instance_id=node.id) order by t.id", rid);
                for (var call : active) {
                    for (JsonNode tool : TraceStore.json(run.get("snapshot")).path("config").path("tools")) {
                        JsonNode contract = tool.path("contract");
                        if (tool.path("skillVersionId").asLong() == TraceStore.number(call, "skill_version_id")
                                && tool.path("code").asText().equals(call.get("skill_code"))
                                && "PROCESS".equals(contract.path("type").asText())
                                && contract.path("execution").path("cancellable").asBoolean(false)
                                && tool.path("contractDigest").asText().equals(call.get("skill_contract_sha256"))
                                && Digest.sha256(Json.canonical(contract)).equals(tool.path("contractDigest").asText())) {
                            result.add(Map.of("callId", call.get("id"), "attemptId", call.get("attempt_id"), "skillVersionId", call.get("skill_version_id")));
                            break;
                        }
                    }
                }
                store.db().update("update engine.node_instance set status='CANCELLED',ended_at=now() where run_id=?"
                        + " and status not in ('SUCCEEDED','FAILED','SKIPPED','ABANDONED','CANCELLED')", rid);
                store.db().update("update engine.tool_call set status='ABANDONED' where run_id=? and status in ('REGISTERED','EXECUTING')", rid);
                int inFlight = store.db().queryForObject("select count(*) from engine.tool_call where run_id=? and status='SUBMITTED'", Integer.class, rid);
                if (inFlight > 0) store.event(rid, "CANCEL_IN_FLIGHT", Map.of("submittedCalls", inFlight, "externalEffectReverted", false));
                store.event(rid, "RUN_FINISHED", Map.of("status", "CANCELLED"));
            }
            return List.copyOf(result);
        });
        var receipts = new ArrayList<Map<String, Object>>();
        long deadline = System.nanoTime() + 5_000_000_000L;
        for (var target : targets) {
            var receipt = new LinkedHashMap<String, Object>(target);
            receipt.put("receiptReceived", false);
            receipt.put("remoteTerminationConfirmed", false);
            // REQUIRED 可能加入调用者事务；此时不把 lambda 返回冒充提交，不持外层锁发送 HTTP。
            if (TransactionSynchronizationManager.isActualTransactionActive()) receipt.put("reason", "OUTER_TRANSACTION_PENDING");
            else if (System.nanoTime() >= deadline) receipt.put("reason", "DEADLINE_EXCEEDED");
            else {
                try {
                    var response = clients.cancelSkillCall(((Number) target.get("callId")).longValue(),
                            Map.of("runId", id, "attemptId", target.get("attemptId"), "skillVersionId", target.get("skillVersionId")), actor, deadline);
                    receipt.put("receiptReceived", true);
                    receipt.put("localCallMatched", response.path("localCallMatched").asBoolean());
                    receipt.put("cancelRequested", response.path("cancelRequested").asBoolean());
                    receipt.put("reason", response.path("reason").asText());
                } catch (RuntimeException unconfirmed) { receipt.put("reason", "REQUEST_FAILED"); }
            }
            // 收据只是通知结果，不改 run/node/tool 状态；HTTP 失败也不撤销已经提交的取消。
            store.atomic(() -> { store.event(rid, "PROCESS_CANCEL_RECEIPT", receipt); return null; });
            receipts.add(receipt);
        }
        var response = new LinkedHashMap<String, Object>(status(id, actor));
        if (!receipts.isEmpty()) response.put("processCancellation", receipts);
        return response;
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

    /** 运行监控：服务端检索 + 分页，避免管理台只能看到最近 200 条后在前端盲目过滤。 */
    public Map<String, Object> page(Actor actor, Map<String, String> filters) {
        if (!Collections.disjoint(filters.keySet(), Set.of("tenant", "tenantId", "principal", "principalId")))
            throw new PlatformException(400, "INVALID_INPUT", "运行查询范围仅来自当前身份");
        int page = Math.max(1, parsePositive(filters.get("page"), 1));
        int size = Math.min(100, Math.max(1, parsePositive(filters.get("size"), 20)));
        var where = new StringBuilder(
                " from engine.run r where r.tenant_id=? and r.principal_id=?"
                        + " and r.trigger_type in ('CHAT','TASK')");
        var args = new ArrayList<Object>();
        args.add(actor.tenantId());
        args.add(actor.principalId());
        String q = filters.getOrDefault("q", "").trim();
        if (q.length() > 128) throw new PlatformException(400, "INVALID_INPUT", "检索条件过长");
        if (!q.isEmpty()) {
            where.append(" and (position(lower(?) in lower(r.public_id))>0"
                    + " or position(lower(?) in lower(r.app_code))>0)");
            args.add(q);
            args.add(q);
        }
        String status = filters.getOrDefault("status", "").trim();
        if (!status.isEmpty()) {
            if (!Set.of("QUEUED", "RUNNING", "WAITING_APPROVAL", "WAITING_EXTERNAL",
                    "SUCCEEDED", "FAILED", "CANCELLED").contains(status))
                throw new PlatformException(400, "INVALID_INPUT", "运行状态无效");
            where.append(" and r.status=?");
            args.add(status);
        }
        String appCode = filters.getOrDefault("appCode", "").trim();
        if (!appCode.isEmpty()) {
            if (appCode.length() > 128) throw new PlatformException(400, "INVALID_INPUT", "应用编码过长");
            where.append(" and r.app_code=?");
            args.add(appCode);
        }
        appendObservationFilters(where, args, filters);
        String from = filters.getOrDefault("from", "").trim();
        String to = filters.getOrDefault("to", "").trim();
        if (!from.isEmpty()) {
            if (!from.matches("\\d{4}-\\d{2}-\\d{2}([T ].*)?"))
                throw new PlatformException(400, "INVALID_INPUT", "开始时间格式无效");
            where.append(" and r.started_at >= ?::timestamptz");
            args.add(from);
        }
        if (!to.isEmpty()) {
            if (!to.matches("\\d{4}-\\d{2}-\\d{2}([T ].*)?"))
                throw new PlatformException(400, "INVALID_INPUT", "结束时间格式无效");
            where.append(" and r.started_at < (?::date + interval '1 day')");
            args.add(to);
        }
        Long total = store.db().queryForObject("select count(*)" + where, Long.class, args.toArray());
        var pageArgs = new ArrayList<Object>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> items = store.db().queryForList(
                "select r.public_id as \"runId\",r.app_code as \"appCode\",r.trigger_type as"
                    + " \"triggerType\",r.status,r.started_at as \"startedAt\",r.ended_at as"
                    + " \"endedAt\",r.stop_reason as \"stopReason\",r.version_refs as \"_observationRefs\","
                    + "r.snapshot->'contextPack' as \"_observationContext\""
                    + where
                    + " order by r.id desc limit ? offset ?",
                pageArgs.toArray());
        for (var item : items) {
            item.put("statusLabel", runStatusLabel(String.valueOf(item.get("status"))));
            item.put("observation", observationView(TraceStore.json(item.remove("_observationRefs")), TraceStore.json(item.remove("_observationContext"))));
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("items", items);
        result.put("total", total == null ? 0 : total);
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    /** 六维查询只消费本次运行持久事实；包/路线/工具必须在同一个冻结来源元素内对应。 */
    private static void appendObservationFilters(StringBuilder where, List<Object> args, Map<String, String> filters) {
        var selected = new LinkedHashMap<String, String>();
        for (String key : List.of("packageId", "routeProfileId", "toolId", "releaseId", "traceId")) {
            String value = filters.getOrDefault(key, "").trim();
            if (value.length() > 128) throw new PlatformException(400, "INVALID_INPUT", "运行观测条件过长");
            selected.put(key, value);
        }
        if (!selected.get("releaseId").isEmpty()) {
            where.append(" and r.snapshot#>>'{contextPack,releaseId}'=?"); args.add(selected.get("releaseId"));
        }
        if (!selected.get("traceId").isEmpty()) {
            where.append(" and r.version_refs->>'traceId'=?"); args.add(selected.get("traceId"));
        }
        if (selected.get("packageId").isEmpty() && selected.get("routeProfileId").isEmpty() && selected.get("toolId").isEmpty()) return;
        where.append(" and exists(select 1 from jsonb_array_elements(case when jsonb_typeof(r.version_refs->'capabilityPackages')='array'"
                + " then r.version_refs->'capabilityPackages' else '[]'::jsonb end) as pkg(value) where true");
        for (String key : List.of("packageId", "routeProfileId")) if (!selected.get(key).isEmpty()) {
            where.append(" and pkg.value->>'" + key + "'=?"); args.add(selected.get(key));
        }
        if (!selected.get("toolId").isEmpty()) {
            // toolId 不能从技能编码猜测，也不能仅因声明里出现而声称已发生调用；必须有同精确技能的实际 PG 调用。
            where.append(" and exists(select 1 from jsonb_array_elements(case when jsonb_typeof(pkg.value->'bindings')='array'"
                    + " then pkg.value->'bindings' else '[]'::jsonb end) as binding(value) join engine.tool_call observed_call"
                    + " on observed_call.run_id=r.id and observed_call.skill_code=binding.value->>'skillCode'"
                    + " and observed_call.skill_version_id::text=binding.value->>'skillVersionId'"
                    + " where binding.value->>'toolId'=? and binding.value->>'routeProfileId'=pkg.value->>'routeProfileId')");
            args.add(selected.get("toolId"));
        }
        where.append(")");
    }

    /** null 表示历史未记录；冻结来源包含选择结果，不把全部绑定误称为实际调用清单。 */
    private static Map<String, Object> observationView(JsonNode refs, JsonNode contextPack) {
        var result = new LinkedHashMap<String, Object>();
        result.put("traceId", refs.path("traceId").isTextual() ? refs.path("traceId").asText() : null);
        result.put("releaseId", contextPack.path("releaseId").isTextual() ? contextPack.path("releaseId").asText() : null);
        result.put("spaceId", contextPack.path("space").isTextual() ? contextPack.path("space").asText() : null);
        result.put("capabilityPackages", refs.path("capabilityPackages").isArray() ? refs.path("capabilityPackages").deepCopy() : null);
        return result;
    }

    private static int parsePositive(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException failure) {
            throw new PlatformException(400, "INVALID_INPUT", "分页参数无效");
        }
    }

    private static String runStatusLabel(String status) {
        return switch (status) {
            case "QUEUED" -> "排队中";
            case "RUNNING" -> "运行中";
            case "WAITING_APPROVAL" -> "等待确认";
            case "WAITING_EXTERNAL" -> "等待外部";
            case "SUCCEEDED" -> "成功";
            case "FAILED" -> "失败";
            case "CANCELLED" -> "已取消";
            default -> status;
        };
    }

    public void recover() {
        scheduler.poll();
    }

    private void driveRecovered(long id) {
        if (store.protectedRun(id)) {
            driveOwned(id);
            return;
        }
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

    private void driveOwned(long id) {
        if (runAuthority == null) throw RunAuthorityStore.denied();
        var dispatch = scheduler.ownedDispatch(id);
        String type = store.ownedApplicationType(dispatch, runAuthority.currentRetained(id));
        if (type.equals("FLOW")) driveOwnedFlow(id);
        else {
            if (initialMaterials == null) throw RunAuthorityStore.denied();
            driveOwnedInitial(id);
        }
    }

    private void driveOwnedInitial(long id) {
        var dispatch = scheduler.ownedDispatch(id);
        try {
            if (!store.hasInitialPreparation(dispatch, runAuthority.currentRetained(id))) {
                String lifecycle = store.ownedInitialTrigger(dispatch, runAuthority.currentRetained(id));
                if (lifecycle.equals("CHAT")) initialMaterials.prepareFirstChat(id);
                else initialMaterials.prepareInitial(id);
            }
            var proof = runAuthority.currentRetained(id);
            var manifest = initialMaterials.preparedInitial(id, proof);
            byte[] bytes = initialMaterials.initialRequestUtf8(id, manifest, proof);
            var claim = store.dispatchInitial(dispatch, owner, manifest, bytes, proof);
            long node = TraceStore.number(claim, "id");
            activeNodes.add(node);
            try {
                new ModelExchange(store, clients).exchangeInitial(dispatch, claim, manifest, bytes,
                        initialMaterials, runAuthority, text -> initialChunks.publish(id, text));
                store.commitInitial(dispatch, claim, runAuthority.currentRetained(id));
            } finally { activeNodes.remove(node); }
        } catch (RuntimeException failure) {
            // Durable marker without response remains unknown, including lost commit acknowledgement.
            scheduler.deferOwned(id, java.time.Duration.ofSeconds(3));
            throw failure;
        }
    }

    private void driveOwnedFlow(long id) {
        var dispatch = scheduler.ownedDispatch(id);
        try {
            if (runAuthority == null) throw RunAuthorityStore.denied();
            var start = runAuthority.currentRetained(id);
            var nodes = store.startOwnedFlow(dispatch, start);
            for (long pending : nodes) {
                if (!scheduler.mayAdvance(id)) return;
                var proof = runAuthority.currentRetained(id);
                var work = store.prepareAndClaimFlow(pending, owner, dispatch, proof);
                if (work == null) continue;
                if (work.budgetExhausted() != null) {
                    store.stopFlowForBudget(dispatch, proof, work.budgetExhausted());
                    return;
                }
                var node = work.node();
                long attempt = TraceStore.number(node, "attemptId");
                String leaseOwner = node.get("lease_owner").toString();
                JsonNode output = null;
                Map<String, Object> error = null;
                activeNodes.add(pending);
                try {
                    if (!scheduler.mayAdvance(id)) return;
                    // Only execution faults enter business error policy. All transaction commits,
                    // proof/dispatch checks and infrastructure failures remain outside this catch.
                    try {
                        output = flow.executeFlow(work, dispatch, proof);
                    } catch (RuntimeFault fault) {
                        error = fault.error();
                    } catch (IllegalArgumentException failure) {
                        error =
                                Map.of(
                                        "semantic",
                                        "RUNTIME_ERROR",
                                        "message",
                                        Objects.toString(failure.getMessage(), "执行失败"));
                    }
                    proof.requireVerified();
                    if (error == null)
                        store.commitFlow(pending, attempt, leaseOwner, output, dispatch, proof);
                    else {
                        var policy = TraceStore.json(node.get("definition")).path("errorPolicy");
                        if (policy.path("onFail").asText().equals("DEFAULT_VALUE"))
                            store.commitDefaultFlow(
                                    pending,
                                    attempt,
                                    leaseOwner,
                                    policy.path("defaultValue"),
                                    error,
                                    dispatch,
                                    proof);
                        else store.failFlow(pending, attempt, leaseOwner, error, dispatch, proof);
                    }
                } finally {
                    activeNodes.remove(pending);
                }
            }
            // Aggregate is a new action, with its own current obtained outside the owned RR.
            store.aggregateFlow(dispatch, runAuthority.currentRetained(id));
        } catch (RuntimeException failure) {
            scheduler.deferOwned(id, java.time.Duration.ofSeconds(3));
            throw failure;
        }
    }

    /**
     * PLAT-04：把一次 RESULT_UNKNOWN 故障落成可观测的等待态。
     *
     * <p>除了节点与执行代次，还把最近确认过的外部任务快照写进 run.error，使界面在
     * "结果未知"之外能看到远端任务 id、状态与最后轮询时刻；没有快照时保持旧结构。
     *
     * <p>恢复窗口：run.error 只在等待期间承载该快照，采纳终态（ExecutionRecovery.adopt）
     * 或回调唤醒（resumeExternalFacts）在同一 CAS 里清空。公开为恢复通道与验收测试的稳定
     * 入口，语义与故障分支完全一致。
     */
    public void markWaitingExternal(long rid, Map<String, Object> node, Map<String, Object> error) {
        store.atomic(
                () -> {
                    store.lockRun(rid);
                    // PLAT-04：等待外部任务时不再只写裸的 RESULT_UNKNOWN。
                    // 快照来自 /fact 与对账回执，缺失时保持旧结构，绝不因此改变等待或终结判定。
                    Map<String, Object> waiting = externalWaitingError(node, error);
                    int changed =
                            store.db()
                                    .update(
                                            "update engine.node_instance set"
                                                + " status='WAITING_EXTERNAL',error=?::jsonb"
                                                + " where id=? and status='RUNNING' and"
                                                + " lease_owner=? and"
                                                + " lease_until>clock_timestamp()",
                                            Json.write(waiting),
                                            node.get("id"),
                                            node.get("lease_owner"));
                    if (changed > 0) {
                        store.db()
                                .update(
                                        "update engine.attempt set"
                                            + " status='UNKNOWN',error=?::jsonb where"
                                            + " id=?",
                                        Json.write(waiting),
                                        node.get("attemptId"));
                        // 故障发生时 run 仍是 RUNNING，WAITING_EXTERNAL 由调度器随后推导，
                        // 因此这里两种状态都允许承载快照；终态运行绝不回写。
                        store.db()
                                .update(
                                        "update engine.run set error=?::jsonb where"
                                            + " id=? and status in"
                                            + " ('RUNNING','WAITING_EXTERNAL')",
                                        Json.write(waiting),
                                        rid);
                        store.event(
                                rid,
                                "WAITING_EXTERNAL",
                                Map.of(
                                        "nodeInstanceId",
                                        node.get("id"),
                                        "error",
                                        waiting));
                    }
                    return null;
                });
    }

    /**
     * PLAT-04：把最近的外部任务快照并入 RESULT_UNKNOWN 错误结构。
     *
     * <p>恢复窗口：快照由技能事实投递与对账回执写入，本方法只读最近一条；读到之前仍返回
     * 旧结构，等待窗口的判定与超时完全不受影响。
     */
    private Map<String, Object> externalWaitingError(
            Map<String, Object> node, Map<String, Object> error) {
        var rows =
                store.db()
                        .queryForList(
                                "select external_job from engine.tool_call where"
                                    + " node_instance_id=? and external_job is not null"
                                    + " order by id desc limit 1",
                                node.get("id"));
        if (rows.isEmpty()) return error;
        Object job = rows.getFirst().get("external_job");
        if (job == null) return error;
        var enriched = new java.util.LinkedHashMap<String, Object>(error);
        enriched.put("externalJob", Json.read(job.toString(), Map.class));
        enriched.put("message", "外部任务未终结，等待远端终态");
        return enriched;
    }

    public void resumeExternalFacts() {
        // W3 (§31.2): consume durable successful/failed execution facts without repeating the
        // skill.
        for (var node :
                store.db()
                        .queryForList(
                                "select n.id,n.run_id from engine.node_instance n join engine.run r"
                                    + " on r.id=n.run_id where n.status='WAITING_EXTERNAL' and"
                                    + " r.status in ('RUNNING','WAITING_EXTERNAL') and not"
                                    + " exists(select 1 from engine.run_authority a where"
                                    + " a.run_id=r.id) and exists(select 1 from engine.tool_call t"
                                    + " where t.node_instance_id=n.id) and not exists(select 1 from"
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
                                            "update engine.run set status='RUNNING',error=null where"
                                            + " id=? and status='WAITING_EXTERNAL'",
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
                                                        + " and lease_until>clock_timestamp() for"
                                                        + " update skip locked) update"
                                                        + " engine.node_instance n set"
                                                        + " lease_until=clock_timestamp()+interval"
                                                        + " '90 seconds' from renewable r where"
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
