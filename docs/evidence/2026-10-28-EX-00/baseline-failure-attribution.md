# EX-00 已归档非通过项的只读归因

日期：2026-10-28；执行者：`ex00_source_inventory`。源码HEAD：`ebbfd62ca1d19efc4d172804efb5ac79453ceb64`。仅分析本卡 `baseline/20261028-164553-272/` 的admin-common/app-management归档XML中的failure/error节点及对应源码；未访问/输出XML properties/system-out节点，未读取真实环境文件或原始日志，未复制异常大段。没有运行测试/服务/PG、修改产品/测试/主台账/索引或HEAD；未使用superpower。

## 覆盖与结论边界

按归档XML重新枚举：

| 模块 | testcase节点 | failure | error | skipped | 本报告覆盖 |
| --- | ---: | ---: | ---: | ---: | --- |
| admin-common | 363 | 10 | 1 | 0 | 全部11个非通过节点 |
| app-management | 177 | 0 | 19 | 0 | 全部19个错误节点，含1个空name的类级BeforeAll错误 |

这里是本轮归档计数，不是通过结论。30个非通过节点至少涉及：测试继承环境、静态生命周期隔离、源码扫描器、授权前置夹具、缺测试库、测试配置扫描污染、Portal ceiling夹具、可信授权上下文。**不能统一称Y-4，也不能据本次只读分析声称修正后全绿。**本报告未确证这些30项另有产品授权实现缺陷；尚有具体失败输入和补齐前置后的业务行为待核实。网关死锁已经EX-35承接，本报告不重复归因。

下列“源码映射错误码”表示从对应throw语句确定，XML未必打印code字段；与XML直接可见的错误码分开。参数化名称保留XML原文，不能把[1]/[2]合并后漏计。

## XML与类名索引

所有链接相对本报告所在目录，未引用target中可能被覆盖的XML。

| 代号 | 完整classname | 归档XML |
| --- | --- | --- |
| A1 | cn.agentplatform.common.authority.AuthorityConfigurationTest | [A1 XML](baseline/20261028-164553-272/admin-common-xml/TEST-cn.agentplatform.common.authority.AuthorityConfigurationTest.xml) |
| A2 | cn.agentplatform.common.authority.AuthorityPolicyTest | [A2 XML](baseline/20261028-164553-272/admin-common-xml/TEST-cn.agentplatform.common.authority.AuthorityPolicyTest.xml) |
| A3 | cn.agentplatform.common.authority.LocalRunAdvanceAuthorityTest | [A3 XML](baseline/20261028-164553-272/admin-common-xml/TEST-cn.agentplatform.common.authority.LocalRunAdvanceAuthorityTest.xml) |
| A4 | cn.agentplatform.common.authority.ServiceTicketProviderTest | [A4 XML](baseline/20261028-164553-272/admin-common-xml/TEST-cn.agentplatform.common.authority.ServiceTicketProviderTest.xml) |
| A5 | cn.agentplatform.common.authority.ServiceTokensDeploymentModeTest | [A5 XML](baseline/20261028-164553-272/admin-common-xml/TEST-cn.agentplatform.common.authority.ServiceTokensDeploymentModeTest.xml) |
| B1 | cn.agentplatform.app.application.AppCopyConcurrencyTest | [B1 XML](baseline/20261028-164553-272/app-management-xml/TEST-cn.agentplatform.app.application.AppCopyConcurrencyTest.xml) |
| B2 | cn.agentplatform.app.application.DraftMetadataHttpTest | [B2 XML](baseline/20261028-164553-272/app-management-xml/TEST-cn.agentplatform.app.application.DraftMetadataHttpTest.xml) |
| B3 | cn.agentplatform.app.application.IntegrationPolicyResolverHttpTest | [B3 XML](baseline/20261028-164553-272/app-management-xml/TEST-cn.agentplatform.app.application.IntegrationPolicyResolverHttpTest.xml) |
| B4 | cn.agentplatform.app.application.PublishTransactionTest | [B4 XML](baseline/20261028-164553-272/app-management-xml/TEST-cn.agentplatform.app.application.PublishTransactionTest.xml) |

## 逐方法/坏类映射

表中测试简名均由上表完整classname及后文源码目录唯一定位。F=XML failure，E=XML error；每行均仍为非通过。

