# O2b F2b1 独立 task review

Spec compliance：**Issues found**。Task quality：**Needs fixes**。数量：Critical **0** / Important **2** / Minor **2**。本报告仅是 F2b1 四文件 task gate，不作 Accepted 或整链签收。

## 阅读范围与证据身份

唯一 shell cwd 为 G:/207/项目/算法重组工具/代码/agent-platform。遵循 AGENTS.md、docs/implementation-brief.md、contracts/README.md，采用 subagent-driven-development/task-reviewer-prompt.md 的先规格后质量流程及 code-review-expert 检查表。本轮仅写本报告；未执行测试、Maven、PG、Git、replay、probe、javap 或浏览器，也未修改 source、target、作者 owned、旧证据或八索引。

完整阅读正式 brief、作者 report、frozen-inputs、actual-api-gate、log-source-note、http-budget-notes；风险清单仅作检查线索，不作为缺陷证据。实际核对具名输入身份：

| 输入 | bytes | SHA256 |
|---|---:|---|
| docs/evidence/iam-stage-4-o2b-f2b1-http-brief.md | 20734 | cd3c7c6ac4f14c73f0055fbdda7381ec23fc294f4a1453bc02afdaaa720afac3 |
| docs/evidence/iam-stage-4-o2b-f2b1-http-report.md | 15079 | 48659d2a065fe688568ed46fe74cecfbecd1f20ab7d7e586d20e047bfb5950e6 |
| .local/iam-o2b-f2b1-actual-api-gate.md | 3785 | 2c2b9f1d78a1702498424505eb2aa2506288a1fa5845ed9fc3a329aaa4b64a66 |
| .local/iam-o2b-f2b1-log-source-note.md | 4720 | 354e097b612935da49cb45132670a9400bf2b5ab64ed8fc76c429f91693e675d |
| .local/iam-o2b-f2b1-http-budget-notes.md | 20355 | 76344f76c459a26048b3a672b6d3f98dba24f41725947da13011c64cc4950fc0 |
| docs/evidence/review-iam-stage-4-o2b-f2b1-http-package.diff | 77313 | ff04f29cc21453d09a83db20b7c79608cee08853f4a8e5489e082f31fe5f33a7 |

complete-context 包按不重叠 split-line 区间 **1–150、151–290、291–420、421–535、536–635、636–727（EOF）** 完整读完。包共 727 split lines；四文件全文均包含，未另逐文件重读源码。以下源文件行号按 diff hunk 换算：metadata 165 行、HTTP 238 行、metadata test 93 行、HTTP test 201 行。

必要依赖窄读仅为两个具名风险：

- 为判断原 Opening 的 getter/close 是否可以当作事实权限，读 admin-common/src/main/java/cn/agentplatform/common/authority/HttpCurrentAuthorityClient.java:543–573；确认真实 remaining/proof/5 秒与 close stop 语义。
- 为判断 HTTP cleanup 是否确实等待物理关闭，读 platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalEventsTlsConnection.java:149–234；确认 abort 只安排关闭，awaitClosed 依据物理关闭及 active 退出后的 terminated。

其余 diff 外仅读取具名本轮元数据、RED raw/txt、final XML/txt/raw 的相关字段/行、data-schema-bound 及一份原始 dumpstream。一次定位 stage 文件的文件名枚举包含 source 副本路径，未读取这些源码副本，未执行任何整库存/整源码/target 重 hash。

## 规格核对与已成立部分

