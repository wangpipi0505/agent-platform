# O2b E engine packet 独立 task review

**Issues found — Needs fixes。C0 / I2 / M0。**

审查者：`/root/review_iam_o2b_e_engine_packet`，fresh 独立 spec + quality review。作者已冻结交还写权；本报告评审冻结 E 实现，不改代码，不替代 root 的 artifact audit。审查日期：2026-10-21。

## 审查依据与范围

完整读 `.local/iam-o2b-e-review-instructions.md`、正式 `iam-stage-4-o2b-e-engine-packet-brief.md`、`iam-stage-4-o2b-e-packet-decisions.md`、`iam-stage-4-o2b-e-wire-clarifications.md`、`iam-stage-4-o2b-e-request-header-scope-decision.md` 及作者 `iam-stage-4-o2b-e-engine-packet-report.md`。后两项补充优先于原八文件范围及较早描述。使用 code-review-expert 检查框架；本次明确授权的冻结包与只读边界优先于通用技能的 Git preflight、修复询问步骤。

完整上下文包 `docs/evidence/review-iam-stage-4-o2b-e-engine-packet-package.diff`：159994 bytes，SHA256 `bfa4a79d16774b4b3dd8be4f0c7487f09b4feafeed4efafc8f7c3df30e75c0b0`。实际按互不重叠的物理行 1–350、351–650、651–950、951–1250、1251–1500、1501–1670、1671–1825 读到 EOF；1825 内容行，按 LF split 包含末尾空项为1826。没有重复读取 changed source 或运行 Git。定位使用该冻结包的 head 行号（root 从同包派生的行索引交叉确认）。

九个完整文件全部审查：RunController、PortalRunEventsService、PortalEventsPacketWriter、RunEventReader、engine application.yml、PortalChildEventsAuthorityFixture、PortalChildEventsHttpTest、PortalChildEventsPostgresTest、PortalEventsPacketCodecTest。范围符合四生产 Java、一既有 resource、三新 test、一新 support；新增五文件。source epoch 603→608/delta9 与审查材料一致，完整 artifact 集合核验由 root 另行负责。

本 reviewer 唯一写入是本报告。每条 shell 的 workdir 均为 `G:/207/项目/算法重组工具/代码/agent-platform`，未访问相邻工程，未执行作者任何测试、PG、replay，未遍历 own/target/.git。

## Strengths

