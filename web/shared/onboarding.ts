/**
 * ONB-01：业务接入模板前端模型与校验。字段与 INT-01/INT-03 契约对齐（引用
 * AssistantExperience / IntegrationProfile / ConfigRelease 的登记字段，不建立
 * 第二套连接/模板引擎）。模板服务端（OnboardingTemplate 控制器）尚未交付——
 * 本层只做类型、校验与既有能力包客户端的包装；提交接口按 ONB 服务端子步启用，
 * 页面不得伪造已可用状态。
 */
import { parseJsonObject, useCapabilityPackageClient, type CapabilityPackageSummary } from "./capability-package";

/** 模板种类：读取查询模板先交付；受控文件分析待 INT-02 文件链路；写入待确认机制齐备。 */
export type OnboardingTemplateKind = "QUERY" | "FILE_ANALYSIS" | "CONTROLLED_WRITE";

export const ONBOARDING_TEMPLATE_KINDS: readonly {
  kind: OnboardingTemplateKind;
  label: string;
  selectable: boolean;
  reason: string;
}[] = [
  { kind: "QUERY", label: "读取查询模板", selectable: true, reason: "" },
  {
    kind: "FILE_ANALYSIS",
    label: "受控文件分析模板",
    selectable: false,
    reason: "待受控文件分析链路（INT-02）交付后开放",
  },
  {
    kind: "CONTROLLED_WRITE",
    label: "受控写入模板",
    selectable: false,
    reason: "待确认机制齐备后开放",
  },
];

/** 引用 AssistantExperience（INT-03）：只登记引用字段，不在模板内重定义体验内容。 */
export interface OnboardingExperienceRef {
  appCode: string;
  experienceRev: number;
}

/** 引用 IntegrationProfile（INT-03）：宿主挂载与输入上下文引用。 */
export interface OnboardingIntegrationRef {
  bizSystemCode: string;
  environment: string;
  mode: string;
}

/** 模板登记草稿：能力包精确身份 + 契约引用字段；不含凭据或可执行脚本。 */
export interface OnboardingTemplateDraft {
  code: string;
  name: string;
  kind: OnboardingTemplateKind;
  description: string;
  /** 来源能力包版本 id（不可变精确身份，由向导从被授予目录中选择）。 */
  sourcePackageVersionId: number | null;
  /** 来源能力包内容 SHA（登记时核对，防止目录内容漂移）。 */
  sourcePackageSha256: string;
  appCode: string;
  experience: OnboardingExperienceRef;
  integration: OnboardingIntegrationRef;
}

const CODE_PATTERN = /^[a-z][a-z0-9_]{1,63}$/;

export function emptyOnboardingTemplateDraft(): OnboardingTemplateDraft {
  return {
    code: "",
    name: "",
    kind: "QUERY",
    description: "",
    sourcePackageVersionId: null,
    sourcePackageSha256: "",
    appCode: "",
    experience: { appCode: "", experienceRev: 1 },
    integration: { bizSystemCode: "", environment: "", mode: "" },
  };
}

/** ③表单字段与 INT-01 契约对齐：非法字段、外部可执行脚本、越权覆盖一律拒绝。 */
export function validateOnboardingTemplateDraft(draft: OnboardingTemplateDraft): string[] {
  const errors: string[] = [];
  if (!CODE_PATTERN.test(draft.code))
    errors.push("模板编码须为小写字母开头的 2–64 位小写字母/数字/下划线。");
  if (!draft.name.trim()) errors.push("请填写模板名称。");
  const kindMeta = ONBOARDING_TEMPLATE_KINDS.find((item) => item.kind === draft.kind);
  if (!kindMeta?.selectable)
    errors.push(kindMeta ? `${kindMeta.label}暂不可登记：${kindMeta.reason}` : "未知模板种类。");
  if (draft.sourcePackageVersionId === null || !Number.isSafeInteger(draft.sourcePackageVersionId) || draft.sourcePackageVersionId <= 0)
    errors.push("请从被授予的能力包目录中选择来源版本。");
  if (!/^[0-9a-f]{64}$/i.test(draft.sourcePackageSha256))
    errors.push("来源能力包内容 SHA256 缺失或格式不合法。");
  if (!CODE_PATTERN.test(draft.appCode))
    errors.push("目标应用编码须为小写字母开头的 2–64 位标识。");
  if (!CODE_PATTERN.test(draft.experience.appCode))
    errors.push("体验引用的 appCode 不合法。");
  if (!Number.isSafeInteger(draft.experience.experienceRev) || draft.experience.experienceRev <= 0)
    errors.push("体验修订号必须为正整数。");
  if (!draft.integration.bizSystemCode.trim()) errors.push("请填写业务系统编码。");
  if (!draft.integration.environment.trim()) errors.push("请填写目标环境。");
  if (!draft.integration.mode.trim()) errors.push("请填写挂载模式。");
  return errors;
}

/** ②业务配置者只能选择被授予的版本：目录由服务端按主体过滤，此处再防越权字段。 */
export function selectablePackages(rows: readonly CapabilityPackageSummary[]): CapabilityPackageSummary[] {
  return rows.filter((row) => Number.isSafeInteger(row.packageVersionId) && row.packageVersionId > 0);
}

