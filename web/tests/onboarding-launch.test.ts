// ONB-06：助手组装交接与使用端首跑——入口同源推导（不发明新入口）、首跑清单、
// 未交付如实登记、发布联动后清单可见。纯函数直测+源码断言。
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import {
  assistantEntryUrl,
  ONBOARDING_LAUNCH_CHECKS,
  ONBOARDING_UNDELIVERED,
} from "../shared/onboarding";

describe("独立助手入口（与 AppPublishPanel 同源）", () => {
  it("显式基址优先，路径 /w/{appCode} 且编码转义", () => {
    expect(assistantEntryUrl("lookup_app", "https://portal.example/")).toBe(
      "https://portal.example/w/lookup_app",
    );
    expect(assistantEntryUrl("biz/App 1", "http://127.0.0.1:15174")).toBe(
      "http://127.0.0.1:15174/w/biz%2FApp%201",
    );
  });

  it("无 base 时走默认 15174 端口推导（与 AppPublishPanel 一致）", () => {
    const url = assistantEntryUrl("app1");
    expect(url).toMatch(/:15174\/w\/app1$/);
  });
});

describe("②首跑验证清单（全部来自已交付链路）", () => {
  it("五项核验：欢迎语/能力卡/推荐问法/变量表单/只读真实执行", () => {
    const items = ONBOARDING_LAUNCH_CHECKS.map((check) => check.item);
    expect(items).toEqual([
      "欢迎语渲染",
      "能力卡展示",
      "推荐问法",
      "模板变量表单",
      "只读调用真实执行",
    ]);
    for (const check of ONBOARDING_LAUNCH_CHECKS) {
      expect(check.expectation.length).toBeGreaterThan(8);
      expect(check.where).toBeTruthy();
    }
    // 无工具负例与失败原样展示语义保持
    expect(ONBOARDING_LAUNCH_CHECKS[4].expectation).toContain("不伪造成功");
  });

  it("③未交付项如实登记：SDK宿主按钮示例/体验后台编辑/写入模板/内嵌待集成", () => {
    const text = ONBOARDING_UNDELIVERED.join("\n");
    expect(text).toContain("SDK 宿主页面按钮集成示例");
    expect(text).toContain("体验配置后台编辑入口");
    expect(text).toContain("待确认机制");
    expect(text).toContain("待集成");
    expect(ONBOARDING_UNDELIVERED.length).toBeGreaterThanOrEqual(4);
  });
});

describe("组件接线（源码断言）", () => {
  const launch = readFileSync(
    new URL("../admin/src/pages/onboarding/AssistantLaunchStep.vue", import.meta.url),
    "utf8",
  );
  const page = readFileSync(
    new URL("../admin/src/pages/onboarding/OnboardingTemplatesPage.vue", import.meta.url),
    "utf8",
  );
  const verify = readFileSync(
    new URL("../admin/src/pages/onboarding/VerifyPublishStep.vue", import.meta.url),
    "utf8",
  );

  it("未发布不显示正式入口（草稿不视为交付完成）", () => {
    expect(launch).toContain("不能将草稿视为交付完成");
    expect(launch).toContain('v-if="!published || !appCode"');
    // 正式入口链接仅在已发布分支渲染
    expect(launch).toContain(':href="entryUrl"');
  });

  it("入口地址走共享推导（同 AppPublishPanel），不在组件内另造", () => {
    expect(launch).toContain("assistantEntryUrl(");
    expect(launch).not.toContain("15174");
  });

  it("内嵌交付标注待集成，不冒充已交付", () => {
    expect(launch).toContain("待集成");
    expect(launch).toContain("ONBOARDING_UNDELIVERED");
  });

  it("页面挂接交接区块；发布事件联动后首跑清单可见", () => {
    expect(page).toContain("<AssistantLaunchStep");
    expect(page).toContain(':published="registered?.status === \'PUBLISHED\'"');
    expect(verify).toContain('emit("published", code.value)');
    expect(page).toContain('@published="registered');
  });
});
