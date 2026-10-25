# N3 I1/I2 定向修复报告

作者完成本次具名修复与两类定向验证，等待root审计和原reviewer复审；本报告不签收N3、全Task5、真实Gateway或普通全局IAM。唯一工程 G:/207/项目/算法重组工具/代码/agent-platform，每条shell显式workdir；未访问相邻旧工程。

## 授权与范围

唯一fix brief 9826 B / SHA c33e6732bf9dec8a43234294c4fa3939a52f4de7e950fd4449c352a954e76458。已先读 receiving-code-review 并按实际源码验证：原RunService提交后metadata查询与created之间没有bound，原RuntimeClients任务直接execute，2秒future只限制传输而非proof有效期。新独占开始时物理367source/1795target逐项匹配原N3最终manifest，全部2162文件先保存再编辑/Maven。

改动严格仅既有 RunService、RuntimeClients 两个生产Java和 PublishedAdmissionHttpPostgresTest、RuntimeClientsPublishedHttpTest 两个测试；源码新增0/修改4/删除0。RunController/POM/app helper/common/路由/配置/迁移/runner/四契约/设计及八索引均不改。原N3报告28426 B/SHA0312f487df70a93a124f4e183834c8d88eb3dc251333a7862351fc6a352507a4，初审18890 B/SHAd971492e77ece4178c710185f8aaa0b159a9addb468aa01e3a67a74e46a8ceca，原handoff和阶段完整保留，未追加原报告或审查。已有获批Apache5.4.4/core5.3.4依赖保持，未安装/升级依赖或POM。

## I1：提交后的metadata与调度通知

保留真正store.atomic返回后的原bound检查，在独立responseMetadata queryForMap返回并通过created/replay分支判断后，scheduler.created紧前新增同caller/frame本地requireBound。查询可能等连接/PG响应，已结束事务内的SET LOCAL不能覆盖它；新增fence既不刷新网络也不改已提交事实。原调度器fresh-current恢复与Admission.close仍保持。

具名新例 expiryAfterCommittedResponseMetadataNeverNotifiesSchedulerAndFreshPollRecovers 用既有afterQuery透明JDBC代理，仅精确提交后SELECT返回时一次推进验证时钟6秒，且断言该时刻非事务；这是控制钩子而非自然等待。调度观察保持原created override真实调用super。

行为红fix-red01在未改调度/授权行为的生产代码上实际403之后观察createdCalls=1（应0）；raw末态已有attempt1、2nodes、33scopes，说明失效请求确已nudge并由另取合法current的worker推进，不能写成PENDING/attempt0或声称后台绕过授权。修复后同例先确认拒绝、createdCalls0、原1run/2nodes/15scopes、PENDING/attempt0、INTERACTIVE容量2恢复；随后新合法main复用同requestId及poll，1run不重复且持久SUCCEEDED。green raw末尾attempt1来自该有意恢复，与负例检查时attempt0是不同时间点。旧expiryDuringNodeMaterialsRollsBackButAfterActualCommitPreservesPendingAndSlot原断言继续在实际20项中通过。

## I2：真实排队/连接后发送前

每次published新建独立HttpClientContext，放入真正私构造RetainedAuthorization与本次System.nanoTime单调deadline。固定addRequestInterceptorLast读取这两个私有attribute；任一缺失/类型错误403默认拒绝，真实proof.requireVerified用原private verificationClock检查current/ticket且不读ThreadLocal；deadline剩余非正则503 APP_PUBLISHED_UNAVAILABLE。任务入口不放一个可替代interceptor的检查，caller仍保留原requireBound。

同一deadline在提交前定为2秒，response.get使用剩余NANOSECONDS，排队/lease/connect后的处理器使用相同值，绝不在worker启动或连接后重新计2秒。工作线程PlatformException经ExecutionException.getCause保持原403/错误码（deadline的503亦不伪造授权成功）；其余执行/IO/timeout仍暂不可用。finally仍cancel request及未完成future，close仍立即关闭Apache并shutdownNow/有界await owned executor。4MiB/identity/无retry/redirect/auth挑战/实际HTTP401/403/409/503和legacy闭锁未放宽。

具名新例 proofExpiringWhileRealExecutorQueuedNeverSendsPublishedGet 使用真实单线程有界ThreadPoolExecutor，首任务占住线程；真实published任务入队后，有限控制线程推进私有验证时钟6秒再释放。所有latch有timeout/finally释放，真实Apache和app HttpServer不替换。红实际app GET=1而应0，整个client testcase仅0.407s，明确未耗尽2秒传输deadline。绿实际app GET=0且403，client正常结束，owned executor终止、控制线程join完成。该例通过唯一worker proof检查处即request interceptor；未增加task入口替代检查。

