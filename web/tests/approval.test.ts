import {it,expect} from 'vitest';import {decisionPayload} from '../shared/approval';
it('confirmation binds the displayed server snapshot digest and rejects missing or expired snapshots',()=>{expect(decisionPayload({snapshotDigest:'sha',expiresAt:'2099-01-01T00:00:00Z'},true,'r')).toEqual({approved:true,requestId:'r',snapshotDigest:'sha'});expect(()=>decisionPayload({},true,'r')).toThrow();expect(()=>decisionPayload({snapshotDigest:'sha',expiresAt:'2000-01-01T00:00:00Z'},true,'r')).toThrow();});

// PLAT-15（契约 §14）：确认面板展示 actionPlan 的 summary/target/diff/risk，不只是"是否允许"；
// 高危（risk=HIGH）且确认角色不足两个时必须二次确认才能批准。
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { compileScript, parse } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as Vue from 'vue';
import * as approvalModule from '../shared/approval';

const HIGH_PLAN = {
  parameterSnapshot: {
    actionPlan: {
      summary: '创建用户 user-42 并启用',
      target: 'user-42',
      diff: { enable: true },
      risk: 'HIGH',
      idempotencyKey: 'k'.repeat(64),
      expiresAt: '2099-01-01T00:00:00Z',
    },
  },
  confirmRoles: ['APPROVER'],
  snapshotDigest: 'sha',
  expiresAt: '2099-01-01T00:00:00Z',
};

it('actionPlanView extracts plan fields and derives the second-confirm gate', () => {
  const view = approvalModule.actionPlanView(HIGH_PLAN)!;
  expect(view.summary).toBe('创建用户 user-42 并启用');
  expect(view.target).toBe('user-42');
  expect(view.diffText).toContain('enable');
  expect(view.risk).toBe('HIGH');
  expect(view.highRisk).toBe(true);
  expect(view.secondConfirmRequired).toBe(true);
  // 双角色：无需二次确认；普通风险：从不要求；无计划：null（旧一段审批零变化）。
  expect(approvalModule.actionPlanView({ ...HIGH_PLAN, confirmRoles: ['A', 'B'] })!.secondConfirmRequired).toBe(false);
  const standard = approvalModule.actionPlanView({ ...HIGH_PLAN, parameterSnapshot: { actionPlan: { summary: 's', risk: 'STANDARD' } } })!;
  expect(standard.highRisk).toBe(false);
  expect(standard.secondConfirmRequired).toBe(false);
  expect(approvalModule.actionPlanView({ parameterSnapshot: {} })).toBeNull();
  expect(approvalModule.actionPlanView({})).toBeNull();
});

it('approvePayload blocks high-risk approval without double confirm and stamps it when confirmed', () => {
  expect(() => approvalModule.approvePayload(HIGH_PLAN, true, 'r', false)).toThrow(/二次确认/);
  const confirmed = approvalModule.approvePayload(HIGH_PLAN, true, 'r', true);
  expect(confirmed).toEqual({ approved: true, requestId: 'r', snapshotDigest: 'sha', doubleConfirmed: true });
  const twoRoles = approvalModule.approvePayload({ ...HIGH_PLAN, confirmRoles: ['A', 'B'] }, true, 'r', false);
  expect(twoRoles).toEqual({ approved: true, requestId: 'r', snapshotDigest: 'sha' });
  expect(approvalModule.approvePayload({ snapshotDigest: 'sha' }, true, 'r')).toEqual({ approved: true, requestId: 'r', snapshotDigest: 'sha' });
});

function mountPanel(row: any) {
  const { descriptor, errors } = parse(
    readFileSync(new URL('../admin/src/pages/ActionPlanPanel.vue', import.meta.url), 'utf8'),
  );
  expect(errors).toEqual([]);
  const code = ts.transpileModule(compileScript(descriptor, { id: 'ActionPlanPanel' }).content, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const exports: any = {}, require = createRequire(import.meta.url);
  new Function('require', 'exports', code)((id: string) =>
    id === 'vue' ? Vue : id.endsWith('/approval') ? approvalModule : require(id), exports);
  exports.default.render = () => null;
  const renderer = Vue.createRenderer<any, any>({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null, createElement: () => ({}), createText: () => ({}), setText() {}, setElementText() {}, patchProp() {} });
  const props = Vue.reactive({ row: JSON.parse(JSON.stringify(row)) });
  const app = renderer.createApp({ render: () => Vue.h(exports.default, props) });
  const instance = app.mount({}).$ as any;
  return { state: instance.subTree.component.setupState, props };
}

it('confirmation panel exposes the stamped plan (summary/target/diff/risk) and the double-confirm state', () => {
  const panel = mountPanel(HIGH_PLAN);
  expect(panel.state.view.summary).toBe('创建用户 user-42 并启用');
  expect(panel.state.view.target).toBe('user-42');
  expect(panel.state.view.diffText).toContain('enable');
  expect(panel.state.view.risk).toBe('HIGH');
  expect(panel.state.view.secondConfirmRequired).toBe(true);
  panel.state.doubleConfirmed = true;
  expect(panel.state.doubleConfirmed).toBe(true);
  // 无计划的旧审批行：面板隐藏，不渲染任何计划内容。
  const legacy = mountPanel({ parameterSnapshot: {} });
  expect(legacy.state.view).toBeNull();
});
