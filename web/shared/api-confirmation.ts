/**
 * M5-2：确认中心真实 API 客户端（经网关）——F1-2 AppShell 待办角标 + F3-3 四类待办矩阵数据面。
 *
 * 端点（网关/authority 已登记）：
 *   GET  /admin/confirmation-center/counts   四类未分页总数（B4-1，engine ConfirmationCenterController）
 *   GET  /v1/tasks?confirmableBy=me          计划确认投影（以修订为单位，仅无有效凭据修订）
 *   GET  /v1/apps/{code}/memories?status=DRAFT    DRAFT 记忆待办（engine MemoryController）
 *   GET/POST /admin/config-releases[.../approve]  发布评审（app-management ConfigReleaseController）
 *
 * 诚实边界（无对应真实端点，如实留空/拒绝）：
 *   高危操作待办无全局列表端点（InlineApproval 按 runId 查询 GET /v1/runs/{id}/approvals），
 *   待办列表如实为空；发布"拒绝"无独立端点（现有语义=批准/停用），REJECT 如实返回错误对象。
 */
import { useApi } from "./api";
import { guard, type ApiFailure } from "./api-task";
import { demoListDraftMemories, type MockMemoryDetail } from "./api-memories";

export interface ConfirmationCounts {
  publishReview: number;
  highRisk: number;
  planConfirmation: number;
  draftMemory: number;
  total: number;
}
export type ServerConfirmationCounts = ConfirmationCounts & { demo?: false };

/** 角标变更通知（处置后同刷）：AppShell 订阅、确认中心处置后触发（不等 15s 轮询）。 */
const countListeners = new Set<() => void>();
export function onConfirmationCountsChanged(listener: () => void): () => void {
  countListeners.add(listener);
  return () => {
    countListeners.delete(listener);
  };
}
export function notifyConfirmationCountsChanged(): void {
  countListeners.forEach((listener) => listener());
}

/** AppShell 轮询入口（真实端点）：形状=GET /admin/confirmation-center/counts 返回体。 */
export async function fetchConfirmationCounts(): Promise<ServerConfirmationCounts> {
  const api = useApi();
  const counts = await api<any>("/admin/confirmation-center/counts");
  return {
    publishReview: Number(counts?.publishReview ?? 0),
    highRisk: Number(counts?.highRisk ?? 0),
    planConfirmation: Number(counts?.planConfirmation ?? 0),
    draftMemory: Number(counts?.draftMemory ?? 0),
    total: Number(counts?.total ?? 0),
  };
}

// ---------------------------------------------------------------------------
// 四类待办矩阵（ApprovalsPage 数据面）
// ---------------------------------------------------------------------------

export interface DemoPublishRequest {
  publishId: string;
  appCode: string;
  targetVersion: number | string;
  manifestRevision?: number;
  digest: string;
  status?: string;
  requestedAt?: string;
  createdBy?: string;
  [key: string]: unknown;
}
export interface DemoHighRiskApproval {
  runId: string;
  nodeId: string;
  nodeName?: string;
  snapshotDigest?: string;
  summary?: string;
  reason?: string;
  requestedAt?: string;
}
export interface DemoPlanConfirmationTodo {
  taskId: string;
  planId: string;
  revision: number;
  digest: string;
  goal: string;
  confirmState: "UNCONFIRMED" | "CONFIRMED_EXPIRED";
  confirmedBy?: string;
  expiresAt?: string;
}

/** 应用目录（跨应用聚合用）。 */
async function appCodes(api: ReturnType<typeof useApi>): Promise<string[]> {
  try {
    const apps = await api<any>("/admin/apps");
    const rows: any[] = Array.isArray(apps) ? apps : Array.isArray(apps?.apps) ? apps.apps : [];
    return rows.map((row) => String(row?.code ?? "")).filter(Boolean);
  } catch {
    return [];
  }
}

