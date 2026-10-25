# Task4 D2 独立审查

## Spec Compliance

**❌ Issues found。** D2 的独立 resolver、PG revision 与混合信任域接线基本符合正式 brief；但原认证→初始策略版本锚点可被同一已签 token 的等价编码绕过，未满足 `iam-stage-4-d2-root-handoffs.md:38` 的原 issuer/jti/subject 约束及显式重新认证要求，详见 I1。

本次仅审查 D2，依据 `docs/evidence/iam-stage-4-d2-brief.md` 与正式 root handoffs；采用 `subagent-driven-development/task-reviewer-prompt.md` 方法。完整上下文 diff 分块单遍审读，随后仅从 diff 计算引用行号，没有重读变更源码、运行 git 或扩大为整分支审查。

- 独立域：`PolicyRequestVerifier.java:18–55` 固定公钥、RS256 用途与 caller/target/action、30 秒/零 skew、原 body 摘要和 nonce；`ServiceAuthorization.java:49–53` 同时核真实 Handler Method、mapping 和 request-bound 私构造 Proof。`IntegrationPolicyResolverController.java:14–18` 仅使用 Proof 保留的原 body。
- 真实一次 PG snapshot：`IntegrationPolicyResolver.java:14–30` 按 credential/tenant/app/biz 参数限定，一次 SQL 取得当前 status/revision/策略；主体重算、版本不匹配拒绝，响应没有 secretRef 或 principalIds 全名单。`PolicyResolveRequest.java:8–23` 明确 v1、类型、字段和长度边界；`PolicyResolveResult.java:3–6` 对应约定 16 字段。
- 三处 mutation：`IntegrationService.java:129–131,169–196,209–223` 保留 ADMIN、采用已验租户，issue 锁发布指针并使用存储的发布 SHA；policy/revoke 锁真实 credential metadata，资源复核后在原事务递增 revision。最终刷新后的 Actor 再次检查 ADMIN。`V104__integration_policy_revision.sql:2` 初始化 revision=1。
- 默认关闭与缺配置拒绝：`PolicyResolverSecurity.java:20–38` 与 `PolicyResolverSecurityTest.java:12–21`；同真实 mapping 双正例、错域零查询、缺 Proof 登记负例见 `IntegrationPolicyResolverHttpTest.java:43–56,89–94,207–213`。
- 共用兼容规则：`IntegrationClaims.java:6–11` 保留旧 principal/hash/digest 与平台角色判定，gateway 仅委托这些纯规则，测试依赖为 common test-jar，未引入业务服务 jar。

**⚠ Cannot verify from this diff，须由 root 继续逐项核验：**

1. **生产实际装配与完整 R3。** `PolicyResolverSecurity.java:26–27` 提供明确 Handler/Ingress 接缝，而当前正例的 receiver、coverage、两条 chain 是 `IntegrationPolicyResolverHttpTest.java:43–56` 的真实测试 context 装配。D3 必须在实际生产 receiver 装配同一接缝，并完成 gateway→authority→实际 app/PG→实际 downstream 的原矩阵；目前的 `engine.authorize` 客户端调用不是 downstream HTTP SecurityFilterChain。gateway 的 `allowedPath` 单测不能单独证明所有 proxy 入口不暴露 resolver。
2. **初始集成票据上限的端到端保留。** `LoopbackAuthority.java:181–182` 在 external 模式用完整 `policyActor(actual)` 建 Grant；`IntegrationPolicyResolverHttpTest.java:162` 的辅助 token 没有表达比 PG 更窄的 roles/attributes。本次真实 PG 结果及后续固定 Grant 路径可核，但不能据此签收“原集成 token 收缩后的上限”全链不增权。root 应结合原 §2.3、D3 实际出票/认证证明补一个“token 上限窄于 PG policy ceiling”的正负例，核角色/许可属性取交、labels 义务保留及原 Grant ceiling 持续绑定；不把 PG 最大允许集合直接视为已经授予的初始集合。
3. **生产 IAM/HTTPS/TLS 与持久锚点。** `LoopbackAuthority.java:39–40,299–323` 的 external 模式仅为 loopback HTTP 测试夹具。生产固定主机/证书、不可用/错 TLS/字段错误、持久认证锚点与正式 IAM 材料签收仍归后续任务。`docs/evidence/iam-stage-4-d2-report.md:3` 保留 `BLOCKED_IAM_SCOPE_ACCEPTANCE`；该门禁不能因修复 I1 或隔离夹具通过而解除。
4. **未跑的旧业务回归与原契约。** 本 diff 未含原四契约或其他服务实现；root 已提供保护核验，但本审查没有重算整个 5745 项保护集合。原 IntegrationSecurityTest、各服务旧 SpringBootTest 和原 app 生命周期回归仍按作者报告的未跑清单交 D3/F，不把本次有限集合相加为全工程通过。设计快照 SHA 本次独立复算为约定的 `3c72b2062beb2e8b377f2384843ea935a0074b50c0063e5106566bbd8dcd0c4f`。

