# G6 Task5 N3 原受理与固定 app published 客户端报告

本段实现完成并冻结，供 root 独立审计/双审；不签收全 Task5 或正式生产 IAM。唯一工程和所有 shell workdir 均为 G:/207/项目/算法重组工具/代码/agent-platform。没有访问相邻旧工程/其配置、数据或服务，没有启动/修改主六服务，没有 package/deploy/commit/push。

## 当前结果与准确测试口径

- final01 的 PublishedAdmissionHttpPostgresTest **19**、FlowMaterialStatusPostgresTest **11**、OwnedFlowSchedulerPostgresTest **10**：实际三类 **40/0F/0E/0skip**。
- final03 的 RuntimeClientsPublishedHttpTest：实际 **19/0F/0E/0skip**。
- 这是两个执行阶段组成的当前受影响覆盖，**不是一次59/0的整套运行**。final01 原始整轮是59/0F/1E，final02 client19/0F/1E，全部保留。final01→final02→final03仅新客户端 legacy 测试的 mode/profile 装配变化；三个生产 Java、POM、其余三个测试源未变。[当前选择及源差异](iam-stage-5-n3-admission-app-client/current-covered-tests.json)。
- 实际执行环境为 Temurin **21.0.12+8-LTS**，路径 jdk-21.0.12.8-hotspot；固定 JDK机制参考源码 tag **jdk-21+35**，二者不是精确同版本。实际 -version 输出见 [java-runtime.json](iam-stage-5-n3-admission-app-client/java-runtime.json)。

## 范围、原始基线与具名例外

正式唯一 brief 原始17150B/SHA cf5246583b8fea428592e2cefe27a2d1cc05bd31665a8c704b84911fc2865a93 保持原字节。N2+I1 current source364/target1764，在任何 Maven/源码变更前逐项核最终 N2 fix manifest 并物理复制；before物理2128文件随后再次逐字复核。原 source manifest SHA ea61bf74aeea2cce1e3d464edc4c091a6e2afc5a8a568ca048b2cae3bb6e93e0、target SHA 19b714cddf010c4d7a47bde610bebf28c4126dc076ae0449668875a4d0b517d3。

当前before对90336个既有受保护文件实际哈希，包含全部旧84553保护路径及本轮前新静态证据；28原runner未改，8索引逐最新 afterSHA 核对后固定。未写共享索引/原提案/四契约。保护after按唯一一次最终取样另见 protection-summary.json；精确排除六runtime目录、postgres.log、root state、本轮own目录/精确报告、逐轮预登记的15个log/fresh-secret精确路径，不宽豁免整个.local或宽stem。

生产 Java 只变更原 RunController、RunService、RuntimeClients。Root另具名授权唯一 POM 例外：engine-runtime/pom.xml 增加 **org.apache.httpcomponents.client5:httpclient5:5.4.4**，core5=5.3.4由原 Boot3.5.0 BOM 管理。修改POM之前 scope-addendum.md 已冻结 SHA b2d15a89d4b12cd135c3acd3db2c1bf6ebfd048e178bd80e05d76f981c1b15d9，原POM SHA 30bb8920dcf6baf2aff404f9a0c7cf3e918a40c9cadc6bac03fff5d7c34c1982及物理副本存在。[具名例外](iam-stage-5-n3-admission-app-client/scope-addendum.md)、[冻结记录](iam-stage-5-n3-admission-app-client/scope-addendum-freeze.json)。没有root/common POM、新生产类、common/route/yml/schema/迁移改动。

例外有真实原因：固定 JDK MultiExchange 的 ConnectException/ConnectionExpiredException 分支可重试 GET，实际 red复现3次HTTP对期望2次。URLConnection streaming替代会在getInputStream中先走getOutputStream并把GET改POST，writeRequests另有无streamingguard的重写分支，不能以它声称零重试。Root认可后才替换传输；没有全局重试属性/反射JDK/手写HTTP栈。此前精确engine实际classpath132项未含可禁重试客户端。新依赖三个精确jar本已在own .tools/m2，未加/升级别库、未安装业务JAR。

加入Apache的工厂影响已读源：engine只有RuntimeClients和TestInputClient创建RestClient，二者均显式SimpleClientHttpRequestFactory；AgentDriver仅捕获异常。Spring6.2.7 DefaultRestClientBuilder.initRequestFactory优先显式factory，因而现有路径不改自动选择。不把这一判断推广到别域/未来默认factory。

