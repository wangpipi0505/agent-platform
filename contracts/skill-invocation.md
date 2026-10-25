# 详细设计 20 —— 技能中心（技术要求 1.4①②③④）

> 上位：《智能体构建平台设计-自研路线》v3.6；《模块1详细设计》索引（需求映射与验收汇总）
> 本文六视图：**功能视图（§1）→ 组成视图（§2，含实现技术）→ 流程视图（§3 时序与分支）→ 数据视图（§4）→ 接口视图（§5）→ 规则视图（§6 管道分支表/§7 状态机）**；附录含写作规范与验收用例。
> 技术底座：Java 21 / Spring Boot 3.x / PostgreSQL / Redis / RestClient / Resilience4j / MCP Java SDK / K8s Java Client。

---

## 1. 功能视图：功能清单与功能设计

技能中心共 8 个功能，分两组：**管理组**（F1~F4，门户使用者）与 **执行组**（F5~F8，引擎运行时）。

| 编号 | 功能 | 一句话职责 | 使用者 |
|---|---|---|---|
| F1 | 技能注册与上架 | 六维契约登记→连接测试→选型验收→不可变上架 | 技能管理员 |
| F2 | 版本管理 | 修改生成新版本、旧版与引用永久保留 | 技能管理员 |
| F3 | 技能库与引用 | 多维检索、引用关系双向查询 | 编排者/管理员 |
| F4 | 调用统计 | 按技能/版本/时间/三态（正式|调试|评测）统计 | 管理员/编排者 |
| F5 | **技能执行** | 八步管道：校验→守卫→幂等→Mock→适配执行→验收→封箱→落库 | 引擎（被调用） |
| F6 | 对账 | 写操作"结果未知"的核实与采纳 | 引擎（被调用） |
| F7 | Mock 调试 | 调试会话的技能替身 | 引擎+编排者 |
| F8 | 引用维护 | 发布时写引用关系，支撑双向查询与停用拦截 | 发布检查器 |

### 1.1 F1 技能注册与上架

**功能描述**：技能是平台调用外部能力的唯一入口，注册过程本质是"**给能力写一份模型能读懂的简历，并验证两件事**"——接口真实可达（连接测试）、模型真的会在该用时选它（选型验收）。上架采用**不可变版本制**：一旦上架，契约内容永久冻结，任何修改都以新版本形式存在——这保证了所有历史引用永不失效，也让"技能说明书质量差导致模型选错"的问题只能在验收阶段被拦住，不会流进生产。

**输入**：六维契约表单（或 OpenAPI 文档自动导入的草稿）。
**输出**：已上架的不可变版本（version_no + sha256 + published_at）。
**规则**：①说明书必填且按附录 A 写作规范（能力句/适用/不适用/参数示例）；②查询语义技能必须声明守卫属性，缺则阻断；③只读与副作用互斥校验；④选型验收命中率 ≥80% 才允许上架（门禁）。
**异常**：编码重复（0001）/Schema 非法（1002）/验收未过（2003）/引用未清（2004）——见 §5 错误码。

### 1.2 F2 版本管理

**功能描述**：管理技能契约的演进，核心承诺是"**引用永不漂移**"。流程与智能体引用的是明确的版本号，发布新版本不影响任何在用引用；升级永远是消费方的显式动作，旧版本永久保留、随时可回退。对技能提供方的体验是"每次修改自动成为新版本"，对消费方的承诺是"我引用的版本不变，除非我主动升级"。这一条是平台"版本链贯穿"原则在技能层的落点。

**输入**：对已发布版本的修改请求。**输出**：新版本草稿。
**规则**：修改已发布=复制新版本草稿；**新版本发布时旧版本转 ARCHIVED（引用关系保留，旧引用仍可执行）**；草稿态版本废弃即删除（从未发布，无引用）；向引用方推送"可升级"通知（不自动升级）。

### 1.3 F3 技能库与引用

**功能描述**：全平台技能的检索目录与引用关系中枢，回答两个日常问题——"**有哪些技能可用**"（多维检索：名称/类别/标签/状态）与"**这个技能被谁在用**"（引用关系双向查询）。引用关系在智能体发布时自动写入 `skill_reference`，是三个治理动作的数据基础：**停用拦截**（被引用的技能不许停，并列出引用方）、**影响分析**（技能要变更，先看波及哪些智能体）、**升级通知**（有新版本，提醒所有引用方）。

**输入**：检索条件 / 技能 ID。**输出**：技能清单 / 引用方清单。
**规则**：引用关系发布时写入、双向查询免扫描；被引用时 disable 被拒绝（2004）并附引用清单。

### 1.4 F4 调用统计

