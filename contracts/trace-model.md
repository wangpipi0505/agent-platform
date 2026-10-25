# 详细设计 31 —— 运行时引擎（技术要求 1.2 执行侧 + 对话型驱动）

> 上位：《智能体构建平台设计-自研路线》v3.6；《模块1详细设计》索引
> 本文六视图：功能视图（§1）→ 组成视图（§2，含实现技术与接口签名）→ 流程视图（§3 六条时序）→ 数据视图（§4 全字段 DDL）→ 接口视图（§5）→ 规则视图（§6 终止/预算/裁剪/事件表）→ 验收（§8）
> 技术底座：Java 21 虚拟线程 / Spring Boot 3.x / PostgreSQL（唯一权威状态源，SKIP LOCKED 认领）/ Redis（缓存与信号量）/ SseEmitter
> 协作册：册 20（技能执行器八步管道由本引擎调用）、册 30（图定义契约）、册 32（调试器前端联动）

---

## 1. 功能视图：功能清单与功能设计

运行时引擎共 10 个功能，全部为**执行组**（无门户界面，被网关/调度/其他册触发）。

| 编号 | 功能 | 一句话职责 | 触发者 |
|---|---|---|---|
| F1 | 运行接入与准备 | 验签、钉版本链、GuardContext 快照、创建 run | 网关/门户/评测 |
| F2 | 上下文组装 | 七步管道产出模型请求体+装配清单 | AgentDriver/LLM 节点 |
| F3 | Agent 受控循环 | 对话型驱动：组装→调模型→解析→技能→回灌→循环 | run 调度 |
| F4 | 流程驱动 | 流程型拓扑推进：就绪判定→并行执行→写输出槽 | run 调度 |
| F5 | 技能调用编排 | 循环内多工具调用的并行/串行编排，调册 20 执行器 | F3/F4 |
| F6 | 运行调度器 | 认领/租约/续租/失联接管/优先级分池/优雅停机 | run 调度 |
| F7 | 执行语义 | 幂等键/attempt/结果未知对账联动/取消三态/检查点 | F3~F5 |
| F8 | 人工确认控制 | WAITING_APPROVAL 挂起/恢复/超时/参数变更重确认 | 技能契约/确认节点 |
| F9 | 预算与终止 | 四类预算记账熔断、六情形终止判定 | F3 |
| F10 | 事件持久化与补发 | 持久事件表+单调 seq+SSE 补发/去重 | 全部 |

### 1.1 F1 运行接入与准备

**功能描述**：一次智能体执行的入口工序。它完成四件"开工前必须钉死"的事：①解析并验签调用者身份，生成权限属性快照（GuardContext，含快照时间，敏感技能执行前可重校验）；②**钉死版本链**——把该智能体发布版本及其引用的模板版本/技能版本/模型配置/解释器兼容版本全部固化进 run 记录，此后任何发布动作都不影响本运行；③创建 run 状态机并落库（唯一权威状态源）；④把 run 投入调度队列。它是"运行钉版本""可重建不变量""租户贯穿"三条总纲的执行点。

**输入**：外部请求（对话消息/任务参数/调试请求/评测跑批）。
**输出**：run（PENDING）+ 版本链快照 + GuardContext。
**规则**：禁止 FORMAL 运行命中草稿版本；**DEBUG 运行以草稿为源，但创建时冻结"调试快照"**——固化草稿修订号、配置/图定义/依赖版本与内容摘要（run.debug_snapshot，存对象存储，保留期随草稿生命周期），运行绑定该不可变快照；草稿后续编辑只影响之后新建的调试运行（运行 A 挂起后改稿并新建运行 B：A 用旧快照、B 用新快照，回放分别可追溯）。GuardContext 快照带 TTL 且敏感技能执行前重校验；评估/调试/正式以 trigger_type 区分，统计与门禁按此隔离。

### 1.2 F2 上下文组装（七步管道）

**功能描述**：每次模型调用前，把散落各处的要素加工成一次模型调用的请求体+装配清单。**不是会话开始跑一次，而是循环每轮都跑**——历史在变长、快照在更新、工具清单可能被守卫改写。管道七步：备料（并行拉取配置/历史/检索/输入/业务变量，检索超时降级）→ 渲染（系统提示分节注册表合并+严格变量插值）→ 消息化（搭消息骨架，工具调用/结果成对）→ 分账（三档计量之估算档分账，公式见总纲）→ 裁剪（四档从轻到重，保调用/结果配对，快照按类型只取最新有效版）→ 校验（总计数/消息结构/变量缺槽/出域四查）→ 冻结（请求体定稿+装配清单落库）。**可重建不变量在此步兑现**：冻结的请求体先持久化（attempt.request_snapshot_ref），再发模型请求。

**输入**：RunContext + 策略。**输出**：AssembledRequest（messages/tools/params/manifest）。
**规则**：分节注册表三级遮蔽+白名单；组装瀑布=守卫裁词表实现位；快照投影按类型/作用域/版本取最新；tokenizer 按模型适配。

### 1.3 F3 Agent 受控循环（对话型驱动器）

**功能描述**：对话型智能体的"发动机"。每轮：组装 → 调模型（流式）→ 解析输出 → 纯文本则终答收口；工具调用则过守卫与确认 → 逐个执行 → 结果回灌 → 下一轮。**它不知道执行过程**——路径由模型每轮临场决定，引擎负责的是边界：终止规则六情形、四类预算、无进展指纹检测、守卫短路、混合返回处理。多工具调用可并行（各自独立信封）。支持嵌套（Agent 节点 = 受预算与深度约束的子循环）。

