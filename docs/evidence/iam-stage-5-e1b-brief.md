# E1b：运行授权入库、三类初始材料及本地续跑检查（准备稿）

日期2026-10-20。当前仅准备；E1a独立Spec/Quality审查关闭、root登记六份执行索引后，才由root追加正式授权并分配唯一source/target作者。不得据准备稿提前改代码或数据库。原v1.2、四契约、工期不变。

唯一工程/workdir：G:/207/项目/算法重组工具/代码/agent-platform。先读AGENTS.md、contracts/README.md。禁止读取/引用/复用相邻旧工程的代码、配置、存储、数据库、进程、依赖。用户已明确没有外部联调环境，继续用隔离端点，不重复询问环境或凭据。

本稿为已批准Task5的有限组件拆分，优先于只读提案中的候选措辞。依据iam-service-authority-implementation-plan.md §2.5、Task5，以及iam-stage-5-e1b-persistence-seam-proposal.md（SHA 6e22271f28828e29062428f684a7ce43b177e2f5191e6b829956117d55d425cc）。后续真实调用图见iam-d3e-first-published-path-proposal.md（SHA 972e08d6b7c5e4b06488d914989163d170bcb7f850fb0764544019cf58461739）。提案是源码事实和方案输入，不代替本稿裁决；特别是普通材料读取不能被误做成原grant永久钉死。

## 1. 本段交付与不可提前签收的范围

实现三个真实生产组件：RunAuthorityStore创建真实新PG run并在同一事务保存授权与三scope；MaterialScopeStore先查范围再读取三种真实初始JSON材料；RunAuthority.current使用受限run.advance实际resume+signed current，在短事务CAS记录检查及阻断。

本段不修改RunService/RunController/TraceStore/RunScheduler/RunScheduleRepository/FlowDriver/AgentDriver/AppService/GatewayController，也不接线这些组件。不得以组件测试宣称实际MVC/filter/controller方法体、已发布FLOW、owned调度或RR2真实模型流已执行。生产IAM工厂/启动闭锁保持，不加默认假client/Clock配置救启动，不改YML、四契约、路由JSON或前端。

Java21、Boot3.5.0、Security6.5、Nimbus9.37.3、PG唯一业务权威源。三新类按engine application/infra分层，跨业务域HTTP，不导入app/skill/model/gateway业务JAR或反射其target/classes。允许engine test-scope复用现admin-common test-jar，无新增版本/库/泛型框架。

## 2. 唯一新建入口：createPublished拥有实际INSERT

采用RunAuthorityStore公开窄方法，候选参数形状已裁决：

```java
long createPublished(
    RetainedAuthorization admission,
    JsonNode acceptedSnapshot, JsonNode inputs, JsonNode versionRefs,
    JsonNode budget, String mode, String requestId,
    Set<String> trustedAdditionalDataLabels);
```

开始先确认同DataSource调用方真实写事务，并调用AuthorityContext.requireBound(admission)，均在任何受保护run payload读取之前。禁止方法自己REQUIRES_NEW、自动提交或捕获授权异常后继续提交。owner只取同次证明；caller=gateway、target=engine、action=proxy.engine.post.RunController.run、source/origin=PORTAL，root.kind=app-version且已验完整initial/origin。metadata/read/debug/eval、DELEGATED/workload不能首次创建。

严格校验acceptedSnapshot.appCode/versionNo/candidateSha256/appVersionId或versionId，以及可选appVersionKey=code@no；versionNo、numeric ID为真实正整数、拒绝字符串/浮点/截断/越界，两数字ID同时有必须一致。按actual code@versionNo重建root.id，candidateSha256为64小写hex且等于签名root.versionDigest；versionRefs.appVer与真实versionNo同类型同值。inputs/snapshot/versionRefs为JSON对象且非SQL/JSON根null，budget与mode/requestId按现published TASK实际规则校验。组件不接受DEBUG/candidate/controlled/chat/eval来源；尚未处理的外部data-source或历史引用不能被当成无标签来源。

