# 详细设计 30 —— 编排与画布（技术要求 1.2①③）

> 上位：《智能体构建平台设计-自研路线》v3.6；《模块1详细设计》索引
> 本文六视图：功能视图（§1）→ 组成视图（§2，含实现技术）→ 流程视图（§3）→ 数据视图（§4 **含图定义契约全文**）→ 接口视图（§5）→ 规则视图（§6 校验/表达式/错误处理表）→ 验收（§8）
> 分工：本文定义图定义契约、画布转换、校验器、节点处理器与异常处理；解释器推进机制（就绪判定/调度）的运行时实现见册 31 §3.3
> 技术底座：AntV X6（前端画布）/ Java 21 SpEL 受限子集（表达式）/ networknt json-schema-validator

---

## 1. 功能视图：功能清单与功能设计

编排共 6 个功能：**设计组**（F1~F3，编排者使用）与 **执行组**（F4~F6，引擎使用）。

| 编号 | 功能 | 一句话职责 | 使用者 |
|---|---|---|---|
| F1 | 流程画布 | 可视化拖拽/连线/配置，产出图定义 | 编排者 |
| F2 | 图定义契约 | 图的结构、执行规则、类型规则的唯一权威定义 | 全平台（契约） |
| F3 | 流程校验 | 五组检查：结构/参数/类型/引用/守卫声明 | 发布检查器 |
| F4 | 流程解释器 | 按图定义推进节点执行 | 引擎 |
| F5 | 节点处理器族 | 各类节点的执行实现（可插拔注册表） | 解释器 |
| F6 | 异常处理 | 节点级失败的三分支语义与配置 | 引擎+编排者 |

### 1.1 F1 流程画布

**功能描述**：编排者的主要工作界面。提供节点面板（按节点类型分组展示）、拖拽摆放、连线（带方向与连接桩约束）、节点属性面板（按节点类型的表单 Schema 驱动生成）、整体校验按钮与错误列表（点击定位到节点）。画布保存时产出两份分离的数据：**业务定义 JSON**（进图定义契约）与**画布展示数据**（坐标/尺寸/样式）——两者物理分离存储，改坐标不构成业务变更。
**输入**：编排者操作。**输出**：flow_definition（业务）+ canvas_layout（展示）。
**规则**：连线遵循类型约束（条件节点才允许多出边带条件；普通节点多出边=并行语义，需显式确认）；一期禁环（画布检测到环直接阻断保存）；所有错误可点击定位到对应节点。

### 1.2 F2 图定义契约

**功能描述**：全平台图相关能力的**唯一权威定义**，三部分构成——①结构规则（JSON Schema：节点/边/属性的合法形状）；②执行规则（图如何被推进：并行/汇合/跳过/错误分支/无环约束）；③类型规则（变量作用域/取值语法/空值/数组/转型）。画布、解释器、调试器、发布校验器四方都只对契约负责。契约全文见 §4.2。

### 1.3 F3 流程校验

**功能描述**：五组检查器（结构/参数/类型/引用/守卫声明）按序执行，加上**评测门禁组**（M4：安全用例 100% + 通过率阈值 + 无新增回归）构成六组发布检查；错误分组返回并定位到节点。校验是发布门禁的第一道。
**输入**：草稿图定义。**输出**：分组错误列表（可点击定位）。
**规则**：错误按严重级统一两档——**ERROR：阻断保存为可校验态、阻断发布**；**WARN：允许保存、发布时列入报告不阻断**（与册 40 发布报告的 warnings 对齐，废止"WARN 阻断发布"的旧表述）。

### 1.4 F4 流程解释器

**功能描述**：把图定义编译为内存执行图，按执行规则推进：计算就绪节点集 → 并行提交（虚拟线程）→ 节点处理器执行 → 写输出槽 → 传播 SKIPPED → 计算新就绪集，直至 END/失败/挂起。等待态（确认/外部任务/调试挂起）不占用执行权，恢复后从检查点继续。
**规则**：见 §6.1 就绪与跳过判定、§6.3 错误处理表；解释器对契约负责，不含业务逻辑。

### 1.5 F5 节点处理器族

**功能描述**：每种节点类型一个处理器（SPI 注册表，模块 2 的本体节点=新增处理器）。一期节点族：
`START / END / LLM（组装+网关调用+结构化输出校验）/ SKILL（调技能执行器，带守卫与确认分流）/ CONDITION（表达式求值，多出边择一）/ CODE（受限表达式，无 I/O）/ RETRIEVE（调检索技能）/ CONFIRM（生成确认待办并挂起）`；`AGENT / ONTOLOGY` 预留。
**规则**：处理器无副作用约定——除声明的输出槽外不得修改上下文；所有外部访问走技能执行器或模型网关。

