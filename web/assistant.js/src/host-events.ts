import { writeSessionUrl } from "../../shared/session";
import type {
  AssistantEvent,
  AssistantEventPayload,
} from "./types";

/**
 * Host-page coupling owned by the SDK: session handoff through the page URL and
 * the additive event-registration surface. Event payloads are plain data only;
 * the richer host-event bridge arrives with the L2 step.
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
