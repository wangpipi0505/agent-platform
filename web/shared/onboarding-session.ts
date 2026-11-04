import type { OnboardingTemplateRecord } from "./onboarding";
import type { FieldsBundle } from "./onboarding-fields";

export interface OnboardingConnectionRef {
  profileId: string; profileRev: number; bindingId: string; bindingRev: number;
  operation: string; environment?: string;
}
export interface OnboardingSessionPlan {
  fieldsBundle: FieldsBundle;
  values: Record<string, unknown>;
  selectedOperations: string[];
  modelEntryId?: number;
  connectionRef?: OnboardingConnectionRef;
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
    execute: (session: OnboardingSession, step: "ASSEMBLE" | "VERIFY" | "PUBLISH", requestId: string) =>
      api(`${path(session.sessionId)}/steps/${step}/execute`, "POST", {
        expectedRev: session.rev, requestId, planSha256: session.planSha256,
      }).then(parseOnboardingSession),
    cancel: (session: OnboardingSession, reason: string, requestId: string) =>
      api(`${path(session.sessionId)}/cancel`, "POST", { expectedRev: session.rev, reason, requestId }).then(parseOnboardingSession),
  };
}
