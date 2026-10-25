/**
 * A28/Mock：纯前端演示模式。
 *
 * VITE_MOCK=true 时 createApi 的 transport 替换为本模块的 mockTransport——
 * 不发真实 HTTP，按路径前缀匹配返回 fixture 数据，全页面脱离后端可用。
 * 业务代码零感知：组件照常调用 useApi()/useAuth()，数据流不变。
 *
 * 退出方式：环境变量去掉 VITE_MOCK 或设为 false 重新构建即恢复真实接口。
 */

function ok(data: unknown): Response {
  return new Response(JSON.stringify({ code: 'OK', message: '', data }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
function notFound(): Response {
  return new Response(JSON.stringify({ code: 'NOT_FOUND', message: '记录不存在', data: null }), {
    status: 404,
    headers: { 'Content-Type': 'application/json' },
  });
}

const spaces = [
  { id: 'sp-001', code: 'satellite', name: '卫星本体', namespaceUri: 'https://example.org/satellite/', domain: '航天', status: 'ACTIVE', draftRev: 3, role: 'OWNER', classCount: 12, propertyCount: 34, relationCount: 8, operationCount: 3, guardCount: 2, assistantCount: 1, candidateCount: 1 },
  { id: 'sp-002', code: 'ground-station', name: '地面站', namespaceUri: 'https://example.org/ground/', domain: '测控', status: 'ACTIVE', draftRev: 1, role: 'EDITOR', classCount: 6, propertyCount: 15, relationCount: 3, operationCount: 1, guardCount: 1, assistantCount: 0, candidateCount: 0 },
];
const classes = [
  { id: 'cl-001', spaceId: 'sp-001', name: '卫星', parentId: null, description: '在轨运行的核心实体', deprecated: false },
  { id: 'cl-002', spaceId: 'sp-001', name: '轨道', parentId: 'cl-001', description: '', deprecated: false },
  { id: 'cl-003', spaceId: 'sp-001', name: '载荷', parentId: 'cl-001', description: '星上有效载荷', deprecated: false },
];
const properties = [
  { id: 'pr-001', spaceId: 'sp-001', domainClassId: 'cl-001', name: '轨道类型', rangeType: 'STRING', required: true },
  { id: 'pr-002', spaceId: 'sp-001', domainClassId: 'cl-001', name: '发射日期', rangeType: 'DATE', required: false },
];
const relations = [
  { id: 'rl-001', spaceId: 'sp-001', fromClassId: 'cl-003', toClassId: 'cl-001', name: '搭载于', cardinality: 'N:1' },
];
const operations = [
  { id: 'op-001', spaceId: 'sp-001', code: 'list_satellites', name: '列出卫星', kind: 'OBJECT_QUERY', maxRows: 100, timeoutMs: 3000, queryTemplate: 'SELECT ?n WHERE { ?s <urn:name> ?n }' },
];
const rules = [
  { id: 'ru-001', spaceId: 'sp-001', applicableClassId: 'cl-001', expression: '质量 > 500 → 优先级 = 高', status: 'ACTIVE', failureMode: 'FAIL' },
];
const strategies = [
  { id: 'st-001', spaceId: 'sp-001', code: 'default_scoring', name: '默认评分', scoringMode: 'RULE_BASED' },
];
const codebooks = [
  { id: 'cb-001', spaceId: 'sp-001', code: 'orbit_type', name: '轨道类型码表' },
];
const knowledge = [
  { id: 'kn-001', spaceId: 'sp-001', name: '卫星平台术语表', kind: 'GLOSSARY', status: 'ACTIVE' },
];
const datasources = [
  { id: 'ds-001', spaceId: 'sp-001', name: '遥测主库', kind: 'POSTGRESQL', status: 'ACTIVE', spaceName: '卫星本体', snapshotCount: 3, lastSnapshotAt: '2026-10-24T10:00:00Z', mappingCount: 5, ownerName: '演示用户' },
];
const candidates = [
  { id: 'cand-001', spaceId: 'sp-001', status: 'READY_TO_COMMIT', candidateSha256: 'a'.repeat(64), createdAt: '2026-10-24T08:00:00Z' },
  { id: 'cand-002', spaceId: 'sp-001', status: 'REVIEWING', candidateSha256: 'b'.repeat(64), createdAt: '2026-10-25T09:00:00Z' },
];
const releases = [
  { id: 'rel-001', spaceId: 'sp-001', versionNo: 1, candidateId: 'cand-001', status: 'READY', publishedAt: '2026-10-20T10:00:00Z', publishedBy: '演示用户' },
];
const runs = [
  { id: 'run-001', public_id: 'run-demo-001', app_code: 'satellite-assistant', status: 'SUCCEEDED', trigger_type: 'CHAT' },
];
const executions = [
  { id: 'ex-001', spaceId: 'sp-001', opCode: 'list_satellites', status: 'SUCCEEDED', caller: 'SERVICE', createdAt: '2026-10-24T12:00:00Z', elapsedMs: 234 },
];

const routes: [RegExp, () => Response][] = [
  [/\/onto\/spaces/, () => ok(spaces)],
  [/\/onto\/classes/, () => ok(classes)],
  [/\/onto\/properties/, () => ok(properties)],
  [/\/onto\/relations/, () => ok(relations)],
  [/\/onto\/operations/, () => ok(operations)],
  [/\/onto\/rules/, () => ok(rules)],
  [/\/onto\/strategies/, () => ok(strategies)],
  [/\/onto\/codebooks/, () => ok(codebooks)],
  [/\/onto\/knowledge/, () => ok(knowledge)],
  [/\/onto\/datasources/, () => ok(datasources)],
  [/\/onto\/candidates/, () => ok(candidates)],
  [/\/onto\/releases/, () => ok(releases)],
  [/\/onto\/runs/, () => ok(runs)],
  [/\/onto\/executions/, () => ok(executions)],
];

export async function mockTransport(
  url: RequestInfo | URL,
  _options?: RequestInit,
): Promise<Response> {
  const path = String(url).replace(/^.*?\/onto/, '/onto').split('?')[0];
  for (const [pattern, handler] of routes) {
    if (pattern.test(path)) return handler();
  }
  return notFound();
}
