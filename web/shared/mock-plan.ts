/** T2-14 mock 契约：计划域——修订 CAS/确认凭据/execute 门禁（contracts/task-states.md §5，接口稿 §5.2）。仅 demo 装载。 */
import { type MockTaskSeed, type TaskMode } from "./mock-task"

type PlanTask = MockTaskSeed & { plan: NonNullable<MockTaskSeed["plan"]>; mode: TaskMode }
const plans = new Map<string, PlanTask>()
export const seedPlanTask = (seed: PlanTask) => plans.set(seed.taskId, seed)
export const resetPlans = () => plans.clear()

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
  if (!task) return { error: "TASK_NOT_FOUND", httpStatus: 404 }
  if (task.plan.digest !== req.digest) return { error: "PLAN_DIGEST_CHANGED", httpStatus: 409 }
  if (task.plan.validationStatus !== "VALID") return { error: "PLAN_NOT_VALID", httpStatus: 409 }
  if (task.plan.confirmation.state !== "CONFIRMED_VALID")
    return { error: "PLAN_NOT_CONFIRMED", httpStatus: 409, userMessage: "所选版本未确认或凭据已过期，请重新确认" }
  return { executionTaskId: `task-demo-exec-${req.revision}`, pinnedRevision: req.revision, pinnedDigest: req.digest }
}

/* ===================== F3-2 追加（append-only）：管理台只读计划详情 demo 种子 =====================
 * 形状对齐 contracts/task-states.md §5（plan_revision.validationStatus ∈ PENDING|VALID|INVALID、
 * plan.confirmation.state ∈ UNCONFIRMED|CONFIRMED_VALID|CONFIRMED_EXPIRED）与 mock-task.ts
 * taskDetail().plan 投影——plan-9 当前修订=3/digest cd02/VALID/UNCONFIRMED，两处种子保持一致。
 * 步骤 kind（SKILL/FLOW/AGENT/MODEL/CONDITION）/targetRef/dependsOn/inputBindings 按前端稿
 * §5.3 计划详情段；真实端点（接口稿 §5.2 修订读接口）就绪后由页面整块替换本数据源。
 * 只读：本追加段不提供任何写计划的 demo 入口（用户确认与选定版本执行由助手端计划卡 F4-3 验收）。 */

export type PlanStepKind = "SKILL" | "FLOW" | "AGENT" | "MODEL" | "CONDITION"
export type PlanValidationStatus = "PENDING" | "VALID" | "INVALID"
export type PlanConfirmationState = "UNCONFIRMED" | "CONFIRMED_VALID" | "CONFIRMED_EXPIRED"

/** 单步输入绑定：input=步骤入参名，source=绑定来源类别，value=来源取值表达式（只读展示）。 */
export interface PlanDemoStepBinding {
  input: string
  source: "TASK_INPUT" | "STEP_OUTPUT" | "CONSTANT"
  value: string
}

export interface PlanDemoStep {
  stepId: string
  kind: PlanStepKind
  targetRef: string
  dependsOn: string[]
  /** task_step.state 投影（DB §4.2 / task-states §4）：demo 种子未执行 → READY/BLOCKED。 */
  state: string
  inputBindings: PlanDemoStepBinding[]
}

export interface PlanDemoRevision {
  revision: number
  digest: string
  validationStatus: PlanValidationStatus
  confirmation: { state: PlanConfirmationState; confirmedBy?: string; expiresAt?: string; seq?: number }
  createdAt: string
  /** 升序排列；steps 即该修订固化的步骤清单（修订间按 stepId 差集对比）。 */
  steps: PlanDemoStep[]
}

export interface PlanDemoDetail {
  planId: string
  taskId: string
  appCode: string
  goal: string
  /** 升序；末位=当前修订（与 mock-task.ts 中该任务 plan 投影一致）。 */
  revisions: PlanDemoRevision[]
}

