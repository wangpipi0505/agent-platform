# 算法SDK中断：EX-03新增回归观察的只读准备

只读核实日：2026-10-29；失败运行日：2026-10-28。准备者 `/root/algorithm_interrupt_readonly`。本报告仅新增本文件，不修改产品、测试、三主入口或index，不编译/运行Java、Maven、PG、HTTP、前端或服务，不读取真实配置或凭据，不使用superpower。它不是独立运行结果、根因确认、测试前提变更许可或EX-03/27验收。

已读本仓AGENTS、implementation-brief、contracts/README及三主入口EX-03/27与方法级回归门。当前EX-27尚无本观察的准确测试写入范围；以下白名单须由root先登记，再执行诊断。不能因为不经Importer/Catalog就豁免新增失败，也不能用独立一次PASS覆盖原模块FAIL。

## 已核事实

- EX-03本轮完整skill报告位于`../2026-10-28-EX-03/after/full-20261028-234917-584/`。70 XML/673报告项为556P/7F/106E/4旧SKIP；原664和新增9齐，唯一新增F为`AlgorithmOffloadInterruptionTest#interruptedSdkWaitStopsOriginalOperationBeforeReturningAndNeverRacesCleanup(String)[3]`。三参数顺序为PUT、GET、GET_BODY，因此该项是GET_BODY。
- 当次本类四项为3P/1F。PUT与GET的成功安全行记录返回耗时24/25ms、中断标记true、输入close次数1；GET_BODY没有执行到成功打印。该方法整项耗时0.455秒，不等于中断至worker返回耗时。
- 失败准确停在测试`:94`的`algorithmIoQuiescent()`预期true/实际false。之前peer.entered、worker.join(1000)后不alive、failure非空已过；之后中断标记、输入关闭、无DELETE、PG清理态、释放peer后无迟到请求、共享store继续可用等断言未到。不能把它们写为本轮通过。
- 当前XML没有记录worker捕获的异常类别/白名单错误码、PG last_error_code、stage/Call/executor分项或客户端读取栈。不能反推已经走了正常确认退出分支，或已经走了合法未确认分支。
- EX-00原本类四项均PASS。原与新XML分别SHA256：`a49b2f314f7b06bf1274b2041750ecc522d87f294e7399fc0ec3ddf644896615`、`4c2ec4084e8918c27fda2a20a0bf06721764e5ae7d7e7c5f7c3504e76e491f36`。新旧状态不能覆盖合并。

本轮核实时两源文件身份：

