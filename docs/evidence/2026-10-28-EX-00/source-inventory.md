# EX-00 源码、迁移与入口盘点（AP）

日期：2026-10-28。执行者：`ex00_source_inventory`（独立只读盘点；作者身份不计本卡独立评审）。本报告只证明当前源码与文件清单；没有执行 Maven、PG、HTTP、npm/pnpm、启停服务或产品验收。真实基线、失败方法、跳过、XML 与退出码由本卡 README/采集证据另行记录，本文不引用历史测试数字冒充本轮通过。

## 范围与起始状态

- 根目录：`G:/207/项目/算法重组工具/代码/agent-platform`。已读 `AGENTS.md:3`、`docs/implementation-brief.md:1`、`contracts/README.md:1`；遵守新工程隔离、跨服务 HTTP、PG 权威源及冻结契约约束。未使用 superpower。
- `git rev-parse HEAD`：`ebbfd62ca1d19efc4d172804efb5ac79453ceb64`；分支：`feat/v1.2-implementation`。
- 起始 `git status --short` 仅有未跟踪 `docs/evidence/acceptance-audit-20261026/`、`docs/evidence/batch-1-usability-20261026.md`、`output/`。它们属于本轮前已有内容，具体作者未核定，本盘点不接管、不删除、不读其运行数据。起始无已跟踪修改。
- 本子任务唯一 AP 写入为本文件；同卡其他证据由主执行者/采集者负责。没有修改产品源码、入口脚本、真实 `.env`、索引或提交。没有读取真实环境文件/密钥。

## 迁移盘点与认领边界

直接枚举各 reactor 模块的 `src/main/resources/db/migration/V*.sql`，不把 `docs/evidence` 中的历史镜像当现行源码。`pom.xml:7` 定义七模块。

| 模块 | 当前目录版本/文件数 | 最大版本与源码锚点 | 判断 |
| --- | --- | --- | --- |
| admin-common | 无 V 文件 / 0 | — | 公共模块无独立迁移 |
| platform-gateway | 无 V 文件 / 0 | `src/main/resources/application.yml:20` | 配置 gateway schema，但当前目录无 V 文件 |
| app-management | V100～V107 / 8 | `app-management/src/main/resources/db/migration/V107__identity_mapping.sql:1` | 区间内无缺号、无同目录重复数字版本 |
| engine-runtime | V200～V221、V223 / 23 | `engine-runtime/src/main/resources/db/migration/V223__engine_quota_window.sql:1` | V222 不在当前目录；仅记历史事实，不能据此判断漏实现或补写旧 V |
| skill-registry | V300～V315 / 16 | `skill-registry/src/main/resources/db/migration/V315__skill_semantic_digest.sql:1` | 区间内无缺号、无同目录重复数字版本 |
| model-gateway | V300～V306 / 7 | `model-gateway/src/main/resources/db/migration/V306__fixture_audit.sql:1` | 区间内无缺号、无同目录重复数字版本 |
| monitor | V500～V505 / 6 | `monitor/src/main/resources/db/migration/V505__evaluation_semantic_digest.sql:1` | 区间内无缺号、无同目录重复数字版本 |

skill/model 的 V300～V306 是跨模块同号，当前分别使用独立 `skill`/`model` schema 与各自 `classpath:db/migration`，源码依据为两模块 `application.yml:21`/`:23`；不能当成同一 Flyway 历史冲突。app/engine/monitor 分别见其 `application.yml:16`/`:28`/`:16`。数据库实际已应用版本与 checksum 本盘点未连接核对。

后续卡开工必须重新读取所属模块真实目录并协调认领，不能把本表最大号加一当长期预约，也不能沿用 EX-15a 的历史“V318预留”。不改已应用 V 文件，不补占 V222。引擎已有 `EngineMigrations.java:25` 的 classpath 枚举及 `EngineMigrationsTest.java:16` 的实际最大号检查，不能用固定旧数量替代当次目录。

## 测试模块与选择器

