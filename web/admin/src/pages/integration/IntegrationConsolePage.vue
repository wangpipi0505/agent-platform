<script setup lang="ts">
// F3-4 集成控制台（前端稿 §5.7）：原 7 条集成路由并成一页内左导航（六面板）。
// ① 智能体上下文选择器持久在本页页头——appCode 经路由 query（?app=）持久，
//    选一次六个面板共享，刷新/深链不丢失，消灭逐面板重选；
// ② 六面板按左导航懒挂载（v-if 首次激活才挂载），复用既有 Panel 组件
//    （embedded + appCode props 传入），面板自带 busy/error 局部呈现；
// ③ 语义绑定（Semantic*）为挂起线（主方案 §2.1），不并入本控制台。
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import PageError from "../../PageError.vue";
import PageHeader from "../../PageHeader.vue";
import { usePageOperation, usePageTitle } from "../../usePageOperation";
import { integrationClient } from "../../../../shared/api";
import { parseApplications, type ApplicationOption } from "./integration-console";
import ConfigReleasesPanel from "./ConfigReleasesPanel.vue";
import ConnectionProfilesPage from "./ConnectionProfilesPage.vue";
import EnvironmentBindingsPage from "./EnvironmentBindingsPage.vue";
import IntegrationDiagnosticsPanel from "./IntegrationDiagnosticsPanel.vue";
import IntegrationProfilesPanel from "./IntegrationProfilesPanel.vue";
import ExperienceEditor from "./ExperienceEditor.vue";

usePageTitle(() => "集成控制台");
const { api, busy, error, trace, operation } = usePageOperation();
const client = integrationClient(api);
const route = useRoute();
const router = useRouter();

/** 面板清单：key 即路由 query.panel 的取值，也是旧深链 redirect 的锚点。 */
const PANELS = [
  { key: "releases", label: "配置发布", description: "草稿→校验→批准→CAS 激活" },
  { key: "connections", label: "连接档案", description: "协议、端点策略与认证引用" },
  { key: "bindings", label: "环境绑定", description: "连接档案钉到环境与物理端点" },
  { key: "profiles", label: "接入档案", description: "业务系统、等级与体验引用" },
  { key: "experience", label: "体验编辑", description: "欢迎语、能力说明与呈现" },
  { key: "diagnostics", label: "诊断", description: "按环境只读投影逐阶段状态" },
] as const;
type PanelKey = (typeof PANELS)[number]["key"];

const active = computed<PanelKey>(() => {
  const requested = String(route.query.panel ?? "");
  return (PANELS.some((panel) => panel.key === requested) ? requested : "releases") as PanelKey;
});

/** 智能体上下文：query ?app= 是唯一事实源（持久+可深链）；面板经 props 感知。 */
const applications = ref<ApplicationOption[]>([]);
const appCode = computed(() => (typeof route.query.app === "string" ? route.query.app : ""));

function selectPanel(key: PanelKey) {
  void router.replace({ query: { ...route.query, panel: key } });
}
function chooseApp(code: string) {
  void router.replace({ query: { ...route.query, app: code } });
}

/** 页头刷新：重挂当前面板（key 变化触发重新拉取），面板自身状态随之复位。 */
const epoch = ref(0);

onMounted(async () => {
  await operation(async () => {
    applications.value = parseApplications(await client.applications());
    const current = appCode.value;
    if ((!current || !applications.value.some((item) => item.code === current)) && applications.value.length) {
      void router.replace({ query: { ...route.query, app: applications.value[0].code } });
    }
  });
});
</script>

<template>
  <div class="console">
    <PageHeader
      title="集成控制台"
      subtitle="配置发布、连接档案、环境绑定、接入档案、体验编辑与诊断共用一个智能体上下文；切换左导航不再逐页重选。"
      :busy="busy"
      @refresh="epoch++"
    />
    <PageError :error="error" :trace="trace" />

    <section class="console-context" aria-label="智能体上下文">
      <span class="ctx-label">智能体上下文</span>
      <el-select
        class="ctx-select"
        :model-value="appCode"
        placeholder="选择智能体（六个面板共享）"
        :disabled="busy"
        aria-label="选择智能体"
        @update:model-value="chooseApp"
      >
        <el-option
          v-for="application in applications"
          :key="application.code"
          :label="`${application.name}（${application.code}）`"
          :value="application.code"
        />
      </el-select>
      <span class="ctx-hint">选一次全面板共享；上下文随地址持久，刷新与深链不丢失。</span>
    </section>

    <div class="console-body">
      <nav class="console-nav" aria-label="集成控制台导航">
        <button
          v-for="panel in PANELS"
          :key="panel.key"
          type="button"
          class="nav-item"
          :class="{ active: active === panel.key }"
          :aria-current="active === panel.key ? 'true' : undefined"
          @click="selectPanel(panel.key)"
        >
          <span class="nav-label">{{ panel.label }}</span>
          <span class="nav-desc">{{ panel.description }}</span>
        </button>
      </nav>

      <div class="console-panel">
        <!-- 懒挂载：面板首次激活才挂载；epoch 供页头刷新重挂当前面板。 -->
        <ConfigReleasesPanel v-if="active === 'releases'" :key="`releases-${epoch}`" embedded :app-code="appCode" />
        <ConnectionProfilesPage v-else-if="active === 'connections'" :key="`connections-${epoch}`" embedded />
        <EnvironmentBindingsPage v-else-if="active === 'bindings'" :key="`bindings-${epoch}`" embedded />
        <IntegrationProfilesPanel v-else-if="active === 'profiles'" :key="`profiles-${epoch}`" embedded :app-code="appCode" />
        <ExperienceEditor v-else-if="active === 'experience'" :key="`experience-${epoch}`" embedded :app-code="appCode" />
        <IntegrationDiagnosticsPanel v-else-if="active === 'diagnostics'" :key="`diagnostics-${epoch}`" embedded :app-code="appCode" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.console-context {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--sp-3);
  padding: var(--sp-3) var(--sp-4);
  margin-bottom: var(--sp-4);
  background: var(--surface-2);
  border: 1px solid var(--line);
  border-radius: 8px;
}
.ctx-label { font-size: var(--text-meta); color: var(--muted); }
.ctx-select { width: 280px; }
.ctx-hint { font-size: var(--text-meta); color: var(--muted); }
.console-body {
  display: grid;
  grid-template-columns: 224px minmax(0, 1fr);
  gap: var(--sp-4);
  align-items: start;
}
.console-nav { display: flex; flex-direction: column; gap: var(--sp-2); }
.nav-item {
  display: flex;
  flex-direction: column;
  gap: var(--sp-1);
  padding: var(--sp-2) var(--sp-3);
  text-align: left;
  font: inherit;
  color: var(--ink);
  background: var(--surface-2);
  border: 1px solid var(--line);
  border-radius: 8px;
  cursor: pointer;
}
.nav-item:hover { border-color: var(--brand-primary); }
.nav-item.active {
  background: var(--nav-active-bg);
  border-color: var(--brand-primary);
  color: var(--nav-text-active);
}
.nav-label { font-size: var(--text-body); }
.nav-desc { font-size: var(--text-meta); color: var(--muted); }
@media (max-width: 900px) {
  .console-body { grid-template-columns: minmax(0, 1fr); }
  .console-nav { flex-direction: row; flex-wrap: wrap; }
}
</style>
