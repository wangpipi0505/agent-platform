/** M5-3 E2E 公共夹具：本地出票、网关/引擎 HTTP 客户端、权威库只读辅助、断言与逐步留痕。
 *
 * 票据机制（与 tests/smoke-statistics.mjs 同一先例）：用 .env 的 AGENT_JWT_SECRET 在本进程内
 * 按 ServiceTokens 同构形状铸造 HS256 用户票据（iss=agent-platform-v12, aud=agent-platform,
 * token_use=user, sub/tenant_id/roles/attributes/iat/exp/jti），经网关真实 SecurityFilterChain
 * 验签后驱动业务链；密钥只从 .env 读取，不进命令行参数、日志或证据。
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';

const REPO_ROOT = path.resolve(import.meta.dirname, '..', '..', '..');
const PSQL = 'G:/207/项目/算法重组工具/代码/_worktrees/upgrade-20261103/agent-platform/.tools/postgresql-16.15/pgsql/bin/psql.exe';

export async function loadEnv() {
  return Object.fromEntries(
    (await fs.readFile(path.join(REPO_ROOT, '.env'), 'utf8'))
      .split(/\r?\n/)
      .filter((x) => x && !x.startsWith('#'))
      .map((x) => { const i = x.indexOf('='); return [x.slice(0, i), x.slice(i + 1)]; }));
}

export const GATEWAY = process.env.E2E_GATEWAY || 'http://127.0.0.1:18480';
export const ENGINE = process.env.E2E_ENGINE || 'http://127.0.0.1:18482';
export const MODEL_STUB_PORT = Number(process.env.E2E_MODEL_STUB_PORT || 18485);
export const MCP_STUB_PORT = Number(process.env.E2E_MCP_STUB_PORT || 18486);
export const DEMO_TENANT = 'demo';
export const DEMO_APP = 'satellite-planning-assistant';

const b64 = (v) => Buffer.from(JSON.stringify(v)).toString('base64url');

/** 本地铸造五身份票据（真实过网关验签；与仓库既有 smoke 出票先例同构）。 */
export function mint(env, tenant, roles, sub, extra = {}) {
  const now = Math.floor(Date.now() / 1000);
  const claims = {
    iss: 'agent-platform-v12', aud: 'agent-platform', token_use: 'user',
    sub, tenant_id: tenant, roles, attributes: {},
    iat: now, exp: now + (extra.ttl ?? 900), jti: crypto.randomUUID(),
  };
  const signing = b64({ alg: 'HS256', typ: 'JWT' }) + '.' + b64(claims);
  return signing + '.' + crypto.createHmac('sha256', env.AGENT_JWT_SECRET).update(signing).digest('base64url');
}

export function identities(env) {
  if (env.portalTokens) return env.portalTokens;
  return {
    ADMIN: mint(env, DEMO_TENANT, ['ADMIN'], 'e2e-admin'),
    BUILDER: mint(env, DEMO_TENANT, ['BUILDER'], 'e2e-builder'),
    OPERATOR: mint(env, DEMO_TENANT, ['OPERATOR'], 'e2e-operator'),
    USER: mint(env, DEMO_TENANT, ['USER'], 'demo-user'),
    SERVICE: mint(env, DEMO_TENANT, ['SERVICE'], 'e2e-service'),
  };
}

export class Check {
  constructor(scene, sink) { this.scene = scene; this.sink = sink; this.failed = 0; this.total = 0; }
  eq(actual, expected, label, detail) {
    this.total++;
    const ok = actual === expected;
    if (!ok) this.failed++;
    this.sink.push({ scene: this.scene, label, ok, expected: String(expected), actual: String(actual), detail });
    const mark = ok ? 'PASS' : 'FAIL';
    console.log(`  [${mark}] ${label}${ok ? '' : `（期望 ${expected}，实际 ${actual}${detail ? '；' + detail : ''}）`}`);
    return ok;
  }
  true(cond, label, detail) { return this.eq(cond === true, true, label, detail); }
  matches(actual, regexp, label, detail) { return this.eq(regexp.test(String(actual)), true, label, detail); }
}

