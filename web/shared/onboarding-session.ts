import type { OnboardingTemplateRecord } from "./onboarding";
import type { FieldsBundle } from "./onboarding-fields";

export interface OnboardingConnectionRef {
  profileId: string; profileRev: number; bindingId: string; bindingRev: number;
  operation: string; environment?: string;
}
export interface OnboardingRoutePlan {
  environment: string;
  routeProfileId: string;
}
/** 仅接专业人员显式提供的原 OpenAPI 人工字段，不在浏览器生成安全或执行语义。 */
export interface OnboardingImportOverride {
  name: string;
  description: string;
  execution: Record<string, unknown>;
  guard: Record<string, unknown>;
  returnSpec: Record<string, unknown>;
  errorSemantics: unknown[];
  timeoutMs?: number;
  constraints?: Record<string, unknown>;
}
export interface OnboardingImportConfiguration {
  routePlan: OnboardingRoutePlan;
  importOverrides: Record<string, OnboardingImportOverride>;
}
export interface OnboardingSessionPlan {
  fieldsBundle: FieldsBundle;
  values: Record<string, unknown>;
  selectedOperations: string[];
  modelEntryId?: number;
  connectionRef?: OnboardingConnectionRef;
  sourceDocument?: string;
  routePlan?: OnboardingRoutePlan;
  importOverrides?: Record<string, OnboardingImportOverride>;
}
export type OnboardingExecutableStep = "IMPORT_SKILLS" | "ASSEMBLE" | "VERIFY" | "PUBLISH";
export interface OnboardingImportedDraft {
  operationId: string;
  code: string;
  version: number;
  skillVersionId: number;
  sha256: string;
  status: string;
  workspacePath?: string;
}
export interface OnboardingSkillImportReceipt {
  drafts?: OnboardingImportedDraft[];
  missing?: { code: string; message: string }[];
  sourceResolved?: boolean;
  links?: { skillWorkspaces?: { code: string; path: string }[] };
}
export interface OnboardingStep {
  step: string; status: string; receipt?: Record<string, unknown>;
  error?: { code?: string; message?: string };
}
export interface OnboardingSession {
  sessionId: string; rev: number; status: string;
  templateCode: string; templateRev: number; appCode: string;
  plan: OnboardingSessionPlan; planSha256: string; steps: OnboardingStep[];
  application?: { appCode: string; draftRev: number; status: string; currentVersionNo?: number };
  handover?: { templateCode: string; appCode: string; appVersion: number; launchPath: string };
  choices?: {
    connections?: { label: string; ref: OnboardingConnectionRef }[];
    models?: { id: number; label: string }[];
    operations?: { operationId: string; supported: boolean; reasons?: string[] }[];
  };
}
export interface OnboardingPreview {
  ready: boolean; missing: { code: string; message: string }[];
  operations?: { operationId: string; supported: boolean; reasons?: string[] }[];
  resourcePlan?: Record<string, unknown>;
}
export type OnboardingTemplateChoice = Pick<OnboardingTemplateRecord, "code" | "name" | "kind" | "status" | "currentRev">;
type Api = (path: string, method?: string, body?: unknown) => Promise<unknown>;

function record(value: unknown): Record<string, unknown> | undefined {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown> : undefined;
}
const text = (value: unknown): value is string => typeof value === "string" && value.trim().length > 0;
const positive = (value: unknown): value is number => typeof value === "number" && Number.isSafeInteger(value) && value > 0;
export const onboardingSessionTerminal = (status: string) => status === "PUBLISHED" || status === "CANCELLED";
export const onboardingSourceDocumentLimit = 1_048_576;
export const onboardingImportConfigurationLimit = 262_144;

export function onboardingDocumentBytes(value: string) {
  return new TextEncoder().encode(value).byteLength;
}

function importRoute(value: unknown): value is OnboardingRoutePlan {
  const route = record(value);
  return !!route && Object.keys(route).length === 2
    && text(route.environment) && route.environment.length <= 64
    && text(route.routeProfileId) && route.routeProfileId.length <= 64;
}

function importOverrides(value: unknown): value is Record<string, OnboardingImportOverride> {
  const overrides = record(value);
  if (!overrides || !Object.keys(overrides).length || Object.keys(overrides).length > 32) return false;
  const allowed = new Set([
    "name", "description", "execution", "guard", "returnSpec", "errorSemantics",
    "timeoutMs", "constraints",
  ]);
  return Object.entries(overrides).every(([operationId, value]) => {
    const row = record(value);
    return operationId.trim().length > 0 && operationId.length <= 256 && row
      && Object.keys(row).every(key => allowed.has(key))
      && text(row.name) && text(row.description)
      && !!record(row.execution) && !!record(row.guard) && !!record(row.returnSpec)
      && Array.isArray(row.errorSemantics)
      && (row.timeoutMs === undefined || positive(row.timeoutMs))
      && (row.constraints === undefined || !!record(row.constraints));
  });
}

