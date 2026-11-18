// F3-1（台账 §7.5 / 方案 §5.3 管理台侧）：任务工作台——数据面=M5-2 切真实 API（shared/api-task）。
// 锁定：①状态×操作矩阵渲染（每状态按钮集=服务端 availableActions 逐项映射，不自判）；
// ②状态管道 chips 过滤（13 态）；③恢复面板 recovery 角色投影（USER 三字段口径+OPERATOR 附加分区有则显示）；
// ④页面无新建任务入口（任务发起在助手端 F4-2）；⑤取消/恢复/对账处置入口走 confirmDanger→api-task 端点函数。
// 页面装载：真实 SFC setup（compileScript+transpile），按依赖替换为受控桩，setupState 断言。
// api-task 桩异步委托 mock-task demo 注册表（mock-task 文件保留为演示/测试引擎，断言语义与基底一致）。
import { readFileSync } from 'node:fs';
import { compileScript, parse } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as Vue from 'vue';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as mockTask from '../shared/mock-task';
import * as reasonCodeText from '../shared/reason-code-text';
import { useLiveList } from '../shared/hooks/useLiveList';

/** 页面数据面桩：api-task 异步形状，事实源=mock-task demo 注册表（断言语义与基底一致）。 */
const apiTaskStub = {
  callActions: mockTask.callActions,
  listTasks: async () => mockTask.listTasks(),
  listTaskDetail: async (taskId: string) => mockTask.listTaskDetail(taskId),
  cancelTask: async (req: { taskId: string }) => mockTask.cancelTask(req),
  resumeTask: async (req: { taskId: string }) => mockTask.resumeTask(req),
  reconcileTaskCall: async (req: { taskId: string; stepId: string; callId: string; action: string }) =>
    mockTask.reconcileTaskCall({ ...req, action: req.action as never }),
};

const cleanup: (() => void)[] = [];
afterEach(() => {
  cleanup.splice(0).forEach((close) => close());
  vi.restoreAllMocks();
});
beforeEach(() => {
  mockTask.resetTaskConsoleDemo();
});

async function flush() {
  for (let index = 0; index < 10; index++) {
    await Promise.resolve();
    await Vue.nextTick();
  }
}

/** 真实 TasksPage setup：模板不编译（渲染桩），依赖按 ID 显式替换。 */
function mountTasks(options: { confirmResult?: boolean } = {}) {
  const toast = {
    success: vi.fn(),
    info: vi.fn(),
    error: vi.fn(),
  };
  const confirmDanger = vi.fn(
    async (_options?: { title?: string; message?: string; confirmText?: string }) =>
      options.confirmResult ?? true,
  );
  const { descriptor, errors } = parse(
    readFileSync(new URL('../admin/src/pages/TasksPage.vue', import.meta.url), 'utf8'),
  );
  expect(errors).toEqual([]);
  const code = ts.transpileModule(
    compileScript(descriptor, { id: 'TasksPageTest' }).content,
    { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } },
  ).outputText;
  const exports = {} as { default: Vue.Component & { render: () => void } };
  const requireStub = (id: string) => {
    if (id === 'vue') return Vue;
    if (id === 'element-plus') return { ElMessage: toast };
    // 子组件（DataPage/EmptyState/ErrorBanner/PageHeader）：本组测 setup 逻辑，渲染桩。
    if (id.endsWith('.vue')) return { default: { render: () => null } };
    if (id.includes('DangerConfirm')) return { confirmDanger };
    if (id.includes('usePageOperation')) return { usePageTitle: () => {} };
    if (id.includes('useLiveList')) return { useLiveList };
    if (id.includes('api-task')) return apiTaskStub;
    // R-3：纯函数模块用真实实现（无外部依赖）。
    if (id.includes('reason-code-text')) return reasonCodeText;
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
  return {
    state: instance.subTree.component!.setupState as any,
    toast,
    confirmDanger,
    async reload() {
      await instance.subTree.component!.setupState.reload();
      await flush();
    },
  };
}

const ALL_STATES = [
  'ACCEPTED', 'PLANNING', 'PLAN_READY', 'NEEDS_INPUT', 'WAITING_DEPENDENCIES', 'QUEUED',
  'RUNNING', 'WAITING_APPROVAL', 'WAITING_EXTERNAL', 'WAITING_RECONCILIATION',
  'SUCCEEDED', 'FAILED', 'CANCELLED',
] as const;
// 危险动作=confirmDanger 确认通道（取消/恢复/对账处置）；只读/导航类走 demo 提示。
const DANGEROUS = new Set(['CANCEL', 'RESUME', 'RETRY', 'ADOPT_EXTERNAL', 'DISCLOSE']);

