# H1-0 证据：外部受理后回执丢失 → 恢复 → 受理次数恒 1（门 M1-①）

日期：2026-11-13。卡片：台账 §7.2 **H1-0 小闭环**（阶段 1，负责人 后端C，依赖 M0✓）。
场景契约：`contracts/oracle-replay-adapter.md` §4.4 P0 fixture `example-recover-tool-call.json`（externalAcceptCount=1）。
测试类：`engine-runtime/src/test/java/cn/agentplatform/engine/infra/AgentReceiptLostRecoveryGateTest.java`。

## 环境

- 专用 PG 16.15 @127.0.0.1:25432（`.local/pgdata`，`scripts/start-database.ps1` 语义）；测试库 `agent_platform_engine_test`（EngineTestDatabase 自动建）。
- JDK 21（Eclipse Adoptium 21.0.12.8）；`ENGINE_PG_TEST=true`；库坐标来自 `.env`（AGENT_DB_USER/PASSWORD）。

## 运行记录（三轮）

| 轮 | 结果 | 说明 |
| --- | --- | --- |
| r1 | 红 | 测试自身语法错误（嵌套括号少一层）——编译失败，属测试代码初稿缺陷 |
| r2 | 红 | 两处真实发现：①stub 模型计数使恢复驱动续接轮误回工具调用（见下"语义发现"）；②断言期望模型请求 3 次与引擎 D2 重放语义不符 |
| r3（终） | **绿** | `Tests run: 1, Failures: 0, Errors: 0`，BUILD SUCCESS；复跑 r3' 同绿（mvn-run-r3-green.txt） |

## 语义发现（r2 期间由测试揭出，非缺陷，已写入测试注释）

恢复驱动第 0 轮的模型响应由 **model_invocation 持久化重放**（`ModelExchange.prepare`：`(run,node,round,kind,outputAttempt)` 已存 response 则直接回放）——即 D2"崩溃后从日志完整重建模型看到了什么"。恢复续接轮才发起真正的新模型请求。因此正确的恢复链路为：

1. 故障轮：模型请求 1 次 → 工具调用 → register（REGISTERED）→ 技能服务认领+提交（SUBMITTED）→ 外部受理 1 次 → 回执丢失（RestClientException）→ 权威状态 SUBMITTED → `RuntimeFault(RESULT_UNKNOWN)`。
2. 故障收口（与调度器同语义）：run/node 置 WAITING_EXTERNAL、租约过期。
3. `ExecutionRecovery.recover()`：候选命中 SUBMITTED+WAITING_EXTERNAL → `GET /internal/skill-facts/{id}` 受控查询（1 次）→ 事实摘要校验采纳 → tool_call SUCCEEDED + 事件 `EXTERNAL_FACT_ADOPTED`，全程零出站重发。
4. 恢复驱动：round0 模型响应重放（零请求）→ 工具调用 register 幂等命中 SUCCEEDED 直接消费既有事实（出站桩若再触达即失败）→ round1 续接模型请求（第 2 次）→ 终文本 → 驱动终态。

## 门判定（M1-①：受理次数恒 1）

| 断言 | 结果 |
| --- | --- |
| 外部受理计数 == 1（恢复后终判） | ✓ |
| 受控查询计数 == 1（恢复只查询不重发） | ✓ |
| tool_call 终态 SUCCEEDED、行数 == 1（无第二登记） | ✓ |
| run_event `EXTERNAL_FACT_ADOPTED` == 1 | ✓ |
| 恢复驱动终态文本 + 模型请求总数 == 2（首轮 1 + 续接 1；round0 重放零请求） | ✓ |

## 边界声明

- 故障注入与恢复均在既有机制内完成（零架构改动，符合 H1-0"不改架构"边界）；出站全部走测试桩，无真实外部 I/O（台账 §4.1 测试规范）。
- 本证据仅覆盖 M1-①；M1-②（事件日志版+116 测试类回归）依赖 H1-1..H1-9，不在本卡范围。
- 本轮启动专用 PG 的过程记录：`.tools` 便携包此前已就位；bootstrap.ps1 在 Windows PowerShell 5.1 下因 PS7-only API（`RandomNumberGenerator::GetBytes` 静态重载）失败，改为手工生成 `.env`（键位与脚本一致）+ `pg_ctl start` 启动；后续 G5-0 部署清单可补记此兼容性说明。
