# 算法适配器隔离协议实施计划

> **For agentic workers:** 按 executing-plans 逐项执行、独立复核。本文件是只读调查后的计划；实施须先由根任务移交 skill 源码和 Maven target。当前 MCP 作者持有该模块，本轮不实施、不运行 Maven、不启停服务、不派子 agent。

**Goal:** 在固定 Kubernetes Java Client 23.0.0 上补齐算法任务的持久登记、唯一发送、身份核验和有界查询，通过本新工程的有限 HTTP API fixture 与专用 PG 测试库验证。

**Architecture:** 沿用 SkillExecution 的守卫、引擎 claim/submitted、PREPARING 事实和短事务栅栏；AlgorithmAdapter 只经固定 SDK 操作原绑定 Job。K8s API fixture 只回答有限协议请求，不创建容器；技能保存执行事实，引擎仍唯一提交逻辑结果、节点状态和事件。

**Tech Stack:** Java 21 / Spring Boot 3.5.0 / Kubernetes Java Client 23.0.0 / 现有 JdbcTemplate、TransactionTemplate、Flyway、ArtifactStore / JDK HttpServer；不新增依赖，不自行实现 Kubernetes HTTP 客户端。

## 1. 范围、依据与本次证据等级

- 唯一工程根：`G:/207/项目/算法重组工具/代码/agent-platform`。本文文件路径均相对此根；不访问相邻旧工程、宿主 kubeconfig、Docker 或已有集群。
- 已读本工程 `AGENTS.md`、`docs/implementation-brief.md`、`contracts/README.md`、本任务 brief，以及冻结详设算法/恢复相关段落：430–431、995–1006、1055–1074、1115–1141、1333–1341、1747–1757。原四份契约和设计正文不修改。
- 设计不变量：稳定登记幂等键；Job 名确定性生成；创建前绑定；W2 只查原 Job；当前权限覆盖原任务范围；技能只写事实；W3 由引擎幂等补交付。
- 本次亲自执行的只有文件检索/读取、SHA256、JDK21 `javap` 和固定官方源码读取；**没有执行生产适配器、测试、Maven、SQL、服务或集群请求**。下文“现状/缺口”是源码静态结论，“红绿矩阵”是待执行验收，不能写成已通过。
- 工程当前无完整 Git HEAD。实施冻结使用精确自有文件清单和前后 SHA，不用空 git diff 证明范围或通过。
- brief 提及的 `PgStore` 在当前新工程源码中未检到类型、文件或引用；实际持久入口是 AlgorithmAdapter/HttpAsyncAdapter 的 `JdbcTemplate`，SkillExecution/ReconciliationProofs 的 `TransactionTemplate`。本计划不臆造既存 PgStore，也不为此新增仓储框架。

## 2. 当前实现与确定缺口

行号为 2026-10-20 07:51 +08:00 调查基线附近，MCP 作者仍可能更新共享文件；移交后以方法名和最终摘要重新定位。

