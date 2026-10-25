# O2b F2a TLS 基础实现报告

状态：作者实现及自审完成，等待独立 spec/quality review 与 root 有限审计；未自行签收。唯一 final 实际 **39 tests：Debt7 + TLS32，0 failures / 0 errors / 0 skipped**。没有 commit、部署、外部发布或生产装配。

## 范围与冻结依据

本轮唯一 shell workdir 为 G:/207/项目/算法重组工具/代码/agent-platform。读取正式 brief、evidence contract、frozen inputs、AGENTS.md、docs/implementation-brief.md、contracts/README.md；技术必要输入完整读取 JSSE preparation 与 root followup notes，transport preparation 仅作为已冻结的继承引用，没有声称重读其正文/重审全部 Apache/provider。实现遵从 TDD、ponytail、SDD implementer 与 verification-before-completion；设计已接受，无新增用户确认。

正式 requirements：[brief](iam-stage-4-o2b-f2a-tls-brief.md)；实现期间 root 裁定：[boundary clarifications](iam-stage-4-o2b-f2a-boundary-clarifications.md)。Accepted612 在首次修改前完整 names/bytes/SHA 对照通过；原612在最终再次逐文件核对完全不变。仅新增以下6个源/测试资产，after=618；未改 pom/common/main/assembly/旧 transport/八份执行索引，EVENTS保持关闭，无旧工程/数据库/依赖关联。

| 新文件 | bytes | SHA256 |
| --- | ---: | --- |
| platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalEventsTls.java | 8289 | fb09fd6399f127dd9d0f719096ba1f0c72a73066bbc528c73f2697647dd70a28 |
| platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalEventsTlsConnection.java | 30361 | d83114741dd9ca04bf81f1db7a158916cf2071816360c301350d2bb8c32d6c31 |
| platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalEventsTlsDebt.java | 5838 | 574400dabcf8e406ce1b18b6c1ed770a3372f033f20f7789c45978489fa3584a |
| platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalEventsTlsDebtTest.java | 6672 | b0f8cffd176ca12a3d13ac61c662ec1d4c1c823e8a3940bee3489e023175136c |
| platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalEventsTlsTest.java | 36481 | 1902769990fdaa36aac47e2ef930dd42a4b9522d7046f2e58af7660c49f80a33 |
| platform-gateway/src/test/resources/iam-o2b-f2a/tls-fixtures.p12 | 106807 | bfbe09360b7a2c4612480202d24714f725946ad1f86b0e1f49275010e2a8875d |

测试资产仅 PKCS12 隔离测试私钥，公开测试密码 f2a-test-only；aliases：root、other、i1、i2、i3、normal、wrong-san、expired、four、five、h、h-plus。固定 JDK keytool 生成，不加依赖、不用 sun.* 内部API、不降低JDK校验；逐命令结果/大小修正保留在 .local/iam-o2b-f2a-tls/fixtures/。

## 实现/API与边界

PortalEventsTls(URI,byte[],Path) 只接受受信装配给出的 https ASCII DNS host + 显式1..65535端口、一个4/16B literal address、无额外 URI 成分；InetAddress.getByAddress + InetSocketAddress(InetAddress,port)，不会调用DNS或多地址fallback。物理 socket 固定 new Socket(Proxy.NO_PROXY)，channel=null。CA先size≤65536，再读最多65537拒竞态增长；1–4 PEM X509 CA，计数/垃圾/有效期/basicConstraints/keyCertSign检查后进入私有 PKIX truststore，不合并系统根。

公共 Connection：connectAndHandshake()、abort()、close()、awaitClosed(Duration)。package-private rawSocketForConnect()/upgradeConnectedSocket() 是将来同包 Apache adapter 接缝；只能升级本handle原socket一次，不接受caller socket的公共能力。机械 installRawForTest 接缝仅测试退出，不创建 verified TLS/proof。

每physical一个私有 SunJSSE TLSv1.3 SSLContext，client session cache2/timeout55；共享仅有限 trust material。实际 socket 仅TLSv1.3/TLS_AES_128_GCM_SHA256，HTTPS hostname验证 + 固定SNI；ALPN提供http/1.1，空协商与http/1.1正常，h2端点拒绝。无客户端key manager。只有真实握手、peer证书、实际协议/套件/ALPN检查后才交出SSL view/stream。没有TLS1.2重试、预读、consumedInput、SSLEngine或代理旁路。

raw/SSL wrappers 全部内部，输入/输出实例缓存；state/address/options明确委托，channel=null。public SSL view 禁重握手、参数/协议/验证/session mutation、handshake listener/event和原delegate泄露；只读session不暴露session context。connected/bound保留已验证连接的真实历史状态；closed/shutdown查询实际SSL delegate，退出后给出终止状态。安全敏感与未支持socket选项明确拒绝，普通TCP_NODELAY/keepalive及地址/端口/有限timeout/getOption路径已验证。

