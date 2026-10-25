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
  const url = new URL(location.href);
  url.searchParams.set("sessionId", id);
  history.replaceState(null, "", url);
}
