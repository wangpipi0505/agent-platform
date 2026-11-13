<script setup lang="ts">
/**
 * 问答型智能体的运行界面：左侧对话、右侧说明。
 * 本机 mock 下由 /admin/apps/:code/demo-chat 生成固定回复并落一条运行记录，
 * 真实后端下同一路径应替换为正式对话接口。
 */
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useApi } from '../../../../shared/api'
import planningSeedText from '../../../../shared/satellite-planning.seed.json?raw'
import {safeMarkdown} from '../../../../shared/content'
import {useAuth} from '../../../../shared/api'
import {uploadArtifact} from '../../../../shared/artifact-upload'
import {mockArtifactRequest,registerMockArtifact,mockArtifactsEnabled} from '../../../../shared/mock-artifacts'
import Artifacts from '../../../../shared/Artifacts.vue'
import SchemaInputs from '../../../../shared/SchemaInputs.vue'
import { inputFields } from '../../../../shared/input-schema'
import { confirmDanger } from '../../../../shared/components/DangerConfirm'
import BusinessContextPanel from './BusinessContextPanel.vue'

const props = defineProps<{ appCode: string; appName?: string; mode?: 'draft' | 'published'; currentVersionNo?:number|null }>()
const published = () => props.mode === 'published'
const api = useApi()
const auth=useAuth()
const runVersions=ref<any[]>([]),selectedVersion=ref<number>(),conversationVersion=ref<number>(),versionsLoading=ref(false)
let versionGeneration=0
async function loadVersions(){
  const ticket=++versionGeneration;runVersions.value=[];selectedVersion.value=undefined;versionsLoading.value=false;
  if(!published())return;
  versionsLoading.value=true;
  try {
    const rows=await api<any[]>(`/admin/apps/${encodeURIComponent(props.appCode)}/versions`);
    if(ticket!==versionGeneration)return;
    runVersions.value=rows.filter(row=>(!row.status||['PUBLISHED','SUPERSEDED'].includes(row.status))&&Number.isInteger(row.versionNo));
    selectedVersion.value=runVersions.value.find(row=>row.versionNo===props.currentVersionNo)?.versionNo??runVersions.value.find(row=>row.status==='PUBLISHED')?.versionNo??runVersions.value[0]?.versionNo;
  }catch(problem){if(ticket===versionGeneration)error.value=problem instanceof Error?problem.message:'运行版本加载失败';}
  finally{if(ticket===versionGeneration)versionsLoading.value=false;}
}
function selectVersion(versionNo:number){if(selectedVersion.value===versionNo)return;newConversation();selectedVersion.value=versionNo;}


type Turn = { role: 'user' | 'assistant'; text: string; runId?: string; result?: any }
interface ScenarioMenu { planningDate: string; clock: string; examples: { id: string; title: string; message: string }[]; tools: { code: string; name: string }[] }
const scenarios = ref<ScenarioMenu | null>(null)
const governance=computed(()=>props.appCode==='user-governance-assistant')
const rich=computed(()=>planning.value||governance.value)
const planning = computed(() => ['satellite-planning-assistant', 'satellite-assistant', 'space-visibility-agent'].includes(props.appCode))
const conversationId = ref(`planning-${Date.now()}-${Math.random().toString(36).slice(2)}`)
const latest = computed(() => [...turns.value].reverse().find(turn => turn.result)?.result)
let generation = 0
const statusText = (status?: string) => ({ SUCCEEDED: '已完成', WAITING_APPROVAL: '等待人工确认', NEEDS_INPUT: '需要补充信息', NO_WINDOW: '没有可用窗口', BLOCKED: '约束不满足', REJECTED: '已拒绝', CANCELLED: '已取消' }[status ?? ''] ?? status ?? '')

const turns = ref<Turn[]>([
  {
    role: 'assistant',
    text: welcome(),
  },
])
const input = ref('')
const busy = ref(false)
const error = ref('')
const listRef = ref<HTMLElement | null>(null)
const attachments = ref<{ artifactId: string; name: string; bytes: number }[]>([])
const uploading = ref(false), businessContext = ref<InstanceType<typeof BusinessContextPanel>>()

