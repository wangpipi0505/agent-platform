/**
 * MNT-02子步1：路由访问策略单一来源——菜单、导航与路由 meta 均以本表为准。
 * 读写分离：read 是"打开页面"的条件（本轮统一为已验证主体，不以 can('edit') 替代读权限，
 * 读接口由后端独立鉴权）；write 只登记页内写操作所需的按钮上下文（证据=页面内
 * can()/v-permission 现有用法），同样不是授权依据——后端对每次操作独立鉴权。
 */

export type PageRole = "session" | "edit" | "approve" | "admin";

export interface PageAccessEntry {
  /** 稳定键：路由 meta.access 引用。 */
  key: string;
  /** 菜单路径；参数页为父模块菜单路径。 */
  path: string;
  /** 参数化路由（非菜单项时提供）。 */
  route?: string;
  title: string;
  /** 顶栏 eyebrow 分组。 */
  area: string;
  /** 打开页面的条件：session=任意已验证主体。 */
  read: PageRole;
  /** 页内写操作所需角色；空串=页面无统一写操作。 */
  write: PageRole | "";
  /** 是否进入左菜单。 */
  menu: boolean;
  /**
   * C0-1 裁剪标记（1109 基准，主方案 §2.1）：挂起线页面——代码保留不删除、深链可打开
   * （挂起线冒烟要求），但不进左菜单、验收主线不含。挂起线=本体协同（Semantic*）与
   * onboarding 接入向导；明细见 docs/baseline-trim-1109.md。
   */
  suspended?: true;
}

export const PAGE_ACCESS_VERSION = 1;

export const pageAccess: PageAccessEntry[] = [
  { key: "capability-packages", path: "/capability-packages", title: "软件能力目录", area: "平台管理", read: "session", write: "", menu: true },
  // C0-1 裁剪：onboarding 接入向导线挂起（主方案 §2.1）——构建流程归入智能体工作区，
  // 向导组件挂起；代码保留、深链可打开，不进菜单。
  { key: "onboarding-templates", path: "/onboarding/templates", title: "接入模板", area: "平台管理", read: "session", write: "admin", menu: false, suspended: true },
  { key: "apps", path: "/apps", title: "智能体管理", area: "平台管理", read: "session", write: "edit", menu: true },
  { key: "integration-config", path: "/integration/config-releases", title: "配置发布", area: "平台管理", read: "session", write: "admin", menu: true },
  { key: "integration-connections", path: "/integration/connection-profiles", title: "连接档案", area: "平台管理", read: "session", write: "admin", menu: false },
  { key: "integration-bindings", path: "/integration/environment-bindings", title: "环境绑定", area: "平台管理", read: "session", write: "admin", menu: false },
  { key: "integration-profiles", path: "/integration/integration-profiles", title: "接入档案", area: "平台管理", read: "session", write: "admin", menu: false },
  { key: "integration-experience", path: "/integration/experiences", title: "体验编辑", area: "平台管理", read: "session", write: "admin", menu: false },
  // C0-1 裁剪：本体协同线（Semantic*/semantic-bindings）挂起（主方案 §2.1）——1109 无本体
  // 要求；代码保留、深链可打开，不进菜单。
  { key: "integration-semantics", path: "/integration/semantic-bindings", title: "语义与目标", area: "平台管理", read: "session", write: "edit", menu: false, suspended: true },
  { key: "integration-diagnostics", path: "/integration/diagnostics", title: "接入诊断", area: "平台管理", read: "session", write: "", menu: false },
  { key: "app-workspace", path: "/apps", route: "/apps/:code/:tabMatch(.*)*", title: "应用工作区", area: "平台管理", read: "session", write: "edit", menu: false },
  { key: "operations", path: "/operations", title: "运行中心", area: "运行与支撑", read: "session", write: "admin", menu: true },
  { key: "runs", path: "/runs", title: "运行监控", area: "运行与支撑", read: "session", write: "", menu: true },
  { key: "run-detail", path: "/runs", route: "/runs/:runId", title: "运行详情", area: "运行与支撑", read: "session", write: "", menu: false },
  { key: "skills", path: "/skills", title: "技能库", area: "平台管理", read: "session", write: "edit", menu: true },
  { key: "skill-detail", path: "/skills", route: "/skills/:code", title: "技能详情", area: "平台管理", read: "session", write: "edit", menu: false },
  { key: "services", path: "/services", title: "服务管理", area: "运行与支撑", read: "session", write: "admin", menu: true },
  { key: "providers", path: "/model/providers", title: "模型供应商", area: "模型与模板", read: "session", write: "edit", menu: true },
  { key: "provider-entries", path: "/model/providers", route: "/model/providers/:providerId/entries", title: "模型条目", area: "模型与模板", read: "session", write: "edit", menu: false },
  { key: "entries", path: "/model/entries", title: "模型条目", area: "模型与模板", read: "session", write: "edit", menu: true },
  { key: "templates", path: "/model/prompt-templates", title: "提示词模板", area: "模型与模板", read: "session", write: "edit", menu: true },
  { key: "approvals", path: "/approvals", title: "确认中心", area: "运行与支撑", read: "session", write: "approve", menu: true },
  { key: "evaluations", path: "/evaluations", title: "评测中心", area: "运行与支撑", read: "session", write: "", menu: true },
  { key: "statistics", path: "/statistics", title: "统计分析", area: "运行与支撑", read: "session", write: "", menu: true },
  { key: "audit", path: "/audit", title: "审计记录", area: "治理", read: "session", write: "", menu: true },
  { key: "system", path: "/system", title: "系统管理", area: "治理", read: "session", write: "admin", menu: true },
];

