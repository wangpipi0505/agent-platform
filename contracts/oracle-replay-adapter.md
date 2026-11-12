# dsh 对照 oracle：回放适配器接口设计与行为清单

C0-4 交付物（台账#C0-4），2026-11-13。依据：主方案 **R2**（对照 oracle：可重复、无外部副作用）与 §5.1/§5.2 机制规格。
参考侧：本地固定版本 dsh **0.2.2-rc.2**（`D:\00-Work\02-Application\01-CodeIDE\deepseek-harness`，MIT，不跟踪上游）。
被测侧：engine-runtime harness 内核（Java，H1-x 卡逐步交付）。
用途边界：**仅开发环境影子验证**，不进生产部署；回放适配器隔离真实出站（写操作一律走测试适配器）。

## 1. 回放适配器接口（两侧共用的驱动契约）

对照由"驱动器（driver）"执行同一场景剧本并输出**归一化事件流**（JSONL，UTF-8）；对照器比较归一化流并按 §3 行为清单逐项判定。

```
驱动器命令行契约： <driver-cmd> --scenario <fixture.json> --out <events.jsonl>
  退出码 0=正常完成（含故障注入场景的按剧本恢复完成）；非 0=驱动器自身失败（对照判 FAIL）
输出事件行形状： {"seq":1,"type":"<归一化事件类型>","time":null,"data":{...},"surfaceOp":null}
  time 固定 null——oracle 只比对结构与语义，不比对墙钟；
  seq 各自从 1 起，比对前按 §2 映射表归一化。
```

| 适配器 | 侧 | 实现 | 交付卡 |
| --- | --- | --- | --- |
| dsh 驱动 | 参考侧 | 以固定输入驱动 dsh CLI（Node），解析其 JSONL/事件输出 → 按映射表归一化；模型/工具出站走 dsh 的桩定/回放机制 | H1-10 前完成 |
| java 回放驱动 | 被测侧 | 调 engine-runtime 回放入口（测试适配器替代真实出站；**禁真实出站**，台账 §4.1 测试规范） | 随 H1-1..H1-6 交付 |
| stub 驱动 | 骨架自证 | 内置于 `scripts/oracle/run-oracle.mjs`：按 fixture 直接产出归一化事件流，用于骨架可运行性验证，不代表任何真实行为 | C0-4（本卡） |

## 2. 事件映射表（dsh → session_event）

映射表唯一文件：`scripts/oracle/event-mapping.json`（本契约为其语义说明）。比对允许**粒度差异**：行为清单逐项一致为通过，粒度差异记录映射说明即可判定；**语义差异记缺陷**（R2 差异判定口径）。

| dsh 侧（概念） | session_event | 映射说明 |
| --- | --- | --- |
| turn 开始/结束 | `turn/start` / `turn/end{reason}` | reason 词汇按 fixture 映射子表转换 |
| 系统提示/用户输入/助手消息 | `system/message` / `user/message` / `assistant/message{stream,usage}` | assistant 压缩流整体比对，usage 差异容忍（模型计量口径不同） |
| 工具调用/结果 | `tool/call{callId,name,args}` / `tool/result{callId,error?,meta}` | callId 两侧各自生成，按调用序对齐；配对性按 callId 检查 |
| 模型请求头 | `request/header{config,tools}` / `request/context{provider,model,contextWindow}` | tools=采纳集清单（toolId+版本+schemaDigest）；dsh 无持久 header 时由其请求记录投影 |
| 计划模式 | `plan/mode{active}` | — |
| 审批 | `approval/asked` / `approval/decided` / `approval/policy{mode}` | — |
| 压缩 | `compaction/start` / `compaction/summary{summary,shadowedRange,...}` / `compaction/end` | 遮蔽区间以配对边界核对 |
| 子代理 | `subagent/registered` / `subagent/settled{stopReason}` | stop_reason 词汇映射 |
| 输入入队/消费 | `inbox/enqueue{queue,dedupeKey,message}` / `inbox/consume{queue,dedupeKey,consumedByStep}` | dsh 无独立 inbox 事件时按其输入处理序列投影，粒度差异允许 |
| todo / job / 工作目录 | `todo/write` / `job/registered`+`job/settled` / `working-directory/change` | 场景按需启用 |

## 3. 行为清单（R2 四项，对照判定唯一口径）