**输入**：run（CHAT 触发）。**输出**：终答/熔断原因/异常。
**规则**：每轮预算前置检查；无进展指纹（连续 N 轮 skillId+参数 hash 重复）→ 熔断；模型调用前必须先持久化请求（不变量）。

### 1.4 F4 流程驱动（流程型驱动器）

**功能描述**：流程型智能体的推进器。按图定义契约推进：就绪判定**按汇合语义分派**（详见册 30 §6.1 权威定义与 §6.4 隔离/取消范围：ANY=任一入边 SUCCEEDED 即就绪并锁定获胜分支，其余 ABANDONED——**仅取消"无其他有效消费者的专属剩余分支"**，共享上游不误伤；全部 FAILED/失败混合跳过且无剩余成功可能 → 汇合节点 FAILED/SKIPPED 进入确定终态）→ 并行提交就绪节点（虚拟线程）→ 节点处理器执行（LLM/技能/条件/代码/检索/确认）→ 写输出槽 → 计算新就绪集，直至 END 或失败终止。**执行规则显式**：并行汇合（ALL/ANY）、错误三分支（终止/错误边/默认值）全部由图定义声明，解释器只负责忠实执行。

**输入**：run + flow_definition。**输出**：run 终态 + 各节点输出槽。
**规则**：并行分支禁止写同名变量（发布校验保证）；条件表达式为 SpEL 受限白名单子集；CONFIRM 节点与 Agent 节点按 §1.8/嵌套预算执行。

### 1.5 F5 技能调用编排

**功能描述**：Agent 循环与技能执行器之间的编排层。模型一轮可能要求多个工具调用：逐个解析 → 守卫与确认判定（CONFIRM_REQUIRED 触发挂起，**仅挂起该节点及其依赖后继，不依赖该结果的其他分支继续**）→ 无确认阻塞的并行执行（虚拟线程）→ 按 call_id 汇总信封 → 有失败按失败语义决定整体走向（单技能失败默认不阻断兄弟调用）。
**规则**：每个调用独立幂等键与信封（结果隔离）；并行数量受信号量限制。

### 1.6 F6 运行调度器

**功能描述**：多副本下的执行权管理者。就绪步骤进入待执行表（DB 即队列），副本以 `SELECT … FOR UPDATE SKIP LOCKED` 认领并写租约（lease_owner + lease_until），执行中续租；租约过期 → 其他副本可接管（旧副本提交带租约校验，失联提交被拒）；优雅停机：停止认领 → 在途执行完或租约到期转交。交互/批量两条队列与 worker 池分离，互不挤占。
**规则**：租约校验与状态更新同事务；认领即计 attempt；等待态（确认/调试/外部任务）不占用执行权。

### 1.7 F7 执行语义

**功能描述**：与册 20 执行器协作，落实"恰好一次"的业务效果：逻辑节点实例与执行尝试分离（node_instance_id + attempt_no）、幂等键生成与管理、结果未知对账联动（调册 20 Reconciler）、取消三态记录（请求受理/外部停止/效果撤销）、Agent 调用级检查点（细化到每次模型调用与每次工具调用的持久化）。
**规则（调用登记制，本轮修正）**：**每个逻辑调用在首次发起前原子登记**——tool_call 记录（run_id + node_instance_id + call_seq + skill_version_id + 参数摘要），登记成功即取得该调用的唯一执行身份；**幂等键 = hash(run_id + node_instance_id + call_seq + skill_version_id)，登记时生成并持久化，重试/恢复/接管一律复用登记值**——不同逻辑调用身份不同（同参数的两次合法调用不会互判为重试），同一调用的恢复与重试复用登记身份。旧 attempt 迟到结果验签留痕参与对账，不重复提交逻辑结果。
**认领与失联恢复（评审 U03）**：tool_call 的认领（REGISTERED→EXECUTING）绑定当前 node_instance 的有效租约——认领条件含租约检查，EXECUTING 记录认领副本；技能副本在写 SUBMITTED 前失联 → 租约过期后 tool_call 回 REGISTERED 可被接管者重新认领（幂等键不变；写操作的副作用风险由"SUBMITTED 前失联不得重发写操作、先对账"规则覆盖）；**旧执行者恢复后的提交被认领 CAS 拒绝**。三个中断点恢复：CAS 认领后失联→重新认领；提交标记后失联→SUBMITTED=已提交未知→对账；响应已保存后失联→结果已在，直接交付。内部交付报文：`POST internal/tool-calls/{callId}/result {status, resultRef, errorSemantics}`（幂等 by callId，消费键=callId）。
**调用的完整生命周期与三个恢复窗口（评审 R02）**：生命周期 = **模型返回检查点**（流式响应完成时，对返回的每个 tool_call 分配稳定 call_seq 并登记 tool_call 记录——登记持久化后才执行，崩溃后接管者从检查点发现"已登记未执行"的调用，**复用原 call_seq 与幂等键**，不重新分配）→ 登记取得执行权 → 提交外部 → 终态/UNKNOWN → 对账 → 逻辑结果提交 → Agent 继续 → 全部调用终态后节点完成。**三个恢复窗口**：①登记后崩溃（未提交外部）→ tool_call=REGISTERED，接管者按登记参数直接执行（幂等键防并发）；②已提交外部、平台未知（Job 已创建/HTTP 已发出）→ UNKNOWN → 对账器核实（确定性 Job 名+绑定表复用）；③执行事实已落库、逻辑结果提交失败（跨服务回调丢失）→ 引擎按"SUCCEEDED 且未提交"的 tool_call 轮询恢复，补提交逻辑结果（幂等）。**分工**：技能中心=执行事实（attempt/响应/守卫留痕）；引擎=逻辑结果、节点状态与事实事件的唯一提交者（修正主设计旧表述）。

