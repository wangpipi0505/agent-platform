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


---

## 11. 工程增补（PLAT-05，2026-10-28）：统一错误对象、errorSemantics 声明与任务结果端点

> 本节为平台侧优化的工程增补，依据《平台侧优化方案与实施细则-20261027》§4.5（PLAT-05）与 §4.16（PLAT-16 拆入 T05 落地），经优化台账 T05 派发实施；**不属于 v1.2 原文提取**，`contracts/snapshot/` 保持 v1.2 原文快照不变。与上文冲突时以本节为准。

### 11.1 统一错误对象（七字段）

技能执行失败时，`ResultEnvelope.error` 统一为以下结构，API、SSE、运行详情与模型工具结果使用同一套语义：

| 字段 | 说明 |
|---|---|
| `semantic` | 五类错误语义（§4.2 ⑥）及 `REMOTE_FAILED` / `SUBMIT_NO_TASK_ID` / `OUTPUT_INVALID` / `NON_RETRYABLE_FAILURE` |
| `code` | 远端业务错误码（如 `DATA_SCOPE_DENIED`、`TLE_CHECKSUM_INVALID`）；远端未提供为 `null` |
| `category` | `semantic` 的派生分类：`PARAM` / `AUTH` / `QUOTA` / `TRANSPORT` / `BUSINESS` / `SYSTEM` |
| `retryable` | 是否可重试；`RESULT_UNKNOWN` 恒为 `false`（必须走对账，不允许盲目重试） |
| `userMessage` | 中文可读原因（有界），供对话、页面与审计直接展示 |
| `detail` | `{"raw": "<远端原文，有界 512>"}`；远端未提供原文为 `null` |
| `traceId` | 请求上下文 traceId，与 API 错误信封同源 |

`reason` 键保留为兼容（诊断原文）。`RESULT_UNKNOWN` 属 `TRANSPORT` 且不可重试；`REMOTE_FAILED` 属 `BUSINESS` 且不可重试。

### 11.2 契约 errorSemantics 声明（对象形态）

技能契约（§5.2 表单 `errorSemantics` 键）可声明远端错误读取与改判规则：

```json
{ "codePath": "$.code", "messagePath": "$.message", "retryableCodes": ["TIMEOUT"] }
```

- `codePath` / `messagePath`：远端错误响应体中业务码/原因的点路径（仅支持 `$.a.b` 点路径）；
- `retryableCodes`：命中所列业务码时，4xx 确定性失败改判 `RETRYABLE_FAILURE`（可重试）；副作用调用的 5xx（结果未知）不受声明影响；
- **未声明时用平台默认映射**：读取 `$.code`/`$.message`；HTTP 401/403 → `NO_PERMISSION`，429 → `QUOTA`，其余 4xx → `PARAM_ERROR`，5xx 只读 → `RETRYABLE_FAILURE`，副作用 5xx → `RESULT_UNKNOWN`。路线三桥 envelope（`ok:false`）随 HTTP 403 映射为 `NO_PERMISSION` + 远端中文原因（`category=AUTH`、`retryable=false`），平台不把 403 当"系统错误"，模型应看到可解释拒绝而不是重试；
- 对象形态在注册期校验（路径仅支持点路径、`retryableCodes` 必须为字符串数组）；v1.2 数组形态（match 列表）原样透传，执行侧按平台默认映射处理；
- 异步终态失败（`REMOTE_FAILED`）按 `errorSemantics.codePath` 携带稳定业务码，原文按 `reconciliation.failureMessagePath`（缺省回退 `errorSemantics.messagePath`）读取，有界落绑定行。

### 11.3 任务结果读取端点（T05.2）

`GET /admin/skills/tasks/{callId}/result`（门户面，经网关 `/admin/skills/**` 代理）：

- 已终态任务返回 `{callId, status, result}`，失败任务的 `result.error` 为 11.1 完整错误对象；
- 不存在 → 可读 404 `FACT_NOT_FOUND`；未终态（`UNKNOWN` 或执行准备中）→ 可读 409 `TASK_NOT_FINAL`；
- `callId` 是平台任务身份；`externalJobId` 只是远端任务标识，不作为寻址键。

---

## 12. 工程增补（PLAT-06，2026-10-28）：SUBMIT_QUERY 声明式路径映射（pathParams）与查询错误可读化

