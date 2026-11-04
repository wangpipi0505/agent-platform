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
 * OR condition@1.0 求值客户端（SEM-07.1）。
 *
 * <p>只按已冻结的 release 与声明调用 OR，不接收客户端同名字段，也不在本侧重算条件。
 * 响应逐字段核对：版本必须与请求同址、结论只允许三种、UNKNOWN 必须有原因、allHold 必须
 * 与逐条结论一致；任何畸形按依赖不可用拒绝，绝不把解析失败当成 HOLDS。
 */
@Component
public class SemanticDecisionClient {
    private static final String PATH = "/onto/releases/%s/semantic/conditions/evaluate";
    private static final Set<String> KINDS = Set.of("EXISTS", "RELATION", "STATE", "VERSION", "RULE");
    private static final Set<String> RESULTS = Set.of("HOLDS", "NOT_HOLDS", "UNKNOWN");
    private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");

    private final SkillClients clients;
    private final String baseUrl;

    public SemanticDecisionClient(
            SkillClients clients,
            @Value("${agent.services.ontology:http://127.0.0.1:18182}") String baseUrl) {
        this.clients = Objects.requireNonNull(clients);
        this.baseUrl = Objects.requireNonNull(baseUrl).replaceAll("/+$", "");
    }

    public ConditionResult evaluate(UUID releaseId, Map<String, Object> request) {
        if (releaseId == null || request == null)
            throw new PlatformException(400, "SEMANTIC_REF_INVALID", "条件求值请求不能为空");
        JsonNode data = clients.post(baseUrl + PATH.formatted(releaseId), request);
        if (!data.isObject()) throw malformed("条件响应不是对象");
        if (!"condition@1.0".equals(text(data, "profile"))
                || !"1.0".equals(text(data, "schemaVersion")))
            throw malformed("条件响应版本不受支持");
        JsonNode ref = data.path("releaseRef");
        if (!ref.isObject() || !releaseId.toString().equals(ref.path("releaseId").asText()))
            throw malformed("条件响应版本与请求不一致");
        if (ref.path("spaceCode").asText().isBlank()
                || ref.path("version").asText().isBlank()
                || !DIGEST.matcher(ref.path("contentDigest").asText()).matches())
            throw malformed("条件响应版本引用不完整");
        String checkedAt = text(data, "checkedAt");
        try {
            java.time.Instant.parse(checkedAt);
        } catch (RuntimeException ex) {
            throw malformed("条件响应检查时间不可解析");
        }
        JsonNode items = data.path("conditions");
        if (!items.isArray() || items.isEmpty() || items.size() > 16)
            throw malformed("条件响应条目数量不合法");
        List<Condition> conditions = new ArrayList<>();
        boolean expectedAll = true;
        for (JsonNode item : items) {
            if (!item.isObject()) throw malformed("条件条目不是对象");
            String kind = item.path("conditionKind").asText();
            String value = item.path("result").asText();
            if (!KINDS.contains(kind) || !RESULTS.contains(value))
                throw malformed("条件结论未登记");
            String reason = item.path("unknownReason").asText("");
            if ("UNKNOWN".equals(value) && reason.isBlank())
                throw malformed("UNKNOWN条件缺少原因");
            JsonNode target = item.path("targetRef");
            if (target.isObject() && !"space-object".equals(target.path("refKind").asText()))
                throw malformed("条件目标不是space-object");
            conditions.add(new Condition(kind, value, reason));
            expectedAll &= "HOLDS".equals(value);
        }
        if (data.path("allHold").isBoolean() && data.path("allHold").asBoolean() != expectedAll)
            throw malformed("allHold与逐条结论不一致");
        @SuppressWarnings("unchecked")
        Map<String, Object> releaseRef = Json.read(data.path("releaseRef").toString(), Map.class);
        return new ConditionResult(releaseRef, expectedAll, List.copyOf(conditions));
    }

    private static PlatformException malformed(String message) {
        return new PlatformException(
                502, "DEPENDENCY_UNAVAILABLE", "OR条件响应无法核对：" + message);
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText("");
    }

    public record Condition(String conditionKind, String result, String unknownReason) {}

    public record ConditionResult(
            Map<String, Object> releaseRef, boolean allHold, List<Condition> conditions) {
        public Condition firstFailure() {
            for (Condition condition : conditions)
                if (!"HOLDS".equals(condition.result())) return condition;
            return null;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("releaseRef", releaseRef);
            out.put("allHold", allHold);
            List<Map<String, Object>> items = new ArrayList<>();
            for (Condition condition : conditions) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("conditionKind", condition.conditionKind());
                item.put("result", condition.result());
                if (!condition.unknownReason().isBlank()) item.put("unknownReason", condition.unknownReason());
                items.add(item);
            }
            out.put("conditions", items);
            return out;
        }
    }
}
