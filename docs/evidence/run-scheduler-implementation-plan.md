# G5 RunScheduler 公平调度与交互/批量分池实施计划

> **For agentic workers:** 按 executing-plans 技能逐项执行与复核。本文件仅为只读调查后的实施计划；后续须由根任务明确移交源码和 Maven target 后实施。本轮不修改 Java、迁移、配置或测试，不运行 Maven，不启停服务，不创建子智能体。

**Goal:** 将创建和恢复入口的直接启动线程，改为 PG 持久排队、公平领取及先取有界工作槽的统一调度入口，保持现有节点租约、调用幂等与唯一逻辑提交。

**Architecture:** 在 `engine.run` 增加调度机会的租约、代次和下次可调度时间；短事务用 `FOR UPDATE SKIP LOCKED` 领取。`RunScheduler` 分别维护交互、批量工作槽，取得槽和 PG 调度机会后才启动虚拟线程；实际节点仍由 `TraceStore.claim` 取得执行权。调度机会不能代替节点执行权、技能发送栅栏或事实采纳裁决。

**Tech Stack:** Java 21、Spring Boot 3.5.0、PostgreSQL 16、现有 JdbcTemplate/TransactionTemplate、JDK Semaphore/虚拟线程；不引入队列中间件或新依赖。

## 全局约束与依据

- 唯一工程根 `G:/207/项目/算法重组工具/代码/agent-platform`；不得接触相邻旧工程。PG 是业务权威，内存集合只限本地槽与工作线程去重，不构成执行权。
- 原详设 v1.2 快照 SHA256 为 `3c72b2062beb2e8b377f2384843ea935a0074b50c0063e5106566bbd8dcd0c4f`；不修改冻结原文或另建契约。
- 对照册 31 F1/F6、§3.1/3.3/3.4、§4.5、§6.5 和 `docs/evidence/engine-admission-plan.md`。F1 原有 `PENDING` 即持久排队状态，V200 已允许该状态。
- 保留 run → node → call 锁顺序。所有网络、对象存储、工作槽等待均在 PG 行锁事务外；不得为排队持有 JDBC 连接。
- 保留 `TraceStore.commit` 的有效节点租约、owner、可提交状态与唯一提交条件，及 ToolCalls 的登记/发送栅栏。调度重试不生成新 callId、call_seq 或幂等键。
- 取消只停止本地继续推进和未来认领；不得宣称外部操作已经停止或副作用已撤销。
- 根任务已完成 monitor `PENDING`、`WAITING_EXTERNAL` 归入 pending 的独立修复及审核；本任务不编辑 monitor，不改变评分规则、数据集语义或输出结构。
- 当前输入校验与 ChatSessions 预约 P1 由其他作者修复。移交时以其最终冻结版本为基线，不能覆盖新校验，也不能将其恢复成旧预约顺序。

## 已确认的当前路径与改动风险

| 当前落点 | 具体观察 | 必须处理 |
| --- | --- | --- |
| `RunService.createInternal` | EVALUATION 在请求线程最多反复 drive 1000 次，其他触发直接 startVirtualThread；已有请求号的 EVALUATION 回读也会 drive | 删除生产入口的同步评测推进与直接线程启动，全部提交调度；历史已受理请求先回读，不受新增容量门禁影响 |
| `RunService.recover` | 每秒按 id 取前 100 个 RUNNING，再逐个启动虚拟线程 | 以 PG 公平领取替换固定头部扫描，不为未拿到槽的行创建线程 |
| `RunService.drive` | 本地仅 activeNodes 续租集合，没有 run 级去重；TraceStore.claim 才仲裁节点权利 | 保留节点 CAS；同一实例同 run 仅一个调度 worker，不能通过公开 drive 保留不受槽限制的生产旁路 |
| `ChatSessions.turn` | 首次 turn 预约持久化早于 createSessionTurn；输入校验 P1 正在原作者处调整 | 新轮次容量预约必须早于首个 chat_turn 写入；与 RunService 共用同一可移交 ticket，失败不留无 run 的新轮次 |
| `PlatformErrors` | PlatformException 的 ApiResponse.data 固定 null，无 Retry-After | engine 局部容量错误响应保留统一四字段，增加明确 retryAfterSeconds 与 HTTP Retry-After；不改全局错误约定 |
| `GatewayController.proxy` | 只转发 Content-Disposition、ETag | 增加 Retry-After 响应头的窄白名单与纵切测试，否则外部调用方收不到等待提示 |
| ApprovalService / ExecutionRecovery / resumeExternalFacts | 各自在短事务将等待节点恢复为 READY、运行恢复为 RUNNING | 同事务标记该 run 即刻可调度，不能直接起线程；事实校验/采纳仍走原路径 |
| EvaluationService.execute/resume | 已使用持久的 report/case 幂等请求号，并保留 RUNNING 报告供恢复 | 队列受理后返回 PENDING/RUNNING 不判失败；测试恢复反复提交仍只创建原 run |

