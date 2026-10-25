# Initial model stream review fixes

交付状态：ALL / noBackground / NOTAccepted。此项修复供独立复审和 root 物理核验，不代表代码验收、生产 IAM 激活、engine sender 授权或部署。accepted code baseline 仍为 677；输入 680 只取得完整性资格。原报告、清单、helpers、ZIP 和关闭资源未修改。

本报告的 B 为 `.local/iam-model-initial-stream-fix/`，所有相对引用均以当前项目根目录为基准。最终外层 handback.json 封存本报告及后续 inventory/control/ZIP 的精确身份；这些文件不能自引用。命令日志、参数和含属性的原始 Surefire XML 在本地私有 ACL 下保留，公开包仅提供它们的路径、长度与 SHA256。

## 范围与实现

680 个源文件保留，零新增/删除，3 MOD、677 相同。只修改以下既有文件：

- `model-gateway/src/main/java/cn/agentplatform/model/application/ModelCalls.java`
- `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationFixture.java`
- `model-gateway/src/test/java/cn/agentplatform/model/application/InitialStreamIntegrationTest.java`

I1：initialStream 先完成 provider 结果和真实 usage 持久化，记录 completionPersisted；不再在后续授权/清理异常时把成功账本伪写成失败。所有 permit、HttpClient 关闭与等待、capacity 释放结束后，实际 servlet owner 在 ModelCalls.java:706–708 再执行 genuine current material/binding refresh 与 owner/deadline 检查，才返回给既有同步 ModelController 输出 final。该 fence 位于持久化 catch/finally 外，输出撤权不回滚已发生的 provider、SUCCEEDED、usage、SENT，也不退款或重发。cleanup 保留主异常并附加 suppressed。

I2：initialTokenCount 的私有 beforeSend 回调在完整 SecretVault.resolve/凭据准备返回之后、sendAsync 前运行，重新取当前 material/binding、检查相同编码语义及身份，检查 owner/deadline 后重算剩余超时。授权不转交 worker，不在 PG 事务内发送网络请求。它是当前真实调用路径的私有边界，不是外部可注入 production test hook。

I3：fixture 构造器从开始取得资源起覆盖失败清理；closeOwned 尝试每一个已有 stage，后续关闭失败 suppressed 到原失败上，finally 清除属于本实例的 current。借用 DataSource bean 禁止容器重复销毁。测试失败发生于真实 pool SELECT1、Redis PING、HTTP client 启动之后；清理断言先于测试 emergency cleanup 和进程 guard。

## 真实 RED 与最终受影响验证

先建立独立 owner 正向全链（DIRECT 8.859s exit0），再得到以下真实行为 RED。第一次 ownclasses 只读编译失败修复后才得到这些结果，不把 setup exit 当行为 RED。

| 边界 | 原始 RED | 真实失败 |
| --- | --- | --- |
| I1 | red-final-boundary，6.901s exit1 | 实际成功 finished update 后 IAM revoke，客户端仍收到 final；provider 1、真实 usage 66 |
| I2 | red-credential-boundary，6.523s exit1 | 实际 owner 第二次 vault key 读取处 revoke，tokenizer 仍为 1，预期 0 |
| I3 构造 | red-constructor-lifecycle，2.053s exit1 | 已启动真实 pool 在构造失败后仍未关 |
| I3 teardown | red-teardown-lifecycle，4.709s exit1 | 前段关闭抛错使后续真实 pool 未关 |

I1 初次 GREEN 修改未命中实际 LF 文本，仍输出 final 的失败也保留；随后实际修正才通过。最终 I1 的 post-finished-revoke.json 记录 successful update=true、usage66、provider1、clientFinal=false；测试另断言 SUCCEEDED 和 quota SENT。

I2 使用 root 明确批准的既有 JVM Properties seam，独立测试 JVM 内在 fixture 完成后短暂 arm。透明保留原 Properties，只统计实际 servlet owner 对实际 key 的读取，第一次为 provider、第二次为 tokenizer。第二次发生在 vault 文件读取之后、凭据解密准备期间，同线程执行真实 IAM revoke，确认无活动 PG 事务。它不声称撤权在 resolve 返回或解密完成后发生；生产 fence 才是在完整准备后执行。finally 无条件恢复原对象并断言 identity，最终 credential-boundary.json 记录两次读取、propertiesRestored=true、tokenizer0/provider0，测试同时断言 log/reservation0。

