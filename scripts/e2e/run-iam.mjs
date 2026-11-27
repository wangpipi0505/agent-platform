import { readFile, rename, writeFile } from 'node:fs/promises';
import { setTimeout as sleep } from 'node:timers/promises';
import { Check, client, runId } from './lib/harness.mjs';
import * as memory from './scenarios/s3-memory-domain.mjs';
import * as plugin from './scenarios/s4-plugin-discovery.mjs';
import * as mcp from './scenarios/s5-mcp-connection.mjs';

const config = JSON.parse(await readFile('runtime-config.json', 'utf8'));
const identity = JSON.parse(await readFile('identities.json', 'utf8'));
if (config.database !== 's4_e2e' || !config.modelName.startsWith('deepseek')) {
  throw new Error('The isolated E2 database and configured DeepSeek entry are required');
}
const portalTokens = Object.fromEntries(Object.entries(identity).map(([role, item]) => [role, item.token]));
if (!portalTokens.ADMIN || !portalTokens.USER || portalTokens.ADMIN === portalTokens.USER) {
  throw new Error('Two independently issued portal sessions are required');
}

const base = 'http://127.0.0.1:18090';
if (process.env.E2E_GATEWAY !== base || process.env.E2E_ENGINE !== base) {
  throw new Error('All scenario clients must use the isolated E2 gateway');
}
const app = 'r22e2-real-v2';
const peer = 'satellite-planning-assistant';
const appTasks = '/v1/apps/' + app + '/tasks';
const taskPath = (id) => '/v1/tasks/' + id;
const admin = client(portalTokens.ADMIN, { base });
const user = client(portalTokens.USER, { base });
const grantPath = '/admin/platform-authorization/grants';
const records = [];
const results = [];
const report = 'm5-3-e2e-report.json';
const env = { portalTokens };

function http(check, result, status, label, code) {
  check.eq(result.status, status, label, result.code);
  if (code) check.eq(result.code, code, label + '原因码');
  return result.data;
}

function requiredId(check, value, label) {
  if (!check.true(typeof value === 'string' && value.length > 0, label)) {
    throw new Error(label + '缺少后续步骤前置');
  }
  return value;
}

async function withRole(check, role, action) {
  if (portalTokens[role]) return action(client(portalTokens[role], { base }));
  // The directory only exposes one approved secondary test subject here.
  // Change its role in the sandbox, verify the actual session projection, and revoke the grant.
  const grant = { subject: identity.USER.subject, role, appScope: [app, peer] };
  const added = await admin.put(grantPath, grant);
  http(check, added, 200, '隔离库临时分配 ' + role);
  if (added.status !== 200) return;
  try {
    const session = await user.get('/auth/session');
    check.true(session.data?.roles?.includes(role) === true, '真实门户会话读到 ' + role);
    return await action(user);
  } finally {
    http(check, await admin.del(grantPath, { subject: grant.subject, role }), 200, '回收 ' + role);
  }
}

async function readUntil(httpClient, path, predicate, seconds) {
  const deadline = Date.now() + seconds * 1000;
  let response;
  do {
    response = await httpClient.get(path, 12000);
    if (response.status !== 200 || predicate(response.data)) return response;
    await sleep(2000);
  } while (Date.now() < deadline);
  return response;
}

