/**
 * M5-2：附件真实 API 客户端（经网关）——工作区问答面板的模板/附件注册与下载。
 *
 * 端点（skill-registry ArtifactController，网关/authority 已登记）：
 *   POST /admin/artifacts                     multipart 上传 → {artifactId,bytes,sha256}
 *   GET  /admin/artifacts/{id}/download       管理台受控下载
 *
 * VITE_MOCK=true 时经 shared/mock-artifacts 本地存储（IndexedDB/sessionStorage）承接，
 * 与 shared/artifact-upload.ts 的既有双模口径一致；页面不再 import mock-artifacts。
 */
import { useApi } from "./api";
import { mockArtifactsEnabled } from "./mock-artifacts";

export interface StoredArtifactRef {
  artifactId?: string;
  name: string;
  mediaType?: string;
  downloadUrl: string;
  [key: string]: unknown;
}

/** 模板/文件注册：真实=POST /admin/artifacts multipart；演示=registerMockArtifact 本地存储。 */
export async function registerArtifact(input: {
  name: string;
  mediaType?: string;
  content: string | Blob | ArrayBuffer;
}): Promise<StoredArtifactRef | undefined> {
  if (mockArtifactsEnabled()) {
    const { registerMockArtifact } = await import("./mock-artifacts");
    return (await registerMockArtifact(input)) as StoredArtifactRef;
  }
  const auth = (await import("./api")).useAuth();
  if (!auth.token) return undefined;
  const blob =
    input.content instanceof Blob
      ? input.content
      : new Blob([input.content as BlobPart], { type: input.mediaType ?? "text/plain" });
  const body = new FormData();
  body.append("file", blob, input.name);
  const response = await fetch("/admin/artifacts", {
    method: "POST",
    headers: { Authorization: `Bearer ${auth.token}` },
    body,
    credentials: "omit",
  });
  const payload = await response.json().catch(() => ({}));
  const envelope = payload?.code === "OK" && payload?.data ? payload.data : payload;
  const artifactId = typeof envelope?.artifactId === "string" ? envelope.artifactId : "";
  if (!response.ok || !artifactId) return undefined;
  return {
    artifactId,
    name: input.name,
    mediaType: input.mediaType,
    downloadUrl: `/admin/artifacts/${artifactId}/download`,
  };
}

/** 受控下载（已注册附件）：返回 Blob；真实=GET downloadUrl（带票据），演示=mock 传输层。 */
export async function downloadStoredArtifact(ref: StoredArtifactRef): Promise<Blob | undefined> {
  if (mockArtifactsEnabled()) {
    const { mockArtifactRequest } = await import("./mock-artifacts");
    const response = await mockArtifactRequest(ref.downloadUrl);
    return response ? await response.blob() : undefined;
  }
  const auth = (await import("./api")).useAuth();
  if (!auth.token) return undefined;
  const response = await fetch(ref.downloadUrl, {
    headers: { Authorization: `Bearer ${auth.token}` },
    credentials: "omit",
  });
  if (!response.ok) return undefined;
  return await response.blob();
}