## 最小边界与文件责任

本增量实现运行级公平调度机会、本地分池、准入与停机。节点并行、三类分布式许可和其余内存硬闸不在本增量中冒称完成。

| 文件 | 责任 |
| --- | --- |
| 新建 `engine-runtime/src/main/resources/db/migration/V215__run_scheduler.sql` | 只增加 run 调度字段及部分索引；迁移号须在移交时再次确认无人占用 |
| 新建 `engine-runtime/src/main/java/cn/agentplatform/engine/domain/RunDispatch.java` | 调度令牌不可变值：runId、owner、generation、until、队列类别；不作为节点 leaseOwner |
| 新建 `engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunScheduleRepository.java` | 公平领取、精确领取、续租、带 owner/代次释放、唤醒；短事务和最小列投影 |
| 新建 `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunScheduler.java` | 两个独立有界槽、预约移交、同 run 本地去重、虚拟线程生命周期和 drain |
| 新建 `engine-runtime/src/main/java/cn/agentplatform/engine/domain/RunAdmissionRejected.java` 与 `engine-runtime/src/main/java/cn/agentplatform/engine/api/RunAdmissionErrors.java` | 429 满载 / 503 停止受理；统一 ApiResponse、retryAfterSeconds 和 Retry-After |
| 修改 `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java` | 所有创建/恢复/续跑入口接入 scheduler；将真正驱动方法限制为持槽且持调度令牌的内部路径 |
| 修改 `engine-runtime/src/main/java/cn/agentplatform/engine/application/ChatSessions.java` | 新轮次写入前预约、既有预约/请求号优先回读与恢复、ticket 正确移交/释放 |
| 修改 `engine-runtime/src/main/java/cn/agentplatform/engine/application/ApprovalService.java` | 确认、拒绝及超时产生后续可推进工作时，同事务设置调度时间 |
| 修改 `engine-runtime/src/main/java/cn/agentplatform/engine/infra/ExecutionRecovery.java` | 已验证事实恢复 READY 时同事务唤醒，保留 W3 证明与节点认领规则 |
| 修改 `engine-runtime/src/main/resources/application.yml` | 显式正整数的交互/批量槽、poll、租约/续租、drain 上限；保持原流式 60 秒配置 |
| 修改 `platform-gateway/src/main/java/cn/agentplatform/gateway/api/GatewayController.java` | 仅透传 Retry-After，不放开任意响应头 |
| 新增 engine 的 `RunScheduleRepositoryPostgresTest`、`RunSchedulerTest`、`RunSchedulerPostgresTest`、`RunAdmissionHttpTest` | 真实 PG、公平性、并发/取消/停机及真实 MVC 安全过滤链测试 |
| 扩充现有 `ChatSessionsTest`、`ControlledDraftSkillTest`、`ControlledReplayTest`、`ExecutionRecoveryTest`；新增 gateway 定向 HTTP 测试 | 保留各已审路径，并断言无调度旁路、无孤立预约与响应头丢失 |

不需要改 admin-common、model-gateway、skill-registry、原契约或前端协议。前端现有 terminal 判定为三个终态，PENDING 应继续保持忙碌；后续真实浏览器检查确认这一兼容性，不因此改写 observer/Chat/SDK。

## 数据与内部接口建议

V215 最小字段：`dispatch_next_at timestamptz NOT NULL DEFAULT now()`、`dispatch_owner varchar(64)`、`dispatch_until timestamptz`、`dispatch_generation bigint NOT NULL DEFAULT 0`，非负代次、owner/until 成对。队列类别从权威 `trigger_type` 推导：EVALUATION=BATCH，CHAT/TASK/DEBUG=INTERACTIVE；不信任请求中的 pool 参数，也不增设可能失配的队列类别列。

