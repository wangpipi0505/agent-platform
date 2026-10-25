# O2b F2c2a 启动与容器保留边界修正报告

状态：DONE_WITH_CONCERNS。两源码、受影响 target 和本次 owned 完成后全部冻结交还 root；未签发 Accepted。沿 v1.2、Java21/Tomcat10.1.41/Servlet6.0 原设计，仅未装配组件及隔离端点，无真实 IAM/PG/provider/生产容量结论，无 commit/部署/主服务重启/公开 EVENTS。

## 实际范围

仅修改 PortalRunEventsSse.java、PortalRunEventsSseLifecycleTest.java 两条既有授权路径；source630→630，其余628完整名字/bytes/SHA exact。前者修接管 Session、原 REQUEST 校验、delegate waiting keys、固定 original-delegate phantom gate 和已完成 Handle 引用断边；后者复制原机械 child launcher 到本次 fixes/peers，新增真实失败/回收/容量向量及必要 scalar 断言。原 SseTest、C2a/C1 owned/report/review、八索引、设计/四契约及其它源码不改。

正式输入与优先级由 .local/iam-o2b-f2c2a-fixes-frozen-inputs.json 固定；已读 fixes brief、root retention amendment、原四份要求、原 report/review，以及两 source-only analysis 的 named gate/key/edge/JDK 段。采用 root1536/512B/128KiB，未采用 candidate3072 或旧1218计数。

## 启动所有权与原请求

start 从接收 Session 起统一处理异常，Q 和 beginSse 均在此边界内。没有开始异步生命周期时，清 token、释放已取得 lease 并调用真实 Session.close；Http 仍须实际 transport/timer/active 退出后才能退 Owner。已有 async 时 stop/真实 AsyncContext.complete/Session.close 仍经过原 completion/action/heavy/borrow/worker/IO gates；没有造 onComplete 或越门退费。

消费 Q 前检查原 private bridge.current、当前 origin/slot epoch、原 socket、原 request thread、真实 REQUEST dispatcher、原 request facade 和 response facade。真实 include 和把原 Servlet request/response/Session 转交独立 worker 都不能拿 attribute 充作调用时 scope。缺 Valve token 同样在 Q 前拒绝。reject 与每个新 REQUEST 会单向清 origin/State/response/socket/requestThread，且只保留一个当前 attribute；从不将旧 token 转向复用 socket。

真实 Q403 与 TLS Q transport-close 的旧生产 RED 都在测试收尾前记录 Owner 未退、awaitClosed=false；收尾 finally 的 Session.close 属测试补救，不冒生产修复。GREEN 每向量连续失败两次恢复 owners0/global1048576，随后同 protocol 正常请求仍可 start。worker/include/missing-token GREEN 的 Q connections=0，无 bootstrap worker/setter残留，Session 有限关闭。首轮旧 worker 触发真实有界 socket read timeout，另保留 red-offthread-bounded，只验该向量并明确断言 Q connections=2 而期望0；不是新诊断 API 编译失败。

## 固定容量与引用机制

addWaitingProcessor/removeWaitingProcessor 统一将本 protocol 的 WrappedProcessor 映射到其 final 原 delegate，再调用 super；plain original 保持 identity，foreign owned wrapper 拒绝。process remove、async LONG、old-null recycle、正常 recycle 和 stop 使用同键；实际双方向 super add/remove round-trip（waiting count 归原值）及真实 recycle 路径验证键一致。11个 Processor SPI及 timeoutAsync/checkAsyncTimeoutGeneration 原委托保持；没有替代 timer 或关闭 timeout 来解决 retention。

1536 个固定 byte states 为 FREE/RESERVED/TRACKED/BURNED，一个 private SlotReference[1536]、一个 private ReferenceQueue，SlotReference extends PhantomReference<Processor>，只加 index 标量。每次 factory 先 reserve FREE，再且只一次真实 super.createProcessor，再构造唯一 wrapper/reference、按原 identity 安装并发布；delegate final、一对一且有 reachabilityFence。所有 gate/bridge/queue/array/wrapper 新图都在原1MiB precharge 后。

任何 reservation 后构造/登记异常 burn 到 JVMexit；before-reference 与 after-install/before-commit 两个实际标量故障点均验证，两个 burn 使真实成功容量只剩1534，总 owned allocations1536 后拒绝。later queue notification 遇 BURNED 同 identity 也不退款，源码分支独立核对。没有 same-slot retry 或强 delegate 副本。

唯一 reference-bearing drain helper 在 admission intrinsic monitor 内 poll、核 index/state/reference-object identity、断 tracking 后置 FREE；helper 完整返回后外层才使用 credit/释放 monitor，外层只有 scalars，没有 probe/callback/nested admission/reference collection。mismatch fail closed。ReferenceHandler 先断 discovered，ReferenceQueue.poll 将 next 自环，旧 history 无增长链；四个 retired reference tails 各512B另计。无手工 clear/enqueue、get/refersTo(null)退款、反射/Unsafe、reference线程/任务、生产 GC 或无限 registry。

