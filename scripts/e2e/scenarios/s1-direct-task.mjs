/** S1 直接任务全链：create（requestId 幂等/REQUEST_REPLAY）→ events 增量（afterSeq 排他游标）
 * → detail（availableActions/recovery 投影）→ cancel/resume 幂等与终态语义。
 * 走网关 HTTP；等待态置位经权威库（与 TasksApiTest 同口径的数据面准备，登记见 README）。 */
import { Check, client, identities, runId, taskIdByPublicId } from '../lib/harness.mjs';

export const meta = { id: 'S1', name: '直接任务全链（受理/幂等/事件游标/取消恢复）' };

export async function run({ env, records }) {
  const check = new Check(meta.id, records);
  const ids = identities(env);
  const rid = 's1_' + runId();
  const goal = 'E2E S1 演示任务：给出一句话结论即可。';

  const created = await client(ids.ADMIN).post(`/v1/apps/satellite-planning-assistant/tasks`,
    { requestId: rid, mode: 'AGENT', goal, inputs: {} });
  check.eq(created.status, 200, '受理 200', created.message);
  check.eq(created.data?.state, 'ACCEPTED', '受理态 ACCEPTED');
  check.true((created.data?.availableActions || []).includes('CANCEL'), '受理即含 CANCEL 动作');

  const replay = await client(ids.ADMIN).post(`/v1/apps/satellite-planning-assistant/tasks`,
    { requestId: rid, mode: 'AGENT', goal, inputs: {} });
  check.eq(replay.status, 200, '同号同参重放 200');
  check.eq(replay.data?.taskId, created.data?.taskId, '同号同参重放返回原任务（幂等）');

  const replayDiff = await client(ids.ADMIN).post(`/v1/apps/satellite-planning-assistant/tasks`,
    { requestId: rid, mode: 'AGENT', goal: goal + '（异参）', inputs: {} });
  check.eq(replayDiff.status, 409, '同号异参 409');
  check.eq(replayDiff.code, 'REQUEST_REPLAY', '409 语义 REQUEST_REPLAY');

  const tid = taskIdByPublicId(env, created.data.taskId);
  const detail = await client(ids.ADMIN).get(`/v1/tasks/${tid}`);
  check.eq(detail.status, 200, '详情 200（数字 id 路径）', detail.message);
  check.true(detail.data?.eventWatermark >= 1, '详情含 eventWatermark');

  const ev1 = await client(ids.ADMIN).get(`/v1/tasks/${tid}/events?afterSeq=0`);
  check.eq(ev1.status, 200, '事件增量 200');
  const next = ev1.data?.nextAfterSeq;
  check.true(Number(next) >= 1, 'nextAfterSeq=排他游标（最后扫描 seq，不 +1）', 'nextAfterSeq=' + next);
  check.eq(ev1.data?.truncated, false, '末页 truncated=false');
  check.true((ev1.data?.events || []).some((e) => e.type === 'TASK_ACCEPTED'), '含 TASK_ACCEPTED 事件');
  const ev2 = await client(ids.ADMIN).get(`/v1/tasks/${tid}/events?afterSeq=${next}`);
  check.eq((ev2.data?.events || []).length, 0, 'afterSeq=next 增量为空（排他语义）');

  const c1 = await client(ids.ADMIN).post(`/v1/tasks/${tid}/cancel`, {});
  check.eq(c1.status, 200, '取消 200');
  check.eq(c1.data?.state, 'CANCELLED', '取消态 CANCELLED');
  const c2 = await client(ids.ADMIN).post(`/v1/tasks/${tid}/cancel`, {});
  check.eq(c2.data?.state, 'CANCELLED', '重复取消幂等（原终态）');
  check.eq(c2.data?.stateVersion, c1.data?.stateVersion, '重复取消不推进 stateVersion');

  const r1 = await client(ids.ADMIN).post(`/v1/tasks/${tid}/resume`, {});
  check.eq(r1.data?.state, 'CANCELLED', '终态无恢复（resume 保持 CANCELLED）');

  // 等待态恢复 + 角色投影（数据面置位 WAITING_EXTERNAL，与 TasksApiTest 同口径）
  const rid2 = 's1w_' + runId();
  const t2 = await client(ids.USER).post(`/v1/apps/satellite-planning-assistant/tasks`,
    { requestId: rid2, mode: 'AGENT', goal: 'E2E S1 等待恢复示例。', inputs: {} });
  check.eq(t2.status, 200, 'USER 受理等待态任务 200');
  const t2id = taskIdByPublicId(env, t2.data.taskId);
  const { pgOne } = await import('../lib/harness.mjs');
  pgOne(env, `update engine.task set state='WAITING_EXTERNAL' where id=${t2id} and state='ACCEPTED'`);
  const resumed = await client(ids.USER).post(`/v1/tasks/${t2id}/resume`, {});
  check.eq(resumed.status, 200, '等待态 resume 200', resumed.message);
  check.eq(resumed.data?.state, 'RUNNING', '恢复态 RUNNING');
  check.true((resumed.data?.availableActions || []).includes('RESUME'), '恢复后含 RESUME 资格（事实计算）');
  const rec = resumed.data?.recovery || {};
  check.true(rec.recoverySource !== undefined && rec.reasonCode !== undefined && rec.reasonSummary !== undefined,
    'USER 恢复投影三字段齐全');
  check.true(!('checkpoint' in rec), 'USER 不见 checkpoint（非特权投影）');
  // 特权投影验证用 OPERATOR 自有任务（任务所有权 tenant+principal 在前——跨主体 404 另见 S8）
  const rid3 = 's1o_' + runId();
  const t3 = await client(ids.OPERATOR).post(`/v1/apps/satellite-planning-assistant/tasks`,
    { requestId: rid3, mode: 'AGENT', goal: 'E2E S1 OPERATOR 恢复示例。', inputs: {} });
  const t3id = taskIdByPublicId(env, t3.data.taskId);
  pgOne(env, `update engine.task set state='WAITING_EXTERNAL' where id=${t3id} and state='ACCEPTED'`);
  const opResume = await client(ids.OPERATOR).post(`/v1/tasks/${t3id}/resume`, {});
  check.eq(opResume.status, 200, 'OPERATOR 恢复自有等待任务 200');
  const opDetail = await client(ids.OPERATOR).get(`/v1/tasks/${t3id}`);
  const keys = Object.keys(opDetail.data?.recovery || {});
  check.true(keys.length > 3, 'OPERATOR 见特权恢复投影（含检查点/内部字段）', 'keys=' + keys.join(','));
  const crossResume = await client(ids.USER).post(`/v1/tasks/${t3id}/resume`, {});
  check.eq(crossResume.status, 404, '跨主体恢复 404（所有权先于角色）');

  return { taskId: created.data.taskId, waitingTaskId: t2.data.taskId };
}
