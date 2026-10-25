# ServiceAuthorizationTest 单文件迁移独立审阅

Spec Compliance: **Approved**。Task Quality: **Approved**。Critical 0 / Important 0 / Minor 0。

本结论只覆盖该测试输入迁移。未发现缺失、额外实现或误解 brief 的阻碍项；不据此宣布全局 IAM、SSE、PG、B 或全部需求完成。最终 Accepted 由 root 在独立物理审计后决定。

## 审阅输入与方法

唯一工程 `G:/207/项目/算法重组工具/代码/agent-platform`。读取本工程 AGENTS.md，使用 subagent-driven-development 的 SKILL.md 与 task-reviewer-prompt.md。只写本报告；未改源码、target、作者封存件、8 索引，未运行测试/probe/browser/replay/git，未读邻接工程。

完整读取正式 brief、逐方法 notes、作者报告与 handoff；全部 bytes/SHA256 与派发值实测相同：

| 输入 | bytes | SHA256 |
|---|---:|---|
| docs/evidence/iam-stage-4-service-tests-migration-brief.md | 8630 | 46aef4415bbfb1216b119fd2160520a213790c75f40f5aa25dad3385f2c9c561 |
| .local/iam-legacy-service-tests-migration-notes.md | 14357 | 13b4edc3911330a218219b9a269fa7c3e473e21cc750fc6653aacb35c89e364a |
| docs/evidence/review-iam-stage-4-service-tests-migration-package.diff | 45132 | 7e31f1320635fba71cd7243256df9c28dd4955451f34e907034c737f557c1e42 |
| docs/evidence/iam-stage-4-service-tests-migration-report.md | 8966 | 6acbf76fd878a96e13ed17c44fe15ea71f3369af23cd4040b169100ddbf3f3bd |
| docs/evidence/iam-stage-4-service-tests-migration/handoff.json | 5680 | d229e1569711a01342fe96773b80c823c4a68813b6abc3f82304250eec635302 |

完整上下文 diff 分不重叠的 1–190、191–380、381–560 内容行读取到 EOF；其中 hunk 为旧 442 行/新 491 行。没有另读变更源码；以下 S 行号从 diff 的新侧逐行计算，S=`admin-common/src/test/java/cn/agentplatform/common/authority/ServiceAuthorizationTest.java`。健康方法在完整 diff 中全部是未改上下文。该文本 diff 只用于审阅，不当作可执行 CRLF 补丁。

## 关键合规证据

- S:28–52 定义两条合法 r_<32hex>、固定 SHA 和 status selector；domainRuns 两项均为相同构造。observations 只从显式传入的测试 Actor 提取 subject，再登记常量 run/SHA，不消费 JWT 或 HTTP 身份。S:48–49 明确此处不是 PG admission 或 engine 持久化证明。所有原 STATUS setup 均在首次 exchange 前调用此 helper，包括 workload 后半攻击、actorProjection、nested mock URI 与 retained initialAB。
- S:126 起先配置真实宽 portalActor，经真实 gateway.exchange 取得 ticket，之后才设 changeDecision 收窄 current；old=ticket 没有重签。checkResource=false 仍检查 ingress 角色/属性投影；APPROVER 403/业务0，EDITOR 200/业务1、roles 仅 EDITOR、region 仅 A。
- S:97–101 的测试 cancel controller 仅校验 RUN、CANCEL_RESOURCE 与当前角色后计数。S:147 起明确 integration STATUS exchange 精确403，再以普通 run 资源调用真实 cancel exchange→engine.authorize→gateway.resume；source/origin 逐项断言 first=INTEGRATION/INTEGRATION、resume=DELEGATED/INTEGRATION。first/resume 各自用真实 POST 请求验证 v1 200/业务1、admin/debug 各403/业务0，没有误用 GET expect。随后 changed 只制作 STATUS 攻击票；三条 STATUS GET alias 精确403、业务0且 authorityCalls 不增加。
- S:422–438 保留 secondary mapping 的 setOrder(-1)、isHandler=false、动态 registerMapping 与继承的全局 MappedInterceptor，业务资源改为独立 STATS_RESOURCE。S:440–462 经 gateway→monitor drilldown exchange、monitor.authorizeRetained，单个 bindRetained frame 内 S:451 requireBound→S:452 monitor.forward→S:453 requireBound；frame 结束后只用返回的 ServiceTicket 进入 engine HTTP。没有 retained/current 跨 frame 或成功票改签；wrong-route 使用另一个真实 engine STATUS 票，保留403而不是错误 audience 导致的401。gateway/monitor/client/frame 均在显式关闭范围内。

