// R-3（台账 §7.6）：四码前端兜底文案锁定——contracts/error-codes.md §2 已登记码
// 必须有中文兜底；未知码如实返回 undefined（页面保留 mono 原码，不伪造语义）。
import { describe, expect, it } from "vitest";
import { reasonCodeText } from "../shared/reason-code-text";

describe("reason-code-text（R-3 四码兜底）", () => {
  it("TASK_DISPATCH_STALE/TASK_STEP_STALE/COMPACTION_PAIRING_BROKEN/INTEGRATION_PATH_FORBIDDEN 均有兜底", () => {
    for (const code of [
      "TASK_DISPATCH_STALE",
      "TASK_STEP_STALE",
      "COMPACTION_PAIRING_BROKEN",
      "INTEGRATION_PATH_FORBIDDEN",
    ]) {
      const text = reasonCodeText(code);
      expect(text, `${code} 应有兜底文案`).toBeTruthy();
      expect(text).not.toBe(code); // 文案不是原码回显
    }
    expect(reasonCodeText("TASK_DISPATCH_STALE")).toContain("对账留痕");
    expect(reasonCodeText("TASK_STEP_STALE")).toContain("不覆盖");
    expect(reasonCodeText("COMPACTION_PAIRING_BROKEN")).toContain("工具调用");
    expect(reasonCodeText("INTEGRATION_PATH_FORBIDDEN")).toContain("未登记接口");
  });

  it("未知码/空值返回 undefined，页面回落原码展示", () => {
    expect(reasonCodeText("SOME_FUTURE_CODE")).toBeUndefined();
    expect(reasonCodeText("")).toBeUndefined();
    expect(reasonCodeText(undefined)).toBeUndefined();
    expect(reasonCodeText(null)).toBeUndefined();
  });
});
