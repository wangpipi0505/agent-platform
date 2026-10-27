# PLAT-06 / T06：SUBMIT_QUERY 声明式路径映射（pathParams）与 catch 降级修正 — 证据

## 1. 范围与依据

- 派发单元 S-2（T06.1 + T06.2），方案 §4.6（553–609 行），台账 §3 T06.1/T06.2，计划 §6 规范、§2.3 #4/#5。
- 基线：分支 `feat/v1.2-implementation`，开工时 HEAD `0b704d9`；skill-registry 基线 `bdc663f`（T05 技能侧，已收口，未推翻其错误处理路径）。
- 交付内容：
  1. **T06.1**：契约支持 `reconciliation.pathParams`（平台字段 `externalJobId`/`idempotencyKey` → 远端占位符）；保存期校验"端点占位符（endpoint/resultEndpoint/lookupEndpoint 并集）与 pathParams 一一对应"；占位符缺映射 → 400 `ASYNC_PATH_PARAM_MISSING`（带字段路径 `reconciliation.pathParams.<名>` 与修复建议）；声明了端点未使用的占位符 → 400 `ASYNC_PATH_PARAM_UNMATCHED`（新增码，触发条件在提交说明与契约 §12 写明）。
  2. **兼容**：旧 `{jobId}`/`{idempotencyKey}` 字符串替换原样保留（内置默认映射，语义固定，pathParams 不得重定义）；空 `httpMapping` 对 SUBMIT_QUERY 视为缺省（实测保存期已按缺省移除，用例固化该行为）；执行期对任务标识未知 + 端点需 externalJobId 的 pathParams 契约不发垃圾请求，保持结果未知等待对账（旧 `{jobId}` 行为不变）。
  3. **T06.2**：状态端点 401/403 → `NO_PERMISSION`（unknown=false，携带远端码/原文，绑定行保持 SUBMITTED 不谎称外部终态）；其余 4xx/5xx 仍 RESULT_UNKNOWN 但原因带 HTTP 状态与远端业务码、cause 保留（`initCause`）；执行器 `SkillExecution` 兜底 catch 对 `ASYNC_*` PlatformException（如 400 `ASYNC_MULTIPART_UNSUPPORTED`）按 PROCESS_ 先例原样透出，不再折叠成 UNKNOWN/OUTPUT_INVALID；查询 catch 折叠消息携带原始语义/原因。
  4. **jobIdPath 双源优先级文档化**：`reconciliation.jobIdPath` 优先于顶层 `jobIdPath`，缺省 `$.jobId`（现状行为，写入契约 §12.2）。
  5. **契约增补**：`contracts/skill-invocation.md` 追加 §12（工程增补，非 v1.2 原文，snapshot 不动），`contracts/freeze.json` 更新 sha256 并在 engineeringAddendum 登记；`node scripts/verify-contracts.mjs` 通过。
- **迁移：零改动**（pathParams 存技能契约 JSON，无 DDL；V319 未动用）。

## 2. 环境

- JDK21：`JAVA_HOME=/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot`
- 真实测试库：`jdbc:postgresql://127.0.0.1:25432/agent_platform_skill_async_test`（白名单库，`AGENT_DB_PASSWORD` 经 `source .tools/env-load.sh` 载入，已剥 CRLF）
- Maven：`mvn -B -pl skill-registry test -Dtest='<类列表>' "-DargLine=-Xmx1024m"`
- 契约门禁：`node scripts/verify-contracts.mjs`

## 3. 命令原文

