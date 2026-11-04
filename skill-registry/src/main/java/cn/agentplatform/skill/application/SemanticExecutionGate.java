package cn.agentplatform.skill.application;

import cn.agentplatform.common.PlatformException;
import cn.agentplatform.skill.infra.SemanticDecisionClient;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * SEM-07.1：实际分发前的语义条件复核。
 *
 * <p>条件与版本只从“调用归属”的冻结技能契约读取：{@code execution.semanticConditions}
 * 在发布时固化，调用请求里的同名参数一律不采信。声明存在即强制复核，未装配/未绑定版本/
 * 依赖不可达都必须拒绝，不能把“没检查”降级成“通过”。
 *
 * <p>本类不产生授权 token、不改变幂等键或 actionPlan：只把 OR 的 condition@1.0 结论翻译成
 * 分发前失败。NOT_HOLDS 与 UNKNOWN 分开报错，调用方不能用重试把“无法确认”变成“成立”。
 */
@Service
public class SemanticExecutionGate {
    private static final Set<String> KINDS = Set.of("EXISTS", "RELATION", "STATE", "VERSION", "RULE");
    private static final Set<String> DECLARATION_KEYS =
            Set.of("releaseId", "opCode", "bindingId", "conditions");
    private static final Set<String> CONDITION_KEYS = Set.of("conditionKind", "targetRef", "expected");
    private static final Set<String> TARGET_KEYS = Set.of("refKind", "spaceCode", "objectType", "stableId");
    private static final int MAX_CONDITIONS = 16;

    private final SemanticDecisionClient decisions;

    public SemanticExecutionGate(SemanticDecisionClient decisions) {
        this.decisions = Objects.requireNonNull(decisions);
    }

    /** 契约是否声明了强制条件；装配判断与接线处的快速分支共用同一判定。 */
    public static boolean declared(Map<String, Object> contract) {
        Map<String, Object> execution = object(contract == null ? null : contract.get("execution"));
        return execution.get("semanticConditions") instanceof Map<?, ?> map && !map.isEmpty();
    }

    public void check(Map<String, Object> contract) {
        if (!declared(contract)) return;
        Map<String, Object> declaration = declaration(contract);
        UUID releaseId = release(contract, declaration);
        List<Map<String, Object>> conditions = conditions(declaration.get("conditions"));
        String opCode = text(declaration.get("opCode"));
        if (opCode.isBlank()) opCode = text(contract.get("code"));
        String bindingId = text(declaration.get("bindingId"));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("opCode", opCode);
        if (!bindingId.isBlank()) request.put("bindingId", bindingId);
        request.put("conditions", conditions);
        SemanticDecisionClient.ConditionResult result;
        try {
            result = decisions.evaluate(releaseId, request);
        } catch (PlatformException failure) {
            if (failure.status() == 502 && "DEPENDENCY_UNAVAILABLE".equals(failure.code()))
                throw new PlatformException(
                        503, "SEMANTIC_SOURCE_UNAVAILABLE", "条件检查依赖不可达，拒绝在无法核验时分发");
            throw failure;
        }
        if (result.allHold()) return;
        SemanticDecisionClient.Condition failure = result.firstFailure();
        String kind = failure == null ? "UNKNOWN" : failure.conditionKind();
        if (failure != null && "UNKNOWN".equals(failure.result()))
            throw new PlatformException(
                    409,
                    "SEMANTIC_CONDITION_UNKNOWN",
                    "分发前条件无法确认：" + kind + "（" + failure.unknownReason() + "）");
        throw new PlatformException(
                409, "SEMANTIC_CONDITION_BLOCKED", "分发前条件不成立：" + kind);
    }

    // ---- declaration parsing -------------------------------------------------

    private static Map<String, Object> declaration(Map<String, Object> contract) {
        Map<String, Object> execution = object(contract == null ? null : contract.get("execution"));
        Object raw = execution.get("semanticConditions");
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty())
            throw new PlatformException(409, "SEMANTIC_CONDITION_INVALID", "语义条件声明必须为非空对象");
        Map<String, Object> declaration = new LinkedHashMap<>();
        for (var entry : map.entrySet()) declaration.put(String.valueOf(entry.getKey()), entry.getValue());
        for (String key : declaration.keySet())
            if (!DECLARATION_KEYS.contains(key))
                throw new PlatformException(409, "SEMANTIC_CONDITION_INVALID", "条件声明含未登记字段：" + key);
        return declaration;
    }

    private static UUID release(Map<String, Object> contract, Map<String, Object> declaration) {
        Map<String, Object> source = object(contract.get("importSource"));
        String pinned = text(source.get("releaseId"));
        String declaredRelease = text(declaration.get("releaseId"));
        String value = pinned.isBlank() ? declaredRelease : pinned;
        if (value.isBlank())
            throw new PlatformException(
                    503, "SEMANTIC_CONDITION_UNAVAILABLE", "技能未绑定可核对的冻结版本，拒绝分发");
        try {
            UUID releaseId = UUID.fromString(value);
            if (!pinned.isBlank() && !declaredRelease.isBlank() && !pinned.equals(declaredRelease))
                throw new PlatformException(
                        409, "SEMANTIC_CONDITION_INVALID", "条件声明版本与技能冻结版本不一致");
            return releaseId;
        } catch (IllegalArgumentException invalid) {
            throw new PlatformException(409, "SEMANTIC_CONDITION_INVALID", "条件声明的版本不是UUID");
        }
    }

    private static List<Map<String, Object>> conditions(Object raw) {
        if (!(raw instanceof List<?> items) || items.isEmpty())
            throw new PlatformException(409, "SEMANTIC_CONDITION_INVALID", "条件声明必须为非空数组");
        if (items.size() > MAX_CONDITIONS)
            throw new PlatformException(409, "SEMANTIC_CONDITION_INVALID", "条件声明最多" + MAX_CONDITIONS + "条");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> map))
                throw new PlatformException(409, "SEMANTIC_CONDITION_INVALID", "条件项必须为对象");
            Map<String, Object> condition = new LinkedHashMap<>();
            for (var entry : map.entrySet()) condition.put(String.valueOf(entry.getKey()), entry.getValue());
            for (String key : condition.keySet())
                if (!CONDITION_KEYS.contains(key))
                    throw new PlatformException(409, "SEMANTIC_CONDITION_INVALID", "条件项含未登记字段：" + key);
            String kind = text(condition.get("conditionKind"));
            if (!KINDS.contains(kind))
                throw new PlatformException(409, "SEMANTIC_CONDITION_INVALID", "条件类型未登记：" + kind);
            Map<String, Object> target = object(condition.get("targetRef"));
            for (String key : target.keySet())
                if (!TARGET_KEYS.contains(key))
                    throw new PlatformException(409, "SEMANTIC_CONDITION_INVALID", "条件目标含未登记字段：" + key);
            if (!"space-object".equals(text(target.get("refKind")))
                    || text(target.get("spaceCode")).isBlank()
                    || text(target.get("objectType")).isBlank()
                    || text(target.get("stableId")).isBlank())
                throw new PlatformException(409, "SEMANTIC_CONDITION_INVALID", "条件目标必须是完整space-object引用");
            if (condition.get("expected") != null && !(condition.get("expected") instanceof Map<?, ?>))
                throw new PlatformException(409, "SEMANTIC_CONDITION_INVALID", "expected必须为对象");
            out.add(condition);
        }
        return out;
    }

    private static Map<String, Object> object(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        for (var entry : map.entrySet()) out.put(String.valueOf(entry.getKey()), entry.getValue());
        return out;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
