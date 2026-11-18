/** R-14 M5-4 压测数据前置（造数）：经网关 HTTP 真实链路预置 ≥50 任务/会话/记忆行，避免空集偏差。
 *
 * 用法：`E2E_GATEWAY=http://127.0.0.1:18880 node scripts/perf/seed-data.mjs [总数]`
 * - 复用 scripts/e2e/lib/harness.mjs（本地出票+网关客户端）；端口段由 E2E_GATEWAY/E2E_ENGINE 环境变量指向 188xx。
 * - 造数全部走业务 API（真实授权链），无直连写库；requestId 前缀 r14_ 保证可重复执行幂等。
 * - 造数内容：
 *   1. AGENT 任务 50 条（ADMIN 受理，真实运行链产生任务事件）；
 *   2. PLAN 任务 10 条 × 每条 3 个计划修订（修订读取基准的数据面）；
 *   3. 会话 60 条（demo-user 创建，每会话 1 轮次经模型桩出话产生会话事件）；
 *   4. 记忆 60 条（demo-user PRIVATE，一半含检索关键词"星历"）。
 * - 产物：seed-manifest.json（id 清单+计数+起止时间）落 evidence 目录，供压测脚本消费。
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import {
  REPO_ROOT,
  loadEnv,
  client,
  identities,
  runId,
  GATEWAY,
  DEMO_APP,
} from '../e2e/lib/harness.mjs';

const EVIDENCE_DIR = path.join(REPO_ROOT, 'docs', 'evidence', '2026-11-19-R-14');
const AGENT_TASKS = Number(process.argv[2] || 50);
const PLAN_TASKS = 10;
const SESSIONS = 60;
const MEMORIES = 60;
const CONCURRENCY = 3;

async function pool(items, worker, size = CONCURRENCY) {
  const results = [];
  let i = 0;
  async function lane() {
    while (i < items.length) {
      const idx = i++;
      results[idx] = await worker(items[idx], idx);
    }
  }
  await Promise.all(Array.from({ length: Math.min(size, items.length) }, lane));
  return results;
}

async function main() {
  const env = await loadEnv();
  const ids = identities(env);
  const admin = client(ids.ADMIN);
  const user = client(ids.USER);
  await fs.mkdir(EVIDENCE_DIR, { recursive: true });
  const errors = [];
  const startedAt = new Date().toISOString();
  console.log(`== R-14 造数开始：网关=${GATEWAY} app=${DEMO_APP}`);
  console.log(`   目标：AGENT 任务 ${AGENT_TASKS} / PLAN 任务 ${PLAN_TASKS}(各3修订) / 会话 ${SESSIONS} / 记忆 ${MEMORIES}`);

  // 0. 模型路由幂等注册（demo-provider/demo-model → 本地桩 18885；与 M5-3 run-all 同款）
  const stubBase = 'http://127.0.0.1:18885';
  let providers = (await admin.get('/admin/model-providers')).data || [];
  let provider = Array.isArray(providers) ? providers.find((p) => p.code === 'demo-provider') : null;
  if (!provider) {
    const created = await admin.post('/admin/model-providers', {
      code: 'demo-provider', name: '演示供应商(R-14压测本地桩)', baseUrl: stubBase,
      trustLabels: ['R14_LOCAL_STUB'], credential: { secret: 'r14-stub-not-a-real-key' },
    });
    if (created.status !== 200) throw new Error('demo-provider 注册失败: ' + JSON.stringify(created).slice(0, 300));
    provider = created.data;
  }
  const test = await admin.post(`/admin/model-providers/${provider.id}/test`);
  if (!test.data?.ok) throw new Error('demo-provider 连通测试未过（模型桩是否在跑？）: ' + JSON.stringify(test.data));
  let entries = (await admin.get('/admin/model-entries')).data || [];
  let entry = Array.isArray(entries) ? entries.find((e) => e.modelName === 'demo-model') : null;
  if (!entry) {
    const created = await admin.post('/admin/model-entries', {
      modelName: 'demo-model', providerId: provider.id, displayName: '演示模型(R-14桩)',
      contextWindow: 8192, maxOutputTokens: 2048, capabilities: ['chat'],
    });
    if (created.status !== 200) throw new Error('demo-model 条目注册失败: ' + JSON.stringify(created).slice(0, 300));
    entry = created.data;
  }
  console.log('   模型路由就绪：demo-provider/demo-model → 本地桩 18885');

  // 1. AGENT 任务（真实受理+运行链；goal 带序号避免 request 语义重复）
  const seedTag = 'r14_' + runId();
  console.log('   [1/4] AGENT 任务造数...');
  const taskIds = (
    await pool(Array.from({ length: AGENT_TASKS }, (_, k) => k), async (k) => {
      const r = await admin.post(`/v1/apps/${DEMO_APP}/tasks`, {
        requestId: `${seedTag}_t${k}`, mode: 'AGENT',
        goal: `R-14 压测造数任务 ${k}：给出一句结论即可。`, inputs: {},
      });
      if (r.status !== 200 || !r.data?.taskId) { errors.push({ kind: 'task', k, status: r.status, msg: r.message }); return null; }
      return r.data.taskId;
    })
  ).filter(Boolean);
  console.log(`   AGENT 任务成功 ${taskIds.length}/${AGENT_TASKS}`);

  // 2. PLAN 任务 × 3 修订（修订读取基准数据面；不确认不执行，保持 confirmableBy=me 非空供 counts 基准）
  console.log('   [2/4] PLAN 任务+计划修订造数...');
  const step = (id, deps) => ({ stepId: id, kind: 'MODEL', targetRef: 'model:demo-model', dependsOn: deps, inputBindings: {} });
  const planTasks = (
    await pool(Array.from({ length: PLAN_TASKS }, (_, k) => k), async (k) => {
      const r = await admin.post(`/v1/apps/${DEMO_APP}/tasks`, {
        requestId: `${seedTag}_p${k}`, mode: 'PLAN',
        goal: `R-14 压测造数规划任务 ${k}：两步模型计划。`, inputs: {},
      });
      if (r.status !== 200 || !r.data?.taskId) { errors.push({ kind: 'plan-task', k, status: r.status, msg: r.message }); return null; }
      const tid = r.data.taskId;
      const planId = `p-${seedTag}-${k}`;
      const revisions = [];
      for (const [base, steps] of [[0, [step('s1', []), step('s2', ['s1'])]], [1, [step('s1', []), step('s2', ['s1']), step('s3', ['s2'])]], [2, [step('s1', []), step('s2', ['s1'])]]]) {
        const rev = await admin.post(`/v1/tasks/${tid}/plans`, { planId, baseRevision: base, patch: { steps } });
        if (rev.status !== 200) { errors.push({ kind: 'plan-rev', k, base, status: rev.status, msg: rev.message }); break; }
        revisions.push(rev.data?.revision);
      }
      return { taskId: tid, planId, revisions };
    }, 2)
  ).filter(Boolean);
  console.log(`   PLAN 任务成功 ${planTasks.length}/${PLAN_TASKS}（各 3 修订）`);

  // 3. 会话 × 1 轮次（真实 harness 链：用户消息→模型桩出话→会话事件）
  console.log('   [3/4] 会话+轮次造数（经模型桩真实出话）...');
  const sessions = (
    await pool(Array.from({ length: SESSIONS }, (_, k) => k), async (k) => {
      const s = await user.post(`/v1/apps/${DEMO_APP}/sessions`, {});
      if (s.status !== 200 || !s.data?.sessionId) { errors.push({ kind: 'session', k, status: s.status, msg: s.message }); return null; }
      const sid = s.data.sessionId;
      const turn = await user.post(`/v1/apps/${DEMO_APP}/sessions/${sid}/turns`, {
        requestId: `${seedTag}_s${k}`, inputs: { message: `R-14 压测造数轮次 ${k}：请确认已受理。` },
      });
      if (turn.status !== 200) { errors.push({ kind: 'turn', k, sid, status: turn.status, msg: turn.message }); }
      const detail = await user.get(`/v1/apps/${DEMO_APP}/sessions/${sid}`);
      const watermark = Number(detail.data?.eventWatermark || 0);
      return { sessionId: sid, eventWatermark: watermark };
    }, 2)
  ).filter(Boolean);
  console.log(`   会话成功 ${sessions.length}/${SESSIONS}（均含 1 轮次事件）`);

  // 4. 记忆（demo-user PRIVATE；一半含"星历"供 q 检索基准）
  console.log('   [4/4] 记忆造数...');
  const memoryIds = (
    await pool(Array.from({ length: MEMORIES }, (_, k) => k), async (k) => {
      const withKeyword = k % 2 === 0;
      const content = withKeyword
        ? `R-14 压测造数经验 ${k}：星历年龄超过14天时窗口评分显著下降，规划前先核对星历时效（条目 ${k}）。`
        : `R-14 压测造数偏好 ${k}：输出偏好简明观测窗口表，云量上限默认30%（条目 ${k}）。`;
      const r = await user.post(`/v1/apps/${DEMO_APP}/memories`, {
        requestId: `${seedTag}_m${k}`, content,
        kind: withKeyword ? 'EXPERIENCE' : 'PREFERENCE', scope: 'PRIVATE',
        tags: ['压测', withKeyword ? '星历' : '偏好'],
      });
      if (r.status !== 200 || !r.data?.memoryId) { errors.push({ kind: 'memory', k, status: r.status, msg: r.message }); return null; }
      return r.data.memoryId;
    }, 5)
  ).filter(Boolean);
  console.log(`   记忆成功 ${memoryIds.length}/${MEMORIES}`);

  const manifest = {
    gateway: GATEWAY,
    demoApp: DEMO_APP,
    seedTag,
    startedAt,
    finishedAt: new Date().toISOString(),
    counts: {
      agentTasks: taskIds.length, planTasks: planTasks.length,
      planRevisions: planTasks.reduce((a, t) => a + t.revisions.length, 0),
      sessions: sessions.length,
      sessionEventWatermarkSum: sessions.reduce((a, s) => a + s.eventWatermark, 0),
      memories: memoryIds.length,
      errors: errors.length,
    },
    taskIds, planTasks,
    sessionIds: sessions.map((s) => s.sessionId),
    sessionWatermarks: Object.fromEntries(sessions.map((s) => [s.sessionId, s.eventWatermark])),
    memoryIds,
    errors,
  };
  const out = path.join(EVIDENCE_DIR, 'seed-manifest.json');
  await fs.writeFile(out, JSON.stringify(manifest, null, 2));
  console.log(`== 造数完成：${JSON.stringify(manifest.counts)}`);
  console.log(`   清单落盘：${out}`);
  if (errors.length) { console.error(`存在 ${errors.length} 条造数错误（详见 manifest.errors）`); process.exitCode = 1; }
}

main().catch((e) => { console.error(e); process.exit(1); });
