export function resumePayload(paused: any, action: string, requestId: string) {
  // Instance identity and pause generation form the CAS boundary; graph node IDs cannot resume execution.
  if (!paused?.nodeInstanceId || !Number.isFinite(paused.pauseSeq))
    throw new Error("没有可放行的挂起节点，请刷新状态");
  if (!paused.allowedActions?.includes(action))
    throw new Error("当前挂起状态不允许此操作");
  return {
    nodeInstanceId: paused.nodeInstanceId,
    pauseSeq: paused.pauseSeq,
    action,
    requestId,
  };
}
