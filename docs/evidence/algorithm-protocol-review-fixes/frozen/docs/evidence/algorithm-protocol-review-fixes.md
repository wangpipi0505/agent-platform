# 算法协议独立审查返修记录

本轮处理 `review-algorithm-protocol.md` 的 R1/P2、R2/P2 和 N1/P3，依据 root 的 `algorithm-protocol-review-fix-brief.md`。独占同一新工程 skill 源码/target；不 package、不启停主服务、不运行实际 JVM 纵切、不修改 common/其他模块/POM/迁移/原四契约/v1.2/Node fixture/暂停 runner。原独立审查 REQUEST_CHANGES 是本轮输入，本报告不自行签署 APPROVE，也不关闭完整 G2/T11。

## 新基线和保护范围

返修起步将全部 skill/common src、三个 POM、当前原报告/开源参考文档/原测试脚本/Node fixture 共94文件按原字节复制到 `algorithm-protocol-review-fixes/baseline/`，见 `baseline-sha256.json`。另固定原 `algorithm-protocol-artifacts/` 全部文件及原 `algorithm-protocol-source-sha256.json` 共492文件的保护摘要；原 baseline/frozen/patch/apply/日志/manifest 不覆盖。原16文件补丁 SHA256 仍为 `674fcc2b768c0ebca9be11980e5a261b2343a818db1842dd979c5950bb228159`。

本轮生产仅改 `infra/ArtifactStore.java`、`infra/AlgorithmAdapter.java`。测试改 Java `AlgorithmApiFixture.java`、`AlgorithmProtocolTest.java`、`AlgorithmExecutionPostgresTest.java`，新增 `AlgorithmOffloadInterruptionTest.java`；新入口 `scripts/test-algorithm-protocol-review-fixes.ps1` 只写本轮证据目录；更正当前原报告N1一句并新增本报告。锁序、PREPARING/单POST CAS、UID唯一索引/原代、UNKNOWN禁止换代、HTTP/MCP API/45s共享store、Schema/安全上下文/守卫/审批/Mock均保留。

精确before/after SHA、ADD/MODIFY清单、新完整上下文补丁、frozen、实际apply验证与测试证据摘要见本轮目录 `source-sha256.json`。全回归前后全部src/POM为91文件，逐文件原字节比较；原492证据再次比较。

## R1：原SDK操作取消、完成屏障与中断

固定SDK实际源码证实：MinIO8.5.17 `MinioClient.putObject/getObject` 对 `future.get()` 的 InterruptedException 只包装RuntimeException，既不恢复请求线程标记，也不取消/等待原异步链。原offload接着立即执行对象删除，可能与仍在进行的PUT竞争；原close的cancelAll/shutdown没有原future完成屏障。此因果与审查一致。

新算法专用路径使用 MinioAsyncClient 公开putObject/getObject入口，保留 **真实原SDK future自然完成** 的生命周期；不调用future.cancel来制造包装future已结束的假象。原HTTP/MCP路径仍使用原同步入口和45s配置。算法实例独有的 AlgorithmIo：

1. 每个真实Call经EventListener注册，并使用原同一个绝对deadlineNanoTime；callEnd/callFailed才从集合移除。每个SDK操作的完成动作维护尚未结束的stage数，包括SDK PartReader/上传/分片链。GET迟到返回的响应流在停止状态立即关闭。
2. 明确请求线程中断时，捕获真实 InterruptedException，或由 Thread.interrupted() 记录该线程实际中断状态；封闭后续Call并取消当前真实Call，关闭本次源/响应流。只读异常类别或字符串含interrupt不会设置中断位；SocketTimeoutException/InterruptedIOException普通期限不被当作请求线程中断。
3. 首次停止只创建一次 **200ms有限取消收尾界限**，重复close不刷新；它不是业务deadline重置。等待真实stage完成、所有Call已end，再等待本实例executor终止，区分HTTP I/O退出与OkHttp回调最后的dispatcher bookkeeping。正常可确认分支返回前这些条件同时成立；不拿远端sleep handler active作为本地屏障。
4. 为完成有限停止/PG事实登记临时清掉已记录的线程中断位，finally在离开offload/close前恢复；保留取消信号，同时不让JDBC清理被已经记录的标记打断。READY前再次核实际中断和原deadline。
5. 算法失败不即时DELETE。CLEANUP_PENDING与延后cleanup_after在同一次PG更新记录，避免先置now再延后之间被cleanup抢占；没有先删除后标DELETED。原共享store后续按持久归属受控清理，只有实际清理完成才记DELETED。已接收远端写入可能继续完成，任何本地future/call结束都不等同外部撤销。

