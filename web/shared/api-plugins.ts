/**
 * M5-2：插件域真实 API 客户端（经网关）——F3-3 资产页·插件与来源数据面。
 *
 * 端点（skill-registry PluginGovernanceController，A3-5，网关/authority 已登记）：
 *   GET/POST /admin/plugin-sources                     来源目录/登记
 *   POST     /admin/plugin-sources/{id}/discover       触发发现（202 {discoveryId,status}）
 *   GET      /admin/plugin-sources/{id}/discoveries/{discoveryId}  发现批次轮询
 *   GET      /admin/plugins                            插件目录（status/source 过滤）
 *   POST     /admin/plugins/{versionId}/enable|disable 启停（disable 必填 reason）
 *   POST     /admin/plugins/{versionId}/grants         授权登记
 *   DELETE   /admin/plugins/grants/{grantId}           授权撤销
 */
import { useApi } from "./api";
import { guard, type ApiFailure } from "./api-task";

export type PluginSourceStatus = "ACTIVE" | "PAUSED";
export type PluginStatus = "ENABLED" | "DISABLED";
export type DiscoveryStatus = "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED";

export interface MockPluginSource {
  /** id=sourceId 别名（页面消费面保持 mock 形状）。 */
  id: string;
  sourceId: string;
  name: string;
  type: string;
  url: string;
  status: PluginSourceStatus;
  lastDiscoveryAt?: string | null;
  lastDiscoveryId?: string | null;
  [key: string]: unknown;
}
export interface PluginGrant {
  grantId: string;
  granteeType: string;
  granteeCode: string;
  [key: string]: unknown;
}
export interface MockPlugin {
  versionId: string;
  pluginId?: string;
  name: string;
  version: string | number;
  sourceId?: string;
  status: PluginStatus;
  grants: PluginGrant[];
  [key: string]: unknown;
}
export interface MockDiscovery {
  discoveryId: string;
  sourceId: string;
  status: DiscoveryStatus;
  changes?: { added: unknown[]; changed: unknown[]; removed: unknown[] };
  /** 失败原因（{code,message} 形状；服务端原始字符串如实包装为 DISCOVERY_FAILED）。 */
  error?: { code: string; message: string };
  [key: string]: unknown;
}

const asArray = (value: any): any[] => (Array.isArray(value) ? value : Array.isArray(value?.items) ? value.items : []);

function normalizeSource(row: any): MockPluginSource {
  const sourceId = String(row?.sourceId ?? row?.source_id ?? row?.id ?? "");
  return {
    ...(row ?? {}),
    id: sourceId,
    sourceId,
    name: String(row?.name ?? ""),
    type: String(row?.type ?? "HTTP"),
    url: String(row?.url ?? row?.endpoint ?? ""),
    status: (row?.status ?? "ACTIVE") as PluginSourceStatus,
    lastDiscoveryAt: row?.lastDiscoveryAt ?? row?.last_discovery_at ?? null,
    lastDiscoveryId: row?.lastDiscoveryId ?? row?.last_discovery_id ?? null,
  };
}

function normalizePlugin(row: any): MockPlugin {
  return {
    ...(row ?? {}),
    versionId: String(row?.versionId ?? row?.version_id ?? row?.id ?? ""),
    pluginId: row?.pluginId ?? row?.plugin_id,
    name: String(row?.name ?? row?.pluginName ?? ""),
    version: row?.version ?? row?.versionNo ?? "",
    sourceId: row?.sourceId ?? row?.source_id,
    status: (row?.status ?? "DISABLED") as PluginStatus,
    grants: asArray(row?.grants).map((grant: any) => ({
      ...(grant ?? {}),
      grantId: String(grant?.grantId ?? grant?.grant_id ?? grant?.id ?? ""),
      granteeType: String(grant?.granteeType ?? grant?.grantee_type ?? ""),
      granteeCode: String(grant?.granteeCode ?? grant?.grantee_code ?? ""),
    })),
  };
}

