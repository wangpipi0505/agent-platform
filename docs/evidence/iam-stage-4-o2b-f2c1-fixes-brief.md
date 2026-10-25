# C1 I1单writer准入修复与M1可读性

固定cwd G:/207/项目/算法重组工具/代码/agent-platform，不读/关联/影响相邻旧工程。用户授权按计划持续实现及隔离测试；不问确认。v1.2/四契约/时间不动，无commit/deploy/主服务重启。遵守AGENTS、实现brief/contracts、receiving-code-review/TDD/verification/ponytail及SDD implementer规则。

完整读原C1正式brief（docs/evidence/iam-stage-4-o2b-f2c1-material-brief.md）及其要求、原报告、fresh review docs/evidence/review-iam-stage-4-o2b-f2c1-material.md和两root测试澄清。本轮只修review I1并同文件处理M1，不扩成新功能。

原candidate源码627/target历史logical2264及9460owned已root六phase独立审计PASS，但代码review C0/I1/M2，不得写成Accepted。sourceBefore .local/iam-o2b-f2c1-material/source-after.json，123392B/0802f48d8e4071cf3f80ec667bd262e7cd7cb992411f6c4217487f401f06c84a，完整immutable source-after目录已root核，继承其原目录proof；targetBefore同根target-after.json，485745B/d247ca387b7220370d707782b4d461ba2deea9e0684b493270f7d22222f2c698。新inputs将冻结before与root审查身份。

## 唯一写权

只改gateway infra/PortalEventsMaterialVerifier.java和test同包PortalEventsMaterialAuthorityTest.java；不新增source，627→627，其他625 exact（尤其Http、既有VerifierTest、固定resource/common/TLS/F1/engine/pom/main/controller/assembly/frontend/原设计/八索引）。原C1 source/target snapshots、所有阶段/报告/handoff/inventory/review保持冻结；只写自己的fix roots/helpers/报告。没有其它source/target writer。

## I1要求

现writePayload仅activeWriters++后锁外执行，允许同window并发/重入排序/精确数值临时图，单份80MiB无法覆盖。**在进入任何payload遍历/排序/数值转换前原子限制每window最多一个active writer，对并发及重入立即有界拒绝**。复用既有window monitor/admission即可；不建generic semaphore/framework、逐writer队列或增加预算，不按创建线程亲和修复，不持window/Session/holder monitor跨generator callback。保持首writer可跨线程合法使用。

closing后拒新调用，首writer仍在实际工作时close/interrupt/cancel不能退费；actual writer返回并清引用后才能释放。失败准入不能错误递减首writer计数/退款，首writer异常finally恰一次清准入并唤醒关闭；既有真实Session/G/receipt/P3/guard/原t0均不改。源注释说明单份F费用只覆盖一个重遍历，不做逐句翻译。

## M1及保留M2

仅本Verifier中schema各Shape声明和factory链按字段/配置分行，保留全部token顺序/字段/数值/Unicode/recycler/duplicate选项语义；不另建框架/拆新增source。报告给format-only范围和保持语义依据，源diff清晰。M2为既有javac乱码/deprecation，保留原诊断，不改runner/POM/关闭检查/为消噪重跑。

## 必要实际验证（不倒跑原19）

先新增两个真实window测试，建议方法sameWindowConcurrentWriterIsRejected与sameWindowReentrantWriterIsRejected（可选更清晰名），通过既有真实issuer/Q→G→child→receiver/receipt/C1链；保持原classpath asset/测试peer。旧prod先跑这两条，取得真实缺失准入RED；最多两条writer，禁止无界fanout。

并发：首writer在实际writeString/writeNumber遍历内barrier阻塞；同window第二writer尝试并在进入其遍历/临时图前拒绝，不能只是排队等首writer。其generator必须零调用，首writer/Owner费用继续保留；实际release/退出后关闭归零，可另验证后续顺序使用仍合法。重入：实际generator回调尝试同window.writePayload，内层有界拒绝，未再入遍历；外层能正常完成或按实际异常退出，计数不负/不退早/不死锁。保留首writer跨线程合法与关闭/interrupt持费。不能只用手造计数器/伪window或私有反射。

实现I1＋M1后，一次最终**受影响矩阵**：两个新测试、已有PortalEventsMaterialAuthorityTest#twoActualBorrowedWritesKeepFeesUntilWorkersExit，以及未修改PortalEventsMaterialVerifierTest全部5项。当前建议8项，实际selector/计数如实记录，不预报通过。selector用逗号分列同class#method，避免既有runner不支持的加号组合；编译/selector失败诚实分类，不冒业务RED。不要先green这些再重复全Authority14/原19或额外全suite。原19属于原candidate epoch，修复后8属于新627 epoch，不能加成同场27。若实际发现更广行为变更，先向root具名说明再扩大必要回归。

## 证据与交还

ownedRoot仅.local/iam-o2b-f2c1-fixes/与docs/evidence/iam-stage-4-o2b-f2c1-fixes/、具名.tools/iam-o2b-f2c1-fixes-*、报告及实际wrapper日志。每phase成功hash/退出先即时cache/journal再后继assert；metadata只续未完、保留原helper/失败raw，不重跑成功测试/编译/PG/provider/Git apply。

源码before627→after627、两修改/其余625 exact；每stage只两变更副本，625继承原root已验清单。固定资源不改/不重新提取E/不重hash旧vector。主privateGit精确before627 actual blobs/tree，attrs先add、隔离config/template；独立replay预存before627名字/bytes后，首次plain check/apply各一次0、完整after627精确，无辅助index/after补copy。报告patch/replay是本fix新epoch，不改旧成功proof。

finite target仅actual compiler input/created lists/resources/common fixtureJAR/Running，从原C1 logical2264继承；记录actual compile还是up-to-date及新logical计数，不wholecurrent/classpath/部署。diagnostics另列。所有自有child actualwaitFor+collector、worker/callback退出，保持twoOwner收费。

写docs/evidence/iam-stage-4-o2b-f2c1-fixes-report.md及自有handoff/inventory，冻结source/target/owned并交还。短报DONE类别、控制三身份、实际RED/最终affected测试及顾虑。root审计delta与原reviewer复审I1/M1，均闭合后才限定C1cert/八索引，再C2。无需提交代码。
