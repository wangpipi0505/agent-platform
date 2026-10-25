# O2b F2c2a SSE task review

## Spec Compliance

**Issues found.** 仅审查未装配 C2a 组件，不是 whole-branch/生产接线验收。存在两个启动边界缺口：原 Q 失败没有关闭已取得的 Session/Owner（I1），入口没有校验调用时仍为原真实 REQUEST 的 processor scope（I2）。正式 abort-profile amendment 的“缺 scope 默认拒绝”和原请求线程 setter 约束尚未完整落实。

有效要求按顺序完整读至 EOF：`iam-stage-4-o2b-f2c2a-sse-brief.md`、actual-api-supplement、decoder-type-amendment、abort-profile-amendment。后两份明确覆盖原五/六路径与旧 abort 实现候选；最终七路径本身没有要求矛盾。作者 report/handoff 仅作为待核声明，未用作者自审或已选架构降低问题级别。

其余本次可核内容符合要求：MaterialVerifier 的 raw 与 canonical 共用 writer 门，raw 保留 lexeme/escape（`platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalEventsMaterialVerifier.java:401`）；Http 保留原 initial cursor 并仅在真实 window 确认后更新 publicAfter（`platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalRunEventsHttp.java:234`）；SSE reset 优先、status 一次、无合成 terminal、固定 backing 与 8192 chunk、flush/drain 后确认（`platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalRunEventsSse.java:103`、`:180`、`:215`）。Decoder 与 Budget 变更保持窄范围；无自动扫描/生产路由装配。

### Cannot verify from diff

- source627→630、其余623 exact、原设计/四契约/八索引 immutable、完整 primary/replay 名字及 bytes、有限 target overlay、owned inventory 冻结：交 root 的有限 artifact 审计，不以本代码审查代替。
- final-candidate-safe 29/0/0/0、38 final children、历史117 child 的 waitFor/collector 实际退出、各失败 epoch 保留，以及 source/target 与这些执行证据的同一性：已读 report/handoff 声明，未重跑或逐一重读 raw/XML。由 root 闭合。
- 固定 1MiB baseline 的预收费、一次 lifetime、profile 检查、wrapper/bridge 字段和 waiting 移除可从代码核对；1218 wrapper 计数的全部容器保留集合/瞬态、VM 实际对象布局与排除范围不因本审查成为 RSS 或生产容量证明。root 应将正式工程 profile 与实际固定依赖身份一起保留，不能仅以账本算式替代该边界。
- C2b 的真实生产 ingress/assembly、独立 gateway-engine-issuer-PG-TLS 链以及 C2c 浏览器 null/long 行为均不在本 task 的可验范围；不能据此开放公开 EVENTS。

root 随后通知上述机械证据已由独立 FIRSTEXEC 六 phase PASS 闭合：`.local/iam-o2b-f2c2a-root-check.json`，15701B，SHA256 `ac26a8bbaeddaad5cc88258cbdca27920ebfb5f846c45d557955a7dc2a9dfa82`；范围包括15713个 distinct physical、24 epochs、原生 Git627→630/623 exact、check/apply 各一次0、finite399+3 resource copy=402/logical2295、final29/0与38 children、全部历史117 wait/collector，以及原 C1/八索引/设计边界。此处记录 root 的审计交接，reviewer 未重复执行；机械 PASS 不关闭 I1/I2，也不扩大容器资源或生产接线结论。

## Strengths

- `PortalEventsMaterialVerifier.java:401` 的 raw 输出在借 payload 前进入与 canonical 同一 writer 门，借用关闭后才退出 writer；逐字节去除字符串外四种 JSON 格式空白，避免数值重编码。对应 `PortalRunEventsSseTest.java:85`、`:99`、`:124` 有真实材料链、双向重入拒绝、异常后再进入、Unicode/null/精度与可达上界断言。
- `PortalRunEventsSse.java:215` 每次真实 ready/write/flush 周围检查同一 window，event cursor 在完整写出、flush 与 ready-drain 后确认；`PortalRunEventsSseLifecycleTest.java:284` 的实际 pending flush 测试既测继续 drain，也测 generation/revoke 后 cursor 不前进。
- `PortalRunEventsSse.java:264`、`:271` 与 Http 的 awaitClosed 门把 completion、heavy helper、action、worker 实际 join 和引用 detach 放在旧退款之前。测试分别暂停 heavy、轻 shell、completion、action，能区分整个 callback 与已退出的 heavy helper（LifecycleTest `:267`、`:300`、`:325`）。
- `PortalRunEventsSse.java:315`、`:384`、`:405` 用原 socket 锁覆盖 action/process/recycle，以同 request token 失效抵挡 keepalive 和 processor 跨 socket 复用；测试使用真实 connector/Valve，并实际确认复用及 forced-release 的 missing-completion 持费（LifecycleTest `:347`、`:374`）。没有把 forced invalidation 冒作 onComplete。

## Issues

### Critical

无。C0。

### Important

**I1 — 原 Q/lease 启动失败遗留 Owner，两个失败即可耗尽入口。**

位置：`platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalRunEventsSse.java:37`（lookupMetadata/beginSse 在统一失败清理之外；try 从第43行开始），以及 `platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalRunEventsSseLifecycleTest.java:115`。

`start` 接过已由 begin 取得 Owner 的真实 Session，但 metadata 拒绝、网络失败或 beginSse 拒绝会直接抛出，不返回 Handle，也不执行 session.close。Http.lookupMetadata 的失败路径只 abort，结束 active/transport 后仍保留 Owner；closing 仍为 false，后续单纯 awaitClosed 不会启动清理。实际新 Servlet fixture 的 catch 也只记录 failure。因此这不是需要 action/completion 证据的受控持费：此时尚未建立异步生命周期，已退出的 Q 资源仍永久占两个 admission 槽。正式输入没有约定 caller 必须补救 pre-handoff Session 的免责契约。

修正：从接收 Session 开始给出一致的失败所有权边界，把 lookupMetadata/beginSse 纳入失败关闭路径；在异步/lease 已建立后继续遵守现有 completion/heavy 门，不能统一粗暴退费。新增真实 Q-403 和 Q transport failure 的 focused vector，断言未返回 Handle、无 setter/reader 残留、原 Session 有限关闭并恢复 owners0/global baseline，重复失败后第三正常请求仍可准入。现有 bad-receipt/late bootstrap 测试发生在 Q 成功之后，不能覆盖此路径。

**I2 — 已 mint 的 request attribute 不证明调用时仍在原 REQUEST 作用域。**

位置：`platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalRunEventsSse.java:33`、`:314`、`:332`。

