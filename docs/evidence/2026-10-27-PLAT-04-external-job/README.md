# PLAT-04 异步结果带任务态 — 实现证据

- 卡：T04（PLAT-04，P0）
- 分支：`feat/v1.2-implementation`，基线 `dde22f4`（含 T01）
- 日期：2026-10-27
- 环境：JDK 21.0.12（Eclipse Adoptium），`-Dmaven.repo.local=.tools/m2`，PG 127.0.0.1:25432

## 0. 结论摘要

四条可观测行为全部落地，定向测试全绿且可红：

| 行为 | 落点 | 证据 |
| --- | --- | --- |
| 异步提交后绑定落远端任务 id，可按 run/tool-call 反查 | `skill.external_job_binding.external_job_id`（V307 后可空）+ `SkillExecution.externalJob()` 按 `tenant_id+call_id` 读 PG | skill 测试 #1/#3 |
| `GET /internal/tool-calls/{callId}` 带 `externalJob`（含 remoteJobId/state/submittedAt/lastPolledAt，无则显式 null） | `ToolCalls.view()` 投影 `engine.tool_call.external_job`（V220） | engine 测试 #1/#2 |
| 外部任务未终结时 `run.error` 带结构化 `externalJob` | `RunService.markWaitingExternal()` 把最近快照并入 RESULT_UNKNOWN 错误结构，写 run/node/attempt | engine 测试 #3 |
| 终态映射与恢复 | 远端成功→SUCCEEDED；失败→FAILED+REMOTE_FAILED（有界原文）；超时/未知→RESULT_UNKNOWN 且保留 remoteJobId+lastPolledAt；adopt/resume 清空等待快照 | skill 测试 #2/#3/#6、engine 测试 #4/#6 |

真实测试原文（最终一次运行）：

```
### SKILL
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 7.996 s -- in cn.agentplatform.skill.application.ExternalJobStateTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
### ENGINE
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 6.930 s -- in cn.agentplatform.engine.infra.ExternalJobStateTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 1. 背景

路线一暴露：异步技能提交成功后，平台只保存了自己生成的 `idempotencyKey`，远端任务 id 丢失；引擎侧 run 终态为 `RESULT_UNKNOWN` 时界面无外部任务态可看，用户只能看到"结果未知"，既不知道是否已提交，也无法区分"处理中"与"远端失败"。

必须交付：绑定落远端 id 且可反查；`GET /internal/tool-calls/{callId}` 返回 `externalJob`；run 记录在未终结时带结构化外部任务；终态精确映射（成功/失败/未知）且未知态保留 remoteJobId 与最后轮询时间。

## 2. 改动

### 2.1 文件清单

修改（白名单内）：

| 文件 | 行数变化 | 要点 |
| --- | --- | --- |
| `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillExecution.java` | +102 / −13（1290 → 1379） | `@Value("${agent.skill.external-job-enabled:true}")` 回滚开关；`envelope()` 注入 `externalJob`；`externalJob(request)` 从绑定行读 PG 并做状态映射；`refreshExternalJob(callId)` 在等待态把最新快照合并回 `execution_fact.result`（CAS `status='UNKNOWN'`）；`fact()` 改包可见供测试；`SUBMIT_NO_TASK_ID` 在开关关闭时回退旧语义 |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/HttpAsyncAdapter.java` | +85 / −9（478 → 554） | 无 taskId → `status='FAILED'`、`external_state='SUBMIT_NO_TASK_ID'`、`remote_error_code='SUBMIT_NO_TASK_ID'` 并抛 `SUBMIT_NO_TASK_ID`（非 unknown）；提交成功写 `external_state='SUBMITTED'`、`submitted_at`、`last_polled_at`；每次轮询刷新 `external_state`/`last_polled_at`（仅 `status in ('SENDING','SUBMITTED')`）；远端 `failed` → `status='FAILED'` + `REMOTE_FAILED` + 有界原文；成功 → `DONE`/`SUCCEEDED`；`failureMessage` 截断到 512（含省略号），`external_state` 截断到 32 |
| `engine-runtime/src/main/java/cn/agentplatform/engine/infra/ToolCalls.java` | +21 / −0（585 → 606） | `/fact` 采纳时把 `result.externalJob` 持久化到 `tool_call.external_job`（仅 `n==1` 的已采纳事实，迟到投递不覆盖）；`view()` 显式投影 `externalJob`，无值时为 JSON `null` 节点（`TraceStore.json(null)` 返回 `{}`，故单独构造） |
| `engine-runtime/src/main/java/cn/agentplatform/engine/infra/ExecutionRecovery.java` | +20 / −4（234 → 250） | `requestReconcile()` 解析对账回执 `result.externalJob` 并写 `tool_call.external_job`（等待态没有活租约，`/fact` 通道会 late，这是"未终结可见"的唯一刷新路径）；`adopt()` 采纳时写 `external_job` 并在同一 CAS 清 `run.error` |
| `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java` | +85 / −35（净 +50） | 抽出 `public void markWaitingExternal(rid,node,error)`；`externalWaitingError()` 读该节点最近一条 `tool_call.external_job` 并入错误结构（`externalJob` + 等待文案），写 node/attempt，且仅当 run 处于 `RUNNING/WAITING_EXTERNAL` 时写 `run.error`；`resumeExternalFacts()` 唤醒时 `run.error=null` |