| reactor 模块 | 当前 `*Test.java` 文件数 | 模块内同名类 | 其他 Surefire 常见命名 |
| --- | ---: | --- | --- |
| admin-common | 34 | 无 | 无额外 Test*/\*Tests/\*TestCase |
| platform-gateway | 27 | 无 | 同左 |
| app-management | 23 | 无 | 同左 |
| engine-runtime | 93 | 无 | 同左 |
| skill-registry | 70 | 无 | 同左 |
| model-gateway | 22 | 无 | 同左 |
| monitor | 9 | 无 | 同左 |
| 合计 | 278 | — | 无 \*IT/\*ITCase 文件 |

清单单位是源码文件/顶层测试类，不是测试方法或本轮执行数。已按每个模块 `src/test/java` 同时检查 `Test*.java`、`*Test.java`、`*Tests.java`、`*TestCase.java` 与 IT 命名；非测试命名 Java 中未发现 JUnit 测试注解。精确模块选择器见后附清单，调用时使用手册 `Test-AP <模块> '<逗号类名>'`；清单不含历史镜像、辅助 fixture/Main。`engine-runtime/src/test/scripts/TestInputMigrations.java` 与 `test-input-e2e.mjs` 是手工脚本，不由当前 Maven 常规测试源入口自动发现。

检查根和七个子 POM：无仓内 surefire/failsafe 的额外 includes/excludes、testSourceDirectory、build-helper 测试源、skipTests/maven.test.skip 配置；根继承 Boot 3.5.0（`pom.xml:4`），只有 enforcer 显式插件（`:9`）。本盘点没有运行 effective-pom，继承插件的实际版本与执行情况仍以本轮构建日志/XML为准。`scripts/test.ps1:26`～`:29` 的旧模块模式会带 `-am`，本轮按手册逐模块无 `-am`，先 install 当前依赖，不能让上游失败掩盖目标模块未执行。

源码未发现 `@Disabled`，但存在环境门控：`AGENT_DB_PASSWORD`、`ENGINE_PG_TEST`、`AGENT_REDIS_PASSWORD`、`AGENT_MINIO_ENDPOINT`、`AGENT_SPIKE_JDK8`。具体声明位置附后。`ServiceLifecycleTest.java:56` 仅在 Windows 运行；`LocalSpikeLifecycleLiveTest.java:31` 依赖 JDK8，`:52`～`:54` 还检查本仓算法夹具文件，`:177` 可抛 TestAbortedException。配置缺失可能跳过，也可能在未门控夹具中失败，不能仅设置 ENGINE_PG_TEST 就推断全部执行。

AP 端口/存储限制已有源码：`EngineTestDatabase.java:16` 强制 URL 含 `:25432/`，`:34` 选独立 `agent_platform_engine_test`；`AlgorithmTestDatabase.java:25`～`:43` 只准两算法专用库并固定本机25432；Redis 用例见 `ChunkBusRedisTest.java:30` 与 `ModelQuotaRedisTest.java:25` 的26379。其它 PG 测试也存在显式本机25432库连接（例如 `PublishPinTest.java:56`）。本盘点不验证端口占用/库是否就绪，不把 OR BASE/P4 策略套到 AP。

前端从 `web/package.json:15` 执行 `vitest run`，`:16` 的 build 包含 vue-tsc 与三个 Vite 产物；当前跟踪源码有37个 `.test.ts`（均在 `web/tests/`），无额外 `.spec.*`。`web/shared/controlled-test.ts` 是生产辅助模块，名称不属于 `.test.ts`；不计测试。未运行前端命令，实际发现/通过数待真实基线。

## 第一批卡片的源码入口与调用方

