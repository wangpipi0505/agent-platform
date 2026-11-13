/** F4-2 mock 契约：记忆域——接口稿 §5.4（GET/POST/PATCH/DELETE /v1/apps/{code}/memories）。仅 demo 装载。 */

export type MemoryKind = "PREFERENCE" | "EXPERIENCE"
export type MemoryScope = "PRIVATE" | "SHARED"
export type MemoryStatus = "ACTIVE" | "REVOKED"

/** 列表项形状（接口稿 §5.4：列表不含正文，正文经单条接口读取）。 */
export interface MockMemoryRow {
  memoryId: string
  kind: MemoryKind
  scope: MemoryScope
  tags: string[]
  version: number
  status: MemoryStatus
  expiresAt?: string
  sourceTaskId?: string
  demo: true
}

/** 单条详情：列表项 + 正文（GET /memories/{id} 返回体）。 */
export interface MockMemoryDetail extends MockMemoryRow {
  content: string
}

interface MemoryStore extends MockMemoryDetail {
  /** mock 固定主体（u-demo）：USER 仅本人 PRIVATE+已授权 SHARED 可见。 */
  principalId: string
}

const memories = new Map<string, MemoryStore>()
/** POST requestId 幂等账本：requestId → memoryId（同号重放返回原条目）。 */
const replayBook = new Map<string, string>()

const seedMemories = (): MemoryStore[] => [
  { memoryId: "mem-demo-1", principalId: "u-demo",
    kind: "PREFERENCE", scope: "PRIVATE", tags: ["观测偏好"], version: 2, status: "ACTIVE",
    content: "观测序列规划时云量阈值默认取 30%，冬季观测窗口优先早于 22:00。",
    sourceTaskId: "task-demo-77", demo: true },
  { memoryId: "mem-demo-2", principalId: "u-demo",
    kind: "EXPERIENCE", scope: "PRIVATE", tags: ["差异报告"], version: 1, status: "ACTIVE",
    content: "两种识别算法结果差异集中在低照度帧；对比表需附每帧置信度差值列。",
    demo: true },
]
seedMemories().forEach((m) => memories.set(m.memoryId, m))

const strip = (m: MemoryStore): Omit<MockMemoryRow, "demo"> => {
  const { content: _c, principalId: _p, demo: _d, ...rest } = m
  return rest
}
const detail = (m: MemoryStore): MockMemoryDetail => {
  const { principalId: _p, ...rest } = m
  return rest
}
/** 简单关键词匹配（q 命中正文或标签；真实实现为服务端检索，前端形状一致）。 */
const matchesQuery = (m: MemoryStore, q?: string) =>
  !q || m.content.includes(q) || m.tags.some((t) => t.includes(q))

/**
 * 本人记忆列表：先权限过滤（mock 主体固定 u-demo：本人 PRIVATE 全可见，
 * SHARED 仅 demo 授权条目，本种子集不含他人条目）再检索；REVOKED 立即退出检索
 * （接口稿 §5.4 DELETE 语义），默认仅 ACTIVE；q/status/tags 过滤。
 */
export function listMemories(query: { scope?: MemoryScope; status?: MemoryStatus; tags?: string; q?: string; sessionId?: string } = {}):
  { memories: Omit<MockMemoryRow, "demo">[] } {
  const rows = [...memories.values()]
    .filter((m) => m.status === (query.status ?? "ACTIVE"))
    .filter((m) => !query.scope || m.scope === query.scope)
    .filter((m) => !query.tags || query.tags.split(",").some((t) => m.tags.includes(t.trim())))
    .filter((m) => matchesQuery(m, query.q?.trim() || undefined))
  return { memories: rows.map(strip) }
}

/** 单条正文读取：不存在或已 REVOKED 按不可见处理（404 形状）。 */
export function getMemory(memoryId: string): MockMemoryDetail | { error: string; httpStatus: number } {
  const m = memories.get(memoryId)
  if (!m || m.status !== "ACTIVE") return { error: "MEMORY_NOT_FOUND", httpStatus: 404 }
  return detail(m)
}

/** 显式保存（POST）：requestId 幂等重放原条目；USER 写范围默认仅 PRIVATE（写 SHARED 拒绝）。 */
export function createMemory(req: { requestId: string; content: string; kind: MemoryKind; scope?: MemoryScope;
  tags?: string[]; expiresAt?: string; sessionId?: string; sourceTaskId?: string }):
  MockMemoryDetail | { error: string; httpStatus: number; userMessage: string } {
  if (!req.content?.trim()) return { error: "PARAM_CONTENT_REQUIRED", httpStatus: 400, userMessage: "正文不能为空" }
  const existing = replayBook.get(req.requestId)
  if (existing) return detail(memories.get(existing)!)
  const scope = req.scope ?? "PRIVATE"
  if (scope !== "PRIVATE")
    return { error: "SCOPE_WRITE_DENIED", httpStatus: 403, userMessage: "当前策略仅允许写入 PRIVATE（USER 不写 SHARED）" }
  const memoryId = `mem-demo-${memories.size + 3}`
  const row: MemoryStore = {
    memoryId, principalId: "u-demo", kind: req.kind, scope, tags: req.tags ?? [], version: 1,
    status: "ACTIVE", expiresAt: req.expiresAt, sourceTaskId: req.sourceTaskId,
    content: req.content.trim(), demo: true,
  }
  memories.set(memoryId, row)
  replayBook.set(req.requestId, memoryId)
  return detail(row)
}

/** 修改（PATCH）：expectedVersion 版本 CAS——过期 409 MEMORY_VERSION_CONFLICT；正文/标签/状态增量。 */
export function updateMemory(memoryId: string, req: { content?: string; tags?: string[]; expiresAt?: string;
  status?: MemoryStatus; expectedVersion: number }): MockMemoryDetail | { error: string; httpStatus: number } {
  const m = memories.get(memoryId)
  if (!m || m.status !== "ACTIVE") return { error: "MEMORY_NOT_FOUND", httpStatus: 404 }
  if (m.version !== req.expectedVersion) return { error: "MEMORY_VERSION_CONFLICT", httpStatus: 409 }
  if (req.content !== undefined) m.content = req.content.trim()
  if (req.tags !== undefined) m.tags = req.tags
  if (req.expiresAt !== undefined) m.expiresAt = req.expiresAt
  if (req.status !== undefined) m.status = req.status
  m.version += 1
  return detail(m)
}

/** 删除（DELETE）：先置 REVOKED（立即退出检索），幂等——重复删除返回同一终态。 */
export function deleteMemory(memoryId: string, _req: { requestId: string }):
  { memoryId: string; status: MemoryStatus } | { error: string; httpStatus: number } {
  const m = memories.get(memoryId)
  if (!m) return { error: "MEMORY_NOT_FOUND", httpStatus: 404 }
  m.status = "REVOKED"
  return { memoryId, status: "REVOKED" }
}