ContextValve 在真实 REQUEST 中 mint token，但 start 只检查 attribute 的私有类型，arm 只检查 armed/used/response；二者均不核对当前 bridge scope、origin processor/socket 和原请求线程。将这个真实 request/response/Session 交给独立应用线程后，attribute 仍存在，代码仍可启动 worker 并在该线程调用 setWriteListener。后续 Protocol.verify 不能补救这一点：它只检查 connector profile。该调用越过正式要求的“缺 scope 默认拒绝”，也重新引入已明确禁止的 off-thread setter 分支及其同步执行/锁序风险。

修正：在任何 Session 消费/异步生命周期动作之前，验证当前真实 bridge scope 与该 token 的 origin/原 socket/当前 REQUEST epoch 一致，并确保入口在原请求线程中；失败须配合 I1 安全关闭所接管资源。token 不得因 caller 持有 attribute 就成为可跨线程调用的许可。新增真实 Servlet 将 start 转交独立 worker 的 focused vector，确认立即拒绝、没有 setter/callback 写出且 Owner 有限归还；同时保留现有原请求线程成功向量。无需重跑整个历史矩阵来证明这一具体拒绝边界。

### Minor

**M1 — 测试输出尚有已报告噪音，不能称为 pristine。**

位置：`docs/evidence/iam-stage-4-o2b-f2c2a-sse-report.md:94`；隔离容器 teardown 位于 `platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalRunEventsSseLifecycleTest.java:132`。

作者明确报告 Maven encoding/deprecation、Tomcat shutdown 线程及模块访问警告。本 reviewer 未重跑以确认这些原始警告；它们仍应作为跟踪项留在证据中。后续具名区分已有构建噪音与真实未退出线程，在授权的构建/test 配置范围内消除可修噪音；不要屏蔽日志或把 child 已退出推导成完整容器资源 join。此项本身不否定已报告的 29 项断言结果。

## Assessment

**Task quality: Needs fixes.** C0 / I2 / M1。核心编码、cursor 与退出门的结构及真实测试较充分，但启动入口的所有权和线程约束尚不封闭；应先修复两个 Important，再由 fresh task review 与 root artifact gate 联合决定有限 C2a 签收。

## Read coverage and validation record

- cwd 始终为 `G:/207/项目/算法重组工具/代码/agent-platform`。仅本报告写入；未运行 git、测试、PG/provider/replay，也未修改源码/target/作者 owned/八索引。
- dispatch、四份正式 requirements、SDD skill/task-reviewer template、作者 report/handoff、root package metadata 已完整读。package 实际219255B，SHA256 `8431eadcf04f82375f8f9330865d32389d52e445ba7123928658c989cc8bb31b`；按不重叠区间 1–420、421–840、841–1260、1261–1680、1681–2110、2111–2530、2531–2802、2803–3060、3061–3345、3346–EOF 读取一次。最后实质行3522，splitLines3523包含末尾空 split；七个 Java full-context 均未另读。本文源码行号由 hunk 的新文件计数推导。
- 具名依赖风险 R1（控制协议漂移）：只读 unchanged `engine-runtime/src/main/java/cn/agentplatform/engine/application/PortalRunEventsService.java:271`–289，核对 status/reset JSON 字段与原因集合；当前 C2a 字段一致。先前误用 platform-engine 路径失败；一次为定位文件名的 rg --files 扩展到了本仓 evidence 路径，已停止，只输出路径，未读取其中 source 内容。
- 具名依赖风险 R2（socket/action/recycle 锁序、timeout 与 wrapper 保留边界）：读本仓固定 Tomcat10.1.41 官方缓存 identity；SocketProcessorBase 全部（run 最外 socket 锁），SocketWrapperBase 151–166、408–431（原 lock 与 release-before-doClose）；AbstractProtocol 825–852、877–906、957–982、1089–1123、1181–1238（process、waiting、release/cache）；AbstractProcessor 681–719（timeout dispatch/generation）；AsyncStateMachine 271–305（ASM monitor 内 onComplete）。这些范围支持 wrapper 使用原 socket 锁、明确移除 waiting 与原 timeout 委托；未进行容器全源码审计。
- I1/I2 的必要 focused validation 已向 root 协调，仅提出，不执行。现有作者成功测试不重复；最终矩阵和子进程物理证据交 root 核验。
- 报告完成后冻结，唯一报告写权交还 root。

---

# C2a fixes delta re-review

## Spec Compliance

**Issues found.** 原 I1 的 Q/lease 失败接管、原 I2 的请求线程/scope 校验已获得针对性修复；新的 allocation gate 主要机制正确。但正式“无独立、无界的 owned inert history”要求尚未闭合（F-I1），且新 pre-async 失败清理在 frame 断边前退款（F-I2）。因此本次不能将原报告翻转为 Approved。

本次有效范围是两个既有 Java 路径的 delta，原七路径 full-context review 继承。已完整读 fixes brief、正式 wrapper-retention amendment、作者 fixes report，以及两份 frozen source-only retention/slot-gate analysis。正式1536×512B gate覆盖旧1218估算；analysis中的早期3072/256B只是已被覆盖候选。作者给出的 externalReturnValueBoundary 不是正式要求；root也明确没有另行批准 caller 返回值免责约定。

### 原 findings 的闭合情况

- **原 I1 的具名 Q/lease 边界已修复。** `PortalRunEventsSse.java:34`–77（delta39–93）将 token验证、lookupMetadata、beginSse 放在接管 Session 的同一异常边界；state 尚未建立的失败会 close Session。真实失败测试在补救 finally 之前检查 awaitClosed/owners，并在两次失败后再启动正常请求（LifecycleTest `:250`，delta627–641）。该结论不覆盖下述已有 State/frame 的 pre-async 清理缺口，也不把 malformed Q403 fixture 误记为合法业务403覆盖。
- **原 I2 已修复。** `PortalRunEventsSse.java:336`（delta237–240）在 Q/async/worker 之前同时核 REQUEST dispatcher、原 Thread identity、当前 private bridge、origin/current slot/原 socket、实际 request/response facade 及 token未使用；worker转交、include、missing-token向量在真实 Tomcat/Valve/request上检查Q connections=0，无setter（LifecycleTest `:266`，delta643–654）。未创建通用 caller permit。
- **原 M1 仍保留。** encoding/deprecation、Tomcat shutdown线程/模块访问警告没有被本次范围扩大来消除，也未被报告成 pristine。

### Cannot verify from delta

