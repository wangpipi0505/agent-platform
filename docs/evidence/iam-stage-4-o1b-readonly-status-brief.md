# O1b：engine 独立只读运行状态

状态：正式实施要求，O1a 已 Accepted；本切片未实现、未测试。用户已授权按计划持续开发，无真实 IAM/模型/本体/K8s 环境时用隔离端点，不再次询问是否继续。先向 root 回报精确源文件、API、proof 可见性、SQL/事务、首次写出与具名测试范围，收到常规范围批准后编辑。

## 1. 固定范围与已验基线

唯一工程和每条 shell 显式 workdir：`G:/207/项目/算法重组工具/代码/agent-platform`。遵守 AGENTS.md、implementation-brief 和 contracts/README。禁止读取、引用、复用、影响相邻旧工程代码/配置/数据/缓存/依赖/进程。原 v1.2、四契约、IAM 实施计划与裁决、固定 Java21/Boot3.5.0/Spring6.2.7/Nimbus9.37.3 等版本、六服务加 common、跨服务 HTTP、真实 PG 不变；无 POM/依赖/迁移/前端改动，不 package/deploy、不开放生产 IAM、不排工期、不 commit。

依赖：O1a metadata → **O1b engine 有限只读 status** → O1c gateway 联合 GET → O2 PORTAL SSE。这里只开放真实 `RunController` 的 **GET /admin/runs/{id} 的 IAM 分支**，限完整 run_authority/material_scope 的 PORTAL published pure FLOW。保留原完整 status JSON。debug/v1 alias、列表、variables、events、CHAT、技能/模型/确认/附件/回放、后台新动作、RR1/RR2 不新增能力；legacy 非 IAM 原行为与 POST 创建/重复返回 status 保持。

O1a 已独立 Spec compliant / Task quality Approved，root641项有限审计通过：

- 必要源578（后端386、前端192）：`iam-stage-4-o1a-run-metadata/source-after.json`，SHA256 `719caa92b7dc1c33ee620e923247bb57bad45cee5c0b9c210401dc9fde463510`。
- target1948：同目录 `target-after.json`，SHA256 `1404555e5bd1f0adfca1a3f74077d952dc9e9f62097a99dc60bf0ed2eed7bda2`；按该目录 composition 继承，不重扫旧 target/evidence 全树。
- 独立评审 `review-iam-stage-4-o1a-run-metadata.md`，14309B，SHA256 `c28e50f9ba869c9b260982bd98294149a610e5f43549c3161c08831985426ee0`。
- 八执行索引 checkpoint `.local/checkpoint-iam-o1a-docs-before/manifest.json`，SHA256 `04f6c2789ccbf9346e206debb511018a11a1d51374e0ae5f1bec407a3152a65b`。T01唯一整体完成。

设计来源已由 root 全文核对：`iam-stage-4-o1b-readonly-status-proposal.md` 28769B / `d1d68162f3fd11a7921296d0e4861f991fec7102c9db39ec87f80cd896636888`；`iam-stage-4-o1b-interface-finalization-notes.md` 20886B / `3326fdb88da860b87b692441cdfb7f98f7c03effa4950866e344108251e20566`。本文是最终要求；两份来源提供冻结原件路径/窄读范围，不是已实现事实。不要再读取所有历史报告，按具体接缝窄读。

## 2. 精确目的与授权分离

入站仅实际 GET `/admin/runs/{id}`：caller=gateway、target=engine、action=`proxy.engine.get.RunController.status`、mode=`delegated`、source_kind/origin_kind=PORTAL/PORTAL。资源为 `ResourceBinding("run-snapshot", publicRunId, acceptedSnapshotSha256)`，call 与 operation.resource 全等；publicRunId 为 `r_[0-9a-f]{32}`，sha为64位小写hex。它是本地 OBSERVER_STATUS 目的，**不新造 JWT authority_mode=observer**。

