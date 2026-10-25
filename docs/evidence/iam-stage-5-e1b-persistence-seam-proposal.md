# E1b 持久授权、三类初始材料与 local current 接缝提案

状态：只读准备，未实施、未测试。唯一输出为本文。日期：2026-10-20。

唯一工程根 `G:/207/项目/算法重组工具/代码/agent-platform`。未修改此前架构提案及其已保护SHA、原设计/四契约/执行索引。E1a当前为唯一common作者；本次未读取changing common四文件、target、凭据、数据库、网络或进程。common事实使用 `docs/evidence/iam-stage-4-d3a0-deadline-fix/frozen/admin-common` 与正式 `iam-stage-5-e1a-brief.md`，engine事实仅来自已授权稳定RunService/RunController/TraceStore/RunScheduleRepository及迁移源码。下面E1a API均是正式brief约定，仍须以最终双审冻结结果接收，不能假称本次已读到实现。

## 1. 本组件的实际完成边界

建议E1b只完成三个生产接缝：真实PG新run的授权side row与三类初始材料同事务；metadata-only恢复与受限run.advance的真实resume/current验签；旧row/材料缺失/范围收窄/503/过期的拒绝与持久阻断。组件测试可以实际调用这些生产store/application类，并用真实ServiceAuthorization建立受理frame。按root精化，初始材料version取真实canonical snapshot SHA，唯一键包含tenant；不是常量revision或通用序列。

本段**不接线RunService.createInternal/driveRecovered，不运行真实FLOW，不开放生产IAM**。不修改GatewayController/AppService/FlowDriver/RunScheduler业务行为，不宣称status/requestId、节点/事件/output/error/checkpoint、child/model/stream已有保护。它们是随后联合切片的明确依赖。现run创建仍未调用此组件时，必须保持IAM启动闭锁；不能因store测试通过就在原无scope路径开门。

root已经选择唯一内嵌local tuple：`run.advance`、caller=engine、target=engine、mode=delegated、root.kind=app-version、source=DELEGATED、origin=PORTAL；仅resume出票。原HTTP目录、metadata用途检查、158原actual HTTP集合及D3新增真实metadata条目保持，不增加虚构HTTP行。

## 2. run→app-version：现有列、最小保存字段及可信来源

### 2.1 实际事实

V200的engine.run有app_code、app_version_id、tenant_id、principal_id、snapshot、inputs、version_refs，没有独立versionNo/candidateSHA列。V201–V215未补这两个列；V215仅增加dispatch字段。RunService.createInternal的真正INSERT位于原380–418附近：app_version_id取frozen.appVersionId数字，否则frozen.versionId数字；snapshot保存整个frozen；version_refs保存versionRefs(source,frozen,req)，其中appVer来自frozen.versionNo，flowDefSha来自source.definition，model/skill/prompt及evalReportId各有自己的来源。

真实app返回快照含appCode、versionNo、candidateSha256、数字appVersionId/versionId，以及appVersionKey=code@no。后续联合入口必须先完成真实app metadata/current pointer/immutable payload校验，再把该已核快照传给原run INSERT。E1b组件不能独立证明远端app的versionId确实存在，不能访问app数据库代替该HTTP链。

### 2.2 推荐：只加side row的accepted version元数据，不改run本表

`RunAuthorityStore.insertNew(long runId, RetainedAuthorization admission)`作为内部步骤，**第一步**要求调用方真实事务及 `AuthorityContext.requireBound(admission)`，在任何run payload读取前执行。再读取本事务新建run的metadata，检查owner、PENDING/TASK、app_version_id>0、无debug/eval/controlled来源、无既存authority；然后才读取这条**本事务新写入且调用方拥有**的snapshot中的严格字段及三类初始材料。不得把同方法用于历史修复或requestId既存分支。公开入口采用2.3节窄创建方法，避免公开任意runId补权入口。

新run字段必须严格校验：

