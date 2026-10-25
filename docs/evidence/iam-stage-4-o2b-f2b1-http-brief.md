# O2b F2b1 原Q HTTPS元数据接入正式任务

唯一工作根：G:/207/项目/算法重组工具/代码/agent-platform；与相邻旧工程完全隔离。按当前v1.2/四冻结契约实施，时间安排不在范围。F2a已限定Accepted，八执行索引已闭合；此文只授权F2b1，不授权b2/c/公开EVENTS/主服务装配或生产部署。

## 精确前置与授权边界

前置certificate：.local/iam-o2b-f2a-acceptance.json；八索引checkpoint5588B/SHA463f154dadfe4d9cc7f02599c3e7954c15b440f99291c7aa5ed829fd3d907f8a。source-before618=.local/iam-o2b-f2a-format/source-after.json（118270B/SHAe9a961138967910fae61a2f611c8fd0f69d1391c4b4c973dc78b79da8e6be1e0），完整已验source-only复制快照=.local/iam-o2b-f2a-format/source-after；它是明确copy lineage，不是新patch replay。功能47绿/19main15test属于旧b0c594e9...功能epoch，格式head仅token等价未重编译；不得虚称已运行当前字节。target-before2202=.local/iam-o2b-f2a-fixes/target-after.json（460075B/SHA95313cf613f9b3045eb85b524740a77b2fb36fc6a6148ce83b43cce75d3d8067），仅历史logical lineage。完整输入记录=.local/iam-o2b-f2b1-frozen-inputs.json。

仅四个source写入路径：

- platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalRunEventsHttp.java
- platform-gateway/src/main/java/cn/agentplatform/gateway/application/RunObservationMetadataClient.java
- platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalRunEventsHttpTest.java
- platform-gateway/src/test/java/cn/agentplatform/gateway/application/RunObservationEventsMetadataTest.java

一已有文件修改、三新增，预期source621；其余617保持逐字节相同。不改F1/F2a/common/Controller/assembly/main/pom/现有STATUS transport/前端/四契约/八索引/旧artifact。只可在已有metadata client追加EVENTS专用解析与事实类，保留原STATUS方法。新owned=.local/iam-o2b-f2b1-http/、docs/evidence/iam-stage-4-o2b-f2b1-http/，报告=docs/evidence/iam-stage-4-o2b-f2b1-http-report.md，新helper前缀=.tools/iam-o2b-f2b1-，新stage外层log按既有wrapper具名归档。你是唯一source/target writer。

## Root实际API与固定实现决定（优先于下方草案推荐签名）

1. 采用PortalRunEventsHttp(PortalEventsTls tls)，从同包factory不可变hostname/port/address派生唯一HTTPS route/literalIP，不再收重复origin/IP；只能使用实际tls.acquire返回handle和同包rawSocketForConnect/upgradeConnectedSocket，不能制造verified对象。详见.local/iam-o2b-f2b1-actual-api-gate.md（3785B/SHA2c2b9f1d78a1702498424505eb2aa2506288a1fa5845ed9fc3a329aaa4b64a66）。
2. 实际Owner类型PortalEventsPacketBudget.Owner；先准入、再reserve3145728+token128，再begin真实Opening；t0在begin之前。一个原Q指一次真实begin/exchange，重复getter检查同一Q允许，不能重新换票。Opening5s/转交边界是真实common约束，不能自行改其时钟或伪造proof。
3. 默认ManagedHttpClientConnectionFactory存在headers/wire DEBUG出票路径；采用固定DefaultBHttpClientConnection的私有Managed adapter，保留标准parser/writer/SocketHolder和必要activate/passivate/close语义，不引入logging hooks。只追加必要接缝，不复制协议栈或通用框架；不更改全局logger。固定API窄读和决定见.local/iam-o2b-f2b1-log-source-note.md（4720B/SHA354e097b612935da49cb45132670a9400bf2b5ab64ed8fc76c429f91693e675d）。原草案默认managed factory路线以此替代。
4. 固定3MiB组件费用模型完整输入=.local/iam-o2b-f2b1-http-budget-notes.md（20355B/SHA76344f76c459a26048b3a672b6d3f98dba24f41725947da13011c64cc4950fc0）。实际固定对象图需再次对照；空cookie/auth registry、无默认PSL、无credentials/defaultUA/protocolUpgrade、不用DOM/共享recycler/name cache、有限取消设施及实际退出后退账都是成立条件。该表排除类加载/静态既有STATUS mapper/common/TLS/JCA/native，不能称整个JVM或全链192MiB证明。不得通过调大额度掩盖失配；遇实际固定API不符先给具名源码/费用事实。
5. b1仅真实Q metadata HTTP和严格事实解析；opaque EventsMetadata不代表业务授权。未装配EVENTS route；不实现G/E、receipt/材料/SSE，保留后续私有Session自然接缝即可。零复用、零排队、真实Q物理关闭/worker/timer退出后才能退账。Session lookup成功后保留同Opening供未来b2；明确close必定关闭借用以外的自有资源，不能关闭外部共享authority/TLS factory。

