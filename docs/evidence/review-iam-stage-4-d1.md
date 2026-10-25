### Spec Compliance

- ✅ **Spec compliant / Approved（仅 D1）**。公共接收链按已验 caller、本机 target、实际 HTTP method/template、action、mode 精确取目录行；机器与 delegated 分开，disabled 行不匹配可用 mode：`admin-common/src/main/java/cn/agentplatform/common/authority/ServiceAuthorization.java:58`、`:72`、`:83`。本结论不签收 Task4 后续阶段或生产 IAM。
- ✅ 在线 Actor 投影、实际解析资源再核验和刷新失败后旧帧不可读符合本次要求：`admin-common/src/main/java/cn/agentplatform/common/Actor.java:31`、`admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityContext.java:25`。Actor 四字段及原 requireRole 规则保留；原 close 的 owner-thread、非 LIFO 跳过已关闭祖先逻辑保留于 `AuthorityContext.java:43`。
- ✅ 运行时逐 method/template/别名检查位于 `SmartInitializingSingleton`，普通及 disabled 能力还必须具备全路径接收守卫；精确 error 例外没有扩展成框架/路径通配豁免：`admin-common/src/main/java/cn/agentplatform/common/authority/RouteCoverageVerifier.java:30`、`:43`、`:56`。真实启动反例在 `admin-common/src/test/java/cn/agentplatform/common/authority/RouteCoverageVerifierTest.java:42`、`:58`、`:62`、`:93` 验证 webServer.start=0；多 mapping 和局部守卫反例见同文件 `:114`、`:131`、`:135`。
- ✅ 明确部署模式替代 decoder 类型判定；完整生产 iam 闭锁保留，未增加 ready 开关：`admin-common/src/main/java/cn/agentplatform/common/SecurityConfiguration.java:55`、`:105`、`:208`。组合 decoder 回归见 `admin-common/src/test/java/cn/agentplatform/common/authority/TrustedJwtDecoderTest.java:220`、`:229`。
- ⚠️ **X1 / D3 gateway 接入未完成**：`RouteCoverageVerifier.java:14` 的 Ingress 不接受 ANY 或含星号模板；`:40` 的登记仅豁免覆盖检查，不提供独立信任链，也不会让 `ServiceAuthorization.java:83` 接受 portal/integration 直连。作者记录的 gateway 两个 ANY 代理因此不能直接复用该组件装配（`docs/evidence/iam-stage-4-d1-report.md:168`）。D1 brief 明确把真实 gateway/session/resolver 接入放在后续，故不以该缺口否决本次公共接收组件；**D3 接入前必须**提供绑定真实 HandlerMethod、明确专用验证链的受限代理登记并证明合法入口可用、非授权代理为零，不能简单开放 ANY 或豁免整个 `/admin/**`、`/v1/**`。本次未读取 gateway 源码，真实 handler 清单由 root 在 D3 核对。
- ⚠️ **X2 / D2、D3、D4、E 与联合验收**：真实 PG owner/version、父子资源关系、六服务 payload 读写点、出站迁移、门户、持久 grant/材料及 permit/lease 消费不在此 diff。`ServiceAuthorization.java:80` 只提供 exact resource 复核；fixture 在 `ServiceAuthorizationTest.java:36`、`:64` 使用固定域 metadata，不能替代真实 PG 验收。静态 ServiceTokens 与生产装配一致性也留给 D3。
- ⚠️ **X3 / 全局冻结与发布门禁**：九文件包没有改 POM/契约/六业务模块；`SecurityConfiguration.java:55` 仍拒绝 iam。全工程保护清单、设计 v1.2 指定 SHA、生产 `BLOCKED_IAM_SCOPE_ACCEPTANCE`、T01、原启动闭锁测试的完整保存和依赖锁定依赖 root 已执行的独立移交核验，本次不把未在 diff 中的文件宣称为重新审过；没有生产 IAM 签收仍不得改为 `IAM_SCOPE_ACCEPTED`。

- ⚠️ **X4 / D2 POLICY_MACHINE 与全局 service guard 的混合装配接缝未完成**：`ServiceAuthorization.java:37`–`:49` 只对已绑定的精确 health 提前返回；其它请求均读取 service JWT 的 action/resource 并进入 delegated/workload 校验。`RouteCoverageVerifier.java:40` 的 Ingress 只跳过覆盖检查；`:47` 对普通能力要求全路径、无 exclude 的同机守卫。因此，同一 mapping 中仅新增 POLICY_MACHINE 专用 FilterChain 和精确 Ingress 登记，仍会被全局 service guard 拒绝；此缺口不局限于 gateway ANY。D2 必须把专用入口的已验证信任域、实际 HandlerMethod 和接收守卫明确衔接，或采用有实际隔离证据的分发范围；必要时扩展 common 接缝，不能用任意请求头、路径 allow-all 或 ready 布尔跳过。必须增加同进程正常服务能力仍受守卫、合法 resolver 进入一次、伪造/错误域/直接绕过为零及启动覆盖反例。D1 brief 的安全要求第5项明确把 POLICY_MACHINE 专用入站登记放在后续；当前未交付真实 resolver 且生产闭锁，所以独立判断为后续接入的明确必办项，不否决当前下游公共接收组件。**当前 API 不具备仅靠配置即可完成混合装配的能力，不应宣称已有该能力。** 此判断完全依据已读包，未再次外查或跑测试。
### Strengths

