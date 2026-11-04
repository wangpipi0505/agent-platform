import tokenCss from "../../shared/tokens.css?raw";
import widgetCss from "./widget.css?raw";
import type { Widget } from "./types";

// Presentation is isolated in a shadow root; authorization remains enforced by the gateway.
// Server-provided experience text is rendered through textContent only, never as markup.
const DRAWER =
  '<section class="drawer" style="display:none"><header><strong>智能助手</strong><button class="close">关闭</button></header><section class="experience" hidden><p class="welcome"></p><div class="capabilities"></div><div class="prompts"></div><p class="experience-note"></p></section><div class="log"><p>向智能体提问。</p></div><p class="error" role="alert"></p><details class="run-details" hidden><summary>执行详情</summary><pre class="run-meta"></pre></details><textarea aria-label="输入问题"></textarea><button class="send">发送</button></section>';
const BALL = '<button class="ball" aria-label="打开智能助手">AI</button>';

const WARNING_CSS =
  " .output-warning{color:var(--st-suspend-text);background:color-mix(in srgb, var(--st-suspend) 12%, transparent);border-left:3px solid var(--st-suspend);border-radius:6px;padding:10px 12px;white-space:normal;overflow-wrap:anywhere}";

export type MountOptions = {
  autoLauncher?: boolean;
  mount?: HTMLElement;
};

export function mountWidget(options: MountOptions = {}): Widget {
  if (!customElements.get("agent-platform-assistant"))
    customElements.define(
      "agent-platform-assistant",
      class extends HTMLElement {},
    );
  const autoLauncher = options.autoLauncher !== false;
  const host = document.createElement("agent-platform-assistant");
  const root = host.attachShadow({ mode: "open" });
  root.innerHTML = `<style>${widgetCss}</style>${autoLauncher ? BALL : ""}${DRAWER}`;
  const tokens = document.createElement("style");
  tokens.textContent = tokenCss
    .slice(0, tokenCss.indexOf("}") + 1)
    .replace(":root", ":host");
  tokens.textContent += WARNING_CSS;
  root.prepend(tokens);
  (options.mount ?? document.body).append(host);
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
    close: root.querySelector<HTMLButtonElement>(".close")!,
    input: root.querySelector<HTMLTextAreaElement>("textarea")!,
    experience: root.querySelector<HTMLElement>(".experience")!,
    welcome: root.querySelector<HTMLElement>(".welcome")!,
    capabilities: root.querySelector<HTMLElement>(".capabilities")!,
    prompts: root.querySelector<HTMLElement>(".prompts")!,
    experienceNote: root.querySelector<HTMLElement>(".experience-note")!,
    ball: root.querySelector<HTMLElement>(".ball") ?? undefined,
  };
}

export function bubble(widget: Widget, text: string): HTMLDivElement {
  const element = document.createElement("div");
  element.className = "bubble";
  element.textContent = text;
  widget.log.append(element);
  return element;
}

/**
 * UI-07：工程元数据（运行标识/状态等）只进可折叠"执行详情"，默认隐藏——
 * 用户先看到结果与下一步；呈现行由宿主侧按需调用，协议与请求面不变。
 */
export function setRunMeta(root: ShadowRoot, text: string): void {
  const details = root.querySelector<HTMLDetailsElement>(".run-details");
  if (!details) return;
  details.querySelector(".run-meta")!.textContent = text;
  details.hidden = !text;
}

/** Validation metadata stays visually separate from business content. */
export function prependWarning(element: HTMLElement, warning: string) {
  const note = document.createElement("p");
  note.setAttribute("role", "status");
  note.className = "output-warning";
  note.textContent = warning;
  element.prepend(note);
}
