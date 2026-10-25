import test from 'node:test';
import assert from 'node:assert/strict';
import { cleanupAlgorithmRun, assertProcessOwnership, assertScenarioTerminal, ownershipScripts, loadArtifactLock } from './smoke-algorithm-processes.mjs';
import { startAlgorithmFixture } from './smoke-algorithm-protocol.mjs';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs/promises';
import path from 'node:path';

test('cleanup retains the original failure and continues after a child and fixture failure', async () => {
  const original = new Error('business ACK lost'), stopError = new Error('PID identity changed');
  const fixtureError = new Error('fixture close broken'), visited = [];
  await assert.rejects(cleanupAlgorithmRun({ failure: original,
    children: [{ pid: 11 }, { pid: 12 }],
    stop: async child => { visited.push(child.pid); if (child.pid === 11) throw stopError; },
    fixtures: [{ close: async () => { visited.push('fixture'); throw fixtureError; } }],
    writeRecords: async () => visited.push('records'),
  }), error => {
    assert(error instanceof AggregateError);
    assert.equal(error.cause, original);
    assert.equal(error.errors[0], original);
    assert.equal(error.errors[1].cause, stopError);
    assert.equal(error.errors[2].cause, fixtureError);
    return true;
  });
  assert.deepEqual(visited, [11, 12, 'fixture', 'records']);
});

test('fixture close timeout still writes final records and reports its timeout', async () => {
  let written = false;
  await assert.rejects(cleanupAlgorithmRun({ children: [], stop: async () => {},
    fixtures: [{ close: () => new Promise(() => {}) }], closeTimeoutMs: 25,
    writeRecords: async () => { written = true; },
  }), error => error instanceof AggregateError && error.errors[0].cause.code === 'ETIMEDOUT');
  assert.equal(written, true);
});

test('a listener with another PID or another exact jar argument never permits HTTP', () => {
  const record = { pid: 31, jar: 'G:\\project\\reviewed\\engine-runtime.jar', created: '20261020100000.000000+000' };
  const owned = { pid: 31, name: 'java.exe', created: record.created,
    commandLine: '"java.exe" -jar "G:\\project\\reviewed\\engine-runtime.jar"', listenerPids: [31] };
  assert.doesNotThrow(() => assertProcessOwnership(record, owned));
  assert.throws(() => assertProcessOwnership(record, { ...owned, listenerPids: [32] }), /listener/);
  assert.throws(() => assertProcessOwnership(record, { ...owned,
    commandLine: 'java.exe -jar "G:\\project\\reviewed\\engine-runtime.jar.other"' }), /JAR/);
  assert.throws(() => assertProcessOwnership(record, { ...owned, created: 'reused PID' }), /creation/);
});

test('finite fixture rejects absent and wrong bearer before receiving a business POST', async () => {
  const fixture = await startAlgorithmFixture({ namespace: 'runner-auth-test', expectedBearer: 'fake-runner-bearer', maxMs: 3000 });
  try {
    for (const authorization of [undefined, 'Bearer another-fake-bearer']) {
      const response = await fetch(fixture.baseUrl + '/apis/batch/v1/namespaces/runner-auth-test/jobs', {
        method: 'POST', headers: authorization ? { authorization } : {}, body: '{}', signal: AbortSignal.timeout(1000),
      });
      assert.equal(response.status, 401);
    }
    assert.equal(fixture.snapshot().posts, 0);
    assert.equal(fixture.snapshot().authRejected, 2);
  } finally { await fixture.close(); }
});

function observation(mode) {
  const uid = 'finite-original-uid', unknown = mode === 'running';
  const binding = { external_job_uid: uid, status: unknown ? 'SUBMITTED' : 'DONE', metadata: { terminalEvidence: { condition: mode === 'failed' ? 'Failed' : 'Complete' } } };
  const fact = { attempt_id: 7, execution_owner: 'original-owner', call_kind: 'DEBUG', status: unknown ? 'UNKNOWN' : mode === 'failed' ? 'FAILED' : 'SUCCEEDED', result: {
    structured: mode === 'failed' ? null : { value: 'ok' }, error: mode === 'failed' ? { semantic: 'NON_RETRYABLE_FAILURE' } : null,
  } };
  return { before: { binding: structuredClone(binding), fact: structuredClone(fact) }, after: { binding, fact, toolCommits: mode === 'complete' ? 1 : 0,
    call: { status: unknown ? 'UNKNOWN' : fact.status }, run: { status: unknown ? 'CANCELLED' : fact.status } },
    recheck: { ok: mode !== 'failed' } };
}

test('the actual inline envelope structured field is accepted and compared to full JSON', () => {
  const value = observation('complete');
  assert.doesNotThrow(() => assertScenarioTerminal('complete', value, { jobUid: 'finite-original-uid' }, '{"value":"ok"}'));
  value.after.fact.result.structured.value = 'truncated';
  assert.throws(() => assertScenarioTerminal('complete', value, { jobUid: 'finite-original-uid' }, '{"value":"ok"}'));
});

