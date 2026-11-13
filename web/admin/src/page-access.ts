/**
 * MNT-02子步1：路由访问策略单一来源——菜单、导航与路由 meta 均以本表为准。
 * 读写分离：read 是"打开页面"的条件（本轮统一为已验证主体，不以 can('edit') 替代读权限，
 * 读接口由后端独立鉴权）；write 只登记页内写操作所需的按钮上下文（证据=页面内
 * can()/v-permission 现有用法），同样不是授权依据——后端对每次操作独立鉴权。
 *
 * F1-2（主方案 §7.2）：PageRole 从 session/edit/approve/admin 扩展为六角色
 * session/builder/approve/operator/admin/user（保留 session 作为登录门）；既有 edit 值
 * 迁移为 builder（BUILDER=现 EDITOR 扩展）。本卡 read 条件保持 "session" 不变——按角色
 * 隐藏菜单的 enforcement 留待 B4-1 后端角色就绪（F4 联调期），本卡只交付类型/映射/分组结构。
 * 菜单五分组（前端稿 §3.1 角色导航树）：①智能体 ②任务与运行 ③构建资产 ④集成与治理 ⑤平台管理。
 */

export type PageRole = "session" | "builder" | "approve" | "operator" | "admin" | "user";

/** F1-2 菜单五分组（前端稿 §3.1，数组顺序=侧栏渲染顺序，分组名=面包屑一级）。 */
export const MENU_GROUP_ORDER = ["智能体", "任务与运行", "构建资产", "集成与治理", "平台管理"] as const;
export type MenuGroup = (typeof MENU_GROUP_ORDER)[number];

export interface PageAccessEntry {
  /** 稳定键：路由 meta.access 引用。 */
  key: string;
  /** 菜单路径；参数页为父模块菜单路径。 */
  path: string;
  /** 参数化路由（非菜单项时提供）。 */
  route?: string;
  title: string;
  /** 顶栏面包屑一级分组（F1-2 起为五分组之一，前端稿 §3.1）。 */
  area: MenuGroup;
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
  // ① 智能体（前端稿 §3.1：列表+详情=智能体工作区）
  { key: "apps", path: "/apps", title: "智能体管理", area: "智能体", read: "session", write: "builder", menu: true },
  { key: "app-workspace", path: "/apps", route: "/apps/:code/:tabMatch(.*)*", title: "智能体工作区", area: "智能体", read: "session", write: "builder", menu: false },
  // F2-1：运行记录独立页（/apps/:code/records/:runId）——不与全局 run-detail（/runs/:runId，
  // 返回语境=运行监控）合并：本页保留「返回工作区运行记录锚点」面包屑，详情实现复用 RunDetail。
  { key: "app-run-record", path: "/apps", route: "/apps/:code/records/:runId", title: "运行记录详情", area: "智能体", read: "session", write: "", menu: false },
  // ② 任务与运行
  { key: "operations", path: "/operations", title: "运行中心", area: "任务与运行", read: "session", write: "admin", menu: true },
  { key: "runs", path: "/runs", title: "运行监控", area: "任务与运行", read: "session", write: "", menu: true },
  { key: "run-detail", path: "/runs", route: "/runs/:runId", title: "运行详情", area: "任务与运行", read: "session", write: "", menu: false },
  // F3-2：管理台只读计划详情（前端稿 §5.3）——追加式登记在既有任务与运行条目后、
  // 挂起线（C0-1 标记）之前；纯只读页无写操作（write 留空），不进菜单、深链可打开。
  { key: "plan-detail", path: "/plans", route: "/plans/:planId", title: "计划详情", area: "任务与运行", read: "session", write: "", menu: false },
  { key: "services", path: "/services", title: "服务管理", area: "任务与运行", read: "session", write: "admin", menu: true },
  // F3-1：任务工作台（方案 §5.3 管理台侧=监控与运维；任务发起入口在助手端 F4-2，本页只读+处置）——
  // 追加式条目，既有行不重排；恢复/取消/对账处置按 §5.3 属授权 OPERATOR 语境，write 仅登记按钮上下文。
  { key: "tasks", path: "/tasks", title: "任务工作台", area: "任务与运行", read: "session", write: "operator", menu: true },
  // ③ 构建资产（BUILDER/ADMIN：技能库/提示词模板；画布流程在工作区内，无独立菜单）
  { key: "skills", path: "/skills", title: "技能库", area: "构建资产", read: "session", write: "builder", menu: true },
  { key: "skill-detail", path: "/skills", route: "/skills/:code", title: "技能详情", area: "构建资产", read: "session", write: "builder", menu: false },
  { key: "templates", path: "/model/prompt-templates", title: "提示词模板", area: "构建资产", read: "session", write: "builder", menu: true },
  // ④ 集成与治理（F3-4：集成控制台 7 路由并一页——六面板共用页头智能体上下文选择器
  // （?app=），旧 6 条深链路由 redirect 到 ?panel= 锚点；write=admin 与页内 can('admin') 一致。
  // 语义绑定（挂起线）仍独立路由深链可达，不并入）
  { key: "integration-console", path: "/integration/console", title: "集成控制台", area: "集成与治理", read: "session", write: "admin", menu: true },
  // C0-1 裁剪：本体协同线（Semantic*/semantic-bindings）挂起（主方案 §2.1）——1109 无本体
  // 要求；代码保留、深链可打开，不进菜单。
  { key: "integration-semantics", path: "/integration/semantic-bindings", title: "语义与目标", area: "集成与治理", read: "session", write: "builder", menu: false, suspended: true },
  // ⑤ 平台管理（ADMIN，OPERATOR 只读子集：模型/确认中心/统计/审计/系统）
  { key: "capability-packages", path: "/capability-packages", title: "软件能力目录", area: "平台管理", read: "session", write: "", menu: true },
  // C0-1 裁剪：onboarding 接入向导线挂起（主方案 §2.1）——构建流程归入智能体工作区，
  // 向导组件挂起；代码保留、深链可打开，不进菜单。
  { key: "onboarding-templates", path: "/onboarding/templates", title: "接入模板", area: "平台管理", read: "session", write: "admin", menu: false, suspended: true },
  // F3-4 模型管理 master-detail（前端稿 §5.8）：替代 providers+entries 两级跳转两条菜单；
  // write=admin 与页内 v-permission('admin') 一致（旧 providers 条目登记 builder 系历史口径，
  // 合并页以实际用法为准）。旧 /model/providers、/model/entries 路由 redirect 到本页。
  { key: "model-management", path: "/model/management", title: "模型管理", area: "平台管理", read: "session", write: "admin", menu: true },
  { key: "approvals", path: "/approvals", title: "确认中心", area: "平台管理", read: "session", write: "approve", menu: true },
  { key: "statistics", path: "/statistics", title: "统计分析", area: "平台管理", read: "session", write: "", menu: true },
  { key: "audit", path: "/audit", title: "审计记录", area: "平台管理", read: "session", write: "", menu: true },
  { key: "system", path: "/system", title: "系统管理", area: "平台管理", read: "session", write: "admin", menu: true },
  // 评测中心：前端稿 §3.1 移入"内部工具"子组（属平台管理分组，独立子组随 F3 落地）
  { key: "evaluations", path: "/evaluations", title: "评测中心", area: "平台管理", read: "session", write: "", menu: true },
];

