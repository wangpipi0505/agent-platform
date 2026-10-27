# PLAT-15 / T15（写操作准备/执行两段与人工确认）证据 — 2026-10-28

分支 `feat/v1.2-implementation`，开工基线 HEAD `d836f69`（独占批次，本仓唯一实现泳道）。
方案依据：平台侧优化方案 §4.15（945–986 行，六个实现项）；契约增补 §14（`contracts/skill-invocation.md`，freeze.json 同步更新 sha256 与 engineeringAddendum，`node scripts/verify-contracts.mjs` 通过）。
台账验收锚点：T15.1 actionPlan 生成 + PREPARE/EXECUTE 两段（越权自审批被拦）；T15.2 幂等键 + 唯一约束 engine V221（重复提交返回首次结果）。

## 0. 定死口径清单（实现期决定，逐条偏差单列见 §6）

| # | 口径 | 决定 | 依据 |
| --- | --- | --- | --- |
| D1 | 兼容策略 | `execution.approval` 键**缺省 = `SINGLE_STEP`**：存量契约不落键，执行流程/信封形状零变化；新契约显式声明 `mode=PREPARE_EXECUTE` 才走两段；显式 `SINGLE_STEP` 等价缺省（旧技能自述）。方案字面"默认 PREPARE_EXECUTE"不落地为"缺省即两段"，避免存量技能行为漂移 | 派发单建议 + 契约 §14.1 |
| D2 | PREPARE 调用形态 | **独立入口声明**：`execution.approval.prepare = {endpoint, httpMethod?}`，httpMethod 只允许 POST（缺省 POST），请求体=守卫后参数，出站带同一 `Idempotency-Key` 与鉴权/身份头（§11/§13 语义不变）。仅 HTTP **同步**技能支持；MCP/ALGORITHM/PROCESS/异步 HTTP 声明两段 → 注册期 400 `APPROVAL_PREPARE_UNSUPPORTED` | 契约 §14.1/§14.2 |
| D3 | actionPlan 与幂等键来源 | 技能预演入口只提供 `summary/target/diff`；`idempotencyKey`/`expiresAt` 由**引擎在 requestApproval 统一盖章**（技能侧提供的被覆盖）：`executeKey = sha256("EXECUTE:" + callId + ":" + 去键核心摘要)`，`digest = sha256(canonical(盖章后完整计划))`，`expiresAt = 审批窗口（申请+3600s，单一过期语义）`。幂等键来源唯一是引擎 | 契约 §14.3 |
| D4 | 高危判别与确认强度 | 只认契约声明 `execution.approval.risk=HIGH`（不从 URL/方法猜测）。HIGH 强度 = **confirmRoles ≥ 2 个不同角色 或 审批请求 `doubleConfirmed=true`（前端二次确认勾选）**；审批时（approve）与执行时（claim 复查）双重把关，任一不满足 → 403 `CONFIRM_STRENGTH_INSUFFICIENT` | 契约 §14.5 |

## 1. 改动文件清单（按模块分组，全部在白名单内）

**contracts（2 修改）**
- `contracts/skill-invocation.md`：新增 §14（声明与兼容口径 / PREPARE 段 / actionPlan 盖章 / EXECUTE 三重门 / 高危与审计 / 错误码登记表）。
- `contracts/freeze.json`：skill-invocation sha256 更新（`a41502f2…` → `e3abb233…`），engineeringAddendum 追加 PLAT-15 条目；snapshot 未动。