## Strengths

- Proof 接缝足够窄：不创建 Actor、不过通用 authorizeWorkload，也没有 `/internal/**` 豁免。使用不可变原 body 作为 controller 唯一输入，因此后续无需重新读取另一个可变 payload（`PolicyRequestVerifier.java:60–65`、`IntegrationPolicyResolverController.java:16–18`）。
- PG 授权资源来自真实 metadata；行锁一直保留至事务结束，policy/revoke 将 revision 与变更共同提交。测试覆盖陈旧资源并发写只能一个成功、snapshot 一致、真实审计触发异常回滚，以及最终 ADMIN 被撤销后零写入（`IntegrationService.java:209–223`；`IntegrationPolicyResolverHttpTest.java:145–160,181–204`）。
- 新测试实际启动 Spring HTTP、真实 service/controller 与专用 PostgreSQL，并安装全局 MappedInterceptor。resolver 查询计数和 authority 调用计数直接覆盖无递归及错误输入不查库；没有以内存 ACTIVE 替代本次 PG 结果（`IntegrationPolicyResolverHttpTest.java:43–87,89–129,163–179`）。
- 作者准确区分最终同码、早期行为红、装配失败及未跑范围；本次独立读取归档 XML，与原日志结果一致，没有把后轮 target XML 当成早期轮次证据。

## Issues

### Critical (Must Fix)

无。I1 发生在新增测试 authority 模式，不能夸大为已部署的生产 IAM 漏洞。

### Important (Should Fix)

**I1 — 同一已签认证 token 可改变文本摘要，从旧版本锚点“变成”初次认证。**

位置：[LoopbackAuthority.java:178](G:/207/项目/算法重组工具/代码/agent-platform/admin-common/src/test/java/cn/agentplatform/common/authority/LoopbackAuthority.java:178)、[LoopbackAuthority.java:211](G:/207/项目/算法重组工具/代码/agent-platform/admin-common/src/test/java/cn/agentplatform/common/authority/LoopbackAuthority.java:211)，以及仅验证原字符串重放的 [IntegrationPolicyResolverHttpTest.java:173](G:/207/项目/算法重组工具/代码/agent-platform/app-management/src/test/java/cn/agentplatform/app/application/IntegrationPolicyResolverHttpTest.java:173)。

`sourceHash=Digest.sha256(token)` 是查找原 Grant 的唯一身份锚点；找不到即把 expectedPolicyVersion 设为 null。新增 external 分支只检查部分主体/集成字段，没有校验固定 issuer 或必需 jti，也没有按已验 issuer+jti+主体找回原认证。

本次单次 Nimbus 9.37.3 检查证明：只将 HS256 signature 段最后一个 Base64URL 字符的未使用 pad bits 改一位，**不重签、不改变 claims/issuer/sub/jti 或签名字节，Nimbus 仍验签成功，但完整 token SHA256 改变**。因此在相同 policy（digest 不变）使 revision 从 1→2 后，这个等价原 token 会漏过旧 Grant 查找，再以 null 版本从 PG 获取 revision 2；并没有发生显式新认证。新增夹具和本次旧授权失效验收不能可靠代表正式要求。这里是库级实际复现加已审代码控制流判断，未重跑真实 HTTP/PG 变体请求。