## 具体实现和边界

RunController保留run(String,JsonNode)原签名，在可信IAM装配下只让真实admin POST模板进入；v1 alias拒绝。RunService的原createInternal继续承担参数、guard、InputDeclarations、GraphRules DAG、预算、输出映射及原Admission/scheduler/status；没有复制一套create流程。IAM来自验证AuthorityProperties/serviceId=engine的RuntimeClients固定构造；旧显式legacy构造保留。缺current能力/缺受理store装配直接拒绝，未依据Actor/decoder/System属性/缺Bean推断mode。全局IAM_TRUST_CHAIN_UNAVAILABLE源码保持原样；这些是隔离真实业务装配。

具体published方法只接code和既有私构造RetainedAuthorization。开始、forward返回且发送前、完整body返回及解析后均requireBound同frame；只从retained.current派生app.published.read、同root/operation/grant/origin，无source透传/exchange/resume。固定trusted origin：HTTPS，明确隔离只127.0.0.1 HTTP；userinfo/query/fragment/路径前缀及坏code拒绝。实际只GET /internal/apps/{code}/published，版本字段按真实D3b结构验证：外层OK/data、appCode/versionNo/candidateSha256/appVersionKey及相等正整数appVersionId/versionId。candidate是存储候选摘要，未用返回JSON hash替代。

Apache逐实例关闭HTTP request retry/redirect/cookie/auth cache/content compression/认证挑战处理，Accept-Encoding=identity且拒非identity。1s connect，2s外层future覆盖headers和整个body，4MiB字节限制；超限先cancel再关闭流，截止/中断取消request与future，避免close阶段无界drain。close立即关client及有界关owned虚拟线程executor。这里的零重试指HTTP请求不重发，底层多地址TCP尝试不被冒称另一HTTP请求。401（含Basic challenge）/403/409/503保持，JSON/协议/IO/截止为APP_PUBLISHED_UNAVAILABLE，不回显票/密钥/上游完整body。

createInternal先拒ambient Tx，才进行任何frame/current/app I/O，确保原REQUIRED atomic拥有实际外层commit。prepareSnapshot前穷尽拒非FLOW/空图/未知和外发节点/controlled-debug历史字段、config的外部依赖、node依赖及dependencyVersions四类worker依赖；空依赖容器/null合法，已知空node引用及空dataSources只在准备中正常化。原显式versionNo/step/debug/evaluation/session/history入口未开放。新gate红发现的剩余worker依赖已在受理前拦截。

实际snapshot/inputs准备完成后再刷新主frame retained并核全部initial角色/region等义务覆盖；A/B→A在INSERT前拒绝。原requestId advisory锁、Admission.persist及短本地事务保留，仅该IAM Tx set local statement_timeout=2000ms/lock_timeout=1500ms，锁后/关键写后/callback末都是本地bound检查，无锁内网络刷新。RunAuthorityStore.createPublished替换旧run INSERT，真实app PG数字versionId为唯一来源；E1b生成publicId与input_ref一致，TraceStore.createNode写全部初始节点scope。

首次命中、容量满锁重查、persist竞态重查三处只有窄owner/id/publicId/app/trigger/mode metadata；已存在分支退出RC/锁后才statusPublished单次RR view，复用原verifyReplay和抽出的原完整status组合，不二次拼view/锁内读inputs。所有候选先核TASK/FULL/owner/app，root/authority/scopes在payload前校验。不同main重放保留旧grant/initial/origin且不重复node提交。完整status仍含runId/appCode/status/eventsWatermark/inputs/output/fileRefs/error/versionRefs/nodes/events/paused及原节点、事件字段。

真正store.atomic返回后、scheduler.created之前检查原bound；提交后失效保留PENDING/材料已提交事实，finally释放Admission slot，不发过期nudge/响应。新合法main可读同run，原owned scheduler凭新local current自行推进，未再插入/回填。worker先完成也走完整受保护响应；响应前current收窄拒绝，已提交终态保留。

## 真实HTTP、数据库、测试启动

实际两服务HTTP/context：engine原RunController/RunService/RuntimeClients、ServiceAuthorization receiver、N2 scheduler/FlowDriver；app原AppController/AppService/真正guards/PG。签名fixture直接产生gateway caller main，**未经过真实GatewayController**，N4仍待做。app test-only薄main在app模块自己的cn.agentplatform.common.authority包，沿既有fixture惯例访问package-private隔离构造；engine不反射/加载兄弟app target，不引app业务JAR。

