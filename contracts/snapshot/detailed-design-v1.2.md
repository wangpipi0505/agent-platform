# 智能体构建平台详细设计（整合版）

> **基线声明**：本文档为详细设计的**唯一阅读与评审基线**，由九册分册于 2026-10-19 合并而成（内容零删减，仅去除分册间重复的通用约定）；原九册分册转为历史工作稿归档，不再单独维护。后续修订直接在本文档上走版本化（v1.x）。
> **修订记录**：v1.1（2026-10-20）——按第四轮复审修正：tool_call 补 is_mock/duration_ms/error_semantic/claimed_lease 字段与耗时口径选定；并发 commit 同候选复用语义选定（同候选只产一个版本，不同请求号关联返回）；Mock/DEBUG 统计口径分离；调试状态查询响应与六组检查表述同步。v1.2（2026-10-20）——按第五轮关闭核查修正 X01（tool_call.error_semantic 列宽 16→32，对齐五类错误语义最长枚举 RETRYABLE_FAILURE）与 requestId 去重记录的写入时点表述（受理 resume 完成 CAS 时同事务保存）。**第五轮复审结论：模块 1 的 17/17 项功能设计覆盖闭环。**
> 依据：《算法重组工具技术要求-1012》模块 1（17 项）；模块 2（本体，25 项）不在本范围，仅留接口。
> 上位约束：《智能体构建平台设计与实现路径（自研路线）》v3.7。
> 技术底座：Java 21 + Spring Boot 3.4.x/3.5.x + PostgreSQL + Redis + K8s + MinIO；前端 Vue 3.5 + TS + Element Plus + AntV X6。

## 总目录

> **章节引用导航**：各部分内部的"§n"引用指该部分原文编号（如"册 31 §6.5"指第五部分内的小节）；跨部分引用一律写明部分名。

| 部分 | 内容 | 主要读者 |
|---|---|---|
| 第一部分 | 通用约定、错误码、公共组件接口签名、非功能约定 | 全员 |
| 第二部分 | 模型接入（1.1① + 计量三档/背压/出域路由） | 后端 |
| 第三部分 | 技能中心（1.4①~④ + 执行器八步管道） | 后端 |
| 第四部分 | 编排与画布 + 图定义契约全文（1.2①③） | 前端+后端 |
| 第五部分 | 运行时引擎（双驱动/组装七步/调度/执行语义/隔离矩阵）（1.2 执行侧） | 后端 |
| 第六部分 | 调试器（1.2②） | 前端+后端 |
| 第七部分 | 管理与发布 + 集成四件套（1.3①~⑤ + 1.2④） | 前端+后端 |
| 第八部分 | 前端工程与设计系统（四端/页面清单/设计 tokens） | 前端 |
| 第九部分 | 评测模块（M4 轻量版） | 后端 |
| 附录 | 复用与借鉴出处汇总 | 全员 |

**全文最高优先级的五条铁律**（各部分冲突时以此为准）：
1. 引擎与配置分离：全平台一份逻辑引擎（多租户、可多副本），智能体 = 一份配置记录；
2. 数据与控制分离：模型输出是数据不是指令；守卫值永远来自权限系统；
3. 可重建不变量：凡进入模型请求的内容必须可从 run 消息日志重建，最终请求材料**先持久化成功、再发送模型请求**；
4. 逻辑结果/节点状态/事实事件的唯一提交者=引擎（执行器只持久化执行事实并按 attempt 幂等回调）；
5. 契约先行：图定义契约、trace 模型、技能调用契约、授权与确认规范——四份契约定稿前不写引擎代码。

---

# 第一部分 通用约定、错误码与公共接口（原册 00）

## 1. 文档结构与阅读指引

本整合版第二~九部分按"能力域"组织。原分册编号对照：第一部分=原册 00；第二部分=原册 10；第三部分=原册 20；第四部分=原册 30；第五部分=原册 31；第六部分=原册 32；第七部分=原册 40；第八部分=原册 60+61；第九部分=评测（主设计 6.9 落地）。

## 2. 技术底座

| 项 | 选型 | 说明 |
|---|---|---|
| 语言/框架 | Java 21 + Spring Boot 3.4.x / 3.5.x | 虚拟线程必需 21；**Boot 版本锁定 Spring AI 1.0 官方兼容矩阵（3.4.x/3.5.x），不用 3.3.x** |
| 数据库 | PostgreSQL 15+ | **唯一权威状态源**；Redis 只做缓存/限流 |
| 缓存 | Redis 7.x | 活跃会话/信号量/限流，非真相源 |
| 对象存储 | MinIO（S3 兼容） | 产物/文件，租户前缀+预签名下载 |
| 编排 | K8s | 引擎多副本+算法 Job |
| 迁移 | Flyway | 契约→DDL 版本化 |
| 可观测 | OTel + Prometheus + Grafana + Loki + Tempo | 自部署（交付策略见 §1.6） |

## 3. 工程结构与服务拆分

```
agent-platform/
├── platform-gateway/    接入网关：双认证（门户 JWT/外部 ApiKey+出票）/限流/CORS/SSE 转发
├── engine-runtime/      运行时引擎：双驱动/组装/调度/执行语义/确认/调试/事件
├── model-gateway/       模型接入：供应商/绑定解析/LLMClient/计量/背压/出域路由
├── skill-registry/      技能中心：六维契约/注册/版本/执行器/统计
├── app-management/      应用管理：工作台/生命周期/发布/集成四件套
├── monitor/             运行监控与评测：监控/回放/确认中心/评测
├── admin-common/        公共：鉴权/审计/错误码/公共字段/trace 关联
└── web/                 前端：flow-designer(画布) / admin(门户) / webapp(对话) / assistant.js(嵌入)
```

服务间调用规则：`gateway → 各服务`（唯一外部入口）；`engine-runtime → model-gateway / skill-registry`（执行期仅两条外调）；`app-management / monitor` 只读引擎数据。服务内禁止循环依赖；跨服务一律 HTTP + 显式契约。

后端包结构模板（各服务统一）：`api/`（Controller+record）`application/`（用例编排，事务边界）`domain/`（领域模型与状态机，无框架依赖）`infra/`（仓储/外部客户端/配置）+ `error/ trace/ security/`。

## 4. 通用约定

- **公共字段**（所有业务表默认）：`id BIGINT PK`、`tenant_id`、`created_by/at`、`updated_by/at`、`deleted`、`remark`；
- **版本化实体模式**：主表 + `_draft` + `_version`（不可变：version_no/sha256/published_at/by）；
- **ID**：内部 BIGINT；对外用 `code` 或发布版本号；runId 对外 `r_<序号>`；
- **幂等键（调用登记制）**：每个逻辑调用首次发起前原子登记 tool_call 并生成 `hash(run_id + node_instance_id + call_seq + skill_version_id)`，重试/恢复/接管复用登记值——不同逻辑调用身份不同，同一调用复用身份；
- **时间**：TIMESTAMPTZ（UTC 存储）；状态枚举 VARCHAR 大写、集中状态机；
- **三态标记**：FORMAL / DEBUG / EVALUATION 贯穿统计与门禁。

## 5. 错误码总表

| 错误码 | 含义 | 归属 |
|---|---|---|
| SYS-MODEL-4001/4002/401/429/503/504/413 | 绑定失败/能力不足/凭据/限流/熔断/超时/上下文超限 | 模型接入 |
| SYS-EGRESS-4031 | 数据不可出域且无合规端点 | 模型接入 |
| SYS-GW-429 | 网关限流 | 网关 |
| SYS-APP-4002/4031、BIZ-APP-3001/3002 | 应用停用/越权/发布检查未过/草稿已变更需重预检 | 管理发布 |
| BIZ-SKILL-0001/1002/2001~2004 | 编码重复/契约校验失败/验收未过/校验未过/验收门禁/引用未清 | 技能中心 |
| BIZ-EVAL-3001 | 评测门禁安全用例失败 | 评测 |

## 6. 非功能约定

- **安全**：双认证（门户 JWT：受众/范围/出票方声明上限；外部 ApiKey/出票 JWT）；数据与控制分离；守卫交集原则+默认拒绝；凭据只存引用、永不入 prompt 与日志；数据出域规则每次模型调用前检查；用户令牌不透传下游；
- **性能**：异步状态机运行时；虚拟线程+三类信号量+舱壁分池；六道内存硬闸随 M1 落地；背压满载快速拒绝（交互）或排队（批量）；
- **可观测**：OTel+Prometheus+Grafana+Loki+Tempo（**随平台交付、对客户业务用户不暴露，仅运维网络可达**）；业务 trace=run/node_instance/attempt；
- **部署**：引擎无状态多副本（HPA 按并发运行数）；分代 ZGC + `MaxRAMPercentage=70` + limit=堆×1.4；压测基线 500 并发/首 token p95/SSE 5000+。

## 7. 公共组件接口签名（跨册引用，变更须契约评审）

```java
// —— 模型接入 ——
interface LLMClient {
    ModelStream call(ModelRequest req);            // 流式；出域路由/配额/计量/错误归一在内部
    ModelResponse callSync(ModelRequest req);      // 评测跑批用
}
interface ModelBindingResolver { ResolvedModel resolve(AppVersionRef v, String nodeId); }
interface QuotaService { AcquireResult acquire(String providerId, int estTokens, AppRef app); }

// —— 技能中心 ——
interface SkillExecutor { CompletableFuture<ResultEnvelope> execute(SkillInvocation inv); }
record SkillInvocation(String tenantId, SkillVersionRef skill, JsonNode arguments,
                       String principalId, GuardContext guard, String idempotencyKey,
                       String callId,              // 持久化调用身份（tool_call 主键），登记制生成
                       InvokePolicy policy, CancelToken cancel) {}
record ResultEnvelope(OwnershipChain owner, ResultStatus status,
                      ErrorSemantics error, String modelSummary,
                      JsonNode structured, List<ArtifactRef> fileRefs, String cardPayloadJson) {}

// —— 运行时引擎 ——
interface RunDriver { CompletableFuture<RunOutcome> drive(RunContext ctx, DriveListener l); }
interface NodeHandler { String type(); NodeResult execute(NodeInstance n, FlowContext c, DriveListener l); }
class ContextAssembler {
    AssembledRequest assemble(RunContext ctx);
    AssembledRequest assembleForNode(LLMNodeConfig cfg, FlowContext ctx);
}
interface EventStore { void append(RunId runId, EventType type, JsonNode payload);
                       List<RunEvent> replayAfter(RunId runId, int lastSeq); }

// —— 守卫 ——
interface GuardResolver {
    GuardContext snapshot(Principal p);                                        // run 创建时
    AllowedScope intersect(GuardContext g, Optional<JsonNode> modelProposed);  // 执行时（默认拒绝）
}

// —— 发布 ——
interface PublishCheck { CheckReport check(AppDraft draft); }   // 六组检查器责任链 SPI

// —— 模块 2 预留 ——
interface VocabularyProvider { VocabSection sectionFor(AppVersionRef v, Principal p); }
```

---

# 第二部分 模型接入（详细设计 10）

> 六视图：功能/组成/流程/数据/接口/规则。承接需求 1.1① + 1.1④ 计量。

## 1. 功能视图（8 功能）

F1 供应商与凭据管理 / F2 模型条目管理 / F3 绑定解析（节点级>应用级>默认，能力与窗口检查）/ F4 LLMClient（唯一调用出口：流式、错误归一、准入、usage 对账；**internalToolExecutionEnabled=false，工具循环归 AgentDriver**）/ F5 计量三档（估算展示/准入判定/usage 对账；校准不跨模型复用）/ F6 背压与配额（供应商 TPM/RPM 令牌桶、应用配额、交互快速拒绝+批量排队）/ F7 数据出域路由（标签集匹配）/ F8 健康检查与熔断。

关键规则：
- **准入计量**：按部署模型适配（jtokkit/`/tokenize`）+ 保守余量；**字符系数必须按模型实测校准，固定 ×1.3 不作普适兜底**；413 → 缩减上下文一档重试一次的恢复策略；
- **出域标签匹配**：数据必需标签集 D ⊆ 端点信任标签集 E 才可路由；**数据未分类 ≠ 公开**（默认仅本地端点，策略可配）；标签词表租户级统一维护；
- **实际路由的模型与端点随最终请求入 attempt 快照**（可重建不变量）。

## 2. 数据模型

`model_provider(code,name,protocol,base_url,trust_labels JSONB,status,health JSONB)` / `model_credential(provider_id,auth_type,secret_ref,tail_hint,rotated_at)` / `model_entry(provider_id,model_name,display_name,context_window,max_output_tokens,capabilities[],status)` / `model_call_log(provider,model,run_id,estimated/prompt/completion/total_tokens,duration_ms,status,error_code,egress JSONB,created_at)` / `quota_config(provider_id,tpm,rpm,app_code,user_principal,app_concurrency,policy)`。

## 3. 核心时序

调用时序：出域路由 → 令牌桶准入 → （超限：429+retryAfter / 排队）→ SSE 流式 → usage 对账回写。错误归一：429→排队、401→告警不重试、5xx→只读重试一次、写语义→UNKNOWN 对账。熔断：按供应商 DEGRADED/半开恢复。

## 4. 接口（报文级示例见整合前分册 §5）

`POST /admin/model-providers`（trustLabels 数组）、`POST /{id}/test`（分段失败原因）、`GET/POST /admin/model-entries`、`PUT /admin/apps/{code}/config`（modelBinding+nodeOverrides）。

## 5. 提示词模板与推理策略（自索引册 §3.2/§3.4 并入，评审 T08 关闭项）

**提示词模板**：`prompt_template(id, code UK, name, description, status)` / `prompt_template_version(template_id, version_no UK, status DRAFT|PUBLISHED|ARCHIVED, content TEXT, variables JSONB[{name,type,required,desc,defaultValue}], output_contract JSONB, sha256, published_at/by)`。接口：`GET/POST /admin/prompt-templates`、`PUT /{id}`（生成新草稿）、`POST /{id}/versions/{v}/publish`、`POST /{id}/versions/{v}/render`（试渲染：传变量值返回成品文本+token 计数）、`GET /{id}/versions`。规则：`{{var}}` 严格插值（未知/未定义 fail-fast）；发布不可变；被引用版本删除拒绝。

**推理策略对象**（应用/LLM 节点级，随版本固化）：

```json
{ "sampling": {"temperature":0, "topP":0.9},
  "output": {"maxTokens":4096, "format":{"type":"none|json","schema":{}},
             "onInvalid":"RETRY|TERMINATE|PASS_WITH_FLAG", "maxRetries":2},
  "context": {"historyTurns":20, "historyTokens":24000,
              "summarization":{"enabled":true,"triggerRatio":0.7}},
  "timeout": {"modelMs":60000, "skillMs":30000},
  "budgets": {"maxSteps":25, "modelCalls":40, "totalTokens":200000,
              "totalDurationMs":600000, "maxDepth":2} }
```

> 本节内容原散于索引册，已并入整合版第二部分；与 §6.1 组装器、§6.6 预算、§3.4 策略引擎的实现定义同源。

---

# 第三部分 技能中心（详细设计 20）

## 1. 功能视图（8 功能）

F1 注册与上架（六步向导：基础→调用定义→Schema→执行属性与守卫→错误语义→连接测试+**选型验收门禁 ≥80%**）/ F2 版本管理（新版本发布时旧版 ARCHIVED，引用保留，旧引用仍可执行）/ F3 技能库与引用（双向查询、停用拦截）/ F4 调用统计（逻辑调用口径、三态分列、失败下钻）/ F5 **技能执行（八步管道）** / F6 对账（结果未知核实三选一）/ F7 Mock（绑定调试运行/草稿，LAST_REAL 复用前重查授权）/ F8 引用维护。