### 1.6 F6 异常处理（1.2③）

**功能描述**：节点级失败语义的配置化。每个节点声明 `errorPolicy`：`onFail`（TERMINATE / ERROR_EDGE / DEFAULT_VALUE）+ `retries`（是否重试由技能契约 gate：只读可重试、写超时进 UNKNOWN 对账）+ `timeoutMs`。异常信息（节点/原因/处理结果）落 node_instance 并按 error_edge 传递 `${error.*}` 变量。
**规则**：重试归属引擎单一层；错误边携带的错误变量在下游节点可引用（供 LLM 节点生成解释）；评测模块的异常类用例复用本节定义。

---

## 2. 组成视图：模块组成与实现技术

### 2.1 组件组成图

```
┌──────────────────── 前端（web/flow-designer）────────────────────┐
│ X6 画布 │ 节点面板 │ 属性面板(表单Schema驱动) │ 校验错误列表 │ 调试联动 │
│ 契约转换器：X6数据 ⇄ 业务定义JSON（双向）                          │
└──────────────────────────┬───────────────────────────────────────┘
                           │ definition + canvas_layout
┌──────────────────────────▼───────────── app-management ──────────┐
│ FlowDesignerService：草稿 CRUD / 保存 / 版本对照                   │
│ FlowValidator：五组检查器（结构→参数→类型→引用→守卫声明）           │
└──────────────────────────┬───────────────────────────────────────┘
                           │ 已发布 flow_definition
┌──────────────────────────▼───────────── engine-runtime ──────────┐
│ FlowDriver（推进与就绪判定，详见册31 §3.3）                        │
│ NodeHandlerRegistry → LLM/SKILL/CONDITION/CODE/RETRIEVE/CONFIRM   │
│ ExpressionEvaluator（SpEL 受限白名单子集）                         │
│ OutputGate / ErrorClassifier（复用册20）                           │
└──────────────────────────────────────────────────────────────────┘
```

### 2.2 组件职责与实现技术

| 组件 | 实现技术 | 职责 |
|---|---|---|
| 画布前端 | AntV X6 2.x + Vue3 形状组件 + 表单 Schema 驱动的属性面板 | 拖拽/连线/配置/错误定位 |
| 契约转换器 | 前端 TS 模块 | X6 数据 ⇄ 业务定义 JSON 双向可逆转换 |
| FlowDesignerService | Spring Boot @Service | 草稿 CRUD、版本对照、发布联动 |
| FlowValidator | 责任链五检查器 | 分组错误输出（ERROR/WARN） |
| FlowDriver | 册 31（虚拟线程+事件推进） | 拓扑执行 |
| NodeHandlerRegistry | SPI 注册表 | 节点类型→处理器 |
| ExpressionEvaluator | SpEL SimpleEvaluationContext + 函数白名单 | 条件与 CODE 节点求值 |
| LLMNodeHandler | 调 ContextAssembler + ModelGatewayClient | 组装/流式/结构化输出校验 |
| SKILLNodeHandler | 调 SkillExecutorClient（册 20） | 守卫/确认分流/信封接收 |

**依赖纪律**：前端转换器与后端校验器**共用同一份图定义 JSON Schema**（契约文件随代码发布并做版本比对，前端加载校验）；NodeHandler 之间禁止互相调用；处理器只能通过上下文读写自己声明的输入输出。

---

## 3. 流程视图：核心流程时序（含异常分支）

### 3.1 画布保存流程

```
编排者拖拽/连线/配置 → 点击保存
  → 前端转换器：X6.toJSON() → 拆分 {definition, canvas_layout}
  → 前端预校验（契约 JSON Schema：结构组快速反馈）
  → POST /admin/flows/{draftId} {definition, canvasLayout}
  → FlowValidator 五组检查 → 全过：落草稿库返回 OK
                          → 有 ERROR：分组返回（结构/参数/类型/引用/守卫），前端错误列表点击定位
```

### 3.2 流程运行推进流程（解释器视角，机制细节见册 31 §3.3）

```
run 就绪 → 编译图定义 → 就绪集=[START]
  循环：就绪集并行提交（虚拟线程）
    LLM 节点：组装（七步）→ 网关流式调用 → 结构化输出校验 → 写输出槽
    SKILL 节点：守卫→确认分流→执行器八步 → 信封→写输出槽
    CONDITION：求值 → 命中分支置 READY，未命中分支 SKIPPED 传播
    CONFIRM：生成待办 → 该支路 WAITING_APPROVAL（其他分支照常）
    失败：按 errorPolicy 三分支（终止 / 错误边[携带 ${error.*}] / 默认值）
  直到 END 或 无可推进节点（全部等待/失败）
```

