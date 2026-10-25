# G5 调度与分池独立审查

日期：2026-10-20（Asia/Shanghai）。结论：**REQUEST_CHANGES**。确认 1 项 P1、2 项 P2；未发现 P0。现有定向测试通过不能关闭下面三个反例，暂不部署。

## 范围与证据边界

审查依据为本工程 AGENTS、v1.2 implementation brief/contracts、冻结详设的调度与恢复约束，以及 `run-scheduler-implementation-plan.md`、`run-scheduler-report.md`、`run-scheduler-sha256.json`、`run-scheduler-test-preservation.json`。阅读了新的调度器、仓储、V215、创建/会话/恢复/审批/取消入口、容量错误处理与网关响应头实现和相关测试。

只使用新工程代码与测试库，未读取旧相邻工程配置或数据库，未连接 `agent_platform_dev_v12`。未修改生产代码或作者测试，未打包、部署或启停六个服务。真实 PG 探针使用专用 `127.0.0.1:25432/agent_platform_review_scheduler_20261020_test`；新建库迁移至 engine V215，探针创建的会话、轮次、节点、run 均清理。Maven 定向测试使用已授权的 `agent_platform_engine_test`。

审查结束时再次计算冻结清单：**23/23 SHA-256 匹配**。受保护文件摘要也分别匹配：

| 文件 | SHA-256 |
|---|---|
| InputDeclarations.java | d7e3ea02fc12fb6574513fabc759fd978c2c42b4c3c340e9bfb46207644b5e89 |
| FlowDriver.java | a0e65396eb52818a091b33cb1606053b0ddc4969381cace0b2b0f9e8b407c563 |
| OutputPolicy.java | cebcbaf813d4da4f77e453dbfb2e3d247ead8a31dca522d6f2ac4145696f53de |

## 确认发现

### P1：会话首次预约绕过持久化准入线性化点，drain 后返回 503 却消费轮次

位置：`engine-runtime/src/main/java/cn/agentplatform/engine/application/ChatSessions.java:88`、`:104`、`:180`；`RunService.java:470`；`RunScheduler.java` 的 `Admission.persist`。

新会话轮次先取得 ticket，随后直接通过 `store.atomic` 插入 `chat_turn` 并增加 `next_turn`；直到构建 run 时才调用 `ticket.persist` 检查 draining。因此 reserve 返回后、预约事务开始前发生 drain，仍然写入预约，然后在 run 持久化前返回 `503 ENGINE_DRAINING`。同样，预约已经成功后才开始 drain，第二次 gate 检查也会拒绝已受理的工作。两种时序均可留下 `run_public_id=null`。

独立真实 PG/实际 ChatSessions 探针：RunService 子类仅覆盖 `reserveSessionTurn`，执行 `super.reserveSessionTurn()` 后立即 `scheduler.close()`，再返回原 ticket。其余调用实际 `ChatSessions.turn`、RunService、TraceStore 与 JDBC；配置 interactive=1、batch=1、poll=20ms、lease=3000ms、drain=0。冻结 CHAT 快照使用 START→END，输入合法，依赖客户端禁止任何 POST。实际输出：

```text
DRAIN_REJECT=503 ENGINE_DRAINING
TURN_AFTER_503=[{turn_no=1, request_id=first, run_public_id=null}]
NEXT_TURN=2
RUN_COUNT=0
SLOTS=1
CLEAN_TURNS=1
CLEAN_SESSION=1
```

这不是槽位泄漏，而是拒绝响应与持久化状态不一致。原 requestId 可以走既有空预约恢复路径，但新 requestId 会受到上一轮未完成的限制，用户收到拒绝后仍被消费轮次。不应要求调用者自行用重试修复此状态。

修复验收：首次 `chat_turn` 持久化必须使用同一 ticket 的准入线性化点；若 drain 赢在首次受理之前，应 503 且 0 新 run/turn、next_turn 不变、0 paid。预约成功即为已受理工作，之后创建 run 应继续使用该预约和 ticket，不能再次以 drain 拒绝它。HTTP/快照准备仍在短 PG 事务之外。增加两个时序的实际会话测试，而不仅是单独调用 Admission.persist 的状态机测试。

### P2：单个 PG 行锁阻塞整个心跳续租链，使无争用 run 同时失租

位置：`RunScheduler.java:382-387`、`engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunScheduleRepository.java:82-95`；关联检查 `RunService.java:1603-1627`。

