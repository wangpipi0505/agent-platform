# N2：具体事务组合接缝提案

2026-10-20；只读设计准备，交 root 裁决，不是 N1/N2 签收。唯一工程为 `G:/207/项目/算法重组工具/代码/agent-platform`。依据 N1 待独立双审的最终 source362（报告中的 source-after.json SHA：`bbc0491672b771781405173869706a2d741673dd96b76d9b919666560a2aa51c`），已读已接受提案 §4、N1 root-decisions/report 及下列具体调用段。N1 若返修相关 API，正式 N2 前须再做窄复核。未重跑既有 69/0，未运行测试/Maven/数据库/进程，未改 source/target。

成文期间 root 通知 N1 已独立 Spec compliant / Task Quality Approved，无 Critical/Important、API 未返修，源码仍 362/target1740；review SHA `b9576db7964c02bf1d78ccfe2354baa979583cd2a9a7660abcff02e2221b0904`。本代理未重读双审，也不代 root 重复签收；本提案读到的接口基线不因此变化。

## 1. 推荐裁决与真实接口限制

推荐**受保护组合事务由 TraceStore 的固定用途方法拥有**，原 FlowDriver/RunService 保留应用编排，原纯决策最小提取到既有 `domain.GraphRules`。GraphRules 只接数据并返回有限纯值结果；TraceStore 不依赖 application，不接 Supplier/SQL/table/URL/任意业务回调，不新增 driver、receipt 或权限 ThreadLocal。

需要 root 在 N2 允许范围中加 **这一个既有文件 `engine-runtime/src/main/java/cn/agentplatform/engine/domain/GraphRules.java`**。现文件已有 join/exclusive/DAG 纯方法；新增内容仅是原 FlowDriver.prepare 的入边转换/ANY选择，以及原 RunService.aggregate 的状态和输出选择。不是把整套流程算法搬进 infra。MaterialScopeStore 的包内 API 不必公开。

源码硬约束：

- `TraceStore:503–548` 的 WriteView/writable 是 private，依次锁 run→authority observation→按 id 排序的 node，再核全部 metadata/scope，最后 readValues 读取 payload。`594–620` 的 append/appendEvent 也是 private。
- `TraceStore:550–590` protectedAtomic 拒绝任何 ambient：RC 报 RR_REQUIRED，RR/Serializable 也报 TOP_LEVEL_REQUIRED。`679/787/841/884` 的 claim/commit/fail/consume 均用它；不能在旧 store.atomic 内逐个调用。
- `MaterialScopeStore:233–250` 要求完整原委托绑定、无持久 block、last_decision_id 为本 proof；`410–496` append 从数据库重读父 scope，继承 initial floor/父 union/current obligations；公开 record 自报父关系不成立。
- `FlowDriver:41–148` 的准备读在事务外、写用局部 atomic；`RunService:1226–1311` aggregate 反而在其外层 atomic 内再调用 prepare。两者不能靠 proof 参数解决同快照与真实提交问题。

两个短方案供裁决：

| 方案 | 必要变化 | 判断 |
| --- | --- | --- |
| A，推荐 | GraphRules 增加有限纯决策；TraceStore 固定方法包住原组合 SQL、scope 和真实提交；application 调用固定方法 | 不放宽 N1 顶层规则；可共用 private 写原语；分层清楚，接口面小 |
| B，不推荐 | FlowDriver/RunService 各自拥有顶层 RR；新增公开、仅供这些具体组合使用的 joined read/write/scoping 方法；每个外层自行终检 | 必须明确例外于 N1「protected 公共 writer 仅顶层」裁决，公开更多材料/组合入口且难约束调用者；仍不得有通用回调或把 joined execute 当 commit |

若不允许 GraphRules 的最小范围增加，也不允许方案 B 的明确例外，则现限制下没有合规的直接嵌套接法；应由 root 裁决，不以反向 application 依赖或通用事务回调规避。

## 2. 固定用途接口与事务拥有者

下表为拟议方法名，非已经存在的 API。`d` 始终是原 RunDispatch，`p` 始终是 RunAuthority.currentRetained 返回的同一个 RetainedAuthorization。应用层不得先开启 store.atomic 再调用这些受保护方法；legacy 路径仍走原实现。

