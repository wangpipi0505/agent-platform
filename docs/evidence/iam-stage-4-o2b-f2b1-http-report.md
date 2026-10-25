# F2b1 原Q HTTPS元数据实现报告

状态：实现及本轮证据冻结，交回 root 独立复审；本报告不作 Accepted 判定。唯一工程根为 G:/207/项目/算法重组工具/代码/agent-platform。EVENTS route、G/E、材料/SSE、主服务装配、生产部署、真实 engine/PG 全链均未在本阶段实施。

## 输入与范围

正式输入为 iam-stage-4-o2b-f2b1-http-brief.md、frozen-inputs.json 及 actual-api-gate/log-source-note/http-budget-notes 三补充。v1.2及四契约没有修改。before618 完整来源为 .local/iam-o2b-f2a-format/source-after，属于明确 copy-derived lineage，不能说成旧 patch 的重放。before target2202 为历史 logical lineage，不等于本阶段重新运行或重编译2202项。

source-after621：仅一已有文件追加 EVENTS 实现（RunObservationMetadataClient）和三新增（PortalRunEventsHttp、PortalRunEventsHttpTest、RunObservationEventsMetadataTest）。其余617文件逐字节保持。所有原 STATUS 源码在原末尾类括号之前的字节完全相同；status-prefix-closure.json记录机械核对。common/F1/F2a/controller/assembly/main/pom/前端/八索引/旧artifact均未改。

## 实现不变量

PortalRunEventsHttp(PortalEventsTls tls)只从已构造factory的同包final hostname/port/address派生端点。begin依次F1 acquire、reserve3145728（F1另收128）、记录t0、一次真实beginGatewayPortalEvents，保存其真实Opening/Q；不存第二份Portal原文，不另exchange。t0+5秒及t0+55秒不刷新，实际hop=min(t0+5秒,lookupStart+2秒,t0+55秒)。无公开URL/ticket/check回调或proof工厂。

每Session只允许一次lookup；同Session重复/并发lookup在网络前拒绝。私有pool total/route=1、唯一literal地址、私有route planner、不使用系统proxy/SOCKS、无本层retry/redirect/compression/cookie/auth challenge/auth cache/connection state/user-agent或协议升级。显式空cookie/auth registry和credentials provider避开默认PSL构造路径；空默认cookie store未启用处理。

固定四参DefaultHttpClientConnectionOperator通过DetachedSocketFactory先调用真实tls.acquire再取得rawSocketForConnect。真实F2a raw wrapper校固定地址、拒local bind；connect≤1000ms，握手仍受原F2a connect+handshake≤2000ms约束。upgrade只接受原raw、固定host/port及私有context，调用同handle的upgradeConnectedSocket一次。SocketConfig显式2000ms、linger禁用；operator对raw的选项调用与真实wrapper兼容，正常HTTPS及慢头/阻塞read验收均运行。SafeSsl禁止SO_TIMEOUT修改，所以私有Managed adapter对已升级socket保留F2a固定超时，由独立的绝对hop timer实际abort；不把Apache setter当期限证据。

私有QuietConnection继承固定core DefaultBHttpClientConnection，实现Managed必要接缝，沿用标准parser/writer/SocketHolder，不安装DefaultManaged的headers/wire logging hooks。没有生产全局logger配置更改。executor接收实际ClassicHttpRequest，先查原Opening/Q、owner期间状态、SSL socket和session参数/ID、固定GET/path/header；IdentityHashMap最多2项（本Session最多1项），finally移除。实际标准sendRequestHeader在取得SocketHolder.getOutputStream之后进入writer wrapper，再从同一actual request引用查guard并复核权限和请求。锁只包map插入/取出；检查与I/O不持map锁。不声称最终检查与阻塞write之间为零时间竞态。

请求完整wire ASCII计数32768，包括request line、最终Host、各头及CRLF；头数≤16，Bearer先验证ASCII/长度。测试8495字符仅为有限机械向量可容纳证据，不是实际E请求或伪造E正例。