/** 大小、深度和对象形状只是客户端输入保护；内容不构成批准、授权或后端合同验证。 */
export function parseOnboardingImportConfiguration(content: string): OnboardingImportConfiguration {
  if (onboardingDocumentBytes(content) > onboardingImportConfigurationLimit)
    throw Error("专业配置文件最大256KiB，未替换原配置");
  let value: unknown;
  try {
    value = JSON.parse(content);
  } catch {
    throw Error("专业配置文件必须是合法JSON对象，未替换原配置");
  }
  let nodes = 0;
  function withinBudget(item: unknown, depth: number) {
    if (++nodes > 20_000 || depth > 32) throw Error("专业配置嵌套或内容过多，未替换原配置");
    if (Array.isArray(item)) {
      item.forEach(child => withinBudget(child, depth + 1));
    } else {
      const object = record(item);
      if (!object) return;
      for (const [key, child] of Object.entries(object)) {
        if (["__proto__", "constructor", "prototype"].includes(key))
          throw Error("专业配置包含不允许的对象字段，未替换原配置");
        withinBudget(child, depth + 1);
      }
    }
  }
  withinBudget(value, 0);
  const configuration = record(value);
  if (!configuration || Object.keys(configuration).length !== 2
      || !importRoute(configuration.routePlan) || !importOverrides(configuration.importOverrides))
    throw Error("专业配置须只包含routePlan和importOverrides，并显式提供原OpenAPI人工合同字段");
  return value as OnboardingImportConfiguration;
}

