# 详细设计 32 —— 调试器（技术要求 1.2② 调试预览）

> 上位：《智能体构建平台设计-自研路线》v3.6；《模块1详细设计》索引
> 本文六视图：功能视图（§1）→ 组成视图（§2）→ 流程视图（§3 五条时序）→ 数据视图（§4）→ 接口视图（§5）→ 规则视图（§6 挂起/事件/Mock/可见性规则表）→ 验收（§8）
> 技术底座：复用引擎（debug run）+ SSE（Last-Event-ID 补发）+ PostgreSQL（挂起状态持久化）+ 虚拟线程 park 挂起
> 铁律：**调试 = 同一引擎的 debug 执行模式**，禁止另写调试执行器；调试不污染正式统计（call_kind/trigger_type 隔离）

---

## 1. 功能视图：功能清单与功能设计

调试器共 7 个功能，使用者是**编排者**（构建中心的调试预览页）。

| 编号 | 功能 | 一句话职责 | 触发 |
|---|---|---|---|
| F1 | 调试运行管理 | 创建/取消/续用调试运行（以草稿为配置） | 编排者 |
| F2 | 全量预览 | 测试输入跑完整流程，总览节点状态/耗时/结果 | 编排者 |
| F3 | 单步调试 | 逐节点挂起-查看-放行，prompt 全文与变量可见 | 编排者 |
| F4 | 断点管理 | 在指定节点设断点，全量模式跑到断点也停 | 编排者 |
| F5 | 技能 Mock | 调试会话内以固定值/缓存值替代真实技能调用 | 编排者 |
| F6 | 调试事件流 | 节点级事件的实时推送与断线补发 | 引擎 |
| F7 | 测试输入管理 | 测试输入的保存/命名/复用 | 编排者 |

### 1.1 F1 调试运行管理

**功能描述**：调试的入口管理器。以**草稿**为配置（调试永远针对草稿——发布版本走正式运行）创建 `trigger_type=DEBUG` 的运行；同一草稿可同时存在多个调试运行（互不干扰，各自独立 run）；支持取消与重开。调试运行与正式运行共用引擎与 trace，但通过 trigger_type 与 call_kind 双标记隔离，统计单列。
**输入**：草稿 ID + 模式（RUN/STEP）+ 测试输入 + Mock 配置。**输出**：debugRunId。
**规则**：草稿未保存的修改先保存才能调试；挂起数全局上限保护。**调试运行创建时冻结草稿快照**（修订号+配置/图定义摘要，run.debug_snapshot）：草稿后续编辑只影响之后新建的调试运行——运行 A 挂起后改稿并新建运行 B，A/B 各用各自快照，回放分别可追溯（评审 R03）。

### 1.2 F2 全量预览

**功能描述**："跑一遍看整体"。用测试输入从头跑到尾（无断点即不停），产出总览：每个节点的状态（等待/运行/成功/失败/跳过）、耗时条、输入输出摘要、最终结果与 token 消耗。失败时定位到出错节点，点击进入该节点的输入输出与错误详情。这是编排者最高频的动作——目标是"保存即跑、跑完即看"。
**规则**：全量模式遇断点也会停（断点优先于模式）；遇确认节点按确认流程挂起（WAITING_APPROVAL，**确认人按节点声明确认角色解析——编排者身份不自动获得审批权**；不具备角色时该次调用以 Mock 替代并明确提示，或转确认中心处理）。

### 1.3 F3 单步调试

**功能描述**："逐节点解剖"。每个节点执行前挂起（WAITING_STEP），编排者可以：查看**组装后的完整 prompt 全文**与装配清单（分账表/裁剪日志——LLM 节点的核心调试物）、查看当前变量上下文快照、查看上游节点输出、单步放行执行本节点、查看执行结果与原始模型返回、继续下一步。**让模型可调试的前提是让 prompt 可见**——这是单步调试最有价值的能力。
**输入**：resume 动作（NEXT_NODE / RUN_TO_END / RUN_TO_BREAKPOINT）+ **挂起定位三元组**（nodeInstanceId + pauseSeq 挂起代次 + requestId）。**输出**：节点级执行明细。
**规则**：resume 定位与幂等——以 `{nodeInstanceId, pauseSeq}` 定位具体挂起点，以 DB 状态 CAS（WAITING_STEP→RUNNING）判定生效，**requestId 去重防延迟重复请求误放后续节点**；挂起超时（默认 30 分钟）自动 CANCELLED；全局同时挂起数上限。**跨副本**：挂起状态权威在 DB，resume 由任意副本处理（不依赖进程内 future——进程内 park 仅作同副本加速路径，跨副本以 DB 状态轮询/事件恢复）；孤儿判定带 owner 与心跳，启动扫描只清理超时孤儿，不误清其他副本的正常调试运行。

