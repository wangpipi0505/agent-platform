# G6 Task2 可信 JWT/JWKS 输入实施证据

2026-10-20；唯一工程 `G:/207/项目/算法重组工具/代码/agent-platform`。本轮仅 Task2 软件输入边界，交付待独立审查。生产状态仍为 **BLOCKED_IAM_SCOPE_ACCEPTANCE**，不代表真实 IAM 签收或部署。

## 需求与前置

执行 [iam-stage-2-implementation-brief.md](iam-stage-2-implementation-brief.md) 中原 Task2、§2.1–2.3、§4 及 root 正式前置交接；其批准计划摘要为 `8b2fb5e7e08907625827d06a592688a868b3e23e0ccd1907d424e96840832d14`。工程 AGENTS、[implementation-brief](../implementation-brief.md)、[四契约说明](../../contracts/README.md) 保持。执行使用 executing-plans、test-driven-development、ponytail、verification-before-completion；已存在明确授权的新工程隔离范围，无 HEAD，不创建/切换工作树，不派子 agent。

起步先复制完整 common src/POM **28 文件**至 [baseline](iam-stage-2/baseline/)，[baseline-sha256.json](iam-stage-2/baseline-sha256.json) 精确等于最新 `iam-stage-1-ownership-fix/source-after.json`，没有取旧55项版本。Task1 Policy14、原配置37及旧Common/Schema/Vault10形成61项已关闭前置。Task1 ClaimCeiling 与路由目录均未改。

## 明确支持的客户端适配 profile

下列字段约定是本客户端的受限 IAM adapter profile，经 root 接受；不是 v1.2/RFC 强制字段，不声明任意外部 issuer 兼容。所有身份字段取已签 JSON；请求头/body 不构成 caller、tenant、sub、角色或属性来源。

| 域/字段 | 支持与严格边界 |
| --- | --- |
| 共有 JOSE | compact JWS、RS256、`typ=at+jwt`、非空 string kid；JWT 最多16384个ASCII字符/字节。拒绝 none/HS256/其他类型以及 jku/x5u/jwk/x5c/crit 头；不发现网络端点 |
| 共有 claims | iss/sub/jti/token_use/client_id 均非空 string；原始 exp/iat 为整数NumericDate，nbf若出现亦为整数；拒绝缺项、字符串数字、小数、秒转毫秒long溢出与重复JSON字段。aud 支持单 string 或恰含一个string的数组，精确匹配配置，不接受额外受众 |
| 时间 | Clock注入、skew0；exp必须严格晚于now和iat，iat不得在未来；nbf不得在未来。内部exp−iat≤配置max-ticket-seconds且≤60，内部nbf必需。门户不套用内部60秒上限；测试门户300秒不是生产上限 |
| portal | 仅gateway可配置enabled；精确配置issuer/JWKS/audience/client-id，`token_use=user`，`client_id`绑定配置portal client，tenant_id/sub必需。ID token/JWT typ、错误client/use均401 |
| portal roles/attributes | roles为string数组；attributes为string→string数组。角色与配置role-allowlist交集；region与配置值交集；未知属性不映射；dataLabels保留原集合并合并配置义务，即使键未列入许可allowlist也不丢弃。bizSystemCode/userId只能两侧精确同值单元素；冲突/缺侧/多值403，空白/非string是malformed401 |
| portal scope | 仅接受空格分隔ASCII action字符串，不接受数组/数字；缺项或空串成为空集合。与配置scope-allowlist交集。真实可信Web链要求基础入口scope `platform.access`，缺失403；该入口scope不代表具体资源操作已授权 |
| delegated service | authority独立issuer/JWKS；`token_use=service`、`authority_mode=delegated`、aud恰为本service配置；caller/client_id精确一致且在配置代码固定六服务登记中，target恰为本service，action/scope精确同一个已登记该caller→target的delegated动作；tenant_id/sub/grant_ref/authorization_version必需 |
| workload service | `authority_mode=workload`；caller/client_id/sub精确等于同一个已登记client身份；只接受既有route catalog列出的workload动作，不能拿delegated业务动作；tenant_id/grant_ref/roles/attributes/authorization_version/act/user_id出现即拒绝；不产生用户Actor |
| service resource | resource为精确包含kind/id/version_digest三个非空string的对象；这是可信输入结构，尚未比对某次HTTP资源或当前IAM决定。nonce、在线版本/撤销、操作资源绑定和current证据属后续Task3/4 |
| integration | RS256 decoder不接受integration用途，也没有HMAC fallback。现有显式dev/test ApiKey/SDK与专用integration HMAC路径保留；本轮不实现真实IAM集成桥接或新生产integration decoder |

