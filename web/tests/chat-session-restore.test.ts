import * as authority from '../shared/server-authority';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { compileScript, parse } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as Vue from 'vue';
import { afterEach, expect, it, vi } from 'vitest';
import * as content from '../shared/content';
import * as protocol from '../shared/run-protocol';
import * as inputSchema from '../shared/input-schema';
import * as session from '../shared/session';
import { terminal } from '../debug-panel/events';

type Snapshot = { runId: string; status: string; output?: unknown; error?: unknown; fileRefs?: unknown[] };
type Observation = {
  signal: AbortSignal;
  open: (cursor: string, emit: (event: unknown) => void) => Promise<void>;
  onStatus: (snapshot: Snapshot) => void;
};
type ChatState = {
  busy: boolean; ready: boolean; runId: string;
  sessionInfo?: { versionNo?: number; maxTurns?: number };
  messages: { role: string; text: string }[];
  stop: () => Promise<void>;
};
const cleanup: (() => void)[] = [];
afterEach(() => { cleanup.splice(0).forEach(close => close()); vi.unstubAllGlobals(); });
async function flush() {
  for (let index = 0; index < 12; index++) { await Promise.resolve(); await Vue.nextTick(); }
}

/** 与现有页面测试相同：真实SFC setup/生命周期，只替换HTTP、观察器和子组件。 */
function mountChat() {
  vi.stubGlobal('location', { search: '?sessionId=s_original', href: 'https://example.test/chat?sessionId=s_original' });
  vi.stubGlobal('requestAnimationFrame', vi.fn(() => 1));
  vi.stubGlobal('cancelAnimationFrame', vi.fn());
  vi.stubGlobal('confirm', vi.fn(() => true));
  const requests: { path: string; method: string }[] = [], streams: string[] = [], observations: Observation[] = [];
  let finish!: (value: Snapshot) => void, denyCancel = false;
  const api = vi.fn(async (path: string, method = 'GET') => {
    requests.push({ path, method });
    if(path.endsWith('/semantic-results'))return {runId:path.split('/')[3],results:[],rejected:[]};
    if (path === '/v1/apps/app/sessions/s_original' && method === 'GET')
      return { sessionId: 's_original', appCode: 'app', versionNo: 3, maxTurns: 40, nextTurn: 3,
        turns: [{ run_public_id: 'r_previous' }, { run_public_id: 'r_original' }] };
    if (path === '/v1/apps/app/input-schema?versionNo=3') return { type: 'CHAT', inputs: [] };
    if (path === '/v1/runs/r_previous')
      return { runId: 'r_previous', status: 'FAILED', inputs: { message: '上一轮问题' }, output: {}, error: { message: '原轮次失败原因' } };
    if (path === '/v1/runs/r_original' && method === 'GET')
      return { runId: 'r_original', status: 'RUNNING', inputs: { message: '尚在回答的问题' }, output: {}, fileRefs: [] };
    if (path === '/v1/runs/r_original/cancel' && method === 'POST') {
      if (denyCancel) throw Object.assign(new Error('当前身份无权停止'), { status: 403 });
      return { runId: 'r_original', status: 'CANCELLED', output: {}, error: { message: '运行已取消' }, fileRefs: [] };
    }
    throw new Error('Unexpected request: ' + method + ' ' + path);
  });
  const observeRun = vi.fn((options: Observation) => {
    observations.push(options);
    return new Promise<Snapshot>((resolve, reject) => {
      finish = resolve;
      options.signal.addEventListener('abort', () => reject(new DOMException('观察已停止', 'AbortError')), { once: true });
    });
  });
  const { descriptor, errors } = parse(readFileSync(new URL('../shared/Chat.vue', import.meta.url), 'utf8'));
  expect(errors).toEqual([]);
  const code = ts.transpileModule(compileScript(descriptor, { id: 'ChatSessionRestore' }).content,
    { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  const exports = {} as { default: Vue.Component & { render: () => null } }, require = createRequire(import.meta.url);
  const auth = { token: 'verified-local-session', snapshot: () => ({ signal: new AbortController().signal, current:()=>true }) };
  new Function('require', 'exports', code)((id: string) => id === 'vue' ? Vue
    : id.endsWith('/api') ? { useApi: () => api, useAuth: () => auth }
    : id.endsWith('/content') ? content
    : id.endsWith('/run-protocol') ? protocol
    : id.endsWith('/input-schema') ? inputSchema
    : id.endsWith('/server-authority') ? authority
    : id.endsWith('/session') ? session
    : id.endsWith('/run-observer') ? { observeRun }
    : id.endsWith('/events') ? { terminal, streamEvents: async (path: string) => { streams.push(path); } }
    : id.endsWith('.vue') ? { default: { render: () => null } }
    : require(id), exports);
  exports.default.render = () => null;
  const renderer = Vue.createRenderer<object, object>({
    createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null,
    createElement: () => ({}), createText: () => ({}), setText() {}, setElementText() {}, patchProp() {},
  });
  const app = renderer.createApp({ render: () => Vue.h(exports.default, { appCode: 'app' }) });
  const instance = app.mount({}).$ as unknown as { subTree: { component: { setupState: unknown } } };
  cleanup.push(() => app.unmount());
  return { state: instance.subTree.component!.setupState as unknown as ChatState, requests, streams, observations, observeRun,
    finish: (value: Snapshot) => finish(value), denyCancel: (value: boolean) => { denyCancel = value; } };
}

it('刷新恢复固定版本和原未完成run，继续观察终态而不创建或重发会话轮次', async () => {
  const call = mountChat(); await flush();
  expect(call.observeRun).toHaveBeenCalledTimes(1);
  expect(call.state.runId).toBe('r_original');
  expect(call.state.busy).toBe(true);
  expect(call.state.sessionInfo).toMatchObject({ versionNo: 3, maxTurns: 40 });
  expect(call.state.messages[1].text).toContain('原轮次失败原因');
  expect(call.state.messages.at(-1)?.text).toBe('');
  expect(call.requests.every(request => request.method === 'GET')).toBe(true);
  await call.observations[0].open('0', () => {});
  expect(call.streams).toEqual(['/v1/runs/r_original/events']);
  const final = { runId: 'r_original', status: 'SUCCEEDED', output: { text: '原运行的最终回答' }, fileRefs: [] };
  call.observations[0].onStatus(final); call.finish(final); await flush();
  expect(call.state.messages.at(-1)?.text).toBe('原运行的最终回答');
  expect(call.state.busy).toBe(false);
  expect(call.state.ready).toBe(true);
  expect(call.requests.some(request => request.method !== 'GET')).toBe(false);
});

it('恢复后停止只取消原run，403保留观察，成功收据才结束观察并展示原取消终态', async () => {
  const call = mountChat(); await flush();
  expect(call.state.busy).toBe(true);
  call.denyCancel(true);
  await call.state.stop(); await flush();
  expect(call.state.busy).toBe(true);
  expect(call.observations[0].signal.aborted).toBe(false);
  call.denyCancel(false);
  await call.state.stop(); await flush();
  expect(call.observations[0].signal.aborted).toBe(true);
  expect(call.state.busy).toBe(false);
  expect(call.state.runId).toBe('r_original');
  expect(call.state.messages.at(-1)?.text).toContain('运行已取消');
  expect(call.requests.filter(request => request.method === 'POST')).toEqual([
    { path: '/v1/runs/r_original/cancel', method: 'POST' }, { path: '/v1/runs/r_original/cancel', method: 'POST' },
  ]);
  expect(call.observeRun).toHaveBeenCalledTimes(1);
});