**功能描述**：技能的用量与健康台账。按技能/版本/时间统计调用次数、成功率、耗时分布与错误语义分布，并把**正式调用、调试调用、评测调用三态分开记账**——既保证生产统计的纯净（调试与评测不污染成功率），又能回答治理问题："调试花了多少资源""哪个技能失败率在升高"。统计是技能治理的数据依据：高频技能优先优化，高失败技能优先排障，长期零调用的技能进入下架评估。

**输入**：执行器统一埋点（StatRecorder 异步批量写入）。**输出**：分时段聚合报表。
**规则**：三态（FORMAL/DEBUG/EVALUATION）分列；按技能/版本/时间多维聚合；日报预聚合。

### 1.5 F5 技能执行（核心）

**功能描述**：平台真正的"动手"组件，也是**全平台唯一允许触碰外部系统的组件**，因此是安全闸门最密集的地方。它接收引擎的调用指令——模型决定调用哪个技能、填什么参数——经过**三段八步管道**：执行前把所有"该不该"判掉（参数过没过 Schema、有没有权限、是否重复发起、调试是否该用替身），执行中真正调用外部能力（HTTP 服务 / MCP 工具 / K8s 算法 Job，多技能可并行），执行后把结果验收（大小硬闸流式转存、输出断言、错误分类）、盖归属链戳、封箱落库——最终产出一个归属链清晰的 `ResultEnvelope` 交给上下文组装器回灌模型。错误也是结果：失败按五类错误语义封装回灌，让模型有机会自我纠正而不是直接崩给用户。

**输入**：`SkillInvocation`（技能版本 + 参数 + 身份三件套 + 幂等键 + 策略 + 取消令牌）。
**输出**：`ResultEnvelope`（归属链 + 四份内容 + 状态）。
**规则**：八步管道（§6 分支全表）；守卫链默认拒绝；写超时进结果未知对账；大小硬闸流式转存；Mock 只在调试会话生效。

### 1.6 F6 对账

**功能描述**：处理分布式世界最麻烦的问题——"**请求发出去了，但不知道成没成**"。写操作技能超时后，结果处于"结果未知"态：不能当失败处理（可能已经生效，盲目重试就重复执行），也不能当成功（可能根本没执行）。对账器按技能注册时声明的对账配置（查询端点/状态接口/完成标记路径）核实外部事实，结论三选一闭环：**未生效→允许重试；已生效→采纳结果；仍未知→保持 UNKNOWN 转人工**。它让"写操作超时"从一场需要人肉排查的事故，变成一次系统内可自动恢复的正常流程；配合幂等键，保证"恰好一次"的业务效果。

**输入**：技能契约 reconciliation 配置 + 幂等键。**输出**：对账结论与（若已生效）结果信封。
**规则**：对账前先验签与归属校验；对账本身也计 attempt 留痕；多次对账仍未知 → 转人工并告警。

### 1.7 F7 Mock 调试

**功能描述**：调试会话的技能替身。编排者在调试流程编排时，往往不想真的调用昂贵、缓慢或有副作用的外部能力（比如每次调试都真跑一遍算法 Job）——Mock 让指定技能在调试会话中返回**固定结果**（手填 JSON）或**最近一次真实返回的缓存**。它使"先验证编排逻辑与数据流转、再接真实能力"成为标准调试姿势；Mock 调用单独记账（call_kind=DEBUG），不污染生产统计。

**输入**：调试运行标记 + Mock 配置。**输出**：Mock 信封（标 mock 来源）。
**规则**：仅 call_kind=DEBUG 生效；正式运行永远忽略 Mock。

### 1.8 F8 引用维护

**功能描述**：在智能体发布时自动登记"**哪个智能体版本用了哪个技能版本**"，为三个治理动作提供数据：**停用拦截**（被引用的技能不许停，并列出引用方）、**影响分析**（技能要变更，先看波及面）、**升级通知**（技能有新版本，提醒所有引用方）。引用是发布动作的自动副产品，不依赖人工登记。

**输入**：应用发布事件。**输出**：skill_reference 记录。
**规则**：按技能版本粒度登记（不是技能粒度）；双向查询。

---

## 2. 组成视图：模块组成与实现技术

### 2.1 组件组成图

