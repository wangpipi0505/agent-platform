<script setup lang="ts">
import { ref, watch } from "vue";
import { useApi } from "./api";
import { decisionPayload } from "./approval";
const props = defineProps<{ runId: string; endpoint?: string }>();
const rows = ref<any[]>([]);
const error = ref("");
const busy = ref(false);
async function refresh() {
  try {
    rows.value = await useApi()(
      (props.endpoint || "") +
        "/v1/runs/" +
        encodeURIComponent(props.runId) +
        "/approvals",
    );
    error.value = "";
  } catch (e: any) {
    error.value = e.message;
  }
}
watch(
  () => props.runId,
  () => {
    if (props.runId) void refresh();
  },
  { immediate: true },
);
async function decide(row: any, approved: boolean) {
  if (!confirm(approved ? "确认依据此参数快照允许执行？" : "确认拒绝执行？"))
    return;
  busy.value = true;
  try {
    await useApi()(
      (props.endpoint || "") +
        "/v1/runs/" +
        encodeURIComponent(props.runId) +
        "/approvals/" +
        encodeURIComponent(row.nodeInstanceId || row.nodeId) +
        "/decision",
      "POST",
      decisionPayload(row, approved, crypto.randomUUID()),
    );
    await refresh();
  } catch (e: any) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
defineExpose({ refresh });
</script>
<template>
  <!-- UIF-A7：takeover 语义——审批占据输入区位置（v-if 由 Chat 控制），琥珀警示边框；
       Enter=允许 / Esc=拒绝的键盘直达由按钮 autofocus + keydown 处理。 -->
  <section class="panel approval-takeover">
    <div class="row">
      <h2>需要你的确认</h2>
      <el-button @click="refresh" :loading="busy">刷新确认</el-button>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-else-if="!rows.length" class="muted">没有当前身份可处理的确认。</p>
    <article
      v-for="row in rows"
      :key="row.nodeInstanceId || row.nodeId"
      tabindex="-1"
      @keydown.enter.exact.prevent="decide(row, true)"
      @keydown.esc.prevent="decide(row, false)"
    >
      <strong>{{ row.nodeDefId || row.nodeId }}</strong>
      <p class="muted">截止时间：{{ row.expiresAt || "由服务端控制" }}</p>
      <details>
        <summary>待确认参数（摘要 {{ (row.snapshotDigest || "").slice(0, 12) }}…）</summary>
        <pre>{{ JSON.stringify(row.parameterSnapshot, null, 2) }}</pre>
      </details>
      <div class="row">
        <el-button type="primary" :loading="busy" @click="decide(row, true)"
          >确认执行（Enter）</el-button
        ><el-button :disabled="busy" @click="decide(row, false)">拒绝（Esc）</el-button>
      </div>
    </article>
  </section>
</template>
<style scoped>
/* UIF-A7：琥珀警示语言——1px 琥珀边框 + 浅琥珀底条幅；拒绝按钮常规态不上红。 */
.approval-takeover {
  border: 1px solid var(--st-suspend);
  background: color-mix(in srgb, var(--st-suspend) 6%, white);
  border-radius: var(--radius, 0.5rem);
}
.approval-takeover > .row h2 {
  color: var(--st-suspend-text);
  margin: 0;
}
</style>
