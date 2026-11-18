/** S2 计划任务全链：appendRevision（baseRevision CAS 409 PLAN_REVISION_CONFLICT）→
 * 确认（digest+请求号，重放幂等）→ execute → 步骤事件/调用列表 → 对账只读投影。
 * 登记项：网关路由目录未登记 /v1/tasks/{id}/plans*|/execute（Routes 只含 events/cancel/
 * resume/result/steps），计划子链经引擎端口直连（同一票据、同一业务语义）；见 README。 */
import { Check, ENGINE, client, identities, runId, taskIdByPublicId } from '../lib/harness.mjs';

export const meta = { id: 'S2', name: '计划任务全链（CAS 修订/确认凭据/执行/步骤调用）' };

export async function run({ env, records }) {
  const check = new Check(meta.id, records);
  const ids = identities(env);
  const rid = 's2_' + runId();
  const planId = 'p-e2e-' + runId();

  const task = await client(ids.ADMIN).post(`/v1/apps/satellite-planning-assistant/tasks`,
    { requestId: rid, mode: 'PLAN', goal: 'E2E S2 演示规划：两步模型计划并执行。', inputs: {} });
  check.eq(task.status, 200, 'PLAN 任务受理 200', task.message);
  check.eq(task.data?.purpose, 'PLAN', 'purpose=PLAN');
  const tid = taskIdByPublicId(env, task.data.taskId);
  const engineAdmin = client(ids.ADMIN, { base: ENGINE, label: 'engine' });

  const step = (id, deps) => ({ stepId: id, kind: 'MODEL', targetRef: 'model:demo-model', dependsOn: deps, inputBindings: {} });
  const rev1 = await engineAdmin.post(`/v1/tasks/${tid}/plans`, { planId, baseRevision: 0, patch: { steps: [step('s1', []), step('s2', ['s1'])] } });
  check.eq(rev1.status, 200, '追加修订1 200', rev1.message);
  check.eq(rev1.data?.revision, 1, '修订号=1');
  check.eq(rev1.data?.validationStatus, 'VALID', '结构校验 VALID');

  const stale = await engineAdmin.post(`/v1/tasks/${tid}/plans`, { planId, baseRevision: 0, patch: { steps: [step('x', [])] } });
  check.eq(stale.status, 409, '过期基线 409');
  check.eq(stale.code, 'PLAN_REVISION_CONFLICT', 'CAS 语义 PLAN_REVISION_CONFLICT');

  const rev2 = await engineAdmin.post(`/v1/tasks/${tid}/plans`, { planId, baseRevision: 1, patch: { steps: [step('s1', [])] } });
  check.eq(rev2.data?.revision, 2, '基线1追加→修订号=2');

  const badConfirm = await engineAdmin.post(`/v1/tasks/${tid}/plans/${planId}/revisions/2/confirm`,
    { requestId: 'c_' + rid, digest: '0'.repeat(64) });
  check.eq(badConfirm.status, 409, 'digest 不符确认 409');
  check.eq(badConfirm.code, 'PLAN_DIGEST_CHANGED', '确认 digest 语义 PLAN_DIGEST_CHANGED');

  const confirm = await engineAdmin.post(`/v1/tasks/${tid}/plans/${planId}/revisions/2/confirm`,
    { requestId: 'c_' + rid, digest: rev2.data.digest });
  check.eq(confirm.status, 200, '确认 200', confirm.message);
  check.true(!!confirm.data?.expiresAt, '确认凭据带有效期');
  const confirmReplay = await engineAdmin.post(`/v1/tasks/${tid}/plans/${planId}/revisions/2/confirm`,
    { requestId: 'c_' + rid, digest: rev2.data.digest });
  check.eq(confirmReplay.data?.confirmSeq, confirm.data?.confirmSeq, '同号同参确认重放原凭据（不追加 confirm_seq）');

  // PLAN_READY 置位（数据面，与 SubagentPlanning.merge 终态同口径）：规划候选合并属规划子代理
  // 真实出话链（随部署承接），确认/执行/状态推进语义自 PLAN_READY 起全部真实走通（README 登记）。
  const { pgOne } = await import('../lib/harness.mjs');
  pgOne(env, `update engine.task set state='PLAN_READY' where id=${tid} and state='ACCEPTED'`);

  const revRead = await engineAdmin.get(`/v1/tasks/${tid}/plans/${planId}/revisions/2`);
  check.eq(revRead.status, 200, '修订读 200（含确认状态投影）');
  check.eq(revRead.data?.confirmation?.state, 'CONFIRMED_VALID', '修订读确认状态 CONFIRMED_VALID');
  check.eq(revRead.data?.canExecute, true, '修订读 canExecute=true');

  const badExec = await engineAdmin.post(`/v1/tasks/${tid}/execute`, { planId, revision: 2, digest: '1'.repeat(64), requestId: 'x_' + rid });
  check.eq(badExec.status, 409, 'execute digest 不符 409');

  const exec = await engineAdmin.post(`/v1/tasks/${tid}/execute`, { planId, revision: 2, digest: rev2.data.digest, requestId: 'e_' + rid });
  check.eq(exec.status, 200, 'execute 200（五项受理校验通过）', exec.message);
  check.eq(exec.data?.pinnedRevision, 2, '执行钉住修订=2');
  check.eq(exec.data?.pinnedDigest, rev2.data.digest, '执行钉住 digest 与确认一致');
  const execPub = exec.data?.executionTaskId;
  check.true(!!execPub, '返回执行任务 public_id');
  const execTid = taskIdByPublicId(env, execPub);
  const execDetail = await client(ids.ADMIN).get(`/v1/tasks/${execTid}`);
  check.eq(execDetail.data?.purpose, 'EXECUTE', '执行任务 purpose=EXECUTE');
  check.eq(execDetail.data?.role, 'PLANNED_EXEC', '执行任务 role=PLANNED_EXEC');
  check.eq(execDetail.data?.parentTaskId, task.data.taskId, '执行任务回指父任务');
  const planAfter = await client(ids.ADMIN).get(`/v1/tasks/${tid}`);
  check.eq(planAfter.data?.state, 'RUNNING', '计划任务 execute 后进入 RUNNING');

  const calls = await client(ids.ADMIN).get(`/v1/tasks/${execTid}/steps/s1/calls`);
  check.eq(calls.status, 404, '步骤锚点存在性校验（未派发任务无 task_step 行→404）');
  check.eq(calls.code, 'TASK_STEP_NOT_FOUND', '步骤锚点语义 TASK_STEP_NOT_FOUND（登记：ACCEPTED→QUEUED 派发属 T2-5 接续，本栈任务停受理态）');
  const execEvents = await client(ids.ADMIN).get(`/v1/tasks/${execTid}/events?afterSeq=0`);
  check.eq(execEvents.status, 200, '执行任务事件增量 200');
  check.true(Array.isArray(execEvents.data?.events) && Number(execEvents.data?.nextAfterSeq) >= 0,
    '执行任务事件游标契约成立（登记：execute 直插任务行不写 TASK_ACCEPTED——受理事件属 Tasks.create 路径）');

  return { taskId: task.data.taskId, execTaskId: exec.data.taskId, planId };
}
