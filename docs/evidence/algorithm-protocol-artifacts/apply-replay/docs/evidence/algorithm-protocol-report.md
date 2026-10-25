# 算法原任务协议实现与模块验收

本报告仅记录本新工程 G2 算法协议增量。根任务已移交独占 skill 源码/target；前置原 attempt 改造与 Node 清理独立审查结论不由本报告重签。本轮没有 package、启停主服务、提交、发布、Kubernetes 集群或算法容器执行。当前主服务仍是根任务固定的 846 阶段制品。本报告不关闭完整 T11/G2；冻结后的独立审查和实际 engine/skill JVM 纵切由根任务执行。

依据为 `algorithm-protocol-implementation-brief.md`、只读完整计划、`algorithm-readonly-retry-decisions.md` 及根任务本轮总期限裁决。原四契约、v1.2 原文、既有 POM、common/engine 生产代码、旧迁移和既有测试均未修改。

## 范围与冻结

唯一工作目录为 `G:/207/项目/算法重组工具/代码/agent-platform`。本工程无完整 HEAD，不能用空 git diff 证明范围。移交时保存 84 个已有源文件/配置/参考文档到 `algorithm-protocol-artifacts/baseline/`，原字节摘要见 `baseline-sha256.json`。V311 在该基线不存在，本轮新增。

修改五个生产文件：`infra/AlgorithmAdapter.java`、`infra/SkillAdapters.java`、`infra/ArtifactStore.java`、`application/SkillExecution.java`、`application/ReconciliationProofs.java`；新增 V311、六个算法 Java 测试/夹具文件、一个 PowerShell 测试入口和一个 Node 测试 fixture；更新 `docs/open-source-references.md`。全部精确相对路径、before/after SHA256、ADD/MODIFY 状态、完整上下文补丁、冻结副本和实际 apply 后比对结果见 `algorithm-protocol-source-sha256.json`。

`final-full-before-sha256.json` / `final-full-after-sha256.json` 对全部 skill/common `src` 及三个 POM 共 90 文件记录原字节摘要。整轮前后逐文件相等。冻结内容不含凭据、环境文件、JAR/服务配置；SDK读源与测试日志独立列摘要。

## 实现和矩阵

下表路径除特殊说明外相对 `skill-registry/src`。P= `test/java/cn/agentplatform/skill/infra/AlgorithmProtocolTest.java`，E= `test/java/cn/agentplatform/skill/application/AlgorithmExecutionPostgresTest.java`，M= `test/java/cn/agentplatform/skill/infra/AlgorithmMigrationPostgresTest.java`。这些测试使用实际 PG、固定 Kubernetes SDK 和有限本地 HTTP 接收端，未用 mock verify 代替业务请求计数。