```
┌──────────────────────── skill-registry 服务 ────────────────────────┐
│ 入口层                                                              │
│  SkillController(门户API)      SkillExecuteController(引擎内部调用)   │
├─────────────────────────────────────────────────────────────────────┤
│ 应用服务层                                                          │
│  SkillRegistryService   注册/草稿/发布/版本（F1 F2）                  │
│  SkillAcceptanceService 选型验收（F1 门禁，调 model-gateway）          │
│  SkillQueryService      库/引用/统计查询（F3 F4）                     │
│  SkillMockService       Mock 管理（F7）                              │
│  SkillExecutor          八步管道编排者（F5 F6 F8）★核心               │
├─────────────────────────────────────────────────────────────────────┤
│ 领域组件                                                            │
│  GuardChain(守卫链) │ IdempotencyService(幂等/绑定) │ Reconciler(对账) │
│  ResultEnveloper(信封/归属链) │ AdapterRegistry(适配器注册表)          │
│  ErrorClassifier(错误语义映射) │ OutputGate(大小硬闸/流式转存)          │
├─────────────────────────────────────────────────────────────────────┤
│ 适配器层（AdapterRegistry 管理，可插拔）                              │
│  HttpAdapter  │ McpAdapter  │ AlgoAdapter(K8s Job) │ OntologyAdapter(预留) │
├─────────────────────────────────────────────────────────────────────┤
│ 支撑                                                                │
│  SchemaValidator(networknt) │ SkillMockStore │ StatRecorder │ Flyway │
└─────────────────────────────────────────────────────────────────────┘
          ↑被调用                          ↓调用外部
   AgentDriver / FlowDriver        文档服务 / 本体API / 算法K8s Job / MCP 工具
```

### 2.2 组件职责与实现技术（用什么实现）

| 组件 | 实现技术 | 职责 |
|---|---|---|
| SkillController / SkillExecuteController | Spring Boot @RestController | 门户 API（§5）与引擎内部调用入口 |
| SkillRegistryService | @Service + Flyway 管表 | 六维契约 CRUD、草稿/发布双轨、版本号分配 |
| SkillAcceptanceService | @Service + RestClient（调模型网关） | 构造选型 prompt→模型选型→逐题比对→报告与门禁 |
| **SkillExecutor** | @Service + 虚拟线程（并行调用） | 八步管道顺序编排，短路返回 |
| GuardChain | 责任链（三个 Handler） | 参数校验后置的权限重校验/交集/出域 |
| IdempotencyService | PostgreSQL（SKIP LOCKED）+ Redis 计数 | 幂等键登记/查询、并发重复识别 |
| Reconciler | RestClient + JSONPath | 按 reconciliation 配置核实外部事实 |
| ResultEnveloper | 纯 Java | 归属链盖章、四份内容组装 |
| OutputGate | 流式 InputStream 包装器（CountingInputStream） | 接收即计数，超阈边收边转 MinIO |
| ErrorClassifier | 规则表（JSONB 配置） | HTTP 状态/业务码 → 五类错误语义 |
| HttpAdapter | Spring RestClient + Resilience4j（超时机制） | 参数组装+守卫注入+调用 |
| McpAdapter | MCP Java SDK Client | 外部 MCP 工具列取/调用 |
| AlgoAdapter | K8s Java Client | Job 确定性命名提交/状态轮询/日志 |
| StatRecorder | 异步批量写（内存队列+定时刷盘） | stat_invocation 埋点 |

**依赖关系**：SkillExecutor 依赖 GuardChain/IdempotencyService/AdapterRegistry/Reconciler/ResultEnveloper；GuardChain 依赖 GuardResolver（册 31）；HttpAdapter 依赖 ErrorClassifier 与 OutputGate。**禁止**：适配器互相调用、领域组件直接依赖适配器实现类（只依赖接口）。

## 3. 流程视图：核心流程时序（含异常分支）

### 3.1 技能注册上架流程（F1 全流程）

```
编排者 → 向导(六步表单) → SkillRegistryService.createDraft()            【DRAFT】
  → [可选] swagger 导入：SkillRegistryService.importOpenApi() → 草稿+告警清单
  → 补说明书/守卫属性 → 保存契约（Schema 校验，失败列 errors）           【DRAFT】
  → 连接测试（HttpAdapter.ping）→ ok？
        ├─ 否 → 修改端点/凭据 → 重测
  → 提交验收：SkillAcceptanceService.runAcceptance()
        → 逐题：构造选型 prompt → model-gateway → 解析 tool_call → 比对
        → 命中率 ≥80%？ ├─ 否 → 回 DRAFT，返回逐题报告（不扣次数）        【DRAFT】
                        ├─ 是 → 发布：写 PUBLISHED + sha256 + 引用登记    【PUBLISHED】
```

### 3.2 技能执行完整时序（F5 成功路径 + 分支位）

