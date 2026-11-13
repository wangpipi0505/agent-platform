/**
 * F3-6（台账 §7.5）：会话详情与步骤调用下钻——mock 契约（demo:true，仅演示装载）。
 * 形状逐字段对齐 H1-9 已交付端点与 §5.7 调用列表：
 *  - GET /v1/apps/{code}/sessions/{id}（快照：元数据+messages+eventWatermark，接口稿 §5.1）
 *  - GET /v1/apps/{code}/sessions/{id}/events?afterSeq&surfaceOnly（事件增量：普通/诊断双视图+过滤游标）
 *  - GET /v1/tasks/{taskId}/steps/{stepId}/calls（调用列表：callId/skillCode/status/errorSummary/
 *    appCode/sessionId/eventSeq/reconciliation?，§5.7）
 *  - GET /v1/sessions/{id}/subagents（子代理目录：children+stopReason，§5.3）
 *  - recovery 恢复事实角色投影（contracts/h1-6-h1-8-stubs.md §3：USER 三字段/OPERATOR 全量，接口稿 §5.2）
 *  - 直链拒绝：403+审计（接口稿 §5.1/§5.6：已认证主体直链访问无权资源返回 403 并记审计）
 * 独立文件不接 mock-transport（红线：不向 mock-transport.ts 单文件堆场景）；页面直接消费本模块
 * 导出函数，联调期整体替换为真实端点（替换点逐函数标注）。
 */

export type DemoRole = "USER" | "OPERATOR";

/** H1-9 事件增量条目形状：{seq, type, occurredAt, data[, surfaceOp]}。 */
export interface DemoSessionEvent {
  seq: number;
  type: string;
  occurredAt: string;
  data: Record<string, unknown>;
}

/** 事件增量页（接口稿 §5.1 游标语义：nextAfterSeq=本页最后扫描到的原始 seq，含被过滤跳过事件，不 +1）。 */
export interface DemoEventsPage {
  sessionId: string;
  events: DemoSessionEvent[];
  nextAfterSeq: number;
  truncated: boolean;
}

/** §5.7 调用列表行：错误摘要由 error_semantic 映射；reconciliation 仅授权 OPERATOR/ADMIN 可见。 */
export interface DemoCallRow {
  taskId: string;
  stepId: string;
  callId: string;
  skillCode: string;
  skillVersion: number;
  status: string;
  errorSummary: string | null;
  appCode: string | null;
  sessionId: string | null;
  eventSeq: number | null;
  startedAt: string;
  durationMs: number;
  reconciliation?: { state: string; reasonCode: string; checkedAt: string };
}

/** §5.3 子代理目录行：USER 停因=展示摘要，OPERATOR=终态原因码（同字段按角色投影）。 */
export interface DemoSubagentRow {
  childSessionId: string;
  label: string;
  status: string;
  stopReason: string;
  durationMs: number;
  startedAt: string;
}

/** RecoveryFact（contracts/h1-6-h1-8-stubs.md §3）：USER 仅 source/code/summary，OPERATOR 另见附加分区。 */
export interface DemoRecoveryFact {
  recoverySource: "MANUAL" | "AUTO";
  reasonCode: string;
  reasonSummary: string;
  checkpoint: { eventWatermark: number; runRefs: string[] };
  reusedSteps: { stepId: string; resultDigest: string }[];
  blockedReasons: string[];
}

/** 角色投影出口（接口稿 §5.2）：USER=恰三字段子集；OPERATOR/ADMIN=全量事实。 */
export type DemoRecoveryProjection = Pick<DemoRecoveryFact, "recoverySource" | "reasonCode" | "reasonSummary"> &
  Partial<Pick<DemoRecoveryFact, "checkpoint" | "reusedSteps" | "blockedReasons">>;

/** 直链/诊断门拒绝形状（403）与资源缺失（404）：mock 按错误信封边界如实返回。 */
export interface DemoReject {
  error: string;
  httpStatus: number;
  category: "AUTH" | "PARAM";
  userMessage: string;
  traceId: string;
  audit: DemoAuditRecord;
}

