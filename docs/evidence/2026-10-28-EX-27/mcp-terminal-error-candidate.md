# MCP 终态 ACK 错误：仅测试候选与窄修设计

2026-10-29，准备者 `artifact_boundary_review`。本轮只新增本文及 `mcp-terminal-error-test-candidate.patch`；没有 apply patch，没有改测试/生产源码、依赖、三主入口或 index，没有运行 Java/Maven/PG/HTTP/WSL/Node。本机 Python/.NET 仅用于读文件、解析已有 JAR class 元数据和生成证据 patch，不加载或执行 Java 类。所有运行数量均为预测，须主线程独审、登记与分配唯一实际窗口。

## 1. 已有真实失败与本候选目标

PhaseA `phase-a-full-20261029-01` 的 `McpAdapterTest.blockedAckBodyCannotOutliveTheWholeSessionDeadline` 在源码 489 行的零 `onErrorDropped` 断言失败。此前 unknown=false、业务调用数=0、耗时小于1800ms 均已通过，方法耗时0.870秒；原 Full701 同方法通过且耗时0.867秒。新增失败不能解释成 deadline 断言失败或直接按机器慢放行。

实际日志唯一 dropped error 的调用栈属于该方法自身：`McpAdapter.session:66` 的 initialize → SDK `McpClientSession.lambda$sendRequest$9` → `BoundedMcpSseTransport.sendMessage:381` → `fail:399`。本次与 Full701 的 MCP adapter/transport/test 指纹均相同；这是既存路径暴露的问题，但本轮新增 F 和完整回归 collector1 原件仍须保留。

真实 SDK 0.10 的 POST 请求具有显式 error consumer：它移除 pendingResponses 后调用 RPC `MonoSink.error`，sendRequest 外层有 `Mono.timeout(requestTimeout)`。`initialize()` 本身为 sendRequest().flatMap，没有 cache；`initializationTimeout` 用于 withInitializationCheck，不是此 initialize 的另一计时器。现有完整日志不能恢复底层 HTTP/future 超时和取消交接的每个时间点。

**本候选不伪称“cancel 已返回后一定 onErrorDropped”。** Reactor3.7.6 的 MonoCreate sink 已完成 cancel 时，迟到 error 可走 onOperatorError 返回，旧实现也可能零 dropped。因此新方法直接观察真实发送 Mono 在 RPC 取消、transport 关闭后是否仍向 SDK 交付错误。零 dropped 只是附加检查；明确的新增 RED 目标为 `lateErrors == 0`，当前实现预测实际为1，必须实跑取得事实。

## 2. 精确来源及候选文件

| 对象 | SHA-256 / 事实 |
| --- | --- |
| 当前 `McpAdapterTest.java` 原字节 | `EBB60E0AF57AA8D182D3C82E1698CABB336984C22153854FA93B17DD24370AFC` |
| 当前 `BoundedMcpSseTransport.java`，仅设计引用、不改 | `44B1A81C554A9BE6B3E1B11A1881D467B347C9D1B2E604FE4147E95FA38C0C77` |
| 当前 `McpAdapter.java`，不改 | `65BB3A464518D2F59ED25D5E0546C6AFEAFB8CEF21A3CAA864D5F0ABCDEE653E` |
| 新证据 patch | `AC26D7C5A019D0F6119946F0E6B0416A97505949AB7839197CFA83AFEC24CC03` |
| patch 生成时内存候选 Java（UTF-8/LF） | `068002325D7D224FB685DBB3476AEBD27CEDFF170430E115BDF1008ADC9F282F` |
| 本仓 `mcp-0.10.0.jar` | `EE5B24C04A9432EF9495342F80F6DCACEB6FC2A907F535677BE0331EA896CA6B` |

patch 仅面向 `skill-registry/src/test/java/cn/agentplatform/skill/infra/McpAdapterTest.java`，只增 imports、一个默认测试、一个剩余时间 helper、Peer 的首 ACK byte 闩锁及 flush 后 countDown。候选内存从842行到1045行；没有写一份重复 Java 文件。LF 摘要只是内存候选的明确表示；真正 apply 后须另读实际原字节 SHA，不能假定工作树换行策略。

已实际完成只读 `git apply --check -- docs/evidence/2026-10-28-EX-27/mcp-terminal-error-test-candidate.patch`，通过。没有执行 git apply，也没有编译或运行测试。

## 3. 新方法与真实 API

