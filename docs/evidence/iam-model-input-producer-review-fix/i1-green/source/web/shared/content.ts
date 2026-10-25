import DOMPurify from "dompurify";
import { marked } from "marked";
export function outputText(value:any){return typeof value==='string'?value:typeof value?.text==='string'?value.text:JSON.stringify(value??'运行尚无输出',null,2);}
/** Validation is runtime metadata: show its warning separately from business content. */
export function outputWarning(value: any): string {
  if (value?.outputValidation?.valid !== false) return "";
  const reason = value.outputValidation.reason;
  return "输出未通过格式校验，已按配置保留结果。" +
    (typeof reason === "string" && reason ? " " + reason : "");
}
/** A durable final snapshot replaces provisional chunks, including invalid retry attempts. */
export function finalAnswer(value: any): { text: string; warning: string } {
  return { text: outputText(value), warning: outputWarning(value) };
}
export function safeMarkdown(text: string) {
  return DOMPurify.sanitize(marked.parse(text, { async: false }) as string, {
    FORBID_TAGS: ["img", "iframe", "script", "style", "form"],
    FORBID_ATTR: ["style"],
    ALLOWED_URI_REGEXP: /^(?:https?:|#|\/)/i,
  });
}
