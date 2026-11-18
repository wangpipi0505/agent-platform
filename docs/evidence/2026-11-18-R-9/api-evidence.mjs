/**
 * R-9②③④⑤ 网关与契约缺陷收口——API 级真实请求证据（全部经网关 18680 真实路径，零直连）。
 *
 * 逐项覆盖：
 * - 缺陷② 网关路由缺口：POST /v1/tasks/{id}/plans、GET plans/{planId}/revisions/{r}、
 *   POST .../confirm、POST /v1/tasks/{id}/execute 经网关真实可达（修复前 404 NOT_FOUND
 *   "此任务入口不存在"）；未登记子路径（/v1/tasks/{id}/plans/{planId} 裸形、
 *   /v1/tasks/{id}/plansx）仍被网关拒绝 404——收口不放水。
 * - 缺陷③ 网关代理 PATCH 全 500：创建记忆后 PATCH（错误 expectedVersion→409
 *   MEMORY_VERSION_CONFLICT 证明请求体真实到达引擎并被业务处理；正确版本→200）。
 * - 缺陷④ 网关代理 DELETE 丢体：DELETE /v1/apps/{code}/memories/{id} 携带请求体 →
 *   200 且 state=REVOKED（幂等重复删除返回原终态）。
 * - 缺陷⑤ ID 契约：任务受理返回业务号（task-*），随后 detail/events/result/全文同形状
 *   回传经网关 200（修复前 400 MethodArgumentTypeMismatch，浏览器面无桥）。
 * - 票据：网关 /auth/dev-token 真实出票（tenant local-v12）；demo 租户票据按 M5-2 同款
 *   本地铸造（与 ServiceTokens 同构 HS256，服务端同密钥真实验签）——V901 种子 tenant=demo。
 * 输出：api-evidence.log。
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';

const REPO_ROOT = path.resolve(import.meta.dirname, '..', '..', '..');
const GATEWAY = 'http://127.0.0.1:18680';
const APP = 'satellite-planning-assistant';

const lines = [];
const log = (s) => { lines.push(s); console.log(s); };
const summarize = (v) => JSON.stringify(v)?.slice(0, 220);

const loadEnv = async () => Object.fromEntries(
  (await fs.readFile(path.join(REPO_ROOT, '.env'), 'utf8'))
    .split(/\r?\n/).filter((x) => x && !x.startsWith('#'))
    .map((x) => { const i = x.indexOf('='); return [x.slice(0, i), x.slice(i + 1)]; }));

const b64 = (v) => Buffer.from(JSON.stringify(v)).toString('base64url');
/** M5-2 同款：与 ServiceTokens 同构 HS256 用户票（服务端同密钥真实验签；仅本机验证库用）。 */
function mintDemoTicket(env, sub = 'demo-user') {
  const now = Math.floor(Date.now() / 1000);
  const claims = { iss: 'agent-platform-v12', aud: 'agent-platform', token_use: 'user',
    sub, tenant_id: 'demo', roles: ['ADMIN', 'EDITOR', 'APPROVER'], attributes: {},
    iat: now, exp: now + 900, jti: crypto.randomUUID() };
  const signing = b64({ alg: 'HS256', typ: 'JWT' }) + '.' + b64(claims);
  return signing + '.' + crypto.createHmac('sha256', env.AGENT_JWT_SECRET).update(signing).digest('base64url');
}

