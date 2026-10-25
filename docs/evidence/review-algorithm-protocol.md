# 算法协议独立审查

审查日期：2026-10-20。唯一工程：`G:/207/项目/算法重组工具/代码/agent-platform`。依据 review brief、implementation brief、原计划和 `algorithm-readonly-retry-decisions.md`，并实际检查冻结生产代码、测试、固定 SDK 源码、完整 patch 和作者日志。使用 code-review；遇到亲跑失败后使用 systematic-debugging。没有修改作者源码、测试、原证据或预算，没有 package、启停主服务、访问相邻旧工程、派子 agent 或运行耗尽/慢正则探针。本报告是唯一新增审查文件。

## 两个结论

- **需求符合性：REQUEST_CHANGES。** 原代登记、唯一发送、UID/Pod 归属、只读 NOT_SENT 新代交接、同代终态采纳、字节限制和通常 deadline 路径有实际实现及相应证据；算法转存的线程中断路径没有保留中断标记，也没有建立原 SDK 异步操作退出后再释放调用的保证，不满足计划 §3.4 和总期限补充的资源生命周期要求。
- **代码质量：REQUEST_CHANGES。** 核心协议采取默认拒绝，事务边界清楚，HTTP 专用 asynchronous 含义保持；但中断包装异常未处理、异常原因被合并导致本次失败不能定位，且独立首轮 46 项中 3 项真实失败尚未闭合因果。仅后续 3 项绿不能替代一次可信的受影响回归。

这两个结论只针对本增量。不能外推生产 K8s/RBAC/容器、容量、全 T11/G2，亦不是实际 engine+skill JVM 纵切结论。

## 发现

### R1 [P2] 算法 MinIO 转存中断丢失线程标记，并在原异步操作退出前进入清理/释放路径

位置：`skill-registry/src/main/java/cn/agentplatform/skill/infra/ArtifactStore.java:207`、`:217`、`:234`、`:243`、`:165`；`infra/SkillAdapters.java:331`；`infra/AlgorithmAdapter.java:492`（均在同一 Java 包根下）。

触发：算法大输出进入专用 ArtifactStore，在 `client.putObject(...)` 或 `client.getObject(...)` 等待 SDK future 时，请求线程被中断，而上传/回读 HTTP 调用尚未完成。这是取消/本地执行中断路径，不需要大正文或资源耗尽才能触发。

固定读源证据：本轮保存的 `sdk/io/minio/MinioClient.java:1371` / `:209` 实际调用 `asyncClient...get()`；`:1372` / `:210` 捕获 InterruptedException 后仅抛 RuntimeException，既不取消原 future，也不恢复线程中断标记。`sdk/io/minio/S3Base.java:2939` 有独立 supplyAsync 读流，`:612` 起有 enqueue 的 HTTP future；多分片还在 `:2840` 使用独立异步链。这里不是假定 MinIO 同步 API 等于没有后台工作。

当前 offload 捕获上述包装异常后，先将产物改为 CLEANUP_PENDING 并调用 deleteObject；原操作此时并没有被取消或等待。随后再次包装为 IllegalStateException，退出 `SkillAdapters` 的 try-with-resources 才调用 close。close 只有 cancelAll、evictAll、shutdown，没有原 future/操作完成确认；AlgorithmAdapter 的恢复逻辑只判断顶层 `e instanceof InterruptedException`，因此经过上述两层包装后不会恢复已经清除的中断标记。

影响：本地取消不能保留线程取消信号，清理会与尚在进行的上传/回读竞争，调用返回也没有“原 SDK 操作已退出”的同步依据。不能据此宣称取消后没有后台转存或已经安全释放占用。这里没有声称已实际复现对象丢失、远端效果撤销失败或永久线程泄漏；中断分支是通过固定 SDK 与本次代码控制流确认的缺口，本轮未添加/执行新的中断探针。

建议修复方向：算法专用入口需要能管理真实 SDK 操作的取消与完成，识别包装的中断并保留标记；本地取消后先停止并确认自己的 I/O/异步阶段退出，再处理结果/清理事实和释放本次资源。只关闭专用实例，保留共享 HTTP/MCP store 原入口。用小正文、有限 Latch 在 PUT 等待和回读等待阶段中断，断言调用返回时本地原操作已终结、无迟到发送/READY、中断标记保留及共享入口仍可用。不要用无限等待或提高产品 deadline 替代退出机制。