export const pageByKey: Record<string, PageAccessEntry> = Object.fromEntries(
  pageAccess.map((entry) => [entry.key, entry]),
);

export function menuEntries(): PageAccessEntry[] {
  // C0-1：挂起线页面不进菜单（suspended 标记），深链仍可打开（挂起线冒烟要求）。
  return pageAccess.filter((entry) => entry.menu && !entry.suspended);
}

/** 与既有 can() 同一角色映射：ADMIN 全量，edit/approve/admin 各对应专属角色。 */
export function roleAllows(read: PageRole, roles: readonly string[]): boolean {
  if (read === "session") return true;
  // 与既有 can() 同一映射表（approve→APPROVER，非大写化），避免"APPROVE"错配。
  const mapped = { edit: "EDITOR", approve: "APPROVER", admin: "ADMIN" } as Record<string, string>;
  return roles.includes("ADMIN") || roles.includes(mapped[read] || "");
}

/**
 * 鉴权读取桥：由 PortalShell（应用壳）安装，读取活跃会话的已验证状态。
 * 默认放行——未安装桥时由 PortalShell 自身的 token 门承接（App 仅在已验证后渲染路由内容）。
 */
let authReader: () => { hasToken: boolean; roles: readonly string[] } = () => ({
  hasToken: true,
  roles: [],
});

export function installPageAccessAuth(reader: () => {
  hasToken: boolean;
  roles: readonly string[];
}) {
  authReader = reader;
}

/** 导航决策：读条件不满足时不加载页面，并给出 403 入口原因（reason=role）或引导登录（session）。 */
export function navigationDecision(
  entry: PageAccessEntry,
  auth: { hasToken: boolean; roles: readonly string[] },
): { allow: boolean; reason?: "session" | "role" } {
  if (entry.read === "session") return auth.hasToken ? { allow: true } : { allow: false, reason: "session" };
  if (!auth.hasToken) return { allow: false, reason: "session" };
  return roleAllows(entry.read, auth.roles) ? { allow: true } : { allow: false, reason: "role" };
}

export function accessDecisionByKey(key: string): {
  entry?: PageAccessEntry;
  allow: boolean;
  reason?: "session" | "role";
} {
  const entry = pageByKey[key];
  if (!entry) return { allow: true };
  const decision = navigationDecision(entry, authReader());
  return { entry, ...decision };
}