### 1.8 F8 人工确认控制

**功能描述**：技能声明 CONFIRM_REQUIRED 或流程含 CONFIRM 节点时，把对应 node_instance 置 WAITING_APPROVAL 并生成确认待办（确认人范围、参数快照、有效期）；确认人处理（确认/拒绝/超时）驱动恢复或终止；**确认后参数变更须重新确认**。与调试挂起 WAITING_STEP 完全独立。
**规则**：默认仅挂起需确认节点及其依赖后继，其他分支照常；run 聚合状态按节点状态计算（无可推进节点且有待确认 → 显示 WAITING_APPROVAL）。

### 1.9 F9 预算与终止

**功能描述**：四类预算（总耗时/模型调用次数/token 总量/嵌套深度）的记账与前置熔断；终止规则六情形的判定与出口（终答/预算熔断/异常终止/用户取消/确认拒绝/无进展），每条出口都写明 stop_reason 供 trace 与答复。
**规则**：预算前置检查（下一轮开始前）；超限答复须向用户说明"因预算停止"及已完成部分。

### 1.10 F10 事件持久化与补发

**功能描述**：run 的全部事实事件（状态变更/节点开始结束/等待恢复/逻辑结果提交/终态）写持久事件表（run 内单调递增 seq），**与状态更新同事务**；SSE 是观察通道：断线重连带 Last-Event-ID 从持久表补发，消费端按事件 ID 去重；chunk 增量属展示数据，与事实事件分离（未配逐 chunk 持久化则不承诺逐 token 补发）。
**规则**：订阅先校验租户/归属/查看权限；游标早于保留窗口返回过期信号+状态水位快照。

---

## 2. 组成视图：模块组成与实现技术

### 2.1 组件组成图

```
┌──────────────────────── engine-runtime ─────────────────────────┐
│ 入口                                                             │
│  RunController(执行API)   DebugRunController(调试)   内部触发API    │
├──────────────────────────────────────────────────────────────────┤
│ 编排层                                                           │
│  RunService(执行准备/版本钉定/GuardContext)                        │
│  RunScheduler(认领/租约/接管/分池)                                 │
│  AgentDriver(受控循环)   FlowDriver(拓扑推进)                      │
│  ToolCallOrchestrator(多工具并行/串行+确认分流)                     │
│  ApprovalController  DebugController                              │
├──────────────────────────────────────────────────────────────────┤
│ 领域组件                                                          │
│  ContextAssembler(七步管道)                                       │
│   ├─ SectionRegistry │ SnapshotProjector │ VariableInterpolator   │
│   ├─ TokenCounter(固定密度/jtokkit/usage校准) │ TrimPipeline        │
│   └─ Summarizer(摘要后端,调模型)                                   │
│  ExecutionSemantics(幂等键/attempt/对账联动/取消三态/检查点)         │
│  BudgetEnforcer │ TerminationRules │ ProgressDetector(无进展指纹)   │
│  EventStore(持久事件+seq) │ SsePusher │ ModelGatewayClient(Spring AI)│
│  GuardResolver(交集判定) │ NodeHandlerRegistry(节点处理器注册表)     │
├──────────────────────────────────────────────────────────────────┤
│ 出口                                                              │
│  skill-registry 执行器(技能调用) │ model-gateway(模型调用)          │
│  PostgreSQL(权威状态) │ Redis(缓存/信号量) │ 队列(算法Job步)         │
└──────────────────────────────────────────────────────────────────┘
```

### 2.2 组件职责与实现技术

| 组件 | 实现技术 | 职责 |
|---|---|---|
| RunService | @Service + Flyway 表 | 执行准备四件事（§1.1） |
| RunScheduler | JDBC `FOR UPDATE SKIP LOCKED` + 租约字段 + Java 21 虚拟线程 worker | 认领/续租/接管/分池 |
| AgentDriver | 虚拟线程 per run + SseEmitter 流式 | 受控循环 |
| FlowDriver | 内存图模型 + 事件驱动推进 + 虚拟线程并行 | 拓扑执行 |
| ToolCallOrchestrator | 虚拟线程并行 + Semaphore（模型/技能并发限） | 多工具编排+确认分流 |
| ContextAssembler | 管道阶段类 + jtokkit + 估算器 + usage 校准器 | 七步组装 |
| SectionRegistry | 内存注册表（名称唯一+序号排序+作用域遮蔽） | 分节/上下文/工具/变量贡献 |
| ExecutionSemantics | @Service（幂等键/attempt/对账/取消三态/检查点） | 恰好一次语义 |
| ApprovalController / DebugController | @Service + 状态字段 + 超时器 | 两类挂起态分立 |
| BudgetEnforcer / TerminationRules / ProgressDetector | 纯 Java 策略类 | 预算与终止 |
| EventStore + SsePusher | run_event 表 + SseEmitter + Last-Event-ID 补发 | 事件持久与观察 |
| GuardResolver | @Service + IAM 客户端 | 交集判定与留痕 |
| ModelGatewayClient | Spring AI ChatClient（**internalToolExecutionEnabled=false**，仅单次模型调用） | 模型调用；**工具循环由 AgentDriver 控制**——守卫/预算/检查点/确认全部包在循环内 |

**依赖纪律**：Driver 依赖领域组件，领域组件互不依赖适配器实现；所有模型调用必经 ModelGatewayClient；所有技能调用必经 skill-registry 执行器；所有事件必经 EventStore。

### 2.3 关键接口签名