正常 deadline 和中断必须区分：OkHttp `RealCall.kt:515` 开始 timeout，onFailure 在 `:525` 通知 SDK future，`:536` 才完成 dispatcher bookkeeping。正常失败的 future 完成时，实际 I/O 可能已经退出，剩余只是回调收尾；不能仅以 dispatcher 线程短暂仍存在判定网络仍在运行。本发现依赖的是 **被中断的 get 提前返回，原 future 未完成** 的具体路径。远端 fixture 的 sleep/active 也不等同本地 I/O 活跃。

### R2 [P2] 独立执行三项在目标检查点之前失败，当前诊断无法闭合原因

位置：`skill-registry/src/test/java/cn/agentplatform/skill/application/AlgorithmExecutionPostgresTest.java:315`、`:717`、`:654`；生产 `infra/AlgorithmAdapter.java:492`；测试端 `infra/AlgorithmApiFixture.java:188`。

独立首次命令实际 **46/3/0/0**，其中 Execution29 的三项失败如下；Migration2 和 Protocol15 通过。后续只复跑这三项为 **3/0/0/0**，源码和测试未改变。首轮失败不能被后次绿覆盖。

| 首轮方法 / 断言 | 实际结果 | 专库残留事实所示阶段 |
| --- | --- | --- |
| preparingAndRegisteredExistBeforeSubmittedAndFirstPost:315 | expected SUCCEEDED, actual UNKNOWN；test elapsed 0.512s | call 493446154736600：duration_ms=141，binding=SENDING、send_started=true、UID=null |
| staleObservationCannotAdoptReplacementBindingUid:717 | Expected SkillAdapters.Failure to be thrown, but nothing was thrown；test elapsed 0.417s | call 493447732221600：duration_ms=70，binding=SENDING、UID=null；beforeLog 的 replacement UID 注入没有到达 |
| twoAuthorizedRetryWorkersPrepareOnlyOneGeneration:654 | expected SUCCEEDED, actual UNKNOWN；test elapsed 0.771s | call 493450561317800：duration_ms=120，binding=SENDING、UID=null；前面的 POST=1 与新代457断言已通过，未完成结果收敛 |

上述 call 对照测试套件确定的执行顺序和本轮时间段的专库持久行；没有把它们当作所有 UNKNOWN 行。三行 error 都是 `RESULT_UNKNOWN` / `K8s协议、字节或总时限未通过；保留原Job仅对账`。测试默认 timeoutMs 为500（`:191`），这些适配器执行段远低于500ms，**不能认定为 deadline 耗尽，也不能直接认定为生产 CAS 失效**。已知的是：独立首轮没有到达这些成功/UID/终态目标检查点。重跑三项正常到达并通过，没有继续循环重跑以挑选绿。

AlgorithmAdapter 的通用 catch 丢弃了异常类别、阶段和 HTTP 状态，fixture 的 RuntimeException 分支也只返回通用错误；因此现有材料不足以区分测试端异常、连接/解码失败或其他早期失败。请求修复/补证的是可定位性与可靠验证：保留不含凭据/正文的阶段、异常分类和请求状态诊断，fixture 将后台断言/异常明确回传给测试线程，再查明首轮原因并重跑受影响范围。不要仅调高500ms或删掉现有断言。此条不是声称已查明底层故障类型。

### N1 [P3] 作者报告的 PREPARING 时序描述与实现/测试相反

`docs/evidence/algorithm-protocol-report.md:21` 写“POST前已非PREPARING”。实际 `AlgorithmExecutionPostgresTest.java:311` 明确断言 POST 到达时仍为 PREPARING，`:312` 断言的是 binding 已为 SENDING；SkillExecution 的事实在取得结果/恢复时才离开 PREPARING。现实现保留 PREPARING 用来阻止 outbox 在外部观察之前投递未知事实，并非此处生产协议缺陷。应更正报告，区分 engine submitted、binding SENDING 和 fact callback_state。

## 已核实的范围与证据

字节核对：变更清单16条，当前文件与 after SHA、所有修改项 baseline 与 before SHA 全匹配；94份 frozen 及94份实际 apply-replay-no-autocrlf 的文件全部匹配；索引125份证据摘要全部匹配。完整补丁 SHA256 为 `674fcc2b768c0ebca9be11980e5a261b2343a818db1842dd979c5950bb228159`。作者完整回归前后90份 src/POM 清单相等，本次测试结束后当前90份也与之相同。本轮没有重新 apply 写文件；是独立核验作者实际重放目录的字节，不冒称自己执行了 apply。

