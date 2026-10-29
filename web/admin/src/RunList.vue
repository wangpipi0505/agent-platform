<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { useApi } from "../../shared/api";
import { record } from "../../shared/run-protocol";
import RunObservation from "./RunObservation.vue";
import PageError from "./PageError.vue";
const props = defineProps<{ appCode?: string }>();
const api = useApi();
type RunRow = { runId: string; appCode: string; status?: string; statusLabel?: string; triggerType?: string; startedAt?: string; endedAt?: string; durationMs?: number; observation?: unknown };
const items = ref<RunRow[]>([]);
const total = ref(0);
const page = ref(1);
const size = ref(20);
const busy = ref(false);
const error = ref("");
const trace = ref("");
const filter = reactive({ q: "", status: "", range: [] as string[], appCode: "", packageId: "", routeProfileId: "", toolId: "", releaseId: "", traceId: "" });
let sequence = 0;
const statuses = [
  ["", "全部状态"],
  ["QUEUED", "排队中"], ["RUNNING", "运行中"], ["WAITING_APPROVAL", "等待确认"],
  ["WAITING_EXTERNAL", "等待外部"], ["SUCCEEDED", "成功"], ["FAILED", "失败"],
  ["CANCELLED", "已取消"],
] as const;
const statusLabel = (value?: string) =>
  statuses.find(([key]) => key === value)?.[1] || value || "—";
function durationText(row: RunRow) {
  if (row.durationMs != null) return `${row.durationMs} ms`;
  if (row.startedAt && row.endedAt) {
    const ms = new Date(row.endedAt).getTime() - new Date(row.startedAt).getTime();
    return Number.isFinite(ms) && ms >= 0 ? `${ms} ms` : "—";
  }
  return "—";
}
const formatTime = (value?: string) =>
  value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "—";
const traceText = (observation: unknown) => {
  const value = record(observation)?.traceId;
  return typeof value === "string" && value ? value : "未记录";
};
const sourceText = (observation: unknown) => {
  const value = record(observation)?.capabilityPackages;
  return Array.isArray(value) ? `${value.length} 个包来源` : "未记录";
};
async function load() {
  const ticket = ++sequence;
  busy.value = true;
  error.value = "";
  trace.value = "";
  try {
    const query = new URLSearchParams({ page: String(page.value), size: String(size.value) });
    const appCode = props.appCode || filter.appCode.trim();
    if (appCode) query.set("appCode", appCode);
    if (filter.q) query.set("q", filter.q);
    if (filter.status) query.set("status", filter.status);
    if (filter.range?.[0]) query.set("from", filter.range[0]);
    if (filter.range?.[1]) query.set("to", filter.range[1]);
    for (const key of ["packageId", "routeProfileId", "toolId", "releaseId", "traceId"] as const)
      if (filter[key].trim()) query.set(key, filter[key].trim());
    const data = await api<{ items: RunRow[]; total: number }>(`/admin/runs/page?${query.toString()}`);
    if (ticket !== sequence) return;
    if (!Array.isArray(data?.items) || typeof data.total !== "number" || !Number.isFinite(data.total))
      throw new Error("运行记录响应不完整，请刷新重试");
    items.value = data.items;
    total.value = data.total;
  } catch (e: unknown) {
    if (ticket !== sequence || record(e)?.name === "AbortError") return;
    const failure = record(e);
    error.value = typeof failure?.message === "string" ? failure.message : "运行记录加载失败";
    trace.value = typeof failure?.traceId === "string" ? failure.traceId : "";
  } finally {
    if (ticket === sequence) busy.value = false;
  }
}
function search() {
  page.value = 1;
  void load();
}
function reset() {
  Object.assign(filter, { q: "", status: "", range: [], appCode: "", packageId: "", routeProfileId: "", toolId: "", releaseId: "", traceId: "" });
  search();
}
onMounted(load);
watch(() => props.appCode, () => { page.value = 1; void load(); });
onBeforeUnmount(() => { sequence++; });
</script>

