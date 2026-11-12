<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useApi } from '../../shared/api'

const api = useApi()
const data = ref<any>()
const error = ref('')
const busy = ref(false)
// AP-S06：fixture 演示数据隔离语义。卡片语义独立于服务健康——
// 有 fixture 且处于演示模式时告警；非演示模式显示“已隔离”；读取失败显示“来源未知”。
// 任何情况下 fixture 卡都不使用绿色“正常”徽标。
const fixtures = ref<any>(null)
// AP-S02：模型深度健康与进程健康分开。深度探测按需/缓存执行，避免刷新页面时压垮上游。
const health = ref<any>(null)
const healthError = ref('')
const healthBusy = ref(false)

const fixtureUnknown = computed(
  () => !!fixtures.value && (!fixtures.value.model || !fixtures.value.skill),
)
const fixtureTotal = computed(
  () => (fixtures.value?.model?.providerCount ?? 0) + (fixtures.value?.skill?.fixtureCount ?? 0),
)
const fixtureWarn = computed(
  () =>
    fixtureTotal.value > 0 &&
    [fixtures.value?.model, fixtures.value?.skill].some((s) => s?.demoMode === true),
)

async function load() {
  busy.value = true
  error.value = ''
  try {
    data.value = await api('/admin/system/status')
    const [model, skill] = await Promise.allSettled([
      api('/admin/model-providers/fixture'),
      api('/admin/skills/fixture'),
    ])
    fixtures.value = {
      model: model.status === 'fulfilled' ? model.value : null,
      skill: skill.status === 'fulfilled' ? skill.value : null,
    }
  } catch (failure: any) {
    error.value = failure?.message || '系统状态读取失败'
  } finally {
    busy.value = false
  }
}
function time(value?: string) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}
// 演示数据不一定带耗时字段，缺省时显示占位符，避免只渲染出裸的 “ms”。
function latencyText(ms?: number) {
  return typeof ms === 'number' && Number.isFinite(ms) ? `${ms} ms` : '—'
}
const healthLabels: Record<string, string> = {
  HEALTHY: '健康',
  DEGRADED: '降级',
  UNHEALTHY: '不可用',
  UNKNOWN: '未探测',
}
function healthLabel(status?: string) {
  return healthLabels[status || 'UNKNOWN'] || status || '未知'
}
const checkLabels: Record<string, string> = {
  PASS: '通过',
  WARN: '告警',
  FAIL: '失败',
  SKIP: '跳过',
}
function checkLabel(status?: string) {
  return checkLabels[status || ''] || status || ''
}
async function loadHealth(refresh = false, force = false) {
  healthBusy.value = true
  healthError.value = ''
  try {
    const query = `?refresh=${refresh}${force ? '&force=true' : ''}`
    health.value = await api(`/admin/model-health${query}`)
  } catch (failure: any) {
    healthError.value = failure?.message || '模型深度健康读取失败'
  } finally {
    healthBusy.value = false
  }
}
onMounted(() => {
  load()
  // 页面挂载只补未探测条目；用户点“重新探测”才强制重新访问全部启用条目。
  loadHealth(true, false)
})
</script>

