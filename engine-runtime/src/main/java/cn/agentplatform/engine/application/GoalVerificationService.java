package cn.agentplatform.engine.application;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.engine.domain.GoalOutcome;
import cn.agentplatform.engine.infra.GoalOutcomeStore;
import cn.agentplatform.engine.infra.TraceStore;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * SEM-08：业务目标状态的读回核验。
 *
 * <p>判据来自受理时冻结的 {@code run.snapshot.config.goals}，验证动作必须是同一发布配置中
 * 声明的只读操作。核验只读 {@code engine.tool_call} 的既有事实，不创建新的调用、不重发、也不
 * 改写 run 终态；因此超时后源已成功的调用可以直接读回，恢复重跑零重复写。
 *
 * <p>三类事实严格分开：run 终态、工具回执、目标处置。{@code ACHIEVED} 只由“成功回执 + 预期
 * 业务状态/产物命中”给出；HTTP 200、工具成功但业务未落库、模型自述都不会被映射为完成。
 */
@Service
public class GoalVerificationService {

    /** 单次核验的硬预算：目标数与每目标回执扫描数都有界，防止运行被无限展开。 */
    static final int MAX_GOALS = 32;

    static final int MAX_CALL_SCAN = 50;

    /** 没有成功回执时，等待/未知的终止条件由 run 终态决定，不自行轮询。 */
    private static final Set<String> TERMINAL_RUN_STATES = Set.of("SUCCEEDED", "FAILED", "CANCELLED");

    /** 明确“被拒绝/参数不合法”的失败不冒充 UNKNOWN，应显示为未达成并可修正。 */
    private static final Set<String> REJECTED_SEMANTICS =
            Set.of(
                    "NO_PERMISSION",
                    "PARAM_ERROR",
                    "CONTRACT_INVALID",
                    "CALL_CONTRACT_REQUIRED",
                    "ENDPOINT_FORBIDDEN",
                    "GUARD_ATTR_UNRESOLVED");

    private final TraceStore store;
    private final GoalOutcomeStore outcomes;

    public GoalVerificationService(TraceStore store, GoalOutcomeStore outcomes) {
        this.store = store;
        this.outcomes = outcomes;
    }

    /** 终止/取消后由运行协调器调用；非终态返回空摘要，不主动轮询。 */
    public Map<String, Object> verifyIfTerminal(long runId) {
        var run = run(runId);
        String state = String.valueOf(run.get("status"));
        if (!TERMINAL_RUN_STATES.contains(state))
            return Map.of("runId", runId, "runStatus", state, "goals", List.of(), "skipped", true);
        return verify(runId);
    }

    public List<Map<String, Object>> list(long runId) {
        return outcomes.list(runId);
    }

    /**
     * 幂等核验：同一 run 重复调用只更新变化的处置，不产生新 call，也不改 run 状态。
     */
    public Map<String, Object> verify(long runId) {
        var run = run(runId);
        JsonNode snapshot = TraceStore.json(run.get("snapshot"));
        JsonNode declared = snapshot.path("config").path("goals");
        List<Map<String, Object>> goals = new ArrayList<>();
        JsonNode tools = snapshot.path("config").path("tools");
        int total = declared.isArray() ? declared.size() : 0;
        int considered = Math.min(total, MAX_GOALS);
        for (int i = 0; i < considered; i++) {
            JsonNode goal = declared.get(i);
            GoalOutcome outcome = evaluate(run, goal, tools);
            goals.add(outcomes.upsert(runId, outcome));
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String state : GoalOutcome.STATES) counts.put(state, 0);
        for (var goal : goals) {
            String state = String.valueOf(goal.get("outcomeState"));
            counts.computeIfPresent(state, (k, v) -> v + 1);
        }
        Map<String, Object> budget = new LinkedHashMap<>();
        budget.put("maxGoals", MAX_GOALS);
        budget.put("declaredGoals", total);
        budget.put("verifiedGoals", considered);
        budget.put("truncated", total > considered);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("runId", runId);
        summary.put("runStatus", run.get("status"));
        summary.put("goals", goals);
        summary.put("counts", counts);
        summary.put("complete", considered > 0 && counts.get(GoalOutcome.ACHIEVED) == considered);
        summary.put("budget", budget);
        return summary;
    }

    // ---- evaluation ---------------------------------------------------------