新增：

| 文件 | 行数 | 说明 |
| --- | --- | --- |
| `skill-registry/src/main/resources/db/migration/V314__external_job_state.sql` | 18 | 只增列 + 索引，全部可空 |
| `engine-runtime/src/main/resources/db/migration/V220__run_error_structured.sql` | 9 | 只增列 `engine.tool_call.external_job jsonb`（`engine.run.error` 自 V200 已是 jsonb，故不新增 run 列） |
| `skill-registry/src/test/java/cn/agentplatform/skill/application/ExternalJobStateTest.java` | 386 | 8 例，独立库 `agent_platform_skill_external_job_test` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ExternalJobStateTest.java` | 367 | 6 例，复用 `TracePostgresTest` 夹具 |

### 2.2 不变量与恢复窗口

1. **PG 是唯一权威**：`externalJob` 的每个字段都来自 `skill.external_job_binding`，envelope / execution_fact / tool_call 都只是投影；没有任何内存 Map 或请求参数推断。
2. **提交必须可证明**：远端接受请求但未返回任务标识时，平台再也无法按原任务对账，这不是"结果未知"（UNKNOWN 会被无限轮询且永不收敛），而是一次可证明的提交失败 → 事实 `FAILED` + 稳定码 `SUBMIT_NO_TASK_ID`，绑定行 `status='FAILED'` 且 `external_job_id` 保持 NULL（V307 允许空）。
3. **终态映射**：远端成功 → `DONE`/`SUCCEEDED`（事实 SUCCEEDED）；远端 `failed` → `FAILED` + `REMOTE_FAILED` + 有界远端原文（≤512）；超时/未知 → `RESULT_UNKNOWN`，但绑定保留 `external_job_id`、`external_state`、`last_polled_at`。
4. **轮询幂等**：同一 callId 的重复轮询只 `update` 活性列（`last_polled_at`/`external_state`），不新增绑定行；`external_job_id` 写入受 `external_job_id is null` + `request_snapshot` + 唯一索引 `http_async_external_job` 三重约束；测试 #4 断言绑定行数恒为 1、`last_polled_at` 推进而 `submitted_at` 不变。
5. **终态不可回退（脏数据 fence）**：活性刷新只作用于 `status in ('SENDING','SUBMITTED')` 的绑定行；事实刷新带 `status='UNKNOWN'`；已 `DONE/FAILED` 的绑定不会被迟到的 RUNNING 覆盖（测试 #6：binding=DONE、fact=SUCCEEDED 后远端又回 RUNNING，轮询返回原终态，binding `external_state` 保持 SUCCEEDED、`last_polled_at` 不再推进）。
6. **run.error 的结构化只发生在等待窗口**：仅 `RESULT_UNKNOWN` 故障分支、且该节点最近一条 `tool_call.external_job` 非空时，才把快照并入错误（node/attempt/run 三处一致）。写 `run` 有状态围栏 `status in ('RUNNING','WAITING_EXTERNAL')`，终态运行绝不回写。
7. **恢复窗口闭环**：`ExecutionRecovery.adopt()` 采纳终态时把 `run.status='RUNNING'`、`error=null` 置于同一 CAS（`where status='WAITING_EXTERNAL'`），`tool_call` 更新同时写 `external_job`；`RunService.resumeExternalFacts()` 的回调唤醒同样 `error=null`。等待期间不刷新快照的唯一后果是界面显示旧轮询时刻，不影响采纳/超时判定。
8. **刷新通道不参与采纳判定**：`requestReconcile()` 拿到回执里的 `externalJob` 后只写可观测列，不写事实、不改状态；即使对账调用 409（已有事实）也不影响调度。
9. **GET 协议**：`externalJob` 恒存在；无外部任务时为 JSON `null`（不是缺字段、不是 `{}`），调用方不需要猜测。
10. **回滚开关**：`agent.skill.external-job-enabled=false` 时 envelope `externalJob=null`、`SUBMIT_NO_TASK_ID` 回退为 `UNKNOWN/RESULT_UNKNOWN`；引擎侧不再收到快照，`GET` 返回 null、`run.error` 保持旧结构。绑定行仍写审计列（对旧读取方无影响）。

## 3. 测试原文（红 → 绿）

### 3.1 Skill：`ExternalJobStateTest`（8 例，独立库）

先写测试后实现，首次运行（实现前）：

```
[ERROR] Tests run: 8, Failures: 3, Errors: 2, Skipped: 0, Time elapsed: 7.700 s <<< FAILURE! -- in cn.agentplatform.skill.application.ExternalJobStateTest
[ERROR]   ExternalJobStateTest.remoteFailureCarriesRemoteCodeAndMessageIntoFact:282 expected: <remote checksum mismatch: expected 9f2 got 1a3> but was: <外部任务终态失败：remote checksum mismatch: expected 9f2 got 1a3>
[ERROR]   ExternalJobStateTest.runningRemoteJobStaysUnknownButVisible:301 expected: <job-1> but was: <>
[ERROR]   ExternalJobStateTest.terminalFactIsNotReopenedByLatePoll:360 expected: <SUCCEEDED> but was: <UNKNOWN>
[ERROR]   ExternalJobStateTest.disabledFlagKeepsLegacyUnknownBehavior:335 NullPointer Cannot assign field "externalJobEnabled" because "this.executor" is null
[ERROR]   ExternalJobStateTest.disabledFlagOmitsExternalJobFromRunningFact:346 NullPointer Cannot assign field "externalJobEnabled" because "this.executor" is null
[ERROR] Tests run: 8, Failures: 3, Errors: 2, Skipped: 0
```

迭代中还跑过单方法定位（`Tests run: 2, Failures: 1`、`Tests run: 5, Failures: 3`），最终：

```
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 7.996 s -- in cn.agentplatform.skill.application.ExternalJobStateTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