| 现调用 → 拟接缝 | 真正事务拥有者 | 读/写与返回边界 |
| --- | --- | --- |
| repository.claimNext/claimSpecific → 共用 claim 的 protected 分支 | RunScheduleRepository 原短 run-row Tx | 只读队列、authority/grant/block/backoff、node id/status/lease 等控制 metadata；只写 dispatch owner/generation/until。禁止 status=RUNNING、RUN_STARTED、payload |
| driveRecovered / drive(long,Actor) → 同一 owned continuation | 无外层业务 Tx | 先窄分 protected/legacy，取得本 run binding.root；调用 `currentRetained(rid,new ServiceCall("engine","run.advance",root))`。protected 不读 guard_ctx 作权限，不复用请求线程 proof |
| PENDING owned run → `TraceStore.startOwnedFlow(d,p)` | TraceStore 顶层 RR | run→authority→node；完整绑定/current-CAS/dispatch 检查后授权并读材料，防御性确认 pure FLOW。PENDING→RUNNING、run-state、RUN_STARTED 及其 scope 同提交；已 RUNNING 不重复事件 |
| FlowDriver.prepare + store.claim → `TraceStore.prepareAndClaimFlow(nodeId,nodeOwner,d,p)` | TraceStore 一个顶层 RR | 在同一授权快照内调用 GraphRules 纯准备决策，写准备副作用，完成原 claim/预算/attempt/NODE_STARTED；返回 NoClaim、BudgetExhausted(code) 或已物化的运行/节点/执行 scope 数据 |
| FlowDriver.execute → 使用上一步物化数据的现 execute 重载 | 无 PG Tx/网络授权刷新 | 原 START/END/CODE/CONDITION 运算和输出映射；不再内部调用 raw scope(run)/store.nodes。同 p 在 execute 前后核期限，d 在执行前/提交时再核 |
| store.commit / DEFAULT_VALUE → `commitFlow(...,d,p)` / `commitDefaultFlow(...,error,defaultOutput,d,p)` | 各为 TraceStore 顶层 RR | 复用私有具体 CAS 实现；完整 authority/dispatch、node lease/attempt 关系检查。default 的 node.error 与成功 output、attempt、事件/scopes 一个提交 |
| store.fail → `failFlow(...,d,p)` | TraceStore 顶层 RR | 保留原 node CAS0 时实际 attempt failure、无 NODE_FAILED 的语义；dispatch 已失效是退出该动作，不借它写新 generation 的失败事实 |
| RunService.stopForBudget → `stopFlowForBudget(d,p,reason)` | TraceStore 顶层 RR | 仅真实预算业务原因：run.error/state、实际 CANCELLED nodes 的 state scopes、RUN_FINISHED 同提交；不得接收权限异常或 GUARD_SNAPSHOT_INVALID 冒充预算 |
| RunService.aggregate → 事务外新 current，然后 `TraceStore.aggregateFlow(d,p)` | TraceStore 一个顶层 RR | 全部 prepare 副作用、重新物化节点状态、最终 run state/output、RUN_FINISHED/scopes 同提交；RunService 不再持有 protected 外层 atomic |

所有上述 TraceStore 顶层方法复用原 protectedAtomic：callback 内末检查；实际 protectedTx.execute 返回后同 p.requireVerified；失败 rollback-only，提交后到期不虚称回滚已提交事实。FlowDriver/RunService 不再宣称拥有这一提交。原 N1 public writer 可通过私有同事务实现复用，绝不从新组合方法调用 public 顶层 writer。N2 实际 worker 只能使用带 d 的这些接缝；旧 proof-only API 不是 N2 的 dispatch 入口。

原 RunScheduler.current 是已有 dispatch ThreadLocal；可加一个窄 `ownedDispatch(rid)` 取出已有值，立即显式传 d，不保存 proof/角色/材料，不新增 ThreadLocal。`mayAdvance` 保持仅 RUNNING；startOwnedFlow 单独允许 d 对应的 PENDING/RUNNING。

## 3. 同快照材料、准备、claim 与执行

