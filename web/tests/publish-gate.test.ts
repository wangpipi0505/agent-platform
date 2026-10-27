import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { compileScript, parse } from "@vue/compiler-sfc";
import ts from "typescript";
import * as Vue from "vue";
import { describe, expect, it, vi } from "vitest";
import * as gate from "../admin/src/publish-gate";
import type { GateRejection, PublishPrecheck } from "../admin/src/publish-gate";

const shaA = "a".repeat(64);
const shaB = "b".repeat(64);
const HOUR = 3_600_000;

function rejection(
  message = "缺少满足安全100%、通过率及无新增回归的候选报告",
  overrides: { status?: number; code?: string; message?: string; traceId?: string } = {},
): GateRejection {
  const e = Object.assign(new Error(message), {
    status: 409,
    code: "EVAL_GATE_BLOCKED" as const,
    traceId: "trace-1",
  }) as Error & GateRejection;
  return Object.assign(e, overrides);
}

describe("gate mode presentation", () => {
  it("renders the three gate modes as visually distinct states", () => {
    const blocking = gate.gateModeInfo(gate.normalizeGateMode("BLOCKING"));
    const advisory = gate.gateModeInfo(gate.normalizeGateMode("ADVISORY"));
    const off = gate.gateModeInfo(gate.normalizeGateMode("OFF"));
    expect(new Set([blocking.label, advisory.label, off.label]).size).toBe(3);
    expect(advisory.description).toContain("仅告警");
    expect(off.description).toContain("不评估评测结论");
    expect(blocking.tone).toBe("danger");
    expect(advisory.tone).toBe("warning");
    expect(off.tone).toBe("info");
  });

  it("falls back to BLOCKING presentation for unknown or missing mode values", () => {
    expect(gate.normalizeGateMode("STRICT")).toBe("BLOCKING");
    expect(gate.normalizeGateMode(undefined)).toBe("BLOCKING");
    expect(gate.normalizeGateMode(null)).toBe("BLOCKING");
    expect(gate.gateModeInfo(gate.normalizeGateMode("ADVISORY; drop table"))).toEqual(
      gate.gateModeInfo("BLOCKING"),
    );
  });
});