- 原始630→最终630两修改/其余628 exact、post-finalizing最终manifest、两段实际Git/check/apply/full names/bytes、finite400/logical2297、保护controls/owned冻结属于root独立artifact gate。本 reviewer不重做该审计；root通知新fix artifact PASS不等于代码签收。
- 13/0（14child）、7/0（9child）、2/0（2child）是三个不同source epoch的报告结果；不得相加冒作最终同场22项。全部历史31child waitFor/collector及RED保存由root机械核对；本次未重跑测试。
- 作者ledger引用的configured Temurin21.0.12+8及layout flags/source hashes是本次固定VM证据输入；字段级算账不建立其它VM布局、RSS、GC及时性或生产容量结论。F-I1闭合前，即使416B单bundle算术成立，也不能宣称所有相关owned对象人口已由1536 gate覆盖。
- C2b生产接线/真实gateway-engine-issuer-PG-TLS链、C2c浏览器null/long，以及全平台验收仍在范围外。

## Strengths

- `PortalRunEventsSse.java:400`–408（delta305–313）统一add/removeWaitingProcessor的original-delegate key，拒foreign owned wrapper并保留plain Processor身份；实际factory/recycle测试双方向与super调用做round-trip（LifecycleTest delta668–669）。11个Processor SPI及原timeout/generation委托未被改为NOOP。
- `PortalRunEventsSse.java:409`–434（delta314–339）以同monitor在所有owned wrapper/reference构造前预留FREE槽，实际superfactory每次一次，任何后续异常烧槽。TRACKED只有private queue返回同index/state/reference对象才归还；BURNED通知不退款。drain helper不返回引用、没有probe/嵌套准入，完整返回后才使用credit。没有公开queue/ref、手工clear/enqueue、生产GC或额外reference线程。
- `PortalRunEventsSseLifecycleTest.java:279`（delta656–684）用真实Processor强根做1535＋两线程最后一槽竞争，观察一次成功/一次拒绝；construction与registration两故障点实际烧掉两槽。自动通知reuse单独观察，最多一次test-only GC且可报告INCONCLUSIVE，没有伪造collector通知。确定性容量门与GC进度证据被正确分开。
- `PortalRunEventsSse.java:340`、`:355`及recycle old-null分支（delta241、257、383）补全reject/每REQUEST/complete/recycle的五引用单向断边；原socket捕获、取锁后重验和完整action保护保留。Handle.detach及stop的finalizing门避免长期Handle直接保留State，也避免旧close快照在最终清空token后新建action；这解决了反向引用风险的一部分，但没有解决F-I1的对象人口范围。
- 依照ledger的保守header16/reference8/align8逐字段核算：wrapper56＋SlotReference56＋RequestSlot64＋State168＋两个monitor32＋Handle40＝416B，单份小图可放入512B；固定array/bridge/queue等25944B在128KiB内。这里确认的是单份字段与固定数组，不是接受每delegate只存在一份独立历史Handle的前提。

## Issues

### Critical

无。C0。

### Important

**F-I1 — finished Handle切断反向图，但未满足无独立无界owned历史的正式预算约束。**

位置：`platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalRunEventsSse.java:43`、`:78`、`:320`（delta56、94–116、219–221）；`docs/evidence/iam-stage-4-o2b-f2c2a-fixes/allocation-ledger.json` 的 bundleObjects/ externalReturnValueBoundary。

每次成功start创建一个新Handle。完成后只将它的state清null并留下after/terminal，caller仍可保存所有互不相同的Handle。同一cached delegate可顺序完成任意多请求，仅占一个processor bundle；每次Owner退出允许下一次请求，delegate的phantom gate也不跟踪这些历史Handle。故持续强可达的40B owned Handle人口既不受两Owner约束，也不受1536 bundle约束；“没有内部history collection”不足以排除独立caller root。

正式amendment要求“no independently rooted unbounded owned inert history”，没有允许把adapter创建并返回的Handle自动变为免费对象。作者新提出的caller-return-value exclusion是未获授权的计费边界改变，不能用null State证明剩余Handle本身已在512B或129024B margin内。更不能由该排除推导所有晚到调用栈里旧State的小尾部均已自动有界。

修正：明确闭合返回对象的真实所有权和人口机制。若仍坚持当前正式范围，应采用确实有限/被独立收费且不误复用旧请求身份的返回对象生命周期；若产品决定将纯标量返回值正式转移给caller并排除其保留，需要root明确裁定与修订正式边界，同时继续证明Handle不能保留State/token/protocol/重图，以及晚到调用的State尾部有明确上界。不要仅追加作者免责声明或用GC及时性弥补。可用少量真实顺序请求、同processor复用且保留旧Handle展示计数关系，无需全矩阵或大规模RSS测试；当前缺口已有源码构造证明。

**F-I2 — startAsync失败时已分配frame仍在State上，先释放lease再退出清理栈。**

位置：`platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalRunEventsSse.java:42`–45、`:68`–71（delta55–58、84–87）。

State及其270374B Frame在request.startAsync之前创建。如果真实请求不支持async而startAsync抛IllegalStateException，state非null、async为null，catch直接lease.release然后session.close。此路径未清State.frame、Handle/State关联或其它已分配引用；start的state局部仍可能保留这张图，旧Http却已允许退款/退Owner。依赖JIT把最后使用后的局部立即判死不构成固定源码生命周期证明，也违反原“frame backing留用期间仍收费”的不变量。现有Q失败覆盖的是state==null，bootstrap错误覆盖的是已经开始async，二者都未走这个分支。

修正：把未启动async的已构造State收尾做成窄helper，在释放lease之前先切断frame及所有非必要重引用/返回句柄关联，并让携带重图的helper真正返回后再退费；不存在worker/IO/completion时不应伪造onComplete。随后再由Session.close完成实际transport/timer退出。补一条真实Servlet asyncSupported=false或等效真实startAsync拒绝向量，观测pre-async State已detach后才退款、无worker/setter、Owner有限归baseline；不要靠fixture finally先关闭来掩盖顺序。

### Minor

**F-M1 — Q403向量在HTTP framing阶段早拒，未证明合法业务403分支。**

位置：`platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalRunEventsSseLifecycleTest.java:563`（delta748），以及`:256`–258（delta633–635）。

fixture发送403但没有Content-Type、body为空。原Http.checkResponse要求application/json，早于RunObservationMetadataClient.parseEventsFailure，因此该向量实际验证畸形响应失败清理；assertNotNull(failure)未区分其503 unavailable与合法403/有限业务错误code。统一catch清理的实现已有其它失败证据，故此项为Minor，但报告“真实Q403”不能当作合法拒权解析分支已覆盖。

修正fixture为现有严格ApiResponse允许的403 body并带正确Content-Type/长度，断言原403状态及允许的错误code，同时保留当前Owner归还断言。只补该focused向量，不为改报告重跑已绿集合。

**M1（继承，不重复编号）— 原测试日志噪音继续跟踪。**

位置：`docs/evidence/iam-stage-4-o2b-f2c2a-fixes-report.md` 的“限制”段；原review M1已给出具体噪音来源。未改POM/运行配置去掩盖警告，child实际退出不等于完整container同步join。此项不是本次新增的资源退出失败断言。

## Assessment

