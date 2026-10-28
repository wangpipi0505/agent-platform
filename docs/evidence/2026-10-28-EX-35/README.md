# EX-35 发布代理回调锁反序修复

阶段导航：**原frozen轮历史记录保留，最新共享模块见[后续收口](#后续收口共享网关模块与独立评审)。** 下文原gate=false及SSE新增失败均为当轮事实，未覆盖或改写；最新逐方法回归结果与独立评审身份在末尾单列。

日期：2026-10-28。实施HEAD：`20333f35bc9f97dc11df296c04c8028e27fd42c8`，分支`feat/v1.2-implementation`。只在本卡两份Java文件及本证据目录工作；没有使用superpower，没有修改鉴权、限流、迁移、`PublishedRunHttp`、真实`.env`或三主入口，没有操作index/commit或外部发布。

**原frozen轮当时结论（历史记录）：冻结代理候选的33方法全部通过，最终完整模块采集齐全但gate=false。新增一个SSE生命周期失败需要独立承接，EX-35主项保持进行中，不能宣布全模块或本卡验收通过。**

## 问题与改动

EX-00已确认：产品timer持Exchange锁注册WriteListener等待真实Tomcat处理锁；真实Tomcat timeout持处理锁调用Exchange.fail，形成反序，close随后被阻塞。原始基线及归因保留于[EX-00只读分析](../2026-10-28-EX-00/gateway-close-deadlock-analysis.md)。

- Exchange监视器只决定状态、唯一writer、在途Servlet操作及终态句柄权属。请求/输出流调用、listener注册、worker/HTTP取消、AsyncContext.complete均在锁外执行。
- 终态先阻止新操作并摘除active、清理业务引用。普通完成由最后退出的在途操作领取真实AsyncContext；上下文领取后置空，因此inline/迟到回调不能二次完成或安装writer。
- 真实MVC timeout在listener返回前从终态领取上下文并在锁外完成，避免Tomcat按“未完成timeout”另行生成响应；仍在返回途中的writer回调只见终态，无输出。该特殊边界来自首个修复版本的实测失败，并非放宽用例。
- 有限输出块在readiness之后、实际write之前检查状态、总预算及现有授权proof；拒绝status在最终complete前恢复。取消权只领取一次，worker退出与取消并发时按单调增量汇总句柄计数。
- Future仍在execute前安装；5秒总预算、2MiB上限、拒绝状态白名单、原JSON信封与出站目标保持原语义。没有新增依赖或执行框架。

新增三个JUnit方法，完整原方法仍为30个：

- `timerAndRealServletTimeoutCannotDeadlockClose`：同文件内的隔离JVM启动真实Spring/Security/Tomcat和产品代理；只发不完整chunked JSON，保持产品5秒timer，随后触发真实容器timeout与真实writer注册竞争。子进程用ThreadMXBean识别Java锁环并非零退出，父测试有30秒上界和强制回收。
- `closeBeforeActiveRegistrationRejectsItsLateContextWith503`：请求wrapper仅在真实`PublishedRunProxy.start`的前置Servlet调用暂停，close先看见空active并退出，随后真实startAsync/迟到context仍唯一完成且503，零上游/输出。
- `closeAfterFiniteWriteClaimPreservesTheClaimedSuccessStatus`：在实际Servlet write委托前暂停已领取的有限块，close进入终态并完成取消/关闭决议后再放行，断言客户端正文与领取块完全相同、状态仍200、无额外字节。人工等待有4秒界限，记录两次取消interrupt但不把注入等待变成虚构的可中断Servlet写；实际委托一次，客户端111字节完整收到。未领取输出的原403零成功字节断言仍保留。

只提取`EX35_`安全事实；不把原始日志、XML或请求凭据纳入Git。

## 实测记录

所有命令均由[run-tests.ps1](run-tests.ps1)加载本仓`.env`到当前测试进程，不打印值。`ENGINE_PG_TEST=true`；既有`AGENT_DEV_AUTH=true`未隐藏。JDK21.0.12，独立Maven本地库`.tools/m2`。原始日志和当次XML立即归档在每次run的忽略目录，版本化产物仅含安全汇总、方法名/状态及源码摘要。

当前依赖安装时点为20:01:27；之后root独立执行EX-02仅改app候选，不改admin-common、engine或gateway依赖，明确无需因该卡重跑reactor安装。EX-02 app改动不纳入本卡网关提交。三份本卡采集/比较脚本另经[Windows PowerShell5.1语法检查](scripts-ps51-parse.json)，各0错误；不将语法检查算产品测试。

| Run | 命令范围 | 结果 |
| --- | --- | --- |
| [red-20261028-194626-659](runs/red-20261028-194626-659/run.json) | 仅新增方法，原产品HEAD | exit1，1 FAIL；`EX35_DEADLOCK`确认同型Exchange→Tomcat处理锁→Exchange环 |
| green-targeted-20261028-195554-323 | 采集工具调用中断 | 仅raw.log，无run.json/XML，不计通过；[中断记录](runs/green-targeted-20261028-195554-323/interrupted.json) |
| [green-targeted-20261028-195604-154](runs/green-targeted-20261028-195604-154/run.json) | 首版锁边界 | exit1，1 FAIL；无锁环但真实timeout返回后的503断言失败 |
| [diagnostic-targeted-20261028-195733-573](runs/diagnostic-targeted-20261028-195733-573/run.json) | 原断言追加安全行号定位 | exit1，1 FAIL；定位子JVM第1666行HTTP503断言，无业务超时调整 |
| [green-targeted-20261028-195902-950](runs/green-targeted-20261028-195902-950/run.json) | 新增竞争＋原首次write前过期方法 | exit0，2 PASS；complete=1、onComplete=1、writer=1、active=0、输出0、上游0；原403零成功字节也通过 |
| [green-class-20261028-200005-482](runs/green-class-20261028-200005-482/run.json) | 第一候选完整GatewayPublishedRunProxyTest | exit0，31 PASS、0FAIL、0ERROR、0SKIP；当时30原方法＋死锁复现1新增均在XML中，原坏类完整退出；后续两反例另见33方法记录 |
| [install-20261028-200127-682](runs/install-20261028-200127-682/run.json) | 当前reactor install -DskipTests | exit0；仅构建安装，0测试，不计测试通过 |
| [after-module-20261028-200140-469](runs/after-module-20261028-200140-469/run.json) | 初始候选、原单JVM模块命令 | exit1，27 XML、266case=243PASS/9FAIL/14ERROR；21个PG方法因类级连接失败未执行，另有6个RateLimit新增ERROR；不放行 |
| [review-races-red-20261028-201350-785](runs/review-races-red-20261028-201350-785/run.json) | 独立预审两反例、初始候选 | exit1，2FAIL；迟到注册空200、已领取块被改503，委托前计数不当作客户端证据 |
| [review-races-green-20261028-201529-235](runs/review-races-green-20261028-201529-235/run.json) | 两分支小修＋四方法 | exit1，3PASS/1FAIL；状态已正确，但人工可中断pause使正文断言失败，未计为通过 |
| [review-races-green-20261028-201937-814](runs/review-races-green-20261028-201937-814/run.json) | 人工pause不引入额外Servlet异常 | exit0，4PASS；完整正文断言保留，真实delegatedWrites=1，claimed/written=111，status200；迟到注册503 |
| [review-races-red-replay-20261028-202052-304](runs/review-races-red-replay-20261028-202052-304/run.json) | 初始候选两分支受控重放 | exit1，2FAIL；产品SHA与旧module候选一致后重放，真实write委托一次 |
| [review-races-red-wire-20261028-202145-165](runs/review-races-red-wire-20261028-202145-165/run.json) | 同旧候选、最终fixture补实际接收事实 | exit1，2FAIL；客户端收到完整111字节、bodyMatchesClaim=true但status503；另迟到注册空200；随后恢复两分支修复 |
| [final-class-20261028-202226-065](runs/final-class-20261028-202226-065/run.json) | 第二候选完整GatewayPublishedRunProxyTest | exit0，33PASS/0FAIL/0ERROR/0SKIP；全部30原方法＋3新增；后续单读静态修正另见冻结SHA轮 |
| [final-module-20261028-202420-827](runs/final-module-20261028-202420-827/run.json) | 第二候选、恢复依赖、逐类新JVM的27类模块 | exit1，27XML/287方法=272PASS/7FAIL/8ERROR，0SKIP；新非通过/缺方法/坏类为0。仍含静态发现的双读closed窄窗口，不以本轮放行；已完整归档后改单读，等待新SHA最终回归 |
| [frozen-class-20261028-205615-721](runs/frozen-class-20261028-205615-721/run.json) | 最终冻结B4AFF…/03533…，完整类＋逐类新JVM条件 | exit0，33PASS/0FAIL/0ERROR/0SKIP；原30＋新增3，缺失/重复/非通过为0，详见本轮proxy-method-comparison.json |
| [frozen-module-20261028-205724-595](runs/frozen-module-20261028-205724-595/run.json) | 相同冻结SHA，最终27类逐类新JVM模块 | exit1，27XML/287方法=271PASS/8FAIL/8ERROR，0SKIP；代理33PASS，无方法缺失；新增SSE1FAIL且原TLS1FAIL消失，gate=false |
| [lifecycle-repeat-1-20261028-211206-488](runs/lifecycle-repeat-1-20261028-211206-488/run.json) | root批准的同准确SSE方法单次有界独立复验 | exit0，1PASS，同.env/argLine/freshFork；仅额外观测，不覆盖完整模块新增失败，此后没有刷绿 |

前述两次真实锁竞争通过以各自[safe-facts](runs/green-targeted-20261028-195902-950/safe-facts.txt)及[完整类safe-facts](runs/green-class-20261028-200005-482/safe-facts.txt)为准；原[红安全锁事实](runs/red-20261028-194626-659/safe-facts.txt)保留，未覆盖成绿色。

## 模块对照与剩余项

以EX-00的[comparison-baseline.json](../2026-10-28-EX-00/comparison-baseline.json)为唯一选择依据。其gateway方法表实际包含主run的29方法与补采run的225方法，共254方法、26类，原GatewayPublishedRunProxyTest未完整报告。由[compare-methods.ps1](compare-methods.ps1)按完整类名＋方法名比较，分别保留两次baseline来源，不把补采拼成当时全模块通过。

初始模块的[comparison](runs/after-module-20261028-200140-469/comparison.json)明确记录：类XML齐全，但collectionComplete=false、allTestsPassed=false；两个类级空method失败导致原21方法缺失，不能把少跑的方法算成失败消失。[安全异常事实](runs/after-module-20261028-200140-469/nonpass-safe-facts.json)仅提取异常类型、原因类型和必要业务frames。PG子进程为PSQLException←ConnectException；当时AP25432/26379/29000无监听，进程消失原因未证实。61388等旧数字是exec session ID，不是OS PID，不以Get-Process查询这些数字作进程证据。

依赖由主执行者恢复并持续保活，详见[dependency-recovery.json](dependency-recovery.json)；真实`.env`指纹一致，无清数据/新建库/API启动。最终模块开跑前再次核对三端口及指纹，见[final-module-preflight.json](final-module-preflight.json)。

RateLimit六个ERROR均保留原方法名和异常类型；首个根因是`ServiceTokens.registerDeployment`拒绝同classloader内IAM→LEGACY，随后五个为Spring上下文失败阈值复用。该安全不变量不改。主执行者明确批准并更新卡片v1.5/手册v1.6：仅网关模块增加`-DreuseForks=false`，各类合法部署配置在新JVM中独立。最终run.json显式记录该条件、环境指纹及恢复记录；不把运行条件变化当成产品修复。

最终模块[comparison](runs/frozen-module-20261028-205724-595/comparison.json)和[gate](runs/frozen-module-20261028-205724-595/gate.json)已归档。旧26类254方法无缺失；恢复原坏类30方法＋新增3方法均PASS；classLevelCases、缺类、重复和新跳过均为0。collectionComplete=true，但gatePassed=false、allTestsPassed=false。当前不声称全模块或EX-35已验收；独立只读评审和本地提交由主执行者完成。

最终轮保留15个原非通过，顶层/caused-by异常类型仍与EX-00一致，见[安全比较](runs/frozen-module-20261028-205724-595/nonpass-cause-comparison.json)。但`PortalRunEventsSseLifecycleTest#forcedConnectorReleaseInvalidatesTokensWithoutInventingCompletion`新增FAIL；原TLS负对照FAIL→PASS。即使总非通过仍16，也发生了方法替换，不能用相同总数或单次独立复验PASS放行。

第二候选的[逐方法comparison](runs/final-module-20261028-202420-827/comparison.json)包含254个旧方法、恢复的原坏类30方法、新增3方法，共287方法，26→27类；没有缺方法、空method坏类、新跳过或新增非通过。collectionComplete=true，但allTestsPassed=false。唯一既有状态变化是`PortalEventsTlsTest#plainObserverDetectsApplicationAndAdditionalConnectionNegativeControl`从FAIL到PASS，保留历史时序失败，不计本卡产品修复。

保留的15个非通过按原方法名逐项列在[nonpass.csv](runs/final-module-20261028-202420-827/nonpass.csv)；[安全异常比较](runs/final-module-20261028-202420-827/nonpass-cause-comparison.json)确认其顶层/caused-by异常类型集合均与所选EX-00原XML一致，不输出原消息。

| 既有类/组 | 保留结果 |
| --- | --- |
| GatewayRunStatusChainPostgresTest | 1FAIL：realGatewayQueryAndStatusReturnOriginalBytes |
| PublishedMetadataSmokeLauncherTest | 1FAIL：launchTwoIsolatedJvmFixturesWithFiniteDeadline |
| PortalRunEventsJoinedChainTest | 原5方法FAIL |
| PortalRunEventsSseAutomaticCleanupTest / PortalRunEventsSseResetCleanupTest | 各1ERROR |
| IntegrationSecurityTest | 原6方法ERROR |

静态末次修正仅把installAsync的closed读取合为一次并复用stopped设置503，封闭两次原子读之间的窄窗口。没有为该静态窗口添加复杂测试钩子；原有界pre-active反例保留。改前/后SHA及`/root/ex35_independent_review`的独立冻结确认见[static-followup.json](static-followup.json)、[静态冻结SHA](static-freeze-sha256.json)。该修正已另取frozen-class/frozen-module结果，未沿用第二候选模块放行。

新增SSE观察的[只读分析及承接白名单建议](sse-new-observation-analysis.md)保留了失败/单次通过、真实phase事实和固定Tomcat入口。失败为`:695`entered.await=false，后续recycles断言未执行，0/1另属外层子JVM退出码。ready-false尚不能证明callback/process已经返回、processor已重新关联；fixture裸socket.close与同一socket处理锁之间有明确可检验交错，但本次未采到currentProcessor值，不将源级解释当已实测唯一根因。未修改SSE/测试或等待。测试窗口已明确交还root/EX-01，后续只读归因不启动Maven/PG/HTTP/Java。

## 文件归属与回滚

产品/测试白名单只有`platform-gateway/src/main/java/cn/agentplatform/gateway/application/PublishedRunProxy.java`与`platform-gateway/src/test/java/cn/agentplatform/gateway/application/GatewayPublishedRunProxyTest.java`。开工时已有app-management两份测试改动、EX-01脚本/证据、EX-02证据、EX-00额外未跟踪文件及历史output，均未纳入本卡改动或恢复。

回滚只恢复主执行者最终登记的本卡代码提交；保留本卡/EX-00失败及验证证据。当前尚未提交，不提供虚构提交号。`git diff --check`对两Java白名单通过；独立评审待主执行者登记。

## 后续收口：共享网关模块与独立评审

2026-10-29文档补齐，引用2026-10-28实际执行的共享模块`EX-27/safety-module-20261028-233458-012`：[真实运行与命令](../2026-10-28-EX-27/runs/safety-module-20261028-233458-012/run.json)、[逐方法comparison](../2026-10-28-EX-27/runs/safety-module-20261028-233458-012/comparison.json)、[阶段gate（实际文件名stage-gate.json）](../2026-10-28-EX-27/runs/safety-module-20261028-233458-012/stage-gate.json)、[保留非通过原因类型比较](../2026-10-28-EX-27/runs/safety-module-20261028-233458-012/nonpass-cause-comparison.json)。原frozen轮的[gate=false](runs/frozen-module-20261028-205724-595/gate.json)仍独立保留。

共享模块真实结果为27类/27XML/288方法：273PASS、7FAIL、8ERROR、0SKIP；native Maven exit1、`allTestsPassed=false`。按EX-00原254方法（主29＋补225）、恢复原Proxy30、新Proxy3、新SSE1逐项核对，`collectionComplete=true`、`regressionGate=true`；新增非通过、缺失、重复、空方法/类级坏case均为0。15个保留非通过的状态、顶层异常及caused-by类型集合均与所选原基线一致。旧TLS负对照FAIL→PASS仅作本轮额外观测，不宣称本卡修复TLS产品，也不用其抵消任何新失败。既有非通过仍待各自范围处理，不能把本轮写成全模块全绿。

根因修复的两份候选从本卡frozen完整33PASS到共享模块保持同一源码SHA256：[冻结摘要](static-freeze-sha256.json)与[共享模块前](../2026-10-28-EX-27/runs/safety-module-20261028-233458-012/fingerprints-before.json)/[后](../2026-10-28-EX-27/runs/safety-module-20261028-233458-012/fingerprints-after.json)一致。

- 产品`PublishedRunProxy.java`：`B4AFF02FFC69CBDC5697A5B7D1B9BC6151B6D75D180791AD5B05E5926C92E129`。
- 测试`GatewayPublishedRunProxyTest.java`：`03533FDD869AC48D3F1036871FCA76F0EE4CDB05B98A03FA8D62C78F00E376D6`。

共享模块的Proxy33方法全部PASS，SSE默认完整38方法亦全部PASS，49个SSE子JVM真实exit0且均已退出。EX-27仅修正真实processor回收正例的测试前提，并新增默认off-lock恶劣关闭安全向量；SSE/HTTP/Budget产品没有因此修改。真实Owner/worker/动作目标/另一连接请求及原复用向量的安全事实分列在[共享安全汇总](../2026-10-28-EX-27/runs/safety-module-20261028-233458-012/safety-facts-summary.json)。没有把新增向量未发生的原SSE processor复用冒称实测复用。

本卡原SSE模块FAIL、其单独PASS、[EX-27的447确定性诊断红](../2026-10-28-EX-27/diagnostic-result.md)及[E175夹具编译失败（0XML/0方法/0child）](../2026-10-28-EX-27/compile-failure.md)均保留，不抹除历史，也不声称原历史SSE失败唯一同因。依赖恢复、逐类fresh JVM和EX-01服务环境配置是明确的运行条件事实，不作为本卡产品修复结论。

[独立只读评审review.md](review.md)已记录实质终审前置：无未关闭实质发现，仍待root对精确暂存树、commit及回滚身份窄核。作者不改独立评审或index，本地提交由root统一；EX-35回滚仍仅对应本卡两Java的登记提交，不混入EX-27测试。此收口不外推PLAT-18整体完成，也不关闭EX-27/Y-4整卡或其其它子步。公开可stage的准确路径清单及本轮纯派生摘要见[closeout.json](closeout.json)，不包含raw/XML、`.env`或其它卡文件。
