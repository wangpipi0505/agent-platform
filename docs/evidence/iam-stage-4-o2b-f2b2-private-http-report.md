# O2b F2b2 私有同 Session G / child / APEV 输入交还

2026-10-21；状态 DONE_WITH_CONCERNS，供 root 独立复审，尚不是 Accepted。唯一工作根为 `G:/207/项目/算法重组工具/代码/agent-platform`；所有 shell cwd 固定于此。未读取或关联相邻旧工程；没有提交、remote、生产启动、设计/契约/八执行索引修改。source、target 与本轮 owned 证据写权在 handoff/inventory 冻结后交还。

本轮只提供同一私有 Session 的 Q→G→child→私有 HTTP/F1 输入。没有 receipt 验签/材验结论、公开 cursor、SSE/browser 字节、controller/assembly 装配，不能计作完整 IAM 或全部34任务完成。

## 1. 输入、最小变化与字节闭合

依据 AGENTS.md、docs/implementation-brief.md、contracts/README.md、冻结 F2b draft F72–82、完整 gate notes、b1 budget；实际 b1 接口取限定 Accepted `.local/iam-o2b-f2b1-acceptance.json`。按本轮 root brief 覆盖候选 begin 收费建议：lazy reservation 与 consume 时同实例双接口。

- brief：12165B / SHA256 943cc0dbc71e575af6ff13ef0c0ca20f212db1b555d431ee52aeeac6b8169e74。
- notes：9993B / SHA256 6f91b44e62ae5aa4a336d3070911e90d4e70c8b7a7bbd1d2f36607f4d47149f5。
- frozen inputs：`.local/iam-o2b-f2b2-private-http-frozen-inputs.json`；源基线621与 target2226取已冻结 b1 fixes。
- root 大票澄清：`.local/iam-o2b-f2b2-test-vector-clarification.md`。普通8482B与另一个 issuer 签名前 padding 的大票分开；padding没有权限语义，完整签名/关联校验没有放宽。

只修改三份既有源码：`PortalRunEventsHttp.java`、`RunObservationMetadataClient.java`、`PortalRunEventsHttpTest.java`；新增同包 `PortalRunEventsPrivateHttpTest.java`、`PortalRunEventsPrivateAuthorityTest.java`。总621→623，其余618条名字和字节精确相同。没有生产新文件、依赖/pom/common/F1/F2a/engine/main/controller/frontend变化。

Metadata 是在 before 原字节中只插入纯 `parsePrivateEventsErrorCode(byte[])`。删除该新方法后整份文件字节还原；STATUS 前6168B以及已有 Q/EVENTS 事实/错误方法全部原字节保留。原 HttpTest 还原 before 后只替换一次 child ownedRoot 表达式，可反向精确还原整文件；原14方法和断言字节均未改变。本次原 child 均写入 `.local/iam-o2b-f2b2-private-http/peers/original-q-*`，没有回写旧 b1/fixes/peers。机械证明见 `source-closure.json`，live源码名字623见 `.local/.../source-live-name-closure.json`；旧 `.playwright-cli` 遥测不属于 source 枚举。

源身份：

- before621：118930B / SHA256 7ce27abedc333786bff2c7c886ab6092814b52118bf1fd3fd87f6a52eacf81fa。
- after623：119381B / SHA256 c2dd5086c9718098dc0957aa7f0dd3e7c809f56793d7eaea1b53e3987262c246。
- 完整 before/source-after 快照及五份各阶段可变源码均保留；所有623 epoch 的未变618均绑定 before 副本。

## 2. Session 时序、真实关联和 HTTP

begin 继续只取得 Owner/HTTP reservation/原 Opening 与 Q，另借存同一个 authority 和 original source 引用，不复制原 token。consumePrivate 及 PacketConsumer 都是包内同步接缝，不接受外来 G/child/facts，不公开裸流或 packet。consume 先要求 lookupMetadata 已返回成功且 physical Q 图实际清理：execute/handler、body/response、TLS handle、timer termination、client/manager close均退出，原引用清空，close竞争下不发布 Q_READY。只允许消费一次。

consume 在新私有图分配前 reserve 1MiB（另计128B token），再检查同一 authority 实例也实现 ResponseAuthorityClient。仍在原 Opening/Q 有效的前后检查之间，从本 Session facts 复制七字段、cursor、snapshot到私有不可注入 final record；没有 DOM/Map。随后同实例 `openGatewayPortalEvents(originalOpening,savedSnapshot,session::abort)` 恰一次，再同实例 `exchangePortalEventsChild(originalSource,sameG)` 恰一次。source在 exchange finally及总finally清除。转 G 后不再读取 Opening/Q/facts getter；facts旧 view失效。返回迟到 G 仍归该 Session 持有，取消等待超时后操作自己完成实际清理并关闭 G，不发 child/E 头。