<template>
  <section class="panel system-panel">
    <div class="row">
      <div>
        <h2>系统状态</h2>
        <p class="muted">只读检查数据库、缓存与各服务；这里不修改任何运行配置。</p>
      </div>
      <el-button :loading="busy" @click="load">重新检查</el-button>
    </div>

    <div v-if="error" class="error" role="alert">{{ error }}</div>
    <template v-else-if="data">
      <div class="overview" :class="{ bad: !data.ok }">
        <strong>{{ data.ok ? '全部就绪' : '存在不可用项' }}</strong>
        <span>版本 {{ data.version }} · 检查于 {{ time(data.checkedAt) }}</span>
      </div>
      <div class="checks">
        <article v-for="check in data.checks" :key="check.key" class="check" :class="{ bad: !check.ok }">
          <div class="check-title">
            <span class="dot" />
            <strong>{{ check.label }}</strong>
            <span class="pill">{{ check.ok ? '正常' : '异常' }}</span>
          </div>
          <p>{{ check.detail }}</p>
          <small class="muted">{{ latencyText(check.latencyMs) }}</small>
        </article>
      </div>
      <!-- AP-S02：进程健康之外，单独展示上游模型的可达、鉴权、对话、工具与计量探测。 -->
      <div class="deep-card" :class="(health?.status || 'unknown').toLowerCase()">
        <div class="check-title">
          <span class="dot deep-dot" />
          <strong>模型深度健康</strong>
          <span class="pill deep-pill">{{ health ? healthLabel(health.status) : '读取中' }}</span>
          <el-button
            class="deep-action"
            size="small"
            :loading="healthBusy"
            @click="loadHealth(true, true)"
          >重新探测</el-button>
        </div>
        <div v-if="healthError" class="deep-error" role="alert">{{ healthError }}</div>
        <template v-else-if="health">
          <p class="deep-summary">
            共 {{ health.counts?.total ?? 0 }} 个启用条目：健康 {{ health.counts?.healthy ?? 0 }}，
            降级 {{ health.counts?.degraded ?? 0 }}，不可用 {{ health.counts?.unhealthy ?? 0 }}，
            未探测 {{ health.counts?.unknown ?? 0 }}。
            <span v-if="health.ttlSeconds != null" class="muted">
              结果缓存 {{ health.ttlSeconds }} 秒；探测有并发与超时上限。
            </span>
            <span v-else class="muted">探测有并发与超时上限。</span>
          </p>
          <div v-if="!health.entries?.length" class="muted">尚未配置模型条目。</div>
          <div v-else class="deep-list">
            <article v-for="entry in health.entries" :key="entry.entryId" class="deep-entry">
              <div class="deep-entry-head">
                <strong>{{ entry.model || `条目 ${entry.entryId}` }}</strong>
                <span class="pill" :class="`entry-${(entry.status || 'UNKNOWN').toLowerCase()}`">
                  {{ healthLabel(entry.status) }}
                </span>
                <small class="muted">
                  条目 {{ entry.entryId }} · {{ entry.latencyMs ?? 0 }} ms
                  <template v-if="entry.cached"> · 缓存 {{ entry.ageSeconds }}s</template>
                </small>
              </div>
              <p v-if="entry.reason" class="deep-reason">{{ entry.reason }}</p>
              <div v-if="entry.checks?.length" class="deep-checks">
                <span
                  v-for="check in entry.checks"
                  :key="check.key"
                  class="deep-check"
                  :class="`check-${(check.status || '').toLowerCase()}`"
                  :title="check.message"
                >{{ check.label }}：{{ checkLabel(check.status) }}</span>
              </div>
            </article>
          </div>
        </template>
        <el-skeleton v-else :rows="2" animated />
      </div>
      <p class="muted hint">
        Redis 不可用时，发布、审批、统计与审计会返回 502；聊天和调试仍可运行。模型/监控为发布依赖，进程存在但依赖未就绪时会标为异常。
      </p>
      <!-- AP-S06：演示数据隔离卡。语义独立于服务健康检查，fixture 永不显示为“健康可用”。 -->
      <div v-if="fixtures" class="fixture-card" :class="{ warn: fixtureWarn }">
        <div class="check-title">
          <span class="dot fixture-dot" />
          <strong>内置能力</strong>
          <span class="pill fixture-pill">{{
            fixtureWarn ? '允许调用' : '已隔离'
          }}</span>
        </div>
        <p v-if="fixtureUnknown">隔离汇总暂不可读，能力状态暂不可读，请刷新重试。</p>
        <p v-else-if="fixtureTotal === 0">未检出内置能力。</p>
        <p v-else>
          检出模型供应商 {{ fixtures.model?.providerCount ?? 0 }} 个（条目
          {{ fixtures.model?.entryCount ?? 0 }}）、技能 {{ fixtures.skill?.fixtureCount ?? 0 }}
          个。{{
            fixtureWarn
              ? '当前允许内置能力参与调用。'
              : '生产/默认模式下已隔离：不参与真实调用、不可新增，可在“模型供应商/技能库”识别并清理。'
          }}
        </p>
      </div>
    </template>
    <el-skeleton v-else :rows="5" animated />
  </section>
</template>