**Task quality: Needs fixes.** 当前剩余 C0 / I2 / M2，其中Important为F-I1、F-I2；Minor为继承M1及新F-M1。原Q失败与原REQUEST校验、delegate key及phantom gate本身有实质改进，但返回值范围与pre-async退款顺序仍阻止本task有限签收。

## Delta coverage / validation / handoff

- 两路径package一次按1–260、261–520、521–EOF互不重叠读取至末实质行782（split783含末尾空项），65410B，SHA256 `3a7ec00bc817141909d4a127f68c0fb9b544e30fba962cf9ef55a49fd502b41f`；未重读changed source或原七文件package。引用的source行号按新hunk计数，另给delta行号便于复核。
- 已读dispatch、两新正式要求、14148B作者report、两份frozen source-only analysis、allocation-ledger.json与jdk-reference-layout-source.json。首次并行note输出超过工具总输出上限，随后补读缺失note范围；不将截断输出冒作完整阅读。
- 唯一新增unchanged业务依赖风险核对：Q403是否达到合法业务错误路径。读 `platform-gateway/src/main/java/cn/agentplatform/gateway/application/RunObservationMetadataClient.java:167`–215，确认parseEventsFailure必须先有严格ApiResponse/code；Http framing顺序继承原full-context review，不另读Http。
- 原socket/recycle/timeout依赖核对和原review覆盖继承；未进行无关扫库。JDK源码字段身份和布局诊断只作有限ledger输入，没有运行新的VM/provider/RSS测试。
- F-I1/F-I2及Q403疑点已向root协调；本review未运行任何测试、PG/provider、git/replay或artifact audit。root明确无额外caller返回值免责要求，并等待完整清单统一修复。
- 只追加本报告；原10840B prefix保持不变，source/target/作者owned/设计/八索引无写入。追加完成后冻结，唯一report写权明确交还root。
---

## Third-wave retention-anchor four-path delta task re-review

### Spec Compliance

- **❌ Issues found.** The new shared-population and preasync changes implement the previous F-I1/F-I2 remedies, and the Q403 fixture now exercises the intended business failure. One Important remains in the claimed closed-shell proof: Http can release the original heavy-resource fees while another Http cancellation/cleanup helper still owns transport snapshots. The literal Session anchor bounds the number of shells, but does not make those heavy snapshots fit 1792B. See R-I1 below.
- This is the original C2a task's third-wave delta gate, not a whole-branch or full-engine acceptance. Binding inputs are `docs/evidence/iam-stage-4-o2b-f2c2a-retention-fixes-brief.md` and `.local/iam-o2b-f2c2a-shared-population-amendment.md`, with inherited original requirements. The formal amendment explicitly supersedes the previous 1MiB/512B/processor-only/two-path restrictions; the review uses one shared1536 population,1792B per credit and3MiB connector baseline. It does not reintroduce the rejected caller-return-value exception or Sse-only counter proposal.
- Input delta: `docs/evidence/review-iam-stage-4-o2b-f2c2a-retention-fixes-package.diff`,90715B, SHA256 `b5dbe7808c3d7b3dd6ecdb9bf6694292e860f1f1160883d3a061d4c6a8df8ddf`; full disjoint reads1–270,271–530,531–790,791–1055 through EOF (1056 split lines including trailing empty). Original seven-path full review and previous two-path delta are inherited. Author report15531B/`9edd98918d2499e9764b4f775562cbffa3df956074d04abb9c69b68fb48bedac` is evidence of claims, not the verdict.

#### Prior findings and requirement checks

1. **F-I1 population gap closed by the new mechanism.** `PortalRunEventsSse.java:35` start validates/claims the actual original REQUEST before `createHandle`, then calls the narrow Http factory. The old Session-taking start overload is removed (delta194–210). `PortalRunEventsHttp.java:44` forSse claims the unique Session attempt before Owner/Session creation; the constructor stores final lifecycleAnchor (delta57–81,103–115). Handle claims at most one Session and State, and State keeps final Handle for its lifetime (delta250–269,278–300). A completed Handle clears only its reverse State edge. Consequently caller-retained Handle, delayed State, Session, non-static lease and Session/lease ancestor receiver all retain the same charged referent. This resolves the earlier unbounded independent completed-Handle count without excluding returned objects. This conclusion is about population; R-I1 prevents accepting the complete heavy-to-shell transition.
2. **Shared sum, not two pools.** `PortalRunEventsSse.java:386` ControlledProtocol region (delta459–565) has one Reference<?> array, one byte state array, one ReferenceQueue<Object>,1536 total FREE/RESERVED/TRACKED/BURNED credits. Both factories reserve before owned allocation; Handle failure paths burn and fence the referent. SlotReference still refers to the actual super-created Processor; HandleReference refers to the unique Handle. Drain checks type/index/object identity and TRACKED before freeing; BURNED never refunds. The private drain helper returns before admission continues or the monitor unlocks. No production manual clear/enqueue/GC, exported reference, second queue, growing history or new thread appears in this delta. Construction and draining contain no HTTP/lifecycle/join operation under the population monitor.
3. **F-I2 preasync Frame ordering closed.** Start's preasync catch calls detachUnstarted before releasing the lease and closing Session (delta236–248); the helper clears frame/output/token/session/lease and Handle's reverse edge, then returns (delta303–310). State Frame allocation is the constructor's final potentially throwing work, so a failed constructor is not published for later reuse. The real asyncSupported=false fixture observes the detach phase while Owner/lease remain charged and checks no worker/setter/onComplete, then baseline (delta752–764). The probe establishes a phase boundary and fee observation; source establishes the actual field cuts. It is not represented as a heap measurement or proof of every machine instruction.
4. **Original Q cleanup and original-request scope fixes remain.** The factory admits before Session creation, rejects repeated request-token claims, and keeps original identity/thread/dispatcher checks (delta438–445). Q or lease creation failures still close the created Session. Http.beginSse validates effectiveCursor before reserve/publication (Http:255–261; delta125–131), eliminating the separately recorded real invalid-cursor half-published lease. Existing non-SSE Http.begin remains separately governed, not a new bypass accepted as an SSE path.
5. **F-M1 Q403 fixture closed.** `PortalRunEventsSseLifecycleTest.java:710` now sends an allowed RUN_AUTHORITY_DENIED ApiResponse with application/json and exact body length; startup assertions require PlatformException403 and that code (delta858,1040). Two failures return Owner count to0/baseline before the following normal request. The previous evidence remains accurately described as malformed framing, not silently upgraded.
6. **Sse completion/helper improvements are real.** State.finish elects finalizing before any worker Thread capture, calls the original abort to wake private IO without refund, waits through a returning awaitWorker helper, rechecks worker/actions/heavy/slot, then uses a returning detachHeavy helper before releasing the lease (delta359–426). Non-elected finishers wait without copying worker. Timeout/interruption keeps retry authority; finally clears finalizing/notifies. stop does not start a new action while finalizing; nullable token captures remain single-read. This fixes the specific concurrent join tail described in the author's last correction. It does not serialize the separate Http helper paths in R-I1.
7. **Ledger arithmetic and named bodies are consistent, conditional on real cuts.** Budget changes only3145728 baseline and read-only baseline value (delta12–39); Owner rules are unchanged here. The field evidence gives Handle/ref/State/two monitors296B, Session336B, lease56B, Owner48B, map56B and Object[8]88B, core880B. The declared1312B graph fits1792B;1536×1792+131072+4×1792=2890752<3145728; twoOwner candidate197431124+3145728=200576852<201326592. Fixed graph24400B is below131072. New booleans/counters fit their listed bodies. These sums do not cover Frame/Thread/client/timer/request snapshots by counting an8B edge. Session's final shared TLS/config/budget classification is inherited from the accepted fixed profile, not inferred from arbitrary injection.