```bash
# 前置门禁
node scripts/verify-contracts.mjs

# 红阶段（新测试类，8 失败 + 1 既有锚点绿）
source .tools/env-load.sh
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
mvn -B -pl skill-registry test -Dtest='PathParamsAsyncMappingTest' "-DargLine=-Xmx1024m"

# 绿阶段（新类）
mvn -B -pl skill-registry test -Dtest='PathParamsAsyncMappingTest' "-DargLine=-Xmx1024m"

# 回归批（含 T05 五类与相邻类；-Dsurefire.runOrder=reversealphabetical 使
# SkillClientsErrorTest 先行注册进程级 deploymentMode，规避计划 §7.5 已知的
# 静态态隔离假红——不加该参数时 AsyncHttpExecutionTest 恰好出现文档记载的 8 项失败）
mvn -B -pl skill-registry test -Dsurefire.runOrder=reversealphabetical \
  -Dtest='PathParamsAsyncMappingTest,TaskResultControllerTest,AsyncResultEndpointFailureTest,SkillErrorEnvelopeTest,SkillTaskResultTest,ErrorSemanticsTest,AsyncHttpExecutionTest,SkillAuthSchemeRegistrationTest,ExternalJobStateTest,HttpAsyncAdapterTest,SkillRulesTest,SkillExecutionTest,SchemaDegradedValidationTest,SkillClientsErrorTest,CallbackHttpTest,ReconciliationProofTest,SkillCallbackWakeTest' \
  "-DargLine=-Xmx1024m"

# 基线对照：同命令、同 runOrder、同类列表（PathParamsAsyncMappingTest 移出、
# skill-registry/src/main/java 的 T06 改动 git stash push 后运行，跑完恢复），
# 输出见 baseline-final-batch-same-order.txt，方法级 diff 见 failure-set-diff.txt
```

## 4. 结果原文（红→绿）

**红阶段**（`red-run1.txt`，9 例 8 红 1 绿；1 绿 = 旧 `{jobId}` 契约零回归锚点）：

```
[ERROR] Tests run: 9, Failures: 3, Errors: 5, Skipped: 0
PathParamsAsyncMappingTest.asyncProtocolRejectionKeepsItsCodeInsteadOfFoldingToUnknown
  ==> Expected PlatformException to be thrown, but nothing was thrown        （T06.2 执行器折叠）
PathParamsAsyncMappingTest.missingMappingRejectedWithFieldPathAndSuggestion
  ==> expected PlatformException but was IllegalArgumentException             （URI.create 不可读报错，即方案"现状"痛点）
PathParamsAsyncMappingTest.unmappedPathParamsEntryRejected                   （同上）
PathParamsAsyncMappingTest.pathParamsContractSavesSubmitsAndReconcilesWithSubstitution
  ? IllegalArgument Illegal character in path at index 29: .../tasks/{remoteTaskId}
PathParamsAsyncMappingTest.pathParamsCoverBothPlatformFieldsAcrossPathAndQuery （同上）
PathParamsAsyncMappingTest.queryAuthRejectionSurfacesNoPermissionInsteadOfUnknown （同上，且折叠成 UNKNOWN）
PathParamsAsyncMappingTest.queryNotFoundStaysUnknownWithReadableStatus        （同上）
PathParamsAsyncMappingTest.emptyHttpMappingTreatedAsAbsentForSubmitQuery      （同上）
```

**绿阶段（新类）**（`green-newclass-9of9.txt`）：

```
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0 -- in cn.agentplatform.skill.application.PathParamsAsyncMappingTest
[INFO] BUILD SUCCESS
```

九例对应方案 §4.6 验收：①只用 `{remoteTaskId}`+pathParams 保存/提交/真实对账（断言远端收到 `/tasks/task-1`，查询串占位符替换为幂等键原文）；②缺映射 400 `ASYNC_PATH_PARAM_MISSING` 含 `reconciliation.pathParams.remoteTaskId` 与修复建议；③旧 `{jobId}` 契约同链路绿；④空 `httpMapping` 保存通过且不落键；⑤查询 403 → FAILED `NO_PERMISSION` + 远端码 AUTH_EXPIRED + 原文（绑定行保持 SUBMITTED）、404 仍 UNKNOWN 但原因含 `HTTP 404`、ASYNC_MULTIPART_UNSUPPORTED 400 原样透出；⑥pathParams 多余映射保存期拒绝。

**回归批**（`green-final-batch.txt`，含 T06；`baseline-final-batch-same-order.txt` 为同口径基线）：

