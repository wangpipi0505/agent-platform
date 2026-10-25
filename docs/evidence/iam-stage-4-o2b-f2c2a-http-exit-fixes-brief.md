# C2a Http heavy-helper exit refund gate fixes

本任务是同一C2a的第四波窄修正。按当前v1.2计划持续实施，所有工作仅在G:/207/项目/算法重组工具/代码/agent-platform；不读取、引用、复用、导入、链接、修改或连接相邻旧工程的代码/配置/数据/进程/依赖。用户明确暂无真实模型、本体/IAM/K8s，使用本工程隔离端点即可，无需再请示。

先读本brief和`.local/iam-o2b-f2c2a-http-exit-fixes-frozen-inputs.json`。正式问题为原review `docs/evidence/review-iam-stage-4-o2b-f2c2a-sse.md` 第三波append中R-I1/R-M1；root已完整读19942B追加，full42473B SHA caeca0717826f2fc954853a20ee94706b5e8f52ae6ee62d1922693edebea7bec。其余继承M日志噪音原样记录。上一波7062 owned与六phase/8103physical审计PASS只是artifact，不能当代码Accepted。

## 精确写权与约束

- 仅改两个既有source：`platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalRunEventsHttp.java`及`platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalRunEventsSseLifecycleTest.java`。source630→630、2修改、其余628完整名字/bytes/SHA exact，无新增source。若遇真实额外必要路径，先报具体原因，不能自行扩大。
- 可更新由这两个路径引起的有限target及本任务新owned：`.local/iam-o2b-f2c2a-http-exit-fixes/`、`docs/evidence/iam-stage-4-o2b-f2c2a-http-exit-fixes/`、`.tools/iam-o2b-f2c2a-http-exit-fixes-*`及独立报告`docs/evidence/iam-stage-4-o2b-f2c2a-http-exit-fixes-report.md`。Lifecycle child根切到本新owned，不污染旧peer目录。每stage启动前assert新owned和E常量。
- 上一波source630是6d4e023e2ab5f5f5fbbc3bd386bd967b67c67c546872ccb0ba408ca6f66c32d8；target历史逻辑2298是32b310dbcfc0c58858f3d4148a18b447d93b2f2b1017d8e6f050b1e050f095d8。完整immutable基线`.local/iam-o2b-f2c2a-retention-fixes/primary`，其630源文件已root实际校验，复制时排除.git。旧before/source/target/nativeGit/replay/14671+8643+7062 inventories/报告/所有成功失败epoch不可改写或重做，旧宽helperglob成员按冻结epoch继承。
- 不改Sse/Budget/Verifier/Decoder/common/controller/assembly/frontend/其它tests/POM/main/原v1.2/四外部契约/八索引及执行文档；不commit/deploy/restart任何旧main，不git add -A。不得引其它业务JAR或反射load sibling target。
- Java21/Boot3.5.0/Security6.5/Nimbus9.37.3/SpringAI1.0及原HTTP/JSSE/Tomcat固定profile不变。当前共享1536名额/ReferenceQueue机制、final Session和State锚唯一Handle、3MiB baseline与1792B信用额度、单JVM终身一个protocol、1024/64/64/64桥、11SPI/真实socket外锁/epoch/recycle均不得削弱。PG仍业务权威，本任务不需PG。凭据仅secret_ref、sourceToken不日志不输出。

## 必须修正

R-I1已由root具名核immutable Http Session字段和555–714段确认：stopTransport在Session锁内复制handle/request到c/r，锁外还要使用；合法权限取消可直接调用Session.abort，不必在State.actions之内。另一个实际completion/awaitClosed可先完成transport、清字段、Owner.close，第一helper仍持需使用的c/r。finishTransport同样捕获closingHandle/request/alarm/timer/client/manager，在自身finally清cleanupActive并notify后尚未真正return，另一awaitClosed可能退款。TLS连接terminated只证明其内部物理退出，不证明这些Http祖先已返回。

修复要求：全部相关transport-snapshot helper真正返回前，原HTTP/private/Owner重图费用必须保持。把含重快照的cleanup工作放入实际返回的inner helper；只持标量/Session闭壳的outer在其真返回后才发布transportClean和解除cleanupActive。stopTransport在任何重快照前参加与退款互斥的准入/退出门，重引用helper真返回后outer才释放该门；已有并发stop、cleanup、awaitClosed不得穿过窗口。允许采用最小明确boolean/counter gate；无需设计通用框架。若用拒绝重复cancel/与唯一cleanup互斥的方式，需证明唯一在途取消/cleanup负责实际唤醒，不让stop因为门拒绝而遗留实际IO无唤醒。已有超时/中断/失败分支继续持费、解选举、可重试。