### 可确认退出与超出有限收尾的区别

`interruptedSdkWaitStopsOriginalOperationBeforeReturningAndNeverRacesCleanup` 在真实PG与有限Latch S3 peer的三个实际阶段中断：PUT ACK等待、GET响应头等待、GET正文读取。测试在worker返回瞬间检查：原SDK stages=0、真实Call集合空、独有executor已terminated、输入已关闭、中断位true；远端Latch此时仍未释放。随后才放开远端，确认无迟到请求/无READY/无即时DELETE、状态CLEANUP_PENDING，原共享store真实PUT+GET继续成功，最后清理自有对象。独立新夹具只承担这些阶段闩锁职责，正文<1KiB、单请求<4KiB、最多32路由、每Latch最多1500ms，没有资源耗尽探针。

正常确认分支定向实测PUT/GET头/GET正文返回分别23/26/23ms。最初未修复版实际红为2 tests/2 failures/0 errors/0 skipped：PUT和GET头等待返回后中断位均false；日志 `r1-interruption-red.log`。首次绿日志431/420ms包含后续共享store和清理，不能当worker返回耗时；后来修正计时点后以 `final-algorithm-directed.log` 的23/26/23ms为准。

极端有限分支由 `unconfirmedStopRetainsOriginalFutureUntilActualFiniteSourceExit` 单独验证：一个2字节源在close之后仍等待有界Latch，让真实SDK输入stage超过200ms收尾界限。此时返回 STOP_NOT_CONFIRMED **不表示已经释放**：PG last_error_code=ALGORITHM_IO_STOP_NOT_CONFIRMED、CLEANUP_PENDING；原future的whenComplete与Call listener继续强持有原专属生命周期，stopping继续禁止新Call，stage尚未结束时不dispose。测试先断言retained=true/quiescent=false、零网络，再释放有限源；原future真实结束后completion action才关闭专属资源，随后quiescent=true，仍零新网络。不会取消原包装future、遗弃它、另起转存/无限wait或关闭共享池。

这个极端分支保留并明确暴露“尚未确认退出”的本地事实，不承诺任意不合作InputStream/平台故障都能在200ms内物理退出，也不把它当作正常完成/外部停止。没有搭建分布式占用框架；本次scope与原future/Call生命周期持续拥有未结束资源，独立审查需判断此剩余边界。所有正常受控SDK网络中断与200ms期限分支均实际确认本地退出。

## R2：安全诊断与真实冲突因果

未把原审查首轮46/3与随后3绿改写成通过；原异常已被吞掉，无法补造其历史异常正文或旧XML。本轮先添加安全诊断后，以审查原46选择器在新测试JVM执行一次，实际46/1：`trustedFailedJobIsNonRetryableAndDuplicateDoesNotCreateNewAttempt` 在BIND_UID阶段出现 DuplicateKeyException，duration_ms=73，绑定SENDING/UID=null，远低于500ms预算。见 `r2-original46-cold-diagnostics.log` 与只读 `r2-cold46-safe-pg.log`。原三项此轮通过，但不能用它们通过替代调查。

