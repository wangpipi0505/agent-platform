# G6 Task3：当前 authority、服务票据与响应生命周期

状态：**实现及有限协议测试完成，交独立审查；不是生产 IAM 签收。**

唯一工程为 `G:/207/项目/算法重组工具/代码/agent-platform`。从 root 正式移交的 Task2 复审版本开始：`iam-stage-2-review-fixes/source-after.json` 的 **34 个完整 common src/POM 文件、82 项测试**，逐项 SHA 一致。未回退至 77 项版本。任务执行期间仅修改 `admin-common` 和本报告/`iam-stage-3/` 证据；未读取相邻旧工程、`.env`、vault、真实密钥或实际服务数据，未启动/重启主服务，未 package、install、提交或发布，也未派生子 agent。

## 最终验证与冻结

- 最终源树：**63 文件，29 新增、4 修改**。完整列表及每文件前后 SHA：`iam-stage-3/changed-files.json`。
- 最后一次完整 common：**113 tests / 0 failures / 0 errors / 0 skipped**，31 项为本次三个测试类（15 current、13 response、3 provider），其余 82 项保持。原日志为 `iam-stage-3/frozen-full-common.log`，10 个原始 XML 全部在 `iam-stage-3/frozen-full-xml/`，机器汇总为 `full-test-summary.json`。
- `source-before.json`、`baseline/` 保存完整起点；`source-after.json`、`frozen/` 保存最终完整 common。**501 个保护文件 SHA 全部未改变**，包括其他业务模块/前端/契约/根 POM及 common 生产启动配置；`protected-before.json` 与 `protected-after.json` 相同。
- `frozen-target/` 保存最终 target 全部 237 文件，清单在 `target-after.json`。其中 `admin-common-0.1.0-SNAPSHOT.jar` 是起点已有旧 jar，本轮没有 package，**不得将该旧 jar 当本轮交付包**；审查使用本轮 `classes`、`test-classes` 和原始测试报告。
- 工程没有可用于本任务的 HEAD/remote 提交基础；没有输出空 git diff 或制造提交。`full.patch` 是完整 common 源码补丁，包含新增文件。
- 补丁 SHA256：`93b1771243b0b7c1674099b72fe4be89f3c5fee6570e191f6406d6872bda5d19`。
- 最终源清单 SHA256：`9a21048bbc9b4bc342a64e0ec5c6a243b2edcf120e7acaa0c05f9527a3855ab8`。
- `freeze-and-verify.py` 实际在独立 `applied-final/` 起点副本执行 `git -c core.autocrlf=false apply --check`、`git -c core.autocrlf=false apply`，均 exit 0；**实际应用后的全部 63 文件与 frozen 和 live 逐字节相同**。命令/输出在 `apply-log.json`，结论在 `freeze-proof.json`。
- 首次实际 apply 受本机 `core.autocrlf=true` 影响，19 个文件仅换行不同；保留实际 `applied/` 和 `first-apply-newline-mismatch.json`，没有把规范化比较冒充逐字节相同。最终每命令关闭该选项，未改全局 Git 配置。最终 apply 的 whitespace 提示来自部分新 record 文件原有 CRLF，保留源码字节后比较通过，没有使用 `--whitespace=fix`。

## 已实现的共同接口与边界

`CurrentAuthorityClient` 的 client/exchange/resume/forward/authorize/authorizeWorkload/approver 与原精确签名一致；`VerifiedAuthority`、`ServiceTicket` 显式携带 `OperationBinding`。使用固定配置的 issuer/JWKS/token/decision 地址与固定本服务凭据。自定义 JSON adapter 的 operation 不是冒称 RFC8693 的标准 grant type。

所有决定均为独立 `agent-authority-decision+jwt` / `current-decision` RS256 断言，绑定本次 nonce、整个请求 canonical SHA、当前 client、target/action/resource、查询时间；active/current 必须为真正 boolean。普通票据继续 `at+jwt` / `service`、≤60秒；决定≤5秒；skew=0。验签、用途、时间、请求/主体/资源/票据 jti/version 绑定完成才产生对象。无决定缓存、旧票据回退或本地 Actor 自签。HTTP 503、坏 JSON/签名/JWKS 不返回旧成功结果；显式重新查询恢复成功才可继续。

签名 `initial_ceiling` 与 `operation.ceiling_sha256` 相互校验。初次 portal 交换既核当前结果也核初始 ceiling 不超本地 `PortalClaimMapper` 的裁剪结果；forward 还不得超过传入本次 current，operation id、来源和截止保持不变。resume 只发送持久 grant_ref 与新 call，authority 按其当前 grant、当前权限与 operation policy 创建新的 DELEGATED 操作，不收 caller 自报 source/期限/roles/attributes。普通原 PORTAL observer 仍在原来源寿命终止。测试用原 `ADMIN,EDITOR / A,B`、本地 `EDITOR / A` 证明不能恢复被裁掉的值，也覆盖“当前窄而初始宽”的隐藏上限。