容量证明用 actual ControlledProtocol.createProcessor 强持真实返回 Processor[1537]；1535强根后两线程争最后一槽，旧生产成功2的 RED，GREEN成功1/拒绝1，再次拒绝且 creations/allocations仍1536。recycle和 waiting移除不退 bundle。该确定性 cap 与 GC 见证分开：独立 witness child 放下真实 roots，先自然观察1秒；未收到通知后按预授权调用一次 test-only System.gc，再有限5秒观察，实际1536个自动通知及真实 factory reuse。生产无 GC、无进度期限保证；没有手工通知。

## 完整对象/引用 ledger

allocation-ledger.json 保存逐字段表与 configured Temurin21.0.12+8 runtime flags、JDK src.zip 五个具名类字段身份。实际 ObjectAlignmentInBytes=8、compressedOops/classPointers=true；估算更保守采用 header16B、reference8B、arrayheader24B、align8，不凭算式当测量或 RSS证明。改变 VM 对齐/版本不在该固定 profile 内。

一个 bundle 的保守对象上界416B：WrappedProcessor56（4 refs+id long）；SlotReference56（Reference4 refs+index）；RequestSlot64（5 refs+2 booleans）；State168（12 refs+3long+15boolean+3int）；两个 monitor32；当前 Handle40。<=512，余96B。将完成瞬间的轻 State/Handle 也列入，未只计 wrapper 浅尺寸。State 的材料/frame/Session/IO/worker/action 重图仍归原 Owner，不能靠 bundle 释放或GC通知提前退费。

fixed 上界25944B：reference array12312、states1560、64桥1104、queue/lock/condition及至多两AQS lock nodes256、protocol/static标量与SLOT key512、所有68处 Java String literal occurrence独立保守计10200（不去重）。均小于131072，未加任何可增长集合/队列任务。1536*512+131072=917504；另4*512 retired tails2048，原 baseline 内尚129024未分配 margin。构造失败的至多一 wrapper/reference/token图仍占 BURNED bundle，不藏不收费历史。固定库/VM/native/GC/整个 delegate/framework heap 原排除保留。

retirement 在每个新 REQUEST（含普通/pipeline）、startup reject/preasync exception、normal onComplete、process exception/non-LONG、delegate.recycle 之前两分支均断 token五引用及 wrapper current slot，不依赖 State.finish。action仍先捕获原 socket/origin，取原socket最外锁后重新核全部关联并覆盖真实 action返回，不提前清活跃 action所需关联。

新增尾部修正：成功 Http cleanup后 State 将 token/probe/Handle/Session 清空，完成 Handle 将 State 清null并保存 after(long)/terminal(boolean)。Handle 是 static nested class，无隐式 outer；外部长期保存 Handle 不再保留 State/token/material/protocol 历史。adapter内部无 Handle/finishedState/token历史表。调用方可无限保存它自己收到的 scalar-only Handle 对象，该返回值对象数本身没有 fixedbundle 上界；本报告未把它们计0或声称任意调用方全堆被1MiB/双Owner限定。该调用方自持返回值与 adapter内部可达图边界保留给 fresh reviewer判断，生产 C2b仍未装配。

## 实际 epochs 与测试

以下每行均保留真实 source epoch、raw、Running XML/testcases、compiler名单、命令、退出与有限target，不拼成同场数字：

| stage | Maven exit | tests/failure/error/skip | actual child | finite capture |
|---|---:|---:|---:|---:|
| red-startup-cap | 1 | 5/5/0/0 | 5 | 396 |
| red-offthread-bounded | 1 | 1/1/0/0 | 1 | 396 |
| affected-matrix | 0 | 13/0/0/0 | 14 | 397 |
| detached-tail-regression | 0 | 7/0/0/0 | 9 | 397 |
| post-finalizing-guard | 0 | 2/0/0/0 | 2 | 397 |

首次共同 affected-matrix 为新增7方法+brief具名6既有方法，共13JUnit/14child全绿。其后只新增 completed Handle/State 引用切断与 missing-token fixture；detached-tail-regression 仅六个既有 Lifecycle方法+scope具名方法7JUnit/9child全绿。scope原有dispatcher与新missing-token包装在同方法，该方法已在root“只新增向量”指示前启动，实际多跑1个dispatcher如实保留；不再跑13矩阵/cap/Q。三个生产epoch不合成22项同场绿色。gate section SHA在两green epoch相同，已于ledger核对。

