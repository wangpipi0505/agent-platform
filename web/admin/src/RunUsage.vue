<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from "vue";
import { useApi, useAuth } from "../../shared/api";
import { record } from "../../shared/run-protocol";
const auth = useAuth(), api = useApi();
const by = ref("app"), windowSeconds = ref(3600), appliedBy = ref("app"), appliedWindow = ref(3600);
const rows = ref<Record<string, unknown>[]>(), busy = ref(false), error = ref("");
const dimensions: Record<string, string> = { app: "应用", user: "用户", model: "模型" };
const statuses: Record<string, string> = {
  EXCEEDED: "已达应用上限", WITHIN_LIMIT: "已计量用量未达上限", USAGE_INCOMPLETE: "缺少用量，未判定",
  WINDOW_MISMATCH: "查询窗口与配额窗口不同", NOT_APPLICABLE: "本维度无独立配额",
  UNLIMITED: "未配置应用上限", DISABLED: "配额闸门关闭",
};
let sequence = 0, disposed = false;
function invalidate() { sequence++; rows.value = undefined; error.value = ""; busy.value = false; }
watch(() => [auth.generation, auth.can("admin")], invalidate, { flush: "sync" });
onBeforeUnmount(() => { disposed = true; invalidate(); });
function count(value: unknown) {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0 ? value.toLocaleString() : "未记录";
}
async function load() {
  if (busy.value || disposed || !auth.can("admin")) return;
  const current = ++sequence, generation = auth.generation, selectedBy = by.value, selectedWindow = windowSeconds.value;
  const valid = () => !disposed && current === sequence && auth.generation === generation && auth.can("admin");
  busy.value = true; error.value = ""; rows.value = undefined;
  try {
    const result = await api<unknown>(`/admin/usage/summary?by=${encodeURIComponent(selectedBy)}&windowSeconds=${selectedWindow}`);
    if (!Array.isArray(result) || result.some((row) => !record(row))) throw new Error("用量响应无效，请重试");
    if (valid()) {
      rows.value = result.map((row) => record(row)!);
      appliedBy.value = selectedBy; appliedWindow.value = selectedWindow;
    }
  } catch (failure: unknown) {
    if (valid()) error.value = failure instanceof Error ? failure.message : "用量查询失败，请稍后重试";
  } finally { if (valid()) busy.value = false; }
}
function open(event: Event) {
  if ((event.target as HTMLDetailsElement).open && !rows.value && !busy.value) void load();
}
</script>

<template>
  <details v-if="auth.can('admin')" class="panel run-usage" @toggle="open">
    <summary>Token 用量与配额</summary>
    <el-form class="usage-query" label-position="top" @submit.prevent="load">
      <el-form-item label="汇总维度"><el-select v-model="by"><el-option v-for="(name, value) in dimensions" :key="value" :label="name" :value="value" /></el-select></el-form-item>
      <el-form-item label="查询窗口"><el-select v-model="windowSeconds"><el-option label="近1小时" :value="3600" /><el-option label="近24小时" :value="86400" /><el-option label="近7天" :value="604800" /></el-select></el-form-item>
      <el-button native-type="submit" :loading="busy">查询用量</el-button>
    </el-form>
    <p v-if="error" class="error" role="alert">{{ error }}，可重新查询。</p>
    <el-skeleton v-if="busy" :rows="3" animated />
    <template v-else-if="rows">
      <p class="muted">按{{ dimensions[appliedBy] }}汇总近 {{ appliedWindow / 3600 }} 小时。Token 为已计量部分，缺少用量的调用单独列出；应用上限仅比较相同窗口。</p>
      <el-table :data="rows" empty-text="当前窗口没有已完成的模型调用">
        <el-table-column :label="dimensions[appliedBy]" min-width="180"><template #default="{ row }">{{ row.scope || '未记录' }}</template></el-table-column>
        <el-table-column label="已计量 Token" min-width="140"><template #default="{ row }">{{ count(row.tokens) }}</template></el-table-column>
        <el-table-column label="已完成调用" width="120"><template #default="{ row }">{{ count(row.invocations) }}</template></el-table-column>
        <el-table-column label="缺少用量" width="110"><template #default="{ row }">{{ count(row.usageMissingInvocations) }}</template></el-table-column>
        <el-table-column label="应用上限" width="130"><template #default="{ row }">{{ row.limitTokens === null ? '未配置或不适用' : count(row.limitTokens) }}</template></el-table-column>
        <el-table-column label="配额状态" min-width="210"><template #default="{ row }">{{ statuses[String(row.limitStatus)] || '未记录' }}<div v-if="typeof row.quotaWindowSeconds === 'number'" class="muted">配额窗口 {{ row.quotaWindowSeconds }} 秒</div></template></el-table-column>
      </el-table>
    </template>
  </details>
</template>

<style scoped>
.run-usage { margin-top: 1rem; } summary { cursor: pointer; font-weight: 600; }
.usage-query { display: flex; align-items: center; flex-wrap: wrap; gap: 1rem; margin-top: 1rem; }
.usage-query .el-form-item { min-width: 12rem; margin-bottom: 0; }
</style>
