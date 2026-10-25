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
  <section class="panel">
    <div class="row">
      <h2>运行确认</h2>
      <el-button @click="refresh" :loading="busy">刷新确认</el-button>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-else-if="!rows.length" class="muted">没有当前身份可处理的确认。</p>
    <article v-for="row in rows" :key="row.nodeInstanceId || row.nodeId">
      <strong>{{ row.nodeDefId || row.nodeId }}</strong>
      <p>截止时间：{{ row.expiresAt || "由服务端控制" }}</p>
      <pre>{{ JSON.stringify(row.parameterSnapshot, null, 2) }}</pre>
      <p class="muted">参数摘要：{{ row.snapshotDigest }}</p>
      <el-button type="primary" :loading="busy" @click="decide(row, true)"
        >确认执行</el-button
      ><el-button :disabled="busy" @click="decide(row, false)">拒绝</el-button>
    </article>
  </section>
</template>
