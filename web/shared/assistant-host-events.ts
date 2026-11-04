/**
 * 标准结果事件信封与通道边界（contracts/assistant-integration.md §4）。
 *
 * 本模块不依赖 DOM、Vue 或 SDK 内部状态，SDK、webapp 与宿主可共用：
 * - 七个固定事件名与固定字段；
 * - 严格校验：未知 schemaVersion/事件/字段、超长、超大小一律拒绝；
 * - 同页回调与 iframe 通道（精确 targetOrigin、origin/source/nonce/实例校验）；
 * - 一次权威终态只产生一次 result；消息文本不是业务已提交依据。
 */

export const HOST_EVENT_SCHEMA_VERSION = "1.0" as const;

export const HOST_EVENT_NAMES = [
  "ready",
  "opened",
  "closed",
  "run-started",
  "approval-required",
  "result",
  "error",
] as const;
export type HostEventName = (typeof HOST_EVENT_NAMES)[number];

export const HOST_EVENT_STATUSES = [
  "SUCCEEDED",
  "FAILED",
  "UNKNOWN",
  "WAITING_APPROVAL",
  "CANCELLED",
] as const;
export type HostEventStatus = (typeof HOST_EVENT_STATUSES)[number];

export type HostRecordRef = { source: string; recordId: string };
export type HostFileRef = {
  artifactId: number;
  name: string;
  sizeBytes?: number;
};
export type HostEventError = {
  code: string;
  message?: string;
  retryable?: boolean;
};

/** Never carries functions, DOM nodes, window handles or navigation URLs. */
export type HostEventEnvelope = {
  schemaVersion: "1.0";
  event: HostEventName;
  integrationId: string;
  appCode: string;
  traceId: string;
  occurredAt: string;
  sessionId?: string;
  runId?: string;
  configReleaseId?: string;
  status?: HostEventStatus;
  structured?: Record<string, unknown>;
  fileRefs?: HostFileRef[];
  businessRecordRefs?: HostRecordRef[];
  error?: HostEventError;
};

/** 单事件序列化上限；schema 字段上限之外的实现侧硬限制。 */
export const HOST_EVENT_MAX_BYTES = 8192;
export const HOST_EVENT_MAX_STRUCTURED_PROPERTIES = 32;
export const HOST_EVENT_MAX_REFS = 16;

export const HOST_CHANNEL_EVENT_TYPE = "agent-platform:assistant:event";
export const HOST_CHANNEL_HELLO_TYPE = "agent-platform:assistant:hello";
export const INTEGRATION_EVENT_CHANNEL_INVALID =
  "INTEGRATION_EVENT_CHANNEL_INVALID";