<style scoped>
.system-panel { padding: 1.25rem 1.5rem 1.5rem; }
.row { display: flex; align-items: flex-start; justify-content: space-between; gap: 1rem; }
.overview {
  display: flex; justify-content: space-between; gap: 1rem; margin: 1rem 0 1.25rem;
  padding: .9rem 1rem; border: 1px solid rgba(74, 222, 128, .35); background: rgba(74, 222, 128, .1); border-radius: 8px;
}
.overview.bad { border-color: rgba(248, 113, 113, .4); background: rgba(248, 113, 113, .1); }
.checks { display: grid; grid-template-columns: repeat(auto-fill, minmax(15rem, 1fr)); gap: .85rem; }
.check { border: 1px solid var(--line); border-radius: 8px; padding: .85rem 1rem; min-height: 7.5rem; }
.check.bad { border-color: rgba(248, 113, 113, .4); background: rgba(248, 113, 113, .06); }
.check-title { display: flex; align-items: center; gap: .45rem; }
.dot { width: .55rem; height: .55rem; border-radius: 50%; background: var(--st-ok); flex: none; }
.check.bad .dot { background: var(--st-fail); }
.pill { margin-left: auto; font-size: .75rem; padding: .1rem .45rem; border-radius: 999px; background: rgba(74, 222, 128, .15); color: var(--st-ok-text); }
.check.bad .pill { background: rgba(248, 113, 113, .15); color: var(--st-fail-text); }
.check p { margin: .55rem 0 .35rem; word-break: break-word; }
.hint { margin-top: 1rem; line-height: 1.7; }
</style>
<style scoped>
/* AP-S06：fixture 卡专用中性/警示配色，复用绿色“正常”语义被有意排除。 */
.fixture-card {
  margin-top: 1rem;
  border: 1px solid var(--line);
  background: var(--surface-2);
  border-radius: 8px;
  padding: 0.85rem 1rem;
}
.fixture-card.warn {
  border-color: rgba(251, 191, 36, .4);
  background: rgba(251, 191, 36, .08);
}
.fixture-card p {
  margin: 0.5rem 0 0;
  line-height: 1.6;
}
.fixture-dot {
  background: var(--st-wait);
}
.fixture-pill {
  background: rgba(156, 163, 175, .15);
  color: var(--st-wait-text);
}
.fixture-card.warn .fixture-pill {
  background: rgba(251, 191, 36, .15);
  color: var(--st-suspend-text);
}
/* AP-S02：深度健康卡与进程卡分开着色，UNKNOWN 明确标灰，避免“未探测”被读成正常。 */
.deep-card {
  margin-top: 1rem;
  border: 1px solid var(--line);
  background: var(--surface-2);
  border-radius: 8px;
  padding: 0.85rem 1rem;
}
.deep-card.healthy { border-color: rgba(74, 222, 128, .35); background: rgba(74, 222, 128, .07); }
.deep-card.degraded { border-color: rgba(251, 191, 36, .4); background: rgba(251, 191, 36, .07); }
.deep-card.unhealthy { border-color: rgba(248, 113, 113, .4); background: rgba(248, 113, 113, .07); }
.deep-dot { background: var(--st-wait); }
.deep-card.healthy .deep-dot { background: var(--st-ok); }
.deep-card.degraded .deep-dot { background: var(--st-suspend); }
.deep-card.unhealthy .deep-dot { background: var(--st-fail); }
.deep-pill { background: rgba(156, 163, 175, .15); color: var(--st-wait-text); }
.deep-card.healthy .deep-pill { background: rgba(74, 222, 128, .15); color: var(--st-ok-text); }
.deep-card.degraded .deep-pill { background: rgba(251, 191, 36, .15); color: var(--st-suspend-text); }
.deep-card.unhealthy .deep-pill { background: rgba(248, 113, 113, .15); color: var(--st-fail-text); }
.deep-action { margin-left: 0.35rem; }
.deep-error { margin-top: 0.65rem; color: var(--st-fail-text); }
.deep-summary { margin: 0.65rem 0; line-height: 1.7; }
.deep-list { display: grid; gap: 0.55rem; }
.deep-entry { border: 1px solid var(--line); background: var(--surface); border-radius: 6px; padding: 0.6rem 0.75rem; }
.deep-entry-head { display: flex; align-items: center; gap: 0.45rem; flex-wrap: wrap; }
.deep-entry-head .pill { margin-left: 0; }
.deep-entry-head small { margin-left: auto; }
.entry-healthy { background: rgba(74, 222, 128, .15); color: var(--st-ok-text); }
.entry-degraded { background: rgba(251, 191, 36, .15); color: var(--st-suspend-text); }
.entry-unhealthy { background: rgba(248, 113, 113, .15); color: var(--st-fail-text); }
.entry-unknown { background: rgba(156, 163, 175, .15); color: var(--st-wait-text); }
.deep-reason { margin: 0.45rem 0 0; color: var(--muted); line-height: 1.55; }
.deep-checks { display: flex; flex-wrap: wrap; gap: 0.35rem; margin-top: 0.45rem; }
.deep-check { font-size: 0.75rem; border: 1px solid var(--line); border-radius: 999px; padding: 0.08rem 0.45rem; color: var(--muted); }
.deep-check.check-pass { border-color: rgba(74, 222, 128, .35); color: var(--st-ok-text); }
.deep-check.check-warn { border-color: rgba(251, 191, 36, .4); color: var(--st-suspend-text); }
.deep-check.check-fail { border-color: rgba(248, 113, 113, .4); color: var(--st-fail-text); }
</style>
