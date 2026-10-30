export async function restoreSession(
  api: (path: string) => Promise<any>,
  code: string,
  id: string,
) {
  const base = "/v1/apps/" + encodeURIComponent(code);
  const session = await api(base + "/sessions/" + encodeURIComponent(id));
  if (!session.versionNo) throw Error("会话缺少固定发布版本");
  const declaration = await api(
    base + "/input-schema?versionNo=" + session.versionNo,
  );
  return { session, declaration };
}
export function writeSessionUrl(id: string) {
  // 空 id 只退出当前历史续接；旧会话仍受服务端归属校验保护。
  const url = new URL(location.href);
  if (id) url.searchParams.set("sessionId", id);
  else url.searchParams.delete("sessionId");
  history.replaceState(null, "", url);
}