| 卡 | 已证实入口/调用方 | 当前源码事实与边界 |
| --- | --- | --- |
| EX-01 | `scripts/start-backend.ps1:1`、`:6`、`:35`、`:55`；调用方 `scripts/dev-up.ps1:43` | 仅 Services/SkipBuild 参数；全局载入一份 .env，随后各服务继承当前进程环境；尚无 ServiceEnvironmentFiles 与逐服务覆盖恢复。`scripts/Test-ServiceEnvironment.ps1` 不存在，属于拟新增产物。 |
| EX-01 配置 | app `application.yml:35`/`:37` 与 engine `application.yml:62`/`:64`；读取方 `ReleasePins.java:54` 与 `OntologyContextPacks.java:82` | app/engine共用 AGENT_ONTOLOGY_URL/API_KEY_REF。engine quota 三阈值已有环境占位符（yml:74～79）；gateway 限流仍为 yml:33～36 的字面默认值。skill schema mode/预算见 yml:33～36。`.env.example:2`～`:20` 尚未覆盖这些开关。 |
| EX-01 安全约束 | `ProcessAdapter.java:133`；`ServiceTickets.java:106`/`:131` | PROCESS启用且白名单空会拒绝构造；AUTHORITY允许 provider 缺席装配但签发时失败，不回落 LEGACY。真实服务配置隔离仍待 EX-01 验证。 |
| EX-02 | `app-management/src/main/java/cn/agentplatform/app/infra/ReleasePins.java:75`～`:95`；调用方 `AppService.java:1608`/`:1706` | 读取/校验 space，却只序列化 platformTenant/appCode/environment/releaseId 四字段。OR拒绝缺空间的依据见OR报告。存在 `src/test/java/cn/agentplatform/app/infra/ReleasePinsTest.java` 与 `.../application/PublishPinTest.java`；当前HTTP真实400/修复后200未由本盘点执行。 |
| EX-03 | `OntologyImportController.java:21`/`:33` → `OntologyManifestImports.java:43`/`:63` → `SkillCatalog.create` | 导入器`:111`～`:134`只在非空时复制authRef，未生成authScheme；声明说明仍把authRef视为可选。SkillCatalog`:453`归一化调用 `AuthScheme.registration`（`:87`～`:95`），兼容缺省BEARER；`SkillAdapters.java:558` 调用统一headers。推导出错鉴权风险，但本盘点未跑出站。 |
| EX-04 | `OntologyManifestImports.java:74`～`:87`，后续 `SkillRules`/`SkillCatalog`；`ToolOutputPolicy.java:108`～`:131` | 现导入器硬要求HTTP/SYNC/POST，未映射binding_ref/exposure。OR现分页为limitParam/offsetParam/maxLimit/maxRows，AP读取request.page/size与response paths/maxPageSize，必须先冻结转换表；不能直接复制对象。新增OntologyProjectionImportTest尚未存在。 |
| EX-05 | `scripts/verify-contracts.mjs`、`contracts/skill-invocation.md`；OR OpenAPI/生成client见OR报告 | 属后续依赖EX-02/03/04的同步卡；本盘点未执行校验器，也未改冻结正文。 |
| EX-06/07 | 拟新增 `scripts/acceptance/Prepare-Joint.ps1`、`Invoke-JointAcceptance.ps1`、`Invoke-S1Acceptance.ps1` | 三脚本当前均不存在；不能调用或把服务就绪当READY release/凭据/真实J1～J9验收。 |

以上 Java 简名均位于卡片指定包；精确路径在后附测试清单及卡片白名单中。仅做范围核实，没有提前实现或关闭卡。

## 三主入口复核与待证事实

只读对照 `文档/优化方案/优化台账-统一-20261028.md:565`（§14）、`执行卡片-统一补差-20261028.md:19`～`:66`、`联合验收与回归执行手册-20261028.md:5`～`:70` 及其测试函数/分组段。EX-00先安装再逐模块、P4端口前置拒绝、旧OR脚本不用作分库入口、EX-01参数待实现、EX-06脚本待新增均与源码相符。未发现会使本轮EX-00必然执行失败的新入口矛盾；真实环境/构建可能失败，不能由源码检查排除。EX-04字段差异已报主执行者，按其设计门处理，不据此扩大本卡实现。

待真实基线证明：当前依赖是否编译/安装；每模块当次XML覆盖源清单；方法级失败/错误/跳过与未执行原因；外部夹具前提；前端真实发现/运行数；PG实际迁移状态。本文没有“通过模块”“零新增失败”或EX-00完成结论。后续认领还须重读HEAD/脏文件和迁移目录。回滚本盘点只涉及本报告，既有证据不回滚。

## 附：当次源码选择器与门控位置

