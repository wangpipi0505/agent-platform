# G1 输出策略两项 P2 修复证据

本次处理 `review-output-policy.md` 的 G1-OP-01、G1-OP-02。唯一根目录为 `G:/207/项目/算法重组工具/代码/agent-platform`；已读 AGENTS、implementation brief、contracts README、原实现证据、独立评审及 `.local/reviews/g1-output.json`。原评审是无 HEAD 的源快照，本报告不将工作目录假称为 Git diff。

生产修改仅限 OutputPolicy、ModelExchange，测试仅限其两个现有输出策略测试文件；新增文档仅本报告。未修改统计、H1、其他驱动、迁移或依赖版本，未提交 Git，未启停业务服务。全套测试中的 Spring 测试上下文使用独立 engine 测试数据库，不是部署或业务服务操作。独占 engine Maven 窗口已在 2026-10-20 04:54:04 +08:00 全套完成后释放。

## G1-OP-01：按 Schema 位置识别引用

`OutputPolicy.schemaLocations` 区分三种结构：Schema 对象、Schema map 的各项值、Schema array 的各项值。只有真正的 Schema 对象检查 `$ref`、`$dynamicRef`、`$recursiveRef`；`properties` 等 map 的键是业务名称，`const`、`enum`、`examples`、`default` 内容保留为实例/注解数据。覆盖 `$defs`/definitions、properties/patternProperties、dependentSchemas/dependencies、组合、条件、items/prefixItems、additionalProperties、contains、unevaluated 及 contentSchema 等 Schema 位置。

该遍历只用于安全检查及初始化位置枚举；JSON 类型、约束、组合、锚点、引用解析仍由已安装的 networknt 1.5.6 执行，没有另造 JSON Schema 验证器。真实 Schema 位置里的 HTTP(S)、file、相对引用仍在编译前以 `OUTPUT_POLICY_INVALID` 拒绝。

另外给 networknt 工厂注册拒绝外部加载的 loader。即使本地引用指向原本的数据字面量，而该目标被引用后作为 Schema 执行，也不能落入库默认的文件或网络 loader。未引用的数据字面量中的同名字段可以合法存在。

## G1-OP-02：发送前初始化与已付费用量保护

使用本工程已安装官方 JAR 的 `javap -c/-p` 核实：

- `JsonSchemaFactory.preload` 捕获并忽略初始化 Exception，因此单凭 `getSchema` 成功不能证明引用完整。
- `JsonSchema.initializeValidators` 调用各 validator 的 `preloadJsonSchema`；Ref/DynamicRef/RecursiveRef validator 通过库自己的 JsonSchemaRef 解析引用，并根据祖先位置停止引用环。
- 默认预加载有引用嵌套深度阈值；本次配置为 `Integer.MAX_VALUE`，避免深层无效引用留至模型返回后才解析。
- `DefaultSchemaLoader` 先尝试自定义 loader，再尝试默认 classpath/URI loader；拒绝 loader 在默认 I/O 前抛错。

构造 OutputPolicy 时对根和每个已识别的子 Schema 显式 `initializeValidators`，也检查未触发的可选分支和未引用的 `$defs`。由 networknt 的 `getSubSchema` 保留原文档/资源上下文，支持本地锚点和递归。根路径直接用根 Schema：该版本 `getSubSchema(emptyPath)` 返回 null。初始化失败统一为 `OUTPUT_POLICY_INVALID`，在 ContextProjection、measure、prepare、预算扣减和 chatStream 之前拒绝。

ModelExchange 增加验证器 RuntimeException 保护：先持久化 `semantic=OUTPUT_POLICY_INVALID` 的诊断，再走原有幂等用量记账，最后抛配置错误。它不会进入模型输出格式失败的 PASS/RETRY 分支，也不会为了配置问题产生修正请求。正常的 schema 不匹配仍使用原 `OUTPUT_INVALID` 三策略路径和原预算栅栏。已有响应在恢复时重用，`usage_accounted` 保证成功记账仅一次。

JAR：`.tools/m2/com/networknt/json-schema-validator/1.5.6/json-schema-validator-1.5.6.jar`，SHA256=`5531436b97dfa90d34bb2acbbe65e054a0a24ded1576032b684ee179728f86d4`；SHA1 与本地仓库同文件 `.sha1` 相符。字节码探查留在 `engine-runtime/target/output-policy-library-bytecode.log`，未宣称阅读了未安装的源文件。

## 新增测试与真实红绿

新增 8 个测试：6 个领域测试、2 个真实 PG 测试。

| 测试 | 检查的行为 |
| --- | --- |
| referenceNamedBusinessPropertiesAreNotSchemaKeywords | `$ref`/`$dynamicRef`/`$recursiveRef` 作为业务属性名时可验收；同一 Schema 仍执行 required；同版本 networknt 直接验证也成功 |
| referenceNamedLiteralFieldsRemainData | const/enum/examples/default 中含外部地址和非字符串同名字段是普通数据 |
| unresolvedLocalReferencesFailDuringConstructionEvenInUnevaluatedBranches | 根、optional properties、anyOf 未触发分支和未引用 `$defs` 中缺本地引用均在构造期拒绝 |
| actualSchemaReferencesRejectNetworkFileAndRelativeTargetsInNestedSchemas | 三类引用关键字的 HTTPS、file、relative 目标在嵌套 Schema 中仍拒绝 |
| referencedLiteralCannotActivateAnExternalSchemaLoader | 本地引用将 const 内容作为 Schema 后，外部 file 引用仍不能读取 |
| recursiveLocalSchemasAndNamedAnchorsRemainValid | 递归本地命名锚点可验证，并继续拒绝类型不匹配实例 |
| missingLocalReferenceRejectsBeforeAnyModelHttpOrBudgetDebit | 三种 onInvalid 均在任何 RuntimeClients.post/chatStream 前抛配置错；零调用、零 invocation 行、预算 JSON 完全不变 |
| unexpectedValidatorFailurePreservesDiagnosticAndChargesPaidResponseOnce | 在真实 PG 持久化路径注入 validator 异常，响应、配置错误诊断与 usage_accounted 均保留；恢复无重发，modelCalls=1、tokens=7，仅记一次 |

