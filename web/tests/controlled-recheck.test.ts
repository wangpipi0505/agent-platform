// R06（SCN-009）：重检controlledCallId必须在提交边界形成正安全整数JSON number；
// ElInput字符串、自动重检与非法输入行为均在此锁定。后端严格校验保持不变。
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { compileScript, parse } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as Vue from 'vue';
import { afterEach, expect, it, vi } from 'vitest';
import * as controlled from '../shared/controlled-test';
import * as serviceAccess from '../shared/service-access';
import * as artifactUpload from '../shared/artifact-upload';

type ApiCall = { path: string; method: string; body?: unknown };
const cleanup: (() => void)[] = [];
afterEach(() => { cleanup.splice(0).forEach(close => close()); vi.unstubAllGlobals(); });
async function flush() {
  for (let index = 0; index < 12; index++) { await Promise.resolve(); await Vue.nextTick(); }
}

/** 与现有页面测试相同：真实SFC setup/生命周期，只替换HTTP与子组件。 */
function mountResource(statusBody: Record<string, unknown>) {
  const calls: ApiCall[] = [];
  const api = vi.fn(async (path: string, method = 'GET', body?: unknown) => {
    calls.push({ path, method, body });
    if (path === '/admin/skills/sk_recheck' && method === 'GET')
      return { code: 'sk_recheck', versions: [{ versionNo: 1, status: 'DRAFT' }] };
    if (path === '/admin/model-entries') return [];
    if (path === '/admin/debug-runs/r_status/status') return statusBody;
    return {};
  });
  const { descriptor, errors } = parse(readFileSync(new URL('../admin/src/ResourceDetail.vue', import.meta.url), 'utf8'));
  expect(errors).toEqual([]);
  const code = ts.transpileModule(compileScript(descriptor, { id: 'ResourceDetailRecheck' }).content,
    { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  const exports = {} as { default: Vue.Component & { render: () => null } }, require = createRequire(import.meta.url);
  const auth = { token: 't', user: { tenantId: 'tenant', principalId: 'operator' } };
  new Function('require', 'exports', code)((id: string) => id === 'vue' ? Vue
    : id.endsWith('/shared/api') ? { useApi: () => api, useAuth: () => auth }
    : id.endsWith('/shared/controlled-test') ? controlled
    : id.endsWith('/shared/service-access') ? serviceAccess
    : id.endsWith('/shared/artifact-upload') ? artifactUpload
    : id.endsWith('.vue') ? { default: { render: () => null } }
    : require(id), exports);
  exports.default.render = () => null;
  const renderer = Vue.createRenderer<object, object>({
    createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null,
    createElement: () => ({}), createText: () => ({}), setText() {}, setElementText() {}, patchProp() {},
  });
  const app = renderer.createApp({ render: () => Vue.h(exports.default, { resource: { code: 'sk_recheck', name: '重检样例' }, kind: 'skill' }) });
  const instance = app.mount({}).$ as unknown as { subTree: { component: { setupState: any } } };
  cleanup.push(() => app.unmount());
  return { state: instance.subTree.component!.setupState as any, calls };
}

it('手动重检把ElInput字符串在提交边界转成正整数JSON number再发送', async () => {
  const page = mountResource({});
  await flush();
  page.state.controlledCallId = '3';
  await page.state.execute('recheck');
  const post = page.calls.find(c => c.method === 'POST' && c.path === '/admin/skills/sk_recheck/drafts/1/connection-test');
  expect(post).toBeTruthy();
  expect(typeof post!.body.controlledCallId).toBe('number');
  expect(post!.body).toEqual({ controlledCallId: 3 });
  expect(page.state.error).toBe('');
});

it('自动重检沿用真实成功DEBUG的callId并同样发送number', async () => {
  const page = mountResource({ runId: 'r_status', status: 'SUCCEEDED', controlledCallId: 36 });
  await flush();
  page.state.testRun = { runId: 'r_status' };
  await page.state.checkTest();
  const post = page.calls.find(c => c.method === 'POST' && c.path.endsWith('/connection-test'));
  expect(post).toBeTruthy();
  expect(typeof post!.body.controlledCallId).toBe('number');
  expect(post!.body).toEqual({ controlledCallId: 36 });
});

it('空、非数字、零、负数、小数与超安全整数均提示且零请求', async () => {
  const page = mountResource({});
  await flush();
  const before = page.calls.length;
  for (const bad of ['', 'abc', '0', '-1', '1.5', '3.0', '9007199254740993']) {
    page.state.controlledCallId = bad;
    await page.state.execute('recheck');
    expect(String(page.state.error)).toContain('正整数');
    expect(page.calls.length).toBe(before);
  }
});