| XML | 精确name | 状态/类型 | 已见安全事实、源码锚点 | 承接/剩余 |
| --- | --- | --- | --- | --- |
| A1 | illegalConfigurationFailsBeforeCreatingListener(Invalid)[3] | F / org.opentest4j.AssertionFailedError | complete-iam-still-closed；预期IAM_TRUST_CHAIN_UNAVAILABLE，出现IAM_DEV_AUTH_FORBIDDEN；测试:65、:110～117，产品AuthorityProperties:82 | E1；EX-27测试环境隔离，补齐后重验目标门 |
| A1 | illegalConfigurationFailsBeforeCreatingListener(Invalid)[30] | F / java.lang.AssertionError | dev-without-dev-auth输入（测试:100）；:116断言未捕获异常 | E1；EX-27，不能把实际继承true当未提供 |
| A1 | portalCeilingsAreRequiredAndStrictlyBoundByTheDeploymentBinder | F / org.opentest4j.AssertionFailedError | 最终失败期望含IAM_TRUST_CHAIN_UNAVAILABLE；实际出现IAM_DEV_AUTH_FORBIDDEN；测试:162～176，产品AuthorityProperties:82 | E1；EX-27，较早已预期的PORTAL_CONFIG_REQUIRED不能误报为本轮最终根因 |
| A1 | explicitTestProfileMayUseOnlyLiteralLoopbackTrustEndpointsAndIamStaysClosed | F / org.opentest4j.AssertionFailedError | 预期IAM_TRUST_CHAIN_UNAVAILABLE，出现IAM_DEV_AUTH_FORBIDDEN；测试:150～158，产品AuthorityProperties:82 | E1；EX-27，保留literal loopback与localhost负例 |
| A2 | catalogCoversEveryActualControllerTemplateWithoutGrantingGatewayWildcards | F / java.lang.AssertionError | AuthorityPolicyTest:160得到classAt=-1；实际文件为ModelInputMaterialController | T1；EX-27扫描器修正后才可评价完整目录覆盖 |
| A3 | sameRootAppReadAndDebugGrantsCannotBecomeLocalMain | E / cn.agentplatform.common.PlatformException | query:476收到inactive/current拒绝；测试:38在构造proof时出错，尚未到:39的resume负例；源码映射SYS-AUTH-403 | T2；EX-27先细分循环输入；本轮确切首错输入未编码进XML |
| A4 | actorCannotMintAnIamServiceTicketEvenWhenLegacyKeyExists | F / java.lang.AssertionError | ServiceTicketProviderTest:16预期抛错，实际未抛；只清JVM属性未隔离ServiceTokens注册态 | T3；EX-27进程/生命周期夹具隔离，不能清产品安全状态 |
| A5 | actualIamOverridesJvmTestAndClosesAllLegacySignersBeforeListener | F / org.opentest4j.AssertionFailedError | 子场景main:123预期IAM_TRUST_CHAIN_UNAVAILABLE，出现IAM_DEV_AUTH_FORBIDDEN；父方法:27/runScenario:47 | E1；EX-27环境隔离后重验永久关闭旧签发器 |
| A5 | invalidDeploymentDoesNotRegisterPermission | F / org.opentest4j.AssertionFailedError | 子场景main:154预期bindDeployment抛错，实际未抛；父:29/:47；XML未给出确切invalid子项标签 | E1候选；EX-27补逐输入诊断，不能将具体子项推断写成事实 |
| A5 | legacyThenIamClosesSigningAcrossThreads | F / org.opentest4j.AssertionFailedError | 子场景main:139绑定IAM时出现IAM_DEV_AUTH_FORBIDDEN；父:28/:47 | E1；EX-27环境隔离，跨线程关闭断言尚未完成 |
| A5 | isolatedReceiverExplicitlyRegistersIamWithoutOpeningProductionChain | F / org.opentest4j.AssertionFailedError | Receiver:172→SecurityConfiguration:60→AuthorityProperties:82，出现IAM_DEV_AUTH_FORBIDDEN；isolatedReceiver:186 | E1；EX-27，不能据此判接收器装配已通过 |
| B1 | （XML name=""） | E / org.flywaydb.core.internal.exception.FlywaySqlException | AppCopyConcurrencyTest.database:40；SQLSTATE=3D000，Error Code=0；专用库agent_platform_app_copy_test不存在 | E2；EX-00测试库前置，类级失败；该类业务测试未执行 |
| B2 | realDraftIdentityIsTenantScopedAndRequiresServiceAndEditor | E / java.lang.IllegalStateException | Failed to load ApplicationContext；cause=BeanDefinitionOverrideException，bean=authorityProperties，两测试Receiver冲突；方法:69未进入业务断言 | T4；EX-27测试配置隔离；不能开启生产bean覆盖掩盖问题 |
| B3 | externalGrantKeepsNarrowSourceClaimsAndUnionsLabelsThroughEveryDecision | E / java.lang.IllegalStateException | PORTAL_CEILING_INVALID；method:195→update:90→admin:89→LoopbackAuthority.client:120 | T5；EX-27 Portal合法夹具 |
| B3 | policyAndOrdinaryAdminServiceBothWorkInSameMappingAndNoSecretsLeak | E / java.lang.IllegalStateException | 同码；method:93→update:90→admin:89 | T5；EX-27；前面部分断言不能代替整方法完成 |
| B3 | crossedTrustDomainsAndWrongCredentialResourcesWriteNothing | E / java.lang.IllegalStateException | 同码；method:137→LoopbackAuthority.client:120 | T5；EX-27 |
| B3 | malformedSourceAuthenticationRejectsBeforePolicyAndConsumesNoGrant | E / java.lang.IllegalStateException | 同码；method:229→LoopbackAuthority.client:120 | T5；EX-27 |
| B3 | stableAuthenticationRejectsEquivalentOldTokenAfterSamePolicyRevision(String)[1] | E / java.lang.IllegalStateException | 同码；method:180→LoopbackAuthority.client:120 | T5；EX-27，保留参数实例 |
| B3 | stableAuthenticationRejectsEquivalentOldTokenAfterSamePolicyRevision(String)[2] | E / java.lang.IllegalStateException | 同码；method:180→LoopbackAuthority.client:120 | T5；EX-27，保留参数实例 |
| B3 | nonAdminAndWrongTenantCannotMutateEvenWithCorrectServiceRoute | E / java.lang.IllegalStateException | 同码；method:295→update:90→admin:89 | T5；EX-27 |
| B3 | emptySourcePermissionsAreNeverReplenishedFromPolicy | E / java.lang.IllegalStateException | 同码；method:217→update:90→admin:89 | T5；EX-27 |
| B3 | issueBindsStoredPublishedVersionAndInitialRevisionIsOne | E / java.lang.IllegalStateException | 同码；method:158→admin:89 | T5；EX-27 |
| B3 | adminRoleMustStillExistAtTheFinalResourceDecision | E / java.lang.IllegalStateException | 同码；method:302→update:90→admin:89 | T5；EX-27 |
| B3 | policyAndRevokeChangesAdvanceRevisionAndOldExpectedVersionRejects | E / java.lang.IllegalStateException | 同码；method:97→update:90→admin:89 | T5；EX-27 |
| B3 | realResolverBackedGrantsFailAfterRevisionAndExplicitNewAuthenticationRecovers | E / java.lang.IllegalStateException | 同码；method:262→LoopbackAuthority.client:120 | T5；EX-27 |
| B3 | concurrentStaleWritesSerializeAndSnapshotsNeverMixRevisionAndPolicy | E / java.lang.IllegalStateException | 同码；method:280→LoopbackAuthority.client:120 | T5；EX-27 |
| B3 | invalidPolicyAndAuditFailureRollBackRevisionAndPayload | E / java.lang.IllegalStateException | 同码；method:148→update:90→admin:89 | T5；EX-27 |
| B3 | sameAuthenticationCannotChangeBindingsTimesOrOriginalCeiling | E / java.lang.IllegalStateException | 同码；method:251→LoopbackAuthority.client:120 | T5；EX-27 |
| B4 | globalCredentialLookupRequiresDedicatedGatewayIdentity | E / cn.agentplatform.common.PlatformException | method:347→IntegrationService.requirePublishedVersion:214→AuthorityContext.frame:70；XML无code字段，源码映射AUTHORITY_CONTEXT_MISSING | T6；EX-27可信上下文夹具，未到后续网关身份断言 |
| B4 | integrationSecretOnlyShownOnceAndRevocationPersists | E / cn.agentplatform.common.PlatformException | method:315→IntegrationService.requirePublishedVersion:214→AuthorityContext.frame:70；源码映射AUTHORITY_CONTEXT_MISSING | T6；EX-27；一次性秘密/撤销的后续断言未完成 |

