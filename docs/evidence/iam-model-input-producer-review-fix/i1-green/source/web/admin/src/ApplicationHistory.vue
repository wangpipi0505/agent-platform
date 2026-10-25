<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch } from 'vue';
import { useApi, useAuth } from '../../shared/api';
import { diffValue } from '../../shared/statistics-lifecycle';
const props=defineProps<{appCode:string;versions:any[];revision?:number}>();
const api=useApi(),auth=useAuth(),logs=ref<any[]>([]),error=ref(''),diffError=ref(''),busy=ref(false),comparing=ref(false),comparison=ref<any>(),fromVersion=ref<number>(),toVersion=ref<number>();
let loadSequence=0,compareSequence=0,disposed=false;
async function load() {const sequence=++loadSequence;busy.value=true;error.value='';logs.value=[];try{const rows=await api<any[]>('/admin/apps/'+encodeURIComponent(props.appCode)+'/lifecycle-logs');if(sequence===loadSequence)logs.value=rows;}catch(e:any){if(sequence===loadSequence)error.value=e.message;}finally{if(sequence===loadSequence)busy.value=false;}}
watch(()=>[props.appCode,props.revision],()=>{comparison.value=undefined;void load();});
watch(()=>props.versions,()=>{const values=props.versions.map(v=>v.versionNo);toVersion.value=values[0];fromVersion.value=values[1]??values[0];},{immediate:true});
function invalidateComparison() {compareSequence++;comparison.value=undefined;diffError.value='';comparing.value=false;}
watch(()=>[props.appCode,fromVersion.value,toVersion.value,auth.token],invalidateComparison,{flush:'sync'});
onBeforeUnmount(()=>{disposed=true;loadSequence++;invalidateComparison();});
async function compare() {
  if(disposed)return;
  const sequence=++compareSequence,appCode=props.appCode,from=fromVersion.value,to=toVersion.value,token=auth.token;
  const current=()=>!disposed&&sequence===compareSequence&&props.appCode===appCode&&fromVersion.value===from&&toVersion.value===to&&auth.token===token;
  comparison.value=undefined;diffError.value='';comparing.value=true;
  try {
    if(!from||!to)throw Error('请选择两个已发布版本');
    const result=await api('/admin/apps/'+encodeURIComponent(appCode)+'/versions/diff?fromVersion='+from+'&toVersion='+to);
    if(current())comparison.value=result;
  }catch(e:any){if(current())diffError.value=e.message;}
  finally{if(current())comparing.value=false;}
}
onMounted(load);
const operationLabels:Record<string,string>={ADD:'新增',REMOVE:'移除',REPLACE:'修改'};
</script>
<template>
  <div class="panel"><h2>版本对照</h2><p class="muted">对照已发布版本的应用信息与业务配置。</p>
    <el-empty v-if="!versions.length" description="发布第一个版本后可查看对照" :image-size="60" />
    <template v-else><div class="history-select"><el-select v-model="fromVersion" aria-label="原版本" placeholder="原版本"><el-option v-for="v in versions" :key="v.versionNo" :value="v.versionNo" :label="'v'+v.versionNo"/></el-select><span>→</span><el-select v-model="toVersion" aria-label="目标版本" placeholder="目标版本"><el-option v-for="v in versions" :key="v.versionNo" :value="v.versionNo" :label="'v'+v.versionNo"/></el-select><el-button :loading="comparing" @click="compare">对照版本</el-button></div>
    <div v-if="diffError" class="error" role="alert">{{diffError}}</div>
    <el-table v-if="comparison" :data="comparison.changes" empty-text="两个版本的业务配置一致"><el-table-column label="变更" width="100"><template #default="{row}">{{operationLabels[row.operation]||row.operation}}</template></el-table-column><el-table-column prop="path" label="配置路径" min-width="180"/><el-table-column label="原值" min-width="150"><template #default="{row}"><span class="diff-value">{{diffValue(row,'before')}}</span></template></el-table-column><el-table-column label="新值" min-width="150"><template #default="{row}"><span class="diff-value">{{diffValue(row,'after')}}</span></template></el-table-column><el-table-column type="expand"><template #default="{row}"><pre>{{JSON.stringify(row,null,2)}}</pre></template></el-table-column></el-table>
    <details v-if="comparison"><summary>版本摘要</summary><p>v{{comparison.fromVersion}}：{{comparison.fromSha256}}</p><p>v{{comparison.toVersion}}：{{comparison.toSha256}}</p></details></template>
  </div>
  <div class="panel"><div class="row"><h2>生命周期记录</h2><el-button text :loading="busy" @click="load">刷新记录</el-button></div><div v-if="error" class="error" role="alert">{{error}}</div><el-skeleton v-if="busy" :rows="3" animated/><el-table v-else :data="logs" empty-text="暂无生命周期记录"><el-table-column prop="createdAt" label="时间 UTC" min-width="190"/><el-table-column prop="actor" label="操作人" min-width="120"/><el-table-column prop="action" label="操作" width="120"/><el-table-column label="状态变化" min-width="190"><template #default="{row}">{{row.fromState??'—'}} → {{row.toState??'—'}}</template></el-table-column><el-table-column type="expand"><template #default="{row}"><pre>{{JSON.stringify(row.detail,null,2)}}</pre></template></el-table-column></el-table></div>
</template>
<style scoped>.history-select{display:flex;align-items:center;gap:.75rem;margin:1rem 0;flex-wrap:wrap}.history-select .el-select{width:140px}.diff-value{display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden;overflow-wrap:anywhere}details{margin-top:1rem;overflow-wrap:anywhere}summary{cursor:pointer}pre{margin:1rem;white-space:pre-wrap}.panel+.panel{margin-top:1rem}</style>
