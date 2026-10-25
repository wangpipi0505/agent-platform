# O2b F2b2：同一会话的 G / child 与私有事件输入

本任务须在 F2b1 修正经独立复审、root 有限审计和限定证书闭合后，由 root 显式派发才获得源文件写权；本文预先保存不代表当前授权。独立新工程固定根 `G:/207/项目/算法重组工具/代码/agent-platform`；所有 shell cwd 均为该根。不得读取、关联、复用或影响相邻旧工程的代码、配置、数据、依赖、进程。用户已授权按计划持续开发；没有专用联调环境，使用本新工程隔离测试端点。

## 依据与目标

先读 AGENTS.md、docs/implementation-brief.md、contracts/README.md；v1.2 和四契约不动、时间安排不在范围。Java21 / Boot3.5.0 / common+六服务分层、跨业务HTTP、既定Vue栈、默认拒绝、身份来源与凭据secret_ref要求不变。

完整读 `.local/iam-o2b-f2b2-gate-notes.md`（9993B，SHA256 6f91b44e62ae5aa4a336d3070911e90d4e70c8b7a7bbd1d2f36607f4d47149f5），以及冻结 `.local/iam-o2b-f2b-http-task-draft.md` F72–82 和 `.local/iam-o2b-f2b1-http-budget-notes.md`。本任务按下述 root 决定落实 gate 候选；不重新做固定 Apache/Jackson/TLS/provider 全库调查，实际 b1 Accepted 接口以派发时证书为准。

仅让已经取得原Q元数据的同一个私有 Session 在原锚点内移交真实G、恰一次交换绑定child，再经相同TLS约束读取私有 APEV 输入，用F1 Decoder同步交付 UntrustedPacket。没有 receipt/material 可信结论，没有浏览器输出或公开 EVENTS 装配；这些在 c1/c2。

## 最小源文件范围

before 以 root 派发证书所指 F2b1 修正后621为准；expected after623。允许修改三个既有文件，新增两个测试文件，其他618个before条目字节/名字不变：

1. 修改 `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalRunEventsHttp.java`，增加同 Session 私有输入阶段与有限退出。
2. 修改 `platform-gateway/src/main/java/cn/agentplatform/gateway/application/RunObservationMetadataClient.java`，只追加纯有界 E 错误 code 解析入口；已有 STATUS 前6168B和原Q/EVENTS事实方法行为不动，不添加外来许可工厂。
3. 修改 `platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalRunEventsHttpTest.java`，仅将 child 证据输出迁至本次 ownedRoot（可加具名测试属性以便以后复用），原14个测试方法/断言语义不动。本次新生产行为影响所有HTTP出口，允许在新完整epoch运行这些受影响14项一次；不得写回旧fixes/peers。
4. 新增同包 `PortalRunEventsPrivateHttpTest.java`。
5. 新增同包 `PortalRunEventsPrivateAuthorityTest.java`。

不改 common、TLS、F1、engine、pom、main、controller/assembly、前端、设计/契约或八执行索引。不加依赖、不创建通用授权/网络框架。若需要扩大范围，给root具体具名原因，独立工作可继续。

## Root 对候选的具体决定

- 保留原 begin/lookupMetadata/close/awaitClosed；consumePrivate 为包内同步接缝，consumer亦包内，不能公开裸流/包/任意G+child+facts组合。future c1 由Session内部拿自己的G和事实绑定验证器。
- Session 可借用保存传入 CurrentAuthorityClient 及原Portal source 的引用；不复制token。新增少量字段计入原3MiB过渡margin。**1MiB b2 reservation 延迟到 consumePrivate 进入时，在七事实私有复制/decoder/私有输入新增图分配前申请**，另128 token；原只做Q的Session仍是3211392B。因此无需改变旧14项费用预期。只在consume阶段检查同一个authority实例也实现ResponseAuthorityClient；不支持则默认拒绝，不接收第二个authority实例。此项替代候选“begin前强制双接口、begin申请b2费”的建议。
- 其余 gate §1–5 的机制与有限预算表均采用：16384+32768+131072+131072+131072+606208=1048576B；没有第二套并存HTTP图。该表是有限组件工程模型，不是全JVM/TLS/common/native容量证明。
- E仍使用RunObservationSelectors.events(run,acceptedSha)的真实run-snapshot ResourceBinding；不另造event资源kind。
- Q的事实getter依旧Opening且在transfer后失效；在仍有效的原Opening/Q前后检查之间，从本Session已读facts私有复制七字段及cursor/snapshot，private final、无DOM/Map、不可由caller注入。转G后不再调用旧Opening/Q/facts getter，事实仅在同G下使用。
- b2每次读及交付前后取同一G的最新Capture核subject/resource/initialCursor/live；跨阻塞read不要求旧Capture仍是最新。generation变更的完整材料重验由c1负责，此处不得把短checked_until当全流硬期限。

