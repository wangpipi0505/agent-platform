/** Configuration-console DTOs and state transitions, loaded only by the explicit frontend demo. */
type Row = Record<string, any>
type Digest = (value: unknown) => string
const copy = <T>(value: T): T => value === undefined ? value : JSON.parse(JSON.stringify(value))
const at = () => new Date().toISOString()
const positive = (value: unknown) => Number.isSafeInteger(Number(value)) && Number(value) > 0
const demoApps = new Set(['satellite-planning-assistant', 'user-governance-assistant', 'satellite-assistant', 'payload-advisor', 'anomaly-triage', 'space-visibility-agent'])
const revision = (row: Row | undefined, key: string, value: number) => row?.revisions?.find((item: Row) => Number(item[key]) === Number(value))
const published = (ctx: Row, state: Row, code: string, version: number) => state.versions[code]?.find((item: Row) => Number(item.versionNo) === Number(version))
const metadata = (row: Row) => ({ createdBy: row.createdBy ?? 'u-demo', createdAt: row.createdAt ?? at() })
const releaseContent = (row: Row) => ({ releaseId: row.releaseId, appCode: row.appCode, appVersion: row.appVersion, candidateSha256: row.candidateSha256,
  experienceRev: row.experienceRev, integrationProfileId: row.integrationProfileId, integrationProfileRev: row.integrationProfileRev,
  connectionProfileRefs: row.connectionProfileRefs, activationScope: row.activationScope, skillRefs: row.skillRefs, semanticBindings: row.semanticBindings })

function normalizeProfile(row: Row, kind: string, ctx: Row, digest: Digest) {
  const revKey = kind === 'integration-profiles' ? 'profileRev' : 'currentRev'
  const itemKey = kind === 'environment-bindings' ? 'bindingRev' : 'profileRev'
  row[revKey] ??= Number(row.revision ?? row.profileVersion ?? row.revisions?.at(-1)?.[itemKey] ?? 1)
  row.status = row.status === 'ACTIVE' ? 'APPROVED' : row.status ?? 'DRAFT'
  Object.assign(row, metadata(row)); row.updatedAt ??= row.createdAt
  const connection = ctx.demoConnections.find((item: Row) => item.profileId === row.connectionProfileId)
  const declaration: Row = kind === 'connection-profiles'
    ? { protocol: row.protocol ?? 'HTTP_JSON', endpointPolicy: row.endpointPolicy ?? { baseUrl: row.baseUrl ?? 'http://127.0.0.1:19180' },
        authScheme: row.authScheme ?? { kind: row.authType ?? 'NONE' }, identityPolicy: row.identityPolicy ?? {},
        allowedUses: row.allowedUses ?? ['CONFIG_RELEASE'], operationAllowlist: row.operationAllowlist ?? [], inputContractRefs: row.inputContractRefs ?? [], outputContractRefs: row.outputContractRefs ?? [] }
    : kind === 'environment-bindings'
      ? { environment: row.environment ?? 'production', connectionProfileId: row.connectionProfileId,
          connectionProfileRev: row.connectionProfileRev ?? connection?.currentRev ?? 1,
          baseEndpoint: row.baseEndpoint ?? connection?.revisions?.at(-1)?.endpointPolicy?.baseUrl ?? 'http://127.0.0.1:19180',
          secretRef: row.secretRef ?? '', runtimeRef: row.runtimeRef ?? {}, originPolicy: row.originPolicy ?? [], healthPolicy: row.healthPolicy ?? {} }
      : { bizSystemCode: row.bizSystemCode ?? (row.appCode === 'user-governance-assistant' ? 'USER_CENTER' : 'SPACE'), environment: row.environment ?? 'production',
          mode: row.mode ?? 'L2', allowedOrigins: row.allowedOrigins ?? ['http://127.0.0.1:25175', 'http://localhost:25175'], contextSchema: row.contextSchema ?? {},
          credentialPolicyRef: row.credentialPolicyRef ?? 'policy:frontend-demo', experienceRef: row.experienceRef ?? { appCode: row.appCode, experienceRev: 1 } }
  if (!Array.isArray(row.revisions) || !row.revisions.length) row.revisions = [{ ...declaration, [itemKey]: row[revKey], ...metadata(row) }]
  for (const item of row.revisions) {
    for (const [key, value] of Object.entries(declaration)) item[key] ??= copy(value)
    item[itemKey] ??= row[revKey]; Object.assign(item, metadata(item))
    item.declarationSha256 ??= digest(item)
    if (kind === 'environment-bindings') item.hasSecret = Boolean(item.secretRef)
  }
}