| 文件/入口 | 已有机制与静态证据 | 本增量需补齐 |
| --- | --- | --- |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/AlgorithmAdapter.java:27–75`，invoke | 显式 API/namespace/serviceAccount、镜像清单、secret_ref；Job 名 `alg-` + SHA256(key) 前40位；先插绑定，再 GET；queryOnly 遇404不创建 | 绑定目前直接写 SUBMITTED，且仅 tenant/call/name/namespace/image；缺 REGISTERED、完整身份、快照、发送 CAS。`on conflict do nothing` 后未读回比对，queryOnly 也会插绑定 |
| 同文件 `:76–145`，Job 构造/create | 非 root、禁提权、drop ALL、不挂载 SA token、资源请求/上限、backoffLimit=0、activeDeadlineSeconds；409 后 GET 同名 Job | 同名/409 均未校验 Job namespace/name/UID、平台归属、原参数/合同；仅 callId label 不能作为完整归属。不能把任意同名 Job 当原结果 |
| 同文件 `:146–165`，轮询/Pod | succeeded>0 后读 Pod，failed>0 抛 RETRYABLE_FAILURE；250ms轮询 | 总 deadline 在 read/create 后才开始；仅 SDK read timeout；failed 计数不等于 Failed 终态条件；无响应大小上限、列表页界限或 Pod owner 核验 |
| 同文件 `:166–183`，日志与DONE | 已用 SDK `buildCall` 取得 InputStream，并经 SkillAdapters.read/ArtifactStore 转存 | 任取首 Pod，未固定 container；DONE 更新仅 tenant/call，无 principal/run/attempt/key/UID 栅栏；日志阶段尚无覆盖整个调用的硬 deadline |
| `.../infra/SkillAdapters.java:64–72,107–126` | asynchronous/bind 仅识别 HTTP SUBMIT_QUERY/CALLBACK；ALGORITHM 单独 invoke；MCP queryOnly 已阻止 tools/call | ALGORITHM 没有复用异步登记/发送恢复栅栏。需新增明确的“需要持久绑定”判断，不能直接改变 asynchronous 的 HTTP 含义，误改 CALLBACK/MCP 分支 |
| `.../application/SkillExecution.java:220–274` | HTTP 异步绑定与 PREPARING 事实同事务先落库，然后引擎 submitted，再外调 | ALGORITHM 在引擎 submitted 后才进入适配器登记；存在尚无本地绑定/事实的 W1/W2 裂缝 |
| 同文件 reconcile `:561–625,671–681,759–762,816–850` | 当前守卫和 requireHistoricalScope 已覆盖对账；ALGORITHM queryOnly；事实终态有原 attempt/快照 CAS | queriedBinding/发送未开始栅栏只覆盖 HTTP 异步；算法成功缺绑定栅栏。所有 Failure 都回 UNKNOWN，导致可信 Job 终态失败不能收敛；成功写库硬编码 SUCCEEDED |
| `.../application/ReconciliationProofs.java:71–100,128–251` | 原 fact→binding 锁序、原代/合同/快照校验、不可变证明；引擎许可未启用 | 算法应复用完整绑定验证；不能让算法404、查询错误或 Job Failed 被包装为 NOT_APPLIED 证明 |
| `.../resources/db/migration/V300__skill.sql`、V307、V308 | V300 tenant/call 主键；V307 已有 principal/run/attempt/version/key/send_started/request_snapshot；V308 PREPARING 隔离 | 复用已有列；算法专属 endpoint/namespace/Job UID/身份摘要仍需持久化。老算法行缺归属，不能按当前配置静默补齐 |
| `.../infra/SkillAdapters.java:230–244`、`.../infra/ArtifactStore.java:144–188,394–426` | outputMaxBytes+1 后转受控产物；ArtifactStore 有64MiB硬上限、PG归属和Schema验证 | 保留这些机制；算法额外覆盖日志小结果路径、元数据响应、总时限，不能将阈值截断正文交给JSON解析并冒称完整成功 |
| `skill-registry/src/test/java/...` | 已读 SkillExecutionTest、AsyncHttpExecutionTest、ReconciliationProofTest、McpExecutionPostgresTest 相关入口；现有算法引用只是构造/mock | 未检到 AlgorithmAdapter 协议专测；已有 HTTP/MCP/回调通过数不能证明算法路径。SkillExecutionTest 使用本工程综合测试库 `agent_platform_v12`，与运行库 `agent_platform_dev_v12` 分开；算法新测试必须独立专库 |

保留当前守卫和审批：输入/输出 Schema、已验 JWT 的 tenant/principal、当前属性交集、原任务范围覆盖、引擎合同/参数/调用归属、写操作确认、DEBUG Mock 零外调、FORMAL 忽略 Mock、受控产物归属及事实唯一提交，全部进入回归矩阵。

## 3. 最小协议与接口边界

### 3.1 登记与原代身份

在原 PREPARING 短事务中执行 AlgorithmAdapter.bind，再插 execution_fact。登记内容复用 V307 列，至少绑定 tenant、principal、run、node、call、原 attempt、skillVersion、contractSha256、原参数摘要、守卫后参数摘要、稳定 key、完整 request_snapshot；metadata 标 `protocol=ALGORITHM`。同时冻结 API 地址摘要、namespace、serviceAccount、image 和确定性 Job 名。明文凭据只在 SecretVault.resolve 后用于 SDK，不进快照、错误、日志或证据。

地址摘要用经 URI 校验的规范 API origin/base path，不含 userinfo/query/fragment；生产 HTTPS，测试仅显式 loopback HTTP；不读取 kubeconfig，不跟随重定向到其他 origin。配置变更不能将原绑定切向另一个 endpoint/namespace。查询原任务仍校验当前镜像/授权限制；当前策略收紧时拒绝采纳，不扩大历史权限。

新增 `persistentBinding(contract)`：ALGORITHM 或原 asynchronous(contract)。保留 asynchronous 的既有 HTTP 语义。SkillAdapters.bind 按类型委派，PREPARING、对账绑定校验、NOT_SENT 栅栏和最终事实绑定条件用 persistentBinding。所有网络在 PG 事务外，锁序仍为 fact→binding。

拟定内部方法边界如下；旧 invoke 重载为 HTTP/MCP 测试保留，ALGORITHM 缺原 attempt 时默认拒绝。不得从“此刻最新 binding”推断本线程原代。

```java
// SkillAdapters，现有 Raw 不变
boolean persistentBinding(Map<String, Object> contract);
void bind(Map<String, Object> contract, Map<String, Object> request);
Raw invoke(Map<String, Object> contract, Map<String, Object> arguments,
           String key, long callId, boolean queryOnly, String runId,
           long expectedAttempt);

// AlgorithmAdapter；仅算法入口增加明确的原代参数
void bind(Map<String, Object> contract, Map<String, Object> request);
Raw invoke(Map<String, Object> contract, Map<String, Object> arguments,
           String key, long callId, boolean queryOnly, SkillAdapters outputs,
           String runId, long expectedAttempt);