E 使用原 run-snapshot ResourceBinding，GET固定 `/admin/runs/{run}/events?Last-Event-ID=<Long.toString(cursor)>`，精确私有 MIME、HTTP/1.1、无body，唯一真实child Bearer。TLS继续固定 origin、hostname、literalIP、CA及同一 actual request/connection/TLS session身份；executor与实际 writer两处 guard。Bearer ASCII≤16384、整 head≤32768、≤16头。没有proxy/DNS旁路/重试/redirect/压缩/cookie/auth fallback或凭据日志。未重新调查 Apache/Jackson/TLS/provider全库，只复用被冻结路径与实际本地API。

E head deadline 为 min(now+2秒、原t0+55秒、当前G remaining)，覆盖分配至完整合法 head。TLS handle 的 E hard 参数保留原 lifetime，不能把短 head 或 checked_until冻结成全流期限；head timer负责更严head界。合法完整head前核 child/G后，仅以G最新capture/live绑定、原55秒和F2a15秒idle控制read/consumer，不再续验已建立响应的短child票。每次read前后、readNext/交付与callback前后重新取得同G最新Capture检查 subject/resource/cursor/live，跨阻塞read不要求旧Capture仍最新。common自动renew继续承担G刷新，未增加common线程。generation下完整材料重验属于c1。

每Session每次只有一个timer worker、最多一个待执行task；Q timer已退出才能建E timer；head→body仅一次切换，epoch标记防旧已取消head任务误abort新body。timer只abort，不在自身join。head/body真实物理退出和timer termination之后才可退费。

## 3. 响应、F1 与退出

QuietConnection实际 parser也按阶段选profile，保留8192B总head/单行、最多63头、非法控制/折行/1xx/压缩/重复framing/CL+TE/未知定界/trailer拒绝。E200只接受精确私有MIME；唯一规范非负long CL或唯一chunked，成功CL不再套Q的8192上限，也不按CL聚合。真实测试覆盖21345B CL与chunked，读入同一个私有live InputStream并绑定F1 Decoder。

非200只读≤8192严格JSON，CL限额及chunked+1实际读取均生效。纯解析入口复用既有4键/duplicate/UTF8/BOM/trailing/长度规则，仅返回≤64 ASCII code，Session在解析前后检查G并配对：400/EVENT_CURSOR、既有403白名单、404/RUN_NOT_FOUND、429/STREAM_CAPACITY、413/EVENT_LIMIT或BUFFER_LIMIT。未知/内部401/redirect/500/错误MIME均本地503；server message/cause/DOM不返回。413/429不构成事实或重试许可。

Decoder每次仅交一个UntrustedPacket，callback返回且所有segment borrow关闭、packet.close后才读下一包；无队列/预取完整包/复制完整包。测试中的HELLO receipt `aa.bb.cc`、WINDOW等只为F1结构形状，未被当作可信receipt或材料。EOF没有运行终态断言，CAPACITY也不是事实。

关闭顺序为停止准入、abort/cancel、实际read/parse/consumer/handler退出、response/entity关闭、TLS awaitClosed、timer退出、client/manager close完成，再清引用并退b2/HTTP费用、Owner关闭。borrow未关闭时decoder.close拒绝，保留packet/decoder及费用供重试。卡住callback或真实socket close仍保留2Owner，第三在新权限/network工作前拒绝；解除后实际退出、一次释放。G.close没有common内部线程join API，本轮不声称所有common任务已物理join。

## 4. 有限费用模型

| b2本地分项 | B | 实际约束 |
|---|---:|---|
| 私有七事实/cursor/snapshot | 16384 | 小型final record及有界不可变字段引用，无Map/DOM |
| decoder/live input/同步接缝 | 32768 | 一个decoder/一个guard/一个同步packet，F1 wire/scan另计 |
| G/child引用/发送过渡 | 131072 | 借common proof；Bearer主背板沿3MiB请求项 |
| 阶段/取消/退出 | 131072 | 一个worker/一个待执行task、一次head/body切换 |
| 有限错误/退出临时对象 | 131072 | body/JSON仍属原3MiB有限parser项，外泄只短code |
| 本地有限过渡余量 | 606208 | 不允许第二HTTP图/common DOM/任意callback存储 |
| reserve参数 | 1048576 | F1另收token128 |

Q-only仍3211392B/Owner；consume后基础4260096B/Owner，两Owner8520192B。F1扫描候选峰值80036796B/Owner，消费含一borrow12919652B。实际保持一套互斥HTTP图，没有Q/E两套并存。下一c1/c2同一1MiB只计一次，候选98715562B/Owner、双197431124<201326592；c1材料/帧/parser须在下轮F1扫描前释放。

