// R-25 验收：发布链重放播种——注册 demo-provider/demo-model → 给 demo 应用 draft config 补
// modelBinding.modelEntryId → precheck/commit 真实发布（attachModels 冻结解析生成 modelRefs）
// → 新激活快照断言（modelEntryId>0 + modelRefs 非空）→ CHAT 会话轮次真实出话 → DB run SUCCEEDED。
// 用法：先 bash scripts/perf/start-stack-r24.sh 起栈（189xx 段）；然后
//   E2E_GATEWAY=http://127.0.0.1:18980 E2E_MODEL_STUB_PORT=18985 node scripts/perf/verify-r25-model-chain.mjs
import { execFileSync } from "node:child_process";
import { writeFileSync, mkdirSync } from "node:fs";
import { loadEnv, identities, client } from "../e2e/lib/harness.mjs";

const GATEWAY = process.env.E2E_GATEWAY || "http://127.0.0.1:18980";
const TENANT = "demo";
const APP = "satellite-planning-assistant";
const OUT = "docs/evidence/2026-11-19-R-25";
const PSQL = ".tools/postgresql-16.15/pgsql/bin/psql.exe";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const env = await loadEnv();
const admin = client(identities(env).ADMIN, { base: GATEWAY });
const user = client(identities(env).USER, { base: GATEWAY });
const pg = (sql) =>
  execFileSync(PSQL, ["-h", DB_HOST, "-p", DB_PORT, "-U", env.AGENT_DB_USER, "-d", DB_NAME, "-At", "-c", sql],
    { encoding: "utf8", env: { ...process.env, PGPASSWORD: env.AGENT_DB_PASSWORD } }).trim();

/** 模型注册（照 e2e run-all.ensureModelRoute 同构；返回 entryId）。 */
async function ensureModelEntry() {
  const base = `http://127.0.0.1:${process.env.E2E_MODEL_STUB_PORT || 18985}`;
  const providers = (await admin.get("/admin/model-providers")).data || [];
  let provider = Array.isArray(providers) ? providers.find((p) => p.code === "demo-provider") : null;
  if (!provider) {
    const created = await admin.post("/admin/model-providers", {
      code: "demo-provider", name: "演示供应商(R25本地桩)", baseUrl: base,
      trustLabels: ["R25_LOCAL_STUB"], credential: { secret: "r25-stub-not-a-real-key" },
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
      modelName: "demo-model", providerId: provider.id, displayName: "演示模型(R25桩)",
      contextWindow: 8192, maxOutputTokens: 2048, capabilities: ["chat"],
    });
    if (created.status !== 200) throw new Error("demo-model 条目注册失败: " + JSON.stringify(created).slice(0, 300));
    entry = created.data;
  }
  console.log(`模型链就绪：demo-provider#${provider.id}（status=${test.data.status ?? "OK"}）；demo-model entryId=${entry.id}`);
  return Number(entry.id);
}

