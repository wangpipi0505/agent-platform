import fs from 'node:fs/promises';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Review-only probe: extract the actual test callback and inject fake HTTP/PS.
// No sockets, PowerShell, JVM or external data services are created.
const directory = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(directory, '../../..');
const manifest = JSON.parse(await fs.readFile(path.join(root, 'docs/evidence/algorithm-process-runner-fixes-v3-sha256.json'), 'utf8'));
async function sources() {
  const result = {};
  for (const file of Object.keys(manifest.sources)) result[file] = crypto.createHash('sha256').update(await fs.readFile(path.join(root, file))).digest('hex');
  return result;
}
const before = await sources();
await fs.writeFile(path.join(directory, 'source-before.json'), JSON.stringify(before, null, 2));
const source = await fs.readFile(path.join(root, 'tests/algorithm-process-runner-async.test.mjs'), 'utf8');
const start = source.indexOf("test('actual PowerShell wait"), end = source.indexOf("test('one absolute", start);
assert(start >= 0 && end > start);
const actualTest = source.slice(start, end);
const results = [];
for (const rejectFetch of [false, true]) {
  const closeCalls = [], psFailure = Object.assign(Error('synthetic execFile rejection'), { code: 7 });
  const server = { once() {}, listen(_port, _host, ready) { ready(); }, address() { return { port: 1 }; },
    closeAllConnections() { closeCalls.push('closeAllConnections'); }, close(done) { closeCalls.push('close'); done(); } };
  let callback;
  new Function('test', 'http', 'assert', 'fetch', 'powershell', actualTest)(
    (_name, run) => { callback = run; }, { createServer: () => server }, assert,
    () => rejectFetch ? new Promise((_resolve, reject) => setTimeout(() => reject(Error('synthetic HTTP failure')), 1))
      : Promise.resolve({ text: () => Promise.resolve('finite-only') }),
    () => new Promise((_resolve, reject) => setTimeout(() => reject(psFailure), 40)),
  );
  let observed;
  try { await callback(); } catch (error) { observed = error; }
  assert.equal(observed, psFailure);
  assert.deepEqual(closeCalls, []);
  results.push({ case: rejectFetch ? 'HTTP and execFile both reject' : 'execFile rejects after HTTP succeeds',
    observedError: observed.message, code: observed.code, closeCalls, originalHttpFailureMasked: rejectFetch });
}
const after = await sources();
await fs.writeFile(path.join(directory, 'source-after.json'), JSON.stringify(after, null, 2));
assert.deepEqual(after, before);
for (const [file, expected] of Object.entries(manifest.sources)) assert.equal(before[file], expected);
console.log(JSON.stringify({ probe: 'actual async test callback cleanup with injected fake HTTP/execFile',
  noRealResourcesStarted: true, sourceUnchanged: true, results }, null, 2));
