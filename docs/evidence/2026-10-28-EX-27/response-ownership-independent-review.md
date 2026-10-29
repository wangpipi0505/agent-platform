# EX-27 响应体所有权内部边界独立审阅

2026-10-29，审阅者 `/root/docs_reader_check`，未编写本次Java测试或产品补丁。本轮只读测试、原/新ArtifactStore.await、collector、固定SDK/JDK源码归档和root实际红绿原XML/回执；只新增本报告，没有编译、测试、Java、PG、HTTP、服务或HEAD/index操作。应用code-review-expert的竞态/资源所有权核查，不使用Superpower。

**结论：当前75F89B补丁及40474F测试无阻断，可继续root既定SDK6/相邻/完整回归；仅catch另加独立close的旧建议已被本次await内局部once消除风险。内部边界的有效实红→同测试绿成立，整卡/原Full700唯一根因不据此关闭。**

## 1. 审阅的准确字节

| 项 | SHA256 |
|---|---|
| 新测试AlgorithmOffloadResponseOwnershipTest.java（红绿同源） | 40474f829debd58d0b1d689d8f139f5bd89e2b2a07b298470d90c870960397f6 |
| 候选说明response-ownership-candidate.md | 57ccb1a0bf5f71901cab674509e12cc1ad2d6acf1b72d64b6f8acff521ecf8fb |
| collector response-ownership/run-target.ps1（红绿同源） | eaffebb353d96ddb5f65a2055f72e09830e579fa429ae052f7408d713a7fe88b |
| 红原ArtifactStore.java | 967849596ae6179a2b0858fb39d50a365c65f2370d4e2ad0658dfee44a0569b1 |
| 当前/绿ArtifactStore.java | 75f89b938a054be6ac385c7152f49a9525d25e696bce3634c42c7bd3201ae907 |
| 原五例所在AlgorithmOffloadInterruptionTest.java | 43ec56726c4dd04f3b4e4553590b80316d87bd2b619230c6f728a52d9376bd9d |

独立逐项核红source-before==source-after共152份，原151份全部与EX-15b冻结manifest一致、仅新增本测试；绿的152份前后也一致，红绿之间仅ArtifactStore变。实际当前其余151份均与红一致；原五身份的源字节没动。原件test-source/product-source/collector复制SHA均与各自admission相符。直接比较红绿源码，await方法之外的前缀/后缀完全相同；Git diff为15+/11-、仅该await范围，未改stopAndAwait、Call listener、executor释放、源流或其它产品路径。

## 2. 实红资格与绿原件

均为root实际运行，审阅者未重跑。目录在`response-ownership/runs/`：

| Run | 原生/采集 | 原XML | 所有权见证/判定 |
|---|---|---|---|
| response-ownership-red-20261029-01 | native1/collector0 | 1 test、1F、0E、0S；SHA 13ea98c449015d4715ec9f11223ac10967350a76a7ab883d0105dcadde708679 | awaitInterrupted=true、originalFutureSuccess=true、sameResponse=true、stoppingBeforeCallerClose=false、stages0、calls1、retainedOriginalCall=true；唯一主失败为原awaitOriginalQuiescence严格q=false |
| response-ownership-green-20261029-01 | native0/collector0 | 同一身份1P、0E、0S；SHA 7c90501a57eec5fd2c752a38cca290fb4884a3d0f82c28a8f9409f32a653e4f2 | 同future/同响应成功及stopping=false、stages0不变，calls0、retainedOriginalCall=false，严格q通过 |

已独立重算两份XML SHA，核唯一方法`interruptedAwaitOwnsResponseCompletedBeforeCallerStartsStopping`。红failure精确为`AssertionFailedError`，首项目帧是原`AlgorithmOffloadInterruptionTest.awaitOriginalQuiescence:454`；不是编译、夹具前提、准备超时或别的异常。XML中的两个suppressed `ALGORITHM_IO_STOP_NOT_CONFIRMED`来自严格主断言之后的cleanup，未替换主失败。collector分别精确锁定身份/见证/主失败消息摘要，native先于XML解析落盘，source/context stable均真，PG未使用；collector0在红表示预期红采集合格，不表示测试PASS。

两轮HEAD均acad6bdee223b23f331420c6de50045cb5fa8941，env只记录安全SHA、未导入；admin-common JAR/context pin一致。XML classpath明确包含MinIO8.5.17/OkHttp4.12.0/Okio3.6.0，未把fake future/Call/响应或PG当真实SDK。

## 3. 固定资源所有权链与并发风险

只读本仓归档source JAR，SHA与候选固定值一致：MinIO419efc157540138afd56dd1056e645370d91000f14564d7a841f2d5e9dc9a5ba；OkHttp d91a769a4140e542cddbac4e67fcf279299614e8bfd53bd23b85e60c2861341c；Okio870a42a7b468af8fd24d67b7b62e5861aab398c56f15a80b10642e7001564e2f。JDK21.0.12.8本地lib/src.zip SHA ded7327c984af1126165c5b00b5d768bcebff4aa55a985a0d355804400c01b42；没有运行javap/Java。

