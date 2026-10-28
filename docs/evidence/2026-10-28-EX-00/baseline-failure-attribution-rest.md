# EX-00 其余模块非通过项的只读归因

日期：2026-10-28；执行者：`ex00_source_inventory`；源码HEAD：`ebbfd62ca1d19efc4d172804efb5ac79453ceb64`。仅分析 `baseline/20261028-164553-272/` 当次归档XML中的failure/error/skipped节点、安全环境标志及必要源码。未访问/输出XML properties/system-out节点，未读取真实.env/local-env、原始日志、运行夹具中的配置/秘密文件；未运行任何测试、服务或PG命令，未修改产品/测试/台账/索引/HEAD，未使用superpower。

范围为engine-runtime、skill-registry、model-gateway、monitor全部非通过节点，以及 `cn.agentplatform.common.authority.GatewayRunStatusChainPostgresTest#realGatewayQueryAndStatusReturnOriginalBytes`。gateway死锁已有EX-35，不重复归因；AppCopy补库重验由采集者协调，本报告不操作。

下附逐case表保留完整classname、原始精确name、状态/类型、归档XML、必要源码位置和分类编号；空name保留类级初始化失败身份。参数化实例不合并。全部328个非通过/跳过节点均有映射；12个SKILL-SECONDARY、4个CONTRACT-STATUS、1个TEST-RELEASE以及8个SQL边界节点仍有明确待核实/核定事项，不能把“均有分类”当作根因全已证实。表格由实际XML生成，分类不是通过或关闭结论。

## 分类与承接界限

`baseline/20261028-164553-272/test-environment-flags.json`只含安全标志：ENGINE_PG_TEST=true、AGENT_DEV_AUTH=true、基础环境无AGENT_SECURITY_MODE，运行期各测试如何修改系统属性未被穷举。缺独立服务阶段/证据属性与缺业务环境不是同一问题；旧夹具/断言漂移、环境前置、未核实产品行为也分开记录，不能全称Y-4。