实际读差异包括五个生产文件、V311、六份算法 Java 测试/fixture、测试脚本、Node runner 和参考文档；通过完整上下文 patch及baseline/current差异确认。Node runner仅静态审查，不在本轮启动实际服务。

| 边界 | 独立判断与依据 |
| --- | --- |
| 登记与发送 | SkillExecution:222/239 的 persistentBinding 在原 PREPARING 短事务登记；submitted:271 后才观察。AlgorithmAdapter:502 用完整身份/快照/metadata及 REGISTERED/send=false CAS；SDK禁重定向和连接重试。网络在事务外。首次注册与终态更新回滚测试均有真实PG断言。 |
| 身份与 UID | AlgorithmAdapter:201/234/292 核 tenant/principal/run/node/call/attempt/version/key、raw/guarded摘要、contract/API/ns/image/SA；:612 每次原Job/409后读验 metadata与本地identity；UID只能空值首次绑定或相等。该摘要不是签名，可信API/ns/RBAC仍是外部前提。 |
| Pod 与输出 | :749 核唯一候选、无continue、namespace/UID、唯一controller的batch/v1 Job/name/UID、algorithm容器exit0；:440固定容器/follow=false。完整JSON流验证与Schema/产物归属没有被日志截断替代。 |
| 只读新代 | :532原fact锁之后锁binding；只允许 FAILED/RETRYABLE+readonly/非sideEffect+可靠NOT_SENT/UID空的新权威attempt。旧事实和整个旧binding原子归档，新PREPARING在发送之前完成；UNKNOWN、已发送、无新权威被拒。新完成对新attempt做CAS。首次真实503分类测试与交接测试组合成立；注意交接helper还显式seed旧FAILED/NOT_SENT，不能独自冒称真实engine调度闭环。 |
| 原ACK与原Job | W1仅NOT_SENT fence；旧attempt不能借新binding；ACK未知保持原名只查，失败计数不是终态，可信Failed为NON_RETRYABLE。对账只查原Job，queryOnly缺binding不插入。 |
| 同代事实 | SkillExecution:952先fact再binding，AlgorithmAdapter:682核source/UID/快照；DONE与终态fact同事务。实际PG回滚、旧fact、已终态与UID替换测试（UID项首轮失败见R2）检查旧观测不能污染新事实。 |
| 原保护路径 | asynchronous仍仅HTTP，persistentBinding单独新增ALGORITHM；普通HTTP/MCP的read入口传deadline=0，原store配置不变；回调/proof没有被算法UNKNOWN转换成写许可。作者原HTTP/MCP相关140项与最终完整377+10日志及摘要已核；本轮不再重复跑它们。 |
| 迁移与legacy | V311只加nullable列、算法部分唯一索引及不可变binding历史，没有修改旧V文件或全局NOT NULL。本轮实际跑V310→V311、HTTP原行/proof摘要保护、算法唯一约束；主算法测试库已在V311，只能把本轮该分支称validate/up-to-date，不称重新空库迁移。作者早期空库日志另有保留。缺主体/缺binding的legacy整体reconcile保持审计UNKNOWN、零网络/无proof。 |

## 76/1、拆分尾段测试与资源边界的技术判断

作者旧 `algorithm-final-directed.log` 为76/1/0/0，UPLOAD_ACK的expected2/actual1断言确实失败；其阶段前提要求整条K8s/PG前段之后还能在200ms内到达目标PUT，这不保证成立。保留此日志并把“确实到达尾段”的用例从前段合法截止用例拆开是合理的测试设计调整，不是靠放宽产品期限获得绿。

拆分红 `algorithm-red-minio-split.log` 为2/2/0/0，旧无deadline委派应超时却未抛出。当前尾段用真实SDK、PG和小型MinIO fixture，从一个确定200ms绝对deadline开始，仍严格检查PUT+1、READBACK的GET+1、<450ms、无READY、CLEANUP_PENDING以及共享原入口慢调用成功；本轮亲跑两参数分别232/227ms，共享入口580/567ms，清理后自有对象0。

