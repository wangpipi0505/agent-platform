# G5/T15 在线增量与有界观察计划

2026-10-20。仅本新工程；只读检查已覆盖 AGENTS、implementation-brief、contracts/README、README、冻结详设 F10（1769–1773）、隔离矩阵与六道内存硬闸。主协调者已释放 engine 写入/构建独占窗口；使用 TDD、Java 技能与子任务独立审查，持续开发授权已覆盖本实现。

## 不变量与接口

- PG 继续是 run 状态、事实事件单调 seq、完整最终结果的唯一权威；Redis 7 PubSub 只传在线展示增量，连接恢复只交付恢复后的新消息，不补发历史 token。
- `AgentDriver -> ChunkBus.publish(long,String)` 不变。仅 controller 使用旧 subscribe 回调，可改为 `subscribe(long) -> Subscription` 的有界 poll 句柄，让一条 SSE 的唯一发送线程串行处理事实与 chunk，取消同步回调及另一层发送线程。
- `TraceStore.run(id,Actor.current())` 先校验 tenant+principal，再创建本地订阅。浏览器没有 Redis 通道名或任意 run 内部 ID 订阅入口。PG 自增 run 主键作内部消息路由键。
- 生产必须经 Redis 回送到本地，禁止 publish 先投本地再 Redis 造成重复，也禁止 Redis 故障时以进程内成功冒充分布式成功。显式 `new ChunkBus()` 只供现有 AgentDriver 测试构造器使用并注明 fixture。

## 有界资源与恢复

- 每副本默认最多 256 条 SSE，可配置到 5000（容量尚未验收）；每订阅最多 32 条文本，每条 UTF-8 文本最多 8 KiB；排队及发送中的文本同时受每副本全局 16 MiB UTF-8 预算约束（可配置最多 64 MiB）。队列不阻塞 offer，溢出关闭该观察通道，由客户端按最后事实 seq 重连并读取最终快照。
- Redis 采用现有 Spring Data Redis 所带 lettuce；独立 1 条发布连接+1 条订阅连接；异步发布最多 64 条在途，客户端请求队列也有界。只对服务端固定内部通道订阅，以 runId 信封分发。
- lettuce 固定 2 条 I/O 与 2 条计算线程资源、1 条后台建连工作者，连接/命令超时 1 秒。关闭 lettuce autoReconnect，防止已接受但未确认 PUBLISH 被 SDK 隐式重发；固定工作者新建连接，只收恢复后的新增量。断线不会改 run 状态或重发模型，PubSub 不具有离线补发保证。
- `StreamingResponseBody` 在 MVC 初始化完成后由单条写线程串行写 SSE 并 flush，消除 `SseEmitter.earlySendAttempts` 隐藏缓存。订阅槽覆盖真实写线程整个生命周期；任务未启动即完成/超时的路径由 callable interceptor 清理；开始写后的断开/取消/发送失败/终态由 finally 清理。
- 单条事实最多 256 KiB，PG 每次读 1 条；读前预留独立全局 16 MiB 发送预算，持有至 flush 返回。超大事实发 EVENT_LIMIT 后关闭观察并保留 PG 查询恢复途径。游标过期/超前发无事实 ID 的状态+水位快照恢复信号。
- 恢复信号不分配事实 event ID；chunk 不携带持久 seq。任何观察中断不能取消业务 run。

## TDD 与验收边界

1. 首先编写并运行当前同步 ChunkBus 可编译的慢观察者阻塞回归，观察预期失败；再引入 poll API 与队列、数量、字节闸、cleanup 测试。
2. 两个独立 bus/独立 Redis 连接在本工程 Redis7 上验证跨实例、run 隔离、本地不重复、取消订阅；断连拒绝/丢增量不影响 PG 事实路径。
3. 本工程专用 PG 测试库验证 controller 授权拒绝在 subscribe 之前，Last-Event-ID 补发与 chunk 不带 id，终态正常收敛。
4. 单独运行 engine 定向及全量 Maven，不与主协调者/其他工作者共享 target 并发。保存红绿输出及准确用例数，最后独立审查。

两个同 JVM bus 实例的 Redis 测试只是传输集成证据；两个实际 engine 进程的纵切由主协调者随后执行。5000+ SSE、500 并发容量、调度公平性与其他内存硬闸不在本增量完成声明内。

预算计数是 UTF-8 逻辑载荷，不等于精确 JVM 堆：JSON 转义和 String/帧对象会增加有界常数开销。Redis PUBLISH 在途最多 64、每条线上编码上限约 49 KiB；无离线发布队列、无逐 token 持久化。初始 Redis 不可用时 stream_status 标明 chunkAvailable=false，继续 PG 事实；活动连接断开/本地缓冲溢出发 stream_reset 并关闭，客户端恢复状态+事实 seq；未接收的 token 只能由最终权威结果替换。
