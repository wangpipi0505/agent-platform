# O2a Initial 材料旧测试输入迁移报告

状态 DONE_WITH_CONCERNS，作者完成，等待 fresh reviewer 与 root 有限审计；不等同 Accepted。唯一 cwd 为 G:/207/项目/算法重组工具/代码/agent-platform。无工程 HEAD、无 commit、无外部发布。

## 授权与继承 RED

已完整读取正式 brief（6121B/SHA256 5a2c56199d2a11ae9635cf6ca77d8c3cdfa8983b951adfb9966ba98bab8706f6）及其72内容行有限准备（12799B/SHA256 bec015b38922e906f69d1917ce5051a4333368cba326f5dca2047f042ab067bb），并读取 AGENTS、implementation-brief、contracts/README；使用 TDD、verification-before-completion、ponytail。正式 brief 要求优先于准备稿第4节的运行建议：沿用已确认旧 RED，只运行一次最终12方法，没有新增单方法 RED 或重跑原65。

冻结 .local/iam-o2a-b-inherited-test-failure-check.json 明确原 B final01 六类65项为64pass/1error，错误是本测试旧132行 STATUS+app-version exchange，尚未发生 MaterialScopeStore.read。该历史错误保持原记录，不追改B结果，不归因成B reader失败。本次 preflight.json 只读继承该判定，并将当前fixture、read/requireReader/requirePurpose、Controller三参签名、AuthorityContext/ServiceAuthorization、selector/LoopbackAuthority的8项身份定点对齐最新Accepted593，未重审全部旧源码。

## 唯一源码改动与精确证明

只改 engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialMaterialScopePostgresTest.java 的 sameRootStatusPurposeCannotReadInitialMaterial 和7条必要 imports；12个原测试名及源文件顺序全部保留。method-byte-check.json 对每个方法记录UTF-8字节数与SHA256，其他11方法逐项相等；还把新方法替换回旧方法并去除新增imports，机械断言整个原文件完全一致。没有修改production、fixture、runner、POM、契约、迁移或其它测试。

方法先走既有生产受理，读取真实 persisted Binding，选择它的 run-snapshot material ref。原 STATUS+RunAuthorityFixture.ROOT（app-version）的同Portal exchange独立精确断言 PlatformException.status==403，保留非法入口负例。随后用同一个Portal、同一真实受理run，从 b.owner()/publicId()/snapshotSha() 可信登记 observation；snapshotSha是已接受摘要，未使用候选 ROOT.versionDigest。新合法资源由 RunObservationSelectors.status 构造，使用 STATUS_ACTION。

新ticket经实际 TrustedJwtDecoder 解码，将 JwtAuthenticationToken 和实际GET MockHttpServletRequest分别放入 SecurityContext/RequestContext；请求路径与servletPath均 /admin/runs/实际id，设置真实模板、URI变量、BEST_MATCHING_HANDLER。HandlerMethod取实际 RunController.status(String,HttpServletRequest,HttpServletResponse)，四null构造器仅作handler描述，不调用Controller业务；没有使用固定POST的 f.admitted(call,work)。实际 ServiceAuthorization(engine,policy,f.engine).preHandle==true 建立可信frame；AuthorityContext.requireInitialFor(Actor.current(),resource)执行在线刷新并使用其返回proof。

在read之前分别调用 proof.requireVerified 与 AuthorityContext.requireBound，并精确核对call、caller gateway、source/origin PORTAL、actor owner、operation subject、call/operation resource；read之后再核bound/verified，同一active frame未换mapping。因此后续拒绝不是无frame、过期或错误owner/route抢先失败。MaterialScopeStore.read(ref,RetainedAuthorization)最终精确断言403/RUN_AUTHORITY_DENIED；不使用无条件拒绝的VerifiedAuthority重载。已核生产顺序为 requireReadProof→requireReader→requirePurpose，再核owner/execution root：合法STATUS先在purpose处拒绝，未靠run-snapshot与execution app-version的资源不同伪装通过。

