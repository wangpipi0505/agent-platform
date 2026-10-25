# E1a common retained authorization（2026-10-20）

DONE_WITH_CONCERNS；source/target 已归还 root，待新独立任务复审。完整 common 回归 174 / 0 failures / 0 errors / 0 skipped；最终源码、target、保护清单已核，完整 patch 已实际应用且 346 项逐字相同，见 final-verification.json。范围仅 E1a，独立复审尚未执行；E1b、真实 PG/RunAuthority/材料 scope/driver/provider/RR2 与生产 IAM startup 闭锁均保持原边界。

## 保护差异预登记

首次冻结发现唯一新增保护文件 docs/evidence/iam-stage-5-e1b-persistence-seam-proposal.md，before=null，SHA256=070fcb0689a6b813dc790c5d214c4303c3e6264f5ec239c9694a4c1f1d5ed66d。已向 root 报告并等待逐文件归属/结束写入确认；未豁免 docs 或 evidence 目录。原有 22786 个保护文件没有内容变化。首次 freeze 由于尚无 root-protection-disposition.json 返回 ENOENT exit1，真实差异及失败单列保存；这不是测试失败，不改变任何源文件或已通过结果。

随后 root 确认该文件是其显式授权的独立只读 E1b 准备，先说明仍在修订、不得认可中间摘要；最终确认已全文阅读、独立核验和结束写入，最终 SHA256=6e22271f28828e29062428f684a7ce43b177e2f5191e6b829956117d55d425cc、35083 bytes。仅这一新增文件写入 root-protection-disposition.json；initial manifest、first-freeze-protected-*、首次退出保留，不豁免目录，不修改既有 architecture proposal 或任何旧报告。

## 基线、范围及实现

唯一工作根 G:/207/项目/算法重组工具/代码/agent-platform。正式 brief SHA256=f4f38ec26e91524cbf72d3cb4f984c161c24f55238e78e5f72c13c3d6c8cdaf3 开工时匹配。先读 brief、AGENTS.md、contracts/README.md、implementation-brief、E1 proposal 最终第10节及当前实际 common 实现。使用 TDD、verification-before-completion、ponytail；出现聚焦测试失败后按 systematic-debugging 核对实际栈、输入与路径。

source-before 344 项与 iam-stage-4-d3a0-deadline-fix/source-after.json 及其 frozen 字节完全一致。最终 346 项，仅下列 8 个文件变化：

- 生产：CurrentAuthorityClient.java、HttpCurrentAuthorityClient.java、AuthorityContext.java、ServiceAuthorization.java，均在 admin-common/src/main/java/cn/agentplatform/common/authority/。
- 测试：LoopbackAuthority.java、ServiceAuthorizationTest.java，新增 RetainedAuthorizationTest.java、AuthorityContextRetainedTest.java，均在 admin-common/src/test/java/cn/agentplatform/common/authority/。独立 frame 测试类专测本线程最顶 frame、捕获对象身份、失败终止、Clock 回退、非 LIFO、线程隔离，避免将生命周期失败藏在 HTTP 403 中；实际 servlet/HTTP/async 保留在 ServiceAuthorizationTest。

CurrentAuthorityClient 原七方法及 VerifiedAuthority 九字段、OperationBinding 形状不变。仅新增 default authorizeRetained，旧实现调用其原 authorize 并返回 currentOnly。Http override 调一次 decoder、一次 authorize current query，原 authorize 只投影同路径。嵌套 public static final RetainedAuthorization 无公共构造器、无 setter/withInitial/Jackson creator；私存同次 VA、完整初始 Actor（再经 Actor 深拷贝）、origin、ticket jti/expiry、已经核过的 nonce/request digest 及同 client Clock。无参 requireVerified 检查 current/operation/grant 及 ticket 截止，getter 拒绝无 proof；toString 不输出主体、token、origin 或决策 ID。

