package cn.agentplatform.engine.application;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.domain.*;
import cn.agentplatform.engine.infra.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.*;

/**
 * 运行快照准备协作对象（MNT-05 子步1）：固定快照准备、候选验证与版本引用。
 *
 * <p>本类只做准备：不写库、不开事务、不改变调度与锁顺序；网络只发生在技能/提示词/发布绑定
 * 冻结读取，与抽取前 RunService 的调用序列逐次一致。RunService 保留同名兼容委托作为受理门面。
 */
@Service
public class RunSnapshotService {
    private static final java.util.regex.Pattern UUID_PATTERN =
            java.util.regex.Pattern.compile(
                    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    /** 模板摘要形状：只有校验通过的摘要才允许进入不可变运行的引用记录。 */
    private static final java.util.regex.Pattern PROMPT_SHA_PATTERN =
            java.util.regex.Pattern.compile("[0-9a-f]{64}");

    private final RuntimeClients clients;
    private final Clock clock;
    /**
     * 可选装配：未配置本体地址的环境不注入。冻结阶段是唯一取包点，之后 assemble 只读文本。
     */
    private OntologyContextPacks contextPacks;
    private boolean promptDigestCheck = true;
    /**
     * 运行期发布绑定解析开关与环境。默认开启、production：关闭即退回"只有显式 releaseId 才注入"
     * 的旧行为，作为本卡唯一的运行时回滚面；环境决定查询哪份绑定。
     */
    private boolean pinResolutionEnabled = true;
    private String pinEnvironment = "production";

    @Autowired
    public RunSnapshotService(RuntimeClients clients, ObjectProvider<Clock> clocks) {
        this.clients = clients;
        this.clock = clocks.getIfAvailable(Clock::systemUTC);
    }

    /** 受控测试装配：显式时钟，不依赖容器。 */
    RunSnapshotService(RuntimeClients clients, Clock clock) {
        this.clients = clients;
        this.clock = Objects.requireNonNull(clock);
    }

    boolean iamEnabled() {
        return clients != null && clients.iamEnabled();
    }

    @Autowired(required = false)
    public void contextPacks(OntologyContextPacks packs) {
        this.contextPacks = packs;
    }

    @Autowired
    public void promptDigestCheck(@Value("${agent.prompt.digest-check:true}") boolean enabled) {
        this.promptDigestCheck = enabled;
    }

    @Autowired
    public void pinResolutionEnabled(
            @Value("${agent.pin.resolution-enabled:true}") boolean enabled) {
        this.pinResolutionEnabled = enabled;
    }

    @Autowired
    public void pinEnvironment(@Value("${agent.pin.environment:production}") String environment) {
        this.pinEnvironment =
                environment == null || environment.isBlank() ? "production" : environment.trim();
    }

    JsonNode verifyCandidate(JsonNode snapshot, JsonNode req, String code) {
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
    Object versionRefs(JsonNode source, JsonNode frozen, JsonNode req) {
        Map<String, Object> refs = new LinkedHashMap<>(),
                skills = new TreeMap<>(),
                prompts = new TreeMap<>(),
                promptShas = new TreeMap<>();
        for (JsonNode skill : frozen.path("config").path("tools"))
            skills.put(skill.path("code").asText(), skill.path("skillVersionId"));
        JsonNode recordedPrompts = frozen.path("config").path("frozenPrompts");
        if (!recordedPrompts.isArray() && "TEMPLATE".equals(source.path("config").path("promptSource").asText()))
            recordedPrompts = InitialChatRules.promptConfig(source).path("frozenPrompts");
        for (JsonNode prompt : recordedPrompts) {
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
    void freezeModelRefs(JsonNode snapshot, String trigger, Actor actor) {
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
    JsonNode prepareSnapshot(JsonNode original, Actor actor) {
        return prepareSnapshot(original, actor, null);
    }

    JsonNode prepareSnapshot(JsonNode original, Actor actor, Long controlledDraftId) {
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
                        ? "DISABLED" : !RunService.toolInProfile(ref, toolProfile) ? "PROFILE_NOT_MEMBER" : "SELECTED");
            if (visible) {
                boolean enabled = ref.path("enabled").asBoolean(true);
                boolean member = RunService.toolInProfile(ref, toolProfile);
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
        JsonNode primaryPrompt = "TEMPLATE".equals(config.path("promptSource").asText())
                ? InitialChatRules.promptConfig(original).path("frozenPrompts").get(0) : null;
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
                JsonNode template = primaryPrompt != null
                                && code.equals(primaryPrompt.path("code").asText())
                                && version == primaryPrompt.path("version").asInt()
                        ? primaryPrompt
                        : clients.get(
                                "app",
                                "/internal/prompt-templates/" + code + "/versions/" + version,
                                actor);
                if (ref.has("sha256") && !ref.path("sha256").equals(template.path("sha256")))
                    throw new PlatformException(409, "PROMPT_TEMPLATE_SNAPSHOT_MISMATCH", "模板版本摘要与冻结引用不一致");
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
        RunService.annotateToolSelection(
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
            String key = sources.versions().keySet().stream().filter(k -> k.startsWith(code + ":")).findFirst().orElseThrow(RunSnapshotService::capabilityConflict);
            ((com.fasterxml.jackson.databind.node.ObjectNode) tool).set("capabilityOrigins", sources.origins().get(key).deepCopy());
        }
        snapshot.set("capabilityBindings", frozen);
    }

    /** 新运行与受控回放共用入库前门；回放只查原快照自身，不读当前包/技能目录、不重算历史选择。 */
    static void validateCapabilitySnapshot(JsonNode snapshot) {
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
            String key = sources.versions().keySet().stream().filter(k -> k.startsWith(code + ":")).findFirst().orElseThrow(RunSnapshotService::capabilityConflict);
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

    private static boolean dependency(JsonNode value) {
        return !value.isMissingNode()
                && !value.isNull()
                && (!value.isContainerNode() || !value.isEmpty());
    }
}