独立心跳线程解决了 poll/readyFacts 阻塞，但 heartbeat 内按顺序执行普通阻塞 UPDATE；全部 dispatch 续租结束后才调用 renewNodes。一个 run 行被其他事务锁住，就阻止其它 active dispatch 和所有 node/tool claimed lease 续租。异常也会跳出整个 heartbeat，缺少逐项隔离。

独立真实 PG 反例：两个 active 阻塞 worker，lease=1000ms，poll=20ms；不启动定时器，手动 poll 建立两项 dispatch。通过反射只确定 `active.values()` 实际遍历的第一项，在独立 JDBC 事务 `SELECT ... FOR UPDATE` 锁住该 run；异步调用实际 heartbeat，等待 1250ms，读取另一项未锁 run 的 lease 与 node-renew 回调计数。核心创建/加锁/等待置于同一个 JShell block，避免交互编译延迟影响初始租约。实际输出：

```text
REAL_PG_HEARTBEAT_BLOCKED=true
UNLOCKED_OTHER_LEASE_VALID=false
NODE_RENEWALS=0 ACTIVE=2
CLEAN_RUNS=2
```

回滚持锁事务后 heartbeat 完成，释放两个 worker 并清理其节点和 run。该探针确认无争用 dispatch 失租及 node-renew 被饿死，**未声称实际 node 已过 90 秒或发生重复付费调用**。首次使用短租约、多个交互片段的试跑发生初始化时延，不能作为本发现证据；上面单 block 版本为有效复现。

修复验收：续租必须有局部、非阻塞或严格有界的行锁策略，且逐项失败不能中断其它项；node 与 tool claimed lease 路径也必须防止同类串行阻塞。可采用短事务/CTE 的 FOR UPDATE SKIP LOCKED 或有界局部锁超时，但不能放松 owner/generation、未过期与终态条件。增加真实 PG 持锁测试，检查未锁 run/node 的续租仍前进。

### P2：合法的大 dispatch lease 配置把 node 心跳延后到其固定租约过期之后

位置：`RunScheduler.java:46-69`、`:107-112`；`RunService.java:1603-1627`；`TraceStore.java:114`。

构造器允许 dispatch lease=300000ms；心跳首次延迟与后续间隔均为 lease/3，即 100000ms。节点初次 claim 和续租却固定为 90000ms。因此该合法配置下，一个接近启动时就开始的长调用，其 node 租约会在首次心跳前到期，后续 `lease_until>now()` 条件也不能续回；调大 dispatch lease 反而破坏 node/tool 租约安全。

独立构造器正例确认 300000ms 可被接受。100000ms 与 90000ms 来自已核对源码的确定性计算，**没有把它冒称为运行 90 秒的实际过期测试**。默认 90000ms dispatch lease 不触发此配置反例。

修复验收：心跳节奏同时满足固定 node/tool lease 的安全周期，例如间隔上限 30000ms；不要只以 dispatch lease 为依据。保持现有大 lease 合法配置，增加首轮延迟和后续节奏边界测试。

## 已核查的正确行为

- 仓储 claim 使用短事务与 `FOR UPDATE OF r SKIP LOCKED`，过滤真实 READY/可恢复节点后按 next_at/id 排序；每次 poll 的尝试次数由开始时槽位预算限定。尾部 READY 不被前百条不可运行记录永久遮挡，锁住队头可跳过。
- PG PENDING→RUNNING、dispatch generation 与首次 RUN_STARTED 在同一事务；事件写入失败回滚。续租/退租限定 owner+generation，旧 owner 不能修改后继代。这里的双仓储测试不等同于真实双 JVM 压测。
- interactive/batch 分池、active.putIfAbsent 与 Admission 状态机限制本地重复 worker；worker 的 finally 回收真实槽位，取消或 drain 超时不提前制造可用槽位。单独 Admission 的 close/persist/handoff 边界有针对性测试，但未覆盖上述实际会话预约窗口。
- RunService 共有创建入口、受控草稿、重执行、受控复现使用统一准入；EVALUATION 写入 PG 队列并异步调度，未保留原请求线程同步执行循环。已受理 requestId 在 full/drain 下先进行回读和内容冲突检查；历史空会话预约可以恢复。
- 原运行输入、冻结会话声明、START 校验仍在；InputDeclarations/FlowDriver/OutputPolicy 摘要未变，原输入回归 35 项与会话回归 4 项本次独立执行通过。此次 P1 是调度新引入的 drain 窗口，不撤销此前输入校验 P1 的修复历史。
- 审批、debug 恢复、外部事实就绪路径写 dispatch_next_at；恢复工作仍受调度器分池/租约约束。cancel 保持节点/工具提交屏障及终态约束。
- engine 容量错误区分 429/503 并设置 Retry-After；gateway 只额外转发明确允许的 Retry-After，未放开任意响应头或 internal 路由。monitor 判分和数据集结构不在本增量修改范围。

