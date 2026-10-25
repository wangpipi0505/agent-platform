import schema from "../../contracts/graph-definition.schema.json";
import { definitionIssues } from "./validation";
export type MappingRow = {
  key: string;
  source: "literal" | "variable";
  type: string;
  value: string;
};
const names: Record<string, string> = {
  START: "开始",
  END: "结束",
  LLM: "模型推理",
  SKILL: "技能调用",
  CONDITION: "条件分支",
  CODE: "表达式转换",
  RETRIEVE: "检索技能",
  CONFIRM: "人工确认",
  AGENT: "智能体循环",
  ONTOLOGY: "本体调用（待配置）",
};
export const nodeTypes = schema.properties.nodes.items.properties.type.enum.map(
  (type) => ({ type, label: names[type] || type }),
);
export function rowsFromMap(value: Record<string, any> = {}): MappingRow[] {
  return Object.entries(value || {}).map(([key, v]) => ({
    key,
    source:
      typeof v === "string" && /^\$\{.*\}$/.test(v) ? "variable" : "literal",
    type:
      v === null
        ? "NULL"
        : Array.isArray(v)
          ? "ARRAY"
          : typeof v === "object"
            ? "OBJECT"
            : typeof v === "number"
              ? "NUMBER"
              : typeof v === "boolean"
                ? "BOOLEAN"
                : "STRING",
    value: typeof v === "string" ? v : JSON.stringify(v),
  }));
}
export function mapFromRows(rows: MappingRow[]) {
  const value: Record<string, any> = {};
  for (const row of rows) {
    if (!row.key.trim()) throw new Error("请填写参数名称");
    if (Object.hasOwn(value, row.key))
      throw new Error("参数名称重复：" + row.key);
    if (["__proto__", "prototype", "constructor"].includes(row.key))
      throw new Error("不允许此参数名称");
    let v: any = row.value;
    if (row.source === "variable") {
      if (!/^\$\{.+\}$/.test(v))
        throw new Error(row.key + " 请选择完整变量引用");
    } else if (row.type === "NUMBER") {
      if (!String(v).trim() || !Number.isFinite(Number(v)))
        throw new Error(row.key + " 必须是有效数字");
      v = Number(v);
    } else if (row.type === "BOOLEAN") {
      if (!["true", "false"].includes(v))
        throw new Error(row.key + " 必须为true或false");
      v = v === "true";
    } else if (row.type === "NULL") v = null;
    else if (["OBJECT", "ARRAY"].includes(row.type)) {
      v = JSON.parse(v);
      if (
        row.type === "ARRAY"
          ? !Array.isArray(v)
          : v === null || typeof v !== "object" || Array.isArray(v)
      )
        throw new Error(row.key + " 类型不匹配");
    }
    value[row.key] = v;
  }
  return value;
}
export function nodeIssues(definition: any) {
  const issues = definitionIssues(definition);
  const ids = new Set((definition.nodes || []).map((n: any) => n.id));
  for (const n of definition.nodes || []) {
    const add = (message: string) =>
      issues.push({ nodeId: n.id, message, path: "" });
    if (
      ["SKILL", "RETRIEVE"].includes(n.type) &&
      (!n.skillRef?.code || !n.skillRef?.version)
    )
      add("请选择技能并填写发布版本");
    if (n.type === "CODE" && !n.expression?.trim()) add("请填写转换表达式");
    if (n.type === "CONFIRM" && !n.confirmRoles?.length)
      add("请指定至少一个确认角色");
    if (n.type === "CONDITION") {
      if (!n.branches?.length) add("请添加条件分支");
      for (const b of n.branches || [])
        if (!ids.has(b.to || b.else)) add("分支目标节点不存在");
    }
  }
  return issues;
}
