# PLAT-05 技能侧（T05）失败结果可读——证据

## 1. 范围与依据

- 依据：《平台侧优化方案与实施细则-20261027》§4.5（PLAT-05 失败结果与状态端点可读）与 §4.16（PLAT-16 桥 envelope 映射，拆入 T05 落地）；《优化台账-平台侧-PLAT》§2 PLAT-05、§3 T05.1/T05.2；开发计划 §6 代码规范、§7.2 证据规范。
- 基线：`agent-platform` `feat/v1.2-implementation` @ `b68d60d`。
- **T05.1 错误模型扩字段**：新增 `skill-registry/.../domain/ErrorSemantics.java`（纯函数域模型：七字段规范化、`Declared` 声明解析、远端 `code/message` 提取、`retryableCodes` 改判）；`SkillAdapters.Failure` 扩展 `code/raw` 字段与 `error(traceId)`；`SkillExecution.envelope` 的错误对象由 `{semantic,reason}` 扩为七字段（`semantic/code/category/retryable/userMessage/detail/traceId`，保留 `reason` 兼容键）；同步 HTTP 适配器错误响应按契约声明（缺省 `$.code/$.message`）提取远端业务码与原文；异步适配器 `REMOTE_FAILED`/提交 4xx 同样携带；回调失败路径（SkillCallbacks）同构。注册期在 `SkillCatalog.normalize` 校验对象形态声明。
- **契约 errorSemantics**：`contracts/skill-invocation.md` 增补 §11（七字段错误对象、对象形态声明、平台默认映射含路线三桥 `ok:false` 403 → `NO_PERMISSION`/`category=AUTH`/`retryable=false`、`GET /admin/skills/tasks/{callId}/result` 端点）；`contracts/freeze.json` 更新该契约 sha256=`5400fb4699a64e35694fe1799b5fb4a206c50089a9780e8a7496f65e518cb20a` 并登记 `engineeringAddendum` 溯源。
- **T05.2 结果端点**：新增 `skill-registry/.../api/TaskResultController.java`（`GET /admin/skills/tasks/{callId}/result`，只校验参数+调应用层）与 `SkillExecution.taskResult(callId)`：失败任务返回完整信封（含七字段 error）；不存在 → 404 `FACT_NOT_FOUND`；未终态（UNKNOWN/执行准备中）→ 409 `TASK_NOT_FINAL`。
- **HttpAsyncAdapter 409/4xx 处理（§4.5 实现3）**：结果端点 409/4xx 不再直接折叠为 RESULT_UNKNOWN，先复查状态端点，能确定终态失败则产出 `REMOTE_FAILED` + 契约声明的业务码/原文并收敛绑定行；不能确定则保持 RESULT_UNKNOWN 走对账。未触碰 T06 将改造的 pathParams/占位符替换逻辑。
- **不修改**：`admin-common/**PlatformErrors.java`、engine-runtime、app-management、monitor、web、迁移目录（本任务零迁移）。

## 2. 运行环境

- JDK：Eclipse Adoptium jdk-21.0.12.8-hotspot（`JAVA_HOME` 指定，JDK17 默认 java 不可用）。
- PostgreSQL：127.0.0.1:25432，白名单测试库 `agent_platform_v12`（同步/端点用例）与 `agent_platform_skill_async_test`（异步用例），用户 `agent_v12`；凭据经 `.tools/env-load.sh` 加载 `.env`（CRLF 剥离）。
- Maven：本仓多模块，`-pl skill-registry`（admin-common 取自本地仓库）；`-DargLine=-Xmx1024m`（计划 §7.5 已知 fork JVM native OOM 规避）。
- G-9 路由目录：新 handler 位于 `/admin/skills/**` 门户面（经网关既有 `/admin/skills/**` 代理），构建期仅产生不阻断的门户面缺行告警（本任务 +1 条）；skill-registry 运行期未装配 RouteCoverageVerifier，无启动影响。