## 本审查亲自执行的验证

Java 21；Maven `-o -B -ntp -pl <module>`，未使用 `-am`。engine 设置 `ENGINE_PG_TEST=true` 与真实测试库。结果与作者全模块回归分开记录。

| 本次独立运行 | 结果 | 完成时间 |
|---|---|---|
| engine 九类定向：RunSchedulerPostgresTest(24)、RunAdmissionHttpTest(5)、RunAdmissionBoundaryPostgresTest(2)、RunInputValidationPostgresTest(35)、ChatSessionsTest(4)、ExecutionRecoveryTest(4)、TracePostgresTest(24)、ControlledDraftSkillTest(5)、ControlledReplayTest(2) | 105 tests，0 failures/errors/skipped | 07:35:41 +08:00 |
| platform-gateway 全模块：IntegrationPolicyTest(4)、RoutesTest(6)、IntegrationSecurityTest(6) | 16 tests，0 failures/errors/skipped | 07:37:07 +08:00 |
| node scripts/verify-contracts.mjs | 通过 | 本审查轮 |
| 冻结 23 文件及三个保护文件摘要 | 全部匹配 | 07:37 后复核 |
| 实际会话 drain PG 反例、实际 PG 心跳行锁反例、合法大 lease 构造器探针 | 三项确认，详见上文 | 本审查轮 |

作者报告的 engine 396 与 gateway 16 整模块结果只作为作者证据引用；本审查没有重跑 engine 全模块，也不合并成某个同轮总数。

## 测试保持与剩余限制

六个旧测试修改中，只有 `.local/run-scheduler-original-tests/.../RunInputValidationPostgresTest.java` 候选旧全文 SHA 与既有 675 源文件摘要对应值匹配。亲自对比显示该文件仅把直接 new RunService 换成 SchedulerTestResources.runs，原断言保留。其余五份候选旧全文摘要不匹配，不能据此声称逐断言未弱化；本审查依据当前完整测试源码、历史报告及实际行为验证。无 HEAD 的空 diff 和用例计数相等都不是断言保持证据。

尚未运行 `tests/smoke-run-scheduler-replicas.mjs`，未部署真实两副本进行故障/接管试验；上述 PG 多 owner、代际与行锁证据不能代替该项。分布式模型/tool 许可、其余内存闸与总体容量证明也不属于本子项已经验收的范围。

审查到此完成，**engine-runtime 与 platform-gateway target 已释放**。请在独立修复中完成三个反例红绿后复审；保留本报告作为冻结版本的 REQUEST_CHANGES 历史。

## 2026-10-20 07:53 返修独立复核附录

**当前候选结论：APPROVE（三项审查发现全部关闭）。** 上文 REQUEST_CHANGES 保留为原冻结版本的历史结论。本附录只关闭该 P1 和两个 P2；不把整个 G5、实际双 JVM 故障接管或部署验收标为完成。此修复范围内未发现剩余 P0/P1/P2。

### 冻结与变更核验

先独立计算摘要，再阅读 `run-scheduler-review-fixes.md`、实际补丁及四个修改后生产文件、两个新测试：

- `run-scheduler-review-fixes-sha256.json`：6/6 匹配。
- `run-scheduler-review-candidate-sha256.json`：25/25 匹配。
- `.local/run-scheduler-review-baseline/` 修改前全文：与原冻结清单 23/23 匹配。
- 原 23 文件中除四个生产修改以外的 19 文件仍逐字节匹配原冻结清单；本次未改原测试。此事实不补齐更早 675 阶段缺少的五份可靠旧测试全文，上文历史限制仍成立。
- `node scripts/verify-contracts.mjs` 本次亲自执行通过。

### P1 会话首次受理与 drain：关闭

`ChatSessions.java:176` 现在把首次短预约事务交给同一 `ticket.persist(reserve)`。`RunService.java:471` 对该 ticket 的后续 run 持久化调用 `completeReservation`；该步骤要求首次写入已经成功、ticket 仍 OPEN 且未完成过，不再次检查 drain。首次写入失败不能获得完成资格，重复消费也被拒绝。HTTP/快照准备未包进预约事务。

