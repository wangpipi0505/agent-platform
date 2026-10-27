<script setup lang="ts">
import { onMounted, reactive, ref, watch } from "vue";
import { useApi } from "../../shared/api";
const props = defineProps<{ appCode?: string }>();
const api = useApi();
const items = ref<any[]>([]);
const total = ref(0);
const page = ref(1);
const size = ref(20);
const busy = ref(false);
const error = ref("");
const filter = reactive({ q: "", status: "", range: [] as string[] });
const statuses = [
  ["", "全部状态"],
  ["QUEUED", "排队中"], ["RUNNING", "运行中"], ["WAITING_APPROVAL", "等待确认"],
  ["WAITING_EXTERNAL", "等待外部"], ["SUCCEEDED", "成功"], ["FAILED", "失败"],
  ["CANCELLED", "已取消"],
] as const;
const statusLabel = (value?: string) =>
  statuses.find(([key]) => key === value)?.[1] || value || "—";
function durationText(row: any) {
  if (row.durationMs != null) return `${row.durationMs} ms`;
  if (row.startedAt && row.endedAt) {
    const ms = new Date(row.endedAt).getTime() - new Date(row.startedAt).getTime();
    return Number.isFinite(ms) && ms >= 0 ? `${ms} ms` : "—";
  }
  return "—";
}
const formatTime = (value?: string) =>
  value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "—";
async function load() {
  busy.value = true;
  error.value = "";
  try {
    const query = new URLSearchParams({ page: String(page.value), size: String(size.value) });
    if (props.appCode) query.set("appCode", props.appCode);
    if (filter.q) query.set("q", filter.q);
    if (filter.status) query.set("status", filter.status);
    if (filter.range?.[0]) query.set("from", filter.range[0]);
    if (filter.range?.[1]) query.set("to", filter.range[1]);
    const data = await api<any>(`/admin/runs/page?${query.toString()}`);
    items.value = data?.items || [];
    total.value = Number(data?.total || 0);
  } catch (e: any) {
    error.value = e?.message || "运行记录加载失败";
  } finally {
    busy.value = false;
  }
}
function search() {
  page.value = 1;
  void load();
}
onMounted(load);
watch(() => props.appCode, () => { page.value = 1; void load(); });
</script>

<template>
  <div class="panel">
    <div class="filters">
      <el-input v-model="filter.q" clearable placeholder="运行编码 / 应用编码" aria-label="运行编码搜索" @keyup.enter="search" />
      <el-select v-model="filter.status" clearable placeholder="状态" aria-label="运行状态">
        <el-option v-for="[value, label] in statuses.slice(1)" :key="value" :value="value" :label="label" />
      </el-select>
      <el-date-picker v-model="filter.range" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" style="width: 260px" />
      <el-button type="primary" :loading="busy" @click="search">查询</el-button>
    </div>
    <div v-if="error" class="error" role="alert">{{ error }}</div>
    <el-table v-loading="busy" :data="items">
      <el-table-column label="运行编码" width="250" show-overflow-tooltip>
        <template #default="{ row }"><a :href="'#/runs/' + encodeURIComponent(row.runId)">{{ row.runId }}</a></template>
      </el-table-column>
      <el-table-column prop="appCode" label="应用" min-width="150" show-overflow-tooltip />
      <el-table-column label="状态" width="110"><template #default="{ row }">{{ row.statusLabel || statusLabel(row.status) }}</template></el-table-column>
      <el-table-column prop="triggerType" label="用途" width="100" />
      <el-table-column label="开始时间" width="180"><template #default="{ row }">{{ formatTime(row.startedAt) }}</template></el-table-column>
      <el-table-column label="结束时间" width="180"><template #default="{ row }">{{ formatTime(row.endedAt) }}</template></el-table-column>
      <el-table-column label="耗时" width="100"><template #default="{ row }">{{ durationText(row) }}</template></el-table-column>
    </el-table>
    <el-pagination
      v-model:current-page="page"
      v-model:page-size="size"
      :total="total"
      :page-sizes="[20, 50, 100]"
      layout="sizes,prev,pager,next,total"
      style="margin-top: 1rem"
      @current-change="load"
      @size-change="search"
    />
  </div>
</template>
