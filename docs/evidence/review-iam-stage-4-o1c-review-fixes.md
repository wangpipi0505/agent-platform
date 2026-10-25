# Spec Compliance

- **✅ Spec compliant。原 I1 已解决；本轮 C0 / I0 / M0。** 只修改授权的 `PublishedRunHttp.java` 和 `RunObservationGetTransportTest.java`，没有新增/删除源码。原完整 O1c 审查结论继续适用，本次仅复审两个文件的冻结 delta；必要源码仍585（backend393/frontend192）。
- **✅ 全部提前终止出口有覆盖。** `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PublishedRunHttp.java:244` 开始的整个 response handler 被 `:288` 的 IOException/RuntimeException catch 包住：非200状态、unsafe headers、Content-Type、entity/content 获取、declared length、最终 proof/deadline 检查及严格 retained-error 解析失败，均在 Apache 自动 response graceful close 前取消实际 handle，再原样抛出。取消由 `:291` 的 observation policy 条件限定。
- **✅ 内层 stream close 顺序保持。** `PublishedRunHttp.java:277` 的 read-loop catch 仍位于 input try-with-resources 内部，`:280` 在输入流自动 close 前取消；未错误地用外层 catch 替代这一层。内外两层检查现有 cancelled 状态，避免同一同步失败沿两层重复逻辑取消；未修改 Cancellation 的公共行为。
- **✅ 旧 GET/POST 等价。** 新内外条件均排除 LEGACY，外层 catch 对 LEGACY 只 `throw failure`。原 status/code 分支、外层 PlatformException 原样传递、其它异常 safe503 映射以及 finally request.cancel/active.remove/release 保持，见 `PublishedRunHttp.java:248`、`:295`、`:301`。没有新增 API、重试、请求或通用框架。
- **⚠️ 证据审计界限。** 编译/测试执行来源、回放、继承 target 更正与物理 inventory 的独立核验由 root 有限 delta 审计负责。本审查阅读代码及冻结摘要，不把源码 diff 当成所有物理执行事实的证明；未重新跑绿测试，也未将不重跑列为缺陷。

# Strengths

- `platform-gateway/src/test/java/cn/agentplatform/gateway/application/RunObservationGetTransportTest.java:84`、`:90`：两个独立测试覆盖 Q/M 已声明长度超限、599、401、Set-Cookie 和 text/plain，不只修复一个 throw site。原八方法及 Peer 正文在本次 diff 中未改，两个新增方法合计十个 early cases。
- `RunObservationGetTransportTest.java:97`：真实 socket peer 保持声明的 body 未完成，能够区分 graceful drain 等待与取消；断言包括 under-one-second、实际固定 GET 路径、一次 accept、一次 installed handle、cancelled=true、一次 cancelled handle，`:106`。每个 peer 继续经过既有关闭/thread-join 检查。测试校验行为，不只校验内部 mock 调用。
- `PublishedRunHttp.java:288`：统一覆盖 handler 层失败，改动范围小；保留原异常对象及现有内层取消，同时有注释说明两个资源关闭层次的差异。known403/404、无效 UTF-8、增量超限及成功读取仍沿既有分支处理。
- 冻结 report/test-verification 明确区分真实 RED 两个首行失败与 GREEN 十个 case，不虚构 RED 已执行被前一断言中止的循环余项。GREEN 摘要为同一 final 命令 transport10 + chain2 = 12/0/0/0，覆盖原共享 Peer 的完整十方法以及实际 positive/partial 控制；原历史31+3仍是多命令历史。

# Issues

## Critical — 0

- 无 Critical 发现。

## Important — 0

- 原 I1 关闭：提前 handler 失败已在 Apache response close 前取消，读取中失败仍在 input close 前取消；无需追加其它源码修复。

## Minor — 0 open

- 本轮无新增 Minor。
- 原 M1 仍按已冻结独立 correction 处理：原 test-verification 的 finalSource 不能冒充对应早轮 executedSource；历史26/31差异与缺失中间文本不被本次覆盖。本次受影响 transport 整类最终执行是新证据，不重写旧历史。无需更改原封存件或为消除档案缺口重跑历史测试。