interface ToolOption { code: string; name: string; version: number; description?: string; inputSchema: any; parameterTemplate?: Record<string, unknown> }
const availableTools = ref<ToolOption[]>([])
const selectedToolKey = ref('')
const selectedTool = ref<ToolOption>()
const toolForm = ref<InstanceType<typeof SchemaInputs>>()
const toolsLoading = ref(false)
const toolsError = ref('')
const toolFields = computed(() => inputFields(selectedTool.value?.inputSchema))
const acceptsToolFile = computed(() => toolFields.value.some(field => field.format === 'binary'))
const hasPlanningFileTemplates = computed(() => selectedTool.value?.code === 'planning-file-upload')
const toolKey = (tool: ToolOption) => `${tool.code}@${tool.version}`
let toolsGeneration = 0

async function loadAvailableTools(preserveSelection = false) {
  const ticket = ++toolsGeneration
  if (!preserveSelection) { availableTools.value = []; selectTool('') }
  toolsError.value = ''; toolsLoading.value = false
  if (published() && !selectedVersion.value) return
  const query = new URLSearchParams({ conversationId: conversationId.value })
  if (published()) query.set('versionNo', String(conversationVersion.value ?? selectedVersion.value))
  toolsLoading.value = true
  try {
    const rows = await api<ToolOption[]>(`/admin/apps/${encodeURIComponent(props.appCode)}/available-tools?${query}`)
    if (ticket !== toolsGeneration) return
    // 目录模板会随会话业务上下文更新；保留当前声明对象，避免 SchemaInputs 重置用户已填参数。
    availableTools.value = rows
    if (selectedToolKey.value && !rows.some(tool => toolKey(tool) === selectedToolKey.value)) selectTool('')
  } catch (problem) {
    if (ticket === toolsGeneration) toolsError.value = problem instanceof Error ? problem.message : '技能目录加载失败，请重试'
  } finally { if (ticket === toolsGeneration) toolsLoading.value = false }
}

function selectTool(key: string) {
  if (selectedToolKey.value !== (key || '')) attachments.value = []
  selectedToolKey.value = key || ''
  selectedTool.value = availableTools.value.find(tool => toolKey(tool) === key)
  error.value = ''
  void restoreToolTemplate()
}
async function restoreToolTemplate() {
  const tool = availableTools.value.find(item => toolKey(item) === selectedToolKey.value)
  selectedTool.value = tool
  await nextTick()
  if (tool && selectedTool.value === tool) {
    toolForm.value?.replace(JSON.parse(JSON.stringify(tool.parameterTemplate ?? {})))
    error.value = ''
  }
}
async function executeTool() {
  if (!selectedTool.value || !toolForm.value || toolsLoading.value || busy.value) return
  try {
    const tool = selectedTool.value
    const parameters = toolForm.value.collect()
    await send(`执行${tool.name}`, { tool, parameters })
  } catch (problem) { error.value = problem instanceof Error ? problem.message : '请检查技能参数后重试' }
}

