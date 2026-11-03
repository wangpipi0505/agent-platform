// UI-03：AP资源与技能工作台——主从布局、任务分段与既有R修复回归锚。
// 静态断言锁定布局结构与提交边界语义；行为用例由 controlled-recheck /
// process-registration / openapi-import / mcp-registration 各自承载。
import { readFileSync } from "node:fs";
import { expect, it } from "vitest";

const read = (name: string) =>
  readFileSync(new URL("../admin/src/" + name, import.meta.url), "utf8");

it("详情主从布局：左侧版本列表约31%、右侧分段详情约69%，窄屏回落单列", () => {
  const detail = read("ResourceDetail.vue");
  expect(detail).toContain(
    "grid-template-columns: minmax(13rem, 31fr) minmax(0, 69fr)",
  );
  expect(detail).toContain('role="listbox"');
  expect(detail).toContain('role="option"');
  expect(detail).toContain(
    ".resource-detail { grid-template-columns: minmax(0, 1fr); }",
  );
});

it("详情任务分段：技能五段（契约→连接调试→重检→选型→发布），模板三段", () => {
  const detail = read("ResourceDetail.vue");
  for (const section of [
    '<el-tab-pane label="契约" name="contract">',
    '<el-tab-pane label="连接调试" name="connection">',
    '<el-tab-pane label="重检" name="recheck">',
    '<el-tab-pane label="选型验收" name="acceptance">',
    '<el-tab-pane label="版本与发布" name="release">',
    '<el-tab-pane label="模板内容" name="content">',
    '<el-tab-pane label="试渲染" name="render">',
  ])
    expect(detail).toContain(section);
  // 四段输出完整可查：服务接入、MCP工具面板、执行属性、版本记录均在分段内。
  expect(detail).toContain("service-access");
  expect(detail).toContain("McpToolsPanel");
  expect(detail).toContain("执行属性与权限守卫");
  expect(detail).toContain("版本与验收记录");
});

it("不可发布时禁用发布并说明条件；发布版本只读、编辑创建新草稿", () => {
  const detail = read("ResourceDetail.vue");
  expect(detail).toContain(':disabled="!selected"');
  expect(detail).toContain("请先在左侧选择版本。");
  expect(detail).toContain("发布由服务端校验连接测试、选型验收与确认角色门禁。");
  expect(detail).toContain("发布版本只读，编辑将创建新草稿。");
});

it("R06/R07/R08回归锚：重检提交边界正整数number、无工具预期空串、共享上传函数", () => {
  const detail = read("ResourceDetail.vue");
  expect(detail).toContain("controlledCallIdNumber");
  expect(detail).toContain('controlledCallId: controlledCallIdNumber()');
  expect(detail).toContain('{"question":"你好","expectSelected":""}');
  expect(detail).toContain("uploadArtifactFile(auth.token, file)");
});

it("R12回归锚：OpenAPI诊断动态展示，不写死数量，不把业务限制转为接口要求", () => {
  const importer = read("OpenApiImport.vue");
  expect(importer).toContain("op.diagnostics");
  expect(importer).toContain("preview.warnings");
  expect(importer).not.toMatch(/共\s*51|51个|16个诊断/);
});

it("四协议入口保留：HTTP/MCP/ALGORITHM/PROCESS 与 MCP 工具检查、Schema 输入", () => {
  const wizard = read("SkillWizard.vue");
  expect(wizard).toContain("['HTTP', 'MCP', 'ALGORITHM', 'PROCESS']");
  expect(read("McpToolsPanel.vue")).toContain("检查远端工具");
  expect(read("ResourceDetail.vue")).toContain(":upload-artifact=\"uploadArtifact\"");
});

it("UI-02遗留补迁移：四个资源列表页主操作经PageHeader actions插槽迁入顶栏", () => {
  for (const [page, action] of [
    ["SkillsPage.vue", "注册技能"],
    ["ProvidersPage.vue", "添加供应商"],
    ["ModelEntriesPage.vue", "添加模型条目"],
    ["PromptTemplatesPage.vue", "创建提示词模板"],
  ] as const) {
    const source = read("pages/" + page);
    expect(source).toContain("<template #actions>");
    expect(source).toContain(action);
  }
});
