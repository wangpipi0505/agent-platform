# O2a C：真实 engine PORTAL EVENTS 作者报告

状态 **DONE_WITH_CONCERNS，作者完成，尚非 Accepted**。身份 /root/iam_o2a_c_events。唯一工作目录 G:/207/项目/算法重组工具/代码/agent-platform。所有源码与有限 target 写权在封存交还前由本作者独占；无 commit、无生产发布、无相邻旧工程访问。最终精确六类 **29 tests / 0 failures / 0 errors / 0 skipped**；最终绿后没有源码修改，也没有为收口重复成功测试、PG或重放。

## 正式输入与范围

正式 brief 17339B / SHA256 90c7a208beaa3f4f0939cecd6d2d16fdb8fe1c092697f9f8cfa7f1d3a609862a；retention 风险15320B / SHA256 5620f6d7fe5671d06011d4bd86fabcb6c746fedfe5ca11b6fc6e039892005b0e；preparation28698B / SHA256 f8909ee34be436552ae1b6bb457ea7965f53371d8f5fa017902dc427ea11272e；排队顺序补充裁定1989B / SHA256 9e8c7f036a734dab7a707cfcb4ff798c24303c9afcd90871b94bfa959e352c91。当前物理身份另存 inputs.json。AGENTS、implementation-brief、contracts README及具名trace保留条款已读；按TDD、verification、ponytail、Java技能实施，失败定位采用systematic-debugging。

仅8个批准生产文件和6个批准测试/fixture（合计delta14）。没有改变A生命周期/CurrentAuthority、POM、catalog、迁移、EngineApplication、SecurityConfiguration、Gateway、执行writer或其它旧测试。新service和plain lite @Bean assembly均无可正常扫描的stereotype，只在独立受控receiver显式import。真实core RunService决定IAM分流；保留events(String,String,Long)与Controller四参构造，legacy路径由原RunEventResponseTest两项回归验证。

## 实际接通的安全与读取边界

真实链为签名E交换→trusted decoder/JWKS→serviceSecurity→全映射ServiceAuthorization与RouteCoverageVerifier→生产RunController→PortalRunEventsService→同一RunEventReader→真实PG。service opening和ServiceAuthorization使用同一个真实HttpCurrentAuthorityClient。实际HTTP receiver拥有core RunService、真实DataSource/Controller/reader；scheduler启动由测试明确静止，producer直接调用真实受保护RunAuthorityStore/TraceStore API，不把Actor/公开DTO/fake proof当授权。

run-snapshot与STATUS/EVENTS双向配对；EVENTS只接受engine/gateway/delegated/PORTAL/PORTAL与events(publicRunId,acceptedSnapshotSha)。Q/执行POST保持原互斥。IAM入口在Actor/store.run/裸SQL之前分流；缺service、错resource、debug/v1/HEAD/其它method、未知query/body、缺实际handler/frame、真实FORWARD均关闭。HTTP验证unknown与别的owner为404，payload getter0；没有把这些默认关闭降为legacy查询。query存在时只验证恰一个query值并优先于header，否则header至多一个；ASCII1..19位且0..Long.MAX_VALUE，允许前导0，拒重复选中值、空白/符号/空/Unicode数字/溢出。逻辑cursor绑定与既有authority原始HTTP request SHA没有混用。

C readWindow在同reader原2permits、同账本、独立readOnly REPEATABLE_READ窗口内完成control/三根、最新state、下一可选event的联合材料计划。state完整11列为id/public_id/app_code/status/event_seq/stop_reason/started_at/ended_at/mode/trigger_type/output_ref；UTC微秒投影与writer一致，核完整row摘要。scope按revision/hash去重，state/event的直接父并集共用预算，每个父完整parent数组仍计费。scope/owner/initial/floor/locator/本轮current均先于业务payload；state1MiB额外交集纳入原root8MiB，不另享额度。min(seq)仅在完整材料/state核验后从同RR取得，不伪装signed MaterialRef。真实并发终态writer在本轮不可见，下轮完整窗口才见新event/state/watermark。

