// MNT-02子步2：未保存离开保护四路径（dirty计算/取消/放弃/安全清除）。
// resolveLeaveAction 为纯函数直测；页面接线以源码静态断言锁定（安全清除读实时 auth，
// 基线以最近一次服务端返回为准；保存后基线复位）。
import { expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolveLeaveAction } from "../shared/useUnsavedChanges";

it("dirty计算：无未保存更改直接放行，不走确认", () => {
  expect(
    resolveLeaveAction({ dirty: false, security: false, confirmed: false }),
  ).toBe("pass");
});

it("取消：确认框取消留在本页（block），不丢弃", () => {
  expect(
    resolveLeaveAction({ dirty: true, security: false, confirmed: false }),
  ).toBe("block");
});

it("放弃：确认离开则丢弃更改放行（不写库由页面保证——本层只放行并回调onReset）", () => {
  expect(
    resolveLeaveAction({ dirty: true, security: false, confirmed: true }),
  ).toBe("discard-pass");
});

it("安全清除：登录失效/撤权/退出/身份切换直接放行，不被离开确认阻挡", () => {
  expect(
    resolveLeaveAction({ dirty: true, security: true, confirmed: false }),
  ).toBe("discard-pass");
});

it("页面接线：基线取服务端返回、安全键接实时auth、保存/载入后复位", () => {
  const workspace = readFileSync(
    new URL("../admin/src/pages/AppWorkspacePage.vue", import.meta.url),
    "utf8",
  );
  expect(workspace).toContain("useUnsavedChanges({");
  expect(workspace).toContain("configBaseline.value = JSON.stringify(app.value");
  expect(workspace).toContain("securityKey: () => auth.token ?? undefined");
  const detail = readFileSync(
    new URL("../admin/src/ResourceDetail.vue", import.meta.url),
    "utf8",
  );
  expect(detail).toContain("useUnsavedChanges({");
  expect(detail).toContain("securityKey: () => auth.token ?? undefined");
  expect(detail.match(/contentBaseline\.value = /g)?.length).toBeGreaterThanOrEqual(2);
});
