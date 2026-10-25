# ServiceAuthorizationTest 单文件输入迁移报告

状态：作者实现及验证已冻结，等待 fresh reviewer 与 root 审计决定 Accepted。唯一工程 G:/207/项目/算法重组工具/代码/agent-platform；只修改既有 admin-common/src/test/java/cn/agentplatform/common/authority/ServiceAuthorizationTest.java，0 新增、0 删除源文件。Accepted A 历史结果不重测、不改写；8 个执行索引字节/SHA 均保持。T01 仍是唯一已完成任务。

## 实际改变

主 STATUS 改用两个合法 r_<32hex> ID 与 RunObservationSelectors.status 固定摘要，并同步 URL、domainRuns、mock URI、并发计数及负例资源。每个 STATUS fixture 在第一次 exchange 前，从测试配置的已知 actor 与常量登记有限 observation。登记不读取 JWT 自报身份，也不证明 PG admission 或 engine 业务运行存在。

宽 portalActor 先通过真实 exchange 取得宽票，再将在线 current 的角色和属性缩窄；两个 HTTP 请求都使用该真实票。integration 的 STATUS exchange 明确403；原方法使用 catalog 已登记 cancel、普通 run 资源及真实 exchange→authorize→resume，first source/origin=INTEGRATION，resume source=DELEGATED/origin=INTEGRATION。first/resume 的 v1 POST 各200/业务1，admin/debug POST 各403/业务0。另有 STATUS 攻击票三条 GET alias 均403且无新增 authority 查询。重签只用于攻击负例。

secondary 用独立普通 run 统计资源：真实 gateway→monitor drilldown exchange、monitor authorizeRetained；包内可信单 frame 中 requireBound→真实 monitor.forward→requireBound。frame 关闭后只有真实返回 ServiceTicket 进入真实 engine secondary HTTP。保留动态 mapping、setOrder(-1)、非普通 handler 与全局 MappedInterceptor；wrong-route 使用另一张真实 engine STATUS 票，精确403。全部 client 在有限 try-with-resources 范围内。

## 原覆盖核对

14 个方法名顺序与原件完全相同，无 disable/排除；health 方法及其相邻分隔文本原字节不变。

| 原方法 | 保留的实质断言 |
|---|---|
| signedOldAdminCannotOverrideOnlineEditorInRealController | 宽真实票不能替代在线 EDITOR/region A；APPROVER403/业务0，EDITOR200/业务1，返回投影精确缩窄 |
| integrationOriginCannotUseAdminAliasesEvenAfterDelegatedResume | STATUS exchange403；cancel真实first/resume来源和origin、POST alias业务计数；STATUS攻击全部403且无在线查询 |
| exactIdentityCapabilityAndResolvedDomainResourceMatrixNeverEntersBusiness | caller/action/tenant/sub403，target401，另run/未登记/HEAD/disabled403，直传用户票/缺claim/坏签名JWT401，最终200/业务1 |
| resourceRefreshRevocationExpiryAndUnavailableNeverReuseInitialSuccess | 首次入站成功后撤权403、过期403、不可用503，业务0/servletFailures0；恢复200，下一请求403/503，service JWT过期401，时钟复位 |
| requireForChecksLocalOwnerAndRejectsWiderActorWithoutUsingItAsAuthorityInput | 异tenant/sub及更宽actor403，同actor真实refresh200；无frame或仅JWT不可投影Actor |
| exceptionsAsyncHandoffAndConcurrentRequestsLeaveNoAmbientFrame | 异常清理、Callable无ambient frame/Actor、两个真实资源票与两个latch真实并发，各run业务1，清理至少4 |
| bothWorkloadRowsAreUsableAndCannotBecomeDelegatedUserCapabilities | 两条workload真实200/无Actor/frame，伪delegated403；后半真实STATUS票攻击workload403/业务0 |
| catchingFailedResourceRefreshCannotRecoverThePreviousActorProjection | frame内撤权、捕获真实refresh失败后Actor仍失败，最终403/业务0 |
| freshSignedDecisionCannotChangeAnyOriginalSubjectGrantOperationOrCallBinding | 全tenant/sub/grant/caller/version/action/resource/operation/roles/attributes矩阵逐项在refresh拒绝403/业务0 |
| actorProjectionRequiresContextWithSameTenantAndSubject | 真实authorize current绑定后，JWT异tenant/sub均不可投影；finally清SecurityContext |
| nestedServletDispatchCannotOverwriteTheRequestCleanupFrame | 同一个mock request首次成功、第二次拒绝；afterCompletion按原frame handle关闭，最终无ambient |
| secondaryMappingUsesTheSameRealHttpCurrentAuthorityGuard | 真实monitor forward票200/业务1，撤权403/业务0，真实STATUS wrong-route403/业务0 |
| retainedInitialUsesOnePreHandleQueryAndOneExplicitRefreshThenClosesOnHttpAndAsync | initialAB不变/current仅A，同frame requireBound；同步精确2、async精确3在线查询，离frame的捕获proof拒绝 |
| explicitlyRegisteredExactHealthNeedsNoBearerAndGrantsNoSiblingPath | 原方法不变：精确health200，三个sibling401，业务0且authority requests为空 |