以下清单由实际 reactor 模块 `src/test/java` 生成；仅枚举，不执行测试。


### admin-common — 34类

源码范围：admin-common/src/test/java（各文件类声明可直接定位）；模块选择参数：-pl admin-common。

```text
ActorSubjectSnapshotTest,AuthorityConfigurationTest,AuthorityContextRetainedTest,AuthorityPolicyTest,AuthSchemeTest,CommonBoundaryTest,CurrentAuthorityClientTest,EngineConsoleCatalogTest,GatewayEventsIngressBoundaryTest,GatewayIngressBoundaryTest,GatewayPortalEventsAuthorityTest,GatewayRunAuthorizationTest,GatewayStatusAuthorizationTest,IntegrationClaimsTest,LocalRunAdvanceAuthorityTest,MetadataSelectorsTest,PolicyRequestVerifierTest,PortalClaimMapperTest,PortalEventsAuthorityProfileTest,PortalEventsChildAssociationTest,PortalEventsReceiverClosedTest,PortalSessionAuthorizationTest,ResponseAuthorityTest,RetainedAuthorizationTest,RouteCoverageVerifierTest,RunObservationSelectorsTest,RunWakeCatalogTest,SchemaDialectTest,SecretVaultTest,ServiceAuthorizationTest,ServiceTicketProviderTest,ServiceTokensDeploymentModeTest,SharedSchemaAdmissionTest,TrustedJwtDecoderTest
```

### platform-gateway — 27类

源码范围：platform-gateway/src/test/java（各文件类声明可直接定位）；模块选择参数：-pl platform-gateway。

```text
GatewayPortalEventsServletTest,GatewayPublishedChainPostgresTest,GatewayPublishedRunProxyTest,GatewayRunStatusChainPostgresTest,IntegrationPolicyTest,IntegrationRateLimitTest,IntegrationSecurityTest,PortalEventsMaterialAuthorityTest,PortalEventsMaterialVerifierTest,PortalEventsPacketBudgetTest,PortalEventsPacketDecoderTest,PortalEventsTlsDebtTest,PortalEventsTlsTest,PortalRunEventsHttpTest,PortalRunEventsJoinedChainTest,PortalRunEventsPrivateAuthorityTest,PortalRunEventsPrivateHttpTest,PortalRunEventsSseAutomaticCleanupTest,PortalRunEventsSseLifecycleTest,PortalRunEventsSseResetCleanupTest,PortalRunEventsSseTest,PortalSessionHttpTest,PublishedMetadataClientTest,PublishedMetadataSmokeLauncherTest,RoutesTest,RunObservationEventsMetadataTest,RunObservationGetTransportTest
```

### app-management — 23类

源码范围：app-management/src/test/java（各文件类声明可直接定位）；模块选择参数：-pl app-management。

```text
AppCopyConcurrencyTest,AppDigestTest,ApplicationLifecycleTest,AppRulesTest,AppRulesUnionTypeTest,AppSchemaDialectTest,AuditLinkPostgresTest,DataHealthHttpTest,DataHealthPostgresTest,DraftMetadataHttpTest,IdentityMappingPostgresTest,IntegrationPolicyDatabaseTest,IntegrationPolicyResolverHttpTest,IntegrationRulesTest,PolicyResolverSecurityTest,PublishedMetadataHttpTest,PublishedPayloadHttpTest,PublishGateTest,PublishPinAuditPostgresTest,PublishPinTest,PublishTransactionTest,ReleasePinsTest,TemplateDigestTest
```

### engine-runtime — 93类

源码范围：engine-runtime/src/test/java（各文件类声明可直接定位）；模块选择参数：-pl engine-runtime。

