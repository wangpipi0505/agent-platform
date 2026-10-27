export type ServiceAccess = {
  endpoint: string;
  origin: string;
  path: string;
  method: string;
  type: string;
  authConfigured: boolean;
  timeoutMs?: number;
};

/** 只把可调用的远程服务呈现为接入卡片；模板与无端点技能不渲染。 */
export function serviceAccess(definition: any): ServiceAccess | null {
  if (!definition || !definition.endpoint) return null;
  if (!["HTTP", "ALGORITHM", "MCP"].includes(definition.type)) return null;
  const endpoint = String(definition.endpoint);
  let origin = "";
  let path = endpoint;
  try {
    const parsed = new URL(endpoint);
    if (parsed.protocol === "http:" || parsed.protocol === "https:") {
      origin = parsed.origin;
      path = parsed.pathname || "/";
    }
  } catch {
    // 本地算法端点可能不是绝对 URL；仍显示原值，但不构造根地址链接。
  }
  return {
    endpoint,
    origin,
    path,
    method: definition.type === "MCP" ? "MCP" : String(definition.httpMethod || "POST"),
    type: String(definition.type),
    authConfigured: Boolean(definition.authConfigured ?? definition.authRef),
    timeoutMs: Number(definition.timeoutMs) || undefined,
  };
}

export function probeView(result: any): {
  label: string;
  type: "success" | "warning" | "danger" | "info";
  detail: string;
} {
  if (!result) return { label: "尚未探测", type: "info", detail: "点击探测端点，只发 HEAD，不执行技能" };
  const status = String(result.status || "");
  if (status === "REACHABLE")
    return {
      label: "端点可达",
      type: "success",
      detail: `${result.httpStatus ? `HTTP ${result.httpStatus} · ` : ""}${result.latencyMs ?? "-"} ms`,
    };
  if (status === "DEGRADED")
    return {
      label: "端点降级",
      type: "warning",
      detail: `${result.httpStatus ? `HTTP ${result.httpStatus} · ` : ""}${result.reason || "服务返回异常"}`,
    };
  if (status === "UNREACHABLE")
    return { label: "端点不可达", type: "danger", detail: String(result.reason || "连接失败") };
  return { label: "未知", type: "info", detail: String(result.reason || "") };
}