<template>
  <div class="panel">
    <div class="filters">
      <el-input v-model="filter.q" clearable placeholder="运行编码 / 应用编码" aria-label="运行编码搜索" @keyup.enter="search" />
      <el-select v-model="filter.status" clearable placeholder="状态" aria-label="运行状态">
        <el-option v-for="[value, label] in statuses.slice(1)" :key="value" :value="value" :label="label" />
      </el-select>
      <el-date-picker v-model="filter.range" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" style="width: 260px" />
    </div>
    <details class="observation-filters">
      <summary>按应用、能力来源与关联标识筛选</summary>
      <div class="filters">
        <el-input v-if="!props.appCode" v-model="filter.appCode" clearable maxlength="128" placeholder="精确应用编码" aria-label="精确应用编码" @keyup.enter="search" />
        <el-input v-model="filter.packageId" clearable maxlength="128" placeholder="能力包 packageId" aria-label="能力包标识" @keyup.enter="search" />
        <el-input v-model="filter.routeProfileId" clearable maxlength="128" placeholder="路线 routeProfileId" aria-label="路线标识" @keyup.enter="search" />
        <el-input v-model="filter.toolId" clearable maxlength="128" placeholder="工具 toolId" aria-label="工具标识" @keyup.enter="search" />
        <el-input v-model="filter.releaseId" clearable maxlength="128" placeholder="本体发布 releaseId" aria-label="本体发布标识" @keyup.enter="search" />
        <el-input v-model="filter.traceId" clearable maxlength="128" placeholder="关联 traceId" aria-label="关联追踪标识" @keyup.enter="search" />
      </div>
      <p class="muted">包与路线按同一冻结来源匹配；工具同时核对实际调用的精确技能版本。本体发布来自本次运行的上下文包记录。</p>
    </details>
    <div class="query-actions"><el-button type="primary" :loading="busy" @click="search">查询</el-button><el-button :disabled="busy" @click="reset">重置筛选</el-button></div>
    <PageError :error="error" :trace="trace" />
    <el-table v-loading="busy" :data="items">
      <el-table-column type="expand"><template #default="{ row }"><div class="observation-detail"><RunObservation :observation="row.observation" /></div></template></el-table-column>
      <el-table-column label="运行编码" width="250" show-overflow-tooltip>
        <template #default="{ row }"><a :href="'#/runs/' + encodeURIComponent(row.runId)">{{ row.runId }}</a></template>
      </el-table-column>
      <el-table-column prop="appCode" label="应用" min-width="150" show-overflow-tooltip />
      <el-table-column label="冻结能力来源" min-width="140"><template #default="{ row }">{{ sourceText(row.observation) }}</template></el-table-column>
      <el-table-column label="关联 Trace" min-width="220" show-overflow-tooltip><template #default="{ row }">{{ traceText(row.observation) }}</template></el-table-column>
      <el-table-column label="状态" width="110"><template #default="{ row }">{{ row.statusLabel || statusLabel(row.status) }}</template></el-table-column>
      <el-table-column prop="triggerType" label="用途" width="100" />
      <el-table-column label="开始时间" width="180"><template #default="{ row }">{{ formatTime(row.startedAt) }}</template></el-table-column>
      <el-table-column label="结束时间" width="180"><template #default="{ row }">{{ formatTime(row.endedAt) }}</template></el-table-column>
      <el-table-column label="耗时" width="100"><template #default="{ row }">{{ durationText(row) }}</template></el-table-column>
      <template #empty><el-empty :description="error ? '运行记录读取失败，请重试' : '没有匹配的运行记录，请调整筛选条件'" /></template>
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

<style scoped>
.observation-filters { margin: 0 0 1rem; }
.observation-filters summary { cursor: pointer; margin-bottom: .75rem; }
.observation-filters .filters > * { min-width: 12rem; }
.query-actions { margin-bottom: 1rem; }
.observation-detail { padding: .75rem 1.25rem; }
@media (max-width: 600px) {
  .observation-filters .filters > * { min-width: 100%; }
  .observation-detail { padding: .5rem; }
}
</style>