export interface DemoAuditRecord {
  at: string;
  action: string;
  tenant: string;
  principal: string;
  appCode: string;
  sessionId: string;
  reason: string;
}

/** 快照（H1-9 GET 会话）：普通视图 messages 投影+eventWatermark，客户端以 seq>watermark 续接。 */
export interface DemoSnapshot {
  sessionId: string;
  appCode: string;
  title: string;
  state: string;
  principalId: string;
  linkedTaskId: string;
  eventWatermark: number;
  demo: true;
  messages: { seq: number; role: string; text: string; occurredAt: string }[];
}

// ---------------------------------------------------------------------------
// 演示种子：同一会话 sess-demo-28 供双视角走查（压缩对+tool 配对+诊断事件齐全）；
// calls 覆盖锚点四态（真实 Flow 锚点/计划步骤锚点/无会话/无可见事件）；task-demo-95 挂
// 恢复事实。occurredAt 固定值保证"同输入同输出"（刷新重查锚点确定性）。
// ---------------------------------------------------------------------------

const OCC = (seq: number) => `2026-11-13T08:${String(5 + Math.floor(seq / 6)).padStart(2, "0")}:${String((seq * 7) % 60).padStart(2, "0")}Z`;

interface DemoSessionSeed {
  sessionId: string;
  appCode: string;
  title: string;
  state: string;
  principalId: string;
  linkedTaskId: string;
  demo: true;
  /** 原始事件存储=诊断视图事实源（surfaceOnly=false 才整包可见）。 */
  events: DemoSessionEvent[];
}

/** 诊断视图专用类型（接口稿 §5.1 USER 不可读清单，与 engine SURFACE_HIDDEN 同目录）。 */
const DIAGNOSTIC_ONLY = new Set([
  "system/message",
  "request/header",
  "request/context",
  "assistant/attempt",
]);