| # | 行为 | 判定方法（对归一化流） | 证据 |
| --- | --- | --- | --- |
| B1 | **工具可见性与实际执行一致** | 每步 `request/header.tools` 采纳集 ⊇ 该步全部 `tool/call` 的 name 且版本一致；未声明工具不得出现调用 | 对照报告 B1 行 |
| B2 | **工具调用与结果成对** | 每个 `tool/call`（按 callId）恰有一个 `tool/result`；压缩遮蔽区间不拆配对（startSeq..endSeq 边界不含半对）；补写结果必须 `synthetic=true, semantic=UNKNOWN` | 对照报告 B2 行 |
| B3 | **已完成产物复用** | 故障恢复场景中，恢复后不得重复出现已完成步骤的 `tool/call`（同幂等键）；复用事实体现在恢复事件/投影 | 对照报告 B3 行 |
| B4 | **恢复后事件序列等价** | 恢复点后两侧归一化事件序列结构等价（类型/关键键一致；文本类字段容忍措辞差异）；受理次数恒 1（外部受理型场景） | 对照报告 B4 行 |

差异记录：粒度差异 → 映射说明登记（通过）；语义差异 → 缺陷台账（阻断 H1-4 影子验证门）。风险触发器：影子验证差异超 3 项语义级即启动缓冲重排（台账 §8）。

## 4. 场景素材三清单（fixture 字段与初始场景集）

fixture 形状见 `scripts/oracle/fixtures/example-recover-tool-call.json`；清单字段如下。

### 4.1 清单一：桩定模型响应（stubs.modelResponses）

| 字段 | 说明 |
| --- | --- |
| `step` | 触发步号（1 起） |
| `type` | `MESSAGE`（普通回复）/ `TOOL_CALLS`（含工具调用）/ `SCHEMA_VIOLATION`（畸形输出，供 A15b 类场景） |
| `content` / `toolCalls` | 文本或 `[{name,args}]` |
| `usage` | `{inputTokens, outputTokens}` 校准值 |

### 4.2 清单二：桩定工具回执（stubs.toolReceipts）

| 字段 | 说明 |
| --- | --- |
| `callKey` | 对应 `toolCalls[].name`+调用序 |
| `outcome` | `SUCCEEDED`（含 result）/ `FAILED`（结构化业务错误，可带 retryable）/ `ACCEPTED_NO_RECEIPT`（外部受理后回执丢失）/ `UNREACHABLE`（适配器不可达） |
| `result` / `error` | 成功载荷或结构化错误（五类：参数错误/无权限/资源不足/可重试/结果未知） |

### 4.3 清单三：故障注入点（faultInjection）

| 注入点 | 语义 | 对应验证 |
| --- | --- | --- |
| `AFTER_EXTERNAL_ACCEPT_BEFORE_RECEIPT` | 外部已受理、回执未落即崩溃 | M1-①/§5.8：受理次数恒 1 |
| `AFTER_CLAIM_BEFORE_CONSUME_COMMIT` | inbox 认领后、consume 事务提交前崩溃 | 两阶段消费：重投幂等 |
| `AFTER_CONSUME_COMMIT_BEFORE_RESULT` | 提交后崩溃 | 不重复消费、不补写 |
| `MID_COMPACTION` | 压缩三事件之间崩溃 | 孤儿锁检测、配对边界 |
| `MODEL_SWITCH_WINDOW` | 切换模型窗口检查中失败 | `MODEL_SWITCH_COMPACTION_FAILED` |
| `SAME_NAME_REPLACE` | 同名技能替换/撤权后分发 | A10 四场景（撤权/同名替换/同步重试/恢复） |

动作统一为 `CRASH`（进程中断，恢复从检查点/事件日志继续）；恢复过程由被测驱动按各自机制执行，oracle 不注入恢复逻辑。

### 4.4 初始场景集（骨架期先立 1 例，H1-10 前补齐）

| 场景 | fixture | 覆盖行为 | 优先级 |
| --- | --- | --- | --- |
| 外部受理后回执丢失 → 重启恢复 | `fixtures/example-recover-tool-call.json` | B2/B4 + 受理次数恒 1 | **P0（M1-① 同款）** |
| 多步工具调用 + 压缩触发 | 待 H1-4 后补 | B1/B2 | P1 |
| 同名替换/撤权恢复 | 待 H1-6 后补 | B3/B4 | P1 |
| 双分支计划并行与汇合 | 待 T2-7 后补 | B4 | P2 |

## 5. 骨架可运行性（本卡交付）

`scripts/oracle/run-oracle.mjs`：对照器骨架，**stub 驱动模式下今天即可端到端运行**（`node scripts/oracle/run-oracle.mjs --scenario scripts/oracle/fixtures/example-recover-tool-call.json`，无外部依赖、无真实出站）。输出对照报告（B1-B4 逐项 + 序列 diff）；两侧引用/被测真实驱动按上表替换 stub 后进入影子验证。骨架判定仅证明管线可用，不构成任何行为通过结论。