export const pageByKey: Record<string, PageAccessEntry> = Object.fromEntries(
  pageAccess.map((entry) => [entry.key, entry]),
);

export function menuEntries(): PageAccessEntry[] {
  // C0-1：挂起线页面不进菜单（suspended 标记），深链仍可打开（挂起线冒烟要求）。
  return pageAccess.filter((entry) => entry.menu && !entry.suspended);
}

/** F1-2 五分组视图：按 MENU_GROUP_ORDER 归组的菜单条目（AppShell 分组渲染唯一来源）。 */
export function groupedMenuEntries(): { group: MenuGroup; entries: PageAccessEntry[] }[] {
  return MENU_GROUP_ORDER.map((group) => ({
    group,
    entries: menuEntries().filter((entry) => entry.area === group),
  })).filter((section) => section.entries.length > 0);
}

/**
 * F1-2 六角色映射（主方案 §7.2，与既有 can() 同一口径：ADMIN 全量）：
 * builder→BUILDER 或 EDITOR（现行 claims 仍发 EDITOR，BUILDER 为扩展名——双兼容）；
 * approve→APPROVER；operator→OPERATOR；admin→ADMIN；user→USER。
 */
export function roleAllows(read: PageRole, roles: readonly string[]): boolean {
  if (read === "session") return true;
  const mapped: Record<Exclude<PageRole, "session">, readonly string[]> = {
    builder: ["BUILDER", "EDITOR"],
    approve: ["APPROVER"],
    operator: ["OPERATOR"],
    admin: ["ADMIN"],
    user: ["USER"],
  };
  return roles.includes("ADMIN") || mapped[read].some((role) => roles.includes(role));
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
