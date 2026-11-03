package cn.agentplatform.app.domain;

import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 语义绑定修订的声明形状（SEM-02.2 阶段1）。
 *
 * <p>绑定只记录"应用 -> 某个 READY release 上已批准的语义扩展 -> 技能精确版本"的引用，
 * 不复制本体或技能内容；这里只做本地形状校验，跨系统的精确性由阶段2的 ConfigRelease
 * 组合校验负责。四个引用摘要（release contentDigest / extensionDigest / skillSha256）
 * 都是声明的一部分：任一变化都必须产生新修订，旧修订不可原地改写。
 *
 * <p>缺省的可选引用写 null（未交付），不写空串：空串会被下游误读为"已声明但内容为空"。
 */
public record SemanticBinding(
        String bindingId,
        ReleaseRef releaseRef,
        String extensionId,
        String extensionDigest,
        String opCode,
        SkillRef skill,
        List<String> profiles) {

    public record ReleaseRef(String spaceCode, UUID releaseId, String version, String contentDigest) {}

    public record SkillRef(String skillCode, int skillVersion, String skillSha256) {}

    public static final Set<String> CREATE_KEYS = Set.of("bindingId", "appCode", "expectedRev", "requestId",
            "releaseRef", "extensionId", "extensionDigest", "opCode", "skill", "profiles");
    public static final Set<String> REVISE_KEYS = Set.of("expectedRev", "requestId",
            "releaseRef", "extensionId", "extensionDigest", "opCode", "skill", "profiles");

    private static final Set<String> RELEASE_KEYS = Set.of("spaceCode", "releaseId", "version", "contentDigest");
    private static final Set<String> SKILL_KEYS = Set.of("skillCode", "skillVersion", "skillSha256");
    private static final Set<String> PROFILES = Set.of("read-only@1.0");

    private static final Pattern ID = Pattern.compile("^[a-z][a-z0-9-]{2,47}$");
    private static final Pattern SPACE_CODE = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern OP_CODE = Pattern.compile("^[A-Za-z][A-Za-z0-9._:-]{0,79}$");
    private static final Pattern SKILL_CODE = Pattern.compile("^[A-Za-z][A-Za-z0-9._-]{1,63}$");
    private static final Pattern EXTENSION_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$");
    private static final Pattern UUID_TEXT = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /**
     * 解析并校验一份绑定声明。
     *
     * @param create true 表示新建（body 内必须带 bindingId/appCode），false 表示追加修订
     * @param pathId 修订路径上的 bindingId；create 时忽略
     */
    public static SemanticBinding parse(Map<String, Object> body, boolean create, String pathId) {
        keys(body, create ? CREATE_KEYS : REVISE_KEYS, "body");
        String bindingId;
        if (create) {
            bindingId = text(body, "bindingId", 64);
            if (!ID.matcher(bindingId).matches()) {
                throw field("bindingId", "须为 3-48 位小写字母开头的标识");
            }
            appCode(body);
        } else {
            if (pathId == null || !ID.matcher(pathId).matches()) {
                throw field("bindingId", "路径标识不合法");
            }
            bindingId = pathId;
        }

        Map<String, Object> release = object(body.get("releaseRef"), "releaseRef");
        keys(release, RELEASE_KEYS, "releaseRef");
        String spaceCode = text(release, "spaceCode", 64);
        if (!SPACE_CODE.matcher(spaceCode).matches()) {
            throw field("releaseRef.spaceCode", "只允许字母、数字、点、下划线、连字符");
        }
        UUID releaseId = uuid(release.get("releaseId"), "releaseRef.releaseId");
        String version = text(release, "version", 64);
        String contentDigest = sha256(release.get("contentDigest"), "releaseRef.contentDigest");

        String extensionId = optionalText(body, "extensionId", 64);
        if (extensionId != null && !EXTENSION_ID.matcher(extensionId).matches()) {
            throw field("extensionId", "只允许字母、数字、点、下划线、冒号、连字符");
        }
        String extensionDigest = sha256(body.get("extensionDigest"), "extensionDigest");

        String opCode = text(body, "opCode", 80);
        if (!OP_CODE.matcher(opCode).matches()) {
            throw field("opCode", "须以字母开头，只允许字母、数字、点、下划线、冒号、连字符");
        }

        Map<String, Object> skillBody = object(body.get("skill"), "skill");
        keys(skillBody, SKILL_KEYS, "skill");
        String skillCode = text(skillBody, "skillCode", 64);
        if (!SKILL_CODE.matcher(skillCode).matches()) {
            throw field("skill.skillCode", "须以字母开头，只允许字母、数字、点、下划线、连字符");
        }
        int skillVersion = positiveInt(skillBody.get("skillVersion"), "skill.skillVersion");
        String skillSha = skillBody.get("skillSha256") == null ? null
                : sha256(skillBody.get("skillSha256"), "skill.skillSha256");

        List<String> profiles = profiles(body.get("profiles"));

        return new SemanticBinding(bindingId,
                new ReleaseRef(spaceCode, releaseId, version, contentDigest),
                extensionId, extensionDigest, opCode,
                new SkillRef(skillCode, skillVersion, skillSha), profiles);
    }

    /** 应用编码只在新建时从 body 取；修订时以父表为准，避免同一绑定的应用在修订间漂移。 */
    public static String appCode(Map<String, Object> body) {
        String appCode = text(body, "appCode", 64);
        if (!ID.matcher(appCode).matches()) {
            throw field("appCode", "须为 3-48 位小写字母开头的标识");
        }
        return appCode;
    }

    /** 声明摘要：只覆盖引用与版本，不包含 requestId/expectedRev 这类写入控制字段。 */
    public String sha256() {
        return Digest.sha256(Json.canonical(declaration()));
    }

    public Map<String, Object> declaration() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bindingId", bindingId);
        out.put("releaseRef", releaseRefMap());
        if (extensionId != null) {
            out.put("extensionId", extensionId);
        }
        out.put("extensionDigest", extensionDigest);
        out.put("opCode", opCode);
        out.put("skill", skillMap());
        out.put("profiles", profiles);
        return out;
    }

    public Map<String, Object> releaseRefMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("spaceCode", releaseRef.spaceCode());
        out.put("releaseId", releaseRef.releaseId().toString());
        out.put("version", releaseRef.version());
        out.put("contentDigest", releaseRef.contentDigest());
        return out;
    }

    public Map<String, Object> skillMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("skillCode", skill.skillCode());
        out.put("skillVersion", skill.skillVersion());
        if (skill.skillSha256() != null) {
            out.put("skillSha256", skill.skillSha256());
        }
        return out;
    }

    public static PlatformException field(String path, String reason) {
        return new PlatformException(400, "SEMANTIC_FIELD_OUT_OF_RANGE",
                "语义绑定字段不合法: " + path + "（" + reason + "）");
    }

    public static PlatformException invalidReference(String reason) {
        return new PlatformException(400, "SEMANTIC_REF_INVALID", "语义绑定引用不合法: " + reason);
    }

    private static void keys(Map<String, Object> body, Set<String> allowed, String path) {
        for (String key : body.keySet()) {
            if (!allowed.contains(key)) {
                throw field(path + "." + key, "不允许的字段");
            }
        }
    }

    private static Map<String, Object> object(Object raw, String path) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw field(path, "必须是对象");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw field(path, "键必须是字符串");
            }
            out.put(key, entry.getValue());
        }
        return out;
    }

    private static String text(Map<String, Object> body, String key, int max) {
        Object raw = body.get(key);
        if (!(raw instanceof String value) || value.isBlank()) {
            throw field(key, "必填的非空字符串");
        }
        if (value.length() > max) {
            throw field(key, "长度不能超过 " + max);
        }
        if (!value.equals(value.trim())) {
            throw field(key, "首尾不能有空白");
        }
        return value;
    }

    private static String optionalText(Map<String, Object> body, String key, int max) {
        Object raw = body.get(key);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String value) || value.isBlank() || value.length() > max || !value.equals(value.trim())) {
            throw field(key, "若提供须为非空字符串且长度不超过 " + max);
        }
        return value;
    }

    private static String sha256(Object raw, String path) {
        if (!(raw instanceof String value) || !SHA256.matcher(value).matches()) {
            throw field(path, "必须是小写 64 位 SHA-256");
        }
        return value;
    }

    private static UUID uuid(Object raw, String path) {
        if (!(raw instanceof String value) || !UUID_TEXT.matcher(value).matches()) {
            throw field(path, "必须是规范 UUID 字符串");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw invalidReference(path + " 不是合法 UUID");
        }
    }

    private static int positiveInt(Object raw, String path) {
        if (!(raw instanceof Number number) || number.longValue() != number.doubleValue()) {
            throw field(path, "必须是整数");
        }
        long value = number.longValue();
        if (value < 1 || value > 1_000_000L) {
            throw field(path, "须在 1..1000000 之间");
        }
        return (int) value;
    }

    private static List<String> profiles(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > 4) {
            throw field("profiles", "必须是 1-4 项的数组");
        }
        List<String> out = new java.util.ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String profile) || !PROFILES.contains(profile)) {
                throw field("profiles", "仅支持 read-only@1.0");
            }
            if (out.contains(profile)) {
                throw field("profiles", "不能重复");
            }
            out.add(profile);
        }
        return List.copyOf(out);
    }
}
