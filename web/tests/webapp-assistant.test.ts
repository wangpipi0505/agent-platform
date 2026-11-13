// F4-1/F4-2/F4-3（webapp 助手端三卡连做，mock 期）：mock 契约行为单测 + 组件结构静态断言。
// 契约单测消费 T2-14 已交付形状（mock-session/mock-task/mock-plan）+ F4 新交付 mock-memories
// 与 plan-buttons 状态机纯函数；组件结构与红线（tokens/无原生 confirm/窄屏抽屉/host-channel
// 保持）按 workspace-shell.test.ts 同口径源断言锁定。真实交互属阶段二联调。
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import {
  demoAdvanceTask,
  demoCreateExecTask,
  submitTask,
  taskDetail,
  taskEvents,
} from "../shared/mock-task";
import { confirmPlan, executePlan, seedPlanTask } from "../shared/mock-plan";
import { listMySessions, truncateTitle } from "../shared/mock-session";
import {
  createMemory,
  deleteMemory,
  getMemory,
  listMemories,
  updateMemory,
} from "../shared/mock-memories";
import { planButtons } from "../webapp/src/plan-buttons";
import { demoFlowFor } from "../webapp/src/demo-flow";

const read = (name: string) =>
  readFileSync(new URL("../" + name, import.meta.url), "utf8");
const ok = <T>(value: T) => value as Extract<T, { taskId: string }> | Extract<T, { memoryId: string }>;

describe("F4-1 会话列表（mock-session mine=true，S2-1 契约）", () => {
  it("mine 过滤：TASK_INTERNAL 不进列表，lastActiveAt 倒序；mine 缺省拒绝", () => {
    const page = listMySessions({ mine: true });
    expect(page.sessions.map((s) => s.sessionId)).toEqual([
      "sess-demo-28",
      "sess-demo-27",
    ]);
    expect(
      page.sessions.every((s) => !s.title.includes("TASK_INTERNAL")),
    ).toBe(true);
    expect(page.nextBefore).toBeUndefined();
    expect(() => listMySessions({})).toThrow("mine=true 首期固定");
  });

  it("before 游标切换分页：晚于游标行排除，nextBefore 末页省略", () => {
    const page1 = listMySessions({ mine: true, limit: 1 });
    expect(page1.sessions.map((s) => s.sessionId)).toEqual(["sess-demo-28"]);
    expect(page1.nextBefore).toBe("2026-11-13T10:00:00Z");
    const page2 = listMySessions({
      mine: true,
      limit: 1,
      before: page1.nextBefore,
    });
    expect(page2.sessions.map((s) => s.sessionId)).toEqual(["sess-demo-27"]);
    expect(page2.nextBefore).toBeUndefined();
  });

  it("标题=首条用户消息截断 ≤48（稳定规则）", () => {
    expect(truncateTitle("一".repeat(60))).toHaveLength(48);
    expect(truncateTitle("短标题")).toBe("短标题");
  });

  it("SessionList 结构：mine 数据源/整行切换/新建入口/重命名删除占位 disabled+tooltip 后续批次", () => {
    const src = read("webapp/src/SessionList.vue");
    const tpl = src.split("<template>")[1] ?? "";
    expect(src).toContain("listMySessions({ mine: true");
    expect(src).toContain("@click=\"select(row)\"");
    expect(src).toContain("emit('create')");
    expect(src).toContain("新建会话");
    expect(tpl.match(/后续批次/g)?.length).toBe(2);
    expect(src).toContain("link disabled>重命名");
    expect(src).toContain("link disabled>删除");
    expect(src).toContain("v-if=\"nextBefore\"");
    expect(src).toContain("加载更多");
  });
});