| 计划边界 | 生产机制 | 有限行为/持久断言 |
| --- | --- | --- |
| PREPARING 在外发前完整登记 | SkillExecution.execute 的短事务调用 persistentBinding/bind，并插 PREPARING 事实；submitted 成功后才进入外调 | E.preparingAndRegisteredExistBeforeSubmittedAndFirstPost：submitted 时 PG 已有 REGISTERED/PREPARING、send=false，POST前已非PREPARING；registrationAndFactRollbackTogetherBeforeAnyNetwork、initialFactInsertFailureRollsBackBindingAndNeverSubmits：双向回滚、零业务POST |
| 原代与完整身份 | AlgorithmAdapter.bind/identityDigest/validateBinding/observe：tenant/principal/run/node/call/attempt/version/合同摘要/key、原参数/守卫参数/原快照、API摘要/namespace/SA/image/确定性名称 | P.staleAttemptCannotObserveOrSend、crossPrincipalAndTenantCannotQueryOrMutate、changedArgumentsOrContractCannotIssueRequests、apiChangeAndPolicyShrinkCannotQueryHistoricalBinding；错误归属零查询/零成功 |
| 一次发送与 W1 | REGISTERED/send=false 原身份 CAS 到 SENDING/send=true，成功才 POST；OkHttp 连接自动重试和重定向关闭。queryOnly 不 bind/POST/DELETE；send=true 的404不重建 | P.twoInstancesShareOneDurableSendCas；E.w1ReconciliationFencesDelayedSubmittedAck、submittedRefusalLeavesPreparingAndNeverPosts；P.queryOnlyWithoutBindingDoesNotInsertOrSend、running404PreservesBindingAndNeverCreatesInQueryOnly |
| ACK 未知与 W2 | POST已接收但ACK丢失保留原绑定；新实例只查询同 API/ns/name；不更换随机名称 | E.ackLostThenNewAdapterQueryConvergesWithoutReplay：原事实UNKNOWN，新适配实例查询收敛、总POST=1；P.lostAckNeverCreatesSecondJobAfter404 |
| 已存在Job/409/UID | 每次GET/POSTACK/409复读核 namespace/name/非空UID/本地重算annotation；UID只从null写一次并完整CAS，替换UID拒绝 | P.identicalJobRecoversThrough409WithoutSecondPost、forgedAnnotationCannotSupplyResult、wrongNamespaceNeverReadsLogs、replacingUidCannotReplaceOriginalBinding |
| 可信终态失败 | 仅 Complete=True/Failed=True，二者矛盾拒绝；failed/succeeded计数不是终态。Failed正常记录 FAILED/NON_RETRYABLE_FAILURE，不生成 NOT_APPLIED | P.failedCountAloneIsUncertain、missingStatusCannotBeCountedAsComplete、contradictoryConditionsAreUncertain；E.trustedFailedConditionCanConvergeFromUnknown、trustedFailedJobIsNonRetryableAndDuplicateDoesNotCreateNewAttempt：重复/对账交付原失败，POST仍1 |
| Pod/容器 | list limit=2/watch=false/有限timeout，不翻页；要求恰一Pod、无continue、namespace/nonemptyUID、controller=true、batch/v1/Job/name/UID owner精确匹配，algorithm容器恰一且终止exit0；日志固定container/follow=false，不请求截断 | P.wrongPodOwnerNeverReadsLogs、podControllerMustBeTrue、podOwnerVersionMustMatch、zeroPodsCannotSupplyResult、multiplePodsNeverSelectFirst、continuedPodListIsUnknown、nonAlgorithmContainerCannotSupplyLogs、nonzeroContainerExitCannotSupplySuccessfulLogs |
| Job防护 | 固定 completions/parallelism=1，backoff=0、activeDeadline、专用SA、禁挂token、非root/禁提权/dropALL、资源请求/上限、镜像允许清单 | P.algorithmBodyRetainsResourcesSecurityAndExplicitContainer 对实际 POST 请求体断言；不声称生产集群已强制这些设置 |
| 元数据/错误体字节硬闸 | SDK正式 OkHttp 应用拦截器封装透明解码后的响应体；SDK string/model之前限制，成功和错误体同闸，未知Content-Length也限额，超限立即关流 | P.metadataBudgetAppliesBeforeSdkMaterialization、chunkedMetadataBudgetAppliesWithoutLength、chunkedErrorBodyIsBoundedWithoutSend、decodedGzipMetadataIsBoundedBeforeSdkModels、decodedGzipErrorBodyUsesSameBudget |
| 日志/完整JSON/受控产物 | 日志buildCall取流，CompleteJson非阻塞token馈入器要求单一完整JSON文档；decoded日志总字节硬限，不把阈值截断当成功；小结果/原产物路径保留Schema/归属/摘要 | P.logBudgetRejectsCompleteJsonBeyondBytes、decodedGzipLogCannotBypassHardBytes、trailingJsonDocumentCannotBeSuccessful、truncatedJsonCannotBeSuccessful、trailingJsonCannotProduceReadyArtifact、offloadPreservesCompleteUtf8JsonAndRunOwnership、completeSmallSchemaViolationIsOutputInvalid、completeOffloadedSchemaViolationIsOutputInvalidWithoutReadyArtifact |
| 统一单调总期限 | AlgorithmAdapter首次外部GET之前计算唯一nanoTime deadline，SDK Call使用remaining、sleep/read检查同deadline，SchemaDeadline同域；readAlgorithm把原deadline传入算法专用store，涵盖日志/上传/回读 | P.oneDeadlineCoversSlowInitialGetAndDoesNotSendLate、totalDeadlineBoundsSlowContinuousMetadata、slowAcceptedPostIsUnknownAndHasOneReception、continuousLogBytesCannotExtendTotalDeadline、fullKubernetesChainPassesOriginalAbsoluteDeadlineToOutput、frontStageDeadlineExpiresBeforeAnyMinioPutOrDelayedPost，以及两个算法转存尾段参数化用例，详见下文 |
| 同代终态原子采纳 | SkillExecution.adoptAlgorithm先锁原fact再binding，核原身份/快照/状态/UID/source；绑定DONE+terminalEvidence和终态事实同事务。网络不跨PG事务 | E.staleSuccessObservationCannotMarkChangedFactDone、staleObservationCannotAdoptReplacementBindingUid、concurrentlyTerminalFactCannotBeOverwrittenByOldObservation、terminalFactFailureRollsBackBindingDoneAtomically；不允许先单独DONE再交事实 |
| 当前守卫/审批/Mock/凭据 | 原SkillRules输入/输出准入、当前授权覆盖原范围、引擎claim/合同/参数/审批不变；SecretVault按tenant解析到SDK Bearer；不保存明文secret | E.narrowedCurrentScopeDoesNotQueryHistoricalJob、noApprovalAndDebugMockHaveZeroAlgorithmRequests、debugLastRealMakesNoAdditionalAlgorithmRequest、formalInvocationIgnoresMockAndUsesRealAlgorithm；P.foreignTenantSecretCannotReachAlgorithmApi、exactBearerRejectionKeepsRemoteObservationUnknown |
| W3/迟到/计量 | 只重投持久事实，不重执外部业务；原attempt显式传递；取消/旧代结果不可复活；累计duration保留旧段、每代attemptDuration独立 | E.w3OnlyRedeliversPersistedFact、lateTerminalObservationIsRetainedWithoutRevivingCancelledAuthority，readonly新代测试核累积时长/原档案；fixture引擎logicalCommits计数不能替代真实engineJVM证明 |
| 旧数据与HTTP兼容 | V311仅nullable算法身份列+算法部分唯一索引+不可变绑定历史，不回填猜测。旧主体/缺绑定返回审计LEGACY_BINDING UNKNOWN | M新建全迁移/V310升级/唯一索引；E.legacyBindingWithoutPrincipalStaysAuditedUnknownWithoutNetwork、missingOriginalBindingStaysAuditedUnknownWithoutNetwork：原事实UNKNOWN、零额外API、无proof、retryBusinessAllowed=false；原HTTP路径完整回归 |

