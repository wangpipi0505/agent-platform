import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import { cleanupSchedulerSmoke } from './scheduler-smoke-cleanup.mjs';

function resources(failures = {}) {
  const calls = [];
  const run = name => {
    calls.push(name);
    if (Object.hasOwn(failures, name)) throw failures[name];
  };
  return {
    calls,
    options: {
      lock: { exitCode: null, stdin: { end: text => { assert.equal(text, 'rollback;\n\\q\n'); run('lock'); } } },
      children: [{ pid: 101 }, { pid: 102 }],
      processRecords: [{ pid: 101 }, { pid: 102 }],
      stop: async ({ child, record }) => { assert.equal(record.pid, child.pid); run('stop:' + child.pid); record.stopped = true; },
      fixture: { close: done => { try { run('peer'); done?.(); } catch (error) { if (done) done(error); else throw error; } } },
      sockets: new Set([{ destroy: () => run('socket:1') }, { destroy: () => run('socket:2') }]),
      writeRecords: async () => run('records')
    }
  };
}

const allCalls = ['lock', 'stop:101', 'stop:102', 'peer', 'socket:1', 'socket:2', 'records'];

test('first stop refusal still attempts every later resource and preserves the validation error', { timeout: 1000 }, async () => {
  const validation = new Error('scheduler validation failed');
  const refusal = new Error('Refuse unrelated PID');
  const { options, calls } = resources({ 'stop:101': refusal });
  let thrown;
  try { await cleanupSchedulerSmoke({ ...options, failure: { error: validation } }); } catch (error) { thrown = error; }
  assert.deepEqual(calls, allCalls);
  assert(thrown instanceof AggregateError);
  assert.equal(thrown.cause, validation);
  assert.equal(thrown.errors[0], validation);
  assert.equal(thrown.errors[1].cause, refusal);
  assert.match(thrown.errors[1].message, /101/);
  assert.equal(options.processRecords[0].stopped, undefined);
  assert.equal(options.processRecords[1].stopped, true);
});

test('all cleanup failures remain inspectable, including synchronous, peer callback and final write failures', { timeout: 1000 }, async () => {
  const failures = Object.fromEntries(allCalls.map(name => [name, new Error(name + ' failed')]));
  const { options, calls } = resources(failures);
  let thrown;
  try { await cleanupSchedulerSmoke(options); } catch (error) { thrown = error; }
  assert.deepEqual(calls, allCalls);
  assert(thrown instanceof AggregateError);
  assert.equal(thrown.errors.length, allCalls.length);
  assert.deepEqual(new Set(thrown.errors.map(error => error.cause)), new Set(Object.values(failures)));
  assert(thrown.errors.every(error => error.message.startsWith('Cleanup failed: ')));
});

test('successful cleanup keeps the normal path and writes final stopped records', { timeout: 1000 }, async () => {
  const { options, calls } = resources();
  options.writeRecords = async () => { assert(options.processRecords.every(record => record.stopped)); calls.push('records'); };
  await cleanupSchedulerSmoke(options);
  assert.deepEqual(calls, allCalls);
});

test('successful cleanup rethrows the original validation error unchanged, including falsy throws', { timeout: 1000 }, async () => {
  for (const original of [new Error('original assertion'), undefined, null, false]) {
    const { options, calls } = resources();
    let caught = false;
    try { await cleanupSchedulerSmoke({ ...options, failure: { error: original } }); } catch (error) { caught = true; assert.equal(error, original); }
    assert(caught);
    assert.deepEqual(calls, allCalls);
  }
});

test('cleanup failure alone rejects instead of reporting a successful validation', { timeout: 1000 }, async () => {
  const refusal = new Error('CIM access denied');
  const { options, calls } = resources({ 'stop:101': refusal });
  await assert.rejects(cleanupSchedulerSmoke(options), error => error instanceof AggregateError && error.errors[0].cause === refusal);
  assert.deepEqual(calls, allCalls);
});

test('final records are attempted while peer close waits for its sockets', { timeout: 1000 }, async () => {
  const { options, calls } = resources();
  const peerError = new Error('peer close failed after sockets');
  let closed;
  options.fixture.close = done => { calls.push('peer'); closed = done; };
  options.writeRecords = async () => { calls.push('records'); closed(peerError); };
  await assert.rejects(cleanupSchedulerSmoke(options), error => error instanceof AggregateError && error.errors[0].cause === peerError);
  assert.deepEqual(calls, allCalls);
});

test('one absent resource and an already closed peer are idempotent', { timeout: 1000 }, async () => {
  for (const absent of ['lock', 'children', 'fixture', 'sockets']) {
    const { options, calls } = resources();
    options[absent] = ['children', 'sockets'].includes(absent) ? [] : undefined;
    await cleanupSchedulerSmoke(options);
    assert.equal(calls.at(-1), 'records');
  }
  const { options, calls } = resources();
  options.lock.exitCode = 0;
  options.children = [];
  options.sockets = [];
  options.fixture.close = done => { calls.push('peer'); done(Object.assign(new Error('already closed'), { code: 'ERR_SERVER_NOT_RUNNING' })); };
  await cleanupSchedulerSmoke(options);
  await cleanupSchedulerSmoke(options);
  assert.deepEqual(calls, ['peer', 'records', 'peer', 'records']);
});

test('live script calls the tested helper without changing PID/JAR ownership checks', async () => {
  const source = await fs.readFile(new URL('./smoke-run-scheduler-replicas.mjs', import.meta.url), 'utf8');
  const baseline = await fs.readFile(new URL('../docs/evidence/scheduler-smoke-cleanup-fix/baseline/tests/smoke-run-scheduler-replicas.mjs', import.meta.url), 'utf8');
  const stopBody = text => text.slice(text.indexOf('async function stop(engine) {'), text.indexOf('\nfunction seed('));
  assert.equal(stopBody(source), stopBody(baseline));
  assert.match(source, /import \{ cleanupSchedulerSmoke \} from '\.\/scheduler-smoke-cleanup\.mjs';/);
  assert.match(source, /await cleanupSchedulerSmoke\(/);

  // Execute only the live script's actual catch/finally, replacing its live validation body.
  const declarations = source.indexOf('let first, second');
  const mainTry = source.indexOf('\ntry {', declarations);
  const mainCatch = source.lastIndexOf('\n} catch (error) {');
  assert(mainCatch > mainTry);
  const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
  const run = new AsyncFunction('scope', 'cleanupSchedulerSmoke', 'fs', 'path', 'root',
    'const {lock, children, processRecords, stop, fixture, sockets} = scope;\n' +
    source.slice(declarations, mainTry) + '\ntry { await scope.validate();' + source.slice(mainCatch));
  const validation = new Error('live validation failure');
  const refusal = new Error('Refuse unrelated PID');
  const { options, calls } = resources({ 'stop:101': refusal });
  options.validate = async () => { throw validation; };
  const fakeFs = { writeFile: async (file, data) => {
    assert.equal(file, path.join('owned-smoke', '.local/scheduler-live-processes.json'));
    assert.deepEqual(JSON.parse(data), [{ pid: 101 }, { pid: 102, stopped: true }]);
    await options.writeRecords();
  } };
  await assert.rejects(run(options, cleanupSchedulerSmoke, fakeFs, path, 'owned-smoke'),
    error => error instanceof AggregateError && error.cause === validation && error.errors[1].cause === refusal);
  assert.deepEqual(calls, allCalls);
});