#### ⚠ Cannot verify from this delta / remaining external gates

- **Finite artifact identity and historical exactness:** root is independently checking630→630/four modifications/other626 exact, logical target2298, native Git/check/apply once, Running/compiler/resources, epoch metadata, historical children and prior owned identities. At review freeze root reported first four phases PASS and a fifth-phase explicit-common-resource metadata assertion being cached before only unfinished phases continue. This review neither repeats that audit nor calls the remaining phases PASS. The supplied final source identity is `6d4e023e2ab5f5f5fbbc3bd386bd967b67c67c546872ccb0ba408ca6f66c32d8`; a source manifest alone is not runtime or deep-graph proof.
- **Supported VM/library/shared graph assumptions:** the inherited Temurin21.0.12+8/Tomcat10.1.41 profile, conservative header/ref/alignment assumptions, fixed guards Object[8], B2 TLS/config ownership and four retired-reference-tail allowance remain the formal engineering scope. The final anchored analysis and actual-field-bodies were read, not replaced with a fresh full-heap/provider/upstream audit. There is no claim of portable JVM layout, measured RSS/full heap, deployment capacity, or GC progress deadline. R-I1 is a concrete failed transition proof, not relegated to this assumptions list.
- **Runtime interleavings not witnessed by the existing selected runs:** the current ancestor-tail verifies fields/final anchors while a real awaitClosed/reap ancestor remains after refund. It does not stall Http.stopTransport after its transport snapshot, nor establish that all competing finishTransport heavy-bearing helper frames have returned before another closer refunds. The two narrowly specified R-I1 vectors need root-coordinated implementation/validation; none was executed here.
- **External acceptance:** public EVENTS wiring, actual C2b entrance/cross-process chain, C2c four frontend consumers, whole-project acceptance and deployment remain outside this task. No eight-index/design/contract update is implied.

### Strengths

- Admission now precedes the object whose retention must be charged. The exact one-Handle/one-Session/at-most-one-State final-anchor design covers the real Session/lease/reap ancestor graph rather than assuming callers drop returned handles (Sse start delta198–210; Http delta57–81).
- The mixed population preserves exact private reference identity, burns uncertain registration, and shares the real last-slot cap. It does not replace an unbounded graph with a second hidden pool (delta500–565).
- The new tests use real Servlet/Processor behavior: retained completed Handles exhaust actual factory capacity before the third Session/Q, construction/registration failures burn real slots, and asyncSupported=false exercises the constructed-State path. Q403 now asserts status/code rather than merely any exception (delta752–773,835–865).
- Field cleanup includes materialChild/materialCheckpointForTest in Http before Owner.close, and finalizing precedes worker snapshot in Sse. These are meaningful deep-graph corrections, not just changes to a closed boolean (Http:689–702; delta359–426).
- The report preserves failed epochs, fixture corrections, original mixed INCONCLUSIVE and later OBSERVED separately. It does not sum heterogeneous source epochs into a final-head all-green count.

### Issues

#### Critical (Must Fix)

None found in this task-scoped delta review.

#### Important (Should Fix)

**R-I1 — Http heavy-reference helper lifetimes are still outside the refund gate.**

Evidence: `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalRunEventsHttp.java:567` stopTransport, `:577` abort, `:586` finishTransport, `:641` transportClean publication, `:650` cleanupActive release, `:663` reap and `:678`–`:703` final cleanup/refund. The new final lifecycleAnchor is at `:70`–`:81` and the new declaration explicitly allows only the closed shell, not the heavy transport graph. Existing selected ancestor-tail assertions are in delta819–830.

Concrete sequence, with references still needed by future code rather than relying on stale local slots: (1) A legitimate concurrent cancellation invokes Session.abort; stopTransport copies handle and request under the Session monitor at570–573 and pauses before574/575. The permission cancellation path can invoke the bound Session.abort directly; it is not necessarily inside State.stop's actions counter. (2) A second closer/completion path stops and joins the actual resources, clears the Session fields through finishTransport, observes no active operation or unreleased SSE lease and executes fee.close/Owner.close. There is no Session abort-in-flight gate checked by finishTransport or awaitClosed. (3) The first caller still retains c/r and must use them for abort/cancel when resumed. The final Session anchor keeps the one population credit, but that credit's1792B shell declaration explicitly excludes transport/request heavy graphs. Cleared Session fields and a completed physical resource do not sever another live stack's strong reference.

A second manifestation is finishTransport itself: elected cleanup captures six transport/library objects at608–613; it publishes transportClean at641 and clears cleanupActive/notifies in its own finally at650–652. Another awaitClosed can then pass finishTransport and refund before the reference-bearing helper has returned. The code offers no returning inner helper boundary before that publication. This narrower tail also fails the formal actual-helper-return rule; whether a particular optimized JVM happens to drop dead locals early is not the required fixed source proof.

The bounded dependency check of `PortalEventsTlsConnection.java:149`–`:233` shows abort schedules close and awaitClosed waits for terminated. Neither waits for an arbitrary Http.stopTransport caller holding c/r to return. This review does not reopen the accepted B2 TLS implementation; it identifies the new claimed1792B Http-shell transition's unaccounted references. The existing State.action gate and elected worker helper are good but do not cover the independent Http cancellation path.

Impact: the declared twoOwner-plus-baseline upper bound cannot be trusted at this transition. This is not a remote-code or permission-bypass finding, nor a claim of an observed OOM. It is Important because the task's explicit hard retention/accounting requirement is unproved on an executable concurrent path.

Required direction: keep the original HTTP/Owner heavy fees until all relevant transport-snapshot helpers have actually returned; gate competing refund against admission/exit of those helpers, and move heavy snapshots into an inner scope whose return precedes publishing refundable cleanliness. Preserve cancellation wakeup, actual resource exit, lock order, bounded retries and the final Session anchor; do not hide transport graphs in1792B or add caller exclusions. Root should coordinate the exact implementation rather than applying an unreviewed lock arrangement.

