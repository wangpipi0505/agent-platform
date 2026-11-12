/** Explicit, writable demo resources. These handlers are loaded only by mock-transport. */
import businessSeedText from './business-demo.seed.json?raw'
import { mockConfigurationRequest, normalizeMockConfiguration } from './mock-config-releases'
type Row = Record<string, any>
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value))
const at = () => new Date().toISOString()
export const mockExtensionState: Row = { versions: {}, skillVersions: {}, experiences: {}, sessions: [], alerts: [], prechecks: {}, datasets: {}, snapshots: {} }
export function restoreMockExtensions(value: unknown) {
  if (value && typeof value === 'object') Object.assign(mockExtensionState, value)
}
// A deterministic demo fingerprint changes with candidate content; it is not a production cryptographic proof.
export function mockDigest(value: unknown) {
  const text = JSON.stringify(value) ?? 'null'; let hash = 2166136261
  for (let i = 0; i < text.length; i++) hash = Math.imul(hash ^ text.charCodeAt(i), 16777619)
  return (hash >>> 0).toString(16).padStart(8, '0').repeat(8)
}
const categories: Row[] = JSON.parse(businessSeedText).classifications
function category(value: string) {
  if (categories.some(row => row.code === value)) return value
  return /用户|治理/.test(value) ? 'USER_GOVERNANCE' : /数据|处理/.test(value) ? 'DATA_PROCESSING' : value ? 'SPACE_OBSERVATION' : 'GENERAL'
}
export function normalizeMockResources(ctx: Row) {
  for (const app of ctx.apps) {
    app.bizCategory = category(app.bizCategory)
    if (app.code === 'user-governance-assistant') app.config.responseStyle ??= 'detailed'
    // The mock historical catalog is frozen at first load, before any draft write.
    if (!mockExtensionState.versions[app.code] && app.currentVersionNo) mockExtensionState.versions[app.code] = Array.from({ length: Number(app.currentVersionNo) }, (_, index) => {
      const versionNo = Number(app.currentVersionNo) - index, snapshot = copy(app)
      snapshot.config = { ...snapshot.config, responseStyle: index ? 'compact' : 'detailed' }
      if (index) snapshot.description = `${snapshot.description}（演示历史 v${versionNo}）`
      return { versionNo, status: index ? 'SUPERSEDED' : 'PUBLISHED', snapshot, snapshotDigest: mockDigest(snapshot), publishedAt: app.updatedAt, publishedBy: 'u-demo', note: index ? '演示历史版本：简洁回答' : '当前演示版本：详细回答' }
    })
  }
  for (const skill of ctx.skills) {
    skill.bizCategories ??= [skill.code.startsWith('user-') ? 'USER_GOVERNANCE' : 'SPACE_OBSERVATION']
    skill.bizCategories = skill.bizCategories.map(category)
    skill.businessSystemCode ??= skill.bizCategories.includes('USER_GOVERNANCE') ? 'USER_CENTER' : 'SPACE'
    skill.accessPolicy ??= { visibility: 'PUBLIC', viewRoles: ['ADMIN', 'EDITOR', 'OPERATOR', 'APPROVER'], configureRoles: ['SPACE_OPERATOR', 'UM_OPERATOR'], invokeRoles: ['SPACE_OPERATOR', 'UM_OPERATOR'] }
  }
  normalizeMockConfiguration(ctx, mockExtensionState, mockDigest)
}
export function mockStateRequest(path: string, method: string, body: Row | undefined, query: URLSearchParams, ctx: Row): Response | undefined {
  const state = mockExtensionState
  const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', message: '', data }), { headers: { 'Content-Type': 'application/json' } })
  const fail = (status: number, message: string) => new Response(JSON.stringify({ code: status === 404 ? 'NOT_FOUND' : status === 405 ? 'METHOD_NOT_ALLOWED' : status === 409 ? 'CONFLICT' : 'INVALID_ARGUMENT', message, data: null }), { status, headers: { 'Content-Type': 'application/json' } })
  const write = (data: unknown, action = 'DEMO_UPDATE', target = path) => { ctx.audit(action, 'RESOURCE', target, action); ctx.persist(); return ok(data) }
  const find = (rows: Row[], id: string) => rows.find(row => String(row.id ?? row.code ?? row.profileId ?? row.bindingId ?? row.releaseId ?? row.sessionId) === id)
  let m: RegExpMatchArray | null
  normalizeMockResources(ctx)
  if (path === '/admin/classifications') return method === 'GET' ? ok(categories) : fail(405, '分类目录只读')
  if ((m = path.match(/^\/admin\/apps\/([^/]+)\/versions\/(\d+)(?:\/(restore|activate))?$/))) {
    const app = find(ctx.apps, m[1]); if (!app) return fail(404, '应用不存在')
    const version = (state.versions[app.code] ?? []).find((row: Row) => row.versionNo === Number(m![2]))
    if (!version) return fail(404, '发布版本不存在')
    if (!m[3]) return method === 'GET' ? ok({ ...version, appCode: app.code, config: copy(version.snapshot.config), definition: copy(version.snapshot.definition ?? null) }) : fail(405, '发布快照只读')
    if (method !== 'POST') return fail(405, '版本切换仅接受POST')
    if (body?.draftRev !== undefined && body.draftRev !== app.draftRev) return fail(409, '应用草稿修订已变化，请刷新')
    if (m[3] === 'activate') {
      app.currentVersionNo = version.versionNo; app.status = 'PUBLISHED'; app.updatedAt = at()
      state.versions[app.code].forEach((row: Row) => row.status = row.versionNo === version.versionNo ? 'PUBLISHED' : 'SUPERSEDED')
      return write({ ...app, activatedVersionNo: version.versionNo }, 'APP_VERSION_ACTIVATE', app.code)
    }
    const currentVersionNo = app.currentVersionNo, draftRev = Number(app.draftRev) + 1
    Object.assign(app, copy(version.snapshot), { code: app.code, currentVersionNo, draftRev, draftId: `draft-${app.code}-r${draftRev}`, status: 'DRAFT', updatedAt: at() })
    if (app.type === 'FLOW') ctx.flowStore[app.code] = { definition: copy(app.definition), layout: copy(app.layout ?? {}), draftRev }
    delete state.prechecks[app.code]
    return write({ ...app, restoredFromVersion: version.versionNo }, 'APP_VERSION_RESTORE', app.code)
  }
  if (path === '/admin/apps' && method === 'GET') {
    return ok(ctx.apps.filter((app: Row) => (!query.get('type') || app.type === query.get('type')) && (!query.get('status') || app.status === query.get('status')) && (!query.get('bizCategory') || app.bizCategory === query.get('bizCategory')) && (!query.get('q') && !query.get('keyword') || `${app.name} ${app.code} ${app.description}`.toLowerCase().includes((query.get('q') ?? query.get('keyword') ?? '').toLowerCase())) && (!query.get('code') || app.code.includes(query.get('code'))) && (!query.get('domain') || app.domain === query.get('domain')) && (!query.get('tag') || app.tags?.includes(query.get('tag')))))
  }
  if (path === '/admin/apps' && method === 'POST') {
    if (!body?.code || !body?.name) return fail(400, '应用编码和名称必填')
    if (find(ctx.apps, body.code)) return fail(409, '应用编码已存在')
    const app: Row = { ...copy(body), displayName: body.name, status: 'DRAFT', type: body.type ?? 'CHAT', currentVersionNo: null, draftRev: 1, draftId: `draft-${body.code}-r1`, bizCategory: category(body.bizCategory ?? ''), createdAt: at(), updatedAt: at(), config: { modelBinding: { modelEntryId: ctx.modelEntries[0]?.id }, skillRefs: [], ...body.config } }
    if (app.type === 'FLOW') Object.assign(app, { definition: { definitionVersion: '1.0', trigger: 'TASK', entry: 'start', inputs: [], outputs: [], joins: [], nodes: [{ id: 'start', type: 'START', name: '开始' }, { id: 'end', type: 'END', name: '结束' }], edges: [{ from: 'start', to: 'end' }], executionRules: { parallel: false, maxParallel: 1, cyclesForbidden: true } }, layout: {} })
    ctx.apps.unshift(app); return write(app, 'APP_CREATE', app.code)
  }
  if ((m = path.match(/^\/admin\/apps\/([^/]+)\/(lifecycle-logs|versions(?:\/diff)?)$/))) {
    const app = find(ctx.apps, m[1]); if (!app) return fail(404, '应用不存在')
    if (method !== 'GET') return fail(405, '版本目录只读')
    const versions: Row[] = state.versions[app.code] ??= Array.from({ length: Number(app.currentVersionNo ?? 0) }, (_, i) => ({ versionNo: Number(app.currentVersionNo) - i, status: i ? 'SUPERSEDED' : 'PUBLISHED', snapshot: copy(app), snapshotDigest: mockDigest([app.code, i, app.config]), publishedAt: app.updatedAt, publishedBy: 'u-demo' }))
    if (m[2] === 'lifecycle-logs') return ok(ctx.auditLogs.filter((row: Row) => row.targetId === app.code).map((row: Row) => ({ ...row, action: row.action, operator: row.actor, reason: row.detail })))
    if (m[2] === 'versions/diff') {
      const from = versions.find(row => row.versionNo === Number(query.get('fromVersion'))), to = versions.find(row => row.versionNo === Number(query.get('toVersion')))
      if (!from || !to) return fail(404, '发布版本不存在')
      const changes = Object.keys({ ...from.snapshot, ...to.snapshot }).filter(key => JSON.stringify(from.snapshot[key]) !== JSON.stringify(to.snapshot[key])).map(key => ({ operation: from.snapshot[key] === undefined ? 'ADD' : to.snapshot[key] === undefined ? 'REMOVE' : 'REPLACE', path: key, field: key, before: from.snapshot[key], after: to.snapshot[key] }))
      return ok({ fromVersion: from.versionNo, toVersion: to.versionNo, fromSha256: from.snapshotDigest, toSha256: to.snapshotDigest, changes, differences: changes, from: from.snapshot, to: to.snapshot })
    }
    return ok(versions)
  }
  if ((m = path.match(/^\/admin\/apps\/([^/]+)\/publish\/(precheck|commit)$/))) {
    if (method !== 'POST') return fail(405, '发布操作仅接受 POST')
    const app = find(ctx.apps, m[1]); if (!app) return fail(404, '应用不存在')
    const digest = mockDigest({ config: app.config, definition: app.definition, draftRev: app.draftRev })
    if (m[2] === 'precheck') {
      const report = ctx.evaluations.find((row: Row) => row.appCode === app.code && row.draftRev === app.draftRev && row.candidateSha256 === digest && row.status === 'ACCEPTED')
      const checks = [{ group: '候选配置', result: app.config?.modelBinding?.modelEntryId ? 'PASS' : 'ERROR', detail: app.config?.modelBinding?.modelEntryId ? '模型已绑定' : '请先选择模型' }, { group: '评测门禁', result: report ? 'PASS' : 'ERROR', detail: report ? '评测报告与当前候选匹配' : '请运行当前候选评测后重新预检' }]
      const precheck = { precheckId: ctx.nextId('precheck'), appCode: app.code, draftRev: app.draftRev, candidateSha256: digest, passed: checks.every(row => row.result === 'PASS'), gateMode: 'BLOCKING', publicationTarget: 'NON_PRODUCTION', evaluationEnvironment: 'DEMO', environmentConfirmationRequired: false, report: { checks, warnings: [] }, ...(report ? { evalReportId: report.id, evaluationBinding: { evalReportId: report.id, candidateSha256: digest, evaluatedCandidateSha256: digest, draftRev: app.draftRev, matchedBy: 'SHA256' } } : {}) }
      state.prechecks[app.code] = precheck; return write(precheck, 'APP_PRECHECK', app.code)
    }
    const precheck = state.prechecks[app.code]
    if (!precheck?.passed || precheck.draftRev !== app.draftRev || precheck.candidateSha256 !== digest || body?.precheckId !== precheck.precheckId || body?.candidateSha256 !== digest || body?.draftRev !== app.draftRev) return fail(409, '当前候选未通过评测并绑定预检，请重新预检')
    state.versions[app.code] ??= Array.from({ length: Number(app.currentVersionNo ?? 0) }, (_, i) => ({ versionNo: Number(app.currentVersionNo) - i, status: 'SUPERSEDED', snapshot: copy(app), snapshotDigest: mockDigest([app.code, i]), publishedAt: app.updatedAt, publishedBy: 'u-demo' }))
    state.versions[app.code].forEach((row: Row) => row.status = 'SUPERSEDED')
    app.currentVersionNo = Math.max(0, ...state.versions[app.code].map((row: Row) => Number(row.versionNo))) + 1; app.status = 'PUBLISHED'; app.updatedAt = at()
    state.versions[app.code].unshift({ versionNo: app.currentVersionNo, status: 'PUBLISHED', snapshot: copy(app), config: copy(app.config), definition: copy(app.definition ?? null), snapshotDigest: digest, candidateSha256: digest, publishedAt: at(), publishedBy: 'u-demo' })
    delete state.prechecks[app.code]; return write(app, 'APP_PUBLISH', app.code)
  }
  if ((m = path.match(/^\/admin\/(model-providers|model-entries)(?:\/([^/]+))?(?:\/(credentials\/rotate|disable|enable|quota|test|metering))?$/))) {
    const rows: Row[] = m[1] === 'model-providers' ? ctx.modelProviders : ctx.modelEntries
    const row = m[2] ? find(rows, m[2]) : undefined
    if (m[2] && !row) return fail(404, '模型资源不存在')
    if (!m[2]) {
      if (method === 'GET') return ok(rows)
      if (method !== 'POST') return fail(405, '不支持该方法')
      if (!body?.[m[1] === 'model-providers' ? 'code' : 'modelName'] && !body?.modelId) return fail(400, '模型编码必填')
      if (rows.some(item => m![1] === 'model-providers' ? item.code === body?.code : item.providerId === body?.providerId && (item.modelId ?? item.modelName) === (body?.modelId ?? body?.modelName))) return fail(409, '模型资源已存在')
      const created = { ...copy(body), id: ctx.nextId(m[1] === 'model-providers' ? 'mp' : 'me'), modelId: body?.modelId ?? body?.modelName, status: 'ACTIVE', credentialStatus: body?.secretRef ? 'SET' : 'UNSET', updatedAt: at() }; rows.unshift(created); return write(created)
    }
    if (m[3] === 'test') return method === 'POST' ? ok({ id: row!.id, status: 'HEALTHY', ok: true, model: row!.modelId ?? row!.name, checks: [{ key: 'demo-contract', status: 'PASS', message: '模型契约检查通过' }], success: true, reachable: true, latencyMs: 42, message: '连接检查通过', checkedAt: at() }) : fail(405, '仅接受POST')
    if (m[3] === 'metering' && method === 'GET') return ok(row!.metering ?? {})
    if (m[3] && ['POST', 'PUT'].includes(method)) { if (m[3] === 'disable' || m[3] === 'enable') row!.status = m[3] === 'disable' ? 'DISABLED' : 'ACTIVE'; else if (m[3] === 'credentials/rotate') { if (!body?.secretRef) return fail(400, 'secretRef必填'); row!.secretRef = body.secretRef; row!.credentialStatus = 'SET' } else row![m[3]] = copy(body ?? {}); row!.updatedAt = at(); return write(row) }
    if (!m[3] && method === 'GET') return ok(row)
    if (!m[3] && method === 'PUT') { Object.assign(row!, copy(body ?? {}), { id: row!.id, updatedAt: at() }); return write(row) }
    if (!m[3] && method === 'DELETE') { rows.splice(rows.indexOf(row!), 1); return write(null) }
    return fail(405, '不支持该方法')
  }
  if ((m = path.match(/^\/admin\/(skills|prompt-templates)(?:\/([^/]+))?(?:\/(versions|drafts)(?:\/(\d+))?(?:\/(publish|render|acceptance|connection-test|service-probe))?|\/(referenced-by|disable|draft))?$/)) && !['semantic-catalog', 'import-openapi'].includes(m[2])) {
    const isSkill = m[1] === 'skills', rows: Row[] = isSkill ? ctx.skills : ctx.promptTemplates
    const row = m[2] ? find(rows, m[2]) : undefined
    const versions = (resource: Row): Row[] => {
      if (!isSkill) { const list = ctx.templateVersionSets[resource.code] ??= [{ ...copy(resource), version: 1, versionNo: 1 }]; for (const version of list) version.versionNo ??= version.version; return list }
      return state.skillVersions[resource.code] ??= [{ ...copy(resource), version: resource.currentVersion ?? 1, versionNo: resource.currentVersion ?? 1, sha256: mockDigest(resource), definition: { ...copy(resource), execution: resource.execution ?? { readonly: resource.readonly !== false, sideEffect: resource.sideEffect === true, idempotent: true }, inputSchema: resource.inputSchema ?? { type: 'object', properties: { target: { type: 'string' } } }, outputSchema: resource.outputSchema ?? { type: 'object' } } }]
    }
    if (!m[2]) {
      if (method === 'GET') {
        const roles: string[] = ctx.actorRoles?.() ?? ['ADMIN', 'EDITOR', 'APPROVER']
        return ok(rows.filter(item => {
          if (!isSkill) return true
          const policy = item.accessPolicy ?? {}, permission = query.get('purpose') === 'configure' ? 'configureRoles' : 'viewRoles'
          const app = query.get('appCode') ? find(ctx.apps, query.get('appCode')!) : undefined
          const businessActor = ctx.businessContext?.()
          return (!query.get('bizCategory') || item.bizCategories?.includes(query.get('bizCategory'))) && (policy.visibility === 'PUBLIC' && permission === 'viewRoles' || (policy[permission] ?? []).some((role: string) => roles.includes(role))) && (!app || item.bizCategories?.includes('GENERAL') || item.bizCategories?.includes(app.bizCategory)) && (!businessActor?.businessSystemCode || !item.businessSystemCode || item.businessSystemCode === businessActor.businessSystemCode || item.bizCategories?.includes('GENERAL') || roles.includes('ADMIN'))
        }))
      }
      if (method !== 'POST') return fail(405, '不支持该方法')
      if (!body?.code || !body?.name) return fail(400, '编码和名称必填')
      if (find(rows, body.code)) return fail(409, '资源编码已存在')
      const created = { ...copy(body), status: 'DRAFT', currentVersion: 1, kind: body.kind ?? body.type ?? 'HTTP', updatedAt: at() }; rows.unshift(created); normalizeMockResources(ctx); versions(created); return write(created)
    }
    if (!row) return fail(404, '资源不存在')
    const list = versions(row), version = m[4] ? list.find(item => Number(item.versionNo ?? item.version) === Number(m![4])) : undefined
    if (m[4] && !version) return fail(404, '资源版本不存在')
    if (m[6] === 'referenced-by') return method === 'GET' ? ok(ctx.apps.filter((app: Row) => app.config?.skillRefs?.some((ref: Row) => ref.code === row.code)).map((app: Row) => ({ appCode: app.code, appName: app.name, code: app.code, name: app.name, draftRev: app.draftRev, version: app.config.skillRefs.find((ref: Row) => ref.code === row.code)?.version }))) : fail(405, '只读接口')
    if (m[5] === 'render') {
      if (method !== 'POST') return fail(405, '仅接受POST')
      const text = String(version!.content ?? row.content ?? '').replace(/\{\{\s*([^{}]+?)\s*\}\}/g, (_all, key: string) => String(body?.variables?.[key] ?? `{{${key}}}`)); return ok({ rendered: text, content: text })
    }
    if (m[5]) {
      if (method !== 'POST') return fail(405, '仅接受POST')
      if (m[5] === 'publish') { version!.status = 'PUBLISHED'; version!.publishedAt = at(); row.currentVersion = Number(m[4]); row.status = 'PUBLISHED'; return write({ ...version, code: row.code }, 'RESOURCE_PUBLISH') }
      if (m[5] === 'service-probe') return ok({ status: 'PASS', reachable: true, success: true, latencyMs: 42, message: '演示服务检查通过', checkedAt: at() })
      return ok({ code: row.code, version: Number(m[4]), status: 'ACCEPTED', success: true, passed: true, controlledCallId: 9001, results: (body?.cases ?? []).map((item: Row, index: number) => ({ ...item, index, passed: true, status: 'SUCCEEDED' })), message: '演示契约检查通过' })
    }
    if (m[3]) return method === 'GET' ? ok(m[4] ? version : list) : fail(405, '版本目录只读')
    if (m[6]) { if (method !== 'POST') return fail(405, '仅接受POST'); if (m[6] === 'draft') { const next = Math.max(...list.map(item => Number(item.versionNo ?? item.version))) + 1; list.unshift({ ...copy(list[0]), version: next, versionNo: next, status: 'DRAFT' }); row.currentVersion = next; row.status = 'DRAFT' } else row.status = 'DISABLED'; return write(row) }
    if (method === 'GET') return ok({ ...row, versions: list })
    if (method === 'PUT') {
      let draft = list.find(item => item.status === 'DRAFT')
      if (!draft) { const next = Math.max(...list.map(item => Number(item.versionNo ?? item.version))) + 1; draft = { ...copy(list[0]), version: next, versionNo: next, status: 'DRAFT' }; list.unshift(draft) }
      const code = row.code, versionNo = draft.versionNo ?? draft.version
      Object.assign(row, copy(body ?? {}), { code, updatedAt: at(), status: 'DRAFT', currentVersion: versionNo })
      Object.assign(draft, copy(body ?? {}), { code, versionNo, version: versionNo, status: 'DRAFT', content: body?.content ?? draft.content })
      if (isSkill) draft.definition = { ...draft.definition, ...copy(body ?? {}) }
      return write({ ...row, versions: list })
    }
    if (method === 'DELETE') { if (isSkill && ctx.apps.some((app: Row) => app.config?.skillRefs?.some((ref: Row) => ref.code === row.code))) return fail(409, '技能仍被应用引用，请先解除引用'); rows.splice(rows.indexOf(row), 1); return write(null) }
    return fail(405, '不支持该方法')
  }
  return mockConfigurationRequest(path, method, body, query, ctx, state, mockDigest)
}
