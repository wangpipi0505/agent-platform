# O2a B 借用输出有限修复报告（追加 epoch）

状态 DONE_WITH_CONCERNS；作者完成不等于独立 Accepted。无 Git HEAD、无 commit。唯一 shell cwd 为 G:/207/项目/算法重组工具/代码/agent-platform。

## 范围与评审核实

已完整读取修复 brief、独立 review 至 EOF、原正式 brief、budget/parser-cache/inherited-test 三份决定、preparation、AGENTS、implementation-brief 与 contracts/README；使用 TDD、receiving-code-review、verification-before-completion、ponytail。未访问相邻旧工程，没有新增生产类、依赖或公开签名；v1.2、四契约、POM、迁移、HTTP receiver、C 功能与权限判断不变。

I1 核实：修复前 RunEventReader.java:161 直接 output.write(emission.payload)，外部覆写方法取得原自有数组；同一次 consume 可顺序多次 write。RED01 的真实 writer/response 用例保存收到的数组、先复制验证原 JSON，然后改写实际参数，第二次输出确实成为全部 ! 的字节；这不是反射返回类型或伪造 emission 的证明。

M1 核实：Budget 原 rootParserHasItsOwnFiniteDepthTokensStringAndNumberLimits 缺 root name exact/+1，生产 maxNameLength 已正确配置；新增唯一具名 parser 机械用例，不制造虚假 RED。

M2 核实：原报告134/187行对最终阶段 deprecation/unchecked 的归属不准确；旧报告冻结，下文只追加更正。

## 实现、所有权与相交账本

将原 Work.ledger 与原 lease 一起交给 EventEmission；生产新增的只是 ledger 引用和 Borrow 的 outputActive 布尔 guard。payload 输出在任何 clone 前用同一个 ledger reserve(payload.length+24L)，然后 clone，重新核 current/close，调用外部 write，返回后再核 live；try-with-resources 在实际调用返回或抛异常时释放该临时额度。24B 是部署标准对象对齐下的数组头与对齐保守余量，不是精确 JVM heap 测量。

内部 emission.payload 永不传入外部虚方法；sink 可保留自己收到的独立副本。无 flush/close 调用方流。payload/labels 两种输出共用 beginOutput，拒绝 payload→payload、payload→labels、labels→payload、labels→labels 重入；外层输出 finally 清 outputActive，故顺序多次输出和 IOException 后同 borrow 恢复仍可行。

原 model<=64MiB（含原编码数组、完整 parent/labels 及表示slack）、canonical<=8MiB、总量<=192MiB、两个 permit<=384MiB 继续同时适用。准备阶段原 parser 峰值64+48+64+8=184MiB、canonical峰值64+48+40+8=160MiB不变。输出时 raw/parser/canonical Reservation 已真实关闭；保守保留 model<=64MiB 与 fixed8MiB 原记账，再独立计一份最大262144+24=262168B副本，总额<=75759640B（约72.251MiB），且实际 ledger.reserve 仍逐次执行192MiB硬检查。没有再次许诺已占用的8MiB给payload副本。labels generator 的原固定小buffer仍在fixed8MiB内，由共同输出guard保证最多一份；cleanup空槽默认总<=36800B仍按原固定临时区。接收方自己保留副本的存储属于它的输出接收所有权，不恢复reader内部别名。

original encoded payload 长度仍由 Info.Measurements.eventBackingBytes 记录；totalReservedPeak 是 read/mint 前已测量的读取阶段值，不能冒充新增输出阶段峰值。旧 newMeasurement... sink 的58B参数观察在修复后只能证明输出copy长度，不能再当内部backing身份测量；该旧用例本次未执行、旧测量未篡改。输出预算由分配前ledger和互斥生命周期作上界证明，不声称新增了 JVM heap 实测。

active close 只标 closing，原 consumer finally 才清 payload/labels 和释放原 lease；未增加结果池、permit或只能输出一次限制。异常同时在输出finally释放copy/guard，在实际consumer finally归还原permit。

## 新回归与有限验证

三条新 PG 用例复用真实 PG 生产 writer、既有真签名/JWT/可信frame response，不mock数据源证明所有权，也不重写material历史。数据库固定 agent_platform_iam_o2a_b_material_v12，fixture先检查current_database，engine Flyway仍217，19项migration校验；凭据只经未改runner加载本工程.env进入子进程，未输出。

