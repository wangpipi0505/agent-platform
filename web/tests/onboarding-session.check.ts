import { beforeEach, afterEach, expect, it, vi } from "vitest";
import { createRenderer, defineComponent, h, nextTick, type Component } from "vue";
import TemplatesPage from "../admin/src/pages/onboarding/OnboardingTemplatesPage.vue";
import Workspace from "../admin/src/pages/onboarding/OnboardingSessionWorkspace.vue";
import BusinessFields from "../admin/src/pages/onboarding/BusinessFieldsStep.vue";
import { parseOnboardingSession, onboardingSessionClient, type OnboardingSession } from "../shared/onboarding-session";

const fixture = vi.hoisted(() => ({ api: vi.fn(), errors: [] as unknown[], role: "EDITOR" }));
vi.mock("../shared/capability-package", async () => {
  const original = await vi.importActual<typeof import("../shared/capability-package")>("../shared/capability-package");
  return { ...original, useCapabilityPackageClient: () => ({ list: () => { throw Error("业务使用区不能读取管理员来源包目录"); } }) };
});
vi.mock("../admin/src/PageHeader.vue", async () => {
  const { defineComponent, h } = await import("vue");
  return { default: defineComponent({ setup(_props, { slots }) { return () => h("header", null, slots.actions?.()); } }) };
});
vi.mock("vue-router", () => ({
  useRoute: () => ({ query: { onboardingSession: "intake-a" } }),
  useRouter: () => ({ replace: vi.fn() }),
}));
vi.mock("../admin/src/usePageOperation", async () => {
  const { ref } = await import("vue");
  return {
    usePageTitle: () => {},
    usePageOperation: () => {
      const busy = ref(false);
      return {
        auth: { token: "verified", generation: 0, can: (permission: string) => permission === "edit" || fixture.role === "ADMIN" },
        api: fixture.api, busy, error: ref(""), trace: ref(""), current: () => true,
        operation: async (work: () => Promise<void>) => {
          busy.value = true;
          try { await work(); } catch (error) { fixture.errors.push(error); }
          finally { busy.value = false; }
        },
      };
    },
  };
});
vi.mock("../admin/src/pages/onboarding/SemanticConfigurationStep.vue", async () => {
  const { defineComponent, h } = await import("vue");
  return { default: defineComponent({
    emits: ["saved"],
    setup(_props, { emit }) { return () => h("el-button", { onClick: () => emit("saved", { code: "orders_actual", draftRev: 9 }) }, "收到语义保存回执"); },
  }) };
});
interface Node { type: string; text: string; props: Record<string, unknown>; children: Node[]; parent: Node | null }
const node = (type: string, text = ""): Node => ({ type, text, props: {}, children: [], parent: null });
const renderer = createRenderer<Node, Node>({
  createElement: (type) => node(type), createText: (text) => node("text", text), createComment: (text) => node("comment", text),
  setText: (n, text) => { n.text = text; }, setElementText: (n, text) => { n.text = text; n.children = []; },
  patchProp: (n, key, _old, value) => { n.props[key] = value; },
  insert: (n, parent, anchor) => {
    if (n.parent) n.parent.children.splice(n.parent.children.indexOf(n), 1);
    n.parent = parent;
    const index = anchor ? parent.children.indexOf(anchor) : -1;
    if (index < 0) parent.children.push(n); else parent.children.splice(index, 0, n);
  },
  remove: (n) => { if (n.parent) n.parent.children.splice(n.parent.children.indexOf(n), 1); },
  parentNode: (n) => n.parent, nextSibling: (n) => n.parent?.children[n.parent.children.indexOf(n) + 1] ?? null,
});
const all = (root: Node): Node[] => [root, ...root.children.flatMap(all)];
const text = (root: Node): string => root.text + root.children.map(text).join("");
const roots: ReturnType<typeof renderer.createApp>[] = [];
async function settle() { for (let i = 0; i < 20; i++) { await Promise.resolve(); await nextTick(); } }
async function mount(component: Component, props: Record<string, unknown> = {}) {
  const root = node("root"), app = renderer.createApp({ render: () => h(component, props) });
  app.config.warnHandler = () => {};
  app.component("el-table-column", { render: () => null });
  // ElementPlus的Option会把label呈现为可见文本；Node宿主也保留该行为，文本断言不变。
  app.component("el-option", defineComponent({
    props: { label: { type: [String, Number], default: "" }, value: {}, disabled: Boolean },
    setup(props) {
      return () => h("el-option", { label: props.label, value: props.value, disabled: props.disabled }, String(props.label));
    },
  }));
  app.mount(root); roots.push(app); await settle(); return root;
}
function button(root: Node, label: string) {
  const value = all(root).find(n => n.type === "el-button" && text(n).includes(label));
  expect(value, `应有操作按钮：${label}`).toBeDefined(); return value!;
}
async function click(target: Node) {
  expect(target.props.disabled).not.toBe(true);
  (target.props.onClick as () => void)(); await settle();
}
function fill(root: Node, label: string, value: unknown) {
  const item = all(root).find(n => n.type === "el-form-item" && n.props.label === label);
  expect(item, `应有业务字段：${label}`).toBeDefined();
  const input = all(item!).find(n => typeof n.props["onUpdate:modelValue"] === "function");
  expect(input).toBeDefined(); (input!.props["onUpdate:modelValue"] as (v: unknown) => void)(value);
}
const bundle = {
  fields: [
    { label: "启用", param: "enabled", kind: "boolean" },
    { label: "数量", param: "count", kind: "number" },
    { label: "可空数值", param: "amount", kind: "number" },
  ], mappings: {}, returns: { summaryTemplate: "", cardTemplateEnabled: false, cardFields: [], fileRefPaths: [] },
};
function session(status = "DRAFT", id = "intake-a"): OnboardingSession {
  return {
    sessionId: id, rev: 4, status, templateCode: "tpl_query", templateRev: 2, appCode: "orders_actual",
    plan: { fieldsBundle: structuredClone(bundle) as OnboardingSession["plan"]["fieldsBundle"], values: { enabled: false, count: 0, amount: null }, selectedOperations: [] },
    planSha256: "a".repeat(64), steps: [],
    application: { appCode: "orders_actual", draftRev: 8, status: "DRAFT" },
    choices: { operations: [{ operationId: "orders.read", supported: true }, { operationId: "orders.write", supported: false, reasons: ["写入操作未受支持"] }] },
  };
}
let saved: OnboardingSession;
beforeEach(() => {
  saved = session(); fixture.errors.length = 0; fixture.role = "EDITOR"; fixture.api.mockReset();
  fixture.api.mockImplementation(async (path: string, method = "GET", body?: Record<string, unknown>) => {
    if (path === "/admin/onboarding-sessions/templates") return [{ code: "tpl_query", name: "标准读取模板", kind: "QUERY", status: "PUBLISHED", currentRev: 2 }];
    if (path === "/admin/onboarding-sessions" && method === "GET") return [saved, session("DRAFT", "intake-b")];
    if (path.startsWith("/admin/onboarding-sessions/") && method === "GET") return structuredClone(saved);
    if (path === "/admin/onboarding-sessions/intake-a" && method === "PUT") {
      expect(body?.expectedRev).toBe(saved.rev);
      saved = { ...saved, rev: saved.rev + 1, plan: JSON.parse(JSON.stringify(body!.plan)) as OnboardingSession["plan"] }; return saved;
    }
    if (path.endsWith("/cancel") && method === "POST") {
      expect(body?.expectedRev).toBe(saved.rev);
      saved = { ...saved, rev: saved.rev + 1, status: "CANCELLED" }; return saved;
    }
    throw Error(`意外请求 ${method} ${path}`);
  });
});
afterEach(() => roots.splice(0).forEach(app => app.unmount()));