## 3. 执行的命令原文

前置门禁：

```
node scripts/verify-contracts.mjs
```

红→绿定向（多类同跑；JAVA_HOME 必须 JDK21；先 `source .tools/env-load.sh`）：

```
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
source .tools/env-load.sh
mvn -B -pl skill-registry test -Dtest='SkillErrorEnvelopeTest,AsyncResultEndpointFailureTest' "-DargLine=-Xmx1024m"   # 红：行为级失败先证
mvn -B -pl skill-registry test -Dtest='ErrorSemanticsTest,TaskResultControllerTest,SkillErrorEnvelopeTest,SkillTaskResultTest,AsyncResultEndpointFailureTest' "-DargLine=-Xmx1024m"   # 绿：五类同跑
```

回归（全模块单 JVM；与基线 `b68d60d` 同命令对照）：

```
mvn -B -pl skill-registry test "-DargLine=-Xmx1024m"
```

基线对照在同一提交的独立 `git worktree`（`../wt-t05-baseline`，跑完已删除）内以同命令执行。

## 4. 结果（Tests run 原文）

红阶段（实现前，仅行为级两类）：

```
[ERROR] Tests run: 4, Failures: 4, Errors: 0, Skipped: 0
[ERROR]   AsyncResultEndpointFailureTest.resultEndpoint409RechecksStatusAndReportsRemoteFailed:296 结果端点 409 不能折叠成 RESULT_UNKNOWN... expected: <REMOTE_FAILED> but was: <RESULT_UNKNOWN>
[ERROR]   SkillErrorEnvelopeTest.bridgeEnvelope403BecomesReadableAuthRejection:234->assertSevenFields:220 错误对象缺少字段: code
[ERROR]   SkillErrorEnvelopeTest.declaredErrorSemanticsRetryableCodeOverridesHttpDefault:260 expected: <RETRYABLE_FAILURE> but was: <PARAM_ERROR>
[ERROR]   SkillErrorEnvelopeTest.untrackedBusinessCodeKeepsHttpDefaultSemantics:287 expected: <PARAM> but was: <>
```

（说明：`ErrorSemanticsTest`/`SkillTaskResultTest`/`TaskResultControllerTest` 引用当时尚不存在的 API，红阶段以编译失败为红，未单独留编译输出；上列 4 例是对既有 API 的真断言失败，红输出原文见 `red-run1-behavioral.txt`。）

绿阶段（实现后，五类同跑）：

