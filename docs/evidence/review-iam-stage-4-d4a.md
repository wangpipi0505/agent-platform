# D4a 独立任务审查：有限门户会话后端

## Spec Compliance

❌ Issues found：一个 Important 规格缺口。会话专用 elapsed 验签遗漏原路径隐含的 `exp > nbf` 时间顺序，坏日期签名被分类为授权截止 403，违背补充要求的坏结构 503。见 I1。其余可见改动符合本次有限后端范围；此结论不代表生产 IAM 或整个 D/E/F/G 验收。

⚠️ 不能从本补丁独立验证的事项：

- **实际 normal legacy 装配**：`platform-gateway/src/test/java/cn/agentplatform/common/authority/PortalSessionHttpTest.java:204` 的 NormalLegacy 只导入新 controller，并排除 SecurityAutoConfiguration、UserDetailsServiceAutoConfiguration 和 ManagementWebSecurityAutoConfiguration；`:208` 证明 Optional client 可缺省实例化，不等价于真实应用完整 legacy 启动。root 已通过具名装配只读核验及有限实例化关闭 brief 的“新 controller 不强依赖 IAM client”窄要求：实际 GatewayApplication 扫描 gateway/common，CurrentAuthorityClient 无自动工厂，GatewayIamAssembly 无自动配置/import，原 SecurityConfiguration 闭锁及 POM/config 未变；root 没有执行一次新的完整实际 GatewayApplication legacy 启动。本 reviewer 未自行读取这些补丁外文件，保留完整启动覆盖限制。新 controller 的 Optional 构造本身见 `platform-gateway/src/main/java/cn/agentplatform/gateway/api/PortalSessionController.java:14`，没有发现其新增强制 IAM 依赖。
- **全局闭锁和未改实现**：`platform-gateway/src/main/java/cn/agentplatform/gateway/infra/GatewayIamAssembly.java:26` 仍为无组件 stereotype 的显式导入类。生产 `IAM_TRUST_CHAIN_UNAVAILABLE`、TrustedJwtDecoder 完整严格输入语义、既有 Json ISO 序列化设置、published-run 独立 metadata/main 与写出复核、Java21/Boot3.5.0/Security6.5/Nimbus9.37.3 和 POM/SQL/其他服务保护不在本补丁完整证明范围；root 应使用已有物理锚点与 source delta 关闭这些项目，不能用此审查代替旧 B2 全面复审。
- **交接事实**：source 377→380、target 1888→1931、overlay/seal/replay、20 条真实命令及 Running XML、各阶段源码适用性、资源关闭和历史保护由 root 完成独立有限证据审计并回报 PASS：`.local/iam-d4a-root-check.json`，6175 B，SHA256 `75cdaf666272495705314539e73a0c0685f3f07824658427229ee58dc53cfc3b`；root 回报 2982 distinct、380/1931 当前项、11 scope/75 overlay/594 own、20 commands/21 actual XML/24 latest valid、28 runner/8 latest index/9 contracts/13 anchors/design 和 3 upstream copies+3 originals，0 异常，未重跑行为测试/历史全扫描。此处为具名 root 回报，本 reviewer 未独立重做该审计。本审查验证了报告/handoff/package 的输入哈希并评估可见测试源码，没有重复扫描/重放或宣称自己核过所有日志。作者所述 24 个唯一 case 是多阶段最后适用结果，不是一次最终同源 24-case suite（`docs/evidence/iam-stage-4-d4a-portal-session-report.md:55`、`:77`）。

## Strengths

