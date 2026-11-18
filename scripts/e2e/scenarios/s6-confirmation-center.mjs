/** S6 确认中心：counts 四类（publishReview/highRisk/planConfirmation/draftMemory）+total，
 * 角色门（USER 403/BUILDER 可读），confirmableBy=me 投影（B4-1）与确认闭环计数联动。
 * PLAN_READY 置位为数据面（同 S2 口径——规划候选合并随部署承接，README 登记）；
 * 投影/确认/计数联动全部走真实 HTTP。 */
import { Check, ENGINE, client, identities, runId, taskIdByPublicId } from '../lib/harness.mjs';

export const meta = { id: 'S6', name: '确认中心（四类计数/confirmableBy=me/确认闭环）' };

export async function run({ env, records }) {
  const check = new Check(meta.id, records);
  const ids = identities(env);
  const rid = runId();
  const admin = client(ids.ADMIN);
  const builder = client(ids.BUILDER);
  const user = client(ids.USER);

  const counts = async (tok) => (await client(tok).get('/admin/confirmation-center/counts')).data;
  const c0 = await counts(ids.ADMIN);
  check.eq(c0?.total, (c0?.publishReview ?? 0) + (c0?.highRisk ?? 0) + (c0?.planConfirmation ?? 0) + (c0?.draftMemory ?? 0),
    'total=四类之和', JSON.stringify(c0));
  const cUser = await client(ids.USER).get('/admin/confirmation-center/counts');
  check.eq(cUser.status, 403, 'USER 读确认中心 403');
  check.eq(cUser.code, 'CONFIRMATION_CENTER_FORBIDDEN', '越权语义 CONFIRMATION_CENTER_FORBIDDEN');
  const cBuilder = await builder.get('/admin/confirmation-center/counts');
  check.eq(cBuilder.status, 200, 'BUILDER 可读确认中心（管理台入口门）');

  // 计划确认联动：USER 受理 PLAN 任务 + VALID 头修订（无确认凭据）→ PLAN_READY（数据面）
  const task = await user.post(`/v1/apps/satellite-planning-assistant/tasks`,
    { requestId: 's6_' + rid, mode: 'PLAN', goal: 'E2E S6 计划确认计数联动。', inputs: {} });
  check.eq(task.status, 200, 'USER PLAN 任务受理 200', task.message);
  const tid = taskIdByPublicId(env, task.data.taskId);
  const planId = 'p-s6-' + rid;
  const rev = await client(ids.USER, { base: ENGINE }).post(`/v1/tasks/${tid}/plans`,
    { planId, baseRevision: 0, patch: { steps: [{ stepId: 's1', kind: 'MODEL', targetRef: 'model:demo-model', dependsOn: [], inputBindings: {} }] } });
  check.eq(rev.data?.validationStatus, 'VALID', '无凭据 VALID 修订就绪');
  const { pgOne } = await import('../lib/harness.mjs');
  pgOne(env, `update engine.task set state='PLAN_READY' where id=${tid} and state='ACCEPTED'`);

  const mine = await user.get('/v1/tasks?confirmableBy=me');
  check.eq(mine.status, 200, 'confirmableBy=me 投影 200', mine.message);
  const items = mine.data?.items || [];
  const hit = items.find((x) => x.taskId === task.data.taskId);
  check.true(!!hit, 'USER confirmable 投影含待确认修订（PLAN_READY+VALID 头修订+无有效凭据）', JSON.stringify(items).slice(0, 160));
  check.true(hit && hit.planId === planId, '投影携带 planId', hit ? 'planId=' + hit.planId : 'miss');
  check.true(hit && Number(hit.revision) === 1, '投影携带修订号');
  check.true(hit && !!hit.digest, '投影携带 digest');

  const c1 = await counts(ids.ADMIN);
  check.true(Number(c1?.planConfirmation) > Number(c0?.planConfirmation ?? 0),
    '无凭据 VALID 头修订计入 planConfirmation 计数（ADMIN 租户全域）', `${c0?.planConfirmation}→${c1?.planConfirmation}`);

  const confirmed = await client(ids.USER, { base: ENGINE }).post(`/v1/tasks/${tid}/plans/${planId}/revisions/1/confirm`,
    { requestId: 's6c_' + rid, digest: rev.data.digest });
  check.eq(confirmed.status, 200, 'USER 确认自己的待办 200', confirmed.message);
  const mine2 = await user.get('/v1/tasks?confirmableBy=me');
  check.true(!((mine2.data?.items || []).some((x) => x.taskId === task.data.taskId)),
    '确认后退出 confirmable 投影');
  const c2 = await counts(ids.ADMIN);
  check.eq(Number(c2?.planConfirmation), Number(c1?.planConfirmation) - 1,
    '确认凭据落地后 planConfirmation 计数-1', `${c1?.planConfirmation}→${c2?.planConfirmation}`);

  const badParam = await user.get('/v1/tasks?confirmableBy=you');
  check.eq(badParam.status, 400, 'confirmableBy 仅支持 me（400）');
  check.eq(badParam.code, 'TASK_CONFIRMABLE_INVALID', '参数语义 TASK_CONFIRMABLE_INVALID');

  return { taskId: task.data.taskId, planId };
}
