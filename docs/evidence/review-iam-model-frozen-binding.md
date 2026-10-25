# Frozen model binding prerequisite — independent task review

## Spec Compliance

- ❌ Issues found：明确要求的事务 <=3 秒没有实际执行，见 I1。其余本次 diff 可验证的私有证明、摘要、租户选择、冻结元数据、授权刷新顺序和标签闭包符合任务要求。该判断仅限本次私有前置组件，不代表公开模型入口或整个分支验收。
- **Quality：Needs fixes。C0 / I1 / M0。**
- ⚠️ 实物、历史运行和资源结论由 root 独立审计决定；本评审没有把作者报告当作这些事实的替代证明。首次 canonical 打包执行偏差、历史 common/engine loaded-class 缺失、保护文档 7 原样 + 1 外部格式变化的独立判断见后文。

## Review identity and boundary

- Base：accepted consumer 660；交接引用 `.local/iam-model-input-consumer-review-fix/final-seal/source.json` SHA256 `de1ac03e734b8aae7288ecafe553dbc0bcaa7d4d2a82961a6d5f887910d03c4e`。未重新审核其历史补丁/清单。
- Head：control 引用 final667 source SHA256 `5e4a053394066ff115a617c89cd3b85cc58518d49de50e1f5a91265ab20f89b7`；660→667、7 新增 + 1 窄修改、其余659原样的实物全量比对由 root 负责。
- 完整窄 diff `.local/iam-model-frozen-binding/native-sealed/change.patch` 已按有界窗口一次读完第1–1288行至EOF；实际 SHA256 核对为 `19a35639d891a0f22a7962372467be878544c8cb3fbe986771e68a50a5f8a08a`（交接84296B）。没有另读任何变更文件全文，也没有重读旧 producer/consumer patch。
- 作者报告实际 SHA256 核对为 `b40f383f900f5bc1865fdc7b25b75d9542464cb864a01af9b995e7b30a694bb6`（交接22911B）；control 实际 SHA256 为 `bb84025839663c0eceedd5977fd302e754e2fe2fa5ee2c7e625b20930a3d5099`（交接3710B）。
- 已读本次评审简报、任务简报、冻结计划、root映射澄清、JDBC/clock/empty/artifact四份补充及 reviewer template。所有 shell cwd 为 `G:/207/项目/算法重组工具/代码/agent-platform`；没有邻接工程读取、网络/PG操作、测试/build/native重跑、源代码修改或子智能体。

## Strengths

- `model-gateway/src/main/java/cn/agentplatform/model/application/FrozenModelBindingVerifier.java:39`：入口先拒绝 ambient transaction/中断/null，随后调用真实私有 input 的有效性检查。`:47`–`:66` 在替换映射前捕获 accepted UTF8、request、manifest 和 retained；直接对原始 accepted bytes 求 SHA，未把科学计数数值经通用 JSON 重序列化后冒充原摘要。
- `model-gateway/src/main/java/cn/agentplatform/model/application/InitialModelInputVerifier.java:618`：唯一既有修改是包可见 `authorityForBinding()`，先 `requireVerified()` 再返回原 mapping；未添加公开证明构造入口。
- `model-gateway/src/main/java/cn/agentplatform/model/infra/FrozenModelBindingStore.java:18`：只使用 concrete store 的参数化 tenant/id binding SELECT；SQL CASE 在 JDBC 取字符串前限制256KiB，maxRows2，资源使用 try-with-resources。`FrozenModelBindingVerifier.java:149`–`:180` 对完整 strict tree 先验SHA，再核对 row/manifest/snapshot/provider 身份、精确字段集及整数、字符串、数组、协议和同权威 tokenizer。未查询可变 entry/provider/secret，也未接入 ModelCalls。
- `FrozenModelBindingVerifier.java:70`–`:100`：TransactionTemplate 返回后才执行最终 `requireInitialFor`；代码之后不再调用旧 input/retained，有 fresh purpose、caller/call/operation、owner/root、完整 initial ceiling、origin 连续性检查。`:104`–`:131` 以真实 retained 有效性和 exact binding 检查夹住 MaterialAuthorization；固定 signed observation clock 仅用于结构/覆盖检查，不代替 retained 私有时钟。
- `FrozenModelBindingVerifier.java:105`–`:128`：标签并集包含 request、initial、fresh actor、三个 parent 和 child，超过256拒绝；使用冻结 trust labels 并复用 ModelPolicy 的 empty→LOCAL。能力和输出优先级在`:183`–`:193`，没有新增 HTTPS-only 策略或 principal catalog ACL。
- `FrozenModelBindingVerifier.java:231`–`:246`：新结果 private constructor，每个公开 accessor 都执行 retained/exact-frame验证，metadata返回深拷贝，toString不含证明/请求/凭据。保存 accepted manifest，而不是旧 consumer 作为有效性委托；没有异步续期、发送许可或自动接线。
- `model-gateway/src/test/java/cn/agentplatform/model/application/FrozenModelBindingConsumerFixture.java:36`–`:57`：实际 ModelStore.createProvider/createEntry/freeze 后再加入 poison secret_ref；观察器`:117`–`:135` 委托真实 JDBC Connection 调用并解包实际异常，没有制作成功行。`:88`–`:100` 的 empty portal 例外仍验证真实签名 exchange，继而通过实际 engine.authorizeRetained/resume/model receiver/consumer，符合补充授权，不等于通用 gateway 支持空分类。
- `engine-runtime/src/test/java/cn/agentplatform/engine/infra/FrozenModelBindingProducerFixture.java:156`–`:189`、`:202`–`:239`：独立 engine fixture 使用真实 createPublished/prepareInitial、locked sources 和 producer assemble，比对实际 manifest request SHA；model HTTP test `FrozenModelBindingConsumerHttpTest.java:51`–`:66` 要求不同PID、实际 consumer proof及冻结绑定整体摘要，并覆盖0.00001。没有在生产实现引入跨业务JAR依赖。
- `FrozenModelBindingVerifierTest.java:18`–`:27` 的两条断言分别针对 old-SHA snapshot 内容变更和丢失 trust obligation；`:163`–`:176` 有真实 HTTP observer 正控和业务写入检查；`:158`–`:162` 有真实 PG lock timeout 后的健康帧正控。历史 RED 是否确实在 skeleton上执行、各epoch测试计数和运行身份仍须核对原始留存证据。