const rawEvents: DemoSessionEvent[] = [
  { seq: 1, type: "turn/start", occurredAt: OCC(1), data: { turn: 1 } },
  { seq: 2, type: "user/message", occurredAt: OCC(2), data: { turn: 1, role: "user", text: "对同一数据集运行两种识别算法并生成差异报告" } },
  { seq: 3, type: "step/start", occurredAt: OCC(3), data: { stepId: "detect_a", kind: "FLOW" } },
  { seq: 4, type: "tool/call", occurredAt: OCC(4), data: { callId: "call-a01", name: "detect-a", args: { datasetRef: "artifact-51", threshold: 0.7, rawPayload: "〔完整原始参数·USER 不可读〕" }, skillVersionId: "detect-a@3", idempotencyKey: "idem-a01" } },
  { seq: 5, type: "tool/result", occurredAt: OCC(5), data: { callId: "call-a01", result: { reportRef: "artifact-52", rawRows: "〔完整原始结果·USER 不可读〕" }, resultSummary: "识别结果 artifact-52（12 目标）", meta: { status: "SUCCEEDED", durationMs: 4300 } } },
  { seq: 6, type: "step/end", occurredAt: OCC(6), data: { stepId: "detect_a", state: "SUCCEEDED" } },
  { seq: 7, type: "step/start", occurredAt: OCC(7), data: { stepId: "notify_ext", kind: "FLOW" } },
  { seq: 8, type: "tool/call", occurredAt: OCC(8), data: { callId: "call-a02", name: "notify-ext", args: { externalRef: "EXT-77", payload: "〔完整原始参数·USER 不可读〕" }, skillVersionId: "notify-ext@3", idempotencyKey: "idem-a02" } },
  { seq: 9, type: "tool/result", occurredAt: OCC(9), data: { callId: "call-a02", error: "外部任务 EXT-77 回执未决", meta: { status: "WAITING_RECONCILIATION", durationMs: 1200 } } },
  { seq: 10, type: "step/end", occurredAt: OCC(10), data: { stepId: "notify_ext", state: "WAITING_RECONCILIATION" } },
  { seq: 11, type: "compaction/start", occurredAt: OCC(11), data: { fromSeq: 2, toSeq: 10, tokensBefore: 18600 } },
  { seq: 12, type: "compaction/summary", occurredAt: OCC(12), data: { summary: "已压缩第 1 轮检测与外部通知对话：两次工具调用（detect-a/notify-ext）与中间结论合并为摘要；外部任务 EXT-77 回执仍待对账。〔压缩正文·USER 不可读〕", fromSeq: 2, toSeq: 10, tokensBefore: 18600, tokensAfter: 4300, modelCalls: 2, model: "deepseek-chat" } },
  { seq: 13, type: "compaction/end", occurredAt: OCC(13), data: { fromSeq: 2, toSeq: 10, tokensBefore: 18600, tokensAfter: 4300 } },
  { seq: 14, type: "assistant/message", occurredAt: OCC(14), data: { turn: 1, role: "assistant", text: "阶段结论：主算法完成识别（结果 artifact-52），外部通知 EXT-77 回执未决，已转入待对账。" } },
  { seq: 15, type: "turn/end", occurredAt: OCC(15), data: { turn: 1, reason: "completed" } },
  { seq: 16, type: "turn/start", occurredAt: OCC(16), data: { turn: 2 } },
  { seq: 17, type: "request/header", occurredAt: OCC(17), data: { model: "deepseek-chat", tools: ["detect-a@3", "detect-b@2", "compare-report@2"], promptDigest: "p-9" } },
  { seq: 18, type: "step/start", occurredAt: OCC(18), data: { stepId: "detect_b", kind: "PLAN" } },
  { seq: 19, type: "tool/call", occurredAt: OCC(19), data: { callId: "call-p31", name: "detect-b", args: { datasetRef: "artifact-51", threshold: 0.82 }, skillVersionId: "detect-b@2", idempotencyKey: "idem-p31" } },
  { seq: 20, type: "tool/result", occurredAt: OCC(20), data: { callId: "call-p31", result: { reportRef: "artifact-53" }, resultSummary: "识别结果 artifact-53（9 目标）", meta: { status: "SUCCEEDED", durationMs: 3800 } } },
  { seq: 21, type: "step/end", occurredAt: OCC(21), data: { stepId: "detect_b", state: "SUCCEEDED" } },
  { seq: 22, type: "step/start", occurredAt: OCC(22), data: { stepId: "compare", kind: "PLAN" } },
  { seq: 23, type: "tool/call", occurredAt: OCC(23), data: { callId: "call-f23", name: "compare-report", args: { leftRef: "artifact-52", rightRef: "artifact-53" }, skillVersionId: "compare-report@2", idempotencyKey: "idem-f23" } },
  { seq: 24, type: "tool/result", occurredAt: OCC(24), data: { callId: "call-f23", error: "OUTPUT_SCHEMA_INVALID：结果缺少 diff 字段", meta: { status: "FAILED", durationMs: 640 } } },
  { seq: 25, type: "step/end", occurredAt: OCC(25), data: { stepId: "compare", state: "FAILED" } },
  { seq: 26, type: "system/message", occurredAt: OCC(26), data: { role: "system", text: "上下文预算提醒：当前用量 43%〔诊断事件·USER 不可读〕" } },
  { seq: 27, type: "assistant/message", occurredAt: OCC(27), data: { turn: 2, role: "assistant", text: "差异报告生成失败（compare-report 结果缺少 diff 字段），已保留两个识别结果。" } },
  { seq: 28, type: "turn/end", occurredAt: OCC(28), data: { turn: 2, reason: "completed" } },
];

const sessions: DemoSessionSeed[] = [
  {
    sessionId: "sess-demo-28",
    appCode: "recon-assistant",
    title: "对同一数据集运行两种识别算法并生成差异报告",
    state: "IDLE",
    principalId: "u-demo",
    linkedTaskId: "task-demo-95",
    demo: true,
    events: rawEvents,
  },
  {
    // 直链拒绝审计用例：他人会话 + 未授权工作区，两角色均 403。
    sessionId: "sess-demo-forbidden",
    appCode: "vault-assistant",
    title: "他人会话（未授权工作区）",
    state: "IDLE",
    principalId: "u-other",
    linkedTaskId: "task-demo-other",
    demo: true,
    events: [{ seq: 1, type: "user/message", occurredAt: OCC(1), data: { role: "user", text: "内部资料整理" } }],
  },
];