### 3.3 发布校验流程

见 §1.3 五组检查器；检查器结论分组（ERROR 阻断发布 / WARN 记录），全部通过后固化版本（进入册 40 发布流程）。

### 3.4 异常处理流程（节点失败三分支）

```
节点执行异常（超时/错误语义/Schema 失败）
  ├─ retries>0 且契约允许重试 → 重试（attempt+1，幂等键不变）
  ├─ 重试耗尽 → onFail 判定：
  │    TERMINATE    → run=FAILED（stop_reason=NODE_FAILED）
  │    ERROR_EDGE   → 走声明的错误边（边变量 ${error.message/code/semantic}）
  │    DEFAULT_VALUE→ 输出槽填默认值，后续节点照常
  └─ 全程落 node_instance.error（节点/原因/处理结果）→ 评测异常用例可断言
```

### 3.5 表达式求值流程

解析（白名单语法）→ 取变量（JSONPath，空值传播规则：比较中 null 视为不命中）→ 求值（受限 SpEL：仅字面量/变量/比较/逻辑/算术/白名单函数 `isEmpty() size() toNumber() contains()`）→ 超时 50ms 保护 → 结果。黑名单（new/T()/方法调用/赋值）→ 拒绝并审计。

---

## 4. 数据视图：图定义契约全文 + 存储

### 4.1 存储

```sql
flow_definition(id, app_version_id FK, definition JSONB NOT NULL,     -- 业务定义
                canvas_layout JSONB,                                   -- 画布展示数据（分离）
                definition_sha256 CHAR(64))          -- 业务定义摘要（展示数据不进摘要）
```

### 4.2 图定义契约（v1.0，全文要点）

```json
{
  "definitionVersion": "1.0",
  "trigger": "TASK | CHAT",
  "entry": "n_start",
  "inputs":  [ {"name":"region","type":"STRING","required":true,"desc":"…"} ],
  "outputs": [ {"name":"summary","from":"${n_llm.output.text}"} ],
  "nodes": [
    { "id":"n_start", "type":"START" },
    { "id":"n_fetch", "type":"SKILL",
      "name":"查询日志",
      "skillRef": { "code":"query_logs", "version": 3 },
      "args": { "region":"${input.region}", "from":"${input.from}", "to":"${input.to}" },
      "output": { "logs":"$", "total":"$.total" },
      "errorPolicy": { "onFail":"ERROR_EDGE", "retries":1,
                       "timeoutMs":30000, "errorEdgeTo":"n_err" } },
    { "id":"n_cond", "type":"CONDITION",
      "branches": [ {"when":"${n_fetch.output.total} > 0","to":"n_llm"},
                    {"else":"n_empty"} ] },
    { "id":"n_llm", "type":"LLM",
      "name":"生成解读",
      "promptRef": { "templateCode":"tpl_log_analysis", "version": 2 },
      "modelOverride": null,
      "args": { "logs":"${n_fetch.output.logs}" },
      "output": { "text":"$.text" },
      "outputContract": { "type":"json", "schema":{…} } },
    { "id":"n_confirm", "type":"CONFIRM",
      "confirmRoles": ["admin"], "timeoutMin": 60,
      "onTimeout": "TERMINATE" },
    { "id":"n_code", "type":"CODE",
      "expression": "isEmpty(${n_fetch.output.logs}) ? [] : size(${n_fetch.output.logs})" }
  ],
  "edges": [ {"from":"n_start","to":"n_fetch"},
             {"from":"n_fetch","to":"n_cond"},
             {"from":"n_cond","to":"n_llm","when":"分支命中由 CONDITION 节点内部决定"},
             {"from":"n_llm","to":"n_end"} ],
  "joins": [ {"nodeId":"n_join","semantics":"ALL","skipCountsAs":"PASS"} ],
  "executionRules": { "parallel": true, "maxParallel": 8, "cyclesForbidden": true }
}
```