**engine-runtime（2 修改 + 1 新增迁移 + 1 新增测试）**
- `src/main/resources/db/migration/V221__action_plan_execute_key.sql`（新增）：`engine.action_plan` 表——`digest`（盖章后计划 canonical SHA-256）、`plan jsonb`、**`execute_key char(64) NOT NULL UNIQUE`**（V221 唯一约束，"同一份计划只有一条 EXECUTE 授权记录"的硬保证）、`risk/status/expires_at/confirmed_by/confirmed_at/double_confirmed/execute_granted_at/executed_status/result_digest`（runId/actionPlanId/confirmBy/confirmAt/result 审计链）。回滚 = DROP TABLE（§5）。
- `src/main/java/cn/agentplatform/engine/infra/ToolCalls.java`：
  - `requestApproval`：confirmation 携带 `actionPlan` 时调 `stampActionPlan`——引擎盖章（D3）+ `on conflict(execute_key) do update`（同内容复用同行并刷新盖章字段）+ 审批节点 approval_context 写入 `actionPlanId/actionPlanDigest/actionPlan/risk`；未知 risk 值 400 拒绝。
  - `claim`：批准检查后新增 `requireExecutablePlan` EXECUTE 门（契约 §14.4）——缺 executeKey → 409 `EXECUTE_KEY_REQUIRED`；键或 digest 与计划不一致 → 409 `ACTION_PLAN_DIGEST_MISMATCH`；计划过期 → 409 `ACTION_PLAN_EXPIRED`；HIGH 强度不足 → 403 `CONFIRM_STRENGTH_INSUFFICIENT`；通过才 `execute_granted_at=coalesce(...)`（首次授予才落，重复 EXECUTE 授权时间不变）。**任一拒绝发生在 claim CAS 之前，副作用不发生**。无 actionPlanId 的旧调用直接通过（零回归）。
  - `approval`（GET）：批准且绑定计划时下发 `actionPlan {id, idempotencyKey(=executeKey), digest, risk, expiresAt, summary, target}`——技能 EXECUTE 的唯一授权来源。
  - `fact`：被采纳的事实（n==1）回写计划行 `status=EXECUTED/EXECUTE_FAILED`、`executed_status`、`result_digest`（审计 §4.15 第5项）。
- `src/main/java/cn/agentplatform/engine/application/ApprovalService.java`：`approve` 在任何状态写入之前——计划与审批节点 digest 一致性校验（不一致 409）、HIGH 强度门（403，写入前拒绝=越权/弱确认零副作用）；批准/拒绝后计划行落 `confirmed_by/confirmed_at/double_confirmed`，`APPROVAL_DONE` 事件追加 `actionPlanId/risk`。
- `src/test/java/cn/agentplatform/engine/infra/ActionPlanPostgresTest.java`（新增，8 例，真 PG）。

**skill-registry（4 修改 + 1 新增测试 + 1 测试夹具增列）**
- `src/main/java/cn/agentplatform/skill/domain/SkillRules.java`：`execution.approval` 唯一解释入口——`approvalMode`（缺省 SINGLE_STEP，D1）/`approvalRisk`（声明式 HIGH，D4）/`prepareDeclaration`（D2 独立入口）。
- `src/main/java/cn/agentplatform/skill/application/SkillCatalog.java`：注册期 `normalizeApproval`——mode 枚举、HTTP 同步限定、sideEffect=true 限定、prepare.endpoint 必须为无明文凭据 HTTP(S)（`SkillRules.endpoint`）、httpMethod 只允许 POST、risk 枚举；空 approval 视为缺省不落键（归一化副本，不改调用方 Map）。
- `src/main/java/cn/agentplatform/skill/infra/SkillAdapters.java`：`prepare(...)`——以守卫后参数 POST 调用声明入口，复用同步 HTTP 通道（鉴权/身份头/大小硬闸同一套），适配器失败归一 `ACTION_PLAN_UNAVAILABLE`。
- `src/main/java/cn/agentplatform/skill/application/SkillExecution.java`：
  - 未批准 + 两段 → 调 `adapters.prepare` 组装 actionPlan（调试 Mock 运行跳过业务预演，占位摘要），随 WAITING_APPROVAL confirmation 上交引擎盖章；预演失败 → 502 `ACTION_PLAN_UNAVAILABLE` 可读错误（无副作用、不进 WAITING_APPROVAL）。
  - 批准 + 两段 → claim 请求携带 `executeKey`，业务出站 `Idempotency-Key` = 引擎盖章键；request_snapshot 记录 `businessIdempotencyKey`，对账（reconcile）按真实出站键查询（缺键回退逻辑调用键，存量零回归）。
  - SINGLE_STEP 忽略 approval.actionPlan（含存量）。
