# EX-06 AP下一阶段方案（收敛后的静态候选）

2026-10-29，docs_reader_check。本轮只做安全证据归档、源码阅读和本方案；没有运行产品/PS候选/Node/Java/HTTP/PG/服务，没有修改脚本、三主入口或Git index。当前Ontology已由root实际执行，不把本方案记成AP交付、All/J/S1通过。首次AP方案的源码指纹见`ap-stage-source-index.json`，它与`archive-verification-20261029.json`均保留历史时点，不随模型候选改写为新版验证。后续执行顺序、精确候选白名单及当前模型源SHA见`ap-stage-execution-candidate.md`；下列路径均相对AP仓，OR特别标明。

## 1. 已有实证及边界

- `protocol-ontology-20261029-1428/`保存root实际WindowsPS5协议red native1/到达22检查、NullString单处修复后25/native0，以及准备32/native0。红是实际第二次私密原子替换失败，不是故意stub或缺文件。当前Common A008与保留C2A6的全文差异恰为`File.Replace`备份路径参数`$null`换`[System.Management.Automation.Language.NullString]::Value`；独立proof原目标保留。测试驱动/fixture未换断言。原始安全回执7份已逐字节与私有原件核同，原带密env/log不归档。
- `ontology-20261029-1438/`保存root首跑143857-277与Resume144116-754：native0、sourcesStable=true。同session `joint-or-20261029-01`，两份partial均`ONTOLOGY_READY/complete=false`。49 actions、resources、context深比较完全相同，新增/删除action0、pending均null、6凭据/6不同principal。两份credentials.env只算SHA且同为`615615207b70cfb9dd919af22490174de9a4e97bcda13898308007d45e416ed5`；没有解码/复制其内容。4份安全run/admission与原字节相同。
- 首跑完成49个登记动作，不等于49个独立资源。Resume没有新增journal动作/资源映射/key字节，但实际重读/消费校验会产生服务审计/消费事实，不能称绝无DB副作用。A两READY版本与B独立READY/临时组已在safe projection保留；本次没有再次HTTP查服务。
- AP目前只核dev身份。三应用、真实manifest导入、技能连接/选型/发布、模型端点、应用评估/发布与J/S1入口均未交付；默认All继续显式拒绝，不能降级Ontology后返回全链成功。

## 2. 实际管理API与权限

