# 运行时、技能及公共安全任务审查

审查日期：2026-10-20。结论：**Request changes**。本轮静态执行路径审查发现四项 P1，其中正式技能运行、写技能上架和事实回调恢复均存在可达阻断；不能以现有模块单测通过认定跨服务闭环成立。

## 范围与方法

- 先读本工程 `AGENTS.md`、`docs/implementation-brief.md`、`contracts/README.md`，按 `code-review` 技能检查逻辑、并发、安全与关键失败路径。
- 仅读取本独立工程及明确授权的原设计 `G:/207/项目/算法重组工具/文档/设计/详细设计/智能体构建平台详细设计-整合版.md`，并核对本工程契约快照；未读取相邻业务代码、数据、配置或运行环境凭据。
- 检查 engine-runtime、skill-registry、admin-common、platform-gateway 的实际调用路径，并为候选评测/发布消费追踪本工程 monitor、app-management 接口。模型网关仅检查直接相关接口与冻结边界。
- 已阅读 engine-report、engine-tool-calls-api 的测试证据。按任务要求未重跑已通过完整测试，以下为代码和 SQL 可证明的路径分析；建议新增回归不代表已执行。未修改业务代码，仅新增本报告。
- 所列行号为审查时源码；协作者正在修复，修复后的关闭状态须另行复核。

## 必须修复的代码问题

### R1 / P1：正式运行的 trigger_type 无法进入技能执行

位置：`skill-registry/src/main/java/cn/agentplatform/skill/application/SkillExecution.java:71`；关联 `engine-runtime/src/main/resources/db/migration/V200__engine.sql:5`、`engine-runtime/src/main/java/cn/agentplatform/engine/infra/ToolCalls.java:427`。

引擎权威正式用途为 `CHAT`/`TASK`，`ToolCalls.view()` 原样返回 `trigger_type`；技能端认领后却只接受 `FORMAL`、`DEBUG`、`EVALUATION`。正式 FLOW 的 SKILL 节点和 CHAT 的工具调用都会在已认领后抛 `CALL_KIND_INVALID`。调试和评测不触发此问题，因此可出现评测成功、发布成功、正式执行失败。

修复：将权威 `CHAT`/`TASK` 明确映射为技能统计 `FORMAL`，DEBUG/EVALUATION 原样映射；拒绝其他值，同时在请求快照保留实际权威用途。不要读取调用方 body.triggerType 作为权威，也不要直接把 CHAT/TASK 写入只允许三类 call_kind 的技能表。

建议回归：用真实 ToolCalls claim 回包分别运行 CHAT、TASK、DEBUG、EVALUATION；验证外部执行次数、call_kind 与实际 Mock 标记，正式请求携带 Mock 仍不生效。

### R2 / P1：写技能首次发布存在不可满足的前置条件环

位置：`skill-registry/src/main/java/cn/agentplatform/skill/application/SkillGovernance.java:25`、`:26`；关联 `SkillCatalog.java:159`、`SkillExecution.java:34`、`engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java:349`。

新建写技能为 DRAFT。上架要求该精确版本及摘要有成功 connection_test；connection 对非只读技能只接受该版本的非 Mock、成功 DEBUG execution_fact。但引擎准备技能和技能执行入口都拒绝 DRAFT。因此用户无法通过正常接口产生发布所需的受控调试事实。测试直接 SQL 改成 PUBLISHED 可以掩盖此环，但不是可交付操作路径。

修复：为技能草稿的受控调试建立有权限、有精确版本/摘要绑定的入口，继续复用引擎登记、显式确认、守卫和事实链；仅该受控 DEBUG 路径可执行冻结技能候选。正式运行及一般技能 execute 仍不能任意放开 DRAFT。

建议回归：从纯空业务状态，经真实 API 创建写技能、受控调试、确认、产生连接报告和选型报告、发布；全流程禁止直接 SQL 改状态。同时验证普通正式调用不能执行同一 DRAFT。

### R3 / P1：事实回调一次失败后没有自动重投消费方，可永久挂起

位置：`skill-registry/src/main/java/cn/agentplatform/skill/application/SkillExecution.java:174`—`:185`；关联 `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java:529`、`:945`、`:980`—`:985`。

