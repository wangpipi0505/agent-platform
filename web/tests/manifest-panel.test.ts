// F3-5：工作区 manifest 分区卡编辑 + 发布预检同屏。
// 逻辑面直测 shared/mock-manifest（GET/PUT 形状与语义逐字段对齐 A3-1 已交付端点：
// WorkspaceManifestController/WorkspaceManifests）；页面接线以源码静态断言锁定
// （先例：workspace-shell.test.ts / unsaved-changes.test.ts 的静态断言口径，
// 不挂载 Vue 组件——面板交互由既有全局用例口径承载）。
//
// ── 基准任务 a「创建智能体并发布 ≤6 步」步数推导（台账 §7.5：任务 a 在此卡验收）──
//   步1 智能体列表行链接进入工作区（AppsPage 行链接 → /apps/:code；创建 Dialog 成功后也直接跳工作区）
//   步2 工作区落在概览锚点：manifest 分区卡与概览同屏（AppWorkspacePage overview tab，
//       AppOverviewPanel 下方即 ManifestPanel，无需额外导航步）
//   步3 manifest 七分区受控编辑（ManifestPanel data-partition=model/skills/plugins/rules/
//       memory/context/permission 的受控字段）
//   步4 保存 manifest（「保存 manifest」→ PUT {requestId, manifest, baseRevision} CAS；
//       409 MANIFEST_REVISION_CONFLICT 同屏提示重载）
//   步5 预检（智能体头「预检」/分区卡「运行发布预检」→ manifest 引用核验 checks + 校验
//       issues 合并同屏）
//   步6 提交发布（智能体头「提交发布」；manifest 校验不过 disabled+tooltip，manifest 保存后
//       既有预检过期会先要求重新预检）
//   = 6 步 ≤ 6 ✓；每跳锚点在本文件「任务a」用例中逐条源码可追踪。
import { readFileSync } from "node:fs";
import { beforeEach, expect, it } from "vitest";
import {
  mockManifestGet,
  mockManifestPut,
  resetMockManifests,
  validateManifest,
  type ManifestObject,
} from "../shared/mock-manifest";

const read = (name: string) =>
  readFileSync(new URL("../" + name, import.meta.url), "utf8");

/** 一份可通过冻结契约校验的最小 manifest（字段照 contracts/manifest.schema.json 七分区）。 */
function validManifest(): ManifestObject {
  return {
    metadata: { name: "演示智能体", category: "运行支持" },
    model: {
      defaultBinding: { providerCode: "openai-compatible", modelName: "glm-4.7" },
      inferencePolicy: { temperature: 0.3, retry: { max: 2, on: ["TRANSPORT"] } },
    },
    skills: {
      guideRefs: ["analyze-flow@2"],
      toolGrants: [{ skillCode: "detect-a", version: 3 }],
    },
    plugins: [{ pluginCode: "mcp-orbit", version: "1.4.0", capabilities: ["SKILL:detect-a"] }],
    rules: ["no-direct-delete@1"],
    memoryPolicy: { kinds: ["PREFERENCE"], readScopes: ["PRIVATE"], writeScopes: ["PRIVATE"], topK: 5 },
    contextPolicy: { maxTurns: 200, contextBudget: 60000, compactionThreshold: 0.85, historyWindow: 20 },
    permission: { roles: ["USER", "OPERATOR"] },
  };
}

async function errorOf(promise: Promise<unknown>): Promise<any> {
  try {
    await promise;
    return null;
  } catch (e) {
    return e;
  }
}

beforeEach(() => resetMockManifests());

it("七分区字段编辑形状：合法 manifest 通过校验（零 issues）", () => {
  expect(validateManifest(validManifest())).toEqual([]);
});

