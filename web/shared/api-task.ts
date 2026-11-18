/**
 * M5-2：任务域真实 API 客户端（经网关）——F3-1 任务工作台 + F4-2/F4-3 助手端任务卡数据面。
 *
 * 端点（engine TaskController，网关/authority 已登记）：
 *   GET  /v1/tasks?state&appCode&from&to&principalId&page&pageSize&confirmableBy
 *   GET  /v1/tasks/{taskId}                       详情（服务端 availableActions + recovery 角色投影）
 *   GET  /v1/tasks/{taskId}/events?afterSeq&limit 事件增量（排他游标，不 +1）
 *   POST /v1/apps/{code}/tasks                    受理（requestId 幂等，mode 必填）
 *   POST /v1/tasks/{taskId}/cancel|resume         取消/恢复
 *   POST /v1/tasks/{taskId}/steps/{stepId}/calls/{callId}/reconcile  对账处置
 *   GET  /v1/tasks/{taskId}/steps/{stepId}/calls  步骤调用列表（锚点定位）
 *
 * 函数形状与原 mock-task 消费面同形（错误以 {error,httpStatus,userMessage} 对象返回，
 * 页面 `if ("error" in done)` 语义不变）；api 层抛错在此统一降形。
 * VITE_MOCK=true 时经 useApi 的 mock 传输层仍走演示路由（mock-transport /v1/tasks*）。
 */
import { useApi } from "./api";

export type TaskMode = "PLAN" | "AGENT";
export type TaskState =
  | "ACCEPTED" | "PLANNING" | "PLAN_READY" | "NEEDS_INPUT" | "WAITING_DEPENDENCIES" | "QUEUED"
  | "RUNNING" | "WAITING_APPROVAL" | "WAITING_EXTERNAL" | "WAITING_RECONCILIATION"
  | "SUCCEEDED" | "FAILED" | "CANCELLED";

/** 演示/联调默认应用编码：V901 种子工作区（contracts/demo-seed-v901-design.md §2）。 */
export const demoAppCode = "satellite-planning-assistant";

export interface TaskStepRow {
  stepId: string;
  state: string;
  resultDigest?: string;
  attemptNo?: number;
}
export interface TaskSubtaskRow {
  taskId: string;
  role?: string;
  state: string;
  goal?: string;
}
export interface DemoPendingCall {
  callId: string;
  stepId: string;
  externalRef?: string;
  /** 服务端对账核对投影：UNRECEIVED=可查证未受理（出 RETRY）；RECEIVED=已受理（查证/采纳）；UNKNOWN=不可核实（披露） */
  verified: "UNRECEIVED" | "RECEIVED" | "UNKNOWN";
  disclosed?: true;
}
export interface DemoTaskRecovery {
  recoverySource: "MANUAL" | "AUTO";
  reasonCode: string;
  reasonSummary: string;
  checkpoint?: { eventWatermark: number; runRefs: string[] };
  reusedSteps?: { stepId: string; resultDigest: string }[];
  blockedReasons?: string[];
}
export interface TaskPlanRef {
  planId: string;
  revision: number;
  digest: string;
  validationStatus: "VALID" | "INVALID" | "PENDING";
  confirmation: {
    state: "UNCONFIRMED" | "CONFIRMED_VALID" | "CONFIRMED_EXPIRED";
    confirmedBy?: string;
    expiresAt?: string;
    seq?: number;
  };
}
export interface TaskRow {
  taskId: string;
  appCode: string;
  mode: TaskMode;
  purpose?: string;
  state: TaskState;
  goal: string;
  principalId: string;
  createdAt: string;
  updatedAt?: string;
  stateVersion?: number;
  steps: TaskStepRow[];
  subtasks: TaskSubtaskRow[];
  dependencies?: { upstreamTaskId: string; satisfied: boolean }[];
  plan?: TaskPlanRef | null;
  recovery?: DemoTaskRecovery | null;
  availableActions: string[];
  eventWatermark?: number;
}
export type ApiFailure = { error: string; httpStatus: number; userMessage: string };

/** api 层抛错→mock 同形错误对象（页面 `if ("error" in done)` 语义保持）。 */
export async function guard<T>(fn: () => Promise<T>): Promise<T | ApiFailure> {
  try {
    return await fn();
  } catch (e: any) {
    return {
      error: String(e?.code ?? e?.name ?? "REQUEST_FAILED"),
      httpStatus: Number(e?.status ?? 0),
      userMessage: String(e?.message ?? "请求失败"),
    };
  }
}

