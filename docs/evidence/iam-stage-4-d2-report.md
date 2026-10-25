# G6 Task4 D2 实施报告（待独立审查）

D2独立policy resolver、PG revision、D1审查X4混合信任域接线已实现并冻结。范围仅本新工程；没有部署、启动主服务、提交commit或外部发布。原四契约/v1.2、Java21、Boot3.5.0、Security6.5.0、Nimbus9.37.3未变。**这不是Task4完整R3签收，也不是生产IAM实现。** T01仍唯一完成；IAM_TRUST_CHAIN_UNAVAILABLE / BLOCKED_IAM_SCOPE_ACCEPTANCE及真实IAM/集群签收边界保持。

## 实际接口、信任域与资源

- `IntegrationPolicyResolverController.resolve(HttpServletRequest)`固定`POST /internal/iam/integration-policy/resolve`，返回原约定16字段PolicyResolveResult。schemaVersion=1，输入9字段严格类型、缺失、未知字段、长度及正ID校验；expectedPolicyVersion字段必须存在，首次可为null。tenant/app/biz/credential参数化限定；principal按共用规则重新计算，不匹配403；非本范围404；旧版本409。
- `PolicyRequestVerifier`只复用既有严格pinned RS256/JSON验证；固定typ/use/issuer/sub/caller/target/aud/action、30秒TTL、零skew、jti、原字节SHA256和nonce。1–2把独立固定RSA公共key；私钥、缺kid、重复kid、小RSA、未知kid拒绝。错误本地请求401，无远端503伪装。Proof私构造，绑定verifier实例、真实request引用、过期时间与不可变原body；仅返回body副本，controller只解析该已验原body，不再次读取另一个可变payload。复制到别的request/verifier、过期、假attribute不能通过。
- `ServiceAuthorization`保留原构造，新增6参精确policy Method+verifier装配。全局MappedInterceptor仍全路径无exclude；只有该Method、实际mapping模板、固定POST/path及本request专用Proof共同满足才交独立POLICY_MACHINE域；普通service路径仍完整原守卫。app真实测试context同时安装实际IntegrationController/Service、resolver、两条SecurityFilterChain、MappedInterceptor、完整runtime coverage。
- `agent.policy-resolver.enabled=false`默认不注册controller/chain；启用时从独立`issuer`和`public-jwks`配置固定公钥，缺/私钥/错误配置启动失败。没有新建生产IAM私钥。`PolicyResolverSecurity.handler()/ingress()`供D3精确接入；D3还需在生产实际接收器显式启用并装配。当前全工程IAM启动闭锁未解除。
- Resolver只做一次PG SELECT snapshot，包含status/revision/secret_ref/claim_limits/origins；不调用Actor.current、SecretVault、旧凭据HTTP接口或authority。secret_ref仅参与旧兼容canonical digest，响应不返回secretRef/密文/key/principalIds全名单/业务payload。平台角色沿用旧剔除规则，dataLabels独立返回；bizSystemCode/userId为重新绑定的属性。
- `IntegrationClaims`仅共享principal、旧canonical digest、平台角色判定。gateway IntegrationPolicy直接委托；短principal、长`ext_` hash、旧digest及ApiKey路径行为不变；没有业务服务JAR依赖。新增common测试jar固定maven-jar-plugin3.4.2，app仅test范围依赖。
- V104实施前最大V103。专用新库`127.0.0.1:25432/agent_platform_iam_policy_v12`首次检查为不存在并创建，实际PG16.15迁移100–104；从未向main运行库写入。所有凭据只由既有受控launcher注入，未输出配置/密码；JWT/master/RSA与vault为独立临时测试材料。
- issue原事务先锁app.application的当前发布指针，仅联查app.app_version存储的candidate_sha256，不先读draft/config/definition/snapshot。资源=`app-version`, `code@versionNo`, storedPublishedSHA；未发布保持409。policy/revoke锁真实credential metadata，资源=`integration-credential`, id, SHA256(canonical({credentialId,policyVersion:id:revision,policyDigest}))，核当前资源后写入及revision+1、audit同事务。初始revision=1，无独立reissue/key-replacement入口因此没有新增API。最终资源refresh返回的Actor再次requireRole(ADMIN)，防入口ADMIN在最终决定被撤销后继续写入。