```

本轮只接通算法原代栅栏；不宣称已完成 HTTP/MCP 全部 expectedAttempt 改造，也不开放新代 reset/重发。SkillExecution 的首次发送传已核实 request/authority 的原 attempt，对账传 recorded.attempt_id；内部 HTTP 请求体和冻结外部契约不增加用户可伪造的授权字段。

### 3.2 发送、重用与 ACK 未知

1. 先完整读回登记并校验身份/快照；唯一冲突意味着比对既有记录，不能吞掉后续身份冲突。
2. 查询原确定性 Job，存在则先核验身份，再核验/持久绑定 UID，只复用、绝不 POST。queryOnly 全路径禁止插新绑定、POST、DELETE、换名或重建。
3. 首次原 Job GET404且 queryOnly=false 时，仅在引擎 submitted 成功之后，以原代身份和 `REGISTERED AND send_started=false` 条件 CAS 为 SENDING/send_started=true；影响0行只查原 Job，不能绕开或重试 POST。POST 最多一次；显式关闭 SDK 底层自动连接重试和重定向。
4. POST201/200 的正文和409后的 GET 都必须通过完整身份核验；响应 UID 非空。UID 只允许从 null 写入一次或与原值相同；不能覆盖为新 UID。
5. POST已到达而ACK丢失、读响应失败、409后暂时404或进程崩溃，保留原绑定，UNKNOWN；恢复只查同 endpoint/namespace/name。send_started=true 后，即使404也不创建第二次。
6. W1 中登记成功但未开始发送，沿现有 PREPARING/NOT_SENT CAS 阻止旧线程迟到发送；仅产生历史证明，`retryBusinessAllowed=false`。普通404没有未生效证明能力。

`send_started` 是持久的“可能已开始发送”栅栏，不能证明网络确已发送或 Job 生效；fixture 的 POST 接收计数才是本地协议实际接收证据。SUBMITTED 仅表示原 Job 的身份/UID 已被API响应核实；DONE 表示终态观测已留存。引擎 submitted 标记、平台发送栅栏、对端收到、Job完成是不同事实。

### 3.3 Job 与 Pod 身份

Job 的 namespace/name 与绑定逐项一致；metadata.uid 非空，并与已存 UID 相等。创建时保留 call label，另加平台域 annotations 的 `identity-sha256`：摘要正文包含前述完整稳定身份和原 attempt/合同/参数摘要，不含明文身份或参数。规范摘要由本地绑定重算，不信任对端单个 call label。创建 ACK、首次 GET、每次轮询和409复读使用同一校验。

摘要表达的是平台在可信专用 API/namespace 下的归属标记，不是密码学签名。其可信前提是专用命名空间与受控 Job 创建权限；fixture 不能证明生产 RBAC。恢复不比较新工作副本与旧 execution_owner 相等；owner/租约变更不会更换稳定 Job 身份。

使用 SDK listNamespacedPod，`labelSelector=job-name=<name>`、`limit=2`、有限 timeoutSeconds、watch=false。列表 JSON有客户端硬上限；不自动翻无限页。存在 continue、超限、零个可用 Pod或多个成功候选均 UNKNOWN。只有 Pod namespace、非空 UID、controller=true/kind=Job/apiVersion=batch/v1/name/uid 的 ownerReference 与原 Job 一致，且 algorithm 容器终止 exitCode=0，才读取该 Pod 的 `container=algorithm` 日志。匹配标签但 owner错误不得读日志。Job spec 固定单次执行的 completions=1、parallelism=1，保留当前安全上下文、资源上限、backoff=0和activeDeadline。

### 3.4 时限、字节与关闭

- 用 System.nanoTime 计算从首次外部 GET 前开始的统一 deadline，覆盖读/创建/409复读/轮询/Pod列表/日志；每个 SDK call 设置剩余 call timeout，同时显式 connect/read/write timeout，sleep不能越过剩余预算。每个call和日志流在结束/异常/中断时关闭；中断保留线程标记，不声称外部已停止。
- SDK仍负责 URL、鉴权、JSON与模型。仅在其 OkHttp 扩展点加有界 ResponseBody/Source，Job/Pod成功和错误元数据均最多1MiB；Content-Length 可提前拒绝，未知/chunked长度仍逐字节计数；超限不能让 SDK 先整段物化。控制对象/列表数量，避免把服务端limit当内存安全保证。
- 日志沿 SDK buildCall→ResponseBody.byteStream→本地有界流→现有 SkillAdapters.read/ArtifactStore；业务 stdout 必须是一个完整输出Schema可验的JSON文档。小输出遵守 outputMaxBytes，大输出沿已有受控转存，日志总硬上限沿 ArtifactStore.MAX_ARTIFACT_BYTES=64MiB。
- 不使用 PodLog `.execute()` 将正文物化为 String。已读 SDK limitBytes 文档允许近似返回和截断，**本阶段业务结果日志不使用该服务器截断参数**；客户端最多消费硬上限+1探测字节即拒绝并关流，不把截断的合法JSON前缀当成功。tailLines 同样不用于业务结果。测试通过注入较小硬上限验证，禁止制造64MiB以上负载或耗尽探针。
- 任何日志截断/超限/未读完/不完整JSON均不提交 SUCCEEDED，不产生可交付的不完整产物；明确已完整取得但Schema不符按 OUTPUT_INVALID。流中断/无日志/未证明完整性保持 UNKNOWN；成功/失败/超时路径均核验本地响应关闭与线程退出。连接关闭不代表取消算法。

### 3.5 终态、错误和事实采纳

| 可信观察 | 执行事实与后续 |
| --- | --- |
| Complete=True，归属/UID/Pod/完整输出Schema通过 | SUCCEEDED；同一短事务以 fact原代/快照和 binding原代/UID 做CAS，再走原 outbox/引擎交付 |
| Failed=True，归属/UID通过 | FAILED，保留脱敏失败证据和现有 RETRYABLE_FAILURE 语义；表示原Job已知失败，**不表示未生效，不授予重发** |
| failed计数>0但无Failed终态，active/pending、缺status | UNKNOWN；有限查询后返回，不长期占有引擎执行权 |
| Complete与Failed同时True、状态缺陷、错归属/新UID、API/日志读失败、发送ACK未知 | UNKNOWN，审计具体原因；不造 NOT_APPLIED |
| 401/403 | 已有任务或发送可能发生时保持UNKNOWN，记录鉴权拒绝；发送前确定的本地守卫拒绝按NO_PERMISSION失败且零外调 |
| 已核实未发送且NOT_SENT CAS胜出 | UNKNOWN + 不可变NOT_SENT证明；retryBusinessAllowed=false，等待未来引擎许可 |

现有 Failure(boolean unknown) 不能让 reconcile 区分“可信终态失败”与不可信网络异常。最小增加 SkillAdapters 内部 `TerminalFailure extends Failure`，携带限定键的 source 证据（原Job UID/identity摘要/终态条件/来源摘要）；只允许核验后的算法分支构造。execute 保持 FAILED落库；reconcile 在通用Failure前接该类型，构造 FAILED信封，并与成功共用带binding/原代条件的终态CAS。通用异常仍UNKNOWN，不改变其他适配器的语义。

终态 binding 与 execution_fact 必须在同一个短事务原子采纳；网络完成后先锁原fact，再锁binding重验快照/UID，本地原代或事实已改变则只留观测/拒绝旧覆盖。不能先用弱条件把 binding 改DONE、再把其他代事实补成成功。技能与引擎没有跨服务事务；引擎已取消时，可信迟到终态仍是可以留存的执行事实，由原交付协议标迟到并拒绝复活逻辑运行，不能删除外部事实来冒称外部已停止。W3回调失败仅重投已存事实，不调用算法POST；逻辑提交和node_finished仍由引擎负责。

## 4. 精确拟修改文件

不改 POM、admin-common、engine生产代码、原契约或历史报告；不新建接口/工厂层。以下均为**未来实施清单**。

| 动作/文件 | 责任 |
| --- | --- |
| 修改 `skill-registry/src/main/java/cn/agentplatform/skill/infra/AlgorithmAdapter.java` | bind、原代发送CAS、完整Job/Pod核验、SDK超时/字节硬闸、结构化可信失败；保留 `AlgorithmAdapter(JdbcTemplate)`，增加包内测试配置构造入口，生产仍显式环境配置 |
| 修改 `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillAdapters.java` | persistentBinding、ALGORITHM bind、原attempt重载、TerminalFailure；保留HTTP/MCP既有分支、Raw/输出闸 |
| 修改 `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillExecution.java` | 原登记事务覆盖算法、原attempt传播、对账绑定/NOT_SENT/终态失败采纳；成功失败同代CAS、原守卫/审批/Mock/outbox不削弱 |
| 修改 `skill-registry/src/main/java/cn/agentplatform/skill/application/ReconciliationProofs.java` | 复用原身份/快照核验，算法分支核对API/namespace/UID证据；算法404/Failed禁止生成NOT_APPLIED |
| 新建 `skill-registry/src/main/resources/db/migration/V311__algorithm_job_identity.sql` | 新增nullable的 `external_job_uid text`、`external_namespace varchar(63)`、`external_api_sha256 char(64)`、`external_identity_sha256 char(64)`，算法部分唯一约束；实施前重新确认迁移号未被占用 |
| 新建 `skill-registry/src/test/java/cn/agentplatform/skill/infra/AlgorithmApiFixture.java` | JDK HttpServer，固定白名单路由、有限脚本响应、请求计数/顺序、body和headers断言；无容器、watch、exec、proxy、删除 |
| 新建 `skill-registry/src/test/java/cn/agentplatform/skill/infra/AlgorithmProtocolTest.java` | 本地固定SDK真实HTTP；身份/状态/边界/关闭断言，PG可mock仅限与纯协议无关的行操作，不能宣称持久性 |
| 新建 `skill-registry/src/test/java/cn/agentplatform/skill/infra/AlgorithmTestDatabase.java` | 仅本新PG25432上的两个明确算法测试库；返回真实JdbcTemplate/事务，Flyway迁移，不读开发库数据 |
| 新建 `skill-registry/src/test/java/cn/agentplatform/skill/application/AlgorithmExecutionPostgresTest.java` | 专用PG + 真实K8s HTTP fixture + 有限engine HTTP authority fixture；身份、发送并发、W1/W2/W3、守卫/产物/事实 |
| 新建 `skill-registry/src/test/java/cn/agentplatform/skill/infra/AlgorithmMigrationPostgresTest.java` | V310→新增迁移与空库全迁移、老算法隔离、HTTP绑定/证明兼容 |
| 新建 `scripts/test-algorithm-protocol.ps1` | 只验证显式配置和运行上述新套件；不启动数据库/服务、不读取/打印宿主凭据、拒绝非白名单测试DB |
| 新建 `scripts/algorithm-protocol-live.mjs` | 根任务后续受理实际engine+skill隔离进程时使用的有限API fixture及W1/W2/W3纵切计数；只操作本工程显式新端口/运行身份，不自行接管当前服务 |
| 新建 `docs/evidence/algorithm-protocol-report.md` 与 `docs/evidence/algorithm-protocol-source-sha256.json` | 红/绿/回归/迁移/外部接收计数、固定文件摘要、边界；实施时创建，不回写原报告 |
| 修改 `docs/open-source-references.md` 的原K8s条目 | 仅在实施验收时追加本固定SDK实际已读方法/许可证和本增量边界，不宣称全SDK对照 |

### 4.1 迁移兼容要求

增量迁移不重写 V300/V307/V308/V310，不对 HTTP 或旧行加全局 NOT NULL。新算法行由应用完整写入，部分唯一索引只作用于 `metadata->>'protocol'='ALGORITHM'`：禁止同一 API摘要/namespace/name 被多调用占有，以及同一 API摘要/namespace/非空UID被多调用占有。唯一冲突必须读回核验并安全拒绝，不能用ON CONFLICT吞掉身份不一致。

既有算法行缺 key/principal/attempt/完整快照/UID，**不从当前Actor、环境或Job名字猜出回填**。保留原行/事实，查询返回可审计的LEGACY_BINDING未知原因，零POST；需后续受控证据补全或人工闭环。老HTTP REGISTERED/SENDING/SUBMITTED/DONE/NOT_SENT与不可变proof摘要完全保持。迁移测试分别用全新库 `agent_platform_skill_algorithm_test` 和升级库 `agent_platform_skill_algorithm_migration_test`，不清理/搬迁开发运行库。

## 5. 有限HTTP fixture 与关键红绿矩阵

fixture只监听127.0.0.1随机端口；仅4类路径：POST `/apis/batch/v1/namespaces/{ns}/jobs`、GET 同路径加`/{name}`、GET `/api/v1/namespaces/{ns}/pods`、GET 同路径加`/{pod}/log`。生产轮询继续用GET完整Job，以每次同时核验身份；已核对的 SDK readNamespacedJobStatus 可用，但本最小实现无需另加status路由。

POST回包包含apiVersion/kind/metadata.namespace/name/uid/平台annotations；GET脚本只在 Running、Complete、Failed、404、409冲突恢复、有限拒绝/断连之间切换。响应体和计数有固定上限；延迟仅几十至几百毫秒，有限慢片段验证deadline。所有等待用有上限Latch/屏障，不靠长sleep或负载试验。

关键断言按行为和持久证据判断，而非测试数量：

| 场景 | 旧版预期红/风险 | 目标绿必须同时断言 |
| --- | --- | --- |
| 首次新建 | 无完整登记/UID | 第一个POST到达前真实PG已存在REGISTERED/PREPARING；submitted先于发送；一次POST、同名、UID和完整归属落库 |
| 登记事务失败 | 算法无原子占位事实 | 绑定/事实一起回滚，POST=0；不向outbox交付PREPARING |
| W1 submitted被拒或ACK迟到 | 缺NOT_SENT发送仲裁 | NOT_SENT CAS与旧发送竞争只许一个胜出；未发送证明胜出时POST=0，重复execute不重发 |
| 同call/key两线程/两实例 | 依赖同名409，可能双POST | 共享PG发送CAS最多一胜；最多一次POST；失败者只GET并重用，不新Job |
| ACK丢失 | 绑定无发送事实/UID归属 | peer接到POST后断回包；原事实UNKNOWN；新适配实例/对账GET原Job后收敛；POST总数仍1 |
| 同名已存在/POST409 | 任意同名Job被采纳 | 相同完整身份才采纳，第一次可信UID只能写一次；错误identity/namespace/合同/参数任一项均不读日志、不成功 |
| UID被替换/跨租户归属 | 同名新Job或Pod可混入 | 原UID已绑定后新UID→UNKNOWN；其他tenant/principal/run/attempt绑定不发送、不改行、不返回结果 |
| queryOnly原Job404/缺绑定 | 会插SUBMITTED占位 | 原PG状态不因query改变身份；POST=0，无绑定时也不插；404仍UNKNOWN、retryBusinessAllowed=false |
| Running/缺status/failed计数 | failed>0误判终态 | 有限查询后UNKNOWN；仅Failed=True才终态失败；无无限轮询 |
| Complete/Failed/矛盾终态 | 仅succeeded计数、失败对账不收敛 | 可信Complete与完整输出→SUCCEEDED；可信Failed→FAILED且无NOT_APPLIED；矛盾条件→UNKNOWN |
| Pod同标签但错误owner | 首Pod直接读日志 | Job UID/name/controller/apiVersion逐项核验；错owner日志GET=0；零/多候选/continue安全未知 |
| 日志小/转存/超限 | 无统一硬deadline | UTF8按字节；正常完整JSON通过；超过内存阈值按原ArtifactStore转存；注入4KiB总限额时只读取≤4097探测字节即拒绝，无不完整成功产物 |
| 元数据/chunked错误正文 | SDK可先整段物化 | 用注入1KiB预算、≤1025字节响应验证成功/错误/未知长度均拦截；不构造大对象/堆耗尽载荷 |
| 慢POST/GET/日志连续少量字节 | read timeout不足以总截止 | 总耗时在设定预算+明确调度容差内结束（例如200ms预算/≤1000ms夹具上界）；无迟到POST、无后台继续读；POST可能已接收时仍UNKNOWN |
| 当前权限收窄/无审批/Mock | 原守卫可能被新路径绕开 | 当前范围不覆盖历史时API请求=0；无审批POST=0；DEBUG FIXED/LAST_REAL算法API=0；FORMAL不能用Mock |
| W3事实已存但引擎回调失败 | 缺算法专测 | 原事实/UID不变；恢复只重投事实、引擎call只提交一次，POST=1；取消/旧代结果不可复活运行 |
| 终态采纳与事实变化并发 | DONE弱更新/无算法bindingFence | 事实已终态或原代/绑定已变时旧观测不能更新新代；不污染新UID/DONE，不交付旧结果 |
| 老算法升级/HTTP兼容 | 老记录缺身份 | 空库与V310升级均通过validate；旧算法安全未知且零创建；HTTP绑定、回调和proof原行摘要不变 |

每个红测先在原行为基线运行，断言上述不变量。若新增测试构造入口初次仅编译红，先只加入兼容配置构造入口，再运行取得**行为红**；编译缺方法不能算协议缺陷的红证据。绿测检查fixture路由、接收计数、真实PG行和交付计数，不能把mock verify当实际外部接收。

## 6. 实施顺序与依赖

### Task A：取得移交、固定基线并建立可失败夹具

- [ ] MCP作者完成返修、独立审查、释放源码/target；记录最终共享 SkillRules/Schema/Mcp 入口摘要。本计划不替代该审查，不复制历史242项结论。
- [ ] 重新核对 V311 可用号；记录第4节文件现有/不存在状态与SHA。配置只来自本工程显式环境，检查凭据存在，不打印其值。
- [ ] 写 AlgorithmApiFixture、专库 helper 和最小原子登记/错归属/queryOnly行为断言；加入必要构造接缝后先取得行为红，保存命令/日志。

### Task B：登记、发送栅栏与迁移一起落地

- [ ] 先写专用PG的登记回滚、并发单POST、submitted拒绝、ACK丢失、旧行升级红测。
- [ ] 同一改动集修改 AlgorithmAdapter、SkillAdapters、SkillExecution、ReconciliationProofs 和新增迁移；不能只改适配器而仍绕过PREPARING，或只改asynchronous导致HTTP/MCP语义扩大。
- [ ] 补原attempt传播和所有PG写条件；校验请求与绑定并持久UID；跑本阶段定向绿。此阶段不开放新代发送或取消。

### Task C：身份、终态与有界I/O一起验收

- [ ] 写Job/Pod owner/UID、可信Failed、矛盾状态、有限超限/超时/关闭红测；已有Job归属不足时不得临时以call label放行。
- [ ] 仅用固定SDK及其OkHttp扩展点补齐身份/终态/硬闸；可信失败与成功同代CAS必须同时改执行和对账路径。
- [ ] 本地资源退出、无迟到发送与所有关键矩阵绿后，跑HTTP异步/回调/证明/MCP和完整skill回归；最终共享源码冻结后才记录整轮，期间源码变动需重跑受影响范围。

### Task D：独立审查与实际进程纵切

- [ ] 对冻结清单做独立规格/并发/资源边界审查；修复后复核。记录的是本增量结论，不能代签全T11。
- [ ] 根任务在其明确选择的新工程隔离端口，以实际engine+skill进程和有限K8s API fixture执行新建、ACK未知→queryOnly、W3回调恢复；记录PG事实、外部POST总计数和唯一逻辑提交。fixture不是K8s、没有算法容器。
- [ ] 原设计/依赖边界、迁移、完整模块日志与前后SHA一致，生成新报告；没有实际进程证据时只可称模块/协议测试通过。

### 与其余工作的依赖关系

| 工作 | 顺序/是否本轮一起改 |
| --- | --- |
| MCP共享Schema预算/正则/关闭返修 | 必须先释放skill源码和target，再实施；算法不能覆盖其防护和queryOnly修复 |
| G5 RunScheduler返修/公平分池 | 与本算法协议逻辑可独立审查；真实engine纵切用其最终冻结版。算法UNKNOWN及时返回，不能用长轮询占有节点租约；本轮不改engine调度生产代码 |
| 算法原attempt栅栏 | 本轮与发送、UID、事实采纳一起完成，防止旧查询写错当前绑定 |
| HTTP/MCP全链expectedAttempt、NOT_APPLIED单次许可 | 后续独立增量，启用新代前必须全部完成：引擎原子许可/审批复核、技能旧事实与绑定归档、全适配器原代参数、迟到线程CAS、累计计量。原callId/key不变；确定性原Job如何处理必须另验，不以换随机Job名绕过旧副作用 |
| 取消三态 | 后续engine+skill协同：受理/外部停止/效果撤销分别持久记录。当前中断/关流/JobFailed不表示停止或撤销；本轮无DELETE。未来K8s删除也只代表受理，停止需可核验Pod/Job证据，撤销另需业务证明 |
| 人工UNKNOWN闭环、真实K8s/RBAC/容器/生产IAM、容量 | 单独验收；均不是本地协议矩阵的通过推论 |

## 7. 拟执行命令与判定

以下命令**本轮未执行**。工作目录固定为上述新工程根；只有根任务释放Maven target后才运行。JDK路径来自本工程既有脚本；凭据由当前会话安全注入，不打印.env，不在命令行传明文。不要调用会启停服务的 scripts/test.ps1 作为本任务快捷入口。

```powershell
Set-Location -LiteralPath 'G:/207/项目/算法重组工具/代码/agent-platform'
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
$algorithmRoot=(Get-Location).Path
if ([string]::IsNullOrWhiteSpace($env:AGENT_DB_PASSWORD)) { throw '缺少本新工程PG测试凭据' }