| 分类 | 已证实事实/源码锚点 | 承接与剩余 |
| --- | --- | --- |
| ENV-ICA | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatAdmissionHttpPostgresTest.java:71`读取ica.phase后Path.of(null)；Observation类复用control；并发方法由ExecutionException包装同NPE | EX-27单列显式服务phase夹具/执行入口；本轮未提供此系统属性，未进入所需真实phase，不得改成静默跳过 |
| ENV-INITIAL | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15`与InitialSenderIntegrationTest.java:42；`model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:35`均读取initial.fixture，NPE发生在构造/定位目录 | EX-27显式跨模块夹具编排；不是产品初始sender/stream业务NPE。model失败清理负例在进入可控故障前即失败，也归此前置 |
| ENV-REGISTRY | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:53`～`:58`先核查fresh库，再要求model.input.registry；XML AssertionError为owned database registry required | EX-27所有权登记/可复跑夹具；21个Postgres方法及1个HTTP类级初始化均停在此处，不能报材料生产逻辑已测 |
| ENV-TIMEOUT | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunEventReaderTimeoutBudgetPostgresTest.java:29`缺timeout.fix.evidence导致NPE；`:18`为随机库，`:26`的CREATE位于失败之前 | EX-00记录环境/资源归属，EX-27补证据入口。可能已创建随机库但未写registry，不能自动枚举清理或复用；本文未连库核实 |
| ENV-OWNED | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/OwnedFlowSchedulerPostgresTest.java:122`～`:131`直接使用agent_platform_iam_owned_flow_v12，两个类的XML均明确数据库不存在 | EX-00专用测试库前置/归属核准；不是调度/材料业务断言失败，不复用相邻工程库 |
| ENV-TLS | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93`读取consumer.tls后Path.of(null)；多类共享此helper | EX-27准备独立测试TLS输入并声明来源；本轮只是未提供路径，不证明证书文件损坏，更不读取生产证书替代 |
| ENV-BINDING | `model-gateway/src/test/java/cn/agentplatform/model/application/FrozenModelBindingConsumerHttpTest.java:26`要求binding.fixture，3个派生HTTP类复用start；XML为空name类级失败 | EX-27真实有限集成fixture入口；不会因@BeforeAll失败把其余方法算作已执行 |
| ENV-CONSUMER | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerHttpTest.java:28`～`:30`要求consumer.fixture | EX-27显式consumer阶段夹具；当前类级初始化失败 |
| ENV-MODE | CallbackHttpTest.java:224～244、McpManagementTest.java:109～124、ReconciliationProofTest.java:737～744/970～980手建context导入SecurityConfiguration却未给完整mode/service部署配置；monitor DatasetHttpTest启动真实应用，首次XML为SECURITY_MODE_REQUIRED，另两项为相同context失败阈值 | EX-27合法部署配置生成/上下文隔离，和Y-4方向相符的具体子类；产品AuthorityProperties.java:30的缺省拒绝不应放宽。缓存阈值项不捏造其XML也打印了该码 |
| ENV-DEV-AUTH | model InitialModelInputVerifierTest.java:527～549预期IAM_TRUST_CHAIN_UNAVAILABLE，XML出现IAM_DEV_AUTH_FORBIDDEN；同前份报告E1，开发开关继承抢先触发正确拒绝 | EX-27测试环境隔离；不能据此称IAM真实链路/生产默认模式通过 |
| TEST-SCAN | EngineContextPostgresTest/RunAdmissionHttpTest/TestInputHttpTest启动EngineApplication；产品EngineApplication.java:7扫描engine/common，test-classes里的PortalRunEventsJoinedEngine.java:39与InitialChatAdmissionFixture.java:115均是@Configuration，XML记录decoder重名冲突；随后同类出现失败缓存阈值 | EX-27隔离有限Receiver；不以开启生产bean覆盖或删安全配置修测试 |
| TEST-HISTORY | FlowMaterialDatabasePreflightTest.java:53～58仍要求数据库最高迁移<=216，本轮实际223；它是分配V217前的一次性历史前置检查，现行源码已有V217及后续版本 | EX-27拆分历史预检与当前迁移验收；不删V文件、不把当前最大号写死替代有效断言；不据此认定迁移损坏 |
| CONTRACT-STATUS | `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java:1951`无条件添加errorRaw，包括protectedView。FlowMaterialStatus:76、RunObservationStatus:430/:619及gateway:110的12键断言失败，后3项明确12→13 | EX-22/EX-05核定观察面字段语义与冻结契约，EX-27随后同步正确断言；保留原body/key断言，不能只将12改13，也不能只凭字段名判泄露 |
| SQL-METADATA | RunObservationMetadataHttpPostgresTest.java:171禁node_instance等，XML实际失败SQL只提取词法标志：包含node_instance/model_invocation，不含inputs::text/snapshot::text。产品RunAuthorityStore.java:115～120的CHAT_RELATIONS新增形状exists/count检查，`:126`～`:129`将其作为invalid_chat元数据投影 | 源码/旧SQL白名单冲突已证实；EX-27先核准允许的元数据查询边界并保留无payload/只读RR/同连接断言，未据此判实际输入外泄或允许直接删禁表检查 |
| SQL-INPUT-PROBE | RunEventReaderPostgresTest.java:186禁止所有inputs::text；产品RunEventReader.java:188～200现在先octet_length/jsonb_typeof探测根，FLOW不消费inputs，CHAT另有受限根读取 | EX-27核准“长度探测”与“实际材料化”两层检查；本次只有SQL断言失败，不能假报未触碰任何输入表达式，也不能把长度数当实际payload内容已泄漏 |
| TEST-RELEASE | PortalRunEventsLifecycleTest.java:115在h.exits==2后立即查帧，实际61/预期62；测试Hooks在PortalRunEventsHttpTest.java:85实际complete刚返回就++exits，产品PortalRunEventsService.java:194～197随后finally才release（具体归还`:136`～`:137`）；同测试文件`:57`的相邻场景已显式await帧数 | 已找到测试观察点先于真实归还的竞态；EX-27以有期限的真实frame/owner条件核实。当前快照不证明永久泄漏，也没有本轮最终归还记录可声称零泄漏；若期限内不归还再转产品修复卡 |
| SKILL-TICKET | XML必要栈明确ServiceTokens.issue:29；SkillClients.java:23默认为LEGACY，ServiceTickets.java:124调用issue，而ServiceTokens.java:17～30以classloader注册态控制签发：null或iam均拒绝，源码码AUTHORITY_CONTEXT_MISSING | EX-27部署生命周期/有限签发夹具隔离；不能只改JVM属性或反射reset生产态。不是本轮已证明AUTHORITY provider出错；真实装配另由EX-26验 |
| SKILL-SECONDARY | 5个空事实查询、3个reconcile FACT_NOT_FOUND、4个latch等待断言。SkillExecution.java:77在创建execution_fact（`:293`等）前先经SkillClients鉴权；同类大量明确票据前置错误能解释未到外部提交/无fact，但这些case自身XML未都包含原始worker错误 | EX-27先捕获Future/前置异常，再核实原竞争/回滚场景；本文不把所有空fact/latch超时当已确证同因，也不据它们判产品对账/幂等损坏 |
| SKILL-WIRING | SkillCallbackWakeTest.java:216刷新context报缺cn.agentplatform.skill.infra.ServiceTickets；其内嵌WebConfig在同文件:64～70窄扫描回调类、手工registerBean没有该bean，SkillClients.java:26～28有必需setter注入 | EX-27测试装配白名单；不把产品必需注入改可选以掩盖测试缺bean |
| TEST-MIGRATION-JSON | AlgorithmMigrationPostgresTest.java:88～106从target310迁至当前全部版本，`:118`～`:124`仅剔V311四键后比较整行；XML安全比对得到原有键值变化0、5个新增键external_state/last_polled_at/remote_error_code/remote_error_message/submitted_at，正对应V314__external_job_state.sql:9～14的可空追加列 | EX-27升级测试按当前迁移分别验证旧值保持与新增列语义；不改历史迁移、不删列，不以固定旧JSON键集合判旧行破坏 |
| TEST-STREAM-FIXTURE | ModelCallsPostgresTest.java:171～188的stream分支无条件发送Hello/world内容、没有tool_calls；TOOLS分支在`:190`之后仅用于非stream。`:1105`直接get(0)为null | EX-27流式provider夹具发出真实tool-call delta后再验证聚合；本XML不能证明产品丢失了实际发出的工具调用 |
| INPUT-JDK8 | LocalSpikeLifecycleLiveTest.java:31要求AGENT_SPIKE_JDK8；4个XML skipped节点与采集者核准一致，原因是该输入缺失 | 明确外部测试输入未提供；保留跳过，不能标通过/用替身补正式证据，也不等价真实worker验收。补齐/核准后单独重验 |
| UNRESOLVED | 任何未落入上述可证实/候选组的节点保持此类，不自动归Y-4 | 主执行者登记进一步定位，不自行开修 |

## 产品疑点与仍待核实的行为

CONTRACT-STATUS已先报主执行者。主执行者补充的既有来源为81995e1（AP-S07），effectiveError在run.error非空时直接返回该值，空时才取node/event回退；所以errorRaw不能凭字段名被定性为确定泄露。当前冻结设计/trace契约未见该字面字段的核定由EX-22/EX-05承接，本次不扩改产品。

SQL-METADATA、SQL-INPUT-PROBE分别是已观测查询结构与旧边界断言的冲突；需要核对允许的CHAT形状验证/长度预算，不可把所有SQL红机械归为脆弱断言，也不可从无数据值的词法检查推出用户payload内容泄漏。TEST-RELEASE缺“真正release之后”的本轮最终观测，先保留未核实。SKILL-SECONDARY未捕获原始worker失败的case保持候选连锁解释，合法前置补齐后仍必须重验原故障路径。

## 后续验证要求（未执行）

先分别认领环境前置、夹具/生命周期、契约边界三类范围；缺显式集成phase/SSL/所有权registry不得默认跳过或拿相邻工程运行目录填充。新增脚本/共享测试helper仍须白名单与独立评审。每次新RunId保留原红/skip，定向后无-am逐模块回归，对照准确方法集合、类级坏类与新增跳过/未执行；不以失败总数不变、latch走到一半或BUILD SUCCESS替代原场景通过。

下面的完整映射仅表示归因覆盖，不是各组都已追清/修复。涉及stage构造失败的业务断言未完成，类级空name不展平为其所有方法已运行。

## 当次节点统计与逐case映射


| 模块/范围 | 归档case数 | F | E | S |
| --- | ---: | ---: | ---: | ---: |
| engine-runtime | 883 | 35 | 82 | 0 |
| skill-registry | 664 | 6 | 106 | 4 |
| model-gateway | 175 | 7 | 84 | 0 |
| monitor | 63 | 0 | 3 | 0 |
| platform-gateway | 1 | 1 | 0 | 0 |

gateway行只统计本报告指定方法，不代表gateway全模块完成。

| 分类 | 节点数 |
| --- | ---: |
| CONTRACT-STATUS | 4 |
| ENV-BINDING | 4 |
| ENV-CONSUMER | 1 |
| ENV-DEV-AUTH | 1 |
| ENV-ICA | 13 |
| ENV-INITIAL | 70 |
| ENV-MODE | 43 |
| ENV-OWNED | 2 |
| ENV-REGISTRY | 22 |
| ENV-TIMEOUT | 1 |
| ENV-TLS | 64 |
| INPUT-JDK8 | 4 |
| SKILL-SECONDARY | 12 |
| SKILL-TICKET | 56 |
| SKILL-WIRING | 3 |
| SQL-INPUT-PROBE | 1 |
| SQL-METADATA | 7 |
| TEST-HISTORY | 1 |
| TEST-MIGRATION-JSON | 1 |
| TEST-RELEASE | 1 |
| TEST-SCAN | 16 |
| TEST-STREAM-FIXTURE | 1 |

每行源码位置仅保存最前面必要的本仓帧；fixture提前失败时不展开尚未到达的业务调用。表中码只有明确XML文本/源码throw映射，未知保留—。

### engine-runtime / cn.agentplatform.engine.infra.EngineContextPostgresTest

[归档XML](baseline/20261028-164553-272/engine-runtime-xml/TEST-cn.agentplatform.engine.infra.EngineContextPostgresTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| actualMvcTimeoutLeavesRoomForTheFiftyFiveSecondObservationWindow | error / java.lang.IllegalStateException | TEST-SCAN | — | —（Spring初始化/缓存阈值或条件跳过；见分类依据） |
| repositoryProxyAndSchedulerUseRealInitializedJdbc | error / java.lang.IllegalStateException | TEST-SCAN | — | —（Spring初始化/缓存阈值或条件跳过；见分类依据） |

### engine-runtime / cn.agentplatform.engine.infra.FlowMaterialDatabasePreflightTest

[归档XML](baseline/20261028-164553-272/engine-runtime-xml/TEST-cn.agentplatform.engine.infra.FlowMaterialDatabasePreflightTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| inspectBothAuthorizedHistoriesAndActualSourceBeforeAllocating217 | failure / java.lang.AssertionError | TEST-HISTORY | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/FlowMaterialDatabasePreflightTest.java:58` |

### engine-runtime / cn.agentplatform.engine.infra.FlowMaterialStatusPostgresTest