## 有限authority fixture与旧认证锚点

只在common测试源码公开有限LoopbackAuthority入口；原默认fixture行为保留，150项完整common回归含原145项。D2明确externalPolicy模式固定连接真实app随机端口HTTP，policy值仅来自真实PG resolver；初次exchange一次查询建立Grant的初始ceiling/version，后续resume/authorize从Grant取expectedVersion，不能从请求body覆盖。policy请求签名域独立于普通authority key。

相同policy提交保持digest但revision+1时，fixture将已认证原source token SHA绑定既有Grant。重复原token exchange仍取旧锚点并拒绝，不能重新读取null版本获得新grant；旧resume/authorize也拒绝，失败不创建grant。只有明确新jti/token认证才建立新grant。真实IAM必须持久管理“原认证→初始版本/ceiling”锚点，这是签收条件，不能把本测试Map当生产持久实现。

外部模式每决定最多一次resolver查询，测试计数断言为1；直接policy成功请求前后authority请求数0，错误域/DTO的PG查询数0，resolverToAuthority累计0。D2实际链是测试authority→真实app controller/security→真实PG→测试authority；其中engine call只是有限授权call，尚未经过实际gateway业务桥接/真实downstream，这部分属于D3后原R3。

## 行为红→绿与实际运行

所有命令cwd=`G:/207/项目/算法重组工具/代码/agent-platform`，通过`.tools/run-iam-module-tests.mjs`运行；其真实Maven命令为离线`mvn.cmd -o -B -ntp -pl <modules> -am test -Dmaven.repo.local=.tools/m2 -Djdk.net.unixdomain.tmpdir=V:/.local/tmp -Dtest=<tests> -Dsurefire.failIfNoSpecifiedTests=false`。每stage唯一原日志保留，未把编译/夹具异常计作行为红。

| 阶段与实际命令（node .tools/run-iam-module-tests.mjs 后的参数） | 原结果/意义 | 归档 |
|---|---|---|
| `iam-stage-4-d2-common-red admin-common PolicyRequestVerifierTest,IntegrationClaimsTest` | 4项，2 assertion fail、1 runtime error：缺主体规则/构造拒绝，fail-closed最小接缝拒绝合法proof；无编译异常 | `common-red/raw.log`、原XML、红时两个最小实现 |
| `iam-stage-4-d2-common-green admin-common PolicyRequestVerifierTest,IntegrationClaimsTest` | 4/0/0/0 | `logs/iam-stage-4-d2-common-green.log` |
| `iam-stage-4-d2-database app-management IntegrationPolicyDatabaseTest` | 1/0/0/0，真实专用PG首次创建/迁移 | logs原输出；该测试原XML仍在frozen-target/app-management/target/surefire-reports |
| `iam-stage-4-d2-http-behavior-red-final app-management IntegrationPolicyResolverHttpTest` | 2/2/0/0：合法policy经实际全局guard得403（应200）；真实ADMIN policy后revision仍1（应2） | `http-behavior-red/`原XML/rawlog/红时source |
| `iam-stage-4-d2-http-green-2 app-management IntegrationPolicyResolverHttpTest` | 2/0/0/0 | logs原输出 |
| `iam-stage-4-d2-matrix-red app-management IntegrationPolicyResolverHttpTest` | 9/2/0/0：samepolicy后旧source token重复exchange仍成功；另审计异常错误分派403与测试期待500不一致 | `matrix-red/`原XML/rawlog/source |
| `iam-stage-4-d2-matrix-green app-management IntegrationPolicyResolverHttpTest` | 9/0/0/0；加原认证版本锚点；测试限定DataAccessException advice格式化故意audit失败为500，实际service事务/PG触发器未替换 | logs原输出 |
| `iam-stage-4-d2-role-refresh-red app-management IntegrationPolicyResolverHttpTest#adminRoleMustStillExistAtTheFinalResourceDecision` | 1/1/0/0：最终ADMIN被收窄后返回200（应403） | `role-refresh-red/`原XML/rawlog/红时source |
| `iam-stage-4-d2-role-refresh-green app-management IntegrationPolicyResolverHttpTest#adminRoleMustStillExistAtTheFinalResourceDecision` | 1/0/0/0 | `role-refresh-green/`原XML/rawlog |
| `iam-stage-4-d2-final-focused admin-common,app-management,platform-gateway PolicyRequestVerifierTest,IntegrationClaimsTest,IntegrationPolicyResolverHttpTest,PolicyResolverSecurityTest,IntegrationRulesTest,IntegrationPolicyTest` | common5、app17、gateway5，均0失败/错误/跳过 | `final-focused/`本轮6份XML/rawlog |
| `iam-stage-4-d2-final-common admin-common *` | 150/1/0/0；唯一旧静态扫描固定158失效，新增精确policy使实际159且常量注解旧正则读为空路径。不是HTTP守卫红 | `full-common-failure/`本轮14份XML/rawlog |
| `iam-stage-4-d2-ingress-final admin-common,app-management AuthorityPolicyTest,IntegrationPolicyResolverHttpTest` | 受影响静态清单14项与最终app真实HTTP13项均绿；固定literal与共用PATH直接相等断言，原158目录+精确policy集合完整比对，policy绝不在service目录 | `ingress-final/`本轮2份XML/rawlog |
| `iam-stage-4-d2-final-common-fixed admin-common *` | **150/0/0/0，BUILD SUCCESS**，包含原145+新增5 | `final-common/`本轮14份XML/rawlog |