## 分类依据与最小后续范围（均未实施）

源码前缀：公共产品 `admin-common/src/main/java/cn/agentplatform/common/`，公共测试 `admin-common/src/test/java/cn/agentplatform/common/authority/`；应用测试 `app-management/src/test/java/cn/agentplatform/app/application/`。下列完整文件路径消除表中简名歧义。

### E1：测试继承宿主开发开关；部分具体输入仍待细分

`admin-common/src/test/java/cn/agentplatform/common/authority/AuthorityConfigurationTest.java:44`～`:56`的completeIam未显式声明agent.dev-auth=false；其application助手`:209`～`:222`仅setDefaultProperties。`ServiceTokensDeploymentModeTest.java:38`～`:40`的新JVM继承进程环境，`:57`～`:64`的StandardEnvironment仍保留systemEnvironment。产品 `admin-common/src/main/java/cn/agentplatform/common/SecurityConfiguration.java:60`读实际agent.dev-auth，`authority/AuthorityProperties.java:82`在IAM+true时故意拒绝。

采集者只提供的非秘密事实：本轮Import-AP导入AGENT_DEV_AUTH=true并由Surefire继承；本轮未显式传-Dagent.dev-auth=true。AGENT_SECURITY_MODE在配置/基础进程中均不存在，collector/argLine亦未指定该系统属性；测试运行时自行改的系统属性未全部追踪。本报告没有读取真实配置来获取这些事实。