**执行规则（契约正文）**：
1. **无环**：一期禁环，发布校验做 DAG 检测（Agent 自主循环封装在 AGENT 节点内部，不是图上的环）；
2. **并行**：普通节点多条无条件出边 → 出边目标**并行就绪**；条件分支只能属于 CONDITION 节点；
3. **汇合**：多入边节点声明 `joins.semantics`——ALL（全部成功或 SKIPPED 才就绪，默认）/ ANY（任一入边 SUCCEEDED 即就绪并**锁定获胜分支**；其余入边置 ABANDONED，迟到结果仅留痕不改写输出；ABANDONED 分支若在执行中→请求取消；全部节点到达终态（含 ABANDONED）才允许 run 结束）；
   3a. **ANY 取消范围约束（v3.7 修正）**：ANY 触发时仅可请求取消"**该汇合的专属剩余分支**"——即沿图反向追溯、其全部下游只消费本 ANY 汇合（或其后继）的分支；若被取消分支还有**其他有效消费者**（如 B→K 的 K），**只把该分支对本汇合的输出标记为不再消费（不取消执行）**。发布校验提供两种模式：①默认仅允许"封闭分支"图式使用 ANY（校验器证明剩余分支无共享后继）；②显式声明"允许共享上游"则运行时只标记不取消。**防误伤独立下游 K**（评审 R05 复现图）；
   3b. **汇合终态（v3.7 补）**：全部入边 FAILED → 汇合节点 FAILED（走其 errorPolicy）；FAILED 与 SKIPPED 混合且再无成功可能 → 汇合节点 SKIPPED（继续传播）；杜绝"永远等不到成功"的挂起。**与册 31 就绪判定同源**（分派表见 §6.1，运行时实现以本契约为准）；
4. **SKIPPED 传播**：条件未命中的下游沿边传播 SKIPPED，汇合节点按 `skipCountsAs` 判定；
5. **错误分支**：节点 `errorPolicy.onFail=ERROR_EDGE` 时必须存在对应 `errorEdgeTo` 错误边，边上下文注入 `${error.message/code/semantic}`；TERMINATE 为默认；
6. **并行写禁令**：同一并行批内两个节点写同名输出 → 发布校验失败。

**类型规则（契约正文）**：
- 变量作用域：`input`（运行输入，只读）、`node_X.output`（节点私有输出槽，他人只读）、`context`（业务上下文，只读）；
- 类型系统：STRING/NUMBER/BOOLEAN/OBJECT/ARRAY/NULL；取值语法 JSONPath 子集（点路径 + `[*]`，一期不含过滤表达式）；
- 空值：路径取不到 → null；声明 required 的参数为 null → 运行期错误回灌；
- 数组映射：`[*]` 取出的数组展平后逐元素映射到目标数组参数；**元素数与目标不一致时默认报错（ERROR，防静默丢业务数据），仅在映射配置显式声明截断策略（truncate+保留数）时才允许截断并 WARN**；
- **运行期校验（v3.7 补，评审 R08）**：发布校验拦不住三类运行期错误——①用户本次输入缺失/类型错；②上游返回 null；③第三方响应不符合输出 Schema。三者的统一处理：一律记 `node_instance.error={semantic:PARAM_ERROR|OUTPUT_INVALID, path, reason}`，按节点 errorPolicy 走（终止/错误边/默认值）；**区分**：OUTPUT_INVALID/PARAM_ERROR 属结构错误，**不可通过重试修复**（重试计数不计入或不触发自动重试）；RETRYABLE_FAILURE 才自动重试。执行记录保留校验路径与原因（评测异常用例断言对象）。
- 转型：仅经受限表达式显式函数（`toNumber()/toString()`），禁止隐式强转。

**三数据分离**：`definition`（进摘要、进 trace）与 `canvas_layout`（纯展示）分字段存储——改布局不构成业务变更、不产生新版本差异。

## 5. 接口视图

| 方法 | 路径 | 请求/响应要点 |
|---|---|---|
| GET | `/admin/flows/{draftId}` | `data:{definition, canvasLayout, issues:[]}` |
| PUT | `/admin/flows/{draftId}` | 保存 definition+canvasLayout → 校验 → `data:{errors:[{group,nodeId,severity,reason}]}` |
| POST | `/admin/flows/{draftId}/validate` | 同上，只校验不保存 |
| POST | `/admin/flows/{draftId}/dry-map` | `{sampleInput}` → `data:[{nodeId, resolvedArgs}]`（映射试算） |
| GET | `/admin/node-types` | 节点类型清单（面板渲染与表单 Schema） |

## 6. 规则视图

### 6.1 就绪与跳过判定表（权威定义，与第四部分 §执行规则 3a/3b 同源；v3.7 统一两模式与终态）

