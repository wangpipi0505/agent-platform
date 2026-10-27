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
          <small class="muted">{{ check.latencyMs }} ms</small>
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
            <span class="muted">结果缓存 {{ health.ttlSeconds }} 秒；探测有并发与超时上限。</span>
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
          <strong>演示数据（fixture）</strong>
          <span class="pill fixture-pill">{{
            fixtureWarn ? '演示模式放行' : '已隔离'
          }}</span>
        </div>
        <p v-if="fixtureUnknown">隔离汇总暂不可读，演示数据来源未知；请检查 model/skill 服务。</p>
        <p v-else-if="fixtureTotal === 0">未检出演示 fixture 数据。</p>
        <p v-else>
          检出模型供应商 {{ fixtures.model?.providerCount ?? 0 }} 个（条目
          {{ fixtures.model?.entryCount ?? 0 }}）、技能 {{ fixtures.skill?.fixtureCount ?? 0 }}
          个。{{
            fixtureWarn
              ? '当前为演示模式，fixture 可参与调用，仅限本地联调。'
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
  padding: .9rem 1rem; border: 1px solid #b7ebc6; background: #f0f9eb; border-radius: 8px;
}
.overview.bad { border-color: #f3c2c2; background: #fef0f0; }
.checks { display: grid; grid-template-columns: repeat(auto-fill, minmax(15rem, 1fr)); gap: .85rem; }
.check { border: 1px solid #e4e7ed; border-radius: 8px; padding: .85rem 1rem; min-height: 7.5rem; }
.check.bad { border-color: #f0b9b9; background: #fffafa; }
.check-title { display: flex; align-items: center; gap: .45rem; }
.dot { width: .55rem; height: .55rem; border-radius: 50%; background: #67c23a; flex: none; }
.check.bad .dot { background: #f56c6c; }
.pill { margin-left: auto; font-size: .75rem; padding: .1rem .45rem; border-radius: 999px; background: #f0f9eb; color: #3f8f2c; }
.check.bad .pill { background: #fef0f0; color: #c45656; }
.check p { margin: .55rem 0 .35rem; word-break: break-word; }
.hint { margin-top: 1rem; line-height: 1.7; }
</style>
<style scoped>
/* AP-S06：fixture 卡专用中性/警示配色，复用绿色“正常”语义被有意排除。 */
.fixture-card {
  margin-top: 1rem;
  border: 1px solid #d4d7de;
  background: #f7f8fa;
  border-radius: 8px;
  padding: 0.85rem 1rem;
}
.fixture-card.warn {
  border-color: #f3d19e;
  background: #fdf6ec;
}
.fixture-card p {
  margin: 0.5rem 0 0;
  line-height: 1.6;
}
.fixture-dot {
  background: #909399;
}
.fixture-pill {
  background: #f4f4f5;
  color: #606266;
}
.fixture-card.warn .fixture-pill {
  background: #faecd8;
  color: #b88230;
}
/* AP-S02：深度健康卡与进程卡分开着色，UNKNOWN 明确标灰，避免“未探测”被读成正常。 */
.deep-card {
  margin-top: 1rem;
  border: 1px solid #d4d7de;
  background: #f7f8fa;
  border-radius: 8px;
  padding: 0.85rem 1rem;
}
.deep-card.healthy { border-color: #b7ebc6; background: #f7fcf5; }
.deep-card.degraded { border-color: #f3d19e; background: #fdf8ef; }
.deep-card.unhealthy { border-color: #f3c2c2; background: #fff8f8; }
.deep-dot { background: #909399; }
.deep-card.healthy .deep-dot { background: #67c23a; }
.deep-card.degraded .deep-dot { background: #e6a23c; }
.deep-card.unhealthy .deep-dot { background: #f56c6c; }
.deep-pill { background: #f4f4f5; color: #606266; }
.deep-card.healthy .deep-pill { background: #f0f9eb; color: #3f8f2c; }
.deep-card.degraded .deep-pill { background: #fdf6ec; color: #b88230; }
.deep-card.unhealthy .deep-pill { background: #fef0f0; color: #c45656; }
.deep-action { margin-left: 0.35rem; }
.deep-error { margin-top: 0.65rem; color: #c45656; }
.deep-summary { margin: 0.65rem 0; line-height: 1.7; }
.deep-list { display: grid; gap: 0.55rem; }
.deep-entry { border: 1px solid #e4e7ed; background: #fff; border-radius: 6px; padding: 0.6rem 0.75rem; }
.deep-entry-head { display: flex; align-items: center; gap: 0.45rem; flex-wrap: wrap; }
.deep-entry-head .pill { margin-left: 0; }
.deep-entry-head small { margin-left: auto; }
.entry-healthy { background: #f0f9eb; color: #3f8f2c; }
.entry-degraded { background: #fdf6ec; color: #b88230; }
.entry-unhealthy { background: #fef0f0; color: #c45656; }
.entry-unknown { background: #f4f4f5; color: #606266; }
.deep-reason { margin: 0.45rem 0 0; color: #606266; line-height: 1.55; }
.deep-checks { display: flex; flex-wrap: wrap; gap: 0.35rem; margin-top: 0.45rem; }
.deep-check { font-size: 0.75rem; border: 1px solid #e4e7ed; border-radius: 999px; padding: 0.08rem 0.45rem; color: #606266; }
.deep-check.check-pass { border-color: #c8e6b8; color: #3f8f2c; }
.deep-check.check-warn { border-color: #f3d19e; color: #b88230; }
.deep-check.check-fail { border-color: #f3c2c2; color: #c45656; }
</style>