最后一项用 Mockito construction mock 注入不可预见的验证器异常，仅用来检验付费后的故障窗口；不是 Schema 解释器或供应商能力验收。缺本地引用的构造/发送前测试使用真实 OutputPolicy 和 networknt，不使用 mock。所有模型出站均是测试目录下的受控协议边界，没有向真实模型供应商发送请求。

Java21 路径：`C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot`。从本工程 `.env` 加载独立测试 PG 环境但不打印凭据，设置 `ENGINE_PG_TEST=true`。连接独立 `127.0.0.1:25432/agent_platform_engine_test`，schema engine/V213。

基础命令：

```text
mvn.cmd -B -ntp -pl engine-runtime -am test -Dmaven.repo.local=.tools/m2 -Djdk.net.unixdomain.tmpdir=V:/.local/tmp
```

红测追加：

```text
-Dtest=OutputPolicyTest,OutputPolicyPostgresTest#missingLocalReferenceRejectsBeforeAnyModelHttpOrBudgetDebit+unexpectedValidatorFailurePreservesDiagnosticAndChargesPaidResponseOnce -Dsurefire.failIfNoSpecifiedTests=false
```

定向绿测追加 `-Dtest=OutputPolicyTest,OutputPolicyPostgresTest -Dsurefire.failIfNoSpecifiedTests=false`；全套没有选择器。

| 日志（均在 engine-runtime/target） | 实际结果 |
| --- | --- |
| output-policy-fix-red.log | 04:49:48，10 tests / 5 failures / 0 errors / 0 skipped。合法业务属性/字面量被拒、缺引用构造无错、进入被禁止的模型 HTTP 边界、验证器异常越过记账保护均被捕获；生产修复之前实际运行 |
| output-policy-fix-green.log | 第一次实现检查未通过：误用空根路径 getSubSchema 返回 null，另有异常注入 mock 未提供 onInvalid；保留该失败日志，没有跳过失败用例 |
| output-policy-fix-green-2.log | 04:51:42，28/0/0/0。适配库根路径行为并完善注入夹具后通过 |
| output-policy-fix-targeted-green.log | 04:52:44，30/0/0/0；补充引用到字面量及递归锚点边界后，领域 10 + PG 20 全部通过 |
| output-policy-fix-full-green.log | **04:54:04，111 tests / 0 failures / 0 errors / 0 skipped，exit 0**；逐个读取本次 Surefire XML 独立汇总相同 |
| output-policy-fix-config-probe.log | 已编译类的 jshell 行为探针：未知 format.type、非字符串 type、未知 onInvalid、数组 schema 四项均输出 OUTPUT_POLICY_INVALID，无模型或 PG 请求 |

全套 111 的口径是原 G1 全套 100 + 统计任务已新增的 3 + 本次新增 8；定向复验不重复累计。全套包含 RunStatisticsPostgresTest=5（日志 85–106 行）、ChatSessionsTest=4（118–123 行）、OutputPolicyTest=10（114–115 行）及 OutputPolicyPostgresTest=20（187–193 行）。统计和 H1 源码不属于本次修改。

四个 Java 文件用 GoogleJavaFormat 1.25.2 `--aosp` 格式化；`--dry-run --set-exit-if-changed` 实际 exit 0。Mockito/JDK 在异常注入测试运行时有动态 attach 警告；测试与构建成功，本次没有修改项目 JVM/依赖配置来压制它。

## 定向复核文件清单

| 文件 | 修复后 SHA256 |
| --- | --- |
| engine-runtime/src/main/java/cn/agentplatform/engine/domain/OutputPolicy.java | cebcbaf813d4da4f77e453dbfb2e3d247ead8a31dca522d6f2ac4145696f53de |
| engine-runtime/src/main/java/cn/agentplatform/engine/application/ModelExchange.java | e5f548510993ad64a12a34ce3d0d2701eb48082c88913480e8472c4a99e08b4a |
| engine-runtime/src/test/java/cn/agentplatform/engine/domain/OutputPolicyTest.java | db4e02546dd1b70fdc7b6cd9dc2c42c24af3a7142fb3a12f7270de6f30849e25 |
| engine-runtime/src/test/java/cn/agentplatform/engine/infra/OutputPolicyPostgresTest.java | b93b2fd182eab3084ce80485e224e8f510ffc205f50f249c4d00ce841e644e1d |
| docs/evidence/engine-output-policy-fix.md | 本报告 |

原评审包中其余 6 个文件已逐个重新散列，与原快照相同：pom、AgentDriver、FlowDriver、ControlledReplay、V213、原 engine-output-policy.md。

上述修复和新证据交独立评审定向复核；不将本报告等同于人审关闭或全工程验收。原评审记录的并发/进程崩溃、全元模式/复杂度/格式词汇、真实供应商与前端流式呈现边界维持原有验收范围。
