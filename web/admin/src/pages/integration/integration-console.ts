/**
 * INT-08：配置管理台的共享形状与纯函数。
 *
 * 页面只通过这里解析服务端响应、比较草稿差异、判断激活前置条件并生成脱敏导出；
 * 任何"前端自己决定生效"的判断都不在这里——激活仍由后端复核引用并 CAS 切换指针。
 * 解析失败抛可读错误，避免把 unknown 直接渲染成 [object Object]。
 */
import { asUnknownRecord } from "../../../../shared/resource-types";
import type { IntegrationClient } from "../../../../shared/api";

export type StageStatus = "OK" | "WARN" | "BLOCKED" | "UNKNOWN";
export const ENVIRONMENTS = ["production", "staging", "test", "development"] as const;

export interface ApplicationOption {
  code: string;
  name: string;
  currentVersionNo?: number;
}
export interface ConnectionRef {
  profileId: string;
  profileRev: number;
  bindingId?: string;
  bindingRev?: number;
  operation?: string;
  environment?: string;
  resolutionDigest?: string;
}
export interface SkillRef {
  code: string;
  version: number;
}
export interface ReleaseDraft {
  appCode: string;
  appVersion: number;
  experienceRev: number;
  integrationProfileId: string;
  integrationProfileRev: number;
  connectionProfileRefs: ConnectionRef[];
  activationScope: string;
  semanticBindings?: unknown[];
}
export interface ReleaseView extends ReleaseDraft {
  releaseId: string;
  candidateSha256: string;
  status: string;
  digest: string;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
  skillRefs: SkillRef[];
  semanticBindings: unknown[];
}
export interface ExperienceRevision {
  experienceRev: number;
  title: string;
  welcomeText: string;
  capabilityDescriptions: unknown[];
  promptRefs: unknown[];
  inputPresentation: Record<string, unknown>;
  resultPresentation: Record<string, unknown>;
  helpRefs: unknown[];
  declarationSha256: string;
  createdBy: string;
  createdAt: string;
}
export interface ExperienceView {
  appCode: string;
  experienceRev: number;
  status: string;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
  revisions: ExperienceRevision[];
}
export interface ProfileRevision {
  profileRev: number;
  bizSystemCode: string;
  environment: string;
  mode: string;
  allowedOrigins: string[];
  contextSchema: Record<string, unknown>;
  credentialPolicyRef: string;
  experienceRef: { appCode: string; experienceRev: number } | null;
  declarationSha256: string;
  createdBy: string;
  createdAt: string;
}
export interface IntegrationProfileView {
  profileId: string;
  appCode: string;
  profileRev: number;
  status: string;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
  revisions: ProfileRevision[];
}
export interface ConnectionProfileRevision {
  profileRev: number;
  protocol: string;
  endpointPolicy: unknown;
  authScheme: unknown;
  identityPolicy: unknown;
  allowedUses: unknown;
  operationAllowlist?: string[];
  declarationSha256: string;
  createdBy: string;
  createdAt: string;
}
export interface ConnectionProfile {
  profileId: string;
  currentRev: number;
  status: string;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
  revisions: ConnectionProfileRevision[];
}
export interface EnvironmentBindingRevision {
  bindingRev: number;
  environment: string;
  connectionProfileId: string;
  connectionProfileRev: number;
  baseEndpoint: string;
  hasSecret: boolean;
  runtimeRef: Record<string, unknown>;
  originPolicy: unknown[];
  healthPolicy: Record<string, unknown>;
  declarationSha256: string;
  createdBy: string;
  createdAt: string;
}
export interface EnvironmentBinding {
  bindingId: string;
  currentRev: number;
  status: string;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
  revisions: EnvironmentBindingRevision[];
}
export interface ApplicationCredential {
  id: number;
  appCode: string;
  bizSystemCode: string;
  status: string;
  allowedOrigins: string[];
  principalIds: string[];
  issuedAt: string;
}

