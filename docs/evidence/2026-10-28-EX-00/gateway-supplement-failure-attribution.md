# EX-00 gateway补采非通过项只读归因

日期：2026-10-28；执行者：`ex00_source_inventory`。源码HEAD：`ebbfd62ca1d19efc4d172804efb5ac79453ceb64`。只读分析 `baseline/20261028-173319-158/platform-gateway-xml/` 的failure/error及对应测试/必要直接调用方；未访问/输出XML properties/system-out节点，未读取原始子进程日志、运行classpath/config/properties文件或秘密，没有运行测试、服务、PG或修改产品/测试/索引/HEAD；未使用superpower。

## 本轮覆盖

补采23份XML、225个testcase，7个failure、8个error、0 skipped；下表逐项映射全部15个非通过。它与主RunId `20261028-164553-272` 是两次采集。两目录合并仅按类名核对有26个不同类XML，对照当前27个测试源，缺的正是GatewayPublishedRunProxyTest；这也不等于同一轮全模块执行。旧报告的1F只覆盖当时已有的GatewayRunStatusChainPostgresTest错误，**不能据此称gateway全覆盖/全通过**。PublishedRunProxy坏类没有因本次补采取得完成证据，死锁继续由EX-35承接，不以其缺XML作0失败。

F/E和类型均来自本次归档XML；明确代码来自XML时直接标记，从产品throw推导时另说明。下表Java简名在后文给出完整路径；原始错误正文不复制。

## 逐case与证据

| 完整classname / 精确name | 状态/类型 | 最小证据与源码位置 | 分类/承接 |
| --- | --- | --- | --- |
| cn.agentplatform.gateway.application.PublishedMetadataSmokeLauncherTest / launchTwoIsolatedJvmFixturesWithFiniteDeadline | F / org.opentest4j.AssertionFailedError | [XML](baseline/20261028-173319-158/platform-gateway-xml/TEST-cn.agentplatform.gateway.application.PublishedMetadataSmokeLauncherTest.xml)；Launcher:19子进程exit预期0/实际1；child帧PublishedMetadataClient.ownedTransport:40→构造:36→PublishedMetadataSmokeMain:29；缺类org.apache.hc.core5.http.io.HttpMessageParserFactory | G1：有效子进程classpath缺依赖；EX-27当前源夹具classpath/启动入口 |
| cn.agentplatform.gateway.infra.PortalEventsTlsTest / plainObserverDetectsApplicationAndAdditionalConnectionNegativeControl | F / org.opentest4j.AssertionFailedError | [XML](baseline/20261028-173319-158/platform-gateway-xml/TEST-cn.agentplatform.gateway.infra.PortalEventsTlsTest.xml)；TlsTest:73；安全计数accepted=2、captured=0、application=0、applicationMarker=false | G2：负对照observer未捕获标记；EX-27先核实fixture I/O，具体首个I/O原因待定位 |
| cn.agentplatform.gateway.infra.PortalRunEventsJoinedChainTest / slowReaderCurrentAuthorityAndExpiry | F / org.opentest4j.AssertionFailedError | [XML](baseline/20261028-173319-158/platform-gateway-xml/TEST-cn.agentplatform.gateway.infra.PortalRunEventsJoinedChainTest.xml)；awaitFile:56←focused:125←focused:118←方法:115；另有Child.close:71清理断言；安全child事实补充OWN_SCHEMA_EPOCH | G3：既有joined专库时代守卫拒绝；EX-27夹具epoch核准，不计真实慢读/续期通过 |
| cn.agentplatform.gateway.infra.PortalRunEventsJoinedChainTest / retainedStreamControlledExpiry | F / org.opentest4j.AssertionFailedError | 同XML；awaitFile:56←focused:125←方法:117；Child.close:71 | G3；EX-27先核实有限联合子进程启动，业务期限断言尚未执行 |
| cn.agentplatform.gateway.infra.PortalRunEventsJoinedChainTest / exactPayloadAndRealRenewal | F / org.opentest4j.AssertionFailedError | 同XML；awaitFile:56←focused:125←focused:118←方法:114；Child.close:71 | G3；未取得真实payload相等/续期结果 |
| cn.agentplatform.gateway.infra.PortalRunEventsJoinedChainTest / independentJoinedChainDeniesBeforeMaterialAndRelaysRealEngineOutput | F / org.opentest4j.AssertionFailedError | 同XML；awaitFile:56←方法:164；Child.close:71 | G3；未取得真实双子进程联合链验收 |
| cn.agentplatform.gateway.infra.PortalRunEventsJoinedChainTest / retainedTerminalHistoryScopeInventory | F / org.opentest4j.AssertionFailedError | 同XML；awaitFile:56←focused:125←方法:116；Child.close:71 | G3；历史范围库存断言未完成 |
| cn.agentplatform.gateway.infra.PortalRunEventsSseAutomaticCleanupTest / actualCompletionAutomaticallyRefundsAfterTransportHelperReturns | E / java.lang.NullPointerException | [XML](baseline/20261028-173319-158/platform-gateway-xml/TEST-cn.agentplatform.gateway.infra.PortalRunEventsSseAutomaticCleanupTest.xml)；方法:19读取cleanup.fix.evidence后Path.of(null) | G4：缺显式证据目录输入；EX-27；尚未创建子进程或验证自动归还 |
| cn.agentplatform.gateway.infra.PortalRunEventsSseResetCleanupTest / terminalResetAutomaticallyRefundsWhenReaderAbortOverlapsCompletion | E / java.lang.NullPointerException | [XML](baseline/20261028-173319-158/platform-gateway-xml/TEST-cn.agentplatform.gateway.infra.PortalRunEventsSseResetCleanupTest.xml)；方法:18读取reset.cleanup.evidence后Path.of(null) | G4；EX-27；未进入reset/完成重叠场景 |
| cn.agentplatform.gateway.IntegrationSecurityTest / directServiceApiAuthenticatesApiKeyThenPassesOnlyScopedInternalTicket | E / java.lang.IllegalStateException | [XML](baseline/20261028-173319-158/platform-gateway-xml/TEST-cn.agentplatform.gateway.IntegrationSecurityTest.xml)；AuthorityProperties.invalid:102←validate:30←validateDeployment:80←SecurityConfiguration.bindDeployment:60；XML码SECURITY_MODE_REQUIRED | G5：真实Spring测试部署配置未提供mode；EX-27合法部署配置夹具 |
| cn.agentplatform.gateway.IntegrationSecurityTest / revocationAndPolicyChangeImmediatelyRejectExistingTicket | E / java.lang.IllegalStateException | 同XML；ApplicationContext失败阈值，无本仓业务帧/错误码 | G5：同类首次context失败后的缓存拒绝；不伪造该节点已执行撤权断言 |
| cn.agentplatform.gateway.IntegrationSecurityTest / refusesInvalidSecretBeforeIssuingTicket | E / java.lang.IllegalStateException | 同XML；ApplicationContext失败阈值 | G5；该节点XML无SECURITY_MODE_REQUIRED字面，不冒充重复业务拒绝 |
| cn.agentplatform.gateway.IntegrationSecurityTest / validTicketCannotAccessAdminOrOtherAppOrOtherAppRun | E / java.lang.IllegalStateException | 同XML；ApplicationContext失败阈值 | G5；越权负例未进入业务断言 |
| cn.agentplatform.gateway.IntegrationSecurityTest / corsAllowsRegisteredOriginAndRejectsUnknownOrigin | E / java.lang.IllegalStateException | 同XML；ApplicationContext失败阈值 | G5；不能记CORS通过/失败机制已验证 |
| cn.agentplatform.gateway.IntegrationSecurityTest / boundedEngineRetryAfterSurvivesGatewayWithoutOpeningOtherHeaders | E / java.lang.IllegalStateException | 同XML；ApplicationContext失败阈值 | G5；Retry-After与头边界断言未执行 |