- payloadSinkMutationCannotChangeLaterWritesInTheSameRealBorrow：首次sink保存并改写实际收到数组，第二/第三次输出与首次原始已验证JSON完全一致，外层返回后继续改接收数组也不改变另份接收结果；sink显式拒绝flush/close。
- bothOutputMethodsRejectCrossReentryAndRecoverAfterSinkIOException：四种同借用重入拒绝且内层sink零进入；payload和labels各抛同一个IOException后仍可顺序输出；捕获与未捕获异常路径均清理，结束borrow拒绝再用，随后两个permit能被再次持有。
- blockingActualPayloadWritesKeepBothPermitsUntilConsumerFinallyReturns：两个实际OutputStream.write同时阻塞，close两个emission后第三次仍WORKSPACE；第一个真实consumer finally返回仅恢复一个permit，第二个仍占用；第二个真实finally后两个permit均恢复，第三个仍拒绝。
- rootFieldNameParserAccepts1048576CharactersAndRejects1048577：使用已有parse入口，exact字段可查到，+1拒绝；仅parser单项机械边界，不宣称同一JSON同时通过业务raw/model/total相交预算。

RED01发生在生产改动前，Budget1通过、PG3中2 assertion failures/0 errors（mutation/reentry），阻塞实际finally通过。GREEN01是+16头计费实现，Budget14与新PG3共17通过。随后因审查发现数组对齐slack，改为最终+24，仅追加GREEN02新PG3通过；Budget源码与parser未再改，沿用GREEN01的14项证据。三个阶段分开，不累加成24项、20项或一套17项最终全回归。原65、旧PG18、原成功样本和旧成功replay均未重跑。

### iam-stage-o2a-b-borrow-fixes-red01

命令（唯一cwd为本工程）：

```text
node .tools/run-iam-module-tests.mjs iam-stage-o2a-b-borrow-fixes-red01 engine-runtime RunEventReaderPostgresTest#payloadSinkMutationCannotChangeLaterWritesInTheSameRealBorrow,RunEventReaderPostgresTest#bothOutputMethodsRejectCrossReentryAndRecoverAfterSinkIOException,RunEventReaderPostgresTest#blockingActualPayloadWritesKeepBothPermitsUntilConsumerFinallyReturns,RunEventReaderBudgetTest#rootFieldNameParserAccepts1048576CharactersAndRejects1048577
```

exit 1；cn.agentplatform.engine.infra.RunEventReaderBudgetTest: 1 tests / 0 failures / 0 errors / 0 skipped；cn.agentplatform.engine.infra.RunEventReaderPostgresTest: 3 tests / 2 failures / 0 errors / 0 skipped。
raw：`docs/evidence/iam-stage-4-o2a-b-borrow-fixes/iam-stage-o2a-b-borrow-fixes-red01/iam-stage-o2a-b-borrow-fixes-red01.log`，12912 B / SHA256 `bb2c380013b03d36f14cf9bb3afe0dd50ddb5c418b5e9b627a5c0b72d0f5b042`。

XML：`docs/evidence/iam-stage-4-o2a-b-borrow-fixes/iam-stage-o2a-b-borrow-fixes-red01/TEST-cn.agentplatform.engine.infra.RunEventReaderBudgetTest.xml`，46128 B / SHA256 `83bc1ac8968c35fbbb109e3af1289e4cbf88dace373ca137695000ccb8201f1c`。

实际方法：`rootFieldNameParserAccepts1048576CharactersAndRejects1048577`。

XML：`docs/evidence/iam-stage-4-o2a-b-borrow-fixes/iam-stage-o2a-b-borrow-fixes-red01/TEST-cn.agentplatform.engine.infra.RunEventReaderPostgresTest.xml`，52203 B / SHA256 `a9297384000fdb944a6f2c41ba5d65da131845555c11bffdf27526c44f286b09`。

实际方法：`bothOutputMethodsRejectCrossReentryAndRecoverAfterSinkIOException`、`blockingActualPayloadWritesKeepBothPermitsUntilConsumerFinallyReturns`、`payloadSinkMutationCannotChangeLaterWritesInTheSameRealBorrow`。

### iam-stage-o2a-b-borrow-fixes-green01

命令（唯一cwd为本工程）：

```text
node .tools/run-iam-module-tests.mjs iam-stage-o2a-b-borrow-fixes-green01 engine-runtime RunEventReaderBudgetTest,RunEventReaderPostgresTest#payloadSinkMutationCannotChangeLaterWritesInTheSameRealBorrow,RunEventReaderPostgresTest#bothOutputMethodsRejectCrossReentryAndRecoverAfterSinkIOException,RunEventReaderPostgresTest#blockingActualPayloadWritesKeepBothPermitsUntilConsumerFinallyReturns
```

exit 0；cn.agentplatform.engine.infra.RunEventReaderBudgetTest: 14 tests / 0 failures / 0 errors / 0 skipped；cn.agentplatform.engine.infra.RunEventReaderPostgresTest: 3 tests / 0 failures / 0 errors / 0 skipped。
raw：`docs/evidence/iam-stage-4-o2a-b-borrow-fixes/iam-stage-o2a-b-borrow-fixes-green01/iam-stage-o2a-b-borrow-fixes-green01.log`，8633 B / SHA256 `c2b98093bd0f55b4d4ea2dfffa07a18e7f2eda903ac1f4840bfbbda86e804420`。