只读PG按明确原四个call查询发现固定Java fixture UID `fixture-job-uid` 与持久旧行的 `(external_api_sha256,external_namespace,external_job_uid)` 冲突。随机loopback端口被OS再次分配，使API origin摘要相同；namespace固定alg-test，而fixture跨实例复用了相同UID。产品部分唯一索引正确拒绝了这种身份占用，BIND_UID失败前已SENDING，因此UID为空且未到beforeLog。

| 失败call | 占有相同API/ns/固定UID的旧call | 原失败created_at | 旧占有行updated_at |
| --- | --- | --- | --- |
| 493446154736600（原审查） | 492381926308700 | 10:06:37.049669 | 09:48:52.949270 |
| 493447732221600（原审查） | 491170793730700 | 10:06:38.626735 | 09:28:41.604420 |
| 493450561317800（原审查） | 491174507514600 | 10:06:41.480437 | 09:28:45.323433 |
| 494240932136800（本轮安全诊断复现） | 490931563256900 | 10:19:51.817835 | 09:24:42.376127 |

日期均为2026-10-20 +08:00；准确摘要/状态join见 `r2-fixed-uid-collision-evidence.log`，早于失败的时间证据见 `r2-uid-collision-chronology-full.log`。部分旧行来自纯Protocol测试，未必有execution_fact，因此初次只join双侧fact的chronology只返回一行；保留这次结果，不把它冒充完整四行。上述原三行有充分的既存唯一索引冲突条件，与本轮真实异常复现机制和原阶段吻合；仍不声称保存了原已丢失异常文本，或排除了历史同时发生的所有其他错误。

修复只在Java fixture给每个实例分配UUID Job UID，并让Pod owner默认同步该UID；实例内每次响应仍稳定。产品API/ns/UID唯一约束、原身份、500ms成功用例和200ms真正期限用例都未放宽，也未清除旧PG行来获得绿。`independentFixturesHaveDistinctStableJobUids` 固定UID版行为红1/1后转绿。`reusedApiNamespaceUidIsRejectedWithSafeBindStageDiagnostic` 在真实PG明确seed旧占有行，真实POST返回后仍验证BIND_UID/DuplicateKeyException、当前SENDING/UIDnull、一次POST，证明约束继续生效。

### 诊断安全边界

AlgorithmAdapter的通用catch只输出固定stage、异常类名或HTTP状态、数值call/attempt。阶段覆盖GET_ORIGINAL、SEND_CAS、POST_JOB、GET_CONFLICT、BIND_UID、LIST_PODS、POD_IDENTITY、LOG_GET、OUTPUT、POLL_DELAY/POLL_GET；不输出原始异常message、response body、参数、URL、Authorization或Secret。错误语义仍RESULT_UNKNOWN、只查原Job，不因分类开放重发。普通unknown的业务固定原因保持既有语义。

Java fixture将RuntimeException和AssertionError存入测试内存；assertHealthy只向测试线程返回异常类别，失败时正常关闭资源后显式失败，不把后台断言关流当作成功。两类故障注入POST分别得到HTTP_500，畸形小ACK得到JsonSyntaxException，确定性UID冲突得到DuplicateKeyException。诊断测试使用敏感哨兵正文并断言其不出现在错误中，正常日志只打印允许的分类。临时恢复原genericcatch时三项行为红3/3；finally恢复原源码字节后转绿。没有从生产凭据或真实业务body构造探针。

修复后按原46选择器再执行一次：46/0/0/0，见 `r2-original46-after-uid-fix.log`。这次针对已确定的fixture身份问题修复后回归，不是循环挑选偶然绿。随后全部算法89/0/0/0，覆盖Protocol54、Execution29、Migration2、Interruption4。

## N1：更正报告时序

仅更正当前 `algorithm-protocol-report.md` 中“POST前已非PREPARING”的错误描述。实际POST到达时：engine已经接收submitted，binding从REGISTERED变SENDING/send_started=true，而execution_fact.callback_state仍PREPARING，用来阻止outbox过早交付。生产时序没有为迎合报告而改变。原frozen报告、原patch与manifest原字节保留。

## 实际命令和结果

