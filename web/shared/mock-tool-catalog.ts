/** Mock skill contracts describe operation inputs; business identity and authority come from the business source. */
import planningText from './satellite-planning.seed.json?raw'
import governanceText from './business-demo.seed.json?raw'

type Schema = Record<string, unknown>
type Contract = { inputSchema: Schema; parameterTemplate: Record<string, unknown> }
const record = (value: unknown): Schema => value && typeof value === 'object' && !Array.isArray(value) ? value as Schema : {}
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T
const planning = JSON.parse(planningText)
const governance = JSON.parse(governanceText)
const text = (title: string, description = title, options: Schema = {}): Schema => ({ type: 'string', title, description, ...options })
const integer = (title: string, minimum = 1, options: Schema = {}): Schema => ({ type: 'integer', title, description: title, minimum, ...options })
const list = (title: string, items: Schema, options: Schema = {}): Schema => ({ type: 'array', title, description: title, items, uniqueItems: true, ...options })
const object = (title: string, properties: Schema, required: string[] = [], options: Schema = {}): Schema => ({ type: 'object', title, description: title, properties, required, ...options })
const ids = (title: string, values: unknown[], defaults?: unknown[]) => list(title, { type: 'integer', enum: values }, { minItems: 1, ...(defaults ? { default: defaults } : {}) })
const satelliteIds = planning.assets.satellites.map((satellite: { id: number }) => satellite.id)
const spaceSatellites = planning.assets.satellites.filter((satellite: { supportedPayloads: string[] }) => satellite.supportedPayloads.includes('SPACE')).map((satellite: { id: number }) => satellite.id)
const keyword = text('搜索关键词', '按名称或编码筛选；留空返回全部')
const page = { pageNumber: integer('页码', 1, { default: 1 }), pageSize: integer('每页条数', 1, { maximum: 200, default: 20 }) }
const satelliteId = integer('观测卫星', 1, { enum: satelliteIds })
const payloadType = text('载荷类型', 'SPACE为空间观测，GROUND为对地观测', { enum: ['SPACE', 'GROUND'] })
const stationId = text('地面站', '留空使用全部可用地面站', { enum: planning.assets.stations.map((station: { id: string }) => station.id) })
const startTime = text('开始时间', '带时区的规划起始时间，必须早于结束时间', { format: 'date-time', default: `${planning.planningDate}T08:00:00+08:00` })
const endTime = text('结束时间', '带时区的规划结束时间，必须晚于开始时间', { format: 'date-time', default: `${planning.planningDate}T12:00:00+08:00` })
const planId = text('规划标识', '请先生成规划；自动带入当前会话规划标识', { minLength: 1 })
const taskId = text('任务标识', '填写已受理任务标识；可查询共享演示任务', { minLength: 1, default: 'DEMO-RUNNING-0001' })
const requestId = text('申请标识', '请先生成账号变更申请；自动带入当前会话申请标识', { minLength: 1 })
const accountId = text('业务账号标识', '从用户查询或创建账号结果中选择账号标识', { minLength: 1 })
const artifactId = text('文件制品标识', '上传文件或生成导出制品后填入真实 artifactId', { minLength: 1 })
const roleIds = list('申请授予的角色', text('目标角色', '仅表示申请目标角色；操作人的权限仍由业务源读取', { enum: governance.roles.filter((role: { status: string }) => role.status === 'ACTIVE').map((role: { id: string }) => role.id) }), { minItems: 1, default: ['ROLE_ANALYST'] })
const departmentId = text('目标部门', '账号所属部门；授权范围以业务源当前身份为准', { enum: governance.departments.map((department: { id: string }) => department.id), default: 'dept-a' })
const releaseRef = object('所选本体正式版本', {
  space: text('本体空间标识'), spaceCode: text('本体空间编码', undefined, { minLength: 1 }),
  releaseId: text('正式发布标识', undefined, { minLength: 1 }), version: text('本体版本', undefined, { minLength: 1 }),
  contentDigest: text('版本内容摘要', undefined, { minLength: 1 }),
}, ['spaceCode', 'releaseId', 'version', 'contentDigest'], { description: '从当前配置的本体正式版本带入，未选择本体时请先配置', default: {} })
const frozenPlan = object('已冻结的规划', {
  id: text('规划标识', undefined, { minLength: 1 }), kind: text('观测类型', undefined, { enum: ['SPACE', 'GROUND'] }),
  targetId: { title: '目标标识', description: '空间目标数字ID或地面目标GT编码', type: ['string', 'integer'] },
  windows: list('已选择观测窗口', object('观测窗口', { id: text('窗口标识', undefined, { minLength: 1 }) }, ['id']), { minItems: 1 }),
  downlinks: list('下传窗口', object('下传窗口', { id: text('下传窗口标识', undefined, { minLength: 1 }) }, ['id'])),
  revision: integer('规划修订'),
}, ['id', 'kind', 'targetId', 'windows', 'revision'], { description: '请先生成并冻结规划；提交前检查下传并等待人工确认', default: {} })
const target = (ground: boolean) => ground
  ? text('地面目标', '从地面目标目录选择目标编码', { enum: planning.assets.groundTargets.map((item: { id: string }) => item.id), default: 'GT-QH-01' })
  : integer('空间目标', 1, { enum: planning.assets.spaceTargets.map((item: { id: number }) => item.id), default: 8890 })
