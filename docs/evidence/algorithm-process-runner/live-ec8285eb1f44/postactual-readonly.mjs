import fs from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';
import { spawnSync, execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { ownershipScripts } from '../../../../tests/smoke-algorithm-processes.mjs';

const directory = import.meta.dirname, root = path.resolve(directory, '../../../..');
const database = 'agent_platform_algorithm_live_ec8285eb1f44';
const raw = JSON.parse(await fs.readFile(path.join(directory, 'result.json'), 'utf8'));
assert.equal(raw.database, database);
const local = Object.fromEntries((await fs.readFile(path.join(root, '.env'), 'utf8')).split(/\r?\n/)
  .filter(line => line && !line.startsWith('#')).map(line => { const at = line.indexOf('='); return [line.slice(0, at), line.slice(at + 1)]; }));
const endpoint = new URL(local.AGENT_DB_URL.replace(/^jdbc:/, ''));
assert.equal(endpoint.hostname, '127.0.0.1'); assert.equal(endpoint.port, '25432');
const system = Object.fromEntries(['SystemRoot', 'WINDIR', 'PATH', 'TEMP', 'TMP'].filter(key => process.env[key]).map(key => [key, process.env[key]]));
const secrets = [local.AGENT_DB_PASSWORD, local.AGENT_MINIO_ACCESS_KEY, local.AGENT_MINIO_SECRET_KEY, local.AGENT_REDIS_PASSWORD].filter(Boolean);
const redact = text => secrets.reduce((value, secret) => value.replaceAll(secret, '[REDACTED]'), String(text));
const query = `BEGIN READ ONLY;
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
await fs.writeFile(path.join(directory, 'postactual-readonly.sql'), query, { flag: 'wx' });
const args = ['-h', endpoint.hostname, '-p', endpoint.port, '-U', local.AGENT_DB_USER, '-d', database, '-At', '-v', 'ON_ERROR_STOP=1', '-f', '-'];
const result = spawnSync(path.join(root, '.tools/postgresql-16.15/pgsql/bin/psql.exe'), args,
  { input: query, env: { ...system, PGPASSWORD: local.AGENT_DB_PASSWORD, PGCONNECT_TIMEOUT: '5', PGOPTIONS: '-c statement_timeout=5000 -c default_transaction_read_only=on' },
    encoding: 'utf8', windowsHide: true, timeout: 10000, maxBuffer: 2 * 1024 * 1024 });
await fs.writeFile(path.join(directory, 'postactual-readonly.stdout.log'), redact(result.stdout || ''), { flag: 'wx' });
await fs.writeFile(path.join(directory, 'postactual-readonly.stderr.log'), redact(result.stderr || ''), { flag: 'wx' });
const output = { at: new Date().toISOString(), command: 'project psql.exe -h 127.0.0.1 -p 25432 -U [project .env user] -d ' + database + ' -At -v ON_ERROR_STOP=1 -f -',
  readOnlyTransaction: true, defaultTransactionReadOnly: true, timeoutMs: 10000, statementTimeoutMs: 5000, exit: result.status, stderr: redact(result.stderr || ''),
  error: result.error ? { name: result.error.name, message: redact(result.error.message), code: result.error.code } : null };
if (result.status === 0) output.data = JSON.parse(result.stdout.trim().split(/\r?\n/).filter(line => !['BEGIN', 'SET', 'COMMIT'].includes(line)).join('\n'));
await fs.writeFile(path.join(root, 'docs/evidence/algorithm-process-live-v5-readonly.json'), redact(JSON.stringify(output, null, 2)), { flag: 'wx' });
assert.equal(result.status, 0, 'Read-only PG snapshot failed; original logs retained');

const records = JSON.parse(await fs.readFile(path.join(directory, 'processes.json'), 'utf8'));
const checks = [], execute = promisify(execFile);
for (const record of records) {
  try {
    const observed = await execute('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command',
      '[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false);' + ownershipScripts.inspect],
      { env: { SystemRoot: process.env.SystemRoot, WINDIR: process.env.WINDIR, PATH: process.env.PATH, ALG_PID: String(record.pid), ALG_PORT: String(record.port) },
        timeout: 10000, maxBuffer: 65536, encoding: 'utf8', windowsHide: true });
    checks.push({ recordedPid: record.pid, port: record.port, module: record.module, observed: JSON.parse(observed.stdout), stderr: observed.stderr });
  } catch (error) { checks.push({ recordedPid: record.pid, port: record.port, module: record.module, error: { name: error.name, message: redact(error.message), code: error.code } }); }
}
const cleanup = { at: new Date().toISOString(), checks, allAbsent: checks.every(check => !check.error && check.observed.pid === null && check.observed.listenerPids.length === 0), readOnlyInspection: true };
await fs.writeFile(path.join(root, 'docs/evidence/algorithm-process-live-v5-cleanup-readonly.json'), redact(JSON.stringify(cleanup, null, 2)), { flag: 'wx' });
assert(cleanup.allAbsent, 'Postactual recorded private PID/listener remains or inspection failed');
console.log(JSON.stringify({ database, pgExit: result.status, runs: output.data.runs.length, calls: output.data.calls.length, facts: output.data.facts.length,
  bindings: output.data.bindings.length, artifacts: output.data.artifacts.length, privateRecords: records.length, allAbsent: cleanup.allAbsent }));
