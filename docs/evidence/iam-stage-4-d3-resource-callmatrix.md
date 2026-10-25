# D3/E 实际资源与调用矩阵（只读分析，2026-10-20）

## 状态与覆盖边界

本报告供 root 作接口裁决及后续实施交接，不是 D3/E 实现或验收。只读唯一工程内 gateway、engine、skill、model、monitor 生产 Java 和对应 SQL；未读正在修改的 common/app 候选，未运行 Maven/npm/测试、未访问服务/数据库/进程，未改四契约。已读 AGENTS.md、implementation-brief、contracts/README 及指定三份 IAM 交接文档。下列“事实”来自源码/迁移，“建议”均未实现、未运行验证。app 的真实 metadata SQL、当前发布版本摘要和 D2 终稿需所属实施者补核，不能从 engine 消费字段反推已经可信。

重要结论：已有业务父子锚点可以复用，但没有发现已实现的 operation/grant→run→child 授权持久链。本轮在 engine/skill/model/monitor 迁移中检索 initial_ceiling、run_authority、material_scope、operation_id、grant_id 无匹配；这是限定目录的源码结果，不是数据库状态证明。C exact ResourceBinding 行为按 root 已核结论接受，不重读其候选，不提出放宽。

所有源码路径均相对工程根 `G:/207/项目/算法重组工具/代码/agent-platform`。表中别名精确展开如下，冒号后为此次读取的实际行号：

| 别名 | 文件 |
| --- | --- |
| GW | platform-gateway/src/main/java/cn/agentplatform/gateway/api/GatewayController.java |
| GI | platform-gateway/src/main/java/cn/agentplatform/gateway/application/IntegrationAccess.java |
| GR | platform-gateway/src/main/java/cn/agentplatform/gateway/domain/Routes.java |
| RC | engine-runtime/src/main/java/cn/agentplatform/engine/api/RunController.java |
| RS | engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java |
| TS | engine-runtime/src/main/java/cn/agentplatform/engine/infra/TraceStore.java |
| TC | engine-runtime/src/main/java/cn/agentplatform/engine/infra/ToolCalls.java |
| RT | engine-runtime/src/main/java/cn/agentplatform/engine/infra/RuntimeClients.java |
| ME | engine-runtime/src/main/java/cn/agentplatform/engine/application/ModelExchange.java |
| AD | engine-runtime/src/main/java/cn/agentplatform/engine/application/AgentDriver.java |
| CS | engine-runtime/src/main/java/cn/agentplatform/engine/application/ChatSessions.java |
| SC | skill-registry/src/main/java/cn/agentplatform/skill/api/SkillController.java |
| SE | skill-registry/src/main/java/cn/agentplatform/skill/application/SkillExecution.java |
| SK | skill-registry/src/main/java/cn/agentplatform/skill/application/SkillCatalog.java |
| AS | skill-registry/src/main/java/cn/agentplatform/skill/infra/ArtifactStore.java |
| MC | model-gateway/src/main/java/cn/agentplatform/model/api/ModelController.java |
| MS | model-gateway/src/main/java/cn/agentplatform/model/infra/ModelStore.java |
| ML | model-gateway/src/main/java/cn/agentplatform/model/application/ModelCalls.java |
| EV | monitor/src/main/java/cn/agentplatform/monitor/application/EvaluationService.java |
| ST | monitor/src/main/java/cn/agentplatform/monitor/application/StatisticsService.java |

## 一、主要入口与实际版本解析

这里的“建议根资源”是业务语义方向，并非新定 ResourceBinding 字符串协议。读/管理 run 的新请求和创建 run 的原 operation 是不同请求，不允许以读取 operation 复活原执行 operation。

