import { chromium } from '@playwright/test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';

// Only run after root explicitly confirms the new engine is ready. There is no route mock.
if (!process.argv.includes('--engine-ready')) throw Error('等待 root 明确通知 engine 就绪后，才可使用 --engine-ready 执行真实 API 联调');
const root = path.resolve(import.meta.dirname, '../..');
const fixture = JSON.parse(await fs.readFile(path.join(root, '.local/named-input-live-fixture.json'), 'utf8'));
assert.equal(fixture.noModelOrExternalCalls, true);
const suffix = crypto.randomUUID().slice(0, 8);
const evidence = {
  realApi: true, mockedRoutes: 0, fixtureApp: fixture.appCode, draftId: fixture.draftId,
  independentExpected: fixture.expected, checks: {}, requests: [], pageErrors: [], startedAt: new Date().toISOString(),
};
const browser = await chromium.launchPersistentContext(path.join(root, '.local/chromium-named-inputs-live-' + suffix), {
  executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true,
  viewport: { width: 1440, height: 1000 },
});
const page = await browser.newPage();
const pending = [];
const namedPath = '/admin/debug-inputs';
const requestPath = request => new URL(request.url()).pathname;
const responseFor = (pathname, method = 'GET') => {
  const response = page.waitForResponse(response => requestPath(response.request()) === pathname && response.request().method() === method);
  void response.catch(() => {}); // An earlier UI failure must not leave a detached rejection at page close.
  return response;
};
async function data(response) {
  const envelope = await response.json();
  assert.equal(response.ok(), true, JSON.stringify({ status: response.status(), code: envelope.code, message: envelope.message }));
  assert.equal(envelope.code, 'OK', envelope.message);
  return envelope.data;
}
async function screenshot(name) { await page.screenshot({ path: path.join(root, 'docs/evidence/frontend-test-inputs-live-' + name + '.png'), fullPage: true }); }
async function chooseNamed(name, revision) {
  await page.locator('.named-inputs .el-select').click();
  await page.getByRole('option', { name: `${name} · 版本 ${revision}`, exact: true }).click();
}
page.on('pageerror', error => evidence.pageErrors.push(error.message));
page.on('response', response => {
  const request = response.request(), url = new URL(request.url());
  // Never inspect or serialize authentication responses, headers, or tokens.
  if (!url.pathname.startsWith('/admin/')) return;
  const entry = { path: url.pathname, query: Object.fromEntries(url.searchParams), method: request.method(), status: response.status() };
  if (request.method() !== 'GET') entry.body = request.postDataJSON();
  evidence.requests.push(entry);
  pending.push(response.json().then(envelope => { entry.response = envelope; }).catch(error => { entry.observerError = error.message; }));
});