```java
interface RunDriver { CompletableFuture<RunOutcome> drive(RunContext ctx, DriveListener listener); }
record RunContext(RunId runId, AppVersionRef version, ConversationRef conversation,
                  GuardContext guard, JsonNode input, Strategy strategy, BudgetState budget) {}

interface NodeHandler {                       // 节点处理器 SPI（模块2扩展点）
    String type();                            // LLM|SKILL|CONDITION|CODE|RETRIEVE|CONFIRM|AGENT
    NodeResult execute(NodeInstance node, FlowContext ctx, DriveListener l);
}

interface LLMClient {                         // 封装 Spring AI，收口模型调用
    ModelStream call(ModelRequest request);   // 流式；内部完成计量/usage校准/出域路由
}

class ContextAssembler {
    AssembledRequest assemble(RunContext ctx);            // 七步管道
    AssembledRequest assembleForNode(LLMNodeConfig, FlowContext ctx);  // 流程 LLM 节点
}
```

---

## 3. 流程视图：核心流程时序（含异常分支）

### 3.1 运行创建与执行准备

```
调用方 → gateway：验签(JWT/出票) → 限流 → RunService.prepare()
  RunService：解析配置来源（FORMAL=发布版本，禁草稿；DEBUG=冻结草稿快照）→ GuardContext 快照 → 建 run(PENDING)
            → 写 version_refs → 投入调度队列(交互池|批量池)
  → gateway 返回 {runId, 状态查询地址, 事件流地址}
分支：版本停用 → SYS-APP-4002；限流 → SYS-GW-429+retryAfter
```

### 3.2 Agent 受控循环完整时序（成功路径+分支位）

```
RunScheduler(认领)  AgentDriver  ContextAssembler  ModelGateway  GuardChain  ApprovalCtl  SkillExecutor  EventStore
   │认领 run────────>│                                                                                  
   │                 │①budget.check ──超限→[熔断出口,说明已完成的哪一步]                                  
   │                 │②assemble(七步)──>│  ……(册20/§6.1 七步)……       │                          
   │                 │③persistRequest(候选版,先落库,不变量)──────────────────────────────>│                   
   │                 │③b 网关路由/准入/必要重组→**最终请求重冻结落库**(新快照版本,不覆盖前次)──>│                   
   │                 │④call(流式)──────>│ token chunk* ──> SsePusher（展示流）                            
   │                 │⑤解析: 纯文本?──→ [终答出口]⑥落库响应→RUN_FINISHED                                 
   │                 │   工具调用×k → ToolCallOrchestrator:                                            │
   │                 │   每调用: 守卫──────>│ 交集=空→信封(NO_PERMISSION)回灌│                          
   │                 │              CONFIRM_REQUIRED──>ApprovalCtl→WAITING_APPROVAL(挂起,事件)        
   │                 │              放行→SkillExecutor(八步,册20)──>Envelope                          
   │                 │   回灌(callId 对应)→⑦budget.consume→无进展指纹→回到①                             
   终止/挂起/熔断/取消 → EventStore 落 stop_reason
```

**分支表**：混合返回（文本+工具并存→文本作增量说明，先执行工具）；输出截断（max_tokens）→按策略续写或失败；参数不合法→带错误回灌计步；未知工具→回灌纠正；无进展指纹连续重复→熔断。

### 3.3 FlowDriver 推进时序（含并行/条件/失败分支）

```
就绪集 = [n1] → n1 执行成功 → 就绪集=[n2,n3](并行) → 两虚拟线程同时执行
  n2 成功 / n3 失败(策略=错误边→n3e) → 汇合节点 N：入边状态 {n2:S, n3:E(错误边), n3b:SKIPPED}
  按 ALL 语义（成功+SKIPPED 视为通过）放行 → 继续
  条件节点求值 → 仅命中分支置 READY，其余 SKIPPED 并向其后继传播
  CONFIRM 节点 → 该支路 WAITING_APPROVAL，其余分支照常推进
```

### 3.4 调度器认领-接管时序（副本崩溃场景）

```
副本A：认领步骤S(租约30s) → 执行中（等待模型）
副本A：每10s续租 …… 副本A崩溃（续租停止）
副本B：租约到期扫描 → S 租约超时 → 接管：置 attempt1=SUPERSEDED(失联)
  → 按 §6.5 恢复检查点：模型响应已落库则复用；工具调用已提交则凭幂等键对账
  → 从检查点继续（不重跑已完成的模型调用）
副本A 若复活：**拒绝其直接提交逻辑结果**（租约已失），但其执行结果/外部事实**验签留痕并纳入对账**——SUPERSEDED 只转移执行权，不作废事实，防"外部已成功、证据被丢"导致重复执行
```

### 3.5 上下文组装管道内部流程（七步）

备料（并行拉取：配置/历史/检索[超时降级]/业务变量）→ 渲染（分节合并+严格插值）→ 消息化（骨架+配对）→ 分账（估算档，窗口−输出−安全余量−系统−工具−当前−检索=历史可用）→ 裁剪（快照投影取最新→四档：工具输出修剪/整轮丢弃/摘要压缩[调模型]/硬截断）→ 校验（总数/结构配对/缺槽/出域路由）→ 冻结（快照+manifest：分账表/裁剪日志/来源引用）。

### 3.6 WAITING_APPROVAL 挂起与恢复时序

```
技能执行段一确认判定 → node=WAITING_APPROVAL, run 聚合状态更新, 生成确认待办
确认人 → 确认中心 → 确认(参数快照比对，变更则重新挂起) / 拒绝 / 超时
  确认 → node 回 RUNNING → 从检查点继续（该技能调用真正发起）
  拒绝/超时 → 按节点配置：终止 或 走默认分支
全程：审计记录（谁/何时/参数快照/结论）；与 WAITING_STEP 互不影响
```

