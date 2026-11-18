// F2-2（台账 §7.5）：技能库单页闭环——列表 kind 筛选 + 详情单页分区 + 分型发布 checklist。
// 行为用例锁定 mock-skill-precheck 的分型预检状态机（页面不自算通过/阻断）；
// 页面结构用源码级静态断言（先例=resource-workbench.test.ts）。
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import {
  fetchMockSkillPrecheck,
  matchesKindFilter,
  skillKindOf,
  SKILL_KIND_FILTERS,
} from "../shared/mock-skill-precheck";

const read = (name: string) =>
  readFileSync(new URL("../admin/src/" + name, import.meta.url), "utf8");

describe("技能 kind 分型与列表筛选", () => {
  it("kind 推导：GUIDE 才是指南，HTTP/MCP 等工具类型与缺省都按 TOOL", () => {
    expect(skillKindOf({ kind: "GUIDE" })).toBe("GUIDE");
    expect(skillKindOf({ kind: "HTTP" })).toBe("TOOL");
    expect(skillKindOf({ kind: "MCP" })).toBe("TOOL");
    expect(skillKindOf({})).toBe("TOOL");
    expect(skillKindOf(undefined)).toBe("TOOL");
  });
  it("kind chips 筛选：空=全部；TOOL=非 GUIDE；GUIDE=仅 GUIDE", () => {
    const tool = { kind: "HTTP" };
    const guide = { kind: "GUIDE" };
    for (const row of [tool, guide]) expect(matchesKindFilter(row, "")).toBe(true);
    expect(matchesKindFilter(tool, "TOOL")).toBe(true);
    expect(matchesKindFilter(guide, "TOOL")).toBe(false);
    expect(matchesKindFilter(guide, "GUIDE")).toBe(true);
    expect(matchesKindFilter(tool, "GUIDE")).toBe(false);
  });
  it("筛选选项固定三档：全部 / TOOL / GUIDE", () => {
    expect(SKILL_KIND_FILTERS.map((item) => item.value)).toEqual(["", "TOOL", "GUIDE"]);
  });
});

describe("TOOL 分型发布预检（服务端结果语义，demo 推导）", () => {
  const publishedReadonlyTool = {
    code: "orbit_query",
    name: "轨道查询",
    status: "PUBLISHED",
    currentVersion: 2,
    endpoint: "https://svc.example/orbit",
    inputSchema: { type: "object" },
    outputSchema: { type: "object" },
    execution: { readonly: true, sideEffect: false, idempotent: true },
  };

  it("无副作用已发布技能：四项全绿，意图项免检不阻断", async () => {
    const precheck = await fetchMockSkillPrecheck(publishedReadonlyTool);
    expect(precheck.demo).toBe(true);
    expect(precheck.kind).toBe("TOOL");
    expect(precheck.version).toBe(2);
    expect(precheck.items.map((item) => item.key)).toEqual([
      "contract",
      "connection",
      "acceptance",
      "intent",
    ]);
    expect(precheck.items.every((item) => item.status === "PASS")).toBe(true);
    const intent = precheck.items.find((item) => item.key === "intent")!;
    expect(intent.required).toBe(false);
    expect(intent.fieldAnchor).toBeUndefined();
    expect(precheck.passed).toBe(true);
  });

  it("副作用技能未声明意图字段：意图项 FAIL 并携带 businessIntentInput 定位锚点，阻断发布", async () => {
    const precheck = await fetchMockSkillPrecheck({
      ...publishedReadonlyTool,
      execution: { readonly: false, sideEffect: true, idempotent: false },
    });
    const intent = precheck.items.find((item) => item.key === "intent")!;
    expect(intent.status).toBe("FAIL");
    expect(intent.required).toBe(true);
    expect(intent.fieldAnchor).toBe("businessIntentInput");
    expect(precheck.passed).toBe(false);
  });

  it("副作用技能已声明意图字段：意图项 PASS，发布解禁", async () => {
    const precheck = await fetchMockSkillPrecheck({
      ...publishedReadonlyTool,
      execution: { readonly: false, sideEffect: true, idempotent: false },
      businessIntentInput: "targetId",
    });
    expect(precheck.items.find((item) => item.key === "intent")!.status).toBe("PASS");
    expect(precheck.passed).toBe(true);
  });

  it("草稿版本：选型验收 PENDING（有记录才绿），阻断发布", async () => {
    const precheck = await fetchMockSkillPrecheck({
      ...publishedReadonlyTool,
      status: "DRAFT",
    });
    const acceptance = precheck.items.find((item) => item.key === "acceptance")!;
    expect(acceptance.status).toBe("PENDING");
    expect(precheck.passed).toBe(false);
  });

  it("契约缺 Schema 或无端点：对应项不过", async () => {
    const noSchema = await fetchMockSkillPrecheck({
      ...publishedReadonlyTool,
      outputSchema: undefined,
    });
    expect(noSchema.items.find((item) => item.key === "contract")!.status).toBe("FAIL");
    const noEndpoint = await fetchMockSkillPrecheck({
      ...publishedReadonlyTool,
      endpoint: "",
    });
    expect(noEndpoint.items.find((item) => item.key === "connection")!.status).toBe("PENDING");
    expect(noEndpoint.passed).toBe(false);
  });
});