原metadata8MiB、root8MiB、scope/initial/完整row1MiB、control256KiB、4096 records/直接父、event公开重编码262144、原parser depth/token/string/number和model64MiB/canonical8MiB/all-live192MiB均保留；两个owner最多384MiB是保守已计费表示界，不是精确JVM堆测量。state行预检与CASE后取text，state加入root交集的超限负例在state_text前失败。每次statement_timeout沿原保守锚点floor(ms)、不足1ms不SQL/不设0、真实commit返回后再查活性mint。网络不进入RR，当前线程nonrecycler清理及原B一次borrow/output重入/copy账本隔离保持。

readNext原签名与empty非window语义保持。新WindowEmission私有构造、one-shot同步consume，内部事实不由可公开构造DTO建立权限；active close只标停，实际finally才归还permit。保留窗口reset只消费完整核验window：after>watermark AHEAD；oldest非空且after<oldest-1 EXPIRED；空表且after<watermark EXPIRED。terminal仅SUCCEEDED/FAILED/CANCELLED且排空才退出；非终态empty退出本轮再有限poll，不持RR/permit睡眠。

## 终态保留例外与明确缺口

仅新窗口中由实际完整child父集合指定的历史非消费run-event父，才可把物理行缺失记为候选；仍核同run immutable scope、可信producer原绑定、规范正long locator、完整scope摘要/owner/initial/floor/current及预算。有物理行属于别的run仍拒。只有完整真实state摘要最终证明terminal才接受这些候选；当前选中event消费优先，必须存在并读完整row核摘要。非event/缺scope/错revision/hash/current不放宽；没有全局ignoreMissing开关，也不把absence当清理许可。

测试由真实writer完成FAILED后，仅删除其测试独占run的event物理行；scope/control/state不改、不禁trigger、不造receipt。部分与全量清理后的expired/ahead/空表watermark/排空已测，原B readNext对仍消费的依赖缺行保持拒绝。这个测试证明清理后真实PG事实的观察语义，**没有生产retention job、活跃run清理可继续、writer/replay/去重全平台兼容的证明**；这些gap原样保留，未偷偷修writer或重写历史。

## 原generation、真实Servlet与容量所有权

PortalEventsResponse仅新增本地isSuperseded：同锁先requireCurrent，再同holder/evidence owner；旧generation true，同代同evidence false，null/foreign/future拒绝。仅明确capture活性guard失败后，才将healthy replacement分类为Superseded；material/digest错误不catch一般403重试。旧结果实际close/归还后，同一读轮最多2次完整RR重读；成功完整窗口开始下一有限读轮，原after/首次hard bound不变。测试同时覆盖3次不同成功cursor轮续核继续、单轮3次supersession安全关闭，没有复活旧proof。

IAM成功接管后Controller return null，使用专用Servlet async task，未借StreamingResponseBody框架附加getBody/flush。默认ThreadPoolExecutor2线程+2有界队列。实际编码、getOutputStream获取及返回后终检、write前后/flush前后、可能阻塞的AsyncContext.complete都归一个原子owner。OPENING调用实际退出才释放；PENDING只有CAS确认neverrun才释放，迟到run不执行；RUNNING的timeout/onComplete/close/shutdown只stop，真实getter/write/flush/complete仍阻塞时Bpermit/Cslot/frame继续占用，逐实际返回恢复。没有把Future.cancel/Servlet通知当物理退出，也没有复制AuthorityContext/Retained/Actor到async。

按补充裁定，opening在原真实frame先预留C frame并完成首个完整保护窗口，然后同步丢弃B结果/归还frame；queued只持小owner/slot/holder/原cursor。RUNNING重新reserve frame+fresh capture完整RR，预检业务字段未作异步输出proof。两个真实active poller和两个queued owner最终均观察真实新terminal，没有排队占B导致的循环等待。另有实际队列neverrun/拒绝、listener安装失败和一次释放验证。

