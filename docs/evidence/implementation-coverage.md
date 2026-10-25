# 本阶段全工程审查与实现覆盖核对

**当前阅读口径（C2c限定验收后）** 后端联合链与四前端协议已按各自阶段限定闭合，source644；I1定向修复已关闭。模型可信材料／出域和恢复、默认生产及外部环境仍待；以下历史记录不据局部证据改为整体全部完成。

日期：2026-10-20。审查角色：独立 whole-branch reviewer；采用 requesting-code-review、code-review、verification-before-completion 的证据与审查方法。**当前工程已有可审查、可运行的纵切成果，但不满足17项联合验收、34任务完成或生产交付出口。** 本报告不更改任务状态，不新增功能，不将缺功能列成已部署漏洞。

## 1. 范围、基线与证据口径

- 仅读取 `G:/207/项目/算法重组工具/代码/agent-platform` 内当前源码、测试、契约及证据，并经主任务明确授权只读三份设计执行文档：`文档/设计/详细设计/智能体构建平台开发任务拆分.md`、`智能体构建平台待办事项台账.md`、`智能体构建平台实施方案与实施计划.md`。没有读取相邻旧代码、原型、配置、数据、缓存或进程。
- 已读 AGENTS.md、docs/implementation-brief.md、contracts/README.md、freeze.json、冻结详设相关分册及完整任务/验收矩阵。实际 `Get-FileHash` 得到快照 SHA256 `3c72b2062beb2e8b377f2384843ea935a0074b50c0063e5106566bbd8dcd0c4f`，与冻结记录一致。
- 实际 `git rev-parse --verify HEAD` 返回 `fatal: Needed a single revision`，`git status --short` 显示全新工程为 untracked。不存在可比较的 BASE/HEAD；本次是当前源码审查，不伪造 Git diff、提交版本或签字。后续交付须固定可追踪制品/源码版本。
- 未运行数据库查询、未停止/重启服务、未重跑全后端测试。审查时直接读取 Surefire XML：common5、gateway14、app22、engine75、skill70、model54、monitor11，共251项，均0 failure/error/skipped。此为现存分模块测试产物核验，不声称是审查者新执行的单轮测试；主任务的 `.local/backend-reviewed-redis7-all.log` 当时仍在运行，应由主任务以最终结果追加。
- 主要行为证据为各模块测试断言、`integration-report.md`、`frontend-report.md`、`artifact-report.md`、`review-execution-followup.md`、`deployment-report.md` 及其指向的日志。历史报告正文有阶段性待办，采用最新追加结论与当前代码；例如 I1/I2/I3/O1/O2 已关闭，不再用旧行号重报。前端报告最新追加为34项测试、三构建、production audit0；导入与LAST_REAL浏览器增量由对应审查者另行收尾。
- 本机已有独立 PG16.15、Redis7.2.16、MinIO。HTTP/model fixture 仅在测试目录；真实基础设施与真实业务供应商验收是两种证据。5MiB源附件→受控复现新run→下载SHA一致且业务外调增量0，已核读 `tests/smoke-reproduce-artifact.ps1` 和 `.local/reproduce-artifact-smoke-result.json`；不把允许的内部存储HTTP误称为“零HTTP”。

状态定义：**已具备局部实现**＝已有可执行路径及局部验证，仍有本行列明的补齐/验收；**待联调**＝已有消费/适配路径，关键真实依赖或组合验证尚缺；**实现缺口**＝至少一项设计要求没有对应执行行为，保存字段、页面占位或SDK依赖不计完成。三个状态均不是最终验收通过。

## 2. 本阶段新发现

### H1 / P1：CHAT范围收窄后，旧回答未经当前授权复核再次进入模型

**审查时开放，已即时通知主任务。** 位置：`engine-runtime/src/main/java/cn/agentplatform/engine/application/ChatHistory.java:18` 的查询与 `:38–53` 的终答组装；调用点 `AgentDriver.java:72–74`、`ContextAssembler.java:38`；入口 `ChatSessions.java` 的 turn/owned。

触发：同一tenant/principal/app第一轮具有属性范围[A,B]，技能结果及助手终答包含B范围数据；第二轮JWT范围收窄[A]，应用/技能守卫仍允许非空交集。ChatSessions只核owner/app和应用可用性；ChatHistory只按owner/app取历史 `r.output.text`（或整个output）及失败error，未读取历史guard_ctx、调用的有效参数范围，也不与当前权限比较。ContextAssembler无条件加入这些历史消息，正常ModelExchange将其再次发送。

影响：先前可见但当前已撤销的数据可能继续参与新的模型调用与回答。这里**直接复用的是历史assistant终答/错误文本，不是原始tool消息**；只在工具调用处重新求交不能修复历史终答通道。该路径与已关闭的LAST_REAL/受控复现I1是不同入口。静态路径已确定，审查者未执行运行库复现。建议先以权限变化拒绝继续旧会话的保守门禁或逐历史事实授权验证修复，并验证范围收窄、角色撤销、历史错误内容、恢复/读取入口以及拒绝后零新增模型请求。修复及关闭证据应由后续定向报告记录，本报告不以建议代替已修复。

本轮其余检查未形成新的确定P1/P2代码发现。以下“实现缺口”是设计覆盖差距，不能解读为完整安全扫描通过或已经部署了这些缺陷。

## 3. 17项逐条实现覆盖

代码简称：app=`app-management/src/main/java/cn/agentplatform/app`；engine=`engine-runtime/src/main/java/cn/agentplatform/engine`；skill=`skill-registry/src/main/java/cn/agentplatform/skill`；model=`model-gateway/src/main/java/cn/agentplatform/model`。表内类均为这些目录的 application/api/infra/domain 同名文件；测试位于各模块 src/test。对应T/A编号采用任务拆分文档原编号。

| 需求 / 功能 | 状态 | 当前可执行落点（代码 / API / 前端） | 已有证据与尚欠验收 |
| --- | --- | --- | --- |
| 1.1① 模型绑定（T08/T10/T30，A05） | 已具备局部实现 | model ModelStore/ModelCalls/ModelQuota/ModelCircuits；app候选冻结modelRefs；`/admin/model-providers`、entries、freeze/validate、internal chat/stream/measure；`web/admin/src/ChatConfig.vue`、供应商/条目页面 | ModelCallsPostgresTest、Quota Redis/PG及PublishTransactionTest验证精确binding、实际wire、配额/熔断恢复、出域；真实模型与专用tokenizer、双供应商能力/准确计量联调未完成，管理页轮换/配额等完整操作仍需补齐。不能用SDK fixture证明真实模型能力。 |
| 1.1② 模板、变量与版本（T10/T13/T19/T30，A06） | 已具备局部实现 | app TemplateService/AppRules、engine ContextAssembler；`/admin/prompt-templates`、版本publish/render；ResourceDetail.vue、ChatConfig.vue | 严格插值/类型/版本持久化和不可变发布已有规则/PG测试；模板CRUD全生命周期、引用影响界面与完整变量组合消费者验收仍缺。render的token明确估算；并非真实tokenizer计数。 |
| 1.1③ 算法/API/本体技能编排与结果传递（T07/T11/T17/T19/T21，A04/A08/A14/A18） | 待联调 | SkillExecution/SkillAdapters/AlgorithmAdapter、engine AgentDriver/FlowDriver；SKILL节点与`/internal/skills/{code}/execute`；SkillWizard.vue、NodeProperties.vue | HTTP正式写一次、候选确认、后继结果映射与MinIO大结果真实基础设施纵切已有；MCP/K8s只有适配代码，真实本体多空间/两个版本未验。ontologyMeta字段及聚合逻辑不等同本体消费完成。HTTP/MCP异步协议另见G2。 |
| 1.1④ 推理参数、上下文、格式/失败策略（T08/T10/T13/T14/T19，A05/A06/A11/A12） | 实现缺口 | ContextAssembler、ContextProjection、ModelExchange、AgentDriver；ModelCalls将采样/长度/JSON responseFormat传入SDK；ChatConfig.vue提供策略字段 | ContextProjectionTest/ModelExchangeTest与model测试覆盖计量、成对裁剪、独立SUMMARY、先存后发；`output.onInvalid`的RETRY/TERMINATE/PASS_WITH_FLAG消费及格式失败门禁未发现实现（G1）。40轮真实长会话、分批压缩/全局内存闸与H1仍待处理。 |
| 1.2① 画布/分支/映射/保存运行（T16/T17/T19/T21，A14/A15/A18） | 已具备局部实现 | GraphValidator/GraphRules/Values/FlowDriver；`GET/PUT /admin/flows/{code}`、graphs/validate；`web/flow-designer` X6转换、字段表单、错误定位 | GraphRules/Validator/Values、引擎PG与浏览器保存回读已覆盖DAG/ALL/ANY/错误边等主要路径；全类型组合、表达式上限、复杂并行业务流及真实本体后继映射仍需矩阵验证。AGENT/ONTOLOGY专用节点按基线预留，不当遗漏强制新增。 |
| 1.2② FULL/STEP调试与节点明细（T15/T20，A13/A16/A17） | 已具备局部实现 | RunService/DebugControls/RunController；debug-runs/status/resume/variables/prompt/controls；`web/debug-panel/DebugPanel.vue` | DebugControlsTest、RuntimeStreamTest、PG恢复、Chrome FULL/STEP/刷新/断点/FIXED；LAST_REAL有源事实/当前scope与附件后端验证，UI增量另有证据收尾。输入集持久管理/提升评测、真正双副本resume和慢客户端仍缺。 |
| 1.2③ 超时/重试/终止/异常留痕（T11/T12/T14/T16/T17，A09/A10/A12/A14） | 实现缺口 | RunService、TraceStore、ToolCalls、ExecutionRecovery、SkillOutbox、SkillExecution.reconcile；错误边/重试/租约恢复实际执行 | 旧R1–R4、F0–F6、I1–I3已独立关闭，PG竞态/失败事实恢复有测试；完整异步CALLBACK、外部取消三态与UNKNOWN人工决定不具备（G2），不能把run标CANCELLED等同外部任务取消。100轮竞态和真实跨进程三窗口仍需总回归。 |
| 1.2④ 发布校验/固化/执行API（T22/T23/T29，A19/A20/A26） | 已具备局部实现 | app AppService.precheck/commit与IntegrationService；monitor EvaluationService；`publish/precheck`、commit、v1 runs；App.vue发布评测页 | PublishTransactionTest、EvaluationPersistenceTest及真实候选→批准→发布→正式运行验证；六组检查确实消费报告。真实业务数据集/阈值、真实宿主短票据浏览器全链及生产IAM仍待，跨服务依赖瞬时变化不宣称全局事务。 |
| 1.3① 目录检索/展示/正式入口（T10/T24/T28，A21） | 已具备局部实现 | AppService.list/publishedInputs、RunService.create；`/admin/apps`、`/v1/apps/{code}/input-schema`；App.vue列表/运行、WebApp | 应用双轨与精确发布输入、正式流程及CHAT浏览器路径已验证；列表筛选/分页当前在前端对已返回数组完成，服务端分页/规模验证未完成；复制便捷流程仍缺。 |
| 1.3② 领域/业务分类/标签（T10/T24，A21） | 已具备局部实现 | TemplateService.classifications/classification、AppService.metadata；`/admin/classifications` GET/POST；App.vue分类筛选及元数据 | DOMAIN/BIZ_CATEGORY/TAG分维度PG保存与筛选已有；完整字典维护（删除/禁用/引用规则）及客户分类数据未验，不能将字符串字段可保存计为治理闭环。 |
| 1.3③ 生命周期/历史/变更记录（T10/T23/T24，A20/A21） | 实现缺口 | AppService.lifecycle、不可变app_version/skill_reference/publish_commit；disable/enable/archive/rollback；App.vue状态及版本 | 原子发布/回滚/新草稿旧版本运行与PG不变触发器已验证；应用复制API、归档检索/恢复及完整变更记录消费入口未齐（G4）。历史版本表有数据不等于所有生命周期交互已交付。 |
| 1.3④ 状态/节点/耗时/异常/日志（T15/T25，A13/A22） | 已具备局部实现 | RunController/RunService.status、TraceStore事件；`/admin/runs/{id}`与events；RunDetail.vue | 状态、节点I/O/错误、持久水位、取消与受控附件已有真实链；历史列表最多200条且只有当前principal，缺服务端应用/时间/状态组合查询和授权运维全局视图；跨副本chunk/容量未验。 |
| 1.3⑤ 历史查询/I/O/实际版本链（T06/T21/T25，A18/A22） | 待联调 | ReplayService/ControlledReplay/ReplayScope；`replay/reproduce/reexecute`、status.versionRefs；RunDetail与App.vue记录页 | ControlledReplay/BoundaryTest证明重算及当前授权、投影/附件归属；实际5MiB复现下载hash一致且0业务外调。真实本体实际release/version/digest集合未验；多代重试源明确拒绝复现，完整历史查询UI/API仍缺。H1是CHAT历史另一路径，不能借本行复现修复视为已关。 |
| 1.4① 技能登记/I/O/凭据/超时校验（T07/T11/T30，A04/A08） | 已具备局部实现 | SkillCatalog/SkillGovernance/SkillRules、OpenApiImports/OpenApiHttpMapping；skills、connection-test、acceptance、openapi preview/create；SkillWizard/ResourceDetail/OpenApiImport.vue | 真实受控DRAFT→确认→连接→选择门禁→发布，OpenAPI安全子集、PATCH/JSON字符串wire已测试且O1/O2关闭；复杂OpenAPI明确拒绝。完整六维向导的全部合同字段与真实三协议供应商验收仍欠。 |
| 1.4② 三类技能库/版本/引用方（T07/T30，A04） | 已具备局部实现 | SkillCatalog.list/get/references/disable；`/skills/{code}/referenced-by`与versions；技能列表/详情 | 三type可登记、旧引用保留、引用拦截已有；库的服务端多维检索/分页、完整引用/影响界面与真实三类资源联调待补。HTTP/MCP/ALGORITHM是协议分类，本体是HTTP消费，不冒充已交付本体平台。 |
| 1.4③ 技能配置版本化/旧引用保留（T07/T23/T30，A04/A20） | 已具备局部实现 | SkillCatalog.save/publish、V302不可变约束、AppService.commit引用固化；versions/publish与详情 | 发布新版本旧版ARCHIVED、精确版本调用与发布事务已有测试；升级提醒/影响分析和实际多消费者主动升级/回退验收未齐，不能因版本号存在而报全覆盖。 |
| 1.4④ 次数/耗时/失败原因/下钻（T11/T26/T30，A17/A23） | 实现缺口 | SkillExecution.statistics直接聚合execution_fact；`/skills/{code}/stats`、model统计、engine internal/statistics；App.vue统计页 | call_id主键、用途/is_mock分组、平均/p95已有；时间范围/失败语义明细下钻、平台业务系统/token/确认量汇总和完整统计重建/对账验收缺失（G3）。当前平台接口仅按trigger_type/status计run数，不能称完整报表。 |

## 4. 确定的实现差距与目录/开源机制对照

| 差距 | 当前具体证据 | 下一步代码工作与验收范围 |
| --- | --- | --- |
| G1 格式失败策略 | 冻结详设第二部分§5定义output.onInvalid三分支；ContextAssembler:84传递字段，ModelCalls:308只设置供应商responseFormat；engine/model生产代码搜索onInvalid/PASS_WITH_FLAG无处理器 | 在受控循环边界校验实际返回，按冻结策略计预算/持久化重试或失败/标记通过；故意返回错误JSON/Schema的模型fixture证明三分支及恢复不多发。不是依赖真实模型才能写。 |
| G2 异步、外部取消、人工对账 | SkillCatalog:284显式拒绝HTTP/MCP非SYNC与CALLBACK；AlgorithmAdapter有确定Job名/查询但没有完整取消协议；SkillExecution.reconcile已查询三种外部结果，NOT_APPLIED仍返回retryBusinessAllowed=false，无人工决策完整入口 | 按既定契约实现受鉴权回调、取消传播/三态、NOT_APPLIED允许重试的安全转移与UNKNOWN人工审计；补错投/重放/迟到/取消竞态。实际MCP/K8s能力确认仍需外部环境。 |
| G3 统计/监控查询 | SkillExecution:711聚合全时期fact，无time/error维度入口；RunController:196平台统计只有trigger/status；RunService:1410列表当前主体最新200条；未找到stat_invocation迁移/重建任务 | 先冻结指标粒度和源事实关联，再补时间/业务系统过滤、失败下钻、重建/对账与分页；A23故障后不丢不重，禁止关联多表重复累加token。 |
| G4 管理完整操作 | AppController无copy；应用列表排除ARCHIVED且无归档查询；TemplateService仅创建/修订/发布/试渲染；资源页以版本和JSON结果为主；供应商轮换/配额API先于对应管理UI | 补应用复制、归档/历史管理、可执行字典/资源维护、引用方/影响页面；提供明确操作入口与权限反例。完整IAM管理或复用入口应等接口责任边界明确后接入。 |
| G5 分布式与内存承载 | ChunkBus是进程内ConcurrentHashMap订阅，持久事件仍在PG；RunService.recover按id取前100个RUNNING并启动虚拟线程；无跨副本chunk广播/公平队列完整实现证据 | 补跨副本在线chunk、慢消费者有界策略、公平调度和运行级并发/缓存/缓冲硬闸；验证A13/A29。当前SQL租约与模型配额不能替代全引擎容量门禁。 |
| G6 用户管理/审计/集成生产边界 | ServiceTokens.issue受众为共享agent-platform-internal，未细分caller/target scope；SecurityConfiguration采用当前开发JWT体系；部署entrypoint默认IAM/scope未验收拒绝启动；审计接口仅最近500运行事件 | 实现生产身份适配、服务目标scope、操作审计检索与IAM入口，保留部署阻断直到真实验收。不是“公网已部署越权漏洞”的结论；不能仅设门禁开关true宣布完成。 |
| G7 数据生命周期与可恢复交付 | PG备份恢复44表已有；对象/vault/masterkey一致恢复、双副本滚动退出与实际镜像运行无证据 | 补数据保留/清理、恢复引用核对、制品digest/原生配置运行验证和手册；先用独立开发环境完成可执行演练，再进入客户资源。 |

目录方面，七后端模块与六前端包符合详设服务布局，跨服务HTTP、PG业务权威、独立schema与迁移已有。包层次总体符合api/application/domain/infra；不是每个服务都四层齐备，monitor目前主要是评测和引擎HTTP投影，尚不等于完整监控统计服务。业务SQL散在application及少数api中，建议后续随相关任务收敛职责，不为形式新增空层。

已核 `docs/open-source-references.md` 与实际源码注释：Spring AI单次调用/关闭自动工具执行、Harness有序组装/配对/持久事件与chunk分离、Dify流式取消交互机制、X6定义与布局分离均有实际落点；没有复用相邻工程。源码对重要租约、事务、授权、重放、原始事实保留写了原因注释。Dify许可被明确区分，未声称复制React/CSS。MCP/K8s/swagger等直接依赖不得等同真实协议验收或读遍上游源码；旧注释如ContextAssembler“配置摘要器前拒绝溢出”已落后于ContextProjection，宜在文档整理时纠正。

计划要求的统一 `docs/design-traceability.md` 目前不存在；本报告加open-source-references及各专题证据形成初步对照，但尚未替代正式逐模块设计→来源→代码→测试索引。前端具备结构化输入、渐进高级JSON、真实错误与空态、三产物主链；“简单可用”已有浏览器证据，完整管理矩阵、密度三档/对比度审计和所有权限态仍需逐项验收。

## 5. T01–T34差距索引（不改台账状态）

下面只记录相对工作包出口尚欠内容；现有代码与证据复用第3节，避免重复抄实施内容。