Session/State/pool/connection锁内不join、不等待库close或任意callback；不得反射私有JDK/Tomcat。保留真实active/operationThread/decoder借用/SSE lease/material/worker/action/completion/slot门；不合成onComplete、提前退lease/Owner、不把transport/Thread/Frame/任意Throwable/testhook捕获算1792闭壳。重图局部、任意probe和callback应在真实helper返回前处理；outer不得把含重图的Exception/record/lambda结果带到退款之后。失败捕获与传播需遵从同一生命周期约束，不能靠本机JIT死局部提前消失证明。clean/closed快路径不可再抓transport。锁顺序和有限deadline保留。

新增仅少量标量/测试hook字段应列实际javap与闭合图ledger，Session allowance仍384B；整个1536×1792+128KiB+4×1792=2890752<3145728和两Owner+baseline200576852<201326592原有限模型不变。不能重新主张全JVM/RSS/native/GC期限。

R-M1：`inertReadyIdleShellAllowsMaterialFeeExitButRetainsFinalOwner`的lightShell当前中间assert/evidence仍7218374B，实际应为9315526B。以`connectorBaselineBytes()+6169798L`之类的具名分项表达，并写真实观察值，避免新magic string或只改末尾baseline。仅该受影响方法验证，不跑全29。

## TDD与限定验证

先冻结实际native before630与完整源名，再加关键失败路径。需两个真实Session并发向量：①真实Session.abort→stopTransport在已复制c/r且尚待取消/abort使用时暂停，同时另一真实completion/awaitClosed尝试，断言原重费用仍持有，helper退出后最终回baseline；②两个真实closer，暂停真实cleanup的reference-bearing helper近返回边界，另一closer不能先退款，helper真返回后才可完成。使用隔离TLS/HTTP/实际Session与真实resource exit，不以caller自造flags/假完成替代。最小bounded testhook只观察/暂停具名阶段、正常为null、在原费用释放前切断；不用生产callback模拟资源退出。

旧生产行为RED允许先加入窄testhook及测试代码，但不得在RED前加入新gate/新清洁发布时间改变生产行为；保存完整sourceEpoch和hook-only说明。不能用新API缺失编译错误或旧baseline不匹配冒RED。若实际同步条件使某负向量不可达，先报告具体门谓词，不强行伪造失败。真实child按原有privateSTDIN隔离launcher运行，自有module classpath，不连接旧环境；每个真实waitFor/collector/exit必须留档。

两并发向量修正后，只跑这两项、R-M1单项、原`actualAwaitClosedAncestorPinsRefundedShellCredit`及`firstErrorAndLateFirstWindowCannotReviveRegistration`共5方法作为本次affected matrix（若真实新增代码失败可按新问题再窄修、只跑受影响方法）。不重跑原7/10/14/29、mixed/GC、cap/burn、原审计/PG/provider。**本波不准调用测试或生产GC**。root/reviewer不重复作者绿测；每次source/fixture确实变化才允许相应新验证，不为补报告或metadata重复命令。

## 过程、证据和交还

所有成功phase立刻缓存命令/exit/hash；metadata失败保留原helper/错误/成功cache，只续未完成。不要全量输出manifest/rawpayload/JWT/secret。Windows复制/移动/删除用单一PS native、先校绝对workspace目标；尽量不删除。

最终自审完所有改动再做一套native630 before/after Git tree及全实际blobs、两path binary patch，plain apply --check一次与apply一次均需实际exit；无replay辅助index、apply后不copy。成功后不能因小修重做同一套；有实质新修则保留已成功epoch并仅增量新链。无commit。

有限target仅actual compiler input/created名单、明确source-bound资源、commonfixtureJAR、actualRunning；common NothingToCompile应归继承artifact而非新compile。对继承2298逻辑manifest overlay，不能扫全当前target/classpath冒新编译。保留原owner规则及所有timeout/log/thread关闭噪音。原epoch与本次RED/GREEN不合并为同head数字。

报告精确说明改动/原两时序/新gate与异常重试/实际测试命令和结果/真实进程退出/field ledger/source630及target有限边界。handoff提供sourceAfter、targetOverlay/targetAfter、privateGit、stageCatalog、peerExits、observations、冻结状态和所有file/bytes/SHA。own-inventory包括全部新owned/helpers/report/handoff，仅排自身一个inventory。返回三个control实际bytes/SHA、剩余concerns，明确4类写权全部冻结交还root。不得自行签Accepted或改八索引；原reviewer complete2delta复审及root有限增量审计后再裁定，继续C2b/C2c。