# 新脚本只运行算法专测；专库由helper按固定白名单创建，不启动PG。
& ./scripts/test-algorithm-protocol.ps1

# 等价定向命令；-am中无匹配测试模块允许为空，但报告必须包含3个算法套件，PG套件不得跳过。
& mvn.cmd -B -ntp -pl skill-registry -am test "-Dmaven.repo.local=$algorithmRoot/.tools/m2" '-Dtest=AlgorithmProtocolTest,AlgorithmExecutionPostgresTest,AlgorithmMigrationPostgresTest' '-Dsurefire.failIfNoSpecifiedTests=false'

# 不变性与既有路径回归；只在独占target、凭据/测试资源均已由根任务准备后运行。
& mvn.cmd -B -ntp -pl skill-registry -am test "-Dmaven.repo.local=$algorithmRoot/.tools/m2" '-Dtest=AsyncHttpExecutionTest,CallbackHttpTest,ReconciliationProofTest,SignedCallbackTest,McpAdapterTest,McpExecutionPostgresTest' '-Dsurefire.failIfNoSpecifiedTests=false'
& mvn.cmd -B -ntp -pl skill-registry -am test "-Dmaven.repo.local=$algorithmRoot/.tools/m2"
& node ./scripts/verify-contracts.mjs

# 根任务另准备实际隔离engine/skill进程；runner只访问显式目标并输出脱敏计数。
& node ./scripts/algorithm-protocol-live.mjs
```

每条命令检查退出码；所有阶段保留实际FAIL/PASS、执行时间、tests/errors/failures/skips和精确来源清单。定向绿不能替代最终模块整轮。现有部分旧套件使用开发测试库，完整回归沿项目既有隔离约定由根任务安排；本新增算法套件不得沿用该开发库地址，不重置其他测试数据。

验收标准：矩阵全部有可追溯行为断言；原绑定/唯一发送/身份与UID/终态失败/字节和时限/当前守卫/迁移兼容全部成立；没有跳过PG项；最终源码与整轮日志绑定；独立审查结论明确；实际engine+skill有限fixture纵切只接受一次逻辑结果。任何缺项仅标该项待验，不用累计测试数关闭T11/G2。

## 8. 固定SDK实际读源记录

本地 `.tools/m2/io/kubernetes/client-java-api/23.0.0/client-java-api-23.0.0.jar` SHA256：`2bba28b574ea6c5379db349c1f7a15d3c2cbd55c26e18c7eb0addfd7c1d8a5a8`。用本工程JDK21 javap实际核对BatchV1Api、CoreV1Api、ApiClient和两个Pod请求builder。父POM固定23.0.0、scm tag v23.0.0，声明Apache-2.0；未读取宿主Maven配置。

本地无该SDK sources.jar，故只读官方固定来源：

- [Maven Central 23.0.0源码包](https://repo.maven.apache.org/maven2/io/kubernetes/client-java-api/23.0.0/client-java-api-23.0.0-sources.jar)，仅内存解包读取；包SHA256=`7b0da48b1e4eb3843a8cb375066b701e38d0ec52af35d4d728551c2c76201929`。
- [官方v23.0.0源码](https://github.com/kubernetes-client/java/tree/v23.0.0)，annotated tag对象 `ced342bdaa87a16891137b8c4628cd1d77d310e0`，解析commit `1042f25043f297d3e41569387ae6dd9152e176ad`。下列3文件的Maven源码与官方tag文件SHA相同；没有因User-Agent字面值含SNAPSHOT而升级或替换依赖。
- [BatchV1Api.java](https://github.com/kubernetes-client/java/blob/v23.0.0/kubernetes/src/main/java/io/kubernetes/client/openapi/apis/BatchV1Api.java)：createNamespacedJob builder/POST、readNamespacedJob builder/GET、readNamespacedJobStatus builder/GET及execute返回V1Job；检查create的namespace/body非空验证与BearerToken。SHA=`ebff6ca64b3851f77a15b54ea6e4d97dfbfc580f80a3b5585f680c1c1145b538`。
- [CoreV1Api.java](https://github.com/kubernetes-client/java/blob/v23.0.0/kubernetes/src/main/java/io/kubernetes/client/openapi/apis/CoreV1Api.java)：listNamespacedPod的labelSelector/limit/continue/timeoutSeconds，readNamespacedPodLog的container/follow/limitBytes/tailLines/buildCall/execute；检查GET路径、query序列化和日志String物化路径。列表limit可被服务器忽略，日志limitBytes近似截断，因此两者不能代替客户端硬闸。SHA=`0517ea99bf3c830372574a5108476188d80e7d892766e22bc811cc9cf7c1bc1d`。
- [ApiClient.java](https://github.com/kubernetes-client/java/blob/v23.0.0/kubernetes/src/main/java/io/kubernetes/client/openapi/ApiClient.java)：构造/认证、setBasePath、get/setHttpClient、connect/read/write timeout setter；确认timeout setter构建OkHttp子client，可以在SDK正式入口配置call timeout/重试/重定向和有界响应。SHA=`58e38b725e93934445bb0d3ab7fb967722dfe2360a0cd5d3b2221825247a43bb`。
- [V1OwnerReference.java](https://github.com/kubernetes-client/java/blob/v23.0.0/kubernetes/src/main/java/io/kubernetes/client/openapi/models/V1OwnerReference.java) 的apiVersion/kind/controller/uid，以及 [V1JobStatus.java](https://github.com/kubernetes-client/java/blob/v23.0.0/kubernetes/src/main/java/io/kubernetes/client/openapi/models/V1JobStatus.java) 的conditions/failed/succeeded getter；仅核对相关模型入口，未声称读全部模型。
- [官方LICENSE](https://github.com/kubernetes-client/java/blob/v23.0.0/LICENSE)：Apache License 2.0，读取许可标识并与本地固定父POM一致核对；LICENSE SHA=`b40930bbcf80744c86c46a12bc9da056641d722716c378f5659b9e555ef833e1`。项目继续直接依赖SDK，若未来复制上游实现需保留许可/NOTICE，本计划不复制上游实现。

源码摘要按收到的UTF8文本计算，二进制包摘要按原字节计算；这是读源记录，不是签名、供应链审计或运行验证。未下载/写入生产源码、POM或旧报告。

## 9. 调查基线与未覆盖外部条件

| 相对文件 | 调查SHA256 |
| --- | --- |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/AlgorithmAdapter.java` | `77ea491eeac1ebfb08469208d04856f3646ecd12d265195a72fce71d5c197a40` |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillAdapters.java` | `2e46370616994cdf0a95c3c24f4fb67cf499df31fa60e39d5c664b4f4efc1c88` |
| `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillExecution.java` | `e446ad0929a33472cf1ee326910d094fac0c0bd54b7a51db1a19667b4cce03fe` |
| `skill-registry/src/main/java/cn/agentplatform/skill/application/ReconciliationProofs.java` | `fceb53d0d04360598e640d750fde05a8cb124dbf2cfe440a5fea7de630bd412d` |
| `skill-registry/src/main/resources/db/migration/V300__skill.sql` | `e8c764d3d3b2747e54567ba0750eef1b8638b6e8c86469f8c37ec1a59b65c9d2` |
| `skill-registry/src/main/resources/db/migration/V307__http_async_binding.sql` | `c32087496295f94f91e47a65602c1d77ef5d56cbbbeaa9ab67d96304a3b35824` |
| `skill-registry/src/main/resources/db/migration/V308__async_preparation_delivery.sql` | `4a2229c9c0df0bc95c6e782306abf01fc60a7b7bcb3f9a825be6b0ee15390daf` |

本计划可在没有专用K8s环境时实施上述隔离协议增量。它不能验证API server/admission真实行为、namespace/RBAC、网络策略/TLS证书与生产secret轮换、镜像拉取、容器启动/算法计算、节点调度/资源强制限制、Job控制器终态/GC/Pod清理、真实日志完整性与供应商业务副作用。多副本容量、公平调度完整G5、生产IAM、外部停止/效果撤销、NOT_APPLIED授权新代以及人工UNKNOWN闭环均仍独立验收。任何隔离fixture绿测不得表述为真实K8s运行、算法容器成功、生产验收或完整T11完成。