测试需要的唯一小接缝为RuntimeClients文件内package-private受信构造接收owned ExecutorService；正常public装配仍原owned虚拟线程executor，部署属性验证、固定origin、缺能力闭锁、明确legacy构造保留。没有新增生产类、部署开关、授权框架、跨线程requireBound或public VA重绑。

该检查与实际wire write不是原子撤权，不能撤回已发送字节；现有Apache cancellation仍是timeout后的防线。没有额外TLS/连接等待探针，不宣称新测试实际模拟TLS等待；连接后位置来自固定实际源码，真实queue请求验证interceptor生效。

## 实际命令、红绿与当前覆盖

以下全部由原受控runner执行Java21/offline Maven，workdir为唯一工程。自有run-stage.mjs先登记精确log/fresh-secret、物理冻结367source，并验证未改app helper source/class SHA后复用原合法own app/common 115项classpath产物，0次新javac/setup。每轮实际Running只有这两类；另76份既有incidental XML仅登记，不计执行。

- `node .tools/run-iam-module-tests.mjs iam-stage-5-n3-fix-red01 engine-runtime PublishedAdmissionHttpPostgresTest#expiryAfterCommittedResponseMetadataNeverNotifiesSchedulerAndFreshPollRecovers,RuntimeClientsPublishedHttpTest#proofExpiringWhileRealExecutorQueuedNeverSendsPublishedGet` — exit 1; 2 tests / 2F / 0E / 0skip; [raw](iam-stage-5-n3-admission-app-client-fix/iam-stage-5-n3-fix-red01/raw.log)、[Running/XML/source manifest](iam-stage-5-n3-admission-app-client-fix/iam-stage-5-n3-fix-red01/manifest.json)。
- `node .tools/run-iam-module-tests.mjs iam-stage-5-n3-fix-final01 engine-runtime PublishedAdmissionHttpPostgresTest,RuntimeClientsPublishedHttpTest` — exit 0; 40 tests / 0F / 0E / 0skip; [raw](iam-stage-5-n3-admission-app-client-fix/iam-stage-5-n3-fix-final01/raw.log)、[Running/XML/source manifest](iam-stage-5-n3-admission-app-client-fix/iam-stage-5-n3-fix-final01/manifest.json)。

原始受控命令的stdout/exit和helper SHA在各command.json，两个raw逐字保存；所有40个具名case与XML SHA见 [test-audit](iam-stage-5-n3-admission-app-client-fix/test-audit.json)。当前40/0F/0E/0skip来自单次fix-final01，覆盖当前两类各20。原N3 final01另三类40与final03 client19保留为历史；原admission19/client19由本轮20/20取代，未相加。未改原status11/scheduler10相关逻辑，按brief不重复跑这两旧类；其测试源码SHA在current-covered-tests列明不变。

### cn.agentplatform.engine.infra.PublishedAdmissionHttpPostgresTest

- `explicitVersionDebugStepAndV1AliasAreClosedBeforeAppSend`
- `repeatedPostUsesOneProtectedRrViewAndPreservesOriginalGrant`
- `actualPublishedAdmissionAndOwnedWorkerReturnFullStatus`
- `everyAdmissionWriteFailureRollsBackRunAuthorityNodesAndScopes`
- `narrowedCurrentCannotAdmitInitialABOrReadItsAcceptedMaterials`
- `expiryAfterCommittedResponseMetadataNeverNotifiesSchedulerAndFreshPollRecovers`
- `actualAdvisoryWaitCannotOutliveBoundProofAndLockTimeoutIsFinite`
- `publishedCodeConditionAndOutputMappingUseOriginalFlowDriver`
- `ambientTransactionAndMissingFrameRejectBeforeAnyAuthorityOrAppIo`
- `expiryDuringNodeMaterialsRollsBackButAfterActualCommitPreservesPendingAndSlot`
- `legacyAcceptedRowWithoutAuthorityIsNeverReadOrBackfilled`
- `remainingOwnedWorkerDependenciesAreRejectedBeforeAdmission`
- `workerCanFinishBeforeCreationStatusAndNarrowingStillLeaksNoPayload`
- `emptyDependencyContainersAndNullsAreLegalPublishedStructure`
- `unsupportedGraphAndEveryDependencyAreRejectedBeforeLegacyPreparation`
- `explicitFlowNonemptyGraphAndOriginalInputDagBudgetGuardRemainRequired`
- `persistLockRaceReturnsSameRunOutsideRcWithoutDuplicateNudge`
- `actualAppPointerChangeAndDisableCreateNothing`
- `capacityFullRecheckWaitsForCommittedSameRequestAndReadsOnlyAfterLock`
- `changedMainRootAndMissingScopesRejectBeforeEnginePayload`