- run.app_code等于snapshot.appCode；snapshot.versionNo是正整数且在支持范围内，拒绝浮点/字符串/截断转换。
- snapshot.appVersionId/versionId若两者存在须为同一正整数，并等于run.app_version_id。appVersionKey若存在须等于run.app_code+"@"+versionNo。
- snapshot.candidateSha256为64位小写hex，等于main signed resource.versionDigest；资源kind必须app-version，id必须由实际run.app_code和该严格versionNo重建。
- snapshot/app metadata与授权root三字段全部一致；version_refs.appVer必须等于严格versionNo。versionRefs的其它值是材料内容，不把它们或flowDefSha冒充app candidate SHA。
- admission当前call必须target=engine、action=`proxy.engine.post.RunController.run`、caller=gateway、source/origin=PORTAL；缺proof、query Grant、读app-version Grant、DELEGATED/workload不进入首次持久路径。

将accepted `app_version_id`与`app_version_no`作为新增run_authority元数据，与root_kind/root_id/root_sha256一起保存。未来 `load(runId)`只join：

```
run: id, public_id, tenant_id, principal_id, app_code, app_version_id
run_authority: accepted version id/no, root三字段, owner, grant, initial,
               origin, immutable admission审计字段, current阻断审计字段
```

用run.app_version_id=side.app_version_id、run.app_code+"@"+side.app_version_no=side.root_id、owner全等来复核实际关联。后续load不读取snapshot/version_refs里的旧版本字段；root_sha256就是初次同事务严格核对并保存的accepted candidate SHA。三字段不是凭空从当前Actor推导的，也不是把service JWT与自己重复比较。

此方案无需新增AcceptedVersion参数/公共DTO、无需修改engine.run列、无需receipt。一个caller可自行构造的AcceptedVersion record不会比实际已存新run提供更多来源证明。若后续run INSERT明确增加独立accepted列以方便通用查询，可以单独评估，但不是本E1b必需项。

**安全边界明确：** persisted新snapshot中的版本字段来源真实性仍依赖未来可信受理writer的真实app调用；本组件验证“已核main proof与实际新run内容一致”，不伪称“单靠engine SQL证明app域存在”。组件测试可构造真实engine新run及匹配的签名主Grant验证这项局部不变量，必须标记为store组件测试。

### 2.3 “新建”与旧缺row：建议最小接口细调

单看xmin不能证明INSERT：旧行在当前事务UPDATE也会获得新xmin。公开 `insertNew(runId,retained)` 在没有额外真实创建事实时，无法完全阻止合法proof被误传给旧缺row。不能以xmin、created_at、PENDING或“没有scope”代替新建事实。

建议把公开入口细调为本类的窄 `createPublished(...)`，由它自己执行**实际engine.run INSERT RETURNING id**，然后仅将该局部id传给包内/私有insertNew与三scope写入；不接收调用者runId，不接受update/upsert，不生成receipt/capability/身份服务。候选形状：

```java
long createPublished(
    RetainedAuthorization admission,
    JsonNode acceptedSnapshot, JsonNode inputs, JsonNode versionRefs,
    JsonNode budget, String mode, String requestId,
    Set<String> trustedAdditionalDataLabels);
```

该方法强制已有外层事务，首先requireBound；owner从retained取，app_code与数字versionId从已严格校验的acceptedSnapshot取，root与proof全等；public_id由本方法生成，trigger_type固定TASK、debug_snapshot/eval_candidate为NULL，其余V200必填列沿现RunService的真实published INSERT表达。guard_ctx只能写当前Actor审计投影，不成为权限来源。参数中的acceptedSnapshot仍是可信受理writer已经核过app来源的数据，不因为传入本方法就自动变成远端真实性证明。

方法内完成newrun→authority→三scope→本地proof终检，返回真实runId；调用方随后可在同一事务写nodes。requestId冲突遵守原PG唯一约束，绝不把ON CONFLICT结果当新run来补权。已存在authority拒绝；旧run缺grant在load/current/read均拒绝且没有可传入其runId的公开补权方法。包内写原语的可信边界不声称防同进程任意恶意SQL。

这会把**现有published INSERT的有限片段**提取到该生产方法；E1b只实现未接线的组件，后续联合段在原RunService.persist/atomic的新建published分支调用它，移除该分支重复INSERT，并保留原requestId锁/重查、nodes、admission与提交后调度。DEBUG/candidate/CHAT不走此入口。本次不修改RunService、不宣称调用图已迁移，也不新增第二套RunService/TraceStore。相比增加“新建证明”类型，这个窄真实创建边界更直接；接口细调仍需正式brief明确接受后实现。

## 3. insertNew的最小完整行为与审计