- `src/test/java/cn/agentplatform/skill/application/SkillPrepareExecuteTest.java`（新增，7 例，真 PG + 双 HttpFixture）。
- `src/test/java/cn/agentplatform/skill/infra/HttpFixture.java`：新增 `lastIdempotencyKey` 捕获（纯增量，不影响既有用例）。

**web（2 修改 + 1 新增组件 + 1 测试文件扩展；未动 router.ts/AppShell.vue/main.ts）**
- `src/pages/ActionPlanPanel.vue`（新增）：行动计划卡——summary/target/diff/risk（HIGH 红色警示 el-alert + el-tag）/执行授权过期/幂等键；`secondConfirmRequired` 时渲染二次确认勾选框并 emit `update:doubleConfirmed`。
- `src/pages/ApprovalsPage.vue`：确认中心表格新增展开列渲染 ActionPlanPanel（在既有审批面板上扩展，不新开路由）；批准改用 `approvePayload`（高危强度不足且未勾选二次确认 → 前端即拒，不提交）。
- `shared/approval.ts`：新增 `actionPlanView(row)`（从 `row.parameterSnapshot.actionPlan` 提取展示视图 + 二次确认判定）与 `approvePayload(row, approved, requestId, doubleConfirmed)`（沿用 `decisionPayload` 摘要绑定，高危时附加 `doubleConfirmed`）。
- `tests/approval.test.ts`：新增 3 例（视图提取/二次确认门/批准载荷 + SFC 挂载断言面板 setupState 暴露 summary/target/diff/risk）。

**docs/evidence**：本目录（README + red-engine.txt / red-skill.txt / red-web.txt / green-engine-directed.txt / green-skill-directed.txt / green-web-vitest-full.txt / green-web-vue-tsc.txt / green-web-build.txt）。

**admin-common**：未改动（新错误全部走既有 `PlatformException`/`ErrorSemantics` 信封，天然携带 code+userMessage+traceId）。

## 2. 红→绿输出摘要（分模块，全文见本目录文件）

**engine（`ActionPlanPostgresTest`，8 例，真 PG `agent_platform_engine_test`）**——红（red-engine.txt）：
```
[ERROR] Tests run: 8, Failures: 3, Errors: 5, Skipped: 0
  executeWithMismatchedKey…:277 expected: <ACTION_PLAN_DIGEST_MISMATCH> but was: <APPROVAL_REQUIRED>
  prepareApprovalPersistsStampedPlan…:183 expected: <1> but was: <0>          （无 action_plan 行）
  duplicateExecute…/expiredPlan…/unauthorizedConfirm… EmptyResultDataAccess    （engine.action_plan 不存在数据）
  highRiskRequires…:453 Expected PlatformException to be thrown               （无强度门）
[INFO] BUILD FAILURE
```
绿（green-engine-directed.txt，与既有审批/相邻回归同跑）：
```
[INFO] Tests run: 24 -- TracePostgresTest（含 writeApprovalIsBoundToStableCallArgumentsAcrossAttempts 既有锚点）
[INFO] Tests run:  3 -- EngineMigrationsTest（ENGINE_MIGRATIONS=[…,220,221,222,223] max=223，V221 已识别）
[INFO] Tests run:  4 -- EngineErrorSurfacePostgresTest
[INFO] Tests run:  8 -- ActionPlanPostgresTest
[INFO] Tests run: 11 -- RunEnvironmentAnnotationTest
[INFO] Tests run: 50, Failures: 0, Errors: 0, Skipped: 0    BUILD SUCCESS
```