async function loadScenarios() {
  scenarios.value = null
  if (!rich.value) return
  try { scenarios.value = await api<ScenarioMenu>(`/admin/apps/${encodeURIComponent(props.appCode)}/planning-scenarios`) }
  catch (problem) { error.value = problem instanceof Error ? problem.message : '示例加载失败' }
}
function newConversation() {
  generation++
  conversationVersion.value=undefined
  conversationId.value = `planning-${Date.now()}-${Math.random().toString(36).slice(2)}`
  turns.value = [{ role: 'assistant', text: welcome() }]
  attachments.value=[]
  selectTool('')
  input.value = ''; error.value = ''; busy.value = false
}
async function resetDemo() {
  // F1-3：原生 confirm 清零，演示重置改走 DangerConfirm。
  if (
    !(await confirmDanger({
      title: '重置演示数据',
      message: '重置本标签页的全部配置、业务申请与任务，恢复初始示例数据？',
      confirmText: '重置',
    }))
  )
    return
  try { await api('/admin/_demo/reset', 'POST', {}); window.location.reload() }
  catch (problem) { error.value = problem instanceof Error ? problem.message : '重置失败' }
}
watch(() => [props.appCode,props.mode], () => { newConversation(); void loadScenarios();void loadVersions() })
watch(() => [props.appCode, props.mode, selectedVersion.value, conversationId.value], () => { void loadAvailableTools() }, { immediate: true })
watch(() => props.appName, () => {if(turns.value.length===1)turns.value[0].text=welcome()})
onMounted(()=>{void loadScenarios();void loadVersions()})
async function send(example?: string, execution?: { tool: ToolOption; parameters: Record<string, unknown> }) {
  const text = (example ?? input.value).trim()
  if (!text || busy.value || uploading.value) return
  const parameters = execution?.parameters ?? {}
  const versionNo=published()?(conversationVersion.value??selectedVersion.value):undefined;
  if(published()&&(!versionNo||versionsLoading.value)){error.value='请选择已发布的运行版本';return;}
  if(published())conversationVersion.value=versionNo;
  const requestGeneration = generation
  turns.value.push({ role: 'user', text })
  if (!execution) input.value = ''
  busy.value = true
  error.value = ''
  await nextTick()
  listRef.value?.scrollTo({ top: listRef.value.scrollHeight })
  try {
    const result = await api<any>(
      `/admin/apps/${encodeURIComponent(props.appCode)}/demo-chat`,
      'POST',
      { message: text, conversationId: conversationId.value, attachments: acceptsToolFile.value ? attachments.value.map(file=>({artifactId:file.artifactId,name:file.name})) : [], parameters, ...(execution ? { toolSelection: { code: execution.tool.code, version: execution.tool.version } } : {}), ...(published()?{versionNo}:{}) },
    )
    if (requestGeneration !== generation) return
    turns.value.push({ role: 'assistant', text: result.reply, runId: result.runId, result });if(!execution)attachments.value=[];void businessContext.value?.load();void loadAvailableTools(true)
  } catch (e: any) {
    error.value = e?.message || '发送失败，请稍后重试。'
  } finally {
    if (requestGeneration === generation) busy.value = false
    await nextTick()
    listRef.value?.scrollTo({ top: listRef.value.scrollHeight })
  }
}

async function decide(approved: boolean) {
  const result = latest.value
  if (!result?.approval || busy.value) return
  busy.value = true; error.value = ''
  try {
    await api(`/admin/approvals/${encodeURIComponent(result.runId)}/${encodeURIComponent(result.approval.nodeId)}`, 'POST', { approved, snapshotDigest: result.ontology?.releaseRef?.contentDigest })
    const snapshot = await api<{ planning?: any }>(`/admin/runs/${encodeURIComponent(result.runId)}`)
    const turn = turns.value.find(item => item.runId === result.runId)
    void businessContext.value?.load()
    if (turn && snapshot.planning) { turn.result = snapshot.planning; turn.text = snapshot.planning.reply }
    void loadAvailableTools(true)
  } catch (problem) { error.value = problem instanceof Error ? problem.message : '确认失败，请重试' }
  finally { busy.value = false }
}

