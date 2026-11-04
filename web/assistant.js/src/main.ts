import type { AssistantHandle, AssistantOptions } from "./types";
import { createRuntime } from "./runtime";
import { createEventBus, createSdkHostEvents, readSessionId } from "./host-events";
import { mountWidget } from "./widget";

/** One instance per explicit mount point; re-init replaces the previous one. */
const mounted = new WeakMap<HTMLElement, AssistantHandle>();

/**
 * Public embed entry. Hosts keep the original `init(options).destroy()` flow;
 * the handle additionally exposes open/close/updateContext/on/off for host
 * buttons and lifecycle wiring. No host API is removed or renamed.
 */
export function init(options: AssistantOptions): AssistantHandle {
  if (!/^https?:\/\//.test(options.endpoint))
    throw Error("endpoint 仅允许 http/https");
  if (typeof options.ticket !== "function")
    throw Error("必须提供宿主后端出票函数");
  const mount = options.mount;
  if (mount !== undefined && !(mount instanceof HTMLElement))
    throw Error("mount 必须是 DOM 元素");
  if (
    options.integrationId !== undefined &&
    !/^[A-Za-z0-9._-]{1,64}$/.test(options.integrationId)
  )
    throw Error("integrationId 字符不合法");
  if (
    options.onHostEvent !== undefined &&
    typeof options.onHostEvent !== "function"
  )
    throw Error("onHostEvent 必须是函数");

  if (mount) mounted.get(mount)?.destroy();

  const bus = createEventBus();
  const hostEvents = createSdkHostEvents({
    appCode: options.appCode,
    integrationId: options.integrationId,
    onEvent: options.onHostEvent,
  });
  const widget = mountWidget({
    autoLauncher: options.autoLauncher !== false,
    mount,
  });
  const runtime = createRuntime(
    options,
    widget,
    readSessionId(location.search),
    bus.emit,
    hostEvents,
  );
  const listeners = new AbortController();
  const { signal } = listeners;

  widget.ball?.addEventListener("click", () => runtime.open(), { signal });
  widget.close.addEventListener("click", () => runtime.close(), {
    signal,
  });
  widget.send.addEventListener("click", () => void runtime.send(), { signal });

  // 宿主在 init 时已注册回调的，ready 不会丢失；后注册者仍可通过 onHostEvent 订阅后续事件。
  hostEvents.emit("ready");

  let destroyed = false;
  const handle: AssistantHandle = {
    open(openOptions) {
      runtime.open(openOptions);
    },
    close() {
      runtime.close();
    },
    updateContext(context) {
      runtime.updateContext(context);
    },
    on(event, listener) {
      return bus.on(event, listener);
    },
    off(event, listener) {
      bus.off(event, listener);
    },
    onHostEvent(listener) {
      return hostEvents.subscribe(listener);
    },
    destroy() {
      if (destroyed) return;
      destroyed = true;
      runtime.destroy();
      listeners.abort();
      widget.host.remove();
      bus.clear();
      hostEvents.clear();
      if (mount && mounted.get(mount) === handle) mounted.delete(mount);
    },
  };
  if (mount) mounted.set(mount, handle);
  return handle;
}

export type { AssistantHandle, AssistantOptions };