| 实际入口/调用点 | 当前可信来源、版本事实 | 建议根 / 实际 child；目前缺口及读 payload 前门槛 |
| --- | --- | --- |
| GW:34 的 ANY `/admin/**`、`/v1/**`；GR:7-37 分服务 | 本机仅有已验入口身份、路径、query/body；没有业务 PG。GW:41-51 直接从 Actor 发旧内部票。路径选择不证明版本。 | 必须先精确 method/template/action 分类，再解析真实域对象。gateway 通配 HandlerMethod 仅门户/集成专用 ingress 登记；不能变通配普通服务 capability。 |
| RC:35 POST `/admin/apps/{code}/runs`、`/v1/apps/{code}/runs` | RS:240-261 用 `versionNo` 调 app `/internal/apps/{code}/versions/{n}`，否则 `/published`。RS:363-406 保存真实 appVersionId/versionId 与 version_refs；前端 versionNo 是选择器，不是可信摘要。 | 创建操作根可为 app 的确切发布版本；child 为新 run、冻结 skill/model。首次只有 code，缺可信 metadata-first 解析；现 app 响应已含执行 snapshot，不能先取完整 snapshot 再声称“读前检查”。 |
| RC:29 POST `/admin/debug-runs`；RC:40 `/internal/evaluations/run` | RS:242 取 draft snapshot；243-260 取 draftRev/candidateSha256 或 frozenCandidate；RS:391-397 分别保存 debug_snapshot/eval_candidate。 | 根应为实际冻结 draft/candidate 身份及摘要；draftRev 或请求自报 hash 不能单独成立。debug run 不能硬填一个 app published version。当前源真实性与版本查询需 app 作者补核。 |
| RC:53 GET `/admin/runs/{id}`、`/admin/debug-runs/{id}/status`、`/v1/runs/{id}`；RC:58/63 resume/cancel；RC:72/83 variables/prompt | TS:31-40 SQL 按 public_id+tenant+principal 查 run，但 `select *` 同时读取 snapshot/inputs/output。RC:89 直接读 attempt request_snapshot/response/checkpoint。 | 本次请求根可以是有实际冻结来源的 run metadata；child 为 node/attempt/material。需要窄字段 run metadata 查询，再授权同快照 payload；不能直接把 TS.run 当 metadata 查询。三个 status 别名须保留各自 integrationAllowed。 |
| GI:149-160 集成 `/v1/runs/*` 前置归属检查 | 当前先 GET `/v1/runs/{runId}` 得完整业务 status，再检查 appCode；只有 runId 时仍用 Actor 出票。 | 这是具体 bootstrap 环：取 root binding 前已需受保护下游读。应替换为仅 owner/app/version metadata 的独立机制，而非为该 GET 填通用 hash。须先核 tenant/principal，再核 credential 绑定 app。 |
| engine/api/ChatSessionController.java:18/23/28 的 create/get/turn | CS:27 首次取 app published；33-40 保存 session.snapshot；44-59 get 返回版本号/轮次；67-85 turn 用原 session snapshot，并对新轮次检查当前 app 可用性。 | create 根为确切 app-version；后续 session/turn 使用 session 已冻结来源，不能把“当前 published”重新锁到旧 session。PG session→turn 为真实关系，turn→run 为字符串投影，需受理链补强。 |
| SC:46/51/57/62/75/80/89/98/107/113 管理、版本、连接/验收/MCP discovery | SK:69-121 查 skill/skill_version；85-86 返回 sha256/digest；239-257 draft 可改；已发布 immutable 由 V302 支持。 | 精确版本执行/测试应绑定 skill version id+contract sha；draft 改动需重验。列表/新建作用域是 tenant 中真实 skill 集合/创建许可；没有现成集合 revision，不可虚构 `version=1`。MCP discovery/连接测试会出站，不能因名字含 metadata 免执行授权。 |
| MC:25-75 provider/entry CRUD、test、rotate、quota | provider 有 config_rev（MS:98/112 等）；entry 本身可更新（MS:215），不是每个入口都有 immutable binding。 | 管理根应分别为 provider/entry 的受信配置身份与实际 revision/摘要；create/list 是集合权限，须单独裁决。不能将所有管理入口都假扮 model-binding 执行。 |
| MC:85/90 model-bindings freeze/validate | MS:334-355 从实际 entry/provider 生成无 secretRef snapshot，canonical sha，落 binding_snapshot；366-391 用 tenant+id+sha 读取 snapshot，实时解析凭据引用。 | freeze 是建立绑定的动作，不是已有绑定的纯读；需先核 entry/provider metadata 与当前授权。validate 也会读取 snapshot/配置。当前实现无 engine parent 校验。 |
| EV:104-145 创建评测；monitor/api/EvaluationController.java:18/29/35/40 | 获取 app candidate 并比 candidateSha256/draftRev；EV:172-200 保存 dataset cases+sha 与 report candidate；315-342 恢复读取二者。 | 根可为确切 candidate/report；child 为 dataset、case run。datasetVersion 仅名称，需要 PG sha；读取 report/cases 前需 metadata/scope 检查。恢复若后台执行需新 DELEGATED，不能继承旧门户寿命。 |
| ST:78-102 `/admin/statistics`；235 run detail；monitor/api/StatisticsController.java:30/35/40 snapshots | engine 返回 runRefs 后向 model POST `/internal/statistics/query` 携 runIds；ST:148 queryHash 是过滤条件摘要；206 直接读 report。 | 聚合根是有明确定义的 tenant/query scope 或持久 snapshot；runIds 是查询目标集合，不是自动授权集合。queryHash 不是真实版本摘要。各条 run/material 仍需 scope 判权；当前没有可直接复用的集合版本。 |
| engine/api/TestInputController.java:21-50 debug-input CRUD/promote | engine/application/TestInputService.java:82 调 app draft；245-314 使用冻结 promotion；infra/TestInputClient.java:28-33 调 `/internal/app-drafts/{id}` 与 `/internal/datasets/promotions`。 | 根 debug_input 的实际 revision、child 为 dataset promotion。源输入 hash 不能替代 app draft 归属/版本；monitor 校验源字段与输入 hash（DatasetService:76-122）不等于已验证 engine PG 关系。 |
| skill/api/ArtifactController.java:18/23 download | AS:449-455 先 PG owner+state+expiry+可选run匹配；496-507 后才 MinIO getObject。V306 有 artifact sha/size。 | 可复用的先 metadata 后对象读取结构；根 artifact 或合法 run 下 artifact child。需加历史 scope/真实字节 digest/current 及 lease，不能仅 URL/runId 证明权限。 |