新入口 `./scripts/test-algorithm-protocol-review-fixes.ps1` 沿用本工程JDK21、本地Maven仓库、PG凭据注入和JVM有限临时目录；只将日志写到本轮目录，支持审查原有#方法+选择器，校验新鲜Surefire XML和零skip，不启动服务。新增算法默认选择包含中断套件。只使用本工程明确两个算法专库，原其他协议套件使用各自既有隔离测试资源；不清运行库。

| 日志/测试选择 | tests/failures/errors/skips | 证据 |
| --- | --- | --- |
| r1-interruption-red.log / AlgorithmOffloadInterruptionTest（初始两参数） | 2/2/0/0 | 实际SDK等待中断丢标记 |
| r1-interruption-first-green.log | 2/0/0/0 | 首次修复，计时范围后已校正 |
| r1-interruption-and-deadline.log | 5/0/0/0 | 三中断+两200ms期限；同轮编辑脚本有UTF8读取错误，未因此声称未执行的新增断言已覆盖 |
| r2-original46-cold-diagnostics.log / 原审查46选择器 | 46/1/0/0 | BIND_UID真实DuplicateKey复现；保留该失败 |
| r2-fixture-uid-red.log | 编译失败，不算行为红 | 新测试误用不存在helper，修正后才取得下一行行为红 |
| r2-fixture-uid-behavior-red.log | 1/1/0/0 | 跨实例固定UID重复 |
| r2-safe-diagnostics-red.log / 暂恢复原Adapter genericcatch | 3/3/0/0 | 三类诊断缺失行为红，finally恢复源码 |
| r1-r2-targeted-green.log | 10/0/0/0 | 首次包括retained边界和诊断/真正deadline；后补PG分类/AssertionError参数进入最终套件 |
| r2-original46-after-uid-fix.log / 原审查46选择器 | 46/0/0/0 | 仍500ms，无删断言/改产品索引 |
| final-algorithm-directed.log / 四个算法套件 | 89/0/0/0 | 三中断23/26/23ms；200ms尾段227/227ms，原共享687/577ms |
| final-full.log / -Tests '*' | skill 386/0/0/0；common 10/0/0/0（合计396） | 最终所有skill/common，源码/POM前后摘要绑定 |

最终整轮中PUT/GET头/GET正文的中断返回25/23/22ms；真正200ms尾段224/223ms，原共享入口567/569ms。最终91个src/POM原字节相等，原492证据摘要相等。控制台打印已保存日志中的中文分类行时发生过一次GBK输出编码错误，它位于上述SHA核验、XML复制及报告更新之后，不是Maven测试失败；最终摘要/冻结核验均用原字节重新确认。

最终原HTTP异步/Callback/proof/MCP/Schema/原attempt兼容由完整skill整轮包含，不单独拼接新七模块计数。普通200ms尾段新增assertFalse(Thread.currentThread().isInterrupted())，与真正请求线程中断后的true形成正反例；原<450ms、PUT/回读次数、无READY、CLEANUP_PENDING、原共享store可用及受控清理断言保留。

固定源码依据仍为原读源包：MinioAsyncClient公开putObject/getObject，S3Base的supplyAsync PartReader、multipart supplyAsync/get/abort链及enqueue；OkHttp4.12 RealCall.callStart、async timeout.enter、Call end和finally dispatcher.finished。完成future可能早于dispatcher收尾，因此新屏障同时核原future自然完成、Call结束与executor终止。有限测试正文未超过5MiB，没有亲跑multipart场景；其原future链覆盖依据固定源码机制，仍交独立审查核验。相关原字节摘要见原sdk/source-sha256.json，此轮没有更新依赖、复制上游实现、读宿主配置或新造K8s/S3客户端。

最后完成实际git apply --check及apply后与frozen逐字节比较，且复核原492证据未改后归还target。等待原独立reviewer复核；真实engine+skill进程纵切、生产K8s/RBAC/容器、容量与外部效果撤销仍为后续验证，本轮未执行或代签。