| 任务 | 当前可review产出 | 距完整工作包出口的差距 |
| --- | --- | --- |
| T01 | 隔离根、独立Git/缓存/端口、冻结快照 | Git无HEAD属当前事实；制品/源码版本追溯在交付前补齐，不伪造提交。 |
| T02 | 四契约提取/摘要/Schema | 消费者版本记录、完整正反契约样例及项目评审签收仍需按台账核验。 |
| T03 | Java21/Boot3.5/AI1.0、Vue多包、CI脚本 | CI实际流水线/制品留存/部署秘密注入联验；仅本地可构建不足以认CI完成。 |
| T04 | 独立PG/Redis7/MinIO、HTTP FC/流式、X6 | 真实IAM、MCP/K8s、双真实模型/tokenizer环境与PoC。 |
| T05 | 容量目标保留 | 真实模型与资源的版本化定标参数未有，不能填造p95。 |
| T06 | 各域Flyway、PK/FK/CHECK/不变触发器 | 完整空库/升级矩阵、全部约束反例与长期保留规则核对。 |
| T07 | 六维注册、发布门禁、版本/引用API | G4完整目录治理；真实三协议验收和语义类型分类；引用升级交互。 |
| T08 | 冻结绑定、模型调用/计量/配额恢复 | 真实供应商能力/计量校准，管理消费者与容量联验。 |
| T09 | JWT/Actor、参数交集、附件owner、确认 | H1先修；G6生产IAM/服务scope与敏感操作实时重校验总验收。 |
| T10 | 草稿/模板/分类/策略存储与引用 | G1执行策略、G4完整模板/分类管理，不能只存字段。 |
| T11 | 登记、事实/outbox、HTTP/MCP/Job适配 | G2完整异步/取消/人工对账，真实MCP/Job；全部八步组合验收。 |
| T12 | PG租约、代次、唯一提交与审批状态 | G5调度公平/容量闸；真实三恢复窗口、100轮旧新执行者与外部计数。 |
| T13 | 组装、计量投影、SUMMARY与材料先存 | H1历史scope；40轮组合重建、分批摘要/缓存/缓冲/并发/直接内存六道硬闸。 |
| T14 | Agent受控循环、检查点/多工具 | G1格式策略及真实模型六出口/四预算/无进展完整谱。 |
| T15 | PG事件、水位、认证SSE/客户端 | G5跨副本在线chunk、慢客户端和5000+连接实证。 |
| T16 | 图Schema、DAG/表达式/类型子集 | 全类型/默认值/错误策略组合、表达式时间上限精确验证。 |
| T17 | FlowDriver分支/汇合/跳过/节点 | 真实复合流全组合、G2异步节点与外部取消消费者验证。 |
| T18 | 六包/三产物、tokens、类型API | 资源页面/状态完整矩阵、密度三档与对比度/权限检索验收。 |
| T19 | X6定义/布局分离及结构表单 | 全节点属性组合与复杂图保存/执行往返验证。 |
| T20 | FULL/STEP/断点/Mock/受控测试 | 双副本/restart resume、输入集提升评测、LAST_REAL UI最新验收归档。 |
| T21 | 本体HTTP/ontologyMeta消费槽位 | 真实多空间/同空间两个版本与实际releaseId/version/digest；不能自造本体结果。 |
| T22 | 候选真实执行/规则报告/门禁 | 客户用例与阈值、自动恢复调度/归因反馈等轻量范围核对；LLM-judge/标注/A-B明确二期，不当一期阻塞。 |
| T23 | 两阶段发布、并发/幂等/事务引用 | 全故障点及重启结果恢复组合；精确依赖变化时门禁联验。 |
| T24 | 双轨生命周期、正式输入/运行 | G4复制/归档/历史完整操作与服务端规模检索。 |
| T25 | 状态详情、三级回放/工件复现 | G3历史检索，T21实际版本链；多代重试复现明确受限；完整UI操作联验。 |
| T26 | fact聚合、真实/Mock分组、model usage | G3时间/原因下钻、平台报表、重建/对账与统计故障用例。 |
| T27 | 中心及运行内确认，摘要/角色/期限 | 真实IAM确认人解析、参数变化/过期/通知入口与G2取消三态组合。 |
| T28 | WebApp会话/流式/确认/附件 | H1先修；会话业务系统维度及全切换路径、真实宿主身份/富卡片动作完整验收。 |
| T29 | Service API/SDK/短票据/吊销 | 真实宿主后端出票→浏览器全链、版本钉定/来源/吊销SSE组合、生产IAM。 |
| T30 | 供应商/模型/模板/技能/评测页 | G3/G4/G6完整CRUD、引用统计、用户/角色/租户/配置或IAM复用入口。 |
| T31 | 当前17项覆盖报告、Chrome纵切 | 第3节全部尚欠验收闭环，H1清零，固定验收版本和业务签收。 |
| T32 | 既有独立审查与PG/HTTP精确竞态 | H1修后回归；100轮/跨进程/多副本/错投/统计重建总回归，不能只合计单测数。 |
| T33 | 单次5MiB/清理与复现证据 | T05参数后执行500运行、5000+SSE、200×5MiB及慢客户端/取消完整负载。 |
| T34 | 43源/44渲染对象静态校验、Collector验证、PG44表恢复 | 未运行镜像/集群，缺双副本/灰度/回退/完整灾备与运维观测实证；G7及客户清单/签收。 |

## 6. A01–A30验收差距索引

“局部证据”均为读取现存结果与断言，不是审查者重跑；每组原始验收条件以任务文档为准，下面不缩减其条件。

| 组 | 局部证据 | 尚欠完整判定 |
| --- | --- | --- |
| A01 | 多模块构建/独立三存储/前端X6及fixture FC流式 | 真实IAM/K8s/双模型探活及Job结果；CI/交付组合。 |
| A02 | 四契约freeze.json与快照hash实核 | 人审记录/生产消费者/正反样例全追踪。 |
| A03 | Flyway真实PG、TracePostgres/PublishTransaction | 全约束/空库升级组合与17字符错误语义持久化归档。 |
| A04 | 技能注册/门禁/旧版/引用测试、OpenAPI wire | 三类真实服务及完整检索/停用归档/管理交互。 |
| A05 | ModelCalls/Quota/Circuit、冻结binding/路由反例 | 真实供应商/tokenizer/多模型上下文及运行路由能力联合验收。 |
| A06 | 模板插值与采样/长度实际SDK参数 | G1格式失败三分支，全部策略执行，不是JSON存取。 |
| A07 | tenant/principal、守卫交集/工件权限反例 | H1关闭、生产IAM实时吊销和隐藏数据范围联合验证。 |
| A08 | call登记与幂等、真实写计数、正式Mock拒绝 | 同键100轮/多工具真实外部副作用全组合。 |
| A09 | ExecutionRecovery/Outbox与R1–R3定向关闭 | 真实进程崩溃/队列丢失三窗口、实际外部对账/取消。 |
| A10 | TracePostgres/ToolCancellationRace、租约CAS | 100轮旧新执行者/跨副本接管与业务计数。 |
| A11 | ContextProjection/ModelExchange成对裁剪与SUMMARY持久化 | 40轮真实长会话、含压缩完整重建与发送失败全路径；H1。 |
| A12 | Agent/Flow测试、FC fixture、预算代码 | 六出口/四预算/所有异常策略与真实模型完整谱。 |
| A13 | RuntimeStream及前端SSE恢复测试、Chrome | 双副本在线chunk/过期游标/慢客户端/大连接。 |
| A14 | GraphRules/Validator/Values及Flow PG测试 | 全节点/类型/错误/并行映射矩阵、表达式50ms实际断言核验。 |
| A15 | 四断点截图、结构表单/错误定位、三构建 | 密度三档/对比度、完整审计检索与IAM管理入口/越权反例。 |
| A16 | DebugControlsTest与刷新恢复、resume CAS | 两独立副本/restart挂起隔离、保留期覆盖历史。 |
| A17 | FIXED/LAST_REAL权威源/当前scope测试与UI局部 | LAST_REAL当前UI增量归档；慢/取消/超时资源与混合调用完整矩阵。 |
| A18 | ontologyMeta承载、run实际集合代码 | 真实本体至少两版本多空间；缺失此证据不得报通过。 |
| A19 | EvaluationPersistence与真实首候选/写确认续跑 | 业务数据集/阈值、全部安全/回归用例组合。 |
| A20 | PublishTransaction并发/回滚/幂等、浏览器发布 | 全写入点故障、跨服务依赖变化/重启重试结果证据。 |
| A21 | 六维前端筛选、双轨与正式运行Chrome | 复制/归档管理/变更记录及服务端查询一致性。 |
| A22 | 详情/回放重算/附件5MiB新owner hash | 完整历史过滤、真实本体版本链、所有受控复现边界UI。 |
| A23 | call_id事实、用途/Mock分组、usage持久对账 | G3全部报表维度/失败下钻/重建崩溃与源表核对。 |
| A24 | 真实等待→批准→写一次、取消竞态测试 | 真实角色解析、通知/参数改动、G2外部可取消三态及重启总回归。 |
| A25 | 多轮/刷新/SDK、消毒/URL/附件、无localStorage票据 | H1；业务系统维度/会话切换、富卡片动作和真实宿主身份链。 |
| A26 | 短票据/APIKey运行、admin403/吊销401 | 真实宿主出票浏览器全链与吊销既有SSE/钉版本全组合。 |
| A27 | 本报告17行与主链证据 | 无17/17联合通过记录；H1开放，其他实现/联调差距仍存在。 |
| A28 | 错归属/旧租约/新附件owner等定向反例 | 全100轮/多副本组合、外部计数及统计重建；H1修后加入总回归。 |
| A29 | 5MiB真实MinIO有界接收/下载/复制 | 未执行规定500/5000/200负载，无定标p95/持续时长资源曲线。 |
| A30 | 部署schema/Collector离线验证及PG新库恢复 | 镜像/集群实际部署、灰度/回退、PG+对象+vault一致恢复、完整手册/签收。 |

## 7. 可继续编码的依赖顺序与真正外部输入

1. 先修H1并独立复核，再推进T09/T13/T28消费者与T32回归。已有本地PG/Redis/MinIO足以写关键失败路径，不需等待客户业务系统。
2. T10/T13/T14补G1；T11/T12/T27补G2，先用测试内受控协议端点覆盖取消/对账/回调失败，保留真实协议验收状态。二者可沿现有生产者接口继续实现。
3. T26先补G3指标/查询/重建，再让T25/T30接真实统计与历史过滤；T24/T30并行补G4管理操作。T18–T20继续输入集、完整界面状态和跨副本消费者。
4. T12/T13/T15补G5，随后用两独立新工程副本跑T32。部署候选静态通过仅是起点，不能等同两副本已验证。
5. 外部输入到位后完成T04/T05/T21/T29真实业务纵切，再按T31→T32→T33→T34完成正式出口。LLM-judge/人工标注/A-B与本体平台本身属于已明确后续/范围外，不据此阻塞一期其余代码。

真正需要外部输入的事项：生产IAM issuer/信任材料/角色与属性字典/服务caller-target授权及管理边界；真实模型与专用tokenizer、配额和部署资源（用于能力/计量/容量定标）；本体多空间多版本服务及实际releaseId/version/digest报文；MCP服务和独立K8s API/namespace/serviceAccount/允许镜像/网络/RBAC；业务评测集与通过率/质量口径、宿主系统服务端出票集成；客户TLS/网络/存储/保留期/备份目标及发布签收责任。G1/G3/G4等内部代码未齐不能归咎于这些外部输入。

## 8. 审查结论与验证记录

本阶段适合review的成果：真实PG权威执行和租约/回调事实、候选评测/发布/正式运行、精确模型绑定与账务恢复、受控调试/复现/大工件、前端主链以及独立部署候选。已有验证覆盖关键控制路径，不能将这些成果贬为纯骨架；同样不能将251项现存测试或17项设计覆盖表称为完成产品。

阶段结论：**保留H1修改请求；允许继续依赖已验证局部接口开发，正式G0–G4整体出口均须按原条件补证，当前不批准17项最终验收或生产发布。** 外部IAM/scope未验收默认阻断的部署门禁应保持，不因静态清单通过而开启。

本报告新执行的是只读文件/源码检索、快照SHA、Git基线检查及Surefire XML汇总。未声称重跑后端、浏览器、负载或集群。报告文件自身将用条目计数和存在性检查核对；后续代码/测试变化需追加证据，特别是H1与主任务当前整轮回归。

### 追加：交稿前证据核对

已直接读取 `.local/backend-reviewed-redis7-all.log` 最终结果：2026-10-20 04:26:56，七模块全部SUCCESS、BUILD SUCCESS，251项且零failure/error/skipped，与本轮XML汇总一致。此为主任务实际执行、审查者读取，不冒认审查者重跑。其后主任务开始H1修复，故该251项不能覆盖H1新增回归，H1关闭需另附定向红绿与复核结果。

报告结构实际检查：17条需求、34条T任务、30条A验收，无遗漏或重复编号；`Test-Path docs/design-traceability.md` 为false，G4/追踪索引差距没有把已存在文件误报缺失。对报告执行 `git diff --check --no-index -- NUL docs/evidence/implementation-coverage.md` 无空白错误输出；exit1来自新文件与NUL不同，不将其称为Git分支diff或测试失败。

### 追加：H1定向修复关闭复核（04:33:19绿测后）

**H1 — CLOSE。最新结论撤销本报告针对H1的修改请求；上文H1开放、待修和清零条件保留为发现时记录，以本追加为准。** 本次只读 `ChatHistory.java`、`ChatSessions.java`、`ChatSessionsTest.java` 及H1红绿日志，没有复核施工中的G1/G3代码，没有重跑完整测试、查询运行库或修改生产源码。H1关闭不改变其余17项/34任务/30组验收差距，不表示生产IAM实时撤权已验收。

当前实现位置：`engine-runtime/src/main/java/cn/agentplatform/engine/application/ChatHistory.java` 的 checkScope/requireScope/load，及同目录 `ChatSessions.java` 的新轮受理分支。

- 新requestId在任何turn预留前，按session/tenant/principal/app读取所有已有历史run.guard_ctx。当前Actor的角色必须覆盖源角色，当前每个属性值集合必须覆盖源集合。集合顺序不影响结果，权限扩大允许；属性范围收窄、角色撤销、缺少roles/attributes证据拒绝，返回403 `CHAT_HISTORY_SCOPE_CHANGED` 并提示新建会话。
- load在拼装任何历史user/assistant内容前再次核源guard与当前run.guard_ctx，覆盖执行和恢复入口。成功终答和失败error共用这一前置检查，不能靠换成FAILED绕过。该第二道检查也避免把新轮入口检查作为唯一历史外发依据。
- 旧requestId仍保持既有受理结果的幂等读取；它不会在此分支创建新的历史模型请求。此次关闭限定于H1的“权限收窄后新轮再次向模型发送旧宽范围内容”路径。运行中外部IAM实时撤权、既有历史记录的另行保留/可读政策仍属原有生产身份与授权验收边界，未由此实现替代。

已直接读取真实红绿日志：`.local/chat-history-scope-red.log` 在2026-10-20 04:29:41为1项、1 failure，明确 `Expected PlatformException to be thrown, but nothing was thrown`；不是缺方法编译失败。`.local/chat-history-scope-green.log` 在04:33:19为 `ChatSessionsTest` 4项、0 failure/error/skipped、BUILD SUCCESS。测试由主任务执行，本审查者核对日志与断言，未冒认亲自重跑。

新增用例在真实PG测试库预置同owner源回答 `contains protected B data` 与源scope[A,B]，检验收窄[A]在ChatHistory.load和新轮受理处拒绝、模型调用计数0、chat_turn仍仅1条；还检验集合[B,A]等价、扩大[A,B,C]可用、角色撤销拒绝、FAILED错误含B仍拒绝、缺guard证据拒绝。原同权限双轮正例仍实际进入模型fixture两次，并确认第二轮请求带前轮问题/回答；既有requestId和应用停用边界另两项保持通过。这里的历史事实由测试预置，模型为受控fixture，不将其误称真实业务工具或供应商的端到端验收。

该修复阻断原H1明确触发路径，未发现需要继续阻断H1关闭的新问题。其余功能补齐及全套回归由对应任务继续，本定向复核到此结束。

### 主实施者追加：局部差距推进检查点（05:41完整回归后）

以下由 root 实施者记录，引用各专题独立评审；不冒认本覆盖报告原审查者重新审核全部17功能/34任务/30组验收。上文发现时记录保留，当前局部结论以本追加及专题为准。

- G1：严格JSON/Schema及RETRY/TERMINATE/PASS_WITH_FLAG、逐次持久化/预算/恢复/复现已实现，Schema两个P2独立关闭；实际协议fixture三分支及无效ref零调用，正式PASS应用WebApp/SDK提示和历史恢复通过。见review-output-policy、integration-output-statistics-lifecycle及frontend-output-policy。
- G3：源事实统计、UTC维度/错误下钻、V501按源快照和幂等原子重建已实现；NO_SEND统计量P2和前端过滤分页P2独立关闭。实际JWT/跨租户/三并发重建/PG源4=4通过。全历史服务端过滤、灾难/大规模重建组合仍欠。
- G4：应用复制、AND筛选/显式归档、日志、版本差异已实现；复制并发源快照P2和前端迟到diff P2独立关闭，真实发布v1/v2及Chrome通过。完整分类/模板治理、持久输入集等未被这些局部结果替代。
- G5：Redis7片段、PG有界事实读取/游标恢复及两后端P2已实现并关闭；两个真实JVM的1–9事实/片段和游标7补8/9通过。前端恢复与鉴权两P2关闭后83项全套及三构建绿；真实65秒又发现MVC约30秒结束连接、RUNNING空输出误当终答。前者配置修复红绿3项并独立复核，后者正在修复；实际55秒复验尚欠。公平调度、其余内存闸、规定容量仍欠。
- G2：HTTP SUBMIT_QUERY两P1独立关闭；真实engine/skill的立即完成、等待查询、ACK丢失按原key恢复均submit1且技能节点逻辑提交1。签名CALLBACK已开始TDD，NOT_APPLIED授权恢复、取消三态和UNKNOWN人工审计仍欠；非UNKNOWN既有事实再次交付授权语义另列待核对，未泛称所有历史读取已覆盖。

05:41:11单轮后端366项、0失败/错误/跳过，由root实际执行，见backend-stream-async-reviewed-all.md；不是把模块分批绿测相加。其后MVC/前端空快照/CALLBACK增量不包含在该轮声明里。T01完成标记及原A出口保持；仍无17/17联合验收或生产发布许可。G6生产IAM/caller-target及完整审计、G7一致灾备/真实镜像集群仍按原差距继续。

### 主实施者追加：实际流式复验、CALLBACK与输入集增量

前文“正在”保留为发现时状态。本追加只更新有独立证据的局部结论：

- G5首EOF复验已完成：MVC60秒配置与非终态空快照修复均独立关闭后，实际65秒提供方fixture下WebApp/SDK首EOF55018/55017ms、各2流/2状态、一个run/turn，终态前后续片段可见、最终答案完整、seq1–6不重。见frontend-stream-live.md；公平调度、其余内存硬闸及容量仍未完成。
- G2签名CALLBACK已实现并独立复核，skill133完整回归，新增回包deliveryAccepted仅描述本次投递。实际四场景全部submit1：成功中W3同fact先采纳/逻辑提交1；FAILED无result保留QUOTA；ACK前早到同消息重投成功；取消后迟到只留LATE且不复活。见signed-callback-live.md。NOT_APPLIED不可变证明开始施工，引擎许可、取消三态及人工闭环仍待，不将“本地取消运行”当作“外部停止或撤销”。
- T20/T22后端命名输入、CAS、源revision冻结提升与不可变数据集已完成三域E2E；输入Schema字面量误拒的sole P2红28/183→绿，engine330，独立23探针关闭。前端23项受控Chrome、83项测试/三构建及独立评审通过；gateway/app/monitor已更新开发进程，真实页面链路待engine更新。见test-input-report、input-schema-literals-fix、review-test-inputs、frontend-test-inputs、review-frontend-test-inputs。
- 新的实际运行入口缺口尚未关闭：DEBUG缺必填/错type/minLength、EVALUATION缺必填、正式运行缺必填均错误200受理，各新run1且合计fixture模型调用5。runtime-input-validation-red.log/json保留。正在修共有RunService入口，并统一FLOW START校验以允许原契约明确支持的required NULL；无效输入须在创建run及付费调用之前拒绝。命名保存有校验不能作为所有运行入口已校验的证据。

上述330/133/83分别属于不同范围回归，不合并为新的同轮完整后端数字。原17项/34任务/30组出口及生产默认阻断状态保持。

### 主实施者追加：675完整回归与输入、证明链路关闭

本追加由root记录，引用各专题独立复核，不替代本报告最初审查者对17项/34任务/30组的整体复核。上文开放状态保留为发现时证据，以下局部结论优先。

- 运行入口/START完整声明及会话预约P1已修复并独立关闭：engine365，独立35+原4项通过；部署后真实五个无效输入均400/0run/0模型，required NULL/false/0/空容器FLOW成功，两实际会话拒绝后0turn/next1，修正原ID或新ID各正常一次且幂等读原run。见runtime-input-validation-live、review-runtime-input-validation；原红证据及REQUEST_CHANGES保留。
- T20/T22真实页面9项链路通过：命名输入保存rev2、显式使用、debug、独立expected/safety提升、不可变选集及原候选ACCEPTED，candidateSha未变。FLOW390撑宽仅在DebugPanel scoped CSS修复；canvas244px/两节点完整，83项/三构建、原受控23项及独立390/1440只读复核通过。见frontend-test-inputs-live、frontend-test-inputs-mobile-fix、review-frontend-test-inputs。
- G2不可变NOT_APPLIED/NOT_SENT证明、旧APPLIED代次CAS及proof服务票据P2独立关闭，skill167。实际engine/skill/PG及隔离协议端点生成srp:1，重复读取同摘要、独立重算摘要一致、原业务提交1；portal+SERVICE403/其他主体404，原调用仍UNKNOWN且retryBusinessAllowed=false。异步三场景、签名回调四场景在此版本重新通过；见skill-reconciliation-proof-live及review-skill-reconciliation-proof。不将历史证明等同重试许可。
- 评测WAITING_EXTERNAL/PENDING误判终结已红绿修复、monitor22与独立9项通过并部署，保留原报告和用例runId恢复。G5异步调度之后还须接同报告观察/恢复页面；当前完整原始JSON显示不作为易用性终态。
- 最新root实际执行06:55:24单轮七模块675项，零失败/错误/跳过：common5/gateway15/app35/engine365/skill167/model66/monitor22。235份后端src/POM在该轮前后SHA完全相同，见backend-input-proof-reviewed-all及source-sha256/summary文件。包含183项Schema参数化边界，不宣称675项独立功能。前端83项另列，不相加冒称后端整轮。
- 最新06:51 PG一致备份已恢复至新库，51表含Flyway；核对证明SHA、命名输入2行、engine214/skill310/monitor502及proof不可变触发器，见backup-restore。不等于对象/vault/masterkey一致灾备。