Servlet timeout固定55000ms，没有误把Capture.remainingNanos的短checkedUntil当连接寿命；A原first55秒、源与holder仍是权限终界。真实短票exp=2秒后原response以真实client续核继续；另在同原响应18次每次3秒可信clock推进和真实签名HTTP续核后，累计54再+2仍关闭且旧getter返回后0业务write。这里没有等待55秒墙钟或声称socket被强制断开。实际TCP断开后原run仍PENDING且真实producer可继续append，观察关闭没有cancel后台run。

ChunkBus新slot-only lease复用原slots且不分配queue32，frame复用原16MiB responseBytes；旧subscribe/reserveResponse语义保留。完整frame单一精确backing262245B，另预留8192B固定header/control/对象scratch，合计270437B先于reader/分配保留。B临时payload copy仍记B原ledger，独立C完整frame记C池；无几何BAOS/toByteArray，无裸CR/LF payload注入，type限制ASCII[A-Za-z_]{1,64}。旧response reservation与新frame共同计入16MiB，正常空池62帧；两个真实阻塞writer保留2帧时60、逐退出61/62已测。pure FLOW stream_status明确chunkAvailable=false/chunkReplay=false。

机械encoder验证19位Long.MAX_VALUE、64字符type、262144payload，完整262245正好/+1拒绝。**真实PG event_seq为int、type为varchar(32)**；最初真实writer64字符type按PG失败，后真实HTTP用32字符type和262144/+1 UTF8 payload（含中文），没有改schema或伪造state/history使极端ID可达。实际wire保持旧id/type/payload与recovery字段、Last-Event-ID续读、大整数及Unicode；escaping沿原B重新编码与C原样byte copy/CRLF guard，不把机械最大ID当实际PG事件。

已提交且holder仍live的event容量返回固定EVENT_LIMIT reset；root/state容量固定BUFFER_LIMIT。未知材料拒绝不伪装容量，不输出exception细节；失效holder只关闭。真实队列拒绝曾错误返回403：async取得所有权后抛回MVC触发受保护/error再分派。现由原owner写固定未提交429/STREAM_CAPACITY信封，实际complete返回后释放；listener失败同理500/STREAM_UNAVAILABLE。该失败及修复均保留focused XML/raw。容器timeout已自行完成后，重复complete可能IllegalState；这不提前释放仍阻塞应用owner，应用实际调用返回/抛出才到finally。

## RED、修复与最终精确验证

最初PG在真实seed后因缺readWindow失败；真实HTTP mapped入口在期望200/CURSOR_AHEAD时实际403，随后才开启purpose/安全service链。早期若干HTTP是DataSource/Flyway/actuator route coverage初始化失败，明确不算功能RED。没有把compile/setup错当成功或把它们倒填为业务负例。

**早期归档concern**：最早red/http-red/http-red-route/http-red-mapped/first-green只保留实际raw，未在后续测试覆盖前复制该次Surefire XML或完整source快照。test-results.json明确此缺口，不构造假XML/伪称旧epoch，也未重跑已成功first-green。后续每个focused/最终阶段均在命令前保存source-epoch，实际Running对应XML/txt即时复制，连同command/cwd/exit写journal；最终全量适用六类有完整实际XML与同一final epoch。

