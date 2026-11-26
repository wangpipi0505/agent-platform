/**
 * M5-2：会话域真实 API 客户端（经网关）——F4 助手端会话列表 + F3-6 管理台会话详情数据面。
 *
 * 端点（engine ChatSessionController/SubagentCatalogController/TaskController，已登记网关）：
 *   GET /v1/apps/{code}/sessions?mine=true&limit&before   本人会话列表（S2-1）
 *   GET /v1/apps/{code}/sessions/{id}                     快照（messages=普通视图消息投影）
 *   GET /v1/apps/{code}/sessions/{id}/events?afterSeq&limit&surfaceOnly  事件增量
 *   GET /v1/sessions/{id}/subagents                       子代理目录（分角色停因投影）
 *   GET /v1/tasks/{taskId}                                recovery 恢复事实（服务端角色投影）
 *
 * 诚实边界（无对应真实端点，如实降级）：会话→调用链无端点级桥（调用按 taskId/stepId 查询，
 * 会话详情页无 linkedTaskId 可解析时调用列表如实为空）；appCode 为空的深链按演示应用编码解析，
 * 不属于该应用的会话如实 404。
 */
import { useApi } from "./api";
import { demoAppCode, type DemoTaskRecovery } from "./api-task";

export type DemoRole = "USER" | "OPERATOR";

export interface DemoSessionEvent {
  seq: number;
  type: string;
  occurredAt: string;
  data: Record<string, unknown>;
}
export interface DemoSnapshot {
  sessionId: string;
  appCode: string;
  title?: string;
  state?: string;
  principalId?: string;
  linkedTaskId?: string;
  eventWatermark: number;
  messages: { seq: number; role: string; text: string; occurredAt?: string }[];
}
export interface DemoReject {
  error: string;
  httpStatus: number;
  category?: string;
  userMessage: string;
  traceId?: string;
  /** 服务端拒绝审计投影（真实 403/404 响应未提供审计体时为 null，页面如显示实占位）。 */
  audit?: {
    principal?: string;
    appCode?: string;
    sessionId?: string;
    reason?: string;
  } | null;
}
export interface DemoEventsPage {
  sessionId: string;
  events: DemoSessionEvent[];
  nextAfterSeq: number;
  truncated: boolean;
}
export interface DemoCallRow {
  taskId: string;
  stepId: string;
  callId: string;
  skillCode?: string;
  skillVersion?: number;
  status?: string;
  errorSummary?: string;
  appCode?: string;
  sessionId?: string | null;
  eventSeq?: number | null;
  startedAt?: string;
  durationMs?: number;
  reconciliation?: Record<string, unknown>;
}
export interface DemoSubagentRow {
  childSessionId: string;
  childTaskId?: string;
  label?: string;
  status?: string;
  stopReason?: string;
  stopDisplay?: string;
  startedAt?: string;
  endedAt?: string;
  durationMs?: number;
}
export type DemoRecoveryProjection = Pick<
  DemoTaskRecovery,
  "recoverySource" | "reasonCode" | "reasonSummary"
> &
  Partial<Pick<DemoTaskRecovery, "checkpoint" | "reusedSteps" | "blockedReasons">>;

const reject = (error: string, httpStatus: number, userMessage: string): DemoReject => ({
  error,
  httpStatus,
  userMessage,
});

/** 标题截断（纯函数；自 mock-session 迁出——页面不再 import mock-*）。 */
export const truncateTitle = (firstUserMessage: string, max = 48) => {
  const text = String(firstUserMessage ?? "").replace(/\s+/g, " ").trim();
  if (!text) return "新会话";
  return text.length <= max ? text : `${text.slice(0, Math.max(0, max - 1))}…`;
};

export interface MockSessionRow {
  sessionId: string;
  appCode: string;
  title: string;
  startedAt: string;
  lastActiveAt: string;
  turns: number;
  state: string;
}