### cn.agentplatform.engine.infra.RuntimeClientsPublishedHttpTest

- `oversizedBodyCancelsBeforeUnboundedAllocation`
- `unknownServiceNeverSignsOrSends`
- `headersThenUnfinishedBodyHasCompleteDeadlineAndClientCloses`
- `numericIdsVersionKeyAndStoredDigestAreAllValidated`
- `proofExpiringWhileRealExecutorQueuedNeverSendsPublishedGet`
- `publicVerifiedObjectAndUnboundCaptureCannotSend`
- `completeResponseStillRequiresOriginalBoundFrame`
- `appHttpFailureSemanticsRemainDistinct`
- `explicitLegacyConstructionKeepsKnownAppSkillModelRoutes`
- `iamActorGetPostStreamAndUnknownTargetsNeverSignOrSend`
- `redirectIsNotFollowedOrRetried`
- `disconnectedPublishedGetIsNeverAutomaticallyResent`
- `forwardReturningAfterProofExpiryCannotSendAppRequest`
- `trustedIamDoesNotFallBackWhenJvmModeSaysTest`
- `nonIdentityEncodingIsRejectedWithoutAutomaticDecompression`
- `boundMainForwardsExactOperationGrantOriginAndNeverPortalSource`
- `malformedJsonIsUnavailableWithoutBodyEcho`
- `trustedModeRejectsMissingCapabilityAndUnsafeOrigins`
- `unsafeCodeAndWrongRootNeverSend`
- `interruptionAfterHeadersCancelsRequestAndPreservesInterrupt`

## 数据库与真实业务链

仍是原真正RunController/RunService/RuntimeClients/ServiceAuthorization receiver，独立app child使用原AppController/AppService/guards/PG；main来自签名fixture，未经过真实GatewayController。固定两个已存在fixture库 agent_platform_iam_admission_engine_v12、agent_platform_iam_admission_app_v12，两轮记录DB_EXISTED=true，源max217/104，正常Flyway仅validate/up-to-date；engine连续200..217/app100..104所有history success。有限history记录16条见 [数据库记录](iam-stage-5-n3-admission-app-client-fix/database-history.json)，未做旧history preflight、新迁移、主库或旧D3b写入。两类中原缺scope单行故障注入finally恢复触发器的断言继续通过。无主六服务/真实外部endpoint探针。

## 完整冻结、patch与target归因

source367→367（+0/~4/-0），target1795→1798（+3/~10/-0），业务JAR变化0。全部2162物理before位于自有local/before及evidence/before；frozen367、target-final1798及source/target完整前后清单、增加修改删除清单齐全。source-before SHA 297a5b55920d7bc38adb8a4af8d7426fcbaa9827cf81a6805d89d76fd48603f5，source-after SHA 730a2b8c74caa0c47fc1244b47055d956776e3df4b2a0f125d42f4b093b92656；target-before SHA eb7cc047f3181f1324a2859090bc2648a0d0e02ba8e672fbe86d689170543876，target-after SHA 1a4ac516fa75fe4ffcb706db938dc05caee476f605e8b4b10aeebd7f087f9923。

全context patch 251529 B / SHA bea265d2ca9fee391a6206835624afd06d96d594b13cc349fbb660ad45478eec；真实git apply --check=0、apply=0，从物理before重放367文件与final逐字相等，不复制after冒充apply。实际无HEAD/remote/commit，未使用HEAD比对或伪造空diff。见 [patch-check](iam-stage-5-n3-admission-app-client-fix/patch-check.json)、[真实apply](iam-stage-5-n3-admission-app-client-fix/apply.json)。

10项target修改逐项为RunService.class、RuntimeClients.class和其PublishedResponse内部class（编译带关联源行号更新）、两个测试主class、testCompile createdFiles.lst、两类各XML和txt报告。3项新增：

- 新增 `engine-runtime/target/surefire-reports/2026-10-20T23-33-06_006.dumpstream` 344 B。
- 新增 `engine-runtime/target/surefire-reports/2026-10-20T23-34-02_321.dumpstream` 344 B。
- 新增 `engine-runtime/target/test-classes/cn/agentplatform/engine/infra/RuntimeClientsPublishedHttpTest$1.class` 1800 B。

