# O1a：独立运行观察的真实元数据接缝

状态：正式要求已准备，未派发、未实现。D4b前端完成独立评审并交还唯一source所有权后实施；本切片不开放外部GET status/events。用户已授权持续开发，无真实外部环境时使用隔离端点，不重复询问是否继续。

## 1. 固定范围与依据

唯一工程 `G:/207/项目/算法重组工具/代码/agent-platform`，所有shell显式workdir；遵守AGENTS.md。不读取、引用、复用或改变相邻旧代码、配置、数据库、缓存、依赖及进程。依据v1.2、既有IAM实施计划Task4–6和§2.4、root接受的`iam-next-run-observation-seam-proposal.md` §3；该提案SHA为`e6dede6002498c1580a607e39fd2c3fa3dd4703359e847892fe5319d3ea6a0e4`，它只提供已核路径，不是已实现/已测试证据。原v1.2、四契约、计划裁决、固定版本、七模块边界不改，不排工期、不部署、不开放生产IAM。

依赖决策：O1a metadata → O1b engine只读status → O1c gateway真实GET联合链 → O2 PORTAL SSE；RR1 INTEGRATION材料与RR2模型恢复另列。原POST创建/重复返回status、DELEGATED后台执行及现有生产闭锁保持原目的。不能让元数据接口顺便开放status/debug/v1/SDK/events/写入/CHAT/技能/模型/确认/附件/回放。

原后台持久资源仍为app-version，grant/initial ceiling/origin不变。未来status使用同originalPortal的新独立exchange，签名资源为 `ResourceBinding("run-snapshot",publicRunId,acceptedSnapshotSha256)`；不resume/forward原执行grant，不把query票改成status许可。O1a只落实前面的独立query及真实PG元数据，不新增derive协议，也不宣称生产authority已支持资源关系解析。

## 2. 精确内部接口及selector

只新增 `GET /internal/runs/{id}/observation-metadata`；caller=gateway、target=engine、action=`run.observation.metadata.read`、mode=delegated、source_kind=PORTAL、origin_kind=PORTAL，integrationAllowed=false。加入精确catalog行及实际handler覆盖验证；不能增加ANY或别名。query/action/kind/target/caller/mode/source/origin双向绑定：该query action只能配该selector kind，该kind不能用于其他动作/服务/来源。特别不能把查询票用于status/events/POST/本地推进或workload。

新增 `RunObservationSelectors`（common authority包）共享不可歧义算法；`metadata(SubjectKey verified,String publicRunId)` 为建议签名，作者实施前回报最终API。kind=`run-observation-selector-v1`；id为 `Json.canonical(List.of("run-observation-selector.v1",tenant,principal,publicRunId,"run-observation-metadata.v1"))`；versionDigest为以下descriptor的 `Digest.sha256(Json.canonical(descriptor))`：

```json
{"schema":"run-observation-selector.v1","tenant_id":"已验证租户","principal_id":"已验证主体","run_id":"真实public_id","projection":"run-observation-metadata.v1"}
```

descriptor全部为字符串，不受浮点/嵌套数值规范化歧义影响。tenant/principal非空且长度不超过128；publicRunId只接收现受保护published run生成的 `r_[0-9a-f]{32}`。无效输入400，不能使用URL/query/header自报身份。query摘要版本化查询语义，不冒充实际run数据版本。

controller拒绝任何raw query（包括bare `?`）、request body、重复/不支持dispatcher；由实际ServiceAuthorization/JWT及request frame确定身份与方法模板，再比对由该身份和真实path构造的selector。已有header/JWT/service guard负责的公共限制继续使用，不为这个接口引入宽泛新过滤器。具体已有API和原始URI处理在源码确认后回报；不能仅检查@RequestParam Map为空就声称bare `?`已拒绝。

## 3. 真实PG投影与读取顺序

成功以现有ApiResponse包装精确七字段数据对象：

- `runId`：真实run.public_id，与path全等。
- `owner`：精确tenantId/principalId，两者与已验主体及run/run_authority一致。
- `root`：精确kind/id/versionDigest，真实原app-version根；不是新observer许可。
- `appVersionId`、`appVersionNo`：真实正整数及run/run_authority一致的版本。
- `acceptedSnapshotSha256`、`initialCeilingSha256`：真实run_authority的64位小写hex摘要。

DTO仅数据，不提供Actor/VerifiedAuthority/retained/read/write capability转换。禁止输出grant_ref、initial_ceiling、源JWT/票据、decision原文、内部numeric run id或任意scope/payload。成功data的UTF-8 JSON字节数不超过4096（不是字符数，也不是整个ApiResponse），在新服务输出时应可证明（本地序列化检查即可），响应不能静默截断。appVersionId/No均用正long，不缩为int；类型/字段/哈希合法性在域投影时检查，不能让损坏metadata变成貌似有效的200。

