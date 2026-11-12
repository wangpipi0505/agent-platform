# 会话事件目录契约（session-events）

C0-2 契约冻结（台账#C0-2），2026-11-13。
来源与裁决：事件类型枚举以**数据库稿《05-数据库设计-20261112》§4.1 的 `session_event.type` CHECK 枚举为唯一权威**（迁移清单唯一权威，共 **27 值**）；事件语义按主方案《02-主方案-基于1109技术要求-20261112》§5.1/§5.4/§5.5/§5.9；读取投影与角色白名单按接口稿《04-接口设计-20261112》§5.1/§5.7。

> **计数说明（X-2 待澄清已登记）**：台账 C0-2 交付描述写"事件目录 25 类"，指不含 `job/registered`、`job/settled` 两类的核心目录；数据库稿 CHECK 枚举含 job 两类共 **27 值**——按裁决顺序（字段以 05 为准）本契约收录 27 类全目录。

## 1. 事件目录（27 类）

### 1.1 生命周期

| 事件 | 负载（data） | 说明 |
| --- | --- | --- |
| `turn/start` | `{turn}` | turn 开始；与 `chat_turn` 创建同事务 |
| `turn/end` | `{turn, reason}` | reason ∈ `completed/aborted/error/max-tokens/interrupted/forked`；中断恢复补写 `interrupted` |
| `step/start` | `{step}` | 一次模型请求+其工具调用为一个 step |
| `step/end` | `{step, ...}` | step 收口 |

### 1.2 消息

| 事件 | 负载 | 说明 |
| --- | --- | --- |
| `system/message` | `{content}` | 系统提示（渲染产物落日志，变更时原位 replace） |
| `user/message` | `{content}` | 用户输入；与 `chat_turn` 写入同事务 |
| `assistant/message` | `{stream, usage}` | 内嵌完整压缩流；**data 上限放宽为 2MB**，其余事件 256KB |
| `assistant/attempt` | `{...}` | 失败/重试/取消尝试记录；**不入模型历史** |

### 1.3 工具

| 事件 | 负载 | 说明 |
| --- | --- | --- |
| `tool/call` | `{callId, name, args}` | 先持久登记（REGISTERED）再分发；`tool/call` 与 `tool/result` 使用同一 `callId`；`(session_id, data->>'callId')` 条件唯一索引（type='tool/call' 且 callId 非空） |
| `tool/result` | `{callId, error?, meta}` | 按权威回执确认；**恢复补写一律 `synthetic=true, semantic='UNKNOWN'`**，模型可见"结果未确认（待对账）"，禁止写 error 表述失败；诊断定位按 `session_id/type/data.callId` 查询 |

### 1.4 请求（D2：模型可见即已记录）

| 事件 | 负载 | 说明 |
| --- | --- | --- |
| `request/header` | `{config, tools}` | **tools=该步采纳集清单**（每工具：toolId、精确版本引用、schemaDigest）；重启后同一步从原 header 恢复工具面，实时复核只收窄 |
| `request/context` | `{provider, model, contextWindow}` | 模型上下文事实；与 header 同事务先于模型出站落库 |

### 1.5 平台扩展（log-only，整值替换或事件对）

