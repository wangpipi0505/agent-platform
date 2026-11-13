# H1-2 证据：turn 写入事件联动 + run 早关联（含 wave1 合并记录）

日期：2026-11-13。泳道 L1 卡 H1-2（后端A），提交 290a84f9，合并 3cf89684。

## 交付

- `SessionStore(TraceStore)` 构造器：append 经 trace.atomic（REQUIRED 传播）加入调用方事务——`chat_turn` 创建与 `turn/start`+`user/message` **同事务**（主方案 §5.1）。
- `ChatSessions.turn()` 轮次预留事务内追加两事件；run 受理即回写 `chat_turn.run_public_id`（既有机制保持）。

## 定向测试（ChatTurnEventLinkageTest，3/3 绿）

| 用例 | 覆盖时点 | 断言 |
| --- | --- | --- |
| turnLifecycleBindsRunEarlyAndReplaysToSameTurn | 受理后（P2）/提交后（P4） | run 早关联、事件恰两件（seq1=turn/start{turn:1}）、重启（新 ChatSessions 实例）重放归原轮次原运行、watermark 恒 2 |
| secondTurnAppendsDistinctEventsAndReplaysLandOnOwnRuns | 轮次递增 | 第二轮事件续写 seq3,4（turn=2）；重启后各轮重放各归各运行 |
| failedRunReplayReturnsSameRunWithoutDuplicateTurn | 受理后运行失败 | 重放归原 FAILED 运行、零重复轮次/事件（运行层受理恒 1 由 H1-0 证） |

边界：`evaluationTurn` 不在本卡；"预留后 run 未建"窗口位于 ticket.persist 单事务内部、公共 API 不可达，以"受理后运行失败"恢复不变量覆盖。

## 定向回归（修复一项 H1-1 遗留测试污染后）

- ChatSessionsTest 4/4 + SessionStoreContractTest 5/5 + ChatTurnEventLinkageTest 3/3 + AgentReceiptLostRecoveryGateTest 1/1 = **13/13 绿**（合并基线 3cf89684 上复核）。
- 污染修复：SessionStoreContractTest 增加 @AfterEach（原收尾残留 `hs_t_%` 行以 FK 阻断 ChatSessionsTest 的无界 chat_session 清理）；ChatTurnEventLinkageTest 同款 @BeforeEach/@AfterEach 兜底。

## 环境备注

- 定向回归期间一次后台 shell 滞留（无 PG 锁等待，为 shell 管道问题），杀掉重跑即过——与产品代码无关。