test('a running bound Job stays SUBMITTED and external UNKNOWN after local cancellation', () => {
  const value = observation('running');
  assert.doesNotThrow(() => assertScenarioTerminal('running', value, { jobUid: 'finite-original-uid' }, '{}'));
  value.after.fact.status = 'FAILED';
  assert.throws(() => assertScenarioTerminal('running', value, { jobUid: 'finite-original-uid' }, '{}'));
});

test('failed connection recheck must stay false and cannot pass as NOT_APPLIED', () => {
  const value = observation('failed'); value.recheck.ok = true;
  assert.throws(() => assertScenarioTerminal('failed', value, { jobUid: 'finite-original-uid' }, '{}'));
});

test('an already captured UID cannot be silently replaced even if fixture now agrees', () => {
  const value = observation('failed'); value.after.binding.external_job_uid = 'replacement-uid';
  assert.throws(() => assertScenarioTerminal('failed', value, { jobUid: 'replacement-uid' }, '{}'));
});

test('final recording error is reported together with the original failure after all fixture attempts', async () => {
  const original = Error('original protocol failure'), recording = Error('disk full'), visited = [];
  await assert.rejects(cleanupAlgorithmRun({ failure: original, children: [], stop: async () => {},
    fixtures: [{ close: () => visited.push(1) }, { close: () => visited.push(2) }],
    writeRecords: () => { visited.push('record'); throw recording; },
  }), error => error instanceof AggregateError && error.errors[0] === original && error.errors[1].cause === recording);
  assert.deepEqual(visited, [1, 2, 'record']);
});

test('finite lost ACK creates once and later GET observes the same original Job without a second POST', async () => {
  const namespace = 'runner-ack-check', expectedBearer = 'fake-runner-ack-bearer';
  const fixture = await startAlgorithmFixture({ namespace, expectedBearer, mode: 'ack-loss', maxMs: 3000 });
  const name = 'alg-' + 'a'.repeat(40), jobRoute = '/apis/batch/v1/namespaces/' + namespace + '/jobs';
  const headers = { authorization: 'Bearer ' + expectedBearer, 'content-type': 'application/json' };
  try {
    assert.equal((await fetch(fixture.baseUrl + jobRoute + '/' + name, { headers, signal: AbortSignal.timeout(1000) })).status, 404);
    await assert.rejects(fetch(fixture.baseUrl + jobRoute, { method: 'POST', headers,
      body: JSON.stringify({ metadata: { name, namespace, annotations: { 'agent-platform.cn/identity-sha256': 'b'.repeat(64) } } }), signal: AbortSignal.timeout(1000) }));
    const originalUid = fixture.snapshot().jobUid;
    const observed = await fetch(fixture.baseUrl + jobRoute + '/' + name, { headers, signal: AbortSignal.timeout(1000) }).then(response => response.json());
    assert.equal(observed.metadata.uid, originalUid);
    assert.equal(observed.status.conditions[0].type, 'Complete');
    assert.equal(fixture.assertSinglePost().posts, 1);
  } finally { await fixture.close(); }
});

test('actual PowerShell scripts parse and the stop guard refuses a mismatched synthetic JAR before Stop-Process', () => {
  const parser = '$t=$null;$e=$null;[void][System.Management.Automation.Language.Parser]::ParseInput($env:ALG_SCRIPT,[ref]$t,[ref]$e);if($e.Count){$e|Out-String|Write-Error;exit 2}';
  for (const script of Object.values(ownershipScripts)) {
    const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', parser], {
      encoding: 'utf8', timeout: 5000, windowsHide: true, env: { ...process.env, ALG_SCRIPT: script },
    });
    assert.equal(result.status, 0, result.stderr);
  }
  // Synthetic commands only: never inspect or stop a real operating-system PID in this check.
  const fixture = 'function Get-CimInstance {[pscustomobject]@{Name="java.exe";CommandLine=\'java.exe -jar "G:\\reviewed\\wrong.jar"\';CreationDate=[datetime]"2026-10-20T00:00:00Z"}};'
    + 'function Stop-Process {throw "UNEXPECTED_STOP_REACHED"};'
    + 'try {& ([scriptblock]::Create($env:ALG_SCRIPT));throw "MISSING_REFUSAL"}catch {if($_.Exception.Message -ne "Refuse unrelated PID/JAR/creation"){throw};Write-Output "REFUSED_BEFORE_STOP"}';
  const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', fixture], {
    encoding: 'utf8', timeout: 5000, windowsHide: true, env: { ...process.env, ALG_SCRIPT: ownershipScripts.stop,
      ALG_PID: '777', ALG_CREATED: '2026-10-20T00:00:00.0000000Z', ALG_JAR: 'G:\\reviewed\\engine-runtime.jar' },
  });
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /REFUSED_BEFORE_STOP/);
});

test('artifact handoff rejects a missing root review marker before resolving artifact paths', async () => {
  const directory = await fs.mkdtemp(path.resolve(import.meta.dirname, '../docs/evidence/algorithm-process-runner/lock-check-'));
  const file = path.join(directory, 'not-reviewed.json');
  await fs.writeFile(file, JSON.stringify({ handoff: 'not-reviewed', 'engine-runtime': { directory: '../target' } }));
  await assert.rejects(loadArtifactLock(file), /Root reviewed artifact handoff required/);
  // Keep the finite input as evidence; never touch a real JAR or target directory.
});
