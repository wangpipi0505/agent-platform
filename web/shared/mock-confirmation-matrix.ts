/**
 * F3-3 mock 契约：确认中心四类待办矩阵（前端稿 §5.9 待办矩阵表）。
 * 四类待办的来源、可见范围、处理角色与提交端点（§5.9 表格逐类落形）：
 * - 发布评审：GET /admin/apps/{code}/publish-requests?status=PENDING（接口稿 §5.7，契约先行）；
 *   只读查看=APPROVER/ADMIN，审批提交=现有 ConfigReleaseController 审批动作（首期 ADMIN）。
 * - 高危操作：现有 InlineApproval 待审（runId+nodeId 决策入口；双主体确认链 V224，
 *   继续走运行审批，不经计划确认）→ InlineApprovalController 现有端点。
 * - 计划确认：GET /v1/tasks?state=PLAN_READY&confirmableBy=me——以修订为单位
 *   {taskId, planId, revision, digest, confirmState}，仅返回无有效凭据的修订；
 *   处理=跳计划详情/助手端确认（POST .../revisions/{revision}/confirm）。
 * - DRAFT 记忆：GET /v1/apps/{code}/memories?status=DRAFT；ADMIN（共享层）/来源用户（私有层）
 *   经 PATCH {status: ACTIVE|REVOKED, expectedVersion} 审批（消费 mock-memories demoApproveMemory）。
 * 角标口径（接口稿 GET /admin/confirmation-center/counts）：total=四类未分页总数之和；
 * 处置后列表与角标同源刷新。仅 demo 装载。
 */
import { demoListDraftMemories, demoApproveMemory, type MockMemoryDetail } from "./mock-memories";
import { listTasks, type DemoTaskListRow } from "./mock-task";

// —— ① 发布评审待办（publishId 与待审 releaseId 1:1 的待审视图，接口稿 §5.7）——

export interface DemoPublishRequest {
  publishId: string
  appCode: string
  targetVersion: string
  manifestRevision: number
  digest: string
  precheckSummary: string
  requestedBy: string
  requestedAt: string
  status: "PENDING" | "APPROVED" | "REJECTED"
  /** 处理角色口径（§5.9）：APPROVER 待 authority 扩展前只读可见，不提前放开提交。 */
  canApprove: boolean
  readonly?: true
  demo: true
}

const publishRequests = new Map<string, DemoPublishRequest>()

const seedPublishRequests = (): DemoPublishRequest[] => [
  { publishId: "pub-301", appCode: "recon-assistant", targetVersion: "v1.6.0", manifestRevision: 12,
    digest: "ab12cd", precheckSummary: "预检通过：模板/Schema/插件引用齐备", requestedBy: "u-builder",
    requestedAt: "2026-11-13T09:00:00Z", status: "PENDING", canApprove: true, demo: true },
  { publishId: "pub-302", appCode: "satellite-planning-assistant", targetVersion: "v2.3.0", manifestRevision: 8,
    digest: "ef34ab", precheckSummary: "预检通过：规则引用 no-direct-delete@1 生效", requestedBy: "u-builder",
    requestedAt: "2026-11-13T10:20:00Z", status: "PENDING", canApprove: false, readonly: true, demo: true },
]

// —— ② 高危操作待办（InlineApproval 形状：runId+nodeId；行内展开行动计划凭据摘要）——

export interface DemoHighRiskApproval {
  runId: string
  nodeId: string
  nodeDefId: string
  statusLabel: string
  status: string
  /** ActionPlanPanel 消费的行动计划快照形状（approval.ts actionPlanView 输入口）。 */
  parameterSnapshot: {
    actionPlan: {
      summary: string
      target: string
      diff: Record<string, unknown>
      risk: "HIGH"
      idempotencyKey: string
      expiresAt: string
    }
  }
  snapshotDigest: string
  expiresAt: string
  confirmRoles: string[]
  statusPending: boolean
  demo: true
}

const highRiskApprovals = new Map<string, DemoHighRiskApproval>()

const seedHighRisk = (): DemoHighRiskApproval[] => [
  { runId: "run-demo-551", nodeId: "node-delete-cache", nodeDefId: "purge_cache", statusLabel: "等待确认",
    status: "WAITING_APPROVAL",
    parameterSnapshot: { actionPlan: { summary: "清空识别结果缓存分区 cache-7", target: "cache-7",
      diff: { removedKeys: 128 }, risk: "HIGH", idempotencyKey: "k".repeat(64),
      expiresAt: "2099-01-01T00:00:00Z" } },
    snapshotDigest: "sha:9f21", expiresAt: "2099-01-01T00:00:00Z", confirmRoles: ["APPROVER"],
    statusPending: true, demo: true },
]

// —— 处置记录（发布评审/高危操作 demo 处置；联调期切回 ConfigRelease/InlineApproval 端点）——

