# O2a A：继承服务授权测试冲突处置

本决定由 root 对冻结基线独立核验后作出，属于执行范围内的测试证据归因，不修改原 v1.2 设计、四份契约、生产授权规则或旧测试。A 正式 brief 与 seam approval 保持原文。本补充明确 final01 的已知继承失败如何记录，不能把失败改写成通过。

## 已核冻结身份和原因

- ServiceAuthorizationTest.java：33932 B，SHA256 `7ddcda5d63b6ecb8d2aa4731865d93e499c45de47065aa3ed4e4ce89e04c4c6d`；位于 docs/evidence/iam-stage-5-n4b2-gateway-published-chain/frozen/admin-common/src/test/java/cn/agentplatform/common/authority/。
- LoopbackAuthority.java：49744 B，SHA256 `523d93b484f47065f15d1b998f182bb95d2f1bf79d154bff10685fa65a0f7518`；位于 docs/evidence/iam-stage-4-o1b-readonly-status/frozen-overlay/admin-common/src/test/java/cn/agentplatform/common/authority/。
- RunObservationSelectors.java：2918 B，SHA256 `3e53c32a47856fc52f0bc61f45ea40fea70143875c0aec9e76274035e44adf24`；位于同一 O1b frozen-overlay 的 admin-common/src/main/java/cn/agentplatform/common/authority/。

旧测试第 28–29、41 行将 run/r-test 资源配给保留 STATUS action；旧 fixture 第 199、209、402–407 行已在 exchange 阶段调用 STATUS 校验；旧生产 selector 第 29–33 行要求 run-snapshot、合法 public run ID 和 PORTAL 来源。旧 integration 用例还预期 integration 可以读 v1 STATUS，与既有 PORTAL-only 规则冲突。以上结构性冲突在 A 修改前已经存在。root 与本任务独立 reviewer 分别核对了冻结文件；没有重跑旧基线以制造第二次相同失败。

## 本次执行和验收边界

author 报告 final01 已实际运行指定 8 类共 85 个方法，其中新 Profile 21、新 Receiver 1 及其余 5 类共 71 方法通过；ServiceAuthorizationTest 为 1 通过、13 ERROR，合计为 72 通过、13 ERROR。以上动态数字仍须最终 raw Running/XML/执行源码身份审计确认。不能报告 85 全绿，也不能将 7 类 71 项与旧 health 重复汇总成新的测试命令。

不放宽 receiver；不改旧测试；不重复已绿 7 类；不追加只选择 health 的命令来粉饰旧类结果。完整保留 final01 原始退出码、日志、XML、执行源码快照和 13 个前置错误。A 的新增功能是否可接受，由其 7 类实际结果、完整实现审核、证据审计和本继承冲突的透明披露共同判断。本决定不预先授予 A Accepted。

## 独立旧测试迁移待办

以下 13 个方法必须逐名保留为未完成迁移，不计通过，不静默豁免。迁移另建具体任务与测试输入，保持实际支持的路由/来源/资源关系；不得恢复已关闭的旧 STATUS 语义。

1. signedOldAdminCannotOverrideOnlineEditorInRealController
2. integrationOriginCannotUseAdminAliasesEvenAfterDelegatedResume
3. exactIdentityCapabilityAndResolvedDomainResourceMatrixNeverEntersBusiness
4. resourceRefreshRevocationExpiryAndUnavailableNeverReuseInitialSuccess
5. requireForChecksLocalOwnerAndRejectsWiderActorWithoutUsingItAsAuthorityInput
6. exceptionsAsyncHandoffAndConcurrentRequestsLeaveNoAmbientFrame
7. bothWorkloadRowsAreUsableAndCannotBecomeDelegatedUserCapabilities
8. catchingFailedResourceRefreshCannotRecoverThePreviousActorProjection
9. freshSignedDecisionCannotChangeAnyOriginalSubjectGrantOperationOrCallBinding
10. actorProjectionRequiresContextWithSameTenantAndSubject
11. nestedServletDispatchCannotOverwriteTheRequestCleanupFrame
12. secondaryMappingUsesTheSameRealHttpCurrentAuthorityGuard
13. retainedInitialUsesOnePreHandleQueryAndOneExplicitRefreshThenClosesOnHttpAndAsync

唯一不经过该旧 exchange 前置的 health 方法为 explicitlyRegisteredExactHealthNeedsNoBearerAndGrantsNoSiblingPath。旧 workload 方法中前半段可能执行过，但后半段错误使整个方法仍为 ERROR，不能计为完整通过。

冻结后 root 只核必要物理证据，fresh reviewer 再读最终完整 diff；本归因不代替最终审核，不宣称全量 common 或整个后端通过。

EOF