Necessary focused validation: pause a real cancellation after c/r snapshot while another real completion/awaitClosed attempts refund; assert original fees remain until that helper exits, then baseline. Separately exercise two concurrent closers with cleanup paused at the boundary before its heavy-bearing helper returns, ensuring the other cannot refund early. Existing ancestor-tail remains useful for the small closed graph, but does not answer either vector. No test or production change was made during review.

#### Minor (Nice to Have)

**R-M1 — Light-shell regression retains the old baseline-dependent expected total.** `platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalRunEventsSseLifecycleTest.java:538` still asserts7218374B after the material fee has exited, and`:540` writes the same stale retainedBeforeCompletion value (delta961,965). This wave increased connector baseline by2097152B; unchanged Owner/HTTP/private/SSE fees therefore give9315526B at that phase. The final-baseline assertion was updated, but this intermediate assertion/evidence string was missed. Fix both values, preferably express the assertion as connectorBaselineBytes()+6169798L so the intended component subtotal is visible. This is a deterministic regression-test mismatch, not evidence that the implementation released too much. The mandated selected matrix did not run this method; do not rerun the full29 to discover it. A future corrected single-method run is sufficient.

**M1 inherited — reported output noise remains.** The author report's final limitations retain encoding/deprecation/module-opens and container shutdown-thread warnings; historical95s fixture failures and forced bounded child exits are also retained as distinct failed epochs. These are Minor reporting/diagnostic items, not proof that the successful child processes failed to exit or that this change requires unrelated logging/POM configuration work. Root must preserve their visibility in the final roll-up. The substantive failed fixtures have later specific corrected evidence; do not count their earlier JUnit pass/INCONCLUSIVE as GC success.

### Assessment

**Task quality: Needs fixes.** Current count **C0 / I1 / M2**. Previous F-I1 independent-population, F-I2 preasync Frame and F-M1 business-Q403 findings are closed in their specific scopes. One remaining Http heavy-helper refund gate blocks accepting the full lifetime/ledger claim; the light-shell oracle and inherited noise are separately Minor.

### Evidence handling and review boundary

- Read the two new formal requirements completely, the author report, the task-reviewer template, the final anchored analysis addendum166–260, allocation-ledger.json and actual-field-bodies.json. Rejected earlier Sse-only counter candidates were not adopted. No author rationale controlled severity.
- Because Http's cleanup hunk ends mid-awaitClosed, inspected current Http263–296 (lease signal/reap/release),555–628 (stopTransport/finishTransport entry) and629–800 (awaitClosed completion). The last requested range extended past the relevant function into framing/QuietConnection context720–800; that extra bounded content was read, but prompted no broader scan. No complete changed source reread was performed. The only external-code check was the named TLS helper-return risk: symbol search in PortalEventsTlsConnection and149–233. No repository crawl, Git, test, build, PG/provider, replay or previous root audit was run.
- Read the reported epoch/child linkage and three bounded result properties. The actual corrected mixed witness says handleNotifications1/processorNotifications1/sharedFactoryReuse2/OBSERVED with explicitGcCalls1. The latest ancestor witness says ownerRefunded/final anchors/reaperAncestorObserved=true. The preasync witness says actualAsyncSupported=false,detachBarrierWhileOwnerAndLeaseCharged=true,worker/setter/completion0,finalBaseline3145728. These are existing selected observations, not independent reruns or exhaustive race proofs.
- Epochs remain separate: retained-handle RED1fail; initial14/2fail with mixed semantically INCONCLUSIVE; cleanup10/3fail; cursor RED1fail; corrected7/1fail with mixed OBSERVED; action fixture1/0; final elected-join3/0 across4children. The final3 are firstErrorAndLateFirstWindowCannotReviveRegistration, stalledActualActionRetainsTwoOwnersUntilActionAndCompletionExit and actualAwaitClosedAncestorPinsRefundedShellCredit. Earlier successful population/Q/preasync and affected lifecycle evidence is inherited only across the stated method changes; there is no final-head combined43-test claim.
- Root owns the finite audit and any subsequent narrow fixer/validation dispatch. This reviewer has now frozen this append and returned the review report's only write right. The preceding22531B/bf0a63ee944f607789b00a982eaa42e7a95f5acbfd66fef41afe2536f2d4e093 prefix is preserved verbatim; no source/target/author-owned/eight-index/design/contract was edited.
#### Root artifact gate update received before final handoff

Root subsequently reported all six incremental artifact phases PASS: `.local/iam-o2b-f2c2a-retention-fixes-root-check.json`,13177B,SHA256 `f13880dfa6af6d4356e509f1648dc3dbdeadcf64e6d03486f4a35e5d178c6c8a`;8103distinct physical members,source630/four modified/626exact,native630/all blobs,first plain check/apply once each,seven distinct epochs/40actual child wait+collector records,finite401→logical2298,C1eight/design/onlyT01 unchanged. The phase5 metadata resource-membership assertion and original helper/error were preserved; only phases5/6 resumed, with no production/test/Git/replay rerun. This root-owned result closes the artifact-identity Cannot verify item above as a separate mechanical gate; this reviewer did not duplicate that audit. R-I1 semantics, fixed-profile/shared-graph limitations, the two unwitnessed Http interleavings, and downstream C2b/C2c/full acceptance boundaries remain unchanged. Final verdict remains Issues found / Needs fixes,C0/I1/M2. This complete third-wave append is frozen and its sole report write right is returned to root.
---

## Fourth-wave Http helper-exit two-path delta task re-review

### Spec Compliance

**✅ Spec compliant for this bounded implementation task.** R-I1 and R-M1 are closed. The new cancellation/cleanup gates preserve the required heavy fees until reference-bearing helpers have actually returned; the light-shell assertion/evidence reflects the3MiB baseline. No new Critical or Important issue was found. The nonblocking historical evidence deviation and inherited diagnostics below remain explicit; this is not whole-project acceptance.

Binding inputs: `docs/evidence/iam-stage-4-o2b-f2c2a-http-exit-fixes-brief.md`, its frozen-inputs, inherited shared-population amendment, and all four root authorizations for inner RuntimeException|Error failure policy, reachable failed-cleanup retry, finite repeated fixture failures and corrected per-call nonnull witness. Those authorizations change the stated narrow policy/fixture contract; the author report is not treated as a source of exceptions to requirements.