```text
ActionPlanPostgresTest,AgentLoopBudgetPostgresTest,ChatSessionsTest,ChunkBusRedisTest,ChunkBusTest,ContextAssemblerTest,ContextPackTextTest,ContextProjectionTest,ControlledDraftSkillTest,ControlledReplayTest,DebugControlsTest,EngineContextPostgresTest,EngineErrorSurfacePostgresTest,EngineMigrationsTest,ExecutionRecoveryTest,ExternalJobStateTest,FlowMaterialDatabasePreflightTest,FlowMaterialScopePostgresTest,FlowMaterialStatusPostgresTest,FlowMaterialTransactionsPostgresTest,FrozenModelsTest,GraphRulesTest,GraphValidatorTest,InitialChatAdmissionHttpPostgresTest,InitialChatObservationPostgresTest,InitialMaterialScopePostgresTest,InitialSenderFactsPostgresTest,InitialSenderIntegrationTest,InputDeclarationSchemaPositionsTest,InputDeclarationsTest,JsonGateTest,ModelExchangeTest,ModelInputMaterialHttpTest,ModelInputMaterialPostgresTest,OntologyBindingResolutionTest,OntologyContextPacksTest,OutputPolicyPostgresTest,OutputPolicyTest,OwnedFlowMaterialsPostgresTest,OwnedFlowSchedulerPostgresTest,PortalChildEventsHttpTest,PortalChildEventsPostgresTest,PortalEventsPacketCodecTest,PortalRunEventsCapacityTest,PortalRunEventsHttpTest,PortalRunEventsLifecycleTest,PortalRunEventWindowPostgresTest,PromptFreezeDigestTest,PublishedAdmissionHttpPostgresTest,ReplayBoundaryTest,ReplayServiceTest,RunAdmissionBoundaryPostgresTest,RunAdmissionHttpTest,RunAuthorityDatabasePreflightTest,RunAuthorityLocalCurrentPostgresTest,RunAuthorityPersistencePostgresTest,RunDiagnosticsTest,RunEnvironmentAnnotationTest,RunEnvironmentPostgresTest,RunEventReaderBudgetTest,RunEventReaderPostgresTest,RunEventReaderTimeoutBudgetPostgresTest,RunEventResponseTest,RunEventsHttpTest,RunEventsPostgresTest,RunInputValidationPostgresTest,RunObservationMetadataHttpPostgresTest,RunObservationStatusHttpPostgresTest,RunOperationsTest,RunOrphanConvergencePostgresTest,RunQuotaAdmissionPostgresTest,RunQuotaPostgresTest,RunSchedulerPostgresTest,RunSchedulerReviewPostgresTest,RunSchedulerTimingTest,RunServiceContextPackTest,RunStatisticsPostgresTest,RuntimeClientsPublishedHttpTest,RuntimeStreamTest,RunToolProfileTest,RunWakeServicePostgresTest,SharedSchemaBudgetTest,SkillErrorSurfaceTest,TestInputClientTest,TestInputHttpTest,TestInputPromotionPostgresTest,ToolCancellationRaceTest,ToolInterceptorChainTest,ToolInterceptorWiringPostgresTest,ToolOutputLargeResponsePostgresTest,ToolOutputPolicyTest,TracePostgresTest,ValuesTest
```

### skill-registry — 70类

源码范围：skill-registry/src/test/java（各文件类声明可直接定位）；模块选择参数：-pl skill-registry。

```text
AdapterRegistryZeroChangeTest,AlgorithmExecutionPostgresTest,AlgorithmMigrationPostgresTest,AlgorithmOffloadInterruptionTest,AlgorithmProtocolTest,ArtifactBoundTest,ArtifactPersistenceTest,ArtifactReferenceTest,AsyncHttpExecutionTest,AsyncResultEndpointFailureTest,AuthSchemeOutboundTest,BoundedSchemaValidationTest,CallbackHttpTest,ErrorSemanticsTest,ExternalJobStateTest,FixtureSkillsTest,HttpAsyncAdapterTest,HttpAsyncAttemptFenceTest,HttpSchemaBoundaryTest,IdentityOutboundTest,LocalSpikeLifecycleLiveTest,LocalSpikeManagerFailurePathTest,McpAdapterTest,McpExecutionPostgresTest,McpManagementTest,McpResourceLifecycleTest,McpSchemaBoundaryTest,MultipartFormMappingTest,MultipartStreamingTest,OntologyManifestImportsTest,OpenApiHttpMappingTest,OpenApiImportControllerTest,OpenApiImportPersistenceTest,OpenApiImportsTest,PathParamsAsyncMappingTest,ProcessAdapterHardeningTest,ProcessAdapterTest,ProcessContractTest,ProcessProtocolTest,ProcessPublishPrecheckTest,ProcessTreeTest,ReconciliationProofTest,SchemaDegradedValidationTest,SchemaWorkBudgetTest,ServiceLifecycleTest,ServiceManifestTest,ServiceProbeTest,ServiceTicketsTest,SignedCallbackTest,SkillAttemptPropagationTest,SkillAuthSchemeRegistrationTest,SkillCallbackWakeTest,SkillClientsErrorTest,SkillDigestTest,SkillErrorEnvelopeTest,SkillEvidenceRebindPostgresTest,SkillExecutionTest,SkillIdentityRegistrationTest,SkillPrepareExecuteTest,SkillProcessAuditTest,SkillProcessExecutionTest,SkillProcessRegistrationTest,SkillProcessRoutingTest,SkillProcessWhitelistRegistrationTest,SkillPublishDigestPostgresTest,SkillRulesTest,SkillSchemaDialectTest,SkillStatisticsPostgresTest,SkillTaskResultTest,TaskResultControllerTest
```

