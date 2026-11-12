// MNT-02子步1：路由访问单一来源（page-access.ts）与未知路径404。
// 锁定：①主入口同源登记（标题/区域/读条件/写上下文；ONB-01起15个——新增接入模板页；INT-08起16个——新增配置发布入口）；②读条件本轮统一为
// 已验证主体（不以can('edit')替代读权限），写上下文按页面实际can()/v-permission用法登记；
// ③未知路径命中 NotFoundPage 而非静默重定向；④合法主体原深链可达。
import { expect, it } from "vitest";
import { readFileSync } from "node:fs";
import {
  PAGE_ACCESS_VERSION,
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

it("单一来源：主入口（C0-1 裁剪后 15 个）与全部页面登记齐全且字段完整", () => {
  const menu = menuEntries();
  // C0-1 裁剪：接入模板/语义与目标两条挂起线移出菜单（主方案 §2.1），17→15。
  expect(menu.length).toBe(15);
  expect(new Set(menu.map((e) => e.path)).size).toBe(15);
  for (const entry of pageAccess) {
    expect(entry.title.length, entry.key).toBeGreaterThan(0);
    expect(entry.area.length, entry.key).toBeGreaterThan(0);
    expect(["session", "edit", "approve", "admin"], entry.key).toContain(entry.read);
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
  expect(pageByKey["services"].write).toBe("admin");
  expect(pageByKey["approvals"].write).toBe("approve");
  expect(pageByKey["skills"].write).toBe("edit");
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

it("roleAllows与既有can()同映射：ADMIN全量，edit/approve/admin各对应", () => {
  expect(roleAllows("edit", ["EDITOR"])).toBe(true);
  expect(roleAllows("edit", ["ADMIN"])).toBe(true);
  expect(roleAllows("approve", ["APPROVER"])).toBe(true);
  expect(roleAllows("admin", ["ADMIN"])).toBe(true);
  expect(roleAllows("edit", ["APPROVER"])).toBe(false);
  expect(roleAllows("admin", ["EDITOR"])).toBe(false);
});
