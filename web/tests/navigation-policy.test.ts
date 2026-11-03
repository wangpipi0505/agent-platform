// MNT-02子步1：路由访问单一来源（page-access.ts）与未知路径404。
// 锁定：①14主入口+参数页同源登记（标题/区域/读条件/写上下文）；②读条件本轮统一为
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

it("单一来源：14个主入口与全部页面登记齐全且字段完整", () => {
  const menu = menuEntries();
  expect(menu.length).toBe(14);
  expect(new Set(menu.map((e) => e.path)).size).toBe(14);
  for (const entry of pageAccess) {
    expect(entry.title.length, entry.key).toBeGreaterThan(0);
    expect(entry.area.length, entry.key).toBeGreaterThan(0);
    expect(["session", "edit", "approve", "admin"], entry.key).toContain(entry.read);
  }
  expect(pageByKey["apps"].menu).toBe(true);
  expect(pageByKey["run-detail"].menu).toBe(false);
  expect(pageByKey["run-detail"].route).toBe("/runs/:runId");
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
  // 菜单同源：AppShell 从 router 派生 navMenus，不再手写14入口数组
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
