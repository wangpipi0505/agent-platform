package cn.agentplatform.engine.domain;

import cn.agentplatform.common.PlatformException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SEM-08：单个业务目标的处置事实。
 *
 * <p>不变量：
 *
 * <ul>
 *   <li>执行终态、业务效果、目标处置是三类事实；本记录只描述“按冻结判据读回后，目标处于什么
 *       处置”，绝不因为 run=SUCCEEDED 或 HTTP 200 写成 {@code ACHIEVED}。
 *   <li>{@code ACHIEVED} 必须带可核验的 call 证据；{@code PENDING}/{@code UNKNOWN} 必须给出
 *       下一步或原因，避免把“没读到”混成“已完成”。
 *   <li>判据文本来自受理快照，本记录不改写它，只做投影。
 * </ul>
 */
public record GoalOutcome(
        String goalId,
        Map<String, Object> criterion,
        String state,
        Map<String, Object> readbackRef,
        String nextStep,
        Map<String, Object> detail) {

    public static final String ACHIEVED = "ACHIEVED";
    public static final String NOT_ACHIEVED = "NOT_ACHIEVED";
    public static final String PENDING = "PENDING";
    public static final String FAILED = "FAILED";
    public static final String UNKNOWN = "UNKNOWN";
    public static final List<String> STATES =
            List.of(ACHIEVED, NOT_ACHIEVED, PENDING, FAILED, UNKNOWN);

    public static final int MAX_GOAL_ID = 64;
    public static final int MAX_NEXT_STEP = 256;

    public GoalOutcome {
        if (goalId == null || goalId.isBlank() || goalId.length() > MAX_GOAL_ID)
            throw invalid("goalId必须为1..64字符");
        if (!STATES.contains(state)) throw invalid("未知的目标处置状态: " + state);
        criterion = criterion == null ? Map.of() : Map.copyOf(criterion);
        readbackRef = readbackRef == null ? null : Map.copyOf(readbackRef);
        detail = detail == null ? Map.of() : Map.copyOf(detail);
        if (nextStep != null && nextStep.length() > MAX_NEXT_STEP)
            throw invalid("nextStep超过256字符");
        if (ACHIEVED.equals(state) && readbackRef == null) throw invalid("ACHIEVED必须带readbackRef");
        if ((PENDING.equals(state) || UNKNOWN.equals(state)) && (nextStep == null || nextStep.isBlank()))
            throw invalid(state + "必须给出下一步");
    }

    public Map<String, Object> toMap() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("goalId", goalId);
        view.put("criterion", criterion);
        view.put("outcomeState", state);
        if (readbackRef != null) view.put("readbackRef", readbackRef);
        if (nextStep != null) view.put("nextStep", nextStep);
        view.put("detail", detail);
        return view;
    }

    private static PlatformException invalid(String message) {
        return new PlatformException(500, "GOAL_CRITERION_INVALID", message);
    }
}
