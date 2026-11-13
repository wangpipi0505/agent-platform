/**
 * F3-3 mock 契约：MCP 连接域——接口稿 §5.6（skill-registry）：
 * POST /admin/mcp-connections、POST /{id}/test（连通测试返回具体失败类别）、
 * POST /{id}/discover（发现工具清单；批次串行 batch_no）、GET /admin/mcp-connections/{id}
 * （状态/revision/目录版本=最近一次成功发现批次 batch_no/最近测试结果与失败原因）、
 * GET /{id}/tools?diff=true（快照+差异 ADDED/CHANGED/REMOVED）、POST .../tools/review
 * （审核生成技能草稿，返回 skillDraftRef）。
 * test/discover 绑定当前连接修订号：配置变更 revision+1，旧修订结果拦截不入目录（评审 12）。
 * 仅 demo 装载。
 */

export type McpTransport = "STDIO" | "HTTP" | "SSE"
export type McpStatus = "UNTESTED" | "CONNECTED" | "FAILED"
export type McpFailureCategory = "AUTH" | "TIMEOUT" | "PROTOCOL" | "ENDPOINT_UNREACHABLE"
export type McpToolChangeType = "ADDED" | "CHANGED" | "REMOVED"

export interface McpTool {
  toolName: string
  description: string
  inputDigest: string
  /** 最近一次成功发现批次（batch_no）；快照差异按上次目录批次对比。 */
  batchNo: number
  changeType?: McpToolChangeType
}

export interface MockMcpConnection {
  id: string
  name: string
  endpoint: string
  transport: McpTransport
  /** 连接修订号：test/discover 绑定当前修订，配置变更 +1，旧修订结果不入目录（评审 12）。 */
  revision: number
  status: McpStatus
  /** 目录版本：最近一次成功发布的完整发现批次号（分页不完整不生效）。 */
  catalogBatchNo?: number
  lastTest?: { testedAt: string; revision: number; result: "OK" } |
    { testedAt: string; revision: number; result: "FAILED"; failureCategory: McpFailureCategory; message: string }
  /** 演示开关：下一次连通测试走失败分支（指定失败类别）。 */
  failNextTest?: McpFailureCategory
  demo: true
}

const connections = new Map<string, MockMcpConnection>()
/** 连接 → 工具目录（按发现批次累积；revision 不匹配的批次不入目录）。 */
const catalogs = new Map<string, McpTool[]>()
/** 连接 → 发现批次序号（batch_no，串行递增）。 */
const batchSeq = new Map<string, number>()
/** 连接 → 进行中批次（串行约束：同一连接同时只允许一个发现批次）。 */
const runningBatch = new Map<string, number>()
/** 审核生成的技能草稿（skillDraftRef）。 */
const skillDrafts = new Map<string, { draftId: string; code: string; name: string }>()
let connSeq = 0
let draftSeq = 0