> 本节为平台侧优化的工程增补，依据《平台侧优化方案与实施细则-20261027》§4.6（PLAT-06），经优化台账 T06 派发实施；**不属于 v1.2 原文提取**，`contracts/snapshot/` 保持 v1.2 原文快照不变。与上文冲突时以本节为准。

### 12.1 reconciliation.pathParams 声明

SUBMIT_QUERY 对账端点可用声明式映射表达"平台字段 → 远端占位符"，远端字段叫 `taskId` 不必再写成 `{jobId}`：

```json
{
  "execution": { "kind": "HTTP_ASYNC" },
  "reconciliation": {
    "endpoint": "http://host/tasks/{remoteTaskId}",
    "pathParams": {
      "remoteTaskId": { "from": "externalJobId" },
      "remoteKey": { "from": "idempotencyKey" }
    },
    "jobIdPath": "$.taskId",
    "donePath": "$.status",
    "doneValue": "SUCCEEDED",
    "failedValue": "FAILED",
    "failureMessagePath": "$.errorMessage",
    "resultEndpoint": "http://host/tasks/{remoteTaskId}/result",
    "resultEndpointPath": "$"
  }
}
```

- `pathParams` 键 = 端点里的占位符名（`{name}`，字母开头的标识符）；值必须为 `{"from": 平台字段}`，`from` 只允许 `externalJobId`（已核实的外部任务标识，来自提交 ACK 或按幂等键的查询确认）与 `idempotencyKey`（原调用的幂等键原文）；
- 占位符可出现在 `endpoint`、`resultEndpoint`、`lookupEndpoint` 的路径或查询串任意位置；替换值按 URL 段编码；
- **保存期一一对应校验**：`endpoint`/`resultEndpoint`/`lookupEndpoint` 中出现的全部占位符与 `pathParams` 必须一一对应。占位符缺映射 → `400 ASYNC_PATH_PARAM_MISSING`，报错带字段路径（如 `reconciliation.pathParams.remoteTaskId`）与修复建议（声明 `{"from":"externalJobId"}` 或改用旧占位符）；声明了端点未使用的占位符 → `400 ASYNC_PATH_PARAM_UNMATCHED`；`from` 非法、值形态错误或重定义旧占位符 → `400 INVALID_ASYNC_PROTOCOL`（同样带字段路径）；
- 任务标识未知（提交 ACK 丢失且未配置 `lookupEndpoint`）时，需要 `externalJobId` 的查询端点构造不出可寻址 URL——平台不发送请求，保持结果未知等待对账；旧 `{jobId}` 端点保持既有行为不变。

### 12.2 占位符兼容与 jobIdPath 双源优先级

- **旧契约零回归**：`{jobId}` / `{idempotencyKey}` 字符串替换继续可用，等价于内置默认映射（`jobId`→`externalJobId`、`idempotencyKey`→`idempotencyKey`），语义固定，不需要也不允许写进 `pathParams`；
- 新旧可混用：同一契约里 `{jobId}` 走内置映射、其余占位符由 `pathParams` 声明；
- **空 `httpMapping` 对 SUBMIT_QUERY 视为缺省**：保存时移除，不再报非法；非空 `httpMapping` 与异步提交仍互斥（`INVALID_ASYNC_PROTOCOL`）；
- **`jobIdPath` 双源优先级（现状行为，本次文档化）**：任务标识读取路径为 `reconciliation.jobIdPath` 优先，其次顶层 `jobIdPath`，两者都缺省为 `$.jobId`；`idempotencyKeyPath` 仅在 `reconciliation` 上声明（缺省 `$.idempotencyKey`）。

### 12.3 查询阶段 4xx 错误语义（T06.2）

对账/轮询查询阶段的远端 4xx 不再一律折叠成 `RESULT_UNKNOWN`：