建议由createPublished内的insertNew步骤统筹**authority+全部三scope**，不把三次public写操作留给容易漏调用的application代码：

1. 要求同一个DataSource/实际事务，先requireBound；检查主受理call及本地proof时限。
2. 按2节核新run真实内容，严格解析retained完整initial及稳定origin；复用既有initial规范hash语义，核等于签名operation.ceilingSha256。
3. 从三份实际新payload计算材料scope：snapshot、inputs是初始根材料；versionRefs至少以snapshot scope为父。versionRefs含请求派生evalReportId等信息时，不声称仅由snapshot推导；本段权限要求保守取同次完整initial作floor，后续纯TASK入口应关闭不适用的额外请求语义。
4. 当前必须覆盖三scope的requiredRoles/requiredEntitlements。dataLabels取 **initial labels ∪ 实际current labels ∪ 可信parents labels ∪ 可信config/data-source附加labels**，不能只保存初始旧labels；相关来源未获可信判定时该材料路径不能默认空labels。initial A/B、current A、初始requirements A/B在提交前拒绝，不能先创建再等status失败。合法首次正例为A/B覆盖A/B，随后收窄A才验证历史拒绝。
5. INSERT run_authority保存完整initial、accepted元数据、原source/origin、主operation/call审计；写三scope均加入同事务。最后requireBound同一retained及时间再次通过再返回。任何失败向上传播，使外层run/node/authority/scope一起回滚，不catch后提交。

新增run_authority字段建议（具体迁移名/号待开工核Flyway，不占号）：

| 字段 | 用途 |
| --- | --- |
| run_id PK/FK engine.run(id) | 一run一授权；非upsert |
| tenant_id/principal_id varchar(64) NOT NULL | 与V200及run owner全等 |
| app_version_id bigint、app_version_no integer NOT NULL且>0 | accepted数字ID与规范版本号；metadata-only关联 |
| root_kind、root_id、root_sha256 NOT NULL | kind有限app-version；id=实际code@no；SHA规范 |
| grant_ref、grant_expires_at NOT NULL | 受限持久delegation，不是JWT或refresh token |
| initial_ceiling JSONB、initial_ceiling_sha256 char(64) | 全值及按现规范算法复算校验；不能只有hash |
| accepted_snapshot_sha256 char(64) | 本域三材料bundle真实版本，与实际canonical snapshot内容hash一致；不同于app candidate SHA |
| source_kind、origin_authentication_id | 首PORTAL原来源与稳定认证锚点 |
| admission_decision_id、admission_authorization_version、admission_operation_id | 首scope的实际签名来源审计；永久保留，不被last_*覆盖 |
| last_decision_id、last_checked_at、blocked_reason | Task5已有用途；不当作签名proof |
| retry_not_before timestamptz、check_version bigint NOT NULL DEFAULT 0 | 503有限退避、并发结果CAS；不是run枚举状态 |

除last_*、blocked_reason、retry_not_before、check_version外的初始绑定字段仅允许初始INSERT。仓储没有UPDATE初始值方法；可在该side table加一个有限BEFORE UPDATE trigger拒绝初始字段变化，保留显式重新授权将来单独设计的迁移/路径。不能拿当前A UPDATE initialAB。日志不打印grant_ref、完整token/原认证标识。

## 4. 三种初始材料：固定schema、真实snapshot版本、精确内容摘要

### 4.1 不在这个组件建立17种通用材料

支持且只支持：

| producer/kind/id | 唯一业务payload列 | version |
| --- | --- | --- |
| engine / run-snapshot / run.public_id | engine.run.snapshot | 实际canonical snapshot SHA |
| engine / run-inputs / run.public_id | engine.run.inputs | 同accepted snapshot SHA |
| engine / run-version-refs / run.public_id | engine.run.version_refs | 同accepted snapshot SHA |

三列都是本次受理的不可变JSON对象；SQL NULL不合法，JSON根null也拒绝，嵌套null保留。遵循原批准计划§2.5，version绑定实际内容版本：这里直接使用4.2规范流程得到的本域canonical snapshot SHA，三材料属于同一次accepted snapshot bundle；inputs/versionRefs各自payloadSha仍来自各自内容，不能复用snapshot SHA冒充其payload摘要。相同snapshot、不同run输入靠tenant/kind/id及各自payloadSHA区分，不需要序列或假定常量revision。