function text(value: unknown): string {
  return typeof value === "string" ? value : "";
}
function integer(value: unknown): number {
  const parsed = typeof value === "number" ? value : Number(value);
  return Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : 0;
}
function object(value: unknown, label: string): Record<string, unknown> {
  const record = asUnknownRecord(value);
  if (!record) throw new Error(`服务返回的${label}格式不正确，请检查接口契约后重试`);
  return record;
}
function list(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

/** 列表端点既可能返回数组，也可能返回 {items: []}；其它形状直接报错，不静默当空列表。 */
export function parseRows(value: unknown, label: string): Record<string, unknown>[] {
  const rows = Array.isArray(value) ? value : asUnknownRecord(value)?.items;
  if (!Array.isArray(rows)) throw new Error(`服务返回的${label}列表格式不正确，请检查接口契约后重试`);
  return rows.map((row) => object(row, label));
}

export function parseApplications(value: unknown): ApplicationOption[] {
  return parseRows(value, "应用")
    .filter((row) => text(row.code))
    .map((row) => ({ code: text(row.code), name: text(row.name) || text(row.code),
      ...(integer(row.currentVersionNo) > 0 ? { currentVersionNo: integer(row.currentVersionNo) } : {}) }));
}

function connectionRefs(value: unknown): ConnectionRef[] {
  return list(value)
    .map((raw) => object(raw, "连接引用"))
    .map((raw) => ({ profileId: text(raw.profileId), profileRev: integer(raw.profileRev),
      ...(raw.bindingId !== undefined ? { bindingId: text(raw.bindingId), bindingRev: integer(raw.bindingRev), operation: text(raw.operation) } : {}),
      ...(raw.environment !== undefined ? { environment: text(raw.environment) } : {}),
      ...(raw.resolutionDigest !== undefined ? { resolutionDigest: text(raw.resolutionDigest) } : {}) }))
    .filter((ref) => !!ref.profileId && ref.profileRev > 0);
}

export function parseReleases(value: unknown): ReleaseView[] {
  return parseRows(value, "配置发布").map((row) => ({
    releaseId: text(row.releaseId),
    appCode: text(row.appCode),
    appVersion: integer(row.appVersion),
    candidateSha256: text(row.candidateSha256),
    experienceRev: integer(row.experienceRev),
    integrationProfileId: text(row.integrationProfileId),
    integrationProfileRev: integer(row.integrationProfileRev),
    connectionProfileRefs: connectionRefs(row.connectionProfileRefs),
    activationScope: text(row.activationScope),
    status: text(row.status),
    digest: text(row.digest),
    createdBy: text(row.createdBy),
    createdAt: text(row.createdAt),
    updatedAt: text(row.updatedAt),
    skillRefs: list(row.skillRefs)
      .map((raw) => object(raw, "技能引用"))
      .map((raw) => ({ code: text(raw.code), version: integer(raw.version) }))
      .filter((ref) => !!ref.code),
    semanticBindings: list(row.semanticBindings),
  }));
}

export function parseExperiences(value: unknown, appCode: string): ExperienceView {
  const row = object(value, "体验描述");
  return {
    appCode: text(row.appCode) || appCode,
    experienceRev: integer(row.experienceRev),
    status: text(row.status),
    createdBy: text(row.createdBy),
    createdAt: text(row.createdAt),
    updatedAt: text(row.updatedAt),
    revisions: list(row.revisions).map((raw) => {
      const revision = object(raw, "体验修订");
      return {
        experienceRev: integer(revision.experienceRev),
        title: text(revision.title),
        welcomeText: text(revision.welcomeText),
        capabilityDescriptions: list(revision.capabilityDescriptions),
        promptRefs: list(revision.promptRefs),
        inputPresentation: asUnknownRecord(revision.inputPresentation) ?? {},
        resultPresentation: asUnknownRecord(revision.resultPresentation) ?? {},
        helpRefs: list(revision.helpRefs),
        declarationSha256: text(revision.declarationSha256),
        createdBy: text(revision.createdBy),
        createdAt: text(revision.createdAt),
      };
    }),
  };
}

export function parseProfiles(value: unknown): IntegrationProfileView[] {
  return parseRows(value, "接入档案").map((row) => ({
    profileId: text(row.profileId),
    appCode: text(row.appCode),
    profileRev: integer(row.profileRev),
    status: text(row.status),
    createdBy: text(row.createdBy),
    createdAt: text(row.createdAt),
    updatedAt: text(row.updatedAt),
    revisions: list(row.revisions).map((raw) => {
      const revision = object(raw, "接入档案修订");
      const ref = asUnknownRecord(revision.experienceRef);
      return {
        profileRev: integer(revision.profileRev),
        bizSystemCode: text(revision.bizSystemCode),
        environment: text(revision.environment),
        mode: text(revision.mode),
        allowedOrigins: list(revision.allowedOrigins).filter((origin): origin is string => typeof origin === "string"),
        contextSchema: asUnknownRecord(revision.contextSchema) ?? {},
        credentialPolicyRef: text(revision.credentialPolicyRef),
        experienceRef: ref ? { appCode: text(ref.appCode), experienceRev: integer(ref.experienceRev) } : null,
        declarationSha256: text(revision.declarationSha256),
        createdBy: text(revision.createdBy),
        createdAt: text(revision.createdAt),
      };
    }),
  }));
}

export function parseConnections(value: unknown): ConnectionProfile[] {
  return parseRows(value, "连接档案").map((row) => ({
    profileId: text(row.profileId),
    currentRev: integer(row.currentRev),
    status: text(row.status),
    createdBy: text(row.createdBy),
    createdAt: text(row.createdAt),
    updatedAt: text(row.updatedAt),
    revisions: list(row.revisions).map((raw) => {
      const revision = object(raw, "连接档案修订");
      return {
        profileRev: integer(revision.profileRev),
        protocol: text(revision.protocol),
        endpointPolicy: revision.endpointPolicy,
        authScheme: revision.authScheme,
        identityPolicy: revision.identityPolicy,
        allowedUses: revision.allowedUses,
        operationAllowlist: list(revision.operationAllowlist).filter((v): v is string => typeof v === "string"),
        declarationSha256: text(revision.declarationSha256),
        createdBy: text(revision.createdBy),
        createdAt: text(revision.createdAt),
      };
    }),
  }));
}

export function parseBindings(value: unknown): EnvironmentBinding[] {
  return parseRows(value, "环境绑定").map((row) => ({
    bindingId: text(row.bindingId),
    currentRev: integer(row.currentRev),
    status: text(row.status),
    createdBy: text(row.createdBy),
    createdAt: text(row.createdAt),
    updatedAt: text(row.updatedAt),
    revisions: list(row.revisions).map((raw) => {
      const revision = object(raw, "环境绑定修订");
      return {
        bindingRev: integer(revision.bindingRev),
        environment: text(revision.environment),
        connectionProfileId: text(revision.connectionProfileId),
        connectionProfileRev: integer(revision.connectionProfileRev),
        baseEndpoint: text(revision.baseEndpoint),
        // secretRef 仅用于判断“是否已配置”，不进入界面展示之外的任何导出。
        hasSecret: !!text(revision.secretRef),
        runtimeRef: asUnknownRecord(revision.runtimeRef) ?? {},
        originPolicy: list(revision.originPolicy),
        healthPolicy: asUnknownRecord(revision.healthPolicy) ?? {},
        declarationSha256: text(revision.declarationSha256),
        createdBy: text(revision.createdBy),
        createdAt: text(revision.createdAt),
      };
    }),
  }));
}

export function parseCredential(value: unknown, appCode: string): ApplicationCredential | null {
  const row = asUnknownRecord(value);
  if (!row) return null;
  const limits = asUnknownRecord(row.claimLimits);
  const principals = limits ? list(limits.principalIds) : [];
  return {
    id: integer(row.id),
    appCode: text(row.appCode) || appCode,
    bizSystemCode: text(row.bizSystemCode),
    status: text(row.status),
    allowedOrigins: list(row.allowedOrigins).filter((origin): origin is string => typeof origin === "string"),
    principalIds: principals.filter((id): id is string => typeof id === "string"),
    issuedAt: text(row.issuedAt),
  };
}

export function activeRelease(releases: ReleaseView[], environment: string): ReleaseView | null {
  return releases.find((release) => release.status === "ACTIVE" && release.activationScope === environment) ?? null;
}

export function nextRequestId(): string {
  const cryptoApi = globalThis.crypto;
  if (cryptoApi && typeof cryptoApi.randomUUID === "function") return cryptoApi.randomUUID();
  return `req-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`;
}

/**
 * 复制为草稿：只能从当前环境的激活版本复制。
 * 跨环境复制等于把另一个环境的引用带进本环境，必须先重新选择引用，所以这里明确拒绝。
 */
export function copyAsDraft(release: ReleaseView | null, appCode: string, environment: string): ReleaseDraft {
  if (!release) throw new Error("请先等待当前环境加载出激活的配置版本");
  if (release.activationScope !== environment) {
    throw new Error(
      `不能把 ${release.activationScope || "未知环境"} 的激活配置复制到 ${environment}；请重新选择该环境的引用后再创建。`,
    );
  }
  return {
    appCode,
    appVersion: release.appVersion,
    experienceRev: release.experienceRev,
    integrationProfileId: release.integrationProfileId,
    integrationProfileRev: release.integrationProfileRev,
    connectionProfileRefs: release.connectionProfileRefs.map((ref) => ({ ...ref })),
    activationScope: environment,
    ...(release.semanticBindings.length ? { semanticBindings: release.semanticBindings.map((ref) => ({ ...object(ref, "语义绑定") })) } : {}),
  };
}

export function validateDraft(draft: ReleaseDraft): string[] {
  const problems: string[] = [];
  if (!draft.appCode.trim()) problems.push("缺少应用编码");
  if (!Number.isSafeInteger(draft.appVersion) || draft.appVersion < 1) problems.push("应用版本必须是正整数");
  if (!Number.isSafeInteger(draft.experienceRev) || draft.experienceRev < 1) problems.push("体验修订必须是正整数");
  if (!draft.integrationProfileId.trim()) problems.push("缺少接入档案");
  if (!Number.isSafeInteger(draft.integrationProfileRev) || draft.integrationProfileRev < 1) {
    problems.push("接入档案修订必须是正整数");
  }
  if (!/^[a-z][a-z0-9-]{0,31}$/.test(draft.activationScope)) problems.push("生效环境格式不正确");
  if (draft.connectionProfileRefs.some((ref) => !ref.profileId || ref.profileRev < 1)) {
    problems.push("连接引用必须包含档案与正整数修订");
  }
  return problems;
}

export interface FieldDiff {
  field: string;
  label: string;
  before: string;
  after: string;
}

function describeConnections(refs: ConnectionRef[]): string {
  return refs.map((ref) => `${ref.profileId}@${ref.profileRev}`).join("、") || "无";
}

export function diffRelease(base: ReleaseView | null, draft: ReleaseDraft): FieldDiff[] {
  if (!base) return [];
  const fields: Array<[string, string, unknown, unknown]> = [
    ["appVersion", "应用版本", base.appVersion, draft.appVersion],
    ["experienceRev", "体验修订", base.experienceRev, draft.experienceRev],
    ["integrationProfileId", "接入档案", base.integrationProfileId, draft.integrationProfileId],
    ["integrationProfileRev", "接入档案修订", base.integrationProfileRev, draft.integrationProfileRev],
    ["connectionProfileRefs", "连接引用", describeConnections(base.connectionProfileRefs), describeConnections(draft.connectionProfileRefs)],
    ["activationScope", "生效环境", base.activationScope, draft.activationScope],
  ];
  return fields
    .filter(([, , before, after]) => String(before) !== String(after))
    .map(([field, label, before, after]) => ({ field, label, before: String(before), after: String(after) }));
}

export interface ActivationContext {
  release: ReleaseView | null;
  environment: string;
  experience?: ExperienceView;
  profile?: IntegrationProfileView;
  connections: ConnectionProfile[];
}

/** 激活前置条件：只报事实，不替后端做决定；真正切换时后端还会再复核一次引用。 */
export function activationBlockers(context: ActivationContext): string[] {
  const { release } = context;
  if (!release) return ["当前环境没有可激活的配置发布"];
  const blockers: string[] = [];
  if (release.status !== "APPROVED") {
    blockers.push(`配置发布状态为 ${release.status}，只有通过校验并批准（APPROVED）后才能激活`);
  }
  if (release.activationScope !== context.environment) {
    blockers.push(
      `配置发布生效环境为 ${release.activationScope}，与当前选择的 ${context.environment} 不一致；请先切换环境。`,
    );
  }
  if (!/^[0-9a-f]{64}$/.test(release.digest)) blockers.push("配置发布缺少有效的完整性摘要，不能激活");
  const experience = context.experience;
  if (experience && experience.status !== "APPROVED") blockers.push("体验修订未批准；激活时的引用复核会拒绝这次切换");
  const profile = context.profile;
  if (!profile) blockers.push("接入档案不存在或不可见，无法确认引用有效性");
  else if (profile.status !== "APPROVED") blockers.push(`接入档案状态为 ${profile.status}，未批准不能激活`);
  for (const ref of release.connectionProfileRefs) {
    const connection = context.connections.find((item) => item.profileId === ref.profileId);
    if (!connection) blockers.push(`连接档案 ${ref.profileId} 不存在或不可见`);
    else if (connection.status !== "APPROVED") blockers.push(`连接档案 ${ref.profileId} 状态为 ${connection.status}，未批准不能激活`);
  }
  return blockers;
}

export function releaseImpact(action: "validate" | "approve" | "activate" | "disable"): string[] {
  switch (action) {
    case "validate":
      return ["重新核对应用版本、体验修订、接入档案与连接引用是否仍有效。", "只改变这份草稿的状态，不影响任何生产会话。"];
    case "approve":
      return ["批准只代表这份发布可以进入激活候选；不会自动生效。", "激活时还会再复核一次引用，过期组合不会上线。"];
    case "activate":
      return [
        "激活后，该环境的新会话与新运行按这份配置解析体验、接入档案与连接。",
        "既有运行继续使用各自创建时的快照，不回溯改写。",
        "同作用域的上一个激活发布自动退位为 SUPERSEDED，历史仍保留。",
      ];
    case "disable":
      return [
        "停用后该环境没有激活配置：新会话与新运行会收到 CONFIG_RELEASE_NOT_ACTIVE（409）。",
        "不会自动降级到草稿、其它环境或默认能力。",
        "既有运行、审计与历史配置保留，可重新激活其它已批准发布。",
      ];
  }
}

const SENSITIVE_KEY = /secret|token|password|credential|authorization|cookie|createdby|principal|tenant/i;

/** 导出只保留逻辑引用与校验摘要；secretRef、物理地址、内部账号与任何凭据都不出现。 */
export function redactedExport(release: ReleaseView, environment: string): Record<string, unknown> {
  return {
    kind: "agent-platform.config-release",
    exportedAt: new Date().toISOString(),
    environment,
    release: {
      releaseId: release.releaseId,
      appCode: release.appCode,
      appVersion: release.appVersion,
      status: release.status,
      digest: release.digest,
      candidateSha256: release.candidateSha256,
      activationScope: release.activationScope,
      experienceRef: { appCode: release.appCode, experienceRev: release.experienceRev },
      integrationProfileRef: { profileId: release.integrationProfileId, profileRev: release.integrationProfileRev },
      connectionProfileRefs: release.connectionProfileRefs.map((ref) => ({
        profileId: ref.profileId,
        profileRev: ref.profileRev,
      })),
      skillRefs: release.skillRefs.map((ref) => ({ code: ref.code, version: ref.version })),
    },
  };
}

/** 防回归：任何导出对象里出现敏感键名都视为缺陷（由单测覆盖）。 */
export function containsSensitiveKey(value: unknown): boolean {
  if (Array.isArray(value)) return value.some(containsSensitiveKey);
  const record = asUnknownRecord(value);
  if (!record) return false;
  return Object.entries(record).some(([key, child]) => SENSITIVE_KEY.test(key) || containsSensitiveKey(child));
}

export interface DiagnosticStage {
  key: string;
  label: string;
  status: StageStatus;
  detail: string;
  evidence: string;
}

export interface DiagnosticInput {
  environment: string;
  releases: ReleaseView[];
  profiles: IntegrationProfileView[];
  connections: ConnectionProfile[];
  bindings: EnvironmentBinding[];
  credential: ApplicationCredential | null;
}

/**
 * 诊断按接入 ID 组合只读投影，不调用写接口，也不发起业务运行。
 * 控制台没有 Authority 身份源档案的只读端点，因此身份阶段如实标记 UNKNOWN，而不是伪造探针。
 */
export function diagnoseStages(input: DiagnosticInput): DiagnosticStage[] {
  const active = activeRelease(input.releases, input.environment);
  const stages: DiagnosticStage[] = [
    {
      key: "identity",
      label: "身份源",
      status: "UNKNOWN",
      detail: "身份源档案由 Authority 运行时配置；本控制台没有只读投影，不能在这里判定其可用性。",
      evidence: "无只读端点",
    },
  ];
  const credential = input.credential;
  stages.push({
    key: "ticket",
    label: "票据与凭据",
    status: !credential ? "BLOCKED" : credential.status === "ACTIVE" ? "OK" : "BLOCKED",
    detail: !credential
      ? "该应用尚未签发集成凭据，宿主无法换取短期运行票据。"
      : credential.status === "ACTIVE"
        ? `凭据 ${credential.bizSystemCode || "（未命名）"} 有效；来源白名单 ${credential.allowedOrigins.length} 条，主体上限 ${credential.principalIds.length} 个。`
        : `凭据状态为 ${credential.status}，已撤权；续期票据会被拒绝。`,
    evidence: credential ? `credential#${credential.id}` : "无凭据",
  });
  stages.push({
    key: "version",
    label: "配置版本与权限",
    status: !active ? "BLOCKED" : /^[0-9a-f]{64}$/.test(active.digest) ? "OK" : "WARN",
    detail: !active
      ? `环境 ${input.environment} 没有激活的配置发布；新会话/运行会收到 CONFIG_RELEASE_NOT_ACTIVE。`
      : `激活发布 ${active.releaseId}（应用版本 ${active.appVersion}）状态 ${active.status}，摘要 ${active.digest.slice(0, 12)}…。`,
    evidence: active ? `release#${active.releaseId}` : "无激活发布",
  });
  const profile = active
    ? input.profiles.find((item) => item.profileId === active.integrationProfileId)
    : undefined;
  stages.push({
    key: "profile",
    label: "接入档案引用",
    status: !active ? "UNKNOWN" : !profile ? "BLOCKED" : profile.status === "APPROVED" ? "OK" : "BLOCKED",
    detail: !active
      ? "没有激活发布，无法确定接入档案。"
      : !profile
        ? `激活发布引用的接入档案 ${active.integrationProfileId} 不存在或不可见。`
        : `接入档案 ${profile.profileId}@${profile.profileRev} 状态 ${profile.status}。`,
    evidence: profile ? `profile#${profile.profileId}@${profile.profileRev}` : "无档案",
  });
  const missingConnections = active
    ? active.connectionProfileRefs.filter((ref) => {
        const connection = input.connections.find((item) => item.profileId === ref.profileId);
        return !connection || connection.status !== "APPROVED";
      })
    : [];
  stages.push({
    key: "connection",
    label: "连接可达性",
    status: !active ? "UNKNOWN" : missingConnections.length ? "BLOCKED" : active.connectionProfileRefs.length ? "OK" : "WARN",
    detail: !active
      ? "没有激活发布，无法确定连接引用。"
      : missingConnections.length
        ? `以下连接档案缺失或未批准：${missingConnections.map((ref) => ref.profileId).join("、")}。`
        : active.connectionProfileRefs.length
          ? `已声明 ${active.connectionProfileRefs.length} 个连接引用，均处于 APPROVED；可达性由运行期健康检查判定。`
          : "激活发布没有声明连接引用。",
    evidence: active ? `connections#${active.connectionProfileRefs.length}` : "无连接引用",
  });
  const binding = input.bindings.find((item) => item.status === "APPROVED" && item.revisions.some((revision) => revision.environment === input.environment));
  stages.push({
    key: "binding",
    label: "环境绑定",
    status: binding ? "OK" : "WARN",
    detail: binding
      ? `环境 ${input.environment} 已批准绑定 ${binding.bindingId}@${binding.currentRev}；物理地址与密钥不在本面板展示。`
      : `环境 ${input.environment} 没有已批准的环境绑定；运行期可能无法解析物理地址。`,
    evidence: binding ? `binding#${binding.bindingId}` : "无绑定",
  });
  return stages;
}

/** 把服务端 409/400 的登记码翻译成可执行提示，避免用户只看到“请求失败”。 */
export function actionError(error: unknown): string {
  const record = asUnknownRecord(error);
  const code = text(record?.code);
  switch (code) {
    case "CONFIG_REV_CONFLICT":
      return "配置已被他人更新（409）：请刷新后以新的 expectedRev 重试；同一 requestId 只能重放相同内容。";
    case "CONFIG_STATE_TRANSITION_INVALID":
      return "当前状态不允许该操作，请刷新状态后重试。";
    case "CONFIG_VERSION_UNKNOWN":
      return "引用的修订不存在或已变化，请重新选择。";
    case "CONFIG_REF_NOT_FOUND":
      return "引用的对象不存在，请刷新目录。";
    case "CONFIG_RELEASE_NOT_ACTIVE":
      return "该环境没有激活的配置发布。";
    default:
      return text(record?.message) || "操作失败，请稍后重试";
  }
}

export type { IntegrationClient };