- 状态端点 `401/403` → `NO_PERMISSION` 可读失败（不可重试）：凭证问题不会因轮询消失，错误对象携带远端业务码（§11.2 默认读取路径）与原文；**查询失败不是外部任务的终态**，`external_job_binding` 行保持 `SUBMITTED` 原状；
- 其余 4xx（404/409 等）与 5xx → 仍为 `RESULT_UNKNOWN` 继续对账（任务可能尚未可见），但错误原因携带 HTTP 状态与远端业务码/原文，被折叠的底层异常以 `cause` 保留；
- 结果端点 409/4xx 先复查状态端点的行为沿用 §11（T05）不变；
- 提交阶段 4xx 语义沿用 §11.2 平台默认映射（401/403→`NO_PERMISSION`、429→`QUOTA`、其余→`PARAM_ERROR`），携带远端码/原文；
- 异步协议拒绝（`ASYNC_*`，如 `ASYNC_MULTIPART_UNSUPPORTED` 400）在执行器不再折叠为 `UNKNOWN`/`OUTPUT_INVALID`，按原码原状态透出——它们是"本次调用为何没有运行"的结论，在任何出站请求之前即已成立。

---

## 13. 工程增补（PLAT-12，2026-10-28）：技能契约 identity 声明与出站 X-User-* 注入

> 本节为平台侧优化的工程增补，依据《平台侧优化方案与实施细则-20261027》§4.12（PLAT-12），经优化台账 T12.1 派发实施；**不属于 v1.2 原文提取**，`contracts/snapshot/` 保持 v1.2 原文快照不变。与上文冲突时以本节为准。

### 13.1 契约 identity 声明

技能契约（顶层 `identity` 键）可声明出站身份注入：

```json
{
  "identity": {
    "mode": "PLATFORM_SUBJECT",
    "headers": {
      "X-User-Id": "principalId",
      "X-User-Tenant": "tenantId",
      "X-User-Roles": "roles",
      "X-User-Attributes": "attributes"
    }
  }
}
```

- `mode` 只允许 `PLATFORM_SUBJECT`：出站身份只有一个来源，就是进程内已验主体快照（`Actor.current().subject()`，T12a）；引入第二种 mode 等于承诺一个不存在的身份通道。其他值 → `400 SKILL_IDENTITY_MODE_INVALID`（可读错误，带收到的值）；
- `headers` 的**值**只允许主体快照字段白名单 `principalId` / `tenantId` / `roles` / `attributes`——声明任意 header→值的映射会把出站头变成自由写入通道，这正是方案"伪造 `X-User-*` 被网关拒绝"要堵的口子。白名单外取值 → `400 SKILL_IDENTITY_HEADER_INVALID`；
- `headers` 的**键**为出站头名，必须是合法 HTTP 头名（字母/数字/连字符），且不得与鉴权/幂等/传输语义的保留头冲突（`Authorization`、`X-API-Key`、`Idempotency-Key`、`Accept`、`Content-Type`、`Host`、`Cookie`、`Set-Cookie`），否则 `400 SKILL_IDENTITY_HEADER_INVALID`；
- `identity` 仅支持 HTTP 技能：同步与异步两个发头点都在 HTTP 适配器内，MCP（SSE transport）、ALGORITHM（SDK）、PROCESS（stdin）没有 `X-User-*` 通道，非 HTTP 声明 → `400 SKILL_IDENTITY_UNSUPPORTED`；
- 声明形态错误（非对象、headers 为空/非对象、值非字符串）→ `400 SKILL_IDENTITY_INVALID`；
- **向后兼容**：未声明 `identity` 的契约不产生该键，出站行为与执行事实信封形状零变化；旧编辑器局部保存若省略 `identity`，已保存的声明以旧值为准补回（与 authScheme 同一"省略不等于删除"语义）；identity 变化会改变语义摘要（`identity` 不在展示字段排除集内），发布门禁照常要求重测。

### 13.2 出站注入语义

