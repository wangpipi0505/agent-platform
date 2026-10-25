# 原尝试身份传播与 HTTP 异步发送 CAS

本报告对应 G2 重试许可之前的独立增量，仅修改新工程 `skill-registry` 的生产/测试源及本报告的证据文件。历史 846 项、G5 和共享 Schema 固定阶段不包含本次变更。本次没有打包、替换 live JAR、启停服务、部署、提交或发布。

## 实现与边界

- `SkillExecution.execute` 采用请求及引擎 claim 已核实的原 `attempt`，`reconcile` 采用原 `execution_fact.attempt_id`，逐层传给 `SkillAdapters`、同步 HTTP、异步 HTTP、MCP、ALGORITHM。各业务 invoke 只有带显式正数 expectedAttempt 的入口；不保留无 attempt 的兼容重载或默认 0。MCP 发现仍走既有独立 discover 路径。
- HTTP 异步 bind 在冲突时核对租户/主体/逻辑调用、attempt、run、版本、幂等键、完整 JSON 请求快照（含原始和 guarded 参数）、HTTP 协议及合同 SHA。invoke 在发送前核对显式 expectedAttempt 与快照及合同；业务参数必须等于冻结 guarded 参数。对账继续使用既有历史授权范围规则，授权范围扩大不会重写原快照。
- 发送 CAS 以首次读取的原主体、run、expectedAttempt、版本、key、request_snapshot 和合同 metadata 为条件，只有 REGISTERED/send_started=false 能成为 SENDING。读后换代或任一身份变化令 CAS 失败且零 POST；随后不能借新 binding 补齐身份。ACK、原键查询绑定和 DONE 更新同样限制原代、快照及合同 metadata，并在重新读取后核对原代。queryOnly 不进入发送 CAS，错代在查询前拒绝，未发送绑定也不允许假查询。
- **参数传播不是跨代原子发送保证。** 同步 HTTP 和 MCP 没有新增持久发送栅栏；ALGORITHM 仍使用既有 Job 流程，本次没有补齐 PREPARING、K8s UID/Pod 归属或完整有界 I/O 协议。以上不能冒称已经获得 HTTP 异步同等级的跨代原子发送保证，也不能据此关闭完整 G2。
- UNKNOWN 不重置，不生成新代许可，不自动重发，不新增重试 API 或迁移；`retryBusinessAllowed=false` 保持既有语义。合法只读 RETRYABLE_FAILURE 路径、计量、审批、Mock、CALLBACK、proof 及 MCP 预算由完整 skill 回归验证。

## 隔离环境和行为红

仅使用本工程 `.env` 加载的进程变量（不打印凭据）、JDK 21.0.12、Maven 3.9.16、本工程 `.tools/m2`、PG 16.15 `127.0.0.1:25432`。新增栅栏测试使用既有集成测试库 **agent_platform_v12**；旧 AsyncHttpExecutionTest 使用 **agent_platform_skill_async_test**。两者都不是运行库 **agent_platform_dev_v12**。其他完整模块测试的实际库列于最终执行结果。

HTTP 端点只在测试内创建 `127.0.0.1:0` 的临时服务器，固定小响应和有限等待；实际发送/查询计数写入日志。新代仅由测试显式受控 PG 事务模拟，无任何生产 reset 实现。读后 CAS 前身份变化由测试 JdbcTemplate 在真实 PG UPDATE 前插入受控事务/更新，不以 Mockito 模拟数据库竞争。旧 submitted ACK 测试使用真实 HTTP authority 夹具及屏障，其模型不是实际 engine JVM 验收。

所有 Maven 命令均从新工程根执行：

```powershell
mvn.cmd -o -B -ntp -pl skill-registry test `
  '-Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2' `
  '-Djdk.net.unixdomain.tmpdir=V:/.local/tmp'
```

定向命令在上式追加对应 `-Dtest=...`。没有 `-am`、`package` 或生产运行操作。

| 阶段 | -Dtest | 实测结果 | 日志 |
|---|---|---|---|
| 行为红：原代/绑定/查询 | HttpAsyncAttemptFenceTest | 13 tests，8 failures，0 errors/skipped | skill-expected-attempt-artifacts/red-attempt-fence.log |
| 行为红：迟到 submitted ACK | AsyncHttpExecutionTest#oldSubmittedAckCannotDispatchNewRegisteredAttempt | 1 test，1 failure，0 errors/skipped；旧线程产生 1 POST | skill-expected-attempt-artifacts/red-submitted-ack.log |
| 行为红：CAS 六种身份变化 | HttpAsyncAttemptFenceTest#identityChangeAfterReadBeforeCasNeverPosts | 6 tests，6 failures，0 errors/skipped；principal/run/version/key/snapshot/合同变化不能阻止原发送 | skill-expected-attempt-artifacts/red-cas-identity.log |
| 初次定向绿 | HttpAsyncAttemptFenceTest,AsyncHttpExecutionTest | 41/41，0 failures/errors/skipped | skill-expected-attempt-artifacts/green-directed.log |
| 中间完整轮 | 无定向限制 | 295/295，0 failures/errors/skipped，仅对应前一编译快照 | skill-expected-attempt-artifacts/green-full-skill.log |
| 行为红：claim 身份缺失/错代 | AsyncHttpExecutionTest#missingOrWrongClaimAttemptCannotDispatch | 2 tests，2 failures，0 errors/skipped；两例各产生 1 POST/1 GET | skill-expected-attempt-artifacts/red-claim-attempt.log |
| 最终定向绿 | AsyncHttpExecutionTest,SkillExecutionTest,HttpAsyncAttemptFenceTest,SkillAttemptPropagationTest | 73/73，0 failures/errors/skipped | skill-expected-attempt-artifacts/green-final-directed.log |
| **最终完整轮** | 无定向限制 | **297/297，0 failures/errors/skipped，BUILD SUCCESS，2026-10-20 08:57:39 +08:00** | skill-expected-attempt-artifacts/green-final-full-skill.log |