| 阶段 | 已有API/准确字段 | 实际门槛与源码 |
|---|---|---|
| 身份 | `POST /auth/dev-token`，取data.accessToken和data.user；不发自报tenant/roles | `platform-gateway/.../api/DevAuthentication.java`，固定local-v12/developer-v12、ADMIN/EDITOR/APPROVER、region=LOCAL；正式模式不能擅用dev入口 |
| 模型供应商 | `POST /admin/model-providers`：code/name/baseUrl/trustLabels=[LOCAL]/protocol=OPENAI_COMPATIBLE；TestFixture不需真实credential；`POST /admin/model-providers/{id}/test` | `ModelController.java:30`，`ModelStore.java:58`与`ModelCalls.java:113`要求ADMIN；新建DISABLED，实际连接测试成功才ENABLED；支持根URL或/v1，禁止带userInfo/fragment |
| 模型条目 | `POST /admin/model-entries`：providerId/modelName/displayName/contextWindow/maxOutputTokens/capabilities=[chat,tools]；`POST /admin/model-entries/{id}/test` | `ModelStore.java:265` ADMIN；窗口>输出上限>0；ModelCalls.entryHealth实际测目录/chat/tools，不把HTTP200直接当全部能力通过。Real只核显式ModelEntryId所属本tenant、entry/provider ENABLED及所需能力/信任标签，不能取首条 |
| OR正式导入 | `POST /admin/skills/import-ontology-manifests`：`{manifests:[原正式manifest],authScheme:API_KEY,authRef:<本session consumer ref>}` | `OntologyImportController.java:21`、`OntologyManifestImports.java:41`；ADMIN且SecretVault实际可解析该tenant ref；2MiB上限；必须HTTP/SYNC/POST、preview=false、正式releaseId。返回DRAFT，未自动发布 |
| 技能连接 | `POST /admin/skills/{code}/drafts/{version}/connection-test`，首次`{inputs:{},requestId:<会话唯一>}`；未完成时用返回controlledCallId重检 | `SkillController.java:105` EDITOR；`SkillGovernance.java:40`带authRef再要求ADMIN；走真实engine DEBUG run和非Mock SUCCEEDED执行事实，不能只探端口。保存runId/callId、SHA/semanticDigest/ok；有界查run终态后重检，不无限重建DEBUG |
| 选型/技能发布 | `POST .../acceptance`：modelEntryId、cases[{question,expectSelected:准确code,expectArgs:{}}]；`POST .../drafts/{version}/publish`无请求体 | `SkillGovernance.java:119`真实model chat选择工具并验入参，至少80%；`SkillCatalog.java:881`要求同版本/sha/digest的真实连接与选型报告、只读守卫声明。失败保留DRAFT与报告 |
| 应用创建/配置 | `POST /admin/apps`：code/name/type=CHAT及说明；`PUT /admin/apps/{code}/config`：draftRev/config；每步使用最新返回draftRev | `AppController.java:32/67` EDITOR；`AppService.java:321/351`同tenant唯一、乐观修订；config显式modelBinding.modelEntryId、inputs、systemPrompt、params、skillRefs[{code,version}]、guard.roles/attributes、ontology。使用skillRefs，不把seed示例tools误作引用 |
| 应用评估 | `POST /admin/evaluations`：appCode/draftRev/candidateSha256/datasetVersion/cases[{id,inputs,expected,safety,assertion}]；`GET /admin/evaluations/{id}`与`POST .../{id}/resume` | `EvaluationController.java:18` EDITOR；`EvaluationService.java:127`绑定真实冻结候选、1至200用例、至少1条safety=true；RUNNING不是通过。必须等待ACCEPTED且匹配本候选；安全用例只可EXACT/SCHEMA（EvaluationAssertions.java:133） |
| 预检/发布 | `POST /admin/apps/{code}/publish/precheck`无body；通过后`POST .../publish/commit`：idempotencyRequestNo/precheckId/draftRev/candidateSha256 | `AppService.java:927/1406`，真实模型冻结、技能引用、守卫、评估报告门；检查passed、gateMode=BLOCKING、评测项PASS及本次evalReportId，无WAIVED/ADVISORY/OFF。预检15分钟；commit同请求号可重放，不能换号补救未知结果 |
| 正式运行/观察 | `POST /admin/apps/{code}/runs`：requestId/inputs等当前RunService协议；`GET /admin/runs/{id}` | `RunController.java:49/89`真实入口；不得在body临时覆盖ontology。CHAT最终输出含text/usage/actualModel（AgentDriver.java:133），评估不能只拿{text}按EXACT假通过 |

连接、导入、选型和发布是不同证据。connection-test响应没有reportId，只记录实际runId/controlledCallId、ok、latencyMs、candidateSha256、semanticDigest/digestVersion；选型acceptance有id，不把它混作连接ID。真实导入接真实OR、真正X-API-Key出站和受控执行，不能把TestFixture模型描述成真实外部模型。J4分页扩展需真实offset/limit操作另有证据；当前JointTask SELECT无分页参数，不能宣称覆盖EX-04分页正向。

## 3. 复跑与资源清单

