/**
 * M5-2：记忆域真实 API 客户端（经网关）——F4 我的记忆 + F3-3 资产页/确认中心 DRAFT 记忆数据面。
 *
 * 端点（engine MemoryController，A3-4 五条，网关/authority 已登记）：
 *   GET    /v1/apps/{code}/memories?scope&status&tags&q&sessionId&page&pageSize
 *   GET    /v1/apps/{code}/memories/{memoryId}          单条（含正文）
 *   POST   /v1/apps/{code}/memories                     显式保存（requestId 幂等）
 *   PATCH  /v1/apps/{code}/memories/{memoryId}          CAS 更新/审批（409 MEMORY_VERSION_CONFLICT）
 *   DELETE /v1/apps/{code}/memories/{memoryId}          先置 REVOKED，幂等
 *
 * 诚实边界：管理台跨工作区聚合无独立端点——统一按演示应用编码（V901 种子工作区）读取。
 * 函数形状与原 mock-memories 消费面同形；错误以 {error,httpStatus[,userMessage]} 对象返回。
 */
import { useApi } from "./api";
import { demoAppCode, guard, type ApiFailure } from "./api-task";

export type MemoryKind = "PREFERENCE" | "EXPERIENCE";
export type MemoryScope = "PRIVATE" | "SHARED";
export type MemoryStatus = "ACTIVE" | "REVOKED" | "DRAFT";

export interface MockMemoryRow {
  memoryId: string;
  principalId?: string;
  kind: MemoryKind;
  scope: MemoryScope;
  tags: string[];
  version: number;
  status: MemoryStatus;
  expiresAt?: string;
  sourceTaskId?: string;
  createdAt?: string;
  updatedAt?: string;
}
export interface MockMemoryDetail extends MockMemoryRow {
  content: string;
}

const memoryPath = (memoryId: string, appCode: string) =>
  `/v1/apps/${encodeURIComponent(appCode)}/memories/${encodeURIComponent(memoryId)}`;

function normalizeMemory(row: any, withContent = false): MockMemoryDetail {
  const tags = Array.isArray(row?.tags)
    ? row.tags.map(String)
    : typeof row?.tags === "string" && row.tags.trim()
      ? row.tags.split(",").map((tag: string) => tag.trim()).filter(Boolean)
      : [];
  return {
    memoryId: String(row?.memoryId ?? row?.memory_id ?? ""),
    principalId: row?.principalId ?? row?.principal_id,
    kind: (row?.kind ?? "PREFERENCE") as MemoryKind,
    scope: (row?.scope ?? "PRIVATE") as MemoryScope,
    tags,
    version: Number(row?.version ?? 0),
    status: (row?.status ?? "ACTIVE") as MemoryStatus,
    expiresAt: row?.expiresAt ?? row?.expires_at,
    sourceTaskId: row?.sourceTaskId ?? row?.source_task_id,
    createdAt: row?.createdAt ?? row?.created_at,
    updatedAt: row?.updatedAt ?? row?.updated_at,
    content: withContent ? String(row?.content ?? "") : String(row?.content ?? ""),
  };
}

/** 列表（真实端点）：scope/status/tags/q 过滤由服务端承接；列表不含正文（单条读取取正文）。
 *  服务端 status 仅接受 ACTIVE/DRAFT（REVOKED 已退出检索不再返回）——REVOKED 查询如实返回空。 */
export async function listMemories(
  query: { scope?: MemoryScope; status?: MemoryStatus; tags?: string; q?: string; sessionId?: string } = {},
  appCode = demoAppCode,
): Promise<{ memories: MockMemoryRow[] }> {
  const api = useApi();
  const wanted = query.status ?? "ACTIVE";
  if (wanted === "REVOKED") return { memories: [] };
  const params = new URLSearchParams();
  if (query.scope) params.set("scope", query.scope);
  params.set("status", wanted);
  if (query.tags) params.set("tags", query.tags);
  if (query.q) params.set("q", query.q);
  if (query.sessionId) params.set("sessionId", query.sessionId);
  params.set("pageSize", "100");
  const page = await api<any>(`/v1/apps/${encodeURIComponent(appCode)}/memories?${params.toString()}`);
  const rows: any[] = Array.isArray(page?.memories) ? page.memories : Array.isArray(page) ? page : [];
  return { memories: rows.map((row) => normalizeMemory(row)) };
}