行为红均为成功编译后 JUnit 断言失败，不以编译错误代替。初次定向绿之后追加 claim 身份入口检查；中间完整轮仅用于检查先前编译快照，最终验收以最终源码冻结和重新完整回归为准。

## 最终回归与冻结

最终完整轮 297 项通过，较历史 skill 270 项增加 27 项；不是整工程最终统一回归，也没有纳入历史固定 846 制品。当前全部 63 份 src/POM 在最终完整轮前后 SHA 一致，且等于 frozen，见 `final-source-before.json`、`final-source-after.json`。全部最终 JUnit XML 已保存在 `final-surefire/`，逐 suite 数目及总数见 `final-test-summary.json`。日志包含既有 Mockito 动态 agent/JDK 提示，不影响上述零失败结论。

完整基线位于 `skill-expected-attempt-artifacts/baseline/skill-registry`，包含修改前全部 61 份 src/POM 文件，SHA 清单为 `baseline-sha256.json`；不能用无 HEAD 的空 git diff 作为变更证据。

最终 63 份 src/POM 文件在 `frozen/skill-registry`；`changes.json` 列出 **11 modified、2 added、0 deleted** 及修改前后 SHA。新增的两份测试为 HttpAsyncAttemptFenceTest（19 项）和 SkillAttemptPropagationTest（5 项）；既有 AsyncHttpExecutionTest 新增迟到 submitted ACK 1 项、claim 2 项，较历史 270 项增加 27 项。

`skill-expected-attempt.patch` 是以实际基线和冻结副本生成的完整上下文补丁（非空、无需 HEAD），SHA256：`90500a461d1369d293956524303665099985344523cef4a33f624f6327d1ce5b`。在 `patch-replay/` 内对全量基线实际运行 `git apply --check`、`git apply` 均为 exit 0，重放后 63 份文件 SHA 与 frozen 全相等；详见 `patch-replay-verification.json`。禁用 autocrlf 以保存实际源文件字节，因此 Git 提示部分 CRLF 新行的 whitespace 警告，字节核验通过。较早一次重放因继承父 Git 子目录而被跳过，未通过 SHA 核验；遗留 `replay/` 目录只保留旧基线，**不能**当作最终有效重放。可选清理动作 `Remove-Item -LiteralPath G:/207/项目/算法重组工具/代码/agent-platform/docs/evidence/skill-expected-attempt-artifacts/replay -Recurse -Force` 被工具自动审批拒绝，原文理由只有 `rejected: blocked by policy`，未给更具体原因；动作未执行，此后没有重试或等价删除，目录保留。精确命令及拒绝说明见 `cleanup-rejection.txt`。

全新工程 Java 调用点搜索记录为 `caller-search.txt`：受影响调用全部位于 skill-registry；服务间仍通过 HTTP，不存在需调整的其他模块 Java 调用方，因此本次未对其他模块重复编译/测试，统一跨模块回归由 root 负责。

最终完整轮实际使用的六个测试库为 `agent_platform_v12`、`agent_platform_skill_async_test`、`agent_platform_skill_callback_test`、`agent_platform_skill_mcp_test`、`agent_platform_skill_proof_test`、`agent_platform_statistics_test`，全部位于上述本工程 PG；未使用运行库 agent_platform_dev_v12。查询日志与 `http-counts.json` 相互印证。

新增 HttpAsyncAttemptFenceTest 共 19 项的实际有限端点总数为 **POST=1、GET=3**：同原代两个并发者合计只发 1 POST、查询 2 GET；queryOnly 合法原代 1 GET，模拟换代后不再 GET、不写回；其余 17 项均为零 POST/GET。新增旧 submitted ACK 测试、缺失 claim attempt、错误 claim attempt 三例各为 **POST=0、GET=0**。所有读后 CAS 前 attempt/主体/run/版本/key/快照/合同变化均为零 POST/GET，新 REGISTERED 行保持未发送。已有 AsyncHttpExecutionTest 某些范围测试会主动清零计数，因此 `http-counts.json` 保留逐测试观测，不将它们加总冒称完整网络总流量。

保护清单 `protected-before.json` 核对 878 份范围外文件，最终 872 份不变。`protected-verification.json` 如实记录六个并行变化：root 已确认更新的 `docs/open-source-references.md`、`docs/design-traceability.md`、`docs/evidence/backup-restore.md`、`docs/evidence/progress.md`、`docs/evidence/implementation-coverage.md`，及 `.tools/verify-stage-manifest.mjs`。本执行者未编辑这六份文件；范围外业务 source/POM、契约及三个 root smoke 脚本保持原 SHA。没有读取相邻旧工程，没有修改原四契约、设计正文、工期或执行台账。

完整证据（含报告、日志、基线、冻结副本、真实补丁、变化清单和最终 JUnit XML）的索引 SHA 保存在 `artifact-sha256.json`。skill target 已完成本次使用；生产/测试源码现冻结，交还 root 独立审查。本报告中的测试与隔离端点结论不替代真实模型、本体、IAM、K8s 或正式容量验收。

后续顺序：先独立复核本次身份增量；再完成算法完整协议；最后才实现与验证引擎 NOT_APPLIED/NOT_SENT 证明驱动的重试许可。制品更新及跨模块统一回归由 root 独立决定。
