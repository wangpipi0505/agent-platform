<script lang="ts">
import {reactive as reactiveAttempts} from 'vue';
// Only ACK uncertainty, not reports, credentials or cases. Keep it across a sign-in
// remount in this page session; aborting HTTP does not prove the create was rejected.
const unconfirmedCreations=reactiveAttempts(new Map<string,string>());
</script>
<script setup lang="ts">
import {computed,ref,watch,onBeforeUnmount} from 'vue';
import {useApi,useAuth} from '../../shared/api';
import EvaluationInputs from './EvaluationInputs.vue';
import EvaluationReport from './EvaluationReport.vue';
import {observeEvaluation,evaluationLabel} from './evaluation-observer';
const props=defineProps<{appCode:string;draftRev:number;candidate?:any}>();
const emit=defineEmits<{report:[report:any];bind:[];activity:[active:boolean]}>();
const api=useApi(),auth=useAuth(),inputs=ref<InstanceType<typeof EvaluationInputs>>();
const report=ref<any>(),reports=ref<any[]>([]),selected=ref(''),error=ref(''),notice=ref<any>(),creating=ref(false),listLoading=ref(false),choosing=ref(false);
const identity=computed(()=>JSON.stringify([props.appCode,props.draftRev,props.candidate?.draftRev,props.candidate?.candidateSha256]));
const attemptKey=computed(()=>JSON.stringify([identity.value,auth.user?.tenantId||'',auth.user?.principalId||'']));
const unknownCreate=computed(()=>unconfirmedCreations.has(attemptKey.value)&&!creating.value);
const matches=(r:any)=>r?.appCode===props.appCode&&r.draftRev===props.candidate?.draftRev&&r.candidateSha256===props.candidate?.candidateSha256;
const valid=(r:any,id?:string)=>matches(r)&&!!(r.evalReportId||r.id)&&(!id||(r.evalReportId||r.id)===id)&&typeof r.createdBy==='string'&&!!r.createdBy&&Array.isArray(r.results)&&['PENDING','RUNNING','WAITING_EXTERNAL','WAITING_APPROVAL','ACCEPTED','REJECTED','ERROR'].includes(r.status);
const matching=computed(()=>reports.value.filter(matches));
const running=computed(()=>report.value&&!['ACCEPTED','REJECTED','ERROR'].includes(report.value.status));
let generation=0,alive=true,controller=new AbortController(),observer=new AbortController(),listSequence=0,detailSequence=0;
const canResume=()=>!!auth.user?.principalId&&auth.user.principalId===report.value?.createdBy&&auth.can('edit');
function current(g:number){return alive&&g===generation&&!controller.signal.aborted;}
function observe(resumeError=false){observer.abort();observer=new AbortController();const g=generation;notice.value=undefined;
  void observeEvaluation(report.value,{api,signal:observer.signal,canResume,publish:value=>{if(current(g)&&matches(value)){report.value=value;emit('report',value);}},notice:value=>{if(current(g))notice.value=value;},resumeError});
}
function failure(e:any){if(e.name!=='AbortError'){error.value=e.status===403?'当前账号没有读取或恢复评测报告的权限。':e.status===401?'登录已失效，请重新登录。':e.message;if(e.status>=400&&e.status<500){observer.abort();notice.value={phase:'stopped',message:'请求被拒绝，已停止自动观察。',retryable:false};}}}
async function list(){const g=generation,ticket=++listSequence;listLoading.value=true;error.value='';try{const rows=await api('/admin/evaluations','GET',undefined,controller.signal);if(current(g)&&ticket===listSequence)reports.value=rows;}catch(e){if(current(g)&&ticket===listSequence)failure(e);}finally{if(current(g)&&ticket===listSequence)listLoading.value=false;}}
async function choose(){if(creating.value||choosing.value)return;observer.abort();const g=generation,ticket=++detailSequence,id=selected.value;report.value=undefined;notice.value=undefined;error.value='';if(!id)return;choosing.value=true;
  emit('activity',true);
  try{const value=await api('/admin/evaluations/'+encodeURIComponent(id),'GET',undefined,controller.signal);if(!current(g)||ticket!==detailSequence||selected.value!==id)return;if(!valid(value,id))throw Error('报告标识、所有者或候选与当前预检不匹配，请重新预检。');if(value.createdBy===auth.user?.principalId&&value.datasetVersion===unconfirmedCreations.get(attemptKey.value))unconfirmedCreations.delete(attemptKey.value);report.value=value;emit('report',value);observe();}
  catch(e){if(current(g)&&ticket===detailSequence)failure(e);}finally{if(current(g)&&ticket===detailSequence){choosing.value=false;emit('activity',unknownCreate.value);}}
}
async function create(){if(creating.value||choosing.value||running.value||unknownCreate.value||!auth.can('edit'))return;const g=generation;error.value='';
  let dataset:any;try{if(!props.candidate?.candidateSha256)throw Error('请先预检当前候选');dataset=inputs.value!.collect();}catch(e){failure(e);return;}
  const frozen={appCode:props.appCode,draftRev:props.candidate.draftRev,candidateSha256:props.candidate.candidateSha256,...dataset};
  emit('activity',true);
  const key=attemptKey.value;
  creating.value=true;unconfirmedCreations.set(key,frozen.datasetVersion);observer.abort();listSequence++;listLoading.value=false;
  try{const value=await api('/admin/evaluations','POST',frozen,controller.signal);if(!current(g))return;
    if(!valid(value))throw Error('创建响应缺少有效冻结报告，请从已受理报告列表找回，避免重复创建。');
    unconfirmedCreations.delete(key);
    report.value=value;
    selected.value=value.evalReportId||value.id;reports.value=[value,...reports.value.filter(r=>(r.evalReportId||r.id)!==selected.value)];emit('report',value);observe();
  }catch(e:any){if(current(g)){if(e.status>=400&&e.status<500)unconfirmedCreations.delete(key);failure(e);}}
  finally{if(current(g)){creating.value=false;emit('activity',unknownCreate.value);}}
}
function retry(){if(report.value&&canResume())observe(report.value.status==='ERROR');}
watch(identity,()=>{generation++;listSequence++;detailSequence++;controller.abort();observer.abort();controller=new AbortController();reports.value=[];report.value=undefined;selected.value='';notice.value=undefined;creating.value=false;listLoading.value=false;choosing.value=false;error.value='';emit('activity',unknownCreate.value);if(auth.token)void list();},{immediate:true,flush:'sync'});
watch(()=>[auth.token,auth.user?.tenantId,auth.user?.principalId,auth.can('edit')],()=>{generation++;listSequence++;detailSequence++;controller.abort();observer.abort();controller=new AbortController();creating.value=false;listLoading.value=false;choosing.value=false;report.value=undefined;reports.value=[];selected.value='';error.value='';emit('activity',unknownCreate.value);notice.value={phase:'stopped',message:'身份或权限已变化，已停止自动观察。请刷新报告列表。',retryable:false};},{flush:'sync'});
onBeforeUnmount(()=>{alive=false;generation++;controller.abort();observer.abort();});
</script>
<template>
  <section class="panel"><h2>2. 运行候选评测</h2><p class="muted">评测冻结当前候选和用例。离开页面仅停止观察，任务仍可继续。</p>
    <template v-if="candidate"><EvaluationInputs :key="appCode" ref="inputs" :app-code="appCode"/><el-button type="primary" :loading="creating" :disabled="creating||choosing||running||unknownCreate" v-permission="'edit'" @click="create">执行候选评测</el-button></template><p v-else class="muted">先运行发布预检，再选择当前候选的用例或已有报告。</p>
    <details class="existing-reports" :open="unknownCreate"><summary>找回已受理报告</summary><p v-if="!candidate" class="muted">请先预检，以匹配当前应用、修订与候选摘要。</p><el-select v-model="selected" aria-label="已受理评测报告" placeholder="选择当前候选的报告" :disabled="listLoading||choosing||creating||!candidate" @change="choose"><el-option v-for="r in matching" :key="r.evalReportId||r.id" :value="r.evalReportId||r.id" :label="r.datasetVersion+' · '+evaluationLabel(r)+(r.createdBy===auth.user?.principalId?' · 我发起的':' · 仅查看')"/></el-select><el-button :loading="listLoading" :disabled="creating" @click="list">刷新报告列表</el-button><p v-if="candidate&&!matching.length&&!listLoading" class="muted">当前候选没有可选择的已受理报告。</p></details>
    <p v-if="choosing" class="muted" role="status">正在载入所选报告，请稍候…</p><div v-if="unknownCreate" class="error" role="alert">尚未确认创建结果。请刷新已受理报告列表找回，页面不会自动重建报告。</div><div v-if="error" class="error" role="alert">{{error}}</div>
  </section>
  <section v-if="report" class="panel"><EvaluationReport :report="report"/><p v-if="notice" class="muted" role="status">{{notice.message}}</p><el-button v-if="notice?.retryable&&canResume()" :disabled="creating" @click="retry">{{report.status==='ERROR'?'重试此报告':'重新连接报告'}}</el-button><el-button v-if="report.status==='ACCEPTED'" v-permission="'edit'" @click="emit('bind')">重新预检并绑定报告</el-button></section>
</template>
<style scoped>.existing-reports{margin-top:1rem}.existing-reports .el-select{width:min(440px,100%);margin:.75rem .5rem .75rem 0}.panel>.el-button{margin-top:.75rem}@media(max-width:600px){.panel>.el-button{min-height:44px}}</style>
