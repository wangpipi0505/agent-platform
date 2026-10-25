# O2b F2a 最小 TLS 基础任务（root正式冻结；实现尚未验收）

> 实质边界与最小修正：H=32768/peer chain=4 是 JVM 启动时的 JSSE 静态属性，不能包装成每 SSLContext 局部配置；本阶段用全新测试 JVM 启动参数验证，生产启用仍需后续 launcher 授权；若本阶段无法建立启动契约，32KiB/4链正式profile可明确待F2b装配验证，不阻碍独立TLS组件及其隔离测试，也不得宣称生产已生效。另建议增加累计出站 ciphertext W=131072，封住本端自主 KeyUpdate/hash 输出项；若不接受 W，须明确等价的有限输出约束，不能仅以入站 C 覆盖它。
> Agentic implementation：按已授权 SDD/执行计划逐项 RED→实现→GREEN→review；本任务现已授权实现；完成后另行独立review和root审计，不自行签收。

目标：交付一个默认未装配的、可取消且输入累计受限的 server-auth TLS1.3 socket 基础；F2b 才接 HTTP/Q，F2c 才接真实验材/SSE。
技术：Java21、现有 SunJSSE、Adoptium21.0.12+8；保留既有 Apache client5.4.4/core5.3.4，F2a 不新加依赖。
输入：F1 Accepted source612 manifest `.local/iam-o2b-f1-gateway-decoder/source-after.json`，SHA b391ee9fe99f69b51066006bdb468bcf4750cf2f6bd16a2dc22ae458fae301f4。
范围：只在本新工程工作；EVENTS 关闭；不改 F1、assembly、controller、common、pom、生产 main、八份执行文档及旧 transport。

## 1. 最小文件/API（3 个生产 + 2 个测试，测试资产另计）
生产目录 `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/`：
- `PortalEventsTls.java`：固定配置/CA载入、共享两物理槽、有限 watchdog/关闭调度；`PortalEventsTls(URI origin, byte[] literalAddress, Path caFile)`、`Connection acquire(long callerDeadlineNanos)`、`close()`。构造参数只来自受信装配，常量限制不可由请求覆盖。
- `PortalEventsTlsConnection.java`：实现上述 Connection（可将公开嵌套接口放首文件）；`SSLSocket connectAndHandshake()`、`void abort()`、`boolean awaitClosed(Duration timeout)`、`close()`。raw/SSL delegating wrappers、受保护 streams 均为内部类；close 发起终止，awaitClosed 才查询实际退出。
- `PortalEventsTlsDebt.java`：package-private 入/出站计量及 reservation；无网络、无 IAM、无可公开任意 credit/reset 方法；只暴露 package-private 只读 snapshot 供测试。
测试目录同包 `platform-gateway/src/test/java/cn/agentplatform/gateway/infra/`：`PortalEventsTlsDebtTest.java` 与 `PortalEventsTlsTest.java`；后者内嵌独立 JVM main/本地 TLS server helpers，避免额外通用测试框架。
资产：`platform-gateway/src/test/resources/iam-o2b-f2a/tls-fixtures.p12`，仅隔离测试 CA/服务端私钥及明确 alias；CA PEM 在测试临时目录从该资产导出；生产资源不得包含私钥。
F2b 接缝：Connection 内保留 package-private `Socket rawSocketForConnect()`、`SSLSocket upgradeConnectedSocket()`；前者只能在 acquire 取得槽后创建，后者只能升级本 handle 的原 socket 一次。
未来 Apache SocketFactory 先 acquire/取得这个受控 raw wrapper，TlsSocketStrategy.upgrade 再核原对象身份、固定 host/port 后调用 upgrade；不能直到收到普通已连接 socket 才计槽，也不能接受任意 caller socket。F2a 不实现 Apache adapter。