### 1.4 F4 断点管理

**功能描述**：在画布节点上设置/取消断点；全量模式运行到断点节点前自动转为挂起（等同单步停在该节点）。
**规则**：断点保存在画布展示数据（canvas_layout），不进业务定义与摘要。

### 1.5 F5 技能 Mock

**功能描述**：调试会话内把指定技能替换为替身——`FIXED`（手填固定 JSON 返回）或 `LAST_REAL`（该技能最近一次真实返回的缓存）。Mock 命中时执行管道第④步短路，真实外部调用不发起；信封标记 mock 来源。
**规则**：仅 DEBUG 运行生效；**Mock 绑定调试运行或应用草稿**（不全局共享）；`LAST_REAL` 记录来源运行与用户，**复用前重查当前用户对该结果的读取授权**，无权退化为 FIXED 或拒绝；正式运行永远忽略 Mock。

### 1.6 F6 调试事件流

**功能描述**：把引擎事件（节点开始/结束/失败/挂起/恢复/终态）实时推给调试面板，并支持断线重连补发：事件持久在 run_event 表（带单调 seq），重连携带 Last-Event-ID 从 DB 补发，前端按 ID 去重。**断线不杀任务**——页面关闭/网络断开，调试运行继续按策略执行（挂起的等待超时兜底）。
**规则**：订阅先校验草稿归属；chunk 增量与事实事件分离。

### 1.7 F7 测试输入管理

**功能描述**：把调试用的输入保存为命名测试项（含变量值），供反复调试与回归复用；M4 评测模块上线后，测试输入可直接"提升"为评测数据集用例（同构数据）。
**规则**：按草稿归属；一个输入项 = 一组完整的流程输入变量值。

---

## 2. 组成视图：模块组成与实现技术

### 2.1 组件组成图

```
┌──────────── 前端（web/debug-panel）────────────┐
│ 调试面板：输入表单 │ 单步控制条 │ 节点检查器     │
│ （画布节点变色联动/变量查看器/prompt全文查看器）  │
└──────────────┬─────────────────────────────────┘
               │ SSE + REST
┌──────────────▼──── app-management / engine-runtime ────┐
│ DebugRunController                                     │
│ DebugController：WAITING_STEP 状态机 + gate 注册表       │
│   （挂起=DB 状态权威+同副本虚拟线程 park 加速；resume=DB 状态 CAS，跨副本可处理） │
│ EventStreamService：run_event 读取 + SSE 推送 + 补发     │
│ SkillMockService：Mock CRUD 与管道第④步判定              │
│ TestInputService：测试输入 CRUD                         │
└────────────────────────────────────────────────────────┘
        ↓ 复用
engine-runtime：RunService（DEBUG 触发）/ drivers / EventStore
```

### 2.2 组件职责与实现技术

| 组件 | 实现技术 | 职责 |
|---|---|---|
| DebugRunController | @RestController | 调试运行生命周期 API |
| DebugController | @Service + ConcurrentHashMap gate 注册表 + VT park + 状态落 DB | 单步挂起/放行/断点/超时清理 |
| EventStreamService | SseEmitter + run_event 表（Last-Event-ID 补发） | 事件推送 |
| SkillMockService | @Service + skill_mock 表 | Mock CRUD 与管道判定 |
| TestInputService | @Service | 测试输入 CRUD |
| 调试面板前端 | Vue3 + X6 联动 + SSE 客户端 | 节点变色/检查器/控制条 |

**依赖纪律**：DebugController 通过引擎既有的 stepController 钩子挂入，不修改 AgentDriver/FlowDriver 主干逻辑；Mock 判定在执行管道段一第④步，MockService 只管配置。