675冻结之后G5公平调度/V215、交互/批量分池及租约/drain矩阵、MCP固定SDK0.10列工具/能力协商/有界SSE继续施工，不能冒称已经包含在675里。G2单次授权许可依赖G5稳定及expectedAttempt传播，随后推进外部取消三态/UNKNOWN人工审计。其余内存闸、完整历史/模板/分类/输入集治理、生产IAM/caller-target及容量出口保持待办。

用户明确当前无专用模型、本体、IAM与K8s联调环境，授权继续实现并用新工程隔离端点验证；不再反复索要环境。内部适配、协议测试与界面仍持续开发，真实外部联合验收另记。T01为唯一已完成任务标记，原依赖、原契约与无工期安排保持，未代签17/17或生产发布。


### 主实施者追加：846与真实MCP、双JVM、异步评测闭环

2026-10-20 08:29:57单轮七模块846项，0失败/错误/跳过（common10/gateway16/app35/engine427/skill270/model66/monitor22），262份后端src/POM前后SHA一致；六份新制品嵌同一common并已更新独立开发服务，真实MCP完整页面/协议、双实际engine JVM调度、同报告异步评测页面均通过。 详见backend-schema-scheduler-reviewed-all/deployment、review-shared-schema-resource、mcp-protocol-live、run-scheduler-replicas-live、frontend-evaluation-async-live及review-frontend-mcp-live-layout。

共享Schema独立40安全测试APPROVE，原自动中断审查不改成批准；循环Schema/RE2子集限制明确存档。前端127/三构建和完整App390布局独立通过。真实MCP整轮业务2、两次UNKNOWN查询零重发，最后仅本地取消；调度真实两JVM仅证明未领取node的dispatch接管和200尾任务有限公平。异步评测原报告创建1、GET1/resume1→ACCEPTED。08:43 PG新库恢复51表含Flyway并核对215/310/502/原证明/输入3行；不替代一致灾备。

继续顺序：原attempt显式传播及HTTP异步发送CAS→算法PREPARING/身份UID/Pod归属/有界I/O协议→G2未生效证明的引擎单次许可与W1–W3→取消三态/人工审计；随后按依赖推进完整历史/输入集/模板分类、其余内存硬闸、G6可信IAM及caller-target。expectedAttempt增量源码另立阶段，未经新统一回归不纳入846。

这些是有限隔离端点及真实本地基础设施验证，不等于17项/34任务/30组全部联合验收或容量通过。T01唯一完成标记，原依赖、原契约、不安排工期及IAM/scope发布默认阻断保持。用户确认无专用模型/本体/IAM/K8s环境，继续实现并用隔离测试端点验证，不再索要环境。 本追加由root记录，不冒认最初覆盖审查者重新批准17项整体覆盖。


### 算法962统一回归与待完成真实纵切

2026-10-20 10:44:28，root实际单轮七模块962项，0失败/错误/跳过；272份后端src/POM前后SHA一致。expectedAttempt及算法Job身份/UID/Pod归属/单次发送/可信Failed不可重试/有界I/O与MinIO生命周期返修均已独立关闭并纳入本轮。10:48固定六份新制品、内嵌common核对后更新独立新工程开发进程，六健康UP，skill迁移311；旧846制品/日志保留。证据为新工程docs/evidence/backend-algorithm-reviewed-all、backend-algorithm-deployment和review-algorithm-protocol。

算法runner准备阶段12项有限检查与独立审查通过，但真实首跑在业务请求前因Windows PowerShell默认编码使中文JAR路径乱码而被归属守卫拒绝；原finally清理失败保留，后续只读确认记录PID及端口均无残留。正在仅修runner UTF8及最终fact原代断言，保留原patch/SHA/红证据，复审后再跑五实际场景。962不是该纵切成功，亦不重复宣称旧846页面/双JVM矩阵已在新制品重跑。

10:51 PG备份373385字节，SHA256 d9f4bec3e0ea2f5a93dc86cc81c52fb41fe066852baff0fe9701e9a647b3f54c，单事务恢复新库52表（含Flyway）；只读核对215/311/502、原proof摘要/触发器、输入3行、算法两唯一索引及历史不可变触发器，见algorithm-restored-data和backup-restore。运行库连接不变，未启动恢复库服务；仍不替代对象/vault/master key一致灾备。

实施顺序保持：算法真实纵切关闭→已批准计划G6 A–E→G2完整证明许可/W1–W3→取消三态/UNKNOWN人工审计。G2生产另依赖G6 F真实跨服务及IAM签收。仅T01标记完成，原四契约/依赖/无工期安排及BLOCKED_IAM_SCOPE_ACCEPTANCE不变；17功能项、34任务、30验收及容量没有整体签收。


### 算法 V5 隔离五场景复核及 G6 基础层交接

2026-10-20，固定962阶段engine/skill制品与已独立批准的runner/oracle实际执行第五轮，5/5通过、exit=0，事后独立复核spec/quality通过。正常完成、丢ACK查询原Job、可信Failed、Running UNKNOWN后仅本地取消、真实MinIO结果卸载五类均保留完整逐行证据；见新工程docs/evidence/algorithm-process-live-v5.md及review-algorithm-process-runner.md最新追加。原四轮失败与返修红证据保留，不能回写为五轮全部成功。

唯一新库agent_platform_algorithm_live_ec8285eb1f44保留：5run/5call/5fact/5binding/1artifact。ACK原外部attempt5、唯一新增本地no2=6消费原成功事实，逻辑提交仅6，原Job UID/外部身份不变；各场景POST一次，四个受控重检后重新capture无新业务。Failed保持非重试终态；Running本地CANCELLED、外部fact/call UNKNOWN、binding SUBMITTED，不代表外部停止或效果撤销。卸载对象READY、1212字节，实际下载SHA d0150502d84a1181a40bb6d26b8332e63c62f54283f056e0d1891fa866fc1d63，独立GET逐字核对一致。

root核41份实际证据SHA、272份后端src/POM不变、本轮10私有PID/TCP无残留及六主服务归属未变；独立审查另核原始PG/唯一对象/进程。启动中两条Flyway schema已存在WARN为非阻塞日志噪声，原日志保留。准备阶段135项Node检查与实际五场景分别记账，不与962后端测试相加。有限K8s HTTP端点加真实JVM/PG/MinIO的通过不代签真实集群/容器/RBAC/IAM、容量或完整G2。

后续按已批准计划先实施G6 Task1的common路由能力/属性语义/生产模式闭锁，再逐步推进可信验签、当前authority、跨服务迁移和持久委托材料范围；本检查点未实现这些后续功能。G2生产启用仍需G6E/F与真实IAM签收。T01仍为唯一完成标记；17功能项/34任务/30组验收未整体签收，原四契约、依赖及不排工期保持，BLOCKED_IAM_SCOPE_ACCEPTANCE不变。

### G6 A–C 公共授权层复审关闭与 D1 接入（2026-10-20）

G6 Task1 基础配置/能力目录及归属修复、Task2 可信 JWT/JWKS 与声明上限三项修复、Task3 当前 authority/受限服务票据/响应生命周期三项修复均已分别获独立 Spec compliance Approved / Task quality Approved。最新同码完整 common 为123项、0失败/错误/跳过（14:06:47）；不是七模块962的重跑或与其相加，新增common尚未打包部署。主六服务仍属962固定制品。

Task3 的 I1 单次响应能力复领、I2 非LIFO上下文复活、I3 批准者固定APPROVER门槛全部Closed。真实行为红、原113/候选121/最终123的日志和XML、完整合并8文件patch及实际apply均保留。root核63源码/两份实际apply/245target、原证据与保护集合；独立复审核四份63源码、两份245target和最终10类XML。见新工程 docs/evidence/iam-stage-3-review-fixes.md、review-iam-stage-3.md最新追加；最终补丁SHA 7c7e3c3f4e51e429c02e8b187ba32255ebb1a1058a8f568f5ba4de5d7e42f2c2。原v1.2/四契约/七模块边界再次核验通过，六业务模块252份src/POM及父POM仍与962相同（D1开始前核验）。

已继续Task4 D1：公共接收守卫、真实method/template覆盖、在线收窄Actor投影及有限随机端口HTTP验证。随后D2独立PG policy resolver、D3网关与六服务迁移、D4门户接入，再推进E持久grant/材料范围；D的真实材料流须与E联合完成，不提前签收Task4。原grant ceiling、producer/selector/同连接manifest、实际首发后accepted与finally关闭、PG同事务scope/metadata-first、冻结confirmRoles（含EDITOR正例及要求APPROVER/仅EDITOR负例）继续作为后续明确门禁。测试预期配置堆栈M1为非阻塞日志维护项，历史日志不改写。

T01仍为唯一完成标记，T09等工作包继续进行中；17项功能/34任务/30组验收、真实IAM/集群及容量均未整体签收。用户确认暂无专用外部环境，继续用隔离端点实现验证；原四契约、依赖和无工期安排保持。IAM_TRUST_CHAIN_UNAVAILABLE与BLOCKED_IAM_SCOPE_ACCEPTANCE继续保持，未因common通过开放生产或半套服务链。

### G6 D1独立闭合并进入D2（2026-10-20）

公共服务接收守卫与监听前实际路由覆盖已独立Spec/Quality Approved（仅D1），common最终145/0/0/0，14:40:20；不是七模块962重跑，也未新打包部署。9文件完整补丁55eea451...实际apply后67源字节一致；root核294target、3115保护、268交付证据及12XML，保留原142/0断言失败/1error和全部具名红。见新工程docs/evidence/iam-stage-4-d1-report.md、review-iam-stage-4-d1.md。

已开始D2独立PG policy resolver/revision和POLICY_MACHINE专用验签与全局guard的实际衔接。D1审查X1–X4明确留后续：gateway ANY代理需D3受限登记、真实业务域资源/版本及父子关系、出站/门户/持久材料和联合流；D2不能仅登记Ingress便宣称混合域可用。CRLF apply1499行警告与旧配置负向堆栈为非阻塞维护项，见open-review-notes.md，原日志不改写。

T01仍唯一完成，T09等工作包继续进行中；完整Task4/G6/G2、17功能/34任务/30验收、真实IAM/集群/容量均未整体签收。原v1.2/四契约/七模块边界再次检查通过，无工期安排，IAM_TRUST_CHAIN_UNAVAILABLE/BLOCKED_IAM_SCOPE_ACCEPTANCE保持。



### G6 D2独立闭合与版本元数据查询准备（2026-10-20）

独立app policy resolver、PG policy_revision及POLICY_MACHINE与全局守卫同mapping接线已获Spec compliance Approved / Task quality Approved（仅D2）。真实ADMIN issue/policy/revoke采用实际发布/凭据metadata资源、原事务revision和审计，回滚/陈旧并发/最终ADMIN收窄均有有限HTTP与专用PG证据。最新完整common150/0/0/0（15:49:36）、app真实HTTP19/0/0/0（15:49:57）；先前直接config/rules与gateway策略回归仍按各自实际轮次记账，不拼成全工程数。见新工程docs/evidence/iam-stage-4-d2-report.md与review-iam-stage-4-d2.md最新追加。

首轮I1等价token编码绕过旧policy版本锚点、root纳入的原token窄上限被PG补宽两项已修复并复审Closed：按已验issuer+jti及原主体/绑定/声明锚定初始版本，角色/许可属性求交、数据标签义务并集；fresh新jti才是新认证。真实PG行为红、原报告/审查前缀与各轮XML/rawlog保留。原D2补丁1577fea7...与返修补丁e805f819...分别真实apply，最终332源字节一致；root核target313/131/54及1610保护，返修仅两个测试文件，生产源未再改。

后续先实施D3a0 published版本metadata组件：精确受限查询Grant与真实版本主Grant分开，复用现C，不新增metadataJWT/receipt；随后真实gateway/six-service接入、E持久grant/完整初始ceiling/材料与联合stream、D4门户和F统一矩阵。原R3尚需gateway→authority→真实app/PG→downstream SecurityFilterChain，真实HTTPS/持久IAM锚点及旧业务统一回归不提前签收。未发布新增G6制品，主服务仍962固定阶段；T01仍唯一完成，T09/T11等进行中，17功能/34任务/30验收与生产/容量未整体通过。原v1.2 SHA3c72、四契约、依赖和不排工期保持；IAM_TRUST_CHAIN_UNAVAILABLE / BLOCKED_IAM_SCOPE_ACCEPTANCE不变。

### G6 D3a0元数据组件与期限测试复审闭合（2026-10-20）

published版本元数据组件及期限测试补强已分别获独立Spec compliant / Task quality Approved（仅D3a0）。复用普通C的受限查询Grant，返回真实app-version后，由同原认证明确再次exchange独立主Grant；不新增metadata JWT/receipt、不升级查询Grant、不向app下传原source。真实app只读指针/版本/SHA，双向用途/tenant/path检查在PG前；integration与DELEGATED首查询保持拒绝。

实际结果分别记账：完整common155/0、app HTTP9/0、gateway客户端原轮10/0、跨JVM launcher1及实际metadata1/payload0；后续只对新增deadline断言跑具名方法1/0，不拼为一次全工程数。受控服务端扣住合法响应最后一字节时客户端已退出，原M1复审Closed。344份源/POM冻结与原补丁、补强一测试补丁均真实apply逐字一致；原报告/审查前缀、真实失败及警告保留。详见代码/agent-platform/docs/evidence/iam-stage-4-d3a0-report.md、review-iam-stage-4-d3a0.md及root审计。

后续先E1a保留同次已验完整initial ceiling、稳定原认证ID及请求frame；再接持久授权/材料scope、run.advance本地受限动作与真实gateway→app→engine已发布FLOW受理/读取/owned调度。实际主payload版本与指针检查、所有status/requestId材料、六服务出站/集成/门户/联合stream及完整D3/E/R3/F/G仍有独立验收，查询组件通过不替代它们。

原brief保护SHA漏采不可追认，8954项保护不含该文件；补强期间21302项未变、唯一root授权proposal追加单列。行尾/预期负例/Surefire classpath诊断作为已知维护项保留，不伪称输出无警告。未新打包部署，主服务仍962固定制品；T01仍唯一完成，T09/T11等进行中，17功能/34任务/30验收未整体通过。v1.2 SHA3c72、四契约、依赖与不排工期保持，IAM_TRUST_CHAIN_UNAVAILABLE / BLOCKED_IAM_SCOPE_ACCEPTANCE不变。

### G6 E1a完整初始授权证明组件闭合（2026-10-20）

E1a已获独立Spec compliant / Task quality Approved，仅限公共授权接缝。收到的同一已验service JWT与同次signed current保留完整initial、稳定issuer+jti原认证锚；current收窄不会反向改initial。私有证明使用同client Clock、最早期限；请求frame按对象身份与当前线程校验，刷新/过期/缺proof/错误绑定永久失效，普通current-only不能伪造初始证明。原HTTP/async/nonLIFO清理保留。

最终一次完整common174项、0失败/错误/跳过，17份XML。原真实红4/4、查询期间到期红1/1、定向中间3断言失败及修正均保留，不与旧962/其它组件相加。root独立核344基线→346源，live/frozen/replay/full-run逐字一致；仅8文件、335 common target，其他1313 target不变；22786原保护文件未变，仅一精确root只读提案新增。详见代码/agent-platform/docs/evidence/iam-stage-5-e1a-report.md、review-iam-stage-5-e1a.md及.local/iam-stage-5-e1a-root-handoff-check.json。

后续E1b：真实PG新run/完整授权/三初始材料同事务，材料metadata-first与同快照读取，唯一local run.advance及原main Grant关联、短事务CAS阻断。普通新认证读取历史材料按当次权限覆盖，后台续跑严格绑定原委托，两者区分。随后仍需真实gateway→app→engine受理/指针/全status与requestId材料/owned调度、全部材料/显式重新授权/CHAT/skill/model/facts/下载回放/RR1/RR2/门户及完整D3–G。

六轮Surefire classpath诊断、39行预期startup ERROR列非阻断维护项，非无噪声声明；未新打包部署、主服务仍962固定制品。T01仍唯一完成，17功能/34任务/30验收未整体通过，v1.2/四契约/七模块HTTP边界及不排工期保持，生产IAM与scope发布闭锁不变。

### G6 E1b持久授权与三初始材料组件闭合（2026-10-20）

E1b及唯一Important I1已获独立Spec compliant / Task quality Approved，仅闭合持久授权/三初始材料/local run.advance组件。真实PG新run与完整initial/origin/main Grant、不可变run_authority、snapshot/inputs/version_refs三份scope同事务；同DataSource回滚位防外层catch留下半成run。材料先metadata、真实REPEATABLE_READ同快照、精确JSON数值与原始floor/parent绑定；当次新认证可按实际权限读历史材料，后台续跑则严格原委托，二者不混用。

原最后完整common179/0/0/0，原最后engine三类35/0/0/0；修复后只改engine四文件，受影响current全类12/0/0/0。原common精确77项源不变；旧35与新12分别对应各自冻结源，不相加成47项，也不称最终全356同码双跑。I1以真实PG未改版本行锁与实际commit后推进Clock复现两个行为红2/2F，再绿12：UPDATE后事务内验同proof，真实commit返回后交付VA前再验；已提交检查保留历史事实，旧Binding失败CAS不覆盖。

root独立核最终356源live/frozen/replay/green逐字一致，1695 target、1132非engine target不变、906 common+engine物理冻结与311 compiled；36384实际采样保护文件不变。初采漏含两个.local父制品路径已披露，另核903旧target备份及由原完整输出确定性重建的旧audit；不冒称有直接事前audit SHA采样。原review15557字节前缀不动，复审追加后SHA565196377a0ee09022efc5314f5c7804c284caaa1315a051e3deabb2caee6194。见代码/agent-platform/docs/evidence/iam-stage-5-e1b-report.md、iam-stage-5-e1b-i1-fix-report.md、review-iam-stage-5-e1b.md及.local/iam-e1b-i1-fix-root-check.json。

下一D3b仅实现真实AppController→AppService已发布payload授权读取：实际PG窄metadata/指针、事务外刷新、短锁内重读与payload、提交后终检，独立HTTP+PG验证。后续仍需真实gateway可信realm与metadata→main、engine实际受理和owned调度接线、全status/requestId/TraceStore及全部材料/显式重新授权/CHAT/skill/model/facts/下载回放/RR1/RR2/门户/完整D3–G；E1b不代替全Task5或G2生产许可。

原归档夹带XML、attempt01字节重放失败/最终13行CRLF提示、setup/stub与数值断言噪声、39条预期startup ERROR及Surefire Windows诊断均原样留档，维护项不改写成无噪声。未新打包部署，主服务仍962固定制品；仅T01完成，17功能/34任务/30验收未整体通过，原v1.2/四契约/固定版本/七模块HTTP边界与生产IAM闭锁保持，不排工期。

### G6 D3b已发布快照授权读取组件闭合（2026-10-20）

D3b独立Spec compliant / Task quality Approved，仅闭合实际AppController→AppService的GET /internal/apps/{code}/published组件。IAM先按真实PG窄metadata核tenant/code/current pointer/数字versionId/no/stored candidate SHA；事务外刷新，短READ_COMMITTED内先application FOR SHARE、锁后独立语句重读，再读精确snapshot；payload后、回调结束前及真实commit返回后均核同frame/proof期限。旧票切版409、错root拒绝，显式legacy分支保留，未开放其它合法action或全局IAM。

最终同357源码的受影响payload全类24/0F/0E/0skip；原受影响整组33包含metadata9，唯一受改child的跨JVMlauncher1均保持各自来源。自审只补新metadata(pointer2)→新main恢复的payload测试，旧33/1与最终仅该测试文件不同，其余356逐项SHA一致；不加总、不开称同源双跑，legacy测试仅controller分支不算完整legacy部署。

root核baseline356源/1695 target物理副本、最终357 live/frozen/replay/最后payload轮、1710 live/frozen target一致；仅7源文件（2生产/5测试）、33 target变化仅app/gateway且删0；七轮actual Running/raw/XML/source均核，incidental0。原135304项采样after exit1保留，root另一次读原件核135302静态相等，精确root state与PG日志两项授权变化；原PG日志和after样本前缀均保持，原JAR EBUSY后窄读及root原件读均相等。8执行文档在收口前不变。见代码/agent-platform/docs/evidence/iam-stage-4-d3b-report.md、review-iam-stage-4-d3b.md、.local/iam-stage-4-d3b-root-fast-check.json及root-protected-check.json。

保留Minor：锁等待测试的beforeSql latch先于真正SQL发送，单次pg_stat_activity存在偶发假阴性窗口，后续有限观测等待改进；本轮实际Lock/blocker严格成功，不倒跑绿色轮。344字节Windows classpath诊断及采样/编译装配/异常类型断言/helper/root提前读重冻目录等噪声均原样记录，不伪称原始扫描无差异或无噪声。

下一按已核接缝推进N1有限材料及原status组件→N2原owned worker→N3原RunService/app client受理→N4实际gateway信任装配与首门户HTTP链。V216仅三初始材料；扩展须新连续迁移并核真实源/PG历史，不占高号。数字versionId由D3b可信app PG/HTTP返回，engine对main根核code@no/SHA，不从请求补ID或加receipt。GET监控/全status动作、CHAT/skill/model/facts/全部材料/批准/下载回放/门户/RR1/RR2及完整Task4/5/6仍待。