/** §5.7 calls 存储：锚点四态+计划步骤锚点+压缩遮蔽锚点（eventSeq 指向被压缩覆盖区间）。 */
const callRows: (DemoCallRow & { demo: true })[] = [
  { taskId: "task-demo-95", stepId: "detect_a", callId: "call-a01", skillCode: "detect-a", skillVersion: 3, status: "SUCCEEDED", errorSummary: null, appCode: "recon-assistant", sessionId: "sess-demo-28", eventSeq: 4, startedAt: "2026-11-13T08:05:12Z", durationMs: 4300, demo: true },
  { taskId: "task-demo-95", stepId: "notify_ext", callId: "call-a02", skillCode: "notify-ext", skillVersion: 3, status: "WAITING_RECONCILIATION", errorSummary: "外部任务 EXT-77 回执未决", appCode: "recon-assistant", sessionId: "sess-demo-28", eventSeq: 8, startedAt: "2026-11-13T08:06:20Z", durationMs: 1200, reconciliation: { state: "PENDING", reasonCode: "EXTERNAL_RESULT_UNKNOWN", checkedAt: "2026-11-13T08:31:00Z" }, demo: true },
  { taskId: "task-demo-95", stepId: "compare", callId: "call-f23", skillCode: "compare-report", skillVersion: 2, status: "FAILED", errorSummary: "OUTPUT_SCHEMA_INVALID：结果缺少 diff 字段", appCode: "recon-assistant", sessionId: "sess-demo-28", eventSeq: 23, startedAt: "2026-11-13T08:12:44Z", durationMs: 640, demo: true },
  { taskId: "task-demo-96", stepId: "detect_b", callId: "call-p31", skillCode: "detect-b", skillVersion: 2, status: "SUCCEEDED", errorSummary: null, appCode: "recon-assistant", sessionId: "sess-demo-28", eventSeq: 19, startedAt: "2026-11-13T08:09:03Z", durationMs: 3800, demo: true },
  { taskId: "task-demo-97", stepId: "archive_copy", callId: "call-n41", skillCode: "archive-copy", skillVersion: 1, status: "FAILED", errorSummary: "独立任务无会话关联，事件锚点不可用", appCode: null, sessionId: null, eventSeq: null, startedAt: "2026-11-13T08:15:02Z", durationMs: 210, demo: true },
  { taskId: "task-demo-95", stepId: "notify_ext", callId: "call-n42", skillCode: "notify-ext", skillVersion: 3, status: "WAITING_RECONCILIATION", errorSummary: "外部回执不可查证，保留待对账", appCode: "recon-assistant", sessionId: "sess-demo-28", eventSeq: null, startedAt: "2026-11-13T08:07:41Z", durationMs: 90, reconciliation: { state: "TRACKED", reasonCode: "EXTERNAL_RESULT_UNKNOWN", checkedAt: "2026-11-13T08:32:00Z" }, demo: true },
];

const recoveryFacts: Record<string, DemoRecoveryFact | null> = {
  // H1-8 事实出口（contracts/h1-6-h1-8-stubs.md §3）：重启后可重复取得同一事实（确定性种子）。
  "task-demo-95": {
    recoverySource: "AUTO",
    reasonCode: "LEASE_EXPIRED",
    reasonSummary: "执行租约到期后已接续",
    checkpoint: { eventWatermark: 13, runRefs: ["run-551"] },
    reusedSteps: [{ stepId: "detect_a", resultDigest: "ab41" }],
    blockedReasons: ["外部任务 EXT-77 回执未决"],
  },
  "task-demo-96": null,
  "task-demo-97": null,
};

