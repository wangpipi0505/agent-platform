/**
 * A28/Mock：纯前端演示模式。
 * VITE_MOCK=true 时替换 transport + auth + SSE，全页面脱离后端可用。
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

// ── 空间 ──
const spaces = [
  { id: 'sp-001', code: 'satellite', name: '卫星本体', namespaceUri: 'https://example.org/satellite/', domain: '航天', status: 'ACTIVE', draftRev: 3, role: 'OWNER', classCount: 12, propertyCount: 34, relationCount: 8, operationCount: 3, guardCount: 2, assistantCount: 1, candidateCount: 1 },
  { id: 'sp-002', code: 'ground-station', name: '地面站', namespaceUri: 'https://example.org/ground/', domain: '测控', status: 'ACTIVE', draftRev: 1, role: 'EDITOR', classCount: 6, propertyCount: 15, relationCount: 3, operationCount: 1, guardCount: 1, assistantCount: 0, candidateCount: 0 },
  { id: 'sp-003', code: 'payload', name: '载荷', namespaceUri: 'https://example.org/payload/', domain: '载荷', status: 'ACTIVE', draftRev: 2, role: 'EDITOR', classCount: 8, propertyCount: 22, relationCount: 5, operationCount: 2, guardCount: 1, assistantCount: 1, candidateCount: 0 },
]

// ── 类 / 属性 / 关系 ──
const classes = [
  { id: 'cl-001', spaceId: 'sp-001', name: '卫星', parentId: null, description: '在轨运行的核心实体', deprecated: false },
  { id: 'cl-002', spaceId: 'sp-001', name: '轨道', parentId: 'cl-001', description: '运行轨道参数', deprecated: false },
  { id: 'cl-003', spaceId: 'sp-001', name: '载荷', parentId: 'cl-001', description: '星上有效载荷', deprecated: false },
  { id: 'cl-004', spaceId: 'sp-001', name: '通信载荷', parentId: 'cl-003', description: '', deprecated: false },
  { id: 'cl-005', spaceId: 'sp-001', name: '推进系统', parentId: 'cl-001', description: '', deprecated: false },
]
const properties = [
  { id: 'pr-001', spaceId: 'sp-001', domainClassId: 'cl-001', name: '轨道类型', rangeType: 'STRING', required: true, description: 'LEO/MEO/GEO' },
  { id: 'pr-002', spaceId: 'sp-001', domainClassId: 'cl-001', name: '发射日期', rangeType: 'DATE', required: false, description: '' },
  { id: 'pr-003', spaceId: 'sp-001', domainClassId: 'cl-001', name: '质量(kg)', rangeType: 'DECIMAL', required: false, description: '干重不含推进剂' },
  { id: 'pr-004', spaceId: 'sp-001', domainClassId: 'cl-001', name: '设计寿命', rangeType: 'INTEGER', required: false, description: '单位：年' },
  { id: 'pr-005', spaceId: 'sp-001', domainClassId: 'cl-003', name: '频段', rangeType: 'STRING', required: true, description: 'S/C/X/Ku/Ka' },
]
const relations = [
  { id: 'rl-001', spaceId: 'sp-001', fromClassId: 'cl-003', toClassId: 'cl-001', name: '搭载于', cardinality: 'N:1', inverseOf: '搭载', transitive: false, symmetric: false },
  { id: 'rl-002', spaceId: 'sp-001', fromClassId: 'cl-004', toClassId: 'cl-003', name: '属于', cardinality: '1:1', inverseOf: null, transitive: false, symmetric: false },
]

// ── 操作 / 规则 / 策略 ──
const operations = [
  { id: 'op-001', spaceId: 'sp-001', code: 'list_satellites', name: '列出卫星', kind: 'OBJECT_QUERY', maxRows: 100, timeoutMs: 3000, queryTemplate: 'PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\nSELECT ?name WHERE { ?s a <urn:卫星> ; rdfs:label ?n . BIND(?n AS ?name) }', strategyRef: null, precondition: '', description: '按名称列出所有在轨卫星', assistantTool: 'EDITOR' },
  { id: 'op-002', spaceId: 'sp-001', code: 'get_orbit', name: '查询轨道参数', kind: 'OBJECT_QUERY', maxRows: 50, timeoutMs: 5000, queryTemplate: 'SELECT ?p ?o WHERE { ?s <urn:orbit> ?orbit . ?orbit ?p ?o }', strategyRef: null, precondition: '需已知卫星标识', description: '', assistantTool: null },
  { id: 'op-003', spaceId: 'sp-001', code: 'check_payload_compat', name: '载荷兼容性检查', kind: 'RULE_CHECK', maxRows: 10, timeoutMs: 10000, queryTemplate: '', strategyRef: 'st-001', precondition: '需已知载荷类型与频段', description: '检查通信载荷与卫星平台的频段兼容性', assistantTool: null },
]
const rules = [
  { id: 'ru-001', spaceId: 'sp-001', applicableClassId: 'cl-001', expression: '质量 > 500 → 优先级 = 高', status: 'ACTIVE', failureMode: 'FAIL' },
  { id: 'ru-002', spaceId: 'sp-001', applicableClassId: 'cl-003', expression: '频段 = Ka → 优先级 = 高', status: 'ACTIVE', failureMode: 'FAIL' },
]
const strategies = [
  { id: 'st-001', spaceId: 'sp-001', code: 'default_scoring', name: '默认评分', scoringMode: 'RULE_BASED', description: '基于规则优先级的默认评分策略' },
]
const codebooks = [
  { id: 'cb-001', spaceId: 'sp-001', code: 'orbit_type', name: '轨道类型码表', values: [{ value: 'LEO', label: '低地球轨道' }, { value: 'MEO', label: '中地球轨道' }, { value: 'GEO', label: '地球同步轨道' }] },
  { id: 'cb-002', spaceId: 'sp-001', code: 'freq_band', name: '频段码表', values: [{ value: 'S', label: 'S 频段' }, { value: 'X', label: 'X 频段' }, { value: 'Ku', label: 'Ku 频段' }, { value: 'Ka', label: 'Ka 频段' }] },
]

// ── 知识 / 数据源 ──
const knowledge = [
  { id: 'kn-001', spaceId: 'sp-001', name: '卫星平台术语表', kind: 'GLOSSARY', status: 'ACTIVE' },
  { id: 'kn-002', spaceId: 'sp-001', name: '发射任务背景资料', kind: 'DOCUMENT', status: 'ACTIVE' },
  { id: 'kn-003', spaceId: 'sp-002', name: '地面站设备手册', kind: 'DOCUMENT', status: 'ACTIVE' },
]
const datasources = [
  { id: 'ds-001', spaceId: 'sp-001', name: '遥测主库', kind: 'POSTGRESQL', status: 'ACTIVE', spaceName: '卫星本体', snapshotCount: 3, lastSnapshotAt: '2026-10-24T10:00:00Z', mappingCount: 5, ownerName: '张三' },
  { id: 'ds-002', spaceId: 'sp-001', name: '任务文件系统', kind: 'S3', status: 'ACTIVE', spaceName: '卫星本体', snapshotCount: 1, lastSnapshotAt: '2026-10-23T08:00:00Z', mappingCount: 2, ownerName: '李四' },
  { id: 'ds-003', spaceId: 'sp-002', name: '测控数据库', kind: 'POSTGRESQL', status: 'ACTIVE', spaceName: '地面站', snapshotCount: 2, lastSnapshotAt: '2026-10-22T06:00:00Z', mappingCount: 3, ownerName: '张三' },
]

// ── 候选 / 发布 / 运行 ──
const candidates = [
  { id: 'cand-001', spaceId: 'sp-001', status: 'READY_TO_COMMIT', candidateSha256: 'a'.repeat(64), createdAt: '2026-10-24T08:00:00Z' },
  { id: 'cand-002', spaceId: 'sp-001', status: 'REVIEWING', candidateSha256: 'b'.repeat(64), createdAt: '2026-10-25T09:00:00Z' },
]
const releases = [
  { id: 'rel-001', spaceId: 'sp-001', versionNo: 1, candidateId: 'cand-001', status: 'READY', publishedAt: '2026-10-20T10:00:00Z', publishedBy: '演示用户' },
]
const runs = [
  { id: 'run-001', public_id: 'run-demo-001', app_code: 'satellite-assistant', status: 'SUCCEEDED', trigger_type: 'CHAT', created_at: '2026-10-25T08:00:00Z' },
  { id: 'run-002', public_id: 'run-demo-002', app_code: 'satellite-assistant', status: 'RUNNING', trigger_type: 'CHAT', created_at: '2026-10-25T10:00:00Z' },
]
const executions = [
  { id: 'ex-001', spaceId: 'sp-001', opCode: 'list_satellites', status: 'SUCCEEDED', caller: 'SERVICE', createdAt: '2026-10-24T12:00:00Z', elapsedMs: 234 },
  { id: 'ex-002', spaceId: 'sp-001', opCode: 'get_orbit', status: 'SUCCEEDED', caller: 'SERVICE', createdAt: '2026-10-24T12:01:00Z', elapsedMs: 156 },
  { id: 'ex-003', spaceId: 'sp-002', opCode: 'check_payload_compat', status: 'SUCCEEDED', caller: 'SERVICE', createdAt: '2026-10-24T14:00:00Z', elapsedMs: 892 },
]

// ── 路由表 ──
const routes: [RegExp, () => Response][] = [
  [/\/onto\/spaces/, () => envelope(spaces)],
  [/\/onto\/classes/, () => envelope(classes)],
  [/\/onto\/properties/, () => envelope(properties)],
  [/\/onto\/relations/, () => envelope(relations)],
  [/\/onto\/operations/, () => envelope(operations)],
  [/\/onto\/rules/, () => envelope(rules)],
  [/\/onto\/strategies/, () => envelope(strategies)],
  [/\/onto\/codebooks/, () => envelope(codebooks)],
  [/\/onto\/knowledge/, () => envelope(knowledge)],
  [/\/onto\/datasources/, () => envelope(datasources)],
  [/\/onto\/candidates/, () => envelope(candidates)],
  [/\/onto\/releases/, () => envelope(releases)],
  [/\/onto\/runs/, () => envelope(runs)],
  [/\/onto\/executions/, () => envelope(executions)],
]

// ── Mock transport ──
export async function mockTransport(
  url: RequestInfo | URL,
  _options?: RequestInit,
): Promise<Response> {
  const path = String(url).replace(/^.*?\/onto/, '/onto').split('?')[0]
  for (const [pattern, handler] of routes) {
    if (pattern.test(path)) return envelope(handler() === undefined ? [] : handler());
  }
  // 逐资源精确匹配
  for (const [pattern, handler] of routes) {
    if (pattern.test(path)) return handler();
  }
  return envelope404();
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