function experience(app: Row, digest: Digest): Row {
  const item = { experienceRev: 1, title: app.name, welcomeText: app.config?.welcome ?? app.description ?? '',
    capabilityDescriptions: app.config?.skillRefs?.map((ref: Row) => ({ code: ref.code, version: ref.version })) ?? [],
    promptRefs: copy(app.config?.promptRefs ?? []), helpRefs: [], inputPresentation: { type: 'CHAT' }, resultPresentation: { format: 'MARKDOWN' }, createdBy: 'u-demo', createdAt: at() }
  return { appCode: app.code, experienceRev: 1, status: 'APPROVED', createdBy: 'u-demo', createdAt: item.createdAt, updatedAt: item.createdAt,
    revisions: [{ ...item, declarationSha256: digest(item) }] }
}

/** Add missing demo rows once; existing user IDs, declarations, revisions and disabled states survive migration. */
export function normalizeMockConfiguration(ctx: Row, state: Row, digest: Digest) {
  if (Number(state.configurationSchemaVersion ?? 0) >= 1) return
  const releases: Row[] = ctx.demoReleases, profiles: Row[] = ctx.demoIntegrationProfiles
  for (const row of ctx.demoConnections) normalizeProfile(row, 'connection-profiles', ctx, digest)
  for (const row of profiles) {
    row.appCode ??= releases.find(item => item.integrationProfileId === row.profileId)?.appCode ?? (row.profileId === 'ip-sat-1' ? 'satellite-assistant' : undefined)
    normalizeProfile(row, 'integration-profiles', ctx, digest)
  }
  for (const row of ctx.demoBindings) normalizeProfile(row, 'environment-bindings', ctx, digest)
  for (const app of ctx.apps.filter((item: Row) => positive(item.currentVersionNo))) {
    const view: Row = state.experiences[app.code] ??= experience(app, digest)
    if (view.status === 'ACTIVE') view.status = 'APPROVED'
    if (!demoApps.has(app.code)) continue
    const existing = releases.filter(item => item.appCode === app.code)
    let connection: Row | undefined = ctx.demoConnections.find((item: Row) => item.profileId === existing[0]?.connectionProfileRefs?.[0]?.profileId)
    if (!connection) connection = ctx.demoConnections.find((item: Row) => item.profileId === `cp-${app.code}`)
    if (!connection) {
      connection = { profileId: `cp-${app.code}`, currentRev: 1, status: 'APPROVED', operationAllowlist: app.config?.skillRefs?.map((ref: Row) => ref.code) ?? [] }
      normalizeProfile(connection, 'connection-profiles', ctx, digest); ctx.demoConnections.push(connection)
    }
    const declaration = revision(connection, 'profileRev', connection.currentRev)
    // The original cp-sat flat fixture did not declare operations; retain its endpoint while filling that missing contract.
    if (connection.profileId === 'cp-sat' && !declaration.operationAllowlist.length) {
      declaration.operationAllowlist = app.config?.skillRefs?.map((ref: Row) => ref.code) ?? []
      declaration.declarationSha256 = digest({ ...declaration, declarationSha256: undefined })
    }
    let binding: Row | undefined = ctx.demoBindings.find((item: Row) => item.revisions?.some((rev: Row) => rev.connectionProfileId === connection!.profileId && rev.connectionProfileRev === connection!.currentRev && rev.environment === 'production'))
    if (!binding) {
      binding = { bindingId: `eb-${app.code}`, currentRev: 1, status: 'APPROVED', connectionProfileId: connection.profileId, connectionProfileRev: connection.currentRev, environment: 'production' }
      normalizeProfile(binding, 'environment-bindings', ctx, digest); ctx.demoBindings.push(binding)
    }
    let profile: Row | undefined = profiles.find(item => item.appCode === app.code && item.revisions?.some((rev: Row) => rev.profileRev === item.profileRev && rev.environment === 'production'))
    if (!profile) {
      profile = { profileId: `ip-${app.code}`, appCode: app.code, profileRev: 1, status: 'APPROVED', experienceRef: { appCode: app.code, experienceRev: view.experienceRev } }
      normalizeProfile(profile, 'integration-profiles', ctx, digest); profiles.push(profile)
    }
    const refs = [{ profileId: connection.profileId, profileRev: connection.currentRev, bindingId: binding.bindingId, bindingRev: binding.currentRev, environment: 'production', operation: declaration.operationAllowlist[0] ?? '' }]
    const base: Row = { appCode: app.code, appVersion: Number(app.currentVersionNo), experienceRev: view.experienceRev,
      integrationProfileId: profile.profileId, integrationProfileRev: profile.profileRev, connectionProfileRefs: refs, activationScope: 'production' }
    for (const status of ['ACTIVE', 'APPROVED', 'DRAFT']) {
      if (existing.some(item => item.status === status) || status === 'ACTIVE' && existing.length) continue
      const row: Row = { ...copy(base), releaseId: `rel-demo-${app.code}-${status.toLowerCase()}`, status, createdBy: 'u-demo', createdAt: at(), updatedAt: at() }
      if (!releases.some(item => item.releaseId === row.releaseId)) releases.push(row)
    }
  }
  for (const row of releases) {
    const app = ctx.apps.find((item: Row) => item.code === row.appCode)
    const version = published(ctx, state, row.appCode, row.appVersion)
    row.experienceRev ??= 1; row.activationScope ??= row.environment ?? 'production'
    row.connectionProfileRefs ??= []; row.semanticBindings ??= []; row.skillRefs ??= copy(version?.snapshot?.config?.skillRefs ?? app?.config?.skillRefs ?? [])
    for (const ref of row.connectionProfileRefs) {
      const binding = ctx.demoBindings.find((item: Row) => item.revisions?.some((rev: Row) => rev.connectionProfileId === ref.profileId && rev.connectionProfileRev === ref.profileRev && rev.environment === row.activationScope))
      if (binding && !ref.bindingId) Object.assign(ref, { bindingId: binding.bindingId, bindingRev: binding.currentRev, environment: row.activationScope })
      const connection = ctx.demoConnections.find((item: Row) => item.profileId === ref.profileId)
      ref.operation ??= revision(connection, 'profileRev', ref.profileRev)?.operationAllowlist?.[0] ?? ''
      ref.resolutionDigest ??= digest(ref)
    }
    if (!/^[0-9a-f]{64}$/.test(row.candidateSha256 ?? '')) row.candidateSha256 = version?.snapshotDigest ?? digest(app?.config ?? {})
    if (!/^[0-9a-f]{64}$/.test(row.digest ?? '')) row.digest = digest(releaseContent(row))
    Object.assign(row, metadata(row)); row.updatedAt ??= row.createdAt
    if (['APPROVED', 'ACTIVE', 'SUPERSEDED'].includes(row.status)) row.validation ??= { passed: true, errors: [], checks: [], candidateSha256: row.digest }
  }
  state.configurationSchemaVersion = 1
  ctx.persist()
}