async function main() {
  mkdirSync(new URL(`../../${OUT}/`, import.meta.url), { recursive: true });
  const entryId = await ensureModelEntry();

  // 1) 读草稿（draftRev+config），补 modelBinding.modelEntryId。
  const draft = await admin.get(`/admin/flows/${APP}`);
  if (draft.status !== 200) throw new Error("草稿读取失败: " + draft.status + " " + draft.message);
  const draftRev = Number(draft.data?.draftRev);
  const config = draft.data?.config ?? {};
  if (!config.modelBinding) throw new Error("草稿 config 无 modelBinding（种子形状异常）");
  if (Number(config.modelBinding.modelEntryId) === entryId)
    console.log("草稿已含正确 modelEntryId，跳过保存");
  else {
    config.modelBinding = { ...config.modelBinding, modelEntryId: entryId };
    const saved = await admin.put(`/admin/apps/${APP}/config`, { draftRev, config });
    if (saved.status !== 200)
      throw new Error("config 保存失败: " + saved.status + " " + JSON.stringify(saved).slice(0, 400));
    console.log(`草稿已补 modelEntryId=${entryId}（draftRev ${draftRev}→${saved.data?.draftRev ?? draftRev + 1}）`);
  }

  // 2) 真实发布链：precheck → commit。
  const pre = await admin.post(`/admin/apps/${APP}/publish/precheck`, {});
  if (pre.status !== 200) throw new Error("precheck 失败: " + pre.status + " " + JSON.stringify(pre).slice(0, 500));
  if (!pre.data?.passed) throw new Error("precheck 未过: " + JSON.stringify(pre.data?.report ?? pre.data).slice(0, 600));
  console.log(`precheck PASS：precheckId=${pre.data.precheckId} candidateSha256=${String(pre.data.candidateSha256).slice(0, 12)}… gate=${pre.data.gateMode} target=${pre.data.publicationTarget} envConfirm=${pre.data.environmentConfirmationRequired}`);
  const commitBody = {
    idempotencyRequestNo: "r25-" + crypto.randomUUID().slice(0, 8),
    precheckId: pre.data.precheckId,
    draftRev: pre.data.draftRev,
    candidateSha256: pre.data.candidateSha256,
  };
  // 非生产目标+演示来源评测 → 显式确认当前预检/报告/来源摘要（AppPublicationService:478-485）。
  if (pre.data.environmentConfirmationRequired) {
    commitBody.evaluationEnvironmentConfirmation = {
      confirmed: true,
      precheckId: pre.data.precheckId,
      evalReportId: pre.data.evalReportId,
      evidenceSha256: pre.data.environmentEvidenceSha256,
    };
  }
  const committed = await admin.post(`/admin/apps/${APP}/publish/commit`, commitBody);
  if (committed.status !== 200)
    throw new Error("commit 失败: " + committed.status + " " + JSON.stringify(committed).slice(0, 500));
  const versionNo = Number(committed.data?.versionNo ?? committed.data?.appVersionNo ?? 0);
  console.log(`commit 成功：versionNo=${versionNo || "(见快照核验)"}`);

  // 3) 新激活快照断言：config.modelBinding.modelEntryId>0 且 dependencyVersions.modelRefs 非空。
  const versions = await admin.get(`/admin/apps/${APP}/versions`);
  const list = versions.data?.versions ?? versions.data ?? [];
  const latestNo = versionNo || (Array.isArray(list) ? Math.max(...list.map((v) => Number(v.versionNo ?? v.version ?? 0))) : 0);
  const snapshot = await admin.get(`/admin/apps/${APP}/versions/${latestNo}`);
  const snap = snapshot.data ?? {};
  const snapConfig = snap.config ?? snap.snapshot?.config ?? {};
  const refs = snap.dependencyVersions?.modelRefs ?? snap.snapshot?.dependencyVersions?.modelRefs ?? [];
  const entryOk = Number(snapConfig.modelBinding?.modelEntryId) === entryId;
  console.log(`新快照核验：versionNo=${latestNo} modelEntryId>${0}=${entryOk} modelRefs=${refs.length}`);
  if (!entryOk || refs.length === 0)
    throw new Error("新快照缺冻结模型引用: modelEntryIdOk=" + entryOk + " modelRefs=" + refs.length);

  // 4) CHAT 会话轮次真实出话（新快照装配链）→ DB run SUCCEEDED。
  const s = await user.post(`/v1/apps/${APP}/sessions`, {});
  if (s.status !== 200 || !s.data?.sessionId) throw new Error("会话创建失败: " + s.status + " " + s.message);
  const sid = s.data.sessionId;
  const turn = await user.post(`/v1/apps/${APP}/sessions/${sid}/turns`, {
    requestId: "r25-turn-" + Date.now(), inputs: { message: "R-25 冻结模型引用验证：请用一句话确认已受理。" },
  });
  if (turn.status !== 200) throw new Error("轮次受理失败: " + turn.status + " " + turn.message);
  console.log(`会话轮次受理：${sid}（轮询 run 终态…）`);

  const deadline = Date.now() + 5 * 60 * 1000;
  let status = "", stopReason = "";
  while (Date.now() < deadline) {
    const rows = pg(`select status,coalesce(stop_reason,'') from engine.run where app_code='${APP}' order by id desc limit 1`)
      .replace(/\r$/, "");
    [status, stopReason] = rows.split("|");
    if (["SUCCEEDED", "FAILED", "CANCELLED"].includes(status)) break;
    await sleep(4000);
  }
  const mismatchInDb = Number(pg("select count(*) from engine.run_event where payload::text like '%PROMPT_TEMPLATE_SNAPSHOT_MISMATCH%'"));
  const latest = pg(`select status,coalesce(stop_reason,''),public_id from engine.run where app_code='${APP}' order by id desc limit 1`).replace(/\r$/, "");
  const report = {
    at: new Date().toISOString(), gateway: GATEWAY, app: APP, entryId,
    precheck: { id: pre.data.precheckId, passed: pre.data.passed, gate: pre.data.gateMode },
    commit: { status: committed.status, versionNo: latestNo },
    snapshot: { modelEntryIdOk: entryOk, modelRefCount: refs.length },
    chatTurn: { sessionId: sid, runStatus: status, stopReason },
    promptMismatchEvents: mismatchInDb,
    latestRun: latest,
  };
  writeFileSync(new URL(`../../${OUT}/r25-model-chain-result.json`, import.meta.url), JSON.stringify(report, null, 2));
  console.log(`run 终态=${status} stopReason=${stopReason || "-"} 装配409=${mismatchInDb}`);
  console.log("报告已写 " + OUT + "/r25-model-chain-result.json");

  if (mismatchInDb > 0) throw new Error("存在 PROMPT_TEMPLATE_SNAPSHOT_MISMATCH——不应发生（R-24 已修）");
  if (status !== "SUCCEEDED")
    throw new Error(`run 终态=${status}（stopReason=${stopReason}）——冻结模型引用链未闭环`);
  console.log("R-25 端到端验收：发布链重放播种 modelRefs → CHAT 轮次真实出话 SUCCEEDED（PASS）");
}

main().catch((e) => { console.error("FAIL:", e.message); process.exit(1); });