function welcome(){return `你好，我是${props.appName||'智能体'}。`+(props.appCode==='user-governance-assistant'?'可以查询用户、部门和角色，发起开户、停用或角色变更，并按业务权限完成审批。':/satellite|space/.test(props.appCode)?'可以从业务资源总览开始，选择对空或对地规划，再校验下传、人工确认并获取任务结果。':'请告诉我需要处理的问题，也可以附加文件和运行参数。')}
async function uploadToolFile(file: File) {
  const key = selectedToolKey.value, conversation = conversationId.value
  uploading.value = true
  try {
    const artifactId = await uploadArtifact(auth.token, file)
    if (key !== selectedToolKey.value || conversation !== conversationId.value) throw Error('会话或技能已切换，请在当前技能重新选择文件。')
    attachments.value.push({ artifactId, name: file.name, bytes: file.size })
    return artifactId
  } finally { uploading.value = false }
}
async function templateFile(kind:string){
 const seed=JSON.parse(planningSeedText);const satellite=seed.assets.satellites.find((item:any)=>item.id===3150);
 const templates:Record<string,{name:string,mediaType:string,content:string}>={csv:{name:'观测目标模板.csv',mediaType:'text/csv;charset=utf-8',content:'targetId,targetType,planningDate\nGT-QH-01,GROUND,2026-11-06\n'},json:{name:'观测约束模板.json',mediaType:'application/json',content:JSON.stringify({maxCloudPercent:30,onlySatelliteId:3150},null,2)},txt:{name:'星历参数模板.txt',mediaType:'text/plain;charset=utf-8',content:JSON.stringify({planningDate:seed.planningDate,satelliteId:satellite.id,epochAgeDays:seed.windows.find((item:any)=>item.satelliteId===satellite.id)?.epochAgeDays,...satellite},null,2)}};
 const file=templates[kind];if(!file)return;const ref=mockArtifactsEnabled()?await registerMockArtifact(file):undefined;const response=ref?await mockArtifactRequest(ref.downloadUrl):undefined;const blob=response?await response.blob():new Blob([file.content],{type:file.mediaType});const url=URL.createObjectURL(blob);const link=document.createElement('a');link.href=url;link.download=file.name;link.click();URL.revokeObjectURL(url);
}
function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    void send()
  }
}
</script>