## 2. 有限配置与身份
固定 origin 仅 `https://<ASCII DNS hostname>:<1..65535>`，无 userinfo/path/query/fragment；单地址只接 4/16 字节 InetAddress.getByAddress，不调用 DNS，不接受 zone/hostname；SNI/SAN 仍按原 hostname。
物理 socket 必须 `new Socket(Proxy.NO_PROXY)`、channel=null；不使用默认 SOCKS、ProxySelector、CONNECT、STARTTLS、consumedInput overload、预读或协议 fallback。
每物理连接一个全新私有 SSLContext.getInstance("TLSv1.3","SunJSSE")，共享仅已受限 trust 素材；client session cache=2/timeout=55，禁止跨连接共享 session context。关闭后断开 context/session/wrapper 强引用；cache2 不宣称限制 childSessions。
实际 socket 只启用 `TLSv1.3` 和 `TLS_AES_128_GCM_SHA256`；HTTPS endpointIdentificationAlgorithm、固定 SNI；ALPN 只提供 http/1.1，接受空协商或 http/1.1，拒其它协议；不装客户端 key manager。
握手完成后、交出输入/输出流前，检查实际协议/套件及标准证书/主机名验证成功；不将证书错误吞为普通 EOF，不允许 TLS1.2 重试。应用不能重新 startHandshake 或修改验证/协议参数。
CA 原文件≤65536B：先 size 检查，再最多读65537B拒超限/竞态增长；只允许1–4张 PEM X509 CA，逐块计数、拒第五块及非空尾垃圾，再解析；校有效期/basicConstraints/keyCertSign（存在keyUsage时），只建私有 truststore，不合并系统根。
CA 限制可局部实现，但只界文件/数量，不能声称 X509 对象图≤64KiB；不从证书 AIA/CRL URI另行联网取材，本 profile 不启用网络 revocation。
新 JVM 启动固定 `-Djdk.tls.maxHandshakeMessageSize=32768 -Djdk.tls.maxCertificateChainLength=4`；前者是单握手消息 body 上限，非总握手字节；链长度是对端实际发送条目，非只算本地 CA。
这些值在 SSLConfiguration 初始化时读取；禁止运行中 System.setProperty 冒充生效。库可拒绝缺失/不符声明，但不能靠读取 property 证明已初始化内部值；新 JVM实测与后续 launcher启动契约才是依据。第五张可能已解析后才拒绝，瞬时费用另列。

## 3. raw 与 SSL 层唯一计量
固定 C=65536；每 physical socket 自 TLS 构造前持有一个 ledger，D=累计成功 raw 入站字节−唯一 SSL 应用层实际返回字节；另计 pending reservation R；始终 0≤D、D+R≤C。
raw read 先同锁 reserve min(requestLen,C−D−R)，释放锁后做真实 read，返回 n 后只 settle n、退未用额度；EOF/0不记成功字节。异常无可靠n则保守全额转debt并abort，不把可能已消费字节退掉；锁不能跨阻塞I/O。
length=0按InputStream契约返回0；正长度而容量=0立即容量异常+abort，不能返回假EOF/0。使用checked long，任何越界/负债均失败，不靠时间上限防溢出。
SSL外包装的唯一应用 read 在 delegate返回n>0后且交给调用方前 credit一次；HTTP头/正文都在这里，复制、解码、Content-Length、packet完成、下游write不再credit。一个handle最多一个reader/一个writer，并发同向调用立即拒绝。
raw read()/read(byte[],off,len)统一走单路径；raw skip拒绝正数（防JSSE deplete绕行），available最多报告当前可读预算且不读；无 mark/reset。raw direct delegate不外泄。
SSL skip也拒绝正数，readNBytes/transferTo只经本层read且不另credit；readAllBytes/readNBytes(int)直接拒绝无调用方有限缓冲的聚合。关闭/隐式JSSE drain从不credit。流close统一走handle.abort，不能先SSL drain。
不能用 FilterInputStream 的 read 重入产生双计；两wrapper代理实际地址/状态/options/shutdown/close，getChannel返回null，重复getInputStream必须同一实例；未验证时不得暴露SSL stream/getSession触发隐式握手旁路。
握手、NST、KeyUpdate、TLS header/tag/padding消耗不会被别的应用进度清零；Q→E、idle、逻辑release/COMMIT均不reset。仅新socket且新slot建立新ledger；物理池连接回池继续占槽。
建议 W=131072 raw出站累计硬cap：真实write前预留整段；成功保持已用，异常保守保持并abort，无credit；初握手/应用/KeyUpdate/alert均经同一hook。它是功能限制，不能被分段写或close绕过。
C控制输入引起的累计非应用内容，W控制本端输出引起的累计内容；证书/X509/JCE/native/静态cache仍单列。沿用准备稿 R=33088、P=33093、L=65860 瞬时数组项，不把它们压成C。