`AuthorityProperties.Portal` 窄增 `attributeValueAllowlist: Map<String,Set<String>>` 和 `scopeAllowlist: Set<String>`；强绑定器仍拒绝未知配置。键名单与值上限键集合必须一致；值非空非空白，归属值只许一个。配置仅允许ADMIN/EDITOR/APPROVER业务角色，禁止SERVICE/GATEWAY_AUTH。scope集合必须包含本profile基础入口 `platform.access`。上限取自配置的issuer+client这一对，未建立任意动态issuer/client注册表。

## 实现与边界

- `TrustedJwtDecoder` 使用固定Spring Security/Nimbus验签。未验iss/use最多用于在已配置portal与authority候选中选择，不派生URL。每次验签从固定端点重新读取小JWKS，不缓存、不重试、不用旧成功结果；每份JWKS最多8把、每把RSA公钥≥2048位，要求唯一非空kid、alg=RS256、use=sig，key_ops若有只能verify。混入oct、私钥字段、错误算法/用途、重复kid、证书/远程链接或不可解析响应整体503；有效JWKS找不到kid和错误签名401。
- `BoundedIamHttp` 仅GET配置JWKS，POST配置token/decision URI。生产HTTPS，testProfile显式true时仅字面127.0.0.1/::1 HTTP例外；拒绝userinfo/query/fragment、非配置端点和重定向。连接≤1000ms；总请求/正文期限≤2000ms（包括连接等待，比单独read deadline更保守）。自定义BodySubscriber在消费期间限制65536字节；未知长度分块正文同样受限，溢出/超时取消subscription和HTTP future。没有手写密码学。
- `PortalClaimMapper.map(Jwt)` 返回Actor+允许scope；decoder返回的portal Jwt已替换roles/attributes/scope为裁剪结果，Actor四字段不变。`Actor.current`拒绝workload，RS256身份移除SERVICE/GATEWAY_AUTH。原HMAC开发语义保留。
- `SecurityConfiguration.security` 在可信decoder链使用裁剪角色及scope，拒绝portal直接调用internal，且在current-authority/资源链缺席时拒绝所有RS256 internal请求；401/403输出ApiResponse标准code。外层filter保留上游PlatformException的503，避免Nimbus包装后变成错误的token401。
- **生产装配仍闭锁**：原静态BeanFactoryPostProcessor在任何IAM业务HTTP监听器建立前抛IAM_TRUST_CHAIN_UNAVAILABLE；没有ready/跳过guard开关，也未把可信decoder装配成可启动的生产业务链。独立测试配置通过反射仅调用真实security链方法，未导入生产配置guard；该测试配置/issuer全部位于test目录。新adapter在生产尚不可启动是本轮必要边界。
- `ServiceTokens` 仅补明确的dev/test用途文档，未改变既有HS256输出、ApiKey或SDK授权形式。生产不能通过已关闭的IAM装配回退该路径。后续签发应由Task3 authority exchange替换，不能把本轮注释当成已完成生产续票。

## 固定开源源码实际对照

从Maven Central下载并保存在 [upstream](iam-stage-2/upstream/) 的**固定实际版本**源JAR，不变更POM或依赖。URL与SHA在 [upstream-sha256.json](iam-stage-2/upstream-sha256.json)，提取源码和源JAR也纳入最终manifest。源获取脚本见 [setup.py](iam-stage-2/setup.py)。

| 实读源码 | 采取的机制/防守 |
| --- | --- |
| Spring Security6.5.0 `NimbusJwtDecoder.createJwt/validateJwt/processor` | 保留Nimbus processor验签，再执行Spring validators；不用withIssuerLocation发现或默认JWKSource缓存。自定义source异常可能被createJwt包装，因此沿原因链只恢复本地固定503异常，其余token错误用无敏感详情的BadJwtException |
| `JwtTimestampValidator.validate/setClock` | 默认skew60且仅当现有exp/nbf才检查。显式Duration.ZERO与注入Clock；另加必需字段、未来iat、严格exp>now，以覆盖默认exp==now边界 |
| `MappedJwtClaimSetConverter.convert/withDefaults` | 默认coerce claim类型并在缺iat有exp时补iat；本实现先查原始签名payload的类型与必需性，不能让补值绕过profile |
| Nimbus9.37.3 `DefaultJWTProcessor.process(SignedJWT)` | 类型检查→固定selector→库JWSVerifier→claims verifier；本实现不替代JWS/RSA，清空Nimbus默认claims verifier后由Spring显式validator完成时间与用途检查 |
| `JWSVerificationKeySelector.selectJWSKeys`、`JWKSet.parse` | selector限定RS256；JWKSet默认可能忽略未知kty，故先用严格JSON逐把验证并构造受限JWKSet，未知类型不静默忽略 |
| `JWTClaimsSet.parse` 第990/993/996行 | 实际使用`seconds * 1000`的long转换。真实RS256 payload中`(1L<<61)+合法exp`会wrap回合法短日期；新增失败样本后以Math.multiplyExact在库转换前拒绝exp/iat/nbf溢出，未改变库或实现密码学 |

