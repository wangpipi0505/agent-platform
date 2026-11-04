import { buildDeclaredInputs, inputFields } from "../../shared/input-schema";
import type { ExtraInput } from "./types";

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
      field.enum || field.type === "boolean"
        ? document.createElement("select")
        : ["object", "array"].includes(field.type)
          ? document.createElement("textarea")
          : document.createElement("input");
    control.setAttribute("aria-label", field.name);
    if (control instanceof HTMLSelectElement) {
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
  context: Record<string, unknown>,
  extraInputs: Map<string, ExtraInput>,
) {
  const raw: any = { message, context };
  for (const [name, { field, control }] of extraInputs) {
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
