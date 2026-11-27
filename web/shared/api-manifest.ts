/**
 * M5-2：工作区 manifest 真实 API 客户端（经网关）——F3-5 ManifestPanel 数据面。
 *
 * 端点（app-management WorkspaceManifestController，A3-1，网关/authority 已登记）：
 *   GET /admin/apps/{code}/manifest   读（头修订+分区）
 *   PUT /admin/apps/{code}/manifest   {requestId, manifest, baseRevision}（CAS 409 MANIFEST_REVISION_CONFLICT）
 *
 * 原 ManifestPanel 页内 VITE_MOCK 演示分支移入本模块（页面不再 import mock-*）：
 * VITE_MOCK=true 走 shared/mock-manifest 演示端点，否则走真实 api。
 */
import { useApi } from "./api";
import type { ManifestGetView, ManifestSaveResult } from "./mock-manifest";

export { validateManifest, MANIFEST_PUT_KEYS, MANIFEST_MAX_BYTES } from "./mock-manifest";
export type { ManifestIssue, ManifestObject, ManifestGetView, ManifestSaveResult } from "./mock-manifest";

const demoMode = () =>
  typeof import.meta.env?.VITE_MOCK === "string" && import.meta.env.VITE_MOCK !== "false";

/** 读 manifest（真实端点；演示模式经 mock-manifest）。 */
export async function fetchManifest(code: string): Promise<ManifestGetView | null> {
  if (demoMode()) {
    const { mockManifestGet } = await import("./mock-manifest");
    return mockManifestGet(code);
  }
  const api = useApi();
  return api<ManifestGetView | null>(`/admin/apps/${encodeURIComponent(code)}/manifest?allowMissing=true`);
}

/** 保存 manifest（真实端点；CAS/409/issues 语义由服务端裁决，错误以抛出呈现）。返回形状=ManifestSaveResult。 */
export async function putManifest(
  code: string,
  body: { requestId: string; manifest: Record<string, unknown>; baseRevision: number },
): Promise<ManifestSaveResult> {
  if (demoMode()) {
    const { mockManifestPut } = await import("./mock-manifest");
    return mockManifestPut(code, body as never);
  }
  const api = useApi();
  return api<ManifestSaveResult>(`/admin/apps/${encodeURIComponent(code)}/manifest`, "PUT", body);
}