## 4. 槽、期限、取消与 F1 关系
F2a 使用独立固定2槽 ledger；acquire在创建 Socket/SSLContext/握手前占槽，第三个立即拒绝，无等待队列。异常构造也必须关闭已创建资源后归还；所有实例共用该物理上限，不能每请求new factory扩容。
connect≤1000ms；connect+handshake总≤2000ms（从acquire开始，慢滴不能刷新）；物理绝对期限≤55s且取callerDeadline更早者。所有期限用单调时钟；正剩余不足1ms向上取1ms，已过期直接拒绝，绝不传0表示无限。
握手后读空闲≤15s，从最后一次真实应用交付开始；NST/KeyUpdate/raw活动不续闲时。入池无reader仍执行相同15s与55s终止；这是保守可用性限制，F2b可更早取消，不增大期限或重置debt。
有限 watchdog每50ms扫描最多2handle；关闭执行最多2worker、每handle至多一个关闭任务，无每read新增任务/无无限取消队列。watchdog只发布stop/调度abort，不在其线程等待close。
先置stop、关闭真实最底层socket以打断connect/read/write，再关闭SSL包装并清理；失败/容量/timeout均如此。close报错不算成功退出；若真实I/O或close仍未退出，槽永久保持已占并拒新连接，报告受控耗尽，不假归还。
用活动I/O计数与关闭任务finally共同确认：实际connect/handshake/read/write/flush及close均退出、引用清理后才释放一次。Future.cancel、已设置stop、线程interrupt、session.invalidate、超时await都不是退出凭据。
abort与句柄安装竞争：安装前已stop则新句柄立即进入同一关闭路径；获取流、发起I/O、返回数据前均检查stop。关闭串行且幂等，失败后不会复用连接；已有数据被stop截住时不交给调用方。
F1真实API：`PortalEventsPacketBudget.SHARED.acquire()`→Owner；Owner.reserve(bytes)另收128B token；Owner.close拒绝live reservations/decoder read/borrow。本阶段不调用它给未知TLS总量占“16MiB”，也不修改它。
F2b须在原opening owner生命周期内持有TLS handle，Q→E顺序借用同连接或先实际关闭旧连接再新建；独立TLS槽与F1 owner双重限制。F2b自有HTTP/model/frame先Owner.reserve后分配，实际下游write/flush/async.complete退出再还Owner；TLS槽由物理close退出决定，两者不互相冒充。