## 3. 流程视图：核心流程时序（含异常分支）

### 3.1 全量预览时序

```
编排者 → 填测试输入 → POST /admin/debug-runs {mode:RUN}
  → DebugController：建 run(DEBUG) → 引擎按草稿执行（无断点直跑）
  → 节点事件经 SSE 推面板（节点变色）→ RUN_FINISHED
  → 面板展示：节点状态/耗时条/最终结果/token 消耗；失败节点可点开看详情
```

### 3.2 单步调试时序（核心）

```
编排者 → POST /admin/debug-runs {mode:STEP} → 引擎执行至首节点前挂起
  引擎：ctx 快照落库 → 事件 WAITING_STEP(seq=n) → SSE 推面板
编排者 → GET /admin/debug-runs/{id}/node/{nid}/prompt   ← 查看 prompt 全文+装配清单
       → GET .../variables                              ← 查看变量上下文快照
       → POST .../resume {action:NEXT_NODE}             ← 放行（幂等：状态 CAS）
  引擎：执行该节点 → NODE_FINISHED(输入输出摘要) → 下一节点前再次挂起
  …… 重复至 END → RUN_FINISHED
```

**prompt 检查器内容**：组装后的 messages 全文、tools 定义、分账表、裁剪日志、实际发送参数——LLM 节点的"黑盒"在这里完全打开。

### 3.3 断线重连与恢复时序

```
页面关闭/网络断开 → SSE 连接断（任务不杀，继续按策略执行）
  重新打开 → GET run 状态（权威在 DB）→ GET /events?Last-Event-ID=seqK
  → 服务端从 run_event 表补发 seq>K 的事件 → 前端去重后追平
挂起中的单步会话重开：状态在 DB → 面板恢复挂起视图 → 继续操作
引擎重启（极端）：启动扫描 WAITING_STEP 孤儿 → CANCELLED（一期）；M4 升级持久挂起
```

### 3.4 Mock 生效时序

```
编排者配置 Mock(FIXED) → 执行管道段一第④步判定命中 → 直接构造信封(status=SUCCEEDED, 来源=MOCK)
  → 不发起真实调用 → 信封与事件标记 mock=true → 面板节点显示 MOCK 角标
```

### 3.5 孤儿清理时序

挂起超时（30 分钟）→ 自动 CANCELLED（stop_reason=DEBUG_TIMEOUT）；每分钟扫描：挂起数超全局上限 → 拒绝新挂起并告警；引擎启动 → 扫描 WAITING_STEP 孤儿（一期）→ CANCELLED。

**分支表**：resume 幂等（已完成挂起点重复放行→返回当前状态不重复推进）；断点与单步叠加（单步模式下断点无额外作用）；确认节点在单步中（转 WAITING_APPROVAL，编排者在确认中心处理，与步骤放行独立）；Mock 与断点叠加（Mock 命中节点不停留，直接过）。

---

## 4. 数据视图

### 4.1 复用表（不新建运行态表）

- `run`（trigger_type=DEBUG）+ `node_instance` + `attempt` + `run_event` —— 全部复用引擎表，`trigger_type=DEBUG` 隔离统计与门禁；
- `skill_mock`（见册 20 §4.3）。

### 4.2 新建表：debug_input（测试输入）

```sql
debug_input(id, tenant_id, app_draft_id, name VARCHAR(128),
            inputs JSONB NOT NULL,        -- 完整流程输入变量值
            created_by/at, updated_by/at)  -- IDX(app_draft_id)
```

### 4.3 挂起状态存储

WAITING_STEP 不建新表：`node_instance.status=WAITING_STEP` + `run.wait_reason` + 上下文快照引用（attempt.request_snapshot_ref 已有）；gate 注册表为进程内加速结构，丢失可由 DB 状态重建。

---

## 5. 接口视图（报文级）

### 5.1 创建调试运行

