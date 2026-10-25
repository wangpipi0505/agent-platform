# C2a Http 重引用 helper 实际返回前持费修正

状态：DONE_WITH_CONCERNS，作者实现与有限证据已完成并冻结交还；未签 Accepted。需原 reviewer complete2delta 复审及 root 有限增量审计后裁定。未更新八索引、四契约、执行文档、原设计；没有 commit、部署、main 重启、相邻旧工程访问或新增 subagent。

## 依据与边界

唯一 cwd：G:/207/项目/算法重组工具/代码/agent-platform。先读指定8916B brief和2069B frozen-inputs，再读 AGENTS、SDD implementer contract、TDD/verification/ponytail及必要项目约束。冻结输入继承source630 `6d4e023e2ab5f5f5fbbc3bd386bd967b67c67c546872ccb0ba408ca6f66c32d8` 与target2298 `32b310dbcfc0c58858f3d4148a18b447d93b2f2b1017d8e6f050b1e050f095d8`；上一波 artifact PASS 不作为代码 Accepted。原 reviewer 第三波 R-I1/R-M1 是本次修正依据，继承 M 日志问题仍登记。

仅修改既有 PortalRunEventsHttp.java 与 PortalRunEventsSseLifecycleTest.java。source630→630、2修改、628完整名字/bytes/SHA exact，无新增 source；所有 Sse/Budget/Verifier/Decoder/common/controller/assembly/frontend/POM/main/其它 test 均未修改。完整两路径逐行源包和文本差异见 two-source-package.md/two-source.diff。

## 两条原时序与修复

旧 stopTransport 在 Session monitor 下复制 handle/request 到 c/r，离锁后仍需 c.abort/r.cancel；合法 Session.abort 并不必须处于 State.actions 之内。另一真实 completion/closer 可清 transport 与 Owner，而原 stop 栈仍持有待使用快照。旧 finishTransport 同样持 closingHandle/request/alarm/timer/client/manager；它在自身 finally 发布 cleanupActive=false 并 notify 后仍未实际返回。TLS terminated 只证明 TLS 内部退出，不能证明 Http 祖先 frame 退出。

新增一个 int transportStops。stopTransport 在任何重快照前、同一 Session monitor 内准入；transportClean 或已选举 cleanup 直接返回且不抓任何 transport/probe。计数最大值拒绝新快照，已有 admitted stops 负责实际唤醒，避免整数溢出。一般并发 stop 仍可同时准入，保证早先 canceller 暂停时另一路仍能真正 abort IO。cleanup 已选举时负责原 handle.abort/request.cancel，拒绝重复 stop 不会遗留无人负责的唤醒。

重快照、probe及所有 RuntimeException|Error 捕获均放入实际返回的 stopTransportReferences/finishTransportReferences。outer 仅持 Session 与 primitive，inner 真返回后才减 stop 计数、解除 cleanupActive 或发布 transportClean。cleanup election 有界等待 cleanupActive/transportStops==0，保留 active/operationThread 原谓词；选举之后阻止新的 stop 快照进入。库 close、物理等待、callback 都在 Session/guards monitor 外，保持原锁顺序。closed/clean 快路径不重新捕获 transport。

根授权明确允许该窄边界以失败持费取代 unchecked Error 传播。inner 把 RuntimeException|Error 转为 primitive false，不保存/抛出/记录 Throwable；不把任意 Throwable 带到门释放之后。失败不发布 clean，保留原 transport 字段供后续独立有界 cleanup 重试；InterruptedException 保持 interrupt 标志，timeout/中断同样 false。outer 在失败 inner 实际返回后解除选举，无同次无限重试。成功 helper 先实际 TLS/timer 退出和 client/manager close、运行尾部 probe，再清所有 transport/testhook/guards 字段，实际 return 后 outer 才发布 clean。未削弱 decoder borrow、SSE lease/material、worker/action/completion、slot或Owner门。

新增 nullable transportCheckpointForTest 正常为 null，仅作 bounded阶段观察/暂停；任意捕获仍由原重费用覆盖，成功 cleanup 在退款之前切断该字段。stop-snapshot 只有本次真实局部 c!=null&&r!=null 才触发，不导出资源、不合成资源退出。cleanup-tail 在旧版是 finally 已开放旧门但重 frame 未 return，在新版是重 inner 接近 return 且门仍闭；相位对应的是实际引用 helper，不把 test latch 当生产退出。

## TDD、可达性与实际测试

