/**
 * 发布门禁（PLAT-02）的前端同屏说明模型。
 *
 * 契约对齐 app-management 后端（T02.1，`88f12a8`）：
 * - 预检 200：`AppService.precheck` 返回 gateMode/checks/warnings，评测门禁检查项的
 *   result 为 PASS|WAIVED|ADVISORY|SKIPPED；其余检查失败用 ERROR（不是 FAIL）。
 * - 预检 409：`PlatformErrors` 统一错误体 `{code,message,data,traceId}`，评测门禁拦截
 *   为 `EVAL_GATE_BLOCKED`，body 不携带 checks 明细（明细只落审计行）。
 * - 豁免：POST/DELETE `/admin/apps/{code}/publish-waivers`，服务端校验
 *   reason 10..512、TTL≤24h、candidateSha256 为 64 位小写十六进制（PublishWaiver.grant）。
 * 本模块只做展示归类与本地预校验；服务端仍是唯一权威，本地校验失败提示先于请求发出。
 */

export type GateMode = "BLOCKING" | "ADVISORY" | "OFF";

export type PublishCheck = {
  group: string;
  result: string;
  detail?: string;
  waiverId?: string;
  expiresAt?: string;
  evidence?: string;
};

export type PublishPrecheck = {
  precheckId: string;
  draftRev: number;
  candidateSha256: string;
  evalReportId?: string;
  passed: boolean;
  result: string;
  gateMode: GateMode;
  waiverId?: string | null;
  report?: { checks?: PublishCheck[]; warnings?: string[] };
};

export type WaiverView = {
  id: string;
  appCode: string;
  draftRev: number;
  candidateSha256: string;
  reason: string;
  approvedBy: string;
  approvedAt: string;
  expiresAt: string;
  revokedAt: string | null;
  status: "ACTIVE" | "EXPIRED" | "REVOKED";
};

/** api() 抛出的错误携带 status/code/traceId；这里只认评测门禁拦截那一型。 */
export type GateRejection = {
  status: number;
  code: "EVAL_GATE_BLOCKED";
  message: string;
  traceId?: string;
};

export type WaiverRequest = {
  reason: string;
  draftRev: number;
  candidateSha256: string;
  expiresAt: string;
};

export const WAIVER_MAX_HOURS = 24;
export const WAIVER_MIN_REASON = 10;
export const WAIVER_MAX_REASON = 512;
const HOUR_MS = 3_600_000;

/** 与后端 PublishGate.parse 同规：未知值一律回落 BLOCKING（失败关闭）。 */
export function normalizeGateMode(raw: unknown): GateMode {
  if (typeof raw !== "string") return "BLOCKING";
  switch (raw.trim().toUpperCase()) {
    case "ADVISORY":
      return "ADVISORY";
    case "OFF":
      return "OFF";
    default:
      return "BLOCKING";
  }
}

export type GateModeInfo = {
  mode: GateMode;
  label: string;
  tone: "danger" | "warning" | "info";
  description: string;
};

const MODE_INFO: Record<GateMode, GateModeInfo> = {
  BLOCKING: {
    mode: "BLOCKING",
    label: "阻断",
    tone: "danger",
    description: "评测门禁阻断：缺少满足安全100%、通过率及无新增回归的候选报告时，不能发布。",
  },
  ADVISORY: {
    mode: "ADVISORY",
    label: "仅告警",
    tone: "warning",
    description: "评测门禁仅告警：缺少合格报告时放行发布，发布风险由发布人承担。",
  },
  OFF: {
    mode: "OFF",
    label: "已关闭",
    tone: "info",
    description: "评测门禁已关闭（gate-mode=OFF）：不评估评测结论，发布不带评测证据。",
  },
};

export function gateModeInfo(mode: GateMode): GateModeInfo {
  return MODE_INFO[mode];
}

const PASSING_RESULTS = new Set(["PASS", "WAIVED", "ADVISORY", "SKIPPED"]);

