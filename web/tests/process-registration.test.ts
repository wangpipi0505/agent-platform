// R10（SCN-004）：通用PROCESS登记页面——类型选项/按类型清理不相容字段/process对象JSON登记；
// 契约校验以服务端ProcessContract.parse为准（ProcessContractTest），本测试锁定提交形状与入口行为。
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { compileScript, parse } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as Vue from 'vue';
import { afterEach, expect, it, vi } from 'vitest';
import * as schemaDialect from '../shared/schema-dialect';

const cleanup: (() => void)[] = [];
afterEach(() => { cleanup.splice(0).forEach(close => close()); vi.unstubAllGlobals(); });
async function flush() {
  for (let index = 0; index < 12; index++) { await Promise.resolve(); await Vue.nextTick(); }
}

function initial(type: string) {
  return {
    code: 'process_demo', name: '本地算法', type, description: '受控进程',
    endpoint: 'https://old.example.test', authRef: 'vault:legacy', toolName: 'stale',
    inputSchema: { type: 'object' }, outputSchema: { type: 'object' },
    execution: { readonly: true, sideEffect: false, idempotent: true, cancellable: false },
    guard: { roles: [], attributes: {} }, timeoutMs: 30000,
  };
}

/** 与现有页面测试相同：真实SFC setup/生命周期，只替换子组件与共享模块。 */
function mountWizard(type: string) {
  const saved: any[] = [];
  const { descriptor, errors } = parse(readFileSync(new URL('../admin/src/SkillWizard.vue', import.meta.url), 'utf8'));
  expect(errors).toEqual([]);
  const code = ts.transpileModule(compileScript(descriptor, { id: 'SkillWizardProcess' }).content,
    { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  const exports = {} as { default: Vue.Component & { render: () => null } }, require = createRequire(import.meta.url);
  new Function('require', 'exports', code)((id: string) => id === 'vue' ? Vue
    : id.endsWith('/schema-dialect') ? schemaDialect
    : id.endsWith('.vue') ? { default: { render: () => null } }
    : require(id), exports);
  exports.default.render = () => null;
  const renderer = Vue.createRenderer<object, object>({
    createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null,
    createElement: () => ({}), createText: () => ({}), setText() {}, setElementText() {}, patchProp() {},
  });
  const app = renderer.createApp({
    render: () => Vue.h(exports.default, {
      initial: initial(type),
      onSave: (value: any) => saved.push(value),
      onOpenImported: () => {},
    }),
  });
  const instance = app.mount({}).$ as unknown as { subTree: { component: { setupState: any } } };
  cleanup.push(() => app.unmount());
  return { state: instance.subTree.component!.setupState as any, saved };
}

const validProcess = {
  command: ['C:/apps/worker/algorithm-worker.jar'],
  workDirRoot: 'C:/apps/worker/runs',
  timeoutMs: 120000,
};

it('切换到PROCESS清除endpoint/httpMapping/toolName/authRef，切回HTTP清除process', async () => {
  const page = mountWizard('HTTP');
  await flush();
  page.state.value.type = 'PROCESS';
  await flush();
  expect(page.state.value.endpoint).toBeUndefined();
  expect(page.state.value.toolName).toBeUndefined();
  expect(page.state.value.authRef).toBeUndefined();
  page.state.value.process = validProcess;
  page.state.value.type = 'HTTP';
  await flush();
  expect(page.state.value.process).toBeUndefined();
});

it('PROCESS在连接步骤接受合法process对象并走完全流程，最终负载不含endpoint/httpMapping', async () => {
  const page = mountWizard('HTTP');
  await flush();
  page.state.value.type = 'PROCESS';
  await flush();
  // 走到连接步骤（步骤0必填已由initial满足）。
  page.state.next();
  expect(page.state.step).toBe(1);
  page.state.processJson = JSON.stringify(validProcess, null, 2);
  page.state.next();
  expect(page.state.step).toBe(2);
  page.state.next(); // 输入输出（initial自带Schema）
  page.state.next(); // 执行属性
  page.state.next(); // 权限守卫
  expect(page.state.step).toBe(5);
  expect(page.state.value.process).toEqual(validProcess);
  expect(page.state.value.endpoint).toBeUndefined();
  expect(page.state.value.httpMapping).toBeUndefined();
  expect(page.state.value.authRef).toBeUndefined();
  expect(page.state.value.toolName).toBeUndefined();
  page.state.emit('save', page.state.value);
  expect(page.saved).toHaveLength(1);
  expect(page.saved[0].type).toBe('PROCESS');
});

it('process子契约非法JSON与非对象都在连接步骤拒绝且不前进', async () => {
  for (const bad of ['{not-json', '["command"]', 'null']) {
    const page = mountWizard('HTTP');
    await flush();
    page.state.value.type = 'PROCESS';
    await flush();
    page.state.next();
    page.state.processJson = bad;
    page.state.next();
    expect(page.state.step).toBe(1);
    expect(String(page.state.error)).toContain('process');
  }
});

it('HTTP原入口有效：endpoint缺失仍在连接步骤拒绝，MCP工具名校验保持', async () => {
  const page = mountWizard('HTTP');
  await flush();
  page.state.value.endpoint = '';
  page.state.next(); // 先到连接步骤
  page.state.next(); // 从连接步骤前进触发endpoint校验
  expect(page.state.step).toBe(1);
  expect(String(page.state.error)).toContain('http/https');
  const mcp = mountWizard('MCP');
  await flush();
  mcp.state.value.endpoint = 'https://mcp.example.test';
  mcp.state.value.toolName = '';
  mcp.state.next();
  mcp.state.next();
  expect(mcp.state.step).toBe(1);
  expect(String(mcp.state.error)).toContain('工具名');
});
