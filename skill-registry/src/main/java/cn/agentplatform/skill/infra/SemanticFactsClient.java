package cn.agentplatform.skill.infra;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * OR 只读语义事实入口（SEM-03.1 引用解析 / SEM-03.2 单源事实）。
 *
 * <p>只经 {@link SkillClients} 的既有签票/信封语义访问 OR，不直连 OR 数据库，也不把
 * 调用方给的外部 ID 当已解析对象。响应必须逐字段核对：releaseId/spaceCode 必须与本次
 * 请求一致，缺字段或标识不是 UUID 一律按依赖不可用拒绝，避免把错版本地址带回执行链。
 *
 * <p>事实响应只做形状核对与白名单投影：四态、关系引用与时效分支必须自洽；不重算
 * factSetDigest（摘要由 OR 按冻结载荷生成），也不把 UNKNOWN 时效补成当前时间。
 */
@Component
public class SemanticFactsClient {
    /** 与平台其他 OR 侧调用一致：地址从配置注入，不在此硬编码业务空间。 */
    private static final String PATH = "/onto/releases/%s/semantic/facts/resolve";

    private static final String FACTS_PATH = "/onto/releases/%s/semantic/facts";
    private static final Set<String> VALUE_STATES =
            Set.of("PRESENT", "NULL", "ABSENT", "UNKNOWN");
    private static final Set<String> EVIDENCE_KINDS =
            Set.of("call", "artifact", "business-record");
    private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");

    private final SkillClients clients;
    private final String baseUrl;

    public SemanticFactsClient(
            SkillClients clients,
            @Value("${agent.services.ontology:http://127.0.0.1:18182}") String baseUrl) {
        this.clients = Objects.requireNonNull(clients);
        this.baseUrl = Objects.requireNonNull(baseUrl).replaceAll("/+$", "");
    }

    /** 解析一个 space-object 引用；外部引用由 OR 侧拒绝，不在此处补解析器。 */
    public ResolvedObject resolve(UUID releaseId, Map<String, Object> reference) {
        if (releaseId == null || reference == null)
            throw new PlatformException(400, "SEMANTIC_REF_INVALID", "对象引用不能为空");
        JsonNode data = clients.post(baseUrl + PATH.formatted(releaseId), reference);
        if (!data.isObject()) throw malformed();
        String refKind = text(data, "refKind");
        String spaceCode = text(data, "spaceCode");
        String objectType = text(data, "objectType");
        String stableId = text(data, "stableId");
        if (!releaseId.toString().equals(text(data, "releaseId")))
            throw malformed();
        if (reference.get("spaceCode") != null
                && !reference.get("spaceCode").toString().equals(spaceCode)) throw malformed();
        if (reference.get("objectType") != null
                && !reference.get("objectType").toString().equals(objectType)) throw malformed();
        if (reference.get("stableId") != null
                && !reference.get("stableId").toString().equals(stableId)) throw malformed();
        return new ResolvedObject(
                refKind,
                spaceCode,
                UUID.fromString(text(data, "releaseId")),
                text(data, "version"),
                objectType,
                stableId,
                uuid(data, "classId"),
                uuid(data, "mappingId"),
                text(data, "keyField"),
                text(data, "sourceKey"));
    }

    /** 服务端地址的可核对投影；不含源表/数据源等实现细节。 */
    public record ResolvedObject(
            String refKind,
            String spaceCode,
            UUID releaseId,
            String version,
            String objectType,
            String stableId,
            UUID classId,
            UUID mappingId,
            String keyField,
            String sourceKey) {

        public Map<String, Object> toMap() {
            var map = new LinkedHashMap<String, Object>();
            map.put("refKind", refKind);
            map.put("spaceCode", spaceCode);
            map.put("releaseId", releaseId.toString());
            map.put("version", version);
            map.put("objectType", objectType);
            map.put("stableId", stableId);
            map.put("classId", classId.toString());
            map.put("mappingId", mappingId.toString());
            map.put("keyField", keyField);
            map.put("sourceKey", sourceKey);
            return map;
        }
    }