- 只见 2prod/2tests，metadata 原 STATUS 内容在包中为 unchanged context，新增逻辑位于其后。新兼容测试实际调用旧 STATUS lookup 并断言正常 snapshot 和畸形 root 的 503（RunObservationEventsMetadataTest.java:64–82）。完整 617 文件未改的机械证明仍交 root。
- HTTP begin 先 F1 acquire/reserve3145728，再记录 t0、一次真实 beginGatewayPortalEvents；同 Session 保存原 Opening/Q，used 阻止第二 lookup（PortalRunEventsHttp.java:38–45、72–87）。未见 caller 可注入 URL、ticket、permit 工厂或另一次 exchange。
- executor 在实际连接完成后核实际 request，IdentityHashMap 插入/查找仅短锁，writer 在 SocketHolder.getOutputStream 之后再次核同一对象；权限检查与 writer I/O 不在 guards 锁内（HTTP:145–162、214、222）。测试覆盖实际 identity 匹配与缺项拒绝（HTTP test:96、172–175）。不声称 check 到阻塞 write 零时间窗口。
- operator 使用 Session 闭包、唯一 literal 地址与固定 https route；真实 acquire → raw → 同 handle upgrade。空 cookie/auth registry、非空但始终返回 null 的 credentials provider、禁 retry/redirect/compression/cookie/auth challenge/state/defaultUA/protocolUpgrade 明确落地，未引入默认 PSL 或 ambient credentials 路线（HTTP:114–150）。QuietConnection 使用标准 core parser/writer/holder，未安装默认 headers/wire hooks（HTTP:209–228）。
- SafeSsl 不允许重设 timeout 的限制由单个绝对 hop timer 调用 abort 处理，worker 禁继承 InheritableThreadLocal；没有每 read 重排任务或 application worker queue（HTTP:114–118、224）。头解析拒绝与 handler finally 均先 stopTransport，之后才让 Apache 收尾 entity/response；未知 remainder 不用于连接复用（HTTP:88–97、216、226–228）。
- head 总 8192、行 8192、最多 63 头、有限空行、fold/control、HTTP/1.1、1xx/非白名单状态、唯一 Content-Type、CL/TE 冲突、无界 framing、编码/cookie/location/trailer/challenge 采用拒绝策略。body 有固定8193缓冲与实际8192限额，终态读取用于触发 chunk/trailer 错误（HTTP:90–95、196–207、213–235）。
- EVENTS 不使用旧 DOM mapper：严格 UTF-8 REPORT、BOM 检查、局部禁 recycler/canonicalization/intern 的 factory、duplicate 检测、depth/name/string/number/document/token 限制；只构造四个 schema map，拒未知字段/数组/尾随值，long 正整数与 surrogate 配对检查均在固定路径（metadata:97–164）。七字段与原 Q subject/run/selector/root/version/SHA 核对，compact generator 关闭完成后才过 4096 sink，返回前再核原 Q；facts close 清 Opening/Q 借用，未见授权铸造入口（metadata:72–124）。
- 预算按实现重新对照，而非依据 reserved 计数直接通过：单 Session 一个 client/manager/route/request/connection；单 timer/task；8KiB 固定 IO、8193 body 加至多8192最终副本、32KiB请求格式化、有限head保存与拒绝前扩容、固定四map与局部解析/生成器。未见该 profile 之外的 DOM、共享 name cache、隐含默认 PSL、无限重试或每 read 设施。B 组额外最终 body 副本可由原65536余量覆盖；F 实际计数 sink 不保留输出数组，更小于冻结模型。A/G 仍是具名固定构造次数的保守工程估计，不能声称库/JDK逐字段绝对 heap 证明。
- 正常及错误收尾先等待 handle 实际关闭、timer termination，再清 transport 图，Owner/3MiB 最终由显式 Session close 释放；blocked close 测试证明单 closer 的持账路径（HTTP:169–186；HTTP test:190–198）。**并发 closer 的缺口见 I1，不能用单 closer 绿证据推定它成立。**

## Important（2）

### I1 — 并发 close/awaitClosed 可重复进入 transport cleanup，并读到被另一线程清空的字段

位置：platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalRunEventsHttp.java:169–177、180–184（包351–366）。

触发：lookup 已结束或尚未调用，两个线程同时 close()/awaitClosed()。active 只代表 lookup；finishTransport 在169–171行检查后没有占用 cleanup 执行权。两个 caller 都能通过 !transportClean；175–176行在锁外反复读取可变 handle/timer，而177行另一线程在 Session 锁内把它们清为 null。

