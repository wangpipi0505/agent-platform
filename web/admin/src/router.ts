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
    path: "/platform/authorizations",
    name: "platform-authorizations",
    component: () => import("./pages/PlatformAuthorizationsPage.vue"),
    meta: { access: "platform-authorizations", title: "用户与授权" },
  },
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
    meta: { access: "app-workspace", title: "智能体工作区" },
  },
  // F2-1：智能体运行记录独立路由（静态段 records 优先于上方 tabMatch 通配，旧深链
  // /apps/:code/records/:runId 由工作区内嵌分支自然迁移到本页）。不复用全局 run-detail
  // （/runs/:runId 的返回语境是运行监控）：本页面包屑锚回工作区「运行记录」锚点。
  {
    path: "/apps/:code/records/:runId",
    name: "app-run-record",
    component: () => import("./pages/AppRunRecordPage.vue"),
    meta: { access: "app-run-record", title: "运行记录详情" },
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
  // F3-2：管理台只读计划详情（前端稿 §5.3：管理台供运维查看对应计划与执行链路）。
  // 追加式注册：排在既有任务与运行条目（run-detail）之后；确认/执行入口属助手端
  // 计划卡（F4-3），本页只读、不进菜单（深链 /plans/:planId，同 run-detail 的参数页模式）。
  {
    path: "/plans/:planId",
    name: "plan-detail",
    component: () => import("./pages/PlanDetailPage.vue"),
    meta: { access: "plan-detail", title: "计划详情" },
  },
  // F3-6：会话详情与步骤调用下钻（台账 §7.5 / 前端稿 §5.4）——管理台侧参数页：
  // 入口=/tasks 或 /runs 下钻 + 直链 /sessions/:sessionId；USER 助手端入口属 F4 域。
  // 双视角投影（USER 普通视图白名单 / OPERATOR 诊断视图），授权与字段裁剪以服务端为准。
  {
    path: "/sessions/:sessionId",
    name: "session-detail",
    component: () => import("./pages/SessionDetailPage.vue"),
    meta: { access: "session-detail", title: "会话详情" },
  },
  // F3-1：任务工作台（方案 §5.3 管理台侧=监控与运维）——追加式注册，既有路由不重排。
  {
    path: "/tasks",
    name: "tasks",
    component: () => import("./pages/TasksPage.vue"),
    meta: { access: "tasks", title: "任务工作台" },
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
  // F3-4 集成控制台（前端稿 §5.7）：原 7 条集成路由并成一页内左导航（六面板懒挂载），
  // 智能体上下文选择器持久页头（?app=）；语义绑定（Semantic*）为挂起线不并入，路由保留。
  {
    path: "/integration/console",
    name: "integration-console",
    component: () => import("./pages/integration/IntegrationConsolePage.vue"),
    meta: { access: "integration-console", title: "集成控制台" },
  },
  // F3-4 旧集成深链重定向映射（锚点=控制台左导航 query.panel）：
  //   config-releases→releases  connection-profiles→connections  environment-bindings→bindings
  //   integration-profiles→profiles  experiences→experience  diagnostics→diagnostics
  // 旧页面组件保留（控制台 embedded 复用），仅路由收敛；挂起线 semantic-bindings 不在此列。
  { path: "/integration/config-releases", redirect: { path: "/integration/console", query: { panel: "releases" } } },
  { path: "/integration/connection-profiles", redirect: { path: "/integration/console", query: { panel: "connections" } } },
  { path: "/integration/environment-bindings", redirect: { path: "/integration/console", query: { panel: "bindings" } } },
  { path: "/integration/integration-profiles", redirect: { path: "/integration/console", query: { panel: "profiles" } } },
  { path: "/integration/experiences", redirect: { path: "/integration/console", query: { panel: "experience" } } },
  { path: "/integration/diagnostics", redirect: { path: "/integration/console", query: { panel: "diagnostics" } } },
  {
    path: "/integration/semantic-bindings",
    name: "integration-semantics",
    component: () => import("./pages/integration/SemanticBindingsPanel.vue"),
    meta: { access: "integration-semantics", title: "语义与目标" },
  },
  // F3-4 模型管理（前端稿 §5.8）：providers+entries 两级跳转并成 master-detail 一页，
  // 供应商选择经 query ?provider= 持久。
  {
    path: "/model/management",
    name: "model-management",
    component: () => import("./pages/ModelManagementPage.vue"),
    meta: { access: "model-management", title: "模型管理" },
  },
  // F3-4 旧模型页重定向（providerId 深链映射 ?provider=<id>；旧页面文件保留为回归锚，仅路由与菜单收敛）。
  {
    path: "/model/providers/:providerId/entries",
    redirect: (to) => ({ path: "/model/management", query: { provider: String(to.params.providerId ?? "") } }),
  },
  { path: "/model/providers", redirect: { path: "/model/management" } },
  { path: "/model/entries", redirect: { path: "/model/management" } },
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
  // F3-3 资产管理四页（前端稿 §3.1/§5.9；接口稿 §5.4-5.6 mock 驱动）——追加式注册，
  // 既有路由不重排：/assets/memories 记忆、/assets/rules 规则、/assets/plugins 插件、
  // /assets/mcp MCP 连接；确认中心四类待办矩阵在既有 /approvals 页内扩展（不新增路由）。
  {
    path: "/assets/memories",
    name: "memories",
    component: () => import("./pages/assets/MemoriesPage.vue"),
    meta: { access: "memories", title: "记忆管理" },
  },
  {
    path: "/assets/rules",
    name: "rules",
    component: () => import("./pages/assets/RulesPage.vue"),
    meta: { access: "rules", title: "规则管理" },
  },
  {
    path: "/assets/plugins",
    name: "plugins",
    component: () => import("./pages/assets/PluginsPage.vue"),
    meta: { access: "plugins", title: "插件与来源" },
  },
  {
    path: "/assets/mcp",
    name: "mcp",
    component: () => import("./pages/assets/McpConnectionsPage.vue"),
    meta: { access: "mcp", title: "MCP 连接" },
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
  // 深链冷启动时门户会话尚未建立（hasToken=false），不能直接判 404：
  // PortalShell 只在 auth.token 存在后渲染 <App>，导航先放行、由它自己的 token 门承接；
  // 只有"已登录但角色不足"才跳 403 入口。
  if (decision.reason === "session") return true;
  return {
    path: "/not-found",
    query: decision.reason === "role" ? { denied: decision.entry?.title || "" } : {},
  };
});

// MNT-02子步1：左菜单与路由同源（capability 目录可见性沿用既有角色判断由 AppShell 保留）。
// F1-2：补 key 供壳层定位条目（确认中心待办角标），area=五分组名（§3.1）供分组渲染。
export const navMenus = menuEntries().map((entry) => ({
  key: entry.key,
  label: entry.title,
  path: entry.path,
  area: entry.area,
}));
