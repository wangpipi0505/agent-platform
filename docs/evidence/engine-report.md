# Engine runtime evidence and boundaries

2026-10-20；独立工程 `G:/207/项目/算法重组工具/代码/agent-platform`。只写 engine-runtime、graph-definition schema 与 engine evidence。未读取或复用相邻旧业务工程。Java21 / Boot3.5 / JdbcTemplate / PostgreSQL16.15（专用25432）。pom、application.yml、EngineApplication.java由主智能体提供，未覆盖。

## 可执行核心

- V200建立权威 run/node_instance/attempt/tool_call/run_event，以及 attempt_note；CHAT/TASK 发布版、DEBUG 冻结快照、EVALUATION 发布版/候选互斥由 PG CHECK 强制。claimed_lease 与 error_semantic VARCHAR32 齐全。
- V201只追加工具参数确认上下文；已应用V200不再改写。测试库由真实Flyway执行，非H2或内存状态替代。
- TraceStore：run→node锁序、带代次的租约、接管递增attempt、RUNNING白名单逻辑提交、0行仅留SUPERSEDED证据、唯一提交与同事务事件seq。JdbcTemplate使用方法访问，已用真实Spring Repository CGLIB代理回归修复public字段读取缺陷。
- FlowDriver：DAG、ALL/ANY确定终态、skip传播、条件分支、START/END/CODE/LLM/SKILL/RETRIEVE/CONFIRM/AGENT处理。ANY获胜写一次，保留有其他消费者的共享上游；全节点终态才结束run。错误边可传节点错误；DEFAULT_VALUE与只读RETRYABLE_FAILURE引擎单层重试。受限SpEL不开放Java调用，50ms求值上限，点路径及[*]映射，required/类型/enum与基础对象数组输出校验。
- AgentDriver：模型仅单次HTTP流调用，工具循环由引擎控制。完整最终请求先落PG；完整模型回包和round checkpoint先持久，再派工具。接管可重用已记录模型响应；W3成功执行事实可补逻辑提交而不重派技能。保留原始用户消息及完整工具call/result配对；未知工具拒绝，PARAM_ERROR最多两次回灌，NO_PERMISSION/QUOTA不自动重试，步骤/模型调用/实际usage token/总时长预算持久计数，无进展指纹终止。
- 工具调用先登记且稳定callId/key，参数canonical digest禁止漂移；真实node/current attempt/lease校验后claim，submitted必须先于外调。事实与逻辑提交分离，迟到事实留痕不改终态。相同attempt outbox重投/UNKNOWN→终态不重复累计实际执行耗时；跨attempt实际执行段累计。
- 写技能确认：skill.execution.confirmRoles是冻结角色权威。未批准的call保持REGISTERED、零执行事实；WAITING_APPROVAL持久冻结callId/skillVersionId/args/digest/roles/expiry。确认强制requestId+snapshotDigest、真实声明角色、版本参数和过期校验。批准只重新排队；新attempt仍重用callId和原模型checkpoint，技能通过engine approval只读接口独立复核，无body.confirmed捷径。
- 上下文装配每轮执行：冻结配置/模板及技能版本准备、严格渲染、消息配对、估算分账、裁剪阶段记录、校验、最终冻结。采样/output/context/timeout/budgets完整传递。字符密度仅标估算，tokenCountExact=false；真实tokenizer窗口准入交由模型网关执行，不把固定密度当普适准入。
- 调试FULL/STEP、断点、DB挂起pauseSeq、三元组CAS、resume requestId同事务去重、跨实例恢复、30分钟超时取消。单步可查看执行前冻结prompt/manifest/variables。Mock只在DEBUG透传到技能守卫执行管道，正式及评测不发送Mock。
- 真实模型SSE：原始provider chunk经ChunkBus直接推在线观察者；不先收整段再切片。chunk无事实seq，持久最终响应与run_event事实补发保持分离；断线不取消运行。
- 正式运行钉数字appVersionId；候选评测校验原始canonical摘要、运行不可变frozenCandidate；(evalReportId,caseId)持久幂等，新副本可恢复已有run而不读新草稿。请求号换输入/版本拒绝。

## API

ApiResponse统一壳，身份只取已验证Actor；内部换发ServiceTokens，不透传用户JWT。

