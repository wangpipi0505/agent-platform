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

/* ---------- 边界收窄工具（无 as / 无 @ts-ignore） ---------- */

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const asString = (value: unknown): string => (typeof value === "string" ? value : "");

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

export function isPlainValue(value: unknown): boolean {
  return (
    value === null ||
    typeof value === "string" ||
    typeof value === "number" ||
    typeof value === "boolean"
  );
}
