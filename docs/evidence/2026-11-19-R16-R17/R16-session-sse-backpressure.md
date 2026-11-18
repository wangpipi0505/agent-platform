# R-16 会话 SSE 限流/背压——交付证据（2026-11-19）

分支 `close-wt-r16`（基 5bde1c30），worktree wt-r16。

## 现状核查（诚实登记）

H1-9 会话 SSE（`ChatSessions.eventStream`/`StreamPump`）在交付时已具备 T2-11 任务 SSE 同构的
大部分背压策略：

- PG（事实源）即缓冲，进程内不设无界队列（每批 `SessionStore.read` ≤200 事件）；
- `onCompletion`/`onTimeout`/`onError` → `pump::close` 收口（既有）；
- 未读积压超限判慢客户端主动断开（原硬编码常量 `STREAM_BACKLOG_LIMIT=2000`，与 T2-11
  任务 SSE 同值同语义）；
- 断开只停泵不取消 turn（泵内无 run/turn 写路径）。

本卡补齐的缺口（三项）：

1. **积压上限配置化**：原 `static final int STREAM_BACKLOG_LIMIT = 2000` → 配置键
   `agent.sse.max-buffered-events`（默认 2000），会话/任务两泵共用同一键。
2. **慢消费者断开记审计行**：断开前落结构化审计行 `AUDIT SESSION_SSE_SLOW_CONSUMER_DISCONNECT`
   （tenant/principal/appCode/sessionId/unreadBacklog/limit/resume 可追溯）；任务 SSE 同步补
   `AUDIT TASK_SSE_SLOW_CONSUMER_DISCONNECT`（taskId/unreadBacklog/limit）。
3. **单主体连接上限（新增第二道限流面）**：`agent.sse.max-connections-per-principal`
   （默认 50）——同 tenant+principal 活动会话 SSE 连接达上限时拒绝新连接
   （429 `SESSION_SSE_CONNECTION_LIMIT`）并记审计行 `AUDIT SESSION_SSE_CONNECTION_LIMIT`；
   在归属校验之后执行（无权者仍 404），既有连接不受影响。

## 改动文件

- `engine-runtime/src/main/java/cn/agentplatform/engine/application/ChatSessions.java`
  - :24-45 两个 @Value 配置字段（:34 maxBufferedEvents/:42 maxConnectionsPerPrincipal，含口径 javadoc）；
  - :536-577 eventStream 连接上限判定+429+审计（:558 审计行），泵携带连接时身份快照（tenant/principal/appCode）；
  - :749-816 StreamPump 慢消费者断开审计行（:808，unreadBacklog/limit 可追溯）；
  - 删除 `STREAM_BACKLOG_LIMIT` 静态常量（改配置项；两处 pump 引用同步更新）。
- `engine-runtime/src/main/java/cn/agentplatform/engine/application/Tasks.java`
  - :56-62 `agent.sse.max-buffered-events` 字段（:61 @Value，与会话 SSE 共键）；
  - :665-692 TaskEventPump 慢消费者断开审计行（:672，断开不取消任务语义不变）。
- `engine-runtime/src/main/resources/application.yml` :44-53 `agent.sse` 两键默认值
  （env 间接引用 `${AGENT_SSE_MAX_BUFFERED_EVENTS:2000}` / `${AGENT_SSE_MAX_CONNECTIONS_PER_PRINCIPAL:50}`）。
- `engine-runtime/src/test/java/cn/agentplatform/engine/infra/SessionSseBackpressureTest.java`
  新增 4 用例。
- `docs/evidence/2026-11-19-R16-R17/R16-session-sse-backpressure.md` 本文件。

## DoD 命令与结果

```bash
# 编译
mvn -B -ntp -pl engine-runtime -am -DskipTests compile -Dmaven.repo.local=.tools/m2
# → BUILD SUCCESS (EXIT=0)

# 新用例（ENGINE_PG_TEST=true，专用测试库 agent_platform_engine_test）
mvn -B -ntp -pl engine-runtime -am test -Dtest=SessionSseBackpressureTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.repo.local=.tools/m2 \
  "-DargLine=-Xmx1024m" -Dsurefire.timeout=600
# → Tests run: 4, Failures: 0, Errors: 0, Skipped: 0

# 既有 SSE 回归
mvn -B -ntp -pl engine-runtime -am test \
  "-Dtest=SessionEventsEndpointTest,TaskEventsSseTest,TaskEventsSseRealPortTest,TasksApiTest,SessionListEndpointTest" \
  -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.repo.local=.tools/m2 "-DargLine=-Xmx1024m"
# → 31/31 绿（TaskEventsSseRealPortTest 2 + TaskEventsSseTest 5 + TasksApiTest 7
#   + SessionEventsEndpointTest 11 + SessionListEndpointTest 6）
```