const subagentRows: DemoSubagentRow[] = [
  { childSessionId: "sess-demo-28-c1", label: "算法候选探索", status: "SUCCEEDED", stopReason: "COMPLETE", durationMs: 8300, startedAt: "2026-11-13T08:10:00Z" },
  { childSessionId: "sess-demo-28-c2", label: "参数映射复核", status: "FAILED", stopReason: "DEPTH_LIMIT", durationMs: 4100, startedAt: "2026-11-13T08:11:30Z" },
];

/** USER 停因展示摘要映射（§5.3：USER 停因映射为展示摘要；OPERATOR 可见终态原因码）。 */
const STOP_REASON_LABELS: Record<string, string> = {
  COMPLETE: "正常完成",
  DEPTH_LIMIT: "深度受限停止",
  CANCELLED: "已取消",
  ERROR: "异常停止",
};

// ---------------------------------------------------------------------------
// 拒绝审计（接口稿 §5.1/§5.6：403 记拒绝原因；engine 无独立审计表时同口径落可追溯记录）。
// ---------------------------------------------------------------------------

let auditRecords: DemoAuditRecord[] = [];
const principalOf = (role: DemoRole) => (role === "USER" ? "u-demo" : "op-demo");
function recordRejection(action: string, role: DemoRole, appCode: string, sessionId: string, reason: string): DemoReject {
  const audit: DemoAuditRecord = {
    at: "2026-11-13T08:35:00Z",
    action,
    tenant: "t-demo",
    principal: principalOf(role),
    appCode,
    sessionId,
    reason,
  };
  auditRecords = [...auditRecords, audit];
  return {
    error: action,
    httpStatus: 403,
    category: "AUTH",
    userMessage:
      action === "SESSION_DIAGNOSTIC_VIEW_FORBIDDEN"
        ? "诊断视图仅 OPERATOR/ADMIN 可用，已记录拒绝审计"
        : "当前账号没有权限读取该会话，已记录拒绝审计",
    traceId: "trace-demo-f36",
    audit,
  };
}

/** 审计只读出口（页面/测试断言"直链拒绝并记审计"）。 */
export function auditTrail(): DemoAuditRecord[] {
  return [...auditRecords];
}

// ---------------------------------------------------------------------------
// 事件投影：普通视图（surfaceOnly=true，默认）按白名单类型+字段级展示投影；
// 诊断视图（surfaceOnly=false，仅 OPERATOR/ADMIN）整包返回（含压缩正文与原始材料）。
// 压缩遮蔽：被压缩覆盖区间内的原始事件不参与普通投影，仅保留压缩标记（区间+token 计数）。
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

const argsSummaryOf = (args: unknown): string => {
  if (!args || typeof args !== "object") return String(args ?? "");
  return Object.entries(args as Record<string, unknown>)
    .filter(([key]) => key !== "rawPayload" && key !== "payload")
    .map(([key, value]) => `${key}=${String(value)}`)
    .join("；")
    .slice(0, 80);
};

/** 普通视图单事件字段投影（接口稿 §5.1 白名单：工具名+参数摘要、结果摘要、压缩标记无正文）。 */
function projectEventData(event: DemoSessionEvent): Record<string, unknown> {
  if (event.type === "tool/call") {
    const data = event.data;
    return {
      callId: data.callId,
      name: data.name,
      argsSummary: argsSummaryOf(data.args),
    };
  }
  if (event.type === "tool/result") {
    const data = event.data;
    const meta = (data.meta ?? {}) as Record<string, unknown>;
    const projected: Record<string, unknown> = {
      callId: data.callId,
      meta: { status: meta.status, durationMs: meta.durationMs },
    };
    if (typeof data.resultSummary === "string") projected.resultSummary = data.resultSummary;
    if (typeof data.error === "string") projected.error = data.error;
    return projected;
  }
  if (event.type === "compaction/summary") {
    // 压缩标记摘要：区间+token 计数；不含 summary 正文与模型调用细节（modelCalls/model）。
    const data = event.data;
    return {
      fromSeq: data.fromSeq,
      toSeq: data.toSeq,
      tokensBefore: data.tokensBefore,
      tokensAfter: data.tokensAfter,
    };
  }
  return event.data;
}

