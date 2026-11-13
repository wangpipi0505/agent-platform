/**
 * MNT-03（onboarding DTO 族子步）：onboarding 模板/连接档案/诊断结果的稳定
 * 外壳类型与边界收窄解析器。权威定义在本文件；onboarding.ts 引用并叠加业务
 * 规则。未知网络输入一律经 parse* 收窄（不用 `as` / `@ts-ignore` 代替解析）；
 * 动态 inputs/output/config 保持 unknown 受校验值。既有协议事实保持：
 * 顶层 artifactId、controlledCallId 正整数 number、无工具预期空串、false/0/null、
 * 数组根与现有错误信封均按原语义处理，不在本层改写。
 */

/* ---------- 模板种类与登记草稿 ---------- */

export type OnboardingTemplateKind = "QUERY" | "FILE_ANALYSIS" | "CONTROLLED_WRITE";

export interface OnboardingExperienceRef {
  appCode: string;
  experienceRev: number;
}

export interface OnboardingIntegrationRef {
  bizSystemCode: string;
  environment: string;
  mode: string;
}

export interface OnboardingTemplateDraft {
  code: string;
  name: string;
  kind: OnboardingTemplateKind;
  description: string;
  /** 来源能力包版本 id（不可变精确身份，由向导从被授予目录中选择）。 */
  sourcePackageVersionId: number | null;
  /** 来源能力包内容 SHA256（登记时核对，防止目录内容漂移）。 */
  sourcePackageSha256: string;
  appCode: string;
  experience: OnboardingExperienceRef;
  integration: OnboardingIntegrationRef;
}

/* ---------- 连接档案（GET /admin/connection-profiles 投影） ---------- */

export interface ConnectionProfileView {
  profileId: string;
  name: string;
  bizSystemCode: string;
  environment: string;
  status: string;
}

/* ---------- 规格诊断（preview 规范化） ---------- */

export interface OperationDiagnosis {
  operationId: string;
  method: string;
  path: string;
  supported: boolean;
  requiresAuth: boolean;
  reasons: string[];
}

export interface PreflightPreview {
  operations: OperationDiagnosis[];
  warnings: string[];
  documentSha256: string;
}

/* ---------- 接入模板记录（GET /admin/onboarding-templates 投影，ONB-04） ---------- */

export interface OnboardingTemplateRecord {
  code: string;
  name: string;
  kind: string;
  status: string;
  currentRev: number;
  updatedAt: string;
}

/** 未知输入 → 模板记录外壳；缺 code 的行丢弃，状态/修订给确定值。 */
export function parseOnboardingTemplate(value: unknown): OnboardingTemplateRecord | null {
  if (!isRecord(value)) return null;
  const code = asString(value.code);
  if (!code) return null;
  const revision = asInt(value.currentRev);
  return {
    code,
    name: asString(value.name) || code,
    kind: asString(value.kind) || "QUERY",
    status: asString(value.status) || "DRAFT",
    currentRev: revision > 0 ? revision : 1,
    updatedAt: asString(value.updatedAt),
  };
}

export function parseOnboardingTemplateList(value: unknown): OnboardingTemplateRecord[] {
  if (!Array.isArray(value)) return [];
  return value
    .map(parseOnboardingTemplate)
    .filter((row): row is OnboardingTemplateRecord => row !== null);
}

/* ---------- 边界收窄工具（无 as / 无 @ts-ignore） ---------- */

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const asString = (value: unknown): string => (typeof value === "string" ? value : "");
const asInt = (value: unknown): number =>
  typeof value === "number" && Number.isSafeInteger(value) ? value : 0;

/** 未知输入 → 连接档案外壳；字段缺失给确定空值，多余键不进入类型面。 */
export function parseConnectionProfileView(value: unknown): ConnectionProfileView | null {
  if (!isRecord(value)) return null;
  const rawId = value.profileId;
  const profileId =
    typeof rawId === "string"
      ? rawId
      : typeof rawId === "number" && Number.isSafeInteger(rawId) && rawId > 0
        ? String(rawId)
        : "";
  if (!profileId) return null;
  return {
    profileId,
    name: asString(value.name),
    bizSystemCode: asString(value.bizSystemCode),
    environment: asString(value.environment),
    status: asString(value.status),
  };
}