app窄javac只own-module test源，Java21、-encoding UTF-8、-proc:none、own app/common+既有依赖115项classpath、输出app/target/test-classes。凭据来自原runner child env及私有STDIN，未复制.env parser，配置/secret无日志或明文报告。setup编译计0测试：[compile记录与源](iam-stage-5-n3-admission-app-client/compile/provenance.json)。最初visibility编译失败原诊断保留；该旧包源从首个immutable阶段恢复并与编译前实际记录SHA逐字核对，明确标记hash-verified reconstruction，不伪称当时物理副本。

固定新库仅agent_platform_iam_admission_engine_v12和agent_platform_iam_admission_app_v12，127.0.0.1:25432/agent_v12。先核实际源max engine217/app104、目标exists/history，然后正常连续迁移200..217、100..104；无新迁移/无旧215/216 preflight/旧D3b套件/耗尽probe。[76条阶段原始history记录](iam-stage-5-n3-admission-app-client/database-history.json)。app为每个负例seed另一真实published PG行，版本行不可变；没有把UPDATE失败改成绕过app保护。app数字id来自PG identity并由原authorizedSnapshot返回。

按brief显式允许的两个旧类，仅有限写其既有agent_platform_iam_flow_material_v12和agent_platform_iam_owned_flow_v12 fixture；未跑common全套/N1全部/N2Materials/LocalCurrent/旧D3b/七reactor，不写主库或旧app_payload/policy/skill/model库。

缺scope负例仅新engine库单个已创建测试run的run-inputs行：同本地Tx BEGIN→只disable material_scope_immutable→delete→finally re-enable→COMMIT，并查询tgenabled=O；故障注入与后续业务请求分开。该受损fixture不算合法受理/修复/回填。所有write故障在run/authority/node及第1/4/7/8/12/15 scope点回滚0新增；node期间截止回滚；真实commit后截止保留1run/2nodes/15scopes/PENDING/attempt0且slot恢复，再由新合法current实际推进。有限并发实际覆盖三处requestId、容量满、advisory等锁后proof失效与真实55P03锁超时。

原未处理SQL异常进入/error后被现guard闭锁为403，这是保留边界，未将它冒称新的授权判断或改common错误映射；负例同时验证真实SQLSTATE55P03、等待有界、写入0。原始stack/error噪声全部保留。

## 阶段证据、命令与噪声

所有命令均为原受控runner/Java21/offline Maven。每阶段raw及实际Running对应XML、确切source物理冻结；旧incidental XML只登记不计执行。[命令清单](iam-stage-5-n3-admission-app-client/commands.json)、[逐阶段审计](iam-stage-5-n3-admission-app-client/test-audit.json)、[噪声审计](iam-stage-5-n3-admission-app-client/noise-audit.json)。下面raw链接从本证据同名目录解析。