具体 locked view 在 TraceStore 内部复用并小幅拆分 writable：先锁 run，锁 authority 并核 binding/current observation；锁 node 按 id 排序；枚举 scope/真实 FK、核完整性与覆盖；最后才读 payload。把 dispatch owner/generation/live lease 的窄检查放在 payload 之前，写 CAS 内再带 dispatch fence。锁等待后的实际存活检查不能只拿 PG 事务开始时的 now() 当实时；新增 fence 使用实际时点（例如 clock_timestamp），并保留原 node owner、lease、status、committed_attempt_id 条件。authority recordCheck 仍仅锁 authority，不反向锁 run/node。

新方法可沿 N1 保守读取完整 currentScopes/readValues，无需首段优化投影。父 union 因而覆盖**实际读取的所有材料**，不能只记录图上前驱。run inputs、snapshot、所有 node outputs/errors 均来自这个已授权快照。控制 metadata SELECT 不得顺带 select *、guard_ctx、snapshot、inputs、definition、output/error。

GraphRules 的具体纯准备输入：snapshot.definition、目标节点、按 step_seq 的节点列表；内部按原 edge 顺序查前驱。具体输出只含 WAIT/READY/FAILED/SKIPPED、可选 winner id、独占前驱的 abandon ids。保留原规则：

- CONDITION 的 terminal predecessor 根据 selected 转为 SKIPPED；ERROR edge/onFail=ERROR_EDGE 的状态转换和普通失败边转换顺序不变；ALL/ANY 与 skipCountsAs 继续调用原 join。
- ANY winner 仍按原入边列表选择实际 status=SUCCEEDED 的第一个前驱；不能擅自改成“转换后逻辑 SUCCEEDED”。winner 只写一次；只放弃独占直接前驱、且原状态 READY/RUNNING，保留 shared consumer。
- 每个实际 changed row 都 append node-state。FAILED/SKIPPED 沿原事件；ANY 的 winner 与 ABANDONED 原来没有事件，仍无须新增事件，但不得漏 scopes。零行 UPDATE 不造该行的新事实 scope。

每次准备写完，TraceStore 必须把**本事务刚写的状态与新 scope**重新物化到内部 view，后续准备/claim/聚合读新值；原 WriteView.values 不会因 SQL 自动更新。private append 保留历史已消费父集合，新材料加入集合，后续事件用真实新父。不能把第一次读取的 READY map 一直当当前事实。

prepareAndClaimFlow 不是粗暴把旧 prepare 与会抛预算异常的 claim 合并：为保留「准备先完成、claim 预算失败不会撤销已完成准备」语义，在任何 node RUNNING/attempt/预算写之前，基于锁内预算与 attempt_no 做具体预算预检。失败返回 BudgetExhausted(code)，仅提交原 prepare 副作用；末检查与真实提交终检仍执行。随后 RunService 用同 p 调 stopFlowForBudget。无 claim 写、无 attempt、无预算消耗；若 p 已失效，则 stop 不写，下次新动作重新判预算。真正 claim 的意外 SQL/材料异常则整个本次事务回滚，不能吞成预算结果。预算检查代码只私有重用现 consume 的 steps/duration 规则，不加可插拔框架。

实际 claim 保留 maxParallel、RUNNING live node 不重领、过期 node generation、attempt_no、首次 steps 消耗、默认 request/checkpoint/error scopes、NODE_STARTED。返回的运行数据/节点/执行 scope 只是普通已物化数据，不具有授权效力；execute/commit 都仍必须显式拿 p 和 d。

**跨 execute 的父证明不需要新增 receipt**：claim 的真实 attempt 初始 scopes 已以本次读到的全部执行输入 scopes 为父持久化。execute 只读该批已物化数据，不再次查数据库。commit 在锁内重新授权当前材料，并纳入该 attempt 的真实 request/state/checkpoint/error scopes；这些真实父的传递并集保留执行时消费的 requirements，即使别的节点在执行期间改变输出也不会丢父。MaterialScopeStore.append 继续从库核父身份/内容；不得从调用者传一张“已授权 scopes”票据替代。

## 4. 提交、失败、预算与聚合的具体写集

commitDefaultFlow 与 commitFlow 共用私有具体实现。默认分支把 error 字段加入**同一个受 node/dispatch fence 约束的成功 UPDATE**，CAS 成功才 append node-error；然后原 node-output/state、attempt SUCCEEDED、LOGIC_RESULT_COMMITTED、NODE_FINISHED 同事务。保留原默认分支 attempt 为成功的事实，不凭空伪造 attempt failure。CAS0 只保留原 SUPERSEDED_SUBMIT note，不写 default error、成功 scope 或成功事件。

