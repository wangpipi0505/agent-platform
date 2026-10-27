import { describe, expect, it } from "vitest";
import {
  alertStatusLabel,
  checkType,
  count,
  millis,
  percent,
  severityView,
  timeLabel,
  windowLabel,
} from "../admin/src/pages/operations-admin";

describe("operations center presentation", () => {
  it("does not turn missing metrics into fabricated zeros", () => {
    expect(percent(null)).toBe("—");
    expect(percent("0.5")).toBe("—");
    expect(millis(undefined)).toBe("—");
    expect(count(Number.NaN)).toBe("—");
  });

  it("formats ratios and durations for the cards", () => {
    expect(percent(0.125)).toBe("12.5%");
    expect(percent(1)).toBe("100.0%");
    expect(millis(42)).toBe("42 ms");
    expect(millis(1938)).toBe("1.9 s");
  });

  it("formats the supported windows without inventing one", () => {
    expect(windowLabel(15)).toBe("15 分钟");
    expect(windowLabel(60)).toBe("1 小时");
    expect(windowLabel(90)).toBe("1 小时 30 分钟");
    expect(windowLabel(null)).toBe("—");
  });

  it("maps alert severity and lifecycle states", () => {
    expect(severityView("CRITICAL")).toEqual({ label: "严重", type: "danger" });
    expect(severityView("WARNING")).toEqual({ label: "警告", type: "warning" });
    expect(alertStatusLabel("OPEN")).toBe("待处理");
    expect(alertStatusLabel("ACK")).toBe("已确认");
    expect(alertStatusLabel("RESOLVED")).toBe("已恢复");
    expect(alertStatusLabel("BOGUS")).toBe("—");
  });

  it("renders database timestamps as explicit UTC without losing nanoseconds", () => {
    expect(timeLabel("2026-10-27T06:36:40.610280300Z")).toBe("2026-10-27 06:36:40 UTC");
    expect(timeLabel("not-a-time")).toBe("—");
    expect(timeLabel(undefined)).toBe("—");
  });

  it("marks only an explicit boolean true as healthy", () => {
    expect(checkType(true)).toBe("success");
    expect(checkType(false)).toBe("danger");
    expect(checkType(null)).toBe("danger");
  });
});
