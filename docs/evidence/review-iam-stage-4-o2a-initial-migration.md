# Spec Compliance

- ✅ Spec compliant（单文件迁移语义）；Quality: Approved；Critical 0 / Important 0 / Minor 1。审查对象仅本次 Initial 测试迁移，既有 Accepted B 不是本轮重新验收对象。
- 核验输入：`docs/evidence/iam-stage-4-o2a-initial-migration-brief.md` 全文；作者报告 `docs/evidence/iam-stage-4-o2a-initial-migration-report.md` 10547 B，SHA256 `27452ff95b8e6e680f3cc4461c07222692d024bfb7d00125d4adb27ef028f1d8`，全文1–69行；完整 diff `docs/evidence/review-iam-stage-4-o2a-initial-migration-package.diff` 18115 B，SHA256 `f4d4dacd72a7776a1e785ddcb3fa450e6f8f7bee37a291820d362f8f167c7c70`，202内容行，一次读至EOF。另完整读迁移准备72行至EOF。没有重新读 changed source；下文新源码行号由完整 diff 的 head 行映射得到。
- 基线标识：Accepted B borrow-fixes 593清单 `83e1fc79f72a4b21bdeb75a3cc41b7d0406797070f25e4242fc74407b42f1a20` → 本迁移593清单 `e9791749f842e9403604f5fc3ff354e596536746577c65eb7b610374d7d69d16`；均为交接标识，不是 commit。本 reviewer 未重复 root 的清单物理审计。
- 变更范围符合：diff:15–21 仅7条必要 imports；diff:148–198 唯一方法体变化。原12个测试名及顺序在 diff:29–146 全部保留，另11方法与 helper 均是 context，未出现删改。方法仍调用 retained 重载，既不构造假 proof，也不改生产、fixture、runner、POM 或其它测试。
- 双负例正确分离：`InitialMaterialScopePostgresTest.java:137–147`（diff:152–162）先真实受理、取真实 run-snapshot ref，对同 Portal 的旧 STATUS+app-version exchange 断言403，再从 persisted binding 的 owner/publicId/accepted snapshotSha 登记并取得合法 STATUS ticket。没有拿候选 ROOT.versionDigest 充当接受摘要，也没有仅把原失败改成 exchange 负例后结束。
- 真实接收前提完备：该测试:148–167（diff:163–182）使用 TrustedJwtDecoder、GET实际路径/servletPath、真实模板、URI变量、实际三参 status HandlerMethod、SecurityContext/RequestContext 与 ServiceAuthorization.preHandle；requireInitialFor 返回新 retained mapping，read 前独立 requireVerified/requireBound，并核 call/caller/source/origin/actor owner/operation subject及双资源。没有借用固定POST helper建立 STATUS frame。
- 同 frame 精确拒绝成立：该测试:168–178（diff:183–193）紧贴 read 采集 SQL 与 payload，断言403/RUN_AUTHORITY_DENIED、payloadReads 0、observations空；read后再次 requireBound/requireVerified。SQL非空允许控制元数据，排除 snapshot::text、inputs::text、version_refs::text、row_to_json；未声称全部SQL为0。
- 释放完整：该测试:179–184（diff:194–199）guard.afterCompletion/security.clear/request.reset 嵌套 finally，decoder及fixture try-with-resources；beforeSql hook 在内层 finally恢复（该测试:173）。
- ⚠ 仅凭 diff 不能确认其它11方法的原始字节、全593源码只一项变化、生产0、逻辑target2060/overlay、首次物理check/apply及journal/own-inventory完整性；这些属于 root 已划定的独立物理审计，不能将本审核的语义通过代替它们。作者报告:13、57–63 的相关陈述在本轮仍为待 root 审计的交接声明。

# Strengths

- `MaterialScopeStore.java:47–79,204–226` 与 `RunAuthorityStore.java:193–198,227` 提供独立拒绝顺序依据：合法 retained 校验后，真实 locator/binding/scopes 查询进入 requireReadProof→requireReader→requirePurpose，purpose 在 owner及execution-root比较之前抛403/RUN_AUTHORITY_DENIED，payload readCurrent在后。本次 frame 的 gateway/STATUS/PORTAL组合不是 localRunAdvance，也不满足 POST main。因此当前实现拒绝位置是 purpose，而非新观察资源与历史执行root不等造成的假阳性。
- `AuthorityContext.java:43–50,73–94` 说明 refresh 返回并绑定 fresh mapping，requireBound核对象身份及有效性；`HttpCurrentAuthorityClient.java:304–337,348–375` 显示 retained 的私有构造来自实际 decode/authorize 和 ceiling/origin验证。测试 read 前后不重新refresh或跨线程，前提不会被意外换成旧mapping。
- `RunAuthorityStore.java:151–166` 实际加载仅run标识列与authority控制字段、scope元数据；`RunAuthorityFixture.java:66,76,93–95` 的 beforeSql可见所有prepared SQL、payload计数器覆盖既有专用材料查询。结合实际读取路径与测试断言，payload0有实质依据；这里没有声称测试字符串检查能捕获所有未来SQL写法或提供B bounded reader的内存预算证明。
- `RunController.java:70–74` 确认实际 status三参签名；`ServiceAuthorization.java:55–88,101–129` 确认实际 route、在线 retained验证、frame建立及清理。`TrustedJwtDecoder.java:26–31,34–54,71–80` 仍启用Nimbus RS256、issuer和时间校验，testProfile不是跳过JWT验证。四null Controller仅作反射handler描述，不执行业务，符合任务边界。