it("恢复业务值false/0/数值null不被控件初始化省略，且不会将恢复误标为dirty", async () => {
  const values = vi.fn();
  await mount(BusinessFields, { fields: bundle.fields, initialValues: { enabled: false, count: 0, amount: null }, onValues: values });
  expect(values.mock.calls.at(-1)?.[0]).toEqual({ enabled: false, count: 0, amount: null });
  const root = await mount(Workspace, { templates: [] });
  expect(button(root, "预检计划").props.disabled).toBe(false);
});
it("操作选择显式显示unsupported原因，并将受支持选择保存进真实计划", async () => {
  const root = await mount(Workspace, { templates: [] });
  const operations = all(root).filter(n => n.type === "el-checkbox");
  const supported = operations.find(n => n.props.label === "orders.read");
  const unsupported = operations.find(n => n.props.label === "orders.write");
  expect(supported).toBeDefined(); expect(unsupported?.props.disabled).toBe(true);
  expect(text(root)).toContain("写入操作未受支持");
  (supported!.props["onUpdate:modelValue"] as (v: boolean) => void)(true); await settle();
  await click(button(root, "保存接入计划"));
  expect(saved.plan.selectedOperations).toEqual(["orders.read"]);
  expect(saved.plan.values).toEqual({ enabled: false, count: 0, amount: null });
});
it("本地dirty时继续/刷新不可覆盖，直接调用禁用按钮事件也不发送读回", async () => {
  const root = await mount(Workspace, { templates: [] });
  fill(root, "数量", 9); await settle();
  const refresh = button(root, "刷新会话"), resume = button(root, "继续");
  expect(refresh.props.disabled).toBe(true); expect(resume.props.disabled).toBe(true);
  const count = fixture.api.mock.calls.length;
  (refresh.props.onClick as () => void)(); (resume.props.onClick as () => void)(); await settle();
  expect(fixture.api.mock.calls).toHaveLength(count);
  const input = all(root).find(n => n.type === "el-input-number" && n.props["aria-label"] === "count");
  expect(input?.props.modelValue).toBe(9);
});
it("取消会话要求明确reason，使用当前rev且取消后禁止继续业务操作", async () => {
  const root = await mount(Workspace, { templates: [] });
  expect(button(root, "取消接入会话").props.disabled).toBe(true);
  fill(root, "取消理由", "业务不再需要接入"); await settle();
  await click(button(root, "取消接入会话"));
  const request = fixture.api.mock.calls.find(([path, method]) => String(path).endsWith("/cancel") && method === "POST");
  expect(request?.[2]).toMatchObject({ expectedRev: 4, reason: "业务不再需要接入" });
  expect(button(root, "预检计划").props.disabled).toBe(true);
});
it.each(["PUBLISHED", "CANCELLED"])("%s会话的所有变更/执行按钮禁用且不能补发execute", async status => {
  saved = session(status);
  saved.steps = [{ step: "VERIFY", status: "SUCCEEDED" }];
  const root = await mount(Workspace, { templates: [] });
  for (const label of ["保存接入计划", "预检计划", "组装真实应用草稿", "验证与评测", "按门禁发布助手"])
    expect(button(root, label).props.disabled, label).toBe(true);
  const calls = fixture.api.mock.calls.length;
  (button(root, "按门禁发布助手").props.onClick as () => void)(); await settle();
  expect(fixture.api.mock.calls).toHaveLength(calls);
});
it("交接仅采用server handover实际appCode/appVersion，模板编码刻意不同", async () => {
  saved = session("PUBLISHED");
  saved.handover = { templateCode: "tpl_query", appCode: "orders_actual", appVersion: 7, launchPath: "/w/orders_actual" };
  const root = await mount(Workspace, { templates: [] });
  const link = all(root).find(n => n.type === "a" && String(n.props.href).includes("/w/orders_actual"));
  expect(link).toBeDefined(); expect(text(root)).toContain("本次发布回执版本 7");
  expect(all(root).some(n => n.type === "a" && String(n.props.href).includes("/w/tpl_query"))).toBe(false);
});
it("坏handover、非发布状态和不完整稳定计划不能通过客户端解析", () => {
  const good = session("PUBLISHED");
  expect(() => parseOnboardingSession({ ...good, handover: { templateCode: "tpl_query", appCode: "other", appVersion: 7, launchPath: "/w/other" } })).toThrow();
  expect(() => parseOnboardingSession({ ...good, status: "DRAFT", handover: { templateCode: "tpl_query", appCode: "orders_actual", appVersion: 7, launchPath: "/w/orders_actual" } })).toThrow();
  expect(() => parseOnboardingSession({ ...good, plan: { ...good.plan, values: [] } })).toThrow();
});
it("semanticSaved只回读持久会话，不重复PUT应用或会话计划", async () => {
  const root = await mount(Workspace, { templates: [] });
  await click(button(root, "收到语义保存回执"));
  expect(fixture.api.mock.calls.every(([, method]) => !method || method === "GET")).toBe(true);
  expect(fixture.errors).toEqual([]);
});