- MinIO S3Base.java:658完成原HTTP Response future；MinioAsyncClient.java:238–255用thenApply把同一response.body().byteStream装入GetObjectResponse；GetObjectResponse.java:28继承FilterInputStream且没有自带close所有权门。
- JDK21 FilterInputStream.java:169–170仅调用in.close()，没有once或锁。Okio RealBufferedSource JVM inputStream.close:173转到同source.close；commonClose:392–396的closed是普通字段，先检查、再置位、再关source并clear buffer。它支持顺序重复close不等于一般并发close安全；OkHttp Exchange.ResponseBodySource:310–323同样有普通closed/completed状态。
- OkHttp RealCall.kt:128–142明确cancel只中止I/O，调用者仍负责close响应体；body真正完成后messageDone/callDone才callEnd/callFailed。Call.cancel、server finished、future成功、stages归零均不能单独证明响应体释放。
- JDK21 CompletableFuture.java:879–895对已完成future可在登记whenComplete的调用线程直接执行，对未完成future压入独立completion；不同回调没有“一个future就全串行”的契约。故仅在InterruptedException catch另加独立close，可能与原stopping completion或checkActive失败补关重复/并发，不能靠一般AutoCloseable幂等或FilterInputStream继承关系放行。

## 4. 当前最小修法符合范围

ArtifactStore.java:159局部AtomicBoolean仅作为这个await的一次关闭所有权门，Consumer<T>先判断AutoCloseable再CAS；三处共享：原completion在stopping时（174）、get成功后checkActive失败（189）、InterruptedException catch对同original追加whenComplete（196）。

这覆盖“catch登记时future尚未完成”与“登记时已完成”两个时序，未取消original或包装future、未替换Call；不靠提前stopping或单查interrupted去改原stop初始化。catch仍设置原interrupted并抛同一个InterruptedException。未增加等待、重试、executor或超时预算，原stage++/完成finally stage--/真实Call移除/200ms drain均未改。

CAS把**三条内部关闭入口**限制为一个赢家；不把CAS=true当“close已结束”，源码没有据它清Call、置settled或宣告释放。close异常沿原catch Exception策略不替换中断；若底层关闭未完成，原Call/executor严格q仍须失败。新回调不注册假stage或假Call，也不根据close异常直接报绿。当前真实绿验证的是固定SDK实际成功关闭及原q，不是AtomicBoolean本身。

剩余边界：once不承诺外部任意响应使用者/另一次await/测试cleanup与流读取的通用线程安全，也不把一般AutoCloseable多次close定义为安全。仅用于原await的同一成功结果；正常返回给消费方后其正常close仍是原API责任。本次无须外部取消框架、I/O monitor持锁关闭或共享store生命周期重构。不得将来把closeOnce CAS失败视为资源已完成，或在异常时手工清空calls/stages。

## 5. 测试未替换原行为，预算和中断位保留

新测试仅seed自己随机对象，以原operation的真实asyncClient.getObject获得original；保留真实原future与Call。反射只调用原await/读原状态，stages/calls在原monitor下快照读取；没有fake future、complete/cancel/clear、替换client或直接shutdown。Call同一性、future未完成、真实InterruptedException、同response、callback/dispatcher active+queue空等前提在最终q之前都有断言。

allowCallerClose只控制await已中断到调用者原close之间的合法调度间隙；不持产品锁、不占SDK executor、不截持响应体。2s absolute algorithm deadline和原200ms stop未变；准备共用1s、worker join1000、peer.finished+最终原q共用1s，各预算明确，没把前提失败转Assume或重跑样本。它测原内部await/close边界，不是完整offload/产物注册或错误持久化。

worker仍由原close恢复中断，测试151行严格断言interruptRestored=true。JUnit调用线程不同于worker：在cleanup close后178行先清除close传播的状态并按入口callerWasInterrupted恢复，再join；try-with-resources退出后194行外层finally再次恢复。恢复助手Thread.interrupted()+按原位interrupt的源语义已独立核对；没有修改worker证据或用提前清位跳过其断言。绿色说明这些路径未导致测试错误，但没有另加测试外JUnit线程状态探针，不能声称已做该额外动态观测。

## 6. 结论和剩余验收

本子步RED→GREEN有效，当前75F89B无静态阻断。固定SDK响应所有权缺口已被同测试证明并修复该内部边界；不追认原Full700唯一cause，不以此关闭原Full700或整张EX-27。原五例源/预算/断言保留的核查已完成；旧五+新一、相邻及Full701的实际方法与有界旧原因对照尚须root后续证据。不得将新例1P当全模块绿，任何新失败或未执行源方法继续分列。

本报告到此停写；最终回归后另做终审，不覆写本轮红绿原件。
