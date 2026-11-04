// ONB-05：接入会话恢复与错误恢复——错误码映射（CONFIG_* 登记集）、阻塞可解释、
// 冲突保留本地编辑、失败重试不丢内容、服务端持久化恢复推导。纯函数直测+源码断言。
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import {
  conflictDiff,
  mapOnboardingError,
  ONBOARDING_ERROR_MESSAGES,
  resumeEntryFor,
} from "../shared/onboarding";

describe("③服务端错误码映射（CONFIG_* 登记集）", () => {
  it("七类 CONFIG_* 码全部有可读文案与建议动作", () => {
    for (const code of [
      "CONFIG_REV_CONFLICT",
      "CONFIG_STATE_TRANSITION_INVALID",
      "CONFIG_REF_NOT_FOUND",
      "CONFIG_SCHEMA_BUDGET_EXCEEDED",
      "CONFIG_AUTH_SCHEME_UNSUPPORTED",
      "CONFIG_SECRET_PLAINTEXT_REJECTED",
      "CONFIG_VERSION_UNKNOWN",
    ]) {
      const mapped = ONBOARDING_ERROR_MESSAGES[code];
      expect(mapped.message.length).toBeGreaterThan(6);
      expect(mapped.action.length).toBeGreaterThan(4);
    }
  });

  it("并发冲突：保留本地编辑不自动覆盖，给出核对动作", () => {
    const view = mapOnboardingError({ code: "CONFIG_REV_CONFLICT", message: "raw" });
    expect(view.message).toContain("本地编辑已保留");
    expect(view.action).toContain("刷新模板目录核对");
    expect(view.recoverable).toBe(true);
  });

  it("状态转移非法/引用缺失为阻塞类：可解释且不诱导重试", () => {
    for (const code of ["CONFIG_STATE_TRANSITION_INVALID", "CONFIG_REF_NOT_FOUND"]) {
      const view = mapOnboardingError({ code, message: "raw" });
      expect(view.blocking).toBe(true);
      expect(view.recoverable).toBe(false);
    }
    // 停用模板走状态转移阻塞：只有 DRAFT 可发布
    expect(ONBOARDING_ERROR_MESSAGES.CONFIG_STATE_TRANSITION_INVALID.message).toContain("只有 DRAFT 可发布");
  });

  it("身份兜底：401 过期会话/403 撤权可解释阻塞", () => {
    const expired = mapOnboardingError({ status: 401, message: "会话已失效" });
    expect(expired.blocking).toBe(true);
    expect(expired.message).toContain("会话已失效");
    const forbidden = mapOnboardingError({ status: 403, message: "denied" });
    expect(forbidden.blocking).toBe(true);
    expect(forbidden.action).toContain("管理员");
  });

  it("未知错误回退原 message 且可恢复，不吞错", () => {
    const view = mapOnboardingError({ message: "网关超时" });
    expect(view.message).toBe("网关超时");
    expect(view.recoverable).toBe(true);
    expect(view.blocking).toBe(false);
  });
});

describe("①会话恢复推导（服务端持久化，不依赖本地缓存）", () => {
  it("DRAFT→继续验证发布；PUBLISHED/未知→无恢复入口", () => {
    expect(resumeEntryFor({ status: "DRAFT" })).toBe("verify");
    expect(resumeEntryFor({ status: "PUBLISHED" })).toBe("none");
    expect(resumeEntryFor({})).toBe("none");
  });
});

describe("④并发修订差异对照", () => {
  it("值不同键列出，相同键不列；本地/服务端键并集覆盖", () => {
    const diff = conflictDiff(
      { code: "t1", name: "本地名", kind: "QUERY" },
      { code: "t1", name: "服务端名", kind: "QUERY", extra: 1 },
    );
    expect(diff).toEqual(["name", "extra"]);
  });
});

describe("组件接线（源码断言）", () => {
  const notice = readFileSync(
    new URL("../admin/src/pages/onboarding/OnboardingErrorNotice.vue", import.meta.url),
    "utf8",
  );
  const assembly = readFileSync(
    new URL("../admin/src/pages/onboarding/DraftAssemblyStep.vue", import.meta.url),
    "utf8",
  );
  const verify = readFileSync(
    new URL("../admin/src/pages/onboarding/VerifyPublishStep.vue", import.meta.url),
    "utf8",
  );
  const page = readFileSync(
    new URL("../admin/src/pages/onboarding/OnboardingTemplatesPage.vue", import.meta.url),
    "utf8",
  );

  it("错误提示组件走映射而非裸 message；阻塞可解释", () => {
    expect(notice).toContain("mapOnboardingError");
    expect(notice).toContain("该问题属于身份或状态阻塞，重试不会解决");
    expect(notice).not.toContain("e.message");
  });

  it("②部分失败重试不丢内容：document/connection 由 v-model 保留；组装失败保留草稿", () => {
    expect(verify).toContain("预检/发布失败后重试：document/connection 由 v-model 保留");
    expect(verify).toContain('v-model="document"');
    expect(verify).toContain('v-model="connection"');
    expect(assembly).toContain("props.draft"); // 草稿由父级持有，失败不清空
  });

  it("④冲突保留：REV_CONFLICT 时展示差异键且本地草稿不被覆盖", () => {
    expect(assembly).toContain("CONFIG_REV_CONFLICT");
    expect(assembly).toContain("conflictDiff(");
    expect(assembly).toContain("本地编辑已保留");
  });

  it("①恢复提示条：DRAFT 草稿经 resumeEntryFor 推导展示继续入口", () => {
    expect(page).toContain("resumeEntryFor");
    expect(page).toContain("发现未完成的登记草稿");
    expect(page).toContain("服务端已持久化");
  });
});