- `focused-window`：exit 0；Running PortalRunEventsHttpTest, PortalRunEventWindowPostgresTest；source epoch `41bc08417d25b17eacf4b5425a2263f4fba21fc42e84656a6e2f45cfca3b2344`。
- `focused-lifecycle`：exit 1；Running ；source epoch `04663fd0bbfd670eb371571221d949fa5aded2e67a1ef80357025c93aa949cf7`。
- `focused-lifecycle-compilefix`：exit 1；Running PortalRunEventsCapacityTest, PortalRunEventsLifecycleTest；source epoch `28ed568c9cf06973c0e37ed4557352fc022c2401e9516c5297e2b166b272ce89`。
- `focused-physical-fixes`：exit 1；Running PortalRunEventsCapacityTest, PortalRunEventsLifecycleTest；source epoch `d97d495670f3fb34b5294b49e77e9f3df266b4cb9eeaec44aae96ce96cb30f55`。
- `focused-remaining`：exit 0；Running PortalRunEventsCapacityTest, PortalRunEventsHttpTest, PortalRunEventsLifecycleTest；source epoch `586cdf1ff1d9c8e42f25714024e4d19dc7993856d6cd382c1558586511277296`。
- `focused-renew-and-queue`：exit 1；Running PortalRunEventsHttpTest, PortalRunEventsLifecycleTest；source epoch `bf3678a56a8c61017fa46ec560c91326d6d588a291a91f6dea0ab9b324445e75`。
- `focused-active-queue-diagnosis`：exit 0；Running PortalRunEventsLifecycleTest；source epoch `41b27dda0a35cc6b7a27b9dca4e24df38ec09920598a5e721ee74d1e57ace12b`。
- `final`：exit 0；Running PortalEventsReceiverClosedTest, PortalRunEventsCapacityTest, PortalRunEventsHttpTest, PortalRunEventsLifecycleTest, PortalRunEventWindowPostgresTest, RunEventResponseTest；source epoch `252050fd62edafca014750183e6c17b56ce97ac3fc7e71077a253fe70a69b1c5`。

唯一最终命令（runner未改）：

```text
node .tools/run-iam-module-tests.mjs iam-stage-o2a-c-events-final engine-runtime PortalEventsReceiverClosedTest,PortalRunEventWindowPostgresTest,PortalRunEventsHttpTest,PortalRunEventsLifecycleTest,PortalRunEventsCapacityTest,RunEventResponseTest
```

cwd固定工程；JDK21.0.12+8、Boot3.5.0/Spring6.2.7/Security6.5.0/Jackson2.19.0/Tomcat10.1.41；PG127.0.0.1:25432/agent_platform_iam_o2a_c_events_v12为独立C库，19项Flyway验证、schema217。仅fixture自有terminal run发生测试维护DELETE，其它原A/B/Initial DB不碰。旧B全类/65、Initial、所有后端均未重跑。compiler重编输入多于测试Running，不冒称运行所有编译类。

| actual Running class | tests | failures | errors | skipped |
| --- | ---: | ---: | ---: | ---: |
| PortalEventsReceiverClosedTest | 2 | 0 | 0 | 0 |
| PortalRunEventsCapacityTest | 4 | 0 | 0 | 0 |
| PortalRunEventsHttpTest | 6 | 0 | 0 | 0 |
| PortalRunEventsLifecycleTest | 9 | 0 | 0 | 0 |
| PortalRunEventWindowPostgresTest | 6 | 0 | 0 | 0 |
| RunEventResponseTest | 2 | 0 | 0 | 0 |

最终source epoch manifest SHA256 **252050fd62edafca014750183e6c17b56ce97ac3fc7e71077a253fe70a69b1c5**；final原始文件 .local/iam-o2a-c-events/final/source-epoch.json。raw为同目录raw.log，XML/txt为同目录以admin-common-/engine-runtime-前缀保存的实际报告；final/result.json保存原路径/bytes/SHA与actual Running。全部29个实际XML方法名在test-results.json，不以源文件方法顺序代替执行事实。

原runner固定输出的本任务临时raw已一次原样移到runner-raw子目录，映射在runner-relocations.json；未更改runner或重做测试。最终raw有真实timeout/error后的/error默认拒绝堆栈，以及原unit回归触发Mockito/ByteBuddy动态agent与JDK CDS提示、已有deprecation/unchecked/path乱码；不是零工具噪声。13个新dumpstream各344B，单独归档diagnostics/，不纳逻辑target。没有为消除提示改POM/安全路由或重跑绿阶段。

## 固定版本实际primary source对照

