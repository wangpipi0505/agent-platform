import type { AssistantHandle, AssistantOptions } from "./types";
import { createRuntime } from "./runtime";
import { createEventBus, readSessionId } from "./host-events";
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

  if (mount) mounted.get(mount)?.destroy();

  const bus = createEventBus();
  const widget = mountWidget({
    autoLauncher: options.autoLauncher !== false,
    mount,
  });
  const runtime = createRuntime(
    options,
    widget,
    readSessionId(location.search),
    bus.emit,
  );
  const listeners = new AbortController();
  const { signal } = listeners;

  widget.ball?.addEventListener("click", () => runtime.open(), { signal });
  widget.close.addEventListener("click", () => runtime.close(), {
    signal,
  });
  widget.send.addEventListener("click", () => void runtime.send(), { signal });

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
    destroy() {
      if (destroyed) return;
      destroyed = true;
      runtime.destroy();
      listeners.abort();
      widget.host.remove();
      bus.clear();
      if (mount && mounted.get(mount) === handle) mounted.delete(mount);
    },
  };
  if (mount) mounted.set(mount, handle);
  return handle;
}

export type { AssistantHandle, AssistantOptions };