T01仍唯一完成，17功能/34任务/30验收未整体通过；原v1.2/四契约/固定版本/七模块HTTP边界不变，不排工期。未新打包部署、主服务仍962固定制品；生产IAM与BLOCKED_IAM_SCOPE_ACCEPTANCE继续闭锁，隔离组件通过不替代真实IAM签收。

### G6 N1有限FLOW材料与原status组件闭合（2026-10-20）

N1独立Spec compliant / Task quality Approved，无Critical/Important，仅闭合真实engine材料写读与原RunService.status组合组件。原三initial的SHA64/owner/full-initial/grant/origin保留，新增固定run/node/attempt/event locator与追加revision，派生继承initial floor和实际父scope义务并集。公开VA-only读签名保留但受保护读取默认拒绝，真实读取使用私有Retained；新main可按当前owner/root/scope读取旧grant过期或后台blocked的历史材料，后台推进仍严格原委托和持久current CAS。

实际TraceStore claim/commit/fail/event/consume使用同DS顶层RR，run→authority observation→node锁序，payload/scope/原CAS/事件同事务，真实commit返回后终检；初始化只拥有真实新run/node INSERT并加入原受理Tx，外层实际commit终检仍由N3负责。原status先窄owner/root/purpose与scope metadata、事务外刷新、一个只读RR完整读取；14份payload同物理连接，nullable scope缺失不当空值，SQL NULL/JSON-null原展示差异保留，纯lease续约不破坏事实digest。

最终单轮实际6 Running/6 XML共69/0F/0E/0skip：N1三类11+11+10，确受影响原E1b三类12+12+13；57 incidental XML未算执行，不与历史common/engine测试相加。root逐项核13轮raw/Running/XML/source及最终362 live/frozen/replay/final-source，before357源/1710 target物理副本、最终1740 target一致；14源变化、51 target变化实际仅engine-runtime，业务JAR变化0。另一次核65381静态+28runner+8执行文档共4574232441字节0漂移，原详设/四契约/正式brief不变。

真实连续V217已在agent_platform_iam_flow_material_v12及原E1b agent_platform_iam_engine_v12隔离库验证，主库未迁移。G2旧engine217候选让位，后续218仍须实际源/目标历史重核才分配；skill312/G2skill313仍仅候选。证据：代码/agent-platform/docs/evidence/iam-stage-5-n1-flow-material-status-report.md、review-iam-stage-5-n1.md、.local/iam-n1-root-fast-check.json、root-protected-check.json及root-audit-addendum.json。

维护项如实保留：新增Status测试unchecked编译提示、原RunAuthorityStore deprecation/ContextProjection unchecked提示；red02错误父断言、red07已关闭backend观测噪声、red05尾计数未到达与preflight事后整源归档适用性限制、root首轮inventory漏根pom的工具噪声均不倒写。最终69源码/执行证据无上述适用性歧义，不为消噪重跑绿色轮。

下一N2原owned scheduler/FlowDriver真实推进，再N3原RunService/app client受理、N4真实gateway首门户HTTP链。N2组合prepare/DEFAULT_VALUE/budget/aggregate须由实际事务拥有者复核同proof，不简单嵌套N1顶层writer；纯计算之外的CHAT/模型/技能/审批/恢复/历史/下载回放/流与RR1/RR2仍待。N1 fixture不替代真实受理/owned worker，独立GET/debug/v1未由此开放。

T01仍唯一整体完成项，17功能/34任务/30验收未整体通过；v1.2/四契约/固定版本/七模块HTTP边界不变，不排工期。源码未新package/deploy，主服务仍962固定制品；生产IAM及BLOCKED_IAM_SCOPE_ACCEPTANCE继续闭锁，隔离组件通过不替代真实IAM签收。

### G6 N2原owned纯FLOW推进与I1修复闭合（2026-10-20）

N2同一独立reviewer复审Spec compliant / Task quality Approved，I1关闭，无Critical/Important。原RunScheduler reserve/persist/created/poll→owned dispatch→RunService→FlowDriver→真实PG节点/attempt/event/output/aggregate已闭合为隔离纯FLOW组件；只FULL/TASK/published、明确type=FLOW、START/END/CODE/CONDITION。N1 fixture是真实受理存储组件，尚未经过原RunController/createInternal、RuntimeClients或gateway；N3/N4仍为下一工作。

调度只领取dispatch，当前授权通过后才开始；每个新节点/聚合在事务外新current，组合写由TraceStore顶层RR拥有，run→authority→node锁序及锁等待后真实clock_timestamp期限核验保持。实际消费材料/attempt父义务、ANY独占与共享分支、预算预检保留prepare、DEFAULT_VALUE与失败/聚合同事务写入、SQL NULL/JSON null、末节点提交后新current仅聚合不重复节点均有真实组件验证；短proof到期与有效原grant、真实403/503/收窄永久拒绝分开。

原最终67是I1修复前历史代码。评审发现旧gate只排CHAT，真实红四独立例4/3F/0E：missing/null/UNKNOWN错误完成，CHAT原已拒绝。生产仅一行正向FLOW修复后，当前最终仅两直接受影响N2类25/0F/0E/0skip（Materials15、Scheduler10），63 incidental XML不计；四类均PENDING、开始/attempt/真实execute/output0，授权读快照payload增15，正常FLOW原计算执行2次且输出正确。不重复旧67、不把两轮相加。

当前364源码/1764目标，经root实核before364/1761物理副本、live/frozen/replay/final源码、raw/Running/XML及逐test WARN映射；修复仅2源变化，目标3新增+8修改+0删=11路径，全engine、JAR0。原N2目标46仅非删除路径，另1旧StaleCheck.class删除，总47；删除具体机制/时点未记录，不冒称自动清理已证。原完整patch与修复patch均真实apply/逐字重放。

root另核84553静态（75091复用基线+9462补采）+28脚本+8索引，共6103912586B：84552静态不变，唯一授权原报告追加且15189B前缀保持，0越权/未验证。初审13504B原前缀与旧handoff/历史轮保持；复审只追加，现SHA2082ee6bf56ac988942b529046f2f5443674badac085b66b0977125d3ad5ff1a。旧归档stem错误4421文件原字节迁回、真实失败与root审计假设修正原件均保留，不改before遮差异。

M1/M2作为非阻断维护项登记：最终15个预期负例WARN尚未逐条测试捕获断言；另两份独立344B Surefire Manifest-JAR路径诊断原样保留，不声称pristine，不应用其禁检argLine提示或为消噪重跑。原N2连续V217隔离库证据保留，修复只复用owned_flow库、不重跑history预检/不占新迁移；G2下一engine218、skill312/G2skill313仍仅候选。

证据：代码/agent-platform/docs/evidence/iam-stage-5-n2-owned-flow-report.md（保留原前缀并追加修复）、review-iam-stage-5-n2.md、iam-stage-5-n2-owned-flow-fix/handoff.json；root实际核验见.local/iam-n2-root-*.json、iam-n2-fix-root-fast-check.json、root-protected-check.json和fix-post-review-check.json。固定上游6原件SHA与实际读源方法/段落已核，root未冒称重读完整上游。

下一N3原RunController/RunService受理、固定app published客户端、三处requestId锁外单视图重放及真实外层commit后检查，再N4实际gateway首门户HTTP链。全恢复域ExecutionRecovery/Approval/debug、独立GETstatus/SSE、CHAT/工具/模型/批准/Observer/RR1/RR2/门户浏览器、真实IAM/本体/K8s/容量仍未验收。T01仍唯一整体完成，17功能/34任务/30验收未整体通过；v1.2/四契约/版本/七模块HTTP边界不变，不排工期，未新package/deploy，主服务仍962制品，生产IAM闭锁保持。

### G6 N3原受理与已发布快照HTTP客户端闭合（2026-10-20）

N3及I1/I2修复经同一独立评审者复审：Spec compliant / Task quality Approved，Critical0、Important0。本次验收原RunController→RunService→RuntimeClients→独立app实际HTTP/PG→真正受理事务→原owned worker→完整status；仅TASK/FULL、当前已发布、明确FLOW及START/END/CODE/CONDITION。main票据来自隔离签名fixture，尚未经过真实GatewayController；不计全Task5或普通全局IAM完成。

应用发布快照绑定code/versionNo/可信app-version根/存储candidate摘要/真实PG数字ID与别名。三处requestId重放均在锁外读取N1同一RR完整视图；环境事务在任何授权/app IO前拒绝，真实最外层commit返回后再检验。所有依赖及不支持节点在legacy preparation前默认拒绝，空容器/null合法结构保留；同事务run/authority/node/15项初始scope和原容量、预算、输入/DAG校验保留。

I1关闭提交后metadata SELECT等待产生的调度窗口：created紧前重检同bound proof，过期请求0次nudge、负例检查时1run/2node/15scope、PENDING/attempt0、容量释放，之后新合法main/poll恢复成功。I2以每次独立HttpClientContext的私有Retained与同一2秒单调deadline，在实际Apache连接后request interceptor、发送前复核；真实队列过期0 GET/403，异步授权异常保持403，caller bound/取消/关闭保持。该检查不承诺撤回已发wire字节，未做TLS等待真实探针。

当前单次fix-final01只执行两受影响类：PublishedAdmissionHttpPostgresTest20、RuntimeClientsPublishedHttpTest20，合40/0F/0E/0skip；红fix-red01真实2/2F/0E，旧76 XML不计执行。原N3 final01 59/0F/1E、final02 client19/0F/1E及final03 client19/0保留，不能写成同次59全绿。原status11/scheduler10相关逻辑未变，按范围不重复；不把新旧轮相加。

当前367源码/1798目标，I1/I2仅4源修改、target新增3/修改10/删除0、业务JAR0；完整before/live/frozen/replay及真实patch apply逐字一致。root核101048静态（90336已核基底+10712补采）/7634684209B、28脚本、8最新索引，总7635135657B，0漂移/未核；原报告及初审前缀保持，真实失败和工具错误原件保留。两app child及23已记录端点无残留，不冒称client未记录端口逐项OS核过。

报告勘误：fix-report I1“green raw末尾attempt1”应为实际final01 metadata-expiry case attempts2/scopes52/createdCalls0，是有意合法恢复后的末态；负例检查时attempt0仍由实际断言与绿结果支持。旧JDK内部GET自动重发已由真实红证实，获批engine单一POM例外使用固定Apache httpclient5 5.4.4/core5 5.3.4，按实例禁retry/redirect/auth挑战/压缩，1秒connect/2秒全体deadline/4MiB；未改common或root POM、未升级依赖。

证据：代码/agent-platform/docs/evidence/iam-stage-5-n3-admission-app-client-report.md、独立-fix-report.md、review-iam-stage-5-n3.md及-fix/handoff.json；root审计.local/iam-n3-root-*、iam-n3-fix-root-fast-check.json、iam-n3-fix-root-protected-check.json。开源固定源/许可/SHA与实际方法链已核；root核11份文件，不冒称重读完整上游。unchecked、负例worker/55P03/servlet ERROR与15份344B路径诊断按历史阶段保留，非pristine。

下一N4a先统一可信Spring部署模式与旧本地签发闭锁，再N4b将真实GatewayController的两个ANY入口登记与实际运行守卫、默认拒绝其它入口及响应前期限检查一起落实。门户浏览器/独立GETstatus/SSE/CHAT/工具模型/批准/全恢复/Observer/RR1/RR2/真实IAM本体K8s/容量仍待。T01仍唯一整体完成，17功能/34任务/30验收未整体签收；v1.2/四契约/固定版本/七模块HTTP边界不变、不排工期。未新package/deploy，主服务仍962制品，生产IAM_TRUST_CHAIN_UNAVAILABLE保持。

### G6 N4a可信部署模式与旧签发收口（2026-10-21）

N4a独立fresh review：Spec compliant / Task quality Approved，Critical0/Important0/Minor0。本组件只统一可信Spring部署快照与ServiceTokens旧签发行为，未验收真实GatewayController、完整IAM或全Task5。SecurityConfiguration.bindDeployment(Environment)是唯一严格绑定/validateDeployment登记入口；完整配置在合法IAM仍原IAM_TRUST_CHAIN_UNAVAILABLE、listener factory0。

无注册的issue即使JVM=test/profile=test也拒绝；合法dev-hmac/test快照不受后续JVM参数或context关闭影响。任一合法IAM登记在原启动异常前不可逆关闭共同private sign，issue/userToken/integrationToken三API均拒绝，再启合法legacy亦拒绝。同Class monitor覆盖登记、issue许可与签名，确定性跨线程验证可见性；只同classloader，不撤回已出票/关闭旧listener。未登记user/integration保留既有非IAM兼容，secretBytes和原JWT claims/TTL/key域不变。

真实red01 1/1F/0E：高优先级实际Spring IAM覆盖JVM test，原配置关闭/factory0后旧issue仍签成功。final01 common53=48pass/5F/0E、engine未执行；四child短tmp缺失和一fixture Bean同名只修测试装配。final02仅续5失败common+首次engine20=25/0F/0E/0skip，原48绿未重跑。73个不同用例有分批有效证据，非同次73全绿；生产两文件两绿轮完全相同，小fixture patch保留无断言弱化。

当前source368（+1/~5/-0）、target1806（+8/~23/-0）；仅SecurityConfiguration/ServiceTokens两生产类，新增mode测试和原正向场景独立child隔离，原全部断言保留。唯一JAR变化是common测试fixture JAR224108→236449B，生产JAR0。before/live/frozen/replay完整物理SHA与真实apply一致；root另核109089静态/8718696454B、28runner、8索引，总8719171811B，0越权/未核/未登记新增。101048旧基底仅此前获批review+5内部索引更新，外部3另核过渡，原详设不变。

证据：代码/agent-platform/docs/evidence/iam-stage-5-n4a-deployment-mode-report.md、iam-stage-5-n4a-deployment-mode/handoff.json、review-iam-stage-5-n4a.md；root核.local/iam-n4a-root-fast-check.json与root-protected-check.json。固定Context6.2.7/Boot3.5.0/JDK21参考/JLS21监视器机制及许可/实际方法段落已归档；root核8文件和3来源manifest/archive物理SHA，不冒称完整上游重读或参考JDK等于实际Temurin21.0.12+8。

保留三轮4份344B Windows Surefire路径诊断及unchecked；无禁检argLine/POM/runner变更。作者XML正则/setup/路径失败、root旧fixture JAR历史descriptor和红前测试差异假设/续作helper错误均保留，纠正仅续未完成阶段，无业务重跑。一次自动策略拒绝证据脚本PowerShell写入（blocked by policy）未执行，后apply_patch安全创建再单独node运行；不冒称保留完整截断前payload，未申请升级权限。

下一N4b1从同一main exchange私验保留不可公构造的query/ticket/真实grant/source期限证明，再N4b2将实际gateway两个ANY登记、真实默认拒绝守卫/HTTP链与响应写前期限检查一起实现。现有IAM字段足够，不新wire/profile、不resume重建main、不重放POST；普通Ingress wildcard禁令和app/engine守卫不动。common现JDK传输的内部retry/close仍须后续具名核，不据局部证据称整链零重试/物理socket硬截止。

T01仍唯一整体完成；17功能/34任务/30验收未整体签收。独立GETstatus/SSE、CHAT/工具模型/批准/全恢复/Observer/RR1/RR2/门户浏览器/真实IAM本体K8s/容量仍待；v1.2/四契约/固定版本/七模块HTTP边界保持。不排工期、不package/deploy，主六服务仍962制品。

### G6 N4b1主exchange私有期限证明（2026-10-21）

N4b1组件已独立Spec compliant / Task Quality Approved，C0/I0/M0。仅common两生产文件：默认拒绝的有限gateway已发布run API，以同一次真实Portal/query/ticket私验生成不可公构造证明；保留真实current/ticket/operation/grant/source最小期限、源入口单调预算、失败永久失效和owner close边界。旧exchange/receiver/恢复/response语义保持；没有新增wire、依赖或生产IAM入口。

实际setup编译失败Running0；已编译原型red29/4F/0E，修复后唯一最终两类20+29=49/0F/0E/0skip。原型红不是未改生产漏洞，不把29红+49最终记作78个唯一绿色。源码369(+1/~3/-0)，target1814(+8/~12/-0)，仅common测试fixture JAR变化、生产JAR0。root核before/frozen/live/replay、6260证据/10工具、117539静态+28runner+8索引以及固定source archive entry；已纠正metadata/CRLF等工具问题原记录保留。证据及边界见代码/agent-platform/docs/evidence/iam-stage-5-n4b1-gateway-run-authorization-report.md与review-iam-stage-5-n4b1.md。

后续N4b2依赖N3/I1/I2、N4a和N4b1：先common实际gateway守卫/两个ANY严格登记及真正installed安全链；再gateway有限异步读取→metadata查询→同源独立main exchange→固定engine POST→完整缓冲/实际写前期限检查；最后实际GatewayController/RunController/AppController+隔离PG/owned worker联合验收。验收包含重复requestId单run/无额外执行、发布指针变更/停用、错误根/票据/权限、成功字节写前过期、超限/慢端/断连/关闭/late callback与真实worker退出。缺proxy503只是防御，不独立当链路完成；普通Ingress wildcard禁令和app/engine守卫不变。固定gateway Apache5.4.4是下一受限例外，common JDK retry/close缺口继续具名待办。

本组件不验收实际gateway JSON边界或全N4b/Task5；门户浏览器、独立GETstatus、SSE/RR1/RR2、CHAT/模型工具/批准恢复、真实IAM/本体/K8s/容量仍待。决定窗口<=5秒不是即时撤权SLA，已发字节不可撤回，Future取消不等同物理socket或worker结束。T01仍唯一整体完成，T09/T11/T12继续进行中，17功能/34任务/30验收未整体签收；v1.2/四契约/固定版本/七模块HTTP与不排工期保持。主服务仍962固定制品，未新package/deploy，生产IAM_TRUST_CHAIN_UNAVAILABLE不变。

### G6 N4b2实际门户创建链与异步安装闭环（2026-10-21）

N4b2原独立审查发现I1关闭／AsyncContext安装竞态及M1测试名称失实；三文件修复后，原reviewer对spec与quality复审通过，原初审保持不改。证据：代码/agent-platform/docs/evidence/iam-stage-5-n4b2-gateway-published-chain-report.md、iam-stage-5-n4b2-close-install-fix-report.md、review-iam-stage-5-n4b2-close-install-fix.md。只签收有限门户POST已发布run链，不签收全Task4/5或生产IAM。

实际GatewayController、两个ANY严格登记／真实FilterChainProxy与servlet注册证明、默认拒绝、私有Portal proof、MVC非阻塞读写、metadata与独立main授权、固定engine POST及真实RunController/AppController＋隔离PG/owned worker已贯通。正例一run/两attempt/52材料/output a=7；同requestId重放仍同run；同origin有四个distinct grants（含重放的两query＋两main）。指针变更/停用、错票据/权限/缺scope、写前过期、JSON/大小/分块trailer和长行、断连/关闭均有具名证据。

原28轮的48个当前唯一用例按最后有效绿与源码适用性登记，不是一次最终48绿；修复新增两窗，当前四类源码计3＋30＋7＋10=50个@Test，也不称一次50绿。修复red01前窗真实完成红，red02后窗真实close1s阻塞，green03三条有效绿＋green04两窗最终绿；中间两oracle errors保留。迟到实际async直接complete，不复活状态；未归属async失败只安全status＋terminal，允许安全空503体；close无需等闩锁、实际onComplete一次/timeout零，资源与worker分别核实。

原B2 source369→377、target1814→1879，修复仅三源修改、target1888（新增9/修改21/删除0）；生产JAR零变化。root原source/新16347证据/XML/target与保护谱系审计通过；123837文件完整物理核由原作者实际执行，root未重复十余GB扫描。修复root另核2435个物理文件，包括完整当前source/target、overlay/replay、新112证据/五XML、28runner/8索引/原设计/13锚点，零差异；见.local/iam-n4b2-root-*-check.json与.local/iam-n4b2-fix-root-check.json。实际root cwd apply与重建一致，未改历史失败或伪造事前捕获。

下一D4a先实现GET /auth/session：真实门户exchange＋同ticket当前authorize、私有四字段白名单投影及实际handler安装；再D4前端provider/会话generation/取消旧请求、到期和迟到401隔离及真实浏览器。其后独立GETstatus、SSE/RR1/RR2、CHAT/模型技能/批准/全恢复和F联合链仍待。T09/T11/T12保持进行中、T01唯一整体完成，17功能/34任务/30验收未整体签收；原v1.2/四契约/固定版本/七模块HTTP不变，不排工期。未新package/deploy，主服务仍旧962制品，生产IAM_TRUST_CHAIN_UNAVAILABLE保持。

保留边界：5000ms从proxy入口起算、不含先前JWT验证；已发字节不能撤回；慢reader实际73728成功bytes且fixture5500ms主动关闭，非恶意peer物理硬断保证；proxy16历史app PID/port/exit未记录；common BoundedIamHttp内部retry/close仍待具名核，不能称wholechain零重试或全部socket硬取消。诊断、setup/工具错误和来源CRLF差异保持原件。

### G6 D4a门户会话与签名时间区间闭环（2026-10-21）