## 5. 可执行测试与真实 RED（此稿未执行）
- [ ] 先建上述两测试类、有限测试素材和最小编译stub：生产默认拒绝/未实现。运行新测试记录真实断言失败；编译缺类仅算脚手架RED，不能替代“正常TLS本应成功却被拒”等行为RED。再逐项实现到GREEN，不提交stub。
- [ ] DebtTest独立脚本流按明确返回序列驱动真实wrapper：raw 100→app60则D40；C−1读2只允许1后下次抛限额；EOF/0退预留；阻塞read在返回前R已占；未知部分异常不退；负credit/溢出拒绝。断言真实delegate调用长度/次数，非只测setter。
- [ ] DebtTest用latch卡raw read/close：两槽占满，abort已调用但退出未放行时第三连接仍拒；解除后才可新acquire；覆盖close重复、安装前abort、并发同向read、skip/deplete/available及readNBytes/transferTo无双credit。测试替身只用于机械I/O/退出，不伪装TLS成功。
- [ ] TlsTest以ProcessBuilder启动同测试类的TLS服务端和客户端子模式，使用实际java.home/bin/java、surefire.test.class.path，分别传前述启动属性；socket只监听127.0.0.1:0，固定hostname为engine.f2a.test，连接literal127.0.0.1。子进程使用有限stdout/stderr收集、总期限及finally销毁并waitFor实际退出。
- [ ] 素材aliases含正常SAN、错误SAN、另一CA、过期、发送链4/5、Certificate body H/H+1；仅测试私钥。合法H素材需有效签名和受信链，不能用损坏record冒充H阈值。记录实际发送链条目/body字节，不假定PKCS12链等于wire链。
- [ ] 正例完成真实TLS1.3/AES128协商、固定SAN验证、有限原样应用字节回环；记录双方runtime/provider/protocol/cipher及必要启动flags，服务器未请求客户端证书；通过正常传输读取几十个完整TLS application records，观察D只扣明文而开销仍增。
- [ ] 错CA、错SAN、过期/无证书、明文端口、TLS1.2-only、TLS1.3仅AES256的实际端点均拒；服务器观察零客户端应用字节、客户端拿不到可用stream；无fallback/第二地址connect。仅assertThrows而不观察server行为不足。
- [ ] CA 65536/+1、1–4/第5张、尾垃圾、非CA分别在连接前拒；peer chain4成功/5失败，单Certificate H成功/H+1失败在新JVM实际验证。将profile错误与凭据错误分别记录，不能以“都失败”掩盖限制未生效。
- [ ] 真实TLS peer连续发1B小record直至C耗尽，逐次读取1B；不能收到假EOF，也不能debt被清零。另peer保持无应用输入、持续合法KeyUpdate至容量或固定期限取消，客户端零应用交付；真实server.startHandshake在该build若不能产生所需KeyUpdate，此项明确失败待最小fixture修正，禁止skip/伪计数代替。
- [ ] 真实peer启动时server.newSessionTicketCount=10，验证正常NST与application交错不重置debt；不声称这测试了任意NST洪泛。任意消息数量上界依raw hook+C不变量及已读consumer依据；不得因此重新开启无限provider调查。
- [ ] W边界先用机械raw输出验证W/W+1及异常保守收费，再真实TLS累计写至上限并观察abort；出站应用量只需有限，不能将无限writer留给reader的C兜底。
- [ ] 真实连接后不回应握手、握手慢滴、握手成功后无包idle、blocked应用read/peer停止读使write受阻，逐项验证2s/15s/55s/主动abort及第三槽实际退出时点；wall-clock断言留小调度余量，同时断言单调deadline未重置，不用sleep模拟通过。
- [ ] 同一物理连接分两个应用阶段模拟Q→E，阶段切换与短idle后D仍累计；这只是TLS复用测试，不冒称真实Q、PG或完整IAM链已通过。
执行命令候选（root在实现授权后确认现有wrapper位置）：`mvn -pl platform-gateway -am -Dtest=PortalEventsTlsDebtTest,PortalEventsTlsTest -Dsurefire.failIfNoSpecifiedTests=false test`；只跑新目标，不借旧target/replay当新证据；本稿未运行Maven或任何测试。

