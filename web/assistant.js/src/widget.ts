import tokenCss from "../../shared/tokens.css?raw";
import widgetCss from "./widget.css?raw";
import type { Widget } from "./types";

// Presentation is isolated in a shadow root; authorization remains enforced by the gateway.
const MARKUP =
  '<button class="ball" aria-label="打开智能助手">AI</button><section class="drawer" style="display:none"><header><strong>智能助手</strong><button class="close">关闭</button></header><div class="log"><p>向智能体提问。</p></div><p class="error" role="alert"></p><textarea aria-label="输入问题"></textarea><button class="send">发送</button></section>';

const WARNING_CSS =
  " .output-warning{color:var(--st-suspend-text);background:#fff7ed;border-left:3px solid var(--st-suspend);border-radius:6px;padding:10px 12px;white-space:normal;overflow-wrap:anywhere}";

export function mountWidget(): Widget {
  if (!customElements.get("agent-platform-assistant"))
    customElements.define(
      "agent-platform-assistant",
      class extends HTMLElement {},
    );
  const host = document.createElement("agent-platform-assistant");
  const root = host.attachShadow({ mode: "open" });
  root.innerHTML = `<style>${widgetCss}</style>${MARKUP}`;
  const tokens = document.createElement("style");
  tokens.textContent = tokenCss
    .slice(0, tokenCss.indexOf("}") + 1)
    .replace(":root", ":host");
  tokens.textContent += WARNING_CSS;
  root.prepend(tokens);
  document.body.append(host);
  const connection = document.createElement("p");
  connection.setAttribute("role", "status");
  root.querySelector(".error")!.before(connection);
  return {
    host,
    root,
    drawer: root.querySelector<HTMLElement>(".drawer")!,
    log: root.querySelector<HTMLElement>(".log")!,
    error: root.querySelector<HTMLElement>(".error")!,
    connection,
    send: root.querySelector<HTMLButtonElement>(".send")!,
    input: root.querySelector<HTMLTextAreaElement>("textarea")!,
  };
}

export function bubble(widget: Widget, text: string): HTMLDivElement {
  const element = document.createElement("div");
  element.className = "bubble";
  element.textContent = text;
  widget.log.append(element);
  return element;
}

/** Validation metadata stays visually separate from business content. */
export function prependWarning(element: HTMLElement, warning: string) {
  const note = document.createElement("p");
  note.setAttribute("role", "status");
  note.className = "output-warning";
  note.textContent = warning;
  element.prepend(note);
}
