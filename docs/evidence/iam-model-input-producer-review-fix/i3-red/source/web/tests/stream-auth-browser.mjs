import { chromium, expect } from '@playwright/test';
import path from 'node:path';
import fs from 'node:fs/promises';
const root = path.resolve(import.meta.dirname, '../..');
const browser = await chromium.launchPersistentContext(path.join(root, '.local/chromium-stream-auth'), {
  executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true,
});
const ok = data => ({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 'OK', data }) });
const eof = { status: 200, contentType: 'text/event-stream', body: 'event: stream_status\ndata: {"chunkReplay":false}\n\n' };
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };
const results = [];
browser.setDefaultTimeout(5000);
const run = async (name, test) => {
  try { const evidence = await test(); results.push({ name, passed: true, ...evidence }); }
  catch (error) { results.push({ name, passed: false, error: error.message }); }
  console.log(JSON.stringify(results.at(-1)));
};
async function submit(page, sdk) {
  await page.goto('http://127.0.0.1:15175/stream-recovery.html' + (sdk ? '?sdk=1' : ''));
  if (sdk) await page.getByRole('button', { name: '打开智能助手' }).click();
  await (sdk ? page.getByRole('textbox', { name: '输入问题' }) : page.getByPlaceholder('输入问题，Shift+Enter 换行')).fill('auth boundary fixture');
  await page.getByRole('button', { name: sdk ? '发送' : '提交运行', exact: true }).click();
}
try {
  for (const [status, contentType, body, message, label = ''] of [
    [403, 'text/plain', 'forbidden', '(403)'],
    [401, 'text/plain', '', '(401)'],
    [404, 'text/html', '<html>not found</html>', '(404)'],
    [403, 'application/json', '{"code":"DENIED","message":"没有访问权限"}', '没有访问权限'],
    [403, 'application/json', '{"code":"OK","data":{"status":"SUCCEEDED"}}', '(403)', 'HTTP overrides envelope'],
  ]) await run(`SDK status ${status} ${contentType} ${label}`.trim(), async () => {
    const page = await browser.newPage(); let creates = 0, streams = 0, reads = 0;
    try {
      await page.addInitScript(() => {
        const native = window.setTimeout;
        window.setTimeout = (fn, delay, ...args) => native(fn, [750,1500,3000,6000,8000].includes(delay) ? 0 : delay, ...args);
      });
      await page.route('**/v1/**', route => {
        const p = new URL(route.request().url()).pathname;
        if (p.endsWith('/input-schema')) return route.fulfill(ok({ type: 'FLOW', inputs: [] }));
        if (p.endsWith('/runs')) { creates++; return route.fulfill(ok({ runId: 'r_auth' })); }
        if (p.endsWith('/events')) { streams++; return route.fulfill(eof); }
        reads++; return route.fulfill({ status, contentType, body });
      });
      await submit(page, true);
      await expect(page.getByRole('button', { name: '发送', exact: true })).toBeEnabled();
      const error = await page.getByRole('alert').innerText();
      const tickets = await page.evaluate(() => window.fixtureTickets);
      console.log(JSON.stringify({ probe: 'SDK error response', status, creates, streams, reads, tickets, error }));
      expect(reads).toBe(1); expect(creates).toBe(1); expect(streams).toBe(1); expect(tickets).toBe(4);
      expect(error).toContain(message); expect(error).not.toContain('Unexpected');
      return { creates, streams, reads, tickets, error };
    } finally { await page.close(); }
  });

  for (const mode of ['unmount', 'stop', 'new-login', 'active', 'sse-unmount', 'sse-new-login', 'sdk-destroy']) {
    await run(mode + ' late 401', async () => {
      const page = await browser.newPage(), started = deferred(), release = deferred(), replied = deferred();
      const sdk = mode === 'sdk-destroy', sse = mode.startsWith('sse-');
      let creates = 0, streams = 0, reads = 0, cancels = 0, cancelled = 0;
      page.on('dialog', dialog => dialog.accept());
      page.on('requestfailed', req => { if (req.url().endsWith(sse ? '/events' : '/r_auth')) cancelled++; });
      try {
        await page.route('**/v1/**', async route => {
          const p = new URL(route.request().url()).pathname;
          if (p.endsWith('/input-schema')) return route.fulfill(ok({ type: 'FLOW', inputs: [] }));
          if (p.endsWith('/cancel')) { cancels++; return route.fulfill(ok({})); }
          if (p.endsWith('/runs')) { creates++; return route.fulfill(ok({ runId: 'r_auth' })); }
          if (p.endsWith('/events')) { streams++; if (!sse) return route.fulfill(eof); }
          else reads++;
          expect(route.request().headers().authorization).toContain(sdk ? 'fixture-short-ticket-' : 'component-fixture-only');
          started.resolve(); await release.promise;
          await route.fulfill({ status: 401, contentType: 'application/json', body: '{"code":"UNAUTHORIZED","message":"revoked"}' });
          replied.resolve();
        });
        await submit(page, sdk); await started.promise;
        if (mode.includes('unmount') || sdk) await page.evaluate(() => window.disposeFixture());
        if (mode === 'stop') {
          await page.getByRole('button', { name: '停止运行', exact: true }).click();
          await expect(page.getByRole('button', { name: '停止运行', exact: true })).toHaveCount(0);
        }
        if (mode.includes('new-login')) await page.evaluate(async () => {
          const url = performance.getEntriesByType('resource').map(e => e.name).find(u => /\/shared\/api\.ts/.test(u));
          const { useAuth } = await import(url); useAuth().token = 'component-new-login';
        });
        release.resolve(); await replied.promise;
        if (mode === 'active' || mode.includes('new-login'))
          await page.getByRole('alert').filter({ hasText: sse ? '401' : 'revoked' }).waitFor();
        await page.waitForTimeout(150);
        if (mode.includes('unmount') || sdk)
          await expect(page.locator(sdk ? 'agent-platform-assistant' : '.chat')).toHaveCount(0);
        const authAfter = sdk ? undefined : await page.evaluate(() => window.fixtureAuth());
        const expectedAuth = mode === 'active' ? '' : mode.includes('new-login') ? 'component-new-login' : 'component-fixture-only';
        console.log(JSON.stringify({ probe: mode, creates, streams, reads, cancels, cancelled, authAfter }));
        if (!sdk) expect(authAfter).toBe(expectedAuth);
        expect(creates).toBe(1); expect(streams).toBe(1); expect(reads).toBe(sse ? 0 : 1); expect(cancels).toBe(mode === 'stop' ? 1 : 0);
        if (mode.includes('unmount') || sdk || mode === 'stop') expect(cancelled).toBe(1);
        return { creates, streams, reads, cancels, cancelled, authAfter };
      } finally { release.resolve(); await page.close(); }
    });
  }
  await fs.writeFile(path.join(root, '.local/frontend-stream-auth-browser.json'), JSON.stringify({ componentFixtureOnly: true, results }, null, 2));
  if (results.some(r => !r.passed)) process.exitCode = 1;
  else console.log(`PASS: ${results.length} Chrome auth boundary cases`);
} finally { await browser.close(); }