## 6. Review/source gate 与后续边界
证据/隔离/连续执行遵循 root 的 `.local/iam-o2b-f2a-evidence-contract.md`；本稿不另造证据规则。固定来源、每physical有限非应用输入、正常TLS验证、实际abort/exit为本阶段不可后移的验收项。
F2a只签收固定来源身份、C/W控制、有限槽/期限/实际退出；不签收16MiB TLS heap/native/globalcache或F1 192MiB全链覆盖。全局X509 soft cache、DER对象放大及JCE/native仍未量化，保留独立生产容量门槛，不无限延长本阶段。
每socket独占context避免把新连接parent/session图积到长寿共享context；全局证书cache不能靠close清除。已知边界明确保留，不允许将真实无限child/hash保留留在活动socket上而只标“风险”。
F2b负责Apache真实socketFactory+TlsSocketStrategy链、固定HTTP/Q、发头前proof、headers/drain及有限HTTP费用；F2c负责receipt/G/current/语义/公开SSE及跨进程真实engine/issuer/PG正例。F2a通过不打开EVENTS。
本次完整阅读：AGENTS.md、docs/implementation-brief.md、contracts/README.md；冻结transport95行/23946B、JSSE155行/29395B、rootnotes19行/4141B；F1新生产Budget3537B/Decoder19120B。未读F1测试/其它业务源码/target。
唯一新增primary窄核：OpenJDK jdk-21.0.12+8 SSLConfiguration L98–103，核静态属性名/default与不可局部设置的边界：[官方源码](https://raw.githubusercontent.com/openjdk/jdk21u/jdk-21.0.12%2B8/src/java.base/share/classes/sun/security/ssl/SSLConfiguration.java)；页面返回其它行不算完整审计。既有wrapper接缝/consumer结论沿用三份冻结稿，不重跑provider/javap/探针。

## 7. Root source gate 裁定（2026-10-21，正式要求）

本节把草案中的候选转为限定实现要求；源设计/四契约不变。W=131072接受，为raw本端总输出硬cap；未来本通道只固定Q/E GET，不能拿它承诺任意POST/无限输出。C=65536、全实例共用2个物理槽、零queue、TLS1.3唯一AES128GCM和正常证书/主机名验证均固定。两槽既约束活动socket，也约束入池尚未真实关闭socket。

H=32768/peer chain=4在F2a新子JVM以明确启动属性实际验证；生产main/launcher本轮不改，因此生产装配与内部静态初始化已生效的证明留F2b，不宣称全生产有效。组件构造要求启动声明与固定profile相符，声明本身不是内部状态证明；对子JVM采用java实际新进程启动属性并做真实peer阈值测试，不在初始化后setProperty。未知JSSE/DER/native/global X509 cache完整费用与全链192MiB覆盖继续待验，本轮不用F1 Owner伪预留16MiB。

唯一source新增6文件：上述3 production、2 test及1 test/resources/iam-o2b-f2a/tls-fixtures.p12；其余Accepted612逐字节不变。新工程原deps及固定JDK keytool可用于生成仅测试素材；密码可固定公开测试值，明确不是生产secret。不得为证书生成增加依赖/复制业务源/关闭JDK校验或使用internal sun.*导出绕行。Certificate H/H+1及4/5链必须为有效受信素材且记录实际TLS1.3 Certificate body/链长度；若素材工具存在技术阻碍先报告给root，不能用损坏record/同时有别的错误的素材代替目标边界。

最少可用API按第1节，Connection可为PortalEventsTls公开嵌套接口。三个文件各自职责不变，常规清晰格式，勿压缩整方法成单行。SSL公开包装不能允许应用重置参数/startHandshake或取出未计量原stream/socket；所有Inet/Socket/SSLSocket状态与选项的必要委托需明确列出并验证，未支持操作明确拒绝。SSLSocket closed/connected状态不得从空super Socket读取而造假。机械I/O测试接缝只package-private，不能提供public arbitrary verified TLS/proof factory。

50ms watchdog/2 closeworkers为这个TLS组件共用有限设施，与既有G/E自动renew职责不同；不另造授权renew。不能每factory/perconnection生成无限调度器或无界任务。factory close拒后续acquire并abort其连接；共享设施不能因一个factory close打断其它factory现有连接。计数锁不跨I/O，停止后数据不对外返回。close抛错/不退出不得提前归还槽；保留原异常的有限通用提示，不输出私钥/凭据。

真实标准来源只对实际阅读范围作对照。测试先最小功能RED，不要求每个最终case都有独立历史RED；编译失败/selector未启动均如实保留不计功能RED。用root evidence contract的既有offline runner，只运行新两类，阶段唯一；真实TLS子端点及进程全部隔离并finally实际退出。实际失败只做必要修复和受影响focused测试，唯一final两类之后不重复运行。

root完整阅读草案1–40、41–77 EOF，identity16030B/aea631b742ef275dd1bc2371d2e1e6d0457f3a4ffeda21e450a86de6551e8c5a；实现证据遵从 .local/iam-o2b-f2a-evidence-contract.md，冻结输入见 .local/iam-o2b-f2a-frozen-inputs.json。TLS工程范围已授权，无需再次询问用户。作者自己遇到新具体阻碍应先向root提出，不能擅自扩大源码范围或把待验项包装成完成。