/** 单条（真实端点）：不存在或不可见按 404 形状（api 抛错降形）。 */
export async function getMemory(memoryId: string, appCode = demoAppCode): Promise<MockMemoryDetail | ApiFailure> {
  const api = useApi();
  return guard(async () => normalizeMemory(await api<any>(memoryPath(memoryId, appCode)), true));
}

/** 显式保存（真实端点）：requestId 幂等。 */
export async function createMemory(req: {
  requestId: string;
  content: string;
  kind: MemoryKind;
  scope?: MemoryScope;
  tags?: string[];
  expiresAt?: string;
  sessionId?: string;
  sourceTaskId?: string;
}, appCode = demoAppCode): Promise<MockMemoryDetail | ApiFailure> {
  const api = useApi();
  return guard(async () =>
    normalizeMemory(
      await api<any>(`/v1/apps/${encodeURIComponent(appCode)}/memories`, "POST", {
        requestId: req.requestId,
        content: req.content,
        kind: req.kind,
        scope: req.scope ?? "PRIVATE",
        ...(req.tags ? { tags: req.tags } : {}),
        ...(req.expiresAt ? { expiresAt: req.expiresAt } : {}),
        ...(req.sessionId ? { sessionId: req.sessionId } : {}),
        ...(req.sourceTaskId ? { sourceTaskId: req.sourceTaskId } : {}),
      }),
      true,
    ),
  );
}

/** 修改/审批（真实端点）：expectedVersion CAS（409 MEMORY_VERSION_CONFLICT 同码）。 */
export async function updateMemory(
  memoryId: string,
  req: { content?: string; tags?: string[]; expiresAt?: string; status?: MemoryStatus; expectedVersion: number },
  appCode = demoAppCode,
): Promise<MockMemoryDetail | ApiFailure> {
  const api = useApi();
  return guard(async () => {
    const body: Record<string, unknown> = { expectedVersion: req.expectedVersion };
    if (req.content !== undefined) body.content = req.content;
    if (req.tags !== undefined) body.tags = req.tags;
    if (req.expiresAt !== undefined) body.expiresAt = req.expiresAt;
    if (req.status !== undefined) body.status = req.status;
    return normalizeMemory(await api<any>(memoryPath(memoryId, appCode), "PATCH", body), true);
  });
}

/** 删除（真实端点）：先置 REVOKED 立即退出检索，幂等；requestId 必填（服务端校验）。 */
export async function deleteMemory(
  memoryId: string,
  req: { requestId: string },
  appCode = demoAppCode,
): Promise<{ memoryId: string; status: MemoryStatus } | ApiFailure> {
  const api = useApi();
  return guard(async () => {
    const done = await api<any>(memoryPath(memoryId, appCode), "DELETE", { requestId: req.requestId });
    return { memoryId: String(done?.memoryId ?? memoryId), status: (done?.status ?? "REVOKED") as MemoryStatus };
  });
}

export type DemoMemoryApprovalAction = "ACTIVE" | "REVOKED";

/** DRAFT 待办清单（真实端点）：status=DRAFT 检索（管理台确认中心与资产页同源）。 */
export async function demoListDraftMemories(scope?: MemoryScope): Promise<MockMemoryDetail[]> {
  const api = useApi();
  const params = new URLSearchParams({ status: "DRAFT", pageSize: "100" });
  if (scope) params.set("scope", scope);
  try {
    const page = await api<any>(`/v1/apps/${encodeURIComponent(demoAppCode)}/memories?${params.toString()}`);
    const rows: any[] = Array.isArray(page?.memories) ? page.memories : Array.isArray(page) ? page : [];
    return rows.map((row) => normalizeMemory(row, true));
  } catch {
    return [];
  }
}

/** 单条 DRAFT 读取（真实端点）：非 DRAFT/未知按 404 形状。 */
export async function demoGetDraftMemory(memoryId: string): Promise<MockMemoryDetail | ApiFailure> {
  return getMemory(memoryId);
}

/** DRAFT 审批（真实端点 PATCH status 字段语义）：确认→ACTIVE、拒绝→REVOKED，CAS 同码。 */
export async function demoApproveMemory(
  memoryId: string,
  action: DemoMemoryApprovalAction,
  expectedVersion: number,
): Promise<MockMemoryDetail | ApiFailure> {
  return updateMemory(memoryId, { status: action, expectedVersion });
}