因此多项IAM测试先撞IAM_DEV_AUTH_FORBIDDEN，而到不了它们想验证的IAM_TRUST_CHAIN_UNAVAILABLE。A1[30]明确是“未提供devAuth”输入，实际仍继承true；其不抛错不代表产品放行了无devAuth的部署。A5 invalidDeployment仅能确认某次bind意外未抛错；当前XML未标细分输入，源码与继承开关提供候选解释，**不能独断其正好是dev-without-dev-auth**。

承接：EX-27的AuthorityConfigurationTest/ServiceTokensDeploymentModeTest测试环境及子进程助手；在明确测试输入的最高优先级源中隔离相关开发开关，并保留显式true的负例。不改AuthorityProperties的拒绝规则，不靠改真实.env制造绿。回归仍须逐参数实例，保留父子场景身份与退出码。

### T1：源扫描器不支持final class，尚未完成全目录比对

`admin-common/src/test/java/cn/agentplatform/common/authority/AuthorityPolicyTest.java:159`～`:160`仅搜索字面public class。失败文件 `engine-runtime/src/main/java/cn/agentplatform/engine/api/ModelInputMaterialController.java:13`～`:14`为RestController + public final class，所以classAt=-1，不是控制器没有public声明。

该控制器`:21`的GET `/internal/material-scopes/{kind}/{id}`在 `admin-common/src/main/resources/agent-service-authority.json:4`已有model→engine、delegated、material.scope.read项；本错误不能据为此路由未登记。承接EX-27的测试扫描器；修正后必须继续完整集合比较，不能只改期望159/160数字，也不能由本次失败点推断其余路由全部齐备。

### T2：负例在准备合法proof前就失败

`admin-common/src/test/java/cn/agentplatform/common/authority/LocalRunAdvanceAuthorityTest.java:35`～`:39`为四个输入先exchange/authorizeRetained，再断言本地resume被拒绝。错误栈落在`:38`，产品 `HttpCurrentAuthorityClient.java:476`因签名决策active/current=false抛 `AuthorityProtocol.java:11`定义的SYS-AUTH-403。

至少其中status输入仍把app-version ROOT交给RunController.status。当前产品 `admin-common/src/main/java/cn/agentplatform/common/authority/RunObservationSelectors.java:20`～`:23`/`:49`～`:56`要求run-snapshot与真实run ID；测试权威 `LoopbackAuthority.java:653`～`:658`还要求登记观察资源。fixture在`:188`把前置异常转为active=false。它无法先产生预期的合法status proof，便无从验证后续resume拒绝。

承接EX-27拆分/命名每个输入并按现行profile建立合法前置。XML未含循环iteration，**本轮最先被拒的输入仍待精确诊断**；不能称已证实某一具体迭代，也不能为让负例跑到后面而放松产品purpose规则。

### T3：JVM属性与classloader部署权限生命周期混用

`admin-common/src/test/java/cn/agentplatform/common/authority/ServiceTicketProviderTest.java:10`～`:20`只备份/修改JVM属性，`:16`假定此时未注册。产品 `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:17`～`:30`的deploymentMode是classloader生命周期状态；issue能返回说明已有非IAM部署注册，不受这些JVM属性清除影响。注册态跨context关闭保留是其显式不变量。

已证实测试的隔离假设不足；尚未定位本轮是哪一个更早测试完成了注册，不能指定执行顺序或断言产品签发绕过了已注册IAM。EX-27应使用有限独立JVM验证未注册场景及有意注册场景；不得反射reset或修改生产注册态来恢复测试顺序依赖。

### E2：专用测试库未准备，属于EX-00环境前置