接收的同一已验 service JWT 与同次 signed current decision 均严格核完整 initial/hash，且全值相等；保留原 caller/call/grant/version/operation/ticket_jti 及 nonce/request digest 检查，并核 grant expiry 相等。initial 必须覆盖两侧 current；dataLabels 是只增不减的义务。不存在补取 ceiling 的第二次查询。在线查询结束时再次核 ticket expiry，避免 decode 成功后查询期间到期仍输出 mapping。顶层 origin 两侧均缺时为 currentOnly；单侧缺、空/空白、非字符串、超过128或不同值均直接拒绝。

ServiceAuthorization private requireRetained 保留原 route/mode/caller/current/integration origin gate，public require 返回 .current。preHandle 绑定 retained 与同 call refresh。workload 与 POLICY_MACHINE 既有路径不生成用户 frame。AuthorityContext 单一值为 mapping，public bind(VA) 保持 currentOnly，bindRetained 仅包可见；requireFor/requireInitialFor 共享刷新。先 invalidated=true，核本次 caller/call/grant/operation/version、范围不扩大、initial/origin 不变后才替换对象并恢复可用。带 proof 降级、403、503、过期或绑定失败均永久失效；current() 遇到 proof 到期亦退休 frame。requireBound 不联网，只认本线程最顶活 frame 的同一对象身份及私存 Clock 检查；旧捕获实例失败会退休当前相关 frame。原非 LIFO closed 祖先跳过和 servlet afterCompletion/afterConcurrentHandlingStarted 清理继续保留。

有限 LoopbackAuthority 复用原 Grant store，增加 originAuthenticationId 分量，值为 oa1: + digest(List.of("origin-authentication.v1", verified issuer, verified jti))，长度68。PORTAL 固定其 portal issuer/aud/RS256/at+jwt、client 与有界 jti/时间；旧 integration 固定既有 issuer()，external D2 固定 ServiceTokens.ISSUER。不根据传入 iss 动态建信任域。query/main 两次使用同一 source 字符串，独立 Grant/root/operation 保留同 origin；forward/resume/approver 投影继承它。

首次严格验过的 source claims 保存在既有 Grant 的 details 中；同 issuer+jti 重 exchange 比较原字段、主体、Actor 集合与剩余声明（忽略 object 键顺序及 fixture 自加诊断 source_token_sha256）。保留原 external D2 sourceIntegrationActor、policyVersion、initial、逐项绑定、labels 并集逻辑；旧认证不会因 JWT 文本/签名表示变化或新 Grant 重置 policy/ceiling。无新生产 IAM、身份缓存、依赖或业务模块。

测试 fixture 原 public 方法签名全部核对保留，详情见 test-audit.json，包含 D3 所需 testClientSecret/clock/advanceClock/portalActor 及 externalPolicy/clearFiniteGrants/narrowAdminAtResourceCheck 等。仅显式 portalActor fixture 配置允许其声明的属性集合，默认未设置时维持原 EDITOR/region A/restricted 配置。旧 signedOldAdmin 用例原来直接将签名票据角色扩大到 initial 之外，在新双侧检查下应当拒绝；现将原 source/initial 真实设为 ADMIN+EDITOR/AB，signed current 收窄至 EDITOR/A，保留 APPROVER 403、EDITOR 200 和实际返回仅 EDITOR/A 的原行为断言，无生产放宽。

## 真实测试顺序与证据

每轮均通过原受控 .tools/run-iam-module-tests.mjs，只注入独立测试凭据；未读取或输出 .env、vault、私钥。命令、原始 log、实际运行类 XML/TXT、当轮完整源副本及 source-sha.json 紧随该轮归档在同名目录；不是从最后 target 冒充前轮结果。全部六轮可编译，无编译/setup 失败。test-audit.json 从这些归档 XML 聚合，不将轮次结果相加。

