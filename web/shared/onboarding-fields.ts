/**
 * ONB-03：业务字段表单与受限映射的声明式模型。只支持直接引用/常量两类映射，
 * 不增加任意 JS/表达式执行；false/0/null/数组根在收集与展示中保持原值不改写；
 * 映射缺失、类型错误和未知字段在 submit 前阻断并定位到字段。
 */
import { isPlainValue } from "./resource-types";

/** 业务字段声明：模板生成表单的单一来源（标签/说明/单位/枚举/文件控件）。 */
export interface BusinessFieldDef {
  /** 用户可见字段名（表单标签）。 */
  label: string;
  /** 实际参数名（发送给运行体的键；须与声明 inputSchema 对齐）。 */
  param: string;
  kind: "text" | "number" | "boolean" | "enum" | "file";
  description?: string;
  unit?: string;
  /** enum 的允许取值；空数组表示未配置（校验阻断）。 */
  enumValues?: string[];
  required?: boolean;
}

/** 受限映射：直接引用另一字段，或常量值；无表达式、无脚本。 */
export type FieldMapping =
  | { kind: "direct"; from: string }
  | { kind: "constant"; value: unknown };

/** 返回呈现（R01 权威方言）：摘要模板 + 可选表格容器 + 受控产物引用路径。 */
export interface ReturnPresentation {
  /** modelSummary.tpl：{{}} 插值，占位符只能是已声明返回字段。 */
  summaryTemplate: string;
  /** JSON 容器 cardTemplate（可选）；键为展示名，值为返回字段引用。 */
  cardTemplateEnabled: boolean;
  cardFields: { label: string; from: string }[];
  /** 受控产物引用路径（fileRefPaths，每行一个；只能引用声明字段）。 */
  fileRefPaths: string[];
}

export interface FieldsBundle {
  fields: BusinessFieldDef[];
  mappings: Record<string, FieldMapping>;
  returns: ReturnPresentation;
}

/** 返回字段引用合法性：占位符/映射目标必须在已声明返回字段集合内。 */
export function declaredReturnFields(fields: BusinessFieldDef[]): string[] {
  return fields.filter((field) => field.kind !== "file").map((field) => field.param);
}

function extractPlaceholders(template: string): string[] {
  return [...template.matchAll(/\{\{\s*([\w.]+)\s*\}\}/g)].map((match) => match[1]);
}

/** 映射校验：缺失、类型错误、未知字段逐条阻断并定位；模板所需字段必填。 */
export function validateFieldsBundle(bundle: FieldsBundle): { field: string; message: string }[] {
  const errors: { field: string; message: string }[] = [];
  const params = new Set(bundle.fields.map((field) => field.param));
  if (params.size !== bundle.fields.length)
    errors.push({ field: "*", message: "存在重复的实际参数名。" });
  for (const field of bundle.fields) {
    if (!field.label.trim()) errors.push({ field: field.param, message: "缺少用户可见标签。" });
    if (field.kind === "enum" && !(field.enumValues && field.enumValues.length))
      errors.push({ field: field.param, message: "枚举字段未配置允许取值。" });
  }
  for (const [param, mapping] of Object.entries(bundle.mappings)) {
    if (!params.has(param)) {
      errors.push({ field: param, message: `映射指向未知实际参数“${param}”。` });
      continue;
    }
    if (mapping.kind === "direct" && !params.has(mapping.from))
      errors.push({ field: param, message: `映射来源“${mapping.from}”不是已声明字段。` });
    if (mapping.kind === "constant" && !isPlainValue(mapping.value) && mapping.value !== undefined)
      errors.push({ field: param, message: "常量映射只允许字符串/数字/布尔/null。" });
  }
  for (const field of bundle.fields) {
    if (field.required && !bundle.mappings[field.param])
      errors.push({ field: field.param, message: "必填字段缺少映射。" });
  }
  const returns = bundle.returns;
  const returnFields = declaredReturnFields(bundle.fields);
  for (const placeholder of extractPlaceholders(returns.summaryTemplate)) {
    if (!returnFields.includes(placeholder))
      errors.push({ field: "summaryTemplate", message: `摘要模板占位符“${placeholder}”不是已声明返回字段。` });
  }
  for (const card of returns.cardFields) {
    if (!returnFields.includes(card.from))
      errors.push({ field: "cardTemplate", message: `容器字段“${card.label}”引用未知返回字段“${card.from}”。` });
  }
  for (const path of returns.fileRefPaths.filter(Boolean)) {
    if (!returnFields.includes(path))
      errors.push({ field: "fileRefPaths", message: `产物引用路径“${path}”不是已声明返回字段。` });
  }
  if (returns.cardTemplateEnabled && !returns.cardFields.length)
    errors.push({ field: "cardTemplate", message: "启用容器卡片但未配置任何字段。" });
  return errors;
}

