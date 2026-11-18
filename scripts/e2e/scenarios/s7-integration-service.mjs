/** S7 外部集成（SERVICE 票据面）：出票（ApiKey+Origin 精确匹配）→白名单四端点
 * （tasks create/detail/events/result）→白名单外 403 APP_BINDING_DENIED+审计行→跨应用
 * 绑定 403→SERVICE 面 AGENT-only（403 SERVICE_AGENT_ONLY）→伪造 policy_digest 旧票失效→
 * 凭据撤销后旧票 INTEGRATION_TICKET_EXPIRED（每次请求实时读权威状态）→直连 ApiKey 面
 * （X-User-* 派生身份/自报身份拒绝）。
 * 登记项（README 详述）：凭据签发/撤销端点绑定 IAM 权威链（AuthorityContext.requireFor），
 * dev-hmac 本地栈无权威复核入口（403 AUTHORITY_CONTEXT_MISSING）——凭据行与撤销经权威库
 * 数据面置备（与 IntegrationHostTicketTest 夹具同口径），业务语义全部走真实网关 HTTP。 */
import crypto from 'node:crypto';
import { Check, client, identities, runId, taskIdByPublicId, vaultPut, pgExec, pgOne } from '../lib/harness.mjs';

export const meta = { id: 'S7', name: '外部集成（出票/白名单/绑定/过期语义）' };

const sha256 = (s) => crypto.createHash('sha256').update(s, 'utf8').digest('hex');
const b64 = (v) => Buffer.from(JSON.stringify(v)).toString('base64url');

/** 伪造 policy_digest 的"旧票"：形状与 ServiceTokens.integrationToken 一致，摘要与凭据不符。 */
function forgedTicket(env, tenant, sub, appId, biz, credentialId) {
  const now = Math.floor(Date.now() / 1000);
  const claims = {
    iss: 'agent-platform-v12', aud: 'agent-platform-embed', token_use: 'integration',
    sub, tenant_id: tenant, roles: ['USER'],
    attributes: { bizSystemCode: biz, userId: sub },
    iat: now, exp: now + 120, jti: crypto.randomUUID(),
    app_code: appId, biz_system_code: biz, user_id: sub, credential_id: credentialId,
    policy_digest: 'f'.repeat(64),
  };
  const signing = b64({ alg: 'HS256', typ: 'JWT' }) + '.' + b64(claims);
  return signing + '.' + crypto.createHmac('sha256', env.AGENT_JWT_SECRET).update(signing).digest('base64url');
}

