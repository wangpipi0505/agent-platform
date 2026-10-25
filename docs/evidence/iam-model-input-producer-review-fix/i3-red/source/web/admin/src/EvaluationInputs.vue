<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue';
import { useApi } from '../../shared/api';
const props = defineProps<{appCode:string}>();
const api = useApi();
const items = ref<any[]>([]), version = ref(''), dataset = ref<any>(), loading = ref(false), error = ref(''), manual = ref(false), manualVersion = ref(''), raw = ref('[]');
const hasMore = ref(false), afterVersion = ref('');
let active = true, generation = 0, sequence = 0, controller = new AbortController();
function failure(e:any) { if(e.name!=='AbortError') error.value = e.status===403 ? '当前账号没有读取评测数据集的权限。' : e.message; }
async function load(more=false) {
  const current=generation, ticket=++sequence; loading.value=true; error.value='';
  try {const query=new URLSearchParams({appCode:props.appCode,limit:'20'}); if(more && afterVersion.value)query.set('afterVersion',afterVersion.value);
    const data=await api('/admin/evaluation-datasets?'+query,'GET',undefined,controller.signal);
    if(!active||current!==generation||ticket!==sequence)return;
    items.value=more?[...items.value,...data.items]:data.items;hasMore.value=data.hasMore;afterVersion.value=data.nextAfterVersion;
  } catch(e) {if(active&&current===generation&&ticket===sequence)failure(e);} finally{if(active&&current===generation&&ticket===sequence)loading.value=false;}
}
async function select() {
  dataset.value=undefined;const current=generation,ticket=++sequence,selected=version.value;error.value='';
  if(!selected)return;loading.value=true;
  try{const data=await api(`/admin/evaluation-datasets/${encodeURIComponent(props.appCode)}/${encodeURIComponent(selected)}`,'GET',undefined,controller.signal);
    if(active&&current===generation&&ticket===sequence&&selected===version.value&&!manual.value)dataset.value=data;
  }catch(e){if(active&&current===generation&&ticket===sequence)failure(e);}finally{if(active&&current===generation&&ticket===sequence)loading.value=false;}
}
function collect() {
  if(manual.value) {const cases=JSON.parse(raw.value);if(!manualVersion.value.trim()||!Array.isArray(cases)||!cases.length)throw Error('请填写数据集版本与非空用例数组');return {datasetVersion:manualVersion.value.trim(),cases};}
  if(loading.value||!dataset.value||dataset.value.datasetVersion!==version.value)throw Error('请选择并载入已保存的数据集版本');
  return {datasetVersion:dataset.value.datasetVersion,cases:JSON.parse(JSON.stringify(dataset.value.cases))};
}
watch(()=>props.appCode,()=>{generation++;sequence++;controller.abort();controller=new AbortController();items.value=[];version.value='';dataset.value=undefined;manual.value=false;manualVersion.value='';raw.value='[]';void load();},{immediate:true});
watch(manual,()=>{sequence++;loading.value=false;dataset.value=undefined;if(!manual.value&&version.value)void select();});
onBeforeUnmount(()=>{active=false;generation++;controller.abort();});defineExpose({collect});
</script>
<template>
  <el-form v-if="!manual" label-position="top"><el-form-item label="已保存的数据集版本"><el-select v-model="version" aria-label="已保存的数据集版本" placeholder="选择由测试输入提升的版本" :disabled="loading" @change="select"><el-option v-for="item in items" :key="item.datasetVersion" :value="item.datasetVersion" :label="item.datasetVersion" /></el-select></el-form-item></el-form>
  <div v-if="!manual" class="actions"><el-button :disabled="loading" @click="load()">刷新数据集</el-button><el-button v-if="hasMore" :disabled="loading" @click="load(true)">载入更多版本</el-button></div>
  <p v-if="loading" class="muted" role="status">正在载入数据集…</p><p v-else-if="!manual && !items.length && !error" class="muted">暂无已保存数据集。可在调试页将命名输入提升为评测用例。</p>
  <p v-if="dataset && !manual" class="muted" role="status">已载入 {{dataset.cases.length}} 条不可变用例，执行时使用此版本保存的输入与预期值。</p>
  <div v-if="error" class="error" role="alert">{{error}}</div>
  <details @toggle="manual=($event.target as HTMLDetailsElement).open"><summary>高级：手工填写用例数组</summary><p class="muted">按完整输出精确比较，请独立填写预期值。相同版本不能覆盖已有内容。</p><el-form label-position="top"><el-form-item label="手工数据集版本"><el-input v-model="manualVersion" aria-label="手工数据集版本"/></el-form-item><el-form-item label="用例数组（inputs / expected / safety）"><el-input v-model="raw" type="textarea" :rows="6" aria-label="手工用例数组" placeholder='[{"inputs":{},"expected":{},"safety":false}]'/></el-form-item></el-form></details>
</template>
