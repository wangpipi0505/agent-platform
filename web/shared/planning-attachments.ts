/** Single-target file inputs are resolved to the same explicit constraints as typed planning requests. */
import { readMockArtifact } from './mock-artifacts'
import { planningSeed } from './satellite-planning'

type Parameters = Record<string, unknown>
type Body = { message?: unknown; attachments?: unknown; parameters?: unknown }
const fields = ['targetId', 'targetType', 'planningDate', 'maxCloudPercent', 'onlySatelliteId', 'epochAgeDays'] as const
const copy = (value: unknown): Parameters => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw Error('运行参数必须是JSON对象')
  return value as Parameters
}
const templates = [
  ['planning-file-upload', '上传规划文件', false],
  ['planning-file-parse', '解析目标与规划约束', true],
  ['planning-file-export', '导出规划文件', true],
  ['planning-file-download', '下载规划制品', true],
] as const
export const planningFileSkills = templates.map(([code, name, readonly]) => ({
  code, name, description: `${name}；目标CSV当前仅支持一条目标，多目标请分批上传。`,
  type: 'HTTP', kind: 'HTTP', status: 'PUBLISHED', currentVersion: 1, version: 1,
  bizCategories: ['GENERAL', 'SPACE_OBSERVATION'], businessSystemCode: 'SPACE',
  accessPolicy: { visibility: 'PUBLIC', viewRoles: ['ADMIN', 'EDITOR', 'SPACE_OPERATOR', 'VIEWER'], configureRoles: ['ADMIN', 'EDITOR'], invokeRoles: ['ADMIN', 'EDITOR', 'SPACE_OPERATOR'] },
  endpoint: `mock://planning-files/${code}`, method: readonly ? 'GET' : 'POST', timeoutMs: 30000,
  execution: { readonly, sideEffect: !readonly, idempotent: true, cancellable: true },
  readonly, sideEffect: !readonly, guard: { roles: [], attributes: {} },
  inputSchema: { type: 'object', properties: { artifactId: { type: 'string' }, parameters: { type: 'object' } } },
  outputSchema: { type: 'object', properties: { artifactId: { type: 'string' }, parameters: { type: 'object' }, bytes: { type: 'integer' } } },
}))
function target(id: unknown) {
  return [...planningSeed.assets.groundTargets, ...planningSeed.assets.spaceTargets].find(item => String(item.id) === String(id))
}
function number(value: unknown, label: string, max = Infinity) {
  if ((typeof value !== 'number' && typeof value !== 'string') || String(value).trim() === '') throw Error(`${label}必须是有效数值`)
  const result = Number(value)
  if (!Number.isFinite(result) || result < 0 || result > max) throw Error(`${label}必须在0至${max === Infinity ? '有限正数' : max}之间`)
  return result
}
function normalize(source: Parameters, label: string): Parameters {
  const result: Parameters = {}
  for (const key of fields) if (source[key] !== undefined && source[key] !== '') result[key] = source[key]
  if (source.satelliteId !== undefined && source.satelliteId !== '') {
    if (result.onlySatelliteId !== undefined && Number(result.onlySatelliteId) !== Number(source.satelliteId)) throw Error(`${label}的satelliteId与onlySatelliteId冲突`)
    result.onlySatelliteId = source.satelliteId
  }
  if (result.targetId !== undefined) {
    const selected = target(result.targetId)
    if (!selected) throw Error(`${label}的目标ID ${String(result.targetId)}不存在，请使用目标目录中的ID`)
    result.targetId = selected.id
    if (result.targetType !== undefined && String(result.targetType).toUpperCase() !== selected.type) throw Error(`${label}的targetType与目标ID不匹配`)
    result.targetType = selected.type
  } else if (result.targetType !== undefined) {
    result.targetType = String(result.targetType).toUpperCase()
    if (!['GROUND', 'SPACE'].includes(String(result.targetType))) throw Error(`${label}的targetType仅支持GROUND或SPACE`)
  }
  if (result.planningDate !== undefined) {
    const date = String(result.planningDate)
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || !Number.isFinite(Date.parse(date)) || new Date(date).toISOString().slice(0, 10) !== date) throw Error(`${label}的planningDate必须为有效的YYYY-MM-DD日期`)
    if (date !== planningSeed.planningDate) throw Error(`当前场景仅提供${planningSeed.planningDate}的规划窗口，请调整日期后重试`)
    result.planningDate = date
  }
  if (result.maxCloudPercent !== undefined) result.maxCloudPercent = number(result.maxCloudPercent, `${label}的最大云量`, 100)
  if (result.epochAgeDays !== undefined) result.epochAgeDays = number(result.epochAgeDays, `${label}的星历年龄天数`)
  if (result.onlySatelliteId !== undefined) {
    const id = number(result.onlySatelliteId, `${label}的卫星ID`)
    if (!Number.isInteger(id) || !planningSeed.assets.satellites.some(item => item.id === id)) throw Error(`${label}的卫星ID ${id}不存在`)
    result.onlySatelliteId = id
  }
  return result
}
function merge(destination: Parameters, source: Parameters, label: string) {
  for (const [key, value] of Object.entries(source)) {
    if (destination[key] !== undefined && String(destination[key]) !== String(value)) throw Error(`${label}与已有输入的${key}冲突（${String(destination[key])} / ${String(value)}），请统一后重试`)
    destination[key] = value
  }
}
/** Handles CSV quoting as well as the downloadable unquoted single-row template. */
function csv(text: string, name: string) {
  const rows: string[][] = []; let row: string[] = [], field = '', quoted = false
  for (let index = 0; index < text.length; index++) {
    const char = text[index]
    if (char === '"') {
      if (quoted && text[index + 1] === '"') { field += '"'; index++ } else quoted = !quoted
    } else if (!quoted && (char === ',' || char === '\n' || char === '\r')) {
      row.push(field.trim()); field = ''
      if (char !== ',') { if (row.some(Boolean)) rows.push(row); row = []; if (char === '\r' && text[index + 1] === '\n') index++ }
    } else field += char
  }
  if (quoted) throw Error(`${name}的CSV引号没有闭合`)
  row.push(field.trim()); if (row.some(Boolean)) rows.push(row)
  if (rows.length < 2) throw Error(`${name}至少需要表头和一条目标记录`)
  if (rows.length > 2) throw Error(`${name}包含多个目标；当前仅支持单目标规划，请每次保留一行目标并分批上传`)
  const headers = rows[0]
  if (new Set(headers).size !== headers.length || !['targetId', 'targetType', 'planningDate'].every(key => headers.includes(key))) throw Error(`${name}表头必须包含targetId,targetType,planningDate且不能重复`)
  if (rows[1].length !== headers.length) throw Error(`${name}的数据列数与表头不一致`)
  const result = Object.fromEntries(headers.map((header, index) => [header, rows[1][index]]))
  if (['targetId', 'targetType', 'planningDate'].some(key => !result[key])) throw Error(`${name}的目标ID、目标类型和日期不能为空`)
  return result
}
function typedParameters(message: string): Parameters {
  const result: Parameters = {}
  const selected = [...planningSeed.assets.groundTargets, ...planningSeed.assets.spaceTargets].filter(item => {
    const aliases = String(item.id) === 'GT-QH-01' ? ['青海湖'] : String(item.id) === 'GT-PY-02' ? ['鄱阳湖'] : String(item.id) === '8890' ? ['STARLINK'] : ['遥感目标02']
    return [String(item.id), item.name, ...aliases].some(alias => message.toUpperCase().includes(alias.toUpperCase()))
  })
  if (selected.length > 1) throw Error('文本包含多个明确目标，请保留一个目标后再规划')
  if (selected.length) { result.targetId = selected[0].id; result.targetType = selected[0].type }
  else if (/对地|地面目标/.test(message)) result.targetType = 'GROUND'
  else if (/对空|空间目标/.test(message)) result.targetType = 'SPACE'
  const dates = [...new Set(message.match(/\d{4}-\d{2}-\d{2}/g) || [])]
  if (dates.length > 1) throw Error('文本包含多个规划日期，请明确一个日期')
  if (dates.length) result.planningDate = dates[0]
  const cloud = [...message.matchAll(/云量[^\d]{0,8}(\d+(?:\.\d+)?)\s*%?/g)].map(match => Number(match[1]))
  if (new Set(cloud).size > 1) throw Error('文本包含不同的云量限制，请统一后重试')
  if (cloud.length) result.maxCloudPercent = cloud[0]
  const satellite = message.match(/(?:只用|仅用|卫星ID[：:]?)[^\d]{0,6}(\d+)/)?.[1]
  if (satellite) result.onlySatelliteId = Number(satellite)
  return normalize(result, '对话文本')
}
/** 单文件解析可独立读取约束；只有生成规划时才要求已选定目标。 */
export async function parsePlanningArtifact(artifactId: string) {
  const file = await readMockArtifact(artifactId)
  if (!file) throw Error('文件不存在，请重新上传实际文件。')
  const text = file.content.replace(/^\uFEFF/, '').trim()
  if (!text) throw Error(`${file.name}内容为空`)
  let values: Parameters
  if (/\.csv$/i.test(file.name) || file.mediaType.includes('csv')) values = csv(text, file.name)
  else if (/\.(json|txt)$/i.test(file.name) || file.mediaType.includes('json') || file.mediaType.startsWith('text/')) {
    try { values = copy(JSON.parse(text)) } catch { throw Error(`${file.name}必须为JSON对象；星历参数TXT支持satelliteId、epochAgeDays及轨道参数，请使用下载模板`) }
  } else throw Error(`${file.name}格式不支持，请上传单目标CSV、约束JSON或星历参数TXT`)
  const parameters = normalize(values, file.name)
  if (!Object.keys(parameters).length) throw Error(`${file.name}未包含可用规划字段，请使用目标、约束或星历参数模板`)
  return { file, parameters }
}