列表/创建/publish precheck、graph validate、OpenAPI import、分类/模板、api-docs 等不是同一种 app-version 执行动作。此报告未读取 app 当前候选，因此 app CRUD/发布/模板/分类的真实 SQL 及集合 revision 尚未签收。不得据上述矩阵声称“全部 handler 已覆盖”；启动时仍须以实际 Spring runtime mappings（含 Actuator）核对。

## 二、关键跨服务调用及父子关系

| 实际发送点 | 已有业务锚点及真实 child | 可复用与缺少部分 |
| --- | --- | --- |
| RS:773-775 GET skill code+version；RS:866-868 GET prompt code+version | run snapshot 内 config.tools 固定 skillVersionId/contract/guard（RS:804-820）；prompt 在 snapshot 固定。 | 有真实版本引用；读取完整技能合同/模板前还需绑定已验根，app→依赖 relation 必须来自权威冻结记录。初始 run 尚未落库时不能引用不存在的 run_relation。 |
| RS:694-709 POST `/internal/model-bindings/freeze` | DEBUG 缺 ref 时冻结真实 entry；非 DEBUG 缺 ref 则拒绝（690-692）。 | 可复用真实 freeze；须解决 draft→modelEntry 的关系元数据和变更竞态。不能把 modelEntryId 自报视为父应用授权。 |
| AD:279 注册 TC；AD:292 POST `/internal/skills/{code}/execute` | TC:32 校验 node belongs run；47-64 写 tool_call，含 version/argsDigest/attempt/key；71-89 从 run frozen tool 取 contract hash。SE:149-163 再由 engine claim 对比 attempt/version/code/argsDigest/node/run/key。 | 已有强业务核对路径；缺 operation/grant→run 锚点与 metadata-only 投影。TC:533-578 返回 mock_snapshot、result、arguments，并查询 snapshot，所以现 GET/claim DTO 不是安全的纯 metadata。SE:56 在取 registration 前已读完整 skill contract，应调整检查顺序。 |
| ME:161/175/211 projection measure | MAIN invocation 已存 source_request（ME:265-275），但回调 measure 请求是投影的 r；SUMMARY measure 在独立 summary invoke 前。 | 不能只拿 final_request hash 核所有 measure；需持久/证明每个实际投影/summary measure 的子请求 metadata，或在发送前建立窄 invocation-material 记录。不能使用用户任意 body hash 自洽。 |
| ME:309/319 chatStream；RT:83-99 HttpURLConnection POST `/internal/model/chat/stream` | ME:381-414 锁 invocation，冻结 final_request、runId、attemptId、invocationRef，记录 DISPATCHED/sdk_attempts，再发网络。 | 可复用 source/final 冻结与唯一 invocation 轴。model 当前 MS:375 只查自己的 binding，不核 engine invocation→run→binding。需新窄 metadata 关系查询/证明。 |
| SE:57/127/150/274 → engine tool read/approval/claim/submitted；SE:1165 fact | TC:178-201 join run 核 tenant/principal，锁顺序 run→node→call；现审批 ApprovalService:96 取冻结 confirmRoles，135/184 记录 approvedBy。 | 保留这些核验，但业务表关系不自动等于 authority 可跨资源出票。claim/approval 与原执行 operation 同根可转发；read payload/审批结果需对应 scope。批准仍核持久 approvedBy 与冻结角色，无 ADMIN 自动替代。 |
| engine/infra/ExecutionRecovery.java:61 GET `/internal/skill-facts/{id}` | skill.execution_fact 保存 tenant/call/principal/run/attempt/version/contract hash/args digest；SE:553-582 fact 生成 proof。 | 恢复事实有来源，但事实 proof 含结果，不是 metadata-only；worker 先取持久 grant/current/initial ceiling，开启新 DELEGATED，再读取/采用事实，不能构造 SERVICE Actor 代替。 |
| EV:237-254 POST engine `/internal/evaluations/run` | report 持久 candidate 与 dataset；每个请求携 candidateSha256、datasetVersion、frozenCandidate。 | body parentIds/candidate 值一致不能证明调用属于合法 report operation；应由 monitor 可信受理/冻结记录提供 report→case→candidate 关系。 |
| ST:82/99 engine statistics→model statistics | engine runRefs 是已有聚合来源，model 回包被限制为这些 ids（105-106）。 | 返回集合一致性可复用，但不能替代跨域每项授权；必要的 query snapshot/集合版本是新缺口，不应随便命名常量。 |

