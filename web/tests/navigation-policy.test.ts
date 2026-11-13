// MNT-02子步1：路由访问单一来源（page-access.ts）与未知路径404。
// 锁定：①主入口同源登记（标题/区域/读条件/写上下文；ONB-01起15个——新增接入模板页；INT-08起16个——新增配置发布入口）；②读条件本轮统一为
// 已验证主体（不以can('edit')替代读权限），写上下文按页面实际can()/v-permission用法登记；
// ③未知路径命中 NotFoundPage 而非静默重定向；④合法主体原深链可达。
// F1-2：PageRole 六角色（session/builder/approve/operator/admin/user，edit→builder 迁移）、
// 菜单五分组（前端稿 §3.1，每组菜单条目 ≤10；read 仍全 session，按角色隐藏菜单留待 B4-1）。
import { expect, it } from "vitest";
import { readFileSync } from "node:fs";
import {
  MENU_GROUP_ORDER,
  PAGE_ACCESS_VERSION,
  groupedMenuEntries,
  menuEntries,
  navigationDecision,
  pageAccess,
  pageByKey,
  roleAllows,
} from "../admin/src/page-access";

// vitest 根配置无 vue 插件，无法直接 import router.ts（传递引入 .vue）；
// 路由登记以源码静态断言锁定，page-access 策略动态断言。
const routerSource = readFileSync(
  new URL("../admin/src/router.ts", import.meta.url),
  "utf8",
);

it("单一来源：主入口（C0-1 裁剪+F3-4 收敛+F3-1 任务工作台后 15 个）与全部页面登记齐全且字段完整", () => {
  const menu = menuEntries();
  // C0-1 裁剪：接入模板/语义与目标两条挂起线移出菜单（主方案 §2.1），17→15；
  // F3-4 菜单收敛（前端稿 §5.7/§5.8）：集成控制台 1 条替代配置发布 1 条（其余深链页本就
  // menu:false），模型管理 1 条替代模型供应商+模型条目 2 条，15→14；
  // F3-1：任务工作台（方案 §5.3 管理台侧）入「任务与运行」组菜单，14→15。
  expect(menu.length).toBe(15);
  expect(new Set(menu.map((e) => e.path)).size).toBe(15);
  for (const entry of pageAccess) {
    expect(entry.title.length, entry.key).toBeGreaterThan(0);
    expect(entry.area.length, entry.key).toBeGreaterThan(0);
    expect(
      ["session", "builder", "approve", "operator", "admin", "user"],
      entry.key,
    ).toContain(entry.read);
  }
  expect(pageByKey["apps"].menu).toBe(true);
  expect(pageByKey["run-detail"].menu).toBe(false);
  expect(pageByKey["run-detail"].route).toBe("/runs/:runId");
});

it("C0-1 挂起线：Semantic*/onboarding 移出菜单但代码保留、深链可打开", () => {
  // 挂起标记：仅本体协同线与 onboarding 向导线两个入口（主方案 §2.1）
  const suspended = pageAccess.filter((e) => e.suspended);
  expect(suspended.map((e) => e.key).sort()).toEqual(["integration-semantics", "onboarding-templates"]);
  for (const entry of suspended) {
    expect(entry.menu, entry.key).toBe(false); // 不进菜单
    expect(entry.read, entry.key).toBe("session"); // 读门不变——深链可打开（挂起线冒烟）
  }
  // 菜单派生排除挂起项；路由与读条件保持，代码保留不删除
  expect(menuEntries().some((e) => e.suspended)).toBe(false);
  expect(routerSource).toContain('"/onboarding/templates"');
  expect(routerSource).toContain('"/integration/semantic-bindings"');
});

it("读权限本轮统一为已验证主体：不以edit替代读门", () => {
  expect(PAGE_ACCESS_VERSION).toBe(1);
  for (const entry of pageAccess) {
    expect(entry.read, entry.key).toBe("session");
  }
  // 写上下文按页面实际用法登记（证据=页面内 can()/v-permission 用法），非空仅在确有写操作处
  // F1-2：既有 edit 值迁移为 builder（BUILDER=现 EDITOR 扩展，主方案 §7.2）——条目数据同步改名
  expect(pageByKey["services"].write).toBe("admin");
  expect(pageByKey["approvals"].write).toBe("approve");
  expect(pageByKey["skills"].write).toBe("builder");
  expect(pageByKey["apps"].write).toBe("builder");
  expect(
    readFileSync(new URL("../admin/src/page-access.ts", import.meta.url), "utf8"),
  ).not.toContain('write: "edit"');
});