`AuthorityContext` 使用普通 ThreadLocal，只在请求 scope 绑定/恢复；子线程不能继承。后台必须显式传递对象。record 本身不被宣称为防伪结构，生产业务不得反序列化构造这些可信对象；接收端仍验票和在线查询。

`WorkloadCredentialResolver.resolve()` 无用户参数，只解析配置所限定的本服务环境引用，构造时缺值即失败；本轮全部测试注入每实例独立临时凭据，没有调用真实环境秘密解析。纯 workload 票据与用户模式互斥，不映射用户 Actor/grant。approve 查询绑定真实批准主体及 approval 资源，不以发起人角色代替批准者。

`ServiceTokens.issue(Actor)` 默认/iam 拒绝。root 明确批准的窄兼容：显式 `agent.security.mode=test` 且 `spring.profiles.active` 包含 test，或显式 dev-hmac 且 `agent.dev-auth=true` 时允许旧静态签发。测试设置后 finally 还原。既有 integration 签发保留独立用途及≤300秒；普通 RS256 decoder 没增加 HMAC fallback。**静态兼容入口使用 system properties，与 Spring 配置来源的统一接线仍交 Task4**。

## 原响应能力

`ResponseAuthorityClient.open/check` 均返回固定 `ResponseLease`。response-only continuation 使用独立签名用途 `agent-response-continuation+jwt` / `response-continuation`，持有不可变原 binding、operation、ticket jti、opening decision、caller/consumer/action、source expiry 及 authority 签出的父响应关联。它不被普通 service decoder 接受。check 重核原 continuation，不能改 responseId、hardDeadline、operation 或来源期限。

PORTAL/DELEGATED 每轮写前检查，在 checkedUntil 到期时在线续核；原来源/operation/grant 到期及撤权/不可用关闭。watchdog 以 1 秒 schedule 固定检查，测试用注入 Clock/闩锁和显式 watchdog 检查，不等待长真实时间。`close()` 先原子停止再清理，幂等；不等待持有续核锁的线程，续核返回后再次检查停止位，排队 chunk 无法在关闭后继续。回调异常也不能复活 holder。

INTEGRATION 有效 open 才接收独立 `agent-response-material+jwt` / `response-completion` permit；签名、全部主体/操作/资源/响应/父响应/开流 ceiling/version/selector/截止绑定当场校验，只给此 holder 固定已验公钥与原文。每次 read/send 仍验独立签名与到期。有效期≤原 hardDeadline，SSE≤55秒、model≤本次传入 timeout 且≤610秒；这些是本轮软件边界，非生产 SLA。

进入 drain 必须已由可信调用方 `markResponseAccepted()` 记录合法 SSE 受理或实际首次 provider 发送事实。没有此状态，撤权/故障后关闭，不能借 permit 发首次业务。已有原响应的 drain 只接受匹配的 `ResponseMaterialAccess`，不含 Actor/VerifiedAuthority/ServiceTicket 转换。access 提供 owner/resource/producer/consumer 查询供后续可信 store/connector 使用；所有实际读取仍先调用 requireForResponse。SSE 验原资源版本与单调 seq；模型 final 只消费一次，冲突/重复 final 与后续 chunk 均拒绝。模型最终物理 write 完成或失败后，**调用适配器必须在 finally close holder**；该真实循环在 Task4 接线。close 后 permit/access 不可重放。

普通 `MaterialAuthorization.require(scope,current)` 到期规则未放宽；completion 只走 `requireForResponse`，并核 owner、manifest 版本/摘要、ceiling 覆盖和标签义务。`MaterialRef/MaterialScopeV1/AuthorizedMaterial/MaterialAuthorization` 是 root 允许提前实现的 Task5 **纯 common 前置**，没有 PG/store 实现，也未完成 Task5。此处 ResponseMaterial payload 检查使用 canonical JSON，artifact 原始字节和真实 blob 消费需 Task5 在其 store 边界完成相应摘要规则，不把该 JSON 响应检查冒称全部材料存储实现。

## 独立集成 policy fixture

`LoopbackAuthority` 只在 test 源目录，每实例独立 RSA issuer、portal key、独立 policy key 与临时 HMAC integration key；有限 alice/approver、A/B、六业务 client；普通 JWKS 单把、独立 policy 域单把，均低于8/2限制。最多2个 grant、64 operation、128 request 的小样本上限；不做耗尽或慢 probe。

