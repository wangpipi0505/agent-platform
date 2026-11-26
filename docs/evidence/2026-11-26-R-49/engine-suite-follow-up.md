# 引擎全套历史失败跟踪（R-49）

开发侧于 2026-11-26 连续执行两轮 engine-runtime 全套。本表单独跟踪 R-49 以外的测试，开列失败类并保留不同轮次的差异。计数列按“断言失败 / 执行错误”排列。

| 轮次 | 总用例 | 断言失败 | 执行错误 | 跳过 | 报错或失败的类 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 首次全套 | 1182 | 45 | 109 | 3 | 37 |
| 含 R-49 的全套 | 1188 | 45 | 108 | 3 | 36 |

新增的 ModelFailureTaskPostgresTest 在常规发现下两条均通过。两轮均失败的类有 36 个，首次失败、本次通过 1 个，仅本次失败 0 个。逐类原始统计见 [全套输出](full-suite.txt)。

首次运行缺少统计专用库，RunStatisticsPostgresTest 在初始化处记为一项错误。复跑补齐后，该类 5 项全部通过。加上新增的 R-49 两项，两次全套用例总数相差 6。EvaluationRunPostgresTest 的 JDBC 建连错误在相邻四类独立回归中也复查过，三个用例均通过。其整套错误继续保留在下表。[相邻回归](adjacent-tests.txt)。

## 核查入口

- 会话配置夹具。ChatSessionConfigPinTest 构造 ChatSessions 时传入空 TraceStore，SessionStore 在断言执行前调用 trace.db()。
- 迁移验证。FlywayLegacyUpgradeEvidenceTest 在当前迁移集执行后仍断言库的最高版本为 231，本批隔离库实际为 901。
- 历史多进程联测。InitialSenderFactsPostgresTest 从 initial.fixture 读取独立模型和引擎控制目录，ModelInputMaterialPostgresTest 需要 model.input.registry。应按原联测生命周期准备各用例的数据库和进程。
- 其余类按下表的具体用例独立复现，再判明业务实现、测试数据或运行环境的责任范围。三条 RunAuthorityLiveResumeTest 受 EX26_LIVE_RESUME 开关控制，属于单列的真实签发服务集成测试。

复跑整套的 Maven 命令与环境见 [R-49 复跑说明](README.md)。单类复跑使用 `mvn -pl engine-runtime test -Dtest=类名`。Surefire 对每类生成 TEST-包名.类名.xml，其中含全部方法及完整堆栈。

## 逐类跟踪