可达交错：T2 对 `timer != null` 判断为真后暂停；T1 等待结束、清 timer=null/transportClean=true；T2 再求值 `timer.awaitTermination(...)`，可发生 NullPointerException。handle 的非空检查/awaitClosed 调用同样有此窗口，alarm.cancel/timer.shutdown 也有对应重复读。字段未以同一同步协议访问，初始 synchronized 检查不能覆盖之后的读取。当前测试只证明顺序重复 close 及 lookup/cancel，未覆盖两个真正并发 cleanup caller。

影响：公开 close/awaitClosed 的幂等并发退出会出现未分类运行时异常，调用者无法可靠获得“未退出/已退出”结果；无法据当前代码签收并发 cleanup 协议。这里未声称已复现 NPE、已出现票泄露或物理 TLS 被提前释放；结论来自明确源码交错，本 reviewer 没有运行新测试。

最小修法：在 Session 状态锁下选出唯一 cleanup 执行者，对其所用 handle/timer/alarm/client 建立一致的局部快照；其他 closer 在各自剩余期限内等待该清理结果或返回 false。物理 await/termination 不持 guards 锁，也不要把整个等待简单搬进 Session 锁从而阻止 timer.abort。只有清理状态成功发布后统一清引用和一次退账。为两个并发 close/awaitClosed 加确定性 barrier 测试，覆盖“正在 cleanup、timeout 保持费用、解除后一次释放、两调用均不抛 NPE”；仅运行受影响 selector 并保留真实新 epoch，不重造旧 final18。

对应 brief D52、D56、D58、E69；优先级 Important（P1）。

### I2 — 缺少正式要求的真实 HTTPS 精确8192 body 成功边界

位置：platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalRunEventsHttpTest.java:103–119，特别110/117；application/RunObservationEventsMetadataTest.java:43–47；正式 brief E62（brief:98）。

目前精确8192成功只直接调用 parser。wire 正例为普通 body、8192 head、63条头、普通长度 chunk；没有通过真实 Session/TLS 的8192 body成功。wire 的8193向量使用 fixed(200,...)，同时发送 Content-Length:8193，所以会被 HTTP:206 的头检查先拒绝，不能将它描述为已实测8193缓冲读取溢出分支。作者 report:78 已诚实承认 TLS8192成功缺口。

影响：可达、明确要求的 HTTP/Q 集成边界尚未完成；parser 层绿不能证明 framing → bounded read → parse → 物理 close → facts 的边界组合，也不能排除恰好上限时的读取/EOF问题。与4096合法data不可达不同，向合法envelope追加空白即可得到合法8192 wire body。

最小修法：在真实受控 TLS peer 的 focused boundary 用例中发送精确8192 UTF-8 body（合法schema加空白），至少 CL 路径断言 facts七字段、一次原Q/无第二exchange及实际退出退账。再用 chunked8192成功与 chunked8193拒绝覆盖无预报CL的实际上限+1读取路径；后者同时证明拒绝先abort、零facts且不drain。只跑该新增/受影响用例，记录真实bytes与新stage，不把旧 parser 结果重命名为wire，不重跑旧18来美化历史。

对应 brief E62、E65 及 C40；优先级 Important（P2，明确验收项缺失）。

## Minor（2）

### M1 — 新增安全/并发分支过度压在单行，难以审查状态与退出顺序

位置：HTTP:126–132、157、177、184、234；metadata:128–159、162–163；HTTP test:185、192–197。

多个状态判断、异常、引用清理和权限检查挤在同一长行，尤其 cleanup 全部字段清空及 fee/owner 释放次序不易维护。已有关键不变量注释有价值，但应把新代码按常规 Java 缩进与单逻辑步骤分行，便于后续 b2 审阅。只格式化本次新增内容/新文件；保持原 STATUS prefix 逐字节不动，不引入额外抽象或全仓格式化。该项本身不构成运行时缺陷，优先级 Minor（P3）。

### M2 — 测试输出仍有已保留的启动诊断噪声

