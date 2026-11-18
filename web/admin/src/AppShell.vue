<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { useAuth } from "../../shared/api";
import { PAGE_ACTIONS_TARGET, pageChrome } from "./page-chrome";
import { navMenus } from "./router";
import { MENU_GROUP_ORDER, type MenuGroup } from "./page-access";
import { canReadCapabilityPackages } from "../../shared/capability-package";
import {
  fetchConfirmationCounts,
  onConfirmationCountsChanged,
  type ServerConfirmationCounts,
} from "../../shared/api-confirmation";
import CommandPalette from "./CommandPalette.vue";
import { useTheme } from "../../shared/theme";

const auth = useAuth();
const route = useRoute();
// MNT-02子步1：菜单从 page-access 单一来源派生（标题/路径/分组与路由 meta 同表）。
// F1-2：按五分组（§3.1）渲染，分组常显、可折叠（折叠后 tooltip 显示分组名）。
const density = ref(localStorage.getItem("density") || "标准");
const pageActionsTarget = PAGE_ACTIONS_TARGET;

// F4-4 亮色切换（方案 §7.3）：顶栏按钮翻转 dark/light 并持久 localStorage，
// 初始跟随系统（shared/theme.ts）；系统偏好变化仅在没有手动覆盖时跟随。
const { resolved: themeResolved, toggleTheme, dispose: disposeTheme } = useTheme();

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

// —— F4-4 断点体系（方案 §7.2 断点表：≥1440 全量 / 1280 侧栏自动折叠 / <768 抽屉） ——
// ≤1280 侧栏收窄为图标列（复用 F1-2 分组折叠语义：组头只留图标+tooltip；菜单项取
// data-short 首字代字）；<768 侧栏整体收抽屉（照 webapp 先例：matchMedia+scrim，
// 抽屉内恢复全量菜单）。matchMedia 驱动而非纯 CSS media，抽屉/图标列互斥由类计算保证。
const iconMode = ref(false);
const mobile = ref(false);
const drawerOpen = ref(false);
let desktopQuery: MediaQueryList | null = null;
let mobileQuery: MediaQueryList | null = null;
function onDesktopChange(e: MediaQueryListEvent) {
  iconMode.value = e.matches;
}
function onMobileChange(e: MediaQueryListEvent) {
  mobile.value = e.matches;
  if (!e.matches) drawerOpen.value = false;
}

// —— 待办角标：15s 轮询真实端点 GET /admin/confirmation-center/counts（B4-1，M5-2 切真实 API），
// 角标渲染在确认中心菜单项上显示 total。 ——
const counts = ref<ServerConfirmationCounts | null>(null);
let pollTimer: number | undefined;
function loadCounts() {
  fetchConfirmationCounts()
    .then((data) => {
      counts.value = data;
    })
    .catch(() => {});
}