describe("summarizeGateState", () => {
  const basePrecheck: PublishPrecheck = {
    precheckId: "pc_1",
    draftRev: 6,
    candidateSha256: shaB,
    evalReportId: "",
    passed: true,
    result: "PASS",
    gateMode: "BLOCKING",
    waiverId: null,
    report: { checks: [], warnings: [] },
  };

  it("shows the gate check and mode for a passing BLOCKING precheck", () => {
    const view = gate.summarizeGateState({
      precheck: {
        ...basePrecheck,
        report: { checks: [{ group: "评测门禁", result: "PASS" }], warnings: [] },
      },
      isAdmin: true,
    });
    expect(view.blocked).toBeNull();
    expect(view.mode).toBe("BLOCKING");
    expect(view.modeInfo?.label).toBe("阻断");
    expect(view.gateCheck?.result).toBe("PASS");
    expect(view.waiver).toBeNull();
    expect(view.canApplyWaiver).toBe(false);
  });

  it("surfaces backend warnings for an ADVISORY precheck and keeps it distinct", () => {
    const view = gate.summarizeGateState({
      precheck: {
        ...basePrecheck,
        gateMode: "ADVISORY",
        report: {
          checks: [{ group: "评测门禁", result: "ADVISORY" }],
          warnings: ["评测门禁：缺少满足安全100%、通过率及无新增回归的候选报告（ADVISORY 仅告警，发布风险由发布人承担）"],
        },
      },
      isAdmin: false,
    });
    expect(view.mode).toBe("ADVISORY");
    expect(view.modeInfo?.label).not.toBe("阻断");
    expect(view.warnings.join()).toContain("仅告警");
    expect(view.blocked).toBeNull();
    expect(view.failing).toHaveLength(0);
  });

  it("describes an OFF gate as not evaluating evaluation conclusions", () => {
    const view = gate.summarizeGateState({
      precheck: {
        ...basePrecheck,
        gateMode: "OFF",
        report: { checks: [{ group: "评测门禁", result: "SKIPPED" }], warnings: ["评测门禁：已由 gate-mode=OFF 关闭，发布不带评测结论"] },
      },
      isAdmin: true,
    });
    expect(view.mode).toBe("OFF");
    expect(view.modeInfo?.description).toContain("不评估评测结论");
    expect(view.gateCheck?.result).toBe("SKIPPED");
  });

  it("marks a WAIVED gate as waived, never as passed, with reason and expiry", () => {
    const now = 1_700_000_000_000;
    const view = gate.summarizeGateState({
      precheck: {
        ...basePrecheck,
        gateMode: "BLOCKING",
        waiverId: "wv_1",
        report: {
          checks: [
            {
              group: "评测门禁",
              result: "WAIVED",
              detail: "ADMIN 显式豁免：评测断言修复上线前临时放行",
              waiverId: "wv_1",
              expiresAt: new Date(now + 2 * HOUR).toISOString(),
            },
          ],
          warnings: ["评测门禁：本次发布使用 ADMIN 豁免 wv_1"],
        },
      },
      isAdmin: true,
      now,
    });
    expect(view.gateCheck?.result).toBe("WAIVED");
    expect(view.waiver?.id).toBe("wv_1");
    expect(view.waiver?.reason).toContain("临时放行");
    expect(view.waiver?.remainingMs).toBeGreaterThan(0);
    expect(view.waiver?.remainingText).toContain("剩余");
  });

  it("explains a 409 EVAL_GATE_BLOCKED rejection with code, message and who may waive", () => {
    const view = gate.summarizeGateState({
      rejection: rejection(),
      isAdmin: true,
      now: 0,
    });
    expect(view.blocked?.code).toBe("EVAL_GATE_BLOCKED");
    expect(view.blocked?.message).toContain("缺少满足");
    expect(view.blocked?.traceId).toBe("trace-1");
    expect(view.canApplyWaiver).toBe(true);
    expect(view.applyHint).toContain("仅 ADMIN");
    expect(view.applyHint).toContain("24 小时");
  });

  it("shows a non-operable hint to non-ADMIN accounts instead of a waiver form", () => {
    const view = gate.summarizeGateState({ rejection: rejection(), isAdmin: false, now: 0 });
    expect(view.canApplyWaiver).toBe(false);
    expect(view.applyHint).toContain("仅 ADMIN 可申请豁免");
    expect(view.applyHint).toContain("无权");
  });

  it("lists failed checks for a 200 blocked precheck and denies waiver applicability", () => {
    const view = gate.summarizeGateState({
      precheck: {
        ...basePrecheck,
        passed: false,
        result: "BLOCKED",
        report: {
          checks: [
            { group: "结构", result: "ERROR", detail: "节点 x 缺少必填输出" },
            { group: "评测门禁", result: "PASS" },
          ],
          warnings: [],
        },
      },
      isAdmin: true,
    });
    expect(view.blocked).toBeNull();
    expect(view.failing.map((c) => c.group)).toEqual(["结构"]);
    expect(view.applyHint).toContain("不适用");
    expect(view.canApplyWaiver).toBe(false);
  });

  it("flags a session waiver whose binding no longer matches the current candidate", () => {
    const view = gate.summarizeGateState({
      rejection: rejection(),
      sessionWaiver: {
        id: "wv_old",
        appCode: "app_a",
        draftRev: 5,
        candidateSha256: shaA,
        reason: "路线一验证临时放行",
        approvedBy: "admin-1",
        approvedAt: new Date(0).toISOString(),
        expiresAt: new Date(HOUR).toISOString(),
        revokedAt: null,
        status: "ACTIVE",
      },
      candidate: { draftRev: 6, candidateSha256: shaB },
      isAdmin: true,
      now: 0,
    });
    expect(view.waiver?.bindingMismatch).toBe(true);
  });

  it("reports a revoked session waiver and no remaining window", () => {
    const view = gate.summarizeGateState({
      sessionWaiver: {
        id: "wv_r",
        appCode: "app_a",
        draftRev: 6,
        candidateSha256: shaB,
        reason: "路线一验证临时放行",
        approvedBy: "admin-1",
        approvedAt: new Date(0).toISOString(),
        expiresAt: new Date(10 * HOUR).toISOString(),
        revokedAt: new Date(HOUR).toISOString(),
        status: "REVOKED",
      },
      isAdmin: true,
      now: 2 * HOUR,
    });
    expect(view.waiver?.status).toBe("REVOKED");
    expect(view.waiver?.remainingMs).toBe(0);
  });

  it("detects a waiver-invalid rejection so the UI can ask for re-grant", () => {
    const view = gate.summarizeGateState({
      rejection: rejection("发布豁免已撤销或过期，请重新预检或重新申请豁免"),
      isAdmin: true,
      now: 0,
    });
    expect(view.waiverInvalid).toBe(true);
    expect(view.applyHint).toContain("重新申请");
  });
});

