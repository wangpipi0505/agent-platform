/**
 * M5-2 真实链路 API 级证据：页面消费的同一批端点（经网关 18200）逐端点抓真实响应。
 * - 票据：网关 /auth/dev-token 真实出票（SPA 登录面，tenant local-v12）+ 本地铸造 demo 租户
 *   票据（与 ServiceTokens 同构 HS256，服务端同密钥真实验签）——V901 种子 tenant=demo，
 *   需要应用在位的数据面（记忆写入/规则/manifest/计划）用 demo 面，两口径都留痕。
 * - 密钥只从 .env 读，不进日志。任务详情/事件端点仅接受内部数字 id（平台契约缺口：
 *   受理/列表返回 public_id，浏览器面无桥——诚实登记），本脚本照 wt-m53 同款以 psql 桥
 *   解析数字 id 证明端点本身真实工作。
 * 输出：api-evidence.log。
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';

const REPO_ROOT = path.resolve(import.meta.dirname, '..', '..', '..');
const GATEWAY = 'http://127.0.0.1:18200';
const APP = 'satellite-planning-assistant';
const PSQL = 'G:/207/项目/算法重组工具/代码/_worktrees/upgrade-20261103/agent-platform/.tools/postgresql-16.15/pgsql/bin/psql.exe';

const loadEnv = async () => Object.fromEntries(
  (await fs.readFile(path.join(REPO_ROOT, '.env'), 'utf8'))
    .split(/\r?\n/).filter((x) => x && !x.startsWith('#'))
    .map((x) => { const i = x.indexOf('='); return [x.slice(0, i), x.slice(i + 1)]; }));

const b64 = (v) => Buffer.from(JSON.stringify(v)).toString('base64url');
function mint(env, tenant, roles, sub) {
  const now = Math.floor(Date.now() / 1000);
  const claims = { iss: 'agent-platform-v12', aud: 'agent-platform', token_use: 'user',
    sub, tenant_id: tenant, roles, attributes: {}, iat: now, exp: now + 900, jti: crypto.randomUUID() };
  const signing = b64({ alg: 'HS256', typ: 'JWT' }) + '.' + b64(claims);
  return signing + '.' + crypto.createHmac('sha256', env.AGENT_JWT_SECRET).update(signing).digest('base64url');
}

function taskIdByPublicId(env, publicId) {
  const out = execFileSync(PSQL, ['-h', '127.0.0.1', '-p', '25432', '-U', env.AGENT_DB_USER,
    '-d', 'm52_demo', '-tAc', `select id from engine.task where public_id='${publicId}'`],
    { env: { ...process.env, PGPASSWORD: env.AGENT_DB_PASSWORD }, timeout: 20000 });
  return String(out).trim();
}

// 网关 PATCH 代理缺陷（wt-m53 缺陷登记③ ProtocolException）：PATCH/带体 DELETE 经服务端口
// 直连（与页面 dev 代理 AGENT_DIRECT_SERVICE_PROXY=1 同口径）；其余走网关。
const callDirect = (base) => async (label, method, url, token, body) => {
  try {
    const response = await fetch(base + url, {
      method,
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const envelope = await response.json().catch(() => ({}));
    log(`[${response.status}] (direct ${base.replace("http://127.0.0.1:", "")}) ${method} ${url} — ${label}`);
    log(`      ${summarize(envelope.data ?? envelope)}`);
    return { status: response.status, data: envelope.data };
  } catch (error) {
    log(`[ERR] ${method} ${url} — ${label}: ${error.message}`);
    return { status: 0, data: null };
  }
};
const ENGINE = "http://127.0.0.1:18202";
const callEngine = callDirect(ENGINE);
const lines = [];
function log(line) { lines.push(line); console.log(line); }
const summarize = (value) => {
  const text = JSON.stringify(value);
  return text.length > 300 ? text.slice(0, 300) + `…(${text.length}B)` : text;
};

async function call(label, method, url, token, body) {
  try {
    const response = await fetch(GATEWAY + url, {
      method,
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const envelope = await response.json().catch(() => ({}));
    log(`[${response.status}] ${method} ${url} — ${label}`);
    log(`      ${summarize(envelope.data ?? envelope)}`);
    return { status: response.status, data: envelope.data };
  } catch (error) {
    log(`[ERR] ${method} ${url} — ${label}: ${error.message}`);
    return { status: 0, data: null };
  }
}

const env = await loadEnv();
const demoAdmin = mint(env, 'demo', ['ADMIN', 'APPROVER', 'EDITOR'], 'm52-demo-admin');
const spaUser = mint(env, 'local-v12', ['ADMIN', 'APPROVER', 'EDITOR'], 'developer-v12');

log(`# M5-2 real-link API evidence ${new Date().toISOString()}`);
log(`# gateway=${GATEWAY} demoApp=${APP} db=m52_demo`);

// 0) 登录双口径：网关真实 /auth/dev-token（SPA 登录面）
const devTokenResponse = await fetch(GATEWAY + '/auth/dev-token', { method: 'POST' });
const devToken = await devTokenResponse.json();
log(`[${devTokenResponse.status}] POST /auth/dev-token — SPA 登录出票（gateway 真实签发）`);
log(`      roles=${JSON.stringify(devToken?.data?.user?.roles)} tenant=${devToken?.data?.user?.tenantId} tokenLen=${String(devToken?.data?.accessToken ?? '').length}`);

// 1) 任务工作台（TasksPage/AssistantChat/TaskCard）：受理/列表（demo 租户=种子面）
const created = await call('AssistantChat submitTask 受理', 'POST', `/v1/apps/${APP}/tasks`, demoAdmin,
  { requestId: crypto.randomUUID(), mode: 'AGENT', goal: 'M5-2 演示：对同一数据集运行两种识别算法并生成差异报告', inputs: { message: 'M5-2 演示任务' } });
const publicId = created?.data?.taskId;
await call('TasksPage listTasks', 'GET', '/v1/tasks?page=1&pageSize=50', demoAdmin);
const numericId = publicId ? taskIdByPublicId(env, publicId) : '';
if (numericId) {
  await call('TaskCard taskDetail（数字 id 路径；public_id 400=平台契约缺口，诚实登记）', 'GET', `/v1/tasks/${numericId}`, demoAdmin);
  await call('TaskCard taskEvents 游标增量', 'GET', `/v1/tasks/${numericId}/events?afterSeq=0&limit=200`, demoAdmin);
}

// 2) 确认中心（AppShell/ApprovalsPage）
await call('AppShell 角标 counts（demo 租户）', 'GET', '/admin/confirmation-center/counts', demoAdmin);
await call('ApprovalsPage listPlanConfirmationTodos（confirmableBy=me）', 'GET', '/v1/tasks?confirmableBy=me', demoAdmin);
await call('ApprovalsPage DRAFT 记忆待办（status=DRAFT）', 'GET', `/v1/apps/${APP}/memories?status=DRAFT&pageSize=100`, demoAdmin);
await call('V901 种子记忆可见性（demo 租户 ACTIVE 列表）', 'GET', `/v1/apps/${APP}/memories?status=ACTIVE&pageSize=100`, demoAdmin);

// 3) 我的记忆（MyMemories/MemoriesPage）：真实写入面（POST→GET→PATCH CAS→DELETE，demo 租户）
const saved = await call('MyMemories createMemory（真实写入）', 'POST', `/v1/apps/${APP}/memories`, demoAdmin,
  { requestId: crypto.randomUUID(), content: 'M5-2 演示：输出偏好简明观测窗口表（页面写入）', kind: 'PREFERENCE', scope: 'PRIVATE', tags: ['M5-2', '演示'] });
const savedId = saved?.data?.memoryId;
if (savedId) {
  const fresh = await call('MemoriesPage getMemory 单条正文', 'GET', `/v1/apps/${APP}/memories/${savedId}`, demoAdmin);
  await callEngine('MemoriesPage updateMemory 版本 CAS（预期 409=冲突同码；PATCH 直连引擎=网关缺陷③分流）', 'PATCH', `/v1/apps/${APP}/memories/${savedId}`, demoAdmin,
    { content: '并发修改（CAS 冲突演示）', expectedVersion: Number(fresh?.data?.version ?? 1) + 5 });
  await callEngine('MemoriesPage updateMemory 正确版本', 'PATCH', `/v1/apps/${APP}/memories/${savedId}`, demoAdmin,
    { content: 'M5-2 演示（已编辑）', expectedVersion: Number(fresh?.data?.version ?? 1) });
  await callEngine('MyMemories deleteMemory 先置 REVOKED（requestId 必填）', 'DELETE', `/v1/apps/${APP}/memories/${savedId}`, demoAdmin, { requestId: crypto.randomUUID() });
}

// 4) 会话列表（SessionList/SessionDetailPage）
await call('SessionList listMySessions（mine=true）', 'GET', `/v1/apps/${APP}/sessions?mine=true`, demoAdmin);

// 5) 资产四页：规则/技能/插件
await call('RulesPage appCodes 应用目录（demo 租户=V901 种子应用）', 'GET', '/admin/apps', demoAdmin);
await call('RulesPage listRules（应用规则目录）', 'GET', `/admin/apps/${APP}/rules`, demoAdmin);
await call('RulesPage createRule 真实登记', 'POST', `/admin/apps/${APP}/rules`, demoAdmin,
  { requestId: crypto.randomUUID(), code: 'm52-demo-guard', name: 'M5-2 演示护栏', nature: 'GUIDANCE', scope: { app: APP },
    content: { trigger: 'M5-2 演示输出', action: '附演示标记', requirement: '输出含 M5-2 标记' } });
await call('SkillsPage skills 列表（V901 种子技能）', 'GET', '/admin/skills', demoAdmin);
await call('PluginsPage listPluginSources', 'GET', '/admin/plugin-sources', demoAdmin);
await call('PluginsPage listPlugins', 'GET', '/admin/plugins', demoAdmin);

// 6) MCP 连接（McpConnectionsPage）：登记→单条读→连通测试（服务端无列表端点，索引式聚合）
const mcpCreated = await call('McpConnectionsPage createMcpConnection 真实登记', 'POST', '/admin/mcp-connections', demoAdmin,
  { code: 'm52-demo-conn', name: 'M5-2 演示连接', baseEndpoint: 'http://127.0.0.1:18999/mcp', transport: 'STREAMABLE_HTTP', timeoutMs: 30000 });
const mcpId = mcpCreated?.data?.id ?? mcpCreated?.data?.code;
if (mcpId) {
  await call('McpConnectionsPage getMcpConnection', 'GET', `/admin/mcp-connections/${mcpId}`, demoAdmin);
  await call('McpConnectionsPage testMcpConnection（真实连通测试）', 'POST', `/admin/mcp-connections/${mcpId}/test`, demoAdmin, {});
}

// 7) 集成控制台（IntegrationConsolePage）与计划详情（PlanDetailPage 修订读）
await call('IntegrationConsolePage applications', 'GET', '/admin/apps', demoAdmin);
await call('IntegrationConsolePage configReleases', 'GET', `/admin/config-releases?appCode=${APP}`, demoAdmin);
await call('ManifestPanel fetchManifest（V901 种子 manifest）', 'GET', `/admin/apps/${APP}/manifest`, demoAdmin);
const confirmable = await call('计划确认投影（PlanCard/确认中心桥）', 'GET', '/v1/tasks?confirmableBy=me', demoAdmin);
const todo = Array.isArray(confirmable?.data?.items) ? confirmable.data.items[0] : null;
if (todo) {
  await call('PlanDetailPage planRevision 修订读', 'GET',
    `/v1/tasks/${todo.taskId}/plans/${todo.planId}/revisions/${todo.revision}`, demoAdmin);
}

await fs.writeFile(path.join(import.meta.dirname, 'api-evidence.log'), lines.join('\n') + '\n', 'utf8');
console.log(`\n已写入 ${path.join(import.meta.dirname, 'api-evidence.log')}（${lines.length} 行）`);
