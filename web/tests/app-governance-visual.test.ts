// UI-04：AP应用编排调试治理视觉——状态语义色、顶栏主操作、图表主题、画布令牌回归锚。
// 静态断言锁定呈现结构；图定义/保存字段行为由 graph.test.ts 等既有用例承载。
import { readFileSync } from "node:fs";
import { expect, it } from "vitest";

const read = (name: string) =>
  readFileSync(new URL("../" + name, import.meta.url), "utf8");

it("状态语义色：WAITING_* 悬置黄不与成功混同，成功/失败/运行各得其色", () => {
  const runList = read("admin/src/RunList.vue");
  // 全局（非 scoped）映射：RunList/RunDetail/确认中心与各资源列表共用 .status 类
  expect(runList).toContain("<style>");
  expect(runList).toContain(".status.WAITING_APPROVAL");
  expect(runList).toContain(".status.WAITING_EXTERNAL");
  expect(runList).toContain("var(--st-suspend-text");
  expect(runList).toContain(".status.SUCCEEDED");
  expect(runList).toContain(".status.PUBLISHED");
  expect(runList).toContain("var(--st-ok-text");
  expect(runList).toContain(".status.FAILED");
  expect(runList).toContain("var(--st-fail-text");
  // 运行状态标签：等待确认是独立状态文案，不画成成功。
  expect(runList).toContain('"WAITING_APPROVAL", "等待确认"');
});

it("治理页主操作经PageHeader actions插槽迁入顶栏", () => {
  const apps = read("admin/src/pages/AppsPage.vue");
  expect(apps).toContain("<template #actions>");
  expect(apps).toContain("创建智能体");
  // 运行/确认/评测/服务/统计/审计/系统页主操作=刷新，由PageHeader内置并已接入；
  // 页内不再重复主操作行（此前已迁移的 Operations/ServiceManagement 同模式）。
  for (const page of [
    "admin/src/pages/ApprovalsPage.vue",
    "admin/src/pages/EvaluationsPage.vue",
    "admin/src/pages/StatisticsPage.vue",
    "admin/src/pages/AuditPage.vue",
    "admin/src/pages/SystemPage.vue",
  ])
    expect(read(page)).toContain("<PageHeader");
  // F1-3：RunsPage 改为四段高度样板页——页头（含刷新）内嵌于 RunList 载体的
  // DataPage 四段模型，页源码层面以 <RunList> 断言承接。
  expect(read("admin/src/pages/RunsPage.vue")).toContain("<RunList");
});

it("ECharts主题helper从语义令牌解析轴/图例/tooltip配色", () => {
  const chart = read("shared/chart.ts");
  expect(chart).toContain("export function chartBaseTheme");
  for (const tokenName of ["--ink", "--muted", "--line", "--surface-2"])
    expect(chart).toContain('token("' + tokenName + '"');
  expect(chart).toContain("tooltip:");
});

it("X6画布保持令牌化：节点描边/状态色/文字均取自语义变量", () => {
  const transform = read("flow-designer/transform.ts");
  expect(transform).toContain('var(--st-wait)');
  expect(transform).toContain('var(--surface)');
  expect(transform).toContain('var(--ink)');
  const designer = read("flow-designer/FlowDesigner.vue");
  // 运行态着色：WAITING* → --st-suspend（悬置），SUCCEEDED → --st-ok，FAILED → --st-fail；
  // 等待态不得画成成功色。
  expect(designer).toContain('"--st-"');
  expect(designer).toContain('? "ok"');
  expect(designer).toContain('? "fail"');
  expect(designer).toContain('? "suspend"');
  expect(designer).toContain('? "run"');
});

it("CodeMirror只读全文已适配：readOnly+不可编辑+主题令牌", () => {
  const viewer = read("shared/CodeViewer.vue");
  expect(viewer).toContain("EditorState.readOnly.of(true)");
  expect(viewer).toContain("EditorView.editable.of(false)");
  expect(viewer).toContain('backgroundColor: "var(--surface-2)"');
  expect(viewer).toContain("{ dark: true }");
});

it("应用工作区现有tab顺序保持：概览→编排→调试→发布集成→正式运行→运行记录", () => {
  const workspace = read("admin/src/pages/AppWorkspacePage.vue");
  const overview = workspace.indexOf("['概览', '']");
  const orchestration = workspace.indexOf("'orchestration/flow'");
  const debug = workspace.indexOf("'orchestration/debug'");
  const publish = workspace.indexOf("'orchestration/publish'");
  const run = workspace.indexOf("'run']");
  const runs = workspace.indexOf("'runs']");
  for (const index of [overview, orchestration, debug, publish, run, runs])
    expect(index).toBeGreaterThan(-1);
  expect(overview).toBeLessThan(orchestration);
  expect(orchestration).toBeLessThan(debug);
  expect(debug).toBeLessThan(publish);
  expect(publish).toBeLessThan(run);
  expect(run).toBeLessThan(runs);
  // 发布预检门禁语义保持：预检标识缺失时明确报错，不静默放行。
  expect(workspace).toContain("请先重新预检并通过评测");
});
