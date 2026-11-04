// ONB-03：业务字段表单与受限映射——类型保持收集、声明式映射阻断定位、
// 权威方言生成契约与切换丢弃提示。
import { describe, expect, it } from "vitest";
import {
  buildGeneratedContract,
  collectFieldValues,
  discardedOnSwitch,
  emptyFieldsBundle,
  evaluateMapping,
  validateFieldsBundle,
  type BusinessFieldDef,
  type FieldsBundle,
} from "../shared/onboarding-fields";

const fields: BusinessFieldDef[] = [
  { label: "关键字", param: "q", kind: "text", required: true },
  { label: "上限", param: "limit", kind: "number", unit: "条" },
  { label: "含归档", param: "archived", kind: "boolean" },
  { label: "排序", param: "order", kind: "enum", enumValues: ["asc", "desc"] },
  { label: "附件", param: "attach", kind: "file", required: true },
];
const bundle: FieldsBundle = {
  fields,
  mappings: { q: { kind: "direct", from: "q" }, limit: { kind: "constant", value: 10 } },
  returns: {
    summaryTemplate: "查询完成，共 {{ limit }} 条，附件 {{ attach }}。",
    cardTemplateEnabled: true,
    cardFields: [{ label: "上限", from: "limit" }],
    fileRefPaths: ["attach"],
  },
};

describe("类型保持收集（false/0/null/数组根不改值）", () => {
  it("布尔/数值/null 原类型提交；空串非必填省略；数组根原样", () => {
    const collected = collectFieldValues(fields, {
      q: "关键 词",
      limit: "25",
      archived: false,
      order: "",
      attach: null,
      extra: [false, 0, null],
    });
    expect(collected.archived).toBe(false);
    expect(collected.limit).toBe(25);
    expect(collected.order).toBeUndefined();
    expect(collected.attach).toBeNull();
    // 额外未声明字段不收集（session 仅保存允许字段），数组根语义在声明字段内保持：
    expect(collected.extra).toBeUndefined();
    const arrayRoot = collectFieldValues(
      [{ label: "清单", param: "rows", kind: "text" }],
      { rows: [false, 0, null] },
    );
    expect(arrayRoot.rows).toEqual([false, 0, null]);
  });

  it("非有限数字收集为 null，不伪装数值", () => {
    expect(collectFieldValues(fields, { limit: "abc" }).limit).toBeNull();
  });
});

describe("受限映射校验（缺失/未知/类型错误定位）", () => {
  it("未知映射目标、未知来源、非法常量、必填缺映射逐条定位", () => {
    const bad: FieldsBundle = {
      fields,
      mappings: {
        q: { kind: "direct", from: "nonexistent" },
        ghost: { kind: "constant", value: 1 },
        archived: { kind: "constant", value: { deep: true } },
      },
      returns: bundle.returns,
    };
    const errors = validateFieldsBundle(bad);
    expect(errors.some((e) => e.field === "ghost" && e.message.includes("未知实际参数"))).toBe(true);
    expect(errors.some((e) => e.field === "q" && e.message.includes("不是已声明字段"))).toBe(true);
    expect(errors.some((e) => e.field === "archived" && e.message.includes("只允许"))).toBe(true);
    expect(errors.some((e) => e.field === "attach" && e.message.includes("缺少映射"))).toBe(true);
  });

  it("重复参数名与空枚举阻断", () => {
    const dup: FieldsBundle = {
      fields: [fields[0], { ...fields[0], label: "重复" }],
      mappings: {},
      returns: bundle.returns,
    };
    expect(validateFieldsBundle(dup).some((e) => e.message.includes("重复的实际参数名"))).toBe(true);
    const noEnum: FieldsBundle = {
      fields: [{ label: "排序", param: "order", kind: "enum" }],
      mappings: {},
      returns: bundle.returns,
    };
    expect(validateFieldsBundle(noEnum).some((e) => e.message.includes("枚举字段未配置"))).toBe(true);
  });

  it("返回呈现：占位符/容器字段/产物路径未知引用阻断；空容器启用阻断", () => {
    const badReturns: FieldsBundle = {
      fields,
      mappings: { q: { kind: "direct", from: "q" } },
      returns: {
        summaryTemplate: "共 {{ n }} 条",
        cardTemplateEnabled: true,
        cardFields: [{ label: "X", from: "ghost" }],
        fileRefPaths: ["nope"],
      },
    };
    const errors = validateFieldsBundle(badReturns);
    expect(errors.some((e) => e.field === "summaryTemplate" && e.message.includes("n"))).toBe(true);
    expect(errors.some((e) => e.field === "cardTemplate" && e.message.includes("ghost"))).toBe(true);
    expect(errors.some((e) => e.field === "fileRefPaths" && e.message.includes("nope"))).toBe(true);
  });
});

describe("声明式映射求值与生成契约", () => {
  it("evaluateMapping 只支持直接引用/常量，无表达式执行", () => {
    expect(evaluateMapping({ kind: "direct", from: "q" }, { q: "abc" })).toBe("abc");
    expect(evaluateMapping({ kind: "constant", value: 0 }, {})).toBe(0);
    expect(evaluateMapping(undefined, {})).toBeUndefined();
  });

  it("生成契约按权威方言（modelSummary.tpl + cardTemplate + fileRefPaths）", () => {
    const contract = buildGeneratedContract(bundle);
    expect(contract.dialect).toEqual({ summaryTemplate: "modelSummary.tpl", container: "cardTemplate" });
    const returnSpec = contract.returnSpec as { summaryTemplate: string };
    expect(returnSpec.summaryTemplate).toContain("查询完成");
    expect(JSON.stringify(contract)).toContain("fileRefPaths");
    expect(JSON.stringify(contract)).not.toMatch(/\bauthRef\b|\bendpoint\b/);
  });

  it("切换模板丢弃提示：字段/映射/模板差异逐项列出", () => {
    const next = emptyFieldsBundle();
    const discarded = discardedOnSwitch(bundle, next);
    expect(discarded.some((item) => item.includes("关键字"))).toBe(true);
    expect(discarded.some((item) => item.includes("映射“limit”"))).toBe(true);
    expect(discarded).toContain("摘要模板");
  });
});
