// F3-2：管理台只读计划详情页——锁定四件事：
// ① demo 种子（mock-plan.ts F3-2 追加导出）的修订对比数据形状（对齐 task-states §5 与
//    mock-task.ts plan 投影，plan-9 当前修订两处一致）；② diffPlanSteps 简单 diff 语义；
// ③ 页面只读徽章三态（validationStatus / confirmation）与依赖分层（depends_on）；
// ④ 只读红线：页面不含确认/执行操作入口，注册追加且不进菜单。
// 页面装载沿用既有页面测试模式（run-detail-navigation）：真实 SFC setup，
// 替换路由与 .vue 依赖为受控桩（vitest 根配置无 vue 插件，不能直接 import .vue）。
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { compileScript, parse } from "@vue/compiler-sfc";
import ts from "typescript";
import * as Vue from "vue";
import { afterEach, expect, it } from "vitest";
import { describe } from "vitest";
import * as mockPlan from "../shared/mock-plan";
import { taskDetail } from "../shared/mock-task";
import { menuEntries, pageByKey } from "../admin/src/page-access";

const pageSource = readFileSync(
  new URL("../admin/src/pages/PlanDetailPage.vue", import.meta.url),
  "utf8",
);

const cleanup: (() => void)[] = [];
afterEach(() => {
  cleanup.splice(0).forEach((close) => close());
});

/** 真实 SFC setup + 桩依赖（vue-router / .vue 组件），返回页面 setupState（ref 已解包）。 */
function mountPlan(planId: string) {
  const { descriptor, errors } = parse(pageSource, { filename: "PlanDetailPage.vue" });
  expect(errors).toEqual([]);
  const code = ts.transpileModule(
    compileScript(descriptor, { id: "PlanDetail" }).content,
    { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } },
  ).outputText;
  const exports = {} as { default: Vue.Component & { render: () => null } };
  const require = createRequire(import.meta.url);
  const route = Vue.reactive({ params: { planId } });
  new Function("require", "exports", code)(
    (id: string) =>
      id === "vue"
        ? Vue
        : id.endsWith("vue-router")
          ? { useRoute: () => route }
          : id.endsWith("/shared/mock-plan")
            ? mockPlan
            : id.endsWith(".vue")
              ? { default: { render: () => null } }
              : require(id),
    exports,
  );
  exports.default.render = () => null;
  const renderer = Vue.createRenderer<object, object>({
    createComment: () => ({}),
    insert() {},
    remove() {},
    parentNode: () => null,
    nextSibling: () => null,
    createElement: () => ({}),
    createText: () => ({}),
    setText() {},
    setElementText() {},
    patchProp() {},
  });
  const app = renderer.createApp({ render: () => Vue.h(exports.default) });
  const instance = app.mount({}).$ as unknown as {
    subTree: { component: { setupState: any } };
  };
  cleanup.push(() => app.unmount());
  return { state: instance.subTree.component!.setupState as any, route };
}