独立重新编写 JShell 探针，在原专用测试库使用实际 ChatSessions/RunService/TraceStore。受理前窗口仍采用 reserve 返回前立即 close；新增受理后窗口在实际 `createReservedSessionTurn` 入口 close，并断言当前没有活跃 PG 事务。唯一租户、两个会话，纯 START→END 快照，客户端 POST 计数且禁止调用。实际输出：

```text
BEFORE_ACCEPT_REJECT=503 ENGINE_DRAINING
BEFORE_ACCEPT_TURNS=0 NEXT=1 SLOTS=1
AFTER_ACCEPT_STATUS=PENDING REPLAY_SAME=true NULL_TURNS=0 NEXT=2
AFTER_ACCEPT_CONFLICT=409 REQUEST_CONFLICT
SESSION_PROBE_PAID=0 RUNS=1
SESSION_CLEAN_RUNS=1
```

因此受理前拒绝不消费预约；受理后 drain 不再孤立预约，原 ID 回读同一个 run，改内容仍 409。清理了探针两个会话、关联轮次及一个 run 的节点/事件/run。

### P2 PG 行锁阻塞续租链：关闭

`RunScheduleRepository.java:88` 用 CTE 锁定可续租 run 并 SKIP LOCKED；owner/generation、未过期和非终态条件保留。`RunScheduler.java:402-403` 对每项 dispatch 与节点续租分别隔离异常。`RunService.java:1615/1628` 对节点与工具行采用同样的跳锁策略，每个节点为独立短事务，失败仅回滚本项并继续；只有当前 owner 的活节点成功续租后，才尝试续其匹配 claimed_by、未过期、允许状态的工具调用。

独立原反例复测：专用库两个真实 active 阻塞 worker，dispatch lease=1000ms；单独连接锁住实际遍历的第一项 run。手动调用实际 heartbeat 两次，每次要求在 800ms 内返回，间隔 700ms，第二次后再等待 700ms；整个过程持有该行锁。读取未锁 dispatch 的有效性与节点续租回调次数。随后释放行锁，再尝试续已经过期的第一项。实际输出：

```text
HEARTBEAT_RETURNED_WHILE_LOCKED=true OTHER_VALID=true NODE_RENEWALS=2 ACTIVE=2
EXPIRED_LOCKED_OWNER_RENEWED=false
HEARTBEAT_CLEAN_RUNS=2
```

该独立探针保持两个有限 worker，没有资源耗尽测试。释放 worker 并清理两条 run/节点。node/tool 的实际行锁、单节点事务故障隔离、expired/foreign node/call 四种负向边界由下述本次亲自运行的新 PG 测试验证；已阅读其 SQL、真实 claim/register/claim/submitted 构建和断言，没有将它们冒称为另一轮完整外部技能调用。

### P2 大 lease 的心跳周期：关闭

`RunScheduler.java:109-111` 将首次延迟与固定延迟统一为 `max(10,min(30000,lease/3))`。本次亲自执行三个实际定时器调用参数测试：lease=100/90000/300000ms 分别安排 33/30000/30000ms 的首次延迟和后续间隔。合法大 lease 保留，固定 90 秒节点/工具租约仍受安全周期保护。这是实际参数捕获测试，不是等待 90 秒的过期试验。

### 本次独立验证与释放

Java21，真实 `agent_platform_engine_test`，`ENGINE_PG_TEST=true`，Maven 离线 `-pl engine-runtime`，未使用 `-am`。亲自执行：

```text
RunInputValidationPostgresTest       35
RunSchedulerPostgresTest             24
RunSchedulerReviewPostgresTest       13
RunSchedulerTimingTest                3
合计 75，Failures 0，Errors 0，Skipped 0
BUILD SUCCESS，2026-10-20T07:53:15+08:00
```

两条预期故障注入警告对应 dispatch 异常和 node SQL 异常隔离用例，没有被算作隐藏失败。未重复作者 engine 412 全模块，也未重跑未修改的 gateway；作者完整结果与本次 75 项独立运行严格区分。

本附录以外未修改生产、作者测试或原始证据；未启停、打包、部署、派生子代理或占用 skill target。**engine-runtime / platform-gateway target 与 engine_test 库现已释放。** 实际副本烟测、发布及其它 G5 条目仍由根任务另行验证。
