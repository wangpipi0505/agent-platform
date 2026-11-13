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

// ---------------------------------------------------------------------------
// F3-1 增补（追加式：以上冻结契约零改动）——任务工作台（管理台）demo 数据源与处置函数。
// mock 期由 F3-1 消费；联调期整体替换为 T2-2/T2-12 真实端点（列表→GET 任务列表、
// cancelTask→POST cancel、resumeTask→POST resume、reconcileTaskCall→POST
// /v1/tasks/{taskId}/steps/{stepId}/calls/{callId}/reconcile）。
// 注意：不得向 tasks Map 追加条目——submitTask 的 taskId=tasks.size+79 已被
// 冻结测试（mock-task-plan-session）锁定 task-demo-81；演示种子走独立注册表。
// ---------------------------------------------------------------------------

export interface DemoPendingCall {
  callId: string
  stepId: string
  externalRef?: string
  /** 服务端对账核对投影：UNRECEIVED=可查证未受理（出 RETRY）；RECEIVED=已受理（查证/采纳）；UNKNOWN=不可核实（披露） */
  verified: "UNRECEIVED" | "RECEIVED" | "UNKNOWN"
  /** DISCLOSE 处置后保留待对账记录标记 */
  disclosed?: true
}

/** 演示种子恢复投影：冻结三字段口径 + OPERATOR 附加分区（blockedReasons 有则显示）。 */
export type DemoTaskRecovery = NonNullable<MockTaskSeed["recovery"]> & { blockedReasons?: string[] }

export interface DemoTaskSeedExtra extends Omit<MockTaskSeed, "recovery"> {
  subtasks?: { subTaskId: string; goal: string; state: string }[]
  pendingCalls?: DemoPendingCall[]
  /** 恢复资格事实投影（task-states §3：RESUME 由服务端按事实叠加，非持久状态） */
  resumable?: true
  /** 自动恢复已持租约未提交 → 手动 resume 返回 pending（同一恢复入口先到先得） */
  autoRecoveryLease?: true
  recovery: DemoTaskRecovery | null
}

export type DemoTaskListRow = MockTaskSeed & {
  availableActions: string[]
  subtasks: NonNullable<DemoTaskSeedExtra["subtasks"]>
}

export type DemoTaskDetail = NonNullable<ReturnType<typeof taskDetail>> & {
  subtasks: NonNullable<DemoTaskSeedExtra["subtasks"]>
  pendingCalls: DemoPendingCall[]
}

const demoTaskExtras = new Map<string, DemoTaskSeedExtra>()

function seedDemoTaskExtras(): void {
  demoTaskExtras.clear()
  const extras: DemoTaskSeedExtra[] = [
    { taskId: "task-demo-91", appCode: "recon-assistant", mode: "AGENT", purpose: "EXECUTE", state: "WAITING_RECONCILIATION",
      goal: "外部通知调用回执丢失，按核对结果处置未决调用", principalId: "u-demo", createdAt: "2026-11-13T10:05:00Z", stateVersion: 9, demo: true,
      steps: [{ stepId: "detect_a", state: "SUCCEEDED", resultDigest: "ab41" }, { stepId: "notify_ext", state: "WAITING_RECONCILIATION" }],
      subtasks: [{ subTaskId: "sub-91a", goal: "复核 EXT-77 外部受理事实", state: "WAITING_EXTERNAL" }],
      pendingCalls: [
        { callId: "call-9f1", stepId: "notify_ext", externalRef: "EXT-77", verified: "UNRECEIVED" },
        { callId: "call-9f2", stepId: "notify_ext", externalRef: "EXT-78", verified: "RECEIVED" },
      ],
      resumable: true, autoRecoveryLease: true, recovery: null,
      events: [{ seq: 1, type: "TASK_ACCEPTED", data: {} }, { seq: 2, type: "STEP_FINISHED", data: { stepId: "detect_a" } },
        { seq: 3, type: "RECONCILIATION_REQUIRED", data: { stepId: "notify_ext", callId: "call-9f1" } },
        { seq: 4, type: "SUBTASK_REGISTERED", data: { subTaskId: "sub-91a" } }] },
    { taskId: "task-demo-92", appCode: "recon-assistant", mode: "AGENT", purpose: "EXECUTE", state: "WAITING_EXTERNAL",
      goal: "外部回执等待中，执行租约曾过期，可从检查点恢复", principalId: "u-demo", createdAt: "2026-11-13T09:50:00Z", stateVersion: 14, demo: true,
      steps: [{ stepId: "detect_a", state: "SUCCEEDED", resultDigest: "ab41" }, { stepId: "dispatch_b", state: "WAITING_EXTERNAL" }],
      resumable: true,
      recovery: { recoverySource: "AUTO", reasonCode: "LEASE_EXPIRED",
        reasonSummary: "执行租约过期，自动恢复已从检查点续跑一次，仍在等待外部回执",
        checkpoint: { eventWatermark: 14, runRefs: ["run-a1"] },
        reusedSteps: [{ stepId: "detect_a", resultDigest: "ab41" }],
        blockedReasons: ["外部任务 EXT-77 回执未决"] },
      events: [{ seq: 1, type: "TASK_ACCEPTED", data: {} }, { seq: 2, type: "STEP_FINISHED", data: { stepId: "detect_a" } },
        { seq: 3, type: "TASK_RESUMED", data: { recoverySource: "AUTO", reasonCode: "LEASE_EXPIRED", checkpoint: { eventWatermark: 14, runRefs: ["run-a1"] } } }] },
    { taskId: "task-demo-93", appCode: "satellite-planning-assistant", mode: "PLAN", purpose: "EXECUTE", state: "SUCCEEDED",
      goal: "已完成一次可导出的执行任务（终态只读记录）", principalId: "u-demo", createdAt: "2026-11-13T08:30:00Z", stateVersion: 20, demo: true,
      steps: [{ stepId: "detect_a", state: "SUCCEEDED", resultDigest: "ab41" }, { stepId: "compare", state: "SUCCEEDED", resultDigest: "cc07" }],
      plan: { planId: "plan-8", revision: 2, digest: "be11", validationStatus: "VALID",
        confirmation: { state: "CONFIRMED_VALID", confirmedBy: "u-demo", seq: 3 } },
      recovery: null,
      events: [{ seq: 1, type: "TASK_ACCEPTED", data: {} }, { seq: 2, type: "PLAN_VALIDATED", data: { revision: 2 } },
        { seq: 3, type: "STEP_FINISHED", data: { stepId: "detect_a" } }, { seq: 4, type: "STEP_FINISHED", data: { stepId: "compare" } },
        { seq: 5, type: "TASK_FINISHED", data: { state: "SUCCEEDED" } }] },
  ]
  extras.forEach((t) => demoTaskExtras.set(t.taskId, t))
}
seedDemoTaskExtras()

