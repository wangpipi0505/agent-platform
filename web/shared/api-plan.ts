/**
 * M5-2：计划域真实 API 客户端（经网关）——F4-3 助手端计划卡 + F3-2 管理台计划详情数据面。
 *
 * 端点（engine TaskController/Plans，网关/authority 已登记）：
 *   GET  /v1/tasks/{taskId}                         本人任务及最新计划修订
 *   GET  /v1/tasks/{taskId}/plans/{planId}/revisions/{revision}  修订读（含确认状态投影）
 *   POST .../confirm                                计划版本级确认（digest 校验 409 PLAN_DIGEST_CHANGED）
 *   POST /v1/tasks/{taskId}/execute                 选定计划执行（五项受理校验）
 */
import { useApi } from "./api";
import { guard, taskDetail, type TaskPlanRef, type ApiFailure } from "./api-task";

export type {
  TaskMode,
  TaskState,
  TaskPlanRef,
  TaskRow,
  ApiFailure,
} from "./api-task";

/** 确认中心投影行（接口稿 §5.7 confirmableBy=me 形状）。 */
export interface ConfirmableRevision {
  taskId: string;
  planId: string;
  revision: number;
  digest: string;
  goal: string;
  confirmState: "UNCONFIRMED" | "CONFIRMED_EXPIRED";
  confirmedBy?: string;
  expiresAt?: string;
}

/** 计划确认后仍可按本人任务读取最新修订与当前授权。 */
export async function taskPlanDetail(taskId: string) {
  const detail = await taskDetail(taskId);
  const plan = detail.plan ?? null;
  return {
    ...detail,
    plan,
    canConfirm: plan?.canConfirm === true,
    canExecute: plan?.canExecute === true,
    operationReasonRequired: plan?.operationReasonRequired === true,
  };
}

/** 计划确认（真实端点）：同号同参重放返回原凭据；digest 不符 409 PLAN_DIGEST_CHANGED。 */
export async function confirmPlan(
  taskId: string,
  req: { requestId: string; digest: string; confirmedBy?: string; planId: string; revision: number },
): Promise<{ planId: string; revision: number; confirmSeq: number; confirmedBy: string; expiresAt: string; digest: string }> {
  const api = useApi();
  return api(
    `/v1/tasks/${encodeURIComponent(taskId)}/plans/${encodeURIComponent(req.planId)}/revisions/${encodeURIComponent(String(req.revision))}/confirm`,
    "POST",
    { requestId: req.requestId, digest: req.digest, confirmedBy: req.confirmedBy },
  );
}

/** 选定计划执行（真实端点）：未确认/过期 409 PLAN_NOT_CONFIRMED（引导重新确认）。 */
export async function executePlan(
  taskId: string,
  req: { requestId: string; planId: string; revision: number; digest: string; operationReason?: string },
): Promise<{ executionTaskId: string; pinnedRevision?: number } | ApiFailure> {
  const api = useApi();
  return guard(async () => {
    const done = await api<any>(`/v1/tasks/${encodeURIComponent(taskId)}/execute`, "POST", {
      requestId: req.requestId,
      planId: req.planId,
      revision: req.revision,
      digest: req.digest,
      operationReason: req.operationReason,
    });
    return {
      executionTaskId: String(done?.executionTaskId ?? done?.taskId ?? ""),
      pinnedRevision: Number(done?.pinnedRevision ?? req.revision),
    };
  });
}