Reviewed complete package `docs/evidence/review-iam-stage-4-o2b-f2c2a-http-exit-fixes-package.diff`,28922B,SHA256 `2efdacce8e43abba6c63310421b16c7e5016fffe5b66f30baeda84e17dfda4d2`, in disjoint1–190 and191–363 reads through EOF (364splitlines). Original full7, previous2 and previous4 reviews are inherited. Supplied final source630 identity is `6f61ed3ce5ce87fbafead1dd1434f2b1d9dc1b3c3692be81a51c19a7155e3b9d`; author report12693B/`e259829db63397a94f6bc891b5271bf0d4849b151963c37568fc40a94f09c795`.

#### R-I1 closure and concurrency checks

- **Cancellation admission precedes all transport copies.** `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalRunEventsHttp.java:570`–`:595` now checks transportClean/cleanupActive/counter saturation and increments transportStops under the Session monitor before calling stopTransportReferences. Only the inner captures c/r and hook. The outer decrements and notifies after the inner returns; it has no transport or Throwable local. The same monitor guards cleanup election, so no competing cleanup can observe zero stops while an admitted transport-snapshot helper is still live. The clean/cleanup-active rejection branch never captures a transport or probe. The Integer.MAX_VALUE check prevents wraparound; at saturation there are already admitted responsible cancellers, and all relevant heavy fees remain held.
- **Concurrent cancellation still has a responsible wakeup.** Ordinary admitted stops are not serialized behind a paused first canceller: a later stop may actually abort/cancel IO while the first retains its snapshot. During cleanupActive, the uniquely elected cleanup invokes the original handle.abort/request.cancel itself (`Http:647`–`:648`). Thus rejecting a repeated stop during that election does not omit the mechanical wakeup. If that cleanup fails before doing so, the gate reopens and later bounded cancellation/cleanup can retry while dirty fields and fees are retained; no progress guarantee is inferred for a stalled library or failed callback.
- **Cleanup publication follows actual helper return.** `Http:605`–`:631` waits boundedly while cleanupActive or transportStops!=0, preserves the original active/operationThread predicate, then elects under the same lock. All six transport snapshots, the arbitrary testhook, actual waits/closes and their exception objects live in finishTransportReferences (`:632`–`:684`). The scalar result returns to the outer before it publishes transportClean or clears cleanupActive at`:626`–`:627`. This directly closes the previous release-in-the-heavy-helper-finally window. Other closers may proceed after the outer unlocks, but the remaining outer frame contains only Session/primitive values covered by the existing anchor/shell model.
- **No heavy work under the application gates.** Snapshot copying is short and locked; hook callbacks, abort/cancel, physical awaitClosed/awaitTermination and library close occur outside the Session and guards monitors (`Http:646`–`:656`,delta157–167). The final guarded field cuts/guards.clear contain no arbitrary callback or wait. This delta introduces no State/pool/connection lock acquisition or new executor. Inherited actual decoder/lease/material/worker/action/onComplete gates remain intact; the delta does not synthesize completion or retire those fees early.
- **Failure is primitive and retains retry authority.** Both inner boundaries catch RuntimeException|Error and return false without exporting/logging/storing the Throwable; cleanup also restores interrupt and returns false for InterruptedException (`Http:590`–`:594`,`:676`–`:683`). This matches the explicit root error-policy authorization rather than silently changing the requested policy. Timeout/failure does not publish clean. After the real inner return the outer releases its stop/election gate, allowing a later bounded attempt against the original fields. stop's primitive result is intentionally not a success claim: it merely allows the admitted snapshot lifetime to end; awaitClosed still requires successful cleanup before refund. An ordinary failed stop cannot set transportClean. No same-call unbounded retry was added. Arbitrary failures outside these two inner boundaries and general VM failure guarantees remain outside this narrow policy.
- **Successful cleanup severs the new callback graph before refund.** The inner clears transportCheckpointForTest with the existing transport fields before returning (`Http:670`), then the outer publishes clean. The testhook's arbitrary capture is covered while the original heavy fees remain held; it is not reclassified as a small closed-shell reference. Its normal production value is null. Final Session/State anchors and shared1536 admission are unchanged.

#### R-M1 closure and ledger

`platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalRunEventsSseLifecycleTest.java:575`–`:578` now records the actual retained total, checks connectorBaselineBytes()+6169798L, and writes actual intermediate/final values. The existing green light-shell property records9315526B then3145728B, matching the required arithmetic. The stale7218374B assertion/evidence is removed.

The only new Session fields are transportStops int4 and nullable transportCheckpointForTest ref8. The source delta and bounded actual-field-bodies check agree; the declared aligned Session body is344B, below384B. Closed-core888B and declared1312B remain below1792B; fixed graph24784B remains below131072B. The unchanged total2890752B is below3145728B; unchanged twoOwners+baseline200576852B is below201326592B. These are the inherited supported-profile engineering bounds, not measured RSS or a portable JVM guarantee. No new heavyweight object, dynamic registry, queue, thread, pool, reservation policy or GC mechanism appears in this delta.

#### Focused runtime evidence and phase correspondence

The reported source epochs are kept distinct:

- `red-hooks-only`:2 failures; the original stop barrier did not establish the copied c/r were nonnull, and the ordinary cleanup probe was unreachable under the real active/operationThread gate. Neither is promoted to the later corrected proof.
- `red-cleanup-retry-hooks-only`:1 real behavioral failure after the formally authorized finite repeated RuntimeException setup. The rejection count was not instrumented and remains uncounted. It is not described as one-shot. Production operation finally, resource cleanup and actual completion establish the retry opportunity; no active/terminated/completion flag is written by the fixture.
- `green-affected`:5/0/0/0 across6children, source `ba94bc33eed1901d400321e00eb91f62d2879add03b0bc08d2b86ebc8b1435a4`. Four methods' evidence is inherited after the later hook-only nonnull condition; the early stop observation alone is insufficient for that detail.
- `red-stop-nonnull-witness`:1 real failure on saved old no-gate behavior plus the conditional-nonnull hook, source `b24eab69f98736392f166052d0c327a1384ae87a76fbbc8846b1611048496edb`.
- `green-stop-nonnull-witness`:1/0/0/0 on final source `6f61ed3c…`, one child. This is not a same-head combined six-test or rerun-five matrix.

The final hook at `Http:586` fires only when that call's copied c and r are both nonnull. In `LifecycleTest:375`–`:407` (delta294–326), only the named first thread pauses; a real second closer executes close/awaitClosed and the test observes held fees before releasing the first, then joins both actual threads and waits for real Session/Handle closure. The corrected stop RED property reports secondCloserReturned=true,owners0,reserved3145728 while the helper is paused; the final GREEN reports false,owners1,reserved7405824. The cleanup retry RED/GREEN show the same early-refund versus held-fee change. Their small property files were read directly, along with the light-shell observation and green raw Running/result lines.

