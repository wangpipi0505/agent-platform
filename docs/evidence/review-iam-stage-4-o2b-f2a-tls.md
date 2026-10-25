# O2b F2a TLS 独立任务审查

## Spec Compliance

- ❌ **Issues found**：单 reader 约束未覆盖整个批量读取调用（I1）；明文拒绝端点没有提供规定的独立零应用字节/无重连观察（I2）；子进程启动后的异常路径不保证进入清理（I3）。另有关闭失败状态和明确格式要求的偏差（M1、M2）。本结论仅针对 F2a，不能作为整个 IAM/F/SSE 或生产容量验收。
- **Task quality：Needs fixes。Critical 0 / Important 3 / Minor 4。**
- ⚠ 本报告末尾列出的 diff 外证明仍需 root 审计或后续阶段完成；作者报告、root 初核和测试数本身不替代下列语义判断。

## Strengths

- `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalEventsTls.java:38`、`:62`、`:104`：固定 origin/literal 地址与私有 CA 有明确边界；PEM 先限制原文件和实际读取大小，再逐块计数、验 CA 属性，PKIX 显式关闭 revocation；没有用系统信任根兜底。每连接创建独立 SunJSSE context，并单独设置 session cache/timeout，未把这些值误写成完整 JVM 内存上限。
- `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalEventsTlsDebt.java:18`、`:25`、`:38`、`:50`、`:64`：raw 读取先预留后 I/O，成功/EOF/0 与未知部分异常分别处理；计数锁不跨阻塞 I/O；checked arithmetic 失败仍通过 finally 发布 abort；W 在真实写入前收费且异常不退款。唯一应用 credit 位于 `PortalEventsTlsConnection.java:220`，没有阶段切换 reset。
- `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalEventsTls.java:19`、`:113` 与 `PortalEventsTlsConnection.java:138`、`:147`：全实例共享两槽，取得槽后才创建 socket/context；关闭先发布 stop、关闭 bottom，再等 active 归零、关闭 SSL、清引用并释放一次。关闭错误保留槽；没有把 cancel/await 超时当作退出。
- `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalEventsTlsConnection.java:105`、`:120`、`:132`、`:363`：固定 TLS1.3/AES128、HTTPS/SNI/ALPN，真实握手检查完成后才提供公共 view；公共 view 禁参数修改、重握手和 listener 暴露。物理 opening/hard deadline 不重置，idle 只在应用读取交付后更新（`:41`、`:67`、`:221`）。
- `platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalEventsTlsTest.java:52`、`:138`、`:324`：H/chain 边界使用新 JVM 启动参数和具名特定错误断言；正常 TLS 与机械生命周期替身用途分开；闩锁测试确实区分 bottom close 已退出与 raw read 尚未退出。真实端点覆盖两种 ALPN 正例、证书/profile 拒绝、C/W、KeyUpdate、NST、idle/hard/caller 期限，虽仍有 I1/I2/I3 缺口。
- 已读原始 final 日志显示实际重新编译 19 main/15 test，实际 Running 仅 Debt7/TLS32，39/0/0/0；功能 RED 原日志真实包含六个 Debt assertion failure 与正常 TLS expected true / actual false、`TLS not implemented (functional RED)`。这支持真实 RED→GREEN 历史，初始启动/服务器超时不被冒充功能 RED。

## Issues

### Critical

- 无。

### Important

**I1 — 批量读取仍允许第二个 reader 插入同一逻辑调用。**