紧贴read窗口清零payloadReads、payloadObservations，并使用现有beforeSql记录prepared SQL，finally恢复hook；断言payloadReads==0、observations空、SQL列表非空且每条都不含 snapshot::text / inputs::text / version_refs::text / row_to_json。允许locator/binding/scope控制元数据SQL，包括既有明确的r标识列与a.*投影；不声称整个SQL为0，也不声称旧metadata路径有B bounded reader的内存预算。guard.afterCompletion、security.clear、request.reset用嵌套finally；decoder/fixture各自try-with-resources。没有伪造VA/Retained、重签阳性或放宽生产授权。

源文件原顺序如下（JUnit XML执行顺序另见test-results.json，不把两者混同）：

1. `readsThreeActualColumnsAndPreservesParentAndLabelUnion`
2. `freshSameOwnerGrantWithNewInitialReadsExpiredHistoricalGrant`
3. `narrowerCurrentCannotReadAnyPayloadAndDoesNotRewriteHistory`
4. `wrongOwnerRootPurposeVersionOrDigestRejectBeforePayload`
5. `repeatableReadMustNotSilentlyJoinReadCommitted`
6. `preciseJsonbNumbersNullsAndArrayOrderDetermineActualHashes`
7. `immutableDatabaseRejectsConcurrentPayloadChangeBetweenMetadataAndSelect`
8. `corruptedMetadataAndMissingScopesDenyWithoutSelectingPayload`
9. `payloadDigestMismatchAndRootNullAreRejectedAfterExactlyOneRead`
10. `integersBeyondDoubleRangeAndSignedZeroRemainExact`
11. `recomputedScopeHashCannotHideWrongParentOwnerOrAdmissionAudit`
12. `sameRootStatusPurposeCannotReadInitialMaterial`

## 唯一最终验证

实际唯一runner命令：

```text
node .tools/run-iam-module-tests.mjs iam-stage-o2a-initial-migration-final01 engine-runtime InitialMaterialScopePostgresTest
```

cwd固定本工程；JDK21、Maven离线 .tools/m2、独立PG 127.0.0.1:25432/agent_platform_iam_engine_v12，runner只把本工程测试凭据注入子进程，没有输出凭据。PG实际为16.15；Flyway验证19项迁移，engine schema217已最新。

exit0 / BUILD SUCCESS；actual Running仅 cn.agentplatform.engine.infra.InitialMaterialScopePostgresTest；XML **12 tests / 0 failures / 0 errors / 0 skipped**，9.046秒。test-results.json保存实际命令、cwd、source epoch、Running、全部XML testcase名和raw summaries。未运行原65、旧B PG21/Budget14、旧Service14/Profile28或C；没有重复已绿全类，没有focused修复需求。

raw：`docs/evidence/iam-stage-4-o2a-initial-migration/iam-stage-o2a-initial-migration-final01/iam-stage-o2a-initial-migration-final01.log`，12181 B / SHA256 `ed0699b816f3a09613c1b998dce9827ff780bf6e76fc5a43fd5c5edf518484a1`。
XML：`docs/evidence/iam-stage-4-o2a-initial-migration/iam-stage-o2a-initial-migration-final01/TEST-cn.agentplatform.engine.infra.InitialMaterialScopePostgresTest.xml`，54833 B / SHA256 `1cff4e35450058cba2c164b6f2a1235666d36c4c6e5da8286f170ec861bdcd7d`。
test-results.json：5120 B / SHA256 `cbf6dc680027fda8261649b91ddc8ecc70e16eacd9cc2607a4fbf9a6b5b28030`。
最终source epoch：16582 B / SHA256 `40814e5bfb55b02fc4122d9ec3d360b127e29d0212e3ed946726712db37194d4`；测试完成后该源码未再修改。

## 有限交接与首次补丁重放

