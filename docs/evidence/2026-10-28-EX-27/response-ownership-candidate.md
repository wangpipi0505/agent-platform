# EX-27：中断等待与成功响应交接的确定性红测候选

2026-10-29，作者 `/root/algorithm_interrupt_readonly`。本轮只新增一个测试及本说明；未编译、运行 formatter、Java、Maven、PG、HTTP 或 Node，未读 `.env`，未改产品、旧五身份、主入口或 index，不使用 Superpower。静态可编译性核对不能代替实际编译。

## 范围与真实字节

| 文件 | 当前 SHA256 | 本轮角色 |
| --- | --- | --- |
| `skill-registry/src/test/java/cn/agentplatform/skill/infra/AlgorithmOffloadResponseOwnershipTest.java` | `40474F829DEBD58D0B1D689D8F139F5BD89E2B2A07B298470D90C870960397F6` | 唯一新增测试，1 个默认方法；含 JUnit 调用线程中断隔离 |
| `skill-registry/src/test/java/cn/agentplatform/skill/infra/AlgorithmOffloadInterruptionTest.java` | `43EC56726C4DD04F3B4E4553590B80316D87BD2B619230C6F728A52D9376BD9D` | 只读，复用既有有限 LatchedS3/最终严格判定辅助方法 |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/ArtifactStore.java` | `967849596AE6179A2B0858FB39D50A365C65F2370D4E2AD0658DFEE44A0569B1` | 只读，调用原 asyncClient、AlgorithmIo.await、close |

当前 HEAD 为 `acad6bdee223b23f331420c6de50045cb5fa8941`。EX-15b 原冻结 151 份 skill 源逐项摘要无漂移；仅新增该测试后为 152。若后续 formatter 改字节，必须另记真实新摘要，不能仍使用上表旧摘要。

这是**原内部 await/close 边界的真实 SDK/HTTP 测试**。不调用完整 offload，不访问 PG，不宣称覆盖产物登记、错误码持久化、上传、共享 store 的业务复用或原 Full700 现场。测试仅使用原 operation 创建的客户端；没有替换 HTTP client、假的 future/Call/response、生产 hook、直接 shutdown 或集合改写。

## 可证明缺口与原失败的区别

本仓固定 MinIO8.5.17 `S3Base.java:658` complete 原 Response future；`MinioAsyncClient.java:238–258` 通过 thenApply 将同一响应的 body.byteStream 放进 GetObjectResponse。后者继承 FilterInputStream，其接口要求调用者关闭。

`ArtifactStore.java:161–193` 的原完成回调只在**回调执行当刻** stopping=true 时关闭成功值，随后 stages--；InterruptedException 分支只记录 interrupted 并重抛。允许下列真实顺序：

1. 原 future 未完成，worker 在原 await 中被中断并抛出 InterruptedException。
2. worker 尚未执行调用者的 close/stopAndAwait，stopping 仍 false。
3. SDK 真实成功响应完成；旧 whenComplete 跳过 close 并归还 stage。
4. 调用者随后 stopAndAwait 取消真实 Call，但已经没有成功响应的关闭所有者。

固定 OkHttp4.12 `RealCall.kt:128–145` 明确 cancel 不代替调用者关闭响应体；`Exchange.ResponseBodySource` 在 read/close 完成时才报告 body 完成，`RealCall.callDone` 才回调 callEnd/callFailed。服务器把字节写完、关闭 exchange、finished=0，不等于客户端读取或关闭了响应体。因此上述顺序可遗留 stages=0、原 Call 仍在、executor 尚未 shutdown，即使 server finished 后再等 1 秒也未必退出。

这证明的是代码允许的响应所有权缺口；**尚未证明 Full700 当时就是此顺序**。原 Full700 的 `awaitOriginalQuiescence:454` 只有末次 aggregate=false。当前两轮定向均 5P；AfterReturn 的 handoff 首次 q=true/26ms，三个样本 stages=0/calls=0/executorTerminated=true，未命中此缺口。它们不能覆盖原 Full700 F。

其余可能组合仍须区分：stages>0 表示原 future/完成回调未退出；stages=0/calls>0 可表示响应体未关闭，也可能是仍在进行的关闭；stages=0/calls=0/executorShutdown=true/terminated=false 表示执行器尾段尚存。现有诊断可区分**样本时刻**这些分项，但没有原响应成功值/取得者的所有权证据，且失败后的 finally 样本晚于原判定；不能仅凭其中一种组合宣布唯一根因。

固定原件：MinIO source JAR SHA `419EFC157540138AFD56DD1056E645370D91000F14564D7A841F2D5E9DC9A5BA`，OkHttp `D91A769A4140E542CDDBAC4E67FCF279299614E8BFD53BD23B85E60C2861341C`，Okio `870A42A7B468AF8FD24D67B7B62E5861AAB398C56F15A80B10642E7001564E2F`，均与本仓 sdk/source-sha256.json 相符。本轮只窄读相关方法，没有宣称审计全部 SDK。

## 新方法与判定

方法身份：`AlgorithmOffloadResponseOwnershipTest#interruptedAwaitOwnsResponseCompletedBeforeCallerStartsStopping`。