## 真实红绿与命令

固定执行环境：Java21 `C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot`，工程离线`.tools/m2`；V:仅映射本新工程，Unix-domain临时路径V:/.local/tmp。未读取.env/vault/masterkey/实际凭据或启动主服务。所有Maven命令均带以下前缀，未使用package/install：

```powershell
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
mvn.cmd -o -B -ntp -pl admin-common -am test '-Dmaven.repo.local=.tools/m2' '-Djdk.net.unixdomain.tmpdir=V:/.local/tmp' '-Dtest=TrustedJwtDecoderTest,PortalClaimMapperTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

每次原始输出均保存且不覆盖，阶段如下；选择器变化如下表所列。没有将缺类/编译/环境失败当作关键行为红。

| 日志 | 选择器/结果 |
| --- | --- |
| [seam-error.log](iam-stage-2/seam-error.log) | 最小接缝起步2 errors：Jwt.Builder要求Instant，属于接缝修正，**不计行为红** |
| [behavior-red.log](iam-stage-2/behavior-red.log)、[red-seam源码/XML](iam-stage-2/red-seam/) | 修正接缝时间后2项、2 assertion failures、0 errors/skip：真实k2签名伪报kid=k1被放行；ADMIN/SERVICE/GATEWAY_AUTH越界角色未裁剪。正例也实际构造真实RS256签名；此时接缝尚未实现验签 |
| input-green-1.log | 实现初稿一个Java类型推断编译错误，非行为红；保留 |
| input-green-2.log / input-green-3.log | 7项选择器迭代；前者1 failure追到测试签发器先把日期类型归一化，改为JWSObject原始payload后7/7通过。该测试修正无生产回退 |
| [http-red.log](iam-stage-2/http-red.log) | `TrustedJwtDecoderTest#realHttpRejectsPortalServiceRoleMissingScopeAndJwksOutageBeforeHandler`：1 failure/0 errors，旧链403缺标准code |
| [http-scope-red.log](iam-stage-2/http-scope-red.log) | 同一单方法优先检scope：1 failure/0 errors，缺scope预期403实际200，handler被执行 |
| [http-green.log](iam-stage-2/http-green.log) | 双类8/8；实际随机loopback HTTP证明成功200、portal带SERVICE直接internal403、缺scope403、错误RSA401、JWKS503、恢复200；每次拒绝handler计数不变 |
| expanded-input-1.log / expanded-input-2.log | 双类扩展首轮lambda编译接缝错误非行为红；修复后11/11，包括delegated/workload、字节上限、正文闩锁期限、POST编码 |
| [actor-role-red.log](iam-stage-2/actor-role-red.log) | `PortalClaimMapperTest`4项、2 failures/0 errors：RS256 Actor仍有SERVICE/GATEWAY_AUTH；另一个是测试对空白ownership错误期望403，应为malformed401，已修正该断言；真实角色红随后由Actor移除角色转绿 |
| [config-input-green.log](iam-stage-2/config-input-green.log) | `TrustedJwtDecoderTest,PortalClaimMapperTest,AuthorityConfigurationTest`52/52；增加强绑定portal值上限、test HTTP例外与IAM继续监听前闭锁 |
| final-input-green.log | 双类14/14，增加jku/重复raw claim无网络请求、redirect目的端点命中0 |
| [numeric-date-red.log](iam-stage-2/numeric-date-red.log)、[红源码/XML](iam-stage-2/numeric-date-red/) | `TrustedJwtDecoderTest#invalidClaimMatrixIsRejectedWithoutClaimCoercion`，1 failure/0 errors，已真实验签的overflow-exp被错误接受 |
| [numeric-date-green.log](iam-stage-2/numeric-date-green.log) | 双类14/14；Math.multiplyExact边界后overflow-exp/iat拒绝，原签名/轮换/HTTP/正文测试仍通过 |
| [full-common.log](iam-stage-2/full-common.log)、[XML](iam-stage-2/full-surefire/)、[summary](iam-stage-2/full-test-summary.json) | 冻结源码**唯一一次完整common：77/77，0 failures/errors/skips，BUILD SUCCESS**；Configuration39+Policy14+Mapper4+Trusted10+旧Common4/Schema5/Vault1。负向启动测试的预期ERROR日志不是Surefire错误 |

完整common命令没有`-Dtest`/failIfNoSpecifiedTests，其他相同：

```powershell
mvn.cmd -o -B -ntp -pl admin-common -am test '-Dmaven.repo.local=.tools/m2' '-Djdk.net.unixdomain.tmpdir=V:/.local/tmp'
```

### 负向矩阵与故障行为