触发：外部动作已成功，skill.execution_fact 已提交；随后 `/internal/tool-calls/{id}/fact` 短暂不可达。deliver 保存 PENDING/错误并返回 UNKNOWN。引擎将节点转 WAITING_EXTERNAL，而自身 tool_call 仍为 SUBMITTED。技能端不存在定时 outbox 消费；引擎 recover 仅驱动 RUNNING，resumeExternalFacts 又要求引擎 tool_call 已全部为 SUCCEEDED/FAILED，因此没人再次交付这条已持久成功事实。连接恢复、服务重启均不会自行恢复。

`/internal/skill-facts/{id}/retry` 只是显式接口，当前工程没有调度者调用；已有测试手工调用 retry，证明的是接口幂等而非恢复闭环。超过 lease 后回调还会被判 LATE，单加晚到重投也不能完成逻辑提交。

修复：增加持久 outbox 消费或引擎的事实拉取恢复，按可验证主体重投事实，绝不重执行业务；同时为已过期原租约的、已核实成功事实提供受控采纳到新代逻辑提交的路径。持久化重投次数/下次时间/错误，且不能用长期重建高权限用户身份绕过授权。

建议回归：外部成功后只阻断首次 fact 回调，恢复网络，零人工 retry；要求同 callId 最终一次逻辑提交且外部动作一次。再测试重启和 lease 已过期两种时机。

### R4 / P1：提交权校验与取消不互斥，取消完成后仍可取得外部提交权

位置：`engine-runtime/src/main/java/cn/agentplatform/engine/infra/ToolCalls.java:146`—`:161`；关联同文件 `validateLease():68`—`:84`、`RunService.cancel():911`—`:924`。

交错：submitted 事务先以普通 SELECT 验证 node/run 为 RUNNING；在该 SELECT 后、tool_call UPDATE 前另一事务 cancel 提交，node/run 已 CANCELLED；submitted 随后 UPDATE 只检查调用的 EXECUTING、claim owner/attempt 与 claimed_lease，仍会成功并返回 submitted=true，技能端在 `SkillExecution.java:82`—`:85` 随后向外部派发写操作。validateLease 在事务内不等于与取消互斥，READ COMMITTED 的普通 SELECT 不锁住取消相关行。

修复：提交权操作和取消使用一致的 run→node→tool_call 锁序，并在同一互斥事务内验证 live run/node/current attempt 后完成 CAS。claim 也存在同类校验窗口，需一并检查。明确已取得提交权后发生取消属于在途取消三态，不能承诺撤回外部效果。

建议回归：在 validateLease 与调用状态 UPDATE 之间加入可控闩锁，让取消先提交；submitted 必须失败，外部 fixture 调用数为零。反向交错必须明确记录在途提交状态，且取消后不产生逻辑成功事件。

## 需纳入后续修复/验收的边界

这些项目与上述确定代码错误分开列出；已登记为边界不代表可以计为 v1.2 已实现。