# Issues

## Critical (Must Fix)

- 无。

## Important (Should Fix)

- 无。

## Minor (Nice to Have)

- M1 工具输出仍有噪声：`docs/evidence/iam-stage-4-o2a-initial-migration/iam-stage-o2a-initial-migration-final01/iam-stage-o2a-initial-migration-final01.log:61–64` 含既有 RunEventReaderPostgresTest 的deprecation及 FlowMaterialStatusPostgresTest 的unchecked INFO，且中文路径/信息乱码；`docs/evidence/iam-stage-4-o2a-initial-migration/diagnostics/engine-runtime/2026-10-21T11-48-52_034.dumpstream:2–4` 含 Surefire Boot Manifest-JAR绝对classpath/different root提示。归属为既有其它测试的编译诊断与Windows/Surefire输出环境，本次新增单方法未引入对应API或unchecked代码，不是失败断言，也不是本轮权限放宽。降低日志可读性，可在单独授权的工具维护任务处理输出编码及classpath问题；不要求为此修改本任务禁止修改的runner/POM/其它测试。作者报告:67已披露，不能表述为零噪声。

# Checks and named risks

- R1“负例先因失效/缺frame/错owner/resource失败”：仅定点读 `MaterialScopeStore.java:30–85,200–250`、`RunAuthorityStore.java:151–166,193–198,227`、`AuthorityContext.java:1–106`、`HttpCurrentAuthorityClient.java:304–377`、`ServiceRoutePolicy.java:52–56`；结论见 Strengths。首轮按准备稿旧行定位RunAuthorityStore时读到120–180（含现有bounded控制路径），随后定位实际load/purpose；没有进一步扩展审查该bounded实现。
- R2“handler/JWT是伪前提或POST误路由”：定点读 `RunAuthorityFixture.java:20–58`、`RunController.java:65–79`、`ServiceAuthorization.java:16–131`、`TrustedJwtDecoder.java:26–80`；实际三参status及在线授权调用链一致。一次组合输出截断了AuthorityContext，已仅补读该文件；diff全文未截断亦未重读。
- R3“可信登记实际放宽owner或使用候选摘要”：定点读 `LoopbackAuthority.java:40–49,518–524` 与 `RunObservationSelectors.java:1–56`（并仅对具名observation入口做rg定位）；登记immutable owner/resource，status selector绑定run+accepted摘要，实际STATUS gate再核owner/resource，符合测试输入。
- R4“payload计数遗漏已有实际路径或误称零SQL”：定点读 `RunAuthorityFixture.java:60–101` 与R1读取路径；测试窗口包含read的prepared SQL，控制metadata与完整材料读取已区分。
- R5“作者GREEN/噪声归因失真”：只读冻结raw、XML及新diagnostics；raw12181 B/SHA256 `ed0699b816f3a09613c1b998dce9827ff780bf6e76fc5a43fd5c5edf518484a1`，XML54833 B/SHA256 `1cff4e35450058cba2c164b6f2a1235666d36c4c6e5da8286f170ec861bdcd7d`，均与作者报告一致。raw:72仅Running InitialMaterialScopePostgresTest，:121/:125为12/0/0/0，:134 BUILD SUCCESS；XML:2及:67–144有完整12 testcase、无failure/error/skip，包含迁移方法:95。XML执行顺序与源码方法顺序不同属正常，未将两者混同。
- 执行边界：本 reviewer 未运行测试、PostgreSQL、replay或git；未扫描全仓/target；未修改源码、用户docs或作者封存产物，仅新写本review报告。所有shell工作目录固定 `G:/207/项目/算法重组工具/代码/agent-platform`。首次诊断JSON猜测.local路径不存在，随后仅在本任务evidence具名定位；无写入、无验证重跑。

# Assessment

- **Task quality: Approved（C0 / I0 / M1）。** 该迁移恢复了合法STATUS前提下对Initial材料读取目的隔离的真实测试，并保留旧非法exchange入口的负例；核验到的GREEN是本次单类12项，不是重写旧RED。
- ⚠ 跨任务限制：只继承正式brief/作者报告:9的原B final01旧65项64pass/1error归因，没有重跑或复核旧失败证据；本次12绿不补记成旧65绿。真实security receiver seam不等于完整status HTTP/JSON、SSE、C实现、生产容量或整体IAM验收（作者报告:69）。root完成自己的原字节、own/target/replay审计后方可关闭这一个迁移待办。