**skill（`SkillPrepareExecuteTest`，7 例，真 PG）**——红（red-skill.txt）：
```
[ERROR] Tests run: 7, Failures: 6, Errors: 1, Skipped: 0
  prepareReturnsActionPlanWithoutBusinessSideEffect:276 两段技能的 WAITING_APPROVAL 必须携带 actionPlan
  approvedExecuteUsesStampedKey…:296 expected: <plan-key-123> but was: <>     （claim 未携带 executeKey）
  prepareFailureIsReadable…:308 Expected PlatformException to be thrown       （无 502 可读错误）
  registrationRejects…/registrationRejectsTwoPhase…：注册期无 approval 校验
[INFO] BUILD FAILURE
```
绿（green-skill-directed.txt，T05/T06/T21d/契约摘要回归同跑）：
```
[INFO] Tests run:  7 -- SkillPrepareExecuteTest
[INFO] Tests run:  6 -- SkillRulesTest / 6 -- SkillDigestTest / 6 -- ErrorSemanticsTest
[INFO] Tests run: 25 -- SkillExecutionTest（既有审批流回归）  3 -- SkillErrorEnvelopeTest  3 -- SkillTaskResultTest
[INFO] Tests run:  9 -- PathParamsAsyncMappingTest  2 -- HttpAsyncAdapterTest  2 -- SkillProcessAuditTest  11 -- ProcessAdapterTest
[INFO] Tests run:  5 -- SkillPublishDigestPostgresTest
[INFO] Tests run: 85, Failures: 0, Errors: 0, Skipped: 0    BUILD SUCCESS
```

**web（vitest）**——红（red-web.txt）：`Tests 3 failed | 1 passed`（`actionPlanView is not a function`——实现未落地）。绿：
```
tests/approval.test.ts 4 passed
全量：Test Files 37 passed，Tests 295 passed（基线 292 + 新增 3）
vue-tsc --noEmit EXIT=0；npm run build 全部三端 built 成功
```

## 3. 回归对照（新增失败 = 0）

| 范围 | 命令（节选） | 结果 | 与基线对照 |
| --- | --- | --- | --- |
| engine 定向多类 | `mvn test -Dtest='ActionPlanPostgresTest,TracePostgresTest,EngineMigrationsTest,EngineErrorSurfacePostgresTest,RunEnvironmentAnnotationTest' -Dsurefire.runOrder=reversealphabetical` | 50/50 绿 | TracePostgresTest 24 例（含既有写审批两用例）零失败；EngineErrorSurfacePostgresTest/RunEnvironmentAnnotationTest 相邻零失败 |
| skill 定向多类（含 T05/T06/T21d 回归集） | `mvn test -Dtest='SkillPrepareExecuteTest,SkillRulesTest,SkillExecutionTest,SkillErrorEnvelopeTest,SkillTaskResultTest,PathParamsAsyncMappingTest,HttpAsyncAdapterTest,SkillProcessAuditTest,ProcessAdapterTest,SkillPublishDigestPostgresTest,ErrorSemanticsTest,SkillDigestTest' -Dsurefire.runOrder=reversealphabetical` | 85/85 绿 | 回归期发现并修复 1 处自身回归（reconcile 读 `businessIdempotencyKey` 用 `text()` 对存量事实抛 400 → 改为缺键回退，见 §6 偏差⑤），修复后 SkillExecutionTest/PathParamsAsyncMappingTest 既有失败清零 |
| web 全量 | `npx vitest run` | 295 passed（37 文件） | 基线 292 + 新增 3 = 295，既有 0 失败 |
| web 类型/构建 | `npx vue-tsc --noEmit` / `npm run build` | EXIT=0 / 三端 built | 无新增类型错误 |
| 契约门禁 | `node scripts/verify-contracts.mjs` | 通过 | v1.2 快照、四契约哈希、七模块依赖边界、G-9 服务票据面 0 MISSING |

运行纪律：`source .tools/env-load.sh`（ENGINE_PG_TEST=true、AGENT_DB_PASSWORD）；`-Dsurefire.runOrder=reversealphabetical`；`RuntimeStreamTest` 未触碰；真实 PG 仅白名单测试库。

## 4. 验收对照（方案 §4.15）