## 根因依据与未知项

### G1：有限smoke进程的有效classpath不完整

`platform-gateway/src/test/java/cn/agentplatform/gateway/application/PublishedMetadataSmokeLauncherTest.java:14`～`:17`从本仓历史证据的smoke-classpaths.json取gateway字符串作为-cp，未使用当前surefire.test.class.path。实际child栈明确缺 `org.apache.hc.core5.http.io.HttpMessageParserFactory`，所以失败早于预期HTTP smoke完成；`platform-gateway/src/main/java/cn/agentplatform/gateway/application/PublishedMetadataClient.java:39`～`:41`构造真实PublishedRunHttp，其Apache依赖在 `platform-gateway/pom.xml:1`声明。

已证实有效child加载路径不能解析该类；没有读取旧classpath文件来断言它具体缺哪一个jar/为何陈旧。EX-27应从本轮已安装依赖生成本轮独立classpath输入并保留进程classpath摘要/退出码，不覆盖历史证据，不改产品以移除真实HTTP依赖。本次失败不能升级为双JVM metadata smoke通过。

### G2：负对照原始peer没有收到测试标记

`platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalEventsTlsTest.java:57`～`:74`使用两个直接Java Socket向测试raw peer发标记，属于observer负对照；它没有调用产品TLS出站链。`:900`～`:953`的rawServer先在`:912`向首连接写HTTP响应，再读首连接字节，最后仅accept/close额外连接；`:955`～`:980`按捕获字节计算application计数。

本轮accepted=2但captured=0，解释了application=0；observer并未观察到原本应发送的明文负对照。先写/对端关闭/首次读之间有可核实的I/O竞态窗口，但XML没有给出首个I/O异常类型/完整时序，本文不指定其必为哪一种socket错误。EX-27应保持负对照可证伪性并定位该窗口；不能将该失败写成产品TLS发出了明文，也不能简单删掉observer负对照。