## Issues

### Critical — 0

无已确认 Critical 问题。

### Important — 1

**I1 — 3 秒事务预算仅被设置，没有应用到手工 JDBC，也未在事务结束前检查。**

- 位置：`model-gateway/src/main/java/cn/agentplatform/model/application/FrozenModelBindingVerifier.java:36`、`:70`–`:79`；`model-gateway/src/main/java/cn/agentplatform/model/infra/FrozenModelBindingStore.java:20`–`:30`。
- `tx.setTimeout(3)` 只把期限交给 Spring transaction resource holder。Store直接取得 Connection、createStatement/prepareStatement，然后各自 `setQueryTimeout(2)`；没有读取事务剩余时间、没有 `DataSourceUtils.applyTransactionTimeout/applyTimeout`，也没有自己的3秒 monotonic事务期限。回调里的 `checkTime(started)` 只检查整个 verifier 的8秒上限。两个 SET、SELECT、行读取等阶段的累计时间不会因为该配置自动受3秒限制。
- 具名外查确认了项目实际缓存 Spring 6.2.7 的行为：`DataSourceTransactionManager.doBegin` 字节码offset210调用 `ConnectionHolder.setTimeoutInSeconds`；`DataSourceUtils.applyTimeout` offset33才读取 `getTimeToLiveInSeconds`；`DataSourceTransactionManager.doCommit` offset42直接 `Connection.commit`。`DataSourceTransactionObject.isRollbackOnly` 委托 holder；`ResourceHolderSupport.isRollbackOnly` 只返回布尔位，实际 deadline 检查发生于 `getTimeToLiveInMillis/getTimeToLiveInSeconds`，不会被这里的commit自动触发。检查的是项目内依赖的实际字节码，不是外部文档推测。
- 可达路径：在事务已开始后、实际 SELECT前累计耗时超过3秒但未满8秒（现有透明 observer 的 beforeRead 可以提供这种延迟），随后SELECT在2秒内成功。没有任何3秒检查会阻止callback返回、commit、最终IAM刷新或mint结果。这是源码与实际框架字节码推出的失败路径；本评审没有运行该新用例，也没有把推断称作已实测失败。
- 现有 `FrozenModelBindingVerifierTest.java:216`–`:219` 只注入8050ms并验证8秒总预算，`:158`–`:162` 只验证单次锁等待；两者均不能验证独立3秒事务预算。作者报告 `docs/evidence/iam-model-frozen-binding-report.md:28` 对 transaction<=3s 的保证因而超出当前代码证据。
- 最小修复边界：在两份新增生产文件内把事务剩余预算与单条query<=2s/lock<=1s及整体8秒预算正确组合，在每个可能执行SQL的阶段应用，并在事务工作返回/退出前拒绝已过事务期限；保持sanitized503、实际资源关闭、中断状态与不重试。不应简单调用会以较大剩余值覆盖2秒query上限的API，也不应只在mint前检查8秒。新增一个事务内>3且<8秒累计延迟的拒绝用例与正常真实PG正控，断言拒绝后没有最终IAM刷新、无证明、连接归零；维持已有锁取消/8秒测试。普通DataSource无法强制抢占的既有私人/unwired限制仍需如实保留。

