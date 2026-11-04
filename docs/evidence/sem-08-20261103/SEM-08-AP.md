# SEM-08 证据：业务目标状态读回核验与恢复（AP / engine）

日期：2026-11-04
子步：SEM-08（整卡）
席位：O（OR 与语义），本子步按派单承接 engine 侧实现
提交：见回执

## 1. 范围与白名单

实现文件：

- `engine-runtime/src/main/resources/db/migration/V228__goal_outcome.sql`
- `engine-runtime/src/main/java/cn/agentplatform/engine/domain/GoalOutcome.java`
- `engine-runtime/src/main/java/cn/agentplatform/engine/infra/GoalOutcomeStore.java`
- `engine-runtime/src/main/java/cn/agentplatform/engine/application/GoalVerificationService.java`
- `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java`（仅终态后接线与状态只读呈现）
- `engine-runtime/src/test/java/cn/agentplatform/engine/infra/GoalVerificationServiceTest.java`

未触达：`SkillExecution`/`SkillAdapters`/skill-registry、OR 任何文件、`ToolCalls`、前端。

## 2. 事实模型与三类分离

`engine.goal_outcome` 每行是一个目标在某个运行上的处置：

- `criterion`：受理时从 `run.snapshot.config.goals[]` 冻结的判据，不随后续编辑变化。
- `outcome_state`：`ACHIEVED/NOT_ACHIEVED/PENDING/FAILED/UNKNOWN`，只由读回事实产生。
- `readback_ref`：`{evidenceKind:"call", callId}`，仅 `ACHIEVED` 必填（DB 约束同）。
- `next_step`：等待/失败/未知时的终止条件或下一步；`PENDING`/`UNKNOWN` 必填（领域构造校验）。
- `detail`：`callId/callStatus/skillCode/predicate/matched/readbackValue/errorSemantic` 等证据字段。
- `revision`：内容变化才递增；相同内容重复核验不产生新版本（`on conflict ... where ... is distinct from`）。

运行终态、工具调用状态、目标处置是三类事实：`engine.run.status` 不被核验改写；`engine.tool_call` 只读；
只有 `goal_outcome` 由本子步写入。`ACHIEVED` 必须同时满足“验证操作是发布配置中的只读操作”与“回执命中判据”，
HTTP 200、工具 `SUCCEEDED`、模型自述都不单独构成完成。

## 3. 核验逻辑

`GoalVerificationService.verify(runId)`：

1. 读取 `engine.run` 的 `status` 与 `snapshot.config.goals/tools`；目标数上限 `MAX_GOALS=32`，超出在 `budget` 中标记 `truncated`。
2. 每个目标要求 `goalId`、`criterion.readback.skillCode`；验证操作必须在同一发布配置的 `tools[]` 中声明
   `execution.readonly=true` 且 `sideEffect!=true`，否则 `UNKNOWN(readback_not_approved)`。
3. 按 `skillCode`（或判据给出的 `callId`）取该 run 最新 `engine.tool_call`，只读 `result`。
4. 状态映射：
   - `SUCCEEDED` + 判据 `equals/nonEmpty/exists` 命中 → `ACHIEVED` + `call` 回执引用；
   - `SUCCEEDED` 未命中或结果无该字段 → `NOT_ACHIEVED`（`value_missing/value_mismatch/empty/absent`），不标完成；
   - `FAILED` + 拒绝类语义（`NO_PERMISSION/PARAM_ERROR/CONTRACT_INVALID/CALL_CONTRACT_REQUIRED/ENDPOINT_FORBIDDEN/GUARD_ATTR_UNRESOLVED`）→ `NOT_ACHIEVED`，其余 `FAILED`；
   - `UNKNOWN` → `UNKNOWN`（沿用既有对账，不误标未达成）；
   - `REGISTERED/SUBMITTED/EXECUTING` → `PENDING`；
   - `ABANDONED`：run 终态 → `NOT_ACHIEVED`，否则 `PENDING`；
   - 无调用：run 终态 → `NOT_ACHIEVED`（`readback_not_started`），非终态 → `PENDING`（等待同 run 恢复后的调用）。