最终新随机 key 组与独立 common/prompt 选择共 13 方法：9 DIRECT_JUNIT_CUSTOM_EVENT_DERIVED、4 Surefire。下表由 tests-union.json 逐项生成；elapsed 为所在命令时间，legacy 两方法同一命令，不重复计总时长。

| Phase | 方法 | 毫秒 |
| --- | --- | --- |
| final-fresh-final-boundary | finalRevocationAfterSuccessfulPersistenceSuppressesOnlyClientFinal() | 11833 |
| final-fresh-credential-boundary | credentialPreparationRevocationPreventsTokenizerEgress() | 8722 |
| final-fresh-constructor-lifecycle | failedReceiverSetupClosesAlreadyStartedDependencies() | 2309 |
| final-fresh-teardown-lifecycle | teardownFailureStillClosesLaterOwnedDependencies() | 7408 |
| final-fresh-positive | authenticatedInitialHttpHasExactDurableWireAndUnavailableUsage() | 13176 |
| final-fresh-tokenizer-ack | tokenizerRejectsBadFullRequestCountsAndAcknowledgementsBeforeReservation() | 14774 |
| final-fresh-tokenizer-deadline-2 | tokenizerWholeBodyDripIsCancelledAtBoundBeforeReservation() | 20427 |
| final-fresh-rollback-direct | realSqlAbortRollsBackSendingAndRefundsOnlyProvenNoSend() | 11585 |
| final-fresh-commit-unknown-direct | realCommitThenAckLossPreservesSendingSentAndNeverRefundsOrDispatches() | 10660 |
| final-fresh-legacy-regression | streamingWireRejectionAlsoPersistsZeroSendRefund | 10914 |
| final-fresh-legacy-regression | streamingAlsoFreezesActualWireAndReturnsRealChunks | 10914 |
| final-security | retainedInitialUsesOnePreHandleQueryAndOneExplicitRefreshThenClosesOnHttpAndAsync | 7428 |
| final-prompt | legacyToolsAndAssistantResultPairingRemainNativeAndToolExecutionDisabled | 4665 |

正向方法覆盖真实 STOP/raw wire、missing usage 诚实 unavailable、三个同 ticket 降级 body、重复不重发；tokenizer ACK 方法含 8 个内部异常情景。deadline 方法运行真实生产 10s whole-body 限制，真实 peer 取消发生在外层 guard 前，下游 reservation/provider 为零。SQL rollback 与 commit-then-ACK-loss 使用真实 PostgreSQL：只有证明 NO_SEND 才退款；已提交但 ACK 丢失保持 SENDING/SENT，不退款、不 dispatch。legacy 的两个业务方法使用已授权 fixture overlay，仅 BeforeAll 的端点/用户/secret lookup/Redis wiring 变化，AfterAll 和业务方法字节相同；regression-overlay.json 与 regression-overlay.patch 记录原/改身份及上下文。没有重放原 26/28/历史全套。

M1 的新 DIRECT adapter 记录 started/finished/skipped 的 uniqueId、reason、test/container、时间、status/throwable。计划中的容器 skip 传播为派生 skipped，失败容器下未执行方法诚实记 NOT_EXECUTED；XML 明示自定义 event-derived。非恰好一个成功方法或任何 skip/abort/failed event 均 fail closed。五个真实 adapter self-check：disabled method、disabled container、aborted、failed container 均预期 exit1，成功样例 exit0 且 before/after 实际执行。此为 adapter contract，不是生产功能或原十个无 skip 成功失效的声明。public-mechanisms 仅包含无凭据的 JUnit/adapter 源码。

## 源码、运行类和制品批次

最终 13 方法绑定第一次 frozen source（a5b3fb9a…），运行 cohort 在 compiled-capture/manifest.json 中有限封存。之后 native diff --check 检出新增七行尾混合 CRLF；root 授权只删除这七个 CR。eol-delta.json 保留旧→新精确字节位置，文本行和 token 内容无变化；最终 normalized source 为 final2-source/source.json（dea83bd7…）。同一已封存 javac 输入机制/选项在新 own 输出路径编译 ModelCalls，全部 3 个相关 class 与实际运行 cohort 字节/SHA 完全一致，详见 eol-equivalence.json。未重跑或重新标注那 13 个方法为新源码测试。