| 事件 | 负载 | 说明 |
| --- | --- | --- |
| `plan/mode` | `{active}` | 会话交互模式（不入模型转录）；激活注入 `plan:policy` 提示分节。**任务模式是任务级属性（task.mode），与本事件解耦**（主方案 §5.6） |
| `approval/asked` | `{actionPlanId, ...}` | 审批请求；**关联原 actionPlanId，不另建确认对象** |
| `approval/decided` | `{...}` | 审批决定（闭集 allowed-once/rejected/cancelled/unavailable） |
| `approval/policy` | `{mode}` | mode ∈ `ask/never`；`never` 服务层拦截，重放生效；通道不可用→拒绝出站（fail-closed，`APPROVAL_UNAVAILABLE`） |
| `todo/write` | `{...}` | todo 整表替换事件（三态） |
| `compaction/start` | `{turn}` | 压缩三事件锁协议第一步；锁先落库，崩溃留孤儿锁可检测 |
| `compaction/summary` | `{summary, shadowedRange, shadowedSeqs, tokens, model}` | 压缩摘要；**工具配对不可拆**（压缩区间以配对为边界） |
| `compaction/end` | `{...}` | 压缩收口 |
| `subagent/registered` | `{...}` | 子代理登记（目录=`subagent_catalog` 表投影） |
| `subagent/settled` | `{stopReason, ...}` | 子代理终态回执；stop_reason ∈ `COMPLETED/ABORTED/ERROR/MAX_TOKENS/REFUSAL` |
| `job/registered` | `{...}` | job 事件对（owner 终止连带取消） |
| `job/settled` | `{...}` | job 收口 |
| `inbox/enqueue` | `{queue, dedupeKey, message}` | 入队持久事件；dedupeKey：turn 输入=requestId，注入=来源事件 seq；`agent_inbox` 表是其物化投影，可随时重建 |
| `inbox/consume` | `{queue, dedupeKey, consumedByStep}` | 确认消费事件；**与该步模型请求材料（request/header+request/context）同一事务提交**；补写仅限历史修复/投影重建异常路径，正常运行不触发 |
| `working-directory/change` | `{...}` | 工作目录变更（workspace 语义） |

## 2. 存储与契约（PG 实现，移植 dsh SessionHandle 语义）

- 表：`engine.session_event(session_id, seq, type, occurred_at, data jsonb, surface_op jsonb)`，PK `(session_id, seq)`；INDEX `(session_id, seq) WHERE surface_op IS NOT NULL`；追加不可变（触发器禁 UPDATE/DELETE）。
- **契约四条**：
  1. `read` 只返回连续合法前缀，**不返回撕裂尾**；
  2. `append` 首事件 seq 必须等于已存 next-seq（事务插入天然满足）；
  3. 单写者所有权（per-session 写锁 / `FOR UPDATE`）；
  4. 恢复时补写中断收尾事件：缺失 `tool/result` 一律 `synthetic=true、semantic=UNKNOWN`；未闭合 `turn` 补 `turn/end{interrupted}`。
- **Surface 投影**：`append` 与 `replace{startSeq,endSeq}` 两种操作维护模型可见面；`deriveMessages()` 增量 fold（进程内缓存+按 seq 游标拉增量），只投影消息类事件。
- **写入纪律（同事务边界）**：`chat_turn` 写入与 `turn/start`+`user/message` 同事务；run 受理即回写 `chat_turn.run_public_id`；模型请求前先落 `request/header`+`request/context`（D2）；`inbox/consume` 与请求材料同事务；事务外不做外部 I/O。

## 3. 读取投影与角色白名单（接口稿 §5.1，实现卡 H1-9）

- **普通视图**（surfaceOnly=true 默认）：按会话读权限返回；保留压缩标记摘要；被压缩覆盖的原始消息不参与普通投影；快照与事件流用同一原始 seq 衔接。
- **诊断视图**（surfaceOnly=false）：仅已获会话读权限的 OPERATOR/ADMIN；无诊断权限显式请求返回 403 并记录拒绝原因。
- **字段白名单（USER 本人会话）**：可读 `turn/*`、`user/message`、`assistant/message`、`tool/call`+`tool/result` 展示投影、`plan/mode`、`todo/write`、任务进度类、`compaction/*` 压缩标记摘要（"此处已压缩"+区间+token 计数）；**不可读** `system/message`、`request/header`、`request/context`、工具 Schema 明细与完整原始参数。
- **过滤后游标语义**：`afterSeq` 排他（`seq > afterSeq`）；`nextAfterSeq` = 本页最后扫描到的原始 seq（含被过滤跳过的事件，**不 +1**）；整页全过滤也推进游标；SSE `Last-Event-ID` = 最后交付的原始 seq，重连重扫区间按 seq 幂等去重，不重不漏。

## 4. 变更纪律

- 枚举扩展（新增事件类型）一律走**新迁移追加 CHECK 值**（数据库稿 §6 迁移纪律），并回写本契约目录；禁止散落字面量（实现集中在 `SessionEvents` 常量类，台账 §4.1 代码规范）。
