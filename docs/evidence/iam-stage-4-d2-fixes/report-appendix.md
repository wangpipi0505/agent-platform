
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
