package cn.agentplatform.skill.infra;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * OR 固定版本定义投影入口（SEM-06.1 端点，SEM-06.2 AP 侧接线）。
 *
 * <p>定义投影是"这个版本允许看什么"的只读视图，不是权限票据：本客户端只负责把固定 releaseId
 * 与查询选择送给 OR，并逐字段核对响应。releaseId 必须与请求一致，五类定义必须带类型化 ref，
 * 截断声明必须自洽；任何形状不符都按依赖不可用拒绝，绝不把半合法定义带进模型上下文。
 *
 * <p>本类<b>不缓存</b>投影正文：OR 的缓存键已经包含有效权限/策略修订，客户端再缓存会让撤权后的
 * 旧投影继续命中；同一运行内的追问应每次都经过 OR 的授权修订判断。
 */
@Component
public class SemanticDefinitionClient {
    private static final String PATH = "/onto/releases/%s/semantic/definitions/query";
    private static final Set<String> SELECTION_KEYS =
            Set.of("kinds", "objectTypes", "terms", "intent", "limit");
    private static final Set<String> KIND_NAMES =
            Set.of("objects", "properties", "relations", "rules", "actions");
    private static final Map<String, String> REF_KINDS =
            Map.of(
                    "objects", "object-type",
                    "properties", "property",
                    "relations", "relation",
                    "rules", "rule",
                    "actions", "action");
    private static final int MAX_ENTRIES = 200;
    private static final int MAX_TERMS = 16;
    private static final int MAX_TYPES = 32;
    private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");

    private final SkillClients clients;
    private final String baseUrl;

    public SemanticDefinitionClient(
            SkillClients clients,
            @Value("${agent.services.ontology:http://127.0.0.1:18182}") String baseUrl) {
        this.clients = Objects.requireNonNull(clients);
        this.baseUrl = Objects.requireNonNull(baseUrl).replaceAll("/+$", "");
    }

    /**
     * 查询固定版本的定义投影。选择只表达"想看什么"，不参与授权；授权由 OR 按当前主体重新判定。
     */
    public Projection query(UUID releaseId, Map<String, Object> selection) {
        if (releaseId == null)
            throw new PlatformException(400, "SEMANTIC_REF_INVALID", "定义查询缺少固定版本");
        Map<String, Object> body = selection(selection);
        JsonNode data;
        try {
            data = clients.post(baseUrl + PATH.formatted(releaseId), body);
        } catch (PlatformException e) {
            // OR 的 503/SEMANTIC_SOURCE_UNAVAILABLE 是对外可重试的真实结论；SkillClients 对 5xx
            // 统一折成 502 依赖不可用，这里只把该折码还原为语义源不可用，不改 4xx 业务码。
            if (e.status() == 502 && "DEPENDENCY_UNAVAILABLE".equals(e.code()))
                throw new PlatformException(
                        503, "SEMANTIC_SOURCE_UNAVAILABLE", "本体定义投影暂不可用，请稍后重试");
            throw e;
        }
        if (!data.isObject()) throw malformed();
        String schemaVersion = text(data, "schemaVersion");
        String profile = text(data, "profile");
        if (!"1.0".equals(schemaVersion) || !"read-only".equals(profile)) throw malformed();
        Map<String, Object> releaseRef = releaseRef(data.get("releaseRef"), releaseId);
        JsonNode definitions = data.get("definitions");
        if (definitions == null || !definitions.isObject()) throw malformed();
        var normalized = new LinkedHashMap<String, List<Map<String, Object>>>();
        int total = 0;
        for (String name : KIND_NAMES) {
            JsonNode array = definitions.get(name);
            if (array == null || !array.isArray()) throw malformed();
            var entries = new ArrayList<Map<String, Object>>(array.size());
            for (JsonNode entry : array) {
                entries.add(definition(name, entry));
                total++;
            }
            normalized.put(name, List.copyOf(entries));
        }
        if (total > MAX_ENTRIES) throw malformed();
        Map<String, Object> counts = counts(data.get("counts"), normalized);
        boolean empty = bool(data, "emptyResult");
        boolean truncated = bool(data, "truncated");
        Map<String, Object> truncation = truncated ? truncation(data.get("truncation")) : null;
        String digest = text(data, "projectionDigest");
        if (!DIGEST.matcher(digest).matches()) throw malformed();
        return new Projection(
                releaseRef, normalized, counts, empty, truncated, truncation, digest);
    }