## 原 13 个方法及 health 覆盖

| 保留方法及新侧证据 | 实际保留的实质断言 |
|---|---|
| S:126 signedOldAdminCannotOverrideOnlineEditorInRealController | 真实宽票后在线收窄；角色拒绝、成功业务计数和返回角色/region 精确投影。 |
| S:147 integrationOriginCannotUseAdminAliasesEvenAfterDelegatedResume | STATUS exchange 拒绝；真实 cancel first/resume 的 source/origin 不洗白；两票三 POST 路由的精确状态/业务数；STATUS 攻击无在线查询。 |
| S:180 exactIdentityCapabilityAndResolvedDomainResourceMatrixNeverEntersBusiness | caller/action/tenant/sub403，target401，另run403、未登记403、HEAD403、disabled POST403，用户票/缺claim/坏签名/坏JWT401；最终合法200/业务1。 |
| S:208 resourceRefreshRevocationExpiryAndUnavailableNeverReuseInitialSuccess | beforeResource 在 preHandle 成功后注入 revoked/expired/unavailable，精确403/403/503、业务0且 servletFailures0；复位时钟后成功，再逐请求入站403/503及 JWT 过期401。 |
| S:226 requireForChecksLocalOwnerAndRejectsWiderActorWithoutUsingItAsAuthorityInput | 异 tenant、异 subject、更宽 Actor 均拒绝403/业务0；同 Actor refresh 成功200；无 frame 的 requireFor 和只有已验 JWT 的 Actor.current 仍拒绝。 |
| S:245 exceptionsAsyncHandoffAndConcurrentRequestsLeaveNoAmbientFrame | 原异常403及清理计数；Callable 内 AuthorityContext/Actor 均缺失；两个真实 run 票同时提交两个线程，arrived/release 两 latch 保证并发，两个 response200、各 run 业务1、清理至少4。 |
| S:292 bothWorkloadRowsAreUsableAndCannotBecomeDelegatedUserCapabilities | 两条 credential workload 行真实200/业务1且 handler 无 Actor/frame；伪 delegated403；后半由真实已登记 STATUS 票改成 workload 仍403/业务0。 |
| S:316 catchingFailedResourceRefreshCannotRecoverThePreviousActorProjection | frame 内撤权并实际 requireFor 失败，捕获失败之后 Actor.current 仍失败；最终403/业务0，未退化为测试前入口拒绝。 |
| S:364 freshSignedDecisionCannotChangeAnyOriginalSubjectGrantOperationOrCallBinding | tenant/sub/grant/caller/version/action/resource/operation/roles/attributes 全矩阵保留；每轮先恢复 current，再在 beforeResource 修改真正在线响应，精确403/业务0。 |
| S:386 actorProjectionRequiresContextWithSameTenantAndSubject | 真实 exchange/authorize current 后绑定，JWT tenant/sub 变体各使 Actor.current 拒绝；finally 清 SecurityContext。 |
| S:398 nestedServletDispatchCannotOverwriteTheRequestCleanupFrame | 同一 mock request、合法新 URI、相同 route template；首次 preHandle 成功、第二次拒绝；finally afterCompletion 和原 handle close，结束后 context 缺失。 |
| S:440 secondaryMappingUsesTheSameRealHttpCurrentAuthorityGuard | 真 monitor forward 票在动态 secondary HTTP200/业务1；撤权后403/业务0；真实 STATUS wrong-route403/业务0。 |
| S:465 retainedInitialUsesOnePreHandleQueryAndOneExplicitRefreshThenClosesOnHttpAndAsync | 同 known initialAB subject 注册；initial ceiling 精确 initialAB/current region仅A；requireBound；同步 authorityCalls 恰加2、async 恰加3；两次离 frame 的 captured proof 都拒绝。 |
| S:348 explicitlyRegisteredExactHealthNeedsNoBearerAndGrantsNoSiblingPath | 原方法正文未变：health200；liveness/info/prometheus401；业务0且 authority requests 空。 |