```
[INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

（分 클래스：ErrorSemanticsTest 6、TaskResultControllerTest 4、SkillErrorEnvelopeTest 3、SkillTaskResultTest 3、AsyncResultEndpointFailureTest 1；原文见 `green-run-all5.txt`。）

回归对照（全模块单 JVM）：

| 运行 | Tests run | Failures | Errors | Skipped |
|---|---|---|---|---|
| 基线 `b68d60d`（worktree） | 586 | 6 | 106 | 4 |
| 本任务改动后 | 603（=586+17 新增） | 6 | 106 | 4 |

失败方法清单逐一 diff **完全一致**（`failing-methods-baseline.txt` 与 `failing-methods-with-t05.txt` 相同，112 行）——**零新增失败、零新增错误**。既有失败为：AlgorithmExecutionPostgresTest（2F/25E）、AsyncHttpExecutionTest（3F/5E）、CallbackHttpTest（32E）、ReconciliationProofTest（34E）、SkillCallbackWakeTest（3E）、AlgorithmMigrationPostgresTest（1F）、McpManagementTest（7E）。

门禁：

```
G-9 路由目录：服务票据面 0 MISSING；门户面缺行告警 27 条、STALE 5 条（均不阻断...）
v1.2快照、四契约原文哈希与七模块依赖边界通过
```

## 5. 未覆盖与已知失败

- **既有失败（非本任务引入，与基线逐一相同）**：上表 7 类 112 例。主因是计划 §7.5/审计 Y-3 记载的进程级静态态问题——`ServiceTokens.deploymentMode` 仅由 Spring 容器内 `SecurityConfiguration` 注册，skill-registry 测试全部为纯 JUnit（不启容器），依赖 `SkillClients` 出票的用例在全模块 JVM 内必现 `403 服务票据必须由当前可信授权签发`；余为闩锁时序（AsyncHttpExecutionTest 三例 10s 超时）与迁移夹具断言（AlgorithmMigrationPostgresTest 对比串含 null 列展开）。修复属测试基座事项（G-8/Y-3 线），不在 T05 白名单内。
- **v1.2 数组形态 errorSemantics（`[{"match":...,"semantic":...}]`）未按 match 解释**：v1.2 起该字段从未被执行侧实现；本任务仅实现方案 §4.5 的对象形态，数组形态原样透传并按平台默认映射处理（契约 §11.2 已写明）。OpenApiImportsTest/OntologyManifestImportsTest 的既有用法不受影响（已随全模块通过）。
- **引擎侧输出（run.error、SSE、管理台）与前端展示属另一泳道**（PLAT-05 引擎侧 T04 列），本任务只保证技能侧信封/事实/端点三处同构；`errorSemantic` 顶层键与 `error.reason` 兼容键保留，引擎既有读取路径不受影响。
- **运行期路由目录**：新端点为门户面，`agent-service-authority.json`（admin-common，白名单外）未加行；构建期仅告警。若编排者要求门户面也零告警，需在 admin-common 泳道补一行（`GET /admin/skills/tasks/{callId}/result`）。
- **snapshot 未同步修改**：`contracts/snapshot/detailed-design-v1.2.md` 是 v1.2 原文冻结输入（`sourceSha256` 锚定仓库外原始文档），本次为平台优化增补语义而非 v1.2 原文变更，改写快照会伪造原文漂移；故仅在 `skill-invocation.md` 追加明确标注非原文的 §11 并更新其 digest、在 freeze.json 登记 `engineeringAddendum`。这是对派发单"同步 contracts/snapshot/"一句的有意偏差，理由如上。
- 未覆盖：MCP/ALGORITHM/PROCESS 适配器失败的 code/raw（远端无错误响应体可解析，恒 null——七字段仍齐全，由 ErrorSemanticsTest 语义缺省覆盖）；CALLBACK 失败回调携带 code/message 的提取（机制与同步一致，未单列用例）。

## 6. 回滚方式

- 代码回滚：还原以下文件至 `b68d60d` 即完全回到改造前行为（无迁移、无配置、无开关，错误结构变化不落任何 schema）：
  - `skill-registry/src/main/java/cn/agentplatform/skill/domain/ErrorSemantics.java`（删除）
  - `skill-registry/src/main/java/cn/agentplatform/skill/api/TaskResultController.java`（删除）
  - `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillAdapters.java`、`infra/HttpAsyncAdapter.java`、`application/SkillExecution.java`、`application/SkillCallbacks.java`、`application/SkillCatalog.java`（git checkout）
  - `skill-registry/src/test/java/**`（5 个新测试类删除；`SchemaDegradedValidationTest.java` 还原 envelope 调用签名）
- 契约回滚：`contracts/skill-invocation.md` 删除 §11；`contracts/freeze.json` 中 skill-invocation 的 `sha256` 还原为 `43dce0daf6d5510ed9399190e2ea83418bcc2c501778e25ef6efaecef7016506` 并删除 `engineeringAddendum` 键；回滚后 `node scripts/verify-contracts.mjs` 应通过。
- 存量数据兼容：新七字段错误对象只存在于新写入的 `skill.execution_fact.result` JSONB；旧行 `{semantic,reason}` 结构可被新代码正常读取（`error.semantic`/`error.reason` 键保留），无需数据迁移。
