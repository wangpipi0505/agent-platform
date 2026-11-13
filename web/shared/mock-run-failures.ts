/**
 * F2-3（方案 §5.6 运行中心 / §8 解耦注意）：运行域演示数据补形——独立 mock 文件，
 * 不往 mock-transport.ts 堆；仅在 VITE_MOCK 演示传输层接管时由 mock-transport 在
 * 运行端点处委托调用，真实后端响应不经过本文件。
 *
 * 背景：运行列表既有 demo 行只有 errorMessage（如 "SKILL_TIMEOUT: telemetry_fetch 超过 5000ms"），
 * 没有 error/errorSemantic 结构化字段；详情节点行也没有 input 快照。本文件按
 * demo:true 补齐两块形状，供 F2-3 的失败行内联错误摘要列与节点检查器输入独立展示：
 * - 列表行（FAILED 才补）：errorSemantic（失败语义码）+ error { reasonCode, message }；
 * - 详情节点行：input（节点输入快照）+ 失败节点 errorSemantic/error（供诊断锚点
 *   "定位 error/errorSemantic 非空节点"与检查器输入/输出分块展示）。
 */

export const RUN_FAILURE_DEMO = true;

/** "CODE: 首行文本" 拆码：无码前缀回退 RUN_FAILED，文本取首行。 */
function splitFailure(text: string): { reasonCode: string; firstLine: string } {
  const firstLine = String(text).split("\n", 1)[0]?.trim() ?? "";
  const colon = firstLine.indexOf(":");
  if (colon <= 0) return { reasonCode: "RUN_FAILED", firstLine };
  return {
    reasonCode: firstLine.slice(0, colon).trim(),
    firstLine: firstLine.slice(colon + 1).trim() || firstLine,
  };
}

type Row = Record<string, unknown>;

/** 运行列表行补形：仅 FAILED 行补 errorSemantic + error { reasonCode, message }。 */
export function withRunFailureSummaries(rows: unknown): unknown[] {
  if (!Array.isArray(rows)) return [];
  return rows.map((row) => {
    const record = row as Row;
    if (record?.status !== "FAILED") return row;
    const raw = typeof record.errorMessage === "string" ? record.errorMessage : "";
    const { reasonCode, firstLine } = splitFailure(raw || "运行失败");
    return {
      ...record,
      demo: RUN_FAILURE_DEMO,
      errorSemantic: reasonCode,
      error: { reasonCode, message: firstLine || raw || "运行失败" },
    };
  });
}

/** 节点输入演示快照：按 appCode:nodeDefId 取补，未命中的节点不臆造输入。 */
const demoNodeInputs: Record<string, unknown> = {
  "anomaly-triage:llm": { question: "近 30 分钟遥测异常分诊", telemetry: "SAT-04 通道 7 抖动" },
  "anomaly-triage:orbit_query": { skill: "telemetry_fetch", window: "5m", thresholdMs: 5000 },
  "satellite-assistant:llm": { question: "SAT-01 当前轨道高度是多少？", contextRefs: ["orbit_query"] },
  "satellite-assistant:orbit_query": { satId: "SAT-01", fields: ["altitude", "inclination"] },
  "space-visibility-agent:target_query": { constellation: "SB-SAR", status: "IN_ORBIT" },
  "space-visibility-agent:visibility_plan": {
    targets: ["SAT-01", "SAT-A"],
    window: "2026-07-01 08:00-12:00 UTC",
    minElevationDeg: 10,
  },
};

/** 详情节点行补形：input 快照 + 失败节点 errorSemantic/error（取节点自身 errorMessage 拆码）。 */
export function withRunNodeInputs(appCode: unknown, nodes: unknown): unknown[] {
  if (!Array.isArray(nodes)) return [];
  return nodes.map((node) => {
    const record = node as Row;
    if (!record || typeof record !== "object") return node;
    const key = `${String(appCode ?? "")}:${String(record.nodeDefId ?? "")}`;
    const input = demoNodeInputs[key];
    const failed = record.status === "FAILED";
    if (!input && !failed) return node;
    const raw = typeof record.errorMessage === "string" ? record.errorMessage : "";
    const { reasonCode, firstLine } = splitFailure(raw || "节点执行失败");
    return {
      ...record,
      demo: RUN_FAILURE_DEMO,
      ...(input ? { input } : {}),
      ...(failed
        ? {
            errorSemantic: reasonCode,
            error: { reasonCode, message: firstLine || raw || "节点执行失败" },
          }
        : {}),
    };
  });
}
