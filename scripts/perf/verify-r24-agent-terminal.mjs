// R-24 端到端验收（缺陷载体=会话轮次链，r14_perf 实证 run 全 CHAT/FAILED；AGENT 任务派发接续
// 停受理态属 T2-5 已登记缺口非本卡范围）：r24_check 库（V901 已落）上经真实网关对
// satellite-planning-assistant 发起 CHAT 会话+轮次，真实模型链出话至 run 终态；
// 断言无 PROMPT_TEMPLATE_SNAPSHOT_MISMATCH / NODE_FAILED，SUCCEEDED 在案。
// 另受理一条 AGENT 任务（DoD 字面），如实登记其停 ACCEPTED（T2-5 接续缺口口径）。
// 用法：先 bash scripts/perf/start-stack-r24.sh 起栈；然后
//   E2E_GATEWAY=http://127.0.0.1:18980 E2E_MODEL_STUB_PORT=18985 node scripts/perf/verify-r24-agent-terminal.mjs
import { writeFileSync, mkdirSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { loadEnv, identities, client } from "../e2e/lib/harness.mjs";

const GATEWAY = process.env.E2E_GATEWAY || "http://127.0.0.1:18980";
const TENANT = "demo";
const APP = "satellite-planning-assistant";
const OUT = "docs/evidence/2026-11-19-R-24";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const env = await loadEnv();
const admin = client(identities(env).ADMIN, { base: GATEWAY });
const user = client(identities(env).USER, { base: GATEWAY });

/** 模型路由注册（照 e2e run-all.ensureModelRoute 同构；demo-provider/demo-model → 本地桩）。 */
async function ensureModelRoute() {
  const base = `http://127.0.0.1:${process.env.E2E_MODEL_STUB_PORT || 18985}`;
  const providers = (await admin.get("/admin/model-providers")).data || [];
  let provider = Array.isArray(providers) ? providers.find((p) => p.code === "demo-provider") : null;
  if (!provider) {
    const created = await admin.post("/admin/model-providers", {
      code: "demo-provider", name: "演示供应商(R24本地桩)", baseUrl: base,
      trustLabels: ["R24_LOCAL_STUB"], credential: { secret: "r24-stub-not-a-real-key" },
    });
    if (created.status !== 200) throw new Error("demo-provider 注册失败: " + JSON.stringify(created).slice(0, 300));
    provider = created.data;
  }
  const test = await admin.post(`/admin/model-providers/${provider.id}/test`);
  if (!test.data?.ok) throw new Error("demo-provider 连通测试未过（模型桩在跑吗）: " + JSON.stringify(test.data));
  const entries = (await admin.get("/admin/model-entries")).data || [];
  let entry = Array.isArray(entries) ? entries.find((e) => e.modelName === "demo-model") : null;
  if (!entry) {
    const created = await admin.post("/admin/model-entries", {
      modelName: "demo-model", providerId: provider.id, displayName: "演示模型(R24桩)",
      contextWindow: 8192, maxOutputTokens: 2048, capabilities: ["chat"],
    });
    if (created.status !== 200) throw new Error("demo-model 条目注册失败: " + JSON.stringify(created).slice(0, 300));
    entry = created.data;
  }
  console.log(`模型链就绪：demo-provider#${provider.id} → 本地桩；demo-model#${entry.id}`);
}

/** CHAT 会话+轮次（V901 DIRECT config 装配路径）→ 轮询会话事件至轮次终态。 */
async function chatTurnToTerminal() {
  const s = await user.post(`/v1/apps/${APP}/sessions`, {});
  if (s.status !== 200 || !s.data?.sessionId) throw new Error("会话创建失败: " + s.status + " " + s.message);
  const sid = s.data.sessionId;
  const turn = await user.post(`/v1/apps/${APP}/sessions/${sid}/turns`, {
    requestId: "r24-turn-" + Date.now(), inputs: { message: "R-24 装配口径验证：请用一句话确认已受理并给出演示结论。" },
  });
  if (turn.status !== 200) throw new Error("轮次受理失败: " + turn.status + " " + turn.message);
  console.log(`会话轮次受理：${sid}`);

  const deadline = Date.now() + 5 * 60 * 1000;
  let events = [];
  const asEvents = (data) =>
    Array.isArray(data) ? data : Array.isArray(data?.events) ? data.events : Array.isArray(data?.items) ? data.items : [];
  while (Date.now() < deadline) {
    const ev = await user.get(`/v1/apps/${APP}/sessions/${sid}/events?afterSeq=0`);
    events = asEvents(ev.data);
    const dump = JSON.stringify(ev.data);
    const finished = dump.includes("turn/finished");
    const runTerminal = dump.includes('"status":"SUCCEEDED"') || dump.includes('"status":"FAILED"');
    if (finished || runTerminal) {
      return {
        sid,
        turnState: finished ? "FINISHED" : "RUN_TERMINAL",
        eventCount: events.length,
        mismatchCount: (dump.match(/PROMPT_TEMPLATE_SNAPSHOT_MISMATCH/g) || []).length,
        nodeFailedCount: (dump.match(/NODE_FAILED/g) || []).length,
        succeededFrame: dump.includes('"SUCCEEDED"'),
        failedFrame: dump.includes('"FAILED"'),
        tail: events.slice(-6),
      };
    }
    await sleep(4000);
  }
  const dump = JSON.stringify(events);
  return {
    sid, turnState: "TIMEOUT", eventCount: events.length,
    mismatchCount: (dump.match(/PROMPT_TEMPLATE_SNAPSHOT_MISMATCH/g) || []).length,
    nodeFailedCount: (dump.match(/NODE_FAILED/g) || []).length,
    succeededFrame: false, failedFrame: false, tail: events.slice(-6),
  };
}

const run = async () => {
  mkdirSync(new URL(`../../${OUT}/`, import.meta.url), { recursive: true });
  await ensureModelRoute();

  // DoD 字面载体：AGENT 任务受理（派发接续停 ACCEPTED=T2-5 已登记口径，如实留证）。
  const requestId = "r24-" + crypto.randomUUID().slice(0, 8);
  const created = await admin.post(`/v1/apps/${APP}/tasks`, {
    mode: "AGENT", requestId, goal: "R-24 装配口径验证：给出今天上午可观测窗口规划的一句话结论。", inputs: {},
  });
  if (created.status !== 200) throw new Error("AGENT 任务受理失败: " + JSON.stringify(created).slice(0, 300));
  const tid = created.data?.taskId;
  console.log(`AGENT 任务受理：${tid}（派发接续=T2-5 已登记缺口，预期停 ACCEPTED）`);
  await sleep(3000);
  const td = await admin.get(`/v1/tasks/${tid}`);
  const taskDetail = td.data ?? {};

  // 缺陷实际载体复验：CHAT 会话轮次真实出话至终态。
  const chat = await chatTurnToTerminal();
  const report = {
    at: new Date().toISOString(), gateway: GATEWAY, tenant: TENANT, app: APP,
    agentTask: { taskId: tid, requestId, stateAfterPoll: taskDetail.state ?? taskDetail.status, note: "T2-5 派发接续未交付（M5-3/R-14 已登记口径），非 R-24 范围" },
    chatTurn: {
      sessionId: chat.sid, turnState: chat.turnState, eventCount: chat.eventCount,
      promptMismatchCount: chat.mismatchCount, nodeFailedCount: chat.nodeFailedCount,
      succeededFrame: chat.succeededFrame, failedFrame: chat.failedFrame,
      tailEvents: chat.tail,
    },
  };
  writeFileSync(new URL(`../../${OUT}/r24-agent-terminal-result.json`, import.meta.url), JSON.stringify(report, null, 2));
  console.log(`轮次=${chat.turnState} 事件=${chat.eventCount} 装配409=${chat.mismatchCount} NODE_FAILED=${chat.nodeFailedCount} SUCCEEDED帧=${chat.succeededFrame}`);
  console.log("报告已写 " + OUT + "/r24-agent-terminal-result.json");

  if (chat.mismatchCount > 0) throw new Error("存在 PROMPT_TEMPLATE_SNAPSHOT_MISMATCH——R-24 验收失败");

  // DB 实证断言（会话事件投影过滤诊断帧，用户面事件流不承载 NODE_FAILED——以 run 表为准）：
  const PSQL = ".tools/postgresql-16.15/pgsql/bin/psql.exe";
  const pg = (sql) =>
    execFileSync(PSQL, ["-h", "127.0.0.1", "-p", "25432", "-U", env.AGENT_DB_USER, "-d", "r24_check", "-At", "-c", sql],
      { encoding: "utf8", env: { ...process.env, PGPASSWORD: env.AGENT_DB_PASSWORD } }).trim();
  const runs = pg("select status,coalesce(stop_reason,'') from engine.run order by id").split("\n").filter(Boolean);
  const mismatchInDb = Number(pg("select count(*) from engine.run_event where payload::text like '%PROMPT_TEMPLATE_SNAPSHOT_MISMATCH%'"));
  const nodeFailed = runs.filter((r) => r.split("|")[1] === "NODE_FAILED");
  console.log(`DB 实证：run 终态=${JSON.stringify(runs)} 装配409事件=${mismatchInDb} NODE_FAILED行=${nodeFailed.length}`);

  if (mismatchInDb > 0) throw new Error("DB 中存在 PROMPT_TEMPLATE_SNAPSHOT_MISMATCH——R-24 装配缺陷未消除");
  if (runs.length === 0) throw new Error("无 run 行——出话链未发生");
  if (nodeFailed.length > 0) {
    const reason = nodeFailed[0].split("|")[1];
    console.log(`R-24 装配缺陷已消除（409=0），但存在下一层缺口 NODE_FAILED(reason=${reason})——登记后续卡，非 promptSource 口径`);
    writeFileSync(new URL(`../../${OUT}/r24-next-gap.json`, import.meta.url),
      JSON.stringify({ nextGap: reason, runs, note: "promptSource 口径对齐已达成；完整出话终态被下一层缺口阻挡（MODEL_NOT_CONFIGURED=快照缺冻结模型引用），另登记" }, null, 2));
    process.exit(2);
  }
  if (!runs.some((r) => r.split("|")[0] === "SUCCEEDED")) throw new Error("未见 SUCCEEDED run——出话链未到终态");
  console.log("R-24 端到端验收：会话轮次真实出话 SUCCEEDED、无装配 409/NODE_FAILED（PASS）");
};

run().catch((e) => { console.error("FAIL:", e.message); process.exit(1); });