本方法自行生成public_id并执行真实engine.run INSERT RETURNING id；只提取原RunService已发布TASK INSERT的有限列和值，trigger_type固定TASK、debug_snapshot/eval_candidate为NULL、PENDING、原input_ref/guard_ctx/budget等真实必填含义保留。guard_ctx只作审计。然后以方法局部新id调用私有或包内insertNew，写authority和全部三scope，再做本地requireBound及期限终检，返回实际runId；调用方可同一事务继续写node。无接收任意runId的public补权方法、无upsert/ON CONFLICT旧run补权、无receipt/创建能力类或xmin证明：UPDATE也会改变xmin，不能证明首次INSERT。

requestId冲突保持PG唯一约束，既存分支不得变成此方法的补权路径。原RunService后续接线时必须替换它真正新建published分支的INSERT，并保留其锁/重查/节点/admission/提交后派发；本段不修改它。acceptedSnapshot真实性依赖后续真实app受理writer核远端发布指针，本组件只证明“同次main proof和实际新run一致”，不能跨PG猜app版本存在。

## 3. 持久schema、完整initial及禁止历史回填

保留Task5最小run_authority语义：run_id PK/FK→engine.run、tenant_id/principal_id varchar(64)、grant_ref、grant_expires_at、完整initial_ceiling JSONB、resource_digest、source_kind、origin_authentication_id、last_decision_id/last_checked_at/blocked_reason。resource_digest为真实accepted app candidate SHA；与本域材料snapshot SHA分开。再加实际需要的accepted app_version_id/no、root_kind/id、initial_ceiling_sha256、accepted_snapshot_sha256，以及immutable admission_decision_id/authorization_version/operation_id、retry_not_before/check_version。

initial保存E1a同次已验完整Actor，不是当前Actor/guard_ctx重建；严格解析已知四属性region/dataLabels/bizSystemCode/userId，集合/owner/hash校验复用规范算法。为避免engine复制AuthorityProtocol.ceiling逻辑，**允许在现MaterialAuthorization增加一个public纯函数ceilingSha256(Actor)，内部直接委托AuthorityProtocol.ceiling**；不得开放整个协议类、证明构造/反序列化方法或新增另一套hash语义。实际MaterialScopeV1公共record保持原形状，不改四契约。

load只join真实run窄metadata：id/public_id/tenant/principal/app_code/app_version_id与side row及三scope元数据；验证owner、数字version ID、code@stored versionNo和root，不能从旧snapshot读版本填补。旧run无authority、无fullinitial/origin/三scope任一项缺失均拒绝，旧payload SELECT=0，无自动回填。

原initial/accepted/root/origin/admission字段初次INSERT后不可被普通检查更新；有限PG trigger禁止绑定字段UPDATE，只有last_*/blocked/retry/check可按受限CAS改。run_authority有FK，材料side row关联本run。仅对已有authority的run加有限不可变字段保护，禁止owner/version/snapshot/inputs/version_refs被旧路径改写；不限制原状态/lease/output等本段未接管字段，不影响所有历史dev run。材料行不UPDATE/DELETE，无cascade清历史scope。

**未来显式重新授权仍是Task5要求，尚未实现。** 本组件禁止普通检查改原initial并不等于禁止该功能；后续必须保留原initial/历史scope及审计，明确新委托代际/绑定切换/CAS的授权路径，不能用当前Actor覆盖旧initial。报告把这项列为后续依赖，不降为可选优化。

## 4. 三种材料、真实版本与精确JSON

仅支持producer=engine、id=run.public_id：run-snapshot→run.snapshot，run-inputs→run.inputs，run-version-refs→run.version_refs。version统一为该run**实际PG持久化snapshot规范内容SHA**；各材料payloadSha256则独立取各列实际内容规范SHA。不能使用常量1、identity revision、app candidate SHA或runId摘要代替真实材料版本/内容摘要。唯一键严格为(tenant_id,producer,kind,material_id,material_version)，无序列/通用revision管理器。

material_scope保存run_id FK→run_authority、上述定位列、payload_sha256、scope_sha256、scope JSONB，各列与MaterialScopeV1的owner/material值严格一致，关联真实run/accepted snapshot版本。scope sourceDecisionId/sourceAuthorizationVersion来自immutable admission审计，initial hash为历史完整initial的规范SHA。snapshot、inputs为初始根，versionRefs至少以该run snapshot的真实scopeSha为父；父摘要存在且同run真实关联，不能仅检查格式。

