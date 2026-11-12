<script setup lang="ts">
import {onMounted,ref} from 'vue';
import {useApi} from '../../shared/api';
import {businessCategories} from '../../shared/skill-catalog';
defineProps<{modelValue?:string|string[];multiple?:boolean}>();
const emit=defineEmits<{'update:modelValue':[value:any]}>();
const options=ref(businessCategories);const api=useApi();
onMounted(async()=>{try{const rows=await api<any[]>('/admin/classifications');const choices=rows.filter(item=>businessCategories.some(choice=>choice.code===item.code));if(choices.length)options.value=choices;}catch{}});
</script>
<template><el-select :model-value="modelValue" :multiple="multiple" clearable filterable placeholder="选择业务分类" @update:model-value="emit('update:modelValue',$event)"><el-option v-for="item in options" :key="item.code" :value="item.code" :label="item.name" /></el-select></template>
