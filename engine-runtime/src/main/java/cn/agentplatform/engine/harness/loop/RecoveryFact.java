package cn.agentplatform.engine.harness.loop;

import java.util.List;

/**
 * H1-8 恢复事实（契约桩 contracts/h1-6-h1-8-stubs.md §3）：RunRecoveryCoordinator 的只读
 * 恢复事实出口形状。手动/自动恢复共用同一写入路径（TASK_RESUMED / 恢复事件），本出口按
 * 事件事实推导并可重复取得（验收：重启后可取得同一恢复事实）。
 *
 * @param recoverySource MANUAL|AUTO
 * @param reasonCode     EXTERNAL_RESULT_UNKNOWN|LEASE_EXPIRED|EXECUTION_INTERRUPTED|MANUAL_REQUEST
 * @param reasonSummary  受控枚举映射的可展示摘要（读取时映射，不落库）
 * @param checkpoint     eventWatermark（会话水位，无会话回退 run 事件水位）+ runRefs
 * @param reusedSteps    已提交节点（复用事实）：stepId+resultDigest
 * @param blockedReasons 阻塞原因（WAITING 类节点摘要）
 */
public record RecoveryFact(
        String recoverySource,
        String reasonCode,
        String reasonSummary,
        Checkpoint checkpoint,
        List<ReusedStep> reusedSteps,
        List<String> blockedReasons) {

    public record Checkpoint(long eventWatermark, List<String> runRefs) {}

    public record ReusedStep(String stepId, String resultDigest) {}

    /** 角色投影（接口稿 §5.2）：USER 仅来源/原因/摘要；OPERATOR/ADMIN 全量。 */
    public UserView projectFor(boolean privileged) {
        if (privileged) return null; // 全量即本记录本身，调用方直接使用
        return new UserView(recoverySource, reasonCode, reasonSummary);
    }

    /** USER 视角投影载体（仅三字段）。 */
    public record UserView(String recoverySource, String reasonCode, String reasonSummary) {}
}