## 2. 六维能力契约（skill_version 核心字段）

基础（description 面向模型/endpoint/auth_ref/timeout/concurrency/output_max_bytes）+ 调用形态（SYNC/SUBMIT_QUERY/CALLBACK）+ 执行属性（readonly/sideEffect/idempotent/cancellable）+ 调用权限（roles/guardAttrs——**查询语义技能缺失守卫声明→发布阻断**）+ 返回内容四份（modelSummary/structured/fileRef/cardTemplate）+ 错误语义五分类映射 + 资源约束。

## 3. 执行器八步管道（分支全表）

①参数 Schema 校验（失败→回灌重试计步，连续 2 次→反问）→ ②守卫链（重校验→交集→出域；空交集→NO_PERMISSION 回灌，不重试）→ ③**登记与去重（登记先行：发起外部调用前原子创建调用记录并取得执行权，唯一约束仲裁并发；同步 HTTP 同样先登记）** → ④Mock 判定（仅 DEBUG）→ ⑤适配执行（HTTP/MCP/ALGORITHM 确定性 Job 名；取消令牌传播）→ ⑥结果验收（**接收即计数、有界缓冲、流式转存**；错误分类五语义）→ ⑦信封封装（归属链+四份内容）→ ⑧**执行事实落库**（attempt/响应/守卫留痕；**逻辑结果与事件由引擎统一提交**，回调幂等、失败可恢复消费）。

## 4. 数据模型

`skill / skill_version（六维契约全字段）/ skill_reference / skill_mock（owner_type DEBUG_RUN|APP_DRAFT + LAST_REAL 来源授权）/ stat_invocation`。工具调用登记表 `tool_call` 权威定义在第五部分（引擎册）。

---

# 第四部分 编排与画布 + 图定义契约（详细设计 30）

## 1. 功能视图（6 功能）

F1 流程画布（X6 拖拽/连线/属性面板 Schema 驱动/错误定位）/ F2 **图定义契约（结构+执行规则+类型规则，唯一权威定义）** / F3 流程校验（五组检查器）/ F4 流程解释器（语义分派推进）/ F5 节点处理器族（START/END/LLM/SKILL/CONDITION/CODE/RETRIEVE/CONFIRM + AGENT/ONTOLOGY 预留）/ F6 异常处理（节点级三分支）。

## 2. 图定义契约（v1.0 正文）

**结构**：`{definitionVersion, trigger:TASK|CHAT, entry, inputs[], outputs[], nodes[{id,type,name,skillRef|promptRef|modelOverride|branches|expression|errorPolicy…}], edges[{from,to,when}], joins[{nodeId,semantics:ALL|ANY,skipCountsAs}], executionRules{parallel,maxParallel,cyclesForbidden}}`。

**执行规则**：①无环（DAG 检测，Agent 循环封装在 AGENT 节点内）；②并行：普通节点多无条件出边=并行就绪；③汇合：ALL（全部成功或 SKIPPED）/ ANY（任一成功即就绪并锁定获胜分支，其余 ABANDONED；**取消范围仅限无其他有效消费者的专属剩余分支**，共享上游只标记不取消；全部 FAILED/失败混合跳过无成功可能 → 汇合 FAILED/SKIPPED 确定终态）；④SKIPPED 传播；⑤错误分支带 `${error.*}`；⑥并行写禁令；⑦**ANY 汇合细则**：获胜锁定/迟到结果留痕/ABANDONED 连锁 SKIPPED/全节点终态才允许 run 结束。

**类型规则**：作用域（input 只读/node 输出槽/context 只读）；JSONPath 子集（点路径+`[*]`）；空值→null、required 缺失→运行期错误；**数组映射不一致默认报错，显式截断策略才 WARN**；转型仅显式函数。

**三数据分离**：业务定义（进摘要/trace）与画布展示数据分离存储。

## 3. 校验与表达式

五组校验（结构/参数/类型/引用/守卫声明）分组 ERROR/WARN；**运行期校验统一**（发布拦不住的三类：输入缺失/上游 null/第三方响应不符 → 记 node_instance.error{semantic,path,reason} 按 errorPolicy 走，结构错误不可重试修复）。表达式白名单：字面量/变量/比较/逻辑/算术/`isEmpty size toNumber toString contains`；禁 new/T()/方法调用/赋值/循环；50ms 超时+审计。

## 4. 节点处理器 SPI

`NodeHandler{type(); execute(NodeInstance, FlowContext, DriveListener)}` 注册表——模块 2 本体节点=新增处理器，不改引擎。

---

# 第五部分 运行时引擎（详细设计 31）

## 1. 功能视图（10 功能）

F1 运行接入与准备（验签/钉版本链/GuardContext/DEBUG 冻结草稿快照）/ F2 上下文组装（七步管道）/ F3 Agent 受控循环 / F4 流程驱动 / F5 技能调用编排（并行+确认分流）/ F6 运行调度器（认领/租约/接管/分池）/ F7 执行语义（幂等登记/对账/取消三态/调用级检查点）/ F8 人工确认控制 / F9 预算与终止 / F10 事件持久化与补发。

## 2. trace 权威模型（唯一权威定义，其他册只引用）

`run（app_version_id/trigger_type/status 六态+三等待/stop_reason/principal/tenant/conversation/input_ref/output_ref/version_refs{…,ontology 调用级版本集合}/guard_ctx/debug_snapshot/budget/时间）`；
`node_instance（node_def_id NULL+step_seq/node_type/join_semantics/status 十态/lease_owner/lease_until/**committed_attempt_id**/committed_output_ref/error/digest/时间，UNIQUE(run_id,step_seq)）`；
`attempt（node_instance_id/attempt_no/idempotency_key/request_snapshot_ref ★先落库再发送/routed_model 实际路由端点/response_ref/error/时间，UNIQUE(node_instance_id,attempt_no)）`；
`tool_call（call_seq/skill_version_id/args_digest/idempotency_key UK/status 七态含 ABANDONED/result_ref）——登记先行，外部调用前原子创建并取得执行权`。

**逻辑结果原子提交**：`UPDATE node_instance SET committed_attempt_id=:a … WHERE id=:nid AND committed_attempt_id IS NULL AND lease_owner=:me AND lease_until>now()` 影响行数=0 → 降级留痕；**同事务**写 LOGIC_RESULT_COMMITTED/NODE_FINISHED 事件（seq 同事务取号）。

**调用生命周期三恢复窗口**：W1 登记后崩溃（REGISTERED→按登记参数执行）/ W2 已提交外部未知（UNKNOWN→对账，确定性 Job 名+绑定表复用）/ W3 事实落库回调失败（引擎按 SUCCEEDED 未提交记录轮询补提交）。**分工**：技能中心=执行事实；引擎=逻辑结果/状态/事件唯一提交者。

**双驱动**：AgentDriver（受控循环：终止规则六情形+四类预算+无进展指纹+混合返回处理，**工具循环由 Driver 控制，Spring AI internalToolExecutionEnabled=false**）；FlowDriver（按汇合语义分派推进：ALL/ANY 细则、SKIPPED 传播、错误三分支；ANY 取消仅限专属剩余分支，全失败→FAILED/SKIPPED 确定终态）。共享器官：组装器/网关/执行器/trace。

**隔离矩阵**：数据隔离四落点（查询侧守卫链/结果侧归属链投影/上下文侧无共享状态/租户贯穿）+ 操作隔离五落点（执行权/并发信号量三类/确认态仅挂依赖支路/取消按 run 精确作用/版本钉定）；三处引擎外配套（数据源强制/产物鉴权/IAM）。

## 3. 事件与补发

持久事件表（run 内单调 seq，与状态更新同事务）；SSE 观察通道断线不杀任务；Last-Event-ID 补发/去重；chunk 增量与事实事件分离；游标过期返回水位快照。

---

# 第六部分 调试器（详细设计 32）

**功能**：F1 调试运行管理（以草稿为源，创建时冻结调试快照）/ F2 全量预览 / F3 单步调试（**prompt 全文+装配清单+变量快照可见**）/ F4 断点（存展示数据）/ F5 Mock（FIXED/LAST_REAL，复用前重查授权）/ F6 事件流（seq 补发去重、断线不杀任务）/ F7 测试输入管理（可提升为评测用例）。

**挂起与放行规则**：挂起标识=nodeInstanceId（实例）+pauseSeq（=WAITING_STEP 事件 seq，持久化）；resume 携带三元组+requestId，DB 状态 CAS 判定生效，requestId 去重防延迟误放；**跨副本**：挂起权威在 DB，任意副本处理；超时 30 分钟/全局上限/孤儿清理。

**可见性**：prompt 全文+装配清单（凭据永不出现）；技能真实响应摘要+富载荷；走资源归属检查。

---

# 第七部分 管理与发布（详细设计 40）

**功能**：F1 应用工作台（检索/标签/**正式运行入口**：对话型跳 WebApp、流程型输入表单选版本提交，未发布禁用）/ F2 生命周期（DRAFT→PUBLISHED→DISABLED→ARCHIVED，修改=新草稿，删除仅 DRAFT）/ F3 发布管理（**两阶段**：precheck 不落版本→commit 绑定 draftRev+候选摘要+评测报告版本+幂等请求号，重复提交返回同一版本；预检后改稿→412 重预检；回滚=指回历史版本）/ F4 运行监控 / F5 执行记录与回放三级（**本体实际调用版本集合展示**）/ F6 确认中心（角色解析确认人，参数变更重确认）/ F7 集成管理（四件套/密钥签发吊销/域白名单/声明范围上限/主题/版本钉定）/ F8 统计报表。

**发布检查器六组**：结构/参数/类型与无效引用/引用有效性/守卫声明/评测门禁（安全 100%+通过率阈值+无新增回归）——ERROR 阻断，WARN 入报告。

**集成四件套**：WebApp / assistant.js 嵌入套件（出票 JWT ≤5min、声明范围上限裁剪）/ Service API 文档 / 集成密钥（吊销秒级生效，已建立 SSE 完成后关闭）。体验三细节：业务上下文注入/结果卡片（摘要与富载荷分离）/会话三维归属。

---

# 第八部分 前端工程与设计系统（详细设计 60/61）

**技术栈**：Vue 3.5+TS+Vite+Pinia+Element Plus+AntV X6(vue-shape)+ECharts；SSE 用 **fetch 流式客户端**（携带 Authorization，原生 EventSource 弃用）；设计系统=Element Plus 基座+平台设计 tokens。

**四端**：admin（门户，一级导航=技术要求四子模块+系统支撑组）/ webapp / assistant.js（Shadow DOM） / 模块包 flow-designer+debug-panel。

**页面清单**（按子模块，摘要）——智能体管理：应用列表（含正式运行入口）/概览/运行/记录；编排工作区：画布（流程型）/配置表单（对话型）/调试/发布向导（**两阶段**）；模型推理配置：供应商/条目/提示词模板；技能管理：库/注册向导/详情；运行与确认：全局监控/回放/确认中心；支撑：评测/审计/系统管理。

**自适应布局（桌面四断点）**：尺寸两分法——控件尺寸 `--ctl-*` 恒定（悬浮球/顶栏高），布局尺寸 `--lay-*` 必须弹性（clamp/flex/百分比，**禁容器级固定像素**）；断点 ≥2560 宽松/1920 标准/1440 标准/1366 紧凑下限（侧栏折叠、隐藏次要列），<1366 横向滚动兜底；rem+密度三档用户可切换；画布 ResizeObserver+zoom-to-fit；ECharts 随容器重绘。

**设计 tokens**：品牌色（默认蓝，VI 后整体替换）/运行状态五色**双色制**（填充基色+文字 700 级深变体，对比度 ≥4.5:1）/中性灰阶/ECharts 八色板；间距 4 的倍数；圆角卡片 8/按钮 6/气泡 12；阴影两级；admin 正文 13px 紧凑、对话端 15px 宽松；**深色模式一期不做，tokens 成对预留**。

**前端流程**：登录守卫/画布保存双向转换/**调试联动（SSE 事件→节点变色+检查器）**/对话流式渲染（raf 批量）/嵌入助手初始化（出票握手）/发布向导两阶段。

**安全规则**：不可信内容渲染——Markdown DOMPurify 消毒、URL 协议白名单、卡片动作白名单、产物受控下载；token 不进 localStorage。

---

# 第九部分 评测模块（M4 轻量版，详见主设计 6.9）

用例库（dataset/dataset_case 版本化，四类标准：能力正确性/安全合规零容忍/稳健性/质量体验 rubric）→ 三档判分器（规则判分/LLM-as-judge 含校准集/人工标注二期）→ 报告（版本对比矩阵/失败归因标签/质量时间线）→ **发布门禁**（安全 100%+通过率阈值+无新增回归，并入 1.2④ 检查器）→ 处置闭环（归因四去向/回归防逃逸/反馈回流/版本联动重评）。分期：M4 轻量版 4~6 人周；LLM-judge/标注/A-B 二期。

---

# 附录 A 复用与借鉴出处汇总（对照《物料清单》O1~O16）

| 册 | 直接依赖 | 机制复用 | 读码借鉴 |
|---|---|---|---|
| 模型接入 | Spring AI、jtokkit、vLLM `/tokenize`、Resilience4j | Redis Lua 令牌桶 | One-API/LiteLLM（管理面可选） |
| 技能中心 | MCP Java SDK、swagger-parser、K8s Client、networknt、RestClient+Resilience4j | — | dsh `core/tools`、OpenAI function calling 规范 |
| 编排画布 | AntV X6、SpEL（受限）、networknt | — | Dify workflow、LangGraph4j |
| 运行时引擎 | Spring AI（单次调用）、PostgreSQL SKIP LOCKED、SSE 标准 | — | dsh `core/system-prompt`、`core/session`、`core/agent-loop`、`llm/token-meter`、`compaction` |
| 调试器 | Monaco/CodeMirror、SSE | — | Dify 调试预览、ZCode/Codex（黑盒） |
| 前端 | Vue3/Vite/Pinia/Element Plus/X6/ECharts/openapi-typescript | — | LangSmith（评测形态）、Coze（渠道分发） |
| 可观测 | OTel+Prometheus+Grafana+Loki+Tempo（随交付、对客户不暴露） | — | — |

**纯自研（差异化核心）**：调用登记制与幂等键、逻辑结果唯一提交、守卫交集与出域标签、图定义契约与五组检查器、ANY 取消范围、终止规则与四类预算、调试器、回放三级、本体调用级版本集合。

---

# 附录 B 与分册的对照

| 整合版部分 | 原分册 |
|---|---|
| 第一部分 | 详细设计00-总册 |
| 第二部分 | 详细设计10-模型接入 |
| 第三部分 | 详细设计20-技能中心 |
| 第四部分 | 详细设计30-编排与画布 |
| 第五部分 | 详细设计31-运行时引擎 |
| 第六部分 | 详细设计32-调试器 |
| 第七部分 | 详细设计40-管理与发布 |
| 第八部分 | 详细设计60-前端工程 + 61-前端设计系统 |
| 第九部分 | 主设计 6.9 评测模块 |


---

# 以下为九册分册全文（合并归档，内容与上方各部分对应）

---

# 详细设计 00 —— 总册：通用约定、工程结构与公共接口签名

> 定位：文档族的**公共依赖收拢册**。各分册（10/20/30/31/32/40/50）的通用约定、工程结构、公共接口签名、错误码总表全部收拢于此；分册只写自己的专属内容，公共内容一律引用本册。
> 阅读顺序建议：本册 → 所在模块分册 → 需要时查上位设计。

---

## 1. 文档族索引