<template>
  <div class="panel chat">
    <header class="chat-head">
      <div>
        <h2>{{ published() ? '正式运行（助手对话）' : '对话（草稿）' }}</h2>
        <p class="muted">
          {{ published()
            ? '以所选发布版本提供问答服务，每次提问生成正式运行记录。切换运行版本会开始新对话。'
            : '草稿态问答：回复来自当前草稿绑定的模型与技能；每次发送会留一条运行记录，不代表已发布版本。' }}
        </p>
      </div>
      <div class="row"><label v-if="published()" class="row">运行版本<el-select :model-value="selectedVersion" :disabled="busy||versionsLoading" :loading="versionsLoading" style="width:11rem" aria-label="运行版本" @update:model-value="selectVersion"><el-option v-for="version in runVersions" :key="version.versionNo" :value="version.versionNo" :label="`v${version.versionNo}${version.versionNo===currentVersionNo?'（默认）':''}`" /></el-select></label><el-button :disabled="busy" @click="newConversation">新对话</el-button><el-button v-if="scenarios" :disabled="busy" @click="resetDemo">重置示例</el-button><RouterLink class="muted" to="/runs">查看运行记录</RouterLink></div>
    </header>
    <BusinessContextPanel v-if="governance" ref="businessContext" @changed="loadScenarios" @reset="newConversation" />
    <div v-if="scenarios" class="scenario-guide">
      <div><strong>{{governance ? '用户与权限管理示例' : '观测规划业务示例'}}</strong><span class="muted">场景日期 {{ scenarios.planningDate }} · 北京时间 · {{ availableTools.length }} 个绑定工具</span></div>
      <p>{{governance ? '推荐顺序：查看业务目录 → 发起用户变更 → 核对权限和语义条件 → 切换审批身份 → 审批并查看结果。' : '推荐顺序：业务资源 → 对空或对地规划 → 下传 → 本体依据 → 提交并确认 → 任务结果。'}}</p>
      <div class="example-list"><el-button v-for="example in scenarios.examples" :key="example.id" size="small" :disabled="busy" :title="example.message" @click="send(example.message)">{{ example.title }}</el-button></div>
    </div>
    <div :class="['planning-layout', { 'with-result': rich && latest }]">
    <div class="conversation">
    <div ref="listRef" class="turns" role="log" aria-live="polite">
      <div v-for="(turn, index) in turns" :key="index" :class="['turn', turn.role]">
        <div v-if="turn.role==='assistant'" class="bubble markdown" v-html="safeMarkdown(turn.text)" /><div v-else class="bubble">{{turn.text}}</div>
        <RouterLink v-if="turn.runId" class="meta" :to="`/runs/${turn.runId}`">运行 {{ turn.runId }} · {{ statusText(turn.result?.status) }}</RouterLink>
      </div>
    </div>
    <p v-if="error" class="error-text" role="alert">{{ error }}</p>
    <section class="skill-composer" aria-label="技能参数执行">
      <div class="skill-picker">
        <label for="available-skill">可用技能 / 工具</label>
        <el-select id="available-skill" :model-value="selectedToolKey" filterable clearable :loading="toolsLoading" :disabled="busy || uploading || toolsLoading || versionsLoading" placeholder="搜索技能名称或编码" aria-label="选择可用技能/工具" @update:model-value="selectTool">
          <el-option v-for="tool in availableTools" :key="toolKey(tool)" :value="toolKey(tool)" :label="`${tool.name} · ${tool.code} · v${tool.version}`" />
        </el-select>
        <el-button size="small" :disabled="busy || toolsLoading || versionsLoading" @click="loadAvailableTools(true)">刷新目录</el-button>
      </div>
      <p v-if="toolsError" class="error-text" role="alert">{{ toolsError }}，可点击“刷新目录”重试。</p>
      <p v-else-if="!toolsLoading && !availableTools.length" class="muted">{{ published() ? '此运行版本没有可用技能，请选择其他发布版本，或在配置中绑定技能后重新发布。' : '当前应用尚未绑定可用技能，请在对话配置中选择技能并保存。' }}</p>
      <template v-if="selectedTool">
        <div class="row"><strong>{{ selectedTool.name }} · v{{ selectedTool.version }}</strong><el-button text size="small" :disabled="busy || toolsLoading" @click="restoreToolTemplate">重新载入模板</el-button></div>
        <p v-if="selectedTool.description" class="muted">{{ selectedTool.description }}</p>
        <details :key="selectedToolKey" class="attachment-composer" open aria-label="所选技能的附件与运行参数">
          <summary>{{ acceptsToolFile ? '附件与运行参数' : '运行参数' }} · {{ selectedTool.name }}</summary>
          <div v-if="hasPlanningFileTemplates" class="file-templates row">
            <span class="muted">下载输入模板</span>
            <el-button size="small" @click="templateFile('csv')">目标CSV模板</el-button>
            <el-button size="small" @click="templateFile('json')">约束JSON模板</el-button>
            <el-button size="small" @click="templateFile('txt')">星历参数TXT</el-button>
          </div>
          <SchemaInputs :key="selectedToolKey" ref="toolForm" :declaration="selectedTool.inputSchema" :upload-artifact="uploadToolFile" />
          <p v-if="!toolFields.length" class="muted">此技能不需要输入参数，可直接执行。</p>
          <p v-for="file in attachments" v-show="acceptsToolFile" :key="file.artifactId" class="uploaded-file">已上传：{{ file.name }} · {{ file.bytes }}字节</p>
          <p v-if="hasPlanningFileTemplates" class="muted">支持单目标CSV、约束JSON和星历参数TXT。上传后自动填入文件标识。</p>
        </details>
        <div class="row"><el-button type="primary" :loading="busy" :disabled="busy || toolsLoading || uploading || versionsLoading" @click="executeTool">执行技能</el-button><span class="muted">按所选技能版本校验参数，无需填写下方问题。</span></div>
      </template>
    </section>
    <div class="composer">
      <el-input
        v-model="input"
        type="textarea"
        :rows="2"
        maxlength="2000"
        placeholder="输入问题后按 Enter 发送，Shift+Enter 换行"
        aria-label="输入问题"
        @keydown="onKeydown"
      />
      <el-button type="primary" :loading="busy" :disabled="!input.trim()||uploading||(published()&&(!selectedVersion||versionsLoading))" @click="send()">发送</el-button>
    </div>
    </div>
    <aside v-if="rich && latest" class="planning-result" aria-label="规划结构化结果">
      <header><h3>{{governance?'本次业务变更与依据':'本次规划与依据'}}</h3><el-tag :type="latest.status === 'SUCCEEDED' ? 'success' : 'warning'">{{ statusText(latest.status) }}</el-tag></header>
      <section v-if="latest.approval" class="approval-card">
        <strong>{{ latest.approval.summary }}</strong>
        <p>冻结计划：{{ latest.plan?.id || latest.businessChange?.requestId }}。状态：{{ latest.approval.status }}。</p>
        <div v-if="latest.approval.status === 'PENDING'" class="row"><el-button type="primary" :loading="busy" @click="decide(true)">批准并执行</el-button><el-button :disabled="busy" @click="decide(false)">拒绝</el-button></div>
      </section>
      <section v-if="latest.workflow?.length"><h4>业务执行流程</h4><ol class="workflow-list"><li v-for="node in latest.workflow" :key="node.id"><strong>{{node.label}} · {{statusText(node.status)}}</strong><p v-if="node.permission" class="muted">所需权限 {{node.permission}}</p><p>{{node.reason||node.result}}</p></li></ol></section>
      <section v-if="latest.businessChange"><h4>变更申请 {{latest.businessChange.requestId}}</h4><p>{{latest.businessChange.action}} · {{latest.businessChange.target}} · {{latest.businessChange.department}}</p><details><summary>查看变更前后</summary><strong>变更前</strong><pre>{{JSON.stringify(latest.businessChange.before,null,2)}}</pre><strong>变更后</strong><pre>{{JSON.stringify(latest.businessChange.after,null,2)}}</pre></details></section>
      <section v-if="planning && latest.plan">
        <h4>{{ latest.plan.kind === 'SPACE' ? '对空观测' : '对地观测' }} · {{ latest.plan.targetName }}</h4>
        <p class="muted">{{ latest.plan.id }} · {{ latest.plan.planningDate }} · 推荐 {{ latest.plan.recommendedWindowId || '无' }}</p>
        <div class="result-scroll"><table class="result-table"><caption>可用观测窗口</caption><thead><tr><th>窗口 / 卫星</th><th>北京时间</th><th>时长</th><th>评分</th></tr></thead><tbody><tr v-for="window in latest.plan.windows" :key="window.id"><td>{{ window.id }}<br>卫星 {{ window.satelliteId }}</td><td>{{ window.startTime.slice(11,19) }}<br>—{{ window.endTime.slice(11,19) }}</td><td>{{ window.durationSec }}秒</td><td>{{ window.score }}</td></tr><tr v-if="!latest.plan.windows.length"><td colspan="4">没有满足约束的窗口</td></tr></tbody></table></div>
        <p v-if="latest.plan.excludedWindows.length" class="muted">排除 {{ latest.plan.excludedWindows.map((window:any) => window.id).join('、') }}，原因见答复与规则判定。</p>
        <ul v-if="latest.plan.downlinks.length" class="downlink-list"><li v-for="link in latest.plan.downlinks" :key="link.id">{{ link.stationId }} · 卫星{{ link.satelliteId }} · {{ link.startTime.slice(11,19) }}—{{ link.endTime.slice(11,19) }} · {{ link.capacityGb }}GB</li></ul>
      </section>
      <section v-if="latest.task"><h4>任务 {{ latest.task.taskId }}</h4><p>{{ latest.task.status }} · {{ latest.task.stages?.join(' → ') }}</p></section>
      <Artifacts v-if="latest.artifacts?.length" :files="latest.artifacts" :run-id="latest.runId" admin />
      <section v-if="latest.ontology" class="ontology-evidence"><h4>本体响应</h4><strong>{{ latest.ontology.name }}</strong><p class="muted">{{ latest.ontology.releaseRef?.version }} · {{ latest.ontology.releaseRef?.releaseId }}</p>
        <ul v-if="latest.ontology.decisions?.length" class="rule-list"><li v-for="(decision,index) in latest.ontology.decisions" :key="`${decision.ruleId}-${index}`"><el-tag size="small" :type="decision.result === 'HOLDS' ? 'success' : decision.result === 'NOT_HOLDS' ? 'danger' : 'info'">{{ decision.result === 'HOLDS' ? '满足' : decision.result === 'NOT_HOLDS' ? '不满足' : '未判定' }}</el-tag><div><strong>{{ decision.name }}</strong><p>{{ decision.reason }}</p></div></li></ul>
        <details><summary>对象事实（{{ latest.ontology.facts?.length }}）与关系</summary><p v-for="relation in latest.ontology.relations" :key="relation">{{ relation }}</p><div v-for="fact in latest.ontology.facts" :key="fact.stableId"><strong>{{ fact.objectType }} · {{ fact.stableId }}</strong><pre>{{ JSON.stringify(fact.values,null,2) }}</pre></div></details>
      </section>
      <p v-else class="muted">本次没有加载本体；可在对话配置中选择正式本体版本。</p>
      <section><h4>工具调用链（{{ latest.toolCalls?.length || 0 }}）</h4><details v-for="tool in latest.toolCalls || []" :key="tool.id" class="tool-call"><summary>{{ tool.name }} · {{ tool.status }}</summary><p class="muted">{{ tool.skillCode }}</p><strong>输入</strong><pre>{{ JSON.stringify(tool.input,null,2) }}</pre><strong>输出</strong><pre>{{ JSON.stringify(tool.output,null,2) }}</pre></details></section>
    </aside>
    </div>
  </div>