响应只接受HTTP/1.1，首个1xx直接拒绝，成功200，有限403/404错误映射；内部401/302/挑战和其它状态503。响应head逐readLine在标准parser存储前计length+2，总≤8192，行≤8192，最多63头（第64拒），有限前导空行，拒fold/control。只接受唯一application/json（可唯一charset=UTF-8）、唯一规范CL或唯一chunked，拒CL+TE及重复，禁Content-Encoding/Set-Cookie/Location/Trailer/auth挑战。body用8193缓冲检测8192+1；分块末尾读到EOF确认无trailer，首trailer因独立maxHeaderCount1拒绝；扩展行有8192上界。额外的≤8192最终body副本处于固定B组剩余额度内。

EVENTS parser不走既有STATUS DOM。先固定char[8192]严格UTF-8解码，拒BOM；局部JsonFactory禁共享recycler/name canonicalization/intern及source location，严格重复键；depth4/name32/string512/number20/document8192/token64限制。只建立固定四个小对象map，字段立即按schema判定，无数组、未知树或skipChildren。拒未配对JSON转义surrogate，正整数只取long。完整核owner真实Q subject、opening run、原Q metadata selector、app root/version和三个小写SHA，保存全部七字段及snapshot。计长使用同schema的紧凑UTF8 generator和4096 hard sink，生成器关闭完成后才通过。返回前再核同Opening/Q；facts不是授权。Session保留facts，最终close同步清facts的Opening/Q借用引用；关闭后的getter拒绝，不能以结果延长authority存活。

## 3MiB组件费用与退出

固定表继承冻结budget-notes，实际实现图保持该profile：A393216（单builder/client/operator/StrictConnPool/managed/exec/config图），B65536（8192输入、行、输出、8193 body及最终≤8192副本/小IO），C524288（32KiB writer增长、Bearer/请求副本），D262144（8KiB总head及拒前扩容/63头/chunk），E1048576（严格decoder、char数组、JsonFactory/parser/固定map/scalar/duplicate检测），F65536（generator/sink及计长），G131072（Session/guard/timer/facts/退出），合计2490368，另655360固定余量=3145728。

每Owner真实账本基础65536+3145728+token128=3211392；两Owner6422784。测试实际记录正好此数、第三Session拒绝；机械真实F2a raw close gate未解除时close/awaitClosed不成功且费用不退，解除并实际await后一次释放。没有调大额度。

本层无application worker executor；lookup调用线程承担唯一操作，另每Session一个单次deadline任务/一个私有timer worker，F1最多2个Session，remove-on-cancel，shutdown后实际await termination、队列空。stop/错误首先真实handle.abort及request.cancel，随后才让Apache关闭response/entity，避免错误body close/drain。Q成功也先实际关闭handle、退出handler/executor及timer，再销毁client/manager。关闭与操作线程用active/operationThread协调；未退出保留Owner和费用，不能以cancel返回代替退出。成功Q保留Opening给后续b2接缝，直到Session显式close。

以上是固定有限可达图及拒绝前瞬时收费模型，不是实际全库逐字段heap证明。排除已存在common授权图、STATUS mapper、TLS/JCA/JSSE/X509/provider/native、类加载/metaspace及native线程栈，不能据此声称全链192MiB或整个JVM上限。真实TLS仍继承F2a C/W/槽位边界。

## 真实验证

每stage完整命令/显式cwd/exit/stdout/stderr/raw（若实际启动Maven）、当时完整source epoch和副本、原compiler input/created lists、实际Running的XML/txt及有限target均归档。源码没有在测试运行中修改。旧XML不计新通过；唯一final为iam-stage-o2b-f2b1-final，selector恰PortalRunEventsHttpTest,RunObservationEventsMetadataTest。