describe("waiver payload validation", () => {
  it("builds a payload bound to draftRev and candidateSha256 with an ISO expiry inside the TTL", () => {
    const now = 1_700_000_000_000;
    const payload = gate.buildWaiverPayload(
      {
        reason: "  路线一验证：评测断言修复上线前临时放行  ",
        hours: 24,
        draftRev: 6,
        candidateSha256: shaB,
      },
      now,
    );
    expect(payload.reason).toBe("路线一验证：评测断言修复上线前临时放行");
    expect(payload.draftRev).toBe(6);
    expect(payload.candidateSha256).toBe(shaB);
    expect(Date.parse(payload.expiresAt)).toBe(now + 24 * HOUR);
  });

  it("rejects reason length outside 10..512, hours outside 1..24, bad sha and bad rev", () => {
    const valid = { reason: "足够长的豁免理由哦", hours: 1, draftRev: 1, candidateSha256: shaA };
    expect(() => gate.buildWaiverPayload({ ...valid, reason: "太短" }, 0)).toThrow();
    expect(() => gate.buildWaiverPayload({ ...valid, reason: "x".repeat(513) }, 0)).toThrow();
    expect(() => gate.buildWaiverPayload({ ...valid, hours: 0 }, 0)).toThrow();
    expect(() => gate.buildWaiverPayload({ ...valid, hours: 25 }, 0)).toThrow();
    expect(() => gate.buildWaiverPayload({ ...valid, hours: 1.5 }, 0)).toThrow();
    expect(() => gate.buildWaiverPayload({ ...valid, candidateSha256: "NOTAHASH" }, 0)).toThrow();
    expect(() => gate.buildWaiverPayload({ ...valid, candidateSha256: shaA.toUpperCase() }, 0)).toThrow();
    expect(() => gate.buildWaiverPayload({ ...valid, draftRev: 0 }, 0)).toThrow();
  });
});

describe("waiver remaining formatting", () => {
  it("formats remaining time and expires gracefully", () => {
    const now = 1_700_000_000_000;
    expect(gate.formatWaiverRemaining(new Date(now + 2 * HOUR + 60_000).toISOString(), now)).toBe(
      "剩余 2 小时 1 分",
    );
    expect(gate.formatWaiverRemaining(new Date(now + 90_000).toISOString(), now)).toBe("剩余 1 分");
    expect(gate.formatWaiverRemaining(new Date(now - 1).toISOString(), now)).toBe("已到期");
    expect(gate.formatWaiverRemaining("not-a-time", now)).toBe("有效期未知");
  });
});

describe("isGateRejection", () => {
  it("accepts only 409 EVAL_GATE_BLOCKED shaped errors", () => {
    expect(gate.isGateRejection(rejection())).toBe(true);
    expect(gate.isGateRejection(rejection("草稿已变更，请重新预检", { status: 412, code: "CANDIDATE_CHANGED" }))).toBe(false);
    expect(gate.isGateRejection(rejection("x", { code: "IDEMPOTENCY_CONFLICT" }))).toBe(false);
    expect(gate.isGateRejection(new Error("网络中断"))).toBe(false);
    expect(gate.isGateRejection(undefined)).toBe(false);
  });
});