最终保留的验证边界：完整common150；app真实HTTP13最终同码、config2/rules2聚焦绿；gateway直接策略回归5。没有把不同轮次加成“177全工程测试”，也没有声称全app或七模块全量通过。部分中间绿色轮次XML在后续运行时被target更新，只有其原rawlog保留；未用后轮XML冒充这些中间轮次。所有已出现失败测试轮次原XML均独立归档。首HTTP准备阶段Actuator discovery未登记两轮、V102种子外键缺失一轮单列`http-setup-failure*`；首次行为轮另混有fixture标签配置错误，保存`http-behavior-red-initial`；一次测试误把约定16字段写17的失败保存在`http-field-count-failure`，改为精确字段集合校验。这些不是业务红。

## 具名最终覆盖

`IntegrationPolicyResolverHttpTest`共13个具名测试：

1. `policyAndOrdinaryAdminServiceBothWorkInSameMappingAndNoSecretsLeak`：同mapping双正例、16字段、角色/labels拆分、无秘密、policy查PG=1/authority=0。
2. `policyAndRevokeChangesAdvanceRevisionAndOldExpectedVersionRejects`：真实ADMIN policy/revoke，revision1→2→3，旧expectedVersion409、移除主体false。
3. `wrongSignatureClaimsAlgorithmsMethodPathAndBindingNeverQueryPg`：错误caller/sub/target/action/issuer/use/aud、普通service/portal/integration/authority签名域、none、过期/未来时间、方法/路径/body/nonce及重复JSON；PG=0。
4. `strictDtoTypesBoundsAndUnknownFieldsRejectBeforePg`：每字段缺失/类型，版本/ID/长度/旧版本格式/额外字段，PG=0。
5. `tenantAppBizCredentialPrincipalIsolationLongMappingAndSubjectRemoval`：跨租户/app/biz/id/principal拒绝，两个有限合法用户alice/长user，长principal与旧canonical digest兼容，未知用户无许可。
6. `crossedTrustDomainsAndWrongCredentialResourcesWriteNothing`：policy↔service错域、错id/旧资源SHA不写入。
7. `invalidPolicyAndAuditFailureRollBackRevisionAndPayload`：invalid400；真实PG audit触发异常，原事务回滚revision和policy。
8. `issueBindsStoredPublishedVersionAndInitialRevisionIsOne`：错发布SHA拒绝，真实issue初始revision1，测试临时vault。
9. `realResolverBackedGrantsFailAfterRevisionAndExplicitNewAuthenticationRecovers`：samepolicy digest不变、revision变更，旧exchange/resume/authorize拒绝，fresh认证成功，再REVOKED新认证拒绝，每决定一次PG查询、无递归。
10. `concurrentStaleWritesSerializeAndSnapshotsNeverMixRevisionAndPolicy`：两个同旧资源并发写只有一个200/一个403，四个有限并发snapshot只见一致旧或新version/digest/subjectAllowed，重取新授权后revision3。
11. `nonAdminAndWrongTenantCannotMutateEvenWithCorrectServiceRoute`：真实受信service但无ADMIN/错租户不写入。
12. `adminRoleMustStillExistAtTheFinalResourceDecision`：最终当前决定丢ADMIN时403/零写入。
13. `actualPolicyChainWithoutGlobalProofRegistrationCannotEnterResolver`：同真实app装配缺proof Method登记时403/PG0。

