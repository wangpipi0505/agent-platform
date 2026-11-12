<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { useAuth } from "../../shared/api";
import { PAGE_ACTIONS_TARGET, pageChrome } from "./page-chrome";
import { navMenus } from "./router";
import { canReadCapabilityPackages } from "../../shared/capability-package";

const auth = useAuth();
const route = useRoute();
// MNT-02子步1：菜单从 page-access 单一来源派生（标题/路径/分组与路由 meta 同表）。
const menus = navMenus.map((entry) => [entry.label, entry.path]);
const density = ref(localStorage.getItem("density") || "标准");
const pageActionsTarget = PAGE_ACTIONS_TARGET;

const tab = computed(() => {
  const parts = route.params.tabMatch;
  return (Array.isArray(parts) ? parts.join("/") : String(parts || "")) || "overview";
});
const shellClass = computed(() => ({
  "statistics-mode": route.path === "/statistics",
  "capability-mode": route.name === "capability-packages",
  "test-input-mode":
    route.name === "app-workspace" &&
    ["orchestration/debug", "orchestration/publish"].includes(tab.value),
  "skill-detail-mode": route.path.startsWith("/skills/"),
}));
const isActive = (menu: string) =>
  route.path === menu ||
  route.path.startsWith(menu === "/apps" ? "/apps/" : menu + "/");

watch(
  density,
  (value) => {
    localStorage.setItem("density", value);
    // UI-01：密度改为显式令牌（html[data-density]），不再缩放 html 根字号；
    // 键与档位名称不变，旧偏好迁移后仍选中原档位。
    document.documentElement.dataset.density = value;
  },
  { immediate: true },
);

function logout() {
  auth.invalidate(undefined, "signed-out", "已退出，请重新连接");
}
</script>

<template>
  <div class="shell" :class="shellClass">
    <aside class="nav">
      <div class="logo">◈ 智能体平台</div>
      <small>构建与管理</small>
      <template v-for="menu in menus.filter((item) => item[1] !== '/capability-packages' || canReadCapabilityPackages(auth.roles))" :key="menu[1]">
        <small v-if="menu[1] === '/services'">运行与支撑</small>
        <a
          :href="'#' + menu[1]"
          :data-short="menu[0].slice(0, 1)"
          :aria-label="menu[0]"
          :title="menu[0]"
          :class="{ active: isActive(menu[1]) }"
          >{{ menu[0] }}</a
        >
      </template>
      <small style="margin-top: auto">独立环境 · v1.2</small>
    </aside>
    <main class="main">
      <header class="topbar">
        <!-- UI-02：顶栏左侧为当前模块上下文；中间挂载点承接本页 PageHeader 的
             Teleport 操作（随路由卸载自动清理），右侧保留密度/身份/退出。 -->
        <div class="context">
          <span class="muted">{{ pageChrome.eyebrow }}</span>
          <strong>{{ pageChrome.title }}</strong>
        </div>
        <div :id="pageActionsTarget" class="page-actions-slot"></div>
        <div class="actions">
          <span class="muted">显示密度</span>
          <el-select v-model="density" style="width: 6rem" aria-label="显示密度">
            <el-option v-for="d in ['紧凑', '标准', '宽松']" :key="d" :value="d" />
          </el-select>
          <span class="muted" data-testid="portal-identity">{{ auth.user?.principalId }}</span>
          <el-button text @click="logout">退出</el-button>
        </div>
      </header>
      <div class="content">
        <RouterView />
      </div>
    </main>
  </div>
</template>

<style scoped>
.context {
  display: flex;
  align-items: baseline;
  gap: 0.6rem;
  min-width: 0;
}
.context strong {
  font-size: var(--text-title);
  font-weight: 650;
}
.page-actions-slot {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-left: auto;
}
@media (max-width: 900px) {
  .capability-mode { min-width: 0; }
  .capability-mode .topbar { height: auto; min-height: 3.5rem; flex-wrap: wrap; gap: .5rem; padding: .75rem 1rem; }
  .capability-mode .topbar .actions { flex-wrap: wrap; }
}
@media (max-width: 900px) {
  .statistics-mode {
    min-width: 0;
  }
  .statistics-mode .topbar {
    height: auto;
    min-height: 3.5rem;
    flex-wrap: wrap;
    gap: 0.5rem;
    padding: 0.75rem 1rem;
  }
  .statistics-mode .topbar .actions {
    flex-wrap: wrap;
  }
}
@media (max-width: 900px) {
  .test-input-mode {
    min-width: 0;
  }
  .test-input-mode .topbar {
    height: auto;
    min-height: 3.5rem;
    flex-wrap: wrap;
    gap: 0.5rem;
    padding: 0.75rem 1rem;
  }
  .test-input-mode .topbar .actions {
    flex-wrap: wrap;
  }
}
@media (max-width: 900px) {
  .skill-detail-mode {
    min-width: 0;
  }
  .skill-detail-mode .topbar {
    height: auto;
    min-height: 3.5rem;
    flex-wrap: wrap;
    gap: 0.5rem;
    padding: 0.75rem 1rem;
  }
  .skill-detail-mode .topbar .actions {
    flex-wrap: wrap;
  }
}
</style>