唯一键为 **`(tenant_id,producer,kind,material_id,material_version)`**。无PG sequence、material nextRevision API、mutable指针或max(revision)查询；重复写即失败，即使digest相同也不能覆盖source审计。未知kind、producer非engine、version不是该run已保存accepted_snapshot_sha256一律拒绝。以后新增attempt/round/event等材料时按其真实内容版本另评，当前不提前实现。

`engine.material_scope`最小列：run_id FK→run_authority(run_id)、tenant_id、producer/kind/material_id/material_version、payload_sha256、scope_sha256、scope JSONB。tenant_id与scope.owner.tenantId/run owner全等，其他列与scope.material字段一致；scope_sha256可按tenant约束唯一。写入不UPDATE/DELETE（包括历史scope），无cascade删除历史授权材料。采用一个有限表trigger禁止scope行UPDATE/DELETE能把不可变性变成PG约束；保留未来受控保留期维护的独立任务，不在本组件开放删除接口。

如需防原三业务列被后续旧代码误改，可对**已有run_authority的run**添加有限BEFORE UPDATE检查：app_code/app_version_id/tenant_id/principal_id/snapshot/inputs/version_refs不得改变，NULL安全比较；其它原status/lease/budget/output仍按原业务。这是维护本组件已接管材料的不可变性，不对所有历史dev/test run盲目施加新约束。该check不代替未来所有业务写点的材料授权。

### 4.2 payload digest不能用身份摘要；JSON数值不能悄悄丢精度

沿实际MaterialRef五字段与MaterialScopeV1十三字段，MaterialAuthorization.scopeDigest继续用于scope本身。payloadSha256是具体内容规范字节的SHA256；app candidate SHA、runId hash、scope hash、ceiling hash四者均不能代替它。

建议私有、有限JSON材料表示流程留MaterialScopeStore，不改全局Json配置：

1. 对**已在本事务持久化的真实PG JSONB列**读取其text，使用本地严格精确数字解析：整数BigInteger，十进制BigDecimal，不经double；拒绝非有限值/重复字段/非JSON。全局Json.read目前未开启精确小数映射，不能直接假定其对高精度数值无损。
2. 固定object键排序、数组保序、字符串/boolean/null原值。数值使用精确值规范化（例如BigDecimal.stripTrailingZeros，零统一为0，整值转精确BigInteger）；不以float近似判断相等。
3. 用既有Json.canonical的对象排序/UTF-8表示产生实际材料规范字节，验证精确数字node在此转换中未丢精度；其实际代码normalize是递归排序键、保留数组/leaf，不提供跨库数值精度证明。实现必须用PG往返测试确认BigInteger/DecimalNode路径。若某表示不能无损支持，拒绝，不能偷偷舍入。不要新增另一套可选序列化策略注册机制。
4. Hash这些规范UTF-8字节；read重复同一过程后精确比较payloadSha256。范围校验在**任何已提交历史payload SELECT之前**，摘要校验在读取后；两项顺序不能颠倒。

首writer在PG中取实际持久值建立摘要，避免把pre-PG输入的数字表示与PG返回值混算。后续真实RunService接线若要求展示与原请求精确数值完全一致，还须验证其更早JsonNode解析未丢精度；本组件不能恢复上游已经丢掉的位数。

这里的“字节”明确是本域engine JSON材料规范字节，不声称等于浏览器HTTP body原字节、app HTTP envelope原字节或provider stream原字节；这些另有原字节/manifest要求。本段不存bytea副本、不新建payload镜像。nested null与{}、数组顺序、两个超double精度且不同的小数必须产生可辨摘要；1/1.0/1e0按上述精确值规则可归一，规则必须固定而非读写各自选择。

### 4.3 `write(scope)`的真实权限来源不能含糊

一参数 `write(MaterialScopeV1)` 自己没有RetainedAuthorization，也不能从sourceDecisionId字符串重建验签证明。E1a没有公开“获取当前retained且不刷新”的API；在写事务里调用requireInitialFor会联网，不能为了凑签名这样做。

最小推荐：

