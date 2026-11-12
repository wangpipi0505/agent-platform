import { beforeEach, describe, expect, it, vi } from 'vitest'
import Ajv2020 from 'ajv/dist/2020'

type Row = Record<string, any>
let request: (path: string, method?: string, body?: Row) => Promise<Row>
const app = '/admin/apps/satellite-planning-assistant'
beforeEach(async () => {
  vi.resetModules()
  const storage = new Map<string, string>()
  vi.stubGlobal('sessionStorage', { getItem: (key: string) => storage.get(key) ?? null, setItem: (key: string, value: string) => storage.set(key, value), removeItem: (key: string) => storage.delete(key) })
  const { mockTransport } = await import('../shared/mock-transport')
  request = async (path, method = 'GET', body) => {
    const response = await mockTransport(path, { method, ...(body ? { body: JSON.stringify(body) } : {}) })
    return { httpStatus: response.status, ...(await response.json()) }
  }
})
const chat = async (message: string, conversationId = 'test-conversation') => (await request(app + '/demo-chat', 'POST', { message, conversationId })).data

describe('卫星规划助手完整Mock业务链', () => {
  it('登记完整规划工具并绑定正式本体与提示词', async () => {
    const response = await request(app)
    expect(response.httpStatus).toBe(200)
    expect(response.data.name).toBe('卫星规划助手')
    expect(response.data.config.ontology.releaseId).toBe('rel-observation-v1')
    const tools = (await request('/admin/skills')).data
    const bound = response.data.config.skillRefs.map((ref: Row) => ref.code)
    expect(bound.length).toBeGreaterThanOrEqual(16)
    for (const code of bound) {
      const tool = tools.find((item: Row) => item.code === code)
      expect(tool, code).toBeDefined()
      const detail = (await request('/admin/skills/' + code)).data
      expect(detail.versions[0].inputSchema.type).toBe('object')
    }
    const submit = (await request('/admin/skills/planning-task-submit')).data
    expect(submit.versions[0].execution.sideEffect).toBe(true)
    const prompt = (await request('/admin/prompt-templates/planning-system')).data
    expect(prompt.content).toContain('地面目标')
    expect(prompt.content).toContain('人工确认')
  })

  it('目录、本体和工具有对应业务数据', async () => {
    const result = await chat('列出可用的卫星、空间目标、地面目标和地面站')
    expect(result.reply).toContain('青海湖')
    expect(result.reply).toContain('STARLINK-31091')
    expect(result.toolCalls.map((tool: Row) => tool.skillCode)).toContain('planning-ground-target-query')
    expect(result.ontology.releaseRef.releaseId).toBe('rel-observation-v1')
    expect(result.ontology.facts.length).toBeGreaterThan(2)
    const run = (await request('/admin/runs/' + result.runId)).data
    expect(run.output.text).toBe(result.reply)
    expect(run.versionRefs.ontologyRelease).toBe('rel-observation-v1')
  })

  it('对空与对地问法得到各自窗口和规则，而非通用答复', async () => {
    const space = await chat('规划明天上午对空间目标 STARLINK-31091 的观测窗口')
    expect(space.plan.kind).toBe('SPACE')
    expect(space.plan.windows[0].targetId).toBe(8890)
    expect(space.toolCalls.map((tool: Row) => tool.skillCode)).toContain('planning-space-plan')
    const ground = await chat('规划明天上午青海湖生态观测区的对地观测，云量不超过30%')
    expect(ground.plan.kind).toBe('GROUND')
    expect(ground.plan.windows[0].targetId).toBe('GT-QH-01')
    expect(ground.ontology.decisions.some((decision: Row) => decision.ruleId === 'rule-cloud' && decision.result === 'HOLDS')).toBe(true)
    expect(ground.reply).not.toBe(space.reply)
  })

  it('下传校验使用同一会话的规划窗口', async () => {
    await chat('规划明天上午青海湖生态观测区的对地观测')
    const result = await chat('检查这套规划能否通过喀什站下传，推荐最优方案')
    expect(result.plan.kind).toBe('GROUND')
    expect(result.plan.downlinks[0].stationId).toBe('ST-KS-01')
    expect(result.plan.downlinks[0].satelliteId).toBe(result.plan.windows[0].satelliteId)
    expect(result.toolCalls.map((tool: Row) => tool.skillCode)).toContain('planning-downlink-validate')
  })

  it('会话隔离，缺少规划时不提交别人的任务', async () => {
    await chat('规划明天上午青海湖生态观测区的对地观测', 'alice')
    const bob = await chat('提交这套规划', 'bob')
    expect(bob.status).toBe('NEEDS_INPUT')
    expect(bob.task).toBeUndefined()
    expect(bob.reply).toContain('先')
  })

  it('提交先等待确认，批准后才生成任务、结果和制品', async () => {
    await chat('规划明天上午青海湖生态观测区的对地观测')
    const waiting = await chat('提交这套规划')
    expect(waiting.status).toBe('WAITING_APPROVAL')
    expect(waiting.task).toBeUndefined()
    expect(waiting.approval.runId).toBe(waiting.runId)
    const decision = await request(`/admin/approvals/${waiting.runId}/${waiting.approval.nodeId}`, 'POST', { approved: true })
    expect(decision.httpStatus).toBe(200)
    const run = (await request('/admin/runs/' + waiting.runId)).data
    expect(run.status).toBe('SUCCEEDED')
    expect(run.output.task.status).toBe('SUCCEEDED')
    expect(run.output.artifacts.length).toBeGreaterThan(0)
    const result = await chat('查询刚才任务的结果')
    expect(result.task.taskId).toBe(run.output.task.taskId)
    expect(result.reply).toContain('已完成')
    expect(result.ontology.decisions.some((decision: Row) => decision.ruleId === 'rule-approval' && decision.result === 'HOLDS')).toBe(true)
    const conflict = await request(`/admin/approvals/${waiting.runId}/${waiting.approval.nodeId}`, 'POST', { approved: false })
    expect(conflict.httpStatus).toBe(409)
  })

  it('拒绝后不产生任务，修订后的本体配置实际决定响应', async () => {
    await chat('规划明天上午 STARLINK-31091 的空间观测')
    const waiting = await chat('提交这套规划')
    await request(`/admin/approvals/${waiting.runId}/${waiting.approval.nodeId}`, 'POST', { approved: false })
    const run = (await request('/admin/runs/' + waiting.runId)).data
    expect(run.output.task).toBeUndefined()
    expect(run.status).toBe('REJECTED')
    const config = (await request(app)).data.config
    delete config.ontology
    await request(app + '/config', 'PUT', { draftRev: 1, config })
    const result = await chat('说明当前本体的规则与依据', 'no-ontology')
    expect(result.ontology).toBeNull()
    expect(result.reply).toContain('未选择本体')
  })

  it('无窗口、未知目标和日期不匹配有明确业务反馈', async () => {
    const empty = await chat('规划鄱阳湖湿地夜间光学观测')
    expect(empty.status).toBe('NO_WINDOW')
    expect(empty.plan.windows).toEqual([])
    expect(empty.reply).toContain('光照')
    const unknown = await chat('规划目标 UNKNOWN-999 的观测')
    expect(unknown.status).toBe('NEEDS_INPUT')
    expect(unknown.task).toBeUndefined()
    const date = await chat('规划2030-01-01青海湖观测')
    expect(date.status).toBe('NEEDS_INPUT')
    expect(date.reply).toContain('2026-11-06')
  })
  it('取消待确认运行后不能再批准或产生任务', async () => {
    await chat('规划明天上午青海湖生态观测区的对地观测')
    const waiting = await chat('提交这套规划')
    await request('/admin/runs/' + waiting.runId + '/cancel', 'POST', {})
    const approval = await request(`/admin/approvals/${waiting.runId}/${waiting.approval.nodeId}`, 'POST', { approved: true })
    expect(approval.httpStatus).toBe(409)
    const snapshot = (await request('/admin/runs/' + waiting.runId)).data
    expect(snapshot.status).toBe('CANCELLED')
    expect(snapshot.output.task).toBeUndefined()
  })
  it('选择其他本体不会引用观测本体的规则与事实', async () => {
    const config = (await request(app)).data.config
    config.ontology = { space: 'sp-sat', spaceCode: 'sp-sat', releaseId: 'rel-003', version: 'v3', contentDigest: 'a'.repeat(64) }
    await request(app + '/config', 'PUT', { config, draftRev: 1 })
    const result = await chat('说明当前本体的规则与依据')
    expect(result.ontology.releaseRef.releaseId).toBe('rel-003')
    expect(result.reply).not.toContain('rule-cloud')
    expect(result.ontology.decisions).toEqual([])
  })
  it('自定义云量和星历光照约束在追问规则时保持一致', async () => {
    const cloud = await chat('规划青海湖对地观测，云量不超过20%')
    expect(cloud.plan.windows).toEqual([])
    expect(cloud.ontology.decisions.some((decision: Row) => decision.ruleId === 'rule-cloud' && decision.result === 'NOT_HOLDS' && decision.reason.includes('20%'))).toBe(true)
    await chat('模拟星历过期，规划 STARLINK-31091 的观测')
    const staleRules = await chat('说明这次本体规则的判定依据')
    expect(staleRules.ontology.decisions.find((decision: Row) => decision.ruleId === 'rule-epoch').result).toBe('NOT_HOLDS')
    await chat('规划鄱阳湖湿地夜间光学观测')
    const nightRules = await chat('说明这次本体规则的判定依据')
    expect(nightRules.ontology.decisions.find((decision: Row) => decision.ruleId === 'rule-sunlight').result).toBe('NOT_HOLDS')
  })
  it('执行中任务可查询、受控取消，确认后状态与工具结果一致', async () => {
    const running = await chat('查询任务 DEMO-RUNNING-0001 的进度')
    expect(running.task.status).toBe('RUNNING')
    const waiting = await chat('取消任务 DEMO-RUNNING-0001')
    expect(waiting.status).toBe('WAITING_APPROVAL')
    await request(`/admin/approvals/${waiting.runId}/${waiting.approval.nodeId}`, 'POST', { approved: true })
    const cancelled = await chat('查询任务 DEMO-RUNNING-0001 的结果')
    expect(cancelled.task.status).toBe('CANCELLED')
    expect(cancelled.reply).not.toContain('可下载')
    const execution = (await request('/admin/runs/' + waiting.runId)).data
    expect(execution.planning.toolCalls.map((tool: Row) => tool.skillCode)).toContain('planning-task-cancel')
  })
  it('按地面目标编码匹配，不静默换成默认目标', async () => {
    const result = await chat('规划地面目标 GT-PY-02 的对地观测')
    expect(result.plan.targetId).toBe('GT-PY-02')
    expect(result.reply).toContain('鄱阳湖')
  })
  it('用户新建任务按会话隔离，显式ID也不能访问其他会话的制品', async () => {
    await chat('规划青海湖对地观测', 'alice')
    const waiting = await chat('提交这套规划', 'alice')
    await request(`/admin/approvals/${waiting.runId}/${waiting.approval.nodeId}`, 'POST', { approved: true })
    const task = (await request('/admin/runs/' + waiting.runId)).data.output.task
    const other = await chat(`查询任务 ${task.taskId} 的状态`, 'bob')
    expect(other.status).toBe('NEEDS_INPUT')
    expect(other.task).toBeUndefined()
  })
  it('未知目标编码拒绝匹配，缺少目标时要求用户选择', async () => {
    expect((await chat('规划地面目标 GT-PY-03 的对地观测')).status).toBe('NEEDS_INPUT')
    expect((await chat('规划空间目标9998的观测')).status).toBe('NEEDS_INPUT')
    expect((await chat('规划明天上午对地观测')).status).toBe('NEEDS_INPUT')
  })
  it('其他本体的下传与批准结果不追加观测本体决策', async () => {
    const config = (await request(app)).data.config
    config.ontology = { space: 'sp-sat', spaceCode: 'sp-sat', releaseId: 'rel-003', version: 'v3', contentDigest: 'a'.repeat(64) }
    await request(app + '/config', 'PUT', { config, draftRev: 1 })
    await chat('规划青海湖对地观测')
    expect((await chat('检查喀什站下传')).ontology.decisions).toEqual([])
    const waiting = await chat('提交这套规划')
    await request(`/admin/approvals/${waiting.runId}/${waiting.approval.nodeId}`, 'POST', { approved: true })
    expect((await request('/admin/runs/' + waiting.runId)).data.planning.ontology.decisions).toEqual([])
  })
  it('刷新恢复待确认计划，批准后制品仍引用冻结本体', async () => {
    await chat('规划青海湖对地观测')
    const waiting = await chat('提交这套规划')
    vi.resetModules()
    const { mockTransport } = await import('../shared/mock-transport')
    const before = await (await mockTransport('/admin/runs/' + waiting.runId)).json()
    expect(before.data.status).toBe('WAITING_APPROVAL')
    expect(before.data.planning.plan.targetId).toBe('GT-QH-01')
    const approved = await mockTransport(`/admin/approvals/${waiting.runId}/${waiting.approval.nodeId}`, { method: 'POST', body: JSON.stringify({ approved: true }) })
    expect(approved.status).toBe(200)
    const after = await (await mockTransport('/admin/runs/' + waiting.runId)).json()
    const report = after.data.output.artifacts.find((artifact: Row) => artifact.mediaType === 'application/json')
    expect(JSON.parse(report.content).ontology.releaseId).toBe('rel-observation-v1')
  })
  it('工具链实际输入输出符合各自声明的Schema', async () => {
    const catalog = (await request(app + '/planning-scenarios')).data
    const validator = new Ajv2020({ strict: false, formats: { 'date-time': true } })
    for (const message of ['列出可用的卫星、空间目标、地面目标和地面站', '规划青海湖对地观测', '检查喀什站下传', '说明当前本体的规则与依据', '导出当前规划报告', '提交这套规划']) {
      const result = await chat(message)
      for (const tool of result.toolCalls) {
        const definition = catalog.tools.find((item: Row) => item.code === tool.skillCode)
        expect(validator.compile(definition.inputSchema)(tool.input), `${tool.skillCode}输入不符合契约`).toBe(true)
        expect(validator.compile(definition.outputSchema)(tool.output), `${tool.skillCode}输出不符合契约`).toBe(true)
      }
    }
  })
  it('规划的本体事实只引用对应目标及观测链，目录仍提供完整实例', async () => {
    const catalog = await chat('列出可用的卫星、空间目标、地面目标和地面站')
    expect(catalog.ontology.facts).toHaveLength(23)
    const result = await chat('规划地面目标 GT-PY-02 的对地观测')
    const targets = result.ontology.facts.filter((fact: Row) => ['GroundTarget', 'SpaceTarget'].includes(fact.objectType))
    expect(targets).toHaveLength(1)
    expect(targets[0].values.id).toBe('GT-PY-02')
    expect(result.ontology.facts.some((fact: Row) => fact.stableId === 'GD-W03')).toBe(true)
  })
})
