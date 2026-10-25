import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import { spawnSync, execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { assertAttemptScope } from '../../../tests/algorithm-process-attempt-scope.mjs';
import { assertScenarioTerminal } from '../../../tests/smoke-algorithm-processes.mjs';

const root = path.resolve(import.meta.dirname, '../../..'), evidence = import.meta.dirname;
const rawDirectory = path.join(root, 'docs/evidence/algorithm-process-runner/live-ec8285eb1f44');
const read = async file => JSON.parse(await fs.readFile(file, 'utf8'));
const sha = value => crypto.createHash('sha256').update(value).digest('hex');
const summary = await read(path.join(root, 'docs/evidence/algorithm-process-live-v5-summary.json'));
const sourceManifest = await read(path.join(root, 'docs/evidence/algorithm-process-runner-time-precision-fix-sha256.json'));
const sourceBefore = {};
for (const [file, expected] of Object.entries(sourceManifest.sources)) { sourceBefore[file] = sha(await fs.readFile(path.join(root, file))); assert.equal(sourceBefore[file], expected); }
await fs.writeFile(path.join(evidence, 'source-before.json'), JSON.stringify(sourceBefore, null, 2));
let rawHashChecks = 0;
for (const [file, expected] of Object.entries(summary.evidenceSha256)) { assert.equal(sha(await fs.readFile(path.join(root, file))), expected, file); rawHashChecks++; }
let historicalChecks = 0;
for (const [file, expected] of Object.entries(await read(path.join(root, 'docs/evidence/algorithm-process-runner-time-precision-fix/preserved-history.json')))) {
  assert.equal(sha(await fs.readFile(path.join(root, file))), expected, file); historicalChecks++;
}
for (const module of ['engine-runtime', 'skill-registry']) assert.equal(sha(await fs.readFile(summary.fixedArtifacts[module].jar)), summary.fixedArtifacts[module].sha256);
assert.equal(summary.database, 'agent_platform_algorithm_live_ec8285eb1f44');
const local = Object.fromEntries((await fs.readFile(path.join(root, '.env'), 'utf8')).split(/\r?\n/).filter(line => line && !line.startsWith('#')).map(line => { const at = line.indexOf('='); return [line.slice(0, at), line.slice(at + 1)]; }));
const endpoint = new URL(local.AGENT_DB_URL.replace(/^jdbc:/, ''));
assert.equal(endpoint.hostname, '127.0.0.1'); assert.equal(endpoint.port, '25432');
const sql = `BEGIN READ ONLY;
SET LOCAL statement_timeout=5000;
SELECT json_build_object('database',current_database(),
 'runs',(select json_agg(to_jsonb(r) order by r.id) from engine.run r),
 'calls',(select json_agg(to_jsonb(c) order by c.id) from engine.tool_call c),
 'facts',(select json_agg(to_jsonb(f) order by f.call_id) from skill.execution_fact f),
 'bindings',(select json_agg(to_jsonb(b) order by b.call_id) from skill.external_job_binding b),
 'attempts',(select json_agg(to_jsonb(a) order by a.id) from engine.attempt a),
 'nodes',(select json_agg(to_jsonb(n) order by n.id) from engine.node_instance n),
 'events',(select json_agg(to_jsonb(e) order by e.run_id,e.seq) from engine.run_event e),
 'notes',(select json_agg(to_jsonb(n) order by n.id) from engine.attempt_note n),
 'artifacts',(select coalesce(json_agg(to_jsonb(a) order by a.artifact_id),'[]'::json) from skill.artifact a),
 'engineMigrations',(select json_agg(json_build_object('version',version,'success',success) order by installed_rank) from engine.flyway_schema_history),
 'skillMigrations',(select json_agg(json_build_object('version',version,'success',success) order by installed_rank) from skill.flyway_schema_history));
COMMIT;
`;
await fs.writeFile(path.join(evidence, 'readonly.sql'), sql);
const system = Object.fromEntries(['SystemRoot', 'WINDIR', 'PATH', 'TEMP', 'TMP'].filter(key => process.env[key]).map(key => [key, process.env[key]]));
const pg = spawnSync(path.join(root, '.tools/postgresql-16.15/pgsql/bin/psql.exe'), ['-h', endpoint.hostname, '-p', endpoint.port, '-U', local.AGENT_DB_USER, '-d', summary.database, '-At', '-v', 'ON_ERROR_STOP=1', '-f', '-'],
  { env: { ...system, PGPASSWORD: local.AGENT_DB_PASSWORD, PGCONNECT_TIMEOUT: '5', PGOPTIONS: '-c statement_timeout=5000 -c default_transaction_read_only=on' },
    input: sql, encoding: 'utf8', timeout: 10000, maxBuffer: 2 * 1024 * 1024, windowsHide: true });
await fs.writeFile(path.join(evidence, 'readonly.stdout.log'), pg.stdout || '');
await fs.writeFile(path.join(evidence, 'readonly.stderr.log'), pg.stderr || '');
await fs.writeFile(path.join(evidence, 'readonly-command.json'), JSON.stringify({ command: 'project psql.exe -h 127.0.0.1 -p 25432 -U [project .env user] -d ' + summary.database + ' -At -v ON_ERROR_STOP=1 -f -', explicitReadOnly: true, statementTimeoutMs: 5000, processTimeoutMs: 10000, status: pg.status, signal: pg.signal, errorCode: pg.error?.code }, null, 2));
assert.equal(pg.status, 0); assert.equal(pg.stderr, '');
const data = JSON.parse(pg.stdout.trim().split(/\r?\n/).filter(line => !['BEGIN', 'SET', 'COMMIT'].includes(line)).join('\n'));
assert.equal(data.database, summary.database);
for (const name of ['runs', 'calls', 'facts', 'bindings']) assert.equal(data[name].length, 5, name);
assert.equal(data.artifacts.length, 1);
assert(data.engineMigrations.every(m => m.success) && data.skillMigrations.every(m => m.success));
const caseResults = [];
for (const [index, mode] of ['complete', 'ack-loss', 'failed', 'running', 'offload'].entries()) {
  const scenario = await read(path.join(rawDirectory, mode + '.json'));
  const finalFixture = await read(path.join(rawDirectory, 'fixture-' + (index + 1) + '-final.json'));
  const stdout = JSON.stringify({ value: mode === 'offload' ? 'a'.repeat(1200) : 'ok-' + mode });
  assertAttemptScope(mode, scenario, scenario.after, scenario.fixtureAfter);
  assertScenarioTerminal(mode, scenario, scenario.fixtureAfter, stdout);
  if (mode === 'running') {
    assert.equal(scenario.afterRecheck, undefined); assert.equal(scenario.reconcile.status, 'UNKNOWN');
    assert.equal(scenario.afterReconcile.fact.status, 'UNKNOWN'); assert.equal(scenario.localCancel.status, 'CANCELLED');
  } else { assert(scenario.afterRecheck); assertAttemptScope(mode, scenario, scenario.afterRecheck, scenario.fixtureAfter); }
  assert.deepEqual(finalFixture, scenario.fixtureAfter);
  const view = scenario.afterRecheck || scenario.after;
  for (const [set, key, row] of [['calls', 'id', view.call], ['facts', 'call_id', view.fact], ['bindings', 'call_id', view.binding]]) {
    const rows = data[set].filter(value => value[key] === scenario.callId); assert.equal(rows.length, 1); assert.deepEqual(rows[0], row, mode + ' live ' + set);
  }
  assert.deepEqual(data.nodes.find(n => n.id === view.node.id), view.node, mode + ' live node');
  assert.deepEqual(data.attempts.filter(a => a.node_instance_id === view.node.id), view.attemptRows, mode + ' live attempts');
  assert.deepEqual(data.events.filter(e => e.run_id === view.run.id), view.events, mode + ' live events');
  const notes = data.notes.filter(n => n.node_instance_id === view.node.id && n.kind === 'TOOL_EXECUTION_FACT' && n.payload.accepted && ['SUCCEEDED', 'FAILED'].includes(n.payload.status));
  assert.deepEqual(notes, view.terminalNotes, mode + ' live accepted terminal notes');
  assert.equal(data.runs.find(r => r.id === view.run.id).status, view.run.status);
  assert.equal(finalFixture.posts, 1); assert.equal(finalFixture.authRejected, 0); assert.equal(finalFixture.denied, 0);
  caseResults.push({ mode, runId: scenario.runId, callId: scenario.callId, originalAttempt: scenario.before.call.attempt_id,
    localAttempts: view.attemptRows.map(a => ({ id: a.id, no: a.attempt_no, status: a.status })), committedAttempt: view.node.committed_attempt_id,
    status: view.run.status, factStatus: view.fact.status, bindingStatus: view.binding.status, jobUid: view.binding.external_job_uid,
    posts: finalFixture.posts, jobGets: finalFixture.jobGets, logicalCommits: view.toolCommits, terminalNotes: view.terminalNotes.length,
    recheckCaptured: !!scenario.afterRecheck, liveDatabaseMatchesRows: true });
}

// One signed GET, only the sole READY record owned by the recorded V5 offload call.
const artifact = data.artifacts[0], offload = caseResults.find(c => c.mode === 'offload');
assert.equal(artifact.state, 'READY'); assert.equal(artifact.run_id, offload.runId); assert.equal(artifact.call_id, offload.callId);
assert.equal(artifact.tenant_id, 'algorithm-live-ec8285eb1f44'); assert.equal(artifact.principal_id, 'algorithm-live'); assert.equal(artifact.size_bytes, 1212);
const minio = new URL(local.AGENT_MINIO_ENDPOINT); assert.equal(minio.hostname, '127.0.0.1'); assert.equal(minio.port, '29000');
const encode = s => encodeURIComponent(s).replace(/[!'()*]/g, c => '%' + c.charCodeAt(0).toString(16).toUpperCase());
const objectPath = '/' + [artifact.bucket, ...artifact.object_key.split('/')].map(encode).join('/');
const date = new Date().toISOString().replace(/[:-]|\.\d{3}/g, ''), day = date.slice(0, 8), payloadHash = sha('');
const canonicalHeaders = 'host:' + minio.host + '\nx-amz-content-sha256:' + payloadHash + '\nx-amz-date:' + date + '\n';
const signedHeaders = 'host;x-amz-content-sha256;x-amz-date', scope = day + '/us-east-1/s3/aws4_request';
const canonical = ['GET', objectPath, '', canonicalHeaders, signedHeaders, payloadHash].join('\n');
const hmac = (key, value) => crypto.createHmac('sha256', key).update(value).digest();
const key = hmac(hmac(hmac(hmac('AWS4' + local.AGENT_MINIO_SECRET_KEY, day), 'us-east-1'), 's3'), 'aws4_request');
const signature = hmac(key, 'AWS4-HMAC-SHA256\n' + date + '\n' + scope + '\n' + sha(canonical)).toString('hex');
const response = await fetch(minio.origin + objectPath, { method: 'GET', redirect: 'error', signal: AbortSignal.timeout(10000), headers: {
  'x-amz-date': date, 'x-amz-content-sha256': payloadHash,
  authorization: 'AWS4-HMAC-SHA256 Credential=' + local.AGENT_MINIO_ACCESS_KEY + '/' + scope + ', SignedHeaders=' + signedHeaders + ', Signature=' + signature } });
assert.equal(response.status, 200, 'Single authorized object GET failed');
assert(Number(response.headers.get('content-length')) <= 8192);
const reader = response.body.getReader(), chunks = []; let count = 0;
try { while (true) { const { done, value } = await reader.read(); if (done) break; count += value.length; assert(count <= 8192, 'Bounded object GET'); chunks.push(value); } }
finally { await reader.cancel(); }
const bytes = Buffer.concat(chunks), objectSha = sha(bytes);
assert.deepEqual(bytes, Buffer.from(JSON.stringify({ value: 'a'.repeat(1200) })));
assert.equal(objectSha, artifact.sha256); assert.equal(bytes.length, artifact.size_bytes);
await fs.writeFile(path.join(evidence, 'offload-readback.json'), bytes);
const objectCheck = { method: 'GET', status: response.status, artifactId: artifact.artifact_id, ownerTenant: artifact.tenant_id,
  ownerRun: artifact.run_id, callId: artifact.call_id, state: artifact.state, bytes: bytes.length, sha256: objectSha, byteLimit: 8192, timeoutMs: 10000,
  exactlyMatchesOriginalJson: true, noListWriteOrDelete: true };
await fs.writeFile(path.join(evidence, 'object-get-result.json'), JSON.stringify(objectCheck, null, 2));

// Inspect only the recorded PIDs and ports in a single bounded, read-only PowerShell query.
const records = await read(path.join(rawDirectory, 'processes.json'));
assert.equal(records.length, 10); assert(records.every(r => r.stopped && r.final.pid === null && !r.final.listenerPids.length));
const script = '[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false);$ErrorActionPreference="Stop";'
  + '$records=ConvertFrom-Json $env:V5_RECORDS; $filter=($records|ForEach-Object {"ProcessId="+$_.pid}) -join " OR ";'
  + '$found=@(Get-CimInstance Win32_Process -Filter $filter);'
  + '$ports=@($records|ForEach-Object {[int]$_.port}|Select-Object -Unique);$listeners=@(Get-NetTCPConnection -LocalPort $ports -State Listen -ErrorAction SilentlyContinue);'
  + '$result=@($records|ForEach-Object {$r=$_;$p=@($found|Where-Object {$_.ProcessId -eq $r.pid});'
  + '[pscustomobject]@{recordedPid=$r.pid;port=$r.port;recordedCreation=$r.created;recordedJar=$r.jar;present=($p.Count -gt 0);'
  + 'observed=@($p|ForEach-Object {[pscustomobject]@{pid=$_.ProcessId;created=$_.CreationDate.ToUniversalTime().ToString("o");commandLine=$_.CommandLine}});'
  + 'listenerPids=@($listeners|Where-Object {$_.LocalPort -eq $r.port}|ForEach-Object {$_.OwningProcess})}});ConvertTo-Json -Depth 6 -Compress -InputObject $result';
await fs.writeFile(path.join(evidence, 'process-inspection.ps1'), script);
const processResult = await promisify(execFile)('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script],
  { env: { ...system, V5_RECORDS: JSON.stringify(records.map(({ pid, port, created, jar }) => ({ pid, port, created, jar }))) }, encoding: 'utf8', timeout: 10000, maxBuffer: 65536, windowsHide: true });
await fs.writeFile(path.join(evidence, 'process-inspection.stdout.log'), processResult.stdout);
await fs.writeFile(path.join(evidence, 'process-inspection.stderr.log'), processResult.stderr);
const processRows = JSON.parse(processResult.stdout); assert.equal(processRows.length, 10);
assert(processRows.every(r => !r.present && !r.listenerPids.length));
const sourceAfter = {};
for (const file of Object.keys(sourceManifest.sources)) sourceAfter[file] = sha(await fs.readFile(path.join(root, file)));
await fs.writeFile(path.join(evidence, 'source-after.json'), JSON.stringify(sourceAfter, null, 2)); assert.deepEqual(sourceAfter, sourceBefore);
const result = { status: 'independent-readonly-evidence-matched', rawHashChecks, historicalChecks, sourceFilesUnchanged: 16,
  fixedJarShaMatched: 2, readonlyDatabase: data.database, cases: caseResults, objectCheck,
  processInspection: { status: 0, recordedPids: 10, presentPids: 0, remainingListeners: 0 }, noBusinessHttpJvmsOrWrites: true };
await fs.writeFile(path.join(evidence, 'verification-result.json'), JSON.stringify(result, null, 2));
console.log(JSON.stringify(result, null, 2));
