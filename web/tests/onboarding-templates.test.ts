// ONB-01：业务接入模板登记——契约对齐校验、被授予版本选择、服务端缺口不伪造可用。
// 纯函数直测 + 页面接线静态断言（路由/权限单一来源）。
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import {
  applyPackageSelection,
  buildOnboardingTemplatePayload,
  emptyOnboardingTemplateDraft,
  onboardingClient,
  ONBOARDING_TEMPLATE_KINDS,
  selectablePackages,
  TEMPLATE_WRITE_SCOPE,
  validateOnboardingTemplateDraft,
} from "../shared/onboarding";

const draft = (overrides: Partial<ReturnType<typeof emptyOnboardingTemplateDraft>> = {}) => ({
  ...emptyOnboardingTemplateDraft(),
  code: "lookup_query",
  name: "查找模板",
  kind: "QUERY" as const,
  sourcePackageVersionId: 42,
  sourcePackageSha256: "a".repeat(64),
  appCode: "lookup_app",
  experience: { appCode: "lookup_app", experienceRev: 2 },
  integration: { bizSystemCode: "BIZ01", environment: "PROD", mode: "EMBED" },
  ...overrides,
});

describe("INT-01 契约对齐校验", () => {
  it("完整合法草稿零错误，payload 仅含契约字段", () => {
    expect(validateOnboardingTemplateDraft(draft())).toEqual([]);
    const payload = buildOnboardingTemplatePayload(draft());
    expect(Object.keys(payload).sort()).toEqual([
      "appCode",
      "code",
      "description",
      "experience",
      "integration",
      "kind",
      "name",
      "sourcePackageSha256",
      "sourcePackageVersionId",
    ]);
    // 引用字段与 INT-03 契约命名一致，不含凭据或脚本字段（description 误配用词边界排除）
    expect(JSON.stringify(payload)).not.toMatch(/\bsecret\b|\bcredential\b|\bscript\b|\bpassword\b/i);
  });

  it("非法编码、缺失来源、坏 SHA、坏引用逐项拒绝", () => {
    const messages = validateOnboardingTemplateDraft(
      draft({
        code: "Bad-Code",
        sourcePackageVersionId: null,
        sourcePackageSha256: "nope",
        appCode: "X",
        experience: { appCode: "", experienceRev: 0 },
        integration: { bizSystemCode: "", environment: "", mode: "" },
      }),
    );
    expect(messages.length).toBeGreaterThanOrEqual(7);
    expect(messages.some((m) => m.includes("模板编码"))).toBe(true);
    expect(messages.some((m) => m.includes("被授予的能力包"))).toBe(true);
    expect(messages.some((m) => m.includes("SHA256"))).toBe(true);
    expect(messages.some((m) => m.includes("appCode"))).toBe(true);
    expect(messages.some((m) => m.includes("体验修订号"))).toBe(true);
    expect(messages.some((m) => m.includes("业务系统编码"))).toBe(true);
  });

  it("未交付种类（文件分析/写入）不可登记并说明原因", () => {
    for (const item of ONBOARDING_TEMPLATE_KINDS) {
      if (item.kind === "QUERY") expect(item.selectable).toBe(true);
      else {
        expect(item.selectable).toBe(false);
        expect(item.reason).toBeTruthy();
      }
    }
    const blocked = validateOnboardingTemplateDraft(draft({ kind: "CONTROLLED_WRITE" }));
    expect(blocked.some((m) => m.includes("待确认机制齐备"))).toBe(true);
    const blocked2 = validateOnboardingTemplateDraft(draft({ kind: "FILE_ANALYSIS" }));
    expect(blocked2.some((m) => m.includes("INT-02"))).toBe(true);
  });
});

