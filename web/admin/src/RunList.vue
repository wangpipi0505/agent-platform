<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { useApi } from "../../shared/api";
import { record } from "../../shared/run-protocol";
import RunObservation from "./RunObservation.vue";
import PageHeader from "./PageHeader.vue";
import DataPage from "../../shared/components/DataPage.vue";
import EmptyState from "../../shared/components/EmptyState.vue";
import ErrorBanner from "../../shared/components/ErrorBanner.vue";

// F1-3 样板页①（方案 §7.1 表格页四段高度模型）：RunList 即 RunsPage 的 DataPage 载体——
// 页头/筛选(含已生效筛选 chip 回显)/表格(height=100% 表体滚动)/分页 20/50/100 钉底。
const props = defineProps<{ appCode?: string; title?: string; subtitle?: string }>();
const emit = defineEmits<{ (e: "refresh"): void }>();
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
// —— 已生效筛选 chip 回显（方案 §4 DataPage 行）：折叠的高级筛选生效后仍可见、可单个移除。
// chips 拍“查询”时点的快照（服务端筛选按查询语义生效），不是输入框实时值。
type FilterKey = Exclude<keyof typeof filter, "range">;
type Chip = { key: FilterKey | "rangeFrom" | "rangeTo"; label: string; value: string };
const chipLabels: Record<Chip["key"], string> = {
  q: "运行编码", status: "状态", appCode: "应用", packageId: "能力包",
  routeProfileId: "路线", toolId: "工具", releaseId: "本体发布", traceId: "Trace",
  rangeFrom: "开始日期", rangeTo: "结束日期",
};
const applied = ref<Chip[]>([]);
function snapshotChips(): Chip[] {
  const chips: Chip[] = [];
  const push = (key: Chip["key"], value?: string) => {
    const text = String(value ?? "").trim();
    if (text) chips.push({ key, label: chipLabels[key], value: text });
  };
  push("q", filter.q);
  push("status", filter.status ? statusLabel(filter.status) : "");
  if (!props.appCode) push("appCode", filter.appCode);
  push("packageId", filter.packageId);
  push("routeProfileId", filter.routeProfileId);
  push("toolId", filter.toolId);
  push("releaseId", filter.releaseId);
  push("traceId", filter.traceId);
  push("rangeFrom", filter.range?.[0]);
  push("rangeTo", filter.range?.[1]);
  return chips;
}
function search() {
  page.value = 1;
  applied.value = snapshotChips();
  void load();
}
function onPage(value: number) {
  page.value = value;
  void load();
}
function onSize(value: number) {
  size.value = value;
  search();
}
function removeChip(chip: Chip) {
  if (chip.key === "rangeFrom" || chip.key === "rangeTo") {
    const range = [...filter.range];
    range[chip.key === "rangeFrom" ? 0 : 1] = "";
    filter.range = range;
  } else {
    filter[chip.key] = "";
  }
  search();
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
  <DataPage
    :page="page"
    :page-size="size"
    :total="total"
    @update:page="onPage"
    @update:page-size="onSize"
  >
    <template #header>
      <PageHeader
        :title="title || '运行监控'"
        :subtitle="subtitle"
        :busy="busy"
        @refresh="emit('refresh')"
      />
    </template>
    <template #error>
      <ErrorBanner :error="error" :trace="trace" />
    </template>
    <template #filters>
      <!-- 基础 3 字段常显（方案 §4） -->
      <el-input v-model="filter.q" clearable placeholder="运行编码 / 应用编码" aria-label="运行编码搜索" style="width: 16rem" @keyup.enter="search" />
      <el-select v-model="filter.status" clearable placeholder="状态" aria-label="运行状态" style="width: 10rem">
        <el-option v-for="[value, label] in statuses.slice(1)" :key="value" :value="value" :label="label" />
      </el-select>
      <el-date-picker v-model="filter.range" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" style="width: 16rem" />
      <el-button type="primary" :loading="busy" @click="search">查询</el-button>
      <el-button :disabled="busy" @click="reset">重置筛选</el-button>
      <!-- 高级筛选折叠：按应用、能力来源与关联标识 -->
      <details class="observation-filters">
        <summary>按应用、能力来源与关联标识筛选</summary>
        <div class="advanced-fields">
          <el-input v-if="!props.appCode" v-model="filter.appCode" clearable maxlength="128" placeholder="精确应用编码" aria-label="精确应用编码" @keyup.enter="search" />
          <el-input v-model="filter.packageId" clearable maxlength="128" placeholder="能力包 packageId" aria-label="能力包标识" @keyup.enter="search" />
          <el-input v-model="filter.routeProfileId" clearable maxlength="128" placeholder="路线 routeProfileId" aria-label="路线标识" @keyup.enter="search" />
          <el-input v-model="filter.toolId" clearable maxlength="128" placeholder="工具 toolId" aria-label="工具标识" @keyup.enter="search" />
          <el-input v-model="filter.releaseId" clearable maxlength="128" placeholder="本体发布 releaseId" aria-label="本体发布标识" @keyup.enter="search" />
          <el-input v-model="filter.traceId" clearable maxlength="128" placeholder="关联 traceId" aria-label="关联追踪标识" @keyup.enter="search" />
        </div>
        <p class="muted">包与路线按同一冻结来源匹配；工具同时核对实际调用的精确技能版本。本体发布来自本次运行的上下文包记录。</p>
      </details>
    </template>
    <template #chips>
      <el-tag
        v-for="chip in applied"
        :key="chip.key + ':' + chip.value"
        closable
        @close="removeChip(chip)"
      >{{ chip.label }}：{{ chip.value }}</el-tag>
      <el-button v-if="applied.length" text size="small" @click="reset">清除全部</el-button>
    </template>
    <el-table v-loading="busy" :data="items" height="100%">
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
      <template #empty>
        <EmptyState
          :variant="error ? 'error' : 'filtered'"
          :description="error ? error : '没有匹配的运行记录，请调整筛选条件'"
          :action-text="error ? '刷新重试' : '清除筛选'"
          @action="error ? load() : reset()"
        />
      </template>
    </el-table>
  </DataPage>
</template>

<style scoped>
.observation-filters { flex-basis: 100%; }
.observation-filters summary { cursor: pointer; margin-bottom: var(--sp-2); }
.advanced-fields { display: flex; flex-wrap: wrap; gap: var(--sp-3); }
.advanced-fields > * { min-width: 12rem; }
.observation-detail { padding: var(--sp-3) var(--sp-4); }
@media (max-width: 600px) {
  .advanced-fields > * { min-width: 100%; }
  .observation-detail { padding: var(--sp-2); }
}
</style>
<!-- UI-04：.status 状态类此前无任何样式定义，等待确认与成功渲染无差别；
     这里做全局（非 scoped）语义色映射——RunList/RunDetail/确认中心与各资源
     列表共用 .status 类。WAITING_* 一律悬置黄，绝不与成功绿混同。 -->
<style>
.status.SUCCEEDED,
.status.PUBLISHED { color: var(--st-ok-text, #86efac); }
.status.FAILED,
.status.DISABLED { color: var(--st-fail-text, #fca5a5); }
.status.RUNNING { color: var(--st-run-text, #93c5fd); }
.status.WAITING_APPROVAL,
.status.WAITING_EXTERNAL { color: var(--st-suspend-text, #fcd34d); font-weight: 600; }
</style>