| 册 | 文档 | 承接内容 | 主要读者 |
|---|---|---|---|
| 主设计 | 智能体构建平台设计-自研路线 v3.6 | 总体架构/机制详设/参考实现定位/里程碑 | 全员 |
| 索引 | 模块1详细设计（索引册） | 17 项需求映射与验收汇总 | 项目管理/测试 |
| **00 总册** | **本文** | 通用约定/工程结构/公共接口/错误码 | 全员 |
| 10 | 模型接入 | 1.1① + 1.1④ 计量 | 后端 |
| 20 | 技能中心 | 1.4①~④ + 执行器 | 后端 |
| 30 | 编排与画布 | 1.2①③ + 图定义契约 | 前端+后端 |
| 31 | 运行时引擎 | 1.2 执行侧 + 对话型驱动 | 后端 |
| 32 | 调试器 | 1.2② | 前端+后端 |
| 40 | 管理与发布 | 1.3①~⑤ + 1.2④ + 集成四件套 | 前端+后端 |
| 50 | 评测（M4） | 评测三件套（详见主设计 6.9；**不另立分册，归属主设计与 M4 交付**；模块 1 的 17 项不含评测） | 后端 |
| 60 | 前端工程 | 四端工程（admin/webapp/assistant/flow-designer）/页面清单/组件架构/前端流程 | 前端 |
| 61 | 前端设计系统 | 三端风格规范/design tokens/组件风格/交互规范 | 前端+UI |
| 参考 | 对话总结 / 物料清单 / 《问题》《问题2》 | 概念与决策依据、选型依据、评审处置 | 全员 |

---

## 2. 技术底座

| 项     | 选型                                         | 说明                                                                 |
| ----- | ------------------------------------------ | ------------------------------------------------------------------ |
| 语言/框架 | Java 21 + Spring Boot 3.4.x / 3.5.x        | 虚拟线程必需 21；**Boot 版本锁定 Spring AI 1.0 官方兼容矩阵（3.4.x/3.5.x），不用 3.3.x** |
| 数据库   | PostgreSQL 15+                             | **唯一权威状态源**；Redis 只做缓存/限流                                          |
| 缓存    | Redis 7.x                                  | 活跃会话/信号量/限流，非真相源                                                   |
| 对象存储  | MinIO（S3 兼容）                               | 产物/文件，租户前缀+预签名下载                                                   |
| 编排    | K8s                                        | 引擎多副本+算法 Job                                                       |
| 迁移    | Flyway                                     | 契约→DDL 版本化                                                         |
| 可观测   | OTel + Prometheus + Grafana + Loki + Tempo | 自部署                                                                |

---

## 3. 工程结构与服务拆分

### 3.1 服务拆分

```
agent-platform/
├── platform-gateway/    接入网关：双认证（门户 JWT/外部 ApiKey+出票）/限流/CORS/SSE 转发
├── engine-runtime/      运行时引擎（册 31）：双驱动/组装/调度/执行语义/确认/调试/事件
├── model-gateway/       模型接入（册 10）：供应商/绑定解析/LLMClient/计量/背压/出域路由
├── skill-registry/      技能中心（册 20）：六维契约/注册/版本/执行器/统计
├── app-management/      应用管理（册 40）：工作台/生命周期/发布/集成四件套
├── monitor/             运行监控与评测（册 40/50）：监控/回放/确认中心/评测
├── admin-common/        公共：鉴权/审计/错误码/公共字段/trace 关联
└── web/                 前端：flow-designer(画布) / admin(门户) / webapp(对话) / assistant.js(嵌入)
```

### 3.2 服务间调用规则

- `gateway → 各服务`：唯一外部入口；
- `engine-runtime → model-gateway / skill-registry`：执行期唯一两条外调；
- `app-management / monitor` 只读引擎数据（run 表），不直调引擎内部；
- 服务内模块间**禁止循环依赖**；跨服务一律 HTTP + 显式契约。

### 3.3 后端包结构模板（各服务统一）

```
<service>/
├── api/          Controller（入参出参 record）
├── application/  应用服务（用例编排，事务边界）
├── domain/       领域模型与状态机（纯 Java，无框架依赖）
├── infra/        仓储实现/外部客户端/配置
└──共通: error/ trace/ security/
```

---

## 4. 通用约定

### 4.1 公共字段（所有业务表默认，DDL 省略）

`id BIGINT PK`、`tenant_id VARCHAR(64)`、`created_by VARCHAR(64)`、`created_at TIMESTAMPTZ`、`updated_by`、`updated_at`、`deleted BOOLEAN DEFAULT false`、`remark`。

### 4.2 ID 与编码

- 内部主键 BIGINT；对外标识一律 `code`（租户内唯一，人工可读）或发布版本号；
- 运行标识 `runId` 对外格式 `r_<序号>`；**幂等键采用调用登记制**：每个逻辑调用首次发起前原子登记（tool_call 记录）并生成幂等键 `hash(run_id + node_instance_id + call_seq + skill_version_id)`，重试/恢复/接管复用登记值——**不同逻辑调用身份不同，同一调用的恢复与重试复用登记身份**；
- K8s Job 名 = `alg-` + hash(幂等键)（确定性命名防重）。

### 4.3 时间与状态

- 全部 `TIMESTAMPTZ`（UTC 存储，展示按租户时区）；状态枚举一律 VARCHAR 大写，集中定义于状态机类；
- 三态标记贯穿：`FORMAL / DEBUG / EVALUATION`（正式/调试/评测，统计与门禁按此隔离）。

### 4.4 统一响应壳

```json
{ "code": "OK", "message": "", "data": {…}, "traceId": "…" }
```

### 4.5 错误码总表

| 错误码                             | 含义                        | 来源册    |
| ------------------------------- | ------------------------- | ------ |
| SYS-MODEL-4001 / 4002           | 绑定解析失败 / 模型能力不足           | 10     |
| SYS-MODEL-401 / 429 / 503 / 504 | 凭据无效 / 配额限流 / 熔断中 / 超时    | 10     |
| SYS-MODEL-413                   | 上下文超限（准入档兜底）              | 10     |
| SYS-EGRESS-4031                 | 数据不可出域且无合规端点              | 10     |
| SYS-GW-429                      | 网关限流                      | 40     |
| SYS-APP-4002 / SYS-APP-4031     | 应用已停用 / 资源越权              | 40     |
| BIZ-APP-3001                    | 发布检查未通过                   | 40     |
| BIZ-SKILL-0001 / 1002           | 编码重复 / 契约校验失败             | 20     |
| BIZ-SKILL-2001~2004             | 验收未过 / 校验未过 / 验收门禁 / 引用未清 | 20     |
| BIZ-EVAL-3001                   | 评测门禁安全用例失败                | 50(M4) |

### 4.6 可重建不变量（引擎运行时断言）

凡进入模型请求的内容（系统提示分节/快照/历史/工具结果/裁剪与压缩）必须可从 run 消息日志重建；**最终请求材料先持久化成功、再发送模型请求**（材料清单：最终提示/消息顺序/工具 Schema/模型与参数/快照与压缩版本；摘要压缩自身的模型调用同样入记录）。

---

## 5. 公共组件接口签名汇总（跨册引用，签名变更须走契约评审）

```java
// —— 模型接入（册 10）——
interface LLMClient {
    ModelStream call(ModelRequest req);            // 流式；内部完成出域路由/配额/计量/错误归一
    ModelResponse callSync(ModelRequest req);      // 评测跑批用
}
interface ModelBindingResolver { ResolvedModel resolve(AppVersionRef v, String nodeId); }
interface QuotaService { AcquireResult acquire(String providerId, int estTokens, AppRef app); }

// —— 技能中心（册 20）——
interface SkillExecutor {
    CompletableFuture<ResultEnvelope> execute(SkillInvocation inv);
}
record SkillInvocation(String tenantId, SkillVersionRef skill, JsonNode arguments,
                       String principalId, GuardContext guard, String idempotencyKey,
                       String callId,      // 持久化调用身份（tool_call 主键），登记制生成
                       InvokePolicy policy, CancelToken cancel) {}
record ResultEnvelope(OwnershipChain owner, ResultStatus status,
                      ErrorSemantics error, String modelSummary,
                      JsonNode structured, List<ArtifactRef> fileRefs, String cardPayloadJson) {}

// —— 运行时引擎（册 31）——
interface RunDriver { CompletableFuture<RunOutcome> drive(RunContext ctx, DriveListener l); }
interface NodeHandler { String type(); NodeResult execute(NodeInstance n, FlowContext c, DriveListener l); }
class ContextAssembler {
    AssembledRequest assemble(RunContext ctx);
    AssembledRequest assembleForNode(LLMNodeConfig cfg, FlowContext ctx);
}
interface EventStore { void append(RunId runId, EventType type, JsonNode payload);   // 与状态同事务
                       List<RunEvent> replayAfter(RunId runId, int lastSeq); }

// —— 守卫（册 31/20 共用）——
interface GuardResolver {
    GuardContext snapshot(Principal p);                                          // run 创建时
    AllowedScope intersect(GuardContext g, Optional<JsonNode> modelProposed);    // 执行时（默认拒绝）
}

// —— 发布（册 40）——
interface PublishCheck { CheckReport check(AppDraft draft); }   // 六组检查器责任链 SPI

// —— 模块 2 预留 ——
interface VocabularyProvider { VocabSection sectionFor(AppVersionRef v, Principal p); }  // 词表注入
```

---

## 6. 非功能约定

### 6.1 安全

- 双认证：门户用户（JWT：受众/范围/出票方声明上限）+ 外部系统（Service ApiKey / 出票 JWT）；
- 数据与控制分离（模型输出是数据不是指令）；守卫交集原则+默认拒绝；
- 凭据只存引用（密文），prompt 与日志中永不出现；出票系统只能声明授权范围内属性；
- 数据出域规则在每次模型调用前对完整上下文检查；用户令牌不透传下游。

### 6.2 性能与内存（细则见册 31 §6.3）

- 异步状态机运行时；虚拟线程 + 三类信号量（模型/技能/组装并发）+ 舱壁分池；
- 六道内存硬闸（技能输出流式转存/历史按需加载/事件从 DB 补发/表达式缓存封顶/线程池封顶/直接内存设限）随 M1 落地；
- 背压：满载快速拒绝（交互）或排队（批量），绝不硬扛到 OOM。

### 6.3 可观测

- OTel span 以 run_id/node_instance_id 为业务关联键；Grafana 看板（并发运行/成功率/耗时/token）；
- 业务 trace = run/node_instance/attempt + 守卫留痕 + 裁剪日志 + 装配清单（可重建不变量支撑回放）。

### 6.4 部署

- 引擎无状态多副本（HPA 按并发运行数）；JVM：分代 ZGC + `MaxRAMPercentage=70` + 容器 limit=堆×1.4 + `MaxDirectMemorySize` 显式设限；
- 压测基线：500 并发运行、首 token p95、单副本 SSE 5000+（M4 验收）；
- **可观测栈交付策略（已决策）**：OTel + Prometheus + Grafana + Loki + Tempo **随平台一并交付部署**，定位为**交付方运维排障设施**（数据留在客户环境，合规）。**对客户业务用户不暴露**，四条措施：①端口仅限运维网络可达，不对业务网段开放；②不进门户导航与用户功能菜单；③用户手册与功能文档不含此栈说明（仅运维部署附录涉及）；④Grafana/各组件仅运维账号可访问。客户侧的运行监控统一使用平台自带的"运行中心"页面（自研，不依赖此栈）。资源占用（约 2~4GB 内存+按保留期磁盘）列入交付清单提前告知。

---

## 7. 变更管理

- **契约变更**（四份契约/本册公共签名）：版本化 + 评审 + 受影响分册联动修订；
- **主设计演进**：v3.x 版本记录追溯；分册与主设计冲突时以主设计为准并提修订；
- **模块 2 接入**：走 §预留接口（节点处理器/词表提供者/ontology_version 字段/守卫属性升级/检索技能换实现），不改动既有分册结构。

---

## 8. 待决事项（同《模块1详细设计》§11）

契约评审人与冻结日期；供应商管理面取舍；vLLM 实测排期；本体接口人。~~前端框架与设计系统~~ **已决策（2026-10-19）：Vue 3.5 + TypeScript + Element Plus + AntV X6，设计系统=Element Plus 基座+平台设计 tokens（详见册 60）。**


---

# 详细设计 10 —— 模型接入（技术要求 1.1① + 1.1④ 计量部分）

> 上位：《智能体构建平台设计-自研路线》v3.6；《模块1详细设计》索引
> 本文六视图：功能视图（§1）→ 组成视图（§2）→ 流程视图（§3 六条时序）→ 数据视图（§4 全字段 DDL）→ 接口视图（§5）→ 规则视图（§6 解析/计量/背压/出域规则表）→ 验收（§8）
> 技术底座：Spring AI（引擎内客户端）/ RestClient / Redis（令牌桶 Lua）/ networknt
> 职责澄清：**Spring AI = 引擎内模型客户端**（本册）；**供应商管理面**（Key/配额/统计，本册 §2 的 GatewayAdmin，一期可用 One-API 替代）——两层职责分离，非二选一。

---

## 1. 功能视图：功能清单与功能设计

模型接入共 8 个功能：**管理组**（F1~F3，管理员使用）与 **运行组**（F4~F8，引擎消费）。

| 编号 | 功能 | 一句话职责 | 使用者 |
|---|---|---|---|
| F1 | 供应商与凭据管理 | 协议/地址/凭据的登记、测试、启停 | 管理员 |
| F2 | 模型条目管理 | 供应商下可用模型的参数上限与能力登记 | 管理员 |
| F3 | 绑定解析 | 应用级/节点级模型绑定的运行时解析 | 引擎 |
| F4 | 模型调用客户端 | 统一协议的流式/非流式调用（Spring AI 封装） | 引擎 |
| F5 | 计量三档 | 估算展示 / 准入判定 / usage 对账回写 | 引擎+报表 |
| F6 | 背压与配额 | 供应商令牌桶、应用配额、排队与快速拒绝 | 引擎 |
| F7 | 数据出域路由 | 按数据密级/租户要求选择本地或云端端点 | 引擎 |
| F8 | 健康检查与熔断 | 供应商探活、故障熔断与恢复 | 运维/引擎 |

### 1.1 F1 供应商与凭据管理

**功能描述**：把"用哪些大模型、从哪调、凭什么调"登记成平台数据。供应商 = 协议+地址（OpenAI 兼容 / Ollama）；凭据 = 密钥的**密文引用**（平台库存密文或环境变量引用），界面上只可替换不可查看；模型条目 = 供应商下实际可用的模型及其窗口/输出上限/能力标记。启用前必须通过连接测试（列模型+短对话），测试失败给出分段原因（AUTH / ENDPOINT / MODEL）。
**输入**：供应商配置 + 凭据。**输出**：可被应用绑定的模型条目。
**规则**：凭据只写不读（界面仅显示尾四位）；禁用供应商时，已发布应用**运行不受影响**（发布时已快照），但新发布被检查器拦截；凭据轮换立即生效（每次调用实时解析引用）。

### 1.2 F2 模型条目管理

**功能描述**：登记每个供应商下可用的模型清单及其元数据——`context_window`（上下文窗口）、`max_output_tokens`（输出上限）、`capabilities`（chat/tools/vision/json_mode）——这些元数据是**组装分账与发布检查的依据**：预算分配读窗口，工具调用能力检查读 capabilities（不支持 function calling 的模型不允许绑定到使用工具的智能体，发布检查拦截）。
**输入**：管理员登记或"从供应商拉取模型清单"自动发现。**输出**：模型条目清单。

### 1.3 F3 绑定解析

