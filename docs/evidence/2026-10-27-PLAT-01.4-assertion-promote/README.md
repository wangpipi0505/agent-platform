# PLAT-01.4 让“提升为评测用例”链路真正携带断言

日期：2026-10-27
范围：`monitor`（保存期契约）+ `engine-runtime`（提升请求透传）
结论：提升请求里的 `assertion` 现在与 `id/inputs/expected/safety` 同权进入用例，保存期按
`EvaluationAssertions.requireValid` 校验并以 canonical 形状冻结；数据集读接口原样回读；带
`CONTAINS` 的用例在 `EvaluationService` 判分时确实按 `CONTAINS` 执行。

## 1. 问题

- 前端调试面板早已提交断言：`web/debug-panel/NamedInputs.vue:132-165` 的 `promote()` 在
  `assertion !== 'EXACT'` 时把 `assertion` 放进提升请求体，并在 `:160-162` 专门识别
  `不支持字段: assertion` 的老后端错误。后端不跟进时，用户选 `CONTAINS` 必然 400。
- 提升链路有两层白名单把字段丢掉：
  1. `engine-runtime/src/main/java/cn/agentplatform/engine/application/TestInputService.java`
     （任务书写作 `monitor/.../TestInputService.java`，该文件不存在于 monitor；真实位置由父代理
     2026-10-27 裁决扩围确认）。原 `:229-231` 字段白名单只有
     `requestId/revision/datasetVersion/caseId/expected/safety`，原 `:257-289` 组 case 时只放
     `id/inputs/expected/safety`。请求带 `assertion` 会直接
     `400 INVALID_INPUT 不支持字段: assertion`。
  2. `monitor/src/main/java/cn/agentplatform/monitor/application/DatasetService.java` 原 `:90`
     用例白名单同样只有 `id/inputs/expected/safety`，即便 engine 透传也会被拒。
- 即使绕过两层白名单，`EvaluationService` 的用例读取路径只认 `assertion` 字段，缺失即按
  `EXACT` 判分（`EvaluationAssertions` 的默认模式），所以通过提升创建的用例永远无法使用
  `CONTAINS`/`NUMERIC_TOLERANCE`。
- 附带契约不一致：`engine-runtime/.../TestInputClient.java` 原本把所有 4xx 统一改写为
  `TEST_INPUT_DEPENDENCY_REJECTED`，monitor 已经给出的 `EVALUATION_ASSERTION_*` 业务码到不了
  前端；本次按父代理批准一并只对提升调用透传统一 `ApiResponse` 的 code/message。

## 2. 改动

只改以下文件（另加证据目录）：

| 文件 | 改动 |
| --- | --- |
| `engine-runtime/src/main/java/cn/agentplatform/engine/application/TestInputService.java` | 白名单加入 `assertion`（`:231-239`）；组 case 改用 `LinkedHashMap`，仅在 `body.has("assertion")` 时写该键（`:264-271`），缺失绝不补 `EXACT` |
| `engine-runtime/src/main/java/cn/agentplatform/engine/infra/TestInputClient.java` | 仅 `promote()` 走 `propagateRejection` 分支：4xx 响应体是统一 `ApiResponse` 时原样抛出 monitor 的 code/message；非 JSON 保持原依赖拒绝；5xx/连接中断语义不变（仍 502） |
| `monitor/src/main/java/cn/agentplatform/monitor/application/DatasetService.java` | 用例白名单加入 `assertion`（`:122`）；保存前 `EvaluationAssertions.requireValid(assertion, expected, safety)`（`:85-95`），`InvalidAssertion` 以原 code/原文案 `fail(400, …)` 透出；仅显式断言时用 `Spec.canonical()` 重写（`:97-106`），缺失保持数组原字节；落库与摘要都用冻结后的 cases（`:144-145`） |
| `monitor/src/test/java/cn/agentplatform/monitor/application/DatasetServiceTest.java` | 新增 5 条保存期测试（真实 PG，Flyway monitor） |
| `monitor/src/test/java/cn/agentplatform/monitor/application/EvaluationAssertionServiceTest.java` | 新增 `containsAcceptsSupersetOutputThatExactRejects`（`:387`）：同一份输出分别用 CONTAINS/EXACT 判分，CONTAINS 通过、EXACT 失败 |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/TestInputClientTest.java` | 新增 3 条：透传原码原消息、5xx 仍 502、非 ApiResponse 回退旧码 |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/TestInputPromotionPostgresTest.java` | 新增 3 条组合测试：真实 PG 草稿夹具 + 真实 `TestInputService`/`TestInputClient` + 桩 monitor HTTP，验证 carried payload、canonical 形状、跨 HTTP code 透传 |

