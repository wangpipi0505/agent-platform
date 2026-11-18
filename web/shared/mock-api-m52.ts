/**
 * M5-2：真实端点 URL 的演示路由（VITE_MOCK=true 时经 mock-transport 装载）。
 *
 * 页面数据面已切真实 API（shared/api-*，URL 语义经网关）；演示模式把这些 URL 委托给
 * 保留的 mock 域模块（mock-task/mock-plan/mock-session-detail/mock-session/mock-memories/
 * mock-rules/mock-plugins/mock-mcp/mock-confirmation-*），保证 VITE_MOCK 全页面脱离后端可
 * 用（A28 口径不变）。生产构建不含本模块（tree-shaking 剔除未引用路径）。
 */
import * as mockTask from './mock-task'
import * as mockSessionList from './mock-session'
import * as mockSessionDetail from './mock-session-detail'
import * as mockMemories from './mock-memories'
import * as mockRules from './mock-rules'
import * as mockPlugins from './mock-plugins'
import * as mockMcp from './mock-mcp'
import { fetchMockConfirmationCounts } from './mock-confirmation-counts'
import {
  listPendingPublishRequests,
  listPendingHighRiskApprovals,
  listPlanConfirmationTodos,
  listDraftMemoryTodos,
} from './mock-confirmation-matrix'

type JsonRow = Record<string, any>

