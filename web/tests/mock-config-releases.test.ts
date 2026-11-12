import { expect, test, vi } from 'vitest'
import { activationBlockers, parseBindings, parseConnections, parseExperiences, parseProfiles, parseReleases } from '../admin/src/pages/integration/integration-console'

async function demo(saved?: unknown, legacyLocal = false) {
  vi.resetModules()
  const session = new Map<string, string>(), local = new Map<string, string>()
  if (saved) (legacyLocal ? local : session).set('ap-demo-store-v1', JSON.stringify(saved))
  const storage = (rows: Map<string, string>) => ({ getItem: (key: string) => rows.get(key) ?? null, setItem: (key: string, value: string) => rows.set(key, value), removeItem: (key: string) => rows.delete(key) })
  vi.stubGlobal('sessionStorage', storage(session)); vi.stubGlobal('localStorage', storage(local))
  const { mockTransport } = await import('../shared/mock-transport')
  const call = async (path: string, method = 'GET', body?: unknown) => {
    const response = await mockTransport(path, { method, ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
    return { status: response.status, ...(await response.json()) }
  }
  return { call, saved: () => JSON.parse(session.get('ap-demo-store-v1') ?? 'null') }
}

test('configuration demo records use the DTO consumed by the console for planning and account assistants', async () => {
  const { call } = await demo()
  const connections = parseConnections((await call('/admin/connection-profiles')).data)
  const bindings = parseBindings((await call('/admin/environment-bindings')).data)
  for (const code of ['satellite-planning-assistant', 'user-governance-assistant', 'satellite-assistant']) {
    const profiles = parseProfiles((await call(`/admin/integration-profiles?appCode=${code}`)).data)
    expect(profiles.some(row => row.status === 'APPROVED' && row.profileRev > 0 && row.revisions.length > 0)).toBe(true)
    const releases = parseReleases((await call(`/admin/config-releases?appCode=${code}`)).data)
    expect(releases.map(row => row.status)).toEqual(expect.arrayContaining(['ACTIVE', 'APPROVED', 'DRAFT']))
    expect(releases.every(row => row.appCode === code && /^[0-9a-f]{64}$/.test(row.digest))).toBe(true)
    const candidate = releases.find(row => row.status === 'APPROVED')!
    expect(activationBlockers({ release: candidate, environment: 'production', experience: parseExperiences((await call(`/admin/apps/${code}/experiences`)).data, code), profile: profiles.find(row => row.profileId === candidate.integrationProfileId), connections })).toEqual([])
    expect(candidate.connectionProfileRefs.every(ref => bindings.some(binding => binding.bindingId === ref.bindingId && binding.currentRev === ref.bindingRev))).toBe(true)
  }
})

test('release lifecycle validates frozen references, switches one pointer and can restore a previous release', async () => {
  const { call, saved } = await demo()
  const code = 'satellite-planning-assistant'
  const active = (await call(`/admin/config-releases?appCode=${code}`)).data.find((row: any) => row.status === 'ACTIVE')
  const created = await call('/admin/config-releases', 'POST', { ...active, releaseId: 'rel-config-roundtrip', status: 'ACTIVE' })
  expect(created.data.status).toBe('DRAFT')
  expect((await call('/admin/config-releases/rel-config-roundtrip/approve', 'POST', {})).status).toBe(409)
  expect((await call('/admin/config-releases/rel-config-roundtrip/validate', 'POST', {})).data.passed).toBe(true)
  expect((await call('/admin/config-releases/rel-config-roundtrip/approve', 'POST', {})).data.status).toBe('APPROVED')
  expect((await call('/admin/config-releases/rel-config-roundtrip/activate', 'POST', {})).data.status).toBe('ACTIVE')
  expect((await call(`/admin/config-releases/${active.releaseId}`)).data.status).toBe('SUPERSEDED')
  expect((await call('/admin/config-releases/rel-config-roundtrip/disable', 'POST', {})).data.status).toBe('DISABLED')
  await call(`/admin/config-releases/${active.releaseId}/validate`, 'POST', {})
  await call(`/admin/config-releases/${active.releaseId}/approve`, 'POST', {})
  expect((await call(`/admin/config-releases/${active.releaseId}/activate`, 'POST', {})).data.status).toBe('ACTIVE')
  const reloaded = await demo(saved())
  const rows = (await reloaded.call(`/admin/config-releases?appCode=${code}`)).data
  expect(rows.filter((row: any) => row.status === 'ACTIVE').map((row: any) => row.releaseId)).toEqual([active.releaseId])
  expect(rows.find((row: any) => row.releaseId === 'rel-config-roundtrip').status).toBe('DISABLED')
})

test('old localStorage flat DTOs migrate without discarding user configuration', async () => {
  const old = { version: 2, apps: [{ code: 'custom-user-app', name: '用户配置保留', currentVersionNo: 1, config: { welcome: '用户自己的欢迎语' } }],
    demoConnections: [{ profileId: 'user-connection', status: 'ACTIVE', revision: 4, baseUrl: 'https://user.example/api', authType: 'NONE', note: '保留此字段' }],
    demoIntegrationProfiles: [{ profileId: 'user-profile', appCode: 'custom-user-app', status: 'ACTIVE', revision: 3, environment: 'production', bizSystemCode: 'USER_CUSTOM' }],
    demoBindings: [{ bindingId: 'user-binding', status: 'ACTIVE', connectionProfileId: 'user-connection', environment: 'production' }],
    demoReleases: [{ releaseId: 'user-release', appCode: 'custom-user-app', appVersion: 1, experienceRev: 1, integrationProfileId: 'user-profile', integrationProfileRev: 3, connectionProfileRefs: [{ profileId: 'user-connection', profileRev: 4 }], activationScope: 'production', status: 'ACTIVE', digest: 'sha256-old' }] }
  const { call, saved } = await demo(old, true)
  expect((await call('/admin/apps/custom-user-app')).data.config.welcome).toBe('用户自己的欢迎语')
  const connection = (await call('/admin/connection-profiles/user-connection')).data
  expect(connection).toMatchObject({ currentRev: 4, status: 'APPROVED', note: '保留此字段' })
  expect(connection.revisions[0].endpointPolicy.baseUrl).toBe('https://user.example/api')
  expect((await call('/admin/config-releases/user-release')).data).toMatchObject({ releaseId: 'user-release', status: 'ACTIVE', integrationProfileRev: 3 })
  expect((await call('/admin/config-releases?appCode=custom-user-app')).data).toHaveLength(1)
  expect((await call('/admin/config-releases?appCode=user-governance-assistant')).data.length).toBeGreaterThan(0)
  expect(saved().apps.find((row: any) => row.code === 'custom-user-app').name).toBe('用户配置保留')
})

test('new dependency revisions read back and disabled bindings block release activation', async () => {
  const { call } = await demo()
  const code = 'satellite-planning-assistant'
  await call('/admin/connection-profiles', 'POST', { profileId: 'cp-roundtrip', protocol: 'HTTP_JSON', endpointPolicy: {}, authScheme: { kind: 'NONE' }, operationAllowlist: ['query'] })
  expect((await call('/admin/connection-profiles/cp-roundtrip/approve', 'POST', { expectedRev: 1 })).data.status).toBe('APPROVED')
  await call('/admin/environment-bindings', 'POST', { bindingId: 'eb-roundtrip', environment: 'production', connectionProfileId: 'cp-roundtrip', connectionProfileRev: 1, baseEndpoint: 'https://demo.example/api', secretRef: '' })
  await call('/admin/environment-bindings/eb-roundtrip/approve', 'POST', { expectedRev: 1 })
  await call(`/admin/apps/${code}/experiences/versions`, 'POST', { expectedRev: 1, title: '修订后的体验', welcomeText: '保存并读回', capabilityDescriptions: [], promptRefs: [], helpRefs: [], inputPresentation: {}, resultPresentation: {} })
  expect((await call(`/admin/apps/${code}/experiences/approve`, 'POST', { expectedRev: 1 })).status).toBe(409)
  await call(`/admin/apps/${code}/experiences/approve`, 'POST', { expectedRev: 2 })
  const experience = parseExperiences((await call(`/admin/apps/${code}/experiences`)).data, code)
  expect(experience.revisions.map(row => row.experienceRev)).toEqual([1, 2])
  expect(experience.revisions.at(-1)?.title).toBe('修订后的体验')
  await call('/admin/integration-profiles', 'POST', { profileId: 'ip-roundtrip', appCode: code, bizSystemCode: 'SPACE', environment: 'production', mode: 'L2', allowedOrigins: ['https://demo.example'], contextSchema: {}, credentialPolicyRef: 'policy:demo', experienceRef: { appCode: code, experienceRev: 2 } })
  expect((await call('/admin/integration-profiles/ip-roundtrip/approve', 'POST', { expectedRev: 1 })).data.status).toBe('APPROVED')
  const created = await call('/admin/config-releases', 'POST', { releaseId: 'rel-dependent', appCode: code, appVersion: 1, experienceRev: 2, integrationProfileId: 'ip-roundtrip', integrationProfileRev: 1, activationScope: 'production', connectionProfileRefs: [{ profileId: 'cp-roundtrip', profileRev: 1, bindingId: 'eb-roundtrip', bindingRev: 1, operation: 'query', environment: 'production' }] })
  expect(created.status).toBe(200)
  expect((await call('/admin/config-releases/rel-dependent/validate', 'POST', {})).data.passed).toBe(true)
  await call('/admin/config-releases/rel-dependent/approve', 'POST', {})
  await call('/admin/environment-bindings/eb-roundtrip/disable', 'POST', { expectedRev: 1 })
  expect((await call('/admin/config-releases/rel-dependent/activate', 'POST', {})).status).toBe(409)
  expect((await call('/admin/config-releases/rel-dependent')).data.status).toBe('APPROVED')
})