| 阶段 | 实际XML统计 | 性质 | 原始记录 |
| --- | --- | --- | --- |
| iam-stage-5-n3-red01 | 1/1F/0E/0skip | 行为红：未知 null service 是 NPE，未提供要求的 403 拒绝 | [raw ](iam-stage-5-n3-admission-app-client/iam-stage-5-n3-red01/raw.log)、[source/Running/XML ](iam-stage-5-n3-admission-app-client/iam-stage-5-n3-red01/manifest.json) |
| iam-stage-5-n3-red02 | 0/0F/0E/0skip | setup：新测试括号语法错误，未运行测试 | [raw ](iam-stage-5-n3-admission-app-client/iam-stage-5-n3-red02/raw.log)、[source/Running/XML ](iam-stage-5-n3-admission-app-client/iam-stage-5-n3-red02/manifest.json) |
| iam-stage-5-n3-red03 | 0/0F/0E/0skip | setup：FlowDriver 实际第二参数为 AgentDriver；新测试传参编译错误，未运行测试 | [raw ](iam-stage-5-n3-admission-app-client/iam-stage-5-n3-red03/raw.log)、[source/Running/XML ](iam-stage-5-n3-admission-app-client/iam-stage-5-n3-red03/manifest.json) |
| iam-stage-5-n3-red04 | 1/0F/1E/0skip | setup：gateway fixture 在 A/B actor 设置前建立，allowlist 不匹配；尚非受理行为红 | [raw ](iam-stage-5-n3-admission-app-client/iam-stage-5-n3-red04/raw.log)、[source/Running/XML ](iam-stage-5-n3-admission-app-client/iam-stage-5-n3-red04/manifest.json) |
| iam-stage-5-n3-red05 | 1/1F/0E/0skip | 真实 HTTP 行为红：有效 main 到原受理，旧 Actor client 被闭锁，期望200实际403，新增run/nodes/scopes=0 | [raw ](iam-stage-5-n3-admission-app-client/iam-stage-5-n3-red05/raw.log)、[source/Running/XML ](iam-stage-5-n3-admission-app-client/iam-stage-5-n3-red05/manifest.json) |
| iam-stage-5-n3-green01 | 2/1F/0E/0skip | 受理链已进入；断言在 attempt=2 时早于聚合终态，观察为 RUNNING，不是假称 worker 失败 | [raw ](iam-stage-5-n3-admission-app-client/iam-stage-5-n3-green01/raw.log)、[source/Running/XML ](iam-stage-5-n3-admission-app-client/iam-stage-5-n3-green01/manifest.json) |
| iam-stage-5-n3-green02 | 14/3F/0E/0skip | malformed JSON 400是真实实现缺口；另两项是 END args 及 operation claim 层级的测试装配/断言错误 | [raw ](iam-stage-5-n3-admission-app-client/iam-stage-5-n3-green02/raw.log)、[source/Running/XML ](iam-stage-5-n3-admission-app-client/iam-stage-5-n3-green02/manifest.json) |
| iam-stage-5-n3-green03 | 25/3F/5E/0skip | 测试 fixture 尝试 UPDATE 已发布不可变版本，app子进程退出并引发后续管道错误；改为每变体 seed 新真实发布行。另有 claim 命名及故障异常映射预期错误 | [raw ](iam-stage-5-n3-admission-app-client/iam-stage-5-n3-green03/raw.log)、[source/Running/XML ](iam-stage-5-n3-admission-app-client/iam-stage-5-n3-green03/manifest.json) |
| iam-stage-5-n3-green04 | 29/2F/0E/0skip | 两个负例期待500而实际/error被原guard闭锁为403；原始日志保留。故障注入改用可辨PlatformException500，真实锁超时另断言SQLSTATE55P03，未改公共错误映射 | [raw ](iam-stage-5-n3-admission-app-client/iam-stage-5-n3-green04/raw.log)、[source/Running/XML ](iam-stage-5-n3-admission-app-client/iam-stage-5-n3-green04/manifest.json) |
| iam-stage-5-n3-retry-red01 | 4/1F/0E/0skip | 真实自动重试红：warmup+失败GET实际3次而期望2次；同轮3个受理边界已绿 | [raw ](iam-stage-5-n3-admission-app-client/iam-stage-5-n3-retry-red01/raw.log)、[source/Running/XML ](iam-stage-5-n3-admission-app-client/iam-stage-5-n3-retry-red01/manifest.json) |
| iam-stage-5-n3-client-green01 | 17/0F/0E/0skip | Apache实际客户端17项绿 | [raw ](iam-stage-5-n3-admission-app-client/iam-stage-5-n3-client-green01/raw.log)、[source/Running/XML ](iam-stage-5-n3-admission-app-client/iam-stage-5-n3-client-green01/manifest.json) |
| iam-stage-5-n3-gate-red01 | 1/1F/0E/0skip | 真实前置gate红：未迁移 dependencyVersions.skillRefs 被先受理，实际200对期望403 | [raw ](iam-stage-5-n3-admission-app-client/iam-stage-5-n3-gate-red01/raw.log)、[source/Running/XML ](iam-stage-5-n3-admission-app-client/iam-stage-5-n3-gate-red01/manifest.json) |
| iam-stage-5-n3-final01 | 59/0F/1E/0skip | 实际4类59/0F/1E；三个受理/原受影响类40项绿，仅新legacy测试缺JVM mode/profile | [raw ](iam-stage-5-n3-admission-app-client/iam-stage-5-n3-final01/raw.log)、[source/Running/XML ](iam-stage-5-n3-admission-app-client/iam-stage-5-n3-final01/manifest.json) |
| iam-stage-5-n3-final02 | 19/0F/1E/0skip | 仅client19/0F/1E；mode已设test，但还缺原ServiceTokens所需test profile | [raw ](iam-stage-5-n3-admission-app-client/iam-stage-5-n3-final02/raw.log)、[source/Running/XML ](iam-stage-5-n3-admission-app-client/iam-stage-5-n3-final02/manifest.json) |
| iam-stage-5-n3-final03 | 19/0F/0E/0skip | 仅client19/0F/0E/0skip；该legacy测试自身设置并finally恢复mode+profile | [raw ](iam-stage-5-n3-admission-app-client/iam-stage-5-n3-final03/raw.log)、[source/Running/XML ](iam-stage-5-n3-admission-app-client/iam-stage-5-n3-final03/manifest.json) |

