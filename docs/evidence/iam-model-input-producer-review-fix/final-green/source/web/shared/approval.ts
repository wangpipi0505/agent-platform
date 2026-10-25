export function decisionPayload(
  snapshot: any,
  approved: boolean,
  requestId: string,
) {
  if (!snapshot.snapshotDigest)
    throw new Error("参数快照缺少摘要，请刷新确认卡片");
  if (snapshot.expiresAt && Date.parse(snapshot.expiresAt) <= Date.now())
    throw new Error("确认已过期，请刷新运行状态");
  return { approved, requestId, snapshotDigest: snapshot.snapshotDigest };
}