使用工程实际.tools/m2具名6个jar记录bytes/SHA到primary/actual-jars.json；实际JDK21 javap对Spring6.2.7 return-handler/task与ServletServerHttpResponse留存字节码。该版本handleReturnValue对null仅setRequestHandled返回；ResponseEntity.ok(null)分支会flush；常规StreamingResponseBody任务提交前getBody实际getOutputStream，callback后还flush。因此C由Servlet专用owner覆盖这些调用的物理退出。来源：[Spring6.2.7源码](https://raw.githubusercontent.com/spring-projects/spring-framework/v6.2.7/spring-webmvc/src/main/java/org/springframework/web/servlet/mvc/method/annotation/StreamingResponseBodyReturnValueHandler.java)。

有限worker/queue与AbortPolicy对照[Java21 ThreadPoolExecutor官方文档](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html)。单RR看不见之后合法提交对照[PG16事务隔离文档](https://www.postgresql.org/docs/16/transaction-iso.html)，并以上述真实PG并发测试验证。固定版本bytecode/真实Servlet/真实PG作为实际证据，不把仅阅读文档当集成通过。

## 有限source/target与首次私有原始重放

Accepted source-before593、逻辑target-before2060从Initial已Approved基线直接继承。仅delta14重新计算当前bytes/SHA并对齐最终执行epoch；新增7份使source600，其余586份原记录不全树扫描重审。before/after各自精确保存，自有after冻结。生产8+测试6如下。

| file | delta | bytes | SHA256 |
| --- | --- | ---: | --- |
| `admin-common/src/main/java/cn/agentplatform/common/authority/RunObservationSelectors.java` | 修改 | 3871 | `d388f05d0777b37703d4ae18401d11cd047cbd2680c1dcadbde15b3f1db76da9` |
| `admin-common/src/main/java/cn/agentplatform/common/authority/PortalEventsResponse.java` | 修改 | 7308 | `ca571762f831d5994ef15d509abf067271459955833109d3c2f5db5aef143ea5` |
| `engine-runtime/src/main/java/cn/agentplatform/engine/api/RunController.java` | 修改 | 28042 | `5311b0bcb7edef3de431ca25a2c8a29fbea60e525fd7c2a3ea84d42e462d801d` |
| `engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunEventReader.java` | 修改 | 34378 | `ec4f810e9f1673c47fb7208765b8a3b9386613fe35117fd50f66449fe83c40ab` |
| `engine-runtime/src/main/java/cn/agentplatform/engine/infra/MaterialScopeStore.java` | 修改 | 45169 | `dc591fc5c1db60acc006b4e0b7cd9b5b1d80589e2f1a016da3e1a8a9e856fe6a` |
| `engine-runtime/src/main/java/cn/agentplatform/engine/infra/ChunkBus.java` | 修改 | 8699 | `1b1635b154e76f78b64d211c6c306be57e0aaa71410d820ac74915b255646b97` |
| `admin-common/src/test/java/cn/agentplatform/common/authority/PortalEventsReceiverClosedTest.java` | 修改 | 2254 | `aafa73ef3a0c812e427d51bbc7f18793b993f9cb45f87d70c5468b4d03a6b7bf` |
| `engine-runtime/src/main/java/cn/agentplatform/engine/application/PortalRunEventsService.java` | 新增 | 17663 | `f986c6fcf6e40495c462484b4d51f8a0e61a8d5c219138560e31469494f86192` |
| `engine-runtime/src/main/java/cn/agentplatform/engine/EnginePortalEventsAssembly.java` | 新增 | 780 | `8dbbd661ef5f2e265e375314725ec83ce855c4cc04b2b792e5ff1373fd1ee1e6` |
| `engine-runtime/src/test/java/cn/agentplatform/common/authority/PortalRunEventsAuthorityFixture.java` | 新增 | 1230 | `7a6b4169ecd1f923ed4a2fcc29354c4e9b6c0b87b32a4144790d8c3576c2e532` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/PortalRunEventWindowPostgresTest.java` | 新增 | 17365 | `57e2f6ff44a816028ef6e7d5dc4c866f6715069cf71944313e4341831dab188b` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/PortalRunEventsHttpTest.java` | 新增 | 19245 | `6668bc79cf3b3a002e6474021c0fae6fbbddccee8763fe5e2c01de55bfdf46dc` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/PortalRunEventsLifecycleTest.java` | 新增 | 17566 | `5d8afd7b4d6f0563f4a0541dadae8cab720aa9b92396ba2ec4ddae98b36e6439` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/PortalRunEventsCapacityTest.java` | 新增 | 5136 | `f24188ac77b57ebf47c91aeb5487138f5fe061f73d8a18c1bddcb2c51090ec35` |

有限target只来自admin-common/engine-runtime四compiler阶段createdFiles/inputFiles、具名manifest资源、common test-fixture JAR和最终actual Running6类XML/txt。overlay498，逻辑target2096，保存到target-overlay；未扫描旧大target树，未删除/重封存旧A/B/Initial。diagnostic独立13份。

私有原始replay baseline593按Initial frozen private-replay逐具名manifest复制到本任务新目录，原记录直接继承；只在本任务私有Git init/index，无commit、不动工程Git配置。生成14文件binary/full-index patch后恢复before并移除新文件，首次真实git apply --check exit0、首次真实git apply exit0。apply后没有源码copy，私有600项文件名/bytes/SHA全部与source-after一致。replay prepare进程尚未结束时曾有4个后继helper前置断言退出，它们没有执行git apply/check；成功prepare/check/apply各仅物理执行一次，journal即刻落盘，后续收口复用缓存。

- `.local/iam-o2a-c-events/source-before.json`：112856B / SHA256 `e9791749f842e9403604f5fc3ff354e596536746577c65eb7b610374d7d69d16`。
- `.local/iam-o2a-c-events/source-after.json`：114375B / SHA256 `4bf838da425c655524614fa60fbe9eeba9d3cb892d1dd7347899d9cb1ac8cd3e`。
- `docs/evidence/iam-stage-4-o2a-c-events/source-delta.json`：6535B / SHA256 `0db3a9d89b5a252ef66e52ebdc8ba09d3df8711f5a665ca03c6e00ee71671493`。
- `.local/iam-o2a-c-events/target-before.json`：427782B / SHA256 `9dd29bc3bd986d324a94dd100fa1c30e46518c715c0215aba555e8a644c4e98d`。
- `.local/iam-o2a-c-events/target-overlay.json`：109802B / SHA256 `a8bdaafe0ab275ac1f0592e4a2d2de7feb66d5d43c4ae847fc9155c71b50bb34`。
- `.local/iam-o2a-c-events/target-after.json`：435939B / SHA256 `e738c822ad54067e44ce505c53c8810f84f445a554c9607914bf2c3287a7e198`。
- `docs/evidence/iam-stage-4-o2a-c-events/test-results.json`：38408B / SHA256 `230d20314d15479eedefdd252dc71b5590bdb6359ed998ed9bcede1e3537e731`。
- `.local/iam-o2a-c-events/replay-source.json`：114375B / SHA256 `4bf838da425c655524614fa60fbe9eeba9d3cb892d1dd7347899d9cb1ac8cd3e`。
- `docs/evidence/iam-stage-4-o2a-c-events/source.patch`：122360B / SHA256 `352e0d3c00e8bf79fae751f6f4bdf62e118d63f0ab8bccf6242461529b5d57a5`。
- `docs/evidence/iam-stage-4-o2a-c-events/replay-commands.json`：133005B / SHA256 `e059cf51be29ccaabeaf071cbab409f860c82bd9244a495bdfa3fcb5ba678364`。

## 交还、冻结与限制

report/handoff/own-inventory封存后即归还source/target写权，不再修改。inventory仅self排除own-inventory本身，覆盖本任务local/evidence/report及精确helper名单，包含private Git真实叶子、journal和hash-cache。handoff给出实际final阶段、epoch、source/target/overlay、raw/XML、patch/replay与上述concerns，作者完成仍需独立review/root有限审计。

范围仅engine一跳PORTAL/pure FLOW。Gateway/O2b、CHAT/chunk/skill/model、生产全IAM/retention job、真实生产配额、socket物理强断/撤权SLA均未宣称完成。已进入底层getter/write/flush/complete的阻塞或已接受字节不可追回；本切片证明应用实际调用及资源finally顺序，不能证明所有socket尾部字节已送达。保留早期XML/source快照缺口与诊断噪声，不把29绿扩大成所有精确业务输入或原所有后端回归均已覆盖。