## 测试统计

- 新增 SessionSseBackpressureTest 4/4 绿（首轮交付后修复一处测试自身竞态，见登记项 7，
  修复后组合回归 3×21/21 + 两卡 10 类 65/65 全绿）：
  1. `slowConsumerDisconnectedWithAuditWhenBacklogExceedsLimit`——上限反射调小为 5，预插 210
     事件（>单批 200+上限 5）：首批 200 帧落盘后未读积压 10>5 → 泵主动断开，恰好记一条
     SLOW_CONSUMER_DISCONNECT 审计行（含租户/主体/会话号/unreadBacklog=10/limit=5）；响应
     200、事件事实不被删除、无轮次写入（断开不取消 turn）。
  2. `connectionCapPerPrincipalRejectsWith429AndAuditWithoutTouchingExistingStreams`——上限
     调小为 2：第 3 条 429 SESSION_SSE_CONNECTION_LIMIT+审计行（active=2/limit=2）；另一主体
     同租户不受影响（per-principal 非全局）；既有连接继续出帧。
  3. `fiftyConcurrentConnectionsStayBoundedInMemoryAndCapRejects51st`——50 连接拉同一会话流
     全部出帧；System.gc 后堆增量 <256MB（有界）；默认上限 50 下第 51 连接 429+审计
     （active=50/limit=50，同时验证默认值生效）。
  4. `backpressureDefaultsAreDeclaredInApplicationYml`——application.yml 两键+默认 2000/50
     在案；fresh 实例（非 Spring 注入）字段初值与 yml 默认一致。

## 诚实登记项

1. **背压策略主体（PG 即缓冲/批量≤200/超限断开/收口钩子）系 H1-9 已交付**，本卡为"补齐缺口"
   而非新建：积压上限 2000 原为硬编码常量，行为已在；新增的是配置化+审计行+连接上限。
2. **50 连接压测为低成本模拟**（卡面允许口径）：standalone MockMvc 异步请求+System.gc 堆采样，
   门槛为宽松的 256MB 增量上界，非真实网络负载压测；单连接"慢"由积压判定承载（泵同步发送，
   进程内不存在无界发送队列，OOM 面只在 PG 缓冲——事实源本就有界于库容量）。
3. **连接上限仅落会话 SSE**（卡面范围为 ChatSessions 事件流）；任务 SSE（T2-11）本卡只补
   积压配置化与审计行，未加连接上限（如实登记；如需同构可另卡）。
4. **yml 默认与代码默认双写**（@Value 字段初值=2000/50 与 yml 一致）：非 Spring 构造（如既有
   单测 `new ChatSessions(...)`）取字段初值，Spring 装配取 yml；测试④断言两者一致。
5. 配置键按卡面建议命名 `agent.sse.*`（会话/任务共用 `max-buffered-events`；`max-connections-
   per-principal` 目前仅会话侧消费）。
6. **全量套件环境失败集（基线在案，与本卡无关）**：本 worktree 直接 `mvn test` 全量跑
   engine-runtime 有 27 个环境依赖失败类（@SpringBootTest 缺 AGENT_SECURITY_MODE、缺
   `.local/iam-n3-*` n3 工件、缺 agent_platform_statistics_test 专用库、Redis 缓存未起、
   共享库 FK 清理顺序敏感、既有 ChatSessionConfigPinTest 构造 NPE）。基线 5bde1c30 与本卡
   HEAD 三次全量对比（环境命令完全一致）：失败类集合逐类相同（27=27=27），本卡零新增失败类
   ——明细见 R17 证据文件"全量套件对比"节。
7. **测试自身竞态修复（首轮交付后）**：慢消费者用例原以"泵收口探测（closeSessionStreams
   轮询）"间接推断断开——该探测会主动 close 尚未自然断开的泵，负载下抢跑泵内断开分支导致
   审计行缺失（隔离 5/5 绿、跨类组合下可复现）。修复：改为直接轮询审计行（在 ChatSessions
   logger 监视器内读取 ListAppender，与泵线程的 logback append 构成 happens-before），
   收口探测移至审计断言之后；生产行为无变化（泵内 warn 先于 close 顺序未动）。
