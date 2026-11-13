/**
 * F1-2 待办角标 mock 数据源（仅 demo，不经 mock-transport 装载）：形状=接口稿
 * GET /admin/confirmation-center/counts 返回体（04-接口设计 §5）：
 * `{publishReview,highRisk,planConfirmation,draftMemory,total}`，total=四项之和，
 * 按可见待办的未分页总数计数（含只读待审发布，不可见类别为零）。
 * 真实端点由 B4-1 交付并登记网关/authority 后，AppShell 轮询切换到真实客户端。
 *
 * F3-3 增补（追加式）：计数改为**从四类待办矩阵实时推导**（mock-confirmation-matrix，
 * 与四类列表同源），保证「角标和列表一致、处置后同刷」（前端稿 §5.9）；形状与轮询入口
 * 不变。另提供变更通知钩子供确认中心处置后立即刷新 AppShell 角标（不等 15s 轮询）。
 */
import { computeDemoConfirmationCounts } from "./mock-confirmation-matrix";

export interface ConfirmationCounts {
  /** 发布评审待办（含当前主体无办理权、只读可见的待审发布）。 */
  publishReview: number;
  /** 高危操作确认（双主体）。 */
  highRisk: number;
  /** 计划确认（PLAN_READY 待确认）。 */
  planConfirmation: number;
  /** DRAFT 记忆待补全。 */
  draftMemory: number;
  /** 四项之和（接口稿口径）。 */
  total: number;
}

export interface MockConfirmationCounts extends ConfirmationCounts {
  /** demo:true=非服务端数据（mock 资产惯例，见 mock-task.ts MockTaskSeed）。 */
  demo: true;
}

/** F1-2 演示固定值（四类待办各 1-2 条，total=四项之和）。F3-3 起实时计数取代轮询静态值，
 * 本常量保留为文档锚：与矩阵种子初值一致（发布评审 2 + 高危 1 + 计划 1 + DRAFT 记忆 2 = 6）。 */
export const mockConfirmationCounts: MockConfirmationCounts = {
  demo: true,
  publishReview: 2,
  highRisk: 1,
  planConfirmation: 1,
  draftMemory: 2,
  total: 6,
};

/** 角标变更通知（F3-3）：确认中心处置后调用，AppShell 订阅后立即重读计数（处理后同刷）。 */
const countListeners = new Set<() => void>();
export function onConfirmationCountsChanged(listener: () => void): () => void {
  countListeners.add(listener);
  return () => countListeners.delete(listener);
}
export function notifyConfirmationCountsChanged(): void {
  countListeners.forEach((listener) => listener());
}

/** AppShell 15s 轮询入口：Promise 形状与未来真实客户端一致，便于切换。
 * F3-3 起从四类待办矩阵实时推导——角标与四类列表未分页总数天然一致。 */
export function fetchMockConfirmationCounts(): Promise<MockConfirmationCounts> {
  const counts = computeDemoConfirmationCounts();
  return Promise.resolve({ demo: true, ...counts });
}