- 位置：`platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalEventsTlsConnection.java:207`、`:226`、`:234`；现有覆盖位于 `platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalEventsTlsTest.java:244`、`:252`。
- `reading` 只从一次 `read(byte[],off,len)` 入口持有到其 finally；`readNBytes(byte[],off,len)` 与 `transferTo` 都继承 JDK 实现。固定 JDK 源码显示前者循环调用 read，后者每次 read 返回后再调用目标 output.write。因此，线程 A 的 transferTo 卡在目标 write 时，reading 已为 false，线程 B 的 read 可以成功消费同一个输入流；readNBytes 多次底层读取之间也存在相同窗口。数据会被另一个 reader 抢走，违反本任务“并发同向调用立即拒绝”，但现有只卡在单次 raw read 的并发测试无法发现。
- 最小修法：让所有公开读取入口共享一次覆盖完整调用的 reader ownership；把已持有 ownership 的单次计量读取抽为内部路径，使 bulk 循环不重复获取锁、不重复 credit。增加一个确定性的聚合并发用例：transferTo 的目标 write 用 latch 暂停，此时第二个读取必须立即被拒；再核对 bulk 完成后的唯一 credit。不能只给循环中的每次 read 加同样的 CAS。
- 判定依据：具名风险为“继承的 bulk 方法是否扩大同向并发窗口”。仅就此读取固定 [OpenJDK jdk-21.0.12+8 InputStream](https://raw.githubusercontent.com/openjdk/jdk21u/jdk-21.0.12%2B8/src/java.base/share/classes/java/io/InputStream.java) 的零起始 L481–491、L758–774；没有重跑 provider 或测试。源码确定该执行路径，本报告未声称运行过复现。

**I2 — 明文拒绝端点的零应用/一次连接证据是常量，无法支持规定的观察。**

- 位置：`platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalEventsTlsTest.java:433`、`:438`、`:440`，以及 `:44`–`:50` 的统一断言；作者报告 `docs/evidence/iam-stage-4-o2b-f2a-tls-report.md:79`。
- rawServer 把收到的客户端字节直接丢弃，finally 无条件写 `application=0`；`accepted=1` 也只在第一次 accept 后写死，没有 TLS server 路径的第二次 accept 检查。因此 plain 用例即便接收到不应有的应用明文，或出现额外连接，也可能仍报告相同的 0/1。客户端确实有失败断言，静态实现也没有 fallback，但这些都不能冒充 brief 明确要求的服务器独立观察。
- 最小修法：对 plain peer 收到的数据做有界记录与明确的协议/应用标记断言，确认只存在失败握手允许的流量；再用有限 accept 观察实际连接数。让 application/accepted 来自观察结果，保留已有失败与成功历史，新增唯一 focused stage 验证修正。同步收窄旧报告的“全部拒绝端点均已观察零应用/一次连接”表述，直到新证据建立。

**I3 — 子进程启动成功后，元数据失败可绕过 finally 清理。**

- 位置：`platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalEventsTlsTest.java:152`–`:156`；清理实现位于 `:185`。
- `ProcessBuilder.start()` 已经创建真实进程，随后先写 launch.properties，最后才创建并返回 Child。若 store 的文件创建/写入抛 IOException，外层 try-with-resources 尚未拿到 Child，collector 也没建立，process 没有任何 finally destroy/waitFor 所有者。服务器可能已经监听或阻塞；实际成功的 61 个退出记录不能证明这条异常路径受控。该窗口违背本任务所有隔离子 JVM 必须 finally 实际退出的要求。
- 最小修法：从 start 成功的下一步开始立即建立异常清理责任；在 launch 元数据写入、Child/collector 建立失败时 destroyForcibly 并有限 waitFor 实际退出，再保留原始异常。用一个新窄用例让 launch 文件目标不可写/是目录，核对启动后的 PID 确实退出，不需要重复整个成功 suite。

### Minor

**M1 — close 失败时公共 socket 状态被 terminated 覆盖，可能失真。**

- 位置：`platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalEventsTlsConnection.java:166`–`:174`、`:421`–`:423`；报告 `docs/evidence/iam-stage-4-o2b-f2a-tls-report.md:30`。
- 清理 finally 无论 close 成败都会清空 ssl，而失败时 terminated=false。此后 retained SafeSsl 的 isClosed/isInputShutdown/isOutputShutdown 全返回 false，即使底层 socket 已实际关闭、只是后续 SSL close 报错。状态与“保留物理槽等待可靠退出”是不同事实；当前映射会把已经停止且不可用的连接表现为未关闭/未 shutdown。实际流入口仍拒绝、槽仍保留，所以本项不等于绕过授权或提前归槽。
- 最小修法：在断开 delegate 引用前保留各个实际已知状态，并把 unknown/失败状态与 slot-release 状态分开；为实际 bottom 已关闭但后续关闭失败的路径补一个状态断言，避免用成功退出标志代替 socket 状态。

**M2 — 多处复杂方法压为单行，违反明确的可读性要求。**

- 位置：`platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalEventsTlsConnection.java:291`、`:294`、`:312`，`platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalEventsTlsTest.java:358`、`:361`。
- 这不是对简短 getter 的格式偏好：这些行包含 read/计数/同步/异常/finally，或完整的阻塞测试流与关闭方法，使安全生命周期审查和后续修改容易遗漏分支。brief §7 明确要求常规清晰格式、勿压缩整方法成单行。
- 最小修法：只展开复杂控制流、规范空格/缩进，不引入新抽象或改变逻辑；简单一行委托 getter 可保留。

**M3 — 新 Windows classpath 诊断和旧 deprecation 仍使测试输出有噪音。**

- 位置：`docs/evidence/iam-stage-4-o2b-f2a-tls-report.md:103`；本轮 raw 证据 `.local/iam-o2b-f2a-tls/diagnostics/2026-10-21T17-33-43_370.dumpstream:2`，`.local/iam-o2b-f2a-tls/stages/iam-stage-o2b-f2a-final/raw.log:55`、`:64`。
- 实际读取 final dumpstream，内容为 Boot Manifest-JAR absolute classpath / `'other' has different root`，并附禁用 URLClassPath 检查的建议；diagnostics.json 列本轮 13 份同类 344B 文件。final raw 同时存在未修改 IntegrationPolicy、GatewayPublishedChainPostgresTest 的 deprecation 提示。实际 Running、最终汇总与 exit 支持最终测试已执行，没有据此发现 crash/skip；但不能宣称诊断已解决或输出 pristine。
- 最小处理：保留原始诊断；在独立获授权的 runner/路径兼容性维护项中修复来源，不关闭 JDK 检查、不为了清日志重跑通过测试。旧 deprecation 留原责任范围，不扩大本轮六文件修改。

**M4 — failed thresholds 阶段的 input-source 哈希不是编译 epoch 证明。**

- 位置：`docs/evidence/iam-stage-4-o2b-f2a-tls-report.md:105`；`docs/evidence/iam-stage-4-o2b-f2a-tls/metadata-corrections.json` 的 `failed thresholds source epoch` 项。
- 编译后、测试运行期间修改生产 raw lifecycle 源，导致该失败阶段 target-finite 的 input-source 哈希观察晚于实际编译。该阶段不能用于“此份 input-source 字节执行了这次测试”的断言，也不能借原 source.json 与较晚 target-finite 拼成同一个 epoch。现有说明承认限制；后续 final 日志确实重新编译，但完整编译字节关联仍是 root 的有限审计事项。
- 最小处理：永久保留该失败阶段及其 epoch 限定，审计时排除其较晚 input-source hash 的编译证明用途；修正后与 final 采用各自独立身份。无需也不得改写旧证据或重跑失败历史来抹平问题。最终当前编译验证不因这个历史缺陷自动作废。

## ⚠ Cannot verify from diff

1. **全源与 Git 回放**：原 Accepted612 全部字节未变、source618/live/private baseline、binary patch 与全 618 apply/replay、owned5311、所有阶段及 logical target2200/physical overlay 的精确成员与身份，由 root 单独有限审计。六文件上下文不能证明整个目录未改动；本 reviewer 没有重新遍历旧源、target 或私有 Git，也未重跑 replay。
2. **所有阶段实际执行与进程退出全集**：已窄读 final/functional-red 原日志、functional-red TLS txt、test-stages/diagnostics/corrections，未逐个读取所有 XML、61 个 final 子进程退出文件和各 stage 的全部原始 peer 日志。39/0/0/0 与功能 RED 的实际日志已核；所有阶段、PID/collector 完整退出和 target 编译关联由 root 继续核对，且 I3 是现有代码中独立存在的异常清理缺口。
3. **PKCS12 内容与 wire 推导链**：package 中 fixture 为 106807B / SHA256 bfbe09360b7a2c4612480202d24714f725946ad1f86b0e1f49275010e2a8875d 的二进制通知。没有在本审查再次运行 keytool/provider，也没有独立解密抓包。Test:447–455 从实际 KM 返回链推导 DER/body，Test:56–60 断言准确的限制错误；其真实 fixture 内容、有效签名、受信链和作者已保存的固定 tag CertificateMessage 对照仍须结合现有 artifact 审计，不能仅靠二进制通知确认。root 澄清接受 producer+实际链推导，不能改称网络明文观测。
4. **H/chain/AIA 的生产生效**：Tls:51–56 只能检查声明；新测试 JVM 启动参数在 Test:141–147 可见。它们不证明任意既有 JVM 的 JSSE/PKIX 静态初始化状态，生产 main/launcher 与全进程网络取材边界仍未装配验收。
5. **完整容量边界**：C/W 限制与私有 context 不等于 TLS heap/native/kernel/global X509 cache/DER/JCE 总上限。第五链项可能先解析后拒绝、瞬时数组 R/P/L、child session 图与全局缓存的完整容量证明不在此 diff；不得声称 16MiB TLS 或 F1 全链 192MiB 已验。
6. **未来 F2b 接缝**：package-private rawSocketForConnect/upgradeConnectedSocket 有明确单 handle 路径，但实际 Apache5.4.4/core5.3.4 adapter、原对象身份核对、固定 HTTP/Q/headers/drain、F1 Owner 双生命周期与发头 proof 均尚未实现，不能由本次 TLS 复用测试推断通过。
7. **F2c/业务集成与 EVENTS**：receipt/G-current/完整验材、真实 issuer/engine/PG、Servlet/公开 SSE 和生产装配均未审查。新文件没有启用装配；整个工程 EVENTS 仍关闭及原 assembly/contracts/indexes 不变的外部状态由 root 身份审计确认。
8. **实现二进制与平台故障能力**：本次窄读 JDK InputStream 只回答 I1 的继承路径；不等于对固定 provider、全部 Socket/SSLSocket 或二进制等价性的重新审计。OS/native I/O 不能退出时，代码保留槽是受控耗尽策略，不是强制终止任何系统调用的证明。

## Review binding 与执行记录

- Base：Accepted F1 source612。Head：作者冻结 source618；无 commit。全程 shell workdir 只为 `G:/207/项目/算法重组工具/代码/agent-platform`，未访问邻旧工程。
- 完整读取 task-reviewer 模板、`.local/iam-o2b-f2a-review-instructions.md`、evidence contract、AGENTS.md、docs/implementation-brief.md、contracts/README.md、formal brief、boundary clarifications、author report。采用模板的独立 Spec/Quality 两 verdict，不接受作者自审代替审查。
- 实际核对输入：brief 19554B / SHA256 682c3d47a3150f714f8bfcd4c7ab7919b6cb1b6a6e870f8cd66ed810175d2a19；clarifications 2605B / 6fe0457ec21ca581a2b433c1dd50fcb4e21e0b8b83188e7b2e86ae9c82a6a4f7；author report 18124B / 11fb2e3ad7041387cc1457c73a9c3e9ba7a55e36c370abca89cb7ed64eecce17。
- 完整上下文包 `docs/evidence/review-iam-stage-4-o2b-f2a-tls-package.diff`：91810B / SHA256 288aa1e0f4b9e1262cb74ba146c6fa2ae3c72d84546eb808f1c46539c246b025。一次不重叠读取 1–160、161–320、321–480、481–640、641–800、801–960、961–1100、1101–1220、1221–1335 到 EOF；PowerShell Get-Content 1335 行，与保留末尾空串的 1336 splitlines 一致。未另读五个 changed 文本文件，也未重新生成 diff。合并初次输出仅 clarifications 被工具截断，已单独补全；上下文包各段无截断。
- 唯一具名外部源码核对为 I1 的固定 tag InputStream bulk 路径（零起始 L481–491、L758–774；网页附带上下文不作其它 API 的审计结论）。额外本地窄读只针对 RED/final 实际执行、诊断/epoch 具名风险，范围已在 M3/M4 与 ⚠2 中列明；没有爬取其它业务源。
- 未运行任何新实验、tests、PG、provider probe 或 Git replay；没有改动 source/target/作者 artifacts/八索引。唯一写入为本新报告。
- 本报告写入后冻结并交还 review 写权。后续修正须由 root 派发有唯一 stage 的必要 focused 验证；本 reviewer 未自行修改实现。

## Assessment

**Task quality：Needs fixes。**

核心计量、身份验证、有限物理槽与 abort-before-drain 结构有实质防护，真实新 JVM 测试也有价值。I1 会改变批量读的并发行为，I2/I3 分别使强制拒绝观察和隔离端点清理不完整；需修复并提供相应有限验证后再做 F2a 限定签收。


---

# F2a fixes 追加复审（修正 epoch，原报告前缀保持不变）

## Spec Compliance / Task quality

- **Spec Compliance：❌ Issues found，仅剩 M2 的局部格式要求未完全满足。** I1、I2、I3 及 M1 已关闭；本轮未发现新的 Critical/Important。C/W、固定 TLS profile、物理槽与真实退出等功能约束的修正未见退化。M3/M4 是明确保留的诊断与历史证据局限。
- **Task quality：Approved，带非阻断 Minor；不等于 root Accepted。** 当前 open 计数为 **Critical 0 / Important 0 / Minor 3（M2 残留、M3、M4）**，新增独立问题 0。原前缀的 Needs fixes 是原 epoch 的历史结论；本追加结论只适用于下述冻结 fixes head。
- 本轮审查只追加文档，没有运行测试、Maven、PG、Git/replay、provider probe 或新增实验。原 16561B 内容未改，原 plain 错误声明也没有被追溯抹去。

## Strengths 与逐项处置

### I1：关闭——整个公开 bulk 调用均持 reader/active ownership

- 现 head 位置：platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalEventsTlsConnection.java:250、:260、:265、:278、:290、:309。
- beginRead 在 CAS 成功后进入 active；CAS 拒绝发生在其它 reader 的 abort 范围外，不会为了拒绝第二个 reader 而取消第一调用。read()/继承 read(byte[]) 汇入受控三参 read；readNBytes(byte[],off,len) 与 transferTo 各自一次 beginRead/endRead，直到整个循环、目标 write 与 finally 完成才归还。
- readOwned 不重复 CAS/active；只有该内部路径在真实下层正 n、stop 检查之后 credit 一次并更新 lastDelivery。len=0、参数异常、首次 EOF 短读、bulk 异常 abort 与固定大小 transfer buffer 保留有限语义。
- 测试位置：platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalEventsTlsTest.java:44、:421、:449、:457、:475。真实 TLS 发送1024个42字节；target.write 的 latch 暂停时，read、有限 readNBytes、第二 transferTo 都必须立即拒绝。abort 模式占满第二槽，断言 bulk 未退出前 awaitClosed=false、第三 acquire 拒绝，放行后才退出。正常/目标异常/abort 三种路径均核对1024唯一 credit；首次 bulk-eof 检查短读及未填充缓冲内容。
- 原始 functional-red 日志实际包含“target.write blocked 时第二 reader 未抛错”；新 final 实际运行相关参数化用例并通过。因此不再只有静态推断或碰运气的并发覆盖。

### I2：关闭——实际有限 raw 观察与额外连接负控制建立新 epoch 证据

- 现 head 位置：platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalEventsTlsTest.java:57、:107、:935、:955。
- plain 分支实际捕获最多65536字节，使用固定3500ms读取总期限；按外层 TLS record framing 分列允许失败握手记录、opaque type23、不完整记录与非TLS剩余字节，另查明确应用 marker。正常 plain 断言不仅 application=0，还要求确有捕获和握手记录、opaque/incomplete/marker 均为0/false。
- 额外连接计数来自实际 accept；首连接后300ms窗口最多累计8次，socket 逐个关闭。负控制在首连接仍持有时排队第二连接，并发送明确应用明文，必须观察 application>0 与 accepted=2。证明范围是有限捕获及该 accept 窗口，没有假称解密 TLS ciphertext 或观察无限未来。
- 独立窄读原始 peer 结果：.local/iam-o2b-f2a-fixes/peers/plain-0278c117-f055-4daa-b3c1-82fe5a7161c9/server.properties 记录 captured=355、failureHandshakeRecords=2、accepted=1、application=0、opaque/incomplete=0、marker=false；.local/iam-o2b-f2a-fixes/peers/plain-negative-control-75d65670-df24-409f-87d0-ae87de7ed765/server.properties 记录 captured=33、application=33、accepted=2、marker=true。路径从新 peer manifest 中有限筛选，非系统进程扫描。
- observer-state-red 的原始日志确有旧 helper application=0/accepted=1 导致负控制断言失败。更早 functional-red 的 plain Connection refused 是测试发送顺序错误，仍为 error，不计 I2 功能 RED。
- docs/evidence/iam-stage-4-o2b-f2a-fixes-report.md:27 明确纠正原报告第79行的适用范围。此纠正与新有限观察一起关闭 I2；原 39 green 及原 constant claim 均仍是原 epoch 历史，不能被新证据追溯“证明正确”。stall/drip 也不因此获得不存在的独立零应用证明。

### I3：关闭——start 后立即持有异常清理责任

- 现 head 位置：platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalEventsTlsTest.java:76、:249、:270、:292。
- start 成功后立即进入 try，覆盖 started callback、launch metadata 写入和 Child/collector 构造；Exception/Error 调用 terminate，保留原始异常并把清理异常作为 suppressed。terminate 实际 destroy/wait，有限期限内处理中断并恢复状态，不把取消标志当成退出。
- Child 在调用线程同步打开输出文件，再建立 unstarted collector，完整初始化后 start；构造失败关闭已开的 output，再由外层清理进程。成功返回后生命周期交给 Child.close。
- 新测试分别把 server.launch.properties 与 server.out 设为目录，捕获仅本次刚创建的 Process，断言故障抛出后 waitFor/!isAlive；测试 finally 对 RED 时复现的泄漏也执行实际清理。functional-red 原始日志确有 metadata failure 后新进程未退出的失败；后续受影响矩阵与 final 通过。
- 本结论针对被修正的启动/元数据/collector 建立路径，不扩大成任意 OS 故障都可强杀或全系统 PID 审计。

### M1：关闭——已知 socket 状态与归槽结果分离

- 现 head 位置：platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalEventsTlsConnection.java:194、:621；测试 platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalEventsTlsTest.java:607、:617。
- 在清空 bottom/ssl 前分别缓存实际已知 closed/inputShutdown/outputShutdown；引用清空后公共 view 返回这些缓存，不再用 terminated 替代状态。terminated 仍要求 close 成功且 active=0，失败不归槽。
- 新测试对真实 loopback bottom 执行 shutdownInput/shutdownOutput，super.close 后故意抛 IOException，断言 view 已知关闭/shutdown，而 awaitClosed=false、第三槽拒绝。反射只创建内部 view 供机械状态观察，没有设置 verified 或制造可用 TLS 流。observer-state-red 原始日志确实先复现旧 view=false，随后通过。

## 保留 Minor 与新增问题判断

### M2：部分关闭，局部缩进仍未符合常规格式要求（非阻断）

- 已改善：原复杂 raw read/write/flush、关闭流程、阻塞测试的整方法单行已展开。源码中的计数、锁与 finally 现在能逐行检查；此改善成立。
- 仍需最小修正的具体 head 范围：
  1. platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalEventsTlsTest.java:381–389：client 方法 try-with-resources 的 byte[] initializer 后，外层 try 内正文没有保持正确嵌套缩进；同一错误持续影响该方法后续正文，至 :581–585 的方法收尾。
  2. 同文件 :586–591：failure 方法及 factory 方法被移到列0，和相邻 class 成员缩进不一致。:592 起又恢复四空格，说明不是统一的代码风格选择。
  3. platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalEventsTlsConnection.java:124、:387：多行 if 的单句 throw 被移到与 if 同级，弱化其受条件控制的可视关系。
- 后果：虽然空白不改变 Java 运行语义，但修正 brief 明确要求规范常规缩进；不能把仅“非空白 token 一致”视为 M2 全部关闭。以上属于可读性残留，不升级为并发或 TLS 安全缺陷。
- 最小修法：只修这些已具名嵌套/成员/条件体空白，不新增框架、不动 token；用独立新 epoch 的精确 token/字节 lineage 证明纯空白修正。无需为美化缩进重跑已通过47项，也不要求 final 强制重编译。当前作者报告与本次复审冻结后，后续纯空白修正另行核对，不能默改本 epoch。

### M3：保留——原13份诊断及本修正6份新增诊断

- 位置：docs/evidence/iam-stage-4-o2b-f2a-fixes-report.md:66；新 final 原始诊断 platform-gateway/target/surefire-reports/2026-10-21T18-22-26_498.dumpstream:2；实际重编译日志 .local/iam-o2b-f2a-fixes/stages/iam-stage-o2b-f2a-fix-eof-collector-green/raw.log:56、:65。
- 已读新 final 344B dumpstream，仍为 Windows Boot Manifest-JAR absolute classpath / different root，并附禁用 URLClassPath 检查的建议。原13份与本轮6份分别保留，不视为已修复；旧 IntegrationPolicy/GatewayPublishedChainPostgresTest deprecation 在实际重编译阶段仍可见。
- 实际 Running/汇总/exit 支持测试执行，未从这些诊断发现 crash/skip。后续 runner 维护另行处理，不能关闭 JDK 检查或重跑成功测试来隐藏噪音。

### M4：保留——原 thresholds 的晚 input-source 观察仍不能冒充编译 epoch

- 位置：docs/evidence/iam-stage-4-o2b-f2a-fixes-report.md:56、:66，原报告第105行。
- 原 failed thresholds 编译后改源码的历史局限没有消失，本修正未把它用于新编译证明。新 eof-collector-green 原始日志确有 gateway19 main/15 test 编译；final 原始日志明确 up-to-date。采用先前已实际编译且源身份相同的产物是有效的验证组织方式，不要求重复编译或重跑 final。
- 完整618 source epoch 相同、实际 createdFiles/资源与 final 执行 class 的有限 lineage 仍由 root 审计，不能只靠两个文字摘要断言。

### 新增问题

- 新增 Critical 0、Important 0、独立 Minor 0。M2 的残留沿原编号保留，M3/M4 继续作为公开限制，不计成新的缺陷编号。
- bulk-collector-green 名称含 green 但实际 exit1 的重复正长度 EOF 断言失败已原样保留。修正为首次 bulk-eof 短读断言覆盖当前要求，没有把这个失败写成成功，也没有据此声称 JSSE repeated EOF 新契约。

## 实际窄核与 ⚠ 边界

- 本轮完整读 fixes report，再完整读 fixes brief 与两文件 full-context 包；没有重读原六文件包或原报告正文，没有单独重读两文件 source。沿用上轮已读 AGENTS/工程简述/contracts/原 brief/clarifications/task-reviewer 规则。
- 包读取区间：1–180、181–360、361–540、541–720、721–900、901–1080、1081–1260、1261–1440、1441–1620、1621–1800、1801–1980、1981–2130 EOF；与保留末尾空串的2131 splitlines一致。各源码段无输出截断。随后只机械解析包计算所引 head 行号，不再输出或重审同一源码。
- 针对“功能 RED 是否真实、final 是否误称重编译”的风险，窄查五份原始 raw.log：functional-red、observer-state-red、bulk-collector-green、eof-collector-green、final 的实际 Running、统计、失败原因和编译行。读取 test-stages.json/有限 stage 元数据以及新 final diagnostics。实际 final 为 Debt7+TLS40=47/0/0/0，BUILD SUCCESS；eof-collector-green 为6/0/0/0，且真实编译19/15；先前两次 RED 与 EOF 失败均未被计成通过。
- ⚠ 未逐个独立重核所有123份 (run,PID) exit、118份 launch 与全部 XML/target/库存；peer-exits 的71run/123exit/121不同numericPID是已读汇总字段，根节点需继续按实际文件审计。一次元数据输出误展开大对象而截断，未将该输出称为完整逐项阅读。只窄读前述两个实际 plain peer properties。退出后的 numeric PID 复用本身不构成泄漏或身份冲突。
- ⚠ 早期 RED 的 cwd 字段是父 Surefire user.dir；源码显式 directory(...) 只证明启动请求。修正后的 cwd 字段亦记录 requested root，而非独立 OS cwd 观察。docs/evidence/iam-stage-4-o2b-f2a-fixes-report.md:64 已承认此限定，不能追补成过去已观测。
- ⚠ 原报告八类 diff 外边界继续适用，并扩展到本修正的新 source618/其余616不变、target logical2202/overlay316、两文件 patch/full618 replay、owned5338、各编译 epoch 及旧证据不可变性；这些由 root 的有限 artifact 审计闭合。本 reviewer 不重复 Git、扫描历史 target 或 provider。
- ⚠ H/chain/AIA 生产 launcher/静态初始化、完整 TLS/JCE/native/global-cache 容量、F1 全链预算、Apache 固定 HTTP/Q 与 F2b 接缝、真实验材/公开 SSE/F2c、全工程 EVENTS/assembly/index 不变的外部证明均未因此完成。当前 Approved 仅为 task quality 判定。
- 本轮没有新增外部 provider/source 调查；I1 的固定 JDK InputStream 依据沿用上轮已记录范围，修正后 bulk 入口已显式覆盖，不再依赖其继承循环承担 ownership。

## 冻结身份与写权交还

- fixes brief：8536B / SHA256 fcae15430b58383a25f00330f25430bb60f32758feac7ddc73261d884be1eb71。
- fixes author report：15211B / SHA256 7f1c7a56985fd3424de2995b2b7e21ccb74cb81f02d0b2fbbd7e45833fd0a9f2。
- fixes complete-context package：133305B / SHA256 e1b0bf9dbd211d947fe38b7e11df819e39f0eb106a1a76783b0512d868409e43。
- 上述三个输入均在读取时实际核对身份。fix head 的两文本身份由 package/作者冻结声明绑定：Connection35287B/de6d77ca8e5a7e093da63ca9d29dbd4be0e23b600fddc921ecdabb6c482d03b0；TlsTest57162B/160108502526edd71ed3aa66e64944f9066c5b9d0c0989a49a9b75535059a2df。当前live与完整manifest对应仍由root有限审计。
- 追加前实际核对原报告为16561B / SHA256 29bf1098be6afdf1b41b31470a323cd1bc4e66e75b4e3ff6e121cada90684e39；使用纯字节append并在追加后重新核对前16561B的同一SHA。完整combined报告身份在本次交还消息提供，避免文件自嵌自身hash。
- 唯一修改为本报告追加；source/target/作者owned/原报告前缀/八索引均未改。此追加完成后冻结并向root交还review写权。当前M2残留未冒称关闭；后续具名纯空白修正需独立追加复核。


---

# F2a M2 纯空白 epoch 具名复审

## 当前结论

- **Spec Compliance：✅ Spec compliant（限定 F2a 任务范围）。**
- **Task quality：Approved。当前 Critical 0 / Important 0 / Minor 2，保留 M3/M4；新增问题0。**
- **M2 已关闭。** I1/I2/I3/M1 的功能修正结论沿用上一追加复审；本次只核具名缩进，未扩大为生产装配、容量或后续 F2b/F2c 验收。root 仍需完成此格式 epoch 的有限控制/token/copy-lineage 审计后裁定整体限定签收。

## M2 闭合依据

- platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalEventsTlsConnection.java:124、:387：两个 throw 现均缩进到对应多行 if 的条件体层级，条件与执行语句关系清楚。
- platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalEventsTlsTest.java:382–591：array initializer、client 的 try-with-resources 内正文、分支/循环/异常/finally 和方法收尾恢复正确嵌套；failure/factory 回到类成员缩进，与紧接的 deadline/mechanics 一致。差异没有改变控制流 token、字符串或注释内容。
- 已完整读取实际 whitespace.diff 第1–150、151–300、301–449行至EOF，不重叠、无截断；完整读取短 format report 与 token-proof。未重读已审两文件完整源码包，也不需要额外源码片段。
- token-proof 列 Connection 5693 tokens 与 Test 11185 tokens，before/after token hash 分别相同；包括完整注释/字符串，并列 onlyLeadingWhitespaceChanged、lineEndingsPreserved 为 true。实际 diff 中两文件的全部新增/删除行也只移动行首空白，范围与具名要求一致。行数及语句内容保持，旧复审所引用的 head 行号继续适用。
- 新 Connection 身份：35295B / SHA256 21921cebd23d4aa73308376975a3ba6f3302961db46b9e83d00ae2cd0a856eb9；新 Test：58006B / SHA256 179f9b628d112dd980962ba81138551590b03fc167e4d848b355c924a3848536。身份来自已核绑定的 token-proof；当前live、before/after副本及完整manifest的字节闭合由root继续有限审计。

## 证据继承与保留限制

- **没有运行或要求任何新编译/测试。** 47/0/0/0 与 focused19 main/15 test 属于冻结的上一功能 epoch。本次字节仅作具名纯空白/token等价审查，不声称新字节已由 javac 编译或47项重新执行，也不把 token 等价称作新的运行结果。
- **M3 保留**：原13份及修正6份 Windows classpath 诊断、旧 deprecation 未修复；未关闭 JDK 检查。**M4 保留**：原 failed thresholds 的编译时刻与晚 input-source 观察仍不能拼接为同一 epoch。它们是公开保留的非阻断诊断/历史证据限制，本次缩进修改没有抹平它们。
- ⚠ 新 source618 snapshot 的616未变文件通过已验 fix replay 的明确 no-transform copy lineage 继承；这是复制快照，不能称为新的 patch apply/replay、编译或测试。完整618副本/manifest/token控制与新owned身份由root有限核对，本reviewer没有重跑其审计。
- ⚠ 先前追加报告列出的完整TLS/JCE/native/global-cache容量、生产H/chain/AIA启动契约、F1全链预算、Apache固定HTTP/Q、真实验材/公开SSE及其余外部集成边界继续有效。此次Spec compliant与Approved仅为F2a任务规格和质量的审查结论，不是全IAM/生产完成声明。

## 输入身份、前缀与写权

- 已核 format report：1829B / SHA256 2e4d0b7a1b31cd8524569aeaa1cc84593a3ea4beb3ae0d2192ef02fddee2d9e6。
- 已核 format handoff 身份：3352B / SHA256 bb2da2e018417445808e288da5d7fea3f43b6f65e098cab74670398a60c2c118；未把仅身份核对称为其全部库存内容审计。
- 已核 whitespace.diff：27120B / SHA256 2a61ae77ae5d36241a09341a190111b723e66cc0ed9e703973b60e4fc5a23034。
- 已核 token-proof：4288B / SHA256 2af97638945ba82edbdb15fa47c4ebee706ca7b65581569c624ce21e18139017。
- 追加前核对当前完整报告30724B / SHA256 167897aa7b417e0905578cb28be0212c56d80ab907b9b5180959cc359e4270e9。纯字节append后重新核对前30724B同一SHA，原16561B的SHA亦仍为29bf1098be6afdf1b41b31470a323cd1bc4e66e75b4e3ff6e121cada90684e39。combined完整身份在交还消息提供。
- 全程唯一shell workdir为新agent-platform工程；唯一修改为本次review追加。其它source/target/作者owned/历史报告/八索引保持冻结；未运行tests/Maven/PG/Git/provider/replay。此追加完成后冻结并交还review写权。