### Minor — 0

无独立 Minor 代码发现。测试中个别负例只断言异常类型/HTTP状态、源码有压缩长行，但本次未将其扩大为额外阻断或凭风格累计问题数。

## Execution deviations and evidence qualification

- **首次 canonical package是已发生的执行偏差，后续owned-copy成功不能使其合规。** 作者报告`:89`明确记录 canonical common/engine重编译、plain engine JAR写入、Boot repackage rename失败和一次移动尝试失败；受影响路径是工程内 `engine-runtime/target/engine-runtime-0.1.0-SNAPSHOT.jar`。artifact补充允许评审现状，不是追认首次命令。当前diff没有显示由此产生的业务源码变化；也没有证据可据此断言主进程无影响或已被破坏。故将它作为独立执行/环境审计事项，而不把未知锁持有者影响虚构成一个确定的安全严重度。root应核实失败命令/log/exit与后续无补偿操作，并保留明确限制；不能通过操作主进程/被锁产物或重跑绿测试消除历史。
- **没有 common/engine历史loaded-class封存。** 报告`:91`–`:104`区分 post-green7-plus-failed-package target与fresh exact-source owned package，未宣称后采集hash等于先前每个JVM已加载字节。该证据缺口无法追溯补造；源代码epoch、实际Running/XML/classpath/argfile、最终包身份仍能分别提供证据，但不能合并成逐历史class证明。root应分别确认相应身份，不把新probe/同源重编译充作历史attestation。本评审不会仅凭缺少历史逐类快照判定新实现功能错误，也不会因此把历史测试来源当作已完全证明。
- **保护文档外部变化。** 报告`:110`精确表述7/8 byte-identical及1处外部格式变化，并保留原hash/新hash、unknown origin/operator；与评审简报提供的root分类一致，没有伪称8份原hash全等。本次没有读写外部保护文档，339个非空行归一化等价由root独立审计承担。不能把作者的无写入声明当成actor鉴定；没有支持将外部变化归因于本组件的证据。
- **clock/empty例外**：上文代码检查支持它们严格限于私有验证/测试setup。HTTP集成的fixed fixture clock不证明墙上时间真实推进；专门测试使用受控可信clock推进验证retained expiry，整体8秒另用System.nanoTime。root应保留这种证据区分，不能把固定观察时间描述成撤销过期保护。

## Named outside-diff check and root audit limits

- 唯一代码风险外查：I1事务期限语义。仅对 `.tools/m2/org/springframework/spring-jdbc/6.2.7/spring-jdbc-6.2.7.jar` 和 `spring-tx/6.2.7/spring-tx-6.2.7.jar`执行只读 `javap -c -p`，查看上述5个类的有关方法；没有执行应用或创建探针，没有写入依赖/target。依赖字节码没有源码行号，已给方法名和具体instruction offsets。
- 已核对本次 diff/report/control SHA。未重跑tests/build/native，不重新执行PG/HTTP；没有重复审查旧producer/consumer inventory或完整补丁。既有受信consumer/AuthorityContext/ModelPolicy语义按本任务已接受依赖与补充约束使用；没有展开新一轮历史组件验收。
- root待核对：667全源与659原样、有限compiler/resource/JAR与owned copy一致性、实际launcher classpath无 sibling业务JAR；red2三项中2条真正assertion RED/正控、green7 20/0/0/0、green5/6旧73绿项目的实际epoch及源等价。不得把93项含fixture error的运行描述成整轮全绿。
- root待核对：实际JDBC/settings与HTTP正控和运行观察、源代码未引入measure/SecretVault路径的编译依赖闭包、real publication/prepareInitial/export、schema隔离、模型子进程环境过滤/私密文件ACL、六个owned DB/十二roles的注册和关闭、PID/listener闭合。diff能审查实现意图与调用路径，不能替代这些实际文件/资源证明。
- root待核对：失败canonical包原始log/exit、fresh owned package log/exit与历史class限制、7+1保护文档分类、inventory/report/control封存顺序及身份。任何这类实物检查的新差异应独立评判，不应由本报告的代码优点遮盖。

## Assessment and handoff

**Spec：Needs fixes（I1）。Quality：Needs fixes。C0 / I1 / M0。** 证明来源、冻结内容选择和刷新后有效性设计总体闭合，但明确要求的事务期限未实际施加，当前不能接受为满足全部资源边界的前置组件。修复应集中于该真实期限及必要测试，避免重新运行不受影响旧绿套件来修饰证据。

唯一写入为 `docs/evidence/review-iam-model-frozen-binding.md`。本报告封存后交还 **ALL review/report rights**；未持有source/target/helper/config/DB/HTTP/runtime写权，未启动后台任务。最终是否接受由root结合修复评审与独立实物审计决定。