最终六方法对应 originalRequestBootstrapAndContinuousCursorTerminal（after/terminal标量及Handle.detach）、firstErrorAndLateFirstWindowCannotReviveRegistration（bad-receipt/late两child）、sameProcessorOnAnotherSocketCannotReceiveOldAbort、lateAbortCannotTouchReusedKeepaliveRequest（inert token）、forcedConnectorReleaseInvalidatesTokensWithoutInventingCompletion（两Owner/无completion/token五引用清空）、stalledActualActionRetainsTwoOwnersUntilActionAndCompletionExit。它们直接验证新的finish/Handle断边没有改变实际 action/completion/复用门。

全部新增历史31child实际 waitFor 返回、collector join、alive=false/collectorAlive=false；失败6child的非0退出保持。不是取消flag或peerEOF代替。普通生命周期最后owners0/global1048576；forced noCompletion向量仍保两Owner直至实际child JVMexit，不造completion/退款。不跑原29全套、旧C1原19/fix8、PG/provider/原replay。

## Git、target与冻结

继承 root已验 source630与logicaltarget2295及physicalcache，不重hash/replay原始baseline。首次主链privateGit before630与当时after630实际blob/全名全bytes；binary attrs先首次add；新2section binary patch，首次 plain check/apply 各一次0，replay无辅助index、无after copy，完整630精确。source-after、target-after、patch及两次操作即时journal/cache。元数据错误仅恢复未完成：初次测试编辑因 Python默认GBK在读取时失败未写源码，protected-controls首次路径relative错误在外部授权设计索引的读取前发生；均未重跑成功测试/Git阶段。

最终 finite overlay400、logical2295→2297。实际raw gateway main22/test23编译；common raw为Nothing to compile，common有限既有lists及对应输出只是继承身份佐证，不声称本轮重编译。finite仅名单及对应输出、实际resource复制、common fixtureJAR、实际Running报告；无whole target/classpath枚举。原C2a的24epoch/117child/14671own与root15713physical证明继承不重跑；23个具名保护control含八索引、四契约、设计快照实际身份校验，原report/review/input身份保持。

report/handoff/own-inventory及freeze最后一次封装后，全部两source/受影响target/own/helpers写权交还root，本writer不再修改。后续原reviewer审delta及root有限artifact增量审计通过前，不更新八索引/不接公开EVENTS/不签收。

## root授权的 post-finalizing 最小修正链

封装自审发现新nullable token的两个具体调度窗口：close 已取得旧State快照，在另一finish置finalizing=true后仍可进入旧stop，Session.abort后token可能已null；finish入口先判token!=null、再第二次读取给holdsLock，也可能夹入清null。root裁定同correction epoch只改stop加finalizing拒新动作，以及finish先local currentToken单次读取再holdsLock；无新graph/线程/probe机制。原探针无法精确pause这两次内部读取，故这里只记源码调度竞态与结构修正，不冒确定性执行RED。

post-finalizing-guard仅sequence与lateKeepalive两方法2JUnit/2child全绿，无其它旧GREEN重跑。五epoch累计31child全部实际退出。此前13GREEN与7GREEN保持原各epoch身份；本段2GREEN同样独立。

原 .local/iam-o2b-f2c2a-fixes/source-after.json、main primary/replay/native成功记录完全保持字节，仍是修正前630。最终source身份为 .local/iam-o2b-f2c2a-fixes/post-finalizing/source-after.json；最终target身份为该目录target-after.json（finite400/logical2297）。post-finalizing/primary 为独立复制的真实native repository，before tree继承旧main after tree；before630/after630实际full names/bytes与batch cat-file blobs闭合，仅1source delta，其余629 exact；原始630→最终630仍2修改/628 exact。post-finalizing/replay 从旧after630复制，在应用前已保存完整before；仅新1section binary delta 的plaincheck/apply各一次0，无index/aftercopy。两次chain各自一次操作，绝不把原已执行check/apply冒作final或覆写旧记录。

post-finalizing/correction.json 连接旧main source/Git/replay→post native/delta→最终source/target，command/result仍在stages/post-finalizing-guard，实际阶段追加同journal。root再从original630到final生成累计2paths审查包。新增guards无字段/分配/字面量，既有416B/25944B ledger原样继承；finished Handle仍仅两个标量且state=null，内部无历史。

## 限制

GC/ReferenceHandler无及时性保证，1536 gate可因延迟通知拒绝新processor；BURNED永久降可用容量。forced-noCompletion仍可能安全耗尽两Owner。原M1 encoding/deprecation、Tomcat shutdown线程/模块访问警告保留raw与diagnostics，不冒pristine或完整container join。外部返回值对象保留不属内部有限图证明；没有全JVM/RSS/native/JSSE/GC storage上限或生产容量验收。C2b真实独立gateway/engine/issuer/新PG/TLS链、生产装配和C2c前端null/大cursor适配仍属后续。
