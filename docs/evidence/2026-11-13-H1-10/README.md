# H1-10 回归+oracle 门（2026-11-13 执行）——证据补档（R-12）

- 台账归属：§7.2 阶段 1（预估 4 人日）
- 状态：**通过**（2026-11-14 用户 M1 批量签认；门 M1-②，全量 116 类移 M5 收口）
- 交付日期：2026-11-13 执行；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：**无（纯回归执行卡，索引 §二"无提交锚点 4 张"之一）**——回归在 2026-11-13 于彼时工作树执行，无独立代码提交
- 复查命令：台账 §7.2 H1-10 卡面（只读）；本目录为机制级补档

## 验收口径（卡面摘录）

> 回归报告：engine 改动面定向回归 15 类 **78/78 绿**（含主链路冒烟：受理→恢复门 M1-①、影子 turn 信封、轮次事件、会话端点/SSE、快照绑定、审批拦截、intent 键、Inbox、恢复事实、既有 ExecutionRecovery/TracePostgres 夹具）+web 改动面全量 **518 用例 13 失败=§9.4 基线登记完全一致（assertion-editor 6/mcp-registration 2/run-detail-navigation 4/satellite-planning 1），零新增失败**；oracle 三场景=AgentEngineShadowTest（B1/B2/B4）+AgentReceiptLostRecoveryGateTest（受理恒 1）。

## 复现命令（15 类定向回归，逐一可复跑）

```bash
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
export ENGINE_PG_TEST=true; set -a; source .env; set +a
mvn -pl engine-runtime test -Dtest='AgentReceiptLostRecoveryGateTest,AgentEngineShadowTest,ChatTurnEventLinkageTest,InboxContractTest,AgentD2RequestPersistTest,StepSnapshotBindingTest,RecoveryFactExitTest,SessionEventsEndpointTest,SessionStoreContractTest,TodoJobsTest' -DfailIfNoTests=false
# web 改动面（前端基线对照）：cd web && npx vitest run（13 失败=§9.4 基线）
```

## 关键输出摘要（2026-11-19 实跑复验·门内代表类）

R-12 补档在同一 PG 25432 环境对门内代表类**实际复跑 9 类**（AgentReceiptLostRecoveryGateTest 1/1、AgentEngineShadowTest 3/3、ChatTurnEventLinkageTest 3/3、InboxContractTest 5/5、AgentD2RequestPersistTest 2/2、StepSnapshotBindingTest 3/3、RecoveryFactExitTest 2/2、SessionEventsEndpointTest 11/11、SessionStoreContractTest 5/5）——**合计 35/35 全绿**（详见各卡目录，报告 mtime 2026-11-19 01:38）。oracle 三场景（B1/B2/B4+受理恒 1）包含在上述实跑内。

诚实登记：
- 卡面 15 类 78/78 为 2026-11-13 交付时点口径；本补档复跑为其中 9 类代表（35/35 绿），其余类（ExecutionRecovery/TracePostgres 夹具等）未逐类复跑，复现命令同上替换 `-Dtest`；
- web 518 用例基线对照未复跑（本工作树无 node_modules），命令附上。

## Surefire 报告指针

门内 9 类 surefire 报告均在 `engine-runtime/target/surefire-reports/`（mtime 2026-11-19 01:38，本补档实跑产物；target/ 不入 Git，逐类数字已登记于各卡 README 与本文件）。