- 实际 HTTP 正例通过正常 G exchange、签名 E ticket、TrustedJwtDecoder、ServiceAuthorization filter/interceptor 和真实 handler frame 后进入 PG；support 的直接 open 仅用于 PG 编码测试。private/standalone 双向 open 无 fallback；非 IAM private 在 legacy Actor/SQL 前拒绝。controller 保持三参及既有映射限制。private Accept 使用有界 MediaType 解析并拒绝参数、混合、重复及非精确拼写；普通 SSE 分支未加入 receipt/manifest。
- 私有材料复用唯一 readWindow RR 中的 initial、binding/provenance、三 root、最新 state、selected event 及其直接 parents 并集；没有新增编码 SQL 或免费深复制第二 JSON 图。完整 parent 数组保留原序，集合/字典输出排序，revision 与历史 source version 保留。grantRef/operationId 作为历史 opaque provenance 输出，未把可用 JWT/continuation 放入 WINDOW。状态十一字段、事件五非 payload 字段与原 row/scope 摘要义务分开。
- PacketMaterial 不从 public getter 导出。私有编码与 payload 共用同步 borrow/outputActive，拒绝重入和跨线程借用；sink 只能取得已收费的隔离 copy。两遍 metadata 计数/SHA 共用不可变 RR 材料和 capture；第二遍及 payload 摘要一致且 live 才写 COMMIT，随后 flush，owner 成功返回后才推进 cursor。已写 packet 的 superseded 不重读拼接；prefix 前仍只允许原有限重读。
- 固定 MIME、24B prefix、44B COMMIT、10键 header、14键 WINDOW、HELLO receipt、两种 CAPACITY code、event/null/empty/cursor/terminal 向量及大小边界与规格相符。HELLO afterSeq 使用 initialCursor；新终态需完整 WINDOW；仅活动 idle 可跳过，EOF 不作终态证明。eventType 保留原 C Reader 规则并交集 wire 字符串/总长限制，未将新64字符正则加到私有路径。
- 固定 UTF-8 编码器避免8MiB完整 wire buffer/Jackson增长型编码 backing；surrogate、escaping、tokens（包括结束 token）、depth、string、number 和十进制指数展开在对应边界检查。scratch65536、descriptor65536、header8216 在新分配前预留；两个流式8192 backing、固定 prefix/commit 和逐次≤8216隔离 copy 有可核计费路径。原16MiB共享池、270437 frame、两个 Reader permit、192MiB单工作区 ledger、B readNext/consume 与普通 C 输出路径保留。未发现需扩预算的有据问题；这不是精确堆峰值或最大规模压力证明。
- 正常消费的 entry/action/final guard 失败仍通过 beforeRelease complete；HTTP 已实际阻塞 get/write/flush/complete 各两个连接并验证 permit/frame/slot 保留到退出。下面 I2 是新增容量回调异常逃逸的例外，不能被这些通过的场景抵消。
- application.yml 仅增加32KB请求头和简注；HTTP 使用真实 classpath application.yml，断言有效32768，合法8495字符 child ticket通过，超32KB容器400有独立用例。没有放宽JWT16384 cap或声称反向代理已部署。

## C / I / M

### Critical

无。

### Important I1 — 首 HELLO 前容量错误没有 ApiResponse 正文（P2，需修）

位置：`engine-runtime/src/main/java/cn/agentplatform/engine/application/PortalRunEventsService.java:223`。

触发：private opening preflight 成功后结果释放并排队；实际 writer 的首个 fresh RR 返回非429容量失败，尚未输出 HELLO，故 `statusSent == false`、响应未 committed。`capacityReset` 的 private 分支只设置 failure status 和 application/json，随即 complete，没有像现有 public 分支那样写固定 ApiResponse。这也适用于 HELLO 第一遍编码在零packet字节时容量失败的同分支。

后果：客户端收到例如413和空 application/json body，缺少规范要求的白名单 code/envelope，无法按同一接口错误协议区分 BUFFER_LIMIT/EVENT_LIMIT。正式 packet-decisions 明确未 committed 沿 C ApiResponse/status；作者报告的自报遗漏在源码控制流中独立确认。

最小修法：将“尚无packet字节且未 committed”的 pre-HELLO容量错误单独处理，在既有有界 frame/scratch、actual holder capture guard 与 owner 内写并 flush 固定通用 ApiResponse，再 actual complete；已开始或部分 packet 的路径只关闭，不能追加 JSON/SSE。不新增 MVC redispatch，不泄漏未验证 state/watermark。增加 private fresh queued 首RR容量失败 focused 用例，断言 status、精确JSON envelope、零 APEV/receipt、释放顺序。原45green只覆盖 public 的对应容量用例，以及 private HELLO后的真实EVENT_LIMIT，不能证明此分支正确。

### Important I2 — CAPACITY输出异常绕过消费窗口的 complete-before-release（P1，需修）

位置：`engine-runtime/src/main/java/cn/agentplatform/engine/application/PortalRunEventsService.java:174`；相关失败出口为同文件226；消费释放接缝为 `engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunEventReader.java:254`。

触发：private WINDOW 在 `pending.consume` 的 action 中于 prefix 前发现编码容量失败（HELLO已完整提交，`statusSent == true`、`packetStarted == false`）。beforeRelease 的新增 CapacityFailure 分支直接调用 `capacityReset(limit)`。该方法只在 `PortalEventsPacketWriter.capacity(...)` 成功后调用 complete；其 getOutputStream、child export/current capture、write/flush 或 live guard 仍可抛 IOException/RuntimeException。例如 CAPACITY 输出失败或持有者恰在此出口失效。