- **PREPARE 返回 actionPlan 且无业务副作用**：`SkillPrepareExecuteTest.prepareReturnsActionPlanWithoutBusinessSideEffect`（预演入口 1 次调用、业务写端点 0 次、无 claim/submitted）。
- **digest 不匹配的 EXECUTE 拒绝且无副作用**：`ActionPlanPostgresTest.executeWithMismatchedKeyIsRejectedWithoutSideEffect`（409 + 调用仍 REGISTERED + 无 TOOL_EXECUTION_FACT + 无 execute_granted_at）。
- **过期 actionPlan 拒绝**：`ActionPlanPostgresTest.expiredPlanRejectsExecuteEvenAfterApproval`（批准后再过期，claim 409 ACTION_PLAN_EXPIRED）。
- **同一幂等键重复 EXECUTE 返回首次结果、单条执行记录（V221 真 PG）**：`ActionPlanPostgresTest.duplicateExecuteReturnsFirstResultWithSinglePlanRow`（二次 claim claimed=false 且返回首次 SUCCEEDED 结果、execute_granted_at 不变、action_plan 1 行）；`prepareApprovalPersistsStampedPlanUnderUniqueExecuteKey` 另以裸 insert 验证唯一约束真被 PG 拒绝 + 同内容重复 PREPARE 复用同一行。
- **越权确认 403 无副作用**：`ActionPlanPostgresTest.unauthorizedConfirmLeavesPlanAndCallUntouched`（403 APPROVAL_ROLE_REQUIRED + context 仍 PENDING + 计划仍 PREPARED + 无 confirmed_by）。
- **高危操作确认强度不足执行期拒绝**：`ActionPlanPostgresTest.highRiskRequiresTwoRolesOrDoubleConfirmBeforeAndAtExecute`（审批期 403 + 二次确认后批准 + 执行期 EXECUTE_KEY_REQUIRED/DIGEST 门仍在）与 `twoDeclaredRolesSatisfyHighRiskWithoutDoubleConfirm`（双角色豁免二次确认）。
- **旧技能（无 approval 声明）零回归锚点**：engine `legacySingleStepCallIgnoresExecuteKeyGate`（无 action_plan 行、无 executeKey 的 claim 照常授予）+ skill `legacyContractWithoutApprovalKeepsSingleStepFlow`（不调预演、业务键仍为逻辑调用键）+ 既有 `TracePostgresTest.writeApprovalIsBoundToStableCallArgumentsAcrossAttempts`/`SkillExecutionTest.unapprovedWriteNeverClaimsOrDispatches` 全绿。
- **前端确认面板渲染 diff/risk/target**：`web/tests/approval.test.ts` 3 例（视图提取、二次确认门、SFC setupState 暴露字段）；确认中心展开列实际渲染。
- **审计**：`action_plan` 行 confirmed_by/confirmed_at/execute_granted_at/executed_status/result_digest + `APPROVAL_DONE` 事件带 actionPlanId/risk（两用例内断言）。
- **错误码 code+userMessage+traceId**：9 个新码（APPROVAL_MODE_INVALID/APPROVAL_PREPARE_UNSUPPORTED/APPROVAL_PREPARE_INVALID/APPROVAL_RISK_INVALID/ACTION_PLAN_UNAVAILABLE/EXECUTE_KEY_REQUIRED/ACTION_PLAN_DIGEST_MISMATCH/ACTION_PLAN_EXPIRED/CONFIRM_STRENGTH_INSUFFICIENT）全部经既有 PlatformException/ErrorSemantics 信封，登记于契约 §14.6。

## 5. 回滚方式

1. **V221 down**：`DROP TABLE engine.action_plan;`（表为纯新增，无旧列改动；审批节点 approval_context 内的 actionPlan 引用字段随之失效但不再被解释——`requireExecutablePlan`/`approve` 对缺表场景不可用，必须先回退代码）。
2. **代码回退**：revert 本卡 4 个主代码文件（ToolCalls/ApprovalService/SkillCatalog/SkillExecution）+ SkillRules/SkillAdapters 两个方法级增补 + 契约 §14 与 freeze.json 回退（sha256 恢复 `a41502f2…`）。回退后：存量与两段技能的 `execution.approval` 键成为惰性字段（无解释入口），两段调用自动退回 SINGLE_STEP 语义——审批节点/确认角色/审计等既有能力不受影响。
3. **行为开关**：本卡未新增配置开关；紧急止血可按第 2 条 revert 代码（无迁移数据依赖，action_plan 表可保留为空壳）。

