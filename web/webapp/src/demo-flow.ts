/**
 * F4-3/F4-2 演示消息流装配（M5-2：演示示意数据内联本模块，页面/共享层不再 import mock-*）：
 * 会话切换时按 sessionId 装配示意消息（文本气泡 + 三类卡片）；命中演示会话 id 才有内容，
 * 真实会话（新建/服务端列表）返回空流。消息流真实交互（发送→submitTask→任务卡）由
 * AssistantChat 驱动，任务/计划卡数据面为真实端点（shared/api-task / shared/api-plan）。
 */

export interface ToolCallView {
  /** 技能/步骤名。 */
  name: string
  /** 参数摘要（折叠态单行）。 */
  paramsSummary: string
  state: string
  params: Record<string, unknown>
  result?: string
}

export type FlowMessage =
  | { key: string; kind: "text"; role: "user" | "assistant"; text: string }
  | { key: string; kind: "tool"; view: ToolCallView }
  | { key: string; kind: "plan"; taskId: string }
  | { key: string; kind: "task"; taskId: string }

export interface SessionView {
  sessionId: string
  title: string
  lastActiveAt: string
  state: string
  appCode: string
}

/** 示意任务行（task-states 13 态口径；仅消息流装配用，任务卡详情走真实端点）。 */
interface DemoFlowTask {
  taskId: string
  steps: { stepId: string; state: string; resultDigest?: string }[]
}

const demoFlowTasks: DemoFlowTask[] = [
  {
    taskId: "task-demo-77",
    steps: [
      { stepId: "detect_a", state: "SUCCEEDED", resultDigest: "ab41" },
      { stepId: "detect_b", state: "RUNNING" },
    ],
  },
  { taskId: "task-demo-78", steps: [] },
]

const summarize = (params: Record<string, unknown>) => {
  const pairs = Object.entries(params).map(([k, v]) => `${k}=${String(v)}`)
  const text = pairs.join(", ")
  return text.length > 32 ? `${text.slice(0, 32)}…` : text
}

/** 由任务步骤投影工具调用卡视图（F4-3 默认折叠语义见 ToolCallCard）。 */
export function toolCallViewsOf(task: DemoFlowTask): ToolCallView[] {
  return task.steps.map((step, index) => ({
    name: step.stepId,
    paramsSummary: summarize({ dataset: "ds-2026-11-13", algorithm: step.stepId }),
    state: step.state,
    params: { dataset: "ds-2026-11-13", algorithm: step.stepId, batch: index + 1 },
    result: step.resultDigest ? `结果摘要 ${step.resultDigest}` : undefined,
  }))
}

let localSeq = 0
export const nextKey = (prefix: string) => `${prefix}-${++localSeq}`

/** 演示消息流：仅命中演示会话 id；其余（真实/新建会话）返回空流。 */
export function demoFlowFor(sessionId: string): FlowMessage[] {
  const [runTask, planTask] = demoFlowTasks
  if (sessionId === "sess-demo-28" && runTask) {
    return [
      { key: nextKey("u"), kind: "text", role: "user", text: "对同一数据集运行两种识别算法并生成差异报告" },
      { key: nextKey("a"), kind: "text", role: "assistant", text: "已受理为 Agent 任务，正在按步骤执行；过程可在任务卡跟踪。" },
      ...toolCallViewsOf(runTask).map((view) => ({ key: nextKey("t"), kind: "tool", view }) as FlowMessage),
      { key: nextKey("k"), kind: "task", taskId: runTask.taskId },
    ]
  }
  if (sessionId === "sess-demo-27" && planTask) {
    return [
      { key: nextKey("u"), kind: "text", role: "user", text: "上次差异报告的第 3 节需要补充对比" },
      { key: nextKey("a"), kind: "text", role: "assistant", text: "已为该目标生成规划任务，请确认计划版本后执行。" },
      { key: nextKey("p"), kind: "plan", taskId: planTask.taskId },
    ]
  }
  return []
}
