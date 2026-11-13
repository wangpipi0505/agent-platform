<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { useAuth } from "../../shared/api";
import { PAGE_ACTIONS_TARGET, pageChrome } from "./page-chrome";
import { navMenus } from "./router";
import { MENU_GROUP_ORDER, type MenuGroup } from "./page-access";
import { canReadCapabilityPackages } from "../../shared/capability-package";
import {
  fetchMockConfirmationCounts,
  type MockConfirmationCounts,
} from "../../shared/mock-confirmation-counts";
import CommandPalette from "./CommandPalette.vue";

const auth = useAuth();
const route = useRoute();
// MNT-02子步1：菜单从 page-access 单一来源派生（标题/路径/分组与路由 meta 同表）。
// F1-2：按五分组（§3.1）渲染，分组常显、可折叠（折叠后 tooltip 显示分组名）。
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

// —— 五分组菜单 ——
// capability 目录可见性沿用既有角色判断（canReadCapabilityPackages）。
const visibleMenus = computed(() =>
  navMenus.filter(
    (item) => item.key !== "capability-packages" || canReadCapabilityPackages(auth.roles),
  ),
);
const groups = computed(() =>
  MENU_GROUP_ORDER.map((group) => ({
    group,
    items: visibleMenus.value.filter((item) => item.area === group),
  })).filter((section) => section.items.length > 0),
);

/** 分组图标（内联 SVG，不引入新依赖；stroke 走 currentColor 继承侧栏文字色）。 */
const groupIcons: Record<MenuGroup, string> = {
  智能体:
    '<rect x="5" y="5" width="14" height="14" rx="2"/><rect x="9.5" y="9.5" width="5" height="5" rx="1"/><path d="M9 2v3M15 2v3M9 19v3M15 19v3M2 9h3M2 15h3M19 9h3M19 15h3"/>',
  任务与运行: '<path d="M3 12h4l3-7 4 14 3-7h4"/>',
  构建资产: '<path d="M12 3l9 5-9 5-9-5 9-5z"/><path d="M21 13.5l-9 5-9-5"/>',
  集成与治理: '<path d="M12 3l7 3v5c0 4.4-2.9 7.7-7 9-4.1-1.3-7-4.6-7-9V6l7-3z"/>',
  平台管理:
    '<path d="M4 7h16M4 12h16M4 17h16"/><circle cx="9" cy="7" r="2"/><circle cx="15" cy="12" r="2"/><circle cx="7" cy="17" r="2"/>',
};

const COLLAPSE_KEY = "nav-collapsed-groups";
function readCollapsedGroups(): string[] {
  try {
    const raw = localStorage.getItem(COLLAPSE_KEY);
    return raw ? (JSON.parse(raw) as string[]) : [];
  } catch {
    return [];
  }
}
const collapsedGroups = ref<string[]>(readCollapsedGroups());
const isCollapsed = (group: MenuGroup) => collapsedGroups.value.includes(group);
function toggleGroup(group: MenuGroup) {
  const next = new Set(collapsedGroups.value);
  if (next.has(group)) next.delete(group);
  else next.add(group);
  collapsedGroups.value = [...next];
  localStorage.setItem(COLLAPSE_KEY, JSON.stringify(collapsedGroups.value));
}

// —— 命令面板：Ctrl+K / ⌘K 唤起（过滤与跳转在 CommandPalette 内） ——
const paletteOpen = ref(false);
function onGlobalKey(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
    event.preventDefault();
    paletteOpen.value = !paletteOpen.value;
  }
}

// —— 待办角标：15s 轮询 mock 数据源（接口稿 GET /admin/confirmation-center/counts 形状；
// B4-1 真实端点就绪后切换客户端），角标渲染在确认中心菜单项上显示 total。 ——
const counts = ref<MockConfirmationCounts | null>(null);
let pollTimer: number | undefined;
function loadCounts() {
  fetchMockConfirmationCounts()
    .then((data) => {
      counts.value = data;
    })
    .catch(() => {});
}