/** 只链接当前实际技能详情路由；服务端任意URL不能变成向导中的可点击入口。 */
export function onboardingSkillWorkspacePath(code: string, preferred?: string): string | undefined {
  if (!/^[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(code)) return undefined;
  const path = `/skills/${encodeURIComponent(code)}`;
  if (preferred === `#${path}`) return preferred;
  if (preferred === path) return `#${preferred}`;
  return `#${path}`;
}

export function onboardingSkillImportReceipt(value: unknown): OnboardingSkillImportReceipt | undefined {
  if (value === undefined || value === null) return undefined;
  const receipt = record(value);
  if (!receipt) throw Error("技能导入回执必须是对象，请保留会话后重新读取");
  if (receipt.drafts !== undefined && (!Array.isArray(receipt.drafts) || !receipt.drafts.every(item => {
    const draft = record(item);
    return draft && text(draft.operationId) && text(draft.code) && positive(draft.version)
      && positive(draft.skillVersionId) && typeof draft.sha256 === "string" && /^[a-f0-9]{64}$/.test(draft.sha256)
      && text(draft.status) && (draft.workspacePath === undefined || typeof draft.workspacePath === "string");
  }))) throw Error("技能导入回执缺少实际编码、版本或摘要，请保留会话后重新读取");
  if (receipt.sourceResolved !== undefined && typeof receipt.sourceResolved !== "boolean")
    throw Error("技能来源解析回执格式不完整");
  if (receipt.missing !== undefined && (!Array.isArray(receipt.missing) || !receipt.missing.every(item => {
    const missing = record(item);
    return missing && text(missing.code) && text(missing.message);
  }))) throw Error("技能导入缺项回执格式不完整");
  if (receipt.links !== undefined) {
    const links = record(receipt.links);
    if (!links || (links.skillWorkspaces !== undefined && (!Array.isArray(links.skillWorkspaces)
        || !links.skillWorkspaces.every(item => {
          const link = record(item);
          return link && text(link.code) && text(link.path);
        })))) throw Error("技能工作台入口回执格式不完整");
  }
  return receipt as OnboardingSkillImportReceipt;
}

/** 缺失稳定字段即拒绝，不用空对象或客户端默认计划冒充恢复成功。 */
export function parseOnboardingSession(value: unknown): OnboardingSession {
  const row = record(value);
  const plan = record(row?.plan);
  const fields = record(plan?.fieldsBundle);
  const operations = plan?.selectedOperations;
  const steps = row?.steps;
  if (!row || !text(row.sessionId) || !positive(row.rev) || !text(row.status)
      || !text(row.appCode) || !text(row.templateCode) || !positive(row.templateRev)
      || !plan || !fields || !Array.isArray(fields.fields)
      || !record(fields.mappings) || !record(fields.returns) || !record(plan.values)
      || !Array.isArray(operations) || !operations.every(text) || new Set(operations).size !== operations.length
      || !Array.isArray(steps) || !steps.every(step => text(record(step)?.step) && text(record(step)?.status)))
    throw Error("服务返回的接入会话格式不完整，请保留会话标识并刷新重试");
  if ((plan.sourceDocument !== undefined && (typeof plan.sourceDocument !== "string"
      || onboardingDocumentBytes(plan.sourceDocument) > onboardingSourceDocumentLimit))
      || (plan.routePlan !== undefined && !importRoute(plan.routePlan))
      || (plan.importOverrides !== undefined && !importOverrides(plan.importOverrides)))
    throw Error("恢复的OpenAPI规格或专业配置格式不完整，请保留会话后重试");
  for (const step of steps) {
    const row = record(step)!;
    if (row.step === "IMPORT_SKILLS" && row.receipt !== undefined) onboardingSkillImportReceipt(row.receipt);
  }
  const application = row.application === undefined ? undefined : record(row.application);
  if (row.application !== undefined && (!application || application.appCode !== row.appCode || !positive(application.draftRev)))
    throw Error("接入会话的应用回执与实际目标应用不一致");
  if (row.handover !== undefined) {
    const handover = record(row.handover);
    if (row.status !== "PUBLISHED" || !handover || handover.appCode !== row.appCode
        || handover.templateCode !== row.templateCode || !positive(handover.appVersion)
        || (handover.status !== undefined && handover.status !== "PUBLISHED"))
      throw Error("服务端交接未确认实际应用与发布版本，不能打开助手入口");
    // launchPath仅保留为审计信息；页面入口由已核对的实际appCode和既有webapp地址推导。
  }
  const choices = record(row.choices);
  if (choices?.operations !== undefined && (!Array.isArray(choices.operations)
      || !choices.operations.every(item => {
        const operation = record(item);
        return operation && text(operation.operationId) && typeof operation.supported === "boolean"
          && (operation.reasons === undefined || (Array.isArray(operation.reasons) && operation.reasons.every(text)));
      }))) throw Error("接入操作支持情况回执不完整");
  return value as OnboardingSession;
}

export function onboardingSessionClient(api: Api) {
  const root = "/admin/onboarding-sessions";
  const path = (id: string) => `${root}/${encodeURIComponent(id)}`;
  return {
    async templates(): Promise<OnboardingTemplateChoice[]> {
      const raw = await api(`${root}/templates`);
      const value = Array.isArray(raw) ? raw : record(raw)?.items;
      if (!Array.isArray(value) || !value.every(item => {
        const row = record(item);
        return row && text(row.code) && text(row.name) && row.kind === "QUERY"
          && row.status === "PUBLISHED" && positive(row.currentRev);
      })) throw Error("可用接入模板目录格式不完整");
      return value as OnboardingTemplateChoice[];
    },
    async list() {
      const raw = await api(root), rows = Array.isArray(raw) ? raw : record(raw)?.items;
      if (!Array.isArray(rows)) throw Error("接入会话列表格式不正确");
      return rows.map(parseOnboardingSession);
    },
    get: (id: string) => api(path(id)).then(parseOnboardingSession),
    create: (body: { templateCode: string; templateRev: number; appCode: string; appName: string; requestId: string }) =>
      api(root, "POST", body).then(parseOnboardingSession),
    save: (session: OnboardingSession, plan: OnboardingSessionPlan, requestId: string) =>
      api(path(session.sessionId), "PUT", { expectedRev: session.rev, requestId, plan }).then(parseOnboardingSession),
    async preview(session: OnboardingSession, requestId: string): Promise<OnboardingPreview> {
      const value = record(await api(`${path(session.sessionId)}/preview`, "POST", { expectedRev: session.rev, requestId }));
      if (!value || typeof value.ready !== "boolean" || !Array.isArray(value.missing))
        throw Error("接入预检回执不完整");
      return value as unknown as OnboardingPreview;
    },
    execute: (session: OnboardingSession, step: OnboardingExecutableStep, requestId: string) =>
      api(`${path(session.sessionId)}/steps/${step}/execute`, "POST", {
        expectedRev: session.rev, requestId, planSha256: session.planSha256,
      }).then(parseOnboardingSession),
    cancel: (session: OnboardingSession, reason: string, requestId: string) =>
      api(`${path(session.sessionId)}/cancel`, "POST", { expectedRev: session.rev, reason, requestId }).then(parseOnboardingSession),
  };
}
