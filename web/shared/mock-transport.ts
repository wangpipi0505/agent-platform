/**
 * A28/Mock：纯前端演示模式。
 * VITE_MOCK=true 时替换 transport + auth + SSE，全页面脱离后端可用。
 * 路由按 /admin 资源段匹配中文示例数据；未匹配读请求返回空集合、
 * 写请求返回空对象，保证任何页面可进、按钮可点。
 * 生产构建不含本模块（tree-shaking 剔除未引用路径）。
 */

// ── 通用信封 ──
function envelope(data: unknown) {
  return new Response(JSON.stringify({ code: 'OK', message: '', data }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}
function envelope404() {
  return new Response(JSON.stringify({ code: 'NOT_FOUND', message: '记录不存在', data: null }), {
    status: 404,
    headers: { 'Content-Type': 'application/json' },
  })
}

// ── 会话（VITE_AUTH_MODE=dev 时 PortalShell 经此取得演示票据与身份）──
const now = '2026-10-25T10:00:00Z'
const SESSION_IDENTITY = () => ({
  tenantId: 't-demo',
  principalId: 'u-demo',
  roles: ['ADMIN', 'EDITOR', 'APPROVER'],
  // 门户身份校验要求恰好四字段且未过期；动态生成保证演示永不过期。
  expiresAt: new Date(Date.now() + 24 * 3600 * 1000).toISOString().replace(/\.\d{3}Z$/, 'Z'),
})

// ── 智能体应用 ──
const apps = [
  {
    code: 'satellite-assistant', name: '卫星运行助手', displayName: '卫星运行助手', status: 'PUBLISHED',
    type: 'AGENT', domain: '航天', bizCategory: '运行支持', tags: ['在轨管理', '演示'],
    description: '面向卫星运行团队的问答与 orbit 查询助手。', currentVersionNo: 3, draftRev: 5,
    createdAt: '2026-09-12T08:00:00Z', updatedAt: '2026-10-24T15:20:00Z',
    config: { model: 'deepseek-chat', temperature: 0.3, maxTurns: 12 },
  },
  {
    code: 'payload-advisor', name: '载荷选型顾问', displayName: '载荷选型顾问', status: 'DRAFT',
    type: 'AGENT', domain: '航天', bizCategory: '论证支持', tags: ['选型'],
    description: '按任务需求推荐兼容载荷配置。', currentVersionNo: 1, draftRev: 2,
    createdAt: '2026-10-01T09:00:00Z', updatedAt: '2026-10-22T11:00:00Z',
    config: { model: 'deepseek-chat', temperature: 0.5 },
  },
  {
    code: 'anomaly-triage', name: '遥测异常分诊', displayName: '遥测异常分诊', status: 'PUBLISHED',
    type: 'FLOW', domain: '测控', bizCategory: '故障处置', tags: ['遥测', '分诊'],
    description: '对遥测告警执行规则分诊并生成处置建议。', currentVersionNo: 2, draftRev: 3,
    createdAt: '2026-09-20T08:00:00Z', updatedAt: '2026-10-21T17:40:00Z',
    config: { model: 'deepseek-reasoner', temperature: 0.1 },
  },
]
const appVersions = [
  { versionNo: 3, status: 'PUBLISHED', snapshotDigest: 'sha256-v3-app', publishedAt: '2026-10-24T15:20:00Z', publishedBy: '演示用户', note: '新增轨道查询技能' },
  { versionNo: 2, status: 'DEPRECATED', snapshotDigest: 'sha256-v2-app', publishedAt: '2026-10-10T10:00:00Z', publishedBy: '演示用户', note: '' },
]

// ── 模型供应商 / 条目 ──
const modelProviders = [
  { id: 'mp-001', code: 'deepseek', name: 'DeepSeek 官方', protocol: 'OPENAI_COMPATIBLE', baseUrl: 'https://api.deepseek.com/v1', status: 'ACTIVE', credentialStatus: 'SET', quota: { tpm: 200000, rpd: 20000 }, updatedAt: now },
  { id: 'mp-002', code: 'local-vllm', name: '内网 vLLM 网关', protocol: 'OPENAI_COMPATIBLE', baseUrl: 'http://127.0.0.1:18090/v1', status: 'ACTIVE', credentialStatus: 'SET', quota: { tpm: 60000, rpd: 6000 }, updatedAt: '2026-10-20T08:00:00Z' },
]
const modelEntries = [
  { id: 'me-001', providerId: 'mp-001', providerCode: 'deepseek', modelId: 'deepseek-chat', displayName: 'DeepSeek Chat', contextWindow: 65536, maxOutputTokens: 8192, capabilities: ['CHAT', 'TOOL_CALL'], status: 'ACTIVE', metering: { inputPer1k: 0.001, outputPer1k: 0.002 } },
  { id: 'me-002', providerId: 'mp-001', providerCode: 'deepseek', modelId: 'deepseek-reasoner', displayName: 'DeepSeek Reasoner', contextWindow: 65536, maxOutputTokens: 8192, capabilities: ['CHAT', 'REASONING'], status: 'ACTIVE', metering: { inputPer1k: 0.002, outputPer1k: 0.004 } },
  { id: 'me-003', providerId: 'mp-002', providerCode: 'local-vllm', modelId: 'qwen2.5-32b-instruct', displayName: 'Qwen2.5-32B（内网）', contextWindow: 32768, maxOutputTokens: 4096, capabilities: ['CHAT'], status: 'ACTIVE', metering: { inputPer1k: 0, outputPer1k: 0 } },
]

// ── 提示词模板 ──
const promptTemplates = [
  { code: 'satellite-system', name: '卫星助手系统提示', status: 'PUBLISHED', currentVersion: 2, updatedAt: '2026-10-18T09:00:00Z', description: '约束卫星领域回答口径与引用要求。' },
  { code: 'triage-user', name: '异常分诊用户提示', status: 'DRAFT', currentVersion: 1, updatedAt: '2026-10-15T14:00:00Z', description: '将遥测告警转成结构化分诊输入。' },
]
const templateVersions = [
  { version: 2, status: 'PUBLISHED', content: '你是卫星运行助手，回答需引用操作结果并注明时间。', publishedAt: '2026-10-18T09:00:00Z' },
  { version: 1, status: 'DEPRECATED', content: '你是卫星运行助手。', publishedAt: '2026-09-12T08:00:00Z' },
]

// ── 技能 ──
const skills = [
  { code: 'orbit_query', name: '轨道要素查询', status: 'PUBLISHED', kind: 'HTTP', description: '查询卫星当前轨道根数与星下点。', currentVersion: 2, executionCount: 1582, successRate: 0.993, averageDurationMs: 246, updatedAt: '2026-10-24T10:00:00Z' },
  { code: 'telemetry_fetch', name: '遥测片段提取', status: 'PUBLISHED', kind: 'HTTP', description: '按时间窗提取遥测参数片段。', currentVersion: 1, executionCount: 930, successRate: 0.981, averageDurationMs: 512, updatedAt: '2026-10-22T10:00:00Z' },
  { code: 'payload_compat', name: '载荷兼容检查', status: 'DISABLED', kind: 'HTTP', description: '校验载荷频段与平台约束。', currentVersion: 1, executionCount: 45, successRate: 0.955, averageDurationMs: 780, updatedAt: '2026-10-12T10:00:00Z' },
]

// ── 运行 / 调试 ──
const runs = [
  { id: 'run-101', runId: 'run-101', publicId: 'run-demo-101', appCode: 'satellite-assistant', appName: '卫星运行助手', status: 'SUCCEEDED', triggerType: 'CHAT', caller: '门户用户', startedAt: '2026-10-25T08:12:00Z', duration: 4218, errorMessage: null, nodeCount: 6, tokenUsage: { input: 3120, output: 860 } },
  { id: 'run-102', runId: 'run-102', publicId: 'run-demo-102', appCode: 'satellite-assistant', appName: '卫星运行助手', status: 'RUNNING', triggerType: 'CHAT', caller: '门户用户', startedAt: '2026-10-25T09:40:00Z', duration: 9180, errorMessage: null, nodeCount: 4, tokenUsage: { input: 1980, output: 220 } },
  { id: 'run-103', runId: 'run-103', publicId: 'run-demo-103', appCode: 'anomaly-triage', appName: '遥测异常分诊', status: 'FAILED', triggerType: 'API', caller: 'service-account', startedAt: '2026-10-25T07:05:00Z', duration: 1522, errorMessage: 'SKILL_TIMEOUT: telemetry_fetch 超过 5000ms', nodeCount: 3, tokenUsage: { input: 940, output: 0 } },
]
const debugRuns = [
  { id: 'dbg-201', status: 'PAUSED', appCode: 'payload-advisor', startedAt: '2026-10-25T09:00:00Z', controls: { paused: true }, variables: { task: '评估 Ku 频段载荷', budget: 3 } },
]

// ── 运行详情状态（RunDetail 画布与回放消费；eventsWatermark 为精确游标）──
const runNodes = (status: string, failedNode?: string) => [
  { id: 'start', nodeDefId: 'start', label: '开始', status: 'SUCCEEDED', duration: 12 },
  { id: 'retrieve', nodeDefId: 'retrieve_context', label: '检索上下文', status: failedNode === 'retrieve' ? 'FAILED' : 'SUCCEEDED', duration: 386, errorMessage: failedNode === 'retrieve' ? '技能连接失败' : undefined },
  { id: 'llm', nodeDefId: 'llm', label: '模型推理', status: failedNode === 'llm' ? 'FAILED' : 'SUCCEEDED', duration: 2140, lastRun: { summary: '输出轨道参数分析 2 段', tokens: { input: 3120, output: 860 } } },
  { id: 'orbit', nodeDefId: 'orbit_query', label: '轨道查询技能', status: failedNode === 'orbit' ? 'FAILED' : 'SUCCEEDED', duration: 246 },
  { id: 'confirm', nodeDefId: 'confirm-write', label: '写回确认', status: 'WAITING_APPROVAL', duration: 0 },
  { id: 'respond', nodeDefId: 'respond', label: '生成答复', status, duration: 210 },
]
const runStatus = (id: string) => {
  const row = byId(runs, id)
  const failedNode = row?.status === 'FAILED' ? 'orbit' : undefined
  return {
    ...row,
    status: row?.status ?? 'SUCCEEDED',
    eventsWatermark: 9,
    nodes: runNodes(row?.status ?? 'SUCCEEDED', failedNode),
    error: failedNode ? { code: 'SKILL_TIMEOUT', message: 'orbit_query 超过 5000ms' } : null,
    versionRefs: { app: 'satellite-assistant@3', skills: { orbit_query: 2 } },
  }
}
/** SSE 帧序列：id/event/data 与 parseFrames 的解析格式对应。 */
const sse = (events: { seq: string; type: string; data: unknown }[]) =>
  new Response(
    events.map((e) => `id: ${e.seq}\nevent: ${e.type}\ndata: ${JSON.stringify(e.data)}\n\n`).join(''),
    { status: 200, headers: { 'Content-Type': 'text/event-stream' } },
  )

// ── 确认中心 / 评测 ──
const approvals = [
  { id: 'ap-001', runId: 'run-102', nodeId: 'confirm-orbit-write', nodeName: '轨道参数写回', status: 'PENDING', requestedAt: '2026-10-25T09:42:00Z', reason: '该操作具有副作用，需要人工确认。', summary: '计划更新 SAT-01 轨道根数 2 项。' },
  { id: 'ap-002', runId: 'run-104', nodeId: 'confirm-dispatch', nodeName: '处置工单派发', status: 'PENDING', requestedAt: '2026-10-25T08:30:00Z', reason: '外部系统写入需审批。', summary: '派发工单 GD-20261025-07 至测控一站。' },
]
const evaluationDatasets = [
  { id: 'ed-001', name: '卫星问答回归集', version: 4, caseCount: 60, status: 'ACTIVE', updatedAt: '2026-10-20T10:00:00Z' },
  { id: 'ed-002', name: '分诊准确率集', version: 2, caseCount: 35, status: 'ACTIVE', updatedAt: '2026-10-16T10:00:00Z' },
]
const evaluations = [
  { id: 'ev-301', datasetId: 'ed-001', datasetName: '卫星问答回归集', status: 'SUCCEEDED', passed: 58, failed: 2, total: 60, passRate: 0.967, runId: 'run-101', startedAt: '2026-10-24T20:00:00Z', duration: 182000 },
  { id: 'ev-302', datasetId: 'ed-002', datasetName: '分诊准确率集', status: 'RUNNING', passed: 12, failed: 0, total: 35, passRate: 1.0, runId: null, startedAt: '2026-10-25T09:50:00Z', duration: 61000 },
]

// ── 统计 / 审计 / 分类 ──
const statisticsSnapshots = [
  { snapshotId: 'ss-20261025', bizSystemCode: '运行支持', model: 'deepseek-chat', succeeded: 128, failed: 4, cancelled: 2, active: 3, successRate: 0.955, averageDurationMs: 3860, p95DurationMs: 9120, capturedAt: now },
  { snapshotId: 'ss-20261024', bizSystemCode: '故障处置', model: 'deepseek-reasoner', succeeded: 57, failed: 9, cancelled: 1, active: 0, successRate: 0.851, averageDurationMs: 7420, p95DurationMs: 15400, capturedAt: '2026-10-24T10:00:00Z' },
]
const statisticsSummary = {
  succeeded: 185, failed: 13, cancelled: 3, active: 3, successRate: 0.921,
  averageDurationMs: 4640, p95DurationMs: 12260,
  series: [
    { date: '2026-10-23', succeeded: 52, failed: 5 },
    { date: '2026-10-24', succeeded: 57, failed: 9 },
    { date: '2026-10-25', succeeded: 76, failed: 4 },
  ],
}
const statisticsGroups = [
  { date: '2026-10-25', appCode: 'satellite-assistant', bizSystemCode: '运行支持', purpose: 'CHAT', runs: 76, successRate: 0.947, failed: 4, unfinished: 0, p95DurationMs: 9120, confirmCount: 1, model: 'deepseek-chat', tokens: { input: 231000, output: 61000 } },
  { date: '2026-10-25', appCode: 'anomaly-triage', bizSystemCode: '故障处置', purpose: 'API', runs: 38, successRate: 0.842, failed: 6, unfinished: 0, p95DurationMs: 15400, confirmCount: 0, model: 'deepseek-reasoner', tokens: { input: 98000, output: 24000 } },
  { date: '2026-10-24', appCode: 'satellite-assistant', bizSystemCode: '运行支持', purpose: 'CHAT', runs: 57, successRate: 0.965, failed: 2, unfinished: 0, p95DurationMs: 8800, confirmCount: 2, model: 'deepseek-chat', tokens: { input: 176000, output: 47000 } },
]
const statisticsSkillGroups = [
  { date: '2026-10-25', appCode: 'satellite-assistant', bizSystemCode: '运行支持', purpose: 'CHAT', skill: 'orbit_query', version: 2, calls: 96, mock: 0, failures: 1, successRate: 0.99, p95DurationMs: 1240 },
  { date: '2026-10-25', appCode: 'anomaly-triage', bizSystemCode: '故障处置', purpose: 'API', skill: 'telemetry_fetch', version: 1, calls: 41, mock: 2, failures: 4, successRate: 0.902, p95DurationMs: 5210 },
]
const classifications = [
  { id: 'cls-01', name: '运行支持', kind: 'BIZ_CATEGORY', children: [{ id: 'cls-01-1', name: '在轨管理' }] },
  { id: 'cls-02', name: '故障处置', kind: 'BIZ_CATEGORY', children: [] },
]
const auditLogs = [
  { id: 9001, actor: 'u-demo', actorName: '演示用户', action: 'APP_PUBLISH', targetType: 'APP', targetId: 'satellite-assistant', detail: '发布 v3', createdAt: '2026-10-24T15:20:00Z', result: 'SUCCESS' },
  { id: 9002, actor: 'u-demo', actorName: '演示用户', action: 'SKILL_DISABLE', targetType: 'SKILL', targetId: 'payload_compat', detail: '停用兼容检查技能', createdAt: '2026-10-23T11:00:00Z', result: 'SUCCESS' },
]

const byId = <T extends { id?: string; code?: string }>(list: T[], key: string) =>
  list.find((item) => item.id === key || item.code === key)

/** 路由表：正则捕获后按资源返回；handler 收到 (match, method)。 */
type Handler = (m: RegExpMatchArray, method: string) => Response
const routes: [RegExp, Handler][] = [
  // ── 会话 ──
  [/^\/auth\/dev-token$/, () => envelope({ accessToken: 'mock-token-demo' })],
  [/^\/auth\/session$/, () => envelope(SESSION_IDENTITY())],

  // ── 智能体应用 ──
  [/^\/admin\/apps$/, (_m, method) => (method === 'GET' ? envelope(apps) : envelope({ ...apps[0], code: 'app-new', name: '新智能体', status: 'DRAFT' }))],
  [/^\/admin\/apps\/([^/]+)\/config$/, (m) => envelope(byId(apps, m[1])?.config ?? {})],
  [/^\/admin\/apps\/([^/]+)\/integration\/credentials$/, () => envelope([])],
  [/^\/admin\/apps\/([^/]+)\/integration$/, (m) => envelope({ appCode: m[1], endpoints: [], credentials: [] })],
  [/^\/admin\/apps\/([^/]+)\/versions$/, () => envelope(appVersions)],
  [/^\/admin\/apps\/([^/]+)\/runs$/, (m) => envelope(runs.filter((r) => r.appCode === m[1]))],
  [/^\/admin\/apps\/([^/]+)\/sessions$/, () => envelope([])],
  [/^\/admin\/apps\/([^/]+)\/publish\/precheck$/, () => envelope({ passed: true, checks: [{ kind: 'CONTRACT', passed: true }, { kind: 'MODEL', passed: true }, { kind: 'SKILL', passed: true }] })],
  [/^\/admin\/apps\/([^/]+)\/publish\/commit$/, (m) => envelope({ code: m[1], status: 'PUBLISHED', currentVersionNo: 4 })],
  [/^\/admin\/apps\/([^/]+)$/, (m, method) => {
    const found = byId(apps, m[1])
    if (method === 'GET') return found ? envelope(found) : envelope404()
    return envelope(found ?? { code: m[1] })
  }],

  // ── 模型 ──
  [/^\/admin\/model-providers$/, (_m, method) => (method === 'GET' ? envelope(modelProviders) : envelope(modelProviders[0]))],
  [/^\/admin\/model-providers\/([^/]+)\/(credentials\/rotate|disable|quota|test)$/, (m) => envelope(byId(modelProviders, m[1]) ?? { id: m[1] })],
  [/^\/admin\/model-providers\/([^/]+)$/, (m, method) => {
    const found = byId(modelProviders, m[1])
    if (method === 'GET') return found ? envelope(found) : envelope404()
    return envelope(found ?? { id: m[1] })
  }],
  [/^\/admin\/model-entries$/, (_m, method) => (method === 'GET' ? envelope(modelEntries) : envelope(modelEntries[0]))],
  [/^\/admin\/model-entries\/([^/]+)\/metering$/, (m) => envelope(byId(modelEntries, m[1])?.metering ?? {})],
  [/^\/admin\/model-entries\/([^/]+)$/, (m, method) => {
    const found = byId(modelEntries, m[1])
    if (method === 'GET') return found ? envelope(found) : envelope404()
    return envelope(found ?? { id: m[1] })
  }],

  // ── 提示词模板 ──
  [/^\/admin\/prompt-templates$/, (_m, method) => (method === 'GET' ? envelope(promptTemplates) : envelope(promptTemplates[0]))],
  [/^\/admin\/prompt-templates\/([^/]+)\/versions$/, () => envelope(templateVersions)],
  [/^\/admin\/prompt-templates\/([^/]+)\/versions\/([^/]+)\/render$/, () => envelope({ rendered: '（渲染后的提示词内容示例）' })],
  [/^\/admin\/prompt-templates\/([^/]+)\/versions\/([^/]+)\/publish$/, (m) => envelope({ code: m[1], version: Number(m[2]), status: 'PUBLISHED' })],
  [/^\/admin\/prompt-templates\/([^/]+)$/, (m, method) => {
    const found = byId(promptTemplates, m[1])
    if (method === 'GET') return found ? envelope(found) : envelope404()
    return envelope(found ?? { code: m[1] })
  }],

  // ── 技能 ──
  [/^\/admin\/skills\/import-openapi\/preview$/, () => envelope({ operations: [], message: '演示模式：未解析到操作' })],
  [/^\/admin\/skills\/import-openapi$/, () => envelope({ imported: 0 })],
  [/^\/admin\/skills$/, (_m, method) => (method === 'GET' ? envelope(skills) : envelope(skills[0]))],
  [/^\/admin\/skills\/([^/]+)\/stats$/, () => envelope({ executionCount: 2557, successRate: 0.987, averageDurationMs: 380, p95DurationMs: 1240, daily: [{ date: '2026-10-24', count: 210, failures: 3 }] })],
  [/^\/admin\/skills\/([^/]+)\/referenced-by$/, () => envelope([])],
  [/^\/admin\/skills\/([^/]+)\/versions$/, () => envelope([{ version: 2, status: 'PUBLISHED', publishedAt: '2026-10-20T10:00:00Z' }, { version: 1, status: 'DEPRECATED', publishedAt: '2026-09-01T10:00:00Z' }])],
  [/^\/admin\/skills\/([^/]+)\/drafts\/([^/]+)\/(acceptance|connection-test|publish)$/, (m) => envelope({ code: m[1], version: Number(m[2]), status: 'ACCEPTED' })],
  [/^\/admin\/skills\/([^/]+)\/(disable|draft)$/, (m) => envelope(byId(skills, m[1]) ?? { code: m[1] })],
  [/^\/admin\/skills\/([^/]+)$/, (m, method) => {
    const found = byId(skills, m[1])
    if (method === 'GET') return found ? envelope(found) : envelope404()
    return envelope(found ?? { code: m[1] })
  }],

  // ── 运行与调试 ──
  [/^\/admin\/runs$/, (_m, method) => (method === 'GET' ? envelope(runs) : envelope({ id: 'run-new', status: 'QUEUED' }))],
  [/^\/admin\/debug-runs$/, (_m, method) => (method === 'GET' ? envelope(debugRuns) : envelope(debugRuns[0]))],
  [/^\/admin\/debug-runs\/([^/]+)\/events$/, () => sse(mockRunEvents())],
  [/^\/admin\/debug-runs\/([^/]+)\/(controls|variables|status|resume|cancel)$/, () => envelope(debugRuns[0])],
  [/^\/admin\/debug-runs\/([^/]+)\/node\/([^/]+)\/prompt$/, () => envelope({ label: '轨道分析', raw: '（演示模式：节点提示词内容）', status: 'SUCCEEDED', duration: 1820 })],
  [/^\/admin\/debug-runs\/([^/]+)$/, (m, method) => {
    const found = byId(debugRuns, m[1])
    if (method === 'GET') return found ? envelope(found) : envelope404()
    return envelope(found ?? { id: m[1] })
  }],
  [/^\/admin\/runs\/([^/]+)\/events$/, () => sse(mockRunEvents())],
  [/^\/admin\/runs\/([^/]+)\/(cancel|reexecute|replay|reproduce)$/, (m) => envelope(byId(runs, m[1]) ?? { id: m[1] })],
  [/^\/admin\/runs\/([^/]+)$/, (m) => {
    const found = byId(runs, m[1])
    return found ? envelope(runStatus(m[1])) : envelope404()
  }],

  // ── 确认中心 / 评测 ──
  [/^\/admin\/approvals/, () => envelope(approvals)],
  [/^\/admin\/evaluation-datasets/, () => envelope(evaluationDatasets)],
  [/^\/admin\/evaluations$/, (_m, method) => (method === 'GET' ? envelope(evaluations) : envelope({ ...evaluations[0], id: 'ev-new', status: 'QUEUED' }))],
  [/^\/admin\/evaluations\/([^/]+)\/resume$/, (m) => envelope(byId(evaluations, m[1]) ?? { id: m[1] })],
  [/^\/admin\/evaluations\/([^/]+)$/, (m, method) => {
    const found = byId(evaluations, m[1])
    if (method === 'GET') return found ? envelope(found) : envelope404()
    return envelope(found ?? { id: m[1] })
  }],

  // ── 统计 / 审计 / 分类 ──
  [/^\/admin\/statistics\/snapshots$/, () => envelope(statisticsSnapshots)],
  [/^\/admin\/statistics\/snapshots\/([^/]+)$/, () => envelope({ ...statisticsSummary, snapshotId: 'ss-20261025', page: 1, pageSize: 20, groups: statisticsGroups, groupsTotal: statisticsGroups.length, skillGroups: statisticsSkillGroups, skillGroupsTotal: statisticsSkillGroups.length })],
  [/^\/admin\/statistics\/runs/, () => envelope(runs)],
  [/^\/admin\/statistics\/rebuild$/, () => envelope({ ...statisticsSummary, snapshotId: 'ss-new', page: 1, pageSize: 20, groups: statisticsGroups, groupsTotal: statisticsGroups.length, skillGroups: statisticsSkillGroups, skillGroupsTotal: statisticsSkillGroups.length })],
  [/^\/admin\/statistics$/, () => envelope({ ...statisticsSummary, page: 1, pageSize: 20, groups: statisticsGroups, groupsTotal: statisticsGroups.length, skillGroups: statisticsSkillGroups, skillGroupsTotal: statisticsSkillGroups.length })],
  [/^\/admin\/audit$/, () => envelope(auditLogs)],
  [/^\/admin\/classifications$/, () => envelope(classifications)],
]

// ── Mock transport ──
export async function mockTransport(
  url: RequestInfo | URL,
  options?: RequestInit,
): Promise<Response> {
  const method = (options?.method ?? 'GET').toUpperCase()
  const parsed = new URL(String(url), 'http://mock.local')
  const path = parsed.pathname
  // handler 已返回封装好的 Response，直接透传；不得再包一层信封。
  for (const [pattern, handler] of routes) {
    const match = path.match(pattern)
    if (match) return handler(match, method)
  }
  if (method === 'GET') return envelope([])
  return envelope(null)
}

// ── Mock SSE 事件流（供调试预览/运行监控演示消费）──
export function mockRunEvents(): { seq: string; type: string; data: unknown }[] {
  return [
    { seq: '1', type: 'NODE_STARTED', data: { nodeId: 'start', nodeDefId: 'start' } },
    { seq: '2', type: 'NODE_FINISHED', data: { nodeId: 'start', nodeDefId: 'start' } },
    { seq: '3', type: 'NODE_STARTED', data: { nodeId: 'llm', nodeDefId: 'llm' } },
    { seq: '4', type: 'CHUNK', data: { text: '正在分析轨道参数…' } },
    { seq: '5', type: 'CHUNK', data: { text: '正在检查载荷兼容性…' } },
    { seq: '6', type: 'NODE_FINISHED', data: { nodeId: 'llm', nodeDefId: 'llm' } },
    { seq: '7', type: 'NODE_STARTED', data: { nodeId: 'skill', nodeDefId: 'skill' } },
    { seq: '8', type: 'NODE_FINISHED', data: { nodeId: 'skill', nodeDefId: 'skill' } },
    { seq: '9', type: 'RUN_FINISHED', data: { status: 'SUCCEEDED' } },
  ]
}