/** snake_case 行（详情 steps/subtasks 为库列名直投）→ 页面 camelCase 视图。 */
function normalizeStep(row: any): TaskStepRow {
  return {
    stepId: String(row.step_id ?? row.stepId ?? ""),
    state: String(row.state ?? ""),
    resultDigest: row.result_digest ?? row.resultDigest,
    attemptNo: row.attempt_no ?? row.attemptNo,
  };
}
function normalizeSubtask(row: any): TaskSubtaskRow {
  return {
    taskId: String(row.task_id ?? row.taskId ?? row.subTaskId ?? row.sub_task_id ?? ""),
    role: row.role,
    state: String(row.state ?? ""),
    goal: row.goal,
  };
}

/** 详情/列表行统一归一：steps/subtasks/dependencies 形状 + 服务端字段透传。 */
function normalizeTask(view: any): TaskRow {
  return {
    taskId: String(view.taskId ?? view.task_id ?? ""),
    appCode: String(view.appCode ?? view.app_code ?? ""),
    mode: (view.mode ?? "AGENT") as TaskMode,
    purpose: view.purpose,
    state: view.state as TaskState,
    goal: String(view.goal ?? ""),
    principalId: String(view.principalId ?? view.principal_id ?? ""),
    createdAt: String(view.createdAt ?? view.created_at ?? ""),
    updatedAt: view.updatedAt ?? view.updated_at,
    stateVersion: Number(view.stateVersion ?? view.state_version ?? 0),
    steps: Array.isArray(view.steps) ? view.steps.map(normalizeStep) : [],
    subtasks: Array.isArray(view.subtasks) ? view.subtasks.map(normalizeSubtask) : [],
    dependencies: Array.isArray(view.dependencies)
      ? view.dependencies.map((d: any) => ({
          upstreamTaskId: String(d.upstream_task_id ?? d.upstreamTaskId ?? ""),
          satisfied: !!d.satisfied,
        }))
      : [],
    plan: (view.plan as TaskPlanRef) ?? null,
    recovery: (view.recovery as DemoTaskRecovery) ?? null,
    availableActions: Array.isArray(view.availableActions) ? view.availableActions.map(String) : [],
    eventWatermark: Number(view.eventWatermark ?? 0),
  };
}

/** 任务列表（真实端点）：首页大页取回后逐行补详情（availableActions/recovery/steps 服务端计算面在详情端点）。 */
export async function listTasks(query: { state?: string; appCode?: string; pageSize?: number } = {}): Promise<TaskRow[]> {
  const api = useApi();
  const params = new URLSearchParams({ page: "1", pageSize: String(query.pageSize ?? 50) });
  if (query.state) params.set("state", query.state);
  if (query.appCode) params.set("appCode", query.appCode);
  const page = await api<any>(`/v1/tasks?${params.toString()}`);
  const rows: any[] = Array.isArray(page?.tasks) ? page.tasks : Array.isArray(page) ? page : [];
  // 行内嵌任务树与状态×操作矩阵的字段在详情端点（接口稿 §5.2）；列表行补详情（并发受限）。
  const hydrated = await Promise.all(
    rows.map(async (row) => {
      const detail = await guard(() => taskDetail(String(row.taskId)));
      return "error" in detail ? normalizeTask(row) : detail;
    }),
  );
  return hydrated.sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1));
}

/** 任务详情（真实端点）：state/availableActions/recovery 角色投影/steps/subtasks/事件水位。 */
export async function taskDetail(taskId: string): Promise<TaskRow> {
  const api = useApi();
  return normalizeTask(await api<any>(`/v1/tasks/${encodeURIComponent(taskId)}`));
}

/** 任务详情 + 未决调用（工作台展开）：pendingCalls 由步骤调用列表聚合（真实端点按步骤分页）。 */
export async function listTaskDetail(taskId: string): Promise<TaskRow & { pendingCalls: DemoPendingCall[] }> {
  const detail = await taskDetail(taskId);
  const api = useApi();
  const pending: DemoPendingCall[] = [];
  await Promise.all(
    detail.steps.map(async (step) => {
      const page = await guard(() =>
        api<any>(`/v1/tasks/${encodeURIComponent(taskId)}/steps/${encodeURIComponent(step.stepId)}/calls?pageSize=100`),
      );
      if ("error" in page) return;
      for (const call of Array.isArray(page?.calls) ? page.calls : []) {
        // 未决调用=服务端核对投影（UNRECEIVED/RECEIVED/UNKNOWN）；无该投影的字段如实归 UNKNOWN。
        pending.push({
          callId: String(call.callId ?? call.id ?? ""),
          stepId: step.stepId,
          externalRef: call.externalRef ?? call.external_ref,
          verified: (call.verified ?? "UNKNOWN") as DemoPendingCall["verified"],
          ...(call.disclosed ? { disclosed: true as const } : {}),
        });
      }
    }),
  );
  return { ...detail, pendingCalls: pending };
}