/** 普通/诊断双视图投影（role 仅用于诊断门；普通视图投影对所有可读者一致）。 */
export function projectDemoEvents(
  events: DemoSessionEvent[],
  options: { surfaceOnly?: boolean } = {},
): DemoSessionEvent[] {
  const surface = options.surfaceOnly !== false;
  const covered = compactionRanges(events);
  return events
    .filter((event) => {
      if (surface && DIAGNOSTIC_ONLY.has(event.type)) return false;
      if (surface && covered.some((range) => event.seq >= range.fromSeq && event.seq <= range.toSeq))
        return false; // 被压缩覆盖的原始事件不参与普通投影（仅保留压缩标记）
      return true;
    })
    .map((event) =>
      surface ? { ...event, data: projectEventData(event) } : { ...event, data: { ...event.data } },
    );
}

function findSession(appCode: string, sessionId: string): DemoSessionSeed | undefined {
  return sessions.find(
    (session) =>
      session.sessionId === sessionId && (!appCode || session.appCode === appCode),
  );
}

/** 会话读授权（demo 口径）：USER 仅本人；OPERATOR 限已授权工作区。 */
function readForbidden(session: DemoSessionSeed, role: DemoRole): string | null {
  if (role === "USER" && session.principalId !== "u-demo")
    return "会话归属本人口径不满足";
  if (role === "OPERATOR" && !["recon-assistant", "satellite-planning-assistant"].includes(session.appCode))
    return "工作区未授权";
  return null;
}

// ---------------------------------------------------------------------------
// 端点形状函数（页面直接消费；联调期替换为真实 HTTP 端点）。
// ---------------------------------------------------------------------------

/** GET 快照（H1-9）：messages=普通视图消息投影（被压缩覆盖的消息不参与）；403 记审计、404 如实。 */
export function sessionSnapshot(
  appCode: string,
  sessionId: string,
  role: DemoRole,
): DemoSnapshot | DemoReject {
  const session = findSession(appCode, sessionId);
  if (!session)
    return {
      error: "SESSION_NOT_FOUND",
      httpStatus: 404,
      category: "PARAM",
      userMessage: "会话不存在或路径归属不匹配",
      traceId: "trace-demo-f36",
      audit: {
        at: "2026-11-13T08:35:00Z",
        action: "SESSION_NOT_FOUND",
        tenant: "t-demo",
        principal: principalOf(role),
        appCode,
        sessionId,
        reason: "资源不存在",
      },
    };
  const forbidden = readForbidden(session, role);
  if (forbidden) return recordRejection("SESSION_READ_FORBIDDEN", role, session.appCode, sessionId, forbidden);
  // 普通视图消息投影：被压缩覆盖的原始消息不参与（接口稿 §5.1），仅保留压缩标记（页面由事件流渲染）。
  const messages = projectDemoEvents(session.events, { surfaceOnly: true })
    .filter((event) => event.type === "user/message" || event.type === "assistant/message")
    .map((event) => ({
      seq: event.seq,
      role: String(event.data.role ?? (event.type === "user/message" ? "user" : "assistant")),
      text: String(event.data.text ?? ""),
      occurredAt: event.occurredAt,
    }));
  return {
    sessionId: session.sessionId,
    appCode: session.appCode,
    title: session.title,
    state: session.state,
    principalId: session.principalId,
    linkedTaskId: session.linkedTaskId,
    eventWatermark: session.events.at(-1)?.seq ?? 0,
    demo: true,
    messages,
  };
}

/**
 * GET 事件增量（H1-9）：afterSeq 排他；nextAfterSeq=本页最后扫描到的原始 seq（含被普通视图
 * 过滤跳过的事件，不 +1）；整页全被过滤时 events 为空但游标照常推进；truncated=水位之外还有。
 * surfaceOnly=false（诊断视图）仅 OPERATOR/ADMIN；USER 显式请求 403 并记拒绝审计。
 */