## 继承的F2b1细则（只取草案A–E，不授权其后续F节）

## A. F2b1 最小范围/API
1. 新增 gateway/infra/PortalRunEventsHttp.java：固定 origin/literal IP/已 Accepted TLS factory；内部 Session、Apache adapter、guard、有限取消/计费。
2. 修改 gateway/application/RunObservationMetadataClient.java：追加 EVENTS 专用严格解析及 opaque EventsMetadata；既有 STATUS lookup/Result 与旧 PublishedRunHttp 不变。
3. 新增同包 PortalRunEventsHttpTest.java 与 application/RunObservationEventsMetadataTest.java；测试 helper 内嵌，借已 Accepted F2a 测试资产，只读不改资产。
4. source allowlist 即上述 2prod/2tests；证据路径由 root 另冻结。common、controller、assembly、main、pom、旧 transport、F1/F2a 文件全部不改。
5. 推荐 API：PortalRunEventsHttp(PortalEventsTls tls, URI fixedOrigin, byte[] literalAddress)；构造不自动创建网络连接。
6. 推荐 API：Session begin(CurrentAuthorityClient authority, String originalPortalSource, String run, long cursor)；Session.lookupMetadata()→EventsMetadata；Session.abort()/awaitClosed(Duration)/close()。
7. begin 先 SHARED.acquire() 得 F1 Owner、预留 HTTP 固定工作额度、记录 t0=System.nanoTime，再且仅一次 authority.beginGatewayPortalEvents(source,run,cursor)。
8. Session 私有构造并持有真正 Opening；同 Session 恰一次 opening.queryAuthorization() 得原 Q 身份，任何重复 lookup/concurrent lookup 均拒绝，无第二次 exchange。
9. t0 在 begin 前；openingDeadline=t0+5s、lifetimeDeadline=t0+55s 都只可缩短。Opening 自身 getters/requireVerified 是权限依据，t0/deadline 只是保守传输限制。
10. 这层 begin 用真实 common 再验原 Portal，不把 raw source/run 参数视作已验 capture；F2c 才接实际 ingress，当前不能将 STATUS capture 跨线程复用为 EVENTS。
11. lookup 固定读取原 Opening 的 run/Q；完成 body、严格解析、返回事实前再核同 Opening getter 与 q.requireVerified()；不把返回 Facts 当读权。
12. EventsMetadata 私有构造，保留七字段事实、snapshot resource 及原 Q 身份用于后续交叉核对；toString 不出票/原文；不提供 arbitrary proof/permit 工厂。
13. 原 RunObservationMetadataClient.lookup(PortalStatusRequest,request,deadline,Cancellation) 会另 exchange，不能用于本阶段；Result.requireMain 也不是 G proof。
14. 最小解析入口可为 public static parseEvents(GatewayEventsOpening, byte[] boundedBody)→EventsMetadata，内部自行取得原 Q/已验 subject，不能收 caller 自报 SubjectKey 作为依据。
15. 上述解析入口只能制造已校验事实，必须绑定真实 Opening/Q；实际 HTTP 仍只由私有 Session 发起，调用者不能传任意 URL/ticket/check Runnable。