common四项PolicyRequestVerifierTest另证明request/verifier复制、Proof body副本修改、过期、错误Method、假attribute、缺verifier、缺登记拒绝；空/私钥/重复/过多keys失败，未知kid/同kid错误私钥签名/错误typ/31秒TTL均拒绝。IntegrationClaimsTest及gateway五项直接回归验证规则委托兼容。PolicyResolverSecurityTest证明disabled无入口和enabled缺/私钥/错公钥启动失败。

## 冻结、补丁与保护

`source-before.json`与`baseline/`保存319个起点source/POM；`source-after.json`与`frozen/`保存332个最终source/POM；`changed-files.json`列21个实际新增/修改文件。common原文件仅授权的ServiceAuthorization/LoopbackAuthority/相关AuthorityPolicyTest/POM变化，其余原common源保留；六业务其他未负责源及四契约不动。

`change.patch` SHA256=`1577fea756786bc8b193fb0004628ca71ee7c88232b75b748d99e01bc788b69f`。无HEAD（`git rev-parse --verify HEAD`为Needed a single revision），remote为空，因此无虚假commit/HEAD diff。使用baseline/frozen实际no-index diff。

`freeze.mjs`将diff及apply限定在独立Git搜索边界（GIT_CEILING_DIRECTORIES），防父仓库prefix跳过和`.gitattributes`自动LF归一影响原字节；`replay/`从baseline实际git apply，`replay-check.json` **332条全部逐字节相等**，`freeze-summary.json`记录完成时间/补丁hash。首次子目录apply exit0但跳过被最终新文件缺失校验拦住，空原输出保存在`apply-skipped-before-ceiling.log`；第二次LF归一补丁不能应用原CRLF基线，失败原输出保存在`apply-lf-normalized-failure.log`。最终`apply.log`为真实成功输出，保留30行CRLF空白警告；未清洗源码去迎合补丁。早期打包过程的临时diff文件被最终有效patch取代，未将它们计作可重放交付。原D1 1499警告及旧Config M1日志/证据原样保留。

`frozen-target/`保留common312/app127/gateway54个target文件及三个target-sha256.json；包含测试class与实际测试XML，不能把target内旧轮其他XML混作当轮测试集合。当轮边界以独立archive及rawlog为准。

`protected-before.json`/`protected-check.json`核5745项；5743项完全一致，仅`expected-root-changes.json`单列root明确确认的两个并发变更：`docs/evidence/progress.md`执行索引与`iam-stage-4-d3-root-handoffs.md`准备交接（各保留old/new SHA）。D2未写这两文件，没有对整个evidence豁免。root维护的其他索引不属于本轮source diff。

## 实际源码对照与自审边界

实际阅读、固定版本URL及文件SHA列于`upstream/read-source-sha256.json`：Nimbus9.37.3 `JWSObject.verify`/`RSASSAVerifier.verify`只保证签名字节，独立claims/用途/时间约束由既有strict verifier负责；Spring Security6.5.0 `FilterChainProxy.getFilters/doFilterInternal`先匹配单一chain并传firewalled request，支撑精确Order(1)及request-bound proof；Spring6.2.7 `MappedInterceptor.matches`无include/exclude即全映射；JdbcTemplate6.2.7 `query(PreparedStatementCreator,PreparedStatementSetter,ResultSetExtractor)`使用参数绑定并只执行一次query。后两份本轮官方source jar保留；Nimbus/MappedInterceptor读取前序只读官方源码副本并单列SHA，不改旧证据。

自审/未完成：

