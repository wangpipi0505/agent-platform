import {it,expect} from 'vitest';import {decisionPayload} from '../shared/approval';
it('confirmation binds the displayed server snapshot digest and rejects missing or expired snapshots',()=>{expect(decisionPayload({snapshotDigest:'sha',expiresAt:'2099-01-01T00:00:00Z'},true,'r')).toEqual({approved:true,requestId:'r',snapshotDigest:'sha'});expect(()=>decisionPayload({},true,'r')).toThrow();expect(()=>decisionPayload({snapshotDigest:'sha',expiresAt:'2000-01-01T00:00:00Z'},true,'r')).toThrow();});

// PLAT-15（契约 §14）：确认面板展示 actionPlan 的 summary/target/diff/risk，不只是"是否允许"；
// 双主体政策（b21e3dca）：客户端二次勾选不再构成第二确认人，批准门移到服务端持久审批事实，
// 旧高危计划（HIGH 且未声明 TWO_PRINCIPALS_V1）必须重新发起调用与 PREPARE。
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

it('actionPlanView extracts plan fields and derives the two-principal policy gates', () => {
  const view = approvalModule.actionPlanView(HIGH_PLAN)!;
  expect(view.summary).toBe('创建用户 user-42 并启用');
  expect(view.target).toBe('user-42');
  expect(view.diffText).toContain('enable');
  expect(view.risk).toBe('HIGH');
  expect(view.highRisk).toBe(true);
  // 旧高危计划：客户端二次勾选已不再是确认门（恒 false），必须重新发起调用与 PREPARE。
  expect(view.secondConfirmRequired).toBe(false);
  expect(view.policy).toBe('LEGACY_DECLARATION');
  expect(view.twoPrincipalsRequired).toBe(false);
  expect(view.requiresReprepare).toBe(true);
  // 显式双主体政策：按服务端持久审批事实计数，不要求客户端二次勾选，也无需重新 PREPARE。
  const twoPrincipals = approvalModule.actionPlanView({ ...HIGH_PLAN, parameterSnapshot: { ...HIGH_PLAN.parameterSnapshot, actionPlan: { ...HIGH_PLAN.parameterSnapshot.actionPlan, approvalPolicy: 'TWO_PRINCIPALS_V1' } } })!;
  expect(twoPrincipals.policy).toBe('TWO_PRINCIPALS_V1');
  expect(twoPrincipals.twoPrincipalsRequired).toBe(true);
  expect(twoPrincipals.requiresReprepare).toBe(false);
  const standard = approvalModule.actionPlanView({ ...HIGH_PLAN, parameterSnapshot: { actionPlan: { summary: 's', risk: 'STANDARD' } } })!;
  expect(standard.highRisk).toBe(false);
  expect(standard.requiresReprepare).toBe(false);
  expect(approvalModule.actionPlanView({ parameterSnapshot: {} })).toBeNull();
  expect(approvalModule.actionPlanView({})).toBeNull();
});

it('approvePayload rejects legacy high-risk plans and submits the own decision under two-principal policy', () => {
  expect(() => approvalModule.approvePayload(HIGH_PLAN, true, 'r', false)).toThrow(/双主体政策/);
  // 客户端勾选不能替代服务端双主体事实：带勾选依旧拒绝。
  expect(() => approvalModule.approvePayload(HIGH_PLAN, true, 'r', true)).toThrow(/双主体政策/);
  const twoPrincipals = approvalModule.approvePayload({ ...HIGH_PLAN, parameterSnapshot: { ...HIGH_PLAN.parameterSnapshot, actionPlan: { ...HIGH_PLAN.parameterSnapshot.actionPlan, approvalPolicy: 'TWO_PRINCIPALS_V1' } } }, true, 'r', true);
  expect(twoPrincipals).toEqual({ approved: true, requestId: 'r', snapshotDigest: 'sha' });
  // 无计划的旧一段审批行：零变化。
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

it('confirmation panel exposes the stamped plan (summary/target/diff/risk) and the two-principal policy state', () => {
  const panel = mountPanel(HIGH_PLAN);
  expect(panel.state.view.summary).toBe('创建用户 user-42 并启用');
  expect(panel.state.view.target).toBe('user-42');
  expect(panel.state.view.diffText).toContain('enable');
  expect(panel.state.view.risk).toBe('HIGH');
  expect(panel.state.view.secondConfirmRequired).toBe(false);
  expect(panel.state.view.requiresReprepare).toBe(true);
  // 无计划的旧审批行：面板隐藏，不渲染任何计划内容。
  const legacy = mountPanel({ parameterSnapshot: {} });
  expect(legacy.state.view).toBeNull();
});
