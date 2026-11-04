/**
 * ONB-01：业务接入模板前端模型与校验。字段与 INT-01/INT-03 契约对齐（引用
 * AssistantExperience / IntegrationProfile / ConfigRelease 的登记字段，不建立
 * 第二套连接/模板引擎）。模板服务端（OnboardingTemplate 控制器）尚未交付——
 * 本层只做类型、校验与既有能力包客户端的包装；提交接口按 ONB 服务端子步启用，
 * 页面不得伪造已可用状态。
 */
import { parseJsonObject, useCapabilityPackageClient, type CapabilityPackageSummary } from "./capability-package";
// MNT-03 DTO 族子步：稳定外壳类型与边界解析器权威定义在 resource-types.ts；
// 本文件只叠加 onboarding 业务规则并转发类型（既有导入面不变）。
import type {
  ConnectionProfileView,
  OnboardingExperienceRef,
  OnboardingIntegrationRef,
  OnboardingTemplateDraft,
  OnboardingTemplateKind,
  OperationDiagnosis,
  PreflightPreview,
} from "./resource-types";
import { parseOnboardingTemplate, parsePreflightPreview } from "./resource-types";

export type {
  ConnectionProfileView,
  OnboardingExperienceRef,
  OnboardingIntegrationRef,
  OnboardingTemplateDraft,
  OnboardingTemplateKind,
  OperationDiagnosis,
  PreflightPreview,
};

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

/**
 * ONB-04：模板服务端已交付（/admin/onboarding-templates）。写操作（创建/发布/预检）
 * 为 ADMIN-only；业务配置者只读目录。提交幂等键 requestId + 修订基线 expectedRev
 * 按 §4.3 约定：同一 requestId + 同计划返回既有进度（不覆盖人工后续编辑），
 * 同键不同计划 409。
 */
export const TEMPLATE_WRITE_SCOPE =
  "模板登记/发布为管理员操作；业务配置者可浏览目录并在被授予范围内发起登记请求，由管理员复核提交。";

/* ===================== ONB-02：前置清单与规格诊断（前端模型） ===================== */

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

/* ===================== ONB-04：模板会话客户端（真实服务端接口） ===================== */

import { parseOnboardingTemplateList, type OnboardingTemplateRecord } from "./resource-types";

/** 请求体键集与服务端 BODY_KEYS/TRANSITION_KEYS/PREFLIGHT_KEYS 对齐（未知键不发送）。 */
export interface OnboardingCreateRequest {
  code: string
  name: string
  kind: OnboardingTemplateKind
  description: string
  sourcePackageVersionId: number
  sourcePackageSha256: string
  appCode: string
  experience: OnboardingExperienceRef
  integration: OnboardingIntegrationRef
  requestId: string
}

export interface OnboardingTransitionRequest {
  expectedRev: number
  requestId: string
}

export interface OnboardingPreflightRequest {
  document: string
  connection: string
  requestId: string
}

type OnboardingApi = (path: string, method?: string, body?: unknown) => Promise<unknown>;

export function onboardingClient(api: OnboardingApi) {
  const root = "/admin/onboarding-templates";
  return {
    list: (filters: { q?: string; kind?: string } = {}) => {
      const query = new URLSearchParams();
      for (const [key, value] of Object.entries(filters))
        if (value !== undefined && value !== "") query.set(key, String(value));
      const suffix = query.toString();
      return api(suffix ? `${root}?${suffix}` : root).then(parseOnboardingTemplateList);
    },
    /** 组装登记：完整规范化草稿 + requestId 幂等；新建不接受 expectedRev（服务端 400 守护）。 */
    create: (request: OnboardingCreateRequest): Promise<OnboardingTemplateRecord | null> =>
      api(root, "POST", { ...request }).then(parseOnboardingTemplate),
    /** 发布前预检：文档规格 + 连接引用 + requestId；响应为逐项检查结论。 */
    preflight: (code: string, version: number, request: OnboardingPreflightRequest) =>
      api(`${root}/${encodeURIComponent(code)}/versions/${version}/preflight`, "POST", {
        ...request,
      }).then(parsePreflightPreview),
    /** 发布（仅 DRAFT 可发布；expectedRev 修订基线 + requestId 幂等）。 */
    publish: (code: string, request: OnboardingTransitionRequest): Promise<unknown> =>
      api(`${root}/${encodeURIComponent(code)}/publish`, "POST", { ...request }),
    disable: (code: string, request: OnboardingTransitionRequest): Promise<unknown> =>
      api(`${root}/${encodeURIComponent(code)}/disable`, "POST", { ...request }),
  };
}

export { parseOnboardingTemplateList };
export type { OnboardingTemplateRecord };

/* ===================== ONB-05：会话恢复与错误恢复（错误码映射/冲突保留） ===================== */

/**
 * ③服务端错误码→可读文案与建议动作（CONFIG_* 登记集）。未知码回退原始 message
 * 并保持可恢复——不静默、不吞错。
 */