## B. 固定 Apache 真实接缝
16. 固定 client5.4.4/core5.3.4 与自有 Maven repo；不增加依赖，不升级库、不用新 resolver 或系统 truststore 替代 F2a。
17. 每个已准入 Owner 一条私有 Session/client/manager（最多2 Session），manager total=route=1；无连接复用/闲池保留；请求结束先实际关闭 Q socket 再允许后续 E socket。
18. 采用私有 PoolingHttpClientConnectionManagerBuilder 子类 override createConnectionOperator，注入四参 DefaultHttpClientConnectionOperator(detachedFactory,schemeResolver,literalResolver,tlsLookup)。
19. 仅 setTlsSocketStrategy 不足：默认 operator 会先创建普通 socket。DetachedSocketFactory.create(Proxy) 无 HttpContext；用 Session 闭包取得原 owner/当前唯一 deadline，不使用 ThreadLocal/公共 registry。
20. detachedFactory 拒非空 proxy，先 tls.acquire(min(lifetimeDeadline,当次hopDeadline)) 再 rawSocketForConnect；安装 handle 与 stop 的竞态必须立刻 abort。
21. lookup 只注册 https；resolver 只接受固定 hostname，返回一条 InetAddress.getByAddress(literal)；scheme resolver/route 校固定 HTTPS host+port，拒其它地址、local bind、隧道和升级路径。
22. TlsSocketStrategy.upgrade 核 socket == Session.raw、固定 host/port/上下文身份，再 upgradeConnectedSocket() 一次；返回固定受控 SSL wrapper。
23. 固定库 operator 将原 detached socket 直接传入 upgrade，再 conn.bind(sslSocket,socket)；可沿正式 F2a 两接缝接入，无须复制 operator 全实现。
24. F2a raw connect 实际仍须 clamp≤1000ms、connect+handshake≤2000ms；HTTP hop 总期限另取 min(t0+5s,lookupStart+2s,t0+55s)，不能因慢头/短读重置。
25. 4参 HttpRequestExecutor.execute 在连接完成后先验证私有 Session/context、实际 SSL socket/session 身份、GET/URI/header 与原 Q/Opening/owner live，再 super.execute。
26. 私有最多2项 IdentityHashMap<ClassicHttpRequest,Guard>：executor 用实际收到的 request 注册；锁只保护插入/取出，不能跨权限检查或 I/O；finally 精确移除。
27. 注册值只能由 Session 的真实 Opening/Q 构造；不得依 request 自报字段重造授权。缺项/错 request 对象/重复注册/第三项均在 writer 写出首字节前拒绝。
28. 自定义 requestWriterFactory 包装标准 writer：write(actualRequest,buffer,out) 先查同一对象，重核 Q/Opening、deadline/stop/SSL 身份与固定 header，再 delegate.write。
29. 已核 core 四参 executor→conn.sendRequestHeader(request)→DefaultBHttpClientConnection.requestWriter.write(request,...,socketHolder.getOutputStream())；中间不复制 request。
30. DefaultManagedHttpClientConnection 继承此 sendRequestHeader；故 writer 检查位于真实 getOutputStream 返回之后。上层原始 HttpGet 可被 Apache 包装，不应要求它与 executor request 同对象。
31. 不用 onRequestSubmitted/onRequestHead 作首发 guard，它们位于发头后；不宣称最后检查与阻塞 write 之间零时间竞态。
32. Q 请求仅 GET /internal/runs/{canonicalRun}/observation-metadata；HTTP/1.1、单 Authorization: Bearer 原Q、Accept: application/json、Accept-Encoding: identity、无实体/Expect。
33. 禁 proxy/SOCKS/系统属性/重试/redirect/compression/cookie/auth cache/auth challenge/connection state；拒所有1xx，包括100/101，绝不进入 fallback；headers/wire debug 必须关闭。
34. 用实际完整 request line+headers（Apache 添加 Host 等之后）计 ASCII octets，32768B 上限；Authorization 延用票既有限额，必须容纳8495B正常 E 票供下一阶段。

