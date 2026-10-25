# HTTP SUBMIT_QUERY 两项 P1 修复证据

日期：2026-10-20。供原 `review-skill-async-http.md` 评审者定向复核。最终源码已冻结；本报告不替代独立复核或真实引擎进程验收。

## 范围与输入

唯一工程根为 `G:/207/项目/算法重组工具/代码/agent-platform`。使用 receiving-code-review、test-driven-development、java-spring-boot 和 verification-before-completion 工作流。已读工程 AGENTS、implementation-brief、contracts/README、skill-invocation、原评审/实现证据和原 SHA 清单；只读核对 SkillOutbox、SkillRules、SkillAdapters、SkillClients、engine ToolCalls 的 claim/submitted/fact 权威。

冻结详设快照实测 SHA256 为 `3C72B2062BEB2E8B377F2384843EA935A0074B50C0063E5106566BBD8DCD0C4F`，与 freeze.json 一致。未改原设计契约、公共依赖、engine、OpenAPI、统计或 web；未读相邻旧工程，未启停服务，未提交 Git。

本轮业务文件精确为：

1. `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillExecution.java`
2. `skill-registry/src/main/java/cn/agentplatform/skill/infra/HttpAsyncAdapter.java`
3. `skill-registry/src/main/resources/db/migration/V308__async_preparation_delivery.sql`
4. `skill-registry/src/test/java/cn/agentplatform/skill/application/AsyncHttpExecutionTest.java`

上述四文件最终摘要见 `skill-async-fix-sha256.txt`。其余新增/写入仅本报告、同前缀测试日志和摘要清单。

## H1：查询旧任务之前核对当前授权

对账在已有引擎 UNKNOWN 状态检查后重新执行 guard 和 inputSchema；HTTP 异步任务同时检查持久 `guardedArguments`。守卫属性按集合覆盖判断，原范围必须完全被当前有效请求交集覆盖，属性集合重排不改变权限；非守卫参数仍须相等。冻结证据缺失、当前属性授权缺失或角色被撤销均默认拒绝。

拒绝以 403 `RECONCILE_SCOPE_DENIED` 返回，在 reconciliation_note 留 `AUTHORIZATION_DENIED`；不查询外部 job/key、不物化结果、不交付结果，也不把原 UNKNOWN 改成外部 FAILED。审计仅存原幂等键摘要和对账耗时。

测试包括原 `[A,B]` → 当前 `[A]` 的非空收窄、角色撤销、当前授权集缺失、冻结证据缺失；均断言查询零次、原 submit 仍一次、fact 回调零次、原 UNKNOWN 保持且 structured 为空/fileRefs 为空。正例包括冻结属性重排、授权扩大，以及原请求 AB 经守卫实际发送 A 后当前有效请求扩大为 AB。

## H2：准备记录不进入 outbox，恢复不能释放迟到发送

V308 扩充 callback_state，新增明确 `PREPARING`。登记 binding 和 UNKNOWN 占位仍在同一短事务落库，但占位显式 PREPARING，现有 outbox 仅认领 PENDING；直接 retry 和重复 execute 也不会投递准备记录。实际完成/真实未知落库后才开放 PENDING。迁移将未发送且仍为 REGISTERED 的旧 PENDING/UNKNOWN 占位隔离为 PREPARING，不重写已发送未知或完成事实。

准备记录恢复必须等引擎独立确认 UNKNOWN。恢复以短 CAS 将尚未发送的 REGISTERED binding 标为 NOT_SENT；原 `send_started` CAS 同时要求 REGISTERED。因此：发送 CAS 先赢则保留原 key 查询；恢复 CAS 先赢则即便原 submitted ACK 迟到，也不能外发或查询一个未创建的任务。重复 NOT_SENT 对账保持无查询、无重发，产生审计并保留 UNKNOWN；不把 NOT_SENT 冒充外部 FAILED 或可自行重试授权。没有事务或行锁跨 submitted RPC/外部 HTTP。

测试证据：