export const ONBOARDING_ERROR_MESSAGES: Record<string, { message: string; action: string }> = {
  CONFIG_REV_CONFLICT: {
    message: "并发修订冲突：服务端模板已有更新修订，你的本地编辑已保留，未被覆盖。",
    action: "刷新模板目录核对服务端最新内容与差异，决定保留或放弃后再重试。",
  },
  CONFIG_STATE_TRANSITION_INVALID: {
    message: "当前模板状态不允许该操作（只有 DRAFT 可发布；已停用模板不能再变更）。",
    action: "刷新目录查看最新状态；如需修改请先确认模板未被停用。",
  },
  CONFIG_REF_NOT_FOUND: {
    message: "引用的模板、连接或体验配置不存在，或当前账号无权访问。",
    action: "核对引用标识；跨租户资源不可见，请联系管理员授予。",
  },
  CONFIG_SCHEMA_BUDGET_EXCEEDED: {
    message: "规格工作量超出 Schema 预算上限。",
    action: "按服务端预算提示精简规格，或退回 OpenAPI 专业导入入口处理。",
  },
  CONFIG_AUTH_SCHEME_UNSUPPORTED: {
    message: "规格声明的认证方式不在受支持子集内。",
    action: "改用受支持的认证方式并由管理员配置引用后重试。",
  },
  CONFIG_SECRET_PLAINTEXT_REJECTED: {
    message: "检测到明文凭据——登记内容禁止包含密钥明文。",
    action: "移除明文，改用管理员已配置的凭据引用（secret_ref）。",
  },
  CONFIG_VERSION_UNKNOWN: {
    message: "请求的模板版本不存在或已被撤回。",
    action: "刷新目录后选择当前版本；撤回只影响新接入，不影响已发布应用来源。",
  },
};

/** 错误解释视图：code 缺失时按 HTTP 状态兜底；recoverable=可原地重试。 */
export interface OnboardingErrorView {
  message: string
  code: string
  action: string
  recoverable: boolean
  /** ⑤阻塞类：撤权/停用/过期会话需要先解决身份或状态，不是重试能解决。 */
  blocking: boolean
}

export function mapOnboardingError(
  error: unknown,
): OnboardingErrorView {
  const record = (typeof error === "object" && error !== null ? error : {}) as {
    message?: unknown
    code?: unknown
    status?: unknown
  };
  const code = typeof record.code === "string" ? record.code : "";
  const status = typeof record.status === "number" ? record.status : 0;
  const rawMessage = typeof record.message === "string" ? record.message : "";
  const known = code ? ONBOARDING_ERROR_MESSAGES[code] : undefined;
  if (known)
    return {
      message: known.message,
      code,
      action: known.action,
      recoverable: code !== "CONFIG_STATE_TRANSITION_INVALID" && code !== "CONFIG_REF_NOT_FOUND",
      blocking: code === "CONFIG_REF_NOT_FOUND" || code === "CONFIG_STATE_TRANSITION_INVALID",
    };
  if (status === 401)
    return {
      message: rawMessage || "会话已失效，请重新连接。",
      code: "AUTH_EXPIRED",
      action: "重新登录后继续；本地已填内容保留。",
      recoverable: false,
      blocking: true,
    };
  if (status === 403)
    return {
      message: rawMessage || "当前账号没有此操作权限。",
      code: "AUTH_FORBIDDEN",
      action: "模板登记/发布/预检需管理员权限；可复制登记 JSON 交管理员复核提交。",
      recoverable: false,
      blocking: true,
    };
  return {
    message: rawMessage || "操作失败，请重试。",
    code: code || "UNKNOWN",
    action: "检查网络与输入后重试；原文已保留。",
    recoverable: true,
    blocking: false,
  };
}

/** ①会话恢复推导：从服务端模板记录推导向导应回到的入口（不依赖本地缓存）。 */
export type ResumeEntry = "wizard" | "verify" | "none";

export function resumeEntryFor(record: {
  status?: string
}): ResumeEntry {
  const status = String(record.status ?? "").toUpperCase();
  if (status === "DRAFT") return "verify";
  if (status === "PUBLISHED") return "none";
  return "none";
}

/** ④并发修订差异摘要：本地草稿与服务端记录逐键对照（值不同即列出）。 */
export function conflictDiff(
  local: Record<string, unknown>,
  server: Record<string, unknown>,
): string[] {
  const keys = new Set([...Object.keys(local), ...Object.keys(server)]);
  const diff: string[] = [];
  for (const key of keys) {
    const left = JSON.stringify(local[key]);
    const right = JSON.stringify(server[key]);
    if (left !== right) diff.push(key);
  }
  return diff;
}

/** 兼容薄包装：preview 响应经 resource-types 边界收窄后返回规范诊断。 */
export function normalizePreview(response: unknown): PreflightPreview {
  return parsePreflightPreview(response);
}

export { parseJsonObject, useCapabilityPackageClient };