- `RunAuthorityStore.createPublished(...)`是公开的真实创建/授权入口，内部insertNew持有显式retained，前后requireBound并统一构造/验证三scope；不暴露任意runId补权。
- `MaterialScopeStore.write(MaterialScopeV1)`保留该形状，但**仅infra包可见**，作为受信repository写入原语，由同包RunAuthorityStore调用，不暴露成HTTP或application任意材料提交入口。
- write要求实际事务，严格kind/version/tenant/locator/owner、scopeDigest、三材料实际列摘要、initial hash及完整权限floor与新run_authority一致；sourceDecisionId/sourceAuthorizationVersion必须等于该行immutable admission审计字段；versionRefs父scope必须为该run本次snapshot的真实scopeSha。labels必须包含initial/current/可信附加/parent并集，不等同于initial旧集合。不是接受任意scope对象就写。
- write不声明“独立完成proof验签”；证明来自外层insertNew的实际retained与frame检查，数据库审计字段只防错配。任何后续writer若要用新DELEGATED current写别类材料，须新增明确证明传递路径；不能复用这条initial primitive套上旧admission decision。

这与原Task5的write/read职责一致，具体可见性收紧以避免一个无proof参数的公开持久入口被误用；若正式brief坚持public write且要求其独立证明当前授权，则需显式增加retained参数或受控调用方法，这是签名事实，不能用新ThreadLocal/writer框架掩盖。本文推荐窄包内原语。

`trustedAdditionalDataLabels`只是可信创建调用点明确传入的**附加义务**，没有替代initial/current/parent的权限；三者由createPublished自身取并集。应用config和data-source标签的具体业务字段须沿已批准schema解析，由真实受理writer核来源后提供；不凭本文猜一个字段名，也不能默认所有来源无标签。E1b组件可以证明非空附加集合不会丢失；真实入口是否完整收集config/data-source标签是下一接线的具名测试。当前仅自含JSON的组件正例可明确无外部data-source/历史父源；带未处理数据源的生产路径仍关闭。

### 4.4 `read(ref,current)`与metadata-only读取

公开 `AuthorizedMaterial read(MaterialRef ref, VerifiedAuthority current)`：

1. 先严格限定三kind/producer/version及id格式；只读scope metadata与run/run_authority窄关联，校验owner、accepted root、grant_ref/expiry及current.operation根/ceiling hash，不读取snapshot/inputs/version_refs。
2. MaterialAuthorization.require(scope,current,服务私有可信Clock)通过；显式Clock只在store构造时由受信装配注入，业务read方法不接收调用者Clock。scope的labels完整保留；requiredEntitlements只接受现region/bizSystemCode/userId语义，不能把dataLabels当可裁剪权限。
3. 在同一PG快照内按固定switch读取对应列，精确内容digest吻合后返回AuthorizedMaterial。未知kind不得转成自由table/column/SQL。

最小同快照方式是MaterialScopeStore自己持有同manager的只读REPEATABLE_READ TransactionTemplate：顶层read开快照；加入现有事务时必须确认其隔离等级满足要求，不能在默认READ_COMMITTED里悄悄JOIN后声称同快照，也不REQUIRES_NEW绕过外层新建事务。组件内部insertNew直接读自己同事务新值，不走这个已提交历史read API。多材料恢复检查可只在一事务读取三scope元数据；完整status批量payload快照是后续联合任务。

VerifiedAuthority是现公开record，read不把“Java对象可构造”当密码学能力；它的生产调用前提是来自真实guard或RunAuthority.current且已验。read严格核PG关联防合法VA跨run/grant误用，不另造签名验证cache/ThreadLocal。组件测试必须以实际签名产物调用，不用new VA自证安全。

## 5. RunAuthority.current：真实local resume、完整值关联与失败持久

保持原Task5签名 `VerifiedAuthority current(long runId, ServiceCall call)`；E1b只允许精确run.advance local call，其他action/default均拒绝，不能“先支持任意ServiceCall以后再收口”。

真实步骤：