完整链测试:894捕获实际SDK链进入readAlgorithm的deadline，并验证前段消耗预算；其入口时间是首次GET接收时间的近似比较，并非捕获内部最初变量的逐bit等式。结合生产代码只有一次deadline创建并原值传递，可以支持没有尾段重新起算。frontStage测试:928验证前段过期时零PUT/零迟到POST。这三组组合证明通常统一期限与尾段I/O取消，不能等同“整个链每个调度都必然在200ms内到达PUT”。

元数据/日志硬闸位于SDK物化前的应用拦截器，透明gzip解码后消费至cap+1即拒绝；Okio固定8192预取不属于应用cap+1承诺，也不是TCP配额。本轮gzip成功/错误/log三项和可见source+close断言均通过。

资源证据的准确边界：尾段测试:832先在调用返回后等peer active至0（最多700ms），随后:838检查没有同端点OkHttp线程；它证明本例最终不再活跃、后续无新请求以及共享client可用，不能证明返回瞬间所有本地任务已退出，更未覆盖R1的请求线程中断。peer active可仅代表远端延迟处理，不表示本地仍有I/O。正常deadline已确认取消真实HTTP，而非Future超时后任其正常上传；不能把这个正确结论外推到未覆盖的中断分支。

## 本轮亲跑命令与原始结果摘录

工作目录为工程根。凭据仅从明确授权的新工程 `.env` 装入进程变量，不输出值；JDK为 `C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot`。不用会启停服务的test.ps1，不调用作者会写原证据目录的测试脚本；直接Maven，仅更新其正常target结果。

```powershell
$reviewTests='AlgorithmExecutionPostgresTest,AlgorithmMigrationPostgresTest,AlgorithmProtocolTest#twoInstancesShareOneDurableSendCas+identicalJobRecoversThrough409WithoutSecondPost+wrongPodOwnerNeverReadsLogs+podControllerMustBeTrue+podOwnerVersionMustMatch+replacingUidCannotReplaceOriginalBinding+decodedGzip*+sdkVisibleSourceConsumesAtMostLimitPlusOneProbeByteAndCloses+trailingJsonCannotProduceReadyArtifact+algorithmOutputDeadlineCapsMinioUploadAndReadbackAndLeavesSharedClientUsable+fullKubernetesChainPassesOriginalAbsoluteDeadlineToOutput+frontStageDeadlineExpiresBeforeAnyMinioPutOrDelayedPost'
mvn.cmd -B -ntp -pl skill-registry -am test '-Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2' '-Djdk.net.unixdomain.tmpdir=V:/.local/tmp' '-Dsurefire.failIfNoSpecifiedTests=false' "-Dtest=$reviewTests"
```

2026-10-20 10:06:24–10:06:53 +08:00，exit1，实际摘录：

```text
Tests run: 29, Failures: 3, Errors: 0, Skipped: 0 -- AlgorithmExecutionPostgresTest
preparingAndRegisteredExistBeforeSubmittedAndFirstPost:315 expected: <SUCCEEDED> but was: <UNKNOWN>
staleObservationCannotAdoptReplacementBindingUid:717 Expected cn.agentplatform.skill.infra.SkillAdapters.Failure to be thrown, but nothing was thrown.
twoAuthorizedRetryWorkersPrepareOnlyOneGeneration:654 expected: <SUCCEEDED> but was: <UNKNOWN>
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- AlgorithmMigrationPostgresTest
ALGORITHM_MINIO stage=UPLOAD_ACK budgetMs=200 elapsedMs=232 originalEntryMs=580 POST=0 active=0 objectsAfterOwnedCleanup=0
ALGORITHM_MINIO stage=READBACK budgetMs=200 elapsedMs=227 originalEntryMs=567 POST=0 active=0 objectsAfterOwnedCleanup=0
Tests run: 15, Failures: 0, Errors: 0, Skipped: 0 -- AlgorithmProtocolTest
Tests run: 46, Failures: 3, Errors: 0, Skipped: 0
BUILD FAILURE
```

相同Maven参数，仅替换选择器为：

```text
-Dtest=AlgorithmExecutionPostgresTest#preparingAndRegisteredExistBeforeSubmittedAndFirstPost+staleObservationCannotAdoptReplacementBindingUid+twoAuthorizedRetryWorkersPrepareOnlyOneGeneration
```

