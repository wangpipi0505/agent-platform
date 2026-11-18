/** M5-3 E2E 单命令入口：`node scripts/e2e/run-all.mjs`
 *
 * 前置：五服务栈已在 184xx 起好（`bash scripts/e2e/start-stack.sh`，见 README），专用库
 * m53_e2e 已建（全链迁移+V901 种子由各服务启动时 Flyway 自举）。
 * 行为：起本地模型桩（真实出话链）→ 幂等注册 demo-provider/demo-model → 顺序执行 8 场景 →
 * 控制台逐断言 PASS/FAIL + 场景汇总 + JSON 报告落盘 → 存在失败即非零退出。
 * 重复运行安全：各场景以 runId 生成新实体，互不依赖历史状态；证据目录可多次覆盖。
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { REPO_ROOT, loadEnv, client, identities, GATEWAY, MODEL_STUB_PORT } from './lib/harness.mjs';
import * as s1 from './scenarios/s1-direct-task.mjs';
import * as s2 from './scenarios/s2-plan-task.mjs';
import * as s3 from './scenarios/s3-memory-domain.mjs';
import * as s4 from './scenarios/s4-plugin-discovery.mjs';
import * as s5 from './scenarios/s5-mcp-connection.mjs';
import * as s6 from './scenarios/s6-confirmation-center.mjs';
import * as s7 from './scenarios/s7-integration-service.mjs';
import * as s8 from './scenarios/s8-role-matrix.mjs';

const SCENARIOS = [s1, s2, s3, s4, s5, s6, s7, s8];
const EVIDENCE_DIR = process.env.E2E_EVIDENCE_DIR || path.join(REPO_ROOT, 'docs', 'evidence', '2026-11-18-M5-3');

async function waitForGateway() {
  for (let i = 0; i < 10; i++) {
    try {
      const r = await fetch(GATEWAY + '/actuator/health', { signal: AbortSignal.timeout(3000) });
      if (r.status === 200 || r.status === 503) return true;
    } catch { /* retry */ }
    await new Promise((r) => setTimeout(r, 1000));
  }
  return false;
}

/** 幂等注册 demo-provider/demo-model 指向本地桩（存在即复用；供 S1/S2 真实出话链）。 */
async function ensureModelRoute(env, stubProc) {
  const admin = client(identities(env).ADMIN);
  const base = `http://127.0.0.1:${MODEL_STUB_PORT}`;
  let provider = null;
  const providers = (await admin.get('/admin/model-providers')).data || [];
  provider = Array.isArray(providers) ? providers.find((p) => p.code === 'demo-provider') : null;
  if (!provider) {
    const created = await admin.post('/admin/model-providers', {
      code: 'demo-provider', name: '演示供应商(E2E本地桩)', baseUrl: base,
      trustLabels: ['E2E_LOCAL_STUB'], credential: { secret: 'e2e-stub-not-a-real-key' },
    });
    if (created.status !== 200) throw new Error('demo-provider 注册失败: ' + JSON.stringify(created).slice(0, 300));
    provider = created.data;
  }
  const test = await admin.post(`/admin/model-providers/${provider.id}/test`);
  if (!test.data?.ok) throw new Error('demo-provider 连通测试未过（模型桩是否在跑？）: ' + JSON.stringify(test.data));
  let entry = null;
  const entries = (await admin.get('/admin/model-entries')).data || [];
  entry = Array.isArray(entries) ? entries.find((e) => e.modelName === 'demo-model') : null;
  if (!entry) {
    const created = await admin.post('/admin/model-entries', {
      modelName: 'demo-model', providerId: provider.id, displayName: '演示模型(E2E桩)',
      contextWindow: 8192, maxOutputTokens: 2048, capabilities: ['chat'],
    });
    if (created.status !== 200) throw new Error('demo-model 条目注册失败: ' + JSON.stringify(created).slice(0, 300));
    entry = created.data;
  }
  return { providerId: provider.id, entryId: entry.id };
}