## 6. 偏差与定死决策（每条单列）

1. **D1 兼容策略（有意偏差）**：方案 §4.15 写"默认 `PREPARE_EXECUTE`，旧技能可显式 `SINGLE_STEP`"；实现定死为"**缺省不落键 = `SINGLE_STEP`，显式声明 `PREPARE_EXECUTE` 才走两段**"。理由：缺省即两段会让全部存量写技能行为漂移（每次写调用多一次预演请求 + 确认面板形态变化），违背"向后兼容（行为变更走开关或双写法窗口）"计划 §6.5；方案的两段目标是新增写工具（路线三业务工具），显式声明即达成"默认走两段"的演进意图。契约 §14.1 已写明。
2. **D2 PREPARE 调用形态（二选一）**：定死**独立入口声明**（`prepare.endpoint`）而非 phase 参数。理由：业务系统的预览/dry-run 通常是独立端点；同一端点 + phase 标记会要求业务系统在写入口内实现分支，扩大业务侧改造面。GET 被显式拒绝（参数进 URL 日志）；非 HTTP 适配器与异步 HTTP 不支持（无对应无副作用预演通道/自带任务态语义），注册期 400 拒绝——与 §13 identity 的适配器边界同一裁决。
3. **D3 幂等键来源**：actionPlan 的 `idempotencyKey/expiresAt` 由引擎盖章（技能侧占位被覆盖），而非采用技能预演返回值。理由：幂等键是重复执行仲裁的根，必须落在持有唯一约束（V221）与事实对账的一方；技能侧提供可伪造/可碰撞。`executeKey` 派生自"去键计划核心摘要"，避免自引用循环。
4. **D4 高危判别**：声明式 `risk=HIGH`，不做"删除/改状态/发 Key"的 URL/方法猜测；确认强度 = 双角色或二次确认（`doubleConfirmed`），审批与执行双门。方案 §4.15 第4项"至少两个角色或二次确认"逐字落地。
5. **对账键修正（实现期发现的自回归）**：两段 EXECUTE 的业务出站键 = 盖章键 ≠ 逻辑调用键，对账若沿用旧键会查错端点。已让 request_snapshot 记录 `businessIdempotencyKey` 并在 `reconcile` 优先读取（缺键回退旧键，存量事实零影响）。首版实现用 `text()` 读取导致 T06 既有用例 4 处 + SkillExecutionTest 1 处失败（红→绿回归期抓到并修复，最终 85/85 绿）。
6. **测试夹具环境注记**：共享 engine 测试库此前被并行泳道以 V223 先行迁移，V221 首跑触发 Flyway 乱序拒绝；`ActionPlanPostgresTest` 夹具以 `outOfOrder(true)` 一次性补齐（注释写明"一次性引导"，新库上按序应用，行为等价）。另观察到 `engine-runtime/target/classes/db/migration/V222__run_context_pack.sql` 为并行泳道遗留的构建产物（源码树无 V222）——未触碰，仅记录。
7. **web/shared 与 web/tests 归属**：`shared/approval.ts` 与 `tests/approval.test.ts` 在物理上位于 `web/admin/src` 白名单之外，但它们是既有确认面板的共享逻辑与既有测试文件（本卡为纯追加：2 个函数 + 3 个用例），未改动任何既有函数行为；特此单列报备。
8. **mock 运行豁免预演**：调试 Mock 运行（引擎登记 mock_snapshot 非空）不调用业务预演入口（Mock 本就不触达业务系统），actionPlan 以占位摘要生成——契约 §14.2 已写明。