it("七分区逐项破坏：issues 指明 path/code（path/code/message 逐条）", () => {
  const cases: [string, (m: ManifestObject) => void, string, string][] = [
    ["模型", (m) => delete m.model.defaultBinding.providerCode, "/model/defaultBinding/providerCode", "MANIFEST_FIELD_REQUIRED"],
    ["技能链-guideRefs", (m) => (m.skills.guideRefs = []), "/skills/guideRefs", "MANIFEST_FIELD_REQUIRED"],
    ["技能链-引用形状", (m) => (m.skills.guideRefs = ["analyze-flow"]), "/skills/guideRefs", "MANIFEST_FIELD_INVALID"],
    ["技能链-toolGrants", (m) => (m.skills.toolGrants = [{ skillCode: "detect-a" }]), "/skills/toolGrants/version", "MANIFEST_FIELD_INVALID"],
    ["插件", (m) => (m.plugins = [{ pluginCode: "mcp-orbit" }]), "/plugins/version", "MANIFEST_FIELD_REQUIRED"],
    ["规则", (m) => (m.rules = ["no-direct-delete"]), "/rules", "MANIFEST_FIELD_INVALID"],
    ["记忆-topK", (m) => (m.memoryPolicy.topK = 0), "/memoryPolicy/topK", "MANIFEST_FIELD_INVALID"],
    ["记忆-写范围", (m) => (m.memoryPolicy.writeScopes = ["PUBLIC"]), "/memoryPolicy/writeScopes", "MANIFEST_FIELD_INVALID"],
    ["上下文-maxTurns", (m) => (m.contextPolicy.maxTurns = 0), "/contextPolicy/maxTurns", "MANIFEST_FIELD_REQUIRED"],
    ["上下文-压缩阈值", (m) => (m.contextPolicy.compactionThreshold = 1.5), "/contextPolicy/compactionThreshold", "MANIFEST_FIELD_INVALID"],
    ["权限-空角色", (m) => (m.permission.roles = []), "/permission/roles", "MANIFEST_FIELD_REQUIRED"],
    ["权限-非法角色", (m) => (m.permission.roles = ["GUEST"]), "/permission/roles", "MANIFEST_FIELD_INVALID"],
    ["必键缺失", (m) => delete m.contextPolicy, "/contextPolicy", "MANIFEST_KEY_REQUIRED"],
  ];
  for (const [partition, mutate, path, code] of cases) {
    const manifest = validManifest();
    mutate(manifest);
    const issues = validateManifest(manifest);
    const hit = issues.find((issue) => issue.path === path && issue.code === code);
    expect(hit, `分区[${partition}] 应产生 ${path} ${code}，实际: ${JSON.stringify(issues)}`).toBeTruthy();
    expect(hit!.message.length).toBeGreaterThan(0);
  }
});

it("GET 形状：演示 manifest + revision + digest + validation（demo:true，对齐 A3-1 响应体）", async () => {
  const view = await mockManifestGet("demo-app");
  expect(view.demo).toBe(true);
  expect(view.appCode).toBe("demo-app");
  expect(view.revision).toBeGreaterThanOrEqual(1);
  expect(view.digest).toMatch(/^[0-9a-f]{64}$/);
  expect(view.validation).toEqual({ ok: true, issues: [] });
  expect(view.manifest.metadata.name.length).toBeGreaterThan(0);
  // 七分区齐备（演示种子覆盖冻结契约全部分区）
  for (const key of ["metadata", "model", "skills", "plugins", "rules", "memoryPolicy", "contextPolicy", "permission"])
    expect(view.manifest[key]).toBeTruthy();
});

it("CAS 409：baseRevision 过期 → MANIFEST_REVISION_CONFLICT，形状真实（status/code/message 指明期望与携带）", async () => {
  await mockManifestGet("demo-app"); // 种子 → 头修订 1
  const stale = await errorOf(
    mockManifestPut("demo-app", { requestId: "r1", manifest: validManifest(), baseRevision: 0 }),
  );
  expect(stale.status).toBe(409);
  expect(stale.code).toBe("MANIFEST_REVISION_CONFLICT");
  expect(stale.message).toContain("期望 1");
  expect(stale.message).toContain("请求携带 0");
  // 正确保存推进头修订后，旧 baseRevision 再次过期
  await mockManifestPut("demo-app", { requestId: "r2", manifest: validManifest(), baseRevision: 1 });
  const expired = await errorOf(
    mockManifestPut("demo-app", { requestId: "r3", manifest: validManifest(), baseRevision: 1 }),
  );
  expect(expired?.status).toBe(409);
  expect(expired?.code).toBe("MANIFEST_REVISION_CONFLICT");
  expect(expired?.message).toContain("期望 2");
});