## C. 响应、解析与费用
35. 只接 HTTP/1.1；响应每行8192限制、至多63条（maxHeaderCount64实际拒第64条），maxEmptyLineCount 有限正数；拒 obs-fold/非法控制字符。
36. 建议额外固定总 response head≤8192B（含状态行/CRLF/终止空行）：parser 包装 SessionInputBuffer.readLine，在交给标准 parser 保存前累计 line.length+2，拒总超限；不以 socket read-ahead 字节数误计 body。
37. readLine 默认无 charset decoder 时 byte→char 一一映射；用CRLF计数对bare LF保守收费。单行背板由8192约束，reject前瞬时 line buffer 仍计费。
38. trailers 独立 maxHeaderCount1 拒首实际 trailer，chunk extension line 有界；终态读到 EOF 才能确认无尾部错误，不能把未读 trailer 称已通过。
39. 成功仅200；唯一 Content-Type application/json（允许唯一charset=UTF-8），禁止 Content-Encoding（含identity）、Set-Cookie、Location、Trailer。
40. CL 至多一项规范非负十进制；TE 至多一项且仅chunked；CL+TE/重复/其它coding拒绝。Q body最多8192B，有限缓冲读取上限+1探超限；拒无明确有限政策的close-delimited响应。
41. envelope 恰 code/message/data/traceId、code=OK、message≤512字符、traceId null或≤128字符；严格UTF8/重复键/BOM/trailing/depth/token限制。
42. data 恰 runId/owner/root/appVersionId/appVersionNo/acceptedSnapshotSha256/initialCeilingSha256 七字段；紧凑UTF8序列化≤4096B，先预留序列化scratch。
43. owner 恰 tenantId/principalId，交叉 q.ticket().operation().subject()；run 等于 opening.publicRunId；进一步核 q operation resource==RunObservationSelectors.metadata(verifiedSubject,run)。
44. appVersionId/No 为可装long的正整数；root恰 kind/id/versionDigest、kind=app-version、id符合现有 app@versionNo 规则；三个SHA为64位小写hex；保留所有七字段用于F2c。
45. 非200仅严格≤8192 ApiResponse：404/RUN_NOT_FOUND，403沿既有具名授权白名单；其它含内部401、302/认证挑战、畸形envelope一律503，不能冒充Portal401。
46. b1 不引入容量429新业务映射；F2b2 再明确 STREAM_CAPACITY 精确白名单。真正 common Portal已失效沿原分类，不把内部票到期统归401。
47. F1 Owner.reserve(bytes) 每项额外128B token；先reserve再建client/manager/请求/header formatter/response parser/缓冲/UTF8与JSON model，失败则abort而非先分配后补账。
48. 固定表至少列 input主8192+line8192+output8192；char扩容旧新峰值、至多63 header对象/列表/字符串、32KiB request formatter与ticket复制、8193 envelope、UTF8 chars/model/token/serialize scratch。
49. 为每 Session 的 Apache配置/连接对象、identity项、timer/cancel句柄及共享设施按最多2项静态保守计费；实现前须给具名固定库对象表和常量总额，不能以单个8KiB或随意16MiB代替证明。
50. 头总8KiB会缩小实际header费用，但预算仍覆盖拒绝前单行/扩容瞬时；JSON限制须在巨token构造前生效，不能完整String后才查限。
51. TLS仍仅继承F2a来源/C=65536/W=131072/槽/退出结论；X509/JSSE/native/globalcache全量堆预算未证，不从F1 Owner虚预留16MiB宣称全链192MiB已覆盖。

## D. 取消与实际退出
52. Session 活跃操作最多1；共享有限deadline机制最多2活跃任务，无每read新任务、无限队列或新授权renew scheduler。
53. timeout/cancel/坏头/parse失败首先handle.abort并立即discard/request cancel，随后close entity/response，防ChunkedInputStream.close先drain。
54. 若Apache在异常回退中自动close，raw/SSL受控close仍须走F2a abort；保留测试证明错误响应不在close/drain挂住。
55. Q成功也先abort/discard，完成execute/handler退出与真实handle.awaitClosed后才销毁manager/归还HTTP费用；不为复用drain未知残体。
56. close/await超时只报告未退出，Owner/HTTP reservation/Session slot仍持有；Future.cancel、request.cancel返回、logical stop都不能当I/O已退出。
57. b1 Session继续持原Opening及其t0/Owner，直到close；供b2在同5秒Opening窗口转G，不重新begin。关闭Opening、清source强引用后才最终释放。
58. 若退出被卡，保留有限cleanup状态并拒该容量的新请求；两个卡住即耗尽，不能新建factory绕过F1/F2a全局2槽。
59. F2a启动属性仍在新测试JVM固定验证；b1不改生产main/launcher，不能声称生产H/chain静态profile已装配。