先实际读取、冻结native before630及完整源名后测试。RED 阶段只增窄 hook/测试及新owned child根，未加入门或改变 clean 发布时间。首 stage 的 stop 退款过早断言失败，但当时未证本次 c/r非空；首 cleanup 目标被 active && operationThread!=current 原门挡住，6s未到 hook，是保留的可达性探测失败，绝不冒称第二真实 RED。

cleanup 重试向量使用真实隔离 TLS/HTTP/Session。child有限期内，对非 named closer1 的真实 cleanup-snapshot hook 抛 RuntimeException，由原 catch 返回false并保留真实字段；实际 operation finally 自行置 active=false，真实 worker/completion退出后 closer1重试并暂停于 cleanup-tail。此为真实失败后可重试分支，不写 active/operationThread/terminated，不伪造完成。该 fixture 拒绝次数未计数，不能声称一次；root已明确放宽先前一次性建议。实际 second closer 经旧 clean快路径返回true、Owner0、reserved3145728，构成第二真实 behavior RED。

初次五方法 GREEN 后，自审发现 stop 非空局部见证不足，先保存当前完整字节及原 source epoch，按 root独立授权 `.local/iam-o2b-f2c2a-http-exit-nonnull-witness-authorization.md` 只给已保存旧 hook-only 行为加入相同 c/r非空条件，仅跑 stop 单项。它确实到达并在第二closer返回true、Owner0/baseline时按退款断言失败；恢复原 gate及同一条件后仅跑 stop单项 GREEN。原 stop/五方法 epoch 原样保留并降为该细节未证，不改写成最终同head全绿；另四项证据继承前一epoch。没有重复 cleanup/lightShell/ancestor/firstError或五项矩阵。

| stage | Maven exit | tests/fail/error/skip | actual child | source630 SHA256 |
| --- | ---: | --- | ---: | --- |
| red-hooks-only | 1 | 2/2/0/0 | 2 | `cef3efb9d0440c0eb4ecb35fcfa2c70a48dac33e58c7aa1b2dc52fffc57d5148` |
| red-cleanup-retry-hooks-only | 1 | 1/1/0/0 | 1 | `75998960c92d3390362f225d7c6b494ed912474378fba132a60b6e61b4279d64` |
| green-affected | 0 | 5/0/0/0 | 6 | `ba94bc33eed1901d400321e00eb91f62d2879add03b0bc08d2b86ebc8b1435a4` |
| red-stop-nonnull-witness | 1 | 1/1/0/0 | 1 | `b24eab69f98736392f166052d0c327a1384ae87a76fbbc8846b1611048496edb` |
| green-stop-nonnull-witness | 0 | 1/0/0/0 | 1 | `6f61ed3ce5ce87fbafead1dd1434f2b1d9dc1b3c3692be81a51c19a7155e3b9d` |

实际 affected方法仅 actualAbortSnapshotMustReturnBeforeHttpRefund、actualCleanupSnapshotMustReturnBeforeHttpRefund、inertReadyIdleShellAllowsMaterialFeeExitButRetainsFinalOwner、actualAwaitClosedAncestorPinsRefundedShellCredit、firstErrorAndLateFirstWindowCannotReviveRegistration。最后一方法本身运行 bad-receipt/late 两 child。原 green-affected=5/0/0/0、6child；最终 green-stop-nonnull-witness=1/0/0/0、1child，不能合并为同一source全套结果。

每阶段实际命令保存在 stages/<stage>/command.json：JDK21.0.12+8、`mvn.cmd -o -B -ntp -pl platform-gateway -am test -Dmaven.repo.local=.tools/m2 -Djdk.net.unixdomain.tmpdir=V:/.local/tmp -Dtest=<上列限定方法选择器> -Dsurefire.failIfNoSpecifiedTests=false`。使用独立新owned端点/原有有界privateSTDIN fixture、仅本module classpath。测试与生产 GC均未调用；没有29/14/10/7全套、mixed、cap/burn、PG/provider、旧审计重跑。每个stage的完整630 sourceEpoch、实际raw/XML/Running/input/created名单、有限target和process-exit即时缓存。

总计5个实际Maven阶段、10个方法调用、11个child（7 exit0、4 exit1），父Maven为2次exit0、3次预期/探测exit1。全部11 child实际 waitFor95s返回、finally waitFor3s返回，collector join后alive=false；所有失败child也真实退出，不抹掉输出。具体 launch/exit file identities 与sourceEpoch见 peer-exits.json，父Maven实际process exit见stage-catalog.json。重 TLS peer worker也由原 SequencePeer.close真实join5s并断言退出。未把容器日志中线程关闭提示当完整容器线程同步证明。

