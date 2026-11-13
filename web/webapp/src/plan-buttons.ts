/**
 * F4-3 计划卡按钮状态机（前端稿 §5.4"计划卡按钮状态机"表；webapp 计划卡消费）。
 * 纯函数：输入=服务端返回的所选修订 confirmState + canConfirm/canExecute（前端不自判，
 * 红线8）+ 确认凭据展示字段；输出=确认/执行按钮的渲染决策。INVALID/PENDING 校验态双禁用。
 */

export type ConfirmState = "UNCONFIRMED" | "CONFIRMED_VALID" | "CONFIRMED_EXPIRED"
export type ValidationStatus = "VALID" | "INVALID" | "PENDING"

export interface PlanButtonInput {
  /** 所选修订确认状态（服务端 plan.confirmation.state）。 */
  confirmState: ConfirmState
  /** 服务端计算的确认权布尔（前端不自行判断权限）。 */
  canConfirm: boolean
  /** 服务端计算的执行权布尔（仅针对所选修订）。 */
  canExecute: boolean
  /** 所选修订校验状态（INVALID/PENDING 双禁用）。 */
  validationStatus?: ValidationStatus
  confirmedBy?: string
  expiresAt?: string
}

export interface PlanButtonDecision {
  confirm: {
    /** UNCONFIRMED 且无确认权时隐藏（改显 waitingNote）。 */
    visible: boolean
    disabled: boolean
    label: string
    tooltip: string
    /** 凭据摘要（CONFIRMED_VALID：已确认 by X 至 Y）。 */
    note: string
  }
  execute: { disabled: boolean; tooltip: string }
  /** canConfirm=false 的 UNCONFIRMED 提示文案（其余为空串）。 */
  waitingNote: string
}

export function planButtons(input: PlanButtonInput): PlanButtonDecision {
  const { confirmState, canConfirm, canExecute, validationStatus = "VALID" } = input
  const invalid = validationStatus !== "VALID"
  // INVALID/PENDING：校验未通过/待校验，确认与执行都不可用（状态机表末行）。
  if (invalid) {
    const why = validationStatus === "INVALID" ? "校验未通过" : "校验中"
    return {
      confirm: { visible: true, disabled: true, label: "确认", tooltip: why, note: "" },
      execute: { disabled: true, tooltip: why },
      waitingNote: "",
    }
  }
  if (confirmState === "CONFIRMED_VALID") {
    const who = input.confirmedBy ? ` by ${input.confirmedBy}` : ""
    const until = input.expiresAt ? ` 至 ${input.expiresAt}` : ""
    return {
      confirm: { visible: true, disabled: true, label: "已确认", tooltip: `已确认${who}${until}`, note: `已确认${who}${until}` },
      execute: { disabled: !canExecute, tooltip: canExecute ? "" : "不可执行" },
      waitingNote: "",
    }
  }
  if (confirmState === "CONFIRMED_EXPIRED") {
    return {
      confirm: { visible: canConfirm, disabled: false, label: "重新确认", tooltip: "", note: "凭据已过期" },
      execute: { disabled: true, tooltip: "凭据已过期" },
      waitingNote: canConfirm ? "" : "待提交人/APPROVER 确认",
    }
  }
  // UNCONFIRMED：确认按钮可点（有确认权）或隐藏（无确认权）；执行恒禁用——tooltip"待确认"。
  return {
    confirm: { visible: canConfirm, disabled: false, label: "确认", tooltip: "", note: "" },
    execute: { disabled: true, tooltip: "待确认" },
    waitingNote: canConfirm ? "" : "待提交人/APPROVER 确认",
  }
}
