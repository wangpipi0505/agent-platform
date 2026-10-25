# G5/T15 后端在线 SSE 独立评审

评审日期：2026-10-20。初审结论：请求修改（2 项 P2）；**修复后定向复核结论：两项已关闭，本后端增量可继续集成验收**，详见文末复核。不能关闭完整 T15 或 G5。

## 输入与方法

- 只在新工程根 `G:/207/项目/算法重组工具/代码/agent-platform` 工作；只写本报告，未修改业务代码、启停服务、操作 Git 或重跑 Maven/已通过套件。
- 使用 code-review、requesting-code-review、verification-before-completion 技能的独立评审、需求核对和证据分级原则。当前没有 HEAD，以 `.local/engine-stream-review-package.txt` 的 10 文件全文与 SHA256 作为冻结评审输入。
- 已逐一计算当前这 10 个文件 SHA256，全部与包内摘要匹配；冻结详设 `contracts/snapshot/detailed-design-v1.2.md` 的 SHA256 与 freeze.json 一致（`3c72b2062beb2e8b377f2384843ea935a0074b50c0063e5106566bbd8dcd0c4f`）。
- 已读 AGENTS.md、implementation-brief、contracts/README、F10（1769–1773）、六道内存硬闸及隔离矩阵，以及 engine-stream-plan.md、engine-stream-report.md。评审范围为包内 ChunkBus、RedisChunks、RunController、application.yml、engine POM 和五个新增测试文件；不扩展调度公平性或其他已登记缺口。
- 主协调者另外明确授权只读本工程当前 Lettuce JAR、执行非 Maven 短探针检查资源注册/关闭。探针只连接本工程 Redis26379 并关闭自己的连接，不重启 Redis 或业务服务；密码仅从本根 .env 读取至进程，不打印。

## 必须修复

### [P2] 已清空的保留窗口未识别为过期，导致事实流紧密轮询至超时

位置：`engine-runtime/src/main/java/cn/agentplatform/engine/api/RunController.java:164–167`，相关循环 `:275–281`。

F10 要求游标早于保留窗口时返回过期信号和状态水位。当前判断仅在 `min(seq)` 是 Number 时处理过期。若某个终态 run 的 `event_seq=3`，保留期清理已删除它的全部 run_event，客户端以 `Last-Event-ID:1` 重连，则 `oldest_seq=null`、`last<watermark`：初始判断漏过，返回 stream_status；每次查事件均为空，查状态仍为 SUCCEEDED/3，随后第 280–281 行直接 continue。由于 last 永远无法到达 3 且 nextPoll 不更新，本连接会持续执行两条 PG 查询，直到约 55 秒期限（或容器先超时），既没有 CURSOR_EXPIRED，也没有正常终态收敛。并发重连会将本应立即返回的恢复请求转化为持续数据库负载。

这是源码控制流可确定的边界；本轮没有执行新复现或声称运行复现成功。最小回归输入可复用已有真实 PG fixture：写三条事实并置终态，删除该 run 全部事件，以游标 1 请求，断言立即返回无 id 的 CURSOR_EXPIRED、eventsWatermark=3 和终态；同时验证 event_seq=0/无事件的新 run 及 last=watermark 的已追平 run 不误报过期。建议初始判断覆盖“无保留事件但 watermark 高于游标”，并使排空期间遇到清理形成的不可追平水位也进入受控恢复路径，避免无限紧密重查。

现有 `expiredFactCursorSignalsStatusWatermarkInsteadOfClaimingReplay` 仅删除 seq<=2、保留 seq=3，不能覆盖本例。最后增加的就绪 chunk 快速排空优化也没有覆盖这个状态。

### [P2] 断连时丢弃未关闭的 Lettuce 连接对象，重建次数决定常驻资源增长

位置：`engine-runtime/src/main/java/cn/agentplatform/engine/infra/RedisChunks.java:64–69`，重建路径 `:76–81`。

onRedisDisconnected 将 publisher/subscriber 置 null，下一次 connect 新建连接，但没有对退休连接执行 close/closeAsync。`autoReconnect(false)` 禁止隐式重发，却不会自动注销断开连接的 Java 对象。当前 Lettuce 6.5.5.RELEASE 的 AbstractRedisClient.closeableResources 仍持有这些连接；每次网络断连/恢复都增加两个对象以及它们持有的 endpoint、listener 等，直到整个 client shutdown 才清理。活跃 socket 保持两个并不能证明长期内存资源有界。