- **与鉴权头叠加而非替换**（§11 PLAT-11 的 `AuthScheme` 语义零改动）：鉴权头照发，身份头追加在同一请求上；组合 `authScheme=API_KEY` 时 `X-API-Key` 与 `X-User-*` 并存；
- 注入点为 SkillAdapters 同步发头点与 HttpAsyncAdapter 异步 `request()`（提交与查询请求都注入），两处共用同一份契约映射，同一技能在 SYNC 与 SUBMIT_QUERY 间发出同一组身份头；
- **取值唯一权威是进程内主体快照**：`X-User-Id`←`principalId`、`X-User-Tenant`←`tenantId`、`X-User-Roles`←`roles`、`X-User-Attributes`←`attributes`。与 V107 `app.identity_mapping` 的关系：映射表经 `SubjectSnapshot.mappingKey()`（canonical SHA-256）与快照对齐，是"平台主体→外部 subject"的持久登记面；出站值不读表——跨服务以映射表为源的反查属后续卡，不在本节实现；
- **序列化格式（定死）**：`roles` 按字典序排序后逗号连接（如 `EDITOR,USER_ADMIN`——集合迭代序不进协议，同一主体序列化结果恒定）；`attributes` 为键字典序、值排序字符串数组的紧凑 JSON（如 `{"dept":["103"],"region":["LOCAL"]}`）；
- **空值不发头**：`roles`/`attributes` 为空集合、`principalId`/`tenantId` 空白时该头不发送——HTTP 头没有空集合表达，空串会让对端按逗号拆分出幽灵角色；跳过比伪造空值诚实；
- **无可用主体（系统级调用）时跳过注入，不伪造值**：无验签上下文（401）或机器 workload 票据（403，机器能力不能映射为用户身份）时，身份头整体缺省，出站请求照常发出（身份头是补充可读身份，不是鉴权依据）；跳过结论随执行事实信封 `result.identity` 落运行详情——`{mode, headers:[{name, from, sent}], injected, skipReason}`，`skipReason=NO_ACTOR_SUBJECT|SUBJECT_VALUES_EMPTY`；**trace 只带头名/来源字段/布尔，不带头值**，身份明细不向运行详情扩散；未声明 `identity` 的技能信封无 `identity` 键。

### 13.3 与桥接层身份的边界

方案 §4.12 明令禁止桥接层自定义身份：出站身份由平台适配器按本节统一注入，技能接入方不得在 URL、请求体或自定义头里自行携带身份替换语义；本节与 PLAT-04/05/06 的对账与错误语义正交，不改变任何既有错误码与状态机。

---

## 14. 工程增补（PLAT-15，2026-10-28）：写操作 PREPARE/EXECUTE 两段与人工确认（execution.approval）

> 本节为平台侧优化的工程增补，依据《平台侧优化方案与实施细则-20261027》§4.15（PLAT-15），经优化台账 T15.1/T15.2 派发实施；**不属于 v1.2 原文提取**，`contracts/snapshot/` 保持 v1.2 原文快照不变。与上文冲突时以本节为准。

### 14.1 契约声明与兼容口径（定死）

技能契约在 `execution.approval` 上声明写操作的两段审批模式：

```json
{
  "execution": {
    "sideEffect": true,
    "confirmRoles": ["OP_ADMIN", "SEC_AUDITOR"],
    "approval": {
      "mode": "PREPARE_EXECUTE",
      "risk": "HIGH",
      "prepare": { "endpoint": "https://host.example/users/prepare", "httpMethod": "POST" }
    }
  }
}
```

- **向后兼容口径（定死，D1）**：`execution.approval` 键**缺省 = `SINGLE_STEP`**——存量技能不落该键，执行流程与信封形状零变化。方案 §4.15"默认 PREPARE_EXECUTE"的口径落为：**新契约必须显式声明 `mode=PREPARE_EXECUTE` 才进入两段**；平台不做缺省即两段的行为迁移，避免存量技能行为漂移。显式声明 `mode=SINGLE_STEP` 等价于缺省（供旧技能自述）。其他取值 → `400 APPROVAL_MODE_INVALID`；
- `mode=PREPARE_EXECUTE` 仅支持 **HTTP 同步技能**（`type=HTTP` 且调用方式为同步）：PREPARE 的出站点与 EXECUTE 一样是 HTTP 适配器；MCP（SSE transport）、ALGORITHM（SDK）、PROCESS（stdin）、异步 HTTP（SUBMIT_QUERY/CALLBACK 自带任务态语义）声明两段 → `400 APPROVAL_PREPARE_UNSUPPORTED`（与 §13 identity 的适配器边界同一裁决）；
- `prepare.endpoint` 必填，且必须是 `SkillRules.endpoint` 接受的无明文凭据 HTTP(S) 地址，否则 `400 APPROVAL_PREPARE_INVALID`；`prepare.httpMethod` 只允许 `POST`（缺省 `POST`）——PREPARE 语义是"以同一组参数做一次无副作用的预演"，载体是请求体，GET 会让参数进 URL 日志；
- `mode=PREPARE_EXECUTE` 要求 `sideEffect=true` 且 `confirmRoles` 非空（只读技能没有可确认的写，缺确认角色的写技能被既有发布门禁拦截）；
- `risk` 可选，只允许 `STANDARD`（缺省）| `HIGH`，其他取值 → `400 APPROVAL_RISK_INVALID`。**高危判别（定死，D4）**：删除、改状态、发 Key 等高危写操作以工具契约声明的 `risk=HIGH` 为准——平台不从 URL/方法猜测危险级别，声明权在技能接入方；`approval` 变化随 `execution` 进入语义摘要（`SkillDigest`），发布门禁照常要求重测。

