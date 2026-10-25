/** Two reviewed engine JVMs, a fresh PG database and pure FLOW fixtures.
 * Tests scheduling opportunity handover; no provider, node-execution or load claim.
 * Usage: node tests/smoke-run-scheduler-replicas.mjs .local/artifacts/<reviewed>/engine-runtime.jar
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import http from 'node:http';
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';

const root = path.resolve(import.meta.dirname, '..');
const artifact = await fs.realpath(path.resolve(root, process.argv[2] || 'MISSING-REVIEWED-ARTIFACT'));
assert(artifact.toLowerCase().startsWith(path.join(root, '.local/artifacts').toLowerCase() + path.sep), 'Use a copied, reviewed project artifact');
const artifactSha256 = crypto.createHash('sha256').update(await fs.readFile(artifact)).digest('hex');
const local = Object.fromEntries((await fs.readFile(path.join(root, '.env'), 'utf8')).split(/\r?\n/)
  .filter(line => line && !line.startsWith('#')).map(line => { const at = line.indexOf('='); return [line.slice(0, at), line.slice(at + 1)]; }));
const suffix = crypto.randomUUID().replaceAll('-', '').slice(0, 10);
const database = 'agent_platform_scheduler_live_' + suffix;
const tenant = 'scheduler-live-' + suffix, principal = 'scheduler-live';
const java = path.join(local.AGENT_JAVA_HOME || 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot', 'bin/java.exe');
const psql = path.join(root, '.tools/postgresql-16.15/pgsql/bin/psql.exe');
const pgEnv = { ...process.env, PGPASSWORD: local.AGENT_DB_PASSWORD };
const pgArgs = ['-h', '127.0.0.1', '-p', '25432', '-U', local.AGENT_DB_USER || 'agent_v12', '-d', database, '-At', '-v', 'ON_ERROR_STOP=1'];
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
function sql(query) {
  const result = spawnSync(psql, [...pgArgs, '-f', '-'], { input: query, env: pgEnv, encoding: 'utf8', windowsHide: true });
  assert.equal(result.status, 0, result.stderr); return result.stdout.trim();
}
const quote = value => "'" + String(value).replaceAll("'", "''") + "'";
const jsonSql = value => quote(JSON.stringify(value)) + '::jsonb';
async function until(load, predicate, label, timeout = 30000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { const value = await load(); if (predicate(value)) return value; await pause(100); }
  throw Error('Timed out: ' + label);
}
const now = Math.floor(Date.now() / 1000);
const claims = { iss: 'agent-platform-v12', sub: principal, tenant_id: tenant, aud: 'agent-platform', token_use: 'user',
  roles: ['EDITOR'], attributes: {}, iat: now, exp: now + 900, jti: crypto.randomUUID() };
const signing = Buffer.from(JSON.stringify({ alg: 'HS256', typ: 'JWT' })).toString('base64url') + '.' + Buffer.from(JSON.stringify(claims)).toString('base64url');
const token = signing + '.' + crypto.createHmac('sha256', local.AGENT_JWT_SECRET).update(signing).digest('base64url');
const snapshot = { appCode: 'scheduler_live', appVersionId: 1, versionNo: 1, type: 'FLOW', config: {},
  definition: { nodes: [{ id: 'start', type: 'START' }, { id: 'end', type: 'END' }], edges: [{ from: 'start', to: 'end' }], outputs: [], executionRules: { parallel: false, maxParallel: 1 } } };
const guard = { tenantId: tenant, principalId: principal, roles: ['EDITOR'], attributes: {} };
const children = [], processRecords = [], sockets = new Set();
let upstreamReads = 0, lock;
const fixture = http.createServer((req, res) => {
  upstreamReads++; res.setHeader('Content-Type', 'application/json');
  res.end(JSON.stringify({ code: 'OK', message: 'ok', data: snapshot, traceId: 'isolated-scheduler-peer' }));
});
fixture.on('connection', socket => { sockets.add(socket); socket.on('close', () => sockets.delete(socket)); });
await new Promise((resolve, reject) => { fixture.once('error', reject); fixture.listen(28419, '127.0.0.1', resolve); });
async function start(port) {
  const portEnv = { ...process.env, AGENT_SCHEDULER_TEST_PORT: String(port) };
  const free = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command',
    'if(Get-NetTCPConnection -LocalPort ([int]$env:AGENT_SCHEDULER_TEST_PORT) -State Listen -ErrorAction SilentlyContinue){exit 7}'],
    { env: portEnv, encoding: 'utf8', windowsHide: true });
  assert.equal(free.status, 0, 'Refuse to start or contact an occupied test port: ' + port);
  const name = 'scheduler-live-' + suffix + '-' + port + '-' + children.length;
  const outPath = path.join(root, '.local/logs/' + name + '.out.log');
  const errPath = path.join(root, '.local/logs/' + name + '.err.log');
  const out = await fs.open(outPath, 'w'), err = await fs.open(errPath, 'w');
  const child = spawn(java, ['-Djava.io.tmpdir=V:/.local/tmp', '-Djdk.net.unixdomain.tmpdir=V:/.local/tmp', '-Xms64m', '-Xmx256m', '-jar', artifact],
    { cwd: root, env: { ...process.env, ...local, SERVER_PORT: String(port), AGENT_DB_URL: 'jdbc:postgresql://127.0.0.1:25432/' + database + '?currentSchema=engine',
      AGENT_APP_URL: 'http://127.0.0.1:28419', AGENT_ENGINE_URL: 'http://127.0.0.1:' + port,
      AGENT_INTERACTIVE_WORKERS: '1', AGENT_BATCH_WORKERS: '1', AGENT_SCHEDULER_POLL_MS: '100', AGENT_SCHEDULER_LEASE_MS: '1000', AGENT_SCHEDULER_DRAIN_MS: '1000' },
      windowsHide: true, stdio: ['ignore', out.fd, err.fd] });
  await out.close(); await err.close(); children.push(child);
  const record = { pid: child.pid, port, jar: artifact, database, outPath, errPath };
  processRecords.push(record); await fs.writeFile(path.join(root, '.local/scheduler-live-processes.json'), JSON.stringify(processRecords, null, 2));
  // Confirm TCP ownership before any HTTP request; a healthy unrelated listener is not ours.
  await until(async () => {
    assert.equal(child.exitCode, null, 'Owned engine exited before opening its port');
    const owned = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command',
      '$n=Get-NetTCPConnection -LocalPort ([int]$env:AGENT_SCHEDULER_TEST_PORT) -State Listen -ErrorAction SilentlyContinue; if(@($n | Where-Object OwningProcess -eq ([int]$env:AGENT_SCHEDULER_TEST_PID)).Count -lt 1){exit 8}'],
      { env: { ...portEnv, AGENT_SCHEDULER_TEST_PID: String(child.pid) }, encoding: 'utf8', windowsHide: true });
    return owned.status === 0;
  }, Boolean, 'owned engine TCP listener ' + port, 60000);
  await until(async () => { try { return (await fetch('http://127.0.0.1:' + port + '/actuator/health', { signal: AbortSignal.timeout(1000) })).ok; } catch { return false; } }, ok => ok, 'engine health ' + port, 60000);
  const output = await fs.readFile(outPath, 'utf8');
  record.owner = output.match(/Run scheduler started owner=([a-f0-9-]+)/)?.[1]; assert(record.owner, 'scheduler owner startup evidence');
  return { child, record };
}
async function stop(engine) {
  if (!engine || engine.child.exitCode !== null || engine.child.signalCode !== null) return;
  // Verify the original project JAR and port before terminating a recorded process.
  const code = '$p=Get-CimInstance Win32_Process -Filter "ProcessId=' + engine.child.pid + '";if($null -eq $p){exit 0};if($p.Name -ne "java.exe" -or -not $p.CommandLine.Contains($env:AGENT_SCHEDULER_REVIEWED_JAR)){throw "Refuse unrelated PID"};Stop-Process -Id ' + engine.child.pid;
  const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', code], { env: { ...process.env, AGENT_SCHEDULER_REVIEWED_JAR: artifact }, encoding: 'utf8', windowsHide: true });
  assert.equal(result.status, 0, result.stderr);
  await until(async () => engine.child.exitCode !== null || engine.child.signalCode !== null, Boolean, 'child exit', 10000);
  engine.record.stopped = true;
}
function seed(publicId, head = false, delayed = false) {
  return `with inserted as (insert into engine.run(public_id,app_code,debug_snapshot,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs,dispatch_next_at) values(${quote(publicId)},'scheduler_live',${jsonSql(snapshot)},'DEBUG',${quote(head ? 'RUNNING' : 'PENDING')},${quote(principal)},${quote(tenant)},'pg:isolated-scheduler-fixture','{}',${jsonSql(guard)},'{}',${jsonSql(snapshot)},'{}',now()${delayed ? "+interval '2 seconds'" : ''}) returning id) insert into engine.node_instance(run_id,node_def_id,step_seq,node_type,status,definition,lease_owner,lease_until) select id,'start',1,'START',${quote(head ? 'RUNNING' : 'READY')},'{"id":"start","type":"START"}',${head ? "'fixture-other-node-owner',now()+interval '10 minutes'" : 'null,null'} from inserted;` +
    (head ? '' : `insert into engine.node_instance(run_id,node_def_id,step_seq,node_type,status,definition) select id,'end',2,'END','READY','{"id":"end","type":"END"}' from engine.run where public_id=${quote(publicId)};`);
}
let first, second;
try {
  const created = spawnSync(path.join(root, '.tools/postgresql-16.15/pgsql/bin/createdb.exe'),
    ['-h', '127.0.0.1', '-p', '25432', '-U', local.AGENT_DB_USER || 'agent_v12', database], { env: pgEnv, encoding: 'utf8', windowsHide: true });
  assert.equal(created.status, 0, created.stderr); // New unique DB only; never replace/drop another database.
  first = await start(18086);
  const handoverId = 'r_scheduler_handover_' + suffix;
  sql(seed(handoverId, false, true));
  lock = spawn(psql, [...pgArgs, '-f', '-'], { env: pgEnv, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
  let lockOutput = '', lockError = ''; lock.stdout.on('data', data => { lockOutput += data; }); lock.stderr.on('data', data => { lockError += data; });
  lock.stdin.write(`begin; select n.id from engine.node_instance n join engine.run r on r.id=n.run_id where r.public_id=${quote(handoverId)} and n.node_def_id='start' for update of n;\n\\echo NODE_LOCKED\n`);
  await until(async () => lockOutput, value => value.includes('NODE_LOCKED'), 'fixture node lock');
  const held = await until(async () => JSON.parse(sql(`select json_build_object('owner',dispatch_owner,'generation',dispatch_generation,'until',dispatch_until,'valid',dispatch_until>now()) from engine.run where public_id=${quote(handoverId)};`)), value => value.owner === first.record.owner && value.valid, 'first real dispatch claim');
  await stop(first);
  lock.stdin.end('commit;\n\\q\n');
  await until(async () => lock.exitCode, value => value !== null, 'node lock released'); assert.equal(lock.exitCode, 0, lockError); lock = null;
  await until(async () => sql(`select dispatch_until<=now() from engine.run where public_id=${quote(handoverId)};`), value => value === 't', 'real dispatch expiry');
  second = await start(18087);
  const handed = await until(async () => JSON.parse(sql(`select json_build_object('status',status,'generation',dispatch_generation,'startedEvents',(select count(*) from engine.run_event where run_id=r.id and type='RUN_STARTED'),'commits',(select count(*) from engine.run_event where run_id=r.id and type='LOGIC_RESULT_COMMITTED'),'attempts',(select count(*) from engine.attempt a join engine.node_instance n on n.id=a.node_instance_id where n.run_id=r.id)) from engine.run r where public_id=${quote(handoverId)};`)), value => value.status === 'SUCCEEDED', 'successor handover');
  assert.equal(handed.generation, held.generation + 1); assert.equal(handed.startedEvents, 1); assert.equal(handed.commits, 2); assert.equal(handed.attempts, 2);
  first = await start(18086);
  const prefix = 'r_scheduler_tail_' + suffix + '_';
  const rows = []; for (let i = 0; i < 101; i++) rows.push(seed('r_scheduler_head_' + suffix + '_' + i, true));
  for (let i = 0; i < 200; i++) rows.push(seed(prefix + i));
  sql('begin;' + rows.join('\n') + 'commit;');
  const cohort = await until(async () => JSON.parse(sql(`select json_build_object('total',count(*),'succeeded',count(*) filter(where status='SUCCEEDED'),'failed',count(*) filter(where status='FAILED'),'maxGeneration',max(dispatch_generation)) from engine.run where public_id like ${quote(prefix + '%')};`)), value => value.succeeded === 200, 'all fair tail rows', 90000);
  assert.equal(cohort.failed, 0);
  const uniqueness = JSON.parse(sql(`select json_build_object('starts',(select count(*) from engine.run_event e join engine.run r on r.id=e.run_id where r.public_id like ${quote(prefix + '%')} and e.type='RUN_STARTED'),'commits',(select count(*) from engine.run_event e join engine.run r on r.id=e.run_id where r.public_id like ${quote(prefix + '%')} and e.type='LOGIC_RESULT_COMMITTED'),'attempts',(select count(*) from engine.attempt a join engine.node_instance n on n.id=a.node_instance_id join engine.run r on r.id=n.run_id where r.public_id like ${quote(prefix + '%')}),'workerOwners',(select count(distinct split_part(n.lease_owner,'_',1)) from engine.node_instance n join engine.run r on r.id=n.run_id where r.public_id like ${quote(prefix + '%')}),'headAttempts',(select count(*) from engine.attempt a join engine.node_instance n on n.id=a.node_instance_id join engine.run r on r.id=n.run_id where r.public_id like ${quote('r_scheduler_head_' + suffix + '_%')}));`));
  assert.equal(uniqueness.starts, 200); assert.equal(uniqueness.commits, 400); assert.equal(uniqueness.attempts, 400); assert.equal(uniqueness.workerOwners, 2); assert.equal(uniqueness.headAttempts, 0);
  const httpChecks = [];
  for (const port of [18086, 18087]) {
    const body = JSON.stringify({ appCode: 'scheduler_live', inputs: {}, requestId: crypto.randomUUID() });
    let rejected = 0;
    const envelope = await until(async () => {
      const response = await fetch('http://127.0.0.1:' + port + '/admin/debug-runs', { method: 'POST', headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' }, body, signal: AbortSignal.timeout(10000) });
      const value = await response.json();
      // A final cohort worker may still be exiting after its durable success. Retry the
      // same unaccepted request only on an explicit capacity rejection, never on lost ACK.
      if (response.status === 429) { assert(Number(response.headers.get('Retry-After')) > 0); rejected++; await pause(1000); return null; }
      assert.equal(response.status, 200, JSON.stringify(value)); return value;
    }, Boolean, 'HTTP admission after cohort');
    const id = envelope.data.runId;
    await until(async () => sql(`select status from engine.run where public_id=${quote(id)};`), value => value === 'SUCCEEDED', 'actual HTTP run');
    httpChecks.push({ port, runId: id, capacityRejectionsBeforeAcceptance: rejected, acceptedStatus: envelope.data.status, finalStatus: 'SUCCEEDED' });
  }
  const result = { actualTwoEngineJvms: true, freshDatabase: database, artifactSha256, fixtureRowsOnly: true, providerCalls: 0,
    handover: { runId: handoverId, original: held, final: handed, ownerProcessStopped: true, dispatchWaitedForRealExpiry: true, nodeWasNotYetClaimed: true },
    fairness: { liveHeadRows: 101, readyTailRows: 200, cohort, uniqueness }, upstreamReads, httpChecks, processRecords,
    exclusions: ['provider cancellation', 'in-flight node lease takeover', 'distributed resource permits', 'capacity benchmark', 'graceful OS stop'] };
  await fs.writeFile(path.join(root, '.local/run-scheduler-replicas-live.json'), JSON.stringify(result, null, 2)); process.stdout.write(JSON.stringify(result, null, 2) + '\n');
} finally {
  if (lock && lock.exitCode === null) lock.stdin.end('rollback;\n\\q\n');
  for (const child of children) { const record = processRecords.find(entry => entry.pid === child.pid); await stop({ child, record }); }
  fixture.close(); for (const socket of sockets) socket.destroy();
  await fs.writeFile(path.join(root, '.local/scheduler-live-processes.json'), JSON.stringify(processRecords, null, 2));
}