/** 与服务端 `passed` 计算同口径：其余检查失败在预检响应里的结果是 ERROR。 */
export function isPassingCheck(check: PublishCheck): boolean {
  return PASSING_RESULTS.has(check.result);
}

export function findGateCheck(checks: unknown): PublishCheck | null {
  if (!Array.isArray(checks)) return null;
  const gate = checks.find(
    (c): c is PublishCheck =>
      !!c && typeof c === "object" && (c as PublishCheck).group === "评测门禁",
  );
  return gate ?? null;
}

export function isGateRejection(e: unknown): e is GateRejection {
  return (
    !!e &&
    typeof e === "object" &&
    (e as { status?: unknown }).status === 409 &&
    (e as { code?: unknown }).code === "EVAL_GATE_BLOCKED"
  );
}

export function formatWaiverRemaining(expiresAt: string, now: number): string {
  const deadline = Date.parse(expiresAt);
  if (!Number.isFinite(deadline)) return "有效期未知";
  const remaining = deadline - now;
  if (remaining <= 0) return "已到期";
  const minutes = Math.floor(remaining / 60_000);
  const hours = Math.floor(minutes / 60);
  if (hours >= 1) return `剩余 ${hours} 小时 ${minutes - hours * 60} 分`;
  return `剩余 ${minutes} 分`;
}

export function shortSha(sha: string): string {
  return sha.length > 16 ? `${sha.slice(0, 12)}…` : sha;
}

const SHA_PATTERN = /^[0-9a-f]{64}$/;

/**
 * 创建豁免请求的本地预校验，字段规则镜像后端 PublishWaiver.grant；
 * 目的是把明显的形状错误留在前端提示，服务端校验仍是唯一权威。
 */
export function buildWaiverPayload(
  input: { reason: string; hours: number; draftRev: number; candidateSha256: string },
  now: number,
): WaiverRequest {
  const reason = input.reason.trim();
  if (reason.length < WAIVER_MIN_REASON || reason.length > WAIVER_MAX_REASON)
    throw new Error(`豁免理由需${WAIVER_MIN_REASON}至${WAIVER_MAX_REASON}字`);
  if (!Number.isInteger(input.hours) || input.hours < 1 || input.hours > WAIVER_MAX_HOURS)
    throw new Error(`豁免有效期需为 1 到 ${WAIVER_MAX_HOURS} 的整数小时`);
  if (!Number.isInteger(input.draftRev) || input.draftRev < 1)
    throw new Error("豁免必须绑定有效的草稿修订号");
  if (!SHA_PATTERN.test(input.candidateSha256))
    throw new Error("豁免必须绑定64位小写十六进制候选摘要");
  return {
    reason,
    draftRev: input.draftRev,
    candidateSha256: input.candidateSha256,
    // 到期时间落在 (now, now+24h]，与服务端窗口校验一致。
    expiresAt: new Date(now + input.hours * HOUR_MS).toISOString(),
  };
}

export type ActiveWaiverView = {
  id: string;
  reason: string;
  expiresAt: string;
  remainingMs: number;
  remainingText: string;
  status: WaiverView["status"];
  /** precheck 检查项只能带回 id/expiresAt；session 豁免才有完整绑定。 */
  source: "session" | "precheck";
  bindingMismatch: boolean;
};

export type GateStateInput = {
  precheck?: PublishPrecheck | null;
  rejection?: GateRejection | null;
  sessionWaiver?: WaiverView | null;
  candidate?: { draftRev?: number; candidateSha256?: string } | null;
  isAdmin?: boolean;
  now?: number;
};

export type GateStateView = {
  mode: GateMode | null;
  modeInfo: GateModeInfo | null;
  gateCheck: PublishCheck | null;
  /** 200 响应里未通过的检查（结构性问题），评测豁免不适用。 */
  failing: PublishCheck[];
  warnings: string[];
  blocked: GateRejection | null;
  /** 409 message 表明是豁免被撤销/过期导致，需要重新授予而非反复重试。 */
  waiverInvalid: boolean;
  waiver: ActiveWaiverView | null;
  canApplyWaiver: boolean;
  applyHint: string;
};

