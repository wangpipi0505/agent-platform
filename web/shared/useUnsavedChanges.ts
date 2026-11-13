import { onBeforeUnmount, watch } from "vue";
import { onBeforeRouteLeave } from "vue-router";
import { confirmDanger } from "./components/DangerConfirm";

/** 离开决策：pass=无未保存更改直接放行；block=用户取消留在本页；discard-pass=放弃更改放行。 */
export type LeaveAction = "pass" | "block" | "discard-pass";

/**
 * MNT-02子步2：离开决策纯函数——四路径（dirty计算/取消/放弃/安全清除）。
 * 安全优先：安全转换（登录失效/撤权/退出/身份切换）直接放行并丢弃更改，
 * 不被离开确认阻挡；普通离开时 dirty 由页面以最近一次服务端返回为基线判定。
 */
export function resolveLeaveAction(state: {
  dirty: boolean;
  security: boolean;
  confirmed: boolean;
}): LeaveAction {
  if (state.security) return "discard-pass";
  if (!state.dirty) return "pass";
  return state.confirmed ? "discard-pass" : "block";
}

/**
 * 未保存离开保护：页面以最近一次服务端返回（expectedDraftRev/快照）为基线提供 isDirty；
 * 路由离开时若 dirty 则确认——取消留在本页，确认丢弃（不写库、不自动提交）并回调 onReset。
 * securityKey 变化（登录失效/撤权/退出/身份切换）即清除编辑基线并放行后续导航（安全优先）。
 * 保存成功后页面更新基线，dirty 自动复位不误报。
 */
export function useUnsavedChanges(options: {
  isDirty: () => boolean;
  /** 安全转换信号：会话令牌（或代次）——值变化视为身份失效/切换。 */
  securityKey?: () => string | undefined;
  /** 放弃或安全清除时由页面复位本地编辑基线。 */
  onReset?: () => void;
}) {
  let security = false;
  let lastSecurityKey = options.securityKey?.() ?? "";
  const stopSecurity = options.securityKey
    ? watch(
        options.securityKey as () => string,
        (value) => {
          if (value === lastSecurityKey) return;
          security = true;
          lastSecurityKey = value;
          options.onReset?.();
        },
        { flush: "sync" },
      )
    : undefined;
  // F1-3：原生 confirm 清零——离开确认改走 DangerConfirm（异步弹窗）；
  // 守卫返回 Promise，vue-router 等待用户决策后再放行/拦截。
  onBeforeRouteLeave(async () => {
    const confirmed = await confirmDanger({
      title: "未保存的更改",
      message: "有未保存的更改，离开将丢弃。确认离开？",
      confirmText: "离开并丢弃",
    });
    const action = resolveLeaveAction({
      dirty: options.isDirty(),
      security,
      confirmed,
    });
    if (action === "discard-pass") options.onReset?.();
    security = false;
    return action !== "block";
  });
  onBeforeUnmount(() => {
    stopSecurity?.();
  });
  return { resolveLeaveAction };
}
