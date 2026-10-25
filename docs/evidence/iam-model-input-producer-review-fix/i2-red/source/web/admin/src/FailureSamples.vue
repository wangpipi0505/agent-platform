<script setup lang="ts">
import { ref } from 'vue';
import { useApi } from '../../shared/api';
import { failureTarget } from '../../shared/statistics-lifecycle';
defineProps<{ failures:any[] }>();
const api=useApi(), opened=ref(false), busy=ref(false), error=ref(''), metadata=ref<any>();
async function open(href:string) {
  error.value='';metadata.value=undefined;
  try {
    const target=failureTarget(href);
    if(target.kind==='owner') { location.hash='/runs/'+encodeURIComponent(target.runId);return; }
    opened.value=true;busy.value=true;
    metadata.value=await api('/admin/statistics/runs/'+encodeURIComponent(target.runId));
  } catch(e:any) { error.value=e.status===403?'当前账号没有查看故障元数据的权限':e.message; }
  finally {busy.value=false;}
}
</script>
<template>
  <el-table :data="failures" empty-text="当前窗口没有失败样本">
    <el-table-column prop="errorSemantic" label="失败原因" min-width="170" />
    <el-table-column prop="count" label="次数" width="90" />
    <el-table-column label="运行样本" min-width="230"><template #default="{row}">
      <el-button v-if="row.href" link type="primary" @click="open(row.href)">{{row.runId}}</el-button>
      <el-button v-for="link in row.sampleLinks || []" :key="link.runId" link type="primary" @click="open(link.href)">{{link.runId}}</el-button>
    </template></el-table-column>
  </el-table>
  <div v-if="error && !opened" class="error" role="alert">{{error}}</div>
  <el-dialog v-model="opened" class="statistics-failure-dialog" title="运行故障" width="min(850px, 94vw)">
    <el-skeleton v-if="busy" :rows="4" animated /><div v-else-if="error" class="error" role="alert">{{error}}</div>
    <template v-else-if="metadata"><el-descriptions :column="2" border>
      <el-descriptions-item v-for="(label,key) in {runId:'运行编码',appCode:'应用',status:'状态',errorSemantic:'失败原因',stopReason:'停止原因',message:'说明',startedAt:'开始时间',endedAt:'结束时间'}" :key="key" :label="label">{{metadata[key] ?? '—'}}</el-descriptions-item>
    </el-descriptions><p class="muted">仅显示故障元数据。失败技能调用共 {{metadata.failedSkillCallsTotal ?? 0}} 条，最多显示 {{metadata.sampleLimit ?? 100}} 条。</p>
    <el-table :data="metadata.failedSkillCalls || metadata.skillCalls || []"><el-table-column prop="skillCode" label="技能"/><el-table-column prop="status" label="状态"/><el-table-column prop="errorSemantic" label="失败原因"/></el-table></template>
  </el-dialog>
</template>
<style scoped>:global(.statistics-failure-dialog){width:min(850px,94vw)!important}</style>
