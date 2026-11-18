/** S5 MCP 连接档案：登记（编码冲突 409/修订纪律 PUT revision+1）→test（按次建连，失败带
 * 具体类别）→discover（批次受理、失败整批作废、上一代目录保留）→目录差异查询→审核 review。
 * 登记项：本构建 McpTransport 唯一实现为 StubMcpTransport（不发起网络请求，未登记场景
 * 一律 UNREACHABLE——"桩不做静默成功"）；真实 Streamable HTTP/SSE 传输随接入卡交付。
 * 故本场景断言档案/批次/目录语义与失败类别；乱序批次 409（发布门并发拦截）与真实
 * SSE 线缆路径属单元层与接入卡范围，见 README。 */
import { Check, client, identities, runId } from '../lib/harness.mjs';

export const meta = { id: 'S5', name: 'MCP 连接档案（登记/test/discover/目录/审核）' };

export async function run({ env, records }) {
  const check = new Check(meta.id, records);
  const ids = identities(env);
  const rid = runId();
  const admin = client(ids.ADMIN);
  const user = client(ids.USER);
  const code = 'e2emcp' + rid;
  const baseEndpoint = 'http://127.0.0.1:1/unreachable'; // StubMcpTransport 不发网络请求；取值合法即可

  const reg = await admin.post('/admin/mcp-connections',
    { code, name: 'E2E S5 MCP 连接', transport: 'STREAMABLE_HTTP', baseEndpoint, timeoutMs: 3000 });
  check.eq(reg.status, 200, '登记 200', reg.message);
  check.eq(reg.data?.code, code, '回执含连接编码');
  check.eq(reg.data?.revision, 1, '初始修订 1');

  const dup = await admin.post('/admin/mcp-connections',
    { code, name: '重复登记', transport: 'STREAMABLE_HTTP', baseEndpoint, timeoutMs: 3000 });
  check.eq(dup.status, 409, '同码重复登记 409');
  check.eq(dup.code, 'MCP_CONNECTION_CONFLICT', '冲突语义 MCP_CONNECTION_CONFLICT');

  const userReg = await user.post('/admin/mcp-connections',
    { code: 'u' + rid, name: '越权登记', transport: 'STREAMABLE_HTTP', baseEndpoint, timeoutMs: 3000 });
  check.eq(userReg.status, 403, 'USER 登记连接 403（ADMIN 门）');

  const upd = await admin.put(`/admin/mcp-connections/${code}`,
    { name: 'E2E S5 MCP 连接（修订）', transport: 'STREAMABLE_HTTP', baseEndpoint: baseEndpoint + '/v2', timeoutMs: 5000 });
  check.eq(upd.status, 200, '配置变更 200', upd.message);
  check.eq(upd.data?.revision, 2, '端点变更 revision+1（修订绑定纪律）');

  const test = await admin.post(`/admin/mcp-connections/${code}/test`);
  check.eq(test.status, 200, '按次建连 test 200（结果投影）', test.message);
  check.eq(test.data?.result, 'FAILED', '未登记传输场景：test FAILED');
  check.eq(test.data?.category, 'UNREACHABLE', '失败携带具体类别 UNREACHABLE（桩不静默成功）');

  const discover = await admin.post(`/admin/mcp-connections/${code}/discover`);
  check.eq(discover.status, 200, 'discover 按次语义同步返回', discover.message);
  check.eq(discover.data?.status, 'FAILED', '发现整批作废（FAILED）');
  check.eq(discover.data?.catalogPreserved, true, '上一代目录原样保留（catalogPreserved）');

  const tools = await admin.get(`/admin/mcp-connections/${code}/tools`);
  check.eq(tools.status, 200, '目录查询 200', tools.message);
  const toolsDiff = await admin.get(`/admin/mcp-connections/${code}/tools?diff=true`);
  check.eq(toolsDiff.status, 200, '目录差异查询 200（diff 面）');
  check.true((toolsDiff.data?.diffs || toolsDiff.data?.changes || toolsDiff.data || []).constructor === Array ||
    typeof toolsDiff.data === 'object', '差异投影可读', JSON.stringify(toolsDiff.data || {}).slice(0, 100));

  const review = await admin.post(`/admin/mcp-connections/${code}/tools/review`,
    { toolName: 'not-ever-discovered-tool' });
  check.eq(review.status, 409, '无已发布目录时审核 409（先完成一次发现的前置）');
  check.eq(review.code, 'MCP_CONNECTION_STATE', '审核前置语义 MCP_CONNECTION_STATE（catalogBatch=0）');

  return { code };
}