2026-10-20 10:07:54–10:07:57 +08:00，exit0，3/0/0/0，BUILD SUCCESS。第一次Execution XML被Surefire正常覆盖为这次3项报告，故本报告保留第一次工具原始结果摘录和对应PG行；没有伪装成留存了一份完整首轮XML/原始日志文件。作者final-surefire和所有原日志未改。

另执行只读psql，固定 `127.0.0.1:25432 / agent_platform_skill_algorithm_test / agent_v12`，查询本轮10:06:24至10:06:44的 `algorithm_execution_%` fact与binding，限定输出call/status/duration/error/send_started/UID，用于R2阶段定位。没有读取运行库或原始参数/凭据。仅迁移测试按白名单重建其自有 `agent_platform_skill_algorithm_migration_test` 的skill schema。

本轮共49次测试执行（46项首轮+3项定向复跑），46个不同用例；首轮有3失败，无error/skip。作者80算法定向与377 skill+10 common完整回归是核验过的历史冻结证据，不与本轮数字拼成“全部通过”。

## 交接

需原作者处理R1，并为R2补足首轮失败原因/可靠阶段验证，修正N1报告时序后重新冻结及复核。不得以本报告代签完整G2。审查写入完成后归还skill Maven target；没有遗留本轮运行中的Maven会话或新增服务进程。

---

## 返修复审追加（2026-10-20，最新结论）

本节针对正式冻结的 R1/R2/N1 返修，依据 `algorithm-protocol-review-fix-brief.md`、`algorithm-protocol-review-fixes.md` 以及本轮 `algorithm-protocol-review-fixes/source-sha256.json` 和完整差异。上文首次 REQUEST_CHANGES、46/3首轮及3项后次绿、合计49次执行含3失败的历史保持原文，不回写成全绿。

**需求符合性：APPROVE（限本次算法协议增量及R1/R2/N1返修范围）。** 请求线程中断、通常deadline和未确认停止分支现已分开处理；正常受控I/O取消在返回前确认退出，极端未确认分支保留实际原future的生命周期，不再把返回误作资源已释放。原三项早期失败已取得固定UID冲突的直接新复现、历史持久条件和确定负例，未放宽产品索引或期限。N1更正与实际时序一致。

**代码质量：APPROVE（同一有限范围）。** 没有发现需继续阻断本返修的具体缺陷。新增生命周期机制集中在算法专用store，原共享入口保留；安全诊断包含阶段/类别而不拼接异常正文。原代/锁序/唯一发送/UNKNOWN门禁未被返修改动。以下限制仍成立，不构成生产或全G2签收。

### R1关闭依据及剩余边界

生产位置：`skill-registry/src/main/java/cn/agentplatform/skill/infra/ArtifactStore.java:114` 的 AlgorithmIo、`:155` 的 await、`:211` 的 stopAndAwait、`:320` 的 close、`:379`/`:389` 的公开异步SDK调用、`:400` 的READY前检查、`:426` 的清理状态登记。

1. 算法现在等待公开 MinioAsyncClient 返回的真实原future；没有cancel包装future来伪造底层任务已完成。原future的whenComplete维护stage计数，Call listener从callStart到callEnd/callFailed维护实际I/O集合，涵盖SDK预读/上传链与返回流。scope内共享对象引用和completion action使未结束的原任务继续拥有对应生命周期。
2. 真正的 InterruptedException 或请求线程实际中断标记会被记录；正常SocketTimeout/InterruptedIOException不会仅因名字含interrupt就伪造请求线程中断。停止时封闭新Call、cancel当前Call并关闭源，暂清中断位完成有限收尾及PG状态登记，finally恢复。固定绝对deadline仍设置在每个真实Call，业务期限没有因收尾重新起算。
3. 正常可确认停止要求原stage结束、Call集合为空，随后专有executor terminated，覆盖future完成先于dispatcher finally的时序。200ms是一次性有限取消收尾期限；后续close不刷新该期限。测试检查时远端Latch仍未释放，因此该断言不依赖远端handler active归零。本轮PUT ACK、GET头、GET正文三阶段中断返回分别24/23/23ms；请求线程中断位true、输入已close、本地quiescent=true、无即时DELETE，放开远端后无迟到新请求，原共享store真实PUT/GET仍成功。
4. 算法异常路径不再立即DELETE。CLEANUP_PENDING及延后的cleanup_after在同一个PG更新中写入；普通失败记OFFLOAD_FAILED，未确认停止记ALGORITHM_IO_STOP_NOT_CONFIRMED。这是保留清理事实，不是外部写入回滚承诺。共享HTTP/MCP原入口的同步SDK、45s配置和既有清理路径不变。