两个 GREEN窗口暂停期间 secondCloser=false、Owner1、reserved7405824；helper真实返回并两测试线程实际join后原 Session/Handle awaitClosed成功，Owner0/baseline3145728。窗口原Http图至少3145728B仍收费；7405824为该实际状态的观察值，不扩成通用价格。lightShell中间断言改为 connectorBaselineBytes()+6169798L，实测 retainedBeforeCompletion=9315526；evidence写真实retained/final值。末尾baseline仍3145728。原ancestor保持final锚及callback/lease真实关闭门；没有加入合成onComplete。

## 字段、闭合图与有限模型

实际 javap命令/exit/raw保存7个相关class字段，actual-field-bodies.json逐字段记录。Session52字段：33 references×8 +6 long×8 +12 boolean×1 +1 int×4 +header16=344B，按align8已对齐。原336B，新增 ref8/int4消耗部分原padding，实际有效增加8B；344≤Session allowance384。字段诊断来自green-affected，最后只改同一hook条件，无字段/闭合图变化，精确epoch在ledger中登记。

闭合核心为原 Handle/ref/State/两monitor296 + Session344 + releasedLease56 + closedOwner48 + 空guards56 + Object[8]88 =888B。正式 declared表仍1312B≤1792B，Session384等具名allowance未变。Frame/Thread/transport/任意probe/Throwable都不被当成8B closed edge。TLS/shared config与Owner规则继承原归属，未以全JVM/RSS/native/GC期限作保证。三个新checkpoint常量每个保守128B，在固定图另留384B，总24784<131072。

1536×1792+128KiB+4×1792=2890752<3145728，原两Owner+baseline200576852<201326592保持；共享1536/ReferenceQueue、唯一final锚、1792信用、3MiB baseline、单JVM一protocol、1024/64/64/64桥、11SPI/socket/epoch/recycle均未改。

## 最终 native Git、有限 target 与偏差

所有 source/fixture自审在第一套最终Git之前完成，之后没有源修改。native primary/replay before630由实际冻结source-before复制，未复用旧.git；首次add前设置binary attributes。完整630实际before/after Git blobs由native cat-file batch逐条校验bytes/SHA；tree `ec9cc2bd1fb9c2023b0d393b8ba2babd0bbf6716`→`21c1dd73acdeca42b2c57054c4bfd3cd8a7e5069`。两路径4829B binary patch `60b6f344cc39a76c725fd0680f1aca13dc5fcf4bd6ec13d3e0a7fa033608b7f7`；plain apply --check一次exit0，plain apply一次exit0，无辅助replay index、无apply后copy。成功Git命令stdout/stderr/exit立即缓存，未重复成功阶段。

source-after.json 124057B/SHA `6f61ed3ce5ce87fbafead1dd1434f2b1d9dc1b3c3692be81a51c19a7155e3b9d`。最终target只由实际compiler input/created、source-bound三资源、common fixtureJAR、actualRunning/report形成401有限overlay；common NothingToCompile按继承artifact登记，绝不声称本轮新编译common。覆盖继承2298逻辑manifest后仍2298，20条hash/bytes变化，无全target/classpath扫描冒新编译。target-after.json 493693B/SHA `304be175bbbcd46f11fa045617b34c456526be83925a0599b3de43877081eeda`。每阶段target快照与最终overlay分别保留。

初runner的E常量断言残留旧前缀，在任何Maven/stage启动前拒绝；没有写旧owned。该最早失败helper修订未单独snapshot，错误原文在会话，metadata-deviations.json如实登记。自审metadata首次默认GBK读UTF8失败，保留原失败helper并改显式UTF8，只续自审。最终before-copy前两次因WindowsPowerShell5把BOMless UTF8常量当ANSI被sole-root断言拒绝，均在任何copy/Git之前，缓存exit1/stderr/失败helper；显式UTF8 BOM后仅续未完成copy。成功Git/测试均未重做。原失败epoch、Maven失败、child退出及warning全部保留。

原review M日志/编码/容器thread关闭噪音维持，相关raw与child.out原样留档。新实现是窄fix，仍可能因外部库长期不退出而持费并降低可用性；这符合有限timeout失败持费语义。无GC进度承诺、无Accepted或八索引修改。原reviewer复审与root审计尚待；C2b/C2c继续后续工作。

## 冻结交还

本报告、handoff.json、own-inventory.json作为三个control。inventory包括本轮全部newowned、helpers、报告、handoff及成功/失败缓存，只排自身一个inventory；旧14671+8643+7062等owned按冻结epoch继承，不用当前宽glob反向添加。两source、有限target、本轮owned/helpers、独立report四类写权全部冻结并交还root，作者此后不再写入。未自行标Accepted。
