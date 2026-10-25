# C2a 返回对象人口与未启动状态清理修正

状态：FORMAL_REQUIREMENTS_FROZEN；仅在root显式派发后授予写权。基线为上一 fixes 最终630源码（38c4f04914fd3e6f6cd3fef557ea31158b99ca48937e532fdaf4c163a4a2c52a）、历史逻辑target2297（6ac8d833741f21af04d5b3a71a2a7fe54a21d49619c4fcd80b48dbb0c8b43831）。原C2a和上一fixes均仅artifact PASS，仍未Accepted；不得把C1以外组件写成新全工程验收。

## 输入与范围

唯一依据仍为v1.2，四契约、时间、旧工程隔离不变。读本brief与root正式shared-population补充（正式派发时给出），继承原C2a/API/type/profile要求及上一fixes要求。原wrapper补充中“original delegate referent”和全部GC/safety约束保留；固定数组/queue的类型及分配对象人口按新shared-population补充修正。

新finding完整来源：`docs/evidence/review-iam-stage-4-o2b-f2c2a-sse.md`22531B，F-I1、F-I2及F-M1；原10840B prefix和追加均冻结。上一report为`docs/evidence/iam-stage-4-o2b-f2c2a-fixes-report.md`，artifact审计为`.local/iam-o2b-f2c2a-fixes-root-check.json`，六PASS9683；不重跑原审计、已绿测试、旧Git/replay。

正式派发后只允许修改四个既有源码：

1. `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalRunEventsSse.java`
2. `platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalRunEventsSseLifecycleTest.java`
3. `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalRunEventsHttp.java`
4. `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalEventsPacketBudget.java`

source630→630，4修改、其余626完整名字/bytes/SHA不变。Http仅本次SSE创建/retention anchor/生命周期清理的窄接缝，Budget仅受控connector baseline从1MiB调整为3MiB及相应只读值；Owner/Reservation原规则不变。禁止改Verifier/Decoder/common/engine/Controller/Assembly/frontend/POM/main/其它test/原设计/四契约/八索引，以及此前已冻结owned/helpers/report/review。原日志噪音保留，不扩大配置修警告。LifecycleTest只最小切换本次运行目录，避免向上一fixes/peers写入。

新owned为`.local/iam-o2b-f2c2a-retention-fixes/`、`docs/evidence/iam-stage-4-o2b-f2c2a-retention-fixes/`；helpers仅`.tools/iam-o2b-f2c2a-retention-fixes-*`。报告为`docs/evidence/iam-stage-4-o2b-f2c2a-retention-fixes-report.md`。此前宽helper glob的成员按其冻结epoch继承，新helper不反向污染旧inventory。

## F-I1：返回对象和延迟State快照必须实际受限

不能将adapter创建的finished Handle以caller-return-value免责声明排除，也不能把State.handle清null后忽略任意延迟调用栈持有的State尾部。采用root正式补充的同一个1536共享人口机制：原Processor引用和新增Handle引用都消费同一固定名额；不是各1536，也不新增线程、动态集合、第二个queue或累计创建上限。

保持原`SlotReference extends PhantomReference<Processor>`的referent恰为真实super.createProcessor原delegate；新增private `HandleReference extends PhantomReference<Handle>`。Sse先在真实原REQUEST scope中准入和登记唯一Handle，再在同原线程通过窄Http forSse创建入口产生唯一Session；入口不得接受已创建Session或允许caller通过旧入口绕过准入。Session构造即持final lifecycleAnchor=该Handle，State也持唯一final Handle；Handle完成后只清State反向边。必须强制exact1Handle:1Session:至多1State，不能重定向旧Handle到新请求、重用Handle创建第二Session或复用旧请求身份。任何State/Session/non-static lease或其实际祖先调用栈根均保持对应Handle名额；未准入之前不存在需退款的Sse Session。所有owned构造必须先预留；不确定失败烧槽；只有实际GC自动通知、私有poll和index/state/reference object identity一致才返还。固定内部drain helper完整返回后才能使用新credit或释放准入锁。生产无GC、manualclear/enqueue、引用暴露或reaper。

原1024/64/64、固定64桥、JVM终身一个受控实例、真实socket最外锁、11方法委托/timeout、waiting双向delegate键、每REQUEST/complete/recycle前失效均保持。connector改为3MiB预收至JVM实际退出，先收费再分配；两Owner本身费用不变，合计197431124+3145728=200576852<201326592，余749740。shared1536可因caller长持Handle/Session、GC延迟或烧槽降低可用性，必须明确，不能绕过或隐退credit。

给出真实逐字段与可达性ledger；每个shared slot最多1792B，固定图131072B，4个retired reference tails另计7168B；1536*1792+131072+7168=2890752<3145728。区分processor小图与Handle/唯一State/唯一closedSession/lease/Owner/guards的深图，所有output/async/probe/worker/material/frame引用不能仅算一个8B字段就隐藏实际可达图。active/stalled heavy仍由原Owner覆盖，退款前切断重图并完成实际退出；闭合Session的小尾部由强anchor和1792B覆盖，包括真实reap/awaitClosed等尚未返回的祖先栈。若1792B或任何人口上界不能证明，向root具名NEEDS_CONTEXT，不默加免责或余量。