## 授权与时序不变量

Q成功的execute/handler、body/response关闭、实际TLS handle、timer termination和client/manager关闭均已退出、清旧物理引用，且没有close竞争，才发布Q_READY并允许新E图。保持3MiB直到最终退出，不能Q/E两图并存。

consume恰一次，须先Q_READY；保留原t0、5秒Opening和55秒hard锚点，不重新begin/重置时钟。用同authority的ResponseAuthorityClient.openGatewayPortalEvents(originalOpening,savedSnapshot,session::abort)恰一次得G，然后同authority.exchangePortalEventsChild(originalPortalSource,sameG)恰一次；child交换finally清source。关闭竞争时关迟到G、清child并禁止发头。G与child只在本会话内部生成并保存，字段相等不能代替真实关联。

E GET固定 `/admin/runs/{run}/events?Last-Event-ID=<Long.toString(cursor)>`，唯一Accept `application/vnd.agent-platform.portal-events.v1`，HTTP/1.1、无body、唯一真实child Bearer。继续固定同origin/hostname/literalIP/CA、TLS原handle、无DNS旁路/代理/重试/重定向/复用/压缩/cookie/auth fallback及无header/wire凭据日志。Bearer ASCII≤16384，完整请求≤32768B、≤16头；actual request在executor与writer guard中必须是同一实例、同conn/TLS/session。

E head deadline=min(now+2秒,原t0+55秒,当前G剩余)，涵盖分配/连接/握手/发头/完整head。发头与接受完整合法head前核真实child及同G current。合法head后只用G/current、15秒idle和原55秒控制后续read/consumer；不在每包重验已用于建立响应的短期child JWT。每Session最多一个timer worker、一个待执行task；head→body仅一次切换，带阶段标记防已取消旧head任务误abort新阶段。timer自身不得join自己，无新增common renew调度器。

## 响应与 F1 接入

8KiB响应总head/单行、最多63头，原先1xx/重复framing/压缩/CL+TE/折行/非法控制等拒绝继续生效，实际QuietConnection parser也须选阶段profile。E 200只允精确私有MIME，不能接受SSE/JSON成功。CL规范非负long或唯一chunked二选一；成功流可多包，不能按CL无界聚合，也不能沿用Q的8192 CL限制。拒未知connection-close定界和trailers，先abort再entity/response close，不drain未知尾部。

E非200只允有限JSON错误：body≤8192，CL上限/分块+1实际读取均执行；复用严格4键、duplicate/UTF8/trailing/字段长度规则，只返回短code，不返回DOM/服务端message/cause。Session在解析前后核同G。允许具名组合：400/EVENT_CURSOR；既有已核403权限code白名单、404/RUN_NOT_FOUND；429/STREAM_CAPACITY；413/EVENT_LIMIT或BUFFER_LIMIT；未知、401、重定向、500、错误MIME归本地503，不能转Portal401。413/429仍是失败，不赋予重试/事实许可。

actual entity stream经私有有界live guard后，同一输入实例给PortalEventsPacketDecoder(owner).readNext；不得预取/排队/复制完整包。每次仅一个UntrustedPacket同步借用，callback前后G最新current检查；callback返回且所有segment borrow关闭、packet.close后才能下一包。b2不验receipt/业务规范SHA/材料floor、不推进公开cursor；EOF不是运行终态，CAPACITY不是事实。包、Header及底层stream不得在公共API逃逸。