位置：docs/evidence/iam-stage-4-o2b-f2b1-http-report.md:65；.local/iam-o2b-f2b1-http/stages/iam-stage-o2b-f2b1-red-parser/diagnostics/2026-10-21T18-55-58_055.dumpstream:2–4。

已实际读到 Boot Manifest-JAR absolute classpath / 'other' has different root；report另外说明继承deprecation。按 reviewer skill 记录为 Minor（P3），不能称日志 pristine。该信息不是业务测试失败，也没有证据指向本轮 HTTP 行为错误。保持原始文件不改，将 Windows runner诊断治理交 root 后续范围；不按 dumpstream 建议关闭 JDK classpath 检查，不为消除历史噪声重跑成功阶段。10份/各344B的全量身份由 root 审计，本 reviewer只直接读取上述一份。

## 两项诚实证据边界

**历史 RED 的性质。** 已窄读三份具名 raw 和其 txt：red-parser/raw.log:73–92 是已Running的反射 API 不存在断言（对应现有metadata test:29–33）；red-http2/raw.log:70–116 是 ClassNotFoundException，已编译测试在运行期发现实现类不存在；red-http/raw.log:70–148 则为 LoopbackAuthority 初始化时 Unix-domain/loopback harness故障。三者都不能改称编译失败；前两者只证明 compiled runtime API 缺失，不证明业务guard/畸形wire/失效授权等关键异常路径曾先红后绿。作者 report 的现行分类是诚实的。brief E60/AGENTS 的关键失败路径先行方法学证据仍有限，root应显式登记这一历史限制，不能用final绿补写为完整行为RED；不得撤回实现、重跑旧RED或伪造过去。本项是历史证明边界，不另虚构一个可通过当前源码补丁消除的运行时缺陷。

**4096 data 不可达与8192 body可达。** 已读取 data-schema-bound.json；结合七字段schema、两个128-unit owner、root id≤84、两个正long≤19 digits、固定run/kind/三个SHA，保守compact上界2080合理。维持4096 hard sink（拒第4097字节）并测最宽合法字段/+1正确；不能放宽schema制造4096合法样本。相反，8192 envelope可由空白填充达到，所以I2仍是必须补齐的可达wire验收项。未把parser、CL预拒、真正chunk读取三种证据混为一类。

## 已核验证与 root 留项

已直接读取 final 两份XML的 tests/failures/errors/skipped 与对应txt，实际为 metadata **7/0/0/0**、HTTP **11/0/0/0**；final/raw.log:67–74确实Running两类并合计18。raw:29、35、53、59为up-to-date，未要求再编译，也未运行任何测试。本报告不以绿结果替代并发/预算源码审查。

⚠ 以下不从diff或局部XML推定通过，交root完成有限产物审计：完整621 source与剩余617字节闭合、STATUS前缀机械身份；cleanup实编译20main/17test及完整epoch与final同73d316...的绑定；compiler input/created lists、有限target overlay341/logical2223；私有Git baseline与首次raw binarypatch check/apply各1、完整621 replay byte/names；owninventory17113与live source/target分列；27 child实际退出与collector join全量证明；8执行索引及前置certificate状态。本轮没有重hash这些全集或重新执行其工具。

⚠ 固定Apache/Jackson库调用顺序沿冻结actual-api/log/budget补充作为依赖输入，本 reviewer没有新一轮全库/缓存JAR/开源网页审阅；不能将输入的历史窄读说成本轮完整依赖代码审计。

3MiB结论仅限上述有限组件工程模型，排除common、既有STATUS mapper、TLS/JCA/JSSE/X509/provider/native、类加载和全JVM；两Owner账本6422784不是进程总内存硬界。F2b1仍未装配、EVENTS仍关闭；b2 G/E、c1材料、c2 SSE、真实engine/PG全链、生产launcher及生产容量都不在本轮，不当成遗漏或签收。

## 交还

独立 review 完成并冻结。源码、target、作者证据均未改；报告写权及 review 权交回 root。I1/I2 修正和受影响focused证据完成前，Task quality 保持 Needs fixes。
---