精确命令（每条workdir同本报告第一段）：

```text
node .tools/run-iam-module-tests.mjs iam-stage-5-n3-red01 engine-runtime RuntimeClientsPublishedHttpTest
node .tools/run-iam-module-tests.mjs iam-stage-5-n3-red02 engine-runtime PublishedAdmissionHttpPostgresTest
node .tools/run-iam-module-tests.mjs iam-stage-5-n3-red03 engine-runtime PublishedAdmissionHttpPostgresTest
node .tools/run-iam-module-tests.mjs iam-stage-5-n3-red04 engine-runtime PublishedAdmissionHttpPostgresTest
node .tools/run-iam-module-tests.mjs iam-stage-5-n3-red05 engine-runtime PublishedAdmissionHttpPostgresTest
node .tools/run-iam-module-tests.mjs iam-stage-5-n3-green01 engine-runtime RuntimeClientsPublishedHttpTest,PublishedAdmissionHttpPostgresTest
node .tools/run-iam-module-tests.mjs iam-stage-5-n3-green02 engine-runtime RuntimeClientsPublishedHttpTest,PublishedAdmissionHttpPostgresTest
node .tools/run-iam-module-tests.mjs iam-stage-5-n3-green03 engine-runtime RuntimeClientsPublishedHttpTest,PublishedAdmissionHttpPostgresTest
node .tools/run-iam-module-tests.mjs iam-stage-5-n3-green04 engine-runtime RuntimeClientsPublishedHttpTest,PublishedAdmissionHttpPostgresTest
node .tools/run-iam-module-tests.mjs iam-stage-5-n3-retry-red01 engine-runtime RuntimeClientsPublishedHttpTest#disconnectedPublishedGetIsNeverAutomaticallyResent,PublishedAdmissionHttpPostgresTest#everyAdmissionWriteFailureRollsBackRunAuthorityNodesAndScopes,PublishedAdmissionHttpPostgresTest#actualAdvisoryWaitCannotOutliveBoundProofAndLockTimeoutIsFinite,PublishedAdmissionHttpPostgresTest#workerCanFinishBeforeCreationStatusAndNarrowingStillLeaksNoPayload
node .tools/run-iam-module-tests.mjs iam-stage-5-n3-client-green01 engine-runtime RuntimeClientsPublishedHttpTest
node .tools/run-iam-module-tests.mjs iam-stage-5-n3-gate-red01 engine-runtime PublishedAdmissionHttpPostgresTest#remainingOwnedWorkerDependenciesAreRejectedBeforeAdmission
node .tools/run-iam-module-tests.mjs iam-stage-5-n3-final01 engine-runtime RuntimeClientsPublishedHttpTest,PublishedAdmissionHttpPostgresTest,FlowMaterialStatusPostgresTest,OwnedFlowSchedulerPostgresTest
node .tools/run-iam-module-tests.mjs iam-stage-5-n3-final02 engine-runtime RuntimeClientsPublishedHttpTest
node .tools/run-iam-module-tests.mjs iam-stage-5-n3-final03 engine-runtime RuntimeClientsPublishedHttpTest
```

噪声不隐藏：两轮test-compile失败为0执行；app初次package-private构造可见性编译失败；fixture不可变版本UPDATE造成子进程退出/管道级联；断言claim使用snake_case、END args及提前RUNNING观察等错误；malformed JSON 400是真实实现错误而已改503；JDK GET重复与遗漏worker依赖为两个真实追加行为红。final01/final02 legacy测试mode/profile错误均仅test setup，final03修复。现有unchecked编译提示、受控负例worker WARN和锁超时ERROR/stack见完整raw，未称输出pristine。源码抓取遇ECONNRESET/timeout、一次编辑脚本LF pattern assertion及不存在文件读取均属工具噪声，不计业务红；失败的编辑在写源码前停止。一次不必要的rg --files .tools枚举请求被立即中断且没有输出/复制依赖树，之后仅核确切固定jar；不声称做过SDK/Maven全面扫描。

