# C2a 返回对象人口、生命周期强锚与未启动清理修正

状态：DONE_WITH_CONCERNS；作者实现与有限 artifact proof 完成，四 source、受影响 target 和本轮全部 owned/helpers 冻结交还 root。未签发 Accepted，只有 C1 继承已 Accepted；独立原 reviewer 复审与 root 增量审计尚待完成。未更新八索引/四契约/原设计，未公开 EVENTS、部署、commit、重启旧 main PID 或操作相邻旧工程。

## 范围与输入

固定 cwd 为 G:/207/项目/算法重组工具/代码/agent-platform。唯一改动既有四源码：PortalRunEventsSse.java、PortalRunEventsHttp.java、PortalEventsPacketBudget.java、PortalRunEventsSseLifecycleTest.java。source630→630，4修改，626全名/bytes/SHA exact，无新增 source。最终完整四文件逐行 package 和文本 diff 位于本报告同名目录的 four-source-package.md/four-source.diff。

正式依据为 retention-fixes brief、shared-population amendment、frozen inputs、43999B source-only analysis 最后 append，以及22531B原 review 的 F-I1/F-I2/F-M1；前述资料保持冻结。原分析的 Sse-only counter/512B及 caller-return-value exclusion 已否定，本轮未采用。root 后续两份授权 note 保留原件：.local/iam-o2b-f2c2a-retention-witness-correction-authorization.md 与 .local/iam-o2b-f2c2a-retention-join-and-test-observation-authorization.md；最后并发选举修正另有 root 消息授权。

## 实现与引用闭合

Sse.start 先验证真实 ContextValve RequestSlot、REQUEST dispatcher、同线程 bridge/current origin、原 request/response facade、socket及原 epoch，并单次 claim 此 token，然后在同一 private monitor 下从唯一1536 shared pool预留并登记 Handle；只有登记成功才用原 Http/authority/source/run/cursor 建立唯一 Session。旧接受预构造 Session 的 Sse 入口已移除，非 Sse Http.begin 保持原语义。forSse 在 owner/Session分配前单次 claimSession，State亦先单次 claimState；所有失败不可重绑/重新尝试另一个 Session/State。

一个 private Reference<?>[1536]、byte状态数组和 ReferenceQueue<Object> 共同服务真实原 delegate 的 SlotReference<Processor> 与唯一 Handle 的 HandleReference<Handle>。二者合计1536，包含 RESERVED/BURNED；无第二池/队列/线程、累计创建限制或强 referent 副本。只由自动 GC 通知、private poll、类型/index/state/reference-object identity一致返还。drain 的 reference-bearing helper完整返回后才使用 credit或解锁；所有生命周期/HTTP/close/join均在池锁外。构造/登记不确定失败永久烧槽，不手工退还；reachabilityFence 保持原 referent 至登记或整个 start 清理结束。生产无 GC、manual clear/enqueue、get/refersTo(null)退款、反射或引用导出。

State.final Handle和Session.final lifecycleAnchor均为同一个已登记对象。成功完成仅清 Handle.state并保存 after/terminal标量，State永不清 final Handle，Session永不清 lifecycleAnchor。caller长持完成 Handle、延迟 State、closed Session、inner lease及真实 awaitClosed/reap/signal 祖先栈都会保持同一名额；不以 caller 返回值免责，也不以计数器推断祖先栈已退出。

未启动 async 的已构造 State通过窄 detachUnstarted切 frame/backing、output、token、session、lease/probe等非必要引用，并断 Handle反向边；此 helper真正返回，外层才 lease.release和Session.close。真实 asyncSupported=false向量无worker、setter、onComplete，观察detach阶段Owner/lease仍收费、随后Owner回baseline；phase与代码顺序共同证明清理顺序，未声称用外部观察直接测量每一条内存指令。部分 State构造失败不发布：Frame为构造末项，无后续可抛工作；失败构造栈退回后才进入退款分支。