**STOP_NOT_CONFIRMED的准确解释：** 本轮有限2字节不合作源测试在close之后仍等待Latch，超过200ms收尾时请求线程可以返回失败，但原SDK future尚未结束，`algorithmIoRetained=true`、`algorithmIoQuiescent=false`、PG为CLEANUP_PENDING/ALGORITHM_IO_STOP_NOT_CONFIRMED。此时尚未dispose专有资源，stopping使任何新Call在发出前取消。释放有界源后，原future真实完成的callback才dispose，最后executor终止、quiescent=true，整个测试网络请求数为0。此分支亲跑通过。

我接受这一显式“保留未完成资源”的边界作为R1修复：它不以future.cancel制造完成，也不在无法证明时宣称已释放。**不能把它表述为所有调用返回时均已退出**；它与正常可确认分支不同。当前持有的是进程内原SDK任务/Call及其专用资源生命周期，不是新增分布式并发许可或容量保证。任意永久不合作源、平台故障、进程崩溃、多分片容量及生产资源上限仍未被此有限测试证明。正常实际算法流仍使用受原K8s call deadline约束的CompleteJson输入；本轮没有为验证极端分支而运行耗尽探针。

普通200ms尾段两参数本轮实测226/228ms，返回线程中断位false；随后共享入口579/575ms成功，与主动中断的true形成正反例。完整K8s链传递原deadline和前段过期零PUT/POST两个用例同时通过。超过5MiB的multipart路径本轮未亲跑；生命周期覆盖依据固定MinIO源码的原future链（含PartReader/上传/abort阶段），不能写成实际大文件/分片验收。

### R2关闭依据：历史冲突条件、新诊断复现与本轮负例分别成立

`AlgorithmAdapter.java:504`起只输出固定stage、安全异常分类/HTTP数值状态及call/attempt；`:522`的safeCategory没有原始message/body/URL/凭据拼接。`AlgorithmApiFixture.java:24`给每实例UUID Job UID，`:26`的ownerUid默认同步，实例内仍稳定；`:55`起保存后台RuntimeException/AssertionError并向测试线程显式报出类别。测试finally在关闭资源后检查健康，不再让后台断言关流被当作正常成功。

本轮独立只读PG join：四个失败call分别匹配同API摘要/namespace/固定`fixture-job-uid`的旧占有行，且四个旧行更新时间均早于失败创建时间：

| 失败call | duration_ms | 旧占有call | 旧行早于失败 |
| --- | --- | --- | --- |
| 493446154736600 | 141 | 492381926308700 | true |
| 493447732221600 | 70 | 491170793730700 | true |
| 493450561317800 | 120 | 491174507514600 | true |
| 494240932136800（返修冷46新复现） | 73 | 490931563256900 | true |

旧fixture跨实例固定UID，随机API端口再次分配后与保留PG行形成真实唯一索引冲突；SENDING/UID空且尚未进入日志注入点与该失败阶段吻合。新冷46的实际安全错误明确为BIND_UID/DuplicateKeyException。旧三项已丢失的异常文本不能重建，本结论依据它们当时已存在的冲突条件及新的相同机制复现，不声称有旧异常原文或排除了所有历史并发异常。

本轮确定负例 `reusedApiNamespaceUidIsRejectedWithSafeBindStageDiagnostic` 再次在真实PG制造已占用UID，确认一次POST后BIND_UID/DuplicateKeyException、当前SENDING/UID空，说明生产唯一索引没有削弱。跨fixture稳定唯一UID正例通过；两种后台异常得到HTTP_500、畸形ACK得到JsonSyntaxException，敏感哨兵正文未出现在诊断。原三项现在分别完成登记/终态、UID替换拒绝、并发只一次POST及新代收敛，三项亲跑均绿。

这一闭环是查明fixture身份复用问题后修正并验证，不是单凭重跑偶然绿；原500ms成功预算、200ms期限测试、原生产索引均未放宽。作者原46修复后46/0以及最终89定向、386+10完整日志已核；这些仍属于作者证据，与本轮16项独立执行分开记录。

