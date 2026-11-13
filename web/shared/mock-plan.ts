/** T2-14 mock 契约：计划域——修订 CAS/确认凭据/execute 门禁（contracts/task-states.md §5，接口稿 §5.2）。仅 demo 装载。 */
import { type MockTaskSeed, type TaskMode } from "./mock-task"

type PlanTask = MockTaskSeed & { plan: NonNullable<MockTaskSeed["plan"]>; mode: TaskMode }
const plans = new Map<string, PlanTask>()
export const seedPlanTask = (seed: PlanTask) => plans.set(seed.taskId, seed)
export const resetPlans = () => plans.clear()

/** F4-3 接线：plans 域内该任务的权威计划视图（confirm/execute 的变更落在本域）。
 *  taskDetail 以此合并 plan/confirmation——消费方（助手端计划卡）读单一任务详情即可取到最新凭据；
 *  未登记（未 seed 的任务）返回 undefined，任务详情退回自带 plan。 */
export const planAuthorityOf = (taskId: string) => plans.get(taskId)?.plan

/** POST plans：baseRevision CAS——过期 409 PLAN_REVISION_CONFLICT；修订追加，旧确认凭据不继承。 */
export function appendPlanRevision(taskId: string, req: { requestId: string; baseRevision: number }):
  { planId: string; revision: number; digest: string; validationStatus: string } | { error: string; httpStatus: number } {
  const task = plans.get(taskId)
  if (!task) return { error: "TASK_NOT_FOUND", httpStatus: 404 }
  if (task.plan.revision !== req.baseRevision) return { error: "PLAN_REVISION_CONFLICT", httpStatus: 409 }
  task.plan = { ...task.plan, revision: task.plan.revision + 1, digest: `dg${task.plan.revision + 1}`,
    confirmation: { state: "UNCONFIRMED" }, validationStatus: "VALID" }
  return { planId: task.plan.planId, revision: task.plan.revision, digest: task.plan.digest, validationStatus: "VALID" }
}

/** POST confirm：digest 必须等于该版本固化摘要；同号同参重放返回原凭据（简化：按 digest 判定）。 */
export function confirmPlan(taskId: string, req: { requestId: string; digest: string; confirmedBy: string }):
  { planId: string; revision: number; confirmSeq: number; confirmedBy: string; expiresAt: string; digest: string }
  | { error: string; httpStatus: number } {
  const task = plans.get(taskId)
  if (!task) return { error: "TASK_NOT_FOUND", httpStatus: 404 }
  if (task.plan.digest !== req.digest) return { error: "PLAN_DIGEST_CHANGED", httpStatus: 409 }
  task.plan.confirmation = { state: "CONFIRMED_VALID", confirmedBy: req.confirmedBy, expiresAt: "2026-11-13T18:00:00Z", seq: (task.plan.confirmation.seq ?? 0) + 1 }
  return { planId: task.plan.planId, revision: task.plan.revision, confirmSeq: task.plan.confirmation.seq!, confirmedBy: req.confirmedBy, expiresAt: task.plan.confirmation.expiresAt!, digest: req.digest }
}

/** POST execute：受理校验五项的 mock 主干——无有效凭据 → 409 PLAN_NOT_CONFIRMED（提示重新确认）。 */
export function executePlan(taskId: string, req: { requestId: string; planId: string; revision: number; digest: string }):
  { executionTaskId: string; pinnedRevision: number; pinnedDigest: string } | { error: string; httpStatus: number; userMessage: string } {
  const task = plans.get(taskId)
  if (!task) return { error: "TASK_NOT_FOUND", httpStatus: 404, userMessage: "任务不存在或不可见" }
  if (task.plan.digest !== req.digest) return { error: "PLAN_DIGEST_CHANGED", httpStatus: 409, userMessage: "计划版本已变化，请基于最新版本重新操作" }
  if (task.plan.validationStatus !== "VALID") return { error: "PLAN_NOT_VALID", httpStatus: 409, userMessage: "所选版本校验未通过" }
  if (task.plan.confirmation.state !== "CONFIRMED_VALID")
    return { error: "PLAN_NOT_CONFIRMED", httpStatus: 409, userMessage: "所选版本未确认或凭据已过期，请重新确认" }
  return { executionTaskId: `task-demo-exec-${req.revision}`, pinnedRevision: req.revision, pinnedDigest: req.digest }
}