最终 native2-change.patch 对原输入680的上下文 diff，通过 git diff --check，并在独立 own index 无 commit replay 到680逐文件匹配。第一次 native diff/check失败和旧冻结快照全部保留。未调用主仓 Git。

先捕获 actual runtime 编译集合，再 own package-workcopy fresh compile/package 六包；其 artifacts.json/capture 与原六包原位保留。换行规范化后以 package2-workcopy 进行必要 common 编译/test-JAR 依赖准备与 model compile/package，未 install、改 POM、改缓存或重编 engine。最终 artifacts2.json 的 common/engine 四包来自第一次 fresh、源未变；model 两包来自 normalized package2。634 个项目类/资源 entry 逐项匹配对应 fresh capture；Boot loader、META-INF、其他 nested dependencies 不冒称逐项编译比对。model 内嵌 package2 common 依赖另作精确 SHA 校验并单列来源。测试 runtime、fresh package1、normalized model package2 三个 cohort 分明。

## 资源闭合与执行限制

仅一个新 PG DB，ACTIVE 期间为不同阶段追加独立资源；未 reset、删除、重加密旧 secret，也未使用 CLOSED DB。数据库仍保留真实数据 call_logs10、quotas16。setup/db-closure.json 记录 sessions0、memberships0、两角色 NOLOGIN/无高权，model/engine schema usage/create 均 false。27 组自有 Redis 都保存精确 Linux PID/start/executable/config 与 redis-closed.json；closure2.json 汇集组关闭和截至当时179命令记录。后续最终 control 再扫描已完成命令，按已登记 PID/创建时间确认 absent 或 PID reused，不作全局进程清扫；Maven owner mutex 可用且真实区间无重叠。

外部 command 子进程30s，server hard90/readiness30/shutdown10，精确身份 guard，单 Maven，等待真实结束后才启动下一条。所有实际 helper/参数在运行前封存并执行封存路径。当前/后续封存命令及自引用文件在早期有限清单中明确排除，最终 handback 补记其完成身份。noBackground 的最终判断以 control 加最后封存命令实际结束为依据。

两项独立执行偏差必须保留，整体 NOTAccepted：

1. 早期适配 helper 复用了旧固定测试 vault master key。资源、DB、角色、TLS、secret 文件为新建，但 key 值并非 fresh。后经 root 授权生成随机新 key，私有 ACL 文件和 args 注入，在同一 ACTIVE own DB 新组追加资源，重做必要最终选择；fresh-key-transition.json 记录转折。后续成功不改写历史，也不把早期 RED 说成 fresh-key final。
2. 初次读取旧 helper 全文时固定测试 key 常量出现在工具输出，违反 no-credential-output。该输出不能由后续验证抹去。此处仅记录类别/ref/hash，绝不重复值；公开包排除含该值的原始 helper 全文和私有 logs/args/config；本地历史私有保留，不删除。新随机 key、密码、完整 Properties 和原始 Surefire 属性不输出。

原独立审阅 M2 的 setup/超时/噪声日志，以及审阅者原 no-property-output 偏差仍属保留资格；本次不清洗历史或静音预期错误。failure-register.json 汇集报告前20个非零/启动失败/超时记录，其中包含有意 RED、fail-closed adapter 和 no-index diff exit1。实际非预期失败还包括：初始只读输出编译失败、猜测 pwsh 路径启动失败、deadline 方法名错误导致发现失败、组合 SendFacts Surefire 30.128s 超时（后分两个已授权 DIRECT 通过）、security/prompt 组合缺 reactor test JAR（后独立通过）、首次混合 EOL 检查失败、artifact metadata 两次 helper 实现错误（后新封存 helper通过）。所有原始结果保留；未把这些失败计作成功测试。

v1.2、四契约、调度、默认 IAM closed、D3 和 engine sender 范围不变。未 commit/deploy/生产激活。root 后续独立复审与物理资格判断仍是验收前提。

## 不可变引用