可核 SQL 锚点（仅业务关系，不含 IAM 授权证据）：

- `engine-runtime/src/main/resources/db/migration/V200__engine.sql:2` run 有 tenant/principal/app_version_id/snapshot/version_refs；`:17` node FK run，`:24` attempt FK node，`:29` tool_call FK run/node/attempt，`:35` run_event FK run。app_version_id 不在本迁移中建立跨服务 FK。
- `engine-runtime/src/main/resources/db/migration/V203__engine_frozen_call_contract.sql:1` 增 tool_call.skill_contract_sha256。TC:71-89 在冻结工具中匹配后写值。
- `engine-runtime/src/main/resources/db/migration/V205__engine_model_projection.sql:1` invocation FK run/node，source/final request；`V213__engine_model_output_policy.sql:2` 增 output_attempt_no，`:6` 唯一键 node/round/kind/outputAttempt；`V211__engine_controlled_model_provenance.sql:2` source_invocation_id FK。
- `engine-runtime/src/main/resources/db/migration/V208__engine_chat_sessions.sql:1/10` session snapshot 与 turn FK；run_public_id 是字符串而非该迁移中的 run FK。`V214__named_test_inputs.sql:2/12` debug_input revision、promotion request_sha256/payload。
- `skill-registry/src/main/resources/db/migration/V300__skill.sql:8/23` skill_version 与 execution_fact；`V302__skill_immutability.sql:5/13-17` 发布后不可改和同tenant FK；`V305__controlled_contract_digest.sql:1-3` fact合同摘要；`V306__controlled_artifacts.sql:2` artifact owner/run/call/sha/size，run/call 为业务关联列，并无跨服务 FK。
- `model-gateway/src/main/resources/db/migration/V301__immutable_model_bindings.sql:2` binding_snapshot tenant/entry/provider/sha/snapshot；`V304__provider_configuration_revision.sql:1` config_rev；`V300__models.sql:22` call_log 的 run/attempt 标识不是 engine FK。MS:525-527 当前从请求取 runId/attempt/invocation，不能当来源证明。
- `monitor/src/main/resources/db/migration/V500__monitor.sql:2/7/13` dataset主键与 report FK；`V502__promoted_dataset_provenance.sql:2` 来源字段。

