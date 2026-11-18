/**
 * M5-2 真实链路浏览器演示：playwright 驱动管理台 SPA（vite dev 15173，分流代理到 182xx 段真实服务）。
 * 登录面：拦截 /auth/dev-token 响应，替换为本地铸造的 demo 租户票据（V901 种子租户；服务端同密钥
 * 真实验签）——SPA 登录流程与其余链路全部真实。截图存本目录 screenshots/。
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { createRequire } from 'node:module';

const webRequire = createRequire(path.join(import.meta.dirname, '..', '..', '..', 'web', 'package.json'));
const { chromium } = webRequire('playwright');

const REPO_ROOT = path.resolve(import.meta.dirname, '..', '..', '..');
const BASE = 'http://127.0.0.1:15173';
const OUT = path.join(import.meta.dirname, 'screenshots');

const env = Object.fromEntries(
  (await fs.readFile(path.join(REPO_ROOT, '.env'), 'utf8'))
    .split(/\r?\n/).filter((x) => x && !x.startsWith('#'))
    .map((x) => { const i = x.indexOf('='); return [x.slice(0, i), x.slice(i + 1)]; }));

const b64 = (v) => Buffer.from(JSON.stringify(v)).toString('base64url');
function mint(tenant, roles, sub) {
  const now = Math.floor(Date.now() / 1000);
  const claims = { iss: 'agent-platform-v12', aud: 'agent-platform', token_use: 'user',
    sub, tenant_id: tenant, roles, attributes: {}, iat: now, exp: now + 3600, jti: crypto.randomUUID() };
  const signing = b64({ alg: 'HS256', typ: 'JWT' }) + '.' + b64(claims);
  return signing + '.' + crypto.createHmac('sha256', env.AGENT_JWT_SECRET).update(signing).digest('base64url');
}
const demoToken = mint('demo', ['ADMIN', 'APPROVER', 'EDITOR'], 'm52-demo-admin');

await fs.mkdir(OUT, { recursive: true });
const failures = [];
const exe = path.join(process.env.LOCALAPPDATA ?? '', 'ms-playwright', 'chromium-1228', 'chrome-win64', 'chrome.exe');
const browser = await chromium.launch(await fs.access(exe).then(() => ({ executablePath: exe })).catch(() => ({})));
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
page.on('pageerror', (error) => failures.push(`pageerror: ${error.message}`));

// 登录票据替换（demo 租户；SPA 登录流程本身照常执行）
await page.route('**/auth/dev-token', async (route) => {
  await route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      code: 'OK', message: '',
      data: {
        accessToken: demoToken, tokenType: 'Bearer', expiresIn: 3600,
        user: { tenantId: 'demo', principalId: 'm52-demo-admin', name: 'M5-2 演示管理员', roles: ['ADMIN', 'APPROVER', 'EDITOR'] },
      },
    }),
  });
});

async function shot(name) {
  await page.waitForTimeout(1200);
  await page.screenshot({ path: path.join(OUT, name), fullPage: false });
  console.log(`截图 ${name}`);
}

await page.goto(BASE + '/', { waitUntil: 'networkidle' }).catch(() => {});
await page.getByTestId('portal-reconnect').click().catch(async () => {
  await page.locator('text=进入开发工作台').first().click();
});
await page.waitForSelector('[data-testid="portal-entry"]', { state: 'detached', timeout: 15000 }).catch(() => {});
await shot('01-login-console.png');

// 任务工作台（真实任务列表——api-evidence 已受理的演示任务）
await page.goto(BASE + '/#/tasks', { waitUntil: 'networkidle' }).catch(() => {});
await shot('02-tasks-console.png');

// 资产四页
await page.goto(BASE + '/#/assets/memories', { waitUntil: 'networkidle' }).catch(() => {});
await shot('03-assets-memories.png');
await page.goto(BASE + '/#/assets/rules', { waitUntil: 'networkidle' }).catch(() => {});
await shot('04-assets-rules.png');
await page.goto(BASE + '/#/assets/plugins', { waitUntil: 'networkidle' }).catch(() => {});
await shot('05-assets-plugins.png');
await page.goto(BASE + '/#/assets/mcp', { waitUntil: 'networkidle' }).catch(() => {});
await shot('06-assets-mcp.png');

// 确认中心（四类矩阵 + AppShell 角标）
await page.goto(BASE + '/#/approvals', { waitUntil: 'networkidle' }).catch(() => {});
await shot('07-approvals.png');

// 集成控制台
await page.goto(BASE + '/#/integration/console', { waitUntil: 'networkidle' }).catch(() => {});
await shot('08-integration-console.png');

// 会话详情（参数页：空会话如实空态）与计划详情（无 taskId 上下文如实空态）
await page.goto(BASE + '/#/sessions/demo-session-none', { waitUntil: 'networkidle' }).catch(() => {});
await shot('09-session-detail-empty.png');
await page.goto(BASE + '/#/plans/plan-9', { waitUntil: 'networkidle' }).catch(() => {});
await shot('10-plan-detail-empty.png');

console.log(`failures(JSON console/page errors): ${failures.length}`);
for (const failure of failures) console.log('  ' + failure);
await browser.close();
console.log('DONE screenshots at ' + OUT);