1. **已有OR journal保持原值。** 当前resources.opCode=joint_list。AP阶段前先GET本tenant目录精确查该code；若尚未存在，按原正式manifest首次导入即可。此处是待root实际只读检查，不宣称当前已存在/不存在。若已存在但不属于同session成功导入action，明确冲突停止，不能删/覆盖旧技能或改OR回包。
2. **后续新session派生opCode。** OR `OperationService.java:35/46`允许字母开头、字母数字_-、长度≤80；AP `OntologyManifestImports.java:102`和`SkillCatalog.java:295`同字符集、长度≤64。新session确定采用`joint_`+SHA256(UTF8 SessionId)前24小写hex，合计30字符；首次业务写前固化journal，后续只用journal。哈希冲突仍查资源归属后拒绝，不承诺数学无碰撞。旧partial没有新字段时从已成功operation结果/resources原值恢复，禁止重新派生改变joint_list；B可同opCode但不导入B技能。需要随后脚本补差+协议/真实复跑验证，本轮未实现。
3. **AP journal新增安全action。** 原正式manifest摘要/space/release/opCode/authRef、返回skillVersionId/versionNo/sha256/semanticDigest、连接响应安全字段（无reportId）/选型实际id、providerId/modelEntryId、三appCode及每个draftRev/sha/evalId/precheckId/commit请求号/发布版本号。私密key/token不入action。每次真实写操作前先原子持久pending（包括connection/acceptance/evaluation/precheck/commit），响应只白名单摘取；超时/断连/响应畸形/落盘失败一律保留pending并unknown-stop，禁止自动重试或把未知结果当未创建，与OR阶段同规则。已确认的同sessionaction读回比对后复用；已有同code但无归属记录不据名称猜本会话。API公共skill回包只显示authConfigured，不能从回包重建authRef或证明secret有效，必须核私密ref与实际连接。
4. **资源数量。** 本阶段新增1个本session OR-A导入技能；TestFixture新增1个本session provider+1个entry+1个有界loopback模型进程，Real复用显式entry、不创建provider。新增3个独立应用、每应用独立dataset/eval报告和必要run、3次准备期正式commit；J9另对pinApp变更releaseId形成新候选、重新评估/预检/发布。S1其它正负例使用独立candidate/app，不能借三应用的旧ACCEPTED或semanticDigest复用污染负例。绝不调用整租户purge/cleanTenant；清理按本session已登记IDs并先核引用，不能删已发布技能历史。
5. **env链仍由root激活。** 已有受限app.env/engine.env/skill.env分别只供binder/readonly/consumer；模型夹具无需OR key。先完成下一节DB/JAR准入；root才可按EX-01原ServiceEnvironmentFiles机制在本session专库启动/重启服务，核真正PID/JAR/安全env SHA、六健康后才进AP写操作。不得把start-backend当无副作用预检入口。写文件不能宣称运行进程已载入；脚本不偷偷接管其它监听/清共享库。OR管理凭据仍仅从OR `.runtime/local-env.json`的ONTO_BOOTSTRAP_USERNAME/ONTO_BOOTSTRAP_PASSWORD安全构造PSCredential，不输出值。

## 4. 服务/JAR/数据库窗口先决门（先方案，未启动或创建DB）

2026-10-29T07:03:00Z静态文件盘点见`ap-stage-runtime-inventory.json`：该时点六服务JAR均已存在，准确SHA/大小/修改时间、current src/main逐文件SHA及聚合SHA、source/target/JAR三份迁移清单齐备。文件存在与迁移相同**不证明Java类来自当前源码**，未运行进程/编译核验。下表仅是该时点JAR指纹；root后续构建可能改变它们，运行前必须重新逐一核源/JAR，不能把历史盘点当当前六服务准入。

| 服务/默认端口 | 当前JAR SHA256 |
|---|---|
| platform-gateway/18080 | 9ecb0173d829b637a2a56e76e743b5ff53f2db0eb576df82073753490e4bf592 |
| app-management/18081 | f36bb449adbaa5c723272d57c1d6aba0a0adc8296e814b413aa34466d891df0e |
| engine-runtime/18082 | 76ab67c3a72cd1931e2dc64e053cae8a11a86a850cfa9da5eb95fee1132b419c |
| skill-registry/18083 | 844ef00c928bed9961cf0cfe51468d864c2199df9fa1e2b60a775003eb5ae0be |
| model-gateway/18084 | 24dccc26cc4c7c384307c85428586004cdf0f3833e44743588b5b9d503b401d6 |
| monitor/18085 | 4f58af2075c3e8e7237845073ea63ac7c75c44f613b30e0393ac1adbb81c81a3 |

