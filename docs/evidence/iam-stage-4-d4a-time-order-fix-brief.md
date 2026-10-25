# D4a I1：会话 elapsed 日期顺序修复

唯一工程 `G:/207/项目/算法重组工具/代码/agent-platform`，每个shell显式workdir，不访问相邻旧实现。用户已授权持续开发与隔离验证，不再问是否继续。此处只修独立审查I1；原D4a实现、20轮命令／失败／24例多阶段适用覆盖、原报告／seal／review不改写，不更新八索引或前端，不做部署或打开全局IAM。

## 已确认问题与范围

原完整任务审查 `docs/evidence/review-iam-stage-4-d4a.md`：12551B，SHA `faf39ec6a08f1fcd8376077e237a870385aa2a60d5e814e5f83ed2fed84c2da6`；Spec Issues Found / Task quality Needs fixes，C0/I1/M1。唯一Important是 `AuthorityResponseVerifier.java:60` 的session elapsed分支失去原来的隐式 `nbf <= now < exp`，没有恢复独立 `exp > nbf`。已签、iat<T_exp<=nbf<=now 的不可能时间窗口会被误当合法elapsed，随后归403；按原brief/addendum应是上游坏结构503。没有发现成功proof逃逸，不能夸大成授权绕过。

root已具名读verifier31–69及现有日期测试152–178，确认该根因和现有测试只覆盖future iat／浮点exp。必须在session私有验证路径显式保留 `exp > nbf`；原普通verify／static pinned的默认行为保持，所有签名／header／issuer／audience／日期／nonce／请求绑定和其他authorization语义保持。不引公开工厂、反射、ThreadLocal、异常分类框架或新依赖。

本批唯一两处源码：

1. `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityResponseVerifier.java`：最小session日期顺序检查及解释旧隐式条件的必要注释。
2. `admin-common/src/test/java/cn/agentplatform/common/authority/PortalSessionAuthorizationTest.java`：只增加／调整此次真实风险的有限案例；优先一个新具名case归并已签current和ticket的nbf==exp、nbf>exp四个变体，使用既有LoopbackAuthority变换／Clock／真实JWKS能力，不能改fixture／gateway／其他测试。

若已有fixture不足以构造准确窗口，先报告具体阻碍，不猜造public proof或扩大文件。

## 验证要求

先真实行为红：签名、issuer/use/audience、请求nonce/digest绑定正确，iat<exp，nbf>=exp，传输／验签时now已越过nbf，来源Portal及grant尚未到期；预期503，旧代码实际403。current和ticket分别覆盖，避免来源401或未来nbf既有503掩盖要测的窗口。可用较长合法门户期限和既有变换／JWKS时钟控制，不做sleep循环／压力轮询。

修复后只跑新组，以及两个真实受影响控制：`signedElapsedExchangeAndAuthorizeDecisionsAre403BeforeAnyRelease`（合法elapsed仍403）和 `ordinaryVerifierAndPinnedVerifierKeepExpiredSignedEvidenceUnavailable`（旧默认仍503）。新组若是一个@Test含四变体，按一个实际case记，不能记作四个唯一@Test。未返回任何成功Session proof；本次不新增HTTP suite来重复已验证的错误写出。

不重跑24全套、8HTTP、旧B2/B1/PG/common179/全962；不为编译／日志噪声更改POM/runner或清洗原件。保留真实命令、源快照、Running及XML、red/green退出码；setup与oracle失败不算所需行为红。代码改后由作者执行上述实际受影响检查再交还，reviewer不代跑。

## 不可变基线与有限证据

正式before为原D4a source380，manifest `docs/evidence/iam-stage-4-d4a-portal-session/source-after.json`（78337B，SHA `7e1a1b53ae7b494361bf75b36d2878a1f4dbb1e5d0631c3311643a8e34822708`）；target1931，target-after.json（398161B，SHA `0a855288ee3f47bff030da2f562abdad3c6533c1ee224ed80e5e63d980f99516`）。物理组合见同目录composition：原B2 frozen/target-final＋N4b2修复overlay＋D4a frozen-overlay/frozen-target-overlay。复用此封存基线，不复制完整旧树或fat JAR。

root原D4a有限独立核验 `.local/iam-d4a-root-check.json`，6175B，SHA `75cdaf666272495705314539e73a0c0685f3f07824658427229ee58dc53cfc3b`，已核2982个物理文件、380/1931完整当前、11位置、75目标overlay、594新证据、20命令/21实际XML/24最后有效用例、28脚本/8索引/9contracts/13锚点及原设计和3份上游复制原件；本修复继承，不重复历史123837/16347完整扫描。

每个新命令前完整source SHA清单＋仅本两文件物理overlay；冻结时完整当前source/target/delta和有效组合，full-context两文件补丁从pristine基线实际rootcwd check/apply＋完整380逻辑名称/bytes/SHA重放一致。新／变动目标全部归因，不重跑Maven为报告补测试。原before数据不回填，原失败／诊断不改。

唯一own目录 `.local/iam-d4a-time-order-fix/`、`docs/evidence/iam-stage-4-d4a-time-order-fix/`；唯一新报告 `docs/evidence/iam-stage-4-d4a-time-order-fix-report.md`。根报告／manifest／handoff/seal保持无循环依赖、准确范围与bytes/SHA。最终返回所有源码／target／own写权，由root核验并交原D4a reviewer聚焦复审，原12551B初审保持。

## 观察与边界

原M1保留非阻断编译/日志观察；root另做 `.local/iam-d4a-root-diagnostic-notes.json`：20原始raw中32处编译提示匹配（包括旧Json deprecation、GatewayRunAuthorizationTest unchecked）、241条ERROR包含实际历史失败与预期非法安装，不等同241个意外生产错误。原target delta的25份report包含21份344B Windows Surefire absolute Manifest-JAR/different-root dumpstream，不能采纳其提示去关闭JDK检查。root实际核原件，未清洗或重跑。此观察只登记，不在本批顺手改泛型／工具链；最终分支审查继续裁定。

normal legacy仅有限实例化和root结构核验关闭“新增强制IAM client依赖”风险，不冒称完整实际应用新启动；原设计/四契约、固定版本、两run ANY/actual安全链、非session动作、全局IAM闭锁与前端D4b后续边界保持。