## 三、所有发送/材料读取门槛，不只 RestClient

1. 普通 HTTP：RT:38-56、skill/infra/SkillClients.java:24-50、engine/infra/TestInputClient.java:36-45、EV:71-96、ST:260-276 均仍是 Actor→ServiceTokens.issue 的调用点。真实迁移需显式授权上下文和 route/target；RT:59-64 未知 service 当前 default model，必须默认拒绝。此次只定位，不实施。
2. gateway：GW:47-63 HttpURLConnection 会在 getOutputStream 开始发送 body；GW:79-85 对每个读取块直接写响应；GW:99 finally disconnect。每次发送和响应 chunk 需要实际上下文/lease。禁止自动 redirect 不能只改 RestClient；GW 和 RT 两个 connection 都要显式覆盖。
3. engine SSE：RC:117 当前 TS.run 已读全行；RC:124 订阅，136-142 有未启动收尾；RC:230-239 PG 一次取 next event 的 payload。E 应拆 nextEventRef/metadata→授权→同快照payload，不可先读取/缓存两块再测试。业务 chunk bus 与 PG event 两条输出均须核 lease。
4. model SSE：MC:120-165 捕获 Actor 后虚拟线程 emitter；ML:410-434 doOnNext/blockLast；RT:100-132 reader 处理 chunk/final；GW 写响应构成完整链。当前这些位置尚非 ResponseLease 实现。必须将取消/timeout/error/final 的实际 reader/subscription/writer 关闭绑定，不能仅设状态。外部副作用是否停止仍须如实界定。
5. provider 首发：ML:334-350 的同步 interceptor 已有实际序列化 body 持久化，再 execution.execute；ML:359-375 reactive 使用 `model/infra/FrozenRequestConnector.java:24-53` 收集 body，在底层 writeWith 前回调。它们是可插当前授权+真实请求 digest+material scope 的准确门槛；beforeSend 不是“响应已受理”。accepted 应来自实际响应受理事件。
6. **measure 不是 metadata-only**：ML:49-58 进入 countTokens；ML:516-544 配置 tokenizer 时发送 messages、tools、完整 request 到外部 provider 同源 tokenizer。ME:175/211 在 chat 前即可能触发。需按实际投影请求建立关系和材料检查，并在 tokenizer 首发前核授权；仅保护 chat connector 会漏出站。
7. skill 首发：SE:223-288 persistent bind/observe 与 skill/infra/SkillAdapters.java:152-180 HTTP async/algorithm，`:455` MCP 都是实际执行；body→contract→args guard→claim 校验不能替代在线 current。每次实际外发包括 reconciliation/query/discovery 均按用途分类，不能只修 `/execute` 外层一次检查。
8. api-docs：GW:114-130 遍历五服务 GET `/v3/api-docs`，只在返回后过滤 internal paths。需要独立、明确的 docs-read 资源/能力裁决（可绑定真实服务契约清单版本），并保留真实文档可用正例。它不是某个 run 的 child，也不是自动公开豁免；本报告未验证已存在可用 docs profile。
9. artifact：AS:496 的下载已是 PG→MinIO 顺序，适合加 metadata scope 后再开对象流。原始对象 bytes 摘要与 JSON canonical request hash 是不同材料，不能互换。

## 四、后台/机器用途要分开