`app-management/src/test/java/cn/agentplatform/app/application/AppCopyConcurrencyTest.java:27`～`:40`的BeforeAll直连agent_platform_app_copy_test后migrate，没有建库。XML给出数据库不存在/SQLSTATE3D000；不是SQL迁移内容失败或产品copy断言失败。现有 `scripts/test.ps1:15`～`:17`只准备model/engine两库，不能代替该专用库的前置。

承接EX-00环境准备：核准本仓环回端口、专用测试库及归属后，按协调窗口补齐所需前置并保留本轮红证据，再用新RunId重跑。若要调整通用入口，按EX-00已有scripts/test.ps1白名单独立小评审；本文不执行建库，也不把此类错误改记跳过/通过。

### T4：测试组件扫描混入两个有限Receiver

`app-management/src/test/java/cn/agentplatform/app/application/DraftMetadataHttpTest.java:20`～`:22`启动AppApplication；`app-management/src/main/java/cn/agentplatform/app/AppApplication.java:7`扫描app与common两个包。test-classes中 `app-management/src/test/java/cn/agentplatform/common/authority/PublishedMetadataFixtureMain.java:27`～`:30`及 `PublishedAdmissionFixtureMain.java:56`～`:68`都是@Configuration Receiver并都声明authorityProperties。XML明确记录这两个资源的同名bean覆盖冲突。

承接EX-27隔离测试Receiver/扫描范围，候选白名单是DraftMetadataHttpTest与这两个测试fixture文件。不能以开启生产bean-definition-overriding或删生产安全配置掩盖测试污染；真实部署通常不含test-classes，本错误不足以认定部署扫描缺陷。上下文未启动，该方法业务断言未执行。

### T5：Portal allowlist与显式Actor属性键不一致

`app-management/src/test/java/cn/agentplatform/app/application/IntegrationPolicyResolverHttpTest.java:64`把显式Portal Actor设置成只有dataLabels属性。共享测试夹具 `admin-common/src/test/java/cn/agentplatform/common/authority/LoopbackAuthority.java:113`～`:120`始终声明attributeAllowlist为region/dataLabels，却把显式Actor属性直接当attributeValueAllowlist；因此两组键不相等。

错误链：LoopbackAuthority.client:120 → `admin-common/src/main/java/cn/agentplatform/common/authority/WorkloadCredentialResolver.java:14` → AuthorityProperties.validate:65 → PORTAL_CEILING_INVALID。15项XML均有该码；它们在调用gateway客户端时失败，不能误说全类BeforeAll失败或所有方法主体完全未执行。

这是EX-27合法授权配置夹具可直接承接的具体子类，和Y-4总体“合法默认配置生成”方向相符；应把角色、属性键/值与预期负例一起显式定义，而不是放宽产品ceiling校验。修改共享LoopbackAuthority前需把调用方与公共测试回归纳入文件/验证范围；本报告未扩大任何改动权。

### T6：仅设置旧JWT Actor，未绑定当前资源授权

`app-management/src/test/java/cn/agentplatform/app/application/PublishTransactionTest.java:56`～`:66`只设置JwtAuthenticationToken，两个错误方法`:315`/`:347`随后直接调用IntegrationService.issue。产品 `app-management/src/main/java/cn/agentplatform/app/application/IntegrationService.java:210`～`:214`在真实发布指针锁内要求当前app-version的授权，并由 `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityContext.java:68`～`:74`拒绝缺失frame。

两XML的源码栈准确落此处，源码错误码是AUTHORITY_CONTEXT_MISSING；不是一次性秘密泄漏、撤销持久化或网关身份断言已失败。承接EX-27可信授权上下文夹具迁移，先走现有已验授权/有限接收器建立真实资源绑定，再验原业务场景。它与单纯补Y-4模型配置不同，不能注入任意Actor或删除requireFor来放行。修正后仍可能暴露后续真实缺陷，当前不预判其余断言结果。

## 承接与后续验证界限

本报告仅提出分类和最小候选范围，未替代台账认领：E2归EX-00环境前置；E1/T1/T2/T3/T4/T5/T6分别作为EX-27的独立夹具/隔离/扫描器子项，保留其不同根因、精确方法与剩余。其中A5 invalid子项、A3循环首错输入、A4更早注册者待核实；若合法前置补齐后仍出现产品错误，必须另开/回填产品卡，不沿用“夹具问题”永久豁免。

建议在各子项获准后先定向保存原场景并验证，再按手册先install当前依赖、无-am逐模块回归admin-common/app-management；每次使用新RunId，将failure/error/坏类/跳过/未执行按classname+精确name作集合差。B1空name继续保留类级身份，B3参数化实例保持区分。此次没有运行上述命令，没有修复、通过、零新增或EX-00完成结论。