/** 本人会话列表（真实端点）：mine=true 首期固定；行内 turns/title 由服务端字段投影。 */
export async function listMySessions(
  query: { mine?: boolean; limit?: number; before?: string } = {},
  appCode = demoAppCode,
): Promise<{ sessions: MockSessionRow[]; nextBefore: string | null }> {
  const api = useApi();
  const params = new URLSearchParams();
  if (query.mine !== false) params.set("mine", "true");
  if (query.limit) params.set("limit", String(query.limit));
  if (query.before) params.set("before", query.before);
  const page = await api<any>(`/v1/apps/${encodeURIComponent(appCode)}/sessions?${params.toString()}`);
  const rows: any[] = Array.isArray(page?.sessions) ? page.sessions : Array.isArray(page) ? page : [];
  return {
    sessions: rows.map((row) => ({
      sessionId: String(row.sessionId ?? row.session_id ?? ""),
      appCode: String(row.appCode ?? appCode),
      title: truncateTitle(String(row.title ?? row.firstUserMessage ?? "")),
      startedAt: String(row.startedAt ?? row.started_at ?? row.createdAt ?? ""),
      lastActiveAt: String(row.lastActiveAt ?? row.last_active_at ?? row.updatedAt ?? ""),
      turns: Number(row.turns ?? row.turnCount ?? 0),
      state: String(row.state ?? ''),
    })),
    nextBefore: page?.nextBefore ?? null,
  };
}

/** 会话快照（真实端点）：appCode 为空按演示应用解析（诚实登记：无跨应用会话定位端点）。 */
export async function sessionSnapshot(
  appCode: string,
  sessionId: string,
  _role: DemoRole,
): Promise<DemoSnapshot | DemoReject> {
  const api = useApi();
  try {
    const view = await api<any>(
      `/v1/apps/${encodeURIComponent(appCode || demoAppCode)}/sessions/${encodeURIComponent(sessionId)}`,
    );
    const firstUser = (Array.isArray(view?.messages) ? view.messages : []).find(
      (message: any) => String(message.role) === "user",
    );
    return {
      sessionId: String(view?.sessionId ?? sessionId),
      appCode: String(view?.appCode ?? (appCode || demoAppCode)),
      title: truncateTitle(String(firstUser?.content ?? "")),
      state: view?.state,
      principalId: view?.principalId,
      linkedTaskId: view?.linkedTaskId ?? "",
      eventWatermark: Number(view?.eventWatermark ?? 0),
      messages: (Array.isArray(view?.messages) ? view.messages : []).map((message: any) => ({
        seq: Number(message.seq),
        role: String(message.role),
        text: String(message.content ?? message.text ?? ""),
        occurredAt: message.occurredAt,
      })),
    };
  } catch (e: any) {
    return reject(
      String(e?.code ?? "SESSION_NOT_FOUND"),
      Number(e?.status ?? 404),
      String(e?.message ?? "会话不存在或路径归属不匹配"),
    );
  }
}

/** 会话事件增量（真实端点）：surfaceOnly 视图门由服务端裁决（USER 请求诊断视图 403 如实返回）。 */
export async function sessionEvents(
  appCode: string,
  sessionId: string,
  query: { afterSeq?: number; limit?: number; surfaceOnly?: boolean } = {},
  _role: DemoRole,
): Promise<DemoEventsPage | DemoReject> {
  const api = useApi();
  const params = new URLSearchParams();
  if (typeof query.afterSeq === "number") params.set("afterSeq", String(query.afterSeq));
  if (query.limit) params.set("limit", String(query.limit));
  params.set("surfaceOnly", query.surfaceOnly === false ? "false" : "true");
  try {
    const page = await api<any>(
      `/v1/apps/${encodeURIComponent(appCode || demoAppCode)}/sessions/${encodeURIComponent(sessionId)}/events?${params.toString()}`,
    );
    return {
      sessionId: String(page?.sessionId ?? sessionId),
      events: (Array.isArray(page?.events) ? page.events : []).map((event: any) => ({
        seq: Number(event.seq),
        type: String(event.type),
        occurredAt: String(event.occurredAt ?? ""),
        data: (event.data ?? {}) as Record<string, unknown>,
      })),
      nextAfterSeq: Number(page?.nextAfterSeq ?? query.afterSeq ?? 0),
      truncated: !!page?.truncated,
    };
  } catch (e: any) {
    return reject(
      String(e?.code ?? "SESSION_EVENTS_FORBIDDEN"),
      Number(e?.status ?? 403),
      String(e?.message ?? "事件视图读取被拒绝"),
    );
  }
}

