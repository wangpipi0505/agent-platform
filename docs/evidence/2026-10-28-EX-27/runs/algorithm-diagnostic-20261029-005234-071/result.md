# 原类一次诊断：4P，原Full失败未复现

2026-10-29。本轮已完成一次只读PG前置和一次原AlgorithmOffloadInterruptionTest整类诊断；没有循环抽样、Full、其它Java/Node/前端或业务服务运行，没有修改源码、200ms/2s、原断言或index。Maven及独立PS进程结束后已立即明确归还root实际窗口；此后仅只读落盘/源码指纹并整理本报告。未使用superpower。

## 实际执行与原件

- 只读PG前置通过，实际归属/17条Flyway记录及合法SCHEMA分类修正先落盘，详见[采集分类修正](collector-classification-correction.md)。旧004058准入停止原件不覆盖，不计测试红；原轮无阶段证据，同旧采集谓词实证会拒绝本次已核的合法内部建立记录，不假称历史唯一已证根因。
- preflight.ps1与run-once.ps1分别先经PS5.1.26100.9711 ParseFile，0解析错误；SHA256分别`D39F0E2BB574AB1CF7FDF315809AFD8B097BE2DC4968261402E4A7FEF121EEBE`与`E03EC268A94B526DCC00E96B50F8ABE2387BAB0F209BC53587C1519363588983`。
- 真实Maven起止00:58:32.621～00:58:45.032（+08），native exit=0；已先持久化native-exit.json再XML Load/归档。collector exit=0，新鲜1 XML/原4报告身份准确齐：PUT、GET、GET_BODY三参数及独立未确认向量均PASS，无FAIL/ERROR/SKIP。
- 真实命令在command.json：`mvn.cmd -B -ntp -pl skill-registry test`，无-am；本仓m2，`-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp -Dex27.algorithmInterruptDiagnostic=true`传fork argLine，仅`-Dtest=AlgorithmOffloadInterruptionTest`，保留surefire.timeout=900。不skip、不failure.ignore。
- JDK21.0.12.8、.env SHA `AF038F68E0CA2E3EEEEB0C5240B2C2DA1EF647D680500F63AF1564975EBAFD4A`、AGENT_DEV_AUTH/ENGINE_PG_TEST true、未显式security mode均与原Full相同；实际本仓admin-common已安装JAR SHA仍`1B0D7B40FC3AE02B207294D3B6BE7EA7F7BB9DCD35AF8EA62A372F916FA14B87`。
- 运行前后HEAD均`28aa99ea12cb96bbbf781859c017acebd30647a3`；D64测试与9DEB ArtifactStore、.env前后均稳定，见source-before/after、environment和summary。EX09暂存不属于冻结源栈，也未被本代理操作。
- XML SHA256：`000A11FD43DB311AEB70B2B56E20E5B6CB4E0AEA7FBEDB7B1C1F78B712209B2F`；完整XML与raw.log按既有ignore本地保留。准确方法见methods.csv，安全原JSON见diagnostic-facts.json，分项摘要见facts-summary.json。没有任意异常消息、Call对象、URL、headers/body、tenant/call值或秘密输出。

## 三份安全JSON的准确解释

| 参数 | 原join后观察耗时ms | 首次quiescent检查相对interrupt纳秒 | 首次quiescent | entered时worker栈见证 | entered后另取IO字段 |
| --- | ---: | ---: | --- | --- | --- |
| PUT | 30 | 30,986,500 | true | CompletableFuture.get→AlgorithmIo.await→offload:379；bodyRead=false、await=true | stages1/Call1、executor active1，尚未shutdown/terminate |
| GET | 26 | 26,406,400 | true | CompletableFuture.get→AlgorithmIo.await→offload:389；bodyRead=false、await=true | stages1/Call1、executor active1，尚未shutdown/terminate |
| GET_BODY | 23 | 23,946,200 | true | CompletableFuture.get→AlgorithmIo.await→offload:389；bodyRead=false、await=true | stages0/Call1、executor active0，但尚未shutdown/terminate |

joinReturnMs直接复制原returnMs，是join及原存活断言结束后已有的耗时，不能当worker内部精确返回时刻。首次quiescent时间来自随后原检查，两字段不互换；随后任何快照都不改写首次布尔。本次三个首次原检查都true，没有捕获原Full那次false。