For cleanup, the old hook was placed after the old cleanup gate opened but before its reference frame returned; the new cleanup-tail hook is before the returning inner completes and while cleanupActive remains held. This is the intended corresponding reference-lifetime boundary, not an assertion that the old and new clean-publication statements are at the same location. The new hook runs only after actual TLS/timer waits and library close return (delta158–167). A latch alone is not counted as physical resource-exit evidence. The source-backed current placement, original reviewed cleanup implementation, root hook-only authorizations and cached RED/ GREEN observations together support this narrow closure. Exact saved-old source lineage is also part of root's finite artifact gate.

The green raw logs identify the actual LifecycleTest class and5/0 versus1/0 outcomes. The catalog associates all5Maven epochs and11child exits (7exit0,4exit1), waitFor/collector records and commands; this review does not rerun or independently duplicate the complete artifact audit. The original firstError method accounts for bad-receipt/late two children. No GC, mixed-reference, old matrix, PG/provider, Git or replay was executed by this reviewer.

### ⚠ Cannot verify / external gates

1. **Finite artifact closure is root-owned.** Source630/two modified/other628 exact, actual native blobs/check/apply once,401finite overlay onto logical2298, all11child exit records, saved old hook-only/non-null lineage and protected previous owned/eight/design artifacts require the separate finite audit. The code review does not infer these from the author's self-report or source hash. Root's final result, when available, closes this mechanical item separately from code quality.
2. **Missing earliest failed-helper bytes cannot be reconstructed.** metadata-deviations.json explicitly records that the initial prelaunch root-assertion failure's helper revision was not separately snapshotted. Its exact historical bytes are not verified here and must not be described as saved. The recorded event was before Maven/stage launch or old-owned use; the later UTF8/selfreview and copy-guard failures have their own cached evidence. See Minor H-M1. No metadata-only rerun is warranted to manufacture the missing original.
3. **Supported-profile assumptions and limits remain.** The fixed VM/layout, shared TLS/config/B2 classification, fixed guards storage and retired-reference-tail model are inherited; no provider, whole JVM heap, native/RSS or exhaustive interleaving re-audit was performed. Counter saturation and Error handling were evaluated from source, not a synthetic billion-call or arbitrary-VM-failure experiment. A stalled library may retain fees indefinitely; this review approves bounded wait/failed-close behavior, not an availability or GC deadline guarantee.
4. **Task boundary remains.** C2b actual entry/cross-process integration, C2c four frontend consumers, public EVENTS wiring, whole-project acceptance and deployment remain outside this C2a review. This report grants no source/index/contract write permission and is not an Accepted publication.

### Strengths

- The fix introduces one scalar admission counter and two narrow returning helpers, directly addressing both observed reference windows without expanding the adapter framework (`Http:570`–`:684`).
- Clean publication has an explicit source-level return boundary; it does not depend on JIT dead-local elimination. Failures carry only primitive false across that boundary under the formally agreed shutdown policy (`Http:622`–`:627`,`:679`–`:683`).
- The corrected stop witness tests the actual copied locals, not a pre-call Session-field observation; the cleanup retry respects the real active-operation gate and preserves the failed reachability attempt (delta81,304–324; four root fixture notes).
- The numeric regression uses a named baseline plus component subtotal and records actual observations (`LifecycleTest:575`–`:578`). Final hooks are nullable, cleared under the fee gate, and remain within the existing field allowance.

### Issues

#### Critical (Must Fix)

None.

#### Important (Should Fix)

None. R-I1 is closed by the mutual exclusion plus actual inner-return ordering above. Previous F-I1/F-I2/F-M1 closures remain inherited; no new source defect was identified in this delta.

#### Minor (Nice to Have)

**M1 inherited — diagnostic noise remains visible.** The author's final limitations retain prior encoding/deprecation/module-opening and container thread-shutdown noise in raw/child output. A successful child exit is not represented as proof of synchronous shutdown of every container thread. Preserve this item in the eventual roll-up; it does not require unrelated logging/POM changes in this narrow task.

**H-M1 — one historical prelaunch helper revision was not saved.** `docs/evidence/iam-stage-4-o2b-f2c2a-http-exit-fixes/metadata-deviations.json`,errors[0], explicitly has originalHelperSnapshot=false. This limits independent reproduction of that one prelaunch assertion failure. The process requirement to preserve failed-helper evidence was not fully met for this event. It occurred before source tests/copy/Git and does not invalidate the separately frozen source epochs or behavioral witnesses. Keep the limitation explicit; do not claim a nonexistent snapshot or rerun successful work to replace history. This is a nonblocking evidence-process deviation, not a hidden runtime defect. Subsequent failures are separately cached as documented.

### Assessment

**Task quality: Approved** for this bounded C2a implementation review, with **C0 / I0 / M2** (inherited output noise and the historical prelaunch evidence limitation). All identified blocking code issues are closed; root's independent finite artifact gate and the stated external assumptions remain separately required before any limited acceptance decision. The approval does not erase the nonblocking procedural deviation or extend to C2b/C2c/full-project deployment.

### Review and write-right boundary

Read the complete2path package once through EOF, new formal brief/frozen inputs/four root authorizations/report, ledger, stage catalog and metadata deviations; selectively inspected the two current green raw Running/result summaries, the four relevant corrected RED/GREEN window properties, light-shell property and the two new actual-field entries. No changed source was reread separately; no unchanged dependency scan or new runtime execution was needed. Only this report was appended. The frozen42473B prefix `caeca0717826f2fc954853a20ee94706b5e8f52ae6ee62d1922693edebea7bec` remains byte-for-byte intact. This fourth-wave append is frozen and its sole report write right is returned to root.
#### Fourth-wave root artifact closure before final handoff

Root reported `.local/iam-o2b-f2c2a-http-exit-fixes-root-check.json`,8830B,SHA256 `daefb1767d0e6f9bc58761c0d4bdb91a0a5bc2a28984f311f1bae10914bd9002`:six phases PASS/6468distinct physical members;source630/two modified/628exact,native630 actual blobs/two binary patches/plain check and apply once each exit0,five distinct epochs/11actual child exit+collector records,finite401→logical2298/delta20,C1eight/design/34onlyT01 unchanged. All six checks had already passed before final result materialization hit an obsolete2297 phase-selector TypeError; the original helper/hash/error/cache were preserved and only the selector/result materialization resumed, with no repeated phase/physical/test/Git/replay work. No separate replay-before hash manifest is claimed: root's evidence is the actual1260-copy mapping, executed ev.rows(d)==B precondition,630names,binary preconditions and final actual630. This root-owned result closes mechanical CannotVerify item1 separately; it does not manufacture the missing earliest prelaunch helper snapshot or change the remaining supported-profile/task-boundary limitations. Final verdict remains Spec compliant / Task quality Approved,C0/I0/M2. Complete fourth-wave report append is now frozen; sole report write right is returned to root.