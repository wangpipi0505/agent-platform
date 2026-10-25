import { chromium } from '@playwright/test';
import assert from 'node:assert/strict';
import path from 'node:path';
import crypto from 'node:crypto';

// Called by the root protocol runner while its finite local peer is alive.
// Uses the real portal sign-in and gateway; there are no intercepted API routes.
export async function verifyMcpDraftInBrowser(code) {
  assert(/^smoke_mcp_good_[a-f0-9]{10}$/.test(code), 'Only this runner\'s own draft');
  const root = path.resolve(import.meta.dirname, '../..');
  const browser = await chromium.launchPersistentContext(
    path.join(root, '.local/chromium-mcp-live-' + crypto.randomUUID()),
    { executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true, viewport: { width: 1440, height: 1000 } },
  );
  const page = await browser.newPage();
  const requests = [], pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.message));
  page.on('request', request => {
    const url = new URL(request.url());
    // Never record credentials, Authorization headers or authentication responses.
    if (url.pathname.startsWith('/admin/')) requests.push({ method: request.method(), path: url.pathname });
  });
  try {
    await page.goto('http://127.0.0.1:15173/#/skills/' + code);
    await page.getByRole('button', { name: '进入开发工作台', exact: true }).click();
    const panel = page.getByRole('region', { name: 'MCP 草稿工具检查', exact: true });
    await panel.waitFor();
    assert.match(await panel.innerText(), /所选版本 1.*绑定工具 lookup/);
    const endpoint = '/admin/skills/' + code + '/drafts/1/mcp-tools';
    const responsePromise = page.waitForResponse(response => new URL(response.url()).pathname === endpoint && response.request().method() === 'POST');
    await panel.getByRole('button', { name: '检查远端工具', exact: true }).click();
    const response = await responsePromise;
    assert.equal(response.status(), 200);
    assert.equal(response.request().postData(), null);
    const envelope = await response.json();
    assert.equal(envelope.code, 'OK');
    assert.deepEqual(envelope.data.tools.map(tool => tool.name), ['unrelated', 'lookup']);
    await panel.getByText('已找到准确绑定名称。请继续核对输入 Schema。', { exact: true }).waitFor();
    const tool = panel.locator('article').filter({ has: page.locator('strong code').filter({ hasText: /^lookup$/ }) });
    await tool.getByText('展开完整原始 inputSchema', { exact: true }).click();
    assert.match(await tool.locator('pre').innerText(), /"pattern": "\^A\$"/);
    for (const width of [1440, 390]) {
      await page.setViewportSize({ width, height: width === 390 ? 844 : 1000 });
      await page.screenshot({ path: path.join(root, 'docs/evidence/frontend-mcp-live-tools-' + width + '.png'), fullPage: true, animations: 'disabled' });
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, 'MCP detail must fit ' + width);
    }
    const writes = requests.filter(request => request.method !== 'GET');
    assert.deepEqual(writes, [{ method: 'POST', path: endpoint }]);
    assert.deepEqual(pageErrors, []);
    return { realGatewayAndSavedDraft: true, mockedRoutes: 0, code, version: 1, toolName: 'lookup', bodylessDiscovery: true, rawSchemaVisible: true, viewportWidths: [1440, 390], requests, pageErrors };
  } finally { await browser.close(); }
}
