/** 纯Mock规划业务内核。输入、工具、本体、运行与任务共享同一场景；不调用真实服务。 */
import seedText from './satellite-planning.seed.json?raw'

type Row = Record<string, unknown>
export interface PlanningOntologyRef { space?: string; spaceCode: string; releaseId: string; version: string; contentDigest: string }
interface Satellite { id: number; name: string; supportedPayloads: string[]; orbitAltitudeKm: number; noradCatId: number }
interface Target { id: string | number; name: string; type: string; maxCloudPercent?: number; requiredResolutionM?: number }
interface Station { id: string; name: string }
export interface PlanningWindow { id: string; kind: string; satelliteId: number; targetId: string | number; startTime: string; endTime: string; durationSec: number; score: number; dataGb: number; illumination: string; epochAgeDays: number; cloudPercent?: number; resolutionM?: number; exclusionReason?: string }
export interface Downlink { id: string; satelliteId: number; stationId: string; startTime: string; endTime: string; durationSec: number; capacityGb: number }
export interface PlanningTool { code: string; name: string; description: string; version: number; method: string; endpoint: string; readonly: boolean; sideEffect: boolean; inputSchema: Row; outputSchema: Row }
interface PlanningRule { id: string; name: string; description: string; condition: string }
interface Scenario {
  id: string; version: number; clock: string; planningDate: string; timeZone: string; dataOrigin: string;
  releaseRef: PlanningOntologyRef; systemPrompt: string;
  assets: { satellites: Satellite[]; spaceTargets: Target[]; groundTargets: Target[]; stations: Station[]; payloads: Row[] };
  windows: PlanningWindow[]; downlinks: Downlink[]; tools: PlanningTool[];
  examples: { id: string; title: string; message: string; intent: string }[];
  ontology: { space: { id: string; name: string }; classes: { id: string; name: string; displayName: string }[]; rules: PlanningRule[]; instances: { className: string; stableId: string; values: Row }[]; operations: { code: string; name: string }[] };
}
export const planningSeed = JSON.parse(seedText) as Scenario
export const planningAppCodes = new Set(['satellite-planning-assistant', 'satellite-assistant', 'space-visibility-agent'])
export const planningOntologyConfig: PlanningOntologyRef = { space: planningSeed.ontology.space.id, ...planningSeed.releaseRef }
export interface PlanningDecision { ruleId: string; name: string; result: 'HOLDS' | 'NOT_HOLDS' | 'UNKNOWN'; reason: string; windowId?: string }
export interface PlanningOntology { releaseRef: PlanningOntologyRef; name: string; facts: { objectType: string; stableId: string; values: Row }[]; decisions: PlanningDecision[]; relations: string[] }
export interface ToolCall { id: string; skillCode: string; name: string; input: unknown; output: unknown; status: string }
export interface PlanningPlan { id: string; kind: 'SPACE' | 'GROUND'; targetId: string | number; targetName: string; planningDate: string; windows: PlanningWindow[]; excludedWindows: PlanningWindow[]; downlinks: Downlink[]; downlinkChecked?: boolean; recommendedWindowId?: string; revision: number; constraints?: { maxCloudPercent: number; epochAgeDays: number; night: boolean; onlySatelliteId?: number } }
export interface PlanningArtifact { id: string; name: string; mediaType: string; content: string; size: number; downloadUrl: string }
export interface PlanningTask { taskId: string; status: string; plan: PlanningPlan; sourceRunId: string; stages: string[]; artifacts: PlanningArtifact[]; ownerConversationKey?: string; shared?: boolean; ontology?: PlanningOntology | null }
export interface PlanningApproval { runId: string; nodeId: string; status: string; summary: string; action: 'SUBMIT' | 'CANCEL'; taskId?: string }
export interface PlanningResponse { conversationId: string; runId: string; reply: string; status: string; toolCalls: ToolCall[]; ontology: PlanningOntology | null; plan?: PlanningPlan; approval?: PlanningApproval; task?: PlanningTask; artifacts: PlanningArtifact[]; promptSnapshot: string }
interface Conversation { plan?: PlanningPlan; lastTaskId?: string; pendingRunId?: string }
interface Pending { conversationKey: string; response: PlanningResponse }
export const planningState = { conversations: {} as Record<string, Conversation>, tasks: {} as Record<string, PlanningTask>, pending: {} as Record<string, Pending>, results: {} as Record<string, PlanningResponse>, sequence: 0 }
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T
const row = (value: unknown): Row => value && typeof value === 'object' && !Array.isArray(value) ? value as Row : {}
// 一条执行中共享样例用于展示进度与受控取消；新提交任务仍归属具体会话。
planningState.tasks['DEMO-RUNNING-0001'] = {
  taskId: 'DEMO-RUNNING-0001', status: 'RUNNING', sourceRunId: 'seed-running-task', stages: ['SUBMITTED','RUNNING'], artifacts: [], shared: true,
  plan: { id: 'PLAN-RUNNING-01', kind: 'SPACE', targetId: 8890, targetName: 'STARLINK-31091', planningDate: planningSeed.planningDate, windows: [copy(planningSeed.windows[0])], excludedWindows: [], downlinks: [copy(planningSeed.downlinks[0])], recommendedWindowId: 'SP-W01', revision: 1, constraints: { maxCloudPercent: 30, epochAgeDays: 2, night: false } },
}
export function restorePlanningState(value: unknown) {
  const saved = row(value)
  for (const key of ['conversations', 'tasks', 'pending', 'results'] as const) {
    if (saved[key] && typeof saved[key] === 'object' && !Array.isArray(saved[key])) Object.assign(planningState[key], saved[key])
  }
  if (typeof saved.sequence === 'number') planningState.sequence = saved.sequence
}
export const planningTool = (code: string) => planningSeed.tools.find(tool => tool.code === code)
function call(result: PlanningResponse, code: string, input: unknown, output: unknown, status = 'SUCCEEDED') {
  result.toolCalls.push({ id: `${result.runId}-call-${result.toolCalls.length + 1}`, skillCode: code, name: planningTool(code)?.name ?? code, input: copy(input), output: copy(output), status })
}
function ontologyFor(config: Row): PlanningOntology | null {
  const selected = row(config.ontology)
  if (!selected.releaseId) return null
  const matching = selected.releaseId === planningSeed.releaseRef.releaseId
  return {
    releaseRef: { spaceCode: String(selected.spaceCode ?? selected.space ?? ''), releaseId: String(selected.releaseId), version: String(selected.version ?? ''), contentDigest: String(selected.contentDigest ?? '') },
    name: matching ? planningSeed.ontology.space.name : '所选本体版本',
    facts: matching ? planningSeed.ontology.instances.map(item => ({ objectType: item.className, stableId: item.stableId, values: copy(item.values) })) : [],
    decisions: [], relations: matching ? ['Satellite → carries → Payload', 'ObservationWindow → observesSpace → SpaceTarget', 'ObservationWindow → observesGround → GroundTarget', 'ObservationWindow → followedBy → DownlinkWindow → receivedBy → GroundStation'] : [],
  }
}
function rulesFor(result: PlanningResponse, plan: PlanningPlan) {
  if (!result.ontology || result.ontology.releaseRef.releaseId !== planningSeed.releaseRef.releaseId) return
  const decision = (ruleId: string, good: boolean, reason: string, windowId?: string): PlanningDecision => ({ ruleId, name: planningSeed.ontology.rules.find(rule => rule.id === ruleId)?.name ?? ruleId, result: good ? 'HOLDS' : 'NOT_HOLDS', reason, ...(windowId ? { windowId } : {}) })
  const constraints = plan.constraints ?? { maxCloudPercent: 30, epochAgeDays: 2, night: false }
  const windows = [...plan.windows, ...plan.excludedWindows]
  const satelliteIds = new Set(windows.map(window => window.satelliteId))
  if (constraints.onlySatelliteId) satelliteIds.add(constraints.onlySatelliteId)
  result.ontology.facts = planningSeed.ontology.instances.filter(instance => {
    if (instance.className === 'Satellite') return satelliteIds.has(Number(instance.values.id))
    if (instance.className === 'Payload') return satelliteIds.has(Number(instance.values.satelliteId)) && instance.values.payloadType === plan.kind
    if (['SpaceTarget','GroundTarget'].includes(instance.className)) return instance.values.id === plan.targetId
    if (instance.className === 'ObservationWindow') return windows.some(window => window.id === instance.stableId)
    if (instance.className === 'DownlinkWindow') return plan.downlinks.some(link => link.id === instance.stableId)
    if (instance.className === 'GroundStation') return plan.downlinks.some(link => link.stationId === instance.values.id)
    return false
  }).map(instance => ({ objectType: instance.className, stableId: instance.stableId, values: copy(instance.values) }))
  const payloadMatches = !constraints.onlySatelliteId || Boolean(planningSeed.assets.satellites.find(item => item.id === constraints.onlySatelliteId)?.supportedPayloads.includes(plan.kind))
  result.ontology.decisions = [
    decision('rule-payload', payloadMatches, `按${plan.kind}载荷及目标类型匹配观测卫星`),
    decision('rule-epoch', constraints.epochAgeDays <= 14, `星历年龄${constraints.epochAgeDays}天 / 阈值14天`),
    decision('rule-sunlight', !constraints.night, constraints.night ? '夜间光学对地观测不满足光照条件' : '所选窗口满足本场景光照条件'),
    ...[...plan.windows, ...plan.excludedWindows].filter(window => window.kind === 'GROUND').map(window => decision('rule-cloud', Number(window.cloudPercent) <= constraints.maxCloudPercent, `云量${window.cloudPercent}% / 上限${constraints.maxCloudPercent}%`, window.id)),
    ...plan.windows.filter(window => window.kind === 'GROUND').map(window => decision('rule-resolution', Number(window.resolutionM) <= 1, `分辨率${window.resolutionM}m / 要求1m`, window.id)),
  ]
  call(result, 'planning-ontology-facts', { releaseRef: result.ontology.releaseRef, targetId: plan.targetId }, { facts: result.ontology.facts, relations: result.ontology.relations })
  call(result, 'planning-ontology-rules', { releaseRef: result.ontology.releaseRef, planId: plan.id }, { decisions: result.ontology.decisions })
}
function summarize(plan: PlanningPlan) {
  const satellite = (id: number) => planningSeed.assets.satellites.find(item => item.id === id)?.name ?? String(id)
  const lines = plan.windows.map(window => `| ${window.id} | ${satellite(window.satelliteId)}（${window.satelliteId}） | ${window.startTime.slice(11, 19)}—${window.endTime.slice(11, 19)} | ${window.durationSec}秒 | ${window.score} | ${window.cloudPercent===undefined?'—':`云量${window.cloudPercent}% / ${window.resolutionM}m`} |`)
  return `## ${plan.kind === 'SPACE' ? '对空' : '对地'}观测：${plan.targetName}\n\n- 目标：\`${plan.targetId}\`\n- 日期：${plan.planningDate}，北京时间（UTC+08:00）\n\n${lines.length?'| 窗口 | 卫星 | 时间 | 时长 | 评分 | 成像条件 |\n|---|---|---|---|---|---|\n'+lines.join('\n'):'没有满足条件的观测窗口。'}${plan.excludedWindows.length ? `\n\n### 排除项\n\n${plan.excludedWindows.map(window => `- **${window.id}**：${window.exclusionReason ?? '不满足任务约束'}`).join('\n')}` : ''}\n\n**推荐窗口：${plan.recommendedWindowId ?? '无'}**`
}
function artifactsFor(plan: PlanningPlan, runId: string, ontology: PlanningOntology | null): PlanningArtifact[] {
  const csv = ['windowId,kind,satelliteId,targetId,startTime,endTime,durationSec,score', ...plan.windows.map(window => [window.id, window.kind, window.satelliteId, window.targetId, window.startTime, window.endTime, window.durationSec, window.score].join(','))].join('\r\n')
  const report = JSON.stringify({ mode: 'MOCK', source: planningSeed.dataOrigin, runId, plan, ontology: ontology?.releaseRef ?? null }, null, 2)
  return [{ name: '观测窗口.csv', mediaType: 'text/csv', content: '\uFEFF' + csv }, { name: '规划报告.json', mediaType: 'application/json', content: report }].map((artifact, index) => ({ ...artifact, id: `${runId}-artifact-${index + 1}`, size: new TextEncoder().encode(artifact.content).length, downloadUrl: `data:${artifact.mediaType};charset=utf-8,${encodeURIComponent(artifact.content)}` }))
}
function remember(result: PlanningResponse) {
  planningState.results[result.runId] = copy(result)
  return result
}
export function planningTurn(appCode: string, config: Row, message: string, conversationId: string, runId: string): PlanningResponse {
  const key = `${appCode}:${conversationId}`
  const conversation = planningState.conversations[key] ??= {}
  const result: PlanningResponse = { runId, conversationId, reply: '', status: 'SUCCEEDED', toolCalls: [], ontology: ontologyFor(config), artifacts: [], promptSnapshot: String(config.systemPrompt ?? planningSeed.systemPrompt) }
  const text = message.trim()
  const needs = (reply: string) => { result.status = 'NEEDS_INPUT'; result.reply = reply; return remember(result) }
  const dates = text.match(/\d{4}-\d{2}-\d{2}/g) ?? []
  if (dates.some(date => date !== planningSeed.planningDate)) return needs(`当前演示场景准备的是${planningSeed.planningDate} 08:00—12:00（北京时间）。请使用该日期或示例问法；其他日期没有对应Mock结果。`)
  const groundCode = text.match(/\bGT-[A-Z0-9]+-\d+\b/i)?.[0]
  const spaceCode = text.match(/(?:空间目标|目标ID|目标编号)\s*[:：]?\s*(\d+)/)?.[1]
  if (groundCode && !planningSeed.assets.groundTargets.some(target => target.id === groundCode.toUpperCase())) return needs(`目标目录未找到${groundCode}，请选择GT-QH-01（青海湖）或GT-PY-02（鄱阳湖）。`)
  if (spaceCode && !planningSeed.assets.spaceTargets.some(target => String(target.id) === spaceCode)) return needs(`空间目标目录未找到${spaceCode}，请先查询目标目录并选择具体目标。`)
  if (/UNKNOWN|未知目标|99999/i.test(text)) return needs('没有找到该目标，请先查询目标目录并选择具体空间目标或地面目标。')
  if (!text) return needs('请先输入目标与规划要求，或选择一条示例问法。')

  if (/取消.*任务/.test(text)) {
    const explicit = text.match(/DEMO-[A-Z0-9-]+/)?.[0]
    const task = planningState.tasks[explicit ?? conversation.lastTaskId ?? '']
    if (!task) return needs('请先提交任务，或给出本会话中存在的任务ID。')
    if (!task.shared && task.ownerConversationKey !== key) return needs('该任务不属于当前会话，请使用本会话任务或共享演示任务。')
    if (['SUCCEEDED', 'CANCELLED'].includes(task.status)) { result.status = 'BLOCKED'; result.task = copy(task); result.reply = `任务${task.taskId}已${task.status === 'SUCCEEDED' ? '完成' : '取消'}，不能再次取消。`; return remember(result) }
    result.plan = copy(task.plan)
    result.approval = { runId, nodeId: 'planning-confirm', status: 'PENDING', summary: `取消任务${task.taskId}`, action: 'CANCEL', taskId: task.taskId }
    result.status = 'WAITING_APPROVAL'; result.reply = `取消${task.taskId}需要人工确认；确认前任务状态保持${task.status}。`
    planningState.pending[runId] = { conversationKey: key, response: copy(result) }
    return remember(result)
  }
  if (/提交|执行这套|执行该方案/.test(text)) {
    if (!conversation.plan?.windows.length) return needs('请先生成一套有可用窗口的规划，再提交任务。')
    if (conversation.pendingRunId && planningState.pending[conversation.pendingRunId]) return copy(planningState.pending[conversation.pendingRunId].response)
    result.plan = copy(conversation.plan)
    if (!result.plan.downlinkChecked) {
      result.plan.downlinks = copy(planningSeed.downlinks.filter(link => result.plan!.windows.some(window => window.satelliteId === link.satelliteId && Date.parse(link.startTime) > Date.parse(window.endTime) && link.capacityGb >= window.dataGb)))
      result.plan.downlinkChecked = true
      call(result, 'planning-downlink-validate', { planId: result.plan.id }, { covered: result.plan.downlinks.length > 0, windows: result.plan.downlinks })
    }
    if (!result.plan.downlinks.length) return needs('所选方案没有满足时序与容量的下传窗口，请先换用站点或观测方案。')
    result.plan.windows = result.plan.windows.filter(window => result.plan!.downlinks.some(link => link.satelliteId === window.satelliteId))
    rulesFor(result, result.plan)
    result.approval = { runId, nodeId: 'planning-confirm', status: 'PENDING', summary: `提交${result.plan.targetName}的${result.plan.windows.length}个观测窗口`, action: 'SUBMIT' }
    result.status = 'WAITING_APPROVAL'
    result.reply = `已冻结规划${result.plan.id}，等待人工确认。\n${summarize(result.plan)}\n确认后才会受理任务；当前尚未生成任务或产生调度效果。`
    call(result, 'planning-task-submit', { plan: result.plan }, { accepted: false, reason: '等待人工确认' }, 'WAITING_APPROVAL')
    conversation.pendingRunId = runId
    planningState.pending[runId] = { conversationKey: key, response: copy(result) }
    return remember(result)
  }
  if (/任务.*(结果|状态|进度)|查询刚才|查看刚才/.test(text)) {
    const explicit = text.match(/DEMO-[A-Z0-9-]+/)?.[0]
    const task = planningState.tasks[explicit ?? conversation.lastTaskId ?? '']
    if (!task) return needs('当前会话还没有已受理任务，请先规划、提交并完成确认。')
    if (!task.shared && task.ownerConversationKey !== key) return needs('该任务不属于当前会话，请使用本会话任务或共享演示任务。')
    result.task = copy(task); result.plan = copy(task.plan); result.artifacts = copy(task.artifacts)
    call(result, 'planning-task-status', { taskId: task.taskId }, { status: task.status, progress: task.status === 'SUCCEEDED' ? 100 : 50 })
    call(result, 'planning-task-result', { taskId: task.taskId }, { plan: task.plan, artifacts: task.artifacts })
    if (task.ontology !== undefined) result.ontology = copy(task.ontology)
    result.reply = `任务${task.taskId}${task.status === 'SUCCEEDED' ? '已完成' : `状态为${task.status}`}。\n${summarize(task.plan)}${task.artifacts.length ? '\n可下载观测窗口CSV与规划报告。' : '\n当前没有已完成的结果制品。'}`
    return remember(result)
  }
  if (/导出|下载.*(报告|CSV|制品)/i.test(text)) {
    if (!conversation.plan) return needs('请先生成观测规划，再导出窗口与报告。')
    result.plan = copy(conversation.plan); result.artifacts = artifactsFor(result.plan, runId, result.ontology)
    call(result, 'planning-artifact-export', { planId: result.plan.id }, { files: result.artifacts.map(artifact => ({ name: artifact.name, size: artifact.size })) })
    result.reply = `已生成${result.plan.targetName}的观测窗口CSV与规划报告，包含窗口、约束、下传及本体引用。`
    return remember(result)
  }
  if (/下传|数传|最优方案/.test(text)) {
    if (!conversation.plan?.windows.length) return needs('请先生成观测规划，再校验下传时序和容量。')
    const plan = copy(conversation.plan)
    const station = /喀什/.test(text) ? 'ST-KS-01' : /密云/.test(text) ? 'ST-MY-01' : undefined
    plan.downlinks = planningSeed.downlinks.filter(link => (!station || link.stationId === station) && plan.windows.some(window => window.satelliteId === link.satelliteId && Date.parse(link.startTime) > Date.parse(window.endTime) && link.capacityGb >= window.dataGb))
    plan.downlinkChecked = true
    result.plan = plan
    call(result, 'planning-downlink-plan', { satelliteIds: plan.windows.map(window => window.satelliteId), stationId: station ?? 'ALL' }, { windows: plan.downlinks })
    call(result, 'planning-downlink-validate', { planId: plan.id, dataGb: plan.windows.map(window => window.dataGb) }, { covered: plan.downlinks.length > 0, windows: plan.downlinks })
    rulesFor(result, plan)
    if (result.ontology?.releaseRef.releaseId === planningSeed.releaseRef.releaseId) {
      result.ontology.decisions.push({ ruleId: 'rule-downlink', name: '观测下传时序与容量', result: plan.downlinks.length ? 'HOLDS' : 'NOT_HOLDS', reason: plan.downlinks.length ? '下传开始晚于观测结束，容量足够' : '所选地面站没有覆盖本方案的后续下传窗口' })
      const ruleCall = result.toolCalls.find(tool => tool.skillCode === 'planning-ontology-rules')
      if (ruleCall) ruleCall.output = { decisions: copy(result.ontology.decisions) }
    }
    const coveredIds = new Set(plan.downlinks.map(link => link.satelliteId))
    const feasible = plan.windows.filter(window => coveredIds.has(window.satelliteId))
    plan.recommendedWindowId = feasible.sort((a, b) => b.score - a.score)[0]?.id
    conversation.plan = copy(plan)
    result.reply = `${plan.downlinks.length ? '下传校验通过' : '下传校验未通过'}。\n${plan.downlinks.map(link => `${planningSeed.assets.stations.find(item => item.id === link.stationId)?.name}｜卫星${link.satelliteId}｜${link.startTime.slice(11,19)}—${link.endTime.slice(11,19)}｜容量${link.capacityGb}GB`).join('\n')}\n综合推荐${plan.recommendedWindowId ?? '无可执行窗口'}：观测顺序、站点可达与容量共同满足才可提交。`
    return remember(result)
  }
  if (/本体|规则|依据|语义|关系/.test(text) && !/规划.*(青海湖|STARLINK|鄱阳湖)/i.test(text)) {
    if (!result.ontology) { result.reply = '当前应用未选择本体。请在编排→对话配置中选择本体及正式版本后保存；当前回答不会附加本体事实或规则判定。'; return remember(result) }
    if (result.ontology.releaseRef.releaseId !== planningSeed.releaseRef.releaseId) { result.reply = `当前选择${result.ontology.releaseRef.spaceCode} ${result.ontology.releaseRef.version}（${result.ontology.releaseRef.releaseId}）。该版本未声明本观测规划场景的事实与规则，请选择“卫星与空间地面目标观测规划本体 v1”查看完整响应。`; return remember(result) }
    if (conversation.plan) { result.plan = copy(conversation.plan); rulesFor(result, result.plan) }
    else {
      call(result, 'planning-ontology-facts', { releaseRef: result.ontology.releaseRef }, { facts: result.ontology.facts, relations: result.ontology.relations })
      result.ontology.decisions = planningSeed.ontology.rules.map(rule => ({ ruleId: rule.id, name: rule.name, result: 'UNKNOWN', reason: '尚未选择具体规划，展示规则定义，未执行判定' }))
      call(result, 'planning-ontology-rules', { mode: 'DESCRIBE' }, { rules: planningSeed.ontology.rules })
    }
    result.reply = `当前本体：${result.ontology.name} ${result.ontology.releaseRef.version}（${result.ontology.releaseRef.releaseId}）。\n${result.ontology.relations.join('\n')}\n${planningSeed.ontology.rules.map(rule => `${rule.id}｜${rule.name}：${rule.description}`).join('\n')}\n事实、规则与引用随所选本体保存；模型负责解释，判定结果在右侧逐条显示。`
    return remember(result)
  }
  if (/列出|目录|可用.*卫星|有什么|有哪些/.test(text)) {
    for (const [code, output] of [['planning-satellite-query', planningSeed.assets.satellites], ['planning-payload-query', planningSeed.assets.payloads], ['planning-space-target-query', planningSeed.assets.spaceTargets], ['planning-ground-target-query', planningSeed.assets.groundTargets], ['planning-station-query', planningSeed.assets.stations], ['planning-defaults-read', { maxApproximationDays: 14, maxCloudPercent: 30, requiredResolutionM: 1 }]] as const) call(result, code, { scope: 'SCENARIO', planningDate: planningSeed.planningDate, ...(code === 'planning-payload-query' ? { satelliteIds: planningSeed.assets.satellites.map(satellite => satellite.id) } : {}) }, { items: output })
    if (result.ontology) call(result, 'planning-ontology-facts', { releaseRef: result.ontology.releaseRef }, { facts: result.ontology.facts })
    result.reply = `本场景业务资源（${planningSeed.planningDate}，北京时间）：\n卫星：${planningSeed.assets.satellites.map(satellite => `${satellite.name}(${satellite.id}，${satellite.supportedPayloads.join('/')})`).join('；')}。\n空间目标：${planningSeed.assets.spaceTargets.map(target => `${target.name}(${target.id})`).join('；')}。\n地面目标：${planningSeed.assets.groundTargets.map(target => `${target.name}(${target.id})`).join('；')}。\n地面站：${planningSeed.assets.stations.map(station => `${station.name}(${station.id})`).join('；')}。\n可继续选择对空规划或对地规划，再校验下传并提交。`
    return remember(result)
  }

  const ground = /青海湖|鄱阳湖|GT-QH-01|GT-PY-02|对地|地面目标/.test(text)
  const space = /STARLINK|空间目标|对空|8890|9002|遥感目标02/i.test(text)
  if (!ground && !space) return needs('请先说明空间目标或地面目标。可以使用“规划明天上午STARLINK-31091观测”或“规划青海湖对地观测”，再继续校验下传、提交和查询结果。')
  if (ground && !/青海湖|鄱阳湖|GT-QH-01|GT-PY-02/i.test(text)) return needs('请先选择具体地面目标，例如青海湖GT-QH-01或鄱阳湖GT-PY-02。')
  if (!ground && !/STARLINK|8890|9002|遥感目标02/i.test(text)) return needs('请先选择具体空间目标，例如STARLINK-31091（8890）或演示遥感目标02（9002）。')
  const kind = ground ? 'GROUND' : 'SPACE'
  const target = ground ? planningSeed.assets.groundTargets[/鄱阳湖|GT-PY-02/.test(text) ? 1 : 0] : planningSeed.assets.spaceTargets[/9002|遥感目标02/.test(text) ? 1 : 0]
  const onlySatellite = text.match(/(?:只用|仅用|卫星ID[：:]?)[^\d]{0,6}(\d+)/)?.[1]
  const selected = planningSeed.windows.filter(window => window.kind === kind && window.targetId === target.id && (!onlySatellite || window.satelliteId === Number(onlySatellite)))
  const night = /夜间|夜晚/.test(text) && ground
  const stale = /过期|超期/.test(text)
  const cloudLimit = Number(text.match(/云量[^\d]{0,5}(\d+(?:\.\d+)?)\s*%?/)?.[1] ?? 30)
  const valid = selected.filter(window => !night && !stale && (window.cloudPercent === undefined || window.cloudPercent <= Math.min(30, cloudLimit)))
  const effectiveCloudLimit = Math.min(30, cloudLimit)
  const excluded = selected.filter(window => !valid.includes(window)).map(window => ({ ...window, exclusionReason: night ? '夜间光学观测不满足光照条件' : stale ? '星历年龄20天超过14天' : `云量${window.cloudPercent}%超过${effectiveCloudLimit}%` }))
  const plan: PlanningPlan = { id: `PLAN-${++planningState.sequence}`, kind, targetId: target.id, targetName: target.name, planningDate: planningSeed.planningDate, windows: copy(valid), excludedWindows: copy(excluded), downlinks: [], recommendedWindowId: [...valid].sort((a, b) => b.score - a.score)[0]?.id, revision: 1, constraints: { maxCloudPercent: effectiveCloudLimit, epochAgeDays: stale ? 20 : 2, night, ...(onlySatellite ? { onlySatelliteId: Number(onlySatellite) } : {}) } }
  result.plan = plan
  call(result, kind === 'SPACE' ? 'planning-space-target-query' : 'planning-ground-target-query', { targetId: target.id }, { target })
  call(result, 'planning-payload-query', { satelliteIds: selected.map(window => window.satelliteId), payloadType: kind }, { profiles: planningSeed.assets.payloads.filter(payload => payload.payloadType === kind && (!onlySatellite || payload.satelliteId === Number(onlySatellite))) })
  call(result, kind === 'SPACE' ? 'planning-space-plan' : 'planning-ground-plan', { targetId: target.id, observerSatelliteIds: onlySatellite ? [Number(onlySatellite)] : selected.map(window => window.satelliteId), startTime: `${planningSeed.planningDate}T08:00:00+08:00`, endTime: `${planningSeed.planningDate}T12:00:00+08:00`, cloudLimit }, { windows: plan.windows, excludedWindows: plan.excludedWindows })
  rulesFor(result, plan)
  result.status = night ? 'NO_WINDOW' : stale || !selected.length ? 'BLOCKED' : valid.length ? 'SUCCEEDED' : 'NO_WINDOW'
  result.reply = night ? `没有满足条件的观测窗口：${target.name}夜间光学观测不满足光照约束。建议改到白天，或选择具备相应能力的其他载荷。` : stale ? '本次规划被星历时效规则阻断：演示星历年龄20天，超过14天阈值；请先更新星历再规划。' : !selected.length ? `卫星${onlySatellite ?? '所选卫星'}没有匹配${target.name}的${kind}载荷或场景窗口，请换用具备对应载荷的卫星。` : summarize(plan)
  if (!result.ontology) result.reply += '\n当前未选择本体，未加载本体事实与规则解释。'
  conversation.plan = copy(plan)
  return remember(result)
}

