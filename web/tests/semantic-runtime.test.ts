import { describe, expect, it } from "vitest";
import {
  authoritativeView,
  buildGoalCriterion,
  buildSemanticBindingRequest,
  canReadSemantics,
  canWriteSemantics,
  filterProjection,
  goalOutcomeRows,
  SemanticFormError,
  type SemanticBindingForm,
  type SemanticSelection,
} from "../shared/semantic-runtime";

const releaseRef = {
  spaceCode: "space-a",
  releaseId: "11111111-1111-1111-1111-111111111111",
  version: "v3",
  contentDigest: "a".repeat(64),
};

const selection: SemanticSelection = {
  releaseRef,
  objectTypes: ["Task"],
  actions: ["task_read", "task_close"],
  extensions: [
    {
      id: "22222222-2222-2222-2222-222222222222",
      opCode: "task_read",
      profile: "read-only@1.0",
      schemaVersion: "1.0",
      status: "ACTIVE",
      descriptor: { objectType: "Task", factFields: [{ field: "score", kind: "attribute" }] },
    },
    {
      id: "33333333-3333-3333-3333-333333333333",
      opCode: "task_close",
      profile: "read-only@1.0",
      schemaVersion: "1.0",
      status: "RETIRED",
      descriptor: { objectType: "Task", factFields: [{ field: "status", kind: "attribute" }] },
    },
  ],
};

function form(overrides: Partial<SemanticBindingForm> = {}): SemanticBindingForm {
  return {
    releaseRef,
    objectType: "Task",
    opCode: "task_read",
    extensionId: selection.extensions[0].id,
    skillCode: "customers.read",
    skillVersion: 3,
    factFields: [],
    ...overrides,
  };
}

describe("SEM-09 语义绑定表单", () => {
  it("只对可写角色展示配置入口，读角色也能看到只读证据", () => {
    expect(canReadSemantics(["VIEWER"])).toBe(true);
    expect(canWriteSemantics(["VIEWER"])).toBe(false);
    expect(canWriteSemantics(["EDITOR"])).toBe(true);
    expect(canReadSemantics([])).toBe(false);
  });

  it("从服务端已发布选择集构造请求，忽略调用方注入的授权字段", () => {
    const request = buildSemanticBindingRequest(
      {
        ...form(),
        // 调用方夹带：实现必须丢弃，不能借此扩大源权限。
        ...({ scope: { region: ["all"] }, grant: "OWNER", spaceCode: "other" } as object),
      } as SemanticBindingForm,
      selection,
    );
    expect(request).toEqual({
      releaseRef,
      extensionId: selection.extensions[0].id,
      opCode: "task_read",
      objectType: "Task",
      skill: { skillCode: "customers.read", skillVersion: 3 },
      profiles: ["read-only@1.0"],
      factFields: [{ field: "score", kind: "attribute" }],
    });
    expect(Object.keys(request).sort()).toEqual([
      "extensionId",
      "factFields",
      "objectType",
      "opCode",
      "profiles",
      "releaseRef",
      "skill",
    ]);
  });

  it("拒绝自由输入的对象/动作、停用扩展与跨版本引用", () => {
    expect(() => buildSemanticBindingRequest(form({ objectType: "Order" }), selection)).toThrow(SemanticFormError);
    expect(() => buildSemanticBindingRequest(form({ opCode: "task_delete" }), selection)).toThrow(/动作不在/);
    expect(() =>
      buildSemanticBindingRequest(form({ extensionId: selection.extensions[1].id, opCode: "task_close" }), selection),
    ).toThrow(/未激活/);
    expect(() =>
      buildSemanticBindingRequest(form({ releaseRef: { ...releaseRef, version: "v4" } }), selection),
    ).toThrow(/固定发布版本/);
    expect(() => buildSemanticBindingRequest(form({ skillVersion: 0 }), selection)).toThrow(/正整数/);
  });
});