1. `RunAuthorityStore.load(runId)`只加载2.2固定列与三scope元数据；缺row/缺完整initial/origin/任一scope拒绝。历史payload、guard_ctx不读；不调用Actor.current猜恢复主体。
2. 严格解析PG fullinitial，复算按现ceiling算法的hash，核owner、root、run数字versionId及versionNo关联、grant期限。call.resource必须等于持久root；不信call参数自己声明的run身份。
3. 永久403阻断拒绝普通current调用；503阻断仅当retry_not_before已到才允许一次显式current重试，普通poll不能随意清除阻断。
4. `ticket=engineClient.resume(storedDelegation,localCall)`；`mapped=engineClient.authorizeRetained(ticket.value(),localCall)`。真正HTTP authority验签，target/aud=engine、caller=engine。只为一个新动作resume一次、authorize一次；没有收到票以后从Actor再issue。
5. mapped.requireVerified()用其私有clientClock；核source=DELEGATED/origin=PORTAL、originAuthenticationId、subject/grant/grantExpiry、root、fullinitial规范全值、ceiling hash与PG全等。新operationId/issuedAt/expiresAt来自本次签名，不要求等于原PORTAL operation，不给旧PORTAL延期。
6. 对三份初始scope逐MaterialAuthorization.require；initialAB而currentA时要求AB的材料拒绝。这里无需读payload就可确定不可推进；成功返回VA也不宣称已经读过或验证过未来全部历史材料。
7. 进短事务重核immutable关联及check_version，mapped.requireVerified再次通过，CAS写last_decision_id/last_checked_at并更新check_version；只清掉本次已到期重核的503临时阻断，不无条件清其它403。零行CAS视作过期并发结果，拒绝返回可推进结果，不覆盖另一检查/重新授权状态。网络在事务外。
8. 返回mapped.current()，不得把retained放ThreadLocal或把fullinitial丢掉后用current.Actor重建初始值。返回VA足以给后续同动作材料read/显式driver；E1b尚不接driver。

`RunAuthorityStore.Row`可为本文件public嵌套record供application读，没有对外HTTP；内容是PG metadata而非proof。fullinitial规范hash应严格复用现owner/roles/attributes语义；若AuthorityProtocol.ceiling仍包私有，可在store私有小方法中用明确TreeSet/TreeMap规范对象调用现Json.canonical/Digest，并由与真实operation.ceilingSha256对照测试约束；不为此公开整个内部协议类。

失败记录：捕获明确403/expiry，短事务CAS记blocked_reason及checked_at，保留所有原payload/initial/scope和原业务状态；503记AUTHORITY_UNAVAILABLE及有限retry_not_before（使用构造时固定退避，测试可注入小Duration，不从请求控制）。不存完整异常信息/token。未知编程异常不能伪装授权503，应失败并保留原状态。

side表的retry_not_before避免本组件修改原dispatch_next_at或被RunScheduler.release覆写退避。未来真实scheduler的claim必须同时检查这些side字段；**本组件仅写出了退避事实，未修改scheduler就不能声称自动调度已经退避或旧run已经排除**。同样本组件不需要伪造dispatch lease正例；未来动作提交前还要核owned generation/lease。

## 6. local tuple与original main Grant锚点

生产common最少修改：ServiceRoutePolicy内一个固定tuple匹配方法；HttpCurrentAuthorityClient在resume/ticket及exchange/client/forward入口限制；TrustedJwtDecoder.requireServiceScope接受唯一engine本机local票。HTTP routes()/require与ServiceAuthorization.route不接受local；D3MetadataSelectors用途检查保持。生产startup仍关闭，不改SecurityConfiguration门禁。

fixture沿E1a最终LoopbackAuthority继续扩展，保留实际Basic服务身份、签名、nonce/request digest和当前决策，不复制authority：

- 在现有限Grant record新增一个 `ServiceCall originalCall` 分量（E1a已有origin分量继续）；成功verifySubject后的最初exchange存入已验caller=gateway的call。
- local resume仅在originalCall.target=engine、action=`proxy.engine.post.RunController.run`、root=本Grant app-version、origin=PORTAL时允许；root相同但originalCall是app.version.read/app.published.read仍拒绝。
- originalCall在forward/resume保留，approver投影也原样携带；不从resume请求重填，不保存浏览器token作恢复凭据。
- 限定只有authenticated engine可resume并authorize本机票；client/workload、exchange或forward到run.advance拒绝。local票发往实际RunController任一HTTP方法仍被普通guard拒绝。

该fixture锚点对应实际authority未来必须支持的持久Grant策略；不声称由common client自己验证一个尚未在signed profile出现的originalCall字段。engine另以insertNew的真实main frame保存自己的run关联，两层各司其职。

## 7. 最小生产文件、API与迁移范围

E1a作者归还source/target并双审后才正式分配以下范围；本提案不修改文件。