用例与卡片测试项对应：

| # | 用例 | 覆盖 |
| --- | --- | --- |
| 1 | `missingRemoteTaskIdFailsSubmissionInsteadOfUnknown` | 远端 202 不回 taskId → `SUBMIT_NO_TASK_ID`、fact FAILED、binding FAILED 且 external_job_id 为 NULL |
| 2 | `remoteFailureCarriesRemoteCodeAndMessageIntoFact` | 远端 `failed` → envelope `externalJob.state=FAILED`、`remoteCode=REMOTE_FAILED`、`remoteMessage` 含原文；fact=FAILED；绑定行 `status/external_state/remote_error_code/remote_error_message` 全量落库（引擎据 fact 把 run 落 FAILED） |
| 3 | `runningRemoteJobStaysUnknownButVisible` | 远端持续 `RUNNING` → fact 仍 UNKNOWN，`externalJob.remoteJobId`、`lastPolledAt`、`submittedAt` 可见 |
| 4 | `repeatedPollIsIdempotent` | 同一 callId 轮询两次：业务提交=1 次、binding/fact 行数各=1、`external_job_id` 稳定为 job-1 |
| 5 | `disabledFlagKeepsLegacyUnknownBehavior`、`disabledFlagOmitsExternalJobFromRunningFact` | 开关关闭 → `SUBMIT_NO_TASK_ID` 回退 UNKNOWN/RESULT_UNKNOWN、envelope externalJob=null（回归） |
| 6 | `terminalFactIsNotReopenedByLatePoll` | 脏数据：binding 已 `DONE`、fact 已 `SUCCEEDED` 后远端又回 `RUNNING`，轮询返回原终态 SUCCEEDED，binding `external_state` 保持 `SUCCEEDED`，不新增提交 |
| 附 | `remoteFailureMessageIsBounded` | 远端 4KB 错误 → 绑定 `remote_error_message` ≤512（带省略号），`error.reason` 保留可识别前缀且 ≤640 |

### 3.2 Engine：`ExternalJobStateTest`（6 例）

先写测试、实现尚未补齐时的首次运行：