async function call(label, method, url, token, body) {
  try {
    const response = await fetch(GATEWAY + url, {
      method,
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
        ...(body === undefined ? {} : {}),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const raw = await response.text();
    let envelope;
    try { envelope = JSON.parse(raw); } catch { envelope = { raw: raw.slice(0, 120) }; }
    const code = envelope?.code ?? '-';
    log(`[${response.status}] ${method} ${url} — ${label} (code=${code})`);
    if (envelope?.data !== undefined && envelope.data !== null) log(`      data=${summarize(envelope.data)}`);
    else if (envelope?.message) log(`      message=${String(envelope.message).slice(0, 160)}`);
    return { status: response.status, data: envelope?.data, code };
  } catch (error) {
    log(`[ERR] ${method} ${url} — ${label}: ${error.message}`);
    return { status: 0, data: null, code: 'ERR' };
  }
}

const env = await loadEnv();
log(`# R-9②③④⑤ 网关真实请求证据 ${new Date().toISOString()}  gateway=${GATEWAY}  库=r9_fix`);
log('');

// 0) 双口径登录：网关真实 /auth/dev-token（local-v12）+ demo 租户本地铸造票（V901 种子面）。
const devResponse = await fetch(GATEWAY + '/auth/dev-token', { method: 'POST' });
const devEnvelope = await devResponse.json();
const devToken = devEnvelope?.data?.accessToken;
log(`[${devResponse.status}] POST /auth/dev-token — 网关真实出票 tenant=${devEnvelope?.data?.user?.tenantId}`);
const demoToken = mintDemoTicket(env);
log(`[mint] demo 租户票据（HS256 同构，服务端同密钥真实验签）len=${demoToken.length}`);
log('');

// ============ 缺陷③④：记忆 PATCH（带体）/ DELETE（带体）经网关 ============
// 记忆面用 demo 租户（V901 种子应用/工作区在 demo 租户；local-v12 无该应用——M5-2 同口径）。
log('== 缺陷③④：记忆 PATCH/DELETE 经网关（此前 PATCH 全 500、DELETE 体被丢） ==');
const created = await call('记忆显式保存（真实建行，demo 面）', 'POST', `/v1/apps/${APP}/memories`, demoToken, {
  requestId: 'r9-mem-' + crypto.randomUUID(),
  scope: 'PRIVATE', kind: 'PREFERENCE', content: 'R-9 验证记忆：PATCH/DELETE 网关透传。', tags: ['R-9'],
});
const memoryId = created?.data?.memoryId ?? created?.data?.id ?? created?.data?.memory_id;
log(`      memoryId=${memoryId}`);
const badVersion = await call('PATCH 错误 expectedVersion（体到达引擎→业务 409）', 'PATCH',
  `/v1/apps/${APP}/memories/${encodeURIComponent(memoryId)}`, demoToken,
  { content: '错版本写入不应成功', expectedVersion: 999 });
if (!(badVersion.status === 409 && badVersion.code === 'MEMORY_VERSION_CONFLICT')) {
  log(`      [断言失败] PATCH 期望 409 MEMORY_VERSION_CONFLICT，实得 ${badVersion.status} ${badVersion.code}`);
}
const version = Number(created?.data?.version ?? 1);
const patched = await call('PATCH 正确 expectedVersion', 'PATCH',
  `/v1/apps/${APP}/memories/${encodeURIComponent(memoryId)}`, demoToken,
  { content: 'R-9 验证记忆：PATCH 经网关透传成功。', expectedVersion: version });
if (!(patched.status === 200)) log(`      [断言失败] PATCH 期望 200，实得 ${patched.status}`);
const deleted = await call('DELETE 携带体（体如实透传→REVOKED）', 'DELETE',
  `/v1/apps/${APP}/memories/${encodeURIComponent(memoryId)}`, demoToken,
  { requestId: 'r9-del-' + crypto.randomUUID(), reason: 'R-9 evidence' });
const state = deleted?.data?.state ?? deleted?.data?.status;
if (!(deleted.status === 200 && String(state).toUpperCase() === 'REVOKED'))
  log(`      [断言失败] DELETE 期望 200+REVOKED，实得 ${deleted.status} state=${state}`);
const deletedAgain = await call('DELETE 幂等重放（重复删除返回原终态）', 'DELETE',
  `/v1/apps/${APP}/memories/${encodeURIComponent(memoryId)}`, demoToken,
  { requestId: 'r9-del2-' + crypto.randomUUID(), reason: 'R-9 evidence' });
log(`      幂等重放 status=${deletedAgain.status} state=${deletedAgain?.data?.state ?? deletedAgain?.data?.status}`);
log('');

// ============ 缺陷⑤+②：任务受理→业务号回传全链（detail/events/result + plans/confirm/execute） ============
log('== 缺陷⑤：{taskId}=业务号（task-*）端到端 + 缺陷②：计划面端点经网关接线 ==');
const accepted = await call('任务受理（demo 租户，AGENT 模式）', 'POST', `/v1/apps/${APP}/tasks`, demoToken, {
  requestId: 'r9-task-' + crypto.randomUUID(),
  mode: 'AGENT',
  goal: 'R-9②⑤ 验证任务：业务号端到端与计划面网关接线。',
  inputs: {},
});
const taskId = accepted?.data?.taskId;
log(`      受理返回 taskId=${taskId}（契约形状=业务号）`);
if (!/^task-/.test(String(taskId))) log('      [断言失败] 受理未返回 task-* 业务号');

const detail = await call('GET /v1/tasks/{业务号} 详情（缺陷⑤修复前 400）', 'GET',
  `/v1/tasks/${encodeURIComponent(taskId)}`, demoToken);
if (!(detail.status === 200 && detail?.data?.taskId === taskId))
  log(`      [断言失败] 详情期望 200 且 taskId 回显业务号`);
const events = await call('GET /v1/tasks/{业务号}/events（缺陷⑤修复前 400）', 'GET',
  `/v1/tasks/${encodeURIComponent(taskId)}/events?afterSeq=0`, demoToken);
if (!(events.status === 200)) log(`      [断言失败] 事件期望 200，实得 ${events.status}`);
const result = await call('GET /v1/tasks/{业务号}/result', 'GET',
  `/v1/tasks/${encodeURIComponent(taskId)}/result`, demoToken);
if (!(result.status === 200)) log(`      [断言失败] 结果期望 200，实得 ${result.status}`);

// 计划面：appendRevision（baseRevision=0 真建修订）→ confirm → execute（全经网关）。
const planId = 'plan-r9-' + crypto.randomUUID().replace(/-/g, '').slice(0, 12);
const appended = await call('POST /v1/tasks/{id}/plans（缺陷②修复前网关 404）', 'POST',
  `/v1/tasks/${encodeURIComponent(taskId)}/plans`, demoToken,
  { planId, baseRevision: 0 });
const digest = appended?.data?.digest;
const revision = Number(appended?.data?.revision ?? 0);
if (!(appended.status === 200 && digest)) {
  log(`      [断言失败] 修订追加期望 200+digest，实得 ${appended.status} ${appended.code}`);
} else {
  log(`      修订 revision=${revision} digest=${String(digest).slice(0, 16)}… validationStatus=${appended?.data?.validationStatus}`);
}
const revisionRead = await call('GET plans/{planId}/revisions/{r}（缺陷②接线）', 'GET',
  `/v1/tasks/${encodeURIComponent(taskId)}/plans/${planId}/revisions/${revision}`, demoToken);
log(`      修订读 status=${revisionRead.status} validationStatus=${revisionRead?.data?.validationStatus}`);
const confirmed = await call('POST .../revisions/{r}/confirm（缺陷②接线）', 'POST',
  `/v1/tasks/${encodeURIComponent(taskId)}/plans/${planId}/revisions/${revision}/confirm`, demoToken,
  { requestId: 'r9-confirm-' + crypto.randomUUID(), digest });
log(`      confirm status=${confirmed.status} code=${confirmed.code} confirmSeq=${confirmed?.data?.confirmSeq}`);
const executed = await call('POST /v1/tasks/{id}/execute（缺陷②接线）', 'POST',
  `/v1/tasks/${encodeURIComponent(taskId)}/execute`, demoToken,
  { requestId: 'r9-execute-' + crypto.randomUUID(), planId, revision, digest });
log(`      execute status=${executed.status} code=${executed.code} executionTaskId=${executed?.data?.executionTaskId}`);
if (executed.status !== 200) log(`      [如实登记] execute 未 200：${executed.code} ${executed?.data?.message ?? ''}`);

// 未登记子路径仍拒绝（收口不放水）。
log('');
log('== 缺陷②：未登记子路径仍拒绝（网关 404，不入上游） ==');
for (const [label, path] of [
  ['裸 planId 形未登记', `/v1/tasks/${encodeURIComponent(taskId)}/plans/${planId}`],
  ['plansx 拼写相近', `/v1/tasks/${encodeURIComponent(taskId)}/plansx`],
  ['executex 拼写相近', `/v1/tasks/${encodeURIComponent(taskId)}/executex`],
  ['confirm 深层扩展', `/v1/tasks/${encodeURIComponent(taskId)}/plans/${planId}/revisions/${revision}/confirm/extra`],
]) {
  const r = await call(label, 'GET', path, demoToken);
  if (r.status !== 404) log(`      [断言失败] 期望 404，实得 ${r.status}`);
}

await fs.writeFile(path.join(REPO_ROOT, 'docs/evidence/2026-11-18-R-9/api-evidence.log'), lines.join('\n') + '\n');
log('');
log('证据已写入 docs/evidence/2026-11-18-R-9/api-evidence.log');