- `model/application/ModelHealthProbe.java:17-20` 定时 reconcilePendingUsage/probeDegraded；ML:74-82 创建 `_model_health` SERVICE Actor；111-129 读实际 provider/config_rev/secret_ref 后 GET models，150 起再 POST ping。用途是 provider 固定健康探测；应使用限定 provider/config revision/固定探测参数的本机机器授权，拒绝携用户 messages/工具并禁止恢复用户 grant。它不经过内部 ServiceTokens 客户端，单改 HTTP票据无法覆盖。配置更新竞态仍核当前 revision（MS:725 等）。quota reconciliation 是机器维护账务，亦不产生用户执行权。
- `skill/application/SkillOutbox.java:24-67` 仅枚举 tenant、PG租约认领事实，并从 fact principal 构造 SERVICE Actor；SE:1130-1169 读取结果后 POST `/internal/tool-calls/{call}/fact`。该用途应为固定 skill→engine 事实投递机器能力，绑定 tenant/call/attempt/fact generation/摘要，不能继续模型、执行 skill、恢复浏览器 stream。outbox 没有 adapter 依赖的现有边界应保留。读取/传输既有事实仍需 E scope/生产者证明，不能因为是机器就不限制 payload。
- `skill/api/SkillCallbackController.java:22` `/internal/skill-jobs/{call}/callback` 是已登记外部作业回调；`SkillCallbacks.java:88-131` 保留 SignedCallback 实际 body/时间/签名、send_started、历史 guarded arguments；225/241 再查 engine。不能把 callback 改成可免原签名的普通 workload。callback 私有事实用途与继续业务执行分开。
- `engine/infra/ExecutionRecovery.java:25-61` 的恢复当前从持久主体构造 Actor 后取 skill fact；`RS:1540-1541` 仍读 guard_ctx。后续需完整初始 ceiling、原 grant 与新 DELEGATED operation。旧 guard_ctx/当前窄 Actor 都不能补 initial_ceiling。
- gateway 既有 bootstrap 仅 credential.read/origin.check：GI:77/170 是实际调用。POLICY_MACHINE resolver 是独立签名域。上述 metadata、docs、health、fact 都不能偷偷借这两项权限或 `_gateway`/`SERVICE` 字符串扩展。

## 五、建议最小接入方向及必须先裁决的接口

### A. 首次 appCode/runId 的 bootstrap 困境不能用普通出票补洞

现 C exchange/forward 要求非空且精确的三字段 ResourceBinding。gateway 只有 appCode/runId，不持有本域 PG 版本；versionNo/header/body 的摘要不是可信版本。先以虚构 version/hash 出主执行票、再取真实对象会自相矛盾；先调用已有完整 snapshot/status 接口又已读取受保护 payload。现有 credential.read/origin.check 只解决集成认证材料，不能授权任意资源查询。

建议 root 单独审查一个**仅用于受理前解析的 metadata 内部 profile**；这是待批准的新最小接缝，不是当前普通 ServiceCall、不是已有 API。建议把签发/主体核实放在 authority 侧，域服务固定 receiver 只接受此独立 profile；gateway 不凭 Actor/body 制造可信主体。与常规 C 主执行 exchange/forward 保持隔离。必须明确以下字段和行为后才实施：