本轮已运行两个新探针。首先检查当前依赖在原始 channel 断开后资源注册行为；随后直接创建本次已编译的真实 ChunkBus，通过反射仅关闭该 bus 自己的 client ChannelGroup，等待 RedisChunks 的真实后台 connector 恢复。实际源码探针输出：

```text
ACTUAL initial resources=2 channels=2
ACTUAL cycle=1 resources=4 channels=2 available=true
ACTUAL cycle=2 resources=6 channels=2 available=true
ACTUAL cycle=3 resources=8 channels=2 available=true
ACTUAL after shutdown resources=0 channels=0
```

依赖本身的对照探针输出（每轮新建一发布连接和一订阅连接，只关闭自己的原始 channels）：

```text
BASE resources=0 channels=0
CYCLE 1 connected resources=2 channels=2
CYCLE 1 after raw disconnect resources=2 channels=0 pubOpen=false pubClosed=false
CYCLE 2 connected resources=4 channels=2
CYCLE 2 after raw disconnect resources=4 channels=0 pubOpen=false pubClosed=false
CYCLE 3 connected resources=6 channels=2
CYCLE 3 after raw disconnect resources=6 channels=0 pubOpen=false pubClosed=false
AFTER client shutdown resources=0 channels=0
```

应对退休连接执行显式异步关闭，避免在 Netty 事件线程中同步等待自己的 close；同时保证字段替换、旧连接回调和 shutdown 的竞态不会误清新连接。增加多轮断连/恢复的资源注册数量回归，证明每轮退役对象被注销，而不只是 available 恢复为 true。当前恢复测试只有一轮，断言未来消息与不重播，没有资源数量断言。

#### 新探针的命令与复现说明

先只读 JAR 签名确认资源注册字段和关闭接口；未下载依赖或运行 Maven：

```powershell
& 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot/bin/javap.exe' `
  -classpath '.tools/m2/io/lettuce/lettuce-core/6.5.5.RELEASE/lettuce-core-6.5.5.RELEASE.jar' `
  -private io.lettuce.core.AbstractRedisClient io.lettuce.core.protocol.DefaultEndpoint `
  io.lettuce.core.RedisChannelHandler io.lettuce.core.protocol.ConnectionWatchdog