### model-gateway — 22类

源码范围：model-gateway/src/test/java（各文件类声明可直接定位）；模块选择参数：-pl model-gateway。

```text
EngineModelInputManifestClientHttpTest,FixtureIsolationTest,FrozenInitialInputMeterHttpTest,FrozenInitialInputMeterTest,FrozenInitialPromptHttpTest,FrozenInitialPromptTest,FrozenInitialWireGuardHttpTest,FrozenInitialWireGuardTest,FrozenModelBindingConsumerHttpTest,FrozenModelBindingStorePostgresTest,FrozenModelBindingVerifierTest,InitialModelInputConsumerHttpTest,InitialModelInputVerifierTest,InitialStreamIntegrationTest,InitialStreamSendFactsPostgresTest,ModelCallsPostgresTest,ModelNoSendStatisticsPostgresTest,ModelPolicyTest,ModelQuotaPostgresTest,ModelQuotaRedisTest,ModelStatisticsPostgresTest,ModelStoreCapabilitiesTest
```

### monitor — 9类

源码范围：monitor/src/test/java（各文件类声明可直接定位）；模块选择参数：-pl monitor。

```text
DatasetHttpTest,DatasetServiceTest,EvaluationAssertionServiceTest,EvaluationAssertionsTest,EvaluationPersistenceTest,EvaluationRulesTest,EvaluationSemanticDigestTest,OperationsRulesTest,StatisticsPersistenceTest
```

### 条件启用注解的完整位置

只记录源码声明，不读取相应环境值；同一类也可能有方法级门控。

