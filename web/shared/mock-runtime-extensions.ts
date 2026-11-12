import { mockDigest, mockExtensionState as state } from './mock-state-extensions'
import { validateMockSkillCalls } from './mock-skill-access'
type Row = Record<string, any>
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value))
const stamp = () => new Date().toISOString()
export function mockRuntimeRequest(path: string, method: string, body: Row | undefined, query: URLSearchParams, ctx: Row): Response | undefined {
  const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', message: '', data }), { headers: { 'Content-Type': 'application/json' } })
  const error = (status: number, message: string) => new Response(JSON.stringify({ code: status === 409 ? 'CONFLICT' : status === 404 ? 'NOT_FOUND' : status === 405 ? 'METHOD_NOT_ALLOWED' : 'INVALID_ARGUMENT', message, data: null }), { status, headers: { 'Content-Type': 'application/json' } })
  const save = (value: unknown) => { ctx.persist(); return ok(value) }
  const appBy = (code: string) => ctx.apps.find((app: Row) => app.code === code)
  let m: RegExpMatchArray | null
  function makeRun(app: Row, inputs: Row, debug = false) {
    const runId = ctx.nextId(debug ? 'dbg' : 'run'), definition = clone((debug ? ctx.flowStore[app.code]?.definition : undefined) ?? app.definition ?? { entry: 'start', nodes: [{ id: 'start', type: 'START' }, { id: 'answer', type: 'LLM' }, { id: 'end', type: 'END' }], edges: [{ from: 'start', to: 'answer' }, { from: 'answer', to: 'end' }] })
    const run: Row = { id: runId, runId, publicId: runId, appCode: app.code, appName: app.name, status: 'RUNNING', triggerType: debug ? 'DEBUG' : 'MANUAL', caller: 'u-demo', startedAt: stamp(), duration: 0, nodeCount: definition.nodes.length, tokenUsage: { input: 300, output: 120 }, versionNo: app.currentVersionNo ?? app.draftRev, versionRefs: { app: `${app.code}@${app.currentVersionNo ?? app.draftRev}`, skills: Object.fromEntries((app.config?.skillRefs ?? []).map((ref: Row) => [ref.code, ref.version])) }, definition, inputs: clone(inputs), nodes: definition.nodes.map((node: Row, i: number) => ({ id: node.id, nodeDefId: node.id, nodeInstanceId: `${runId}-${i + 1}`, label: node.name ?? node.id, type: node.type, status: 'PENDING', output: null })), events: [], eventsWatermark: 0, nextNodeId: definition.entry ?? definition.nodes[0]?.id, revision: 1, pauseSeq: 0, controls: { breakpoints: body?.breakpoints ?? [], mocks: body?.mocks ?? [] }, variables: clone(inputs), output: null, error: null }
    run.businessActorId = ctx.businessContext?.()?.currentActorId
    run.skillRefs = clone(app.config?.skillRefs ?? [])
    ctx.runs.unshift(run); if (debug) ctx.debugRuns.unshift(run)
    advance(run, debug && body?.mode === 'STEP' ? 'PAUSE' : 'RUN_TO_END')
    return run
  }
  function event(run: Row, type: string, payload: Row) { const seq = ++run.eventsWatermark; run.events.push({ seq, type, payload, data: payload, created_at: stamp() }) }
  function advance(run: Row, action: string) {
    run.paused = null; run.status = 'RUNNING'
    let count = 0
    while (run.nextNodeId && count++ < 100) {
      const definition = run.definition, node = definition.nodes.find((item: Row) => item.id === run.nextNodeId), row = run.nodes.find((item: Row) => item.nodeDefId === run.nextNodeId)
      if (!node || !row) { run.status = 'FAILED'; run.error = { message: '图引用了不存在的节点' }; break }
      if (action === 'PAUSE' || action === 'RUN_TO_BREAKPOINT' && run.controls.breakpoints.includes(node.id)) {
        run.status = 'WAITING_STEP'; row.status = 'WAITING_STEP'; run.pauseSeq++
        run.paused = { nodeInstanceId: row.nodeInstanceId, nodeDefId: node.id, pauseSeq: run.pauseSeq, allowedActions: ['NEXT_NODE', 'RUN_TO_BREAKPOINT', 'RUN_TO_END', 'CANCEL'] }
        event(run, 'WAITING_STEP', { ...run.paused }); break
      }
      row.status = 'RUNNING'; row.started_at = stamp(); event(run, 'NODE_STARTED', { nodeInstanceId: row.nodeInstanceId, nodeDefId: node.id })
      if (node.type === 'SKILL') {
        const context = ctx.businessContext?.()
        const actor = context?.actors?.find((item: Row) => item.id === run.businessActorId)
        const code = node.skillRef?.code ?? node.skillCode ?? (typeof node.skill === 'string' ? node.skill : node.skill?.code)
        const version = node.skillRef?.version ?? node.skillVersion ?? (typeof node.skill === 'object' ? node.skill?.version : undefined)
        const failures = validateMockSkillCalls({ calls: [{ skillCode: code, ...(version !== undefined ? { version } : {}) }], skills: ctx.skills, skillVersions: state.skillVersions, skillRefs: run.skillRefs, actor })
        if (failures.length) {
          row.status = 'FAILED'; row.error = { code: 'SKILL_ACCESS_DENIED', message: failures.join('；') }
          run.status = 'FAILED'; run.error = row.error
          event(run, 'NODE_FAILED', { nodeInstanceId: row.nodeInstanceId, nodeDefId: node.id, error: run.error })
          break
        }
      }
      const policy = node.businessPolicy
      const context = ctx.businessContext?.(), actor = context?.actors?.find((item: Row) => item.id === (node.type === 'SKILL' ? run.businessActorId : context.currentActorId))
      if (policy && actor) {
        const roles = actor.roles ?? actor.roleCodes ?? [], permissions = actor.permissions ?? actor.permissionCodes ?? []
        if (policy.roles?.length && !policy.roles.some((role: string) => roles.includes(role)) || policy.requiredPermissions?.some((permission: string) => !permissions.includes(permission))) { row.status = 'FAILED'; run.status = 'FAILED'; run.error = { code: 'BUSINESS_POLICY_DENIED', message: '当前业务身份不满足节点业务权限策略' }; event(run, 'NODE_FAILED', { nodeDefId: node.id, error: run.error }); break }
      }
      if (node.type === 'APPROVAL') {
        row.status = 'WAITING_APPROVAL'; run.status = 'WAITING_APPROVAL'; run.pauseSeq++; run.paused = { nodeInstanceId: row.nodeInstanceId, nodeDefId: node.id, pauseSeq: run.pauseSeq, allowedActions: ['CANCEL'] }
        if (!ctx.approvals.some((item: Row) => item.runId === run.runId && item.nodeId === node.id)) ctx.approvals.unshift({ id: ctx.nextId('approval'), runId: run.runId, nodeId: node.id, nodeDefId: node.id, nodeInstanceId: row.nodeInstanceId, nodeName: node.name ?? node.id, status: 'PENDING', requestedAt: stamp(), reason: '流程节点要求人工确认', parameterSnapshot: clone(run.inputs) })
        event(run, 'WAITING_APPROVAL', { nodeDefId: node.id, nodeInstanceId: row.nodeInstanceId }); break
      }
      row.status = 'SUCCEEDED'; row.ended_at = stamp(); row.duration = 120
      const mock = (run.controls.mocks ?? []).find((item: Row) => item.nodeDefId === node.id || item.nodeId === node.id)
      row.output = mock?.output ?? { nodeId: node.id, inputs: run.inputs, message: `${node.name ?? node.id}执行完成` }; run.variables[node.id] = row.output
      event(run, 'NODE_FINISHED', { nodeDefId: node.id, nodeInstanceId: row.nodeInstanceId, status: row.status })
      const edges = definition.edges.filter((edge: Row) => (edge.from ?? edge.source) === node.id)
      let next = node.next ?? edges[0]?.to ?? edges[0]?.target
      if (node.type === 'CONDITION') { const truth = Boolean(run.inputs.condition ?? run.inputs.approved ?? run.inputs.highRisk); next = truth ? node.nextTrue ?? edges.find((edge: Row) => /true|high|是/i.test(edge.label ?? ''))?.to ?? next : node.nextFalse ?? edges.find((edge: Row) => /false|low|否/i.test(edge.label ?? ''))?.to ?? edges.at(-1)?.to ?? next }
      run.nextNodeId = node.type === 'END' ? null : next
      if (action === 'NEXT_NODE' && run.nextNodeId) action = 'PAUSE'
    }
    if (count >= 100) { run.status = 'FAILED'; run.error = { message: '流程超过演示执行上限，请检查环路' } }
    if (run.status === 'RUNNING') { run.status = 'SUCCEEDED'; run.output = { result: run.variables, text: '按已保存流程执行完成' }; run.endedAt = stamp(); event(run, 'RUN_FINISHED', { status: run.status }) }
    run.duration = run.nodes.filter((node: Row) => node.status === 'SUCCEEDED').length * 120
  }
  if ((m = path.match(/^\/admin\/apps\/([^/]+)\/runs$/)) && method === 'POST') {
    const app = appBy(m[1]); if (!app) return error(404, '应用不存在')
    if (!app.currentVersionNo) return error(409, '请先发布应用版本')
    const versionNo = Number(body?.resolvedVersionNo ?? body?.versionNo ?? app.currentVersionNo)
    const snapshot = body?.resolvedSnapshot ?? state.versions[app.code]?.find((item: Row) => item.versionNo === versionNo)?.snapshot
    if (!Number.isInteger(versionNo) || versionNo < 1 || !snapshot) return error(404, '应用版本不存在')
    // The active pointer can move backwards; existence is determined by immutable history.
    return save(makeRun({ ...snapshot, currentVersionNo: versionNo }, body?.inputs ?? {}))
  }
  if (path === '/admin/debug-runs') {
    if (method === 'GET') return ok(ctx.debugRuns)
    if (method !== 'POST') return error(405, '仅支持GET/POST')
    const app = appBy(body?.appCode); if (!app) return error(404, '应用不存在')
    return save(makeRun(app, body?.inputs ?? {}, true))
  }
  if ((m = path.match(/^\/admin\/debug-runs\/([^/]+)(?:\/(status|controls|resume|cancel|variables|events)|\/node\/([^/]+)\/prompt)?$/))) {
    const run = ctx.debugRuns.find((item: Row) => item.id === m![1]); if (!run) return error(404, '调试运行不存在')
    if (m[2] === 'controls') {
      if (method === 'GET') return ok({ revision: run.revision ?? 1, controls: run.controls })
      if (method !== 'PUT') return error(405, '仅支持GET/PUT')
      if (body?.expectedRevision !== run.revision) return error(409, '控制修订已变化，请重新载入')
      run.controls = { breakpoints: clone(body?.breakpoints ?? []), mocks: clone(body?.mocks ?? []) }; run.revision++; return save({ revision: run.revision, controls: run.controls })
    }
    if (m[2] === 'resume') {
      if (method !== 'POST') return error(405, '仅接受POST')
      if (!run.paused || body?.pauseSeq !== run.paused.pauseSeq || String(body?.nodeInstanceId) !== String(run.paused.nodeInstanceId)) return error(409, '挂起节点或序列已变化，请刷新')
      if (!run.paused.allowedActions.includes(body?.action)) return error(409, '当前挂起点不允许此操作')
      advance(run, body?.action); return save(run)
    }
    if (m[2] === 'cancel') { if (method !== 'POST') return error(405, '仅接受POST'); run.status = 'CANCELLED'; run.paused = null; event(run, 'RUN_FINISHED', { status: run.status }); return save(run) }
    if (method !== 'GET') return error(405, '只读接口')
    if (m[2] === 'variables') return ok(run.variables)
    if (m[3]) return ok({ label: m[3], raw: JSON.stringify({ node: run.definition.nodes.find((node: Row) => node.id === m![3]), inputs: run.inputs }), status: run.status })
    if (m[2] === 'events') return ctx.sse((run.events ?? []).map((event: Row) => ({ seq: String(event.seq), type: event.type, data: event.payload })))
    return ok(run)
  }
  if ((m = path.match(/^\/admin\/runs\/([^/]+)(?:\/(events))?$/))) {
    const run = ctx.runs.find((item: Row) => item.id === m![1])
    if (run?.definition) return method !== 'GET' ? error(405, '只读接口') : m[2] ? ctx.sse(run.events.map((event: Row) => ({ seq: String(event.seq), type: event.type, data: event.payload }))) : ok(run)
  }
  if ((m = path.match(/^\/admin\/approvals\/([^/]+)\/([^/]+)$/)) && method === 'POST') {
    const run = ctx.runs.find((item: Row) => item.id === m![1] && item.definition)
    if (run) {
      const approval = ctx.approvals.find((item: Row) => item.runId === run.runId && (item.nodeId === m![2] || item.nodeInstanceId === m![2]))
      if (!approval) return error(404, '确认单不存在')
      if (approval.status !== 'PENDING') return error(409, '确认单已处理')
      const approved = body?.approved !== false && body?.decision !== 'REJECT'
      approval.status = approved ? 'APPROVED' : 'REJECTED'; approval.decidedAt = stamp()
      const node = run.definition.nodes.find((item: Row) => item.id === approval.nodeId), row = run.nodes.find((item: Row) => item.nodeDefId === node.id)
      row.status = approved ? 'SUCCEEDED' : 'REJECTED'; run.paused = null
      if (approved) { run.nextNodeId = node.next ?? run.definition.edges.find((edge: Row) => edge.from === node.id)?.to; advance(run, 'RUN_TO_END') } else { run.status = 'REJECTED'; event(run, 'RUN_FINISHED', { status: run.status }) }
      return save(approval)
    }
  }
  if (path === '/admin/runs' || path === '/admin/runs/page') {
    if (method !== 'GET') return undefined
    const rows = ctx.runs.filter((row: Row) => (!query.get('appCode') || row.appCode === query.get('appCode')) && (!query.get('status') || row.status === query.get('status')) && (!query.get('triggerType') || row.triggerType === query.get('triggerType')))
    const page = Number(query.get('page') ?? 1), size = Number(query.get('size') ?? 20)
    return ok(path.endsWith('/page') ? { items: rows.slice((page - 1) * size, page * size), total: rows.length, page, size } : rows)
  }
  if ((m = path.match(/^\/admin\/evaluation-datasets(?:\/([^/]+)\/([^/]+))?$/))) {
    if (method !== 'GET') return undefined
    if (m[2]) { const version = decodeURIComponent(m[2]); if (state.datasets[version]) return ok({ datasetVersion: version, cases: state.datasets[version] }); const dataset = ctx.evaluationDatasets.find((item: Row) => ctx.datasetVersionOf(item) === version); if (!dataset) return error(404, '数据集版本不存在'); const cases = Array.from({ length: dataset.caseCount }, (_, index) => ({ caseId: `${dataset.id}-${index}`, name: `用例${index + 1}`, inputs: { target: 'SAT-01' }, input: { target: 'SAT-01' }, expected: { visible: true }, assertions: [{ kind: 'EQUALS', path: 'visible', value: true }] })); return ok({ datasetVersion: version, cases }) }
    return ok({ items: ctx.evaluationDatasets.filter((item: Row) => !item.appCode || !query.get('appCode') || item.appCode === query.get('appCode')).map((item: Row) => ({ ...item, datasetVersion: ctx.datasetVersionOf(item), savedAt: item.updatedAt ?? stamp() })), hasMore: false, nextAfterVersion: null })
  }
  if ((m = path.match(/^\/admin\/evaluations(?:\/([^/]+))?(?:\/(resume))?$/))) {
    const row = m[1] ? ctx.evaluations.find((item: Row) => item.id === m![1]) : undefined
    if (m[1] && !row) return error(404, '评测报告不存在')
    if (method === 'GET') return ok(row ?? ctx.evaluations)
    if (method !== 'POST') return error(405, '仅支持GET/POST')
    if (row) return m[2] ? save(row) : error(405, '不支持该方法')
    const app = appBy(body?.appCode), precheck = state.prechecks[body?.appCode]
    if (!app || !precheck || body?.candidateSha256 !== precheck.candidateSha256 || body?.draftRev !== app.draftRev) return error(409, '评测候选已变化，请重新预检')
    if (!Array.isArray(body?.cases) || !body.cases.length) return error(400, '评测用例不能为空')
    const results = body.cases.map((item: Row, index: number) => {
      const inputs = item.inputs ?? item.input ?? {}, expected = item.expected ?? {}, simulated = { ...inputs, visible: true, answer: String(inputs.question ?? inputs.message ?? '演示响应') }
      const passed = !item.forceFailure && Object.entries(expected).every(([key, value]) => JSON.stringify(simulated[key]) === JSON.stringify(value))
      return { caseId: item.caseId ?? `case-${index + 1}`, name: item.name ?? `用例${index + 1}`, status: 'SUCCEEDED', passed, safety: Boolean(item.safety), inputs, expected, actual: simulated, output: simulated, error: null, regression: false, runId: ctx.nextId('eval-case') }
    })
    const passed = results.filter((item: Row) => item.passed).length, accepted = passed === results.length, id = ctx.nextId('ev')
    const report = { id, evalReportId: id, appCode: app.code, draftRev: body.draftRev, candidateSha256: body.candidateSha256, datasetVersion: body.datasetVersion, createdBy: 'u-demo', createdAt: stamp(), startedAt: stamp(), finishedAt: stamp(), status: accepted ? 'ACCEPTED' : 'REJECTED', evaluationEnvironment: 'DEMO', results, summary: { total: results.length, passed, failed: results.length - passed, passRate: passed / results.length, threshold: 1, regressionCount: 0, safetyPassed: results.filter((item: Row) => item.safety && item.passed).length, safetyFailed: results.filter((item: Row) => item.safety && !item.passed).length }, total: results.length, passed, failed: results.length - passed, passRate: passed / results.length, duration: results.length * 120 }
    ctx.evaluations.unshift(report); state.datasets[body.datasetVersion] = clone(body.cases); return save(report)
  }
  if (path.startsWith('/admin/operations/')) {
    const completed = ctx.runs.filter((run: Row) => ['SUCCEEDED', 'FAILED', 'CANCELLED', 'REJECTED'].includes(run.status)), failed = completed.filter((run: Row) => run.status === 'FAILED'), durations = completed.map((run: Row) => Number(run.duration ?? 0)).sort((a: number, b: number) => a - b)
    if (path === '/admin/operations/overview') return method === 'GET' ? ok({ checkedAt: stamp(), windowMinutes: Number(query.get('windowMinutes') ?? 1440), traceId: 'mock-operations', openAlerts: state.alerts.filter((item: Row) => item.status === 'OPEN').length, checks: [{ key: 'demo', label: '前端演示引擎', ok: true, detail: '本地演示状态可用', latencyMs: 1 }], runs: { from: ctx.runs.at(-1)?.startedAt, to: stamp(), runs: { completed: completed.length, succeeded: completed.filter((run: Row) => run.status === 'SUCCEEDED').length, failed: failed.length, failureRate: completed.length ? failed.length / completed.length : 0, averageDurationMs: durations.reduce((sum: number, value: number) => sum + value, 0) / (durations.length || 1), p95DurationMs: durations[Math.floor(durations.length * .95)] ?? 0 }, active: { active: ctx.runs.length - completed.length }, model: { calls: ctx.runs.length, failed: failed.length }, tools: { calls: ctx.skills.reduce((sum: number, item: Row) => sum + Number(item.executionCount ?? 0), 0), failed: failed.length, unknown: 0 }, topErrors: failed.map((item: Row) => ({ semantic: item.errorMessage ?? item.error?.message ?? '运行失败', count: 1 })), recentFailures: failed.slice(0, 5).map((item: Row) => ({ ...item, message: item.errorMessage ?? item.error?.message, endedAt: item.endedAt ?? item.startedAt })) }, retention: { snapshotDays: 30, alertDays: 90 } }) : error(405, '只读接口')
    if (path === '/admin/operations/alerts' && method === 'GET') return ok(state.alerts)
    if (path === '/admin/operations/alerts/evaluate' && method === 'POST') { if (failed.length && !state.alerts.some((item: Row) => item.key === 'failed-runs' && item.status !== 'RESOLVED')) state.alerts.unshift({ id: ctx.nextId('alert'), key: 'failed-runs', title: '发现失败运行', detail: `${failed.length}条运行失败，请查看运行详情`, severity: 'WARNING', status: 'OPEN', firstSeenAt: stamp(), lastSeenAt: stamp() }); return save(state.alerts) }
    if ((m = path.match(/^\/admin\/operations\/alerts\/([^/]+)\/ack$/)) && method === 'POST') { const alert = state.alerts.find((item: Row) => item.id === m![1]); if (!alert) return error(404, '告警不存在'); alert.status = 'ACKNOWLEDGED'; alert.acknowledgedAt = stamp(); return save(alert) }
    if (path === '/admin/operations/retention/purge' && method === 'POST') { if (query.get('confirm') !== 'PURGE') return error(400, '请输入PURGE确认'); const count = state.alerts.filter((item: Row) => item.status === 'RESOLVED').length; state.alerts = state.alerts.filter((item: Row) => item.status !== 'RESOLVED'); return save({ snapshotsDeleted: 0, alertsDeleted: count }) }
    return error(405, '不支持该运行中心操作')
  }
  return undefined
}
