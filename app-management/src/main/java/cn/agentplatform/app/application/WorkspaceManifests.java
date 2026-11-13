package cn.agentplatform.app.application;

import cn.agentplatform.app.infra.ConfigWriteLog;
import cn.agentplatform.app.infra.WorkspaceManifestRepository;
import cn.agentplatform.app.infra.WorkspaceManifestRepository.Head;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 工作区 manifest（A3-1，app V122）的编辑面：读取头修订 / CAS 保存新修订 / 版本列表。
 *
 * <p>语义（接口稿 §5.5、主方案 §6.6、契约 contracts/manifest.schema.json）：PUT 请求
 * {manifest, baseRevision, requestId}——baseRevision 是调用方所见头修订（无 manifest 时为 0），
 * 与服务端当前头修订不一致即 409 MANIFEST_REVISION_CONFLICT（乐观并发，更新丢失由客户端重读解决）；
 * manifest 校验对齐冻结 Schema 的五个必键及其必填子字段（校验失败聚合 issues 并 400 拒绝，
 * 不落任何修订）；digest=规范化 JSON 的内容寻址 sha256，命中既有摘要时不追加新修订（同内容
 * 不重复出版本，库层 UNIQUE 兜底并发双写）。修订链不可变：保存永远追加，不存在原地改写。
 */
@Service
public class WorkspaceManifests {

    static final Set<String> PUT_KEYS = Set.of("requestId", "manifest", "baseRevision");
    /** manifest jsonb ≤1MB（库稿 §4.6，V122 库层 CHECK 同值兜底）；这里提前给出可读错误。 */
    private static final int MAX_MANIFEST_BYTES = 1048576;
    private static final Set<String> ROLE_NAMES =
        Set.of("ADMIN", "BUILDER", "APPROVER", "OPERATOR", "USER");
    private static final String REF_PATTERN = "^[A-Za-z0-9][A-Za-z0-9_-]*@[0-9]+$";

    private final WorkspaceManifestRepository store;
    private final ConfigWriteLog writes;

    public WorkspaceManifests(WorkspaceManifestRepository store, ConfigWriteLog writes) {
        this.store = store;
        this.writes = writes;
    }

    // ---------- 查询 ----------

    public Map<String, Object> get(Actor actor, String appCode) {
        requireApp(actor, appCode);
        Head head = head(actor, appCode);
        return view(appCode, head);
    }

    public Map<String, Object> versions(Actor actor, String appCode) {
        requireApp(actor, appCode);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("appCode", appCode);
        out.put("revisions", store.versions(actor, appCode));
        return out;
    }

    // ---------- 写入 ----------