- `ServiceAuthorization.java:69` 检查在线可信 operation 的 originKind，因此 DELEGATED 恢复不能洗掉 INTEGRATION 来源限制。`ServiceAuthorizationTest.java:125` 对真实 v1 正例和两个 admin 别名负例逐个计数，覆盖了同 action 的关键边界。
- `AuthorityContext.java:31` 在本地比较和网络刷新之前先失效请求帧；`:37` 继续绑定 caller/call/delegation/operation/version 并限制 Actor 收窄。`ServiceAuthorizationTest.java:166`、`:274`、`:322` 覆盖撤权、过期、503、业务捕获失败后读取旧 Actor，以及实签当前决定篡改原绑定。
- `ServiceAuthorizationTest.java:110` 让实签旧 JWT 的 ADMIN 与在线 EDITOR 真实进入 HTTP controller 的业务角色检查；负例不依赖资源复核掩盖投影问题。`:138`、`:250`、`:306` 分别覆盖身份/能力拒绝、两种 workload 与错误 mode、精确健康入口。
- `ServiceAuthorization.java:37` 防止重入覆盖清理句柄，`:103`、`:104` 同时覆盖正常/异常完成与异步交接；`ServiceAuthorizationTest.java:203`、`:356` 有有限异常、并发与重入断言。`:398` 的第二 mapping 使用相同真实 HTTP 当前决定链，补足仅主 MVC mapping 的证明缺口。
- `RouteCoverageVerifierTest.java:87` 对新增预期启动失败日志做窄捕获并断言 throwable，保留真实拒绝和 start=0 证据，未改变生产异常行为。

### Issues

#### Critical (Must Fix)

- 无。本次未发现 D1 范围内可据代码确认的 Critical 缺陷。

#### Important (Should Fix)

- 无。本次未发现必须先修复才能信任 D1 交付的 Important 缺陷；X1–X4 必须由 root 在各自阶段继续跟踪。

#### Minor (Nice to Have)

- **M1 / 补丁空白噪声** — `docs/evidence/iam-stage-4-d1/freeze-commands.json:70` 的实际 git apply stderr 有 1499 行 whitespace errors。该 CRLF 差异还使已有文件在包中近乎整段删除/新增，增加审查噪声；原字节 replay 相同不表示输出干净。后续正常源码编辑时统一既定换行策略并重新生成/冻结相应补丁即可；不要为消除此提示改写本次已冻结历史证据。此项不影响已确认的 Java 行为。
- **M2 / ⚠️ 既有 M1 测试日志维护债务** — `docs/evidence/iam-stage-4-d1/final-common.log:37` 起仍有大量预期启动失败 ERROR/堆栈；同日志 `:5676` 为 145/0/0/0，`:5679` 为 BUILD SUCCESS。它们不是此次测试失败，但降低未来故障可辨识度。`docs/evidence/iam-stage-4-d1-report.md:132` 明确归属于未修改的 AuthorityConfigurationTest；本次不把既有问题升级为 D1 阻断。后续触及该测试时采用窄捕获并断言原因，保留所有历史原始日志。

### Checks and review boundary

- 核对唯一审查包 SHA-256：`fe4d1ba98bc1ac78bfcd50a3b75186d5eb94364a6d6c23ebf4a8674f6fecf61a`。按包顺序读九文件完整上下文；一次工具输出截断的 PortalClaimMapperTest 片段做了补读，其余未重读完整函数。报告行号从 diff 的新文件侧定位。没有运行 git、Maven、suite 或 probe。
- 唯一一次具名外部代码检查：**风险为 D1 将初次决定绑定和“不可扩大 Actor”委托给 C 后，是否遗漏原 grant/operation/ceiling 或 disabled 能力约束**。只检查 `HttpCurrentAuthorityClient.java:63`、`:78`、`:119`、`:137`，`AuthorityProtocol.java:47`、`:54`，`ServiceRoutePolicy.java:19` 的相关局部；可见原 ticket_jti/caller/grant/version/operation、nonce/request hash、期限和 Actor 边界复用，以及 disabled 与 enabled 互斥。无需新测试即可回答该疑点；不重审 C 全协议。
- 只读原 final-common 尾和日志索引，确认保存的实际最终轮为 145 tests / 0 failures / 0 errors / 0 skipped，完成于 2026-10-20 14:40:20；重放与保护清单采用 root 已核验的移交事实，没有把它们宣称为本 reviewer 重新运行。
- 唯一写入为本报告。未改源码、target、原证据、index/HEAD，未启停服务、访问真实环境或凭据。**source/target 明确归还 root；本 reviewer 从未取得其写锁或写入它们。**

### Assessment

**Task quality: Approved。**

**Reasoning:** D1 组件在实际框架匹配之后执行精确能力检查，并在 payload 前提供绑定原主体/操作的在线资源复核；关键拒绝路径、旧 ADMIN 投影、别名来源、上下文清理及监听前失败都有有意义的实签 HTTP 或真实启动证据。两个 Minor 不构成行为阻断；批准不涵盖 X1 的真实 gateway 代理装配、X4 的 POLICY_MACHINE 混合装配、其它后续 Task 或生产 IAM 签收。