GET_BODY服务端已发头/前16字节的闩锁，只在**取栈时**见证仍在SDK await，没有实际readJson帧；栈没有扫描截断。不能由此推断原interrupt时一定仍在await、一定未进入正文读取或正文路径无问题：取栈、IO字段、executor以及随后interrupt不是同一时刻。GET_BODY的栈仍有await而后取stages已经0，恰说明这些读数可跨越future交接，不能把它们拼成同一个原子状态或据此定唯一竞态根因。

三个参数的before-peer-release样本均为worker已退出、worker finally捕获中断true、输入close计数1、stage0/Call0、stopping/interrupted true、stopNotConfirmed false、owned executor shutdown/terminated true、active/queue=0。首次bool与这个更晚的分项分别保存。输入close计数在entered样本就已为1，不能把这一次close单独归因于后来interrupt。

三个异常图均为根IllegalStateException、固定码OUTPUT_ARTIFACT_UNAVAILABLE；PUT/GET cause为InterruptedException，GET_BODY cause为InterruptedIOException；无ALGORITHM_IO_STOP_NOT_CONFIRMED固定码或suppressed节点，stopNotConfirmed也为false。对应own随机tenant/call的PG在释放前均为rows1、CLEANUP_PENDING、OFFLOAD_FAILED、READY0。因此本轮三参数观察到的是**正常确认退出分支**，不是原Full那次未确认分支的证明或否定。独立第四项未确认向量的原断言也PASS；它没有新增这三参数的诊断JSON，不能与其混为一次实际分支。

## 释放前后与原共享复用边界

- before-peer-release时peer.release/finished剩余均1；PUT/GET/GET_BODY routes分别5/6/6，DELETE均0，PG均CLEANUP_PENDING/OFFLOAD_FAILED/READY0。
- 原release→finished等待→40ms及原无迟到路由/无READY断言后，after-original-peer-wait的routes仍5/6/6、DELETE0，stage/Call0、executor terminated，PG仍同上。这只覆盖原测试实际有限观察段，不能外推无限后续。
- 后面测试原本会显式清空peer.stage、真实复用共享store offload、让本tenant旧产物过期并cleanTenant。原全部断言到末尾，originalBodyCompleted=true；最终finally样本routes为14/15/15，DELETE3、PG state=OTHER（安全枚举外）、READY0。这些后段累计计数含原共享复用/主动清理，**不能把它们与释放前直接作差并判成迟到危险请求**，也不把OTHER猜成具体数据库状态。未额外查询PG来补猜。

本轮没有观察错误或丢失JSON；三份均有四phase。所有断言通过仅说明本次路径满足原测试，不能推断原Full新增FAIL是夹具错误、合法未确认或产品缺陷。

## 下一最小取证建议：一次原完整skill上下文，仅开已审flag

**建议是单次完整skill诊断，而不是继续独立单类抽样；尚未执行、未获本轮延伸窗口。** 原Full失败只发生在完整模块上下文，本次没有命中首次false，GET_BODY的客户端正文读取前提也未在取栈点见证。无证据支持现在改测试期望、加等待、人工卡SDK executor、改ArtifactStore或另立已确证产品修复卡。

只读[source上下文比对](original-full-source-context.json)核原Full保存的149个skill源文件：仅本次获批测试从B06F…变为D64诊断候选，其余完全相同，无缺失/新增源；JDK/.env/本仓admin-common JAR摘要与原Full一致。因此下一层最小上下文是沿原Full采集条件和默认fork/reuse/顺序跑完整70类一次，唯一诊断参数增量为argLine中的`-Dex27.algorithmInterruptDiagnostic=true`；不得顺便改reuseForks、阈值、业务预算、源或其它环境。root解除冻结提交EX09后需重新登记实际HEAD并确认EX09变化不进入本源栈。

原Full使用failure.ignore仅为保留所有既有红及673报告集合；若沿同上下文保留该采集参数，必须继续分开记录native exit与真实方法门，BUILD SUCCESS仍不等于通过。保存全部原始XML/raw、三参数安全JSON、source/config/JAR摘要和准确4身份，同时核原664＋导入9、4旧JDK8 SKIP，逐方法比较原116非通过，不只比总数。不能把原不通过移走、跳过或用类级事件替代方法齐全。

单次Full若再次命中首次false，按同次异常固定码/PG码、stage/Call/executor、释放前后事实走独立技术门；若仍P或没有客户端/分支见证，只记未复现/证据不足并停止，不循环抽样，不自动关闭原Full问题，也不自行扩大源码范围。所有PG/Java/HTTP/Full实际窗口必须由root另行交接，本报告只建议下一取证，不执行。
