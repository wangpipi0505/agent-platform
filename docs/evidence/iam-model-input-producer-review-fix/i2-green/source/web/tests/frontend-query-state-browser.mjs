import { chromium } from '@playwright/test';
import fs from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';

const root = path.resolve(import.meta.dirname, '../..');
const phase = process.argv[2] || 'green';
const context = await chromium.launchPersistentContext(path.join(root, '.local/chromium-query-state-' + phase), { executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true, viewport: { width: 1440, height: 1000 } });
const result = { componentFixtureOnly: true, phase, requests: [], pageErrors: [] };
const ok = data => ({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 'OK', data }) });
try {
  const statistics = await context.newPage();
  statistics.on('pageerror', e => result.pageErrors.push(e.message));
  await statistics.route('**/admin/**', async route => {
    const url = new URL(route.request().url());
    if (!url.pathname.startsWith('/admin/') || url.pathname.startsWith('/admin/src/')) return route.continue();
    result.requests.push({ path: url.pathname, query: Object.fromEntries(url.searchParams) });
    if (url.pathname === '/admin/statistics') return route.fulfill(ok({ groups: [], skillGroups: [], groupsTotal: url.searchParams.has('appCode') ? 1 : 41, skillGroupsTotal: 1, page: Number(url.searchParams.get('page')), pageSize: 20 }));
    return route.fulfill(ok([]));
  });
  await statistics.goto('http://127.0.0.1:15175/statistics-lifecycle.html#/statistics');
  await statistics.getByText('共 41 个分组', { exact: false }).waitFor();
  await statistics.getByPlaceholder('全部应用', { exact: true }).fill('unsubmitted_app');
  await statistics.locator('.el-pagination').getByText('2', { exact: true }).click();
  await statistics.getByText('第 2 页', { exact: false }).first().waitFor();
  const paging = result.requests.filter(r => r.path === '/admin/statistics');
  result.unsavedFilterPagination = { queries: paging.map(r => r.query), passed: !paging[1].query.appCode && paging[1].query.page === '2' };
  await statistics.screenshot({ path: path.join(root, 'docs/evidence/frontend-query-state-fix-' + phase + '-pagination.png'), fullPage: true });
  await statistics.getByRole('button', { name: '查询统计', exact: true }).click();
  await statistics.getByText('共 1 个分组', { exact: false }).first().waitFor();
  const submitted = result.requests.filter(r => r.path === '/admin/statistics').at(-1).query;
  result.submittedSmallCohortStartsPageOne = submitted.appCode === 'unsubmitted_app' && submitted.page === '1';

  const history = await context.newPage();
  history.on('pageerror', e => result.pageErrors.push(e.message));
  let release, started;
  const gate = new Promise(resolve => { release = resolve; });
  const requestStarted = new Promise(resolve => { started = resolve; });
  await history.route('**/admin/**', async route => {
    const url = new URL(route.request().url());
    if (!url.pathname.startsWith('/admin/') || url.pathname.startsWith('/admin/src/')) return route.continue();
    result.requests.push({ path: url.pathname, query: Object.fromEntries(url.searchParams) });
    if (url.pathname.endsWith('/versions/diff')) { started(); await gate; return route.fulfill(ok({ fromVersion: 1, toVersion: 2, changes: [{ operation: 'REPLACE', path: '/description', before: 'v1', after: 'stale v2' }] })); }
    if (url.pathname.endsWith('/versions')) return route.fulfill(ok([{ versionNo: 2 }, { versionNo: 1 }]));
    if (url.pathname === '/admin/apps/source') return route.fulfill(ok({ code: 'source', name: '组件 fixture', status: 'PUBLISHED', type: 'CHAT', config: {}, tags: [], draftRev: 2, currentVersionNo: 2 }));
    return route.fulfill(ok([]));
  });
  await history.goto('http://127.0.0.1:15175/statistics-lifecycle.html#/apps/source');
  await history.getByRole('button', { name: '对照版本', exact: true }).click(); await requestStarted;
  await history.locator('.history-select .el-select').nth(1).click();
  await history.getByRole('option', { name: 'v1', exact: true }).click();
  const response = history.waitForResponse(r => r.url().includes('/versions/diff?')); release(); await response;
  await history.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  result.diffLateResponse = { selectorText: await history.locator('.history-select').innerText(), staleResultVisible: await history.getByText('stale v2', { exact: true }).count() > 0 };
  await history.screenshot({ path: path.join(root, 'docs/evidence/frontend-query-state-fix-' + phase + '-comparison.png'), fullPage: true });
  await fs.writeFile(path.join(root, 'docs/evidence/frontend-query-state-fix-' + phase + '.json'), JSON.stringify(result, null, 2));
  process.stdout.write(JSON.stringify(result) + '\n');
  assert.equal(result.unsavedFilterPagination.passed, true, 'Page two must retain applied filters');
  assert.equal(result.submittedSmallCohortStartsPageOne, true);
  assert.equal(result.diffLateResponse.staleResultVisible, false, 'Changed selectors must invalidate delayed response');
  assert.deepEqual(result.pageErrors, []);
} finally { await context.close(); }