/** 显式工具调用以已校验参数执行；共享对话规划、任务及人工确认，避免表单变成无效展示。 */
export function planningSelectedTool(appCode: string, config: Row, code: string, parameters: Row, conversationId: string, runId: string): PlanningResponse {
  const key = `${appCode}:${conversationId}`
  const conversation = planningState.conversations[key] ??= {}
  const result: PlanningResponse = { runId, conversationId, reply: '', status: 'SUCCEEDED', toolCalls: [], ontology: ontologyFor(config), artifacts: [], promptSnapshot: String(config.systemPrompt ?? planningSeed.systemPrompt) }
  const name = planningTool(code)?.name ?? code
  const complete = (output: unknown, reply?: string) => {
    call(result, code, parameters, output)
    result.reply = reply ?? `### ${name}\n\n\`\`\`json\n${JSON.stringify(output, null, 2)}\n\`\`\``
    return remember(result)
  }
  const listIds = (field: string) => Array.isArray(parameters[field]) ? (parameters[field] as unknown[]).map(String) : []
  const page = <T>(rows: T[]) => {
    const pageNumber = Number(parameters.pageNumber ?? 1), pageSize = Number(parameters.pageSize ?? 20)
    return { items: rows.slice((pageNumber - 1) * pageSize, pageNumber * pageSize), total: rows.length, pageNumber, pageSize }
  }
  const matches = (item: { id: string | number; name: string }) =>
    (!parameters.targetId || String(item.id) === String(parameters.targetId)) &&
    (!parameters.keyword || `${item.id} ${item.name}`.toLowerCase().includes(String(parameters.keyword).toLowerCase()))
  const plan = () => {
    const current = conversation.plan
    if (!current || parameters.planId && parameters.planId !== current.id) throw Error('请先在当前会话生成规划，并选择该规划编号。')
    return current
  }
  if (code === 'planning-satellite-query') return complete(page(planningSeed.assets.satellites.filter(item =>
    (!parameters.satelliteId || item.id === parameters.satelliteId) && (!listIds('satelliteIds').length || listIds('satelliteIds').includes(String(item.id))) &&
    (!parameters.payloadType || item.supportedPayloads.includes(String(parameters.payloadType))) && (!parameters.keyword || item.name.includes(String(parameters.keyword))))))
  if (code === 'planning-payload-query') return complete({ profiles: planningSeed.assets.payloads.filter(item =>
    (!listIds('satelliteIds').length || listIds('satelliteIds').includes(String(item.satelliteId))) && (!parameters.satelliteId || item.satelliteId === parameters.satelliteId) && (!parameters.payloadType || item.payloadType === parameters.payloadType)) })
  if (code === 'planning-space-target-query' || code === 'planning-ground-target-query') return complete(page((code === 'planning-space-target-query' ? planningSeed.assets.spaceTargets : planningSeed.assets.groundTargets).filter(matches)))
  if (code === 'planning-station-query') return complete(page(planningSeed.assets.stations.filter(item => (!parameters.stationId || item.id === parameters.stationId) && (!parameters.keyword || item.name.includes(String(parameters.keyword))))))
  if (code === 'planning-defaults-read') return complete({ planningDate: planningSeed.planningDate, maxApproximationDays: 14, maxCloudPercent: 30, requiredResolutionM: 1, timeZone: planningSeed.timeZone })
  if (code === 'planning-ground-plan' || code === 'planning-space-plan') {
    const ground = code === 'planning-ground-plan'
    const target = (ground ? planningSeed.assets.groundTargets : planningSeed.assets.spaceTargets).find(item => String(item.id) === String(parameters.targetId))
    if (!target) throw Error('所选目标不在当前工具对应的目标目录中，请先查询目标。')
    const observers = listIds('observerSatelliteIds')
    if (!observers.length || observers.some(id => !planningSeed.assets.satellites.some(item => String(item.id) === id))) throw Error('请选择目录中存在的观测卫星。')
    const start = Date.parse(String(parameters.startTime)), end = Date.parse(String(parameters.endTime))
    if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) throw Error('规划开始、结束时间必须有效，且结束时间晚于开始时间。')
    if (!String(parameters.startTime).startsWith(planningSeed.planningDate) || !String(parameters.endTime).startsWith(planningSeed.planningDate)) throw Error(`当前场景提供${planningSeed.planningDate}的业务窗口，请选择该日期。`)
    const response = planningTurn(appCode, config, `规划${ground ? '地面目标' : '空间目标'}${target.id}（${target.name}），云量不超过${Number(parameters.cloudLimit ?? 30)}%`, conversationId, runId)
    if (!response.plan) return response
    const current = response.plan
    const excluded = current.windows.filter(window => !observers.includes(String(window.satelliteId)) || Date.parse(window.startTime) < start || Date.parse(window.endTime) > end)
    current.excludedWindows.push(...excluded.map(window => ({ ...window, exclusionReason: !observers.includes(String(window.satelliteId)) ? '未选择该观测卫星' : '窗口超出输入的完整时间范围' })))
    current.windows = current.windows.filter(window => !excluded.includes(window))
    current.excludedWindows = current.excludedWindows.filter(window => observers.includes(String(window.satelliteId)))
    current.recommendedWindowId = [...current.windows].sort((a,b) => b.score-a.score)[0]?.id
    conversation.plan = copy(current)
    response.toolCalls = response.toolCalls.filter(tool => tool.skillCode !== 'planning-ontology-rules')
    const selected = response.toolCalls.find(tool => tool.skillCode === code)
    if (selected) { selected.input = copy(parameters); selected.output = { windows: current.windows, excludedWindows: current.excludedWindows } }
    rulesFor(response, current)
    response.status = current.windows.length ? 'SUCCEEDED' : 'NO_WINDOW'
    response.reply = `### ${name}\n\n${current.windows.length ? summarize(current) : '输入的卫星、时间和云量条件下没有合规窗口，请调整参数重试。'}`
    return remember(response)
  }
  if (code === 'planning-downlink-plan') {
    const ids = listIds('satelliteIds')
    const windows = copy(planningSeed.downlinks.filter(link => (!ids.length || ids.includes(String(link.satelliteId))) && (!parameters.stationId || link.stationId === parameters.stationId) && (!parameters.startTime || Date.parse(link.startTime) >= Date.parse(String(parameters.startTime))) && (!parameters.endTime || Date.parse(link.endTime) <= Date.parse(String(parameters.endTime)))))
    if (conversation.plan) {
      conversation.plan.downlinks = windows.filter(link => conversation.plan!.windows.some(window => window.satelliteId === link.satelliteId && Date.parse(link.startTime) > Date.parse(window.endTime) && link.capacityGb >= window.dataGb))
      conversation.plan.downlinkChecked = true; result.plan = copy(conversation.plan)
    }
    return complete({ windows, total: windows.length })
  }
  if (code === 'planning-downlink-validate') {
    const current = plan()
    const links = current.downlinkChecked ? current.downlinks : planningSeed.downlinks
    const checks = current.windows.map(window => ({ windowId: window.id, covered: links.some(link => link.satelliteId === window.satelliteId && Date.parse(link.startTime) > Date.parse(window.endTime) && link.capacityGb >= window.dataGb) }))
    result.plan = copy(current)
    const covered = checks.length > 0 && checks.every(check => check.covered)
    if (!covered) result.status = 'BLOCKED'
    return complete({ covered, checks })
  }
  if (code === 'planning-ontology-facts' || code === 'planning-ontology-rules') {
    if (!result.ontology || result.ontology.releaseRef.releaseId !== planningSeed.releaseRef.releaseId) throw Error('请先为当前智能体选择观测规划本体的对应正式版本。')
    if (parameters.releaseRef && row(parameters.releaseRef).releaseId !== result.ontology.releaseRef.releaseId) throw Error('参数中的本体版本与当前智能体绑定不一致。')
    if (code === 'planning-ontology-facts') return complete({ releaseRef: result.ontology.releaseRef, facts: result.ontology.facts.filter(fact => (!parameters.objectType || fact.objectType === parameters.objectType) && (!parameters.stableId || fact.stableId === parameters.stableId)), relations: result.ontology.relations })
    if (parameters.planId || conversation.plan) { result.plan = copy(plan()); rulesFor(result, result.plan) }
    else result.ontology.decisions = planningSeed.ontology.rules.map(rule => ({ruleId:rule.id,name:rule.name,result:'UNKNOWN',reason:'尚未输入规划，仅展示规则定义'}))
    result.toolCalls = []
    return complete({ rules: planningSeed.ontology.rules.filter(rule => !listIds('ruleIds').length || listIds('ruleIds').includes(rule.id)), decisions: result.ontology.decisions })
  }
  if (code === 'planning-task-submit') {
    const current = plan()
    if (JSON.stringify(parameters.plan) !== JSON.stringify(current)) throw Error('提交参数必须与当前已生成的完整规划一致；请重新载入参数模板。')
    return planningTurn(appCode, config, '提交当前规划', conversationId, runId)
  }
  if (code.startsWith('planning-task-') || code === 'planning-selection-save') {
    const task = planningState.tasks[String(parameters.taskId)]
    if (!task || !task.shared && task.ownerConversationKey !== key) throw Error('任务不存在或不属于当前会话。')
    if (code === 'planning-task-cancel') {
      const response = planningTurn(appCode, config, `取消任务${task.taskId}`, conversationId, runId)
      call(response, code, parameters, { taskId: task.taskId, accepted: false, reason: response.reply }, response.status)
      return remember(response)
    }
    result.task = copy(task); result.plan = copy(task.plan)
    if (code === 'planning-selection-save') {
      if (task.status !== 'SUCCEEDED') throw Error('任务完成后才可确认结果窗口选择。')
      const ids = listIds('windowIds')
      if (!ids.length || ids.some(id => !task.plan.windows.some(window => window.id === id))) throw Error('请选择当前任务结果中存在的窗口。')
      task.plan.recommendedWindowId = ids[0]; task.plan.revision++; result.plan = copy(task.plan); result.task = copy(task)
      return complete({ taskId: task.taskId, selectedWindowIds: ids, revision: task.plan.revision })
    }
    if (code === 'planning-task-result') result.artifacts = copy(task.artifacts)
    return complete(code === 'planning-task-status' ? { taskId: task.taskId, status: task.status, progress: task.status === 'SUCCEEDED' ? 100 : 50 } : { taskId: task.taskId, status: task.status, plan: task.plan, artifacts: task.artifacts })
  }
  if (code === 'planning-artifact-export') { result.plan = copy(plan()); result.artifacts = artifactsFor(result.plan, runId, result.ontology); return complete({ files: result.artifacts.map(file => ({name:file.name,size:file.size})) }, `### 规划结果已导出\n\n${result.plan.targetName}的窗口CSV与规划报告已生成，可在下方下载。`) }
  throw Error('当前工具尚未配置可执行的业务行为。')
}

