/** T2-14 mock 契约（泳道 L4 首卡）：任务域——POST /v1/apps/{code}/tasks、详情/事件游标。仅 demo 装载（§5.4）。 */
export type TaskMode = "PLAN" | "AGENT"
export type TaskState =
  | "ACCEPTED" | "PLANNING" | "PLAN_READY" | "NEEDS_INPUT" | "WAITING_DEPENDENCIES" | "QUEUED"
  | "RUNNING" | "WAITING_APPROVAL" | "WAITING_EXTERNAL" | "WAITING_RECONCILIATION"
  | "SUCCEEDED" | "FAILED" | "CANCELLED"

export interface MockTaskSeed {
  taskId: string
  appCode: string
  mode: TaskMode
  purpose: "PLAN" | "EXECUTE"
  state: TaskState
  goal: string
  principalId: string
  createdAt: string
  stateVersion: number
  demo: true
  steps: { stepId: string; state: string; resultDigest?: string }[]
  plan?: { planId: string; revision: number; digest: string; validationStatus: "VALID" | "INVALID" | "PENDING";
    confirmation: { state: "UNCONFIRMED" | "CONFIRMED_VALID" | "CONFIRMED_EXPIRED"; confirmedBy?: string; expiresAt?: string; seq?: number } }
  recovery?: { recoverySource: "MANUAL" | "AUTO"; reasonCode: string; reasonSummary: string;
    checkpoint?: { eventWatermark: number; runRefs: string[] }; reusedSteps?: { stepId: string; resultDigest: string }[] } | null
  events: { seq: number; type: string; data: Record<string, unknown> }[]
}

const terminal = (state: TaskState) => state === "SUCCEEDED" || state === "FAILED" || state === "CANCELLED"

/** 服务端计算的 availableActions（contracts/task-states.md §3 矩阵；前端只渲染不自判——红线8）。 */
export function availableActions(state: TaskState, opts: { resumable?: boolean; reconcilable?: boolean } = {}): string[] {
  if (terminal(state)) return ["REISSUE", "EXPORT"]
  switch (state) {
    case "WAITING_DEPENDENCIES": return ["VIEW_DEPENDENCIES", "CANCEL"]
    case "WAITING_APPROVAL": return ["GOTO_CONFIRM", "CANCEL"]
    case "WAITING_EXTERNAL": return ["VIEW_EXTERNAL", "CANCEL"]
    case "WAITING_RECONCILIATION": {
      const actions = opts.reconcilable ? ["TRACK_EXTERNAL", "ADOPT_EXTERNAL", "RETRY"] : ["TRACK_EXTERNAL", "DISCLOSE"]
      return [...actions, "CANCEL"]
    }
    case "RUNNING": return ["CANCEL"]
    default: return ["CANCEL"]
  }
}

const tasks = new Map<string, MockTaskSeed>()
const requests = new Map<string, { taskId: string; digest: string }>()
export const demoTaskSeeds = (): MockTaskSeed[] => [
  { taskId: "task-demo-77", appCode: "satellite-planning-assistant", mode: "AGENT", purpose: "EXECUTE", state: "RUNNING",
    goal: "对同一数据集运行两种识别算法并生成差异报告", principalId: "u-demo", createdAt: "2026-11-13T08:00:00Z", stateVersion: 12, demo: true,
    steps: [{ stepId: "detect_a", state: "SUCCEEDED", resultDigest: "ab41" }, { stepId: "detect_b", state: "RUNNING" }],
    recovery: null,
    events: [{ seq: 1, type: "TASK_ACCEPTED", data: {} }, { seq: 2, type: "STEP_READY", data: { stepId: "detect_a" } },
      { seq: 3, type: "STEP_FINISHED", data: { stepId: "detect_a" } }, { seq: 4, type: "STEP_STARTED", data: { stepId: "detect_b" } }] },
  { taskId: "task-demo-78", appCode: "satellite-planning-assistant", mode: "PLAN", purpose: "PLAN", state: "PLAN_READY",
    goal: "规划卫星观测任务序列", principalId: "u-demo", createdAt: "2026-11-13T09:30:00Z", stateVersion: 3, demo: true,
    steps: [], recovery: null,
    plan: { planId: "plan-9", revision: 3, digest: "cd02", validationStatus: "VALID",
      confirmation: { state: "UNCONFIRMED" } },
    events: [{ seq: 1, type: "TASK_ACCEPTED", data: {} }, { seq: 2, type: "PLAN_VALIDATED", data: { revision: 3 } }] },
]
demoTaskSeeds().forEach((t) => tasks.set(t.taskId, t))