### 14.2 PREPARE 段（调用形态定死，D2）

- **PREPARE 调用形态 = 独立入口**：适配器以守卫后的参数（与 EXECUTE 同一 JSON 形态）POST 调用 `prepare.endpoint`，携带与 EXECUTE 相同的 `Idempotency-Key` 头与鉴权/身份头（§11/§13 语义不变）；业务系统在该入口实现"无副作用的预演"（预检、dry-run、diff 计算），平台按契约把它当只读调用对待；
- prepare 响应必须是 JSON 对象，平台读取顶层 `summary`（操作摘要）、`target`（目标对象）、`diff`（将要发生的变化）、`risk`（风险提示，声明 `risk` 时以契约为准）四个字段；缺省字段平台补默认（`summary` 缺省"对目标对象的写操作"，`diff`/`target` 允许 null）——prepare 响应里的 `idempotencyKey`/`expiresAt` **不是权威**；
- PREPARE 失败（远端 4xx/5xx/超时/输出非 JSON 对象）→ `502 ACTION_PLAN_UNAVAILABLE` 可读错误，**不产生任何业务副作用，也不进入 WAITING_APPROVAL**：确认人绝不审批一份没有依据的计划；PREPARE 在每次"未批准的重派发"中都会重调（业务预演应当幂等）；
- 调试 Mock 运行（引擎登记的 `mock_snapshot` 非空）跳过业务 PREPARE 调用，actionPlan 以"调试替身调用"占位——Mock 路径本来就不会触达业务系统。

### 14.3 actionPlan 与引擎盖章（幂等键来源定死，D3）

- actionPlan 形状：`{summary, target, diff, risk, idempotencyKey, expiresAt}`；
- **`idempotencyKey` 与 `expiresAt` 由引擎在 `requestApproval` 时统一盖章**（技能侧若提供将被覆盖）：`idempotencyKey = sha256("EXECUTE:" + callId + ":" + actionPlanDigest)`，`expiresAt = 审批上下文 expiresAt`（申请确认时刻 + 3600s，单一过期语义）；`actionPlanDigest = sha256(canonical(盖章后的 actionPlan))`——幂等键来源唯一是引擎，技能侧提供的键不进协议，避免伪造或碰撞；
- 引擎 `engine.action_plan`（V221）逐份持久化盖章后的计划：`execute_key` **唯一约束**是"同一份计划只有一条 EXECUTE 授权记录"的硬保证；同一调用重复 PREPARE 出相同内容 → 相同 execute_key → 复用同一行；内容变化 → 新 digest → 新行（旧行保留审计）；
- 审批节点（`approval_context`）保存 `actionPlanId`/`actionPlanDigest`/`actionPlan`/`risk`，确认中心与运行详情展示的就是引擎盖章后的这份。

### 14.4 EXECUTE 段：digest + 未过期 + 幂等

- 批准后的 EXECUTE（技能重新执行到达 claim）**必须**携带引擎盖章的 `executeKey`，引擎按以下顺序拒绝（全部可读 4xx，**副作用不发生**——claim 未授予，业务调用不发出）：
  - 缺少 executeKey → `409 EXECUTE_KEY_REQUIRED`；
  - executeKey 与 `engine.action_plan.execute_key` 不一致，或 plan digest 与审批节点 `actionPlanDigest` 不一致（计划被替换/篡改）→ `409 ACTION_PLAN_DIGEST_MISMATCH`；
  - 计划已过期（`expiresAt <= now`，即使批准动作本身在窗口内完成）→ `409 ACTION_PLAN_EXPIRED`；
  - 高危计划（`risk=HIGH`）确认强度不足（confirmRoles 少于 2 个不同角色且审批时无 `doubleConfirmed` 二次确认标记）→ `403 CONFIRM_STRENGTH_INSUFFICIENT`；
