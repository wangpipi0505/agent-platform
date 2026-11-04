import { writeSessionUrl } from "../../shared/session";
import {
  createHostEventBridge,
  type HostEventBridge,
  type HostEventEnvelope,
} from "../../shared/assistant-host-events";
import type { AssistantEvent, AssistantEventPayload } from "./types";

/**
 * Host-page coupling owned by the SDK: session handoff through the page URL,
 * the legacy lifecycle bus, and the standard result-event bridge (§4).
 * Event payloads are plain data only; functions, DOM nodes and window handles
 * never cross this boundary.
 */
const EVENTS = new Set<AssistantEvent>(["open", "close", "session", "destroy"]);

export function readSessionId(search: string) {
  return new URLSearchParams(search).get("sessionId") || "";
}

export function publishSession(id: string) {
  writeSessionUrl(id);
}

export type EventBus = {
  on<E extends AssistantEvent>(
    event: E,
    listener: (payload: AssistantEventPayload[E]) => void,
  ): () => void;
  off<E extends AssistantEvent>(
    event: E,
    listener: (payload: AssistantEventPayload[E]) => void,
  ): void;
  emit<E extends AssistantEvent>(
    event: E,
    payload: AssistantEventPayload[E],
  ): void;
  clear(): void;
};

export function createEventBus(): EventBus {
  const listeners = new Map<AssistantEvent, Set<(payload: any) => void>>();
  const requireEvent = (event: string) => {
    if (!EVENTS.has(event as AssistantEvent))
      throw Error("不支持的事件类型：" + event);
  };
  return {
    on(event, listener) {
      requireEvent(event);
      if (typeof listener !== "function")
        throw Error("事件监听器必须是函数");
      let set = listeners.get(event);
      if (!set) listeners.set(event, (set = new Set()));
      set.add(listener);
      return () => set.delete(listener);
    },
    off(event, listener) {
      requireEvent(event);
      listeners.get(event)?.delete(listener);
    },
    emit(event, payload) {
      if (!EVENTS.has(event)) return;
      for (const listener of [...(listeners.get(event) ?? [])]) {
        try {
          listener(payload);
        } catch {
          // A host listener must not break the assistant.
        }
      }
    },
    clear() {
      listeners.clear();
    },
  };
}

export type SdkHostEventsOptions = {
  appCode: string;
  integrationId?: string;
  /** Same-page explicit callback registered by the host at init time. */
  onEvent?: (event: HostEventEnvelope) => void;
  /** Optional iframe sender injected by the host/webapp channel. */
  transport?: (event: HostEventEnvelope) => void;
  traceId?: string;
  now?: () => Date;
};

/**
 * Standard result-event bridge. Unknown fields/versions are rejected by the
 * shared validator before delivery; only the seven declared events pass.
 */
export function createSdkHostEvents(
  options: SdkHostEventsOptions,
): HostEventBridge {
  return createHostEventBridge({
    appCode: options.appCode,
    integrationId: options.integrationId,
    onEvent: options.onEvent,
    transport: options.transport,
    traceId: options.traceId,
    now: options.now,
  });
}

export type { HostEventBridge, HostEventEnvelope };
