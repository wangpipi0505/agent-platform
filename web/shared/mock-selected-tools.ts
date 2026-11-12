/** 显式工具目录复用绑定版本，表单模板、参数校验与实际调用使用同一份契约。 */
import { buildDeclaredInputs } from './input-schema'
import { toolContract, parameterTemplate } from './mock-tool-catalog'
import { planningSelectedTool, planningState, planningSeed, type PlanningResponse } from './satellite-planning'
import { governanceState, governanceSelectedTool } from './user-governance'
import { readMockArtifact } from './mock-artifacts'
import { parsePlanningArtifact } from './planning-attachments'

type Row = Record<string, unknown>
export interface ToolSkill extends Row { code: string; name: string; inputSchema?: Row; currentVersion?: number; version?: number; status?: string }
export interface ToolApp extends Row { code: string; config: Row }
export interface AvailableTool { code: string; name: string; version: number; description: string; inputSchema: Row; parameterTemplate: Row }
type Versions = Record<string, Row[]>
const record = (value: unknown): Row => value && typeof value === 'object' && !Array.isArray(value) ? value as Row : {}
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T
const aliases: Record<string, string> = { 'space-target-query':'planning-space-target-query', 'space-visibility-plan':'planning-space-plan', 'space-visibility-submit':'planning-task-submit', 'space-visibility-status':'planning-task-status', 'space-visibility-result':'planning-task-result' }
const builtin = (code: string) => code.startsWith('planning-') || code.startsWith('um-') || Object.hasOwn(aliases, code) || ['orbit_query','telemetry_fetch','payload_compat'].includes(code)

/** 仅升级旧内置通用大表；用户编辑后的精确版本契约保持原值。 */
export function normalizeDemoToolSchemas(skills: ToolSkill[], versions: Versions) {
  const upgrade = (skill: ToolSkill, definition: Row) => {
    if (definition.toolParameterRevision === 3) return
    const schema = record(definition.inputSchema), fields = Object.keys(record(schema.properties))
    const legacy = !fields.length || fields.length > 12 && fields.includes('observerSatelliteIds') && fields.includes('taskId') || fields.length === 3 && fields.every(key => ['requestId','accountId','departmentId'].includes(key)) || fields.length === 2 && fields.includes('artifactId') && fields.includes('parameters')
    if (!legacy) return
    const contract = toolContract(skill.code, schema)
    definition.inputSchema = contract.inputSchema
    definition.parameterTemplate = contract.parameterTemplate
    definition.toolParameterRevision = 3
  }
  for (const skill of skills.filter(skill => builtin(skill.code))) {
    upgrade(skill, skill)
    if (!Object.hasOwn(versions, skill.code)) {
      versions[skill.code] = Array.from({length:Number(skill.currentVersion ?? skill.version ?? 1)}, (_, index) => ({ ...clone(skill), version: index+1, versionNo: index+1, status:'PUBLISHED', definition:clone(skill) }))
    }
    for (const version of versions[skill.code]) {
      upgrade(skill, version)
      if (version.definition) upgrade(skill, record(version.definition))
    }
  }
}

export function availableTools(app: ToolApp, skills: ToolSkill[], versions: Versions, conversationId = ''): AvailableTool[] {
  normalizeDemoToolSchemas(skills, versions)
  const refs = Array.isArray(app.config.skillRefs) ? app.config.skillRefs as Row[] : []
  const planning = planningState.conversations[`${app.code}:${conversationId}`]
  const governance = governanceState.conversations[`${app.code}:${conversationId}`]
  const request = governance?.requestId ? governanceState.requests[governance.requestId] : undefined
  const actor = governanceState.actors.find(actor => actor.id === governanceState.actorId)
  const responses = Object.values(planningState.results).filter(response => response.conversationId === conversationId)
  const artifact = responses.flatMap(response => response.artifacts).at(-1)
  const lastFileReference = responses.flatMap(response => response.toolCalls).map(call => record(call.input).artifactId).filter(Boolean).at(-1)
  const task = planning?.lastTaskId ? planningState.tasks[planning.lastTaskId] : undefined
  return refs.flatMap(ref => {
    const skill = skills.find(item => item.code === ref.code)
    const versionNo = Number(ref.version ?? ref.versionNo)
    if (!skill || ['DISABLED','ARCHIVED'].includes(skill.status ?? '')) return []
    const version = versions[skill.code]?.find(item => Number(item.versionNo ?? item.version) === versionNo) ?? (Number(skill.currentVersion ?? skill.version) === versionNo ? skill : undefined)
    if (!version || ['DRAFT','DISABLED','ARCHIVED'].includes(String(version.status))) return []
    const definition = { ...skill, ...version, ...record(version.definition) }
    const inputSchema = clone(record(definition.inputSchema))
    if (!Object.keys(inputSchema).length) Object.assign(inputSchema,{type:'object',properties:{}})
    const template = parameterTemplate(inputSchema), properties = record(inputSchema.properties)
    const context: Row = { planId:planning?.plan?.id,plan:planning?.plan,taskId:planning?.lastTaskId,windowIds:task?.plan.windows.slice(0,1).map(window=>window.id),requestId:request?.id,accountId:request?.resultAccountId,departmentId:request?.departmentId??actor?.departmentId,artifactId:artifact?.id??lastFileReference,releaseRef:app.config.ontology }
    for (const [key,value] of Object.entries(context)) if (Object.hasOwn(properties,key) && value !== undefined) template[key] = clone(value)
    return [{code:skill.code,name:skill.name,version:versionNo,description:String(definition.description ?? ''),inputSchema,parameterTemplate:template}]
  })
}