onMounted(() => {
  loadCounts();
  pollTimer = window.setInterval(loadCounts, 15_000);
  // F3-3：确认中心处置后立即刷新角标（不等 15s 轮询）——角标与四类列表同源（计数由
  // 四类待办矩阵实时推导），处置页调用 notifyConfirmationCountsChanged 触发本重读。
  const offCountsChanged = onConfirmationCountsChanged(loadCounts);
  window.addEventListener("keydown", onGlobalKey);
  onUnmounted(() => offCountsChanged());
  // F4-4 断点接线：初始值先落（刷新即正确形态），再监听变化。
  desktopQuery = matchMedia("(max-width: 1280px)");
  iconMode.value = desktopQuery.matches;
  desktopQuery.addEventListener("change", onDesktopChange);
  mobileQuery = matchMedia("(max-width: 768px)");
  mobile.value = mobileQuery.matches;
  mobileQuery.addEventListener("change", onMobileChange);
});
onUnmounted(() => {
  if (pollTimer !== undefined) window.clearInterval(pollTimer);
  window.removeEventListener("keydown", onGlobalKey);
  desktopQuery?.removeEventListener("change", onDesktopChange);
  mobileQuery?.removeEventListener("change", onMobileChange);
  disposeTheme();
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
    <!-- F4-4 断点形态：icon-mode（≤1280 图标列，与 mobile 互斥——抽屉内恢复全量菜单）、
         drawer-open（<768 抽屉展开）；tokens.css ≤1280 规则为 CSS 兜底。 -->
    <aside
      class="nav"
      :class="{ 'icon-mode': iconMode && !mobile, 'drawer-open': drawerOpen }"
    >
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
            @click="drawerOpen = false"
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
        <!-- F4-4：<768 抽屉触发钮（仅窄屏渲染）；点击菜单项/scrim 关闭抽屉。 -->
        <el-button
          v-if="mobile"
          text
          class="drawer-trigger"
          aria-label="打开导航菜单"
          @click="drawerOpen = true"
          >☰ 菜单</el-button
        >
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
          <!-- F4-4 亮色切换（§7.3）：解析结果间翻转并持久覆盖，初始跟随系统。 -->
          <el-button
            text
            class="theme-toggle"
            data-testid="theme-toggle"
            :title="themeResolved === 'dark' ? '切换为亮色主题' : '切换为暗色主题'"
            :aria-label="
              '切换主题，当前' + (themeResolved === 'dark' ? '暗色' : '亮色')
            "
            @click="toggleTheme"
            >{{ themeResolved === "dark" ? "☾ 暗色" : "☀ 亮色" }}</el-button
          >
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
    <!-- F4-4 <768 抽屉遮罩：点击关闭抽屉（webapp 同款 scrim 机制） -->
    <div
      v-if="mobile && drawerOpen"
      class="scrim"
      aria-hidden="true"
      @click="drawerOpen = false"
    ></div>
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
/* —— F4-4 断点①：≤1280 图标列（§7.2 断点表；nav 宽 4.5rem 由 tokens.css ≤1280 媒体查询兜底，
   这里按 icon-mode 类补齐形态：组头只留图标 tooltip=分组名（复用 F1-2 折叠语义），
   菜单项取 data-short 首字代字，角标/页脚收纳）—— */
.nav.icon-mode .nav-group-head {
  justify-content: center;
  padding: var(--sp-1);
}
.nav.icon-mode .nav-group-head .glabel,
.nav.icon-mode .nav-group-head .gchev {
  display: none;
}
.nav.icon-mode .nav-group-items a {
  font-size: 0;
  text-align: center;
}
.nav.icon-mode .nav-group-items a::before {
  content: attr(data-short);
  font-size: var(--text-table);
}
.nav.icon-mode .nav-badge {
  position: static;
  display: block;
  width: fit-content;
  margin: 0.15rem auto 0;
  transform: none;
}
.nav.icon-mode .logo {
  font-size: 0;
}
.nav.icon-mode .logo::before {
  content: "◈";
  font-size: var(--text-module);
}
.nav.icon-mode .nav-footer {
  visibility: hidden;
}
/* —— F4-4 断点②：<768 抽屉（照 webapp 先例：matchMedia+fixed+translateX+scrim），
   抽屉内恢复全量菜单字级/内距（覆盖 tokens.css ≤1280 收窄兜底）—— */
.scrim {
  position: fixed;
  inset: 0;
  background: var(--el-mask-color);
  z-index: 20;
}
.drawer-trigger {
  display: none;
}
@media (max-width: 768px) {
  .drawer-trigger {
    display: inline-flex;
  }
  .breadcrumb {
    display: none;
  }
  .nav {
    position: fixed;
    top: 0;
    bottom: 0;
    left: 0;
    z-index: 30;
    width: min(80vw, 20rem);
    padding: 1.5rem 1rem;
    transform: translateX(-100%);
    transition: transform 0.2s ease;
  }
  .nav.drawer-open {
    transform: translateX(0);
  }
  .nav a {
    padding: 0.7rem 0.75rem;
    font-size: inherit;
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