export function sessionEvents(
  appCode: string,
  sessionId: string,
  query: { afterSeq?: number; limit?: number; surfaceOnly?: boolean } = {},
  role: DemoRole,
): DemoEventsPage | DemoReject {
  const session = findSession(appCode, sessionId);
  if (!session)
    return {
      error: "SESSION_NOT_FOUND",
      httpStatus: 404,
      category: "PARAM",
      userMessage: "会话不存在或路径归属不匹配",
      traceId: "trace-demo-f36",
      audit: {
        at: "2026-11-13T08:35:00Z",
        action: "SESSION_NOT_FOUND",
        tenant: "t-demo",
        principal: principalOf(role),
        appCode,
        sessionId,
        reason: "资源不存在",
      },
    };
  const forbidden = readForbidden(session, role);
  if (forbidden) return recordRejection("SESSION_READ_FORBIDDEN", role, session.appCode, sessionId, forbidden);
  const surface = query.surfaceOnly !== false;
  if (!surface && role === "USER")
    return recordRejection(
      "SESSION_DIAGNOSTIC_VIEW_FORBIDDEN",
      role,
      session.appCode,
      sessionId,
      "surfaceOnly=false requires OPERATOR/ADMIN",
    );
  const afterSeq = query.afterSeq ?? 0;
  const limit = Math.min(Math.max(query.limit ?? 200, 1), 1000);
  const scanned = session.events.filter((event) => event.seq > afterSeq).slice(0, limit);
  let nextAfterSeq = afterSeq;
  const rows: DemoSessionEvent[] = [];
  for (const event of scanned) {
    nextAfterSeq = event.seq;
    if (surface && DIAGNOSTIC_ONLY.has(event.type)) continue;
    if (
      surface &&
      compactionRanges(session.events).some(
        (range) => event.seq >= range.fromSeq && event.seq <= range.toSeq,
      )
    )
      continue;
    rows.push(surface ? { ...event, data: projectEventData(event) } : { ...event, data: { ...event.data } });
  }
  const watermark = session.events.at(-1)?.seq ?? 0;
  return { sessionId: session.sessionId, events: rows, nextAfterSeq, truncated: watermark > nextAfterSeq };
}

/** §5.7 调用列表：按 callId 稳定排序；reconciliation 仅 OPERATOR 投影；callId 直查用于刷新重定位。 */
export function stepCalls(
  taskId: string,
  stepId: string,
  role: DemoRole,
  opts: { callId?: string } = {},
): { calls: DemoCallRow[]; total: number } {
  const rows = callRows
    .filter((row) => row.taskId === taskId && row.stepId === stepId)
    .filter((row) => !opts.callId || row.callId === opts.callId)
    .sort((a, b) => a.callId.localeCompare(b.callId))
    .map((row) => {
      const projected: DemoCallRow = {
        taskId: row.taskId,
        stepId: row.stepId,
        callId: row.callId,
        skillCode: row.skillCode,
        skillVersion: row.skillVersion,
        status: row.status,
        errorSummary: row.errorSummary,
        appCode: row.appCode,
        sessionId: row.sessionId,
        eventSeq: row.eventSeq,
        startedAt: row.startedAt,
        durationMs: row.durationMs,
      };
      if (role === "OPERATOR" && row.reconciliation) projected.reconciliation = { ...row.reconciliation };
      return projected;
    });
  return { calls: rows, total: rows.length };
}

/**
 * 会话维度调用聚合（页面下钻表数据源；联调期由 §5.7 端点按 taskId/stepId 分页聚合替换）。
 * 演示聚合=关联本会话的调用 + 独立任务的无锚点调用（call-n41，sessionId=null）——
 * 用于走查"无锚点保留错误摘要、隐藏跳转"形态；真实端点无需该并入（列表按任务查询）。
 */