修复：严格验证原集成认证的固定 issuer、必需 jti 及主体绑定，以已验的稳定认证标识（例如 issuer+jti+tenant/principal，并核 credential/app/biz 等原绑定）关联最初版本，raw token hash 只能作为附加绑定。拒绝非规范 JWT 编码可作补充，但不能代替原认证语义锚点。增加有限测试：原 token 的等价 signature 编码、相同认证标识的另一表示在相同策略 revision+1 后均拒绝，真实新 jti 认证才建立新 Grant；继续断言每决定至多一次 resolver 查询、失败不占新 Grant 额度。无需更改既有对外出票格式。

### Minor (Nice to Have)

**M1 — 预期启动失败日志及补丁空白警告仍形成较大噪音。** 位置：[final-common/raw.log:45](G:/207/项目/算法重组工具/代码/agent-platform/docs/evidence/iam-stage-4-d2/final-common/raw.log:45)、[apply.log:1](G:/207/项目/算法重组工具/代码/agent-platform/docs/evidence/iam-stage-4-d2/apply.log:1)、[实施报告:72](G:/207/项目/算法重组工具/代码/agent-platform/docs/evidence/iam-stage-4-d2-report.md:72)。最终 common 仍包含旧配置负例的大量 `Application run failed` 堆栈；补丁报告 30 行 whitespace warnings。它们没有使最终测试或字节重放失败，因此不升级为 D2 行为缺陷。后续所属测试维护应捕获/约束预期异常日志；新证据脚本应注明换行格式与 Git 检查参数。保留原日志和原字节，不为清警告改写历史证据或重跑本轮 suite。

**M2 — 中间绿色轮次缺少各自独立 XML，证据粒度有限。** 位置：[实施报告:44](G:/207/项目/算法重组工具/代码/agent-platform/docs/evidence/iam-stage-4-d2-report.md:44)。作者已披露部分中间绿只保留 rawlog；它们不能逐 testcase 独立核验。最终关键归档齐全，本项不否定最后同码结果。后续 runner 应在每轮结束、下轮覆盖 target 前立即归档当轮 XML；不可拿最终 XML 补冒早期轮次，也不要求倒跑历史结果。

## Assessment

**Task quality: Needs fixes。** I1 直接削弱 D2 必需的“旧认证不可自动获取新策略版本”验收；修复后应只先跑新增有限反例及直接受影响覆盖，再按源码变化决定必要回归。独立 resolver、锁与 revision 实现未发现本轮另一个阻断缺陷；上述跨 Task ⚠ 项继续保留，不能据此完成整条 R3 或生产 IAM 签收。

本次检查与证据边界：

- diff 实测 155545 字节，SHA256=`ba8fa3557240723613f8102dfbc0aaca6c36bfcd996f2be2a1c73177e32d2030`。基线 319→冻结 332、live/frozen/replay、target 312/127/54 与保护 5743+2 项来自 root 的独立交接核验文件；未伪造 HEAD、未重跑 git apply 或重新计算全部源码/target 清单。
- 直接解析最终归档 XML：final-common 14 类、150/0/0/0；final-focused 6 类、27/0/0/0（common5/app17/gateway5）；ingress-final 2 类、27/0/0/0（common14/appHTTP13）。同时核了 http-behavior-red 2/2/0/0、role-refresh-red 1/1/0/0、matrix-red 9/2/0/0；没有将不同轮次相加宣称全工程通过。
- diff 外仅具名聚焦检查：① pinned 复用是否实际严格验 typ/alg/issuer/jti/时间/重复 JSON，查 `AuthorityResponseVerifier.java:31–52`、`AuthorityProtocol.java:13–27`；② 最终资源复核能否换主体/恢复旧角色，查 `AuthorityContext.java:25–41` 与 `AuthorityProtocol.java:47–52`；③ 原 source token 上限/摘要是否在客户端得到额外保障，查 `HttpCurrentAuthorityClient.java:25–47`；④ 原 Task4 跨任务边界与原要求，查 `iam-stage-4-implementation-brief.md:34–86`。没有扩展到其他业务服务源码。
- 唯一新增有限运行是 I1 的纯内存 Nimbus 9.37.3 HS256 pad-bits 验签。root 专门授权后保留脚本 [.local/review-iam-stage-4-d2-nimbus-check.java](G:/207/项目/算法重组工具/代码/agent-platform/.local/review-iam-stage-4-d2-nimbus-check.java)。用系统 Java 17 源码 launcher 仅验证该库的编码/验签行为，**不替代工程 Java21 测试**；输出 `TOKEN_TEXT_DIFFERENT=true`、`SIGNATURE_BYTES_EQUAL=true`、`CLAIMS_BYTES_EQUAL=true`、`NIMBUS_VARIANT_VERIFIES=true`、`TOKEN_SHA256_DIFFERENT=true`。此前两次 JShell 准备尝试因 classloader 无法加载该 jar 失败，不计行为红或成功证据。未访问网络/PG、未使用真实凭据、未跑 Maven、未修改 source/target/索引/既有证据。

