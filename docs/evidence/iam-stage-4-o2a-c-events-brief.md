# O2a C：受保护运行窗口与真实engine事件流正式任务

Initial单文件迁移已独立Approved且root有限审计/8索引闭环。本文件现为正式任务要求；C分派后只持本文列出的源码及自有有限target写权。唯一workdir `G:/207/项目/算法重组工具/代码/agent-platform`；任何shell显式设置；不访问相邻旧工程，不改v1.2/四契约、不commit、不动main全量部署。沿用Java21/Boot3.5.0/Spring6.2.7/Security6.5/Jackson2.19及现有依赖。应用TDD/verification/ponytail；关键不变量写清注释，固定版本实际primary source对照记录到自有证据。

## 任务及授权范围

把真实 `ServiceAuthorization → RunController.events → PortalRunEventsService → RunEventReader` 的engine一跳PORTAL EVENTS接通，原真实客户端与A holder续核、PG材料、游标、有限容量和Servlet实际退出同时闭环。不是gateway/O2b、CHAT、chunk、skill/model或生产全IAM。common purpose不与安全handler拆开验收。

唯一生产写范围（8个）：

1. `admin-common/src/main/java/cn/agentplatform/common/authority/RunObservationSelectors.java`：固定EVENTS purpose，原STATUS/Q/执行规则保持。
2. 同目录 `PortalEventsResponse.java`：只加下述本地superseded查询，不改A生命周期。
3. `engine-runtime/src/main/java/cn/agentplatform/engine/api/RunController.java`：IAM admin EVENTS分流，legacy保持。
4. `engine-runtime/src/main/java/cn/agentplatform/engine/application/PortalRunEventsService.java`（新）：开response/游标/单owner/有限writer/framing。
5. `engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunEventReader.java`：同RR窗口+可选事件；保留原readNext签名与严格语义。
6. 同目录 `MaterialScopeStore.java`：仅固定state有界投影/历史非消费event父的包内helper。
7. 同目录 `ChunkBus.java`：共享slot-only lease和完整frame计费，旧订阅API语义不变。
8. `engine-runtime/src/main/java/cn/agentplatform/engine/EnginePortalEventsAssembly.java`（新）：无@Component/@Configuration等可被正常扫描的stereotype，plain lite @Bean类只由受控receiver显式import。

测试范围：修改common `PortalEventsReceiverClosedTest.java` 的阶段断言；新增engine `PortalRunEventWindowPostgresTest.java`、`PortalRunEventsHttpTest.java`、`PortalRunEventsLifecycleTest.java`、`PortalRunEventsCapacityTest.java`；必要新fixture仅engine test的 `cn/agentplatform/common/authority/PortalRunEventsAuthorityFixture.java`。可以复用现有B fixture实际API，不改旧B测试/fixture。若需要其它源码、额外测试helper/POM/迁移/通用filter/interceptor/EngineApplication/SecurityConfiguration/catalog/client变更，先以具体失败路径向root报告，不自行扩范围。每个大类超过单一职责及时报告，不擅自发明新框架。

## 固定权限与真实入口

`requirePurpose` 双向限定run-snapshot ↔ STATUS|EVENTS，EVENTS只允许target engine、caller gateway、mode delegated、source PORTAL、origin PORTAL，resource严格为 `events(publicRunId,acceptedSnapshotSha)`；不能以ANY/一般run资源绕过，Q和执行POST仍保持原互斥。真实admin GET `/admin/runs/{id}/events` 才进新服务；debug/v1/HEAD/其它method、错误path/query/body/resource/缺真实HandlerMethod/dispatch/frame均拒绝。按原RunService.iamEnabled在Actor/store.run/裸SQL前分流；缺安全service也拒绝，不能fallback旧查询，即使run无run_authority。

保留events(String,String,Long)签名和四参Controller构造，避免无依据扩大legacy改动；新服务用窄optional注入，实际HTTP有core RunService且IAM mode成立时missing新service必须payload SQL0。新service/assembly不能正常component scan自动开口。opening依赖唯一同一个真实HttpCurrentAuthorityClient，亦为ServiceAuthorization实际client；真实trusted decoder/serviceSecurity filter/mapped interceptor/runtime route coverage均显式装在受控独立receiver。不能public boolean ready假装安全，不能复制旧AuthorityContext/Retained/Actor到async线程。