- D3尚需真实gateway入口首次取得可信credential/published metadata、具体资源版本/父子关系，与实际downstream SecurityFilterChain组成原完整R3；本轮没有proxy/controller业务桥接迁移，gateway仅纯规则委托与不允许此路径的直接策略断言。
- 实际生产IAM仍须固定HTTPS/TLS host/certificate、持久grant及认证→版本锚点，及完整错误TLS/503/404响应材料链的联合验收。本轮loopback HTTP/RSA/PG是有限内部证据。
- 未运行原`DraftMetadataHttpTest`、gateway`IntegrationSecurityTest`或其余业务服务旧SpringBootTest；未运行app原`ApplicationLifecycleTest`/`PublishTransactionTest`/`AppCopyConcurrencyTest`及不相关domain/routes套件。旧直接IntegrationService测试还需随D3迁移到显式当前授权上下文；本轮不以全局mode/service-id覆盖绕过负向配置或伪称它们通过。
- enabled配置本轮提供精确入口开关，但正式生产IAM链仍闭锁；D3必须完成实际配置与guard/coverage装配。业务HTTP admin接口的签名/响应格式未改，IntegrationController自身无需源码改动，调用的真实IntegrationService已复核资源。
- 旧Config M1预期启动失败日志较多，本轮原日志照存；补丁30行CRLF警告与中间绿色轮次XML未独立归档的证据粒度限制如上披露。没有为消噪或补历史反复跑测试。

源码与target于本报告完成时归还root，后续仅独立Spec/Quality审查；D2作者不再写源/target。

## D2 集中返修追加（2026-10-20，待同一 reviewer 复审）

本段只追加，以上报告历史不改。原报告字节 SHA256 为 `3622e92a45a4fd5bf21776f6bc379f4cca5bd36ee9efffd5161a93c8adecdf0b`，完整原前缀副本存 `iam-stage-4-d2-fixes/report-original.md`，交回时再次按原长度验证前缀字节。原 `iam-stage-4-d2/` 目录、原补丁/冻结/replay/所有轮次 XML 与独立 review 原文均不覆盖。

本次依据 `iam-stage-4-d2-fix-brief.md` 集中修复独立 review I1，以及 root 接受为本次缺口的 ⚠2。只修改两个测试文件：`admin-common/src/test/java/cn/agentplatform/common/authority/LoopbackAuthority.java` 与 `app-management/src/test/java/cn/agentplatform/app/application/IntegrationPolicyResolverHttpTest.java`。没有改生产源、原对外格式、四契约、默认 C fixture、gateway、框架版本或生产 IAM；未新增同名 CeilingPolicy 类、生产服务或无限缓存。

### 实际修复与有限证明

external fixture 验签后固定校验实际原出票常量 `ServiceTokens.ISSUER`，要求 `typ=JWT`、HS256、integration use/audience、非空文本 jti（最多128字符）、主体/凭据/时间及声明。所有源 roles/attributes 使用既有 `AuthorityProtocol.actor/strings/attrs` 严格解析，缺失或错误类型拒绝；属性白名单仍为 region、dataLabels、bizSystemCode、userId，未知属性拒绝。principal 由已有 `IntegrationClaims.principal(biz,user)` 重算，biz/user 保留属性必须精确一致。

`originalAuthentication` 在已有有限 Grant 集合中按已验 issuer+jti 找回原认证。查到后逐项比较 tenant/sub/credential/app/biz/user/origin/policyDigest/use/audience/iat/nbf/exp 与原 Actor 声明；集合比较不受 JSON 字段或集合顺序影响。变更绑定、时间或原声明均在 PG 前拒绝。raw token SHA 仅保留诊断绑定，不能创造新认证身份；另一合法 JWT 表示继续沿用初始 policyVersion 和 ceiling。Grant 有限额度保持，失败验证或旧版本决定不创建 Grant。

`initialIntegrationCeiling` 复用已有主体/上限纯规则，角色取源声明与 PG 上限交集并移除平台角色；对白名单许可属性逐项取交，缺失/空集合保持空，不能从 PG 填回。dataLabels 是限制义务，取源与 PG 并集；biz/user 精确绑定。结果再次通过两侧 `AuthorityProtocol.within` 检查。原认证重复 exchange 直接保留首次 ceiling；resume/authorize 继续查询实际 PG、核初始版本及固定上限。

新增具名 HTTP 用例（原13项仍保留，最终总19项）：