describe("被授予版本选择（能力包包装）", () => {
  const row = (packageVersionId: number, sha256 = "b".repeat(64)) => ({
    packageVersionId,
    packageId: "pkg",
    version: "1.0",
    sha256,
    softwareId: "sw",
    softwareVersion: null,
    bindingStatus: "UNRESOLVED",
    verificationStatus: "NOT_EVALUATED",
    applicationCount: 0,
    createdAt: "2026-11-04",
  }) as never;

  it("selectablePackages 过滤非法版本；applyPackageSelection 固定精确身份", () => {
    const rows = selectablePackages([row(1), row(-2), row(3)]);
    expect(rows.map((r) => r.packageVersionId)).toEqual([1, 3]);
    const picked = applyPackageSelection(emptyOnboardingTemplateDraft(), row(3, "c".repeat(64)));
    expect(picked.sourcePackageVersionId).toBe(3);
    expect(picked.sourcePackageSha256).toBe("c".repeat(64));
    expect(applyPackageSelection(emptyOnboardingTemplateDraft(), undefined).sourcePackageVersionId).toBeNull();
  });
});

describe("页面接线与不伪造可用", () => {
  const page = readFileSync(
    new URL("../admin/src/pages/onboarding/OnboardingTemplatesPage.vue", import.meta.url),
    "utf8",
  );

  it("ONB-04 解除硬禁用：登记走 DraftAssemblyStep 真实提交，目录读真实接口", () => {
    expect(page).not.toContain("模板服务端尚未交付");
    expect(page).not.toContain('提交入口暂未启用');
    // ONB-04：提交入口解除硬禁用（DraftAssemblyStep 接真实 create）；无模拟提交路径
    expect(page).toContain('<DraftAssemblyStep :draft="draft"');
    expect(page).toContain('<VerifyPublishStep />');
    expect(page).toContain('TEMPLATE_WRITE_SCOPE');
    expect(page).not.toContain('TEMPLATE_SERVICE_UNAVAILABLE');
    expect(page).not.toMatch(/mock|fixture/i);
  });

  it("向导引用既有能力包客户端，不建第二套目录引擎", () => {
    expect(page).toContain("useCapabilityPackageClient()");
    expect(page).toContain("client.list(");
    expect(page).toContain("applyPackageSelection");
  });

  it("路由与权限走 page-access 单一来源（MNT-02 产物）", () => {
    const router = readFileSync(new URL("../admin/src/router.ts", import.meta.url), "utf8");
    const access = readFileSync(new URL("../admin/src/page-access.ts", import.meta.url), "utf8");
    expect(router).toContain('path: "/onboarding/templates"');
    expect(router).toContain('access: "onboarding-templates"');
    expect(access).toContain(
      '{ key: "onboarding-templates", path: "/onboarding/templates", title: "接入模板", area: "平台管理", read: "session", write: "admin", menu: true }',
    );
  });

  it("ONB-04：服务端已交付——客户端走真实接口，requestId/expectedRev 约定对齐", () => {
    expect(TEMPLATE_WRITE_SCOPE).toContain("管理员");
    // 客户端路径与幂等约定
    const calls: { path: string; method?: string; body?: unknown }[] = [];
    const client = onboardingClient(async (path, method, body) => {
      calls.push({ path, method, body });
      return [];
    });
    void client.list({ q: "查找" });
    expect(calls[0].path).toBe("/admin/onboarding-templates?q=%E6%9F%A5%E6%89%BE");
    void client.create({
      code: "t1", name: "T", kind: "QUERY", description: "",
      sourcePackageVersionId: 1, sourcePackageSha256: "a".repeat(64),
      appCode: "app", experience: { appCode: "app", experienceRev: 1 },
      integration: { bizSystemCode: "B", environment: "PROD", mode: "EMBED" },
      requestId: "rid-1",
    });
    expect(calls[1]).toEqual({
      path: "/admin/onboarding-templates", method: "POST",
      body: expect.objectContaining({ requestId: "rid-1" }),
    });
    expect(JSON.stringify(calls[1].body)).not.toContain("expectedRev");
    void client.publish("t1", { expectedRev: 3, requestId: "rid-2" });
    expect(calls[2]).toEqual({
      path: "/admin/onboarding-templates/t1/publish", method: "POST",
      body: { expectedRev: 3, requestId: "rid-2" },
    });
    void client.preflight("t1", 2, { document: "doc", connection: "conn", requestId: "rid-3" });
    expect(calls[3].path).toBe("/admin/onboarding-templates/t1/versions/2/preflight");
  });
});