| stage 后缀 | 实际指定 tests | Tests/Failures/Errors/Skipped | 退出 |
| --- | --- | --- | --- |
| red-01 | RetainedAuthorizationTest | 4/4/0/0 | 1 |
| targeted-01 | RetainedAuthorizationTest,AuthorityContextRetainedTest,ServiceAuthorizationTest,CurrentAuthorityClientTest | 50/3/0/0 | 1 |
| targeted-02 | 同上四类 | 50/0/0/0 | 0 |
| red-02 | RetainedAuthorizationTest#ticketExpiringDuringTheSingleCurrentQueryCannotProduceAUsableMapping | 1/1/0/0 | 1 |
| targeted-03 | 同上精确方法 | 1/0/0/0 | 0 |
| full-01 | * | 174/0/0/0 | 0 |

命令均为 `node .tools/run-iam-module-tests.mjs iam-stage-5-e1a-<stage> admin-common <tests>`；PowerShell 最后一轮 tests 参数实际为 `'*'`。首次行为红在任何生产编辑之前：收到已签票据 initial 与 operation hash 不同仍通过、单侧 origin 被忽略、同 PORTAL issuer+jti 改角色再创建 Grant、旧 integration 非固定 issuer 被接受，均是 “Expecting code to raise a throwable”，没有缺 API/未编译冒充红。新增 API 的完整正负矩阵及 frame 测试随后加入；未声称每个新测试均在原不存在 API 的基线上单独跑红。自审的查询期间票据到期测试另获真实1/1行为红，再最小加截止检查获1/0绿。

targeted-01 的 3 个断言失败如实保留：旧 ADMIN 测试签名 initial 不一致；新 fixture 时间测试错把合法过去 iat/nbf 当非法；新 async 测试误算2次查询，实际为初始 preHandle + 显式业务刷新 + 完成 redispatch preHandle 共3次。依据实际路径修正测试输入/预期，未删负例或放宽生产验签。targeted-02 50项全绿后，仅因新增到期边界执行 red-02/targeted-03，再执行一次完整 common。未重复无新原因的旧绿轮、七 reactor、package、任何业务服务/PG联合或主进程。

新测试职责及有限验收：

- RetainedAuthorizationTest（最终13方法）：AB initial/A current、hash/labels/深不可变、恰好一次 current query、旧 authorize 投影、default/currentOnly/无公开 mint/Jackson 拒绝；双缺 origin 兼容及两侧所有错值；signed ticket/current 各 initial/subject/tenant/grant/grant expiry/version/root/operation/hash/labels，decision ticket_jti/caller/nonce/request digest/签名错误拒绝；PORTAL/INTEGRATION 同源不同 Grant、fresh jti、新 forward/resume；同源重编码、旧 policyVersion、原绑定/期限修改；固定 issuer/aud/jti/类型/时间；ticket/current/operation/grant 最早截止和查询期间到期。
- AuthorityContextRetainedTest（5方法）：一次刷新替换与 decisionId 更新，initial/origin 恒定，旧对象/错线程/错请求拒绝；403/503/过期/错 root/更宽 actor/origin/initial/降级/current 反扩大后清除故障仍不能复活；currentOnly 和旧 public bind 拒绝初始能力，双缺 origin 的普通 requireFor 仍兼容；本地 requireBound/current 过期及 Clock 回退；合法嵌套、非 LIFO、普通线程隔离。
- ServiceAuthorizationTest（最终14方法，本次新增1、最小迁移1）：真实 HTTP 同步请求 preHandle+显式 requireInitialFor 共2次；真实 async 总3次，afterConcurrentHandlingStarted/afterCompletion 后无 ambient frame；保留原 workload、route、integration origin、请求故障、并发隔离等测试。
- CurrentAuthorityClientTest（19方法）原样参加聚焦和完整回归。完整 common 实际17类名单与174个测试条目见 test-audit.json；包含 AuthorityConfigurationTest 的生产闭锁负例、ResponseAuthorityTest 原 holder/lease、TrustedJwtDecoderTest 等。没有将这些结果与历史962或其它阶段累计。

有限 resume 用例生成真实新 DELEGATED 签名票据及一次 current mapping，保持 origin/initial，旧 observer check 仍失败；这只验证 common fixture 与已有 ResponseLease 机制，不签收真实 provider、PG持久Grant、RunAuthority 或 RR2。

