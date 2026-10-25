# Spec Compliance

- ✅ **Spec compliant（仅 E1a）**。`CurrentAuthorityClient.java:12` 仅添加 default 接缝，原七方法保留；`HttpCurrentAuthorityClient.java:64` 的旧 authorize 投影同一映射路径，`:66` 单次解码、单次 authorize current 查询；`ServiceAuthorization.java:63` 绑定 retained 与原 call 刷新入口。未发现缺失、额外业务实现或任务误解。
- ✅ 完整 initial 来自同一已验 service ticket 和 signed current：`HttpCurrentAuthorityClient.java:80`、`:176`、`:181` 分别核两侧 initial/hash、主体覆盖、operation；`:82` 比较完整 Actor，`:83` 核 grant expiry；`:87` 在查询结束后再拒绝过期票据。`AuthorityProtocol.java:46` 使用既有规范 hash，`:47` 对权限取包含关系、对 dataLabels 保持义务只增不减，未从 current 反推 initial。
- ✅ origin 双缺才允许普通 current-only；单缺、空白、非字符串、过长、不一致拒绝，见 `HttpCurrentAuthorityClient.java:90`、`:94`。有限 issuer+jti 来源在 `LoopbackAuthority.java:185`、`:189`、`:218`、`:259`：先固定信任域验签及时间/有界 jti，再计算稳定锚；复用既有 Grant 保存原 source，重复 exchange 复核原字段、Actor 与其余声明，保留 original policyVersion/ceiling。forward/resume/approver 均继承 origin，`:314` 同时向票据和 current 输出。
- ✅ `HttpCurrentAuthorityClient.java:103` 的 final 嵌套类型仅私有完整构造器（`:109`）；public currentOnly 无 initial/origin/Clock 输入；私存同一映射的 ticket jti/expiry、已核 nonce/request digest 与 client Clock，初始 Actor 再拷贝，toString 脱敏。无参 requireVerified（`:123`）核 current/operation/grant/ticket 最早有效期限。
- ✅ `AuthorityContext.java:28` 的 retained bind 为包可见；`:38` 的 requireBound 仅认可当前线程最顶、未关闭、未失效 frame 上的同一个 mapping 对象，失败先退休该 frame；`:48` 刷新先失效、复核 caller/call/grant/operation/version 和 current 不扩大，再核 initial/origin 不变，最后 `:69` 才替换并恢复。普通 bind/default 不能制造 proof；ThreadLocal 非继承，非 LIFO 清理保留；`ServiceAuthorization.java:125` 保留 async 清理。
- ⚠️ **跨任务边界**：真实 PG Grant、RunAuthority 同事务写入、历史 scope、后台恢复、driver/outbound/scheduler、材料生产消费/下载/stream、provider/RR2、D3/E 联合不在本 diff。`iam-stage-5-e1a-brief.md:35`、`iam-stage-5-e1a-report.md:72` 正确保留这些验收依赖。E1b 必须在真正写入事务中调用 requireBound，不能将未过期的 detached getter 当成持久授权；本审查不签收全 E/Task5 或真实 IAM 闭环。
- ⚠️ **未变全局约束**：VerifiedAuthority 九字段、OperationBinding 形状、四契约/v1.2、固定 Java/Boot/Security/Nimbus 版本及生产 IAM startup 闭锁依赖未变文件。本次不扩大检索；root 的 `.local/iam-stage-5-e1a-root-handoff-check.json:3`、`:5`、`:15`、`:18` 已记录 344→346 源、精确八文件、335 common target、1313 其余 target 和 22786 保护文件核验。root 应维持该冻结链；不能仅凭本 diff 重新宣称未读代码或生产环境已验收。

# Strengths

- `RetainedAuthorizationTest.java:54` 同时断言 initial AB/current A、规范 hash、labels、集合不可变、脱敏及一次查询；`:75` 验证旧 default/public currentOnly/私有构造/Jackson 拒绝；`:100` 和 `:115` 覆盖双侧 origin 与签名绑定错误。测试通过真实有限 HTTP authority 和签名映射，未用 mocked VA 替代核心验签。
- `RetainedAuthorizationTest.java:144` 验证同 source 的不同 Grant/root/operation 共享 origin、forward 与新 DELEGATED resume 保持 initial/origin、旧 observer 不延期、fresh jti 为新认证；`:173` 验证重编码及旧 policyVersion 不重置认证锚。实际用例是有限 fixture 的不同资源调用，不是生产 metadata→main/PG 联合测试；稳定性由 `LoopbackAuthority.java:189` 的 issuer+jti 计算支持，真实链仍属后续。
- `AuthorityContextRetainedTest.java:36` 在撤销、不可用、过期、绑定错、上限改变、origin 改变、降级和 current 扩大后移除故障，再断言旧 frame 不能读取、刷新或持久化；`:92` 覆盖本地过期后 Clock 回退，`:106` 覆盖非 LIFO、其他线程和其他请求。`:20` 还验证 requireBound 不联网。
- `ServiceAuthorizationTest.java:416` 通过真实 servlet/HTTP 验证同步两次查询与 async 三次查询；完成 redispatch 的第三次 preHandle 有明确解释，未为满足次数而删除真实路径。原清理 probe 仍在同一请求线程的 finally 执行，async 仍显式传 VA，未引入 ambient proof。
- `ServiceAuthorizationTest.java:110` 的旧 ADMIN 测试迁移使原 source/initial 真正包含 ADMIN+EDITOR/AB，再让在线 current 收窄到 EDITOR/A，保留 APPROVER 403、EDITOR 200 和响应仅 EDITOR/A 的断言；新双侧完整 ceiling 校验下这是有效输入修正，没有生产放宽。
- `HttpCurrentAuthorityClient.java:87` 与 `RetainedAuthorizationTest.java:234` 处理在线查询耗尽票据最后有效时间的边界；根审计 `.local/iam-stage-5-e1a-root-handoff-check.json:62`、`:73` 保留该场景实际 1/1 红→1/0 绿，不将编译失败冒充行为红。
- 结构保持四个生产文件内嵌实现；frame 生命周期独立于 HTTP 测试，新增两类各承担明确职责。生产类增长主要来自私有证明和共享刷新逻辑，未增加业务 JAR、PG 替身、缓存或新公共生产类型；八文件范围见 `.local/iam-stage-5-e1a-root-handoff-check.json:5`。