### 明确未POST的只读重试裁决

算法外部发送代与引擎节点 attempt 分开判断。可信原 Job Failed 的事实是确定终态且不可重试，不重建Job。仅首次GET的暂时连接/超时/5xx且完整原绑定 REGISTERED/send=false/UID=null，才先做全身份 NOT_SENT CAS；CAS成功后，readonly=true且sideEffect=false可以写 FAILED/RETRYABLE_FAILURE。其余未知或可能已发均保持 UNKNOWN。

新调用必须已获引擎新 REGISTERED attempt 的当前 claim、合同/参数/守卫核验，才在 fact→binding 同事务归档旧 FAILED/RETRYABLE事实与可靠NOT_SENT绑定，准备新代完整 REGISTERED/PREPARING快照；仍是同call/key/确定性Job名称。首次业务POST发生在新PREPARING落库且submitted之后。V311历史表保存整个旧绑定jsonb，UPDATE/DELETE触发器拒绝；不是post-send归档或UNKNOWN reset。

E.unsentReadonlyTransientFailureIsFencedBeforeRetryableFact、engineAuthorizedReadonlyGenerationIsPreparedBeforeOnlyBusinessPost、readonlyGenerationPreparationRollbackPreservesOriginalFactAndBinding、twoAuthorizedRetryWorkersPrepareOnlyOneGeneration、oldExplicitAttemptCannotSendIntoPreparedNewReadonlyGeneration、absentNewAuthorityCannotResetKnownUnsentFact、unknownFactNeverEntersReadonlyGenerationHandoff、sentBindingCannotEnterReadonlyGenerationHandoff、sideEffectContractCannotEnterReadonlyGenerationHandoff 分别验证CAS、原子完整归档、回滚、并发唯一、旧线程fence和禁止边界。HTTP/MCP原只读重试入口继续回归，不把新限制扩大到它们。