export async function run({ env, records }) {
  const check = new Check(meta.id, records);
  const ids = identities(env);
  const rid = runId();
  const admin = client(ids.ADMIN);
  const app = 'satellite-planning-assistant';
  const ORIGIN = 'http://127.0.0.1';
  const biz = 'e2ebiz' + rid.slice(0, 8);
  const GW = 'http://127.0.0.1:18480';

  // 登记项：签发端点绑定 IAM 权威链——dev-hmac 本地栈行为如下（如实断言，不冒充可签发）
  const issueAttempt = await admin.post(`/admin/apps/${app}/integration/credentials`,
    { bizSystemCode: biz, claimLimits: { principalIds: ['e2e-int-user'], roles: ['USER'], attributes: {} }, allowedOrigins: [ORIGIN] });
  check.eq(issueAttempt.status, 403, '凭据签发端点绑定 IAM 权威链（本地 dev-hmac 无复核入口）403');
  check.eq(issueAttempt.code, 'AUTHORITY_CONTEXT_MISSING', '登记语义 AUTHORITY_CONTEXT_MISSING（数据面置备替代，见 README）');

  // 数据面置备凭据（IntegrationHostTicketTest 同口径）：vault 文件 + credential 行
  const secret = crypto.randomBytes(32).toString('base64url');
  const ref = await vaultPut(env, 'demo', secret);
  pgExec(env,
    `insert into app.integration_credential(tenant_id,app_code,biz_system_code,secret_ref,secret_sha256,status,claim_limits,allowed_origins,issued_by)` +
    ` values('demo','${app}','${biz}','${ref}','${sha256(secret)}','ACTIVE','{"principalIds":["e2e-int-user"],"roles":["USER"],"attributes":{}}','["${ORIGIN}"]','e2e-dataplane')`);
  const credentialId = Number(pgOne(env, `select id from app.integration_credential where tenant_id='demo' and biz_system_code='${biz}'`));
  check.true(credentialId > 0, '数据面凭据置备成功（id=' + credentialId + '）');
  const apiKeyOnce = credentialId + ':' + secret;

  const noOrigin = await rawJson(`${GW}/embed/ticket`, { method: 'POST', headers: { Authorization: 'ApiKey ' + apiKeyOnce, 'Content-Type': 'application/json' }, body: JSON.stringify({ userId: 'e2e-int-user', roles: ['USER'] }) });
  check.eq(noOrigin.status, 200, '无 Origin（服务端后端出票）受理 200');
  const badOrigin = await rawJson(`${GW}/embed/ticket`, { method: 'POST', headers: { Authorization: 'ApiKey ' + apiKeyOnce, Origin: 'http://evil.example', 'Content-Type': 'application/json' }, body: JSON.stringify({ userId: 'e2e-int-user', roles: ['USER'] }) });
  check.eq(badOrigin.status, 403, '带未登记 Origin 出票 403');
  check.true(badOrigin.code === '(non-json)', '未登记 Origin 被 CORS 层拒（Invalid CORS request，非业务信封）', badOrigin.body.message);

  const badKey = await rawJson(`${GW}/embed/ticket`, { method: 'POST', headers: { Authorization: `ApiKey ${credentialId}:wrong-secret`, Origin: ORIGIN, 'Content-Type': 'application/json' }, body: JSON.stringify({ userId: 'e2e-int-user', roles: ['USER'] }) });
  check.eq(badKey.status, 401, '错误密钥出票 401');
  check.eq(badKey.body.code, 'INVALID_CREDENTIAL', '密钥核验语义 INVALID_CREDENTIAL');

  const ticketRes = await rawJson(`${GW}/embed/ticket`, { method: 'POST', headers: { Authorization: 'ApiKey ' + apiKeyOnce, Origin: ORIGIN, 'Content-Type': 'application/json' }, body: JSON.stringify({ userId: 'e2e-int-user', roles: ['USER'] }) });
  check.eq(ticketRes.status, 200, '出票 200（票据本体不入日志/证据）');
  check.eq(ticketRes.body.code, 'OK', '出票信封 OK');
  check.eq(ticketRes.body.data?.expiresIn, 300, '短票有效期 300s 上限');
  const ticket = ticketRes.body.data?.ticket;
  check.true(!!ticket, '返回短票');

  const svc = {
    async post(url, body) {
      const r = await fetch(GW + url, { method: 'POST', headers: { Authorization: 'Bearer ' + ticket, Origin: ORIGIN, 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) });
      const raw = await r.text(); const j = raw ? JSON.parse(raw) : {};
      return { status: r.status, code: j.code, data: j.data };
    },
    async get(url) {
      const r = await fetch(GW + url, { headers: { Authorization: 'Bearer ' + ticket, Origin: ORIGIN } });
      const raw = await r.text(); const j = raw ? JSON.parse(raw) : {};
      return { status: r.status, code: j.code, data: j.data };
    },
  };

  const ridTask = 's7_' + rid;
  const created = await svc.post(`/v1/apps/${app}/tasks`, { requestId: ridTask, mode: 'AGENT', goal: 'E2E S7 外部受理。', inputs: {} });
  check.eq(created.status, 200, '白名单①受理：SERVICE 票据 create task 200', created.message);
  const tid = taskIdByPublicId(env, created.data.taskId);
  const detail = await svc.get(`/v1/tasks/${tid}`);
  check.eq(detail.status, 200, '白名单②detail 200');
  const events = await svc.get(`/v1/tasks/${tid}/events`);
  check.eq(events.status, 200, '白名单③events 200');
  const result = await svc.get(`/v1/tasks/${tid}/result`);
  check.eq(result.status, 200, '白名单④result 200', result.code);

  const cancelDenied = await svc.post(`/v1/tasks/${tid}/cancel`, {});
  check.eq(cancelDenied.status, 403, '白名单外 cancel 403');
  check.eq(cancelDenied.code, 'APP_BINDING_DENIED', '白名单外语义 APP_BINDING_DENIED');
  const resumeDenied = await svc.post(`/v1/tasks/${tid}/resume`, {});
  check.eq(resumeDenied.code, 'APP_BINDING_DENIED', 'resume 同样不在白名单');
  const planDenied = await svc.post(`/v1/apps/${app}/tasks`, { requestId: ridTask + 'p', mode: 'PLAN', goal: 'x', inputs: {} });
  check.eq(planDenied.status, 403, 'SERVICE 面 mode=PLAN 403');
  check.eq(planDenied.code, 'SERVICE_AGENT_ONLY', 'AGENT-only 语义 SERVICE_AGENT_ONLY（首期仅受理 AGENT）');

  // 审计语义：白名单外路径落结构化审计行（凭据标识+主体+路径，绝无密钥本体）
  const fs = await import('node:fs/promises');
  const logPath = process.env.E2E_GATEWAY_LOG || 'G:/207/项目/算法重组工具/代码/_worktrees/upgrade-20261103/wt-m53/.local/m53-logs/gateway.out.log';
  let log = '';
  try { log = await fs.readFile(logPath, 'utf8'); } catch { log = ''; }
  check.true(log.includes('AUDIT INTEGRATION_PATH_FORBIDDEN'), '白名单外路径落审计行 AUDIT INTEGRATION_PATH_FORBIDDEN');
  check.true(!log.includes(secret), '审计与日志不落密钥本体');

  const otherApp = await svc.post('/v1/apps/user-governance-assistant/runs', { input: {} });
  check.eq(otherApp.status, 403, '跨应用入口 403');
  check.eq(otherApp.code, 'APP_BINDING_DENIED', '应用绑定语义 APP_BINDING_DENIED');

  const forged = forgedTicket(env, 'demo', 'e2e-int-user', app, biz, credentialId);
  const fr = await rawJson(`${GW}/v1/tasks/${tid}`, { headers: { Authorization: 'Bearer ' + forged, Origin: ORIGIN } });
  check.eq(fr.status, 401, '伪造 policy_digest 旧票 401');
  check.eq(fr.body.code, 'INTEGRATION_TICKET_EXPIRED', '旧票失效语义 INTEGRATION_TICKET_EXPIRED');

  // 撤销（数据面镜像服务 SQL：status=REVOKED + policy_revision+1）；旧票即刻失效
  pgExec(env, `update app.integration_credential set status='REVOKED',revoked_at=now(),policy_revision=policy_revision+1 where tenant_id='demo' and app_code='${app}' and biz_system_code='${biz}'`);
  const afterRevoke = await rawJson(`${GW}/v1/tasks/${tid}`, { headers: { Authorization: 'Bearer ' + ticket, Origin: ORIGIN } });
  check.eq(afterRevoke.status, 401, '撤销后带 Origin 请求 401（凭据复核先于 origin 校验：bound 前置）');
  check.eq(afterRevoke.code, 'INTEGRATION_TICKET_EXPIRED', '撤销语义 INTEGRATION_TICKET_EXPIRED');
  const afterRevokeNoOrigin = await rawJson(`${GW}/v1/tasks/${tid}`, { headers: { Authorization: 'Bearer ' + ticket } });
  check.eq(afterRevokeNoOrigin.status, 401, '撤销后旧票（无 Origin）同样 401——每次请求实时读权威状态');
  check.eq(afterRevokeNoOrigin.body.code, 'INTEGRATION_TICKET_EXPIRED', '撤销语义一致（与 Origin 无关）');

  const secret2 = crypto.randomBytes(32).toString('base64url');
  const ref2 = await vaultPut(env, 'demo', secret2);
  pgExec(env,
    `insert into app.integration_credential(tenant_id,app_code,biz_system_code,secret_ref,secret_sha256,status,claim_limits,allowed_origins,issued_by)` +
    ` values('demo','${app}','${biz}2','${ref2}','${sha256(secret2)}','ACTIVE','{"principalIds":["e2e-int-user"],"roles":["USER"],"attributes":{}}','["${ORIGIN}"]','e2e-dataplane')`);
  const apiKeyOnce2 = pgOne(env, `select id from app.integration_credential where tenant_id='demo' and biz_system_code='${biz}2'`) + ':' + secret2;

  // 直连 ApiKey 面：X-User-* 派生身份；自报 tenant/principal 一律拒绝
  const directHeaders = { Authorization: 'ApiKey ' + apiKeyOnce2, 'X-User-Id': 'e2e-int-user', Origin: ORIGIN };
  const direct = await rawJson(`${GW}/v1/apps/${app}/input-schema`, { headers: directHeaders });
  check.eq(direct.status, 200, '直连 ApiKey 面 200（白名单 input-schema，X-User-Id 派生身份）', direct.body.code);
  const selfTenant = await rawJson(`${GW}/v1/apps/${app}/input-schema`, { headers: { ...directHeaders, 'X-User-Tenant': 'other-tenant' } });
  check.eq(selfTenant.status, 403, '客户端自报 tenant 403');
  check.eq(selfTenant.body.code, 'INTEGRATION_CLIENT_IDENTITY_REJECTED', '自报身份拒绝语义 INTEGRATION_CLIENT_IDENTITY_REJECTED');

  return { credentialId, taskId: created.data.taskId, biz };
}

async function rawJson(url, options = {}) {
  const r = await fetch(url, { ...options, signal: AbortSignal.timeout(30000) });
  const text = await r.text();
  let body;
  try { body = JSON.parse(text); } catch { body = { code: '(non-json)', message: text.slice(0, 120) }; }
  return { status: r.status, body, code: body.code };
}

