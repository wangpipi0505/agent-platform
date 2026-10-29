# SDK中断：第一阶段测试诊断候选

准备日：2026-10-29。仅准备候选，尚未编译、运行Java/Maven、连接PG或启动HTTP。本轮没有读取.env、没有操作Git index/提交、没有修改主入口或任何SSE候选文件。实际窗口仍由root单独交接。

唯一改动源：`skill-registry/src/test/java/cn/agentplatform/skill/infra/AlgorithmOffloadInterruptionTest.java`。

- 候选SHA256：`D64CD4620B234D11EBE76BDEA78917C1D6D71257986C7A5EB26E832D45FEDE5A`。
- 本轮补充后静态比对时HEAD：`28aa99ea12cb96bbbf781859c017acebd30647a3`。
- 本次diff为277行新增、4行替换；均为同类可选诊断、原首次布尔固定及原finally内观察。
- ArtifactStore仍为`9DEB51566206D548110F1F6B5AAAB466619EB80D43E8D30B67F11B7E27B7541D`，与只读准备一致；其余产品/数据库辅助类/POM/SDK均未写入。

## 开关及原范围

默认关闭，只有显式`-Dex27.algorithmInterruptDiagnostic=true`才取额外状态/栈/PG并打印`ALGORITHM_INTERRUPT_DIAGNOSTIC`安全JSON。关闭时不做这些诊断IO。未新增依赖、测试方法、生产hook或后台观察线程；仅增加测试内helper。

原四报告身份保持：

1. `interruptedSdkWaitStopsOriginalOperationBeforeReturningAndNeverRacesCleanup(String)[1]`：PUT。
2. 同方法`[2]`：GET。
3. 同方法`[3]`：GET_BODY。
4. `unconfirmedStopRetainsOriginalFutureUntilActualFiniteSourceExit`。

原参数顺序、25处断言、15处await/join/sleep/2秒deadline表达式均保留；独立未确认向量和整个LatchedS3实现未改。首次原`algorithmIoQuiescent()`只读一次保存为`quiescentAtFirstCheck`，随后原assert使用这个值；诊断不替换期望，不取消future，不修改字段/生产200ms，不因稍后退出把原false改成true。

## 安全JSON与观察边界

顶层字段：`stage`、`joinReturnMs`、`quiescentAtFirstCheck`（未走到原检查时为null）、`firstCheckSinceInterruptNanos`、`originalBodyCompleted`、`samples`。`joinReturnMs`直接复制原已计算的returnMs，是从原interrupt前start到join及原存活断言结束后的观察耗时（毫秒），并非worker内部精确返回时刻；未到该行时为null。`firstCheckSinceInterruptNanos`是随后首次quiescent判断的相对时刻（纳秒）。其余观察时刻为相对nano值；join返回耗时、首次检查值/时间及后续取样值分开。

独审补充的唯一差异：相对旧候选`D32B1DF8293CA0435C8996DFDCAC0E5E3D695F8841D03C81A08D8160D2C314C3`仅加3行：原returnMs计算之后/后续原assert之前复制到诊断Long字段，并在最终JSON写出joinReturnMs。没有移动或新增采样、等待、判断、字段快照或时间读取。D32与本候选均未Java编译/运行，不能把D32计为红。

最多4个样本，固定phase：

| phase | 位置 | 额外PG读 |
| --- | --- | --- |
| `peer-entered` | 原服务端闩锁已到、原interrupt之前；仅此处取worker安全栈 | 否 |
| `before-peer-release` | 首次原判断及已经达到的原断言之后、第一次显式释放peer之前；失败也经finally进入 | 是 |
| `after-original-peer-wait` | 原peer.finished/40ms及原无迟到请求/无READY断言都已通过之后、原共享store复用之前 | 是 |
| `after-finally-peer-release` | 原finally释放/interrupt/worker.join(1000)之后；即使原判断失败也保存 | 是 |

后两段没有新增轮询或延长等待；是既有等待结束时的事实，不能据此承诺已覆盖更长的有限退出时间。主try中的释放前样本只取一次，finally不重复。成功进入共享store复用/原清理后，最终routes/deletes/PG可能包含这些原测试动作，不能把最终计数差直接当成“迟到危险动作”；原无迟到断言及`after-original-peer-wait`样本单独判断。