export function decidePlanningRun(runId: string, approved: boolean): { response?: PlanningResponse; conflict?: string } | undefined {
  const pending = planningState.pending[runId]
  if (!pending) return planningState.results[runId]?.approval ? { conflict: '该确认已处理，不能覆盖终态决定。' } : undefined
  const result = copy(pending.response)
  const approval = result.approval!
  const conversation = planningState.conversations[pending.conversationKey]
  approval.status = approved ? 'APPROVED' : 'REJECTED'
  result.status = approved ? 'SUCCEEDED' : 'REJECTED'
  if (approved && approval.action === 'SUBMIT' && result.plan) {
    const taskId = `DEMO-OBS-${String(++planningState.sequence).padStart(4, '0')}`
    const artifacts = artifactsFor(result.plan, runId, result.ontology)
    const task: PlanningTask = { taskId, status: 'SUCCEEDED', plan: copy(result.plan), sourceRunId: runId, stages: ['SUBMITTED', 'RUNNING', 'SUCCEEDED'], artifacts, ownerConversationKey: pending.conversationKey, ontology: copy(result.ontology) }
    planningState.tasks[taskId] = task; conversation.lastTaskId = taskId
    result.task = copy(task); result.artifacts = artifacts
    result.toolCalls = result.toolCalls.filter(tool => tool.status !== 'WAITING_APPROVAL')
    call(result, 'planning-task-submit', { plan: result.plan, approved: true }, { taskId })
    call(result, 'planning-task-status', { taskId }, { status: 'SUCCEEDED', stages: task.stages })
    call(result, 'planning-task-result', { taskId }, { plan: task.plan })
    call(result, 'planning-selection-save', { taskId, windowIds: result.plan.windows.map(window => window.id) }, { saved: true })
    call(result, 'planning-artifact-export', { taskId }, { artifacts: artifacts.map(artifact => ({ name: artifact.name, size: artifact.size })) })
    if (result.ontology?.releaseRef.releaseId === planningSeed.releaseRef.releaseId) result.ontology.decisions.push({ ruleId: 'rule-approval', name: '任务提交人工确认', result: 'HOLDS', reason: '冻结计划已获人工批准，演示任务受理并完成' })
    task.ontology = copy(result.ontology)
    result.task = copy(task)
    result.reply = `规划任务${taskId}已完成。\n${summarize(task.plan)}\n已保存窗口选择并生成观测窗口CSV与规划报告。`
  } else if (approved && approval.action === 'CANCEL' && approval.taskId) {
    const task = planningState.tasks[approval.taskId]
    if (task) { task.status = 'CANCELLED'; task.stages.push('CANCELLED'); result.task = copy(task) }
    call(result, 'planning-task-cancel', { taskId: approval.taskId }, { status: 'CANCELLED' })
    result.reply = `任务${approval.taskId}已取消。`
  } else result.reply = '已拒绝该操作；没有受理任务，也没有产生业务调度效果。'
  delete planningState.pending[runId]
  delete conversation.pendingRunId
  remember(result)
  return { response: result }
}

