import * as authority from '../shared/server-authority';
// MNT-01 AP：运行节点深链缺陷。Hash路由下 /#/runs/r1?nodeId=n1 的 nodeId 位于路由 query，
// RunDetail 却读 window.location.search（hash路由下为空）导致定位失效。
// 本文件锁定修复语义：路由query读取、同run query变化跟随、显式nodeId输入优先（无router嵌入）、
// 普通详情与坏节点不受影响。后端无变化；不放宽任何断言。
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { compileScript, parse } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as Vue from 'vue';
import { afterEach, expect, it, vi } from 'vitest';
import * as runProtocol from '../shared/run-protocol';
import * as content from '../shared/content';
// RunDetail 现引用 reason-code-text（失败原因文案收敛），夹具按真实 shared 模块解析。
import * as reasonCodeText from '../shared/reason-code-text';

const cleanup: (() => void)[] = [];
afterEach(() => {
  cleanup.splice(0).forEach((close) => close());
  vi.restoreAllMocks();
});
async function flush() {
  for (let index = 0; index < 10; index++) {
    await Promise.resolve();
    await Vue.nextTick();
  }
}

/** 与现有页面测试相同：真实SFC setup，替换HTTP、事件流与路由为受控桩。 */
function mountRun(routeQuery: Record<string, string>, props: Record<string, unknown> = {}) {
  const api = vi.fn(async (_path: string) => _path.endsWith('/semantic-results')?{runId:'r1',results:[],rejected:[]}:({
    runId:'r1',status: 'RUNNING',
    nodes: [
      { nodeDefId: 'n1', status: 'SUCCEEDED' },
      { nodeDefId: 'n2', status: 'RUNNING' },
    ],
    events: [],
    graph: null,
    output: {},
    diagnostics: {},
    eventsWatermark: '',
  }));
  const { descriptor, errors } = parse(
    readFileSync(new URL('../admin/src/RunDetail.vue', import.meta.url), 'utf8'),
  );
  expect(errors).toEqual([]);
  const code = ts.transpileModule(
    compileScript(descriptor, { id: 'RunDetailNav' }).content,
    { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } },
  ).outputText
    // M5-2：RunDetail.connect 读取 import.meta.env.VITE_MOCK 决定演示模式直连；CJS new Function
    // 不支持 import.meta，以空 env 桩替换（测试环境非 mock，保持真实 SSE 分支语义）。
    .replace(/\bimport\.meta\b/g, 'importMetaShim');
  const exports = {} as { default: Vue.Component & { render: () => null } };
  const require = createRequire(import.meta.url);
  const auth = {
    token: 't',
    snapshot: () => ({ current: () => true, generation: 1 }),
    invalidate: vi.fn(),
  };
  const route = Vue.reactive({ query: { ...routeQuery }, params: { runId: 'r1' } });
  const streamEvents = vi.fn(async () => {});
  new Function('require', 'exports', 'importMetaShim', code)((id: string) =>
    id === 'vue'
      ? Vue
      : id.endsWith('vue-router')
        ? { useRoute: () => route }
        : id.endsWith('/shared/api')
          ? { useApi: () => api, useAuth: () => auth }
          // F1-3：RunDetail 取消运行改走 DangerConfirm；本组只测导航深链，确认桩恒真。
          : id.includes('/DangerConfirm')
            ? { confirmDanger: async () => true }
            : id.endsWith('/debug-panel/events')
            ? {
                terminal: (s: string) => ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(s),
                streamEvents,
                authorityFailure: () => false,
              }
            : id.endsWith('/shared/run-protocol')
              ? runProtocol
              : id.endsWith('/shared/content')
                ? content
                : id.endsWith('/shared/server-authority') ? authority
                : id.endsWith('/shared/reason-code-text') ? reasonCodeText
                // MNT-03：RunDetail 引用 resource-types 稳定外壳；本组测导航深链，
                // 沙箱给行为等价浅实现（相对导入在 tests/ 下解析错位，不能 require）。
                : id.includes('/shared/resource-types')
                  ? {
                      emptyRunStatus: () => ({
                        runId: '', status: '', eventsWatermark: '', nodes: [],
                      }),
                      asRecordField: (record: any, key: string) =>
                        record && typeof record === 'object' ? record[key] : undefined,
                      asUnknownRecord: (value: unknown) =>
                        value && typeof value === 'object' && !Array.isArray(value)
                          ? { ...(value as object) }
                          : {},
                    }
                  : id.endsWith('.vue')
                  ? { default: { render: () => null } }
                  : require(id),
    exports,
    { env: {} });
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
  const app = renderer.createApp({ render: () => Vue.h(exports.default, props) });
  const instance = app.mount({}).$ as unknown as {
    subTree: { component: { setupState: any } };
  };
  cleanup.push(() => app.unmount());
  return {
    state: instance.subTree.component!.setupState as any,
    route,
    api,
    streamEvents,
  };
}

it('Hash路由深链从路由query读取nodeId定位（MNT-01缺陷实红）', async () => {
  const page = mountRun({ nodeId: 'n1' }, { runId: 'r1' });
  await flush();
  expect(page.state.nodeFilter).toBe('n1');
  expect(page.api.mock.calls.some(([path]) => String(path).includes('/runs/r1'))).toBe(true);
});

it('同run修改query时定位跟随，不重建组件', async () => {
  const page = mountRun({ nodeId: 'n1' }, { runId: 'r1' });
  await flush();
  const calls = page.api.mock.calls.length;
  page.route.query.nodeId = 'n2';
  await flush();
  expect(page.state.nodeFilter).toBe('n2');
  expect(page.api.mock.calls.length).toBe(calls);
});

it('显式nodeId输入优先，覆盖无router的既有嵌入用法', async () => {
  const page = mountRun({}, { runId: 'r1', nodeId: '9' });
  await flush();
  expect(page.state.nodeFilter).toBe('9');
  // 坏节点同样可留痕：过滤值如实呈现，页面不崩溃、不伪造定位成功。
  const ghost = mountRun({}, { runId: 'r1', nodeId: 'ghost' });
  await flush();
  expect(ghost.state.nodeFilter).toBe('ghost');
});

it('普通运行详情不受影响（锚：无nodeId时过滤为空）', async () => {
  const plain = mountRun({}, { runId: 'r1' });
  await flush();
  expect(plain.state.nodeFilter).toBe('');
});
