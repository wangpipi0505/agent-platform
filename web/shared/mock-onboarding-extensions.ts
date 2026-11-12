import { mockDigest, mockExtensionState as state } from './mock-state-extensions'
type Row = Record<string, any>
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value))
const at = () => new Date().toISOString()
export function mockOnboardingRequest(path: string, method: string, body: Row | undefined, query: URLSearchParams, ctx: Row): Response | undefined {
  const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', message: '', data }), { headers: { 'Content-Type': 'application/json' } })
  const fail = (status: number, message: string) => new Response(JSON.stringify({ code: status === 409 ? 'CONFLICT' : status === 404 ? 'NOT_FOUND' : status === 405 ? 'METHOD_NOT_ALLOWED' : 'INVALID_ARGUMENT', message, data: null }), { status, headers: { 'Content-Type': 'application/json' } })
  const save = (data: unknown) => { ctx.persist(); return ok(data) }
  let m: RegExpMatchArray | null
  if ((m = path.match(/^\/admin\/debug-inputs(?:\/([^/]+))?(?:\/(promote))?$/))) {
    const rows: Row[] = ctx.debugInputs, row = m[1] ? rows.find(item => String(item.id) === m![1]) : undefined
    if (m[1] && !row) return fail(404, '命名输入不存在')
    if (method === 'GET') return ok(row ?? { items: rows.filter(item => (!query.get('appDraftId') || item.appDraftId === query.get('appDraftId')) && Number(item.id) > Number(query.get('afterId') ?? 0)).slice(0, Number(query.get('limit') ?? 20)), hasMore: false, nextAfterId: 0 })
    if (m[2]) {
      if (method !== 'POST') return fail(405, '仅接受POST')
      if (body?.revision !== undefined && body.revision !== row!.revision) return fail(409, '输入修订已变化')
      const version = body?.datasetVersion ?? ctx.nextId('dataset'), app = ctx.apps.find((app: Row) => app.draftId === row!.appDraftId)
      const cases = [{ id: body?.caseId ?? `case-${row!.id}`, caseId: body?.caseId ?? `case-${row!.id}`, name: body?.name ?? row!.name, inputs: clone(row!.inputs), expected: clone(body?.expected ?? {}), assertion: clone(body?.assertion ?? 'EXACT'), assertions: clone(body?.assertions ?? []), safety: body?.safety === true }]
      if (state.datasets[version]) return JSON.stringify(state.datasets[version]) === JSON.stringify(cases) ? ok({ datasetVersion: version, caseCount: 1 }) : fail(409, '数据集版本已冻结，请使用新版本编码')
      state.datasets[version] = cases
      ctx.evaluationDatasets.unshift({ id: version, name: body?.name ?? row!.name, version: 1, datasetVersion: version, appCode: app?.code ?? row!.appCode, cases, caseCount: 1, updatedAt: at() })
      return save({ datasetVersion: version, caseCount: 1 })
    }
    if (!m[1] && method === 'POST') { if (!body?.name || !body?.inputs) return fail(400, '名称和输入必填'); const appDraftId = query.get('appDraftId') ?? body.appDraftId, app = ctx.apps.find((item: Row) => item.draftId === appDraftId); const created = { id: Number(ctx.nextId('input').split('-').at(-1)), appDraftId, appCode: app?.code, name: body.name, inputs: clone(body.inputs), revision: 1, createdAt: at() }; rows.unshift(created); return save(created) }
    if (row && (method === 'PUT' || method === 'DELETE')) { const revision = Number(body?.revision ?? query.get('revision')); if (revision !== row.revision) return fail(409, '命名输入修订已变化'); if (method === 'DELETE') rows.splice(rows.indexOf(row), 1); else Object.assign(row, clone(body ?? {}), { revision: row.revision + 1 }); return save(method === 'DELETE' ? null : row) }
    return fail(405, '不支持该命名输入操作')
  }
  if ((m = path.match(/^\/admin\/onboarding-templates(?:\/([^/]+))?(?:\/(publish|disable)|\/versions\/(\d+)\/preflight)?$/))) {
    const rows: Row[] = ctx.demoOnboardingTemplates, row = m[1] ? rows.find(item => item.code === m![1]) : undefined
    if (m[1] && !row) return fail(404, '模板不存在')
    if (method === 'GET') return ok(row ?? rows.filter(item => (!query.get('q') || `${item.code} ${item.name}`.includes(query.get('q')!)) && (!query.get('kind') || item.kind === query.get('kind'))))
    if (method !== 'POST' && method !== 'PUT' && method !== 'DELETE') return fail(405, '不支持该方法')
    if (row && body?.expectedRev !== undefined && body.expectedRev !== row.currentRev) return fail(409, '模板修订已变化')
    if (m[4]) { if (!body?.document || !body?.connection) return fail(400, '请选择规格文档与连接引用'); row!.preflightPassed = true; return save({ documentSha256: mockDigest(body.document), operations: [{ operationId: 'query', method: 'GET', path: '/query', supported: true, reasons: [] }], warnings: [], checks: [{ result: 'PASS', detail: '演示规格与连接已核对' }], ready: true, passed: true, zeroCreated: true }) }
    if (m[3]) { if (m[3] === 'publish' && !row!.preflightPassed) return fail(409, '请先完成模板预检'); row!.status = m[3] === 'publish' ? 'PUBLISHED' : 'DISABLED'; row!.updatedAt = at(); return save(row) }
    if (!row) { if (!body?.code || !body?.name) return fail(400, '模板编码和名称必填'); const created = { ...clone(body), status: 'DRAFT', currentRev: 1, latest: { ...clone(body), templateRev: 1, revision: 1, versionNo: 1 }, revisions: [{ ...clone(body), templateRev: 1, revision: 1, versionNo: 1 }], createdAt: at(), updatedAt: at() }; rows.unshift(created); return save(created) }
    if (method === 'DELETE') { rows.splice(rows.indexOf(row), 1); return save(null) }
    Object.assign(row, clone(body ?? {}), { currentRev: row.currentRev + 1, status: 'DRAFT', updatedAt: at() }); row.latest = { ...clone(body ?? {}), templateRev: row.currentRev }; (row.revisions ??= []).push(row.latest); return save(row)
  }
  if ((m = path.match(/^\/admin\/onboarding-sessions(?:\/([^/]+))?(?:\/(preview|cancel)|\/steps\/([^/]+)\/execute)?$/))) {
    if (m[1] === 'templates') return method === 'GET' ? ok(ctx.demoOnboardingTemplates.filter((item: Row) => item.status === 'PUBLISHED' && item.kind === 'QUERY')) : fail(405, '只读目录')
    const row = m[1] ? state.sessions.find((item: Row) => item.sessionId === m![1]) : undefined
    if (m[1] && !row) return fail(404, '接入会话不存在')
    if (method === 'GET') return ok(row ?? { items: state.sessions })
    if (!row && method === 'POST') {
      const template = ctx.demoOnboardingTemplates.find((item: Row) => item.code === body?.templateCode && item.status === 'PUBLISHED'); if (!template) return fail(409, '模板未发布')
      const previous = state.sessions.find((item: Row) => item.requestId === body?.requestId); if (previous) return previous.appCode === body?.appCode ? ok(previous) : fail(409, '同一请求号不能用于不同应用')
      if (!body?.appCode || !body?.appName) return fail(400, '应用编码和名称必填')
      const plan = { fieldsBundle: clone(template.latest?.fieldsBundle ?? { fields: [], mappings: {}, returns: {} }), values: {}, selectedOperations: [] }
      const created = { sessionId: ctx.nextId('onboarding'), requestId: body.requestId, rev: 1, status: 'DRAFT', templateCode: template.code, templateRev: body.templateRev ?? template.currentRev, appCode: body.appCode, appName: body.appName, plan, planSha256: mockDigest(plan), steps: ['IMPORT_SKILLS', 'ASSEMBLE', 'VERIFY', 'PUBLISH'].map(step => ({ step, status: 'PENDING' })), choices: { models: ctx.modelEntries.map((item: Row) => ({ id: item.id, label: item.displayName })), connections: [], operations: [] } }; state.sessions.unshift(created); return save(created)
    }
    if (!row) return fail(405, '不支持该方法')
    if (!['POST', 'PUT'].includes(method)) return fail(405, '不支持该方法')
    if (body?.expectedRev !== row.rev) return fail(409, '会话修订已变化，请刷新')
    if (m[2] === 'preview') return ok({ ready: Boolean(row.plan.modelEntryId), missing: row.plan.modelEntryId ? [] : [{ code: 'MODEL_REQUIRED', message: '请选择模型' }], operations: row.plan.selectedOperations.map((operationId: string) => ({ operationId, supported: true })) })
    if (m[2] === 'cancel') { row.status = 'CANCELLED'; row.rev++; return save(row) }
    if (m[3]) {
      if (body?.planSha256 !== row.planSha256) return fail(409, '计划摘要已变化')
      const step = row.steps.find((item: Row) => item.step === m![3]); if (!step) return fail(404, '会话步骤不存在')
      const position = row.steps.indexOf(step); if (position > 0 && row.steps[position - 1].status !== 'SUCCEEDED') return fail(409, '请先完成前一步骤')
      if (row.status === 'CANCELLED') return fail(409, '会话已取消')
      if (step.status === 'SUCCEEDED') return ok(row)
      let app = ctx.apps.find((item: Row) => item.code === row.appCode)
      if (m[3] === 'IMPORT_SKILLS') { const drafts = row.plan.selectedOperations.map((operationId: string, index: number) => { const code = `${row.appCode}-${operationId}`.replace(/[^a-zA-Z0-9_-]/g, '-'); if (!ctx.skills.some((item: Row) => item.code === code)) ctx.skills.unshift({ code, name: operationId, status: 'PUBLISHED', currentVersion: 1, kind: 'HTTP', inputSchema: { type: 'object' }, outputSchema: { type: 'object' }, execution: { readonly: true, sideEffect: false } }); return { operationId, code, version: 1, skillVersionId: 8000 + index, sha256: mockDigest(operationId), status: 'PUBLISHED', workspacePath: `/skills/${code}` } }); step.receipt = { drafts, missing: [], sourceResolved: true } }
      if (m[3] === 'ASSEMBLE') { if (!app) { app = { code: row.appCode, name: row.appName, displayName: row.appName, type: 'CHAT', status: 'DRAFT', currentVersionNo: null, draftRev: 1, draftId: `draft-${row.appCode}-r1`, bizCategory: 'GENERAL', config: { modelBinding: { modelEntryId: row.plan.modelEntryId }, skillRefs: row.steps[0].receipt.drafts.map((item: Row) => ({ code: item.code, version: item.version })) }, createdAt: at(), updatedAt: at() }; ctx.apps.unshift(app) }; row.application = { appCode: app.code, draftRev: app.draftRev, status: app.status, currentVersionNo: app.currentVersionNo }; step.receipt = { ...row.application } }
      if (m[3] === 'VERIFY') { if (!app?.config?.modelBinding?.modelEntryId) return fail(409, '尚未选择模型'); step.receipt = { passed: true, checks: [{ result: 'PASS', detail: '演示资源引用已解析' }] } }
      if (m[3] === 'PUBLISH') { if (!app) return fail(409, '应用尚未组装'); app.currentVersionNo = Number(app.currentVersionNo ?? 0) + 1; app.status = 'PUBLISHED'; (state.versions[app.code] ??= []).unshift({ versionNo: app.currentVersionNo, snapshot: clone(app), snapshotDigest: mockDigest(app.config), status: 'PUBLISHED', publishedAt: at(), publishedBy: 'u-demo' }); row.status = 'PUBLISHED'; row.application = { appCode: app.code, draftRev: app.draftRev, status: app.status, currentVersionNo: app.currentVersionNo }; row.handover = { templateCode: row.templateCode, appCode: app.code, appVersion: app.currentVersionNo, launchPath: `/apps/${app.code}` }; step.receipt = row.handover }
      step.status = 'SUCCEEDED'; row.rev++; return save(row)
    }
    if (method === 'PUT') { if (['PUBLISHED', 'CANCELLED'].includes(row.status)) return fail(409, '终态会话不可修改'); if (!body?.plan?.fieldsBundle || !Array.isArray(body.plan.selectedOperations)) return fail(400, '会话计划不完整'); row.plan = clone(body.plan); row.planSha256 = mockDigest(row.plan); row.rev++; return save(row) }
    return fail(405, '不支持该方法')
  }
  if (path === '/admin/statistics' || path.startsWith('/admin/statistics/')) {
    if ((m = path.match(/^\/admin\/statistics\/runs\/([^/]+)$/))) { const row = ctx.runs.find((item: Row) => item.id === m![1]); return row ? ok({ ...row, errorSemantic: row.errorMessage ?? row.error?.code ?? 'FAILED', message: row.errorMessage ?? row.error?.message, ownerPath: `/admin/runs/${row.id}`, nodeSummary: { total: row.nodeCount ?? 0 }, error: row.error ?? row.errorMessage }) : fail(404, '运行不存在') }
    if (path === '/admin/statistics/snapshots') return method === 'GET' ? ok(Object.values(state.snapshots).map((item: any) => ({ snapshotId: item.snapshotId, filters: item.filters, createdAt: item.createdAt, active: true }))) : fail(405, '只读目录')
    if ((m = path.match(/^\/admin\/statistics\/snapshots\/([^/]+)$/))) return state.snapshots[m[1]] ? ok(state.snapshots[m[1]]) : fail(404, '快照不存在')
    if (path !== '/admin/statistics' && path !== '/admin/statistics/rebuild' && path !== '/admin/statistics/runs') return fail(404, '统计接口不存在')
    if (path === '/admin/statistics/rebuild' && method !== 'POST' || path !== '/admin/statistics/rebuild' && method !== 'GET') return fail(405, '不支持该方法')
    const filters = body ?? Object.fromEntries(query), rows = ctx.runs.filter((item: Row) => (!filters.appCode || item.appCode === filters.appCode) && (!filters.from || item.startedAt >= filters.from) && (!filters.to || item.startedAt < filters.to) && (!filters.callKind || (item.triggerType === 'DEBUG' ? 'DEBUG' : item.triggerType === 'EVALUATION' ? 'EVALUATION' : 'FORMAL') === filters.callKind))
    if (path.endsWith('/runs')) return ok(rows)
    const groups: Row[] = []
    for (const run of rows) { const date = run.startedAt.slice(0, 10), callKind = run.triggerType === 'DEBUG' ? 'DEBUG' : run.triggerType === 'EVALUATION' ? 'EVALUATION' : 'FORMAL'; let group = groups.find(item => item.date === date && item.appCode === run.appCode && item.callKind === callKind); if (!group) { group = { date, appCode: run.appCode, bizSystemCode: run.bizSystemCode ?? null, callKind, runs: 0, succeeded: 0, failed: 0, cancelled: 0, unfinished: 0, averageDurationMs: 0, p95DurationMs: 0, confirmationCount: 0, model: { calls: 0, knownTokens: 0, usageMissingCalls: 0 } }; groups.push(group) }; group.runs++; group.succeeded += Number(run.status === 'SUCCEEDED'); group.failed += Number(run.status === 'FAILED'); group.cancelled += Number(run.status === 'CANCELLED'); group.unfinished += Number(!['SUCCEEDED', 'FAILED', 'CANCELLED', 'REJECTED'].includes(run.status)); group.successRate = group.succeeded / group.runs; group.averageDurationMs = (group.averageDurationMs * (group.runs - 1) + Number(run.duration ?? 0)) / group.runs; group.p95DurationMs = Math.max(group.p95DurationMs, run.duration ?? 0); group.model.calls++; group.model.knownTokens += Number(run.tokenUsage?.input ?? 0) + Number(run.tokenUsage?.output ?? 0); group.model.totalTokens = group.model.knownTokens; group.confirmationCount += ctx.approvals.filter((item: Row) => item.runId === run.id && item.status !== 'PENDING').length }
    const skillGroups = ctx.skills.filter((item: Row) => item.executionCount > 0).map((item: Row) => ({ date: at().slice(0, 10), appCode: filters.appCode ?? '*', callKind: 'FORMAL', skillCode: item.code, skillVersionId: item.currentVersion, calls: item.executionCount, mockCalls: item.executionCount, failed: 0, successRate: item.successRate, p95DurationMs: item.averageDurationMs }))
    const page = Number(filters.page ?? 1), pageSize = Number(filters.pageSize ?? 20), report = { filters, page, pageSize, groups: groups.slice((page - 1) * pageSize, page * pageSize), groupsTotal: groups.length, skillGroups: skillGroups.slice((page - 1) * pageSize, page * pageSize), skillGroupsTotal: skillGroups.length, failures: rows.filter((item: Row) => item.status === 'FAILED').map((item: Row) => ({ runId: item.id, appCode: item.appCode, errorSemantic: item.errorMessage ?? 'FAILED', count: 1, href: `/admin/statistics/runs/${item.id}`, semantic: item.errorMessage ?? 'FAILED', message: item.errorMessage, detailPath: `/admin/statistics/runs/${item.id}`, ownerPath: `/admin/runs/${item.id}` })), consistency: 'DEMO_LOCAL_STATE', sourceSnapshots: { mock: at() }, createdAt: at(), snapshotId: ctx.nextId('statistics') }
    if (path.endsWith('/rebuild')) { state.snapshots[report.snapshotId] = clone(report); return save(report) }; return ok(report)
  }
  return undefined
}
