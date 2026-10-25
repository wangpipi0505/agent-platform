import { chromium, expect } from '@playwright/test';
import path from 'node:path';
import fs from 'node:fs/promises';

const root = path.resolve(import.meta.dirname, '../..');
const browser = await chromium.launchPersistentContext(path.join(root, '.local/chromium-stream-snapshot'), {
  executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true,
  viewport: { width: 1440, height: 960 },
});
const ok = data => ({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 'OK', data }) });
const sse = body => ({ status: 200, contentType: 'text/event-stream', body });
const chunk = text => `event: chunk\ndata: ${JSON.stringify({ text })}\n\n`;
const fact = (seq, type, data = {}) => `id: ${seq}\nevent: ${type}\ndata: ${JSON.stringify(data)}\n\n`;
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { resolve, promise }; };
const finals = [
  { name: 'structured', status: 'SUCCEEDED', output: { answer: ['完整', '权威结果'] } },
  { name: 'empty-object', status: 'SUCCEEDED', output: {} },
  { name: 'empty-text', status: 'SUCCEEDED', output: { text: '' } },
  { name: 'empty-string', status: 'SUCCEEDED', output: '' },
  { name: 'null-output', status: 'SUCCEEDED', output: null },
  { name: 'pass-warning', status: 'SUCCEEDED', output: { text: '保留权威结果', outputValidation: { valid: false, onInvalid: 'PASS_WITH_FLAG', reason: '最终格式提示' } } },
  { name: 'failed', status: 'FAILED', output: {}, error: { semantic: 'MODEL_ERROR', message: '权威失败原因' } },
  { name: 'cancelled', status: 'CANCELLED', error: { message: '权威取消原因' } },
];
const results = [], failures = [];
const check = async (name, action) => { try { await action(); } catch (e) { failures.push({ name, error: e.message }); } };
try {
  for (const sdk of [false, true]) for (const final of finals) {
    const mode = sdk ? 'sdk' : 'webapp', name = `${mode}-${final.name}`;
    const page = await browser.newPage();
    const second = deferred(), third = deferred(), fourth = deferred(), finish = deferred();
    let creates = 0, streams = 0, reads = 0;
    const errors = [], cursors = [];
    page.on('pageerror', e => errors.push(e.message));
    await page.route('**/v1/**', async route => {
      const p = new URL(route.request().url()).pathname;
      if (p.endsWith('/input-schema')) return route.fulfill(ok({ type: 'FLOW', inputs: [] }));
      if (p.endsWith('/runs')) { creates++; return route.fulfill(ok({ runId: 'r_snapshot' })); }
      if (p.endsWith('/approvals')) return route.fulfill(ok([]));
      if (p.endsWith('/events')) {
        streams++; cursors.push(route.request().headers()['last-event-id']);
        if (streams === 1) return route.fulfill(sse(chunk('原始临时片段。')));
        if (streams === 2) { await second.promise; return route.fulfill(sse(chunk('恢复后的未来片段。'))); }
        if (streams === 3) {
          await third.promise;
          return route.fulfill(sse(chunk('等待确认后的未来片段。')));
        }
        if (streams === 4) {
          await fourth.promise;
          return route.fulfill(sse(fact(9, 'RUN_FINISHED', {
            output: { text: '事实中的临时无效答案', outputValidation: { valid: false, reason: '旧invalid提示' } },
          })));
        }
        throw Error('Unexpected fifth stream');
      }
      reads++;
      if (reads === 1) return route.fulfill(ok({ status: 'RUNNING', eventsWatermark: 4, output: {} }));
      if (reads === 2) return route.fulfill(ok({ status: 'WAITING_APPROVAL', eventsWatermark: 8, output: { text: '非终态快照不应替换' } }));
      if (reads === 3) return route.fulfill(ok({ status: 'RUNNING', eventsWatermark: 8, output: {} }));
      await finish.promise;
      return route.fulfill(ok({ runId: 'r_snapshot', eventsWatermark: 9, ...final }));
    });
    try {
      await page.goto('http://127.0.0.1:15175/stream-recovery.html' + (sdk ? '?sdk=1' : ''));
      if (sdk) await page.getByRole('button', { name: '打开智能助手' }).click();
      const input = sdk ? page.getByRole('textbox', { name: '输入问题' }) : page.getByPlaceholder('输入问题，Shift+Enter 换行');
      const send = page.getByRole('button', { name: sdk ? '发送' : '提交运行', exact: true });
      const answer = sdk ? page.locator('agent-platform-assistant .log .bubble').last() : page.locator('.chat .bubble.assistant > div').last();
      await input.fill('快照状态回归'); await send.click();
      await expect.poll(() => streams).toBe(2);
      await check(`${name}: RUNNING {} preserves chunks`, async () => expect((await answer.innerText()).trim()).toBe('原始临时片段。'));
      await check(`${name}: reconnect warns of missing chunks`, async () => expect(await page.getByRole('status').allTextContents()).toEqual(expect.arrayContaining([expect.stringContaining('临时片段可能缺失')])));
      second.resolve();
      await expect.poll(() => streams).toBe(3);
      await check(`${name}: WAITING_APPROVAL and future chunks`, async () => expect((await answer.innerText()).trim()).toBe('原始临时片段。恢复后的未来片段。'));
      await check(`${name}: remains busy before terminal`, async () => { if (sdk) await expect(send).toBeDisabled(); else await expect(send).toHaveCount(0); });
      if (final.name === 'structured') await page.screenshot({ path: path.join(root, `docs/evidence/frontend-stream-snapshot-${mode}-recovered.png`) });
      third.resolve();
      await expect.poll(() => streams).toBe(4);
      await check(`${name}: future chunks after WAITING_APPROVAL`, async () => expect((await answer.innerText()).trim()).toBe('原始临时片段。恢复后的未来片段。等待确认后的未来片段。'));
      fourth.resolve();
      await page.getByText('事实中的临时无效答案', { exact: true }).waitFor();
      await page.getByText(/旧invalid提示/).waitFor();
      finish.resolve();
      await expect(send).toBeEnabled();
      const shown = await answer.innerText();
      const value = final.status === 'FAILED' || final.status === 'CANCELLED' ? final.error : final.output;
      const expected = typeof value === 'string' ? value : typeof value?.text === 'string' ? value.text : JSON.stringify(value ?? '运行尚无输出', null, 2);
      await check(`${name}: terminal replaces provisional answer`, async () => expect(shown.replace(/\s+/g, ' ')).toContain(expected.replace(/\s+/g, ' ')));
      await check(`${name}: empty terminal clears text`, async () => { if (expected === '') expect(shown.trim()).toBe(''); });
      await check(`${name}: no stale content or warning`, async () => {
        expect(shown).not.toContain('临时'); expect(shown).not.toContain('旧invalid提示');
        expect(await page.locator(sdk ? 'agent-platform-assistant .output-warning' : '.chat .output-warning').count()).toBe(final.name === 'pass-warning' ? 1 : 0);
      });
      if (final.name === 'pass-warning') await expect(page.getByText(/最终格式提示/)).toBeVisible();
      expect({ creates, streams, reads }).toEqual({ creates: 1, streams: 4, reads: 4 });
      expect(cursors).toEqual(['0', '4', '8', '8']); expect(errors).toEqual([]);
      results.push({ name, creates, streams, reads, cursors, terminalStatus: final.status, finalText: shown });
      process.stdout.write(`Completed ${name}\n`);
    } catch (e) { failures.push({ name, error: e.message }); }
    finally { second.resolve(); third.resolve(); fourth.resolve(); finish.resolve(); await page.close(); }
  }
  await fs.writeFile(path.join(root, '.local/frontend-stream-snapshot-browser.json'), JSON.stringify({ componentFixtureOnly: true, results, failures }, null, 2));
  for (const failure of failures) process.stderr.write(`${failure.name}: ${failure.error}\n`);
  process.stdout.write(`${results.length} controlled Chrome cases completed; ${failures.length} failed assertions.\n`);
  if (failures.length) process.exitCode = 1;
} finally { await browser.close(); }