    /**
     * 读取一个固定版本对象在已发布操作下的单源事实。
     *
     * <p>调用方只给操作编码、对象引用与入参；源、范围与时点由 OR 按冻结声明判定。
     * 响应中的 releaseRef 必须与本次 release 一致，四态与关系引用必须自洽，
     * 否则按依赖不可用拒绝，绝不把半合法事实带进后续投影。
     */
    public Facts facts(
            UUID releaseId,
            String opCode,
            Map<String, Object> reference,
            Map<String, Object> input) {
        if (releaseId == null || opCode == null || opCode.isBlank() || reference == null)
            throw new PlatformException(400, "SEMANTIC_REF_INVALID", "事实请求缺少版本、操作或对象引用");
        var body = new LinkedHashMap<String, Object>();
        body.put("opCode", opCode);
        body.put("reference", reference);
        body.put("input", input == null ? Map.of() : input);
        JsonNode data = clients.post(baseUrl + FACTS_PATH.formatted(releaseId), body);
        if (!data.isObject()) throw malformed();

        Map<String, Object> releaseRef = releaseRef(data.get("releaseRef"), releaseId);
        Map<String, Object> source = source(data.get("source"));
        Map<String, Object> factSet = factSet(data.get("factSet"));
        Map<String, Object> timeliness = timeliness(data.get("timeliness"));
        List<Map<String, Object>> evidenceRefs = evidenceRefs(data.get("evidenceRefs"));
        return new Facts(releaseRef, objectRef(data.get("objectRef")), source, factSet, timeliness, evidenceRefs);
    }

    private Map<String, Object> releaseRef(JsonNode node, UUID releaseId) {
        if (node == null || !node.isObject()) throw malformed();
        String id = text(node, "releaseId");
        if (!releaseId.toString().equals(id)) throw malformed();
        String spaceCode = text(node, "spaceCode");
        String version = text(node, "version");
        String digest = text(node, "contentDigest");
        if (!DIGEST.matcher(digest).matches()) throw malformed();
        var ref = new LinkedHashMap<String, Object>();
        ref.put("spaceCode", spaceCode);
        ref.put("releaseId", id);
        ref.put("version", version);
        ref.put("contentDigest", digest);
        return ref;
    }

    private Map<String, Object> source(JsonNode node) {
        if (node == null || !node.isObject()) throw malformed();
        var source = new LinkedHashMap<String, Object>();
        source.put("datasourceId", text(node, "datasourceId"));
        source.put("sourceResource", text(node, "sourceResource"));
        JsonNode sha = node.get("metaSnapshotSha256");
        if (sha != null) {
            if (!sha.isTextual() || !DIGEST.matcher(sha.asText()).matches()) throw malformed();
            source.put("metaSnapshotSha256", sha.asText());
        }
        return source;
    }

    private Map<String, Object> factSet(JsonNode node) {
        if (node == null || !node.isObject()) throw malformed();
        String queryRef = text(node, "queryRef");
        JsonNode items = node.get("items");
        if (items == null || !items.isArray() || items.size() > 64) throw malformed();
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (JsonNode item : items) normalized.add(factItem(item));
        var set = new LinkedHashMap<String, Object>();
        set.put("queryRef", queryRef);
        set.put("items", normalized);
        JsonNode digest = node.get("factSetDigest");
        if (digest != null) {
            if (!digest.isTextual() || !DIGEST.matcher(digest.asText()).matches()) throw malformed();
            set.put("factSetDigest", digest.asText());
        }
        JsonNode note = node.get("sourceNote");
        if (note != null) {
            if (!note.isTextual() || note.asText().isBlank() || note.asText().length() > 256)
                throw malformed();
            set.put("sourceNote", note.asText());
        }
        return set;
    }

    private Map<String, Object> factItem(JsonNode node) {
        if (node == null || !node.isObject()) throw malformed();
        String kind = text(node, "factKind");
        if (!"attribute".equals(kind) && !"relation".equals(kind)) throw malformed();
        String name = text(node, kind);
        String state = text(node, "valueState");
        if (!VALUE_STATES.contains(state)) throw malformed();
        String observedAt = text(node, "observedAt");
        try {
            Instant.parse(observedAt);
        } catch (DateTimeParseException ex) {
            throw malformed();
        }
        boolean hasValue = node.has("value");
        JsonNode value = node.get("value");
        var item = new LinkedHashMap<String, Object>();
        item.put("objectRef", objectRef(node.get("objectRef")));
        item.put("factKind", kind);
        item.put(kind, name);
        item.put("valueState", state);
        switch (state) {
            case "PRESENT" -> {
                if ("relation".equals(kind)) {
                    if (hasValue) throw malformed();
                    item.put("relatedObjectRef", objectRef(node.get("relatedObjectRef")));
                } else {
                    if (!hasValue || value == null || value.isNull()) throw malformed();
                    if (!(value.isTextual() || value.isNumber() || value.isBoolean())) throw malformed();
                    item.put("value", Json.mapper().convertValue(value, Object.class));
                }
            }
            case "NULL" -> {
                if (!hasValue || value == null || !value.isNull()) throw malformed();
                item.put("value", null);
            }
            case "ABSENT" -> {
                if (hasValue) throw malformed();
            }
            case "UNKNOWN" -> {
                if (hasValue) throw malformed();
                String reason = text(node, "unknownReason");
                item.put("unknownReason", reason);
            }
            default -> throw malformed();
        }
        item.put("observedAt", observedAt);
        return item;
    }