后果：异常离开 beforeRelease 后，Reader.consumeWindow 的 finally 立即 cleanup/finish/lease.close，随后 service 的内层 finally 归还 frame；直到外层 owner finally 才调用 async.complete。若实际 complete 阻塞，此时 B permit 和 frame 已可被其他连接复用，违背“entry/action/final失败也必须 actual complete-before-release”的硬约束。slot仍由外层 finally持有不抵消 B/frame提前释放。普通输出失败会走已有 complete 分支；这是新增容量分支特有的异常边界，不是要求改变原B consume语义。

最小修法：在仍处于 beforeRelease、持有本窗口的范围内，用真正覆盖所有 capacityReset/容量输出异常的 finally 确保 complete 物理返回，再允许 Reader cleanup 和 frame释放；保持原异常及 partial packet不重试/不推进 cursor 的语义。可将该容量分支的完整尝试包入 try/finally complete，或让容量处理统一保证相同所有权次序，避免只在成功尾部 complete。新增 focused：有效HELLO后在真实消费路径触发WINDOW编码容量失败，再让CAPACITY write/flush或live guard失败，并阻塞实际complete；断言两个Reader permit和frame仍占用到complete退出、无多余COMMIT/重试/cursor推进。当前45green的阻塞测试未触发这一容量回调异常路径，本结论来自源码，不伪称新运行失败。

### Minor

无。未以格式密集或有界排序策略单独增加阻断项。

## 实际运行证据核对

逐stage直接读取各自 command.json/cwd/exit、raw.log 的 actual Running、对应 reports/TEST-*.xml 的 testcase节点（含自闭合）及 failure/error/skipped，并核 source-epoch count/SHA和有关生产文件SHA。没有把compiler input数量当运行数。全部cwd为唯一工程；具体选择器保留在各stage command.json，修正阶段只选其失败/新增方法。下表 epoch SHA 使用前12位以便索引，完整值仍在对应冻结source-epoch文件。

| stage（统一前缀 iam-stage-o2b-e-） | epoch count / SHA前12 | Running类 | XML tests/F/E/S | command exit | 判断 |
|---|---|---:|---|---:|---|
| http-red | 606 / dcbb109b1a93 | 0 | 0/0/0/0 | 1 | ChunkBus跨包构造编译错误，非功能RED |
| http-red-2 | 606 / 575fbfb8d647 | 1 | 1/1/0/0 | 1 | HTTP400运输层失败，非功能RED |
| focused-1 | 608 / c8b96ffce58d | 2 | 7/1/0/0 | 1 | codec6通过，HTTP400不证明业务失败 |
| http-diagnosis | 608 / ad726c4f4eb6 | 1 | 1/1/0/0 | 1 | XML明确Tomcat HTML400 |
| functional-red | 608 / 5027a05d858d | 1 | 1/0/0/0 | 0 | INVALID，不能算有效GREEN/RED |
| actual-baseline-red | 606 / 7aa5bea24b3b | 1 | 1/1/0/0 | 1 | 真实重编译53生产/67测试源，32KB下旧实现403 |
| http-header-green | 608 / 95b2ced3af16 | 1 | 1/0/0/0 | 0 | 真实重编译54生产/68测试源，正常child200 |
| pg-focused | 608 / 749b45d8ff93 | 1 | 5/1/0/0 | 1 | requiredEntitlements集合顺序比较错误 |
| pg-corrected | 608 / 6e5ad41eae61 | 1 | 1/0/0/0 | 0 | 原失败方法改验scope语义及parents原序 |
| http-new-focused | 608 / 05068876f110 | 1 | 6/1/1/0 | 1 | Accept响应协商1F；varchar32拒4097 type为1E |
| http-modes-corrected | 608 / 2c1776735edc | 1 | 1/0/0/0 | 0 | 失败Accept方法修正后通过 |
| http-payload-capacity | 608 / 2c1776735edc | 1 | 1/0/0/0 | 0 | 真实writer超payload路径EVENT_LIMIT |
| wire-boundary-red | 608 / 86e77ca54b42 | 2 | 2/2/0/0 | 1 | 结束token和HELLO initial cursor真实2F |
| wire-boundary-green | 608 / 880520622e14 | 2 | 2/0/0/0 | 0 | 两失败方法修正，生产/测试均重编译 |
| capacity-vectors | 608 / d3117e3313e5 | 1 | 1/0/0/0 | 0 | 实际child上的encoder reason向量 |
| final | 608 / b89023b56cf7 | 7 | 45/0/0/0 | 0 | 唯一正式最终期 |

