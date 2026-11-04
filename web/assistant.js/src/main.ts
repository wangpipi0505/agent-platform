import { mountWidget } from "./widget";
import { createRuntime } from "./runtime";
import { readSessionId } from "./host-events";
import type { AssistantHandle, AssistantOptions } from "./types";

export type { AssistantHandle, AssistantOptions } from "./types";

/**
 * Validate host options, assemble the widget/runtime pair and expose only the
 * existing destroy lifecycle. Opening/closing stays a widget interaction here;
 * no new host call surface is added by this structural step.
 */
export function init(options: AssistantOptions): AssistantHandle {
  if (!/^https?:\/\//.test(options.endpoint))
    throw new Error("endpoint 仅允许 http/https");
  if (typeof options.ticket !== "function")
    throw new Error("必须提供宿主后端出票函数");
  const widget = mountWidget();
  const runtime = createRuntime(
    options,
    widget,
    readSessionId(location.search),
  );
  widget.root.querySelector(".ball")!.addEventListener("click", () => {
    widget.drawer.style.display = "flex";
    void runtime.hydrate();
  });
  widget.root
    .querySelector(".close")!
    .addEventListener("click", () => (widget.drawer.style.display = "none"));
  widget.send.addEventListener("click", () => {
    void runtime.send();
  });
  return {
    destroy() {
      runtime.destroy();
      widget.host.remove();
    },
  };
}
