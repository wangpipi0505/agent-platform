/**
 * F1-2 待办角标 mock 数据源（仅 demo，不经 mock-transport 装载）：形状=接口稿
 * GET /admin/confirmation-center/counts 返回体（04-接口设计 §5）：
 * `{publishReview,highRisk,planConfirmation,draftMemory,total}`，total=四项之和，
 * 按可见待办的未分页总数计数（含只读待审发布，不可见类别为零）。
 * 真实端点由 B4-1 交付并登记网关/authority 后，AppShell 轮询切换到真实客户端。
 */
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

/** 演示固定值：四类待办各 1-2 条，total=四项之和。 */
export const mockConfirmationCounts: MockConfirmationCounts = {
  demo: true,
  publishReview: 2,
  highRisk: 1,
  planConfirmation: 1,
  draftMemory: 1,
  total: 5,
};

/** AppShell 15s 轮询入口：Promise 形状与未来真实客户端一致，便于切换。 */
export function fetchMockConfirmationCounts(): Promise<MockConfirmationCounts> {
  return Promise.resolve(mockConfirmationCounts);
}