async function directTask(check) {
  const body = { requestId: 's4-s1-' + runId(), mode: 'AGENT', goal: 'Reply with E2_IAM_DIRECT_OK.', inputs: {} };
  const created = http(check, await admin.post(appTasks, body), 200, '真实 ADMIN 受理 AGENT');
  const id = requiredId(check, created?.taskId, '受理返回公共任务号');
  check.true(['ACCEPTED','QUEUED'].includes(created?.state), '受理或排队状态', created?.state);
  const replay = http(check, await admin.post(appTasks, body), 200, '同请求号同参数重放');
  check.eq(replay?.taskId, id, '重放返回原任务');
  http(check, await admin.post(appTasks, { ...body, goal: 'E2 different input' }), 409, '同号异参拒绝', 'REQUEST_REPLAY');
  const path = taskPath(id);
  const detail = http(check, await admin.get(path), 200, '本人任务详情');
  check.true(Number(detail?.eventWatermark) >= 1, '详情事件水位');
  const events = http(check, await admin.get(path + '/events?afterSeq=0'), 200, '任务事件增量');
  check.true(events?.events?.some((event) => event.type === 'TASK_ACCEPTED') === true, '受理事件可读');
  if (Number.isInteger(events?.nextAfterSeq)) {
    const tail = http(check, await admin.get(path + '/events?afterSeq=' + events.nextAfterSeq), 200, '追赶事件游标');
    check.true(tail?.events?.every((event) => Number(event.seq) > events.nextAfterSeq) === true, '游标之后无重放');
  } else check.true(false, '任务事件返回有效游标');
  const terminal = await readUntil(admin, path + '/result', (value) =>
    ['SUCCEEDED','FAILED','CANCELLED','TIMED_OUT'].includes(value?.state ?? value?.status), 75);
  const output = http(check, terminal, 200, '真实模型任务结果');
  check.eq(output?.state ?? output?.status, 'SUCCEEDED', '真实 DeepSeek 执行终态', output?.stopReason ?? output?.reason);
  const control = http(check, await admin.post(appTasks, {
    ...body, requestId: 's4-s1-control-' + runId(), goal: 'E2E task control.',
  }), 200, '受理独立取消恢复样本');
  const controlId = requiredId(check, control?.taskId, '控制样本任务号');
  const cancelled = http(check, await admin.post(taskPath(controlId) + '/cancel', {}), 200, '本人取消');
  check.eq(cancelled?.state, 'CANCELLED', '取消写入终态');
  const repeat = http(check, await admin.post(taskPath(controlId) + '/cancel', {}), 200, '本人重复取消');
  check.true(repeat?.state === 'CANCELLED' && repeat.stateVersion === cancelled?.stateVersion, '重复取消复用状态版本');
  http(check, await admin.post(taskPath(controlId) + '/resume', {}), 200, '本人恢复查询终态');
  return { taskId:id, controlId, admittedState:created.state };
}

function step(stepId, dependsOn = []) {
  return { stepId, kind:'MODEL', targetRef:'model:' + config.modelName, dependsOn, inputBindings:{} };
}

async function newPlan(check, owner, scene, steps) {
  const requestId = scene + '-' + runId();
  const received = http(check, await owner.post(appTasks, {
    requestId, mode:'PLAN', goal:'Plan an isolated E2 deterministic one-sentence reply.', inputs:{},
  }), 200, 'PLAN 任务受理');
  const taskId = requiredId(check, received?.taskId, 'PLAN 任务标识');
  const path = taskPath(taskId);
  const planId = 'p-' + runId();
  const body = { planId, baseRevision:0, patch:{steps} };
  const revision = http(check, await owner.post(path + '/plans', body), 200, '本人追加计划修订');
  check.eq(revision?.validationStatus, 'VALID', '计划校验有效');
  return { taskId, path, planId, body, revision, requestId };
}

async function planTask(check) {
  const plan = await newPlan(check, admin, 's4-s2', [step('first'), step('second',['first'])]);
  const { path, planId, body, requestId } = plan;
  check.eq(plan.revision?.revision, 1, '首个计划修订');
  http(check, await admin.post(path + '/plans', body), 409, '过期基线拒绝', 'PLAN_REVISION_CONFLICT');
  const next = http(check, await admin.post(path + '/plans', {
    ...body, baseRevision:1, patch:{steps:[step('first')]},
  }), 200, '追加第二个修订');
  check.eq(next?.revision, 2, '修订顺序');
  const rev = path + '/plans/' + planId + '/revisions/2';
  http(check, await admin.post(rev + '/confirm', { requestId, digest:'0'.repeat(64) }), 409, '确认摘要错误拒绝', 'PLAN_DIGEST_CHANGED');
  const digest = requiredId(check, next?.digest, '后续绑定真实计划摘要');
  const confirmation = http(check, await admin.post(rev + '/confirm', { requestId, digest }), 200, '本人确认计划');
  check.true(Boolean(confirmation?.expiresAt), '确认回执带期限');
  const confirmationRepeat = http(check, await admin.post(rev + '/confirm', { requestId, digest }), 200, '确认请求重放');
  check.true(Boolean(confirmation?.confirmSeq) && confirmationRepeat?.confirmSeq === confirmation.confirmSeq, '重放复用确认凭据');
  const view = http(check, await admin.get(rev), 200, '计划修订回读');
  check.eq(view?.confirmation?.state, 'CONFIRMED_VALID', '确认状态投影');
  check.eq(view?.canExecute, true, '已确认计划可执行');
  const exec = { planId, revision:2, digest, requestId:'s4-ex-' + runId() };
  http(check, await admin.post(path + '/execute', { ...exec, digest:'1'.repeat(64) }), 409, '执行须绑定确认摘要');
  const accepted = http(check, await admin.post(path + '/execute', exec), 200, '执行已确认修订');
  check.eq(accepted?.pinnedRevision, 2, '执行钉住修订');
  check.eq(accepted?.pinnedDigest, digest, '执行钉住摘要');
  const execId = requiredId(check, accepted?.executionTaskId, '执行任务公共号');
  const sub = http(check, await admin.get(taskPath(execId)), 200, '执行子任务详情');
  check.eq(sub?.purpose, 'EXECUTE', '执行子任务用途');
  check.eq(sub?.parentTaskId, plan.taskId, '执行任务回指父计划');
  const events = http(check, await admin.get(taskPath(execId) + '/events?afterSeq=0'), 200, '执行任务事件');
  check.true(Array.isArray(events?.events), '执行任务事件投影');
  return { planId, taskId:plan.taskId, executionTaskId:execId };
}