| 入边状态组合 | ALL 语义 | ANY·专属分支模式（默认） | ANY·允许共享上游模式（显式声明） |
|---|---|---|---|
| 全部 SUCCEEDED | 就绪 | 首个成功即就绪，其余 ABANDONED | 首个成功即就绪（其余仅标记本汇合不再消费，**不取消执行**） |
| 含 SKIPPED，其余 SUCCEEDED | 就绪（SKIPPED 记通过） | 首个成功即就绪 | 同左 |
| 全部 FAILED | 汇合 FAILED（走 errorPolicy） | 汇合 FAILED | 汇合 FAILED |
| FAILED+SKIPPED 混合，再无成功可能 | 汇合 SKIPPED（继续传播，契约正文口径） | 汇合 SKIPPED（契约正文口径） | 汇合 SKIPPED（契约正文口径） |
| 全部 SKIPPED | SKIPPED（继续传播） | SKIPPED | SKIPPED |
| 部分成功、其余执行中 | 等待 | 首个成功即就绪并锁定获胜分支；**专属剩余分支请求取消**；共享上游分支仅解除本汇合等待，不取消执行 | 同左 |

**ANY 两模式（与第四部分执行规则 3a/3b 同源）**：专属分支模式=发布校验器证明剩余分支无共享后继，取消安全；允许共享上游模式=只标记不取消，需显式声明。**无成功可能时的终态判定对两模式一致**（全失败/失败混合跳过→FAILED/SKIPPED 确定出口，杜绝无限等待）。迟到结果只留痕不改写获胜输出。

### 6.2 表达式白名单

允许：字面量、`${变量}`、比较（==/!=/>/</>=/<=）、逻辑（&&/||/!）、算术、内置函数 `isEmpty() size() toNumber() toString() contains()`。禁止：`new`、`T()` 类型引用、任意方法调用、赋值、循环。超时 50ms 强制中断并审计。

### 6.3 节点错误处理表（1.2③）

| 错误语义 | 默认 onFail | 重试 | 说明 |
|---|---|---|---|
| PARAM_ERROR | 回灌重试计步（Agent）/ 反问或按节点 errorPolicy（流程型） | **发布校验拦不住运行期输入缺失/上游 null**（评审 R08），运行期按 §4.2 运行期校验规则记录并走 errorPolicy |
| NO_PERMISSION | TERMINATE | 否 | 回灌模型向用户解释 |
| QUOTA | TERMINATE | 否 | 提示配额 |
| RETRYABLE_FAILURE | 按 retries | 是（退避） | 只读类常态 |
| RESULT_UNKNOWN | 走对账 | 对账后决策 | 写类技能 |

## 7. 与其他册的分工边界

图执行推进机制 → 册 31 §3.3；调试事件与面板 → 册 32；发布固化动作 → 册 40；检索技能实现 → 册 20（本册只定义 RETRIEVE 节点调用检索技能）。

## 8. 验收用例（本册）

1. 画布编排"SKILL→CONDITION→双分支（LLM/空结束）"→ 保存校验通过 → 运行两分支各走一次，SKIPPED 传播正确；
2. 并行双支 + ALL 汇合：一支失败走错误边 → 汇合按规则放行/阻断两态演示；
3. 并行写同名变量 → 发布校验拦截并列出冲突节点；
4. 图中人为造环 → 保存即被 DAG 检测拦截；
5. 映射引用不存在节点/类型不匹配 → 校验分组列出并可点击定位；
6. 受限表达式调用黑名单方法 → 运行拒绝 + 审计记录；
7. 节点失败按 errorPolicy 三分支各演示一次，`${error.*}` 变量在错误边下游可见；
8. 改画布坐标保存 → definition sha256 不变（三数据分离验证）。

---

## 9. 复用与借鉴出处（对照《物料清单》O2/O10/O15）

| 组件 | 复用方式 | 开源出处 | 使用/借鉴内容 |
|---|---|---|---|
| 画布 | 直接依赖 | AntV X6 2.x + x6-vue-shape | 图编辑/连接桩/序列化/小地图/撤销重做 |
| 表达式求值 | 机制复用 | Spring SpEL（SimpleEvaluationContext + 函数白名单） | 求值引擎；白名单与 50ms 超时自研 |
| 图契约结构校验 | 直接依赖 | networknt json-schema-validator | 图定义结构 Schema |
| 图执行语义 | 读码借鉴 | Dify workflow 源码（SKIPPED 传播/重试策略）、LangGraph4j（状态图抽象） | 语义设计参照 |
| 契约 JSON/转换器/五组检查器/属性面板 | 纯自研 | — | 差异化（图契约=四份前置契约之一） |


---

