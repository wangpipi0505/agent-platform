# EX-04 engine 全模块 PostgreSQL 范围静态核查

核查者：独立子智能体 `/root/ex04_pg_scope`，2026-10-29。仅只读当前测试源码、既有 baseline 和本项目 fixture 引用；未运行 Java、Maven、PG、HTTP，未修改产品/测试源码、collector、Git index，不使用 superpower。本报告不代表实际 PG admission。

结论：默认 engine 全模块原测试的固定应用库范围是 **14 个**（主库仅作已知 admin/catalog 连接，另有 `postgres` 系统 catalog 连接）。只列 engine 源码字面量会漏掉原有独立 app 子进程的专库。另有可到达的随机库创建 `timeout_fix_<32hex>`，不能要求这个尚未生成的名字预存在。ROOT 仍须在独占实际窗口核验同一 AP 25432 实例、目录、角色、owner/schema/Flyway；不得因本报告自动创建固定库或清理未知库。

## 固定库与调用链

下表 `infra/`、`application/` 简写均位于 `engine-runtime/src/test/java/cn/agentplatform/engine/`。列的是原程序行为，不是 collector 新增操作。

| 固定库 | schema/用途 | 源码位置及原行为 |
|---|---|---|
| agent_platform_v12 | admin/catalog | `infra/EngineTestDatabase.java:15`、`infra/ModelInputMaterialFixture.java:50`；这些连接不对主库 engine schema 重置/迁移。 |
| agent_platform_engine_test | engine | `infra/EngineTestDatabase.java:21–34`、`infra/TracePostgresTest.java:26–66`；原缺库 CREATE guard、迁移及下文既有清理。 |
| agent_platform_iam_engine_v12 | engine | `infra/RunAuthorityFixture.java:31–38`、`infra/RunAuthorityDatabasePreflightTest.java:10–17`；原缺库 CREATE、target 215 preflight 及正常迁移。 |
| agent_platform_iam_flow_material_v12 | engine | `infra/FlowMaterialDatabasePreflightTest.java:27–60`、`infra/FlowMaterialScopePostgresTest.java:20`；原缺库 CREATE、迁移及故障 trigger。 |
| agent_platform_iam_owned_flow_v12 | engine | `infra/OwnedFlowSchedulerPostgresTest.java:123–137`；校验精确库后，原 fixture 执行 `truncate engine.run restart identity cascade`。 |
| agent_platform_statistics_test | engine | `application/RunStatisticsPostgresTest.java:32–45`；直接连接既有库并迁移，源码没有 CREATE DATABASE。 |
| agent_platform_iam_admission_engine_v12 | engine | `infra/PublishedAdmissionHttpPostgresTest.java:40,192–269`；原缺库 CREATE、迁移及独立 app fixture 子进程。 |
| agent_platform_iam_o2a_c_events_v12 | engine | `infra/PortalRunEventWindowPostgresTest.java:23,29–37`；原缺库 CREATE、迁移及按 fixture run 删除事件。 |
| agent_platform_iam_o2a_b_material_v12 | engine | `infra/RunEventReaderPostgresTest.java:23,29–37`；原缺库 CREATE、迁移。 |
| agent_platform_iam_o1a_metadata_v12 | engine | `infra/RunObservationMetadataHttpPostgresTest.java:46,93–101`；原缺库 CREATE、迁移和故障路径。 |
| agent_platform_iam_o1b_status_v12 | engine | `infra/RunObservationStatusHttpPostgresTest.java:47,144–152`；原缺库 CREATE、迁移和故障路径。 |
| agent_platform_test_input_test | engine | `infra/TestInputHttpTest.java:33,47–63`；原缺库 CREATE，Spring Boot 专用 datasource。 |
| agent_platform_test_input_promotion_test | engine | `infra/TestInputPromotionPostgresTest.java:37,49–63`；原缺库 CREATE、迁移。 |
| agent_platform_iam_admission_app_v12 | app | `app-management/src/test/java/cn/agentplatform/common/authority/PublishedAdmissionFixtureMain.java:33,157–216`；原缺库 CREATE、Flyway target 104。 |