```
AgentDriver      SkillExecutor     GuardChain     Idempotency    HttpAdapter    外部服务     DB(trace)
   │ execute(inv)  │                  │              │              │             │           │
   │──────────────>│ ①参数Schema校验   │              │              │             │           │
   │               │ ②权限重校验──────>│(交集/出域)    │              │             │           │
   │               │   放行<──────────│              │              │             │           │
   │               │ ③原子登记调用(登记先行)────────>│              │             │           │
   │               │   冲突→等待既有;成功→取得执行权 │              │             │           │
   │               │ ④(调试)Mock判定    │              │              │             │           │
   │               │ ⑤守卫注入+组参─────────────────────────────────> │──HTTP POST──>│          │
   │               │    接收计数(有界缓冲)<──────────────────────────│<──200 响应───│           │
   │               │ ⑥大小/Schema/错误分类验收            │              │             │           │
   │               │ ⑦信封封装(归属链)    │              │              │             │           │
   │               │ ⑧attempt/统计/事件───────────────────────────────────────────────────────────>│
   │ <─Envelope────│                  │              │              │             │           │
```

**分支位**（时序中的岔路，全部有信封出口）：

| 岔路 | 条件 | 走向 |
|---|---|---|
| ① 参数错 | Schema 校验失败 | 信封(FAILED, PARAM_ERROR)→回灌模型重试计步；连续 2 次→反问用户，**不进⑤** |
| ② 无权限 | 交集为空 | 信封(FAILED, NO_PERMISSION,"用户无该范围权限")→回灌模型向用户解释，**不进⑤** |
| ③ 重复调用 | 幂等键已有 DONE 记录 | 直接采纳既有信封，**不进⑤**；RUNNING→等待其终态 |
| ⑤ 写超时 | HTTP 超时且 sideEffect=true | 状态 UNKNOWN→**进 3.3 对账时序**，不自动重试 |
| ⑤ 只读超时/5xx | readonly | RETRYABLE_FAILURE→按矩阵退避重试（计 attempt） |
| ⑥ 结果过大 | 超 output_max_bytes | 边收边转 MinIO→信封带 artifact 引用+摘要 |
| ⑥ 业务错误 | 命中 error_semantics 映射 | 按映射语义（如 QUOTA→直接失败并提示配额） |

### 3.3 异步回调与对账时序（SUBMIT_QUERY / ALGORITHM，含迟到场景）

```
AgentDriver   SkillExecutor  AlgoAdapter   external_job_binding   K8s/外部      DB        Reconciler
   │ 登记调用+绑定(登记先行,唯一约束仲裁) │              │                  │             │            │
   │────────────>│  取得执行权后创建Job(Job 名=alg-hash(幂等键))────>│             │            │
   │             │  绑定置 SUBMITTED────────────>│                  │             │            │
   │             │  attempt1 状态:UNKNOWN/执行中   │                  │   (副本A失联/租约到期)      │
   …… 副本B 接管（attempt2）：先查绑定表 → Job 已存在 → 轮询，不新建 Job │            │
   │             │                │              │<──回调(结果,签名)──│             │            │
   │             │ 验签→核对 job_id↔run 归属→去重 │                  │             │            │
   │             │ 对账：donePath 达 DONE？        │                  │             │            │
   │             │   是→原子提交逻辑结果一次──────────────────────────────────────>│           │
   │             │   attempt1 迟到响应若后到→验签留痕参与对账，不再提交第二次                      │
```

**不变量**：外部事实以对账结论为准，attempt 新旧只是执行权记录；逻辑结果只提交一次。

**登记先行修正（评审 P1-2）**：调用记录与外部绑定在**发起创建前**原子登记（唯一约束仲裁并发），创建成功才置 SUBMITTED——"提交后登记"仅是旧时序示意，实现必须登记先行。

### 3.4 Mock 调试时序（F7）

调试运行 + skill_mock.enabled → 段一第④步直接构造信封（status=SUCCEEDED，来源=FIXED/LAST_REAL）→ 跳过⑤⑥ → 落库标 mock → 统计单列。真实外部服务零调用。

### 3.5 版本升级与引用保持流程（F2）

新草稿 → 验收 → 发布 v2（新不可变记录）→ 旧 v1 转 ARCHIVED；`skill_reference` 中引用 v1 的智能体：运行仍钉 v1（运行钉版本）；门户向编排者推"可升级"通知 → 编排者显式改引用 v2 → 重新发布应用。**v1 永不删除**。

### 3.6 统计埋点流程（F4）

SkillExecutor 段三⑧ → StatRecorder 入内存队列（有界）→ 定时批量刷 `stat_invocation` → 日报聚合任务按天汇总 → 报表查询。队列满策略：丢弃前先落应急文件（不丢数据），告警。
**可靠性（v3.7 补）**：统计可从持久事实**重建**——tool_call 表即权威调用事实，聚合任务崩溃/丢数后可全量重算；**口径**：计数单位=逻辑调用（tool_call 去重，不含 attempt 重试），UNKNOWN 对账后按终态归类；重试不计为新调用。

---

## 4. 数据视图：全字段 DDL

> 公共字段各表默认：`id BIGINT PK`、`tenant_id VARCHAR(64)`、`created_by/created_at/updated_by/updated_at`、`deleted BOOLEAN DEFAULT false`。以下省略。