```
[ERROR] Tests run: 6, Failures: 3, Errors: 0, Skipped: 0, Time elapsed: 5.562 s <<< FAILURE! -- in cn.agentplatform.engine.infra.ExternalJobStateTest
[ERROR]   ExternalJobStateTest.toolCallViewExposesRemoteJobIdStateAndPollTimes:125 expected: <job-77> but was: <>
[ERROR]   ExternalJobStateTest.toolCallWithoutExternalJobIsExplicitNull:157 expected: <true> but was: <false>
[ERROR]   ExternalJobStateTest.waitingErrorCarriesSnapshotIntoRunNodeAndAttempt:184 等待态不得伪装成失败原因 ==> expected: <外部任务未终结，等待远端终态> but was: <技能执行结果未知，等待对账>
[ERROR] Tests run: 6, Failures: 3, Errors: 0, Skipped: 0
```

随后临时把 4 处可观测写入（`ToolCalls` 落库/投影、`RunService` 快照合并、`ExecutionRecovery` 刷新与清理）逐一禁用，证明 6 例全部真正依赖实现（不是恒真断言；控制台中文按 GBK 显示为乱码，下面是源码断言原文）：

```
[ERROR] Tests run: 6, Failures: 6, Errors: 0, Skipped: 0, Time elapsed: 4.489 s <<< FAILURE! -- in cn.agentplatform.engine.infra.ExternalJobStateTest
[ERROR]   ExternalJobStateTest.adoptionClearsWaitingSnapshotAndProjectsRemoteResult:226->submitted:86 事实里的 externalJob 必须落库 ==> expected: not <null>
[ERROR]   ExternalJobStateTest.reconcileReplyRefreshesSnapshotWithoutLiveLease:284->submitted:86 事实里的 externalJob 必须落库 ==> expected: not <null>
[ERROR]   ExternalJobStateTest.resumeExternalFactsClearsWaitingSnapshot:337->submitted:86 事实里的 externalJob 必须落库 ==> expected: not <null>
[ERROR]   ExternalJobStateTest.toolCallViewExposesRemoteJobIdStateAndPollTimes:132->submitted:86 事实里的 externalJob 必须落库 ==> expected: not <null>
[ERROR]   ExternalJobStateTest.toolCallWithoutExternalJobIsExplicitNull:168 expected: <true> but was: <false>
[ERROR]   ExternalJobStateTest.waitingErrorCarriesSnapshotIntoRunNodeAndAttempt:173->submitted:86 事实里的 externalJob 必须落库 ==> expected: not <null>
[ERROR] Tests run: 6, Failures: 6, Errors: 0, Skipped: 0
```

恢复实现后：

```
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 6.930 s -- in cn.agentplatform.engine.infra.ExternalJobStateTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

| # | 用例 | 覆盖 |
| --- | --- | --- |
| 1 | `toolCallViewExposesRemoteJobIdStateAndPollTimes` | `/fact` 采纳后 `GET` 返回 `externalJob{remoteJobId,state,submittedAt,lastPolledAt,remoteCode,remoteMessage}` |
| 2 | `toolCallWithoutExternalJobIsExplicitNull` | 同步调用 / 无外部任务 → 字段存在且为 null |
| 3 | `waitingErrorCarriesSnapshotIntoRunNodeAndAttempt` | RESULT_UNKNOWN + 快照 → run.error / node.error / attempt.error 都带 `externalJob`，run 状态保持 WAITING_EXTERNAL |
| 4 | `adoptionClearsWaitingSnapshotAndProjectsRemoteResult` | 采纳终态后 run.error=null、tool_call 投影远端结果、`external_job` 保留 |
| 5 | `reconcileReplyRefreshesSnapshotWithoutLiveLease` | 无活租约时对账回执刷新 `tool_call.external_job`（lastPolledAt 推进） |
| 6 | `resumeExternalFactsClearsWaitingSnapshot` | 回调唤醒（成功事实）后 run.error=null，run 回到 RUNNING |

### 3.3 回归与既存失败（如实区分）

- `ExecutionRecoveryTest`（定向回归，4 例）：`Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`。
- `AsyncHttpExecutionTest`（**既存失败，与本次改动无关**）：先在同一工作树把 5 个改动文件还原到 HEAD 跑基线，再跑改动版，两次结果完全一致：

| 版本 | 结果 |
| --- | --- |
| HEAD `dde22f4`（无本次改动） | `Tests run: 29, Failures: 4, Errors: 5, Skipped: 0` |
| 本次改动 | `Tests run: 29, Failures: 4, Errors: 5, Skipped: 0`（失败用例名逐一相同） |

失败名（两版相同）：`largeAsyncResultUsesControlledArtifactInsteadOfProtocolBuffer:471`、`oldSubmittedAckCannotDispatchNewRegisteredAttempt:633`、`outboxCannotDeliverRegistrationBeforeSubmission:790`、`unknownAuthorityFencesLateSubmittedAcknowledgement:1006`（Failures）；`actualUnknownOutboxUsesPostgresSkipLocked:857`、`cancelledPreparationCannotDeliverQueryOrResubmit:974`、`completedFactIsQueuedAndLateDeliveryNeverRevivesCancelledAuthority:894`、`interruptedBeforeSubmittedRecordsNotSentOnlyAfterAuthorityBecomesUnknown:837`、`sentPreparationCrashImageRecoversByOriginalKeyUnderExpiredLease:926`（Errors）。

> 卡片写的是"`AsyncHttpExecutionTest` 当前 35 例 3F/5E"，与本 checkout 实测不符：本基线是 **29 例 4F/5E**，且失败根因同样集中在租约/outbox/回调时序，不是 `ServiceTokens` 403。本次改动没有新增任何失败；也没有让这 4F/5E 变绿。

## 4. 迁移内容

### V314（skill，最新为 V313）

```sql
ALTER TABLE skill.external_job_binding
    ADD COLUMN IF NOT EXISTS external_state varchar(32),
    ADD COLUMN IF NOT EXISTS submitted_at timestamptz,
    ADD COLUMN IF NOT EXISTS last_polled_at timestamptz,
    ADD COLUMN IF NOT EXISTS remote_error_code varchar(64),
    ADD COLUMN IF NOT EXISTS remote_error_message varchar(512);

