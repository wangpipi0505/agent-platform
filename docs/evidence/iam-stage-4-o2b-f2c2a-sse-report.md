# O2b F2c2a 未装配 SSE / Servlet 组件实现报告

状态：DONE_WITH_CONCERNS。源码、target 与 owned 已冻结交还 root，等待 fresh 完整代码 review 与有限 artifact 审计；本报告不签发 Accepted。

## 范围与身份

只改 root 正式授权的七条源码：新增 PortalRunEventsSse 与两新 test；修改 Http、MaterialVerifier、Decoder、Budget。source 627→630，3 new / 4 modified，其余623逐字相同。Decoder仅 type.isBlank→isEmpty 和注释；Budget仅1MiB全局connector预收费及标量/只读观察，原Owner规则逐字继承。原C1/fixes/19项/fix8/provider/PG/八索引未由本writer重跑或修改。

原 frozen inputs/API补充不改；decoder-type与abort-profile两份正式 amendment 覆盖原候选范围。此前5路径/其余625、6路径/其余624各stage保持原件；此后7路径/其余623，stage-catalog逐阶段给出有效allowlist、实际copied paths及source epoch。早期source629是尚未新增全部test的实际epoch，未回填。

## 实现与实际数据链

原Q/Opening→G→同child/receipt→C1材料/capture链仍由同一个真实Session供给。原Q initialCursor与公开after分开；每个后窗header仍绑定原initialCursor，after必须等于上个真实公开event完整成功后的cursor。HELLO、COMMIT、控制帧、EOF不推进公开after。

VerifiedWindow新增raw输出，与原canonical输出共享I1单writer准入：在借packet Segment或调用sink前独占；不持Window monitor跨sink；异常先关borrow再退出writer。raw只删除string外SP/TAB/CR/LF，保留number lexeme、负零、指数、字符串空白/转义及UTF8原字节。实际payload直接作为data JSON值，无wrapper，无double重写；null、高精度数值、引号与反斜线均有真实链验证。

Frame只有固定270374B backing，无增长副本或第二UTF8 backing；原type保留前导/全空格，最多32 Unicode codepoints及128 UTF8B，拒绝empty/CR/LF/NUL；id为规范long十进制。真实最大可达event帧262312B（262144B payload及Long.MAX_VALUE）；payload+1在真实F1入口拒绝，未冒作SSE backing overflow。status最多一次，reset优先于status，原payload不混入状态wrapper；terminal仅真实drained backlog为空时成立，EOF不冒终态。

原request线程startAsync/owned AsyncListener/精确token后启动唯一reader，有限等待首真实slot，fresh→getOutputStream→fresh→setWriteListener并立即返回；测试记录setter-return后真实callback实际write。全部actual ready/write/flush均在callback内、同输出gate、每chunk≤8192，并在各边界重查同capture。attempt先标记，任意attempt后换代拒绝写旧尾；flush后实际ready/drain及最后fresh都成立才confirm event cursor。真实pendingflush/readyfalse有暂停读取形成的证据，不以peer EOF替代出口。

轻ready-idle等待无window/frame局部，不持应用gate/token；heavy helper真正返回后才detach slot、通知producer结束borrow，随后下一F1实际scan。独立原55s hard/15s idle与5s opening不被虚假唤醒重置；真实readyfalse无下一writable通知时，唯一reader在私有同步操作真正退出后执行受控abort，实际约15s退出。容器async timeout设0以证明不依赖其回调。

## 固定 Tomcat 接缝与锁序

固定Tomcat10.1.41/Servlet6.0/HTTP1.1 NIO，真实Connector显式安装nested ControlledProtocol与ContextValve。protocol的super.createProcessor提供真实delegate；11个public Processor SPI全部委托，只有process scope/recycle生命周期加保护。无private Hook/ASM反射、无复制Http11Processor、无offthread dispatch/start/complete、无裸response异步fallback。

真实process以原SocketWrapper.getLock作最外排斥；固定Thread[64]+WrappedProcessor[64]短monitor bridge只发布当前真实process线程scope，finally恢复previous，无ThreadLocalMap、每event任务/连接registry。每个新REQUEST（含/ping及pipeline）先失效旧token，原token关联不可转向另一socket。外部stop先在输出gate置停/notify并释放所有应用锁，终止私有transport，再等immutable原socket锁；之后gate→token重验epoch/once并覆盖完整CLOSE_NOW调用尾部。onComplete在相同最外socket下短token disarm；异常process及wrapper.recycle先失效，再delegate.recycle/清socket，且移除waitingProcessor。

保留的真实旧deadlock dump证明原io/token→ASM与ASM→onComplete反转；该失败child实际95s watchdog后waitFor/collector收尾。新SPI epoch的真实abort-order、action暂停、重用socket/processor等回归闭合后才进入最终矩阵。

