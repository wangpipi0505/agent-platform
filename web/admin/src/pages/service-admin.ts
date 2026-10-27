/** AP-S05 管理面展示规则。生命周期动作本身仍由后端校验，前端只负责把状态讲清楚。 */
export const serviceStateLabels: Record<string, string> = {
  STARTING: "启动中",
  READY: "运行中",
  UNHEALTHY: "健康检查失败",
  STOPPED: "已停止",
  FAILED: "启动失败",
  UNKNOWN: "未接管",
};

export function serviceStateLabel(state?: string) {
  return serviceStateLabels[String(state || "")] || String(state || "未知");
}

export function serviceStateClass(state?: string) {
  const value = String(state || "").toLowerCase();
  if (value === "ready") return "ready";
  if (value === "starting") return "starting";
  if (value === "unhealthy" || value === "failed") return "failed";
  return "stopped";
}

/** 管理面用“一行一个参数”表达 argv，避免用户误以为可以输入 shell 片段。 */
export function commandText(command: unknown) {
  return Array.isArray(command) ? command.map(String).join("\n") : "";
}

export function commandFromText(text: string) {
  return text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean);
}

export function serviceActionPath(code: string, action: "stop" | "restart" | "delete") {
  const base = `/admin/services/${encodeURIComponent(code)}`;
  const path = action === "delete" ? base : `${base}/${action}`;
  return `${path}?confirm=${encodeURIComponent(code)}`;
}