## 实际读源与实现对照

本次实际读取仓内固定来源副本，未改旧证据。Spring Security 6.5.0（Apache-2.0）NimbusJwtDecoder 的 decode/createJwt/validateJwt：签名处理与后续 claim validator 是两个步骤，不能裸解 JSON 当证据；JwtTimestampValidator 全文：Clock 注入与 expiry/nbf 检查。Nimbus JOSE JWT 9.37.3 JWSObject.verify 及其文档：只验证 JWS 签名，profile/subject/expiry 要另核。E1a 复用当前 TrustedJwtDecoder、AuthorityResponseVerifier 和 AuthorityProtocol，只在已验证同串签名内取原 JSON 类型进行严格映射，并私存同一 Clock。

文件实际 SHA 见 upstream-read-sha256.json；前两版本来源由现有固定 POM及 iam-stage-2/upstream-sha256.json、iam-stage-3/upstream/sha256.json 对照，源包地址分别为 https://repo.maven.apache.org/maven2/org/springframework/security/spring-security-oauth2-jose/6.5.0/spring-security-oauth2-jose-6.5.0-sources.jar 与 https://repo.maven.apache.org/maven2/com/nimbusds/nimbus-jose-jwt/9.37.3/nimbus-jose-jwt-9.37.3-sources.jar。本次读的是具体实现片段/Clock全文，不声称完整上游审计或本次重新下载。

## 冻结、patch 与交付边界

source-after 与 full-01 当轮源码 SHA 完全一致。baseline 原344文件，frozen 最终346文件（两新增测试），完整上下文 binary/no-index patch SHA256=df23480232120b22d05f2c30b37cbdc9965f10bc94e56bb570caae5e5265d3f6。patch-replay.mjs 在正确 parent Git ceiling 下核 `rev-parse` exit128，再真实 `git diff --no-index --binary --unified=100000` exit1，真实 `git apply --binary` exit0；apply.log 无 whitespace 诊断，replay-check.json 全部346项与 frozen/live 字节一致。无 HEAD/remote/commit，未造提交、空 diff 或伪称 Git 基线。

target-before 1633项、target-after 1648项，62项新增/内容变化全部仅 admin-common/target；common 的335个最终 target 文件全部保存 frozen-target，其他6模块 target 摘要不变。源码/POM之外未改配置/YML/迁移/前端/契约/六份执行索引/旧报告/architecture提案。所有最终摘要与交还标记由 final-verification.json 列出。

失败/警告披露：六轮 Maven raw 均无 WARNING；full-01 有39行 ERROR级启动输出，来自 AuthorityConfigurationTest 已断言的预期闭锁/非法配置启动失败，XML174/0与 BUILD SUCCESS 另行保留，不写成“无错误日志”。六轮分别产生一个 Surefire dumpstream，内容均为 Boot Manifest-JAR 绝对classpath、'other' has different root 和 argLine 提示，全部原文/摘要在 test-audit.json 与 frozen-target；未改POM/参数掩盖。另有首次 freeze 的 ENOENT 保护裁决缺失，已保留并按唯一新增文稿恢复；没有编译或 fixture setup 失败。首次环境 Git 探测无 HEAD，后续正式 patch 全在 parent ceiling 下；未访问相邻项目源码/凭据或进程。

自审关注与后续：完整 API/生命周期矩阵是在首四项行为红后编写，并非各自都取得原API基线红；此测试顺序如实披露。外部D2真实 app policy PG联合本轮未重跑，新增同源/旧policy测试是现有限 integration resolver；原external分支约束保留，但不能把本轮 common 结果冒充 D2 PG联合。proof 从 frame 脱离仍可读取未过期的已验数据，真正持久写入必须由未来事务显式 requireBound；本轮没有实现或验收这笔事务。生产 IAM 闭锁、E1b同事务/历史scope、driver/outbound/scheduler、材料生产消费/下载/stream、真实 provider/RR2、D3/E联合均等待后续。独立复审需审最终冻结与patch后才能签收；本报告不是独立审查批准。