按两类触发创建含 `dispatch_next_at,id` 的非终态部分索引。迁移旧行只补字段；不得重置旧节点租约、attempt、调用身份、输出或事件。已有 RUNNING 行按就绪条件参与恢复，新建行使用原设计 PENDING。保留现有 started_at 的统计与总耗时口径，本增量不偷偷重定义排队时间或预算起点。

Repository 内部方法范围固定为：`claimNext(queue, owner, leaseDuration)`、`claimSpecific(runId, queue, owner, leaseDuration)` 返回可空 RunDispatch；`renew(dispatch, leaseDuration)`、`release(dispatch, nextDelay)` 返回 CAS 是否成功；`wake(runId)` 仅调整非终态 run 的下次调度时间。所有身份/队列类别最终从 PG 行校验。

领取事务只选一个已到调度时间、调度租约为空/过期的 PENDING/RUNNING 行，按 `dispatch_next_at,id` 排序，以 `FOR UPDATE OF r SKIP LOCKED` 加锁，写 owner/until、递增 generation 后返回最小列。候选须存在 READY 节点或租约确已过期的 RUNNING 节点；有有效执行租约且没有其他 READY 工作的前 100 个运行不应阻挡后续行。READY 是否真能推进仍由原 FlowDriver.prepare/聚合规则判定，不能在队列 SQL 中另写一套 ALL/ANY/错误边解释器。

调度领取不创建节点 attempt。第一次节点 claim 仍由 TraceStore 执行，并按原规则创建 attempt/预算/事件。新建 PENDING 行初始事件水位可为 0；首次调度使其变为 RUNNING 时，在同一事务写既有 RUN_STARTED，一次且仅一次。不要创造 RUN_CREATED 之类新客户端事件枚举，也不要重复发送 RUN_STARTED。

完成一轮后按 owner+generation 释放，`dispatch_next_at` 移到当前时间加有界轮转间隔；不能总回到最小 id 或保持上次已过期的时间。等待态无工作时不续占槽，醒来仍可通过 PG 重新认领。唤醒与 finally 释放竞争至多带来一个配置轮转间隔的延迟，不能把已恢复工作永久放到将来，也不能丢弃 READY 状态。

**两个不同租约必须区分：** dispatch lease 只负责跨副本的驱动机会，node lease 才允许外调及逻辑提交。调度 owner 变化不能伪装成原节点已过期，不能作废仍可用于对账的外部事实。调度续租失败后停止发起新节点；在途调用的事实保存及原节点 CAS 仍按已审核规则裁决。旧节点 generation 的直接逻辑提交拒绝要复用并测试既有 TraceStore.commit，不另造一个事实提交者。

## 持槽、准入及所有入口