# Evidence and limits

- 已全文读取 `docs/evidence/iam-stage-4-o1c-review-fixes/test-verification.json`，其十个 early rows 各记录0–1ms、requests=1、installedHandles=1、cancelledHandles=1、cancelled=true；代码 assertions 与这些字段逐项对应。实现报告记录 RED 为Q599的2042ms及Q8193的2003ms、各一 request/handle、timer cancellation而cancelled=false；GREEN 原 incremental overflow31ms。该摘要与已读测试设计一致。本轮未再次执行或逐份重复审计原始日志/XML，root 负责执行物理证据核对。
- 实现报告明确 partial 控制的128B真实前缀、原 IOException/WARN、suppressed response exception，以及既有 deprecation 提示；没有将预期故障注入或继承编译提示写成 pristine output，也没有新增未解释的行为失败。
- root 授权的继承 target 修正须单独保留：历史2005 → 五个旧 unused class 的 modified 身份及一个 absent class → corrected baseline2004，再叠加本次245候选中的七个 modified delta → final logical2004。这六条继承偏差的具体历史发生时间未知，不应当作本次编译产物或当作全旧库存已重新审计。本轮代码未改这些 engine/fixture 源；未为本评审扫描旧 target 或要求恢复 unused class。
- 原 Child 的 fresh PID 日志按 root 授权迁至本轮 own scope，报告披露原路径不再存在，未改 fixture 源。日志迁移、403条实际使用输出身份、资源退出、完整 source585 replay 和新 inventory 的真实性由 root 的有限审计独立确认，本评审不扩大为旧证据复验。
- 本轮 task quality 批准不扩张为全局 Task09/G6E/F、完整 Boot、真实外部 IAM 或总 heap/物理 blocked-peer hard-close 保证；原设计、四契约和 T01 唯一全局完成状态保持。

# Assessment

- **Task quality：Approved。Spec compliant，C0 / I0 / M0 open。** 原 I1 的代码原因已消除，测试针对真实关闭顺序有有效回归保护；没有剩余需修改的审查问题。root 可完成独立有限 delta 审计后继续其索引与验收流程。

# Read identity and ownership

- 新 brief 全文至 EOF：`docs/evidence/iam-stage-4-o1c-review-fixes-brief.md` 7415B，SHA256 `77df1fffc85c21fee98ff5031bdeab988219f2b8bfe8f39fb883fedd24c8aff2`。
- 新 report 全文至 EOF：`docs/evidence/iam-stage-4-o1c-review-fixes-report.md` 8677B，SHA256 `add17898b113db838ab41a0e66f96bd40df9a8633257f35367c0708f750fbfc3`，50 split lines。
- 新 handoff 全文至 EOF：`docs/evidence/iam-stage-4-o1c-review-fixes/handoff.json` 6892B，SHA256 `3dad2bbeb87ea87591fec72e7a16445856873e37a249910b9715748966b0819d`，193 split lines。
- 新 full-context package 全文至 EOF：`docs/evidence/review-iam-stage-4-o1c-review-fixes-package.diff` 32847B，SHA256 `e1708256adce3e37a9a5e4a341756a994f03411597d9699d901d9b28acfe7fc5`。一次完整通读，互不重叠1–260和261–477两批，最后477为空行；无截断或全文重读。
- 新 verification 摘要全文至 EOF：`docs/evidence/iam-stage-4-o1c-review-fixes/test-verification.json` 3010B，SHA256 `0ddd513093773b0fcea40894dd9521b47788c8d60652f5f4c14515ce6f0518b2`。
- 继承原 review `780ab201d62ee3339f034893b962ff5029adc97f6d035e59ff226a0829a118e8` 已读上下文；未重新读取原12份变更全文、原依赖源码或旧大库存。本轮不需要新增 named-risk 外部源码调查。
- 仅创建本复审报告，无源码/测试/target/原报告/原证据/索引修改，无测试、probe、编译、浏览器或进程操作；每次 shell 均显式固定工程 workdir。此新报告写权交还 root，其余所有冻结写权一直由 root 持有。