完整 diff 只有一个源文件，无方法增删/重命名/disable，无将精确状态放宽到任意4xx。其余替换是与 selector 一致的路径、domainRuns、mock URI、negative resource 与并发计数 key；没有为了通过测试删除原断言。新增 helper 和 cancel handler 都服务于指定迁移用途。

## 有界外部源码检查

以下仅查 Accepted 冻结源 `.local/iam-o2a-a-lifecycle-fixes/final-source/` 下的同仓库相对文件；并未机械扫描设计或整个源码。其物理身份由 root 的 Accepted baseline 审计承接，本 reviewer 不重复 589 项物理核验。

| 具名风险 | 查阅与结论 |
|---|---|
| STATUS 能否被普通资源或 INTEGRATION 输入绕过、登记是否伪造外部身份 | admin-common/src/main/java/cn/agentplatform/common/authority/RunObservationSelectors.java:12–15,41–53：status 要求合法 run/SHA 并产生 run-snapshot，purpose 双向限定 gateway→engine、delegated、PORTAL source/origin。admin-common/src/test/java/cn/agentplatform/common/authority/LoopbackAuthority.java:45–47,518–523：登记不可变 owner/resource，使用时再次比对受验 subject/resource；rg 定位的 :108 ceiling 是 tenant-a/alice，与宽票同 subject。 |
| cancel alias 与统计转发是否用了不存在或不同用途的 catalog 行 | admin-common/src/main/resources/agent-service-authority.json:843–875：两 admin POST cancel integrationAllowed=false，v1=true；:975–984 为 monitor→engine statistics.run.read；:1935–1944 为 gateway→monitor drilldown。测试 action/method/target/caller 与这些原行匹配，catalog 本身未变。 |
| retained/current 是否可脱离原 frame 或伪造 forward 结果 | admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityContext.java:37–50,68–70：bindRetained 建立当前 frame，requireBound 要求同一 mapping 且验证有效；admin-common/src/main/java/cn/agentplatform/common/authority/HttpCurrentAuthorityClient.java:292–308：forward 校验 current、发真实 query 并检查 operation，authorizeRetained 解码并核 call。S:450–455 与这些 API 配合，在同一 frame 使用 proof，只让真实 ticket 离开。 |

## 验证证据与边界

作者报告及 handoff 记录唯一命令 `node .tools/run-iam-module-tests.mjs iam-stage-service-migration-final01 admin-common ServiceAuthorizationTest`，一次执行、exit0、Running 仅本类、XML14/0/0/0。这里按禁止重跑的审阅合同使用已有执行证据；raw/XML/command/source 对应身份及 589 源、2023 logical target/187 overlay、1833 own-inventory 的物理审计由 root 独立完成，不将作者自述冒称本 reviewer 重新执行验证。

不能从单文件 diff 独立证明的事项：全589源码仅此一改、8索引不变、target归属、进程物理退出、原件/重放及 raw/XML 全部身份。上述项交 root 有限审计，不扩大本次代码审阅，也不新增复跑要求。作者明确保留原85=72PASS13ERROR历史、初次LF patch check1未apply及后续byte-exact check0/首次actual apply0；没有把失败改写为首次全成功。报告记录的新 dumpstream 是既有 Manifest-JAR classpath/root 诊断边界，不能将当前测试通过扩大为全套运行无诊断。

## 问题与结论

Critical: 无。Important: 无。Minor: 无。没有必须修复的代码或测试迁移问题。

规格和质量均 Approved：实际 diff 保留全部方法和主要断言，修复 STATUS 输入与两类特殊授权链的用途冲突，成功票来自真实 authority 调用，并维持失败关闭、精确错误分类、同步/异步调用计数和真实并发覆盖。root 物理审计仍是最终 Accepted 的独立条件。

本报告写入并核对后，交回本报告全部写权；其余文件始终只读，停止写入。

EOF
