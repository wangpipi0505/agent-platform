# 历史分支恢复后的Test-only修正候选

2026-10-29。已完整读取卡片v1.10的五报告/674门和`algorithm-history-technical-review.md`（SHA `E6ADC415CBDEFA2729A8E405CC0F618A73C20E3E4E3D55078E52E373F2775F58`）。当前仅源码/静态候选，尚未Java编译或运行；没有Maven/PG/HTTP、runner准备、.env读取、产品/SDK/DBhelper/主入口/index修改，未使用Superpower。完成后交root独立静审，不自动取得实际窗口。

## 字节身份与真实差异

- 写源前已Copy-Item保存原真实字节`AlgorithmOffloadInterruptionTest.155D.java`，SHA精确`155DD67F8F256DB2227DDA4430B1FE1D99C3CB7127F1AE0B54E02D0ADB96AEB1`；没有从HEAD重建或覆盖旧取证原件。
- 候选源及`AlgorithmOffloadInterruptionTest.candidate.java` SHA为 **`43EC56726C4DD04F3B4E4553590B80316D87BD2B619230C6F728A52D9376BD9D`**。
- `candidate-vs-155D.diff`是实际Git no-index比较：**255新增/30删除**。相对仓HEAD的diff还含此前诊断，不拿它冒充本次差异。HEAD静态核为`83726d50a6cce116251c03c5c193080d8ed5aad2`。
- 产品ArtifactStore仍`9DEB51566206D548110F1F6B5AAAB466619EB80D43E8D30B67F11B7E27B7541D`。

## 实现范围

原参数方法仅委托同类共用网络主体，保留PUT→GET→GET_BODY三身份；新增默认一个原始handoff，原有限Source身份不变，合计5报告。没有第六向量、生产hook、替代client或通用框架。

正常GET_BODY在原**合计1s**准备截止内完成peer闩锁及同次worker栈的readJson＋真实响应体/Okio/socket读正见证；原stage0、仍持有唯一GET Call、owned dispatcher running/queued0且owned ThreadPoolExecutor active/queue0。正常前提不是等Calls空/settled/q，也不把仅解析入口当成功；不达则FAIL，无Assume。成功后只有原计时/字段赋值即interrupt，不夹入另一轮诊断。原严格首次q断言保留，这是有意建立正常前提，不声称零观察扰动或原失败场景未变。

新handoff从同一peer闩锁即interrupt；所有事前capture/readiness都被`!originalHandoff`包住，即使旧debug flag开启也不事前取栈/反射/PG。可选诊断如启用，元数据正确标RAW_HANDOFF_POST_RETURN/省略true/客户端UNKNOWN。其后精确异常图ALGO码与目标PG ALGO码双向核对，无码首次q必须true；已报未确认即使q已恢复true也不抹码。晚样本不反推首次分项。

两种网络分支与确定性Source均补完整PG/延后清理/no早DELETE/原资源真实退出/无新请求及shared真实使用后置。网络peer.finished与原IO退出共用一个原1s收尾截止，原40ms保留；Source原500ms循环、12处原断言义务、retained边界和routes0保留，不为它做HTTP暖场。只在它真实退出后，才由正确parent actor首次使用该原shared进行实际PUT/GET并finally清理身份。最终过期/清理仅本轮新UUID资源，意图边界明确，不触碰旧历史行。

peer在目标停点将当前已接收请求序号CAS固化一次，再countDown；晚请求不能更新基线。主线程从返回后至有意shared前反复比较同一序号、实际routes与DELETE，Source基线0，无counter清零/future取消/额外shutdown。细目见[覆盖矩阵](coverage-matrix.md)。

## 固定上游窄读

已直接只读本仓已有source JAR的具体entry，未解包到SDK目录、下载或运行：OkHttp4.12 JAR SHA `D91A769A4140E542CDDBAC4E67FCF279299614E8BFD53BD23B85E60C2861341C`，`okhttp3/internal/connection/Exchange.kt` ResponseBodySource.read(约262–305)及`okhttp3/Dispatcher.kt` finished/计数(约196–238)；Okio3.6 JAR SHA `870A42A7B468AF8FD24D67B7B62E5861AAB398C56F15A80B10642E7001564E2F`，`jvmMain/okio/JvmOkio.kt`的InputStreamSource.read。Dispatcher去队列与AsyncCall执行结束不同，故同时查原executor活跃/队列；body见证要求该真实delegate读链，不仅Jackson入口。没有声称审计整个SDK或实际跑到这些栈。

## 已做静态核查与尚未做

`verify-fix-candidate.ps1`先PS5.1.26100.9711 ParseFile，0错误，脚本SHA `907C355F2758888DAE14FF8BFB0D34087B874CCC2870717874CC880E2267F2CF`；29项静态检查全true。原25断言义务在47处候选assert中齐，Source原12处齐；仅允许首次q同位别名与两个await改用**原共享截止预算**的规范化映射，不谎称所有等待文本原字节不变。Source500ms循环、800ms join、有限1500ms等待及2s算法预算保持；70源测试类、5报告/674预期、无skip/assumption、括号词法平衡、固定基线、guard及git diff --check通过。详见static-check.json/static-script-parse.json。

这是有限文本/词法和差异检查，**不是Java解析器/编译器，也不是断言运行证明**。真实网络栈见证是否在原预算内达成、五默认报告/完整674、原116类型原因、4旧skip及新增非通过0，均仍待静态独审后root另授实际窗。本轮不准备或启动这些运行；若真实安全义务失败，另立准确产品卡，不能在此改9DEB或放宽预算/正例。