唯一raw输入hook在SSL构造前建立。D=累计计费raw输入−唯一应用实际返回明文，R为阻塞read已预留；0≤D且D+R≤65536。同锁reserve/settle/credit，锁不跨I/O。partial/EOF/0退未用额度；异常无可靠n则整reservation保守转debt并abort（因此snapshot raw在异常后是计费上界，不能误称实际wire成功数）。credit只在SSL应用read返回n>0且stop检查后一次发生。握手/NST/KeyUpdate/header/tag/padding与Q→E阶段切换/idle均不reset。raw/SSL正skip拒绝、mark/reset不可重放；readAllBytes/readNBytes(int)拒绝，有限调用方buffer readNBytes/transferTo只走同一read，不另credit。每handle最多一reader/一writer，同向并发即拒。

W=131072在真实raw write前整段计费；成功保持，未知partial错误保守保持并abort，握手/应用/本端KeyUpdate/close输出均在同hook。该限制会拒绝合法长输出，未来仅固定Q/E GET不能据此承诺任意POST。snapshot output是成功/预留/不确定部分的累计charge，不假称全部已发wire。

所有factory共享2个物理槽，acquire先占槽、第三立即拒绝，无等待队列。connect≤1000ms；opening总≤2000ms；物理绝对deadline=min(caller,acquire+55s)；握手后应用输入idle≤15s，raw/NST/KeyUpdate/出站写不续闲。正剩余毫秒向上取整，绝不传0代表无限。共享50ms watchdog只扫描最多2handle并发布stop/调度；最多2 closeworkers、有限queue、每handle最多一close任务。factory.close仅拒本factory后续acquire并终止自身handle。

先stop，再close真实底层打断I/O，等待active connect/handshake/raw/application read/write/flush全部实际退出，再SSL close、断开context/session/wrapper内部强引用、最后一次释放槽。await超时/interrupt/cancel flag都不冒充退出。close报错/不退出会永久保留槽并受控耗尽；机械闩锁明确证明abort已调用、close放行但raw read未退出时仍不返槽。未承诺故障系统能强制终止任何内核/原生I/O。

## 新JVM profile与实际来源证据

客户端与服务端实际子JVM记录 Eclipse Adoptium 21.0.12+8-LTS、SunJSSE version21、TLSv1.3、TLS_AES_128_GCM_SHA256；正常服务端不请求客户端证书。启动显式 -Djdk.tls.maxHandshakeMessageSize=32768 -Djdk.tls.maxCertificateChainLength=4；最终新JVM同时 -Dcom.sun.security.enableAIAcaIssuers=false，PKIX revocation=false、fixture无AIA、server stapling=false。正常/NST场景server.newSessionTicketCount=10，H/H+1专用两端均0。声明缺失/不符与显式AIA=true构造拒绝。

这些property的当前值不能证明已有JVM早先初始化的静态值。实际新进程阈值测试是本轮证据；生产main/launcher未改，其启动顺序/H/chain/AIA完整生效与进程出站边界待F2b，不宣称任意现有JVM绝对无AIA取材。没有运行中setProperty或全局Security/provider配置变更。