新匿名class对应测试有界ThreadPoolExecutor的透明execute观察；两份344B dumpstream均是Windows Surefire manifest-JAR跨盘根提示，原文留存在完整target-final。无诊断文件清理、无argLine或全局属性掩盖。

## 固定源机制、许可与证据噪声

已实际窄读固定Apache5.4.4：HttpClientBuilder contextAdaptor=castOrCreate、LAST处理器与Protocol→Connect→Main链；ConnectExec lease/connect/upgradeTls结束后chain.proceed；MainClientExec httpProcessor.process后才execRuntime.execute，RuntimeException立即discardEndpoint并rethrow；InternalHttpClient将同每请求context送入scope；HttpClientContext保留已类型化context；InternalExecRuntime执行前只认识cancel、不认识proof；CloseableHttpClient context response-handler重载的close机制。新抽取ConnectExec/HttpClientContext/InternalHttpClient来自既有官方sources.jar，753157 B / SHA1e56c35e4130f4917012cf0c8d021403bca2c805f60c9e689c65eac7d9c15312，其余固定源先SHA核对再复用。LICENSE/NOTICE、官方URL、版本与10份源/许可文件SHA均在 [reading-manifest](iam-stage-5-n3-admission-app-client-fix/upstream/reading-manifest.json)。common私有Retained requireVerified只读窄查，源码未改。

原JDK jdk-21+35仅参考tag，实际运行Temurin/Adoptium21.0.12+8；本轮未宣称二进制精确同源或重跑JDK方案。未网络抓取依赖、扫描SDK/Maven缓存或重跑已绿套件。

输出保留unchecked提示、路径中文编码显示噪声、实际lock-timeout SQLSTATE55P03/servlet ERROR、两份dumpstream；不能称pristine。两红为实际业务断言失败，无setup失败。工具噪声另保留：一次rg通配符Windows123；首次组合输出budget截断后具名补读；finalize在完整freeze/apply/replay/test-audit完成后错误把sources.jar basename当根路径导致ENOENT，仅续作未写的upstream/resources，没有重新跑成功步骤或业务测试。原始失败脚本和实际诊断转录见 [tool-noise](iam-stage-5-n3-admission-app-client-fix/tool-noise.md)。

## 资源、保护与交接

两轮own app child PID45196/61136都真实CHILD_EXIT=0/STOPPED；21个engine context与2个app端点共23个已记录ephemeral端口，独立OS检查无PID/Listen残留，未终止任何其它进程或查主服务端口。client小HTTP fixture各自finally stop和executor终止断言均实际执行，queue例还关闭client、释放latch并join控制线程；未把未记录client端口冒称逐端口OS审计。固定fixture DB留存审计不drop。见 [resource-cleanup](iam-stage-5-n3-admission-app-client-fix/resource-cleanup.json)。

before合法复用root已核90336项静态基底，补采10712个新增静态（含原N3全部产物/原审查/root完整diff和审计工具记录/fix brief），共101048项。原28runner与8个最新索引逐SHA核。after仅执行一次全量物理保护；精确排除六runtime目录、postgres.log、root state、自有fix目录/报告与两轮登记log/fresh-secret，无整个.local或宽stem排除。最终after结果、报告SHA及source/target归还记录将在机器handoff冻结。

普通全局IAM_TRUST_CHAIN_UNAVAILABLE保持闭锁；N4真实gateway/普通mode统一、N4a legacy fixture mode/profile、独立GETstatus/SSE、工具模型CHAT、完整恢复、Observer/RR1/RR2/门户浏览器/真实IAM本体K8s容量/全Task5继续待后续处理。作者未改共享索引、不package/deploy/commit/push。

## 最终一次保护与归还

唯一after采样 2026-10-20T15:41:09.087Z 至 2026-10-20T15:45:11.195Z：101048 个受保护文件共 7634684209 B，覆盖90336旧基底及10712新增静态；修改0/新增0，28runner变化0、8最新索引变化0。全部2165个source/target文件与冻结副本一致，无冻结后新增/缺失；原N3报告和初审逐字不变。见 [一次保护结果](iam-stage-5-n3-admission-app-client-fix/protection-summary.json)、[完整差异](iam-stage-5-n3-admission-app-client-fix/protected-changes.json)。

本次fix交接状态 DONE（仅I1/I2授权修复范围）。两个Maven执行均已返回，final01之后未再运行Maven或改源码/测试/target；资源已释放，source/target所有权在机器handoff写成后归还root，等待其独立审计和原reviewer复审。未替root签收N3或更新共享索引。机器记录见 [handoff](iam-stage-5-n3-admission-app-client-fix/handoff.json)。
