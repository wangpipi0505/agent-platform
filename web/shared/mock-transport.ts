/**
 * A28/Mock：纯前端演示模式。
 * VITE_MOCK=true 时替换 transport + auth + SSE，全页面脱离后端可用。
 * 路由按 /admin 资源段匹配中文示例数据；写请求在内存里真实生效并落 sessionStorage，
 * 因此演示可以点通「登记能力包 → 生成应用 → 改配置 → 发布 → 运行 → 审批 → 评测」，
 * 刷新页面保留本次改动，关闭标签页回到初始种子。
 * 生产构建不含本模块（tree-shaking 剔除未引用路径）。
 */

import { mockStateRequest, mockExtensionState, restoreMockExtensions, mockDigest, normalizeMockResources } from './mock-state-extensions'
import { availableTools, selectedToolParameters, executeSelectedTool, normalizeDemoToolSchemas, type ToolApp, type ToolSkill } from './mock-selected-tools'
import { mockOnboardingRequest } from './mock-onboarding-extensions'
import { governanceApp, governanceSkills, governanceScenario, governanceState, restoreGovernanceState, governanceTurn, decideGovernanceRun, governanceRunNodes, governanceRunEvents, governanceBusinessRequest } from './user-governance'
import { mockRuntimeRequest } from './mock-runtime-extensions'
import { mockArtifactRequest, resetMockArtifacts } from './mock-artifacts'
import { preparePlanningAttachments, planningFileSkills } from './planning-attachments'
import { materializeResponseArtifacts } from './mock-response-artifacts'
import { validateMockSkillCalls } from './mock-skill-access'
import { planningSeed, planningAppCodes, planningOntologyConfig, planningTool, planningState, restorePlanningState, planningTurn, decidePlanningRun, cancelPlanningRun, planningRunNodes, planningRunEvents, type PlanningResponse } from './satellite-planning'

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
function envelope400(message: string) {
  return new Response(JSON.stringify({ code: 'INVALID_ARGUMENT', message, data: null }), {
    status: 400,
    headers: { 'Content-Type': 'application/json' },
  })
}
function envelope409(message: string) {
  return new Response(JSON.stringify({ code: 'CONFLICT', message, data: null }), {
    status: 409,
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
    description: '面向卫星运行团队的问答与 orbit 查询助手。', currentVersionNo: 3, draftRev: 5, draftId: 'draft-satellite-assistant-r5',
    createdAt: '2026-09-12T08:00:00Z', updatedAt: '2026-10-24T15:20:00Z',
    config: {
      modelBinding: { modelEntryId: 'me-001' },
      promptSource: 'TEMPLATE',
      promptRefs: [{ code: 'satellite-system', version: 2, sha256: 'sha256-prompt-satellite-system-v2' }],
      skillRefs: [{ code: 'orbit_query', version: 1 }, { code: 'telemetry_fetch', version: 1 }],
      guard: { roles: ['OPERATOR', 'APPROVER'] },
      requiredLabels: ['PUBLIC'],
      temperature: 0.3, maxTurns: 12,
      knowledgeBases: ['kb-orbit-runbook'],
      welcome: '我是卫星运行助手，可以查询轨道根数、提取遥测片段并解释处置口径。',
      suggestedQuestions: ['SAT-01 当前轨道高度是多少？', '帮我取 SAT-01 最近 30 分钟的遥测片段', '这条告警属于哪一类异常？'],
    },
  },
  {
    code: 'payload-advisor', name: '载荷选型顾问', displayName: '载荷选型顾问', status: 'DRAFT',
    type: 'AGENT', domain: '航天', bizCategory: '论证支持', tags: ['选型'],
    description: '按任务需求推荐兼容载荷配置。', currentVersionNo: 1, draftRev: 2, draftId: 'draft-payload-advisor-r2',
    createdAt: '2026-10-01T09:00:00Z', updatedAt: '2026-10-22T11:00:00Z',
    config: {
      modelBinding: { modelEntryId: 'me-001' },
      promptSource: 'TEMPLATE',
      promptRefs: [{ code: 'payload-advisor', version: 1, sha256: 'sha256-prompt-payload-advisor-v1' }],
      skillRefs: [{ code: 'payload_compat', version: 1 }],
      guard: { roles: ['EDITOR', 'OPERATOR'] },
      requiredLabels: ['PUBLIC'],
      temperature: 0.5, maxTurns: 8,
      knowledgeBases: ['kb-payload-catalog'],
      welcome: '描述任务轨道与载荷需求，我会给出兼容的载荷组合与频段约束。',
      suggestedQuestions: ['Ku 频段 200Mbps 下行的兼容载荷有哪些？', '比较 A/B 两套载荷方案的功耗'],
    },
  },
  {
    code: 'anomaly-triage', name: '遥测异常分诊', displayName: '遥测异常分诊', status: 'PUBLISHED',
    type: 'FLOW', domain: '测控', bizCategory: '故障处置', tags: ['遥测', '分诊'],
    description: '对遥测告警执行规则分诊并生成处置建议。', currentVersionNo: 2, draftRev: 3, draftId: 'draft-anomaly-triage-r3',
    createdAt: '2026-09-20T08:00:00Z', updatedAt: '2026-10-21T17:40:00Z',
    config: {
      modelBinding: { modelEntryId: 'me-002' },
      promptSource: 'TEMPLATE',
      promptRefs: [{ code: 'triage-user', version: 1, sha256: 'sha256-prompt-triage-user-v1' }],
      skillRefs: [{ code: 'telemetry_fetch', version: 1 }],
      guard: { roles: ['OPERATOR'] },
      requiredLabels: ['INTERNAL'],
      temperature: 0.1, maxTurns: 6,
      flowRef: 'flow-anomaly-triage',
    },
    definition: {
      definitionVersion: '1.0', trigger: 'TASK', entry: 'start',
      inputs: [
        { key: 'telemetryId', type: 'STRING', required: true, description: '遥测告警编号' },
        { key: 'window', type: 'STRING', required: false, description: '取样时间窗，默认最近 30 分钟' },
      ],
      outputs: [{ key: 'diagnosis', type: 'JSON', description: '分诊结论与处置建议' }],
      joins: [],
      executionRules: { parallel: false, maxParallel: 1, cyclesForbidden: true },
      nodes: [
        { id: 'start', type: 'START', name: '接收告警' },
        { id: 'fetch', type: 'SKILL', name: '遥测片段提取', skill: 'telemetry_fetch', next: 'classify' },
        { id: 'classify', type: 'LLM', name: '异常分类', model: 'deepseek-reasoner', promptTemplate: 'triage-user', next: 'decide' },
        { id: 'decide', type: 'CONDITION', name: '是否高风险', expression: 'diagnosis.risk == "HIGH"', nextTrue: 'confirm', nextFalse: 'report' },
        { id: 'confirm', type: 'APPROVAL', name: '人工确认处置', next: 'report' },
        { id: 'report', type: 'END', name: '输出处置建议' },
      ],
      edges: [
        { from: 'start', to: 'fetch', label: '告警' },
        { from: 'fetch', to: 'classify', label: '遥测片段' },
        { from: 'classify', to: 'decide', label: '分类结果' },
        { from: 'decide', to: 'confirm', label: 'HIGH' },
        { from: 'decide', to: 'report', label: 'LOW/MEDIUM' },
        { from: 'confirm', to: 'report', label: '已确认' },
      ],
    },
  },
  // 演示组 3：能力包 route1-visibility 生成的应用草稿（CHAT，工具全部走技能链路）。
  {
    code: 'space-visibility-agent', name: '可见性规划智能体', displayName: '可见性规划智能体', status: 'DRAFT',
    type: 'CHAT', domain: '航天', bizCategory: '运行支持', tags: ['可见性', '能力包'],
    description: '按目标与观测星计算可见窗口；异步任务提交需人工确认。', currentVersionNo: null, draftRev: 4,
    draftId: 'draft-space-visibility-agent-r4',
    createdAt: '2026-10-27T09:10:00Z', updatedAt: '2026-10-27T10:20:00Z',
    config: {
      modelBinding: { modelEntryId: 'me-001' },
      promptSource: 'TEMPLATE',
      promptRefs: [{ code: 'visibility-system', version: 1, sha256: 'sha256-prompt-visibility-v1' }],
      skillRefs: [
        { code: 'space-target-query', version: 1 },
        { code: 'space-visibility-plan', version: 1 },
        { code: 'space-visibility-submit', version: 1 },
        { code: 'space-visibility-status', version: 2 },
        { code: 'space-visibility-result', version: 1 },
      ],
      guard: { roles: ['OPERATOR', 'APPROVER'] },
      requiredLabels: ['PUBLIC'],
      temperature: 0.2, maxTurns: 8,
      systemPrompt: '你是空间目标可见性规划助手。查询或规划前先确认目标与观测星；提交异步任务前必须向用户复述参数并等待确认；引用结果时给出 taskId 与弧段数量。',
    },
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
  { id: 'me-001', providerId: 'mp-001', providerCode: 'deepseek', modelId: 'deepseek-chat', displayName: 'DeepSeek Chat', contextWindow: 65536, maxOutputTokens: 8192, capabilities: ['chat', 'tools'], status: 'ACTIVE', metering: { inputPer1k: 0.001, outputPer1k: 0.002 } },
  { id: 'me-002', providerId: 'mp-001', providerCode: 'deepseek', modelId: 'deepseek-reasoner', displayName: 'DeepSeek Reasoner', contextWindow: 65536, maxOutputTokens: 8192, capabilities: ['chat', 'json_mode'], status: 'ACTIVE', metering: { inputPer1k: 0.002, outputPer1k: 0.004 } },
  { id: 'me-003', providerId: 'mp-002', providerCode: 'local-vllm', modelId: 'qwen2.5-32b-instruct', displayName: 'Qwen2.5-32B（内网）', contextWindow: 32768, maxOutputTokens: 4096, capabilities: ['chat'], status: 'ACTIVE', metering: { inputPer1k: 0, outputPer1k: 0 } },
]

// ── 提示词模板 ──
const promptTemplates = [
  {
    code: 'satellite-system', name: '卫星助手系统提示', status: 'PUBLISHED', currentVersion: 2,
    updatedAt: '2026-10-18T09:00:00Z', description: '约束卫星领域回答口径与引用要求。',
    variables: ['satelliteId', 'windowHours', 'operatorRole'],
    content: '你是卫星运行助手，面向{{operatorRole}}回答{{satelliteId}}的运行问题。要求：先给结论再给依据；引用轨道或遥测结果时写明数据时间与来源；只回答未来 {{windowHours}} 小时内的可见窗口，超出范围要说明。',
  },
  {
    code: 'triage-user', name: '异常分诊用户提示', status: 'DRAFT', currentVersion: 1,
    updatedAt: '2026-10-15T14:00:00Z', description: '将遥测告警转成结构化分诊输入。',
    variables: ['telemetryId', 'alarmCode'],
    content: '遥测点 {{telemetryId}} 触发告警 {{alarmCode}}。请按「现象 / 可能原因 / 建议动作」三段输出，并标注需要人工确认的步骤。',
  },
  {
    code: 'visibility-system', name: '可见性规划系统提示', status: 'PUBLISHED', currentVersion: 1,
    updatedAt: '2026-10-27T09:00:00Z', description: '约束可见性规划助手的确认与引用口径。',
    variables: ['targetId', 'stationCode'],
    content: '你负责计算 {{targetId}} 与 {{stationCode}} 的可见窗口。提交异步任务前必须复述目标、测站与时间范围，等待用户确认后再调用写操作。',
  },
  {
    code: 'payload-advisor', name: '载荷选型顾问系统提示', status: 'PUBLISHED', currentVersion: 1,
    updatedAt: '2026-10-20T10:00:00Z', description: '约束载荷选型的频段、功耗与平台兼容口径。',
    variables: ['missionType', 'platformCode'],
    content: '为 {{missionType}} 任务在 {{platformCode}} 平台上推荐载荷。必须先校验频段与功耗约束，再给出两个备选并说明取舍。',
  },
]
const templateVersionSets: Record<string, JsonRow[]> = {
  'satellite-system': [
    { version: 2, status: 'PUBLISHED', content: '你是卫星运行助手，回答需引用操作结果并注明时间。', publishedAt: '2026-10-18T09:00:00Z', publishedBy: '演示用户' },
    { version: 1, status: 'DEPRECATED', content: '你是卫星运行助手。', publishedAt: '2026-09-12T08:00:00Z', publishedBy: '演示用户' },
  ],
  'triage-user': [
    { version: 1, status: 'DRAFT', content: '遥测点 {{telemetryId}} 触发告警 {{alarmCode}}。请按现象/原因/建议输出。', publishedAt: '2026-10-15T14:00:00Z', publishedBy: '演示用户' },
  ],
  'visibility-system': [
    { version: 1, status: 'PUBLISHED', content: '你负责计算 {{targetId}} 与 {{stationCode}} 的可见窗口，写操作前必须复述并等待确认。', publishedAt: '2026-10-27T09:00:00Z', publishedBy: '演示用户' },
  ],
  'payload-advisor': [
    { version: 1, status: 'PUBLISHED', content: '为 {{missionType}} 推荐载荷，先校验频段与功耗，再给两个备选。', publishedAt: '2026-10-20T10:00:00Z', publishedBy: '演示用户' },
  ],
}
const templateVersions = [
  { version: 2, status: 'PUBLISHED', content: '你是卫星运行助手，回答需引用操作结果并注明时间。', publishedAt: '2026-10-18T09:00:00Z' },
  { version: 1, status: 'DEPRECATED', content: '你是卫星运行助手。', publishedAt: '2026-09-12T08:00:00Z' },
]

// ── 技能 ──
const skills = [
  { code: 'orbit_query', name: '轨道要素查询', status: 'PUBLISHED', kind: 'HTTP', description: '查询卫星当前轨道根数与星下点。', currentVersion: 2, executionCount: 1582, successRate: 0.993, averageDurationMs: 246, updatedAt: '2026-10-24T10:00:00Z' },
  { code: 'telemetry_fetch', name: '遥测片段提取', status: 'PUBLISHED', kind: 'HTTP', description: '按时间窗提取遥测参数片段。', currentVersion: 1, executionCount: 930, successRate: 0.981, averageDurationMs: 512, updatedAt: '2026-10-22T10:00:00Z' },
  { code: 'payload_compat', name: '载荷兼容检查', status: 'DISABLED', kind: 'HTTP', description: '校验载荷频段与平台约束。', currentVersion: 1, executionCount: 45, successRate: 0.955, averageDurationMs: 780, updatedAt: '2026-10-12T10:00:00Z' },
  // 演示组 2：route1 能力包声明的 5 个真实技能（2026-10-27 路线一验证的已发布版本）。
  { code: 'space-target-query', name: '目标目录查询', status: 'PUBLISHED', kind: 'HTTP', description: '按 ID 或条件筛选空间目标目录（25870 条）。', currentVersion: 1, executionCount: 214, successRate: 1.0, averageDurationMs: 180, updatedAt: '2026-10-27T09:00:00Z' },
  { code: 'space-visibility-plan', name: '可见性规划（同步）', status: 'PUBLISHED', kind: 'HTTP', description: '计算观测星对目标的可见弧段，含光照段。', currentVersion: 1, executionCount: 96, successRate: 0.99, averageDurationMs: 2400, updatedAt: '2026-10-27T09:00:00Z' },
  { code: 'space-visibility-submit', name: '可见性任务提交（异步）', status: 'PUBLISHED', kind: 'HTTP', description: '受理异步观测规划任务并返回 taskId，平台自动对账。', currentVersion: 1, executionCount: 38, successRate: 0.97, averageDurationMs: 320, updatedAt: '2026-10-27T09:00:00Z' },
  { code: 'space-visibility-status', name: '可见性任务状态', status: 'PUBLISHED', kind: 'HTTP', description: '查询异步任务状态（SUCCEEDED/FAILED/CANCELLED）。', currentVersion: 2, executionCount: 120, successRate: 1.0, averageDurationMs: 90, updatedAt: '2026-10-27T09:00:00Z' },
  { code: 'space-visibility-result', name: '可见性任务结果', status: 'PUBLISHED', kind: 'HTTP', description: '读取异步任务的规划结果。', currentVersion: 1, executionCount: 112, successRate: 1.0, averageDurationMs: 140, updatedAt: '2026-10-27T09:00:00Z' },
  { code: 'space-satellite-options', name: '观测星目录', status: 'DISABLED', kind: 'HTTP', description: '查询 5414 颗观测星 TLE 目录；响应约 550KB 超输出预算，已停用。', currentVersion: 1, executionCount: 6, successRate: 0.83, averageDurationMs: 1800, updatedAt: '2026-10-27T09:00:00Z' },
].map((item) => ({
  readonly: true,
  sideEffect: false,
  version: item.currentVersion,
  sha256: 'c'.repeat(64),
  ...item,
}))

// ── 运行 / 调试 ──
const runs = [
  { id: 'run-101', runId: 'run-101', publicId: 'run-demo-101', appCode: 'satellite-assistant', appName: '卫星运行助手', status: 'SUCCEEDED', versionNo: 3, versionRefs: {appVersion: 3, model: 'deepseek-chat', prompt: 'satellite-system v2', skills: ['orbit_query v1','telemetry_fetch v1']}, triggerType: 'CHAT', caller: '门户用户', startedAt: '2026-10-25T08:12:00Z', duration: 4218, errorMessage: null, nodeCount: 6, tokenUsage: { input: 3120, output: 860 } },
  { id: 'run-102', runId: 'run-102', publicId: 'run-demo-102', appCode: 'satellite-assistant', appName: '卫星运行助手', status: 'RUNNING', versionNo: 3, versionRefs: {appVersion: 3, model: 'deepseek-chat', prompt: 'satellite-system v2', skills: ['orbit_query v1','telemetry_fetch v1']}, triggerType: 'CHAT', caller: '门户用户', startedAt: '2026-10-25T09:40:00Z', duration: 9180, errorMessage: null, nodeCount: 4, tokenUsage: { input: 1980, output: 220 } },
  { id: 'run-103', runId: 'run-103', publicId: 'run-demo-103', appCode: 'anomaly-triage', appName: '遥测异常分诊', status: 'FAILED', versionNo: 2, versionRefs: {appVersion: 2, model: 'deepseek-reasoner', prompt: 'triage-user v1', skills: ['telemetry_fetch v1']}, triggerType: 'API', caller: 'service-account', startedAt: '2026-10-25T07:05:00Z', duration: 1522, errorMessage: 'SKILL_TIMEOUT: telemetry_fetch 超过 5000ms', nodeCount: 3, tokenUsage: { input: 940, output: 0 } },
  // 演示组 4：能力包应用的三条运行（待确认 / 成功 / 失败可读）。
  { id: 'run-104', runId: 'run-104', publicId: 'run-vis-104', appCode: 'space-visibility-agent', appName: '可见性规划智能体', status: 'WAITING_APPROVAL', versionNo: 1, versionRefs: {appVersion: 1, model: 'deepseek-chat', prompt: 'visibility-system v1', skills: ['space-target-query v1','space-visibility-plan v1']}, triggerType: 'CHAT', caller: '门户用户', startedAt: '2026-10-27T10:02:00Z', duration: 2860, errorMessage: null, nodeCount: 6, tokenUsage: { input: 2140, output: 520 } },
  { id: 'run-105', runId: 'run-105', publicId: 'run-vis-105', appCode: 'space-visibility-agent', appName: '可见性规划智能体', status: 'SUCCEEDED', versionNo: 1, versionRefs: {appVersion: 1, model: 'deepseek-chat', prompt: 'visibility-system v1', skills: ['space-target-query v1','space-visibility-plan v1']}, triggerType: 'CHAT', caller: '门户用户', startedAt: '2026-10-27T09:41:00Z', duration: 5120, errorMessage: null, nodeCount: 7, tokenUsage: { input: 2680, output: 640 } },
  { id: 'run-106', runId: 'run-106', publicId: 'run-vis-106', appCode: 'space-visibility-agent', appName: '可见性规划智能体', status: 'FAILED', versionNo: 1, versionRefs: {appVersion: 1, model: 'deepseek-chat', prompt: 'visibility-system v1', skills: ['space-target-query v1','space-visibility-plan v1']}, triggerType: 'CHAT', caller: '门户用户', startedAt: '2026-10-27T09:28:00Z', duration: 930, errorMessage: 'TLE_CHECKSUM: 观测星两行根数校验和不匹配', nodeCount: 4, tokenUsage: { input: 1180, output: 90 } },
]
const debugRuns = [
  { id: 'dbg-201', status: 'PAUSED', appCode: 'payload-advisor', startedAt: '2026-10-25T09:00:00Z', controls: { paused: true }, variables: { task: '评估 Ku 频段载荷', budget: 3 } },
  {
    id: 'dbg-202', status: 'PAUSED', appCode: 'satellite-assistant', startedAt: '2026-10-25T09:30:00Z',
    controls: { paused: true },
    variables: { question: 'SAT-01 当前轨道高度是多少？', orbitQuery: 'SAT-01', window: '30m' },
  },
]

// ── 运行详情状态（RunDetail 画布与回放消费；eventsWatermark 为精确游标）──
const runNodes = (status: string, failedNode?: string, appCode?: string) => {
  const waiting = status === 'WAITING_APPROVAL'
  // 演示组 4：能力包应用按真实链路给节点命名（查询→规划→提交→状态→结果）。
  if (appCode === 'space-visibility-agent') {
    return [
      { id: 'start', nodeDefId: 'start', label: '开始', status: 'SUCCEEDED', duration: 10 },
      { id: 'target', nodeDefId: 'target_query', label: '目标目录查询', status: 'SUCCEEDED', duration: 180 },
      { id: 'plan', nodeDefId: 'visibility_plan', label: '可见性规划', status: failedNode === 'plan' ? 'FAILED' : 'SUCCEEDED', duration: 2400, errorMessage: failedNode === 'plan' ? 'TLE_CHECKSUM: 观测星两行根数校验和不匹配' : undefined },
      { id: 'llm', nodeDefId: 'llm', label: '模型推理', status: 'SUCCEEDED', duration: 1260, lastRun: { summary: '复述目标与窗口后请求确认', tokens: { input: 2140, output: 520 } } },
      { id: 'confirm', nodeDefId: 'confirm_submit', label: '提交异步观测规划任务', status: waiting ? 'WAITING_APPROVAL' : 'SUCCEEDED', duration: waiting ? 0 : 320 },
      { id: 'status', nodeDefId: 'visibility_status', label: '查询任务状态', status: waiting ? 'PENDING' : 'SUCCEEDED', duration: waiting ? 0 : 90 },
      { id: 'respond', nodeDefId: 'respond', label: '生成答复', status, duration: 180 },
    ]
  }
  return [
    { id: 'start', nodeDefId: 'start', label: '开始', status: 'SUCCEEDED', duration: 12 },
    { id: 'retrieve', nodeDefId: 'retrieve_context', label: '检索上下文', status: failedNode === 'retrieve' ? 'FAILED' : 'SUCCEEDED', duration: 386, errorMessage: failedNode === 'retrieve' ? '技能连接失败' : undefined },
    { id: 'llm', nodeDefId: 'llm', label: '模型推理', status: failedNode === 'llm' ? 'FAILED' : 'SUCCEEDED', duration: 2140, lastRun: { summary: '输出轨道参数分析 2 段', tokens: { input: 3120, output: 860 } } },
    { id: 'orbit', nodeDefId: 'orbit_query', label: '轨道查询技能', status: failedNode === 'orbit' ? 'FAILED' : 'SUCCEEDED', duration: 246 },
    { id: 'confirm', nodeDefId: 'confirm-write', label: '写回确认', status: waiting ? 'WAITING_APPROVAL' : 'SUCCEEDED', duration: waiting ? 0 : 210 },
    { id: 'respond', nodeDefId: 'respond', label: '生成答复', status, duration: 210 },
  ]
}
const runStatus = (id: string) => {
  const row = byId(runs, id)
  const governance = governanceState.responses[id]
  if (governance) return { ...row, planning: governance, nodes: governanceRunNodes(id), events: governanceRunEvents(id).map((event, index) => ({ seq: index + 1, type: event.type, payload: event })), eventsWatermark: governanceRunEvents(id).length, output: { text: governance.reply, workflow: governance.workflow, businessChange: governance.businessChange, artifacts: governance.artifacts, fileRefs: governance.artifacts }, error: null }
  const planning = planningState.results[id]
  if (planning) return {
    ...row, runId: id, status: row?.status ?? planning.status, planning,
    output: { text: planning.reply, plan: planning.plan, task: planning.task, artifacts: planning.artifacts, fileRefs: planning.artifacts },
    nodes: planningRunNodes(planning), events: planningRunEvents(planning).map(event => ({...event,seq:Number(event.seq)})), eventsWatermark: planningRunEvents(planning).length,
    versionRefs: (row as JsonRow | undefined)?.versionRefs, error: null,
  }
  const failedNode = row?.status === 'FAILED' ? (row.appCode === 'space-visibility-agent' ? 'plan' : 'orbit') : undefined
  return {
    ...row,
    status: row?.status ?? 'SUCCEEDED',
    eventsWatermark: 9,
    // 版本引用按运行所属应用与版本号生成，避免所有运行都显示同一个 satellite-assistant@3。
    versionRefs: (() => {
      const app = row?.appCode ? byId(apps, row.appCode) : undefined
      const version = (row as JsonRow | undefined)?.versionNo ?? app?.currentVersionNo ?? app?.draftRev ?? 1
      const skillRefs = Array.isArray((app?.config as JsonRow | undefined)?.skillRefs)
        ? ((app!.config as JsonRow).skillRefs as JsonRow[]).map((item) => [String(item.code), Number(item.version ?? 1)] as const)
        : []
      return { app: `${row?.appCode ?? 'unknown'}@${version}`, skills: Object.fromEntries(skillRefs) }
    })(),
    nodes: runNodes(row?.status ?? 'SUCCEEDED', failedNode, row?.appCode),
    error: failedNode
      ? {
          code: row?.appCode === 'space-visibility-agent' ? 'TLE_CHECKSUM' : 'SKILL_TIMEOUT',
          message: row?.errorMessage ?? 'orbit_query 超过 5000ms',
        }
      : null,
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
  { id: 'ap-001', snapshotDigest: 'sha256-demo-ap-001', runId: 'run-102', nodeDefId: 'confirm-orbit-write', nodeId: 'confirm-orbit-write', nodeName: '轨道参数写回', parameterSnapshot: { snapshotDigest: 'sha256-demo-ap-001', actionPlan: { summary: '更新 SAT-01 轨道根数 2 项', target: 'SAT-01', diff: { field: 'orbit', before: 'TLE-OLD', after: 'TLE-NEW' }, risk: 'STANDARD', approvalPolicy: 'LEGACY_DECLARATION', expiresAt: '2026-12-31T00:00:00Z' } }, status: 'PENDING', requestedAt: '2026-10-25T09:42:00Z', reason: '该操作具有副作用，需要人工确认。', summary: '计划更新 SAT-01 轨道根数 2 项。' },
  { id: 'ap-002', snapshotDigest: 'sha256-demo-ap-002', runId: 'run-104', nodeDefId: 'confirm-dispatch', nodeId: 'confirm-dispatch', nodeName: '处置工单派发', parameterSnapshot: { snapshotDigest: 'sha256-demo-ap-002', actionPlan: { summary: '派发工单 GD-20261025-07', target: '测控一站', diff: { field: 'orbit', before: 'TLE-OLD', after: 'TLE-NEW' }, risk: 'STANDARD', approvalPolicy: 'LEGACY_DECLARATION', expiresAt: '2026-12-31T00:00:00Z' } }, status: 'PENDING', requestedAt: '2026-10-25T08:30:00Z', reason: '外部系统写入需审批。', summary: '派发工单 GD-20261025-07 至测控一站。' },
  // 演示组 4：与 run-104 的 WAITING_APPROVAL 节点对应，审批通过后运行才继续。
  { id: 'ap-003', snapshotDigest: 'sha256-demo-ap-003', runId: 'run-104', nodeDefId: 'confirm-submit', nodeId: 'confirm-submit', nodeName: '提交异步观测规划任务', parameterSnapshot: { snapshotDigest: 'sha256-demo-ap-003', actionPlan: { summary: '提交异步观测规划任务', target: 'SAT-01 / SAT-A', diff: { field: 'orbit', before: 'TLE-OLD', after: 'TLE-NEW' }, risk: 'STANDARD', approvalPolicy: 'LEGACY_DECLARATION', expiresAt: '2026-12-31T00:00:00Z' } }, status: 'PENDING', requestedAt: '2026-10-27T10:02:03Z', reason: 'space-visibility-submit 具有副作用（WRITE_WITH_APPROVAL），需 ADMIN/APPROVER 确认。', summary: '目标 SAT-01、观测星 SAT-A，规划窗口 2026-07-01 08:00–12:00 UTC。' },
  { id: 'ap-004', snapshotDigest: 'sha256-demo-ap-004', runId: 'run-105', nodeDefId: 'confirm-submit', nodeId: 'confirm-submit', nodeName: '提交异步观测规划任务', parameterSnapshot: { snapshotDigest: 'sha256-demo-ap-004', actionPlan: { summary: '提交异步观测规划任务', target: 'SAT-02 / SAT-B', diff: { field: 'orbit', before: 'TLE-OLD', after: 'TLE-NEW' }, risk: 'STANDARD', approvalPolicy: 'LEGACY_DECLARATION', expiresAt: '2026-12-31T00:00:00Z' } }, status: 'APPROVED', requestedAt: '2026-10-27T09:41:02Z', decidedAt: '2026-10-27T09:41:40Z', reason: 'space-visibility-submit 具有副作用，需人工确认。', summary: '目标 SAT-02、观测星 SAT-B，规划窗口 24 小时。' },
]
const evaluationDatasets = [
  { id: 'ed-001', name: '卫星问答回归集', version: 4, caseCount: 60, status: 'ACTIVE', updatedAt: '2026-10-20T10:00:00Z' },
  { id: 'ed-002', name: '分诊准确率集', version: 2, caseCount: 35, status: 'ACTIVE', updatedAt: '2026-10-16T10:00:00Z' },
  // 演示组 5：能力包应用的可评测断言集（确定性断言，1 条故意覆盖已知缺陷）。
  { id: 'ed-003', name: '可见性规划回归集', version: 1, caseCount: 12, status: 'ACTIVE', updatedAt: '2026-10-27T09:30:00Z' },
]
const evaluations = [
  { id: 'ev-301', datasetId: 'ed-001', datasetName: '卫星问答回归集', status: 'SUCCEEDED', passed: 58, failed: 2, total: 60, passRate: 0.967, runId: 'run-101', startedAt: '2026-10-24T20:00:00Z', duration: 182000 },
  { id: 'ev-302', datasetId: 'ed-002', datasetName: '分诊准确率集', status: 'RUNNING', passed: 12, failed: 0, total: 35, passRate: 1.0, runId: null, startedAt: '2026-10-25T09:50:00Z', duration: 61000 },
  // 演示组 5：11/12 通过，失败用例指向“整份输出精确相等”断言缺陷。
  { id: 'ev-303', datasetId: 'ed-003', datasetName: '可见性规划回归集', status: 'FAILED', passed: 11, failed: 1, total: 12, passRate: 0.917, runId: 'run-105', startedAt: '2026-10-27T10:10:00Z', duration: 46000 },
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

// ── 软件能力目录（演示组 2/3：route1-visibility 已登记并完成技能绑定解析）──
const capabilityDeclaration = {
  apiVersion: 'platform/v1',
  kind: 'CapabilityPackage',
  metadata: {
    name: 'route1-visibility',
    domain: 'space-visibility',
    owner: 'algorithm-team',
    version: '1.0.0',
    description: '空间—地面目标可见窗口计算系统 space-ground-visible 的能力声明；演示数据取自 2026-10-27 路线一验证的真实技能与应用配置。',
  },
  software: {
    system: 'space-ground-visible',
    version: 'space-target-visible-1.0.0-SNAPSHOT',
    environments: { dev: 'http://127.0.0.1:19180' },
  },
  interfaces: [
    { id: 'space-http-sync', kind: 'HTTP_SYNC', endpoint: 'http://127.0.0.1:19180', timeoutMs: 30000, description: '同步只读入口：目标目录查询、同步可见性规划、任务状态与结果读取。' },
    {
      id: 'space-http-async', kind: 'HTTP_ASYNC', endpoint: 'http://127.0.0.1:19180/api/v1/space/visibility/tasks',
      httpMethod: 'POST', timeoutMs: 120000,
      reconciliation: {
        endpoint: 'http://127.0.0.1:19180/api/v1/space/visibility/tasks/{jobId}',
        method: 'GET', jobIdPath: '$.taskId', donePath: '$.status', doneValue: 'SUCCEEDED',
        failedValue: 'FAILED', failureMessagePath: '$.errorMessage', idempotencyKeyEcho: false,
      },
      description: '异步受理 + 自动对账取结果。',
    },
  ],
  ontology: {
    mode: 'NONE',
    notes: 'ontorepo 在本演示环境未接入：没有 REFERENCE/OWNED 的本体空间与发布版本，概念口径以应用 systemPrompt 注入；工具从 release operation 投影生成属后续能力。',
  },
  tools: [
    { id: 'space-target-query', operation: 'queryTargets', description: '筛选目标目录（25870 条）。', binding: 'space-http-sync', exposure: 'READ_ONLY', readonly: true, sideEffect: false, idempotent: true, skillRef: { code: 'space-target-query', version: 1, sha256: '0f2db9df779cf468f86b98425107c1c1080bfdf32076c37849c113fd32cb3762' } },
    { id: 'space-visibility-plan', operation: 'planVisibility', description: '同步计算可见弧段（含光照段）。', binding: 'space-http-sync', exposure: 'READ_ONLY', readonly: true, sideEffect: false, idempotent: true, skillRef: { code: 'space-visibility-plan', version: 1, sha256: '80889e9e0a1a180b81cbdb815465e4acdfd2b102f6925792bfc42b13652a4969' } },
    { id: 'space-visibility-submit', operation: 'submitVisibilityTask', description: '受理异步观测规划任务，返回 taskId。', binding: 'space-http-async', exposure: 'WRITE_WITH_APPROVAL', confirmRoles: ['ADMIN', 'APPROVER'], readonly: false, sideEffect: true, idempotent: true, skillRef: { code: 'space-visibility-submit', version: 1, sha256: '213b4d463a8cb189e186c1be7ac2b9de1cfbbde1432d3330d6f7c2e20c24492b' } },
    { id: 'space-visibility-status', operation: 'queryVisibilityTaskStatus', description: '查询异步任务状态。', binding: 'space-http-sync', exposure: 'READ_ONLY', readonly: true, sideEffect: false, idempotent: true, skillRef: { code: 'space-visibility-status', version: 2, sha256: '0756fa0d303c23101fbf02aa60222f6faee7b3e8dc6f24c6ba984107c814d732' } },
    { id: 'space-visibility-result', operation: 'getVisibilityTaskResult', description: '读取异步任务的规划结果。', binding: 'space-http-sync', exposure: 'READ_ONLY', readonly: true, sideEffect: false, idempotent: true, skillRef: { code: 'space-visibility-result', version: 1, sha256: 'e601a7bc5f1f3407f57dbb85353708e588e48eef1e1ae4b897f555d192ac5521' } },
  ],
  identity: {
    subjectMapping: 'platform_user -> Actor.subject()',
    scopes: ['region=LOCAL'],
    guard: { roles: ['ADMIN', 'EDITOR', 'APPROVER', 'SERVICE'], attributes: { region: ['LOCAL'] } },
    secrets: [],
  },
  agents: [
    {
      code: 'space-visibility-agent', name: '可见性规划智能体', type: 'CHAT',
      skillRefs: [
        { tool: 'space-target-query', skillCode: 'space-target-query', version: 1 },
        { tool: 'space-visibility-plan', skillCode: 'space-visibility-plan', version: 1 },
        { tool: 'space-visibility-submit', skillCode: 'space-visibility-submit', version: 1 },
        { tool: 'space-visibility-status', skillCode: 'space-visibility-status', version: 2 },
        { tool: 'space-visibility-result', skillCode: 'space-visibility-result', version: 1 },
      ],
      modelBinding: { modelEntryId: 1, entry: 'deepseek-chat（capabilities chat,tools）' },
      guard: { roles: ['ADMIN', 'EDITOR', 'APPROVER', 'SERVICE'], attributes: { region: ['LOCAL'] } },
      requiredLabels: ['PUBLIC'],
    },
  ],
  verification: {
    connection: true, contract: true, model: true, evaluation: false, safety: true, concurrency: false,
    exemptions: [
      { gate: 'evaluation', reason: '应用层评测门禁对自由文本 CHAT 智能体不可用：整份 run output 精确 JSON 相等断言含 usage/actualModel 等易变字段，正确回答仍判 REJECTED。' },
      { gate: 'concurrency', reason: '本路线未覆盖并发隔离用例。' },
    ],
  },
  operations: { notes: '演示环境未采集 SLO/成本/保留期数据；同步超时上限 30000ms，异步提交 120000ms。' },
  routeProfiles: [
    { id: 'http-sync', execution: 'space-http-sync', ontology: 'none', tools: ['space-target-query', 'space-visibility-plan', 'space-visibility-status', 'space-visibility-result'], identity: 'platform-user-guarded（roles + region=LOCAL）' },
    { id: 'http-async', execution: 'space-http-async', ontology: 'none', tools: ['space-visibility-submit'], identity: 'platform-user-guarded + approval（ADMIN/APPROVER）' },
  ],
  implementationGaps: [
    { area: '本体协同：工具投影、contextPack 自动注入、统一主体映射', description: '本包 ontology.mode=NONE，概念口径手写进 systemPrompt。', ownerCard: 'T22B' },
    { area: '执行适配器：PROCESS 适配器注册表', description: '一次性进程（stdio、超时杀树、产物回收）未注册，轨迹路线接入待办。', ownerCard: 'T22C' },
    { area: '控制面收口：共享对象 ID、SLO 与成本归因', description: 'package/route/tool/release 维度观测未落库。', ownerCard: 'T22D' },
  ],
}
const capabilityPackages = [
  {
    packageVersionId: 1, packageId: 'route1-visibility', version: '1.0.0',
    sha256: '9f2c41a7d8e35b06c1f4a9d2e7b8035c6a1d4f8e2b9c7a05d3e6f1b8c2a4d790',
    softwareId: 'space-ground-visible', softwareVersion: 'space-target-visible-1.0.0-SNAPSHOT',
    bindingStatus: 'RESOLVED', verificationStatus: 'NOT_EVALUATED', applicationCount: 1,
    createdAt: '2026-10-27T09:12:00Z', resolvedAt: '2026-10-27T09:18:00Z',
  },
]
const capabilityPackageDetail = {
  ...capabilityPackages[0],
  declaration: capabilityDeclaration,
  resolvedMapping: {
    environment: 'dev',
    routeProfileId: 'http-async',
    bindings: [
      { routeProfileId: 'http-sync', toolId: 'space-target-query', interfaceId: 'space-http-sync', skillCode: 'space-target-query', skillVersionId: 501, version: 1, sha256: '0f2db9df779cf468f86b98425107c1c1080bfdf32076c37849c113fd32cb3762' },
      { routeProfileId: 'http-sync', toolId: 'space-visibility-plan', interfaceId: 'space-http-sync', skillCode: 'space-visibility-plan', skillVersionId: 502, version: 1, sha256: '80889e9e0a1a180b81cbdb815465e4acdfd2b102f6925792bfc42b13652a4969' },
      { routeProfileId: 'http-async', toolId: 'space-visibility-submit', interfaceId: 'space-http-async', skillCode: 'space-visibility-submit', skillVersionId: 503, version: 1, sha256: '213b4d463a8cb189e186c1be7ac2b9de1cfbbde1432d3330d6f7c2e20c24492b' },
      { routeProfileId: 'http-sync', toolId: 'space-visibility-status', interfaceId: 'space-http-sync', skillCode: 'space-visibility-status', skillVersionId: 504, version: 2, sha256: '0756fa0d303c23101fbf02aa60222f6faee7b3e8dc6f24c6ba984107c814d732' },
      { routeProfileId: 'http-sync', toolId: 'space-visibility-result', interfaceId: 'space-http-sync', skillCode: 'space-visibility-result', skillVersionId: 505, version: 1, sha256: 'e601a7bc5f1f3407f57dbb85353708e588e48eef1e1ae4b897f555d192ac5521' },
    ],
  },
  applications: [
    { appId: 104, appCode: 'space-visibility-agent', name: '可见性规划智能体', type: 'CHAT', status: 'DRAFT', draftRev: 4, currentVersionNo: null },
  ],
  applicationsTruncated: false,
}

// ── 可写演示层 ──
// 目标：mock 下每个动作都真的改变数据，而不是返回一句「已保存」。
// 状态放内存 + sessionStorage：刷新保留，关闭标签页回到种子；真实后端不加载本模块。
const STORE_KEY = 'ap-demo-store-v1'
// 旧版演示按资源补齐字段，不能因种子升级清空用户配置。
const STORE_VERSION = 3
let seq = 900
const latestPrecheck: Record<string, { draftRev: number; precheckId: string; passed: boolean }> = {}
let auditSeq = 9100
let importedPackages: JsonRow[] = []
let importedDetails: Record<string, JsonRow> = {}
// 流程编排草稿：FLOW 应用保存后覆盖 fixture definition，刷新仍保留。
let flowStore: Record<string, JsonRow> = {}
// 调试页的命名输入：按草稿标识分组，保存后可在调试面板下拉复用。
let debugInputs: JsonRow[] = [
  { id: 401, appDraftId: 'draft-satellite-assistant-r5', appCode: 'satellite-assistant', name: 'SAT-01 轨道查询', revision: 1, inputs: { question: 'SAT-01 当前轨道高度是多少？', orbitQuery: 'SAT-01' }, createdAt: '2026-10-25T09:20:00Z' },
  { id: 402, appDraftId: 'draft-satellite-assistant-r5', appCode: 'satellite-assistant', name: '遥测片段提取', revision: 2, inputs: { question: '取 SAT-01 最近 30 分钟遥测', window: '30m' }, createdAt: '2026-10-25T09:25:00Z' },
  { id: 403, appDraftId: 'draft-payload-advisor-r2', appCode: 'payload-advisor', name: 'Ku 频段选型', revision: 1, inputs: { task: '评估 Ku 频段载荷', budget: 3 }, createdAt: '2026-10-24T15:00:00Z' },
]
type JsonRow = Record<string, any>

const nextId = (prefix: string) => `${prefix}-${++seq}`
const stamp = () => new Date().toISOString().replace(/\.\d{3}Z$/, 'Z')

/** 动作写进审计记录，演示时「审计记录」页能看到刚才做了什么。 */
const audit = (action: string, targetType: string, targetId: string, detail: string) => {
  auditLogs.unshift({
    id: ++auditSeq, actor: 'u-demo', actorName: '演示用户', action, targetType, targetId,
    detail, createdAt: stamp(), result: 'SUCCESS',
  })
}

const persist = () => {
  try {
    sessionStorage.setItem(STORE_KEY, JSON.stringify({
      version: STORE_VERSION, seq, auditSeq, apps, skills, runs, approvals, evaluations, evaluationDatasets,
      auditLogs, importedPackages, importedDetails, flowStore, debugInputs,
      demoServices, semanticBindings, demoReleases, demoIntegrationProfiles, demoConnections, demoBindings, planningState,
      modelProviders, modelEntries, promptTemplates, templateVersionSets, debugRuns, demoOnboardingTemplates, mockExtensionState, governanceState,
    }))
  } catch {
    // 隐私模式或配额不足时仍保留内存态，不影响演示。
  }
}

const replaceAll = <T,>(target: T[], source: unknown) => {
  if (!Array.isArray(source)) return
  target.length = 0
  target.push(...(source as T[]))
}

// 当前标签页优先；兼容早期版本放在 localStorage 的同名演示配置。
const restore = () => {
  let saved: JsonRow | null = null
  for (const storage of [() => sessionStorage, () => localStorage]) {
    try {
      const raw = storage().getItem(STORE_KEY), candidate = raw ? JSON.parse(raw) : null
      if (candidate && Number(candidate.version ?? 1) >= 1 && Number(candidate.version ?? 1) <= STORE_VERSION) { saved = candidate; break }
    } catch { /* 某个存储不可用时仍可从另一处恢复。 */ }
  }
  if (!saved) return
  replaceAll(apps, saved.apps)
  // 旧会话里保存的 app 可能没有后来新增的 definition/config 字段，用种子补齐而不是整份丢弃。
  for (const seed of appSeeds) {
    const live = apps.find((item) => item.code === seed.code)
    if (!live) { apps.push(JSON.parse(JSON.stringify(seed))); continue }
    if (!live.definition && seed.definition) live.definition = seed.definition
    live.config = { ...(seed.config ?? {}), ...(live.config ?? {}) }
  }
  replaceAll(skills, saved.skills)
  for (const skill of governanceSkills) if (!skills.some(item => item.code === skill.code)) skills.push(skill as unknown as (typeof skills)[number])
  for (const tool of planningSeed.tools) if (!skills.some(item => item.code === tool.code)) skills.push(planningSkillRow(tool.code) as (typeof skills)[number])
  for(const tool of planningFileSkills)if(!skills.some(item=>item.code===tool.code))skills.push(JSON.parse(JSON.stringify(tool)) as (typeof skills)[number])
  restorePlanningState(saved.planningState)
  restoreMockExtensions(saved.mockExtensionState)
  if(Number(mockExtensionState.demoSchemaVersion??0)<2){const approvalSkill=skills.find(skill=>skill.code==='um-approval-decide') as JsonRow|undefined;if(approvalSkill?.accessPolicy&&JSON.stringify(approvalSkill.accessPolicy.invokeRoles)==='["UM_OPERATOR"]')approvalSkill.accessPolicy.invokeRoles=['UM_APPROVER','UM_SECURITY'];mockExtensionState.demoSchemaVersion=2}
  const requiredPlanningRefs=[...planningSeed.tools,...planningFileSkills].map(tool=>({code:tool.code,version:tool.version}))
  for(const app of apps.filter(item=>planningAppCodes.has(item.code))){const refs=(app.config.skillRefs??=[]) as JsonRow[];for(const ref of requiredPlanningRefs)if(!refs.some(item=>item.code===ref.code))refs.push(ref)}
  for(const code of planningAppCodes)for(const version of mockExtensionState.versions[code]??[]){if(!String(version.note??'').includes('演示'))continue;const refs=version.snapshot?.config?.skillRefs;if(Array.isArray(refs))for(const ref of requiredPlanningRefs)if(!refs.some((item:JsonRow)=>item.code===ref.code))refs.push({...ref})}
  restoreGovernanceState(saved.governanceState)
  replaceAll(modelProviders, saved.modelProviders)
  replaceAll(modelEntries, saved.modelEntries)
  replaceAll(promptTemplates, saved.promptTemplates)
  replaceAll(debugRuns, saved.debugRuns)
  replaceAll(demoOnboardingTemplates, saved.demoOnboardingTemplates)
  if (saved.templateVersionSets) Object.assign(templateVersionSets, saved.templateVersionSets)
  replaceAll(runs, saved.runs)
  replaceAll(approvals, saved.approvals)
  replaceAll(evaluations, saved.evaluations)
  replaceAll(evaluationDatasets, saved.evaluationDatasets)
  replaceAll(auditLogs, saved.auditLogs)
  if (Array.isArray(saved.importedPackages)) importedPackages = saved.importedPackages as JsonRow[]
  if (saved.importedDetails && typeof saved.importedDetails === 'object') importedDetails = saved.importedDetails as Record<string, JsonRow>
  if (saved.flowStore && typeof saved.flowStore === 'object') flowStore = saved.flowStore as Record<string, JsonRow>
  replaceAll(debugInputs, saved.debugInputs)
  replaceAll(demoServices, saved.demoServices)
  replaceAll(semanticBindings, saved.semanticBindings)
  replaceAll(demoReleases, saved.demoReleases)
  replaceAll(demoIntegrationProfiles, saved.demoIntegrationProfiles)
  replaceAll(demoConnections, saved.demoConnections)
  replaceAll(demoBindings, saved.demoBindings)
  if (typeof saved.seq === 'number') seq = saved.seq
  if (typeof saved.auditSeq === 'number') auditSeq = saved.auditSeq
}
const appSeeds = apps.map((item) => JSON.parse(JSON.stringify(item)))
let restoredOnce = false
const ensureRestored = () => {
  if (restoredOnce) return
  restoredOnce = true
  restore()
}

const datasetVersionOf = (d: JsonRow) => d.datasetVersion ?? `${d.name} · v${d.version ?? 1}`

// 路由参数永远是字符串，fixture 里的 id 可能是数字（调试命名输入 401/402），统一按字符串比较。
const byId = <T extends { id?: string | number; code?: string }>(list: T[], key: string) =>
  list.find((item) => String(item.id ?? '') === key || item.code === key)

/** 从 OpenAPI 3 JSON 文档抽取操作；抽不到时回退到「METHOD /path」文本格式。 */
const parseOpenApiOperations = (document: string): JsonRow[] => {
  const operations: JsonRow[] = []
  try {
    const doc = JSON.parse(document) as JsonRow
    for (const [path, item] of Object.entries((doc.paths ?? {}) as JsonRow)) {
      for (const [method, operation] of Object.entries((item ?? {}) as JsonRow)) {
        if (!['get', 'post', 'put', 'delete', 'patch'].includes(method.toLowerCase())) continue
        if (operations.length >= 50) break
        const op = (operation ?? {}) as JsonRow
        operations.push({
          operationId: String(op.operationId ?? `${method}-${path.replace(/[^\w]+/g, '-')}`),
          method: method.toUpperCase(),
          path,
          summary: String(op.summary ?? ''),
          supported: true,
          diagnostics: [],
          requiresAuth: false,
          inputSchema: op.requestBody?.content?.['application/json']?.schema ?? { type: 'object', properties: {} },
          outputSchema: op.responses?.['200']?.content?.['application/json']?.schema ?? { type: 'object' },
        })
      }
    }
  } catch {
    // 非 JSON 文档走文本回退
  }
  if (!operations.length) {
    const pattern = /^\s*(get|post|put|delete|patch)\s+(\S+)\s*$/gim
    let hit: RegExpExecArray | null
    while ((hit = pattern.exec(document)) && operations.length < 50) {
      operations.push({
        operationId: hit[2].replace(/[^\w]+/g, '-').replace(/^-|-$/g, ''),
        method: hit[1].toUpperCase(), path: hit[2], supported: true, diagnostics: [],
        inputSchema: { type: 'object', properties: {} }, outputSchema: { type: 'object' },
      })
    }
  }
  return operations
}

/** 路由表：正则捕获后按资源返回；handler 收到 (match, method, body)。 */
// ── 演示数据：服务管理 / 技能统计 / 语义与目标 / 配置发布 ──
// 这些数据只服务本机演示，不写入后端；写操作仍由内存状态承接。
const demoServices = [
  {
    code: 'algorithm-worker', name: '轨道可见性算法服务',
    command: ['java', '-jar', 'demo/algorithm-worker.jar', '--server.port=19180'],
    workDir: 'D:/demo/algorithm-worker', port: 19180, healthPath: '/health',
    startupTimeoutMs: 30000, stopTimeoutMs: 5000, enabled: true, state: 'RUNNING',
    healthUrl: 'http://127.0.0.1:19180/health', stale: false, lastError: null,
    updatedAt: '2026-10-25T09:40:00Z',
  },
  {
    code: 'telemetry-gateway', name: '遥测接入网关',
    command: ['node', 'gateway.js', '--port=38271'],
    workDir: 'D:/demo/telemetry-gateway', port: 38271, healthPath: '/health',
    startupTimeoutMs: 20000, stopTimeoutMs: 5000, enabled: true, state: 'STOPPED',
    healthUrl: 'http://127.0.0.1:38271/health', stale: false, lastError: '上次停止由人工触发',
    updatedAt: '2026-10-24T18:20:00Z',
  },
]

const semanticReleaseRef = {
  spaceCode: 'sp-sat',
  releaseId: 'rel-003',
  version: 'v3',
  contentDigest: 'a'.repeat(64),
}

const semanticCatalog = {
  complete: true,
  errors: [] as string[],
  releases: [
    {
      releaseRef: semanticReleaseRef,
      semanticExtensions: [
        {
          id: 'ext-visibility-1',
          extensionId: 'ext-visibility-1',
          extensionSha256: 'd'.repeat(64),
          name: '可见性只读投影',
          opCode: 'orbit-query',
          profile: 'read-only@1.0',
          schemaVersion: '1.0',
          status: 'ACTIVE',
          descriptor: {
            objectType: 'Satellite',
            factFields: [
              { field: 'noradId', kind: 'attribute' },
              { field: 'inclinationDeg', kind: 'attribute' },
              { field: 'operator', kind: 'attribute' },
            ],
            ruleRefs: ['rule-orbit-window'],
          },
        },
      ],
      operations: [
        { opCode: 'orbit-query', summary: '轨迹查询', status: 'ACTIVE' },
        { opCode: 'health-score', summary: '健康评估', status: 'ACTIVE' },
      ],
      projection: {
        definitions: {
          actions: [
            { ref: { id: 'op-orbit-query', name: '轨迹查询' }, code: 'orbit-query' },
            { ref: { id: 'op-health-score', name: '健康评估' }, code: 'health-score' },
          ],
          objects: [
            { ref: { id: 'cls-satellite', name: 'Satellite' } },
            { ref: { id: 'cls-orbit', name: 'Orbit' } },
            { ref: { id: 'cls-ground-station', name: 'GroundStation' } },
          ],
          rules: [{ ref: { id: 'rule-orbit-window', name: '可见窗口约束' } }],
        },
      },
    },
  ],
}

const semanticBindings = [
  {
    bindingId: 'sb-001', appCode: 'satellite-assistant', status: 'ACTIVE', currentRev: 1,
    releaseRef: semanticReleaseRef, objectType: 'Satellite', opCode: 'orbit-query',
    skillCode: 'orbit_query', skillVersion: 2,
    factFields: [{ field: 'noradId', kind: 'attribute' }, { field: 'inclinationDeg', kind: 'attribute' }],
    ruleRefs: ['rule-orbit-window'],
    revisions: [
      {
        bindingRev: 1, releaseRef: semanticReleaseRef, extensionId: 'ext-visibility-1',
        extensionDigest: 'sha256:' + 'b'.repeat(64), objectType: 'Satellite', opCode: 'orbit-query',
        skill: { skillCode: 'orbit_query', skillVersion: 2 },
        factFields: [{ field: 'noradId', kind: 'attribute' }],
        createdAt: '2026-10-24T16:10:00Z',
      },
    ],
    createdAt: '2026-10-24T16:10:00Z',
  },
]

const demoReleases = [
  {
    releaseId: 'rel-003', appCode: 'satellite-assistant', appVersion: 3,
    candidateSha256: 'sha256-demo-candidate-3', experienceRev: 1,
    integrationProfileId: 'ip-sat-1', integrationProfileRev: 1,
    connectionProfileRefs: [{ profileId: 'cp-sat', profileRev: 1 }],
    activationScope: 'production', status: 'ACTIVE', digest: 'sha256-demo-release-3',
    createdBy: '演示用户', createdAt: '2026-10-25T09:30:00Z', updatedAt: '2026-10-25T09:30:00Z',
  },
  {
    releaseId: 'rel-002', appCode: 'satellite-assistant', appVersion: 2,
    candidateSha256: 'sha256-demo-candidate-2', experienceRev: 1,
    integrationProfileId: 'ip-sat-1', integrationProfileRev: 1,
    connectionProfileRefs: [{ profileId: 'cp-sat', profileRev: 1 }],
    activationScope: 'production', status: 'SUPERSEDED', digest: 'sha256-demo-release-2',
    createdBy: '演示用户', createdAt: '2026-10-20T11:00:00Z', updatedAt: '2026-10-25T09:30:00Z',
  },
]

const demoIntegrationProfiles = [
  {
    profileId: 'ip-sat-1', name: '卫星运行助手接入档案', status: 'ACTIVE', revision: 1,
    profileVersion: 1, environment: 'production', notes: 'HTTP 同步 + 审批回写',
    createdAt: '2026-10-20T10:00:00Z', updatedAt: '2026-10-25T09:30:00Z',
  },
]

const demoConnections = [
  {
    profileId: 'cp-sat', name: '算法服务连接', status: 'ACTIVE', revision: 1,
    profileVersion: 1, baseUrl: 'http://127.0.0.1:19180', authType: 'NONE',
    createdAt: '2026-10-20T10:00:00Z', updatedAt: '2026-10-24T12:00:00Z',
  },
]

const demoBindings = [
  {
    bindingId: 'eb-1', environment: 'production', appCode: 'satellite-assistant',
    integrationProfileId: 'ip-sat-1', connectionProfileId: 'cp-sat', status: 'ACTIVE',
    createdAt: '2026-10-25T09:30:00Z', updatedAt: '2026-10-25T09:30:00Z',
  },
]

function demoReply(appCode: string, message: string): string {
  const text = message.trim()
  if (!text) return '请描述你要查询的卫星或观测目标。'
  if (appCode === 'payload-advisor') {
    return `按当前载荷目录，${text} 建议优先 Ku 频段方案：兼容性检查通过，剩余带宽满足任务需求；如需更高分辨率可切换 Ka 频段。`
  }
  if (appCode === 'space-visibility-agent') {
    return `已按目标「${text}」计算可见窗口：最近一次过境约 12 分钟后开始，持续 8 分 40 秒；建议提前 2 分钟下发观测指令。`
  }
  return `已收到「${text}」。以 SAT-01 为例：轨道高度 550 km，倾角 97.6°，最近可见窗口 2026-10-26 09:12 UTC，持续 8 分 40 秒，仰角峰值 62°。`
}

function skillStatsPayload(code: string) {
  const skill = byId(skills, code)
  if (!skill) return envelope404()
  const calls = Number((skill as JsonRow).executionCount ?? 120)
  const formal = Math.max(1, Math.round(calls * 0.82))
  const debug = Math.max(1, Math.round(calls * 0.12))
  const evaluation = Math.max(1, calls - formal - debug)
  return envelope({
    from: '2026-10-07',
    to: '2026-11-06',
    snapshotAt: stamp(),
    consistency: '单快照一致读取',
    total: { calls, formal, debug, evaluation },
    mock: { calls: 12 },
    byMode: [
      { callKind: '正式', calls: formal, mockCalls: 0, successRate: 0.99, averageDurationMs: 380, p95DurationMs: 1240 },
      { callKind: '调试', calls: debug, mockCalls: 8, successRate: 0.97, averageDurationMs: 180, p95DurationMs: 320 },
      { callKind: '评测', calls: evaluation, mockCalls: 4, successRate: 0.95, averageDurationMs: 260, p95DurationMs: 520 },
    ],
    byDay: [
      { date: '2026-10-24', realFormalCalls: Math.round(formal * 0.5), successRate: 0.99, p95: 1180 },
      { date: '2026-10-25', realFormalCalls: Math.round(formal * 0.5), successRate: 0.98, p95: 1260 },
    ],
    failures: [],
  })
}

type Handler = (m: RegExpMatchArray, method: string, body: JsonRow | undefined) => Response | Promise<Response>
// 接入模板：目录与「可用模板」都要求 kind=QUERY，可用目录只列 PUBLISHED。
const demoOnboardingTemplates = [
  { code: 'satellite-visibility-onboarding', name: '卫星可见性接入模板', kind: 'QUERY', status: 'PUBLISHED', currentRev: 2, updatedAt: '2026-10-26T09:00:00Z', description: '把可见性查询能力接入业务软件的标准问法与判据。', connections: [], experiences: [] },
  { code: 'telemetry-triage-onboarding', name: '遥测异常接入模板', kind: 'QUERY', status: 'DRAFT', currentRev: 1, updatedAt: '2026-10-27T10:00:00Z', description: '遥测异常分诊的输入字段与输出判据草稿。', connections: [], experiences: [] },
]

function planningSkillRow(code: string): JsonRow {
  const tool = planningTool(code)!
  return { ...tool, kind: 'HTTP', type: 'HTTP', status: 'PUBLISHED', currentVersion: 1, executionCount: 0, successRate: 1, averageDurationMs: 120, updatedAt: planningSeed.clock, execution: { readonly: tool.readonly, sideEffect: tool.sideEffect, idempotent: true, cancellable: code === 'planning-task-submit', timeoutMs: 30000 }, guard: { roles: tool.sideEffect ? ['ADMIN', 'APPROVER'] : ['ADMIN', 'EDITOR', 'OPERATOR'] } }
}
const planningApplication = {
  code: 'satellite-planning-assistant', name: '卫星规划助手', displayName: '卫星规划助手', type: 'CHAT', status: 'PUBLISHED',
  domain: '航天', bizCategory: '观测任务规划', tags: ['对空', '对地', '下传', '本体'], description: '从卫星与目标查询，到载荷约束、观测下传规划、人工确认、任务与结果的完整示例。',
  currentVersionNo: 1, draftRev: 1, draftId: 'draft-satellite-planning-assistant-r1', createdAt: planningSeed.clock, updatedAt: planningSeed.clock,
  config: { modelBinding: { modelEntryId: 'me-001' }, promptSource: 'TEMPLATE', promptRefs: [{ code: 'planning-system', version: 1 }], systemPrompt: planningSeed.systemPrompt,
    skillRefs: [...planningSeed.tools, ...planningFileSkills].map(tool => ({ code: tool.code, version: tool.version })), ontology: planningOntologyConfig,
    guard: { roles: ['ADMIN', 'EDITOR', 'OPERATOR', 'APPROVER'] }, requiredLabels: ['PUBLIC'], temperature: 0.1, maxTurns: 20, planningScenarioVersion: 1 },
}
apps.unshift(planningApplication as unknown as (typeof apps)[number])
appSeeds.unshift(JSON.parse(JSON.stringify(planningApplication)))
skills.unshift(...planningSeed.tools.map(tool => planningSkillRow(tool.code) as (typeof skills)[number]))
skills.unshift(...planningFileSkills as unknown as (typeof skills))
for(const app of [...apps,...appSeeds].filter(item=>planningAppCodes.has(item.code))){const refs=(app.config.skillRefs??=[]) as JsonRow[];for(const tool of [...planningSeed.tools,...planningFileSkills])if(!refs.some(ref=>ref.code===tool.code))refs.push({code:tool.code,version:tool.version})}
promptTemplates.unshift({ code: 'planning-system', name: '卫星规划助手系统提示词', description: '覆盖目标解析、18个规划工具、本体规则、下传、确认与结果表达。', status: 'PUBLISHED', currentVersion: 1, variables: [], content: planningSeed.systemPrompt, updatedAt: planningSeed.clock })
templateVersionSets['planning-system'] = [{ version: 1, status: 'PUBLISHED', content: planningSeed.systemPrompt, publishedAt: planningSeed.clock, publishedBy: '演示管理员' }]
debugInputs.unshift(...planningSeed.examples.map((example, index) => ({ id: 5000 + index, appDraftId: planningApplication.draftId, appCode: planningApplication.code, name: example.title, revision: 1, inputs: { message: example.message }, createdAt: planningSeed.clock })))
semanticCatalog.releases.unshift({
  releaseRef: planningSeed.releaseRef, name: planningSeed.ontology.space.name, spaceId: planningSeed.ontology.space.id,
  semanticExtensions: [{ id: 'ext-observation-v1', extensionId: 'ext-observation-v1', extensionSha256: planningSeed.releaseRef.contentDigest, name: '观测规划事实与规则', opCode: 'observation-catalog', profile: 'read-only@1.0', schemaVersion: '1.0', status: 'ACTIVE', descriptor: { objectType: 'Satellite', factFields: [{ field: 'satelliteId', kind: 'attribute' }], ruleRefs: planningSeed.ontology.rules.map(rule => rule.id) } }],
  operations: planningSeed.ontology.operations.map(operation => ({ opCode: operation.code, summary: operation.name, status: 'ACTIVE' })),
  projection: { definitions: { objects: planningSeed.ontology.classes.map(item => ({ ref: { id: item.id, name: item.name }, description: item.displayName })), actions: planningSeed.ontology.operations.map(operation => ({ ref: { id: operation.code, name: operation.name }, code: operation.code })), rules: planningSeed.ontology.rules.map(rule => ({ ref: { id: rule.id, name: rule.name } })) } },
} as unknown as (typeof semanticCatalog.releases)[number])

/** 规划对话、详情、审批和事件共用一条事实。 */
apps.unshift(governanceApp as unknown as (typeof apps)[number])
appSeeds.unshift(JSON.parse(JSON.stringify(governanceApp)))
skills.unshift(...governanceSkills as unknown as (typeof skills))
const businessActor=(id=governanceState.actorId)=>governanceState.actors.find(actor=>actor.id===id)
function skillAccessErrors(calls:JsonRow[],refs:JsonRow[],actorId=governanceState.actorId){return validateMockSkillCalls({calls,skills,skillVersions:mockExtensionState.skillVersions,skillRefs:refs,actor:businessActor(actorId)})}
function denyPlanningCalls(result:PlanningResponse,errors:string[]){result.status='BLOCKED';result.reply='### 技能调用已阻断\n\n'+errors.map(message=>'- '+message).join('\n');result.toolCalls=result.toolCalls.map(call=>({...call,status:'BLOCKED',output:{reason:'调用权限或版本校验未通过'}}));result.artifacts=[];delete result.plan;delete result.task;delete result.approval;result.ontology=null;return result}
semanticCatalog.releases.unshift({releaseRef:governanceApp.config.ontology,name:'用户与权限治理本体',spaceId:'sp-user-governance',semanticExtensions:[{id:'ext-user-governance-v1',extensionId:'ext-user-governance-v1',extensionSha256:governanceApp.config.ontology.contentDigest,name:'业务账号与权限约束',opCode:'user-governance-facts',profile:'read-only@1.0',schemaVersion:'1.0',status:'ACTIVE',descriptor:{objectType:'Account'}}],operations:[{opCode:'user-governance-facts',summary:'用户与权限事实查询',status:'ACTIVE'},{opCode:'user-governance-check',summary:'账号变更规则判定',status:'ACTIVE'}],projection:{definitions:{objects:['Account','Department','Role','Permission','ManagementRequest','ApprovalRecord'].map(name=>({ref:{id:'um-'+name,name},description:name})),actions:[],rules:[]}}} as unknown as (typeof semanticCatalog.releases)[number])
{const version=JSON.parse(JSON.stringify(semanticCatalog.releases[0]));version.releaseRef={...governanceApp.config.ontology,releaseId:'rel-user-governance-v2',version:'v2',contentDigest:mockDigest({...governanceApp.config.ontology,versionNo:2})};version.semanticExtensions.forEach((extension:JsonRow)=>{extension.id='ext-user-governance-v2';extension.extensionId=extension.id;extension.extensionSha256=version.releaseRef.contentDigest});semanticCatalog.releases.splice(1,0,version)}
function saveGovernanceRun(result: any) {
  const previous = byId(runs, result.runId) as JsonRow | undefined
  const data = { id: result.runId, runId: result.runId, publicId: result.runId, appCode: governanceApp.code, appName: governanceApp.name,
    status: ['WAITING_APPROVAL', 'SUCCEEDED', 'REJECTED', 'BLOCKED'].includes(result.status) ? result.status === 'BLOCKED' ? 'FAILED' : result.status : 'SUCCEEDED', businessStatus: result.status, planning: result,
    startedAt: previous?.startedAt ?? stamp(), triggerType: 'CHAT', caller: previous?.caller ?? governanceState.actorId, duration: result.toolCalls.length * 120,
    errorMessage: result.status === 'BLOCKED' ? result.reply : null, nodeCount: governanceRunNodes(result.runId).length,
    tokenUsage: { input: 600, output: 280 }, versionNo: previous?.versionNo ?? governanceApp.currentVersionNo,
    output: { text: result.reply, workflow: result.workflow, businessChange: result.businessChange, artifacts: result.artifacts, fileRefs: result.artifacts },
    versionRefs: previous?.versionRefs ?? { app: `${governanceApp.code}@${governanceApp.currentVersionNo}`, skills: Object.fromEntries(result.toolCalls.map((tool: any) => [tool.skillCode, 1])) } }
  if (previous) Object.assign(previous, data); else runs.unshift(data as unknown as (typeof runs)[number])
  if (result.approval) {
    const currentNodeId = result.approval.nodeId
    for (const row of approvals.filter(item => item.runId === result.runId && item.nodeId !== currentNodeId && item.status === 'PENDING')) Object.assign(row, { status: 'APPROVED', decidedAt: stamp() })
    const found = approvals.find(item => item.runId === result.runId && item.nodeId === currentNodeId)
    if (found) Object.assign(found, { status: result.approval.status, summary: result.approval.summary })
    else approvals.unshift({ id: `approval-${result.runId}-${currentNodeId}`, runId: result.runId, nodeId: currentNodeId, nodeDefId: currentNodeId, nodeInstanceId: currentNodeId, nodeName: '账号授权业务审批', status: result.approval.status, requestedAt: stamp(), summary: result.approval.summary, reason: '业务账号变更需要独立审批', parameterSnapshot: result.businessChange ?? {} } as unknown as (typeof approvals)[number])
  }
}
function savePlanningRun(appCode: string, result: PlanningResponse) {
  const app = byId(apps, appCode)
  const existing = byId(runs, result.runId) as JsonRow | undefined
  const status = ['WAITING_APPROVAL', 'REJECTED','CANCELLED'].includes(result.status) ? result.status : result.status==='BLOCKED'?'FAILED':'SUCCEEDED'
  const data = {
    id: result.runId, runId: result.runId, publicId: result.runId, appCode, appName: app?.name ?? appCode, status, businessStatus: result.status,
    triggerType: 'CHAT', caller: 'u-demo', startedAt: existing?.startedAt ?? stamp(), duration: 860 + result.toolCalls.length * 120,
    errorMessage: null, nodeCount: planningRunNodes(result).length, tokenUsage: { input: 1240, output: 420 },
    versionNo: existing?.versionNo ?? app?.currentVersionNo ?? 1, versionRefs: existing?.versionRefs ?? { app: `${appCode}@${app?.currentVersionNo ?? 1}`, model: 'deepseek-chat', prompt: 'planning-system@1', skills: Object.fromEntries(result.toolCalls.map(tool => [tool.skillCode, 1])), ontologyRelease: result.ontology?.releaseRef.releaseId ?? null },
    output: { text: result.reply, plan: result.plan, task: result.task, artifacts: result.artifacts },
  }
  if (existing) Object.assign(existing, data)
  else runs.unshift(data as unknown as (typeof runs)[number])
  if (result.approval && !approvals.some(item => item.runId === result.runId && item.nodeId === result.approval!.nodeId)) approvals.unshift({
    id: `approval-${result.runId}`, runId: result.runId, nodeId: result.approval.nodeId, nodeDefId: result.approval.nodeId, nodeName: '卫星规划人工确认', status: 'PENDING',
    snapshotDigest: planningSeed.releaseRef.contentDigest, requestedAt: stamp(), reason: '任务提交具有副作用，须人工确认冻结计划。', summary: result.approval.summary,
    parameterSnapshot: { plan: result.plan, actionPlan: { summary: result.approval.summary, target: result.plan?.targetName, diff: { selectedWindows: result.plan?.windows.map(window => window.id) }, risk: 'STANDARD', approvalPolicy: 'MOCK_PLAN_CONFIRMATION', expiresAt: '2026-12-31T00:00:00Z' } },
  } as unknown as (typeof approvals)[number])
}

const routes: [RegExp, Handler][] = [
  // 正式运行输入契约：与业务软件集成调用同一份版本声明，FLOW 应用由 definition.inputs 派生。
  // 语义回执：无只读验证操作时返回空集合（合法），避免运行详情把 404 显示成「记录不存在」。
  [/^\/v1\/runs\/([^/]+)\/semantic-results$/, (m) => envelope({ runId: m[1], results: [], rejected: [], planning: planningState.results[m[1]]?.ontology ?? null })],
  [/^\/admin\/apps\/([^/]+)\/planning-scenarios$/, (m) => planningAppCodes.has(m[1]) ? envelope({ clock: planningSeed.clock, planningDate: planningSeed.planningDate, examples: planningSeed.examples, tools: [...planningSeed.tools,...planningFileSkills], ontology: planningSeed.releaseRef }) : envelope404()],
  [/^\/v1\/apps\/([^/]+)\/input-schema$/, (m) => {
    const app = byId(apps, m[1])
    if (!app) return envelope404()
    const inputs = ((app as JsonRow).definition?.inputs ?? []) as JsonRow[]
    const properties: JsonRow = {}
    for (const input of inputs) {
      properties[String(input.key)] = {
        type: input.type === 'NUMBER' ? 'number' : input.type === 'BOOLEAN' ? 'boolean' : 'string',
        description: input.description ?? '',
      }
    }
    return envelope({
      versionNo: app.currentVersionNo,
      inputSchema: {
        type: 'object',
        required: inputs.filter((item) => item.required).map((item) => item.key),
        properties,
      },
    })
  }],
  // ── 会话 ──
  [/^\/auth\/dev-token$/, () => envelope({ accessToken: 'mock-token-demo', expiresIn: 86400, user: { tenantId: 't-demo', principalId: 'u-demo', roles: ['ADMIN', 'EDITOR', 'APPROVER'] } })],
  [/^\/auth\/session$/, () => envelope(SESSION_IDENTITY())],

  // ── 智能体应用 ──
  [/^\/admin\/apps$/, (_m, method, body) => {
    if (method === 'GET') return envelope(apps)
    const created = {
      code: body?.code ?? nextId('app'), name: body?.name ?? '新智能体', displayName: body?.name ?? '新智能体',
      status: 'DRAFT', type: body?.type ?? 'AGENT', domain: body?.domain ?? '航天',
      bizCategory: body?.bizCategory ?? '', tags: body?.tags ?? [], description: body?.description ?? '',
      currentVersionNo: null, draftRev: 1, createdAt: stamp(), updatedAt: stamp(),
      draftId: `draft-${body?.code ?? 'new'}-r1`,
      config: { model: 'deepseek-chat', temperature: 0.3, maxTurns: 8, ...(body?.config ?? {}) },
    }
    apps.unshift(created as (typeof apps)[number])
    audit('APP_CREATE', 'APP', created.code, `创建智能体「${created.name}」`)
    persist()
    return envelope(created)
  }],
  [/^\/admin\/apps\/([^/]+)\/copy$/, (m, _method, body) => {
    const source = byId(apps, m[1])
    if (!source) return envelope404()
    const copy = {
      ...source, code: body?.code ?? `${source.code}-copy`, name: body?.name ?? `${source.name}（副本）`,
      displayName: body?.name ?? `${source.name}（副本）`, status: 'DRAFT', currentVersionNo: null,
      draftRev: 1, createdAt: stamp(), updatedAt: stamp(), config: { ...(source.config ?? {}) },
      draftId: `draft-${body?.code ?? `${source.code}-copy`}-r1`,
    }
    apps.unshift(copy as (typeof apps)[number])
    audit('APP_COPY', 'APP', copy.code, `从 ${source.code} 复制`)
    persist()
    return envelope(copy)
  }],
  [/^\/admin\/apps\/([^/]+)\/config$/, (m, method, body) => {
    const found = byId(apps, m[1])
    if (!found) return envelope404()
    if (method !== 'GET' && body?.draftRev !== found.draftRev) return envelope409('草稿修订已变化，请刷新')
    if (method !== 'GET') {
      // 前端约定：body 为 { draftRev, config }，响应必须是递增 draftRev 的应用详情。
      const nextConfig = (body?.config ?? body ?? {}) as JsonRow
      found.config = { ...(found.config ?? {}), ...nextConfig }
      if (planningAppCodes.has(found.code) && body?.config) (found.config as JsonRow).ontology = nextConfig.ontology ?? null
      found.draftRev = (found.draftRev ?? 0) + 1
      found.draftId = `draft-${found.code}-r${found.draftRev}`
      found.updatedAt = stamp()
      audit('APP_CONFIG_SAVE', 'APP', found.code, `保存会话配置（草稿 r${found.draftRev}）`)
      persist()
      return envelope(found)
    }
    return envelope(found.config ?? {})
  }],
  [/^\/admin\/apps\/([^/]+)\/integration\/credentials$/, () => envelope([])],
  [/^\/admin\/apps\/([^/]+)\/integration$/, (m) => envelope({ appCode: m[1], endpoints: [], credentials: [] })],
  [/^\/admin\/apps\/([^/]+)\/versions$/, (m) => {
    // 版本目录按应用派生：当前版本为 PUBLISHED，其余为 SUPERSEDED，发布后自动多一条。
    const app = byId(apps, m[1])
    const top = Number(app?.currentVersionNo ?? 0)
    if (!top) return envelope([])
    const list = Array.from({ length: top }, (_, index) => {
      const versionNo = top - index
      return {
        versionNo,
        status: versionNo === top ? 'PUBLISHED' : 'SUPERSEDED',
        snapshotDigest: `sha256-${m[1]}-v${versionNo}`,
        publishedAt: '2026-10-24T15:20:00Z',
        publishedBy: '演示用户',
        note: '',
      }
    })
    return envelope(list)
  }],
  [/^\/admin\/apps\/([^/]+)\/runs$/, (m, method, body) => {
    if (method === 'GET') return envelope(runs.filter((r) => r.appCode === m[1]))
    const app = byId(apps, m[1])
    const runId = nextId('run')
    const created = {
      id: runId, runId, publicId: runId.replace('run-', 'run-demo-'), appCode: m[1], appName: app?.name ?? m[1],
      status: 'SUCCEEDED', triggerType: 'MANUAL', caller: 'u-demo', startedAt: stamp(), duration: 2140,
      errorMessage: null, nodeCount: 6, tokenUsage: { input: 1120, output: 260 },
      versionNo: Number(body?.versionNo ?? app?.currentVersionNo ?? 0),
    }
    runs.unshift(created as (typeof runs)[number])
    audit('RUN_FORMAL', 'RUN', runId, `正式运行 ${app?.name ?? m[1]} v${created.versionNo}`)
    persist()
    return envelope({ runId })
  }],
  [/^\/admin\/apps\/([^/]+)\/sessions$/, () => envelope([])],
  [/^\/admin\/apps\/([^/]+)\/publish\/precheck$/, (m) => {
    const found = byId(apps, m[1])
    if (!found) return envelope404()
    // 预检形状与发布面板契约一致：非生产演示目标 + 无人工确认要求，现场可继续发布。
    const precheck = {
      precheckId: nextId('precheck'), draftRev: found.draftRev, candidateSha256: 'a'.repeat(64),
      evalReportId: 'ev-303', passed: true, result: 'PASS', gateMode: 'BLOCKING',
      publicationTarget: 'NON_PRODUCTION', evaluationEnvironment: 'REAL',
      environmentConfirmationRequired: false,
      report: {
        // 契约见 admin/src/publish-gate.ts：按 group 归类、result 取 PASS/WAIVED/ADVISORY/SKIPPED/ERROR。
        checks: [
          { group: '工具与技能绑定齐全', result: 'PASS', detail: '5 个技能均解析到精确版本' },
          { group: '评测报告与候选摘要一致', result: 'PASS', detail: '报告 ev-303 与候选摘要一致' },
          { group: '无未解析的凭据引用', result: 'PASS', detail: '未发现凭据引用' },
          { group: '评测门禁', result: 'PASS', detail: '安全 100%、无新增回归' },
        ],
        warnings: [],
      },
    }
    latestPrecheck[found.code] = { draftRev: found.draftRev, precheckId: String(precheck.precheckId), passed: true }
    audit('APP_PRECHECK', 'APP', found.code, '发布预检通过')
    persist()
    return envelope(precheck)
  }],
  [/^\/admin\/apps\/([^/]+)\/publish\/commit$/, (m) => {
    const found = byId(apps, m[1])
    if (!found) return envelope404()
    // 发布必须来自当前草稿修订上通过的预检，不能绕过门禁直接提交。
    const precheck = latestPrecheck[found.code]
    if (!precheck || precheck.draftRev !== found.draftRev) {
      return envelope409('缺少当前草稿修订的发布预检，请先运行预检')
    }
    found.status = 'PUBLISHED'
    found.currentVersionNo = (found.currentVersionNo ?? 0) + 1
    found.draftRev = (found.draftRev ?? 1) + 1
    found.updatedAt = stamp()
    audit('APP_PUBLISH', 'APP', found.code, `发布 v${found.currentVersionNo}`)
    persist()
    return envelope(found)
  }],
  [/^\/admin\/apps\/([^/]+)\/(submit|withdraw|approve|reject|disable|enable|archive|restore)$/, (m, _method, body) => {
    const found = byId(apps, m[1])
    if (!found) return envelope404()
    const status: Record<string, string> = {
      submit: 'IN_REVIEW', withdraw: 'DRAFT', approve: 'PUBLISHED', reject: 'DRAFT',
      disable: 'DISABLED', enable: 'PUBLISHED', archive: 'ARCHIVED', restore: 'DRAFT',
    }
    found.status = status[m[2]] ?? found.status
    found.updatedAt = stamp()
    audit(`APP_${m[2].toUpperCase()}`, 'APP', found.code, body?.note ?? `生命周期动作：${m[2]}`)
    persist()
    return envelope(found)
  }],
  // 流程编排：GET 返回 definition+layout+draftRev，PUT 校验后保存并推进草稿修订。
  [/^\/admin\/flows\/([^/]+)$/, (m, method, body) => {
    const app = byId(apps, m[1])
    if (!app) return envelope404()
    if (method !== 'GET' && body?.draftRev !== app.draftRev) return envelope409('流程修订已变化，请刷新')
    if (method === 'GET') {
      return envelope(flowStore[m[1]] ?? { definition: (app as JsonRow).definition ?? null, layout: {}, draftRev: app.draftRev })
    }
    const definition = (body?.definition ?? body) as JsonRow
    if (!definition || !Array.isArray(definition.nodes)) return envelope400('编排缺少节点')
    flowStore[m[1]] = { definition, layout: body?.layout ?? {}, draftRev: (app.draftRev ?? 1) + 1 }
    ;(app as JsonRow).definition = definition
    app.draftRev = flowStore[m[1]].draftRev
    app.updatedAt = stamp()
    audit('APP_FLOW_SAVE', 'APP', app.code, `保存流程编排（草稿 r${app.draftRev}）`)
    persist()
    return envelope(flowStore[m[1]])
  }],
  [/^\/admin\/apps\/([^/]+)$/, (m, method, body) => {
    const found = byId(apps, m[1])
    if (!found) return envelope404()
    if (method === 'GET') return envelope(found)
    Object.assign(found, body ?? {})
    found.draftRev = (found.draftRev ?? 1) + 1
    found.updatedAt = stamp()
    audit('APP_UPDATE', 'APP', found.code, '编辑应用基本信息')
    persist()
    return envelope(found)
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
  [/^\/admin\/prompt-templates\/([^/]+)\/versions$/, (m) => envelope(templateVersionSets[m[1]] ?? [])],
  [/^\/admin\/prompt-templates\/([^/]+)\/versions\/([^/]+)\/render$/, () => envelope({ rendered: '（渲染后的提示词内容示例）' })],
  [/^\/admin\/prompt-templates\/([^/]+)\/versions\/([^/]+)\/publish$/, (m) => envelope({ code: m[1], version: Number(m[2]), status: 'PUBLISHED' })],
  [/^\/admin\/prompt-templates\/([^/]+)$/, (m, method, body) => {
    const found = byId(promptTemplates, m[1])
    if (!found) return envelope404()
    if (method === 'GET') return envelope({ ...found, versions: templateVersionSets[found.code] ?? [] })
    Object.assign(found, body ?? {})
    persist()
    return envelope({ ...found, versions: templateVersionSets[found.code] ?? [] })
  }],

  // ── 技能 ──
  [/^\/admin\/skills\/import-openapi\/preview$/, (_m, _method, body) => {
    const operations = parseOpenApiOperations(String(body?.document ?? ''))
    return envelope(
      operations.length
        ? { operations }
        : { operations: [], message: '未解析到操作（需 OpenAPI paths，或 METHOD /path 形式的接口行）' },
    )
  }],
  [/^\/admin\/skills\/import-openapi$/, (_m, _method, body) => {
    const operations = parseOpenApiOperations(String(body?.document ?? ''))
    const includeOps: string[] = Array.isArray(body?.includeOps) ? body!.includeOps : []
    const overrides = (body?.overrides ?? {}) as JsonRow
    const baseUrl = String((body?.defaults as JsonRow | undefined)?.baseUrl ?? '')
    const drafts = includeOps.map((operationId, index) => {
      const operation = operations.find((op) => op.operationId === operationId)
      const override = (overrides[operationId] ?? {}) as JsonRow
      const code = String(override.code ?? operationId).replace(/[^a-z0-9_]/g, '_').slice(0, 64) || `openapi_${++seq}`
      const created = {
        code, name: String(override.name ?? operation?.summary ?? `导入技能 ${index + 1}`), status: 'DRAFT', kind: 'HTTP',
        description: String(override.description ?? '由 OpenAPI 文档导入。'), currentVersion: 1,
        executionCount: 0, successRate: 0, averageDurationMs: 0, updatedAt: stamp(),
        readonly: true, sideEffect: false, version: 1, sha256: 'c'.repeat(64),
        method: operation?.method ?? 'GET', path: operation?.path ?? '', baseUrl,
        guard: override.guard ?? { roles: [], attributes: {} },
        timeoutMs: override.timeoutMs ?? 5000,
        returnSpec: override.returnSpec ?? null,
        errorSemantics: override.errorSemantics ?? [],
      }
      skills.unshift(created as (typeof skills)[number])
      return { code, name: created.name, status: 'DRAFT' }
    })
    const warnings = operations.length === 0 ? [{ code: 'NO_OPERATIONS', message: '文档未解析到可用操作' }] : []
    audit('SKILL_IMPORT', 'SKILL', drafts.map((d) => d.code).join(','), `导入 ${drafts.length} 个技能草稿`)
    persist()
    return envelope({ drafts, warnings })
  }],
  [/^\/admin\/skills$/, (_m, method, body) => {
    if (method === 'GET') return envelope(skills)
    const created = {
      code: body?.code ?? nextId('skill'), name: body?.name ?? '新技能', status: 'DRAFT', kind: body?.kind ?? 'HTTP',
      description: body?.description ?? '', currentVersion: 1, executionCount: 0, successRate: 0,
      averageDurationMs: 0, updatedAt: stamp(), ...(body ?? {}),
    }
    skills.unshift(created as (typeof skills)[number])
    audit('SKILL_CREATE', 'SKILL', created.code, `注册技能「${created.name}」`)
    persist()
    return envelope(created)
  }],
  [/^\/admin\/skills\/semantic-catalog$/, () => envelope(semanticCatalog)],
  [/^\/admin\/skills\/([^/]+)\/stats$/, (m) => skillStatsPayload(m[1])],
  [/^\/admin\/skills\/([^/]+)\/referenced-by$/, () => envelope([])],
  [/^\/admin\/skills\/([^/]+)\/versions$/, () => envelope([{ version: 2, status: 'PUBLISHED', publishedAt: '2026-10-20T10:00:00Z' }, { version: 1, status: 'DEPRECATED', publishedAt: '2026-09-01T10:00:00Z' }])],
  [/^\/admin\/skills\/([^/]+)\/drafts\/([^/]+)\/(acceptance|connection-test|publish)$/, (m) => {
    const found = byId(skills, m[1])
    if (found) { found.status = m[3] === 'publish' ? 'PUBLISHED' : found.status; found.updatedAt = stamp() }
    persist()
    return envelope({ code: m[1], version: Number(m[2]), status: m[3] === 'publish' ? 'PUBLISHED' : 'ACCEPTED' })
  }],
  [/^\/admin\/skills\/([^/]+)\/(disable|draft)$/, (m) => {
    const found = byId(skills, m[1])
    if (!found) return envelope404()
    found.status = m[2] === 'disable' ? 'DISABLED' : 'DRAFT'
    found.updatedAt = stamp()
    audit(`SKILL_${m[2].toUpperCase()}`, 'SKILL', found.code, m[2] === 'disable' ? '停用技能' : '转为草稿')
    persist()
    return envelope(found)
  }],
  [/^\/admin\/skills\/([^/]+)$/, (m, method, body) => {
    const found = byId(skills, m[1])
    if (!found) return envelope404()
    if (method === 'GET') {
      // 语义绑定页从详情 versions 里挑「已发布且只读、无副作用」的技能版本。
      const planning = planningTool(m[1])
      if (planning) return envelope({ ...found, ...planning, versions: [{ versionNo: 1, version: 1, status: 'PUBLISHED', sha256: planningSeed.releaseRef.contentDigest, inputSchema: planning.inputSchema, outputSchema: planning.outputSchema, execution: (found as JsonRow).execution, definition: { inputSchema: planning.inputSchema, outputSchema: planning.outputSchema, execution: (found as JsonRow).execution } }] })
      const execution = { readonly: true, sideEffect: false, idempotent: true, timeoutMs: 5000 }
      return envelope({
        ...found,
        versions: [
          {
            versionNo: found.currentVersion,
            version: found.currentVersion,
            status: found.status === 'DISABLED' ? 'DISABLED' : 'PUBLISHED',
            publishedAt: found.updatedAt,
            sha256: found.sha256,
            inputSchema: { type: 'object', properties: { target: { type: 'string' } } },
            outputSchema: { type: 'object', properties: { visible: { type: 'boolean' } } },
            definition: { inputSchema: { type: 'object', properties: { target: { type: 'string' } } }, execution },
            execution,
          },
        ],
      })
    }
    Object.assign(found, body ?? {}, { updatedAt: stamp() })
    audit('SKILL_UPDATE', 'SKILL', found.code, '编辑技能')
    persist()
    return envelope(found)
  }],

  // ── 运行与调试 ──
  [/^\/admin\/runs\/page$/, (_m, method) => (method === 'GET' ? envelope({ items: runs, total: runs.length, page: 1, size: 20 }) : envelope(null))],
  [/^\/admin\/runs$/, (_m, method, body) => {
    if (method === 'GET') return envelope(runs)
    const appCode = String(body?.appCode ?? apps[0].code)
    const app = byId(apps, appCode)
    const created = {
      id: nextId('run'), runId: '', publicId: nextId('run-demo'), appCode, appName: app?.name ?? appCode,
      status: 'SUCCEEDED', triggerType: app?.type === 'CHAT' ? 'CHAT' : 'API', caller: 'u-demo',
      startedAt: stamp(), duration: 2380, errorMessage: null, nodeCount: 6,
      tokenUsage: { input: 1240, output: 320 },
    }
    created.runId = created.id
    runs.unshift(created as (typeof runs)[number])
    audit('RUN_CREATE', 'RUN', created.id, `运行应用 ${created.appName}`)
    persist()
    return envelope(created)
  }],
  // 调试命名输入：列表与详情都按 {items,...}/{...} 结构返回，避免前端读 undefined。
  [/^\/admin\/debug-inputs$/, (_m, method, body) => {
    if (method === 'GET')
      return envelope({ items: debugInputs, hasMore: false, nextAfterId: 0 })
    const created = {
      id: ++seq, appDraftId: String(body?.appDraftId ?? ''), appCode: '',
      name: String(body?.name ?? '未命名输入'), revision: 1, inputs: body?.inputs ?? {}, createdAt: stamp(),
    }
    debugInputs.unshift(created)
    audit('DEBUG_INPUT_SAVE', 'APP', created.appDraftId, `保存命名输入「${created.name}」`)
    persist()
    return envelope(created)
  }],
  [/^\/admin\/debug-inputs\/([^/]+)\/promote$/, (m, _method, body) => {
    const found = byId(debugInputs, m[1])
    if (!found) return envelope404()
    const dataset = {
      id: nextId('ed'), name: String(body?.name ?? found.name), version: 1, caseCount: 1,
      status: 'ACTIVE', updatedAt: stamp(),
    }
    evaluationDatasets.unshift(dataset)
    audit('DEBUG_INPUT_PROMOTE', 'DATASET', dataset.id, `将「${found.name}」提升为评测用例`)
    persist()
    return envelope({ datasetVersion: datasetVersionOf(dataset), caseCount: 1 })
  }],
  [/^\/admin\/debug-inputs\/([^/]+)$/, (m, method, body) => {
    const found = byId(debugInputs, m[1])
    if (!found) return envelope404()
    if (method === 'GET') return envelope(found)
    if (method === 'DELETE') {
      debugInputs = debugInputs.filter((item) => item !== found)
    } else {
      Object.assign(found, body ?? {})
      found.revision = (found.revision ?? 1) + 1
    }
    persist()
    return envelope(method === 'DELETE' ? null : found)
  }],
  [/^\/admin\/debug-runs$/, (_m, method, body) => {
    if (method === 'GET') return envelope(debugRuns)
    const appCode = String(body?.appCode ?? 'payload-advisor')
    const created = {
      id: nextId('dbg'), status: 'PAUSED', appCode, startedAt: stamp(),
      controls: { paused: true }, variables: body?.variables ?? { task: '演示任务', budget: 3 },
    }
    debugRuns.unshift(created as (typeof debugRuns)[number])
    audit('DEBUG_RUN_CREATE', 'RUN', created.id, `调试运行 ${appCode}`)
    persist()
    return envelope(created)
  }],
  [/^\/admin\/debug-runs\/([^/]+)\/events$/, (m) => sse(mockRunEvents(String((byId(debugRuns, m[1]) as JsonRow | undefined)?.status ?? 'PAUSED')))],
  [/^\/admin\/debug-runs\/([^/]+)\/(controls|variables|status|resume|cancel)$/, (m, _method, body) => {
    const found = byId(debugRuns, m[1])
    if (!found) return envelope404()
    if (m[2] === 'resume') found.status = 'RUNNING'
    if (m[2] === 'cancel') found.status = 'CANCELLED'
    if (m[2] === 'controls') Object.assign(found.controls ?? {}, body ?? {}, { paused: false })
    if (m[2] === 'variables') Object.assign(found.variables ?? {}, body ?? {})
    persist()
    return envelope(found)
  }],
  [/^\/admin\/debug-runs\/([^/]+)\/node\/([^/]+)\/prompt$/, () => envelope({ label: '轨道分析', raw: '（本地固定数据：节点提示词内容）', status: 'SUCCEEDED', duration: 1820 })],
  [/^\/admin\/debug-runs\/([^/]+)$/, (m, method, body) => {
    const found = byId(debugRuns, m[1])
    if (found) {
      if (method === 'GET') return envelope(found)
      Object.assign(found, body ?? {})
      persist()
      return envelope(found)
    }
    return method === 'GET' ? envelope404() : envelope404()
  }],
  [/^\/admin\/runs\/([^/]+)\/events$/, (m) => sse(planningState.results[m[1]] ? planningRunEvents(planningState.results[m[1]]) : mockRunEvents(String((byId(runs, m[1]) as JsonRow | undefined)?.status ?? 'SUCCEEDED')))],
  [/^\/admin\/runs\/([^/]+)\/(cancel|reexecute|replay|reproduce)$/, (m) => {
    const found = byId(runs, m[1])
    if (!found) return envelope404()
    if (m[2] === 'cancel') {
      cancelPlanningRun(m[1])
      found.status = 'CANCELLED'
      approvals.filter((a) => a.runId === found.id && a.status === 'PENDING').forEach((a) => { a.status = 'REJECTED'; a.decidedAt = stamp() })
      audit('RUN_CANCEL', 'RUN', found.id, '取消运行')
    } else {
      const clone = { ...found, id: nextId('run'), publicId: nextId('run-demo'), status: 'SUCCEEDED', startedAt: stamp(), errorMessage: null }
      clone.runId = clone.id
      runs.unshift(clone as (typeof runs)[number])
      audit('RUN_REEXECUTE', 'RUN', String(found.id), `重新执行（新运行 ${clone.id}）`)
      persist()
      return envelope(clone)
    }
    persist()
    return envelope(found)
  }],
  [/^\/admin\/runs\/([^/]+)$/, (m) => {
    const found = byId(runs, m[1])
    return found ? envelope(runStatus(m[1])) : envelope404()
  }],

  // ── 确认中心 / 评测 ──
  // 能力目录：演示组 2/3 已内置 route1-visibility；登记/解析/生成应用在本机真实生效。
  [/^\/admin\/capability-packages$/, () => envelope({ items: [...capabilityPackages, ...importedPackages] })],
  [/^\/admin\/capability-packages\/import$/, (_m, _method, body) => {
    const declaration = (body?.package ?? {}) as JsonRow
    const meta = (declaration.metadata ?? {}) as JsonRow
    const id = ++seq
    const pkg = {
      packageVersionId: id, packageId: String(meta.name ?? `package-${id}`), version: String(meta.version ?? '0.0.1'),
      sha256: String(id).padStart(64, '0'), softwareId: String((declaration.software ?? {}).system ?? 'unknown-software'),
      softwareVersion: String((declaration.software ?? {}).version ?? ''),
      bindingStatus: 'UNRESOLVED', verificationStatus: 'NOT_EVALUATED', applicationCount: 0,
      createdAt: stamp(),
    }
    importedPackages.unshift(pkg)
    importedDetails[String(id)] = {
      ...pkg, declaration,
      resolvedMapping: { environment: '', routeProfileId: '', bindings: [] },
      applications: [], applicationsTruncated: false,
    }
    audit('CAPABILITY_IMPORT', 'PACKAGE', pkg.packageId, `登记能力包 ${pkg.packageId} ${pkg.version}`)
    persist()
    return envelope({ packageVersionId: id })
  }],
  [/^\/admin\/capability-packages\/([^/]+)\/bindings\/resolve$/, (m, _method, body) => {
    const id = String(m[1])
    const detail = id === '1' ? (capabilityPackageDetail as JsonRow) : importedDetails[id]
    if (!detail) return envelope404()
    detail.resolvedMapping = {
      environment: String(body?.environment ?? 'dev'),
      routeProfileId: String(body?.routeProfileId ?? ''),
      bindings: Array.isArray(body?.bindings) ? body!.bindings : [],
    }
    const pkg = [...capabilityPackages, ...importedPackages].find((p) => String(p.packageVersionId) === id) as JsonRow | undefined
    if (pkg) { pkg.bindingStatus = 'RESOLVED'; pkg.resolvedAt = stamp() }
    audit('CAPABILITY_RESOLVE', 'PACKAGE', String(detail.packageId ?? id), '解析技能绑定')
    persist()
    return envelope(detail)
  }],
  [/^\/admin\/capability-packages\/([^/]+)\/applications$/, (m, _method, body) => {
    const id = String(m[1])
    const detail = id === '1' ? (capabilityPackageDetail as JsonRow) : importedDetails[id]
    if (!detail) return envelope404()
    const declaration = (detail.declaration ?? {}) as JsonRow
    const agents = Array.isArray(declaration.agents) ? declaration.agents : []
    const planApps = Array.isArray(body?.applications) ? body!.applications : []
    const requested = planApps.length ? planApps : agents
    const created = requested.map((agent: JsonRow, index: number) => {
      const code = String(agent.code ?? agent.appCode ?? `package-${id}-app-${index + 1}`)
      const existing = byId(apps, code)
      if (existing) return existing
      const app = {
        code, name: String(agent.name ?? code), displayName: String(agent.name ?? code), status: 'DRAFT',
        type: String(agent.type ?? 'CHAT'), domain: '航天', bizCategory: '能力包', tags: [String(detail.packageId ?? '能力包')],
        description: `由能力包 ${detail.packageId} ${detail.version} 生成。`, currentVersionNo: null, draftRev: 1,
        draftId: `draft-${code}-r1`,
        createdAt: stamp(), updatedAt: stamp(),
        config: {
          modelBinding: { modelEntryId: 'me-001' },
          promptSource: 'TEMPLATE',
          promptRefs: [],
          skillRefs: (agent.skillRefs ?? []).map((s: JsonRow) => ({ code: s.skillCode ?? s.code, version: s.version ?? 1 })),
          guard: { roles: ['OPERATOR'] },
          requiredLabels: ['PUBLIC'],
          temperature: 0.2, maxTurns: 8,
          systemPrompt: '由能力包生成的对话助手，按声明的工具契约执行。',
        },
      }
      apps.unshift(app as (typeof apps)[number])
      return app
    })
    detail.applications = created.map((a: JsonRow, index: number) => ({ appId: 200 + index, appCode: a.code, name: a.name, type: a.type ?? 'CHAT', status: a.status, draftRev: a.draftRev ?? 1, currentVersionNo: a.currentVersionNo ?? null }))
    detail.applicationCount = created.length
    const pkg = [...capabilityPackages, ...importedPackages].find((p) => String(p.packageVersionId) === id) as JsonRow | undefined
    if (pkg) pkg.applicationCount = created.length
    audit('CAPABILITY_APPLICATIONS', 'PACKAGE', String(detail.packageId ?? id), `生成 ${created.length} 个应用草稿`)
    persist()
    return envelope(detail.applications)
  }],
  [/^\/admin\/capability-packages\/([^/]+)$/, (m) => {
    const id = String(m[1])
    if (id === '1') return envelope(capabilityPackageDetail)
    const detail = importedDetails[id]
    return detail ? envelope(detail) : envelope404()
  }],
  [/^\/admin\/_demo\/reset$/, async () => { await resetMockArtifacts();try { sessionStorage.removeItem(STORE_KEY); sessionStorage.removeItem('ap-business-governance-v1') } catch { /* 隐私模式忽略 */ } try { localStorage.removeItem(STORE_KEY) } catch { /* 旧版持久存储可能不可用。 */ } return envelope({ ok: true, reload: true }) }],
  [/^\/admin\/approvals\/([^/]+)\/([^/]+)$/, async (m, _method, body) => {
    if (m[2] === 'planning-confirm') {
      const savedState=JSON.parse(JSON.stringify(planningState))
      const before=planningState.pending[m[1]]?.response as unknown as JsonRow|undefined
      const decision = decidePlanningRun(m[1], body?.approved !== false && body?.decision !== 'REJECT')
      if (decision?.conflict) return new Response(JSON.stringify({ code: 'APPROVAL_CONFLICT', message: decision.conflict, data: null }), { status: 409, headers: { 'Content-Type': 'application/json' } })
      if (decision?.response) {
        const result=decision.response
        const errors=skillAccessErrors(result.toolCalls.slice((before?.toolCalls as unknown[]|undefined)?.length??0),before?.boundSkillRefs??byId(apps,planningApplication.code)?.config.skillRefs??[],before?.businessActorId)
        if(errors.length){Object.assign(planningState,savedState);persist();return envelope409(errors.join('；'))}
        await materializeResponseArtifacts(result)
        planningState.results[result.runId]=JSON.parse(JSON.stringify(result))
        const previous = byId(runs, m[1])
        savePlanningRun(previous?.appCode ?? planningApplication.code, result)
        const approval = approvals.find(item => item.runId === m[1] && item.nodeId === m[2])
        if (approval) Object.assign(approval, { status: result.approval!.status, decidedAt: stamp() })
        audit(result.status === 'REJECTED' ? 'PLANNING_REJECT' : 'PLANNING_APPROVE', 'RUN', m[1], result.reply.split('\n')[0])
        persist()
        return envelope(approval)
      }
    }
    const found = approvals.find((a) => a.runId === m[1] && ((a as JsonRow).nodeInstanceId === m[2] || a.nodeId === m[2]))
    if (!found) return envelope404()
    // 同一确认单只能处理一次：已批准/已拒绝后再提交必须 409，避免演示里重复审批成功。
    if (found.status !== 'PENDING') return envelope409('该确认单已处理，请刷新后查看最新状态')
    const approved = body?.approved !== false && body?.decision !== 'REJECT'
    found.status = approved ? 'APPROVED' : 'REJECTED'
    found.decidedAt = stamp()
    const run = byId(runs, found.runId) as JsonRow | undefined
    if (run && found.runId === 'run-104') {
      run.status = approved ? 'SUCCEEDED' : 'REJECTED'
      run.duration = 3200
      if (approved) run.tokenUsage = { input: 2680, output: 640 }
    } else if (run && run.status === 'WAITING_APPROVAL') {
      run.status = approved ? 'SUCCEEDED' : 'REJECTED'
    }
    audit(approved ? 'APPROVAL_APPROVE' : 'APPROVAL_REJECT', 'APPROVAL', String(found.id), `${found.nodeName}：${approved ? '批准' : '拒绝'}`)
    persist()
    return envelope(found)
  }],
  [/^\/admin\/approvals/, () => envelope(approvals)],
  // 评测输入契约：列表返回 {items:[{datasetVersion,...}],hasMore,nextAfterVersion}；
  // 选定版本返回 {datasetVersion,cases}，发布面板按 dataset.cases.length 渲染。
  [/^\/admin\/evaluation-datasets\/([^/]+)\/([^/]+)$/, (m) => {
    const datasetVersion = decodeURIComponent(m[2])
    const found = evaluationDatasets.find((d) => datasetVersionOf(d) === datasetVersion)
    if (!found) return envelope404()
    const cases = Array.from({ length: Number(found.caseCount ?? 0) }, (_, index) => ({
      caseId: `${found.id}-case-${index + 1}`,
      name: `用例 ${index + 1}`,
      input: { target: 'SAT-01', station: 'ST-02' },
      expected: { visible: true },
      assertions: [{ kind: 'EQUALS', path: 'visible', value: true }],
    }))
    return envelope({ datasetVersion, cases })
  }],
  [/^\/admin\/evaluation-datasets\/([^/]+)$/, (m, method, body) => {
    const found = byId(evaluationDatasets, m[1])
    if (!found) return envelope404()
    if (method !== 'GET') Object.assign(found, body ?? {})
    persist()
    return envelope(found)
  }],
  [/^\/admin\/evaluation-datasets$/, (_m, method, body) => {
    if (method === 'GET')
      return envelope({
        items: evaluationDatasets.map((d) => ({
          datasetVersion: datasetVersionOf(d),
          appCode: 'space-visibility-agent',
          caseCount: d.caseCount,
          savedAt: d.updatedAt ?? stamp(),
        })),
        hasMore: false,
        nextAfterVersion: null,
      })
    const created = {
      id: nextId('ed'), name: String(body?.name ?? '新评测数据集'), version: 1,
      caseCount: Number(body?.caseCount ?? 0), status: 'ACTIVE', updatedAt: stamp(),
    }
    evaluationDatasets.unshift(created)
    audit('DATASET_CREATE', 'DATASET', created.id, `新建评测数据集「${created.name}」`)
    persist()
    return envelope({ datasetVersion: datasetVersionOf(created), caseCount: created.caseCount })
  }],
  [/^\/admin\/evaluations$/, (_m, method, body) => {
    if (method === 'GET') return envelope(evaluations)
    const dataset = byId(evaluationDatasets, String(body?.datasetId ?? evaluationDatasets[0]?.id ?? ''))
    const created = {
      id: nextId('ev'), datasetId: dataset?.id ?? '', datasetName: dataset?.name ?? '',
      status: 'RUNNING', passed: 0, failed: 0, total: dataset?.caseCount ?? 0, passRate: 0,
      runId: null as string | null, startedAt: stamp(), duration: 0,
    }
    evaluations.unshift(created)
    audit('EVALUATION_CREATE', 'EVALUATION', created.id, `发起评测「${created.datasetName}」`)
    persist()
    return envelope(created)
  }],
  [/^\/admin\/evaluations\/([^/]+)\/resume$/, (m, _method, body) => {
    const found = byId(evaluations, m[1])
    if (!found) return envelope404()
    found.status = 'SUCCEEDED'
    found.passed = Number(body?.passed ?? found.total)
    found.failed = Number(body?.failed ?? 0)
    found.passRate = found.total ? found.passed / found.total : 1
    found.duration = 42000
    audit('EVALUATION_RESUME', 'EVALUATION', String(found.id), '继续并完成评测')
    persist()
    return envelope(found)
  }],
  [/^\/admin\/evaluations\/([^/]+)$/, (m, method) => {
    const found = byId(evaluations, m[1])
    if (method === 'GET') return found ? envelope(found) : envelope404()
    return envelope(found ?? { id: m[1] })
  }],

  // ── 演示数据补齐：服务管理 / 技能统计 / 语义与目标 / 配置发布 ──
  [/^\/admin\/services$/, (_m, method, body) => {
    if (method === 'GET') return envelope(demoServices)
    const created = {
      code: String(body?.code ?? 'new-service'), name: String(body?.name ?? '新服务'),
      command: Array.isArray(body?.command) ? body!.command : ['java', '-jar', 'service.jar'],
      workDir: String(body?.workDir ?? 'D:/demo'), port: Number(body?.port ?? 8080),
      healthPath: String(body?.healthPath ?? '/health'), startupTimeoutMs: 30000, stopTimeoutMs: 5000,
      enabled: true, state: 'STOPPED', healthUrl: `http://127.0.0.1:${Number(body?.port ?? 8080)}${String(body?.healthPath ?? '/health')}`,
      updatedAt: stamp(),
    }
    demoServices.unshift(created as (typeof demoServices)[number])
    audit('SERVICE_CREATE', 'SERVICE', created.code, `登记服务 ${created.name}`)
    persist()
    return envelope(created)
  }],
  [/^\/admin\/services\/([^/]+)\/health$/, (m) => {
    const found = demoServices.find((item) => item.code === m[1])
    if (!found) return envelope404()
    return envelope({ reachable: found.state === 'RUNNING', statusCode: found.state === 'RUNNING' ? 200 : null, latencyMs: 18, checkedAt: stamp() })
  }],
  [/^\/admin\/services\/([^/]+)\/logs$/, (m) => {
    const found = demoServices.find((item) => item.code === m[1])
    if (!found) return envelope404()
    return envelope({ available: true, lines: [`[demo] ${found.code} 启动于 ${found.updatedAt}`, '[demo] 已加载配置 config/application.yml', '[demo] started on port ' + found.port] })
  }],
  [/^\/admin\/services\/([^/]+)\/(start|stop|restart)$/, (m, method) => {
    const found = demoServices.find((item) => item.code === m[1])
    if (!found) return envelope404()
    found.state = m[2] === 'stop' ? 'STOPPED' : 'RUNNING'
    found.updatedAt = stamp()
    audit('SERVICE_' + m[2].toUpperCase(), 'SERVICE', found.code, `${found.name} ${found.state}`)
    persist()
    return envelope(found)
  }],
  [/^\/admin\/services\/([^/]+)$/, (m, method, body) => {
    const found = demoServices.find((item) => item.code === m[1])
    if (!found) return envelope404()
    if (method === 'PUT') {
      Object.assign(found, body ?? {})
      audit('SERVICE_UPDATE', 'SERVICE', found.code, `更新服务 ${found.name}`)
      persist()
    }
    return envelope(found)
  }],
  [/^\/admin\/semantic-bindings$/, (_m, method, body) => {
    if (method === 'GET') return envelope(semanticBindings)
    const created = {
      bindingId: `sb-${++seq}`, appCode: String(body?.appCode ?? 'satellite-assistant'), status: 'ACTIVE',
      releaseRef: body?.releaseRef ?? semanticReleaseRef, objectType: String(body?.objectType ?? 'Satellite'),
      opCode: String(body?.opCode ?? 'orbit-query'), skillCode: String(body?.skillCode ?? 'orbit_query'),
      skillVersion: Number(body?.skillVersion ?? 2), factFields: body?.factFields ?? [], ruleRefs: body?.ruleRefs ?? [],
      createdAt: stamp(),
    }
    semanticBindings.unshift(created as (typeof semanticBindings)[number])
    audit('SEMANTIC_BINDING_CREATE', 'APP', created.appCode, `语义绑定 ${created.objectType}/${created.opCode}`)
    persist()
    return envelope(created)
  }],
  [/^\/admin\/semantic-bindings\/([^/]+)$/, (m, method, body) => {
    const found = semanticBindings.find((item) => item.bindingId === m[1])
    if (!found) return envelope404()
    if (method !== 'GET') Object.assign(found, body ?? {})
    persist()
    return envelope(found)
  }],
  [/^\/admin\/config-releases$/, (_m, method, body) => {
    if (method === 'GET') return envelope(demoReleases)
    const created = {
      releaseId: `rel-${++seq}`, appCode: String(body?.appCode ?? 'satellite-assistant'),
      appVersion: Number(body?.appVersion ?? 3), candidateSha256: `sha256-demo-cand-${seq}`,
      experienceRev: 1, integrationProfileId: 'ip-sat-1', integrationProfileRev: 1,
      connectionProfileRefs: [] as { profileId: string; profileRev: number }[], activationScope: String(body?.environment ?? 'production'),
      status: 'DRAFT', digest: `sha256-demo-rel-${seq}`, createdBy: '演示用户', createdAt: stamp(), updatedAt: stamp(),
    }
    demoReleases.unshift(created as (typeof demoReleases)[number])
    audit('CONFIG_RELEASE_CREATE', 'APP', created.appCode, `新建配置发布 ${created.releaseId}`)
    persist()
    return envelope(created)
  }],
  [/^\/admin\/config-releases\/([^/]+)\/(activate|disable)$/, (m) => {
    const found = demoReleases.find((item) => item.releaseId === m[1])
    if (!found) return envelope404()
    if (m[2] === 'activate') {
      demoReleases.forEach((item) => { if (item.appCode === found.appCode && item.activationScope === found.activationScope) item.status = 'SUPERSEDED' })
      found.status = 'ACTIVE'
    } else found.status = 'DISABLED'
    found.updatedAt = stamp()
    persist()
    return envelope(found)
  }],
  [/^\/admin\/config-releases\/([^/]+)$/, (m, method, body) => {
    const found = demoReleases.find((item) => item.releaseId === m[1])
    if (!found) return envelope404()
    if (method !== 'GET') Object.assign(found, body ?? {})
    return envelope(found)
  }],
  [/^\/admin\/integration-profiles$/, () => envelope(demoIntegrationProfiles)],
  [/^\/admin\/connection-profiles$/, () => envelope(demoConnections)],
  [/^\/admin\/environment-bindings$/, () => envelope(demoBindings)],
  [/^\/admin\/apps\/([^/]+)\/experiences$/, (m) => envelope({
    appCode: m[1], experienceRev: 1, status: 'ACTIVE', createdBy: '演示用户',
    createdAt: '2026-10-24T10:00:00Z', updatedAt: '2026-10-24T10:00:00Z',
    revisions: [{ experienceRev: 1, status: 'ACTIVE', createdBy: '演示用户', createdAt: '2026-10-24T10:00:00Z', channels: ['console'], notes: '默认体验' }],
  })],
  [/^\/admin\/apps\/([^/]+)\/demo-chat$/, async (m, _method, body) => {
    const app = byId(apps, m[1])
    const message = String(body?.message ?? '')
    if (planningAppCodes.has(m[1])) {
      if (!app) return envelope404()
      let prepared:Awaited<ReturnType<typeof preparePlanningAttachments>>
      try {prepared=await preparePlanningAttachments(body??{})}catch(problem){return envelope400(problem instanceof Error?problem.message:'文件参数无法解析')}
      const pinned=body?.resolvedSnapshot??app
      const savedState=JSON.parse(JSON.stringify(planningState))
      const result = planningTurn(m[1], pinned.config ?? {}, prepared.message, String(body?.conversationId ?? nextId('conversation')), nextId('run'))
      result.toolCalls.unshift(...prepared.toolCalls)
      if(result.artifacts.length)for(const [code,name] of [['planning-file-export','生成结果文件'],['planning-file-download','提供受控下载入口']])result.toolCalls.push({id:result.runId+'-'+code,skillCode:code,name,status:'SUCCEEDED',input:{planId:result.plan?.id},output:{files:result.artifacts.map(file=>({artifactId:file.id,name:file.name,downloadUrl:file.downloadUrl}))}})
      const errors=skillAccessErrors(result.toolCalls,pinned.config?.skillRefs??[])
      if(!businessActor()?.permissions.includes('space.plan'))errors.push('当前业务身份没有观测规划权限 space.plan')
      if(errors.length){Object.assign(planningState,savedState);denyPlanningCalls(result,errors)}
      else await materializeResponseArtifacts(result)
      Object.assign(result,{boundSkillRefs:pinned.config?.skillRefs??[],businessActorId:governanceState.actorId})
      planningState.results[result.runId]=JSON.parse(JSON.stringify(result))
      if(planningState.pending[result.runId])planningState.pending[result.runId].response=JSON.parse(JSON.stringify(result))
      savePlanningRun(m[1], result)
      Object.assign(byId(runs, result.runId)!, { inputs: {message,attachments:prepared.attachments,parameters:prepared.parameters},...(body?.resolvedVersionNo?{versionNo:body.resolvedVersionNo,versionRefs:{...(byId(runs,result.runId) as JsonRow).versionRefs,app:`${m[1]}@${body.resolvedVersionNo}`}}:{}) })
      audit('PLANNING_CHAT', 'RUN', result.runId, message.slice(0, 80))
      for (const tool of result.toolCalls.filter(item => item.status === 'SUCCEEDED')) {
        const skill = byId(skills, tool.skillCode)
        if (skill) skill.executionCount = (skill.executionCount ?? 0) + 1
      }
      persist()
      return envelope(result)
    }
    const reply = demoReply(m[1], message)
    const runId = nextId('run')
    runs.unshift({
      id: runId, runId, publicId: nextId('run-demo'), appCode: m[1], appName: app?.name ?? m[1],
      status: 'SUCCEEDED', triggerType: 'CHAT', caller: 'u-demo', startedAt: stamp(), duration: 1860,
      errorMessage: null, nodeCount: 4, tokenUsage: { input: 820, output: 210 },
    } as (typeof runs)[number])
    audit('CHAT_TURN', 'APP', m[1], message.slice(0, 40))
    persist()
    return envelope({ runId, reply, status: 'SUCCEEDED', citations: [] })
  }],

  // ── 接入模板 / 接入会话 / 模型深度健康（DEMO-04/08）──
  [/^\/admin\/onboarding-templates$/, (_m, method, body) => {
    if (method === 'GET') return envelope(demoOnboardingTemplates)
    const created = {
      code: String(body?.code ?? `template-${++seq}`), name: String(body?.name ?? '新接入模板'),
      kind: 'QUERY', status: 'DRAFT', currentRev: 1, updatedAt: stamp(),
      description: String(body?.description ?? ''), connections: [], experiences: [],
    }
    demoOnboardingTemplates.unshift(created as (typeof demoOnboardingTemplates)[number])
    audit('ONBOARDING_TEMPLATE_CREATE', 'TEMPLATE', created.code, `新建接入模板「${created.name}」`)
    persist()
    return envelope(created)
  }],
  [/^\/admin\/onboarding-templates\/([^/]+)\/(publish|disable)$/, (m) => {
    const found = byId(demoOnboardingTemplates, m[1])
    if (!found) return envelope404()
    found.status = m[2] === 'publish' ? 'PUBLISHED' : 'DISABLED'
    found.updatedAt = stamp()
    audit('ONBOARDING_TEMPLATE_' + m[2].toUpperCase(), 'TEMPLATE', found.code, `模板状态改为 ${found.status}`)
    persist()
    return envelope(found)
  }],
  [/^\/admin\/onboarding-templates\/([^/]+)$/, (m) => {
    const found = byId(demoOnboardingTemplates, m[1])
    return found ? envelope(found) : envelope404()
  }],
  [/^\/admin\/onboarding-sessions\/templates$/, () =>
    envelope(demoOnboardingTemplates.filter((t) => t.kind === 'QUERY' && t.status === 'PUBLISHED'))],
  [/^\/admin\/onboarding-sessions$/, (_m, method) => (method === 'GET' ? envelope({ items: [] }) : envelope404())],
  [/^\/admin\/model-health$/, () => {
    const entries = modelEntries.map((item) => ({
      entryId: item.id, model: item.modelId, status: 'HEALTHY', latencyMs: 42, cached: false,
      checks: [{ key: 'chat', label: '对话', status: 'PASS', message: '最小对话请求返回 200' }],
    }))
    return envelope({
      status: 'HEALTHY',
      counts: { total: entries.length, healthy: entries.length, degraded: 0, unhealthy: 0, unknown: 0 },
      entries, ttlSeconds: 60, checkedAt: stamp(),
    })
  }],

  // ── 统计 / 审计 / 分类 ──
  [/^\/admin\/statistics\/snapshots$/, () => envelope(statisticsSnapshots)],
  [/^\/admin\/statistics\/snapshots\/([^/]+)$/, () => envelope({ ...statisticsSummary, snapshotId: 'ss-20261025', page: 1, pageSize: 20, groups: statisticsGroups, groupsTotal: statisticsGroups.length, skillGroups: statisticsSkillGroups, skillGroupsTotal: statisticsSkillGroups.length })],
  [/^\/admin\/statistics\/runs/, () => envelope(runs)],
  [/^\/admin\/statistics\/rebuild$/, () => envelope({ ...statisticsSummary, snapshotId: 'ss-new', page: 1, pageSize: 20, groups: statisticsGroups, groupsTotal: statisticsGroups.length, skillGroups: statisticsSkillGroups, skillGroupsTotal: statisticsSkillGroups.length })],
  [/^\/admin\/statistics$/, () => envelope({ ...statisticsSummary, page: 1, pageSize: 20, groups: statisticsGroups, groupsTotal: statisticsGroups.length, skillGroups: statisticsSkillGroups, skillGroupsTotal: statisticsSkillGroups.length })],
  [/^\/admin\/system\/status$/, () => envelope({
    ok: false, checkedAt: now, version: 'demo',
    checks: [
      { name: 'PostgreSQL', ok: true, detail: '连接可用' },
      { name: 'Redis', ok: false, detail: '本地未连接 Redis' },
      { name: 'model-gateway', ok: true, detail: 'HTTP 200' },
    ],
  })],
  [/^\/admin\/audit$/, () => envelope(auditLogs)],
  [/^\/admin\/classifications$/, () => envelope(classifications)],
]

// ── Mock transport ──
export async function mockTransport(
  url: RequestInfo | URL,
  options?: RequestInit,
): Promise<Response> {
  ensureRestored()
  const method = (options?.method ?? 'GET').toUpperCase()
  const parsed = new URL(String(url), 'http://mock.local')
  const path = parsed.pathname
  const artifactResponse = await mockArtifactRequest(path, options)
  if (artifactResponse) return artifactResponse
  // 本地演示：写请求在内存与 sessionStorage 中真实生效，不再拦成 405。
  // 解析请求体供 handler 使用；非法 JSON 视为空体，交由 handler 回 422/默认值。
  let body: JsonRow | undefined
  const raw = options?.body
  if (typeof raw === 'string' && raw.trim()) {
    try {
      body = JSON.parse(raw) as JsonRow
    } catch {
      body = undefined
    }
  } else if (raw && typeof raw === 'object' && !(raw instanceof FormData) && !(raw instanceof Blob)) {
    body = raw as JsonRow
  }
  const context = { apps, skills, runs, approvals, evaluations, evaluationDatasets, auditLogs,
    modelProviders, modelEntries, promptTemplates, templateVersionSets, debugRuns,
    demoReleases, demoIntegrationProfiles, demoConnections, demoBindings, demoOnboardingTemplates,
    flowStore, debugInputs, audit, persist, nextId, sse, datasetVersionOf }
  normalizeDemoToolSchemas(skills as unknown as ToolSkill[], mockExtensionState.skillVersions)
  if(body){delete body.resolvedSnapshot;delete body.resolvedVersionNo}
  const toolCatalog = path.match(/^\/admin\/apps\/([^/]+)\/available-tools$/)
  if (toolCatalog) {
    if (method !== 'GET') return envelope400('可用技能目录仅支持读取。')
    const app = byId(apps,toolCatalog[1])
    if (!app) return envelope404()
    normalizeMockResources(context)
    const versionNo=parsed.searchParams.get('versionNo')
    const selected=versionNo ? mockExtensionState.versions[app.code]?.find((version:JsonRow)=>version.versionNo===Number(versionNo))?.snapshot : app
    if (!selected) return envelope404()
    return envelope(availableTools(selected as ToolApp,skills as unknown as ToolSkill[],mockExtensionState.skillVersions,parsed.searchParams.get('conversationId')??''))
  }
  const assistantRequest=path.match(/^\/admin\/apps\/([^/]+)\/(demo-chat|runs)$/)
  if(assistantRequest&&assistantRequest[2]==='runs'&&planningAppCodes.has(assistantRequest[1])&&method==='POST'){
    const app=byId(apps,assistantRequest[1]);if(!app)return envelope404()
    return mockTransport(`/admin/apps/${assistantRequest[1]}/demo-chat`,{...options,method:'POST',body:JSON.stringify({...body,versionNo:body?.versionNo??app.currentVersionNo,message:body?.inputs?.message??body?.message??planningSeed.examples[1].message,parameters:body?.parameters??body?.inputs?.parameters??{},attachments:body?.attachments??body?.inputs?.attachments??[]})})
  }
  if(assistantRequest&&method==='POST'&&(body?.versionNo!==undefined||assistantRequest[2]==='runs')){
    const app=byId(apps,assistantRequest[1]);if(!app)return envelope404()
    mockStateRequest(`/admin/apps/${app.code}/versions`,'GET',undefined,new URLSearchParams(),context)
    const versionNo=Number(body?.versionNo??app.currentVersionNo)
    const snapshot=mockExtensionState.versions[app.code]?.find((item:JsonRow)=>item.versionNo===versionNo)?.snapshot
    if(!snapshot)return envelope404()
    const key=app.code+':'+String(body?.conversationId??'')
    const pins=mockExtensionState.chatVersions??=( {})
    if(body?.conversationId&&pins[key]&&pins[key]!==versionNo)return envelope409('此会话已固定运行版本，请新建对话后切换版本。')
    if(body?.conversationId)pins[key]=versionNo
    body={...(body??{}),resolvedSnapshot:JSON.parse(JSON.stringify(snapshot)),resolvedVersionNo:versionNo}
  }
  if (assistantRequest?.[2] === 'demo-chat' && method === 'POST' && body?.toolSelection) {
    const app=byId(apps,assistantRequest[1]); if(!app)return envelope404()
    normalizeMockResources(context)
    const selected=(body.resolvedSnapshot??app) as ToolApp
    const conversationId=String(body.conversationId??nextId('conversation'))
    const options=availableTools(selected,skills as unknown as ToolSkill[],mockExtensionState.skillVersions,conversationId)
    const tool=options.find(tool=>tool.code===body!.toolSelection.code&&tool.version===Number(body!.toolSelection.version))
    if(!tool)return envelope409('所选技能版本未绑定到当前智能体、尚未发布或已停用，请刷新可用技能。')
    let parameters:Record<string,unknown>
    try { parameters=selectedToolParameters(tool,body.parameters??{}) }
    catch(problem){return envelope400(problem instanceof Error?problem.message:'技能参数校验未通过。')}
    const accessErrors=skillAccessErrors([{skillCode:tool.code,version:tool.version}],selected.config.skillRefs as JsonRow[]??[])
    if(accessErrors.length)return envelope409(accessErrors.join('；'))
    // 审批统一复用原入口，保留申请人/审批人分离及审批后重查，不以表单参数替换业务身份。
    if(tool.code==='um-approval-decide'){
      const pending=Object.values(governanceState.pending).find(item=>item.requestId===parameters.requestId)
      if(!pending?.response.approval)return envelope409('没有找到该申请当前待处理的审批节点。')
      if(parameters.approved===false&&!String(parameters.reason??'').trim())return envelope400('拒绝申请时请填写审批意见。')
      const decision=await mockTransport(`/admin/approvals/${pending.response.runId}/${pending.response.approval.nodeId}`,{method:'POST',body:JSON.stringify({approved:parameters.approved,reason:parameters.reason})})
      if(!decision.ok)return decision
      const response=governanceState.responses[pending.response.runId]
      audit('SELECTED_TOOL_APPROVAL','RUN',response.runId,JSON.stringify({code:tool.code,parameters}));persist()
      return envelope({...response,selectedTool:{code:tool.code,version:tool.version},parameters})
    }
    const previousPlanning=JSON.parse(JSON.stringify(planningState)),previousGovernance=JSON.parse(JSON.stringify(governanceState))
    const rollback=()=>{Object.assign(planningState,previousPlanning);Object.assign(governanceState,previousGovernance)}
    try{
      const result=await executeSelectedTool({app:selected,tool,parameters,conversationId,runId:nextId('run')})
      const errors=skillAccessErrors(result.toolCalls,selected.config.skillRefs as JsonRow[]??[])
      if(/planning-(ground-plan|space-plan|task-submit|task-cancel|selection-save)/.test(tool.code)&&!businessActor()?.permissions.includes('space.plan'))errors.push('当前业务身份没有观测规划权限 space.plan')
      if(errors.length){rollback();return envelope409(errors.join('；'))}
      await materializeResponseArtifacts(result)
      Object.assign(result,{boundSkillRefs:selected.config.skillRefs??[],businessActorId:governanceState.actorId})
      for(const call of result.toolCalls)Object.assign(call,{version:Number((selected.config.skillRefs as JsonRow[]).find(ref=>ref.code===call.skillCode)?.version??tool.version)})
      if(app.code===governanceApp.code){governanceState.responses[result.runId]=JSON.parse(JSON.stringify(result));if(governanceState.pending[result.runId])governanceState.pending[result.runId].response=JSON.parse(JSON.stringify(result));saveGovernanceRun(result)}
      else {planningState.results[result.runId]=JSON.parse(JSON.stringify(result));if(planningState.pending[result.runId])planningState.pending[result.runId].response=JSON.parse(JSON.stringify(result));savePlanningRun(app.code,result as PlanningResponse)}
      const run=byId(runs,result.runId) as JsonRow
      Object.assign(run,{inputs:{message:body.message,toolSelection:body.toolSelection,parameters},...(body.resolvedVersionNo?{versionNo:body.resolvedVersionNo}:{})})
      run.versionRefs={...run.versionRefs,app:`${app.code}@${body.resolvedVersionNo??app.currentVersionNo??app.draftRev}`,skills:Object.fromEntries(result.toolCalls.map(call=>[call.skillCode,(call as unknown as JsonRow).version]))}
      for(const call of result.toolCalls.filter(call=>call.status==='SUCCEEDED')){const skill=byId(skills,call.skillCode);if(skill)skill.executionCount=(skill.executionCount??0)+1}
      audit('SELECTED_TOOL_EXECUTE','RUN',result.runId,`${tool.code}@${tool.version}`);persist();return envelope(result)
    }catch(problem){rollback();persist();return envelope400(problem instanceof Error?problem.message:'技能执行失败，请检查参数后重试。')}
  }
  const business = governanceBusinessRequest(path, method, body ?? {})
  if (business) { if(path==='/admin/business/reset'&&method==='POST'){for(const run of runs.filter(run=>run.appCode===governanceApp.code&&run.status==='WAITING_APPROVAL')){run.status='CANCELLED';for(const approval of approvals.filter(item=>item.runId===run.id&&item.status==='PENDING'))approval.status='CANCELLED'}}persist(); return business.error ? new Response(JSON.stringify({ code: 'BUSINESS_CONFLICT', message: business.error, data: null }), { status: business.status ?? 409, headers: { 'Content-Type': 'application/json' } }) : envelope(business.data) }
  if (path === '/admin/apps/user-governance-assistant/planning-scenarios') return method === 'GET' ? envelope(governanceScenario) : envelope400('只读目录')
  if (/^\/admin\/apps\/user-governance-assistant\/(demo-chat|runs)$/.test(path) && method === 'POST') {
    const app = byId(apps, governanceApp.code)!
    const selected=body?.resolvedSnapshot??app
    const savedState=JSON.parse(JSON.stringify(governanceState))
    const result = governanceTurn({ appCode: app.code, conversationId: String(body?.conversationId ?? nextId('conversation')), message: String(body?.inputs?.message ?? body?.message ?? governanceScenario.examples[1].message), runId: nextId('run'), config: selected.config ?? {} })
    const errors=skillAccessErrors(result.toolCalls,selected.config?.skillRefs??[])
    if(errors.length){Object.assign(governanceState,savedState);governanceBusinessRequest('/admin/business/actor','POST',{actorId:savedState.actorId});result.status='BLOCKED';result.reply='### 技能调用已阻断\n\n'+errors.map(error=>'- '+error).join('\n');result.toolCalls=result.toolCalls.map(call=>({...call,status:'BLOCKED',output:{reason:'调用权限或版本校验未通过'}}));result.artifacts=[];result.ontology=null;result.workflow=[];delete result.approval;delete result.task;delete result.businessChange}
    else await materializeResponseArtifacts(result)
    Object.assign(result,{boundSkillRefs:selected.config?.skillRefs??[],businessActorId:governanceState.actorId})
    governanceState.responses[result.runId]=JSON.parse(JSON.stringify(result))
    if(governanceState.pending[result.runId])governanceState.pending[result.runId].response=JSON.parse(JSON.stringify(result))
    saveGovernanceRun(result); Object.assign(byId(runs, result.runId)!, { inputs: {message:body?.message,inputs:body?.inputs,parameters:body?.parameters,attachments:body?.attachments},...(body?.resolvedVersionNo?{versionNo:body.resolvedVersionNo,versionRefs:{...(byId(runs,result.runId) as JsonRow).versionRefs,app:`${app.code}@${body.resolvedVersionNo}`}}:{}) }); persist(); return envelope(result)
  }
  const governanceApproval = path.match(/^\/admin\/approvals\/([^/]+)\/([^/]+)$/)
  if (governanceApproval && governanceState.responses[governanceApproval[1]] && method === 'POST') {
    const pending=governanceState.pending[governanceApproval[1]]
    if(pending&&pending.response.approval?.nodeId!==governanceApproval[2])return envelope409('审批节点已变化，请刷新当前待办。')
    const savedState=JSON.parse(JSON.stringify(governanceState))
    const previous=pending?.response as unknown as JsonRow|undefined
    const decision = decideGovernanceRun(governanceApproval[1], body?.approved !== false && body?.decision !== 'REJECT')
    if (decision?.conflict) return envelope409(decision.conflict)
    if (decision?.response) {
      const result=decision.response
      const added=result.toolCalls.slice((previous?.toolCalls as unknown[]|undefined)?.length??0)
      const requesterId=String(previous?.businessChange?.requester??previous?.businessActorId??governanceState.actorId)
      const refs=previous?.boundSkillRefs??appSeeds.find(app=>app.code===governanceApp.code)?.config.skillRefs??[]
      const errors=added.flatMap(call=>skillAccessErrors([call],refs,call.skillCode==='um-approval-decide'?savedState.actorId:requesterId))
      if(errors.length){Object.assign(governanceState,savedState);governanceBusinessRequest('/admin/business/actor','POST',{actorId:savedState.actorId});persist();return envelope409(errors.join('；'))}
      await materializeResponseArtifacts(result);governanceState.responses[result.runId]=JSON.parse(JSON.stringify(result));if(governanceState.pending[result.runId])governanceState.pending[result.runId].response=JSON.parse(JSON.stringify(result));saveGovernanceRun(result); persist(); return envelope(approvals.find(item => item.runId === governanceApproval[1] && item.nodeId === governanceApproval[2]))
    }
  }
  if (path.startsWith('/admin/business/')) return envelope404()
  Object.assign(context, { businessContext: () => governanceBusinessRequest('/admin/business/context', 'GET', {})?.data, actorRoles: () => governanceState.actors.find(actor => actor.id === governanceState.actorId)?.roles ?? [] })
  const extension = mockStateRequest(path, method, body, parsed.searchParams, context)
    ?? mockRuntimeRequest(path, method, body, parsed.searchParams, context)
    ?? mockOnboardingRequest(path, method, body, parsed.searchParams, context)
  if (extension) return extension
  // handler 已返回封装好的 Response，直接透传；不得再包一层信封。
  for (const [pattern, handler] of routes) {
    const match = path.match(pattern)
    if (match) {
      // Each known route has an explicit method surface; never turn an unknown operation into success.
      const readOnly = /\/(planning-scenarios|input-schema|semantic-results|semantic-catalog|stats|referenced-by|lifecycle-logs|events|model-health|status|audit|classifications|integration|sessions)$/.test(path)
      const mutation = /\/(copy|precheck|commit|submit|withdraw|approve|reject|disable|enable|archive|restore|render|publish|resolve|applications|promote|resume|cancel|reexecute|replay|reproduce|import|preview|reset|start|stop|restart|test|rotate)$/.test(path)
      const allowed = readOnly ? ['GET'] : mutation ? ['POST'] : /\/(config|flows\/[^/]+)$/.test(path) ? ['GET', 'PUT'] : ['GET', 'POST', 'PUT', 'DELETE']
      if (!allowed.includes(method)) return new Response(JSON.stringify({ code: 'METHOD_NOT_ALLOWED', message: '该演示资源不支持此方法', data: null }), { status: 405, headers: { 'Content-Type': 'application/json' } })
      if (path.startsWith('/admin/approvals') && path !== '/admin/approvals' && !/^\/admin\/approvals\/[^/]+\/[^/]+$/.test(path)) return envelope404()
      return handler(match, method, body)
    }
  }
  // DEMO-01：未实现的接口显式失败，避免把「未接线」伪装成空列表或成功。
  console.warn('[mock] 未实现的接口', method, path)
  if (method === 'GET') return envelope404()
  return new Response(JSON.stringify({ code: 'METHOD_NOT_ALLOWED', message: `演示数据不支持该方法：${method} ${path}`, data: null }), {
    status: 405,
    headers: { 'Content-Type': 'application/json' },
  })
}

// ── Mock SSE 事件流（供调试预览/运行监控演示消费）──
export function mockRunEvents(status: string = 'SUCCEEDED'): { seq: string; type: string; data: unknown }[] {
  const frames: { seq: string; type: string; data: unknown }[] = [
    { seq: '1', type: 'NODE_STARTED', data: { nodeId: 'start', nodeDefId: 'start' } },
    { seq: '2', type: 'NODE_FINISHED', data: { nodeId: 'start', nodeDefId: 'start' } },
    { seq: '3', type: 'NODE_STARTED', data: { nodeId: 'llm', nodeDefId: 'llm' } },
    { seq: '4', type: 'CHUNK', data: { text: '正在分析轨道参数…' } },
    { seq: '5', type: 'CHUNK', data: { text: '正在检查载荷兼容性…' } },
    { seq: '6', type: 'NODE_FINISHED', data: { nodeId: 'llm', nodeDefId: 'llm' } },
    { seq: '7', type: 'NODE_STARTED', data: { nodeId: 'skill', nodeDefId: 'skill' } },
    { seq: '8', type: 'NODE_FINISHED', data: { nodeId: 'skill', nodeDefId: 'skill' } },
  ]
  // 末帧必须与运行当前状态一致：等待审批的运行不能回放成 SUCCEEDED。
  frames.push(
    status === 'WAITING_APPROVAL'
      ? { seq: '9', type: 'RUN_WAITING', data: { status } }
      : { seq: '9', type: 'RUN_FINISHED', data: { status } },
  )
  return frames
}