游标选择从真实request严格取值：query Last-Event-ID存在必须恰1个且有效，优先header；否则header至多1个且有效，均无为0。只验证选中来源，重复选中值即400；未知query key拒绝。格式ASCII `[0-9]{1,19}`，允许前导0，值0..Long.MAX_VALUE；负号/+、空白/空、Unicode数字、溢出拒绝。logical initial cursor为规范Long数值；原始HTTP request SHA保持既有协议语义，二者不能混淆。开holder必须在真实原frame，先request/readiness/有限slot准入；所有在线调用在RR外；首个保护窗口成功后才能准备SSE success。unknown/他人run404；内部authority故障不伪装Portal401。

## 同一RR的完整窗口和材料

不得另查裸status/event_seq/min(seq)拼结果。C在同一RunEventReader的独立readOnly RR内，使用同一原2permits及账本，私有mint WindowEmission（名字可按职责调整）和guarded synchronous consume；内部state/window/optionalevent不能从公开可构造proof DTO取得。原B EventEmission/借用copy隔离、共同输出重入guard、active close至实际finally全部保持；B readNext empty不提供terminal/window。

完整state固定投影等同writer：`id,public_id,app_code,status,event_seq,stop_reason,started_at,ended_at,mode,trigger_type,output_ref`。时间遵循现有projection UTC微秒规则，不只hash两个scalar。选择最新state revision，完整scope+精确直接父、scope digest/owner/initial/floor/locator/current cover先于payload；state/event依赖并集按revision/hash去重，父各自完整parent数组都计。state完整row新增1MiB cap，同时纳原root8MiB aggregate（额外交集，不放宽原上限）；读取前probe、CASE再取text，root parser限制不变。minSeq为同RR权威PG保留聚合，仅在state/root/owner完整校验后使用，不声称独立signed MaterialRef。

所有原限制同时成立：metadata合计8MiB（含control）、root合计8MiB、scope/initial/完整row各1MiB、control256KiB、records4096/单scope直接父4096、公开重新编码event256KiB；metadata parser depth32/tokens65536/string-name65536/number1000，root depth64/tokens262144/string-name1048576/number1000；model64MiB/canonical8MiB/all-live192MiB/workspace，2owner384MiB模型，分配前计费。独立timeout floor/不足1ms不SQL/不设0、commit真实返回及mint前检查、nonrecycler/current线程清理继续保持；禁止在RR内authority网络。state不能另享一份预算。

有限retention例外必须严格按 `.local/iam-o2a-c-retention-risk.md`（15320B/SHA5620f6d7fe5671d06011d4bd86fabcb6c746fedfe5ca11b6fc6e039892005b0e，85内容行EOF）理解，原B入口不变：仅新的私有窗口里、完整真实state digest证明为SUCCEEDED/FAILED/CANCELLED后，**只作为历史非消费直接父的event**可缺物理event行。它仍由child真实完整父集合指定，同run immutable PG scope行、producer原绑定、规范正long locator、完整digest/owner/initial/floor/本轮current全部校验；有物理行但属其他run仍拒。当前选中event（哪怕也为state父）按消费用途优先，必须存在并读完整row核digest；非event父/缺scope/错revision/hash/current不放宽。缺行probe可先记候选，terminal与完整payload证明之前不得据此接受窗口。不提供通用ignoreMissing开关，不把absence造为清理许可。

terminal独占测试run可以在真实writer完成后，仅删除它的event物理行模拟部分/全量保留清理；scope/control/state不改、不造receipt、不禁trigger。明确该测试证明清理后PG事实的观察语义，不证明真实production retention job或active run清理可继续；这些gap必须报告/台账保留，不能偷偷修writer/删除历史。

游标：after>watermark AHEAD；oldest非空且after<oldest-1 EXPIRED；oldest为空且after<watermark EXPIRED。SUCCEEDED/FAILED/CANCELLED且after>=watermark才排空退出；有积压先输出，非终态empty释放RR/permit/frame后有限poll。前后状态/事件/watermark/minseq只能来自本轮一致快照，下轮才看到并发合法writer提交。reset不能从未经验证控制scalar构造。

## 原generation和真实异步所有权

最小 `PortalEventsResponse.isSuperseded(Capture)`：同holder锁内先requireCurrent（含sticky失效/硬期限），再校验同holder/evidence owner；旧generation返回true，同generation必须同evidence返回false，未来/foreign/null拒绝。不开网络、不返回权限、不改A deadline/renew/watchdog/install，不cache历史。它只解释旧capture已被同holder合法current替换，不代替read许可。