describe("F4-2 执行请求（submitTask 契约 + AssistantChat 输入区）", () => {
  it("requestId 幂等：同参重放原任务；异参 409 REQUEST_REPLAY；mode 必填 400", () => {
    const first = submitTask({
      requestId: "wa-r-1",
      mode: "AGENT",
      goal: "差异报告",
      inputs: { message: "差异报告" },
      sessionId: "sess-demo-28",
      sessionCursor: 0,
    });
    expect(first).toMatchObject({ state: "QUEUED", acceptedPlanMode: "AGENT" });
    const replay = submitTask({
      requestId: "wa-r-1",
      mode: "AGENT",
      goal: "差异报告",
      inputs: { message: "差异报告" },
      sessionId: "sess-demo-28",
      sessionCursor: 0,
    });
    expect(replay).toHaveProperty("taskId", ok(first).taskId);
    const conflict = submitTask({
      requestId: "wa-r-1",
      mode: "AGENT",
      goal: "另一目标",
    });
    expect(conflict).toMatchObject({
      error: "REQUEST_REPLAY",
      httpStatus: 409,
    });
    const noMode = submitTask({ requestId: "wa-r-2", goal: "g" });
    expect(noMode).toMatchObject({
      error: "PARAM_MODE_REQUIRED",
      httpStatus: 400,
    });
  });

  it("PLAN 模式受理 PLANNING；demoAdvance 到 PLAN_READY 附 UNCONFIRMED 计划，事件游标如实", () => {
    const res = ok(
      submitTask({ requestId: "wa-r-3", mode: "PLAN", goal: "规划观测序列" }),
    );
    expect(res.state).toBe("PLANNING");
    const advanced = demoAdvanceTask(res.taskId);
    expect(advanced?.state).toBe("PLAN_READY");
    expect(advanced?.plan?.confirmation.state).toBe("UNCONFIRMED");
    expect(advanced?.plan?.validationStatus).toBe("VALID");
    expect(advanced?.steps.length).toBeGreaterThan(0);
    const page = taskEvents(res.taskId, 0);
    expect(page.events.map((e) => e.type)).toEqual([
      "TASK_ACCEPTED",
      "PLAN_VALIDATED",
    ]);
    expect(page.nextAfterSeq).toBe(2);
    expect(page.truncated).toBe(false);
  });

  it("AGENT 任务 demo 推进到终态（availableActions=REISSUE/EXPORT）；游标增量消费", () => {
    expect(taskDetail("task-demo-77")?.state).toBe("RUNNING");
    demoAdvanceTask("task-demo-77");
    const done = taskDetail("task-demo-77")!;
    expect(done.state).toBe("SUCCEEDED");
    expect(done.availableActions).toEqual(["REISSUE", "EXPORT"]);
    const page = taskEvents("task-demo-77", 4);
    expect(page.events.map((e) => e.seq)).toEqual([5, 6]);
    expect(page.nextAfterSeq).toBe(6);
    expect(page.truncated).toBe(false);
  });

  it("执行任务登记与任务详情同源（F4-3 确认→执行闭环，demo:true）", () => {
    const source = taskDetail("task-demo-77")!;
    const exec = demoCreateExecTask("task-demo-exec-9", "task-demo-77");
    expect(exec.demo).toBe(true);
    expect(taskDetail("task-demo-exec-9")?.goal).toBe(source.goal);
    expect(taskDetail("task-demo-exec-9")?.purpose).toBe("EXECUTE");
  });

  it("AssistantChat 结构：默认 AGENT/请求形状含 sessionId+sessionCursor/终态回填草稿/保存为记忆入口", () => {
    const src = read("webapp/src/AssistantChat.vue");
    expect(src).toContain('ref<"PLAN" | "AGENT">("AGENT")');
    expect(src).toContain("value=\"PLAN\">Plan 规划");
    expect(src).toContain("value=\"AGENT\">Agent 执行");
    expect(src).toContain("requestId: crypto.randomUUID()");
    expect(src).toContain("mode: mode.value");
    expect(src).toContain("goal,");
    expect(src).toContain("inputs: { message: goal }");
    expect(src).toContain("sessionId: sessionId || undefined");
    expect(src).toContain("sessionCursor: cursor.value");
    expect(src).toContain("draft.value = task.goal");
    expect(src).toContain("mode.value = task.mode");
    expect(src).toContain("保存为记忆");
    expect(src).toContain("@executed=\"onExecuted\"");
    // PLAN 路径：计划就绪即追加计划卡（确认→执行入口），不让执行请求停在任务卡。
    expect(src).toContain('kind: "plan"');
  });
});

