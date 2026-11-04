import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { createRenderer, h, nextTick, type Component } from "vue";
import ExperienceEditor from "../admin/src/pages/integration/ExperienceEditor.vue";

const fixture = vi.hoisted(() => ({ api: vi.fn(), errors: [] as unknown[] }));
vi.mock("../admin/src/usePageOperation", async () => {
  const { ref } = await import("vue");
  return {
    usePageTitle: () => {},
    usePageOperation: () => {
      const busy = ref(false), error = ref("");
      return {
        auth: { can: () => true, token: "test-session" }, api: fixture.api,
        busy, error, trace: ref(""), success: () => {}, current: () => true,
        operation: async (fn: () => Promise<void>) => {
          busy.value = true;
          try { await fn(); } catch (e) { fixture.errors.push(e); }
          finally { busy.value = false; }
        },
      };
    },
  };
});
vi.mock("../admin/src/PageHeader.vue", () => ({ default: { render: () => null } }));

interface Node {
  type: string; text: string; props: Record<string, unknown>;
  children: Node[]; parent: Node | null;
}
const node = (type: string, text = ""): Node => ({ type, text, props: {}, children: [], parent: null });
const renderer = createRenderer<Node, Node>({
  createElement: (type) => node(type), createText: (text) => node("text", text),
  createComment: (text) => node("comment", text),
  setText: (n, text) => { n.text = text; },
  setElementText: (n, text) => { n.text = text; n.children = []; },
  patchProp: (n, key, _old, value) => { n.props[key] = value; },
  insert: (n, parent, anchor) => {
    if (n.parent) n.parent.children.splice(n.parent.children.indexOf(n), 1);
    n.parent = parent;
    const at = anchor ? parent.children.indexOf(anchor) : -1;
    if (at < 0) parent.children.push(n); else parent.children.splice(at, 0, n);
  },
  remove: (n) => { if (n.parent) n.parent.children.splice(n.parent.children.indexOf(n), 1); },
  parentNode: (n) => n.parent,
  nextSibling: (n) => n.parent?.children[n.parent.children.indexOf(n) + 1] ?? null,
});
const all = (n: Node): Node[] => [n, ...n.children.flatMap(all)];
const text = (n: Node): string => n.text + n.children.map(text).join("");
const roots: ReturnType<typeof renderer.createApp>[] = [];
async function settle() { for (let i = 0; i < 8; i++) { await Promise.resolve(); await nextTick(); } }
async function mount(component: Component) {
  const root = node("root"), app = renderer.createApp(component);
  app.config.warnHandler = () => {};
  // 列内容不参与本次表单操作；真实页面模板和按钮事件仍由Vue执行。
  app.component("el-table-column", { render: () => null });
  app.mount(root); roots.push(app); await settle(); return root;
}
function button(root: Node, label: string) {
  const found = all(root).find((n) => n.type === "el-button" && text(n).includes(label));
  expect(found, `应存在可操作按钮：${label}`).toBeDefined();
  return found!;
}
async function click(n: Node) {
  expect(n.props.disabled).not.toBe(true);
  (n.props.onClick as () => void)(); await settle();
}
function fill(root: Node, field: string, value: unknown) {
  const item = all(root).find((n) => n.type === "el-form-item" && n.props.label === field);
  expect(item, `应显示表单字段：${field}`).toBeDefined();
  const input = all(item!).find((n) => typeof n.props["onUpdate:modelValue"] === "function");
  expect(input).toBeDefined();
  (input!.props["onUpdate:modelValue"] as (v: unknown) => void)(value);
}
const experience = (status: string, rev: number) => ({
  appCode: "orders", experienceRev: rev, status, revisions: [{
    experienceRev: rev, title: "订单助手", welcomeText: "欢迎", capabilityDescriptions: [],
    promptRefs: [], inputPresentation: {}, resultPresentation: {}, helpRefs: [],
    declarationSha256: "a".repeat(64), createdBy: "admin", createdAt: "2026-11-04T00:00:00Z",
  }],
});
let current: ReturnType<typeof experience> | null;
beforeEach(() => {
  current = null; fixture.errors.length = 0; fixture.api.mockReset();
  fixture.api.mockImplementation(async (path: string, method = "GET", body?: Record<string, unknown>) => {
    if (path === "/admin/apps") return [{ code: "orders", name: "订单助手" }];
    if (path === "/admin/apps/orders/experiences" && method === "GET") {
      if (!current) throw { status: 404, code: "CONFIG_REF_NOT_FOUND" };
      return current;
    }
    if (path === "/admin/apps/orders/experiences" && method === "POST") {
      if (current) throw { status: 409, code: "CONFIG_REV_CONFLICT" };
      current = experience("DRAFT", 1); return current;
    }
    if (path === "/admin/apps/orders/experiences/versions") {
      expect(body?.expectedRev).toBe(current?.experienceRev);
      current = experience("DRAFT", current!.experienceRev + 1); return current;
    }
    throw new Error(`意外请求 ${method} ${path}`);
  });
});
afterEach(() => roots.splice(0).forEach((app) => app.unmount()));

it("CR08：没有体验时从页面创建首份草稿，并刷新显示服务端修订", async () => {
  const root = await mount(ExperienceEditor);
  fill(root, "标题", "订单助手"); await settle();
  await click(button(root, "创建体验草稿"));
  expect(current?.experienceRev).toBe(1);
  expect(text(root)).toContain("当前修订 1");
  expect(fixture.errors).toEqual([]);
});
it("CR08：已有DRAFT保存调用versions并带当前expectedRev，不重复create", async () => {
  current = experience("DRAFT", 2);
  const root = await mount(ExperienceEditor);
  await click(button(root, "保存修订"));
  expect(current?.experienceRev).toBe(3);
  expect(fixture.api.mock.calls.filter(([p, m]) => p === "/admin/apps/orders/experiences" && m === "POST")).toHaveLength(0);
  expect(fixture.errors).toEqual([]);
});
it("CR08：已批准体验追加修订仍走versions，返回新DRAFT后才可重新批准", async () => {
  current = experience("APPROVED", 3);
  const root = await mount(ExperienceEditor);
  await click(button(root, "保存为新草稿"));
  expect(current).toMatchObject({ status: "DRAFT", experienceRev: 4 });
  expect(text(root)).toContain("当前修订 4");
  expect(fixture.errors).toEqual([]);
});