### 4.1 skill —— 技能主表（当前态指针）

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| code | VARCHAR(64) | UK(tenant_id, code) WHERE deleted=false | 业务编码，对外标识 |
| name | VARCHAR(128) | NOT NULL | 展示名 |
| category | VARCHAR(64) | 字典 | 数据查询/算法/文档/系统操作/… |
| tags | TEXT[] | | 检索标签 |
| status | VARCHAR(16) | NOT NULL | DRAFT / PUBLISHED / DISABLED（状态机见 §7） |
| current_version_id | BIGINT | FK→skill_version.id | 当前发布版本指针；草稿态为 NULL |

索引：`UK(tenant_id, code)`、`IDX(category, status)`、GIN(tags)。

### 4.2 skill_version —— 技能版本（不可变）

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| skill_id | BIGINT | NOT NULL | |
| version_no | INT | UK(skill_id, version_no) | 技能内递增，从 1 |
| status | VARCHAR(16) | | DRAFT / PENDING_ACCEPTANCE / PUBLISHED / ARCHIVED |
| **①基础** | | | |
| description | TEXT | NOT NULL | 面向模型的说明书（附录 A 规范） |
| invocation_kind | VARCHAR(16) | NOT NULL | SYNC / SUBMIT_QUERY / CALLBACK |
| adapter_type | VARCHAR(16) | NOT NULL | HTTP / MCP / ALGORITHM |
| endpoint | VARCHAR(512) | | HTTP 完整 URL；MCP 服务地址；ALGO=任务模板编码 |
| http_method | VARCHAR(8) | | GET/POST（仅 HTTP） |
| auth_ref | VARCHAR(128) | | 凭据引用（密文存取） |
| **②资源约束** | | | |
| timeout_ms | INT | NOT NULL DEFAULT 30000 | 单次尝试超时（不含对账） |
| concurrency_limit | INT | DEFAULT 10 | 技能级并发上限（Redis 信号量） |
| output_max_bytes | INT | NOT NULL DEFAULT 1048576 | **输出硬闸**：超限流式转 MinIO，上下文只进摘要 |
| **③参数与输出 Schema** | | | |
| input_schema | JSONB | NOT NULL | JSON Schema Draft 2020-12 |
| output_schema | JSONB | | 输出结构断言用 |
| **④执行属性** | | | |
| readonly_flag | BOOLEAN | NOT NULL DEFAULT false | true→risk_level 推导 READ_ONLY |
| side_effect | BOOLEAN | NOT NULL DEFAULT false | |
| idempotent | BOOLEAN | NOT NULL DEFAULT true | 写技能必须 true 才允许自动重试 |
| cancellable | BOOLEAN | NOT NULL DEFAULT true | |
| **⑤风险与守卫** | | | |
| risk_level | VARCHAR(16) | NOT NULL | READ_ONLY / CONFIRM_REQUIRED（推导，可上调不可下调） |
| guard_attrs | TEXT[] | | 守卫属性声明；**查询语义技能缺失此项→发布阻断** |
| reconciliation | JSONB | | 写技能对账配置：`{"method":"GET","endpoint":"…/{idempotencyKey}","donePath":"$.status","doneValue":"DONE"}` |
| **⑥错误语义映射** | | | |
| error_semantics | JSONB | NOT NULL DEFAULT '[]' | `[{"match":"HTTP 403","semantic":"NO_PERMISSION"}]`；五类：PARAM_ERROR / NO_PERMISSION / QUOTA / RETRYABLE_FAILURE / RESULT_UNKNOWN |
| **⑦返回内容四份** | | | |
| return_spec | JSONB | | `{"modelSummary":{"mode":"PATH|TEMPLATE",…},"structuredPath":"$.data","fileRefPaths":[],"cardTemplate":"…"}` |
| **发布** | | | |
| sha256 | CHAR(64) | | 契约序列化摘要 |
| published_at / published_by | | | |

索引：`UK(skill_id, version_no)`、`IDX(status)`。

### 4.3 其他表

