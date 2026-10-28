# EX-27 原37方法与候选覆盖

原37身份逐项保留；新增1个默认JUnit，共38。历史状态来自EX35冻结完整模块，仅作历史对照，不能转写为当前候选结果。当前6B144C候选在独立完整38及27类模块中SSE全部38PASS，逐项实际结果见current-stage-results。原方法的跨代复用证据仍须在同次默认完整38方法中实际验证。

| 原方法 | 原目标 | 本轮变更 | EX35冻结模块历史 | 当前候选 |
| --- | --- | --- | --- | --- |
| `originalRequestBootstrapAndContinuousCursorTerminal` | 原REQUEST引导、连续游标及真实terminal | 原方法/场景保持 | PASS | 完整38/模块均PASS |
| `privateEofDoesNotInventTerminal` | 私有EOF不得伪造terminal | 原方法/场景保持 | PASS | 完整38/模块均PASS |
| `realBackpressureAbortRetainsCursorAndActuallyExits` | 真实反压取消、游标与实际退出 | 原方法/场景保持 | PASS | 完整38/模块均PASS |
| `resetPrecedesStatusAndDoesNotAdvanceCursor` | reset先于status且不推进游标 | 原方法/场景保持 | PASS | 完整38/模块均PASS |
| `allSpacePgTypeSurvivesActualServletOutput` | PG全空格类型真实Servlet输出 | 原方法/场景保持 | PASS | 完整38/模块均PASS |
| `generationAtGetterAndEachWriteBoundaryNeverPublishesOldTail` | getter及各write边界generation失效 | 原方法/场景保持 | PASS | 完整38/模块均PASS |
| `revokeAndPartialUnknownNeverAdvanceCursor` | revoke/partial未知结果不得推进游标 | 原方法/场景保持 | PASS | 完整38/模块均PASS |
| `firstErrorAndLateFirstWindowCannotReviveRegistration` | 首错和迟到首窗口不可复活注册 | 原方法/场景保持 | PASS | 完整38/模块均PASS |
| `originalIdleDeadlineSurvivesSpuriousWakeupsAndStoppedReader` | 原idle期限不因虚假唤醒或停读而重置 | 原方法/场景保持 | PASS | 完整38/模块均PASS |
| `twoHeavyHelpersKeepWholeOwnersAndThirdAdmissionClosed` | 两个heavy helper持完整Owner并拒第三准入 | 原方法/场景保持 | PASS | 完整38/模块均PASS |
| `stalledCompletionKeepsOwnersBeforeOldHttpCleanup` | completion停滞不得先退款HTTP资源 | 原方法/场景保持 | PASS | 完整38/模块均PASS |
| `abortIsOrderedAfterActualReadyWriteAndRetainsFeesWhileStalled` | abort位于实际write之后且停滞持费 | 原方法/场景保持 | PASS | 完整38/模块均PASS |
| `lateAbortCannotTouchReusedKeepaliveRequest` | 真实keepalive新REQUEST不受旧abort影响 | 原方法/场景保持 | PASS | 完整38/模块均PASS |
| `realNotReadyNotificationResumesTheSameFrame` | 真实not-ready通知恢复同一frame | 原方法/场景保持 | PASS | 完整38/模块均PASS |
| `exactLongMaxCursorAndHighPrecisionJsonReachRealServletBytes` | Long.MAX游标及高精度JSON真实字节 | 原方法/场景保持 | PASS | 完整38/模块均PASS |
| `inertReadyIdleShellAllowsMaterialFeeExitButRetainsFinalOwner` | 轻壳允许material费退出但保留最终Owner | 原方法/场景保持 | PASS | 完整38/模块均PASS |
| `actualPendingFlushRequiresDrainAndFreshGenerationBeforeCursor` | pending flush须drain且fresh后才能推进游标 | 原方法/场景保持 | PASS | 完整38/模块均PASS |
| `stalledActualActionRetainsTwoOwnersUntilActionAndCompletionExit` | 实际action停滞持两个Owner直至action/completion退出 | 原方法/场景保持 | PASS | 完整38/模块均PASS |
| `sameProcessorOnAnotherSocketCannotReceiveOldAbort` | 真实processor跨socket复用不得被旧abort命中 | 原方法/场景保持 | PASS | 完整38/模块均PASS |
| `noFutureWritableCallbackIsNeededForTheOwnedIdleDeadline` | 原idle期限不依赖下一次可写callback | 原方法/场景保持 | PASS | 完整38/模块均PASS |
| `controlledProfileAndSingleLifetimeInstanceFailClosed` | 受控部署profile及单JVM终身实例默认拒绝 | 原方法/场景保持 | PASS | 完整38/模块均PASS |
| `forcedConnectorReleaseInvalidatesTokensWithoutInventingCompletion` | 实际processor回收前失效token且不得伪造completion | 正例增加真实关联/锁内close前提；保留原5秒及全部断言；显式原红mode保留 | FAIL | 完整38/模块均PASS |
| `invalidCursorCannotPublishAnUnreapableLease` | 非法游标不得发布不可回收lease | 原方法/场景保持 | PASS | 完整38/模块均PASS |
| `preasyncUnsupportedDetachesHeavyStateBeforeRefund` | 不支持async时先detach重图再退款 | 原方法/场景保持 | PASS | 完整38/模块均PASS |
| `uncertainHandleConstructionAndRegistrationBurnSharedCredits` | 构造/注册不确定性烧毁共享名额 | 原方法/场景保持 | PASS | 完整38/模块均PASS |
| `mixedAutomaticReferencesReuseOneSharedPopulation` | 自动引用通知共用固定人口 | 原方法/场景保持 | PASS | 完整38/模块均PASS |
| `actualAwaitClosedAncestorPinsRefundedShellCredit` | 实际awaitClosed祖先栈仍锚定退款后壳名额 | 原方法/场景保持 | PASS | 完整38/模块均PASS |
| `actualAbortSnapshotMustReturnBeforeHttpRefund` | 实际HTTP abort快照返回前不得退款 | 原方法/场景保持 | PASS | 完整38/模块均PASS |
| `actualCleanupSnapshotMustReturnBeforeHttpRefund` | 实际HTTP cleanup快照返回前不得退款 | 原方法/场景保持 | PASS | 完整38/模块均PASS |
| `retainedFinishedHandlesConsumeSharedFactoryCapacity` | 保留finished Handle仍占共享factory容量 | 原方法/场景保持 | PASS | 完整38/模块均PASS |
| `q403FailureClosesTransferredSessionBeforeReturningCapacity` | Q403先关闭转交Session再退容量 | 原方法/场景保持 | PASS | 完整38/模块均PASS |
| `qTransportFailureClosesTransferredSessionBeforeReturningCapacity` | Q传输失败先关闭转交Session再退容量 | 原方法/场景保持 | PASS | 完整38/模块均PASS |
| `transferredServletCannotClaimTheOriginalRequestFromWorker` | 转交Servlet不得从worker夺原REQUEST | 原方法/场景保持 | PASS | 完整38/模块均PASS |
| `originalRequestScopeAndDispatcherAreRequiredBeforeQuery` | 查询前要求原REQUEST scope/dispatcher | 原方法/场景保持 | PASS | 完整38/模块均PASS |
| `uncertainProcessorConstructionBurnsItsReservedBundle` | processor构造不确定性烧毁预留bundle | 原方法/场景保持 | PASS | 完整38/模块均PASS |
| `automaticPhantomNotificationCanReturnActualFactoryCapacity` | 真实phantom通知可归还factory容量 | 原方法/场景保持 | PASS | 完整38/模块均PASS |
| `actualProcessorFactoryRefusesBeyondFixedStrongPopulation` | 真实processor工厂不得超过固定强人口 | 原方法/场景保持 | PASS | 完整38/模块均PASS |

新增默认`offLockCloseDuringProcessingRetainsSafetyWithoutInventingRecycle`，使用独立fresh child。它保留实际off-lock交错并继续执行安全断言，不能在原entered预期处早退。无实际SSE processor复用，因此不声称覆盖该复用；只验证另一个真实keepalive socket两个不同REQUEST epoch的响应与动作目标，真实SSE跨socket/epoch复用由原正例及既有两个复用向量覆盖。

仅测试前提修正已由root与独立技术门批准，所有src/main仍只读。若新增向量实际失败，按具体失败区分夹具/API使用与产品义务，禁止改断言凑绿。原447诊断红和EX35历史新增FAIL均保持，不认定历史唯一同因。