XML：`docs/evidence/iam-stage-4-o2a-b-borrow-fixes/iam-stage-o2a-b-borrow-fixes-green01/TEST-cn.agentplatform.engine.infra.RunEventReaderBudgetTest.xml`，48257 B / SHA256 `b08b4d311eca19bf163be6fbb18a64b507f6e4936d9b2bf70a0cde27acbfe3ef`。

实际方法：`scopeArraysRejectDuplicateHashesWrongTypesAndParentCountBeyond4096`、`captureInitialCursorAndRemainingAreLiveLocalOperations`、`canonicalExactBoundaryAndLongAccumulationOverflowFailBeforeExpansion`、`subMillisecondConservativeBudgetNeverRequestsAConnectionOrDisablesTimeout`、`metadataStreamingParserChecksEverySingleConstraintBeforeDom`、`parserCancellationChecksRealCaptureAndFieldNamesConsumeTokenBudget`、`deploymentRepresentationModelCanonicalAndTotalBudgetsAreIndependentHardLimits`、`emissionAndBorrowCannotBeMintedPubliclyOrExportLargePayloadReferences`、`rootFieldNameParserAccepts1048576CharactersAndRejects1048577`、`getterClosureExpiryAndGenerationFailureAreLocalAndSticky`、`modelIntersectionIsAdmittedBeforeSecondDomAndTemporaryReservationsUnwind`、`cumulativeByteRecordAndParentBudgetsRejectOverflowAndNeverSilentlyTruncate`、`normalizationExpansionHasIndependentBudgetBeforeDecimalToIntegerExpansion`、`rootParserHasItsOwnFiniteDepthTokensStringAndNumberLimits`。

XML：`docs/evidence/iam-stage-4-o2a-b-borrow-fixes/iam-stage-o2a-b-borrow-fixes-green01/TEST-cn.agentplatform.engine.infra.RunEventReaderPostgresTest.xml`，48291 B / SHA256 `751bbd5ac1a784f5bc3f697476cc54465dcfdcc6169faf8dbcdcf2050e3209c6`。

实际方法：`bothOutputMethodsRejectCrossReentryAndRecoverAfterSinkIOException`、`blockingActualPayloadWritesKeepBothPermitsUntilConsumerFinallyReturns`、`payloadSinkMutationCannotChangeLaterWritesInTheSameRealBorrow`。

### iam-stage-o2a-b-borrow-fixes-green02

命令（唯一cwd为本工程）：

```text
node .tools/run-iam-module-tests.mjs iam-stage-o2a-b-borrow-fixes-green02 engine-runtime RunEventReaderPostgresTest#payloadSinkMutationCannotChangeLaterWritesInTheSameRealBorrow,RunEventReaderPostgresTest#bothOutputMethodsRejectCrossReentryAndRecoverAfterSinkIOException,RunEventReaderPostgresTest#blockingActualPayloadWritesKeepBothPermitsUntilConsumerFinallyReturns
```

exit 0；cn.agentplatform.engine.infra.RunEventReaderPostgresTest: 3 tests / 0 failures / 0 errors / 0 skipped。
raw：`docs/evidence/iam-stage-4-o2a-b-borrow-fixes/iam-stage-o2a-b-borrow-fixes-green02/iam-stage-o2a-b-borrow-fixes-green02.log`，8419 B / SHA256 `b8a9fd287233fb828773e609db4b98144de6803e2d7b065b853b438229c847c1`。

XML：`docs/evidence/iam-stage-4-o2a-b-borrow-fixes/iam-stage-o2a-b-borrow-fixes-green02/TEST-cn.agentplatform.engine.infra.RunEventReaderPostgresTest.xml`，48266 B / SHA256 `2a45794448b73719e0404a0cb9ed1b69b47a2aee3835c55c0d4714976f7036b4`。

实际方法：`bothOutputMethodsRejectCrossReentryAndRecoverAfterSinkIOException`、`blockingActualPayloadWritesKeepBothPermitsUntilConsumerFinallyReturns`、`payloadSinkMutationCannotChangeLaterWritesInTheSameRealBorrow`。

## M2 阶段归属更正与噪声

撤回原报告 docs/evidence/iam-stage-4-o2a-b-material-reader-report.md:134、:187 将 deprecated/unchecked 提示归属 final01/final02 的说法。两份实际最终raw具名逐行查询 WARNING/deprecated/unchecked 均零匹配；final02 compile/testCompile 的实际 Nothing to compile 在29/35/53/59行，28/34/52/58为compiler阶段标题。final01也为Nothing to compile；Flyway从final02:70起明确INFO，不能当warning。未追溯早期编译提示、未为文案重编译旧阶段。final02:32/56实际乱码及final01:233异常乱码按原文保留，例：`G:\207\��Ŀ\�㷨���鹤��\����\agent-platform`、`��ǰ��Ȩ�������˲���`；机器更正与原件身份见m2-correction.json。