describe("F4-3 计划卡按钮状态机（plan-buttons：confirmState×canConfirm/canExecute 矩阵）", () => {
  it("UNCONFIRMED：canConfirm=true 确认可点+执行禁用（待确认）；canConfirm=false 确认隐藏+待确认人提示", () => {
    expect(
      planButtons({
        confirmState: "UNCONFIRMED",
        canConfirm: true,
        canExecute: false,
      }),
    ).toMatchObject({
      confirm: { visible: true, disabled: false, label: "确认" },
      execute: { disabled: true, tooltip: "待确认" },
      waitingNote: "",
    });
    expect(
      planButtons({
        confirmState: "UNCONFIRMED",
        canConfirm: false,
        canExecute: false,
      }),
    ).toMatchObject({
      confirm: { visible: false },
      execute: { disabled: true },
      waitingNote: "待提交人/APPROVER 确认",
    });
  });

  it("CONFIRMED_VALID：确认禁用显示凭据摘要；执行仅按所选修订 canExecute（不自判）", () => {
    const valid = planButtons({
      confirmState: "CONFIRMED_VALID",
      canConfirm: false,
      canExecute: true,
      confirmedBy: "u-demo",
      expiresAt: "2026-11-13T18:00:00Z",
    });
    expect(valid.confirm).toMatchObject({ visible: true, disabled: true });
    expect(valid.confirm.note).toContain("u-demo");
    expect(valid.confirm.note).toContain("2026-11-13T18:00:00Z");
    expect(valid.execute).toEqual({ disabled: false, tooltip: "" });
    expect(
      planButtons({
        confirmState: "CONFIRMED_VALID",
        canConfirm: false,
        canExecute: false,
      }).execute,
    ).toEqual({ disabled: true, tooltip: "不可执行" });
  });

  it("CONFIRMED_EXPIRED：重新确认（追加凭据）可点；INVALID/PENDING 双禁用", () => {
    expect(
      planButtons({
        confirmState: "CONFIRMED_EXPIRED",
        canConfirm: true,
        canExecute: false,
      }),
    ).toMatchObject({
      confirm: { visible: true, disabled: false, label: "重新确认" },
      execute: { disabled: true, tooltip: "凭据已过期" },
    });
    const invalid = planButtons({
      confirmState: "UNCONFIRMED",
      canConfirm: true,
      canExecute: false,
      validationStatus: "INVALID",
    });
    expect(invalid.confirm.disabled).toBe(true);
    expect(invalid.execute.disabled).toBe(true);
    expect(
      planButtons({
        confirmState: "UNCONFIRMED",
        canConfirm: true,
        canExecute: false,
        validationStatus: "PENDING",
      }).confirm.disabled,
    ).toBe(true);
  });

  it("confirm→execute 闭环（mock-plan 契约）：未确认 execute 409→确认→canExecute 翻转→受理钉住修订", () => {
    seedPlanTask(structuredClone(taskDetail("task-demo-78")) as never);
    expect(taskDetail("task-demo-78")?.canConfirm).toBe(true);
    expect(taskDetail("task-demo-78")?.canExecute).toBe(false);
    expect(
      executePlan("task-demo-78", {
        requestId: "wa-e-0",
        planId: "plan-9",
        revision: 3,
        digest: "cd02",
      }),
    ).toMatchObject({ error: "PLAN_NOT_CONFIRMED", httpStatus: 409 });
    expect(
      confirmPlan("task-demo-78", {
        requestId: "wa-c-1",
        digest: "cd02",
        confirmedBy: "u-demo",
      }),
    ).toMatchObject({ revision: 3, confirmedBy: "u-demo" });
    expect(taskDetail("task-demo-78")?.canExecute).toBe(true);
    expect(
      executePlan("task-demo-78", {
        requestId: "wa-e-1",
        planId: "plan-9",
        revision: 3,
        digest: "cd02",
      }),
    ).toMatchObject({ pinnedRevision: 3 });
  });

  it("PlanCard 结构：服务端 canConfirm/canExecute 直通不自判；过期→重新确认；NOT_CONFIRMED 引导", () => {
    const src = read("webapp/src/cards/PlanCard.vue");
    expect(src).toContain("canConfirm: d.canConfirm");
    expect(src).toContain("canExecute: d.canExecute");
    expect(src).toContain("confirmState: d.plan.confirmation.state");
    expect(src).not.toMatch(/canConfirm\s*===|canConfirm\s*&&|canConfirm\s*\|\|/);
    expect(src).toContain("重新确认所选版本");
    expect(src).toContain("userMessage");
    expect(src).toContain("凭据已过期");
  });
});

