# SEM-07.2（AP 侧）确认后复核与入口一致性证据

日期：2026-11-04
席位：O
前置：SEM-07.1（OR `bc2c631`、AP `033926ce`）

## 1. 范围

白名单内改动：

- `skill-registry/src/main/java/cn/agentplatform/skill/application/SemanticExecutionGate.java`
  —— 新增 `recheck(Map contract)`：复用 `check` 的条件解释，只把“确认后已不成立/无法确认”
  翻译成重新确认；依赖不可达仍是 503。
- `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillExecution.java`
  —— 审批校验通过后、`POST /claim` 前再次复核；新增 `recheckSemantic` 辅助方法。
- `skill-registry/src/test/java/cn/agentplatform/skill/application/SemanticExecutionGateTest.java`
  —— 新增 recheck 五例。

未改 `SkillAdapters.java`；未改 engine（不在白名单，也不需要——见 §3）。
OR 侧本次无需改代码：`condition@1.0` 的逐条 `checkedAt`、`UNKNOWN` 原因与
`SEMANTIC_*` 错误映射已由 SEM-07.1 提供；为避免给封闭契约私加字段，本次不新增响应键。

## 2. 变更点与顺序

`SkillExecution.execute` 关键顺序（当前文件行号）：

| 行 | 动作 |
| --- | --- |
| 229 | 入口预检 `gateSemantic(contract)`（未确认前快速失败） |
| 257 | `needsApproval` 判定（`sideEffect` 或 `CONFIRM_REQUIRED`） |
| 276–293 | 读取审批事实、未批准返回 `WAITING_APPROVAL`、校验 `skillVersionId`/`parametersDigest` |
| 297 | **本次新增**：`recheckSemantic(contract)` |
| 308 | `POST /internal/tool-calls/{id}/claim` 认领 |
| 308 之后 | 适配器分发（真正的业务 I/O） |

因此复核发生在“确认页已批准、但尚未认领/未调用适配器”之间；拒绝时不产生任何 claim、
`SUBMITTED` 或业务写。`recheck` 只对 `SEMANTIC_CONDITION_BLOCKED` /
`SEMANTIC_CONDITION_UNKNOWN` 转成 `409 SEMANTIC_CONDITION_RECONFIRM`（“审批后语义条件已变化，
请重新确认后再执行”），其余错误原样上抛。

`recheckSemantic` 与入口预检采用同一装配规则：声明了 `execution.semanticConditions` 但门禁
未装配时 503 `SEMANTIC_CONDITION_UNAVAILABLE`；未声明则零跨服务调用。这样不能借“重新确认”
绕过“没检查”。

## 3. 各入口一致性（结构性事实）

| 入口 | 路径 | 结论 |
| --- | --- | --- |
| CHAT | `engine-runtime` 对话驱动 → `AgentDriver` → skill 服务 `SkillExecution.execute` | 同入口，受同一 gate |
| FLOW | 流程节点执行 → `AgentDriver` → `execute` | 同上 |
| 调试/受控草稿 | 受控草稿解析出同一契约 → `execute` | 同上 |
| 恢复 | `RunRecoveryCoordinator.recovered` → `RunService.Handler.driveOwned/driveLegacy` → `AgentDriver` → `execute` | 恢复不复制状态机，重新进入同一分发入口，自动复核 |

`RunRecoveryCoordinator.resumeExternalFacts` 只把已有事实的节点翻回 `READY` 并唤醒运行，
不重放技能调用；真正再次出站时仍走 `execute`，因此无需修改 engine 文件。引擎侧的
`ENGINE_PG_TEST` 用例不在本子步白名单内，需 P 在统一窗口补验“恢复后再次出站同样被复核”。

## 4. 无发送的拒绝不记为 UNKNOWN

- 拒绝点（297）在 claim（308）和适配器调用之前；代码路径上没有状态写入。
- `RESULT_UNKNOWN` 仅用于“已发送但无法确认结果”的既有权衡，未在本子步改变。
- 因此条件拒绝表现为明确的 4xx 重新确认/阻断，而不是不可判定的执行事实。

## 5. 并发与跨系统语义（如实声明）

同条件核验是**每次一次读取**：OR 侧按当前 READY 版本、空间授权与已登记绑定求值，返回
逐条 `checkedAt`。它不构成跨系统事务——复核通过到业务写入之间，源系统仍可能变化；本子步
不宣称强一致，也不新增授权 token/状态机；如需更强保证只能由业务侧幂等键/状态机承担。

## 6. 两轮测试

第一轮（新增用例，red→green）：

```
JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot" \
mvn -o -q -pl skill-registry -am -Dtest=SemanticExecutionGateTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：通过（13 例：原 8 例 + recheck 5 例）。

第二轮（回归）：

```
mvn -o -pl skill-registry -am \
 -Dtest=SemanticExecutionGateTest,SemanticDecisionClientTest,SkillExecutionTest,ApprovedConnectionGateTest,DefinitionLookupSkillsTest,SemanticFactsClientTest \
 -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：`Tests run: 69, Failures: 0, Errors: 0, Skipped: 24`。跳过的是
`SkillExecutionTest` 的 24 例：该夹具硬编码 `jdbc:postgresql://127.0.0.1:25432/agent_platform_v12`
（`SkillExecutionTest.java:41-43`），本窗口只有 `35432/agent_platform_test`，故无法在本子步
执行真实 PG 端到端；“零写计数/审批后变化拦截”的行为由门禁顺序与 recheck 单测覆盖，
端到端需 P 在 25432 环境或调整夹具后补验。

## 7. 未覆盖与回退

- 未覆盖：engine 恢复端到端（`ENGINE_PG_TEST`）、真实 25432 PG 的 `SkillExecutionTest`、
  HTTP 全链（CHAT/FLOW/调试各一次真实出站）。
- 回退：删除 297 行复核调用即回到 SEM-07.1 行为；已声明条件且门禁未装配时仍 503，
  不会降级裸调用。回退不删除 OR 侧条件能力与已发布声明。
