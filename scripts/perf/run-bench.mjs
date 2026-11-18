/** R-14 M5-4 性能基准：七类基准经网关 HTTP（真实链路）逐类 ≥200 次采样，样本落 JSON，
 * P50/P95/P99 用线性插值分位（与 numpy 默认 linear 法同式）。
 *
 * 用法：`E2E_GATEWAY=http://127.0.0.1:18880 node scripts/perf/run-bench.mjs`
 * 前置：R-14 栈已起（scripts/perf/start-stack.sh）且 seed-data.mjs 造数完成。
 *
 * 基准集（每类串行采样，单客户端顺序请求；另含并发隔离观察轮见报告）：
 *   1 task-list           GET /v1/tasks?appCode=..&page=1&pageSize=20          (ADMIN)
 *   2 task-detail         GET /v1/tasks/{publicId}                             (ADMIN, 轮换 50 条)
 *   3 session-events-full GET /v1/apps/{code}/sessions/{id}/events?afterSeq=0  (USER, 轮换会话)
 *   4 session-events-inc  同上 afterSeq=watermark-2（增量尾页，2 条事件）        (USER, 轮换会话)
 *   5 memory-list         GET /v1/apps/{code}/memories?page=1&pageSize=20      (USER)
 *   6 memory-search       GET /v1/apps/{code}/memories?q=星历                  (USER)
 *   7 plan-revision-read  GET /v1/tasks/{tid}/plans/{pid}/revisions/{rev}      (ADMIN, 轮换 30 修订)
 *   8 confirmation-counts GET /admin/confirmation-center/counts                (ADMIN)
 *   9 sse-first-frame     GET /v1/apps/{code}/sessions/{id}/events/stream      (USER, Last-Event-ID=0,
 *                          首帧时延=连接建立→首字节帧；轮换 6 会话)
 *
 * 采样口径：performance.now() 计时覆盖完整 fetch（连接→响应头→响应体读毕）；
 * 每类前 20 次预热不计入样本（JIT/连接池预热，报告中如实登记）；失败请求单独计数不剔除不重试。
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import {
  REPO_ROOT,
  loadEnv,
  client,
  identities,
  GATEWAY,
  DEMO_APP,
} from '../e2e/lib/harness.mjs';

const EVIDENCE_DIR = path.join(REPO_ROOT, 'docs', 'evidence', '2026-11-19-R-14');
const SAMPLES_PER_BENCH = Number(process.env.R14_SAMPLES || 240);
const WARMUP = 20;
const SEED = path.join(EVIDENCE_DIR, 'seed-manifest.json');

/** 线性插值分位（Type-7 / numpy 'linear'）：h=(n-1)q，h 整数取 x[h]，否则相邻两点线性内插。 */
export function percentile(sorted, q) {
  if (sorted.length === 0) return null;
  const h = (sorted.length - 1) * q;
  const lo = Math.floor(h), hi = Math.ceil(h);
  return lo === hi ? sorted[lo] : sorted[lo] + (h - lo) * (sorted[hi] - sorted[lo]);
}

export function summarize(samples, failures, label, endpoint, identity) {
  const sorted = [...samples].sort((a, b) => a - b);
  const n = sorted.length;
  const mean = n ? samples.reduce((a, b) => a + b, 0) / n : null;
  return {
    label, endpoint, identity,
    sampleCount: n, failureCount: failures,
    min: n ? sorted[0] : null,
    p50: n ? percentile(sorted, 0.5) : null,
    p95: n ? percentile(sorted, 0.95) : null,
    p99: n ? percentile(sorted, 0.99) : null,
    max: n ? sorted[n - 1] : null,
    mean,
    unit: 'ms',
  };
}

async function bench(label, endpoint, identity, call, { samples: count = SAMPLES_PER_BENCH } = {}) {
  const samples = [];
  let failures = 0;
  const failureDetails = [];
  for (let i = 0; i < count + WARMUP; i++) {
    const t0 = performance.now();
    let ok = true;
    let detail = '';
    try {
      ok = await call(i);
    } catch (e) {
      ok = false;
      detail = String(e && e.message || e).slice(0, 200);
    }
    const ms = performance.now() - t0;
    if (i >= WARMUP) samples.push(ms);
    if (!ok) {
      failures++;
      if (failureDetails.length < 5) failureDetails.push({ at: i, ms: Math.round(ms), detail });
    }
  }
  const sorted = [...samples].sort((a, b) => a - b);
  return { label, endpoint, identity, samples: sorted, failures, failureDetails, summary: summarize(samples, failures, label, endpoint, identity) };
}

async function sseFirstFrame(url, token) {
  const r = await fetch(url, {
    headers: { Authorization: 'Bearer ' + token, Accept: 'text/event-stream', 'Last-Event-ID': '0' },
    signal: AbortSignal.timeout(20000),
  });
  if (!r.ok || !r.body) { await r.text().catch(() => {}); throw new Error('SSE status ' + r.status); }
  const reader = r.body.getReader();
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) throw new Error('SSE closed before first frame');
      if (value && value.length) return; // 首帧字节即达
    }
  } finally {
    reader.cancel().catch(() => {});
  }
}