const planInputs = (ground: boolean): Schema => ({
  targetId: target(ground), observerSatelliteIds: ids('观测卫星集合', ground ? satelliteIds.filter((id: number) => planning.assets.payloads.some((payload: { satelliteId: number; payloadType: string }) => payload.satelliteId === id && payload.payloadType === 'GROUND')) : spaceSatellites, ground ? [3150] : spaceSatellites),
  startTime, endTime,
  ...(ground ? { cloudLimit: { type: 'number', title: '最大云量（%）', description: '仅保留云量不超过该阈值的对地窗口', minimum: 0, maximum: 100, default: 30 } } : {}),
})
const accountInputs: Schema = {
  username: text('账号名', '创建或变更的业务账号名', { minLength: 1, maxLength: 64, pattern: '^[A-Za-z0-9][A-Za-z0-9._-]*$', default: 'chen.xin' }),
  name: text('员工姓名', undefined, { minLength: 1, maxLength: 64, default: '陈新' }),
  employeeNo: text('员工编号', undefined, { minLength: 1, maxLength: 64, default: 'EMP-0103' }), departmentId, roleIds,
  validityDays: integer('授权有效期（天）', 1, { maximum: 30, default: 7, description: '最多30天；关联本体v2时业务规则进一步限制为7天' }),
}
const definition = (title: string, properties: Schema, required: string[] = []) => object(title, properties, required, { additionalProperties: false })
const definitions: Record<string, Schema> = {
  orbit_query: definition('轨道要素查询', { satelliteId: { ...satelliteId, default: 2068 } }, ['satelliteId']),
  telemetry_fetch: definition('遥测片段提取', { satelliteId: { ...satelliteId, default: 2068 }, startTime, endTime, parameterCodes: list('遥测参数编码', text('参数编码', undefined, { minLength: 1 }), { minItems: 1, default: ['BUS_VOLTAGE', 'BATTERY_TEMPERATURE'] }) }, ['satelliteId', 'startTime', 'endTime', 'parameterCodes']),
  payload_compat: definition('载荷兼容检查', { satelliteId: { ...satelliteId, default: 2068 }, payloadType: { ...payloadType, default: 'SPACE' } }, ['satelliteId', 'payloadType']),
  'planning-satellite-query': definition('卫星目录查询', { keyword, satelliteId, payloadType, ...page }),
  'planning-payload-query': definition('独立载荷配置查询', { satelliteIds: ids('卫星集合', satelliteIds, [3150]), payloadType }, ['satelliteIds']),
  'planning-space-target-query': definition('空间目标筛选', { targetId: integer('空间目标标识', 1, { enum: planning.assets.spaceTargets.map((item: { id: number }) => item.id) }), keyword, ...page }),
  'planning-ground-target-query': definition('地面目标区域查询', { targetId: text('地面目标编码', undefined, { enum: planning.assets.groundTargets.map((item: { id: string }) => item.id) }), keyword, ...page }),
  'planning-station-query': definition('地面站目录查询', { stationId, keyword, ...page }),
  'planning-defaults-read': definition('规划默认约束', {}),
  'planning-space-plan': definition('对空观测窗口规划', planInputs(false), ['targetId', 'observerSatelliteIds', 'startTime', 'endTime']),
  'planning-ground-plan': definition('对地观测窗口规划', planInputs(true), ['targetId', 'observerSatelliteIds', 'startTime', 'endTime']),
  'planning-downlink-plan': definition('站星下传窗口规划', { satelliteIds: ids('需要下传的卫星集合', satelliteIds, [3150]), stationId, startTime, endTime }, ['satelliteIds', 'startTime', 'endTime']),
  'planning-downlink-validate': definition('完整时段与容量校验', { planId }, ['planId']),
  'planning-ontology-facts': definition('本体对象事实与关联', { releaseRef, objectType: text('对象类型', '可按对象类型过滤；留空读取所选版本全部事实') }, ['releaseRef']),
  'planning-ontology-rules': definition('本体规则与评分', { releaseRef, planId, ruleIds: list('规则编码集合', text('规则编码', undefined, { minLength: 1 }), { description: '留空显示全部规则；提供规划标识后评估具体规划' }) }, ['releaseRef']),
  'planning-task-submit': definition('观测任务提交', { plan: frozenPlan }, ['plan']),
  'planning-task-status': definition('任务进度查询', { taskId }, ['taskId']),
  'planning-task-result': definition('任务结果查询', { taskId }, ['taskId']),
  'planning-task-cancel': definition('受控取消任务', { taskId: { ...taskId, description: '填写本会话可取消任务标识；执行前必须人工确认' } }, ['taskId']),
  'planning-selection-save': definition('确认规划窗口选择', { taskId, windowIds: list('选中的窗口标识', text('窗口标识', undefined, { minLength: 1 }), { minItems: 1, description: '从该任务规划结果中选择至少一个实际窗口标识' }) }, ['taskId', 'windowIds']),
  'planning-artifact-export': definition('导出规划报告与窗口CSV', { planId }, ['planId']),
  'planning-file-upload': definition('上传规划文件', { artifactId: { ...artifactId, format: 'binary', description: '上传单目标CSV、约束JSON或星历TXT后自动填入真实 artifactId' } }, ['artifactId']),
  'planning-file-parse': definition('解析目标与规划约束', { artifactId }, ['artifactId']),
  'planning-file-export': definition('导出规划文件', { planId }, ['planId']),
  'planning-file-download': definition('下载规划制品', { artifactId }, ['artifactId']),
  'um-identity-resolve': definition('识别当前业务账号', {}),
  'um-permissions-pull': definition('拉取当前业务角色与权限', {}),
  'um-user-query': definition('查询用户与部门', { departmentId, keyword }),
  'um-role-query': definition('查询角色及互斥关系', { keyword }),
  'um-ontology-check': definition('本体规则校验', { requestId, releaseRef }, ['requestId', 'releaseRef']),
  'um-request-prepare': definition('生成账号变更计划', { action: text('变更操作', '创建账号、变更角色或停用账号；不会跳过业务规则和独立审批', { enum: ['CREATE_ACCOUNT', 'ASSIGN_ROLES', 'DISABLE_ACCOUNT'], default: 'CREATE_ACCOUNT' }), ...accountInputs }, ['action', 'username', 'name', 'employeeNo', 'departmentId', 'roleIds', 'validityDays']),
  'um-request-submit': definition('提交账号申请', { requestId }, ['requestId']),
  'um-approval-decide': definition('审批账号申请', { requestId, approved: { type: 'boolean', title: '是否批准', description: '当前审批人的身份、权限和职责分离从业务源校验', default: false }, reason: text('审批意见', '拒绝时请填写原因；实际审批人由当前业务身份确定', { maxLength: 500, default: '' }) }, ['requestId', 'approved']),
  'um-account-create': definition('创建业务账号', { requestId, username: { ...record(accountInputs.username), default: undefined }, name: { ...record(accountInputs.name), default: undefined }, employeeNo: { ...record(accountInputs.employeeNo), default: undefined }, departmentId: { ...departmentId, default: undefined } }, ['requestId']),
  'um-role-assign': definition('授予业务角色', { requestId, accountId, roleIds }, ['requestId', 'accountId', 'roleIds']),
  'um-request-readback': definition('读回账号与授权', { requestId, accountId }, ['requestId']),
  'um-request-receipt': definition('导出授权回执', { requestId }, ['requestId']),
}
for (const [alias, code] of Object.entries({ 'space-target-query': 'planning-space-target-query', 'space-visibility-plan': 'planning-space-plan', 'space-visibility-submit': 'planning-task-submit', 'space-visibility-status': 'planning-task-status', 'space-visibility-result': 'planning-task-result' })) definitions[alias] = definitions[code]