const findDemoTask = (taskId: string): MockTaskSeed | DemoTaskSeedExtra | undefined =>
  tasks.get(taskId) ?? demoTaskExtras.get(taskId)

const pushEvent = (t: MockTaskSeed | DemoTaskSeedExtra, type: string, data: Record<string, unknown>): void => {
  t.events = [...t.events, { seq: (t.events.at(-1)?.seq ?? 0) + 1, type, data }]
}

/** 服务端视角的动作投影：状态矩阵 + 对账核对分支 + 恢复资格叠加（前端只渲染不自判，红线8）。 */
function demoProjectedActions(seed: Pick<DemoTaskSeedExtra, "state" | "pendingCalls" | "resumable">): string[] {
  const actions = availableActions(seed.state, { reconcilable: !!seed.pendingCalls?.some((c) => c.verified === "UNRECEIVED") })
  return seed.resumable && !actions.includes("RESUME") ? [...actions, "RESUME"] : actions
}

/** 未决调用级动作（每个动作绑定具体 callId；仅可查证"未受理"才出 RETRY——前端稿 §5.3）。 */
export function callActions(call: Pick<DemoPendingCall, "verified">): string[] {
  if (call.verified === "UNRECEIVED") return ["TRACK_EXTERNAL", "ADOPT_EXTERNAL", "RETRY"]
  if (call.verified === "RECEIVED") return ["TRACK_EXTERNAL", "ADOPT_EXTERNAL"]
  return ["TRACK_EXTERNAL", "DISCLOSE"]
}

/** 任务工作台列表（demo list 形状）：全量任务按创建时间倒序，行内嵌任务树（方案 §5.3 不跳页看进度）。 */
export function listTasks(): DemoTaskListRow[] {
  const rows = [...tasks.values(), ...demoTaskExtras.values()].map((t) => ({
    ...t,
    availableActions: demoProjectedActions(t as DemoTaskSeedExtra),
    subtasks: demoTaskExtras.get(t.taskId)?.subtasks ?? [],
  }))
  return rows.sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1))
}

/** 工作台详情（卡内展开）：冻结 taskDetail 优先，演示种子补齐 pendingCalls/恢复投影。 */
export function listTaskDetail(taskId: string): DemoTaskDetail | null {
  const extra = demoTaskExtras.get(taskId)
  const base = taskDetail(taskId)
  if (base) return { ...base, subtasks: extra?.subtasks ?? [], pendingCalls: extra?.pendingCalls ?? [] }
  if (!extra) return null
  return { ...extra, canConfirm: extra.plan?.confirmation.state !== "CONFIRMED_VALID",
    canExecute: extra.plan?.confirmation.state === "CONFIRMED_VALID" && extra.plan.validationStatus === "VALID",
    availableActions: demoProjectedActions(extra), eventWatermark: extra.events.at(-1)?.seq ?? 0,
    subtasks: extra.subtasks ?? [], pendingCalls: extra.pendingCalls ?? [] }
}