- `stableAuthenticationRejectsEquivalentOldTokenAfterSamePolicyRevision`，参数化 `pad-bits` 与 `reordered-claims` 两例：原 token 首次授权后，真实 ADMIN 提交相同 policy，digest 不变、revision+1；未预登记的等价签名 pad-bits token、同 issuer/jti 的重排 claims token 都不能重建新版本授权；原 token exchange、旧 resume/authorize 同样拒绝。每个决定实际 resolver/PG 查询=1，所有失败前后 Grant 数不变；只改为新 jti 的明确新认证成功。pad-bits helper 只生成一个确定变体并验证签名字节/claims 字节相同，不枚举，也不打印 token 或密钥。
- `externalGrantKeepsNarrowSourceClaimsAndUnionsLabelsThroughEveryDecision`：真实 PG 允许 reader/writer、region A/B，源声明只有 reader（额外源 ADMIN 被剔除）和 A；原票/initial_ceiling/current/resume 后票与 current/另一合法序列化的重复 exchange 均只保留 reader/A，标签同时包含 source-only 与 restricted。各决定 PG=1。
- `malformedSourceAuthenticationRejectsBeforePolicyAndConsumesNoGrant`：错 MAC/错签名域、错 issuer、缺/错型/空/超长 jti、缺/错型 roles/attributes、未知属性、错主体和保留属性绑定全部 PG=0、Grant 数不变。
- `sameAuthenticationCannotChangeBindingsTimesOrOriginalCeiling`：同 issuer+jti 变更原13种绑定/时间/声明时 PG=0、Grant 数不变。
- `emptySourcePermissionsAreNeverReplenishedFromPolicy`：源 roles 为空、region 缺失或显式为空时，PG 较宽策略不能补角色/region；PG 标签义务保留，initial_ceiling/current 一致，各决定 PG=1。

旧 `realResolverBackedGrantsFailAfterRevisionAndExplicitNewAuthenticationRecovers` 的源 token helper 补齐实际原格式自带的 issuer/roles/attributes 与保留身份属性；并未把源上限修改成 PG 全量来消除缺口。原默认 `integrationClaims()` 和非 external 分支保持原行为。`CurrentAuthorityClientTest` 源码未改，在完整 common 中实际19项通过。

### 每轮原命令、结果与立即归档

所有运行使用原受控 launcher `.tools/run-iam-module-tests.mjs`，仍只读本新工程专用 PG 测试凭据，使用专用库 `agent_platform_iam_policy_v12`、随机端口 Spring/独立临时 RSA/HMAC；未输出配置或密钥。新 `iam-stage-4-d2-fixes/run.mjs` 顺序调用该 launcher，并在下一轮开始前立即复制本轮生成的 XML、raw.log、launcher.log、两份实际源码及完整332项同码 SHA。只归档本轮更新的 XML，编译失败为0个 XML，不能挪用旧 target 报告。

下表各 stage 的完整前缀都是 `iam-stage-4-d2-fix-`；每个目录的 `run.json` 保留原始完整 argv/退出码/XML SHA，`raw.log` 是未经改写的原输出。实际 Maven 参数为 `-o -B -ntp -pl <modules> -am test -Dmaven.repo.local=.tools/m2 -Djdk.net.unixdomain.tmpdir=V:/.local/tmp -Dtest=<tests> -Dsurefire.failIfNoSpecifiedTests=false`，由 Java21 受控 launcher 执行。