普通 finish仍必须真实完成、actions=0、heavy=0、slot清空及worker实际退出。唯一 finalizing选举在任何 Thread capture之前；只有 elected finisher调用原Session.abort实际唤醒private IO（不退费），该helper返回后再由awaitWorker capture/join并真正返回。其它finish仅持State/Handle等待，无Thread局部。join后再核worker/actions/heavy/slot；重图/任意probe callback helper返回后才释放lease。finally解除finalizing并notify，timeout/interrupt/失败保留费用和retry。未真实onComplete的强制connector释放仍保持两Owner，不合成完成；stop在finalizing期间不创建动作，nullable token只捕获一次后做holdsLock/abort。

Http成功 finishTransport先实际connection/timer退出、client/manager关闭，再清transport/testhook字段及guards。awaitClosed在active/lease/decoder真实退出后清material/authority/opening/facts/费用图，再关闭Owner；本轮补清materialChild、materialCheckpointForTest，避免把任意hook捕获隐为8B字段。失败路径保持原费用/重试，不将未完成transport算小尾。beginSse另先读取原opening.effectiveCursor，再reserve/new lease/publication；发布后无可抛cursor检查，避免无State/exit callback的半发布lease。

原1024/64/64、单JVM终身一个controlled protocol、固定64桥、真实socket最外锁、11 Processor委托及timeout、waiting双向原delegate键、每REQUEST/complete/recycle前失效均继承。未修改Verifier/Decoder/common/controller/assembly/frontend/POM/main或其它test。

## 逐字段与深图 ledger

allocation-ledger.json 和 actual-field-bodies.json列实际javap字段与逐项费用；actual-closed-fields-javap.txt保存实际class诊断。使用原固定Temurin21.0.12+8 profile的保守header16/ref8/array24/align8，非RSS/实测heap或任意JVM保证。最后elected-join仅改方法，无字段变动。

Handle=40B（State ref、after long、terminal/sessionAttempted/stateAttempted）；HandleReference=56B（Reference四字段及index）；State=168B（12refs、3long、15boolean、3int），两monitor32B；合计296B。Session实际336B，较原分析的320B诊断值多16B，但仍在正式384B allowance内。lease56B含outerSession、released/charge/cancellation/exit；closedOwner48B仅保留共享budget（decoder清空）；guards map56B及Object[8]88B。故实际核心880B。闭合Session/State逐字段null、lease callbacks/charge/released、Owner.decoder、guards.empty和两final锚身份已由真实祖先屏障有限观察，非任意JDK私有字段反射。

正式声明表：Handle/ref/State/monitors384 + closedSession384 + releasedLease64 + closedOwner64 + guards64 + Object[8]96 + 两次固定callback64 + 一个inertRequestSlot64 + 至多四个released Reservation/failure-token128 =1312B≤1792B。Handle两个一次性boolean、token.claimed仍落原40/64B body；新增protocol counters属于固定图128B body。没有新增重图占用480B余量。被捕获callback至多原两次，均只捕获同State；不能重新登记任意历史。

不能把Frame270374 backing、Thread、async/output/window/parser/material/transport或任意probe/caller capture算成8B小边；其原Owner费用保持至真实退出与引用helper返回。Session中仍保留的是同一释放lease、closedOwner、空guards、finalHandle及原共享TLS/config/budget。TLS采用既已接受的一次B2/installed配置归属，不宣称构造注入普遍意味着singleton；未新增per-Session TLS/config clone。

fixed gate/arrays/queue/bridge/scalars/literal逐项保守24400B <131072B；map一直IdentityHashMap<>(2)，原size≤2受同monitor保护，不生成views、不历史增长，固定Object[8]依据继承官方JDK21来源。1536*1792 +131072 +4*1792 retired-reference tails =2890752B <3145728B，baseline内余254976B。原两Owner197431124 +3145728 =200576852 <201326592，余749740；Owner acquire/reserve/release/close规则未改。baseline先收费再分配，保留到实际JVMexit。

## 实际 TDD 与各 epoch