    @SuppressWarnings("unchecked")
    public Map<String, Object> put(Actor actor, String appCode, Map<String, Object> body) {
        if (body == null) {
            throw invalid("manifest 保存请求体不能为空");
        }
        for (String key : body.keySet()) {
            if (!PUT_KEYS.contains(key)) {
                throw invalid("manifest 保存请求包含未知字段: " + key);
            }
        }
        String requestId = ConnectionProfiles.requestId(body);
        if (!(body.get("manifest") instanceof Map<?, ?> raw) || raw.isEmpty()) {
            throw invalid("缺少或无效字段: manifest（必须是非空 JSON 对象）");
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        raw.forEach((k, v) -> manifest.put(String.valueOf(k), v));
        String requestSha = Digest.sha256(Json.canonical(
            List.of("manifest.put", appCode, manifest, baseRevision(body))));
        Map<String, Object> replayed = writes.replay(actor.tenantId(), "manifest.put", appCode,
            requestId, requestSha);
        if (replayed != null) {
            replayed.put("replayed", true);
            return replayed;
        }
        // 校验在回放之后、写入之前：已成功的保存按上次响应回放，不因契约后续加严而改写历史语义。
        List<Map<String, Object>> issues = validate(manifest);
        if (!issues.isEmpty()) {
            throw new PlatformException(400, "MANIFEST_INVALID", "manifest 校验未通过: "
                + issues.stream().map(i -> i.get("path") + " " + i.get("message"))
                    .reduce((a, b) -> a + "; " + b).orElse(""));
        }
        if (Json.write(manifest).getBytes(StandardCharsets.UTF_8).length > MAX_MANIFEST_BYTES) {
            throw invalid("manifest 超过 1MB 存储预算（库稿 §4.6 octet_length<=1MB）");
        }
        requireApp(actor, appCode);
        String digest = Digest.sha256(Json.canonical(manifest));
        int headRevision = store.head(actor, appCode).map(Head::revision).orElse(0);
        int baseRevision = baseRevision(body);
        if (baseRevision != headRevision) {
            throw new PlatformException(409, "MANIFEST_REVISION_CONFLICT",
                "baseRevision 已过期: 期望 " + headRevision + "，请求携带 " + baseRevision
                    + "，请重新读取头修订后重试: " + appCode);
        }
        // 同内容不重复出版本：内容寻址命中既有修订即原样返回（revision 不推进）。
        var existing = store.byDigest(actor, appCode, digest);
        Map<String, Object> out;
        if (existing.isPresent()) {
            out = response(appCode, existing.get().revision(), digest, true);
        } else {
            int revision = headRevision + 1;
            store.insert(actor, appCode, revision, Json.write(manifest), digest);
            out = response(appCode, revision, digest, false);
        }
        writes.remember(actor.tenantId(), "manifest.put", appCode, requestId, requestSha, out);
        return out;
    }

    // ---------- 内部 ----------

    /** baseRevision 是乐观并发凭据：0=尚无 manifest（首次保存），≥1=调用方所见头修订。 */
    private static int baseRevision(Map<String, Object> body) {
        Object raw = body.get("baseRevision");
        if (!(raw instanceof Number n) || n.intValue() < 0 || n.intValue() != n.doubleValue()) {
            throw invalid("缺少或无效字段: baseRevision（必须为不小于 0 的整数，首次保存传 0）");
        }
        return n.intValue();
    }

    private Map<String, Object> view(String appCode, Head head) {
        Map<String, Object> out = response(appCode, head.revision(), head.digest(), false);
        out.put("manifest", Json.read(head.manifestJson(), Map.class));
        out.put("createdBy", head.createdBy());
        out.put("createdAt", head.createdAt().toString());
        return out;
    }

    private static Map<String, Object> response(String appCode, int revision, String digest,
            boolean deduplicated) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("appCode", appCode);
        out.put("revision", revision);
        out.put("digest", digest);
        out.put("validation", Map.of("ok", true, "issues", List.of()));
        out.put("deduplicated", deduplicated);
        return out;
    }

    private Head head(Actor actor, String appCode) {
        return store.head(actor, appCode).orElseThrow(() -> new PlatformException(404,
            "MANIFEST_NOT_FOUND", "应用还没有工作区 manifest: " + appCode));
    }

    private void requireApp(Actor actor, String appCode) {
        if (!store.appExists(actor, appCode)) {
            throw new PlatformException(404, "CONFIG_REF_NOT_FOUND", "应用不存在: " + appCode);
        }
    }

    private static PlatformException invalid(String message) {
        return new PlatformException(400, "MANIFEST_INVALID", message);
    }

    /**
     * 对齐冻结契约 contracts/manifest.schema.json：五个必键 + 各键必填子字段 + 出现即校形。
     * 聚合全部 issues 而不是只报首个（与预检聚合口径一致）；未知顶层键不拒绝（契约
     * additionalProperties=true，扩展字段须先修订契约再使用）。
     */
    static List<Map<String, Object>> validate(Map<String, Object> manifest) {
        List<Map<String, Object>> issues = new ArrayList<>();
        for (String key : List.of("metadata", "model", "skills", "contextPolicy", "permission")) {
            if (!(manifest.get(key) instanceof Map<?, ?>)) {
                issues.add(issue("/" + key, "MANIFEST_KEY_REQUIRED", "缺少必填对象: " + key));
            }
        }
        metadata(manifest, issues);
        model(manifest, issues);
        skills(manifest, issues);
        contextPolicy(manifest, issues);
        permission(manifest, issues);
        optional(manifest, issues);
        return issues;
    }