```sql
skill_reference(id, tenant_id, skill_version_id FK, app_version_id FK,
                app_code, referenced_at)             -- 引用关系（发布时写入）
skill_mock(id, tenant_id, skill_version_id, mode FIXED|LAST_REAL,
           fixed_response JSONB, last_real_ref BIGINT NULL,
           owner_type DEBUG_RUN|APP_DRAFT, owner_id BIGINT,   -- Mock 绑定调试运行或应用草稿（非全局共享）
           last_real_source_run_id BIGINT NULL,
           last_real_principal VARCHAR(64) NULL,              -- LAST_REAL 来源（复用前重查授权）
           enabled BOOLEAN)
skill_acceptance(id, skill_version_id, created_at,
                 cases JSONB,       -- [{question, expectSelected, expectArgs}]
                 report JSONB)      -- [{question, selected, argsOk, pass}]
stat_invocation(id, tenant_id, skill_version_id, run_id, principal_id,
                call_id BIGINT NOT NULL UK,    -- ★稳定消费键=tool_call.id（评审 V04/T11）：
                                               --  重复消费/重算按此去重，不丢不重
                status SUCCEEDED|FAILED|UNKNOWN, duration_ms,
                error_semantics VARCHAR NULL, call_kind FORMAL|DEBUG|EVALUATION,
                created_at)          -- IDX(skill_version_id, created_at)
-- 统计重建映射（评审 V04/T11）：调用数/耗时/错误语义=tool_call 联 attempt；
--   Mock 标记=tool_call.is_mock（执行器第④步实际命中 Mock 才为 true，逐调用记录；
--   DEBUG run 可混合真实与 Mock 调用）；UNKNOWN 对账转终态后按终态归类重算——
--   权威事实在 tool_call/attempt，stat 可全量重建，内存队列仅为加速
external_job_binding(id, tenant_id, idempotency_key UK, adapter_type,
                     external_job_id, run_id, node_instance_id,
                     status SUBMITTED|RUNNING|DONE|FAILED|UNKNOWN, created_at)
```

---

## 5. 接口视图：API 规格（报文级）

统一响应壳：`{"code":"OK","message":"","data":{…},"traceId":"…"}`；错误码 `BIZ-SKILL-nnnn`。

### 5.1 创建注册草稿

`POST /admin/skills`
```json
// 请求
{ "code": "read_document", "name": "文档阅读",
  "category": "文档", "tags": ["文档","读取"] }
// 响应 data
{ "id": 1001, "code": "read_document", "status": "DRAFT", "versionNo": null }
```
错误：`BIZ-SKILL-0001` 编码重复。

### 5.2 保存版本契约（六维表单整体提交）

`PUT /admin/skills/{code}/draft`
```json
// 请求（节选，六维一次提交）
{ "description": "读取指定路径的文档内容…适用：查看/查阅/总结某文档。不适用：扫描件(用 ocr_document)。",
  "invocation": { "kind": "SYNC", "adapterType": "HTTP", "endpoint": "http://doc-svc/api/read",
                  "httpMethod": "POST", "authRef": "cred:doc-svc", "timeoutMs": 30000 },
  "inputSchema": { "type":"object","required":["path"],
    "properties":{"path":{"type":"string"},"format":{"type":"string","enum":["全文","摘要"]}} },
  "execution": { "readonly": true, "sideEffect": false, "idempotent": true, "cancellable": true },
  "access": { "roles": ["ops"], "guardAttrs": [] },
  "errorSemantics": [ {"match":"HTTP 404","semantic":"PARAM_ERROR"} ],
  "returnSpec": { "modelSummary": {"mode":"TEMPLATE","tpl":"文档《{{title}}》共{{pages}}页"},
                  "structuredPath": "$.content", "fileRefPaths": [] },
  "constraints": { "outputMaxBytes": 1048576 }
}
// 响应 data：{ "versionNo": 1, "status": "DRAFT", "schemaValidated": true }
```
校验失败：`BIZ-SKILL-1002`，`data.errors:[{field,reason}]`。

### 5.3 OpenAPI 导入

`POST /admin/skills/import-openapi`
```json
// 请求
{ "openapiUrl": "http://doc-svc/v3/api-docs", "includeOps": ["readDocument"],
  "defaults": { "invocationKind": "SYNC", "adapterType": "HTTP", "timeoutMs": 30000 } }
// 响应 data
{ "drafts": [ {"code":"readDocument","name":"readDocument","description":"","inputSchema":{…}} ],
  "warnings": ["操作 readDocument 缺少描述，请人工补写面向模型的说明"] }
```

### 5.4 连接测试

`POST /admin/skills/{code}/drafts/{versionNo}/connection-test`
响应：`{"ok":true,"latencyMs":128,"sample":{…}}`；失败：`{"ok":false,"stage":"AUTH|ENDPOINT|SCHEMA","reason":"…"}`。

### 5.5 选型验收（上架门禁）

`POST /admin/skills/{code}/drafts/{versionNo}/acceptance`
```json
// 请求
{ "cases": [
    {"question":"帮我看看 D:/docs 里的 xx 报告","expectSelected":"read_document",
     "expectArgs":{"path":"D:/docs/xx报告.docx"}},
    {"question":"总结一下 xx 报告","expectSelected":"read_document"} ] }
// 响应 data
{ "pass": true, "hitRate": 1.0, "argAccuracy": 0.9,
  "cases": [ {"question":"…","selected":"read_document","argsOk":true,"pass":true},
             {"question":"…","selected":"read_document","argsOk":false,
              "expectedArgs":{"path":"…"},"actualArgs":{"path":"…"},"pass":false} ],
  "gate": {"minHitRate":0.8} }
```
`pass=false` → 发布拒绝：`BIZ-SKILL-2001 选型验收未通过`。