const digestOf = (value: unknown) => JSON.stringify(sortDeep(value))
const sortDeep = (value: unknown): unknown =>
  Array.isArray(value) ? value.map(sortDeep)
    : value && typeof value === "object" ? Object.fromEntries(Object.entries(value).sort(([a], [b]) => a.localeCompare(b)).map(([k, v]) => [k, sortDeep(v)]))
    : value

/** 提交任务：requestId 幂等（同参重放原任务/异参 409 REQUEST_REPLAY）；mode 必填（评审十七轮）。 */
export function submitTask(req: { requestId: string; mode?: TaskMode; goal: string; inputs?: Record<string, unknown>;
  sessionId?: string; sessionCursor?: number; toolPolicy?: { allowedToolRefs: string[] } }):
  { taskId: string; state: TaskState; acceptedPlanMode?: TaskMode } | { error: string; httpStatus: number; userMessage: string } {
  if (!req.mode) return { error: "PARAM_MODE_REQUIRED", httpStatus: 400, userMessage: "mode 必填，不做隐式默认" }
  const prior = requests.get(req.requestId)
  const digest = digestOf({ mode: req.mode, goal: req.goal, inputs: req.inputs ?? null, sessionId: req.sessionId ?? null, toolPolicy: req.toolPolicy ?? null })
  if (prior) return prior.digest === digest ? { taskId: prior.taskId, state: tasks.get(prior.taskId)!.state }
    : { error: "REQUEST_REPLAY", httpStatus: 409, userMessage: "同一 requestId 用于不同参数" }
  const taskId = `task-demo-${tasks.size + 79}`
  tasks.set(taskId, { taskId, appCode: "satellite-planning-assistant", mode: req.mode, purpose: "PLAN", state: req.mode === "PLAN" ? "PLANNING" : "QUEUED",
    goal: req.goal, principalId: "u-demo", createdAt: new Date().toISOString(), stateVersion: 1, demo: true,
    steps: [], recovery: null, events: [{ seq: 1, type: "TASK_ACCEPTED", data: {} }] })
  requests.set(req.requestId, { taskId, digest })
  return { taskId, state: tasks.get(taskId)!.state, acceptedPlanMode: req.mode }
}

/** 任务事件增量：nextAfterSeq=最后扫描 seq（不 +1，评审三轮 07）；truncated 标记。 */
export function taskEvents(taskId: string, afterSeq = 0, limit = 200):
  { events: MockTaskSeed["events"]; nextAfterSeq: number; truncated: boolean } {
  const task = tasks.get(taskId)
  if (!task) return { events: [], nextAfterSeq: afterSeq, truncated: false }
  const scanned = task.events.filter((e) => e.seq > afterSeq).slice(0, limit)
  const nextAfterSeq = scanned.length ? scanned[scanned.length - 1].seq : afterSeq
  return { events: scanned, nextAfterSeq, truncated: task.events.some((e) => e.seq > nextAfterSeq) }
}

export const taskDetail = (taskId: string) => {
  const t = tasks.get(taskId)
  if (!t) return null
  const canConfirm = t.plan?.confirmation.state !== "CONFIRMED_VALID"
  return { ...t, canConfirm, canExecute: t.plan?.confirmation.state === "CONFIRMED_VALID" && t.plan.validationStatus === "VALID",
    availableActions: availableActions(t.state), eventWatermark: t.events.at(-1)?.seq ?? 0 }
}