# I1/I2/M1 修正独立复审（追加，原结论历史保留）

**Spec compliance：Spec compliant（本轮 I1/I2/M1 源码与测试要求）。Task quality：Approved。** 原 I1、I2 与 M1 已闭环；本轮新发现 Critical 0 / Important 0 / Minor 0。当前累计未闭合项为 Critical **0** / Important **0** / Minor **1**：原 M2 Windows runner 诊断继续由 root 留账，不属于本次源码修正。下面列出的产物证据 ⚠ 仍须 root 独立判断；此源码 task review 不替代产物 gate，不作 F2b1 Accepted 或生产签收。

## 范围与读取记录

追加前实际核验原报告为 **15291B / SHA256 4ef225344b004d74d095590e491629949a1473eca1d6c236a37acb36eb6ac8be**。原15291字节前缀完整保留；本节没有覆盖原 Needs fixes 历史。

本轮仍遵循同一 AGENTS / implementation-brief / contracts 与 SDD task-reviewer 的先规格后质量方法。所有 shell cwd 为 G:/207/项目/算法重组工具/代码/agent-platform；未访问相邻旧工程，未写任何源码、target、作者owned、执行索引，未运行测试/Maven/PG/Git/replay/provider/probe/javap/浏览器。

完整阅读并实际核对：

| 输入 | bytes | SHA256 |
|---|---:|---|
| docs/evidence/iam-stage-4-o2b-f2b1-fixes-brief.md | 9590 | aff8c7c0e756dab7d4280e6fdb4d51c2966a69379a0536756677f1d8eff7510a |
| docs/evidence/iam-stage-4-o2b-f2b1-fixes-report.md | 14334 | 19c38b9cccf84b37f98e3f84ce60287c978d54c86c6345933addd664af00d0ac |
| docs/evidence/review-iam-stage-4-o2b-f2b1-fixes-package.diff | 174182 | 35078367b16e4772f91a50a5be4e82d87ebaafd76b5552bb4bd8cbfb6c268396 |

complete-context 包 **2390 split lines**，语义审阅按不重叠区间 **1–260、261–520、521–780、781–1040、1041–1300、1301–1560、1561–1820、1821–2080、2081–2390（EOF）** 完整完成，无截断。包含全部四文件上下文；未另重读 changed files。之后只对同包机械换算新行号、重建metadata前后token流，不重复开展第二轮源码全文审阅。本轮没有新增 diff 外业务依赖读取；common/TLS 的既有窄读与冻结补充边界沿原报告保留。

## I1 闭环：唯一 cleanup、失败重试与一次退账

位置：platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalRunEventsHttp.java:322–390、392–431；lookup退出保障:159–171。对应包813–934、589–604。

- cleanupActive 选举与六个引用快照均在同一 Session 锁内；其余 caller 在 while(cleanupActive) 中按自己的绝对剩余期限 timedWait。它们不会进入同一 transport 的 handle/timer等待，也不会再通过两次读取可变字段制造原 I1 的非空检查/解引用竞态。
- 当前 operationThread 才能在 active lookup 期间清理；外部 awaitClosed 先等待 active 结束。cleanup调用只使用 closingHandle/Request/Alarm/Timer/Client/Manager 局部引用；其他close/abort最多发起幂等取消，不清这些共享字段。
- handle.awaitClosed、timer.awaitTermination、client/manager.close 全在 Session 与 guards 锁外。QuietConnection.close 会回调 stopTransport，但此时没有外层 Session 长锁，deadline.abort仍可取得锁。guards只在短检查/注册/移除及最后clear时持有，未见 guards→Session 的反向嵌套等待。
- 超时与 InterruptedException 返回 false、保留原引用与费用；中断标志恢复。RuntimeException也返回 false，finally必定清cleanupActive并notifyAll，允许后续caller接续。只有真实handle关闭、timer终止及库close返回后才清transport引用并发布transportClean。
- awaitClosed在确认lookup退出、transportClean之后以同一锁检查closed，依次关闭Opening/facts、fee、Owner并标记closed，其他caller只观察已完成状态，未见双退账。lookup的finishTransport外层增加嵌套finally，即使清理失败也会清active/operationThread并通知等待者，不以其结束替代真实transport退出。
- cleanup仅增加一个布尔状态及六个引用快照，未新增生产worker/队列/缓存或复制payload；原3MiB固定组件模型的G组与margin足以容纳该有限增量。此判断仍不是进程RSS/全JVM/TLS堆证明。