async function confirmations(check) {
  const counts = '/admin/confirmation-center/counts';
  const initial = http(check, await admin.get(counts), 200, 'ADMIN 读取确认中心计数');
  const total = (report) => ['publishReview','highRisk','planConfirmation','draftMemory']
    .map((key) => Number(report?.[key])).reduce((sum,count) => sum + count, 0);
  check.true(Number.isInteger(initial?.total) && initial.total === total(initial), '四项计数与合计一致');
  http(check, await user.get(counts), 403, 'USER 无管理计数权');
  await withRole(check, 'BUILDER', async (builder) => {
    http(check, await builder.get(counts), 200, '已授权 BUILDER 读取计数');
  });
  const plan = await newPlan(check, user, 's4-s6', [step('first')]);
  const select = '/v1/tasks?confirmableBy=me';
  const before = http(check, await user.get(select), 200, 'USER 本人待确认修订列表');
  const match = before?.items?.find((entry) => entry.taskId === plan.taskId);
  check.eq(match?.planId, plan.planId, '确认前本人修订进入列表');
  const counted = http(check, await admin.get(counts), 200, '确认前重新计数');
  check.true(Number(counted?.planConfirmation) > Number(initial?.planConfirmation), '计划计数增加');
  const digest = requiredId(check, plan.revision?.digest, '确认待办使用实际摘要');
  http(check, await user.post(plan.path + '/plans/' + plan.planId + '/revisions/1/confirm', {
    requestId:'s4-s6-confirm-' + runId(), digest,
  }), 200, 'USER 确认本人修订');
  const after = http(check, await user.get(select), 200, '确认后读取列表');
  check.true(Array.isArray(after?.items) && !after.items.some((entry) => entry.taskId === plan.taskId), '已确认修订退出待办');
  const updated = http(check, await admin.get(counts), 200, '确认后重新计数');
  check.true(Number.isInteger(updated?.planConfirmation) && updated.planConfirmation === counted?.planConfirmation - 1, '确认后计划计数减少 1');
  http(check, await user.get('/v1/tasks?confirmableBy=you'), 400, '拒绝未知待办筛选', 'TASK_CONFIRMABLE_INVALID');
  return { taskId:plan.taskId, planId:plan.planId };
}

async function embed(apiKey, body, origin) {
  const r = await fetch(base + '/embed/ticket', {
    method:'POST', headers:{ Authorization:'ApiKey ' + apiKey, 'Content-Type':'application/json',
      ...(origin ? { Origin:origin } : {}) }, body:JSON.stringify(body), signal:AbortSignal.timeout(20000),
  });
  const payload = await r.json().catch(() => ({}));
  return { status:r.status, code:payload.code ?? 'NON_JSON', data:payload.data };
}

async function integrationCredential(check) {
  const biz = 'e2es4-' + runId();
  const credentialPath = '/admin/apps/' + app + '/integration/credentials';
  const identityClaims = { userId:'s4-integration-user',roles:['USER'],
    attributes:{ region:['cn-north'], dataLabels:['public'] } };
  const origin = 'http://127.0.0.1:8080';
  const issue = http(check, await admin.post(credentialPath,{
    bizSystemCode:biz, allowedOrigins:[origin],
    claimLimits:{ principalIds:[identityClaims.userId], roles:identityClaims.roles, attributes:identityClaims.attributes },
    roleMapping:{ USER:'AGENT_OPERATOR' },
  }), 200, '真实授权管理签发集成凭据');
  if (!issue?.apiKeyOnce) throw new Error('签发响应缺一次性凭据');
  return { apiKey:issue.apiKeyOnce, identityClaims, origin, revoke:() => admin.post(credentialPath + '/' + biz + '/revoke', {}) };
}