### 5.6 发布 / 版本 / 引用 / 统计

| 端点 | 报文要点 |
|---|---|
| `POST /admin/skills/{code}/drafts/{v}/publish` | 校验（Schema/错误语义完整/守卫声明/验收通过）→ PUBLISHED+sha256。失败码：`BIZ-SKILL-2002 校验未过`、`2003 验收未过` |
| `GET /admin/skills/{code}/versions` | `data:[{versionNo,status,publishedAt,referencedCount}]` |
| `POST /admin/skills/{code}/disable` | 有有效引用 → `BIZ-SKILL-2004` + 引用方清单 |
| `GET /admin/skills/{code}/referenced-by` | `data:[{appCode,appVersion,flowNode}]` |
| `GET /admin/skills/{code}/stats?from&to&groupBy=day&version=` | `data:{total:{calls,formal,debug,evaluation}, byStatus:{succeeded,failed,unknown}, failures:[{errorSemantic,count,sampleRunIds[]}], byDay:[{date,calls,succRate,p95}]}`——**version 可选过滤（缺省=全部版本合计）**；口径：逻辑调用数（按 tool_call 去重，不含 attempt 重试）；失败原因可下钻到执行记录；Mock 单列不计入成功率 |

---

## 6. 规则视图：执行管道八步分支全表

| 步 | 输入 | 处理 | 成功输出 | 失败分支（全枚举） |
|---|---|---|---|---|
| ①参数校验 | arguments + input_schema | networknt 校验；类型/必填/枚举 | 通过 | `PARAM_ERROR` → 信封(FAILED)→回灌模型重试（计步）；连续 2 次 → 反问用户 |
| ②守卫链 | guard + args | 权限重校验（敏感/写）→ 交集（守卫属性对应参数 ∩ 授权集）→ 出域检查 | 放行+守卫留痕 | 空交集 → 信封(FAILED, NO_PERMISSION) 回灌（**不重试**）；出域违规 → 换本地端点或终止 |
| ③认领与登记衔接 | callId（登记制） | **引擎已先行登记 tool_call（REGISTERED）**，技能中心对其做条件认领：REGISTERED→EXECUTING（CAS，影响 0 行=他人已认领→等待既有结果或对账）；**提交边界**：先写 SUBMITTED（含提交时间戳）再发外部请求——崩溃于 SUBMITTED 态=已提交外部未知，恢复走对账，**不得按 REGISTERED 直接重发写操作**；同步 HTTP 技能同样先登记后调用 | 认领与去重 | 唯一约束/CAS 冲突 → 非首次：等待既有结果或对账，**不重复发起副作用**（确定性 Job 名与登记表双保险） |
| ④Mock 判定 | debug 标记 + skill_mock | enabled → 直接构造信封（标 mock） | Mock 信封 | — |
| ⑤适配执行 | 调用指令 | HTTP（RestClient+守卫注入+超时）/ MCP / ALGORITHM（确定性命名提交） | 原始响应 | 超时：只读→RETRYABLE 重试；**写→UNKNOWN 进对账**；连接拒绝→RETRYABLE；403→NO_PERMISSION 不重试 |
| ⑥结果验收 | 原始响应（流式计数） | 大小硬闸→流式转 MinIO；output_schema 断言；error_semantics 匹配分类 | 规范化结果 | 结果未知 → UNKNOWN 进对账器 |
| ⑦信封封装 | 验收后结果 | 按 return_spec 生成四份内容；盖归属链戳 | ResultEnvelope | — |
| ⑧执行事实落库 | 信封 | **技能中心只保存执行事实**（attempt/原始响应/守卫留痕/stat，三态分列）；**逻辑结果提交、节点状态迁移与 node_finished 事件由引擎统一提交**——技能中心按 tool_call **逐调用**回调引擎提交接口（幂等 by callId；**Agent 节点内多个调用各自独立交付，全部终态后节点才完成**，不得按节点 attempt 去重吞掉后续调用），回调失败由引擎对账/轮询恢复消费，不重发技能；参数/响应经 args_digest 与 attempt.request_snapshot 关联可追踪 | — | 事实已存、回调失败 → 引擎恢复路径补提交 |