| 文件 | 候选API/职责 |
| --- | --- |
| 新 `engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunAuthorityStore.java` | public createPublished(...)执行真实新INSERT；内部insertNew(long,Retained)；public Row load(long)；供RunAuthority的受限CAS记录成功/失败方法，具体Row+expected check_version参数，不接任意SQL |
| 新 `engine-runtime/src/main/java/cn/agentplatform/engine/infra/MaterialScopeStore.java` | public read(ref,VA)；包内write(scope)，私有三kind定位/JSON规范化/scope构造辅助；不新增材料框架 |
| 新 `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunAuthority.java` | public current(long,ServiceCall)；实际resume+retained比对+scope覆盖+CAS；无drive替代品 |
| common `authority/ServiceRoutePolicy.java`、`HttpCurrentAuthorityClient.java`、`TrustedJwtDecoder.java` | root已选唯一local tuple；待E1a最终Http client冻结后最窄接入 |
| engine schema下一连续迁移 | run_authority、三种initial material_scope、唯一/外键/有限不可变保护；号及文件名待源与实际Flyway核验，不占V216 |

构造注入JdbcTemplate/PlatformTransactionManager/CurrentAuthorityClient/可信Clock；组件测试显式构造即可。不为E1b新增生产安全配置/YML、不启动RunService @PostConstruct、不为缺bean改全服务启动。以后接线时复用正式配置统一Clock与client；若本组件先加Spring stereotype导致已有扫描启动缺依赖，应采用显式后续装配，不能为绿色默认注入假authority。

不改engine.run本表列（除可选有限update保护trigger针对已有authority的不可变字段），不改TraceStore/RunService/RunScheduler/FlowDriver源码。source层仅实际新增stores/application与三common局部能力；完整真实受理SQL调用位置及调度行为列作后续接线，不把组件测试的手动SQL称为原控制器已执行。

## 8. 真实组件PG测试方案，计数与结论分开

本节全部未运行。建议测试实际类为RunAuthorityPersistencePostgresTest、InitialMaterialScopePostgresTest、RunAuthorityLocalCurrentPostgresTest，以及common LocalRunAdvanceAuthorityTest（最终以作者实际命名为准）。真实独立授权PG、原迁移+新迁移；只有限loopback authority，业务域之间不互引JAR，无mock HTTP target。

### 8.1 建立真实受理frame的最小fixture

无需新增假业务controller来返回“成功run”。保存一个真正portal source，fixture gateway client对真实engine run action和具体app-version root exchange；用engine TrustedJwtDecoder解真实signed JWT，建立标准JwtAuthenticationToken及ServletRequestAttributes。

使用Spring mock servlet请求设施表达 `POST /admin/apps/{code}/runs`，设置真实matching template；HandlerMethod取生产RunController.run(String,JsonNode)的反射Method（可绑定一个不执行的生产controller实例）。调用**实际ServiceAuthorization.preHandle**，由真正engine HttpCurrentAuthorityClient.authorizeRetained建立E1a frame；随后实际AuthorityContext.requireInitialFor取得retained。finally调用receiver.afterCompletion并清理Request/SecurityContext，不能reflect调用bindRetained或new VA伪造proof。

这证明“真实接收守卫+签名frame→生产store”，**不证明MVC路由、filter chain或RunController.run方法体执行**。组件报告分别标示这些覆盖边界；普通HTTP全部映射/生产入口完整coverage仍属联合测试。如果选择actual HTTP测试，必须走生产真实controller实现和接收链，不能增加fake handler仅为mint frame；对本E1b来说上面的guard组件方式更小且诚实。

frame建立后，在真实TransactionTemplate中调用生产createPublished，以匹配真实形状的snapshot/inputs/version_refs等字段触发其真实新run INSERT与内部insertNew；可随后插一个真实node用于回滚断言，但不启动scheduler。旧run负例才使用测试SQL建立缺授权历史行，并证明公开create接口没有绑定旧runId能力。测试证明这笔生产创建组件事务，不把它称为原RunService控制器流程已接线。

### 8.2 必测矩阵