| 测试类 | 首次 | 本次 | 本次用例样本与异常类型 |
| --- | ---: | ---: | --- |
| `ChatSessionConfigPinTest` | 0 / 5 | 0 / 5 | `legacySessionWithoutConfigurationAcceptsLegacyTurns` · NullPointerException |
| `PortalChildEventsHttpTest` | 0 / 1 | 0 / 1 | `privateBlockingGetWriteFlushAndCompleteRetainRealReaderFrameAndSlotUntilExit` · PlatformException |
| `RunSnapshotAssemblyTest` | 0 / 1 | 0 / 1 | `containerInjectsTheSnapshotCollaboratorIntoTheFacade` · UnsatisfiedDependencyException |
| `RunStatisticsPostgresTest` | 0 / 1 | 0 / 0 | 本次通过 |
| `SubagentPlanningTest` | 1 / 0 | 1 / 0 | `duplicateAndLateReceiptsKeepAuditAndNeverOverwriteMergeDecision` · AssertionFailedError |
| `FlywayLegacyUpgradeEvidenceTest` | 1 / 0 | 1 / 0 | `migrateAndAssertDeliveredObjects` · AssertionFailedError |
| `ActionPlanPostgresTest` | 0 / 2 | 0 / 2 | `twoDeclaredRolesSatisfyHighRiskWithoutDoubleConfirm` · PlatformException |
| `ChatTurnEventLinkageTest` | 0 / 3 | 0 / 3 | `turnLifecycleBindsRunEarlyAndReplaysToSameTurn` · DataIntegrityViolationException |
| `EngineContextPostgresTest` | 0 / 2 | 0 / 2 | `actualMvcTimeoutLeavesRoomForTheFiftyFiveSecondObservationWindow` · IllegalStateException |
| `EvaluationConversationSessionTest` | 1 / 0 | 1 / 0 | `persistedCandidateTurnsKeepRealHistoryOwnershipOrderAndEvaluationAdmissionAcrossRestart` · AssertionFailedError |
| `EvaluationRunPostgresTest` | 0 / 1 | 0 / 1 | `conversationTurnsReuseTheAuthorizedPriorAnswerInCanonicalModelInput` · CannotGetJdbcConnectionException |
| `FlowMaterialDatabasePreflightTest` | 0 / 1 | 0 / 1 | `inspectBothAuthorizedHistoriesAndActualSourceBeforeAllocating217` · BadSqlGrammarException |
| `FlowMaterialStatusPostgresTest` | 1 / 1 | 1 / 1 | `narrowerCurrentWrongOwnerWrongRootAndRawStatusReadNoPayload` · PlatformException |
| `InitialChatAdmissionHttpPostgresTest` | 0 / 10 | 0 / 10 | `reviewedPrivateTaskControlStillCompletes` · NullPointerException |
| `InitialChatObservationPostgresTest` | 0 / 3 | 0 / 3 | `reconnectAndFreshCurrentDenialPreserveDurableResult` · NullPointerException |
| `InitialMaterialScopePostgresTest` | 0 / 1 | 0 / 1 | `wrongOwnerRootPurposeVersionOrDigestRejectBeforePayload` · PlatformException |
| `InitialSenderFactsPostgresTest` | 0 / 48 | 0 / 48 | `cancellationAfterFirstChunkCannotPublishOrCommit` · NullPointerException |
| `InitialSenderIntegrationTest` | 0 / 2 | 0 / 2 | `ownedDriveCommitsInitialResultAfterRealMaterialPreparation` · NullPointerException |
| `ModelInputMaterialHttpTest` | 1 / 0 | 1 / 0 | `` · AssertionError |
| `ModelInputMaterialPostgresTest` | 21 / 0 | 21 / 0 | `publicCurrentOnlyAndFabricatedSourceCannotWriteAndCatchCannotCommit` · AssertionError |
| `OwnedFlowMaterialsPostgresTest` | 1 / 0 | 1 / 0 | `lastNodePostCommitProofExpiryLeavesGrantUsableAndAggregateOnlyNewCurrentNeverRerunsNode` · AssertionFailedError |
| `PortalChildEventsPostgresTest` | 1 / 0 | 1 / 0 | `realConcurrentWriterIsOnlyVisibleInNextSnapshotAndNoEncodingSqlExists` · AssertionError |
| `PortalRunEventWindowPostgresTest` | 1 / 0 | 1 / 0 | `concurrentLegalTerminalWriterIsInvisibleUntilNextCompleteReadWindow` · AssertionError |
| `PortalRunEventsCapacityTest` | 0 / 1 | 0 / 1 | `freshQueuedFirstReadCapacityFailureBeforeCommitMapsToGenericHttp413` · PlatformException |
| `PublishedAdmissionHttpPostgresTest` | 1 / 0 | 1 / 0 | `` · AssertionError |
| `RunAdmissionBoundaryPostgresTest` | 1 / 0 | 1 / 0 | `controlledDraftCannotCreateRunOrCallWhenInteractivePoolIsFull` · AssertionFailedError |
| `RunAdmissionHttpTest` | 0 / 5 | 0 / 5 | `oldAcceptedEmptySessionReservationCanRecoverAtCapacity` · IllegalStateException |
| `RunEventReaderPostgresTest` | 1 / 1 | 1 / 1 | `realSampleMeasuresCompleteRepeatedParentArraysAndPayloadGetterOrder` · AssertionError |
| `RunEventReaderTimeoutBudgetPostgresTest` | 0 / 1 | 0 / 1 | `` · NullPointerException |
| `RunInputValidationPostgresTest` | 0 / 7 | 0 / 7 | `actualSessionRejectsBeforeReservationAndAllowsCorrectedFirstTurn` · DataIntegrityViolationException |
| `RunObservationMetadataHttpPostgresTest` | 6 / 1 | 6 / 1 | `actualHttpProjectsSevenFieldsFromReadOnlyRepeatableReadWithoutPayload` · AssertionError |
| `RunObservationStatusHttpPostgresTest` | 1 / 0 | 1 / 0 | `freshStatusDoesNotResumeExecutionGrant` · AssertionError |
| `RunQuotaAdmissionPostgresTest` | 2 / 1 | 2 / 1 | `tenantOverrunRejectsBeforeRunInsertAndReleasesAdmissionSlot` · AssertionFailedError |
| `RuntimeClientsPublishedHttpTest` | 0 / 1 | 0 / 1 | `explicitLegacyConstructionKeepsKnownAppSkillModelRoutes` · MalformedInputException |
| `SessionEventsEndpointTest` | 1 / 0 | 1 / 0 | `diagnosticViewForbiddenForUserRecordsSingleAuditLine` · AssertionFailedError |
| `SessionSseBackpressureTest` | 3 / 0 | 3 / 0 | `connectionCapPerPrincipalRejectsWith429AndAuditWithoutTouchingExistingStreams` · AssertionFailedError |
| `TestInputHttpTest` | 0 / 9 | 0 / 9 | `pagingIsBoundedStableAndDoesNotExposeInputsInMetadata` · IllegalStateException |