[归档XML](baseline/20261028-164553-272/engine-runtime-xml/TEST-cn.agentplatform.engine.infra.FlowMaterialStatusPostgresTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| actualRunServiceStatusReadsOriginalMapInOneReadOnlyRrAndPreservesAliasesAndTimes | failure / org.opentest4j.AssertionFailedError | CONTRACT-STATUS | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/FlowMaterialStatusPostgresTest.java:76` |

### engine-runtime / cn.agentplatform.engine.infra.InitialChatAdmissionHttpPostgresTest

[归档XML](baseline/20261028-164553-272/engine-runtime-xml/TEST-cn.agentplatform.engine.infra.InitialChatAdmissionHttpPostgresTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| concurrentDuplicateAndReconstructedReceiverReplayOneResult | error / java.util.concurrent.ExecutionException | ENV-ICA | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatAdmissionHttpPostgresTest.java:137` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatAdmissionHttpPostgresTest.java:71` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatAdmissionHttpPostgresTest.java:136` |
| durableDispatchUnknownNeverResends | error / java.lang.NullPointerException | ENV-ICA | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatAdmissionHttpPostgresTest.java:71` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatAdmissionHttpPostgresTest.java:116` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatAdmissionHttpPostgresTest.java:114` |
| durableResponseRecoveryReusesSameAttempt | error / java.lang.NullPointerException | ENV-ICA | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatAdmissionHttpPostgresTest.java:71` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatAdmissionHttpPostgresTest.java:116` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatAdmissionHttpPostgresTest.java:113` |
| explicitTopLevelFieldsRejectNewRequestsWithoutEffects | error / java.lang.NullPointerException | ENV-ICA | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatAdmissionHttpPostgresTest.java:71` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatAdmissionHttpPostgresTest.java:22` |
| explicitTopLevelFieldsRejectReplayWithoutChangingResult | error / java.lang.NullPointerException | ENV-ICA | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatAdmissionHttpPostgresTest.java:71` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatAdmissionHttpPostgresTest.java:38` |
| genuinePublishedChatCreatesProtectedAgent | error / java.lang.NullPointerException | ENV-ICA | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatAdmissionHttpPostgresTest.java:71` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatAdmissionHttpPostgresTest.java:80` |
| legalFlowStillCompletes | error / java.lang.NullPointerException | ENV-ICA | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatAdmissionHttpPostgresTest.java:71` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatAdmissionHttpPostgresTest.java:62` |
| rejectsUnsupportedInputsAndReplaysOriginalRequest | error / java.lang.NullPointerException | ENV-ICA | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatAdmissionHttpPostgresTest.java:71` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatAdmissionHttpPostgresTest.java:102` |
| reviewedPrivateTaskControlStillCompletes | error / java.lang.NullPointerException | ENV-ICA | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatAdmissionHttpPostgresTest.java:71` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatAdmissionHttpPostgresTest.java:168` |
| topLevelHistoryMustRejectBeforeCreation | error / java.lang.NullPointerException | ENV-ICA | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatAdmissionHttpPostgresTest.java:71` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatAdmissionHttpPostgresTest.java:14` |

### engine-runtime / cn.agentplatform.engine.infra.InitialChatObservationPostgresTest

[归档XML](baseline/20261028-164553-272/engine-runtime-xml/TEST-cn.agentplatform.engine.infra.InitialChatObservationPostgresTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| freshObserverReadsGenuinelyAdmittedChat | error / java.lang.NullPointerException | ENV-ICA | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatAdmissionHttpPostgresTest.java:71` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatObservationPostgresTest.java:8` |
| immutableStoreRejectsDedicatedRunTamper | error / java.lang.NullPointerException | ENV-ICA | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatAdmissionHttpPostgresTest.java:71` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatObservationPostgresTest.java:8` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatObservationPostgresTest.java:40` |
| reconnectAndFreshCurrentDenialPreserveDurableResult | error / java.lang.NullPointerException | ENV-ICA | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatAdmissionHttpPostgresTest.java:71` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatObservationPostgresTest.java:8` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatObservationPostgresTest.java:27` |

### engine-runtime / cn.agentplatform.engine.infra.InitialSenderFactsPostgresTest

[归档XML](baseline/20261028-164553-272/engine-runtime-xml/TEST-cn.agentplatform.engine.infra.InitialSenderFactsPostgresTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| activeDripHasTotalDeadline | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| cancellationAfterFirstChunkCannotPublishOrCommit | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| changedOriginalRawCannotBeReserializedAndSent | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| committedDispatchAckLossNeverResendsDuringRecovery | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| currentOutageAfterFirstChunkStopsExposure | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| currentOutageBeforeSendKeepsDurableUnknownMarker | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| dispatchGenerationLossBeforeSendCannotPublish | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| duplicateFinalNeverCommits | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| durableResponseRecoveryCommitsSameAttemptWithoutHttp | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| durationBudgetPreservesResponseAcrossRecovery | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| errorEventNeverCommits | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| explicitProviderUsageIsAccountedOnce | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| finalRevocationPreservesModelFactsWithoutEngineCommit | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| historicNullRawCannotBeReserializedAndSent | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| historyProfileRejectsBeforeDispatch | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| hugeRetryCountRejectsBeforeDispatch | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| initialInvalidOutputHonorsPassWithFlag | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| initialInvalidOutputHonorsTerminate | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| initialSchemaMismatchHonorsPassWithFlag | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| initialSchemaMismatchHonorsTerminate | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| invalidOnInvalidRejectsBeforeDispatch | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| invalidOutputDeclarationRejectsBeforeDispatch | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| invalidSchemaRejectsBeforeDispatch | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| invalidUtf8NeverCommits | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| leaseLossAfterFirstChunkStopsExposure | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| leaseLossAtOutcomeCommitRollsBackDerivedFailure | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| leaseLossBeforeSendCannotPublish | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| lostNodeLeaseCannotCommitInvalidOutcome | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| malformedEventNeverCommits | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| metaSchemaComparisonBudgetRejectsBeforeDispatch | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| missingFinalNeverCommits | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| modelCommitAckLossKeepsSendingSentWithoutEngineResend | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| nestedInvalidSchemaRejectsBeforeDispatch | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| observerFailureCancelsWithoutLogicalCommit | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| outputRetryProfileRejectsBeforeDispatch | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| overBudgetUsagePreservesResponseAcrossRecovery | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| oversizedLineFailsBeforeUnboundedAllocation | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| oversizedPersistedInitialScopeRejectsBeforeDispatch | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| preparationRecoveryMaySendExactlyOnce | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| realFreezeRollbackProvesNoSend | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| redirectIsNeverFollowed | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| revocationAfterFirstChunkStopsExposure | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| revocationBeforeSendKeepsDurableUnknownMarker | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| revokedCurrentCannotCommitInvalidOutcome | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| silentStreamHasIdleDeadline | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| toolsProfileRejectsBeforeDispatch | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| wrongTypedFormatRejectsBeforeDispatch | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |
| wrongTypedFormatTypeRejectsBeforeDispatch | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` |

### engine-runtime / cn.agentplatform.engine.infra.InitialSenderIntegrationTest

[归档XML](baseline/20261028-164553-272/engine-runtime-xml/TEST-cn.agentplatform.engine.infra.InitialSenderIntegrationTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| ownedDriveCommitsInitialResultAfterRealMaterialPreparation | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderIntegrationTest.java:42` |
| ownedDriveCommitsSchemaConstrainedInitialResult | error / java.lang.NullPointerException | ENV-INITIAL | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderFactsPostgresTest.java:15` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialSenderIntegrationTest.java:12` |

### engine-runtime / cn.agentplatform.engine.infra.ModelInputMaterialHttpTest

[归档XML](baseline/20261028-164553-272/engine-runtime-xml/TEST-cn.agentplatform.engine.infra.ModelInputMaterialHttpTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| （XML name=""；类级初始化） | failure / java.lang.AssertionError | ENV-REGISTRY | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:58` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:39` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialHttpTest.java:88` |

### engine-runtime / cn.agentplatform.engine.infra.ModelInputMaterialPostgresTest

[归档XML](baseline/20261028-164553-272/engine-runtime-xml/TEST-cn.agentplatform.engine.infra.ModelInputMaterialPostgresTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| boundsRejectBeforeSourceMaterializationAndDepthRejects | failure / java.lang.AssertionError | ENV-REGISTRY | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:58` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:39` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialPostgresTest.java:568` |
| caughtScopeInsertFailureStillRollsBackRequest | failure / java.lang.AssertionError | ENV-REGISTRY | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:58` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:39` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialPostgresTest.java:434` |
| concurrentPreparationsWithOneRetainedProofPersistOneReference | failure / java.lang.AssertionError | ENV-REGISTRY | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:58` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:39` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialPostgresTest.java:771` |
| finalLocalCheckRollsBackAndPostcommitCheckReportsNonAtomicBoundary | failure / java.lang.AssertionError | ENV-REGISTRY | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:58` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:39` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialPostgresTest.java:546` |
| latestSuccessfulDecisionRejectsOlderWriterBeforePayload | failure / java.lang.AssertionError | ENV-REGISTRY | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:58` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:39` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialPostgresTest.java:227` |
| legacyInvocationAndChatTurnAssociationAreRejected | failure / java.lang.AssertionError | ENV-REGISTRY | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:58` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:39` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialPostgresTest.java:738` |
| malformedParameterShapesNeverConsumeImmutableSlot | failure / java.lang.AssertionError | ENV-REGISTRY | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:58` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:39` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialPostgresTest.java:17` |
| metadataSerializerStabilizesAllSetOrders | failure / java.lang.AssertionError | ENV-REGISTRY | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:58` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:39` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialPostgresTest.java:591` |
| missingRootScopeMustDeny | failure / java.lang.AssertionError | ENV-REGISTRY | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:58` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:39` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialPostgresTest.java:301` |
| modelTokenizerProviderAndCredentialCanariesObserveZeroComponentCalls | failure / java.lang.AssertionError | ENV-REGISTRY | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:58` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:39` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialPostgresTest.java:654` |
| narrowedCurrentDeniesBeforeAnySourceAndCannotRemoveOldObligations | failure / java.lang.AssertionError | ENV-REGISTRY | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:58` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:39` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialPostgresTest.java:357` |
| oversizedExpansionRejectsBeforeLargeAllocationWithMeasuredControl | failure / java.lang.AssertionError | ENV-REGISTRY | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:58` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:39` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialPostgresTest.java:111` |
| oversizedSignedDecisionIdentifierRejectsThisBoundedProfile | failure / java.lang.AssertionError | ENV-REGISTRY | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:58` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:39` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialPostgresTest.java:420` |
| persistsRequestAndScopeAtomicallyWithFullParentUnion | failure / java.lang.AssertionError | ENV-REGISTRY | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:58` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:39` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialPostgresTest.java:289` |
| preflight217AndSourceContinuity | failure / java.lang.AssertionError | ENV-REGISTRY | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:58` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialPostgresTest.java:275` |
| publicCurrentOnlyAndFabricatedSourceCannotWriteAndCatchCannotCommit | failure / java.lang.AssertionError | ENV-REGISTRY | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:58` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:39` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialPostgresTest.java:474` |
| realLockWaitExpiryRejectsAndPrecommitExpiryRollsBack | failure / java.lang.AssertionError | ENV-REGISTRY | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:58` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:39` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialPostgresTest.java:512` |
| rejectsUnsupportedAndMismatchedFrozenConfiguration | failure / java.lang.AssertionError | ENV-REGISTRY | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:58` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:39` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialPostgresTest.java:374` |
| replayRecomputesFullRequestAndNeverCreatesDispatchFacts | failure / java.lang.AssertionError | ENV-REGISTRY | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:58` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:39` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialPostgresTest.java:312` |
| templateSemanticsAndNearLimitUnicodeRemainSupported | failure / java.lang.AssertionError | ENV-REGISTRY | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:58` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:39` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialPostgresTest.java:172` |
| validTypedParametersSurvivePersistedCanonicalRequest | failure / java.lang.AssertionError | ENV-REGISTRY | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:58` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java:39` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialPostgresTest.java:50` |

### engine-runtime / cn.agentplatform.engine.infra.OwnedFlowMaterialsPostgresTest

[归档XML](baseline/20261028-164553-272/engine-runtime-xml/TEST-cn.agentplatform.engine.infra.OwnedFlowMaterialsPostgresTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| （XML name=""；类级初始化） | error / org.springframework.jdbc.CannotGetJdbcConnectionException | ENV-OWNED | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/OwnedFlowSchedulerPostgresTest.java:130` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/OwnedFlowMaterialsPostgresTest.java:64` |

### engine-runtime / cn.agentplatform.engine.infra.OwnedFlowSchedulerPostgresTest

[归档XML](baseline/20261028-164553-272/engine-runtime-xml/TEST-cn.agentplatform.engine.infra.OwnedFlowSchedulerPostgresTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| （XML name=""；类级初始化） | error / org.springframework.jdbc.CannotGetJdbcConnectionException | ENV-OWNED | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/OwnedFlowSchedulerPostgresTest.java:130` |

### engine-runtime / cn.agentplatform.engine.infra.PortalRunEventsLifecycleTest

[归档XML](baseline/20261028-164553-272/engine-runtime-xml/TEST-cn.agentplatform.engine.infra.PortalRunEventsLifecycleTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| timeoutOnCompleteAndShutdownKeepTwoActualGetterOwnersUntilEachReturns | failure / org.opentest4j.AssertionFailedError | TEST-RELEASE | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/PortalRunEventsLifecycleTest.java:115` |

### engine-runtime / cn.agentplatform.engine.infra.RunAdmissionHttpTest

[归档XML](baseline/20261028-164553-272/engine-runtime-xml/TEST-cn.agentplatform.engine.infra.RunAdmissionHttpTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| acceptedRequestIdRemainsReadableAtCapacityAndConflictStillConflicts | error / java.lang.IllegalStateException | TEST-SCAN | — | —（Spring初始化/缓存阈值或条件跳过；见分类依据） |
| drainingReturns503RetryAfterButAcceptedRunStillReads | error / java.lang.IllegalStateException | TEST-SCAN | — | —（Spring初始化/缓存阈值或条件跳过；见分类依据） |
| fullPoolRejectsNewDebugAndFormalBeforeAnyRunOrDependencyRead | error / java.lang.IllegalStateException | TEST-SCAN | — | —（Spring初始化/缓存阈值或条件跳过；见分类依据） |
| newSessionTurnIsRejectedBeforeReservationAndNextTurnChanges | error / java.lang.IllegalStateException | TEST-SCAN | — | —（Spring初始化/缓存阈值或条件跳过；见分类依据） |
| oldAcceptedEmptySessionReservationCanRecoverAtCapacity | error / java.lang.IllegalStateException | TEST-SCAN | — | —（Spring初始化/缓存阈值或条件跳过；见分类依据） |

### engine-runtime / cn.agentplatform.engine.infra.RunEventReaderPostgresTest

[归档XML](baseline/20261028-164553-272/engine-runtime-xml/TEST-cn.agentplatform.engine.infra.RunEventReaderPostgresTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| realSampleMeasuresCompleteRepeatedParentArraysAndPayloadGetterOrder | failure / java.lang.AssertionError | SQL-INPUT-PROBE | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunEventReaderPostgresTest.java:186` |

### engine-runtime / cn.agentplatform.engine.infra.RunEventReaderTimeoutBudgetPostgresTest

[归档XML](baseline/20261028-164553-272/engine-runtime-xml/TEST-cn.agentplatform.engine.infra.RunEventReaderTimeoutBudgetPostgresTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| （XML name=""；类级初始化） | error / java.lang.NullPointerException | ENV-TIMEOUT | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunEventReaderTimeoutBudgetPostgresTest.java:29` |

### engine-runtime / cn.agentplatform.engine.infra.RunObservationMetadataHttpPostgresTest

[归档XML](baseline/20261028-164553-272/engine-runtime-xml/TEST-cn.agentplatform.engine.infra.RunObservationMetadataHttpPostgresTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| actualHttpProjectsSevenFieldsFromReadOnlyRepeatableReadWithoutPayload | failure / java.lang.AssertionError | SQL-METADATA | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationMetadataHttpPostgresTest.java:171` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationMetadataHttpPostgresTest.java:170` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationMetadataHttpPostgresTest.java:205` |
| actualInvalidDigestAndRootKindInPostgresAreDeniedWithoutReadingPayload | failure / java.lang.AssertionError | SQL-METADATA | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationMetadataHttpPostgresTest.java:171` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationMetadataHttpPostgresTest.java:170` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationMetadataHttpPostgresTest.java:380` |
| damagedControlMetadataAndMissingOrInconsistentRootManifestsFailClosed | failure / java.lang.AssertionError | SQL-METADATA | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationMetadataHttpPostgresTest.java:171` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationMetadataHttpPostgresTest.java:170` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationMetadataHttpPostgresTest.java:284` |
| localExpiryOrClosedFrameAfterReadAndCommitPreventsAValidProjection | failure / java.lang.AssertionError | SQL-METADATA | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationMetadataHttpPostgresTest.java:171` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationMetadataHttpPostgresTest.java:170` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationMetadataHttpPostgresTest.java:338` |
| longVersionNumbersRemainPinnedWhenLaterRunsAreAccepted | failure / java.lang.AssertionError | SQL-METADATA | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationMetadataHttpPostgresTest.java:171` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationMetadataHttpPostgresTest.java:170` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationMetadataHttpPostgresTest.java:366` |
| notFoundAndOtherOwnerHaveTheSame404WhileOwnedLegacyHas403 | failure / java.lang.AssertionError | SQL-METADATA | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationMetadataHttpPostgresTest.java:171` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationMetadataHttpPostgresTest.java:170` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationMetadataHttpPostgresTest.java:253` |
| selfConsistentLegalRootDigestReplacementIsTheDocumentedStorageTrustBoundary | failure / java.lang.AssertionError | SQL-METADATA | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationMetadataHttpPostgresTest.java:171` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationMetadataHttpPostgresTest.java:170` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationMetadataHttpPostgresTest.java:391` |

### engine-runtime / cn.agentplatform.engine.infra.RunObservationStatusHttpPostgresTest

[归档XML](baseline/20261028-164553-272/engine-runtime-xml/TEST-cn.agentplatform.engine.infra.RunObservationStatusHttpPostgresTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| freshStatusDoesNotResumeExecutionGrant | failure / org.opentest4j.AssertionFailedError | CONTRACT-STATUS | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationStatusHttpPostgresTest.java:430` |
| publicRecordsCannotMintObserverOrWriterAndLegacyAndPostStillUseOriginalPurpose | failure / java.lang.AssertionError | CONTRACT-STATUS | — | `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationStatusHttpPostgresTest.java:619` → `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationStatusHttpPostgresTest.java:203` |

### engine-runtime / cn.agentplatform.engine.infra.TestInputHttpTest

[归档XML](baseline/20261028-164553-272/engine-runtime-xml/TEST-cn.agentplatform.engine.infra.TestInputHttpTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| boundsInputsAndPaginationAndRevalidatesChangedSchema | error / java.lang.IllegalStateException | TEST-SCAN | — | —（Spring初始化/缓存阈值或条件跳过；见分类依据） |
| callerCannotOverrideFrozenInputsAndStaleRevisionCannotPromote | error / java.lang.IllegalStateException | TEST-SCAN | — | —（Spring初始化/缓存阈值或条件跳过；见分类依据） |
| chatDeclarationsValidateRequiredTypeAndChangedReuse | error / java.lang.IllegalStateException | TEST-SCAN | — | —（Spring初始化/缓存阈值或条件跳过；见分类依据） |
| declaredSchemaConstraintsNullAndDefaultsMatchStructuredInputPayloads | error / java.lang.IllegalStateException | TEST-SCAN | — | —（Spring初始化/缓存阈值或条件跳过；见分类依据） |
| deniesOtherOwnerTenantAndMissingEditorIncludingAdmin | error / java.lang.IllegalStateException | TEST-SCAN | — | —（Spring初始化/缓存阈值或条件跳过；见分类依据） |
| pagingIsBoundedStableAndDoesNotExposeInputsInMetadata | error / java.lang.IllegalStateException | TEST-SCAN | — | —（Spring初始化/缓存阈值或条件跳过；见分类依据） |
| parallelUpdatesHaveExactlyOneWinner | error / java.lang.IllegalStateException | TEST-SCAN | — | —（Spring初始化/缓存阈值或条件跳过；见分类依据） |
| persistsAndCasProtectsAgainstLostUpdates | error / java.lang.IllegalStateException | TEST-SCAN | — | —（Spring初始化/缓存阈值或条件跳过；见分类依据） |
| recoveryFreezesSourceAndTargetBeforeHttpAndRejectsDifferentRetry | error / java.lang.IllegalStateException | TEST-SCAN | — | —（Spring初始化/缓存阈值或条件跳过；见分类依据） |

### model-gateway / cn.agentplatform.model.application.FrozenInitialInputMeterHttpTest

[归档XML](baseline/20261028-164553-272/model-gateway-xml/TEST-cn.agentplatform.model.application.FrozenInitialInputMeterHttpTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| （XML name=""；类级初始化） | failure / java.lang.AssertionError | ENV-BINDING | — | `model-gateway/src/test/java/cn/agentplatform/model/application/FrozenModelBindingConsumerHttpTest.java:26` → `model-gateway/src/test/java/cn/agentplatform/model/application/FrozenInitialInputMeterHttpTest.java:13` |

### model-gateway / cn.agentplatform.model.application.FrozenInitialInputMeterTest

[归档XML](baseline/20261028-164553-272/model-gateway-xml/TEST-cn.agentplatform.model.application.FrozenInitialInputMeterTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| activeEndpointControlsThenMeterWindowHasNoHttpIamSqlOrBusinessChanges | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| boundsAndUnknownFailClosed | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| bytesFromAnotherGenuinelyAcceptedRequestCannotUseThisProof | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| explicitRemoteTokenizerMustDenySupportedLocalModel | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| mappingFrameThreadExpiryInterruptAndAmbientLocalTransaction | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| originalBytesPositiveThenOneAsciiByteChangeMustDeny | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| originalScientificCjkNestedFormatAndCallerMutation | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |

### model-gateway / cn.agentplatform.model.application.FrozenInitialPromptHttpTest

[归档XML](baseline/20261028-164553-272/model-gateway-xml/TEST-cn.agentplatform.model.application.FrozenInitialPromptHttpTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| （XML name=""；类级初始化） | failure / java.lang.AssertionError | ENV-BINDING | — | `model-gateway/src/test/java/cn/agentplatform/model/application/FrozenModelBindingConsumerHttpTest.java:26` → `model-gateway/src/test/java/cn/agentplatform/model/application/FrozenInitialPromptHttpTest.java:13` |

### model-gateway / cn.agentplatform.model.application.FrozenInitialPromptTest

[归档XML](baseline/20261028-164553-272/model-gateway-xml/TEST-cn.agentplatform.model.application.FrozenInitialPromptTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| acceptedUnsupportedAndBadNativeOptionsFailExplicitly | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| activeControlsThenPurePreparationAndEncodingWindowHasNoSqlIamHttpOrBusinessChanges | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| boundsAndCurrentFrameThreadExpiryInterruptAndAmbientTransaction | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| effectiveOutputPriorityAndTextStopJsonObject | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| genuineAcceptedNativeOptionsReachActualCallAndStreamBody | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| originalBytesPositiveThenOneAsciiByteChangeMustDeny | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| rawScientificIdentityCallerMutationAndAnotherGenuineRequest | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |

### model-gateway / cn.agentplatform.model.application.FrozenInitialWireGuardHttpTest

[归档XML](baseline/20261028-164553-272/model-gateway-xml/TEST-cn.agentplatform.model.application.FrozenInitialWireGuardHttpTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| （XML name=""；类级初始化） | failure / java.lang.AssertionError | ENV-BINDING | — | `model-gateway/src/test/java/cn/agentplatform/model/application/FrozenModelBindingConsumerHttpTest.java:26` → `model-gateway/src/test/java/cn/agentplatform/model/application/FrozenInitialWireGuardHttpTest.java:13` |

### model-gateway / cn.agentplatform.model.application.FrozenInitialWireGuardTest

[归档XML](baseline/20261028-164553-272/model-gateway-xml/TEST-cn.agentplatform.model.application.FrozenInitialWireGuardTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| activeInstrumentationThenPureGuardAndEncoderHaveNoExternalEffects | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| explicitlySyntheticParserInputsAreStrictBoundedAndPayloadFree | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| rawInputAndGenuineLiveBindingFrameThreadExpiryAndInterruptRemainRequired | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| realSdkDefaultsJsonAndNestedSchemaPreserveEffectiveNativeValues | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| realSdkNativeOptionsMessagesAndCallModeMutationsDeny | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| unchangedActualSdkStreamPassesThenMutatedNativeModelIsDenied | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |

### model-gateway / cn.agentplatform.model.application.FrozenModelBindingConsumerHttpTest

[归档XML](baseline/20261028-164553-272/model-gateway-xml/TEST-cn.agentplatform.model.application.FrozenModelBindingConsumerHttpTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| （XML name=""；类级初始化） | failure / java.lang.AssertionError | ENV-BINDING | — | `model-gateway/src/test/java/cn/agentplatform/model/application/FrozenModelBindingConsumerHttpTest.java:26` |

### model-gateway / cn.agentplatform.model.application.FrozenModelBindingVerifierTest

[归档XML](baseline/20261028-164553-272/model-gateway-xml/TEST-cn.agentplatform.model.application.FrozenModelBindingVerifierTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| actualHttpObserverPositiveControlsAndNoBusinessWrites | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| capabilitiesAndEffectiveOutputAreChecked | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| cooperativeEightSecondEnvelopeCannotMintAfterOverrun | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| emptyClassificationRequiresLocalAndRetainedExpiryRetiresNewProof | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| frozenStateSurvivesMutableDisableAndEndpointLabelModelChanges | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| fullTreeHashRetainsArrayOrderAndObjectOrderIsCanonical | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| interruptionExpiryAndBoundedPoolNeverMintOrRetry | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| metadataBoundsAreStrictBeforeNewProof | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| missingTenantRowAndNestedIdsHaveNoMutableFallback | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| nullClosedReplacedCrossThreadAndAmbientTransactionDenyBeforeQuery | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| numericDigestCopiesAndResultLifetime | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| realFreezeAndAcceptedConsumerPositiveControl | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| realPgLockTimeoutThenFreshHealthyFrame | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| refreshFollowsActualConnectionReleaseAndNarrowingRevocationOutageDeny | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| rejectsChangedSnapshotUnderOldSha | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| retainsLostLabelObligation | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| signedPostPgOwnerRootInitialOriginAndNewLabelChangesAreRechecked | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| transactionBudgetRejectsDelayedReadBeforeFinalRefresh | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| transactionCleanupOverrunCannotReachFinalRefresh | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |

### model-gateway / cn.agentplatform.model.application.InitialModelInputConsumerHttpTest

[归档XML](baseline/20261028-164553-272/model-gateway-xml/TEST-cn.agentplatform.model.application.InitialModelInputConsumerHttpTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| （XML name=""；类级初始化） | failure / java.lang.AssertionError | ENV-CONSUMER | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerHttpTest.java:29` |

### model-gateway / cn.agentplatform.model.application.InitialModelInputVerifierTest

[归档XML](baseline/20261028-164553-272/model-gateway-xml/TEST-cn.agentplatform.model.application.InitialModelInputVerifierTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| ambientTransactionDeniesBeforeAuthorityRefreshOrMaterialHttp | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| closureOwnerRootInitialSnapshotAndUnionCannotBeChangedWithOnlyManifestRehash | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| currentNarrowingRevocationOutageAndExpiryDuringTransportReturnNoResult | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| missingConfigurationAndDefaultIamGuardRemainClosed | failure / java.lang.AssertionError | ENV-DEV-AUTH | IAM_DEV_AUTH_FORBIDDEN；IAM_TRUST_CHAIN_UNAVAILABLE | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:82` → `admin-common/src/main/java/cn/agentplatform/common/SecurityConfiguration.java:60` |
| noContextAndCurrentOnlyDenyBeforeGet | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| permissionArrayOrderMayDifferFromRawManifestHashAndRetainScopeDigest | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| producerDecimalCanonicalAndCommonExponentRemainIndividuallyBound | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| producerNumericCanonicalShaSurvivesStrictParsingAndMutationDeniesBeforeGet | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputVerifierTest.java:67` |
| realSignedBoundRequestProducesPrivateResultAndRetiresWithFrame | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| reordersOnlyObjectKeysAndUsesExactLiveFinalMapping | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| signedForwardWithChangedOrMissingOriginDeniesBeforeDataGet | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| strictLexerRejectsNonJsonNumberInjectionAndOversizedTokenBeforeGet | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| strictSelectorsAndFullCandidateHashDenyBeforeGet | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| wideFiniteDecimalRetainsFrozenParserSupport | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| wrongMeasurePurposeDeniesBeforeDataGet | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |

### model-gateway / cn.agentplatform.model.application.InitialStreamIntegrationTest

[归档XML](baseline/20261028-164553-272/model-gateway-xml/TEST-cn.agentplatform.model.application.InitialStreamIntegrationTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| actualCurrentLossDuringTokenizerAndReservationDeniesCompletion | error / java.lang.NullPointerException | ENV-INITIAL | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:35` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:57` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationTest.java:251` |
| authenticatedInitialHttpHasExactDurableWireAndUnavailableUsage | error / java.lang.NullPointerException | ENV-INITIAL | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:35` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:57` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationTest.java:203` |
| badSelectorsAndSlowBodiesStopBeforeMaterialOrModelWork | error / java.lang.NullPointerException | ENV-INITIAL | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:35` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:57` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationTest.java:293` |
| clientResetCancelsProviderStreamAndReturnsOwnerThread | error / java.lang.NullPointerException | ENV-INITIAL | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:35` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:57` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationTest.java:143` |
| controlledProviderUsagePropagatesWithoutClaimingRealBilling | error / java.lang.NullPointerException | ENV-INITIAL | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:35` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:57` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationTest.java:134` |
| credentialPreparationRevocationPreventsTokenizerEgress | error / java.lang.NullPointerException | ENV-INITIAL | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:35` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:57` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationTest.java:49` |
| failedReceiverSetupClosesAlreadyStartedDependencies | failure / java.lang.AssertionError | ENV-INITIAL | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationTest.java:81` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationTest.java:91` |
| filteredCompletionCannotBecomeFinalSuccess | error / java.lang.NullPointerException | ENV-INITIAL | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:35` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:57` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationTest.java:123` |
| finalRevocationAfterSuccessfulPersistenceSuppressesOnlyClientFinal | error / java.lang.NullPointerException | ENV-INITIAL | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:35` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:57` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationTest.java:28` |
| firstChunkFlushesBeforeProviderFinalAndResponseRevocationStopsFinal | error / java.lang.NullPointerException | ENV-INITIAL | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:35` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:57` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationTest.java:267` |
| providerEofWithoutFinishCannotBecomeFinalSuccess | error / java.lang.NullPointerException | ENV-INITIAL | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:35` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:57` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationTest.java:165` |
| providerHttpFailureClosesAndNeverRetriesOrRefunds | error / java.lang.NullPointerException | ENV-INITIAL | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:35` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:57` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationTest.java:176` |
| teardownFailureStillClosesLaterOwnedDependencies | error / java.lang.NullPointerException | ENV-INITIAL | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:35` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:57` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationTest.java:104` |
| tokenizerRejectsBadFullRequestCountsAndAcknowledgementsBeforeReservation | error / java.lang.NullPointerException | ENV-INITIAL | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:35` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:57` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationTest.java:238` |
| tokenizerWholeBodyDripIsCancelledAtBoundBeforeReservation | error / java.lang.NullPointerException | ENV-INITIAL | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:35` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:57` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationTest.java:186` |
| toolCompletionCannotBecomeFinalSuccess | error / java.lang.NullPointerException | ENV-INITIAL | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:35` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:57` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationTest.java:123` |
| truncatedLengthCannotBecomeFinalSuccess | error / java.lang.NullPointerException | ENV-INITIAL | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:35` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:57` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationTest.java:123` |
| unknownCompletionCannotBecomeFinalSuccess | error / java.lang.NullPointerException | ENV-INITIAL | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:35` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:57` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationTest.java:123` |

### model-gateway / cn.agentplatform.model.application.ModelCallsPostgresTest

[归档XML](baseline/20261028-164553-272/model-gateway-xml/TEST-cn.agentplatform.model.application.ModelCallsPostgresTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| streamingToolCallsAreNotLostByChunkAggregation | error / java.lang.NullPointerException | TEST-STREAM-FIXTURE | — | `model-gateway/src/test/java/cn/agentplatform/model/application/ModelCallsPostgresTest.java:1105` |

### model-gateway / cn.agentplatform.model.infra.EngineModelInputManifestClientHttpTest

[归档XML](baseline/20261028-164553-272/model-gateway-xml/TEST-cn.agentplatform.model.infra.EngineModelInputManifestClientHttpTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| ambientTransactionDeniesBeforeMetadataHttp | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| assertedHeadersAndContentEncodingMustMatchExactly | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| boundedNormalTlsTypedManifest | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| contentLengthAndChunkOverflowCancelBoundedSubscriber | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| interruptionCancelsAndPreservesInterruptStatus | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| oneTotalDeadlineIncludesStalledHeadersAndTricklingBody | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| roleEntitlementLabelAndParentCountsAreCheckedBeforeTypedSets | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| sameAndDifferentOriginRedirectsNeverForwardBearer | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| strictBytesJsonFieldsTypesArraysAndHashes | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |
| wrongHostAndUntrustedCaFailOrdinaryTls | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |

### model-gateway / cn.agentplatform.model.infra.FrozenModelBindingStorePostgresTest

[归档XML](baseline/20261028-164553-272/model-gateway-xml/TEST-cn.agentplatform.model.infra.FrozenModelBindingStorePostgresTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| ordinaryStoreRequiresActualReadOnlyTransaction | error / java.lang.NullPointerException | ENV-TLS | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:93` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:69` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:42` |

### model-gateway / cn.agentplatform.model.infra.InitialStreamSendFactsPostgresTest

[归档XML](baseline/20261028-164553-272/model-gateway-xml/TEST-cn.agentplatform.model.infra.InitialStreamSendFactsPostgresTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| realCommitThenAckLossPreservesSendingSentAndNeverRefundsOrDispatches | error / java.lang.NullPointerException | ENV-INITIAL | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:35` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:57` → `model-gateway/src/test/java/cn/agentplatform/model/infra/InitialStreamSendFactsPostgresTest.java:15` |
| realSqlAbortRollsBackSendingAndRefundsOnlyProvenNoSend | error / java.lang.NullPointerException | ENV-INITIAL | — | `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:35` → `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java:57` → `model-gateway/src/test/java/cn/agentplatform/model/infra/InitialStreamSendFactsPostgresTest.java:38` |

### monitor / cn.agentplatform.monitor.application.DatasetHttpTest

[归档XML](baseline/20261028-164553-272/monitor-xml/TEST-cn.agentplatform.monitor.application.DatasetHttpTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| ownershipAndExplicitEditorAreRequiredAndSourceDigestMustMatch | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| parallelRequestClaimsChooseOneVersionAndSameContentRetriesRemainStable | error / java.lang.IllegalStateException | ENV-MODE | — | —（Spring初始化/缓存阈值或条件跳过；见分类依据） |
| sameContentIsIdempotentButVersionAndRequestIdAreImmutable | error / java.lang.IllegalStateException | ENV-MODE | — | —（Spring初始化/缓存阈值或条件跳过；见分类依据） |

### platform-gateway / cn.agentplatform.common.authority.GatewayRunStatusChainPostgresTest

[归档XML](baseline/20261028-164553-272/platform-gateway-xml/TEST-cn.agentplatform.common.authority.GatewayRunStatusChainPostgresTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| realGatewayQueryAndStatusReturnOriginalBytes | failure / org.opentest4j.AssertionFailedError | CONTRACT-STATUS | — | `platform-gateway/src/test/java/cn/agentplatform/common/authority/GatewayRunStatusChainPostgresTest.java:110` |

### skill-registry / cn.agentplatform.skill.application.AlgorithmExecutionPostgresTest

[归档XML](baseline/20261028-164553-272/skill-registry-xml/TEST-cn.agentplatform.skill.application.AlgorithmExecutionPostgresTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| absentNewAuthorityCannotResetKnownUnsentFact | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| ackLostThenNewAdapterQueryConvergesWithoutReplay | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| concurrentlyTerminalFactCannotBeOverwrittenByOldObservation | error / org.springframework.dao.EmptyResultDataAccessException | SKILL-SECONDARY | — | `skill-registry/src/test/java/cn/agentplatform/skill/application/AlgorithmExecutionPostgresTest.java:288` → `skill-registry/src/test/java/cn/agentplatform/skill/application/AlgorithmExecutionPostgresTest.java:758` |
| debugLastRealMakesNoAdditionalAlgorithmRequest | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| engineAuthorizedReadonlyGenerationIsPreparedBeforeOnlyBusinessPost | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| formalInvocationIgnoresMockAndUsesRealAlgorithm | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| lateTerminalObservationIsRetainedWithoutRevivingCancelledAuthority | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| legacyBindingWithoutPrincipalStaysAuditedUnknownWithoutNetwork | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| missingOriginalBindingStaysAuditedUnknownWithoutNetwork | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| narrowedCurrentScopeDoesNotQueryHistoricalJob | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| noApprovalAndDebugMockHaveZeroAlgorithmRequests | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| oldExplicitAttemptCannotSendIntoPreparedNewReadonlyGeneration | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| preparingAndRegisteredExistBeforeSubmittedAndFirstPost | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| readonlyGenerationPreparationRollbackPreservesOriginalFactAndBinding | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| sentBindingCannotEnterReadonlyGenerationHandoff | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| sideEffectContractCannotEnterReadonlyGenerationHandoff | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| staleObservationCannotAdoptReplacementBindingUid | failure / org.opentest4j.AssertionFailedError | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `skill-registry/src/test/java/cn/agentplatform/skill/application/AlgorithmExecutionPostgresTest.java:741` → `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` |
| staleSuccessObservationCannotMarkChangedFactDone | error / org.springframework.dao.EmptyResultDataAccessException | SKILL-SECONDARY | — | `skill-registry/src/test/java/cn/agentplatform/skill/application/AlgorithmExecutionPostgresTest.java:288` → `skill-registry/src/test/java/cn/agentplatform/skill/application/AlgorithmExecutionPostgresTest.java:725` |
| submittedRefusalLeavesPreparingAndNeverPosts | error / org.springframework.dao.EmptyResultDataAccessException | SKILL-SECONDARY | — | `skill-registry/src/test/java/cn/agentplatform/skill/application/AlgorithmExecutionPostgresTest.java:288` → `skill-registry/src/test/java/cn/agentplatform/skill/application/AlgorithmExecutionPostgresTest.java:364` |
| terminalFactFailureRollsBackBindingDoneAtomically | error / org.springframework.dao.EmptyResultDataAccessException | SKILL-SECONDARY | — | `skill-registry/src/test/java/cn/agentplatform/skill/application/AlgorithmExecutionPostgresTest.java:288` → `skill-registry/src/test/java/cn/agentplatform/skill/application/AlgorithmExecutionPostgresTest.java:947` |
| trustedFailedConditionCanConvergeFromUnknown | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| trustedFailedJobIsNonRetryableAndDuplicateDoesNotCreateNewAttempt | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| twoAuthorizedRetryWorkersPrepareOnlyOneGeneration | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| unknownFactNeverEntersReadonlyGenerationHandoff | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| unsentReadonlyTransientFailureIsFencedBeforeRetryableFact | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| w1ReconciliationFencesDelayedSubmittedAck | failure / org.opentest4j.AssertionFailedError | SKILL-SECONDARY | — | `skill-registry/src/test/java/cn/agentplatform/skill/application/AlgorithmExecutionPostgresTest.java:632` |
| w3OnlyRedeliversPersistedFact | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |

### skill-registry / cn.agentplatform.skill.application.AsyncHttpExecutionTest

[归档XML](baseline/20261028-164553-272/skill-registry-xml/TEST-cn.agentplatform.skill.application.AsyncHttpExecutionTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| actualUnknownOutboxUsesPostgresSkipLockedAndNeverReexecutesBusiness | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| cancelledPreparationCannotDeliverQueryOrResubmit | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| completedFactIsQueuedAndLateDeliveryNeverRevivesCancelledAuthority | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| interruptedBeforeSubmittedRecordsNotSentOnlyAfterAuthorityBecomesUnknown | error / cn.agentplatform.common.PlatformException | SKILL-SECONDARY | FACT_NOT_FOUND（源码） | `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillCatalog.java:144` → `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillExecution.java:835` → `skill-registry/src/test/java/cn/agentplatform/skill/application/AsyncHttpExecutionTest.java:837` |
| oldSubmittedAckCannotDispatchNewRegisteredAttempt | failure / org.opentest4j.AssertionFailedError | SKILL-SECONDARY | — | `skill-registry/src/test/java/cn/agentplatform/skill/application/AsyncHttpExecutionTest.java:633` |
| outboxCannotDeliverRegistrationBeforeSubmittedRpcAndOnlyOneBusinessSend | failure / org.opentest4j.AssertionFailedError | SKILL-SECONDARY | — | `skill-registry/src/test/java/cn/agentplatform/skill/application/AsyncHttpExecutionTest.java:790` |
| sentPreparationCrashImageRecoversByOriginalKeyUnderExpiredLease | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| unknownAuthorityFencesLateSubmittedAcknowledgementBeforeAnyBusinessSend | failure / org.opentest4j.AssertionFailedError | SKILL-SECONDARY | — | `skill-registry/src/test/java/cn/agentplatform/skill/application/AsyncHttpExecutionTest.java:1006` |

### skill-registry / cn.agentplatform.skill.application.CallbackHttpTest

[归档XML](baseline/20261028-164553-272/skill-registry-xml/TEST-cn.agentplatform.skill.application.CallbackHttpTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| abandonedProcessingLeaseCanBeRetriedButLiveLeaseCannot | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| atomicallyRollbackFactWhenReceiptUpdateFails | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| callbackContractRetainsFrozenSecretReference | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| callbackOptionalLookupMustStillBeReadOnly | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| callbackSecretReferenceIsRedactedFromManagementView | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| cancelledRunAndOldAttemptAreOnlyAudited | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| concurrentSameEventIsIdempotent | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| currentScopeMustCoverOriginalTaskAndDenialIsAudited | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| deliveredReceiptReportsAcceptedDeliveryWithoutGlobalLogicalAcceptanceField | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| duplicateEventWithDifferentDigestIsRejected | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| earlyCallbackWaitsForAckAndSameEventCanBeRetried | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| expiredReceiptProcessorIsSupersededWhenIndependentRecoveryAlreadyStoredFact | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| failureDoesNotMaterializeProviderErrorPayloadAsSuccessfulOutput | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| independentKeyLookupCanProveBindingAfterLostAck | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| inFlightUnknownAcknowledgementCannotMarkNewSuccessDelivered(String)[1] | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| inFlightUnknownAcknowledgementCannotMarkNewSuccessDelivered(String)[2] | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| inFlightUnknownAcknowledgementCannotMarkNewSuccessDelivered(String)[3] | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| largeCallbackUsesControlledArtifactInsteadOfUnboundedResultTree | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| lostAckDoesNotAllowCallbackToInventBindingOrResubmit | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| malformedResultDoesNotBecomeAFinalFact | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| notSentFenceRejectsCallbackWithoutResultMaterialization | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| receiptReportsDeliveryAcceptanceWithoutClaimingGlobalLogicalAcceptance | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| rejectsSignatureTimestampAndPayloadMutation | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| responseLostAndOutboxFailureKeepDurableFactWithoutNewSubmission | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| returnMappingUsesOriginalRawSchemaThenProjectsResult | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| serviceJwtAndOriginalPrincipalAreMandatoryEvenWithCorrectHmac | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| trailingJsonAndDuplicateKeysAreRejectedWithoutChangingFact | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| unknownOrMissingFailureSemanticCannotCreateConfirmedFailure | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| validCallbackIsDurableAndDuplicateCannotRepeatBusiness | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| validFailurePersistsDeclaredSemantic | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| validFailureWithoutSuccessPayloadPersistsAnEmptyFailureEnvelope | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| wrongBindingFieldsNeverCreateOrReplaceFacts | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |

### skill-registry / cn.agentplatform.skill.application.ReconciliationProofTest

[归档XML](baseline/20261028-164553-272/skill-registry-xml/TEST-cn.agentplatform.skill.application.ReconciliationProofTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| appliedResponseFromOldAttemptCannotOverwriteOrDeliverAsNewAttempt | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| asyncHttp404And5xxRemainUnknown | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| attemptChangesDuringQueryPreventPublishingProofForNewGeneration | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| businessResultProjectionCannotChangeReconciliationProtocolMeaning | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| callbackFinalDuringAppliedQueryCannotBeOverwrittenOrRedeliveredByOldResponse | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| callbackSuccessDuringQueryPreventsPublishingNewProof | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| cancellingRunDuringQueryPreventsProofAndEngineMismatchFailsClosed | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| completeProofDigestIsRecomputableAndPgRejectsMutation | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| concurrentConflictingResponsesFailClosedWithoutReplacement | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| concurrentIdenticalResponsesCreateOneProof | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| explicitAsyncNotAppliedCreatesImmutableProofWithoutBusinessRetry | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| explicitSynchronousNotAppliedAlsoHasProof | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| mixedAudiencePortalPurposeCannotReadProof | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| narrowedAuthorizationCannotCreateAsyncNotAppliedProof | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| narrowedAuthorizationCannotQueryOrCreateProofIncludingSyncHttp | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| nonProofAppliedReconcileRetainsExistingTicketPolicyInThisLimitedFix | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| noPostgresRowLockSpansProviderHttp | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| normalAppliedResponseForSameAttemptStillCommitsAndDelivers | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| notSentIsIdempotentAndDoesNotTrustArbitrarySendFalseState | error / cn.agentplatform.common.PlatformException | SKILL-SECONDARY | FACT_NOT_FOUND（源码） | `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillCatalog.java:144` → `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillExecution.java:835` → `skill-registry/src/test/java/cn/agentplatform/skill/application/ReconciliationProofTest.java:438` |
| oldAppliedQueryCannotSetDoneOnReplacementBinding | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| ordinaryHttp404And5xxRemainUnknown | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| originalKeyLookupAfterLostAckProducesStableProofWithoutSecondSubmit | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| portalServiceRoleCannotCreateExternalNotAppliedProof | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| portalServiceRoleCannotCreateNotSentProofOrFenceSender | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| portalTokenWithServiceRoleCannotReadOriginalOwnerProof | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| proofHttpRequiresVerifiedInternalTicketAndExactOriginalOwner | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| proofReadIsExactServiceTenantAndPrincipalRestrictedAndHistorical | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| queryTimeoutDoesNotCreateProofOrResend | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| reliableNotSentFenceCreatesProofWithoutAnyExternalRequest | error / cn.agentplatform.common.PlatformException | SKILL-SECONDARY | FACT_NOT_FOUND（源码） | `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillCatalog.java:144` → `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillExecution.java:835` → `skill-registry/src/test/java/cn/agentplatform/skill/application/ReconciliationProofTest.java:344` |
| repeatedQueriesReturnSameProofWithoutReexecution | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| sourceConflictCannotOverwriteImmutableProof | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| staleReconcilerCannotFenceANewerGenerationBinding | error / org.springframework.dao.EmptyResultDataAccessException | SKILL-SECONDARY | — | `skill-registry/src/test/java/cn/agentplatform/skill/application/ReconciliationProofTest.java:592` |
| unconfiguredMarkerCannotGrantProof | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |
| wrongJobOrKeyCannotGrantProof | error / cn.agentplatform.common.PlatformException | SKILL-TICKET | AUTHORITY_CONTEXT_MISSING（源码） | `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:29` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/ServiceTickets.java:124` → `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillClients.java:54` |

### skill-registry / cn.agentplatform.skill.application.SkillCallbackWakeTest

[归档XML](baseline/20261028-164553-272/skill-registry-xml/TEST-cn.agentplatform.skill.application.SkillCallbackWakeTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| adoptedFactSendsOneSignedWake | error / org.springframework.beans.factory.UnsatisfiedDependencyException | SKILL-WIRING | — | `skill-registry/src/test/java/cn/agentplatform/skill/application/SkillCallbackWakeTest.java:216` |
| replayAndSupersededDeliveryDoNotWakeAgain | error / org.springframework.beans.factory.UnsatisfiedDependencyException | SKILL-WIRING | — | `skill-registry/src/test/java/cn/agentplatform/skill/application/SkillCallbackWakeTest.java:216` |
| wakeFailureKeepsCallbackSuccessful | error / org.springframework.beans.factory.UnsatisfiedDependencyException | SKILL-WIRING | — | `skill-registry/src/test/java/cn/agentplatform/skill/application/SkillCallbackWakeTest.java:216` |

### skill-registry / cn.agentplatform.skill.infra.AlgorithmMigrationPostgresTest

[归档XML](baseline/20261028-164553-272/skill-registry-xml/TEST-cn.agentplatform.skill.infra.AlgorithmMigrationPostgresTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| v310UpgradePreservesLegacyHttpProofAndAlgorithmRowsAndAddsScopedUniqueIdentity | failure / org.opentest4j.AssertionFailedError | TEST-MIGRATION-JSON | — | `skill-registry/src/test/java/cn/agentplatform/skill/infra/AlgorithmMigrationPostgresTest.java:123` |

### skill-registry / cn.agentplatform.skill.infra.localspike.LocalSpikeLifecycleLiveTest

[归档XML](baseline/20261028-164553-272/skill-registry-xml/TEST-cn.agentplatform.skill.infra.localspike.LocalSpikeLifecycleLiveTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| coldStartAndReadyHealth | skipped / 无异常type（跳过） | INPUT-JDK8 | — | —（Spring初始化/缓存阈值或条件跳过；见分类依据） |
| concurrentCallsExecuteSeriallyWithCorrectResults | skipped / 无异常type（跳过） | INPUT-JDK8 | — | —（Spring初始化/缓存阈值或条件跳过；见分类依据） |
| externalKillIsDetectedAndAutoRestarted | skipped / 无异常type（跳过） | INPUT-JDK8 | — | —（Spring初始化/缓存阈值或条件跳过；见分类依据） |
| shutdownKillsChildrenAndReleasesPort | skipped / 无异常type（跳过） | INPUT-JDK8 | — | —（Spring初始化/缓存阈值或条件跳过；见分类依据） |

### skill-registry / cn.agentplatform.skill.infra.McpManagementTest

[归档XML](baseline/20261028-164553-272/skill-registry-xml/TEST-cn.agentplatform.skill.infra.McpManagementTest.xml)

| 精确name | 状态/类型 | 分类 | 明确码 | 必要源码位置 |
| --- | --- | --- | --- | --- |
| authenticatedDraftNeedsAdminAndDoesNotExposeSecretReference | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| editorDiscoversOnlyExistingTenantDraftAndCannotOverrideEndpoint | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| invalidJwtCannotReachSavedDraftOrTransport | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| missingMcpToolNameIsRejectedWhenSavingDraft | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| nonMcpAndPublishedVersionCannotBeDiscovered | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| readOnlyUserAndCrossTenantCannotOpenMcpConnection | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
| wrongTenantCredentialReferenceRejectsBeforeOpeningSocket | error / java.lang.IllegalStateException | ENV-MODE | SECURITY_MODE_REQUIRED | `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:102` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30` → `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:80` |