```

相关签名包括 `protected final Set<Closeable> closeableResources`、`protected final ChannelGroup channels`、`RedisChannelHandler.closeAsync()`、`registerCloseables(Collection<Closeable>, Closeable...)`、`isClosed()`。这里只使用 javap 签名检查，不声称已读取完整依赖源码；释放行为由上述运行探针确认。

真实 ChunkBus 探针命令（从新工程根执行；类路径取已完成的定向测试 XML，避免再次编译）：

```powershell
[xml]$reviewXml = Get-Content 'engine-runtime/target/surefire-reports/TEST-cn.agentplatform.engine.infra.ChunkBusRedisTest.xml'
$reviewClasspath = ($reviewXml.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
@'
{
String password=java.nio.file.Files.readAllLines(java.nio.file.Path.of(".env")).stream().filter(s->s.startsWith("AGENT_REDIS_PASSWORD=")).map(s->s.substring(s.indexOf('=')+1).trim()).findFirst().orElseThrow();
if(password.startsWith("\"")&&password.endsWith("\"")) password=password.substring(1,password.length()-1);
var bus=new cn.agentplatform.engine.infra.ChunkBus("127.0.0.1",26379,password,256,16777216);
var bf=bus.getClass().getDeclaredField("redis");bf.setAccessible(true);var redis=bf.get(bus);var cl=redis.getClass().getDeclaredField("client");cl.setAccessible(true);var client=(io.lettuce.core.RedisClient)cl.get(redis);
var rf=io.lettuce.core.AbstractRedisClient.class.getDeclaredField("closeableResources");rf.setAccessible(true);var cf=io.lettuce.core.AbstractRedisClient.class.getDeclaredField("channels");cf.setAccessible(true);
var retained=(java.util.Set<?>)rf.get(client);var channels=(io.netty.channel.group.ChannelGroup)cf.get(client);
try {long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);while(!bus.chunkAvailable()&&System.nanoTime()<deadline)Thread.sleep(10);if(!bus.chunkAvailable())throw new IllegalStateException("initial connect timeout");System.out.println("ACTUAL initial resources="+retained.size()+" channels="+channels.size());
for(int i=1;i<=3;i++){channels.close().awaitUninterruptibly();Thread.sleep(1200);deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);while(!bus.chunkAvailable()&&System.nanoTime()<deadline)Thread.sleep(10);if(!bus.chunkAvailable())throw new IllegalStateException("reconnect timeout");System.out.println("ACTUAL cycle="+i+" resources="+retained.size()+" channels="+channels.size()+" available="+bus.chunkAvailable());}}
finally{bus.close();}
System.out.println("ACTUAL after shutdown resources="+retained.size()+" channels="+channels.size());
}
/exit
'@ | & 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot/bin/jshell.exe' '-R-Djdk.net.unixdomain.tmpdir=V:/.local/tmp' --class-path $reviewClasspath -
```

真实 ChunkBus 探针运行约 5.9 秒，退出码 0；对应工具输出标识 `6415a6`。依赖对照约 2.9 秒，退出码 0，输出标识 `b55520`。最初对照探针遗漏 `jdk.net.unixdomain.tmpdir`，在打开 selector 时因 Windows Unix-domain 临时路径失败；修正为既有测试所用的临时目录参数后才得到有效结果，不把最初失败计作资源行为证据。

## 规范与质量核对

| 项目 | 本范围内结论 |
| --- | --- |
| PG 权威、事实 seq 与临时 chunk 分离 | PG 事实只从 run_event 按 seq 读取；chunk/status/reset 不分配事实 ID。没有对业务 run 进行取消或重试。 |
| 授权先于订阅 | controller 先用 Actor.current 调用 store.run，再调用 chunks.subscribe。真实 PG 测试覆盖跨 tenant/owner 拒绝；这不等于本轮重验 JWT 验签实现。 |
| Redis 分布式路径 | 生产 publish 只经 Redis 回送，本地 fixture 构造器有明确说明；固定内部通道，Redis7 PubSub 没有离线 token 重放；autoReconnect=false，后台重连。退役连接资源未释放，见第二项 P2。 |
| 有界队列与载荷 | 每订阅 32 帧、文本 UTF-8 8 KiB；全局文本 Semaphore 覆盖队列及已 poll 未 close 帧；全局 response 预算在 PG 读取前取得，持有至 flush 返回；单事实 256 KiB、页大小 1。 |
| 真实写线程生命周期 | writer 进入与 afterCompletion 通过同一个 CAS 竞争；未启动任务清理与已启动 writer 的 finally 分工明确；stop 清队列但不释放订阅槽，写阻塞仍占用容量。没有发现该主要路径提前释放 permit 的确定缺陷。 |
| cleanup 与锁 | Frame/Reservation 幂等释放；订阅注册/offer/stop/remove 使用 bus 锁串行，poll 依靠有界并发队列；同步临界区没有 HTTP 写入。 |
| SSE 首响应 | ResponseEntity 显式 text/event-stream/no-store；发送在 StreamingResponseBody 内开始，没有 SseEmitter 初始化前缓存。 |
| 恢复与终态 | 常规 Last-Event-ID、超大事实、部分历史过期、终态水位排空有实现与测试；全历史清理边界见上述 P2。 |

UTF-8 预算是逻辑载荷，不等同 JVM 堆精确计量；JSON 转义、String、帧和协议对象有额外有界开销。这里的有界观察机制不能替代其他五类内存硬闸或整体容量验收。

## 既有测试证据审阅

本轮读取并核对日志与测试断言，没有执行这些套件：

- `.local/engine-stream-final-full-green.log`：129 项、失败/错误/跳过均为 0，BUILD SUCCESS，结束于 2026-10-20 05:14:43 +08:00。
- `.local/engine-stream-final-targeted-green.log`：最终冻结源码相关 20 项、失败/错误/跳过均为 0，BUILD SUCCESS，结束于 05:15:46。构成为 ChunkBusTest 5、ChunkBusRedisTest 4、RunEventResponseTest 2、RunEventsHttpTest 1、RunEventsPostgresTest 7、RuntimeStreamTest 1。不能与早一轮全量合并称为“最终全量 130 项通过”。
- Redis 测试检查两个独立 bus 的跨实例交付、run 隔离、不重复、取消、TCP 断连及恢复后 future-only；它们使用同一 JVM，不是两个实际 engine 进程。
- PG 测试检查授权拒绝、游标补发、终态、chunk 无事实 ID、超大事实与局部保留窗口清理。HTTP fixture 使用真实随机端口 Tomcat/Socket/RST，检查 200 和 text/event-stream 及 RST 后槽可复用；该 fixture 的 PG 边界是 mock。
- 当前测试没有直接覆盖 async 任务尚未开始时的超时/提交失败，或持续阻塞 OutputStream 与 completion 的对抗竞态；这些路径本轮仅做代码审查，不能写成实测已通过。
- 日志有 Mockito/ByteBuddy 动态 agent、CDS 和编译提示，不描述为零警告。

## 验收边界

两实际 engine 进程纵切、5000+ SSE/500 运行容量、客户端 Chat/SDK 自动重连、其他 G5 调度与内存闸由主协调者后续处理，不属于此报告通过声明。P2 修复后需针对该边界提供回归证据，并更新冻结包；当前报告不能作为全 T15 关闭依据。

## 两项 P2 修复后定向复核

2026-10-20，全模块测试结束于 05:27:05 +08:00 后进行。此次只读取 `engine-stream-fix.md`、四个指定 Java 文件、新增断言、红绿日志与最终 surefire XML；没有重跑套件或新探针，没有扩大评审范围，没有启停服务或修改业务代码。

最新冻结输入为 `docs/evidence/engine-stream-fix.md` 所列四个 Java SHA256，独立计算全部匹配（4/4）。原 10 文件包保留为初审历史输入；不能用原包的四个旧摘要代表当前修复源码。

1. **全历史清理 P2：关闭。** 初始快照使用 `oldest_seq=null && last<watermark` 识别过期，空页后的状态查询也在同一 SQL 快照内取 min(seq)，覆盖清理发生在初始检查后的路径。两处发送均无事实 id，不更新 last，保留状态和水位并指向 FINAL_SNAPSHOT。新增真实 PG 测试同时覆盖 RUNNING/SUCCEEDED、旧/超前/已追平游标、event_seq=0 的空运行，以及首帧 flush 后清空历史。每例限制 1 秒、最多两次 queryForMap 和一次 queryForList，直接验证紧密轮询已消除；其 JdbcTemplate spy 没有 stub 权威数据。
2. **退休 Redis 连接泄漏 P2：关闭。** 断连处理在短锁内按连接身份摘除完整旧连接对，再在锁外 closeAsync 两端；旧回调不匹配新连接对，不会清空新引用。connector 的网络建连及 subscribe 等待均在锁外，未发布的局部连接在 finally 关闭，完整且仍打开的连接对才发布；close 先标记关闭和摘除已发布连接，再停止/等待 connector 并关闭 client/resources。新增真实 Redis 测试覆盖四轮交替切一条/两条连接，资源每轮回到 2，最终回到 0；部分建连失败也归零，并在第二条 TCP 已接受而握手停滞时并发 close，以 4 秒上限和资源归零验证实际建连清理竞争。autoReconnect=false、未来 chunk 恢复语义未改变。

独立核对的现有执行证据（均为审阅既有运行结果，不称本次重跑）：

| 日志 | 已核对结果 |
| --- | --- |
| `.local/engine-stream-fix-red.log` | 05:23:26；4 项、4 失败、0 错误/跳过，BUILD FAILURE；对应两个 PG 与两个 Redis 新回归。 |
| `.local/engine-stream-fix-targeted-green.log` | 05:25:25；24 项，失败/错误/跳过均 0，BUILD SUCCESS。 |
| `.local/engine-stream-fix-full-green.log` | 05:27:05；134 项，失败/错误/跳过均 0，BUILD SUCCESS。 |
| `engine-runtime/target/surefire-reports/TEST-*.xml` | 本次只读独立汇总 27 个 suite：134 tests、0 failures、0 errors、0 skipped，与全量日志一致。 |

格式化 dry-run 退出码 0 来自修复者证据，本次未重复执行。Lettuce 重复关闭 WARN、Mockito/ByteBuddy/CDS 提示按修复报告保留，不将通过结果描述为零警告。

本次定向复核没有留下这两项发现的未解决阻塞项。前述初审发现、探针和测试边界保留供追溯；两实际 engine 进程纵切与前端消费者评审仍由主协调者继续，容量及其他已登记 G5 项也不由本次复核关闭。

## 生产 MVC async timeout 修复定向复核

2026-10-20，05:44:42 定向测试完成后独立复核。结论：**本次最小配置修复无阻断，可更新自有 engine 进程并继续真实 65 秒前端纵切验证**。本 reviewer 没有部署、启停服务、执行 Maven 或重跑测试；只追加本报告，engine Maven/target 窗口未占用。

冻结输入为 `engine-mvc-timeout-fix.md` 及两个文件，已独立计算 SHA256 全部匹配：

- application.yml：`E6B3926D23BE538CA17E9D87DD3115BB99D936EC59FCF2A2AFA3F881A4CCD38D`。
- EngineContextPostgresTest.java：`B17C7F38F8F28EC89BBC79EB626927A2233524FD39CB499B06D0AD11AE52F575`。

生产配置 `spring.mvc.async.request-timeout: 60s` 位于正确的 Boot 配置层级，保留有限异步超时，并给 RunController 的 55 秒观察循环留出余量。配置不改变业务 run 状态、模型预算、取消或重试机制，也未改变原 writer/afterCompletion 的 CAS 或 subscription finally 生命周期。60 秒超时不是强制终止底层阻塞写的保证；已启动但尚未退出的 writer 仍持有原订阅许可，因此此变更没有引入靠超时提前释放许可的容量绕过。55 秒是循环时间边界，慢写/DB 等待仍可能使实际退出晚于该值，不能据此承诺所有连接精确在 55 秒结束。

新增回归使用 `@SpringBootTest(webEnvironment=MOCK)` 加载真实生产 Boot MVC 配置，通过 `MockMvcBuilders.webAppContextSetup(context)` 执行真实 handler，检查请求已进入 async 且 `AsyncContext.getTimeout()>55000`。这是 Boot MVC 配置生效的回归，Servlet request/context 本身仍是 MockMvc 对象，不描述为该用例已经等待真实网络 55 秒。DynamicPropertySource 只覆盖测试数据库三项，测试没有覆盖 async timeout；原 HTTP fixture 自设 60000 的盲区因此得到补充。Callable 最多等待 10 秒且 finally 总会 countDown，不必等待整个观察窗口。

探针及 TestConfiguration 均只位于 `src/test`，编译产物在 `target/test-classes`；只读检查 `src/main` 与 `target/classes` 没有该探针类。父/模块 POM 没有将 test-classes 打进主 JAR 的自定义配置；现有生产 JAR 中也没有 EngineContextPostgresTest/AsyncProbe 条目。现有 JAR 时间为 05:32:59，早于此修改，不能据它声称新配置已经部署；后续标准打包应只包含生产配置，不包含测试接口。

已读日志并核对：05:43:16 红测 2 项、1 失败、0 错误/跳过，失败断言正是有效 timeout 未超过 55000；05:43:50 绿测 2/2；05:44:42 格式后 EngineContextPostgresTest 与 RunEventsHttpTest 合计 3 项、0 失败/错误/跳过、BUILD SUCCESS。均为已有执行证据，本轮没有重跑。

另已阅读 `engine-stream-replicas-live.md` 和 `backend-stream-async-reviewed-all.md`：实际双 JVM/PG/Redis纵切与 05:41:11 后端 366 项整轮是此前阶段证据，不包含此次 timeout 修复和新增测试。不得将两者相加冒称最终全量 367 项。生产约 30 秒首 EOF 的历史观察保留；只有后续重新部署并重跑 65 秒真实服务/浏览器纵切，才能确认实际首 EOF 达到约 55 秒。前端非终态空输出修复、容量和其他 G5 验收仍属后续工作。