CREATE INDEX IF NOT EXISTS external_job_binding_open_idx
    ON skill.external_job_binding (tenant_id, status, last_polled_at);
```

- 只增列/加索引；全部可空；保留旧列与旧语义（V307 的 `external_job_id` 可空约束不动）。
- 不加唯一约束：把"远端无 id"变成插入失败会破坏提交失败的审计行。
- `IF NOT EXISTS` 使 Flyway 在已手工执行的库上也可重复运行。

### V220（engine，最新为 V219）

```sql
ALTER TABLE engine.tool_call ADD COLUMN IF NOT EXISTS external_job jsonb;
COMMENT ON COLUMN engine.tool_call.external_job IS
    'PLAT-04: latest verified external job snapshot {...}; NULL when the call has no external job';
```

- `engine.run.error` 自 V200 起就是 `jsonb`（`V200__engine.sql:9`），已确认存在，故**不新增 run 列**；结构化等待态直接写原列。
- 只增列，可空，保留旧列；旧行/无外部任务的调用保持合法。

## 5. 回滚步骤

1. **软回滚（首选，无需迁移）**：配置 `agent.skill.external-job-enabled=false`（或环境变量 `AGENT_SKILL_EXTERNAL_JOB_ENABLED=false`）并重启 skill-registry。
   - envelope `externalJob=null`；`SUBMIT_NO_TASK_ID` 回退为 `UNKNOWN/RESULT_UNKNOWN`；引擎收不到快照，`GET` 返回 null、`run.error` 保持旧结构。
   - 绑定行仍写审计列，旧读取方忽略即可；无需回滚迁移。
2. **代码回滚**：回退上述 5 个文件 + 删除 2 个测试类（V314/V220 可先保留，列可空且无读取方）。
3. **数据库回滚（可选，确认无读取方后执行）**：
   ```sql
   DROP INDEX IF EXISTS skill.external_job_binding_open_idx;
   ALTER TABLE skill.external_job_binding
       DROP COLUMN IF EXISTS remote_error_message,
       DROP COLUMN IF EXISTS remote_error_code,
       DROP COLUMN IF EXISTS last_polled_at,
       DROP COLUMN IF EXISTS submitted_at,
       DROP COLUMN IF EXISTS external_state;
   ALTER TABLE engine.tool_call DROP COLUMN IF EXISTS external_job;
   ```
   删除列不可逆，建议先 `pg_dump -t skill.external_job_binding -t engine.tool_call` 留档；Flyway 历史表需同步清理对应版本行（由运维按发布流程处理）。
4. **顺序**：先关开关 → 观察一个调度周期 → 再回退代码 → 最后（可选）删列。任何一步都不改变已落库的执行事实。

## 6. 未覆盖风险与限制

1. **未做真实第三方联调**：测试用 JDK `HttpServer` + 桩，覆盖 `jobIdPath/donePath/doneValue/failedValue/failureMessagePath/resultEndpoint` 的常规形态；对非标准响应（数组 jobId、嵌套错误结构、分页结果）未验证。
2. **`lastPolledAt` 的时效依赖调度**：等待态刷新由引擎 `ExecutionRecovery`（3s）驱动；停服/积压期间不会推进，界面可能显示陈旧轮询时刻（不改变终态判定）。
3. **`run.error` 覆盖范围有限**：只有"技能 RESULT_UNKNOWN 且存在外部任务快照"才结构化；模型/provider 级 `RESULT_UNKNOWN` 与本卡范围外，不写 `externalJob`。
4. **`GET` 是快照不是实时查询**：远端在两次轮询之间的状态变化最多延迟一个调度周期；调用方不应把它当作实时远端 API。
5. **`run.error` 在 RUNNING 期即写入**：读取方必须结合 `run.status` 判断（WAITING_EXTERNAL 的 error 是"等待快照"而非失败）；若现有前端按"error 非空=失败"渲染，会误报，需后续卡片在展示层处理。
6. **未实现前端/网关展示**：本卡只保证 API 字段与引擎记录；"已提交/处理中/远端失败"的 UI 文案属后续卡片。
7. **未跑模块全量测试**（按卡片要求只跑定向类）；`AsyncHttpExecutionTest` 的 4F/5E 为既存问题，需单独排期。
8. **卡片路径偏差**：卡片写 `engine-runtime/.../api/ToolCalls.java`，本 checkout 中该类实际位于 `engine-runtime/src/main/java/cn/agentplatform/engine/infra/ToolCalls.java`（`api/` 下只有 `ToolCallController.java`）。为满足行为交付，本次只改 `infra/ToolCalls.java`，未新建/移动类；白名单意图（只动该组件）保持不变。
9. **`engine.tool_call.external_job` 无历史回填**：升级前已存在的异步调用不会补快照，`GET` 返回 null，直到下一次对账回执或事实投递刷新。

## 7. 工作区快照

```
$ git status --porcelain
 M engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java
 M engine-runtime/src/main/java/cn/agentplatform/engine/infra/ExecutionRecovery.java
 M engine-runtime/src/main/java/cn/agentplatform/engine/infra/ToolCalls.java
 M skill-registry/src/main/java/cn/agentplatform/skill/application/SkillExecution.java
 M skill-registry/src/main/java/cn/agentplatform/skill/infra/HttpAsyncAdapter.java