describe("SEM-09 权威卡片", () => {
  it("模型伪造成功文本不能覆盖服务端事实", () => {
    const view = authoritativeView(
      { state: "PENDING", message: "业务系统尚未落库" },
      "已为你完成退款并通知客户",
    );
    expect(view.state).toBe("PENDING");
    expect(view.authoritative).toBe(true);
    expect(view.modelTextIgnored).toBe(true);
    expect(view.note).toBe("业务系统尚未落库");
  });

  it("没有服务端回执时不显示成功", () => {
    const view = authoritativeView(null, "已经成功");
    expect(view.state).toBe("UNVERIFIED");
    expect(view.authoritative).toBe(false);
    expect(view.modelTextIgnored).toBe(true);
    expect(view.evidence).toEqual([]);
  });

  it("服务端报告成功但没有可核验回执时降级为未验证", () => {
    const view = authoritativeView({ state: "SUCCEEDED", message: "ok" });
    expect(view.state).toBe("UNVERIFIED");
    expect(view.authoritative).toBe(true);
    expect(view.evidence).toEqual([]);
  });

  it("带 call 回执的成功才可作为完成展示，并保留回执引用", () => {
    const view = authoritativeView({
      state: "SUCCEEDED",
      evidence: [{ evidenceKind: "call", callId: "c-1" }],
    });
    expect(view.state).toBe("SUCCEEDED");
    expect(view.evidence[0]).toMatchObject({ evidenceKind: "call", callId: "c-1" });
    expect(view.modelTextIgnored).toBe(false);
  });

  it("畸形回执不被当作证据", () => {
    const view = authoritativeView({
      state: "SUCCEEDED",
      evidence: [{ evidenceKind: "call" }, { evidenceKind: "artifact", artifactId: 0 }],
    });
    expect(view.state).toBe("UNVERIFIED");
    expect(view.evidence).toEqual([]);
  });
});

describe("SEM-09 目标状态卡片", () => {
  it("ACHIEVED 缺 readbackRef 时降级为 UNKNOWN 并给出问题", () => {
    const [row] = goalOutcomeRows([{ goalId: "refund-done", outcomeState: "ACHIEVED" }]);
    expect(row.outcomeState).toBe("UNKNOWN");
    expect(row.issues[0]).toMatch(/回执/);
    expect(row.label).toBe("无法判定");
  });

  it("等待/未知必须有下一步，否则标出缺失", () => {
    const rows = goalOutcomeRows([
      { goalId: "a", outcomeState: "PENDING" },
      { goalId: "b", outcomeState: "UNKNOWN", nextStep: "30 分钟后按只读操作重查" },
    ]);
    expect(rows[0].issues).toHaveLength(1);
    expect(rows[1].issues).toHaveLength(0);
    expect(rows[1].nextStep).toContain("重查");
  });
});

describe("SEM-09 定义选择与权限", () => {
  const projection = {
    definitions: {
      objects: [{ ref: { id: "o1", name: "Task" } }, { ref: { id: "o2", name: "Order" } }],
      actions: [
        { ref: { id: "a1", name: "读取任务" }, code: "task_read" },
        { ref: { id: "a2", name: "删除任务" }, code: "task_delete" },
      ],
      rules: [{ ref: { id: "r1", name: "金额上限" } }],
    },
  };

  it("无读取权限时不返回任何内部资源标识", () => {
    const visible = filterProjection(projection, { canRead: false });
    expect(visible.visible).toBe(false);
    expect(visible.objects).toEqual([]);
    expect(visible.actions).toEqual([]);
    expect(visible.reason).toContain("无权");
  });

  it("只展示已授权动作，并提示不要用表单扩大权限", () => {
    const visible = filterProjection(projection, {
      canRead: true,
      allowedActions: ["task_read"],
      allowedObjectTypes: ["Task"],
    });
    expect(visible.actions.map((action) => action.code)).toEqual(["task_read"]);
    expect(visible.objects.map((object) => object.ref.name)).toEqual(["Task"]);
    expect(visible.attention).toEqual([]);
  });

  it("空结果与截断必须显式提示", () => {
    const visible = filterProjection(
      { ...projection, emptyResult: true, truncated: true, truncation: { reason: "ITEM_LIMIT", limit: 200 } },
      { canRead: true },
    );
    expect(visible.attention.join("|")).toContain("没有可用定义");
    expect(visible.attention.join("|")).toContain("截断");
  });
});