| 源文件 | SHA256 |
| --- | --- |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/ArtifactStore.java` | `9deb51566206d548110f1f6b5aaab466619eb80d43e8d30b67f11b7e27b7541d` |
| `skill-registry/src/test/java/cn/agentplatform/skill/infra/AlgorithmOffloadInterruptionTest.java` | `b06f5860744f32041cd9a3b656ec3095b7b584d2c52c7effa95f29bb016d7fc9` |

## 实际生命周期与原义务

生产调用由`AlgorithmAdapter.java:464`进入`SkillAdapters.readAlgorithm:653`及其超限分支`:688`，用`ArtifactStore.forAlgorithmDeadline`创建本次独享客户端；原共享HTTP/MCP store不被关闭。当前失败测试直接测该store，没有经过EX-03导入/目录链。

`ArtifactStore.java:114–263`的AlgorithmIo用stage计数跟踪原SDK future完成动作、用Call集合跟踪callStart至callEnd/callFailed；`algorithmIoQuiescent:104`还要求专有dispatcher executor已terminated。`algorithmIoRetained:110`只表示stopping且stage/Call尚未settled，不涵盖“stage/Call已空但executor最后收尾未终止”的全部可能状态，不能只记录这一个布尔就断定全部资源已退出。

`stopAndAwait:211`首次停止只建立一次200ms收尾窗口，重复close不刷新。它记录并暂清中断位，取消真实Call、关闭本次输入、等原stage/Call归零，再等专有executor终止；超限会设置stopNotConfirmed并抛固定码`ALGORITHM_IO_STOP_NOT_CONFIRMED`。`offload:413–440`把清理态及该错误码持久化，延后cleanup_after，不即时DELETE，最后恢复真实中断标记；try-with-resource的close继续使用同一收尾窗口。

本路径没有SSE的Owner/fee退款接口；这里核对的是SDK任务、Call、原输入及专有client/executor的真实生命周期，不把SSE收费模型或新的退款框架套入本卡。

原正式范围见`../algorithm-protocol-review-fixes.md`的R1及“可确认退出与超出有限收尾的区别”，以及`../review-algorithm-protocol.md`的R1复审：正常可确认分支返回前必须结束原stage/Call/executor；200ms确实无法确认时允许显式未确认返回，原future/Call及专有生命周期继续持有，后续真实完成才dispose，不把返回称为已释放，也不承诺任意不合作输入/平台故障都在200ms物理退出。本类`unconfirmedStopRetainsOriginalFutureUntilActualFiniteSourceExit`专门验证该分支，本轮该项PASS。

因此“worker已返回且quiescent=false”本身不足以确认产品违约；同样不足以确认原无条件断言错了。必须取得本次实际分支及其后续所有权/退出事实，不能泛化豁免网络中断用例。

## 可检验窗口，不是已证根因

1. **服务端GET_BODY闩锁并不证明客户端已开始正文读取。** peer在发响应头、写前16字节并flush后置entered；没有客户端消费确认。此时worker可能仍在getObject future等待、future交接、正文解析中的任一点。不能以stage名字断言真实客户端正文路径已到。
2. 本仓固定MinIO8.5.17 `S3Base.executeAsync:612–660`在onResponse里complete原response future；`MinioAsyncClient.getObject:238–258`再把body.byteStream交给调用者。OkHttp4.12.0 `RealCall.AsyncCall.run:512–537`在回调返回后的finally执行dispatcher.finished，且Call的callEnd/callFailed由响应流完成时的callDone分支发出（`:343–365`）。future完成、Call结束和executor终止是不同事件。它们与caller interrupt存在可观测交接窗口。
3. full模块时调度/收尾超过200ms，或该交接触发未确认路径，是需要证据检验的假设。0.455秒整项耗时不能证明其中200ms窗口已耗尽；当前没有worker返回耗时和异常链，不能将其定为唯一原因。
4. 还须排除真实产品缺口：未确认分支没有正确持久码/所有权、已报告停止却原任务仍能发新请求或置READY、有限peer释放后仍不退出、关闭共享客户端等。即使最终只差executor bookkeeping，也不能按“future已done”写成资源已回收。

本轮实际窄读的三个SDK源均与本仓`sdk/source-sha256.json`及固定source JAR内entry**原字节一致**，source JAR摘要也与清单一致；未下载远端、未执行SDK，也未宣称重读整个库：

| 本仓固定原件 | 本轮读段 | SHA256 |
| --- | --- | --- |
| `../algorithm-protocol-artifacts/sdk/io/minio/S3Base.java` | 608–663 | `c4abeda84f5c5c1f9184b7767febb591eaf6f5725e96df7919bc81aa12e87f97` |
| `../algorithm-protocol-artifacts/sdk/io/minio/MinioAsyncClient.java` | 234–262 | `9cbb83b4144607779866ab62a22c56ee4ac388cf6079798946b9df4157c4b350` |
| `../algorithm-protocol-artifacts/sdk/okhttp3/internal/connection/RealCall.kt` | 128–156、332–366、490–537 | `eb2395a4e2cfb0214149add9ca06e9740602dc6b5cf30c95e8eff2ba2c31659a` |

## 建议先登记的有界诊断

**第一阶段唯一拟写源**：`skill-registry/src/test/java/cn/agentplatform/skill/infra/AlgorithmOffloadInterruptionTest.java`及本卡诊断证据子目录。ArtifactStore、AlgorithmAdapter、SkillAdapters、AlgorithmTestDatabase、POM/SDK、生产200ms和原2秒deadline、原所有断言/peer有限等待均只读。当前仅提出设计，尚未取得或使用源码/实际窗口。

- 保留原四个JUnit身份、参数顺序及失败断言。若加显式诊断flag，只用于观察，不跳过原断言，不改期望，不延长200ms/业务deadline，不在中断前人为占用SDK executor或持有产品monitor造红。
- 记录peer.entered时worker的安全栈帧（仅类/方法/行、限少量本仓/SDK帧）以及stage/Call计数，区分client future等待与实际正文读取；没有客户端见证就明确stage前提未证实，不把server闩锁冒客户端闩锁。
- 在worker返回后的**首次原quiescent检查**先固定布尔和相对时间，原assert使用该固定值。额外诊断不得先写文件/查PG/等资源而将原false冲刷成稍后true。轻量状态读取也可能扰动时序，诊断若PASS只能作额外观察，不关闭原模块F。
- 原断言后在finally、释放peer之前单独保存安全快照：failure/caused/suppressed的类名及是否出现白名单固定码；stopping/interrupted/stopNotConfirmed、stage数、Call数、executor shutdown/terminated及可得的active/queue计数；原收尾deadline相对取消时刻；输入close次数、worker存活/中断观测、peer DELETE/路由数。已有项目私有字段可在测试中只读反射，读取时按原monitor取短快照，不改字段、不持锁做网络/PG/写盘、不打印Call/异常对象、URL/header/body或凭据。
- 对本次随机tenant/call只读PG state/last_error_code/READY数量。读取发生在原判断之后，不能为了让原检查等待而提前查PG；不用数据库删除/重置消除失败。诊断准入仍须确认本仓25432与专用算法测试库归属，原AlgorithmTestDatabase自动建库不是所有权证明，不授resetMigrationSchema。
- 释放原有限peer后，在独立标记的有限收尾窗口观测原stage/Call/executor是否真正退出、有无迟到网络/READY/DELETE，以及共享store是否仍可用。保留释放前和释放后两个结论，晚到退出不能倒写“原返回时已静止”；不取消包装future冒实际结束。不用无限重试，新增等待/同步点须先审，不改变原业务预算。

## 决策与验收门

| 后续实际事实 | 可作结论与下一步 |
| --- | --- |
| 固定码/PG均为未确认，原资源确实保留，停止栅栏有效，有限释放后真实退出且无READY/迟到危险动作 | 可评审是否为原正常正例前提不足；仍保留原反例及独立确定性未确认向量，不直接把原assert改成两种都算PASS。正常确认与未确认两种义务均需默认回归覆盖。 |
| PG码不是未确认却返回时仍未静止，或停止/持久/迟到IO/共享资源的原不变量失败 | 登记独立产品修复卡；候选最小生产白名单先为ArtifactStore及对应测试/证据，AlgorithmAdapter/SkillAdapters先只读，需具体失败才能扩围，不塞EX-03导入卡或纯测试卡。 |
| 首次检查已true、没有命中客户端窗口、快照缺失或无法区分 | 仅额外观察/证据不足，原模块新增FAIL及EX-03 gate=false继续保留。 |

实际诊断后由root与独立审阅者核技术门，才决定前置修正或产品卡。获批的最小变更之后才按准确场景→本类原4项→完整skill70类逐方法比较；同一窗口内共享同冻结源码回归，原664项/新增导入9项、4个旧JDK8跳过分别核。新增非通过/坏类/跳过/缺方法必须为0，实际XML、原生退出码、源/配置摘要、未覆盖、独立review与本地提交齐后，才可关闭对应子步；不关闭整张EX-27/Y-4或其它能力。
