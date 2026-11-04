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