---

## 4. 数据视图：trace 权威模型（完整 DDL，v3.7 按 R01 补全）

> **本节是 run / node_instance / attempt / tool_call 的唯一权威定义**，其他分册与主设计只引用、不另定义。公共字段（id/tenant_id/created_by/at/updated_by/at/deleted）各表省略。

### 4.1 run

```sql
CREATE TABLE run (
  id               BIGINT PRIMARY KEY,
  app_code         VARCHAR(64)  NOT NULL,
  app_version_id   BIGINT       NULL,          -- CHAT/TASK 必填（发布版本）；DEBUG 为 NULL；
                                               -- EVALUATION 二选一：发布版本 或 eval_candidate
  debug_snapshot   JSONB        NULL,          -- DEBUG：草稿冻结快照引用（修订号/摘要/存储位置；
                                               --  保留期 ≥ run 查询/回放保留期，评审 T03）
  eval_candidate   JSONB        NULL,          -- ★EVALUATION 候选评测专用（不可变候选快照引用）：
                                               --  {appDraftId, draftRev, candidateSha256,
                                               --   evalReportId, datasetVersion}
                                               --  未发布候选的评测报告绑定此引用，precheck/commit
                                               --  按其 candidateSha256 校验对应性，不得借用旧发布版本报告
  -- 表级一致性 CHECK（按 trigger_type 判定，不引入 FORMAL 字面量）：
  --   CHAT|TASK  ⇒ app_version_id 非空，debug_snapshot 与 eval_candidate 均为空；
  --   DEBUG      ⇒ app_version_id 为 NULL，debug_snapshot 非空，eval_candidate 为空；
  --   EVALUATION ⇒ (app_version_id 非空) XOR (eval_candidate 非空)，debug_snapshot 为空
  trigger_type     VARCHAR(16)  NOT NULL,      -- CHAT|TASK|DEBUG|EVALUATION
  status           VARCHAR(24)  NOT NULL,      -- PENDING|RUNNING|WAITING_STEP|WAITING_APPROVAL|
                                               -- WAITING_EXTERNAL|SUCCEEDED|FAILED|CANCELLED
  stop_reason      VARCHAR(32)  NULL,          -- FINAL_ANSWER|BUDGET_*|NODE_FAILED|USER_CANCEL|
                                               -- DEBUG_TIMEOUT|APPROVAL_REJECTED|APPROVAL_TIMEOUT
  principal_id     VARCHAR(64)  NOT NULL,
  tenant_id        VARCHAR(64)  NOT NULL,
  conversation_id  BIGINT       NULL,
  input_ref        VARCHAR(256) NOT NULL,      -- 输入存对象存储，run 存引用
  output_ref       VARCHAR(256) NULL,
  version_refs     JSONB       NOT NULL,       -- {appVer,flowDefSha,promptVers{},skillVers{},
                                               --  modelCfg,interpreterVer,evalReportId,
                                               --  ontology:[{spaceCode,releaseId,version,
                                               --    contentDigest}]}  ★本体为调用级版本集合
                                               --  （未调用本体=空数组，非"预留"字符串）
  guard_ctx        JSONB       NOT NULL,       -- 权限属性快照+snapshotAt
  budget           JSONB       NOT NULL,       -- limits/consumed/deadline（§6.6）
  started_at       TIMESTAMPTZ, ended_at TIMESTAMPTZ
);
CREATE INDEX idx_run_app_status ON run(app_code, status, started_at DESC);
CREATE INDEX idx_run_tenant ON run(tenant_id, started_at DESC);
```

### 4.2 node_instance

```sql
CREATE TABLE node_instance (
  id                    BIGINT PRIMARY KEY,
  run_id                BIGINT NOT NULL,
  node_def_id           VARCHAR(64) NULL,     -- 图节点定义 id；对话型循环步为 NULL（用 seq_no）
  step_seq              INT NOT NULL,         -- run 内步骤序号（对话型/流程型统一递增）
  node_type             VARCHAR(16) NOT NULL,
  join_semantics        VARCHAR(8)  NULL,     -- 汇合节点记录 ALL/ANY（审计）
  status                VARCHAR(24) NOT NULL, -- READY|RUNNING|WAITING_STEP|WAITING_APPROVAL|
                                              -- WAITING_EXTERNAL|SUCCEEDED|FAILED|SKIPPED|
                                              -- ABANDONED|CANCELLED
  lease_owner           VARCHAR(64) NULL,     -- 执行权持有副本（认领时写入，续租刷新）
  lease_until           TIMESTAMPTZ NULL,     -- 租约到期即失联，可被接管
  committed_attempt_id  BIGINT     NULL,      -- ★唯一逻辑结果来源（提交 CAS 条件见 4.6）
  committed_output_ref  VARCHAR(256) NULL,
  error                 JSONB NULL,           -- {semantic,message,attempts,disposition}
  input_digest          CHAR(64), output_digest CHAR(64),
  started_at, ended_at,
  UNIQUE(run_id, step_seq)
);
```

### 4.3 attempt

```sql
CREATE TABLE attempt (
  id                    BIGINT PRIMARY KEY,
  node_instance_id      BIGINT NOT NULL,
  attempt_no            INT NOT NULL,         -- 从 1 递增；认领/重试/接管各计一次
  status                VARCHAR(16) NOT NULL, -- RUNNING|SUCCEEDED|FAILED|UNKNOWN|SUPERSEDED
  idempotency_key       CHAR(64) NOT NULL,    -- 登记制生成（§1.7），跨尝试稳定属于逻辑调用层，
                                              -- attempt 层记录本尝试实际使用的键
  request_snapshot_ref  VARCHAR(256) NOT NULL,-- ★最终请求材料（不变量：先落库再发送）
  routed_model          JSONB,                -- 实际路由的模型与端点（出域审计）
  response_ref          VARCHAR(256) NULL,
  error                 JSONB NULL,
  started_at, ended_at,
  UNIQUE(node_instance_id, attempt_no)
);
```