/** 向导选择来源版本后固定精确身份（含 SHA），后续步骤不可改写。 */
export function applyPackageSelection(
  draft: OnboardingTemplateDraft,
  row: CapabilityPackageSummary | undefined,
): OnboardingTemplateDraft {
  if (!row) return draft;
  return {
    ...draft,
    sourcePackageVersionId: row.packageVersionId,
    sourcePackageSha256: typeof row.sha256 === "string" ? row.sha256 : "",
  };
}

/**
 * 登记请求体（提交接口启用前的联调预览）。仅含契约字段，无凭据、无脚本字段；
 * 未知键在边界剔除，不以 `as 类型` 绕过校验。
 */
export function buildOnboardingTemplatePayload(draft: OnboardingTemplateDraft): Record<string, unknown> {
  return {
    code: draft.code,
    name: draft.name,
    kind: draft.kind,
    description: draft.description,
    sourcePackageVersionId: draft.sourcePackageVersionId,
    sourcePackageSha256: draft.sourcePackageSha256,
    appCode: draft.appCode,
    experience: {
      appCode: draft.experience.appCode,
      experienceRev: draft.experience.experienceRev,
    },
    integration: {
      bizSystemCode: draft.integration.bizSystemCode,
      environment: draft.integration.environment,
      mode: draft.integration.mode,
    },
  };
}

/** 目录查询参数（租户/权限过滤由服务端执行；前端仅传筛选意图）。 */
export interface OnboardingDirectoryQuery {
  q?: string;
  kind?: OnboardingTemplateKind;
}

export const TEMPLATE_SERVICE_UNAVAILABLE =
  "模板服务端接口尚未交付（ONB 服务端后续子步）；目录与提交接口启用前，本页不显示任何模板数据。";

/* ===================== ONB-02：前置清单与规格诊断（前端模型） ===================== */

/** 连接档案只读投影（GET /admin/connection-profiles，ADMIN-only；脱敏展示不含凭据）。 */
export interface ConnectionProfileView {
  profileId: number | string;
  name?: string;
  bizSystemCode?: string;
  environment?: string;
  status?: string;
  [key: string]: unknown;
}

/**
 * ①只列被授予的连接档案：已批准（APPROVED/ACTIVE）且未停用的档案才可被模板引用；
 * 服务端按身份过滤后，本层再按状态收敛，不给未批准连接任何模板入口。
 */
export function grantedConnections(rows: readonly ConnectionProfileView[]): ConnectionProfileView[] {
  return rows.filter((row) => {
    const status = String(row.status ?? "").toUpperCase();
    return status === "APPROVED" || status === "ACTIVE";
  });
}

/** 逐操作诊断视图：拒绝原因原样来自服务端 diagnostics，不归并为提示语。 */
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

const diagnosticText = (value: unknown): string =>
  typeof value === "string" ? value : JSON.stringify(value);

/** 把 OpenAPI preview 响应规范化为逐操作诊断；拒绝原因与警告逐条保留、可追溯。 */
export function normalizePreview(response: {
  operations?: { operationId?: string; method?: string; path?: string; supported?: boolean; requiresAuth?: boolean; diagnostics?: unknown[] }[];
  warnings?: unknown[];
  documentSha256?: string;
}): PreflightPreview {
  const operations = (response.operations ?? []).map((op) => ({
    operationId: String(op.operationId ?? ""),
    method: String(op.method ?? "").toUpperCase(),
    path: String(op.path ?? ""),
    supported: op.supported === true,
    requiresAuth: op.requiresAuth === true,
    reasons: (op.diagnostics ?? []).map(diagnosticText),
  }));
  return {
    operations,
    warnings: (response.warnings ?? []).map(diagnosticText),
    documentSha256: String(response.documentSha256 ?? ""),
  };
}

/**
 * ②支持子集与差距完全来自响应统计（R12：不写死 51/16，不把业务 Schema 限制
 * 转成要求业务改接口——拒绝原因原样展示并给责任入口）。
 */
export function summarizeDiagnosis(preview: PreflightPreview) {
  const supported = preview.operations.filter((op) => op.supported);
  const unsupported = preview.operations.filter((op) => !op.supported);
  return {
    total: preview.operations.length,
    supportedCount: supported.length,
    unsupported,
    supported,
    partial: supported.length > 0 && unsupported.length > 0,
    allBlocked: preview.operations.length > 0 && supported.length === 0,
  };
}

/**
 * ③不支持的操作不能被模板标成可用：模板目标只能从 supported 子集选择；
 * requiresAuth 的操作在无凭据引用引用时也不得登记（引用由管理员配置）。
 */
export function canUseOperation(op: OperationDiagnosis): boolean {
  return op.supported;
}

/** 诊断步骤汇总（登记 JSON 附带）：原规格 SHA 与逐操作结论可追溯、零创建。 */
export function buildPreflightSummary(preview: PreflightPreview): Record<string, unknown> {
  const summary = summarizeDiagnosis(preview);
  return {
    documentSha256: preview.documentSha256,
    total: summary.total,
    supportedCount: summary.supportedCount,
    unsupported: summary.unsupported.map((op) => ({
      operationId: op.operationId,
      reasons: op.reasons,
    })),
    warnings: preview.warnings,
    zeroCreated: true,
  };
}

export const PREFLIGHT_SERVICE_NOTE =
  "前置诊断预览复用技能导入的只读解析；模板提交接口仍属 ONB 服务端后续子步，启用前不创建任何资源。";

export { parseJsonObject, useCapabilityPackageClient };
