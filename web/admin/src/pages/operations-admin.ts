/**
 * Presentation helpers for the operations center. They only format values supplied by the
 * backend; a missing or malformed value renders as an explicit dash instead of a fabricated zero.
 */
export function percent(value: unknown): string {
  if (typeof value !== "number" || !Number.isFinite(value)) return "—";
  return `${(value * 100).toFixed(1)}%`;
}

export function millis(value: unknown): string {
  if (typeof value !== "number" || !Number.isFinite(value)) return "—";
  if (value < 1000) return `${Math.round(value)} ms`;
  return `${(value / 1000).toFixed(1)} s`;
}

export function windowLabel(minutes: unknown): string {
  if (typeof minutes !== "number" || !Number.isFinite(minutes)) return "—";
  if (minutes < 60) return `${minutes} 分钟`;
  if (minutes % 60 === 0) return `${minutes / 60} 小时`;
  return `${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分钟`;
}

export function count(value: unknown): string {
  if (typeof value !== "number" || !Number.isFinite(value)) return "—";
  return value.toLocaleString("zh-CN");
}

/** PostgreSQL can return nanosecond precision; Date accepts at most milliseconds. */
export function timeLabel(value: unknown): string {
  if (typeof value !== "string" || !value) return "—";
  const normalized = value.replace(/(\.\d{3})\d+/, "$1");
  const date = new Date(normalized);
  if (Number.isNaN(date.getTime())) return "—";
  return `${date.toISOString().slice(0, 19).replace("T", " ")} UTC`;
}

export function severityView(value: unknown): { label: string; type: "danger" | "warning" } {
  if (value === "CRITICAL") return { label: "严重", type: "danger" };
  return { label: "警告", type: "warning" };
}

export function alertStatusLabel(value: unknown): string {
  if (value === "OPEN") return "待处理";
  if (value === "ACK") return "已确认";
  if (value === "RESOLVED") return "已恢复";
  return "—";
}

export function checkType(ok: unknown): "success" | "danger" {
  return ok === true ? "success" : "danger";
}