1. 仅在既有 LatchedS3 中 seed 本次随机对象；GET 固定测试 region，避免无关 region/上传链。调用原 ArtifactStore.asyncClient 的真实 getObject，保留其原 CompletableFuture。
2. 使用原 GET 响应头前闩锁，严格核原 future 未完成、原 await stages=1、唯一真实 Call 未取消；之后中断 worker。
3. 反射调用的是**原私有 await**。调用者 catch 必须取得真实 InterruptedException；测试侧有界闩锁模拟它到 close 之间的合法调度间隙，不持产品 monitor，不占 SDK executor。
4. 核 stopping=false，释放真实 HTTP；同原 future 必须成功。对同一返回响应 assertSame；等待真实 stages、dispatcher 与 executor active/queue 的原完成工作退出。若还有 Call，必须 assertSame 为原 Call。
5. 输出只有固定标签/布尔/计数的 `ALGORITHM_RESPONSE_OWNERSHIP` 安全行，再放行调用者的原 close。close 若抛出，只接受精确 IllegalStateException/ALGORITHM_IO_STOP_NOT_CONFIRMED；不将此当作最终通过。
6. worker 必须退出且原 close 恢复中断位。server finished 与最终原 algorithmIoQuiescent 共用原 1 秒收尾预算，最终仍严格要求 true；没有“已报未确认就不检查真实退出”的分支。

2 秒 deadline、stopAndAwait 内原 200ms 均未改；准备共享 1 秒、worker join1000、最终共用 1 秒。准备见证不达就失败，不 Assume，不循环抽样。旧五身份/参数顺序/所有断言和预算保持。

finally 在严格断言之后释放自有闩锁，关闭同一个真实成功响应、原 operation，并有界 join 自己 worker；共享 fixture/peer 由 try-with-resources 关闭。主异常先保存，清理异常只附加为 suppressed；晚期清理成功不倒写最终严格判定。原 close 会因 io.interrupted 给调用线程恢复中断，因此测试入口保存 JUnit 调用线程原位；cleanup close 后先恢复该原位再 join，所有 try-with-resources 退出后再次恢复。此隔离不改 worker 的 interruptRestored 断言和 close 失败记录。

## 准入与后续最小建议

首轮 RED 建议仅：

```powershell
mvn.cmd -B -ntp -pl skill-registry test "-Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2" "-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp" -Dsurefire.timeout=900 -Dsurefire.failIfNoSpecifiedTests=true -Dtest=AlgorithmOffloadResponseOwnershipTest
```

实际命令仍由 root 冻结及独占运行；不读 env、不授 PG/外部服务。原生退出必须保存。只有新类 1 报告，全部前置见证已过，安全行显示原顺序，且最终原 q 断言的 AssertionFailedError，才认该缺口的目标实红。编译、夹具、准备超时、其他异常不算该目标红。保留 XML/raw/native/源与 JAR 指纹，不将 collector 成功等同测试通过。

RED 确认后，最小生产候选仅为原 InterruptedException catch 对**同 original**追加成功 AutoCloseable 值的关闭回调，仍抛原 InterruptedException。已完成 future 的 whenComplete 会立即执行；未完成的由之后真实完成执行，覆盖两个登记时序。不能只让旧回调检查 interrupted，也不能提前 stopping 而跳过 cancelUntil 初始化；不能取消包装 future 或伪造 stage/Call 已完成。关闭异常不得替换原中断。

GetObjectResponse→Okio 的 closed 标记支持顺序重复 close；本轮不把一般 AutoCloseable 或并发 close 宣称为线程安全。补丁需独审这一点，并继续依赖真实 Call 结束与原 executor 退出，而非新回调的布尔成功。

本确定性新向量直接实测“中断 catch 先、成功完成后”；没有伪称它也实测了 JDK 指令级“结果已完成但 get 已选中中断返回”的另一竞争。后者的可达性来自本机 JDK21 CompletableFuture.get/waitingGet/whenComplete 源码机制核对。

修复后须新例原断言 GREEN、旧五身份默认回归及整模块方法/旧原因对照；准确 Full 新集合须 root 冻结，不能拿新例 PASS 关闭原 Full700。当前只交候选，不计产品修复、实红或验收完成。

## 静态检查回执

- 1 个 @Test；原 151 源逐项无漂移，新类后 152。
- 原私有 await 调用 1 处；原实际关闭路径未替换。
- 同 response 与原 Call 均有 assertSame，最终严格 q 调用 1 处。
- 调用线程中断位恢复只在主测试线程 cleanup/最外层 finally；不修改 worker 的中断证据。
- 直接 shutdown、fake complete/cancel/clear、新 CompletableFuture 均 0。
- PG/env/DB helper、Disabled/Assumption/assertTimeoutPreemptively 均 0。
- 依赖可见性已核：同包既有静态 LatchedS3/BUCKET/最终辅助方法可访问；私有项目方法只经原对象反射调用。未通过实际 javac/formatter 声称可编译或格式门通过。