async function integration(check) {
  const credential = await integrationCredential(check);
  const { apiKey, identityClaims, origin } = credential;
  let revoked = false;
  try {
    const ticketResult = await embed(apiKey, identityClaims);
    const ticket = http(check, ticketResult, 200, '后端合法出票');
    http(check, await embed(apiKey, identityClaims, 'https://invalid.example'), 403, '拒绝未登记 Origin');
    const bad = apiKey.split(':')[0] + ':invalid';
    http(check, await embed(bad, identityClaims, origin), 401, '错误凭据拒绝');
    const issuedToken = ticket?.accessToken ?? ticket?.token ?? ticket?.access_token;
    if (!issuedToken) throw new Error('真实集成出票缺访问令牌');
    const portal = client(issuedToken, { base });
    const task = http(check, await portal.post(appTasks, { requestId:'s4-s7-' + runId(), mode:'AGENT',
      goal:'Return E2_INTEGRATION_OK.', inputs:{} }), 200, '集成票受理任务');
    const id = requiredId(check, task?.taskId, '集成任务标识');
    const path = taskPath(id);
    http(check, await portal.get(path), 200, '集成任务详情');
    http(check, await portal.get(path + '/events'), 200, '集成任务事件');
    http(check, await portal.get(path + '/result'), 200, '集成任务结果读取');
    http(check, await portal.post(path + '/resume', {}), 403, '集成票无恢复权限');
    http(check, await portal.post('/v1/apps/' + peer + '/tasks', { requestId:'s4-s7-cross-' + runId(),
      mode:'AGENT', goal:'Scope verification.', inputs:{} }), 403, '集成票跨应用受理拒绝');
    http(check, await portal.post(appTasks, { requestId:'s4-s7-plan-' + runId(), mode:'PLAN',
      goal:'Test role scope.', inputs:{} }), 403, '集成票计划受理拒绝');
    const expiring = http(check, await embed(apiKey,identityClaims), 200, '签发自然过期样本');
    const expiryToken = expiring?.accessToken;
    if (expiryToken && check.true(Number.isInteger(expiring.expiresIn) && expiring.expiresIn > 0 && expiring.expiresIn <= 60,
                                  '权威签发的短票带有效期')) {
      const expiryClient = client(expiryToken, { base });
      http(check, await expiryClient.get(path), 200, '到期前短票正常读取');
      await sleep((expiring.expiresIn + 3) * 1000);
      http(check, await expiryClient.get(path), 401, '真实短票自然到期返回 401');
    }
    const fresh = http(check, await embed(apiKey,identityClaims), 200, '签发仍在有效期内的撤销样本');
    const staleClient = client(fresh?.accessToken, { base });
    http(check, await staleClient.get(path), 200, '撤销前新票有效');
    const receipt = await credential.revoke();
    http(check, receipt, 200, '集成凭据撤销');
    revoked = receipt.status === 200;
    http(check, await staleClient.get(path), 401, '撤销之后旧集成票不可再用');
    return { taskId:id };
  } finally {
    if (!revoked) http(check, await credential.revoke(), 200, '失败后回收集成凭据');
  }
}