**功能描述**：运行准备阶段的模型决议。优先级：**节点级绑定 > 应用级绑定 > 平台默认**；解析输出 `ResolvedModel{entry, credential, 参数}` 供组装与调用使用。解析同时做两项检查：模型 `capabilities` 是否覆盖本智能体/节点需要（用了工具的智能体绑了不支持 function calling 的模型 → 发布检查拦截）；窗口是否 ≥ 策略要求的最低配额。
**输入**：应用版本绑定 + 节点覆盖 + 策略。**输出**：ResolvedModel。

### 1.4 F4 模型调用客户端（LLMClient）

**功能描述**：全平台唯一的模型调用出口（Spring AI ChatClient 封装）。承担：流式与非流式调用、消息模型转换、工具定义传递（**工具循环由 AgentDriver 控制，配置 `internalToolExecutionEnabled=false` 关闭框架自动执行**——守卫/版本/trace 要包在调用外层；不使用"内部管理模式"这一易误解叫法）、调用超时、错误归一（把各供应商错误归一为平台错误码）、**调用前准入**（配额获取+计量准入档）、**调用后对账**（usage 回写 model_call_log）。
**输入**：ModelRequest{resolvedModel, messages, tools, params, egress标记}。**输出**：ModelStream（token 流+finish_reason+usage）。
**规则**：所有模型调用必经本客户端（编码规范）；凭据永不入 prompt。

### 1.5 F5 计量三档

**功能描述**：①**估算档**（固定密度 4 字符/token+结构开销）：只用于容量规划与分账展示；②**准入档**：发送前按部署模型适配计量（OpenAI 系 jtokkit；其余走供应商 `/tokenize`）**加保守余量判定能否准入——字符估算系数必须按模型实测校准，不得以固定 ×1.3 作为普适兜底**（中文/JSON Schema/请求格式差异都会放大误差）；准入失败 → 组装器走超限三分支；若云端拒绝上下文超限（413），执行**超限恢复策略**：自动缩减上下文一档后重试一次，仍超限才失败；③**对账档**：响应回包 usage 为实际消耗，回写 model_call_log，并持续修正估算系数。**校准系数不跨模型/工具集/请求格式复用。**
**输入**：组装产物。**输出**：分账表（展示）、准入判定（门）、usage 记录（账）。

### 1.6 F6 背压与配额

**功能描述**：按供应商维护令牌桶（TPM/RPM 双桶）、按应用与用户维护配额；调用前获取许可，不足则**排队（带预计等待）或快速拒绝**——选择由策略决定（交互流量默认快速拒绝+提示，批量为排队）。满载时绝不 silently 硬扛导致全体超时。
**输入**：调用请求的预估 token 与供应商。**输出**：许可/排队位置/拒绝。

### 1.7 F7 数据出域路由

**功能描述**：按数据的密级/来源标记与租户要求，决定本次调用路由到哪个信任域的端点——不可出域数据只允许路由本地端点；要求物理隔离的租户使用专属端点。路由在**每次调用前**对完整组装上下文执行（上下文中任何部分带不可出域标记即整体降级本地）。
**输入**：组装上下文的出域标记 + 端点信任域登记。**输出**：实际使用的端点。

### 1.8 F8 健康检查与熔断

**功能描述**：定时探测（列模型+1 token 心跳），连续失败进入熔断（标记 DEGRADED，管理门户显示）；熔断期间新调用快速失败 `SYS-MODEL-503` 并提示可切换供应商；半开探测恢复后自动闭合。
**输入**：定时器。**输出**：供应商健康状态。

---

## 2. 组成视图：模块组成与实现技术

### 2.1 组件组成图

```
┌──────────────────── model-gateway ─────────────────────┐
│ 入口                                                    │
│  ModelAdminController(门户)   LLMClient(引擎内部调用)     │
├─────────────────────────────────────────────────────────┤
│ 管理面（GatewayAdmin）                                   │
│  ProviderService │ CredentialService │ ModelEntryService │
│  HealthProbe(定时+熔断)                                  │
├─────────────────────────────────────────────────────────┤
│ 运行面                                                   │
│  ModelBindingResolver(解析) │ QuotaService(令牌桶/配额)   │
│  MeteringService(三档计量)  │ EgressRouter(出域路由)      │
│  LLMClient(Spring AI 封装)  │ ErrorNormalizer(错误归一)   │
└──────────────┬───────────────────────────────────────────┘
               ↓（唯一出口）
   DeepSeek API │ vLLM(本地) │ Ollama │ 其他 OpenAI 兼容端点
```

### 2.2 组件职责与实现技术

| 组件 | 实现技术 | 职责 |
|---|---|---|
| LLMClient | Spring AI ChatClient（**internalToolExecutionEnabled=false**）+ 虚拟线程 | 唯一模型调用出口；流式/非流式；错误归一 |
| ModelBindingResolver | @Service | 两级绑定解析+能力检查 |
| MeteringService | jtokkit（OpenAI 系）+ 字符估算×保守余量（其余）+ usage 回写 | 三档计量 |
| QuotaService | Redis Lua 令牌桶（TPM/RPM 双桶）+ 配额表 | 背压与许可 |
| EgressRouter | @Service + 端点信任域登记表 | 出域路由 |
| HealthProbe | @Scheduled + Resilience4j CircuitBreaker（按供应商） | 探活/熔断/半开恢复 |
| ErrorNormalizer | 映射表 | 供应商错误 → 平台错误码（`SYS-MODEL-429/401/503/504`） |

**依赖纪律**：引擎组件只依赖 `LLMClient` 与 `ModelBindingResolver` 接口；供应商具体协议差异全部封在 LLMClient 内；**任何组件不得绕过 LLMClient 直连供应商**（保证计量、配额、出域、审计全覆盖）。

### 2.3 关键接口签名

```java
interface LLMClient {
    ModelStream call(ModelRequest req);        // 流式（默认）
    ModelResponse callSync(ModelRequest req);  // 非流式（评测跑批用）
}
record ModelRequest(ResolvedModel model, List<Message> messages,
                    List<ToolDef> tools, JsonNode params,
                    EgressDomain egress, String runId,
                    String invocationRef) {}   // attempt_id+sendSeq：定位本次发送的快照版本
interface ModelBindingResolver { ResolvedModel resolve(AppVersionRef v, String nodeId); }
interface QuotaService { AcquireResult acquire(String providerId, int estTokens, AppRef app); }
```

---

## 3. 流程视图：核心流程时序（含异常分支）

### 3.1 供应商接入时序（管理动作）

```
管理员 → 新建供应商（协议/地址/凭据引用）→ POST /admin/model-providers
  → CredentialService：凭据密文入库（仅存引用）
  → 连接测试：列模型 + 1 token 心跳 → 分段失败原因（AUTH/ENDPOINT/MODEL）
  → 登记模型条目（手工或自动发现） → 启用 → 可被应用绑定
```

### 3.2 绑定解析时序（运行准备阶段）

```
RunService.prepare → ModelBindingResolver.resolve(appVersion, nodeId)
  → 节点覆盖？是→用节点绑定 : 应用绑定 : 平台默认
  → capabilities 检查（需要 tools？条目支持？）  窗口 ≥ 策略最低配额？
  → 失败：SYS-MODEL-4002（能力不足，发布检查应已拦截，此处兜底）
  → 成功：ResolvedModel{entry, credentialRef, params} → run.version_refs 记录
```

### 3.3 模型调用完整时序（AgentDriver 每轮）

```
AgentDriver → LLMClient.call(req)
  LLMClient：
   ① EgressRouter.route(req.egress)          ← 出域路由（不可出域→本地端点）
   ② QuotaService.acquire(供应商, 估算token)  ← 令牌桶
        拒绝 → SYS-MODEL-429+retryAfter → AgentDriver 转排队/提示
   ③ 准入计量（适配计量+余量）→ 超限 → 返回 OVERFLOW → 组装器三分支
   ④ HTTP(SSE) → 供应商
        流式 token* → 转发 AgentDriver（SSE 转发）
        usage → MeteringService 对账回写 model_call_log
   错误归一：429→SYS-MODEL-429（排队）｜401→SYS-MODEL-401（凭据，告警）｜
            5xx→按熔断状态处理｜超时→SYS-MODEL-504
```

### 3.4 背压满载时序

```
QuotaService.acquire 令牌不足
  ├─ 策略=交互（默认）→ 快速拒绝 SYS-MODEL-429 + retryAfter 秒
  │    → AgentDriver：可重试类 → 按退避自动重试一次；仍满 → 回灌模型"系统繁忙"
  └─ 策略=批量 → 进入排队（队列位置/预计等待回给调用方）→ 排到后继续
```

### 3.5 数据出域路由时序

```
组装完成 → ContextAssembler 标记数据必需标签集 D（如 {LOCAL} 或 {tenant:T1, secret≤3}；
            任一内容带不可出域标记即整体标记）
  → EgressRouter 按 §6.4 标签匹配：D ⊆ 端点信任标签集 E 才可路由
  无必需标签（数据未分类）→ **不默认视为公开**：按租户"未分类数据策略"处理
  （默认仅路由本地端点，管理员可改）；分类缺失与明确公开是两种状态
  无可用端点 → 拒绝 SYS-EGRESS-4031 + 告警（绝不静默降级）
```

### 3.6 供应商故障熔断与恢复时序

```
HealthProbe 连续 N 次探测失败 → 供应商 DEGRADED（熔断）
  期间新调用快速失败 SYS-MODEL-503（提示可切换供应商）
  半开探测（每 30s 一次 1 token 心跳）→ 成功 → 闭合恢复
应用绑定了故障供应商：运行失败策略由策略配置（等待恢复/切换备用模型绑定）
```

---

## 4. 数据视图：全字段 DDL

> **模型性质声明**：本节为**逻辑模型**——字段名/语义/唯一键为权威定义；列类型与索引以 Flyway 迁移脚本为准（物理 DDL 另出）。字段省略类型标注处默认 TEXT/INT，TIMESTAMPTZ 时间列。

### 4.1 model_provider

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| code | VARCHAR(64) | UK | 业务编码 |
| name | VARCHAR(128) | NOT NULL | 展示名 |
| protocol | VARCHAR(32) | NOT NULL | OPENAI_COMPATIBLE / OLLAMA |
| base_url | VARCHAR(256) | NOT NULL | 端点地址 |
| trust_labels | JSONB | NOT NULL DEFAULT '[]' | **端点信任标签集**（如 ["LOCAL","tenant:T1","secret≤3"]）——数据出域路由按标签集包含匹配（§6.4）；**标签词表由租户级标签字典统一维护**，"tenant-isolated:T1" 等写法不再使用，避免同义标签匹配分歧 |
| status | VARCHAR(16) | NOT NULL | ENABLED / DISABLED / DEGRADED |
| health JSONB | | | {lastProbeAt, consecutiveFailures, circuit} |

### 4.2 model_credential

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| provider_id | BIGINT | FK | |
| auth_type | VARCHAR(16) | | BEARER / BASIC / NONE |
| secret_ref | VARCHAR(256) | NOT NULL | 密文引用（环境变量/密钥管理路径） |
| tail_hint | VARCHAR(8) | | 尾四位提示（界面展示用） |
| rotated_at | TIMESTAMP | | 轮换时间（治理） |

### 4.3 model_entry

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| provider_id | BIGINT | FK | |
| model_name | VARCHAR(128) | NOT NULL | 供应商侧模型名 |
| display_name | VARCHAR(128) | | 展示名 |
| context_window | INT | NOT NULL | 组装分账依据 |
| max_output_tokens | INT | NOT NULL | |
| capabilities | TEXT[] | | chat / tools / vision / json_mode |
| status | VARCHAR(16) | | ENABLED / DISABLED |

索引：`UK(provider_id, model_name)`。

### 4.4 model_call_log

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| provider_id / model_entry_id | BIGINT | | |
| run_id / app_code | | NULL | 关联（可空：健康探测调用） |
| estimated_tokens / prompt_tokens / completion_tokens / total_tokens | INT | | 估算与三档对账 |
| duration_ms | INT | | |
| status | VARCHAR(16) | | OK / ERROR / REJECTED |
| error_code | VARCHAR(32) NULL | | |
| egress | JSONB | | 数据必需标签集与实际路由端点（溯源） |
| created_at | | | IDX(created_at), IDX(provider_id, created_at) |

### 4.5 quota_config

```sql
quota_config(id, provider_id, tpm INT, rpm INT,        -- 供应商桶
             app_code NULL, user_principal NULL,        -- NULL=平台默认档
             app_concurrency INT, policy FAST_REJECT|QUEUE)
```

---

## 5. 接口视图（报文级）

### 5.1 新建供应商

`POST /admin/model-providers`
```json
// 请求
{ "code": "deepseek", "name": "DeepSeek 云端", "protocol": "OPENAI_COMPATIBLE",
  "baseUrl": "https://api.deepseek.com/v1", "trustLabels": ["CLOUD"],
  "credential": { "authType": "BEARER", "secret": "sk-***" } }
// 响应 data
{ "id": 301, "code": "deepseek", "status": "DISABLED", "hint": "连接测试通过后启用" }
```

### 5.2 连接测试

`POST /admin/model-providers/{id}/test`
```json
// 响应 data
{ "ok": true, "latencyMs": 320, "discoveredModels": ["deepseek-chat","deepseek-reasoner"],
  "stage": "OK" }
// 失败：{ "ok": false, "stage": "AUTH", "reason": "401 invalid key" }
```

### 5.3 模型条目

`POST /admin/model-entries`
```json
// 请求
{ "providerId": 301, "modelName": "deepseek-chat", "displayName": "DeepSeek V3",
  "contextWindow": 65536, "maxOutputTokens": 8192,
  "capabilities": ["chat","tools","json_mode"] }
// 响应 data：{ "id": 401, "status": "ENABLED" }
```

### 5.4 绑定（随应用配置提交）

`PUT /admin/apps/{code}/config`（节选）
```json
{ "modelBinding": { "modelEntryId": 401,
    "params": {"temperature": 0, "maxTokens": 4096} },
  "nodeOverrides": { "n_llm": { "modelEntryId": 402 } } }
```

**错误码**：`SYS-MODEL-4001 绑定解析失败` / `4002 模型能力不足` / `429 配额与限流` / `401 凭据无效` / `503 熔断中` / `504 超时`。

---

## 6. 规则视图

### 6.1 绑定解析优先级表

| 层级 | 来源 | 备注 |
|---|---|---|
| 1（最高） | 节点级绑定 | LLM 节点单独指定模型 |
| 2 | 应用级绑定 | 应用默认模型 |
| 3（兜底） | 平台默认模型 | 系统配置 |

### 6.2 计量三档规则表

| 档 | 时机 | 计量方式 | 用途 | 精度要求 |
|---|---|---|---|---|
| 估算 | 组装分账时 | jtokkit（OpenAI 系）/ 字符密度×系数 | 分账展示、容量规划 | 低（余量兜底） |
| 准入 | 发送前 | 模型适配计量（插件 tokenizer 或 `/tokenize`）+ 保守余量 1.3 | 判定能否发送 | 中 |
| 对账 | 响应后 | 供应商 usage 回包 | model_call_log、配额扣减、系数校准 | 精确 |

### 6.3 供应商错误处理表

| 错误 | 归一码 | 处理 |
|---|---|---|
| 429 / TPM 超限 | SYS-MODEL-429 | 排队或退避重试一次，仍满→回灌"系统繁忙" |
| 401/403 | SYS-MODEL-401 | 凭据问题，告警；不重试；回灌系统级错误说明 |
| 5xx / 超时 | SYS-MODEL-504 | 只读语义重试一次；写语义走 UNKNOWN 对账 |
| 上下文超限 | SYS-MODEL-413 | 组装器准入档失效核查（不应发生，兜底） |