## D2 集中返修复审追加（2026-10-20）

### Spec Compliance

**✅ Spec compliant / Approved（仅 Task4 D2 及本次集中返修范围）。** 原 I1 已闭合；首轮 ⚠2 经 root 纳入本次必修的初始源声明上限缺口也已闭合。以下结论更新本任务当前审查状态，首轮缺陷及证据历史仍原字节保留。

本次依据 `iam-stage-4-d2-fix-brief.md` 与作者报告的“D2 集中返修追加”，仅单遍审读 fix-only 完整上下文 diff 的两份测试文件。diff 实测 81878 字节、SHA256=`fe8bb0cc4e8f72a7bbe9dc90ff19c88888024bc32743fc532d8e41b3c3016488`；作者整份报告 SHA256=`b148eaa04b02958aa86becf2af2364fd49046d6839523f8c8a23967409d75c4e`。没有重新读取变更源码或扩展审查其他服务，也没有重跑测试、Maven、旧 Nimbus probe 或接触 PG。

**I1：闭合。** [LoopbackAuthority.java:226](G:/207/项目/算法重组工具/代码/agent-platform/admin-common/src/test/java/cn/agentplatform/common/authority/LoopbackAuthority.java:226) 在独立 HMAC 验签后固定 issuer、限制 jti 类型/长度并验证主体与声明；[LoopbackAuthority.java:248](G:/207/项目/算法重组工具/代码/agent-platform/admin-common/src/test/java/cn/agentplatform/common/authority/LoopbackAuthority.java:248) 从已有有限 Grant 按已验 issuer+jti 找回初始认证，再逐项比较原身份/credential/app/biz/user/origin/digest/use/audience/iat/nbf/exp 与解析后的角色、属性集合。raw token hash 不再决定是否是新认证。`dispatch` 在查 PG 前完成原绑定比较，重复认证沿用最初 policyVersion 与 ceiling，校验失败或旧版本拒绝发生在新增 Grant 之前（同文件第182–190行）。

[IntegrationPolicyResolverHttpTest.java:177](G:/207/项目/算法重组工具/代码/agent-platform/app-management/src/test/java/cn/agentplatform/app/application/IntegrationPolicyResolverHttpTest.java:177) 的两个参数覆盖未预登记的 pad-bits 变体及同 issuer/jti 的合法重排 claims；真实 ADMIN 提交相同 policy 后，两变体、原 token、旧 resume/authorize 均拒绝且每决定一次 PG 查询，失败不新增 Grant，只有新 jti 的认证成功。第227、249行的负例另证明错误签名/issuer、缺失或错误 jti/claims、换主体/绑定/时间/声明在 PG 前拒绝。归档 `anchor-red` 两项实际 assertion failure 与最终绿对应，修复不再仅依赖首轮的库级推导。

**本次纳入的 ⚠2：闭合。** [LoopbackAuthority.java:260](G:/207/项目/算法重组工具/代码/agent-platform/admin-common/src/test/java/cn/agentplatform/common/authority/LoopbackAuthority.java:260) 的 `initialIntegrationCeiling` 从已验 source Actor 起步：角色与 PG 取交并剔除内部平台角色；许可属性只从源已有键取交，缺失/空权限不由 PG 补回；biz/user 精确匹配；dataLabels 取义务并集。结果同时通过源、PG 两侧 `AuthorityProtocol.within`，重复 exchange 继续保留首次 ceiling（第185行）。