## F-I2：未开始async的已构造State先清重图再释放lease

真实startAsync拒绝时State/frame已存在。用窄helper清未启动State的frame/backing及其它非必要重引用、Handle反向关联、token等；携带重图的helper须真正返回，才由外层释放lease并执行Session.close的真实transport/timer退出。没有worker/IO/completion时不伪造onComplete；已经启动async的失败沿用原真实exit gates，不能统一提前释放。

完成/失败State仅保留为共享人口计数所需的唯一final Handle边和确有依据的小字段；SseLease反向Session、Session闭合后的小图及实际祖先栈由同一final Session.lifecycleAnchor覆盖，不再用Sse内counter猜测祖先栈已退出。若需要将原final lease字段改成受现有gate保护的nullable字段，先确保使用点和原action/worker/completion退出顺序，不能引入双读null竞态。保留上一nullable token修正：stop在finalizing期间不创建新动作，holdsLock只使用一次捕获的local token。

## F-M1：合法业务Q403

当前q403 fixture缺Content-Type且body为空，实际是framing早拒。改成原严格ApiResponse允许的403 JSON及正确Content-Type/length，断言原403状态和允许错误code，而非只assertNotNull。保留两次失败后Owner归baseline以及同protocol后续正常start的验证；将上一证据准确称为畸形响应失败，不回写上一冻结报告。

## 聚焦验证

先用旧生产字节得到真实的独立Handle人口准入失败测试；不以新增诊断API编译失败冒RED。可先用真实factory占据大部分名额，再通过真实Servlet/同processor复用顺序完成少量请求并保留旧Handle，观察后续start必须在新State/Handle分配前拒绝，无需跑1536个receipt请求或RSS测试。强根应使用有限test holder/reachabilityFence，不能mock Processor或人工设计数。

共享queue实际覆盖两类自动通知与重新准入，另测Handle构造/登记不确定失败的烧槽；原Processor cap/last-slot race/烧槽仍必须保持。GC见证单独fresh child：先有限自然观察，root预授权该新witness至多一次test-only System.gc，再有限等待；不手工通知，不循环GC，无进度承诺。必须记录实际观察，不能拿上一Processor-only通知冒作新Handle分支证据。

增加真实Servlet asyncSupported=false/startAsync拒绝向量，验证无worker/setter、State重图detach先于退款、Owner最终回baseline；源码顺序与观测范围如实区分，不为执行RED新增无必要的生产探针，不靠fixture finally提前收尾。合法Q403只跑该focused向量。

首次共同受影响矩阵还包括六个既有Lifecycle方法（本次实际改了State/Handle/finish/gate，因此需要本次覆盖）：

- originalRequestBootstrapAndContinuousCursorTerminal
- firstErrorAndLateFirstWindowCannotReviveRegistration
- sameProcessorOnAnotherSocketCannotReceiveOldAbort
- lateAbortCannotTouchReusedKeepaliveRequest
- forcedConnectorReleaseInvalidatesTokensWithoutInventingCompletion
- stalledActualActionRetainsTwoOwnersUntilActionAndCompletionExit

及实际factory cap/last-slot与processor构造烧槽两个直接方法：actualProcessorFactoryRefusesBeyondFixedStrongPopulation、uncertainProcessorConstructionBurnsItsReservedBundle。若新mixed reference witness已覆盖原processor通知，不重复旧独立GC witness。作用域worker/include/missingtoken、transportQ等未改分支继承上一结果，不跑原全29/上一13、7、2集合或旧C1/PG/provider。首次矩阵后只有新生产改动/新失败才触发受影响项，测试oracle修正只跑该项。

## 证据和交还

继承上一final630 immutable primary和logical2297，建立本次native Git before/after630及全名全bytes；binary attrs先首次add，plaincheck/apply各只一次，不建辅助replay index、不应用后copy。before必须在首次测试前冻结；先完成生产修改、实际测试和引用/异常路径自审，再冻结最终after并执行最终patch/replay。不要为早封装而在自审前执行成功replay。按实际source epoch保留raw/Running/XML/testcases、compiler名单/资源/common fixtureJAR/实际Running有限target、所有child真实waitFor/collector。源或元数据错误立即缓存，成功阶段不重复；若成功replay后又改生产字节，保留旧成功身份并增加明确新delta，不能覆写冒作最终。

报告列修改与开源对照、引用边/ledger、真实RED/各GREEN、命令/实际退出、源/target/Git链及限制。返回report/handoff/inventory三个实际路径与bytes/SHA，并明确四source/target/全部owned写权冻结交还。原reviewer复审和root增量审计闭合所有Important之前，无Accepted、八索引更新、公开EVENTS或部署；之后继续C2b真实入口/跨进程链和C2c四前端消费点。