try {
  await page.goto(`http://127.0.0.1:15173/#/apps/${fixture.appCode}/orchestration/debug`);
  const login = page.getByRole('button', { name: '进入开发工作台', exact: true });
  if (await login.isVisible()) await login.click();
  const question = page.getByRole('textbox', { name: 'question', exact: true }).first();
  await question.waitFor();
  await Promise.all(pending);
  const application = evidence.requests.find(item => item.path === '/admin/apps/' + fixture.appCode && item.response?.data)?.response.data;
  assert(application, 'Must observe the real application detail');
  assert.equal(application.draftId, fixture.draftId);
  assert.equal(application.draftRev, fixture.draftRev);
  assert.equal(application.type, 'FLOW');
  assert(application.definition.nodes.every(node => ['START', 'END'].includes(node.type)));
  assert.deepEqual(application.definition.outputs.map(({ name, from }) => ({ name, from })), [{ name: 'answer', from: '${input.question}' }]);
  evidence.checks.realDraftAndNoExternalFlow = true;

  await question.fill(fixture.inputs.question);
  await page.getByRole('button', { name: '保存为命名输入', exact: true }).click();
  const dialog = page.getByRole('dialog');
  const name = '完整前端联调-' + suffix;
  await dialog.getByRole('textbox', { name: '测试输入名称', exact: true }).fill(name);
  const createdResponse = responseFor(namedPath, 'POST');
  await dialog.getByRole('button', { name: '保存测试输入', exact: true }).click();
  const saved = await data(await createdResponse);
  evidence.inputId = saved.id;
  assert.equal(saved.revision, 1);
  assert.deepEqual(saved.inputs, fixture.inputs);
  await dialog.waitFor({ state: 'hidden' });
  await chooseNamed(name, 1);
  await question.fill('未保存的临时修改-' + suffix);
  assert.notEqual(await question.inputValue(), fixture.inputs.question);
  await page.getByRole('button', { name: '使用输入', exact: true }).click();
  await page.getByText(`已使用「${name}」版本 1`, { exact: true }).waitFor();
  assert.equal(await question.inputValue(), fixture.inputs.question);
  evidence.checks.savedAndExplicitlyRestoredInput = true;
  await screenshot('debug-input');

  const startedResponse = responseFor('/admin/debug-runs', 'POST');
  const terminalResponse = page.waitForResponse(async response => {
    if (!/^\/admin\/debug-runs\/[^/]+\/status$/.test(requestPath(response.request()))) return false;
    const envelope = await response.json();
    return ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(envelope.data?.status);
  }, { timeout: 45000 });
  void terminalResponse.catch(() => {});
  await page.getByRole('button', { name: '开始调试', exact: true }).click();
  const started = await data(await startedResponse);
  evidence.runId = started.runId;
  const terminal = await data(await terminalResponse);
  assert.equal(terminal.runId, evidence.runId);
  assert.equal(terminal.status, 'SUCCEEDED', JSON.stringify(terminal.error));
  assert.deepEqual(terminal.output, fixture.expected, 'Known independent expected output must match the real FLOW output');
  evidence.debugOutput = terminal.output;
  evidence.checks.realDebugSucceededWithKnownOutput = true;
  await page.locator('.status').filter({ hasText: 'SUCCEEDED' }).first().waitFor();
  await screenshot('debug-succeeded');
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(250);
  evidence.flow390 = await page.evaluate(() => {
    const canvas = document.querySelector('.canvas');
    const rect = element => { const r = element.getBoundingClientRect(); return { left: r.left, top: r.top, right: r.right, bottom: r.bottom, width: r.width, height: r.height }; };
    return { viewport: innerWidth, documentWidth: document.documentElement.scrollWidth, canvas: rect(canvas), nodes: Array.from(canvas.querySelectorAll('.x6-node')).map(node => ({ id: node.getAttribute('data-cell-id'), ...rect(node) })) };
  });
  assert.equal(evidence.flow390.documentWidth <= evidence.flow390.viewport, true);
  assert(evidence.flow390.canvas.width >= 150, 'The FLOW canvas must retain usable width rather than hide overflow');
  assert.equal(evidence.flow390.nodes.length, 2);
  for (const node of evidence.flow390.nodes) {
    assert(node.width >= 30 && node.height >= 10, 'Both actual X6 nodes must retain visible dimensions');
    assert(node.left >= evidence.flow390.canvas.left - 1 && node.right <= evidence.flow390.canvas.right + 1);
    assert(node.top >= evidence.flow390.canvas.top - 1 && node.bottom <= evidence.flow390.canvas.bottom + 1);
  }
  await screenshot('flow-390');
  await page.locator('.canvas').screenshot({ path: path.join(root, 'docs/evidence/frontend-test-inputs-live-flow-canvas-390.png') });
  evidence.checks.realFlowCanvasAndBothNodesVisibleAt390 = true;
  await page.setViewportSize({ width: 1440, height: 1000 });

  await page.getByRole('button', { name: '编辑保存项', exact: true }).click();
  const revisedName = name + '-已复核';
  await dialog.getByRole('textbox', { name: '测试输入名称', exact: true }).fill(revisedName);
  assert.equal(await dialog.getByRole('textbox', { name: 'question', exact: true }).inputValue(), fixture.inputs.question);
  const updatedResponse = responseFor(namedPath + '/' + saved.id, 'PUT');
  await dialog.getByRole('button', { name: '保存测试输入', exact: true }).click();
  const revised = await data(await updatedResponse);
  assert.equal(revised.revision, 2);
  assert.deepEqual(revised.inputs, fixture.inputs);
  await dialog.waitFor({ state: 'hidden' });
  await chooseNamed(revisedName, 2);
  evidence.checks.realRevisionTwoEdit = true;

  await question.fill('当前未保存输入不得替代保存版本-' + suffix);
  await page.getByRole('button', { name: '提升为评测用例', exact: true }).click();
  const expected = dialog.getByRole('textbox', { name: '预期完整输出 JSON', exact: true });
  assert.equal(await expected.inputValue(), '', 'Actual debug output must never populate the expected field');
  await expected.fill(JSON.stringify(fixture.expected));
  await dialog.locator('.el-select').click();
  await page.getByRole('option', { name: '安全用例（失败阻断发布）', exact: true }).click();
  await page.locator('.el-select-dropdown:visible').waitFor({ state: 'hidden' }).catch(() => {});
  await screenshot('promotion');
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(250);
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
  await screenshot('promotion-390');
  await page.setViewportSize({ width: 1440, height: 1000 });
  const promotedResponse = responseFor(namedPath + '/' + saved.id + '/promote', 'POST');
  await dialog.getByRole('button', { name: '创建评测版本', exact: true }).click();
  const promoted = await data(await promotedResponse);
  assert.equal(promoted.source.inputId, saved.id);
  assert.equal(promoted.source.revision, 2);
  assert.equal(promoted.source.appDraftId, fixture.draftId);
  assert.deepEqual(promoted.cases[0].inputs, fixture.inputs);
  assert.deepEqual(promoted.cases[0].expected, fixture.expected);
  assert.equal(promoted.cases[0].safety, true);
  evidence.datasetVersion = promoted.datasetVersion;
  evidence.source = promoted.source;
  evidence.checks.savedRevisionPromotedWithIndependentExpected = true;
  await dialog.waitFor({ state: 'hidden' });

  await page.locator('.tabs').getByRole('link', { name: '发布集成', exact: true }).click();
  const precheckResponse = responseFor('/admin/apps/' + fixture.appCode + '/publish/precheck', 'POST');
  await page.getByRole('button', { name: '运行发布预检', exact: true }).click();
  const precheck = await data(await precheckResponse);
  assert.equal(precheck.candidateSha256, fixture.candidateSha256, 'Naming and editing inputs must not alter the candidate');
  assert.equal(precheck.draftRev, fixture.draftRev);
  await page.getByRole('combobox', { name: '已保存的数据集版本', exact: true }).locator('xpath=ancestor::div[contains(@class,"el-select")][last()]').click();
  const datasetResponse = responseFor('/admin/evaluation-datasets/' + fixture.appCode + '/' + promoted.datasetVersion);
  await page.getByRole('option', { name: promoted.datasetVersion, exact: true }).click();
  const dataset = await data(await datasetResponse);
  assert.deepEqual(dataset.cases, promoted.cases);
  await page.getByText('已载入 1 条不可变用例，执行时使用此版本保存的输入与预期值。', { exact: true }).waitFor();
  evidence.checks.realImmutableDatasetSelected = true;
  await screenshot('dataset-selected');
  const evaluationResponse = responseFor('/admin/evaluations', 'POST');
  await page.getByRole('button', { name: '执行候选评测', exact: true }).click();
  const evaluated = await data(await evaluationResponse);
  evidence.evalReportId = evaluated.evalReportId;
  evidence.evaluationStatus = evaluated.status;
  evidence.evaluationResults = evaluated.results;
  assert.equal(evaluated.status, 'ACCEPTED', JSON.stringify(evaluated));
  assert.equal(evaluated.accepted, true);
  assert.equal(evaluated.datasetVersion, promoted.datasetVersion);
  assert.equal(evaluated.candidateSha256, fixture.candidateSha256);
  evidence.checks.realOriginalEvaluationAccepted = true;
  await page.getByRole('heading', { name: '评测报告', exact: true }).waitFor();
  await screenshot('accepted');
  await page.setViewportSize({ width: 390, height: 844 });
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
  await screenshot('accepted-390');
  await Promise.all(pending);
  const promotionRequest = evidence.requests.find(item => item.path.endsWith('/promote') && item.method === 'POST');
  assert.equal(promotionRequest.body.revision, 2);
  assert.equal('inputs' in promotionRequest.body, false);
  assert.equal('output' in promotionRequest.body, false);
  const evaluationRequest = evidence.requests.find(item => item.path === '/admin/evaluations' && item.method === 'POST');
  assert.deepEqual(evaluationRequest.body.cases, promoted.cases);
  assert.equal(evaluationRequest.body.datasetVersion, promoted.datasetVersion);
  assert.deepEqual(evidence.pageErrors, []);
  evidence.checks.originalApiReceivedExactSavedCases = true;
  evidence.completed = true;
} catch (error) {
  evidence.completed = false;
  evidence.failure = error.stack || error.message;
  await screenshot('failure').catch(() => {});
  throw error;
} finally {
  await Promise.allSettled(pending);
  evidence.endedAt = new Date().toISOString();
  await fs.writeFile(path.join(root, 'docs/evidence/frontend-test-inputs-live.json'), JSON.stringify(evidence, null, 2));
  process.stdout.write(JSON.stringify({ realApi: true, completed: evidence.completed, checks: evidence.checks, inputId: evidence.inputId, runId: evidence.runId, datasetVersion: evidence.datasetVersion, evalReportId: evidence.evalReportId, evaluationStatus: evidence.evaluationStatus, pageErrors: evidence.pageErrors }) + '\n');
  await browser.close();
}