- 实签错误RSA/kid、HS256以RSA公钥bytes作secret、none、错误iss/aud/use/client、额外aud、ID typ/use、缺exp/iat/jti/tenant/sub/client/kid、空/未知kid、过期/精确expiry/未来iat/nbf、数字sub/jti/字符串exp/小数iat/溢出iat/exp、错误scope/role/attribute类型、大于16KiB均拒绝。未知issuer、jku、重复claims不触发JWKS/意外URL请求。
- 角色ADMIN被本fixture上限EDITOR裁剪、region[A,B]裁剪[A]、不允许scope移除；dataLabels保留confidential并合并restricted；键allowlist未包含dataLabels时secret仍保留。ownership精确单值保留，冲突/多值/空白不补身份。
- JWKS k1→k1+k2→k2后旧kid立即拒绝；503期间即使之前成功也拒绝，恢复新请求成功，Clock推进后过期票据不复活。非法JWKS包含重复kid/私钥/oct/错误use/无kid/错误alg/9把keys/非JSON，均503。
- 内部两个模式正例均实签验证；缺grant/tenant/version、混用户claims的workload、client/caller/target不匹配、未登记动作、scope不覆盖、resource缺摘要、超过60秒、workload假sub、缺nbf与跨域portal均拒绝。workload `Actor.current`抛403。
- HTTP固定目的端点、POST form转义/JSON、302不命中重定向handler、恰65536字节成功、65537已知长度与chunked均拒绝。正文超时用1字节+两个闩锁和200ms期限，头已收到但正文未完成仍在有限时间取消；测试释放闩锁并关闭自有server，不做耗尽/慢攻击或长sleep。

## 冻结、补丁与原证据保留

[changes.json](iam-stage-2/changes.json)列出11项修改：新增3主类+3测试类，修改Actor/SecurityConfiguration/ServiceTokens说明/AuthorityProperties/AuthorityConfigurationTest。无业务服务/POM/迁移/前端/部署/smoke/外部台账修改。

- [frozen](iam-stage-2/frozen/)、[source-before.json](iam-stage-2/source-before.json)、[source-after.json](iam-stage-2/source-after.json)：完整common34文件，完整测试前后SHA相等；baseline28文件另存，含新增文件不会遗漏。
- [full.patch](iam-stage-2/full.patch)为完整11文件可应用unified diff；SHA256 **54656ea7bfab9b5ef745e9be684586c9b55657bb00a43180db6996a7d0e05051**。从原字节baseline/frozen生成，不依赖不存在的HEAD，不是空diff。
- [actual-apply.log](iam-stage-2/actual-apply.log)、[patch-apply-verification.json](iam-stage-2/patch-apply-verification.json)：真实git apply返回0；[applied-bytes](iam-stage-2/applied-bytes/)全部34文件逐字节等于frozen及当前源码。
- [target-sha256.json](iam-stage-2/target-sha256.json)：本次common target/classes、test-classes **53**输出摘要，交回root独立审查。没有打包或更新正在运行服务的JAR。
- [prior-evidence-before.json](iam-stage-2/prior-evidence-before.json)/after保存286个Task1/修复/当前review证据摘要，本轮前后全相同。[legacy-review-preservation.json](iam-stage-2/legacy-review-preservation.json)再核Task1修复原178项清单的177原文件/原review probe全部逐字不变；唯一review报告是root在本轮交接前追加独立批准，已与本轮起步hash匹配，不倒写成旧版本。旧55项、旧红字节、旧失败apply、修复61项报告和manifest均保留。
- [manifest.json](iam-stage-2/manifest.json)包含本轮报告和全部证据SHA（清单本身不自包含）；旧清单不修改。

重建/核验原始命令：

```powershell
python docs/evidence/iam-stage-2/freeze.py freeze
git -c core.autocrlf=false apply --verbose --whitespace=nowarn --directory=docs/evidence/iam-stage-2/applied-bytes docs/evidence/iam-stage-2/full.patch
python docs/evidence/iam-stage-2/freeze.py verify
python docs/evidence/iam-stage-2/preserve-history.py
python docs/evidence/iam-stage-2/freeze.py manifest
```

freeze步骤内部已真实执行上述git apply；已应用目录不可重复apply，verify只读源码并重核。测试完成后未修改冻结源码。

## 限制与后续交接

本轮不执行Task3–7；没有真实IAM current决策/nonce签名响应/授权版本撤销、持久grant续票、六服务caller/action/resource守卫、流式permit或生产集成bridge。服务票据输入验签正确不能替代这些能力。因此生产IAM无HTTP监听，内部RS256链继续拒绝执行；所有真实业务服务/固定962 JAR保持原状态。未读真实凭据、未启停主服务、未重复七模块、未package/发布/提交，未改变三外部台账。测试结论只覆盖明确声明profile与本轮有限loopback样本，真实供应商兼容及生产IAM scope签收仍待后续授权验收。