    private Map<String, Object> selection(Map<String, Object> raw) {
        if (raw == null) throw field("selection", "查询选择不能为空");
        for (String key : raw.keySet())
            if (!SELECTION_KEYS.contains(key)) throw field(key, "未登记的定义查询选择字段");
        var body = new LinkedHashMap<String, Object>();
        JsonNode kinds = node(raw.get("kinds"));
        if (kinds != null && !kinds.isNull()) {
            if (!kinds.isArray() || kinds.size() > KIND_NAMES.size()) throw field("kinds", "类型列表非法");
            var values = new ArrayList<String>();
            for (JsonNode kind : kinds) {
                if (!kind.isTextual() || !KIND_NAMES.contains(kind.asText()))
                    throw field("kinds", "未登记的定义类型");
                if (!values.contains(kind.asText())) values.add(kind.asText());
            }
            body.put("kinds", List.copyOf(values));
        }
        body.put("objectTypes", strings(raw.get("objectTypes"), "objectTypes", MAX_TYPES, 64));
        body.put("terms", strings(raw.get("terms"), "terms", MAX_TERMS, 64));
        body.put("intent", text(raw.get("intent"), "intent", 256));
        Object limit = raw.get("limit");
        if (limit != null) {
            if (!(limit instanceof Number number) || number.intValue() < 1 || number.intValue() > MAX_ENTRIES)
                throw field("limit", "定义条目上限必须为1到200的整数");
            body.put("limit", number.intValue());
        }
        body.values().removeIf(Objects::isNull);
        return body;
    }

    private static List<String> strings(Object value, String path, int max, int length) {
        if (value == null) return null;
        if (!(value instanceof List<?> raw)) throw field(path, "必须为字符串数组");
        if (raw.size() > max) throw field(path, "条目数量超过上限");
        var result = new ArrayList<String>(raw.size());
        for (Object item : raw) {
            if (!(item instanceof String text) || text.isBlank() || text.length() > length)
                throw field(path, "条目必须为非空字符串且不超过" + length + "字符");
            if (!result.contains(text)) result.add(text);
        }
        return List.copyOf(result);
    }

    private static String text(Object value, String path, int max) {
        if (value == null) return null;
        if (!(value instanceof String text) || text.isBlank() || text.length() > max)
            throw field(path, "必须为非空字符串且不超过" + max + "字符");
        return text;
    }

    private static JsonNode node(Object value) {
        return value == null ? null : Json.tree(value);
    }

    private Map<String, Object> releaseRef(JsonNode node, UUID releaseId) {
        if (node == null || !node.isObject()) throw malformed();
        if (!releaseId.toString().equals(text(node, "releaseId"))) throw malformed();
        String digest = text(node, "contentDigest");
        if (!DIGEST.matcher(digest).matches()) throw malformed();
        var ref = new LinkedHashMap<String, Object>();
        ref.put("spaceCode", text(node, "spaceCode"));
        ref.put("releaseId", releaseId.toString());
        ref.put("version", text(node, "version"));
        ref.put("contentDigest", digest);
        return ref;
    }

    private Map<String, Object> definition(String collection, JsonNode node) {
        if (node == null || !node.isObject()) throw malformed();
        JsonNode ref = node.get("ref");
        if (ref == null || !ref.isObject()) throw malformed();
        if (!REF_KINDS.get(collection).equals(text(ref, "kind"))) throw malformed();
        text(ref, "id");
        text(ref, "name");
        return Json.mapper().convertValue(node, Map.class);
    }

    private Map<String, Object> counts(
            JsonNode node, Map<String, List<Map<String, Object>>> definitions) {
        if (node == null || !node.isObject()) throw malformed();
        var counts = new LinkedHashMap<String, Object>();
        for (String name : KIND_NAMES) {
            JsonNode value = node.get(name);
            if (value == null || !value.canConvertToInt() || value.asInt() < 0) throw malformed();
            if (value.asInt() != definitions.get(name).size()) throw malformed();
            counts.put(name, value.asInt());
        }
        return counts;
    }

    private Map<String, Object> truncation(JsonNode node) {
        if (node == null || !node.isObject()) throw malformed();
        var truncation = new LinkedHashMap<String, Object>();
        truncation.put("reason", text(node, "reason"));
        for (String field : List.of("limit", "maxBytes")) {
            JsonNode value = node.get(field);
            if (value == null || !value.canConvertToInt() || value.asInt() < 1) throw malformed();
            truncation.put(field, value.asInt());
        }
        return truncation;
    }

    private static boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) throw malformed();
        return value.asBoolean();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw malformed();
        return value.asText();
    }

    /** 服务端已核对的固定版本投影；五类定义都保留类型化 ref，不携带任何源连接信息。 */
    public record Projection(
            Map<String, Object> releaseRef,
            Map<String, List<Map<String, Object>>> definitions,
            Map<String, Object> counts,
            boolean emptyResult,
            boolean truncated,
            Map<String, Object> truncation,
            String projectionDigest) {

        public int itemCount() {
            return counts.values().stream()
                    .mapToInt(value -> value instanceof Number number ? number.intValue() : 0)
                    .sum();
        }

        public Map<String, Object> toMap() {
            var map = new LinkedHashMap<String, Object>();
            map.put("releaseRef", releaseRef);
            map.put("definitions", definitions);
            map.put("counts", counts);
            map.put("emptyResult", emptyResult);
            map.put("truncated", truncated);
            if (truncation != null) map.put("truncation", truncation);
            map.put("projectionDigest", projectionDigest);
            return map;
        }
    }

    private static PlatformException field(String path, String message) {
        return new PlatformException(400, "SEMANTIC_FIELD_OUT_OF_RANGE", path + ": " + message);
    }

    private static PlatformException malformed() {
        return new PlatformException(
                502, "DEPENDENCY_UNAVAILABLE", "本体服务返回的定义投影不完整或口径不一致");
    }
}