## 资源边界和实际失败记录

默认metadata上限1MiB，日志硬限64MiB且不可高于原ArtifactStore硬限；测试注入metadata1KiB、日志4KiB，均只用小正文。`LimitedBody` 的计数断言指 **SDK可见/应用消费的解码字节最多cap+1探测字节**；这是SDK字符串/模型物化之前的限制，不是TCP、压缩字节或底层socket接收配额。上游Okio RealBufferedSource每次固定8192单段读取，外层buffer也固定有界；gzip应用解码后同闸。`sdkVisibleSourceConsumesAtMostLimitPlusOneProbeByteAndCloses` 分别核消费计数、底层固定预取上界和close；超限没有返回截断成功。最初 `algorithm-directed-matrix.log` 的60/1是错误把底层预取计数当cap+1；保留原日志，后续改为准确边界，未扩大应用限额。

算法转存专用 ArtifactStore 使用独立OkHttp池/dispatcher，原公共实例和HTTP/MCP原45s调用入口不变。MinIO 8.5.17 的真实HTTP调用统一走enqueue；固定OkHttp4.12在异步timeout.enter之前触发callStart，PUT/POST派生newBuilder保留listener。算法实例把每个真实Call.timeout设置到 **同一个deadlineNanoTime**，过期即cancel当前I/O；不是逐次刷新相对超时，也不是另起Future超时后任其后台继续。关闭只作用本次独占实例，不关闭共享store。上传可能已写入对象，PG记CLEANUP_PENDING，后续原有受控清理确认后才记DELETED，不承诺外部事务回滚。

小型MinIO fixture每请求/对象有限，上传延迟500ms，回读按16字节/20ms发送。行为红 `algorithm-red-minio-deadline-warmed.log` 在旧转存入口测到670/1057ms，超过200ms总预算。首版74项曾全绿；随后 `algorithm-final-directed.log` 76项中UPLOAD_ACK出现 expected2/actual1。该断言是目标PUT到达次数，不是后续共享store保护：整条K8s+PG前段合法耗尽200ms，尚未到PUT。保留这轮76/1失败，`algorithm-minio-phase-diagnostic.log` 的47绿仅是诊断，不替代最终回归。

按根任务同意拆开证明：

1. `algorithmOutputDeadlineCapsMinioUploadAndReadbackAndLeavesSharedClientUsable` 在真实PG、已预热小型MinIO SDK入口直接开始同一200ms转存期限；严格保留PUT+1、READBACK时GET+1、<450ms、CLEANUP_PENDING/无READY、实际peer active=0/无迟到请求/无活跃OkHttp I/O、断开回读、随后原共享store慢PUT/GET成功、最终自有对象确实清理。测试只暖SDK初始化，不延长产品deadline或删除阶段计数。`algorithm-red-minio-split.log` 临时恢复旧无deadline输出委派时2项均行为失败（应抛超时而未抛），finally恢复生产原字节；`algorithm-green-minio-split.log` 49/49绿，尾段227/224ms，原共享入口均573ms。
2. `fullKubernetesChainPassesOriginalAbsoluteDeadlineToOutput` 用有限70ms阶段延迟，捕获真实SDK链输出入口收到的deadline，确认与首次GET的原deadline相同，前段消耗剩余预算，没有尾段重置；`frontStageDeadlineExpiresBeforeAnyMinioPutOrDelayedPost` 用200ms预算/300ms首GET，确认超时后零PUT、零产物、零迟到业务POST。它们分别覆盖完整链传递和合法未到尾段情况。

