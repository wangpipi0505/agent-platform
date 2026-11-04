import { buildDeclaredInputs, inputFields } from "../../shared/input-schema";
import type { ExtraInput, PageContext, Widget } from "./types";

/** 环境只是选择器，服务端仍按已验票据求交；不把页面上下文当身份来源。 */
export function experiencePath(appCode: string, environment?: string): string {
  if (environment !== undefined && !/^[a-z][a-z0-9-]{0,31}$/.test(environment))
    throw Error("体验环境格式不合法");
  return `/v1/apps/${encodeURIComponent(appCode)}/experience${environment ? `?environment=${encodeURIComponent(environment)}` : ""}`;
}

/** 授权错误可见；错误应用或版本的描述不得混入当前会话。 */
export function experienceProjection(status: number, envelope: unknown, appCode: string, fixedConfig = "") {
  const reply = envelope && typeof envelope === "object" ? envelope as Record<string, unknown> : {};
  if (status === 401 || status === 403)
    throw Object.assign(Error(typeof reply.message === "string" ? reply.message : "体验读取未获授权，请重新登录或联系管理员"), { status, code: reply.code });
  const description = reply.data && typeof reply.data === "object" ? reply.data as Record<string, unknown> : null;
  if (status !== 200 || reply.code !== "OK" || !description
      || !["v1", "1.0"].includes(String(description.schemaVersion)) || description.appCode !== appCode) return null;
  const ref = description.configReleaseRef as { configReleaseId?: unknown } | undefined;
  const configReleaseId = typeof ref?.configReleaseId === "string" ? ref.configReleaseId : "";
  if (fixedConfig && configReleaseId !== fixedConfig)
    throw Object.assign(Error("体验配置与当前会话固定版本不同，请继续使用该会话的原版本"), { status: 409, code: "CONFIG_RELEASE_CHANGED" });
  return { description, configReleaseId };
}

/** Keys the page-context contract forbids even when they look like page state. */
const IDENTITY_KEYS = new Set([
  "tenantId",
  "userId",
  "roles",
  "permissions",
  "token",
  "authorization",
  "cookie",
  "secret",
  "secretRef",
]);

const KEY_PATTERN = /^[a-z][a-zA-Z0-9]{0,31}$/;

/**
 * Validate the host page context against contract §5.1 before any network call.
 * Invalid context is rejected locally and never reaches the gateway.
 */
export function validatePageContext(value: unknown): PageContext {
  if (value === null || typeof value !== "object" || Array.isArray(value))
    throw Error("pageContext 必须是对象");
  const entries = Object.entries(value as Record<string, unknown>);
  if (entries.length > 16) throw Error("pageContext 最多允许 16 个字段");
  const out: PageContext = {};
  for (const [key, raw] of entries) {
    if (!KEY_PATTERN.test(key) || IDENTITY_KEYS.has(key))
      throw Error("pageContext 字段不允许：" + key);
    if (typeof raw === "string") {
      if (raw.length > 256)
        throw Error("pageContext 字段 " + key + " 的值不能超过 256 字符");
      out[key] = raw;
    } else if (typeof raw === "number" && Number.isFinite(raw)) out[key] = raw;
    else if (typeof raw === "boolean") out[key] = raw;
    else throw Error("pageContext 字段 " + key + " 的值必须是标量");
  }
  return out;
}

export function requireApplicationType(declaration: any) {
  if (!["CHAT", "FLOW"].includes(declaration.type))
    throw Error("发布输入契约未提供应用类型，请刷新后重试");
}

export function declaredSchema(declaration: any) {
  return declaration.inputSchema ?? declaration.inputs;
}

/**
 * Render the published input declaration next to the message box. The first
 * send only renders the form; the caller refuses to send until fields exist.
 */
export function renderDeclaredInputs(
  input: HTMLTextAreaElement,
  declared: any,
  extraInputs: Map<string, ExtraInput>,
) {
  let added = false;
  for (const field of inputFields(declared).filter(
    (f) => !["message", "context"].includes(f.name),
  )) {
    if (extraInputs.has(field.name)) continue;
    const label = document.createElement("label");
    label.textContent =
      (field.description || field.name) + (field.required ? " *" : "");
    const control =
      field.format === "binary"
        ? Object.assign(document.createElement("input"), { type: "file" })
        : field.enum || field.type === "boolean"
          ? document.createElement("select")
          : ["object", "array"].includes(field.type)
            ? document.createElement("textarea")
            : document.createElement("input");
    control.setAttribute("aria-label", field.name);
    if (
      field.format === "binary" &&
      control instanceof HTMLInputElement
    ) {
      control.accept = "";
      control.title = "嵌入端不提供上传通道，请由业务系统上传后填写 artifactId";
    } else if (control instanceof HTMLSelectElement) {
      const blank = document.createElement("option");
      blank.value = "";
      blank.textContent = "请选择";
      control.append(blank);
      for (const v of field.enum || [true, false]) {
        const option = document.createElement("option");
        option.value = JSON.stringify(v);
        option.textContent = String(v);
        control.append(option);
      }
    } else if (
      control instanceof HTMLInputElement &&
      ["number", "integer"].includes(field.type)
    )
      control.type = "number";
    label.append(control);
    input.before(label);
    extraInputs.set(field.name, { field, control });
    added = true;
  }
  return added;
}

