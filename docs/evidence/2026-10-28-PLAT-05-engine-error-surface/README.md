# PLAT-05 引擎侧半段（E-0）：错误结构在运行链路统一

## 1. 范围与依据

- 任务：派发单元 E-0 —— PLAT-05 的引擎侧半段：技能 envelope 的统一错误对象在引擎运行链路（run 输出 / SSE 事件 / 管理台运行详情 / 模型工具结果）保持同构，不再中途降级成 `{semantic,reason}`。
- 依据：
  - `contracts/skill-invocation.md` §11（工程增补：七字段统一错误对象、errorSemantics、任务结果端点）——只读，未改 contracts/**；
  - 方案 §4.5（PLAT-05，507–553 行）与 §4.16（PLAT-16 拆入落地）：`engine` 的 run 输出、SSE 事件、管理台运行详情使用同一 error 结构；模型工具结果带 `userMessage`；403 是可解释拒绝（category=AUTH），模型不重复重试不可重试错误；
  - 计划 §6 规范（错误信封、不吞异常换状态码、先写失败路径测试）。
- 技能侧基线：`bdc663f`（PLAT-05 skill 半段已提交），本任务开工时工作树干净（仅既有未跟踪目录）。
- 改动文件（全部在允许白名单内）：
  - `engine-runtime/src/main/java/cn/agentplatform/engine/domain/RuntimeFault.java` —— 新增可选结构化错误对象（七字段 + reason 原样携带），`error()` 只追加引擎元数据（path / message 兜底），旧构造四键形态不变；
  - `engine-runtime/src/main/java/cn/agentplatform/engine/application/AgentDriver.java` —— 新增纯函数 `envelopeError(...)`（解析 envelope 的统一错误对象，含旧 `{semantic,reason}` / `{semantic,message}` 形态与顶层 `errorSemantic`/`error_semantic` 兼容键回退）；`skill()` 三处抛出点（登记即 FAILED、UNKNOWN、执行失败）从"丢弃成裸语义字符串的 IllegalArgumentException"改为携带结构化错误的 RuntimeFault；`drive()` 对可解释拒绝（retryable=false 且带 userMessage）与 PARAM_ERROR 一样以完整错误对象作为工具结果交还模型（上限沿用 corrections<2，NO_PROGRESS 指纹熔断不变）；WAITING_APPROVAL / RESULT_UNKNOWN 照旧向上抛给状态机；
  - `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java` —— 只读重试判据增加 `!explicitNonRetryable(error)`（显式 `retryable=false` 不得自动重试；键缺省保持既有 semantic 推断）；`normalizedError` 透传 `userMessage/traceId/category/detail/errorCode` 并按显式 `retryable` 呈现（旧形态无键不发明字段）；`effectiveError`/`normalizedError` 由包私有放宽为 public（纯函数测试入口，跨包验收用，无行为变化）。
- 新增测试：
  - `engine-runtime/src/test/java/cn/agentplatform/engine/infra/EngineErrorSurfacePostgresTest.java`（PG）：①七字段错误在节点错误 / attempt 错误 / NODE_FAILED 事件（SSE 负载）/ effectiveError→normalizedError 四处同构且含 traceId；②显式 `retryable=false` 阻断只读重试（工具调用保持 FAILED、readonly_retry_count=0）；③AGENT 循环中可解释拒绝以工具结果交还模型（含 userMessage / 远端 code / traceId），运行收口成解释性回答；④旧 `{semantic,reason}` 事实向后兼容（semantic 不降级成 RUNTIME_ERROR、reason 原样保留）；
  - `engine-runtime/src/test/java/cn/agentplatform/engine/application/SkillErrorSurfaceTest.java`（纯函数）：运行详情透传与旧形态不发明字段（红批先写）；envelopeError 解析契约与 RuntimeFault 结构化合并契约（随实现补充，行为红由 PG 用例覆盖）。

## 2. 运行环境

- JDK：Eclipse Adoptium jdk-21.0.12.8-hotspot（`JAVA_HOME` 导出，见命令）。
- 数据库：真实 PG 测试库 `agent_platform_engine_test`（jdbc:postgresql://127.0.0.1:25432/agent_platform_engine_test，PostgreSQL 16.15），经 `.tools/env-load.sh` 载入 `.env`（剥 CRLF）并导出 `ENGINE_PG_TEST=true`；Flyway schema engine 当前版本 222（V220 结构化 run.error 为既有基线，本任务未新增迁移）。
- 模块：仅 `engine-runtime`（`-pl engine-runtime`，依赖走本地仓 jar）；未跑模块全量。
- 协同泳道：admin-common/app-management 有另一泳道在改（Actor.java），本任务未触碰其任何文件；构建期间 `git status` 均只含本任务白名单文件。

## 3. 执行的命令原文

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
source .tools/env-load.sh
# 红批（新测试类，先于实现）
mvn -B -pl engine-runtime test -Dtest='SkillErrorSurfaceTest,EngineErrorSurfacePostgresTest' "-DargLine=-Xmx1024m" \
  > docs/evidence/2026-10-28-PLAT-05-engine-error-surface/red-run1.txt 2>&1
# 绿批 1：新测试类
mvn -B -pl engine-runtime test -Dtest='SkillErrorSurfaceTest,EngineErrorSurfacePostgresTest' "-DargLine=-Xmx1024m" \
  > docs/evidence/2026-10-28-PLAT-05-engine-error-surface/green-run1-newclasses.txt 2>&1   # 首跑剩 3 个类型断言失败
mvn -B -pl engine-runtime test -Dtest='SkillErrorSurfaceTest,EngineErrorSurfacePostgresTest' "-DargLine=-Xmx1024m" \
  > docs/evidence/2026-10-28-PLAT-05-engine-error-surface/green-run2-newclasses.txt 2>&1   # 全绿
# 绿批 2：多类同跑验收（新测试 + 既有 ToolCalls/SSE/运行详情 + PLAT-04 externalJob/ExecutionRecovery + Agent 循环/重放/替身/拦截器/取消）
mvn -B -pl engine-runtime test -Dtest='SkillErrorSurfaceTest,EngineErrorSurfacePostgresTest,RunDiagnosticsTest,ExecutionRecoveryTest,ExternalJobStateTest,AgentLoopBudgetPostgresTest,TracePostgresTest,ControlledDraftSkillTest,DebugControlsTest,ControlledReplayTest,ToolCancellationRaceTest,ToolInterceptorChainTest,RunEventsPostgresTest' "-DargLine=-Xmx1024m" \
  > docs/evidence/2026-10-28-PLAT-05-engine-error-surface/green-run3-regression.txt 2>&1
# 绿批 3：追加 errorPolicy/RETRY 与运行操作、孤儿收敛
mvn -B -pl engine-runtime test -Dtest='RunOperationsTest,OutputPolicyPostgresTest,RunOrphanConvergencePostgresTest' "-DargLine=-Xmx1024m" \
  > docs/evidence/2026-10-28-PLAT-05-engine-error-surface/green-run4-extras.txt 2>&1
# 绿批 4：SSE HTTP
mvn -B -pl engine-runtime test -Dtest='RunEventsHttpTest' "-DargLine=-Xmx1024m" \
  > docs/evidence/2026-10-28-PLAT-05-engine-error-surface/green-run5-sse-http.txt 2>&1
# 绿批 5：HEAD 前进复核（他泳道提交 c94f2f1 后，在 c94f2f1 工作树上复跑核心集）
mvn -B -pl engine-runtime test -Dtest='SkillErrorSurfaceTest,EngineErrorSurfacePostgresTest,ExecutionRecoveryTest,ExternalJobStateTest,AgentLoopBudgetPostgresTest' "-DargLine=-Xmx1024m" \
  > docs/evidence/2026-10-28-PLAT-05-engine-error-surface/green-run6-head-recheck.txt 2>&1
```

说明：首次红批因测试代码自身编译错误（缺 import、跨包访问包私有静态）未到断言；补上 import 并将 `RunService.effectiveError/normalizedError` 放宽为 public（仅可见性，无行为变化）后重跑，得到的行为红见下。本任务未运行 `node scripts/verify-contracts.mjs`（未改 contracts/**，freeze.json 无 diff）。

## 4. 结果原文（Tests run）

红批（red-run1.txt，实现落地前）：

```
[ERROR] Failures:
[ERROR]   SkillErrorSurfaceTest.normalizedErrorExplicitRetryableTrueIsHonoredOverSemanticInference:65 expected: "调用频次超限，请稍后重试" but was: null
[ERROR]   SkillErrorSurfaceTest.normalizedErrorSurfacesUnifiedEnvelopeFields:38 expected: "外部任务失败：TLE 校验和错误" but was: null
[ERROR]   EngineErrorSurfacePostgresTest.explicitRetryableFalseBlocksReadonlyRetry:253 expected: <FAILED> but was: <REGISTERED>
[ERROR]   EngineErrorSurfacePostgresTest.legacySemanticReasonFactKeepsSemanticAndReasonWithoutDegradation:309 expected: <NO_PERMISSION> but was: <RUNTIME_ERROR>
[ERROR]   EngineErrorSurfacePostgresTest.sevenFieldErrorIsIdenticalAcrossNodeAttemptEventAndRunDetail:195->assertSevenFields:168 expected: <REMOTE_FAILED> but was: <RUNTIME_ERROR>
[ERROR] Errors:
[ERROR]   EngineErrorSurfacePostgresTest.modelToolResultCarriesUserMessageForExplainableRejection:360 ? IllegalArgument SKILL_FAILED
[ERROR] Tests run: 8, Failures: 5, Errors: 1, Skipped: 0
```

（同批 2 个旧形态 pin 用例通过，属既有行为回归钉，预期内。）

绿批 1（green-run2-newclasses.txt，新测试类）：

```
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.187 s -- in cn.agentplatform.engine.application.SkillErrorSurfaceTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 5.834 s -- in cn.agentplatform.engine.infra.EngineErrorSurfacePostgresTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

绿批 2（green-run3-regression.txt，多类同跑，1:14 min）：

```
[INFO] Tests run: 7,  Failures: 0, Errors: 0, Skipped: 0 -- cn.agentplatform.engine.application.RunDiagnosticsTest
[INFO] Tests run: 9,  Failures: 0, Errors: 0, Skipped: 0 -- cn.agentplatform.engine.application.SkillErrorSurfaceTest
[INFO] Tests run: 3,  Failures: 0, Errors: 0, Skipped: 0 -- cn.agentplatform.engine.infra.AgentLoopBudgetPostgresTest
[INFO] Tests run: 5,  Failures: 0, Errors: 0, Skipped: 0 -- cn.agentplatform.engine.infra.ControlledDraftSkillTest
[INFO] Tests run: 2,  Failures: 0, Errors: 0, Skipped: 0 -- cn.agentplatform.engine.infra.ControlledReplayTest
[INFO] Tests run: 4,  Failures: 0, Errors: 0, Skipped: 0 -- cn.agentplatform.engine.infra.DebugControlsTest
[INFO] Tests run: 4,  Failures: 0, Errors: 0, Skipped: 0 -- cn.agentplatform.engine.infra.EngineErrorSurfacePostgresTest
[INFO] Tests run: 4,  Failures: 0, Errors: 0, Skipped: 0 -- cn.agentplatform.engine.infra.ExecutionRecoveryTest        （PLAT-04）
[INFO] Tests run: 6,  Failures: 0, Errors: 0, Skipped: 0 -- cn.agentplatform.engine.infra.ExternalJobStateTest         （PLAT-04）
[INFO] Tests run: 9,  Failures: 0, Errors: 0, Skipped: 0 -- cn.agentplatform.engine.infra.RunEventsPostgresTest        （SSE）
[INFO] Tests run: 2,  Failures: 0, Errors: 0, Skipped: 0 -- cn.agentplatform.engine.infra.ToolCancellationRaceTest
[INFO] Tests run: 1,  Failures: 0, Errors: 0, Skipped: 0 -- cn.agentplatform.engine.infra.ToolInterceptorChainTest
[INFO] Tests run: 24, Failures: 0, Errors: 0, Skipped: 0 -- cn.agentplatform.engine.infra.TracePostgresTest            （tool 生命周期/fact）
[INFO] Tests run: 80, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

绿批 3（green-run4-extras.txt）：

```
[INFO] Tests run: 1,  Failures: 0, Errors: 0, Skipped: 0 -- cn.agentplatform.engine.application.RunOperationsTest
[INFO] Tests run: 24, Failures: 0, Errors: 0, Skipped: 0 -- cn.agentplatform.engine.infra.OutputPolicyPostgresTest
[INFO] Tests run: 4,  Failures: 0, Errors: 0, Skipped: 0 -- cn.agentplatform.engine.infra.RunOrphanConvergencePostgresTest
[INFO] Tests run: 29, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

绿批 4（green-run5-sse-http.txt）：

```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in cn.agentplatform.engine.infra.RunEventsHttpTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

绿批 5（green-run6-head-recheck.txt，HEAD 已由他泳道推进至 c94f2f1 后复跑核心集）：

```
[INFO] Tests run: 26, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

回归对照：定向集合合计 80 + 29 + 1 = 110 例，零新增失败；HEAD 前进后核心集复跑 26 例仍零失败。PLAT-04（ExternalJobStateTest / ExecutionRecoveryTest）、外部任务快照、WAITING_EXTERNAL 清理、markWaitingExternal CAS 相关用例全部原样通过。

## 5. 未覆盖与已知失败

- 未跑 engine-runtime 模块全量（按派发纪律"定向集合控制在 15 分钟内"）；定向集合已覆盖派发点名的 ToolCalls/SSE/运行详情/PLAT-04 externalJob/ExecutionRecovery，另加 Agent 循环、受控重放、调试替身、拦截器、取消竞态、errorPolicy 重试、孤儿收敛。未覆盖类中与本次改动理论上相关的仅剩 `RuntimeStreamTest`（派发点名不碰的挂起类）与 portal 事件系列（未改动 portal 组装路径）。
- 单类隔离假红说明：本次全部以多类同跑取数，未出现 `ServiceTokens.deploymentMode` 类单类隔离假红；PG 用例在无 `ENGINE_PG_TEST=true` 时会静默跳过，复现时必须先 `source .tools/env-load.sh`。
- 已知语义边界（有意限制，非缺陷）：
  - 可解释拒绝吸收进模型工具结果仅发生在 AGENT/LLM 驱动循环（`AgentDriver.drive`）；FLOW 的 SKILL/RETRIEVE 节点失败仍按既有语义使节点失败，完整错误对象进节点/run.error（路线一异步 TLE 场景即走此路径，原因可读性由运行详情透传保证）；
  - 旧形态（无 `userMessage`、无 `retryable` 键）不吸收进模型循环、不阻断只读重试、详情渲染不发明新字段——向后兼容窗口内行为与改造前一致；
  - `engine.tool_call.result` 自始至终原样保存技能 envelope（含七字段错误对象），fact 路径本次无需改动。
- 派发偏差：派发单写的是 `engine/api/ToolCalls.java` 约 238 行"解析技能 envelope 错误处"；实际代码中 envelope 错误解析点在 `engine/application/AgentDriver.java`（`skill()` 两处抛出点 + `drive()` 工具反馈），`ToolCalls.fact()` 本就原样落库 envelope，`claim()` 238 行是租约到期的 RESULT_UNKNOWN CAS（与解析无关）。已按实际代码位置实现，未改 ToolCalls.java。
- 基线漂移说明：任务执行期间 HEAD 由 `bdc663f` 被另一泳道（PLAT-12 T12a，commit c94f2f1，03:17:16）按单工作树顺序提交模型推进；该提交仅涉及 admin-common（Actor 加法式快照方法）与 app-management，未触碰本任务任何白名单文件与测试集（本任务三个主文件基线仍为 a31bc95），HEAD 前进后核心集复跑零失败（绿批 5）。工作树中未发现他人未提交改动。
- 实现顺序说明：`RunService.effectiveError/normalizedError` 的 public 放宽（仅可见性）在红批前落地，作为测试编译使能；四类行为红均为断言/异常级红，见第 4 节。

## 6. 回滚方式

- 全部改动位于 engine-runtime 三个主文件 + 两个新增测试类 + 本证据目录，未提交（工作树改动，禁止 git add/commit/push）。
- 单文件回滚：
  - `git checkout -- engine-runtime/src/main/java/cn/agentplatform/engine/application/AgentDriver.java engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java engine-runtime/src/main/java/cn/agentplatform/engine/domain/RuntimeFault.java`
  - 删除新增测试：`rm engine-runtime/src/test/java/cn/agentplatform/engine/application/SkillErrorSurfaceTest.java engine-runtime/src/test/java/cn/agentplatform/engine/infra/EngineErrorSurfacePostgresTest.java`
- 回滚后无残留：本任务无迁移、无配置开关、无依赖变更；`engine.tool_call.result` 中的七字段错误对象由技能侧 bdc663f 写入，回滚引擎侧解析不影响存储内容，仅恢复旧的降级展示。
- 数据兼容：错误对象向后兼容键（reason）与新字段均可空，回滚后旧数据与运行链路不受影响。