    private Map<String, Object> timeliness(JsonNode node) {
        if (node == null || !node.isObject()) throw malformed();
        String observedAt = text(node, "observedAt");
        String freshness = text(node, "freshness");
        instant(observedAt);
        var time = new LinkedHashMap<String, Object>();
        time.put("observedAt", observedAt);
        time.put("freshness", freshness);
        if ("UNKNOWN".equals(freshness)) {
            time.put("unknownReason", text(node, "unknownReason"));
        } else if ("SOURCE_STAMPED".equals(freshness)) {
            String dataAsOf = text(node, "dataAsOf");
            instant(dataAsOf);
            time.put("dataAsOf", dataAsOf);
            JsonNode version = node.get("sourceVersion");
            if (version != null) {
                if (!version.isTextual() || version.asText().length() > 64) throw malformed();
                time.put("sourceVersion", version.asText());
            }
        } else throw malformed();
        return time;
    }

    private List<Map<String, Object>> evidenceRefs(JsonNode node) {
        if (node == null || !node.isArray() || node.size() > 16) throw malformed();
        List<Map<String, Object>> refs = new ArrayList<>();
        for (JsonNode ref : node) {
            if (!ref.isObject()) throw malformed();
            String kind = text(ref, "evidenceKind");
            if (!EVIDENCE_KINDS.contains(kind)) throw malformed();
            var normalized = new LinkedHashMap<String, Object>();
            normalized.put("evidenceKind", kind);
            switch (kind) {
                case "call" -> {
                    normalized.put("callId", text(ref, "callId"));
                    JsonNode run = ref.get("runId");
                    if (run != null) {
                        if (!run.isTextual() || run.asText().length() > 64) throw malformed();
                        normalized.put("runId", run.asText());
                    }
                }
                case "artifact" -> {
                    JsonNode id = ref.get("artifactId");
                    if (id == null || !id.canConvertToLong() || id.asLong() < 1) throw malformed();
                    normalized.put("artifactId", id.asLong());
                    JsonNode digest = ref.get("digest");
                    if (digest != null) {
                        if (!digest.isTextual() || digest.asText().length() > 128) throw malformed();
                        normalized.put("digest", digest.asText());
                    }
                }
                case "business-record" -> {
                    normalized.put("recordSource", text(ref, "recordSource"));
                    normalized.put("recordId", text(ref, "recordId"));
                }
                default -> throw malformed();
            }
            refs.add(normalized);
        }
        return refs;
    }

    private static Map<String, Object> objectRef(JsonNode node) {
        if (node == null || !node.isObject()) throw malformed();
        String kind = text(node, "refKind");
        var ref = new LinkedHashMap<String, Object>();
        ref.put("refKind", kind);
        if ("space-object".equals(kind)) {
            ref.put("spaceCode", text(node, "spaceCode"));
            ref.put("objectType", text(node, "objectType"));
            ref.put("stableId", text(node, "stableId"));
        } else if ("external".equals(kind)) {
            ref.put("sourceSystem", text(node, "sourceSystem"));
            ref.put("externalId", text(node, "externalId"));
        } else throw malformed();
        return ref;
    }

    private static void instant(String value) {
        if (value == null || value.isBlank() || value.length() > 64) throw malformed();
        try {
            Instant.parse(value);
        } catch (DateTimeParseException ex) {
            throw malformed();
        }
    }

    /** 服务端已核对过的单源事实投影；字段为白名单，不携带源连接或原始行。 */
    public record Facts(
            Map<String, Object> releaseRef,
            Map<String, Object> objectRef,
            Map<String, Object> source,
            Map<String, Object> factSet,
            Map<String, Object> timeliness,
            List<Map<String, Object>> evidenceRefs) {

        public Map<String, Object> toMap() {
            var map = new LinkedHashMap<String, Object>();
            map.put("releaseRef", releaseRef);
            map.put("objectRef", objectRef);
            map.put("source", source);
            map.put("factSet", factSet);
            map.put("timeliness", timeliness);
            map.put("evidenceRefs", evidenceRefs);
            return map;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw malformed();
        return value.asText();
    }

    private static UUID uuid(JsonNode node, String field) {
        try {
            return UUID.fromString(text(node, field));
        } catch (IllegalArgumentException ex) {
            throw malformed();
        }
    }

    private static PlatformException malformed() {
        return new PlatformException(502, "DEPENDENCY_UNAVAILABLE", "本体服务返回的对象引用解析结果不完整");
    }
}