D4a有限后端及I1修复已由独立评审通过：GET /auth/session 使用真实门户exchange、同ticket在线authorize、私有不可伪造投影及实际handler/过滤链安装验证。成功只返回tenantId/principalId/roles/expiresAt，角色可空；输出到期是原Portal与真实grant较早者，当前授权短窗另行控制写出，不缓存为后续动作许可。入口拒绝query/body/Cookie/API key/重复凭据与身份别名，401/403/503分别有具名证据。控制器同步序列化并在实际首写前复核；已提交字节不能撤回，不承诺恶意慢peer物理硬断。

初审I1为signed elapsed session路径漏检exp>nbf，原错误表现403，未发现成功proof泄出。修复仅AuthorityResponseVerifier与PortalSessionAuthorizationTest两源；四种current/ticket×nbf==exp/>exp实际红403→绿503，同时重验合法已过期决策403及原普通/pinned verifier503。最终定向3条绿；现有common17＋gateway8=25个当前唯一用例是多阶段适用清单，不是一次最终25条全绿。原20命令/21 XML及修复两命令/两XML、失败和初审均保持原件。

签收依据：代码/agent-platform/docs/evidence/iam-stage-4-d4a-portal-session-report.md、iam-stage-4-d4a-time-order-fix-report.md、review-iam-stage-4-d4a-time-order-fix.md；root原审计2982个不同物理文件及修复审计2432项通过（其中404项为首脚本元数据字段假设失败前已核source前缀，后续据实重建记录，仅继续未完成部分）。source377→380；修复仍380、仅两源修改；target最终1933，修复新增2/修改5，生产JAR零变化；root cwd真实patch check/apply及380份完整逻辑重放一致。历史保护复用已封存谱系，未再次扫描十余GB旧证据。工具字段错误、编译诊断及21＋2份分阶段Surefire路径提示保留，不禁检查、不重跑已绿消噪。

legacy边界已核Optional构造无硬IAM依赖、有限实例装配和默认扫描结构；本轮没有执行完整GatewayApplication legacy启动。真实隔离Gateway/Tomcat/security/签名authority仅验证本有限接口，没有访问主数据库、模型、本体或集群；自有fixture资源已收束。生产IAM保持关闭、主服务仍旧962制品，本轮不package/deploy。下一D4b前端provider、generation隔离、Cookie省略、到期/迟到响应与浏览器验证；独立GETstatus/SSE、CHAT/批准/全恢复、Task5其余/F及真实IAM仍待。T09/T11/T12继续进行中，T01唯一整体完成，17功能/34任务/30验收未整体签收，v1.2/四契约不变、不排工期。

### G6 D4b前端门户及四项修复闭环（2026-10-21）

D4b有限前端门户和I1–I4两波修复已独立复审通过（Spec compliant / Task quality Approved）。默认portal，由可信宿主provider提供token并通过/auth/session投影；显式dev入口独立选择。身份generation与中止信号隔离旧请求，工作区按身份重建，实际业务/SSE/附件请求省略环境Cookie。当前附件401统一失效，旧scope的迟到401不清新身份，403保留身份并给出简明提示；流reader清理失败不覆盖原授权/中止/解析/网络结果。退出、401和到期暂停并退订宿主通知，旧epoch/同步清理通知不能恢复身份，仅手动重连恢复单订阅；正常活跃宿主切换仍可切换身份。会话寿命取安装时固定的单调上限与wall剩余量较早者。

证据按阶段使用：原实现57条定向绿，随后状态403修复2绿、手动连接/局部补验8绿，不能拼成一次最终67条套件。原浏览器最后有效用例名32个（含1个仅视觉用例），分多次执行，并保留设置失败/局部失败及修复；12次实际Bearer请求及环境Cookie阳性对照验证credentials省略，但不冒充真实D4a签名后端的联合浏览器链。I1/I2新增12条中8红/4原已绿，修复后17条定向绿（含5控制项），3个实际组件浏览器场景绿；I3/I4阶段5红/2原已绿→10条定向绿，另2门禁绿，3个真实壳浏览器场景绿，不相加为一次最终套件。各波typecheck与admin/webapp/SDK构建、SDK分发一致性均有独立实际记录；I3/I4的SDK恰与上一波同字节，仍来自本波实际构建。

签收依据：代码/agent-platform/docs/evidence/iam-stage-4-d4b-portal-frontend-report.md、iam-stage-4-d4b-error-stop-fix-report.md、iam-stage-4-d4b-lifecycle-fix-report.md、review-iam-stage-4-d4b-combined-fixes.md。原两份问题评审、所有失败与908/163/133叶各波封存原件保留。root三份独立有限审计分别核1136/392/366个不同物理文件；每波内去重，不跨波相加为唯一文件总数。原审计因browser1缺少已记录的强杀后raw/end文件而中止，续核只完成剩余部分并保留真实中间记录；未重复运行作者已绿测试，未重扫旧十余GB谱系。

必要source后端380继承不改、前端188→192，总568→572；宽清单622包括50份旧浏览器日志，不作为必要源/新测试。后端target1933继承且生产JAR零改，最终frontend dist15；最后生命周期波dist新增5/修改2/删除5。各波真实root cwd patch check/apply与192份前端及完整572份逻辑源逐项一致。入口desktop/390px窄屏可读；现有工作区仍保留1366px最小宽度及横向滚动，不宣称全工作区移动适配。退出不取消后台运行。

M1：admin/webapp仍有>500kB chunk警告，保留原始输出，后续先实测首屏再决定拆包；未提高阈值消噪。未验证真实IAM/模型/本体/K8s，未执行新的dev-env完整进程启动；浏览器dev分支来自隔离harness替换。单调上限防安装后回拨延期，不证明初始客户端时钟，也不提供冻结标签页即时清除SLA。主服务仍旧962制品，本轮不package/deploy。下一O1a真实PG运行观察metadata→O1b engine只读status→O1c gateway联合GET→O2 PORTAL SSE；RR1/RR2、CHAT/批准/全恢复、Task5其余/F及真实IAM仍待。T09/T11/T12进行中，T01唯一整体完成，17功能/34任务/30验收未整体签收；v1.2/四契约不变、不排工期。本段更新前文历史“下一D4b”的当前状态。

### G6 O1a独立运行元数据接缝验收（2026-10-21）

O1a已独立复审Spec compliant / Task quality Approved，无Critical/Important。只新增内部GET /internal/runs/{id}/observation-metadata：真实签名query经engine HTTP/security/handler，从已验主体及真实public_id绑定selector；入口双向目的校验在在线授权前拒绝错配。服务先拒绝ambient事务再在线刷新，顶层只读RR同连接核owner/原app根/版本/initial与snapshot摘要及三根manifest，精确七字段data/正long/UTF8≤4096，返回前本地proof终检。无业务payload读取、无写副作用，不开放外部status/events。

实际四轮：red01单例1F为目的错配仍触发在线authorize；green01共18例含2条既有JWT401的oracle误写；green02同轮20例绿；green03仅engine16例绿。最后适用common7/green02与engine16/green03，非一次23全绿。成功data485B、三条SELECT同连接RR/readOnly，request前后runs50→50且nodes/attempts/events/tools不增；raw bare?实际400，manifest及commit后失效均403。真实PG非法hash/root_kind拒绝；绕过immutable后自洽合法resource_digest替换200/payload0是已披露信任边界，内容关系由O1b读后核。

报告及独立评审：代码/agent-platform/docs/evidence/iam-stage-4-o1a-run-metadata-report.md、review-iam-stage-4-o1a-run-metadata.md。root有限审计641个不同物理文件通过，新206证据叶6061385B；必要source572→578（后端386/前端192，6增4改），target1933→1948（15增17改、32overlay，作者仅核382实际候选），生产JAR未变。首次文本patch应用成功但三处混合EOL不等；修复check曾失败且未apply，后续私有目录binary修复0/0，完整逻辑578 names/bytes/SHA一致。首红573事前清单未改，新增common测试实际在子进程退出16759ms后；所有错误、旧未执行XML和原时间更正均保留，不重跑已绿消噪。

遗留M1测试static HttpClient随JVM退出但未显式close，作者“各client关闭”表述按此限制；M2 SQL投影断言含宽.*，实际当前SQL纯控制字段，后续应增强防回归；M3既有unchecked/Flyway提示及诊断中文编码损失保留。四轮自有测试PID/端口已关闭，独占PG留存。有限legacy装配不等于完整Boot启动；service返回前检查不等于首次wire写出或瞬时撤权。没有真实IAM/模型/本体/K8s联调，没有package/deploy，主服务仍旧962制品。

下一O1b engine独立只读status→O1c gateway联合GET→O2 PORTAL SSE；RR1/RR2、CHAT/批准/全恢复、Task5其余/F和真实IAM继续待。T09/T11/T12进行中，T01唯一整体完成，17功能/34任务/30验收未整体签收；v1.2/四契约不改、不排工期。本段更新前文历史“下一O1a”的当前状态。

### G6 O1b独立只读状态及审查修复验收（2026-10-21）

O1b修复后已独立复审Spec compliant / Task quality Approved，Critical0/Important0。仅开放engine IAM精确GET /admin/runs/{id}：同originalPortal的真实Q metadata后独立fresh M，run-snapshot/publicId/acceptedSHA双向目的；新观察许可不续原执行grant。先拒ambient再事务外refresh，单readOnly RR内核真实owner、历史根与三manifest、全scope/locator/当前覆盖，全部先验后才payload；读后核snapshot/refs摘要及candidate/app/version关系，纯FLOW规则、同RR固定组装保留原完整status。private constructor proof绑定同retained/frame/thread；commit、compose、serialize和实际getOutputStream取得后均终检，首次write紧邻最终检查。

完整ApiResponse UTF8上限仅用于新IAM GET：2,097,152字节恰上限成功，多1字节403/RUN_STATUS_TOO_LARGE，非authority错误，不清blocked、不截断、不重试。独立审查曾发现原BAOS只限count：实际count2,056,000时数组4,096,000；已改私有固定预分配2MiB加原countguard，以标准writeTo观测真实数组，三种批量/单字节/混合写法均2,097,152。小响应也预占2MiB，toByteArray另最多2MiB副本，材料/Jackson/整个请求堆不因此有界。IO部分提交保留primary/suppressed，实际peer128业务字节后EOF、不完整JSON；MVC拒绝delegate close，日志actual unknown，不承诺物理硬断或阻塞write内连续撤权。

原九轮原件保留；原final04新17只是历史。首轮review Needs fixes的另一Important是共同FLOW夹具source/target不符合真实from/to；现已更正，真实legacy reserved受理前prepare/input/DAG到atomic sentinel，SQL/provider/scheduler动作0，明确不是完整public IAM受理链。窄修先真实2F0E（容量与实际RunService:502），后仅受影响新类一次19/0/0/0；原common3、GraphRules1、原status2、O1a4沿其原final01命令且相关接缝未变，非合成29例或原27例套件。新forward夹具完成后结束原dispatch，新raw无Stream closed。原green01误归档red02 XML，已排除并补充说明；green01/green02均无有效该轮XML但raw保留，不重跑补旧档，不将size旧测量改称capacity。

封存：必要source仍579（后端387/前端192），相对O1a1增9改，修复再2文件；target1948→1968→1972，原delta52/actual候选383，窄修delta16含4增/actual候选234，生产JAR未变。原新338证据叶16863563B、修复86叶3073742B；root原799项与窄修122项分别有限审计PASS，继承旧大库存、不重扫。两次独立private replay均首次check/apply0/0并完整逻辑579 names/bytes/SHA一致。新两自有PID39240/35240和engine端口53211/56474已关闭，PG专库保留。所有最初失败、错XML、oracle演进、helper解释修正与日志噪声留存。

报告/复审：代码/agent-platform/docs/evidence/iam-stage-4-o1b-readonly-status-report.md、iam-stage-4-o1b-review-fixes-report.md、review-iam-stage-4-o1b-readonly-status.md、review-iam-stage-4-o1b-review-fixes.md。M1证据解释/M2转发生命周期已处理；剩M3弃用/unchecked/编码、M4逐项post-digest关系分支覆盖、M5未触及TraceStore/RunService可读性继续登记。O1a及更早Minor继续有效，不能因本切片通过静默销账。

下一O1c实际gateway联合GET→O2 PORTAL SSE；RR1/RR2、CHAT/批准/全恢复、Task5其余/F/真实IAM模型本体K8s仍待。本轮无package/deploy，主服务仍旧962制品；有限legacy/HTTP装配不等于完整Boot。T09/T11/T12进行中，T01唯一整体完成，17功能/34任务/30验收未整体签收；v1.2/四契约不改、不排工期。本段更新前文历史“下一O1b”的当前状态。

### G6 O1c网关只读状态及取消边界修复验收（2026-10-21）

O1c实际gateway GET /admin/runs/{id}只读链及I1窄修已独立复审Spec compliant / Task quality Approved，Critical0/Important0。真实原Portal固定STATUS capture→独立Q→engine metadata HTTP→独立fresh M→engine status HTTP→完整严格校验后原始UTF8返回；Q/M相同原来源但grant/operation独立，观测实际exchange/authorize/authorize各两组，没有额外gateway-target authorize。Q只读control与三根metadata/payload0；M真实单readOnly RR、全scope先验再payload，未续原execution grant，执行表/worker/provider不因观察改变。

STATUS只在实际proxy bean存在时有限安装；old构造默认关闭，缺bean/别名/查询含bare?/body/身份头污染/转发均拒。私有capture与proof核同request/anchor/attribute/thread/owner/claimed/lifecycle；实际getOutputStream取得后紧邻首次write终检。原Portal明确失效401 PORTAL_TOKEN_INVALID，JWK依赖503，内部Q/M/JWT与engine401安全503，严格已知403/404保留，engine RUN_STATUS_TOO_LARGE403与authority错误区分；不转WWW-Authenticate或下游私密消息。

Q完整wire8192与七字段data4096分开，M完整wire2097152；只接受严格UTF8、单完整JSON/无重复与尾随、正确envelope/run后才发成功业务字节。中文/转义/精确大整数decimal/null实际867B原始字节一致；正好限额成功/多1安全拒绝。总单调5秒，engine hop至多2秒/剩余预算；byte[]、decoded text、JSON树/根材料额外副本不构成全请求heap2MiB保证，不承诺旧JDKauthority强制取消、在线即时撤权或blocked write物理硬断。

读取中chunk超限原红2044ms、取消先于input.close后绿43ms。独立review又定位提前状态/头/类型/声明长度拒绝绕过inner catch、Apache graceful close排空的问题；仅PublishedRunHttp与其transport test窄修，整个新observation handler终止失败在自动response.close前取消，同时保留inner read取消早于input.close，旧GET/POST和global Cancellation语义不改。窄修先实际2F0E，声明超限与599停滞body各等约2秒；后十组Q/M声明超限、599/401、Set-Cookie、text/plain均0–1ms、一request/installed/cancel、cancelled=true，原读取中超限31ms；58 raw peers均退出。

原15命令的31新方法/common9+chain14+wire8及3指定旧回归保留为历史latest实际结果，不是最终源码一套34。test-verification source误记finalSource的26/31行已独立correction与historical-source-supplement澄清；中间源码全文未逐轮保存，不补造快照/不重测补档。新修复按每命令pre/post manifest、两文件实际文本与Running XML封存，仅一次final命令transport10+chain正向/partial2，12/0/0/0，不重跑common9/chain14/旧POST/SESSION大套件。partialIO实际WARN committed=true attempted867 actualunknown、peer128业务/311raw字节；不append/retry，best-effort close不包装物理硬断。

必要source585（后端393/前端192）沿O1b新增6/修改6，窄修再2文件，无增删；target1972→历史逻辑2005→2004，原delta64/候选524。窄修locator有限409行核验发现原继承库存5个unused测试class身份不同/1缺失，403实际所用输出匹配；独立inherited-target-correction按5改1删更正至2004，再叠加修复delta7/候选245，最终逻辑2004。六条偏差发现时间已记、具体历史发生时间未知，不计本轮编译，不恢复旧类、不宣称全历史target物理复验；生产JAR未变。原own2097叶与root2192项、修复own1889叶/14368380B与root2398项分别有限审计PASS，旧大库存继承不重扫。原与窄修private首check/apply均0/0、逻辑585 names/bytes/SHA一致，init128等helper失败保留而不重做成功测试/apply。原POST CP app115 exact、engine134非空有序一致但旧trailing ROOT差异明示，经no-init origin/byteSHA核后仅原POST一次；原POST回归当轮28表3846历史行不变、新增70、迁移不变；本次窄修只核fixture观察前后计数，未另作全库复核。实际自有进程资源按各轮封存退出，PG/其它服务保留。

报告与复审：代码/agent-platform/docs/evidence/iam-stage-4-o1c-gateway-status-report.md、iam-stage-4-o1c-review-fixes-report.md、review-iam-stage-4-o1c-gateway-status.md、review-iam-stage-4-o1c-review-fixes.md。原M1来源归属已解释，旧O1b-M3/M4/M5、O1a与更早Minor继续登记；新复审其余处置：本轮无新增Minor；原O1c-M1以独立归属更正处理，旧O1b-M3/M4/M5与更早项仍沿原台账保留。

下一O2a A先common固定PORTAL response-current/privateholder、receiver保持STATUS-only关闭EVENTS，再B受限材料读取、C安全engine SSE整体装配，最后O2b gateway独立材料终检。CHAT/RR1/RR2/批准/全恢复/F及真实IAM模型本体K8s仍待。本轮无package/deploy，主服务仍旧962制品；有限HTTP/JVM装配不等于完整Boot。T09/T11/T12进行中，T01仍唯一整体完成，17功能/34任务/30验收未整体签收；原v1.2/四契约不改、不排工期。本段替代前文历史“下一O1c”的当前状态。

### G6 O2a A事件响应公共授权及生命周期窄修验收（2026-10-21）

O2a A 按限定范围 Accepted：common 固定 PORTAL events response-current、私有跨线程 Capture 与生命周期资源约束完成。source589=backend397+frontend192，相对O1c为5改4增；后续窄修仅3既有文件。固定签名绑定、fresh current、first-frame单调55秒上限、generation和material前后本地检查有效；历史执行ceiling/origin不强行等同新观察会话，历史材料来源仍由B的真实PG读取建立。receiver继续STATUS-only，本项不代表engine/gateway公开SSE或全局IAM完成。

原fresh review的I1关闭漏交取消、I2未发布reservation泄漏、I3终态task遗留timer全部解决；M1按实际operation<=grant关系准确改名并披露没有独立grant-first覆盖。仍为2workers/queue64/64slots/250ms watchdog；watchdog拒绝时不执行callback，固定slot保留任务待本地派发，普通closer可锁外执行且实际退出后归还额度。非watchdog调用者可能被自有callback阻塞，不承诺物理关闭或即时撤权SLA。

测试按实际阶段保留：原final01八类85方法为72PASS/13ERROR，旧ServiceAuthorizationTest的13项输入冲突逐名待迁移；随后原green04仅Profile22/22。窄修red01为5FAIL/0ERROR，定向green01为5/5，watchdog补充red02为1FAIL/0ERROR，唯一整类final01为Profile28/28。没有将多阶段相加成一次suite，也未重跑旧六类/PG/engine/gateway/POST/SESSION。Loopback未改，ReceiverClosed沿用原实际闭门证据。

代码依据：docs/evidence/iam-stage-4-o2a-a-portal-events-response-report.md、iam-stage-4-o2a-a-lifecycle-fixes-report.md；独立复审docs/evidence/review-iam-stage-4-o2a-a-lifecycle-fixes.md为Approved C0/I0/M0。根原有限审计.local/iam-o2a-a-root-check.json为5phase/2464physical PASS，窄修.local/iam-o2a-a-fix-root-check.json为5phase/2153physical PASS，分别继承而不合并计数，未重测/重放。正式source清单取修复目录source-after.json，正式target取final/target-after.json，logical2023包含历史诊断，不是纯classpath；新compiler class为0。

归因纠正完整保留：原194诊断源中183历史/11本轮新增，旧logical2004已含908历史dump；旧13错误名单应含actorProjection而health通过，按root两份correction与实际XML为准。窄修4新dump独立归档，误吸收的2027及原physical-check作为superseded元数据保留，正式清单缓存组合为2023。作者及root的helper错误、red01人工blocker超时诊断均保留，最终无相同worker诊断；只续行未成功阶段。三个历史/修复私有replay各自实际check/apply0/0，原件不改写，没有工程commit。

下一顺序：先单文件迁移ServiceAuthorizationTest并完整验14方法，恢复真实合法STATUS/cancel/monitor-forward覆盖；再以Accepted A接口冻结B受限PG/material reader（真实writer超过64父、先校材料再读payload），随后C安全SSE、O2b真实父子响应链。RR/CHAT/F/真实IAM及运行容量仍待独立验收。17需求/34任务/30验收未全闭环，T01仍唯一完成，T09/T11/T12保持进行中；不新增时间安排，不改v1.2原设计与四契约。

### G6服务授权测试输入迁移验收（2026-10-21）

新独立工程内ServiceAuthorizationTest单文件迁移Accepted：全部14原方法名和实质断言保留，health原文不变；source589=backend397+frontend192，仅1测试文件修改，0新增删除，无production/fixture/catalog/POM/其他服务改动。合法STATUS使用run-snapshot及可信已知actor的有限fixture登记；这不是PG admission/engine业务存在证明。integration STATUS明确拒绝，cancel通过真实exchange/authorize/resume及POST别名验证origin不洗白；统计positive通过真实gateway→monitor同frame retained/forward→engine secondary HTTP，成功票均非改签制造。