扩展现 `RunObservationSelectors` 的明确 status 分支，保持原 KIND/ACTION、metadata canonical tuple/digest 与 query 双向规则。固定 status action 只能配此资源与来源；此阶段 run-snapshot observer 资源不能被用作 POST/local advance/events/其他动作。不能只新增允许字符串而不限制反向误用。现 ServiceAuthorization.route 已在在线 authorize 前调用此 helper，默认无需再改该文件；catalog 已有 admin GET 行，默认不改 catalog，不新增 ANY/重复路由。不因共享 action 放开 debug/v1：IAM 分支必须核实际 HandlerMethod、BEST_MATCHING_PATTERN_ATTRIBUTE、REQUEST/GET、contextPath+真实 URI、原始 query（包括 bare ?）及 body，aliases/unsupported dispatcher拒绝。legacy原行为独立保留。

客户端先以 originalPortal 取得独立 Q，真实 O1a metadata HTTP 返回七字段 DTO；再以 **同一 originalPortal 独立新 exchange** 取得 M status 票。Q grant不forward/resume成M，DTO不携带读能力；M的resource版本来自真实 acceptedSnapshotSha，不能用 query摘要/candidate/status摘要。O1b fixture可扮演已配置gateway client，但不得声称已实现O1c实际gateway联合链。

原持久 execution root 仍为 app-version/appCode@versionNo/candidateSha；原grant、initial、origin、admission/version、blocked/check均不变。新M可以使用不同于原执行的grant/decision/version/initial/origin与新的portal认证；要求同tenant/principal，但不要求原受理token/JTI/originAuthenticationId与新观察者相等。原后台grant过期/blocked时，合法新M可读取；观察成功不清blocked、不延期、不resume、不唤醒worker。禁止调用原strict/requireWriter取得读权限或放宽其条件。

LoopbackAuthority只作隔离协议profile：新status分支必须从已验证source及可信fixture预先登记的run/owner/acceptedSHA匹配，exchange/authorize均核精确resource与原call。登记来源是生产受理后可信元数据，不能由待验请求随意注册，也不能靠全局scopeAllowed冒充按run拒绝。不要求authority连engine DB，不新增生产跨库协议。

## 3. 层次、私有只读proof及最小复用

建议固定API：RunController IAM admin GET → RunService.observeStatus → TraceStore.statusObserved → MaterialScopeStore固定只读消费口；最终名称/类型在编辑前回报。

沿原api/application/domain/infra分层，跨服务HTTP，不导其他业务JAR/反射兄弟target/classes。CurrentAuthorityClient/HttpCurrentAuthorityClient、gateway、O1a controller/service/domain默认不改；必要新增跨层结果类型优先窄嵌套类型，不新造通用读写框架。

采用 **包内纯PG helper复用**：从 RunAuthorityStore.observationMetadata 提取 owner/publicId、TASK/FULL/unsupported/tools 与 `load(db,id,true)` 部分为 package-private `loadObservationBinding(JdbcTemplate,SubjectKey,String)` 或同等窄方法。helper不联网、不启事务、不mint能力；原query wrapper保留其purpose/proof/顶层只读RR/错误语义，observer有独立包装。不得把 query 专用 public observationMetadata 接收status proof，也不得在status RR里调用该顶层query方法。Binding和七字段DTO只是数据，不是能力。不要复制大段相同SQL。

在TraceStore中或等价封闭infra位置设 StatusReadProof：**private constructor，mint仅在真实live M/frame与实际PG绑定验证后**。MaterialScopeStore消费口如需访问嵌套类型，可包内可见但构造仍私有。proof持有同一retained/frame、签名run-snapshot资源、真实numeric/public id、owner、原app root、acceptedSHA、initial floor与已预检材料集合；不能以public VA/currentOnly/Actor/Binding/DTO公开构造。禁止任意Runnable/BooleanSupplier“已核权”工厂、任选字符串purpose、写者签名泛化或跨线程/请求复用。上层结果只提供封闭终检能力，不输出raw proof或grant。

消费点：每次固定材料读前后、RR实际commit后、compose后、serialize后、取得实际stream后紧挨首次write，均核同retained/frame仍绑定、期限、用途及run/resource一致。刷新替换/关闭frame后旧capture失效。旧POST读取与新status读取在类型或固定分支上明确区分，原public VA reader恒拒绝、strict/writer不放宽。