先冻结native before630后测试。red-retained-handles沿旧生产字节、原start签名和旧1MiB baseline：1533真实factory Processor强根，加同delegate实际REQUEST Processor1和两个完成Handle；原生产第三次仍成功（failure=null），期望拒绝失败。不是新API缺失编译fail。新shared实现同向量确认第三次在Handle/State/Session/Q前拒绝，1534Processor+2完成Handle占满1536。

独立red-invalid-cursor使用真实Q和该次真实opening.close，旧beginSse半发布lease使actualClosed=false、owners1/reserved3211392；该child没有安装Protocol所以baseline0。新顺序拒绝后actualClosed=true、owners0/reserved0。未借fixture finally先回收制造GREEN；失败结果先写入properties后断言。

以下各行是独立实际source epoch，不能合并为一次“全绿”数字；命令、source.json、raw、Running/XML/testcases、compiler created/input名单、common fixtureJAR/资源和有限target均留在 stages/ 对应目录。每一child实际waitFor、有限collector及exit也保存在各peer，test-epochs-and-child-exits.json统一关联。

| stage | Maven exit | tests/fail/error/skip | child | source manifest SHA256 |
| --- | ---: | --- | ---: | --- |
| red-retained-handles | 1 | 1/1/0/0 | 1 | `853bc6f340c700a4b9f404fa2050c76f6e456232c881581f2c5e8efec8486d0d` |
| green-focused | 1 | 14/2/0/0 | 14 | `01d2513dc705584a4c566310713b4ace49bd68005eab8d4d929c3b4fb9fc9308` |
| green-cleanup-and-fixture | 1 | 10/3/0/0 | 11 | `c66755d668f7cdbc4c86acbac95b2df8e63dde3885c127c736118b687c1d3078` |
| red-invalid-cursor | 1 | 1/1/0/0 | 1 | `e8a0ba4ae73afa9c21b7e90aff3b85e1e07c2006f490cad1ab51284568996500` |
| green-join-creator-corrected-mixed | 1 | 7/1/0/0 | 8 | `082b4b425be5121db6eae30e57fbd5431fe241a3a3641216d97e18eda14ec73b` |
| green-action-fixture-order | 0 | 1/0/0/0 | 1 | `0f5f8ef2ec2be502f07402e220dfe1ff168f95d0d30849b2cb10d8e54b390a22` |
| green-elected-join | 0 | 3/0/0/0 | 4 | `6d4e023e2ab5f5f5fbbc3bd386bd967b67c67c546872ccb0ba408ca6f66c32d8` |

首次14/2fail/0error：bad-receipt bootstrap因原预构造Session夹具引用变null；ancestor先readAllBytes等待被屏障挡住的真实完成，child有限95s失败。其12测试方法通过不等于语义12全通过：mixed JUnit通过但语义INCONCLUSIVE。10项后续关闭/fixture命令在root更窄列表到达前已发起，实际10/3fail/0error/11child；firstError已修好，recycled facade探针使reuse/keepalive两个95s失败，ancestor当时只观测到abort.signal祖先而非reap。保留原件及实际退出，不抹成GREEN。

随后修fixture只捕获真实原RequestSlot，不再访问回收facade。新7/1fail/0error/8child实际证明reuse/keepalive、firstError、合法cursor关闭、corrected mixed及真正awaitClosed→reap祖先；ancestor在退款后持cell锁，lease/probe/worker/frame/transport等已断、Session/State锚同一Handle，拒绝第二Session尝试。唯一action测试在等两路backpressure时首路先失效/disarm；只改夹具为每路ready-false稳定后及时启动其close，单项1/0/0/0 GREEN，两Owner held15485324、第三Owner拒绝、真实CLOSE_NOW各1、两action线程退出。最后发现并发join helper gap，前移唯一election并唤醒原IO后，仅指定3方法（4child）全绿；未重跑7/10/14或GC。

Processor cap/last-slot race与Processor两烧槽在首次epoch分别通过：最后一槽success1/denied1、1536真实分配后拒绝；Handle构造/登记各烧1，在1534真实Processor+burn2后拒绝，无新Session/Q。合法Q403 fixture有严格ApiResponse JSON、Content-Type/length，断言原status403与RUN_AUTHORITY_DENIED；两次失败Owner归3MiB baseline后同protocol正常start。上一冻结报告所谓Q403实际是畸形framing失败，本报告准确区分，未回写旧报告。