## 当前实际测试名称

### cn.agentplatform.engine.infra.FlowMaterialStatusPostgresTest — iam-stage-5-n3-final01，11/0F/0E/0skip

- `pausedProjectionKeepsOriginalAllowedActionsAndAliases`
- `actualContentCorruptionIsClassifiedAfterPayloadRatherThanMetadataDenial`
- `missingNullableScopeWrongDigestForgedParentAndDroppedParentLabelsDenyBeforePayload`
- `freshMainReadsExpiredAndBackgroundBlockedHistoryWithoutRebinding`
- `narrowerCurrentWrongOwnerWrongRootAndRawStatusReadNoPayload`
- `expiryAfterPayloadAndRealReadCommitNeverReturnsStatus`
- `actualRunServiceStatusReadsOriginalMapInOneReadOnlyRrAndPreservesAliasesAndTimes`
- `actualToolMetadataBlocksPureFlowStatusBeforeAnyResultOrPayloadRead`
- `legacyWithoutAuthorityRetainsActualTraceStoreAndStatusBehavior`
- `explicitJsonNullOutputKeepsOriginalPresentationDistinctFromSqlNull`
- `statusRejectsAmbientReadCommittedAndRepeatableReadBeforeNetworkingOrPayload`

### cn.agentplatform.engine.infra.OwnedFlowSchedulerPostgresTest — iam-stage-5-n3-final01，10/0F/0E/0skip

- `legacyOriginalSchedulerAndFlowRemainFunctionalButExplicitProtectedQueueRejectsMissingAuthority`
- `missingAuthorityCapabilityAndUnsupportedNodesNeverUseLegacyOrAgentDriver`
- `protectedClaimOnlyOwnsDispatchBeforeCurrentAndReadsNoPayload`
- `missingSnapshotTypeNeverStartsOwnedWorker`
- `grantExpiryAndPersistentDenialAreExcludedByBothClaimPathsWithoutPayloadOrNetwork`
- `current403And503MakeZeroPayloadAttemptAndStartAndBackoffBothQueueEntrypoints`
- `nullSnapshotTypeNeverStartsOwnedWorker`
- `chatSnapshotTypeNeverStartsOwnedWorker`
- `originalReserveCreatedAndPollRunServiceFlowDriverCompleteExpiredPortalThroughDelegated`
- `unknownSnapshotTypeNeverStartsOwnedWorker`

### cn.agentplatform.engine.infra.PublishedAdmissionHttpPostgresTest — iam-stage-5-n3-final01，19/0F/0E/0skip

- `explicitVersionDebugStepAndV1AliasAreClosedBeforeAppSend`
- `repeatedPostUsesOneProtectedRrViewAndPreservesOriginalGrant`
- `actualPublishedAdmissionAndOwnedWorkerReturnFullStatus`
- `everyAdmissionWriteFailureRollsBackRunAuthorityNodesAndScopes`
- `narrowedCurrentCannotAdmitInitialABOrReadItsAcceptedMaterials`
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

### cn.agentplatform.engine.infra.RuntimeClientsPublishedHttpTest — iam-stage-5-n3-final03，19/0F/0E/0skip

- `oversizedBodyCancelsBeforeUnboundedAllocation`
- `unknownServiceNeverSignsOrSends`
- `headersThenUnfinishedBodyHasCompleteDeadlineAndClientCloses`
- `numericIdsVersionKeyAndStoredDigestAreAllValidated`
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

## 源码、target、patch和保护

source 364→367：新增3、修改4、删除0（3生产Java+唯一POM依赖变更，3新增test）。target 1764→1795：新增31、修改11、删除0；**业务target JAR变化0**。依赖sources.jar是具名开源对照artifact，非业务package产物。