INVALID阶段epoch中三个旧生产文件已恢复baseline SHA，但raw只有test compile，没有生产compile，实际1green不能证明该baseline源码运行。随后actual-baseline-red epoch缺writer且三个生产SHA为baseline，raw真实53生产compile；http-header-green恢复E生产SHA并真实54生产compile。这是新的受控对照，不能补称初始实现之前已得到功能RED。

final七类XML分别为：PortalChildEventsHttpTest 7、PortalChildEventsPostgresTest 7、PortalEventsPacketCodecTest 7、PortalRunEventsCapacityTest 5、PortalRunEventsHttpTest 6、PortalRunEventsLifecycleTest 11、RunEventReaderBudgetTest指定2方法。即新21+C24=45，无失败/错误/跳过。final epoch完整SHA为 `b89023b56cf78945a739ac953a0c4b8867bb92bc31ff72d46c1f96bdf893aca3`。final未再生产compile，但其四生产SHA与此前真实重编译wire-boundary-green相同；不能将此正常增量继承与INVALID的恢复baseline却复用E class混同。新增真实resource有效32768由final HTTP测试及raw量测共同支持。

原C历史stage、D87（86pass/1F）、独立selector3均不合并进本次45；未重跑补旧记录。原final45、成功raw patch/replay608维持不可变。root另行核 own/target/patch/replay/vectors/八索引，因此本报告不冒充独立重放或重复完整manifest审计。

## 具名 unchanged 接缝与 ⚠ 边界

仅为两项具名风险各做一次窄查：

1. `admin-common/.../HttpCurrentAuthorityClient.java` 的 openPortalEventsChild（731起）与 portalEventsChildReceipt（795起）：核实际mintingOwner/child、claim实际opening、slot.engineChild和同response导出，支持E入口与receipt不是fixture制造或DTO造权。未扩大为D重审/重跑。
2. `engine-runtime/.../MaterialScopeStore.java` 的 KINDS、addObserved、readObservedScope、requireObservedFloor（26、547–565附近）：核三root白名单、重复hash一致性、scope字段/业务摘要/owner/initial/locator/source版本接缝，支持完整同RR并集的既有校验。其余未变代码沿Accepted基线，不借本任务重新验收。

⚠ 作者初始功能RED判断有过程偏差，编译失败、HTML400、stale-class INVALID和PG测试错误均公开保留；它们没有被洗成绿色。后续有效对照与新边界RED/GREEN可单独采用，不改写原始TDD历史。

⚠ 预算数值是静态保守模型加实际borrow ledger上限断言，未记录精确heap峰值。报告列的最大metadata315800B、88 scopes、87单parents、3862总parents来自作者向量摘要，root另做有限向量核验；不是4096全组合/8MiB满载或生产容量证明。BUFFER_LIMIT向量注入的是encoder reason，不能冒充真实压力失败。

⚠ 本E是encoder与engine接收端。F的固定TLS身份、生产完整decoder（恶意输入/截断/重复键等拒绝矩阵）、receipt association、fresh G current、完整材料/floor/row摘要验证、公开SSE字符安全仍待F。receipt和wire SHA不替代来源认证或业务scope/row SHA；三root大payload未传，不声称F重算它们。没有真实生产IAM/issuer/K8s/ingress/TLS部署、公开gateway EVENTS全链或socket强断SLA承诺。