it("路由源码：全部页面登记access键，未知路径命中404而非重定向", () => {
  for (const entry of pageAccess) {
    if (!entry.route) continue;
    expect(routerSource).toContain(`name: "${entry.key}"`);
  }
  expect(routerSource.match(/access: "/g)?.length).toBe(pageAccess.length);
  expect(new Set(pageAccess.map((e) => e.key)).size).toBe(pageAccess.length);
  expect(routerSource).toContain('name: "not-found"');
  expect(routerSource).toContain("component: NotFoundPage");
  expect(routerSource).not.toContain('"/:pathMatch(.*)*", redirect');
  // 菜单同源：AppShell 从 router 派生 navMenus，不再手写入口数组
  expect(routerSource).toContain("export const navMenus = menuEntries()");
  // F3-4：旧集成/模型深链收敛为控制台 redirect（锚点映射见 router.ts 注释）；
  // 挂起线 semantic-bindings 不并入控制台，仍为独立路由（上方挂起线断言继续锁定）。
  expect(routerSource).toContain('path: "/integration/console"');
  expect(routerSource).toContain('redirect: { path: "/integration/console", query: { panel: "releases" } }');
  expect(routerSource).toContain('query: { panel: "connections" }');
  expect(routerSource).toContain('query: { panel: "bindings" }');
  expect(routerSource).toContain('query: { panel: "profiles" }');
  expect(routerSource).toContain('query: { panel: "experience" }');
  expect(routerSource).toContain('query: { panel: "diagnostics" }');
  expect(routerSource).toContain('path: "/model/management"');
  expect(routerSource).toContain("String(to.params.providerId");
});

it("导航决策：已验证主体放行，未验证拦截并给出入口原因", () => {
  const entry = pageByKey["apps"];
  expect(navigationDecision(entry, { hasToken: true, roles: [] }).allow).toBe(true);
  const denied = navigationDecision(entry, { hasToken: false, roles: [] });
  expect(denied.allow).toBe(false);
  expect(denied.reason).toBe("session");
  // 本轮读门均为session：角色拦截仅在写上下文使用，读不因缺角色拒绝
  expect(navigationDecision(entry, { hasToken: true, roles: ["EDITOR"] }).allow).toBe(true);
});

it("roleAllows六角色映射（F1-2）：ADMIN全量，builder双兼容EDITOR，其余各对应", () => {
  // builder→BUILDER 或 EDITOR：现行 claims 仍发 EDITOR（BUILDER=其扩展名），双兼容
  expect(roleAllows("builder", ["EDITOR"])).toBe(true);
  expect(roleAllows("builder", ["BUILDER"])).toBe(true);
  expect(roleAllows("builder", ["ADMIN"])).toBe(true);
  expect(roleAllows("approve", ["APPROVER"])).toBe(true);
  expect(roleAllows("operator", ["OPERATOR"])).toBe(true);
  expect(roleAllows("user", ["USER"])).toBe(true);
  expect(roleAllows("admin", ["ADMIN"])).toBe(true);
  expect(roleAllows("builder", ["APPROVER"])).toBe(false);
  expect(roleAllows("admin", ["EDITOR"])).toBe(false);
  expect(roleAllows("user", ["EDITOR"])).toBe(false);
});

it("F1-2 五分组菜单：分组名与渲染顺序固定，每组菜单条目 ≤10", () => {
  // 前端稿 §3.1 角色导航树：①智能体 ②任务与运行 ③构建资产 ④集成与治理 ⑤平台管理
  expect(MENU_GROUP_ORDER).toEqual(["智能体", "任务与运行", "构建资产", "集成与治理", "平台管理"]);
  const sections = groupedMenuEntries();
  expect(sections.map((s) => s.group)).toEqual([...MENU_GROUP_ORDER]); // 五组均非空，顺序=侧栏渲染顺序
  // 当前 read 全 session 口径下按全部条目断言（按角色隐藏菜单的 enforcement 留待 B4-1）
  for (const section of sections) {
    expect(section.entries.length, section.group).toBeLessThanOrEqual(10);
    expect(section.entries.every((e) => e.area === section.group), section.group).toBe(true);
  }
  // 全部条目（F3-4 收敛后 20 条，含挂起线与参数页）都落在五分组内——area 即分组唯一来源
  expect(pageAccess.every((e) => (MENU_GROUP_ORDER as readonly string[]).includes(e.area))).toBe(
    true,
  );
});