- **V222确切准入。** 盘点时engine源码23份迁移，target/classes及JAR24份，唯一多出`V222__run_context_pack.sql`；该文件SHA为`419c753c652b326dbd59de40409f080a16f26b98787a7f8cf870b6ecf2859a90`，内容给engine.run加context_pack jsonb。候选准入仅可使用经root确认可追溯、且JAR整体SHA=`76ab67c3a72cd1931e2dc64e053cae8a11a86a850cfa9da5eb95fee1132b419c`的这份既有包，同时重新核target/JAR V222字节；任何重打包/源变化先停，由EX-27重新确定可追溯包与迁移清单，不自动沿用此值。root只在本session新空库执行既有Flyway，初始无history，启动后24份准确迁移及V222 checksum=701700386/success=true须真实读回；701700386是既有历史期望，尚非本次已验证。不得clean/repair、复制证据SQL进源码、伪造history或省略V222后称同一环境。Java构建来源还需单独核，不以迁移同SHA替代整包来源。
- **选库前先由root只读查归属/history。** 必须记录本仓25432 PG进程/数据目录归属、目标DB名称/owner、相关schema与各Flyway history版本/description/checksum/success，以及engine待恢复run/队列、skill到期/中断artifact与outbox/service实例归属。此次归档者没有连PG，当前DB owner/history/待清数据仍未验证，不能据默认URL或健康端点判专库安全；历史证据不能代本次检查。
- **采用本session唯一空AP DB才启动scheduler。** root完成归属检查后决定并登记唯一DB名/owner；本方案不创建DB、不选取现共享库、不清已有表。六服务涉及app/engine/skill/model/monitor schema应指同一会话数据库，所有服务用各自受限env明确AGENT_DB_URL，不能只app/engine切库而skill/model/monitor仍指全局.env。ORM schema按既有配置；凭据不入清单。若唯一空DB无法按已准入迁移初始化，保留准备失败，不降级复用共享库。
- **scheduler不是被动健康检查。** `skill/infra/ArtifactStore.java:645`定时遍历到期/UPLOADING/CLEANUP_PENDING后清对象；`ServiceLifecycle.java:283/291`启动/定时处理旧服务，SkillOutbox也定时投递。`engine/application/RunScheduler.java:85/276`独立timer立即poll/claim旧run，单纯关Spring Scheduled不能证明不恢复。故不得先把skill/engine接含未知数据的共享库再“看看能否启动”。本次唯一DB与对象前缀登记后才可启动，不靠无限延迟清理规避原产品行为。
- **必要既有依赖。** JDK21（根POM要求[21,22)，Spring Boot3.5.0/SpringAI1.0.0）、本仓PG25432的本session新唯一DB、本session独占Redis物理endpoint（root先核空闲独立端口；engine/model均指向它）、OR18182当前会话READY资源、MinIO endpoint/bucket/本session对象前缀及既有受限凭据。小查询不保证永远无artifact，MinIO不可伪造；不能启动新共享存储栈/删除旧对象。按原服务yml和ArtifactStore的AGENT_MINIO_*配置键核所需输入，只报键/路径和安全指纹。RedisChunks实际用PubSub，因此仅换逻辑DB/SELECT或前缀不能隔离channel；不得复用26379现有实例，不FLUSHALL/FLUSHDB。Redis进程、私有目录/配置、端口/PID、endpoint和生命周期须先登记，只有root启动/停止自己本次实例。
- **原启动器有副作用。** `scripts/start-backend.ps1:140`先调用start-database、装载全局.env并可package，再启动服务；`start-database.ps1`会初始化/创建默认库。root必须先确认同一专属25432已归属、现.env与per-service覆盖计划及本sessionDB边界，再决定使用原启动器或原已授权安全wrapper。此方案不自动执行它、不增加另一个启动框架。首次先gateway+model协议薄步；完整技能链需app/engine/skill/monitor都在同一准入库，才开放HTTP写入。

## 5. 三应用真实发布与J2/J3/J9的固定顺序

root已选择三应用准备期全部真实评估/预检/发布；取消“pinApp首次commit留J9”候选。

| 应用 | 冻结ontology | EX-06准备期动作 | EX-07后续动作 |
|---|---|---|---|
| appCode | `{space:<A UUID>}`，无releaseId | 不创建初始pin；真实ACCEPTED→新鲜预检→commit。ReleasePins应SKIPPED，原因缺releaseId；远端该app/environment仍无ACTIVE绑定 | J1 readonly负例无写；J2 binder首次建pin应changed=true、第二次false；J3必须J2之后运行并验证真context-pack；J5换版、J6解绑/故障 |
| explicitAppCode | `{space:<A UUID>,releaseId:<旧版UUID>}` | 真评估/预检/commit自动pin旧版；核PINNED、OR绑定与app.publish_pin相同 | J5把远端pin改new，冻结配置保持old，证明最终run=old |
| pinAppCode | `{space:<A UUID>,releaseId:<旧版UUID>}` | 真评估/预检/commit自动pin旧版并核三方一致 | J9保存config.releaseId=replacementReleaseId形成新draftRev/sha，重评估/预检/commit，再同idempotencyRequestNo及相同发布body重放 |

动态app准备期评估会走真实BINDING_NOT_FOUND默认跳过context；保存该事实，不把它当J3已通过。不能为让评估通过预pin后再删，不能只在request自报ontology救场。EX-06完成判据是3应用均真实PUBLISHED，同时动态app仍无初始pin，另外两应用初始pin=old。J2须在动态app第一次真实绑定之前；各用例会话/应用精确隔离。