`PublishedAdmissionHttpPostgresTest:269–294` 读取本项目 `.local/iam-n3-admission-app-client/app-classpath.txt`，校验仅有本项目 vendor/app/admin 路径并禁止父 JVM classpath 引入 app-management，然后独立 JVM 执行 `PublishedAdmissionFixtureMain`。此类原 EX-36 baseline 的 20 个方法全部 passed，故专库调用链不是不可达猜测。没有 OR 数据库/业务 JAR；本次不重编译/替换该 fixture 依赖来改变原行为。

## 原有清理和迁移边界

1. `OwnedFlowSchedulerPostgresTest.fixture():135–138` 只在精确 `agent_platform_iam_owned_flow_v12` 专库，经 `RunAuthorityFixture` allowlist/current_database 检查后执行既有 TRUNCATE。只读 collector 不执行该语句；全回归会执行原测试，不能把整轮描述为“无 truncate”。
2. `TracePostgresTest.clean():45–66` 在 `agent_platform_engine_test` 中关闭本测试 scheduler，随后全表 DELETE `engine.reproduction`/`engine.model_invocation`，并按 `engine-test` tenant 删除关联 attempt_note/run_event/tool_call/attempt/node_instance/run。`ModelExchangeTest:25`、`ReplayServiceTest:24` 重复既有表清理；`RunSchedulerReviewPostgresTest:36–38` 还有本专库全表 `chat_turn`/`chat_session` DELETE。不得搬到主库。
3. `RunAdmissionHttpTest`、`RunEnvironmentPostgresTest`、`RunQuota*PostgresTest`、`RunSchedulerPostgresTest` 的其余原清理限定 fixture tenant/run/session。Portal/Observation/Material 测试的 DELETE/ALTER 是原故障路径。`FlowMaterial*`、`OwnedFlowMaterials`、`RunAuthority*`、`RunScheduler*` 会创建并删除自身故障 trigger/function；保留原测试和原失败，collector 不提前移除故障对象。
4. 扫描 `engine-runtime/src/test/java/**/*.java` 未发现 DROP DATABASE、DROP SCHEMA 或 Flyway `clean()`；`fixture.clean()` 是上述测试函数。既有 `.migrate()` 不改源码/版本；精确 schema SQL 历史须实际 admission 核验。

## 动态库与默认失败

| 来源 | 原默认行为 | 本轮要求 |
|---|---|---|
| `RunEventReaderTimeoutBudgetPostgresTest:18–29` | class 初始化产生 `timeout_fix_` + UUID 去横线；:26 CREATE，:28 current_database 校验，:29 缺 `timeout.fix.evidence` 时 Path.of(null) 抛错。 | 不补属性/改 beforeAll；只读记录精确 `^timeout_fix_[a-f0-9]{32}$` 前后集合与 owner，预期只新增本轮原测试的 1 个库。不删除旧匹配库，前缀匹配不构成旧库清理授权。原 class error/3 个源码测试未执行保留。 |
| `ModelInputMaterialFixture:35,50–72` | 对 `iam_model_input_fix_<32hex>` 先查不存在；:57–58 缺 `model.input.registry` 抛 `AssertionError("owned database registry required")`，:72 CREATE 位于其后。 | 默认属性下 CREATE 不可达。不补 registry/evidence、不预创建；原 21 个 Postgres 方法 failure 和 HTTP class failure/11 未执行保持。 |
| `ModelInputConsumerProducerFixture:47,61–97` | helper 可建 `iam_model_input_consumer_fix_<32hex>`，但仅本身 separate-JVM main 构造，默认 engine JUnit 无 caller；缺 `consumer.registry` 也在 CREATE 前拒绝。 | 不启动 main、不补属性，不把此前缀当本轮可创建/清理范围。 |

`ModelInputMaterialPostgresTest:612–620` 确有子 JVM 调用 `ModelInputMaterialFixture.main`，但 main 仅做文件摘要序列化（源码末尾明确不构造 fixture、不连 DB），且父测试先因缺 registry 失败；它不是额外建库通道。