/** Build the declared payload, or the raw message payload for undeclared apps. */
export function collectInputs(
  declared: any,
  message: string,
  context: PageContext,
  extraInputs: Map<string, ExtraInput>,
) {
  const raw: any = { message, context };
  for (const [name, { field, control }] of extraInputs) {
    if (control instanceof HTMLInputElement && control.type === "file") {
      if (control.files?.length)
        throw Error(
          "文件输入「" +
            (field.description || name) +
            "」需由业务系统上传后以 artifactId 传入；嵌入端不提供上传通道。",
        );
      continue;
    }
    if (control.value === "") continue;
    raw[name] =
      control instanceof HTMLSelectElement
        ? JSON.parse(control.value)
        : ["number", "integer"].includes(field.type)
          ? Number(control.value)
          : control.value;
  }
  return inputFields(declared).length
    ? buildDeclaredInputs(declared, raw)
    : raw;
}

/** Draft text for a recommended prompt: editable, never auto-sent. */
export function promptDraft(prompt: any): string {
  const body =
    typeof prompt.example === "string" && prompt.example
      ? prompt.example
      : String(prompt.title || "");
  const variables = Array.isArray(prompt.variables) ? prompt.variables : [];
  const lines = variables
    .filter((v: any) => v && typeof v.name === "string")
    .map(
      (v: any) =>
        (v.required ? "*" : "") +
        (typeof v.description === "string" && v.description
          ? v.description
          : v.name) +
        "：",
    );
  return [body, ...lines].join("\n");
}

/**
 * Render the server experience description as plain text. Nothing from the
 * description is interpreted as HTML or script; unknown fields are ignored.
 */
export function renderExperience(
  widget: Widget,
  description: any,
  useComposer: (text: string) => void,
) {
  const section = widget.experience;
  widget.capabilities.replaceChildren();
  widget.prompts.replaceChildren();
  widget.experienceNote.textContent = "";
  const welcome =
    typeof description.welcomeText === "string"
      ? description.welcomeText.slice(0, 2048)
      : "";
  widget.welcome.textContent = welcome;
  const capabilities = Array.isArray(description.capabilities)
    ? description.capabilities.slice(0, 32)
    : [];
  for (const capability of capabilities) {
    if (!capability || typeof capability.label !== "string") continue;
    const card = document.createElement("button");
    card.type = "button";
    card.className = "capability";
    const label = document.createElement("strong");
    label.textContent = capability.label;
    card.append(label);
    if (
      typeof capability.description === "string" &&
      capability.description
    ) {
      const detail = document.createElement("span");
      detail.textContent = capability.description.slice(0, 512);
      card.append(detail);
    }
    const badges: string[] = [];
    if (capability.readOnly === true) badges.push("只读");
    if (capability.requiresApproval === true) badges.push("需确认");
    for (const text of badges) {
      const badge = document.createElement("em");
      badge.className = "badge";
      badge.textContent = text;
      card.append(badge);
    }
    card.onclick = () => useComposer(capability.label);
    widget.capabilities.append(card);
  }
  const prompts = Array.isArray(description.prompts)
    ? description.prompts.slice(0, 16)
    : [];
  if (prompts.length) {
    const heading = document.createElement("p");
    heading.className = "prompts-title";
    heading.textContent = "推荐问法";
    widget.prompts.append(heading);
  }
  for (const prompt of prompts) {
    if (!prompt || typeof prompt.title !== "string") continue;
    const button = document.createElement("button");
    button.type = "button";
    button.className = "prompt";
    button.textContent = prompt.title;
    button.onclick = () => useComposer(promptDraft(prompt));
    widget.prompts.append(button);
  }
  section.hidden =
    !welcome && !widget.capabilities.childElementCount && !prompts.length;
}

/** Honest fallback: no description means direct chat, not invented abilities. */
export function renderExperienceFallback(widget: Widget) {
  widget.capabilities.replaceChildren();
  widget.prompts.replaceChildren();
  widget.welcome.textContent = "";
  widget.experienceNote.textContent =
    "未获取到该应用的体验描述，可直接提问。";
  widget.experience.hidden = false;
}