## 4. 真实PG只读快照、全部scope先验与内容关系

入口最先拒绝任何ambient事务（包括readOnly RR），再验证signed status resource格式/path/目的。该M已携带真实候选资源，不必为了刷新先读snapshot或调用query顶层API。事务外 `AuthorityContext.requireInitialFor(actor,observerResource)` 刷新同M；不新exchange、不换更宽grant，入场requireBound。若确有事务外控制预读，必须无payload，且RR内重核；优先避免不必要预读。

建立顶层readOnly REPEATABLE_READ，在同一DataSource连接里加载窄控制、authority和三根manifest。owner/publicId/acceptedSHA与本次M的已验subject/resource核对；numeric id、原app root、appVersionId/No、initialSHA按PG冗余、原initial与历史manifest的内部关系核对，不要求原app root或initial等于新M的resource或initial。404不存在/不归属一致；归属但旧legacy/缺authority/材料或绑定损坏403，无回退。query wrapper行为必须保持。在线刷新/HTTP在事务外，事务内无网络/写锁/全局隔离改动/无界重试。

完整三根scopes沿现load(true)/scopes验证owner、kind/id/version、initial floor、原admission decision/version、row冗余、scope digest和父链；snapshot payloadSha必须等于acceptedSHA。历史provenance不等于新M decision/version，不能改写历史去迎合当前票。

沿现statusPublished枚举全部当前必需材料：三根KINDS、RUN_KINDS(state/budget/output/error)、每node definition/state/output/error、每event run-event；原status不输出attempt明细，不额外读attempt payload。保持requireComplete精确集合、每locator最高revision、父scope/floor/实际row绑定，nullable output/error缺scope仍拒绝。每份当前M需覆盖历史roles/entitlements，dataLabels保持义务，不替换历史initial floor或把label误当用户同名权限。

**预检所有必要scope后才第一份payload SELECT。** tool_call、controlled、非TASK/FULL/debug/eval先用窄控制存在性拒绝，不先读tool result/业务snapshot判断有无权限。预检完成后先通过新只读proof读取真实snapshot与versionRefs，沿固定MaterialScopeStore EXACT/normalize/digest原语保留PG JSONB大数和数值归一，不用普通double重新定义摘要。

读取后核原createBound已建立的关系：snapshot.appCode有效且匹配run.app_code/root code；versionNo正long且等于authority no；appVersionId（缺失则versionId）匹配run/authority id，两个同时存在必须一致；candidateSha256为64hex且等于原root.versionDigest；可选appVersionKey若有等于code@no；refs.appVer等于no；实际snapshot摘要等于scope payloadSha、acceptedSHA、签名M版本。snapshotSHA与candidateSHA不同概念，不能要求彼此相等，也不重新计算当前app发布候选或查询发布指针。

随后用已授权、摘要正确的snapshot复用RunService.requirePublishedFlow纯校验，保持已支持FLOW/有限START-END-CODE-CONDITION及无外部/controlled来源；不prepareSnapshot、RuntimeClients解析依赖或创建执行节点。合法fixture需完整真实可接受FLOW，不能只存config的最小假snapshot。再于同一RR读取剩余预检材料、核payload摘要并组装原status，不允许每份材料各开事务混合revision。

检测位置须如实区分：scope/metadata/签名SHA错误payload=0；真实snapshot内容损坏或合法resource_digest单点替换与snapshot.candidate不等，读取后拒绝，**payload>0/业务wire=0**。可信PG/immutable是前提；不声称能发现管理员协调改写全部snapshot/manifest/authority再获得匹配新签名许可，不新增伪receipt/迁移/历史签名来迁就测试。

## 5. 原完整响应与同步首次写出