describe("AppPublishPanel gate actions", () => {
  type Api = (path: string, method?: string, body?: unknown) => Promise<unknown>;
  async function flush() {
    await Promise.resolve();
    await Vue.nextTick();
    await Promise.resolve();
  }
  function mount(options: { api: Api; isAdmin?: boolean; gateBlocked?: GateRejection }) {
    const { descriptor } = parse(
      readFileSync(new URL("../admin/src/pages/workspace/AppPublishPanel.vue", import.meta.url), "utf8"),
    );
    // 面板既有的 webappBase 逻辑引用 import.meta.env；CJS 化后 new Function 不支持，
    // 测试挂载器替换为等价空 env（该逻辑不在本卡被测范围内）。
    const raw = compileScript(descriptor, { id: "panel" }).content.replace(/import\.meta/g, "({env:{}})");
    const code = ts.transpileModule(raw, {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
    }).outputText;
    const auth = Vue.reactive({ token: "token-one", roles: ["ADMIN"], can: () => true });
    const exports: Record<string, unknown> = {};
    const require = createRequire(import.meta.url);
    const app = {
      code: "app_a",
      type: "CHAT",
      draftRev: 6,
      candidateSha256: shaB,
      config: {},
      definition: {},
    };
    new Function("require", "exports", code)((id: string) => {
      if (id === "vue") return Vue;
      if (id.endsWith("/api")) return { useApi: () => options.api, useAuth: () => auth };
      if (id.endsWith("/publish-gate")) return gate;
      if (id.endsWith(".vue")) return {};
      return require(id);
    }, exports);
    (exports.default as { render: () => void }).render = () => null;
    const renderer = Vue.createRenderer<unknown, unknown>({
      createComment: () => ({}),
      insert() {},
      remove() {},
      parentNode: () => null,
      nextSibling: () => null,
      createElement: () => ({}),
      createText: () => ({}),
      setText() {},
      setElementText() {},
      patchProp() {},
    });
    const checked = vi.fn();
    const props = Vue.reactive({
      app,
      code: "app_a",
      busy: false,
      precheck: null,
      precheckStale: false,
      published: null,
      evaluation: null,
      evaluationWorking: false,
      evaluationNeedsCheck: false,
      gateBlocked: options.gateBlocked ?? null,
      isAdmin: options.isAdmin ?? true,
      onCheck: checked,
    });
    const vm = renderer.createApp({ render: () => Vue.h(exports.default as never, props) });
    const instance = vm.mount({}).$ as unknown as {
      subTree: { component: { setupState: Record<string, any> } };
    };
    const state = instance.subTree.component.setupState;
    return { state, checked, props, unmount: () => vm.unmount() };
  }

  it("submits a waiver bound to the current candidate and re-runs the precheck", async () => {
    const waiverView = {
      id: "wv_new",
      appCode: "app_a",
      draftRev: 6,
      candidateSha256: shaB,
      reason: "路线一验证：评测断言修复上线前临时放行",
      approvedBy: "admin-1",
      approvedAt: new Date(0).toISOString(),
      expiresAt: new Date(HOUR).toISOString(),
      revokedAt: null,
      status: "ACTIVE",
    };
    const calls: Array<{ path: string; method?: string; body?: unknown }> = [];
    const v = mount({
      api: async (path, method, body) => {
        calls.push({ path, method, body });
        if (method === "POST" && path.endsWith("/publish-waivers")) return waiverView;
        throw new Error("unexpected " + path);
      },
      gateBlocked: rejection(),
    });
    vi.stubGlobal("confirm", () => true);
    v.state.waiverForm.reason = "路线一验证：评测断言修复上线前临时放行";
    v.state.waiverForm.hours = 2;
    await v.state.applyWaiver();
    await flush();
    const call = calls.find((c) => c.method === "POST");
    expect(call?.path).toBe("/admin/apps/app_a/publish-waivers");
    expect((call?.body as Record<string, unknown>).candidateSha256).toBe(shaB);
    expect((call?.body as Record<string, unknown>).draftRev).toBe(6);
    expect(Number.isFinite(Date.parse((call?.body as Record<string, unknown>).expiresAt as string))).toBe(true);
    expect(v.state.sessionWaiver).toBeTruthy();
    expect(v.state.waiverError).toBe("");
    expect(v.checked).toHaveBeenCalled();
    vi.unstubAllGlobals();
  });

  it("keeps waiver failures recoverable: local validation and server errors both allow retry", async () => {
    let attempts = 0;
    const v = mount({
      api: async (path, method) => {
        if (method === "POST" && path.endsWith("/publish-waivers")) {
          attempts++;
          if (attempts === 1) {
            throw Object.assign(new Error("当前账号没有此操作权限，请联系管理员。"), {
              status: 403,
              code: "PUBLISH_WAIVER_DISABLED",
            });
          }
          return { id: "wv_ok", status: "ACTIVE", expiresAt: new Date(HOUR).toISOString() };
        }
        throw new Error("unexpected " + path);
      },
      gateBlocked: rejection(),
    });
    vi.stubGlobal("confirm", () => true);
    // 第一阶段：形状错误在本地预校验就被拒，不发请求（服务端规则的前端镜像）。
    v.state.waiverForm.reason = "太短";
    await v.state.applyWaiver();
    await flush();
    expect(attempts).toBe(0);
    expect(String(v.state.waiverError)).toContain("豁免理由需10至512字");
    expect(v.state.sessionWaiver).toBeFalsy();
    expect(v.checked).not.toHaveBeenCalled();
    // 第二阶段：服务端拒绝（豁免开关关闭），错误原样展示且表单可重试。
    v.state.waiverForm.reason = "修复后的完整豁免理由，用于重试";
    await v.state.applyWaiver();
    await flush();
    expect(attempts).toBe(1);
    expect(String(v.state.waiverError)).toContain("当前账号没有此操作权限");
    expect(v.state.sessionWaiver).toBeFalsy();
    // 第三阶段：同一表单直接重试成功，错误清除并触发重新预检。
    await v.state.applyWaiver();
    await flush();
    expect(attempts).toBe(2);
    expect(v.state.waiverError).toBe("");
    expect(v.state.sessionWaiver).toBeTruthy();
    expect(v.checked).toHaveBeenCalled();
    vi.unstubAllGlobals();
  });

  it("refuses waiver submission for non-ADMIN accounts without touching the API", async () => {
    const api = vi.fn(async () => {
      throw new Error("must not be called");
    }) as unknown as Api & { mock: { calls: unknown[][] } };
    const v = mount({ api, isAdmin: false, gateBlocked: rejection() });
    v.state.waiverForm.reason = "足够长的豁免理由";
    await v.state.applyWaiver();
    await flush();
    expect(api.mock.calls).toHaveLength(0);
    expect(String(v.state.waiverError)).toContain("仅 ADMIN");
  });

  it("revokes the session waiver via DELETE and re-runs the precheck", async () => {
    const waiverView = {
      id: "wv_new",
      appCode: "app_a",
      draftRev: 6,
      candidateSha256: shaB,
      reason: "路线一验证：评测断言修复上线前临时放行",
      approvedBy: "admin-1",
      approvedAt: new Date(0).toISOString(),
      expiresAt: new Date(HOUR).toISOString(),
      revokedAt: null,
      status: "ACTIVE",
    };
    const revoked = { ...waiverView, revokedAt: new Date(HOUR).toISOString(), status: "REVOKED" };
    const calls: Array<{ path: string; method?: string }> = [];
    const v = mount({
      api: async (path, method) => {
        calls.push({ path, method });
        if (method === "DELETE" && path.endsWith("/publish-waivers/wv_new")) return revoked;
        throw new Error("unexpected " + path);
      },
      gateBlocked: rejection(),
    });
    vi.stubGlobal("confirm", () => true);
    v.state.sessionWaiver = waiverView;
    await flush();
    await v.state.revokeWaiver();
    await flush();
    expect(calls.some((c) => c.method === "DELETE" && c.path === "/admin/apps/app_a/publish-waivers/wv_new")).toBe(true);
    expect((v.state.sessionWaiver as { status: string }).status).toBe("REVOKED");
    expect(v.checked).toHaveBeenCalled();
    vi.unstubAllGlobals();
  });

  it("does not revoke when the confirmation dialog is dismissed", async () => {
    const api = vi.fn(async () => ({})) as unknown as Api & { mock: { calls: unknown[][] } };
    const v = mount({ api, gateBlocked: rejection() });
    v.state.sessionWaiver = {
      id: "wv_new",
      appCode: "app_a",
      draftRev: 6,
      candidateSha256: shaB,
      reason: "路线一验证：评测断言修复上线前临时放行",
      approvedBy: "admin-1",
      approvedAt: new Date(0).toISOString(),
      expiresAt: new Date(HOUR).toISOString(),
      revokedAt: null,
      status: "ACTIVE",
    };
    await flush();
    vi.stubGlobal("confirm", () => false);
    await v.state.revokeWaiver();
    await flush();
    expect(api.mock.calls).toHaveLength(0);
    vi.unstubAllGlobals();
  });
});
