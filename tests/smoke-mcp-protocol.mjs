/** Real gateway/engine/skill/PG path against an isolated SSE + JSON-RPC MCP peer.
 * This peer proves protocol wiring and frozen arguments, not a supplier's capabilities.
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import http from 'node:http';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';

const root = path.resolve(import.meta.dirname, '..');
const base = 'http://127.0.0.1:18080';
const env = Object.fromEntries((await fs.readFile(path.join(root, '.env'), 'utf8'))
  .split(/\r?\n/).filter(line => line && !line.startsWith('#')).map(line => {
    const at = line.indexOf('='); return [line.slice(0, at), line.slice(at + 1)];
  }));
const authResponse = await fetch(base + '/auth/dev-token', { method: 'POST', signal: AbortSignal.timeout(10000) });
assert.equal(authResponse.status, 200);
const auth = await authResponse.json();
assert.equal(auth.code, 'OK');
const userToken = auth.data.accessToken;
const claims = JSON.parse(Buffer.from(userToken.split('.')[1], 'base64url').toString());
function tokenWith(patch) {
  const header = Buffer.from(JSON.stringify({ alg: 'HS256', typ: 'JWT' })).toString('base64url');
  const body = Buffer.from(JSON.stringify({ ...claims, ...patch, exp: Math.floor(Date.now() / 1000) + 300 })).toString('base64url');
  const signed = header + '.' + body;
  return signed + '.' + crypto.createHmac('sha256', env.AGENT_JWT_SECRET).update(signed).digest('base64url');
}
async function request(route, method = 'GET', body, token = userToken) {
  const response = await fetch(base + route, { method,
    headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(20000) });
  return { status: response.status, envelope: await response.json() };
}
async function api(route, method = 'GET', body) {
  const response = await request(route, method, body);
  assert.equal(response.status, 200, route + ': ' + JSON.stringify(response.envelope));
  assert.equal(response.envelope.code, 'OK');
  return response.envelope.data;
}
function sql(query) {
  const result = spawnSync(path.join(root, '.tools/postgresql-16.15/pgsql/bin/psql.exe'),
    ['-h', '127.0.0.1', '-p', '25432', '-U', env.AGENT_DB_USER || 'agent_v12',
      '-d', 'agent_platform_dev_v12', '-At', '-v', 'ON_ERROR_STOP=1', '-f', '-'],
    { input: query, encoding: 'utf8', env: { ...process.env, PGPASSWORD: env.AGENT_DB_PASSWORD } });
  assert.equal(result.status, 0, result.stderr); return result.stdout.trim();
}
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(load, predicate, detail) {
  for (let attempt = 0; attempt < 200; attempt++) {
    const value = await load(); if (predicate(value)) return value; await pause(75);
  }
  throw new Error('Timed out: ' + detail);
}

const sessions = new Map(), sockets = new Set(), calls = [], methods = [];
let activeStreams = 0, openedStreams = 0;
let businessWriteError = false;
let remoteSchema = { type: 'object', properties: { region: { type: 'string', pattern: '^A$' } }, required: ['region'], additionalProperties: false };
function event(response, name, value) {
  response.write('event: ' + name + '\ndata: ' + (typeof value === 'string' ? value : JSON.stringify(value)) + '\n\n');
}
const fixture = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://127.0.0.1:28418');
    if (req.method === 'GET' && url.pathname === '/sse') {
      const id = crypto.randomUUID(); openedStreams++; activeStreams++; sessions.set(id, res);
      res.setHeader('Content-Type', 'text/event-stream'); res.setHeader('Cache-Control', 'no-cache'); res.flushHeaders();
      event(res, 'endpoint', '/messages?session=' + id);
      const heartbeat = setInterval(() => { if (!res.destroyed) res.write(': ping\n\n'); }, 100);
      res.once('close', () => { clearInterval(heartbeat); sessions.delete(id); activeStreams--; });
      return;
    }
    if (req.method === 'POST' && url.pathname === '/messages') {
      const stream = sessions.get(url.searchParams.get('session'));
      if (!stream) { res.writeHead(404); res.end(); return; }
      const chunks = []; let length = 0;
      for await (const chunk of req) {
        length += chunk.length; assert(length <= 128 * 1024, 'fixture request limit'); chunks.push(chunk);
      }
      const rpc = JSON.parse(Buffer.concat(chunks).toString('utf8'));
      assert.equal(rpc.jsonrpc, '2.0'); methods.push(rpc.method);
      res.writeHead(202); res.end();
      if (!Object.hasOwn(rpc, 'id')) return;
      let result;
      if (rpc.method === 'initialize') result = { protocolVersion: '2024-11-05', capabilities: { tools: {} }, serverInfo: { name: 'isolated-mcp-peer', version: '1.0' } };
      else if (rpc.method === 'tools/list') {
        const second = rpc.params?.cursor === 'page-2';
        result = { tools: [{ name: second ? 'lookup' : 'unrelated', description: second ? '按区域查询固定测试值' : '无关工具，用于验证精确绑定', inputSchema: second ? remoteSchema : { type: 'object' } }], ...(second ? {} : { nextCursor: 'page-2' }) };
      } else if (rpc.method === 'tools/call') {
        assert.equal(rpc.params.name, 'lookup'); assert.deepEqual(rpc.params.arguments, { region: 'A' });
        calls.push({ name: rpc.params.name, arguments: rpc.params.arguments });
        result = { content: [{ type: 'text', text: '{"value":7}' }], isError: businessWriteError };
      } else throw new Error('Unexpected MCP method: ' + rpc.method);
      if (!stream.destroyed) event(stream, 'message', { jsonrpc: '2.0', id: rpc.id, result });
      return;
    }
    res.writeHead(404); res.end();
  } catch (error) { res.destroy(error); }
});
fixture.on('connection', socket => { sockets.add(socket); socket.on('close', () => sockets.delete(socket)); });
await new Promise((resolve, reject) => { fixture.once('error', reject); fixture.listen(28418, '127.0.0.1', resolve); });
const runs = [], evidence = [];
try {
  const suffix = crypto.randomUUID().replaceAll('-', '').slice(0, 10);
  async function create(label, toolName = 'lookup', write = false) {
    const code = 'smoke_mcp_' + label + '_' + suffix;
    const skill = await api('/admin/skills', 'POST', { code, name: 'MCP隔离协议 ' + label,
      description: '真实SSE/JSON-RPC协议验证，不能作为供应商能力验收', type: 'MCP',
      endpoint: 'http://127.0.0.1:28418/sse', toolName, timeoutMs: 4000,
      inputSchema: { type: 'object', properties: { region: { type: 'string' } }, required: ['region'], additionalProperties: false },
      outputSchema: { type: 'object', properties: { value: { type: 'integer' } }, required: ['value'], additionalProperties: false },
      execution: { readonly: !write, sideEffect: write, idempotent: true, cancellable: false, ...(write ? { confirmRoles: ['EDITOR'] } : {}) },
      guard: { roles: ['EDITOR'], attributes: {} } });
    return { code, skill };
  }
  const good = await create('good');
  const discovery = await api('/admin/skills/' + good.code + '/drafts/1/mcp-tools', 'POST');
  assert.equal(discovery.protocolVersion, '2024-11-05'); assert.equal(discovery.serverInfo.name, 'isolated-mcp-peer');
  assert.deepEqual(discovery.tools.map(tool => tool.name), ['unrelated', 'lookup']);
  assert.deepEqual(discovery.tools[1].inputSchema, remoteSchema); assert.equal(calls.length, 0);
  await until(async () => activeStreams, n => n === 0, 'discovery SSE close');
  const opensBeforeDenied = openedStreams;
  const denied = await request('/admin/skills/' + good.code + '/drafts/1/mcp-tools', 'POST', undefined, tokenWith({ roles: ['VIEWER'] }));
  assert.equal(denied.status, 403); assert.equal(openedStreams, opensBeforeDenied);
  evidence.push({ case: 'discovery', protocolVersion: discovery.protocolVersion, toolNames: discovery.tools.map(tool => tool.name), rawSchemaPreserved: true, businessCalls: 0, viewerStatus: denied.status, limits: discovery.limits });

  if (process.argv.includes('--browser')) {
    const { verifyMcpDraftInBrowser } = await import('../web/tests/mcp-registration-live-browser.mjs');
    evidence.push({ case: 'real-draft-browser', ...await verifyMcpDraftInBrowser(good.code) });
    assert.equal(calls.length, 0, 'Browser discovery does not invoke a business tool');
    await until(async () => activeStreams, n => n === 0, 'browser discovery SSE close');
  }

  async function controlled(subject, wanted, semantic) {
    const before = calls.length;
    const accepted = await api('/admin/skills/' + subject.code + '/drafts/1/connection-test', 'POST', { inputs: { region: 'A' }, requestId: crypto.randomUUID() });
    runs.push(accepted.runId);
    const final = await until(() => api('/admin/debug-runs/' + accepted.runId + '/status'),
      state => ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(state.status), subject.code);
    assert.equal(final.status, wanted, JSON.stringify(final));
    const callId = Number(final.controlledCallId || final.callId); assert(Number.isSafeInteger(callId) && callId > 0);
    const facts = JSON.parse(sql(`select json_build_object('skillStatus',f.status,'engineStatus',t.status,'semantic',t.error_semantic,'value',f.result->'structured'->'value','logicalCommits',(select count(*) from engine.run_event e where e.run_id=t.run_id and e.type='LOGIC_RESULT_COMMITTED' and e.payload->>'nodeInstanceId'=t.node_instance_id::text)) from skill.execution_fact f join engine.tool_call t on t.id=f.call_id where f.call_id=${callId} and f.tenant_id='local-v12';`));
    if (semantic) assert.equal(facts.semantic, semantic);
    assert.equal(calls.length - before, wanted === 'SUCCEEDED' ? 1 : 0);
    if (wanted === 'SUCCEEDED') {
      assert.equal(facts.skillStatus, 'SUCCEEDED'); assert.equal(facts.engineStatus, 'SUCCEEDED');
      assert.equal(facts.value, 7); assert.equal(facts.logicalCommits, 1);
      const check = await api('/admin/skills/' + subject.code + '/drafts/1/connection-test', 'POST', { controlledCallId: callId });
      assert.equal(check.ok, true); assert.equal(calls.length, before + 1);
    }
    await until(async () => activeStreams, n => n === 0, 'controlled SSE close');
    evidence.push({ case: subject.code, runId: accepted.runId, callId, status: final.status, businessCalls: calls.length - before, facts });
  }
  await controlled(good, 'SUCCEEDED');
  await controlled(await create('missing', 'missing_tool'), 'FAILED', 'PARAM_ERROR');
  remoteSchema = { ...remoteSchema, minProperties: 2 };
  await controlled(await create('schema'), 'FAILED', 'PARAM_ERROR');
  assert.equal(calls.length, 1);

  // A protocol isError does not prove a write was NOT_APPLIED. Use the normal
  // declared-role/snapshot approval path, then verify repeated reconciliation is query-only.
  delete remoteSchema.minProperties; businessWriteError = true;
  const write = await create('write_unknown', 'lookup', true);
  const beforeWrite = calls.length;
  const accepted = await api('/admin/skills/' + write.code + '/drafts/1/connection-test', 'POST', { inputs: { region: 'A' }, requestId: crypto.randomUUID() });
  runs.push(accepted.runId);
  await until(() => api('/admin/debug-runs/' + accepted.runId + '/status'), state => state.status === 'WAITING_APPROVAL', 'write approval');
  assert.equal(calls.length, beforeWrite, 'no tools/call before explicit approval');
  const approval = await until(async () => (await api('/admin/approvals')).find(item => item.runId === accepted.runId), Boolean, 'declared-role approval');
  assert(approval.confirmRoles.includes('EDITOR'));
  await api('/admin/approvals/' + accepted.runId + '/' + approval.nodeInstanceId, 'POST', { requestId: crypto.randomUUID(), snapshotDigest: approval.snapshotDigest, approved: true });
  const waiting = await until(() => api('/admin/debug-runs/' + accepted.runId + '/status'), state => state.status === 'WAITING_EXTERNAL', 'write unknown');
  const callId = Number(waiting.controlledCallId || waiting.callId); assert(Number.isSafeInteger(callId) && callId > 0);
  assert.equal(calls.length, beforeWrite + 1);
  await until(async () => activeStreams, n => n === 0, 'write SSE close');
  const factQuery = `select json_build_object('status',f.status,'attemptId',f.attempt_id,'argsDigest',f.args_digest,'contractSha256',f.skill_contract_sha256,'requestSnapshot',f.request_snapshot,'engineStatus',t.status) from skill.execution_fact f join engine.tool_call t on t.id=f.call_id where f.call_id=${callId} and f.tenant_id='local-v12';`;
  const originalFact = JSON.parse(sql(factQuery));
  assert.equal(originalFact.status, 'UNKNOWN'); assert.equal(originalFact.engineStatus, 'UNKNOWN');
  const beforeQueries = openedStreams;
  const serviceToken = tokenWith({ roles: [...new Set([...(claims.roles || []), 'SERVICE'])], aud: 'agent-platform-internal', token_use: 'service' });
  const observations = [];
  for (let repeat = 0; repeat < 2; repeat++) {
    const response = await fetch('http://127.0.0.1:18083/internal/skill-facts/' + callId + '/reconcile', { method: 'POST', headers: { Authorization: 'Bearer ' + serviceToken, 'Content-Type': 'application/json' }, body: '{}', signal: AbortSignal.timeout(10000) });
    const envelope = await response.json(); assert.equal(response.status, 200, JSON.stringify(envelope));
    assert.equal(envelope.code, 'OK'); assert.equal(envelope.data.status, 'UNKNOWN');
    assert.equal(envelope.data.outcome, 'UNKNOWN'); assert.equal(envelope.data.retryBusinessAllowed, false); observations.push(envelope.data);
  }
  assert.equal(calls.length, beforeWrite + 1); assert.equal(openedStreams, beforeQueries);
  assert.deepEqual(JSON.parse(sql(factQuery)), originalFact);
  const notes = JSON.parse(sql(`select coalesce(json_agg(outcome order by id),'[]') from skill.reconciliation_note where tenant_id='local-v12' and call_id=${callId};`));
  assert.deepEqual(notes, ['UNKNOWN', 'UNKNOWN']);
  evidence.push({ case: 'write-isError-reconcile', skillCode: write.code, runId: accepted.runId, callId, approvalRequired: true,
    explicitDeclaredRole: 'EDITOR', status: waiting.status, businessCalls: calls.length - beforeWrite, queryOpenedStreams: openedStreams - beforeQueries,
    observations, auditOutcomes: notes, factIdentityUnchanged: true });
  assert.equal(calls.length, 2); assert.equal(activeStreams, 0);
  const result = { actualGatewayEngineSkill: true, actualPostgres: true, isolatedProtocolPeerOnly: true,
    sdkProtocol: 'SSE + JSON-RPC 2.0', activeStreams, openedStreams, businessCalls: calls.length, methods, evidence };
  await fs.writeFile(path.join(root, '.local/mcp-protocol-live.json'), JSON.stringify(result, null, 2));
  process.stdout.write(JSON.stringify(result, null, 2) + '\n');
} finally {
  // Keep durable facts as evidence; only this script's unfinished runs may be cancelled.
  for (const id of runs) {
    try {
      const state = await api('/admin/debug-runs/' + id + '/status');
      if (!['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(state.status)) await api('/admin/debug-runs/' + id + '/cancel', 'POST', {});
    } catch { /* Cleanup cannot overwrite the original verification error. */ }
  }
  fixture.close(); for (const socket of sockets) socket.destroy();
}
