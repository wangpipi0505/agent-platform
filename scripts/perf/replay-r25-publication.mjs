import { readFile } from "node:fs/promises";
import { setTimeout as pause } from "node:timers/promises";

function required(name) {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`缺少 ${name}`);
  return value;
}

function integer(value, label, minimum) {
  const number = Number(value);
  if (!Number.isSafeInteger(number) || number < minimum) throw new Error(`${label} 无效`);
  return number;
}

async function main() {
  const gateway = new URL(required("E2E_GATEWAY"));
  const token = required("E2E_PORTAL_TOKEN");
  const app = required("E2E_APP_CODE");
  const entryId = integer(required("E2E_MODEL_ENTRY_ID"), "模型条目", 1);
  const priorVersion = integer(required("E2E_EXPECTED_VERSION"), "预期当前发布版本", 0);
  const publicationId = required("E2E_PUBLICATION_REQUEST_ID");
  const evaluation = JSON.parse(await readFile(required("E2E_EVAL_REQUEST_FILE"), "utf8"));
  if (!["http:", "https:"].includes(gateway.protocol) || gateway.username || gateway.password
      || gateway.pathname !== "/" || gateway.search || gateway.hash
      || !/^[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(app) || !/^[A-Za-z0-9_-]{1,128}$/.test(publicationId)
      || typeof evaluation?.datasetVersion !== "string" || !evaluation.datasetVersion.trim()
      || !Array.isArray(evaluation.cases) || !evaluation.cases.length) {
    throw new Error("网关、应用编码、发布请求号或评测数据集无效");
  }

  async function request(method, path, body) {
    const response = await fetch(new URL(path, gateway), {
      method,
      redirect: "error",
      signal: AbortSignal.timeout(120_000),
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const reply = await response.json().catch(() => null);
    if (!response.ok || reply?.code !== "OK" || !reply.data)
      throw new Error(`${method} ${path} HTTP ${response.status} ${reply?.code ?? ""}`);
    return reply.data;
  }

  const path = `/admin/apps/${app}`;
  let draft = await request("GET", path);
  if (Number(draft.currentVersionNo ?? 0) !== priorVersion || draft.type !== "CHAT")
    throw new Error("应用类型或当前发布版本与预期不符");
  if (!draft.config?.modelBinding || typeof draft.config.modelBinding !== "object"
      || Array.isArray(draft.config.modelBinding)) {
    throw new Error("草稿缺少模型绑定");
  }
  const binding = draft.config.modelBinding;
  if (Object.keys(binding).some((key) => !["modelEntryId", "providerCode", "modelName"].includes(key))
      || (draft.config.modelEntryId !== undefined && Number(draft.config.modelEntryId) !== entryId)) {
    throw new Error("草稿含额外模型绑定配置，请先核对绑定");
  }
  const entries = await request("GET", "/admin/model-entries");
  const model = Array.isArray(entries) && entries.find((entry) => Number(entry.id) === entryId);
  if (!model) throw new Error("当前租户未找到指定模型条目");
  const modelLimit = integer(model.maxOutputTokens, "模型输出上限", 1);
  const configuredLimit = draft.config.output?.maxTokens ?? draft.config.maxOutputTokens;
  if (configuredLimit !== undefined && integer(configuredLimit, "草稿输出上限", 1) > modelLimit)
    throw new Error("草稿输出上限超过指定模型能力");
  const requiresOutputLimit = configuredLimit === undefined && modelLimit < 2048;
  const legacyTurns = draft.config.maxTurns;
  if (Number(binding.modelEntryId) !== entryId || Object.keys(binding).length !== 1
      || legacyTurns !== undefined || requiresOutputLimit) {
    // The published CHAT contract binds by entry ID. V901 stores the older provider and model names.
    const config = { ...draft.config, modelBinding: { modelEntryId: entryId } };
    if (requiresOutputLimit) config.maxOutputTokens = modelLimit;
    if (legacyTurns !== undefined) {
      const turns = integer(legacyTurns, "会话轮数", 1);
      const context = config.context ?? {};
      if (turns > 200 || typeof context !== "object" || Array.isArray(context)
          || (context.maxTurns !== undefined && context.maxTurns !== turns)) {
        throw new Error("草稿中两处会话轮数配置冲突或超出范围");
      }
      config.context = { ...context, maxTurns: turns };
      delete config.maxTurns;
    }
    await request("PUT", `${path}/config`, { draftRev: draft.draftRev, config });
    draft = await request("GET", path);
  }
  if (Number(draft.currentVersionNo ?? 0) !== priorVersion
      || Number(draft.config.modelBinding.modelEntryId) !== entryId
      || !/^[0-9a-f]{64}$/.test(draft.candidateSha256)) {
    throw new Error("候选摘要或模型条目读回异常");
  }
  const candidate = { appCode: app, draftRev: draft.draftRev, candidateSha256: draft.candidateSha256 };
  let report = await request("POST", "/admin/evaluations", { ...evaluation, ...candidate });
  if (!/^er_[0-9a-f-]{36}$/.test(report.id)) throw new Error("评测报告编号无效");
  const reportPath = `/admin/evaluations/${report.id}`;
  for (let attempt = 0; report.status === "RUNNING" && attempt < 25; attempt++) {
    await pause(1200);
    report = await request("POST", `${reportPath}/resume`, candidate);
  }
  const verified = await request("GET", reportPath);
  if (verified.status !== "ACCEPTED" || verified.candidateSha256 !== candidate.candidateSha256)
    throw new Error(`评测报告 ${verified.id} 状态 ${verified.status}，候选匹配 ${verified.candidateSha256 === candidate.candidateSha256}`);
  const precheck = await request("POST", `${path}/publish/precheck`, {});
  if (!precheck.passed || precheck.evaluationEnvironment !== "REAL"
      || precheck.environmentConfirmationRequired || precheck.evalReportId !== verified.id
      || precheck.draftRev !== candidate.draftRev || precheck.candidateSha256 !== candidate.candidateSha256) {
    throw new Error("发布预检未确认同一候选的真实评测报告");
  }
  const publication = await request("POST", `${path}/publish/commit`, {
    precheckId: precheck.precheckId,
    draftRev: candidate.draftRev,
    candidateSha256: candidate.candidateSha256,
    idempotencyRequestNo: publicationId,
  });
  const version = integer(publication.versionNo, "发布版本", 1);
  const snapshot = await request("GET", `${path}/versions/${version}`);
  const refs = snapshot.dependencyVersions?.modelRefs;
  if (Number(snapshot.config?.modelBinding?.modelEntryId) !== entryId
      || !Array.isArray(refs)
      || !refs.some((ref) => Number(ref.modelEntryId) === entryId
        && Number(ref.entry?.id) === entryId
        && Number(ref.modelBindingRef?.bindingId) > 0
        && /^[0-9a-f]{64}$/.test(ref.modelBindingRef?.sha256))) {
    throw new Error("发布版本缺少匹配的冻结模型引用");
  }
  console.log(JSON.stringify({
    app, modelEntryId: entryId, version, reportId: verified.id, precheckId: precheck.precheckId,
    candidateSha256: candidate.candidateSha256, modelRefs: refs.length,
  }));
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
