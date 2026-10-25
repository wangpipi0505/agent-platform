# 优化台账执行报告：R-A8 / R-A2 / R-A5（首轮）

> 执行日期：2026-10-25。对应《优化台账-智能体侧-agent-platform》v1.0 的三个待启动项。
> 环境：JDK 21.0.12（Adoptium）、Maven 3.9.16、工程专用 PG 16.15（127.0.0.1:25432，V: 盘别名启动）、`ENGINE_PG_TEST=true` + `AGENT_DB_*` 凭据来自工程 `.env`。
> 基线提交：`1563353 chore: 建立工程基线`。

## R-A8 maxDepth 计账 + 无进展指纹改"连续 3 轮"

**代码变更**
- `engine-runtime/.../application/AgentDriver.java`：指纹状态从 `Set<fingerprints>`（一次重复即熔断）改为 `lastFingerprint + repeatedRounds` 连续计数，第 3 轮连续相同才抛 `NO_PROGRESS`；指纹摘要改为**只取 技能名+参数**（不含调用 id），对齐契约"skillId+参数hash"口径。
- `engine-runtime/.../application/FlowDriver.java`：AGENT 分支在进入子循环前 `store.consume(runId,"depth",1,2)`，补上契约要求的深度预算消费点（此前 `consumed.depth` 只有聚合校验、无消费方，嵌套 Agent 实际不受限）。计账语义：depth 与 steps/tokens 同为单调累计，maxDepth=单次运行允许进入的 Agent 子循环总次数上限。

**测试**：`engine-runtime/src/test/.../infra/AgentLoopBudgetPostgresTest.java`（3 例，全绿）
1. `noProgressMeltsOnlyOnThirdConsecutiveIdenticalRound`——三轮同技能同参数但调用 id 不同（证明指纹忽略 id），第三轮熔断；且前三轮模型调用均已真实记账（model_invocation=3）。
2. `twoConsecutiveRepeatsAndChangeRecoverToTerminalAnswer`——两轮重复不熔断、参数变化重置计数、纯文本收口。
3. `depthBudgetBlocksThirdAgentLoopEntry`——depth 计账通道：第 3 次消费抛 `BUDGET_DEPTH`，账面停在 2。

## R-A2 压缩语义核对 + 过时注释修正

**核对结论（无代码缺陷）**：`ContextProjection` 四档管道（工具输出修剪→整轮丢弃→模型摘要→硬截断）与契约 §31.6.2/§6.2 一致；类不变量"原始行永不改写、投影为纯函数"使"重试代际前进"问题在本引擎**按构造不成立**（每次投影从原始消息重新计算，失败的请求重试不会累积改写状态）。trimLog/manifest 双摘要（sourceMessagesSha256/projectedMessagesSha256）满足留痕要求。

**代码变更**：`ContextAssembler.java` L68 起的过时注释（"Reject overflow until a configured summarizer exists"——为整合版误判 REF-02 的来源）修正为如实描述：溢出不在此拒绝，ModelExchange 派发统一走 ContextProjection 四档投影；字符密度仅为巡检估算，准入由网关 tokenizer 计量。

**测试**：无需新增（行为未变）；既有 `ContextProjectionTest`、`ModelExchangeTest` 全量回归通过。

## R-A5 RUNNING run 失联终态收敛（RunOrphanConvergence）

**新增** `engine-runtime/.../infra/RunOrphanConvergence.java`（@Scheduled initialDelay=15s / fixedDelay=30s，与 ExecutionRecovery 分工：那边做外部技能事实对账，这边只收敛进程失联）：

- 孤儿判定三条件（缺一不收敛）：①调度租约已死（dispatch_until 非空且过期超 10s 宽限——dispatch 为空的 run 等待认领不是孤儿）；②无任何节点执行租约存活；③仍存在非终态节点（全终态的 run 归聚合路径收尾）。WAITING_* 态不在范围。
- 持锁后按同口径复判（防与刚续租/刚认领的 worker 竞争、防扫描后最后一节点已提交的竞态）——该内层守卫正是首轮测试暴露的缺陷（测试 4 抓到后补上）。
- 收敛动作与 stopForBudget 同构：run→FAILED(stop_reason=ORPHANED) + error 语义、非终态节点→CANCELLED、被中断节点最新 attempt 追加 `ORPHAN_CONVERGED` 注记（含收敛前状态）、`RUN_FINISHED` 事件留痕。

**测试**：`RunOrphanConvergencePostgresTest`（4 例，连续 3 轮全绿）：孤儿收敛含全部证据四件套；健康调度租约跳过；WAITING_APPROVAL 跳过；全终态节点留给聚合。

## 回归与已知预存问题

- 本轮最终全量 engine-runtime 回归结果见文末补记（执行时后台运行中）。
- **预存问题（与本轮改动无关，已用 `git stash` 在干净基线复现）**：
  1. `OwnedFlowMaterialsPostgresTest.lastNodePostCommit...`：期望 4 次新请求实得 6（时序敏感，基线同样失败）；
  2. `admin-common` 下 `ServiceTokensDeploymentModeTest`×5（`Files.readString` 未指定字符集，Git Bash/GBK 环境解码失败）、`LocalRunAdvanceAuthorityTest`×1、`AuthorityPolicyTest`×1——均在工程 PowerShell 工具链下通过，属编码/环境敏感，建议另立台账项处理（修法：`Files.readString(path, StandardCharsets.UTF_8)`）。

## 提交清单

- R-A8：`AgentDriver.java`、`FlowDriver.java`、`AgentLoopBudgetPostgresTest.java`
- R-A2：`ContextAssembler.java`
- R-A5：`RunOrphanConvergence.java`、`RunOrphanConvergencePostgresTest.java`
