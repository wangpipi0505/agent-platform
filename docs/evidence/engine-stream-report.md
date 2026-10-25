# G5/T15 Redis 在线增量与有界 SSE

仅本新工程；未碰调度、统计、H1、输出策略、ModelExchange 或 AgentDriver。无 Git 提交、业务服务启停、旧工程访问。测试中的随机端口 Tomcat 与 TCP 中继是测试 fixture。

## 实现

修改 ChunkBus、RunController.events、engine POM/application.yml；新增 RedisChunks；新增 ChunkBusTest、ChunkBusRedisTest、RunEventResponseTest、RunEventsPostgresTest、RunEventsHttpTest。设计见 engine-stream-plan.md。

生产使用固定 Redis7 内部 PubSub 通道，信封按 PG run 主键路由；发布副本也仅从 Redis 回送接收，避免本地重复。Actor 的 tenant+principal 在创建订阅前通过原 TraceStore.run 校验。生产没有内存降级成功；显式无参 ChunkBus 仅为原 AgentDriver 直接测试构造器的本地 fixture。

默认 256/可配最多 5000 条观察、每条 32 帧/8 KiB 文本；全局文本预算默认 16 MiB（含发送中帧）；PG 单页 1 条/单条 256 KiB，全局 PG/发送预算 16 MiB 读前获取并持有到 flush。单个慢流不能阻塞模型发布；停止后仍占用槽直至写线程真实结束。StreamingResponseBody 在 MVC 初始化后单线程写与 flush；没有额外每订阅 chunk 线程、SseEmitter 初始化前缓存或无界事实历史列表。

Redis 1 发布+1订阅连接、2 I/O+2计算线程、1建连工作者、64在途发布/64命令队列，连接/命令超时 1s。关闭 SDK autoReconnect，后台重建连接，只交付恢复后的新 chunk。PubSub 不持久、无 token 重播。初始失败可继续仅 PG 事实；活动断连/队列溢出关闭观察并发送无 ID 的 stream_reset（连接可写时），客户端按最后事实 ID+最终快照恢复。事实补发仍只取 PG，chunk/status/reset 不带事实 ID；终态按 PG event_seq 排空后关闭。

游标过期/超前返回无 ID 的状态水位快照信号；超大事实不删除或截断 PG，发 EVENT_LIMIT，要求取最终快照。UTF-8 预算不是精确 JVM 堆度量；JSON 转义、String/对象等有有界额外开销，5000 连接容量未验。

## 已执行证据

命令环境只从本根 .env 加载凭据到进程，不打印。Java21；独立 .tools/m2；NIO 临时目录 V:/.local/tmp；`-pl engine-runtime` 不带 `-am`，避免共享其他 target。PG 只使用本工程 25432/agent_platform_engine_test，随机 sse tenant；Redis 为本工程 26379。

- `.local/engine-stream-red.log`：原同步 Consumer 发布，1项1失败 TimeoutException，真实证明慢观察者卡模型发布。
- `.local/engine-stream-api-red.log`：新 poll/预算契约在旧实现下缺 API 编译失败；不把该编译失败当行为证明。
- `.local/engine-stream-early-send-red.log`：1项1失败，未开始 MVC 响应前已读 PG；改 StreamingResponseBody 后 RunEventResponseTest 通过。
- `.local/engine-stream-cursor-red.log`：1项1失败，缺失过期游标信号；补状态水位恢复后通过。
- `.local/engine-stream-targeted-green.log`：18项，0失败/错误/跳过，BUILD SUCCESS（尚未含随后新增的重连第4项）。
- `.local/engine-stream-reconnect-green.log`：4项真实 Redis 测试，0失败/错误/跳过。覆盖两个独立 bus 的跨实例、run 隔离、不重复、取消，初始不可用无本地假成功，只切测试 TCP 连接的断连，恢复后仅 future delta。该阶段断连日志有重复 close WARN，随后移除重复 close 调用；最终代码全量结果待追加。
- `.local/engine-stream-http.log`：2项0失败，含随机端口真实 HTTP 首响应200、TCP RST 后唯一订阅槽可复用；该 fixture 只 mock PG 权威边界，其余 PG 用例另接真实数据库。

## 最终冻结与验证

2026-10-20 05:15:46 +08:00 结束 engine 源码/测试/Maven 独占窗口，随后仅更新本证据与审查包。主协调者接手独立评审与实际双进程纵切。本子任务没有自行打包、重启业务进程或 Git 提交。

