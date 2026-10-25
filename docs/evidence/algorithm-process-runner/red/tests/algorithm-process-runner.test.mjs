import test from 'node:test';
import assert from 'node:assert/strict';
import { cleanupAlgorithmRun, assertProcessOwnership } from './smoke-algorithm-processes.mjs';
import { startAlgorithmFixture } from './smoke-algorithm-protocol.mjs';

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