async function main() {
  const env = await loadEnv();
  const ids = identities(env);
  const admin = client(ids.ADMIN);
  const user = client(ids.USER);
  const manifest = JSON.parse(await fs.readFile(SEED, 'utf8'));
  const startedAt = new Date().toISOString();
  console.log(`== R-14 压测开始：网关=${GATEWAY} 每类采样=${SAMPLES_PER_BENCH}（另预热 ${WARMUP} 不计入）`);

  const rotate = (arr, i) => arr[i % arr.length];
  const results = [];

  // 1 任务列表（B4-1 分页面）
  results.push(await bench('task-list', 'GET /v1/tasks?appCode=..&page=1&pageSize=20', 'ADMIN', async () => {
    const r = await admin.get(`/v1/tasks?appCode=${DEMO_APP}&page=1&pageSize=20`, 15000);
    return r.status === 200 && Array.isArray(r.data?.tasks || r.data?.items || r.data);
  }));

  // 2 任务详情（双形态业务号路径，轮换 50 条）
  results.push(await bench('task-detail', 'GET /v1/tasks/{publicId}', 'ADMIN', async (i) => {
    const r = await admin.get(`/v1/tasks/${rotate(manifest.taskIds, i)}`, 15000);
    return r.status === 200 && r.data?.taskId !== undefined;
  }));

  // 3 会话事件增量——首页全量（afterSeq=0）
  results.push(await bench('session-events-full', 'GET /v1/apps/{code}/sessions/{id}/events?afterSeq=0&limit=200', 'USER', async (i) => {
    const sid = rotate(manifest.sessionIds, i);
    const r = await user.get(`/v1/apps/${DEMO_APP}/sessions/${sid}/events?afterSeq=0&limit=200`, 15000);
    return r.status === 200 && Array.isArray(r.data?.events);
  }));

  // 4 会话事件增量——尾部增量页（afterSeq=watermark-2，约 2 条事件）
  const wm = manifest.sessionWatermarks || {};
  results.push(await bench('session-events-inc', 'GET /v1/apps/{code}/sessions/{id}/events?afterSeq={wm-2}', 'USER', async (i) => {
    const sid = rotate(manifest.sessionIds, i);
    const after = Math.max(0, (wm[sid] || 0) - 2);
    const r = await user.get(`/v1/apps/${DEMO_APP}/sessions/${sid}/events?afterSeq=${after}&limit=200`, 15000);
    return r.status === 200 && Array.isArray(r.data?.events);
  }));

  // 5 记忆列表（分页；响应形状 {memories,page,pageSize,total}）
  results.push(await bench('memory-list', 'GET /v1/apps/{code}/memories?page=1&pageSize=20', 'USER', async () => {
    const r = await user.get(`/v1/apps/${DEMO_APP}/memories?page=1&pageSize=20`, 15000);
    return r.status === 200 && Array.isArray(r.data?.memories);
  }));

  // 6 记忆检索（q ILIKE 关键词，命中约半数种子）
  results.push(await bench('memory-search', 'GET /v1/apps/{code}/memories?q=星历', 'USER', async () => {
    const r = await user.get(`/v1/apps/${DEMO_APP}/memories?q=${encodeURIComponent('星历')}`, 15000);
    return r.status === 200 && Array.isArray(r.data?.memories);
  }));

  // 7 计划修订读取（轮换 30 修订）
  const revs = manifest.planTasks.flatMap((t) => t.revisions.map((rev) => ({ taskId: t.taskId, planId: t.planId, rev })));
  results.push(await bench('plan-revision-read', 'GET /v1/tasks/{tid}/plans/{pid}/revisions/{rev}', 'ADMIN', async (i) => {
    const t = rotate(revs, i);
    const r = await admin.get(`/v1/tasks/${t.taskId}/plans/${t.planId}/revisions/${t.rev}`, 15000);
    return r.status === 200 && r.data?.revision !== undefined;
  }));

  // 8 确认中心 counts
  results.push(await bench('confirmation-counts', 'GET /admin/confirmation-center/counts', 'ADMIN', async () => {
    const r = await admin.get('/admin/confirmation-center/counts', 15000);
    return r.status === 200 && r.data !== undefined;
  }));

  // 9 SSE 首帧时延（Last-Event-ID=0 有积压会话，首字节帧即达；轮换 6 会话）
  {
    const sseSessions = manifest.sessionIds.slice(0, 6);
    const samples = [];
    let failures = 0;
    const failureDetails = [];
    for (let i = 0; i < SAMPLES_PER_BENCH + WARMUP; i++) {
      const sid = rotate(sseSessions, i);
      const t0 = performance.now();
      let ok = true, detail = '';
      try {
        await sseFirstFrame(`${GATEWAY}/v1/apps/${DEMO_APP}/sessions/${sid}/events/stream`, ids.USER);
      } catch (e) {
        ok = false;
        detail = String(e && e.message || e).slice(0, 200);
      }
      const ms = performance.now() - t0;
      if (i >= WARMUP) samples.push(ms);
      if (!ok) {
        failures++;
        if (failureDetails.length < 5) failureDetails.push({ at: i, ms: Math.round(ms), detail });
      }
    }
    const sorted = [...samples].sort((a, b) => a - b);
    results.push({
      label: 'sse-first-frame', endpoint: 'GET /v1/apps/{code}/sessions/{id}/events/stream (Last-Event-ID=0)', identity: 'USER',
      samples: sorted, failures, failureDetails,
      summary: summarize(samples, failures, 'sse-first-frame', 'GET /v1/apps/{code}/sessions/{id}/events/stream', 'USER'),
    });
  }

  // 并发隔离观察轮：4 路并发 × 25 轮 task-detail（v1 §9.2 并发隔离口径的机制级观察）
  const concSamples = [];
  let concFail = 0;
  for (let round = 0; round < 25; round++) {
    const batch = Array.from({ length: 4 }, (_, w) => {
      const t0 = performance.now();
      return admin.get(`/v1/tasks/${manifest.taskIds[(round * 4 + w) % manifest.taskIds.length]}`, 15000)
        .then((r) => { if (r.status !== 200) concFail++; return performance.now() - t0; })
        .catch(() => { concFail++; return performance.now() - t0; });
    });
    concSamples.push(...await Promise.all(batch));
  }
  const concSorted = [...concSamples].sort((a, b) => a - b);
  results.push({
    label: 'task-detail-conc4', endpoint: 'GET /v1/tasks/{publicId} ×4 并发', identity: 'ADMIN×4',
    samples: concSorted, failures: concFail, failureDetails: [],
    summary: summarize(concSamples, concFail, 'task-detail-conc4', 'GET /v1/tasks/{publicId} ×4 并发', 'ADMIN×4'),
    note: '并发隔离观察轮：4 路并发各 25 轮，无预热剔除（负载口径不同于串行基准，仅供对照）',
  });

  // 环境规格快照（PowerShell 输出按 GBK 解码——Windows OEM 代码页，TextDecoder 全 ICU 支持）
  const osSpec = (await (async () => {
    try {
      const { execSync } = await import('node:child_process');
      const buf = execSync('powershell -NoProfile -Command "(Get-CimInstance Win32_OperatingSystem).Caption + \' | \' + (Get-CimInstance Win32_Processor).Name + \' | \' + [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory/1GB,1) + \'GB\'"', { encoding: 'buffer', timeout: 30000 });
      return new TextDecoder('gbk').decode(buf).trim();
    } catch { return process.platform; }
  })());
  const envSpec = {
    recordedAt: new Date().toISOString(),
    gateway: GATEWAY,
    ports: { gateway: 18880, app: 18881, engine: 18882, skill: 18883, model: 18884, modelStub: 18885, pg: 25432 },
    database: 'r14_perf（专用库，全链迁移+V901 种子；PostgreSQL 16.15，max_connections=100，shared_buffers=160MB，work_mem=4MB）',
    os: osSpec,
    node: process.version,
    java: 'JDK 21.0.12.8 (Eclipse Adoptium hotspot)；五服务 JVM -Xms64m -Xmx512m 单副本本地栈',
    clientMode: 'Node 24 undici fetch 串行单客户端（并发隔离观察轮除外）；计时含连接+响应体读毕',
    percentile: '线性插值 Type-7（numpy linear 同式）',
    seedCounts: manifest.counts,
    samplesPerBench: SAMPLES_PER_BENCH,
    warmupExcluded: WARMUP,
    benchStartedAt: startedAt,
    benchFinishedAt: new Date().toISOString(),
  };

  const report = { envSpec, summaries: results.map((r) => r.summary) };
  const samplesOut = path.join(EVIDENCE_DIR, 'perf-samples.json');
  const reportOut = path.join(EVIDENCE_DIR, 'perf-report.json');
  await fs.writeFile(samplesOut, JSON.stringify(results, null, 2));
  await fs.writeFile(reportOut, JSON.stringify(report, null, 2));

  console.log('\n== 基准汇总（ms；线性插值分位）');
  console.log('label                 n    fail      min     P50     P95     P99     max');
  for (const s of report.summaries) {
    const f = (v) => (v == null ? '  -' : v.toFixed(1).padStart(7));
    console.log(
      s.label.padEnd(21) + String(s.sampleCount).padStart(5) + String(s.failureCount).padStart(6)
      + f(s.min) + f(s.p50) + f(s.p95) + f(s.p99) + f(s.max));
  }
  console.log(`\n样本落盘：${samplesOut}`);
  console.log(`汇总落盘：${reportOut}`);
  if (results.some((r) => r.failures > 0)) {
    console.error('存在失败请求（详见 perf-samples.json failureDetails）——报告将如实登记');
    process.exitCode = 1;
  }
}

main().catch((e) => { console.error(e); process.exit(1); });