/** 管理台计划详情（修订读投影）：返回单修订视图；无 task/revision 上下文时返回 null。 */
export interface PlanRevisionView {
  planId: string;
  revision: number;
  digest: string;
  validationStatus: "VALID" | "INVALID" | "PENDING";
  confirmation: TaskPlanRef["confirmation"];
  canConfirm: boolean;
  canExecute: boolean;
}
export async function planRevision(
  taskId: string,
  planId: string,
  revision: number,
): Promise<PlanRevisionView | null> {
  const api = useApi();
  try {
    const view = await api<any>(
      `/v1/tasks/${encodeURIComponent(taskId)}/plans/${encodeURIComponent(planId)}/revisions/${encodeURIComponent(String(revision))}`,
    );
    return {
      planId: String(view?.planId ?? planId),
      revision: Number(view?.revision ?? revision),
      digest: String(view?.digest ?? ""),
      validationStatus: (view?.validationStatus ?? "PENDING") as PlanRevisionView["validationStatus"],
      confirmation: (view?.confirmation ?? { state: "UNCONFIRMED" }) as TaskPlanRef["confirmation"],
      canConfirm: !!view?.canConfirm,
      canExecute: !!view?.canExecute,
    };
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------
// 管理台只读计划详情（纯投影类型/函数自 mock-plan 迁出；数据面=真实修订读端点）。
// ---------------------------------------------------------------------------

export type PlanStepKind = "SKILL" | "FLOW" | "AGENT" | "MODEL" | "CONDITION";
export type PlanValidationStatus = "PENDING" | "VALID" | "INVALID";
export type PlanConfirmationState = "UNCONFIRMED" | "CONFIRMED_VALID" | "CONFIRMED_EXPIRED";

/** 单步输入绑定：input=步骤入参名，source=绑定来源类别，value=来源取值表达式（只读展示）。 */
export interface PlanDemoStepBinding {
  input: string;
  source: "TASK_INPUT" | "STEP_OUTPUT" | "CONSTANT";
  value: string;
}

export interface PlanDemoStep {
  stepId: string;
  kind: PlanStepKind;
  targetRef: string;
  dependsOn: string[];
  state: string;
  inputBindings: PlanDemoStepBinding[];
}

export interface PlanDemoRevision {
  revision: number;
  digest: string;
  validationStatus: PlanValidationStatus;
  confirmation: { state: PlanConfirmationState; confirmedBy?: string; expiresAt?: string; seq?: number };
  createdAt: string;
  /** 升序排列；steps 即该修订固化的步骤清单（修订间按 stepId 差集对比）。 */
  steps: PlanDemoStep[];
}

export interface PlanDemoDetail {
  planId: string;
  taskId: string;
  appCode: string;
  goal: string;
  /** 升序；末位=当前修订。 */
  revisions: PlanDemoRevision[];
}

/** 两修订步骤差集（按 stepId 简单 diff，管理台只读对比用）：added=目标新增，removed=目标删除。 */
export function diffPlanSteps(
  base: PlanDemoRevision,
  target: PlanDemoRevision,
): { added: PlanDemoStep[]; removed: PlanDemoStep[] } {
  const baseIds = new Set(base.steps.map((s) => s.stepId));
  const targetIds = new Set(target.steps.map((s) => s.stepId));
  return {
    added: target.steps.filter((s) => !baseIds.has(s.stepId)),
    removed: base.steps.filter((s) => !targetIds.has(s.stepId)),
  };
}

/**
 * 管理台计划详情（真实端点）：修订读 GET /v1/tasks/{taskId}/plans/{planId}/revisions/{revision}。
 * 诚实边界：plan→steps 固化清单无读取端点（修订读返回 digest/校验/确认投影），steps 如实为空；
 * 无 task/revision 上下文（route 仅 planId）时无法定位修订，返回 null（页面空态如实呈现）。
 */
export async function fetchPlanDetail(
  planId: string,
  opts: { taskId?: string; revision?: number; appCode?: string; goal?: string } = {},
): Promise<PlanDemoDetail | null> {
  const taskId = String(opts.taskId ?? "");
  const revision = Number(opts.revision ?? 0);
  if (!taskId || !Number.isInteger(revision) || revision <= 0) return null;
  const view = await planRevision(taskId, planId, revision);
  if (!view) return null;
  const mapped: PlanDemoRevision = {
    revision: view.revision,
    digest: view.digest,
    validationStatus: view.validationStatus,
    confirmation: view.confirmation,
    createdAt: "",
    steps: [],
  };
  return {
    planId: view.planId,
    taskId,
    appCode: String(opts.appCode ?? ""),
    goal: String(opts.goal ?? ""),
    revisions: [mapped],
  };
}