| 源码路径:行 | 声明 |
| --- | --- |
| `app-management/src/test/java/cn/agentplatform/app/api/DataHealthHttpTest.java:36` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `app-management/src/test/java/cn/agentplatform/app/application/AppCopyConcurrencyTest.java:20` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `app-management/src/test/java/cn/agentplatform/app/application/ApplicationLifecycleTest.java:27` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `app-management/src/test/java/cn/agentplatform/app/application/DataHealthPostgresTest.java:19` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `app-management/src/test/java/cn/agentplatform/app/application/DraftMetadataHttpTest.java:19` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `app-management/src/test/java/cn/agentplatform/app/application/PublishGateTest.java:32` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `app-management/src/test/java/cn/agentplatform/app/application/PublishPinAuditPostgresTest.java:37` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `app-management/src/test/java/cn/agentplatform/app/application/PublishPinTest.java:35` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `app-management/src/test/java/cn/agentplatform/app/application/PublishTransactionTest.java:25` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `app-management/src/test/java/cn/agentplatform/app/application/TemplateDigestTest.java:26` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/application/RunStatisticsPostgresTest.java:25` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ActionPlanPostgresTest.java:23` | `@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/AgentLoopBudgetPostgresTest.java:22` | `@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ChatSessionsTest.java:15` | `@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ChunkBusRedisTest.java:14` | `@EnabledIfEnvironmentVariable(named = "AGENT_REDIS_PASSWORD", matches = ".+")` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ControlledDraftSkillTest.java:16` | `@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ControlledReplayTest.java:15` | `@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/DebugControlsTest.java:13` | `@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/EngineContextPostgresTest.java:28` | `@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/EngineErrorSurfacePostgresTest.java:27` | `@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ExecutionRecoveryTest.java:15` | `@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ExternalJobStateTest.java:20` | `@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelExchangeTest.java:14` | `@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/OutputPolicyPostgresTest.java:23` | `@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ReplayBoundaryTest.java:15` | `@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ReplayServiceTest.java:13` | `@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunAdmissionBoundaryPostgresTest.java:16` | `@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunAdmissionHttpTest.java:32` | `@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunEnvironmentPostgresTest.java:23` | `@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunEventsPostgresTest.java:24` | `@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunInputValidationPostgresTest.java:23` | `@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunOrphanConvergencePostgresTest.java:19` | `@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunQuotaAdmissionPostgresTest.java:28` | `@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunQuotaPostgresTest.java:31` | `@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunSchedulerPostgresTest.java:18` | `@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunSchedulerReviewPostgresTest.java:24` | `@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunWakeServicePostgresTest.java:17` | `@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/TestInputHttpTest.java:28` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/TestInputPromotionPostgresTest.java:35` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ToolCancellationRaceTest.java:16` | `@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ToolInterceptorWiringPostgresTest.java:22` | `@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ToolOutputLargeResponsePostgresTest.java:26` | `@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/TracePostgresTest.java:17` | `@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")` |
| `skill-registry/src/test/java/cn/agentplatform/skill/application/AsyncHttpExecutionTest.java:31` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `skill-registry/src/test/java/cn/agentplatform/skill/application/AsyncResultEndpointFailureTest.java:33` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `skill-registry/src/test/java/cn/agentplatform/skill/application/CallbackHttpTest.java:43` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `skill-registry/src/test/java/cn/agentplatform/skill/application/ExternalJobStateTest.java:36` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `skill-registry/src/test/java/cn/agentplatform/skill/application/FixtureSkillsTest.java:30` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `skill-registry/src/test/java/cn/agentplatform/skill/application/OpenApiImportPersistenceTest.java:20` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `skill-registry/src/test/java/cn/agentplatform/skill/application/PathParamsAsyncMappingTest.java:39` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `skill-registry/src/test/java/cn/agentplatform/skill/application/ReconciliationProofTest.java:35` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `skill-registry/src/test/java/cn/agentplatform/skill/application/ServiceLifecycleTest.java:29` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `skill-registry/src/test/java/cn/agentplatform/skill/application/SkillCallbackWakeTest.java:41` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `skill-registry/src/test/java/cn/agentplatform/skill/application/SkillErrorEnvelopeTest.java:35` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `skill-registry/src/test/java/cn/agentplatform/skill/application/SkillEvidenceRebindPostgresTest.java:38` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `skill-registry/src/test/java/cn/agentplatform/skill/application/SkillExecutionTest.java:22` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `skill-registry/src/test/java/cn/agentplatform/skill/application/SkillExecutionTest.java:710` | `@EnabledIfEnvironmentVariable(named = "AGENT_MINIO_ENDPOINT", matches = ".+")` |
| `skill-registry/src/test/java/cn/agentplatform/skill/application/SkillPrepareExecuteTest.java:30` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `skill-registry/src/test/java/cn/agentplatform/skill/application/SkillProcessAuditTest.java:35` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `skill-registry/src/test/java/cn/agentplatform/skill/application/SkillProcessExecutionTest.java:33` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `skill-registry/src/test/java/cn/agentplatform/skill/application/SkillPublishDigestPostgresTest.java:32` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `skill-registry/src/test/java/cn/agentplatform/skill/application/SkillStatisticsPostgresTest.java:25` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `skill-registry/src/test/java/cn/agentplatform/skill/application/SkillTaskResultTest.java:33` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `skill-registry/src/test/java/cn/agentplatform/skill/infra/ArtifactPersistenceTest.java:23` | `@EnabledIfEnvironmentVariable(named = "AGENT_MINIO_ENDPOINT", matches = ".+")` |
| `skill-registry/src/test/java/cn/agentplatform/skill/infra/HttpAsyncAttemptFenceTest.java:29` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `skill-registry/src/test/java/cn/agentplatform/skill/infra/McpExecutionPostgresTest.java:27` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `skill-registry/src/test/java/cn/agentplatform/skill/infra/McpExecutionPostgresTest.java:452` | `@EnabledIfEnvironmentVariable(named = "AGENT_MINIO_ENDPOINT", matches = ".+")` |
| `skill-registry/src/test/java/cn/agentplatform/skill/infra/McpManagementTest.java:35` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `skill-registry/src/test/java/cn/agentplatform/skill/infra/localspike/LocalSpikeLifecycleLiveTest.java:31` | `@EnabledIfEnvironmentVariable(named = "AGENT_SPIKE_JDK8", matches = ".+")` |
| `model-gateway/src/test/java/cn/agentplatform/model/application/FixtureIsolationTest.java:32` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `model-gateway/src/test/java/cn/agentplatform/model/application/ModelCallsPostgresTest.java:22` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `model-gateway/src/test/java/cn/agentplatform/model/application/ModelNoSendStatisticsPostgresTest.java:27` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `model-gateway/src/test/java/cn/agentplatform/model/application/ModelQuotaPostgresTest.java:21` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `model-gateway/src/test/java/cn/agentplatform/model/application/ModelQuotaRedisTest.java:16` | `@EnabledIfEnvironmentVariable(named = "AGENT_REDIS_PASSWORD", matches = ".+")` |
| `model-gateway/src/test/java/cn/agentplatform/model/application/ModelStatisticsPostgresTest.java:24` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `monitor/src/test/java/cn/agentplatform/monitor/application/DatasetHttpTest.java:19` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `monitor/src/test/java/cn/agentplatform/monitor/application/DatasetServiceTest.java:29` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `monitor/src/test/java/cn/agentplatform/monitor/application/EvaluationAssertionServiceTest.java:30` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `monitor/src/test/java/cn/agentplatform/monitor/application/EvaluationPersistenceTest.java:26` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `monitor/src/test/java/cn/agentplatform/monitor/application/EvaluationSemanticDigestTest.java:31` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |
| `monitor/src/test/java/cn/agentplatform/monitor/application/StatisticsPersistenceTest.java:24` | `@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")` |

