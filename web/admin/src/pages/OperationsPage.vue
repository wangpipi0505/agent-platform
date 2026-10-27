<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { RouterLink } from "vue-router";
import { useApi, useAuth } from "../../../shared/api";
import PageHeader from "../PageHeader.vue";
import { usePageTitle } from "../usePageOperation";
import {
  alertStatusLabel,
  checkType,
  count,
  millis,
  percent,
  severityView,
  timeLabel,
  windowLabel,
} from "./operations-admin";

usePageTitle(() => "运行中心");

const api = useApi();
const auth = useAuth();
const windowMinutes = ref(1440);
const overview = ref<any>(null);
const alerts = ref<any[]>([]);
const error = ref("");
const trace = ref("");
const busy = ref(false);
const evaluating = ref(false);
const acknowledging = ref("");
const purgeConfirm = ref("");
const purgeResult = ref("");
const purging = ref(false);

const runs = computed(() => overview.value?.runs || {});
const checks = computed<any[]>(() => overview.value?.checks || []);
const openAlerts = computed(() => overview.value?.openAlerts ?? 0);
const failureRate = computed(() => runs.value.runs?.failureRate);

async function load() {
  if (!auth.can("admin") || busy.value) return;
  busy.value = true;
  error.value = "";
  trace.value = "";
  try {
    const [snapshot, rows] = await Promise.all([
      api(`/admin/operations/overview?windowMinutes=${windowMinutes.value}`),
      api("/admin/operations/alerts"),
    ]);
    overview.value = snapshot;
    alerts.value = Array.isArray(rows) ? rows : [];
  } catch (e: any) {
    error.value = e.message || "运行中心数据读取失败";
    trace.value = e.traceId || "";
  } finally {
    busy.value = false;
  }
}

async function evaluate() {
  if (evaluating.value || busy.value || !auth.can("admin")) return;
  evaluating.value = true;
  error.value = "";
  try {
    const rows = await api(
      `/admin/operations/alerts/evaluate?windowMinutes=${windowMinutes.value}`,
      "POST",
    );
    alerts.value = Array.isArray(rows) ? rows : [];
    await load();
  } catch (e: any) {
    error.value = e.message || "告警评估失败";
    trace.value = e.traceId || "";
  } finally {
    evaluating.value = false;
  }
}

async function acknowledge(id: string) {
  if (acknowledging.value || !auth.can("admin")) return;
  acknowledging.value = id;
  error.value = "";
  try {
    await api(`/admin/operations/alerts/${encodeURIComponent(id)}/ack`, "POST");
    alerts.value = await api("/admin/operations/alerts");
    await load();
  } catch (e: any) {
    error.value = e.message || "确认告警失败";
    trace.value = e.traceId || "";
  } finally {
    acknowledging.value = "";
  }
}

async function purge() {
  if (purging.value || purgeConfirm.value !== "PURGE") return;
  purging.value = true;
  error.value = "";
  purgeResult.value = "";
  try {
    const result = await api(
      `/admin/operations/retention/purge?confirm=${encodeURIComponent(purgeConfirm.value)}`,
      "POST",
    );
    purgeResult.value = `已清理 ${count(result?.snapshotsDeleted)} 份统计快照、${count(
      result?.alertsDeleted,
    )} 条历史告警。`;
    purgeConfirm.value = "";
    await load();
  } catch (e: any) {
    error.value = e.message || "清理失败";
    trace.value = e.traceId || "";
  } finally {
    purging.value = false;
  }
}

onMounted(load);
</script>