function releaseErrors(row: Row, ctx: Row, state: Row): string[] {
  const errors: string[] = []
  if (!published(ctx, state, row.appCode, row.appVersion)) errors.push('应用发布版本不存在')
  const view = state.experiences[row.appCode]
  if (!view || view.status !== 'APPROVED' || !revision(view, 'experienceRev', row.experienceRev)) errors.push('体验修订不存在或未批准')
  const profile = ctx.demoIntegrationProfiles.find((item: Row) => item.profileId === row.integrationProfileId)
  const declaration = revision(profile, 'profileRev', row.integrationProfileRev)
  if (!profile || profile.appCode !== row.appCode || profile.status !== 'APPROVED' || !declaration) errors.push('接入档案修订不存在或未批准')
  else {
    if (declaration.environment !== row.activationScope) errors.push('接入档案环境与激活环境不一致')
    if (declaration.experienceRef?.appCode !== row.appCode || declaration.experienceRef?.experienceRev !== row.experienceRev) errors.push('接入档案的体验引用不一致')
  }
  for (const ref of row.connectionProfileRefs ?? []) {
    const connection = ctx.demoConnections.find((item: Row) => item.profileId === ref.profileId)
    const contract = revision(connection, 'profileRev', ref.profileRev)
    if (!connection || connection.status !== 'APPROVED' || !contract) { errors.push(`连接档案 ${ref.profileId} 不存在或未批准`); continue }
    const binding = ctx.demoBindings.find((item: Row) => item.bindingId === ref.bindingId), bound = revision(binding, 'bindingRev', ref.bindingRev)
    if (!binding || binding.status !== 'APPROVED' || !bound || bound.environment !== row.activationScope || bound.connectionProfileId !== ref.profileId || bound.connectionProfileRev !== ref.profileRev) errors.push(`连接 ${ref.profileId} 的环境绑定不存在、不匹配或未批准`)
    if (contract.operationAllowlist?.length && !contract.operationAllowlist.includes(ref.operation)) errors.push(`连接 ${ref.profileId} 的操作不在批准白名单中`)
  }
  return errors
}