以下是本报告写入时已完成文件的精确长度与 SHA256；后续 inventory/control/ZIP/handback 使用同目录明确名称单独封存。

| 引用 | Bytes | SHA256 |
| --- | --- | --- |
| .local/iam-model-initial-stream/final-source/source.json | 135063 | 00e5b91d7072adbd54cd41d01aec2aff40005d013f72df81eabf9426610d9868 |
| .local/iam-model-initial-stream-fix-brief.md | 9234 | c4fe4e0d757c1358610a66079bd47299f1bbf80583e370c7772b125c04c03ecb |
| .local/iam-model-initial-stream-fix/final-source/source.json | 135063 | a5b3fb9accc2e6405ea43be5eb5e2c6947c858bf2ef10805410925b02058208a |
| .local/iam-model-initial-stream-fix/final2-source/source.json | 135063 | dea83bd7cc0a1ff997379b894280343aa879db618a71092cf03f54a7d2c1ca0a |
| .local/iam-model-initial-stream-fix/native2-change.patch | 21853 | 43c248c63cf18d9ae842df9dddf164b63fb6bc04143ca46a31c315a5d49a6314 |
| .local/iam-model-initial-stream-fix/native2-replay.json | 158573 | 7b3c56abedbdcdb5fbb27dbfe016576decd91aaa76a32b35a72ace61782c5861 |
| .local/iam-model-initial-stream-fix/tests-union.json | 16785 | bd125af76eea726ead2207dac0d2fc4e5784a80015424725d5bbb52526e89512 |
| .local/iam-model-initial-stream-fix/adapter-contract.json | 1551 | 2d3500f183573ff9cff16088e6c009c50c4679d85ba72b49a92f6912b8e8ad84 |
| .local/iam-model-initial-stream-fix/compiled-capture/manifest.json | 292285 | a86e1f2f3ade32fe9241cf1b9eb6c7dd4c8c9fb4375764a33b4991baad758777 |
| .local/iam-model-initial-stream-fix/package-compiled/manifest.json | 304423 | 9861c9a8c5503276735fec59a1e4cd5abe4c0c2caf420ef8c15ad6c69e356f57 |
| .local/iam-model-initial-stream-fix/package2-compiled/manifest.json | 136735 | 288b7394c60cc8c03fb7f38e95caa455074b63103036ff886a3667591c370e16 |
| .local/iam-model-initial-stream-fix/artifacts.json | 149957 | 599ecac5bbac628c2798ad2a0bd2bdb1b7fcc074253d1c953cd40f180e590dd0 |
| .local/iam-model-initial-stream-fix/artifacts2.json | 151350 | 4e49b22ec037115fdc93a718a264a4c204b24f23def51aa7a0a04053071260b7 |
| .local/iam-model-initial-stream-fix/regression-overlay.json | 928 | f19f0edc23a97bd5d826e95d0aa48e247513110f7b22bc7943ee15ffe5d02b6d |
| .local/iam-model-initial-stream-fix/regression-overlay.patch | 2541 | a42289e2a1a3c34a86568255104517c075bd9df284318668ef3e595dda6dac64 |
| .local/iam-model-initial-stream-fix/eol-delta.json | 738 | 6ff889793ba5678ef79b96ec3f217401e353cc4cee5a07520ffef5146a674126 |
| .local/iam-model-initial-stream-fix/eol-equivalence.json | 2478 | 78090c2d8db6938448aff8f600a33683bb453168cd26d7829d5d8ad058998267 |
| .local/iam-model-initial-stream-fix/fresh-key-transition.json | 411 | 7669a42d1d9b528062c23fa3885d8f5ee644da77381ba2887f21d46be4a447b9 |
| .local/iam-model-initial-stream-fix/setup/db-closure.json | 1113 | 95b0cc85d8f809f4199b6850d840fad2aa6ca94f8e7c04c332d98f7202855164 |
| .local/iam-model-initial-stream-fix/closure2.json | 298571 | 113c11a933dbc750b0b8088dfd119d68d6fc8ea1488531e2505073febe1bc11e |
| .local/iam-model-initial-stream-fix/failure-register.json | 7216 | a4358c8f9000a604d8ed64c8e42251de7d855efc2ec53d597bf0d4e0e3c50539 |