```
with-T06:    Tests run: 193, Failures: 1, Errors: 41
baseline:    Tests run: 184, Failures: 1, Errors: 41   （差值 9 = 新增用例）
失败方法集合：两轮完全一致（43 个，全在 CallbackHttpTest/ReconciliationProofTest/SkillCallbackWakeTest
三个既有 Spring 上下文静态耦合类），新增 0、消失 0 —— 见 failure-set-diff.txt
关键类（T06 触达面）全绿：
  PathParamsAsyncMappingTest 9/9、AsyncHttpExecutionTest 29/29、AsyncResultEndpointFailureTest 1/1、
  SkillErrorEnvelopeTest 3/3、SkillTaskResultTest 3/3、TaskResultControllerTest 4/4、ErrorSemanticsTest 6/6、
  ExternalJobStateTest 8/8、SkillAuthSchemeRegistrationTest 7/7、HttpAsyncAdapterTest 2/2、
  SkillRulesTest 6/6、SkillExecutionTest 25/25、SchemaDegradedValidationTest 14/14、SkillClientsErrorTest 7/7
```

**契约门禁**（`verify-contracts.txt`）：

```
v1.2快照、四契约原文哈希与七模块依赖边界通过
```

## 5. 未覆盖与已知失败

- **43 个既有失败**（基线同口径复跑证明与本改动无关）：`CallbackHttpTest`(32)、`ReconciliationProofTest`(7)、`SkillCallbackWakeTest`(3)+1，根因是三个类各自的 Spring 上下文/进程级静态态（`SecurityConfiguration` 一次性部署校验、`ServiceTickets` 监听器）在同一 JVM 内互相干扰，属计划 §7.5 "运行方式导致的红"同类；不在派发单要求的类列表内，未修改。默认字母序 + 不含 SkillClientsErrorTest 时 `AsyncHttpExecutionTest` 出现 §7.5 文档记载的 8 项隔离假红（本证据已用 runOrder 参数消除并双跑对照）。
- 未覆盖：连接测试 HTTP 容器级用例（连接测试走的是受控调试真实执行链，与 `executor.execute` 同一代码路径，适配器层替换正确性已由 ① 的真实对账断言覆盖）；lookupEndpoint + pathParams 的执行期替换无专门端到端用例（保存期校验已覆盖其形态，执行期 substitution 与主端点共用同一实现）；`resultEndpoint` 含旧 `{idempotencyKey}` 的既有缺陷顺带修复（原实现只替换 `{jobId}`，含 `{idempotencyKey}` 时 URI.create 必败），无独立用例。
- 已知边界（语义取舍，非缺陷）：查询阶段 429 按"结果未知"处理而非 T05 提交侧的 `QUOTA`——配额限制下轮询可恢复，判 FAILED 会把可恢复等待变成终态；提交后 ACK 处理期的 `PlatformException`（如 jobIdPath 非法的 `ASYNC_PATH_INVALID`，仅可能来自绕过注册期的存量数据）保持 unknown 折叠——改为 FAILED 会让已提交的外部任务永久失去对账资格；凭据解析类 PlatformException（`SKILL_CREDENTIAL_MISSING` 等）不属 `ASYNC_*/PROCESS_` 前缀，执行器兜底仍折叠为 UNKNOWN，如需透出属独立决策。
- 回滚开关：无新增配置开关；§6.2 规范的开关要求针对"新增行为默认由配置控制"，本卡为错误面可读化与契约新键（新增字段可空、旧写法不变，符合 §6.5 前后兼容），未引入需要运行时开关的行为分叉。

## 6. 回滚方式

- 代码回滚： revert 本次提交即可——全部改动集中在 `skill-registry`（`HttpAsyncAdapter`、`SkillCatalog`、`SkillExecution` + 新测试类）与 `contracts/skill-invocation.md`/`contracts/freeze.json`，无迁移、无表结构、无配置项；旧契约（`{jobId}`/`{idempotencyKey}`）行为在改动前后由专用锚点用例与既有 `AsyncHttpExecutionTest` 双重锁定，revert 后立即恢复原语义。
- 契约回滚： `contracts/freeze.json` 的 `skill-invocation.sha256` 还原为 `5400fb4699a64e35694fe1799b5fb4a206c50089a9780e8a7496f65e518cb20a`、engineeringAddendum 还原为 PLAT-05 单条目；`contracts/snapshot/` 全程未动。
- 存量数据：pathParams 只存在于其后保存的契约 JSON 内，已保存的 pathParams 契约在回滚后保存期会回到 `{...}` 的 URI.create 报错形态（不可读但不影响已发布版本执行——执行期 `declaredPathParams` 容忍读取，回滚后旧执行路径对未映射占位符按原 unknown 兜底）。
