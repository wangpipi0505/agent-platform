package cn.agentplatform.skill.domain;

import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 语义回执（read-only profile）的 AP 侧投影口径。
 *
 * <p>唯一规范源是 {@code contracts/semantic-runtime-v1.schema.json} 与
 * {@code contracts/semantic-runtime.md}（SEM-01.1，v0.1 草案）；OR 侧同一口径见
 * {@code ontorepo:onto-trace/.../trace/domain/SemanticReceipt.java}。本类只做三件事：
 *
 * <ul>
 *   <li>{@link #project}：把正式响应 {@code meta} 四元组与 {@code data.semantic.*} 片段
 *       投影成一条可校验的回执——四元组必须与 meta 同源，缺 meta、preview、摘要不符一律
 *       产出拒绝标记，不补造版本、不把候选期快照当实际版本；
 *   <li>{@link #validatePaths}：约束 manifest 声明的三个语义路径（{@code factSetPath} /
 *       {@code evidenceRefsPath} / {@code timelinessPath}）必须是 {@code $.data.semantic.*}
 *       下的受限点路径，且不得互相重复；
 *   <li>{@link #parse}：对完整回执做严格复检，供读口与联动测试复用，避免两仓字段漂移。
 * </ul>
 *
 * <p>设计不变量：四态 {@code valueState} 与两态 {@code freshness} 必须与 Schema 同构；缺事实
 * 只能 {@code UNKNOWN}，不能写 {@code ABSENT}/false/0；{@code decisionRef} 在 read-only
 * 下 Schema 级禁止；源数据时点未知时只写 {@code UNKNOWN} 与原因，**绝不填 now 冒充**
 * {@code SOURCE_STAMPED}；拒绝标记显式带 {@code valid=false}，绝不伪装成成功回执。
 *
 * <p>错误码沿用契约 §3 的最小登记集（{@code SEMANTIC_*}），不新增同义码。本类不依赖
 * Spring/DB/HTTP，纯规则可测。
 */
public final class SemanticReceipt {

    public static final String SCHEMA_VERSION = "1.0";
    public static final String PROFILE = "read-only";

    /** 顶层字段封闭集；{@code decisionRef} 不在其中——read-only 下出现即非法。 */
    public static final Set<String> TOP_LEVEL =
            Set.of(
                    "schemaVersion",
                    "profile",
                    "recordId",
                    "status",
                    "bindingRef",
                    "releaseRef",
                    "objectRefs",
                    "factSet",
                    "evidenceRefs",
                    "timeliness",
                    "errors");

    public static final Set<String> VALUE_STATES = Set.of("PRESENT", "NULL", "ABSENT", "UNKNOWN");
    public static final Set<String> FACT_KINDS = Set.of("attribute", "relation");
    public static final Set<String> FRESHNESS = Set.of("SOURCE_STAMPED", "UNKNOWN");
    public static final Set<String> EVIDENCE_KINDS = Set.of("call", "artifact", "business-record");
    public static final Set<String> STATUSES = Set.of("OK", "NO_MATCH", "PARTIAL", "ERROR");

    public static final Set<String> ERROR_CODES =
            Set.of(
                    "SEMANTIC_PROFILE_UNKNOWN",
                    "SEMANTIC_REF_INVALID",
                    "SEMANTIC_SUBJECT_UNVERIFIED",
                    "SEMANTIC_OBJECT_FORBIDDEN",
                    "SEMANTIC_DIGEST_MISMATCH",
                    "SEMANTIC_FIELD_OUT_OF_RANGE",
                    "SEMANTIC_SOURCE_UNAVAILABLE");

    /** manifest 可声明的三个语义投影路径，键名与 OR 冻结 `return_spec` 方言一致。 */
    public static final List<String> SEMANTIC_PATHS =
            List.of("factSetPath", "evidenceRefsPath", "timelinessPath");

    private static final String PREFIX = "$.data.semantic.";
    private static final String SPACE_CODE = "[A-Za-z0-9._-]{1,64}";
    private static final String RECORD_ID = "[A-Za-z0-9._-]{1,64}";
    private static final String SHA256 = "[0-9a-f]{64}";
    private static final String UUID_TEXT =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final int MAX_ITEMS = 64;
    private static final int MAX_REFS = 16;

    /** {@code sha256(canonical(items))}：确定性事实摘要，摘要算法名随之登记，不冒充 contentDigest。 */
    public static final String FACT_DIGEST_ALGORITHM = "sha256(canonical(factSet.items))";

    private SemanticReceipt() {}

    /**
     * 投影结果。{@code record} 为待落库的回执或拒绝标记；{@code status} 是回执状态（拒绝标记恒为
     * ERROR）；{@code valid=false} 表示这只是拒绝标记，读口不得把它渲染成成功语义结果。
     */
    public record Projection(Map<String, Object> record, String status, boolean valid, String digest) {}

    /**
     * 由正式响应片段投影一条回执。
     *
     * @param contract 技能版本契约（取 {@code code}/{@code version}/{@code sha256}）
     * @param meta 正式响应 {@code meta}（四元组核对源；缺失即拒绝）
     * @param fragment 已按声明路径解析出的语义片段（{@code releaseRef}/{@code evidenceRefs}/
     *     {@code timeliness}/{@code factSet}），空表示本次响应没有语义投影
     * @param callId AP 侧本次调用 id（整数文本进证据，不泄漏内部自增细节之外的信息）
     * @param runId 运行公开 id
     * @param observedAt 观察时间（只用于 {@code observedAt}，**不得**当作源数据时点）
     */
    public static Projection project(
            Map<String, Object> contract,
            Map<String, Object> meta,
            Map<String, Object> fragment,
            long callId,
            String runId,
            Instant observedAt) {
        if (fragment == null || fragment.isEmpty()) return null;
        Map<String, Object> binding = bindingRef(contract);
        if (meta == null || meta.isEmpty())
            return marker(
                    "SEMANTIC_REF_INVALID", "正式响应缺少 meta 四元组，无法核对语义版本来源", binding);
        if (Boolean.TRUE.equals(meta.get("preview")))
            return marker(
                    "SEMANTIC_REF_INVALID", "preview=true 的候选期响应不得采集为实际版本", binding);
        Map<String, Object> releaseRef;
        try {
            releaseRef = releaseRef(fragment.get("releaseRef"));
        } catch (IllegalArgumentException invalid) {
            return marker("SEMANTIC_FIELD_OUT_OF_RANGE", invalid.getMessage(), binding);
        }
        try {
            requireSameRelease(releaseRef, meta);
        } catch (IllegalArgumentException mismatch) {
            String code = "SEMANTIC_DIGEST_MISMATCH".equals(mismatch.getMessage())
                    ? "SEMANTIC_DIGEST_MISMATCH"
                    : "SEMANTIC_REF_INVALID";
            return marker(code, describe(code), binding);
        }
        List<Map<String, Object>> evidence;
        try {
            evidence = evidenceRefs(fragment.get("evidenceRefs"));
        } catch (IllegalArgumentException invalid) {
            return marker("SEMANTIC_FIELD_OUT_OF_RANGE", invalid.getMessage(), binding);
        }
        evidence = withThisCall(evidence, callId, runId);
        Map<String, Object> timeliness;
        try {
            timeliness = timeliness(fragment.get("timeliness"));
        } catch (IllegalArgumentException invalid) {
            return marker("SEMANTIC_FIELD_OUT_OF_RANGE", invalid.getMessage(), binding);
        }
        Map<String, Object> factSet = null;
        try {
            factSet = factSet(fragment.get("factSet"), callId);
        } catch (IllegalArgumentException invalid) {
            return marker("SEMANTIC_FIELD_OUT_OF_RANGE", invalid.getMessage(), binding);
        }

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("schemaVersion", SCHEMA_VERSION);
        record.put("profile", PROFILE);
        record.put("bindingRef", binding);
        record.put("releaseRef", releaseRef);
        if (factSet == null) {
            // 片段只证明了版本、证据与时效：事实由 SEM-03 事实服务产出。这里如实标 PARTIAL，
            // 不造空事实冒充"查过了没有"，也不把片段渲染成 OK。
            record.put("status", "PARTIAL");
            record.put("factSet", emptyFactSet(callId));
            record.put(
                    "errors",
                    List.of(
                            Map.of(
                                    "code", "SEMANTIC_SOURCE_UNAVAILABLE",
                                    "message", "本次响应只提供语义投影片段，未提供事实集")));
        } else {
            record.put("status", ((List<?>) factSet.get("items")).isEmpty() ? "NO_MATCH" : "OK");
            record.put("factSet", factSet);
        }
        record.put("evidenceRefs", evidence);
        record.put("timeliness", timeliness);
        String digest = Digest.sha256(Json.canonical(record));
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("schemaVersion", SCHEMA_VERSION);
        ordered.put("profile", PROFILE);
        ordered.put("recordId", recordId(callId, digest));
        ordered.put("status", record.get("status"));
        ordered.put("bindingRef", binding);
        ordered.put("releaseRef", releaseRef);
        ordered.put("factSet", record.get("factSet"));
        ordered.put("evidenceRefs", evidence);
        ordered.put("timeliness", timeliness);
        if (record.containsKey("errors")) ordered.put("errors", record.get("errors"));
        return new Projection(ordered, String.valueOf(ordered.get("status")), true, digest);
    }

    /** 拒绝标记：显式 {@code valid=false}，不假装是成功回执，也不补造版本四元组。 */    public static Projection rejected(
            String code, String message, Map<String, Object> contract) {
        return marker(code, message, bindingRef(contract));
    }

    private static Projection marker(
            String code, String message, Map<String, Object> binding) {
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("schemaVersion", SCHEMA_VERSION);
        marker.put("profile", PROFILE);
        marker.put("status", "ERROR");
        marker.put("bindingRef", binding);
        marker.put("errors", List.of(Map.of("code", code, "message", message)));
        marker.put("valid", false);
        String digest = Digest.sha256(Json.canonical(marker));
        marker.put("recordId", "sr_" + digest.substring(0, 32));
        return new Projection(marker, "ERROR", false, digest);
    }

    /** 记录摘要（只覆盖回执本体，用于执行事实关联；不含调用时间等易变元数据）。 */
    public static String recordId(long callId, String digest) {
        return "sr_" + Digest.sha256(callId + ":" + digest).substring(0, 32);
    }

    /** 校验 manifest 声明的三个语义路径：受限点路径、同前缀、互不重复。 */
    public static void validatePaths(Map<String, Object> returnSpec) {
        Set<String> used = new LinkedHashSet<>();
        for (String key : SEMANTIC_PATHS) {
            Object raw = returnSpec.get(key);
            if (raw == null) continue;
            if (!(raw instanceof String path)) throw invalidPath(key);
            if (!path.startsWith(PREFIX)) throw invalidPath(key);
            String rest = path.substring(PREFIX.length());
            if (rest.isEmpty() || !rest.matches("[A-Za-z_][A-Za-z0-9_-]*(\\.[A-Za-z_][A-Za-z0-9_-]*)*"))
                throw invalidPath(key);
            if (!used.add(key + "=" + path)) throw invalidPath(key);
        }
    }

    private static IllegalArgumentException invalidPath(String key) {
        return new IllegalArgumentException(
                "returnSpec." + key + "必须为$.data.semantic.*下的点路径，且各语义路径不得重复");
    }

    private static String describe(String code) {
        return "SEMANTIC_DIGEST_MISMATCH".equals(code)
                ? "语义片段的 contentDigest 与正式响应 meta 不一致"
                : "语义片段的版本四元组与正式响应 meta 不一致";
    }

    private static Map<String, Object> bindingRef(Map<String, Object> contract) {
        Map<String, Object> binding = new LinkedHashMap<>();
        String code = text(contract == null ? null : contract.get("code"), 64, "bindingRef.skillCode");
        binding.put("skillCode", code);
        int version = 1;
        for (String key : List.of("version", "versionNo")) {
            Object raw = contract == null ? null : contract.get(key);
            if (raw instanceof Number number && number.intValue() > 0) {
                version = number.intValue();
                break;
            }
        }
        binding.put("skillVersion", version);
        Object sha = contract == null ? null : contract.get("sha256");
        if (sha instanceof String value && value.matches(SHA256)) binding.put("skillSha256", value);
        return binding;
    }

    private static Map<String, Object> releaseRef(Object raw) {
        Map<String, Object> source = object(raw, "releaseRef");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("spaceCode", pattern(source.get("spaceCode"), SPACE_CODE, "releaseRef.spaceCode"));
        out.put("releaseId", pattern(source.get("releaseId"), UUID_TEXT, "releaseRef.releaseId"));
        out.put("version", text(source.get("version"), 64, "releaseRef.version"));
        out.put("contentDigest", pattern(source.get("contentDigest"), SHA256, "releaseRef.contentDigest"));
        return out;
    }

    /** meta 与片段必须同源：任何一项不等都拒绝，绝不"以 meta 覆盖片段"掩盖漂移。 */
    private static void requireSameRelease(Map<String, Object> releaseRef, Map<String, Object> meta) {
        for (String key : List.of("spaceCode", "releaseId", "version")) {
            Object actual = meta.get(key);
            if (!String.valueOf(releaseRef.get(key)).equals(String.valueOf(actual)))
                throw new IllegalArgumentException("SEMANTIC_REF_MISMATCH");
        }
        if (!String.valueOf(releaseRef.get("contentDigest"))
                .equals(String.valueOf(meta.get("contentDigest"))))
            throw new IllegalArgumentException("SEMANTIC_DIGEST_MISMATCH");
    }

    private static List<Map<String, Object>> evidenceRefs(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof Collection<?> items)) throw new IllegalArgumentException("evidenceRefs必须为数组");
        if (items.size() > MAX_REFS) throw new IllegalArgumentException("evidenceRefs超过16条");
        List<Map<String, Object>> out = new ArrayList<>(items.size());
        for (Object item : items) out.add(evidenceRef(item));
        return out;
    }

    private static Map<String, Object> evidenceRef(Object raw) {
        Map<String, Object> source = object(raw, "evidenceRefs[]");
        String kind = text(source.get("evidenceKind"), 32, "evidenceKind");
        if (!EVIDENCE_KINDS.contains(kind)) throw new IllegalArgumentException("evidenceKind不合法");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("evidenceKind", kind);
        switch (kind) {
            case "call" -> {
                out.put("callId", text(source.get("callId"), 64, "evidenceRefs.callId"));
                if (source.get("runId") != null) out.put("runId", text(source.get("runId"), 64, "runId"));
            }
            case "artifact" -> {
                Object id = source.get("artifactId");
                if (!(id instanceof Number number) || number.longValue() < 1)
                    throw new IllegalArgumentException("artifactId必须为正整数");
                out.put("artifactId", number.longValue());
                if (source.get("digest") != null) out.put("digest", text(source.get("digest"), 128, "digest"));
            }
            default -> {
                out.put("recordSource", text(source.get("recordSource"), 64, "recordSource"));
                out.put("recordId", text(source.get("recordId"), 128, "recordId"));
            }
        }
        return out;
    }

    /** 本次 AP 调用自身也是一条证据：读口据此把回执钉回同一 call/run。 */
    private static List<Map<String, Object>> withThisCall(
            List<Map<String, Object>> evidence, long callId, String runId) {
        String own = String.valueOf(callId);
        for (Map<String, Object> ref : evidence)
            if ("call".equals(ref.get("evidenceKind")) && own.equals(String.valueOf(ref.get("callId"))))
                return evidence;
        List<Map<String, Object>> out = new ArrayList<>(evidence);
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("evidenceKind", "call");
        ref.put("callId", own);
        if (runId != null && !runId.isBlank()) ref.put("runId", runId);
        out.add(ref);
        if (out.size() > MAX_REFS) throw new IllegalArgumentException("evidenceRefs超过16条");
        return out;
    }

    private static Map<String, Object> timeliness(Object raw) {
        Map<String, Object> source = object(raw, "timeliness");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("observedAt", timestamp(source.get("observedAt"), "timeliness.observedAt"));
        String freshness = text(source.get("freshness"), 16, "timeliness.freshness");
        if (!FRESHNESS.contains(freshness)) throw new IllegalArgumentException("timeliness.freshness不合法");
        out.put("freshness", freshness);
        if ("SOURCE_STAMPED".equals(freshness)) {
            // 源数据时点只能来自来源自身；这里只接受来源给出的值，缺失即拒绝，不填 now。
            out.put("dataAsOf", timestamp(source.get("dataAsOf"), "timeliness.dataAsOf"));
            if (source.get("sourceVersion") != null)
                out.put("sourceVersion", text(source.get("sourceVersion"), 64, "timeliness.sourceVersion"));
        } else {
            if (source.get("dataAsOf") != null)
                throw new IllegalArgumentException("freshness=UNKNOWN时不得提供dataAsOf");
            out.put("unknownReason", text(source.get("unknownReason"), 256, "timeliness.unknownReason"));
        }
        return out;
    }

    /**
     * 片段未携带事实集时的空事实集：{@code items} 为空、本身可校验，并写明事实来源。
     *
     * <p>摘要按同一算法计算（{@code sha256(canonical(items))}），使"没有事实"也是可核对的
     * 确定结论，而不是缺字段。
     */
    private static Map<String, Object> emptyFactSet(long callId) {
        List<Map<String, Object>> items = List.of();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("queryRef", "call:" + callId);
        out.put("items", items);
        out.put("factSetDigest", Digest.sha256(Json.canonical(items)));
        out.put("sourceNote", "来源未提供事实集（read-only 投影片段，事实由 SEM-03 事实服务产出）");
        return out;
    }

    private static Map<String, Object> factSet(Object raw, long callId) {
        if (raw == null) return null;
        Map<String, Object> source = object(raw, "factSet");
        Object itemsRaw = source.get("items");
        if (!(itemsRaw instanceof Collection<?> items)) throw new IllegalArgumentException("factSet.items必须为数组");
        if (items.size() > MAX_ITEMS) throw new IllegalArgumentException("factSet.items超过64条");
        List<Map<String, Object>> parsed = new ArrayList<>(items.size());
        for (Object item : items) parsed.add(factItem(item));
        Map<String, Object> out = new LinkedHashMap<>();
        Object queryRef = source.get("queryRef");
        out.put("queryRef", queryRef == null ? "call:" + callId : text(queryRef, 256, "factSet.queryRef"));
        out.put("items", parsed);
        Object digest = source.get("factSetDigest");
        out.put(
                "factSetDigest",
                digest == null
                        ? Digest.sha256(Json.canonical(parsed))
                        : text(digest, 128, "factSet.factSetDigest"));
        if (source.get("sourceNote") != null)
            out.put("sourceNote", text(source.get("sourceNote"), 256, "factSet.sourceNote"));
        return out;
    }

    private static Map<String, Object> factItem(Object raw) {
        Map<String, Object> source = object(raw, "factSet.items[]");
        String kind = text(source.get("factKind"), 16, "factKind");
        if (!FACT_KINDS.contains(kind)) throw new IllegalArgumentException("factKind不合法");
        String state = text(source.get("valueState"), 16, "valueState");
        if (!VALUE_STATES.contains(state)) throw new IllegalArgumentException("valueState不合法");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("objectRef", objectRef(source.get("objectRef")));
        out.put("factKind", kind);
        if ("attribute".equals(kind)) {
            out.put("attribute", text(source.get("attribute"), 64, "attribute"));
        } else {
            out.put("relation", text(source.get("relation"), 64, "relation"));
        }
        out.put("valueState", state);
        switch (state) {
            case "PRESENT" -> {
                if ("attribute".equals(kind)) {
                    Object value = source.get("value");
                    if (value == null) throw new IllegalArgumentException("PRESENT必须带value");
                    if (value instanceof String str && str.length() > 256)
                        throw new IllegalArgumentException("value超过256字符");
                    if (!(value instanceof String) && !(value instanceof Number) && !(value instanceof Boolean))
                        throw new IllegalArgumentException("value只能是标量");
                    out.put("value", value);
                } else {
                    out.put("relatedObjectRef", objectRef(source.get("relatedObjectRef")));
                }
            }
            case "NULL" -> {
                if (!source.containsKey("value") || source.get("value") != null)
                    throw new IllegalArgumentException("NULL必须显式为null");
                out.put("value", null);
            }
            case "ABSENT" -> {
                if (source.containsKey("value")) throw new IllegalArgumentException("ABSENT不得携带value");
            }
            default -> {
                if (source.containsKey("value")) throw new IllegalArgumentException("UNKNOWN不得携带value");
                out.put("unknownReason", text(source.get("unknownReason"), 256, "unknownReason"));
            }
        }
        out.put("observedAt", timestamp(source.get("observedAt"), "factSet.items.observedAt"));
        return out;
    }

    private static Map<String, Object> objectRef(Object raw) {
        Map<String, Object> source = object(raw, "objectRef");
        String kind = text(source.get("refKind"), 16, "refKind");
        Map<String, Object> out = new LinkedHashMap<>();
        if ("space-object".equals(kind)) {
            if (source.containsKey("sourceSystem") || source.containsKey("externalId"))
                throw new IllegalArgumentException("space-object不得携带external字段");
            out.put("refKind", kind);
            out.put("spaceCode", pattern(source.get("spaceCode"), SPACE_CODE, "objectRef.spaceCode"));
            out.put("objectType", text(source.get("objectType"), 64, "objectRef.objectType"));
            out.put("stableId", text(source.get("stableId"), 128, "objectRef.stableId"));
        } else if ("external".equals(kind)) {
            if (source.containsKey("spaceCode")
                    || source.containsKey("objectType")
                    || source.containsKey("stableId"))
                throw new IllegalArgumentException("external不得携带space字段");
            out.put("refKind", kind);
            out.put("sourceSystem", text(source.get("sourceSystem"), 64, "objectRef.sourceSystem"));
            out.put("externalId", text(source.get("externalId"), 128, "objectRef.externalId"));
        } else {
            throw new IllegalArgumentException("refKind不合法");
        }
        return out;
    }

    /**
     * 严格复检一份完整回执（读口/联动测试用）：字段封闭、枚举、分支与 status 联动。
     *
     * @throws IllegalArgumentException 任一字段越界（调用方按 {@code SEMANTIC_FIELD_OUT_OF_RANGE} 处理）
     */
    public static void parse(Map<String, Object> raw) {
        if (raw == null) throw new IllegalArgumentException("record不能为空");
        only(raw, TOP_LEVEL);
        if (raw.containsKey("decisionRef")) throw new IllegalArgumentException("decisionRef禁止出现");
        if (!SCHEMA_VERSION.equals(raw.get("schemaVersion")))
            throw new IllegalArgumentException("schemaVersion不合法");
        if (!PROFILE.equals(raw.get("profile"))) throw new IllegalArgumentException("profile不合法");
        pattern(raw.get("recordId"), RECORD_ID, "recordId");
        String status = text(raw.get("status"), 16, "status");
        if (!STATUSES.contains(status)) throw new IllegalArgumentException("status不合法");
        bindingRef(raw.get("bindingRef"));
        releaseRef(raw.get("releaseRef"));
        timeliness(raw.get("timeliness"));
        boolean facts = raw.containsKey("factSet");
        if (facts) factSet(raw.get("factSet"), 0);
        if (raw.containsKey("objectRefs")) {
            for (Object ref : list(raw.get("objectRefs"))) objectRef(ref);
        }
        if (raw.containsKey("evidenceRefs"))
            for (Object ref : list(raw.get("evidenceRefs"))) evidenceRef(ref);
        List<?> errors = raw.containsKey("errors") ? list(raw.get("errors")) : List.of();
        for (Object error : errors) {
            Map<String, Object> item = object(error, "errors[]");
            String code = text(item.get("code"), 48, "errors.code");
            if (!ERROR_CODES.contains(code)) throw new IllegalArgumentException("errors.code不在登记集内");
            text(item.get("message"), 256, "errors.message");
        }
        switch (status) {
            case "ERROR" -> {
                if (facts) throw new IllegalArgumentException("ERROR不得携带factSet");
                if (errors.isEmpty()) throw new IllegalArgumentException("ERROR必须说明原因");
            }
            case "PARTIAL" -> {
                if (!facts) throw new IllegalArgumentException("PARTIAL必须携带factSet");
                if (errors.isEmpty()) throw new IllegalArgumentException("PARTIAL必须说明缺项");
            }
            default -> {
                if (!facts) throw new IllegalArgumentException(status + "必须携带factSet");
                if (!errors.isEmpty()) throw new IllegalArgumentException(status + "不得携带errors");
            }
        }
    }

    private static Map<String, Object> bindingRef(Object raw) {
        Map<String, Object> source = object(raw, "bindingRef");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("skillCode", pattern(source.get("skillCode"), RECORD_ID, "bindingRef.skillCode"));
        Object version = source.get("skillVersion");
        if (!(version instanceof Number number) || number.intValue() < 1)
            throw new IllegalArgumentException("bindingRef.skillVersion必须为正整数");
        out.put("skillVersion", number.intValue());
        if (source.get("skillSha256") != null)
            out.put("skillSha256", pattern(source.get("skillSha256"), SHA256, "bindingRef.skillSha256"));
        return out;
    }

    private static void only(Map<String, Object> raw, Set<String> allowed) {
        for (String key : raw.keySet())
            if (!allowed.contains(key)) throw new IllegalArgumentException("未知字段: " + key);
    }

    private static List<?> list(Object raw) {
        if (!(raw instanceof Collection<?> items)) throw new IllegalArgumentException("必须为数组");
        return new ArrayList<>(items);
    }

    private static Map<String, Object> object(Object raw, String path) {
        if (!(raw instanceof Map<?, ?> map)) throw new IllegalArgumentException(path + "必须为对象");
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) out.put(String.valueOf(entry.getKey()), entry.getValue());
        return out;
    }

    private static String text(Object raw, int max, String path) {
        if (!(raw instanceof String value) || value.isBlank())
            throw new IllegalArgumentException(path + "必须为非空字符串");
        if (value.length() > max) throw new IllegalArgumentException(path + "超过" + max + "字符");
        return value;
    }

    private static String pattern(Object raw, String regex, String path) {
        String value = text(raw, 256, path);
        if (!value.matches(regex)) throw new IllegalArgumentException(path + "格式不合法");
        return value;
    }

    private static String timestamp(Object raw, String path) {
        String value = text(raw, 40, path);
        try {
            Instant.parse(value);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(path + "必须为date-time");
        }
        return value;
    }
}