5. 汇总 `counts`（五态）与 `complete`（全部 `ACHIEVED`）。

`verifyIfTerminal` 仅在 `SUCCEEDED/FAILED/CANCELLED` 时执行，避免在运行中反复轮询源系统。

## 4. RunService 接线

- `aggregate(long rid)`：事务内终态分支把 state 记录到 `AtomicReference`，事务提交后调用 `verifyGoals(rid)`；
  核验异常被吞掉，不回滚终态、不改运行状态。
- `cancel(id, actor)`：取消事务提交后调用同一 `verifyGoals(rid)`，保证取消入口也有处置记录。
- `setGoalVerification` 为 `@Autowired(required=false)`，未装配时不产生记录，既有测试/直接构造保持原行为。
- `composeStatusBody`：仅在存在处置记录时追加 `goals` 字段，旧响应形状不变。

## 5. 真实 PG 测试与结果

环境：dev-ap 集群 `127.0.0.1:35432`，库 `agent_platform_engine_test`（`dev_ap_test`），Flyway 迁移至 V228
（`engine.flyway_schema_history` version=228 success=t）。

命令（两轮均使用同一环境变量）：

```
ENGINE_PG_TEST=true \
ENGINE_PG_URL=jdbc:postgresql://127.0.0.1:35432/agent_platform_engine_test \
AGENT_DB_USER=dev_ap_test AGENT_DB_PASSWORD=*** \
JAVA_HOME=.../jdk-21 \
mvn -o -pl engine-runtime -am -Dtest=GoalVerificationServiceTest \
    -Dsurefire.failIfNoSpecifiedTests=false test
```

第一轮：7 例，2 失败 1 错误。失败原因均为夹具而非实现：

- 同一 run 内复用 `step_seq/node_def_id` 触发唯一约束 → 每次调用使用独立节点序号；
- `readbackRef.callId` 在 JSONB 读回后是 `Integer`，断言写死 `Long` → 改为按数值比较；
- 混合结果用例让三个目标共用同一 `skillCode`，读到同一最新调用 → 每个目标指向独立验证操作（这本身是判据设计约束：同一验证操作的多目标应共享同一次读回，或显式给 `callId`）。

第二轮：`Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`。

回归（同一 PG 环境）：

```
-Dtest=GoalVerificationServiceTest,ChatSessionsTest,DebugControlsTest,ExecutionRecoveryTest,EngineProcessCancellationTest,RunObservationStatusHttpPostgresTest
```

结果：21 例中 19 通过，2 个环境受限（均与本改动无关）：

- `EngineProcessCancellationTest`：硬编码 `jdbc:postgresql://127.0.0.1:25432/agent_platform_engine_test`，本窗口无 25432 集群；
- `RunObservationStatusHttpPostgresTest`：启动时自带 25432 连接。
  两者都直接构造 `RunService`（无 `goalVerification`），接线为 no-op，状态字段仅在存在处置时追加。

## 6. 约束与未覆盖

- 只读验证操作的“批准”依据是同一发布配置的 `tools[].execution.readonly`，不是调用时客户端参数；与 SEM-07 的冻结技能契约口径一致。
- 核验只读 `engine.tool_call`，不重发调用，所以超时后源已成功的场景可以零重复写读回；未覆盖真实业务软件源（需 BASE/业务系统窗口）。
- 未新增对外 HTTP 路由；处置通过 `RunService.status()` 的 `goals` 字段与 `GoalVerificationService.list(runId)` 提供，管理端展示留前端子步。
- 并发：同一 run 的核验由终态事务后的单次调用触发；多实例并发下 `upsert` 以主键收敛，重算幂等。未做跨实例串行化压测。
- `goal@1.0` schema 文件与 AP §7 注册表登记不在本子步白名单，留 AP 侧收口。

## 7. 回退

停止在终态后调用 `verifyGoals`（移除两处调用）即可停止新处置写入；`goal_outcome` 历史保留，不篡改旧 run 终态与调用事实。