describe("SEM-09 目标判据表单", () => {
  it("只产受控判据，不接受自由表达式", () => {
    const built = buildGoalCriterion({
      goalId: "refund-done",
      skillCode: "refund.read",
      target: { objectType: "Refund", objectId: "refund-B" },
      readbackArguments: { id: "refund-B" }, objectIdPath: "$.id", resultObjectIdPath: "$.data.id",
      predicatePath: "$.data.refund.status",
      operator: "equals",
      expectedValue: "SUCCESS",
      nextStep: "仍未达成时重新读回",
    });
    expect(built).toEqual({
      goalId: "refund-done",
      criterion: {
        target: { objectType: "Refund", objectId: "refund-B" },
        readback: { skillCode: "refund.read", arguments: { id: "refund-B" }, objectIdPath: "$.id", resultObjectIdPath: "$.data.id" },
        predicate: { path: "$.data.refund.status", operator: "equals", value: "SUCCESS" },
      },
      nextStep: "仍未达成时重新读回",
    });
    expect(() =>
      buildGoalCriterion({
        goalId: "x",
        skillCode: "refund.read",
        target: { objectType: "Refund", objectId: "refund-B" },
        readbackArguments: { id: "refund-B" }, objectIdPath: "$.id", resultObjectIdPath: "$.data.id",
        predicatePath: "SELECT * FROM refunds",
        operator: "exists",
      }),
    ).toThrow(SemanticFormError);
  });
});

describe("CR-06 目标声明精确Schema", () => {
  const base = { goalId: "g", skillCode: "verify", target: { objectType: "Order", objectId: "order-B" },
    readbackArguments: { id: "order-B" }, objectIdPath: "$.id", resultObjectIdPath: "$.data.id", predicatePath: "$.data.rows[0].value" };
  it.each([false, 0, null, ""])("equals保留JSON值 %s", (value) => {
    expect(buildGoalCriterion({ ...base, operator: "equals", expectedValue: value }).criterion.predicate.value).toBe(value);
  });
  it.each(["exists", "nonEmpty"] as const)("%s不生成无意义的value", (operator) => {
    expect(buildGoalCriterion({ ...base, operator }).criterion.predicate).toEqual({ path: base.predicatePath, operator });
  });
  it.each(["data.status", ".data.status", "$.data..status", "$.data.rows[0]oops", "$.data.rows[-1]", "$.data.rows[01]"])("拒绝错误路径 %s", (predicatePath) => {
    expect(() => buildGoalCriterion({ ...base, operator: "exists", predicatePath })).toThrow(SemanticFormError);
  });
  it.each([NaN, Infinity, -Infinity])("拒绝非JSON数值 %s", (expectedValue) => {
    expect(() => buildGoalCriterion({ ...base, operator: "equals", expectedValue })).toThrow(SemanticFormError);
  });
  it("equals缺值、参数错对象均拒绝，并保持提交快照独立", () => {
    expect(() => buildGoalCriterion({ ...base, operator: "equals" })).toThrow(SemanticFormError);
    expect(() => buildGoalCriterion({ ...base, operator: "exists", readbackArguments: { id: "order-A" } })).toThrow(SemanticFormError);
    const submitted = buildGoalCriterion({ ...base, operator: "exists" });
    base.readbackArguments.id = "order-A";
    expect(submitted.criterion.readback.arguments.id).toBe("order-B");
  });
});