export function parseConnectionProfileList(value: unknown): ConnectionProfileView[] {
  if (!Array.isArray(value)) return [];
  return value
    .map(parseConnectionProfileView)
    .filter((row): row is ConnectionProfileView => row !== null);
}

const isBoolean = (value: unknown): value is boolean => typeof value === "boolean";

/** 未知输入 → 逐操作诊断；diagnostics 逐条字符串化保留原文。 */
export function parseOperationDiagnosis(value: unknown): OperationDiagnosis | null {
  if (!isRecord(value)) return null;
  const operationId = asString(value.operationId);
  if (!operationId) return null;
  const diagnostics = Array.isArray(value.diagnostics) ? value.diagnostics : [];
  return {
    operationId,
    method: asString(value.method).toUpperCase(),
    path: asString(value.path),
    supported: value.supported === true,
    requiresAuth: isBoolean(value.requiresAuth) ? value.requiresAuth : false,
    reasons: diagnostics.map((item) =>
      typeof item === "string" ? item : JSON.stringify(item),
    ),
  };
}

/** 未知输入 → 诊断结果外壳（warnings 逐条字符串化；SHA 必须是字符串）。 */
export function parsePreflightPreview(value: unknown): PreflightPreview {
  const record = isRecord(value) ? value : {};
  const operations = Array.isArray(record.operations)
    ? record.operations
        .map(parseOperationDiagnosis)
        .filter((op): op is OperationDiagnosis => op !== null)
    : [];
  const warnings = Array.isArray(record.warnings)
    ? record.warnings.map((item) => (typeof item === "string" ? item : JSON.stringify(item)))
    : [];
  return {
    operations,
    warnings,
    documentSha256: asString(record.documentSha256),
  };
}

/**
 * 动态运行值（inputs/output/config）保持 unknown 受校验：仅确认顶层形状，
 * 不假装固定业务结构。数组根与 null 是合法值，false/0 原样保留。
 */
export function asUnknownRecord(value: unknown): Record<string, unknown> {
  return isRecord(value) ? value : {};
}

/** 未知运行值上安全取键：非对象返回 undefined，不抛错不臆造。 */
export function asRecordField(record: unknown, key: string): unknown {
  return isRecord(record) ? record[key] : undefined;
}

export function isPlainValue(value: unknown): boolean {
  return (
    value === null ||
    typeof value === "string" ||
    typeof value === "number" ||
    typeof value === "boolean"
  );
}
/* ===================== MNT-03（onboarding 之外的 DTO 族：应用/发布→运行→资源） =====================
 * 发布预检外壳已在 admin/src/publish-gate.ts（PLAT-02，PublishPrecheck）——优先引用，
 * 此处不重复定义。动态 inputs/output/config/Schema 保持 unknown 受校验值。
 */

/** 应用详情外壳（GET /admin/apps/{code} 工作区装配面）。 */
export interface AppDetail {
  appCode: string
  name: string
  type: string
  status: string
  draftRev: number
  currentVersionNo: number | null
  description: string
  domain: string
  bizCategory: string
  tags: string[]
  candidateSha256: string
  /** 动态声明保持 unknown：inputs/output/config 由运行时按声明校验。 */
  inputs?: unknown
  output?: unknown
  config?: unknown
}

const asStringOr = (value: unknown, fallback = ""): string =>
  typeof value === "string" ? value : fallback;

/** 空应用详情（加载前初始值；不臆造任何运行事实）。 */
export function emptyAppDetail(): AppDetail {
  return {
    appCode: "",
    name: "",
    type: "CHAT",
    status: "",
    draftRev: 0,
    currentVersionNo: null,
    description: "",
    domain: "",
    bizCategory: "",
    tags: [],
    candidateSha256: "",
  };
}

