import fs from 'node:fs/promises';
import assert from 'node:assert/strict';

// Execute only the actual responsiveness test callback with fake HTTP/PS; no socket or PS starts.
const source = await fs.readFile(new URL('./algorithm-process-runner-async.test.mjs', import.meta.url), 'utf8');
const start = source.indexOf("test('actual PowerShell wait"), end = source.indexOf("test('one absolute", start);
assert(start >= 0 && end > start);
const mode = process.argv[2], closeCalls = [], unhandled = [];
const httpError = Error('synthetic HTTP failure'), psError = Error('synthetic PowerShell failure');
const destroyError = Error('synthetic connection destroy failure'), closeError = Error('synthetic close failure');
process.on('unhandledRejection', error => unhandled.push(error.message));
const server = { once() {}, listen(_port, _host, ready) { ready(); }, address() { return { port: 1 }; },
  closeAllConnections() { closeCalls.push('closeAllConnections'); if (mode === 'all-close-errors') throw destroyError; },
  close(done) { closeCalls.push('close'); if (mode === 'close-never-settles') return; done(mode === 'all-close-errors' ? closeError : undefined); } };
let callback;
new Function('test', 'http', 'assert', 'fetch', 'powershell', 'setTimeout', 'clearTimeout', source.slice(start, end))(
  (_name, run) => { callback = run; }, { createServer: () => server }, assert,
  () => mode === 'double-failure' ? new Promise((_resolve, reject) => setTimeout(() => reject(httpError), 1))
    : Promise.resolve({ text: () => Promise.resolve('finite-only') }),
  () => mode === 'fast-rejection' ? Promise.reject(psError) : new Promise((resolve, reject) => setTimeout(() => {
    if (mode === 'close-never-settles') resolve('bounded'); else reject(psError);
  }, 40)),
  (callback, ms) => setTimeout(callback, ms === 1000 ? 30 : ms), clearTimeout,
);
let observed;
try { await callback(); } catch (error) { observed = error; }
await new Promise(resolve => setTimeout(resolve, 60));
const errors = observed instanceof AggregateError ? observed.errors : observed ? [observed] : [];
process.stdout.write(JSON.stringify({ mode, noRealResourcesStarted: true, closeCalls, unhandled,
  errors: errors.map(error => ({ message: error.message, code: error.code })),
  originalHttpRetained: errors.includes(httpError), originalPsRetained: errors.includes(psError),
  destroyErrorRetained: errors.includes(destroyError), closeErrorRetained: errors.includes(closeError),
  httpIsPrimary: observed === httpError || observed?.cause === httpError,
}) + '\n');