## 冻结结论

**Needs fixes：I1、I2必须修复后回原reviewer复审；当前不得标记E Accepted。** 原final45/原patch第一次apply/原私有608身份保持冻结。由fresh fixer创建新的focused RED/GREEN、不同delta patch与新epoch；只验证新修复和实际受影响接缝，不重跑旧成功阶段补记录。reviewer未自行修源码。报告完成后交还报告写权，后续复审另行授权。

---

# O2b E I1/I2 修复复审附录（2026-10-21）

**Spec Compliance：Spec compliant / Approved。Task quality：Approved。本轮未关闭问题 C0 / I0 / M0。原 I1、I2 均关闭。**

复审者仍为 `/root/review_iam_o2b_e_engine_packet`。以上原审查记录及其当时 Needs fixes 结论原样保留；本附录仅对 fresh fixer 的两文件修复与新增有限证据给出新结论。E Accepted 还需 root 的新增 artifact audit/收口，不能以作者或本复审单独代替；F 未授权。

## 复审输入、身份与边界

完整读取 `.local/iam-o2b-e-fixes-review-instructions.md`、正式 `iam-stage-4-o2b-e-fixes-brief.md`、fixer report，并继承已读原 packet-decisions、wire/header supplements。新 complete-context 包 `docs/evidence/review-iam-stage-4-o2b-e-fixes-package.diff` 实核56621B/SHA256 `9f7004132738a1fc7ab1a3d4bc10d966ab4cbf14cf059648205c4d18f0618004`；按物理行1–300、301–546两个不重叠区间完整一次读至EOF，LF split含尾空项547。没有另外重读当前changed source或运行Git。

新scope只有 PortalRunEventsService、PortalChildEventsHttpTest，无新文件；source608。最新source epoch SHA256 `303ec0625e9b8491d834cbd0ce27a38c0dcffab77104e333b38f960e102a5adf`。Reader、PacketWriter、common、fixture、原C tests、application.yml及固定预算继承原冻结审查。未运行新旧测试/PG/replay，未遍历own/target/.git或相邻工程。只在原报告末尾追加，追加前确认原报告恰15070B/SHA `3e53314023218e788d63c9aae9fda556e2f8a7d9b67e7f0b462ffa8fd240cf82`，追加后再次校验此前缀原字节。

## I1关闭：pre-HELLO容量错误固定正文

定位：`engine-runtime/src/main/java/cn/agentplatform/engine/application/PortalRunEventsService.java:221` 的 capacityReset，尤其零packet/尚无HELLO/未committed分支及241行固定ApiResponse写入。

private分支先对 packetStarted/capacitySent只返回；已有完整HELLO则尝试一次CAPACITY；尚无HELLO但已committed也只返回。其余零packet、未committed路径落到已有固定小JSON：failure status、application/json、通用message和null data/traceId。capture/current/requireLive仍包围get、write和flush；fixed body沿现有frame/scratch收费，未引入未验证state/watermark、receipt、额外预算、MVC redispatch或public fallback。finally覆盖此路径的失败与返回，实际complete返回后才离开owner持额区间。

新 `PortalChildEventsHttpTest.java:154` 的 `privateFreshQueuedCapacityBeforeHelloReturnsBoundedJsonUntilCompleteExits` 先占两个真实executor worker，确认成功preflight释放后真实请求进入队列；随后正常writer追加超限payload，再放行fresh RR。RED-2在已进入complete时观测wire列表0，正是缺失正文。初始final通过后，actual complete阻塞时精确JSON已写/flush、frame剩61、slot仍占用；此时fresh RR已失败、无mint emission，允许两个B permit可用是正确语义。退出后HTTP413、精确application/json/envelope、零APEV/APCM/receipt/state/watermark及frame62/slot恢复均有断言。该方法在纠正epoch前后本体不变，可继承通过。

