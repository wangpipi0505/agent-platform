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
  const policy = String(plan.approvalPolicy ?? "LEGACY_DECLARATION");
  const twoPrincipalsRequired = risk === "HIGH" && policy === "TWO_PRINCIPALS_V1";
  const confirmed = row?.approvalProgress?.confirmedCount;
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
    policy,
    twoPrincipalsRequired,
    confirmedCount: Number.isInteger(confirmed) && confirmed >= 0 && confirmed <= 2 ? confirmed : null,
    requiresReprepare: risk === "HIGH" && !twoPrincipalsRequired,
    // 客户端二次勾选不构成另一名确认人，人数只采用服务端持久审批事实。
    secondConfirmRequired: false,
  };
}

/** 只提交本人的决定与快照摘要；双主体人数与当前授权由服务端确认，不发送第二人声明。 */
export function approvePayload(
  row: any,
  approved: boolean,
  requestId: string,
  _doubleConfirmed = false,
) {
  const payload = decisionPayload(row, approved, requestId);
  const view = actionPlanView(row);
  if (approved && view?.requiresReprepare)
    throw new Error("旧高危计划未采用双主体政策，请重新发起调用与 PREPARE；原计划事实保留");
  return payload;
}
