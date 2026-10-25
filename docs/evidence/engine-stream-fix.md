# G5/T15 SSE 独立评审两个 P2 修复证据

仅本新工程根 `G:/207/项目/算法重组工具/代码/agent-platform`。依据 `review-engine-streaming.md` 两项明确发现及 `.local/engine-stream-review-package.txt` 原冻结输入修复。无 Git 提交、无业务服务或 Redis/PG 启停；随机端口 Tomcat 与 TCP link 仅是既有测试 fixture。未修改 RunService 调度、OutputPolicy、H1、Stats、web、POM 或配置。

## 修复边界

1. `RunController.events`：初始权威 PG 查询中，`oldest_seq=null && event_seq>last` 同样代表游标已过保留窗口。返回无 SSE id 的 `CURSOR_EXPIRED`，含同一个 PG 语句快照的 `status`、`eventsWatermark`、`FINAL_SNAPSHOT` 和 `chunkReplay=false`，随即 EOF；不推进事实 `last`，不把水位冒充已消费事实。空页后再次查询状态时也取保留窗口，覆盖初始检查后历史被清空的竞争；正常新增终态事实仍按原水位排空。
2. `RedisChunks`：连接创建及订阅等待留在唯一 connector 线程，成功前由局部变量持有；短生命周期锁只发布完整且仍打开的连接对。断开任意一条连接时，在同一短锁内摘除旧连接对，再在锁外对两条连接调用 `closeAsync()`；Netty 事件线程不做同步等待。旧回调按连接身份识别，不能清空新的连接对。部分建连失败、订阅失败和关闭竞争的局部连接在 finally 中异步关闭；shutdown 先标记关闭并停止 connector，锁外等待 connector 结束后关闭 client/resources。`autoReconnect=false`、命令/队列预算及只恢复未来 chunk 保持原语义。

对原包内容做换行归一后的字符串核对：`RunController` 的 events 注解之前与 `private static void send` 开始之后均完全一致，生产修改只在指定方法内。

## 先红后绿

`.local/engine-stream-fix-red.log`（05:23:26 +08:00）：在原生产实现上运行新测试，**4 项、4 失败、0 错误、0 跳过**，BUILD FAILURE。

| 真实失败路径 | 原实现结果 | 修复后断言 |
| --- | --- | --- |
| 全部删除 run_event，保留 run.event_seq=3 | 1 秒内不返回 | RUNNING/SUCCEEDED 的游标 0、1 都立即过期；游标 4 超前；水位 3 与状态保留，无 id、无 lastEventId；游标 3 及 event_seq=0 的空运行不误报 |
| stream_status flush 后删除全部历史 | 1 秒内不返回 | 空页后返回 CURSOR_EXPIRED，水位 2、SUCCEEDED，无 id |
| 真实 ChunkBus 多轮原始 TCP 断连并恢复 | 注册资源无法回到两条 | 4 轮交替只切一条/同时切两条测试 client 的 Netty Channel；每轮恢复 available，资源注册集合回到 2、channel=2；最终关闭两者=0 |
| publisher 已连、subscriber 建连失败 | 注册资源无法归零 | 有一条连接成功但第二条失败时，已创建对象全部注销；另用第二条 TCP 已接受但故意不回复握手，在真实待建连期间执行 close，4 秒内结束、资源=0、available=false |

PG 测试仍使用真实 PostgreSQL16.15 `25432/agent_platform_engine_test` 与随机 `sse_` tenant。新增计数只是对真实 JdbcTemplate 做 spy，未 stub 数据；在授权的 run 查询后清空调用计数，响应期间每个 case 最多 2 次 queryForMap、1 次 queryForList，且 1 秒内结束，直接限制忙轮询。RUNNING 已追平及无事件 case 由第一帧后真实更新终态来有界结束，不伪造事实。

Redis 使用本工程真实 Redis7 `26379`，密码仅从本根 `.env` 加载至测试进程。资源测试只反射该 bus 自身 client 的 `closeableResources` 与 `channels`；不关闭其他 client、不重启 server。既有跨 bus、不重复、取消、PG-only、future-only 测试一并回归。shutdown 测试通过 TCP fixture 卡住第二条握手，覆盖实际建连与 close 的竞争。