| 场景 | 实际断言 |
| --- | --- |
| 正常AB/AB首次受理 | PG run/authority/三scope同事务提交；fullinitial与origin/root/数字versionId正确；三种read按真实current返回实际payload |
| current已A、首次requirementsAB | 受理屏障拒绝，run/authority/三scope/node全回滚；不得以创建成功+响应403计绿 |
| AB成功受理后收窄A | PG initialAB及历史scopeAB不变；local current拒绝并写403阻断；read在payload SELECT之前拒绝 |
| 行关联错 | app_code/owner/versionId/versionNo/SHA/appVersionKey任一错，JSON类型错/数字越界，insertNew拒绝；不能用主票自己字段互比蒙混 |
| 缺proof/旧bind/currentOnly/过期/跨线程/已替换retained | insertNew第一步拒绝，受保护新payload读取0；不偷偷走currentActor |
| 真回滚 | run插入后、authority后、第二/第三scope中、node后故障；同一PG事务所有新增均0；immutable side row未独立提交 |
| 旧run缺authority/缺一个scope | load/current/read拒绝；没有补权写入，没有读取旧snapshot/inputs/version_refs；不把旧guard_ctx作为来源 |
| scope错审计/tenant/owner/digest/parent/unknownkind/非accepted snapshot版本/重复写 | 包内write有限校验拒绝，scope历史未更新；拒绝kind注入自由SQL |
| initial/current/config/data-source/parent标签不同 | 写入并集全部保留；current新增标签也不可消失；读取不是把labels当普通权限集合，后续egress义务仍完整 |
| 精确数字/NULL/PG往返 | nested null≠{}，数组顺序敏感；大整数/高精度近邻小数不碰撞；指数/尾零规则一致；top-level null/SQLNULL按schema拒绝 |
| metadata-first同快照 | 在scope校验与payload读之间并发尝试修改，事务快照/不可变保护使旧scope不能读新payload；bad scope时payload SELECT0 |
| 正常local新DELEGATED | engine服务身份resume+authorizeRetained实际签名；source=DELEGATED/origin=PORTAL/fullinitial/origin/root与PG全等；原portal到期后grant尚有效仍可current；不声称flow执行 |
| originalCall错误 | 同app-version根但原Grant来自app读action、metadata Grant、debug/eval Grant拒绝local；gateway/skill/client/workload/forward到local拒绝 |
| local票撞真实HTTP guard | 真实RunController Method/route下preHandle拒绝，不能因decoder认可local就授HTTP能力 |
| authority503/expiry/撤grant | 保留payload/initial/scope，CAS记录对应阻断/有限retry_not_before；到期前不重试，受控到期后成功只清自己的临时阻断；无外部业务发送 |
| 并发结果/授权行变化 | 两current交错，stale check_version更新0，不覆盖新阻断/新关联，不返回过期成功证据；immutable变更按保护拒绝 |

为了断言零payload读取，可用真实JdbcTemplate/DataSource查询观察器对精确三列SELECT计数，不能用Map/H2/假repository替代PG；成功对照必须真的读到内容。authority调用计数证明resume一次、authorize一次，不为fullinitial额外在线查询。SQL fixture/签名fixture/组件JUnit数量分别计账，不把内部assert膨胀为完整业务用例。

## 9. 必须保留的后续真实依赖

- 真gateway metadata→同source main→AppService发布指针/immutable payload→RunController/RunService接线尚未做；该路径才提供本提案新snapshot版本字段的真实业务来源。
- createPublished必须放在原persist atomic的真正新建published分支，替换该分支原INSERT，内部insertNew不接受历史ID；所有历史requestId返回先metadata/scope。真实status包含nodes/events/output/error/fileRefs等，本三材料组件不满足它。
- scheduler的claim/start事件、worker每个新动作current、leased generation提交、403/503候选排除、响应scope与后续材料写点仍未迁移；E1b退避row不能冒称scheduler自动生效。
- 完整Task5的parent并集、node/attempt/checkpoint/output/error/events、CHAT reservation、skill/model/facts/approver/附件、RR1/RR2真实流全部继续；不通过临时删除字段/空响应/假FLOW绕过。
- 原PORTAL期限不受local resume影响。组件只能验证签名operation及原deadline不变；真实observer/provider双流仍须后续实际HTTP验证。

本报告给出可单独实施与审查的持久接缝，保留明确的可信writer边界。所有生产IAM入口在真实调用图与完整材料链签收前保持关闭；当前只有提案文件写入，没有任何实现或验收结果。