### 4.4 tool_call —— 调用登记（登记先行）

```sql
CREATE TABLE tool_call (
  id                BIGINT PRIMARY KEY,
  run_id            BIGINT NOT NULL,
  node_instance_id  BIGINT NOT NULL,
  call_seq          INT NOT NULL,             -- 该节点内逻辑调用序号，登记时分配
  skill_version_id  BIGINT NOT NULL,
  args_digest       CHAR(64) NOT NULL,        -- 规范化参数摘要（对账比对用，不进幂等键）
  args_ref          VARCHAR(256) NULL,        -- 完整入参引用（模型响应检查点关联，可追溯）
  idempotency_key   CHAR(64) NOT NULL,        -- hash(run_id,node_instance_id,call_seq,skill_version_id)
                                      UK(idempotency_key),
  status            VARCHAR(16) NOT NULL,     -- REGISTERED|SUBMITTED|EXECUTING|SUCCEEDED|
                                              -- FAILED|UNKNOWN|ABANDONED
  claimed_by        VARCHAR(64) NULL,         -- ★认领代次/执行者：认领时写入（绑定 node attempt
                                              --  与租约代次），后续状态迁移按此条件更新
  claimed_at        TIMESTAMPTZ NULL,
  claimed_lease     TIMESTAMPTZ NULL,         -- ★认领租约到期（=认领时 node_instance.lease_until；
                                              --  过期后本调用可被接管者重置回 REGISTERED）
  is_mock           BOOLEAN NOT NULL DEFAULT false,  -- 逐调用 Mock 标记：执行器第④步实际命中
                                                     -- Mock 才为 true；DEBUG run 可混合真实与
                                                     -- Mock 调用，**不以运行模式推导**
  duration_ms       INT NULL,                 -- 逻辑调用耗时=各次实际执行（EXECUTING 段）耗时
                                              -- 之和，对账等待不计（口径已选定，评审 W01）
  error_semantic    VARCHAR(32) NULL,         -- 最终终态的错误语义（对账后归类）；长度按五类
                                              -- 语义枚举最长值 RETRYABLE_FAILURE=17 对齐并留余量
  result_ref        VARCHAR(256) NULL,
  created_at
);
-- 认领与失联恢复（评审 V02）：
--   认领：UPDATE tool_call SET status='EXECUTING', claimed_by=:me,
--            claimed_lease=:nodeLeaseUntil
--         WHERE id=:callId AND status='REGISTERED';   -- CAS，影响 0 行=他人已处理
--   后续迁移（EXECUTING→SUBMITTED、→UNKNOWN）一律附带
--         AND claimed_by=:me AND claimed_lease 未过期——
--        **认领副本失联、租约过期后，由接管者按幂等键重置回 REGISTERED 再认领**，
--        旧执行者恢复后的提交因 claimed_by 不匹配被 CAS 拒绝；
--   两种恢复路径按"能否证明尚未发出外部请求"区分：
--     REGISTERED→可安全重发；SUBMITTED/EXECUTING 且副作用可能已发生→先对账再决策，
--     写操作不得凭 REGISTERED 记忆直接重发。
--   旧执行者返回的外部事实：验签留痕参与对账；拒绝的是过期执行者**继续发起操作或
--   直接提交逻辑结果**，不丢弃成功证据。callId 跨恢复稳定，但不单独承担新旧执行权区分。
-- 登记先行：INSERT 成功（唯一约束仲裁并发）= 取得执行权，才发起外部调用；
-- 确定性 Job 名 = 'alg-'||idempotency_key（第二重保险，不替代登记）
```

### 4.5 逻辑结果原子提交与同事务范围（R01 关闭条件）

```sql
-- 引擎侧（持有有效租约的副本）提交逻辑结果：三写一个事务
BEGIN;
  UPDATE node_instance
     SET committed_attempt_id = :attemptId,
         status = 'SUCCEEDED', committed_output_ref = :ref,
         ended_at = now()
   WHERE id = :nid
     AND committed_attempt_id IS NULL          -- 尚未提交过
     AND status = 'RUNNING'                    -- ★可提交状态白名单：仅运行中可迁移到成功
                                               -- （ABANDONED/SKIPPED/WAITING_* 的迟到完成只留痕，
                                               --  不改终态、不发成功事件）
     AND lease_owner = :me AND lease_until > now();   -- 仍持有有效执行权

  -- ★影响行数 = 0（已提交/已取消/租约失效）：本事务到此结束——
  --   不写 LOGIC_RESULT_COMMITTED、不改节点终态、返回既有提交结果；
  --   本次结果降级为留痕（SUPERSEDED_SUBMIT），防虚假成功事件与终态改写（评审 T01）
  IF NOT FOUND THEN
    INSERT INTO attempt_note(node_instance_id, attempt_no, kind)
         VALUES (:nid, :attemptNo, 'SUPERSEDED_SUBMIT');
    COMMIT;
    RETURN 既有提交结果(:nid);
  END IF;

  -- 仅在条件更新成功后才写成功事件（同事务）
  INSERT INTO run_event(run_id, seq, type, payload)
       VALUES (:runId, next_seq(:runId), 'LOGIC_RESULT_COMMITTED', :payload);
COMMIT;
```