describe("F4-2 我的记忆（mock-memories CRUD 形状，接口稿 §5.4）", () => {
  it("保存形状：ACTIVE/version1/demo:true/scope 默认 PRIVATE/写 SHARED 拒绝/requestId 幂等", () => {
    const a = ok(
      createMemory({
        requestId: "wa-m-1",
        content: "云量阈值偏好 30%",
        kind: "PREFERENCE",
        tags: ["观测"],
        sessionId: "sess-demo-28",
      }),
    );
    expect(a).toMatchObject({
      status: "ACTIVE",
      version: 1,
      scope: "PRIVATE",
      kind: "PREFERENCE",
      demo: true,
    });
    const replay = ok(
      createMemory({
        requestId: "wa-m-1",
        content: "云量阈值偏好 30%",
        kind: "PREFERENCE",
      }),
    );
    expect(replay.memoryId).toBe(a.memoryId);
    expect(
      createMemory({
        requestId: "wa-m-2",
        content: "x",
        kind: "PREFERENCE",
        scope: "SHARED",
      }),
    ).toMatchObject({ error: "SCOPE_WRITE_DENIED", httpStatus: 403 });
    expect(
      listMemories({ q: "云量" }).memories.some((m) => m.memoryId === a.memoryId),
    ).toBe(true);
  });

  it("列表项不含正文；单条读取正文；修改版本 CAS 过期 409 MEMORY_VERSION_CONFLICT", () => {
    const list = listMemories({});
    expect(list.memories.every((m) => !("content" in m))).toBe(true);
    const first = list.memories[0]!;
    expect(getMemory(first.memoryId)).toHaveProperty("content");
    expect(
      updateMemory(first.memoryId, {
        content: "x",
        expectedVersion: first.version + 5,
      }),
    ).toMatchObject({ error: "MEMORY_VERSION_CONFLICT", httpStatus: 409 });
    expect(
      updateMemory(first.memoryId, {
        content: "更新后的正文",
        expectedVersion: first.version,
      }),
    ).toMatchObject({ version: first.version + 1, content: "更新后的正文" });
  });

  it("删除：先置 REVOKED 立即退出检索，幂等；撤销后单条读取不可见", () => {
    const created = ok(
      createMemory({
        requestId: "wa-m-3",
        content: "待删除条目",
        kind: "EXPERIENCE",
        tags: ["临时"],
      }),
    );
    expect(
      deleteMemory(created.memoryId, { requestId: "wa-d-1" }),
    ).toMatchObject({ memoryId: created.memoryId, status: "REVOKED" });
    expect(
      listMemories({}).memories.some((m) => m.memoryId === created.memoryId),
    ).toBe(false);
    expect(getMemory(created.memoryId)).toMatchObject({
      error: "MEMORY_NOT_FOUND",
    });
    expect(
      deleteMemory(created.memoryId, { requestId: "wa-d-2" }),
    ).toMatchObject({ status: "REVOKED" });
  });

  it("MyMemories 结构：保存表单回填/CAS 提交/confirmDanger 删除（无原生 confirm）", () => {
    const src = read("webapp/src/MyMemories.vue");
    expect(src).toContain("defineExpose({ openSaveForm })");
    expect(src).toContain("sessionId: saveSessionId.value || undefined");
    expect(src).toContain("expectedVersion: selected.value.version");
    expect(src).toContain("confirmDanger({");
    expect(src).not.toMatch(/\bconfirm\(/);
    expect(src).toContain("MEMORY_VERSION_CONFLICT");
  });
});

describe("F4-3 消息流三类卡片", () => {
  it("demo-flow 装配：会话流含任务卡/计划卡/工具卡，未选中与本地新会话为空流", () => {
    const runFlow = demoFlowFor("sess-demo-28");
    expect(runFlow.some((m) => m.kind === "task")).toBe(true);
    expect(runFlow.filter((m) => m.kind === "tool")).toHaveLength(2);
    expect(demoFlowFor("sess-demo-27").some((m) => m.kind === "plan")).toBe(
      true,
    );
    expect(demoFlowFor("sess-demo-local-x")).toEqual([]);
    expect(demoFlowFor("")).toEqual([]);
  });

  it("ToolCallCard 默认折叠：头部=名称/参数摘要/状态，aria-expanded，点击展开参数与结果", () => {
    const src = read("webapp/src/cards/ToolCallCard.vue");
    expect(src).toContain("const open = ref(false);");
    expect(src).toContain(':aria-expanded="open"');
    expect(src).toContain("@click=\"toggle\"");
    expect(src).toContain('v-if="open"');
    expect(src).toContain("paramsSummary");
    expect(src).toContain("tool-state");
    expect(src).toContain("结果");
  });

  it("TaskCard 与工作台同源 mock-task：taskEvents 游标增量/状态徽章/步骤树/子任务占位/终态带回草稿", () => {
    const src = read("webapp/src/cards/TaskCard.vue");
    expect(src).toContain("taskDetail(props.taskId)");
    expect(src).toContain("taskEvents(props.taskId, cursor.value)");
    expect(src).toContain("cursor.value = page.nextAfterSeq");
    expect(src).toContain("无子任务");
    expect(src).toContain("带回会话草稿重新提交");
    expect(src).toContain('emit("reissue"');
  });
});

describe("红线与 webapp 集成（host-channel 保持/tokens/断点）", () => {
  const files = [
    "webapp/src/App.vue",
    "webapp/src/SessionList.vue",
    "webapp/src/MyMemories.vue",
    "webapp/src/AssistantChat.vue",
    "webapp/src/cards/ToolCallCard.vue",
    "webapp/src/cards/PlanCard.vue",
    "webapp/src/cards/TaskCard.vue",
  ];

  it("host-channel 不破坏：App 保留 hello 通道 connectHostChannel/notifyOpened 与开发登录门", () => {
    const app = read("webapp/src/App.vue");
    expect(app).toContain("connectHostChannel({ appCode: code })");
    expect(app).toContain("hostChannel?.notifyOpened()");
    expect(app).toContain("hostChannel?.close()");
    expect(app).toContain("开发登录");
    expect(read("webapp/src/host-channel.ts")).toContain(
      "export function connectHostChannel",
    );
  });

  it("窄屏 <768px 会话列表收抽屉（translateX 抽屉+scrim），无 clamp(45rem 固定宽回归", () => {
    const app = read("webapp/src/App.vue");
    expect(app).toContain("@media (max-width: 768px)");
    expect(app).toContain("transform: translateX(-100%)");
    expect(app).toContain("matchMedia(\"(max-width: 768px)\")");
    expect(app).toContain('class="scrim"');
    for (const f of files) expect(read(f)).not.toContain("clamp(45rem");
  });

  it("样式只用 tokens 变量：新增/改动的 webapp 组件 style 块无裸色值（#hex/rgb()）", () => {
    for (const f of files) {
      const style = read(f).split("<style")[1] ?? "";
      expect(style).not.toMatch(/#[0-9a-fA-F]{3,8}\b|rgba?\(/);
    }
  });

  it("全组件无原生 confirm（window.confirm 调用）；删除等危险动作走 confirmDanger", () => {
    for (const f of files) {
      // \bconfirm\( 且后随大写=confirmDanger 等自有 API，不算原生调用；本地动作名不含裸 confirm(。
      expect(read(f).replace(/confirmDanger\(/g, "")).not.toMatch(
        /\bconfirm\(/,
      );
    }
    expect(read("webapp/src/MyMemories.vue")).toContain("confirmDanger({");
  });

  it("webapp 独立入口不共用 admin router：无 vue-router 引入，靠侧栏 tab 切换", () => {
    for (const f of files) expect(read(f)).not.toContain("vue-router");
    const app = read("webapp/src/App.vue");
    expect(app).toContain("sideTab === 'sessions'");
    expect(app).toContain("sideTab === 'memories'");
  });
});
