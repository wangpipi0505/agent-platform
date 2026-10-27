import { createRouter, createWebHashHistory, type RouteRecordRaw } from "vue-router";

const routes: RouteRecordRaw[] = [
  { path: "/", redirect: "/apps" },
  {
    path: "/apps",
    name: "apps",
    component: () => import("./pages/AppsPage.vue"),
    meta: { title: "智能体管理" },
  },
  {
    path: "/apps/:code/:tabMatch(.*)*",
    name: "app-workspace",
    component: () => import("./pages/AppWorkspacePage.vue"),
    meta: { title: "应用工作区" },
  },
  {
    path: "/runs",
    name: "runs",
    component: () => import("./pages/RunsPage.vue"),
    meta: { title: "运行监控" },
  },
  {
    path: "/runs/:runId",
    name: "run-detail",
    component: () => import("./pages/RunDetailPage.vue"),
    meta: { title: "运行详情" },
  },
  {
    path: "/skills",
    name: "skills",
    component: () => import("./pages/SkillsPage.vue"),
    meta: { title: "技能库" },
  },
  {
    path: "/services",
    name: "services",
    component: () => import("./pages/ServiceManagementPage.vue"),
    meta: { title: "服务管理" },
  },
  { path: "/skills/new", redirect: "/skills" },
  {
    path: "/skills/:code",
    name: "skill-detail",
    component: () => import("./pages/SkillDetailPage.vue"),
    meta: { title: "技能详情" },
  },
  {
    path: "/model/providers",
    name: "providers",
    component: () => import("./pages/ProvidersPage.vue"),
    meta: { title: "模型供应商" },
  },
  {
    path: "/model/providers/:providerId/entries",
    name: "provider-entries",
    component: () => import("./pages/ModelEntriesPage.vue"),
    meta: { title: "模型条目" },
  },
  {
    path: "/model/entries",
    name: "entries",
    component: () => import("./pages/ModelEntriesPage.vue"),
    meta: { title: "模型条目" },
  },
  {
    path: "/model/prompt-templates",
    name: "templates",
    component: () => import("./pages/PromptTemplatesPage.vue"),
    meta: { title: "提示词模板" },
  },
  {
    path: "/approvals",
    name: "approvals",
    component: () => import("./pages/ApprovalsPage.vue"),
    meta: { title: "确认中心" },
  },
  {
    path: "/evaluations",
    name: "evaluations",
    component: () => import("./pages/EvaluationsPage.vue"),
    meta: { title: "评测中心" },
  },
  {
    path: "/audit",
    name: "audit",
    component: () => import("./pages/AuditPage.vue"),
    meta: { title: "审计记录" },
  },
  {
    path: "/statistics",
    name: "statistics",
    component: () => import("./pages/StatisticsPage.vue"),
    meta: { title: "统计分析" },
  },
  {
    path: "/system",
    name: "system",
    component: () => import("./pages/SystemPage.vue"),
    meta: { title: "系统管理" },
  },
  { path: "/:pathMatch(.*)*", redirect: "/apps" },
];

export const router = createRouter({
  history: createWebHashHistory(),
  routes,
});