function example(schema: Schema, required = false): unknown {
  if (schema.default !== undefined) return clone(schema.default)
  if (Array.isArray(schema.examples) && schema.examples.length) return clone(schema.examples[0])
  if (schema.const !== undefined) return clone(schema.const)
  if (schema.type === 'object' || schema.properties) {
    const needed = Array.isArray(schema.required) ? schema.required : []
    const entries = Object.entries(record(schema.properties)).map(([key, value]) => [key, example(record(value), needed.includes(key))] as const).filter(([, value]) => value !== undefined)
    return required || entries.length ? Object.fromEntries(entries) : undefined
  }
  if (!required) return undefined
  if (Array.isArray(schema.enum) && schema.enum.length) return clone(schema.enum[0])
  const type = Array.isArray(schema.type) ? schema.type[0] : schema.type
  if (type === 'array') return []
  if (type === 'boolean') return false
  if (type === 'null') return null
  if (type === 'number' || type === 'integer') return typeof schema.minimum === 'number' ? schema.minimum : 0
  return ''
}

/** Exact, user-edited versions keep their own fields, defaults and examples. Empty required references stay visible for context to fill. */
export function parameterTemplate(schema: Record<string, unknown>): Record<string, unknown> {
  return record(example(schema, true))
}

export function toolContract(code: string, inputSchema: Record<string, unknown>, context?: { ontology?: unknown }): Contract {
  const schema = clone(definitions[code] ?? inputSchema)
  const ontology = record(context?.ontology)
  const selected = record(ontology.releaseRef ?? ontology)
  const properties = record(schema.properties)
  if (definitions[code] && properties.releaseRef && ['spaceCode', 'releaseId', 'version', 'contentDigest'].every(key => typeof selected[key] === 'string' && selected[key])) {
    const ref = Object.fromEntries(['space', 'spaceCode', 'releaseId', 'version', 'contentDigest'].filter(key => selected[key] !== undefined).map(key => [key, selected[key]]))
    properties.releaseRef = { ...record(properties.releaseRef), default: ref }
  }
  return { inputSchema: schema, parameterTemplate: parameterTemplate(schema) }
}
