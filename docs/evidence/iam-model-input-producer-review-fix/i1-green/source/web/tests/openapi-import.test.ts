import { expect, it } from "vitest";
import { buildOpenApiImport, documentProblem, newOperationForm } from "../shared/openapi-import";

const operation = { operationId: "readResult", method: "GET", path: "/result", supported: true, diagnostics: [], inputSchema: {type: "object", properties: {region: {type: "string"}}} };
function complete() {
  return {...newOperationForm(operation), code: "read_result", name: "读取结果", capability: "读取结果", applicable: "读取授权区域", excluded: "不可修改", example: "region=LOCAL", execution: {readonly: true, sideEffect: false, idempotent: true, cancellable: false}, roles: "EDITOR", attributes: [{name: "region", values: ""}], summaryTemplate: "已读取结果", errorsConfirmed: true};
}
it("checks UTF-8 bytes and empty documents before network requests", () => {
  expect(documentProblem(" ")).toContain("文档");
  expect(documentProblem("中".repeat(350000))).toContain("1 MiB");
  expect(documentProblem("openapi: 3.0.3")).toBe("");
});
it("requires explicit business fields, execution flags and readonly guard attributes", () => {
  expect(() => buildOpenApiImport("doc", "http://127.0.0.1:28412", [operation], [newOperationForm(operation)], true)).toThrow(/编码|职责/);
  const form = complete(); form.attributes = [];
  expect(() => buildOpenApiImport("doc", "http://127.0.0.1:28412", [operation], [form], true)).toThrow(/守卫属性/);
});
it("keeps server-derived schemas out of overrides and creates only selected contracts", () => {
  const body = buildOpenApiImport("doc", "http://127.0.0.1:28412", [operation], [complete()], true);
  expect(body.includeOps).toEqual(["readResult"]);
  expect(body.overrides.readResult.guard).toEqual({roles: ["EDITOR"], attributes: {region: []}});
  expect(body.overrides.readResult.description).toContain("不可修改");
  expect(body.overrides.readResult).not.toHaveProperty("inputSchema");
  expect(body.overrides.readResult).not.toHaveProperty("authRef");
});
it("rejects unsupported operations, duplicate codes, credentials in URL and non-admin auth", () => {
  const run = (ops: any[], forms: any[], admin = true, base = "http://localhost") => buildOpenApiImport("doc", base, ops, forms, admin);
  expect(() => run([{...operation, supported: false}], [complete()])).toThrow(/不支持/);
  expect(() => run([operation, {...operation, operationId: "second"}], [complete(), {...complete(), operationId: "second"}])).toThrow(/重复/);
  expect(() => run([operation], [complete()], true, "https://key:secret@example.test")).toThrow(/服务地址/);
  expect(() => run([{...operation, requiresAuth: true}], [{...complete(), authRef: "vault:existing"}], false)).toThrow(/管理员/);
  expect(() => run([{...operation, requiresAuth: true}], [complete()])).toThrow(/认证引用/);
});
it("blocks unconfirmed execution and failure semantics before creation", () => {
  const run = (form: any) => buildOpenApiImport("doc", "http://localhost", [operation], [form], true);
  expect(() => run({...complete(), execution: {...complete().execution, readonly: null}})).toThrow(/执行属性/);
  expect(() => run({...complete(), execution: {...complete().execution, sideEffect: true}})).toThrow(/副作用/);
  expect(() => run({...complete(), errorsConfirmed: false})).toThrow(/失败语义/);
  expect(() => run({...complete(), errors: [{code: "", message: "retry", retryable: false}]})).toThrow(/错误码/);
  expect(() => run({...complete(), attributes: [{name: "missing", values: ""}]})).toThrow(/输入参数/);
});
it("preserves explicit write confirmation roles and structured error rows", () => {
  const form = {...complete(), execution: {...complete().execution, readonly: false, sideEffect: true}, confirmRoles: "APPROVER", errors: [{code: "BUSY", message: "稍后重试", retryable: true}]};
  const body = buildOpenApiImport("doc", "https://example.test/base", [operation], [form], true);
  expect(body.overrides.readResult.execution.confirmRoles).toEqual(["APPROVER"]);
  expect(body.overrides.readResult.errorSemantics).toEqual(form.errors);
  expect(() => buildOpenApiImport("doc", "https://example.test/base", [operation], [{...form, confirmRoles: ""}], true)).toThrow(/确认角色/);
});