export async function preparePlanningAttachments(body: Body): Promise<{ message: string; toolCalls: any[]; parameters: Parameters; attachments: any[] }> {
  const message = typeof body.message === 'string' ? body.message : ''
  const references = body.attachments === undefined ? [] : body.attachments
  if (!Array.isArray(references)) throw Error('attachments必须是附件引用数组')
  const entered = body.parameters === undefined ? {} : copy(body.parameters)
  if (!references.length && !Object.keys(entered).length) return { message, toolCalls: [], parameters: {}, attachments: [] }
  if (!references.length && /提交|下传|数传|本体|依据|规则|取消|结果|进度|导出|下载/.test(message) && !/规划.*(目标|青海湖|鄱阳湖|STARLINK)/i.test(message)) return {message,toolCalls:[],parameters:entered,attachments:[]}
  const parameters: Parameters = {}; const attachments: any[] = []; const toolCalls: any[] = []
  merge(parameters, normalize(entered, '运行参数'), '运行参数')
  for (const reference of references) {
    if (!reference || typeof reference.artifactId !== 'string' || !reference.artifactId) throw Error('附件缺少artifactId，请重新上传')
    const { file, parameters: parsed } = await parsePlanningArtifact(reference.artifactId)
    merge(parameters, parsed, `附件${file.name}`)
    attachments.push({ artifactId: file.artifactId, name: file.name, mediaType: file.mediaType, bytes: file.bytes })
    for (const [code, name, input, output] of [
      ['planning-file-upload', '读取已上传规划文件', { artifactId: file.artifactId }, { name: file.name, bytes: file.bytes, contentLoaded: true }],
      ['planning-file-parse', '解析目标与规划约束', { artifactId: file.artifactId, name: file.name }, { parameters: parsed, rows: 1, supportedTargetCount: 1 }],
    ] as const) toolCalls.push({ id: `${code}-${toolCalls.length + 1}`, skillCode: code, name, status: 'SUCCEEDED', input, output })
  }
  merge(parameters, typedParameters(message), '对话文本')
  if (!parameters.targetId && !typedParameters(message).targetId) throw Error('附件仅包含约束；请在文本或目标CSV中指定一个地面或空间目标')
  const selected = target(parameters.targetId)
  if (!selected) throw Error('请选择有效的规划目标')
  if (parameters.targetType !== selected.type) throw Error('目标类型与目标ID不一致，请核对文本和附件')
  parameters.planningDate ??= planningSeed.planningDate
  const command = [`规划${selected.type === 'GROUND' ? '地面目标' : '空间目标'}${selected.id}（${selected.name}）`, `日期${String(parameters.planningDate)}`]
  if (parameters.maxCloudPercent !== undefined) command.push(`云量不超过${String(parameters.maxCloudPercent)}%`)
  if (parameters.onlySatelliteId !== undefined) command.push(`只用卫星${String(parameters.onlySatelliteId)}`)
  if (parameters.epochAgeDays !== undefined) command.push(`星历年龄${String(parameters.epochAgeDays)}天${Number(parameters.epochAgeDays) > 14 ? '，星历过期' : ''}`)
  return { message: `${message}\n\n已核对文件及运行参数：${command.join('，')}。`, toolCalls, parameters, attachments }
}
