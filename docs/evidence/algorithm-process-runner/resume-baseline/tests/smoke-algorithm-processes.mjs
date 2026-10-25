/** Five finite protocol scenarios against root-supplied reviewed engine/skill artifacts.
 * No default artifact, existing service adoption, business retry, SQL seeding, or DB drop.
 * Preparation: node --test tests/algorithm-process-runner.test.mjs
 * After root handoff only: node tests/smoke-algorithm-processes.mjs --execute-reviewed <artifact-lock.json>
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import http from 'node:http';
import net from 'node:net';
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { startAlgorithmFixture } from './smoke-algorithm-protocol.mjs';

const root = path.resolve(import.meta.dirname, '..');
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const sha = value => crypto.createHash('sha256').update(value).digest('hex');
const quote = value => "'" + String(value).replaceAll("'", "''") + "'";

// Private to this runner: every resource is attempted even after earlier cleanup fails.
export async function cleanupAlgorithmRun({ failure, children, stop, fixtures, writeRecords, closeTimeoutMs = 1500 }) {
  const errors = [];
  async function attempt(label, operation, timeoutMs) {
    let timer;
    try {
      const result = operation();
      await (timeoutMs === undefined ? result : Promise.race([result, new Promise((resolve, reject) => {
        timer = setTimeout(() => reject(Object.assign(Error(label + ' close timed out'), { code: 'ETIMEDOUT' })), timeoutMs);
      })]));
    } catch (cause) { errors.push(Error('Cleanup failed: ' + label, { cause })); }
    finally { clearTimeout(timer); }
  }
  for (const child of children) await attempt('owned PID ' + child.pid, () => stop(child));
  for (const [index, fixture] of fixtures.entries()) await attempt('fixture ' + index, () => fixture.close(), closeTimeoutMs);
  await attempt('final process records', writeRecords);
  if (errors.length) throw new AggregateError(failure ? [failure, ...errors] : errors,
    'Algorithm process smoke cleanup failed', failure ? { cause: failure } : undefined);
  if (failure) throw failure;
}

export function assertProcessOwnership(record, observed, requireListener = true) {
  assert(observed && observed.pid === record.pid && observed.name?.toLowerCase() === 'java.exe', 'Refuse unrelated PID');
  assert.equal(observed.created, record.created, 'Refuse PID creation mismatch');
  const args = observed.commandLine?.match(/"[^"]*"|[^\s]+/g)?.map(arg => arg.replace(/^"|"$/g, '')) ?? [];
  const at = args.indexOf('-jar');
  assert(at >= 0 && args[at + 1]?.toLowerCase() === record.jar.toLowerCase(), 'Refuse mismatched exact JAR argument');
  if (requireListener) assert(observed.listenerPids.length > 0 && observed.listenerPids.every(pid => pid === record.pid),
    'Refuse listener owned by another PID');
}

async function until(load, accept, label, timeoutMs = 30000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const value = await load();
    if (accept(value)) return value;
    await pause(125);
  }
  throw Error('Timed out: ' + label);
}

function powershell(script, values = {}) {
  const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script], {
    env: { SystemRoot: process.env.SystemRoot, WINDIR: process.env.WINDIR, PATH: process.env.PATH, ...values },
    encoding: 'utf8', windowsHide: true, timeout: 10000,
  });
  assert.equal(result.status, 0, result.error?.message || result.stderr || 'Process inspection failed');
  return result.stdout.trim();
}

function inspect(record) {
  const raw = powershell('$ErrorActionPreference="Stop"; $p=Get-CimInstance Win32_Process -Filter ("ProcessId="+$env:ALG_PID);'
    + '$ids=@(Get-NetTCPConnection -LocalPort ([int]$env:ALG_PORT) -State Listen -ErrorAction SilentlyContinue | ForEach-Object {$_.OwningProcess});'
    + 'if($null -eq $p){@{pid=$null;listenerPids=$ids}|ConvertTo-Json -Compress}else{'
    + '@{pid=[int]$p.ProcessId;name=$p.Name;created=$p.CreationDate.ToUniversalTime().ToString("o");commandLine=$p.CommandLine;listenerPids=$ids}|ConvertTo-Json -Compress}',
  { ALG_PID: String(record.pid), ALG_PORT: String(record.port) });
  return JSON.parse(raw);
}

async function freePort() {
  const server = net.createServer();
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  const port = server.address().port;
  await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
  return port;
}

function freeBeforeStart(port) {
  powershell('if(@(Get-NetTCPConnection -LocalPort ([int]$env:ALG_PORT) -State Listen -ErrorAction SilentlyContinue).Count -gt 0){throw "Refuse occupied private port"}',
    { ALG_PORT: String(port) });
}

export async function loadArtifactLock(file) {
  const lock = JSON.parse(await fs.readFile(file, 'utf8'));
  assert.equal(lock.handoff, 'root-reviewed-algorithm-live', 'Root reviewed artifact handoff required');
  const boundary = (await fs.realpath(path.join(root, '.local/artifacts'))).toLowerCase() + path.sep;
  const result = {};
  for (const module of ['engine-runtime', 'skill-registry']) {
    const supplied = lock[module];
    assert(supplied && path.isAbsolute(supplied.directory) && /^[a-f0-9]{64}$/.test(supplied.sha256), 'Fixed directory and complete SHA required: ' + module);
    const directory = await fs.realpath(supplied.directory);
    assert(directory.toLowerCase().startsWith(boundary), 'Reviewed artifact must be inside this project .local/artifacts');
    const jar = await fs.realpath(path.join(directory, module + '.jar'));
    assert.equal(path.dirname(jar).toLowerCase(), directory.toLowerCase(), 'JAR cannot escape reviewed directory');
    assert.equal(sha(await fs.readFile(jar)), supplied.sha256, 'Reviewed JAR SHA mismatch: ' + module);
    result[module] = { directory, jar, sha256: supplied.sha256 };
  }
  return result;
}

function ticket(secret, tenant, service = false) {
  const now = Math.floor(Date.now() / 1000);
  const body = { iss: 'agent-platform-v12', sub: 'algorithm-live', tenant_id: tenant,
    aud: service ? 'agent-platform-internal' : 'agent-platform', token_use: service ? 'service' : 'user',
    roles: service ? ['EDITOR', 'ADMIN', 'SERVICE'] : ['EDITOR', 'ADMIN'], attributes: {}, iat: now, exp: now + 300, jti: crypto.randomUUID() };
  const encoded = Buffer.from(JSON.stringify({ alg: 'HS256', typ: 'JWT' })).toString('base64url') + '.' + Buffer.from(JSON.stringify(body)).toString('base64url');
  return encoded + '.' + crypto.createHmac('sha256', secret).update(encoded).digest('base64url');
}

async function vaultReference(directory, master, tenant, value) {
  const nonce = crypto.randomBytes(12), id = crypto.randomUUID();
  const cipher = crypto.createCipheriv('aes-256-gcm', crypto.createHash('sha256').update(master).digest(), nonce);
  const encrypted = Buffer.concat([cipher.update(JSON.stringify({ tenantId: tenant, value }), 'utf8'), cipher.final(), cipher.getAuthTag()]);
  await fs.writeFile(path.join(directory, id + '.secret'), Buffer.concat([nonce, encrypted]), { flag: 'wx', mode: 0o600 });
  return 'vault:' + id;
}

async function run(lockFile) {
  assert.equal(process.platform, 'win32', 'This narrow runner uses Windows PID/TCP ownership checks');
  const artifacts = await loadArtifactLock(lockFile); // Refuse before reading credentials or starting any resource.
  const local = Object.fromEntries((await fs.readFile(path.join(root, '.env'), 'utf8')).split(/\r?\n/)
    .filter(line => line && !line.startsWith('#')).map(line => { const at = line.indexOf('='); return [line.slice(0, at), line.slice(at + 1)]; }));
  const pgSource = new URL((local.AGENT_DB_URL ?? '').replace(/^jdbc:/, ''));
  assert(pgSource.protocol === 'postgresql:' && pgSource.hostname === '127.0.0.1' && pgSource.port === '25432', 'Only this project isolated PG endpoint is allowed');
  for (const key of ['AGENT_DB_USER', 'AGENT_DB_PASSWORD', 'AGENT_MINIO_ENDPOINT', 'AGENT_MINIO_ACCESS_KEY', 'AGENT_MINIO_SECRET_KEY', 'AGENT_MINIO_BUCKET'])
    assert(local[key], 'Missing project .env setting: ' + key);
  const minio = new URL(local.AGENT_MINIO_ENDPOINT);
  assert(minio.hostname === '127.0.0.1' && minio.port === '29000' && !minio.username && !minio.password, 'Only this project isolated MinIO endpoint is allowed');
  const suffix = crypto.randomUUID().replaceAll('-', '').slice(0, 12), tenant = 'algorithm-live-' + suffix;
  const database = 'agent_platform_algorithm_live_' + suffix;
  const evidence = path.join(root, 'docs/evidence/algorithm-process-runner/live-' + suffix);
  const privateDirectory = path.join(root, '.local/algorithm-live-' + suffix);
  await fs.mkdir(evidence, { recursive: true });
  await fs.mkdir(path.join(privateDirectory, 'vault'), { recursive: true });
  await fs.mkdir(path.join(privateDirectory, 'tmp'));
  const signing = crypto.randomBytes(48).toString('base64url'), master = crypto.randomBytes(48).toString('base64url');
  // Only explicit project PG/MinIO values are inherited; no parent AGENT_*/Spring/Java injection.
  const system = Object.fromEntries(['SystemRoot', 'WINDIR', 'PATH', 'TEMP', 'TMP', 'USERPROFILE'].filter(key => process.env[key]).map(key => [key, process.env[key]]));
  const java = path.join(local.AGENT_JAVA_HOME || 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot', 'bin/java.exe');
  const psql = path.join(root, '.tools/postgresql-16.15/pgsql/bin/psql.exe');
  const pgEnv = { ...system, PGPASSWORD: local.AGENT_DB_PASSWORD, PGCONNECT_TIMEOUT: '5', PGOPTIONS: '-c statement_timeout=5000' };
  const pgArgs = ['-h', pgSource.hostname, '-p', pgSource.port, '-U', local.AGENT_DB_USER, '-d', database, '-At', '-v', 'ON_ERROR_STOP=1', '-f', '-'];
  const children = [], fixtures = [], records = [], scenarios = [];
  const raw = { artifacts, database, tenant, startedAt: new Date().toISOString(), actualJvms: false, scenarios,
    evidenceLevel: 'pending-live-validation', exclusions: ['real Kubernetes execution', 'container execution', 'RBAC', 'production IAM', 'capacity', 'all G2 acceptance'] };
  const secrets = [local.AGENT_DB_PASSWORD, local.AGENT_MINIO_ACCESS_KEY, local.AGENT_MINIO_SECRET_KEY, signing, master];
  const redact = value => secrets.reduce((text, secret) => text.replaceAll(secret, '[REDACTED]'), String(value));
  const errorView = error => ({ name: error.name, message: redact(error.message), code: error.code,
    cause: error.cause ? errorView(error.cause) : undefined, errors: error.errors?.map(errorView) });
  async function save(name, value) { await fs.writeFile(path.join(evidence, name + '.json'), redact(JSON.stringify(value, null, 2))); }
  function sql(query) {
    const result = spawnSync(psql, pgArgs, { input: 'begin read only;\n' + query + '\ncommit;\n', env: pgEnv,
      encoding: 'utf8', windowsHide: true, timeout: 10000, maxBuffer: 2 * 1024 * 1024 });
    assert.equal(result.status, 0, redact(result.error?.message || result.stderr));
    return result.stdout.trim().split(/\r?\n/).filter(line => line !== 'BEGIN' && line !== 'COMMIT').join('\n');
  }
  const readJson = query => JSON.parse(sql(query));
  async function writeRecords() {
    for (const record of records) {
      try { const observed = inspect(record); record.final = { pid: observed.pid, listenerPids: observed.listenerPids, sameCreation: observed.created === record.created }; }
      catch (error) { record.finalInspectionError = errorView(error); }
    }
    await save('processes', records);
    await save('result', raw);
    const failedInspection = records.filter(record => record.finalInspectionError);
    assert.equal(failedInspection.length, 0, 'Final ownership inspection failed; inspect process records');
    assert(records.every(record => record.final.pid === null && record.final.listenerPids.length === 0), 'Owned PID or private listener remains; inspect process records');
  }
  async function owned(entry, listener = true) {
    assert.equal(entry.child.exitCode, null, 'Owned JVM exited before HTTP');
    assert.equal(entry.child.signalCode, null, 'Owned JVM stopped before HTTP');
    assert(!entry.record.spawnError, 'Owned JVM spawn failed');
    const observation = inspect(entry.record);
    assertProcessOwnership(entry.record, observation, listener);
    return observation;
  }
  async function stop(entry) {
    const { child, record } = entry;
    if (child.exitCode !== null || child.signalCode !== null) { record.exited = true; return; }
    const observation = inspect(record);
    if (observation.pid === null) { record.exited = true; return; }
    assertProcessOwnership(record, observation, false);
    powershell('$ErrorActionPreference="Stop"; $p=Get-CimInstance Win32_Process -Filter ("ProcessId="+$env:ALG_PID);'
      + 'if($null -eq $p){exit 0}; $a=@([regex]::Matches($p.CommandLine,\'"[^\"]*"|[^\\s]+\') | ForEach-Object {$_.Value.Trim(\'"\')});'
      + '$i=[array]::IndexOf($a,"-jar"); if($p.Name -ne "java.exe" -or $p.CreationDate.ToUniversalTime().ToString("o") -ne $env:ALG_CREATED'
      + ' -or $i -lt 0 -or $a[$i+1] -ne $env:ALG_JAR){throw "Refuse unrelated PID/JAR/creation"}; Stop-Process -Id ([int]$env:ALG_PID)',
    { ALG_PID: String(record.pid), ALG_CREATED: record.created, ALG_JAR: record.jar });
    await until(async () => child.exitCode !== null || child.signalCode !== null, Boolean, 'owned JVM exit', 10000);
    record.stopped = true;
  }
  async function request(entry, route, body, internal = false, binary = false) {
    await owned(entry); // Exact child PID, creation time, JAR argument and TCP owner before every HTTP.
    const response = await fetch('http://127.0.0.1:' + entry.record.port + route, {
      method: body === undefined ? 'GET' : 'POST', headers: { authorization: 'Bearer ' + ticket(signing, tenant, internal), 'content-type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(15000),
    });
    if (binary) { assert.equal(response.status, 200, 'Controlled artifact download failed'); return Buffer.from(await response.arrayBuffer()); }
    const text = await response.text();
    assert.equal(response.status, 200, redact(text));
    const envelope = JSON.parse(text); assert.equal(envelope.code, 'OK', redact(text));
    return envelope.data;
  }
  async function start(module, port, common) {
    freeBeforeStart(port);
    const artifact = artifacts[module];
    assert.equal(sha(await fs.readFile(artifact.jar)), artifact.sha256, 'Fixed JAR changed before spawn');
    const index = children.length, outPath = path.join(evidence, module + '-' + index + '.out.log'), errPath = path.join(evidence, module + '-' + index + '.err.log');
    const out = await fs.open(outPath, 'wx'); let err;
    try {
      err = await fs.open(errPath, 'wx');
      const child = spawn(java, ['-Djava.io.tmpdir=' + path.join(privateDirectory, 'tmp'), '-Djdk.net.unixdomain.tmpdir=' + path.join(privateDirectory, 'tmp'),
        '-Xms64m', '-Xmx256m', '-jar', artifact.jar], { cwd: root, windowsHide: true, stdio: ['ignore', out.fd, err.fd],
        env: { ...system, ...common, SERVER_PORT: String(port), AGENT_DB_URL: 'jdbc:postgresql://' + pgSource.host + '/' + database + '?currentSchema=' + (module === 'engine-runtime' ? 'engine' : 'skill') } });
      const record = { pid: child.pid, module, jar: artifact.jar, sha256: artifact.sha256, port, database, outPath, errPath, startedAt: new Date().toISOString() };
      const entry = { child, record }; children.push(entry); records.push(record);
      child.on('error', error => { record.spawnError = errorView(error); });
      await save('processes', records);
      const observation = await until(async () => {
        assert(!record.spawnError && child.exitCode === null && child.signalCode === null, 'Private JVM failed before TCP listener');
        const value = inspect(record);
        if (value.pid === record.pid && !record.created) record.created = value.created;
        if (!value.listenerPids.length) return null;
        assertProcessOwnership(record, value); return value;
      }, Boolean, module + ' owned listener', 60000);
      record.ownershipVerified = { created: observation.created, listenerPids: observation.listenerPids, exactJar: true };
      await until(async () => {
        await owned(entry);
        try { return (await fetch('http://127.0.0.1:' + port + '/actuator/health', { signal: AbortSignal.timeout(1500) })).ok; }
        catch { return false; }
      }, Boolean, module + ' health', 60000);
      return entry;
    } finally { await out.close(); if (err) await err.close(); }
  }
  let failure;
  try {
    const created = spawnSync(path.join(root, '.tools/postgresql-16.15/pgsql/bin/createdb.exe'),
      ['-h', pgSource.hostname, '-p', pgSource.port, '-U', local.AGENT_DB_USER, database],
      { env: pgEnv, encoding: 'utf8', windowsHide: true, timeout: 15000 });
    assert.equal(created.status, 0, redact(created.error?.message || created.stderr));
    raw.databaseCreated = true;
    let rejectedUpstreamRequests = 0;
    const rejectServer = http.createServer((req, res) => { rejectedUpstreamRequests++; res.writeHead(503); res.end('Unused upstream rejected'); });
    fixtures.push({ close: () => new Promise((resolve, reject) => { rejectServer.close(error => error && error.code !== 'ERR_SERVER_NOT_RUNNING' ? reject(error) : resolve()); rejectServer.closeAllConnections(); }) });
    rejectServer.requestTimeout = 1500; rejectServer.headersTimeout = 1500;
    await new Promise((resolve, reject) => { rejectServer.once('error', reject); rejectServer.listen(0, '127.0.0.1', resolve); });
    const rejectUrl = 'http://127.0.0.1:' + rejectServer.address().port;
    for (const mode of ['complete', 'ack-loss', 'failed', 'running', 'offload']) {
      const scenario = { mode, phase: 'preparing', namespace: 'alg-live-' + suffix + '-' + mode }; scenarios.push(scenario);
      const fakeBearer = crypto.randomBytes(24).toString('base64url'); secrets.push(fakeBearer);
      const stdout = JSON.stringify({ value: mode === 'offload' ? 'a'.repeat(1200) : 'ok-' + mode });
      const fixture = await startAlgorithmFixture({ namespace: scenario.namespace, expectedBearer: fakeBearer,
        mode: mode === 'offload' ? 'complete' : mode, stdout, maxMs: 300000 });
      fixtures.push(fixture);
      const authRef = await vaultReference(path.join(privateDirectory, 'vault'), master, tenant, fakeBearer);
      const enginePort = await freePort(); let skillPort = await freePort(); while (skillPort === enginePort) skillPort = await freePort();
      const common = { AGENT_BIND_ADDRESS: '127.0.0.1', AGENT_DB_USER: local.AGENT_DB_USER, AGENT_DB_PASSWORD: local.AGENT_DB_PASSWORD,
        AGENT_JWT_SECRET: signing, AGENT_MASTER_KEY: master, AGENT_SECRET_DIR: path.join(privateDirectory, 'vault'),
        AGENT_APP_URL: rejectUrl, AGENT_MODEL_URL: rejectUrl, AGENT_MONITOR_URL: rejectUrl,
        AGENT_ENGINE_URL: 'http://127.0.0.1:' + enginePort, AGENT_SKILL_URL: 'http://127.0.0.1:' + skillPort,
        AGENT_K8S_API: fixture.baseUrl, AGENT_ALGORITHM_NAMESPACE: scenario.namespace, AGENT_ALGORITHM_SERVICE_ACCOUNT: 'algorithm-proof',
        AGENT_ALGORITHM_ALLOWED_IMAGES: 'algorithm-proof:finite', AGENT_ALGORITHM_ALLOW_LOOPBACK_HTTP: 'true',
        AGENT_INTERACTIVE_WORKERS: '1', AGENT_BATCH_WORKERS: '1', AGENT_SCHEDULER_POLL_MS: '100', AGENT_SCHEDULER_LEASE_MS: '10000', AGENT_SCHEDULER_DRAIN_MS: '1000',
        AGENT_MINIO_ENDPOINT: local.AGENT_MINIO_ENDPOINT, AGENT_MINIO_ACCESS_KEY: local.AGENT_MINIO_ACCESS_KEY,
        AGENT_MINIO_SECRET_KEY: local.AGENT_MINIO_SECRET_KEY, AGENT_MINIO_BUCKET: local.AGENT_MINIO_BUCKET };
      const skill = await start('skill-registry', skillPort, common);
      const engine = await start('engine-runtime', enginePort, common); raw.actualJvms = true;
      const code = 'alg_live_' + suffix + '_' + mode.replaceAll('-', '_');
      const draft = await request(skill, '/admin/skills', { code, name: '有限算法纵切 ' + mode, description: '新工程私有进程与有限API fixture',
        type: 'ALGORITHM', endpoint: '', authRef, invocationKind: 'SUBMIT_QUERY', jobTemplate: { image: 'algorithm-proof:finite' },
        inputSchema: { type: 'object', properties: { message: { type: 'string' } }, required: ['message'], additionalProperties: false },
        outputSchema: { type: 'object', properties: { value: { type: 'string' } }, required: ['value'], additionalProperties: false },
        execution: { readonly: true, sideEffect: false, idempotent: true, cancellable: false }, guard: { roles: ['EDITOR'], attributes: {} },
        timeoutMs: 4000, constraints: { outputMaxBytes: mode === 'offload' ? 1024 : 4096 } });
      scenario.skillCode = code; scenario.skillVersionId = draft.skillVersionId;
      const route = '/admin/skills/' + code + '/drafts/1/connection-test';
      // This is the only call that can create this scenario's run. Never retry after ambiguous HTTP.
      scenario.phase = 'connection-test-submitting'; await save(mode, scenario);
      const first = await request(skill, route, { inputs: { message: mode }, requestId: 'algorithm-live-' + crypto.randomUUID() });
      scenario.connection = first; scenario.runId = first.runId;
      assert(/^r_[A-Za-z0-9_-]+$/.test(scenario.runId), 'Connection test must return the actual controlled run');
      const state = await until(() => request(engine, '/admin/debug-runs/' + scenario.runId + '/status'),
        value => ['SUCCEEDED', 'FAILED', 'WAITING_EXTERNAL', 'CANCELLED'].includes(value.status), 'controlled run ' + mode);
      const call = Number(state.controlledCallId || state.callId || first.controlledCallId);
      assert(Number.isSafeInteger(call) && call > 0, 'Actual controlledCallId required'); scenario.callId = call;
      const capture = () => readJson(`select json_build_object('run',json_build_object('publicId',r.public_id,'status',r.status),
        'call',to_jsonb(t),'fact',(select to_jsonb(f) from skill.execution_fact f where f.tenant_id=r.tenant_id and f.call_id=t.id),
        'binding',(select to_jsonb(b) from skill.external_job_binding b where b.tenant_id=r.tenant_id and b.call_id=t.id),
        'toolCommits',(select count(*) from engine.run_event e where e.run_id=r.id and e.type='LOGIC_RESULT_COMMITTED' and (e.payload->>'nodeInstanceId')::bigint=t.node_instance_id),
        'attempts',(select count(*) from engine.attempt a where a.node_instance_id=t.node_instance_id),
        'events',(select coalesce(json_agg(json_build_object('seq',e.seq,'type',e.type,'payload',e.payload) order by e.seq),'[]'::json) from engine.run_event e where e.run_id=r.id))
        from engine.run r join engine.tool_call t on t.run_id=r.id where r.tenant_id=${quote(tenant)} and r.public_id=${quote(scenario.runId)} and t.id=${call};`);
      scenario.before = await until(async () => capture(), value => value.fact && value.binding, 'original fact and binding');
      assert.equal(scenario.before.fact.is_mock, false); assert.equal(scenario.before.fact.call_kind, 'DEBUG');
      assert.equal(scenario.before.call.attempt_id, scenario.before.fact.attempt_id);
      assert.equal(scenario.before.binding.attempt_id, scenario.before.call.attempt_id);
      assert.equal(scenario.before.call.idempotency_key, scenario.before.binding.idempotency_key);
      assert.equal(scenario.before.binding.external_namespace, scenario.namespace);
      assert.equal(scenario.before.binding.external_job_id, fixture.snapshot().jobName);
      assert.equal(scenario.before.binding.send_started, true);
      scenario.fixtureBefore = fixture.assertSinglePost();
      if (mode === 'ack-loss' || mode === 'running') {
        assert.equal(scenario.before.fact.status, 'UNKNOWN'); assert.equal(scenario.before.call.status, 'UNKNOWN');
        assert.equal(scenario.before.run.status, 'WAITING_EXTERNAL');
        scenario.reconcile = await request(skill, '/internal/skill-facts/' + call + '/reconcile', {}, true);
        if (mode === 'running') {
          assert.equal(scenario.reconcile.status, 'UNKNOWN');
          scenario.afterReconcile = capture();
          assert.equal(scenario.afterReconcile.fact.status, 'UNKNOWN');
          scenario.localCancel = await request(engine, '/admin/debug-runs/' + scenario.runId + '/cancel', {});
          await until(() => request(engine, '/admin/debug-runs/' + scenario.runId + '/status'), value => value.status === 'CANCELLED', 'local cancellation');
          scenario.cancellationMeaning = 'local run cancelled; external fact remains UNKNOWN; no Job delete/stop/rollback claimed';
        }
      } else if (mode === 'failed') {
        assert.equal(scenario.before.fact.status, 'FAILED'); assert.equal(scenario.before.run.status, 'FAILED');
        assert.match(JSON.stringify(scenario.before.fact.result), /NON_RETRYABLE_FAILURE/);
        scenario.reconcile = await request(skill, '/internal/skill-facts/' + call + '/reconcile', {}, true);
        assert.equal(scenario.reconcile.status, 'FAILED');
      }
      const expected = mode === 'failed' ? 'FAILED' : mode === 'running' ? 'CANCELLED' : 'SUCCEEDED';
      scenario.after = await until(async () => capture(), value => value.run.status === expected && (mode === 'running' || value.fact.callback_state === 'DELIVERED'), 'terminal delivery ' + mode);
      for (const field of ['attempt_id', 'external_job_id', 'idempotency_key', 'external_namespace', 'external_identity_sha256'])
        assert.equal(scenario.after.binding[field], scenario.before.binding[field], 'Original binding changed: ' + field);
      assert.equal(scenario.after.call.id, call); assert.equal(scenario.after.call.attempt_id, scenario.before.call.attempt_id);
      assert.equal(scenario.after.attempts, 1); assert.equal(scenario.after.fact.is_mock, false);
      if (mode === 'running') {
        assert.equal(scenario.after.fact.status, 'UNKNOWN'); assert.equal(scenario.after.binding.status, 'BOUND');
      } else {
        assert.equal(scenario.after.binding.status, 'DONE');
        assert.equal(scenario.after.binding.external_job_uid, fixture.snapshot().jobUid);
        assert.equal(scenario.after.binding.metadata.terminalEvidence.condition, mode === 'failed' ? 'Failed' : 'Complete');
        if (mode !== 'failed') {
          assert.equal(scenario.after.fact.status, 'SUCCEEDED'); assert.equal(scenario.after.toolCommits, 1);
          scenario.recheck = await request(skill, route, { controlledCallId: call }); assert.equal(scenario.recheck.ok, true);
        } else scenario.recheck = await request(skill, route, { controlledCallId: call });
      }
      if (mode === 'complete' || mode === 'ack-loss') {
        assert.deepEqual(scenario.after.fact.result.result, JSON.parse(stdout));
        assert.equal(fixture.snapshot().logGets > 0, true); assert.equal(fixture.snapshot().podLists > 0, true);
      }
      if (mode === 'offload') {
        const artifact = readJson(`select row_to_json(a) from skill.artifact a where a.tenant_id=${quote(tenant)} and a.run_id=${quote(scenario.runId)} and a.call_id=${call};`);
        assert.equal(artifact.state, 'READY'); assert.equal(artifact.principal_id, 'algorithm-live');
        const bytes = await request(skill, '/v1/runs/' + scenario.runId + '/artifacts/' + artifact.artifact_id + '/download', undefined, false, true);
        assert.deepEqual(bytes, Buffer.from(stdout)); assert.equal(sha(bytes), artifact.sha256); assert.equal(artifact.size_bytes, bytes.length);
        scenario.artifact = { ...artifact, object_key: '[tenant/run owned key retained in PG]' }; scenario.downloadSha256 = sha(bytes);
      }
      scenario.fixtureAfter = fixture.assertSinglePost();
      assert.equal(scenario.fixtureAfter.posts, scenario.fixtureBefore.posts);
      assert.equal(rejectedUpstreamRequests, 0, 'Unused upstream unexpectedly contacted');
      scenario.phase = 'passed'; await save(mode, scenario);
      await cleanupAlgorithmRun({ children: [engine, skill], stop, fixtures: [fixture], writeRecords: () => save('processes', records) });
    }
    raw.rejectedUpstreamRequests = rejectedUpstreamRequests;
    raw.migrations = { engine: readJson('select json_agg(json_build_object(\'version\',version,\'success\',success) order by installed_rank) from engine.flyway_schema_history;'),
      skill: readJson('select json_agg(json_build_object(\'version\',version,\'success\',success) order by installed_rank) from skill.flyway_schema_history;') };
    assert(raw.migrations.skill.some(migration => migration.version === '311' && migration.success), 'Actual algorithm binding migration required');
    raw.evidenceLevel = 'real-isolated-engine-skill-pg-minio-with-finite-k8s-http-fixture'; raw.passed = true;
  } catch (error) { failure = error; raw.failure = errorView(error); raw.passed = false; }
  finally {
    raw.finishedAt = new Date().toISOString();
    try { await cleanupAlgorithmRun({ failure, children, stop, fixtures, writeRecords }); }
    catch (error) { raw.failure = errorView(error); raw.passed = false; await save('result', raw); throw error; }
  }
  process.stdout.write(JSON.stringify({ passed: raw.passed, database, evidence, scenarioCount: scenarios.length }) + '\n');
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  if (process.argv.length !== 4 || process.argv[2] !== '--execute-reviewed') {
    process.stdout.write('Preparation only. Actual execution requires root handoff: --execute-reviewed <artifact-lock.json>. No resources started.\n');
  } else {
    await run(process.argv[3]);
  }
}