/** 事件增量（真实端点）：返回形状与 mock 同构 {events,nextAfterSeq,truncated}。 */
export async function taskEvents(taskId: string, afterSeq = 0, limit = 200) {
  const api = useApi();
  const page = await api<any>(
    `/v1/tasks/${encodeURIComponent(taskId)}/events?afterSeq=${encodeURIComponent(String(afterSeq))}&limit=${encodeURIComponent(String(limit))}`,
  );
  return {
    events: (Array.isArray(page?.events) ? page.events : []).map((event: any) => ({
      seq: Number(event.seq),
      type: String(event.type),
      data: (event.data ?? {}) as Record<string, unknown>,
    })),
    nextAfterSeq: Number(page?.nextAfterSeq ?? afterSeq),
    truncated: !!page?.truncated,
  };
}

/** 受理（真实端点）：requestId 幂等；mode 必填；会话游标随请求提交。 */
export async function submitTask(req: {
  requestId: string;
  mode?: TaskMode;
  goal: string;
  inputs?: Record<string, unknown>;
  sessionId?: string;
  sessionCursor?: number;
  toolPolicy?: { allowedToolRefs: string[] };
}): Promise<{ taskId: string; state: TaskState; acceptedPlanMode?: TaskMode }> {
  const api = useApi();
  const body: Record<string, unknown> = {
    requestId: req.requestId,
    mode: req.mode,
    goal: req.goal,
    inputs: req.inputs ?? {},
  };
  if (req.sessionId) body.sessionId = req.sessionId;
  if (typeof req.sessionCursor === "number") body.sessionCursor = req.sessionCursor;
  if (req.toolPolicy) body.toolPolicy = req.toolPolicy;
  const created = await api<any>(`/v1/apps/${encodeURIComponent(demoAppCode)}/tasks`, "POST", body);
  return {
    taskId: String(created?.taskId ?? created?.task_id ?? ""),
    state: (created?.state ?? "ACCEPTED") as TaskState,
    acceptedPlanMode: created?.acceptedPlanMode,
  };
}

/** 取消（真实端点，终态幂等）。 */
export async function cancelTask(req: { taskId: string; reason?: string }) {
  const api = useApi();
  return guard(async () => {
    const done = await api<any>(`/v1/tasks/${encodeURIComponent(req.taskId)}/cancel`, "POST", {
      reason: req.reason ?? "OPERATOR_CANCEL",
    });
    return { taskId: String(done?.taskId ?? req.taskId), state: done?.state as TaskState };
  });
}

/** 手动恢复（真实端点）：恢复资格由服务端事实计算（RESUME 出现在 availableActions 才可恢复）。 */
export async function resumeTask(req: { taskId: string }) {
  const api = useApi();
  return guard(async () => {
    const done = await api<any>(`/v1/tasks/${encodeURIComponent(req.taskId)}/resume`, "POST", {});
    if (done?.pending)
      return { taskId: req.taskId, pending: true as const, userMessage: String(done.userMessage ?? "恢复请求已受理，稍后按事件读取恢复事实") };
    return { taskId: String(done?.taskId ?? req.taskId), state: done?.state as TaskState };
  });
}

/** 对账处置提交（真实端点）：TRACK_EXTERNAL/ADOPT_EXTERNAL/RETRY/DISCLOSE，服务端按当时证据复核。 */
export async function reconcileTaskCall(req: {
  taskId: string;
  stepId: string;
  callId: string;
  action: string;
}) {
  const api = useApi();
  return guard(async () => {
    const done = await api<any>(
      `/v1/tasks/${encodeURIComponent(req.taskId)}/steps/${encodeURIComponent(req.stepId)}/calls/${encodeURIComponent(req.callId)}/reconcile`,
      "POST",
      { action: req.action },
    );
    return { taskId: req.taskId, callId: req.callId, ...(done ?? {}) } as Record<string, unknown>;
  });
}

/** 未决调用级动作投影（纯函数，前端稿 §5.3：仅可查证"未受理"才出 RETRY）。 */
export function callActions(call: Pick<DemoPendingCall, "verified">): string[] {
  if (call.verified === "UNRECEIVED") return ["TRACK_EXTERNAL", "ADOPT_EXTERNAL", "RETRY"];
  if (call.verified === "RECEIVED") return ["TRACK_EXTERNAL", "ADOPT_EXTERNAL"];
  return ["TRACK_EXTERNAL", "DISCLOSE"];
}
