# 原Full历史遗留事实与下一技术门：独立只读复核

2026-10-29 02:18（Asia/Shanghai），审阅者`/root/ex00_collector_review`。本轮只读历史JSON/SQL文本、原运行元数据/安全XML投影及相关源码；唯一新增本文件。未运行SQL、Java/Maven或产品测试，未改源/index、未读真实环境文件/凭据，未使用Superpower。本文是下一步测试规格的技术判断，不是候选代码准入、运行通过或EX03关闭。

**结论：遗留行支持恢复原次显式未确认分支及持久待清理状态，足以修正“服务端GET_BODY闩锁必然证明客户端已处于正常正文读取前提”的假设；不足以恢复当时stage/Call/executor原子分项或证明完整安全义务。root提出的五报告最小组合可作为下一技术门，须满足下文精确见证、分支及完整后置要求。原Full失败继续保留，不能仅因错误码符合设计就改判PASS。**

## 1. 历史归属与证据强度

证据目录：[algorithm-history-20261029-015042-551](runs/algorithm-history-20261029-015042-551/history-facts.json)。本轮读取时的SHA256：

| 文件 | SHA256 |
| --- | --- |
| history-facts.json | `33a5252041467621242aab0dbcba27d6e6255c9ad8f2d04f4e6e6a2f7e43e386` |
| window-and-source.json | `6be5d4a77346ce6cccef48a8c44342fb9c6bf0f15731c4e9ef9a5cdd79d9ddce` |
| history.sql | `65d8c18dc95424644e2a096d44745621e04a62ff8b158ae3be5064f39c10d485` |

- SQL仅在原native精确起止窗`23:49:19.2381126–23:52:48.9248179+08`按created_at选取；字面前缀`algorithm_interrupt_`同时用长度及转义LIKE限定，没有额外时间margin。该前缀在当前实际skill生产/测试源码中只用于这三个参数化向量，独立有限Source用不同前缀。单条SELECT/物化CTE保持同一查询视图；LIMIT13实际8行，故本次没有截断，三组为3/3/2，匿名化不输出原tenant/call值。
- 该SQL由只读session选项、5s statement/1s lock timeout及单次防覆盖门包围；native0、SQL摘要和时间回执一致。本审阅者只读这些记录，没有重新连接数据库或运行采集器。
- 源码每组以call+1暖场、call目标、call+2复用；查询按组最小call计算relativeCall，因此1/0/2顺序与上述三步一致。三组各有连续call，前两组均完成三个对象的DELETED，末组只有暖场1与目标0、没有复用2。结合原XML参数顺序及仅第三项失败，属于很强的结构/时序归属；它不是原XML逐项记录了tenant ID的审计关联。
- 独立核原EX03 `after/full-20261028-234917-584/native-exit.json`，起止窗相同、native0带failureIgnore，不代表测试绿。原`skill-registry-xml/TEST-cn.agentplatform.skill.infra.AlgorithmOffloadInterruptionTest.xml`SHA为`4c2ec4084e8918c27fda2a20a0bf06721764e5ae7d7e7c5f7c3504e76e491f36`，确为原四身份3P1F，第三参数在首次quiescent断言失败。manifest记录mtime `23:51:29.0757423+08`与现存归档相符。
- 唯一末组`4a1f36f638529f7aeb7b45ad8816c37f`：暖场1为READY；目标0为CLEANUP_PENDING/ALGORITHM_IO_STOP_NOT_CONFIRMED，created `23:51:28.830032`、updated `23:51:29.065114+08`，cleanup_after恰为updated＋3600s、attempts0、deleted_at null。目标写入距created约235.082ms，距XML记录mtime约10.628ms；这些时间一致性不等于测出了各阶段耗时分解。

**必须保留的历史限制：** 当前可见字段仍与原窗写入形状一致，没有观察到后续清理痕迹；不能据updated_at证明此后绝未被改写。普通业务表不是不可变审计日志，后续SQL可以不更新updated_at。现存暖场READY也不能混写成目标READY。原异常图、原始future状态、Call集合和原executor退出时刻没有实时记录，不能由当前PG行补造。

## 2. 原版本写码机制已对上

原Full的module-source-sha256前后记录与当前`ArtifactStore.java`均为`9DEB51566206D548110F1F6B5AAAB466619EB80D43E8D30B67F11B7E27B7541D`。

该版本`ArtifactStore.java:200–202`只在`unconfirmed()`置stopNotConfirmed；`stopAndAwait:211–253`以原200ms绝对drain预算等待原stages/Calls，再等待原owned executor。未settled或executor未在预算内退出均可走此显式拒绝。`offload`catch的414–431行把该标志唯一映射到目标PG的ALGORITHM_IO_STOP_NOT_CONFIRMED，并保留CLEANUP_PENDING、延后清理。实际skill生产源码中该持久码没有第二处写入。

因此可恢复“原次已进入并持久标记显式未确认分支”，不是泛称任意SDK异常；但不能区分当时究竟是stage、Call还是executor尾段未完成。尤其`algorithmIoRetained():110–112`只覆盖stopping且stages/Calls未settled，**不包含单独executor尾段**。executor未退出时该方法可false、quiescent仍false且未确认码合法；完整安全测试不能统一要求retained=true。

## 3. 最小默认组合与正文正常前提