/** 来源目录（真实端点）。 */
export async function listPluginSources(): Promise<MockPluginSource[]> {
  const api = useApi();
  try {
    return asArray(await api<any>("/admin/plugin-sources")).map(normalizeSource);
  } catch {
    return [];
  }
}

/** 登记来源（真实端点）。 */
export async function createPluginSource(req: { name: string; type: string; url: string }) {
  const api = useApi();
  return guard(async () => normalizeSource(await api<any>("/admin/plugin-sources", "POST", req)));
}

/** 触发发现（真实端点）：202 {discoveryId,status:'QUEUED'} → 轮询。 */
export async function triggerDiscovery(sourceId: string) {
  const api = useApi();
  return guard(async () => {
    const done = await api<any>(`/admin/plugin-sources/${encodeURIComponent(sourceId)}/discover`, "POST", {});
    return {
      discoveryId: String(done?.discoveryId ?? done?.discovery_id ?? ""),
      status: (done?.status ?? "QUEUED") as DiscoveryStatus,
    };
  });
}

/** 发现批次轮询（真实端点）：QUEUED/RUNNING/SUCCEEDED/FAILED；失败保留上一次成功目录。 */
export async function pollDiscovery(sourceId: string, discoveryId: string) {
  const api = useApi();
  return guard(async () => {
    const done = await api<any>(
      `/admin/plugin-sources/${encodeURIComponent(sourceId)}/discoveries/${encodeURIComponent(discoveryId)}`,
    );
    const changes = done?.changes ?? {};
    const rawError = done?.error ?? done?.errorMessage;
    const errorShape =
      rawError && typeof rawError === "object"
        ? { code: String(rawError.code ?? "DISCOVERY_FAILED"), message: String(rawError.message ?? "") }
        : rawError
          ? { code: "DISCOVERY_FAILED", message: String(rawError) }
          : undefined;
    const view: MockDiscovery & Record<string, unknown> = {
      ...(done ?? {}),
      discoveryId: String(done?.discoveryId ?? discoveryId),
      sourceId: String(done?.sourceId ?? sourceId),
      status: (done?.status ?? "RUNNING") as DiscoveryStatus,
      changes: {
        added: asArray(changes.added),
        changed: asArray(changes.changed),
        removed: asArray(changes.removed),
      },
      error: errorShape,
    };
    return view;
  });
}

/** 插件目录（真实端点）：status/source 过滤。 */
export async function listPlugins(
  query: { status?: PluginStatus; source?: string } = {},
): Promise<MockPlugin[]> {
  const api = useApi();
  const params = new URLSearchParams();
  if (query.status) params.set("status", query.status);
  if (query.source) params.set("source", query.source);
  try {
    const rows = await api<any>(`/admin/plugins?${params.toString()}`);
    return asArray(rows).map(normalizePlugin);
  } catch {
    return [];
  }
}

/** 启停（真实端点）：disable 必填 reason。 */
export async function setPluginEnabled(versionId: string, enabled: boolean, reason?: string) {
  const api = useApi();
  return guard(async () =>
    api<any>(`/admin/plugins/${encodeURIComponent(versionId)}/${enabled ? "enable" : "disable"}`, "POST", {
      reason: enabled ? "页面启用" : reason ?? "",
    }),
  );
}

/** 授权登记（真实端点）：返回授权后的插件行（重新读取目录中该版本）。 */
export async function addPluginGrant(
  versionId: string,
  req: { granteeType: string; granteeCode: string; [key: string]: unknown },
) {
  const api = useApi();
  return guard(async () => {
    await api<any>(`/admin/plugins/${encodeURIComponent(versionId)}/grants`, "POST", req);
    const rows = await listPlugins();
    return rows.find((row) => row.versionId === versionId) ?? { versionId };
  });
}

/** 授权撤销（真实端点）。 */
export async function removePluginGrant(grantId: string) {
  const api = useApi();
  return guard(async () => {
    await api<any>(`/admin/plugins/grants/${encodeURIComponent(grantId)}`, "DELETE", {});
    return { grantId, revoked: true as const };
  });
}