const KEY_PATTERN = /^[A-Za-z0-9._-]+$/;
const CHANNEL_ID_PATTERN = /^[A-Za-z0-9._:-]{8,128}$/;
const ORIGIN_PATTERN = /^https?:\/\/[^/?#]+$/;

export type HostEventRejection = {
  ok: false;
  code: typeof INTEGRATION_EVENT_CHANNEL_INVALID;
  reason: string;
};
export type HostEventAcceptance =
  | { ok: true; event: HostEventEnvelope }
  | HostEventRejection;

const ENVELOPE_KEYS = new Set([
  "schemaVersion",
  "event",
  "integrationId",
  "appCode",
  "traceId",
  "occurredAt",
  "sessionId",
  "runId",
  "configReleaseId",
  "status",
  "structured",
  "fileRefs",
  "businessRecordRefs",
  "error",
]);

function reject(reason: string): HostEventRejection {
  return { ok: false, code: INTEGRATION_EVENT_CHANNEL_INVALID, reason };
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isChannelId(value: unknown): value is string {
  return typeof value === "string" && CHANNEL_ID_PATTERN.test(value);
}

function optionalText(
  value: unknown,
  max: number,
  label: string,
): { ok: true; value?: string } | HostEventRejection {
  if (value === undefined) return { ok: true };
  if (typeof value !== "string") return reject(label + "必须是字符串");
  if (value.length > max) return reject(label + "超过长度上限");
  return { ok: true, value };
}

function requiredText(
  value: unknown,
  max: number,
  label: string,
): { ok: true; value: string } | HostEventRejection {
  if (typeof value !== "string" || !value) return reject(label + "缺失");
  if (value.length > max) return reject(label + "超过长度上限");
  return { ok: true, value };
}

function byteLength(value: string): number {
  return new TextEncoder().encode(value).length;
}

function safeJson(value: unknown): boolean {
  try {
    JSON.stringify(value);
    return true;
  } catch {
    return false;
  }
}

function hasUnsafeValue(value: unknown, depth = 0): boolean {
  if (depth > 6) return true;
  if (
    typeof value === "function" ||
    typeof value === "symbol" ||
    typeof value === "bigint"
  )
    return true;
  if (Array.isArray(value)) return value.some((v) => hasUnsafeValue(v, depth + 1));
  if (isObject(value))
    return Object.values(value).some((v) => hasUnsafeValue(v, depth + 1));
  return false;
}

function validateStructured(
  value: unknown,
): { ok: true; value?: Record<string, unknown> } | HostEventRejection {
  if (value === undefined) return { ok: true };
  if (!isObject(value)) return reject("structured 必须是对象");
  const keys = Object.keys(value);
  if (keys.length > HOST_EVENT_MAX_STRUCTURED_PROPERTIES)
    return reject("structured 字段数量超过上限");
  if (hasUnsafeValue(value)) return reject("structured 含不可序列化值");
  if (!safeJson(value)) return reject("structured 无法序列化");
  return { ok: true, value };
}

function validateFileRefs(
  value: unknown,
): { ok: true; value?: HostFileRef[] } | HostEventRejection {
  if (value === undefined) return { ok: true };
  if (!Array.isArray(value)) return reject("fileRefs 必须是数组");
  if (value.length > HOST_EVENT_MAX_REFS) return reject("fileRefs 数量超过上限");
  const refs: HostFileRef[] = [];
  for (const item of value) {
    if (!isObject(item)) return reject("fileRefs 项必须是对象");
    const allowed = new Set(["artifactId", "name", "sizeBytes"]);
    for (const key of Object.keys(item))
      if (!allowed.has(key)) return reject("fileRefs 含未声明字段");
    if (
      typeof item.artifactId !== "number" ||
      !Number.isInteger(item.artifactId) ||
      item.artifactId < 1
    )
      return reject("fileRefs.artifactId 必须是正整数");
    const name = requiredText(item.name, 256, "fileRefs.name");
    if (!name.ok) return name;
    if (
      item.sizeBytes !== undefined &&
      (typeof item.sizeBytes !== "number" ||
        !Number.isInteger(item.sizeBytes) ||
        item.sizeBytes < 0)
    )
      return reject("fileRefs.sizeBytes 必须是非负整数");
    refs.push({
      artifactId: item.artifactId,
      name: name.value,
      ...(item.sizeBytes === undefined ? {} : { sizeBytes: item.sizeBytes }),
    });
  }
  return { ok: true, value: refs };
}

function validateRecordRefs(
  value: unknown,
): { ok: true; value?: HostRecordRef[] } | HostEventRejection {
  if (value === undefined) return { ok: true };
  if (!Array.isArray(value)) return reject("businessRecordRefs 必须是数组");
  if (value.length > HOST_EVENT_MAX_REFS)
    return reject("businessRecordRefs 数量超过上限");
  const refs: HostRecordRef[] = [];
  for (const item of value) {
    if (!isObject(item)) return reject("businessRecordRefs 项必须是对象");
    const allowed = new Set(["source", "recordId"]);
    for (const key of Object.keys(item))
      if (!allowed.has(key)) return reject("businessRecordRefs 含未声明字段");
    const source = requiredText(item.source, 64, "businessRecordRefs.source");
    if (!source.ok) return source;
    const recordId = requiredText(
      item.recordId,
      128,
      "businessRecordRefs.recordId",
    );
    if (!recordId.ok) return recordId;
    refs.push({ source: source.value, recordId: recordId.value });
  }
  return { ok: true, value: refs };
}

function validateError(
  value: unknown,
): { ok: true; value?: HostEventError } | HostEventRejection {
  if (value === undefined) return { ok: true };
  if (!isObject(value)) return reject("error 必须是对象");
  const allowed = new Set(["code", "message", "retryable"]);
  for (const key of Object.keys(value))
    if (!allowed.has(key)) return reject("error 含未声明字段");
  const code = requiredText(value.code, 64, "error.code");
  if (!code.ok) return code;
  const message = optionalText(value.message, 512, "error.message");
  if (!message.ok) return message;
  if (value.retryable !== undefined && typeof value.retryable !== "boolean")
    return reject("error.retryable 必须是布尔值");
  return {
    ok: true,
    value: {
      code: code.value,
      ...(message.value === undefined ? {} : { message: message.value }),
      ...(value.retryable === undefined
        ? {}
        : { retryable: value.retryable as boolean }),
    },
  };
}

/** 严格校验一份宿主事件；失败一律归一为 INTEGRATION_EVENT_CHANNEL_INVALID。 */
export function validateHostEvent(input: unknown): HostEventAcceptance {
  if (!isObject(input)) return reject("事件必须是对象");
  for (const key of Object.keys(input))
    if (!ENVELOPE_KEYS.has(key)) return reject("事件含未声明字段：" + key);

  if (input.schemaVersion !== HOST_EVENT_SCHEMA_VERSION)
    return reject("未知 schemaVersion：" + String(input.schemaVersion));
  if (!HOST_EVENT_NAMES.includes(input.event as HostEventName))
    return reject("未声明的事件名：" + String(input.event));
  const event = input.event as HostEventName;

  const integrationId = requiredText(input.integrationId, 64, "integrationId");
  if (!integrationId.ok) return integrationId;
  if (!KEY_PATTERN.test(integrationId.value))
    return reject("integrationId 字符不合法");
  const appCode = requiredText(input.appCode, 64, "appCode");
  if (!appCode.ok) return appCode;
  if (!KEY_PATTERN.test(appCode.value)) return reject("appCode 字符不合法");
  const traceId = requiredText(input.traceId, 64, "traceId");
  if (!traceId.ok) return traceId;
  const occurredAt = requiredText(input.occurredAt, 64, "occurredAt");
  if (!occurredAt.ok) return occurredAt;
  if (!Number.isFinite(Date.parse(occurredAt.value)))
    return reject("occurredAt 不是有效时间");

  const sessionId = optionalText(input.sessionId, 64, "sessionId");
  if (!sessionId.ok) return sessionId;
  const runId = optionalText(input.runId, 64, "runId");
  if (!runId.ok) return runId;
  const configReleaseId = optionalText(input.configReleaseId, 64, "configReleaseId");
  if (!configReleaseId.ok) return configReleaseId;

  if (
    input.status !== undefined &&
    !HOST_EVENT_STATUSES.includes(input.status as HostEventStatus)
  )
    return reject("未声明的 status");

  const structured = validateStructured(input.structured);
  if (!structured.ok) return structured;
  const fileRefs = validateFileRefs(input.fileRefs);
  if (!fileRefs.ok) return fileRefs;
  const recordRefs = validateRecordRefs(input.businessRecordRefs);
  if (!recordRefs.ok) return recordRefs;
  const error = validateError(input.error);
  if (!error.ok) return error;

  if (event === "result" && (!runId.value || !input.status))
    return reject("result 必须带 runId 与 status");
  if (event === "error" && !error.value)
    return reject("error 事件必须带 error 对象");

  const envelope: HostEventEnvelope = {
    schemaVersion: HOST_EVENT_SCHEMA_VERSION,
    event,
    integrationId: integrationId.value,
    appCode: appCode.value,
    traceId: traceId.value,
    occurredAt: occurredAt.value,
    ...(sessionId.value === undefined ? {} : { sessionId: sessionId.value }),
    ...(runId.value === undefined ? {} : { runId: runId.value }),
    ...(configReleaseId.value === undefined
      ? {}
      : { configReleaseId: configReleaseId.value }),
    ...(input.status === undefined
      ? {}
      : { status: input.status as HostEventStatus }),
    ...(structured.value === undefined ? {} : { structured: structured.value }),
    ...(fileRefs.value === undefined ? {} : { fileRefs: fileRefs.value }),
    ...(recordRefs.value === undefined
      ? {}
      : { businessRecordRefs: recordRefs.value }),
    ...(error.value === undefined ? {} : { error: error.value }),
  };

  let serialized: string;
  try {
    serialized = JSON.stringify(envelope);
  } catch {
    return reject("事件无法序列化");
  }
  if (byteLength(serialized) > HOST_EVENT_MAX_BYTES)
    return reject("事件超过大小上限");

  return { ok: true, event: envelope };
}

export function newChannelId(): string {
  const cryptoApi = globalThis.crypto;
  if (cryptoApi && typeof cryptoApi.randomUUID === "function")
    return cryptoApi.randomUUID();
  return "ch_" + Math.random().toString(36).slice(2) + Date.now().toString(36);
}

export type HostEventBridgeConfig = {
  appCode: string;
  /** 接入档案标识；宿主未提供时回退为 appCode，仅作关联键。 */
  integrationId?: string;
  traceId?: string;
  now?: () => Date;
  /** 同页显式回调（宿主在 init 时注册）。 */
  onEvent?: (event: HostEventEnvelope) => void;
  /** 可选 iframe 发送端；由 webapp 通道或宿主注入。 */
  transport?: (event: HostEventEnvelope) => void;
};

export type HostEventBridge = {
  emit(
    event: HostEventName,
    extra?: Partial<
      Omit<
        HostEventEnvelope,
        "schemaVersion" | "event" | "integrationId" | "appCode" | "traceId" | "occurredAt"
      >
    >,
  ): HostEventEnvelope | null;
  result(
    extra: Partial<
      Omit<
        HostEventEnvelope,
        "schemaVersion" | "event" | "integrationId" | "appCode" | "traceId" | "occurredAt"
      >
    > & { runId: string; status: HostEventStatus },
  ): HostEventEnvelope | null;
  subscribe(listener: (event: HostEventEnvelope) => void): () => void;
  clear(): void;
};

/**
 * 事件桥：只投递通过校验的纯数据信封。
 * 去重规则——同一 run 的 run-started/result 只投递一次，同一审批节点只投递一次，
 * 因此 SSE 重连或重复终态不会造成宿主重复刷新。
 */
export function createHostEventBridge(
  config: HostEventBridgeConfig,
): HostEventBridge {
  const listeners = new Set<(event: HostEventEnvelope) => void>();
  const seen = new Set<string>();
  const traceId = config.traceId || newChannelId();
  const integrationId = config.integrationId || config.appCode;
  const now = config.now || (() => new Date());

  const deliver = (event: HostEventEnvelope) => {
    try {
      config.onEvent?.(event);
    } catch {
      // 宿主回调异常不得破坏 SDK 状态。
    }
    for (const listener of [...listeners]) {
      try {
        listener(event);
      } catch {
        // 同上。
      }
    }
    try {
      config.transport?.(event);
    } catch {
      // 通道异常同样隔离。
    }
  };

  const emit: HostEventBridge["emit"] = (event, extra) => {
    const envelope = {
      schemaVersion: HOST_EVENT_SCHEMA_VERSION,
      event,
      integrationId,
      appCode: config.appCode,
      traceId,
      occurredAt: now().toISOString(),
      ...extra,
    };
    const accepted = validateHostEvent(envelope);
    if (!accepted.ok) return null;
    const value = accepted.event;
    const dedupeKey =
      event === "result"
        ? "result:" + String(value.runId)
        : event === "run-started"
          ? "run-started:" + String(value.runId)
          : event === "approval-required"
            ? "approval:" +
              String(value.runId) +
              ":" +
              String(value.structured?.nodeId ?? "")
            : "";
    if (dedupeKey && seen.has(dedupeKey)) return null;
    if (dedupeKey) seen.add(dedupeKey);
    deliver(value);
    return value;
  };

  return {
    emit,
    result: (extra) => emit("result", extra),
    subscribe(listener) {
      if (typeof listener !== "function") throw Error("监听器必须是函数");
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    clear() {
      listeners.clear();
      seen.clear();
    },
  };
}

export type HostChannel = { nonce: string; instanceId: string };

/** SDK/webapp 发送端：只向精确 origin 发纯数据事件。 */
export function postHostChannelEvent(
  target: { postMessage(message: unknown, targetOrigin: string): void },
  targetOrigin: string,
  event: HostEventEnvelope,
  channel: HostChannel,
): { ok: true } | HostEventRejection {
  if (!ORIGIN_PATTERN.test(targetOrigin) || targetOrigin === "*")
    return reject("targetOrigin 必须是精确 http(s) origin");
  if (!isChannelId(channel?.nonce) || !isChannelId(channel?.instanceId))
    return reject("通道 nonce/实例 ID 不合法");
  const accepted = validateHostEvent(event);
  if (!accepted.ok) return accepted;
  target.postMessage(
    {
      type: HOST_CHANNEL_EVENT_TYPE,
      schemaVersion: HOST_EVENT_SCHEMA_VERSION,
      nonce: channel.nonce,
      instanceId: channel.instanceId,
      event: accepted.event,
    },
    targetOrigin,
  );
  return { ok: true };
}

/** 宿主接收端：origin/source/一次性 nonce/实例 ID 全部匹配才接受事件。 */
export function acceptHostChannelMessage(
  messageEvent: { origin?: unknown; source?: unknown; data?: unknown },
  expected: { origin: string; source: unknown; nonce: string; instanceId: string },
): HostEventAcceptance {
  if (!expected || !ORIGIN_PATTERN.test(expected.origin || ""))
    return reject("期望的 origin 不合法");
  if (messageEvent.origin !== expected.origin)
    return reject("event.origin 不匹配");
  if (!expected.source || messageEvent.source !== expected.source)
    return reject("event.source 不匹配");
  const data = messageEvent.data;
  if (!isObject(data) || data.type !== HOST_CHANNEL_EVENT_TYPE)
    return reject("消息类型不匹配");
  if (data.schemaVersion !== HOST_EVENT_SCHEMA_VERSION)
    return reject("未知 schemaVersion：" + String(data.schemaVersion));
  if (data.nonce !== expected.nonce) return reject("通道 nonce 不匹配");
  if (data.instanceId !== expected.instanceId) return reject("实例 ID 不匹配");
  return validateHostEvent(data.event);
}

/** webapp 接收父窗口 hello：返回一次性通道参数。 */
export function readHostChannelHello(
  data: unknown,
): { ok: true; channel: HostChannel } | HostEventRejection {
  if (!isObject(data) || data.type !== HOST_CHANNEL_HELLO_TYPE)
    return reject("hello 消息类型不匹配");
  if (!isChannelId(data.nonce)) return reject("hello nonce 不合法");
  if (!isChannelId(data.instanceId)) return reject("hello 实例 ID 不合法");
  return { ok: true, channel: { nonce: data.nonce, instanceId: data.instanceId } };
}

/** 从宿主页面 URL 提取 exact origin；空或不透明来源直接拒绝。 */
export function exactOriginOf(value: string | undefined | null): string {
  if (!value) return "";
  try {
    const url = new URL(value);
    if (url.protocol !== "http:" && url.protocol !== "https:") return "";
    return url.origin;
  } catch {
    return "";
  }
}