先验证真实ticket/action/selector及当前frame，再以public_id与已验tenant/principal窄查控制metadata；不存在/其他owner统一404，不暴露是否存在。已归属但缺run_authority、无完整必要根材料metadata或根/版本/owner/initial摘要不一致，403且不回落legacy。原后台grant过期/blocked不代表当下新的独立query自动被拒绝；query是否允许由它的新当前授权决定，不清blocked、不换grant、不更新运行。

窄读取可在现RunAuthorityStore添加受限投影，复用既有load(db,id,false)的控制绑定校验后显式scopes(db,b)，或load(db,id,true)；false本身不含根manifest，不能视为完整校验。也可采用同等单只读RR快照/一致statement投影。实际选择与精确列实施前回报。不选run.snapshot/inputs/output/error、node input/output、event payload或tool result；不调用TraceStore.run/普通composeStatus或预读payload后过滤。不对整个run做select *；内部既有a.*只是authority控制字段，如复用必须准确说明所读列/字段不出域。必要scope存在性/根绑定只读manifest，不取payload。

同一只读RR快照内重核owner、numeric/public id、app根、数字版本、acceptedSnapshotSha、initialSha和根manifest的一致性；返回前本地终检live retained frame。复用AuthorityContext.requireInitialFor(actor,selector)在事务外刷新，入场/读取结束/返回前requireBound(proof)，另核固定query目的；不经过原执行用requirePurpose/strict。新入口先拒绝ambient transaction，再建立顶层只读RR，不能仅依赖会加入已有RR的snapshot helper声称readOnly。在线authorize/refresh在DB事务之外，事务内不联网、不获取写锁、不改全局隔离、不无界重试。proof终检的位置与HTTP首次写边界如有差别据实说明，本切片不把有限返回前校验扩称已验证所有后续wire边界。读取前在线刷新已发现的到期/撤权/503应拒绝；读取后本地proof到期/frame失效应阻止返回有效投影。未再次查询的远端变化不能由本地终检发现，不承诺瞬时撤权。

可信存储前提仍是实际PG及原immutable触发器；metadata-only校验只能检查已有冗余关系、类型、摘要格式和manifest绑定。原resource_digest与snapshot payload中的candidate关系没有独立冗余metadata：绕过immutable触发器后替换为另一自洽合法64hex，O1a不能重算证明错误，不新增receipt或读取payload迁就测试。root负例限定kind/id与app_code/versionNo矛盾、非法hash或其他真实冗余绑定/manifest不一致；任意恶意DB自洽篡改不在此有限信任模型。实际payload摘要关系属于O1b读后校验，不把它假报为O1a零payload拒绝。

三根manifest必须恰为run-snapshot/run-inputs/run-version-refs，沿现scopes校验owner、snapshot版本、initial ceiling、原admission decision/version、manifest摘要及父链。有限published pure FLOW来源依赖原可信受理和immutable存储；可用trigger_type/mode、debug/eval存在性及controlled/tool存在性等窄控制信息拒绝已知未迁移用途，但metadata-only不能重新证明snapshot内部节点图。不得为证明pure FLOW先读取snapshot payload。

## 4. 文件和装配边界

候选新增：common authority `RunObservationSelectors.java`；engine api `RunObservationMetadataController.java`、application `RunObservationMetadataService.java`、domain `RunObservationMetadata.java`及必要独占测试。候选修改：common `ServiceAuthorization.java`实际purpose调用、`agent-service-authority.json`、测试fixture `LoopbackAuthority.java`；engine `RunAuthorityStore.java`，若需真实注入/投影则仅窄改TraceStore或现有装配。不要预先修改CurrentAuthorityClient/HttpCurrentAuthorityClient/GatewayIngressBoundary/POST proxy/运行writer来实现尚未开放的status；发现必要性先报root具体原因。

必须在任何source编辑前向root列明精确文件、API、selector检查点、PG读列/一致性方案和测试selectors，root常规范围批准后再落地。后端api/application/domain/infra分层保持；跨服务HTTP，不引入其他业务JAR。新controller/service默认legacy上下文不能硬依赖不存在的IAM bean，接口本身缺live IAM frame应拒绝；生产global IAM关闭保持。原系统开发入口不应因新增constructor依赖无法装配。

本切片所有文件/配置/SQL/前端依赖均以交还时冻结基线保护；无数据库迁移、POM/依赖/lockfile变化。必要JVM子进程只能独立本模块classpath（common test fixture jar允许），不注入其他业务JAR/反射加载兄弟target/classes。可复用已有有限fixture机制但不得改旧封存报告/脚本，不能复跑旧全局扫描或主业务helper。

## 5. 关键行为红绿与验收