本次RED01/GREEN01/GREEN02确因新增test/source触发compiler；新raw包含-Xlint:deprecation/-Xlint:unchecked的INFO提示与路径乱码，不挪给旧final。Flyway为正常INFO。3个本次344B dumpstream 是Surefire3.5.3 Boot Manifest-JAR绝对classpath、different root提示，各自单独归档diagnostics/并列于diagnostics.json，未加入逻辑target。未修改runner消除噪声。

## 文件身份与物理交接

修复前593清单：112856 B / SHA256 `21fc2a4425e5d0767cb3627719ddfce395c97b9ca3718ebb205a18c90e0e9439`；修复后593清单：112856 B / SHA256 `83e1fc79f72a4b21bdeb75a3cc41b7d0406797070f25e4242fc74407b42f1a20`。仅下列三项更新SHA，其余590项逐条继承，无源码全树重扫。三份before/after精确源分别存.local/iam-o2a-b-borrow-fixes/before和after。

- `engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunEventReader.java`：before 26164 B / SHA256 `68f5f224299feb7165a5682df4042c7472591eaf65f481da9d3c3278f06d1dea`；after 27347 B / SHA256 `cf8eb6e464eb2734c26917a014df5e86bc1dfab86f60c025433357f3a846fe1a`。
- `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunEventReaderBudgetTest.java`：before 11540 B / SHA256 `2fcfdf5522037f323ca4d66bac1904425a8de45cd43dc59af7163bbef6af4afb`；after 12011 B / SHA256 `880a9f7cf6bdcf403e58ec4c6e894ba25ea5d2846039c04e6550afb9055cf2df`。
- `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunEventReaderPostgresTest.java`：before 31652 B / SHA256 `9262901bd1b776a902c8ad2cc6846c02966f1c131497c9b610ae1479f2198736`；after 38346 B / SHA256 `fad3c00524c87028f672a12f2ae6933300152b9f82c983649d7686580bc2c65c`。

本次target为admin-common/engine实际compiler createdFiles/inputFiles、有限manifest资源、common test-fixture JAR、actual Running XML+txt共464项有限overlay；基线labels-fix2054，覆盖后逻辑2060项，历史dumpstream继承不重扫。Budget当前XML来自GREEN01、PG来自GREEN02，阶段归属明确；物理copy在local/target-overlay，未宣称它是全target树。overlay：102123 B / SHA256 `462b615e5757f7c202310bb19c67afdef645e39b4d412f5279289b3c17a72ef2`；target-after：427783 B / SHA256 `a23b333e7e0efd1f4d74cfac75804d9c791a3f9bbc4905c3ca92b132009e1fac`；compiler-inputs：66786 B / SHA256 `58a344157c0aa141170fdf81d145147963e1708ae9d7d75526820a3230f23add`。

新三源码patch：15166 B / SHA256 `3ea1fc2987146cd79b1cbc004c01ead042785f1fc7a582ad78860da67d0773f5`。私有replay由修复前593清单，逐项从已冻结baseline/labels-fix overlay构造，核每项before身份；仅私有Git index，无commit，所有git设置每条命令局部限定，不改项目Git属性。首次git apply --check与首次apply均exit0，原始command/cwd/stdout/stderr保存在replay-commands.json；apply后只有读取hash，没有后拷修复源。全部593名称/bytes/SHA精确等同source-after，replay-source清单112856 B / SHA256 `83e1fc79f72a4b21bdeb75a3cc41b7d0406797070f25e4242fc74407b42f1a20`。这是本次新epoch首次replay，未重跑旧成功replay。

journal和hash-cache记录已成功physical phases；本次未有helper失败或恢复重测。test-results.json精确列actual Running及全部XML方法，未把编译文件数当测试。交接文件为本evidence目录handoff.json和own-inventory.json，inventory唯一自排除项仅它自身，roots仅本任务local/evidence、报告和两个本任务helper；含private-replay/.git真实叶子。report/handoff先固定，再单次封存inventory，交还后不再修改。

## 仍有边界

本次只修I1、补M1、追加M2更正；旧Initial测试迁移及既有final01的65/1error未闭环结论不变，不能声称全回归绿。既有历史RED06 XML为0 assertion failure/1 runtime error，未沿用旧摘要1F。C真实HTTP/SSE、跨服务IAM、所有expiry/renew阻塞排列与生产容量均继续未验收。本次sink为真实同步输出回调，不是C HTTP流验收；表示预算为保守模型而非精确对象heap、GC或全引擎生产并发保证。独立review/root仍需据本epoch有限产物决定Accepted。
