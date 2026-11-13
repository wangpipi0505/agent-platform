// T2-14：任务/计划/会话 mock 契约测试——锁定冻结契约的可判定行为（消费方 F3/F4 依赖这些语义）。
import { describe, expect, it } from "vitest";
import { availableActions, submitTask, taskDetail, taskEvents, demoTaskSeeds } from "../shared/mock-task";
import { appendPlanRevision, confirmPlan, executePlan, seedPlanTask, resetPlans } from "../shared/mock-plan";
import { listMySessions, truncateTitle } from "../shared/mock-session";

describe("mock-task（接口稿 §5.2 + task-states §3）", () => {
  it("requestId 幂等：同参重放原任务，异参 409 REQUEST_REPLAY；mode 必填", () => {
    const first = submitTask({ requestId: "r-1", mode: "AGENT", goal: "g", inputs: { a: 1 } });
    expect(first).toMatchObject({ taskId: "task-demo-81", state: "QUEUED" });
    const replay = submitTask({ requestId: "r-1", mode: "AGENT", goal: "g", inputs: { a: 1 } });
    expect(replay).toMatchObject({ taskId: "task-demo-81" });
    const conflict = submitTask({ requestId: "r-1", mode: "AGENT", goal: "g2" });
    expect(conflict).toMatchObject({ error: "REQUEST_REPLAY", httpStatus: 409 });
    const noMode = submitTask({ requestId: "r-2", goal: "g" });
    expect(noMode).toMatchObject({ error: "PARAM_MODE_REQUIRED", httpStatus: 400 });
  });

  it("availableActions 按状态矩阵计算（前端只渲染不自判）", () => {
    expect(availableActions("WAITING_DEPENDENCIES")).toEqual(["VIEW_DEPENDENCIES", "CANCEL"]);
    expect(availableActions("RUNNING")).toEqual(["CANCEL"]);
    expect(availableActions("SUCCEEDED")).toEqual(["REISSUE", "EXPORT"]);
    expect(availableActions("WAITING_RECONCILIATION", { reconcilable: true })).toContain("RETRY");
    expect(availableActions("WAITING_RECONCILIATION", { reconcilable: false })).not.toContain("RETRY");
  });

  it("事件游标：nextAfterSeq=最后扫描 seq 不 +1；truncated 如实", () => {
    const page = taskEvents("task-demo-77", 0, 2);
    expect(page.events.map((e) => e.seq)).toEqual([1, 2]);
    expect(page.nextAfterSeq).toBe(2);
    expect(page.truncated).toBe(true);
    const end = taskEvents("task-demo-77", 4, 200);
    expect(end.nextAfterSeq).toBe(4);
    expect(end.truncated).toBe(false);
  });

  it("详情：PLAN_READY 任务给服务端计算的 canConfirm/canExecute 与 plan.confirmation", () => {
    const detail = taskDetail("task-demo-78")!;
    expect(detail.canConfirm).toBe(true);
    expect(detail.canExecute).toBe(false);
    expect(detail.plan?.confirmation.state).toBe("UNCONFIRMED");
    expect(detail.demo).toBe(true);
    expect(demoTaskSeeds().every((t) => t.demo)).toBe(true);
  });
});

describe("mock-plan（确认凭据与修订 CAS，task-states §5）", () => {
  it("baseRevision 过期 409 PLAN_REVISION_CONFLICT；新修订旧凭据不继承", () => {
    resetPlans();
    seedPlanTask(structuredClone(demoTaskSeeds()[1]) as never);
    const stale = appendPlanRevision("task-demo-78", { requestId: "p-1", baseRevision: 2 });
    expect(stale).toMatchObject({ error: "PLAN_REVISION_CONFLICT", httpStatus: 409 });
    const ok = appendPlanRevision("task-demo-78", { requestId: "p-1", baseRevision: 3 });
    expect(ok).toMatchObject({ revision: 4, validationStatus: "VALID" });
    expect(executePlan("task-demo-78", { requestId: "e-0", planId: "plan-9", revision: 4, digest: "dg4" }))
      .toMatchObject({ error: "PLAN_NOT_CONFIRMED", httpStatus: 409 });
  });

  it("confirm digest 不符 409 PLAN_DIGEST_CHANGED；确认后 execute 受理", () => {
    const wrong = confirmPlan("task-demo-78", { requestId: "c-0", digest: "bad", confirmedBy: "u-demo" });
    expect(wrong).toMatchObject({ error: "PLAN_DIGEST_CHANGED", httpStatus: 409 });
    const ok = confirmPlan("task-demo-78", { requestId: "c-1", digest: "dg4", confirmedBy: "u-demo" });
    expect(ok).toMatchObject({ revision: 4, confirmSeq: 1 });
    const done = executePlan("task-demo-78", { requestId: "e-1", planId: "plan-9", revision: 4, digest: "dg4" });
    expect(done).toMatchObject({ executionTaskId: "task-demo-exec-4", pinnedDigest: "dg4" });
  });
});

describe("mock-session（S2-1，接口稿 §5.1）", () => {
  it("排除 TASK_INTERNAL；标题截断 ≤48；before 游标翻页", () => {
    expect(truncateTitle("长".repeat(50))).toHaveLength(48);
    const page1 = listMySessions({ mine: true, limit: 1 });
    expect(page1.sessions).toHaveLength(1);
    expect(page1.sessions[0].sessionId).toBe("sess-demo-28");
    expect(page1.nextBefore).toBe("2026-11-13T10:00:00Z");
    const page2 = listMySessions({ mine: true, limit: 1, before: page1.nextBefore });
    expect(page2.sessions.map((s) => s.sessionId)).toEqual(["sess-demo-27"]);
    expect(page2.nextBefore).toBeUndefined();
    const all = listMySessions({ mine: true });
    expect(all.sessions.some((s) => s.sessionId.includes("internal"))).toBe(false);
    expect(() => listMySessions({})).toThrow();
  });
});