    private static void metadata(Map<String, Object> manifest, List<Map<String, Object>> issues) {
        if (!(manifest.get("metadata") instanceof Map<?, ?> metadata)) {
            return;
        }
        Object name = metadata.get("name");
        if (!(name instanceof String text) || text.isBlank() || text.length() > 128) {
            issues.add(issue("/metadata/name", "MANIFEST_FIELD_REQUIRED",
                "metadata.name 必须是不超过 128 字的非空字符串"));
        }
        if (metadata.containsKey("category")) {
            Object category = metadata.get("category");
            if (!(category instanceof String text) || text.isBlank() || text.length() > 64) {
                issues.add(issue("/metadata/category", "MANIFEST_FIELD_INVALID",
                    "metadata.category 必须是不超过 64 字的字符串"));
            }
        }
        if (metadata.get("tags") instanceof List<?> tags) {
            for (Object tag : tags) {
                if (!(tag instanceof String text) || text.isBlank() || text.length() > 64) {
                    issues.add(issue("/metadata/tags", "MANIFEST_FIELD_INVALID",
                        "metadata.tags 条目必须是不超过 64 字的非空字符串"));
                    break;
                }
            }
        }
    }

    private static void model(Map<String, Object> manifest, List<Map<String, Object>> issues) {
        if (!(manifest.get("model") instanceof Map<?, ?> model)) {
            return;
        }
        if (!(model.get("defaultBinding") instanceof Map<?, ?> binding)) {
            issues.add(issue("/model/defaultBinding", "MANIFEST_FIELD_REQUIRED",
                "model.defaultBinding 必须是对象"));
            return;
        }
        requireText(binding, "providerCode", 64, "/model/defaultBinding/providerCode", issues);
        requireText(binding, "modelName", 128, "/model/defaultBinding/modelName", issues);
        if (binding.get("promptTemplateRef") instanceof Map<?, ?> ref) {
            requireText(ref, "code", 64, "/model/defaultBinding/promptTemplateRef/code", issues);
            if (!(ref.get("version") instanceof Number n) || n.intValue() < 1
                || n.intValue() != n.doubleValue()) {
                issues.add(issue("/model/defaultBinding/promptTemplateRef/version",
                    "MANIFEST_FIELD_INVALID", "promptTemplateRef.version 必须是正整数"));
            }
        }
    }

    private static void skills(Map<String, Object> manifest, List<Map<String, Object>> issues) {
        if (!(manifest.get("skills") instanceof Map<?, ?> skills)) {
            return;
        }
        if (!(skills.get("guideRefs") instanceof List<?> guideRefs) || guideRefs.isEmpty()) {
            issues.add(issue("/skills/guideRefs", "MANIFEST_FIELD_REQUIRED",
                "skills.guideRefs 必须是非空数组（指导链有序引用 code@version）"));
        } else {
            patternRefs(guideRefs, "/skills/guideRefs", issues);
        }
        if (!(skills.get("toolGrants") instanceof List<?> toolGrants)) {
            issues.add(issue("/skills/toolGrants", "MANIFEST_FIELD_REQUIRED",
                "skills.toolGrants 必须是数组"));
            return;
        }
        for (Object item : toolGrants) {
            if (!(item instanceof Map<?, ?> grant)) {
                issues.add(issue("/skills/toolGrants", "MANIFEST_FIELD_INVALID",
                    "toolGrants 条目必须是 {skillCode, version} 对象"));
                continue;
            }
            requireText(grant, "skillCode", 64, "/skills/toolGrants/skillCode", issues);
            if (!(grant.get("version") instanceof Number n) || n.intValue() < 1
                || n.intValue() != n.doubleValue()) {
                issues.add(issue("/skills/toolGrants/version", "MANIFEST_FIELD_INVALID",
                    "toolGrants.version 必须是正整数"));
            }
        }
    }