| 项目 | 代码/设计依据 | 现状与影响 | 建议 |
|---|---|---|---|
| 确认角色精确性 | `ApprovalService.java:54`—`:57`；原设计2266、2590行 | permitted 把 ADMIN 当作任何声明确认角色。仅具有 ADMIN、没有声明业务审批角色的主体仍可看待办并批准。设计要求按声明角色/租户解析，当前工程没有另外声明 ADMIN 通配审批语义。 | 将审批角色判断与普通管理 requireRole 分离；加入 ADMIN-only 对业务 APPROVER 待办的拒绝用例。若确需管理员越权审批，应先形成明确设计变更及审计策略。 |
| 内部服务来源/受众约束 | `ServiceTokens.java:11`、`:19`—`:23`；`SecurityConfiguration.java:25`—`:40` | 有验签、issuer、通用 audience、用途和短 TTL；但没有具体调用服务身份/目标服务 audience。任何有效内部票据都可到全部服务的 internal 路径，SERVICE 是统一技术角色，无法区别 monitor、engine、skill 的事实提交权限。 | 给内部票据绑定 caller/target/scope 并由接收服务校验；对 fact、出票凭据读取等敏感端点设置来源能力约束，验证跨服务重放被拒。不能将此现状写成已完成来源隔离。 |
| UNKNOWN 对账闭环与取消三态 | `SkillExecution.reconcile():131`—`:167`、`RunService.cancel():903`；原设计1003、1754行 | 对账可查询已生效并回送，但 NOT_APPLIED 只返回 retryBusinessAllowed=false；无自动核实任务、无过期 UNKNOWN 人工采纳闭环；取消仅改引擎状态，无外部停止/效果撤销记录和取消接口。 | 实现并验收未生效→受控重试、已生效→受控采纳、仍未知→人工三个分支；取消分别记录受理/停止/撤销，禁止把取消等同外部效果回滚。 |
| 运行版本链完整性 | `RunService.prepareSnapshot():311`；`ModelCalls.java:59`—`:61`；engine-report末尾 | 技能/模板精确版本及草稿候选已冻结；模型依旧按运行时可变 entry/provider 元数据读取，发布时模型配置/解释器兼容链未完整固化。供应商停用后已发布快照运行规则尚未完成。 | 发布时冻结完整非秘密模型配置和引用，凭据按 secret_ref 实时解析；验证后续修改/停用不会误写历史运行语义。 |
| 容量与队列隔离 | `RunService.recover():950`、`TraceStore.claim():89`；原设计1747—1750、1191、2476行 | 有单 run maxParallel 门槛，但缺租户/用户并发准入、技能级信号量、交互/批量 worker 池、挂起总量上限。recover 固定扫最早100个 RUNNING，在持续占用时后续 run 可能饥饿。 | 先补有界、公平认领和必要的用户/租户/技能容量策略；做超过100活跃run、慢外调与批量评测并存验收。 |
| 持久事件与 SSE | `TraceStore.event():45`、`RunController.events():97`、`ChunkBus.java:13` | 持久 seq/同事务提交、Last-Event-ID补发具备。临时 chunk 为进程内广播，SSE若落不同副本收不到在线增量；全量事件查询无分页。 | 多副本测试在线 chunk 和事实补发的区别；增加可控容量/分页。不能据单进程fixture认定跨副本流体验已验收。 |
| 上下文/会话/调试完整性 | engine-report:63—77 | 四档摘要压缩、多run会话历史、大载荷引擎对象存储、命名输入/LAST_REAL完整授权缓存、三级回放仍缺。 | 按 v1.2 功能逐项记录未实现与外部验收，不能合并成“38测试=全部引擎完成”。 |

## 候选评测与发布消费观察

已跟踪 `EvaluationService.java:30`、`:40`、`:46`，`RunService.verifyCandidate():231`、`verifyReplay():251` 与 `AppService.java:85`、`:131`—`:139`：当前路径确实先持久冻结候选/数据集，真实创建候选 run，按候选摘要/修订消费通过报告，发布事务复核预检与草稿；没有发现常量 PASS 替代真实执行的路径。这只能说明门禁结构存在，R1 证明 EVALUATION 的成功不能代替正式 trigger_type 的集成验收；R2 证明写技能门禁前置链尚不可用。

额外需安排针对性验收：monitor 报告按 tenant 读取/恢复，而 engine 请求幂等按 tenant+principal+requestId（`RunService.java:47`、V200:14）；不同编辑者恢复同一报告时，应保证沿用原用例 run/受控主体，避免把同一 evalReportId+caseId 作为另一 principal 的新业务执行。当前代码没有显式的报告执行主体恢复机制，建议纳入应用/评测域审查，不把这项静态风险描述为已跑通故障复现。

## 已核对的安全基础与测试局限

- Actor 取 SecurityContext 已验证 JWT，SQL 的运行读取、技能事实读取均限定 tenant/principal；外部集成票据限制平台角色、属性交集、应用路径与运行应用归属，实时重查凭据状态/策略摘要。没有发现可直接从 body 伪造 tenant/principal 的路径。
- 写技能必须持久冻结 call/version/参数摘要/角色/有效期，技能独立取引擎 approval；body.confirmed 不构成许可。旧 claim 的提交/事实有 owner、attempt、lease 条件，逻辑提交与事实事件事务化。R4 是取消与该条件检查间的并发窗口，不否定已有顺序场景测试。
- 现有真实 PG、HTTP fixture 和 Spring代理测试是有效局部证据；跨模块 Mock/手动调用 retry/SQL预置发布状态不覆盖 R1—R4。四项回归应以精确故障时机验证，避免重复扩大无关全量测试。

本轮审查停止于报告，不应用修复，不提交、不发布。
