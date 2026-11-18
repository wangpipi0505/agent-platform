import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { compileScript, parse } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as Vue from 'vue';
import { describe, expect, it, vi } from 'vitest';
import * as assertion from '../shared/assertion';
// NamedInputs.vue 现已直接引用 shared/json-input（JSON 输入解析收敛），夹具同步解析该模块。
import * as jsonInput from '../shared/json-input';

/**
 * 断言编辑器的行为测试。挂载真实 SFC（setup/watchers），只替换 api 与 Element Plus 组件；
 * 与 mcp-registration.test.ts 使用同一套自定义 renderer，避免引入新依赖。
 */
const Stub = { name: 'Stub', props: ['draft', 'safety', 'disabled', 'notice'], render: () => null };

function mount(relative: string, name: string, props: any, api: any = vi.fn(async () => ({}))) {
  const { descriptor, errors } = parse(readFileSync(new URL('../' + relative, import.meta.url), 'utf8'));
  expect(errors).toEqual([]);
  const code = ts.transpileModule(compileScript(descriptor, { id: name }).content, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  const auth = Vue.reactive({ token: 'fixture-one', roles: ['EDITOR'], user: { tenantId: 'tenant-one', principalId: 'owner' }, edit: true, admin: false, can(permission: string) { return !!this.token && permission !== 'admin' && this.edit; } });
  const exports: any = {}, require = createRequire(import.meta.url);
  new Function('require', 'exports', code)((id: string) => id === 'vue' ? Vue
    : id.endsWith('/api') ? { useApi: () => api, useAuth: () => auth }
    : id.endsWith('/assertion') ? assertion
    : id.endsWith('/json-input') ? jsonInput
    : id.endsWith('.vue') ? { default: Stub }
    : require(id), exports);
  exports.default.render = () => null;
  const renderer = Vue.createRenderer<any, any>({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null, createElement: () => ({}), createText: () => ({}), setText() {}, setElementText() {}, patchProp() {} });
  const input = Vue.reactive(props), app = renderer.createApp({ render: () => Vue.h(exports.default, input) });
  const instance = app.mount({}).$ as any;
  return { state: instance.subTree.component.setupState, props: input, api, unmount: () => app.unmount() };
}
async function flush() { await Promise.resolve(); await Vue.nextTick(); await Promise.resolve(); await Vue.nextTick(); }

type Call = [path: string, method: string, body: any];
function fixtureApi(option: { promote?: (body: any) => any; dataset?: (path: string) => any; rejectPromote?: any } = {}) {
  const calls: Call[] = [];
  const fn = vi.fn(async (path: string, method: string, body?: any) => {
    calls.push([path, method, body]);
    if (method === 'GET' && path.startsWith('/admin/debug-inputs?')) return { items: [{ id: 7, name: 'fixture input', revision: 2, inputs: { q: 'x' } }], hasMore: false, nextAfterId: 0 };
    if (method === 'POST' && path === '/admin/debug-inputs/7/promote') { if (option.rejectPromote) throw option.rejectPromote; return option.promote ? option.promote(body) : { datasetVersion: 'v-fixture' }; }
    if (method === 'GET' && path.startsWith('/admin/evaluation-datasets/')) return option.dataset ? option.dataset(path) : { cases: [] };
    throw new Error('unexpected ' + method + ' ' + path);
  });
  return { fn, calls, posts: () => calls.filter(([, method]) => method === 'POST') };
}
function mountNamed(api: any) {
  return mount('debug-panel/NamedInputs.vue', 'NamedInputs', { appCode: 'app-one', draftId: 1 }, api);
}
async function openPromotion(v: ReturnType<typeof mountNamed>) {
  await flush();
  v.state.selected = 7;
  v.state.openPromotion();
  v.state.safety = false;
}

describe('assertion submission shape', () => {
  it('builds the canonical assertion shape for every mode and keeps parameters out of assertion', () => {
    const config = (mode: assertion.AssertionMode, expectedText: string, tolerance = '') => assertion.buildAssertion({ mode, expectedText, tolerance }, false);
    expect(config('EXACT', '{"answer":"ok"}')).toEqual({ expected: { answer: 'ok' }, assertion: 'EXACT' });
    expect(config('JSON_SUBSET', '{"answer":"ok"}')).toEqual({ expected: { answer: 'ok' }, assertion: 'JSON_SUBSET' });
    expect(config('TEXT_EQUALS', 'ok')).toEqual({ expected: 'ok', assertion: 'TEXT_EQUALS' });
    expect(config('CONTAINS', 'answer')).toEqual({ expected: 'answer', assertion: 'CONTAINS' });
    expect(config('REGEX', '^[A-Z]{3}-\\d{4}$')).toEqual({ expected: '^[A-Z]{3}-\\d{4}$', assertion: 'REGEX' });
    expect(config('SCHEMA', '{"type":"object","required":["answer"]}')).toEqual({ expected: { type: 'object', required: ['answer'] }, assertion: 'SCHEMA' });
    expect(config('NUMERIC_TOLERANCE', '0.5', '0.05')).toEqual({ expected: 0.5, assertion: { mode: 'NUMERIC_TOLERANCE', tolerance: 0.05 } });
  });
  it('names the seven modes and their contract semantics in the UI hints', () => {
    expect(assertion.ASSERTION_MODES).toHaveLength(7);
    expect(assertion.MODE_META.EXACT.summary).toContain('整份输出逐字段相等');
    expect(assertion.MODE_META.JSON_SUBSET.summary).toContain('期望值是输出子集');
    expect(assertion.MODE_META.REGEX.hint).toContain('整串匹配');
    expect(assertion.MODE_META.REGEX.expectedKind).toBe('pattern');
    expect(assertion.MODE_META.SCHEMA.expectedKind).toBe('schema');
    expect(assertion.MODE_META.NUMERIC_TOLERANCE.expectedKind).toBe('number');
  });
  it('rejects blank patterns, unsafe modes and negative tolerance with readable reasons', () => {
    expect(() => assertion.buildAssertion({ mode: 'REGEX', expectedText: '   ', tolerance: '' }, false)).toThrow('正则表达式不能为空');
    expect(() => assertion.buildAssertion({ mode: 'REGEX', expectedText: '(?=x)abc', tolerance: '' }, false)).toThrow('RE2 不支持');
    expect(() => assertion.buildAssertion({ mode: 'CONTAINS', expectedText: '  ', tolerance: '' }, false)).toThrow('包含片段不能为空');
    expect(() => assertion.buildAssertion({ mode: 'REGEX', expectedText: '^x$', tolerance: '' }, true)).toThrow('安全用例只允许严格相等或 Schema 校验');
    expect(() => assertion.buildAssertion({ mode: 'NUMERIC_TOLERANCE', expectedText: '1', tolerance: '-1' }, false)).toThrow('容差必须是非负数值');
    expect(() => assertion.buildAssertion({ mode: 'NUMERIC_TOLERANCE', expectedText: '1', tolerance: '' }, false)).toThrow('容差不能为空');
    expect(() => assertion.buildAssertion({ mode: 'SCHEMA', expectedText: '[]', tolerance: '' }, false)).toThrow('Schema 必须是 JSON 对象');
    expect(() => assertion.buildAssertion({ mode: 'JSON_SUBSET', expectedText: '{bad}', tolerance: '' }, false)).toThrow('必须是合法 JSON');
  });
  it('reads missing assertion as EXACT and shows the real tolerance instead of guessing', () => {
    expect(assertion.assertionKey(undefined)).toBe('EXACT');
    expect(assertion.assertionKey('EXACT')).toBe('EXACT');
    expect(assertion.describeAssertion(undefined)).toContain('EXACT');
    expect(assertion.describeAssertion('JSON_SUBSET')).toContain('期望值是输出子集');
    expect(assertion.describeAssertion({ mode: 'NUMERIC_TOLERANCE', tolerance: 0.25 })).toContain('0.25');
    expect(assertion.assertionKey({ mode: 'NUMERIC_TOLERANCE', tolerance: 0.25 })).toBe('NUMERIC_TOLERANCE:0.25');
    expect(assertion.assertionKey('INVENTED')).toContain('INVALID');
  });
});

describe('promotion dialog assertion editor', () => {
  it('submits the selected assertion and re-reads the frozen server case', async () => {
    let submitted: any;
    const api = fixtureApi({
      promote: (body) => { submitted = body; return { datasetVersion: 'v-frozen' }; },
      dataset: (path) => ({ appCode: 'app-one', datasetVersion: path.split('/').pop(), cases: [{ id: submitted.caseId, assertion: 'JSON_SUBSET', expected: { answer: 'ok' }, safety: false }] }),
    });
    const v = mountNamed(api.fn); await openPromotion(v);
    v.state.assertionDraft = { mode: 'JSON_SUBSET', expectedText: '{"answer":"ok"}', tolerance: '' };
    await v.state.promote();
    expect(submitted.assertion).toBe('JSON_SUBSET');
    expect(submitted.expected).toEqual({ answer: 'ok' });
    expect(submitted.safety).toBe(false);
    expect(typeof submitted.requestId).toBe('string');
    expect(api.calls.some(([path, method]) => method === 'GET' && path === '/admin/evaluation-datasets/app-one/v-frozen')).toBe(true);
    expect(v.state.notice).toContain('服务器冻结断言');
    expect(v.state.notice).toContain('JSON_SUBSET');
    expect(v.state.error).toBe('');
    expect(v.state.promotion).toBe(false);
    v.unmount();
  });
  it('omits the default EXACT assertion but still submits the independently entered expected value', async () => {
    let submitted: any;
    const api = fixtureApi({ promote: (body) => { submitted = body; return { datasetVersion: 'v-exact' }; }, dataset: () => ({ cases: [{ id: submitted.caseId, assertion: 'EXACT' }] }) });
    const v = mountNamed(api.fn); await openPromotion(v);
    v.state.assertionDraft = { mode: 'EXACT', expectedText: '{"answer":"ok"}', tolerance: '' };
    await v.state.promote();
    expect('assertion' in submitted).toBe(false);
    expect(submitted.expected).toEqual({ answer: 'ok' });
    expect(v.state.notice).toContain('EXACT');
    v.unmount();
  });
  it('blocks a blank REGEX pattern before sending the promote request', async () => {
    const api = fixtureApi();
    const v = mountNamed(api.fn); await openPromotion(v);
    v.state.assertionDraft = { mode: 'REGEX', expectedText: '   ', tolerance: '' };
    await flush();
    await v.state.promote();
    expect(v.state.error).toBe('正则表达式不能为空');
    expect(api.posts()).toHaveLength(0);
    expect(v.state.promotion).toBe(true);
    expect(v.state.notice).toBe('');
    v.unmount();
  });
  it('shows a readable backend error, keeps the dialog open and never fabricates a created version', async () => {
    const api = fixtureApi({ rejectPromote: Object.assign(new Error('不支持字段: assertion'), { status: 400, code: 'INVALID_INPUT' }) });
    const v = mountNamed(api.fn); await openPromotion(v);
    v.state.assertionDraft = { mode: 'JSON_SUBSET', expectedText: '{"answer":"ok"}', tolerance: '' };
    await v.state.promote();
    expect(v.state.error).toContain('不支持字段: assertion');
    expect(v.state.error).toContain('EXACT');
    expect(v.state.notice).toBe('');
    expect(v.state.promotion).toBe(true);
    expect(api.posts()).toHaveLength(1);
    expect(api.calls.some(([path, method]) => method === 'GET' && path.startsWith('/admin/evaluation-datasets/'))).toBe(false);
    v.unmount();
  });
  it('reports a server assertion mismatch after create instead of trusting the local draft', async () => {
    let submitted: any;
    const api = fixtureApi({ promote: (body) => { submitted = body; return { datasetVersion: 'v-drift' }; }, dataset: () => ({ cases: [{ id: submitted.caseId, assertion: 'EXACT' }] }) });
    const v = mountNamed(api.fn); await openPromotion(v);
    v.state.assertionDraft = { mode: 'JSON_SUBSET', expectedText: '{"answer":"ok"}', tolerance: '' };
    await v.state.promote();
    expect(v.state.notice).toContain('已创建不可变数据集版本 v-drift');
    expect(v.state.error).toContain('服务器保存的断言与提交不一致');
    v.unmount();
  });
});

describe('safety case mode lockout', () => {
  it('disables every non EXACT/SCHEMA option and ignores programmatic mode switches', () => {
    const emitted: any[] = [];
    const v = mount('shared/AssertionEditor.vue', 'AssertionEditor', { draft: { mode: 'EXACT', expectedText: '{}', tolerance: '' }, safety: true, 'onUpdate:draft': (value: any) => emitted.push(value) });
    expect(v.state.options.filter((option: any) => !option.disabled).map((option: any) => option.value)).toEqual(['EXACT', 'SCHEMA']);
    expect(v.state.options.filter((option: any) => option.disabled)).toHaveLength(5);
    v.state.setMode('REGEX');
    expect(emitted).toHaveLength(0);
    v.state.setMode('SCHEMA');
    expect(emitted).toHaveLength(1);
    expect(emitted[0].mode).toBe('SCHEMA');
    expect(v.props.safety).toBe(true);
    v.unmount();
  });
  it('switches a previously selected unsafe mode back to EXACT with an explicit note', async () => {
    const api = fixtureApi();
    const v = mountNamed(api.fn); await openPromotion(v);
    v.state.assertionDraft = { mode: 'REGEX', expectedText: '^x$', tolerance: '' };
    v.state.safety = true;
    await flush();
    expect(v.state.assertionDraft.mode).toBe('EXACT');
    expect(v.state.assertionNotice).toContain('安全用例只允许严格相等或 Schema 校验');
    expect(v.state.assertionNotice).toContain('REGEX');
    v.unmount();
  });
});