- 请求只含受限 selector：目标域、精确 intended action、appCode+明确 selector kind（published/versionNo/draft/candidate）或 runId/sessionId/artifactId，以及已认证外部证明的绑定引用。method/action/selector 类型白名单；不收任意 URL、SQL、parentId 或通用 payload。
- portal 的 tenant/subject 必须由已验 portal JWT 对应的独立信任域核实；integration 必须由当前已验 credential/subject/policy 证据产生（D2终稿需复核），不得来自 X-User 自报头或 gateway 的普通 SERVICE Actor。**仅“gateway签名并声称某subject”不足以让域查询成立**：authority 必须核原始身份及当前限定策略，随后签发限接收域/限动作/限selector/限主体/限期限的 metadata 证明。哪一个新接口承担这一步须 root 裁决；当前没有可声称已存在的方法。
- 域 receiver 先验证 profile/issuer/caller/target/用途、当前证明与 tenant/subject，再用限定 tenant 的 SQL 查询窄列；run/session/artifact 必须在 SQL 同时限定 owner principal。app 的授权归属并不一定是个人 owner，必须由 app 作者给出实际 tenant/visibility/role SQL，不能凭想象统一加 principal_id。集成还需核 credential 的 appCode 与 run 所属 app 一致。
- 只返回 `{resource kind, domain id, immutable version id or actual revision, version sha256, owner/tenant relation outcome, selector echo, producer, resolution evidence id, expiresAt}` 中各类实际存在的字段。run 可带 appCode/appVersionId/冻结来源摘要及关联 session；skill 可带 version id/contract sha/status；model 可带 binding id/sha/provider revision。禁止返回 snapshot/config/prompt/arguments/input/output/result/mock/cases/secretRef/物理存储键。digest 可以从受控持久对象计算，但未来 metadata-first实现必须用独立 metadata列/可信预存摘要，不能让本次 resolver 暗读业务 payload 冒充零payload。
- 解析证明只用于随后正常主动作的资源选择与 TOCTOU校验，不能直接换成 payload许可。主动作仍经 C current/authorize，精确绑定实际域版本。已有 grant/operation 的解析请求必须绑定同一 grant/operation/subject/ceilingSha，不新建更宽 grant，不重置 operation deadline；失效主操作不能借解析续命。
- **首个 exchange 尚无 grant/operation 时**，不得捏造“保留原 grant”。metadata证明只绑定原认证证据、主体、当前策略、受理截止；后续 exchange 必须基于同一认证证据按既有 C 规则创建原始 grant/operation，并校验解析证明仍有效且主体/策略一致。是否需 authority 增加这种受理证明消费，是 root 的明确接口裁决。不得仅靠 gateway 本地把两份签名相等当已验证链。解析过程不产生另一个可供执行的 operation，也不增加源凭据或已存在 operation 的期限。

root补充核实的C约束：Grant本身保存resource，resume和forward都与grant.resource精确相等。因此不能先以selector资源建grant，再用同grant.resume改成实际版本。上面的首入口建议是在首个主执行grant形成前用独立metadata profile解析；如果另选“metadata专用动作先建grant”备选，则主动作必须显式新建真实版本grant，并核同一原认证来源、策略与版本证明，metadata grant不能升级、扩权或延长原执行寿命。新grant备选尚未获裁决，也不是保持原grant的跨hop方案。已有执行grant只允许查询/确认它已绑定的真实根，不可借metadata更换根。

若 root 不批准新 profile，D3 此类首次入口继续闭锁；不能靠请求摘要/常量完成表面正例。

### B. 已解析根在各 hop 保持精确相等，child 在域内核实际关系

建议首先以“一个已发布 app版本→一个 run→一个冻结 skill call/model invocation”为最小纵向链。各普通 service ticket/forward 持有原根 ResourceBinding 不变，保留原 subject/grant/operation/完整初始 ceiling/hash/deadline；child 不进入 C 任意转签。接收域需要额外验证本次动作的实际 child 属于根。

新增最少的 E 持久接缝：run受理事务记录 operation/grant/subject/根binding/完整初始ceiling/截止；冻结 call/invocation/material 事务记录其真实版本、请求摘要、父run、attempt/round/outputAttempt、producer。复用现有业务表和锁，不要求重写调度器。初始 ceiling 来源严格按 stage-5 handoff：实际已验签且绑定 grant/operation/hash 的完整值，不能由 Actor.attributes 或 guard_ctx 推回。

需要一个精确、metadata-only 的 engine relation 投影（也可由 authority 固定受信 adapter 拉取，具体接收方与能力由 root 定）。建议白名单输出：operation/grant标识、tenant/subject、root binding、run public/internal id、call/invocation id、node/attempt、round/kind/outputAttempt、skill version+contractSha 或 model binding+sha、冻结请求摘要、记录状态/代次、producer、原截止。凭据由真实 service 身份证明；PG join 证明确属同 root operation。不能直接复用 TC.get（含 payload），也不能接受 body parentId 即放行。

