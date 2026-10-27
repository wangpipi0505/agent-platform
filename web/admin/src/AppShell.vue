<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { useAuth } from "../../shared/api";
import { pageChrome } from "./page-chrome";

const auth = useAuth();
const route = useRoute();
const menus = [
  ["智能体管理", "/apps"],
  ["模型供应商", "/model/providers"],
  ["模型条目", "/model/entries"],
  ["提示词模板", "/model/prompt-templates"],
  ["技能管理", "/skills"],
  ["服务管理", "/services"],
  ["运行中心", "/operations"],
  ["运行监控", "/runs"],
  ["确认中心", "/approvals"],
  ["评测中心", "/evaluations"],
  ["统计分析", "/statistics"],
  ["审计", "/audit"],
  ["系统管理", "/system"],
];
const density = ref(localStorage.getItem("density") || "标准");
const isMock =
  import.meta.env.DEV &&
  typeof (import.meta as any).env?.VITE_MOCK === "string" &&
  (import.meta as any).env.VITE_MOCK !== "false";

const tab = computed(() => {
  const parts = route.params.tabMatch;
  return (Array.isArray(parts) ? parts.join("/") : String(parts || "")) || "overview";
});
const shellClass = computed(() => ({
  "canvas-mode": route.name === "app-workspace" && tab.value === "orchestration/flow",
  "statistics-mode": route.path === "/statistics",
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
    document.documentElement.style.fontSize = (
      { 紧凑: "15px", 标准: "16px", 宽松: "17px" } as Record<string, string>
    )[value];
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
      <template v-for="(menu, i) in menus" :key="menu[1]">
        <small v-if="i === 5">运行与支撑</small>
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
        <span>工作台 / {{ pageChrome.title }}</span>
        <div class="actions">
          <span class="muted">显示密度</span>
          <el-select v-model="density" style="width: 6rem" aria-label="显示密度">
            <el-option v-for="d in ['紧凑', '标准', '宽松']" :key="d" :value="d" />
          </el-select>
          <span class="muted" data-testid="portal-identity">{{ auth.user?.principalId }}</span>
          <el-button text @click="logout">退出</el-button>
        </div>
      </header>
      <div v-if="isMock" class="mock-banner" role="status">
        演示模式：本页数据为本地模拟，写入不会保存。请连接真实后端后操作。
      </div>
      <div class="content">
        <RouterView />
      </div>
    </main>
  </div>
</template>

<style scoped>
.mock-banner {
  padding: 0.65rem 1.5rem;
  background: #fff7e6;
  border-bottom: 1px solid #f5dab1;
  color: #8a5a00;
  font-size: 0.875rem;
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