describe("demo 种子：修订对比数据形状（task-states §5 / mock-task plan 投影）", () => {
  it("plan-9 含 ≥2 修订且升序；当前修订与 mock-task taskDetail().plan 投影一致", () => {
    const plan = mockPlan.demoPlanDetail("plan-9")!;
    expect(plan.planId).toBe("plan-9");
    expect(plan.taskId).toBe("task-demo-78");
    expect(plan.revisions.length).toBeGreaterThanOrEqual(2);
    expect(plan.revisions.map((r) => r.revision)).toEqual(
      [...plan.revisions.map((r) => r.revision)].sort((a, b) => a - b),
    );
    const latest = plan.revisions.at(-1)!;
    const projection = taskDetail("task-demo-78")!.plan!;
    expect(latest.revision).toBe(projection.revision);
    expect(latest.digest).toBe(projection.digest);
    expect(latest.validationStatus).toBe(projection.validationStatus);
    expect(latest.confirmation.state).toBe(projection.confirmation.state);
  });

  it("每个修订的 validationStatus/confirmation.state 均在契约枚举内；步骤 kind 覆盖五类，dependsOn/inputBindings 形状完整", () => {
    const plan = mockPlan.demoPlanDetail("plan-9")!;
    const validationStates = ["PENDING", "VALID", "INVALID"];
    const confirmationStates = ["UNCONFIRMED", "CONFIRMED_VALID", "CONFIRMED_EXPIRED"];
    const kinds = new Set<string>();
    for (const revision of plan.revisions) {
      expect(validationStates, `修订 ${revision.revision}`).toContain(revision.validationStatus);
      expect(confirmationStates, `修订 ${revision.revision}`).toContain(revision.confirmation.state);
      expect(revision.digest.length).toBeGreaterThan(0);
      const stepIds = new Set(revision.steps.map((s) => s.stepId));
      for (const step of revision.steps) {
        kinds.add(step.kind);
        expect(["SKILL", "FLOW", "AGENT", "MODEL", "CONDITION"]).toContain(step.kind);
        expect(step.targetRef.length).toBeGreaterThan(0);
        // dependsOn 引用完整：依赖的步骤必在同一修订内（展示层 chips 数据源可信）
        for (const dep of step.dependsOn) expect(stepIds.has(dep), `${step.stepId}→${dep}`).toBe(true);
        for (const binding of step.inputBindings) {
          expect(binding.input.length).toBeGreaterThan(0);
          expect(["TASK_INPUT", "STEP_OUTPUT", "CONSTANT"]).toContain(binding.source);
          expect(binding.value.length).toBeGreaterThan(0);
        }
      }
    }
    // demo 覆盖全部五种步骤 kind（SKILL/FLOW/AGENT/MODEL/CONDITION）
    expect([...kinds].sort()).toEqual(["AGENT", "CONDITION", "FLOW", "MODEL", "SKILL"]);
    // 未知 planId → null（不伪造计划）
    expect(mockPlan.demoPlanDetail("plan-404")).toBeNull();
  });

  it("diffPlanSteps：按 stepId 简单 diff，新增/删除双向可逆", () => {
    const { revisions } = mockPlan.demoPlanDetail("plan-9")!;
    const [rev1, rev2, rev3] = revisions;
    const addedIds = (d: { added: { stepId: string }[] }) => d.added.map((s) => s.stepId);
    // rev1 → rev3：新增 enhance/retry_gate/report，无删除
    expect(addedIds(mockPlan.diffPlanSteps(rev1, rev3))).toEqual(["enhance", "retry_gate", "report"]);
    expect(mockPlan.diffPlanSteps(rev1, rev3).removed).toEqual([]);
    // 反向：删除=原新增
    expect(mockPlan.diffPlanSteps(rev3, rev1).removed.map((s) => s.stepId)).toEqual([
      "enhance",
      "retry_gate",
      "report",
    ]);
    expect(mockPlan.diffPlanSteps(rev3, rev1).added).toEqual([]);
    // rev2 → rev3：enhance 两修订同名同源不算差异，只新增门控与报告
    expect(addedIds(mockPlan.diffPlanSteps(rev2, rev3))).toEqual(["retry_gate", "report"]);
  });
});

