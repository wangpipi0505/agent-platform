/** S3 记忆域：USER 私有保存（幂等/REQUEST_REPLAY）→列表（不含正文）/单条/检索→可见性
 * →PATCH CAS 409→越权写/删 403→删除 REVOKED 退出→重复删除幂等。
 * 登记项（README 详述）：
 * ① 经网关的 PATCH 全部 500（网关 HttpURLConnection 不支持 PATCH——平台缺陷，归口网关卡），
 *    PATCH 断言经引擎端口直连（同一票据同一语义）。
 * ② 网关对 DELETE 只转发空体（仅 POST/PUT/PATCH 写 body）——删除请求经引擎端口直连（登记缺陷）。
 * ③ 本发布（V901 种子应用未配 memoryPolicy）策略收窄为仅 PRIVATE：SHARED 读写一律
 *    403（含 ADMIN）——契约口径"未配置时 read/writeScopes 均仅含 PRIVATE"的真实行为；
 *    共享分层/审批分层语义由服务层测试覆盖（TasksApiTest/Memories 相关），此处如实断言收窄面。 */
import { Check, ENGINE, client, identities, runId } from '../lib/harness.mjs';

export const meta = { id: 'S3', name: '记忆域（私有CRUD/CAS/可见性/删除退出）' };

export async function run({ env, records }) {
  const check = new Check(meta.id, records);
  const ids = identities(env);
  const rid = runId();
  const app = 'satellite-planning-assistant';
  const user = client(ids.USER);
  const admin = client(ids.ADMIN);
  const engineUser = client(ids.USER, { base: ENGINE, label: 'engine' });
  const engineAdmin = client(ids.ADMIN, { base: ENGINE, label: 'engine' });

  const content = 'E2E S3 记忆：' + rid;
  const mem = await user.post(`/v1/apps/${app}/memories`,
    { requestId: 'm_' + rid, content, kind: 'PREFERENCE', scope: 'PRIVATE', tags: ['e2e'] });
  check.eq(mem.status, 200, 'USER 私有保存 200', mem.message);
  check.eq(mem.data?.status, 'ACTIVE', '保存即 ACTIVE');
  const mid = mem.data?.memoryId;

  const replay = await user.post(`/v1/apps/${app}/memories`,
    { requestId: 'm_' + rid, content, kind: 'PREFERENCE', scope: 'PRIVATE', tags: ['e2e'] });
  check.eq(replay.data?.memoryId, mid, '同号同参保存幂等（同 memoryId）');
  const replayDiff = await user.post(`/v1/apps/${app}/memories`,
    { requestId: 'm_' + rid, content: content + '！', kind: 'PREFERENCE', scope: 'PRIVATE' });
  check.eq(replayDiff.status, 409, '同号异参保存 409');
  check.eq(replayDiff.code, 'REQUEST_REPLAY', '保存重放语义 REQUEST_REPLAY');

  const sharedDenied = await user.post(`/v1/apps/${app}/memories`,
    { requestId: 'ms_' + rid, content: '共享写', kind: 'EXPERIENCE', scope: 'SHARED' });
  check.eq(sharedDenied.status, 403, 'USER 写共享 403');
  check.eq(sharedDenied.code, 'SCOPE_WRITE_DENIED', '共享写语义 SCOPE_WRITE_DENIED');
  const adminShared = await admin.post(`/v1/apps/${app}/memories`,
    { requestId: 'sa_' + rid, content: 'ADMIN 共享写', kind: 'EXPERIENCE', scope: 'SHARED' });
  check.eq(adminShared.status, 403, '策略收窄下 ADMIN 写共享同样 403（当前策略 writeScopes 不含 SHARED）');
  check.eq(adminShared.code, 'SCOPE_WRITE_DENIED', '策略收窄语义一致 SCOPE_WRITE_DENIED');

  const list = await user.get(`/v1/apps/${app}/memories?page=1&pageSize=50`);
  check.true((list.data?.memories || []).some((m) => m.memoryId === mid), '本人列表可见');
  check.true((list.data?.memories || []).every((m) => m.content === undefined), '列表项不含正文（单条读正文）');
  const adminList = await admin.get(`/v1/apps/${app}/memories?page=1&pageSize=100`);
  check.true(!(adminList.data?.memories || []).some((m) => m.memoryId === mid), '他人 PRIVATE 条目对 ADMIN 列表不可见');
  const searched = await user.get(`/v1/apps/${app}/memories?q=${encodeURIComponent(rid)}`);
  check.true((searched.data?.memories || []).some((m) => m.memoryId === mid), 'q 检索命中');

  const get = await user.get(`/v1/apps/${app}/memories/${mid}`);
  check.eq(get.status, 200, '单条读取含正文');
  check.true(get.data?.content !== undefined, '单条接口返回正文');

  const casBad = await engineUser.patch(`/v1/apps/${app}/memories/${mid}`, { expectedVersion: 99, content: '过期写' });
  check.eq(casBad.status, 409, '过期 CAS 409');
  check.eq(casBad.code, 'MEMORY_VERSION_CONFLICT', 'CAS 语义 MEMORY_VERSION_CONFLICT');
  const casOk = await engineUser.patch(`/v1/apps/${app}/memories/${mid}`, { expectedVersion: 1, content: content + '（修订）' });
  check.eq(casOk.status, 200, 'CAS 命中更新 200', casOk.message);
  check.eq(casOk.data?.version, 2, '版本推进到 2');

  // 种子共享条目：策略收窄面下非授权读 403（真实行为，契约"未配置仅 PRIVATE"）
  const sharedRead = await user.get(`/v1/apps/${app}/memories/demo-mem-exp-0001`);
  check.eq(sharedRead.status, 403, '策略收窄下共享条目读 403（含种子 SHARED 条目）');
  check.eq(sharedRead.code, 'MEMORY_FORBIDDEN', '共享读收窄语义 MEMORY_FORBIDDEN');

  // 越权：他人（ADMIN 主体）删除 USER 私有条目（删除体经引擎直连——登记项：网关丢弃 DELETE 体）
  const delDenied = await engineAdmin.del(`/v1/apps/${app}/memories/${mid}`, { requestId: 'd0_' + rid });
  check.eq(delDenied.status, 403, '非所有人删私有条目 403');
  check.eq(delDenied.code, 'MEMORY_DELETE_FORBIDDEN', '删除权语义 MEMORY_DELETE_FORBIDDEN');

  const revoke = await engineUser.patch(`/v1/apps/${app}/memories/${mid}`, { expectedVersion: 2, status: 'REVOKED' });
  check.eq(revoke.status, 200, '所有者撤销 200');
  check.eq(revoke.data?.status, 'REVOKED', '状态 REVOKED');
  const afterRevoke = await user.get(`/v1/apps/${app}/memories?q=${encodeURIComponent(rid)}`);
  check.true(!(afterRevoke.data?.memories || []).some((m) => m.memoryId === mid), 'REVOKED 退出检索');
  const getRevoked = await user.get(`/v1/apps/${app}/memories/${mid}`);
  check.eq(getRevoked.status, 404, 'REVOKED 单条读取 404（先撤销检索资格）');

  const mem2 = await user.post(`/v1/apps/${app}/memories`,
    { requestId: 'm2_' + rid, content: content + '（删除样例）', kind: 'PREFERENCE', scope: 'PRIVATE' });
  const del = await engineUser.del(`/v1/apps/${app}/memories/${mem2.data?.memoryId}`, {});
  check.eq(del.status, 400, '删除必须 requestId（参数校验）');
  check.eq(del.code, 'MEMORY_DELETE_REQUEST_INVALID', '删除参数语义 MEMORY_DELETE_REQUEST_INVALID');
  const del2 = await engineUser.del(`/v1/apps/${app}/memories/${mem2.data?.memoryId}`, { requestId: 'd_' + rid });
  check.eq(del2.data?.status, 'REVOKED', '删除即撤销（所有人撤销权）');
  const del3 = await engineUser.del(`/v1/apps/${app}/memories/${mem2.data?.memoryId}`, { requestId: 'd2_' + rid });
  check.eq(del3.data?.status, 'REVOKED', '重复删除幂等');

  return { memoryId: mid };
}