`POST /admin/debug-runs`
```json
// 请求
{ "appDraftId": 501, "mode": "STEP",
  "inputs": { "region": "A区", "from": "2026-10-12" },
  "mocks": [ {"skillCode":"query_logs","mode":"FIXED","fixedResponse":{"total":0}} ],
  "breakpoints": ["n_llm"] }
// 响应 data
{ "debugRunId": 90001, "status": "RUNNING", "eventsUrl": "/admin/debug-runs/90001/events" }
```

### 5.2 事件流

`GET /admin/debug-runs/{id}/events`（SSE）
```
event: NODE_FINISHED
id: 7
data: {"nodeInstanceId":"ni_90001_2","nodeDefId":"n_fetch","status":"SUCCEEDED","durationMs":430,"outputDigest":"{…摘要…}"}

event: WAITING_STEP
id: 8
data: {"nodeInstanceId":"ni_90001_3","nodeDefId":"n_llm","pauseSeq":8,
       "allowedActions":["NEXT_NODE","RUN_TO_END","CANCEL"],
       "promptRef":"att/90001/2","variablesRef":"ctx/90001/2"}
```
重连：`GET .../events?Last-Event-ID=7` → 从 seq>7 补发。**nodeInstanceId（实例）用于 resume 定位，nodeDefId（图节点编码）仅用于画布定位**（评审 T04）。

### 5.3 查看与放行

| 端点 | 报文要点 |
|---|---|
| `GET /admin/debug-runs/{id}/node/{nid}/prompt` | `data:{messages:[…全文…],tools:[…],manifest:{分账表,裁剪日志}}`（走资源归属检查） |
| `GET /admin/debug-runs/{id}/variables` | `data:{scopes:{global:{…},node_x:{…}}}` |
| `GET /admin/debug-runs/{id}/status` | 权威状态查询（页面刷新/重开/游标过期均先调此接口），响应见下 |
| `POST /admin/debug-runs/{id}/resume` | `{"nodeInstanceId":"ni_90001_3","nodeDefId":"n_llm","pauseSeq":8,"action":"NEXT_NODE","requestId":"uuid"}` → 状态 CAS（WAITING_STEP→RUNNING 且 pauseSeq 匹配）成功 `data:{status:"RUNNING"}`；重复/过期挂起代次 → `data:{deduped:true}`；节点已推进 → `data:{stale:true,nodeInstanceId:"当前挂起节点"}` |
| `POST /admin/debug-runs/{id}/cancel` | 状态 → CANCELLED（DEBUG_TIMEOUT/USER_CANCEL） |
| `PUT /admin/debug-runs/{id}/mocks` | 更新 Mock 配置（挂起中的下一次该技能调用生效） |

**调试状态查询响应（评审 V06）**：

```json
{ "code": "OK", "data": {
    "status": "WAITING_STEP",
    "paused": { "nodeInstanceId": "ni_90001_3", "nodeDefId": "n_llm",
                "pauseSeq": 8,
                "allowedActions": ["NEXT_NODE", "RUN_TO_END", "CANCEL"] },
    "eventsWatermark": 23 } }
```

- 游标过期（`Last-Event-ID` 早于保留窗口）→ 先返回本报文作为状态快照，客户端以 `eventsWatermark` 为新订阅起点续订并去重；
- `paused` 为空且 status=RUNNING → 正常执行中；页面刷新不丢挂起上下文（快照引用在 attempt）；
- requestId 去重记录：**受理 resume 并完成 CAS 时，同事务保存 requestId 并关联原挂起点**（沿用既有 run_event 事件记录即可，不另建专门表）；重连/重启后凭该持久记录恢复去重依据。

### 5.4 测试输入

`GET/POST/DELETE /admin/debug-inputs?appDraftId=` —— 命名保存/列表/删除；`POST .../promote`（M4：提升为评测数据集用例）。

---

## 6. 规则视图

### 6.1 挂起与放行规则表