三scope权限floor取完整initial的角色与region/bizSystemCode/userId要求。dataLabels为义务并集：initial + 实际current新增 + 真实parent + trustedAdditionalDataLabels；不把labels当可裁剪权限或普通cover条件。外层create持有retained统一构造三scope，当前必须覆盖要求，initialAB/currentA且requiredAB时**提交前全回滚**。合法历史收窄测试先AB/AB成功受理，再A，保留原AB。

MaterialScopeStore.write(MaterialScopeV1)保留一参数形状但仅infra包可见、强制已有真实事务；它是RunAuthorityStore内部受信写原语，校验定位/版本/owner/hash/审计/真实payload/parent，不冒充独立验签。证明来自外层create前后requireBound，不能新增ThreadLocal writer框架。后续DELEGATED新材料writer必须显式传当次证明，不能复用旧admission决策。

数字规范化留MaterialScopeStore私有实现，不改全局Json：从已存PG JSONB的text严格解析BigInteger/BigDecimal，object键排序、array保序、nested null保留，尾零/指数按精确值规范化，零统一0；规范UTF-8计算SHA。现Json.canonical可复用，但必须实际证明精确node未被转double。不得悄悄舍入。真实PG测试区分超double范围整数、相邻高精度小数、null与{}、array顺序；1/1.0/1e0规范一致。根null/SQLNULL拒绝。本域规范字节不等于HTTP/provider原字节，不加bytea镜像/第二份payload。

## 5. 普通read与后台current必须区分

public AuthorizedMaterial read(MaterialRef, VerifiedAuthority)沿原计划，生产前提是VA来自真实guard或RunAuthority.current；公开record自身不是密码学证明，组件不另造身份cache。先scope/run/side metadata，再MaterialAuthorization.require，最后同快照固定switch选唯一业务列并核真实payload SHA。错误owner/root/kind/version/scope/parent/digest在能判定的最早阶段拒绝，scope错误时三payload列SELECT=0；错误payload SHA在读取后、任何返回前拒绝。

**普通历史读取不要求当前grant_ref等于旧run原grant，不要求当前initial hash等于旧scope历史initial，不要求旧run原grant仍有效，也不因旧run后台403阻断就直接剥夺合法读取。** 它要求当次current自身有效、同owner、实际root/用途正确，当前权限覆盖历史scope，scope历史initial hash能和PG保存的历史fullinitial相符。新原认证/新Grant/currentABC在同owner/root下应可读旧scopeAB；不能把提案4.4的grant/ceiling核对误读为永久grant钉死。后台strict equality仅属第7节。

本段read只接受精确engine-target主受理用途或run.advance：主受理caller=gateway/source+originPORTAL；local caller=engine/sourceDELEGATED/originPORTAL。app读/metadata/debug/eval及任意其它action拒绝；未来真实status路径需明确扩用途并做实际HTTP验收，不能先放开所有engine动作。普通read的正例必须有“旧委托已到期、新same-owner Grant与新initialABC、current覆盖旧AB”及反例currentA/错owner/root/purpose，证明边界可用。

scope metadata→payload使用同DataSource的只读REPEATABLE_READ TransactionTemplate；加入现有事务须确认不低于REPEATABLE_READ，拒绝在READ_COMMITTED静默join，也不REQUIRES_NEW绕外层事务。create内部写自己的新值不用这项历史read方法。可信Clock只在构造时注入，read业务参数不接受调用者Clock。实际并发闩锁验证快照/不可变保护，不全局修改业务隔离等级。

## 6. 唯一非HTTP local tuple

在现ServiceRoutePolicy内嵌一个固定tuple helper：action=run.advance、caller=engine、target=engine、delegated、root.kind=app-version、source=DELEGATED、origin=PORTAL，**只有resume出票**。routes()/require()/HTTP目录保持原语义，不增假URL或local HTTP row，不为所有action增local布尔开关。HttpCurrentAuthorityClient与TrustedJwtDecoder.requireServiceScope仅窄接该tuple；exchange/client/workload/forward到local一律拒绝，普通ServiceAuthorization HTTP guard必须拒绝local票。

沿现LoopbackAuthority有限Grant增加originalCall分量，记录首次已验gateway exchange的call。只有originalCall.target=engine/action=proxy.engine.post.RunController.run且实际root一致的PORTAL-origin main Grant可local resume；同root的app读Grant、metadata选择器、debug/eval均不能。originalCall和origin在forward/resume/approver保持，不从resume输入补填。真实Basic服务身份、签名/JWKS/current/nonce/requestDigest检查保留；不要复制authority、开全局静态ACTIVE或持久原用户JWT。该策略是未来真实IAM适配profile要求，fixture通过不等于有生产IAM server。

