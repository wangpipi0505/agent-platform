# E1a：保留同次已验完整初始上限（正式授权）

本稿是已批准 Task5 的前置接缝拆分，不改变 v1.2/四契约。唯一工作根 G:/207/项目/算法重组工具/代码/agent-platform，严格不读取/关联相邻旧代码、配置、凭据、数据与进程。先读 AGENTS.md、contracts/README.md；工程执行索引的最新 G6 结论优先于旧初始化交接。用户已授权持续开发和隔离端点，无专用外部环境，不再索要。

开工门禁已满足：D3a0及deadline补强均已独立Spec compliant / Task quality Approved，root已全文读取最终复审，source/target归还；六份执行索引已更新。当前344源/POM基线见iam-stage-4-d3a0-deadline-fix/source-after.json（仅一测试补强，common生产仍D3a0冻结API）；root核验见.local/iam-stage-4-d3a0-deadline-fix-root-check.json。最终review SHA e99163ee787fbe34cb1d1490b89c9842d6dac3385f4e04c62d178b485f5e07ae，report c973f076da4113b2a61f0178fc068e927c6e17e031671e8a44d4c73d6a93e4d0。现正式授权新作者作为唯一common source/target持有者实施本稿。生产IAM startup闭锁保持；本段不操作PG/业务服务，不等同E/Task5完成。

## 实现边界和接口裁决

参考 iam-stage-5-e1-initial-seam-proposal.md 的源码事实与末尾精化。保留 VerifiedAuthority 九字段、OperationBinding 公共形状及 CurrentAuthorityClient 原七方法。采用一个最小 default 方法及 HttpCurrentAuthorityClient 内部 public static final RetainedAuthorization，完整构造器 private，仅真实验签路径 mint；public currentOnly(VerifiedAuthority) 永不接受 initial/origin/claims。默认 authorizeRetained 调原 authorize，仅返回 current-only；HTTP override 一次严格映射产生 retained，原 authorize 只投影 retained.current()，不双 decode/query、不形成递归。

RetainedAuthorization 私存当前 VA、完整 initial Actor、originAuthenticationId、ticket jti/expiry、已核 nonce/request digest 与同 client 的可信 Clock。API 为 current()/hasInitialProof()/requireInitialCeiling()/requireOriginAuthenticationId()/requireVerified()。最后一项不接受调用者 Clock，避免旧 Clock 绕过期限；所有实例绑定、主体、grant/root/operation/hash/有效期由同一验签映射建立。缺 proof 的 getter/check 默认拒绝。初始集合深拷贝，toString 脱敏；无公共完整构造、withInitial、record canonical constructor、Jackson creator 或可反序列化 proof 的业务入口。它是已验数据和瞬时证明，不是脱离 frame 就可持久授权的许可。

## 签名来源与期限

沿现 decoder/verifier 固定 issuer/aud/typ/算法、重复字段/类型/时钟、nonce、request_sha256、client_id、call/current 语义。authorizeRetained 对收到的同一已验 service JWT 和同一次 signed current decision 两边完整校验 initial_ceiling，采用现 AuthorityProtocol.ceiling 规范 hash；完整值与主体/grant/operation/root 一致，current 必须被 initial 覆盖。labels 为义务，只增不减，不能反向裁剪。仍核 ticket_jti、subject_caller、authorization_version、grant、operation 等现字段。不得裸解未验 JWT 填 Actor 或为取 ceiling 发第二次 online query。

proof 时限是 ticket/current/operation/grant 截止最小值；本地 check 使用私存可信 Clock。query 已核 nonce/request digest 进入同一私构造结果，不事后从另一个 VA 拼接。只证明测试固定 profile，不声称已签收生产 IAM 持久 Grant。

内部 signed profile 顶层新增 origin_authentication_id（service JWT 与对应 signed current decision 都有），非空字符串最长128，两边相同；同原认证跨 metadata query/main 不同 Grant 仍同值，forward/resume 不重置。值须来自 authority 对已验证原 source 认证的稳定锚点，不是 decision jti、ticket jti、grant id、operation id 或 guard_ctx。双边均缺可返回旧普通 current-only；单边缺/空/类型错/过长/不一致直接拒绝，不能降级掩盖。有限测试 fixture 的最小 issuer+jti 方案由末尾追加裁决给出；保留 D2 的原认证、原 policyVersion、窄上限与 labels 并集约束，不另造生产 IAM。

## 请求 frame 接缝

ServiceAuthorization private requireRetained 保留原 require 的全部 route/mode/current/caller/integration 检查；原 public require 返回 .current。preHandle 仅一次映射并用 retained + 同 call 刷新 supplier 绑定；workload/POLICY_MACHINE 无用户 proof/frame。

AuthorityContext 增包可见 bindRetained(retained,supplier)，保留 public bind(VA) 为 current-only。frame 单一权威值为 retained。requireFor 与新 public requireInitialFor(Actor,ResourceBinding) 共用一次 refresh 路径；public requireBound(RetainedAuthorization expected) 不联网，用于后续真实事务。

沿现先 invalidated=true 再复核/刷新顺序，继续校验 caller/call/grant/operation/version、当前范围不得扩大。带 proof 刷新要求 full initial/origin 不变；每次 nonce/decisionId 来自本次真正查询，不能与上次拼接。任何失败、过期、缺 proof 降级都令相关 frame 永久失效；不能捕获异常后再读旧 current、再次成功刷新复活或靠旧捕获对象持久化。

