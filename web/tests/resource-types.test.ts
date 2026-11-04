// MNT-03（onboarding DTO 族子步）：稳定外壳与边界收窄——未知网络输入不用 as 解析；
// 动态值保持 unknown；false/0/null/数组根语义不被改写。
import { describe, expect, it } from "vitest";
import {
  asUnknownRecord,
  isPlainValue,
  parseConnectionProfileList,
  parseConnectionProfileView,
  parsePreflightPreview,
} from "../shared/resource-types";

describe("连接档案边界收窄", () => {
  it("非法形状返回 null/空数组；多余键不进入类型面", () => {
    expect(parseConnectionProfileView(null)).toBeNull();
    expect(parseConnectionProfileView("x")).toBeNull();
    expect(parseConnectionProfileView({})).toBeNull();
    expect(parseConnectionProfileList("not-array")).toEqual([]);
    const row = parseConnectionProfileView({
      profileId: 7,
      name: "核心库",
      bizSystemCode: "B01",
      environment: "PROD",
      status: "APPROVED",
      secretValue: "should-not-leak",
      nested: { deep: true },
    });
    expect(row).toEqual({
      profileId: "7",
      name: "核心库",
      bizSystemCode: "B01",
      environment: "PROD",
      status: "APPROVED",
    });
    expect(JSON.stringify(row)).not.toContain("secretValue");
  });

  it("列表解析逐项收窄并丢弃坏行", () => {
    const rows = parseConnectionProfileList([
      { profileId: "a", status: "APPROVED" },
      { nope: 1 },
      null,
      { profileId: 9, status: "ACTIVE" },
    ]);
    expect(rows.map((row) => row.profileId)).toEqual(["a", "9"]);
  });
});

describe("诊断结果边界收窄", () => {
  it("非字符串 diagnostics 原样字符串化；supported 严格布尔", () => {
    const preview = parsePreflightPreview({
      operations: [
        { operationId: "op1", method: "get", supported: true, diagnostics: ["文本", { code: "X" }] },
        { operationId: "op2", supported: "yes" },
        { nope: true },
      ],
      warnings: [42, "预算提示"],
      documentSha256: "ab".repeat(32),
    });
    expect(preview.operations.length).toBe(2);
    expect(preview.operations[0].method).toBe("GET");
    expect(preview.operations[0].reasons).toEqual(["文本", '{"code":"X"}']);
    expect(preview.operations[1].supported).toBe(false);
    expect(preview.warnings).toEqual(["42", "预算提示"]);
  });

  it("完全未知输入给空壳不抛错", () => {
    const preview = parsePreflightPreview(undefined);
    expect(preview).toEqual({ operations: [], warnings: [], documentSha256: "" });
  });
});

describe("动态值保持 unknown 受校验", () => {
  it("asUnknownRecord 仅收窄顶层形状；非对象给空对象", () => {
    expect(asUnknownRecord({ total: 3, flags: [false, 0, null] })).toEqual({
      total: 3,
      flags: [false, 0, null],
    });
    expect(asUnknownRecord([1, 2])).toEqual({});
    expect(asUnknownRecord(null)).toEqual({});
  });

  it("isPlainValue：false/0/null 是合法标量；数组/对象不是", () => {
    expect(isPlainValue(false)).toBe(true);
    expect(isPlainValue(0)).toBe(true);
    expect(isPlainValue(null)).toBe(true);
    expect(isPlainValue("")).toBe(true);
    expect(isPlainValue([])).toBe(false);
    expect(isPlainValue({})).toBe(false);
  });
});

/* ===================== MNT-03（onboarding 之外 DTO 族） ===================== */
import {
  asRecordField,

  emptyAppDetail,
  emptyRunStatus,
  parseAppDetail,
  parseEvaluationSnapshot,
  parsePublishedPointer,
  parseResourceVersion,
  parseRunNodes,
  parseRunStatusEnvelope,
  parseRunStatusSnapshot,
} from "../shared/resource-types";