/**
 * 会话维度调用聚合（真实端点诚实降级）：调用按 taskId/stepId 查询（§5.7），会话→调用无
 * 端点级桥——linkedTaskId 未解析时如实返回空列表（页面保留"无调用"空态）。
 */
export async function sessionCalls(
  _sessionId: string,
  _role: DemoRole,
): Promise<{ calls: DemoCallRow[]; total: number }> {
  return { calls: [], total: 0 };
}

/** 子代理目录（真实端点）：分角色停因投影由服务端承接（USER 展示摘要/OPERATOR 原因码）。 */
export async function sessionSubagents(
  sessionId: string,
  _role: DemoRole,
): Promise<{ children: DemoSubagentRow[]; nextAfter: string | null }> {
  const api = useApi();
  try {
    const page = await api<any>(`/v1/sessions/${encodeURIComponent(sessionId)}/subagents`);
    return {
      children: (Array.isArray(page?.children) ? page.children : []).map((row: any) => ({
        childSessionId: String(row.childSessionId ?? ""),
        childTaskId: row.childTaskId,
        label: row.label,
        status: row.status,
        stopReason: row.stopReason,
        stopDisplay: row.stopDisplay,
        startedAt: row.startedAt,
        endedAt: row.endedAt,
        durationMs: row.durationMs,
      })),
      nextAfter: page?.nextAfter ?? null,
    };
  } catch {
    return { children: [], nextAfter: null };
  }
}

/** recovery 恢复事实（真实端点）：服务端已按角色投影；从未恢复→null。 */
export async function taskRecovery(taskId: string, _role: DemoRole): Promise<DemoRecoveryProjection | null> {
  if (!taskId) return null;
  const api = useApi();
  try {
    const detail = await api<any>(`/v1/tasks/${encodeURIComponent(taskId)}`);
    const recovery = detail?.recovery;
    if (!recovery) return null;
    return {
      recoverySource: recovery.recoverySource,
      reasonCode: recovery.reasonCode,
      reasonSummary: recovery.reasonSummary,
      checkpoint: recovery.checkpoint,
      reusedSteps: recovery.reusedSteps,
      blockedReasons: recovery.blockedReasons,
    };
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------
// 锚点定位与压缩区间（纯投影，自 mock-session-detail 迁出；§5.7 三态语义不变）。
// ---------------------------------------------------------------------------

export interface CompactionRange {
  fromSeq: number;
  toSeq: number;
  tokensBefore: number;
  tokensAfter: number;
}

/** 压缩覆盖区间（由 compaction/start..end 对推导；普通视图据此遮蔽被覆盖事件）。 */
export function compactionRanges(events: DemoSessionEvent[]): CompactionRange[] {
  const start = events.find((event) => event.type === "compaction/start");
  const end = events.find((event) => event.type === "compaction/end");
  if (!start || !end) return [];
  const fromSeq = Number(start.data.fromSeq);
  const toSeq = Number(start.data.toSeq);
  return [
    {
      fromSeq,
      toSeq,
      tokensBefore: Number(start.data.tokensBefore ?? end.data.tokensBefore ?? 0),
      tokensAfter: Number(end.data.tokensAfter ?? start.data.tokensAfter ?? 0),
    },
  ];
}

export type CallAnchorView =
  | { mode: "jump"; seq: number }
  | { mode: "hidden"; reason: "no-session" | "no-visible-event" | "shadowed" };

/** 调用锚点解析：三字段齐备才可跳；不可见（被压缩覆盖）→hidden/shadowed 如实降级。 */
export function callAnchor(
  call: Pick<DemoCallRow, "appCode" | "sessionId" | "eventSeq">,
  view: { surfaceOnly: boolean },
  visibleSeqs?: ReadonlySet<number>,
): CallAnchorView {
  if (!call.appCode || !call.sessionId || call.eventSeq == null) {
    return call.appCode && call.sessionId
      ? { mode: "hidden", reason: "no-visible-event" }
      : { mode: "hidden", reason: "no-session" };
  }
  if (view.surfaceOnly && visibleSeqs && !visibleSeqs.has(call.eventSeq))
    return { mode: "hidden", reason: "shadowed" };
  return { mode: "jump", seq: call.eventSeq };
}