1. scheduler 每次先 `tryAcquire` 对应队列槽，拿不到就不认领、不取全快照、不创建虚拟线程。数据库无候选、领取失败、线程启动失败均归还。不得先创建线程再在里面阻塞等待槽，也不使用无界 Executor 队列。
2. 本地 activeRun 只记录已持槽 worker。worker 真正结束的 finally 才释放对应槽；取消、超时或 drain 标记不能提前释放仍阻塞的线程所占槽。预约移交须用显式状态 CAS，producer 的 finally 不能释放已经移交给 worker 的槽；重复 close 不超发许可。
3. 新交互请求在已有 requestId 读取和语义冲突检查后、首次业务持久化及任何付费发送前预约槽。准备快照/输入验证/依赖验证失败则释放，无 run/attempt/model invocation/tool submit。满载 429，停止受理 503；retryAfterSeconds 为正且有界，Retry-After 以秒返回，不回显 owner 或内部容量细节。
4. 新交互不得持续抢空闲槽使旧的已受理 READY 工作饥饿。准入获得槽后先检查/领取该池更早的持久就绪工作；若存在，将槽交给旧工作并拒绝本次未持久化的新请求。没有积压才将预约交给本次创建。跨副本 SKIP LOCKED 提供可取得工作而非严格全局 FIFO，验收表述为可重复证明无固定头部饥饿，不能宣称绝对公平。
5. requestId 已受理回读位于新门禁前。同请求号与首次提交并发时，容量拒绝分支应使用与创建相同的请求号仲裁短事务再查一次：已存在则回读原 run，未受理才 429。不能在持该锁时拉快照或等待槽。冲突输入仍 409，不能以容量错误掩盖幂等冲突。
6. EVALUATION 不要求立即拿到本地槽；校验并冻结原候选后写 PENDING，快速返回原状态结构，由批量池随后处理。重复 report/case 只回读原 run。报告恢复继续消费原候选/原数据集，不临时改成后续草稿。
7. ChatSessions 新轮次先通过原作者修复后的本地输入校验和 requestId 既有分支，再在首次 chat_turn 写入前拿预约；同一预约传到 RunService，不能二次取槽。旧有 chat_turn 已受理而 run_public_id 尚空的崩溃窗口须按 PG 归属恢复为 PENDING，不因新容量门禁丢弃既有工作；恢复资格由服务端已验证预约决定，禁止接受请求体的 bypass/accepted 标记。
8. DEBUG/FULL/STEP、TASK 转 CHAT、session turn、EVALUATION、reexecuteFrozen、reproduceFrozen、controlled draft test 都经过同一调度。纯日志回放不执行。人工确认/STEP resume/外部事实恢复是已受理工作的重新排队，不为容量不足丢掉用户确认或重新发送写技能。
9. 为避免 Spring 依赖环，RunService 依赖 scheduler，scheduler 通过本次提交/扫描传入的驱动回调执行工作，scheduler 不反向构造 RunService。现有四参数测试构造需要明确改为测试注入可控 scheduler；不能留“测试同步评测”生产开关来绕过调度。

本增量中的批量持久队列指 PG 保留待处理行和有界领取投影，不承诺整个磁盘队列的行数已受配额限制。报表一次现有 1–200 条用例上限保留；跨报表队列容量、按租户配额/权重及三类资源分布式许可另验收。

## 优雅停机与恢复边界

- 开始 stop 时原子置 draining，停止新准入和新领取。已拿到但尚未交付 worker 的预约不得继续创建运行；已持久化的批量/交互待处理行留在 PG。
- 活跃 worker 在 drain 上限内继续原节点租约与 dispatch 续租；HTTP、SSE 观察生命周期保持独立，断线不触发取消。
- 工作提前结束正常释放。达到 drain 上限后停止续租，不把 dispatch/node 租约强行改成过期；其他副本只能等真实租约过期后接管。旧线程若仍在执行不应提前归还本地槽，旧 owner 的 finally 不得释放新 generation 的 PG 调度租约。
- 生命周期必须确保停止领取早于 drain，且续租设施在 drain 结束前仍可运行。实施时用可控制时钟/屏障验证回调顺序，不能依赖未经核实的 Spring 默认销毁顺序。Spring SmartLifecycle 的异步 stop 回调在完成或 drain 到限后调用一次。
- 进程被 kill 的持久排队及过期调度机会能由另一实例发现；在途已 SUBMITTED 调用只对账，不凭恢复重发。保留原 UNKNOWN/CALLBACK 与已登记未提交/事实已落未采纳的三个恢复窗口。

## 红测矩阵与分项执行

所有新行为先写失败测试并记录真实失败，再实现最小改动。失败应是目标断言，不用编译错误或环境故障充作行为红测。数据库测试使用本工程专用测试库，不能清理开发运行库。