口径决策：

- **缺失 = EXACT 的存库表达**：不写 `assertion` 键，而不是写 `"EXACT"`。这样历史数据集与提升
  生成的用例字节、摘要都不变，`EvaluationService` 原有“缺失即 EXACT”的读取语义继续成立。
  显式传 `"EXACT"` 时按 canonical 原样存 `"EXACT"`（合法且等价；前端默认不会发送该键）。
- **canonical 形状复用契约**：字符串模式存模式名；`NUMERIC_TOLERANCE` 存
  `{"mode":"NUMERIC_TOLERANCE","tolerance":<double>}`；对象输入还会被压缩为契约的
  `{mode,tolerance}`，不引入新字段名。
- **校验位置**：语义校验只在 monitor 保存期做一次（`EvaluationAssertions` 是 monitor 领域契约，
  engine-runtime 不能跨服务依赖它）；engine-runtime 只负责原样搬运。非法配置在写数据集与请求
  摘要之前被拒，`storedRows()==0`。
- **错误码不包装**：`EVALUATION_ASSERTION_INVALID`、`EVALUATION_ASSERTION_SAFETY_CONSTRAINT`、
  `EVALUATION_ASSERTION_UNKNOWN` 原样返回；跨服务时 `TestInputClient.promote` 从 4xx
  `ApiResponse` 里恢复 code/message，其他依赖错误仍走 `TEST_INPUT_DEPENDENCY_REJECTED`。

## 3. 验证