新默认方法：`closedSessionDoesNotDeliverLateAckFailureToCancelledRpc`。复用现有 `@BeforeEach` 的真实 `Peer`、其 loopback HTTP/SSE 服务和原 blockAck 分支。Peer 仅在 `sendResponseHeaders(202,0)` 后实际写出并 flush 首 byte 时 countDown `firstAckByte`；没有伪造初始化结果或用早期计数代替完整交付。

新方法创建实际 BoundedMcpSseTransport 与实际 `McpClient.async(...).requestTimeout(800ms).initializationTimeout(800ms).build()`。透明 `McpClientTransport` 委托的 connect、sendMessage、unmarshalFrom、closeGracefully、close 都调用该同一真实 transport；仅 sendMessage 返回值附加 doOnError 观察和 doFinally 见证，不更改结果、调度、错误或订阅方式。没有 mock SDK、response、future 或生产实例。

以下签名已从本 AP 自有缓存实际 class 文件元数据核实，未启动 JVM：

| API | 用法及核实 |
| --- | --- |
| `McpClientTransport.connect(Function<Mono<JSONRPCMessage>,Mono<JSONRPCMessage>>)` | 实际抽象接口，直接委托 |
| `McpTransport.sendMessage(JSONRPCMessage)` | 实际返回 Mono，委托后只观察错误与完成 |
| `McpTransport.unmarshalFrom(Object, TypeReference<T>)` | 泛型实现与当前生产 transport 同型 |
| `McpTransport.closeGracefully()` / `close()` | 前者抽象、后者接口 default；本候选均显式委托 |
| `McpAsyncClient.initialize()` | 实际返回 Mono<InitializeResult>，非缓存路径 |
| `BaseSubscriber.hookOnSubscribe/onNext/onComplete/onError/hookFinally` | 实际 protected hooks；显式接收错误，避免默认 error callback 缺失 |
| `BaseSubscriber.requestUnbounded()` / `cancel()` | 实际 public final；请求与取消真实上游 Subscription |
| `BoundedMcpSseTransport.whenTerminated` / `failure` / `callSent` | 现有同包接口，不新增生产 test hook |

拥有的 executor 提交 `initialize().subscribe(rpc)`。本实现真实 send 的阻塞调用使 subscribe worker 在 ACK 正文未结束时尚未返回；测试先核真实订阅、首 ACK byte、worker 尚未结束、没有提前错误，再调用该 BaseSubscriber.cancel()，确认 CANCEL hook，随后关闭本次实际 transport。旧 sender 的 catch 仍会保存 failure 并抛出；透明委托据实计数。未来窄修应保存 failure 而不再交付这一终态错误。

workerFuture 返回和 sendMessage.doFinally 均为必需证据：它们发生在 SDK 错误消费者实际运行之后，不能只等 doOnError 入口计数。真实 transport 的 whenTerminated 回调另行证明 reader/HTTP/activeSends 已收敛，不能用 close() 返回的50ms等待上限冒充终止。

## 4. 预算、清理与 RED 解释边界

- 生产 transport 全局 deadline 与 SDK request/initialization 设置仍为800ms；没有延长现有800/1800ms。
- 首订阅、首 ACK、worker 返回、send doFinally 使用同一800ms绝对期限的剩余时间。要求发送交付在该期限前已完成，避免旧 SDK timer 抢先取消导致假的绿色；任何此前提失败都不能算新增产品 RED。
- 方法包括 finally 的总预算为1800ms。finally 取消真实 RPC，关闭 client/transport，取消未结束的自有 future，shutdownNow 自有 executor，并在同一1800ms剩余预算内等待 executor 与真实 transport 终止。即使前提失败也执行清理；appender 在嵌套 finally 内脱离并停止。没有无限 future.get、join 或 executor.close 等待。
- 原 `@AfterEach` 的2秒 socket/selector/permit 检查以及 Peer.close 原样保留。Peer server 继续由这个既有生命周期拥有；没有清理其它服务、对象或进程。
- firstFailure 必须非空；callSent=false、peer.calls=0；RPC 的 value/completion/error callback 均为0，最终是明确 CANCEL。lateErrors 非零时必须只有一次、发生在真实 cancel 返回后、异常与 firstFailure 同一对象、sendSignal=ON_ERROR；零时 sendSignal=ON_COMPLETE。
- 直测 SDK/transport 不经过 McpAdapter.session 的 semaphore acquire，故只断言 SESSIONS 前后原值相同，不宣称证明生产 permit acquire/release。原 adapter 测试与原 afterEach 继续承担生产会话释放证据。