J9不是首次创建绑定，而是平台真实新候选发布导致现有绑定更新：必须仅在pinApp被冻结config里把releaseId从old改new；草稿revision/sha/semanticDigest确实变化，使用新datasetVersion与新实际ACCEPTED eval ID，预检准确命中本候选/报告，gateMode=BLOCKING、评估项PASS，无豁免/ADVISORY/OFF/旧语义命中替代。发布后保存新version/sha、OR ACTIVE binding=new和app.publish_pin一致；同body/同idempotencyRequestNo重放，核版本/绑定身份未重复创建。重放仍可能发远端幂等POST并写审计，不能声称绝无HTTP或DB事实。

`ReleasePins.java:67`只在space/releaseId及enabled/baseURL/ref齐时发POST `/onto/internal/release-bindings`；AppService.java:1608在发布事务提交后才pin，失败不回滚已提交版本。正常已成功发布后的J9重放是明确用例；遇超时/502 PIN_FAILED/坏响应则保留pending并unknown-stop，先由root只读核版本/绑定/观测再决定同号恢复，不自动换号创建另一次发布。预检有效期15分钟，每次实际发布前现场获取。

## 6. 下一最小交付与真实窗口

1. **模型协议结果独立保留。** 初始两路径封版、独审无阻断，原25+新增38=63项；后续root实际执行与Handle最小工具修复各由其原件记录，不把本方案倒填成新版同源绿、不修改旧候选SHA。原准备32不变。入口/常量/工具目录和未知/畸形/大体/nonce停机断言齐，未提供SSE。服务仅Model协议，不伪造评估/发布；实际63结果以root采集为准。
2. **下一实现先opCode小步，独立封版。** 只动Prepare/Common与准备层驱动：新session固定joint_+SHA24，旧partial从真实已登记资源保留原opCode；所有operation查找/端点授予/消费URL/manifest引用统一使用该值。先追加确定性/长度/旧partial/矛盾拒绝用例，保留32原断言；不同时接AP写链。准确候选白名单见执行报告，需root另行准入后才能写脚本。
3. **运行环境候选先准入，后实际启动。** root核PG owner/history及旧数据，决定唯一AP DB、独占Redis物理endpoint、精确6服务JAR/源/私密env。TestFixture provider使用FixturePolicy可识别的`fixture_joint_<SHA24>`；model与engine的受限env都显式AGENT_DEMO_MODE=true。实际provider/entry读回source=FIXTURE，run详情/快照runtime.environment=DEMO、demoMode=true，不能在REAL名义调用固定模型。仅模型协议测试无需上述AP运行环境。
4. **资源链逐小步。** 先只provider/entry真实创建+test并封版；随后独立OR-A正式导入→受控DEBUG真实连接→选型→技能publish→读回；再逐个三应用ACCEPTED/预检/commit。每步pending先落、未知停、已确认action同session校验复用；未全部齐不出成功manifest、不签新key补救。TestFixture只证明固定输出协议，Real需显式已验证ModelEntryId及真实访问权。
5. **最后补最终All与J/S1。** 3发布应用/指定绑定状态、OR全部READY/ACTIVE/私密ref、19主字段+revocation/isolation均实际校验才原子写完整manifest。Invoke-JointAcceptance按J1→J2→J3后续直到J9；Invoke-S1Acceptance各分项且负例独立candidate，不能复用三应用旧ACCEPTED污染。当前All/J/S1尚未实现，complete=false保留。

## 7. 仍未解除的准入项

- 模型63及后续工具修复只依root实际原件；原25/32历史绿不能代新版63，本方案不覆盖其失败/通过历史。
- opCode兼容、AP资源链与All/J/S1仍需实现和实测；当前joint_list是否AP已占用需root真实只读查，冲突不覆写/删旧技能。
- 新唯一PG与独占Redis端口/实例、6服务源/JAR/env与V222启动后history尚未本轮验证；本报告不启动任何运行时。
- Real外部模型、J4真实分页、S1异步SUBMIT_QUERY/故障桩及真实worker等依原卡分项。普通模型CONTAINS与安全SCHEMA(text.const=EX06_REFUSE)可用于显式TestFixture，但不能把宽松空schema或safety=false伪作安全验收。

所有后续执行均按`ap-stage-execution-candidate.md`逐步再准入；本轮仅改本方案及该候选报告，不改主文档/产品/现有脚本/index。