新测试实质覆盖：HTTP test:635–724 的 TerminationGate/concurrentClose 使用真实 executor worker、真实 acquire handle 与机械socket.close gate，分别控制物理关闭和timer实际退出。两个Owner计6422784B、短等待false、第三拒绝、timer等待调用数恰1、释放后两个caller正常结束、剩余一Owner3211392B、重复close不重复退款都有断言。测试不是概率重试，也没有制造真实TLS已验证状态或授权proof。

HTTP test:726–774 分别用中断及第一次termination wait的机械RuntimeException，检查cleanupActive已释放、timer原引用与3211392B仍保留、真实worker尚未退出；放行后下一caller可正常关闭且账本归零。它明确是清理时点的机械测试，真实网络退出仍由本次HTTP全类及I2向量补足，不把机械未连接socket当TLS正例。

已核新RED原始raw和XML：`.local/iam-o2b-f2b1-fixes/stages/iam-stage-o2b-f2b1-fixes-red-concurrent/raw.log:70–108` 实际Running一例，失败为 `Only the elected caller may await transport termination`，expected1/actual2；不是类/API缺失，也不是声称已经复现NPE。后来加入物理socket阶段和retry用例的epoch变化在report中明确保留，不将新GREEN源码冒充当时RED源码。**I1关闭。**

## I2 闭环：真实TLS下的可达body边界

位置：HTTP test:776–838；Peer观察关闭:238–249；生产bounded read与先abort:128–145。对应包2304–2366、1766–1776、553–570。

新增bodyBounds在同一实际Session/Opening/原Q/受信TLS/Apache/严格parser路径构造：

1. 合法envelope加JSON空白，先断言UTF-8恰8192，再通过CL8192发送。
2. 同样8192正文通过 `Transfer-Encoding:chunked` / `2000` chunk及合法终止块发送，无CL。
3. 8193正文通过 `2001` chunk发送，无CL，并附后续 `1000` chunk头，但不提供其payload或结束块。

前两项逐字段断言run/owner/root/versionId/versionNo/accepted/initial，以及原Q对象仍相同。第三项断言503、facts=null、stopped=true，完整lookup少于1500ms，早于2秒hop timer；结合前两项合法schema与唯一变化为body长度，该向量覆盖实际bounded-read超限，而非CL预拒或parser-only调用。

三个向量均通过既有beforeOutput接缝观察真实handle/timer，不替换连接或proof；lookup后检查handle.awaitClosed(Duration.ZERO)、timer.isTerminated，并在测试侧close前等peer实际观察EOF/reset。未知尾部从未提供，源码仍先stopTransport再框架response/entity关闭；这足以支持本profile下超限不因drain等待未知尾部的结论，不扩大为测量所有TLS内部read。原Q逐字节Authorization只出现一次、connect一次、grantCount不增、第二lookup拒绝、close前费用仍持有及显式close后归零也均有断言。**I2关闭。**

合法compact data保守2080、4096合法DTO不可达的边界未改变；本次没有通过放宽字段规则制造4096样本。原parser8192、旧CL8193预拒与本次新wire向量保留各自历史分类。

## M1 闭环与范围检查

关键生产状态、try/catch/finally、清引用、费用释放及测试断言均拆成可审阅的顺序行；清理协议新增注释解释选举/快照、保留重试引用和锁外物理等待。未引入无关抽象、公共proof工厂或生产测试gate。原请求guard、TLS配置、头/body限制和parser语义从完整包看保持。