?? app-management/src/main/resources/db/migration/V105__publish_gate.sql
?? docs/evidence/2026-10-27-PLAT-04-external-job/
?? docs/evidence/acceptance-audit-20261026/
?? docs/evidence/batch-1-usability-20261026.md
?? engine-runtime/src/main/resources/db/migration/V220__run_error_structured.sql
?? engine-runtime/src/test/java/cn/agentplatform/engine/infra/ExternalJobStateTest.java
?? output/
?? skill-registry/src/main/resources/db/migration/V314__external_job_state.sql
?? skill-registry/src/test/java/cn/agentplatform/skill/application/ExternalJobStateTest.java
```

- 白名单外零改动：`docs/evidence/acceptance-audit-20261026/`、`docs/evidence/batch-1-usability-20261026.md`、`output/` 为进入本卡前已存在的未跟踪文件；`app-management/.../V105__publish_gate.sql` 是本次会话期间由其他泳道出现的新文件，本卡未创建、未修改、未暂存 app-management 下任何文件。
- 临时对照脚本（基线/红灯验证用）已删除，未留在工作树。
- 未执行 `git add` / `git commit` / `git stash` / `git checkout`。

## 8. 复现命令

```bat
cd G:\207\项目\算法重组工具\代码\agent-platform
set AGENT_DB_PASSWORD=<取自 .env>
set ENGINE_PG_TEST=true
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot
mvn -B -ntp -pl skill-registry test -Dtest=ExternalJobStateTest "-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp" -Dmaven.repo.local=.tools/m2
mvn -B -ntp -pl engine-runtime test -Dtest=ExternalJobStateTest "-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp" -Dmaven.repo.local=.tools/m2
mvn -B -ntp -pl engine-runtime test -Dtest=ExecutionRecoveryTest "-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp" -Dmaven.repo.local=.tools/m2
```

新测试库 `agent_platform_skill_external_job_test` 由测试自身在缺失时创建（与既有异步测试同一模式），无需手工建库。