最终定向80项的尾段实测223/224ms，原共享入口566/570ms、active=0、自有对象清理后0。最终整轮尾段225/223ms，原共享入口均570ms；`algorithm-final-full.log` 保留实际 ALGORITHM_MINIO 行。所有超时只证明本地调用/流退出，不表示原外部Job停止或效果撤销。

## 红绿命令和计数

统一入口 `./scripts/test-algorithm-protocol.ps1 -Tests '<类名或#方法模式>' -Log '<文件名>'`，本地JDK21、Maven仓库固定在工程 `.tools/m2`，实际Maven使用 `-B -ntp -pl skill-registry -am test -Djdk.net.unixdomain.tmpdir=V:/.local/tmp -Dsurefire.failIfNoSpecifiedTests=false`。PowerShell逗号列表始终作为一个带引号参数。脚本不启停任何服务，检查新鲜Surefire XML，拒绝0项/失败/错误/跳过冒充成功。PG密码仅从本工程显式环境加载且不打印。

| 选择/阶段（日志均在algorithm-protocol-artifacts） | 实际 tests/failures/errors/skips | 判定 |
| --- | --- | --- |
| A登记/queryOnly：algorithm-red-A-behavior.log | 2/2/0/0 | 原行为真实红 |
| C纠正SDK必填ContainerStatus夹具后：algorithm-red-C-corrected-fixture.log | 20/19/0/0 | Job/Pod/边界真实红；初版夹具错误日志不作为通过 |
| B PG：algorithm-red-B-behavior.log | 7/4/2/0 | 仅4项断言失败作为红；2错误不当行为红 |
| algorithm-green-first-actual.log | 27/0/0/0 | 第一轮定向绿；无测试BUILD SUCCESS的algorithm-green-first.log无效 |
| 单一完整JSON：algorithm-red-C-complete-json.log → algorithm-green-C-complete-json.log | 31/2/1/0 → 31/0/0/0 | 两项正文尾随文档真实红；另1冷启动超时不算该行为红 |
| readonly裁决：algorithm-red-readonly-handoff.log → algorithm-green-readonly-races.log | 13/2/1/0 → 20/0/0/0 | Failed语义/未发送暂错红；旧绑定错误单独记；后续快照PGObject比较错误保留原日志 |
| 升级：algorithm-red-migration-behavior.log → algorithm-migration-first.log | 1/1/0/0 → 2/0/0/0 | 暂移本轮自有V311时列数4→0真实失败；恢复后实际全迁移/升级通过 |
| decoded gzip：algorithm-red-decoded-gzip.log | 1/1/0/0 | network interceptor不足，改应用拦截器；最终套件含成功/错误/log |
| offload schema：algorithm-red-offload-schema.log | 2/1/0/0 | 完整Schema错应OUTPUT_INVALID，原返回UNKNOWN；修复后72/0/0/0 |
| MinIO warm原入口红/首次绿 | 2/2/0/0 → 2/0/0/0 | 见上文，首次compile错误不算绿 |
| MinIO拆分红/绿 | 2/2/0/0 → 49/0/0/0 | 见上文，保留76/1失败 |
| legacy整体reconcile：algorithm-red-legacy-reconcile-corrected.log | 2/2/0/0 | 应结构UNKNOWN却409；初版错误列名的2error不算行为红 |
| 既有协议：algorithm-existing-regression.log | 140/0/0/0 | AsyncHttpExecution/CallbackHttp/ReconciliationProof/SignedCallback/McpAdapter/McpExecutionPostgres，非算法专测替代 |
| 最终算法：algorithm-final-directed-corrected.log | 80/0/0/0 | Protocol49 + Execution29 + Migration2 |
| 最终完整：algorithm-final-full.log | skill 377/0/0/0；common 10/0/0/0（合计387） | `-Tests '*'`，最终src/POM前后同SHA |