async function main() {
  const env = await loadEnv();
  const startedAt = new Date().toISOString();
  console.log('== M5-3 API 级 E2E（全部经网关 ' + GATEWAY + '；计划子链经引擎直连，见 README 登记） ==');

  if (!(await waitForGateway())) {
    console.error('网关未就绪：请先 `bash scripts/e2e/start-stack.sh` 起栈（详见 scripts/e2e/README.md）');
    process.exit(2);
  }

  // 本地模型桩：真实出话链（engine→model-gateway→provider HTTP），进程随本脚本退出
  const stub = spawn(process.execPath, [path.join(import.meta.dirname, 'fixtures', 'model-stub.mjs'), String(MODEL_STUB_PORT)], { stdio: ['ignore', 'pipe', 'pipe'] });
  let stubOut = '';
  stub.stdout.on('data', (d) => (stubOut += d));
  stub.stderr.on('data', (d) => (stubOut += d));
  try {
    await new Promise((resolve, reject) => {
      const t = setTimeout(() => reject(new Error('模型桩启动超时: ' + stubOut)), 8000);
      stub.stdout.once('data', () => { clearTimeout(t); resolve(); });
      stub.once('exit', () => { clearTimeout(t); reject(new Error('模型桩提前退出: ' + stubOut)); });
    });
    const route = await ensureModelRoute(env, stub);
    console.log(`模型链就绪：demo-provider#${route.providerId} → 本地桩:${MODEL_STUB_PORT}；demo-model#${route.entryId}`);
  } catch (e) {
    console.error('模型路由准备失败：' + e.message);
    stub.kill('SIGTERM');
    process.exit(2);
  }

  const records = [];
  const results = [];
  for (const scenario of SCENARIOS) {
    console.log(`\n-- ${scenario.meta.id} ${scenario.meta.name} --`);
    const sceneStarted = Date.now();
    let status = 'PASS', failed = 0, total = 0, error = null;
    const before = records.length;
    try {
      await scenario.run({ env, records });
    } catch (e) {
      status = 'FAIL';
      error = String(e.message || e);
      console.error(`  [场景异常] ${error}`);
    }
    const slice = records.slice(before);
    failed = slice.filter((r) => !r.ok).length + (error ? 1 : 0);
    total = slice.length + (error ? 1 : 0);
    if (failed > 0) status = 'FAIL';
    console.log(`== ${scenario.meta.id} ${status}（断言 ${total - failed}/${total}，${Date.now() - sceneStarted}ms） ==`);
    results.push({ id: scenario.meta.id, name: scenario.meta.name, status, assertions: { total, failed }, error });
  }

  stub.kill('SIGTERM');

  const passed = results.filter((r) => r.status === 'PASS').length;
  const summary = {
    tool: 'M5-3 场景转 E2E（API 级，经网关）',
    startedAt, finishedAt: new Date().toISOString(),
    stack: { gateway: GATEWAY, engine: 'http://127.0.0.1:18482', database: 'm53_e2e@127.0.0.1:25432' },
    totals: { scenarios: results.length, passed, failed: results.length - passed, assertions: records.length, assertionFailures: records.filter((r) => !r.ok).length },
    scenarios: results,
    records,
  };
  try {
    await fs.mkdir(EVIDENCE_DIR, { recursive: true });
    const reportPath = path.join(EVIDENCE_DIR, 'm5-3-e2e-report.json');
    await fs.writeFile(reportPath, JSON.stringify(summary, null, 2));
    console.log(`\nJSON 报告：${reportPath}`);
  } catch (e) {
    const fallback = path.join(REPO_ROOT, '.local', 'm53-e2e-report.json');
    await fs.writeFile(fallback, JSON.stringify(summary, null, 2));
    console.log(`\n证据目录不可写（${e.message}），报告落 ${fallback}`);
  }

  console.log('\n===== M5-3 E2E 场景汇总 =====');
  for (const r of results) console.log(`${r.status === 'PASS' ? 'PASS' : 'FAIL'}  ${r.id}  ${r.name}（断言 ${r.assertions.total - r.assertions.failed}/${r.assertions.total}）${r.error ? ' —— ' + r.error : ''}`);
  console.log(`合计：场景 ${passed}/${results.length} PASS；断言 ${summary.totals.assertions - summary.totals.assertionFailures}/${summary.totals.assertions}`);
  process.exit(results.length - passed > 0 ? 1 : 0);
}

main().catch((e) => { console.error(e); process.exit(2); });