    private static void contextPolicy(Map<String, Object> manifest,
            List<Map<String, Object>> issues) {
        if (!(manifest.get("contextPolicy") instanceof Map<?, ?> policy)) {
            return;
        }
        for (String key : List.of("maxTurns", "contextBudget")) {
            if (!(policy.get(key) instanceof Number n) || n.intValue() < 1
                || n.intValue() != n.doubleValue()) {
                issues.add(issue("/contextPolicy/" + key, "MANIFEST_FIELD_REQUIRED",
                    "contextPolicy." + key + " 必须是不小于 1 的整数"));
            }
        }
        if (policy.get("compactionThreshold") instanceof Number n
            && (n.doubleValue() <= 0 || n.doubleValue() > 1)) {
            issues.add(issue("/contextPolicy/compactionThreshold", "MANIFEST_FIELD_INVALID",
                "compactionThreshold 必须在 (0,1] 区间"));
        }
    }

    private static void permission(Map<String, Object> manifest, List<Map<String, Object>> issues) {
        if (!(manifest.get("permission") instanceof Map<?, ?> permission)) {
            return;
        }
        if (!(permission.get("roles") instanceof List<?> roles) || roles.isEmpty()) {
            issues.add(issue("/permission/roles", "MANIFEST_FIELD_REQUIRED",
                "permission.roles 必须是非空数组（发布授予角色）"));
            return;
        }
        for (Object role : roles) {
            if (!(role instanceof String text) || !ROLE_NAMES.contains(text)) {
                issues.add(issue("/permission/roles", "MANIFEST_FIELD_INVALID",
                    "permission.roles 条目只允许 " + ROLE_NAMES + ": " + role));
            }
        }
    }

    /** 可选键出现即校形（契约 properties 里已钉形状的部分）。 */
    private static void optional(Map<String, Object> manifest, List<Map<String, Object>> issues) {
        if (manifest.get("rules") instanceof List<?> rules) {
            patternRefs(rules, "/rules", issues);
        }
        if (manifest.get("plugins") instanceof List<?> plugins) {
            for (Object item : plugins) {
                if (!(item instanceof Map<?, ?> plugin)) {
                    issues.add(issue("/plugins", "MANIFEST_FIELD_INVALID",
                        "plugins 条目必须是 {pluginCode, version, capabilities} 对象"));
                    continue;
                }
                requireText(plugin, "pluginCode", 64, "/plugins/pluginCode", issues);
                requireText(plugin, "version", 64, "/plugins/version", issues);
            }
        }
        if (manifest.containsKey("flowRef")
            && (!(manifest.get("flowRef") instanceof String flowRef) || flowRef.length() > 160)) {
            issues.add(issue("/flowRef", "MANIFEST_FIELD_INVALID",
                "flowRef 必须是不超过 160 字的字符串"));
        }
    }

    private static void patternRefs(List<?> refs, String path,
            List<Map<String, Object>> issues) {
        for (Object ref : refs) {
            if (!(ref instanceof String text) || !text.matches(REF_PATTERN)) {
                issues.add(issue(path, "MANIFEST_FIELD_INVALID",
                    "引用必须是 code@version 形状（code 以字母数字开头）: " + ref));
            }
        }
    }

    private static void requireText(Map<?, ?> owner, String key, int max, String path,
            List<Map<String, Object>> issues) {
        Object value = owner.get(key);
        if (!(value instanceof String text) || text.isBlank() || text.length() > max) {
            issues.add(issue(path, "MANIFEST_FIELD_REQUIRED",
                path.substring(path.lastIndexOf('/') + 1) + " 必须是不超过 " + max + " 字的非空字符串"));
        }
    }

    private static Map<String, Object> issue(String path, String code, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path);
        out.put("code", code);
        out.put("message", message);
        return out;
    }
}