const digest = (value: string) => {
  let h = 2166136261
  for (let i = 0; i < value.length; i++) {
    h ^= value.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return (h >>> 0).toString(16).padStart(8, "0")
}

interface McpSeed {
  connection: MockMcpConnection
  tools: { toolName: string; description: string }[]
}

const seedMcp = (): McpSeed[] => [
  { connection: { id: "mcp-1", name: "卫星数据网关", endpoint: "https://mcp.example.com/satellite", transport: "HTTP",
    revision: 3, status: "CONNECTED", catalogBatchNo: 4,
    lastTest: { testedAt: "2026-11-12T09:00:00Z", revision: 3, result: "OK" }, demo: true },
    tools: [
      { toolName: "query_track", description: "查询轨道根数与星历" },
      { toolName: "fetch_imagery", description: "拉取影像元数据" },
    ] },
  { connection: { id: "mcp-2", name: "内网文档 MCP", endpoint: "http://docs.internal/mcp", transport: "SSE",
    revision: 1, status: "UNTESTED", demo: true },
    tools: [] },
]
function resetMcpDemoData(): void {
  connections.clear()
  catalogs.clear()
  batchSeq.clear()
  runningBatch.clear()
  skillDrafts.clear()
  connSeq = 0
  draftSeq = 0
  seedMcp().forEach((seed) => {
    connections.set(seed.connection.id, seed.connection)
    batchSeq.set(seed.connection.id, seed.connection.catalogBatchNo ?? 0)
    catalogs.set(seed.connection.id, seed.tools.map((t) => ({
      ...t, inputDigest: digest(t.toolName + t.description),
      batchNo: seed.connection.catalogBatchNo ?? 0,
    })))
  })
}
resetMcpDemoData()

/** GET /admin/mcp-connections（demo 列表形状）。 */
export function listMcpConnections(): MockMcpConnection[] {
  return [...connections.values()]
}

/** GET /admin/mcp-connections/{id}：状态/revision/目录版本/最近测试与失败原因。 */
export function getMcpConnection(id: string): MockMcpConnection | { error: string; httpStatus: number } {
  const c = connections.get(id)
  if (!c) return { error: "CONNECTION_NOT_FOUND", httpStatus: 404 }
  return c
}

/** POST /admin/mcp-connections：登记连接档案。 */
export function createMcpConnection(req: { name: string; endpoint: string; transport: McpTransport }):
  MockMcpConnection | { error: string; httpStatus: number; userMessage: string } {
  if (!req.name?.trim() || !req.endpoint?.trim())
    return { error: "PARAM_CONNECTION_INVALID", httpStatus: 400, userMessage: "名称与端点必填" }
  const row: MockMcpConnection = {
    id: `mcp-${++connSeq + 2}`, name: req.name.trim(), endpoint: req.endpoint.trim(),
    transport: req.transport, revision: 1, status: "UNTESTED", demo: true,
  }
  connections.set(row.id, row)
  catalogs.set(row.id, [])
  batchSeq.set(row.id, 0)
  return row
}

/** 配置变更（demo：端点/传输/名称编辑）：revision+1，旧修订的测试/发现结果不入目录。 */
export function updateMcpConnection(id: string, req: { name?: string; endpoint?: string; transport?: McpTransport }):
  MockMcpConnection | { error: string; httpStatus: number } {
  const c = connections.get(id)
  if (!c) return { error: "CONNECTION_NOT_FOUND", httpStatus: 404 }
  if (req.name !== undefined) c.name = req.name
  if (req.endpoint !== undefined) c.endpoint = req.endpoint
  if (req.transport !== undefined) c.transport = req.transport
  c.revision += 1
  return c
}

/**
 * POST /{id}/test：连通测试（按次建连），绑定当前修订号。
 * 成功 → {result:'OK', revision}；失败返回**具体失败类别**（AUTH/TIMEOUT/PROTOCOL/ENDPOINT_UNREACHABLE）。
 */
export function testMcpConnection(id: string):
  { id: string; revision: number; result: "OK" }
  | { id: string; revision: number; result: "FAILED"; failureCategory: McpFailureCategory; message: string }
  | { error: string; httpStatus: number } {
  const c = connections.get(id)
  if (!c) return { error: "CONNECTION_NOT_FOUND", httpStatus: 404 }
  const testedAt = new Date().toISOString()
  if (c.failNextTest) {
    const category = c.failNextTest
    c.failNextTest = undefined
    c.status = "FAILED"
    c.lastTest = { testedAt, revision: c.revision, result: "FAILED", failureCategory: category,
      message: `连接失败：${category}（演示失败类别）` }
    return { id, revision: c.revision, result: "FAILED", failureCategory: category, message: c.lastTest.message }
  }
  c.status = "CONNECTED"
  c.lastTest = { testedAt, revision: c.revision, result: "OK" }
  return { id, revision: c.revision, result: "OK" }
}

/**
 * POST /{id}/discover：发现工具清单（批次串行，batch_no 递增）。
 * 同一连接已有进行中批次 → 409 DISCOVERY_IN_PROGRESS；发现绑定当前修订号，
 * 返回 `{batchNo, revision, status, toolCount}`——工具入目录前由快照差异/审核确认。
 */
export function discoverMcpTools(id: string):
  { batchNo: number; revision: number; status: "RUNNING"; toolCount: number }
  | { error: string; httpStatus: number; userMessage?: string } {
  const c = connections.get(id)
  if (!c) return { error: "CONNECTION_NOT_FOUND", httpStatus: 404 }
  if (runningBatch.has(id))
    return { error: "DISCOVERY_IN_PROGRESS", httpStatus: 409,
      userMessage: `发现批次串行：批次 ${runningBatch.get(id)} 进行中` }
  const batchNo = (batchSeq.get(id) ?? 0) + 1
  batchSeq.set(id, batchNo)
  runningBatch.set(id, batchNo)
  return { batchNo, revision: c.revision, status: "RUNNING", toolCount: catalogs.get(id)?.length ?? 0 }
}

/**
 * GET /{id}/tools?diff=true：快照+差异（demo：完成进行中批次并生成目录差异）。
 * 差异按上次成功目录对比：新批次工具全量入目录（完整批次才生效——分页不完整不生效），
 * 逐项标 ADDED/CHANGED/REMOVED；测试/发现绑定修订号——修订过期的批次不更新目录。
 */
export function listMcpTools(id: string, query: { diff?: boolean } = {}):
  { tools: McpTool[]; diff?: { added: McpTool[]; changed: McpTool[]; removed: McpTool[] }; batchNo?: number }
  | { error: string; httpStatus: number } {
  const c = connections.get(id)
  if (!c) return { error: "CONNECTION_NOT_FOUND", httpStatus: 404 }
  let batchNo: number | undefined
  if (runningBatch.has(id)) {
    batchNo = runningBatch.get(id)
    runningBatch.delete(id)
    // 演示：新批次发现出 1 个新增工具 + 1 个描述变更；修订以连接当前 revision 为准（绑定）。
    const catalog = catalogs.get(id) ?? []
    const added: McpTool = { toolName: `tool_b${batchNo}_new`, description: `批次 ${batchNo} 新增工具`,
      inputDigest: digest(`${id}:${batchNo}:new`), batchNo: batchNo!, changeType: "ADDED" }
    const target = catalog[0]
    const changed: McpTool[] = target
      ? [{ ...target, description: `${target.description}（批次 ${batchNo} 更新输入摘要）`,
           inputDigest: digest(`${id}:${batchNo}:${target.toolName}`), batchNo: batchNo!, changeType: "CHANGED" }]
      : []
    const next = [...changed, ...catalog.slice(1).map((t) => ({ ...t, changeType: undefined })), added]
    catalogs.set(id, next)
    c.catalogBatchNo = batchNo
  }
  const tools = catalogs.get(id) ?? []
  if (!query.diff) return { tools, batchNo }
  const added = tools.filter((t) => t.changeType === "ADDED")
  const changed = tools.filter((t) => t.changeType === "CHANGED")
  const removedBatch = Math.max(0, (c.catalogBatchNo ?? 1) - 1)
  const removed: McpTool[] = batchNo && removedBatch > 0
    ? [{ toolName: `tool_b${removedBatch}_retired`, description: `批次 ${batchNo} 移除：上批次占位工具`,
        inputDigest: digest(`${id}:${removedBatch}:retired`), batchNo: removedBatch, changeType: "REMOVED" }]
    : []
  return { tools: tools.map((t) => ({ ...t })), diff: { added, changed, removed }, batchNo: batchNo ?? c.catalogBatchNo }
}

/** POST .../tools/review：审核差异后生成技能草稿（返回 skillDraftRef）。 */
export function reviewMcpTools(id: string, req: { toolName: string }):
  { skillDraftRef: { draftId: string; code: string; name: string } } | { error: string; httpStatus: number; userMessage?: string } {
  const c = connections.get(id)
  if (!c) return { error: "CONNECTION_NOT_FOUND", httpStatus: 404 }
  const tool = (catalogs.get(id) ?? []).find((t) => t.toolName === req.toolName)
  if (!tool) return { error: "TOOL_NOT_FOUND", httpStatus: 404, userMessage: "该连接目录中无此工具" }
  const draftId = `skill-draft-${++draftSeq}`
  const ref = { draftId, code: `mcp_${tool.toolName}`, name: `${c.name}·${tool.toolName}` }
  skillDrafts.set(tool.toolName, ref)
  return { skillDraftRef: ref }
}

/** 演示种子复位（测试用）。 */
export function resetMcpDemo(): void {
  resetMcpDemoData()
}