有效 d 下仅 node generation/lease/CAS 失败：普通 commit 返回 false+原 note；fail 仍可写该真实 attempt 的失败 state/error，node CAS0 则无 node scope/NODE_FAILED。**d 本身失效**：prepare/start/claim/aggregate/budget 不推进；提交按 superseded false 处理、不得成功写。若保留 commit 的 note，须先核该 attempt/node/run 的窄 FK 及有效本地 proof/authority；不凭过期 proof 写 note。失效 d 的 fail 直接退出，不能把旧执行结果转换成当前 generation 的失败。权限错误先于这些普通结果处理。

stopFlowForBudget 重新核 d/p 和原 terminal guard；固定写 run FAILED/stop_reason/error/ended_at；逐个记录实际被 CANCELLED 的 node-state；最后 RUN_FINISHED 及 run-state/scopes。任一点异常全回滚。不得以权限失败、数据库 serialization conflict 或短票过期触发它。

aggregateFlow 在一个 RR 内执行：授权物化 → 按原节点顺序对 READY 节点计算并落 prepare → 刷新节点材料 → GraphRules 纯聚合选择 → 固定 run UPDATE → terminal 时原 RUN_FINISHED。状态计算保留 ERROR_EDGE 对 FAILED 的豁免，非终态按 runnable、WAITING_STEP、WAITING_EXTERNAL、WAITING_APPROVAL 的原顺序；N2 pure gate 下等待外部/审批路径不应成为可执行业务。

输出仍按 step_seq 取首个 SQL output 非 NULL 的 END，否则最后节点 output。必须沿用 N1 Payload.sqlNull，不能把 SQL NULL 和显式 JSON null 都当 JsonNode.null 后改变 fallback；run-output 的 digest 与业务展示分别保持原语义。最终 run-state/run-output scopes 继承本事务已消费的定义/状态/输出及 prepare 新 scopes，不能只继承最后选中的输出。terminal 早返回不重复 RUN_FINISHED。最后实际 commit 后的同 proof 检查归 TraceStore.aggregateFlow。

## 5. 队列活性与权限失败分类

protected claim 的 eligibility 使用窄 authority side metadata：必须存在 authority，grant_expires_at 在未来；无 block，或只有 AUTHORITY_UNAVAILABLE 且 retry_not_before 已到。403/原 grant expired 不领；503 到期后一次 worker 有限重核。claimSpecific 同样检查退避，不能绕过。verified IAM 模式中缺 authority 的旧 run 不因缺行自动降级 legacy；原 legacy 部署队列和原业务实现保留。

另须仅对 protected 增加一个候选：**run.status=RUNNING、nodes 非空且全部 terminal**。它只派发 aggregate-only，取得新 current 后聚合，不执行/重 claim 节点、不外部等待。理由：最后 END 或最后一次 prepare 已提交，紧接着 post-commit proof 过期会阻止本轮 aggregate；原 claim SQL 仅认 READY/过期 RUNNING node（RunScheduleRepository:47–53），否则这个 run 永远悬挂。legacy 候选不扩大。

`RunAuthorityStore:101–124` 实际 recordCheck 以完整 Binding equals 和 check_version CAS 写当前观察；只允许 DENIED/EXPIRED/UNAVAILABLE，UNAVAILABLE 才可到期重核；没有通用“短 proof 过期”block。`RunAuthority:28–44` 现 catch(PlatformException) 同时包住网络、recordCheck 和本地最终检查，可能把本地短 proof 到期的 403 归 DENIED；成功 recordCheck 后原 binding 已过期，随后错误记录通常 CAS0，也不能把这个偶然结果当正确分类。

最小处理不新增 Store action receipt/API：