it("业务使用只读发布模板目录，不请求ADMIN模板/包目录且不展示登记按钮", async () => {
  const root = await mount(TemplatesPage);
  expect(fixture.api.mock.calls.some(([path]) => path === "/admin/onboarding-sessions/templates")).toBe(true);
  expect(fixture.api.mock.calls.some(([path]) => path === "/admin/onboarding/templates")).toBe(false);
  expect(all(root).some(n => n.type === "el-button" && text(n).includes("登记模板"))).toBe(false);
  expect(fixture.errors).toEqual([]);
});
it("业务模板目录拒绝混入DRAFT或非QUERY，不把管理员草稿伪装成可用模板", async () => {
  const client = onboardingSessionClient(async () => [{ code: "tpl_query", name: "未发布", kind: "QUERY", status: "DRAFT", currentRev: 1 }]);
  await expect(client.templates()).rejects.toThrow("目录格式");
});

it("Workspace无需ADMIN父页模板即可独立读取业务目录，兼容items包装", async () => {
  const base = fixture.api.getMockImplementation()!;
  fixture.api.mockImplementation(async (path: string, method = "GET", body?: Record<string, unknown>) => {
    if (path === "/admin/onboarding-sessions/templates") return { items: [{ code: "tpl_query", name: "独立业务模板", kind: "QUERY", status: "PUBLISHED", currentRev: 2 }] };
    return base(path, method, body);
  });
  const root = await mount(Workspace);
  expect(text(root)).toContain("独立业务模板");
  expect(fixture.api.mock.calls.some(([path]) => path === "/admin/onboarding-sessions/templates")).toBe(true);
  expect(fixture.errors).toEqual([]);
});

it("未获评测时展示服务端缺项与实际应用工作台入口，不推断评测或发布通过", async () => {
  saved.steps = [{ step: "VERIFY", status: "SUCCEEDED", receipt: { runId: "r_original", missing: [{ code: "EVALUATION_REQUIRED", message: "请在原应用工作台提供确定性评测用例" }] } }];
  const root = await mount(Workspace);
  expect(text(root)).toContain("EVALUATION_REQUIRED");
  expect(all(root).some(n => n.type === "a" && n.props.href === "#/apps/orders_actual/orchestration/publish")).toBe(true);
  expect(all(root).some(n => n.type === "a" && String(n.props.href).includes("/w/"))).toBe(false);
  expect(fixture.api.mock.calls.every(([, method]) => !method || method === "GET")).toBe(true);
});