async function roleMatrix(check) {
  const manifest = '/v1/deploy/resource-manifest?appCode=' + peer;
  http(check, await admin.get(manifest), 200, 'ADMIN 资源清单');
  http(check, await user.get(manifest), 403, 'USER 不能读取部署资源清单');
  await withRole(check, 'BUILDER', async (builder) => {
    http(check, await builder.get(manifest), 200, 'BUILDER 资源清单');
    http(check, await builder.post('/admin/model-providers', {}), 403, 'BUILDER 无模型供应商配置权');
  });
  await withRole(check, 'OPERATOR', async (operator) => {
    http(check, await operator.get(manifest), 200, 'OPERATOR 资源清单');
    const session = http(check, await operator.post('/v1/apps/' + app + '/sessions', {}), 200, 'OPERATOR 本人会话');
    if (session?.sessionId) {
      http(check, await operator.get('/v1/apps/' + app + '/sessions/' + session.sessionId + '/events?surfaceOnly=false'),
        200, 'OPERATOR 本人会话诊断视图');
    }
  });
  const userSessions = '/v1/apps/' + app + '/sessions';
  const own = http(check, await user.post(userSessions, {}), 200, 'USER 本人会话');
  if (own?.sessionId) {
    http(check, await user.get(userSessions + '/' + own.sessionId + '/events?surfaceOnly=false'), 403, 'USER 无会话诊断视图权限');
    http(check, await admin.get(userSessions + '/' + own.sessionId), 403, '他人会话受归属保护');
  }
  const restricted = [
    ['/admin/plugin-sources','get'],
    ['/admin/mcp-connections/s4-placeholder/test','post'],
    ['/admin/model-providers','post'],
    ['/admin/apps/' + app + '/integration/credentials','post'],
  ];
  for (const [route, method] of restricted) http(check, await user[method](route), 403, 'USER 管理入口拒绝 ' + route);
  const credential = await integrationCredential(check);
  try {
    const ticket = http(check, await embed(credential.apiKey, credential.identityClaims), 200, 'SERVICE 真实接入出票');
    if (!ticket?.accessToken) throw new Error('SERVICE 角色样本缺少集成票');
    const service = client(ticket.accessToken, { base });
    for (const route of [manifest, '/admin/plugin-sources', '/admin/confirmation-center/counts']) {
      http(check, await service.get(route), 403, 'SERVICE 管理入口拒绝 ' + route);
    }
  } finally {
    http(check, await credential.revoke(), 200, '回收 SERVICE 样本凭据');
  }
  const task = http(check, await user.post(appTasks, { requestId:'s4-s8-'+runId(), mode:'AGENT',
    goal:'Isolated ownership test.', inputs:{} }), 200, 'USER 独立任务');
  if (task?.taskId) http(check, await admin.get(taskPath(task.taskId)), 403, '他人任务受归属保护');
  return { taskId:task?.taskId };
}

async function saveReport() {
  const summary = {
    setup:config, identityKinds:Object.keys(portalTokens), executed:results.length,
    passed:results.filter((result) => result.status === 'PASS').length,
    assertions:records.length, failedAssertions:records.filter((record) => !record.ok).length,
    results, records,
  };
  await writeFile(report + '.tmp', JSON.stringify(summary, null, 2) + '\n', {mode:0o600});
  await rename(report + '.tmp', report);
  return summary;
}

for (const [role, httpClient] of [['ADMIN',admin],['USER',user]]) {
  const actual = await httpClient.get('/auth/session');
  if (actual.status !== 200 || actual.data?.principalId !== identity[role].subject
      || !actual.data?.roles?.includes(role)) throw new Error('Real IAM session check failed: ' + role + ' ' + actual.code);
}
const runExistingScenario = (scenario) => () => scenario.run({ env, records });
const scenarios = [
  ['S1','直接任务链',directTask],
  ['S2','计划修订与执行',planTask],
  ['S3','记忆域',runExistingScenario(memory)],
  ['S4','插件发现闭环',runExistingScenario(plugin)],
  ['S5','MCP 连接',runExistingScenario(mcp)],
  ['S6','确认中心',confirmations],
  ['S7','集成服务',integration],
  ['S8','角色矩阵',roleMatrix],
];
for (const [id,name,run] of scenarios) {
  const begin = records.length;
  console.log('\n' + id + ' ' + name);
  let facts;
  try {
    facts = await run(new Check(id,records));
  } catch (error) {
    // Report invariant or HTTP failures without including headers, keys or bearer tokens.
    new Check(id,records).true(false, '场景依赖未满足', String(error.message).slice(0,160));
  }
  const assertions = records.length - begin;
  const failures = records.slice(begin).filter((record) => !record.ok).length;
  results.push({id,name,assertions,failures,status:assertions > 0 && failures === 0 ? 'PASS' : 'FAIL',facts});
  await saveReport();
}
const summary = await saveReport();
console.log(JSON.stringify({executed:summary.executed,passed:summary.passed,assertions:summary.assertions,
  failedAssertions:summary.failedAssertions}));
process.exitCode = summary.executed === 8 && summary.passed === 8 ? 0 : 1;
