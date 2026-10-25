# C2a 启动所有权、原请求作用域与容器保留边界修正

状态：FORMAL_REQUIREMENTS_FROZEN；只在 root 显式派发后授予两路径写权。原作者已交还全部写权，原 C2a 尚未 Accepted。沿用 v1.2，四契约与时间不改；只使用本新工程和隔离端点，不读取或关联相邻旧工程。

## 范围

读原 C2a brief、actual-api-supplement、decoder-type-amendment、abort-profile-amendment，再读本 brief 与 root 的 wrapper-retention 补充，后者修正原 1218 个包装对象计数论证的前提。原作者报告与独立 review 为事实输入，不是豁免：

- 原报告：docs/evidence/iam-stage-4-o2b-f2c2a-sse-report.md。
- 独立 review：docs/evidence/review-iam-stage-4-o2b-f2c2a-sse.md，C0/I2/M1。
- root 原 artifact 审计：.local/iam-o2b-f2c2a-root-check.json，六项 PASS；未关闭代码缺口。

唯一源码写权为已有两个文件：

1. platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalRunEventsSse.java
2. platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalRunEventsSseLifecycleTest.java

source630→630，两个修改，其余628逐字不变。不得改 Http、Verifier、Budget、Decoder、common、engine、Controller/Assembly、前端、POM/main、设计/四契约/八索引及原 C2a/旧 C1 owned/report/review。如果必需新增写路径，先向 root 提供实际原因；不得自行扩大。

新 owned 为 .local/iam-o2b-f2c2a-fixes/、docs/evidence/iam-stage-4-o2b-f2c2a-fixes/，helpers 仅 .tools/iam-o2b-f2c2a-fixes-*。新报告为 docs/evidence/iam-stage-4-o2b-f2c2a-fixes-report.md。原报告属于已冻结清单，本次另写 fix report 保留其身份。LifecycleTest 中运行目录需最小改成新的 fixes/peers，避免其既有 helper/child 向旧 owned 写入。

## I1：从接收 Session 起闭合失败所有权

start 接受真实 Session 后，lookupMetadata、beginSse 和异步 bootstrap 任一阶段异常都必须有一致、有限且正确的清理。不得在未返回 Handle 的情况下遗留 Owner，也不能靠 fixture catch/finally 补救来掩盖生产入口缺陷。

尚未开始 Servlet 异步生命周期的 Q/lease 失败应关闭本 Session，并等待真正已启动的 transport/timer/worker退出后归还费用；不能先退 Owner 再等资源退出。已建立 lease/异步生命周期的失败仍沿用 completion/action/IO/heavy/borrow/worker 门，不能用统一 catch 粗暴释放，也不能合成 onComplete。

先用旧生产字节写真实 Q-403 与真实 Q transport failure 的失败测试。断言无 Handle、无 setter/新 reader 残留，原 Session 有限关闭，最终 owners0/global1048576；重复失败不耗尽 admission，后续正常请求仍可开始。旧 RED 清理可以在断言完成后显式 finally 关闭原 Session/容器，必须记录该补救属于测试收尾，不能放在观测前。

## I2：入口必须仍处于原真实 REQUEST

在消费 Session/Q、启动异步生命周期或创建 worker 之前，检查当前 private bridge scope、origin processor、原 socket、当前 REQUEST epoch 及原请求线程与该 token 一致。只有从真实 ContextValve mint 的 attribute 类型还不够；dispatcher 也必须符合原始 REQUEST。采用现有受控错误路径默认拒绝，不引入 caller 身份/许可或伪造 scope。

失败必须与 I1 的资源所有权一致。token 可单向失效/清引用，不能转向新 socket/epoch，也不得允许持有 attribute 的应用 worker 重用它。

新增真实 Servlet 把原 request/response/Session 转交独立 worker 的失败向量，证实在任何 Q/async/worker/setter之前拒绝且费用有限归还；覆盖必要缺 scope/错误 dispatcher 情形。使用真实 connector、Valve、request，不能 mock request/手工 new token 来替代该证明。旧实现可能会进入危险 setter：用有界真实 probe/独立 child 保留失败并确保收尾，不为 RED 留无限死锁。

