# H1-6/H1-8 对外契约桩（工具调用事件与恢复事实）

链处理规则第 4 条③（台账 §2.1 v1.4）：H1-6/H1-8 对外契约先在 contracts/ 定桩，下游卡对桩先写、不等整链合入。2026-11-13。

裁决与来源：会话事件形状以 contracts/session-events.md + 数据库稿 §4.1 为权威；恢复事实以接口稿 §5.2 `recovery` 投影 + 数据库稿 §4.2 TASK_RESUMED 负载为权威。本桩只做**定桩细化**（字段级实现契约），不新增语义；与上位文档冲突时以上位文档为准。

## 1. H1-6：共用登记入口的工具调用会话事件（在 tools 登记事务内）

共用技能登记入口（AgentDriver.skill→ToolCalls.register 链）在**登记同一事务**内追加会话事件（仅当运行有会话关联——chatSession 注入，同 H1-5 口径）：

| 事件 | 时点 | 负载（data） | 事务边界 |
| --- | --- | --- | --- |
| `tool/call` | ToolCalls.register 成功后 | `{callId, name(技能code), args, skillVersionId, idempotencyKey}` | 与 register 同事务；受 `(session_id, callId)` 条件唯一索引约束——重放复用首锚点（不重复追加） |
| `tool/result` | 权威回执确认（fact/采纳/adapter SUCCEEDED） | `{callId, error?, meta{status, durationMs}}`；大结果外溢后带 locator | 与事实确认同事务 |

- **登记事务追加唯一调用事件**（台账 H1-6 验收原文）：重放（幂等命中）不得产生第二条 tool/call——唯一索引兜底 + append 前查询。
- **消费方**：H1-9 会话事件投影（tool/call+result 的展示投影按接口稿 §5.1 白名单）；B2 事件级配对（H1-4 影子已留接口，见 AgentEngineShadowTest 注记）；F3-6 调用锚点 `appCode/sessionId/eventSeq`。
- **无会话运行**（独立任务/评测）：不追加会话事件（与 H1-5 一致，事实保留运行链路）。

## 2. H1-6：意图键（沿用 BusinessIdempotency，零新增表）

- 意图键 = `(租户, 技能版本, 契约摘要, 意图值)`；意图值从**冻结 run.inputs** 按 `businessIntentInput` 字段名读取（模型输出不参与）。
- 首发缺值→PARAM 错误步骤 FAILED（不进待对账）；存在未决外部调用→WAITING_RECONCILIATION；同键参数摘要不一致→直接冲突。
- 验收三用例（接口稿 §5.2/主方案 §5.2）：①不同调用序号并发提交同一意图→外部受理一次；②同意图改参数→冲突且受理次数不变；③缺值首发→参数错误。

## 3. H1-8：恢复事实（RecoveryCoordinator 输出桩）

`RunRecoveryCoordinator` 增加只读恢复事实出口（不新增表；checkpoint 已含 eventWatermark——主方案 §5.8）：

```text
RecoveryFact {
  recoverySource: MANUAL | AUTO          // 手动/自动同入口
  reasonCode: EXTERNAL_RESULT_UNKNOWN | LEASE_EXPIRED | EXECUTION_INTERRUPTED | MANUAL_REQUEST
  reasonSummary: String                  // 受控枚举→可展示摘要（读取时映射）
  checkpoint: { eventWatermark: int, runRefs: [publicId] }
  reusedSteps: [ { stepId, resultDigest } ]
  blockedReasons: [ String ]             // 阻塞原因（可展示摘要）
}
```

- 写入路径：任务协调层与状态 CAS 同事务写 `TASK_RESUMED`（库稿 §4.2 负载）；引擎侧出口按角色投影（USER 仅 source/code/summary；OPERATOR/ADMIN 另见 checkpoint/reusedSteps/blockedReasons——接口稿 §5.2）。
- 供 T2-2（任务详情/resume/事件共用投影）与 T2-6/T2-7（对桩先写——阶段二卡，本桩即其依赖面）消费。
- H1-8 验收（台账原文）：外部受理恒 1（M1-① 已证基线），重启后可取得同一恢复事实。

## 4. 下游对桩登记

| 消费卡 | 对桩内容 | 泳道 |
| --- | --- | --- |
| H1-6 实现 | §1 事件写入 + §2 意图键 | L1 |
| H1-7 实现 | §1 采纳集（header.tools 已由 H1-5 落）+ 快照恢复只收窄 | L3 插缝 |
| H1-8 实现 | §3 RecoveryFact 出口 | L1 |
| T2-11（阶段二） | §1 事件流 + §3 投影 | L2 |
| T2-6/T2-7（阶段二） | §3 恢复事实/回执注入 | L3 |