**本体版本元数据采集（评审 T10，消费侧规格）**：本体类技能在 return_spec 声明 `ontologyMeta`——`{"spaceCode":"…","releaseId":"…","version":"…","contentDigest":"…"}`，来源=本体语义服务**响应固定字段**（或响应头 `X-Ontology-Release`），执行器在段三⑥采集进 ResultEnvelope 归属链 → 引擎聚合写入 `run.version_refs.ontology`——**聚合键=空间+发布标识+版本+内容摘要，同空间不同实际版本都保留**（条目携带 callId 关联到具体调用；同空间多版本属告警项，不静默覆盖；如业务要求同空间单版本则冲突按配置拒绝）。应用配置声明了预期本体版本而实际返回不一致 → WARN/按配置拒绝。**可执行消费示例**：注册"本体语义查询"技能（HTTP 直连本体语义 API，声明守卫属性）→ 流程 SKILL 节点选入并映射参数 → 守卫检查 → 执行 → 返回结果携带 `${ontologyMeta}` → 传递给后继 LLM 节点 → 执行记录展示本次实际本体版本。**不以自建本体建模界面为前提**；实际版本由服务返回采集，非展示层填写。

---

## 7. 规则视图：状态机（转移表）

| 当前态 | 事件 | 次态 | 条件/动作 |
|---|---|---|---|
| （无） | 创建 | skill=DRAFT + version=DRAFT | |
| DRAFT | 保存契约 | DRAFT | Schema 校验 |
| DRAFT | 提交验收 | version=PENDING_ACCEPTANCE | 选型验收执行 |
| PENDING_ACCEPTANCE | 验收通过 | version=PUBLISHED, skill=PUBLISHED | sha256/published_at 落库 |
| PENDING_ACCEPTANCE | 验收未通过 | version=DRAFT | 返回报告 |
| PUBLISHED | 修改 | 旧版本保持 PUBLISHED + 新版本 DRAFT | 旧引用不变 |
| PUBLISHED | disable | skill=DISABLED | 无有效引用才允许 |
| PUBLISHED | 新版本发布 | 旧版本 ARCHIVED | 引用关系保留 |

---

## 8. 附录 A：面向模型的说明书写作规范（注册表单内嵌引导）

```text
【能力句】动词开头，一句话说清做什么："读取指定路径的文档内容，返回标题/段落/表格结构。"
【适用】列出用户可能的多种说法："适用于：查看/查阅/总结某文档"
【不适用】防误选："不适用于：扫描件与图片（用 ocr_document）；目录浏览（用 list_directory）"
【参数】逐个说明含义与示例："path：文档完整路径，如 D:/docs/a.docx（必填）"
```

## 9. 验收用例（本册）

1. OpenAPI 导入 → 补说明 → 选型验收 → 上架全流程 ≤10 分钟；
2. 选型命中率 <80% 时发布被拒（`BIZ-SKILL-2003`）；
3. 查询技能缺守卫属性发布被拒（`BIZ-SKILL-2002`）；
4. 同幂等键并发两次调用 → 仅一次真实调用，两次返回同一信封；
5. 输出 2MB（超 1MB 闸）→ 产物引用落 MinIO，模型摘要 <500 字符，**执行器峰值内存受控**；
6. 被引用技能 disable 被拒并列出引用方；
7. 说明书含"不适用"条目时，干扰问题（扫描件）正确路由到替代技能；
8. 写技能超时 → UNKNOWN 对账 → 迟到成功结果采纳且不重复执行（对账时序 3.3）。

---

## 10. 复用与借鉴出处（对照《物料清单》O4/O5/O6/O10/O13/O15）

| 组件 | 复用方式 | 开源出处 | 使用/借鉴内容 |
|---|---|---|---|
| 参数/输出 Schema 校验 | 直接依赖 | networknt json-schema-validator | Draft 2020-12；校验失败信息翻译成回灌文本 |
| OpenAPI 导入 | 直接依赖 | swagger-parser | 接口+参数 Schema 解析生成注册草稿 |
| MCP 适配器 | 直接依赖 | MCP Java SDK（Client） | 列工具/调用/能力协商；Server 二期 |
| HTTP 适配器 | 自研+机制复用 | Spring RestClient + Resilience4j | 调用与超时机制；**重试决策矩阵自研**（契约驱动） |
| 算法适配器 | 直接依赖 | K8s Java Client | 确定性 Job 命名防重/状态轮询/日志 |
| 说明书写作规范 | 借鉴 | OpenAI function calling 最佳实践 + dsh 工具描述风格 | 适用/不适用/参数示例三段式 |
| 执行管道形态 | 读码借鉴 | dsh `core/tools`（pre-execute→execute→post-execute 三段瀑布）、Dify 工具插件两级描述（provider+tool） | 管道分段与插件描述思想 |
| 守卫链/ResultEnvelope/选型验收/对账器 | 纯自研 | — | 平台差异化（归属链/交集原则/门禁） |


---