### 6.4 出域路由判定表（v3.7 改标签匹配，废止 LOCAL/CLOUD 二值）

**模型**：数据的**必需标签集** D（如 {LOCAL, tenant:T1, secret≤3}）与端点的**信任标签集** E 匹配——`D ⊆ E` 才可路由；**数据带必需标签而候选端点标签不满足 → 不路由且不默认放行**（拒绝 `SYS-EGRESS-4031` + 告警）。

| 场景 | 判定 |
|---|---|
| 数据无必需标签 | 按应用绑定端点 |
| 数据要求 {LOCAL}，端点具备 LOCAL | 可路由本地端点 |
| 数据要求 {LOCAL}，候选端点不具备 LOCAL | 拒绝 `SYS-EGRESS-4031` + 告警（绝不路由云端） |
| 数据要求 {tenant:T1}，端点具备 {tenant:T1} | 可路由（租户专属端点） |
| 切换模型/端点后 | **重新校验**：能力/上下文窗口/参数/标签匹配，任一不满足按无可用端点处理 |

**快照补充**：实际路由的**模型与端点**随最终请求一并纳入 attempt 持久快照（可重建不变量的一部分）。

### 6.5 背压参数表

| 参数 | 默认 | 说明 |
|---|---|---|
| provider.tpm / rpm | 按供应商合同 | 令牌桶容量与速率 |
| app.quota.tokensPerDay | 100 万/应用/天 | 应用配额 |
| user.concurrency | 3 | 用户级并发运行上限 |
| overflow.policy | FAST_REJECT（交互）/ QUEUE（批量） | 满载策略 |

---

## 7. 与其他册的分工边界

组装分账与超限三分支 → 册 31 §6.1/组装器；出域标记的产生（数据密级）→ 数据源/册 20 return_spec 传递，本册只消费标记做路由；供应商管理面若采用 One-API → 本册 GatewayAdmin 简化为"引用+同步"。

## 8. 验收用例（本册）

1. 两供应商（云端+本地）并存，应用切换绑定无需重启，计量连续；
2. 凭据错误 → 401 归一码 + 告警 + 界面提示，凭据内容不出现在任何日志/错误信息；
3. TPM 令牌桶耗尽 → 交互流快速拒绝带 retryAfter，批量流排队且最终完成；
4. LOCAL_ONLY 数据 + 应用绑定云端 → 强制路由本地端点；无本地端点 → 拒绝并告警；
5. 供应商熔断：连续失败进入 DEGRADED，恢复后半开自动闭合；
6. usage 对账：100 次调用的估算/实际偏差报告生成，系数自动修正；
7. capabilities 检查：不支持 tools 的模型绑定到带工具智能体 → 发布校验拦截。

---

## 8. 复用与借鉴出处（对照《物料清单》O1/O3/O7）

| 组件 | 复用方式 | 开源出处 | 使用/借鉴内容 |
|---|---|---|---|
| LLMClient | 直接依赖 | Spring AI 1.0.x（ChatClient/流式/消息模型） | 单次对话能力；**关闭框架自动工具执行**（循环自研，守卫/版本/trace 包在外层） |
| 供应商管理面 | 配置部署（可选） | One-API / LiteLLM | Key 托管、配额、统一 OpenAI 兼容端点 |
| OpenAI 系精确计数 | 直接依赖 | jtokkit | cl100k/o200k 编码 |
| 非 OpenAI 准入计数 | 接口调用 | vLLM `/tokenize` 端点 | 服务端精确计数（准入低频可接受） |
| 令牌桶/限流 | 机制复用 | Redis Lua 脚本 + Resilience4j RateLimiter | 分布式 TPM/RPM 双桶 |
| 熔断/半开恢复 | 直接依赖 | Resilience4j CircuitBreaker | 按供应商熔断 |
| 计量与对账设计 | 读码借鉴 | dsh `llm/token-meter`（固定密度估价+usage 对账思想） | 估价仅展示档的思想出处 |
| 绑定解析/出域路由/错误归一 | 纯自研 | — | 业务语义（信任域/声明上限/归一映射表） |


---

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

# 详细设计 40 —— 管理与发布（技术要求 1.3①~⑤ + 1.2④）

> 上位：《智能体构建平台设计-自研路线》v3.6；《模块1详细设计》索引
> 本文六视图：功能视图（§1）→ 组成视图（§2）→ 流程视图（§3 六条时序）→ 数据视图（§4 全字段 DDL）→ 接口视图（§5 报文级）→ 规则视图（§6 生命周期/门禁/集成/回放规则表）→ 验收（§8）
> 技术底座：Spring Boot 3.x / PostgreSQL / Redis / gateway（ApiKnife 认证）/ Web Component（嵌入套件）
> 核心原则：**发布版本不可变**——发布=校验→快照→摘要→出四件套；在途运行钉版本，发布与迭代互不影响

---

## 1. 功能视图：功能清单与功能设计

管理与发布共 8 个功能：**管理组**（F1~F2、F4~F6、F8）与 **发布集成组**（F3、F7）。

| 编号 | 功能 | 一句话职责 | 使用者 |
|---|---|---|---|
| F1 | 应用工作台 | 列表/多维检索/分类标签/新建向导 | 编排者 |
| F2 | 生命周期管理 | 创建/复制/发布/停用/归档与变更留痕 | 编排者 |
| F3 | 发布管理 | 发布检查器→版本固化→回滚与版本对照 | 编排者 |
| F4 | 运行监控 | 实时任务状态/当前节点/耗时/异常 | 编排者/运维 |
| F5 | 执行记录与回放 | 多维检索+回放三级（回放/重执行/受控复现） | 编排者/运维 |
| F6 | 确认中心 | WAITING_APPROVAL 待办的确认/拒绝/超时处理 | 确认人 |
| F7 | 集成管理 | 集成四件套/密钥/域白名单/主题/版本钉定 | 管理员+外部系统 |
| F8 | 统计报表 | 调用量/成功率/耗时/token/按应用与业务系统维度 | 管理员 |

### 1.1 F1 应用工作台

**功能描述**：编排者的应用目录。多维检索（名称/编码/专业领域/业务分类/标签/状态），列表展示版本号、更新时间、发布状态；新建向导以**三问卡片**引导选类型（对话型/流程型，见主设计 §2 选型三问），选型即确定后续配置表单的形状。支持复制应用（复制草稿并重置版本）。**列表/详情提供正式运行入口（1.3① 的"运行"）**：对话型应用 → 跳转对应 WebApp；流程型应用 → 打开运行输入表单（选择允许执行的发布版本、填参、提交），提交后进入任务详情；未发布/已停用应用运行入口禁用并说明原因；提交按钮防重复点击（幂等请求号）。
**输入**：检索条件 / 运行表单。**输出**：应用列表（分页）/ 正式运行记录。
**规则**：检索按租户过滤；归档应用默认不出现在列表但可显式检索；未发布应用不可正式运行（调试走调试入口，不混淆）。

### 1.2 F2 生命周期管理

**功能描述**：应用状态的受控迁移与全程留痕。状态机：`DRAFT → PUBLISHED → DISABLED → ARCHIVED`；**修改已发布应用 = 基于该版本复制新草稿**，发布产生新的不可变版本——在途运行钉旧版本不受影响。所有迁移写 `app_lifecycle_log`（谁/何时/从什么状态到什么状态/发布摘要）。
**输入**：编排者的生命周期操作。**输出**：状态迁移 + 留痕记录。
**规则**：DISABLED 后对外 API 返回明确"应用已停用"；ARCHIVED 不进默认列表；删除仅允许 DRAFT（已发布应用不可删除，只能停用+归档）。

### 1.3 F3 发布管理

**功能描述**：把草稿固化为不可变发布版本的完整流程：**发布检查器**（六组检查：结构/参数/类型/引用/守卫声明/评测门禁，任一 ERROR 阻断）→ 通过后写 `app_version`（version_no + SHA-256 摘要 + 解释器兼容版本 + 依赖版本清单）→ 集成四件套自动更新到新版本。支持**回滚**：把对外流量指回历史版本（新指针，不修改历史）；**版本对照**：两个版本的配置 diff。
**输入**：草稿。**输出**：发布版本 + 发布报告（检查结论/摘要/四件套地址）。
**规则**：发布采用**两阶段**（precheck 预检 → commit 正式提交，绑定草稿修订号+候选摘要+幂等请求号）；**同一幂等请求号重复提交返回同一版本（不产生新版本号）**；预检后草稿再变更 → 预检失效须重检；发布不停服（在途运行钉版本）。

### 1.4 F4 运行监控

**功能描述**：运行状态的实时与历史视图。实时：任务列表（状态/当前节点/耗时/异常）+ 详情页节点级状态图 + SSE 实时刷新；聚合指标推 Prometheus 供 Grafana（并发运行数/成功率/耗时分布/token 消耗）。支持按 run 取消。
**输入**：run/node_instance 表 + 事件流。**输出**：监控视图 + 操控（取消）。
**规则**：等待态细分展示（WAITING_STEP/WAITING_APPROVAL/WAITING_EXTERNAL）；数据按租户与角色过滤。

### 1.5 F5 执行记录与回放

