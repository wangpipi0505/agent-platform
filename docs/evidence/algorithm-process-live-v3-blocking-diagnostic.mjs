import fs from 'node:fs/promises';
import path from 'node:path';
import http from 'node:http';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import { spawn, spawnSync } from 'node:child_process';
import { ownershipScripts } from '../../tests/smoke-algorithm-processes.mjs';

const sourcePath = path.resolve(import.meta.dirname, '../../tests/smoke-algorithm-processes.mjs');
const source = await fs.readFile(sourcePath, 'utf8');
const start = source.indexOf('function powershell('), end = source.indexOf('export const ownershipScripts', start);
assert(start >= 0 && end > start);
const powershell = new Function('spawnSync', 'assert', source.slice(start, end) + '\nreturn powershell;')(spawnSync, assert);
const output = { at: new Date().toISOString(), runnerSha256: crypto.createHash('sha256').update(source).digest('hex'),
  realBusinessOrJobContacted: false, originalHelperChanged: false, probes: [] };

for (const [label, script, values] of [
  ['actual recorded-child PID/port inspection after cleanup', ownershipScripts.inspect, { ALG_PID: '62900', ALG_PORT: '55459' }],
  ['finite synthetic PowerShell delay', 'Start-Sleep -Milliseconds 350; Write-Output "finite-probe"', {}],
]) {
  let receivedAt, timerAt, child;
  const server = http.createServer((req, res) => {
    receivedAt = performance.now();
    assert.equal(req.url, '/finite-probe'); res.end('finite-http-only');
  });
  server.requestTimeout = 1000; server.headersTimeout = 1000;
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  let timeout;
  try {
    const client = `process.send({ready:true});setTimeout(async()=>{const at=performance.now();try{const r=await fetch(process.env.FINITE_TEST_URL,{signal:AbortSignal.timeout(3000)});const text=await r.text();process.send({status:r.status,text,elapsedMs:performance.now()-at},()=>process.disconnect());}catch(error){process.send({error:error.message},()=>process.disconnect());}},25);`;
    child = spawn(process.execPath, ['-e', client], { windowsHide: true,
      env: { SystemRoot: process.env.SystemRoot, PATH: process.env.PATH, FINITE_TEST_URL: 'http://127.0.0.1:' + server.address().port + '/finite-probe' },
      stdio: ['ignore', 'pipe', 'pipe', 'ipc'] });
    const messages = [];
    let readyResolve, resultResolve;
    const ready = new Promise(resolve => { readyResolve = resolve; });
    const result = new Promise(resolve => { resultResolve = resolve; });
    child.on('message', message => { messages.push(message); if (message.ready) readyResolve(); else resultResolve(message); });
    child.on('error', resultResolve);
    const deadline = new Promise((resolve, reject) => { timeout = setTimeout(() => reject(Error('Finite client deadline exceeded')), 5000); });
    await Promise.race([ready, deadline]);
    const before = performance.now();
    const timer = new Promise(resolve => setTimeout(() => { timerAt = performance.now(); resolve(); }, 25));
    const raw = powershell(script, values); // The unchanged real sync helper blocks the fixture thread here.
    const returnedAt = performance.now();
    const response = await Promise.race([result, deadline]);
    await timer;
    assert.equal(response.status, 200, JSON.stringify(response)); assert.equal(response.text, 'finite-http-only');
    assert(receivedAt >= returnedAt, 'Local HTTP handler could not run until PowerShell returned');
    assert(timerAt >= returnedAt, '25ms timer could not run until PowerShell returned');
    output.probes.push({ label, helperElapsedMs: returnedAt - before, scheduledTimerMs: 25,
      timerActualMs: timerAt - before, httpHandlerAfterHelperReturnMs: receivedAt - returnedAt,
      clientHttpElapsedMs: response.elapsedMs, observedOwnPidAbsent: label.startsWith('actual') ? JSON.parse(raw).pid === null : undefined,
      httpAndTimerBlockedUntilHelperReturned: true });
  } finally {
    clearTimeout(timeout);
    if (child?.exitCode === null && child.signalCode === null) {
      await new Promise(resolve => { child.once('exit', resolve); const bounded = setTimeout(() => { child.kill(); }, 500); child.once('exit', () => clearTimeout(bounded)); });
    }
    server.closeAllConnections(); await new Promise(resolve => server.close(resolve));
  }
}
await fs.writeFile(path.join(import.meta.dirname, 'algorithm-process-live-v3-blocking-diagnostic.json'), JSON.stringify(output, null, 2));
process.stdout.write(JSON.stringify(output, null, 2) + '\n');
