export type MockRow = {
  skillCode: string;
  mode: "FIXED" | "LAST_REAL";
  response: string;
  sourceCallId?: number;
  [key: string]: any;
};
export function mockRows(rules: any[]): MockRow[] {
  return rules.map((rule) => {
    const { fixedResponse, ...rest } = rule;
    if (!["FIXED", "LAST_REAL"].includes(rule.mode))
      throw Error("服务端存在未知Mock模式，请勿覆盖");
    return { ...rest, response: JSON.stringify(fixedResponse ?? {}, null, 2) };
  });
}
export function mockRules(rows: MockRow[]) {
  const seen = new Set<string>();
  return rows.map((row) => {
    const { response, fixedResponse, ...rule } = row;
    if (!rule.skillCode?.trim() || seen.has(rule.skillCode))
      throw Error("请填写唯一的技能编码");
    seen.add(rule.skillCode);
    if (rule.mode === "LAST_REAL") {
      if(rule.sourceCallId==null)delete rule.sourceCallId;
      if (
        rule.sourceCallId !== undefined &&
        (!Number.isSafeInteger(rule.sourceCallId) || rule.sourceCallId <= 0)
      )
        throw Error("来源调用标识必须为正整数");
      return rule;
    }
    if (rule.mode !== "FIXED") throw Error("请选择有效Mock模式");
    delete rule.sourceCallId;
    const value = JSON.parse(response);
    if (value === null || typeof value !== "object" || Array.isArray(value))
      throw Error("固定响应必须为JSON对象");
    return { ...rule, fixedResponse: value };
  });
}