## 容器保留边界

按 root wrapper-retention 补充实现最小修正。不能以当前 waiting set 的 cardinality 直接推导所有历史强引用上界，也不能通过禁用 Tomcat async timeout、无限 registry、累计创建上限或新线程/队列来规避。

保持完整 11 个 Processor 方法及实际 timeout/generation 语义、真实 socket 最外锁与回收前失效、单 JVM 终身一个受控协议实例、1024/64/64 固定配置和固定64桥、预收1MiB直到 JVM 退出。原受控 token 在失效时清 origin/response/socket/state 的行为必须保持；活动 action 在清引用前捕获原关联并在原 socket 下重新校验，不得产生迟到 action。

## 必要验证，避免重复全矩阵

新增 I1、I2 与容器保留键/反向边的真实失败路径先 RED，再实现。原成功 29 项、历史117 child及原六项 artifact 结果保持原件，不重跑全29、旧C1原19/fix8、PG/provider/原 replay。

最终只运行新增向量及与本次修改直接相关的既有 LifecycleTest 方法：

- originalRequestBootstrapAndContinuousCursorTerminal
- firstErrorAndLateFirstWindowCannotReviveRegistration
- sameProcessorOnAnotherSocketCannotReceiveOldAbort
- lateAbortCannotTouchReusedKeepaliveRequest
- forcedConnectorReleaseInvalidatesTokensWithoutInventingCompletion
- stalledActualActionRetainsTwoOwnersUntilActionAndCompletionExit

若实际更窄修改使部分既有方法无关，可向 root 说明；若发现新生产问题，应按实际影响选择新增覆盖并记录原因。不得为最后统一数字或补元数据重复已绿集合。首次共同受影响矩阵后，若只修测试 oracle，继续只验该受影响向量。

费用区分保持：正常 lifecycle owner0/global1048576；无 protocol 的 child global0；真实强制回收无 completion 保留两个 Owner至实际 JVM退出，不伪称完成/退款。真实单 lifetime 限制要求各独立场景用 fresh child，不能同 JVM 反复建 protocol。

## 注释、来源与证据

注释解释 Session 接管/失败边界、真实 REQUEST 检查、超时集合键的引用关系以及 token 单向清理，不逐句翻译。仅据已经阅读的官方固定版本源码写开源对照；原配置不升级。

继承 root 已验的 frozen630副本与target逻辑2295，建立本次独立实际 Git before630/after630与完整名字/bytes证明。binary attrs 在第一次 add 前设置；plain check/apply各只一次，不用辅助 replay index/应用后copy。每个实际阶段立即缓存身份、raw/Running/XML/testcase/真实compiler名单与对应有限 target；元数据错误只恢复未完成部分。有限 target 只 actual compiler inputs/created outputs、resources、common fixtureJAR、实际 Running 报告，不枚举 whole target/classpath。所有 child真实 waitFor/collector退出（包含失败），不把取消flag当完成。

报告列修改、真实 RED/Green、命令/阶段、子进程退出、源/target/补丁身份与限制；原 warning 噪音 M1保留，不扩大配置修噪范围。交还时三个控制身份 report/handoff/inventory及全部source/target/owned写权必须明确冻结。然后由原 reviewer复审delta和root有限审计，未通过前不签收、不更新八索引、不接公开EVENTS或部署。


正式root容器补充：.local/iam-o2b-f2c2a-wrapper-retention-amendment.md；对应source-only分析为 .local/iam-o2b-f2c2a-wrapper-retention-analysis.md 与 .local/iam-o2b-f2c2a-wrapper-slot-gate-analysis.md。root正式选择1536个original-delegate phantom bundle slots/512B每bundle/128KiB固定图；旧1218假设不再作为预算依据。gate saturate真实factory、burn与实际reference queue回收分开观察，测试内一次显式GC见证已在补充中预授权，生产不调用。所有精确身份由 .local/iam-o2b-f2c2a-fixes-frozen-inputs.json 固定。