以下 helper 不在默认 engine JUnit 调用图：`GatewayRunStatusEngineFixtureMain`、`GatewayPublishedEngineFixtureMain`、`PortalRunEventsJoinedEngine`、`FrozenModelBindingProducerFixture`、`InitialSenderIntegrationFixture`、`InitialChatAdmissionFixture`、`ModelInputConsumerProducerFixture`。全 `src/test/java` 符号与 `*Test.java` caller 检索未发现 JUnit 入口调用（self main/helper 互调除外）。因此排除 `agent_platform_iam_o1c_gateway_status_v12`、`joined_<24hex>` 及这些 helper 的外部配置库。`src/test/scripts/TestInputMigrations.java` 不在默认 JUnit 源目录/入口，其 `_empty_test`、`_upgrade_test` 库也排除。

## 原 baseline 与回归门槛

- 原件 `docs/evidence/2026-10-28-EX-36/runs/after-module-20261028-230322-842/method-summary/methods.csv`：907 条 = 905 具名身份 + 2 class events，93 XML。
- `collector/engine-baseline-class-events.csv` 保留 `ModelInputMaterialHttpTest` failure、`RunEventReaderTimeoutBudgetPostgresTest` error，不能冒充通过的具名测试。
- `collector/engine-baseline-unexecuted-source.csv` 的 14 行 = HTTP 11 + timeout 3。EX-04 新增 25 具名身份后预期 932 条 = 930 具名 + 2 class events；原未执行 14 不变。原因是否保持仍须实际 XML 比对。
- 本报告不声明当前库实际存在、owner 正确、Maven 全绿或全回归完成；这些结论只能来自实际 admission 和回归。

## 只读输入 SHA-256

下列为静态核查时磁盘原始字节；本报告作者未改源码。

