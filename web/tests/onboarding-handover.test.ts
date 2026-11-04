import { expect, it, vi } from "vitest";
import { assistantEntryUrl, publishedTemplateApplication } from "../shared/onboarding";

it("CR12：模板编码与目标应用不同，只使用服务端真实应用身份形成入口", async () => {
  const api = vi.fn(async (path: string) => {
    if (path === "/admin/onboarding-templates/tpl_orders") return { status: "PUBLISHED", latest: { appCode: "orders_assistant" } };
    if (path === "/admin/apps/orders_assistant") return { code: "orders_assistant", status: "PUBLISHED", currentVersionNo: 3 };
    throw Error("不应按模板编码查询应用");
  });
  const app = await publishedTemplateApplication(api, "tpl_orders");
  expect(app).toEqual({ appCode: "orders_assistant", appVersion: 3 });
  expect(assistantEntryUrl(app!.appCode, "https://portal.example")).toBe("https://portal.example/w/orders_assistant");
});
it("CR12：模板PUBLISHED不能替代应用发布、停用和身份核验", async () => {
  for (const app of [
    { code: "orders", status: "DRAFT", currentVersionNo: null },
    { code: "orders", status: "DISABLED", currentVersionNo: 2 },
    { code: "other", status: "PUBLISHED", currentVersionNo: 2 },
  ]) {
    const api = vi.fn(async (path: string) => path.includes("onboarding-templates")
      ? { status: "PUBLISHED", latest: { appCode: "orders" } } : app);
    expect(await publishedTemplateApplication(api, "tpl_orders")).toBeUndefined();
  }
});
it("CR12：未发布模板不读取应用，授权错误保持失败可见", async () => {
  const api = vi.fn(async () => ({ status: "DRAFT", latest: { appCode: "orders" } }));
  expect(await publishedTemplateApplication(api, "tpl_orders")).toBeUndefined();
  expect(api).toHaveBeenCalledTimes(1);
  await expect(publishedTemplateApplication(async () => { throw Error("当前账号无权读取"); }, "tpl_orders")).rejects.toThrow(/无权/);
});