仅在明确的旧capture活性检查失败分支分类superseded；digest/metadata/material拒绝不能catch一般403后因恰巧renew就重试。确认为healthy superseded时，旧结果实际退出、关闭并归还后，对同after最多2次重新完整RR读取，仍受首次harddeadline；次数耗尽安全关闭，不复活旧结果。每轮新capture，材料与当前权限都重验。

新IAM使用专用Servlet async task，Controller成功接管后return null；不ResponseEntity.ok(null)，不借StreamingResponseBody框架追加getBody/flush。现有Spring6.2.7真实getBody在任务提交前，框架flush在callback后，故callback finally不证明最后实际退出。用户task必须拥有编码、实际getOutputStream获取及返回后终检、write前后/flush前后检查、最终可能阻塞的AsyncContext.complete。真实HTTP验证filter async-supported、正常frame清理和null返回，无异步ThreadLocalframe复制。

一个连接一个writer/最多一个pending result。专用ThreadPoolExecutor默认2线程+2有限队列，不用默认MVC无界executor；opening超额429 STREAM_CAPACITY。一个原子owner区分opening/PENDING/RUNNING/finished：opening阻塞发布stop但调用实际退出才释放；PENDING取消仅CAS确认neverrun后可释放，迟到run不能执行；RUNNING的getter/write/flush/complete真阻塞时timeout/error/onComplete/close/shutdown仅stop，Bpermit/Cslot/frame继续占用至actual finally。Future.cancel/async事件本身不是实际退出证据。提交拒绝、listener/启动异常、queued-neverrun都一次释放，不能由fixture私自扫资源掩盖泄漏。关闭观察不cancel后台run，不重置首次55秒hard bound；按A源/当前/原响应规则续核，不声称物理强断撤权SLA。

逐帧正常完成并继续poll时，当前B结果/frame可在该次真实consume退出后释放，Cslot仍归连接任务；不要求空闲poll强占B。若在持有当前结果/frame时进入终态/异常收尾，可能阻塞的complete也必须在这些资源实际finally之前返回，不能先退出consume再以“已完成通知”释放其它占额。没有当前结果/frame的停止路径只保持它实际持有的资源至complete退出，不为完成调用凭空申请B额度。

## 完整frame与共享容量

复用ChunkBus原slot与16MiB responseBytes，新增slot-only lease不注册32chunk queue；原subscribe/reserveResponse保持。新slot shutdown仅stop活动owner，实际退出再release，不让全局close提前释放新writer。frame在大分配/reader前保留；容量紧张时无Bpermit等待，有限retry/poll或opening429。

帧固定为 `id:<decimal>\nevent:<type>\ndata:<compact-json>\n\n`；event payload是B重新编码后<=262144 UTF8字节，type ASCII `[A-Za-z_]{1,64}`，seq非负long最多19位。framing最大101B，故完整backing cap262245B。分配前保留完整容量，一个固定/精确cap数组，无几何BAOS/额外toByteArray，不把copy/escaping/中间表示免费处理；payload不能含裸CR/LF注入。控制帧同样有限、guarded、预算内。C接收B独立输出copy的frame记C池，B临时copy仍在B ledger，不混淆所有权。

stream_status按pure FLOW报告chunkAvailable=false/chunkReplay=false，保留旧公开id/type/payload和recovery字段。未提交错误按实际isCommitted和旧HTTP映射；已提交且current仍live，metadata/root/state容量BUFFER_LIMIT、event/编码容量EVENT_LIMIT固定小reset。若authority失效只关闭，不能用旧proof补最后一帧。未知row材料拒绝不伪装容量，exception细节不输出。原getStream/write/flush/complete进入底层后字节不可追回，报告实际边界。

## 必须的真实验证与精确阶段

先真实HTTP/PG关键负例RED，再实现。common旧全关闭断言迁移为当前purpose规则，保留错kind/action/caller/mode/source/origin；缺handler/debug/v1/错误method/资源的默认关闭与payload SQL0落到真实engine HTTP。新fixture用真签JWKS/E exchange/actual authorize/open/renew、生产Controller/reader/PG与真实Servlet；跨模块只common/test-fixture JAR和HTTP，禁止业务服务JAR/target反射。不得拿Actor/公开DTO或fake proof直接mint。