唯一实际命令iam-stage-service-migration-final01完整执行ServiceAuthorizationTest，exit0、Running仅本类、XML14/0/0/0。原13项输入冲突的迁移待办关闭；原A历史85=72PASS/13ERROR保持原件，Profile28/Receiver及其他旧类/PG/engine/gateway/POST/SESSION不重跑，不把新14项拼入另一次全量suite。同步2/async3在线调用计数、双run真并发、工作负载与失败关闭矩阵均保留。

独立审核docs/evidence/review-iam-stage-4-service-tests-migration.md为Spec/Quality Approved C0/I0/M0；root .local/iam-service-migration-root-check.json为5phase/2034physical PASS且首轮审计完成。报告docs/evidence/iam-stage-4-service-tests-migration-report.md及同名证据目录封存唯一源码pre/post、raw/XML、187实际compiler/resource/Running overlay和1833 own leaves。正式source-after.json SHA01121a4739ff5a0299cce9e5ecaa0ab5d433cd0be177ffd5d03f9410c67fe346；target-after.json SHA244535cb02df60585217088e321f2b6ac44aaca20de22821a080d66d41967e20，historical logical2023，不是纯classpath。新dump1单独归档，不吸入正式logical。

私有589重放实际顺序保留为首次LF patch check1、CRLF byte-exact补丁第二次actual check0、首次actual apply0；589名称/bytes/SHA严格相同，apply后无copy。没有忽略空白、修改工程.gitattributes/.git或改变已测试源码；只恢复未成功重放元数据，成功source/target阶段缓存，14方法不重测。此前A的replay和诊断归因纠正全部只读继承。

下一依赖顺序保持B受限PG/material reader→C安全SSE→O2b真实父子响应链。B按已Accepted Capture接口补最小本地initialCursor数据接缝，使用response.requireLive(Capture)而不在PG事务内隐式联网；真实writer大于64父、完整材料摘要及权限先于payload读取、双workspace实际消费退出后归还均须独立验收。receiver仍STATUS-only，公开SSE/PG材料读取尚未交付。17需求/34任务/30验收未全闭环，T01仍唯一完成、T09/T11/T12进行中，RR/CHAT/F/真实IAM和容量待验；原v1.2/四契约不动，不安排时间。

### G6 O2a B受限事件材料读取验收（2026-10-21）

独立新工程的B受限PG/material reader及借用输出修复已Accepted。最终source593（backend401/frontend192），相比前一Accepted589新增4文件，B完整范围8文件；后续借用修复只更新其中3文件。固定public event256KiB、完整row1MiB、metadata/root各8MiB、记录/直接父4096及model64MiB/canonical8MiB/总192MiB相交；原两个permit覆盖准备、pending与实际consumer finally，active逻辑close不提前归还。自建只读RR内先完整元数据/scope/current校验再payload，无authority联网；真实15节点writer样本含67直接父、68scopes/H2180。保守预算不是精确JVM heap或生产容量证明。

独立审核初次C0/I1/M2的三项已修复：输出前同ledger计费独立byte[]副本，调用方不能改写reader原数组；payload/labels共同guard拒绝交叉重入并在异常后恢复；补root字段名1048576/+1机械边界；原报告编译提示归属由追加修复报告纠正。最终review为Spec/Quality Approved，C0/I0/M1仅既有编译提示及路径乱码，原Needs fixes/错误原件保留。报告为docs/evidence/iam-stage-4-o2a-b-material-reader-report.md及iam-stage-4-o2a-b-borrow-fixes-report.md，审核为review-iam-stage-4-o2a-b-material-reader.md。

验证按实际阶段分别成立：原final01六类65=64PASS/1旧Initial setup ERROR，原final02仅B两类31通过；借用修复RED01实际4项2assertion失败，GREEN01为Budget14+新PG3共17通过，最终+24对齐slack后GREEN02仅新PG3通过。没有把阶段拼为一套最终17或全回归，也未重跑旧65/PG18/Service14/Profile28。root原B7phase/5836 physical及修复4phase/2170 physical各自有限审计通过，不加总为全仓覆盖；两代原B和本次修复的新patch均实际首check0/apply0且私有源码593名称/bytes/SHA相同，成功阶段均缓存，无重复运行。

最终源码清单.local/iam-o2a-b-borrow-fixes/source-after.json SHA83e1fc79f72a4b21bdeb75a3cc41b7d0406797070f25e4242fc74407b42f1a20；最终target-after.json SHAa23b333e7e0efd1f4d74cfac75804d9c791a3f9bbc4905c3ca92b132009e1fac，为历史逻辑2060项、实际新overlay464，不是纯classpath/全target树。原14+3及本修复3个新dumpstream分别归档排除；旧历史诊断只读继承。原内部backing58B测量属于修复前epoch，修复后sink收到copy，其长度不能再解释为内部数组身份；新增输出上界由同ledger预收费证明。

下一依赖先处理InitialMaterialScopePostgresTest单方法输入迁移，再C同RR保护窗口/游标/真实Servlet SSE，再O2b真实父子响应链。Initial失败来自STATUS+app-version在exchange阶段被既有selector拒绝，尚未进入read断言；必须建立合法STATUS/run-snapshot真实frame后验证其不能读取initial材料，不能只改成断言exchange拒绝。此前Service13项迁移已关闭，不因这个独立旧用例重新打开。

C尚未开启：B Optional.empty不提供终态或游标窗口证明，common receiver仍STATUS-only，安全HTTP/SSE与实际getter/write/flush/complete生命周期待验。新增具名风险：历史event物理清理后scope仍在，会触发严格locator/执行完整性拒绝；C只可在明确范围内验证终态保留窗口观察语义，生产retention执行链/活跃run清理兼容仍待独立实现验收，不能用测试DELETE冒充生产清理。C准备与retention风险文档是只读准备，不是已交付功能。

17需求/34任务/30验收继续未全闭环，T01仍唯一完成、T09/T11/T12进行中；RR/CHAT/F/真实IAM及K8s/全容量仍待验。只使用隔离端点，不关联相邻旧实现；v1.2/四契约保持原样，不安排时间。

### G6 O2a Initial材料测试迁移验收（2026-10-21）

InitialMaterialScopePostgresTest单文件迁移Accepted：12个原方法名/顺序及其他11方法原始字节不变，仅目标方法和7个必要imports修改，生产/fixture/runner/POM/契约0改动。旧STATUS+app-version交换保留403负例；新的阳性前提使用真实已受理run的owner/publicId/已接受snapshotSha、真签JWT、实际GET/三参status HandlerMethod和ServiceAuthorization frame。retained proof在同frame读前后验证，生产requirePurpose先于owner/root比较精确拒绝403/RUN_AUTHORITY_DENIED；payload0，控制SQL允许且完整材料SQL未发生。这个迁移待办关闭；原B历史65=64PASS/1setup ERROR不重写为65绿。

唯一实际命令iam-stage-o2a-initial-migration-final01完整12方法，exit0/0F/0E/0S；没有重跑原65、旧B PG21/Budget14、Service14/Profile28或C。独立review docs/evidence/review-iam-stage-4-o2a-initial-migration.md为Spec/Quality Approved C0/I0/M1，M1仅既有编译INFO/路径乱码/Surefire环境提示；root .local/iam-o2a-initial-migration-root-check.json为4phase/2157 physical有限审计通过，1682自有叶子、593私有源码真实首次check0/apply0且完全相同。source593仅1测试delta，SHAe9791749f842e9403604f5fc3ff354e596536746577c65eb7b610374d7d69d16；target SHA9dd29bc3bd986d324a94dd100fa1c30e46518c715c0215aba555e8a644c4e98d，462实际compiler/resource/Running overlay、历史逻辑仍2060；新dump1单独归档。成功阶段缓存，物理检查/测试/replay不重复。

报告docs/evidence/iam-stage-4-o2a-initial-migration-report.md及同名证据目录保留raw/XML、单文件pre/post、逐方法字节核对和准确执行源身份。测试原raw在执行后一次移入本任务归档，original路径是历史地址，归档bytes/SHA相同；不补造原路径。本次仅handler/security seam用途隔离验证，不宣称完整status HTTP/JSON或SSE。B受限读取/borrow修复结论继承，旧Service13项迁移保持关闭。

下一依赖为C同RR完整state/cursor-window+可选event、唯一2permit owner、真实Servletasync getter/write/flush/complete及共享frame配额，再O2b真实父子响应链。C使用最小本地generation查询，真正superseded才有限重读；不可一般403重试。历史event清理观察仅在明确的终态窗口与完整scope/current校验下处理，原B严格读不暗改；production retention执行及active清理兼容仍单独待验。当前C仍未开放，准备稿不算实现。17需求/34任务/30验收未全闭环，T01唯一完成，T09/T11/T12进行中；RR/CHAT/F/真实IAM/K8s/全容量待验。v1.2/四契约不动，不安排时间，继续使用隔离联调端点。

### G6 O2a C真实engine事件流及评审修复验收（2026-10-21）

O2a C限定切片Accepted：真实签名E/trusted decoder/ServiceAuthorization/handler→RunController→PortalRunEventsService→同RunEventReader真实PG，完成engine一跳PORTAL/pure FLOW保护事件流；同client opening，run-snapshot与STATUS/EVENTS双向purpose，严格真实游标来源/请求/默认拒绝和unknown/other-owner404。完整state11字段、state/event父并集和原root/metadata预算在同一readOnly RR核验，terminal/cursor/retention窗口来自同轮完整材料，不拼裸scalar。原A生命周期、B readNext严格borrow/copy与legacy接口保持。

专用Servlet async单owner覆盖实际getter/write/flush/complete；opening真实窗口预检后归还B/frame，queued只持小owner/slot/holder/原cursor，RUNNING完整fresh读避免排队饥饿。原B2permit、共享Cslot和16MiB池，完整262245B backing另8192B scratch先计费，两个阻塞owner逐实际退出才恢复；原55秒/源/grant/op硬界不因续核重置，观察断线不cancel后台run。机械long19/type64边界与PG int/varchar32可达范围分别记载。

独立首审C0/I2/M2的I1/I2已按新修复证据和同一评审人复审关闭：consume入口及最终guard失效的终止complete覆盖实际持有B/frame直到调用返回；健康superseded有限重读保持旧实际退出后再完整读取；fresh RUNNING未提交容量失败走HTTP映射，仅已提交且current live才有界reset。本轮先有入口guard、最终guard、未提交容量三项真实1F RED，focused3绿，再唯一最终四类24/0/0/0（Lifecycle11/Capacity5/Http6/Budget具名2），所有新阶段raw/XML/epoch即时归档。 原C最终六类29/0/0/0为修复前真实冻结历史，未扩大宣称修复后重跑原29或全后端。

作者报告docs/evidence/iam-stage-4-o2a-c-events-report.md及新修复报告docs/evidence/iam-stage-4-o2a-c-fixes-report.md、独立review docs/evidence/review-iam-stage-4-o2a-c-events.md保留全部实际阶段。root原C五phase/2373 distinct物理审计及新修复审计 .local/iam-o2a-c-fixes-root-check.json分别通过，首次私有raw patch check/apply及最终source逐项一致；不重复旧测试/PG/replay或扫描旧target。最新source600（SHA44192fbf077285773a9c8d35c9e89c1e6e9f1958184a16aee81ae0c211da9b54），最新target历史逻辑2096（SHA65646da310fefd754a22b1284854291de3447551ff801a2b8da9f150473eebc9），新overlay/diagnostic按各自报告分开，历史逻辑清单不是当前干净classpath。

M1真实工具/容器负路径日志噪声保留，不能称零警告/零ERROR日志。M2早期red/http-red/http-red-route/http-red-mapped/first-green五阶段仅raw，XML及完整source epoch缺失是已发生的历史归档偏差；root依据后期八阶段完整epoch/XML及新修复针对性证据接受本限定切片，但不认可缺失的早期精确XML/epoch已被验证。禁止补造或重跑冒充原阶段，所有新阶段必须即时归档。

终态测试仅DELETE自己真实writer完成后的event物理行，验证清理后PG观察语义；production retention job、active-run清理可继续及writer/replay/去重全平台兼容仍未关闭。Gateway/O2b G/E关联、TLS/私有完整material packet、真实gateway独立验证和browser SSE尚待后续D→E→F逐门验收；本轮不代表全IAM/CHAT/chunk/skill/model、生产容量或socket物理强断/撤权SLA。旧B65/1setup ERROR等历史不重写。17需求/34任务/30验收未全闭环，T01仍唯一完成，T09/T11/T12进行中；RR/CHAT/F/真实IAM/K8s/全容量待验。v1.2/四契约不动，不安排时间，继续隔离联调端点。

### G6 O2b D common父子授权与私有准入验收（2026-10-21）

限定D Accepted：真实original Portal decode与Q exchange，独立固定G exchange/actual authorize/open，opaque G holder，再经authority一次parent-child association得到E exchange、实际authorize/同receiver frame child open、生产受限receipt导出及G一次真验；双方check分别验证各自fresh current。G固定gateway.run.events.observe/private profile，只允许私有G验证入口，不新增publicroute或放宽requirePurpose。旧A standalone明确拒child marker，新child严格要求实际签名ticket/current一致的私有证据；没有用public DTO/Actor造权。

Opening自decode/Q原时点计5秒及原55秒硬界、自动到期、同64slot有限runtime转移与actual active退出后释放；不复制scheduler、不把短current或新hop重设硬期限。authority有界64parent/一父一子原子状态，过期前不为容量逐出防重放记录；G/E source/subject/run/cursor/完整关联一致、current版本和roles可独立。receipt为actualENGINE_CHILD owner/live只读≤4096ASCII JWS，不是G权限或后续任意字节认证；continuation不跨consumer。

D最终六类只一次实际87 tests=86 passed/1F/0E/0S（exit1），新G18+child22与原A28/closed2/Service14均通过。原失败是继承selector断言，永久保留原87/1F；后续唯一测试方法体迁移另一次完整3方法实际3/0F/0E/0S（exit0），fresh review Spec compliant/Approved C0/I0/M0。仅测试delta1、production/fixture0，三方法名/顺序及其他两方法和方法外bytes保持。不得把两阶段改写成87全绿。D审计5阶段6476 distinct/own6245，迁移审计5阶段1835 distinct/own1622，原始命令/Running/XML/source epoch与单次不同patch隔离回放均有限核对。 独立review docs/evidence/review-iam-stage-4-o2b-d-common.md为Spec/Quality Approved；root有限审计分别为 .local/iam-o2b-d-root-check.json、.local/iam-o2b-d-selector-migration-root-check.json。最新source603（SHAab0310201aff0a59e87f73b0692bf6e89b050591d7f33c2b4f099da3a97283c4），target历史逻辑2118（SHAf04cd892a8842083b1d546795a5ce146aa3b4edec794f90f3fa0f2e58035a893），有限overlay仅实际common compiler/resources/fixtureJAR/actualRunning报告，新diagnostic分开；历史逻辑不是当前全后端classpath。没有重跑原C29/修复24/旧B65、PG或全后端。

D独立review原C0/I0/M3中的M1继承selector断言已由上述独立迁移关闭；M2原3035B early replay独立check/apply raw未即时落盘，保持原现场和证据缺口，最终不同patch一次603完整回放不补旧记录；M3本轮D compiler清单JSON-Buffer归档格式不一致，冻结原件可无损解码并与overlay原始bytes一致，迁移新清单已按原字节归档。M2/M3仍作为非阻断Minor保留，未重写历史。 原C M1工具诊断/M2早期五阶段raw-only XML/epoch缺口及所有历史Minor继续保留，不补造、不重写历史。

当前只common协议和受控issuer验证，不等于生产authority已支持/部署，也未开放gateway实际EVENTS。下一E：真实engine child→同RR完整材料私有packet，保留原complete-before-release及16MiB池；再F：固定engine TLS身份/CA/必要mTLS、完整packet与独立G材料验证后公开SSE。签名关联/摘要不替代engine来源认证，gateway不把可信PG事实扩大为独立重算所有root payload。production retention/active prune/writer-replay兼容、RR/CHAT/F/真实IAM/K8s/生产容量继续待验，不承诺socket物理强断/撤权SLA。17需求/34任务/30验收未全闭环，T01仍唯一完成，T09/T11/T12进行中；v1.2/四契约不动、时间不安排、新工程隔离继续。

### G6 O2b E引擎child与完整材料私有报文验收（2026-10-21）

限定E Accepted：真实G/E签名链与engine ServiceAuthorization/filter/frame，固定private Accept只走实际child open，普通表示只走standalone open；双向拒绝/no fallback，非IAM私有在legacy前拒绝，同三参handler/action和debug/v1限制保持。HELLO从actual owner/live/ENGINE_CHILD production出口取得receipt，首次fresh RR全部验证之后与首WINDOW同consume输出。receipt仍不是G权限或任意字节认证。

私有v1固定24B APEV prefix和44B APCM COMMIT，10键header、完整14键WINDOW、HELLO/CAPACITY，header8KiB/metadata8MiB/payload256KiB及checked长度交集。完整initial/provenance/三root refs/state/event refs/revision/all scopes/完整parents/直接父/11键state/5非payload事件字段/window来自同一原RR；历史opaque grant/operation ID保留，可用service票/continuation不出wire。采用同borrow两遍受限count/hash/UTF8，第二遍等长等hash并live才COMMIT，不分配完整大wire buffer。

沿原B192MiB账本对新增模型/scratch/descriptor/隔离copy分配前收费，保留16MiB response池、270437B frame、2reader permit和有限原runtime；原B读者/普通C路径保持。entry/action/final guard失败也先actual complete，再释放B/frame/slot；阻塞get/write/flush/complete仍持额。首/游标reset/必要终态为完整材料WINDOW，后续活动idle可无包，EOF不证明terminal；partial不advance，不跨generation拼包。

原E独立源码epoch的最终7类45项通过（新增21、受影响C24），保持原运行及90459B patch/一次check-apply/608回放冻结。本轮fresh fixes仅Service+HTTPtest delta2：有效RED3/3F；初始有限final4为3pass/1E，write核心断言通过后在postrelease长存探针遇renew generation交错。仅测试尾部改用新正常holder，write-corrected单项通过；生产同字节/另三方法及核心断言未变的继承经原reviewer和root核实，不声称一次4green或最新epoch重跑其余三项。两项I1/I2均关闭。新19201B delta2 patch首次check/apply均0，完整608名称/bytes/SHA一致；新530有限overlay合并历史逻辑target2148，新4份diagnostic另列。 fresh独立review docs/evidence/review-iam-stage-4-o2b-e-engine-packet.md 为Spec compliant/Approved；root有限审计 .local/iam-o2b-e-root-check.json、.local/iam-o2b-e-fixes-root-check.json。最新source608（SHA303ec0625e9b8491d834cbd0ce27a38c0dcffab77104e333b38f960e102a5adf），target历史逻辑2148（SHA30422b7bcf37c2e31ef6c596ced94543d432bf847bde17b513640d6dba403f51）；overlay按真实compiler/resources/common fixtureJAR/actual Running报告有限合并，新dumpstream/dump分离，历史逻辑不是当前全后端classpath。

本轮E修复未关闭问题C0/I0/M0。pre-HELLO失败现为固定有界ApiResponse JSON；CAPACITY输出异常也在beforeRelease内finally等待actual complete，再归还B permit/frame/slot。原E初期TDD顺序/controlled-baseline、HTML400/stale INVALID及本轮0 Running INVALID、初始final1E保留，不补造、不改写。I2为实际已授权borrow上的编码故障及write/flush注入，不是最大BUFFER_LIMIT压力；192MiB为静态保守模型及账本断言，未记录精确heap峰值。F仍待实现，三root大payload不出wire，未验实际TLS来源/网关完整材料/公开SSE/真实外部IAM/K8s/生产容量。 原D87/86pass/1F和独立selector3green永久保持；E受影响C24在新源码epoch回归与历史C阶段严格分开。旧C M1工具诊断/M2早期raw-only XML/epoch缺口、D early独立记录缺口/JSON-Buffer归档及其他历史Minor不补造、不隐去。

下一F仍待实现/验收：复用D Opening的原Q票实际metadata HTTP七字段→固定engine HTTPS身份/CA/必要mTLS与正确workload secret_ref→完整packet decoder/COMMIT/receipt关联/完整scope直接父与state/event摘要及fresh G current→实际公开SSE。E不导出三root大payload，F不能声称重算其payload SHA；engine可信PG负责row membership/latest/同RR选择。当前未开放gateway EVENTS，也不代表生产issuer/TLS/网关材料验证/真实外部IAM/K8s/生产容量/Socket强断SLA完成。production retention/active prune、RR/CHAT/F与全17需求/34任务/30验收继续待验，T01唯一完成、T09/T11/T12进行中；v1.2/四契约/时间约束及新工程隔离不变。

### G6 O2b F1严格私有报文解码与本地预算验收（2026-10-21）

限定F1 Accepted：仅gateway infra新增strict packet decoder与有限resource budget及两测试。逐段验证24B APEV prefix/44B APCM COMMIT、精确segment/packet SHA、ordinal、长度与严格UTF8/JSON结构；一次只持有一个有界包，无下一包预取。结果明确为UntrustedPacket，不代表engine来源、receipt验签、parent关联、run/floor/current、业务row SHA或公开输出许可。gateway EVENTS继续关闭，STATUS/POST、common/Controller/assembly/main/HTTP/TLS/四契约保持。

