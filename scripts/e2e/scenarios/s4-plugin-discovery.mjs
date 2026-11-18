/** S4 插件发现闭环（R-6 已接线）：ADMIN 登记→discover(202 QUEUED)→轮询 SUCCEEDED（差异
 * changes.added）→enable（执行器发布版本天然带差异展示，过差异确认门）→grant→重复 grant
 * 409→revoke→来源停用拦截 discover 409。
 * 登记项：409 PLUGIN_DIFF_NOT_SHOWN 分支需数据面直写版本（recordDiscoveredVersion 无 HTTP
 * 端点），单元层由 PluginDiscoveryExecutorTest 覆盖，本场景断言其正向路径，见 README。 */
import { Check, client, identities, eventually, runId } from '../lib/harness.mjs';

export const meta = { id: 'S4', name: '插件发现闭环（发现/差异确认门/授权撤权/停用拦截）' };

export async function run({ env, records }) {
  const check = new Check(meta.id, records);
  const ids = identities(env);
  const rid = runId();
  const admin = client(ids.ADMIN);
  const user = client(ids.USER);

  const code = 'e2esrc' + rid;
  const src = await admin.post('/admin/plugin-sources',
    { code, name: 'E2E S4 插件来源', sourceType: 'REGISTRY', location: 'fixture://e2e-' + rid });
  check.eq(src.status, 200, 'ADMIN 登记来源 200', src.message);
  check.eq(src.data?.status, 'ACTIVE', '来源初始 ACTIVE');
  const sid = src.data.id;

  const userCreate = await user.post('/admin/plugin-sources',
    { code: 'u' + rid, name: '越权来源', sourceType: 'REGISTRY', location: 'fixture://x' });
  check.eq(userCreate.status, 403, 'USER 登记来源 403（ADMIN 门）');

  const discover = await admin.post(`/admin/plugin-sources/${sid}/discover`);
  check.eq(discover.status, 202, 'discover 受理 202 QUEUED', discover.message);
  check.eq(discover.data?.status, 'QUEUED', '受理态 QUEUED');
  check.eq(discover.data?.batchNo, 1, '批次号单调起点 1');
  const discoveryId = discover.data?.discoveryId;

  const done = await eventually(async () => {
    const d = await admin.get(`/admin/plugin-sources/${sid}/discoveries/${discoveryId}`);
    if (d.data?.status === 'SUCCEEDED' || d.data?.status === 'FAILED') return d;
    return null;
  }, { attempts: 20, intervalMs: 700, label: 'discovery 终态' });
  check.eq(done.data?.status, 'SUCCEEDED', '轮询 discovery 至 SUCCEEDED');
  const added = done.data?.changes?.added || [];
  check.true(added.some((a) => a.versionId > 0 && a.plugin && a.version), '差异确认门材料：changes.added 含 plugin/version/versionId',
    JSON.stringify(added).slice(0, 120));

  const plugins = await admin.get(`/admin/plugins?source=${sid}`);
  const pv = (Array.isArray(plugins.data) ? plugins.data : plugins.data?.plugins || [])[0];
  check.eq(pv?.status, 'DISCOVERED', '目录版本初始 DISCOVERED');
  const vid = pv?.versionId;

  const enabled = await admin.post(`/admin/plugins/${vid}/enable`);
  check.eq(enabled.status, 200, 'enable 200（执行器发布版本随成功批次展示过差异→过门）', enabled.message);
  check.eq(enabled.data?.status, 'ENABLED', '版本 ENABLED');

  const grant = await admin.post(`/admin/plugins/${vid}/grants`,
    { granteeType: 'TENANT', granteeCode: 'demo', capabilities: pv.capabilities });
  check.eq(grant.status, 200, 'grant 200（能力子集=版本能力）', grant.message);
  check.eq(grant.data?.status, 'GRANTED', '授权态 GRANTED');
  const grantId = grant.data?.grantId || grant.data?.id;
  const grantDup = await admin.post(`/admin/plugins/${vid}/grants`,
    { granteeType: 'TENANT', granteeCode: 'demo', capabilities: pv.capabilities });
  check.eq(grantDup.status, 409, '重复授权 409');
  check.eq(grantDup.code, 'PLUGIN_GRANT_EXISTS', '重复授权语义 PLUGIN_GRANT_EXISTS');

  const revoked = await admin.del(`/admin/plugins/grants/${grantId}`, { reason: 'E2E S4 撤权' });
  check.eq(revoked.status, 200, 'revoke 200', revoked.message);
  check.eq(revoked.data?.status, 'REVOKED', '撤权态 REVOKED（记录保留不删除）');

  // 登记项：来源停用/启停端点（disableSource/enableSource）未暴露 HTTP 面，停用拦截
  // （409 PLUGIN_SOURCE_DISABLED）在服务层存在且经 PluginDiscoveryExecutorTest 单元覆盖；
  // 本场景以来源列表投影收尾并如实登记该 API 空隙，见 README。
  const sources = await admin.get('/admin/plugin-sources');
  check.eq(sources.status, 200, '来源列表投影 200（末态留档）');
  check.true((sources.data || []).some((s) => s.id === sid), '登记来源在列表可见');

  return { sourceId: sid, versionId: vid };
}