[IntegrationPolicyResolverHttpTest.java:194](G:/207/项目/算法重组工具/代码/agent-platform/app-management/src/test/java/cn/agentplatform/app/application/IntegrationPolicyResolverHttpTest.java:194) 明确把真实 PG 放宽为 reader/writer、A/B，源仅 reader/A（并带需剔除的 ADMIN）；检查首次与 resumed/repeated 票据、initial_ceiling 和 current 都不补回 writer/B，source-only 与 PG restricted 标签共同保留。第216行还覆盖源空 roles、缺失或空 region。`behavior-red` 中实际出现 writer/B 增回及标签丢失，最终对应测试转绿，源 helper 没有改成 PG 全量来规避失败。

**⚠ 保留的跨 Task 边界：** 首轮 ⚠1 实际生产 receiver/全 gateway→authority→app/PG→downstream R3、⚠3 真实 HTTPS/TLS 与持久 IAM 锚点/外部签收、⚠4 迁移后的旧业务统一回归，已由 root 明确移交 D3/E/F/G。原四契约和 v1.2 的保护核验由 root 承担。本次只关闭测试 fixture 与 D2 有限真实 HTTP/PG 验收的两个缺口，不关闭上述事项，也不改变 `BLOCKED_IAM_SCOPE_ACCEPTANCE`。

### Strengths

- 修复复用现有有限 Grant 保存稳定认证与原声明，新增 helper 仅负责认证匹配和初始上限计算，生产源、外部票据格式、gateway 与默认非 external fixture 分支不变（`LoopbackAuthority.java:182–185,242–275`）。
- 测试校准保留了真正失败历史：最初编译错误及错误“先预登记变体”的用例都如实单列，后续 `anchor-red` 才作为 I1 行为红；没有用它们替换首轮证据。
- 本次每轮有独立 XML/rawlog；直接解析归档确认 `anchor-red` 2/2/0/0，`behavior-red` 3/1/0/0，`focused-green` 5/0/0/0，最终完整 common 14类150/0/0/0、app真实HTTP 1类19/0/0/0。最终日志分别在 `iam-stage-4-d2-fixes/iam-stage-4-d2-fix-final-common/raw.log:5688,5696` 和 `iam-stage-4-d2-fixes/iam-stage-4-d2-fix-final-http/raw.log:74,87` 明确结果及 BUILD SUCCESS；没有合并轮次或宣称全业务回归。

### Issues

#### Critical (Must Fix)

无。

#### Important (Should Fix)

无新的或仍存的本任务阻断项。原 I1 与本次纳入的 ⚠2 按以上代码和实际归档证据关闭。

#### Minor (Nice to Have)

首轮 M1/M2 作为历史维护项保留，不改写首轮原结论：本轮完整 common 仍有 38 处预期配置失败 `Application run failed` 日志（`iam-stage-4-d2-fixes/iam-stage-4-d2-fix-final-common/raw.log:45` 起）；本次 `apply.log`、`diff.stderr.log` 均为0字节，没有新增空白警告。本轮即时归档已避免继续发生 M2，但不能补足首轮缺失的中间 XML。后续按原维护建议处理，无需为此重跑本轮或篡改历史输出。

### Assessment

**Task quality: Approved。** 两个指定缺口已在实现与有限真实 HTTP/PG 行为证据中闭合，未发现本次新增的 Critical/Important。维持 D2 任务范围的批准，不提前签收生产 IAM 或完整 R3。

审计边界：root 的 `.local/iam-stage-4-d2-fix-root-handoff-check.json` 记录原 D2 baseline332 匹配、本次 live/frozen/replay332 一致、target common313/app131/gateway54（gateway54未变）、1610项保护均相同，以及新 patch `e805f81979349d81e719eee49acb36c6a7a81c3a152fd8f14882f0dc3b8588a4` 的实际重放。本复审读取该独立核验并核对 diff/报告 hash 与当轮 XML/rawlog，未再次执行全量源/target 重算或 git apply。

追加前独立确认本报告首轮长度12014字节、SHA256=`0d80dc0dc8b233c2b3bc9ebbea38efda711ec088864d8977ec858c2722c48b38`。本次仅在其末尾追加；源码、target、索引、原证据和首轮报告前缀保持只读。