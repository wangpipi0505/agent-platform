<script setup lang="ts">
import { ref, reactive, onMounted, onBeforeUnmount, watch } from 'vue';
import { useAuth, useApi } from '../../shared/api';
import { defaultStatisticsWindow, statisticsQuery, rebuildBody, tokenLabel, statisticsPages, percentage, milliseconds } from '../../shared/statistics-lifecycle';
import FailureSamples from './FailureSamples.vue';
const auth=useAuth(),api=useApi();
const filters=reactive({...defaultStatisticsWindow(),appCode:'',bizSystemCode:'',callKind:'',page:1,pageSize:20});
const report=ref<any>(),busy=ref(false),rebuilding=ref(false),error=ref(''),snapshots=ref<any[]>([]),snapshotError=ref(''),snapshotId=ref('');
let pending={key:'',id:''};
let appliedFilters={...filters},requestSequence=0,snapshotSequence=0,disposed=false;
function invalidate() {requestSequence++;snapshotSequence++;report.value=undefined;snapshots.value=[];busy.value=false;rebuilding.value=false;error.value='';snapshotError.value='';snapshotId.value='';pending={key:'',id:''};}
watch(()=>[auth.can('admin'),auth.token],invalidate,{flush:'sync'});
onBeforeUnmount(()=>{disposed=true;invalidate();});
async function listSnapshots() {
  if(disposed||!auth.can('admin'))return;
  const sequence=++snapshotSequence,token=auth.token;
  const current=()=>!disposed&&sequence===snapshotSequence&&auth.can('admin')&&auth.token===token;
  try {const rows=await api('/admin/statistics/snapshots');if(current()){snapshots.value=rows;snapshotError.value='';}}catch(e:any){if(current())snapshotError.value=e.message;}
}
async function query(reset=true) {
  if(disposed||!auth.can('admin')||busy.value||rebuilding.value)return;
  const sequence=++requestSequence,token=auth.token;
  const current=()=>!disposed&&sequence===requestSequence&&auth.can('admin')&&auth.token===token;
  busy.value=true;error.value='';report.value=undefined;
  try {
    // Pagination belongs to the submitted report, never to edits still in the form.
    const selected=reset?{...filters,page:1}:{...appliedFilters,page:filters.page,pageSize:filters.pageSize};
    const savedId=reset?'':snapshotId.value;
    const path=savedId?'/admin/statistics/snapshots/'+encodeURIComponent(savedId)+'?page='+filters.page+'&pageSize='+filters.pageSize:'/admin/statistics?'+statisticsQuery(selected);
    if(reset){appliedFilters=selected;filters.page=1;snapshotId.value='';}
    const result=await api(path);
    if(!current())return;
    report.value=result;filters.pageSize=result.pageSize??filters.pageSize;
    if(savedId&&result.filters){const saved=result.filters;Object.assign(filters,{from:saved.from.slice(0,10),to:saved.to.slice(0,10),appCode:saved.appCode??'',bizSystemCode:saved.bizSystemCode??'',callKind:saved.callKind??''});appliedFilters={...filters};}
  }catch(e:any){if(current())error.value=e.status===403?'当前账号没有平台统计权限':e.message;}
  finally{if(current())busy.value=false;}
}
async function rebuild() {
  if(disposed||rebuilding.value||busy.value||!auth.can('admin'))return;
  const sequence=++requestSequence,token=auth.token;
  const current=()=>!disposed&&sequence===requestSequence&&auth.can('admin')&&auth.token===token;
  error.value='';
  try {
    const selected={...filters,page:1};
    const key=JSON.stringify(rebuildBody(selected,'pending'));
    if(pending.key!==key)pending={key,id:'stats_'+crypto.randomUUID()};
    const body=rebuildBody(selected,pending.id);
    rebuilding.value=true;
    const result=await api('/admin/statistics/rebuild','POST',body);
    if(!current())return;
    appliedFilters=selected;report.value=result;filters.page=1;filters.pageSize=result.pageSize??filters.pageSize;snapshotId.value=result.snapshotId;pending={key:'',id:''};
    await listSnapshots();
  }catch(e:any){if(current())error.value=e.message;}
  finally{if(current())rebuilding.value=false;}
}
async function readSnapshot(id:string) {if(disposed||!auth.can('admin')||busy.value||rebuilding.value)return;snapshotId.value=id;filters.page=1;await query(false);}
onMounted(()=>{if(auth.can('admin')){void query();void listSnapshots();}});
defineExpose({refresh:()=>query()});
</script>
<template>
  <el-result v-if="!auth.can('admin')" icon="warning" title="需要管理员权限" sub-title="请使用具有 ADMIN 角色的账号查看平台统计。" />
  <template v-else>
    <div class="panel"><h2>统计范围</h2><el-form label-position="top" class="statistics-filters" @submit.prevent="query()">
      <el-form-item label="开始日期（UTC，包含）"><input v-model="filters.from" type="date" aria-label="开始日期（UTC，包含）" /></el-form-item>
      <el-form-item label="结束日期（UTC，不包含）"><input v-model="filters.to" type="date" aria-label="结束日期（UTC，不包含）" /></el-form-item>
      <el-form-item label="应用编码"><el-input v-model="filters.appCode" clearable placeholder="全部应用" /></el-form-item>
      <el-form-item label="业务系统编码"><el-input v-model="filters.bizSystemCode" clearable placeholder="全部业务系统" /></el-form-item>
      <el-form-item label="运行用途"><el-select v-model="filters.callKind" clearable placeholder="全部用途"><el-option label="正式" value="FORMAL"/><el-option label="调试" value="DEBUG"/><el-option label="评测" value="EVALUATION"/></el-select></el-form-item>
      <div class="actions"><el-button type="primary" native-type="submit" :loading="busy" :disabled="rebuilding">查询统计</el-button><el-button :loading="rebuilding" :disabled="busy||rebuilding" @click="rebuild">重建并保存快照</el-button></div>
    </el-form><p class="muted">按运行开始时间筛选，最长 366 天。数据较多时请缩短时间或增加筛选。</p></div>
    <div v-if="error" class="error" role="alert">{{error}}<p>调整筛选或稍后重试。</p></div>
    <el-skeleton v-if="busy" class="panel" :rows="6" animated />
    <template v-else-if="report">
      <div class="panel"><div class="row"><h2>运行分组</h2><span class="muted">共 {{report.groupsTotal}} 个分组 · 第 {{report.page}} 页</span></div>
        <el-table :data="report.groups" empty-text="当前页没有运行分组">
          <el-table-column prop="date" label="日期 UTC" width="120"/><el-table-column prop="appCode" label="应用" min-width="120"/><el-table-column label="业务系统" min-width="120"><template #default="{row}">{{row.bizSystemCode??'未标记'}}</template></el-table-column><el-table-column prop="callKind" label="用途" width="120"/>
          <el-table-column prop="runs" label="运行数" width="90"/><el-table-column label="成功率" width="100"><template #default="{row}">{{percentage(row.successRate)}}</template></el-table-column><el-table-column prop="failed" label="失败" width="80"/><el-table-column prop="unfinished" label="未结束" width="90"/>
          <el-table-column label="P95 耗时" width="130"><template #default="{row}">{{milliseconds(row.p95DurationMs)}}</template></el-table-column><el-table-column prop="confirmationCount" label="确认次数" width="100"/>
          <el-table-column label="模型 Token" width="120"><template #default="{row}">{{tokenLabel(row.model)}}</template></el-table-column>
          <el-table-column type="expand"><template #default="{row}"><div class="statistics-detail">成功 {{row.succeeded}} · 取消 {{row.cancelled}} · 平均耗时 {{milliseconds(row.averageDurationMs)}} · 模型发送 {{row.model?.calls??'—'}} 次 · 已计量 Token {{row.model?.knownTokens??'—'}} · 缺少用量 {{row.model?.usageMissingCalls??'—'}} 次</div></template></el-table-column>
        </el-table>
      </div>
      <div class="panel"><div class="row"><h2>技能分组</h2><span class="muted">共 {{report.skillGroupsTotal}} 个分组 · 第 {{report.page}} 页</span></div>
        <el-table :data="report.skillGroups" empty-text="当前页没有技能分组"><el-table-column prop="date" label="日期 UTC" width="120"/><el-table-column prop="appCode" label="应用" min-width="120"/><el-table-column prop="bizSystemCode" label="业务系统" min-width="120"/><el-table-column prop="callKind" label="用途" width="120"/><el-table-column prop="skillCode" label="技能" min-width="130"/><el-table-column prop="skillVersionId" label="版本" width="90"/><el-table-column prop="calls" label="调用数" width="90"/><el-table-column prop="mockCalls" label="Mock" width="80"/><el-table-column prop="failed" label="失败" width="80"/><el-table-column label="成功率" width="100"><template #default="{row}">{{percentage(row.successRate)}}</template></el-table-column><el-table-column label="P95 耗时" width="130"><template #default="{row}">{{milliseconds(row.p95DurationMs)}}</template></el-table-column></el-table>
      </div>
      <div class="row statistics-pagination"><p class="muted">两张表共用页码，分组总数各自独立。</p><el-pagination v-model:current-page="filters.page" :page-count="statisticsPages(report,filters.pageSize)" layout="prev,pager,next" :disabled="busy||rebuilding" @current-change="query(false)" /></div>
      <div class="panel"><h2>失败样本</h2><FailureSamples :failures="report.failures||[]" /></div>
      <div class="panel"><details><summary>统计口径与来源时刻{{snapshotId?' · 快照 '+snapshotId:''}}</summary><p>窗口 {{report.filters?.from}} 至 {{report.filters?.to}}（UTC，左闭右开），按运行开始时间选取。各来源分别读取一致快照，不代表跨服务同一时刻。</p><el-descriptions :column="1" border><el-descriptions-item label="一致性">{{report.consistency}}</el-descriptions-item><el-descriptions-item v-for="(time,source) in report.sourceSnapshots" :key="source" :label="String(source)">{{time}}</el-descriptions-item><el-descriptions-item label="计数粒度">运行 / 逻辑技能调用 / 模型实际发送 / 已完成确认事件</el-descriptions-item></el-descriptions><p class="muted">Mock 独立计数，不计真实技能成功率与耗时。模型用量未齐全时显示“待计量”。运行耗时包含等待；技能耗时为实际业务执行时间。当前页数据不作为全量总计。</p></details></div>
    </template>
    <div class="panel"><div class="row"><h2>已保存快照</h2><el-button text @click="listSnapshots">刷新列表</el-button></div><div v-if="snapshotError" class="error" role="alert">{{snapshotError}}</div><el-table :data="snapshots" empty-text="尚无快照，查询范围确定后可重建保存"><el-table-column prop="createdAt" label="保存时间 UTC" min-width="190"/><el-table-column label="范围" min-width="220"><template #default="{row}">{{row.filters?.appCode||'全部应用'}} · {{row.filters?.callKind||'全部用途'}}<div class="muted">{{row.filters?.from}} → {{row.filters?.to}}</div></template></el-table-column><el-table-column label="状态" width="100"><template #default="{row}">{{row.active?'当前快照':'历史快照'}}</template></el-table-column><el-table-column label="操作" width="110"><template #default="{row}"><el-button link type="primary" :disabled="busy||rebuilding" @click="readSnapshot(row.snapshotId)">读取快照</el-button></template></el-table-column></el-table></div>
  </template>
</template>
<style scoped>
.statistics-filters{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:0 1rem}.statistics-filters input[type=date]{width:100%;min-width:0;min-height:34px;border:1px solid var(--el-border-color);border-radius:var(--el-border-radius-base);padding:0 10px;color:var(--el-text-color-regular);font:inherit;background:var(--el-fill-color-blank)}.statistics-filters .actions{align-self:center;flex-wrap:wrap}.statistics-detail{padding:1rem}.statistics-pagination{margin:1rem 0}.panel+.panel{margin-top:1rem}summary{cursor:pointer;font-weight:600}details p{line-height:1.7;overflow-wrap:anywhere}@media(max-width:900px){.statistics-filters{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:600px){.statistics-filters{grid-template-columns:1fr}.row{flex-wrap:wrap}}
</style>