以上仅固定有限组件工程模型；TLS/JCA/provider/common内部图/JVM类与native/kernel、外部受控consumer自身分配等均非全量覆盖，不能当RSS或全进程/生产192MiB硬容量证明。

## 5. 实际验证、RED分类及最终epoch

所有验证使用工程自身JDK21、固定TLS startup flags、短tmpdir、独立新工程 `.tools/m2` 与既有offline runner，不绕过JDK检查、不读旧依赖/配置/进程。每phase先保存完整source623 manifest及五源码副本，再严格等待Maven/child实际退出并立即journal；raw、Running/XML、compiler清单、有限target均绑定各自完整epoch。源码在阶段中保持不变。日志含历史Windows classpath/deprecation诊断，未抹去。

最终命令：`node .tools/iam-o2b-f2b2-evidence.mjs stage iam-stage-o2b-f2b2-final PortalRunEventsPrivateHttpTest,PortalRunEventsPrivateAuthorityTest,PortalRunEventsHttpTest`，内部调用固定 `.tools/run-iam-module-tests.mjs` 的离线Maven runner。final实际新编译gateway20main/19test；common两段up-to-date。final属于新的完整623字节epoch，前次9+8成功属于旧epoch；不是把历史成功拼为final，也不是声称final up-to-date。final一次Running/XML为原Http14、PrivateAuthority8、PrivateHttp9，共31/0F/0E/0S。final后没有再跑行为测试；Metadata7/旧STATUS/F2a/F1/provider/PG历史均未重复。

| 阶段（均前缀iam-stage-o2b-f2b2-） | exit | 实际结果 |
|---|---:|---|
| seed-red | 1 | 编译诊断；无Running |
| seed-behavior | 1 | PortalRunEventsPrivateHttpTest:1T/0F/1E/0S |
| functional-seed-red | 1 | PortalRunEventsPrivateHttpTest:1T/1F/0E/0S |
| normal-green | 1 | 编译诊断；无Running |
| normal-green2 | 1 | PortalRunEventsPrivateHttpTest:1T/1F/0E/0S |
| large-child-green | 1 | PortalRunEventsPrivateHttpTest:1T/1F/0E/0S |
| large-child-green2 | 0 | PortalRunEventsPrivateHttpTest:1T/0F/0E/0S |
| error-wire-red | 1 | PortalRunEventsPrivateHttpTest:1T/1F/0E/0S |
| errors-green | 0 | PortalRunEventsPrivateHttpTest:2T/0F/0E/0S |
| lifecycle-red | 1 | PortalRunEventsPrivateAuthorityTest:1T/1F/0E/0S, PortalRunEventsPrivateHttpTest:1T/1F/0E/0S |
| lifecycle-green | 0 | PortalRunEventsPrivateAuthorityTest:1T/0F/0E/0S, PortalRunEventsPrivateHttpTest:1T/0F/0E/0S |
| authority-matrix | 1 | selector拒绝；无Maven/Running |
| authority-matrix2 | 0 | PortalRunEventsPrivateAuthorityTest:8T/0F/0E/0S |
| http-matrix | 0 | PortalRunEventsPrivateHttpTest:9T/0F/0E/0S |
| final | 0 | PortalRunEventsHttpTest:14T/0F/0E/0S, PortalRunEventsPrivateAuthorityTest:8T/0F/0E/0S, PortalRunEventsPrivateHttpTest:9T/0F/0E/0S |

关键真实行为RED有三项：error-wire-red真实E GET收到413 EVENT_LIMIT后错误归503，mapping-0记录实际请求bytes/状态；lifecycle-red分别证明完整合法head后TLS冻结head2秒导致body终止，以及取消移交后真实迟到G仍可currentCapture。对应focused新epoch均绿。body失败另外保留peer在Session已关闭后观测G过期的suppressed诊断，不将其误当另一个业务RED。

其余失败准确分开：缺API编译、默认拒绝seed、consumer checked-exception编译、native stderr/UTF8 wrapper诊断；两包真实消费后8482B目标长度断言；实验actor属性晚于gateway配置导致within拒绝；带+ selector被旧runner语法拒绝且没有Maven/Running。它们均不是已接收到E错误状态的业务RED。所有实际Maven/raw/XML、bounded child原字节输出与exit保留在stage catalog/peer-exits；没有回退旧产物、隐藏失败或把编译失败当guard行为。

最终真实观察（只列final，不混用历史数据）：

