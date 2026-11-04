import { expect, it } from "vitest";
import { experiencePath, experienceProjection } from "../assistant.js/src/experience";

const reply = { code: "OK", data: { schemaVersion: "v1", appCode: "orders", configReleaseRef: { configReleaseId: "cr1" }, welcomeText: "Hello" } };
it("CR07：体验只给本应用且保留服务端配置引用", () => {
  expect(experienceProjection(200, reply, "orders")?.configReleaseId).toBe("cr1");
  expect(experienceProjection(200, reply, "other")).toBeNull();
  expect(experienceProjection(200, reply, "orders", "cr1")?.description.welcomeText).toBe("Hello");
  expect(() => experienceProjection(200, reply, "orders", "old")).toThrow(/固定版本/);
});
it("CR07：401和403不能伪装成正常无体验回退", () => {
  for (const status of [401, 403]) expect(() => experienceProjection(status, { code: "DENIED", message: "无权" }, "orders")).toThrow(/无权/);
  expect(experienceProjection(409, { code: "CONFIG_RELEASE_NOT_ACTIVE" }, "orders")).toBeNull();
});
it("CR07：环境仅通过合法选择器传递，不能注入路径或改应用", () => {
  expect(experiencePath("orders", "staging")).toBe("/v1/apps/orders/experience?environment=staging");
  expect(experiencePath("orders")).toBe("/v1/apps/orders/experience");
  expect(() => experiencePath("orders", "production&tenant=other")).toThrow(/格式/);
});