| 规则 | 内容 |
|---|---|
| 挂起点 | 每节点执行前（STEP 模式）或断点节点前（RUN 模式） |
| **挂起标识持久化** | WAITING_STEP 事件的 `run_event.seq` 即挂起代次 pauseSeq，随事件持久化；node_instance 状态与挂起快照引用同步落库——resume/刷新/跨副本恢复均以此为权威（评审 R04） |
| 放行幂等与定位 | resume 携带 `nodeInstanceId+pauseSeq+requestId`；以 DB 状态 CAS（WAITING_STEP→RUNNING 且 pauseSeq 匹配）判定生效；requestId 去重防延迟请求误放后续节点；**跨副本**：挂起权威在 DB，任意副本可处理 resume（进程内 park 为同副本加速，跨副本以状态轮询/事件恢复） |
| 标识区分 | 接口/事件一律返回 **nodeInstanceId（运行实例）**；nodeDefId（图节点编码）仅供画布定位，不作为放行依据 |
| 挂起超时 | 30 分钟（可配）自动 CANCELLED，stop_reason=DEBUG_TIMEOUT |
| 全局上限 | 同时挂起调试会话数超限 → 拒绝新建调试运行 |
| 断线 | SSE 断开不取消任务；页面重开恢复视图 |
| 孤儿清理 | 引擎启动扫描 + 每分钟超时扫描 |
| 隔离 | 调试运行不进正式统计、不触发评测门禁、不产生 skill_reference |

### 6.2 可见性规则表

| 对象 | 可见范围 | 约束 |
|---|---|---|
| prompt 全文 + 装配清单 | 编排者（本租户、本草稿调试运行） | 走资源归属检查；prompt 中凭据永不出现（凭据只存引用） |
| 变量上下文 | 调试会话内全量 | 含守卫裁剪后的词表视图 |
| 技能真实响应 | 摘要+富载荷 | 与正式一致；Mock 时标注来源 |

### 6.3 Mock 规则表

| 规则 | 内容 |
|---|---|
| 生效范围 | 仅 DEBUG 运行；正式/评测运行忽略 |
| 优先级 | Mock 命中 → 幂等去重仍执行（防并发重复）但真实调用不发起 |
| 统计 | mock=true 单列，不计入技能成功率 |

---

## 7. 与其他册的分工边界

挂起机制与事件持久化 → 册 31；Mock 执行判定 → 册 20 段一④（本册管配置，**Mock 绑定调试运行/草稿，LAST_REAL 复用前重查授权**）；确认中心（WAITING_APPROVAL 待办）→ 册 40（**确认人按节点声明角色解析，编排者身份不自动获得审批权**）；评测提升 → M4 评测模块。

---

## 8. 验收用例（本册）

1. 三节点流程单步走完：每节点 prompt 全文、变量上下文、输入输出全部可见且与正式执行一致（同引擎对比）；
2. 全量预览：并行分支与条件分支的状态/耗时/SKIPPED 展示正确；
3. 断点：全量模式停在断点节点，resume 后继续到 END；
4. 断线重连：SSE 断开 60 秒内重连，事件补发不重不漏，任务未被取消；
5. 挂起超时：30 分钟无操作 → 自动 CANCELLED，stop_reason=DEBUG_TIMEOUT；
6. resume 幂等：连续点三次"放行"→ 节点只执行一次；
7. Mock：配置 FIXED 后该技能零真实调用，面板显示 MOCK 角标，统计 DEBUG 列可见；
8. 调试运行不出现在正式运行统计与技能成功率中（三态隔离验证）；
9. 引擎重启后（一期）孤儿挂起被清理为 CANCELLED 并有明确提示。

---

## 9. 复用与借鉴出处（对照《物料清单》，**新增 O16 建议**）

| 组件 | 复用方式 | 开源出处 | 使用/借鉴内容 |
|---|---|---|---|
| 事件流 | 直接依赖+规范 | SseEmitter + WHATWG SSE 标准（Last-Event-ID 补发/去重） | 补发协议 |
| **prompt 全文查看器** | 直接依赖 | **Monaco Editor（或 CodeMirror 6）只读模式**——检索/高亮/折叠/行号；**本册新增选型，建议物料清单补 O16** | 只读配置，不带编辑能力 |
| 挂起原语 | 自研 | Java 21 虚拟线程 park + CompletableFuture + 状态落 DB | 超时/幂等/孤儿清理 |
| 断线不杀/孤儿扫描/Mock | 纯自研 | — | 业务语义 |
| 调试交互形态 | 借鉴 | Dify 调试预览、ZCode/Codex 单步体验（黑盒观察） | 面板布局与控制条交互 |


---