保持原ApiResponse及完整status字段：runId/appCode/status/eventsWatermark/inputs/output/fileRefs/error/versionRefs/nodes/events/paused，原node字段/别名、event seq/type/payload/created_at、时间、大精度数值、SQL NULL/JSON null语义。不得填空nodes/events/output替代真实材料；pureFLOW的fileRefs空须由真实无tool记录成立。原POST继续原statusPublished目的，新observeStatus复用composeStatus而不复制整段约110行读取/组装算法。

有限IAM admin GET采用同步同request输出，legacy沿原返回逻辑。必须完成真实RR commit → composition/终检 → 完整原ApiResponse JSON序列化 → 本地终检 → **获取此次实际response的实际ServletOutputStream → 同frame/proof/期限/目的再次终检 → 紧挨首次write(bytes)**。header/长度等步骤放在最终终检前；取得stream后不做潜在长耗时转换/网络。不提前flush/sendError/写成功envelope；IAM分支完成响应后不再由converter二次输出。不要只在getOutputStream前检查就宣称首次write受控。实际方法签名/反射/handler coverage相应更新，不加global advice/通用输出框架或SSE lease/watchdog。

已存在且适用于本路径的响应上限沿原常量并测边界；4096 metadata上限不适用于完整status。不截断合法结果伪装成功。若发现本路径没有可用上限而新增缓冲会造成实际无界风险，实施前向root报告具体原路径/值与最小候选，不自行发明会改变契约的限制。

首次业务write前失效返回既有错误（401/403/503按真实层），不能部分status或空成功。HTTP错误envelope允许存在，业务wire=0不等于socket无字节。若IO已部分提交，停止/关闭并记录实际部分，不能补写成功JSON/自动重发GET/宣称已追回。一次阻塞write内部不是连续在线授权，不承诺瞬时撤权/恶意慢peer物理硬断；未再次在线查询的远端变化不能由本地终检发现。O1c另做gateway最后一跳，O2另做长流生命周期。

## 6. 锁定候选文件与实施前范围回报

默认修改：common RunObservationSelectors.java、对应Test、LoopbackAuthority.java；engine RunController.java、RunService.java、TraceStore.java、MaterialScopeStore.java、RunAuthorityStore.java。默认新增独占 `RunObservationStatusHttpPostgresTest.java`。不预改ServiceAuthorization/catalog/O1a API；若实际handler source locator需动，先报root精确行/原因。生产源较大只新增必要窄方法/共享既有核心，不顺手重构整文件。

编辑前列最终路径/API、StatusReadProof类/constructor/mint/消费可见性、共享控制helper、新旧reader如何共用算法而不混purpose、全部SQL列/单RR、序列化与实际stream后终检方案、适用原上限、具名测试selectors。发现外部新依赖/迁移/客户端协议或大重构需要先报具体阻碍，不自行扩域；常规细节由root裁定，不问用户重复授权。

## 7. 定向真实HTTP/PG红绿验收

先关键行为失败测试后实现，编译/装配/脚本失败单列setup不能冒充行为红。真实专用PG及V200–217迁移、实际签名Loopback/JWKS、真实engine Spring HTTP/security/handler、实际RunService/TraceStore/MaterialScopeStore读取。真实生产受理创建完整FLOW根、节点/事件与scope，可复用已验domain fixture，不用Map/假DTO/裸伪造run。O1a Q真实HTTP→同Portal fresh M→真实engine GET；fixture扮gateway client不等于真正O1c两服务链。普通只读正例的请求观察期间run/node/attempt/event/tool新增、worker/provider/skill调用为0；第7.6项受控并发writer为明确例外，单独记录其预期写入及读取快照。

必要行为合并覆盖：

