<script setup lang="ts">
import {computed,ref,watch} from 'vue';import {useApi,useAuth} from '../../shared/api';
import {categoryCode,configurableSkill,publishedSkillVersions,skillInCategory} from '../../shared/skill-catalog';
const props=defineProps<{modelValue:any;skills:any[];appCode?:string;bizCategory?:string}>();const emit=defineEmits<{'update:modelValue':[value:any]}>();
const api=useApi(),auth=useAuth();const all=ref(false),versions=ref<number[]>([]),error=ref('');
const options=computed(()=>props.skills.filter(skill=>configurableSkill(skill,auth.roles)&&(all.value||skillInCategory(skill,categoryCode(props.bizCategory,props.appCode)))));
let sequence=0;
watch(()=>props.modelValue?.code,async code=>{const current=++sequence;versions.value=[];error.value='';if(!code)return;try{const skill=await api(`/admin/skills/${encodeURIComponent(code)}`);if(current===sequence)versions.value=publishedSkillVersions(skill);}catch(problem){if(current===sequence)error.value=problem instanceof Error?problem.message:'版本加载失败';}},{immediate:true});
function select(code:string){emit('update:modelValue',{...props.modelValue,code,version:undefined});}
</script>
<template><div class="skill-picker"><el-select :model-value="modelValue?.code" filterable placeholder="选择技能" @update:model-value="select"><el-option v-for="item in options" :key="item.code" :value="item.code" :label="item.name||item.code" /></el-select><el-select :model-value="modelValue?.version" placeholder="已发布版本" @update:model-value="emit('update:modelValue',{...modelValue,version:$event})"><el-option v-for="version in versions" :key="version" :value="version" :label="`v${version}`" /></el-select><el-checkbox v-model="all">全部有权配置</el-checkbox><small v-if="error" class="error">{{error}}</small><small v-else-if="modelValue?.code&&!versions.length" class="muted">暂无已发布版本</small></div></template>
<style scoped>.skill-picker{display:flex;gap:.5rem;flex-wrap:wrap;align-items:center}.skill-picker .el-select{width:13rem}</style>
