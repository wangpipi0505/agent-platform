package cn.agentplatform.engine.infra;

import cn.agentplatform.common.Json;
import cn.agentplatform.engine.domain.GoalOutcome;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * SEM-08：目标处置的持久存储。
 *
 * <p>只做“按 run+goal 追加/更新处置”，不开新的任务状态机，也不回写 run 终态。并发核验由
 * {@code on conflict ... where ... is distinct from} 收敛：内容未变时不产生新版本，从而让恢复
 * 重跑零重复写；内容变化才递增 revision。
 */
@Repository
public class GoalOutcomeStore {

    private final JdbcTemplate db;

    public GoalOutcomeStore(JdbcTemplate db) {
        this.db = db;
    }

    public Map<String, Object> find(long runId, String goalId) {
        return one(
                "select run_id,goal_id,criterion::text as criterion_json,outcome_state,"
                        + "readback_ref::text as readback_json,next_step,detail::text as detail_json,"
                        + "revision,updated_at from engine.goal_outcome where run_id=? and goal_id=?",
                runId,
                goalId);
    }

    public List<Map<String, Object>> list(long runId) {
        return db.queryForList(
                        "select run_id,goal_id,criterion::text as criterion_json,outcome_state,"
                                + "readback_ref::text as readback_json,next_step,detail::text as detail_json,"
                                + "revision,updated_at from engine.goal_outcome where run_id=? order by goal_id",
                        runId)
                .stream()
                .map(GoalOutcomeStore::view)
                .toList();
    }

    /**
     * 幂等写入。返回写入后的行；内容未变时返回既有行（revision 不变）。
     */
    public Map<String, Object> upsert(long runId, GoalOutcome outcome) {
        var updated =
                db.queryForList(
                        "insert into engine.goal_outcome(run_id,goal_id,criterion,outcome_state,readback_ref,next_step,detail)"
                                + " values(?,?,?::jsonb,?,?::jsonb,?,?::jsonb)"
                                + " on conflict (run_id,goal_id) do update set"
                                + " criterion=excluded.criterion,outcome_state=excluded.outcome_state,"
                                + " readback_ref=excluded.readback_ref,next_step=excluded.next_step,"
                                + " detail=excluded.detail,revision=engine.goal_outcome.revision+1,updated_at=now()"
                                + " where engine.goal_outcome.criterion is distinct from excluded.criterion"
                                + " or engine.goal_outcome.outcome_state is distinct from excluded.outcome_state"
                                + " or engine.goal_outcome.readback_ref is distinct from excluded.readback_ref"
                                + " or engine.goal_outcome.next_step is distinct from excluded.next_step"
                                + " or engine.goal_outcome.detail is distinct from excluded.detail"
                                + " returning run_id,goal_id,criterion::text as criterion_json,outcome_state,"
                                + " readback_ref::text as readback_json,next_step,detail::text as detail_json,revision,updated_at",
                        runId,
                        outcome.goalId(),
                        Json.write(outcome.criterion()),
                        outcome.state(),
                        outcome.readbackRef() == null ? null : Json.write(outcome.readbackRef()),
                        outcome.nextStep(),
                        Json.write(outcome.detail()));
        if (!updated.isEmpty()) return view(updated.getFirst());
        return find(runId, outcome.goalId());
    }

    public int revision(long runId, String goalId) {
        var row = find(runId, goalId);
        return row == null ? 0 : ((Number) row.get("revision")).intValue();
    }

    private Map<String, Object> one(String sql, Object... args) {
        var rows = db.queryForList(sql, args);
        return rows.isEmpty() ? null : view(rows.getFirst());
    }

    private static Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("runId", row.get("run_id"));
        view.put("goalId", row.get("goal_id"));
        view.put("criterion", Json.read(String.valueOf(row.get("criterion_json")), Map.class));
        view.put("outcomeState", row.get("outcome_state"));
        Object readback = row.get("readback_json");
        view.put("readbackRef", readback == null ? null : Json.read(String.valueOf(readback), Map.class));
        view.put("nextStep", row.get("next_step"));
        view.put("detail", Json.read(String.valueOf(row.get("detail_json")), Map.class));
        view.put("revision", row.get("revision"));
        view.put("updatedAt", row.get("updated_at"));
        return view;
    }
}