只有配置为 gateway 且 integration.enabled 的显式集成 exchange 分支把完整原短票据交固定 authority。未验 token_use 仅用于互斥的固定 profile 选择，portal 验签失败/JWKS503不降级另一分支。fixture 实际验独立 HMAC、用途/TTL、credential/app/biz/user/policy/origin；用户/集成票据不发业务下游。客户端复核签回结果与原 subject-token 摘要、主体、集成字段、资源和来源一致。

每个 INTEGRATION grant 决定实际通过固定 HTTP `/internal/iam/integration-policy/resolve`，使用独立 RS256 `agent-policy-request+jwt` / `policy-query`、issuer/sub/caller/aud/action/iat/nbf/exp/jti/bodySha256/nonce。专用 resolver handler 只用固定 policy 公钥，不调用普通 authority；完整 schemaVersion=1 DTO（包括所有预计 tenant/app/biz/user/principal/version 字段及 role/attribute/labels/origins）在本次结果中校验，不缓存 ACTIVE。普通 authority key 签 policy assertion、错类型 active、旧版本、缺字段、错绑定、inactive、subject移出、503及实际移除 resolver route 后的404均拒绝。测试断言每次决定最多一次 resolver 调用、无 resolver→authority 回调、业务发送0；未知业务路由有实际计数 handler。

**本 fixture 的 resolver 是有限协议反例数据源，不是 PG。真实 app controller/专用 SecurityFilterChain/PG policy revision、真实 IAM 消费及 gateway 入站桥接仍属 Task4；本报告不签收该完整真实链。**

## 行为红→绿与执行记录

执行环境固定 Java21 `C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot`，仓库内 `.tools/m2`。V: 为已有仅指向本工程的映射，`V:/.local/tmp` 避免本机非 ASCII Unix-domain 临时路径问题。

主体协议测试与完整回归使用以下基础命令（最早仅 Actor 的定向红尚未加 `-o` 与 Unix-domain 参数；该用例不建 HTTP client）：

```powershell
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
mvn.cmd -o -B -ntp -pl admin-common -am test "-Dmaven.repo.local=.tools/m2" "-Djdk.net.unixdomain.tmpdir=V:/.local/tmp"
```

命名测试在该命令后附 `"-Dtest=..." "-Dsurefire.failIfNoSpecifiedTests=false"`；最后冻结轮不附 test 过滤。真实返回码与输出保留在对应日志，XML保存断言名/实际失败。表内红均是**编译可通过后的行为断言失败，零错误**：

| 行为/实际命名过滤 | 原始红日志与 XML/源码 | 红结果 | 绿证据 |
|---|---|---|---|
| `ServiceTicketProviderTest`：iam下旧Actor仍可签票 | `red-actor-java21.log`、`red-actor.xml` | 1/1 failure/0 error | `green-targeted.log`及最终3项provider XML |
| `CurrentAuthorityClientTest,ServiceTicketProviderTest,ResponseAuthorityTest`：阻塞writer撤scope仍写1 chunk；idle未停、RR1未转drain/截止未停 | `red-lifecycle.log`、`red-response-xml/`、`red-lifecycle-source/` | 19/4 failures/0 errors | `red-rr2.log`同轮response6绿；最终response13全绿 |
| `CurrentAuthorityClientTest#rr2ResumeCannotReintroduceRolesOutsideOriginalSignedGrantCeiling,ResponseAuthorityTest` | `red-rr2.log`、`red-rr2-source/` | 7/1 failure/0 error | `red-r3.log`同轮该RR2绿；最终current15绿 |
| `CurrentAuthorityClientTest#r3ResolverStringActiveCannotBeCoercedIntoPermission+rr2ResumeCannotReintroduceRolesOutsideOriginalSignedGrantCeiling`：HTTP resolver `"active":"true"`被强制转换成许可 | `red-r3.log`、`red-r3-source/` | 2/1 failure/0 error | `green-targeted.log`及最终current XML |
| `CurrentAuthorityClientTest#portalCannotHideBroaderInitialCeilingBehindNarrowCurrentRoles` | `red-initial-ceiling.log/.xml`、`red-initial-ceiling-client.java` | 1/1 failure/0 error | 最终current XML |
| `ResponseAuthorityTest#closeMarksStopBeforeWaitingForAnInFlightRenewal`：close等待续核锁 | `red-close-race.log/.xml`、`red-close-guard.java` | 1/1 failure/0 error | `green-final-races.log/.xml`（2绿）及最终response XML |
| `ResponseAuthorityTest#modelCompletionAcceptsOriginalFinalOnceAndRejectsConflictingReplay`：原final成功后冲突final仍可写 | `red-model-final.log/.xml`、`red-model-final-guard.java` | 1/1 failure/0 error | `green-final-races.log/.xml`及最终response XML |

