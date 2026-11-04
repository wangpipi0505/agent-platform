import { createRouter, createWebHashHistory, type RouteRecordRaw } from "vue-router";
import { pageChrome } from "./page-chrome";
import {
  accessDecisionByKey,
  menuEntries,
  pageByKey,
} from "./page-access";
import NotFoundPage from "./pages/NotFoundPage.vue";

const routes: RouteRecordRaw[] = [
  { path: "/", redirect: "/apps" },
  {
    path: "/capability-packages",
    name: "capability-packages",
    component: () => import("./pages/CapabilityPackagesPage.vue"),
    meta: { access: "capability-packages", title: "软件能力目录" },
  },
  {
    path: "/onboarding/templates",
    name: "onboarding-templates",
    component: () => import("./pages/onboarding/OnboardingTemplatesPage.vue"),
    meta: { access: "onboarding-templates", title: "接入模板" },
  },
  {
    path: "/apps",
    name: "apps",
    component: () => import("./pages/AppsPage.vue"),
    meta: { access: "apps", title: "智能体管理" },
  },
  {
    path: "/apps/:code/:tabMatch(.*)*",
    name: "app-workspace",
    component: () => import("./pages/AppWorkspacePage.vue"),
    meta: { access: "app-workspace", title: "应用工作区" },
  },
  {
    path: "/operations",
    name: "operations",
    component: () => import("./pages/OperationsPage.vue"),
    meta: { access: "operations", title: "运行中心" },
  },
  {
    path: "/runs",
    name: "runs",
    component: () => import("./pages/RunsPage.vue"),
    meta: { access: "runs", title: "运行监控" },
  },
  {
    path: "/runs/:runId",
    name: "run-detail",
    component: () => import("./pages/RunDetailPage.vue"),
    meta: { access: "run-detail", title: "运行详情" },
  },
  {
    path: "/skills",
    name: "skills",
    component: () => import("./pages/SkillsPage.vue"),
    meta: { access: "skills", title: "技能库" },
  },
  {
    path: "/services",
    name: "services",
    component: () => import("./pages/ServiceManagementPage.vue"),
    meta: { access: "services", title: "服务管理" },
  },
  { path: "/skills/new", redirect: "/skills" },
  {
    path: "/skills/:code",
    name: "skill-detail",
    component: () => import("./pages/SkillDetailPage.vue"),
    meta: { access: "skill-detail", title: "技能详情" },
  },
  {
    path: "/integration/config-releases",
    name: "integration-config",
    component: () => import("./pages/integration/ConfigReleasesPanel.vue"),
    meta: { access: "integration-config", title: "配置发布" },
  },
  {
    path: "/integration/connection-profiles",
    name: "integration-connections",
    component: () => import("./pages/integration/ConnectionProfilesPage.vue"),
    meta: { access: "integration-connections", title: "连接档案" },
  },
  {
    path: "/integration/environment-bindings",
    name: "integration-bindings",
    component: () => import("./pages/integration/EnvironmentBindingsPage.vue"),
    meta: { access: "integration-bindings", title: "环境绑定" },
  },
  {
    path: "/integration/integration-profiles",
    name: "integration-profiles",
    component: () => import("./pages/integration/IntegrationProfilesPanel.vue"),
    meta: { access: "integration-profiles", title: "接入档案" },
  },
  {
    path: "/integration/experiences",
    name: "integration-experience",
    component: () => import("./pages/integration/ExperienceEditor.vue"),
    meta: { access: "integration-experience", title: "体验编辑" },
  },
  {
    path: "/integration/diagnostics",
    name: "integration-diagnostics",
    component: () => import("./pages/integration/IntegrationDiagnosticsPanel.vue"),
    meta: { access: "integration-diagnostics", title: "接入诊断" },
  },
  {
    path: "/model/providers",
    name: "providers",
    component: () => import("./pages/ProvidersPage.vue"),
    meta: { access: "providers", title: "模型供应商" },
  },
  {
    path: "/model/providers/:providerId/entries",
    name: "provider-entries",
    component: () => import("./pages/ModelEntriesPage.vue"),
    meta: { access: "entries", title: "模型条目" },
  },
  {
    path: "/model/entries",
    name: "entries",
    component: () => import("./pages/ModelEntriesPage.vue"),
    meta: { access: "entries", title: "模型条目" },
  },
  {
    path: "/model/prompt-templates",
    name: "templates",
    component: () => import("./pages/PromptTemplatesPage.vue"),
    meta: { access: "templates", title: "提示词模板" },
  },
  {
    path: "/approvals",
    name: "approvals",
    component: () => import("./pages/ApprovalsPage.vue"),
    meta: { access: "approvals", title: "确认中心" },
  },
  {
    path: "/evaluations",
    name: "evaluations",
    component: () => import("./pages/EvaluationsPage.vue"),
    meta: { access: "evaluations", title: "评测中心" },
  },
  {
    path: "/audit",
    name: "audit",
    component: () => import("./pages/AuditPage.vue"),
    meta: { access: "audit", title: "审计记录" },
  },
  {
    path: "/statistics",
    name: "statistics",
    component: () => import("./pages/StatisticsPage.vue"),
    meta: { access: "statistics", title: "统计分析" },
  },
  {
    path: "/system",
    name: "system",
    component: () => import("./pages/SystemPage.vue"),
    meta: { access: "system", title: "系统管理" },
  },
  // MNT-02子步1：未知路径显式404（带返回工作台入口），不再静默重定向。
  {
    path: "/:pathMatch(.*)*",
    name: "not-found",
    component: NotFoundPage,
    meta: { title: "页面不存在" },
  },
];

export const router = createRouter({
  history: createWebHashHistory(),
  routes,
});

// UI-02：路由离开复位壳层上下文，防止未写页头的页面残留上一页标题；
// 写页头的页面（PageHeader/usePageTitle）在挂载后覆盖复位值。
router.afterEach((to) => {
  const key = to.meta?.access as string | undefined;
  const entry = key ? pageByKey[key] : undefined;
  pageChrome.title = entry?.title || "智能体管理";
  pageChrome.eyebrow = entry?.area || "平台管理";
  pageChrome.subtitle = "连接业务能力，持续验证每一次运行。";
});
// MNT-02子步1：读条件不满足时不加载页面——缺验证主体引导登录（PortalShell 自身
// 的 token 门承接），缺角色经 /not-found?denied=<页面> 给出403处理入口。
// 合法主体深链不受影响；后端仍对每次操作独立鉴权，前端守卫只改善导航体验。
router.beforeEach((to) => {
  const key = to.meta?.access;
  if (!key) return true;
  const decision = accessDecisionByKey(key as string);
  if (decision.allow) return true;
  return {
    path: "/not-found",
    query: decision.reason === "role" ? { denied: decision.entry?.title || "" } : {},
  };
});

// MNT-02子步1：左菜单与路由同源（capability 目录可见性沿用既有角色判断由 AppShell 保留）。
export const navMenus = menuEntries().map((entry) => ({
  label: entry.title,
  path: entry.path,
  area: entry.area,
}));
