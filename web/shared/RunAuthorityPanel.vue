<script setup lang="ts">
import type { RunAuthority } from './server-authority';
import GoalOutcomePanel from './GoalOutcomePanel.vue';
import SemanticEvidence from './SemanticEvidence.vue';
const props = defineProps<{
  authority: RunAuthority;
  runStatus?: string;
  modelText?: string;
}>();
</script>
<template>
  <section aria-label="服务端业务依据">
    <el-alert v-if="props.authority.error" type="warning" :closable="false" :title="props.authority.error" />
    <GoalOutcomePanel v-if="props.authority.goals.length" :goals="props.authority.goals" :run-status="props.runStatus" />
    <article v-for="receipt in props.authority.receipts" :key="receipt.callId">
      <SemanticEvidence title="语义读回依据" :server="receipt.server" :model-text="props.modelText" />
      <p>正式版本：{{receipt.release}} · 原始时效：{{receipt.observedAt}}</p>
      <details v-if="receipt.facts.length"><summary>已授权事实</summary><pre>{{JSON.stringify(receipt.facts,null,2)}}</pre></details>
    </article>
    <p v-if="!props.authority.goals.length&&!props.authority.receipts.length">尚无可核验的业务目标或语义回执；模型文本不作为业务完成依据。</p>
  </section>
</template>
