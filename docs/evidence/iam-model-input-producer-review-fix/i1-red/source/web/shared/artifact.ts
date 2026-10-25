export function artifactPath(file: any, runId: string) {
  // A business ticket must never be sent to arbitrary URLs or to the admin download surface.
  const path = file.externalDownloadUrl;
  const prefix = "/v1/runs/" + encodeURIComponent(runId) + "/artifacts/";
  if (
    typeof path !== "string" ||
    !path.startsWith(prefix) ||
    !/^\/v1\/runs\/[^/]+\/artifacts\/[^/?#]+\/download$/.test(path)
  )
    throw Error("附件缺少当前运行的受控下载入口");
  return path;
}
export async function downloadArtifact(
  file: any,
  runId: string,
  token: string,
  endpoint = "",
  admin = false,
  scope?:{signal:AbortSignal;current:()=>boolean},
) {
  const check=()=>{scope?.signal.throwIfAborted();if(scope&&!scope.current())throw new DOMException('下载已停止','AbortError');};
  check();
  const path = admin ? file.downloadUrl : artifactPath(file, runId);
  if(admin&&!/^\/admin\/artifacts\/[^/?#]+\/download$/.test(path||''))throw Error('附件缺少受控下载入口');
  const r = await fetch(endpoint + path, {
    headers: { Authorization: "Bearer " + token },
    signal:scope?.signal,
    credentials:'omit',
  });
  check();
  if (!r.ok) throw Object.assign(Error("下载附件失败 (" + r.status + ")"),{status:r.status});
  const blob=await r.blob();check();
  const url = URL.createObjectURL(blob);
  try {
  check();
  const a = document.createElement("a");
  a.href = url;
  a.download = String(file.name || file.artifactId || "artifact");
  check();
  a.click();
  } finally {URL.revokeObjectURL(url);}
}