- **同事务范围**：逻辑结果引用 + 节点状态 + 事实事件（LOGIC_RESULT_COMMITTED）三写一个事务（节点状态已在第一条 UPDATE 迁移，无需第二条 UPDATE）；
- **并发收敛**：并发完成/旧租约迟到/接管者完成同时发生时，`committed_attempt_id IS NULL` 的条件更新保证**只有一处提交成功**，其余降级为留痕；
- **跨服务分工**：执行事实（attempt/响应）由技能中心持久（可重复消费），逻辑结果/状态/事件由引擎提交——执行事实先行落库，引擎提交失败可凭事实恢复（§6.5 三窗口）；
- **事件 seq 分配**：`next_seq(run_id)` 在与状态更新的同事务内取号，保证 monotonic 且不跳号丢失。

### 4.6 数据模型补充与 budget 结构

```sql
run_event(id BIGSERIAL PK, run_id BIGINT NOT NULL, seq INT NOT NULL,
          type VARCHAR(32) NOT NULL,      -- RUN_STARTED|NODE_STARTED|NODE_FINISHED|
                                          -- NODE_FAILED|WAITING_STEP|RESUMED|WAITING_APPROVAL|
                                          -- APPROVAL_DONE|LOGIC_RESULT_COMMITTED|RUN_FINISHED|...
          payload JSONB,                  -- 事件体（事实数据，不含 chunk 增量）
          created_at,
          UNIQUE(run_id, seq))            -- 补发/去重的依据
message(id BIGINT PK, conversation_id, run_id NULL, seq INT,
        role SYSTEM|USER|ASSISTANT|TOOL, content_ref VARCHAR,   -- 原文在对象存储
        content_digest VARCHAR, tool_call JSONB NULL, tokens INT, created_at)
```

**budget 结构（唯一一份）**：

```json
{ "limits": {"maxSteps":25,"modelCalls":40,"totalTokens":200000,"totalDurationMs":600000,"maxDepth":2},
  "consumed": {"steps":7,"modelCalls":8,"tokens":43210,"depth":1},
  "deadline": "2026-10-19T15:30:00+08:00" }
```

> **modelCalls 为独立上限**（评审 T12）：与 maxSteps 并列——一步可含多次模型调用（续写/重试），两者分别记账、分别熔断。

---

## 5. 接口视图

### 5.1 对外执行 API（经 gateway，详见册 40 发布集成）

`POST /v1/apps/{code}/runs` · `GET /v1/runs/{id}` · `GET /v1/runs/{id}/events`（SSE+Last-Event-ID）· `POST /v1/runs/{id}/cancel` · `GET /v1/runs/{id}/result`

### 5.2 门户/调试 API

`POST /admin/debug-runs`（mode=RUN|STEP）· `POST /admin/debug-runs/{id}/resume`（幂等）· `POST /admin/debug-runs/{id}/cancel` · `GET /admin/runs`（检索）· `GET /admin/runs/{id}`（详情+节点摘要）· `GET /admin/runs/{id}/replay`（回放三级）

### 5.3 内部 SPI

`NodeHandler`（节点处理器注册表，模块 2 扩展点）、`LLMClient`（模型调用收口）、`SkillExecutorClient`（调 skill-registry）、`VocabularyProvider`（词表提供者，模块 2 词表注入点）。

---

## 6. 规则视图

### 6.1 Agent 终止规则表（六情形 × 出口）

| # | 情形 | 判定 | 出口 | stop_reason |
|---|---|---|---|---|
| 1 | 纯文本返回 | finish_reason=end_turn 且无 tool_call | 终答 | FINAL_ANSWER |
| 2 | 工具调用+文本并存 | 同响应两者皆有 | 先执行工具，文本作增量说明（不终止） | —（继续） |
| 3 | 输出截断 | finish_reason=max_tokens | 按策略：续写（拼接）或失败 | TRUNCATED |
| 4 | 参数不合法 | Schema 校验失败 | 带原因回灌重试（计步）；连续 2 次→反问用户 | —（继续/澄清） |
| 5 | 未知工具 | 名不在可见清单 | 回灌纠正 | —（继续） |
| 6 | 无进展 | 连续 N=3 轮 (skillId+参数hash) 指纹重复 | 熔断，说明已得结果与卡点 | NO_PROGRESS |
| 预算 | 步数/token/耗时/深度任一超限 | 前置检查 | 熔断，答复说明与已完成部分 | BUDGET_* |

### 6.2 上下文裁剪四档（从轻到重，配对保持）

①旧工具输出修剪（独立修剪器，零模型成本，保头尾）→ ②整轮丢弃 → ③摘要压缩（调模型，丢弃轮次生成"前情摘要"，压缩调用入 trace）→ ④硬截断兜底。

### 6.3 事件类型表

| 事件 | 载荷要点 | 消费 |
|---|---|---|
| RUN_STARTED / RUN_FINISHED | 版本链/stop_reason/usage | 前端终态、统计 |
| NODE_STARTED / NODE_FINISHED / NODE_FAILED | 节点 id/输入输出摘要/耗时/attempt | 画布状态、回放 |
| WAITING_STEP / RESUMED | 挂起节点/prompt 快照引用 | 调试面板 |
| WAITING_APPROVAL / APPROVAL_DONE | 确认待办/结论 | 确认中心、审计 |
| LOGIC_RESULT_COMMITTED | 幂等键/artifact 引用 | 结果查询、溯源 |

### 6.4 隔离设计矩阵（数据隔离 + 操作隔离在引擎内的落点）

**定位**：引擎是隔离的**判定与执行位**——守卫在这里判、归属链在这里盖、并发在这里限、状态在这里分；判定依据来自 IAM，最终强制落在数据源/存储层，引擎自己不产生任何豁免。