实际测试保留五阶段：功能RED2失败；首次green编译失败不计RED；compiled17含预算2失败；纠正及新增focused5通过；唯一final20通过（Budget9+Decoder11，16main/13test真实编译，common无Running）。root六阶段3610个不同物理文件审计PASS；未重跑作者成功测试、PG或重放。原52743B文本patch首check/apply均0但两生产文件末尾CR丢失，原失败现场完整保留；另制22378B二进制patch、独立608基线首check/apply均0，一次完整612字节核对吻合。 独立review docs/evidence/review-iam-stage-4-o2b-f1-gateway-decoder.md 为Spec compliant/Approved，root有限审计 .local/iam-o2b-f1-root-check.json。最新source612（新增4、SHAb391ee9fe99f69b51066006bdb468bcf4750cf2f6bd16a2dc22ae458fae301f4），target历史逻辑2167（SHA86cf879d8893d7b49bcec0e261a262e1339596f2ce5c431e37190cb07c7530f0）；仅合并实际compiler/resources/common fixtureJAR/actual Running有限overlay，新diagnostics另列，不称全后端当前classpath。

M1保留：4份Manifest-JAR跨盘诊断、既有弃用与日志路径提示，不隐藏、不重跑。parser 65667584B静态模型小于64MiB预留，仅固定ReaderBased路径；allocation/backing观测不冒充精确存活堆上界。固定schema使部分JSON深度/token边界不可达，已明确交集限制；UntrustedPacket没有业务授权效力。F2 TLS/HTTP/Servlet资源和来源、receipt/G-current/完整验材与SSE仍待实现；未宣称生产容量。 原E45、fixes初始final3pass/1E及纠正write1pass、两份patch/回放/独立review/审计保持冻结，不重跑或改写；旧C/D证据缺口与所有已记录限制继续保留。

下一F2仍待实现/验收：Opening原Q票实际metadata HTTP七字段、固定engine CA/hostname/origin HTTPS、发送headers前实际child/G live、真实receipt关联/fresh G current、完整nested schema/scope直接父/floor/initial/state-event业务规范化SHA、公开SSE/frame/cursor/terminal及真实upstream abort/close/actual complete所有权。G/E均已有自动renew，不另造scheduler。F1不计HTTP/TLS/Servlet底层buffer；其预算与来源必须在F2另证，E16MiB也不能替代。三root大payload仍不出wire，不假称网关重算其payload SHA。production retention/active prune、真实外部IAM/K8s/生产容量/强断SLA与全17需求/34任务/30验收继续待验。T01唯一完成、T09/T11/T12进行中，v1.2/四契约/时间及独立新工程隔离不变。

### G6 O2b F2a隔离TLS基础限定验收（2026-10-21）

限定F2a Accepted：新增3个gateway infra TLS生产类、两测试类及仅测试PKCS12素材。固定专用CA/hostname/SAN、单literalIP、TLS1.3唯一AES128GCM；每physical连接C65536累计raw输入减唯一已交付应用字节、W131072累计raw输出，全实例共用两物理槽、零等待队列，单调connect/handshake/idle/hard期限与实际abort/退出后归还。TLS只证明固定服务端来源与组件边界，不给业务授权，组件仍未装配；gateway EVENTS继续关闭。STATUS/POST、common/Controller/assembly/main/pom、v1.2及四契约保持。

原F2a final39仅为历史。独立审查I1/I2/I3、M1/M2已关闭；功能修正唯一final实际Debt7+TLS40=47/0/0/0，raw/XML/Running已核。19main/15test在前一eof-collector-green实际编译，final同618源码epoch按up-to-date执行。新的格式head只改两文件具名行首空白，含注释/字符串的token完全等价，未重编译或重跑47。root分别完成原6phase5941、功能修正5phase6296和格式3phase21个实测物理身份+616复制身份继承的有限核对，不能相加成新测试数。原/新binarypatch分别首次check/apply成功且完整source回放一致；最新完整618为明确复制快照。原612source逐字节不变，累计新增6。123份自有(run,PID)真实退出已核，早期cwd仅请求字段、numericPID复用和失败历史如实保留。 独立review docs/evidence/review-iam-stage-4-o2b-f2a-tls.md 为Spec compliant/Approved，root有限审计 .local/iam-o2b-f2a-root-check.json、.local/iam-o2b-f2a-fixes-root-check.json、.local/iam-o2b-f2a-format-root-check.json。source618（新增6、SHAe9a961138967910fae61a2f611c8fd0f69d1391c4b4c973dc78b79da8e6be1e0），target历史逻辑2202（SHA95313cf613f9b3045eb85b524740a77b2fb36fc6a6148ce83b43cce75d3d8067）；finite overlay只采实际编译/资源/fixtureJAR/actual Running，新diagnostics单列，不冒称全后端当前classpath。

保留两项非阻断限制：M3原13份与功能修正6份Windows Manifest-JAR跨盘诊断及旧deprecation；M4原failed thresholds的较晚input-source观察不能当编译epoch。原plain常量观察声明不成立，由新报告明确纠正，只以新有限raw分类/额外accept负控制建立当前证据，不追溯改写旧报告。 H32768/peer chain4只在全新隔离测试JVM的明确启动flags下实际验证；生产launcher与静态JSSE属性初始化未验证，本轮不修改main。未知DER/X509全局cache/JCE/native费用与完整TLS存活堆/全链192MiB/生产容量继续待验。原F1归一化失败与另制binarypatch完整回放，以及全部E/C/D历史证据继续冻结保留，不重跑或抹平。

下一F2b实际Apache socketFactory/TlsSocketStrategy接缝、原Opening Q事实HTTP及私有E输入、send-header前真实proof与有限HTTP费用；F2c真实receipt关联/fresh G current/完整nested schema及scope父关系/floor/initial/state-event规范化SHA、公开SSE/frame/cursor/terminal和Servlet实际complete所有权。完整跨JVM engine/issuer/PG正例、production retention/active prune、真实外部IAM/K8s及强断/生产容量SLA仍待验。全17需求/34任务/30验收未全闭环，T01唯一完成，T09/T11/T12进行中。时间安排及独立新工程隔离约束不变。

### G6 O2b F2b1原Q HTTPS元数据限定验收

限定F2b1 Accepted：同一实际Opening的原Q仅一次exchange，私有Session固定HTTPS origin/literalIP，通过已验F2a的真实TLS handle接入Apache固定socket/upgrade路径；在实际executor及writer首字节前复核同一请求/原Q/Opening/期限。私有无headers/wire日志Managed adapter、零复用/重试/重定向/代理/压缩/cookie/auth fallback，严格有限head/body及EVENTS流式schema解析。EventsMetadata为绑定同Opening/Q的事实，不能充当G或业务permit。每Owner预留3MiB加128token，持有直至自有连接、活跃读写及worker/timer实际退出；费用仅覆盖具名HTTP组件模型，未覆盖全TLS/JVM/native。当前组件未装配，gateway EVENTS仍关闭。

原F2b1 epoch实际final18（metadata7/HTTP11）；修正epoch新的确定性并发行为RED1F/0E、focused3绿、final HTTP14/0F/0E/0S，focused2真实20main/17test编译、final同完整621 epoch up-to-date；没有重跑旧18或metadata7。真实TLS CL8192及chunked8192成功、chunked8193读取超限/未知尾部主动关闭、原Q一次exchange/七事实与实际退出已补齐。root原审计6阶段/18095 physical，修正审计6阶段/6622 physical，另有限live source名字621独立一致；metadata词法相同和STATUS前6168B exact，18新增child全部实际exit/collector join（17绿0、1RED1），finite overlay143合并历史logical2226。 独立review docs/evidence/review-iam-stage-4-o2b-f2b1-http.md 为Spec compliant/Approved，root有限审计 .local/iam-o2b-f2b1-root-check.json、.local/iam-o2b-f2b1-fixes-root-check.json、.local/iam-o2b-f2b1-fixes-live-names-root.json。source621（本轮三新增、一已有metadata client修改，其余617不变；SHA7ce27abedc333786bff2c7c886ab6092814b52118bf1fd3fd87f6a52eacf81fa），target历史逻辑2226（SHA4b7643c6b7f82ba96199f3d9b8e934089f94cbeb084e383ad13f5c6cf6ec3f53）；finite overlay仅来自实际compiler/resources/common fixtureJAR/actualRunning，不能当全部当前classpath或新部署。

原I1/I2和M1已闭合，M2 Windows classpath/deprecation诊断留项，修正新增3份344B与原10份分开保留。历史API缺失/harness RED不能说成完整业务guard RED。本轮第二辅助replay-index add与plain apply交叠失败、无预存独立preapply全工作树清单，以及两次高层metadata stderr仅有transcript的限制继续保留。root独立核主baseline621 actual tree、现有raw四个正反向image及blob SHA1、其余617前后与复制谱系、完整after621，确认首次普通worktree check/apply各1次exit0的有限重放闭合；未把辅助index失败或摘要当PASS/raw、未再运行Git apply。3MiB仅固定HTTP组件工程模型。公开EVENTS未装配，G/E、完整材料、SSE/Servlet、真实全链和生产启动/容量仍待。 前置F2a原39、功能修正47及纯空白token/copy-lineage分属既定epoch，不重复合计为本轮新测试；新一轮只以本轮实际Running/XML/编译及端点记录为准。原报告/patch/replay/诊断与失败历史继续保留。STATUS既有流程/POST、common/Controller/assembly/main/pom/前端、v1.2及四契约保持。

下一F2b2在同一私有Session由真实Opening转交G、绑定child E并读取私有包；F2c1真实receipt关联、fresh G current、完整nested schema/scope父关系/floor/initial及state-event规范化SHA；F2c2公开SSE/frame/cursor/terminal与Servlet实际生命周期。真实gateway/engine/issuer/PG/TLS联合正例、生产JSSE启动/完整容量、retention/active prune及真实外部IAM/K8s仍待验。全17需求/34任务/30验收未全闭环，T01唯一完成，T09/T11/T12进行中；继续独立新工程，时间安排不在范围。

### G6 O2b F2b2同会话私有事件读取限定验收

限定F2b2 Accepted：同一私有Session已验证原Q事实后，原Opening转真实G、恰一次交换同G child；在固定HTTPS/TLS/actual request双guard下请求唯一私有MIME，F1按单包同步借用消费。七事实在Opening移交前私有保存，后续仅同G最新current控制，不把短child JWT用于每包续验。Q物理图和timer实际退出后才E图；原t0的5秒Opening/55秒hard、E head2秒与15秒idle及阶段取消机制保持。纯有界E错误code解析与原STATUS/Q事实方法保持独立。Q-only Owner3211392B；consume前新增1MiB+128token，E-phase4260096B，退出/borrow未完成则持费并拒第三Owner。费用仅有限组件模型，不是RSS/全TLS/JVM/native容量保证。公开EVENTS尚未装配。

最终source623同一epoch真实执行31项（原Http14、新Authority8、新Http9），0失败/0错误/0跳过；实际新编译20main/19test。root六phase有限审计PASS，9551 owned、三改两新及其余618精确字节、原STATUS6168B与原14断言、主before621真实Git树、预存replay before621名字和字节、首次plain check/apply各一次0与完整after623闭合。finite overlay155→历史逻辑target2239；60个实际child均waitFor且collector退出（53成功、7保留历史失败），最终31 child退出0。普通8482B/额外签名前padding8559B child、43原始拒绝向量、21345B CL/chunked、原t0 55011ms/55callback、15134ms idle及实际撤权读退出已按原始证据核对。没有重跑作者成功测试、PG、provider或Git replay。 独立review docs/evidence/review-iam-stage-4-o2b-f2b2-private-http.md 为Spec compliant/Approved；root有限审计 .local/iam-o2b-f2b2-root-check.json。source623（三既有修改、两新test、其他618不变；SHAc2dd5086c9718098dc0957aa7f0dd3e7c809f56793d7eaea1b53e3987262c246），target历史逻辑2239（SHA38cd8356238f4363f10d3bbdf46660b727d10fbcbdf8cf47ad6ed1c68311bfd5）；finite overlay仅actual compiler/resources/common testJAR/Running，不作全部current/classpath/部署声明。

保留2项Minor：12份各344B Windows classpath/编码诊断及deprecation，新增并发测试长单行可读性。初始Python GBK在写入前失败，其raw仅存在工具transcript，owned为准确observed摘要而非伪造raw；stage子进程归属含mtime启发式，实际退出独立核对。费用仅有限组件模型，排除全TLS/JVM/native/common内部图和任意consumer图；G.close不证明common内部任务物理join。HELLO receipt及WINDOW目前仅未授权结构输入，C1真实receipt/material/current校验、C2公开SSE/Servlet、真实全链和生产launcher/容量继续待办。 原F2b1的18、修正14及本轮各stage严格按source epoch保留，不把旧结果改名新跑或合并为虚假同场测试。v1.2/四契约、common/TLS/F1/engine/main/controller/assembly/pom/前端保持；时间安排不在范围。

下一C1在同Session验证真实receipt与parent-child、完整nested schema/scope直接父/floor/initial、state-event业务canonical SHA和fresh G generation，借用VerifiedWindow；C2再接公开SSE/frame/cursor/terminal、Servlet实际生命周期与物理abort。真实gateway/engine/issuer/PG/TLS全链、生产JSSE launcher/完整容量、retention/active prune及外部IAM/K8s仍待验。17需求/34任务/30验收未全闭合，T01唯一完成、T09/T11/T12进行中，继续独立新工程。

### G6 O2b F2c1完整事件材料校验限定验收

限定C1 Accepted：同一私有Session的原Q七事实、真实G及其child实际接收链生成的HELLO receipt恰一次验证，交叉核parent/child关联；WINDOW严格14键和完整nested材料、root/state/event与直接父精确并集、完整父数组、initial/floor/代际逐项验证。state/event业务SHA按精确Java String排序、BigInteger/BigDecimal与JSON转义语义流式canonical，数值展开前容量准入。最多两次完整P3重验；同步VerifiedWindow只在同Session/具体packet/同代借用内有效，不产公开cursor/终态/浏览器字节。固定80MiB先收费、真实解析/consumer/borrow退出才退款，并早于下一F1 scan；不是全JVM/TLS/native容量保证。

原C1 candidate epoch的19/19验证与14个最终child实际退出，继承其完整独立机械审计；修复后新627 epoch只新增一次受影响矩阵8/8（Authority3＋原Verifier5），最终3个child实际waitFor并collector join，未重跑原19，不能合称同场27。旧实现的真实并发/重入RED均保留；I1同窗口单writer在任何payload遍历前原子拒绝，第二generator调用0，异常后顺序使用成功；M1 schema/factory仅排版。并发/reentry单Owner88342871B、双Owner retention176685742B/第三拒绝/实际writer退出后退款。root新delta六phase PASS核627源码两修改/其他625 exact、actual Git627 before/after、首次plain check/apply各一次0/fullafter627、finite378→历史logical2269和全部6个child实际退出，未代跑成功测试。 fresh review docs/evidence/review-iam-stage-4-o2b-f2c1-material.md 为Spec compliant/Approved；root有限审计 .local/iam-o2b-f2c1-root-check.json、.local/iam-o2b-f2c1-fixes-root-check.json。source627（一既有修改、四新含classpath资源、其他622不变；SHA6b4a6488efa08b27266ccceff39fd3e79b62b137a3edcc6dfd3b071abd83f68c），target历史逻辑2269（SHA1998bf8be7352c46b2ca2698ca16e90cb092b5a09eb5817401b8bca441caa378），finite overlay仅actual compiler/resources/common fixtureJAR/Running。source resource身份与Maven copy及root E WINDOW提取gate闭合；旧HELLO/JWT/私钥不进入新asset，新test运行不读ignored.local或engine业务JAR。

本次I1/M1闭合，仅原M2编译编码/deprecation诊断保留；原F1 ignored.local whole-branch可移植性仍属后续对应任务。writePayload仍为排序/数值归整后的JSON；C2需最小同window rawpayload共享writer借用接缝以保留浏览器原JSON数值/转义。公开EVENTS/SSE/真实Servlet生命周期、实际enginePG latest/membership/rootpayload与生产issuer、真实跨服务首链尚待C2；不据局部验证签收完整IAM或全JVM/TLS/native容量。 旧E/F1/F2a/B1/B2各stage结果按原source epoch保留，不重跑或合并为虚假同场证据。v1.2原文/四契约、common/TLS/F1/engine/pom/main/controller/assembly/前端保持；时间安排不在范围。

下一C2依实际C1私有API接公开SSE/Frame/cursor/terminal与Servlet生命周期，采用真实WriteListener实际写、同Session同步借用、有限ready-idle等待及精确request CLOSE_NOW/disarm；bootstrap在原请求线程等首个可信window后注册并返回，具体源码/并发/有限退出必须另验。真实gateway/engine/issuer/PG/TLS全链、生产JSSE launcher/完整容量、retention/active prune、外部IAM/K8s仍待。17需求/34任务/30验收未全闭合，T01唯一完成、T09/T11/T12进行中，继续独立新工程。

### G6 O2b F2c2a未装配SSE与真实Servlet组件限定验收

限定C2a Accepted：同Session完整receipt/material验证后的原payload以受控同步raw借用写出，仅删除字符串外JSON格式空白，保留原数值词法、转义/Unicode和字符串空白；raw/canonical共享单writer准入。原Q/G initialCursor保持固定，WINDOW.after/event/reset依当前已成功公开flush后的cursor；只有完整write＋requiredflush/readydrain＋最终fresh检查才确认推进。reset优先、status最多一次，终态排完backlog才结束，EOF不冒终态。

真实Tomcat HTTP1.1 NIO中，原请求线程有限等首可信窗后登记listener并返回，首次get在原请求线程完成guard，实际WriteListener执行ready/write/flush guard与有界chunk；ready-idle轻shell有限等待、heavy helper真正返回/slot detach后才进下一F1scan。固定Http11NioProtocol＋公开Processor包装以真实socket锁串行完整IO、CLOSE_NOW与回收，原请求Valve绑定每次REQUEST新epoch；外部stop先释放应用gate再等待socket锁，onComplete及delegate.recycle前失效旧token，late abort不触复用后的请求；SSE实际worker/IO/borrow/重状态/completion gate先于Http旧G/费用cleanup，失败或卡住继续持费并拒第三Owner。这里只签收已实际验证的应用组件，不声称全container/channel/TLS/native同步join。

证据按各自真实字节epoch继承：原C2a最后29方法/38child，后续Q与原REQUEST/分配修正13、7、2各属独立epoch；共享人口/强锚修正保留14与10的失败、corrected7/1fail、action1及elected-join3，mixed首INCONCLUSIVE与改fixture后Handle/Processor通知各1、shared reuse2分列。最终Http修正先5方法GREEN/6child，再对真实c/r均非空见证分别做旧行为stop单项RED及当前stop单项GREEN；其余4方法继承先前epoch，11child全部实际waitFor/collector退出，无GC。两个实际取消/cleanup重试窗口在helper暂停时Owner1/reserved7405824且另一closer未退款，helper和线程真实退出后回3145728基线；light-shell实测9315526→3145728。四轮root各六phase有限审计、各自首次plain check/apply及全native blobs/源码名/有限target已缓存，未重跑已绿测试、原审计或成功Git。 独立review docs/evidence/review-iam-stage-4-o2b-f2c2a-sse.md 为Spec compliant/Approved；root有限审计 .local/iam-o2b-f2c2a-root-check.json、.local/iam-o2b-f2c2a-fixes-root-check.json、.local/iam-o2b-f2c2a-retention-fixes-root-check.json、.local/iam-o2b-f2c2a-http-exit-fixes-root-check.json。source630（3新4修改、其余623 exact；SHA6f61ed3ce5ce87fbafead1dd1434f2b1d9dc1b3c3692be81a51c19a7155e3b9d），target历史逻辑2298（SHA304be175bbbcd46f11fa045617b34c456526be83925a0599b3de43877081eeda），finite overlay只actual compiler/resources/common fixtureJAR/Running；旧C1原19/fix8等按各自epoch继承，不重跑或合为本次同场结果。

C2a已无Critical/Important。保留两项非阻断Minor：既有日志/编码/容器关闭诊断噪音；最初prelaunch断言失败helper无独立历史快照，其准确字节不可追补，后续actual source/测试/Git证据独立保存。支持的VM/layout、共享TLS/config/B2归属及非穷尽并发属于固定有限模型；库长期不退出时继续持费，不承诺GC/可用性期限。 未装配component无扫描/自动路由：公开IAM EVENTS仍关闭。下一C2b显式common ingress EVENTS claim、Controller/Assembly/同ControlledProtocol＋Valve接线，以真实独立gateway/engine/issuer/新PG/TLS和生产E encoder≥65父材料做首链；不能以C2a测试Servlet/private peer代替生产接线证据。固定connector每JVM终身唯一，以共享1536个名额同时计数原delegate与独立Handle/State/Session小图，forSse在Session创建前准入，final Session与State锚定唯一Handle；只在实际GC队列通知后归还名额，构造不确定失败烧槽，不靠旧1218估算。GC/通知迟延与烧槽可能降低可用性。预收3MiB全局基线直至JVM实际退出；Owner清零后该基线保留，双Owner候选合计200576852B属于有限工程模型。生产JSSE launcher/全容量、retention/activeprune、外部IAM/K8s和整个17需求/34任务/30验收仍独立待办。T01唯一完成、T09/T11/T12进行中；v1.2原文/四契约/时间与新工程隔离保持。