### 前端测试源文件

- `web/tests/approval.test.ts`
- `web/tests/artifact.test.ts`
- `web/tests/assertion-editor.test.ts`
- `web/tests/chunk.test.ts`
- `web/tests/control.test.ts`
- `web/tests/controlled-test.test.ts`
- `web/tests/core.test.ts`
- `web/tests/draft.test.ts`
- `web/tests/drafts.test.ts`
- `web/tests/error.test.ts`
- `web/tests/evaluation-observer.test.ts`
- `web/tests/evaluation-panel-review-fixes.test.ts`
- `web/tests/expiry.test.ts`
- `web/tests/frontend-query-state.test.ts`
- `web/tests/graph.test.ts`
- `web/tests/input-form.test.ts`
- `web/tests/login.test.ts`
- `web/tests/mcp-registration.test.ts`
- `web/tests/mock-rules.test.ts`
- `web/tests/mock-transport.test.ts`
- `web/tests/node-form.test.ts`
- `web/tests/openapi-import.test.ts`
- `web/tests/operations.test.ts`
- `web/tests/output.test.ts`
- `web/tests/permission.test.ts`
- `web/tests/portal-auth.test.ts`
- `web/tests/publish-gate.test.ts`
- `web/tests/run-observer.test.ts`
- `web/tests/run-protocol.test.ts`
- `web/tests/schema-dialect.test.ts`
- `web/tests/service-access.test.ts`
- `web/tests/service-admin.test.ts`
- `web/tests/session.test.ts`
- `web/tests/statistics-lifecycle.test.ts`
- `web/tests/stream-auth.test.ts`
- `web/tests/stream.test.ts`
- `web/tests/validation.test.ts`