requireBound 检查本线程最顶 frame 未 closed/invalidated，frame.mapping 与 expected 对象身份相等，内部 requireVerified() 通过；刷新替换后旧对象、退出作用域、非 LIFO 已关闭祖先、其它请求/线程均不能用。失败退休当前相关 frame。保留非 LIFO 清理与异步入口/请求结束清理，禁止 InheritableThreadLocal/全局 proof 缓存；普通业务不能自行 bindRetained。异步仍显式传 VA，后续 PG 恢复另以本 run 元数据取新 current。

## 允许文件

仅 common authority 包 CurrentAuthorityClient、HttpCurrentAuthorityClient、AuthorityContext、ServiceAuthorization 及必要最小内部 helper（优先嵌套，新增生产类型先给出理由），对应测试。可最窄扩展现 LoopbackAuthority 测试 fixture 保留稳定 origin/完整 initial，两份签名输出与有限故障 hook，保留其既有默认行为和 D2/D3 已通过语义；不复制 authority、不加业务 JAR、依赖/版本/POM/服务 YML 不改。

新增测试优先 RetainedAuthorizationTest / AuthorityContextRetainedTest，必要现测试最小迁移；最终报告逐个列真实类名。禁止本段修改 engine/skill/model/app/gateway、前端、迁移/DB、scope schema 或开启生产半链。真实 RunAuthority/RunService 同事务受理与历史scope、driver/outbound、scheduler阻断、材料生产消费/下载/stream为 E1b 与 D3/E 联合后续，全部验收依赖保留。

## 有限验收与报告

1. 先保存可编译的实际行为红，再实现；编译/fixture配置失败另列，不冒充行为红。原 initial region A/B+labels、current A，保留值仍 A/B，hash按现算法一致；currentActor/guard_ctx/public VA/currentOnly/旧 bind(VA)/旧 default 不能制造 proof。
2. 两份签名任一 initial/hash/subject/grant/root/operation/ticket_jti/nonce/request digest/版本错都拒绝；origin 双缺普通 current 兼容但 requireInitialFor 拒绝，单缺/错值/类型/长度拒绝。同原认证 query/main 不同Grant origin相等，fresh jti为新认证；D2旧policy变体拒绝语义保留。
3. 一次 authorizeRetained 只有一次 authorize current query；原 authorize 投影同路径，preHandle 与每次明确业务刷新各一次。current A 不能因initialAB而扩回AB。
4. 成功刷新初始/origin恒定且旧捕获实例 requireBound 失败；403/503/到期/绑定错/降级后即使异常被捕获，当前frame永久失效；非 LIFO 不复活、普通线程/异步无 ambient proof；原 HTTP 异步 finally 清理保留。
5. 使用有限 Clock/闩锁。真实 resume 新 DELEGATED 原origin/initial仍相同，旧 PORTAL observer 不因之延期；本段不把此common有限测试签成真实 provider/RR2通过。保持 production factory closed，原ResponseLease holder语义不变。
6. 聚焦具名测试后，一次完整 common；仅因新变更/失败才重测，不重跑七reactor或独立已通过的同码suite。每轮 rawlog/XML/sourceSHA 立即归档，最终同码与patch/frozen一致；无大量/耗尽/旧漏洞失控probe。

报告 docs/evidence/iam-stage-5-e1a-report.md，证据 docs/evidence/iam-stage-5-e1a/。包含 baseline、源/target/保护SHA、完整patch实际apply后逐字核对、实际开源源码阅读、真实红绿命令/输出/XML、未跑范围和自审疑点，完成归还 source/target 后独立双审。无 HEAD/remote 不伪造git提交/空diff；不改历史证据、不加工期，不将新common测试与旧962相加。

## root补充裁决（E1a正式授权前准备）

采纳 E1提案第10节的精化：有限fixture originId 为 oa1: 加 AuthorityProtocol.digest(List.of("origin-authentication.v1", verified issuer, verified jti))，68字符；query/main使用保存的同一source字符串，不分别生成新source。仅从固定各自信任域、已验签名/alg/typ/aud/时间/有界jti提取，external D2 integration 继续固定 ServiceTokens.ISSUER，非external旧integration保留自己的既有固定域；不能用传入iss选域。forward/resume/approver投影保持同originId，不把它当替代主体/Grant/root/policy的万能匹配。

同 issuer+jti 的原认证声明必须不可变；首次已验 source 角色/属性/主体/绑定/期限的严格规范信息应由现有限Grant保留并对重复exchange复核。不得因新增originId而让PORTAL同iss+jti改claims后创建不同原认证或更宽初始值。复用现有限Grant/规范语义，不新建生产IAM/全局身份缓存。D2 originalAuthentication的已有逐项/Actor/policyVersion/initial锚点完整保留；语义等价的对象键顺序或签名编码变体不能重置认证锚点，fresh jti才是新认证。

首选仅4个生产文件内嵌实现；测试范围为LoopbackAuthority、新RetainedAuthorizationTest、ServiceAuthorizationTest，只有确需独立frame测试时才新增AuthorityContextRetainedTest并说明职责。完整common回归是E1a组件结论，真实app policy PG联合与RunAuthority/受理scope/材料/RR2仍由后续阶段执行，不能合并计数或提前关闭。