describe("页面行为（真实 setup）：徽章三态 / 依赖分层 / 修订对比", () => {
  it("validationStatus 徽章三态与 confirmation 徽章三态一一映射；未知值如实回显", () => {
    const { state } = mountPlan("plan-9");
    const validation = ["VALID", "PENDING", "INVALID"].map((s) => state.validationBadgeOf(s));
    expect(new Set(validation.map((b) => b.label)).size).toBe(3);
    expect(state.validationBadgeOf("VALID")).toMatchObject({ label: "校验通过", type: "success" });
    expect(state.validationBadgeOf("PENDING")).toMatchObject({ label: "校验中", type: "info" });
    expect(state.validationBadgeOf("INVALID")).toMatchObject({ label: "校验失败", type: "danger" });
    const confirmation = ["UNCONFIRMED", "CONFIRMED_VALID", "CONFIRMED_EXPIRED"].map((s) =>
      state.confirmationBadgeOf(s),
    );
    expect(new Set(confirmation.map((b) => b.label)).size).toBe(3);
    expect(state.confirmationBadgeOf("UNCONFIRMED")).toMatchObject({ label: "未确认", type: "info" });
    expect(state.confirmationBadgeOf("CONFIRMED_VALID")).toMatchObject({ label: "已确认有效", type: "success" });
    expect(state.confirmationBadgeOf("CONFIRMED_EXPIRED")).toMatchObject({ label: "已确认过期", type: "warning" });
    // 未知值不伪造状态：原文回显 + 中性色
    expect(state.validationBadgeOf("WHATEVER")).toEqual({ label: "WHATEVER", type: "info" });
  });

  it("依赖展示含 depends_on：默认查看当前修订，分层覆盖全部步骤且上游恒在更浅层", () => {
    const { state } = mountPlan("plan-9");
    // 默认查看修订=当前（末位）修订
    expect(state.viewed.revision).toBe(state.currentRevision.revision);
    const steps = state.viewed.steps as { stepId: string; dependsOn: string[] }[];
    // 步骤表数据源：每步都带 depends_on 数组（chips 渲染源）
    for (const step of steps) expect(Array.isArray(step.dependsOn)).toBe(true);
    const layerIds = (state.dependencyLayers as { stepId: string }[][]).map((layer) =>
      layer.map((s) => s.stepId),
    );
    // 分层列表不丢步骤：拍平 = 全部步骤
    expect(layerIds.flat().sort()).toEqual(steps.map((s) => s.stepId).sort());
    // DAG 分层正确性：每步依赖都位于严格更浅的层（首层无依赖）
    const depthOf = new Map<string, number>();
    layerIds.forEach((ids, depth) => ids.forEach((id) => depthOf.set(id, depth)));
    for (const step of steps) {
      if (!step.dependsOn.length) expect(depthOf.get(step.stepId)).toBe(0);
      for (const dep of step.dependsOn) {
        expect(depthOf.get(dep)!).toBeLessThan(depthOf.get(step.stepId)!);
      }
    }
    // 只读切换查看修订：视图跟随选择器，不产生任何写语义
    state.viewRevision = 1;
    expect(state.viewed.revision).toBe(1);
    expect(state.viewed.steps).toHaveLength(4);
  });

  it("修订对比：默认基准=上一修订→目标=当前修订；切换基准后步骤差异跟随", () => {
    const { state } = mountPlan("plan-9");
    const revisions = state.revisions as { revision: number }[];
    expect(state.compareBaseRevision.revision).toBe(revisions.at(-2)!.revision);
    expect(state.compareTargetRevision.revision).toBe(revisions.at(-1)!.revision);
    // 对比卡数据形状：digest / validationStatus / confirmation.state 每侧齐备
    for (const side of [state.compareBaseRevision, state.compareTargetRevision]) {
      expect(side.digest.length).toBeGreaterThan(0);
      expect(side.validationStatus).toBeDefined();
      expect(side.confirmation.state).toBeDefined();
    }
    expect(state.stepDiff.added.map((s: { stepId: string }) => s.stepId)).toEqual([
      "retry_gate",
      "report",
    ]);
    expect(state.stepDiff.removed).toEqual([]);
    // 切换基准修订 → diff 只读跟随
    state.compareBase = 1;
    expect(state.stepDiff.added.map((s: { stepId: string }) => s.stepId)).toEqual([
      "enhance",
      "retry_gate",
      "report",
    ]);
    // 输入绑定：拍平行=查看修订各步绑定之和，行含 stepId/input/source/value
    const rows = state.bindingRows as { stepId: string; input: string; source: string; value: string }[];
    expect(rows).toHaveLength(9);
    for (const row of rows) {
      expect(row.input.length).toBeGreaterThan(0);
      expect(row.source.length).toBeGreaterThan(0);
      expect(row.value.length).toBeGreaterThan(0);
    }
  });
});

describe("只读红线与注册（静态断言）", () => {
  it("页面不含确认/执行操作入口：无 el-button、无确认执行文案、不引入写计划 mock 入口", () => {
    // 页面自身不渲染任何按钮（PageHeader 的刷新按钮属壳层组件，非本页源码）
    expect(pageSource.match(/<el-button/g)).toBeNull();
    expect(pageSource).not.toContain("确认执行");
    // 只读确认状态徽章在场（UNCONFIRMED/CONFIRMED_VALID/CONFIRMED_EXPIRED 三态标签）
    expect(pageSource).toContain("已确认有效");
    expect(pageSource).toContain("已确认过期");
    expect(pageSource).toContain("未确认");
    // 不从 mock 契约引入任何写入口（confirm/execute/append 均为助手端 F4-3 的动作）
    expect(pageSource).not.toMatch(/confirmPlan|executePlan|appendPlanRevision|seedPlanTask/);
    // 空态也不带动作按钮（EmptyState CTA 显式隐藏）
    expect(pageSource).toContain('action-text=""');
  });

  it("注册追加式：/plans/:planId 深链路由 + page-access 任务与运行条目，不进菜单", () => {
    const routerSource = readFileSync(
      new URL("../admin/src/router.ts", import.meta.url),
      "utf8",
    );
    expect(routerSource).toContain('path: "/plans/:planId"');
    expect(routerSource).toContain('name: "plan-detail"');
    expect(routerSource).toContain('access: "plan-detail"');
    const entry = pageByKey["plan-detail"];
    expect(entry).toBeDefined();
    expect(entry.route).toBe("/plans/:planId");
    expect(entry.area).toBe("任务与运行");
    expect(entry.read).toBe("session");
    expect(entry.write).toBe("");
    expect(entry.menu).toBe(false);
    // 菜单数量不受参数页影响（navigation-policy 基线：F3-3 资产管理四页后 19）
    expect(menuEntries().length).toBe(19);
  });
});
