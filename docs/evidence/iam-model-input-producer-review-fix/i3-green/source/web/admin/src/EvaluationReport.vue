<script setup lang="ts">
import {computed} from 'vue';
import {evaluationSummary,evaluationLabel,pendingCase} from './evaluation-observer';
const props=defineProps<{report:any}>();const stats=computed(()=>evaluationSummary(props.report));
const labels:Record<string,string>={PENDING:'排队中',CREATED:'已创建',READY:'准备中',RUNNING:'运行中',WAITING_EXTERNAL:'等待外部任务',WAITING_APPROVAL:'等待人工确认',WAITING_STEP:'等待单步放行',SUCCEEDED:'执行成功',FAILED:'执行失败',CANCELLED:'已取消',ERROR:'执行出错'};
function result(row:any){return pendingCase(row.status)?labels[row.status]:row.status==='SUCCEEDED'?(row.passed?'通过':'输出不符'):row.error?'执行出错':labels[row.status]||row.status;}
</script>
<template>
  <section class="evaluation-report"><h2>评测报告</h2><p class="evaluation-status" role="status"><strong>{{evaluationLabel(report)}}</strong></p>
    <p>通过 {{stats.passed}} · 失败 {{stats.failed}} · 待完成 {{stats.pending}} · 共 {{stats.total}} 条</p><p>安全用例通过 {{stats.safetyPassed}} / {{stats.safety}}<span v-if="stats.safetyFailed">，失败 {{stats.safetyFailed}} 条</span></p>
    <p class="muted">数据集 {{report.datasetVersion}} · 草稿修订 {{report.draftRev}}。预期与完整输出精确比较。</p>
    <div class="case-list"><article v-for="row in report.results||[]" :key="row.id" class="case-result"><div class="row"><strong>{{row.id}}</strong><span>{{row.safety?'安全用例':'普通用例'}} · {{result(row)}}</span></div>
      <p v-if="row.status==='WAITING_APPROVAL'">评测等待明确的人工确认，页面不会自动批准。</p><p v-if="row.error" class="error">{{row.error}}</p>
      <div v-if="row.runId" class="actions"><a :href="'#/runs/'+encodeURIComponent(row.runId)">查看用例运行</a><a v-if="row.status==='WAITING_APPROVAL'" href="#/approvals">前往确认中心</a></div>
      <details><summary>预期与实际输出</summary><p>独立预期</p><pre>{{JSON.stringify(row.expected,null,2)}}</pre><p>实际输出</p><pre>{{JSON.stringify(row.actual,null,2)}}</pre><p v-if="row.attribution">{{row.attribution}}</p></details>
    </article></div><details><summary>高级：完整报告 JSON</summary><pre>{{JSON.stringify(report,null,2)}}</pre></details>
  </section>
</template>
<style scoped>.evaluation-report{min-width:0;overflow-wrap:anywhere}.evaluation-status{font-size:1.1rem}.case-result{border-top:1px solid var(--line);padding:1rem 0;overflow-wrap:anywhere}.case-result details{margin-top:.75rem}.case-result .actions{flex-wrap:wrap;gap:1rem}@media(max-width:600px){.case-result .row{align-items:flex-start;flex-wrap:wrap}}</style>