| 组 | 必须先证明失败的输入 | 通过标准 |
| --- | --- | --- |
| R1 公平尾部 | 前 100 个 run 有未过期节点 lease；第 101、150、201 为 READY | 后续在有限 tick 内各取得机会；前 100 不新增 attempt/外调；查询不取全 snapshot/inputs |
| R2 轮转 | 200 个持续可推进运行、有限槽、反复 tick | 所有初始候选均被看见；再次领取更新时间，不能永远重扫最小 id |
| R3 跨副本领取 | 两个独立 repository、独立连接/owner，同时 claim；另一事务锁队首 | 同 generation 只有一个持有者，锁行被跳过；未锁尾部可推进；不在等待锁期间生成 worker |
| R4 代次 | A dispatch 过期，B 接管，再让 A renew/release/完成 | A 续租/释放 CAS 为 false，不清 B 租约；旧 node generation commit 拒绝且成功事件仍唯一 |
| R5 本地去重 | 同 run 重复 recover 100 次，worker 用 latch 阻塞 | active worker 与真实线程启动均为 1；finally 后可再次调度；不是只靠节点 claim 返回 null 掩盖线程增长 |
| R6 分池 | 交互槽全部阻塞，再提交批量；反向重做 | 两类各自可工作，互不借用或耗尽另一池槽 |
| R7 新交互拒绝 | 满载时分别提交 DEBUG、FORMAL、CHAT turn、reexecute、controlled draft | 429+正 retryAfter；零新 run/attempt/chat_turn/model invocation/外部 submit；只读快照 HTTP与付费外调分别计数 |
| R8 既有请求号 | 已受理请求遇满载、draining、重复 EVALUATION；并发首次创建与重复请求 | 同 requestId 回原 run；冲突仍 409；不重跑原评测，不新增调用 |
| R9 预约回收 | 输入无效/版本失败/PG插入失败/线程启动失败/异常，以及 ticket 双 close/移交后 producer close | 槽准确回到原值，无超发；持久状态与错误一致，无无 run 新预约 |
| R10 批量队列 | 批量槽满载仍提交已验证 case；重建 scheduler 再消费 | PG 为 PENDING，返回快、无付费调用；重启后原 run 成功；monitor 保持 RUNNING 后可恢复至真实判分 |
| R11 等待与取消 | PENDING 取消；STEP/APPROVAL/EXTERNAL 等待并重复扫描；确认/外部事实与释放竞争 | 取消后零外调；等待不占槽；恢复按原暂停序号/参数/事实证明生效，无永远丢失唤醒 |
| R12 工作线程生命周期 | cancel/drain 时 worker 仍阻塞；解除后抛异常/正常退出 | 解除前槽仍占用，真正退出后 finally 释放；没有早放许可掩盖阻塞线程 |
| R13 停机交接 | stop 与新预约/claim/续租并发；drain 内完成；超过上限；另一 owner 接管 | stop 后不新增认领；上限内保留续租；到限不伪造过期；旧 owner 不退新槽，旧节点提交失败 |
| R14 HTTP 兼容 | engine 局部拒绝经真实 MVC/JWT，再经 gateway | ApiResponse 四字段、429/503 与 Retry-After 均保留；跨租户/主体不因排队泄露 run；无任意响应头透传 |
| R15 入口旁路 | FULL/STEP、session、eval、reexecute、reproduce、controlled draft、resume、确认、W3/outbox | 全部可追溯到相同调度门禁；CONTROLLED 仍零外调，写技能既有 call/key 不变 |
| R16 原语义 | output retry/usage、历史授权、ANY/ALL/错误边、CALLBACK/UNKNOWN、发布评测绑定、SSE续流 | 已审核失败路径不退化，不用删除既有测试换取通过 |

### 实施项 A：持久调度仓储

- [ ] 根任务确认输入校验/会话预约修复已经冻结、审核、部署，并移交源码/target；重新确认 V215 空闲。
- [ ] 新增 R1/R2/R3/R4 的真实 PG 失败断言；只运行自己新增测试，记录原实现的明确失败。
- [ ] 实现 V215 与 repository，索引/领取/释放均保留最小字段及短事务；验证旧库升级与全新测试库迁移。
- [ ] 核对两个 repository 的真实并发结果及唯一逻辑事件，再冻结相关文件摘要供独立审查。

### 实施项 B：分池与生命周期

- [ ] 新增 R5/R6/R9/R12/R13，使用真实 worker+latch，不用线程数配置值冒充活跃上限。
- [ ] 实现 RunScheduler、可幂等移交 ticket、drain；统一处理空领取、异常、取消、启动失败的回收。
- [ ] 对停止顺序和旧 owner 释放竞争作定向复核；本地工作槽不写成分布式外调许可。

### 实施项 C：入口与兼容纵切

- [ ] 新增 R7/R8/R10/R11/R14/R15；包含 ChatSessions 首次预约前拒绝、已有空 run 预约的崩溃恢复。
- [ ] 将 RunService 创建/历史回读/recover/drive 全部接入，移除直接线程启动和同步评测循环，PENDING 首次启动事件同事务。
- [ ] 协调 ChatSessions、ApprovalService、ExecutionRecovery、外部事实就绪及网关响应头；不修改 root 已修好的 monitor 评分/状态分类。
- [ ] 定向检查原评测测试；需要等异步终态时轮询原 run/status，不在产品中恢复测试用同步捷径。
- [ ] 执行真实两个 engine 进程纵切：公平尾部、池隔离、停止一副本、过期接管、写调用计数与逻辑提交唯一；仅由根任务启停本工程已验证身份的进程。