/** 取消（demo）：非终态→CANCELLED + TASK_FINISHED 事件；联调期由服务端 CAS 语义承接。 */
export function cancelTask(req: { taskId: string; reason?: string }):
  { taskId: string; state: TaskState } | { error: string; httpStatus: number; userMessage: string } {
  const t = findDemoTask(req.taskId)
  if (!t) return { error: "TASK_NOT_FOUND", httpStatus: 404, userMessage: "任务不存在" }
  if (terminal(t.state)) return { error: "TASK_TERMINAL", httpStatus: 409, userMessage: "终态任务保留为只读记录，不可取消" }
  t.state = "CANCELLED"
  t.stateVersion += 1
  pushEvent(t, "TASK_FINISHED", { state: "CANCELLED", reason: req.reason ?? "OPERATOR_CANCEL" })
  return { taskId: t.taskId, state: t.state }
}

/** 手动恢复（demo）：与自动恢复同一入口先到先得（pending 如实返回）；成功→RUNNING + TASK_RESUMED 事件与角色投影更新。 */
export function resumeTask(req: { taskId: string }):
  { taskId: string; state: TaskState } | { taskId: string; pending: true; userMessage: string }
  | { error: string; httpStatus: number; userMessage: string } {
  const t = findDemoTask(req.taskId)
  if (!t) return { error: "TASK_NOT_FOUND", httpStatus: 404, userMessage: "任务不存在" }
  if (terminal(t.state)) return { error: "TASK_TERMINAL", httpStatus: 409, userMessage: "终态任务不可恢复" }
  const extra = demoTaskExtras.get(req.taskId)
  if (!extra?.resumable) return { error: "RESUME_NOT_ELIGIBLE", httpStatus: 409, userMessage: "恢复资格由服务端按事实计算，当前任务不成立" }
  if (extra.autoRecoveryLease)
    return { taskId: t.taskId, pending: true, userMessage: "自动恢复已持租约且未提交：同一恢复入口先到先得，稍后按事件或详情读取恢复事实" }
  t.state = "RUNNING"
  t.stateVersion += 1
  const recovery = { recoverySource: "MANUAL" as const, reasonCode: "MANUAL_REQUEST",
    reasonSummary: "运维手动恢复：从最近检查点续跑，复用已完成步骤",
    checkpoint: extra.recovery?.checkpoint, reusedSteps: extra.recovery?.reusedSteps }
  t.recovery = recovery
  pushEvent(t, "TASK_RESUMED", { recoverySource: recovery.recoverySource, reasonCode: recovery.reasonCode,
    checkpoint: recovery.checkpoint, reusedSteps: recovery.reusedSteps, blockedReasons: extra.recovery?.blockedReasons })
  return { taskId: t.taskId, state: t.state }
}

/** 对账处置（demo，绑定 callId）：提交时按当时证据复核，RETRY 对已受理调用 409 RECONCILIATION_EVIDENCE_CONFLICT。 */
export function reconcileTaskCall(req: { taskId: string; stepId: string; callId: string;
  action: "TRACK_EXTERNAL" | "ADOPT_EXTERNAL" | "RETRY" | "DISCLOSE" }):
  { taskId: string; state: TaskState; action: string; callId: string } | { error: string; httpStatus: number; userMessage: string } {
  const t = findDemoTask(req.taskId)
  if (!t) return { error: "TASK_NOT_FOUND", httpStatus: 404, userMessage: "任务不存在" }
  if (t.state !== "WAITING_RECONCILIATION")
    return { error: "TASK_NOT_PENDING_RECONCILIATION", httpStatus: 409, userMessage: "任务不在对账等待态，处置入口仅对未决任务开放" }
  const extra = demoTaskExtras.get(req.taskId)
  const call = extra?.pendingCalls?.find((c) => c.callId === req.callId && c.stepId === req.stepId)
  if (!call) return { error: "CALL_NOT_FOUND", httpStatus: 404, userMessage: "未决调用不存在或已处置" }
  if (req.action === "RETRY" && call.verified !== "UNRECEIVED")
    return { error: "RECONCILIATION_EVIDENCE_CONFLICT", httpStatus: 409, userMessage: `对账证据复核不符：${call.callId} 已被外部受理，禁止重发（前端不提供自由重试）` }
  pushEvent(t, "RECONCILIATION_REQUIRED", { stepId: req.stepId, callId: req.callId, resolution: req.action })
  if (req.action === "RETRY" || req.action === "ADOPT_EXTERNAL") {
    t.state = "RUNNING"
    t.stateVersion += 1
    if (extra) extra.pendingCalls = (extra.pendingCalls ?? []).filter((c) => c.callId !== req.callId)
  }
  if (req.action === "DISCLOSE") call.disclosed = true
  return { taskId: t.taskId, state: t.state, action: req.action, callId: req.callId }
}

/** 测试隔离：恢复演示种子（含冻结两粒的深拷贝回填；不清理 submitTask 产生的请求注册，避免影响幂等语义）。 */
export function resetTaskConsoleDemo(): void {
  demoTaskSeeds().forEach((t) => tasks.set(t.taskId, structuredClone(t)))
  seedDemoTaskExtras()
}
