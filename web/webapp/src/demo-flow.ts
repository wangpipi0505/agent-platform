/**
 * F4-3/F4-2 mock 期演示消息流（demo:true 数据，全部消费 shared/mock-task T2-14 契约）：
 * 会话切换时按 sessionId 装配演示消息（文本气泡 + 三类卡片），消息流真实交互
 * （发送→submitTask→任务卡）由 AssistantChat 驱动。真实会话事件接入属阶段二联调。
 */
import { demoTaskSeeds, type MockTaskSeed } from "../../shared/mock-task";

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

const summarize = (params: Record<string, unknown>) => {
  const pairs = Object.entries(params).map(([k, v]) => `${k}=${String(v)}`)
  const text = pairs.join(", ")
  return text.length > 32 ? `${text.slice(0, 32)}…` : text
}

/** 由任务步骤投影工具调用卡视图（与任务工作台同源 mock-task；F4-3 默认折叠语义见 ToolCallCard）。 */
export function toolCallViewsOf(task: MockTaskSeed): ToolCallView[] {
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

/** 演示消息流：仅命中 mock-session 种子会话；其余（新建会话）返回空流。 */
export function demoFlowFor(sessionId: string): FlowMessage[] {
  const [runTask, planTask] = demoTaskSeeds()
  if (sessionId === "sess-demo-28") {
    return [
      { key: nextKey("u"), kind: "text", role: "user", text: "对同一数据集运行两种识别算法并生成差异报告" },
      { key: nextKey("a"), kind: "text", role: "assistant", text: "已受理为 Agent 任务，正在按步骤执行；过程可在任务卡跟踪。" },
      ...toolCallViewsOf(runTask).map((view) => ({ key: nextKey("t"), kind: "tool", view }) as FlowMessage),
      { key: nextKey("k"), kind: "task", taskId: runTask.taskId },
    ]
  }
  if (sessionId === "sess-demo-27") {
    return [
      { key: nextKey("u"), kind: "text", role: "user", text: "上次差异报告的第 3 节需要补充对比" },
      { key: nextKey("a"), kind: "text", role: "assistant", text: "已为该目标生成规划任务，请确认计划版本后执行。" },
      { key: nextKey("p"), kind: "plan", taskId: planTask.taskId },
    ]
  }
  return []
}