环境：

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
source .tools/env-load.sh   # AGENT_DB_PASSWORD / ENGINE_PG_TEST=true
```

复现红绿：`bash docs/evidence/2026-10-27-PLAT-01.4-assertion-promote/run-evidence.sh`
（脚本会临时 `git checkout --` 三个主文件跑红，`trap` 保证恢复后再跑绿；原始输出在
`red-dataset.log`、`red-engine.log`、`green-monitor.log`、`green-engine.log`）。

**红（旧实现，原文）**

```
[ERROR] Tests run: 5, Failures: 2, Errors: 2, Skipped: 0
[ERROR] Tests run: 6, Failures: 2, Errors: 2, Skipped: 0
```

失败点与预期一致：

- `DatasetServiceTest.rejectsExtraAssertionParameterWithoutWritingTheDataset:119 expected:
  <EVALUATION_ASSERTION_INVALID> but was: <INVALID_DATASET>`
- `DatasetServiceTest.rejectsSafetyCaseThatWeakensEqualityWithoutWritingTheDataset:107 expected:
  <EVALUATION_ASSERTION_SAFETY_CONSTRAINT> but was: <INVALID_DATASET>`
- `DatasetServiceTest.promotionKeepsContainsAssertionAndReadsItBack` /
  `numericToleranceIsStoredAsCanonicalModeAndToleranceObject`：`平台异常 不支持字段: assertion`
  （Windows 控制台按 GBK 显示为乱码，日志原始字节是 UTF-8）
- `TestInputClientTest.propagatesMonitorAssertionBusinessCodeAndMessage:81 expected:
  <EVALUATION_ASSERTION_SAFETY_CONSTRAINT> but was: <TEST_INPUT_DEPENDENCY_REJECTED>`
- `TestInputPromotionPostgresTest.promotionRejectionKeepsMonitorAssertionCodeAcrossHttp:227
  expected: <EVALUATION_ASSERTION_SAFETY_CONSTRAINT> but was: <INVALID_INPUT>`
- `TestInputPromotionPostgresTest.promotionCarriesCanonicalNumericAssertionObject:152` /
  `promotionCarriesStringModeAndOmitsMissingAssertion:173`：同样 `不支持字段: assertion`

**绿（实现后，原文）**

```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.779 s -- in cn.agentplatform.monitor.application.DatasetServiceTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.097 s -- in cn.agentplatform.monitor.application.EvaluationAssertionServiceTest
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.439 s -- in cn.agentplatform.engine.infra.TestInputClientTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.822 s -- in cn.agentplatform.engine.infra.TestInputPromotionPostgresTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
```

覆盖对照（任务书 5 条）：

1. `DatasetServiceTest.promotionKeepsContainsAssertionAndReadsItBack`：保存返回 OK，重新
   `get()` 回读 `assertion == "CONTAINS"`。
2. `DatasetServiceTest.numericToleranceIsStoredAsCanonicalModeAndToleranceObject`：回读
   `{mode:"NUMERIC_TOLERANCE",tolerance:0.05}`（`assertEquals` 整个对象）。
3. `rejectsSafetyCaseThatWeakensEqualityWithoutWritingTheDataset`：`safety=true` + `CONTAINS`
   抛 400 `EVALUATION_ASSERTION_SAFETY_CONSTRAINT`，`storedRows()==0`。
4. `rejectsExtraAssertionParameterWithoutWritingTheDataset`：`{"mode":"REGEX","pattern":"x"}`
   抛 400 `EVALUATION_ASSERTION_INVALID`，消息含 `pattern`，未落库。
5. `EvaluationAssertionServiceTest.containsAcceptsSupersetOutputThatExactRejects`：同一 CHAT 输出
   （比 expected 多 `usage`/`actualModel`）在 CONTAINS 用例 PASS、EXACT 用例 FAIL
   （`ASSERTION_EXACT_MISMATCH`），且每用例只执行一次，排除“仍按 EXACT 判”的假通过。
   该测试复用既有真实库 `EvaluationService` 基座（`@EnabledIfEnvironmentVariable(AGENT_DB_PASSWORD)`），
   未新增 Spring 上下文。

补充：`omittedAssertionStaysAbsentSoEvaluationInterpretsExact` 明确断言缺失时回读**没有**
`assertion` 键；`TestInputPromotionPostgresTest` 另有 3 条组合测试覆盖 engine 侧 payload 与
跨服务错误码（其中一条内部同时验证“字符串模式透传”与“缺失不补键”）。

## 4. 回滚与开关

- 无迁移、无新依赖、无配置项。回滚只需 `git checkout --` 三个主文件
  （`monitor/.../DatasetService.java`、`engine-runtime/.../TestInputService.java`、
  `engine-runtime/.../TestInputClient.java`）并删除四个新增/扩充测试（或直接 revert 提交）。
- 已按新契约写入的用例（含 canonical `assertion`）**不需要数据回滚**：读取路径 `get()` 原样
  返回 JSON，`EvaluationService` 也早已按该字段判分；回滚后只是新提升不再接受断言字段。
- 没有新增运行时开关。既有“断言总开关”（`EvaluationService` 构造参数
  `assertionsEnabled`/`exactOnly`，测试里 `new EvaluationService(..., true, false)` 打开）
  语义未改；需要注意：若评测侧关闭断言，已保存的 CONTAINS 仍会被按 EXACT 执行——这是该开关
  原有行为，本次未改变，也未把保存校验与评测开关联动。
- 回滚 `TestInputClient` 只影响错误码精度：提升失败重新变成
  `TEST_INPUT_DEPENDENCY_REJECTED`，不影响已存数据与评测结果。

## 5. 未覆盖

- **未跑真实 monitor HTTP 提升端点**：`DatasetHttpTest` 因既有的 Spring 测试夹具 Bean
  override 冲突（Y-4 类身份问题，3 Errors）无法启动，本任务不修；monitor 侧用
  `DatasetServiceTest` 直接调 application 层，engine 侧用桩 HTTP 验证协议与 code 透传。
  真实网关（JWT、tenant 解析、幂等冲突）没有在本任务重新端到端跑。
- **提升 → 评测未走同一条真实 HTTP 链路**：第 5 条按任务书允许的拆分方式，用直接构造的数据集
  用例调用 `EvaluationService`，而不是“提升写库后再评测”。两侧分别有真实 PG + 真实客户端，
  但一次调用贯穿两个服务的集成测试未做。
- `NUMERIC_TOLERANCE` 的**判分**差异（tolerance 生效）未在提升链路做端到端验证，只验证了存库
  canonical 形状；判分逻辑由既有 `EvaluationAssertionsTest`/`EvaluationAssertionServiceTest` 覆盖。
- 安全用例允许 `EXACT`/`SCHEMA` 的正向路径只依赖既有契约单测；本次提升层只测了
  `safety=true + CONTAINS` 的拒绝。
- 未知模式（`EVALUATION_ASSERTION_UNKNOWN`）未在提升层单测；非法/多余键已覆盖。
- 前端未做浏览器 E2E；仅依据 `NamedInputs.vue` 已发送 `assertion` 的既有实现判断联通。
- Windows 控制台把中文错误文案显示为 GBK 乱码，测试只断言 code 与英文/结构化片段；接口 JSON
  本身是 UTF-8。