## 7. RunAuthority.current与持久CAS

保持public VerifiedAuthority current(long runId,ServiceCall call)。只精确local tuple，root必须等于load得到的该run accepted root。load完整metadata+三scope，无payload；fullinitial/owner/root/version关联/hash/原grant期限严格验证，永久403阻断拒绝；503临时阻断只有retry_not_before到期后的明确检查才能再试。

网络在事务外：engineClient.resume(stored DelegationRef,call)一次，authorizeRetained(ticket,call)一次，完整验签证明requireVerified。strict核DELEGATED/originPORTAL、稳定originID、owner、原grant与expiry、fullinitial规范全值/hash、root与PG全等。新operationId/期限来自新签名，不等于给原PORTAL observer延期。三scope均覆盖后，进短事务重核immutable绑定/check_version/证明期限，用CAS记last_decision/checked/check_version，成功只清本次允许恢复的503。CAS0是过时结果，拒绝返回可推进VA；不能覆盖另一检查的阻断/重新授权。

明确403/过期/撤销CAS记固定blocked_reason，503记AUTHORITY_UNAVAILABLE与有限retry_not_before；未知编程/DB错误不伪装503。保持原payload/initial/scope及业务事实状态，不把失败写成成功；不吞异常。构造时可信Clock和固定退避Duration可测试注入，不来自请求。检查成功返回当次VA，后台无旧Actor/ThreadLocal。尚未接scheduler，不能声称候选排除/自动退避已生效，也不拿别run的captured current推进。

## 8. 有限文件所有权与真实测试

仅以下生产范围：新engine infra/RunAuthorityStore.java、infra/MaterialScopeStore.java、application/RunAuthority.java；common authority/ServiceRoutePolicy.java、HttpCurrentAuthorityClient.java、TrustedJwtDecoder.java、MaterialAuthorization.java最小改动；engine下一连续迁移；engine/pom.xml仅common test-jar testscope。对应新测试和必要最小LoopbackAuthority扩展。三新生产类先不加会导致现扫描缺bean的stereotype，后续显式正式装配。不增业务安全配置、通用writer框架、材料控制器、Grant服务或另一driver。若确需范围外文件，先给root具体原因/API；不要先改。

唯一授权新PG测试库名agent_platform_iam_engine_v12，固定127.0.0.1:25432/agent_v12。可沿既有受控launcher和D2 DatabaseTest模式：只从新工程AGENT_DB_PASSWORD环境取值，postgres管理连接只查这个固定库是否存在并在不存在时创建它；所有业务SQL仅目标库，断言current_database。不开读/写main、D2或其它库，不输出凭据、不复制env解析器。现源engine最大V215；实际新库Flyway history必须先查并记录，再分配真正下一连续迁移，候选V216未占位；不重写旧迁移/outOfOrder/高号占坑。数据库是否首次创建、表数量及迁移历史一律以实际为准，不能抄旧报告。

建议实际具名测试：common LocalRunAdvanceAuthorityTest；engine RunAuthorityPersistencePostgresTest、InitialMaterialScopePostgresTest、RunAuthorityLocalCurrentPostgresTest，必要同包最小fixture。fixture保存同一portal source→真实gateway exchange main票；engine标准decoder+JwtAuthenticationToken+MockServletRequest，HandlerMethod取真正RunController.run的Method，调用真实ServiceAuthorization.preHandle建立frame，再requireInitialFor；finally真实afterCompletion及Security/Request清理。不得反射bindRetained/new VA伪造证明、不加假目标业务handler。此为guard+store组件测试，不是实际MVC/filter/controller方法体或已发布FLOW。

测试要有可编译实际行为红→实现→有限具名绿，setup/编译失败单列。最少覆盖：