it("digest 去重：同内容命中既有修订原样返回，revision 不推进；不同内容才追加", async () => {
  await mockManifestGet("demo-app"); // 头修订 1
  const first = await mockManifestPut("demo-app", { requestId: "a", manifest: validManifest(), baseRevision: 1 });
  expect(first.deduplicated).toBe(false);
  expect(first.revision).toBe(2);
  const replay = await mockManifestPut("demo-app", { requestId: "b", manifest: validManifest(), baseRevision: 2 });
  expect(replay.deduplicated).toBe(true);
  expect(replay.revision).toBe(2); // 不推进
  expect(replay.digest).toBe(first.digest);
  const changed = validManifest();
  changed.memoryPolicy.topK = 9;
  const next = await mockManifestPut("demo-app", { requestId: "c", manifest: changed, baseRevision: 2 });
  expect(next.deduplicated).toBe(false);
  expect(next.revision).toBe(3); // 修订链只追加
});

it("校验失败 400：MANIFEST_INVALID 聚合 issues（面板据此同屏 path/code/message），且不落修订", async () => {
  await mockManifestGet("demo-app");
  const broken = validManifest();
  delete broken.permission.roles;
  broken.contextPolicy.maxTurns = 0;
  const invalid = await errorOf(
    mockManifestPut("demo-app", { requestId: "x", manifest: broken, baseRevision: 1 }),
  );
  expect(invalid.status).toBe(400);
  expect(invalid.code).toBe("MANIFEST_INVALID");
  expect(invalid.issues.length).toBeGreaterThanOrEqual(2); // 聚合，不短路
  const view = await mockManifestGet("demo-app");
  expect(view.revision).toBe(1); // 校验失败不落任何修订
});

it("分区卡：七分区各自一张卡且受控字段编辑（禁裸 JSON textarea）", () => {
  const panel = read("admin/src/pages/workspace/ManifestPanel.vue");
  for (const partition of ["model", "skills", "plugins", "rules", "memory", "context", "permission"])
    expect(panel, `缺分区卡 data-partition=${partition}`).toContain(`data-partition="${partition}"`);
  // 受控字段编辑：七分区字段逐一 v-model（契约冻结字段，无 JSON 文本编辑）
  for (const binding of [
    'v-model="draft.model.defaultBinding.providerCode"',
    'v-model="draft.model.defaultBinding.modelName"',
    'v-model="draft.model.defaultBinding.promptTemplateRef.code"',
    'v-model="draft.model.defaultBinding.promptTemplateRef.version"',
    'v-model="draft.model.inferencePolicy.temperature"',
    'v-model="draft.model.inferencePolicy.retry.on"',
    'v-model="draft.model.inferencePolicy.budget.maxTurns"',
    'v-model="draft.model.inferencePolicy.budget.maxModelCalls"',
    'v-model="draft.skills.guideRefs[index]"',
    'v-model="row.skillCode"',
    'v-model="row.version"',
    'v-model="row.pluginCode"',
    'v-model="row.version"',
    'v-model="row.capabilities"',
    'v-model="draft.rules"',
    'v-model="draft.memoryPolicy.kinds"',
    'v-model="draft.memoryPolicy.readScopes"',
    'v-model="draft.memoryPolicy.writeScopes"',
    'v-model="draft.memoryPolicy.topK"',
    'v-model="draft.contextPolicy.maxTurns"',
    'v-model="draft.contextPolicy.contextBudget"',
    'v-model="draft.contextPolicy.compactionThreshold"',
    'v-model="draft.contextPolicy.historyWindow"',
    'v-model="draft.permission.roles"',
  ])
    expect(panel, `缺受控绑定 ${binding}`).toContain(binding);
  // 红线：受控字段编辑，禁裸 JSON textarea（元素级断言；javadoc 文字提及不算）
  expect(panel).not.toMatch(/<textarea/i);
  expect(panel).not.toContain('type="textarea"');
});