- 普通真实child8482B，完整E请求head8724B；签名前有限padding真实child8559B，完整head8801B。都经真实common签名/G关联，不打印token；每次同origin上Q实际退出再E两个物理连接。
- 完整head后的child在真实3332ms等待中失效，G已新generation，仍成功两次私有callback；旧head任务未中止body。
- head无完整响应2115ms中止；body真实idle15134ms中止；原t0真实55011ms中止持续送包peer，55次callback，未反射修改t0。另具名3秒G操作寿命2659ms阻断actual read。
- G撤权时actual F1 read正在等待，12ms退出，先前1次callback后没有后续callback；握手及真实writer处child到期/G撤权均零E请求bytes。
- 43个私有raw拒绝向量，headers63与head8192成功，headers64/head8193拒绝；21345B CL/chunked、完整HELLO后EOF、截断/坏commit、一包借用/F1 scan在callback前退出均验证。
- callback/borrow/close卡住不退费，两Owner占用拒第三；物理close两Owner保留8520192B后实际退出才释放。机械socket gate只延迟close，不制造proof/物理退出。

全轮60个child：(run,PID)、请求cwd、固定args、bounded原始client.out、实际waitFor和collector join均落盘，53个exit0/7个exit1，最终31个均exit0。peer内部实际worker也join。12份新344B Surefire classpath dumpstream（共4128B）和deprecation保留，非跳过测试或JDK检查。

## 6. Patch、target与交还

本轮纠正历史编排缺口：只建一个主baseline621 index，attrs `* -text -diff` 在add前，system/global/template隔离；write-tree并实际cat-file所有621 blob核bytes/SHA。独立replay只有unborn初始化库，没有index，先落盘完整before621工作树bytes/names。baseline复制五份after源码并依次add→binary diff；普通`git apply --check`与`git apply`各一次，顺序等待exit0，再完整核after623字节与名字。没有辅助replay index、没有apply后copy源码、没有commit/remote或可解析HEAD commit（git init生成的unborn symbolic HEAD文件如实存在）。详见 `patch-worktree-proof.json`及具名Git原始stdout/stderr JSON。

- raw binary patch：22844B / SHA256 46955b608f6f3a4fe707d1e83b34b4c3ad808c9d45468f07b34196832ad99c9c。
- target before：465582B / SHA256 4b7643c6b7f82ba96199f3d9b8e934089f94cbeb084e383ad13f5c6cf6ec3f53。
- 有限overlay155条：35365B / SHA256 2d6a49c4fcaeec5565bae9c04cb4a94dc10972406bae1b8d5e904fda311c6142；变化27条，合并历史logical2226→2239。
- target after：468626B / SHA256 38cd8356238f4363f10d3bbdf46660b727d10fbcbdf8cf47ad6ed1c68311bfd5。

没有复制整个target或扫m2。每phase仅沿实际compiler input/created清单、Running/XML/资源和common fixture JAR保存有限target；最终overlay按真实stage先后归属，所有class与归属阶段副本hash一致，旧逻辑条目只由历史manifest继承。原失败/诊断副本保持原样。`compilation-proof.json`与`final-epoch-compilation-binding.json`区分历史编译、最终20/19新编译和common up-to-date。

完整报告配 `handoff.json`、`own-inventory.json`、`stage-catalog.json`、`peer-exits.json`、`behavior-red.json`、`source-closure.json`、`patch-worktree-proof.json`、`target-overlay-basis.json`、`final-observations.json`。inventory覆盖本轮两个ownedRoot、具名 `.tools/iam-o2b-f2b2-*` helpers、本报告以及wrapper实际具名外部stage日志；只排除inventory自身以免循环身份。source/target/owned最终冻结后交还，root负责独立复审与八索引。

## 7. 顾虑与后续边界

- 初次Python edit helper默认GBK读UTF8失败，发生在写源码前；原始错误只在tool transcript，另保存准确observed摘要 `.local/.../control-diagnostics.json`，不冒充owned原始stderr。随后缺API编译的Maven raw、所有真实测试失败的child原始bytes均在owned内。selector拒绝也有command.json原始stderr。该历史诊断捕获限制不隐去。
- 12份classpath dumpstream/deprecation保留；Windows本地runner的历史限制没有修订或绕过。
- common G.close无内部join API，有限费用模型不含全JVM/TLS/native/common/任意consumer图。仅本工程隔离issuer/TLS端点，未跑真实gateway/engine/issuer/PG端到端、生产启动或完整容量。
- receipt/material floor/业务SHA、public cursor/SSE/Servlet assembly留给c1/c2。所有输入packet仍Untrusted；当前没有发布许可或可公开EVENTS装配。后续下一F1扫描前释放c1图的纪律仍待实现验证。

开源机制引用沿冻结draft/budget具名 Apache HttpClient5.4.4/Core5.3.4与Jackson2.19.0来源，本轮只窄读本地现有固定实现和common API，不声称重新完整审阅库/JDK/provider。实现复用了实际 QuietConnection parser/executor/writer、F2a TLS handle与F1 Decoder/Budget，未添通用网络/授权框架。
