// R01附录I.3/R08（SCN-005）：POST /admin/artifacts 顶层回包 {artifactId,bytes,sha256} 为规范形状；
// 仅兼容明确的 OK 信封。错误信封、缺ID、失败HTTP一律按失败处理，不猜其他包装形状。
export async function uploadArtifact(token: string, file: File): Promise<string> {
  const body = new FormData();
  body.append("file", file);
  const response = await fetch("/admin/artifacts", {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
    body,
  });
  const payload: unknown = await response.json().catch(() => ({}));
  const record = typeof payload === "object" && payload !== null ? (payload as Record<string, unknown>) : {};
  const envelope = record.code === "OK" && typeof record.data === "object" && record.data !== null
    ? (record.data as Record<string, unknown>)
    : undefined;
  const artifactId = envelope ? envelope.artifactId : record.artifactId;
  if (!response.ok || typeof artifactId !== "string" || artifactId === "")
    throw new Error(
      typeof record.message === "string" && record.message !== ""
        ? record.message
        : `文件上传失败 (${response.status})`,
    );
  return artifactId;
}