## 唯一实际测试命令

node .tools/run-iam-module-tests.mjs iam-stage-service-migration-final01 admin-common ServiceAuthorizationTest

执行次数1；Java21/offline .tools/m2，入口工具未改。实际 Running 仅 cn.agentplatform.common.authority.ServiceAuthorizationTest；XML tests=14/failures=0/errors=0/skipped=0；真实退出码0，BUILD SUCCESS。Maven日志显示main Nothing to compile，testCompile编译29份测试源；compiler input清单为main 57、test 29，这不等于执行其他类。未执行O2a Profile/Receiver/其他旧类/PG/engine/gateway/POST/SESSION套件。

原final01的85=72PASS13ERROR是继承历史，不重复旧输入、不冒充新生产行为RED。当前一次整类结果只恢复本类覆盖，不宣称完整IAM、PG、生产运行、SSE、17需求/34任务/30验收完成。

每次实际测试命令前后均保存完整589清单和本任务文件全文，executedSource与finalSource独立且相等。真实Maven子进程已经退出；fixture、receiver、HTTP clients、并发线程池均在try-with-resources路径结束，未留下本任务服务。并发检查等待两个future的实际response及线程池close，未用cancel冒充物理退出。

## 封存、诊断及重放

源589=397backend+192frontend，仅1 modified/0added/0deleted。target继承Accepted logical2023，包括原历史diagnostic；本次187项overlay仅来自common实际compiler createdFiles/inputFiles、resource、tests.jar及实际Running XML/text。未扫描旧target树，未编译/恢复app。本次新dumpstream1个独立存档，内容是Boot Manifest-JAR绝对classpath/不同root提示，不进入compiler或正式logical；XML与真实exit仍为14/0/0/0和0。

私有no-commit全589重放从已核Accepted原字节建立。首次自动生成的LF文本patch check实际退出1，未apply；原因是工作区Git属性将diff payload规范为LF，而原件与final均为CRLF。失败patch、first-check记录及journal原样保留。只恢复尚未成功的重放阶段，另存byte-exact patch；该修正patch首次check0，首次实际apply0，应用后589名称/字节/SHA全部等于final，无copy final覆盖。成功source/target物理阶段直接复用缓存，没有重测或重做成功重放。

| 封存对象 | bytes/SHA256 |
|---|---|
| admin-common/src/test/java/cn/agentplatform/common/authority/ServiceAuthorizationTest.java | 37995 B | 0200c3923ca520237013f895db6ba000d10787e86477aa1da4c465285528592f |
| docs/evidence/iam-stage-4-service-tests-migration/source-before.json | 111999 B | 3ad9c38b06e8ddc637f6ef4a36a5c2e474d15cf306ba1af341a3d6fad01ae7e4 |
| docs/evidence/iam-stage-4-service-tests-migration/source-after.json | 111999 B | 01121a4739ff5a0299cce9e5ecaa0ab5d433cd0be177ffd5d03f9410c67fe346 |
| docs/evidence/iam-stage-4-service-tests-migration/target-after.json | 419609 B | 244535cb02df60585217088e321f2b6ac44aaca20de22821a080d66d41967e20 |
| docs/evidence/iam-stage-4-service-tests-migration/iam-stage-service-migration-final01/after/TEST-cn.agentplatform.common.authority.ServiceAuthorizationTest.xml | 36272 B | ebcc68cde46f02cdf1012a201cccb807cee642415bd7eb51bae5919109a0e714 |
| docs/evidence/iam-stage-4-service-tests-migration/iam-stage-service-migration-final01/after/raw.log | 4007 B | 98dcd4d368dc3d29f2de0fda7a52e816ebbfa22a951f106ca26587ff54934d72 |
| docs/evidence/iam-stage-4-service-tests-migration/service-tests-migration-byte-exact.patch | 30684 B | c169d8be46034fde40b41344a6124fd089250bb09765719b3d721d9a0cd57ed0 |
| docs/evidence/iam-stage-4-service-tests-migration/replay-source.json | 111999 B | 01121a4739ff5a0299cce9e5ecaa0ab5d433cd0be177ffd5d03f9410c67fe346 |

handoff.json与own-inventory.json在本报告后生成。inventory精确列出自己目录、独立证据目录、报告以及launcher原始log的新leaves，唯一self-exclusion是inventory自身；private replay的.git元数据也纳入，不混入历史证据。所有失败和成功原件只读交还。

## 交还边界

交还该唯一源码、common target/后端class及本任务.local/证据/报告全部写权。无commit、deploy、自动化、新chat、生产改动或B/PG/C/SSE实施。作者不更新Accepted台账；待fresh reviewer与root有限审计。

EOF
