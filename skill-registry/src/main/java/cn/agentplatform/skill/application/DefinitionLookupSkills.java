package cn.agentplatform.skill.application;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.skill.infra.SemanticDefinitionClient;
import cn.agentplatform.skill.infra.SkillAdapter;
import cn.agentplatform.skill.infra.SkillAdapters;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 只读定义查询技能（SEM-06.2 AP 侧）。
 *
 * <p>技能只做"把本次运行固定的 releaseId 与选择交给 OR 并投影结果"，不解释定义、不把
 * terms/intent 当授权条件、也不在 AP 侧缓存投影正文。同一 run 内第一次查询会锁定 releaseId，
 * 之后的追问必须用同一版本；每次追问仍由 OR 按当前权限重新投影，撤权后旧缓存不会在 AP 命中。
 *
 * <p>预算约束按 run 计数：单 run 最多 {@value #MAX_CALLS} 次查询、累计 {@value #MAX_ITEMS} 条定义。
 * 超限是明确的 400 语义错误，不静默截断成"看起来查完了"。
 */
@Component
public class DefinitionLookupSkills implements SkillAdapter {
    /** 运行层适配器类型；声明层能力包 schema 的枚举扩容由契约治理单独决定。 */
    public static final String TYPE = "SEMANTIC_DEFINITION";

    static final int MAX_CALLS = 8;
    static final int MAX_ITEMS = 400;
    private static final int MAX_RUNS = 4096;
    private static final Set<String> ARGUMENTS =
            Set.of("releaseId", "kinds", "objectTypes", "terms", "intent", "limit");

    private final SemanticDefinitionClient definitions;
    /** 进程内按 run 的锁定与预算；只保存版本指针与计数，不保存任何投影正文。 */
    private final ConcurrentHashMap<String, Run> runs = new ConcurrentHashMap<>();

    public DefinitionLookupSkills(SemanticDefinitionClient definitions) {
        this.definitions = Objects.requireNonNull(definitions);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Set<String> supportedKinds() {
        return Set.of("SYNC");
    }

    @Override
    public String defaultKind() {
        return "SYNC";
    }

    @Override
    public SkillAdapters.Raw invoke(
            SkillAdapters host,
            Map<String, Object> contract,
            Map<String, Object> arguments,
            String key,
            long callId,
            boolean queryOnly,
            String runId,
            long expectedAttempt) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        for (String name : args.keySet())
            if (!ARGUMENTS.contains(name))
                throw field(name, "未登记的定义查询参数");
        UUID releaseId = releaseId(contract, args);
        Run run = run(runId, releaseId);
        SemanticDefinitionClient.Projection projection =
                definitions.query(releaseId, selection(args));
        run.record(projection.itemCount());

        Map<String, Object> result = new LinkedHashMap<>(projection.toMap());
        result.put("budget", run.budget());
        String summary = summary(projection);
        return new SkillAdapters.Raw(Json.tree(result), List.of(), summary, Json.tree(null), "");
    }

    /**
     * 固定版本来源优先级：技能契约 execution.releaseId（注册/发布时冻结）高于调用参数。
     * 两者都出现时必须一致，否则拒绝，避免同一技能被参数改写成另一个版本。
     */
    private static UUID releaseId(Map<String, Object> contract, Map<String, Object> args) {
        UUID pinned = uuid(execution(contract).get("releaseId"), "技能契约");
        UUID requested = uuid(args.get("releaseId"), "调用参数");
        if (pinned == null && requested == null)
            throw new PlatformException(
                    409, "SEMANTIC_REF_INVALID", "定义查询技能未绑定固定版本，请经登记门禁补全 releaseId");
        if (pinned != null && requested != null && !pinned.equals(requested))
            throw new PlatformException(
                    409, "SEMANTIC_REF_INVALID", "调用参数中的版本与技能固定版本不一致");
        return pinned != null ? pinned : requested;
    }

    private static UUID uuid(Object value, String source) {
        if (value == null) return null;
        if (!(value instanceof String text)) throw field("releaseId", source + "版本必须为 UUID");
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw field("releaseId", source + "版本必须为 UUID");
        }
    }

    private static Map<String, Object> execution(Map<String, Object> contract) {
        Object value = contract == null ? null : contract.get("execution");
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        var result = new LinkedHashMap<String, Object>();
        map.forEach((k, v) -> result.put(Objects.toString(k), v));
        return result;
    }

    private static Map<String, Object> selection(Map<String, Object> args) {
        var selection = new LinkedHashMap<String, Object>();
        for (String key : List.of("kinds", "objectTypes", "terms", "intent", "limit"))
            if (args.get(key) != null) selection.put(key, args.get(key));
        return selection;
    }

    /** 同一 run 只允许一个固定版本；这是运行内一致性，不是权限判断，权限仍由 OR 每次复核。 */
    private Run run(String runId, UUID releaseId) {
        String key = runId == null || runId.isBlank() ? "call-scope" : runId;
        if (runs.size() > MAX_RUNS) runs.clear();
        Run run = runs.computeIfAbsent(key, ignored -> new Run());
        run.pin(releaseId);
        return run;
    }

    private static String summary(SemanticDefinitionClient.Projection projection) {
        var counts = projection.counts();
        String countsText =
                "对象%s/属性%s/关系%s/规则%s/动作%s"
                        .formatted(
                                counts.getOrDefault("objects", 0),
                                counts.getOrDefault("properties", 0),
                                counts.getOrDefault("relations", 0),
                                counts.getOrDefault("rules", 0),
                                counts.getOrDefault("actions", 0));
        if (projection.emptyResult()) return "固定版本定义查询未命中：" + countsText;
        String suffix = projection.truncated() ? "（结果已按预算截断）" : "";
        return "固定版本定义查询：" + countsText + suffix;
    }

    private static PlatformException field(String path, String message) {
        return new PlatformException(400, "SEMANTIC_FIELD_OUT_OF_RANGE", path + ": " + message);
    }

    /** 单 run 的固定版本与预算；synchronized 保证并发追问不会超发。 */
    static final class Run {
        private String releaseId;
        private int calls;
        private int items;

        synchronized void pin(UUID releaseId) {
            String value = releaseId.toString();
            if (this.releaseId == null) this.releaseId = value;
            else if (!this.releaseId.equals(value))
                throw new PlatformException(
                        409, "SEMANTIC_REF_INVALID", "同一运行内的定义查询必须使用固定版本");
            if (calls >= MAX_CALLS)
                throw new PlatformException(
                        400, "SEMANTIC_FIELD_OUT_OF_RANGE", "本次运行的定义查询次数已用尽");
        }

        synchronized void record(int count) {
            calls++;
            items += count;
            if (items > MAX_ITEMS)
                throw new PlatformException(
                        400, "SEMANTIC_FIELD_OUT_OF_RANGE", "本次运行的定义条目预算已用尽");
        }

        synchronized Map<String, Object> budget() {
            var budget = new LinkedHashMap<String, Object>();
            budget.put("callsUsed", calls);
            budget.put("maxCalls", MAX_CALLS);
            budget.put("itemsUsed", items);
            budget.put("maxItems", MAX_ITEMS);
            return budget;
        }
    }

    /** 登记模板只产草稿：releaseId 由发布门禁前的登记步骤补全，本类不自动发布。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> template() {
        try (var stream =
                DefinitionLookupSkills.class.getResourceAsStream(
                        "/skills/semantic-definition-lookup.json")) {
            if (stream == null) throw new IllegalStateException("定义查询技能登记模板缺失");
            return Json.read(
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8), Map.class);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("定义查询技能登记模板无法读取", e);
        }
    }
}