describe("GUIDE 分型发布预检：两项且无连接测试/选型验收/意图字段", () => {
  const guide = {
    code: "triage-guide",
    name: "异常分诊指南",
    description: "何时使用分诊技能的说明",
    kind: "GUIDE",
    status: "DRAFT",
    content: "# 分诊步骤\n1. 读取遥测",
  };

  it("完整 GUIDE：两项全绿，不含 TOOL 专属项", async () => {
    const precheck = await fetchMockSkillPrecheck(guide);
    expect(precheck.kind).toBe("GUIDE");
    expect(precheck.items.map((item) => item.key)).toEqual(["frontmatter", "content"]);
    expect(precheck.items.every((item) => item.status === "PASS")).toBe(true);
    expect(precheck.passed).toBe(true);
  });

  it("frontmatter 缺 description 或正文为空：对应项不过并阻断发布", async () => {
    const noDescription = await fetchMockSkillPrecheck({ ...guide, description: "" });
    expect(noDescription.items.find((item) => item.key === "frontmatter")!.status).toBe("FAIL");
    expect(noDescription.passed).toBe(false);
    const noContent = await fetchMockSkillPrecheck({ ...guide, content: "  " });
    expect(noContent.items.find((item) => item.key === "content")!.status).toBe("PENDING");
    expect(noContent.passed).toBe(false);
  });

  it("GUIDE 版本缺省回退为 1", async () => {
    const precheck = await fetchMockSkillPrecheck({ ...guide, currentVersion: undefined });
    expect(precheck.version).toBe(1);
  });
});

describe("列表与详情页结构锚（源码级）", () => {
  it("SkillsPage：kind chips 筛选接入客户端过滤，保留 PageHeader actions 注册技能锚", () => {
    const page = read("pages/SkillsPage.vue");
    expect(page).toContain("matchesKindFilter(row, kindFilter.value)");
    expect(page).toContain("v-for=\"option in SKILL_KIND_FILTERS\"");
    expect(page).toContain("<template #actions>");
    expect(page).toContain("注册技能");
  });

  it("SkillDetailPage：单页分区（无 Tab、无 ResourceDetail），分区锚点滚动导航", () => {
    const page = read("pages/SkillDetailPage.vue");
    expect(page).not.toContain("ResourceDetail");
    expect(page).not.toContain("el-tab-pane");
    for (const id of [
      "skill-section-contract",
      "skill-section-connection",
      "skill-section-acceptance",
      "skill-section-versions",
    ])
      expect(page).toContain(`id="${id}"`);
    expect(page).toContain("scrollIntoView");
  });

  it("SkillDetailPage：TOOL/GUIDE 分型渲染——连接调试与选型验收仅 TOOL", () => {
    const page = read("pages/SkillDetailPage.vue");
    const toolOnly = page.match(/v-if="kind === 'TOOL'"/g)?.length ?? 0;
    expect(toolOnly).toBeGreaterThanOrEqual(2);
    expect(page).toContain("skillKindOf(detail.value)");
  });

  it("SkillDetailPage：checklist 由预检投影驱动（M5-2：shared/skill-precheck 自真实技能行推导），前端不自算", () => {
    const page = read("pages/SkillDetailPage.vue");
    expect(page).toContain("fetchSkillPrecheck");
    expect(page).toContain("precheck.value?.passed");
    expect(page).toContain(":disabled=\"!canPublish || busy\"");
    expect(page).toContain("publishBlockedReason");
    expect(page).toContain("item.fieldAnchor");
  });

  it("SkillDetailPage：意图字段锚点可定位 + 发布确认走 DangerConfirm", () => {
    const page = read("pages/SkillDetailPage.vue");
    expect(page).toContain('id="skill-field-businessIntentInput"');
    expect(page).toContain("confirmDanger");
    expect(page).not.toMatch(/\bconfirm\s*\(/);
  });
});