- AB/AB真实create/authority/三scope/三实际read；current已A/requiredAB提交前全回滚；受理后A读取零payload、current403且保留历史。
- 新same-owner Grant/initialABC/currentABC读旧AB，旧grant到期不误伤普通read；错误currentA/owner/root/action拒绝。不要和后台原grant严格绑定混淆。
- 缺proof/currentOnly/旧bind/过期/替换/跨线程/已关闭frame，create第一步拒绝；不读历史payload、不自动补权。
- 真PG故障在run后、authority后、第二/第三scope、随后node后，全部同事务回滚；重复requestId不补旧行。旧run无authority/少scope load/current/read失败，旧payload0。
- actual version metadata各类型/值/范围错；scope tenant/owner/source审计/parent/hash/version/kind错误、重复写拒绝；数据库不可变保护实际UPDATE/DELETE负例；labels并集不丢current新增/config/parent。
- 精确数字/null/数组PG往返、canonical摘要；scope核对与payload SELECT间并发修改有限闩锁，同快照/不可变保护；READ_COMMITTED加入拒绝。
- 实际engine resume+current各一次，新DELEGATED在旧PORTAL到期但原grant有效时可成功，scope仍合法；当前撤权/grant过期/503/篡改绑定拒绝。
- 非main originalCall、错caller/target/root/source、exchange/client/forward local拒绝；真实HTTP guard拒绝local票，原HTTP目录无假条目。
- CAS交错旧结果0行不返回成功、不覆盖新阻断；503到期前零网络，可信Clock到期后只清自己临时阻断；未知错误不伪装授权503。

沿.tools/run-iam-module-tests.mjs闭合参数运行，日志stage唯一，立即归档每轮raw/XML/source（不能用target旧XML顶替）。新增common/shared变更最终一次完整common，engine只运行新/确受影响组件类；不重跑已绿D2/D3/七reactor/旧427等无关套件。root/reviewer不复跑作者同码绿。真正固定版本upstream源代码阅读记录（Spring事务隔离/加入、Jackson精确数值、Nimbus/Security local scope所需）写出处/SHA/不变量注释，未读不声称已对照。

报告docs/evidence/iam-stage-5-e1b-report.md与专属iam-stage-5-e1b/证据：实际baseline/source/target/protected before-after清单、真实失败/噪声、patch完整上下文及实际apply逐字一致、PG库/history/迁移号、红绿命令/XML计数/开源源码阅读、自审疑点、未测范围、归还source/target。无HEAD/remote，不伪造空git差异。保护采集只排除本段目录加斜杠及精确本报告，不排除同前缀brief。root同步文件新增/追加要逐文件列授权处置，原manifest不重写，不扩大目录白名单。

## 9. 后续门禁保留

完整Task5/联合D3–E仍需真实gateway metadata→main→app发布指针/快照→RunService接线，所有requestId/status/TraceStore/节点/事件/checkpoint/output/error/parent材料与实际owned调度、显式重新授权、CHAT/facts/approver/下载/回放/skill/model、RR1/RR2真实HTTP/stream。三初始材料不替代全材料；普通read新增用途要在其真实路径逐项验收。

仅T01完成，17功能/34任务/30验收未整体通过；不与旧962或各局部测试相加，不新打包/部署/推送，不宣称真实IAM/集群/容量验收。全部blocking依赖保留，组件通过后才下一接线切片。

## 正式授权（2026-10-20）

E1a已独立Spec compliant / Task quality Approved，review-iam-stage-5-e1a.md SHA 4d9683a01294ee34d5955c2b646ead5efaf137e34492ecdb84eac87e28ae1387，报告SHA f414394303aa6eec54e44c5711dd2470c925c574cf41641108af82fbca463239；root已全文读取审查并核跨任务边界。root审计.local/iam-stage-5-e1a-root-handoff-check.json确认346源及335 common target、其他1313不变；六份执行索引和维护项已登记，备份.local/checkpoint-iam-e1a-docs-before/manifest.json，全部after SHA复核。原v1.2实际SHA3c72、四契约和七模块边界再次核验通过。

现准备门禁满足，**正式授权E1b唯一作者按本稿实施**，source/target基线为iam-stage-5-e1a/source-after.json的346项（清单SHA 275b5c1d65948a5f594ea74662b0a4abe2765f7d953540ab27fa2c117dcff075），仅第8节文件范围与专用新PG库。root不运行Maven或修改作者src/target，历史报告/审查/提案不再追加；后续root如确需新准备文件，逐文件通知并处置保护差异。E1a两项日志Minor保留，不为它们重跑旧绿色轮。实现遇到具体API/迁移/事务疑点先报root裁决，不能扩大范围或转向用户重复索要环境。