- POST `/admin/debug-runs`，GET `/{id}/status`，POST `/{id}/resume|cancel`；prompt、variables与events子路由。
- GET `/admin/runs`（默认仅CHAT/TASK）、GET `/{id}`、POST `/{id}/cancel`、GET `/{id}/events`。
- POST `/admin/apps/{code}/runs` 与 `/v1/apps/{code}/runs`；GET `/v1/runs/{id}`、GET `/{id}/events`、POST `/{id}/cancel`。不提供裸POST `/v1/runs`。status含appCode供网关绑定核对；owner检查仍为tenant+principal。
- POST `/internal/evaluations/run`，支持caseId/frozenCandidate复用真实run。
- GET `/internal/approvals|statistics|audit`：真实PG投影，统计按triggerType分离。POST `/internal/runs/{id}/approvals/{nodeId}`。
- POST `/internal/graphs/validate` 返回 `{valid,errors:[{code,nodeId,message,severity}]}`，覆盖DAG、节点/连线引用、错误边、ANY共享上游显式模式、并行输出键重名。
- 工具端点见 `engine-tool-calls-api.md`，新增GET `/{callId}/approval` 返回 `{approved,callId,skillVersionId,parametersDigest,roles}`。

## 测试及失败证据

每条Maven命令均在本进程设置独立JDK21，显式使用本项目 `.tools/m2`。`.env`仅进程读取，未打印凭据。PG测试自动新建专用 `agent_platform_engine_test`（同25432），不使用正在运行服务的开发库业务行。早期原始DDL测试在服务启动前运行于独立新开发库；后续已改Flyway+独立测试库。

命令：

```powershell
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
# 从本新工程.env加载环境，勿输出值
$env:ENGINE_PG_TEST='true'
mvn '-Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2' -pl engine-runtime test
```

红→绿实际观察：GraphRules缺类；表达式==误拒及白名单函数缺失；[*]丢元素和null比较错误命中；孤立tool结果未拒/第二轮漏原始user；UNKNOWN回调duration10被计成20；WAITING_EXTERNAL中合法执行事实被拒；调试超时、独立modelCalls预算、图校验器、工具登记与写审批先缺实现再补；真实Spring上下文暴露Repository代理字段缺陷；审批纵切暴露阻塞READY后继使聚合状态误为RUNNING及同步评测空转。

真实PG覆盖：触发版本互斥、取消迟到零成功事件、旧租约拒绝/新代接管、8并发仅1逻辑提交、resume重投不放后续挂起点、seq随事务回滚、跨principal拒绝、稳定调用登记/参数变化拒绝、唯一claim、事实先于逻辑、SUBMITTED过期UNKNOWN不盲重发、迟到证据留存、冻结候选执行与篡改拒绝、候选case复用、模型checkpoint接管零额外模型调用、W3零额外技能派发、超时清理、预算计数、耗时outbox幂等、显式确认角色+digest+标准逻辑提交、写调用跨attempt批准绑定。

真实HTTP fixture覆盖：本地ServerSocket输出原始SSE chunk与final，验证chunk顺序及完整最终响应。Fixture只在测试目录；未写生产假模型成功路径。SpringBootTest加载真实Spring应用/安全/Repository代理/真实PG，使用mock servlet环境避免本机JDK默认TestRestTemplate NIO Unix socket故障；生产客户端显式使用标准HttpURLConnection，真实HTTP流fixture独立验证。

最终JUnit结果见本报告末尾追加的验证记录。Surefire XML与日志在engine-runtime/target，未将“PG未启用而跳过”计作通过。

## 实际阅读的开源机制

仅只读授权的开源参考：DeepSeek Harness（MIT），commit `47f943859bef60e4160492346772ded9b24f765a`。已读：

- `packages/core/system-prompt/src/index.ts`：有序section、变量、assembly waterfall与工具词表排序。
- `packages/core/session/src/invariant.ts`：单调seq、turn/step关系、tool call/result先后及配对。
- `packages/core/session/src/chunk-rows.ts`：chunk与持久编码分离、保留增量边界、未知形状不可静默丢失。
- `packages/core/tools/src/invariant.ts`：pre/execute/post单调管道、冻结执行和结果快照、稳定call身份。

工程对应代码注释位于ContextAssembler、TraceStore、AgentDriver、ChunkBus、ToolCalls/ApprovalService。只借鉴机制并独立Java/PG实现，未复制或导入TS运行时；未声称阅读Dify/其他未读源码。

## 仍需明确验收的边界