停止准入→abort/request.cancel→等待实际read/parse/consumer/borrow及handler退出→真实TLS关闭/timer termination/client close返回→清引用→退b2/HTTP费用→Owner关闭。未退出、borrow泄露或close卡住均保留费用/重试需要的引用，两卡住耗尽全局2Owner、第三立即拒；不能因finally/cancel/EOF先退账。G.close没有common内部线程join API，不声称已证明common全任务退出。

F1扫描期峰值候选80036796B/Owner，消费期12919652B（含一borrow）；下一c1/c2候选与本文的1MiB只能计一次，最终98715562B/Owner、双197431124<201326592。c1/帧/parser需在下一F1扫描前释放。新具体分配必须对预算表复核；超额则先报告root。所有数字排除全TLS/JVM/native/common内部图，不能当RSS或生产容量硬界。

## 真实隔离验证

关键失败路径先写真实行为RED，再实现；不把缺API/编译失败称业务RED。使用自身JDK21/固定TLSflags/短tmpdir/own m2与既有offline runner，不绕过JDK检查，不操作旧环境。新peer须支持同一固定origin上Q后实际退出再E两个物理连接；真实签名issuer生成Opening→G→child，不能伪造G/child DTO。可复用同包已有fixture/证书资产的只读方法，不能调用会写旧ownedRoot的旧child启动器。

最少覆盖：Q未完成/二次consume/外来或无双接口拒绝；正常实际大child（至少8495字节规模，记录真实JWT与完整head字节数，不打印token）及固定路由/MIME；child在握手与writer接缝过期/撤权零HTTP请求；合法head后child过期而G仍有效继续消费；G撤权/55秒或idle中止实际read、零后续callback；错误413/429及伪SSE/JSON/1xx/压缩/未知状态；完整/截断/坏commit私有包、一包借用/无预取；卡住callback/borrow/close未退费、第三拒绝和解除后一次释放；并发取消与移交迟到G的退出。机械barrier只控制I/O/执行时点，不制造valid proof或物理退出。

对固定55秒不通过反射修改t0冒充实际时钟正例；可用具名控制较短G硬期限测试其更严边界，完整55秒如需真实等待一次可行，记录实际而不声称未跑项。bounded child输出、请求cwd、(run,PID)、实际waitFor和collector join必须保存。

受影响最终矩阵：本次两个新测试类 + 原HttpTest14（仅本次新完整epoch跑一次）；Metadata7/旧STATUS/F2a/F1/provider/PG历史不重复。每阶段selector、Running/XML、raw和完整source623 epoch绑定，失败原样保留。每成功phase立即journal/cache；metadata失败续未完成部分，不重跑已成功同字节阶段。不预报测试数。

## 证据与交还

ownedRoot仅 `.local/iam-o2b-f2b2-private-http/`、`docs/evidence/iam-stage-4-o2b-f2b2-private-http/`、具名`.tools/iam-o2b-f2b2-*`、本报告及wrapper实际具名外部日志。原b1/fixes证据全部冻结；新source epoch每阶段完整manifest，五个可变/新增源码副本，其他618继承before。最终完整before621/after623 snapshot、finite target overlay与历史logical继承，不复制全target/扫m2。

新的私有Git raw binarypatch必须 attrs `* -text -diff` 在add前、system/global/template隔离。依次完成before baseline621实际tree/blob核对、replay before621工作树bytes/names留档、首次plain apply --check/apply各一次、完整after623字节与名字闭合；所有依赖严格await，不能把git add与apply并行。不需要第二个辅助replay-index；没有HEAD/remote，不提交。不能apply后补copy源码。

完整报告 `docs/evidence/iam-stage-4-o2b-f2b2-private-http-report.md`，配 handoff.json、own-inventory.json、stage catalog、实际退出、source/target/patch及有限引用对照。真实新编译与up-to-date final区分；原失败/诊断不改。冻结source/target/owned交还后返回DONE/DONE_WITH_CONCERNS/NEEDS_CONTEXT/BLOCKED及短摘要。root负责独立复审和八执行索引。未装配、公开EVENTS/SSE、真实gateway/engine/issuer/PG全链、生产启动与全容量均留待后续，不声明完整IAM或全34任务完成。