**数据隔离四落点**：

| 隔离目标 | 引擎内机制 | 落点（组件/流程） |
|---|---|---|
| 查询侧：B 查不到 A 的数据 | GuardContext 快照 → 执行前守卫链（权限重校验→交集→出域） | RunService 快照（F1）→ ToolCallOrchestrator 每次技能调用前触发（F5）→ GuardChain（册 20 段一②） |
| 结果侧：A 的结果回不到 B 的上下文 | 归属链信封 + 单一回灌投影 | SkillExecutor 盖章（册 20）→ 统一结果组装器投影：对话型投影回本会话历史、流程型按节点依赖读本 run 输出槽 |
| 上下文侧：两会话消息互不混入 | 引擎内存无单例可变状态 | ContextAssembler 备料只读本会话（conversation_id）；FlowDriver 输出槽按 run/node 隔离；同会话连发消息串行化 |
| 租户侧 | tenant 贯穿 run/event/缓存键/存储路径 | EventStore 订阅校验租户归属（F10）；全部查询携带 |

**操作隔离四落点**（不同运行的操作互不干扰）：

| 隔离目标 | 引擎内机制 | 落点 |
|---|---|---|
| 执行权隔离 | 认领/租约/接管：同一步骤同时只有一个副本持有执行权；旧副本提交被拒 | RunScheduler（§3.4 时序） |
| 并发隔离 | 三类信号量（模型调用/技能调用/组装并发）+ 舱壁分池（模型/技能/DB 独立连接池）+ 交互/批量分池 | RunScheduler 分池 + ToolCallOrchestrator 信号量 |
| 确认态隔离 | 并行分支一支 WAITING_APPROVAL 只挂起该支路及依赖后继，其他分支照常；run 聚合状态按节点状态计算 | FlowDriver 依赖判定 + ApprovalController（§3.6） |
| 取消隔离 | 取消按 run 精确作用，令牌沿本 run 调用下沉；取消三态记录 | ExecutionSemantics 取消传播 |
| 版本隔离 | run 钉版本链，发布不影响在途 | RunService version_refs（F1） |

**不在引擎内的三处配套**（防找错位置）：①数据源侧最终强制（查询守卫 SQL 注入器/令牌交换/存储前缀 IAM）在册 20 适配器与数据源层——引擎传身份并触发，数据源验证并过滤；②产物下载鉴权在对象存储与网关层；③身份源（IAM/Keycloak）只被引擎消费。

---

## 7. 与其他册的分工边界

| 关注点 | 本册 | 其他册 |
|---|---|---|
| 技能调用八步/适配器/对账执行 | 调用方 | 册 20 |
| 图定义 Schema/画布转换 | 解释器消费方 | 册 30 |
| 调试面板前端/事件渲染 | 事件生产方 | 册 32 |
| 发布检查器/集成四件套 | 提供版本与运行 | 册 40 |
| 守卫属性来源（IAM） | 消费 GuardContext | 册 10/40 |

---

## 8. 验收用例（本册）

1. Agent 循环终止规则六情形各一条用例全过（含"文本+工具并存"与"连续无进展"）；
2. 四类预算各自触发熔断，答复说明"因预算停止"且已完成步骤可查；
3. 副本 kill -9 后另一副本 30 秒内接管，已落库的模型响应被复用（不重复计费调用），写技能凭幂等键对账不重复执行；
4. 流程并行分支：一支 WAITING_APPROVAL、另一支继续推进至终态，run 聚合状态正确；
5. 长会话（40 轮）触发摘要压缩：裁剪日志可查、工具调用/结果配对完整、下一轮回答仍引用被压缩前的关键事实（经摘要保留）；
6. 断线重连：SSE 带 Last-Event-ID 重连补发不重不漏；页面关闭后任务继续执行至终态；
7. 每次模型调用的 prompt 快照先落库后发送（代码审查+运行时断言双重验证）；
8. 词表按用户授权裁剪：B 用户可见技能清单不含其无权技能（守卫裁词表经组装瀑布生效）。

---

## 9. 复用与借鉴出处（对照《物料清单》O1/O3/O7/O15）

| 组件 | 复用方式 | 开源出处 | 使用/借鉴内容 |
|---|---|---|---|
| 模型客户端 | 直接依赖 | Spring AI ChatClient（**internalToolExecutionEnabled=false**，关闭框架自动工具执行） | 单次调用；循环/守卫/版本/trace 全部自研包裹 |
| 分节注册表/快照投影/严格插值/组装瀑布 | **设计对标实现** | dsh `core/system-prompt`（section/context/variable/waterfall 四概念逐一对标） | 分节序号约定、作用域遮蔽、瀑布改写位=守卫裁词表 |
| 会话日志不变量 | 设计对标 | dsh `core/session`（"Model-visible means logged" 运行时断言） | 总纲第 8 条出处 |
| 固定密度估价 | 设计借鉴 | dsh `llm/token-meter/estimate.ts`（4 字符/token+结构开销） | 估算档实现参照（v3.5 已限定仅展示档） |
| 压缩家族解耦 | 设计借鉴 | dsh `compaction`（修剪器/摘要后端/手动命令三组件分离） | 裁剪第①档独立修剪器 |
| 认领/接管 | 自研+机制 | PostgreSQL `FOR UPDATE SKIP LOCKED`（官方并发模式）+ 租约字段 | 队列语义 |
| 事件补发协议 | 自研+规范 | WHATWG SSE 标准（Last-Event-ID） | 补发/去重 |
| 守卫交集/预算/终止规则/双驱动/隔离矩阵 | 纯自研 | — | 差异化核心 |


---