export function mockConfigurationRequest(path: string, method: string, body: Row | undefined, query: URLSearchParams, ctx: Row, state: Row, digest: Digest): Response | undefined {
  const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', message: '', data }), { headers: { 'Content-Type': 'application/json' } })
  const fail = (status: number, message: string) => new Response(JSON.stringify({ code: status === 404 ? 'CONFIG_REF_NOT_FOUND' : status === 405 ? 'METHOD_NOT_ALLOWED' : status === 409 ? 'CONFLICT' : 'INVALID_ARGUMENT', message, data: null }), { status, headers: { 'Content-Type': 'application/json' } })
  const write = (data: unknown, action: string, target: string) => { ctx.audit(action, 'CONFIGURATION', target, action); ctx.persist(); return ok(data) }
  let m: RegExpMatchArray | null
  if ((m = path.match(/^\/admin\/(connection-profiles|environment-bindings|integration-profiles)(?:\/([^/]+))?(?:\/(versions|approve|disable)(?:\/(\d+)\/grants)?)?$/))) {
    const kind = m[1], rows: Row[] = kind === 'connection-profiles' ? ctx.demoConnections : kind === 'environment-bindings' ? ctx.demoBindings : ctx.demoIntegrationProfiles
    const idKey = kind === 'environment-bindings' ? 'bindingId' : 'profileId', revKey = kind === 'integration-profiles' ? 'profileRev' : 'currentRev', itemKey = kind === 'environment-bindings' ? 'bindingRev' : 'profileRev'
    const row = m[2] ? rows.find(item => item[idKey] === m![2]) : undefined
    if (m[2] && !row) return fail(404, '档案不存在')
    if (method === 'GET') return ok(m[4] ? { grants: [], items: [], revision: Number(m[4]) } : row ?? rows.filter(item => !query.get('appCode') || item.appCode === query.get('appCode')))
    if (method !== 'POST') return fail(405, '仅支持GET/POST')
    if (m[4]) return fail(405, '授权目录只读')
    if (row && body?.expectedRev !== undefined && Number(body.expectedRev) !== Number(row[revKey])) return fail(409, '档案修订已变化，请刷新')
    if (m[3] === 'approve' || m[3] === 'disable') {
      if (m[3] === 'approve' && kind === 'integration-profiles') {
        const ref = revision(row!, itemKey, row![revKey])?.experienceRef, view = state.experiences[row!.appCode]
        if (!ref || ref.appCode !== row!.appCode || view?.status !== 'APPROVED' || !revision(view, 'experienceRev', ref.experienceRev)) return fail(409, '请先批准引用的应用体验')
      }
      if (m[3] === 'approve' && kind === 'environment-bindings') {
        const item = revision(row!, itemKey, row![revKey]), connection = ctx.demoConnections.find((value: Row) => value.profileId === item?.connectionProfileId)
        if (connection?.status !== 'APPROVED' || !revision(connection, 'profileRev', item?.connectionProfileRev)) return fail(409, '请先批准引用的连接档案')
      }
      row!.status = m[3] === 'approve' ? 'APPROVED' : 'DISABLED'; row!.updatedAt = at()
      return write(row, `CONFIG_PROFILE_${m[3].toUpperCase()}`, row![idKey])
    }
    if (!row && (!body?.[idKey] || rows.some(item => item[idKey] === body[idKey]))) return fail(409, '档案编码为空或已存在')
    if (kind === 'integration-profiles' && !row && !ctx.apps.some((app: Row) => app.code === body?.appCode)) return fail(400, '应用不存在')
    if (kind === 'connection-profiles' && !body?.protocol) return fail(400, '连接协议必填')
    if (kind === 'environment-bindings' && (!body?.connectionProfileId || !positive(body?.connectionProfileRev) || !body?.baseEndpoint)) return fail(400, '连接档案、修订和物理端点必填')
    const target: Row = row ?? { [idKey]: body![idKey], [revKey]: 0, ...(kind === 'integration-profiles' ? { appCode: body!.appCode } : {}), revisions: [], createdBy: 'u-demo', createdAt: at() }
    const declaration = copy(body ?? {}); for (const key of [idKey, 'expectedRev', 'requestId', 'appCode']) delete declaration[key]
    target[revKey]++; target.status = 'DRAFT'; target.updatedAt = at()
    target.revisions.push({ ...declaration, [itemKey]: target[revKey], declarationSha256: digest(declaration), createdBy: 'u-demo', createdAt: at(), ...(kind === 'environment-bindings' ? { hasSecret: Boolean(declaration.secretRef) } : {}) })
    if (!row) rows.unshift(target)
    return write(target, 'CONFIG_PROFILE_REVISION', target[idKey])
  }
  if ((m = path.match(/^\/admin\/apps\/([^/]+)\/experiences(?:\/(versions|approve|disable))?$/))) {
    const app = ctx.apps.find((item: Row) => item.code === m![1]); if (!app) return fail(404, '应用不存在')
    const row = state.experiences[m[1]]
    if (method === 'GET') return row ? ok(row) : fail(404, '应用体验尚未创建')
    if (method !== 'POST') return fail(405, '仅支持GET/POST')
    if (!row && m[2]) return fail(404, '应用体验尚未创建')
    if (row && !m[2]) return fail(409, '体验已存在，请创建新修订')
    if (body?.expectedRev !== undefined && Number(body.expectedRev) !== Number(row?.experienceRev)) return fail(409, '体验修订已变化')
    if (m[2] === 'approve' || m[2] === 'disable') row.status = m[2] === 'approve' ? 'APPROVED' : 'DISABLED'
    else {
      if (typeof body?.title !== 'string' || !body.title.trim()) return fail(400, '体验标题必填')
      const target: Row = row ?? { appCode: app.code, experienceRev: 0, createdBy: 'u-demo', createdAt: at(), revisions: [] }
      const declaration = copy(body ?? {}); delete declaration.expectedRev; delete declaration.requestId
      target.experienceRev++; target.status = 'DRAFT'
      target.revisions.push({ ...declaration, experienceRev: target.experienceRev, declarationSha256: digest(declaration), createdBy: 'u-demo', createdAt: at() })
      state.experiences[app.code] = target
    }
    state.experiences[app.code].updatedAt = at()
    return write(state.experiences[app.code], 'APP_EXPERIENCE_UPDATE', app.code)
  }
  if ((m = path.match(/^\/admin\/config-releases(?:\/([^/]+))?(?:\/(validate|approve|activate|disable))?$/))) {
    const rows: Row[] = ctx.demoReleases, row = m[1] ? rows.find(item => item.releaseId === m![1]) : undefined
    if (m[1] && !row) return fail(404, '配置发布不存在')
    if (method === 'GET') return ok(row ?? rows.filter(item => !query.get('appCode') || item.appCode === query.get('appCode')))
    if (method !== 'POST') return fail(405, '仅支持GET/POST')
    if (!m[1]) {
      const app = ctx.apps.find((item: Row) => item.code === body?.appCode), version = published(ctx, state, body?.appCode, body?.appVersion)
      if (!app || !version) return fail(409, '请先发布引用的应用版本')
      if (!positive(body?.experienceRev) || !body?.integrationProfileId || !positive(body?.integrationProfileRev) || !/^[a-z][a-z0-9-]{0,31}$/.test(body?.activationScope ?? '') || !Array.isArray(body?.connectionProfileRefs)
        || body.connectionProfileRefs.some((ref: Row) => !ref || typeof ref.profileId !== 'string' || !ref.profileId || !positive(ref.profileRev))) return fail(400, '配置发布引用或环境格式不正确')
      const created: Row = { ...copy(body!), releaseId: body?.releaseId ?? ctx.nextId('release'), status: 'DRAFT', candidateSha256: version.snapshotDigest,
        createdBy: 'u-demo', createdAt: at(), updatedAt: at(), skillRefs: copy(version.snapshot.config?.skillRefs ?? []), semanticBindings: copy(body?.semanticBindings ?? []) }
      delete created.validation; delete created.requestId
      for (const ref of created.connectionProfileRefs) ref.resolutionDigest = digest(ref)
      created.digest = digest(releaseContent(created))
      if (rows.some(item => item.releaseId === created.releaseId)) return fail(409, '发布编码已存在')
      rows.unshift(created); return write(created, 'CONFIG_RELEASE_CREATE', created.releaseId)
    }
    if (!m[2]) return fail(405, '请选择发布操作')
    if (row!.status === 'DISABLED' && m[2] !== 'disable') return fail(409, '已停用的配置发布不能重新启用，请复制为新草稿')
    const errors = releaseErrors(row!, ctx, state)
    if (m[2] === 'validate') {
      row!.validation = { passed: !errors.length, errors, candidateSha256: row!.digest, checks: errors.length ? errors.map(detail => ({ result: 'ERROR', detail })) : [{ result: 'PASS', detail: '应用版本、体验、接入档案和环境连接引用一致' }] }
      if (!errors.length && !['ACTIVE', 'APPROVED'].includes(row!.status)) row!.status = 'VALIDATED'
      row!.updatedAt = at(); return write({ ...row, ...row!.validation }, 'CONFIG_RELEASE_VALIDATE', row!.releaseId)
    }
    if (m[2] === 'approve') {
      if (errors.length || !row!.validation?.passed || row!.validation.candidateSha256 !== row!.digest || !['VALIDATED', 'APPROVED', 'ACTIVE'].includes(row!.status)) return fail(409, errors.join('；') || '请先通过当前配置发布校验')
      if (row!.status !== 'ACTIVE') row!.status = 'APPROVED'
      row!.approvedBy = 'u-demo'; row!.approvedAt = at()
    }
    if (m[2] === 'activate') {
      if (errors.length || !['APPROVED', 'ACTIVE'].includes(row!.status)) return fail(409, errors.join('；') || '配置发布尚未批准')
      const current = rows.find(item => item.appCode === row!.appCode && item.activationScope === row!.activationScope && item.status === 'ACTIVE')
      if (body?.expectedActiveReleaseId !== undefined && body.expectedActiveReleaseId !== (current?.releaseId ?? null)) return fail(409, '当前激活版本已变化，请刷新')
      for (const item of rows) if (item !== row && item.appCode === row!.appCode && item.activationScope === row!.activationScope && item.status === 'ACTIVE') { item.status = 'SUPERSEDED'; item.updatedAt = at() }
      row!.status = 'ACTIVE'; row!.activatedAt = at()
    }
    if (m[2] === 'disable') row!.status = 'DISABLED'
    row!.updatedAt = at(); return write(row, `CONFIG_RELEASE_${m[2].toUpperCase()}`, row!.releaseId)
  }
  return undefined
}