export function selectedToolParameters(tool: AvailableTool, value: unknown): Row {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw Error('技能参数必须为对象。')
  const parameters = value as Row
  const properties = record(tool.inputSchema.properties)
  if (tool.inputSchema.additionalProperties === false && Object.keys(parameters).some(key => !Object.hasOwn(properties,key))) throw Error('参数包含当前技能未声明的字段，请重新载入模板。')
  return buildDeclaredInputs(tool.inputSchema, parameters) as Row
}

interface ToolInput { app: ToolApp; tool: AvailableTool; parameters: Row; conversationId: string; runId: string }
export async function executeSelectedTool({ app, tool, parameters, conversationId, runId }: ToolInput) {
  if (tool.code.startsWith('um-')) return governanceSelectedTool({appCode:app.code,config:app.config,conversationId,runId},tool.code,parameters)
  const code = aliases[tool.code] ?? tool.code
  let response: PlanningResponse
  if (code.startsWith('planning-file-')) {
    if (code === 'planning-file-export') response = planningSelectedTool(app.code,app.config,'planning-artifact-export',parameters,conversationId,runId)
    else {
      const file = await readMockArtifact(String(parameters.artifactId))
      if (!file) throw Error('文件不存在，请先上传文件或生成结果制品。')
      let output: unknown = {artifactId:file.artifactId,name:file.name,downloadUrl:file.downloadUrl,bytes:file.bytes}
      if (code === 'planning-file-parse') output = (await parsePlanningArtifact(file.artifactId)).parameters
      if (code === 'planning-file-upload') output = {artifactId:file.artifactId,name:file.name,bytes:file.bytes,sha256:file.sha256}
      response = {runId,conversationId,status:'SUCCEEDED',reply:`### ${tool.name}\n\n\`\`\`json\n${JSON.stringify(output,null,2)}\n\`\`\``,ontology:null,artifacts:[],promptSnapshot:String(app.config.systemPrompt ?? ''),toolCalls:[]}
      if (code === 'planning-file-download') response.artifacts = [{id:file.artifactId,name:file.name,mediaType:file.mediaType,content:file.content,size:file.bytes,downloadUrl:file.downloadUrl}]
      response.toolCalls.push({id:runId+'-tool',skillCode:tool.code,name:tool.name,input:parameters,output:code === 'planning-file-download'?{artifactId:file.artifactId,name:file.name,downloadUrl:file.downloadUrl}:output,status:'SUCCEEDED'})
    }
  } else if (code.startsWith('planning-')) response = planningSelectedTool(app.code,app.config,code,parameters,conversationId,runId)
  else {
    const satellite = planningSeed.assets.satellites.find(item => item.id === parameters.satelliteId)
    let output: unknown
    if (code === 'orbit_query') { if(!satellite) throw Error('卫星不在当前目录中。'); output={satelliteId:satellite.id,name:satellite.name,noradCatId:satellite.noradCatId,orbitAltitudeKm:satellite.orbitAltitudeKm,epoch:planningSeed.clock} }
    else if (code === 'telemetry_fetch') {
      if(!satellite) throw Error('卫星不在当前目录中。')
      const start=Date.parse(String(parameters.startTime)),end=Date.parse(String(parameters.endTime))
      if(!Number.isFinite(start)||!Number.isFinite(end)||end<=start)throw Error('遥测时间范围无效。')
      const names=Array.isArray(parameters.parameterCodes)?parameters.parameterCodes.map(String):['batteryVoltage','temperature']
      output={satelliteId:satellite.id,items:Array.from({length:3},(_,index)=>({time:new Date(start+(end-start)*index/3).toISOString(),values:Object.fromEntries(names.map(name=>[name,/temperature/i.test(name)?22+index:28+index/10]))}))}
    } else if(code==='payload_compat')output={satelliteId:parameters.satelliteId,payloadType:parameters.payloadType,compatible:!!satellite?.supportedPayloads.includes(String(parameters.payloadType))}
    else output={skillCode:tool.code,version:tool.version,parameters:clone(parameters),result:'已按所选技能版本完成处理'}
    response={runId,conversationId,status:'SUCCEEDED',reply:`### ${tool.name}\n\n\`\`\`json\n${JSON.stringify(output,null,2)}\n\`\`\``,toolCalls:[{id:runId+'-tool',skillCode:tool.code,name:tool.name,input:parameters,output,status:'SUCCEEDED'}],ontology:null,artifacts:[],promptSnapshot:String(app.config.systemPrompt ?? '')}
  }
  // 旧能力名称只负责兼容绑定，审计和统计保留用户实际选择的技能编码。
  for (const call of response.toolCalls) if (call.skillCode === code || tool.code === 'planning-file-export' && call.skillCode === 'planning-artifact-export') { call.skillCode=tool.code;call.name=tool.name;call.input=clone(parameters) }
  if (!response.toolCalls.some(call => call.skillCode === tool.code)) response.toolCalls.unshift({id:runId+'-selected',skillCode:tool.code,name:tool.name,input:clone(parameters),output:{status:response.status},status:response.status})
  Object.assign(response,{selectedTool:{code:tool.code,version:tool.version},parameters:clone(parameters)})
  return response
}