/** 统一响应客户端。resp.status/resp.code/resp.data；err 时保留 message。 */
async function call(base, url, token, method, body, timeoutMs = 60000, headers = {}) {
  const r = await fetch(base + url, {
    method,
    headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json', ...headers },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(timeoutMs),
  });
  const raw = await r.text();
  let envelope = {};
  try { envelope = JSON.parse(raw); } catch { envelope = { raw: raw.slice(0, 400) }; }
  return { status: r.status, code: envelope.code ?? envelope.error ?? '(none)', message: envelope.message ?? envelope.userMessage ?? '', data: envelope.data, raw };
}

export function client(token, { base = GATEWAY, label = '' } = {}) {
  return {
    async get(url, timeoutMs, headers) { return call(base, url, token, 'GET', undefined, timeoutMs, headers); },
    async post(url, body, timeoutMs, headers) { return call(base, url, token, 'POST', body, timeoutMs, headers); },
    async put(url, body, timeoutMs, headers) { return call(base, url, token, 'PUT', body, timeoutMs, headers); },
    async patch(url, body, timeoutMs, headers) { return call(base, url, token, 'PATCH', body, timeoutMs, headers); },
    async del(url, body, timeoutMs, headers) { return call(base, url, token, 'DELETE', body, timeoutMs, headers); },
    base, label,
  };
}

/** 权威库只读查询（业务权威源 PG；仅 SELECT/解析用途，绝不写库）。 */
export function pgOne(env, sql) {
  return execFileSync(PSQL, ['-h', '127.0.0.1', '-p', '25432', '-U', env.AGENT_DB_USER, '-d', 'm53_e2e', '-At', '-v', 'ON_ERROR_STOP=1', '-c', sql],
    { encoding: 'utf8', env: { ...process.env, PGPASSWORD: env.AGENT_DB_PASSWORD } }).trim();
}

/** 数据面置备（唯一例外，如实登记）：S7 集成凭据签发端点绑定 IAM 权威链
 * （AuthorityContext.requireFor→app-version 资源绑定），dev-hmac 本地栈无权威复核入口，
 * 与 IntegrationHostTicketTest 夹具同口径经权威库置备凭据行+密文文件。仅用于 S7 前置。 */
export function pgExec(env, sql) {
  return execFileSync(PSQL, ['-h', '127.0.0.1', '-p', '25432', '-U', env.AGENT_DB_USER, '-d', 'm53_e2e', '-v', 'ON_ERROR_STOP=1', '-c', sql],
    { encoding: 'utf8', env: { ...process.env, PGPASSWORD: env.AGENT_DB_PASSWORD } });
}

/** SecretVault 本地文件格式复刻（nonce12+AES-256-GCM(iv=nonce, key=SHA256(masterKey)) 之
 * JSON {tenantId,value}，tag 追加尾部）——仅供 S7 数据面置备；密钥不进日志/证据。 */
export async function vaultPut(env, tenantId, secret) {
  const crypto = await import('node:crypto');
  const fs = await import('node:fs/promises');
  const dir = env.E2E_SECRET_DIR || path.join(REPO_ROOT, '.local', 'secrets-m53');
  const key = crypto.createHash('sha256').update(String(env.AGENT_MASTER_KEY), 'utf8').digest();
  const nonce = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', key, nonce);
  const encrypted = Buffer.concat([cipher.update(JSON.stringify({ tenantId, value: secret }), 'utf8'), cipher.final()]);
  const record = Buffer.concat([nonce, encrypted, cipher.getAuthTag()]);
  const id = crypto.randomUUID();
  await fs.mkdir(dir, { recursive: true });
  await fs.writeFile(path.join(dir, id + '.secret'), record, { flag: 'wx' });
  return 'vault:' + id;
}

/** public_id → 数字 id（任务详情/事件路径参数为数字 id；公开回执只含 public_id，属接口空隙，登记见 README）。 */
export function taskIdByPublicId(env, publicId) {
  const v = pgOne(env, `select id from engine.task where public_id='${publicId}'`);
  if (!/^\d+$/.test(v)) throw new Error('任务解析失败: ' + publicId);
  return v;
}

export function runId() { return crypto.randomUUID().replaceAll('-', '').slice(0, 16); }

export async function eventually(fn, { attempts = 20, intervalMs = 500, label = '' } = {}) {
  let last;
  for (let i = 0; i < attempts; i++) {
    last = await fn();
    if (last) return last;
    await new Promise((r) => setTimeout(r, intervalMs));
  }
  throw new Error('eventually 超时: ' + label);
}

export { REPO_ROOT };
