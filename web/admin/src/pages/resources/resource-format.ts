export const statusLabels: Record<string, string> = {
  DRAFT: "草稿",
  PUBLISHED: "已发布",
  DISABLED: "已停用",
  ARCHIVED: "已归档",
  ACTIVE: "启用",
  ENABLED: "启用",
  QUEUED: "排队中",
  RUNNING: "运行中",
  WAITING_APPROVAL: "等待确认",
  WAITING_EXTERNAL: "等待外部",
  SUCCEEDED: "成功",
  FAILED: "失败",
  CANCELLED: "已取消",
  PENDING: "待处理",
  ACCEPTED: "已通过",
  REJECTED: "已拒绝",
  DEPRECATED: "已废弃",
  SUPERSEDED: "已替代",
};

export const statusLabel = (value?: string) =>
  value ? statusLabels[value] || value : "—";

/** Display service timestamps in the operator's timezone instead of raw ISO UTC. */
export function displayTime(value: unknown) {
  if (!value) return "—";
  const date = new Date(String(value));
  return Number.isNaN(date.getTime())
    ? "—"
    : new Intl.DateTimeFormat("zh-CN", {
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        hour12: false,
        timeZone: "Asia/Shanghai",
      }).format(date);
}

export function evalPassText(row: any) {
  const results = Array.isArray(row?.results) ? row.results : [];
  if (!results.length) return "—";
  return `${results.filter((item: any) => item.passed).length}/${results.length}`;
}
