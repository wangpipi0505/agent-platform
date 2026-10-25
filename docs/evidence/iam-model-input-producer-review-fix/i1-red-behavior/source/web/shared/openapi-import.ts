export interface OpenApiOperation {
  operationId: string; method: string; path: string; supported: boolean;
  diagnostics: unknown[]; requiresAuth?: boolean; inputSchema?: any; outputSchema?: any;
}
export interface OperationForm {
  operationId: string; code: string; name: string; capability: string; applicable: string; excluded: string; example: string;
  execution: Record<string, boolean | null>; roles: string; confirmRoles: string;
  attributes: {name: string; values: string}[];
  authRef: string; timeoutMs: number; summaryTemplate: string; structuredPath: string; fileRefPaths: string;
  errors: {code: string; message: string; retryable: boolean}[]; errorsConfirmed: boolean;
}
export function documentProblem(document: string): string {
  if (!document.trim()) return "请粘贴文档或选择本地 JSON / YAML 文件";
  return new TextEncoder().encode(document).byteLength > 1048576 ? "文档超过 1 MiB，请缩小后重试" : "";
}
export function newOperationForm(operation: OpenApiOperation): OperationForm {
  return {operationId: operation.operationId, code: "", name: "", capability: "", applicable: "", excluded: "", example: "",
    execution: {readonly: null, sideEffect: null, idempotent: null, cancellable: null}, roles: "", confirmRoles: "", attributes: [],
    authRef: "", timeoutMs: 30000, summaryTemplate: "", structuredPath: "$", fileRefPaths: "", errors: [], errorsConfirmed: false};
}
const list = (text: string) => [...new Set(text.split(/[,，\n]/).map(value => value.trim()).filter(Boolean))];
export function buildOpenApiImport(document: string, baseUrl: string, operations: OpenApiOperation[], forms: OperationForm[], admin: boolean) {
  const problem = documentProblem(document); if (problem) throw new Error(problem);
  try {
    const url = new URL(baseUrl);
    if (!/^https?:$/.test(url.protocol) || url.username || url.password || url.search || url.hash || /[{}]/.test(baseUrl)) throw new Error();
  } catch { throw new Error("服务地址必须是无凭据、无查询与模板的 HTTP(S) 地址"); }
  if (!forms.length) throw new Error("请至少选择一个支持的操作");
  const codes = new Set<string>();
  const overrides: Record<string, any> = Object.create(null);
  for (const form of forms) {
    const operation = operations.find(op => op.operationId === form.operationId);
    const fail = (message: string): never => {throw new Error(`${form.operationId}：${message}`);};
    if (!operation?.supported) fail("该操作不支持导入，请查看预览诊断");
    if (!/^[a-z][a-z0-9_]{1,63}$/.test(form.code)) fail("编码需以小写字母开头，使用小写字母、数字、下划线，2–64 位");
    if (codes.has(form.code)) fail("技能编码重复，请分别设置唯一编码"); codes.add(form.code);
    if (![form.name, form.capability, form.applicable, form.excluded, form.example].every(value => value.trim())) fail("请填写名称、职责、适用与不适用范围、参数示例");
    if (Object.values(form.execution).some(value => typeof value !== "boolean")) fail("请逐项确认四项执行属性");
    if (form.execution.readonly && form.execution.sideEffect) fail("只读操作不能同时声明副作用");
    const attributes: Record<string, string[]> = Object.create(null);
    for (const attribute of form.attributes) {
      const name = attribute.name.trim();
      if (!name || Object.hasOwn(attributes, name)) fail("守卫属性名不能为空或重复");
      if (!Object.hasOwn(operation?.inputSchema?.properties || {}, name)) fail("守卫属性必须对应输入参数");
      attributes[name] = list(attribute.values);
    }
    const roles = list(form.roles);
    if (!roles.length && !Object.keys(attributes).length) fail("请声明角色或属性守卫，空守卫会默认拒绝");
    if (form.execution.readonly && !Object.keys(attributes).length) fail("查询技能必须声明守卫属性，并对应已验身份的授权范围");
    const confirmRoles = list(form.confirmRoles);
    if (!form.execution.readonly && !confirmRoles.length) fail("写操作必须填写确认角色");
    if (!form.summaryTemplate.trim() || !form.structuredPath.trim()) fail("请填写返回摘要与结构化结果路径");
    if (!form.errorsConfirmed) fail("请确认失败语义；可显式确认无额外业务错误");
    if (form.errors.some(error => !error.code.trim() || !error.message.trim())) fail("每项失败语义需填写错误码和处理说明");
    if (!Number.isInteger(form.timeoutMs) || form.timeoutMs < 100 || form.timeoutMs > 600000) fail("超时需为 100–600000 毫秒的整数");
    if (form.authRef.trim() && !admin) fail("认证引用仅管理员可配置");
    if (operation?.requiresAuth && !form.authRef.trim()) fail("该操作要求 Bearer 认证引用，请管理员配置已登记的 secret_ref 后导入");
    overrides[form.operationId] = {
      code: form.code, name: form.name.trim(),
      description: `能力职责：${form.capability.trim()}\n适用：${form.applicable.trim()}\n不适用：${form.excluded.trim()}\n参数示例：${form.example.trim()}`,
      execution: {...form.execution, ...(!form.execution.readonly ? {confirmRoles} : {})},
      guard: {roles, attributes}, timeoutMs: form.timeoutMs,
      returnSpec: {summaryTemplate: form.summaryTemplate.trim(), structuredPath: form.structuredPath.trim(), fileRefPaths: list(form.fileRefPaths)},
      errorSemantics: form.errors.map(error => ({...error, code: error.code.trim(), message: error.message.trim()})),
      ...(admin && form.authRef.trim() ? {authRef: form.authRef.trim()} : {}),
    };
  }
  return {document, includeOps: forms.map(form => form.operationId), defaults: {baseUrl: baseUrl.trim()}, overrides};
}