所有真实失败和配置/夹具错误日志保留，未把编译失败、未运行、环境异常或后续单独绿写作最终通过。初始NIO loopback临时目录环境错误通过既有本地JVM临时目录参数解决；未改业务规则或扩大载荷。

## 迁移与开源读源

测试helper只允许新工程PG127.0.0.1:25432的 `agent_platform_skill_algorithm_test` 和 `agent_platform_skill_algorithm_migration_test`，未清运行库。全迁移和V310升级都实际执行Flyway validate；升级前旧HTTP完整绑定及不可变proof原字节摘要与升级后相同；旧算法缺身份字段保持null、不猜回填、零外调。新算法API/ns/name和API/ns/非空UID跨call唯一冲突拒绝。根任务允许新增历史表后，早期本轮V311曾仅用于本自有测试库；在该测试库显式重建skill schema重新全迁移，日志 `recreate-own-algorithm-schema.log`。没有repair/更改他人迁移或运行库历史。

固定源包/实际读取文件/许可证原字节SHA在 `algorithm-protocol-artifacts/sdk/source-sha256.json`，只读源范围如下：

- Kubernetes Java Client23.0.0：BatchV1Api.create/read/buildCall；CoreV1Api.listPods的limit/watch/timeout，readPodLog的container/follow/buildCall及limitBytes近似截断文档；ApiClient.buildCall/handleResponse/deserialize/body.string/认证和setHttpClient；JobStatus、OwnerReference、ContainerStatus相关模型。源码包SHA256 `7b0da48b1e4eb3843a8cb375066b701e38d0ec52af35d4d728551c2c76201929`；Apache-2.0 LICENSE原文另存。
- OkHttp4.12.0：RealCall.kt约164行enqueue/callStart、约515行AsyncCall.timeout.enter；OkHttpClient.kt约501行Builder拷贝listener factory；BridgeInterceptor.kt约95行透明gzip；ResponseBody.kt string/read机制。SDK应用拦截器在返回方向收到解码body，早于SDK物化。
- Okio3.6.0：RealBufferedSource.kt与内部common实现、Segment.kt SIZE8192，核固定预取/包装buffer机制。
- MinIO8.5.17：MinioClient.putObject约1370行/getObject约204行同步future.get；S3Base.executeAsync约607–614行派生PUT/POST client并enqueue，putObjectAsync约2935行有限PartReader和单次/分片future链；MinioAsyncClient相关入口。使用原SDK扩展，没有新造K8s/S3 HTTP客户端、反射或后台资源探针。

上述固定来源链接和许可说明同步追加 `docs/open-source-references.md`。不新增依赖、不复制整个SDK实现、不声称读全SDK或供应链审计。

## 提供给后续实际纵切的测试入口和限制

`tests/smoke-algorithm-protocol.mjs` 仅导出/启动本地有限K8s API fixture，不启动engine/skill或接管服务。调用者必须显式提供测试namespace和8–256字符假 `expectedBearer`；所有K8s业务路由精确核 `Authorization: Bearer <expected>`，仅记录authRejected计数，不记录凭据。缺该字段直接拒绝启动，不能把未核验请求当认证证据。每场景新fixture/namespace，不为重置夹具扩大生产行为。统计singlePost断言同时要求无拒绝和无authRejected。

本轮只执行 `node --check tests/smoke-algorithm-protocol.mjs`、--help与契约/依赖边界验证，没有使用它声称真实JVM纵切。夹具持有自有PID/随机端口、有限5分钟截止和自有关闭，不能操作root服务。实际SecretVault→SDK请求、engine唯一逻辑提交以及跨实际JVM恢复仍由root后续独立验证。

身份摘要是可信专用API/namespace下的归属标记，不是签名；生产前提是受控创建权限和RBAC。实际API server/admission/TLS/生产Secret轮换、镜像拉取、调度、容器算法结果、控制器终态/GC/停止、日志来源完整性、容量、公平调度、外部效果撤销、UNKNOWN人工闭环均未由本地fixture验证。没有K8s DELETE或UNKNOWN新代许可，也没有把Failed/404伪造为NOT_APPLIED。