    private GoalOutcome evaluate(Map<String, Object> run, JsonNode goal, JsonNode tools) {
        String goalId = goal.path("goalId").asText("");
        JsonNode criterion = goal.path("criterion");
        if (goalId.isBlank() || goalId.length() > GoalOutcome.MAX_GOAL_ID)
            throw new PlatformException(500, "GOAL_CRITERION_INVALID", "目标缺少合法goalId");
        if (!criterion.isObject())
            throw new PlatformException(500, "GOAL_CRITERION_INVALID", "目标缺少criterion");
        JsonNode readback = criterion.path("readback");
        String skillCode = readback.path("skillCode").asText("");
        if (skillCode.isBlank())
            throw new PlatformException(
                    500, "GOAL_CRITERION_INVALID", "目标" + goalId + "缺少只读验证操作skillCode");

        JsonNode tool = approvedTool(tools, skillCode);
        if (tool == null)
            return outcome(
                    goalId,
                    criterion,
                    GoalOutcome.UNKNOWN,
                    null,
                    "验证操作未在发布配置中登记为只读，不能核验目标",
                    Map.of("reason", "READBACK_NOT_APPROVED", "skillCode", skillCode));

        Map<String, Object> call = latestCall(run, readback);
        if (call == null) {
            // 终态运行没有验证回执：目标未达成是事实，不能留 PENDING 或 UNKNOWN 等下一次；
            // 尚未终态才允许等待恢复后的同 run 调用。
            boolean terminal = TERMINAL_RUN_STATES.contains(String.valueOf(run.get("status")));
            return outcome(
                    goalId,
                    criterion,
                    terminal ? GoalOutcome.NOT_ACHIEVED : GoalOutcome.PENDING,
                    null,
                    terminal ? "运行已结束，未执行只读验证操作，目标未达成" : "等待只读验证操作执行后读回",
                    Map.of("reason", "READBACK_NOT_STARTED", "skillCode", skillCode));
        }

        long callId = ((Number) call.get("id")).longValue();
        String status = String.valueOf(call.get("status"));
        JsonNode result = call.get("result_json") == null ? null : Json.read(String.valueOf(call.get("result_json")));
        JsonNode predicate = criterion.path("predicate");
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("callId", callId);
        detail.put("callStatus", status);
        detail.put("skillCode", skillCode);
        if (predicate.isObject() && predicate.has("path")) detail.put("predicate", predicate.path("path").asText());

        if ("SUCCEEDED".equals(status)) {
            Match match = match(result, predicate);
            detail.put("matched", match.matched());
            if (match.value() != null) detail.put("readbackValue", match.value());
            if (match.matched()) {
                if (match.value() == null && predicate.path("path").asText().isBlank())
                    return outcome(goalId, criterion, GoalOutcome.UNKNOWN, null,
                            "判据缺少可读回字段，不能确认为完成", detail);
                return outcome(goalId, criterion, GoalOutcome.ACHIEVED,
                        Map.of("evidenceKind", "call", "callId", callId), null, detail);
            }
            return outcome(goalId, criterion, GoalOutcome.NOT_ACHIEVED, null,
                    "读回显示业务尚未达到判据，需继续/重试",
                    with(detail, "reason", match.reason()));
        }
        if ("FAILED".equals(status)) {
            String semantic = call.get("error_semantic") == null ? "" : String.valueOf(call.get("error_semantic"));
            detail.put("errorSemantic", semantic);
            if (REJECTED_SEMANTICS.contains(semantic))
                return outcome(goalId, criterion, GoalOutcome.NOT_ACHIEVED, null,
                        "验证操作被拒绝，修正授权/参数后重新验证", detail);
            return outcome(goalId, criterion, GoalOutcome.FAILED, null,
                    "验证操作失败，需修复后重试", detail);
        }
        if ("UNKNOWN".equals(status))
            return outcome(goalId, criterion, GoalOutcome.UNKNOWN, null,
                    "沿用既有UNKNOWN对账，等待源侧回执", detail);
        if ("ABANDONED".equals(status)) {
            if (TERMINAL_RUN_STATES.contains(String.valueOf(run.get("status"))))
                return outcome(goalId, criterion, GoalOutcome.NOT_ACHIEVED, null,
                        "验证操作未完成；目标未达成，如仍需完成请重新发起", detail);
            return outcome(goalId, criterion, GoalOutcome.PENDING, null,
                    "验证操作已中止，等待重新调度", detail);
        }
        return outcome(goalId, criterion, GoalOutcome.PENDING, null,
                "等待验证操作完成（" + status + "）", detail);
    }

    private static JsonNode approvedTool(JsonNode tools, String skillCode) {
        if (!tools.isArray()) return null;
        for (JsonNode tool : tools) {
            if (!skillCode.equals(tool.path("code").asText())) continue;
            JsonNode contract = tool.path("contract").isObject() ? tool.path("contract") : tool;
            JsonNode execution = contract.path("execution");
            boolean readonly = execution.path("readonly").asBoolean(false);
            boolean sideEffect = execution.path("sideEffect").asBoolean(false);
            if (readonly && !sideEffect) return tool;
        }
        return null;
    }

