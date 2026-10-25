import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { Writable } from 'node:stream';
import test from 'node:test';
import { cleanupSchedulerSmoke } from './scheduler-smoke-cleanup.mjs';

const helperUrl = new URL('./scheduler-smoke-cleanup.mjs', import.meta.url).href;

test('real Writable nextTick EPIPE is aggregated without an unhandled event or skipped resources', { timeout: 3000 }, () => {
  const source = `
    import assert from 'node:assert/strict';
    import { Writable } from 'node:stream';
    import { cleanupSchedulerSmoke } from ${JSON.stringify(helperUrl)};
    const calls = [];
    const validation = new Error('original validation');
    const pipeError = Object.assign(new Error('closed lock pipe'), {code: 'EPIPE'});
    const stdin = new Writable({ write(chunk, encoding, done) {
      assert.equal(chunk.toString(), 'rollback;\\n\\\\q\\n');
      calls.push('lock-write');
      process.nextTick(() => done(pipeError));
    }});
    let thrown;
    try { await cleanupSchedulerSmoke({
      lock: {exitCode: null, stdin},
      children: [{pid:101}, {pid:102}], processRecords: [{pid:101}, {pid:102}],
      stop: async ({child}) => { calls.push('stop:' + child.pid); await new Promise(resolve => setImmediate(resolve)); },
      fixture: {close: done => {calls.push('peer'); done();}},
      sockets: new Set([{destroy: () => calls.push('socket')}]),
      writeRecords: async () => calls.push('records'), failure: {error: validation}, closeTimeoutMs: 30
    }); } catch (error) { thrown = error; }
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(calls, ['lock-write','stop:101','stop:102','peer','socket','records']);
    assert(thrown instanceof AggregateError);
    assert.equal(thrown.cause, validation);
    assert.equal(thrown.errors[0], validation);
    assert.equal(thrown.errors[1].cause, pipeError);
    console.log(JSON.stringify({calls, originalPreserved: true, code: thrown.errors[1].cause.code}));
  `;
  const child = spawnSync(process.execPath, ['--input-type=module', '-'], { input: source, encoding: 'utf8', timeout: 2000, windowsHide: true });
  assert.equal(child.error, undefined, String(child.error));
  assert.equal(child.status, 0, 'stdout: ' + child.stdout + '\nstderr: ' + child.stderr);
  assert.equal(child.stderr, '');
  assert.equal(JSON.parse(child.stdout).code, 'EPIPE');
});

test('peer that never closes rejects within the bound after a socket destroy failure', { timeout: 1000 }, async () => {
  const calls = [];
  const validation = new Error('original validation');
  const socketError = new Error('socket destroy failed');
  const cleanup = cleanupSchedulerSmoke({
    children: [], processRecords: [], stop: async () => {},
    fixture: { close: () => calls.push('peer') },
    sockets: new Set([{destroy: () => {calls.push('socket:1'); throw socketError;}}, {destroy: () => calls.push('socket:2')}]),
    writeRecords: async () => calls.push('records'), failure: {error: validation}, closeTimeoutMs: 30
  });
  let observer;
  const outcome = await Promise.race([cleanup.then(() => ({fulfilled: true}), error => ({error})),
    new Promise(resolve => { observer = setTimeout(() => resolve({pending: true}), 200); })]);
  clearTimeout(observer);
  assert.deepEqual(calls, ['peer','socket:1','socket:2','records']);
  assert.equal(outcome.pending, undefined, 'peer close must have a finite rejection bound');
  assert(outcome.error instanceof AggregateError);
  assert.equal(outcome.error.cause, validation);
  assert.equal(outcome.error.errors[0], validation);
  assert(outcome.error.errors.some(error => error.cause === socketError));
  assert(outcome.error.errors.some(error => /fixture peer/.test(error.message) && error.cause.code === 'ETIMEDOUT'));
});

test('peer with a missing tracked socket still reports its timeout', { timeout: 1000 }, async () => {
  const calls = [];
  const cleanup = cleanupSchedulerSmoke({
    children: [], processRecords: [], stop: async () => {},
    fixture: {close: () => calls.push('peer')}, sockets: new Set(),
    writeRecords: async () => calls.push('records'), closeTimeoutMs: 30
  });
  let observer;
  const outcome = await Promise.race([cleanup.then(() => ({fulfilled: true}), error => ({error})),
    new Promise(resolve => { observer = setTimeout(() => resolve({pending: true}), 200); })]);
  clearTimeout(observer);
  assert.deepEqual(calls, ['peer','records']);
  assert.equal(outcome.pending, undefined, 'missing sockets must not leave peer cleanup pending');
  assert(outcome.error instanceof AggregateError);
  assert.equal(outcome.error.errors.length, 1);
  assert.equal(outcome.error.errors[0].cause.code, 'ETIMEDOUT');
});

test('lock whose real Writable never completes is bounded and later resources still run', { timeout: 1000 }, async () => {
  const calls = [];
  const validation = new Error('original validation');
  const stdin = new Writable({write() {calls.push('lock-write');}});
  let thrown;
  try { await cleanupSchedulerSmoke({
    lock: {exitCode: null, stdin}, children: [{pid:101}], processRecords: [{pid:101}],
    stop: async () => calls.push('stop'), fixture: {close: done => {calls.push('peer'); done();}}, sockets: new Set(),
    writeRecords: async () => calls.push('records'), failure: {error: validation}, closeTimeoutMs: 30
  }); } catch (error) { thrown = error; }
  assert.deepEqual(calls, ['lock-write','stop','peer','records']);
  assert(thrown instanceof AggregateError);
  assert.equal(thrown.cause, validation);
  assert.equal(thrown.errors[1].cause.code, 'ETIMEDOUT');
});