/**
 * 表单值收集：boolean/number/null 保持原类型；数组根原样返回；
 * 空字符串的非必填字段省略——不改写 false/0/null 语义。
 */
export function collectFieldValues(
  fields: BusinessFieldDef[],
  values: Record<string, unknown>,
): Record<string, unknown> {
  const result: Record<string, unknown> = {};
  for (const field of fields) {
    const raw = values[field.param];
    if (raw === undefined) continue;
    if (field.kind === "boolean") {
      result[field.param] = raw === true || raw === "true" ? true : raw === false || raw === "false" ? false : null;
      continue;
    }
    if (field.kind === "number") {
      if (raw === null || raw === "") continue;
      const parsed = typeof raw === "number" ? raw : Number(raw);
      result[field.param] = Number.isFinite(parsed) ? parsed : null;
      continue;
    }
    if (Array.isArray(raw)) {
      result[field.param] = raw;
      continue;
    }
    if (raw === null) {
      result[field.param] = null;
      continue;
    }
    const text = String(raw);
    if (text === "" && !field.required) continue;
    result[field.param] = text;
  }
  return result;
}

/** 声明式映射求值（无表达式执行）：direct 取来源字段值，constant 取常量。 */
export function evaluateMapping(
  mapping: FieldMapping | undefined,
  values: Record<string, unknown>,
): unknown {
  if (!mapping) return undefined;
  if (mapping.kind === "constant") return mapping.value;
  return values[mapping.from];
}

/** 生成契约预览（高级区只读）：模板方言 modelSummary.tpl + JSON 容器 + 产物路径。 */
export function buildGeneratedContract(bundle: FieldsBundle): Record<string, unknown> {
  return {
    dialect: { summaryTemplate: "modelSummary.tpl", container: "cardTemplate" },
    inputs: bundle.fields.map((field) => ({
      param: field.param,
      label: field.label,
      kind: field.kind,
      unit: field.unit ?? "",
      required: field.required === true,
      enumValues: field.enumValues ?? [],
    })),
    mappings: bundle.mappings,
    returnSpec: {
      summaryTemplate: bundle.returns.summaryTemplate,
      cardTemplateEnabled: bundle.returns.cardTemplateEnabled,
      cardFields: bundle.returns.cardFields,
      fileRefPaths: bundle.returns.fileRefPaths.filter(Boolean),
    },
  };
}

/** 空白声明式包（向导初始化用）。 */
export function emptyFieldsBundle(): FieldsBundle {
  return { fields: [], mappings: {}, returns: { summaryTemplate: "", cardTemplateEnabled: false, cardFields: [], fileRefPaths: [] } };
}

/** 切换模板/协议时计算将被丢弃的内容，供确认框展示。 */
export function discardedOnSwitch(
  current: FieldsBundle,
  next: FieldsBundle,
): string[] {
  const discarded: string[] = [];
  for (const field of current.fields) {
    if (!next.fields.some((item) => item.param === field.param))
      discarded.push(`字段“${field.label}”(${field.param})`);
  }
  for (const param of Object.keys(current.mappings)) {
    if (!next.mappings[param] && current.fields.some((field) => field.param === param))
      discarded.push(`映射“${param}”`);
  }
  if (current.returns.summaryTemplate !== next.returns.summaryTemplate)
    discarded.push("摘要模板");
  return discarded;
}