跨连接复用实际记录同一个processor id、creations=1；另一socket两个pipelined /ping均成功，迟到旧abort零action。同socketkeepalive也独立验证。公开SocketWrapper.close向量使用callback只读取得的真实socket，独立线程执行真实close/release；recycle在token失效后、delegate.recycle前实际barrier阻塞，双Owner保费/第三拒、迟到abort零action；解除后2 recycle真实返回/waiting0，依然没有completion。forced invalidation未改名onComplete，该异常路径依安全策略保完整Owner至child实际退出。

## 实际费用与退出门

原B2 1MiB只计一次。Session.beginSse在分配前reserve1909574，包含reservation token为1909702；覆盖frame270534、UTF8/counters65664、Servlet1048704、callback/worker/exit524416、可能第二borrow384。frame数组/控制器/State/io与cell/固定thread及token均在候选profile内。公共window/borrow单writer没有新增并行重图。

新增全局1048576B baseline在Bridge/wrapper分配前预收费，一次JVM lifetime仅一个ControlledProtocol（顺序重启也拒绝），不占Owner且不在Connector.stop退款。固定maxConnections1024/maxThreads64/processorCache64，拒绝custom/virtual executor及升级协议；安装前后、createProcessor及process检查。最多1218 live/cached/active/closing/transient wrapper，每个256B保守预算为311808B，固定bridge数组/协议标量等保留至少64KiB，其余为余量，整体小于1MiB；bridge64槽不保留Owner/G/material。waiting集合在recycle移除，不能累计已关闭wrapper。delegate与原框架图属原排除，不用maxThreads单独当processor总数。

双Owner候选2*98715562+1048576=198479700<201326592，余2846892。正常独立lifecycle child最后owners0、owned费用0、global reserved1048576；不装connector的legacy/raw child最后global0。强制close无completion child保留2Owner至实际JVM退出，绝不声称其账本归零。

Http原cleanup/退费之前先挡SSE exit lease。完整event/helper/borrow结束、worker实际join且isAlive=false、active action/IO归零、completion观察、frame/output/slot/thread/async引用detach后才释放lease，继而旧Http私有transport/timer/G/owner cleanup。completion有限wait失败可由原Session/Handle的awaitClosed重试，不因额外reservation失败才“发现”生命周期。两heavy、轻shell、completion与实际action暂停分别验证持费和第三Owner拒绝；轻shell的下一真实F1 scan费用证明旧C1已退出，而整个callback并未被谎称返回。

这是工程收费模型，非全JVM/RSS/JSSE/native/GC/threadstack/全container join或生产容量上界。socket/action真实卡住会继续持费直至有限耗尽；容器轻尾部/内核close缺少同步ack，不冒作已全系统退出。

## 测试阶段与失败保留

最终实际矩阵与所有development epoch分别列出，禁止把早期绿项拼成同场：

| stage | 有效写权/其他继承 | source | Maven exit | tests/fail/errors/skip | children |
|---|---:|---:|---:|---:|---:|
| red-raw | 5/625 | 627 | 0 | 0/0/0/0 | 0 |
| red-raw-actual | 5/625 | 629 | 1 | 1/1/0/0 | 1 |
| green-raw | 5/625 | 629 | 0 | 1/0/0/0 | 1 |
| lifecycle-first | 5/625 | 630 | 1 | 0/0/0/0 | 0 |
| lifecycle-compile | 5/625 | 630 | 0 | 1/0/0/0 | 1 |
| backpressure-first | 5/625 | 630 | 1 | 1/1/0/0 | 1 |
| raw-and-bounds | 5/625 | 630 | 1 | 3/1/0/0 | 3 |
| bounds-reachable | 5/625 | 630 | 1 | 2/2/0/0 | 2 |
| type-amendment-and-backpressure | 6/624 | 630 | 1 | 2/1/0/0 | 2 |
| lifecycle-negatives-1 | 6/624 | 630 | 0 | 5/0/0/0 | 5 |
| lifecycle-exit-gates | 6/624 | 630 | 1 | 7/3/0/0 | 7 |
| bootstrap-and-real-idle | 6/624 | 630 | 0 | 2/0/0/0 | 2 |
| max-frame-and-precision | 6/624 | 630 | 0 | 2/0/0/0 | 2 |
| light-shell-real-exit | 6/624 | 630 | 0 | 1/0/0/0 | 1 |
| actual-pending-flush | 6/624 | 630 | 0 | 1/0/0/0 | 1 |
| spi-abort-order-red-fix | 7/623 | 630 | 1 | 0/0/0/0 | 0 |
| spi-abort-order-actual | 7/623 | 630 | 0 | 2/0/0/0 | 2 |
| spi-lifecycle-boundaries | 7/623 | 630 | 1 | 5/2/0/0 | 5 |
| spi-idle-and-release | 7/623 | 630 | 1 | 1/1/0/0 | 1 |
| spi-real-release-and-idle | 7/623 | 630 | 1 | 2/1/0/0 | 2 |
| spi-forced-release-exit | 7/623 | 630 | 0 | 1/0/0/0 | 1 |
| final-candidate | 7/623 | 630 | 1 | 29/1/0/0 | 38 |
| spi-light-shell-admission | 7/623 | 630 | 0 | 1/0/0/0 | 1 |
| final-candidate-safe | 7/623 | 630 | 0 | 29/0/0/0 | 38 |