mixed第一次先自然观察1秒，再仅1次授权GC/有限等待，实际Processor通知1、Handle0、reuse0=INCONCLUSIVE。源码定位static currentGateway→G.holder→evidence.slot→cancel(bound Session.abort)→Session.finalAnchor→Handle真实测试强根。root具名授权后，仅新fixture helper只读校验捕获的确切Session、清static观察者，helper返回并清exchange后另fresh child自然观察/至多1GC；实际两种通知各1、真实同池factory reuse2=OBSERVED。两次GC分别属明确不同byte epoch；绝无循环/第三次/生产GC/manual通知。最后并发选举修改继承已验证引用机制，未重复mixed。

## native Git、source/target与过程偏差

native primary/replay before均由继承immutable primary630复制；binary attributes在首次add前设置，全名/bytes及630实际Git blob确认。生产修改、实际测试及引用/异常自审先完成，再冻结最终after并生成4个GIT binary patch。最终plain apply --check一次、plain apply一次均exit0；无辅助replay index，无apply后copy。primary/replay最终630全名全bytes/SHA一致，4改/626 exact；各成功阶段即时cache，未重做旧replay/Git/原审计。

最终source manifest：.local/iam-o2b-f2c2a-retention-fixes/source-after.json，124057B/SHA256 `6d4e023e2ab5f5f5fbbc3bd386bd967b67c67c546872ccb0ba408ca6f66c32d8`。

最终logical target：.local/iam-o2b-f2c2a-retention-fixes/target-after.json，493693B/SHA256 `32b310dbcfc0c58858f3d4148a18b447d93b2f2b1017d8e6f050b1e050f095d8`。继承2297逻辑target，仅实际Running/compiler/fixtureJAR及source-bound3资源形成401有限overlay；新增HandleReference.class后逻辑2298。target-overlay/basis/delta均留档，不扫旧target冒充新编译。


首次复用新helper时E常量仍指旧docs fixes前缀，真实RED暂新增于旧root一个全新stage子目录（没有覆盖既有文件）；实际exit/cache后move至新owned。red-stage-relocation.json记录绝对source/dest、全部迁移payload identity及metadata prefix解析映射，旧位置最终不存在。旧inventory冻结成员未改；后续每stage在执行前assert新的O/E根。此偏差未被隐藏或通过重跑RED补记录。此前14671+8643旧owned按冻结epoch成员继承，新helpers不反向加入旧宽glob。

## 开源对照、边界与交还

原固定Tomcat10.1.41真实createProcessor/delegate waiting-key、socket lock/recycle/timeout委托及JDK21 PhantomReference/ReferenceQueue/reachabilityFence、IdentityHashMap容量源事实继承原已审计资料；本轮没有声称重新阅读完整上游源码或重新验收provider/VM/RSS/全容量。实际read/javap及四source package可复核新增机制；没有另写容器/GC framework。

长持完成Handle/Session/旧State、自动通知延迟及永久烧槽会降低shared1536可用性，可能在两Owner尚空闲时拒绝；不承诺GC期限、不隐退credit或旁路准入。工程1792B是固定profile下具名闭合图的上界，不是任意caller或整个JDK/Tomcat堆的保证。原M1日志噪音/编码/ThreadLocal opens提示和容器关闭线程警告保留；成功child实际exit/collector结束不等于证明完整容器所有线程同步join。失败child的95s超时/强制有限结束原样留档。

原全29、上一13/7/2、C1/PG/provider/旧Git/replay未重跑。此轮不构成全工程验收；C2b真实入口/跨进程链与C2c四前端消费点仍待后续。此报告、handoff.json、own-inventory.json及全部四source/affectedtarget/newowned/helpers现冻结，唯一写权明确交还root；需原reviewer复审关闭Important及root增量审计后再裁定有限C2a。
