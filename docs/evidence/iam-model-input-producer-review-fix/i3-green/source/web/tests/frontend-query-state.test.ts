import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { compileScript, parse } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as Vue from 'vue';
import { describe, expect, it, vi } from 'vitest';
import * as statistics from '../shared/statistics-lifecycle';

// Run the real SFC setup and Vue watchers; the host is empty because these
// regressions concern request ownership. Chrome separately checks rendered UI.
function mount(name: string, api: any, props: any = {}) {
  const filename = new URL('../admin/src/' + name + '.vue', import.meta.url);
  const { descriptor } = parse(readFileSync(filename, 'utf8'));
  const script = compileScript(descriptor, { id: name });
  const code = ts.transpileModule(script.content, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  const auth = Vue.reactive({ allowed: true, token: 'component-fixture-only', can() { return this.allowed; } });
  const exports: any = {};
  const require = createRequire(import.meta.url);
  new Function('require', 'exports', code)((id: string) => id === 'vue' ? Vue : id.endsWith('/api') ? { useApi: () => api, useAuth: () => auth } : id.endsWith('/statistics-lifecycle') ? statistics : id.endsWith('.vue') ? {} : require(id), exports);
  const component = exports.default;
  component.render = () => null;
  const renderer = Vue.createRenderer<any, any>({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null, createElement: () => ({}), createText: () => ({}), setText() {}, setElementText() {}, patchProp() {} });
  const input = Vue.reactive(props);
  const app = renderer.createApp({ render: () => Vue.h(component, input) });
  const instance = app.mount({}).$ as any;
  return { state: instance.subTree.component.setupState, auth, props: input, unmount: () => app.unmount() };
}
function deferred() { let resolve!: (value: any) => void, reject!: (error: any) => void; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
async function flush() { await Promise.resolve(); await Vue.nextTick(); await Promise.resolve(); }
const report = (page = 1) => ({ page, pageSize: 20, groups: [], skillGroups: [], groupsTotal: 41, skillGroupsTotal: 1 });
const versions = [{ versionNo: 2 }, { versionNo: 1 }];

describe('statistics applied filters', () => {
  it('paginates the applied cohort until query submits edits and resets page one', async () => {
    const api = vi.fn(async (path: string) => path.endsWith('/snapshots') ? [] : report(Number(new URL(path, 'http://test').searchParams.get('page'))));
    const view = mount('StatisticsPanel', api); await flush();
    view.state.filters.appCode = 'unsubmitted_app'; view.state.filters.page = 2;
    await view.state.query(false);
    const pageTwo = new URL(api.mock.calls.at(-1)![0], 'http://test');
    expect(pageTwo.searchParams.get('appCode')).toBeNull();
    expect(pageTwo.searchParams.get('page')).toBe('2');
    await view.state.query();
    const submitted = new URL(api.mock.calls.at(-1)![0], 'http://test');
    expect(submitted.searchParams.get('appCode')).toBe('unsubmitted_app');
    expect(submitted.searchParams.get('page')).toBe('1'); view.unmount();
  });
  it('does not validate unsubmitted dates during live or saved snapshot pagination', async () => {
    const api = vi.fn(async (path: string) => path.endsWith('/snapshots') ? [] : { ...report(), filters: { from: '2026-10-01T00:00:00Z', to: '2026-10-20T00:00:00Z', appCode: 'saved' } });
    const view = mount('StatisticsPanel', api); await flush();
    view.state.filters.from = '2026-10-20'; view.state.filters.to = '2026-10-01'; view.state.filters.page = 2;
    await view.state.query(false); expect(view.state.error).toBe('');
    await view.state.readSnapshot('s_1'); expect(api.mock.calls.at(-1)![0]).toContain('/snapshots/s_1?page=1');
    expect(view.state.filters.appCode).toBe('saved'); view.unmount();
  });
  it('keeps rebuild retry identity and server page size after form edits during the request', async () => {
    const gate = deferred(); let attempts = 0;
    const api = vi.fn(async (path: string) => { if (path.endsWith('/rebuild')) { if (++attempts === 1) throw Error('retry'); return gate.promise; } return path.endsWith('/snapshots') ? [] : report(); });
    const view = mount('StatisticsPanel', api); await flush();
    view.state.filters.appCode = 'rebuilt'; await view.state.rebuild();
    const retry = view.state.rebuild(); await flush(); view.state.filters.appCode = 'unsubmitted';
    gate.resolve({ ...report(), pageSize: 50, snapshotId: 's_new' }); await retry;
    const bodies = api.mock.calls.filter(([path]) => path.endsWith('/rebuild')).map(call => (call as any)[2]);
    expect(bodies[0].requestId).toBe(bodies[1].requestId);
    expect(Object.keys(bodies[1]).sort()).toEqual(['appCode', 'from', 'groupBy', 'requestId', 'to']);
    view.state.filters.page = 2; await view.state.query(false);
    expect(api.mock.calls.at(-1)![0]).toContain('/snapshots/s_new?page=2&pageSize=50'); view.unmount();
  });
  it('discards an in-flight statistics response after permission loss', async () => {
    const gate = deferred(); const api = vi.fn((path: string) => path.endsWith('/snapshots') ? Promise.resolve([]) : gate.promise);
    const view = mount('StatisticsPanel', api); view.auth.allowed = false; await flush(); gate.resolve(report()); await flush();
    expect(view.state.report).toBeUndefined(); expect(view.state.busy).toBe(false); view.unmount();
  });
});
describe('version comparison ownership', () => {
  it.each(['success', 'error'] as const)('discards a late %s after changing the selection without comparing again', async outcome => {
    const gate = deferred(); const api = vi.fn((path: string) => path.includes('/diff?') ? gate.promise : Promise.resolve([]));
    const view = mount('ApplicationHistory', api, { appCode: 'source', versions }); await flush();
    const pending = view.state.compare(); view.state.toVersion = 1; await flush();
    if (outcome === 'success') gate.resolve({ changes: [{ after: 'stale v2' }] }); else gate.reject(Error('stale error'));
    await pending; expect(view.state.comparison).toBeUndefined(); expect(view.state.diffError).toBe(''); expect(view.state.comparing).toBe(false); view.unmount();
  });
  it('does not let an older finally end the newer comparison', async () => {
    const first = deferred(), second = deferred(); let calls = 0;
    const api = vi.fn((path: string) => path.includes('/diff?') ? (++calls === 1 ? first.promise : second.promise) : Promise.resolve([]));
    const view = mount('ApplicationHistory', api, { appCode: 'source', versions }); await flush();
    const older = view.state.compare(); view.state.toVersion = 1; await flush(); const newer = view.state.compare();
    first.resolve({ changes: [{ after: 'stale v2' }] }); await older;
    expect(view.state.comparing).toBe(true); expect(view.state.comparison).toBeUndefined();
    second.resolve({ changes: [] }); await newer; expect(view.state.comparison).toEqual({ changes: [] });
    expect(api.mock.calls.filter(([path]) => path.includes('/diff?')).map(([path]) => path)).toEqual(['/admin/apps/source/versions/diff?fromVersion=1&toVersion=2', '/admin/apps/source/versions/diff?fromVersion=1&toVersion=1']); view.unmount();
  });
  it.each(['application', 'unmount'] as const)('invalidates comparison after %s change', async boundary => {
    const gate = deferred(); const api = vi.fn((path: string) => path.includes('/diff?') ? gate.promise : Promise.resolve([]));
    const view = mount('ApplicationHistory', api, { appCode: 'source', versions }); await flush(); const pending = view.state.compare();
    if (boundary === 'application') view.props.appCode = 'other'; else view.unmount();
    await flush(); gate.resolve({ changes: [{ after: 'stale source' }] }); await pending;
    expect(view.state.comparison).toBeUndefined(); if (boundary === 'application') view.unmount();
  });
});