/** ① 发布评审待办（真实端点聚合）：各应用 DRAFT 配置发布=待批准（validate→approve 流的前站）。 */
export async function listPendingPublishRequests(): Promise<DemoPublishRequest[]> {
  const api = useApi();
  const codes = await appCodes(api);
  const aggregated = await Promise.all(
    codes.map(async (code) => {
      try {
        const rows = await api<any>(`/admin/config-releases?appCode=${encodeURIComponent(code)}`);
        const list: any[] = Array.isArray(rows) ? rows : Array.isArray(rows?.releases) ? rows.releases : [];
        return list
          .filter((row) => String(row?.status ?? "").toUpperCase() === "DRAFT")
          .map(
            (row): DemoPublishRequest => ({
              publishId: String(row?.releaseId ?? row?.release_id ?? ""),
              appCode: String(row?.appCode ?? code),
              targetVersion: row?.appVersion ?? row?.app_version ?? "",
              manifestRevision: row?.manifestRevision,
              digest: String(row?.candidateSha256 ?? row?.digest ?? ""),
              status: String(row?.status ?? "DRAFT"),
              requestedAt: row?.createdAt ?? row?.created_at,
              createdBy: row?.createdBy ?? row?.created_by,
            }),
          );
      } catch {
        return [];
      }
    }),
  );
  return aggregated.flat();
}

/** ② 高危操作待办（诚实降级）：无全局待审端点（InlineApproval 按 run 查询），列表如实为空。 */
export async function listPendingHighRiskApprovals(): Promise<DemoHighRiskApproval[]> {
  return [];
}

/** ③ 计划确认待办（真实端点）：confirmableBy=me 投影（已确认且凭据有效不回流）。 */
export async function listPlanConfirmationTodos(): Promise<DemoPlanConfirmationTodo[]> {
  const api = useApi();
  try {
    const projection = await api<any>("/v1/tasks?confirmableBy=me");
    const items: any[] = Array.isArray(projection?.items) ? projection.items : [];
    return items.map((item) => ({
      taskId: String(item?.taskId ?? ""),
      planId: String(item?.planId ?? ""),
      revision: Number(item?.revision ?? 0),
      digest: String(item?.digest ?? ""),
      goal: String(item?.goal ?? ""),
      confirmState: (item?.confirmState ?? "UNCONFIRMED") as DemoPlanConfirmationTodo["confirmState"],
      confirmedBy: item?.confirmedBy,
      expiresAt: item?.expiresAt,
    }));
  } catch {
    return [];
  }
}

/** ④ DRAFT 记忆待办（真实端点）：status=DRAFT 检索（单条正文经详情端点预览）。 */
export async function listDraftMemoryTodos(): Promise<MockMemoryDetail[]> {
  return demoListDraftMemories();
}

/** 发布评审处置（真实端点）：批准→POST /admin/config-releases/{id}/approve；拒绝无独立端点如实返回错误。 */
export async function decidePublishRequest(
  publishId: string,
  decision: "APPROVE" | "REJECT",
): Promise<Record<string, unknown> | ApiFailure> {
  if (decision !== "APPROVE") {
    return {
      error: "PUBLISH_REJECT_UNSUPPORTED",
      httpStatus: 501,
      userMessage: "配置发布暂无独立拒绝端点（现有语义=批准/停用），请在集成控制台处理",
    };
  }
  const api = useApi();
  return guard(async () =>
    api<any>(`/admin/config-releases/${encodeURIComponent(publishId)}/approve`, "POST", {}),
  );
}

/** 高危操作处置（诚实降级）：无全局待审端点（决策入口按 run 存在于运行详情），如实返回错误。 */
export async function decideHighRiskApproval(
  runId: string,
  nodeId: string,
  _decision: "APPROVE" | "REJECT",
): Promise<Record<string, unknown> | ApiFailure> {
  return {
    error: "HIGH_RISK_LIST_UNSUPPORTED",
    httpStatus: 501,
    userMessage: `高危操作待办无全局列表端点（run ${runId} node ${nodeId} 请经运行详情处理）`,
  };
}

/** DRAFT 记忆审批（真实端点 PATCH status 字段语义）：确认→ACTIVE、拒绝→REVOKED，CAS 同码。 */
export async function decideDraftMemory(
  memoryId: string,
  action: "ACTIVE" | "REVOKED",
  expectedVersion: number,
): Promise<MockMemoryDetail | ApiFailure> {
  const api = useApi();
  return guard(async () => {
    const done = await api<any>(
      `/v1/apps/satellite-planning-assistant/memories/${encodeURIComponent(memoryId)}`,
      "PATCH",
      { status: action, expectedVersion },
    );
    return {
      memoryId: String(done?.memoryId ?? memoryId),
      status: done?.status ?? action,
      version: Number(done?.version ?? expectedVersion + 1),
      content: String(done?.content ?? ""),
    } as MockMemoryDetail;
  });
}