- 当前没有自动历史摘要/四档语义压缩服务，trimLog为真实空记录；超窗口由网关校准tokenizer拒绝，不静默丢消息或工具配对。40轮保真摘要验收尚未完成。
- prompt/input/result目前PG JSON+pg:引用；未实现大载荷MinIO分流、对象访问签名与保留策略。JSON Schema运行期为明示基础子集，复杂组合/外部ref、显式数组目标长度/截断WARN策略尚未完整实现。
- CHAT受控循环在一个节点实例内以round checkpoint记录，尚无跨多run会话历史管理；不可宣称完整多轮会话存储子系统。
- UNKNOWN过期事实保留并等待核实，不存在自动信任任意body成功的adopt捷径。活租约WAITING_EXTERNAL收到有效事实可恢复；过期UNKNOWN的人工核实采纳链仍需完整对账集成验收。
- ANY采用保守独占直接前驱放弃；不取消有共享消费者的上游。未实现外部任务强杀/确认已取消的完整三态技能取消API。
- 全局调试挂起容量上限、命名测试输入CRUD/提升评测、独立Mock管理/LAST_REAL授权缓存UI、完整三档回放不在本可执行核心中，不能据此计为全部设计功能完成。
- 真实供应商/模型tokenizer校准、真实技能写审批HTTP纵切、readonly跨attempt执行事实重试、业务IAM/本体/容量/多副本故障注入尚需跨模块及外部环境验收。测试fixture与模拟租约接管不等于这些生产验收。

## 最终验证记录

2026-10-20 02:47，最终源码 `mvn -pl engine-runtime test`（上述独立JDK/缓存/PG环境）退出0：38 tests，0 failures，0 errors，0 skipped。细分：ContextAssembler3、GraphRules1（6断言）、GraphValidator2、JsonGate3、Values6、真实Spring Repository代理上下文1、真实HTTP SSE1、真实PostgreSQL21。Flyway真实验证V200+V201，测试库agent_platform_engine_test。

模型配置冻结缺口补充：目前冻结modelEntryId/modelBinding及请求params，未冻结完整发布时model/provider metadata；actualModel只作为实际路由回包记录进attempt.routed_model。故供应商DISABLED后已发布快照允许运行的规则，尚不能通过当前engine快照证明历史绑定；不得以忽略DISABLED代替冻结版本链。凭据仍应实时按secretRef解析，此部分须与app发布/model路由共同补齐。

## 2026-10-20 P1 independent-review repairs and recovery

Verified full engine suite: **45 tests, 0 failures, 0 errors, 0 skipped** (`target/full-recovery-green.log`). This includes the actual Spring context, dedicated PostgreSQL database `agent_platform_engine_test`, and HTTP SSE fixture. The service database is not used as the test fixture. Schema changes are additive V202/V203/V204; V200/V201 were not rewritten.

- R4 submission/cancel: register/claim/submitted/fact serialize in run → node → tool_call order. Cancellation takes the same authority locks, abandons calls lacking submitted external rights, and records `CANCEL_IN_FLIGHT` for previously submitted calls without claiming that external effects were undone. Two controlled PostgreSQL latch tests first failed against the original race and now pass. One holds the validation window while cancel competes; the other holds cancel before submission competes.
- Business approvals: ADMIN is no longer an implicit member of every frozen confirmation role. A real PostgreSQL ADMIN-only actor cannot list or approve an APPPROVER business item. Portal decisions remain tenant + declared-role scoped; the v1 aliases additionally require run ownership.
- V202 controlled DRAFT acceptance: `POST /internal/skill-draft-tests {skillVersionId,inputs,requestId}` requires EDITOR + SERVICE. The server reads one precise DRAFT, verifies its canonical contract digest, and creates its own DEBUG START → SKILL → END snapshot. It freezes the complete contract and records `controlled_draft_skill_version_id` and `controlled_draft_skill_digest`. Ordinary debug request flags cannot grant DRAFT access. Write confirmation remains mandatory; a draft edit invalidates the old acceptance digest. Status returns `controlledCallId`/`callId` once registered. The skill service independently validates these authority fields and the current draft digest.
- V203 freezes each registered call's `skill_contract_sha256` from the run snapshot. An absent digest is never accepted as sufficient proof for autonomous adoption.
- V204 recovery probes: `ExecutionRecovery` polls only authoritative WAITING_EXTERNAL calls, with durable attempt count, retry timestamp and sanitized error class. It reads `/internal/skill-facts/{callId}` under original tenant/principal ownership with empty reconstructed business roles. It validates the canonical fact hash, public run ID, node/call/version/parameter/contract digests, source attempt, trigger kind and mock restrictions. It then takes run → node → call locks, acquires a new attempt lease and records `VERIFIED_FACT_ADOPTED` and `EXTERNAL_FACT_ADOPTED`. It stores execution evidence and requeues READY; the normal driver retains sole logical-result commit authority. It never calls the business execute endpoint. Canceled/terminal runs are not reopened. Successful adoption disappears from the polling set; proof mismatch leaves SUBMITTED and schedules a later probe. The two new PG tests prove no direct LOGIC_RESULT_COMMITTED and no second proof read after success.
- A skill HTTP transport interruption now reads engine call authority: already persisted success is reused; SUBMITTED/EXECUTING/UNKNOWN becomes WAITING_EXTERNAL instead of blindly re-executing the write.