新增primary窄读仅固定 [OpenJDK jdk-21.0.12+8 CertificateMessage](https://raw.githubusercontent.com/openjdk/jdk21u/jdk-21.0.12%2B8/src/java.base/share/classes/sun/security/ssl/CertificateMessage.java)：零起始L648–658、693–707、716–798、844–901。对照实际KM所选DER链、成功session.localCertificates与producer编码：空requestContext，3B list length，每entry为3B DER长度+DER+2B空extensions。该路径推导body/条目，**没有解密抓包或声称观测网络明文**。没有重跑旧provider probe/javap或声称完整provider/二进制等价审计。完整范围见 [source-comparison.json](iam-stage-4-o2b-f2a-tls/source-comparison.json)。

| 实际有效签名素材 | DER长度 | chain entries | Certificate body | 结果 |
| --- | --- | ---: | ---: | --- |
| four | [831,800,801,770] | 4 | 3226 | 正常原样应用5120B |
| five | [831,800,800,801,770] | 5 | 4031 | 明确maximum allowed length(4)，零应用 |
| h | [31984,770] | 2 | 32768 | 正常原样应用5120B |
| h-plus | [31985,770] | 2 | 32769 | 明确maximum allowed size(32768)，零应用 |

四/五链与H/H+1均受信、有效SAN/签名，不用损坏record混淆目标阈值。keytool随机serial可能改变DER长度，必要的实际生成失败/调整均另名保留，最终资产严格匹配以上大小。

## 真实测试与TDD

所有测试通过现有offline runner（固定Java21、project-only .tools/m2、既有依赖）执行：node .tools/run-iam-module-tests.mjs STAGE platform-gateway SELECTOR；外层 .tools/iam-o2b-f2a-evidence.mjs 原样保存command/cwd/exit、runner stdout/stderr、原始Maven合并日志、源epoch、actual Running两类XML/txt及原始compiler inputFiles/createdFiles字节。精确selector与command见 [test-stages.json](iam-stage-4-o2b-f2a-tls/test-stages.json) 和各stage/command.json。正常构建重新编译当前gateway19 production+15 test source，只有新两类实际Running；未把旧类编译或历史target当它们重跑。

| 唯一物理stage | exit | actual tests | failures | errors | skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| red | 1 | 2 | 1 | 1 | 0 |
| tls-red | 1 | 1 | 1 | 0 | 0 |
| functional-red | 1 | 7 | 7 | 0 | 0 |
| core-green | 0 | 7 | 0 | 0 | 0 |
| thresholds | 1 | 12 | 3 | 0 | 0 |
| thresholds-fixed | 0 | 12 | 0 | 0 | 0 |
| traffic | 1 | 7 | 1 | 0 | 0 |
| traffic-fixed | 1 | 5 | 1 | 0 | 0 |
| nst | 0 | 1 | 0 | 0 | 0 |
| overflow-red | 1 | 1 | 1 | 0 | 0 |
| deadlines | 0 | 8 | 0 | 0 | 0 |
| stream-guards | 0 | 8 | 0 | 0 | 0 |
| final | 0 | 39 | 0 | 0 | 0 |

功能RED为functional-red：Debt6 + 正常TLS1全部真实assertion failure；正常子JVM已启动，factory scaffold明确IOException TLS not implemented，expected true而actual false。最初red的launcher/UTF8错误与tls-red的server deadline只作无效脚手架记录，不冒作正常TLS功能RED。core-green随后7/7。overflow-red又真实暴露异常重结算阻断abort的问题；最小finally abort修复，deadlines focused 8/8与final均证实。

最终39项覆盖：raw成功/EOF/0/clip/reservation可见/unknown partial/overflow/C与W；skip/available/聚合/credit唯一；闩锁关闭/两factory槽/重复abort/安装前stop/close失败留槽；CA65536/+1与1–4/第五张/垃圾/非CA；实际TLS正例、空及http1.1 ALPN、错CA/SAN/过期/无证书/明文/TLS1.2-only/AES256-only/h2拒绝且server零客户端应用/一次连接；4/5链及H/H+1；真实1B records累计耗尽、真实KeyUpdate、NST10、两应用阶段复用、transferTo、应用同向并发、stream close；真实期限/阻塞read/write/主动abort。

最终normal实际80次64B echo，应用5120B，D从2241升至24165，raw计费29285；合法record开销不会因明文返回消失。tiny端点逐个1B：实际应用交付1168B后D=65536，非假EOF，raw计费66704。KeyUpdate端点无应用输入，server.startHandshake成功3652次，客户端零应用交付、raw=D=65536、output=44804后容量abort；没有skip或虚拟update代替实际实现。正常NST独立有界debug验证Produced10/Consumed10且应用5120B交错；不声称测试了任意NST洪泛。真实出站上限触发时charge=131054≤W，server实际收应用125952B。

| 实际final端点 | client总耗时ms（含CA等固定准备） |
| --- | ---: |
| stall | 2114 |
| drip | 2113 |
| idle | 15322 |
| blocked-read | 15271 |
| blocked-write | 649 |
| absolute | 55110 |
| caller | 3109 |

stall不回应，drip每350ms合法record前缀慢滴，均不能续opening；idle无reader和blocked-read在约15s终止；absolute持续应用到约55s，D仍40239，故确实触时间而非C；caller约3s；blocked-write在peer停止读且client SO_SNDBUF=1024时真实阻塞，350ms后仍未退出、并发flush立即拒绝，主动abort后实际退出。原单调deadline在各client finally核对未变。

所有端点只监听127.0.0.1:0、固定SAN engine.f2a.test、literal127.0.0.1。ProcessBuilder真实java.home/bin/java + surefire.test.class.path；有界stdout/stderr合并收集128KiB，NST专用1MiB，子进程总期限和finally destroy/waitFor实际退出。final32个case目录包含**61个真实子进程exit/collector退出记录**；早期阶段以当时XML中的finally退出断言为证据，不伪造后来格式的exit文件。

## 失败、诊断与证据边界

完整失败与修正见 [metadata-corrections.json](iam-stage-4-o2b-f2a-tls/metadata-corrections.json)。保留H成功初握手后NST33021触H、port文件存在/初写竞态、两次128KiB NST debug cap失败、overflow RED。H/H+1仅关闭其正常发票消除后续消息干扰；其它正常/NST/KeyUpdate测试仍10票。没有改校验或重跑成功final来美化日志。

Surefire早期user.dir为module，peer证据误写module .local；全部实际进程退出后按root批准，单PowerShell原生Move-Item、核绝对源/目标、仅47个已知目录404文件同盘原字节移到root owned目录。.local/iam-o2b-f2a-tls/relocation-map.json留原路径/原因；历史日志不改；module空父目录保留。final覆盖绝对root路径修正。

13份新344B dumpstream是Windows Surefire Boot Manifest绝对classpath的different-root提示，不是JVM crash；原始副本与 [diagnostics.json](iam-stage-4-o2b-f2a-tls/diagnostics.json) 独立保存，未按其建议关闭JDK URLClassPath检查。未改旧源码带来的deprecation编译提示也保留；报告不称日志绝对无噪音。早期阶段未即时复制的dumpstream现在以原始目标文件/时间和stage关联补齐，未删除旧诊断。

failed thresholds阶段编译后、peer测试运行中有一次raw生命周期源码修改：当时source.json是编译前epoch，target-finite中的input-source hash为稍后观察，不能冒称编译epoch。该失败原样保留。thresholds-fixed与final明确完整重编当前gateway字节且测试期间无源码修改；之后仅证据包装，不再改源码。初private Git metadata修复曾重复属性查询，但未重复成功测试、patch check/apply或重放来包装；所有具体限制如实列出。

## 源/target/patch交接

源before612、after618、新增6、source snapshot、replay-after618在.local/iam-o2b-f2a-tls/对应manifest。新完整private baseline/replay由Accepted612真实字节建立；private .git/info/attributes在add/diff前固定 * -text -diff并保存实际check-attr。初空模板无info目录与Windows612参数过长错误保留，后用明确pathspec文件续做。Git无commit、空template/私有config；patch阶段额外隔离system/global config，两个before Git tree的612个实际blob重新与Accepted bytes/SHA完全核对，防止隐性normalization。

仅6文件的binary raw patch生成一次；fresh完整replay第一次git apply --check成功、第一次git apply成功、随后full names/bytes/SHA=618完全等于after，**没有after-apply source copy**。命令/exit/tree都立即journal。patch：[source.binary.patch](iam-stage-4-o2b-f2a-tls/source.binary.patch)，168795B/SHA5750a5a06134782abb070e3e32994239daa77b7babd6fd8a4b2c192dd342dec6。

target-before继承F1逻辑2167条；本轮仅对实际compiler-list列出的当前class、实际资源复制、实际common fixture JAR、actual Running两类报告形成有限physical overlay314条，逻辑after2200条。旧诊断/未观察条目仅按历史逻辑保留，**不是全物理target扫描或完整classpath已证**。原始lists逐stage字节副本；target overlay副本、delta、resource-copy依据、13新诊断单列。没有重跑原F1/E/C/D/IAM/history suite/replay。

handoff列所有manifest/report/control的显式file/bytes/SHA/count/schema。无循环顺序经root批准：report和其它控制先冻结→handoff（ownInventory只path/count/schema及最终返回SHA策略）→ownInventory含handoff/report/all owned/privateGit/source6/有限target，唯一排除自身。最终消息给handoff与inventory身份；此后归还source/target/owned写权。root自己的preaudit/clarification不纳作者owned。

## 容量限制与后续

C/W仅封本physical累计非应用输入/本端输出相关保留原因；cache2/55不冒充childSessions总上限。沿用冻结准备中的局部瞬时数组项R=33088、P=33093、L=65860（重组旧/新数组还须分列），不能把它们压进C或把CA解析对象图说成≤64KiB。certificate chain第5项可能解析后才拒绝；X509/DER对象放大、SunJSSE调用的实际JCE工作区、native/socket/kernel、静态全局X509 soft cache及其它进程费用仍未量化。未声称16MiB TLS预算、完整JVM固定heap/native上限、F1 192MiB全链覆盖或生产容量验收。

组件默认未装配，不调用F1 Owner为未知TLS费用伪reserve。F2b需在opening owner生命周期持TLS handle，实际Apache socketFactory/TlsSocketStrategy原对象升级、HTTP/Q、发头proof/header/drain及HTTP自有费用；复用保持D/期限不reset，换socket先等旧physical真正close后再acquire。F1 owner与TLS physical slot按各自真实退出释放，互不冒充。F2c仍需真实receipt/G-current/完整验材/跨进程engine/issuer/PG与公开SSE；本轮没有真实Q、Servlet/公开SSE、部署或全部34任务完成声明。

自审：6文件职责/无依赖变更/失败默认拒绝/stop与安装/退出计数/credit唯一/边界数据/Source612/patch完整重放已检查；overflow在自审中修复且真RED/GREEN，最终通过39。剩余风险是以上明确生产启动、全局容量和F2b/F2c集成门槛；独立review仍可能提出修正，作者未代替reviewer/root验收。