const WAIVER_INVALID_PATTERN = /已撤销或过期/;

/** 把后端三种真实数据源（200 预检、409 拦截、会话内豁免）归并成一段同屏说明。 */
export function summarizeGateState(input: GateStateInput): GateStateView {
  const now = input.now ?? Date.now();
  const isAdmin = !!input.isAdmin;
  const precheck = input.precheck ?? null;
  const rejection = input.rejection ?? null;
  const gateCheck = findGateCheck(precheck?.report?.checks);
  const checks = Array.isArray(precheck?.report?.checks)
    ? (precheck?.report?.checks as PublishCheck[])
    : [];
  const failing = checks.filter((c) => !isPassingCheck(c));
  const warnings = Array.isArray(precheck?.report?.warnings)
    ? (precheck?.report?.warnings as string[])
    : [];
  const waiverInvalid = !!rejection && WAIVER_INVALID_PATTERN.test(rejection.message);

  const session = input.sessionWaiver ?? null;
  let waiver: ActiveWaiverView | null = null;
  if (session) {
    const deadline = Date.parse(session.expiresAt);
    // 撤销后立即失效：剩余窗口按 0 展示，而不是让到期时间伪装出剩余时间。
    const remainingMs =
      session.status === "REVOKED" ? 0 : Number.isFinite(deadline) ? Math.max(0, deadline - now) : 0;
    const bindingMismatch =
      !!input.candidate?.draftRev &&
      !!input.candidate?.candidateSha256 &&
      (session.draftRev !== input.candidate.draftRev ||
        session.candidateSha256 !== input.candidate.candidateSha256);
    waiver = {
      id: session.id,
      reason: session.reason,
      expiresAt: session.expiresAt,
      remainingMs,
      remainingText: session.status === "REVOKED" ? "已撤销" : formatWaiverRemaining(session.expiresAt, now),
      status: session.status,
      source: "session",
      bindingMismatch,
    };
  } else if (gateCheck?.waiverId) {
    // 预检检查项带回的豁免（可能是历史会话授予的）：撤销仍可用同一 id 调 DELETE。
    const expiresAt = gateCheck.expiresAt ?? "";
    const deadline = Date.parse(expiresAt);
    const remainingMs = Number.isFinite(deadline) ? Math.max(0, deadline - now) : 0;
    waiver = {
      id: gateCheck.waiverId,
      reason: gateCheck.detail ?? "",
      expiresAt,
      remainingMs,
      remainingText: formatWaiverRemaining(expiresAt, now),
      status: remainingMs > 0 ? "ACTIVE" : "EXPIRED",
      source: "precheck",
      bindingMismatch: false,
    };
  }

  let applyHint = "";
  if (rejection) {
    if (!isAdmin)
      applyHint = "仅 ADMIN 可申请豁免；当前账号无权操作，请联系管理员处理。";
    else if (waiverInvalid)
      applyHint = "发布豁免已撤销或过期：请重新申请豁免，或修复评测后重新预检。";
    else
      applyHint = `仅 ADMIN 可申请豁免；豁免最长 ${WAIVER_MAX_HOURS} 小时，只覆盖评测门禁，绑定当前草稿修订与候选摘要，撤销或到期立即失效。`;
  } else if (failing.length > 0) {
    applyHint = "存在未通过的结构性检查，评测门禁豁免不适用于此类问题，请修复后重新预检。";
  }

  return {
    mode: precheck ? normalizeGateMode(precheck.gateMode) : null,
    modeInfo: precheck ? gateModeInfo(normalizeGateMode(precheck.gateMode)) : null,
    gateCheck,
    failing,
    warnings,
    blocked: rejection,
    waiverInvalid,
    waiver,
    canApplyWaiver: isAdmin && !!rejection,
    applyHint,
  };
}