describe("MNT-03 应用/发布外壳", () => {
  it("parseAppDetail：appCode 缺失返回 null；tags 非数组不臆造；动态字段保持 unknown", () => {
    expect(parseAppDetail({ nope: 1 })).toBeNull();
    const detail = parseAppDetail({
      appCode: "lookup", name: "查找", type: "FLOW", status: "DRAFT", draftRev: 3,
      currentVersionNo: 2, tags: "not-array", inputs: { schema: true }, config: { k: 1 },
    });
    expect(detail?.tags).toEqual([]);
    expect(detail?.draftRev).toBe(3);
    expect(detail?.currentVersionNo).toBe(2);
    expect((detail?.inputs as { schema: boolean }).schema).toBe(true);
  });

  it("空壳工厂：全零值不臆造运行事实", () => {
    expect(emptyAppDetail().draftRev).toBe(0);
    expect(emptyAppDetail().currentVersionNo).toBeNull();
    expect(emptyRunStatus().nodes).toEqual([]);
  });

  it("parseEvaluationSnapshot：evalReportId 必需；parsePublishedPointer：precheckId 必需", () => {
    expect(parseEvaluationSnapshot({ status: "ACCEPTED" })).toBeNull();
    expect(parseEvaluationSnapshot({ evalReportId: "r1", status: "ACCEPTED", draftRev: 2 })?.draftRev).toBe(2);
    expect(parsePublishedPointer({ nope: 1 })).toBeNull();
    expect(parsePublishedPointer({ precheckId: "p1", versionNo: 4 })?.versionNo).toBe(4);
  });
});

describe("MNT-03 运行状态外壳（协议兼容）", () => {
  it("parseRunStatusEnvelope：信封未知键与 data 未知键原样保留", () => {
    const envelope = parseRunStatusEnvelope({
      code: "OK", traceId: "t1", rawData: "kept", data: { status: "RUNNING", eventsWatermark: "5", chunkHint: "keep" },
    });
    expect((envelope as unknown as Record<string, unknown>).rawData).toBe("kept");
    expect((envelope.data as unknown as Record<string, unknown>).chunkHint).toBe("keep");
    expect(envelope.data.eventsWatermark).toBe("5");
  });

  it("无 events 键时不创建空数组（Object.hasOwn 语义由服务端事实决定）", () => {
    const data = parseRunStatusSnapshot({ status: "RUNNING" }) as unknown as Record<string, unknown>;
    expect(Object.hasOwn(data, "events")).toBe(false);
    const withEvents = parseRunStatusSnapshot({ status: "RUNNING", events: [1] }) as unknown as Record<string, unknown>;
    expect(Object.hasOwn(withEvents, "events")).toBe(true);
  });

  it("parseRunNodes：nodeDefId 回退 id；双双缺失丢弃；时间线字段透传", () => {
    const rows = parseRunNodes([
      { id: "n1", status: "SUCCEEDED", started_at: "2026-11-04T00:00:00Z" },
      { nodeDefId: "n2", status: "FAILED", error: { message: "boom" } },
      { status: "RUNNING" },
      null,
    ]);
    expect(rows.length).toBe(2);
    expect(rows[0].nodeDefId).toBe("n1");
    expect(rows[1].started_at).toBeUndefined();
    expect((rows[1].error as { message: string }).message).toBe("boom");
  });

  it("错信封按原语义保留（data null 不伪装成功）", () => {
    const envelope = parseRunStatusEnvelope({ code: "AUTH", message: "denied", data: null });
    expect(envelope.code).toBe("AUTH");
    expect(envelope.message).toBe("denied");
    expect(envelope.data.status).toBe("");
  });
});

describe("MNT-03 资源版本行外壳", () => {
  it("versionNo 必需；version/平铺合同字段透传", () => {
    expect(parseResourceVersion({ version: 3, status: "DRAFT" })).toBeNull();
    const row = parseResourceVersion({
      versionNo: 2, version: 2, status: "PUBLISHED", inputSchema: { type: "object" },
      execution: { readonly: true }, guard: { roles: [] },
    });
    expect(row?.version).toBe(2);
    expect(row?.execution).toEqual({ readonly: true });
  });
});

describe("unknown 收窄辅助", () => {
  it("asRecordField 非对象安全取键；asUnknownRecord 数组给空对象", () => {
    expect(asRecordField({ a: 1 }, "a")).toBe(1);
    expect(asRecordField(null, "a")).toBeUndefined();
    expect(asRecordField("x", "a")).toBeUndefined();
    expect(asUnknownRecord([1])).toEqual({});
  });
});