export function sessionCalls(
  sessionId: string,
  role: DemoRole,
): { calls: DemoCallRow[]; total: number } {
  const rows = callRows
    .filter((row) => row.sessionId === sessionId || row.sessionId === null)
    .sort((a, b) => a.callId.localeCompare(b.callId))
    .map((row) => stepCalls(row.taskId, row.stepId, role, { callId: row.callId }).calls[0]);
  return { calls: rows, total: rows.length };
}

/** GET 子代理目录（§5.3）：不返回提示词/参数/正文；USER 停因=展示摘要，OPERATOR=原因码。 */
export function sessionSubagents(
  sessionId: string,
  role: DemoRole,
): { children: DemoSubagentRow[]; nextAfter: string | null } {
  const isOwnerSession = sessionId === "sess-demo-28";
  const children = (isOwnerSession ? subagentRows : [])
    .slice()
    .sort((a, b) => a.startedAt.localeCompare(b.startedAt) || a.childSessionId.localeCompare(b.childSessionId))
    .map((row) => ({
      ...row,
      stopReason: role === "USER" ? STOP_REASON_LABELS[row.stopReason] ?? row.stopReason : row.stopReason,
    }));
  return { children, nextAfter: null };
}

/**
 * recovery 恢复事实角色投影（接口稿 §5.2 / contracts §3）：
 * USER 恰三字段（recoverySource/reasonCode/reasonSummary，不返回检查点与内部运行引用）；
 * OPERATOR/ADMIN 全量（另含 checkpoint/reusedSteps/blockedReasons）。从未恢复返回 null。
 */
export function taskRecovery(taskId: string, role: DemoRole): DemoRecoveryProjection | null {
  const fact = recoveryFacts[taskId];
  if (!fact) return null;
  if (role === "USER")
    return {
      recoverySource: fact.recoverySource,
      reasonCode: fact.reasonCode,
      reasonSummary: fact.reasonSummary,
    };
  return {
    recoverySource: fact.recoverySource,
    reasonCode: fact.reasonCode,
    reasonSummary: fact.reasonSummary,
    checkpoint: { ...fact.checkpoint, runRefs: [...fact.checkpoint.runRefs] },
    reusedSteps: fact.reusedSteps.map((step) => ({ ...step })),
    blockedReasons: [...fact.blockedReasons],
  };
}

/** 测试隔离：清空审计并复位种子引用（种子本身不可变，无需深拷贝回填）。 */
export function resetSessionDetailDemo(): void {
  auditRecords = [];
}

// ---------------------------------------------------------------------------
// 锚点定位（页面与测试共用；§5.7：无会话或目标会话无权访问→appCode/sessionId/eventSeq 均空；
// 会话可读但无可见事件→eventSeq=null，页面保留错误摘要并隐藏跳转；OPERATOR 可定位被压缩
// 覆盖的事件，USER 仅见压缩标记）。
// ---------------------------------------------------------------------------

export type CallAnchorView =
  | { mode: "jump"; seq: number }
  | { mode: "hidden"; reason: "no-session" | "no-visible-event" | "shadowed" };

/**
 * 调用锚点解析：三字段齐备才可跳；eventSeq 为空→保留摘要隐藏跳转；锚点事件在当前视角
 * 投影中不可见（被压缩覆盖）→USER 隐藏跳转仅见标记，OPERATOR（诊断视图）仍可定位。
 */
export function callAnchor(
  call: Pick<DemoCallRow, "appCode" | "sessionId" | "eventSeq">,
  view: { surfaceOnly: boolean },
  visibleSeqs?: ReadonlySet<number>,
): CallAnchorView {
  if (!call.appCode || !call.sessionId || call.eventSeq == null) {
    // 无会话关联或会话可读但无可见事件：三字段不齐→隐藏跳转（错误摘要保留在行上）
    return call.appCode && call.sessionId
      ? { mode: "hidden", reason: "no-visible-event" }
      : { mode: "hidden", reason: "no-session" };
  }
  if (view.surfaceOnly && visibleSeqs && !visibleSeqs.has(call.eventSeq))
    return { mode: "hidden", reason: "shadowed" };
  return { mode: "jump", seq: call.eventSeq };
}