- 固定能力与不可公开铸造证明：`admin-common/src/main/java/cn/agentplatform/common/authority/CurrentAuthorityClient.java:7` 默认明确 503；`HttpCurrentAuthorityClient.java:19` 的 final proof 只有私有构造器、只读 SessionView、owner 与截止预算，不保存原门户 token 字段。公开 DTO 或 `RetainedAuthorization.currentOnly` 不能进入成功 session proof 路径（同文件 `:87`、`:253`）。测试 `PortalSessionAuthorizationTest.java:34`、`:105` 对默认能力、私有构造及一次消费有实际断言。
- 来源资源和真实链路：`admin-common/src/main/java/cn/agentplatform/common/authority/HttpCurrentAuthorityClient.java:65` 至 `:105` 从真实 decoder 结果与 mapped ceiling 派生两个固定摘要，iat/exp 使用 long 秒；调用固定 gateway self-call，完成 exchange、同 ticket authorize 及 retained initial proof，并比较主体、resource/call、operation、grant/真实 grant expiry、origin、initial ceiling、ticket jti/expiry。角色只收窄且允许空交集；UI expiresAt 仅取 portal/grant 较早者。`PortalSessionAuthorizationTest.java:19`、`:41`、`:59` 包含四字段、空角色和九类签名语义变更拒绝。
- 最新 authorize 窗口与首写：`HttpCurrentAuthorityClient.java:228` 在 ticket decoder 后再次核 exchange；`:234` 在最新 authorize query 前捕获 Clock/nano；`:105` 与 `:38` 共同约束来源、ticket、operation、grant、最新决定及 maxDecisionSeconds，记住失效并绑定 owner close/一次 release。旧 exchange 不陪同整个响应。`PortalSessionController.java:25` 至 `:30` 先完成 JSON/UTF8、取得真实 stream，然后 consume proof 并首次 write；同步 void handler 没有另一个 MVC 成功返回值路径。`PortalSessionAuthorizationTest.java:79`、`:91`、`:115`、`:149` 与 `PortalSessionHttpTest.java:148` 对交接、回退、失效和真实首写窗口有正负控制；不扩大为物理慢客户端撤回保证。
- 有限安装没有增加第三条 ANY：`admin-common/src/main/resources/agent-service-authority.json:2` 只增加精确 GET/gateway→gateway/portal.session.read/delegated/integrationAllowed=false；`GatewayIngressBoundary.java:42` 保留原构造语义，`:47`、`:205` 固定真实 session 类/declared method 与 purpose claim，`:256`、`:263` 拒绝混入头/body/Cookie；`RouteCoverageVerifier.java:98`、`:141`、`:250` 遍历所有 mappings 并核唯一精确 session。原 outer FilterChainProxy/servlet 实际注册证明仍在 `GatewayIngressBoundary.java:374`、`:398` 和 `GatewayIamAssembly.java:56`、`:65`。
- fixture 没有把 catalog route 当资源证明：`admin-common/src/test/java/cn/agentplatform/common/authority/LoopbackAuthority.java:187`、`:195` 限定新 action 的 caller/target/操作和真实 verifySubject 资源；`:223` authorize 对照存储 grant 的 source/origin/originalCall/来源资源；`:205`、`:209` 正确限制新 operation 到 source/grant 较早值；`:325` 的角色变化只适用于新 action。
- HTTP 测试可信边界明确：`platform-gateway/src/test/java/cn/agentplatform/common/authority/PortalSessionHttpTest.java:32` 装配实际 Tomcat、真实 handler、安全链及签名 HTTP authority；`:83` 验一次 exchange+authorize，`:96` 对真正到达服务器的空 query/Proxy-Authorization 使用 raw Socket，`:181` 检测替换/secondary handler 和错误/重复注册，`:223` 对实际端口和同 owner 关闭有断言。没有需要 PG/模型业务进程的依赖；business counter 是 fixture 端点观测，不能单凭它外推所有外部系统均无请求。

## Issues

### Critical（Must Fix）

无。未发现本补丁能由该日期缺口产生成功会话证明或释放成功字节的路径。

### Important（Should Fix）

**I1 — elapsedSession 丢失 `exp > nbf` 的日期顺序，签名正确但日期形状无效的载荷被误判为授权过期。**

位置：`admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityResponseVerifier.java:60`；下游 `admin-common/src/main/java/cn/agentplatform/common/authority/HttpCurrentAuthorityClient.java:368`、`:376`，ticket 对应 `:322`、`:339`。

旧逻辑通过 `nbf <= now < exp` 隐式保证 `nbf < exp`。新分支允许 elapsed exp，但只保留 `nbf >= iat`、`exp > iat`、非未来 iat/nbf 和 maxTTL，没有独立拒绝 `nbf >= exp`。例如请求开始 T，签名 claims 为 iat=T、exp=T+1、nbf=T+2，在传输/验签期间 Clock 到 T+3；签名、请求摘要、nonce、client/call、requestedAt 和 active/current 都可正确，verifySession 会返回此不存在有效时间窗口的 claims，query 随后返回 403。已签 ticket 同样可通过此日期层再被归 403。

影响：它仍 fail closed，不是授权逃逸；但不能把这种结构无效的上游证据当可信授权截止，违反 `docs/evidence/iam-stage-4-d4a-verifier-scope-addendum.md` 对完整时间顺序及坏结构 503 的明确要求，也使该文件关于“不放宽日期”的说明不成立。当前 `admin-common/src/test/java/cn/agentplatform/common/authority/PortalSessionAuthorizationTest.java:167` 仅覆盖未来 iat/浮点 exp，未覆盖这个新分支的顺序缺口。