## E. F2b1 可执行验收（后续获权才运行）
60. 先最小实际行为RED→实现→GREEN；编译缺类不计行为RED。用root既有offline runner、自有m2及上述两测试selector；不重跑F2a/provider调查。
61. 两实际隔离端点：受控签名issuer产生真实common Opening/Q +受信TLS原始HTTP metadata端点；新gateway客户端JVM带F2a固定flags，有限日志/总期限/finally实际waitFor。
62. 正例验证一次Q exchange、原JWT逐字节只发一次、固定host/SAN/IP/路径/Accept、8192 envelope/4096 data边界与7字段；此为HTTP/Q集成，不是enginePG正例。
63. 阻塞真实握手，令Q/Opening失效再恢复：server收到零HTTP头；在实际socket.getOutputStream接缝阻塞后到期亦零Authorization/请求字节。
64. 机械socket阻塞hook仅package-private并使用真实proof；正式HTTPS正例走真实F2a wrapper。另断言executor与writer的actual request引用完全相同，缺identity项不得发头。
65. 响应body读完或parse后Q失效，返回零EventsMetadata；错误subject/run/root/version/hash/七字段、duplicate/trailing/UTF16/BOM、8192+1/4096+1均拒。
66. 原始TLS peer测32KiB发送头正好/+1、8KiB响应head/line、63/64条、空行/folded、CLTE冲突、重复/非法CL、压缩、chunk extension/trailer、100/101/连续103与慢头。
67. 普通child8495B头规模只可用有限header机械向量验证为“容得下”；真实child HTTP成功留b2，不能把伪造大Bearer称E正例。
68. WrongCA/SAN/明文/另origin302/401challenge、系统proxy/SOCKS诱导零旁路；同地址只一次connect，失败不重试/不转发原Portal/Cookie。
69. 两Session占满第三立即拒；阻塞read/close、安装前abort、重复close、取消与返回竞态，实测未退出时账本/物理槽不归还、解除后一次释放。
70. 观察reserve在真实allocation前、费用正好/+1、拒绝后无残体drain；成功Q后物理close才允许下一physical，原5秒/55秒锚点不变。
71. 有限回归仅root选既有Metadata/STATUS/POST相关selectors，确认旧行为；final只跑一次必要矩阵，失败后仅受影响focused重测，结果与未验边界分别记录。


## 验证、证据及交还

先读AGENTS/implementation-brief/contracts及正式输入，关键失败路径先RED再落实现。使用真实common Opening/Q、隔离签名issuer和loopback临时TLS/HTTP端点；不假造proof、不引业务服务JAR、不使用相邻代码/依赖/配置。PKCS12只读复用F2a公开测试素材，无keytool/provider探测重跑。每子进程显式root cwd，日志/输出/期限有限；start成功即承担失败清理，finally实际wait/join；只追踪自己创建的Process，PID用(run,PID)。

新两测试类至少含一个旧STATUS lookup正常路径/错误语义兼容断言，不为此重跑旧整个业务矩阵。固定headers/wire DEBUG隔离测试的日志捕获必须有界，断言不出票且故障断言本身不打印完整票；不要用带token参数的断言错误或全局javax.net.debug把票带入raw。记录源码对照实际方法/行范围。

每实际stage立即保存完整argv/cwd/exit/stdout/stderr/raw、actualRunning XML/txt、当时完整source epoch、原始compiler input/created列表及有限target。final唯一iam-stage-o2b-f2b1-final，selectors=PortalRunEventsHttpTest,RunObservationEventsMetadataTest；以真实XML为准，不预造数量，未Running的历史XML不能计通过。需要新实编译时核实际source epoch，final同epochup-to-date可如实继承，不强制mtime/recompile/重跑美化日志。源码不得在测试进程运行时修改；失败和metadata修正保留，成功phase/hash/test/replay复用不重跑。

完整source-before/after/delta/new/snapshot；从前置618 source-only私有baseline准备新独立Git，仅本工程来源，add前私有attributes * -text -diff、隔离global/system/template；新的raw binarypatch仅四source，首次check/apply各一次，完整621回放逐bytes/SHA及names闭合，apply后禁止copy source补齐。不是业务服务JAR重用。finite target仅实际compiler lists/resources/common fixtureJAR/actualRunning，不扫历史target；target-after由2202历史逻辑加实际overlay合并，新diagnostics分列。

报告写真实RED/GREEN/final、实现不变量、开源对照、预算边界、所有失败和剩余项；不自行Accepted/commit。handoff按原fix可审计schema列manifest/control/stage/编译/peer/patch/replay/owned。owninventory精确包含新owned+privateGit+stage/peer/source和target证据副本/helpers/report，仅排自身；live source/target由各after/overlay清单单列核，不隐式算入ownedRoots。先report/control再handoff再inventory，最终消息给report/handoff/inventory实际bytes/SHA避免循环。原审计/报告/库存及八索引不可改。

冻结并归还source/target/owned写权后，回DONE/真实tests与source变化摘要/三份控制身份/concerns。root随后fresh独立完整review+有限产物审计，通过才限定b1闭合/更新八索引，再推进b2，不把metadata成功当真实engine/PG/events全链成功。