# Issues

## Critical (Must Fix)

- 无。`HttpCurrentAuthorityClient.java:66`、`AuthorityContext.java:38`、`:48` 的本任务验签、绑定与失效边界未发现可据此成立的绕过。

## Important (Should Fix)

- 无。`RetainedAuthorizationTest.java:115`、`AuthorityContextRetainedTest.java:36`、`ServiceAuthorizationTest.java:416` 与实现相互印证，未发现需要阻断 E1a 的错误行为或重要覆盖缺口。

## Minor (Nice to Have)

- **M1：六轮 Surefire dumpstream 有 classpath 诊断噪声。** `docs/evidence/iam-stage-5-e1a/test-audit.json:695`、`:700`、`:705`、`:710`、`:715`、`:720` 均保存 Manifest-JAR 绝对 classpath / `'other' has different root`。实际影响是测试日志不洁净、故障识别成本增加；这些诊断不构成本次 XML 失败，也不抵消已保留的真实红轮。建议后续独立修正 Windows 路径/启动器诊断原因并保留原证据，**不要采用 dumpstream 中禁用 JDK classpath 检查的提示**。本次不改 POM 或测试参数。
- **M2：预期 startup 负例仍输出 39 行 ERROR。** `docs/evidence/iam-stage-5-e1a/test-audit.json:724` 起保留 Application run failed / failure analysis 输出，作者披露见 `docs/evidence/iam-stage-5-e1a-report.md:70`；根审计 `:84` 的 full-01 为 17 XML、174/0/0/0。这些是已知闭锁/非法配置启动负例的日志噪声，不应写成“无错误日志”或用于掩盖新增失败。建议后续在专用测试中捕获并断言预期日志、仍让非预期 ERROR 可见；本次无需为此重跑绿色 suite 或改变生产闭锁行为。

# Checks and boundaries

- 已读取当前工程 `AGENTS.md`、`contracts/README.md`、任务 brief、作者报告、root handoff 审计与任务审查模板；工作根始终为 `G:/207/项目/算法重组工具/代码/agent-platform`，未访问相邻旧工程。
- 独立计算输入摘要：brief `f4f38ec26e91524cbf72d3cb4f984c161c24f55238e78e5f72c13c3d6c8cdaf3`；作者报告 `f414394303aa6eec54e44c5711dd2470c925c574cf41641108af82fbca463239`；root handoff `571ae9fadca6fca9dc24fd3f0a2a8c192f300fc2f72c2293ad01cfdbca92c300`；审查包 154613 字节、SHA256 `d4b41e4b3c90bd35f53ec8af123e39d1c3962c43eb2a8235d51b6e76f9afd6e3`，全部匹配指定材料。
- 完整上下文 diff 首次一次读取被工具输出层截掉中段。经 root 知情后，按包行区间 260–282、283–536、537–800、801–995、996–1195、1196–1450、1451–1545 补读不可见部分（边界少量重叠用于定位）；开头与末尾沿首次输出审阅。随后仅从同一包计算新文件行号，没有重新读取八个变更源文件，也没有运行 Git。
- 唯一具体命名的未变实现核对：**“retained 的最早截止、initial hash 与 labels 方向是否实际由所调用 helper 保证”**。只读取 `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProtocol.java`；`:46`、`:47`、`:54`、`:65` 确认规范 hash、义务方向、operation 绑定与三项 current 截止；没有继续检索其他未变业务调用点。Actor 深不可变以私构造中的再拷贝及新测试断言为证，未展开读取 Actor。
- 测试执行证据采用 root 已独立核验的六阶段 XML/raw/source 冻结链：`.local/iam-stage-5-e1a-root-handoff-check.json:27` 起依次 4/4/0/0、50/3/0/0、50/0/0/0、1/1/0/0、1/0/0/0、174/0/0/0。审查另读取 test-audit 中六 dumpstream 内容和 39 ERROR 清单以列出 Minor；没有重跑作者绿色测试、common full、旧探针或七 reactor，没有将各轮与历史 962 累加。
- 原三项 targeted 失败的修正与 diff 一致：ADMIN 输入修正见 `ServiceAuthorizationTest.java:110`；合法过去时间与未来时间区别见 `RetainedAuthorizationTest.java:202`；async redispatch 次数见 `ServiceAuthorizationTest.java:434`。测试顺序并非所有新 API 场景都有基线红，作者已明确披露（`iam-stage-5-e1a-report.md:45`、`:72`）；首批四项与查询期间到期的实际行为红仍可核验。
- 仅写入本文件 `docs/evidence/review-iam-stage-5-e1a.md`；未改源码、target、index、Git、旧证据或作者报告。只读审查所有权交回 root。

# Assessment

- **Task quality: Approved。**
- **Reasoning:** E1a 将同次已验完整 initial 与稳定原认证锚保留在不可由公共 VA 补造的私有证明中，刷新和本地绑定检查能终止失败 frame，并保留普通 current-only 兼容路径。现有证据足以批准该 common 接缝；两项已知测试输出噪声为非阻断 Minor，真实持久事务与生产闭环仍须后续独立验收。
