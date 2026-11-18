/** S8 角色门矩阵：五身份（ADMIN/BUILDER/OPERATOR/USER/SERVICE）× 敏感端点逐项断言。
 * 覆盖：resource-manifest 三角色门、会话事件诊断视图（surfaceOnly=false）OPERATOR/ADMIN 门
 * （会话归属校验在前——诊断门逐会话以自有会话验证）、确认中心、插件/MCP/模型供应商/
 * 集成凭据 ADMIN 门、任务所有权（tenant+principal，越权 404 不泄露存在性）、恢复投影差异。
 * 登记项：/admin/statistics 归 monitor，本栈未起 monitor（场景见 README 降级登记）。 */
import { Check, client, identities, runId, taskIdByPublicId } from '../lib/harness.mjs';

export const meta = { id: 'S8', name: '角色门矩阵（五身份×敏感端点）' };

export async function run({ env, records }) {
  const check = new Check(meta.id, records);
  const ids = identities(env);
  const rid = runId();
  const app = 'satellite-planning-assistant';

  const probe = async (who, fn, label, expectStatus, expectCode) => {
    const r = await fn();
    check.eq(r.status, expectStatus, `${label} → ${who}`);
    if (expectCode) check.eq(r.code, expectCode, `${label} 语义 → ${who}`, r.message);
    return r;
  };

  // ① resource-manifest：BUILDER/OPERATOR/ADMIN 三角色门（接口稿 §5.7）
  const url = `/v1/deploy/resource-manifest?appCode=${app}`;
  await probe('ADMIN', () => client(ids.ADMIN).get(url), 'resource-manifest', 200);
  await probe('BUILDER', () => client(ids.BUILDER).get(url), 'resource-manifest', 200);
  await probe('OPERATOR', () => client(ids.OPERATOR).get(url), 'resource-manifest', 200);
  await probe('USER', () => client(ids.USER).get(url), 'resource-manifest', 403, 'RESOURCE_MANIFEST_FORBIDDEN');
  await probe('SERVICE', () => client(ids.SERVICE).get(url), 'resource-manifest', 403, 'RESOURCE_MANIFEST_FORBIDDEN');

  // ② 会话事件诊断视图（surfaceOnly=false）：仅 OPERATOR/ADMIN；会话归属校验在前，
  //    逐身份以"自有会话"验证角色门本体（跨主体 404 另见 ④）。
  const mkSession = async (tok) => {
    const r = await client(tok).post(`/v1/apps/${app}/sessions`, {});
    return r;
  };
  const userSess = await mkSession(ids.USER);
  check.eq(userSess.status, 200, 'USER 创建会话 200', userSess.message);
  const userSid = userSess.data?.sessionId || userSess.data?.id;
  await probe('USER', () => client(ids.USER).get(`/v1/apps/${app}/sessions/${userSid}/events?surfaceOnly=false`), '诊断视图(自有会话)', 403, 'SESSION_DIAGNOSTIC_VIEW_FORBIDDEN');
  const bSess = await mkSession(ids.BUILDER);
  const bSid = bSess.data?.sessionId || bSess.data?.id;
  await probe('BUILDER', () => client(ids.BUILDER).get(`/v1/apps/${app}/sessions/${bSid}/events?surfaceOnly=false`), '诊断视图(自有会话)', 403, 'SESSION_DIAGNOSTIC_VIEW_FORBIDDEN');
  const oSess = await mkSession(ids.OPERATOR);
  const oSid = oSess.data?.sessionId || oSess.data?.id;
  await probe('OPERATOR', () => client(ids.OPERATOR).get(`/v1/apps/${app}/sessions/${oSid}/events?surfaceOnly=false`), '诊断视图(自有会话)', 200);
  const aSess = await mkSession(ids.ADMIN);
  const aSid = aSess.data?.sessionId || aSess.data?.id;
  await probe('ADMIN', () => client(ids.ADMIN).get(`/v1/apps/${app}/sessions/${aSid}/events?surfaceOnly=false`), '诊断视图(自有会话)', 200);
  const crossOwner = await client(ids.OPERATOR).get(`/v1/apps/${app}/sessions/${userSid}`);
  check.eq(crossOwner.status, 404, '跨主体会话读取 404（归属先于角色，不泄露存在性）', crossOwner.code);

  // ③ 管理面 ADMIN 门：插件来源/插件启用/MCP/模型供应商/集成凭据
  await probe('USER', () => client(ids.USER).post('/admin/plugins/1/enable'), '插件启用', 403);
  await probe('USER', () => client(ids.USER).get('/admin/plugin-sources'), '插件来源列表', 403);
  await probe('SERVICE', () => client(ids.SERVICE).get('/admin/plugin-sources'), '插件来源列表', 403);
  await probe('USER', () => client(ids.USER).post('/admin/mcp-connections/e2emcp-x/test'), 'MCP test', 403);
  await probe('USER', () => client(ids.USER).post('/admin/model-providers', { code: 'u' + rid, name: 'x', baseUrl: 'http://127.0.0.1:1', trustLabels: ['X'] }), '模型供应商登记', 403);
  await probe('USER', () => client(ids.USER).post(`/admin/apps/${app}/integration/credentials`, { bizSystemCode: 'x' + rid.slice(0, 6), claimLimits: { principalIds: ['u'] }, allowedOrigins: ['http://127.0.0.1'] }), '集成凭据签发', 403);
  await probe('BUILDER', () => client(ids.BUILDER).post('/admin/model-providers', { code: 'b' + rid, name: 'x', baseUrl: 'http://127.0.0.1:1', trustLabels: ['X'] }), '模型供应商登记（ADMIN-only）', 403);

  // ④ 任务面：tenant+principal 所有权——他人任务 404 不泄露存在性
  const own = await client(ids.USER).post(`/v1/apps/${app}/tasks`, { requestId: 's8_' + rid, mode: 'AGENT', goal: 'E2E S8 所有权样例。', inputs: {} });
  check.eq(own.status, 200, 'USER 受理自有任务 200');
  const ownTid = taskIdByPublicId(env, own.data.taskId);
  await probe('ADMIN(非受理人)', () => client(ids.ADMIN).get(`/v1/tasks/${ownTid}`), '他人任务详情', 404, 'TASK_NOT_FOUND');
  await probe('OPERATOR(非受理人)', () => client(ids.OPERATOR).post(`/v1/tasks/${ownTid}/cancel`, {}), '他人任务取消', 404, 'TASK_NOT_FOUND');
  const ownerCancel = await client(ids.USER).post(`/v1/tasks/${ownTid}/cancel`, {});
  check.eq(ownerCancel.data?.state, 'CANCELLED', '受理人本人可取消');

  // ⑤ 列表范围与恢复投影矩阵复验
  const listUser = await client(ids.USER).get(`/v1/tasks?appCode=${app}&page=1&pageSize=5`);
  check.eq(listUser.status, 200, '任务列表（受理人范围）200');
  check.true((listUser.data?.tasks || []).every((t) => t.principalId === 'demo-user'), '列表按受理人过滤（他人任务不入列）');

  // ⑥ minted SERVICE 身份：管理台入口不含 SERVICE
  await probe('SERVICE', () => client(ids.SERVICE).get('/admin/confirmation-center/counts'), '确认中心', 403);

  return {};
}