至少验证：15节点真实pure FLOW、>64父的事件wire；同RR合法并发append/terminal、next轮新提交；state/event并集scope预算与完整state摘要，窄current/metadata篡改先payload拒绝；部分/全部终态清理后的expired/ahead/空表watermark，终态排空/非终态empty；真实query/header优先/重复/invalid、Last-Event-ID恢复、Unicode/escaping/type/id与完整frame exact/+1/backing；原服务票到期后合法原response续核、hard界不重置，旧generation实际getStream返回后/write/flush/第二帧前0业务输出；两个实际阻塞owner保留原B2permits/Cslots/frames，逐实际退出恢复；queued-neverrun、拒绝、timeout/onComplete/shutdown不能提前release；连接断开不cancel后台run。可以合并同一必要行为用例，避免镜像实现的低价值测试。

唯一未改IAM runner，独立DB `agent_platform_iam_o2a_c_events_v12`；所有cleanup只限实际fixture自有run。最后一次精确运行新增/修改类及必要原RunEventResponseTest单位回归，不重跑旧B整类/65/所有后端。RED/compile/setup/GREEN分开，记录每次command/cwd/exit/raw Running/精确XML方法与source epoch；先focused失败后一次最终适用类集合，最终绿后无改动不重复。若旧shared test fixture是否支持这条真实HTTP链不清楚先报告，不假造集成成功。

新local `.local/iam-o2a-c-events/`，新evidence `docs/evidence/iam-stage-4-o2a-c-events/`，helpers `.tools/iam-o2a-c-events-*`。报告 `docs/evidence/iam-stage-4-o2a-c-events-report.md`。继承正式Accepted source/target，只更新授权source delta、保存pre/post；有限target来自实际createdFiles/inputFiles/resources/common test-fixture JAR/actual Running XML+txt，新dumpstream单独，不扫描旧大树。新源码patch真实私有原始check/apply，所有source项名称/bytes/SHA一致，无apply后copy/no commit。journal/hash缓存成功物理阶段，metadata失败只恢复未完成项，不重跑成功测试/PG/replay。封存报告/handoff/own-inventory（仅sole self exclusion，含私有Git），归还写权后不再修改。作者完成不是Accepted，后接独立review/root有限审计。

## 正式Accepted基线与读取接缝

- 最新source `.local/iam-o2a-initial-migration/source-after.json`，593文件/112856B，SHA256 `e9791749f842e9403604f5fc3ff354e596536746577c65eb7b610374d7d69d16`。最新target同目录target-after.json，历史逻辑2060/427782B，SHA256 `9dd29bc3bd986d324a94dd100fa1c30e46518c715c0215aba555e8a644c4e98d`。仅对本文授权delta更新manifest，不能全树扫描源或旧target。
- Initial review `docs/evidence/review-iam-stage-4-o2a-initial-migration.md`，9129B，SHA256 `d2f32abe2c57c156e37fa36d85ee78eee0c131d9ba528ff1ced14de0464b8d89`；4phase/2157 physical root审计 `.local/iam-o2a-initial-migration-root-check.json` 2036B，SHA256 `fc6589694e258ae42db69f1e593912d1d69cf2455e0007f47b2316fb56e3e28e`；8索引checkpoint `.local/checkpoint-iam-o2a-initial-migration-docs-before/manifest.json` 4047B，SHA256 `ee9987da4053f69217062db0b0144066bbcbfd9dcb49ec53dfecc9679a554316`。全部只读继承，不重复旧验证。
- B实际最终输出API为 readNext(PortalEventsResponse,long)→Optional<EventEmission>，consume一次同步Consumer<Borrow>，Borrow.info/requireLive/writePayload/writeRequiredDataLabels；原ledger与lease已一同移交，输出copy分配前reserve(length+24)、payload/labels共同output guard。原scope/有限SQL helper均在engine infra包内。Initial任务production0，因此C生产基线与Accepted B borrow-fixes相同；最终8文件旧identity可由上述593清单取得，新文件不得覆盖现有路径。
- C既有接口/Servlet字节码读取线索在 `.local/iam-o2a-c-preparation.md`（28698B SHAf8909ee34be436552ae1b6bb457ea7965f53371d8f5fa017902dc427ea11272e，142内容行EOF）；它是只读准备，具体精确限制以上述正式要求为准。按实际源码窄读所需API，不假定新方法存在，不读整个累计实施总计划。
- 原所有A/B/Initial报告、evidence、private replay、journal和inventory冻结，不修改/重封存。只写新C自有目录和本文明确源码。root及reviewer不重复author已绿测试/DB/replay，作者成功阶段也必须cache。
