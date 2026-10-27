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

/**
 * PLAT-15（契约 §14）：两段审批的 actionPlan 展示视图。确认人看到"将要做什么"
 * （summary/target/diff/risk），不是只回答"是否允许"；计划不存在（旧一段审批）返回 null。
 */
export function actionPlanView(row: any) {
  const plan = row?.parameterSnapshot?.actionPlan;
  if (!plan || typeof plan !== "object" || Array.isArray(plan)) return null;
  const risk = plan.risk === "HIGH" ? "HIGH" : "STANDARD";
  const roles = Array.isArray(row?.confirmRoles) ? row.confirmRoles.length : 0;
  const diffText =
    plan.diff == null
      ? "（未提供差异详情）"
      : typeof plan.diff === "string"
        ? plan.diff
        : JSON.stringify(plan.diff, null, 2);
  return {
    summary: String(plan.summary ?? "对目标对象的写操作"),
    target: plan.target ?? null,
    diff: plan.diff ?? null,
    diffText,
    risk,
    highRisk: risk === "HIGH",
    expiresAt: plan.expiresAt ?? null,
    idempotencyKey: plan.idempotencyKey ?? null,
    // 高危确认强度（定死 D4）：confirmRoles 少于两个不同角色时必须二次确认。
    secondConfirmRequired: risk === "HIGH" && roles < 2,
  };
}

/** 审批决定载荷：沿用 decisionPayload 的摘要绑定；高危 + 强度不足时必须携带二次确认标记。 */
export function approvePayload(
  row: any,
  approved: boolean,
  requestId: string,
  doubleConfirmed = false,
) {
  const payload: any = decisionPayload(row, approved, requestId);
  const view = actionPlanView(row);
  if (approved && view?.secondConfirmRequired) {
    if (!doubleConfirmed)
      throw new Error("高危操作需要先勾选二次确认，再批准执行");
    payload.doubleConfirmed = true;
  }
  return payload;
}
