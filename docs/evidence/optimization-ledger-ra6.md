# 优化台账执行报告：R-A6（外部唤醒 API）

> 执行日期：2026-10-25。对应《优化台账-智能体侧-agent-platform》R-A6。
> 环境：同 optimization-ledger-ra8-ra2-ra5.md（JDK21 + 工程 PG25432 + ENGINE_PG_TEST）。

## 变更

- **新增 `engine-runtime/.../application/RunWakeService.java`**：非审批类外部事件唤醒。
  - 语义：仅把 `dispatch_next_at` 提前到 `least(coalesce(dispatch_next_at, now()), now())`，不改 run 状态、不代替业务判定（WAITING_EXTERNAL 的事实采纳仍归 ExecutionRecovery 对账，唤醒只让调度器尽早重查）。
  - 幂等两层：动作本身幂等（重复设 now() 无副作用）+ `requestId` 载荷判重（同一外部事件重复投递返回 deduped=true，且只落一条 `WAKE_REQUESTED` 事件，判重手法与 RESUMED 的 requestId 唯一化同族）。
  - 终态 run 唤醒为无害 no-op（返回终态 + woken=false）；缺 requestId/source 按 400 `WAKE_INVALID` 拒绝；按 tenant 隔离解析目标 run。
- **`RunController`**：新增 `POST /internal/runs/{id}/wake`；为不破坏 10+ 处既有 4 参构造调用（含各测试夹具），采用与 portalEvents 相同的可选注入模式（未注入时按需构建，端点行为不变）。

## 测试

`RunWakeServicePostgresTest` 4/4 绿：
1. `wakePullsDispatchEarlierWithoutTouchingStatus`——WAITING_EXTERNAL + 一小时后的 dispatch_next_at，唤醒后提前到当下，状态不变，事件落一条；
2. `duplicateRequestIdIsDedupedToOneEvent`——同 requestId 二次投递 deduped=true 且事件唯一；
3. `terminalRunIsHarmlessNoOp`——SUCCEEDED run 返回 woken=false、无事件；
4. `missingFieldsAreRejected`——缺 source 抛 `WAKE_INVALID`。

## 回归

受影响面为纯新增（新服务类 + 新端点 + 可选注入），既有控制器路径未变语义；`RunController` 相关既有测试在编译期全量通过（本轮修复构造器兼容时的编译即覆盖所有调用方）。