    private Map<String, Object> latestCall(Map<String, Object> run, JsonNode readback) {
        long runId = ((Number) run.get("id")).longValue();
        String skillCode = readback.path("skillCode").asText();
        String callId = readback.path("callId").isIntegralNumber()
                ? Long.toString(readback.path("callId").asLong())
                : readback.path("callId").asText("");
        if (!callId.isBlank() && callId.matches("[0-9]+"))
            return first(
                    "select id,status,error_semantic,result::text as result_json from engine.tool_call"
                            + " where run_id=? and skill_code=? and id=? limit 1",
                    runId,
                    skillCode,
                    Long.parseLong(callId));
        return first(
                "select id,status,error_semantic,result::text as result_json from engine.tool_call"
                        + " where run_id=? and skill_code=? order by id desc limit 1",
                runId,
                skillCode);
    }

    private Map<String, Object> first(String sql, Object... args) {
        var rows = store.db().queryForList(sql, args);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Map<String, Object> run(long runId) {
        var rows = store.db().queryForList("select id,status,snapshot::text as snapshot from engine.run where id=?", runId);
        if (rows.isEmpty()) throw new PlatformException(404, "RUN_NOT_FOUND", "运行不存在");
        return rows.getFirst();
    }

    // ---- predicate ----------------------------------------------------------

    private record Match(boolean matched, Object value, String reason) {}

    private static Match match(JsonNode result, JsonNode predicate) {
        if (result == null) return new Match(false, null, "NO_RESULT");
        if (!predicate.isObject()) return new Match(false, null, "NO_PREDICATE");
        String path = predicate.path("path").asText("");
        if (path.isBlank()) return new Match(false, null, "NO_PREDICATE_PATH");
        JsonNode value = path(result, path);
        if (predicate.has("equals")) {
            if (value == null || value.isMissingNode())
                return new Match(false, null, "VALUE_MISSING");
            Object actual = Json.mapper().convertValue(value, Object.class);
            Object expected = Json.mapper().convertValue(predicate.get("equals"), Object.class);
            boolean same = java.util.Objects.equals(normalize(actual), normalize(expected));
            return new Match(same, actual, same ? "MATCHED" : "VALUE_MISMATCH");
        }
        if (predicate.path("nonEmpty").asBoolean(false)) {
            boolean present = value != null && !value.isMissingNode();
            boolean nonEmpty = present
                    && !(value.isArray() && value.isEmpty())
                    && !(value.isObject() && value.isEmpty())
                    && !(value.isTextual() && value.asText().isBlank());
            return new Match(nonEmpty, present ? Json.mapper().convertValue(value, Object.class) : null,
                    nonEmpty ? "MATCHED" : "EMPTY");
        }
        if (predicate.path("exists").asBoolean(false)) {
            boolean present = value != null && !value.isMissingNode() && !value.isNull();
            return new Match(present, present ? Json.mapper().convertValue(value, Object.class) : null,
                    present ? "MATCHED" : "ABSENT");
        }
        return new Match(false, null, "NO_PREDICATE");
    }

    /** 数字按十进制值比较，避免 1 与 1.0 被当作不同业务状态。 */
    private static Object normalize(Object value) {
        return value instanceof Number number ? new java.math.BigDecimal(number.toString()).stripTrailingZeros() : value;
    }

    /** 受限路径：点号分段 + [n] 下标，不执行表达式。 */
    static JsonNode path(JsonNode root, String path) {
        JsonNode node = root;
        for (String part : path.split("\\.")) {
            String name = part;
            int bracket = part.indexOf('[');
            if (bracket >= 0) {
                name = part.substring(0, bracket);
                if (!name.isEmpty()) node = node.path(name);
                String rest = part.substring(bracket);
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[(\\d+)]").matcher(rest);
                while (m.find()) {
                    if (!node.isArray()) return null;
                    int index = Integer.parseInt(m.group(1));
                    if (index < 0 || index >= node.size()) return null;
                    node = node.get(index);
                }
                continue;
            }
            node = node.path(name);
        }
        return node;
    }

    private static GoalOutcome outcome(
            String goalId,
            JsonNode criterion,
            String state,
            Map<String, Object> readback,
            String nextStep,
            Map<String, Object> detail) {
        return new GoalOutcome(
                goalId,
                Json.mapper().convertValue(criterion, Map.class),
                state,
                readback,
                nextStep,
                detail);
    }

    private static Map<String, Object> with(Map<String, Object> detail, String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(detail);
        copy.put(key, value);
        return copy;
    }
}