const planDemoDetailSeeds = (): PlanDemoDetail[] => [
  {
    planId: "plan-9", taskId: "task-demo-78", appCode: "satellite-planning-assistant",
    goal: "规划卫星观测任务序列",
    revisions: [
      {
        revision: 1, digest: "cd00", validationStatus: "VALID", createdAt: "2026-11-13T08:10:00Z",
        confirmation: { state: "CONFIRMED_EXPIRED", confirmedBy: "u-demo", expiresAt: "2026-11-13T10:00:00Z", seq: 1 },
        steps: [
          { stepId: "resolve_area", kind: "SKILL", targetRef: "skill://resolve-observation-area", dependsOn: [], state: "READY",
            inputBindings: [{ input: "region", source: "TASK_INPUT", value: "inputs.region" }, { input: "window", source: "CONSTANT", value: "24h" }] },
          { stepId: "detect_a", kind: "SKILL", targetRef: "skill://detect-a", dependsOn: ["resolve_area"], state: "BLOCKED",
            inputBindings: [{ input: "dataset", source: "STEP_OUTPUT", value: "resolve_area.artifacts.dataset" }] },
          { stepId: "detect_b", kind: "SKILL", targetRef: "skill://detect-b", dependsOn: ["resolve_area"], state: "BLOCKED",
            inputBindings: [{ input: "dataset", source: "STEP_OUTPUT", value: "resolve_area.artifacts.dataset" }] },
          { stepId: "compare", kind: "AGENT", targetRef: "agent://recon-compare-agent", dependsOn: ["detect_a", "detect_b"], state: "BLOCKED",
            inputBindings: [{ input: "baseline", source: "STEP_OUTPUT", value: "detect_a.artifacts.report" }, { input: "candidate", source: "STEP_OUTPUT", value: "detect_b.artifacts.report" }] },
        ],
      },
      {
        revision: 2, digest: "cd01", validationStatus: "INVALID", createdAt: "2026-11-13T08:40:00Z",
        confirmation: { state: "UNCONFIRMED" },
        steps: [
          { stepId: "resolve_area", kind: "SKILL", targetRef: "skill://resolve-observation-area", dependsOn: [], state: "READY",
            inputBindings: [{ input: "region", source: "TASK_INPUT", value: "inputs.region" }, { input: "window", source: "CONSTANT", value: "24h" }] },
          { stepId: "detect_a", kind: "SKILL", targetRef: "skill://detect-a", dependsOn: ["resolve_area"], state: "BLOCKED",
            inputBindings: [{ input: "dataset", source: "STEP_OUTPUT", value: "resolve_area.artifacts.dataset" }] },
          { stepId: "detect_b", kind: "SKILL", targetRef: "skill://detect-b", dependsOn: ["resolve_area"], state: "BLOCKED",
            inputBindings: [{ input: "dataset", source: "STEP_OUTPUT", value: "resolve_area.artifacts.dataset" }] },
          { stepId: "enhance", kind: "MODEL", targetRef: "model://recon-enhance-v9", dependsOn: ["detect_b"], state: "BLOCKED",
            inputBindings: [{ input: "dataset", source: "STEP_OUTPUT", value: "detect_b.artifacts.dataset" }] },
          { stepId: "compare", kind: "AGENT", targetRef: "agent://recon-compare-agent", dependsOn: ["detect_a", "enhance"], state: "BLOCKED",
            inputBindings: [{ input: "baseline", source: "STEP_OUTPUT", value: "detect_a.artifacts.report" }, { input: "candidate", source: "STEP_OUTPUT", value: "enhance.artifacts.enhanced" }] },
        ],
      },
      {
        revision: 3, digest: "cd02", validationStatus: "VALID", createdAt: "2026-11-13T09:20:00Z",
        confirmation: { state: "UNCONFIRMED" },
        steps: [
          { stepId: "resolve_area", kind: "SKILL", targetRef: "skill://resolve-observation-area", dependsOn: [], state: "READY",
            inputBindings: [{ input: "region", source: "TASK_INPUT", value: "inputs.region" }, { input: "window", source: "CONSTANT", value: "24h" }] },
          { stepId: "detect_a", kind: "SKILL", targetRef: "skill://detect-a", dependsOn: ["resolve_area"], state: "BLOCKED",
            inputBindings: [{ input: "dataset", source: "STEP_OUTPUT", value: "resolve_area.artifacts.dataset" }] },
          { stepId: "detect_b", kind: "SKILL", targetRef: "skill://detect-b", dependsOn: ["resolve_area"], state: "BLOCKED",
            inputBindings: [{ input: "dataset", source: "STEP_OUTPUT", value: "resolve_area.artifacts.dataset" }] },
          { stepId: "enhance", kind: "MODEL", targetRef: "model://recon-enhance-v2", dependsOn: ["detect_b"], state: "BLOCKED",
            inputBindings: [{ input: "dataset", source: "STEP_OUTPUT", value: "detect_b.artifacts.dataset" }] },
          { stepId: "compare", kind: "AGENT", targetRef: "agent://recon-compare-agent", dependsOn: ["detect_a", "enhance"], state: "BLOCKED",
            inputBindings: [{ input: "baseline", source: "STEP_OUTPUT", value: "detect_a.artifacts.report" }, { input: "candidate", source: "STEP_OUTPUT", value: "enhance.artifacts.enhanced" }] },
          { stepId: "retry_gate", kind: "CONDITION", targetRef: "condition://cloud-cover-check", dependsOn: ["compare"], state: "BLOCKED",
            inputBindings: [{ input: "threshold", source: "CONSTANT", value: "0.6" }] },
          { stepId: "report", kind: "FLOW", targetRef: "flow://recon-report-flow", dependsOn: ["compare"], state: "BLOCKED",
            inputBindings: [{ input: "diff", source: "STEP_OUTPUT", value: "compare.artifacts.diffReport" }] },
        ],
      },
    ],
  },
]

const planDemoDetails = new Map<string, PlanDemoDetail>()
planDemoDetailSeeds().forEach((p) => planDemoDetails.set(p.planId, p))

/** 管理台计划详情只读数据源（demo）：按 planId 取整份修订历史；未知 planId → null。 */
export const demoPlanDetail = (planId: string): PlanDemoDetail | null => planDemoDetails.get(planId) ?? null

/** 两修订步骤差集（按 stepId 简单 diff，管理台只读对比用）：added=目标新增，removed=目标删除。 */
export function diffPlanSteps(base: PlanDemoRevision, target: PlanDemoRevision): { added: PlanDemoStep[]; removed: PlanDemoStep[] } {
  const baseIds = new Set(base.steps.map((s) => s.stepId))
  const targetIds = new Set(target.steps.map((s) => s.stepId))
  return {
    added: target.steps.filter((s) => !baseIds.has(s.stepId)),
    removed: base.steps.filter((s) => !targetIds.has(s.stepId)),
  }
}
