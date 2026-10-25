import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { assertAttemptScope } from '../../../tests/algorithm-process-attempt-scope.mjs';

const root = path.resolve(import.meta.dirname, '../../..'), out = import.meta.dirname;
const manifest = JSON.parse(await fs.readFile(path.join(root, 'docs/evidence/algorithm-process-runner-attempt-scope-sha256.json'), 'utf8'));
async function hashSources() {
  const result = {};
  for (const file of Object.keys(manifest.sources)) result[file] = crypto.createHash('sha256').update(await fs.readFile(path.join(root, file))).digest('hex');
  return result;
}
const before = await hashSources();
await fs.writeFile(path.join(out, 'source-before.json'), JSON.stringify(before, null, 2));
const input = path.join(root, 'docs/evidence/algorithm-process-runner-attempt-scope/inputs');
const archived = JSON.parse(await fs.readFile(path.join(input, 'v4-ack-loss.json'), 'utf8'));
const pg = JSON.parse(await fs.readFile(path.join(input, 'algorithm-process-live-v4-readonly.json'), 'utf8')).data;
const fixture = JSON.parse(await fs.readFile(path.join(input, 'v4-ack-fixture-final.json'), 'utf8'));
const testSource = await fs.readFile(path.join(root, 'tests/algorithm-process-attempt-scope.test.mjs'), 'utf8');
const start = testSource.indexOf('function sample()'), end = testSource.indexOf("test('actual v4", start);
assert(start >= 0 && end > start);
const sample = new Function('archived', 'pg', 'fixture', testSource.slice(start, end) + '\nreturn sample;')(archived, pg, fixture);
const scenario = sample();
assertAttemptScope('ack-loss', scenario, scenario.after, fixture);
const ready = scenario.after.events.find(event => event.type === 'EXTERNAL_READY');
const match = ready.created_at.match(/^(.*\.)(\d+)(Z|[+-]\d\d:\d\d)$/);
assert(match);
const micro = match[2].padEnd(6, '0');
assert(Number(micro.slice(3)) < 999, 'Room for same-millisecond inversion');
const laterNote = match[1] + (Number(micro) + 1).toString().padStart(6, '0') + match[3];
scenario.after.terminalNotes[0].created_at = laterNote;
assert.equal(Date.parse(laterNote), Date.parse(ready.created_at));
let accepted = true, error;
try { assertAttemptScope('ack-loss', scenario, scenario.after, fixture); } catch (cause) { accepted = false; error = cause.message; }
const probe = { originalValidAccepted: true, mutation: 'Terminal note is one microsecond after EXTERNAL_READY',
  terminalNoteTime: laterNote, readyTime: ready.created_at, sameDateParseMilliseconds: true, invalidSequenceAccepted: accepted, error };
await fs.writeFile(path.join(out, 'microsecond-order-probe.json'), JSON.stringify(probe, null, 2));

// Authorized one read-only query, restricted to the retained v4 database.
const source = await fs.readFile(path.join(root, 'tests/smoke-algorithm-processes.mjs'), 'utf8');
const query = source.match(/const view = readJson\(`([\s\S]*?)`\);/)?.[1];
assert(query);
const tenant = archived.before.fact.tenant_id, runId = archived.runId, call = archived.callId;
assert.equal(call, 2); assert.equal(runId, 'r_2700d27889274aea890a7042b9e0f994');
const quote = value => "'" + String(value).replaceAll("'", "''") + "'";
const select = query.replaceAll('${quote(tenant)}', quote(tenant)).replaceAll('${quote(scenario.runId)}', quote(runId)).replaceAll('${call}', String(call));
assert(!select.includes('${')); assert(/^select /i.test(select));
const sql = 'BEGIN READ ONLY;\nSET LOCAL statement_timeout=5000;\n' + select + '\nCOMMIT;\n';
await fs.writeFile(path.join(out, 'capture-select.sql'), sql);
const local = Object.fromEntries((await fs.readFile(path.join(root, '.env'), 'utf8')).split(/\r?\n/).filter(line => line && !line.startsWith('#')).map(line => { const at = line.indexOf('='); return [line.slice(0, at), line.slice(at + 1)]; }));
const endpoint = new URL(local.AGENT_DB_URL.replace(/^jdbc:/, ''));
assert.equal(endpoint.hostname, '127.0.0.1'); assert.equal(endpoint.port, '25432');
const database = 'agent_platform_algorithm_live_f7fecdf481a3';
const args = ['-h', endpoint.hostname, '-p', endpoint.port, '-U', local.AGENT_DB_USER, '-d', database, '-At', '-v', 'ON_ERROR_STOP=1', '-f', '-'];
const environment = Object.fromEntries(['SystemRoot', 'WINDIR', 'PATH', 'TEMP', 'TMP'].filter(key => process.env[key]).map(key => [key, process.env[key]]));
const result = spawnSync(path.join(root, '.tools/postgresql-16.15/pgsql/bin/psql.exe'), args,
  { env: { ...environment, PGPASSWORD: local.AGENT_DB_PASSWORD, PGCONNECT_TIMEOUT: '5', PGOPTIONS: '-c statement_timeout=5000 -c default_transaction_read_only=on' },
    input: sql, timeout: 10000, maxBuffer: 2 * 1024 * 1024, encoding: 'utf8', windowsHide: true });
await fs.writeFile(path.join(out, 'capture-select.stdout.log'), result.stdout || '');
await fs.writeFile(path.join(out, 'capture-select.stderr.log'), result.stderr || '');
const metadata = { command: 'project psql.exe -h 127.0.0.1 -p 25432 -U [project .env user] -d ' + database + ' -At -v ON_ERROR_STOP=1 -f -',
  credentialSource: 'project .env to PGPASSWORD environment only', database, timeoutMs: 10000, statementTimeoutMs: 5000,
  explicitReadOnly: true, status: result.status, signal: result.signal, errorCode: result.error?.code };
await fs.writeFile(path.join(out, 'capture-select-command.json'), JSON.stringify(metadata, null, 2));
assert.equal(result.status, 0, 'Authorized current capture SELECT failed; inspect saved raw logs');
const view = JSON.parse(result.stdout.trim().split(/\r?\n/).filter(line => !['BEGIN', 'SET', 'COMMIT'].includes(line)).join('\n'));
const shape = { runId: view.run.publicId, callId: view.calls[0]?.id, calls: view.calls.length, facts: view.facts.length, bindings: view.bindings.length,
  attemptIds: view.attemptRows.map(row => row.id), attemptNos: view.attemptRows.map(row => row.attempt_no), nodeCommittedAttempt: view.node.committed_attempt_id,
  terminalNotes: view.terminalNotes.length, toolCommits: view.toolCommits, eventCount: view.events.length, captureSqlExecutedAgainstRetainedFinalRowsOnly: true };
assert.deepEqual(shape.attemptIds, [5, 6]); assert.equal(shape.nodeCommittedAttempt, 6);
const after = await hashSources();
await fs.writeFile(path.join(out, 'source-after.json'), JSON.stringify(after, null, 2));
assert.deepEqual(after, before);
for (const [file, sha] of Object.entries(manifest.sources)) assert.equal(before[file], sha);
console.log(JSON.stringify({ noBusinessHttpOrJvm: true, sourceUnchanged: true, microsecondProbe: probe, currentCaptureSql: shape }, null, 2));