原raw真实RED为期望原76B、实际canonical67B；修正后原lexeme raw GREEN。全空格真实链先在F1拒绝的RED保留，再按decoder授权修正。编译API/fixture hash/Unicode oracle/await假设等失败均保持原raw/XML，不冒业务RED。peer10s timeout早于组件15s的失效fixture改25s；实际Tomcat.stop先正常close-now/onComplete，不能声称无completion。首次真实强制close barrier断言完成但通用Server.close误要求Owner归零，child95s超时原件保留；该异常向量改显式finally停容器，不绕过费用门。

第一次共同矩阵实际29项=28PASS/1FAIL，唯一light-shell oracle仅等待低于80MiB而可能采到下一F1尚未申请的低值。改为等待实际[64MiB,80MiB)区间，同步读取活跃trace；spi-light-shell-admission定向1PASS。production实现bytes未变。后续final-candidate-safe已在root“仅定向”消息到达前启动，为保已启动child的finally/waitFor/collector而让实际进程结束，作为冗余真实回归如实记录，不要求root重复测试，也不改写前次epoch或合成同场结果。

实际新final-candidate-safe：29 JUnit，failures=0，errors=0，skipped=0；38 个独立child。全部历史117 child均记录实际waitFor返回与collector join，alive=false/collectorAlive=false；失败child exit非0原样保留。两新test的三个legacy wrapper只向旧真实singleWriter(Path)/reentrantWriter(Path)/retention(Path)传新owned目录，绝未运行旧runs()/固定owned child launcher。旧Decoder仅literal自含的eventTypeUtf16AndHeaderIntegerLimitsPreserveOriginalText；未跑全F1/ignored.local vectors。所有新fixtures来自本工程classpath，真实LoopbackAuthority/receiver/private TLS peer及C1完整材料，没有PG/模型环境冒充。

## Git、有限target与交还

实际primary private Git先设binary attrs再首次add，精确before627实际blob与before tree；replay在应用前另存627完整名字/bytes。七路径最终binary patch，首次plain git apply --check与plain apply各一次exit0；replay无辅助index、无after补copy，完整630名字/bytes与source-after一致。primary after630实际Git blob亦逐一核对；无commit。

target从C1历史logical2269继承，只有实际compiler input/created lists及其对应输出、Maven资源copy、common fixtureJAR、实际Running报告进入finite overlay；diagnostics另列。最终finite 402、logical 2295；不枚举whole current target/classpath，不声称每个继承class本轮重编译/部署。phase与hash即时journal/cache，metadata只补未完成阶段，没有重跑旧成功阶段来补文案。

source-after/target-after、stage-catalog、peer-exits/observations、privateGit/replay、报告/handoff/freeze/own-inventory共同交还。inventory覆盖两个owned roots、具名helpers及本报告，唯一self-exclusion是inventory自身。所有七源码/受影响target/owned写权返回root；writer冻结后不再修改。

## 顾虑与下一步

本组件未扫描/未装配，公开IAM EVENTS入口未开放。C2b仍需common/controller/assembly显式安装controlled protocol+Valve、真实独立gateway/engine/issuer/新PG/TLS ≥65父首链；C2c前端null与超2^53 cursor协议适配另排。没有真实模型/本体/IAM/K8s环境不冒生产验收；17/34/30、完整IAM、生产容量、部署均未通过本任务。

正常completion与forced missing-completion语义有意区分；强制socket释放但不发completion会安全耗尽两个Owner直到进程退出，属明确可用性限制，非退款成功。固定connector profile/单lifetime实例需要C2b安装与运维保持；若以后更改Tomcat/升级协议/线程cache/连接上界，当前baseline证明不可直接继承。Maven原encoding/deprecation及隔离Tomcat shutdown线程/模块访问警告保留在diagnostics/raw，实际child已退出，不等于全框架资源同步join。

作者自审核七路径范围、原Q/after分离、raw/canonical共享writer、真实flush/drain/capture、socket锁序/epoch/回收、precharge/detach与原Http退费前gate。最终签收只由root fresh完整review与有限artifact audit作出。
