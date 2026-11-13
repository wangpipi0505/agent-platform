// F3-3（台账 §7.5）：资产管理四页（记忆/规则/插件/MCP）+ 确认中心四类待办矩阵——mock 驱动。
// 锁定：①四页形状（mock-rules/mock-plugins/mock-mcp demo:true，接口稿 §5.5/§5.6 六路径与端点形状；
// mock-memories 消费 F4 已交付形状+F3-3 DRAFT 增补）；②记忆版本 CAS 409 MEMORY_VERSION_CONFLICT
// （编辑与 DRAFT 审批同码）；③规则发布预检互斥 409 RULE_MUTEX_BLOCKED 展示规则对；④插件发现
// 状态机 QUEUED/RUNNING/SUCCEEDED/FAILED（失败保留上一次目录）；⑤确认中心四类 Tab（§5.9 矩阵：
// 发布评审/高危操作/计划确认/DRAFT 记忆，处理角色口径）+ 角标与四类列表未分页总数一致性（处置后同刷）。
// 与现有页面测试相同：真实 SFC setup（compileScript+transpile），按依赖替换为受控桩，setupState 断言。
import { readFileSync } from 'node:fs';
import { compileScript, parse } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as Vue from 'vue';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as mockMemories from '../shared/mock-memories';
import * as mockRules from '../shared/mock-rules';
import * as mockPlugins from '../shared/mock-plugins';
import * as mockMcp from '../shared/mock-mcp';
import * as counts from '../shared/mock-confirmation-counts';
import * as matrix from '../shared/mock-confirmation-matrix';
import { useLiveList } from '../shared/hooks/useLiveList';

const read = (path: string) =>
  readFileSync(new URL('../' + path, import.meta.url), 'utf8');

const cleanup: (() => void)[] = [];
afterEach(() => {
  cleanup.splice(0).forEach((close) => close());
  vi.restoreAllMocks();
});
beforeEach(() => {
  mockMemories.resetMemoryAdminDemo();
  mockRules.resetRulesDemo();
  mockPlugins.resetPluginsDemo();
  mockMcp.resetMcpDemo();
  matrix.resetConfirmationMatrixDemo();
});

async function flush() {
  for (let index = 0; index < 10; index++) {
    await Promise.resolve();
    await Vue.nextTick();
  }
}

const ok = <T>(value: T | { error: string }): T => {
  expect(value).not.toHaveProperty('error');
  return value as T;
};

/** 真实页面 setup：模板不编译（渲染桩），依赖按 ID 显式替换（先例=task-console.test.ts）。 */
function mountPage(path: string, extraStubs: Record<string, unknown> = {}) {
  const toast = { success: vi.fn(), info: vi.fn(), error: vi.fn() };
  const confirmDanger = vi.fn(async () => true);
  const routerPush = vi.fn();
  const { descriptor, errors } = parse(
    read(path),
    'utf8',
  );
  expect(errors, path).toEqual([]);
  const code = ts.transpileModule(
    compileScript(descriptor, { id: 'AssetPageTest' }).content,
    { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } },
  ).outputText;
  const exports = {} as { default: Vue.Component & { render: () => void } };
  const requireStub = (id: string) => {
    if (id === 'vue') return Vue;
    if (id === 'vue-router') return { useRouter: () => ({ push: routerPush }) };
    if (id === 'element-plus') return { ElMessage: toast };
    if (id.endsWith('.vue')) return { default: { render: () => null } };
    if (id.includes('DangerConfirm')) return { confirmDanger };
    if (id.includes('usePageOperation')) return { usePageTitle: () => {} };
    if (id.includes('useLiveList')) return { useLiveList };
    for (const [needle, stub] of Object.entries(extraStubs)) {
      if (id.includes(needle)) return stub;
    }
    throw new Error(`unexpected require: ${id}`);
  };
  new Function('require', 'exports', code)(requireStub, exports);
  exports.default.render = () => null;
  const renderer = Vue.createRenderer<object, object>({
    createComment: () => ({}),
    insert() {},
    remove() {},
    parentNode: () => null,
    nextSibling: () => null,
    createElement: () => ({}),
    createText: () => ({}),
    setText() {},
    setElementText() {},
    patchProp() {},
  });
  const app = renderer.createApp({ render: () => Vue.h(exports.default) });
  const instance = app.mount({}).$ as unknown as { subTree: { component: { setupState: any } } };
  cleanup.push(() => app.unmount());
  return { state: instance.subTree.component!.setupState as any, toast, confirmDanger, routerPush };
}