/** 未知输入 → 应用详情外壳；appCode 缺失返回 null。tags 非数组时不臆造。 */
export function parseAppDetail(value: unknown): AppDetail | null {
  if (!isRecord(value)) return null;
  const appCode = asString(value.appCode) || asString(value.code);
  if (!appCode) return null;
  const tags = Array.isArray(value.tags) ? value.tags.filter(isPlainValue) : [];
  const rev = asInt(value.draftRev);
  const versionNo = asInt(value.currentVersionNo);
  return {
    appCode,
    name: asStringOr(value.name, appCode),
    type: asStringOr(value.type, "CHAT"),
    status: asStringOr(value.status),
    draftRev: rev > 0 ? rev : 0,
    currentVersionNo: versionNo > 0 ? versionNo : null,
    description: asStringOr(value.description),
    domain: asStringOr(value.domain),
    bizCategory: asStringOr(value.bizCategory),
    tags: tags.map((tag) => String(tag)),
    candidateSha256: asString(value.candidateSha256),
    inputs: value.inputs,
    output: value.output,
    config: value.config,
  };
}

/** 评测引用外壳（评测中心/发布门禁核验面）。 */
export interface EvaluationSnapshot {
  status: string
  candidateSha256: string
  draftRev: number
  evalReportId: string
  evaluationId?: string
}

export function parseEvaluationSnapshot(value: unknown): EvaluationSnapshot | null {
  if (!isRecord(value)) return null;
  const reportId = asString(value.evalReportId);
  if (!reportId) return null;
  const rev = asInt(value.draftRev);
  return {
    status: asStringOr(value.status),
    candidateSha256: asString(value.candidateSha256),
    draftRev: rev > 0 ? rev : 0,
    evalReportId: reportId,
    evaluationId: asString(value.evaluationId) || undefined,
  };
}

/** 发布提交回执外壳（POST /admin/apps/{code}/publish/commit）。 */
export interface PublishedPointer {
  precheckId: string
  evalReportId: string
  candidateSha256: string
  draftRev: number
  versionNo: number | null
}

export function parsePublishedPointer(value: unknown): PublishedPointer | null {
  if (!isRecord(value)) return null;
  const precheckId = asString(value.precheckId);
  if (!precheckId) return null;
  const rev = asInt(value.draftRev);
  const versionNo = asInt(value.versionNo);
  return {
    precheckId,
    evalReportId: asString(value.evalReportId),
    candidateSha256: asString(value.candidateSha256),
    draftRev: rev > 0 ? rev : 0,
    versionNo: versionNo > 0 ? versionNo : null,
  };
}

/** 运行状态节点行（状态接口 nodes[]；output/inputs 保持 unknown）。 */
export interface RunNodeStatusRow {
  nodeDefId: string
  id: string
  status: string
  output?: unknown
  /** F2-3：节点输入快照（与输出独立展示）；服务端可能缺省，缺省显示无输入记录。 */
  input?: unknown
  /** F2-3：失败语义码（诊断锚点按 error/errorSemantic 非空定位失败节点）。 */
  errorSemantic?: string
  /** 时间线字段：服务端可能缺省；缺省显示 —，不推算。 */
  started_at?: string
  ended_at?: string
  error?: unknown
}

/** 运行状态快照外壳：decodeRunStatus（run-protocol）解析后的稳定面。
 * output/inputs/graph/observation 保持 unknown；错误信封按原语义。 */
export interface RunStatusSnapshot {
  /** 仅服务端顶层目标处置；动态形状由server-authority边界解码，模型output不参与。 */
  goals?: unknown;
  runId: string
  status: string
  eventsWatermark: string
  nodes: RunNodeStatusRow[]
  output?: unknown
  inputs?: unknown
  graph?: unknown
  diagnostics?: unknown
  observation?: unknown
  error?: unknown
  fileRefs?: unknown
  events?: unknown[]
}

/** 空快照（加载初始值；不臆造任何运行事实）。 */
export function emptyRunStatus(): RunStatusSnapshot {
  return {
    runId: "",
    status: "",
    eventsWatermark: "",
    nodes: [],
    events: [],
  };
}