function envelope(data: unknown) {
  return new Response(JSON.stringify({ code: 'OK', message: '', data }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}
function envelopeError(status: number, code: string, message: string) {
  return new Response(JSON.stringify({ code, message, data: null }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}
const asArray = (value: unknown): JsonRow[] => (Array.isArray(value) ? (value as JsonRow[]) : [])

/** demo:true 锚（演示数据纪律）：包装 mock 行，保持 demo 标记在位。 */
const withDemo = (rows: JsonRow[]) => rows.map((row) => ({ demo: true, ...(row.demo === undefined ? {} : { demo: true }), ...row }))

export function mockRealApiRequest(
  path: string,
  method: string,
  body: JsonRow | undefined,
  query: URLSearchParams,
): Response | undefined {
  // ── 任务域（engine TaskController 形状）──
  let match = path.match(/^\/v1\/tasks$/)
  if (match && method === 'GET') {
    if ((query.get('confirmableBy') ?? '') === 'me')
      return envelope({ items: listPlanConfirmationTodos(), appCode: query.get('appCode') ?? '' })
    return envelope({ tasks: withDemo(mockTask.listTasks()), page: 1, pageSize: 50, total: mockTask.listTasks().length })
  }
  match = path.match(/^\/v1\/tasks\/([^/]+)$/)
  if (match && method === 'GET') {
    const detail = mockTask.listTaskDetail(match[1]!) ?? mockTask.taskDetail(match[1]!)
    return detail ? envelope(detail) : envelopeError(404, 'TASK_NOT_FOUND', '任务不存在')
  }
  match = path.match(/^\/v1\/tasks\/([^/]+)\/events$/)
  if (match && method === 'GET')
    return envelope(mockTask.taskEvents(match[1]!, Number(query.get('afterSeq') ?? 0), Number(query.get('limit') ?? 200)))
  match = path.match(/^\/v1\/tasks\/([^/]+)\/cancel$/)
  if (match && method === 'POST') return envelopeOrError(mockTask.cancelTask({ taskId: match[1]!, reason: body?.reason }))
  match = path.match(/^\/v1\/tasks\/([^/]+)\/resume$/)
  if (match && method === 'POST') return envelopeOrError(mockTask.resumeTask({ taskId: match[1]! }))
  match = path.match(/^\/v1\/tasks\/([^/]+)\/steps\/([^/]+)\/calls\/([^/]+)\/reconcile$/)
  if (match && method === 'POST')
    return envelopeOrError(mockTask.reconcileTaskCall({ taskId: match[1]!, stepId: match[2]!, callId: match[3]!, action: String(body?.action ?? '') as never }))
  match = path.match(/^\/v1\/tasks\/([^/]+)\/steps\/([^/]+)\/calls$/)
  if (match && method === 'GET') return envelope({ taskId: match[1]!, stepId: match[2]!, calls: [], total: 0, page: 1 })
  match = path.match(/^\/v1\/apps\/([^/]+)\/tasks$/)
  if (match && method === 'POST') return envelopeOrError(mockTask.submitTask((body ?? {}) as never))

  // ── 会话域（engine ChatSessionController/SubagentCatalogController 形状）──
  match = path.match(/^\/v1\/apps\/([^/]+)\/sessions$/)
  if (match && method === 'GET') {
    const page = mockSessionList.listMySessions({ mine: true, before: query.get('before') ?? undefined })
    return envelope({ sessions: page.sessions, nextBefore: page.nextBefore })
  }
  match = path.match(/^\/v1\/apps\/([^/]+)\/sessions\/([^/]+)$/)
  if (match && method === 'GET')
    return unwrapOrPassthrough(mockSessionDetail.sessionSnapshot('', match[2]!, 'OPERATOR'))
  match = path.match(/^\/v1\/apps\/([^/]+)\/sessions\/([^/]+)\/events$/)
  if (match && method === 'GET')
    return unwrapOrPassthrough(
      mockSessionDetail.sessionEvents(match[1]!, match[2]!, { surfaceOnly: (query.get('surfaceOnly') ?? 'false') !== 'true' }, 'OPERATOR'),
    )
  match = path.match(/^\/v1\/sessions\/([^/]+)\/subagents$/)
  if (match && method === 'GET') return envelope(mockSessionDetail.sessionSubagents(match[1]!, 'OPERATOR'))
  match = path.match(/^\/v1\/tasks\/([^/]+)\/recovery$/)
  if (match && method === 'GET') return envelope(mockSessionDetail.taskRecovery(match[1]!, 'OPERATOR'))

  // ── 记忆域（engine MemoryController 形状）──
  match = path.match(/^\/v1\/apps\/([^/]+)\/memories$/)
  if (match && method === 'GET') {
    const page = mockMemories.listMemories({
      scope: (query.get('scope') ?? undefined) as never,
      status: (query.get('status') ?? undefined) as never,
      tags: query.get('tags') ?? undefined,
      q: query.get('q') ?? undefined,
      sessionId: query.get('sessionId') ?? undefined,
    })
    return envelope({ memories: page.memories, page: 1, pageSize: 100, total: page.memories.length })
  }
  if (match && method === 'POST') return envelopeOrError(mockMemories.createMemory((body ?? {}) as never))
  match = path.match(/^\/v1\/apps\/([^/]+)\/memories\/([^/]+)$/)
  if (match && method === 'GET') return envelopeOrError(mockMemories.getMemory(match[2]!))
  if (match && method === 'PATCH') return envelopeOrError(mockMemories.updateMemory(match[2]!, (body ?? {}) as never))
  if (match && method === 'DELETE') return envelopeOrError(mockMemories.deleteMemory(match[2]!, { requestId: String(body?.requestId ?? 'demo') }))

  // ── 规则域（app-management RuleController/AppRuleController 形状）──
  match = path.match(/^\/admin\/apps\/([^/]+)\/rules$/)
  if (match && method === 'GET') return envelope(mockRules.listRules(match[1]!).rules)
  if (match && method === 'POST') return envelopeOrError(mockRules.createRule({ ...(body ?? {}), appCode: match[1] } as never))
  match = path.match(/^\/admin\/rules\/([^/]+)\/draft$/)
  if (match && method === 'PUT') return envelopeOrError(mockRules.updateRuleDraft(match[1]!, { content: body?.content }))
  match = path.match(/^\/admin\/rules\/([^/]+)\/versions\/([^/]+)\/publish$/)
  if (match && method === 'POST') return envelopeOrError(mockRules.publishRule(match[1]!, Number(match[2])))
  match = path.match(/^\/admin\/rules\/([^/]+)\/versions$/)
  if (match && method === 'GET') return envelopeOrError(mockRules.listRuleVersions(match[1]!))
  match = path.match(/^\/admin\/rules\/([^/]+)\/(enable|disable)$/)
  if (match && method === 'POST') return envelopeOrError(mockRules.setRuleEnabled(match[1]!, match[2] === 'enable', body?.reason))

  // ── 插件域（skill-registry PluginGovernanceController 形状）──
  if (path === '/admin/plugin-sources' && method === 'GET') return envelope(mockPlugins.listPluginSources())
  if (path === '/admin/plugin-sources' && method === 'POST') return envelopeOrError(mockPlugins.createPluginSource((body ?? {}) as never))
  match = path.match(/^\/admin\/plugin-sources\/([^/]+)\/discover$/)
  if (match && method === 'POST') return envelopeOrError(mockPlugins.triggerDiscovery(match[1]!))
  match = path.match(/^\/admin\/plugin-sources\/([^/]+)\/discoveries\/([^/]+)$/)
  if (match && method === 'GET') return envelopeOrError(mockPlugins.pollDiscovery(match[1]!, match[2]!))
  if (path === '/admin/plugins' && method === 'GET') {
    const status = query.get('status')
    const source = query.get('source')
    return envelope(mockPlugins.listPlugins({ ...(status ? { status } : {}), ...(source ? { source } : {}) } as never))
  }
  match = path.match(/^\/admin\/plugins\/([^/]+)\/(enable|disable)$/)
  if (match && method === 'POST')
    return envelopeOrError(mockPlugins.setPluginEnabled(match[1]!, match[2] === 'enable', body?.reason))
  match = path.match(/^\/admin\/plugins\/([^/]+)\/grants$/)
  if (match && method === 'POST') return envelopeOrError(mockPlugins.addPluginGrant(match[1]!, (body ?? {}) as never))
  match = path.match(/^\/admin\/plugins\/grants\/([^/]+)$/)
  if (match && method === 'DELETE') return envelopeOrError(mockPlugins.removePluginGrant(match[1]!))

  // ── MCP 连接域（skill-registry McpConnectionController 形状）──
  if (path === '/admin/mcp-connections' && method === 'POST') return envelopeOrError(mockMcp.createMcpConnection((body ?? {}) as never))
  match = path.match(/^\/admin\/mcp-connections\/([^/]+)$/)
  if (match && method === 'GET') return envelopeOrError(mockMcp.getMcpConnection(match[1]!))
  if (match && method === 'PUT') return envelopeOrError(mockMcp.updateMcpConnection(match[1]!, (body ?? {}) as never))
  match = path.match(/^\/admin\/mcp-connections\/([^/]+)\/test$/)
  if (match && method === 'POST') return envelopeOrError(mockMcp.testMcpConnection(match[1]!))
  match = path.match(/^\/admin\/mcp-connections\/([^/]+)\/discover$/)
  if (match && method === 'POST') return envelopeOrError(mockMcp.discoverMcpTools(match[1]!))
  match = path.match(/^\/admin\/mcp-connections\/([^/]+)\/tools$/)
  if (match && method === 'GET') return envelopeOrError(mockMcp.listMcpTools(match[1]!, { diff: query.get('diff') === 'true' }))
  match = path.match(/^\/admin\/mcp-connections\/([^/]+)\/tools\/review$/)
  if (match && method === 'POST') return envelopeOrError(mockMcp.reviewMcpTools(match[1]!, (body ?? {}) as never))

  // ── 确认中心（engine ConfirmationCenterController 形状 + §5.9 矩阵）──
  if (path === '/admin/confirmation-center/counts' && method === 'GET') {
    const counts = fetchMockConfirmationCounts() as unknown as JsonRow
    const { demo: _demo, ...rest } = counts
    return envelope(rest)
  }
  if (path === '/admin/confirmation-center/todos' && method === 'GET')
    return envelope({
      publishReview: listPendingPublishRequests(),
      highRisk: listPendingHighRiskApprovals(),
      planConfirmation: listPlanConfirmationTodos(),
      draftMemory: listDraftMemoryTodos(),
    })
  return undefined
}

/** mock 结果 → 信封 Response；{error,...} 形状如实映射为错误信封（api 层降形为 error 对象）。 */
function envelopeOrError(result: unknown): Response {
  if (result && typeof result === 'object' && 'error' in (result as JsonRow))
    return envelopeError(Number((result as JsonRow).httpStatus ?? 400), String((result as JsonRow).error), String((result as JsonRow).userMessage ?? ''))
  return envelope(result)
}

/** DemoSnapshot|DemoReject 形状：reject 按错误信封透传（api 层 catch 后以 httpStatus/userMessage 呈现）。 */
function unwrapOrPassthrough(result: unknown): Response {
  if (result && typeof result === 'object' && 'httpStatus' in (result as JsonRow))
    return envelopeError(Number((result as JsonRow).httpStatus), String((result as JsonRow).error ?? 'DEMO_REJECTED'), String((result as JsonRow).userMessage ?? ''))
  return envelope(result)
}
