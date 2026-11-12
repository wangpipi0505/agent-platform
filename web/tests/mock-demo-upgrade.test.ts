import { expect, test } from 'vitest'
import { mockTransport } from '../shared/mock-transport'

test('demo writes, frozen FLOW version and independent governance approval share actual state', async () => {
  const call = async (path: string, method = 'GET', body?: unknown) => {
    const response = await mockTransport(path, { method, body: body === undefined ? undefined : JSON.stringify(body) })
    return { status: response.status, ...(await response.json()) }
  }
  const provider = (await call('/admin/model-providers', 'POST', { code: 'probe-provider', name: '探针供应商', protocol: 'OPENAI_COMPATIBLE', baseUrl: 'http://demo.local/v1', secretRef: 'secret://demo/probe' })).data
  expect((await call(`/admin/model-providers/${provider.id}`, 'PUT', { name: '保存后读回' })).data.name).toBe('保存后读回')
  expect((await call(`/admin/model-providers/${provider.id}`)).data.name).toBe('保存后读回')
  await call('/admin/prompt-templates', 'POST', { code: 'probe-template', name: '探针模板', content: '你好{{name}}' })
  expect((await call('/admin/prompt-templates/probe-template/versions/1/render', 'POST', { variables: { name: '开发者' } })).data.rendered).toBe('你好开发者')
  await call('/admin/prompt-templates/probe-template/versions/1/publish', 'POST', {})
  await call('/admin/prompt-templates/probe-template', 'PUT', { content: '新版内容' })
  const versions = (await call('/admin/prompt-templates/probe-template/versions')).data
  expect(versions.find((row: any) => row.versionNo === 1).content).toBe('你好{{name}}')
  expect(versions[0].versionNo).toBe(2)

  const app = (await call('/admin/apps', 'POST', { code: 'probe-flow', name: '探针流程', type: 'FLOW' })).data
  const graph = { ...app.definition, nodes: [{ id: 'start', type: 'START' }, { id: 'saved-node', type: 'LLM', name: '已保存节点' }, { id: 'end', type: 'END' }], edges: [{ from: 'start', to: 'saved-node' }, { from: 'saved-node', to: 'end' }] }
  await call('/admin/flows/probe-flow', 'PUT', { draftRev: app.draftRev, definition: graph, layout: {} })
  let precheck = (await call('/admin/apps/probe-flow/publish/precheck', 'POST', {})).data
  expect(precheck.passed).toBe(false)
  const evaluation = (await call('/admin/evaluations', 'POST', { appCode: app.code, draftRev: precheck.draftRev, candidateSha256: precheck.candidateSha256, datasetVersion: 'probe-v1', cases: [{ inputs: { message: '演示' }, expected: {} }] })).data
  expect(evaluation.status).toBe('ACCEPTED')
  precheck = (await call('/admin/apps/probe-flow/publish/precheck', 'POST', {})).data
  expect(precheck.evaluationBinding.evalReportId).toBe(evaluation.id)
  expect((await call('/admin/apps/probe-flow/publish/commit', 'POST', { precheckId: precheck.precheckId, draftRev: precheck.draftRev, candidateSha256: precheck.candidateSha256 })).status).toBe(200)
  const current = (await call('/admin/apps/probe-flow')).data
  await call('/admin/flows/probe-flow', 'PUT', { draftRev: current.draftRev, definition: { ...graph, nodes: app.definition.nodes, edges: app.definition.edges }, layout: {} })
  const formal = (await call('/admin/apps/probe-flow/runs', 'POST', { versionNo: 1, inputs: { message: '运行快照' } })).data
  expect((await call(`/admin/runs/${formal.runId}`)).data.nodes.some((row: any) => row.nodeDefId === 'saved-node')).toBe(true)
  const debug = (await call('/admin/debug-runs', 'POST', { appCode: app.code, mode: 'STEP', inputs: {} })).data
  expect(debug.runId).toBeTruthy()
  expect(typeof debug.eventsWatermark).toBe('number')
  expect(debug.paused.allowedActions).toContain('NEXT_NODE')
  expect((await call(`/admin/debug-runs/${debug.runId}/resume`, 'POST', { ...debug.paused, pauseSeq: -1, action: 'NEXT_NODE' })).status).toBe(409)

  const oldGovernance = (await call('/admin/apps/user-governance-assistant/versions/1')).data
  const newGovernance = (await call('/admin/apps/user-governance-assistant/versions/2')).data
  expect(oldGovernance.config.responseStyle).toBe('compact')
  expect(newGovernance.config.responseStyle).toBe('detailed')
  expect((await call('/admin/apps/user-governance-assistant/versions/1/activate', 'POST', {})).data.currentVersionNo).toBe(1)
  const restored = (await call('/admin/apps/user-governance-assistant/versions/2/restore', 'POST', {})).data
  expect(restored.config.responseStyle).toBe('detailed')
  expect(restored.draftRev).toBeGreaterThan(newGovernance.snapshot.draftRev)
  expect((await call('/admin/apps/user-governance-assistant/versions/1')).data.config.responseStyle).toBe('compact')

  const scenario = (await call('/admin/apps/user-governance-assistant/planning-scenarios')).data
  const chat = async (message: string) => (await call('/admin/apps/user-governance-assistant/demo-chat', 'POST', { conversationId: 'probe-um', message })).data
  await call('/admin/business/reset', 'POST', {})
  await chat(scenario.examples.find((row: any) => row.id === 'um-create').message)
  const waiting = await chat(scenario.examples.find((row: any) => row.id === 'um-submit').message)
  expect(waiting.status).toBe('WAITING_APPROVAL')
  expect((await call(`/admin/approvals/${waiting.runId}/${waiting.approval.nodeId}`, 'POST', { approved: true })).status).toBe(409)
  await call('/admin/business/actor', 'POST', { actorId: 'manager-a' })
  expect((await call(`/admin/approvals/${waiting.runId}/${waiting.approval.nodeId}`, 'POST', { approved: true })).status).toBe(200)
  const finished = (await call(`/admin/runs/${waiting.runId}`)).data
  expect(finished.planning.status).toBe('SUCCEEDED')
  expect((await call('/admin/business/context')).data.users.some((row: any) => row.username === 'chen.xin')).toBe(true)
  expect((await call('/admin/unknown-demo-action', 'POST', {})).status).toBe(405)
})