/** 逐节点收窄：nodeDefId 缺失回退 id；两者皆无丢弃该行（不臆造节点）。 */
export function parseRunNodes(value: unknown): RunNodeStatusRow[] {
  if (!Array.isArray(value)) return [];
  const rows: RunNodeStatusRow[] = [];
  for (const item of value) {
    if (!isRecord(item)) continue;
    const id = asString(item.id);
    const nodeDefId = asString(item.nodeDefId) || id;
    if (!nodeDefId && !id) continue;
    rows.push({
      nodeDefId,
      id: id || nodeDefId,
      status: asStringOr(item.status),
      output: item.output,
      input: item.input,
      errorSemantic: typeof item.errorSemantic === "string" ? item.errorSemantic : undefined,
      started_at: typeof item.started_at === "string" ? item.started_at : undefined,
      ended_at: typeof item.ended_at === "string" ? item.ended_at : undefined,
      error: item.error,
    });
  }
  return rows;
}

/** 运行状态信封外壳：code/message/traceId + 收窄后的 data（错误信封按原语义保留）。 */
export interface RunStatusEnvelope {
  code: string
  message?: string
  traceId?: string
  data: RunStatusSnapshot
}

export function parseRunStatusEnvelope(value: unknown): RunStatusEnvelope {
  const record = isRecord(value) ? value : {};
  // 协议兼容：信封未知键（如 rawData/扩展头）原样保留，仅已知键收窄覆盖。
  return {
    ...record,
    code: asStringOr(record.code),
    message: typeof record.message === "string" ? record.message : undefined,
    traceId: typeof record.traceId === "string" ? record.traceId : undefined,
    data: parseRunStatusSnapshot(record.data),
  };
}

/** 未知输入 → 运行状态快照；eventsWatermark 缺失给空串（游标精度由 run-protocol 权威校验）。 */
export function parseRunStatusSnapshot(value: unknown): RunStatusSnapshot {
  const record = isRecord(value) ? value : {};
  // 协议兼容：data 未知键原样保留（调用方可继续读取扩展字段），已知键收窄覆盖。
  // 无 events 键时不创建空数组——Object.hasOwn(data,'events') 语义由服务端事实决定。
  const out: RunStatusSnapshot = {
    ...record,
    runId: asString(record.runId),
    status: asStringOr(record.status),
    eventsWatermark: asString(record.eventsWatermark),
    nodes: parseRunNodes(record.nodes),
    output: record.output,
    inputs: record.inputs,
    graph: record.graph,
    diagnostics: record.diagnostics,
    observation: record.observation,
    error: record.error,
    fileRefs: record.fileRefs,
  };
  if (Array.isArray(record.events)) out.events = record.events;
  return out;
}

/** 资源版本行外壳（技能/模板 versions[]；definition/content 动态）。 */
export interface ResourceVersionInfo {
  versionNo: number
  /** 服务端历史行可能只带 version 字段（与 versionNo 二选一）。 */
  version?: number
  status: string
  createdAt: string
  content?: string
  definition?: unknown
  /** 部分行平铺合同字段（旧数据形态）；透传不臆造。 */
  inputSchema?: unknown
  outputSchema?: unknown
  evalReportId?: string
  type?: string
  execution?: unknown
  guard?: unknown
}

export function parseResourceVersion(value: unknown): ResourceVersionInfo | null {
  if (!isRecord(value)) return null;
  const versionNo = asInt(value.versionNo);
  if (versionNo <= 0) return null;
  const flatVersion = asInt(value.version);
  return {
    versionNo,
    version: flatVersion > 0 ? flatVersion : undefined,
    status: asStringOr(value.status, "DRAFT"),
    createdAt: asStringOr(value.createdAt),
    content: typeof value.content === "string" ? value.content : undefined,
    definition: value.definition,
    inputSchema: value.inputSchema,
    outputSchema: value.outputSchema,
    evalReportId: typeof value.evalReportId === "string" ? value.evalReportId : undefined,
    type: typeof value.type === "string" ? value.type : undefined,
    execution: value.execution,
    guard: value.guard,
  };
}