每样本包含worker alive/interrupt/原finally捕获flag、输入close次数、peer entered/release/finished剩余计数、routes和deletes计数。不会输出请求路径、对象key、tenant/run/call值、URL、headers、body或凭据。

- 生命周期：原AlgorithmIo monitor内只短读stages、calls.size、stopping、interrupted、stopNotConfirmed、cancelUntil是否设置及其相对interrupt值；不遍历Call或toString。退出该monitor后才读原owned executor shutdown/terminated及ThreadPoolExecutor可得active/queue大小，另记录取样时刻与`quiescentAtSample`。这些字段不声称跨对象原子快照。
- 若原peer闩锁根本未到，不调用可能懒建executor的SDK getter，明确写`OMITTED_NO_PEER_ENQUEUE_WITNESS`；仅记录已有项目生命周期字段。正常闩锁到达证明该专有client已真正enqueue，但不能证明客户端已经进入正文读取。
- worker栈最多扫描64帧、输出16个白名单本仓/MinIO/OkHttp/Okio/Jackson/CompletableFuture帧，只输出类、方法、行号。另写扫描/输出上限、是否扫描截断、是否实际观察到ArtifactStore.readJson或AlgorithmIo.await帧；没有该帧不能反推路径不存在。
- 异常树最多16节点，保留root/cause/suppressed关系和父索引；类名仅输出固定依赖包前缀白名单或固定PlatformException类，其余为OTHER_THROWABLE。只识别`ALGORITHM_IO_STOP_NOT_CONFIRMED`、`OUTPUT_ARTIFACT_UNAVAILABLE`、`ARTIFACT_STATE_CHANGED`固定码；读取message仅做等值/固定前缀匹配，绝不输出任意message/异常栈/对象。
- PG单独JdbcTemplate读取当前随机tenant/call的一行聚合（rows、READY数、state、last_error_code），queryTimeout=1秒、maxRows=1，不修改原共享template。输出state只允许UPLOADING/CLEANUP_PENDING/READY，错误只允许ALGORITHM_IO_STOP_NOT_CONFIRMED/OFFLOAD_FAILED，其他值分别变成NULL/OTHER。没有DDL、reset/drop/truncate或额外写入。
- PG语句超时不独立约束原fixture DataSource的连接获取。因此实际准入仍必须确认本仓25432/既有算法专库的健康与归属，并使用root的外层测试有界窗口；不能把queryTimeout写成所有连接故障都受1秒硬界。PG与输出均不持有原AlgorithmIo monitor。诊断异常只记录安全类别，不替换原assert；原清理finally全部保留。

本轮每参数最多4样本、最多3条单行PG聚合；异常分支少于这个上限。先缓存内存，最后finally统一打印，不在原首次判断之前写盘或查PG。开关启用仍可能扰动调度，额外PASS只能作为额外观察，不能覆盖EX-03 full原新增FAIL。

## 已做静态检查与下一窗口

[PowerShell5.1解析记录](candidate-script-parse.json)为5.1.26100.9711、0解析错误；[静态比对记录](candidate-static-check.json)13项检查均通过。先5.1 ParseFile，后才5.1执行同目录verify-candidate.ps1，检查内容仅读Git/源并写本目录静态JSON；没有Java语法编译或运行。

`verify-candidate.ps1`是针对这份候选的有限静态文本比对，不冒充Java解析器或测试通过。复核命令：

```powershell
& powershell.exe -NoProfile -File .\docs\evidence\2026-10-28-EX-27\runs\algorithm-diagnostic-20261029-prepare-002200-707\verify-candidate.ps1 -RepositoryRoot 'G:/207/项目/算法重组工具/代码/agent-platform'
```

独立静态审通过后才单独交接真实窗口。实际运行必须另建`runs/algorithm-diagnostic-<实际日>-<新runid>/`，不把这个prepare目录当真实运行；沿本卡.gitignore保存raw.log/xml，不改SSE的public-stage-files.txt。准入先核本仓PG25432当前PID/数据目录及既有算法库owner/Flyway归属；不清楚即停给root，不依赖AlgorithmTestDatabase自动建库分支认领。未执行本次准入，也没有编制或执行真实运行脚本。

原full模块FAIL和全部原XML继续保留。实际诊断之后仍要经技术门区分正例前提不足与真实产品违约，再决定后续；产品确证另立准确卡。一次额外PASS不关闭EX-03、EX-27子步或整卡。