// 各页依赖：mock 模块按真实模块注入（页面消费与测试同源）。
function mountMemories() {
  return mountPage('admin/src/pages/assets/MemoriesPage.vue', { 'mock-memories': mockMemories });
}
function mountRules() {
  return mountPage('admin/src/pages/assets/RulesPage.vue', { 'mock-rules': mockRules });
}
function mountPlugins() {
  return mountPage('admin/src/pages/assets/PluginsPage.vue', { 'mock-plugins': mockPlugins });
}
function mountMcp() {
  return mountPage('admin/src/pages/assets/McpConnectionsPage.vue', { 'mock-mcp': mockMcp });
}
function mountApprovals() {
  return mountPage('admin/src/pages/ApprovalsPage.vue', {
    'mock-confirmation-counts': counts,
    'mock-confirmation-matrix': matrix,
  });
}

describe('资产管理四页 mock 形状（接口稿 §5.4-5.6，demo:true）', () => {
  it('记忆（F4 形状+F3-3 增补）：列表无正文/DRAFT 不进默认检索/审批 PATCH status 语义/DELETED 退出检索', () => {
    // 列表项不含正文；默认检索仅 ACTIVE（DRAFT/REVOKED 不回流）
    const list = mockMemories.listMemories({});
    expect(list.memories.length).toBeGreaterThanOrEqual(2);
    expect(list.memories.every((m) => !('content' in m))).toBe(true);
    expect(list.memories.every((m) => m.status === 'ACTIVE')).toBe(true);
    // DRAFT 显式查询：两粒种子（私有层来源用户/共享层 ADMIN）
    const drafts = mockMemories.demoListDraftMemories();
    expect(drafts).toHaveLength(2);
    expect(drafts.map((d) => d.scope).sort()).toEqual(['PRIVATE', 'SHARED']);
    expect(mockMemories.listMemories({ status: 'DRAFT' }).memories).toHaveLength(2);
    // 审批（PATCH status 字段语义）：确认→ACTIVE 进入默认检索
    const draft = drafts[0]!;
    const approved = ok(mockMemories.demoApproveMemory(draft.memoryId, 'ACTIVE', draft.version));
    expect(approved).toMatchObject({ status: 'ACTIVE', version: draft.version + 1 });
    expect(mockMemories.listMemories({}).memories.some((m) => m.memoryId === draft.memoryId)).toBe(true);
    // 拒绝→REVOKED 立即退出检索；重复审批同一草稿 404（非 DRAFT 不可再迁移）
    const other = mockMemories.demoListDraftMemories()[0]!;
    ok(mockMemories.demoApproveMemory(other.memoryId, 'REVOKED', other.version));
    expect(mockMemories.listMemories({ status: 'REVOKED' }).memories.some((m) => m.memoryId === other.memoryId)).toBe(true);
    expect(mockMemories.demoApproveMemory(other.memoryId, 'ACTIVE', other.version + 1))
      .toMatchObject({ error: 'MEMORY_NOT_FOUND', httpStatus: 404 });
    // 单条正文读取：REVOKED 不可见（F4 冻结语义）；DRAFT 管理台预览可见
    expect(mockMemories.getMemory(other.memoryId)).toMatchObject({ error: 'MEMORY_NOT_FOUND' });
    expect(mockMemories.demoGetDraftMemory(other.memoryId)).toMatchObject({ error: 'MEMORY_NOT_FOUND' });
  });

  it('记忆编辑版本 CAS：过期 409 MEMORY_VERSION_CONFLICT（页面冲突提示重载口径）', async () => {
    const first = mockMemories.listMemories({}).memories[0]!;
    expect(mockMemories.updateMemory(first.memoryId, { content: 'x', expectedVersion: first.version + 5 }))
      .toMatchObject({ error: 'MEMORY_VERSION_CONFLICT', httpStatus: 409 });
    const saved = ok(mockMemories.updateMemory(first.memoryId, { content: '更新正文', expectedVersion: first.version }));
    expect(saved).toMatchObject({ version: first.version + 1, content: '更新正文' });
    // 页面挂载：编辑对话框表单承载 expectedVersion（受控表单），409 时置冲突标记
    const page = mountMemories();
    // mock 级成功更新已把版本推至 first.version+1：页面以再+1 的过期版本提交 → 409
    page.state.editForm = {
      memoryId: first.memoryId, content: '并发修改', tags: '', expectedVersion: first.version + 2,
    };
    await page.state.saveEdit();
    expect(page.state.editConflict).toBe(true);
    expect(page.toast.error).toHaveBeenCalledWith(expect.stringContaining('版本冲突'));
    // 重载：读最新版本回填表单并清除冲突标记（expectedVersion 回到当前版本）
    await page.state.reloadEditContent();
    expect(page.state.editConflict).toBe(false);
    expect(page.state.editForm.expectedVersion).toBe(first.version + 1);
    expect(page.toast.success).toHaveBeenCalledWith(expect.stringContaining('重载'));
  });

  it('规则六路径：登记→草稿（三字段受控）→发布→版本→启停；发布预检互斥 409 RULE_MUTEX_BLOCKED 展示规则对', () => {
    const rules = mockRules.listRules().rules;
    expect(rules.every((r) => (r as any).demo === true)).toBe(true);
    expect(rules.map((r) => r.nature).sort()).toEqual(['ENFORCED', 'ENFORCED', 'GUIDANCE']);
    // 登记初始仅草稿
    const created = ok(mockRules.createRule({
      appCode: 'recon-assistant', code: 'rate-limit', name: '限流护栏', nature: 'ENFORCED',
      scope: 'OPERATION', content: { trigger: '外部调用', action: '按窗口限流', requirement: '超限挂起并审计' },
    }));
    expect(created).toMatchObject({ status: 'DRAFT', version: 0, enabled: false });
    // 草稿编辑（三字段）：登记即草稿 v1，编辑递增至 v2
    const drafted = ok(mockRules.updateRuleDraft('rate-limit', {
      content: { trigger: '外部调用峰值', action: '按窗口限流', requirement: '超限挂起并审计' },
    }));
    expect(drafted.draftVersion).toBe(2);
    // 发布：预检互斥冲突（同 scope DATA 同互斥键 delete-guard 的已启用 ENFORCED 对）
    const blocked = mockRules.publishRule('batch-delete-guard', 1) as any;
    expect(blocked.error).toBe('RULE_MUTEX_BLOCKED');
    expect(blocked.httpStatus).toBe(409);
    expect(blocked.conflicts[0].codes).toEqual(['batch-delete-guard', 'no-direct-delete']);
    expect(blocked.conflicts[0].scope).toBe('DATA');
    // 先停用对方再发布：预检通过、版本并入、默认启用
    ok(mockRules.setRuleEnabled('no-direct-delete', false, '演示让路'));
    const published = ok(mockRules.publishRule('batch-delete-guard', 1));
    expect(published.published).toMatchObject({ version: 1 });
    expect(published.rule).toMatchObject({ status: 'PUBLISHED', enabled: true, version: 1 });
    // 版本历史（新→旧）与启停回写
    const versions = ok(mockRules.listRuleVersions('batch-delete-guard'));
    expect(versions.versions[0]!.version).toBe(1);
    ok(mockRules.setRuleEnabled('batch-delete-guard', false, '窗口期'));
    expect(mockRules.listRules().rules.find((r) => r.code === 'batch-delete-guard')!.enabled).toBe(false);
    // GUIDANCE 不参与互斥阻断
    expect(mockRules.publishRule('report-tone', 3)).toHaveProperty('published');
  });

  it('插件：来源/插件/版本/授权形状 + 启停 reason 必填 + 发现状态机（失败保留上一次目录）', () => {
    // 来源与目录形状
    const sources = mockPlugins.listPluginSources();
    expect(sources).toHaveLength(2);
    expect(sources.every((s) => s.demo === true)).toBe(true);
    const plugins = mockPlugins.listPlugins();
    expect(plugins.every((p) => p.demo === true)).toBe(true);
    expect(mockPlugins.listPlugins({ status: 'DISABLED' }).every((p) => p.status === 'DISABLED')).toBe(true);
    // 启停：disable 必填 reason
    expect(mockPlugins.setPluginEnabled('pv-1', false)).toMatchObject({ error: 'PARAM_REASON_REQUIRED', httpStatus: 400 });
    const disabled = ok(mockPlugins.setPluginEnabled('pv-1', false, '升级窗口'));
    expect(disabled).toMatchObject({ status: 'DISABLED', disableReason: '升级窗口' });
    // 授权/撤权
    const granted = ok(mockPlugins.addPluginGrant('pv-1', { granteeType: 'ROLE', granteeCode: 'OPERATOR', capabilities: ['SKILL:detect-b'] }));
    const grant = granted.grants.at(-1)!;
    expect(grant).toMatchObject({ granteeType: 'ROLE', granteeCode: 'OPERATOR' });
    expect(ok(mockPlugins.removePluginGrant(grant.grantId))).toMatchObject({ revoked: true });
    // 发现状态机：202 受理 QUEUED → RUNNING → SUCCEEDED(changes 合入目录)
    const accepted = ok(mockPlugins.triggerDiscovery('src-1'));
    expect(accepted).toEqual({ discoveryId: expect.any(String), status: 'QUEUED' });
    // 串行：进行中再触发 409
    expect(mockPlugins.triggerDiscovery('src-1')).toMatchObject({ error: 'DISCOVERY_IN_PROGRESS', httpStatus: 409 });
    const running = ok(mockPlugins.pollDiscovery('src-1', accepted.discoveryId));
    expect(running.status).toBe('RUNNING');
    const succeeded = ok(mockPlugins.pollDiscovery('src-1', accepted.discoveryId));
    expect(succeeded.status).toBe('SUCCEEDED');
    expect(succeeded.changes!.added[0]!.pluginCode).toBe('geo-annotate');
    expect(mockPlugins.listPlugins().some((p) => p.pluginCode === 'geo-annotate')).toBe(true);
    expect(mockPlugins.listPlugins().find((p) => p.pluginCode === 'ocr-enhance')!.version).toBe('1.5.0');
    // 失败分支：error 可展示且目录保持上一次成功状态（geo-annotate 仍在）
    const source = mockPlugins.listPluginSources().find((s) => s.id === 'src-2')!;
    (source as any).failNextDiscovery = true;
    const failedAccept = ok(mockPlugins.triggerDiscovery('src-2'));
    mockPlugins.pollDiscovery('src-2', failedAccept.discoveryId);
    const failed = mockPlugins.pollDiscovery('src-2', failedAccept.discoveryId) as any;
    expect(failed.status).toBe('FAILED');
    expect(failed.error).toMatchObject({ code: 'SOURCE_UNREACHABLE' });
    expect(failed.changes).toBeUndefined();
    expect(mockPlugins.listPlugins().some((p) => p.pluginCode === 'geo-annotate')).toBe(true);
  });

  it('MCP：连接档案/连通测试失败类别/发现批次串行/快照差异/审核技能草稿/修订绑定', () => {
    const connections = mockMcp.listMcpConnections();
    expect(connections.every((c) => c.demo === true)).toBe(true);
    expect(connections.find((c) => c.id === 'mcp-1')).toMatchObject({ revision: 3, catalogBatchNo: 4, status: 'CONNECTED' });
    // 连通测试：OK 与具体失败类别
    expect(ok(mockMcp.testMcpConnection('mcp-1'))).toMatchObject({ result: 'OK', revision: 3 });
    const target = mockMcp.listMcpConnections().find((c) => c.id === 'mcp-1')! as any;
    target.failNextTest = 'TIMEOUT';
    expect(mockMcp.testMcpConnection('mcp-1')).toMatchObject({ result: 'FAILED', failureCategory: 'TIMEOUT' });
    // 登记连接（revision=1）+ 配置变更 revision+1（旧修订结果不入目录）
    const created = ok(mockMcp.createMcpConnection({ name: '文档网关', endpoint: 'http://gw/mcp', transport: 'HTTP' }));
    expect(created).toMatchObject({ revision: 1, status: 'UNTESTED' });
    const updated = ok(mockMcp.updateMcpConnection(created.id, { endpoint: 'http://gw2/mcp' }));
    expect(updated.revision).toBe(2);
    // 发现批次串行：同连接并发第二笔 409
    const batch = ok(mockMcp.discoverMcpTools(created.id));
    expect(batch).toMatchObject({ batchNo: 1, revision: 2, status: 'RUNNING' });
    expect(mockMcp.discoverMcpTools(created.id)).toMatchObject({ error: 'DISCOVERY_IN_PROGRESS', httpStatus: 409 });
    // 快照差异（有目录存量的 mcp-1，批次 b5）：ADDED/CHANGED/REMOVED 全量标记 + 目录版本推进
    const batch1 = ok(mockMcp.discoverMcpTools('mcp-1'));
    expect(batch1.batchNo).toBe(5);
    const diff = ok(mockMcp.listMcpTools('mcp-1', { diff: true }));
    const changeTypes = [...diff.diff!.added, ...diff.diff!.changed, ...diff.diff!.removed].map((t) => t.changeType);
    expect(changeTypes).toEqual(['ADDED', 'CHANGED', 'REMOVED']);
    expect(mockMcp.listMcpConnections().find((c) => c.id === 'mcp-1')!.catalogBatchNo).toBe(5);
    // 审核生成技能草稿（skillDraftRef）
    const reviewed = ok(mockMcp.reviewMcpTools('mcp-1', { toolName: diff.diff!.added[0]!.toolName }));
    expect(reviewed.skillDraftRef).toMatchObject({ draftId: expect.any(String), code: `mcp_${diff.diff!.added[0]!.toolName}` });
    expect(mockMcp.reviewMcpTools('mcp-1', { toolName: 'missing' })).toMatchObject({ error: 'TOOL_NOT_FOUND', httpStatus: 404 });
  });
});