1. 新M读取完整真实status，旧执行grant过期/blocked仍可独立观察且持久grant/initial/origin/blocked不变；保留所有字段、节点/事件/nullable/数值/时间，不查当前发布指针。
2. Q/POST/local票不能变status；status不能写/推进/events/debug/v1，错caller/target/source/origin/workload在真实层拒绝；Actor/VA/currentOnly/DTO不能mint；readonly proof不能传writer。记录实际guard/handler/authority与SQL位置，不只最终403。
3. 同owner同app版本的两run互换M、跨tenant/owner、wrongSHA及Q→M后snapshot绑定不符在payload前拒绝；不存在/不归属404一致。
4. 完整scope先验：缺nullable/node/event、错误owner/parent/digest/revision/initial floor、当前收窄不覆盖历史材料、unsupported tool/controlled存在均payload=0；不先读snapshot再发现后面的event未授权。
5. 真实payload损坏、candidate/root/app/version/refs关系错、可parse但不受支持FLOW在读后拒绝，记录实际payload>0/业务wire0，保留immutable正常拒绝与隔离库临时破坏/恢复操作，不重写scope修复历史。
6. 同一RR：第一payload后另一连接合法writer提交新event/output/revision，返回只能来自一个快照，不拼新旧watermark/output/events；用真实PG写者事务，不mock查询返回。
7. ambient拒绝先于refresh；当前在线撤权403/不可用503/expiry按位置计metadata/payload。metadata后、payload后、RR真实commit后、compose后、序列化后、**实际getOutputStream延时返回后**推进clock/关闭或替换frame，业务首次write前失效写出零status；保留各窗口而不把读后拒绝说成payload0。不得缓存allow/重exchange/重试。
8. raw query/bare?/body/unsupported dispatcher/alias实际HTTP拒绝，bare?必须原样到接收端；实际guard/coverage安装验证。legacy仅有限构造/装配则据实写，未完整boot不宣称完整；受保护run不能走legacy，原POST主目的及query直接回归保持。

SQL监听覆盖所有实际执行语句与connection/isolation/readOnly/autoCommit，区分control/manifest/各payload；新路径不沿用O1a宽.*投影断言。正例真实payload>0与HTTP完整响应、负例真实wire字节都要观测，不能仅内存结果。fixture HttpClient/decoder/context显式close并记录实际自有PID/端口；不以零beans推断未观察的外部服务计数。

runner固定 `.tools/run-iam-module-tests.mjs`，root cwd/Java21/专用m2离线、合法逗号或# selector，禁止+。建议新类RunObservationStatusHttpPostgresTest；关键方法按上述行为具名。若提取RunAuthorityStore共享helper，O1a只选直接受影响的owner/损坏metadata/ambient/当前失效等具体方法回归，先核真实已存在名字；common只扩受影响selector/purpose测试，不重跑旧全套为凑数字。方法/命令/源码在执行前锁定，执行期间所有source冻结。

## 8. 证据、唯一写权与交还

作者独占source/对应target写权及 `.local/iam-o1b-readonly-status/`、`docs/evidence/iam-stage-4-o1b-readonly-status/`、报告 `docs/evidence/iam-stage-4-o1b-readonly-status-report.md`。root独占八执行索引/review/state/未来brief，不读作者半成品。每约60秒简短回传真实进展/阻碍；按固定实际开源源码范围窄读并写不变量/事务/窗口注释，不能声称读过未读源码。

每个实际命令前完整必要source SHA及仅变化overlay；继承已验578/target1948/前端dist15谱系，不复制/重扫旧全量证据、JAR、node_modules/target全树。记录真实事前scope及增补时间，失败/oracle/setup/诊断原件保留，不倒改清单；工具元数据错误后只继续未完成阶段，不重跑已绿测试或成功物理步骤。

重放使用实际ROOT cwd；patch check/apply明确限定自有replay前缀。混合EOL提前采用自有private evidence repo的info/attributes `* -text` 等精确方案，真实.git/config/.gitattributes不动。若首次应用已成功只字节不等，保留该事实，只补未完成字节一致性，不重做成功apply或直接覆盖伪作补丁。完整逻辑names/bytes/SHA逐项相等，实际物理只存delta即可。

完成自审后冻结source/target/own全部写权，交精确source/target delta、frozen overlay/重放链、命令/cwd/exit/实际Running选XML与最后适用覆盖、真实资源收束、精确own inventory/bytes/SHA和限制。生产JAR不package，测试类产物不部署。由root有限审计+新独立reviewer验收；本切片不签收O1c/O2/RR1/RR2/完整D-E-F/17功能34任务30验收。
