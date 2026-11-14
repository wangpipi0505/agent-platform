# T2-13 验收证据：A01-A08+A08b 机制级验收（阶段二收口）

日期：2026-11-14。卡片：台账 §7.3 **T2-13 A01-A08 与 A08b 验收**。基线 tip `9cc2864a`（阶段二 15 卡全交付）。

## 验收执行口径（诚实标注）

本环境无完整部署栈（七服务+模型服务+外部宿主），A01-A08 正式编号证据的**部署栈全链走查**按台账依赖（G5-0 部署演练/阶段5 mock 融合）承接。本卡按"测试分级减负——门级定向回归+冒烟"口径（§5.3 2026-11-13 修订）执行**机制级定向回归**：M2 门标准中的每项验收语义对应到已交付卡的定向测试集，全量绿即机制级通过；部署栈全链走查留待部署环境就绪后补正式编号证据。

## M2 门标准逐项对照（台账 §2.2）

| M2 标准 | 机制级证据（测试类→断言） | 结果 |
| --- | --- | --- |
| 并发 Plan/Agent 互不串扰 | PlanModeTest.双轨解耦（task.mode 受理固定与会话 plan/mode 独立）+ TasksApiTest.mode 枚举 | ✓ |
| 双分支并行+中断恢复 | AgentReceiptLostRecoveryGateTest（受理恒 1）+ AgentEngineShadowTest（turn 信封/取消/中断补写）+ RecoveryFactExitTest（恢复事实可重复取得）+ StepExecutionMappingTest（failure_policy 映射） | ✓ |
| 两个规划子代理汇合 | SubagentPlanningTest 8 例（A03 根会话幂等/派发幂等/双成功 merge 幂等/单失败/全失败 SUBAGENT_NO_VALID_CANDIDATE/迟到重复回执/目录分角色） | ✓ |
| A08b 超时/耗尽/终止尝试记录 | StepExecutionMappingTest（mapFailurePolicy timeoutMs/retry.max/abort 映射+onFailure PLAN_STEP_FAILURE 记账+必需下游 BLOCKED/可选 SKIPPED） | ✓ |
| 对账语义 | TaskReconciliationTest 5 例（授权链/归属链 404/证据复核 409 RETRY 已受理冲突/ADOPT 冲突 TRACK 过） | ✓ |
| 分派三要素 | TaskDispatchTest 7 例（接管核心：gen1 过期→gen2 接管→旧 gen1 409 STALE 留痕→gen2 DONE 恰一次） | ✓ |
| 压缩三事件锁 | CompactionStoreTest 4 例（三事件+replace+投影 A14/配对边界拒绝/孤儿锁/切换窗口 MODEL_SWITCH_COMPACTION_FAILED） | ✓ |
| 会话/任务域端到端 | ChatTurnEventLinkageTest（四时点重放）+ ChatSessionsTest + SessionEventsEndpointTest（11 例 SSE/游标/白名单）+ SessionListEndpointTest（6 例）+ MemoryApiTest（9 例 A09 子集）+ TaskEventsSseTest（5 例 SSE/裁剪）+ TodoJobsTest（4 例）+ SessionStoreContractTest（5 例契约）+ AgentD2RequestPersistTest（2 例 D2）+ PlansApiTest（4 例 CAS/确认/五项）+ TasksApiTest（6 例）+ PlanModeTest（5 例） | ✓ |

## 回归统计

任务域定向回归 **21 类 103/103 全绿，BUILD SUCCESS**（2026-11-14，PG 16.15 专用库 agent_platform_engine_test，JDK21）。

## 部署依赖（诚实登记）

- A01-A08 正式编号全链证据需部署栈（七服务+模型服务）——随 G5-0 部署演练/阶段5 承接；
- T2-15 result 无附件完整输出由 T2-13 接线后承接（result 首版形状已交付，goal 摘要口径）；
- T2-9 usage 校准随 T2-10 token 启发式承接（估算口径已交付，usage 回报校准待真实模型接入）；
- OPERATOR 工作区授权目录随工作区授权模型卡统一（T2-12/T2-11 同口径待澄清已登记台账）。