**功能描述**：历史运行的检索与三级回放。检索维度：智能体/任务 ID/时间/状态/principal；详情含节点输入输出摘要（原文按需取，走资源归属检查）与**版本链关联**（智能体版本/模型配置/技能版本/**本体实际调用版本集合**——run.version_refs.ontology，含空间/发布标识/版本/摘要，未调用显示"不适用"；同一 run 调用多个本体空间时按调用级逐条列出）。三级回放：**日志回放**（读快照展示）/ **重新执行**（旧配置调当前服务，显式确认后执行）/ **受控复现**（用保存的响应不重跑）。评测运行单独过滤入口。
**输入**：检索条件。**输出**：记录列表 + 回放视图。
**规则**：回放内容走资源归属检查（principal+tenant）；三级回放的边界在界面明确标注（回放≠重新执行≠保证相同答案）。

### 1.6 F6 确认中心

**功能描述**：WAITING_APPROVAL 待办的集中处理页。待办卡片展示：智能体/节点、**确认参数快照**、发起人与时间、有效期倒计时；操作：确认 / 拒绝（填理由）/ 转交。**确认后若引擎检测到参数已变更 → 自动重新挂起并提示重新确认**。超时按节点配置处理（终止/默认分支）。确认事实写入审计。
**输入**：待办 + 决定。**输出**：状态迁移 + 审计。
**规则**：确认人按节点声明的角色/租户解析；同一待办并发处理以先到为准（幂等）。

### 1.7 F7 集成管理

**功能描述**：管理"别的业务软件如何接入发布的智能体"。发布产出**四件套**：WebApp 链接 / 嵌入套件（assistant.js+自动生成的接入代码片段）/ Service API 文档 / 集成密钥。本功能管理：集成密钥签发与吊销（每个业务系统一把，含**可声明的用户与属性范围上限**）、域白名单（CORS）、每应用主题配置（外观我们配对方不碰）、版本钉定（对方可显式钉某版本用于回归）。
**输入**：接入申请与管理配置。**输出**：密钥 + 接入片段 + 白名单生效。
**规则**：密钥只显示一次（签发时）；吊销立即生效（网关实时校验）；出票 JWT 验签含受众/范围/出票方声明上限。

### 1.8 F8 统计报表

**功能描述**：平台运营数据：按应用/技能/业务系统/时间的调用量、成功率、耗时分布、token 消耗、确认量；调试与评测量单列。数据源：model_call_log、stat_invocation、run 表的聚合（日报预聚合 + 明细下钻）。
**输入**：运行与调用数据。**输出**：报表与下钻。

---

## 2. 组成视图：模块组成与实现技术

### 2.1 组件组成图

```
┌──────────── 前端（web/admin + web/webapp + assistant.js）────────┐
│ 应用工作台 │ 画布入口 │ 发布向导 │ 运行监控 │ 确认中心 │ 集成管理 │
│ WebApp 对话页 │ 嵌入式助手组件(assistant.js)                      │
└──────────────────────────┬───────────────────────────────────────┘
┌──────────────────────────▼──── app-management ──────────────────┐
│ AppController │ AppLifecycleService │ PublishService(检查器+固化) │
│ IntegrationService(密钥/出票/片段/白名单) │ StatsService            │
├──────────────────────────── monitor ────────────────────────────┤
│ RunMonitorController │ ReplayService │ ApprovalCenterService      │
│ StatsAggregator(日报预聚合)                                       │
├──────────────────────────── gateway ────────────────────────────┤
│ ServiceApiKey 认证 │ 出票 JWT 验签 │ CORS/域白名单 │ 限流           │
└──────────────────────────────────────────────────────────────────┘
        ↓ 调用
engine-runtime（执行） │ model-gateway │ skill-registry
```

### 2.2 组件职责与实现技术

| 组件 | 实现技术 | 职责 |
|---|---|---|
| AppLifecycleService | @Service + 状态机类 + app_lifecycle_log | 生命周期迁移与留痕 |
| PublishService | @Service + 检查器责任链（六组）+ SHA-256 | 发布固化与发布报告 |
| IntegrationService | @Service + JWT（HMAC 签发/验签） | 集成密钥/出票/白名单 |
| ReplayService | @Service + 对象存储读 + 权限校验 | 三级回放 |
| ApprovalCenterService | @Service + 超时器 | 确认待办与幂等处理 |
| StatsAggregator | @Scheduled 预聚合 + ECharts 前端 | 报表 |
| WebApp / assistant.js | Vue3 组件 + SSE 客户端 | 对话界面与嵌入组件 |
| 网关认证 | Spring Security + ApiKey/JWT 过滤器 | 两类调用方认证（门户用户/外部系统） |

**依赖纪律**：PublishService 依赖评测模块的门禁结论（6.9）但通过接口解耦；IntegrationService 不直接触达引擎（一切执行经 gateway→engine）。

---

## 3. 流程视图：核心流程时序（含异常分支）

### 3.1 应用创建到发布全流程

```text
编排者 → 工作台新建（三问卡片选类型）→ 基本信息 → [对话型:配置表单 | 流程型:画布]
  → 绑定技能/模板/策略 → 调试（册32）→ 点击发布
  → **阶段一 precheck**：PublishService.runChecks()：六组检查
      ├─ 有 ERROR → 返回分组错误，停留在草稿
      └─ 全过 → 返回预检凭证 {precheckId, draftRev, candidateSha256, evalReportId}
  → **阶段二 commit**（编排者确认）：复核草稿修订号与候选摘要未变（变更 → 412 重预检）
      → 固化 app_version(版本号+SHA-256+解释器/依赖版本，幂等请求号防重) → 四件套更新
  → 集成管理页出现接入片段 → 外部系统可调用 / WebApp 可访问
```

### 3.2 版本迭代流程（修改→新草稿→再发布；在途不受影响）

```
编排者打开已发布应用 → "修改" → 复制为 v(n+1) 草稿 → 编辑 → 调试 → 发布
  → app_version 新增 v(n+1)，current_version 指针前移
  在途 run（钉 v(n)）继续跑完；新发起 run 用 v(n+1)
  回滚：把 current 指针与对外路由指回 v(n)（写版本切换记录，历史版本永不删除）
```

### 3.3 外部系统集成流程（F7 全流程）

```
业务系统申请接入 → 管理员：签发集成密钥（一次显示）+ 配置域白名单 + 声明范围上限
  → 对方前端嵌 assistant.js（endpoint/appId/token 由对方后端出票接口提供）
      出票：对方后端用集成密钥签 JWT{user_id,姓名,属性,...}（≤5min 有效）
  → 用户在业务系统内点开弹出助手 → 网关验签（签名/受众/范围/上限裁剪）
  → 每次对话经守卫链（按票据属性）→ 统计按业务系统维度记账
吊销：管理员吊销密钥 → 该系统所有票据验签失败 → 接入立即中断（审计留痕）
```

### 3.4 发布检查器时序（**两阶段**：预检与正式提交分离，v3.7 修正）

```
阶段一（预检，不落版本）：POST /admin/apps/{id}/publish/precheck
  → 检查组1 结构（连通/START-END/无环）         ✗→ERROR 返回
  → 检查组2 参数（必填映射）                    ✗→ERROR
  → 检查组3 类型与无效引用                      ✗→ERROR
  → 检查组4 引用有效性（模板/技能/凭据/供应商）   ✗→ERROR
  → 检查组5 守卫声明（查询技能守卫属性）          ✗→ERROR
  → 检查组6 评测门禁（安全 100% + 通过率 ≥ 阈值 + 无新增回归，M4）
  → 全过 → 返回预检报告 {precheckId, draftRev, candidateSha256, evalReportId}
WARN 级问题不阻断但列入报告。

阶段二（正式提交，绑定预检结论）：POST /admin/apps/{id}/publish/commit
  请求携带 {draftRev, candidateSha256, precheckId, idempotencyRequestNo}
  → **commit 原子边界（短事务，评审 V03）**：
     ①对 app_draft 行加锁并复核：draft_rev=请求值 && candidate_sha256=预检摘要 && 预检未过期
       （不符 → 412 重预检；并发改稿由行锁串行化，不发布未检查内容）
     ②同事务内完成：INSERT app_version + UPDATE current_version 指针
       + 写 skill_reference + INSERT publish_commit 幂等记录
     → 同 idempotencyRequestNo 重复提交 → 返回同一版本号（**不产生新版本号**）
     → 并发 commit 同草稿（**含不同请求号**）：行锁串行化；后到者按
        (app_id, draft_rev, candidate_sha256) 查找已发布版本——存在则**关联新请求号并
        返回同一版本号**（版本号=内容身份，不为同一候选重复发号）；不存在才按本请求创建
     → 服务重启后：幂等结果由 publish_commit 表恢复（不重发、不重复建版本）
  → 返回发布报告（摘要/版本号/四件套地址）
WARN 级问题不阻断但列入报告。
```

### 3.5 停用与归档流程

```
停用：确认无影响或接受影响 → DISABLED → 对外 API 返回"应用已停用"；在途运行可配置（跑完/立即取消，默认跑完）
归档：DISABLED 前提下 → ARCHIVED → 默认列表不可见，可显式检索恢复
删除：仅 DRAFT 允许；已发布应用删除入口不存在（防误删历史）
```

### 3.6 回放三级流程

```
日志回放：读 run 快照/事件 → 展示（零外部调用）
重新执行：显式确认 → 复制 run 配置 → 按当前服务重跑 → 新 run 关联原 run（对比视图）
受控复现：用保存的模型/技能响应回放决策过程（不调外部）——用于培训与分析
```

---

## 4. 数据视图：全字段 DDL

### 4.1 app

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| code | VARCHAR(64) | UK(tenant_id, code) | 业务编码，对外标识 |
| name | VARCHAR(128) | NOT NULL | 展示名 |
| type | VARCHAR(16) | NOT NULL | CHAT / FLOW（创建时定） |
| domain | VARCHAR(64) | 字典：专业领域 | 专业领域维度（独立于业务分类） |
| biz_category | VARCHAR(64) | 字典：业务分类 | 业务分类维度（二者可分别维护与筛选） |
| tags | TEXT[] | | |
| status | VARCHAR(16) | NOT NULL | DRAFT / PUBLISHED / DISABLED / ARCHIVED |
| current_version_id | BIGINT | FK→app_version | 当前对外版本指针 |
| 三问答案 | JSONB | | 向导三问的选择（沉淀数据） |

### 4.2 app_draft / app_version

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| app_id | BIGINT | FK | |
| draft_no / version_no | INT | UK(app_id, no) | 递增 |
| draft_rev | INT | NOT NULL DEFAULT 1 | **草稿业务修订号**：业务定义/配置/图/绑定的任何变更+1（乐观锁预期值，冲突 409）；纯画布布局变更不递增；precheck/commit 绑定该值 |
| config | JSONB | NOT NULL | 模型绑定/技能清单/推理策略/确认策略 |
| prompt_refs | JSONB | | 模板版本引用 |
| skill_refs | JSONB | | 技能版本引用（同时写 skill_reference） |
| flow_definition_id | BIGINT NULL | | 流程型才有 |
| integration JSONB | | | 主题/白名单/版本钉定标记 |
| sha256 | CHAR(64) | | 发布摘要（草稿为 NULL） |
| interpreter_version | VARCHAR(32) | | 解释器兼容版本（发布快照） |
| dependency_versions | JSONB | | 依赖版本清单 |
| published_at / published_by | | | |

索引：`UK(app_id, version_no)`、`IDX(status)`。

### 4.3 app_lifecycle_log

```sql
app_lifecycle_log(id, app_id, actor, action PUBLISH|DISABLE|ARCHIVE|COPY|MODIFY,
                  from_state, to_state, version_no NULL, detail JSONB, created_at)
```

### 4.4 integration_credential

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| biz_system_code | VARCHAR(64) | UK | 业务系统编码 |
| secret_ref | VARCHAR(256) | NOT NULL | HMAC 密钥引用（只写） |
| domains | TEXT[] | | 允许嵌入的域白名单 |
| claim_limits | JSONB | NOT NULL | 可声明的用户与属性范围上限 |
| status | VARCHAR(16) | | ACTIVE / REVOKED |
| issued_at / revoked_at | | | |

### 4.5 publish_report

```sql
publish_report(id, app_draft_id, draft_rev INT,      -- 预检所属草稿与修订号（评审 R06）
               candidate_sha256 CHAR(64),            -- 候选内容摘要
               eval_report_id VARCHAR(64) NULL,      -- 评测报告引用（M4）
               precheck_id VARCHAR(64), expires_at,  -- 预检凭证与有效期
               checks JSONB,      -- 六组检查结论
               warnings JSONB, result PASS|BLOCKED, created_at)
publish_commit(id, app_id, idempotency_request_no UK, precheck_id FK,
               app_version_id, created_at)           -- 幂等提交记录：重复提交返回同一版本
```

---

## 5. 接口视图（报文级）

### 5.1 发布（两阶段接口）

`POST /admin/apps/{code}/publish/precheck`（阶段一：只检查不落版本）
```json
// 响应（通过）
{ "code": "OK", "data": {
    "precheckId": "pc/501", "draftRev": 7, "candidateSha256": "9f2a…",
    "evalReportId": "er/88",
    "report": { "checks": [
      {"group":"结构","result":"PASS"},
      {"group":"评测门禁","result":"PASS","detail":"安全 24/24，能力 91.2%（阈值 85%）"} ],
      "warnings": [ {"group":"引用","reason":"技能 query_logs 存在 v4 可升级"} ] } } }
// 响应（阻断）
{ "code": "BIZ-APP-3001 发布检查未通过",
  "data": { "errors": [ {"group":"类型","nodeId":"n_map","reason":"数组映射到单值参数"} ],
            "reportRef": "pr/501" } }
```

`POST /admin/apps/{code}/publish/commit`（阶段二：绑定预检结论正式提交）
```json
// 请求
{ "precheckId": "pc/501", "draftRev": 7, "candidateSha256": "9f2a…",
  "idempotencyRequestNo": "uuid" }
// 响应（成功；同 idempotencyRequestNo 重复提交返回同一 versionNo，不产生新版本）
{ "code": "OK", "data": { "versionNo": 3, "sha256": "9f2a…",
    "artifacts": { "webAppUrl":"…", "embedSnippet":"<script …>", "apiDocUrl":"…" } } }
// 草稿已变更 → 412 { "code": "BIZ-APP-3002 草稿已变更，请重新预检" }
```

### 5.2 对外执行（Service API，ApiKey 认证）

`POST /v1/apps/ops-log/runs`
```json
// 请求
{ "inputs": { "query": "昨天A区的错误日志有多少" }, "mode": "SYNC" }
// 响应（SYNC）
{ "code": "OK", "data": { "runId": "r_88213", "status": "SUCCEEDED",
  "answer": "昨天 A 区错误日志共 312 条…", "artifacts": [] } }
// 异步：mode=ASYNC → data:{runId}，用 GET /v1/runs/{runId} 轮询或订阅事件流
```

### 5.3 集成管理

| 端点 | 报文要点 |
|---|---|
| `POST /admin/apps/{code}/integration/credentials` | 签发：`data:{bizSystemCode, secret(仅此一次显示), claimLimits}` |
| `PUT .../credentials/{bizCode}` | 更新白名单/声明上限/吊销 |
| `GET .../integration/snippet` | `data:{scriptTag, componentTag, themeConfig}`（按应用生成复制即用） |
| `POST /embed/ticket` | 出票：`{ticket:"eyJhbGci…", expiresIn:300}`（对方后端调用） |

### 5.4 确认中心

| 端点 | 报文要点 |
|---|---|
| `GET /admin/approvals?assignee=&status=WAITING` | 待办列表（参数快照/剩余时间） |
| `POST /admin/approvals/{id}/decision` | `{decision:APPROVE|REJECT, reason}`；参数变更 → `data:{reconfirmRequired:true}` |

---

## 6. 规则视图

### 6.1 生命周期状态机转移表

| 当前态 | 事件 | 次态 | 条件/动作 |
|---|---|---|---|
| （无） | 创建向导完成 | DRAFT | 选类型 |
| DRAFT | 发布检查全过 | PUBLISHED | 固化版本+四件套 |
| PUBLISHED | 修改 | 生成新 DRAFT | 已发布版本保持不变 |
| DRAFT | 再发布 | 新 PUBLISHED 版本 | 门禁重过 |
| PUBLISHED | 停用 | DISABLED | 在途按配置跑完；API 拒新 |
| DISABLED | 重新启用 | PUBLISHED | 指回原版本 |
| DISABLED | 归档 | ARCHIVED | 默认列表不可见 |
| 任意 | 复制 | 新应用 DRAFT | 配置复制，版本重置 |

### 6.2 发布门禁表

| 检查组 | 级别 | 阻断条件 |
|---|---|---|
| 结构/参数/类型/引用/守卫声明 | ERROR | 任一失败阻断 |
| 评测-安全用例 | ERROR | **任一失败阻断（零容忍）** |
| 评测-能力通过率 | ERROR | < 阈值（默认 85%）阻断 |
| 评测-新增回归 | ERROR | 相对基线新增失败阻断 |
| 说明书/引用可升级等 | WARN | 列入发布报告不阻断 |

### 6.3 集成密钥规则表

| 规则 | 内容 |
|---|---|
| 签发 | 一次显示；HMAC 密钥存密文引用 |
| 出票 JWT | 有效期 ≤5 分钟；字段：user_id/姓名/属性（受 claim_limits 上限裁剪）/业务系统 ID |
| 验签 | 网关逐次验签；越界属性丢弃并告警 |
| 吊销 | **立即生效（秒级）**：网关验签实时校验密钥状态（Redis 缓存+吊销即失效，jti/密钥版本进黑名单），不依赖"票据自然过期"；**已建立的 SSE 与在途调用**：完成当前响应后关闭，不再接受新调用 |
| 统计 | 按业务系统维度记账 |

### 6.4 回放三级规则表

| 级别 | 机制 | 承诺 |
|---|---|---|
| 日志回放 | 读快照与事件 | 展示当时发生的一切（默认，零成本） |
| 重新执行 | 旧配置+当前服务重跑 | 显式确认；不承诺相同答案 |
| 受控复现 | 使用保存的模型/技能响应 | 不调外部；用于培训与分析 |

### 6.5 版本钉定规则

外部系统 API 请求可携带 `versionNo` 显式钉定（用于回归验证）；默认使用 `current_version`；钉定版本被停用时返回明确错误。

---

## 7. 与其他册的分工边界

引擎执行/挂起/事件 → 册 31；调试面板 → 册 32；守卫属性来源 → 册 10/Keycloak；评测跑批实现 → 册 31 执行 + 6.9 判分；本体上下文包 → 模块 2（预留接口）。

---

## 8. 验收用例（本册）

1. 创建→编排→调试→发布→外部调用→记录回放全链路走通（M3 主验收）；
2. 发布检查六组各构造反例均被分组拦截并可定位；
3. 评测门禁：构造安全用例失败 → 发布被阻断，报告显示零容忍条目；
4. 版本迭代：v1 在途运行继续，v2 发布后新流量走 v2，回滚指回 v1 立即生效；
5. 修改已发布应用 → 自动生成新草稿，v(n) 内容与摘要不变；
6. 集成：业务系统嵌 assistant.js + 出票 → 弹出助手带用户身份跑通守卫；吊销密钥 → 接入立即中断；
7. 停用后对外 API 返回"已停用"，在途运行按默认策略跑完；
8. 确认中心：并发确认幂等，参数变更触发重确认；
9. 回放三级各自演示且边界声明在界面可见（回放≠重新执行≠保证相同答案）。

---

## 9. 复用与借鉴出处（对照《物料清单》O11/O12/O13/O15）

| 组件 | 复用方式 | 开源出处 | 使用/借鉴内容 |
|---|---|---|---|
| 门户组件/布局 | 直接依赖 | Element Plus | 表单/表格/布局/反馈 |
| 报表图表 | 直接依赖 | ECharts 5 | 统计可视化 |
| CRUD 页加速 | 可选直接依赖 | amis | 系统管理等简单页 |
| 出票/验签 | 自研+库 | nimbus-jose-jwt（JWT/HMAC 签名） | 签发与验签；声明上限裁剪自研 |
| WebApp 对话页 | 自研（交互参考 Dify webapp 开源实现） | — | 流式/卡片/历史 |
| assistant.js | 纯自研 | — | Shadow DOM/悬浮球/富载荷卡片 |
| 发布检查器/回放三级/版本对照 | 纯自研 | — | 差异化核心 |


---

# 详细设计 60 —— 前端工程（admin / webapp / assistant / 画布）

> 上位：《智能体构建平台设计-自研路线》v3.6；《模块1详细设计》索引
> **功能组织原则：门户一级导航 = 技术要求四个子模块（1.1 模型推理配置 / 1.2 流程可视化编排 / 1.3 智能体管理 / 1.4 技能管理）+ 系统支撑组**——页面归属与需求条目一一对应，验收可直接对照
> 本文六视图（前端适配版）：功能视图=**按子模块组织的页面清单**（§4）→ 组成视图=**工程结构与组件架构**（§5）→ 流程视图=**六个关键前端流程**（§6）→ 数据视图=**前端状态与本地存储**（§7）→ 接口视图=**API 客户端与 SSE 事件契约**（§8）→ 规则视图（§9）

---

## 1. 技术栈决策（原待决事项，就此定案）

| 项 | 决策 | 理由 |
|---|---|---|
| 框架 | **Vue 3.5 + TypeScript + Vite** | 团队 Java 背景，Vue 在国内企业前端普及度最高；组合式 API+TS 类型安全 |
| 状态管理 | Pinia | Vue 3 官方推荐 |
| UI 组件库 | **Element Plus** | 表单/表格/布局覆盖管理门户 90% 场景；中文文档 |
| 画布 | **AntV X6 2.x + @antv/x6-vue-shape** | 流程画布成熟；节点用 Vue 组件渲染，与属性面板同技术栈 |
| 图表 | ECharts 5 | 统计报表 |
| SSE | **fetch 流式客户端**（fetch + ReadableStream 解析 SSE，**可携带 Authorization 头**，带 Last-Event-ID 重连） | 原生 EventSource 不支持自定义认证头（会 401），故弃用；配合后端补发协议 |
| API 客户端 | axios + **openapi-typescript 生成的类型化客户端**（从后端 /v3/api-docs 生成） | 前后端契约单点：后端改接口，前端类型报错 |
| 设计系统 | Element Plus 基座 + **平台设计 tokens**（色彩/间距/状态色 CSS 变量层） | 视觉稿可后补：tokens 一层可整体换肤，不影响组件结构 |

**monorepo**：pnpm workspace（四端共享公共包，见 §5）。

---

## 2. 应用拆解（四端）

| 应用 | 形态 | 使用者 | 内容 |
|---|---|---|---|
| **admin** | SPA（门户） | 编排者/技能管理员/管理员 | 四个子模块的全部页面 + 确认中心/评测/审计/系统管理 |
| **webapp** | SPA | 业务用户 | 对话页（按 appCode 加载对应智能体） |
| **assistant** | **单文件脚本** assistant.js（Web Component） | 业务系统页面嵌入 | 悬浮球+抽屉对话 |
| **flow-designer / debug-panel** | 模块包（被 admin 引用） | 编排者 | 画布与调试面板 |

**拆解原则**：画布、对话、调试三个高复杂度模块做成**独立包**；assistant 构建为**无依赖单文件**（依赖内联，Shadow DOM 样式隔离）。

---

## 3. 工程结构（代码目录，views 按子模块组织）

```
web/
├── pnpm-workspace.yaml
├── apps/
│   ├── admin/src/
│   │   ├── main.ts / App.vue
│   │   ├── router/index.ts               路由表=页面清单（§4）
│   │   ├── layouts/AdminLayout.vue       一级导航=四个子模块+系统组
│   │   ├── views/
│   │   │   ├── agent-management/         【子模块 1.3 智能体管理】
│   │   │   │   ├── AppListView / AppCreateWizard / AppOverview
│   │   │   │   ├── orchestration/        【子模块 1.2 的工作区，宿主在应用下】
│   │   │   │   │   ├── OrchestrationLayout / FlowCanvasPage(流程型)
│   │   │   │   │   ├── ChatConfigPage(对话型) / ErrorPolicyForm
│   │   │   │   │   ├── DebugPage / PublishWizard / IntegrationPage
│   │   │   │   ├── runs/                 AppRunList（本应用过滤视图）
│   │   │   │   └── records/              AppRunDetail/Replay
│   │   │   ├── model-config/             【子模块 1.1 模型推理配置】
│   │   │   │   ├── ProviderList / ModelEntryList / PromptTemplateList / PromptTemplateEdit
│   │   │   ├── skill-management/         【子模块 1.4 技能管理】
│   │   │   │   ├── SkillLibrary / SkillWizard(六步) / SkillDetail(版本/验收报告/统计)
│   │   │   ├── runs/                     【1.3④⑤ 全局视图】RunMonitorList / RunDetail / Replay
│   │   │   ├── approvals/                确认中心（支撑）
│   │   │   ├── evaluations/              评测（M4 支撑）
│   │   │   └── system/                   用户/角色/审计/系统配置（支撑）
│   │   ├── stores/                       Pinia（auth/appDraft/debug/chat/dict）
│   │   └── api/                          对 @api-client 的业务封装
│   ├── webapp/src/                       对话页（/w/:appCode）
│   └── assistant/                        嵌入组件源码（构建为单文件 IIFE）
├── packages/
│   ├── api-client/                       类型化 API 客户端（openapi-typescript 生成+axios 封装）
│   ├── shared-ui/                        StatusTag/DurationBar/ResultCard/TokenBadge/ConfirmCard
│   ├── chat-ui/                          对话组件（webapp 与 assistant 共用）
│   ├── flow-designer/                    画布模块 ★（canvas/nodes/transform/property-panel/collab）
│   ├── debug-panel/                      调试面板模块 ★
│   └── shared-utils/                     sse-client/jsonpath-lite/错误码映射
└── design-tokens/                        设计 tokens（CSS 变量换肤层）
```

★ = 复杂度最高的两个包，最先 PoC（X6 集成与流式分帧）。

---

## 4. 功能视图：按技术要求子模块组织的页面清单

### 4.0 组织规则

1. **一级导航 = 四个子模块 + 系统支撑组**，与《技术要求》1.1~1.4 逐条对照，验收直接可用；
2. **两条归属规则**：①1.2 的编排/调试/发布是"应用上下文工作区"——从 1.3 智能体管理进入（1.2 的界面宿主在应用下，不设独立一级菜单）；②全局视图（运行监控/执行记录/确认中心）归 1.3④⑤；
3. 每个页面标注需求条目；**支撑组页面**（确认中心/评测/审计/系统管理）不属于 17 项，标注"支撑"。

### 4.1 菜单一：智能体管理（子模块 1.3）

| 页面 | 路由 | 需求 | 核心交互 |
|---|---|---|---|
| 应用列表 | `/apps` | 1.3①② | 六维筛选（关键词/编码/专业领域/业务分类/标签/状态）+分页+状态标签；新建向导（三问卡片选类型）；复制；专业领域与业务分类为独立字典维度，分别维护与筛选 |
| 应用概览 | `/apps/:code` | 1.3③ | 生命周期操作（发布/停用/归档）+版本列表+变更记录；页签入口：编排/调试/发布集成/运行；**含正式运行入口**（1.3①，见下） |
| **正式运行页** | `/apps/:code/run` | 1.3① | 选发布版本（默认最新可钉定）→ 按 inputs Schema 渲染输入表单 → 提交（幂等请求号防重复点击）→ 进任务详情；未发布/停用禁用并说明 |
| 运行监控 | `/apps/:code/runs` | 1.3④ | 本应用运行列表+实时状态+SSE 刷新+取消 |
| 执行记录 | `/apps/:code/records/:runId` | 1.3⑤ | 节点摘要+版本链+回放三级切换；详情展示本体实际调用版本集合（空间/发布标识/版本/摘要，未调用显示"不适用"） |

### 4.2 菜单二：流程可视化编排（子模块 1.2，应用上下文工作区）

| 页面 | 路由 | 需求 | 核心交互 |
|---|---|---|---|
| 编排工作区（流程型） | `/apps/:code/orchestration/flow` | 1.2① | X6 画布+节点面板+属性面板+保存校验 |
| 编排工作区（对话型） | `/apps/:code/orchestration/chat` | 1.1②③④+1.2① | 配置表单：模型绑定/模板/技能清单/策略 |
| 调试预览 | `/apps/:code/orchestration/debug` | 1.2② | 单步/全量/Mock/prompt 全文（详见册 32） |
| 异常策略 | 节点属性面板内 | 1.2③ | 超时/重试/终止配置（详见 §6.3 错误处理表） |
| 发布向导 | `/apps/:code/orchestration/publish` | 1.2④ | 六组检查报告（五组基础校验+评测门禁）→确认发布→四件套 |

### 4.3 菜单三：模型推理配置（子模块 1.1）

| 页面 | 路由 | 需求 | 核心交互 |
|---|---|---|---|
| 供应商与凭据 | `/model/providers` | 1.1① | 供应商 CRUD+连接测试+凭据轮换 |
| 模型条目 | `/model/providers/:id/entries` | 1.1① | 模型清单/窗口/能力标记/健康状态 |
| 提示词模板 | `/model/prompt-templates` | 1.1② | 模板 CRUD+版本+变量槽+试渲染 |

（1.1③ 工具调用编排、1.1④ 推理策略的配置界面位于 4.2 编排工作区内，不放独立菜单。）

### 4.4 菜单四：技能管理（子模块 1.4）

| 页面 | 路由 | 需求 | 核心交互 |
|---|---|---|---|
| 技能库 | `/skills` | 1.4② | 检索+引用计数+停用拦截 |
| 注册向导 | `/skills/new` | 1.4① | 六步表单（含 OpenAPI 导入/守卫声明/选型验收门禁） |
| 技能详情 | `/skills/:code` | 1.4③④ | 版本列表+引用关系+调用统计 |

### 4.5 菜单五：运行与确认（1.3④⑤ 全局视图 + 支撑）

| 页面 | 路由 | 需求 | 核心交互 |
|---|---|---|---|
| 全局运行监控 | `/runs` | 1.3④ | 全平台运行列表（按应用/状态/时间过滤） |
| 全局执行记录 | `/runs/:runId` | 1.3⑤ | 回放三级+版本链+守卫记录 |
| 确认中心 | `/approvals` | 支撑（6.6） | WAITING_APPROVAL 待办：确认/拒绝/参数快照 |

### 4.6 支撑组（不属于 17 项）

| 页面 | 路由 | 说明 |
|---|---|---|
| 评测中心（M4） | `/evaluations` | 用例库/跑批/报告/门禁配置（主设计 6.9） |
| 审计 | `/audit` | 处理审计/守卫记录/出域记录 |
| 系统管理 | `/system/*` | 用户/角色/租户/系统配置 |

---

## 5. 组成视图：工程结构与组件架构

### 5.1 画布模块（flow-designer 包）内部结构

```
flow-designer/
├── canvas/          X6 封装：Graph 初始化/连接桩规则/快捷键/序列化
├── nodes/           节点注册表：[{type, vueShape, propFormSchema, 默认配置}]
│                    （LLM/SKILL/CONDITION/CODE/RETRIEVE/CONFIRM/START/END）
├── transform/       契约转换器：X6数据 ⇄ 业务定义JSON（双向，含前端预校验）
├── property-panel/  属性面板：按节点 propFormSchema 渲染表单（Schema 驱动）
└── collab/          与后端校验/调试事件的联动（错误定位/节点变色）
```

- **契约单点**：节点类型清单与表单 Schema 由 `GET /admin/node-types` 下发——前端不改代码即可支持新节点类型（模块 2 扩展点的前端配套）。
- **三数据分离落地**：转换器输出 `{definition, canvasLayout}` 两份，分别提交；加载时合并渲染。

### 5.2 调试面板（debug-panel 包）内部结构

`单步控制条（执行本节点/跑到断点/终止）` + `节点检查器（prompt 全文查看器/变量树/输出对比/Mock 表单）` + `画布联动（SSE 事件→节点状态色：灰待/蓝跑/绿成/红败/黄挂起）`。

### 5.3 chat-ui（对话组件，webapp 与 assistant 复用）

消息流（用户/助手/工具卡片/确认卡片）→ 流式渲染（SSE chunk 增量追加+Markdown 渲染+代码高亮）→ 输入框（多行/发送/停止）→ 历史会话切换。props：`endpoint/appCode/ticket/context/theme`；events：`onConfirm/onArtifactClick`。

---

## 6. 流程视图：六个关键前端流程

### 6.1 登录与路由守卫

登录 → authStore 存 accessToken（内存）+ refreshToken（httpOnly cookie 由后端种）→ 路由守卫：未登录跳 `/login`；无页面权限跳 403；菜单按权限渲染。

### 6.2 画布编辑与保存

打开草稿 → `GET /admin/flows/{id}` → 转换器合并渲染（definition→X6 节点，layout→坐标）→ 编辑 → 保存 → 转换器拆分 → 前端契约 Schema 预校验（快速反馈）→ `PUT /admin/flows/{id}` → 后端五组校验 → ERROR 列表点击定位节点（X6 高亮+属性面板打开）。

### 6.3 调试面板联动

创建调试运行 → 订阅 SSE → 事件驱动：`NODE_STARTED` 节点变蓝 / `NODE_FINISHED` 变绿+耗时徽标 / `NODE_FAILED` 变红可点开 / `WAITING_STEP` 变黄弹检查器 → 单步控制条放行 → 断线：按 Last-Event-ID 重连补发 → RUN_FINISHED 汇总视图。

### 6.4 对话页（webapp/assistant 共用 chat-ui）

发消息 → POST 创建运行 → 订阅事件流 → chunk 增量追加渲染（Markdown 流式）→ 结果卡片（按富载荷类型）→ 确认卡片（若 WAITING_APPROVAL：展示参数快照+确认/拒绝按钮 → 调确认接口 → 会话恢复流式）→ 历史会话切换。

### 6.5 嵌入助手初始化（assistant.js）

宿主页面引入 script → `AIAssistant.init({endpoint, appCode, ticket获取函数, context})` → 加载出票（宿主提供的 ticket 函数或调出票接口）→ 渲染悬浮球 → 点击展开抽屉（Shadow DOM 隔离样式）→ 对话流程同 6.4 → 上下文属性（工单 ID 等）随每次消息携带。

### 6.6 发布向导（两阶段，v3.7 与后端对齐）

点发布 → **阶段一** `POST publish/precheck` → 校验报告页（分组错误点击定位/警告列表；全过获得 precheckId+draftRev+候选摘要）
  → 编排者确认（**确认前不产生版本**；若期间草稿已变更，提交返回 412 引导重新预检）
  → **阶段二** `POST publish/commit`（携带 precheckId/draftRev/候选摘要/幂等请求号）→ 成功页展示版本号+四件套 → 集成管理页更新片段；重复提交返回同一版本号。

---

## 7. 数据视图：前端状态与本地存储

| Store | 状态 | 持久化 |
|---|---|---|
| authStore | accessToken（内存）、用户、权限集、租户 | refreshToken 走 httpOnly cookie；**token 不进 localStorage** |
| appDraftStore | 当前编辑的应用草稿（config/flow/校验结果） | **草稿自动保存 localStorage**（防页面崩溃丢稿，带版本戳） |
| debugStore | debugRunId、节点状态映射、当前挂起检查器数据 | 会话级（sessionStorage 存 runId 供恢复） |
| chatStore | 会话列表/当前消息流/流式缓冲 | 服务端为准，本地仅未发送草稿 |
| dictStore | 字典/节点类型清单/权限 | sessionStorage 缓存 |

**原则**：业务数据的真相永远在服务端；前端只缓存"正在编辑的未提交内容"和"展示加速"。

---

## 8. 接口视图：API 客户端与事件契约

- **类型化客户端**：`openapi-typescript` 从后端 OpenAPI 文档生成类型 → `api-client` 包装 axios（拦截器：附 token / 统一错误归一 / traceId 透传头）；
- **SSE 事件枚举**（前端 TS 联合类型，与册 31 §6.3、册 32 §5.2 一致）：`RUN_STARTED | NODE_STARTED | NODE_FINISHED | NODE_FAILED | WAITING_STEP | WAITING_APPROVAL | APPROVAL_DONE | RESUMED | CHUNK | RUN_FINISHED`；
- **重连**：fetch 流式客户端自行实现重连——携带 Last-Event-ID 与认证头、指数退避上限、"终态后停止订阅"（不复用原生 EventSource 的自动重连）；
- **错误提示映射**：`BIZ-*` → 直接展示后端 message；`SYS-*` → 统一话术"系统繁忙，请稍后重试"+可展开 traceId；401 → 跳登录；403 → 无权限页。

---

## 9. 规则视图：前端规则

1. **权限**：路由守卫 + 菜单渲染 + 按钮级 `v-permission` 指令，三层一致取自同一权限集；
2. **兼容性（仅桌面，无移动端）**：Chrome/Edge 最近两个大版本；**断点四档** 1366 / 1440 / 1920 / ≥2560，布局全弹性自适应（规则见册 61 §4.1），<1366 横向滚动兜底；密度三档用户可切换；
3. **大内容渲染**：长文本折叠+展开；超长列表虚拟滚动；prompt 全文查看器用代码编辑器组件（只读，带搜索）；
4. **流式渲染**：chunk 高频追加用 requestAnimationFrame 批量刷 DOM，避免每 token 一次重排；
5. **设计 tokens**：颜色/间距/圆角/状态色（灰待/蓝跑/绿成/红败/黄挂起）全部走 CSS 变量，视觉稿到位后仅替换 tokens 文件——**规范全文见册 61《前端设计系统》**；
6. **Shadow DOM**：assistant 组件样式隔离，不污染宿主页面——**Shadow DOM 只隔离样式，不承担安全隔离**；
7. **不可信内容渲染（模型输出/工具结果/产物链接均为不可信内容）**：①Markdown 渲染前经 **DOMPurify 消毒**（禁 script/事件属性/iframe，白名单标签）；②**URL 协议白名单**仅 http/https（禁 javascript:/data:/file:）；③**卡片动作白名单**（下载走受控 artifact 接口，禁直连原始路径）；④外部图片默认代理加载或禁用。

---

## 10. 构建与部署

| 产物 | 构建方式 | 部署 |
|---|---|---|
| admin 静态包 | Vite build | nginx 托管，`/admin` |
| webapp 静态包 | Vite build | nginx，`/w/:appCode` |
| assistant.js | Vite lib mode（IIFE 单文件，内联样式） | 对象存储+CDN/客户 nginx，按应用生成带版本 hash 的引用 |
| 契约同步 | CI 中拉取后端 /v3/api-docs 重新生成 api-client 类型 | 类型不一致 → CI 失败（前后端契约漂移的机器检查） |

---

## 11. 复用与借鉴出处（对照《物料清单》O2/O12）

| 项 | 复用方式 | 开源出处 |
|---|---|---|
| 框架/状态/构建 | 直接依赖 | Vue 3.5 + Vite + Pinia + TypeScript |
| UI 组件库 | 直接依赖 | Element Plus |
| 画布 | 直接依赖 | AntV X6 2.x + x6-vue-shape |
| 图表 | 直接依赖 | ECharts 5 |
| 类型化 API 客户端 | 直接依赖 | openapi-typescript + axios |
| prompt 全文查看器 | 直接依赖 | Monaco Editor / CodeMirror 6（只读模式，见册 32 新增 O16） |
| 嵌入组件样式隔离 | 机制 | Shadow DOM（Web 标准实现） |
| 设计 tokens | 借鉴 | Element Plus 主题定制机制 |


---

# 详细设计 61 —— 前端设计系统（三端风格规范）

> 上位：册 60 前端工程（§9.5 设计 tokens 的展开）
> 覆盖三端：**admin 管理门户**（专业工具风格）/ **webapp 对话页**（轻量阅读风格）/ **assistant 嵌入组件**（宿主内轻量风格）
> 落地形态：`design-tokens` 包（CSS 变量）+ Element Plus 主题覆盖 + 组件使用规范
> 说明：以下色值/字号为**工程默认提案**——正式企业 VI 确定后仅替换 tokens 文件，组件与页面零改动。视觉稿未到位前，本规范即唯一视觉依据。

---

## 1. 设计原则（四条）

1. **双面性**：admin 是专业工具——信息密度优先、克制装饰；对话端是轻量阅读——留白舒适、气泡分区；
2. **状态先行**：运行五态色（灰/蓝/绿/红/黄）是全平台最强的视觉语言，画布节点、列表标签、详情徽标、监控大盘四处必须完全一致；
3. **可换肤**：一切颜色/间距/圆角走 CSS 变量（design-tokens 包），组件内禁止硬编码色值（stylelint 强制）；
4. **密度分级**：admin 紧凑（compact 模式，一屏多信息），对话端宽松（阅读优先）。

---

## 2. 色彩系统（design tokens）

### 2.1 品牌色

| token | 默认值 | 用途 |
|---|---|---|
| `--brand-primary` | `#2563EB` | 主按钮/链接/选中态/用户气泡底 |
| `--brand-primary-hover` | `#1D4ED8` | |
| `--brand-primary-light` | `#EFF6FF` | 选中底/用户气泡浅底 |

> 企业 VI 确定后整体替换为品牌色系，功能色/状态色保持行业惯例不动。

### 2.2 运行状态五色（全平台统一，最高优先级视觉语言）

| 状态 | token | 色 | 出现位置 |
|---|---|---|---|
| 待执行/跳过 | `--st-wait` | `#9CA3AF` 灰 | 画布节点描边/列表标签 |
| 运行中 | `--st-run` | `#2563EB` 蓝（配呼吸动画） | 节点填充/进度 |
| 成功 | `--st-ok` | `#16A34A` 绿 | 节点/标签/统计 |
| 失败 | `--st-fail` | `#DC2626` 红 | 节点/标签/错误 |
| 挂起 | `--st-suspend` | `#D97706` 黄 | WAITING_STEP / WAITING_APPROVAL / 等待外部任务 |

**双色制（对比度修正）**：每态两枚 token——**填充/描边用基色**（图形标记，非文字对比要求 3:1），**文字用 700 级深变体**（浅底胶囊上的文字对比度 ≥4.5:1，满足 WCAG AA）：`--st-wait-text #4B5563`、`--st-run-text #1D4ED8`、`--st-ok-text #15803D`、`--st-fail-text #B91C1C`、`--st-suspend-text #B45309`。

标签样式统一为**浅底深字胶囊**：底=状态基色 10% 透明度，**字=对应 `-text` 深变体（禁止直接用基色作文字——灰/绿/黄基色在浅底上对比度仅 2.3~2.9，不达 WCAG AA 的 4.5:1）**，圆角全弧。

### 2.3 功能色与中性色

- 功能：成功 `#16A34A` / 警告 `#D97706` / 危险 `#DC2626` / 信息 `#0EA5E9`；
- 中性灰阶：`--gray-50 #F9FAFB` → `--gray-900 #111827`（文字三级：主文 `--gray-900`、次文 `--gray-500`、辅助 `--gray-400`）；
- 背景：admin 页面底 `#F5F7FA`、卡片 `#FFFFFF`、分隔线 `#E5E7EB`；对话端页面底 `#F8FAFC`；
- **深色模式：一期不做**，但 tokens 全部成对定义（`--x` / `--x-dark`），结构预留。

### 2.4 数据可视化色板（ECharts）

8 色循环：`#2563EB #16A34A #D97706 #DC2626 #0EA5E9 #7C3AED #DB2777 #65A30D`；状态类图表强制用状态五色，不用循环色。

---

## 3. 字体与排版

| 项 | admin（紧凑） | 对话端（宽松） |
|---|---|---|
| 字体栈 | `-apple-system, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif`（同） | 同 |
| 正文字号 | **13px**（表格 12px，信息密度优先） | **15px**（阅读优先） |
| 字号阶 | 12 / 13 / 14(小标题) / 16(页标题) / 20(大数字) | 13 / 15 / 17 / 22 |
| 行高 | 1.5 | 1.7（对话消息 1.65） |
| 字重 | 页标题 600 / 小标题 600 / 正文 400 / 强调 500 | 同 |
| 数字 | 表格数字用等宽变体（`font-variant-numeric: tabular-nums`） | — |

**单位规范**：字号/间距以 `rem` 落地（根字号基准 16px）；表中 px 为标准档设计值，随密度三档整体缩放；全端支持浏览器 Ctrl+/- 缩放。

---

## 4. 间距 / 圆角 / 阴影 / 栅格

- **间距**：4 的倍数——`4 / 8 / 12 / 16 / 24 / 32`；卡片内边距 16（admin）/ 24（对话端）；区块间距 24；
- **圆角**：卡片 8px、按钮/输入 6px、对话气泡 12px、状态标签全弧；
- **阴影**：两级——`shadow-1: 0 1px 2px rgba(0,0,0,.06)`（卡片常态）、`shadow-2: 0 8px 24px rgba(0,0,0,.12)`（弹窗/抽屉）；禁止更重的阴影堆叠；
- **栅格与自适应**：admin 内容区 24 栅格；**画布页全屏**（导航折叠为图标条，最大化画布）；**布局尺寸一律弹性（两分法见 §4.1），禁止容器级固定像素**——对话端消息流 `width: min(100%, clamp(720px, 60vw, 1040px))` 居中（行长约 40 汉字，随显示器增大放宽）。

### 4.1 自适应布局规则（桌面动态适配，无移动端）

**尺寸两分法**（stylelint 强制检查）：

| 类 | token 前缀 | 单位规则 | 例 |
|---|---|---|---|
| 控件尺寸 `--ctl-*` | 固定 px/rem **允许** | 按钮/输入框/图标/悬浮球/表格行高/顶栏高——控件恒定是刻意设计 | 悬浮球 48px、顶栏 48px |
| 布局尺寸 `--lay-*` | **必须弹性**：百分比 / fr / `clamp(min, prefer, max)` / `min()` | 侧栏/抽屉/消息流/内容区最大宽 | 侧栏 `clamp(200px, 14vw, 240px)`、消息流 `clamp(720px, 60vw, 1040px)` |

**禁令**：布局容器禁止 `width: <固定大值>px` 写法；控件尺寸走白名单放行。

**断点四档（仅桌面，无移动端）**：

| 视口宽 | 档位 | 行为差异 |
|---|---|---|
| ≥2560（2K/4K） | 宽松 | 消息流上限放宽至 1200px；表格增显次要列；密度默认宽松 |
| 1920~2559 | 标准 | 默认布局 |
| 1440~1919 | 标准 | 默认布局（侧栏可手动折叠） |
| 1366~1439 | 紧凑下限 | 侧栏默认折叠、表格隐藏次要列（列配置）、画布工具栏收纳进菜单 |
| <1366 | 兜底 | 横向滚动可用，不保证布局 |

**动态适配机制**：①字号/间距以 `rem` 落地，**密度三档用户可切换**（紧凑/标准/宽松，改根字号与组件 size，偏好持久化）；②画布容器 ResizeObserver 自适应 + zoom-to-fit（节点坐标是世界坐标，与屏幕无关，天然适配任意显示器）；③ECharts 监听容器 resize 自动重绘；④表格 min-width + 弹性列，容器横向滚动兜底；⑤全部抽屉/侧栏宽度用 `clamp()`。

---

## 5. 布局规范（三端）

### 5.1 admin 门户

```
┌────────────┬──────────────────────────────────┐
│ 侧边导航    │ 顶栏 48px（面包屑/租户/用户/确认角标）│
│ clamp      ├──────────────────────────────────┤
│ 200~240px  │ 内容区 flex:1 弹性自适应            │
│ 可折叠56px  │   列表页=筛选区+表格+分页           │
│            │   详情页=页头+页签（概览/编排/调试/  │
│            │   发布集成/运行/记录）              │
└────────────┴──────────────────────────────────┘
```

- **画布页例外**：进入画布 → 侧边导航自动折叠为 56px 图标条（一开关还原），画布区全屏；顶栏保留（保存/校验/调试/发布按钮组）。
- **自适应要点**：侧栏宽 `clamp(200px, 14vw, 240px)`；**内容区 `flex: 1` 占据全部剩余空间**——任何显示器下先充满再谈密度；顶栏/侧栏高度属控件级恒定（48px），宽度类尺寸一律弹性。

### 5.2 webapp 对话页

顶栏（应用名+会话切换图标）→ 左侧会话列表（可收起，`clamp(220px, 18vw, 300px)`）→ 消息流居中（`min(100%, clamp(720px, 60vw, 1040px))`）→ 底部输入框（自适应高度，Shift+Enter 换行）。

### 5.3 assistant 嵌入组件

悬浮球 48px（控件级恒定；右下，位置可配）→ 点击展开侧滑抽屉（**宽 `clamp(360px, 30vw, 560px)`**，高 100%，Shadow DOM）→ 开场白卡片（应用配置）→ 对话区同 webapp 消息流（宽度自适应抽屉）。

---

## 6. 组件风格规范

| 组件 | 规范 |
|---|---|
| **状态标签 StatusTag** | 浅底深字胶囊；五态色；可带图标；画布节点=描边 2px 状态色 + 左上角状态圆点 |
| **按钮** | 每屏主按钮（primary）≤1 个；危险操作用 danger 且**必须二次确认对话框**；表格行内操作用文字按钮 |
| **表格** | admin 统一 compact；表头灰底；操作列右固定；行高 40px；空数据显示空状态插画+引导按钮 |
| **表单** | 标签左置（配置类长表单）/上置（向导分步）；必填红星；校验即时（失焦即校验）；六步向导用 Steps 组件+底部上一步/下一步 |
| **对话气泡** | 用户：右对齐、品牌浅底、圆角 12（右下 4）；助手：左对齐、白底、带头像；流式中显示**呼吸光标**；工具调用显示为可折叠的"过程卡片"（默认折叠，点开看调用了什么） |
| **结果卡片** | 类型图标+标题+折叠正文+操作区（下载/复制/查看产物）；富载荷（图片/表格）按类型渲染 |
| **确认卡片** | 黄色描边+参数快照表格+剩余时间+确认/拒绝按钮（对应 WAITING_APPROVAL） |
| **空状态** | 统一插画+一句话引导（"还没有智能体，创建第一个"） |
| **加载态** | 列表=骨架屏；按钮=loading 图标；页面=进度条；**流式=光标呼吸**（不用转圈） |
| **错误展示** | BIZ 错误显示后端 message+可展开详情；SYS 错误统一话术+traceId 可复制 |

---

## 7. 交互规范

1. **反馈时效**：任何点击 100ms 内必须有视觉反馈（loading/禁用/跳转）；
2. **流式**：打字机式增量渲染 + 停止按钮（调 cancel）；
3. **危险操作**：删除/停用/发布/取消运行 → 二次确认对话框，明确写出影响（"停用后外部调用将返回已停用"）；
4. **快捷键（画布页）**：`Ctrl+S` 保存、`Ctrl+Z/Y` 撤销重做、`Delete` 删除选中、滚轮缩放、空格拖拽画布；
5. **通知**：站内横幅（全局公告）+ 右下角 toast（操作结果）+ 确认中心红点角标（待办数）；
6. **长任务**：批量/算法任务提交后显示任务卡片（进度/取消入口），允许离开页面。

---

## 8. tokens 落地形态（design-tokens 包）

```
design-tokens/
├── tokens.css        :root{ --brand-*, --st-*, --gray-*, --space-*, --radius-*, --font-* }
├── element-override.css  Element Plus 变量同名覆盖（--el-color-primary 等映射到平台 tokens）
└── stylelint.config  禁止组件内硬编码色值（#hex 检查）
```

- 组件写法：`color: var(--st-fail)`；**禁止** `color: red` 或硬编码 `#DC2626`；
- Element Plus 覆盖：主色/圆角/字号映射到平台 tokens，一处改全局变；
- 视觉稿后补流程：设计师产出 tokens 表 → 替换 tokens.css → 全端生效，零组件改动。

---

## 9. 三端风格差异对照表

| 维度 | admin | webapp | assistant |
|---|---|---|---|
| 风格定位 | 专业工具（信息密度优先） | 轻量阅读（舒适优先） | 宿主内轻量 |
| 密度 | compact（正文 13px） | 宽松（15px） | 跟随抽屉宽度 |
| 导航 | 侧边导航+顶栏 | 顶栏+会话侧栏 | 悬浮球+抽屉 |
| 主色使用 | 选中态/主按钮 | 用户气泡/发送按钮 | 品牌色跟随宿主或平台默认（可配） |
| 表格/表单 | 大量 | 无 | 无 |
| 深色模式 | 一期不做（tokens 预留） | 同 | 跟随宿主（Shadow DOM 内可继承变量） |

---

## 10. 视觉稿后补流程

tokens 表 → 设计师基于 tokens 出关键页视觉稿（工作台/画布/对话页三张即可）→ 评审 → 仅调整 tokens 与个别组件样式 → 全端生效。