保留原四报告身份和有限Source向量的全部既有义务；原GET_BODY正常例明确建立客户端正文/交接前提，另加一个默认原始GET_BODY handoff安全向量。注册为五报告/原Full预期674必须先回填规格，不能把新语义的GET_BODY PASS称作原场景未改变的复现。

正常GET_BODY可使用只读有界见证，避免重装真实MinIO客户端或增加生产hook：

1. 在**原总1s准备预算**内同时完成服务端闩锁和客户端见证，不能各加一个独立1s。原算法总2s、drain200ms及后续退出等待不得放大。
2. 同一次worker栈须有`ArtifactStore.readJson`和实际HTTP响应体→网络读取链的正证据，例如固定版本`Exchange$ResponseBodySource.read`及底层Okio/socket读取帧；不能仅见解析入口或只依据服务端已flush。固定OkHttp4.12源码包SHA `d91a769a4140e542cddbac4e67fcf279299614e8bfd53bd23b85e60c2861341c`中的`Exchange.kt:262–305`可对照该真实body Source路径。本轮只读相关固定源码，不声称审计全部SDK。
3. 同时确认原AlgorithmIo.stages为0、owned dispatcher无running/queued任务、原owned executor无active/queued任务，避免“worker已进入readJson但SDK完成链仍在交接”被算成正常前提。Dispatcher计数归零本身不证明执行器任务已返回；固定`Dispatcher.kt:206–217`在移除running项后仍可能执行尾部回调。不得等待settled/quiescent或强求Calls空：目标GET正文尚未结束，真实Call应仍由原operation跟踪。
4. 这些是有意建立正常测试前提的观察，不是“零观察者效应”。满足后立即interrupt，不夹入另一段无预算的诊断；无正证据应FAIL并记见证未到达，不Assume、跳过或降级为readJson入口。正常向量仍保留原首次quiescent及其余严格断言；见证不是200ms必能完成的保证，若仍失败继续走技术门。

新增handoff向量保持原peer.entered即interrupt，不做事前栈/反射/PG观察，不等正常见证；原服务端停点仍覆盖。后续采样只能在原判定固定后做，不能用晚静止样本伪造首次原子状态。peer可能自然超时，显式释放前不等于服务端一定仍阻塞。

## 4. 两分支都须有完整安全后置

不能按q布尔简单二选一或把false OR成PASS。应匹配目标异常图中**精确IllegalStateException码**ALGORITHM_IO_STOP_NOT_CONFIRMED与同tenant/call PG码，双向一致：

- 没有显式未确认码：首次q必须true，原输入关闭/中断标志及原停止义务继续成立。泛化SDK错误不算未确认许可。
- 有显式未确认码：PG必须为目标CLEANUP_PENDING及同一码，不能READY；q仍先固定并保留。此分支在预算到期后可能已于首次检查前恢复q=true，不能反向声称它从未未确认。q=false而无码、异常/PG不一致均FAIL。

两支均应验证：目标调用没有可公开成功产物/READY、原输入已关、中断标志保持、释放前无DELETE；未确认目标清理延后且不被提前认作可清理。只释放本测试有限peer/source后，在原有界等待内证明**原**stages/Calls全部结束及**原**owned executor真正terminated，禁止用替代future、清集合或额外shutdown伪造。随后目标仍无READY、没有新的upload/readback/cleanup路由，再验证原shared store真实PUT/GET复用；暖场及复用成功不属于目标调用READY。允许已接收原请求结束，不把“无新请求”偷换为远端事务回滚。

原Test:117在首次q及PG读取之后才取路由基线，若照搬会漏掉返回后到基线之间的新请求。新handoff应在测试peer的目标停点、entered.countDown之前固化已接收路由计数/序号；主线程仍按原闩锁即interrupt，不加事前诊断。返回后及释放后与该固定基线比较，边界止于有意共享复用之前，避免晚PG采样把迟到请求吸收进基线。有限Source向量对应基线为0。

handoff不保证每轮命中未确认。现有限Source向量已确定性覆盖未确认码、retained、原future退出、最终quiescent和无网络；若要声称**完整未确认义务默认覆盖**，还应在保留该身份/原断言/退出预算的前提下，把目标PG pending/no READY、清理延后/no早删、共享store真实复用等通用后置补到它，或明确给出其它默认向量的逐项覆盖。不能把只在未必命中的handoff分支写过的断言当作确定性已执行证据；无需因此另加第六身份。

所有新测试仅操作新UUID租户/调用的独立资源；不清理或改写此次恢复的原历史行。读写、重跑和源修改目前均未获本轮执行授权，本文没有执行这些步骤。

## 5. 下一门及关闭条件

建议root先登记五身份、674预期、正文前提变更与上述分支/后置，然后作者提出仅Test的最小候选，再独立核其真实差异及静态/运行准入；保持产品9DEB、SDK、预算和原证据不变。不能继续沿用155D“仅三处诊断增量”的身份描述给新测试签收。

新测试/原Full及独立审阅完成前，原Full首次quiescent F、此前D64/after-return-only仅额外观察和EX03硬门均保留。未来若五项及完整模块通过，也须明确这是历史分支恢复后对测试前提/安全义务的修正闭环，不是ArtifactStore产品已改或旧现场全部安全细节已恢复。本文不授权现在写Test或启动实际运行。