Limits still open: autonomous recovery has unit-fixture HTTP boundary plus real PG, but a killed real multi-service request followed by live proof pull still needs cross-service acceptance. It intentionally refuses legacy calls without frozen contract digests. Recovery currently stores a max duration for proof adoption; aggregation of multiple distinct read-only retry attempts remains a separate accounting edge to verify. No public endpoint accepts a supplied proof result. Context TRIM remains the previously documented no-op pending measured projection work, LAST_REAL/mock editing and conversation replay remain incomplete. These are not counted as completed v1.2 capabilities.

External inline approval aliases now available: `GET /v1/runs/{id}/approvals` and `POST /v1/runs/{id}/approvals/{nodeInstanceId}/decision`, using the same required requestId/snapshotDigest decision payload and additional owner check. Portal approval continues to support an eligible actor other than the initiating owner. Artifact access remains pending.

## v1.2 source package after V204 (V205–V211)

This section supersedes the earlier no-op TRIM / absent CHAT and Mock-management baseline. The original baseline remains above as dated evidence rather than being rewritten.

Implemented code now consumes immutable model bindings from app dependencyVersions.modelRefs, validates canonical configuration SHA, and passes modelBindingRef + authoritative appCode/callKind to model calls and measurement. DEBUG missing refs are frozen at creation; formal/evaluation snapshots missing bindings fail closed. A model cap is not confused with the configured output reservation: measured manifest distinguishes both, plus protected input, history budget and safety margin. Measurement exact=false remains explicitly labeled; there is no character-density admission fallback.

ContextProjection performs historical tool head/tail projection, whole-group dropping, a separately persisted single-call model summary, then historical-content hard truncation. Current instruction and system governance stay protected; tool call/result identities stay paired. Complete original source_request and final projected request are in model_invocation, including trim logs/digests. Summary requests/results have their own SUMMARY rows, actual model-call/token budgets and usage ledger. PG tests verify final material exists before the SDK, both summary/main returns are durable, restart reuses both, and usage is debited once. Summary-source overflow and protected overflow reject explicitly.

Debug controls now have persistent revision CAS and audit events. Frozen mock selection is per stable registered call, preserving the original revision across retries. LAST_REAL selects only authorized original-user, non-mock, same-version/args/contract success; it records the source call/run/principal/digest. FIXED values reach skill's real enabled/response mock branch rather than accidentally falling through to external execution. Formal registrations ignore all debug controls. Skill authority-side independent Mock validation is coordinated with root. Attachment-only LAST_REAL remains explicitly refused until the skill service can mint new current-run artifact references; it is never converted into success with lost attachments.

CHAT sessions persist an immutable published snapshot and ordered turn reservations. New requests check current app publication/enablement without rebinding the stored version. Existing accepted request IDs remain recoverable after disablement. A reservation commits before run creation and attachment; retries locate the same run by stable session/request ID. PG tests execute two actual engine runs with a fixture model, prove persisted prior-turn messages reach the second run, protect the current question during projection, and reject other owners. An application-disabled test first failed against the initial implementation, then passed after the live publication gate; accepted-run lookup is tested separately.

Replay APIs now implement three distinct execution meanings. LOG reads original evidence. REEXECUTE creates a new DEBUG run under old configuration using current services and ordinary confirmation/guard/lease rules. CONTROLLED now creates a real new DEBUG run under the normal FlowDriver/AgentDriver, sourcing model/tool exits from authorized recorded responses, validating current frozen guards/input/output schema, recording new mock/source provenance and recomputing logic. It is no longer merely an archived evidence copy. Two PG tests ban every RuntimeClients HTTP exit: SKILL + condition + branch recomputes 43 from saved value42 despite source.output999; a two-round Agent consumes saved model + tool returns and completes through its normal loop. New model rows have is_mock=true, source_invocation_id and sdk_attempts=0; tool rows have is_mock=true and source call/result SHA.