### G6 O2b F2c2b1精确EVENTS入口与显式生产组件接线限定验收

C2b1已限定 Accepted：IAM 原REQUEST的精确 EVENTS 路由经真实验签JWT、实际Controller/Handler、一次私有capture、实际Spring6.2.7 manager/native响应身份绑定后启动生产SSE。仅该purpose允许唯一Last-Event-ID请求头或单一字面query二选一，默认0、canonical ASCII long；其余RUN/STATUS/session规则不扩大。实际MVC supplied response承担getter/listener/写入，不绕过框架包装，不把proof带入异步。已接管响应后的启动失败执行原清理与持费门，避免普通JSON混写；raw入口仍保持严格facade与抛错语义。

显式import的生产配置提供真实Http/Sse/RunEventsProxy beans，并通过公开Boot API安装同ControlledProtocol/ContextValve。固定最终1024连接/64线程/64cache、内部executor、HTTP1.1；真实独立JVM验证正常启动及5种错误覆盖配置拒绝。共享1536/1792B/3MiB及实际helper/worker退出、未退出继续持费等原C2a门保持。

新Common2及原Common3在其实际epoch通过；真实Boot默认/头/query SSE、framework/54负向、5种最终profile拒绝、原raw helper及最终单请求故障/Q错误/缺组件按各自epoch验证。22个真实child=17成功/5保留诊断失败，全部waitFor/collector完成；原raw child成功与父级UTF8元数据error分开保留。最终head仅窄6child对应JUnit1通过，不包装为最终全套。root六阶段有限审计通过，实际634 before/after/native blobs及8段binary patch/plain check/apply首轮各一次闭合。 独立评审 docs/evidence/review-iam-stage-4-o2b-f2c2b1-events-wiring.md：Spec compliant/Approved，C0/I0/M2；root审计 .local/iam-o2b-f2c2b1-events-wiring-root-check.json 六阶段通过。source634（对C2a为4修改4新、其余626 exact；SHA c91a517192cb8caecf84da5726640d90c759793065dce9cbe0e94291a737d74d）；target历史逻辑2314/finite417/delta48（SHA e8c4ef2cf272f953f4e1282b939d8f6fa055a487da1b16eac9393b0dc150ee3f）。这是限定组件与各真实epoch证据，不是最新全工程测试或部署记录。

保留两项Minor：编译deprecation/unchecked与乱码诊断摘要不完整；原raw JVM缺少两项Tomcat ThreadLocal/RMI leak-detection opens。原始失败、元数据错误和告警均保留，只在未来有功能理由的新runner epoch改善，不为清理文案重跑绿色测试。有限资源模型不等于全JVM/native容量或通用无泄漏保证。

下一C2b2使用独立GW/E/网络issuer、新PG库和正常CA/SAN TLS，验证真实E reader/encoder≥65完整父材料、游标/状态/权限变化及实际退出；C2c随后闭合四个前端消费点的raw payload、精确decimal cursor/watermark。当前公开EVENTS仅在显式IAM装配组件中可用，默认GatewayApplication生产IAM启动尚未验；private peer不替代联合链。外部IAM/K8s、发布部署、全容量与17需求/34任务/30验收仍独立待办。T01唯一完成，T09/T11/T12进行中；原v1.2、四契约、时间安排和新旧工程隔离保持。

### G6 O2b F2c2b2真实网关／引擎／PG联合链限定验收

后端真实联合链已限定闭合：原始事件字节、65 项以上实际父材料、权限续签／收窄／撤销／到期、真实慢读取、历史保留及终态后的自动 G／E 资源回收。最终 test16 独立通过；其余要求使用各自真实历史 epoch，未把局部数字相加为全套通过。作用域 155／父边 10016、事件行 35→34 的完整元数据核对通过。

源码 640；实际有限 763／末阶段 423／历史逻辑 2339；实际 31 服务子进程退出。独立评审 docs/evidence/review-iam-stage-4-o2b-f2c2b2-joined-chain.md 为 Compliant / Approved with Minor，C0/I0/M3；root 六阶段物理、编译、运行、原生应用与保护文档核对通过。限定证书：.local/iam-o2b-f2c2b2-acceptance.json。

本轮只闭合 C2b2 后端联合链，未重跑所有当前方法或完整后端套件。C2c 四前端消费、默认生产 IAM 启动、真实外部 IAM／K8s 与容量仍待；E 的 32768 请求头上限仍是已验证夹具配置，默认生产配置未验。T01 唯一完成，T09／T11／T12 继续进行中，T33 待启动；详设 v1.2、四契约与无工期安排保持。

### G6 O2b F2c2c四前端协议与流式文本修复限定验收

C2c 已限定 Accepted：DebugPanel、RunDetail、Chat 与 assistant.js 使用统一事件边界，保留实际 SSE event 名、任意合法 JSON（含 null／数组／标量）和原始 rawData；游标、状态 watermark 与快照事件 seq 使用 0～Long.MAX_VALUE 的规范十进制字符串。原生 JSON 词元只转换最终成功响应 data 的 watermark 和直接事件行 seq，其他业务 payload 数值保持原生语义，快照不伪造 rawData。32 码点且超过64 UTF8字节的事件名、中文分片和单个可选空格均有验证。

重连、reset后读取状态、手动授权恢复、浏览器能力不足提示、终态与批准、取消和身份代际／退出后的读取器关闭按实际浏览器场景验证。异常节点标识及继承事件名安全处理。独立评审发现的空 text 遮蔽非空 delta／content 已经最小修复，保留空白和字段优先级；Chat与SDK在最终结果返回前均显示正确片段。

原任务95项定向测试及四入口场景属于各自保留的源码阶段；后续 I1 修复为相关51项通过、Chat／SDK四个片段显示用例先失败后通过，最后类型检查、三应用构建和SDK复制 exit0。两轮各自原生补丁 check／apply 与源码、浏览器、有限产物、进程退出由独立评审和 root 核对，未重跑绿色测试；没有把不同阶段拼成最终全套测试。原 finite87条为86个物理路径，修复后165条为163个物理路径，当前实际构建输出15份。

独立评审：docs/evidence/review-iam-stage-4-o2b-f2c2c-frontend-protocol.md（原 C0/I1/M1）及 review-iam-stage-4-o2b-f2c2c-chunk-fallback-fix.md（I1关闭，C0/I0/M1继承）；限定证书 .local/iam-o2b-f2c2c-acceptance.json，绑定原任务 root-qualification。

本轮源码640→644累计13处修改、4份新增，627份原文件不变；I1修复只改2份既有源，其余642份不变。仅验收有限前端协议消费和恢复。默认生产IAM启动、模型／技能的可信材料出域及恢复链、真实外部IAM／本体／模型／K8s与容量仍待；后端历史逻辑2339保持原有边界。T01唯一整体完成，T09／T11／T12继续进行中，T33待启动；详设v1.2、四契约和无工期安排保持。


### 模型初始输入生产组件限定验收

已完成无历史 initial-chat-main-v1 输入的不可变 PG 落库与同事务材料作用域，以及经过真实签名授权的只读元数据接口。三项评审问题均已闭环：锁内绑定最新成功授权决定；模板大规模展开前检查 UTF-8/JSON 预算；已支持参数逐项校验类型。保留正常模板语义与近 2 MiB Unicode 合法输入。

修补后的同一份源码通过 PG21＋HTTP11＝32项测试，零失败、错误和跳过；独立同源副本打包成功（skipTests），原生补丁应用、有限产物与隔离资源退出经 root 核对。原任务及旧回归证据按各自阶段保留，没有合并为最终全工程结果。独立复审 Approved／Approved，C0／I0／M1；M1为保留的编译／Flyway输出告警，后续有实际维护理由时处理。

源码累计644→652：8份新增、3份原文件修改、641份原文件精确未变；本次修补只改其中4份，其他648份未变。限定证书 .local/iam-model-input-producer-acceptance.json；修补报告 docs/evidence/iam-model-input-producer-review-fix-report.md；复审 docs/evidence/review-iam-model-input-producer-review-fix.md；验收边界 .local/iam-model-input-producer-root-final-qualification.md。

下一步实现模型网关对材料和完整请求的校验。公开 CHAT／LLM／AGENT／模型出域、默认生产IAM启动、真实外部IAM／本体／模型／K8s与容量验收仍待。整体34任务状态不作完成提升：T01唯一整体完成，T09／T11／T12进行中，T33待启动。详设v1.2、四契约、工期排除和新旧工程隔离保持。


### 模型初始输入消费组件限定验收

已完成无历史 initial-chat-main-v1 私有输入校验：完整请求摘要、真实签名授权、固定 HTTPS 材料读取、父子材料闭环及当前授权／生命周期检查。原 I1 数值摘要兼容问题和 M1 无效零计数断言已关闭；严格保留合法数值词元，兼容生产小数、科学计数法、整值浮点及大有限数，仍只核对一个完整请求摘要。公开模型链尚未启用。

独立复审 Spec compliant／Approved，C0／I0／M1；M1 为预期 IAM 负例告警及编译提示，保留日志并登记后续有实际维护理由时处理。16项校验测试与最终3项真实独立 JVM／PG／HTTPS联调分别有完整源码阶段记录；仅 HTTP 负例构造变化，相关3份源码和5个类精确一致，不合并为同阶段19项或全工程通过。最终同源副本打包成功，566类／370 JAR条目、660份补丁应用与6个专属数据库退出经根核对。

组件累计652→660：8新增、3原文件修改、649原文件未变；此次修补仅改5份、其余655份未变。限定证书 .local/iam-model-input-consumer-acceptance.json；修补报告 docs/evidence/iam-model-input-consumer-review-fix-report.md；复审 docs/evidence/review-iam-model-input-consumer-review-fix.md；边界 .local/iam-model-input-consumer-root-final-qualification.md。中间元数据重建及审计读取修正均已限定并保留原记录，不影响最终独立证据。

下一步：冻结模型绑定快照的租户、全量摘要、当前材料标签和冻结端点能力校验，仍先完成私有前置组件。真实外部 IAM／本体／模型／K8s、模型首次发送及容量验收待后续闭环。整体34项状态不提升：T01唯一整体完成，T09／T11／T12进行中，T33待启动。详设v1.2、四契约、工期排除及新旧工程隔离保持。


### 冻结模型绑定校验组件：实现限定验收

已完成 initial-chat-main-v1 私有冻结绑定校验：真实消费证明、原始规范请求摘要、PG完整快照摘要与租户/标识、事务释放后当前授权/材料/标签覆盖、冻结能力及结果生命周期。原 I1 的3秒累计事务预算已修补，每次SQL消费剩余期限并保留单条2秒/锁1秒上限，事务清理完成后、最终IAM前再次拒绝过期结果；普通JDBC为合作式限制，不宣称硬抢占。

独立复审：修复行为Spec通过、Quality Approved、代码C0/I0/M0。真实PG失败先行2测/1断言失败/正控通过；最终同阶段22/0/0/0，旧73项回归保留原阶段，不重跑或合并。根核对667份源码（修补仅3改、664原样）、622有限产物/582类/28资源、12编译输入清单、6包/548条目及实际补丁应用；独立副本打包成功。2新库会话0、4角色NOLOGIN、18记录进程和8已知端口关闭。

执行不合规单独保留：修补初始曾在父目录枚举相邻文件/目录名称，未获得全任务无条件合规结论；转抄记录及作者关于未读相邻内容的声明不能替代完整原始轨迹。原误用canonical打包的未知主运行影响、历史loaded-class缺口及Surefire临时字节缺口均未被新绿结果消除。限定证书 .local/iam-model-frozen-binding-acceptance.json；范围 .local/iam-model-frozen-binding-root-final-qualification.md；复审 docs/evidence/review-iam-model-frozen-binding-review-fix.md。当前保护文件基线保留此前7原样+1外部格式变化，不恢复未知来源格式。

下一步为私有LOCAL输入token估算：绑定真实有效证明及原始accepted canonical字节，估算不作为发送/预算准入许可。公开模型链、真实IAM/本体/模型/K8s和容量仍待后续闭环。整体34任务状态不提升：T01唯一整体完成，T09/T11/T12进行中，T33待启动；详设v1.2、四契约和工期排除保持。


### 私有LOCAL输入估算组件：限定验收完成

已完成真实冻结证明与原始canonical字节绑定的JTokkit本地估算（+64结构余量，exact=false），以及进程异常、候选身份、partial setup收尾修补。最终31项在五组各30秒外部上限下全部通过；独立复核未解决C0/I0/M0，源码671不变，隔离资源已闭合。范围、实际证据及执行偏差见 .local/iam-model-local-meter-acceptance.json、.local/iam-model-local-meter-root-final-qualification.md 和 docs/evidence/review-iam-model-local-meter-watchdog-review-fix.md。

该估算尚不承担实际wire计量、准入或发送许可；下一项为共享Spring AI冻结参数转换。T01仍为唯一整体完成任务，T09/T11/T12进行中、T33待启动；真实环境/容量验收待闭环。详设v1.2、四契约及不排工期保持。


### 冻结初始Prompt转换：代码基线确认，执行I1保留

已补齐共享Spring AI原生参数转换及原始冻结字节校验；最终20项相关回归通过，674份源码/4 JAR/补丁及隔离资源完成物理核对。代码复核Approved、无生产代码阻断；总体仍C0/I1/M0：早期未guard Python的历史时间/PID缺口不可补证，整体验收NOT_ACCEPTED。可复核代码作为后续隔离开发基线，I1继续记账。依据 .local/iam-model-frozen-prompt-code-acceptance.json、.local/iam-model-frozen-prompt-root-code-qualification.md、docs/evidence/review-iam-model-frozen-prompt.md。

普通Prompt不是实际发送/计量/准入许可。v1.2、四契约、不排工期及T01唯一整体完成/T09T11T12进行中/T33未启动不变，真实环境和容量仍待闭环。


### 初始流式 SDK 请求体校验：代码基线677，执行偏差保留

已完成私有实际 SDK 请求体与真实冻结输入的对应校验，覆盖模型、消息、参数、输出格式、流式设置及当前证明生命周期；函数未接入生产发送。真实失败先行后，同一final3源码16项相关测试通过。独立复核CodeSpec符合要求、代码Quality附Minor通过；本任务总体C0/I2/M2：双Maven重叠及一次helper启动前字节封存缺口为Important，夹具强停与预期ERROR栈噪声为Minor，历史I1仍保留，整体验收NOT_ACCEPTED/NONCOMPLIANT。

根核对9545份有限证据、677源码五份树、637编译/资源记录、4JAR/377项目ZIP条目及原生补丁重放；4隔离库/8角色和记录进程已收尾。仅确认代码可作为后续独立开发基线，不重跑测试修饰历史。依据 .local/iam-model-frozen-wire-code-acceptance.json、.local/iam-model-frozen-wire-root-code-qualification.md、docs/evidence/review-iam-model-frozen-wire.md。

下一步为显式隔离组件中的认证模型入口→当前材料→专用tokenizer→配额/持久化→provider流式链；默认生产IAM/D3、引擎IAM发送与attempt整合仍未接通。v1.2、四契约、不排工期及T01唯一整体完成/T09T11T12进行中/T33未启动不变。

### 初始模型流式组件：代码基线680，执行历史保留

显式隔离组件现已连通认证入口、当前材料/绑定、专用 tokenizer、配额/持久化和真实 Spring AI SDK/provider SSE。独立复审后 I1/I2/I3 均闭环，适配器诚实记录 skip/container；限定代码评审 C0/I0/M1，最终规范化680源码取得 CODE_ACCEPTED。默认生产 IAM/D3、公共 CHAT 与引擎调用方尚未激活，整体任务 NOT_ACCEPTED、执行 NONCOMPLIANT。

本批13方法实际绑定规范化前源码；仅7个CR删除后，同一编译器/选项产生的3个 ModelCalls class 与运行字节完全相同，未重标或重跑历史。Root完成新交付九项物理核对，六包634项目条目及3848公开ZIP条目一致；27组隔离Redis已关闭，数据库保留数据、零会话，测试角色NOLOGIN/无高权。早期固定测试key复用、独立helper常量输出、原评审偏差及全部失败/超时/ERROR记录继续保留，不因代码通过豁免。

依据：.local/iam-model-initial-stream-code-acceptance.json、.local/iam-model-initial-stream-root-code-qualification.md、docs/evidence/iam-model-initial-stream-fix-report.md、docs/evidence/review-iam-model-initial-stream-fix.md。下一步依赖该680基线，实现真实RunService.drive/driveRecovered→原始请求/attempt持久化→单次HTTP/SSE→fenced logical commit，并验证不确定结果不重发。v1.2、四契约、不排工期与T01唯一整体完成/T09T11T12进行中/T33未启动不变。
### 2026-10-23 组件三档验收规则应用与 source684 开发基线

当前最新限定开发源码为 source684（最终 final4，清单 SHA256 1d11e82ab89aa589aa8415fee8a14e36b38fab40dd18e4567c9b085ea9153901）；独立 SpecCompliant / QualityApproved，C0/I0/M0，初始发送 I1–I4/M1 及租约终态窗口已闭环。根证据 .local/engine-initial-sender-development-baseline.json 仅批准后续开发使用已评审代码，正式组件验收仍为 NOT_ACCEPTED。

本次按《组件验收口径-条件验收规则》v1.0 完成独立适用性复核：核对的四个组件为 **0 项完全验收 + 0 项条件验收 + 4 项不通过**，不是全平台统计。旧代码 Approved 保留；原隔离/边界影响未知和实际凭据字面量输出不能按纯证据缺口转档。完整逐条登记、未补证事实、对冲计划及 T34 硬闸见新工程 docs/evidence/component-acceptance-register-v1.md 与两份独立报告 review-component-conditional-acceptance-v1.md、review-engine-initial-sender-fix.md。无条件档提案、无负责人批准记录，不涂改历史结论。

本轮 final4 DIRECT 12 项、第二数据库回归 DIRECT 6 项；Surefire 4 PASS + 1 类条件 SKIP 投影，SKIP 未计为通过。旧 candidate53 项和两项 final3 串行核对保留原源码/群组归属，不累加冒充 final4 全量。源码/编译输入/运行捕获/三制品/250生产条目/2988引用与52个Linux关闭记录已限定核对；两个自有数据库保留且会话0、角色NOLOGIN。执行历史 NONCOMPLIANT 与 root 丢失的一次只读查找结束身份继续披露。

后续顺序：首次无会话 CHAT 的真实创建→执行/恢复→状态及持久事件观察，复用已评审初始模型线协议；实际 source684 重绑定后再授权实现。会话历史、公共 token 流、公共取消、默认生产 IAM/D3 和部署均另有前置条件。T31 17项联合链、T32故障/并发/隔离、T33容量和T34签收须逐项覆盖，未覆盖不得生产签收；未来条件档出现功能缺陷立即撤回。T01仍唯一全局完成，T09/T11/T12进行中，T33未启动；不安排工期、不改四契约。


### 2026-10-23 首次无会话 CHAT：I1 闭环与 source688 开发基线

本次覆盖CHAT/FULL首次无会话、两message/零tools/三来源父集合、创建幂等和持久事实观察；只补四保留名gate。inputs四HTTP负例与真实published来源派生snapshot四纯规则负例分开登记。完整F3/F7、会话/二轮、公共token/取消及真实环境仍未完成。
最终源码688，清单SHA256 `2625ce4d7f40fcf40c0d529f8b0d59284ceda1ff40253eca629f9bc75ff18ca7`；相对原688仅3MOD/685same。生产只增加chatSession/chatTurn/sessionHistory/history四个顶层保留名。真实RED后，四GREEN方法均通过：新建与重放各12请求全部403，初建无新增/外发、重放不改变原事实，合法CHAT/TASK/FLOW通过。root核对2752文件身份、721运行文件/686Java21类/158依赖及四制品317生产条目一致；未重跑旧九重点群组。

生产代码C0/I0/M0、Spec Compliant/Quality Approved，仅作为后续隔离开发基线；正式组件保持NOT_ACCEPTED，P6部分早期原输出安全边界UNKNOWN，不能证明全部缺口属于规则§3，亦不认定已泄露。所登记五组件为0项完全验收＋0项条件验收＋5项不通过，不是全平台统计；没有条件档提案/负责人批准。关闭审计准确口径为79实际guard/176有效身份观察，另2metadata/693空身份项，control另列审计后3次真实结束；原81/869记录不涂改。

依据：新工程 `.local/initial-chat-admission-development-baseline.json`、`.local/initial-chat-admission-root-final-qualification.md`、`docs/evidence/review-initial-chat-admission-fix.md`、`docs/evidence/component-acceptance-register-v1.md`。T31/T32/T33及真实IAM/D3对冲按 `.local/initial-chat-admission-joint-acceptance-obligations.md` 逐项执行；T34未覆盖/未批准不得签收，未来条件档暴露功能或安全缺陷立即撤回。T01仍唯一全局完成，T09/T11/T12进行中、T33未启动；默认IAM/D3、公共token/cancel/session不启用，冻结v1.2、四契约及不排工期保持。