onMounted(() => {
  loadCounts();
  pollTimer = window.setInterval(loadCounts, 15_000);
  window.addEventListener("keydown", onGlobalKey);
});
onUnmounted(() => {
  if (pollTimer !== undefined) window.clearInterval(pollTimer);
  window.removeEventListener("keydown", onGlobalKey);
});

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
      <section v-for="section in groups" :key="section.group" class="nav-group">
        <button
          type="button"
          class="nav-group-head"
          :title="
            isCollapsed(section.group)
              ? `${section.group}（已折叠，${section.items.length} 项）`
              : section.group
          "
          :aria-expanded="!isCollapsed(section.group)"
          @click="toggleGroup(section.group)"
        >
          <svg
            class="gicon"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="1.7"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
            v-html="groupIcons[section.group]"
          ></svg>
          <span class="glabel">{{ section.group }}</span>
          <span class="gchev" aria-hidden="true">▾</span>
        </button>
        <div v-show="!isCollapsed(section.group)" class="nav-group-items">
          <a
            v-for="item in section.items"
            :key="item.key"
            :href="'#' + item.path"
            :data-short="item.label.slice(0, 1)"
            :aria-label="item.label"
            :title="item.label"
            :class="{ active: isActive(item.path) }"
            >{{ item.label
            }}<span v-if="item.key === 'approvals' && counts" class="nav-badge">{{
              counts.total
            }}</span></a
          >
        </div>
      </section>
      <small class="nav-footer">独立环境 · v1.2</small>
    </aside>
    <main class="main">
      <header class="topbar">
        <!-- F1-2：顶栏左侧两级面包屑（page-chrome.area + title）；中间挂载点承接本页
             PageHeader 的 Teleport 操作（随路由卸载自动清理），右侧命令面板入口/密度/身份/退出。 -->
        <nav class="breadcrumb" aria-label="面包屑">
          <span class="crumb-area">{{ pageChrome.eyebrow }}</span>
          <span class="crumb-sep" aria-hidden="true">/</span>
          <strong class="crumb-title">{{ pageChrome.title }}</strong>
        </nav>
        <div :id="pageActionsTarget" class="page-actions-slot"></div>
        <div class="actions">
          <el-button text class="palette-trigger" @click="paletteOpen = true">
            搜索<kbd class="kbd">Ctrl K</kbd>
          </el-button>
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
    <!-- F1-2 全局命令面板：Ctrl+K/⌘K 唤起，Esc 关闭 -->
    <CommandPalette :open="paletteOpen" @close="paletteOpen = false" />
  </div>
</template>

<style scoped>
/* 侧栏菜单超长时独立滚动（方案 §7.1 高度铁律②），不产生第二条页面滚动条 */
.nav {
  overflow-y: auto;
  min-height: 0;
}
.nav-group {
  display: flex;
  flex-direction: column;
}
.nav-group-head {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  width: 100%;
  padding: var(--sp-1) var(--sp-2);
  background: none;
  border: 0;
  border-radius: var(--radius);
  color: var(--nav-muted);
  font-size: var(--text-meta);
  text-align: left;
  cursor: pointer;
}
.nav-group-head:hover {
  color: var(--nav-text-active);
}
.gicon {
  width: 1rem;
  height: 1rem;
  flex-shrink: 0;
}
.glabel {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.gchev {
  margin-left: auto;
  font-size: var(--text-meta);
  transition: transform 0.15s ease;
}
.nav-group-head[aria-expanded="false"] .gchev {
  transform: rotate(-90deg);
}
.nav-group-items {
  display: flex;
  flex-direction: column;
}
.nav-group-items a {
  position: relative;
}
/* 确认中心待办角标：四类待办合计 total（15s 轮询 mock，B4-1 后切真实端点） */
.nav-badge {
  position: absolute;
  top: 50%;
  right: var(--sp-2);
  transform: translateY(-50%);
  min-width: var(--sp-5);
  padding: 0 var(--sp-1);
  border-radius: 999px;
  background: var(--st-fail);
  color: var(--on-brand);
  font-size: var(--text-meta);
  line-height: 1.4;
  text-align: center;
  font-variant-numeric: tabular-nums;
}
.nav-footer {
  margin-top: auto;
}
.breadcrumb {
  display: flex;
  align-items: baseline;
  gap: var(--sp-2);
  min-width: 0;
}
.crumb-area {
  color: var(--muted);
  white-space: nowrap;
}
.crumb-sep {
  color: var(--text-tertiary);
}
.crumb-title {
  font-size: var(--text-title);
  font-weight: 650;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.kbd {
  margin-left: var(--sp-2);
  padding: 0 var(--sp-1);
  font-family: var(--font-mono);
  font-size: var(--text-meta);
  color: var(--text-tertiary);
  background: var(--bg-surface-2);
  border: 1px solid var(--border-subtle);
  border-radius: var(--el-border-radius-base, 4px);
}
.page-actions-slot {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-left: auto;
}
/* 窄屏侧栏（tokens.css <1200px 收窄至 4.5rem）：分组头只留图标，tooltip 显示分组名 */
@media (max-width: 1200px) {
  .nav-group-head {
    justify-content: center;
    padding: var(--sp-1);
  }
  .nav-group-head .glabel,
  .nav-group-head .gchev {
    display: none;
  }
}
@media (max-width: 900px) {
  .capability-mode { min-width: 0; }
  .capability-mode .topbar { height: auto; min-height: 3.5rem; flex-wrap: wrap; gap: var(--sp-2); padding: var(--sp-3) var(--sp-4); }
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
    gap: var(--sp-2);
    padding: var(--sp-3) var(--sp-4);
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
    gap: var(--sp-2);
    padding: var(--sp-3) var(--sp-4);
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
    gap: var(--sp-2);
    padding: var(--sp-3) var(--sp-4);
  }
  .skill-detail-mode .topbar .actions {
    flex-wrap: wrap;
  }
}
</style>