### N1关闭及冻结核验

当前 `algorithm-protocol-report.md:21` 已改成“POST到达时事实仍PREPARING、绑定已SENDING，engine已接收submitted”。原冻结报告/patch/manifest的错误历史仍完整保留。没有为迎合报告更改生产时序。

独立原字节核验结果：

- 本轮9个变更的当前after SHA及全部修改项before SHA匹配；94 baseline、97 frozen及97实际apply-replay文件全部匹配。当前完整patch SHA256为 `395a4e35aad7c9df62fddcb965950885e20086f143935f80dfa72ab756b3a94c`。
- manifest列出的87份本轮证据摘要全部匹配；492份受保护旧证据仍与返修前摘要一致。作者最终91份src/POM before/after相等，本次亲跑后当前91份仍相同。
- 已核作者git apply --check/apply结果及实际重放文件字节；本审查没有再次apply写入文件。仅追加本报告；没有改源码、测试、预算、迁移、POM、原证据或暂停runner。

### 本轮唯一Maven命令与实际计数

工作目录、JDK21、环境装入与前述首轮相同；只用直接Maven，避免写入作者冻结日志目录。没有完整模块/七模块、package、实际服务JVM或资源耗尽探针。

```powershell
$reviewTests='AlgorithmOffloadInterruptionTest,AlgorithmProtocolTest#postFixtureFailureHasSafeStageAndExplicitBackgroundAssertion+malformedSdkAckRetainsCategoryWithoutMaterializingDiagnosticBody+independentFixturesHaveDistinctStableJobUids+reusedApiNamespaceUidIsRejectedWithSafeBindStageDiagnostic+algorithmOutputDeadlineCapsMinioUploadAndReadbackAndLeavesSharedClientUsable+fullKubernetesChainPassesOriginalAbsoluteDeadlineToOutput+frontStageDeadlineExpiresBeforeAnyMinioPutOrDelayedPost,AlgorithmExecutionPostgresTest#preparingAndRegisteredExistBeforeSubmittedAndFirstPost+staleObservationCannotAdoptReplacementBindingUid+twoAuthorizedRetryWorkersPrepareOnlyOneGeneration'
mvn.cmd -B -ntp -pl skill-registry -am test '-Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2' '-Djdk.net.unixdomain.tmpdir=V:/.local/tmp' '-Dsurefire.failIfNoSpecifiedTests=false' "-Dtest=$reviewTests"
```

2026-10-20 10:33:36–10:33:46 +08:00，exit0：Execution3/0/0/0、Interruption4/0/0/0、Protocol9/0/0/0，**本轮合计16/0/0/0，BUILD SUCCESS**。新鲜Surefire XML逐套件核实计数。实际输出摘录：

```text
ALGORITHM_INTERRUPT stage=PUT returnMs=24 flag=true inputClosed=1
ALGORITHM_INTERRUPT stage=GET returnMs=23 flag=true inputClosed=1
ALGORITHM_INTERRUPT stage=GET_BODY returnMs=23 flag=true inputClosed=1
ALGORITHM_MINIO stage=UPLOAD_ACK budgetMs=200 elapsedMs=226 originalEntryMs=579 POST=0 active=0 objectsAfterOwnedCleanup=0
ALGORITHM_MINIO stage=READBACK budgetMs=200 elapsedMs=228 originalEntryMs=575 POST=0 active=0 objectsAfterOwnedCleanup=0
POST_JOB / HTTP_500（RUNTIME、ASSERTION各一）
POST_JOB / JsonSyntaxException
BIND_UID / DuplicateKeyException
Tests run: 16, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

另以项目自带psql只读上述四个call的专库事实/绑定/旧UID占有及时间，不读取运行库、参数或凭据，不打印密码。未重建测试schema或重复跑迁移。

### 最终交接与未验范围

R1/R2/N1在本轮限定范围内关闭，没有新增阻断发现。保留原首次49次执行含3失败及旧REQUEST_CHANGES，不与本轮16绿或作者回归拼成“历史全绿”。资源未确认分支的保留状态、未亲跑multipart、生产K8s/RBAC/容器、容量、真实engine+skill纵切以及完整G2/T11仍按上文限制。本节写入后正式归还skill Maven target；本轮Maven会话已结束，没有新增运行中的服务/fixture。