<template>
  <PageHeader
    title="运行中心"
    subtitle="指标读取运行权威库；服务健康是实时探测；告警只在管理员评估后落库。"
    :busy="busy"
    @refresh="load"
  >
    <template #actions>
      <el-button type="primary" :loading="evaluating" :disabled="busy" @click="evaluate">
        评估告警
      </el-button>
    </template>
  </PageHeader>

  <el-result
    v-if="!auth.can('admin')"
    icon="warning"
    title="需要管理员权限"
    sub-title="请使用具有 ADMIN 角色的账号查看运行中心。"
  />

  <template v-else>
    <div class="ops-toolbar panel">
      <div>
        <strong>统计窗口</strong>
        <span class="muted">运行按开始时间、模型与工具按调用时间纳入。</span>
      </div>
      <el-select v-model="windowMinutes" style="width: 9rem" aria-label="统计窗口" @change="load">
        <el-option label="最近 15 分钟" :value="15" />
        <el-option label="最近 1 小时" :value="60" />
        <el-option label="最近 6 小时" :value="360" />
        <el-option label="最近 24 小时" :value="1440" />
      </el-select>
    </div>

    <div v-if="error" class="error" role="alert">
      {{ error }}
      <p v-if="trace">追踪号：{{ trace }}（服务日志按此号关联）</p>
      <p>数据未加载时页面不会显示零值或虚构健康状态。</p>
    </div>

    <el-skeleton v-if="busy && !overview" class="panel" :rows="8" animated />

    <template v-else-if="overview">
      <div class="panel ops-summary">
        <div>
          <span class="muted">检查时间</span>
          <strong>{{ timeLabel(overview.checkedAt) }}</strong>
        </div>
        <div>
          <span class="muted">窗口</span>
          <strong>{{ windowLabel(overview.windowMinutes) }}</strong>
        </div>
        <div>
          <span class="muted">待处理告警</span>
          <strong :class="{ danger: openAlerts > 0 }">{{ openAlerts }}</strong>
        </div>
        <div>
          <span class="muted">请求追踪号</span>
          <strong class="mono">{{ overview.traceId || "—" }}</strong>
        </div>
      </div>

      <div class="panel">
        <div class="row">
          <h2>服务健康</h2>
          <span class="muted">实时 GET /actuator/health，4 秒并发上限。</span>
        </div>
        <div class="health-grid">
          <div v-for="check in checks" :key="check.key" class="health-card">
            <div class="row">
              <strong>{{ check.label }}</strong>
              <el-tag :type="checkType(check.ok)" size="small">
                {{ check.ok ? "正常" : "异常" }}
              </el-tag>
            </div>
            <p>{{ check.detail }}</p>
            <span class="muted">{{ millis(check.latencyMs) }}</span>
          </div>
        </div>
        <p v-if="overview.sourceError" class="error" role="alert">
          {{ overview.sourceError }}：运行指标不可用，不能据此判断“没有运行”。
        </p>
      </div>

      <div class="panel">
        <div class="row">
          <h2>运行概况</h2>
          <span class="muted" v-if="runs.from">
            窗口 {{ timeLabel(runs.from) }} → {{ timeLabel(runs.to) }}
          </span>
        </div>
        <div class="metric-grid">
          <div class="metric"><span>完成运行</span><strong>{{ count(runs.runs?.completed) }}</strong></div>
          <div class="metric"><span>成功</span><strong>{{ count(runs.runs?.succeeded) }}</strong></div>
          <div class="metric"><span>失败</span><strong class="danger">{{ count(runs.runs?.failed) }}</strong></div>
          <div class="metric"><span>失败率</span><strong>{{ percent(failureRate) }}</strong></div>
          <div class="metric"><span>当前活跃</span><strong>{{ count(runs.active?.active) }}</strong></div>
          <div class="metric"><span>平均耗时</span><strong>{{ millis(runs.runs?.averageDurationMs) }}</strong></div>
          <div class="metric"><span>P95 耗时</span><strong>{{ millis(runs.runs?.p95DurationMs) }}</strong></div>
        </div>
        <p class="muted">失败率只统计已完成（成功/失败/取消）运行；未结束运行单列。</p>
      </div>

      <div class="panel grid-panels">
        <div>
          <h2>模型调用</h2>
          <div class="metric-grid narrow">
            <div class="metric"><span>调用</span><strong>{{ count(runs.model?.calls) }}</strong></div>
            <div class="metric"><span>失败</span><strong class="danger">{{ count(runs.model?.failed) }}</strong></div>
          </div>
        </div>
        <div>
          <h2>工具调用</h2>
          <div class="metric-grid narrow">
            <div class="metric"><span>调用</span><strong>{{ count(runs.tools?.calls) }}</strong></div>
            <div class="metric"><span>失败</span><strong class="danger">{{ count(runs.tools?.failed) }}</strong></div>
            <div class="metric"><span>未知</span><strong>{{ count(runs.tools?.unknown) }}</strong></div>
          </div>
        </div>
      </div>

      <div class="panel">
        <h2>失败归因 Top 5</h2>
        <el-table :data="runs.topErrors || []" empty-text="窗口内没有失败运行">
          <el-table-column prop="semantic" label="语义" min-width="220" />
          <el-table-column prop="count" label="次数" width="100" />
        </el-table>
      </div>

      <div class="panel">
        <h2>最近失败运行</h2>
        <el-table :data="runs.recentFailures || []" empty-text="窗口内没有失败运行">
          <el-table-column label="运行" min-width="220">
            <template #default="{ row }">
              <RouterLink :to="`/runs/${row.runId}`">{{ row.runId }}</RouterLink>
            </template>
          </el-table-column>
          <el-table-column prop="appCode" label="应用" min-width="140" />
          <el-table-column prop="semantic" label="语义" min-width="180" />
          <el-table-column prop="message" label="消息" min-width="260" show-overflow-tooltip />
          <el-table-column label="结束时间" min-width="190">
            <template #default="{ row }">{{ timeLabel(row.endedAt) }}</template>
          </el-table-column>
        </el-table>
      </div>

      <div class="panel">
        <div class="row">
          <h2>告警</h2>
          <span class="muted">
            只在点击“评估告警”时写入；恢复后自动置为已恢复。告警列表是历史记录，不受上方统计窗口过滤。
          </span>
        </div>
        <el-table :data="alerts" empty-text="尚无告警记录">
          <el-table-column label="级别" width="90">
            <template #default="{ row }">
              <el-tag :type="severityView(row.severity).type" size="small">
                {{ severityView(row.severity).label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="title" label="告警" min-width="220" />
          <el-table-column prop="detail" label="说明" min-width="300" show-overflow-tooltip />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">{{ alertStatusLabel(row.status) }}</template>
          </el-table-column>
          <el-table-column label="最近发现" min-width="190">
            <template #default="{ row }">{{ timeLabel(row.lastSeenAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="100">
            <template #default="{ row }">
              <el-button
                v-if="row.status === 'OPEN'"
                link
                type="primary"
                :loading="acknowledging === row.id"
                @click="acknowledge(row.id)"
              >
                确认
              </el-button>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div class="panel">
        <h2>保留与清理</h2>
        <p class="muted">
          统计快照保留 {{ overview.retention?.snapshotDays }} 天，已恢复告警保留
          {{ overview.retention?.alertDays }} 天；到期清理会真实删除数据库行。
        </p>
        <div class="purge-row">
          <el-input
            v-model="purgeConfirm"
            placeholder="输入 PURGE 确认"
            aria-label="输入 PURGE 确认清理"
            style="max-width: 16rem"
          />
          <el-button
            type="danger"
            plain
            :loading="purging"
            :disabled="purgeConfirm !== 'PURGE'"
            @click="purge"
          >
            清理过期数据
          </el-button>
        </div>
        <p v-if="purgeResult" class="success">{{ purgeResult }}</p>
        <p class="muted">
          自动清理每小时运行一次。页面不会把外部 Prometheus/Grafana 冒充为产品能力；指标口径和来源时刻均显示在上方。
        </p>
      </div>
    </template>
  </template>
</template>

<style scoped>
.ops-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  flex-wrap: wrap;
}
.ops-toolbar .muted {
  margin-left: 0.6rem;
}
.ops-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 1rem;
}
.ops-summary > div {
  display: flex;
  flex-direction: column;
  gap: 0.3rem;
  min-width: 0;
}
.ops-summary strong {
  overflow-wrap: anywhere;
}
.health-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(13rem, 1fr));
  gap: 0.8rem;
}
.health-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--el-border-radius-base);
  padding: 0.8rem;
}
.health-card p {
  margin: 0.5rem 0;
  overflow-wrap: anywhere;
}
.metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(8rem, 1fr));
  gap: 0.8rem;
}
.metric-grid.narrow {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}
.metric {
  background: var(--el-fill-color-lighter);
  border-radius: var(--el-border-radius-base);
  padding: 0.8rem;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}
.metric span {
  color: var(--el-text-color-secondary);
  font-size: 0.85rem;
}
.metric strong {
  font-size: 1.25rem;
}
.grid-panels {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1.5rem;
}
.danger {
  color: var(--el-color-danger);
}
.success {
  color: var(--el-color-success);
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.85rem;
}
.purge-row {
  display: flex;
  gap: 0.75rem;
  flex-wrap: wrap;
}
@media (max-width: 800px) {
  .ops-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .grid-panels {
    grid-template-columns: 1fr;
  }
}
</style>