export function planningRunNodes(result: PlanningResponse) {
  return [
    { id: 'planning-input', nodeDefId: 'planning-input', label: '目标与需求解析', status: 'SUCCEEDED', duration: 30 },
    ...result.toolCalls.map(tool => ({ id: tool.id, nodeDefId: tool.id, label: tool.name, status: tool.status, duration: 120, input: tool.input, output: tool.output })),
    ...(result.approval ? [{ id: result.approval.nodeId, nodeDefId: result.approval.nodeId, label: '人工确认冻结计划', status: result.approval.status === 'PENDING' ? 'WAITING_APPROVAL' : result.approval.status === 'APPROVED' ? 'SUCCEEDED' : 'REJECTED', duration: 0 }] : []),
    { id: 'planning-output', nodeDefId: 'planning-output', label: '规划解释与结果', status: result.status === 'WAITING_APPROVAL' ? 'PENDING' : ['REJECTED','CANCELLED'].includes(result.status) ? result.status : 'SUCCEEDED', duration: 80 },
  ]
}
export function planningRunEvents(result: PlanningResponse) {
  return [...planningRunNodes(result).map((node, index) => ({ seq: String(index + 1), type: 'NODE_FINISHED', data: { nodeId: node.id, nodeDefId: node.nodeDefId, status: node.status } })), { seq: String(planningRunNodes(result).length + 1), type: result.status === 'WAITING_APPROVAL' ? 'RUN_WAITING_APPROVAL' : 'RUN_FINISHED', data: { status: ['WAITING_APPROVAL','REJECTED','CANCELLED'].includes(result.status) ? result.status : 'SUCCEEDED', output: { text: result.reply } } }]
}

export function cancelPlanningRun(runId: string) {
  const result = planningState.results[runId]
  if (!result || result.status !== 'WAITING_APPROVAL') return
  result.status = 'CANCELLED'; result.reply = '运行已取消，冻结计划未被提交。'
  if (result.approval) result.approval.status = 'REJECTED'
  const pending = planningState.pending[runId]
  if (pending) delete planningState.conversations[pending.conversationKey].pendingRunId
  delete planningState.pending[runId]
}
