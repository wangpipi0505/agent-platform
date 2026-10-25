import { existsSync, readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { compileScript, parse } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as Vue from 'vue';
import { describe, expect, it, vi } from 'vitest';

// Real SFC setup/watchers; the browser fixture separately exercises rendered controls.
function mount(name: string, props: any, api: any = vi.fn(async (..._args: any[]) => ({}))) {
  const { descriptor, errors } = parse(readFileSync(new URL('../admin/src/' + name + '.vue', import.meta.url), 'utf8'));
  expect(errors).toEqual([]);
  const code = ts.transpileModule(compileScript(descriptor, { id: name }).content, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  const auth = Vue.reactive({ token: 'fixture-one', roles: ['EDITOR', 'ADMIN'], user: { tenantId: 'tenant-one', principalId: 'owner' }, edit: true, admin: true, can(permission: string) { return !!this.token && (permission === 'admin' ? this.admin : this.edit); } });
  const exports: any = {}, require = createRequire(import.meta.url);
  new Function('require', 'exports', code)((id: string) => id === 'vue' ? Vue : id.endsWith('/api') ? { useApi: () => api, useAuth: () => auth } : id.endsWith('.vue') ? {} : id.endsWith('/controlled-test') ? { completedControlledCall: () => false } : require(id), exports);
  exports.default.render = () => null;
  const renderer = Vue.createRenderer<any, any>({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null, createElement: () => ({}), createText: () => ({}), setText() {}, setElementText() {}, patchProp() {} });
  const input = Vue.reactive(props), app = renderer.createApp({ render: () => Vue.h(exports.default, input) });
  const instance = app.mount({}).$ as any;
  return { state: instance.subTree.component.setupState, auth, props: input, api, unmount: () => app.unmount() };
}
function deferred() { let resolve!: (v: any) => void, reject!: (e: any) => void; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
async function flush() { await Promise.resolve(); await Vue.nextTick(); await Promise.resolve(); await Vue.nextTick(); }
const initial = (type = 'MCP') => ({ code: 'lookup', name: '查询', type, endpoint: 'https://fixture.invalid/sse', toolName: '', authRef: '', inputSchema: { type: 'object' }, outputSchema: { type: 'object' }, execution: { readonly: true }, guard: { roles: ['EDITOR', 'analyst'], attributes: { domain: ['test'] } } });
const definition = (extra: any = {}) => ({ ...initial(), toolName: 'lookup_exact.v2', status: 'DRAFT', ...extra });
const discovered = { protocolVersion: '2024-11-05', serverInfo: { name: 'fixture', version: '1' }, capabilities: { tools: {} }, tools: [{ name: 'lookup_exact.v2', description: '远端说明', inputSchema: { type: 'object', additionalProperties: false, properties: { query: { type: 'string', minLength: 2 } }, required: ['query'] } }], limits: { tools: 100, pages: 16 } };
const panelExists = () => existsSync(new URL('../admin/src/McpToolsPanel.vue', import.meta.url));

describe('MCP registration wizard', () => {
  it('blocks a blank or whitespace tool binding at the connection step', () => {
    const v = mount('SkillWizard', { initial: initial() }); v.state.step = 1; v.state.next(); expect(v.state.step).toBe(1); expect(v.state.error).toContain('工具名');
    v.state.value.toolName = '   '; v.state.next(); expect(v.state.step).toBe(1); v.unmount();
  });
  it('keeps the exact tool binding and existing guard when registering', () => {
    const v = mount('SkillWizard', { initial: { ...initial(), toolName: 'lookup_exact.v2' } });
    for (let i = 0; i < 5; i++) v.state.next();
    expect(v.state.step).toBe(5); expect(v.state.value.toolName).toBe('lookup_exact.v2'); expect(v.state.value.guard).toEqual(initial().guard); v.unmount();
  });
  it.each(['HTTP', 'ALGORITHM'])('does not require MCP fields for %s', type => {
    const v = mount('SkillWizard', { initial: initial(type) }); v.state.step = 1; v.state.next(); expect(v.state.step).toBe(2); expect(v.state.error).toBe(''); v.unmount();
  });
});

describe('selected skill definition', () => {
  it('uses the selected nested definition, never the published top-level binding', async () => {
    const api = vi.fn(async (path: string) => path === '/admin/model-entries' ? [] : { ...definition({ type: 'HTTP', toolName: 'published_wrong', currentVersion: 1 }), versions: [{ versionNo: 2, status: 'DRAFT', type: 'HTTP', toolName: 'flat_wrong', definition: definition({ toolName: 'draft_exact', authRef: 'secret_ref' }) }, { versionNo: 1, status: 'PUBLISHED', definition: definition({ type: 'HTTP', toolName: 'published_wrong' }) }] });
    const v = mount('ResourceDetail', { resource: { code: 'lookup' }, kind: 'skill' }, api); await flush();
    expect(v.state.selectedDefinition?.toolName).toBe('draft_exact'); expect(v.state.selectedDefinition?.type).toBe('MCP'); expect(v.state.selectedDefinition?.status).toBe('DRAFT'); expect(v.state.connectionSchema).toEqual(initial().inputSchema);
    v.state.selected = 1; await flush(); expect(v.state.selectedDefinition?.type).toBe('HTTP'); expect(v.state.selectedDefinition?.status).toBe('PUBLISHED'); v.unmount();
  });
  it('discards stale resource loads after switching code or account', async () => {
    const gate = deferred(); const api = vi.fn((path: string) => path === '/admin/model-entries' ? Promise.resolve([]) : path.endsWith('/lookup') ? gate.promise : Promise.resolve({ versions: [{ versionNo: 8, status: 'DRAFT', definition: definition({ toolName: 'new_exact' }) }] }));
    const v = mount('ResourceDetail', { resource: { code: 'lookup' }, kind: 'skill' }, api); v.props.resource = { code: 'second' }; await flush(); gate.resolve({ versions: [{ versionNo: 2, status: 'DRAFT', definition: definition({ toolName: 'stale' }) }] }); await flush();
    expect(v.state.selectedDefinition?.toolName).toBe('new_exact'); v.auth.user = { tenantId: 'other', principalId: 'other' }; await flush(); expect(v.state.selectedDefinition?.toolName).not.toBe('stale'); v.unmount();
  });
});

describe('explicit MCP tools discovery', () => {
  it('provides the real isolated MCP panel', () => { expect(panelExists()).toBe(true); });
  it('only POSTs the saved selected draft without body, then preserves raw schema and exact name matching', async () => {
    if (!panelExists()) return;
    const v = mount('McpToolsPanel', { code: 'lookup/two', version: 2, definition: definition() }, vi.fn(async () => structuredClone(discovered)));
    await flush(); expect(v.api).not.toHaveBeenCalled(); await v.state.check();
    expect(v.api).toHaveBeenCalledExactlyOnceWith('/admin/skills/lookup%2Ftwo/drafts/2/mcp-tools', 'POST', undefined, expect.any(AbortSignal));
    expect(v.state.discovery.tools[0].inputSchema).toEqual(discovered.tools[0].inputSchema); expect(v.state.boundExists).toBe(true); v.unmount();
  });
  it.each([{ type: 'HTTP' }, { status: 'PUBLISHED' }, { status: 'ARCHIVED' }])('rejects an ineligible selected version %j', async change => {
    if (!panelExists()) return; const v = mount('McpToolsPanel', { code: 'lookup', version: 2, definition: definition(change) }); await v.state.check(); expect(v.api).not.toHaveBeenCalled(); v.unmount();
  });
  it('requires EDITOR and ADMIN for credential-backed discovery with a useful reason', async () => {
    if (!panelExists()) return; const v = mount('McpToolsPanel', { code: 'lookup', version: 2, definition: definition() }); v.auth.edit = false; await v.state.check(); expect(v.api).not.toHaveBeenCalled(); expect(v.state.blockReason).toContain('EDITOR');
    v.auth.edit = true; v.auth.admin = false; v.props.definition = definition({ authRef: ' secret_ref ' }); await flush(); await v.state.check(); expect(v.api).not.toHaveBeenCalled(); expect(v.state.blockReason).toContain('ADMIN'); v.unmount();
  });
  it('uses the selected public authConfigured flag when the reference is redacted', async () => {
    const v = mount('McpToolsPanel', { code: 'lookup', version: 2, definition: definition({ authRef: undefined, authConfigured: true }) });
    v.auth.admin = false; await flush(); await v.state.check(); expect(v.api).not.toHaveBeenCalled(); expect(v.state.blockReason).toContain('ADMIN'); v.unmount();
  });
  it.each(['version', 'code', 'token', 'principal', 'tenant', 'permission', 'roles', 'definition', 'unmount'])('aborts and ignores late results after %s changes', async change => {
    if (!panelExists()) return; const gate = deferred(); const v = mount('McpToolsPanel', { code: 'lookup', version: 2, definition: definition() }, vi.fn(() => gate.promise)); const pending = v.state.check(); expect(v.api).toHaveBeenCalledTimes(1); const signal = (v.api.mock.calls[0] as any)[3];
    if (change === 'version') v.props.version = 3; if (change === 'code') v.props.code = 'other'; if (change === 'token') v.auth.token = 'two'; if (change === 'principal') v.auth.user.principalId = 'other'; if (change === 'tenant') v.auth.user.tenantId = 'other'; if (change === 'permission') v.auth.edit = false; if (change === 'roles') v.auth.roles = ['EDITOR', 'ADMIN', 'analyst']; if (change === 'definition') v.props.definition = definition({ toolName: 'different' }); if (change === 'unmount') v.unmount();
    await flush(); expect(signal.aborted).toBe(true); gate.resolve(discovered); await pending; expect(v.state.discovery).toBeUndefined(); expect(v.state.checking).toBe(false); if (change !== 'unmount') v.unmount();
  });
  it('prevents duplicate requests and stale finally from unlocking a newer check', async () => {
    if (!panelExists()) return; const one = deferred(), two = deferred(); const v = mount('McpToolsPanel', { code: 'lookup', version: 2, definition: definition() }, vi.fn().mockImplementationOnce(() => one.promise).mockImplementationOnce(() => two.promise));
    const old = v.state.check(); await v.state.check(); expect(v.api).toHaveBeenCalledTimes(1); v.props.version = 3; await flush(); const next = v.state.check(); one.resolve(discovered); await old; expect(v.state.checking).toBe(true); expect(v.state.discovery).toBeUndefined(); two.resolve(discovered); await next; expect(v.state.checking).toBe(false); v.unmount();
  });
  it('shows a missing exact binding and permits an explicit retry after a failed request', async () => {
    if (!panelExists()) return; const v = mount('McpToolsPanel', { code: 'lookup', version: 2, definition: definition({ toolName: 'LOOKUP_EXACT.v2' }) }, vi.fn().mockRejectedValueOnce(Error('服务暂时不可用')).mockResolvedValueOnce(discovered));
    await v.state.check(); expect(v.state.error).toContain('服务暂时不可用'); expect(v.state.checking).toBe(false); expect(v.api).toHaveBeenCalledTimes(1); await v.state.check(); expect(v.state.error).toBe(''); expect(v.state.boundExists).toBe(false); expect(v.api).toHaveBeenCalledTimes(2); v.unmount();
  });
  it('discards old errors and rejects malformed discovery without fallback data', async () => {
    if (!panelExists()) return; const gate = deferred(); const v = mount('McpToolsPanel', { code: 'lookup', version: 2, definition: definition() }, vi.fn().mockImplementationOnce(() => gate.promise).mockResolvedValueOnce({ tools: [{ name: 'bad' }] }));
    const pending = v.state.check(); expect(v.api).toHaveBeenCalledTimes(1); v.props.version = 3; await flush(); gate.reject(Error('stale error')); await pending; expect(v.state.error).toBe(''); await v.state.check(); expect(v.state.discovery).toBeUndefined(); expect(v.state.error).not.toBe(''); v.unmount();
  });
});