## I2关闭：容量尝试的所有异常出口仍先actual complete

定位：同文件 `PortalRunEventsService.java:174` 的beforeRelease容量分派，和 `PortalRunEventsService.java:221` 起capacityReset的完整try/finally（249行finally、252行private complete）。

修复没有改变Reader的consume/cleanup。capacityReset把private的export/current/get/write/flush、partial/重复容量的早返回、JSON错误路径全部纳入try，并在finally内执行private complete。于是从beforeRelease调用时，任何容量异常都必须等该线程的actual async.complete物理返回，才能回到Reader cleanup/lease.close及Service内层frame finally。complete现有CAS只保证一次调用；本次所有权安全来自同一调用栈等待实际返回，而非把CAS/Future.cancel/通知视为退出。普通成功WINDOW不进入capacityReset，public路径的行为未被新的private finally提前关闭。

新 `PortalChildEventsHttpTest.java:177`、178的write/flush两用例及179起helper确实经过目标回调（199行B permit、200行frame/slot、201行wire、205行fresh probe）：正常G签名E ticket、真实HTTP filter/frame与PG产生真实WindowEmission；Mockito wrapper只在实际borrow的WINDOW writePrivatePacket入口、prefix之前抛入另一次真实超payload读取得的CapacityFailure，所有RR、HELLO编码、consume、guard和cleanup仍委托实际对象。反射仅选择executor/reader测试接缝，没有制造authority/capture。其后真实CAPACITY writer分别发生write和flush IOException，测试确认两连接各一次WINDOW入口故障和CAPACITY尝试，才阻塞actual complete。

RED-2两路径都在“complete尚未退出，第三readNext却成功”处失败，明确证实原B提前释放；不是一般WINDOW输出异常冒充。修复后核心断言证明两个B permit/frame/slot保留、第三准入被拒、complete退出后恢复。wire检查write失败只留HELLO，flush失败留HELLO+单CAPACITY，prefix/COMMIT/SHA由既有检查器验证；无WINDOW、无额外CAPACITY尝试/重试，afterSeq=0、eventSeq=null。flush失败发生在完整CAPACITY COMMIT已经写出后，因此不能误称此例“没有任何COMMIT”；它证明没有额外WINDOW/重试和cursor推进。

I2所需的至少两个不同失败出口已覆盖。对export/current/get/live的覆盖来自包围整个容量尝试的finally控制流审查，不声称这次逐一动态注入了所有出口。

## 新运行证据与跨epoch继承

独立读取四stage的command.json、raw Running/compile、对应XML testcase（含self-closing）、failure/error原栈和source-epoch；具体物理命令均为冻结command.json中的mvn.cmd离线engine-runtime selectors，cwd均唯一工程。每stage raw确有54生产源及68测试源实际javac，不将compiler数量当测试数。

| stage（统一前缀 iam-stage-o2b-e-fixes-） | epoch SHA前12 | actual Running类 | XML tests/F/E/S | exit | 判断 |
|---|---|---:|---|---:|---|
| red | b89023b56cf7 | 0 | 0/0/0/0 | 0 | INVALID：旧测试源中不存在新selector方法，0运行不作RED |
| red-2 | 92568a63bec7 | 1 | 3/3/0/0 | 1 | 真正I1正文、I2 write/flush资源释放3F |
| final | 99d349718c7e | 1 | 4/0/1/0 | 1 | I1、flush、正常idle→terminal三通过；write在post-release探针1E |
| write-corrected | 303ec0625e9b | 1 | 1/0/0/0 | 0 | 只运行原失败write方法，纠正后通过 |

生产Service的初始final与write-corrected epoch条目均为23215B/SHA256 `3572534a239272e39ff3b6cb7e38d98284708d3db4278b678157a3aa5df3a427`；因此后续没有新生产代码使另三项证据失效。