| AP repo 相对路径 | SHA-256 |
|---|---|
| `engine-runtime/pom.xml` | `a31cf052ccc6f9e296e4ebe946c337761cfc2823f71ea2aa536a0d241d41b601` |
| `pom.xml` | `836ba70369072496695e167bf3ab937fe48c7150a5e0b13b7b8d13674ffbcb2c` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/EngineTestDatabase.java` | `770032049f11755a24761279fd5528da98cd8d63bfe4462da7e107440ca62780` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunAuthorityFixture.java` | `4b019691c319ad97684e66420a560ffb266d72a8e1d2813f1972dd06629b74fb` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunAuthorityDatabasePreflightTest.java` | `66f0a84342972026b362d81014d6e1d9d7666ebf70ea2c645a5e872702614495` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/FlowMaterialDatabasePreflightTest.java` | `4e5149a2b82743736397eab48657984a2d9907725968aef05e41f862e7609154` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/OwnedFlowSchedulerPostgresTest.java` | `ff1e23919fb3aa8691fd6770d6e160dc91e68069ad70505de2b06b74eb023cad` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/TracePostgresTest.java` | `bb94bad69d479cb49fcf91098c7691496d5d124eb26a72798ecc425ad95b73e3` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunSchedulerReviewPostgresTest.java` | `adc53cbe3ece0576f6ce9bf61f855c0b847e73f4aa09641f83ebb5dfac351d76` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/PublishedAdmissionHttpPostgresTest.java` | `6e033bedd210ecc08fc50ee0631c54703480b88ecbe6159508483a731600c3e6` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/PortalRunEventWindowPostgresTest.java` | `57e2f6ff44a816028ef6e7d5dc4c866f6715069cf71944313e4341831dab188b` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunEventReaderPostgresTest.java` | `fad3c00524c87028f672a12f2ae6933300152b9f82c983649d7686580bc2c65c` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationMetadataHttpPostgresTest.java` | `5e53c2aa895c0a7e2790418ad75b2044717de93fd02716b6f1cb92b3aba9adf9` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationStatusHttpPostgresTest.java` | `cea8ad707e62c45eda839ec37ab64926f6028f7c6bc7494ee30c70ad03deff7e` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/TestInputHttpTest.java` | `fc8a028390928b41cb19ad6b49f0fa0ea1a2a6e5a6e48afb4f1ebeb865d55e9d` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/TestInputPromotionPostgresTest.java` | `a0dfbd86b3652597adbec366ced9a18cc84e3770331b3f6e29b233d65f3e0dcf` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java` | `e8378459d2cc17d67401829cb4fb17a1e7cffe049a2e2d137d2a298bc8a5c8b0` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialPostgresTest.java` | `6e07447555b0284152719c84fbd68b7fa4d46eada95dae4deee5160b3c63e715` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialHttpTest.java` | `3a9c7e2e4e8b6af672c1b6f75dda512c771e1a8e3fdd9d796575f19678858ccd` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputConsumerProducerFixture.java` | `d5c4783453cdc001a549db6314277468315b3f53e505ffe7cacbd7ab852cdbd4` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunEventReaderTimeoutBudgetPostgresTest.java` | `5f2af65071eb33124825cc05cb207f03d07289fbad298ab786eb646f14fb69b1` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/PortalRunEventsJoinedEngine.java` | `abe8052d563b4f6d150123a4611279f134cf57906c0d02b338233bd58f393844` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/FrozenModelBindingProducerFixture.java` | `1069921859e39c3bfdbec4c99558ecfe6a06677439910695a4d6ca57a9c1d3ac` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderIntegrationFixture.java` | `1ee1d850a035cacb13a1563aea136a2966f87b54e07d6fb96f1c4cdffb9e1649` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatAdmissionFixture.java` | `3316adc2a44dfd2c36330db7c782c9717ae53b805d051126b3e78ca7ef16631f` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/application/RunStatisticsPostgresTest.java` | `3eb951dbb5c72903cd81c751313f1cb48540e3c9340be9c34c54e8badf791d82` |
| `engine-runtime/src/test/java/cn/agentplatform/common/authority/GatewayRunStatusEngineFixtureMain.java` | `8fdd4d6ca351e6797664e475beff1622dcdb58b534e5d737c0524787f0cb140b` |
| `engine-runtime/src/test/java/cn/agentplatform/common/authority/GatewayPublishedEngineFixtureMain.java` | `c3f1bcbf74cc2b27f64007bbc3a266b8d87a4110a3c295a921244326f6260d56` |
| `app-management/src/test/java/cn/agentplatform/common/authority/PublishedAdmissionFixtureMain.java` | `c5ae74f5765fbb3e6041dbffc264f9c8bd905951479f790aa9f6b2e8722165c6` |
| `.local/iam-n3-admission-app-client/app-classpath.txt` | `098c76dda9986e85ecc8e393e28754a024b4e6d33af8ce871c49e740f59a7b36` |
| `docs/evidence/2026-10-28-EX-36/runs/after-module-20261028-230322-842/method-summary/methods.csv` | `76a4f8611684ef032b675ace3bff11f6ba208d6c8914b50b3660758673fc2c97` |
| `docs/evidence/2026-10-28-EX-04/collector/engine-baseline-class-events.csv` | `3e105f0d1a35d748dcfdfe4d320337a79a1fb3c3eb63a1f368fe4d0d85911aab` |
| `docs/evidence/2026-10-28-EX-04/collector/engine-baseline-unexecuted-source.csv` | `98fca1a307c08fcdb3132a00910e489fd7239ad95170fd77d3ef6aa31efd8a47` |
| `engine-runtime/src/main/resources/application.yml` | `562054834cc0b726ba445d3035ef05d908397cc121b892254efc33c034e1664c` |

## ROOT 只读 admission 脚本静态核对

核对版本 `collector/inputs/full-engine-candidate/pg-preflight.ps1` SHA-256 `c6b509b36ff78ceb18c9539cc942d7620f9b65ca0258ff44ebbeefba88ff7fcc`：固定库 14 个精确匹配上述调用图；主库只核 owner，其余 12 个 engine schema 与 1 个 app schema 分流正确。`RunStatisticsPostgresTest:41`、`TestInputPromotionPostgresTest:66` 明确 engine，`TestInputHttpTest` 的实际 Boot 配置由 `engine-runtime/src/main/resources/application.yml:28–32` 指定 engine。脚本启用 PG 事务只读，只有 catalog/history SELECT，无 DDL。

已向 ROOT 提醒的证据精度项：旧 timeout 库集合应比较每行 before 的原 owner，不宜硬设所有历史匹配前缀都归 agent_v12；仅 name/owner 集合不能证明旧库数据未改变，`oldDatabasesChanged=false` 应限定为 catalog 身份；`unknownDatabaseCount=0` 应明确仅当前准入白名单。上述不是测试断言放宽，不涉及产品行为。脚本后续修改和实际 admission 另行冻结，本段不替代其最终 SHA/运行回执。