唯一预定新产品 RED 是最后的 `assertEquals(0, lateErrors.get(), "MCP_TERMINAL_ACK_LATE_ERROR_DELIVERY")` 得到实际1。此前所有 `MCP_TERMINAL_FIXTURE_*`、资源、信号或其它断言失败，编译错误、没有新鲜 XML、超时/跳过都不是这个 RED。附加零 dropped 检查不能替代最终计数，也不能宣称旧实现必然 dropped。当前仅预测，未取得新方法结果。

## 5. 旧测试完整保留

生成时逐块比对原35个 `@Test` 及1个 `@ParameterizedTest` 的完整方法块，36块全部仍在候选中且字面相同；参数化的3个实际身份保留。原完整 XML 的38身份不改，新默认方法增加一个，预计本类39。原 peer 其它逻辑、旧断言、测试参数及预算不改。

原38身份均位于 `cn.agentplatform.skill.infra.McpAdapterTest`，displayName为空，suite同类；以下方法名保持：

```text
rawTopLevelSchemaKeywordsSurviveSdkConversion
quotaHasDistinctNonUnknownSemantic
writeDisconnectIsUnknownAndNeverResent
initialUtf8BomIsAcceptedWithinWireByteCount
validSseExtensionFieldsAreIgnoredWithinWireLimits
duplicateToolNamesRejectBeforeBusinessCall
tooManyEventsClosesStreamWithoutQueueingSdkNotifications
repeatedCursorCannotLoopUntilTimeout
missingToolFailsBeforeBusinessCall
readDisconnectIsFailureWithoutAutomaticRetry
totalWireLimitAlsoCountsNonDataSseComments
crOnlySseLineEndingsStillUseTheSdkProtocol
forbiddenPostIsNotLostAsGenericTimeout
droppedBusinessPostAckNeverReplaysPostBody
connectionFailurePropagatesWithoutUnconsumedGlobalReactorError
twoRealSessionsExhaustLocalDecodeAdmissionBeforeThirdSocket
followsBoundedToolPagesWithoutChangingArguments
entireSessionUsesOneDeadlineIncludingBlockedReads
endpointCannotForwardCredentialsAcrossOrigins
oversizedEventIsRejectedBeforeSdkMaterializesResult
absentToolsCapabilityRejectsBeforeListOrCall
idleSseWithoutEndpointClosesAtTotalDeadline
oversizedArgumentsFailBeforeCallWithoutBecomingRetryable
ackBodyHasIndependentReceiveLimit
writeToolErrorNeverAuthorizesAutomaticRetry
blockedAckBodyCannotOutliveTheWholeSessionDeadline
businessPostRejectionPreservesDefiniteErrorsAndUncertainWrite(int)[1]
businessPostRejectionPreservesDefiniteErrorsAndUncertainWrite(int)[2]
businessPostRejectionPreservesDefiniteErrorsAndUncertainWrite(int)[3]
unsupportedProtocolIsAConfigurationFailureBeforeBusinessCall
topLevelOneOfAndNestedPatternAreNotLostBySdkJsonSchema
schemaOver64KiBRejectsBeforeBusinessCall
externalSchemaReferenceNeverTriggersNetworkLookup
remoteInputSchemaIsCheckedBeforeBusinessCall
queryOnlyCannotIssueASecondMcpBusinessCall
toolCountIsBoundedBeforeSdkConvertsAllTools
pagesCannotExtendBeyondSixteenRequests
initializesListsThenCallsWithRealArguments
```

## 6. 后续生产上界，尚未实施

若真实新 RED 与独审均成立，生产上界只有 `BoundedMcpSseTransport.sendMessage` 现 catch：先快照 `closed.get() || deadline - System.nanoTime() <= 0`，然后仍调用 fail(ex) 保存首次失败，仅原快照非 terminal 时保留 `throw failure`。不修改 fail 的其它语义、McpAdapter 的预算、SDK/JAR/POM、活跃发送计数或真实收敛逻辑。活动期错误不能因 fail 后副作用而被误吞。

Mono<Void> 的发送完成不是 JSON-RPC 成功响应；这里不生成 initialize 结果、不再发业务请求。闭会话时不走 SDK error consumer 的 remove，可能保留已取消 sink 至本次短生命周期 client 被丢弃；当前生产每次新建并关闭 client，不能据此扩展为长期共享 client 的通用策略。

这个快照并非原子取消协议：快照后才终态的竞争仍可能存在。本新方法确定覆盖 closed 分支，deadline 分支仍须保留原 Full706 实红及原方法回归；不得称已经穷尽所有 race。不得全局 Hooks 吞错、无条件删 throw、放宽预算或修改旧断言。原 Full706 新 F、116个原非通过的严格原因门、4个旧 Skip 以及历史 collector1 继续保留，不因本候选或一次定向通过变成 allGreen。