it("保存=CAS PUT：requestId+baseRevision，409 同屏提示重载，issues 表 path/code/message 逐条", () => {
  const panel = read("admin/src/pages/workspace/ManifestPanel.vue");
  expect(panel).toContain("requestId: crypto.randomUUID()");
  expect(panel).toContain("baseRevision: head.value?.revision ?? 0");
  expect(panel).toContain('e?.code === "MANIFEST_REVISION_CONFLICT"');
  expect(panel).toContain("409 MANIFEST_REVISION_CONFLICT");
  expect(panel).toContain("重载最新修订（覆盖本地未保存修改）");
  expect(panel).toContain('e?.code === "MANIFEST_INVALID"');
  // issues 同屏（path/code/message 三列逐条）
  expect(panel).toContain('prop="path"');
  expect(panel).toContain('prop="code"');
  expect(panel).toContain('prop="message"');
  expect(panel).toContain(':data="combinedIssues"');
});

it("预检同屏：manifestChecks（规则 PASS/BLOCKED、技能/插件 N/A）+ 校验 issues 合并展示", () => {
  const panel = read("admin/src/pages/workspace/ManifestPanel.vue");
  // 只取 manifest 引用核验条目（A3-1 checks 结构：group 以 manifest 开头）
  expect(panel).toContain('startsWith("manifest")');
  expect(panel).toContain(':data="manifestChecks"');
  // M5-2 切真实 API：面板经 shared/api-manifest（真实端点 GET/PUT /admin/apps/{code}/manifest）；
  // VITE_MOCK 演示分支收口进 api-manifest（页面不再 import mock-*）。
  expect(panel).toContain("api-manifest");
  expect(panel).toContain("fetchManifest");
  expect(panel).toContain("putManifest");
  const transport = read("shared/api-manifest.ts");
  expect(transport).toContain("import.meta.env?.VITE_MOCK");
  expect(transport).toContain("mock-manifest");
  expect(transport).toContain("/manifest`, \"PUT\"");
});

it("checklist 联动：校验不过 → 智能体头提交发布 disabled+tooltip；过/无 manifest → 不禁（存量口径）", () => {
  const workspace = read("admin/src/pages/AppWorkspacePage.vue");
  // 联动口径：loaded && exists && !ok 才禁（无 manifest 应用预检 SKIPPED，发布链不受影响）
  expect(workspace).toContain(
    "manifestState.value.loaded && manifestState.value.exists && !manifestState.value.ok",
  );
  expect(workspace).toContain(':disabled="manifestBlocked"');
  expect(workspace).toContain("el-tooltip");
  expect(workspace).toContain(':content="manifestBlockHint');
  expect(workspace).toContain('@validation-change="onManifestState"');
  // manifest 保存后既有预检过期（发布链必须重新预检）
  expect(workspace).toContain('@saved="onManifestSaved"');
  expect(workspace).toContain("precheckStale.value = true");
  // 未保存离开保护覆盖 manifest 草稿
  expect(workspace).toContain("configDirty.value || manifestState.value.dirty");
  expect(workspace).toContain("manifestPanelRef.value?.resetDraft()");
});

it("任务a步数≤6：列表→工作区→分区编辑→保存→预检→提交发布，每跳锚点源码可追踪", () => {
  // 步1 列表行链接进入工作区（创建成功也直达工作区）
  expect(read("admin/src/pages/AppsPage.vue")).toContain(
    "'#/apps/' + encodeURIComponent(row.code)",
  );
  const workspace = read("admin/src/pages/AppWorkspacePage.vue");
  // 步2 工作区概览锚点同屏 manifest 分区卡（无需额外导航步）
  expect(workspace).toContain('v-if="tab === \'overview\'"');
  expect(workspace).toContain("<ManifestPanel");
  // 步3 七分区受控编辑 + 步4 保存（CAS PUT 在 ManifestPanel，上一用例已锁）
  expect(read("admin/src/pages/workspace/ManifestPanel.vue")).toContain("@click=\"save\"");
  // 步5 预检：智能体头既有 [预检] + 分区卡同屏「运行发布预检」
  expect(workspace).toContain('@click="check">预检</el-button>');
  expect(read("admin/src/pages/workspace/ManifestPanel.vue")).toContain("emit('check')");
  // 步6 提交发布：智能体头 [提交发布]（校验不过 disabled+tooltip，联动用例已锁）
  expect(workspace).toContain('@click="commit"');
});