|stage|实际 modules / tests|tests/failures/errors/skipped|解释|
|---|---|---|---|
|red|app-management / `IntegrationPolicyResolverHttpTest#stableAuthenticationRejectsEquivalentOldTokenAfterSamePolicyRevision,IntegrationPolicyResolverHttpTest#externalGrantKeepsNarrowSourceClaimsAndUnionsLabelsThroughEveryDecision`|未运行测试，exit1，XML0|新增测试误用 assertSoftly 静态入口，编译失败；改为 SoftAssertions.assertSoftly。不是行为红。|
|behavior-red|app-management / 同上两方法|3/1/0/0|窄上限真正红：writer/region B 被增回、source-only 标签丢失。最初变体用例在 revision 前先交换了变体，等于预登记其 raw SHA，两个参数意外绿；明确不能用它们证明 I1。保留原输出/XML/源码。|
|anchor-red|app-management / `IntegrationPolicyResolverHttpTest#stableAuthenticationRejectsEquivalentOldTokenAfterSamePolicyRevision`|2/2/0/0|去掉变体预登记后，两变体都意外成功，`Expecting code to raise a throwable`；这是 I1 真实 HTTP/PG 行为红。实现仍为原 raw SHA 查找。|
|focused-green|app-management / `IntegrationPolicyResolverHttpTest#stableAuthenticationRejectsEquivalentOldTokenAfterSamePolicyRevision,IntegrationPolicyResolverHttpTest#externalGrantKeepsNarrowSourceClaimsAndUnionsLabelsThroughEveryDecision,IntegrationPolicyResolverHttpTest#malformedSourceAuthenticationRejectsBeforePolicyAndConsumesNoGrant,IntegrationPolicyResolverHttpTest#sameAuthenticationCannotChangeBindingsTimesOrOriginalCeiling`|5/0/0/0|两个参数及三方法均通过，XML1；随后仅补空上限和错 MAC/域的边界测试。|
|final-common|admin-common / `*`|150/0/0/0|14份原 XML，BUILD SUCCESS；原 common150默认行为保持，包含 CurrentAuthorityClientTest19。|
|final-http|app-management / `IntegrationPolicyResolverHttpTest`|19/0/0/0|1份原 XML，BUILD SUCCESS；与 final-common 完整332项源码 SHA 相同。|

实际命令统一为 `node docs/evidence/iam-stage-4-d2-fixes/run.mjs <stage完整名> <modules> <tests>`，runner 内实际执行 `node .tools/run-iam-module-tests.mjs <stage完整名> <modules> <tests>`。每轮均在新目录归档，未倒跑历史绿色轮次。没有重复 gateway5、七 reactor、package、主服务启停或旧业务无关 suite。最终 app 指真实 HTTP19，并不宣称原业务全部测试通过。

### 本次冻结、实际 apply 与保护

`iam-stage-4-d2-fixes/source-before.json`/`baseline/`为原 D2 冻结332项，`source-after.json`/`frozen/`为最终332项；`changed-files.json`严格仅两个授权文件：

- LoopbackAuthority.java SHA256=`04550cbd37efbb89b0a33ddcdefa9f6c0a34cb142fea3e47e935985433e7ad6c`
- IntegrationPolicyResolverHttpTest.java SHA256=`803dfdcdf2f3afa2aa25fd7542dec1d05a37734762953eeeba8e0c950a58c605`

新 `change.patch` SHA256=`e805f81979349d81e719eee49acb36c6a7a81c3a152fd8f14882f0dc3b8588a4`，相对原 D2 完成态；与原 D2 patch `1577fea756786bc8b193fb0004628ca71ee7c88232b75b748d99e01bc788b69f` 一并保留。`freeze.mjs` 使用原字节、`core.autocrlf=false` 和显式 `GIT_CEILING_DIRECTORIES` 隔离父仓库属性/前缀，不创建 commit。实际在新 replay 副本 `git apply --binary` 后，`replay-check.json` 332项全部逐字节相同；本次 diff.stderr.log/apply.log 均为空、无新空白告警，原D2告警与M1日志仍原样保留。

最终 target 实际冻结 common313/app131/gateway54文件及各自 SHA 清单；gateway target 与原 D2 的54项完全相同。target 可能包含原旧轮其他XML，当前运行范围仅以上逐轮独立目录有效。`protected-before.json`/`protected-check.json` 覆盖原D2目录全部文件、contracts与独立review，共1610项全部一致；另用332源码严格比较保证除两授权测试外没有生产或其他业务源码变化。本次没有将 root 管理的 D3 handoffs/执行索引纳入这个保护集，不构造大目录豁免；它们不在返修 diff。

本轮新读的是实际 `ServiceTokens`、`AuthorityProtocol`、`IntegrationClaims`、`HttpCurrentAuthorityClient` 与 reviewer 的具名 Nimbus pad-bits验证脚本；只复用原D2固定版本已读机制，没有新增框架或声称新生产IAM验证。

自审边界：稳定锚仍仅是有限测试 Grant 存储，生产 IAM 的持久 issuer/jti/主体/初始版本与上限锚点仍需 F/G 签收；D3实际 receiver/gateway资源来源与全R3、真实HTTPS/TLS和原业务统一回归均未提前关闭。原M1/M2历史材料不改；本次六轮都按当轮真实产物立即归档。源码与target在交回清单复核完成后归还root，待原reviewer独立复审。