初始final的error原栈定位 helper:202 的post-release readNext，经RunEventReader.live到PortalEventsResponse.requireLive:35。为核该错误是否资源未回收，仅窄读一次unchanged `admin-common/.../PortalEventsResponse.java:29` 起的requireLive/isSuperseded接缝：35行是holder/evidence/generation不匹配分支，且requireCurrent已先执行，符合当前capture失效而非workspace耗尽。结合真实readNext自行取得capture及同一holder，自动renew generation交错是有据解释；没有关掉授权守卫或把它改为成功。所有blocked resource/wire断言在此post-release语句之前，error不能被改写为整条旧write testcase已通过。

跨epoch test-only继承没有只信作者JSON：对历史initial-final test证据实核28482B/SHA `fa96994b46b2718aa480cb045dd27f8b9aeecd89266f0969c862c9dcd2683ee6`，等于冻结初始final epoch条目。在内存中仅把helper最后的post-release旧probe行替换为换行/两条注释/新的正常open fresh holder，重构结果28748B/SHA `8a200ce05d6f4ed82db780568883872bfa2f9c4ef1b1a54c8ef610dad74c936e`，精确等于最新epoch测试条目；没有写源码或另读当前changed源码。这个全文件等值证明另三个testcase本体、核心blocked/wire断言和其余代码未变。flush也使用该共享helper，但变化只在complete已退出且资源检查通过后的独立恢复探针；write-corrected已执行这个共同新尾部，正常fresh holder没有制造权限或放松断言。

因此可采用“初始final三项有效通过＋原失败write的纠正focused一项通过”，四个不同目标方法有有效通过证据；**不是一次4green，不把初始1E、零运行INVALID或原E45改写**。正常private idle→terminal用例在新生产epoch有效通过，支持private finally未误关成功流。

## Strengths 与 C/I/M

- 两项问题用一个局部容量处理边界修复，未修改Reader/Writer/common原契约，没有新回收框架、生产hook、scheduler或扩大任何预算。
- I1测试使用真实排队/preflight/fresh RR及writer超payload；I2负例明确区分真实权限与borrow、注入编码故障和真实CAPACITY网络出口。
- 测试验证actual complete阻塞期间和退出之后的两侧所有权，验证wire与次数，并有普通成功流回归。
- 运行历史透明保留0 Running INVALID和初始final1E；纠正仅测试post-release探针且只跑失败方法。跨epoch生产与测试变动可用字节身份核定。

Critical 0；Important 0；Minor 0。I1、I2关闭；没有新增需要修复的有据问题。Spec Compliance和Task quality均Approved。

## ⚠ 保留边界与root audit分工

- 本轮修复不代表最大BUFFER_LIMIT压力测试。I1是真实EVENT_LIMIT；I2是在真实已授权borrow处注入CapacityFailure，再注入CAPACITY write/flush错误。不会将测试控制流注入说成生产容量压力或全部故障矩阵。
- initial-final和write-corrected分别保留3pass/1E、1green的物理事实；flush共享helper末尾改变后的有效性是上述限定继承判断，不称最新epoch重新执行了flush/I1/idle三方法。
- 新raw patch/replay608、own inventory、自有私有Git、finite target overlay530/逻辑2148、diagnostics分离、原冻结E artifacts与八索引未变等由root新增有限artifact audit独立核。reviewer不重复遍历/运行来代替audit；双Approved是代码/任务质量结论，E Accepted仍以root audit/最终收口为准。
- 原E45、原90459B patch/一次apply/原608 replay、16stage及原审计只继承，原报告中的TDD、HTML400、stale INVALID、精确heap/容量和F边界继续成立。F生产decoder、TLS/association/fresh G current/完整材料/公开SSE尚未实现或验收；不作全IAM、全后端、生产部署、生产容量或强断SLA结论。

本附录完成后冻结并交还全部reviewer写权；原15070B前缀保留。后续仅在新授权下继续复审。
