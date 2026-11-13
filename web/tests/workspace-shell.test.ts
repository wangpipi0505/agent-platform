// F2-1：智能体工作区壳层——智能体头+左锚点导航+面板布局+运行记录独立路由。
// 静态断言锁定壳层结构与「任务 b ≤4 步」的链接链；面板内部行为由既有用例承载
// （unsaved-changes/app-governance-visual/publish-gate 等），本文件不重复。
import { readFileSync } from "node:fs";
import { expect, it } from "vitest";

const read = (name: string) =>
  readFileSync(new URL("../" + name, import.meta.url), "utf8");

it("智能体头：工作区顶部常驻头含名称/编码/状态/版本摘要/主要动作（预检+提交发布）", () => {
  const workspace = read("admin/src/pages/AppWorkspacePage.vue");
  expect(workspace).toContain('class="agent-head"');
  expect(workspace).toContain('class="agent-name"');
  expect(workspace).toContain('class="agent-code font-mono"');
  // 状态胶囊复用全局 .status 语义色 + resource-format 的状态文案
  expect(workspace).toContain("statusLabel(app.status)");
  // 版本摘要=草稿修订 + 已发布版本号（现有 app 数据，不新造 API）
  expect(workspace).toContain("草稿 {{ app.draftRev");
  expect(workspace).toContain('"已发布 v" + app.currentVersionNo');
  // 主要动作入口沿用发布链路既有 check/commit
  expect(workspace).toContain('@click="check">预检</el-button>');
  expect(workspace).toContain('@click="commit"');
});

it("锚点导航：左锚点列组织既有面板，当前锚点高亮，顺序与既有 tab 一致", () => {
  const workspace = read("admin/src/pages/AppWorkspacePage.vue");
  expect(workspace).toContain('class="anchor-rail"');
  expect(workspace).toContain('class="workspace-content page-scroll"');
  // 高亮机制：active 类跟随当前 tab（key=路由 tabMatch）
  expect(workspace).toContain(':class="{ active: tab === (next || \'overview\') }"');
  // 面板仍按锚点 key 挂载（复用组件不重写内部）
  for (const key of [
    "tab === 'overview'",
    "tab === 'chat'",
    "tab === 'orchestration/chat'",
    "tab === 'orchestration/flow'",
    "tab === 'orchestration/debug'",
    "tab === 'orchestration/publish'",
    "tab === 'run'",
    "tab === 'runs'",
  ])
    expect(workspace).toContain(key);
});

it("面板布局：内容区用 .page-scroll 唯一滚动语义且 min-height:0，不新增三级滚动容器", () => {
  const workspace = read("admin/src/pages/AppWorkspacePage.vue");
  // 高度链：workspace(height:100%) → body(flex:1, min-height:0) → 内容 .page-scroll
  expect(workspace).toMatch(/\.workspace \{[^}]*height: 100%/);
  expect(workspace).toMatch(/\.workspace-body \{[^}]*min-height: 0/);
  expect(workspace).toMatch(/\.workspace-content \{[^}]*min-height: 0/);
  // 页面级新增滚动容器只有 .page-scroll 一处（锚点列自身可滚属 §7.1 锚点列 auto 例外，
  // 与内容滚动互不嵌套）；不得出现固定 vh 内嵌滚动的新包装。
  const pageScroll = workspace.match(/overflow-y: auto/g) ?? [];
  expect(pageScroll.length).toBe(1);
  expect(workspace).not.toMatch(/max-height:\s*\d+vh/);
  // tokens 红线：壳层样式不出现裸色值（#hex / rgb(）/ rgba(）
  const style = workspace.split("<style scoped>")[1] ?? "";
  expect(style).not.toMatch(/#[0-9a-fA-F]{3,8}\b|rgba?\(/);
});

it("records/:runId 独立路由：注册 app-run-record 访问键，详情复用 RunDetail 并锚回工作区", () => {
  const router = read("admin/src/router.ts");
  const access = read("admin/src/page-access.ts");
  const record = read("admin/src/pages/AppRunRecordPage.vue");
  // 路由与访问表同源登记（F1-2 口径：路由 meta.access 引用 page-access key）
  expect(router).toContain('path: "/apps/:code/records/:runId"');
  expect(router).toContain('access: "app-run-record"');
  expect(access).toContain('key: "app-run-record"');
  expect(access).toContain('route: "/apps/:code/records/:runId"');
  // 详情内容复用 RunDetail 单一实现（避免重复页面），页头带锚点面包屑
  expect(record).toContain("<RunDetail");
  expect(record).toContain("'#/apps/' + code + '/runs'");
  // 不再与工作区内嵌 records 分支并存（避免双实现）
  expect(read("admin/src/pages/AppWorkspacePage.vue")).not.toContain(
    "tab.startsWith('records/')",
  );
});

it("任务b步数≤4：列表→工作区→运行记录锚点→记录页，每跳链接在源码可追踪", () => {
  // 第1步：智能体列表行进入工作区（AppsPage 行链接 → /apps/:code）
  const apps = read("admin/src/pages/AppsPage.vue");
  expect(apps).toContain("'#/apps/' + encodeURIComponent(row.code)");
  // 第2步：工作区锚点导航含「运行记录」锚点（key=runs）
  const workspace = read("admin/src/pages/AppWorkspacePage.vue");
  expect(workspace).toContain("['运行记录', 'runs']");
  // 第3步：运行记录面板行链接跳独立记录页（app 语境路由，不再是全局 /runs/:runId）
  const runsPanel = read("admin/src/pages/workspace/AppRunsPanel.vue");
  expect(runsPanel).toContain("'/records/'");
  expect(runsPanel).not.toContain("'#/runs/'");
  // 第4步：记录页面包屑一键锚回工作区运行记录（返回路径 ≤1 步，不绕运行监控）
  const record = read("admin/src/pages/AppRunRecordPage.vue");
  expect(record).toContain("'#/apps/' + code + '/runs'");
  // 正式运行提交后的跳转同样落在独立记录页（工作区语境闭环）
  const runPanel = read("admin/src/pages/workspace/AppRunPanel.vue");
  expect(runPanel).toContain("/records/${");
  expect(runPanel).not.toContain("router.push(`/runs/");
});