其余明确为新增绿色矩阵，不冒充独立红：门户过期后新DELEGATED正常成功、forward同operation不延期、缺operation/policy拒绝、真实scope/role撤销、opaque version变化、grant撤销/到期、approve撤APPROVER、错误subject/resource/action、nonce/jti/caller/expiry篡改、无缓存503/坏JSON/JWKS/签名、独立policy签名域/旧版本/503/404、普通workload互斥、跨线程context缺失、permit多维绑定/跨holder/过期/重放、模型permit在普通JWKS不可用时仍只完成原响应。

早期 `red-actor.log`（PowerShell未引用点号-D参数）、`red-actor-behavior.log`（宿主默认Java17）、`red-response.log`（lambda编译错误）、`red-response-behavior.log`（Unix-domain临时路径）是环境/编译失败，**不算行为红**。这些原日志保留。

`full-common.log` 是模型final补洞前112绿的原日志，未覆盖；因随后定向红覆盖 Surefire 的 ResponseAuthorityTest XML，该非最终轮只保存其余9个XML于 `full-112-partial-xml/`，没有伪造缺失原件。`final-full-common.log`/`final-full-xml/` 是113绿的中间全轮；最后补充现有方法内的role/JWKS/expiry/404/延期期限断言后，以 **`frozen-full-common.log`/`frozen-full-xml/` 的单次完整113绿**对应本次冻结全部源代码。没有把不同轮次子集拼成完整回归。原 Task2 配置闭锁测试的预期异常堆栈仍见完整日志，不是失败；本次新增 unchecked 提示已用有类型 TypeReference 消除。

## root授权的最小接缝与实际源码对照

root在本轮消息明确批准：

1. 同一 `BoundedIamHttp` 增认证 JSON POST；保留全部既有GET/POST API、固定端点、64KiB上限、deadline/cancel、无重定向边界。凭据不会送JWKS或其他地址。
2. `TrustedJwtDecoder` 原严格 JWKS 解析原样提取为包内 static `parseKeys(byte[])`，供独立响应验签器共用；普通 decoder 信任域/用途/401和网络503语义、≤8key限制不改。
3. Task5上述纯材料类型可提前落地；不得写PG/store/业务消费点。
4. 静态dev/test Actor兼容开关按前述有限条件保留；普通decoder不增加integration HMAC。

实际读取本工程本地开源源码：

- Nimbus JOSE JWT **9.37.3** `RSASSAVerifier.verify`：验 JCA 签名，不自动提供应用用途/时效/资源约束。
- Nimbus `JWSObject.verify`：只根据 verifier 返回值置 VERIFIED；对照本实现必须独立做固定算法、用途、issuer/audience、NumericDate、nonce/绑定检查。
- Spring Security OAuth2 JOSE **6.5.0** `NimbusJwtDecoder.createJwt/validateJwt`：签名与 claim validator 分阶段，对照本实现的验签后映射纪律。
- 同时读过此前保存的 Nimbus `DefaultJWTProcessor` 验签后调用 verifyClaims 的代码段。

实际读取的前三个文件已从本工程之前保存的 source jar 解出至 `iam-stage-3/upstream/`，SHA在该目录 `sha256.json`。未联网读取新版本或声称对照未读实现；没有修改公共 `docs/open-source-references.md`（不在本轮写范围）。

## 后续门禁及具体接线疑点

`SecurityConfiguration` 第55行原 `IAM_TRUST_CHAIN_UNAVAILABLE` 闭锁源码SHA未改变；**BLOCKED_IAM_SCOPE_ACCEPTANCE 保持**。没有因common可用提前开放六服务。

Task4需明确消费以下真实边界：

- 生产 authority bean/client配置与受限 workload credential resolver 注入；静态dev/test开关与Spring配置来源统一。
- 实际app只读policy controller、独立验证链/部署公钥、PG一致快照与revision变更；不能以本fixture响应替代。
- 六服务真实method/template资源守卫、grant持久化与每个新动作的resume检查点。common首版forward要求同一确切ResourceBinding；若业务需要子资源收窄，必须给出可验证父子绑定，不能本地猜测。
- 真实writer/模型subscription/HTTP读连接的取消回调；在**实际首发后**调用 markResponseAccepted；最后final/error/timeout/cancel路径finally close。该hook不是事实数据库，也不能由请求头/用户body触发。
- 原连接上的可信manifest/私有frame或header、受限buffer/实际payload摘要与版本核验、producer/consumer连接身份；Task3只检查传入受信manifest，不提供通用接收URL或重开HTTP补查。
- Task5真实PG material_scope、与事实同事务、只读metadata后同快照取payload、父要求与标签并集、历史/SSE/下载/回放/summary材料守卫及artifact原始字节摘要，仍未闭环。

以上是明确未接线的阶段边界，当前无待root额外裁决的common接口歧义；请独立审查本次软件实现与证据后再推进后续阶段。真实外部IAM、生产策略、容量和跨服务真实循环均不以这113项有限common测试签收。
