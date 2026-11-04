// ONB-02：前置诊断与模板选择——支持子集动态统计（不写死51/16）、不支持不可选、
// 失败可恢复、服务端缺口硬禁用+登记。纯函数直测 + 组件源码断言。
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import {
  buildPreflightSummary,
  canUseOperation,
  grantedConnections,
  normalizePreview,
  summarizeDiagnosis,
} from "../shared/onboarding";

const previewResponse = {
  documentSha256: "d".repeat(64),
  operations: [
    { operationId: "op_get", method: "get", path: "/items", supported: true, requiresAuth: false, diagnostics: [] },
    { operationId: "op_oneof", method: "post", path: "/combo", supported: false, requiresAuth: true, diagnostics: ["inputSchema 不支持 oneOf 组合（字段: body#/oneOf）", "缺少请求示例"] },
    { operationId: "op_budget", method: "post", path: "/heavy", supported: false, requiresAuth: false, diagnostics: ["Schema 工作量超出当前预算上限"] },
  ],
  warnings: ["Schema 工作量 63 点，超出本轮预算 48 点——超限操作已标记为不支持"],
};

describe("诊断规范化与支持子集（R12：统计来自响应）", () => {
  const preview = normalizePreview(previewResponse);

  it("逐操作拒绝原因原样保留，方法归一为大写", () => {
    expect(preview.operations[1].method).toBe("POST");
    expect(preview.operations[1].reasons).toEqual([
      "inputSchema 不支持 oneOf 组合（字段: body#/oneOf）",
      "缺少请求示例",
    ]);
    expect(preview.warnings[0]).toContain("超出本轮预算 48 点");
    expect(preview.documentSha256).toBe("d".repeat(64));
  });

  it("支持子集/差距/部分失败全部动态统计，无写死数字", () => {
    const summary = summarizeDiagnosis(preview);
    expect(summary.total).toBe(3);
    expect(summary.supportedCount).toBe(1);
    expect(summary.partial).toBe(true);
    expect(summary.allBlocked).toBe(false);
    expect(summary.unsupported.map((op) => op.operationId)).toEqual(["op_oneof", "op_budget"]);
  });

  it("③不支持操作不可用；全不支持与空目录边界", () => {
    expect(canUseOperation(preview.operations[0])).toBe(true);
    expect(canUseOperation(preview.operations[1])).toBe(false);
    const empty = normalizePreview({ operations: [], warnings: [] });
    expect(summarizeDiagnosis(empty).allBlocked).toBe(false);
    const allBlocked = summarizeDiagnosis(
      normalizePreview({ operations: [previewResponse.operations[1]], warnings: [] }),
    );
    expect(allBlocked.allBlocked).toBe(true);
    expect(allBlocked.supportedCount).toBe(0);
  });

  it("诊断摘要可追溯：规格SHA+逐操作原因+零创建断言", () => {
    const summary = buildPreflightSummary(preview);
    expect(summary.documentSha256).toBe("d".repeat(64));
    expect(summary.zeroCreated).toBe(true);
    expect(JSON.stringify(summary)).toContain("oneOf");
  });
});

describe("被授予连接档案（①只列已批准）", () => {
  it("APPROVED/ACTIVE 才可入模板范围，其余状态收敛", () => {
    const rows = [
      { profileId: 1, status: "APPROVED", environment: "PROD" },
      { profileId: 2, status: "ACTIVE", environment: "STAGING" },
      { profileId: 3, status: "DRAFT" },
      { profileId: 4, status: "DISABLED" },
      { profileId: 5 },
    ] as never[];
    const granted = grantedConnections(rows);
    expect(granted.map((row) => row.profileId)).toEqual([1, 2]);
  });
});

describe("组件接线（源码断言）", () => {
  const step = readFileSync(
    new URL("../admin/src/pages/onboarding/ConnectionCheckStep.vue", import.meta.url),
    "utf8",
  );
  const page = readFileSync(
    new URL("../admin/src/pages/onboarding/OnboardingTemplatesPage.vue", import.meta.url),
    "utf8",
  );

  it("④诊断失败/部分失败可恢复不静默：错误保留+重试入口+原文不清", () => {
    expect(step).toContain("诊断失败：");
    expect(step).toContain("修正后重试；也可退回 OpenAPI 专业导入入口");
    expect(step).toContain('重试');
    // 部分失败醒目警示而非静默
    expect(step).toContain("存在不支持的操作");
    expect(step).toContain('type="warning"');
    // 文档变化时丢弃旧响应，不产生错配诊断
    expect(step).toContain("document.value === source");
  });

  it("②真实服务端诊断：复用预览接口，统计不写死", () => {
    expect(step).toContain('"/admin/skills/import-openapi/preview"');
    expect(step).toContain("支持子集 {{ summary?.supportedCount }} / 共 {{ summary?.total }} 个操作");
    expect(step).not.toMatch(/51|16个/);
    expect(step).toContain("不把业务限制转成改接口要求");
  });

  it("③不支持操作勾选禁用：checkbox disabled + toggle 守卫双保险", () => {
    expect(step).toContain(':disabled="!canUseOperation(row)"');
    expect(step).toContain("if (!usable) return;");
  });

  it("⑤服务端缺口沿用硬禁用+登记；只读探测零创建", () => {
    expect(step).toContain("PREFLIGHT_SERVICE_NOTE");
    expect(step).toContain("提交入口暂未启用");
    // 零创建断言在 shared 的诊断摘要构造器内；组件展示该摘要
    expect(step).toContain("buildPreflightSummary");
    // CR-05（5da4d115）起页面改为会话式向导：诊断步骤组件保留但不再由该页面挂载，
    // 接线断言对齐已交付装配（OnboardingSessionWorkspace + 草稿/发布/助手步骤）。
    expect(page).toContain("<OnboardingSessionWorkspace />");
    expect(page).toContain("<DraftAssemblyStep");
    expect(page).toContain("<VerifyPublishStep");
  });

  it("①连接档案只列被授予（grantedConnections 接线 + ADMIN 分支说明）", () => {
    expect(step).toContain("grantedConnections(");
    expect(step).toContain('"/admin/connection-profiles"');
    expect(step).toContain("连接档案目录需 ADMIN 查看");
  });
});