describe('任务工作台 F3-1（方案 §5.3，mock 驱动）', () => {
  it('矩阵渲染①：13 态按钮集逐项等于服务端 availableActions——不增删不自判，文案齐全，危险态只落处置动作', () => {
    const page = mountTasks();
    for (const state of ALL_STATES) {
      const actions = mockTask.availableActions(state);
      const buttons = page.state.actionButtons(actions);
      expect(buttons.map((btn: { action: string }) => btn.action), `状态 ${state}`).toEqual([...actions]);
      for (const btn of buttons) {
        expect(btn.label, `${state}/${btn.action} 文案`).toBeTruthy();
        expect(btn.danger, `${state}/${btn.action} 危险态`).toBe(DANGEROUS.has(btn.action));
      }
    }
    // 抽查契约关键行（contracts/task-states.md §3）：等待类/运行/终态
    expect(page.state.actionButtons(mockTask.availableActions('RUNNING')).map((b: any) => b.label)).toEqual(['取消']);
    expect(page.state.actionButtons(mockTask.availableActions('WAITING_DEPENDENCIES')).map((b: any) => b.label))
      .toEqual(['查看依赖链', '取消']);
    expect(page.state.actionButtons(mockTask.availableActions('WAITING_APPROVAL')).map((b: any) => b.label))
      .toEqual(['去确认', '取消']);
    expect(page.state.actionButtons(mockTask.availableActions('SUCCEEDED')).map((b: any) => b.label))
      .toEqual(['重新发起', '导出结果']);
  });

  it('矩阵渲染②：服务端投影分支如实呈现——对账按核对结果出 RETRY（绑定 callId）、恢复资格叠加 RESUME（非自判）', () => {
    const page = mountTasks();
    const recon = mockTask.listTaskDetail('task-demo-91')!;
    // 服务端投影：未决调用含可查证"未受理"→ reconcilable 分支 + resumable 事实叠加 RESUME（前端不计算，只渲染）
    expect(recon.availableActions).toEqual(['TRACK_EXTERNAL', 'ADOPT_EXTERNAL', 'RETRY', 'CANCEL', 'RESUME']);
    const pageButtons = page.state.actionButtons(recon.availableActions).map((b: any) => b.action);
    expect(pageButtons).toEqual(['TRACK_EXTERNAL', 'ADOPT_EXTERNAL', 'RETRY', 'CANCEL', 'RESUME']);
    // callId 级动作：仅 UNRECEIVED 出 RETRY；RECEIVED 只查证/采纳；UNKNOWN 不可核实→披露
    expect(mockTask.callActions(recon.pendingCalls[0])).toEqual(['TRACK_EXTERNAL', 'ADOPT_EXTERNAL', 'RETRY']);
    expect(mockTask.callActions(recon.pendingCalls[1])).toEqual(['TRACK_EXTERNAL', 'ADOPT_EXTERNAL']);
    expect(mockTask.callActions({ verified: 'UNKNOWN' })).toEqual(['TRACK_EXTERNAL', 'DISCLOSE']);
    expect(recon.pendingCalls[0].callId).toBe('call-9f1');
    // 等待外部 + 恢复资格 → RESUME 叠加；终态 → 只读导出
    expect(mockTask.listTaskDetail('task-demo-92')!.availableActions).toEqual(['VIEW_EXTERNAL', 'CANCEL', 'RESUME']);
    expect(mockTask.listTaskDetail('task-demo-93')!.availableActions).toEqual(['REISSUE', 'EXPORT']);
  });

  it('状态管道 chips：13 态管道+点击过滤+再次点击清除，过滤结果与所选状态一致', async () => {
    const page = mountTasks();
    await page.reload();
    expect(page.state.pipeline).toHaveLength(13);
    const labels = page.state.pipeline.map(([, label]: [string, string]) => label);
    expect(new Set(labels).size).toBe(13);
    for (const wanted of ['排队', '运行', '等待确认', '等待外部', '等待对账', '成功', '失败'])
      expect(labels, `管道缺「${wanted}」chip`).toContain(wanted);
    expect(page.state.activeState).toBe('');
    expect(page.state.rows).toHaveLength(5);
    expect(page.state.filteredRows).toHaveLength(5);
    // 点击「等待对账」→ 只剩 task-demo-91；点击「失败」→ 空（走 filtered 空态）
    page.state.setFilter('WAITING_RECONCILIATION');
    expect(page.state.activeState).toBe('WAITING_RECONCILIATION');
    expect(page.state.filteredRows.map((t: any) => t.taskId)).toEqual(['task-demo-91']);
    page.state.setFilter('FAILED');
    expect(page.state.filteredRows).toHaveLength(0);
    // 再次点击同一 chip → 清除过滤回到全量
    page.state.setFilter('FAILED');
    expect(page.state.activeState).toBe('');
    expect(page.state.filteredRows).toHaveLength(5);
    // 「全部」chip 任何时刻可复位
    page.state.setFilter('RUNNING');
    page.state.setFilter('');
    expect(page.state.filteredRows).toHaveLength(5);
  });

  it('恢复面板：recovery 角色投影 USER 三字段（来源/原因码/摘要）+ OPERATOR 附加分区有则显示、无则不渲染', async () => {
    const page = mountTasks();
    await page.reload();
    const row92 = page.state.rows.find((t: any) => t.taskId === 'task-demo-92');
    await page.state.toggle(row92);
    await flush();
    const detail92 = page.state.details.get('task-demo-92');
    expect(detail92).toBeTruthy();
    const view = page.state.recoveryView(detail92);
    // USER 三字段口径
    expect(view.source).toBe('AUTO');
    expect(view.sourceLabel).toBe('自动恢复');
    expect(view.reasonCode).toBe('LEASE_EXPIRED');
    expect(view.summary).toContain('检查点');
    // OPERATOR 附加分区（管理台视角，有则显示）
    expect(view.checkpoint).toEqual({ eventWatermark: 14, runRefs: ['run-a1'] });
    expect(view.reusedSteps.map((s: any) => s.stepId)).toEqual(['detect_a']);
    expect(view.blockedReasons).toEqual(['外部任务 EXT-77 回执未决']);
    // recovery 为空如实投影为 null（面板不渲染），附加字段缺失时给空集不伪造
    expect(page.state.recoveryView({ recovery: null })).toBeNull();
    expect(page.state.recoveryView(null)).toBeNull();
    const minimal = page.state.recoveryView({
      recovery: { recoverySource: 'MANUAL', reasonCode: 'MANUAL_REQUEST', reasonSummary: '手动恢复' },
    });
    expect(minimal).toMatchObject({ sourceLabel: '手动恢复', reasonCode: 'MANUAL_REQUEST', summary: '手动恢复' });
    expect(minimal.checkpoint).toBeNull();
    expect(minimal.reusedSteps).toEqual([]);
    expect(minimal.blockedReasons).toEqual([]);
    // 手动恢复成功后投影切换为 MANUAL（同一恢复入口，来源来自恢复事实）
    await page.state.onAction(row92, 'RESUME');
    await page.reload();
    const resumed = mockTask.listTaskDetail('task-demo-92')!;
    expect(resumed.state).toBe('RUNNING');
    expect(resumed.recovery?.recoverySource).toBe('MANUAL');
    expect(mockTask.listTaskDetail('task-demo-91')!.recovery).toBeNull();
  });

  it('无新建任务入口：页面不含"新建任务"文案，所有矩阵按钮/管道 chips 文案均无创建类入口', () => {
    const source = readFileSync(new URL('../admin/src/pages/TasksPage.vue', import.meta.url), 'utf8');
    expect(source).not.toContain('新建任务');
    expect(source).toContain('variant="filtered"');
    expect(source).not.toContain('variant="create"');
    const page = mountTasks();
    const labels = new Set<string>();
    for (const state of ALL_STATES)
      for (const btn of page.state.actionButtons(mockTask.availableActions(state))) labels.add(btn.label);
    for (const [, label] of page.state.pipeline as [string, string][]) labels.add(label);
    labels.add('确认计划');
    labels.add('任务树与详情');
    for (const label of labels) expect(label).not.toContain('新建');
  });

  it('取消入口：confirmDanger 确认后调 mock cancelTask，列表刷新如实落到 CANCELLED', async () => {
    const page = mountTasks({ confirmResult: true });
    await page.reload();
    const row77 = page.state.rows.find((t: any) => t.taskId === 'task-demo-77');
    await page.state.onAction(row77, 'CANCEL');
    expect(page.confirmDanger).toHaveBeenCalledTimes(1);
    expect(page.confirmDanger.mock.calls[0][0]).toMatchObject({ title: '取消任务' });
    expect(mockTask.listTaskDetail('task-demo-77')!.state).toBe('CANCELLED');
    expect(page.toast.success).toHaveBeenCalled();
    await page.reload();
    expect(page.state.rows.find((t: any) => t.taskId === 'task-demo-77')!.state).toBe('CANCELLED');
  });

  it('恢复入口：资格成立→RUNNING+MANUAL 投影；自动租约在持→pending:true 如实呈现"恢复中"不改状态', async () => {
    const page = mountTasks({ confirmResult: true });
    await page.reload();
    const row91 = page.state.rows.find((t: any) => t.taskId === 'task-demo-91');
    const row92 = page.state.rows.find((t: any) => t.taskId === 'task-demo-92');
    // 先到先得：自动恢复持租约 → pending，状态不动，卡片挂"恢复中"
    await page.state.onAction(row91, 'RESUME');
    expect(page.toast.info).toHaveBeenCalled();
    expect(mockTask.listTaskDetail('task-demo-91')!.state).toBe('WAITING_RECONCILIATION');
    expect(page.state.resumePending.has('task-demo-91')).toBe(true);
    // 资格成立：RUNNING + TASK_RESUMED 投影（MANUAL/MANUAL_REQUEST）
    await page.state.onAction(row92, 'RESUME');
    expect(page.confirmDanger.mock.calls.some(([opts]) => (opts as any).title === '恢复任务')).toBe(true);
    const resumed = mockTask.listTaskDetail('task-demo-92')!;
    expect(resumed.state).toBe('RUNNING');
    expect(resumed.recovery).toMatchObject({ recoverySource: 'MANUAL', reasonCode: 'MANUAL_REQUEST' });
    expect(resumed.events.some((e) => e.type === 'TASK_RESUMED' && e.data.recoverySource === 'MANUAL')).toBe(true);
  });

  it('对账处置：RETRY 绑定 callId 走 demo 对账函数；证据不符 409 时报错且状态/未决调用不变', async () => {
    const page = mountTasks({ confirmResult: true });
    await page.reload();
    const row91 = page.state.rows.find((t: any) => t.taskId === 'task-demo-91');
    const callUnreceived = mockTask.listTaskDetail('task-demo-91')!.pendingCalls[0];
    await page.state.onAction(row91, 'RETRY', callUnreceived);
    expect(page.confirmDanger.mock.calls.at(-1)![0]).toMatchObject({ title: '对账处置' });
    expect(String(page.confirmDanger.mock.calls.at(-1)![0]!.message)).toContain('call-9f1');
    expect(mockTask.listTaskDetail('task-demo-91')!.state).toBe('RUNNING');
    expect(mockTask.listTaskDetail('task-demo-91')!.pendingCalls.map((c) => c.callId)).toEqual(['call-9f2']);
    expect(page.toast.success).toHaveBeenCalled();
    // 证据复核不符：RECEIVED 调用 RETRY → 409 RECONCILIATION_EVIDENCE_CONFLICT，前端如实报错不改状态
    mockTask.resetTaskConsoleDemo();
    await page.reload();
    const row91b = page.state.rows.find((t: any) => t.taskId === 'task-demo-91');
    const callReceived = mockTask.listTaskDetail('task-demo-91')!.pendingCalls.find((c) => c.callId === 'call-9f2')!;
    await page.state.onAction(row91b, 'RETRY', callReceived);
    expect(page.toast.error.mock.calls.at(-1)![0]).toContain('对账证据复核不符');
    expect(mockTask.listTaskDetail('task-demo-91')!.state).toBe('WAITING_RECONCILIATION');
    expect(mockTask.listTaskDetail('task-demo-91')!.pendingCalls).toHaveLength(2);
  });

  it('确认拦截与只读动作：confirmDanger 拒绝→不动状态不提示成功；只读动作给 demo 说明不伪造结果', async () => {
    const page = mountTasks({ confirmResult: false });
    await page.reload();
    const row77 = page.state.rows.find((t: any) => t.taskId === 'task-demo-77');
    await page.state.onAction(row77, 'CANCEL');
    expect(mockTask.listTaskDetail('task-demo-77')!.state).toBe('RUNNING');
    expect(page.toast.success).not.toHaveBeenCalled();
    await page.state.onAction(row77, 'VIEW_DEPENDENCIES');
    expect(page.toast.info).toHaveBeenCalled();
    expect(page.toast.success).not.toHaveBeenCalled();
    expect(mockTask.listTaskDetail('task-demo-77')!.state).toBe('RUNNING');
  });
});