Additional review repair: adopted FAILED proofs preserve error_semantic and are consumed directly without POST execute. A failure test first reproduced the unwanted redispatch. The corrected driver terminates a non-retryable failed run with zero external calls; a read-only retryable failed proof permits one explicitly budgeted new execution attempt. V209 readonly_retry_count separates actual retry policy from crash/recovery attempt_no, which otherwise incorrectly exhausted retries.

Run status now projects controlled fileRefs from its authoritative successful tool results while retaining node.output=structured (including null). It preserves V306 bytes/summary and only an externalDownloadUrl exactly bound to this run and artifact ID. uri/s3/bucket/object-key fields are omitted. The test seeds a 5 MiB metadata envelope with structured=null and verifies safe references remain visible without changing node output.

Current limits, not counted as fully accepted capabilities: production provider/tokenizer/IAM/ontology behavior still needs real external-environment acceptance. Controlled reproduction refuses old records missing frozen guard/schema or model invocation material, and explicitly rejects multi-generation read-only retry source histories instead of collapsing them. It currently compares saved model messages strictly; legacy semantically equivalent tool JSON text with different original formatting may reject. Compression uses one summary call and rejects summary sources larger than the model window; hierarchical multi-batch compression and a reusable cross-round compression-family registry remain future work. The standalone evidence archive remains an archive, not another execution mode. Other JsonGate consumers retain their explicit subset limits; model final-answer validation now uses networknt Draft2020-12 with composition/local references and bounded invalid-output policies, as recorded in engine-output-policy.md. These limits do not require an external service to develop; they are independent implementation follow-ups.

API contract and payloads: docs/evidence/engine-v12-api.md. No already-applied migration was rewritten. All new Java source/test files were formatted with google-java-format; EngineApplication.java remains parent-owned.

### Final coordinated Mock authority update

The skill service upgrade has now completed (root-provided evidence: `.local/skill-authority-artifact-green.log`, 45 skill tests including PG/MinIO source revalidation and current-run attachment copying). Engine removed both the compatibility body enabled/response switch and the temporary attachment-only LAST_REAL rejection. Skill receives mock={} and reads only authoritative tool_call.mock_snapshot. A new engine test first failed while the old executable body override existed, then verifies an empty body override while the full mock snapshot remains available through engine authority. LAST_REAL full source Envelope remains frozen with source identity/digest; skill independently checks its own durable source fact and issues current-run artifact references. The earlier temporary limitations in dated paragraphs above no longer apply to this coordinated package.

The CHAT disablement gate was also added after the initial 63-test checkpoint: new request IDs require a current successful published-app read, while accepted request IDs are still recoverable under the original pinned version. Two additional PG tests cover refusal before reserving a new turn and idempotent accepted-run lookup after disablement.

### Final verification and handoff checkpoint

Final full-engine verification after Mock authority-only dispatch, attachment delegation, CHAT current-app disablement gate, and request-id length bounds: **65 tests / 0 failures / 0 errors / 0 skipped**. Maven ran with JDK 21, the project-only .tools/m2 cache and ENGINE_PG_TEST=true against the dedicated PostgreSQL engine test database. Raw log: `engine-runtime/target/v12-final-green.log`; the Surefire XML totals were independently summed after completion. Schema migrations V200–V211 validated successfully; this package adds V205–V211 and does not rewrite deployed migrations. No live provider, IAM or ontology success is asserted by the fixtures. Source is stable for parent packaging/restart and independent review.

## Independent execution review I1/I2 follow-up (V212)

Engine repairs and directly executed red/green evidence are documented in `docs/evidence/engine-review-i1-i2.md`. Final full suite is **75 tests / 0 failures / 0 errors / 0 skipped**, log `engine-runtime/target/i1-i2-full-green.log`, with XML totals checked after completion. Scope is recomputed before source facts/model answers are reused; legal empty attribute declarations match SkillRules; trusted returnSpec projection is not revalidated as raw HTTP output; new Envelope ownership/provenance is separated from original saved model tool-message material. Attachments use the lease-checked internal storage-copy API and must return new current-run controlled references. This allowance is infrastructure HTTP only; business/model execution remains forbidden during controlled reproduction.

V212 adds neutral exact skill connection markers and preserves DRAFT restrictions. Exact PUBLISHED/ARCHIVED read-only/write tests work; disabled skills fail before creating the run; write confirmation is unchanged. Parent's `status.inputs` projection is retained. No service process was stopped; code is stable for parent rebuild/restart and independent review. A real joint storage-port HTTP acceptance after deployment is still not claimed by engine's fixture tests.
