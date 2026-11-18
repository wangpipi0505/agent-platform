/**
 * M5-2：MCP 连接域真实 API 客户端（经网关）——F3-3 资产页·MCP 连接数据面。
 *
 * 端点（skill-registry McpConnectionController，A3-6 七条，网关/authority 已登记）：
 *   POST /admin/mcp-connections                 登记
 *   PUT  /admin/mcp-connections/{id}            配置变更（revision+1）
 *   GET  /admin/mcp-connections/{id}            单条
 *   POST /admin/mcp-connections/{id}/test       连通测试（失败带类别）
 *   POST /admin/mcp-connections/{id}/discover   触发发现（批次串行）
 *   GET  /admin/mcp-connections/{id}/tools?diff 目录快照/差异
 *   POST /admin/mcp-connections/{id}/tools/review  审核生成技能草稿
 *
 * 诚实边界：服务端无 GET 列表端点（仅按 id 读取）——列表以本地已知 id 索引（localStorage）
 * 逐条 GET 聚合，均为服务端真实行；索引不含的连接（他端创建）不出现，如实登记。
 */
import { useApi } from "./api";
import { guard, type ApiFailure } from "./api-task";

export type { ApiFailure };

export type McpTransport = "STDIO" | "HTTP" | "SSE";
export type McpStatus = "UNTESTED" | "CONNECTED" | "FAILED";
export type McpFailureCategory = "AUTH" | "TIMEOUT" | "PROTOCOL" | "ENDPOINT_UNREACHABLE";
export type McpToolChangeType = "ADDED" | "CHANGED" | "REMOVED";

export interface McpTool {
  toolName: string;
  changeType?: McpToolChangeType;
  [key: string]: unknown;
}
export interface MockMcpConnection {
  id: string;
  name: string;
  endpoint: string;
  transport: McpTransport;
  revision?: number;
  status?: McpStatus;
  lastTest?: Record<string, unknown> | null;
  tools?: McpTool[];
  [key: string]: unknown;
}

const KNOWN_IDS_KEY = "ap-mcp-known-ids";

function readKnownIds(): string[] {
  try {
    const raw = localStorage.getItem(KNOWN_IDS_KEY);
    const parsed = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed.map(String).filter(Boolean) : [];
  } catch {
    return [];
  }
}
function writeKnownIds(ids: string[]) {
  try {
    localStorage.setItem(KNOWN_IDS_KEY, JSON.stringify([...new Set(ids)]));
  } catch {
    /* 隐私模式忽略 */
  }
}
function rememberId(id: string) {
  if (id) writeKnownIds([...readKnownIds(), id]);
}

function normalizeConnection(row: any): MockMcpConnection {
  return {
    ...(row ?? {}),
    id: String(row?.id ?? row?.connectionId ?? row?.connection_id ?? row?.code ?? ""),
    name: String(row?.name ?? ""),
    endpoint: String(row?.endpoint ?? row?.base_endpoint ?? row?.baseEndpoint ?? ""),
    transport: (row?.transport ?? "HTTP") as McpTransport,
    revision: Number(row?.revision ?? 0),
    status: (row?.status ?? "UNTESTED") as McpStatus,
    lastTest: row?.lastTest ?? row?.last_test ?? null,
  };
}

/** 页面表单 transport（STDIO/HTTP/SSE）→ 服务端登记枚举（STREAMABLE_HTTP/SSE_LEGACY）。
 *  STDIO 非远程传输档：按 STREAMABLE_HTTP 登记并在页面如显原文（M5-2 诚实登记）。 */
function mapTransport(transport: McpTransport): string {
  if (transport === "SSE") return "SSE_LEGACY";
  return "STREAMABLE_HTTP";
}

/** 服务端 code 形状 [A-Za-z][A-Za-z0-9_-]{0,63}：页面表单无 code 字段，由名称派生。 */
function deriveCode(name: string): string {
  const slug = String(name ?? "").replace(/[^A-Za-z0-9_-]/g, "").slice(0, 60);
  const candidate = /^[A-Za-z]/.test(slug) ? slug : `mcp-${slug}`;
  return candidate || `mcp-${Date.now().toString(36)}`;
}

/** 连接列表（真实端点聚合）：本地已知 id 逐条 GET；404 的 id 从索引剔除（如如实降级）。 */
export async function listMcpConnections(): Promise<MockMcpConnection[]> {
  const api = useApi();
  const ids = readKnownIds();
  const rows = await Promise.all(
    ids.map(async (id) => {
      try {
        return normalizeConnection(await api<any>(`/admin/mcp-connections/${encodeURIComponent(id)}`));
      } catch (e: any) {
        return e?.status === 404 ? null : normalizeConnection({ id });
      }
    }),
  );
  const found = rows.filter((row): row is MockMcpConnection => row !== null);
  writeKnownIds(found.map((row) => row.id));
  return found;
}

/** 单条（真实端点）。 */
export async function getMcpConnection(id: string): Promise<MockMcpConnection | ApiFailure> {
  const api = useApi();
  return guard(async () => normalizeConnection(await api<any>(`/admin/mcp-connections/${encodeURIComponent(id)}`)));
}

/** 登记（真实端点）：新 id 记入本地索引（列表可见）；transport/code/baseEndpoint/timeoutMs 由页面
 *  表单值映射派生（服务端字段对：endpointProfileRef 与 baseEndpoint 二选一；timeoutMs 必填正整数）。 */
export async function createMcpConnection(req: { name: string; endpoint: string; transport: McpTransport }) {
  const api = useApi();
  return guard(async () => {
    const row = normalizeConnection(
      await api<any>("/admin/mcp-connections", "POST", {
        code: deriveCode(req.name),
        name: req.name,
        baseEndpoint: req.endpoint,
        transport: mapTransport(req.transport),
        timeoutMs: 30000,
      }),
    );
    rememberId(row.id);
    return row;
  });
}

/** 配置变更（真实端点）：端点/凭据/传输任一变更 revision+1。 */
export async function updateMcpConnection(
  id: string,
  req: { name?: string; endpoint?: string; transport?: McpTransport },
) {
  const api = useApi();
  return guard(async () => normalizeConnection(await api<any>(`/admin/mcp-connections/${encodeURIComponent(id)}`, "PUT", req)));
}

/** 连通测试（真实端点）：失败带类别（AUTH/TIMEOUT/PROTOCOL/ENDPOINT_UNREACHABLE）。 */
export async function testMcpConnection(id: string) {
  const api = useApi();
  return guard(async () => api<any>(`/admin/mcp-connections/${encodeURIComponent(id)}/test`, "POST", {}));
}

/** 触发发现（真实端点）：批次串行（batch_no）。 */
export async function discoverMcpTools(id: string) {
  const api = useApi();
  return guard(async () => api<any>(`/admin/mcp-connections/${encodeURIComponent(id)}/discover`, "POST", {}));
}

/** 目录快照/差异（真实端点）：diff=true 返回 ADDED/CHANGED/REMOVED。 */
export async function listMcpTools(id: string, query: { diff?: boolean } = {}) {
  const api = useApi();
  return guard(async () =>
    api<any>(`/admin/mcp-connections/${encodeURIComponent(id)}/tools?diff=${query.diff ? "true" : "false"}`),
  );
}

/** 审核生成技能草稿（真实端点）：skillDraftRef。 */
export async function reviewMcpTools(id: string, req: { toolName: string }) {
  const api = useApi();
  return guard(async () =>
    api<any>(`/admin/mcp-connections/${encodeURIComponent(id)}/tools/review`, "POST", req),
  );
}