metadata两个文件仅空白调整。除完整diff人工核对外，本reviewer对包内old/new独立进行保留完整注释、字符串/字符字面量和操作符的词法比较，两文件均相等；主metadata为3771 lexemes，测试因独立scanner把hex字面量分段计为2082（前后相同），作者具名证明按完整Java数值token计2080，计数口径差异不构成内容变化。原STATUS内容在包内仍为unchanged context。

已读取 `docs/evidence/iam-stage-4-o2b-f2b1-fixes/metadata-token-and-status-prefix.json`：作者记录metadata token3771/2080一致、STATUS前6168B完全不变；`format-transformation.json`记录最后格式步骤token等价。这些是具名证据，实际live字节与manifest绑定仍交root，不把本包token比较扩大为全621source检查。

HTTP test:67 已将child目录切至 `.local/iam-o2b-f2b1-fixes/peers/`，符合避免写旧27 peers的要求；包内四文件无新增业务范围。**M1关闭。**

## 已核测试记录及保留限制

本reviewer只读取已存在记录，没有执行或重跑测试。对以下三个stage实际raw的Running/编译结果及XML用例名和计数作了窄核：

| stage | 实际结果 | 独立核对的意义 |
|---|---|---|
| fixes-red-concurrent | 1 test / 1F / 0E / 0S | 两个caller进入timer await的行为RED；main up-to-date，testCompile17 |
| fixes-focused2 | 3 / 0 / 0 / 0 | 并发cleanup、cleanup retry、真实TLS body三个具名方法；raw显示main20/test17 |
| fixes-final | 14 / 0 / 0 / 0 | 实际HTTP14方法均列于XML；raw四处up-to-date |

focused中使用 `+` selector被wrapper拒绝，无Maven/Running/XML，catalog与报告均保留，不计成测试执行。focused3加final14为17次绿色执行、14个final独立用例，不能称17个不同final用例。未重跑旧18或metadata7；token/prefix等价用于保留原兼容语义，不能伪称本轮重新运行metadata7。

原M2诊断仍在：fixes report“实际命令、测试与编译”段保留3份Windows classpath dumpstream和既有deprecation。按既定责任继续root留账，本轮不新计一个重复Minor，不称输出pristine，也不要求改历史记录或绕过JDK检查。

⚠ **source/编译/target身份**：root需完成完整621source与617未变、live STATUS6168B、完整epoch7ce27a...与focused2/final绑定、compiler input/created lists、overlay143/logical2226核对。本reviewer看到了actual compile/up-to-date和XML，没有独立重hash这些全集。

⚠ **patch/worktree历史**：已完整读作者对辅助replay-index add与plain apply交叠的失败说明。主baseline before621 PASS、首次plain check/apply exit0、after621以及raw4 pre/postimage是不同证据，不能把辅助index的失败改称PASS；报告明确没有独立preapply worktree全manifest。root须对实际worktree谱系及只读binary image补证判断，不从本次源码Approved推导replay gate通过。本reviewer未执行Git、解码或重放。

⚠ **control/owned/process**：5841 owned、18 actual child exit/collector join、privateGit和binary images、八索引由root审计；本reviewer没有全量核这些集合。两次高层metadata stderr仅在transcript、没有owned原始raw的限制继续保留，摘要不等于原raw全集；不得因本报告Approved消除此缺口。

⚠ **继承权限与模型边界**：原历史API缺失/issuer harness RED仍不能变为全业务失败路径RED；本轮仅为I1新增了真实行为RED。3MiB仍只属固定HTTP组件工程模型，不涵common/TLS/JCA/native/wholeJVM。F2b1未装配、EVENTS关闭；b2 G/E、c1材料、c2 SSE、真实engine/PG全链及生产launcher/容量不属于本轮，未据此扩大签收。

## 本轮交还

原两个Important及M1源码/测试修正已通过独立复审，剩余Minor仅继承M2；源码Task quality为Approved。产物/历史证明留项维持⚠交root。本报告追加部分冻结，review写权交回root；原15291B前缀继续作为当时Needs fixes的不可覆盖历史。