| Stage后缀 | 实际结果 | 说明 |
|---|---|---|
| red-parser | 1失败/0错误 | 测试已编译、真实签名Opening/Q先成功；反射断言缺少EVENTS解析API，非编译缺类 |
| green-parser | 无Running，编译失败 | Jackson Feature disable须单参数链式调用 |
| green-parser2 | 5/0/0/0 | 严格解析初组 |
| red-http | 1失败/0错误 | child缺Unix-domain临时目录属性导致issuer HTTP client初始化失败；不计行为RED |
| red-http2 | 1失败/0错误 | 隔离issuer/TLS配置已建立，实际测试缺原Q HTTPS实现；反射ClassNotFound导致断言失败，非编译失败 |
| green-http | 无Running，编译失败 | SchemePortResolver真实SAM为resolve(HttpHost) |
| green-http2 | 1/0/0/0 | 真实原Q HTTPS/DEBUG无票 |
| focused-initial | 7/0/0/0 | 初始HTTP组 |
| focused-compat | 2/0/0/0 | 旧STATUS正常lookup/严格503，真实close gate持账 |
| focused-boundaries | 无Maven/Running，exit1 | 既有wrapper禁止+selector；保留stderr，未伪造raw/XML |
| focused-boundaries2 | 4/0/0/0 | 只用逗号重跑受影响4方法 |
| focused-cleanup | 2/0/0/0 | 最宽合法字段、取消/重复lookup/timer退出与借用释放 |
| final | **18/0/0/0** | application7、infra11；唯一正式final |

final与focused-cleanup完整621行source epoch一致。后者真实编译20main/17test；final报告up-to-date并运行两类，没有强制mtime或重复编译美化日志。compilation-proof.json列原始lists与两epoch身份。前置47绿仅属于旧功能epoch，未借作本实现测试。

final中的真实端点是受控签名LoopbackAuthority issuer/JWKS与loopback临时端口原始TLS HTTP peer。gateway客户端运行在带F2a固定启动属性的新子JVM；同一隔离子JVM内issuer和TLS listener是两个真实网络端点，不冒充真实engine/PG。只读复用F2a公开PKCS12测试素材，未跑keytool/provider/旧TLS矩阵。27个本轮owned child均记录(run,PID)/argv/cwd/有界输出/实际exit和collector join，包括失败child；peer-exits.json逐项闭合。Maven/runner原始日志及27个child的日志未保存票/Portal/Authorization原文。

覆盖包括原Q逐字节唯一Authorization、一次exchange、固定host/SAN/literal/path/Accept、8495机械票规模、请求32768/32769、响应8192/8193 head及63/64头、fold/control/空行、重复CL/非法CL/CLTE/无界close framing、压缩/SetCookie/Location/trailer、chunk extension及首trailer、100/101/103、截短body与8192+1、错误CA/SAN/明文、系统proxy/SOCKS诱导、严格403/404与401/302/500映射、阻塞真实握手后原Q失效零HTTP字节、真实SocketHolder输出接缝后到期零请求字节、body完成前失效零facts、缺identity拒、两Session第三拒、取消/重复lookup/安装前abort、实际timer退出、blockedclose持账及解除后一次退账。普通正常HTTPS走真实F2a wrapper；机械hook只阻塞，不能造proof。

metadata组覆盖8192 envelope含空白正例和+1、owner/run/root/version/hash/七字段、重复/trailing/BOM/UTF16/坏UTF8/未配对转义、长scalar/number/trace及最宽128-unit owner/84-byte root id/两个Long.MAX_VALUE合法字段。schema在允许全部control按6B转义的保守最坏情况下，紧凑data上界**2080B**（data-schema-bound.json）。因此有效schema下4096合法data正例及4097合法data反例不可达；4096 hard sink仍保留，未放宽schema捏造边界。最大合法字段与单字段+1实际测试已通过。

仍有10份新dumpstream：Windows Surefire Boot Manifest-JAR绝对classpath/'other has different root'诊断，每份344B，均原样保留；未加disableClassPathURLCheck之类JDK检查绕过。原有deprecation警告也保留。早期环境/编译/selector错误见metadata-corrections.json，修正后只恢复未完成或受影响工作，成功stage证据未重造。