- `.local/engine-stream-full-green.log`：首次全量 129 项，0失败/错误/跳过，05:12:00 BUILD SUCCESS。
- `.local/engine-stream-contenttype.log`：真实 HTTP 媒体类型断言真实失败，暴露 plain StreamingResponseBody 不自动设置 text/event-stream；补 ResponseEntity 显式媒体类型与 no-store。
- `.local/engine-stream-response-final-green.log`：9 项响应体/真实HTTP/PG定向，0失败/错误/跳过，05:12:49 BUILD SUCCESS。
- `.local/engine-stream-final-full-green.log`：媒体类型修复后的全量 129 项，0失败/错误/跳过，05:14:43 BUILD SUCCESS。
- `.local/engine-stream-drain-red.log`：32个就绪 chunk 因固定20ms sleep超300ms，1项1失败；随后只加有帧立即 continue，避免人为50 chunks/s限速。
- `.local/engine-stream-final-targeted-green.log`：最终冻结代码 20 项（ChunkBusTest 5、ChunkBusRedisTest 4、RunEventResponseTest 2、RunEventsHttpTest 1、RunEventsPostgresTest 7、RuntimeStreamTest 1），0失败/错误/跳过，05:15:46 BUILD SUCCESS。最后一处 continue 后未重跑其他引擎领域全量，不把129项全量冒充最后源码全量130项。

最终完整命令（先由本根 .env 仅向进程加载变量，ENGINE_PG_TEST=true，Java21）：

```text
mvn.cmd -B -ntp -pl engine-runtime test -Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2 -Djdk.net.unixdomain.tmpdir=V:/.local/tmp
mvn.cmd -B -ntp -pl engine-runtime test -Dtest=ChunkBusTest,ChunkBusRedisTest,RunEventsPostgresTest,RunEventResponseTest,RunEventsHttpTest,RuntimeStreamTest -Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2 -Djdk.net.unixdomain.tmpdir=V:/.local/tmp
```

最终10个实现/测试/配置文件完整内容与 SHA256 在 `.local/engine-stream-review-package.txt`，独立评审请以此为冻结输入（无本轮基线 commit，工程文件仍未跟踪）。评审由主协调者另派；本子任务不能把尚未返回的评审称为通过。

最终定向日志包含 Mockito/ByteBuddy 动态 Java agent 及 CDS 的 JDK 警告；未将日志描述为零警告。它们不是测试失败；未增加 JVM 全局参数来隐藏。

## 前端消费者精确契约

响应 `Content-Type: text/event-stream`、`Cache-Control: no-store`，UTF-8。所有连接仍需原认证，`Last-Event-ID` header 或同名 query 只能使用最后已消费的**事实** seq。以下控制帧与 chunk 均不含 SSE `id:`、不含 `seq`，不能推进事实游标。

| event | data |
| --- | --- |
| `stream_status` | `{"chunkAvailable":boolean,"chunkReplay":false,"recovery":"LAST_EVENT_ID_AND_FINAL_SNAPSHOT"}` |
| `chunk` | `{"text":string}` |
| `stream_reset`（活动观察重置） | `{"reason":"SLOW_OBSERVER"\|"BUFFER_LIMIT"\|"CHUNK_LIMIT"\|"CHUNK_TRANSPORT_RESET","chunkReplay":false,"lastEventId":number,"recovery":"LAST_EVENT_ID_AND_FINAL_SNAPSHOT"}` |
| `stream_reset`（单事实超大） | `{"reason":"EVENT_LIMIT","lastEventId":number,"recovery":"FINAL_SNAPSHOT","chunkReplay":false}` |
| `stream_reset`（游标过期/超前） | `{"reason":"CURSOR_EXPIRED"\|"CURSOR_AHEAD","eventsWatermark":number,"status":string,"recovery":"FINAL_SNAPSHOT","chunkReplay":false}` |

事实帧仍为 `id:<PG seq>`、`event:<PG type>`、`data:<原PG JSON payload>`。reset 发出后本响应 EOF；客户端应读取已授权 run 状态/最终快照，再决定按事实游标重连。`eventsWatermark` 只描述状态快照水位，不能在未恢复快照前当成已消费事实游标。EVENT_LIMIT 必须取状态/最终快照，直接用原游标重连会重复命中同一超大事实。

即使没有 reset，非终态连接约55秒正常 EOF 后也必须取状态并认证重连；后端不伪造 RUN_FINISHED。初始 Redis 不可用可继续 PG-only；恢复只收到未来 chunk，最终输出必须替换临时增量。网络本身不可写时 reset 可能无法送达，EOF/异常使用相同恢复路径。长任务 Chat/SDK 自动重连由主协调者另补，当前后端测试不证明全T15消费者链完成。

两个实际 engine 进程纵切由主协调者另验，不把同 JVM 的两个 bus 当真实两 Pod。Redis PubSub在发布侧异常/过载下不能保证远端观察者收到 reset；所有stream_status明确chunkReplay=false，最终完整结果必须从PG快照替换，绝不宣称在线token无损。UTF-8预算是逻辑载荷预算，不是精确堆大小；5000连接/500运行/真实供应商与其他G5调度及内存硬闸仍未验收。
