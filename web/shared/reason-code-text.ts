// R-3（台账 §7.6）：错误码前端兜底文案——contracts/error-codes.md §2 已登记码的用户面文案。
// 约定：已知码给中文兜底；未知码返回 undefined（页面保留 mono 原码展示，不伪造语义）。
const REASON_CODE_TEXT: Record<string, string> = {
  TASK_DISPATCH_STALE: "分派回执迟到：任务已被接管或代次过期，已转对账留痕",
  TASK_STEP_STALE: "步骤状态已更新：本次迟到提交不覆盖",
  COMPACTION_PAIRING_BROKEN: "压缩区间拆开了未闭合的工具调用/结果，禁止本次压缩",
  INTEGRATION_PATH_FORBIDDEN: "外部票据访问了未登记接口（已拒绝并审计）",
};

/** 已登记码 → 兜底文案；未知码 → undefined（调用方原样展示码值）。 */
export function reasonCodeText(code: string | null | undefined): string | undefined {
  if (typeof code !== "string") return undefined;
  return REASON_CODE_TEXT[code.trim()];
}