### 实施项 D：回归、审核与台账

- [ ] 获得 engine target 独占后，从本工程 `.env` 仅加载进程变量并选择 Java 21，执行 `mvn.cmd -B -ntp -pl engine-runtime test -Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2 -Djdk.net.unixdomain.tmpdir=V:/.local/tmp`，不带 `-am`；专用 PG 测试开关及库使用既有测试脚本约定。
- [ ] 网关定向/全模块测试在其 target 独占时单独执行；`node scripts/verify-contracts.mjs` 确认原契约未变。根任务统一做最后跨模块回归，不能把各次模块总数相加冒称一次整轮通过。
- [ ] 根任务按最终摘要组织独立规格/安全质量审查；修复审核问题后再部署，不让作者自测替代独立审核。
- [ ] 写实际红绿日志/测试计数/两进程证据及未覆盖边界，更新设计追溯与实施台账。配置上限、少量 fixture、一次成功均不代表 500 运行/5000 SSE/200×5MiB 容量通过。

## 已读参考与明确保留项

只读调查还核对了本工程 ToolCalls、ExecutionRecovery、TraceStore 的租约/锁顺序，及 `review-engine-streaming.md`、`review-skill-async-http.md`、`review-skill-callback.md`。观察连接、旧 UNKNOWN 投递与 CALLBACK/W3 竞争不应被本次调度重排改变。

本轮实际查阅 [PostgreSQL 16 SELECT 官方文档](https://www.postgresql.org/docs/16/sql-select.html) 的锁定子句，SKIP LOCKED 适用于队列消费者，但不提供严格全局公平保证；查阅 [Java 21 Semaphore 官方文档](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Semaphore.html) 的许可取得/归还语义，以及 [Spring 生命周期官方文档](https://docs.spring.io/spring-framework/reference/core/beans/factory-nature.html)。这只是文档级机制对照，未声称读取本轮调度对应的 JDK/Spring/PG 完整实现源码。源码对照记录待真正实施时按实际读取范围补充。

保留后续：模型/技能/装配三类分布式许可、舱壁连接池、全局/租户队列配额与权重、完整 FlowDriver 分支并行、其余内存硬闸和固定资源容量试验。该增量通过后也只能关闭 G5 的持久公平调度及本地分池子项，不能关闭整个 G5/T05/T33 或生产 IAM 发布门禁。

本文件没有实施结果；未运行任何 Maven、数据库写探针、业务请求或服务启停。调查时其他协作者仍在修改输入入口，本文列出的源码位置是责任定位，正式实施前必须按根任务最终冻结内容重新核对，不能用调查时版本冒充实施基线。

## root 接续协调补充

调度改为异步后，Admin当前评测操作只显示一次POST返回的JSON，没有持续读取/恢复最终判分。后端API结构兼容不等于完整页面流程已可用。调度后端冻结后另行移交前端消费者：同一已受理报告显示排队/运行/等待确认/完成，使用原报告id及原请求幂等恢复，不重新创建报告或替换候选；退出页面中止观察，不取消业务；重进可显式恢复，鉴权或确定错误停止自动请求。正常完成不要求用户反复手工点恢复或填写报告ID。该前端工作安排在命名输入真实页面链路验收之后，避免共改App.vue；保留当前83测试与输入集8源码冻结作为历史基线。它属于调度增量的必要纵切，不能以“无需前端协议变化”省略。

主报告同时提供清晰状态、通过/失败用例与安全用例结果，原完整JSON移到渐进展开的技术详情；不要求用户从原始JSON里查状态、手抄报告ID或候选摘要。保持现有完整JSON精确判分事实和独立expected，不在UI自动把实际运行结果写成预期；其他质量判分口径按原设计单独验收。

本地分池不替代分布式模型/技能/组装许可；交互快速拒绝须发生在新run和新chat_turn任何写入之前，并在两端真实HTTP验证Retry-After。原共有入口与会话固定Schema校验、旧requestId优先读取均须保留，不能在调度改造中退回旧行为。