最终定向 `.local/engine-stream-fix-targeted-green.log`（05:25:25 +08:00）：**24 项、0 失败/错误/跳过**，BUILD SUCCESS。组成：ChunkBusRedisTest 6、ChunkBusTest 5、RunEventResponseTest 2、RunEventsHttpTest 1、RunEventsPostgresTest 9、RuntimeStreamTest 1。

最终全模块 `.local/engine-stream-fix-full-green.log`（05:27:05 +08:00）：**134 项、0 失败/错误/跳过**，BUILD SUCCESS，用时 1:21。准确基线为原 129 项全量之后增加就绪 chunk 排空测试得到 130，本次新增 4 项得到 134；本轮全量确实覆盖最终四个 Java 文件 SHA，而非把定向结果合并冒充全量。

日志存在 Mockito/ByteBuddy 动态 agent、CDS 警告；新显式 closeAsync 与 client shutdown 的幂等清理重叠也会产生 Lettuce `Connection is already closed` WARN，没有隐瞒或全局隐藏。它们不是失败，资源集合与 channel 归零由断言验证。

## 命令与冻结文件

Java21；在独占 engine 窗口执行，仅加载本根 `.env` 为进程变量并设置 `ENGINE_PG_TEST=true`。父 POM/common 使用已安装的 `.tools/m2`，Maven 不带 `-am`。

```text
mvn.cmd -B -ntp -pl engine-runtime test -Dtest=RunEventsPostgresTest#fullyClearedHistoryResetsOldCursorsWithBoundedAuthoritativeQueries+retentionCleanupBetweenInitialSnapshotAndDrainResetsWithoutBusyPolling,ChunkBusRedisTest#repeatedRawDisconnectsRetireRegisteredConnectionsBeforeRebuilding+partialConnectionFailureAndConcurrentShutdownReleaseAllConnections -Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2 -Djdk.net.unixdomain.tmpdir=V:/.local/tmp
mvn.cmd -B -ntp -pl engine-runtime test -Dtest=RunEventsPostgresTest,ChunkBusRedisTest,RunEventsHttpTest,RunEventResponseTest,ChunkBusTest,RuntimeStreamTest -Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2 -Djdk.net.unixdomain.tmpdir=V:/.local/tmp
mvn.cmd -B -ntp -pl engine-runtime test -Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2 -Djdk.net.unixdomain.tmpdir=V:/.local/tmp
```

GoogleJavaFormat 1.25.2 `--aosp --replace` 已用于以下 4 个 Java 文件；对同 4 文件再执行 `--aosp --dry-run --set-exit-if-changed`，退出码 0、无输出。

| 文件（相对新工程根） | SHA256 |
| --- | --- |
| engine-runtime/src/main/java/cn/agentplatform/engine/api/RunController.java | 0F2EF9CF57A52E328B452EC63C5D833F92D21F963AD44752AE4D4FD72C900B29 |
| engine-runtime/src/main/java/cn/agentplatform/engine/infra/RedisChunks.java | 9DD67ED93D758EDB9C9345340A049D6878E620EA767E4BB235AAFDB63AAE112F |
| engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunEventsPostgresTest.java | 207B9CDB926E438C11FC36C79D4E2F8734D06884C30D024623C2C1F60E672ED9 |
| engine-runtime/src/test/java/cn/agentplatform/engine/infra/ChunkBusRedisTest.java | CB87D31F8B843868F43EE51CC4AB2B615754B567A1538857C876608A5F56C98D |

交原独立 reviewer 只针对上述两个 P2、四个文件及日志核对关闭；本证据不自行宣称独立评审通过。两实际 engine 进程纵切由主协调者后续部署验证，5000+ SSE/500 运行容量、其他 G5 内存闸及调度公平性均不扩展到本修复结论。

05:27:05 +08:00 全量完成后冻结上述实现/测试文件，仅补齐本报告。释放 engine Maven/target 独占窗口，主协调者可开始部署两实际 engine 纵切。