1. currentRetained 的网络 resume/authorize 真实拒绝仍由原 recordCheck(binding,null,reason,backoff) 记录，CAS0 不覆盖后来的观察。原 grant 已真实到期仍 EXPIRED。保留成功 CAS 内锁等待后及其真实 commit 返回后检查。
2. 把本地 proof 校验、recordCheck 成功提交后校验与网络错误分类分开。仅本次 ticket/operation/local proof 到期，**不得持久写 DENIED/EXPIRED 去永久封死有效 grant**；中止本动作、有限 dispatch 退避，下次新动作重新 current。已提交事实保留，不执行 fallback/fail/aggregate。
3. writer 的材料覆盖拒绝、旧 decision、已有 durable block、元数据完整性失败与 serialization conflict 都在业务 catch 之前退出；不把任意本地 403 当线上新拒绝，更不拿一个新 load 的 Binding 盲写 block 覆盖新 decision。既有 durable block 保持；没有可安全归属的在线拒绝时只退避并保留错误分类。后续真实 current 403/503 才走原持久 CAS。
4. 在原 RunScheduler 加固定 `deferOwned(rid,delay)`：通过已有 d 调 repository.release(d,有界delay)，随后立即退出动作；finally 的 finish 第二次 release 因 owner 已清 CAS0，不把退避缩回 poll，slot 仍在 finally 释放。不增加新权限状态或第二 worker。队列 retry_not_before 和 dispatch_next_at 两道条件都保留；无紧循环。

current 真实403/503、proof等锁到期/提交后到期、完整性/dispatch/serialization 异常必须先于 RunService:966 起的普通异常分流。否则原 catch 会把权限错误变成 RUNTIME_ERROR、DEFAULT_VALUE、NODE_FAILED 或 RUN_FINISHED。异常类型/明确阶段用于分类，不能仅靠 message 前缀混认。

## 6. 边界与正式 brief 的有限验证建议

RunService 构造器每 poll 绑定 resumeExternalFacts；protected 分支必须在该入口的候选 SQL/进入事务前按 authority metadata 排除，不能靠 worker 的 pure switch 阻止周期路径。原 renewLeases 只续活控制 lease，按 owner/live lease 条件保留；pure 节点无 tool rows，不把这条续约算作材料事实更新。独立 ExecutionRecovery 还有外发，本提案未迁移/验证它，不能宣称整个后台恢复域或 Task5 关闭；正式 N2 fixture 必须无外部恢复候选，global IAM 保持关闭。

生产范围仍固定 common+六业务 HTTP 边界，无 POM、四契约、框架、新迁移、真实 IAM；N2 不开放 CONFIRM/AGENT/LLM/SKILL/RETRIEVE、debug/step、checkpoint/response 或新入口。snapshot 后的 pure 防御检查在任何实际节点 execute 前完成，protected drive 不走原 guard_ctx/AgentDriver 分支。

建议正式 N2 以原 reserve/created/poll→owned worker 验证，非本轮执行：

- current403/503 时 dispatch 可占用但 RUN_STARTED、payload、attempt 均零；503 到期前 claimSpecific/claimNext 均不空转，slot 最终归还。
- ALL/ANY/CONDITION/ERROR_EDGE 真实准备状态、winner/ABANDONED 无事件分支的 scopes 与跨步骤父 union；共享前驱不被放弃。
- prepare+claim 预算耗尽时保留准备副作用而没有 attempt；随后预算停止多行写/scopes 的故障原子性。
- CODE DEFAULT_VALUE 成功及每个写点故障；node CAS0、失效 dispatch 均无 error/output/成功 scope 越权写；有效 dispatch 的 fail CAS0 保留真实 attempt failure。
- claim 后其它节点改变输出，execute 使用原物化 scope，commit 父仍含原消费 requirements；执行期间不 raw select *。
- 聚合中的多次 prepare 看本事务新值；SQL NULL/JSON null 的 END fallback、ERROR_EDGE 失败豁免及 RUN_FINISHED 恰一次。
- 最后 END 已提交而本次 proof 随后到期：无业务降级、有效 grant 未永久 block；aggregate-only 再领取、一次新 current 后终结且不重执行 END。
- run→authority→node 等锁/真实提交后 proof 到期、持久 denial CAS 竞态、旧 dispatch generation、有限 serialization failure；legacy 原行为对照。

root 待裁决点只有：批准方案 A 与既有 GraphRules 的窄范围增加；确认上述 budget-precheck 结果保持旧准备副作用；明确将短 proof 到期退避和 aggregate-only 补偿纳入 N2。无外部 IAM 前置阻断。本提案归还 root，作者不占有 source/target 写权。