- CountDownLatch 阻塞 submitted RPC、登记事务已提交时，主动调用真实 SkillOutbox 扫描：事实回调零次，权威仍 EXECUTING。重复 execute/retry 不外发；此时 reconcile 被状态门禁拒绝。释放后业务仅提交一次，真实 UNKNOWN 和后续 SUCCEEDED 均可交付。
- 使用独立 PG 连接持 execution_fact `FOR UPDATE`，真实 outbox `SKIP LOCKED` 扫描为零；释放锁后原 UNKNOWN 入队可交付，重复扫描/投递不重做业务。
- submitted 已成功但响应通过第二个 latch 延迟，模拟引擎 lease 过期为 UNKNOWN；恢复 NOT_SENT 后释放旧 ACK，外部 submit/query 均为零。
- submitted ACK 丢失但 send_started=false：仅引擎 UNKNOWN 后记录 NOT_SENT；重复对账不会按 key 误采伪造完成结果。
- 真正完成事实在回调失败后仍 PENDING；旧 lease 的 late 交付保留技能侧事实，取消权威不复活。取消状态的 PREPARING 重入/重试/对账无发送、无查询、无事实投递。
- 真实发送且丢外部 ACK 后，重建进程丢失于最终事实更新之前的持久 PREPARING 图像；保留 send_started=true，按原 key 恢复成功事实，不再次提交；旧 lease 仍返回 UNKNOWN/LATE。

HTTP 权威夹具使用真实 SkillClients/HTTP，包含 REGISTERED→EXECUTING、EXECUTING→SUBMITTED CAS 和 fact accepted/late 行为；不是将这些边界全部 Mockito。它是受控状态机夹具，不是真实 engine 进程；不宣称验证了完整引擎鉴权/取消实现。发送后崩溃用例重建持久图像，未执行 OS 进程 kill。

## 红绿与最终验证

命令先从本工程 `.env` 加载进程变量（不打印），设置 Java 21，再执行：

```text
mvn -B -ntp -pl skill-registry test -Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2 -Djdk.net.unixdomain.tmpdir=V:/.local/tmp
```

定向增加 `-Dtest=AsyncHttpExecutionTest`；两条发送栅栏定向红测试使用 `AsyncHttpExecutionTest#interruptedBeforeSubmittedRecordsNotSentOnlyAfterAuthorityBecomesUnknown+unknownAuthorityFencesLateSubmittedAcknowledgementBeforeAnyBusinessSend`。全程不带 `-am`。

| 日志 | 实际结果 |
| --- | --- |
| skill-async-fix-red.log | 14 项，5 个断言失败，0 errors/skipped，05:26:19。AB→A 和缺冻结证据未拒绝；角色/授权缺失异常未被归一并审计；预登记 outbox 实际投递 1 次。 |
| skill-async-fix-recovery-red.log | 17 项，6 个断言失败，0 errors/skipped，05:27:20。增加未发送恢复缺少 NOT_SENT 的失败。 |
| skill-async-fix-green.log | 首次修复 17/17，05:28:14。 |
| skill-async-fix-full.log | 中间版本全模块 97/97，async 20 项，05:29:29。不是最终版本。 |
| skill-async-fix-send-fence-red.log | 2 项，2 个断言失败，0 errors/skipped，05:31:22。重复 NOT_SENT 查询误采完成；迟到 submitted ACK 仍发送 1 次。 |
| skill-async-fix-send-fence-green.log | async 21/21，0 failures/errors/skipped，05:32:04。 |
| skill-async-fix-full-final.log | **最终全模块 98/98，async 21/21，0 failures/errors/skipped，BUILD SUCCESS，05:33:24 +08:00。** |

原完整基线为 85 项，本轮增加 13 项，最终 98 项。最终三份 Java 文件经 google-java-format 1.25.2 `--aosp --dry-run --set-exit-if-changed` 验证 exit 0，最终全套运行包含格式化后的源码。日志保留已有 Mockito 动态代理与 unchecked 提示，不将它们写成零警告。

真实 PG 测试仅使用本工程 25432 的 `agent_platform_skill_async_test`、`agent_platform_v12`、`agent_platform_statistics_test`；运行库 `agent_platform_dev_v12` 未迁移或清理。大结果原有测试使用真实受控 MinIO 路径。

## 未完成边界

未发送调用被可靠识别并阻断之后，业务自动恢复仍需后续引擎授权 NOT_APPLIED/RETRY 路径；本轮没有实现该协议或宣称全恢复闭环。CALLBACK 签名回调、取消三态、人工审计产品闭环、真实 engine 进程纵切和外部供应商验收不在本轮。两项 P1 的修复证据不能计作全部 T11 完成，待原评审者定向关闭。
