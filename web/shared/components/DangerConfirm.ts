/**
 * F1-3（台账 §7.5 / 方案 §4 DangerConfirm 行）：命令式确认弹窗——替代全站原生 confirm()。
 * 文件名与台账组件名一致；ElMessageBox 按需动态引入（模块零顶层依赖，
 * 纯 TS 使用方如 useUnsavedChanges 可安全静态 import）。
 *
 * 返回 Promise<boolean>：确认=true；取消/Esc/关闭一律=false。
 * 支持标题/描述/确认文案/危险态（红色主题）/高危操作可选输入确认词。
 */
export interface DangerConfirmOptions {
  /** 弹窗标题；缺省“请确认” */
  title?: string;
  /** 后果说明文案（方案要求“后果说明文案”——调用方写清不可逆影响） */
  message: string;
  /** 确认按钮文案，缺省“确认” */
  confirmText?: string;
  /** 取消按钮文案，缺省“取消” */
  cancelText?: string;
  /** 危险态：确认钮红色主题（默认 true——凡走本组件的均按高危处理） */
  danger?: boolean;
  /** 高危操作可选输入确认词：须逐字输入该词才能确认（方案 §4“可选输入确认词”） */
  confirmWord?: string;
}

export async function confirmDanger(
  options: DangerConfirmOptions,
): Promise<boolean> {
  const {
    title = "请确认",
    message,
    confirmText = "确认",
    cancelText = "取消",
    danger = true,
    confirmWord,
  } = options;
  const { ElMessageBox } = await import("element-plus");
  const boxOptions = {
    confirmButtonText: confirmText,
    cancelButtonText: cancelText,
    type: danger ? ("warning" as const) : ("info" as const),
    customClass: `danger-confirm-dialog${danger ? " is-danger" : ""}`,
    ...(confirmWord
      ? {
          inputPlaceholder: `输入“${confirmWord}”以确认`,
          inputValidator: (value: string) =>
            value === confirmWord || `请输入“${confirmWord}”以确认执行`,
        }
      : {}),
  };
  try {
    if (confirmWord) await ElMessageBox.prompt(message, title, boxOptions);
    else await ElMessageBox.confirm(message, title, boxOptions);
    return true;
  } catch {
    return false;
  }
}