修复建议：在 session 私有验证路径恢复独立 `exp > nbf` 检查，保持普通 verify/pinned 原默认语义；仅新增已签 current/ticket 的 `nbf == exp`、`nbf > exp` 有限失败先行案例，预期 503 且零成功 proof/字节。保留合法 elapsed 的 403 控制和普通默认 expired 503 控制。无需重跑历史全套；本 reviewer 没有执行测试。

### Minor（Nice to Have）

**M1 — 已报告的编译/日志噪声需按来源保留归档与限定，而不是声称 pristine。**

证据：`docs/evidence/iam-stage-4-d4a-portal-session-report.md:75` 明示 unchecked、既有 PG 测试 deprecation、预期非法安装 ERROR 栈及 runner 原编码乱码。新测试 `admin-common/src/test/java/cn/agentplatform/common/authority/PortalSessionAuthorizationTest.java:69` 的泛型强转与 unchecked 提示一致。此项不证明行为失败：预期安装拒绝为正当负例证据，编译 PG 测试不等于运行 PG，既有 deprecation/编码不升级为本任务阻断。root 应在现有日志审计中定位实际提示与来源；保留原件，不 suppression、不为清日志重新运行套件。

## Assessment

**Task quality: Needs fixes.**

私有证明、单次输出边界、精确安装和真实 HTTP oracle 做得较完整，新增规模为一个 39 行 controller、187 行 common 测试、242 行 gateway 测试及既有类的窄改动，没有引入通用响应框架。I1 是本次新 elapsed 能力的确定性日期校验遗漏，须修复后针对性复审；root 已回报有限封存/执行证据审计及新强依赖风险核验通过；上述 ⚠ 仍准确限定本 reviewer 的直接读取范围和未执行的完整实际应用启动。

## 实际读取、输入身份和覆盖限制

- 先完整读取 reviewer prompt：`D:/00-Work/02-Application/01-CodeIDE/UserData/Codex/DotCodex/skills/subagent-driven-development/task-reviewer-prompt.md`。按该提示作一次 task-scoped spec+quality review，没有子代理。
- 完整读至 EOF：brief 13289 B，SHA256 `f5d531d35b7cf33e76d0cfb6b8c600ab08a52030f17f2eac1172d05839aa8f93`；addendum 3154 B，SHA256 `4b3eac5486c7c738960d5f5106ef199e1504d6d1b591c349498c4a596439ec4d`；author report 17456 B，SHA256 `1eb92dafc443d004295cfe6660cec24c6e7923bce96b5728a3a2c341d7e8600b`；handoff.json 3496 B，SHA256 `c95a2cd55e73f511273a771026665dfc9c8266e87e51714cf1e43020b6e2fcc0`。本 reviewer 实际核了这四个哈希。
- 唯一 diff `docs/evidence/review-iam-stage-4-d4a-package.diff`，259796 B，SHA256 `56d0a6f7a9f9b0d28beecb79058d421a1d1d103b6683bde1434d9f5f56928df0`，本 reviewer 已核。包声明基线 source manifest `6caf11a109ae9812af0d6994cfc11761923c40ecd943dfa793a9b900ea6bbba6` → `7e1a1b53ae7b494361bf75b36d2878a1f4dbb1e5d0631c3311643a8e34822708`，无 HEAD/commit 基线。
- 一次完整内容读取为包行 1–3780 连续 140 行块，3781–3900、3901–4020、4021–4140 各 120 行，4141–4288 最后 148 行；共 4288 个逻辑行（换行 split 4289）。每块完整输出无截断。随后只程序化生成已读包行→新文件行的引用索引，没有重新展示/读取改变源文件内容。
- 11 文件完整上下文：PortalSessionAuthorizationTest 1–187；PortalSessionController 1–39；PortalSessionHttpTest 1–242；AuthorityResponseVerifier 1–68；CurrentAuthorityClient 1–25；GatewayIngressBoundary 1–412；HttpCurrentAuthorityClient 1–435；RouteCoverageVerifier 1–263；agent-service-authority.json 1–2018；LoopbackAuthority 1–449；GatewayIamAssembly 1–82。不是只读新增行。
- 未读补丁外业务源码/上游归档，没有 broader callsite crawl；没有重读改变源文件。开源范围的作者声明只据报告，不声称 reviewer 阅读过 Spring/Tomcat 原件。未执行 Maven、HTTP、浏览器、探针、测试循环、git 命令或旧证据全量扫描；20 阶段/24 case 执行事实据上述具名 root 审计回报，不冒称 reviewer 自行读取了全部执行原件。
- 唯一写入 `docs/evidence/review-iam-stage-4-d4a.md`。未修改 source、target、git/index/HEAD、作者证据或旧报告。报告写入结束后全部审查写入所有权归还 root。