Accepted source-before593清单 112856 B / SHA256 `83e1fc79f72a4b21bdeb75a3cc41b7d0406797070f25e4242fc74407b42f1a20`；source-after593清单 112856 B / SHA256 `e9791749f842e9403604f5fc3ff354e596536746577c65eb7b610374d7d69d16`。source-delta仅1测试更新、production0、其余592项原记录继承；before/after精确单文件在 .local/iam-o2a-initial-migration/before 与 after。before 12829 B / SHA256 `923f157c28235d5bc58421997e8282de80a38ee6ed8b97f433e7ad7892aff5ed`；after 16582 B / SHA256 `40814e5bfb55b02fc4122d9ec3d360b127e29d0212e3ed946726712db37194d4`。不全树扫描源码。

Accepted逻辑target-before2060清单 427783 B / SHA256 `a23b333e7e0efd1f4d74cfac75804d9c791a3f9bbc4905c3ca92b132009e1fac`。本次只从admin-common/engine-runtime四个compiler阶段createdFiles/inputFiles、manifest资源、common test-fixture JAR、actual Running XML/txt构造462项候选overlay；物理copy保存在本任务target-overlay。覆盖后逻辑仍2060，target-after 427782 B / SHA256 `9dd29bc3bd986d324a94dd100fa1c30e46518c715c0215aba555e8a644c4e98d`；overlay清单 101686 B / SHA256 `9b00975b77589b26c5e3ea12c486f479e7ac296ed713b4d09719a542897cb4c4`；compiler-inputs 66786 B / SHA256 `58a344157c0aa141170fdf81d145147963e1708ae9d7d75526820a3230f23add`。compiler重编59份测试输入不等于运行59个测试。未扫描全target树或重跑成功旧阶段。

新patch只含该测试：5917 B / SHA256 `378f36a952c6bdf11bcb67ba6ead5f75afea6b82155091affbfdd0d902250a95`。私有593 baseline按最新Accepted manifest逐项从borrow-fixes after、labels frozen-overlay或B baseline具名取源并核bytes/SHA，provenance存本任务local；不遍历继承旧树。仅新私有目录Git init/index，无commit，不改工程.git属性，所有Git设置用每条命令-c局部限定。生成patch后恢复before，首次真实git apply --check exit0，首次真实git apply exit0；应用后只有身份读取，没有copy最终源掩盖结果。全部593逐条bytes/SHA等同source-after；replay-source 112856 B / SHA256 `e9791749f842e9403604f5fc3ff354e596536746577c65eb7b610374d7d69d16`。原始command/cwd/stdout/stderr在replay-commands.json，11414 B / SHA256 `6efdf2deadc12a6be4dce980a0242b3c97c0eb158758919023e5d1403182dc91`。这是本迁移新patch的首次重放，不重做旧成功replay。

每个成功物理阶段（test-executed/target/replay prepare/patch/check/apply/verify）立即journal并缓存hash；metadata后续步骤复用成功物理结果，未重复test/replay/hash。报告/handoff固定后单次封存own-inventory，roots仅本任务local、evidence、报告和两个helper；覆盖private-replay/.git真实叶子、journal/hash-cache及全部自有产物，唯一自排除项是own-inventory本身。seal后归还源码写权，不再修改本任务产物。

## 工具噪声与边界

本次唯一新增344B dumpstream为Surefire Boot Manifest-JAR绝对classpath与different root提示，单独归档diagnostics/并记录diagnostics.json，未纳入逻辑target。新raw61–64行保留既有测试的deprecation/unchecked INFO编译提示与路径乱码；不是新增断言失败，不将其描述为零工具噪声，也没有为清理提示改动runner或重编。Flyway输出为正常INFO。

这里只闭环一个旧Initial输入迁移候选。真实handler/security seam通过不等同完整status Controller HTTP/JSON或SSE验收；C功能、整体IAM、全回归与生产容量均不在范围。fresh reviewer后仍需root审计才可关闭这一个迁移待办。旧B final01历史65/1error保留，不把本次12绿倒填成旧65绿。