export type DemoDecision = "APPROVE" | "REJECT"

/** 发布评审处置（mock）：批准=APPROVED（沿现有语义激活，批准与激活分离可配置）、拒绝=REJECTED。 */
export function decidePublishRequest(publishId: string, decision: DemoDecision):
  DemoPublishRequest | { error: string; httpStatus: number; userMessage: string } {
  const row = publishRequests.get(publishId)
  if (!row) return { error: "PUBLISH_NOT_FOUND", httpStatus: 404, userMessage: "待审发布不存在" }
  if (!row.canApprove)
    return { error: "PUBLISH_DECISION_DENIED", httpStatus: 403, userMessage: "APPROVER 为只读视角：审批提交=ADMIN（§5.9，authority 扩展前不放开）" }
  row.status = decision === "APPROVE" ? "APPROVED" : "REJECTED"
  return row
}

/** 高危操作处置（mock）：批准/拒绝写入待审状态（双主体链的持久审批事实在服务端）。 */
export function decideHighRiskApproval(runId: string, nodeId: string, decision: DemoDecision):
  DemoHighRiskApproval | { error: string; httpStatus: number; userMessage: string } {
  const row = highRiskApprovals.get(`${runId}/${nodeId}`)
  if (!row) return { error: "APPROVAL_NOT_FOUND", httpStatus: 404, userMessage: "高危待审不存在" }
  row.statusPending = false
  row.status = decision === "APPROVE" ? "APPROVED" : "REJECTED"
  row.statusLabel = decision === "APPROVE" ? "已批准" : "已拒绝"
  return row
}

/** ① 发布评审待审列表（status=PENDING 口径）。 */
export function listPendingPublishRequests(): DemoPublishRequest[] {
  return [...publishRequests.values()].filter((row) => row.status === "PENDING")
}

/** ② 高危操作待审列表。 */
export function listPendingHighRiskApprovals(): DemoHighRiskApproval[] {
  return [...highRiskApprovals.values()].filter((row) => row.statusPending)
}

/** ③ 计划确认待办（以修订为单位；仅无有效凭据——UNCONFIRMED/CONFIRMED_EXPIRED——的修订）。 */
export interface DemoPlanConfirmationTodo {
  taskId: string
  planId: string
  revision: number
  digest: string
  goal: string
  confirmState: "UNCONFIRMED" | "CONFIRMED_VALID" | "CONFIRMED_EXPIRED"
  demo: true
}

export function listPlanConfirmationTodos(tasks?: DemoTaskListRow[]): DemoPlanConfirmationTodo[] {
  const rows = tasks ?? listTasks()
  return rows
    .filter((t) => t.plan && t.state === "PLAN_READY" && t.plan.confirmation.state !== "CONFIRMED_VALID")
    .map((t) => ({
      taskId: t.taskId, planId: t.plan!.planId, revision: t.plan!.revision, digest: t.plan!.digest,
      goal: t.goal, confirmState: t.plan!.confirmation.state, demo: true as const,
    }))
}

/** ④ DRAFT 记忆待办（含正文预览；审批走 PATCH status 字段语义）。 */
export function listDraftMemoryTodos(scope?: "PRIVATE" | "SHARED"): MockMemoryDetail[] {
  return demoListDraftMemories(scope)
}

/** DRAFT 记忆审批（确认→ACTIVE、拒绝→REVOKED；CAS 过期 409）。 */
export function decideDraftMemory(memoryId: string, action: "ACTIVE" | "REVOKED", expectedVersion: number):
  MockMemoryDetail | { error: string; httpStatus: number } {
  return demoApproveMemory(memoryId, action, expectedVersion)
}

// —— 角标计数：四类未分页总数之和（接口稿 GET /admin/confirmation-center/counts 口径）——

export interface DemoConfirmationCounts {
  publishReview: number
  highRisk: number
  planConfirmation: number
  draftMemory: number
  total: number
}

export function computeDemoConfirmationCounts(): DemoConfirmationCounts {
  const publishReview = listPendingPublishRequests().length
  const highRisk = listPendingHighRiskApprovals().length
  const planConfirmation = listPlanConfirmationTodos().length
  const draftMemory = listDraftMemoryTodos().length
  return { publishReview, highRisk, planConfirmation, draftMemory, total: publishReview + highRisk + planConfirmation + draftMemory }
}

/** 演示种子复位（测试用）：发布评审/高危待审回种子；计划确认/DRAFT 记忆由各自 mock 复位。 */
export function resetConfirmationMatrixDemo(): void {
  publishRequests.clear()
  seedPublishRequests().forEach((row) => publishRequests.set(row.publishId, row))
  highRiskApprovals.clear()
  seedHighRisk().forEach((row) => highRiskApprovals.set(`${row.runId}/${row.nodeId}`, row))
}