### G3：联合engine子进程的既有专库时代守卫拒绝

`platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalRunEventsJoinedChainTest.java:56`在40秒范围内等文件/子进程状态；`:124`～`:125`及`:159`～`:164`启动真实engine子进程并等engine-ready.json。它从本仓忽略目录读取既有engine-classpath.txt/database.json；Child构造`:59`～`:64`写入命令/输入并启动，`:67`～`:71`有限关闭与退出断言。

五节点均先停在engine-ready等待，清理失败是附带错误，不能当作五个不同SSE业务失败根因。采集者随后提供[本轮安全child事实](baseline/20261028-173319-158/joined-chain-child-facts.json)：5个owned engine子进程的首异常均为java.lang.IllegalStateException / OWN_SCHEMA_EPOCH，必要本仓帧都是 `engine-runtime/src/test/java/cn/agentplatform/engine/infra/PortalRunEventsJoinedEngine.java:111`，无缺类/缺库错误。

该helper的`:109`要求joined_前缀专库，`:110`判断是否已存在；`:111`只在新库迁移，复用既有库时硬要求engine.flyway_schema_history成功且version非空的记录数等于18，否则抛OWN_SCHEMA_EPOCH。因而已确定是该历史epoch守卫拒绝，不能把它也归为G1缺Apache类。确切实际历史计数没有被安全事实记录，本文未查询该库，不能据当前源码迁移数猜为某个值。

EX-27应按当前源、专库所有权和实际迁移历史核准可复跑fixture，不能仅把18改成当前数字或清库绕过守卫。未读取raw日志、classpath/config/database运行文件，也未清理/重建joined库。ready后的SSE业务断言仍未完成。

### G4/G5：显式夹具输入与真实部署配置缺失

`platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalRunEventsSseAutomaticCleanupTest.java:19`与 `PortalRunEventsSseResetCleanupTest.java:18`都在启动子进程前要求独立证据目录属性。缺路径造成的NPE属于测试入口输入问题，不是产品自动refund/reset逻辑NPE，也不以新增条件跳过制造绿。

`platform-gateway/src/test/java/cn/agentplatform/gateway/IntegrationSecurityTest.java:25`用@SpringBootTest启动真实Gateway应用；其`:129`～`:138`仅设置JWT与app/engine URL等动态属性，没有显式完整安全部署配置。产品 `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:30`按无mode默认拒绝，`SecurityConfiguration.java:60`执行该验证。XML首错为SECURITY_MODE_REQUIRED，其余五项为同context缓存阈值，不等于五次执行过业务断言。EX-27先给合法、隔离、显式mode/service-id/loopback测试配置，保留产品拒绝默认。

## owned_flow专用库的准确正向前置（不执行建库）

补充核实的是主RunId的engine错误，不纳入上述15节点计数：

- 专用库名：`agent_platform_iam_owned_flow_v12`；本仓本机PG端口25432。
- `engine-runtime/src/test/java/cn/agentplatform/engine/infra/OwnedFlowSchedulerPostgresTest.java:122`～`:131`的@BeforeAll isolatedDatabase直连该库并正向断言current_database等于该名字；`:135`～`:138`的fixture使用同库并truncate engine.run。
- `engine-runtime/src/test/java/cn/agentplatform/engine/infra/OwnedFlowMaterialsPostgresTest.java:3`静态导入Scheduler helpers；`:62`～`:64`自己的@BeforeAll setup直接调用isolatedDatabase。不是Java继承父类，也不是将“缺库”包在assertThrows中的负例。
- 主RunId两份XML均为CannotGetJdbcConnectionException→PostgreSQL数据库不存在：[Scheduler XML](baseline/20261028-164553-272/engine-runtime-xml/TEST-cn.agentplatform.engine.infra.OwnedFlowSchedulerPostgresTest.xml)、[Materials XML](baseline/20261028-164553-272/engine-runtime-xml/TEST-cn.agentplatform.engine.infra.OwnedFlowMaterialsPostgresTest.xml)。

因此它是明确的本仓正向专用库前置，EX-00可按既定窗口/库归属核准后协调准备；**不是仅凭名字即可指向任意库**。fixture会清测试表，更不能借用业务库/相邻实例。本文未创建、清理或查询任何数据库；AppCopy补库仍由既有管线另行负责。

## 状态与后续界限

G1/G2/G3/G4/G5分别保留不同证据；G2实际I/O时序、G1既有classpath生成缺口及G3实际库历史计数/合法epoch仍需进一步核准，没有因此新增已证实产品缺陷。EX-27可承接测试入口/隔离/observer及子进程诊断，超出测试白名单的产品改动必须另行登记；EX-35坏类问题独立保留。

后续只有在获准补齐对应前置并定向重验、按新RunId归档后，才能比较准确方法差异与声明该场景通过。23 XML/225case是补采覆盖事实，不是gateway全模块通过或EX-00完成结论。