</template>

<style scoped>
.chat { display: grid; gap: 12px; }
.chat-head { display: flex; justify-content: space-between; align-items: start; gap: 16px; }
.chat-head h2 { margin: 0 0 4px; font-size: var(--text-title); }
.chat-head p { margin: 0; }
.turns { display: grid; gap: 12px; max-height: 52vh; overflow-y: auto; padding: 4px; }
.turn { display: grid; gap: 4px; max-width: 94%; min-width: 0; }
.turn.user { justify-self: end; }
.turn .bubble { padding: 10px 12px; border-radius: 10px; background: var(--surface-2); border: 1px solid var(--line); white-space: pre-wrap; }
.turn .markdown {white-space:normal;overflow-wrap:anywhere;overflow:auto;max-width:100%}.markdown :deep(table){border-collapse:collapse;display:block;overflow:auto;max-width:100%}.markdown :deep(td),.markdown :deep(th){padding:.5rem;border:1px solid var(--line)}.markdown :deep(pre){overflow:auto;white-space:pre;max-width:100%;padding:.6rem;background:var(--surface-1)}.markdown :deep(p){margin:.35rem 0}.attachment-composer{padding:.7rem;border:1px solid var(--line);border-radius:.5rem}.attachment-composer summary{cursor:pointer;margin-bottom:.5rem}.upload-label{font-size:.85rem}.upload-label input{max-width:15rem}.workflow-list{padding-left:1.2rem}.workflow-list li{margin-bottom:.6rem}
.turn.user .bubble { background: var(--nav-active-bg); border-color: var(--primary); }
.turn .meta { font-size: var(--text-meta); color: var(--muted); }
.composer { display: grid; grid-template-columns: 1fr auto; gap: 8px; align-items: end; }
.skill-composer { display: grid; gap: .65rem; padding: .8rem; border: 1px solid var(--line); border-radius: .5rem; }
.skill-composer p { margin: 0; }
.skill-picker { display: flex; flex-wrap: wrap; align-items: center; gap: .6rem; }
.skill-picker :deep(.el-select) { flex: 1; min-width: min(15rem, 100%); }
.skill-composer :deep(.el-form) { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(15rem, 100%), 1fr)); gap: 0 1rem; min-width: 0; }
.skill-composer :deep(.el-form-item) { min-width: 0; margin-bottom: .75rem; }
.skill-composer .attachment-composer { min-width: 0; padding: .8rem; background: var(--surface-1, #0f172a); }
.skill-composer .attachment-composer summary { font-weight: 600; font-size: .9rem; overflow-wrap: anywhere; }
.file-templates { flex-wrap: wrap; gap: .5rem; margin-block: .75rem; }
.file-templates :deep(.el-button + .el-button) { margin-left: 0; }
.skill-composer :deep(.artifact-input) { display: grid; gap: .5rem; min-width: 0; width: 100%; }
.skill-composer :deep(.artifact-input input[type=file]) { width: 100%; min-width: 0; color: var(--text, #e2e8f0); font: inherit; }
.skill-composer :deep(.artifact-input input[type=file]::file-selector-button) { margin-right: .5rem; padding: .45rem .75rem; border: 1px solid var(--line, #334155); border-radius: .3rem; color: var(--primary, #38bdf8); background: var(--surface-2, #152030); cursor: pointer; font: inherit; }
.uploaded-file { margin-block: .5rem; overflow-wrap: anywhere; }
.chat { container-type: inline-size; }
.chat-head, .chat-head .row { flex-wrap: wrap; }
.scenario-guide { display: grid; gap: .6rem; padding: .9rem; background: var(--surface-2, #152030); border: 1px solid var(--line, #334155); border-radius: .6rem; }
.scenario-guide > div:first-child, .example-list { display: flex; flex-wrap: wrap; gap: .5rem .8rem; align-items: center; }
.scenario-guide p { margin: 0; font-size: .84rem; color: var(--muted, #94a3b8); }
.example-list :deep(.el-button + .el-button) { margin-left: 0; }
.planning-layout, .conversation { display: grid; gap: 1rem; min-width: 0; align-content: start; }
.planning-layout.with-result { grid-template-columns: minmax(0, 1.05fr) minmax(20rem, 1fr); align-items: start; }
.planning-result { display: grid; gap: 1rem; padding: .9rem; min-width: 0; border: 1px solid var(--line, #334155); border-radius: .6rem; background: var(--surface-2, #152030); max-height: 65vh; overflow: auto; }
.planning-result header { display: flex; flex-wrap: wrap; justify-content: space-between; gap: .6rem; align-items: center; }
.planning-result h3, .planning-result h4, .planning-result p { margin: 0 0 .5rem; }
.planning-result h3 { font-size: 1rem; }.planning-result h4 { font-size: .9rem; }
.planning-result pre { white-space: pre-wrap; overflow-wrap: anywhere; font-size: .75rem; max-height: 20rem; overflow: auto; }
.approval-card { border: 1px solid var(--primary, #38bdf8); border-radius: .5rem; padding: .8rem; }
.result-scroll { overflow-x: auto; }.result-table { width: 100%; border-collapse: collapse; font-size: .8rem; text-align: left; }.result-table caption { text-align: left; margin-bottom: .4rem; }.result-table th, .result-table td { padding: .5rem .35rem; border-bottom: 1px solid var(--line, #334155); }
.rule-list { display: grid; gap: .65rem; padding: 0; list-style: none; font-size: .8rem; }.rule-list li { display: flex; flex-wrap: wrap; gap: .5rem; align-items: start; }.rule-list p { margin: .25rem 0 0; color: var(--muted, #94a3b8); }
.artifact-list { display: grid; gap: .45rem; }.tool-call { border-top: 1px solid var(--line, #334155); padding-block: .55rem; font-size: .8rem; }.tool-call summary { cursor: pointer; }
@container (max-width: 56rem) { .planning-layout.with-result { grid-template-columns: minmax(0,1fr); }.planning-result { max-height: none; }.turns { max-height: 50vh; } }
</style>