既有364source+1764target物理before，final367source/1795target物理副本均在本轮目录；actual source/target before/after及增改删清单齐。无HEAD/remote/commit实际边界已核，未虚构空diff。完整context binary-safe patch 292805B/SHA 0d3979b5c2b8ededb2923d611fca822f01bbb921a49b0cf94876a47eddacee2c，真实git apply --check=0、git apply=0，replay全367文件与final逐字相同，不靠复制after冒充apply。[patch-check](iam-stage-5-n3-admission-app-client/patch-check.json)、[apply](iam-stage-5-n3-admission-app-client/apply.json)、[边界](iam-stage-5-n3-admission-app-client/git-boundary.json)、[source变化](iam-stage-5-n3-admission-app-client/source-changes.json)、[target变化](iam-stage-5-n3-admission-app-client/target-changes.json)。

## 固定开源机制对照

实际读Spring6.2.7 TransactionTemplate.execute、AbstractPlatformTransactionManager.handleExistingTransaction/processCommit/cleanupAfterCompletion、DataSourceTransactionManager.doCommit：REQUIRED可join，只有顶层atomic返回才经历实际commit+cleanup，故入口ambient拒绝和提交后屏障放在原store.atomic外。PG16.15 mvcc.sgml 的RC/RR以及func.sgml pg_advisory_xact_lock：等待后的RC单语句与RR全量status分别承担不同边界，advisory锁随本地Tx释放。

实际读JDK21固定tag HttpClient/sendAsync/cancel/shutdown与MultiExchange retryOnFailure/getExceptionalCF，另读URLConnection getInputStream/getOutputStream/writeRequests及底层parseHTTP的retry guard；这些是被验证后放弃的传输实现，不宣称当前生产用了JDK sendAsync。实际Apache5.4.4源码取官方Maven Central sources.jar，读HttpClientBuilder.build的retry/redirect/compression链条件、ProtocolExec.needAuthentication、CloseableHttpClient.execute response handler清理、HttpUriRequestBase.cancel、InternalExecRuntime.cancel/discardEndpoint(CloseMode.IMMEDIATE)。生产注释说明固定main/outbound、outercommit、三处重放与取消前close不变量，没有逐句翻译。全部URL/固定版本/license/实际SHA在 [reading-manifest](iam-stage-5-n3-admission-app-client/upstream/reading-manifest.json)、[Apache源码和精确二进制来源](iam-stage-5-n3-admission-app-client/upstream/apache-source-download.json)，LICENSE/NOTICE保留。

## 生命周期与剩余范围

每个engine context在finally关闭；app仅私有STDIN STOP并有界wait，异常路径destroy/finally只作用本轮child。实际汇总9个app child身份和59个本轮ephemeral端点，Listen残留0；有一个历史app PID已被新的postgres后端复用，原raw已有该app CHILD_EXIT=0/STOPPED，身份核对后未终止/修改该postgres。见 [资源清理](iam-stage-5-n3-admission-app-client/resource-cleanup.json)。Maven各次已返回退出码，停止继续执行；固定测试DB留存审计，未drop/部署。

N4真实GatewayController与普通IAM全局装配、独立GET status/SSE、CHAT/工具/模型/Session/history/replay、完整恢复、Observer/RR1/RR2/门户浏览器/真实IAM/本体/K8s/容量及全Task5继续未完成。JVM-only legacy正例的mode/profile finally装配作为N4a受影响fixture明确移交。当前不是正式EngineApplication已可IAM启动的声明。

最终一次保护after、报告SHA、source/target所有权归还和机器handoff以 handoff.json 为准；root审计与独立双审后才更新台账。作者未修改任何共享索引。

## 最终保护与交接

唯一一次保护 after 于 2026-10-20T15:04:32.013Z 完成：90336 个既有受保护文件、6840598306 B，覆盖此前全部84553路径；越权修改0、新增0。28个runner与8个最新索引变化0；全部2162 个当前source/target文件与冻结副本一致。见 [保护结果](iam-stage-5-n3-admission-app-client/protection-summary.json) 和 [逐项差异](iam-stage-5-n3-admission-app-client/protected-changes.json)。本次保护后仅补齐本报告和自有机器交接，未修改生产、测试、POM、target或共享索引。

交接状态为 DONE_WITH_CONCERNS：本段授权行为与当前受影响覆盖已完成；保留具名POM例外、历史final01/final02错误、初次helper失败编译源的SHA核对重建说明，以及未经过真实GatewayController/普通全局IAM仍闭锁等证据限制。当前绿色覆盖严格为 final01 另3类40项 + final03 client19项，均0F/0E/0skip，不能写成一次59项整套绿色。全部Maven命令已结束，后续不再运行；source/target独占在机器handoff写成后归还root。