- 业务幂等键 = 盖章的 `idempotencyKey`（经 `GET /internal/tool-calls/{id}/approval` 下发），替换原"逻辑调用键"作为 EXECUTE 的出站 `Idempotency-Key`；同一计划重复 EXECUTE：引擎 claim CAS 只授予权一次，执行事实按 callId 去重——**重复提交返回首次结果，不重复执行业务**（方案验收"同一幂等键重试不重复创建"）；
- `SINGLE_STEP` 技能（含全部存量）不携带 executeKey，claim 不做上述校验——旧行为零回归（既有 writeApproval 用例为锚点）。

### 14.5 高危二次确认与审计

- `risk=HIGH` 的确认强度（定死，D4）：**confirmRoles 至少 2 个不同角色，或单角色 + 审批请求 `doubleConfirmed=true`（前端二次确认勾选）**；审批时（`ApprovalService.approve`）与 EXECUTE 时（claim 复查）双重把关，任一不满足即 403 `CONFIRM_STRENGTH_INSUFFICIENT`，副作用不发生；越权确认（无 confirmRoles 角色）沿用既有 `403 APPROVAL_ROLE_REQUIRED`；
- 审计沿既有结构：`engine.action_plan` 行携带 `run_id/call_id/digest/status/confirmed_by/confirmed_at/execute_granted_at/executed_status/result_digest`（runId/actionPlanId/confirmBy/confirmAt/result）；`APPROVAL_DONE` 运行事件带 `actionPlanId` 与 `risk`；执行结果经既有 fact 路径回写 plan 状态——运行中心可查，不新造审计流。

### 14.6 错误码登记（触发条件）

| 错误码 | 状态 | 触发条件 |
| --- | --- | --- |
| `APPROVAL_MODE_INVALID` | 400 | 注册期 `execution.approval.mode` 不是 `PREPARE_EXECUTE`/`SINGLE_STEP` |
| `APPROVAL_PREPARE_UNSUPPORTED` | 400 | 非 HTTP 同步技能声明 `mode=PREPARE_EXECUTE` |
| `APPROVAL_PREPARE_INVALID` | 400 | `prepare` 缺 endpoint、endpoint 非法或 `httpMethod` 非 POST |
| `APPROVAL_RISK_INVALID` | 400 | `risk` 不是 `STANDARD`/`HIGH` |
| `ACTION_PLAN_UNAVAILABLE` | 502 | PREPARE 调用失败（远端错误/超时/输出非 JSON 对象）；无副作用 |
| `EXECUTE_KEY_REQUIRED` | 409 | 两段调用的 EXECUTE 未携带 executeKey |
| `ACTION_PLAN_DIGEST_MISMATCH` | 409 | executeKey 或 plan digest 与引擎持久化计划不一致 |
| `ACTION_PLAN_EXPIRED` | 409 | 计划 `expiresAt` 已过 |
| `CONFIRM_STRENGTH_INSUFFICIENT` | 403 | `risk=HIGH` 且（confirmRoles 少于 2 个不同角色 且 无 `doubleConfirmed`） |

全部错误经既有 `PlatformException`/`ErrorSemantics` 信封携带 `code + userMessage + traceId`；模型对不可重试的拒绝收到可解释工具结果（不重试），人不重试已过期/被替换的计划。

## 15. 工程增补（PLAT-22 C 段，2026-10-28）：适配器注册表与执行方式零改代码接入

> 本节为平台侧优化的工程增补，依据《平台侧优化方案与实施细则-20261027》§11.4（新软件接入八步）与优化台账 T22C 派发实施；**不属于 v1.2 原文提取**，`contracts/snapshot/` 保持 v1.2 原文不变。与上文冲突时以本节为准。

### 15.1 注册表机制（定死）