## 固定开源接缝对照与限制

以下为具名方法窄读和本地固定JAR javap API核对，不是整库审阅。配置对象图其余来源沿冻结补充；实际预算据有限profile作工程保守模型。

- [client5.4.4 DefaultHttpClientConnectionOperator](https://raw.githubusercontent.com/apache/httpcomponents-client/rel/v5.4.4/httpclient5/src/main/java/org/apache/hc/client5/http/impl/io/DefaultHttpClientConnectionOperator.java) L95–107、135–238：detached factory、literal resolver、raw选项/connect、同socket upgrade和双socket bind。只有一个解析地址，不走下一地址重试。
- [core5.3.4 DefaultBHttpClientConnection](https://raw.githubusercontent.com/apache/httpcomponents-core/rel/v5.3.4/httpcore5/src/main/java/org/apache/hc/core5/http/impl/io/DefaultBHttpClientConnection.java) L95–115、163–188、308–335：标准parser/writer构造、空logging hooks及真实writer调用顺序。
- [core5.3.4 BHttpConnectionBase](https://raw.githubusercontent.com/apache/httpcomponents-core/rel/v5.3.4/httpcore5/src/main/java/org/apache/hc/core5/http/impl/io/BHttpConnectionBase.java) L76–116、154–176、190–268、324–331：8192缓冲、chunk stream配置、holder getter、SO_TIMEOUT与close路径。普通close或清holder不是F2a实际退出证明，故Session独立await。
- [client5.4.4 DefaultManagedHttpClientConnection](https://raw.githubusercontent.com/apache/httpcomponents-client/rel/v5.4.4/httpclient5/src/main/java/org/apache/hc/client5/http/impl/io/DefaultManagedHttpClientConnection.java) L55–60、167–206：默认wire holder和逐头日志，以及activate/passivate语义；本实现不用该类及logging holder。

局部javap另外确认SchemePortResolver SAM、Pooling builder override、四参executor、SessionInputBuffer接口、Managed bind/socket/activate/passivate API。原冻结budget/log note对Jackson2.19.0和builder默认PSL路径的窄读继续作为输入，未重hash或重跑provider调查。

精确8192 envelope正例直接经过parser，真实TLS body的8193溢出已测；精确8192 body成功未单独通过TLS断言。这一覆盖边界保留给独立复审，不把parser级边界扩大成wire正例。

明确未逐一实现/机械模拟所有理论竞态的穷举证明；最终check到真实阻塞write之间存在时间窗口，不能扩大为零竞态保证。证据证明本次有限profile与列出的实际场景，不代表生产容量、真实模型/本体/IAM/K8s验收或EVENTS端到端成功。

## 产物交接

新四文件raw binarypatch32926B，SHA256 ef06c582a8b7e8d1ed9be768a6c978fcb6b6ff73e25d1e0338600428097fa822。全新私有Git只来自本工程before618 source-only复制，add前私有attributes为* -text -diff，global/system/template隔离；baseline/replay原始index blobs完整618逐bytes匹配，新的首次check/apply各一次，apply后没有copy source补齐，完整621回放bytes和names完全一致。

有限target overlay341项，仅来自当轮compiler lists/resources/common fixtureJAR/actualRunning reports，未全target扫描；历史2202逻辑项加本轮overlay合并后2223。新diagnostics10份分列，未混入新通过结果。source-after/delta/new/snapshot、target-before/after/delta/overlay/resource-copies、stage-catalog/compilation-proof/peer-exits/replay与control-identities均在本次owned目录。owninventory精确包含新owned、privateGit、source/target证据副本、stage/peer、helpers及本报告，仅排自身；live source/target在各after/overlay清单另核。

最终handoff/inventory按无循环顺序生成；最终消息另给本报告/handoff/inventory真实bytes/SHA。写权在最终交还后全部归root，等待独立完整review和有限产物审计。