测试使用真实LoopbackAuthority验签/exchange/authorize、真实engine HTTP security/handler/ServiceAuthorization、真实隔离PG。真实生产受理方法创建受保护pure FLOW根及material_scope，再调用本接口；可以复用现已验admission/domain fixture，不以Map、裸伪造DTO、只new controller或手填不经生产受理的假run证明闭环。独占schema/数据库按既有V200–217真实迁移建立，不碰主DB。真实gateway完整进程/POST worker不属于本切片，后续O1c再贯通，报告不得混称已完成双服务GET。

先失败路径测试后实现，仅新增/直接受影响具名selector定向验证；建议 `RunObservationSelectorsTest`、`RunObservationMetadataHttpPostgresTest`。命令使用现 `.tools/run-iam-module-tests.mjs` root cwd、精确modules及合法selector；不改runner、JDK检查或用`+`拼selector。行为红须实际请求到真实预期校验边界；缺类、错装配、编译/脚本失败单列setup，不能当行为红。

必要合并案例：

1. 正例实际签名query票→engine HTTP/PG七字段精确投影，payload SELECT=0；记录exchange/authorize/实际handler/metadata SQL、RR/readOnly/连接标识、真实HTTP返回。固定响应既不含grant/initial/源令牌，也不夹带inputs/output/event内容。旧app发布指针变更不影响钉住历史run根；若该fixture未启app，不伪称真实app指针切换，只核run固定根。
2. 同app/同版本的另一个run、另一owner/tenant、path/selector digest篡改、错误action/caller/target/source/origin、workload/query票撞status/events/POST，均在受保护payload前拒绝。用实际请求、authority计数及SQL监听证明；不可只断言最终403。
3. 404统一隐匿不存在/不归属；缺authority、缺根scope、root kind/id与真实app_code/versionNo矛盾、数字版本/owner/initialSha/snapshotSha冗余绑定或manifest不一致、hash格式非法等真实PG控制metadata损坏拒绝。合法自洽resource_digest替换的信任边界按上节披露，不虚构metadata-only可检测性；不把输入普通字符串校验当PG绑定负例。
4. raw query/bare?、body、未支持alias/dispatcher、实际handler/guard装配遗漏拒绝；既有D1实际coverage机制适用，不宣称仅catalog行即可证明安装。
5. 读取前当前到期/撤权/503时metadata或业务payload计数按实际位置归零；读取后闩锁推进时钟/关闭frame，不能返回有效投影。source与当前授权错误语义分别据实记录。无旧票回退/缓存allow/后台resume或写业务副作用，run/attempt/events/worker/provider/skill新增=0。
6. 只对受影响purpose/route增加直接回归，证明既有app published selector仍按旧边界工作、query不能变成写许可；无需重新运行所有已绿common/后端套件。constructor/legacy覆盖是结构+有限实例还是完整启动必须分开写，未启动不得宣称完整boot验证。

SQL监听应保留实际执行SQL并区分metadata查询、manifest查询及真实payload读取；旧CountingDataSource只匹配部分模式，其payloadReads=0不足以证明没有select *或直接event payload读取。需同时核真实SQL、连接隔离/readOnly。不能把内容摘要损坏的读后拒绝写成payload=0。所有counter必须对应实际代码/HTTP/DB，不用模拟最终wire代表真实序列化。测试authority只是隔离profile，不代表真实IAM产品或开放生产。

## 6. 证据、所有权与交还

作者唯一source/对应target writer；root不读半成品、不同时改source。作者独占 `.local/iam-o1a-run-metadata/`、`docs/evidence/iam-stage-4-o1a-run-metadata/` 及报告 `docs/evidence/iam-stage-4-o1a-run-metadata-report.md`；root独占八执行索引、review、current-work-state和未来brief。每约60秒向root发简短真实进展。

复用D4a/I1/backend380、target1933及D4b最终前端/构建的不可变谱系，交还实际baseline描述。每次实际命令前保存完整必要源码SHA清单及仅变化物理overlay；旧全量证据、后端JAR、前端node_modules不重复复制/扫描。保留初始scope与后来获批增补的真实时间，不倒改事前清单。失败/setup/oracle/工具错误和实际stderr原件保留；已完成物理阶段在工具元数据错误后不重复执行，续未完成部分。

交还精确source/target delta、最终frozen overlay、actual root cwd patch check+apply重放及全部逻辑源names/bytes/SHA一致、target归因、命令/cwd/exit/实际XML与最后有效覆盖，own精确manifest/bytes/SHA、资源PID/port/ready/close与未覆盖限制。不要以多轮累计冒充一次最终全绿，不包部署、不签收完整O1/O2/D/E/F/17功能34任务30验收。报告写完冻结全部source/target/own证据写所有权，再由root有限审计与独立review验收。
