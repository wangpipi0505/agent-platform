import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import http from 'node:http';
import { execFile, spawnSync } from 'node:child_process';
import { promisify } from 'node:util';

const source = await fs.readFile(new URL('./smoke-algorithm-processes.mjs', import.meta.url), 'utf8');
let start = source.indexOf('async function powershell(');
if (start < 0) start = source.indexOf('function powershell(');
const end = source.indexOf('export const ownershipScripts', start);
assert(start >= 0 && end > start);
const powershell = new Function('spawnSync', 'assert', 'execFileAsync', source.slice(start, end) + '\nreturn powershell;')(
  spawnSync, assert, promisify(execFile));

test('actual PowerShell wait leaves same-thread finite HTTP and timer responsive before it settles', async () => {
  const server = http.createServer((req, res) => res.end('finite-only'));
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  let pending;
  try {
    let settled = false;
    const timer = new Promise(resolve => setTimeout(resolve, 25));
    const response = fetch('http://127.0.0.1:' + server.address().port + '/finite', { signal: AbortSignal.timeout(2000) }).then(result => result.text());
    pending = Promise.resolve(powershell('Start-Sleep -Milliseconds 350; Write-Output "bounded"', {}, { timeoutMs: 2000, maxBuffer: 1024 }))
      .then(value => { settled = true; return value; });
    await Promise.all([timer, response]);
    assert.equal(settled, false, 'HTTP/timer must respond while the actual PowerShell helper is still waiting');
    assert.equal(await pending, 'bounded');
  } finally {
    if (pending) await pending;
    server.closeAllConnections(); await new Promise(resolve => server.close(resolve));
  }
});

test('one absolute PowerShell timeout rejects even a command designed to emit repeated output', async () => {
  const at = performance.now();
  await assert.rejects(async () => powershell('1..15 | ForEach-Object {Write-Output "tick"; Start-Sleep -Milliseconds 40}', {},
    { timeoutMs: 400, maxBuffer: 1024 }), error => error.killed === true && error.signal === 'SIGTERM');
  assert(performance.now() - at < 2000, 'Finite timeout must settle independently of output chunks');
});

for (const stream of ['stdout', 'stderr']) {
  test('PowerShell ' + stream + ' overflow rejects with the original bounded execution error', async () => {
    const script = stream === 'stdout' ? 'Write-Output ("x"*4096)' : '[Console]::Error.Write(("x"*4096))';
    await assert.rejects(async () => powershell(script, {}, { timeoutMs: 2000, maxBuffer: 128 }), error => {
      assert.equal(error.code, 'ERR_CHILD_PROCESS_STDIO_MAXBUFFER');
      assert.equal(typeof error[stream], 'string');
      assert(error[stream].length <= 128);
      return true;
    });
  });
}

test('nonzero PowerShell exit retains exit code and original stdout/stderr for cleanup error propagation', async () => {
  await assert.rejects(async () => powershell('Write-Output "finite-out";[Console]::Error.WriteLine("finite-error");exit 7', {}, { timeoutMs: 2000, maxBuffer: 1024 }), error => {
    assert.equal(error.code, 7); assert.match(error.stdout, /finite-out/); assert.match(error.stderr, /finite-error/); return true;
  });
});