describe('资产管理四页 setup（真实 SFC，渲染桩）', () => {
  it('四页挂载：chips 过滤/受控表单/操作入口齐全，红线（confirmDanger/tokens/DataPage）不破', async () => {
    for (const [path, name] of [
      ['admin/src/pages/assets/MemoriesPage.vue', '记忆管理'],
      ['admin/src/pages/assets/RulesPage.vue', '规则管理'],
      ['admin/src/pages/assets/PluginsPage.vue', '插件管理'],
      ['admin/src/pages/assets/McpConnectionsPage.vue', 'MCP 连接'],
    ] as const) {
      const src = read(path);
      // 红线：无原生 confirm；确认通道=confirmDanger；样式只用 tokens 变量（无裸 hex）
      expect(src).toContain('confirmDanger');
      expect(src).not.toMatch(/(^|[^.\w])confirm\(/);
      expect(src).not.toMatch(/#[0-9a-fA-F]{3,8}\b/);
      expect(src).toContain('<DataPage');
      expect(src).toContain(name);
    }
    // 记忆页：scope/status chips + DRAFT 审批动作（通过/拒绝）
    const memories = mountMemories();
    await flush();
    expect(memories.state.draftCount).toBe(2);
    // 规则页：SchemaForm 简化为三字段受控表单（trigger/action/requirement）
    const rules = mountRules();
    expect(rules.state.CONTENT_FIELDS.map((f: any) => f.key)).toEqual(['trigger', 'action', 'requirement']);
    // 插件页：发现状态机文案与来源面板
    const plugins = mountPlugins();
    expect(plugins.state.DISCOVERY_LABEL).toMatchObject({ QUEUED: '已排队', RUNNING: '发现中', SUCCEEDED: '成功', FAILED: '失败' });
    // MCP 页：失败类别文案齐全（连通测试返回具体失败类别的展示口径）
    const mcp = mountMcp();
    expect(Object.keys(mcp.state.FAILURE_LABEL).sort()).toEqual(['AUTH', 'ENDPOINT_UNREACHABLE', 'PROTOCOL', 'TIMEOUT']);
  });

  it('记忆页：列表→正文→编辑（CAS 表单）→DRAFT 审批走 confirmDanger', async () => {
    const page = mountMemories();
    await page.state.reload();
    await flush();
    expect(page.state.rows.length).toBeGreaterThanOrEqual(4);
    // 单条正文查看（ACTIVE 走单条读取）
    const active = page.state.rows.find((r: any) => r.status === 'ACTIVE');
    await page.state.view(active);
    expect(page.state.viewing).toMatchObject({ memoryId: active.memoryId });
    expect(page.state.viewing.content).toBeTruthy();
    // DRAFT 审批：confirmDanger 确认后迁移，列表与 DRAFT 计数同刷
    const draft = page.state.rows.find((r: any) => r.status === 'DRAFT');
    await page.state.decideDraft(draft, 'ACTIVE');
    expect(page.confirmDanger).toHaveBeenCalledWith(expect.objectContaining({ title: '确认草稿生效' }));
    expect(page.state.draftCount).toBe(1);
    // 拒绝分支
    const rest = page.state.rows.find((r: any) => r.status === 'DRAFT');
    await page.state.decideDraft(rest, 'REVOKED');
    expect(page.state.draftCount).toBe(0);
    expect(page.confirmDanger).toHaveBeenLastCalledWith(expect.objectContaining({ title: '拒绝并撤销草稿' }));
  });

  it('MCP 页：测试/发现/快照差异/技能草稿入口按连接行驱动（批次串行提示）', async () => {
    const page = mountMcp();
    await page.state.reload();
    await flush();
    const row = page.state.rows.find((c: any) => c.id === 'mcp-2');
    await page.state.testConnection(row);
    expect(page.toast.success).toHaveBeenCalledWith(expect.stringContaining('连通正常'));
    await page.state.discover(row);
    expect(page.toast.info).toHaveBeenCalledWith(expect.stringContaining('发现批次'));
    expect(page.toast.success).toHaveBeenCalledWith(expect.stringContaining('目录版本 batch_no='));
    page.state.openDiff(row);
    expect(page.state.diffTools.length).toBeGreaterThan(0);
    expect(page.state.diffResult!.added.length).toBeGreaterThan(0);
    // 审核生成技能草稿
    await page.state.reviewTool(page.state.diffResult!.added[0]);
    expect(page.state.draftRefOf(page.state.diffResult!.added[0].toolName)).toMatchObject({ code: expect.any(String) });
  });
});

describe('确认中心四类待办矩阵（前端稿 §5.9）', () => {
  it('四类 Tab 齐全：来源查询形状待办行（发布评审只读口径/计划确认按修订/DRAFT 记忆含正文）', async () => {
    const page = mountApprovals();
    await page.state.load();
    await flush();
    expect(page.state.TABS.map((t: any) => t.key)).toEqual(['publish', 'highRisk', 'plan', 'draftMemory']);
    // 发布评审：PENDING 形状（publishId 1:1 releaseId 的待审视图）；APPROVER 只读一粒
    expect(page.state.publishRows).toHaveLength(2);
    expect(page.state.publishRows[0]).toMatchObject({ status: 'PENDING', precheckSummary: expect.any(String) });
    expect(page.state.publishRows.find((r: any) => r.publishId === 'pub-302')).toMatchObject({ readonly: true, canApprove: false });
    // 高危操作：runId+nodeId 决策入口（双主体链，不经计划确认）
    expect(page.state.highRiskRows).toHaveLength(1);
    expect(page.state.highRiskRows[0]).toMatchObject({ runId: 'run-demo-551', nodeId: 'node-delete-cache' });
    // 计划确认：以修订为单位，仅无有效凭据修订（task-demo-78 UNCONFIRMED）
    expect(page.state.planRows).toHaveLength(1);
    expect(page.state.planRows[0]).toMatchObject({
      taskId: 'task-demo-78', planId: 'plan-9', confirmState: 'UNCONFIRMED', digest: 'cd02',
    });
    // DRAFT 记忆：两粒（含正文预览），处理角色=共享层 ADMIN / 私有层来源用户
    expect(page.state.draftRows).toHaveLength(2);
    expect(page.state.draftRows.every((r: any) => typeof r.content === 'string')).toBe(true);
    // 处理角色口径：只读行不渲染提交按钮（页面 canApprove 分支）
    expect(page.state.publishRows.every((r: any) => typeof r.canApprove === 'boolean')).toBe(true);
  });

  it('处置动作：发布评审批准/拒绝、高危双主体、计划确认跳 plan-detail、DRAFT 记忆 ACTIVE/REVOKED', async () => {
    const page = mountApprovals();
    await page.state.load();
    await flush();
    // 发布评审批准（ADMIN 口径行）
    await page.state.decidePublish(page.state.publishRows[0], 'APPROVE');
    expect(page.confirmDanger).toHaveBeenCalledWith(expect.objectContaining({ title: '批准发布' }));
    expect(page.state.publishRows).toHaveLength(1); // 只剩只读行
    // 只读行提交被拒（APPROVER 不提前放开）
    expect(matrix.decidePublishRequest('pub-302', 'APPROVE'))
      .toMatchObject({ error: 'PUBLISH_DECISION_DENIED', httpStatus: 403 });
    // 高危操作确认
    await page.state.decideHighRisk(page.state.highRiskRows[0], 'APPROVE');
    expect(page.confirmDanger).toHaveBeenCalledWith(expect.objectContaining({ title: '批准执行' }));
    expect(page.state.highRiskRows).toHaveLength(0);
    // 计划确认：跳计划详情（管理台只读指路）
    page.state.gotoPlan(page.state.planRows[0]);
    expect(page.routerPush).toHaveBeenCalledWith('/plans/plan-9');
    // DRAFT 记忆：通过→ACTIVE、拒绝→REVOKED
    await page.state.decideDraft(page.state.draftRows[0], 'ACTIVE');
    expect(page.confirmDanger).toHaveBeenCalledWith(expect.objectContaining({ title: '确认草稿生效' }));
    expect(page.state.draftRows).toHaveLength(1);
    await page.state.decideDraft(page.state.draftRows[0], 'REVOKED');
    expect(page.confirmDanger).toHaveBeenLastCalledWith(expect.objectContaining({ title: '拒绝并撤销草稿' }));
    expect(page.state.draftRows).toHaveLength(0);
  });

  it('角标一致性：计数=四类未分页总数之和（total=Σ），处置后同刷（notify 触发订阅）', async () => {
    // 初值与四类列表一致：2+1+1+2=6
    const initial = await counts.fetchMockConfirmationCounts();
    expect(initial).toMatchObject({
      demo: true, publishReview: 2, highRisk: 1, planConfirmation: 1, draftMemory: 2, total: 6,
    });
    const listener = vi.fn();
    const off = counts.onConfirmationCountsChanged(listener);
    // 页面处置一枚 DRAFT 记忆 + 一条发布评审 → 列表与角标同源收敛
    const page = mountApprovals();
    await page.state.load();
    await flush();
    await page.state.decideDraft(page.state.draftRows[0], 'ACTIVE');
    await page.state.decidePublish(page.state.publishRows.find((r: any) => r.canApprove)!, 'APPROVE');
    const after = await counts.fetchMockConfirmationCounts();
    expect(after).toMatchObject({ publishReview: 1, highRisk: 1, planConfirmation: 1, draftMemory: 1, total: 4 });
    expect(listener).toHaveBeenCalled();
    off();
    // 页面 Tab 计数与角标逐项一致（未分页总数口径）
    await page.state.load();
    expect(page.state.tabCount('publish')).toBe(after.publishReview);
    expect(page.state.tabCount('highRisk')).toBe(after.highRisk);
    expect(page.state.tabCount('plan')).toBe(after.planConfirmation);
    expect(page.state.tabCount('draftMemory')).toBe(after.draftMemory);
    expect(page.state.totalCount).toBe(after.total);
  });

  it('矩阵与角标锚点：§5.9 端点形状注释与 AppShell 订阅登记（源码断言）', () => {
    const approvalsSrc = read('admin/src/pages/ApprovalsPage.vue');
    expect(approvalsSrc).toContain('listPendingPublishRequests');
    expect(approvalsSrc).toContain('listPendingHighRiskApprovals');
    expect(approvalsSrc).toContain('listPlanConfirmationTodos');
    expect(approvalsSrc).toContain('listDraftMemoryTodos');
    expect(approvalsSrc).toContain('notifyConfirmationCountsChanged');
    // 处理角色口径：只读（APPROVER 视角）与 ADMIN 审批双标记
    expect(approvalsSrc).toContain('只读（APPROVER 视角）');
    expect(approvalsSrc).toContain('ADMIN 审批');
    // 计划确认跳计划详情；DRAFT 记忆 ACTIVE/REVOKED
    expect(approvalsSrc).toContain('/plans/${encodeURIComponent(row.planId)}');
    expect(approvalsSrc).toContain("decideDraft(row, 'ACTIVE')");
    expect(approvalsSrc).toContain("decideDraft(row, 'REVOKED')");
    // AppShell：订阅角标变更（处置后不等 15s 轮询立即重读）
    const shellSrc = read('admin/src/AppShell.vue');
    expect(shellSrc).toContain('onConfirmationCountsChanged');
  });

  it('注册锚点：四路由+四 page-access 条目追加式（§3.1 分组：规则→构建资产，其余→集成与治理）', () => {
    const routerSrc = read('admin/src/router.ts');
    for (const [path, key] of [
      ['/assets/memories', 'memories'],
      ['/assets/rules', 'rules'],
      ['/assets/plugins', 'plugins'],
      ['/assets/mcp', 'mcp'],
    ] as const) {
      expect(routerSrc).toContain(`path: "${path}"`);
      expect(routerSrc).toContain(`name: "${key}"`);
      expect(routerSrc).toContain(`access: "${key}"`);
    }
    // 追加式：既有条目位置不重排（apps 在 approvals 之前等既有序断言抽查）
    const accessSrc = read('admin/src/page-access.ts');
    expect(accessSrc.indexOf('key: "skills"')).toBeLessThan(accessSrc.indexOf('key: "rules"'));
    expect(accessSrc.indexOf('key: "integration-console"')).toBeLessThan(accessSrc.indexOf('key: "plugins"'));
    expect(accessSrc.indexOf('key: "plugins"')).toBeLessThan(accessSrc.indexOf('key: "mcp"'));
    expect(accessSrc.indexOf('key: "mcp"')).toBeLessThan(accessSrc.indexOf('key: "memories"'));
    // 挂起线仍在菜单外、四新页均在菜单内
    for (const key of ['memories', 'rules', 'plugins', 'mcp']) {
      expect(accessSrc).toContain(`key: "${key}"`);
    }
    expect(accessSrc.match(/key: "memories", path: "\/assets\/memories", title: "记忆管理", area: "集成与治理"/)).toBeTruthy();
    expect(accessSrc.match(/key: "rules", path: "\/assets\/rules", title: "规则管理", area: "构建资产"/)).toBeTruthy();
  });
});