- kind→执行单元的单一来源是适配器注册表（skill-registry `infra.AdapterRegistry`）：`SkillAdapters` 的 invoke/observe/bind/persistentBinding 与 `SkillCatalog` 的注册期校验（适配器类型白名单、类型×调用形态组合白名单、kind 缺省值、MCP 工具目录能力门）全部经注册表取答案；平台主链不再出现 kind→实现的硬编码 switch 与散落的白名单枚举；
- 注册来源两条路注册同一批单元类，声明清单（type × invocationKind 接受面）只在单元类内维护一份：①**Spring 装配自动发现**——实现 `SkillAdapter` 并标注 `@Component`，注册表构造器注入收集全部单元（内置五个与未来外部适配器同一通道）；②**显式注册**——直接构造场景（既有单测的组织方式）用 `withPlatformDefaults()`（内置五单元）或 `register(...)`；
- 未注册 kind 的语义（定死）：注册期 `400 INVALID_SKILL`（适配器类型无效）与 `400 UNSUPPORTED_INVOCATION_KIND`（组合不支持），错误码与触发条件和注册表化之前完全一致（PROCESS 的 SYNC-only 定制诊断正文由其注册单元承载，不退化）；执行期 `PARAM_ERROR` 失败（unknown=false，与原 switch default 臂同码同位），正文列出已注册类型与接入方式。bind/persistentBinding 等旁路询问对未注册 kind 保持既有静默语义（查找返回 null，无操作），不新增抛错面；
- 内置五单元（HTTP 同步 / HTTP 异步 / MCP / ALGORITHM / PROCESS）零搬运执行语义：单元把注册表路由交回 `SkillAdapters` 既有通道，T05 错误信封、T06 输出卸载、T12 身份注入调用点、T21d 进程加固（超时杀树/可执行白名单/失败转储）、PLAT-15 两段审批原地不动；MCP 与 PROCESS 的对账保护（queryOnly → `RESULT_UNKNOWN`，本地进程与 MCP SSE 无结果查询通道，重跑即第二次副作用）随各自单元声明迁移。

### 15.2 两层 kind 关系（定死）

- **声明层**：能力包 schema（`contracts/capability-package.schema.json` 的 `interfaces[].kind`，T22A 冻结的五值枚举 HTTP_SYNC/HTTP_ASYNC/PROCESS/MCP/ALGORITHM）是新软件接入的唯一声明入口；新取值必须修订 schema（契约纪律 §6.5），schema v1 冻结不受本节影响；
- **运行层**：本注册表允许在不改平台分发代码的前提下注册新执行方式（实现适配器 + 注册即接入）。运行层先行注册不构成声明层上线——注册表中存在但能力包 schema 未声明的 kind 只用于平台侧验证/内部扩展，不能进入对外能力包声明；两层各自独立治理；
- 错误诊断**正文**不是契约不变量：执行期未注册 kind 的正文（已注册类型清单）随注册状态变化，`PARAM_ERROR`/`INVALID_SKILL`/`UNSUPPORTED_INVOCATION_KIND` 错误码与 unknown 位保持不变。

### 15.3 新执行方式接入路径（八步中"平台扩展"的落点）

1. 实现 `SkillAdapter` 接口：声明 `type()` 与 `supportedKinds()`，按需覆写 `defaultKind`（注册期 kind 缺省）、`handles`（同 type 多形态二次路由）、`observe`（独立观测模型）、`bind`/`persistentBinding`（幂等键登记）、`supportsToolDiscovery`（工具目录能力门）、`unsupportedKindMessage`（组合拒绝定制诊断）；执行语义自持，或经 `host`（`SkillAdapters`）复用结果信封等平台共享设施，并保持七字段错误语义；
2. 标注 `@Component` 交 Spring 自动发现，或在测试/嵌入式场景显式 `register`；
3. **无需修改 `SkillAdapters`/`SkillCatalog` 任何分发与白名单代码**——注册期校验、执行分发、绑定、观测、工具目录门由注册表自动获得；
4. 契约形状规则（endpoint/toolName/process 子契约/callback 声明等 per-kind 校验）与执行编排行为分支（回调接听、算法证据等）仍按 §4/§11–§14 既有口径，其收敛属后续 A-02 卡，不在本节范围。

验证锚点：`AdapterRegistryZeroChangeTest`（8 例）——TEST_ECHO 测试单元在产品主代码零字样的前提下经注册表完成注册期放行与执行分发；内置 kind 路由矩阵、bind/persistentBinding 旁路语义与原 if-chain 等价、MCP/PROCESS 对账保护随单元迁移均有锚点断言。