model measure 的投影可能不同于 source/final_request；需增加最小的 frozen measurement material 标识与摘要，且在 tokenizer 发送前已持久关联 invocation。不能删除 measure 正例、不能拿最终 chat 摘要假装已覆盖 projection/summary。目标 model/skill 在读取本域配置/历史材料前检查 relation + 当前根授权，本域 guard/历史scope 另行交集；真正外发前再核 current、冻结请求和资源代次。

这条方向仍有 root 必须明确的接口边界：

1. `requireResource(root)` 只能证明票与根一致。如何把额外域关系证据约束到每个实际 action/child（receiver专用 guard，或 authority 新内部 profile）必须写成可审查规则，不能声称 D1现成方法已涵盖。
2. 创建run前读取 app snapshot/依赖时尚无 run 行，需 app冻结版本的依赖 metadata证明，不能引用未来 run；此项需 app作者在D2结束后补核。先做 skill/model 后半段不代表全链闭合。
3. observation/replay/new session turn 是新请求：如何从可信 run/session frozen root 创建本次 operation、如何限制不能沿观察票执行新业务，须逐action定义；后台只能新 DELEGATED，浏览器原operation永不续命。
4. 集合/list/create/docs/aggregate/health/fact各自的资源语义、真实 revision来源和严格能力目录需要明确，不纳入万能 app-version 常量。缺真实 revision 的集合不可预先宣称可出票。
5. 私有 material manifest 必须来自原受理连接并绑定真实 producer/run/invocation/attempt/round/outputAttempt/资源/请求及payload摘要。上述关系 metadata 接缝只能供普通授权/首发前校验；**不能在失效 drain 时另开HTTP补 manifest**。

## 六、建议有限正负验证（均未执行）

- 首次 appCode：metadata-only SQL/输出计数；正确owner/策略解析真实 immutable版本，然后 gateway→engine 正例。错tenant/subject/credential-app、错metadata caller/action、用执行/普通workload票调用metadata、metadata票读payload均拒绝；payload/provider计数0。并发换 currentVersion，主动作按解析的immutable版本或明确冲突，不能悄悄切版。
- runId：只查 owner+version metadata 后发观察票；同主体跨app的集成负例；跨run/node/attempt负例。现 status/query path不能提前读 inputs/output；坏主体 SQL输出0、业务payload读取0。
- 固定 root→skill/model：真实PG run+call/invocation正例保留同 subject/grant/operation/root/ceiling/deadline，不同hop jti/aud；错call version、同租户别run、伪body parent、真实服务但错producer、请求摘要/attempt/outputAttempt/代次错一项，读取配置/材料或首发计数按既定边界为0。
- measure/tokenizer：合法投影/summary有冻结关联且实际发送1次；错root/invocation/material或授权在首发前失效，tokenizer/chat请求均0。provider与tokenizer都禁止redirect跨目标。
- 完整初始 ceiling：原 A/B、当前 A，受理/恢复/材料仍保留 A/B与labels并集；缺完整值/错grant/错hash拒绝；payload+scope事务故障回滚。
- 真实stream：run_event metadata→同快照payload及model final原连接manifest；PORTAL失效后下一payload/输出0；合法既有INTEGRATION响应按既有permit边界读并完成；错producer/connection/manifest/version/digest、跨连接重放、deadline负例；drain新HTTP/新model/skill发送0；实际reader/subscription/writer关闭可观察。
- health/outbox：health只探测固定provider配置，不接用户messages；outbox真实既有fact一次投递可用，尝试执行skill/model/读其它call拒绝且adapter计数0。callback另测签名/body/attempt和原confirmRoles/approvedBy。
- docs/集合/create/聚合需各自至少一个合法正例和错scope负例，不能用全拒绝当覆盖。真实启动runtime handler（包括Actuator）遗漏闭锁；未取得app终稿及全部服务实际启动结果前不能关闭六服务D3。

本轮验证仅为生产源码和SQL的定位/逐段核对。没有执行行为测试，也没有签收真实IAM、部署、容量或四契约变更；BLOCKED_IAM_SCOPE_ACCEPTANCE保持。

